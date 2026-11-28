'use strict';

const crypto = require('node:crypto');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { applyClientSettings, validateClientSettings } = require('./client-settings');
const { BUILTIN_MOD_IDS, DOWNLOAD_HOSTS, SUPERSEDED_MOD_IDS } = require('./constants');
const { LauncherError } = require('./errors');
const { exists, writeFileAtomic } = require('./fs-util');
const http = require('./http');
const { baseName, sha1OfFile } = require('./mods');
const { LIMITS, PROFILE_EXTENSION, packProfile, readProfile } = require('./profile-file');

/*
 * 모드 프로필: 켜 둔 모드 구성(원하면 게임 안 Cafeteria 설정도)을 파일 하나로 내보내고, 그렇게 받은 파일을 게임 폴더에 적용한다.
 * 파일의 형식과 받은 파일을 검사하는 규칙은 profile-file.js 에 있다.
 *
 * 프로필 파일은 모르는 사람이 만든 것일 수 있다. 적용할 때는 필요한 것을 전부 준비한 뒤에만 mods 폴더를 바꾸고,
 * 바꾸는 도중에 실패하면 그때까지 바꾼 것을 되돌린다.
 */

/** 게임과 로더 자신이 쓰는 모드 id. 이 id 를 가진 jar 는 프로필로 들여오지 않는다. */
const RESERVED_MOD_IDS = ['minecraft', 'java', 'fabricloader'];
/** 프로필의 jar 가 가지면 안 되는 모드 id: 런처가 관리하는 모드, Cafeteria 에 내장된 기능과 겹치는 모드, 게임과 로더. */
const FORBIDDEN_MOD_IDS = [...BUILTIN_MOD_IDS, ...SUPERSEDED_MOD_IDS, ...RESERVED_MOD_IDS];

/** 적용하는 동안 mods 폴더 안에 만드는 임시 폴더의 이름 앞부분. jar 파일이 아니라서 런처도 게임도 모드로 읽지 않는다. */
const TEMPORARY_PREFIX = '.cafeteria-profile-tmp-';
/** 임시 폴더 안에서, 새 파일에 자리를 내준 예전 파일을 옮겨 두는 폴더. */
const REPLACED_DIRECTORY = 'replaced';
/** 다른 프로그램(대개 켜져 있는 게임)이 파일을 잡고 있을 때 나는 오류 코드. */
const IN_USE_CODES = ['EBUSY', 'EPERM', 'EACCES'];
/** 진행률에서 모드를 준비하는 단계가 차지하는 몫. 나머지는 mods 폴더를 바꾸는 단계다. */
const PREPARE_SHARE = 0.9;

function lookupFailed(error) {
  return new LauncherError('PROFILE_LOOKUP_FAILED', 'Modrinth 에서 모드 정보를 확인하지 못했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요.', {
    cause: error.code,
  });
}

function sha1OfBuffer(data) {
  return crypto.createHash('sha1').update(data).digest('hex');
}

/** Windows 에서는 대소문자만 다른 이름도 같은 파일이다. */
function sameName(first, second) {
  return first.toLowerCase() === second.toLowerCase();
}

/**
 * identifyFiles 의 답에서 이 해시를 가진 파일을 찾는다. Modrinth 의 버전 하나에는 파일이 여럿 있을 수 있다.
 *
 * @returns {{version: object, file: object} | null}
 */
function findModrinthFile(identified, sha1) {
  const version = identified ? identified[sha1] : null;
  const files = version && Array.isArray(version.files) ? version.files : [];
  const file = files.find((candidate) => candidate && candidate.hashes && candidate.hashes.sha1 === sha1);

  return file ? { version, file } : null;
}

/** 프로필에 넣을 Cafeteria 설정 파일의 내용. 파일이 없거나, 너무 크거나, 설정처럼 생기지 않았으면 null (프로필은 설정 없이 만든다). */
async function readSettingsToExport(gameDir, limits) {
  try {
    const data = await fsp.readFile(path.join(gameDir, 'config', 'cafeteria.json'));
    validateClientSettings(JSON.parse(data.toString('utf8')));
    // 받는 쪽이 읽지 않을 크기면 넣지 않는다.
    return data.length <= limits.settingsBytes ? data : null;
  } catch {
    return null;
  }
}

