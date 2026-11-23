'use strict';

const fsp = require('node:fs/promises');
const path = require('node:path');
const {
  CAFETERIA_MOD_FILE_PREFIX,
  DOWNLOAD_HOSTS,
  FABRIC_API,
  FABRIC_PROFILE_URL,
  MINECRAFT_VERSION,
  PROFILE_KEY,
  PROFILE_NAME,
  VERSION_ID,
} = require('./constants');
const { LauncherError } = require('./errors');
const { exists, writeFileAtomic } = require('./fs-util');
const http = require('./http');

/** 공식 런처 종류별 프로필 파일. Fabric 공식 설치기와 같은 두 파일을 다룬다. */
const PROFILE_FILES = ['launcher_profiles.json', 'launcher_profiles_microsoft_store.json'];

/** 공식 런처의 기본 JVM 옵션에서 최대 메모리만 설정값으로 바꾸고, 사용자가 적은 추가 인수를 뒤에 붙인다. */
function javaArgs(memoryGb, extraArgs = '') {
  const base = `-Xmx${memoryGb}G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M`;
  return extraArgs ? `${base} ${extraArgs}` : base;
}

async function findProfileFiles(minecraftDir) {
  const found = [];

  for (const name of PROFILE_FILES) {
    const file = path.join(minecraftDir, name);

    if (await exists(file)) {
      found.push(file);
    }
  }

  return found;
}

function versionJsonPath(minecraftDir) {
  return path.join(minecraftDir, 'versions', VERSION_ID, `${VERSION_ID}.json`);
}

/** 런처에 들어 있는 Cafeteria 모드 jar 를 찾는다. */
async function findBundledMod(resourcesDir) {
  const directory = path.join(resourcesDir, 'mods');
  const names = (await fsp.readdir(directory).catch(() => []))
    .filter((name) => name.startsWith(CAFETERIA_MOD_FILE_PREFIX) && name.endsWith('.jar'))
    .sort();

  if (names.length === 0) {
    throw new LauncherError('NO_BUNDLED_MOD', '런처에 Cafeteria 모드 파일이 들어 있지 않습니다. 런처를 다시 설치해 주세요.', { directory });
  }

  return path.join(directory, names[names.length - 1]);
}

/** 현재 설치 상태를 확인한다 (홈 화면 표시용). 아무것도 바꾸지 않는다. */
async function checkStatus({ minecraftDir, gameDir, resourcesDir }) {
  const profileFiles = await findProfileFiles(minecraftDir);
  let profileInstalled = false;

  for (const file of profileFiles) {
    try {
      const json = JSON.parse(await fsp.readFile(file, 'utf8'));
      profileInstalled ||= Boolean(json && json.profiles && json.profiles[PROFILE_KEY]);
    } catch {
      // 읽을 수 없는 파일은 설치 안 됨으로 본다. 실제 오류는 설치할 때 안내한다.
    }
  }

  const bundled = await findBundledMod(resourcesDir).catch(() => null);
  const modInstalled = bundled ? await exists(path.join(gameDir, 'mods', path.basename(bundled))) : false;

  return {
    officialLauncherFound: profileFiles.length > 0,
    installed: profileInstalled && modInstalled && (await exists(versionJsonPath(minecraftDir))),
  };
}

/**
 * 플레이에 필요한 것을 모두 준비한다. 여러 번 실행해도 결과가 같다.
 *  1. 공식 런처의 versions 폴더에 Fabric 버전 정보 저장
 *  2. 게임 폴더의 mods 에 Cafeteria 모드와 Fabric API 넣기
 *  3. 공식 런처에 "Cafeteria" 프로필 등록(또는 갱신)
 *
 * @param {object} options
 * @param {string} options.minecraftDir 공식 런처의 .minecraft 폴더
 * @param {string} options.gameDir 카페테리아 게임 폴더
 * @param {string} options.resourcesDir 런처 리소스 폴더 (mods/*.jar, profile-icon.png)
 * @param {{memoryGb: number, jvmArgs?: string, javaPath?: string, resolution?: {width: number, height: number} | null}} options.profile
 *   공식 런처 프로필에 넣을 실행 옵션
 * @param {import('./mods').ModManager} options.modManager
 * @param {boolean} [options.repair] true 면 이미 있는 Fabric 버전 정보도 새로 받아서 덮어쓴다 (설치 복구)
 * @param {(progress: {message: string, fraction: number}) => void} [options.onProgress]
 */
