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
  exportFlow,
  importFlow,
  readClientSettingsFile,
  validateClientSettings,
} = require('../../src/main/client-settings');
const { tempDir } = require('./helpers');

const SAMPLE = { modules: { zoom: { enabled: true, settings: { zoom_factor: 6 } } } };
/** 게임 폴더에 원래 있던 설정 (글자 그대로 비교한다). */
const OLD = '{"modules":{"fullbright":{"enabled":false}}}';
/** 남에게 받은 설정. 모듈 수가 OLD 와 다르다. */
const INCOMING = { modules: { zoom: { enabled: true, settings: { zoom_factor: 6 } }, fps: { enabled: false, x: 4, y: 8 } } };

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

/** 대화상자 대신 넣는 가짜 함수. 정해 둔 답을 돌려주고, 불릴 때마다 받은 인수를 적어 둔다. */
function recorder(answer) {
  const calls = [];
  const fn = async (...args) => {
    calls.push(args);
    return answer;
  };

  fn.calls = calls;
  return fn;
}

/** 예전 설정(OLD)이 든 게임 폴더와, 가져올 설정 파일(INCOMING)을 만든다. */
function importWorld(t) {
  const directory = tempDir(t);
  const gameDir = path.join(directory, 'game');
  const filePath = path.join(directory, `받은 설정.${SETTINGS_EXTENSION}`);
  writeGameSettings(gameDir, OLD);
  fs.writeFileSync(filePath, exported({ settings: INCOMING }));
  return { gameDir, filePath };
}

/** 게임 폴더의 설정이 그대로이고 bak 도 생기지 않았는지 확인한다. */
function assertUntouched(gameDir) {
  assert.equal(fs.readFileSync(settingsFile(gameDir), 'utf8'), OLD);
  assert.equal(fs.existsSync(`${settingsFile(gameDir)}.bak`), false);
}

/** 파일을 다루다 난 실패를 감싼 오류인지 확인한다. 화면에 가는 안내문에는 경로가 없고, 원인 코드는 details 에 남는다. */
function fileFailure(code, message) {
  return (error) => {
    assert.equal(error.code, code);
    assert.equal(error.message, message);
    assert.match(error.details.cause, /^E[A-Z]+$/);
    return true;
  };
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
    'JSON 이 null': 'null',
    'JSON 이 배열': '[]',
    'JSON 이 글자': '"text"',
    'JSON 이 숫자': '42',
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
});

test('파일을 읽지 못해도 같은 안내문으로 알리고 원인을 남긴다', async (t) => {
  await assert.rejects(
    readClientSettingsFile(path.join(tempDir(t), '없는 파일')),
    fileFailure('BAD_SETTINGS_FILE', '올바른 Cafeteria 설정 파일이 아닙니다.'),
  );
});

test('폴더는 설정 파일로 읽지 않는다', async (t) => {
  // 읽다가 실패한 것이 아니라 읽기 전에 거절한 것이므로 원인이 없다.
  await assert.rejects(readClientSettingsFile(tempDir(t)), { code: 'BAD_SETTINGS_FILE', details: {} });
});

test('저장된 설정을 읽지 못하면 SETTINGS_READ_FAILED 로 알린다', async (t) => {
  const directory = tempDir(t);
  const filePath = path.join(directory, `out.${SETTINGS_EXTENSION}`);
  // cafeteria.json 자리에 폴더가 있다.
  fs.mkdirSync(settingsFile(directory), { recursive: true });

  await assert.rejects(
    exportClientSettings({ gameDir: directory, filePath, appVersion: '1.5.0' }),
    fileFailure('SETTINGS_READ_FAILED', '저장된 Cafeteria 설정을 읽지 못했습니다.'),
  );
  assert.equal(fs.existsSync(filePath), false);
});

test('내보낼 곳에 쓰지 못하면 SETTINGS_EXPORT_FAILED 로 알린다', async (t) => {
  const directory = tempDir(t);
  // 내보낼 파일 자리에 폴더가 있다.
  const filePath = path.join(directory, '이미 있는 폴더');
  writeGameSettings(directory, JSON.stringify(SAMPLE));
  fs.mkdirSync(filePath);

  await assert.rejects(
    exportClientSettings({ gameDir: directory, filePath, appVersion: '1.5.0' }),
    fileFailure('SETTINGS_EXPORT_FAILED', '설정 파일을 저장하지 못했습니다. 다른 위치를 골라 주세요.'),
  );
});

test('설정 파일을 쓰지 못하면 SETTINGS_WRITE_FAILED 로 알린다', async (t) => {
  const expected = fileFailure('SETTINGS_WRITE_FAILED', '설정 파일을 쓰지 못했습니다. 게임을 끈 뒤 다시 시도해 주세요.');

  // bak 을 만들 자리에 폴더가 있어서 예전 설정을 남기지 못한다. 그러면 설정도 바꾸지 않는다.
  const blockedBackup = tempDir(t);
  writeGameSettings(blockedBackup, OLD);
  fs.mkdirSync(`${settingsFile(blockedBackup)}.bak`);
  await assert.rejects(applyClientSettings({ gameDir: blockedBackup, settings: SAMPLE }), expected);
  assert.equal(fs.readFileSync(settingsFile(blockedBackup), 'utf8'), OLD);

  // config 자리에 파일이 있어서 설정 파일을 만들지 못한다.
  const blockedWrite = tempDir(t);
  fs.writeFileSync(path.join(blockedWrite, 'config'), '');
  await assert.rejects(applyClientSettings({ gameDir: blockedWrite, settings: SAMPLE }), expected);
});

