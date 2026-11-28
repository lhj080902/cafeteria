'use strict';

const fsp = require('node:fs/promises');
const path = require('node:path');
const { LauncherError } = require('./errors');
const { exists, writeFileAtomic } = require('./fs-util');
const { assertGameNotRunning } = require('./game-running');

/*
 * 게임 안 Cafeteria 설정(모드가 <게임 폴더>/config/cafeteria.json 에 저장하는 모듈 설정과 HUD 위치)을
 * 파일 하나로 내보내고, 그렇게 받은 파일을 게임 폴더에 다시 넣는다.
 */

/** 내보낸 설정 파일의 확장자. */
const SETTINGS_EXTENSION = 'cafeteria-settings';
/** 가져올 수 있는 설정 파일의 최대 크기. 실제 설정은 수 KB 라서 이보다 크면 다른 파일로 본다. */
const MAX_SETTINGS_BYTES = 1024 * 1024;

const FILE_FORMAT = 1;
const FILE_TYPE = 'cafeteria-settings';

function settingsPath(gameDir) {
  return path.join(gameDir, 'config', 'cafeteria.json');
}

function badSettingsFile(details) {
  return new LauncherError('BAD_SETTINGS_FILE', '올바른 Cafeteria 설정 파일이 아닙니다.', details);
}

function noClientSettings() {
  return new LauncherError('NO_CLIENT_SETTINGS', '저장된 Cafeteria 설정이 없습니다. 게임을 한 번 실행한 뒤 다시 시도해 주세요.');
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    throw badSettingsFile();
  }
}

/**
 * Cafeteria 설정(cafeteria.json 의 내용)처럼 생겼는지 확인한다. 모듈 하나하나의 값은 게임이 읽을 때 걸러 낸다.
 *
 * @param {unknown} value
 * @returns {object} 받은 값 그대로
 */
function validateClientSettings(value) {
  if (!isPlainObject(value) || !isPlainObject(value.modules)) {
    throw badSettingsFile();
  }

  return value;
}

/**
 * 게임 폴더의 Cafeteria 설정을 파일로 내보낸다.
 *
 * @param {object} options
 * @param {string} options.gameDir Cafeteria 게임 폴더
 * @param {string} options.filePath 내보낼 파일
 * @param {string} options.appVersion 런처 버전 (어느 버전에서 만든 파일인지 적어 둔다)
 * @returns {Promise<{modules: number}>} 내보낸 모듈 수
 */
async function exportClientSettings({ gameDir, filePath, appVersion }) {
  let text;

  try {
    text = await fsp.readFile(settingsPath(gameDir), 'utf8');
  } catch (error) {
    throw error.code === 'ENOENT'
      ? noClientSettings()
      : new LauncherError('SETTINGS_READ_FAILED', '저장된 Cafeteria 설정을 읽지 못했습니다.', { cause: error.code });
  }

  const settings = validateClientSettings(parseJson(text));
  const content = JSON.stringify({ format: FILE_FORMAT, type: FILE_TYPE, createdWith: appVersion, settings }, null, 2);

  try {
    await writeFileAtomic(filePath, content);
  } catch (error) {
    throw new LauncherError('SETTINGS_EXPORT_FAILED', '설정 파일을 저장하지 못했습니다. 다른 위치를 골라 주세요.', { cause: error.code });
  }

  return { modules: Object.keys(settings.modules).length };
}

/**
 * 내보낸 설정 파일을 읽어 그 안의 Cafeteria 설정을 돌려준다. 남에게 받은 파일이므로 크기와 형식을 먼저 확인한다.
 *
 * @param {string} filePath
 * @returns {Promise<object>}
 */
async function readClientSettingsFile(filePath) {
  let text;

  try {
    const info = await fsp.stat(filePath);

    // 폴더나 장치처럼 보통 파일이 아닌 것은 stat 의 크기를 믿을 수 없으므로 읽지 않는다.
    if (!info.isFile() || info.size > MAX_SETTINGS_BYTES) {
      throw badSettingsFile();
    }

    text = await fsp.readFile(filePath, 'utf8');
  } catch (error) {
    // 읽지 못한 것도 같은 안내문으로 알리되, 원인(ENOENT, EACCES 등)은 로그에 남게 한다.
    throw error instanceof LauncherError ? error : badSettingsFile({ cause: error.code });
  }

  const file = parseJson(text);

  if (!isPlainObject(file) || file.format !== FILE_FORMAT || file.type !== FILE_TYPE) {
    throw badSettingsFile();
  }

  return validateClientSettings(file.settings);
}