async function ensureInstalled({ minecraftDir, gameDir, resourcesDir, profile, modManager, repair = false, onProgress = () => {} }) {
  const profileFiles = await findProfileFiles(minecraftDir);

  if (profileFiles.length === 0) {
    throw new LauncherError(
      'NO_OFFICIAL_LAUNCHER',
      '공식 Minecraft 런처를 찾을 수 없습니다. 공식 런처를 설치하고 한 번 실행(로그인)한 뒤 다시 시도해 주세요.',
      { minecraftDir },
    );
  }

  onProgress({ message: 'Fabric 로더 정보를 준비하는 중...', fraction: 0.05 });
  await installVersionJson(minecraftDir, repair);

  onProgress({ message: 'Cafeteria 모드를 설치하는 중...', fraction: 0.25 });
  await installBundledMod({ gameDir, resourcesDir });

  onProgress({ message: 'Fabric API를 확인하는 중...', fraction: 0.4 });
  await ensureFabricApi({
    modManager,
    onProgress: (received, total) => {
      const ratio = total > 0 ? received / total : 0;
      onProgress({ message: 'Fabric API를 내려받는 중...', fraction: 0.4 + ratio * 0.45 });
    },
  });

  onProgress({ message: '공식 런처에 Cafeteria 프로필을 등록하는 중...', fraction: 0.9 });
  const icon = await profileIcon(resourcesDir);

  for (const file of profileFiles) {
    await writeProfile(file, { gameDir, icon, ...profile });
  }

  onProgress({ message: '준비 완료', fraction: 1 });
}

async function installVersionJson(minecraftDir, overwrite) {
  const file = versionJsonPath(minecraftDir);

  if (!overwrite && (await exists(file))) {
    return;
  }

  const json = await http.getJson(FABRIC_PROFILE_URL);

  if (!json || json.id !== VERSION_ID || json.inheritsFrom !== MINECRAFT_VERSION || !Array.isArray(json.libraries)) {
    throw new LauncherError('BAD_RESPONSE', 'Fabric 서버에서 받은 버전 정보가 예상과 다릅니다. 잠시 후 다시 시도해 주세요.');
  }

  await writeFileAtomic(file, JSON.stringify(json, null, 2));
}

async function installBundledMod({ gameDir, resourcesDir }) {
  const bundled = await findBundledMod(resourcesDir);
  const modsDir = path.join(gameDir, 'mods');
  await fsp.mkdir(modsDir, { recursive: true });

  // 예전 버전의 Cafeteria 모드(켜진 것, 꺼진 것 모두)는 지워서 한 벌만 남긴다.
  for (const name of await fsp.readdir(modsDir)) {
    if (name.startsWith(CAFETERIA_MOD_FILE_PREFIX) && name !== path.basename(bundled) && /\.jar(\.disabled)?$/i.test(name)) {
      await fsp.rm(path.join(modsDir, name), { force: true });
    }
  }

  await fsp.copyFile(bundled, path.join(modsDir, path.basename(bundled)));
}

/** 켜져 있는 Fabric API 가 없으면 검증된 버전을 내려받는다. */
async function ensureFabricApi({ modManager, onProgress }) {
  const mods = await modManager.list();

  if (mods.some((mod) => mod.modId === 'fabric-api' && mod.enabled)) {
    return;
  }

  await http.download(FABRIC_API.url, path.join(modManager.modsDir, FABRIC_API.fileName), {
    allowedHosts: DOWNLOAD_HOSTS,
    hashes: { sha1: FABRIC_API.sha1, sha512: FABRIC_API.sha512 },
    onProgress,
  });
  await modManager.record(FABRIC_API.fileName, { source: 'builtin', title: 'Fabric API' });
}

