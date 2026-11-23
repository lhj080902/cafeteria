'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { afterEach, test } = require('node:test');
const { FABRIC_API, FABRIC_PROFILE_URL, VERSION_ID } = require('../../src/main/constants');
const http = require('../../src/main/http');
const installer = require('../../src/main/installer');
const { ModManager } = require('../../src/main/mods');
const { fakeFetch, live, makeJar, tempDir } = require('./helpers');

const FABRIC_JSON = { id: VERSION_ID, inheritsFrom: '1.21.4', mainClass: 'net.fabricmc.loader.impl.launch.knot.KnotClient', libraries: [] };
const PROFILE = { memoryGb: 4 };

afterEach(() => http.setFetch((...args) => globalThis.fetch(...args)));

/** 공식 런처가 설치된 PC를 흉내 낸 폴더 구성. */
function setup(t, { profileFiles = ['launcher_profiles.json'], withFabricApi = true } = {}) {
  const root = tempDir(t);
  const minecraftDir = path.join(root, '.minecraft');
  const gameDir = path.join(root, '.cafeteria');
  const resourcesDir = path.join(root, 'resources');
  fs.mkdirSync(minecraftDir, { recursive: true });

  for (const name of profileFiles) {
    fs.writeFileSync(
      path.join(minecraftDir, name),
      JSON.stringify({ profiles: { vanilla: { name: '', type: 'latest-release', lastVersionId: 'latest-release' } }, settings: { keepLauncherOpen: true }, version: 3 }),
    );
  }

  makeJar(path.join(resourcesDir, 'mods', 'cafeteria-client-1.0.0+1.21.4.jar'), { id: 'cafeteria', name: 'Cafeteria Client', version: '1.0.0' });
  fs.writeFileSync(path.join(resourcesDir, 'profile-icon.png'), Buffer.from([0x89, 0x50, 0x4e, 0x47]));

  if (withFabricApi) {
    makeJar(path.join(gameDir, 'mods', 'fabric-api-already-here.jar'), { id: 'fabric-api', name: 'Fabric API' });
  }

  const fetch = fakeFetch({ [FABRIC_PROFILE_URL]: { json: FABRIC_JSON } });
  http.setFetch(fetch);
  const options = { minecraftDir, gameDir, resourcesDir, profile: PROFILE, modManager: new ModManager(gameDir) };
  return { minecraftDir, gameDir, resourcesDir, fetch, options };
}

function readProfiles(minecraftDir, name = 'launcher_profiles.json') {
  return JSON.parse(fs.readFileSync(path.join(minecraftDir, name), 'utf8'));
}

test('처음 설치: Fabric 버전 정보, 모드, 프로필이 모두 준비된다', async (t) => {
  const { minecraftDir, gameDir, resourcesDir, options } = setup(t);
  assert.deepEqual(await installer.checkStatus({ minecraftDir, gameDir, resourcesDir }), { officialLauncherFound: true, installed: false });

  const messages = [];
  await installer.ensureInstalled({ ...options, onProgress: (progress) => messages.push(progress) });

  assert.deepEqual(JSON.parse(fs.readFileSync(installer.versionJsonPath(minecraftDir), 'utf8')), FABRIC_JSON);
  assert.deepEqual(fs.readdirSync(path.join(gameDir, 'mods')).sort(), ['cafeteria-client-1.0.0+1.21.4.jar', 'fabric-api-already-here.jar']);

  const json = readProfiles(minecraftDir);
  const profile = json.profiles['cafeteria-client'];
  assert.equal(profile.name, 'Cafeteria');
  assert.equal(profile.type, 'custom');
  assert.equal(profile.lastVersionId, VERSION_ID);
  assert.equal(profile.gameDir, gameDir);
  assert.match(profile.javaArgs, /^-Xmx4G /);
  assert.match(profile.icon, /^data:image\/png;base64,/);
  assert.equal(profile.created, profile.lastUsed);
  assert.equal('resolution' in profile, false);
  assert.equal('javaDir' in profile, false);
  // 기존 프로필과 런처 설정은 그대로여야 한다
  assert.deepEqual(json.profiles.vanilla, { name: '', type: 'latest-release', lastVersionId: 'latest-release' });
  assert.deepEqual(json.settings, { keepLauncherOpen: true });
  assert.equal(json.version, 3);

  assert.deepEqual(await installer.checkStatus({ minecraftDir, gameDir, resourcesDir }), { officialLauncherFound: true, installed: true });
  assert.equal(messages.at(-1).fraction, 1);
  assert.ok(messages.every((progress, index) => index === 0 || progress.fraction >= messages[index - 1].fraction), '진행률은 줄어들지 않는다');
});