/**
 * 켜 둔 모드 구성을 프로필 파일로 내보낸다. 기본 모드와, Cafeteria 에 내장돼 따로 쓰면 안 되는 모드는 켜져 있어도 넣지 않는다.
 * Modrinth 에 있는 모드는 해시만 적고, 나머지는 jar 를 파일째 넣는다.
 *
 * @param {object} options
 * @param {import('./mods').ModManager} options.modManager
 * @param {string} options.gameDir Cafeteria 게임 폴더
 * @param {string} options.filePath 내보낼 파일
 * @param {string} [options.name] 프로필 이름
 * @param {boolean} [options.includeSettings] 게임 안 Cafeteria 설정도 넣을지
 * @param {string} options.appVersion 런처 버전
 * @param {(hashes: string[]) => Promise<Record<string, object>>} options.identifyFiles sources/modrinth.js 의 identifyFiles
 * @param {typeof LIMITS} [options.limits]
 * @returns {Promise<{mods: number, embedded: string[], hasSettings: boolean}>} embedded: 파일째 넣은 모드의 파일 이름
 */
async function exportProfile({ modManager, gameDir, filePath, name, includeSettings, appVersion, identifyFiles, limits = LIMITS }) {
  const targets = [];

  for (const mod of await modManager.list()) {
    if (mod.enabled && !mod.builtin && !mod.superseded) {
      const file = path.join(modManager.modsDir, mod.fileName);
      targets.push({ mod, file, sha1: await sha1OfFile(file) });
    }
  }

  let identified;

  try {
    identified = await identifyFiles(targets.map((target) => target.sha1));
  } catch (error) {
    throw lookupFailed(error);
  }

  const mods = [];

  for (const { mod, file, sha1 } of targets) {
    const item = { kind: 'modrinth', title: mod.name, version: mod.version || '', fileName: mod.fileName, sha1, size: mod.size };

    if (!findModrinthFile(identified, sha1)) {
      if (mod.size > limits.jarBytes) {
        throw new LauncherError('PROFILE_TOO_LARGE', `프로필에 넣기에는 너무 큰 모드 파일이 있습니다: ${mod.fileName}`);
      }

      const data = await fsp.readFile(file);
      // 실제로 넣는 내용의 해시와 크기를 적는다. 해시를 구한 뒤에 파일이 바뀌었더라도 프로필 안에서는 서로 맞는다.
      Object.assign(item, { kind: 'file', sha1: sha1OfBuffer(data), size: data.length, data });
    }

    mods.push(item);
  }

  const settings = includeSettings ? await readSettingsToExport(gameDir, limits) : null;
  const content = packProfile({ name, createdWith: appVersion, mods, settings });

  if (content.length > limits.fileBytes) {
    throw new LauncherError('PROFILE_TOO_LARGE', '프로필 파일이 너무 큽니다. 파일째 들어가는 모드를 줄여 주세요.');
  }

  try {
    // 임시 이름으로 쓴 뒤 이름을 바꾸므로, 쓰다 실패한 반쪽 파일이 남지 않는다.
    await writeFileAtomic(filePath, content);
  } catch (error) {
    throw new LauncherError('PROFILE_EXPORT_FAILED', '프로필 파일을 저장하지 못했습니다. 다른 위치를 골라 주세요.', { cause: error.code });
  }

  return {
    mods: mods.length,
    embedded: mods.filter((mod) => mod.kind === 'file').map((mod) => mod.fileName),
    hasSettings: settings !== null,
  };
}

/**
 * 프로필 파일에 무엇이 들어 있는지 알려 준다 (적용하기 전에 사용자에게 보여 줄 요약). 아무것도 바꾸지 않는다.
 *
 * @param {object} options
 * @param {string} options.filePath
 * @param {import('./mods').ModManager} options.modManager
 * @param {typeof LIMITS} [options.limits]
 * @returns {Promise<{name: string, createdWith: string, hasSettings: boolean, fingerprint: string, mods: {title: string, version: string, fileName: string, kind: 'modrinth' | 'file', size: number, present: boolean}[]}>}
 *   fingerprint: 파일 내용의 sha256. applyProfile 에 넘기면 요약을 본 뒤에 파일이 바뀌지 않았는지 확인한다.
 *   present: 같은 내용의 파일이 mods 폴더에 이미 있는지 (꺼져 있어도 있는 것이다)
 */