async function profileIcon(resourcesDir) {
  const data = await fsp.readFile(path.join(resourcesDir, 'profile-icon.png')).catch(() => null);
  // 아이콘 파일이 없으면 공식 런처의 기본 아이콘 이름을 쓴다.
  return data ? `data:image/png;base64,${data.toString('base64')}` : 'Furnace';
}

/**
 * launcher_profiles.json 에 Cafeteria 프로필을 넣거나 갱신한다. 다른 프로필과 설정은 건드리지 않는다.
 * lastUsed 를 지금으로 맞춰서 공식 런처를 열었을 때 Cafeteria 가 선택돼 있게 한다.
 */
async function writeProfile(file, { gameDir, icon, memoryGb, jvmArgs = '', javaPath = '', resolution = null }) {
  const { json, text } = await readProfiles(file);
  // 처음 손대기 전의 원본을 한 번만 백업해 둔다.
  const backup = `${file}.cafeteria-backup`;

  if (!(await exists(backup))) {
    await fsp.writeFile(backup, text);
  }

  const now = new Date().toISOString();
  const existing = json.profiles[PROFILE_KEY] && typeof json.profiles[PROFILE_KEY] === 'object' ? json.profiles[PROFILE_KEY] : {};

  const profile = {
    ...existing,
    name: PROFILE_NAME,
    type: 'custom',
    created: existing.created || now,
    lastUsed: now,
    icon,
    lastVersionId: VERSION_ID,
    gameDir,
    javaArgs: javaArgs(memoryGb, jvmArgs),
  };

  // 선택 항목은 설정했을 때만 넣고, 끄면 지워서 공식 런처의 기본 동작으로 돌아가게 한다.
  if (resolution) {
    profile.resolution = { width: resolution.width, height: resolution.height };
  } else {
    delete profile.resolution;
  }

  if (javaPath) {
    profile.javaDir = javaPath;
  } else {
    delete profile.javaDir;
  }

  json.profiles[PROFILE_KEY] = profile;
  await writeFileAtomic(file, JSON.stringify(json, null, 2));
}

/** 프로필 파일을 읽는다. 내용을 이해할 수 없는 파일은 덮어쓰지 않도록 오류로 처리한다 (기존 프로필을 날릴 수 있다). */
async function readProfiles(file) {
  const text = await fsp.readFile(file, 'utf8');
  let json;

  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }

  if (!json || typeof json !== 'object' || Array.isArray(json)) {
    throw new LauncherError(
      'BAD_PROFILES',
      '공식 런처의 프로필 파일을 읽을 수 없습니다. 공식 런처를 한 번 실행했다가 종료한 뒤 다시 시도해 주세요.',
      { file },
    );
  }

  if (!json.profiles || typeof json.profiles !== 'object' || Array.isArray(json.profiles)) {
    json.profiles = {};
  }

  return { json, text };
}

/**
 * 공식 런처에서 Cafeteria 프로필만 지운다. 게임 폴더(모드, 설정, 월드)와 다른 프로필은 건드리지 않는다.
 * @returns {Promise<boolean>} 지운 프로필이 있었으면 true
 */
async function removeProfile(minecraftDir) {
  let removed = false;

  for (const file of await findProfileFiles(minecraftDir)) {
    const { json } = await readProfiles(file);

    if (json.profiles[PROFILE_KEY]) {
      delete json.profiles[PROFILE_KEY];
      await writeFileAtomic(file, JSON.stringify(json, null, 2));
      removed = true;
    }
  }

  return removed;
}

module.exports = { ensureInstalled, checkStatus, writeProfile, removeProfile, javaArgs, findProfileFiles, versionJsonPath, PROFILE_FILES };