test('다시 실행해도 안전하다: 만든 날짜는 유지하고 바뀐 설정만 반영한다', async (t) => {
  const { minecraftDir, fetch, options } = setup(t);
  await installer.ensureInstalled(options);
  const first = readProfiles(minecraftDir).profiles['cafeteria-client'];

  await new Promise((resolve) => setTimeout(resolve, 5));
  await installer.ensureInstalled({
    ...options,
    profile: { memoryGb: 8, jvmArgs: '-Dfoo=bar', javaPath: 'C:\\Java\\bin\\javaw.exe', resolution: { width: 1920, height: 1080 } },
  });
  const second = readProfiles(minecraftDir).profiles['cafeteria-client'];

  assert.equal(second.created, first.created);
  assert.notEqual(second.lastUsed, first.lastUsed);
  assert.match(second.javaArgs, /^-Xmx8G .* -Dfoo=bar$/);
  assert.equal(second.javaDir, 'C:\\Java\\bin\\javaw.exe');
  assert.deepEqual(second.resolution, { width: 1920, height: 1080 });
  assert.equal(fetch.calls.length, 1, 'Fabric 버전 정보는 한 번만 받는다');

  // 선택 항목을 끄면 프로필에서도 지워진다
  await installer.ensureInstalled(options);
  const third = readProfiles(minecraftDir).profiles['cafeteria-client'];
  assert.equal('resolution' in third, false);
  assert.equal('javaDir' in third, false);
});

test('프로필 파일을 처음 바꾸기 전에 원본을 한 번만 백업한다', async (t) => {
  const { minecraftDir, options } = setup(t);
  const original = fs.readFileSync(path.join(minecraftDir, 'launcher_profiles.json'), 'utf8');
  await installer.ensureInstalled(options);
  await installer.ensureInstalled(options);
  assert.equal(fs.readFileSync(path.join(minecraftDir, 'launcher_profiles.json.cafeteria-backup'), 'utf8'), original);
});

test('Microsoft Store 런처의 프로필 파일도 함께 갱신한다', async (t) => {
  const { minecraftDir, options } = setup(t, { profileFiles: ['launcher_profiles.json', 'launcher_profiles_microsoft_store.json'] });
  await installer.ensureInstalled(options);
  assert.ok(readProfiles(minecraftDir).profiles['cafeteria-client']);
  assert.ok(readProfiles(minecraftDir, 'launcher_profiles_microsoft_store.json').profiles['cafeteria-client']);
});

test('예전 버전의 Cafeteria 모드는 지우고 새 것만 남긴다', async (t) => {
  const { gameDir, options } = setup(t);
  makeJar(path.join(gameDir, 'mods', 'cafeteria-client-0.9.0+1.21.4.jar'), { id: 'cafeteria' });
  makeJar(path.join(gameDir, 'mods', 'cafeteria-client-0.8.0+1.21.4.jar.disabled'), { id: 'cafeteria' });
  makeJar(path.join(gameDir, 'mods', 'sodium.jar'), { id: 'sodium' });
  await installer.ensureInstalled(options);
  assert.deepEqual(fs.readdirSync(path.join(gameDir, 'mods')).sort(), ['cafeteria-client-1.0.0+1.21.4.jar', 'fabric-api-already-here.jar', 'sodium.jar']);
});

test('공식 런처가 없으면 아무것도 설치하지 않고 안내한다', async (t) => {
  const { minecraftDir, gameDir, options } = setup(t, { profileFiles: [] });
  await assert.rejects(installer.ensureInstalled(options), { code: 'NO_OFFICIAL_LAUNCHER' });
  assert.equal(fs.existsSync(path.join(minecraftDir, 'versions')), false);
  assert.deepEqual(fs.readdirSync(path.join(gameDir, 'mods')), ['fabric-api-already-here.jar']);
});

test('읽을 수 없는 프로필 파일은 덮어쓰지 않는다', async (t) => {
  const { minecraftDir, options } = setup(t);
  const file = path.join(minecraftDir, 'launcher_profiles.json');

  for (const broken of ['{ 깨진 json', '[]', 'null', '"text"']) {
    fs.writeFileSync(file, broken);
    await assert.rejects(installer.ensureInstalled(options), { code: 'BAD_PROFILES' });
    assert.equal(fs.readFileSync(file, 'utf8'), broken);
  }
});