test('내보내기 흐름: 저장된 설정이 없으면 저장할 곳을 묻지 않는다', async (t) => {
  const chooseFile = recorder(path.join(tempDir(t), `out.${SETTINGS_EXTENSION}`));

  await assert.rejects(exportFlow({ gameDir: tempDir(t), appVersion: '1.5.0', chooseFile }), { code: 'NO_CLIENT_SETTINGS' });
  assert.equal(chooseFile.calls.length, 0);
});

test('내보내기 흐름: 고른 곳에 내보내고, 고르지 않으면 그만둔다', async (t) => {
  const directory = tempDir(t);
  const filePath = path.join(directory, `out.${SETTINGS_EXTENSION}`);
  writeGameSettings(directory, JSON.stringify(SAMPLE));

  assert.equal(await exportFlow({ gameDir: directory, appVersion: '1.5.0', chooseFile: recorder(null) }), null);
  assert.equal(await exportFlow({ gameDir: directory, appVersion: '1.5.0', chooseFile: recorder(undefined) }), null);

  assert.deepEqual(await exportFlow({ gameDir: directory, appVersion: '1.5.0', chooseFile: recorder(filePath) }), { modules: 1 });
  assert.deepEqual(JSON.parse(fs.readFileSync(filePath, 'utf8')), { format: 1, type: 'cafeteria-settings', createdWith: '1.5.0', settings: SAMPLE });
});

test('가져오기 흐름: 게임이 켜져 있으면 파일을 고르기도 전에 거절한다', async (t) => {
  const { gameDir, filePath } = importWorld(t);
  const chooseFile = recorder(filePath);
  const confirm = recorder(true);

  await assert.rejects(importFlow({ gameDir, chooseFile, confirm, isGameRunning: () => true }), { code: 'GAME_RUNNING' });
  assert.equal(chooseFile.calls.length, 0);
  assert.equal(confirm.calls.length, 0);
  assertUntouched(gameDir);
});

test('가져오기 흐름: 확인 창이 떠 있는 동안 게임이 켜지면 거절한다', async (t) => {
  const { gameDir, filePath } = importWorld(t);
  const confirm = recorder(true);
  // 처음에는 꺼져 있다가, 확인 창이 뜬 뒤로는 켜져 있다.
  const isGameRunning = () => confirm.calls.length > 0;

  await assert.rejects(importFlow({ gameDir, chooseFile: recorder(filePath), confirm, isGameRunning }), { code: 'GAME_RUNNING' });
  assert.equal(confirm.calls.length, 1);
  assertUntouched(gameDir);
});

test('가져오기 흐름: 취소하면 아무것도 바꾸지 않는다', async (t) => {
  const { gameDir, filePath } = importWorld(t);
  const isGameRunning = () => false;

  const unasked = recorder(true);
  assert.equal(await importFlow({ gameDir, chooseFile: recorder(null), confirm: unasked, isGameRunning }), null);
  assert.equal(unasked.calls.length, 0, '파일을 고르지 않았으면 확인 창을 띄우지 않는다');
  assertUntouched(gameDir);

  const declined = recorder(false);
  assert.equal(await importFlow({ gameDir, chooseFile: recorder(filePath), confirm: declined, isGameRunning }), null);
  assert.equal(declined.calls.length, 1);
  assertUntouched(gameDir);
});

test('가져오기 흐름: 확인하면 설정을 바꾸고 예전 설정을 bak 으로 남긴다', async (t) => {
  const { gameDir, filePath } = importWorld(t);
  const confirm = recorder(true);

  const result = await importFlow({ gameDir, chooseFile: recorder(filePath), confirm, isGameRunning: async () => false });
  assert.deepEqual(result, { modules: 2, backedUp: true });
  assert.deepEqual(confirm.calls, [[{ modules: 2 }]], '확인 창은 가져올 모듈 수를 받는다');
  assert.deepEqual(JSON.parse(fs.readFileSync(settingsFile(gameDir), 'utf8')), INCOMING);
  assert.equal(fs.readFileSync(`${settingsFile(gameDir)}.bak`, 'utf8'), OLD);
});

test('validateClientSettings 는 객체가 아닌 값을 거절한다', () => {
  for (const value of [null, [], 'x', { modules: [] }]) {
    assert.throws(() => validateClientSettings(value), { code: 'BAD_SETTINGS_FILE', message: '올바른 Cafeteria 설정 파일이 아닙니다.' }, JSON.stringify(value));
  }

  const value = { modules: {} };
  assert.equal(validateClientSettings(value), value);
});
