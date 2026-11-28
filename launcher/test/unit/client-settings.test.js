'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const {
  MAX_SETTINGS_BYTES,
  SETTINGS_EXTENSION,
  applyClientSettings,
  exportClientSettings,
  readClientSettingsFile,
  validateClientSettings,
} = require('../../src/main/client-settings');
const { tempDir } = require('./helpers');

const SAMPLE = { modules: { zoom: { enabled: true, settings: { zoom_factor: 6 } } } };

function settingsFile(gameDir) {
  return path.join(gameDir, 'config', 'cafeteria.json');
}

/** 게임이 저장해 둔 것처럼 config/cafeteria.json 을 만든다. */
function writeGameSettings(gameDir, content) {
  fs.mkdirSync(path.dirname(settingsFile(gameDir)), { recursive: true });
  fs.writeFileSync(settingsFile(gameDir), content);
}

/** 내보낸 파일 모양의 JSON 글자. */
function exported(overrides = {}) {
  return JSON.stringify({ format: 1, type: 'cafeteria-settings', createdWith: '1.5.0', settings: SAMPLE, ...overrides });
}

test('내보낸 파일을 다른 게임 폴더에 가져오면 같은 설정이 된다', async (t) => {
  const directory = tempDir(t);
  const gameA = path.join(directory, 'a');
  const gameB = path.join(directory, 'b');
  const filePath = path.join(directory, `내 설정.${SETTINGS_EXTENSION}`);
  writeGameSettings(gameA, '{"modules":{"zoom":{"enabled":true,"settings":{"zoom_factor":6}}}}');

  const result = await exportClientSettings({ gameDir: gameA, filePath, appVersion: '1.5.0' });
  assert.deepEqual(result, { modules: 1 });

  const file = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  assert.equal(file.format, 1);
  assert.equal(file.type, 'cafeteria-settings');
  assert.equal(file.createdWith, '1.5.0');

  const settings = await readClientSettingsFile(filePath);
  await applyClientSettings({ gameDir: gameB, settings });
  assert.deepEqual(JSON.parse(fs.readFileSync(settingsFile(gameB), 'utf8')), SAMPLE);
});

test('가져올 때 기존 설정을 bak 으로 남긴다', async (t) => {
  const withOld = tempDir(t);
  const old = '{"modules":{"fullbright":{"enabled":false}}}';
  writeGameSettings(withOld, old);

  assert.deepEqual(await applyClientSettings({ gameDir: withOld, settings: SAMPLE }), { backedUp: true });
  assert.equal(fs.readFileSync(`${settingsFile(withOld)}.bak`, 'utf8'), old);
  assert.deepEqual(JSON.parse(fs.readFileSync(settingsFile(withOld), 'utf8')), SAMPLE);

  const empty = tempDir(t);
  assert.deepEqual(await applyClientSettings({ gameDir: empty, settings: SAMPLE }), { backedUp: false });
  assert.equal(fs.existsSync(`${settingsFile(empty)}.bak`), false);
  assert.deepEqual(JSON.parse(fs.readFileSync(settingsFile(empty), 'utf8')), SAMPLE);
});

test('설정이 없으면 내보내지 않는다', async (t) => {
  const directory = tempDir(t);
  const filePath = path.join(directory, `out.${SETTINGS_EXTENSION}`);

  await assert.rejects(exportClientSettings({ gameDir: path.join(directory, 'game'), filePath, appVersion: '1.5.0' }), { code: 'NO_CLIENT_SETTINGS' });
  assert.equal(fs.existsSync(filePath), false);
});

test('깨진 설정은 내보내지 않는다', async (t) => {
  for (const content of ['{ 깨진 파일', '{"modules":[]}', 'null']) {
    const directory = tempDir(t);
    const filePath = path.join(directory, `out.${SETTINGS_EXTENSION}`);
    writeGameSettings(directory, content);

    await assert.rejects(exportClientSettings({ gameDir: directory, filePath, appVersion: '1.5.0' }), { code: 'BAD_SETTINGS_FILE' }, content);
    assert.equal(fs.existsSync(filePath), false, content);
  }
});

test('잘못된 파일은 거절한다', async (t) => {
  const directory = tempDir(t);
  const filePath = path.join(directory, `in.${SETTINGS_EXTENSION}`);
  const valid = exported();
  const cases = {
    'JSON 이 아님': '{ 깨진 파일',
    'type 이 다름': exported({ type: 'cafeteria-profile' }),
    'format 이 2': exported({ format: 2 }),
    'settings 없음': exported({ settings: undefined }),
    'settings.modules 가 배열': exported({ settings: { modules: [] } }),
    // 내용은 올바르고 크기만 넘는다 (JSON 뒤의 공백은 허용된다).
    '1MB + 1바이트': valid.padEnd(MAX_SETTINGS_BYTES + 1, ' '),
  };

  for (const [name, content] of Object.entries(cases)) {
    fs.writeFileSync(filePath, content);
    await assert.rejects(readClientSettingsFile(filePath), { code: 'BAD_SETTINGS_FILE' }, name);
  }

  // 딱 1MB 까지는 받는다. 위의 마지막 경우가 크기 때문에 거절됐다는 것도 함께 확인한다.
  fs.writeFileSync(filePath, valid.padEnd(MAX_SETTINGS_BYTES, ' '));
  assert.equal(fs.statSync(filePath).size, MAX_SETTINGS_BYTES);
  assert.deepEqual(await readClientSettingsFile(filePath), SAMPLE);

  await assert.rejects(readClientSettingsFile(path.join(directory, '없는 파일')), { code: 'BAD_SETTINGS_FILE' });
});

test('validateClientSettings 는 객체가 아닌 값을 거절한다', () => {
  for (const value of [null, [], 'x', { modules: [] }]) {
    assert.throws(() => validateClientSettings(value), { code: 'BAD_SETTINGS_FILE', message: '올바른 Cafeteria 설정 파일이 아닙니다.' }, JSON.stringify(value));
  }

  const value = { modules: {} };
  assert.equal(validateClientSettings(value), value);
});