async function inspectProfile({ filePath, modManager, limits = LIMITS }) {
  const profile = await readProfile(filePath, limits);
  const presentHashes = new Set();

  for (const fileName of await modManager.fileNames()) {
    presentHashes.add(await sha1OfFile(path.join(modManager.modsDir, fileName)));
  }

  return {
    name: profile.name,
    createdWith: profile.createdWith,
    hasSettings: profile.settings !== null,
    fingerprint: profile.fingerprint,
    mods: profile.mods.map(({ title, version, fileName, kind, size, sha1 }) => ({ title, version, fileName, kind, size, present: presentHashes.has(sha1) })),
  };
}

/** mods 폴더에 지금 있는 모드 파일들 (켜진 것, 꺼진 것 모두). */
async function scanMods(modManager) {
  const files = [];

  for (const fileName of await modManager.fileNames()) {
    const file = path.join(modManager.modsDir, fileName);

    files.push({
      fileName,
      enabled: baseName(fileName) === fileName,
      sha1: await sha1OfFile(file),
      modId: modManager.readMetadata(file, await fsp.stat(file)).id,
    });
  }

  return files;
}

/**
 * mods 폴더에 없는 모드를 임시 폴더에 준비하고(item.staged) 그 jar 의 모드 id 를 읽는다(item.modId).
 * 준비하지 못한 모드에는 item.unavailable 을 표시하고 나머지를 계속 준비해서, 안 되는 것을 한 번에 알려 줄 수 있게 한다.
 */
async function stageMissingMods({ profile, items, temporary, modManager, identifyFiles, download, report }) {
  const missing = items.filter((item) => !item.present);
  const remote = missing.filter((item) => item.mod.kind === 'modrinth');
  let identified = {};

  if (remote.length > 0) {
    try {
      identified = await identifyFiles(remote.map((item) => item.mod.sha1));
    } catch (error) {
      throw lookupFailed(error);
    }
  }

  for (const [index, item] of missing.entries()) {
    const { mod } = item;
    const staged = path.join(temporary, mod.fileName);
    const progress = (part) => report(`${mod.title || mod.fileName} 준비하는 중...`, ((index + Math.min(part, 1)) / missing.length) * PREPARE_SHARE);
    progress(0);

    try {
      // 파일 시스템이 앞서 준비한 파일과 같은 이름으로 보는 이름이면, 쓰는 순간 그 파일을 덮어쓰게 된다.
      if (await exists(staged)) {
        throw new Error('앞서 준비한 파일과 자리가 겹친다');
      }

      if (mod.kind === 'modrinth') {
        const found = findModrinthFile(identified, mod.sha1);

        if (!found) {
          throw new Error('Modrinth 에 그 파일이 없다');
        }

        // 받을 주소는 Modrinth 가 알려 준 것만 쓴다. 허용된 호스트인지와 해시는 download 가 확인한다.
        await download(found.file.url, staged, {
          allowedHosts: DOWNLOAD_HOSTS,
          hashes: { sha1: mod.sha1, sha512: found.file.hashes.sha512 },
          onProgress: (received, total) => progress(total > 0 ? received / total : 0),
        });
        item.version = found.version;
      } else {
        const data = profile.extractJar(mod);

        if (data.length !== mod.size || sha1OfBuffer(data) !== mod.sha1) {
          throw new Error('프로필에 든 파일이 목록에 적힌 것과 다르다');
        }

        await fsp.writeFile(staged, data);
      }

      item.modId = modManager.readMetadata(staged, await fsp.stat(staged)).id;
      item.staged = staged;
    } catch {
      item.unavailable = true;
    }
  }
}

/** 게임에 넣으면 안 되는 구성을 찾아 unavailable 로 표시한다: 쓰면 안 되는 모드 id, 프로필 안에서 서로 같은 모드 id. */
function rejectForbiddenMods(items) {
  const byModId = new Map();

  for (const item of items) {
    // 모드 id 가 없는 jar(Fabric 모드가 아닌 파일)는 서로 겹칠 일이 없다.
    if (item.unavailable || !item.modId) {
      continue;
    }

    if (FORBIDDEN_MOD_IDS.includes(item.modId)) {
      item.unavailable = true;
    }

    byModId.set(item.modId, [...(byModId.get(item.modId) || []), item]);
  }

  for (const group of byModId.values()) {
    // 같은 모드가 둘이면 게임이 켜지지 않는다. 어느 쪽을 쓸지 런처가 고르지 않는다.
    if (group.length > 1) {
      for (const item of group) {
        item.unavailable = true;
      }
    }
  }
}

