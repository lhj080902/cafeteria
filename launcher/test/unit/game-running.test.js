'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { SETTINGS_EXTENSION, importFlow } = require('../../src/main/client-settings');
const { assertGameNotRunning, createGameRunningCheck, markerSaysRunning } = require('../../src/main/game-running');
const { tempDir } = require('./helpers');

/** 표지의 수정 시각을 재는 기준 시각. 실제 시계와 상관없이 같은 결과가 나오게 고정해 둔다. */
const NOW = Date.UTC(2026, 10, 28, 12, 0, 0);

/** 모드가 게임이 켜져 있는 동안 갱신하는 표지 파일의 위치. */
function markerFile(gameDir) {
  return path.join(gameDir, 'config', 'cafeteria.running');
}

/** 모드가 남긴 것처럼 표지 파일을 만들고 수정 시각을 맞춘다. */
function writeMarker(gameDir, modifiedAt) {
  fs.mkdirSync(path.dirname(markerFile(gameDir)), { recursive: true });
  fs.writeFileSync(markerFile(gameDir), '');
  fs.utimesSync(markerFile(gameDir), new Date(modifiedAt), new Date(modifiedAt));
}

test('게임이 켜져 있으면 GAME_RUNNING 으로 거절한다', async () => {
  const expected = { code: 'GAME_RUNNING', message: '게임이 실행 중입니다. 게임을 끈 뒤 다시 시도해 주세요.' };

  // 켜져 있는지는 바로 답해도 되고 Promise 로 답해도 된다.
  await assert.rejects(assertGameNotRunning(() => true), expected);
  await assert.rejects(assertGameNotRunning(async () => true), expected);
  await assertGameNotRunning(() => false);
  await assertGameNotRunning(async () => false);
});

test('방금 갱신된 표지가 있으면 켜져 있다', async (t) => {
  const gameDir = tempDir(t);

  writeMarker(gameDir, NOW - 3000);
  assert.equal(await markerSaysRunning(gameDir, NOW), true);

  // 모드는 5초마다 갱신한다. 몇 번 늦어져도 20초가 되기 전까지는 켜져 있다고 본다.
  writeMarker(gameDir, NOW - 19000);
  assert.equal(await markerSaysRunning(gameDir, NOW), true);
});

test('오래된 표지는 무시한다', async (t) => {
  const gameDir = tempDir(t);

  // 게임이 죽으면 표지가 지워지지 않고 남는다.
  writeMarker(gameDir, NOW - 30000);
  assert.equal(await markerSaysRunning(gameDir, NOW), false);

  writeMarker(gameDir, NOW - 20000);
  assert.equal(await markerSaysRunning(gameDir, NOW), false);
});

test('표지가 없으면 꺼져 있다', async (t) => {
  const noFile = tempDir(t);
  fs.mkdirSync(path.join(noFile, 'config'));
  assert.equal(await markerSaysRunning(noFile, NOW), false, '파일 없음');

  const noConfig = tempDir(t);
  assert.equal(await markerSaysRunning(noConfig, NOW), false, 'config 폴더 없음');
  assert.deepEqual(fs.readdirSync(noConfig), [], '보기만 하고 아무것도 만들지 않는다');

  // 수정 시각은 방금이지만 파일이 아니라 폴더다.
  const folder = tempDir(t);
  fs.mkdirSync(markerFile(folder), { recursive: true });
  fs.utimesSync(markerFile(folder), new Date(NOW - 3000), new Date(NOW - 3000));
  assert.equal(await markerSaysRunning(folder, NOW), false, '같은 이름의 폴더');
});

test('시계가 조금 어긋나도 켜져 있다고 본다', async (t) => {
  const gameDir = tempDir(t);

  // 수정 시각이 지금보다 뒤다.
  writeMarker(gameDir, NOW + 5000);
  assert.equal(await markerSaysRunning(gameDir, NOW), true);

  writeMarker(gameDir, NOW + 60000);
  assert.equal(await markerSaysRunning(gameDir, NOW), false);
});

test('직접 띄운 게임이 있으면 파일을 보지 않는다', async () => {
  const isGameRunning = createGameRunningCheck({
    child: () => ({}),
    gameDir: () => {
      throw new Error('게임 폴더를 보면 안 된다');
    },
  });

  assert.equal(await isGameRunning(), true);
});

test('직접 띄운 게임이 없으면 표지를 본다', async (t) => {
  const withMarker = tempDir(t);
  const withoutMarker = tempDir(t);
  writeMarker(withMarker, Date.now() - 3000);

  // 게임 폴더는 설정에서 바뀔 수 있어서 물을 때마다 다시 받는다.
  let current = withMarker;
  const isGameRunning = createGameRunningCheck({ child: () => null, gameDir: () => current });
  assert.equal(await isGameRunning(), true);

  current = withoutMarker;
  assert.equal(await isGameRunning(), false);
});

test('표지가 있으면 설정 가져오기를 거절한다', async (t) => {
  const directory = tempDir(t);
  const gameDir = path.join(directory, 'game');
  const settingsFile = path.join(gameDir, 'config', 'cafeteria.json');
  const incoming = path.join(directory, `받은 설정.${SETTINGS_EXTENSION}`);
  const old = '{"modules":{"fullbright":{"enabled":false}}}';
  // 런처가 띄우지 않은 게임(공식 런처로 켰거나, 런처를 닫았다 다시 연 뒤에도 켜져 있는 게임)이 이 폴더를 쓰고 있다.
  writeMarker(gameDir, Date.now() - 3000);
  fs.writeFileSync(settingsFile, old);
  fs.writeFileSync(
    incoming,
    JSON.stringify({ format: 1, type: 'cafeteria-settings', createdWith: '1.5.0', settings: { modules: { zoom: { enabled: true } } } }),
  );

  await assert.rejects(
    importFlow({
      gameDir,
      chooseFile: async () => incoming,
      confirm: async () => true,
      isGameRunning: createGameRunningCheck({ gameDir: () => gameDir, child: () => null }),
    }),
    { code: 'GAME_RUNNING' },
  );
  assert.equal(fs.readFileSync(settingsFile, 'utf8'), old);
  assert.equal(fs.existsSync(`${settingsFile}.bak`), false);
});
