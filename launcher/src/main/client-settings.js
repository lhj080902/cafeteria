'use strict';

const fsp = require('node:fs/promises');
const path = require('node:path');
const { LauncherError } = require('./errors');
const { exists, writeFileAtomic } = require('./fs-util');

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

function badSettingsFile() {
  return new LauncherError('BAD_SETTINGS_FILE', '올바른 Cafeteria 설정 파일이 아닙니다.');
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
    if (error.code === 'ENOENT') {
      throw new LauncherError('NO_CLIENT_SETTINGS', '저장된 Cafeteria 설정이 없습니다. 게임을 한 번 실행한 뒤 다시 시도해 주세요.');
    }

    throw error;
  }

  const settings = validateClientSettings(parseJson(text));
  await writeFileAtomic(filePath, JSON.stringify({ format: FILE_FORMAT, type: FILE_TYPE, createdWith: appVersion, settings }, null, 2));
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

    if (info.size > MAX_SETTINGS_BYTES) {
      throw badSettingsFile();
    }

    text = await fsp.readFile(filePath, 'utf8');
  } catch {
    throw badSettingsFile();
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
  const backedUp = await exists(file);

  if (backedUp) {
    await fsp.copyFile(file, `${file}.bak`);
  }

  // config 폴더가 없으면 writeFileAtomic 이 만든다.
  await writeFileAtomic(file, JSON.stringify(settings, null, 2));
  return { backedUp };
}

module.exports = {
  SETTINGS_EXTENSION,
  MAX_SETTINGS_BYTES,
  validateClientSettings,
  exportClientSettings,
  readClientSettingsFile,
  applyClientSettings,
};