/**
 * 프로필의 jar 가 놓일 이름(item.target)과, 그 jar 에 자리를 내줘야 하는 기존 파일(item.displaced)을 정한다.
 * 내주면 안 되는 파일(기본 모드, 프로필이 그대로 쓰는 다른 파일)이나 mods 폴더 목록에 없는 무언가가 그 자리에 있으면
 * unavailable 로 표시한다. 아직 아무것도 옮기지 않는다.
 */
async function planPlacements(items, existing, modsDir) {
  const kept = new Set(items.map((item) => item.present).filter(Boolean));

  for (const item of items) {
    if (item.unavailable) {
      continue;
    }

    if (item.present) {
      // 이미 있는 파일은 이름을 그대로 두고, 꺼져 있으면 켠 이름으로 바꾼다. 그 이름을 쓰고 있는 다른 파일만 비킨다.
      item.target = baseName(item.present.fileName);
      item.displaced = item.present.enabled ? [] : existing.filter((file) => sameName(file.fileName, item.target));
    } else {
      // 새로 넣는 파일: 같은 모드의 다른 파일(예전 버전)과 이름이 같은 파일이 비킨다. 켜진 것, 꺼진 것 모두.
      item.target = item.mod.fileName;
      item.displaced = existing.filter((file) => sameName(baseName(file.fileName), item.target) || (item.modId && file.modId === item.modId));
    }

    const forbidden = item.displaced.some((file) => kept.has(file) || BUILTIN_MOD_IDS.includes(file.modId));
    // 폴더, 또는 Windows 의 짧은 이름(ABCDEF~1.JAR)으로 가리킨 다른 파일처럼 목록에서는 찾지 못했는데 그 자리에 있는 것.
    // 그대로 옮기면 그것을 덮어쓰고, 덮어쓴 것은 되돌릴 수 없다.
    const unknown = !existing.some((file) => sameName(file.fileName, item.target)) && (await exists(path.join(modsDir, item.target)));

    if (forbidden || unknown) {
      item.unavailable = true;
    }
  }
}

/**
 * mods 폴더에서 파일을 옮긴 일을 차례로 적어 둔다. 하나라도 실패하면 적어 둔 것을 거꾸로 되돌릴 수 있다.
 * 옮기는 것은 모두 rename 이고 지우는 일은 없다.
 */
function createJournal(rename) {
  const moves = [];

  return {
    async move(from, to) {
      // rename 은 그 자리에 있던 파일을 말없이 덮어쓰고, 덮어쓴 파일은 되돌릴 수 없다. 빈 자리로만 옮긴다.
      if (await exists(to)) {
        throw new LauncherError('PROFILE_TARGET_EXISTS', `mods 폴더에 같은 이름의 파일이나 폴더가 있어서 모드 파일을 바꾸지 못했습니다: ${path.basename(to)}`);
      }

      await rename(from, to);
      moves.push({ from, to });
    },

    async rollback() {
      while (moves.length > 0) {
        const { from, to } = moves[moves.length - 1];
        await rename(to, from);
        moves.pop();
      }
    },
  };
}

/**
 * 준비한 것으로 mods 폴더를 바꾼다: 새 jar 를 넣고(자리를 내준 파일은 임시 폴더의 replaced 로), 꺼져 있던 프로필 모드를 켜고,
 * disableOthers 면 프로필에 없는 모드를 끈다. 옮기는 일은 모두 journal 을 거친다.
 *
 * @returns {Promise<{installed: string[], replaced: {old: string, new: string}[], disabled: string[]}>}
 */
