'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { SettingsStore } = require('../../src/main/settings');
const { tempDir } = require('./helpers');

/** 글자를 뒤집는 것으로 "암호화"를 흉내 내는 가짜 보안 저장소. */
const fakeVault = {
  isAvailable: () => true,
  encrypt: (text) => Buffer.from([...text].reverse().join('')),
  decrypt: (data) => [...data.toString()].reverse().join(''),
};

test('처음에는 기본값을 쓴다', async (t) => {
  const store = await new SettingsStore(path.join(tempDir(t), 'settings.json')).load();
  assert.deepEqual(store.publicView(), {
    memoryGb: 4,
    resolutionEnabled: false,
    resolutionWidth: 1280,
    resolutionHeight: 720,
    jvmArgs: '',
    javaPath: '',
    closeOnLaunch: false,
    autoUpdate: true,
    modUpdateCheck: true,
    launcherPath: '',
    gameDir: '',
    launchMode: 'direct',
    hasCurseforgeKey: false,
  });
  assert.deepEqual(store.profileOptions(), { memoryGb: 4, jvmArgs: '', javaPath: '', resolution: null });
});

test('바꾼 설정은 파일에 저장되고 다시 읽힌다', async (t) => {
  const file = path.join(tempDir(t), 'settings.json');
  const store = await new SettingsStore(file).load();
  await store.update({ memoryGb: 8, closeOnLaunch: true, gameDir: '  /games/cafe  ' });
  const reloaded = await new SettingsStore(file).load();
  assert.equal(reloaded.get('memoryGb'), 8);
  assert.equal(reloaded.get('closeOnLaunch'), true);
  assert.equal(reloaded.get('gameDir'), '/games/cafe');
});

test('잘못된 값은 무시하거나 허용 범위로 보정한다', async (t) => {
  const store = await new SettingsStore(path.join(tempDir(t), 'settings.json')).load();
  await store.update({ memoryGb: 999, closeOnLaunch: 'yes', launcherPath: 42, unknown: 'x' });
  assert.equal(store.get('memoryGb'), 32);
  assert.equal(store.get('closeOnLaunch'), false);
  assert.equal(store.get('launcherPath'), '');
  assert.equal(store.get('unknown'), undefined);
  await store.update({ memoryGb: 0.4 });
  assert.equal(store.get('memoryGb'), 2);
  await store.update({ memoryGb: Number.NaN });
  assert.equal(store.get('memoryGb'), 2);
});

test('깨진 설정 파일이 있어도 기본값으로 시작한다', async (t) => {
  const file = path.join(tempDir(t), 'settings.json');
  fs.writeFileSync(file, '{ 깨진 파일');
  const store = await new SettingsStore(file).load();
  assert.equal(store.get('memoryGb'), 4);
});

test('CurseForge 키는 암호화해서 저장하고 화면용 값에는 넣지 않는다', async (t) => {
  const file = path.join(tempDir(t), 'settings.json');
  const store = await new SettingsStore(file, fakeVault).load();
  const view = await store.setCurseforgeKey('  secret-key-123  ');
  assert.equal(view.hasCurseforgeKey, true);
  assert.equal(JSON.stringify(view).includes('secret-key-123'), false);
  assert.equal(fs.readFileSync(file, 'utf8').includes('secret-key-123'), false);
  assert.equal(store.curseforgeKey(), 'secret-key-123');

  const reloaded = await new SettingsStore(file, fakeVault).load();
  assert.equal(reloaded.curseforgeKey(), 'secret-key-123');
});

test('보안 저장소를 쓸 수 없으면 그대로 저장하되 동작은 같다', async (t) => {
  const file = path.join(tempDir(t), 'settings.json');
  const store = await new SettingsStore(file, { ...fakeVault, isAvailable: () => false }).load();
  await store.setCurseforgeKey('plain-key');
  assert.equal((await new SettingsStore(file).load()).curseforgeKey(), 'plain-key');
});

