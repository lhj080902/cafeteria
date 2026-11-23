'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const AdmZip = require('adm-zip');
const { BUILTIN_MOD_IDS, CAFETERIA_MOD_ID } = require('./constants');
const { LauncherError } = require('./errors');
const { exists, readJson, writeFileAtomic } = require('./fs-util');
const http = require('./http');

const MODRINTH_API = 'https://api.modrinth.com/v2';

const ENABLED_SUFFIX = '.jar';
const DISABLED_SUFFIX = '.jar.disabled';
const FILE_NAME_PATTERN = /^[^\\/:*?"<>|\u0000-\u001f]+\.jar(\.disabled)?$/i;
/** 목록에 보여 줄 모드 아이콘의 최대 크기. 이보다 큰 이미지는 무시한다. */
const MAX_ICON_BYTES = 256 * 1024;

/**
 * 게임 폴더의 mods 폴더를 관리한다.
 * - 켜진 모드는 "이름.jar", 꺼진 모드는 "이름.jar.disabled" 로 둔다 (Fabric 은 .jar 만 읽는다).
 * - 런처로 받은 모드의 출처(Modrinth/CurseForge 프로젝트 ID)는 cafeteria-mods.json 에 기록해서
 *   "이미 설치됨" 표시와 의존성 중복 설치 방지에 쓴다.
 */
class ModManager {
  constructor(gameDir) {
    this.gameDir = gameDir;
    this.modsDir = path.join(gameDir, 'mods');
    this.indexFile = path.join(gameDir, 'cafeteria-mods.json');
    this.metadataCache = new Map();
  }

  /** 화면에서 넘어온 파일 이름이 mods 폴더 안의 모드 파일을 가리키는지 확인하고 전체 경로를 돌려준다. */
  resolve(fileName) {
    if (typeof fileName !== 'string' || fileName !== path.basename(fileName) || !FILE_NAME_PATTERN.test(fileName)) {
      throw new LauncherError('BAD_FILE_NAME', '올바르지 않은 모드 파일 이름입니다.');
    }

    return path.join(this.modsDir, fileName);
  }

  async readIndex() {
    const index = await readJson(this.indexFile, {});
    return index && typeof index.files === 'object' && index.files !== null ? index : { files: {} };
  }

  /** 다운로드한 파일의 출처를 기록한다. */
  async record(fileName, info) {
    const index = await this.readIndex();
    index.files[baseName(fileName)] = info;
    await writeFileAtomic(this.indexFile, JSON.stringify(index, null, 2));
  }

  async forget(fileName) {
    const index = await this.readIndex();

    if (index.files[baseName(fileName)]) {
      delete index.files[baseName(fileName)];
      await writeFileAtomic(this.indexFile, JSON.stringify(index, null, 2));
    }
  }

  /** 지금 mods 폴더에 있는 파일 중 주어진 출처에서 받은 프로젝트 ID 목록. */
  async installedProjects(source) {
    const index = await this.readIndex();
    const present = new Set((await this.fileNames()).map(baseName));
    const projects = new Set();

    for (const [name, info] of Object.entries(index.files)) {
      // 직접 넣은 파일이라도 그 사이트의 모드로 확인된 것(origin)은 설치된 것으로 센다.
      if (present.has(name) && info && (info.source === source || info.origin === source) && info.projectId) {
        projects.add(String(info.projectId));
      }
    }

    return projects;
  }

  async fileNames() {
    let entries;

    try {
      entries = await fsp.readdir(this.modsDir, { withFileTypes: true });
    } catch (error) {
      if (error.code === 'ENOENT') {
        return [];
      }

      throw error;
    }

    return entries
      .filter((entry) => entry.isFile() && FILE_NAME_PATTERN.test(entry.name))
      .map((entry) => entry.name);
  }

  async list() {
    const index = await this.readIndex();
    const mods = [];

    for (const fileName of await this.fileNames()) {
      const file = path.join(this.modsDir, fileName);
      const stat = await fsp.stat(file);
      const metadata = this.readMetadata(file, stat);
      const info = index.files[baseName(fileName)] || {};

      mods.push({
        fileName,
        enabled: !fileName.toLowerCase().endsWith(DISABLED_SUFFIX),
        size: stat.size,
        modId: metadata.id,
        name: metadata.name || info.title || baseName(fileName),
        version: metadata.version,
        description: metadata.description,
        // jar 안에 아이콘이 없으면 Modrinth 에서 찾은 아이콘 주소를 쓴다.
        icon: metadata.icon || info.iconUrl || null,
        source: info.source || 'local',
        builtin: BUILTIN_MOD_IDS.includes(metadata.id),
      });
    }

    // 기본 모드를 맨 위에, 나머지는 이름순으로
    return mods.sort((a, b) => Number(b.builtin) - Number(a.builtin) || a.name.localeCompare(b.name, 'ko'));
  }

  /** jar 안의 fabric.mod.json 에서 이름과 버전을 읽는다. 읽을 수 없는 파일은 빈 정보로 취급한다. */
  readMetadata(file, stat) {
    const cacheKey = `${file}|${stat.size}|${stat.mtimeMs}`;

    if (this.metadataCache.has(cacheKey)) {
      return this.metadataCache.get(cacheKey);
    }

    let metadata = {};

    try {
      const zip = new AdmZip(file);
      const entry = zip.getEntry('fabric.mod.json');

      if (entry) {
        const json = JSON.parse(entry.getData().toString('utf8'));
        metadata = {
          id: typeof json.id === 'string' ? json.id : undefined,
          name: typeof json.name === 'string' ? json.name : undefined,
          version: typeof json.version === 'string' ? json.version : undefined,
          description: typeof json.description === 'string' ? json.description : undefined,
          icon: readIcon(zip, json.icon),
        };
      }
    } catch {
      // 손상된 jar 이거나 Fabric 모드가 아닌 파일: 목록에는 파일 이름으로만 보여 준다.
    }

    this.metadataCache.set(cacheKey, metadata);
    return metadata;
  }

  /**
   * 직접 넣은 모드 파일(출처를 모르는 파일)을 파일 해시로 Modrinth 에서 찾아, 이름·아이콘·프로젝트 정보를 기록한다.
   * 한 번 찾아본 파일은 다시 묻지 않는다. 연결에 실패하면 아무것도 기록하지 않아 다음에 다시 시도한다.
   *
   * @returns {Promise<boolean>} 새로 알아낸 모드가 있으면 true
   */
  async identifyLocalMods() {
    const index = await this.readIndex();
    const unknown = [];

    for (const fileName of await this.fileNames()) {
      const name = baseName(fileName);
      const info = index.files[name];

      if (!info || (info.source === 'local' && !info.lookedUp)) {
        unknown.push({ name, hash: await sha1OfFile(path.join(this.modsDir, fileName)) });
      }
    }

    if (unknown.length === 0) {
      return false;
    }

    let versions;
    let projects = [];

    try {
      versions = await http.postJson(`${MODRINTH_API}/version_files`, { hashes: unknown.map((file) => file.hash), algorithm: 'sha1' });
      const projectIds = [...new Set(Object.values(versions || {}).map((version) => version.project_id).filter(Boolean))];

      if (projectIds.length > 0) {
        projects = await http.getJson(`${MODRINTH_API}/projects?${new URLSearchParams({ ids: JSON.stringify(projectIds) })}`);
      }
    } catch {
      return false;
    }

    const projectById = new Map((Array.isArray(projects) ? projects : []).map((project) => [project.id, project]));
    let found = false;

    for (const { name, hash } of unknown) {
      const version = versions && versions[hash];
      const project = version && projectById.get(version.project_id);
      const previous = index.files[name] || { source: 'local' };

      if (project) {
        found = true;
        index.files[name] = {
          ...previous,
          lookedUp: true,
          origin: 'modrinth',
          projectId: project.id,
          versionId: version.id,
          title: project.title,
          iconUrl: typeof project.icon_url === 'string' && project.icon_url.startsWith('https://cdn.modrinth.com/') ? project.icon_url : undefined,
        };
      } else {
        index.files[name] = { ...previous, lookedUp: true };
      }
    }

    await writeFileAtomic(this.indexFile, JSON.stringify(index, null, 2));
    return found;
  }

  /**
   * PC에 있는 jar 파일을 mods 폴더로 복사한다.
   * 같은 모드(fabric.mod.json 의 id 가 같은 것)의 다른 파일이 이미 있으면 새 파일로 교체한다.
   * 직접 만든 모드처럼 사이트에 없는 모드는 이렇게 새 jar 를 넣는 것이 곧 업데이트다.
   *
   * @returns {Promise<{added: string[], rejected: string[], replaced: {old: string, new: string}[]}>}
   */
  async addLocalFiles(filePaths) {
    await fsp.mkdir(this.modsDir, { recursive: true });
    const added = [];
    const rejected = [];
    const replaced = [];

    for (const source of filePaths) {
      const fileName = typeof source === 'string' ? path.basename(source) : '';
      const stat = fileName ? await fsp.stat(source).catch(() => null) : null;

      if (!stat || !stat.isFile() || !fileName.toLowerCase().endsWith(ENABLED_SUFFIX) || !FILE_NAME_PATTERN.test(fileName)) {
        rejected.push(fileName || String(source));
        continue;
      }

      const modId = this.readMetadata(source, stat).id;

      // Cafeteria 모드는 런처에 들어 있는 것을 쓴다. 따로 넣으면 같은 모드가 두 벌이 되어 게임이 켜지지 않는다.
      if (modId === CAFETERIA_MOD_ID) {
        rejected.push(fileName);
        continue;
      }

      const destination = path.join(this.modsDir, fileName);

      if (path.resolve(source) !== path.resolve(destination)) {
        await fsp.copyFile(source, destination);
        // 같은 모드가 꺼진 상태로 남아 있으면 지워서 한 벌만 남긴다.
        await fsp.rm(`${destination}.disabled`, { force: true });
      }

      for (const old of modId ? await this.filesOfMod(modId, fileName) : []) {
        await fsp.rm(path.join(this.modsDir, old), { force: true });
        await this.forget(old);
        replaced.push({ old, new: fileName });
      }

      await this.record(fileName, { source: 'local' });
      added.push(fileName);
    }

    return { added, rejected, replaced };
  }

  /** mods 폴더에서 주어진 모드 id 를 가진 파일들 (except 로 준 파일은 뺀다). 꺼진 파일도 포함한다. */
  async filesOfMod(modId, except) {
    const found = [];

    for (const name of await this.fileNames()) {
      if (baseName(name) === baseName(except)) {
        continue;
      }

      const file = path.join(this.modsDir, name);

      if (this.readMetadata(file, await fsp.stat(file)).id === modId) {
        found.push(name);
      }
    }

    return found;
  }

  /**
   * 예전 모드 파일을, 이미 mods 폴더에 받아 둔 새 파일(newName)로 바꾼다 (모드 업데이트).
   * 예전 파일이 꺼져 있었으면 새 파일도 꺼 둔다.
   *
   * @returns {Promise<string>} 바꾼 뒤의 파일 이름
   */
  async replaceFile(oldFileName, newName, { enabled, ...info }) {
    this.resolve(newName);

    if (baseName(oldFileName) !== newName) {
      await fsp.rm(this.resolve(oldFileName), { force: true });
      await this.forget(oldFileName);
    } else if (oldFileName !== newName) {
      // 이름이 같은 새 파일을 받았는데 예전 것이 꺼진 상태(.disabled)로 남아 있는 경우
      await fsp.rm(this.resolve(oldFileName), { force: true });
    }

    await this.record(newName, info);
    return enabled ? newName : this.setEnabled(newName, false);
  }

  async setEnabled(fileName, enabled) {
    const current = this.resolve(fileName);
    await this.assertNotBuiltin(current);
    const base = baseName(fileName);
    const target = path.join(this.modsDir, enabled ? base : `${base}.disabled`);

    if (current !== target) {
      await fsp.rm(target, { force: true });
      await fsp.rename(current, target);
    }

    return path.basename(target);
  }

  async remove(fileName) {
    const file = this.resolve(fileName);
    await this.assertNotBuiltin(file);
    await fsp.rm(file, { force: true });
    await this.forget(fileName);
  }

  async assertNotBuiltin(file) {
    if (!(await exists(file))) {
      throw new LauncherError('MOD_NOT_FOUND', '모드 파일을 찾을 수 없습니다. 목록을 새로 고쳐 주세요.');
    }

    const metadata = this.readMetadata(file, await fsp.stat(file));

    if (BUILTIN_MOD_IDS.includes(metadata.id)) {
      throw new LauncherError('BUILTIN_MOD', '카페테리아 기본 모드는 끄거나 지울 수 없습니다.');
    }
  }
}

function sha1OfFile(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha1');
    fs.createReadStream(file)
      .on('data', (chunk) => hash.update(chunk))
      .on('error', reject)
      .on('end', () => resolve(hash.digest('hex')));
  });
}

/** fabric.mod.json 의 icon 항목이 가리키는 PNG 를 data URI 로 읽는다. 없거나 너무 크면 undefined. */
function readIcon(zip, iconPath) {
  if (typeof iconPath !== 'string' || !iconPath.toLowerCase().endsWith('.png')) {
    return undefined;
  }

  const entry = zip.getEntry(iconPath.replace(/^\.?\//, ''));

  if (!entry || entry.isDirectory || entry.header.size > MAX_ICON_BYTES) {
    return undefined;
  }

  return `data:image/png;base64,${entry.getData().toString('base64')}`;
}

/** "이름.jar.disabled" → "이름.jar" */
function baseName(fileName) {
  return fileName.toLowerCase().endsWith(DISABLED_SUFFIX) ? fileName.slice(0, -'.disabled'.length) : fileName;
}

module.exports = { ModManager, baseName, sha1OfFile };