async function swapMods({ items, existing, modsDir, temporary, disableOthers, journal }) {
  const changes = { installed: [], replaced: [], disabled: [] };
  // 원래 자리를 떠난 기존 파일 (치워 둔 것, 이름을 바꾼 것)
  const moved = new Set();

  async function displace(file, newName) {
    if (moved.has(file)) {
      return;
    }

    await journal.move(path.join(modsDir, file.fileName), path.join(temporary, REPLACED_DIRECTORY, file.fileName));
    moved.add(file);
    changes.replaced.push({ old: file.fileName, new: newName });
  }

  async function place(item, from) {
    for (const file of item.displaced) {
      await displace(file, item.target);
    }

    await journal.move(from, path.join(modsDir, item.target));
  }

  for (const item of items.filter((candidate) => candidate.staged)) {
    await place(item, item.staged);
    changes.installed.push(item.target);
  }

  for (const item of items.filter((candidate) => candidate.present && !candidate.present.enabled)) {
    await place(item, path.join(modsDir, item.present.fileName));
    moved.add(item.present);
  }

  if (disableOthers) {
    const profileHashes = new Set(items.map((item) => item.mod.sha1));
    const profileModIds = new Set(items.map((item) => item.modId).filter(Boolean));

    for (const file of existing) {
      const inProfile = profileHashes.has(file.sha1) || profileModIds.has(file.modId);

      if (!file.enabled || moved.has(file) || inProfile || BUILTIN_MOD_IDS.includes(file.modId)) {
        continue;
      }

      const target = `${file.fileName}.disabled`;
      // 예전에 꺼 둔 같은 이름의 파일이 있으면 그 파일이 자리를 내준다 (런처에서 모드를 끌 때와 같다).
      const occupant = existing.find((other) => !moved.has(other) && sameName(other.fileName, target));

      if (occupant) {
        await displace(occupant, target);
      }

      await journal.move(path.join(modsDir, file.fileName), path.join(modsDir, target));
      moved.add(file);
      changes.disabled.push(file.fileName);
    }
  }

  return changes;
}

/** 새로 넣은 모드의 출처 기록. Modrinth 에서 받은 것은 런처로 설치했을 때(mod-installer.js)와 같은 모양이라 업데이트 확인이 그대로 읽는다. */
function recordOf({ mod, version }) {
  return version
    ? { source: 'modrinth', projectId: String(version.project_id), versionId: version.id, title: mod.title }
    : { source: 'local', title: mod.title };
}

/**
 * 프로필 파일을 게임 폴더에 적용한다.
 * 없는 모드를 mods 폴더 안의 임시 폴더에 전부 준비한 뒤에만 mods 폴더를 바꾼다. 하나라도 준비하지 못하면 아무것도 바꾸지 않고,
 * 바꾸는 도중에 실패하면 그때까지 바꾼 것을 되돌린다. 게임이 꺼져 있는지는 부르는 쪽에서 확인한다.
 * 예전 실행이 남긴 임시 폴더는 건드리지 않는다 (되돌리지 못한 원래 파일이 들어 있을 수 있다).
 *
 * @param {object} options
 * @param {string} options.filePath
 * @param {string} [options.expectedFingerprint] inspectProfile 이 돌려준 fingerprint. 지금 파일과 다르면 적용하지 않는다
 * @param {import('./mods').ModManager} options.modManager
 * @param {string} options.gameDir Cafeteria 게임 폴더
 * @param {boolean} [options.disableOthers] 프로필에 없는 모드를 끌지 (기본 모드는 끄지 않는다)
 * @param {boolean} [options.applySettings] 프로필에 든 Cafeteria 설정도 적용할지
 * @param {(hashes: string[]) => Promise<Record<string, object>>} options.identifyFiles sources/modrinth.js 의 identifyFiles
 * @param {typeof http.download} [options.download]
 * @param {(progress: {message: string, fraction: number}) => void} [options.onProgress]
 * @param {(from: string, to: string) => Promise<void>} [options.rename] 파일을 옮기는 함수 (테스트가 옮기다 실패하는 경우를 만들 때 바꿔 넣는다)
 * @param {typeof LIMITS} [options.limits]
 * @returns {Promise<{installed: string[], present: number, replaced: string[], disabled: string[], settingsApplied: boolean}>}
 *   installed: 새로 넣은 파일 이름, present: 이미 있던 프로필 모드 수, replaced: "예전 파일 → 새 파일", disabled: 끈 파일의 끄기 전 이름
 */
