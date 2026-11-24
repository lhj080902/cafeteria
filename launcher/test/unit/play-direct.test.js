'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { LauncherError } = require('../../src/main/errors');
const { playDirect, repairDirect } = require('../../src/main/play-direct');

function setup(overrides = {}) {
  const calls = [];
  const child = { pid: 1234 };
  const layout = { javaPath: 'C:\\data\\runtime\\java-runtime-delta-21.0.7\\bin\\javaw.exe' };
  const session = { accessToken: 'ACCESS_TOKEN', name: 'Steve', uuid: 'u', xuid: 'x' };
  const deps = {
    ensureMods: async (options) => {
      calls.push(['mods', options]);
      options.onProgress({ message: '모드', fraction: 1 });
    },
    ensureGameFiles: async (options) => {
      calls.push(['files', options]);
      options.onProgress({ message: '파일', fraction: 0.5 });
      options.onProgress({ message: '파일', fraction: 1 });
      return { versionJson: { id: '1.21.4' }, layout };
    },
    launchLibraries: (versionJson) => {
      calls.push(['libraries', versionJson]);
      return ['LIBRARIES'];
    },
    buildArgs: (options) => {
      calls.push(['args', options]);
      return ['ARGS'];
    },
    launchGame: async (options) => {
      calls.push(['launch', options]);
      return child;
    },
    ...overrides.deps,
  };
  const account = {
    session: async () => {
      calls.push(['session']);
      return session;
    },
    ...overrides.account,
  };
  const fractions = [];
  const base = {
    account,
    gameDir: 'C:\\game',
    dataDir: 'C:\\data',
    seedDir: 'C:\\minecraft',
    resourcesDir: 'C:\\resources',
    modManager: { modsDir: 'C:\\game\\mods' },
    launcherVersion: '1.3.0',
    options: { memoryGb: 6, jvmArgs: '-Da=b', javaPath: '', resolution: { width: 1280, height: 720 } },
    onProgress: (progress) => fractions.push(progress.fraction),
    deps,
  };
  return { calls, child, layout, session, fractions, base };
}

test('세션 확인, 모드, 게임 파일, 실행 순서로 진행하고 실행한 프로세스를 돌려준다', async () => {
  const { calls, child, layout, session, fractions, base } = setup();
  assert.equal(await playDirect(base), child);
  assert.deepEqual(calls.map(([name]) => name), ['session', 'mods', 'files', 'libraries', 'args', 'launch']);

  const call = (name) => calls.find(([key]) => key === name)[1];
  assert.equal(call('mods').gameDir, 'C:\\game');
  assert.equal(call('mods').resourcesDir, 'C:\\resources');
  assert.equal(call('files').dataDir, 'C:\\data');
  assert.equal(call('files').seedDir, 'C:\\minecraft');
  assert.equal(call('files').repair, false);
  const { versionJson, layout: usedLayout, libraries, session: usedSession, gameDir, launcherVersion, memoryGb, jvmArgs, resolution } = call('args');
  assert.deepEqual({ versionJson, usedLayout, libraries, usedSession, gameDir, launcherVersion, memoryGb, jvmArgs, resolution }, {
    versionJson: { id: '1.21.4' }, usedLayout: layout, libraries: ['LIBRARIES'], usedSession: session, gameDir: 'C:\\game',
    launcherVersion: '1.3.0', memoryGb: 6, jvmArgs: '-Da=b', resolution: { width: 1280, height: 720 },
  });
  assert.deepEqual(call('launch'), { javaPath: layout.javaPath, args: ['ARGS'], gameDir: 'C:\\game' });

  assert.ok(fractions.every((fraction, index) => fraction >= 0 && fraction <= 1 && (index === 0 || fraction >= fractions[index - 1])), JSON.stringify(fractions));
  assert.equal(fractions[fractions.length - 1], 1);
});

test('로그인이 안 돼 있으면 아무것도 받지 않고 멈춘다', async () => {
  const failing = {
    session: async () => {
      throw new LauncherError('LOGIN_REQUIRED', 'Microsoft 계정으로 로그인해 주세요.');
    },
  };
  const { calls, base } = setup({ account: failing });
  await assert.rejects(playDirect(base), { code: 'LOGIN_REQUIRED' });
  assert.deepEqual(calls, []);
});

test('설정에서 Java 를 직접 지정했으면 그것으로 실행한다', async () => {
  const { calls, base } = setup();
  await playDirect({ ...base, options: { ...base.options, javaPath: 'D:\\jdk-21\\bin\\javaw.exe' } });
  assert.equal(calls.find(([name]) => name === 'launch')[1].javaPath, 'D:\\jdk-21\\bin\\javaw.exe');
});

test('설치 복구는 로그인과 실행 없이 모드와 게임 파일만 다시 확인한다', async () => {
  const { calls, fractions, base } = setup();
  const { account, launcherVersion, options, ...repairOptions } = base;
  await repairDirect(repairOptions);
  assert.deepEqual(calls.map(([name]) => name), ['mods', 'files']);
  assert.equal(calls[1][1].repair, true);
  assert.equal(fractions[fractions.length - 1], 1);
});
