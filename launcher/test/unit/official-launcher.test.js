'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { isOfficialLauncherRunning, launchOfficialLauncher, windowsCandidates } = require('../../src/main/official-launcher');
const { tempDir } = require('./helpers');

test('Windows 기본 설치 위치 후보', () => {
  assert.deepEqual(windowsCandidates({ 'ProgramFiles(x86)': 'C:\\Program Files (x86)', ProgramFiles: 'C:\\Program Files', ProgramW6432: 'C:\\Program Files' }), [
    'C:\\Program Files (x86)\\Minecraft Launcher\\MinecraftLauncher.exe',
    'C:\\Program Files\\Minecraft Launcher\\MinecraftLauncher.exe',
  ]);
  assert.deepEqual(windowsCandidates({}), []);
});

test('직접 지정한 실행 파일이 있으면 그것을 실행한다', { skip: process.platform === 'win32' }, async (t) => {
  const directory = tempDir(t);
  const marker = path.join(directory, 'started');
  const script = path.join(directory, 'fake-launcher.sh');
  fs.writeFileSync(script, `#!/bin/sh\necho started > "${marker}"\n`, { mode: 0o755 });

  assert.deepEqual(await launchOfficialLauncher({ customPath: script, platform: 'linux' }), { launched: true, method: 'custom' });

  for (let i = 0; i < 50 && !fs.existsSync(marker); i++) {
    await new Promise((resolve) => setTimeout(resolve, 20));
  }

  assert.equal(fs.existsSync(marker), true, '지정한 프로그램이 실제로 실행돼야 한다');
});

test('지정한 경로가 없고 자동으로도 못 찾으면 실패를 알려 준다', { skip: process.platform === 'win32' }, async () => {
  const result = await launchOfficialLauncher({ customPath: '/없는/경로/launcher', platform: 'darwin' });
  assert.deepEqual(result, { launched: false, method: 'none' });
});

test('Windows 가 아니면 실행 중 여부는 확인하지 않는다', async () => {
  assert.equal(await isOfficialLauncherRunning({ platform: 'linux' }), false);
});