async function applyProfile({
  filePath,
  expectedFingerprint,
  modManager,
  gameDir,
  disableOthers,
  applySettings,
  identifyFiles,
  download = http.download,
  onProgress = () => {},
  rename = fsp.rename,
  limits = LIMITS,
}) {
  const report = (message, fraction) => onProgress({ message, fraction });
  const { modsDir } = modManager;

  // inspectProfile 이 본 것을 믿지 않고 처음부터 다시 검사한다.
  report('프로필을 확인하는 중...', 0);
  const profile = await readProfile(filePath, limits);

  // 사용자가 요약을 보고 확인한 것과 다른 내용을 적용하지 않는다.
  if (expectedFingerprint != null && expectedFingerprint !== profile.fingerprint) {
    throw new LauncherError('PROFILE_CHANGED', '프로필 파일이 고른 뒤에 바뀌었습니다. 다시 골라 주세요.');
  }

  const existing = await scanMods(modManager);
  const items = profile.mods.map((mod) => {
    const same = existing.filter((file) => file.sha1 === mod.sha1);
    // 같은 내용의 파일이 여럿이면 켜져 있는 것을 쓴다.
    const present = same.find((file) => file.enabled) || same[0] || null;

    return { mod, present, modId: present ? present.modId : undefined };
  });

  const temporary = path.join(modsDir, TEMPORARY_PREFIX + crypto.randomBytes(8).toString('hex'));
  let keepTemporary = false;

  try {
    await fsp.mkdir(path.join(temporary, REPLACED_DIRECTORY), { recursive: true });
    await stageMissingMods({ profile, items, temporary, modManager, identifyFiles, download, report });
    rejectForbiddenMods(items);
    await planPlacements(items, existing, modsDir);
    const unavailable = items.filter((item) => item.unavailable);

    // 여기까지는 mods 폴더의 기존 파일을 하나도 바꾸지 않았다.
    if (unavailable.length > 0) {
      const fileNames = unavailable.map((item) => item.mod.fileName);
      throw new LauncherError('PROFILE_MODS_UNAVAILABLE', `프로필의 모드 일부를 준비하지 못해 아무것도 바꾸지 않았습니다:\n${fileNames.join('\n')}`);
    }

    report('모드 파일을 바꾸는 중...', PREPARE_SHARE);
    const journal = createJournal(rename);
    let changes;
    let settingsApplied = false;

    try {
      changes = await swapMods({ items, existing, modsDir, temporary, disableOthers, journal });

      // 설정은 맨 마지막에 쓴다. 쓰지 못하면 모드도 되돌린다.
      if (applySettings && profile.settings) {
        await applyClientSettings({ gameDir, settings: profile.settings });
        settingsApplied = true;
      }
    } catch (error) {
      try {
        await journal.rollback();
      } catch {
        // 옮겨 둔 원래 파일이 임시 폴더에 남아 있으므로 지우지 않고, 어디 있는지 알려 준다.
        keepTemporary = true;
        throw new LauncherError('PROFILE_ROLLBACK_FAILED', `모드 파일을 바꾸다 멈췄고 되돌리지 못했습니다. mods 폴더의 ${path.basename(temporary)} 안에 원래 파일이 있습니다.`);
      }

      throw IN_USE_CODES.includes(error.code)
        ? new LauncherError('FILE_IN_USE', '모드 파일을 바꿀 수 없습니다. 게임이 켜져 있으면 끄고 다시 시도해 주세요.')
        : error;
    }

    // 전부 바꾼 뒤에만 출처 기록을 고친다. 기록은 파일 이름으로 찾으므로, 그 이름을 쓰는 파일이 더는 없는 것만 지운다.
    const remaining = new Set((await modManager.fileNames()).map(baseName));

    for (const { old } of changes.replaced) {
      if (!remaining.has(baseName(old))) {
        await modManager.forget(old);
      }
    }

    for (const item of items.filter((candidate) => candidate.staged)) {
      await modManager.record(item.target, recordOf(item));
    }

    report('프로필을 적용했습니다.', 1);

    return {
      installed: changes.installed,
      present: items.filter((item) => item.present).length,
      replaced: changes.replaced.map((change) => `${change.old} → ${change.new}`),
      disabled: changes.disabled,
      settingsApplied,
    };
  } finally {
    // 자리를 내준 예전 파일은 이때 실제로 지워진다. 지우지 못해도(백신이 잡고 있는 경우 등) 적용 결과는 그대로다.
    if (!keepTemporary) {
      await fsp.rm(temporary, { recursive: true, force: true }).catch(() => {});
    }
  }
}

module.exports = { PROFILE_EXTENSION, LIMITS, RESERVED_MOD_IDS, exportProfile, inspectProfile, applyProfile };