test('복호화할 수 없는 키는 없는 것으로 취급한다', async (t) => {
  const file = path.join(tempDir(t), 'settings.json');
  await (await new SettingsStore(file, fakeVault).load()).setCurseforgeKey('secret');
  const brokenVault = {
    ...fakeVault,
    decrypt: () => {
      throw new Error('다른 PC 에서 암호화됨');
    },
  };
  const store = await new SettingsStore(file, brokenVault).load();
  assert.equal(store.curseforgeKey(), '');
  assert.equal(store.publicView().hasCurseforgeKey, false);
});

test('빈 키를 저장하면 키가 지워진다', async (t) => {
  const store = await new SettingsStore(path.join(tempDir(t), 'settings.json'), fakeVault).load();
  await store.setCurseforgeKey('secret');
  assert.equal((await store.setCurseforgeKey('   ')).hasCurseforgeKey, false);
});

test('창 크기·JVM 인수·Java 경로 설정은 보정된 뒤 프로필 옵션으로 넘어간다', async (t) => {
  const store = await new SettingsStore(path.join(tempDir(t), 'settings.json')).load();
  await store.update({
    resolutionEnabled: true,
    resolutionWidth: 99999,
    resolutionHeight: 10,
    jvmArgs: '  -XX:+AlwaysPreTouch\n\t-Dfoo=bar  ',
    javaPath: ' C:\\Java\\bin\\javaw.exe ',
  });
  assert.deepEqual(store.profileOptions(), {
    memoryGb: 4,
    jvmArgs: '-XX:+AlwaysPreTouch -Dfoo=bar',
    javaPath: 'C:\\Java\\bin\\javaw.exe',
    resolution: { width: 7680, height: 360 },
  });

  await store.update({ resolutionEnabled: false, jvmArgs: 'x'.repeat(5000) });
  assert.equal(store.profileOptions().resolution, null);
  assert.equal(store.get('jvmArgs').length, 1000);
});

test('설정 초기화는 게임 폴더와 CurseForge 키를 남기고 나머지를 기본값으로 되돌린다', async (t) => {
  const file = path.join(tempDir(t), 'settings.json');
  const store = await new SettingsStore(file, fakeVault).load();
  await store.update({ memoryGb: 12, closeOnLaunch: true, gameDir: '/games/cafe', launcherPath: '/opt/launcher', jvmArgs: '-Dx=1', resolutionEnabled: true });
  await store.setCurseforgeKey('secret');

  const view = await store.reset();
  assert.equal(view.memoryGb, 4);
  assert.equal(view.closeOnLaunch, false);
  assert.equal(view.launcherPath, '');
  assert.equal(view.jvmArgs, '');
  assert.equal(view.resolutionEnabled, false);
  assert.equal(view.gameDir, '/games/cafe');
  assert.equal(view.hasCurseforgeKey, true);
  assert.equal((await new SettingsStore(file, fakeVault).load()).get('memoryGb'), 4);
});

test('자동 업데이트는 기본으로 켜져 있고 끌 수 있다', async (t) => {
  const file = path.join(tempDir(t), 'settings.json');
  const store = await new SettingsStore(file).load();
  assert.equal(store.get('autoUpdate'), true);
  await store.update({ autoUpdate: false });
  assert.equal((await new SettingsStore(file).load()).get('autoUpdate'), false);
  await store.update({ autoUpdate: 'no' });
  assert.equal(store.get('autoUpdate'), false, '잘못된 값은 무시한다');
});

test('실행 방식은 direct 가 기본이고 direct, official 만 받는다', async (t) => {
  const store = await new SettingsStore(path.join(tempDir(t), 'settings.json')).load();
  assert.equal(store.get('launchMode'), 'direct');
  assert.equal((await store.update({ launchMode: 'official' })).launchMode, 'official');
  assert.equal((await store.update({ launchMode: 'offline' })).launchMode, 'official');
  assert.equal((await store.reset()).launchMode, 'direct');
});
