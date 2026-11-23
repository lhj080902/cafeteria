'use strict';

const path = require('node:path');
const { BUILTIN_MOD_IDS, DOWNLOAD_HOSTS } = require('./constants');
const { LauncherError } = require('./errors');
const http = require('./http');
const { installMod } = require('./mod-installer');
const { baseName, sha1OfFile } = require('./mods');
const curseforge = require('./sources/curseforge');
const modrinth = require('./sources/modrinth');

/*
 * 설치된 모드의 새 버전을 찾고, 예전 파일을 새 파일로 바꿔 넣는다.
 *
 * - Modrinth: 파일 해시로 "지금 어떤 버전인지"를 찾으므로, 런처로 받은 모드뿐 아니라 직접 넣은 모드도
 *   Modrinth 에 올라와 있으면 함께 확인된다.
 * - CurseForge: 런처로 받은 모드만 확인한다 (API 키 필요).
 * - 기본 모드(Cafeteria, Fabric API)는 런처가 관리하므로 런처 업데이트와 함께 바뀐다.
 * - 어느 사이트에도 없는 모드(직접 만든 모드 등)는 새 jar 를 "모드 파일 추가"로 넣으면 예전 파일과 교체된다.
 */

const SAFE_FILE_NAME = /^[^\\/:*?"<>|\u0000-\u001f]+\.jar$/i;
const PARALLEL_REQUESTS = 4;

/** 한 번에 PARALLEL_REQUESTS 개씩 처리한다. */
async function mapLimited(items, task) {
  const results = [];

  for (let index = 0; index < items.length; index += PARALLEL_REQUESTS) {
    results.push(...(await Promise.all(items.slice(index, index + PARALLEL_REQUESTS).map(task))));
  }

  return results;
}

/**
 * @typedef {object} ModUpdate
 * @property {string} fileName 지금 mods 폴더에 있는 파일 이름
 * @property {string} name 모드 이름
 * @property {'modrinth'|'curseforge'} source 새 파일을 받을 곳
 * @property {string} currentVersion
 * @property {string} newVersion
 * @property {object} file 받을 파일 정보 (sources/*.js 의 resolveFile 결과와 같은 모양)
 */

/**
 * 새 버전이 있는 모드를 찾는다. 아무것도 바꾸지 않는다.
 *
 * @param {object} options
 * @param {import('./mods').ModManager} options.modManager
 * @param {string} [options.curseforgeKey]
 * @returns {Promise<{updates: ModUpdate[], checked: number, unknown: string[], notes: string[]}>}
 *   checked: 새 버전 여부를 확인한 모드 수, unknown: 어느 사이트에서도 찾지 못한 모드 이름, notes: 화면에 보여 줄 안내
 */
async function checkModUpdates({ modManager, curseforgeKey = '' }) {
  const index = await modManager.readIndex();
  const mods = (await modManager.list()).filter((mod) => !mod.builtin && !BUILTIN_MOD_IDS.includes(mod.modId));
  const updates = [];
  const unknown = [];
  const notes = [];
  let checked = 0;

  const entries = mods.map((mod) => ({ mod, info: index.files[baseName(mod.fileName)] || {} }));
  // CurseForge 에서 받은 모드는 키가 있으면 CurseForge 에서, 없으면 Modrinth 에 같은 파일이 있는지로 확인한다.
  const viaCurseforge = entries.filter(({ info }) => info.source === 'curseforge' && info.projectId && curseforgeKey);
  const viaModrinth = entries.filter((entry) => !viaCurseforge.includes(entry));

  for (const entry of viaModrinth) {
    entry.hash = await sha1OfFile(path.join(modManager.modsDir, entry.mod.fileName));
  }

  const identified = await modrinth.identifyFiles(viaModrinth.map((entry) => entry.hash));

  await mapLimited(viaModrinth, async ({ mod, info, hash }) => {
    const current = identified[hash];

    if (!current || !current.project_id) {
      if (info.source === 'curseforge') {
        notes.push(`"${mod.name}"은(는) CurseForge에서 받은 모드라서, 설정에 CurseForge API 키가 있어야 새 버전을 확인할 수 있습니다.`);
      } else {
        unknown.push(mod.name);
      }

      return;
    }

    try {
      const newer = modrinth.pickNewerVersion(current, await modrinth.listVersions(current.project_id));
      checked += 1;

      if (newer) {
        updates.push({
          fileName: mod.fileName,
          name: mod.name,
          source: 'modrinth',
          currentVersion: mod.version || current.version_number || '',
          newVersion: newer.version_number || '',
          file: await modrinth.describeVersion({ ...newer, project_id: current.project_id }, info.title || mod.name),
        });
      }
    } catch (error) {
      notes.push(`"${mod.name}"의 새 버전을 확인하지 못했습니다: ${error.message}`);
    }
  });

  await mapLimited(viaCurseforge, async ({ mod, info }) => {
    try {
      const file = await curseforge.resolveFile(String(info.projectId), curseforgeKey);
      checked += 1;

      // CurseForge 의 파일 번호는 올라온 순서대로 커진다.
      if (Number(file.versionId) > Number(info.versionId)) {
        updates.push({ fileName: mod.fileName, name: mod.name, source: 'curseforge', currentVersion: mod.version || '', newVersion: file.version, file });
      }
    } catch (error) {
      notes.push(`"${mod.name}"의 새 버전을 확인하지 못했습니다: ${error.message}`);
    }
  });

  updates.sort((a, b) => a.name.localeCompare(b.name, 'ko'));
  return { updates, checked, unknown, notes };
}

/**
 * 모드 하나를 새 버전으로 바꾼다. 새 파일을 다 받고 검증한 뒤에 예전 파일을 지우므로, 중간에 실패해도 예전 모드는 그대로 남는다.
 * 새 버전이 추가로 요구하는 필수 모드가 있으면 함께 설치한다.
 *
 * @param {object} options
 * @param {import('./mods').ModManager} options.modManager
 * @param {ModUpdate} options.update checkModUpdates 가 돌려준 항목
 * @param {(projectId: string) => Promise<object>} options.resolveFile 필수 모드를 받을 때 쓸 함수
 * @param {(progress: {message: string, fraction: number}) => void} [options.onProgress]
 * @returns {Promise<{fileName: string, installed: string[], warnings: string[]}>}
 */
async function applyModUpdate({ modManager, update, resolveFile, onProgress = () => {} }) {
  const oldFile = modManager.resolve(update.fileName);
  await modManager.assertNotBuiltin(oldFile);
  const newName = path.basename(String(update.file.fileName));

  if (!SAFE_FILE_NAME.test(newName)) {
    throw new LauncherError('BAD_FILE_NAME', `"${update.name}"의 새 파일 이름이 올바르지 않아 업데이트하지 않았습니다.`);
  }

  const wasEnabled = baseName(update.fileName) === update.fileName;

  await http.download(update.file.url, path.join(modManager.modsDir, newName), {
    allowedHosts: DOWNLOAD_HOSTS,
    hashes: update.file.hashes,
    onProgress: (received, total) => onProgress({ message: `${update.name} 내려받는 중...`, fraction: total > 0 ? received / total : 0 }),
  });

  const fileName = await modManager.replaceFile(update.fileName, newName, {
    source: update.source,
    projectId: String(update.file.projectId),
    versionId: update.file.versionId,
    title: update.file.title,
    enabled: wasEnabled,
  });

  // 새 버전이 새로 요구하는 필수 모드를 채운다. 이미 있는 모드와 기본 모드는 건너뛴다.
  const installed = [];
  const warnings = [];
  const present = await modManager.installedProjects(update.source);

  for (const dependency of update.file.dependencies || []) {
    if (present.has(String(dependency))) {
      continue;
    }

    try {
      const result = await installMod({ source: update.source, projectId: String(dependency), resolveFile, modManager, onProgress });
      installed.push(...result.installed);
      warnings.push(...result.warnings);
    } catch (error) {
      warnings.push(`"${update.name}"에 필요한 모드(${dependency})를 설치하지 못했습니다: ${error.message}`);
    }
  }

  return { fileName, installed, warnings };
}

module.exports = { checkModUpdates, applyModUpdate };