test('profiles 항목이 없는 프로필 파일에는 새로 만들어 넣는다', async (t) => {
  const { minecraftDir, options } = setup(t);
  fs.writeFileSync(path.join(minecraftDir, 'launcher_profiles.json'), JSON.stringify({ settings: {} }));
  await installer.ensureInstalled(options);
  assert.deepEqual(Object.keys(readProfiles(minecraftDir).profiles), ['cafeteria-client']);
});

test('Fabric 서버가 엉뚱한 버전 정보를 주면 저장하지 않는다', async (t) => {
  const { minecraftDir, options } = setup(t);
  http.setFetch(fakeFetch({ [FABRIC_PROFILE_URL]: { json: { ...FABRIC_JSON, id: 'something-else' } } }));
  await assert.rejects(installer.ensureInstalled(options), { code: 'BAD_RESPONSE' });
  assert.equal(fs.existsSync(installer.versionJsonPath(minecraftDir)), false);
  assert.equal('cafeteria-client' in readProfiles(minecraftDir).profiles, false, '실패하면 프로필도 등록하지 않는다');
});

test('서버에 연결할 수 없으면 안내 오류', async (t) => {
  const { options } = setup(t);
  http.setFetch(async () => {
    throw new Error('getaddrinfo ENOTFOUND');
  });
  await assert.rejects(installer.ensureInstalled(options), { code: 'NETWORK' });
});

test('설치 복구는 Fabric 버전 정보를 새로 받아 덮어쓴다', async (t) => {
  const { minecraftDir, fetch, options } = setup(t);
  await installer.ensureInstalled(options);
  fs.writeFileSync(installer.versionJsonPath(minecraftDir), '{ 손상된 파일');
  await installer.ensureInstalled({ ...options, repair: true });
  assert.deepEqual(JSON.parse(fs.readFileSync(installer.versionJsonPath(minecraftDir), 'utf8')), FABRIC_JSON);
  assert.equal(fetch.calls.length, 2);
});

test('런처에 모드 파일이 빠져 있으면 안내 오류', async (t) => {
  const { resourcesDir, options } = setup(t);
  fs.rmSync(path.join(resourcesDir, 'mods'), { recursive: true });
  await assert.rejects(installer.ensureInstalled(options), { code: 'NO_BUNDLED_MOD' });
});

test('프로필 제거는 Cafeteria 프로필만 지우고 게임 폴더는 남긴다', async (t) => {
  const { minecraftDir, gameDir, options } = setup(t, { profileFiles: ['launcher_profiles.json', 'launcher_profiles_microsoft_store.json'] });
  await installer.ensureInstalled(options);

  assert.equal(await installer.removeProfile(minecraftDir), true);
  assert.deepEqual(Object.keys(readProfiles(minecraftDir).profiles), ['vanilla']);
  assert.deepEqual(Object.keys(readProfiles(minecraftDir, 'launcher_profiles_microsoft_store.json').profiles), ['vanilla']);
  assert.equal(fs.existsSync(path.join(gameDir, 'mods', 'cafeteria-client-1.0.0+1.21.4.jar')), true);
  assert.equal(await installer.removeProfile(minecraftDir), false, '이미 없으면 false');
});

test('Fabric API 가 꺼져 있거나 없으면 검증된 버전을 실제 서버에서 내려받는다', live, async (t) => {
  const { gameDir, options } = setup(t, { withFabricApi: false });
  makeJar(path.join(gameDir, 'mods', 'fabric-api-old.jar.disabled'), { id: 'fabric-api' });
  // 버전 정보도 실제 Fabric 서버에서 받는다
  http.setFetch((...args) => globalThis.fetch(...args));
  const fractions = [];
  await installer.ensureInstalled({ ...options, onProgress: (progress) => fractions.push(progress.fraction) });

  const file = path.join(gameDir, 'mods', FABRIC_API.fileName);
  assert.equal(fs.statSync(file).size, 2149128);
  const mods = await new ModManager(gameDir).list();
  assert.ok(mods.some((mod) => mod.modId === 'fabric-api' && mod.enabled && mod.builtin));
  assert.ok(fractions.some((fraction) => fraction > 0.4 && fraction < 0.85), '다운로드 진행률이 전달된다');
  const versionJson = JSON.parse(fs.readFileSync(installer.versionJsonPath(options.minecraftDir), 'utf8'));
  assert.equal(versionJson.id, VERSION_ID);
  assert.ok(versionJson.libraries.length > 0);
});