/**
 * Cafeteria 설정을 게임 폴더에 쓴다. 이미 있던 설정은 cafeteria.json.bak 으로 남긴다.
 * 게임이 켜져 있으면 게임이 끌 때 자기 설정으로 다시 덮어쓰므로, 부르는 쪽에서 게임이 꺼져 있는지 확인한다.
 *
 * @param {object} options
 * @param {string} options.gameDir Cafeteria 게임 폴더
 * @param {object} options.settings validateClientSettings 를 통과한 설정
 * @returns {Promise<{backedUp: boolean}>} 기존 설정을 bak 으로 남겼는지
 */
async function applyClientSettings({ gameDir, settings }) {
  const file = settingsPath(gameDir);
  const content = JSON.stringify(settings, null, 2);
  const backedUp = await exists(file);

  try {
    if (backedUp) {
      await fsp.copyFile(file, `${file}.bak`);
    }

    // config 폴더가 없으면 writeFileAtomic 이 만든다.
    await writeFileAtomic(file, content);
  } catch (error) {
    throw new LauncherError('SETTINGS_WRITE_FAILED', '설정 파일을 쓰지 못했습니다. 게임을 끈 뒤 다시 시도해 주세요.', { cause: error.code });
  }

  return { backedUp };
}

/**
 * 내보내기 한 번의 전체 흐름. 저장할 곳을 묻는 일은 부르는 쪽(대화상자)이 맡는다.
 *
 * @param {object} options
 * @param {string} options.gameDir Cafeteria 게임 폴더
 * @param {string} options.appVersion 런처 버전
 * @param {() => Promise<string | null | undefined>} options.chooseFile 내보낼 파일을 고르게 한다. 취소하면 null
 * @returns {Promise<{modules: number} | null>} 취소하면 null
 */
async function exportFlow({ gameDir, appVersion, chooseFile }) {
  // 내보낼 것이 없으면 저장할 곳을 고르게 하기 전에 알려 준다.
  if (!(await exists(settingsPath(gameDir)))) {
    throw noClientSettings();
  }

  const filePath = await chooseFile();
  return filePath ? exportClientSettings({ gameDir, filePath, appVersion }) : null;
}

/**
 * 가져오기 한 번의 전체 흐름. 파일을 고르고 확인을 받는 일은 부르는 쪽(대화상자)이 맡는다.
 *
 * @param {object} options
 * @param {string} options.gameDir Cafeteria 게임 폴더
 * @param {() => Promise<string | null | undefined>} options.chooseFile 가져올 파일을 고르게 한다. 취소하면 null
 * @param {(info: {modules: number}) => Promise<boolean>} options.confirm 덮어써도 되는지 묻는다
 * @param {() => boolean | Promise<boolean>} options.isGameRunning 지금 게임이 켜져 있는지
 * @returns {Promise<{modules: number, backedUp: boolean} | null>} 취소하면 null
 */
async function importFlow({ gameDir, chooseFile, confirm, isGameRunning }) {
  await assertGameNotRunning(isGameRunning);
  const filePath = await chooseFile();

  if (!filePath) {
    return null;
  }

  const settings = await readClientSettingsFile(filePath);
  const modules = Object.keys(settings.modules).length;

  if (!(await confirm({ modules }))) {
    return null;
  }

  // 파일을 고르고 확인하는 동안 게임이 켜졌을 수 있다.
  await assertGameNotRunning(isGameRunning);
  const { backedUp } = await applyClientSettings({ gameDir, settings });
  return { modules, backedUp };
}

module.exports = {
  SETTINGS_EXTENSION,
  MAX_SETTINGS_BYTES,
  validateClientSettings,
  exportClientSettings,
  readClientSettingsFile,
  applyClientSettings,
  exportFlow,
  importFlow,
};
