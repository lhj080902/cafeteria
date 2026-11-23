'use strict';

const path = require('node:path');
const { DOWNLOAD_HOSTS, FABRIC_API } = require('./constants');
const { LauncherError } = require('./errors');
const http = require('./http');

const MAX_DEPENDENCY_DEPTH = 5;
const SAFE_FILE_NAME = /^[^\\/:*?"<>|\u0000-\u001f]+\.jar$/i;

/** 런처가 플레이할 때 항상 넣어 주는 Fabric API 는 의존성으로 다시 받지 않는다. */
const BUILTIN_PROJECTS = {
  modrinth: new Set([FABRIC_API.modrinthProjectId, 'fabric-api']),
  curseforge: new Set([FABRIC_API.curseforgeProjectId]),
};

/**
 * 모드 하나와 그 모드가 꼭 필요로 하는 모드(필수 의존성)를 내려받아 mods 폴더에 넣는다.
 *
 * @param {object} options
 * @param {'modrinth'|'curseforge'} options.source
 * @param {string} options.projectId
 * @param {(projectId: string) => Promise<object>} options.resolveFile 프로젝트에서 받을 파일을 고르는 함수
 * @param {import('./mods').ModManager} options.modManager
 * @param {(progress: {message: string, fraction: number}) => void} [options.onProgress]
 * @returns {Promise<{installed: string[], warnings: string[]}>} 설치한 모드 이름과 자동 설치하지 못한 의존성 안내
 */
async function installMod({ source, projectId, resolveFile, modManager, onProgress = () => {} }) {
  const alreadyInstalled = await modManager.installedProjects(source);
  const builtin = BUILTIN_PROJECTS[source] || new Set();
  const visited = new Set();
  const installed = [];
  const warnings = [];

  async function installOne(id, depth) {
    const isDependency = depth > 0;

    if (visited.has(id) || builtin.has(id)) {
      return;
    }

    visited.add(id);

    if (alreadyInstalled.has(id)) {
      if (!isDependency) {
        throw new LauncherError('ALREADY_INSTALLED', '이미 설치된 모드입니다.');
      }

      return;
    }

    let file;

    try {
      file = await resolveFile(id);
    } catch (error) {
      if (!isDependency) {
        throw error;
      }

      // 의존성 하나를 못 받았다고 본 모드 설치까지 실패시키지는 않고, 무엇이 빠졌는지 알려 준다.
      warnings.push(`필수 모드(${id})를 자동으로 설치하지 못했습니다: ${error.message}`);
      return;
    }

    if (builtin.has(file.projectId)) {
      return;
    }

    const fileName = path.basename(String(file.fileName));

    if (!SAFE_FILE_NAME.test(fileName)) {
      throw new LauncherError('BAD_FILE_NAME', `"${file.title}"의 파일 이름이 올바르지 않아 설치하지 않았습니다.`);
    }

    await http.download(file.url, path.join(modManager.modsDir, fileName), {
      allowedHosts: DOWNLOAD_HOSTS,
      hashes: file.hashes,
      onProgress: (received, total) => onProgress({ message: `${file.title} 내려받는 중...`, fraction: total > 0 ? received / total : 0 }),
    });
    await modManager.record(fileName, { source, projectId: String(file.projectId), versionId: file.versionId, title: file.title });
    installed.push(file.title);

    if (depth < MAX_DEPENDENCY_DEPTH) {
      for (const dependency of file.dependencies) {
        await installOne(String(dependency), depth + 1);
      }
    }
  }

  await installOne(String(projectId), 0);
  return { installed, warnings };
}

module.exports = { installMod };
