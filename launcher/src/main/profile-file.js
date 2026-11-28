'use strict';

const crypto = require('node:crypto');
const fsp = require('node:fs/promises');
const path = require('node:path');
const AdmZip = require('adm-zip');
const { validateClientSettings } = require('./client-settings');
const { CAFETERIA_MOD_FILE_PREFIX, MINECRAFT_VERSION } = require('./constants');
const { LauncherError } = require('./errors');

/*
 * 모드 프로필 파일의 형식: 만들기(packProfile)와 읽기(readProfile). 무엇을 내보내고 어떻게 적용할지는 profile.js 가 정한다.
 *
 * 프로필 파일은 zip 이다.
 *   profile.json           목록 (형식 번호, 이름, Minecraft 버전, 모드마다 종류·이름·해시·크기)
 *   mods/<파일 이름>        Modrinth 에 없어서 파일째 넣은 모드
 *   config/cafeteria.json  게임 안 Cafeteria 설정 (넣었을 때만)
 * Modrinth 에 있는 모드는 해시만 적고, 받는 쪽이 그 해시의 파일을 Modrinth 에서 받는다. 받을 주소는 파일에 적지 않는다.
 *
 * 프로필 파일은 모르는 사람이 만든 것일 수 있으므로, 읽는 쪽은 파일의 모든 값을 의심한다.
 */

/** 프로필 파일의 확장자. */
const PROFILE_EXTENSION = 'cafeteria-profile';

/** 프로필에서 읽는 것들의 최대 크기와 개수. zip 은 통째로 메모리에 올려 읽으므로 크게 잡지 않는다. */
const LIMITS = {
  fileBytes: 200 * 1024 * 1024,
  manifestBytes: 1024 * 1024,
  mods: 300,
  jarBytes: 100 * 1024 * 1024,
  settingsBytes: 1024 * 1024,
};

const FILE_FORMAT = 1;
const FILE_TYPE = 'cafeteria-profile';
const MANIFEST_ENTRY = 'profile.json';
const SETTINGS_ENTRY = 'config/cafeteria.json';
const MODS_ENTRY_PREFIX = 'mods/';
const KINDS = ['modrinth', 'file'];

const DEFAULT_NAME = 'Cafeteria 프로필';
const MAX_NAME_LENGTH = 60;
const MAX_TEXT_LENGTH = 200;
const SHA1_PATTERN = /^[0-9a-f]{40}$/i;
const JAR_NAME_PATTERN = /^[^\\/:*?"<>|\u0000-\u001f]+\.jar$/i;
/** 파일 이름의 최대 길이. 이보다 긴 이름은 파일로 만들 수 없고, 안내문에 그대로 들어가므로 한없이 길게 두지 않는다. */
const MAX_FILE_NAME_LENGTH = 255;
/** zip 항목을 압축하지 않고 넣었다는 표시 (adm-zip 의 entry.header.method). */
const ZIP_STORED = 0;
/** zip 항목의 이름에 들어갈 수 있는 폴더 수. 프로필이 쓰는 항목에는 하나뿐이다 (mods/<파일 이름>, config/cafeteria.json). */
const MAX_ENTRY_FOLDERS = 8;

const NOT_A_PROFILE = '올바른 Cafeteria 프로필 파일이 아닙니다.';
const BAD_MOD_LIST = '프로필의 모드 목록이 올바르지 않습니다.';

function badProfile(reason, details) {
  return new LauncherError('BAD_PROFILE', reason, details);
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

/** 화면에 보여 줄 글자 값. 글자가 아니면 없는 것으로 보고, 너무 길면 자른다. */
function text(value, maxLength = MAX_TEXT_LENGTH) {
  return typeof value === 'string' ? value.slice(0, maxLength) : '';
}

/** 프로필 이름: 앞뒤 공백을 뗀 1~60자. 비어 있으면 기본 이름을 쓴다. */
function profileName(value) {
  const name = typeof value === 'string' ? value.trim().slice(0, MAX_NAME_LENGTH).trim() : '';
  return name || DEFAULT_NAME;
}

/** mods 폴더 바로 아래에 둘 수 있는 jar 파일 이름인지. 경로가 섞인 이름과, 런처가 관리하는 Cafeteria 모드의 이름은 받지 않는다. */
function isAllowedFileName(fileName) {
  if (typeof fileName !== 'string' || fileName.length > MAX_FILE_NAME_LENGTH || fileName !== path.basename(fileName) || !JAR_NAME_PATTERN.test(fileName)) {
    return false;
  }

  // Windows 에서는 대소문자만 다른 이름도 같은 파일이다.
  return !fileName.toLowerCase().startsWith(CAFETERIA_MOD_FILE_PREFIX);
}

/**
 * 프로필 파일의 내용(zip)을 만든다.
 *
 * @param {object} options
 * @param {string} [options.name] 프로필 이름
 * @param {string} options.createdWith 런처 버전 (어느 버전에서 만든 파일인지 적어 둔다)
 * @param {{kind: 'modrinth' | 'file', title: string, version: string, fileName: string, sha1: string, size: number, data?: Buffer}[]} options.mods
 *   data 가 있는 모드는 그 내용을 mods/<fileName> 으로 넣는다
 * @param {Buffer | null} options.settings config/cafeteria.json 의 내용. 넣지 않으면 null
 * @returns {Buffer}
 */
function packProfile({ name, createdWith, mods, settings }) {
  const zip = new AdmZip();

  const list = mods.map(({ data, ...mod }) => {
    if (data) {
      zip.addFile(MODS_ENTRY_PREFIX + mod.fileName, data);
    }

    return mod;
  });

  if (settings) {
    zip.addFile(SETTINGS_ENTRY, settings);
  }

  const manifest = { format: FILE_FORMAT, type: FILE_TYPE, name: profileName(name), createdWith, minecraft: MINECRAFT_VERSION, hasSettings: Boolean(settings), mods: list };
  zip.addFile(MANIFEST_ENTRY, Buffer.from(JSON.stringify(manifest, null, 2)));
  return zip.toBuffer();
}

/** adm-zip 이 zip 항목의 이름을 읽을 때 쓰는 함수들 (기본값과 같은 UTF-8). 폴더가 너무 깊은 이름을 만나면 던져서 zip 읽기를 멈춘다. */
const ENTRY_NAME_DECODER = {
  efs: true,
  encode: (name) => Buffer.from(name, 'utf8'),
  decode: (data) => {
    const name = data.toString('utf8');

    if (name.split('/').length - 1 > MAX_ENTRY_FOLDERS) {
      throw new Error('폴더가 너무 깊은 zip 항목');
    }

    return name;
  },
};

/**
 * 받은 파일의 내용을 zip 으로 연다. 항목이 너무 많으면 던진다.
 *
 * adm-zip 은 항목을 처음 찾을 때 모든 항목의 머리를 읽으면서 항목 하나에 9KB 쯤을 쓰고, 이름에 든 폴더마다(a/b/c 면 a/ 와 a/b/)
 * 항목을 하나씩 더 만든다. 항목이 아주 많거나 폴더가 아주 깊은 zip 은 64KB 짜리로도 메모리를 1GB 넘게 쓰게 하므로,
 * 개수는 여기서 읽기 전에, 이름은 읽는 도중에(ENTRY_NAME_DECODER) 막는다.
 */
function openZip(buffer, limits) {
  const zip = new AdmZip(buffer, { decoder: ENTRY_NAME_DECODER });

  // 아직 항목을 읽기 전이라 zip 끝에 적힌 개수가 나오고, adm-zip 은 그 개수만큼만 읽는다.
  // 런처가 만든 프로필에는 profile.json, 설정, 파일째 넣은 모드만 들어 있다.
  if (zip.getEntryCount() > limits.mods + 2) {
    throw new Error('항목이 너무 많은 zip');
  }

  return zip;
}

/** zip 에서 정확히 이 이름을 가진 항목을 찾는다. 없으면 null. */
function findEntry(zip, name) {
  const entry = zip.getEntry(name);

  // getEntry 는 찾는 이름을 정리해서(a/../b → b) 찾는다. 돌려준 항목의 이름이 찾던 이름과 같을 때만 쓴다.
  return entry && entry.entryName === name ? entry : null;
}

/**
 * zip 항목의 내용을 꺼낸다. 머리에 적힌 "풀린 크기"(entry.header.size)보다 큰 내용은 돌려주지 않는다.
 * 부르는 쪽이 그 크기를 먼저 확인하므로, 크기를 속인 항목이 확인한 것보다 많은 메모리를 쓰게 두면 안 된다.
 */
function extractEntry(entry) {
  const { header } = entry;

  // 압축하지 않은 항목은 adm-zip 이 적힌 크기와 상관없이 zip 에 든 바이트를 그대로 돌려준다. 두 크기가 다르면 꺼내지 않는다.
  if (header.method === ZIP_STORED && header.compressedSize !== header.size) {
    throw new Error('적힌 크기와 들어 있는 크기가 다른 zip 항목');
  }

  // 압축한 항목은 adm-zip 이 적힌 크기까지만 풀고, 더 나오면 던진다 (adm-zip 의 methods/inflater.js, maxOutputLength).
  return entry.getData();
}

/** zip 의 JSON 항목을 읽는다. 항목이 없거나, 머리에 적힌 크기가 maxBytes 보다 크거나, JSON 이 아니면 던진다. */
function readJsonEntry(zip, name, maxBytes) {
  const entry = findEntry(zip, name);

  if (!entry || entry.header.size > maxBytes) {
    throw new Error(`zip 에 ${name} 이 없거나 너무 크다`);
  }

  return JSON.parse(extractEntry(entry).toString('utf8'));
}

/**
 * profile.json 의 모드 목록을 검사해서 쓸 값만 골라 낸다. 목록에 그 밖의 값(받을 주소, zip 안의 경로 등)이 있어도 읽지 않는다.
 *
 * @returns {{kind: 'modrinth' | 'file', title: string, version: string, fileName: string, sha1: string, size: number}[]}
 */
function readMods(zip, list, limits) {
  const hashes = new Set();
  const names = new Set();

  return list.map((raw) => {
    if (!isPlainObject(raw) || !KINDS.includes(raw.kind) || typeof raw.sha1 !== 'string' || !SHA1_PATTERN.test(raw.sha1)) {
      throw badProfile(BAD_MOD_LIST);
    }

    if (!isAllowedFileName(raw.fileName)) {
      throw badProfile('프로필에 쓸 수 없는 모드 파일 이름이 있습니다.');
    }

    if (!Number.isInteger(raw.size) || raw.size <= 0 || raw.size > limits.jarBytes) {
      throw badProfile(`프로필에 크기가 올바르지 않은 모드가 있습니다: ${raw.fileName}`);
    }

    const mod = { kind: raw.kind, title: text(raw.title), version: text(raw.version), fileName: raw.fileName, sha1: raw.sha1.toLowerCase(), size: raw.size };

    if (hashes.has(mod.sha1) || names.has(mod.fileName.toLowerCase())) {
      throw badProfile(`프로필에 같은 모드가 두 번 들어 있습니다: ${mod.fileName}`);
    }

    hashes.add(mod.sha1);
    names.add(mod.fileName.toLowerCase());

    if (mod.kind === 'file') {
      // 파일째 넣은 모드는 항상 mods/<파일 이름> 에 있다. 내용은 적용할 때 꺼내고, 여기서는 머리에 적힌 크기만 본다.
      const entry = findEntry(zip, MODS_ENTRY_PREFIX + mod.fileName);

      if (!entry || entry.header.size !== mod.size) {
        throw badProfile(`프로필에 모드 파일이 들어 있지 않거나 적힌 크기와 다릅니다: ${mod.fileName}`);
      }
    }

    return mod;
  });
}

/**
 * 프로필 파일을 읽어 검사한다. 요약을 보여 줄 때와 적용할 때 모두 이것으로 읽어서 같은 규칙이 적용되게 한다.
 * 규칙을 하나라도 어기면 BAD_PROFILE 로 거절한다.
 * zip 에서 읽는 항목은 profile.json, 파일째 넣은 모드의 mods/<파일 이름>, config/cafeteria.json 뿐이고 나머지는 풀지 않는다.
 *
 * @param {string} filePath
 * @param {typeof LIMITS} limits
 * @returns {Promise<{fingerprint: string, name: string, createdWith: string, settings: object | null, mods: object[], extractJar: (mod: object) => Buffer}>}
 *   fingerprint: 파일 내용의 sha256, settings: 프로필에 든 Cafeteria 설정 (없으면 null),
 *   extractJar: 파일째 든 모드(kind 가 file)의 내용을 꺼낸다. 목록에 적힌 크기보다 크게 풀리는 항목이면 던진다.
 *   내용이 목록의 해시와 같은지는 부르는 쪽이 확인한다
 */
async function readProfile(filePath, limits) {
  let buffer;

  try {
    const info = await fsp.stat(filePath);

    // 폴더나 장치처럼 보통 파일이 아닌 것은 stat 의 크기를 믿을 수 없으므로 읽지 않는다.
    if (!info.isFile()) {
      throw badProfile(NOT_A_PROFILE);
    }

    // 통째로 메모리에 올리기 전에 크기부터 본다.
    if (info.size > limits.fileBytes) {
      throw badProfile('프로필 파일이 너무 큽니다.');
    }

    buffer = await fsp.readFile(filePath);
  } catch (error) {
    throw error instanceof LauncherError ? error : badProfile('프로필 파일을 읽지 못했습니다.', { cause: error.code });
  }

  let zip;
  let manifest;

  try {
    // 지문을 구하는 바로 그 내용으로 zip 을 연다. 파일을 다시 읽으면 그 사이에 바뀔 수 있다.
    zip = openZip(buffer, limits);
    manifest = readJsonEntry(zip, MANIFEST_ENTRY, limits.manifestBytes);
  } catch {
    throw badProfile(NOT_A_PROFILE);
  }

  if (!isPlainObject(manifest) || manifest.type !== FILE_TYPE) {
    throw badProfile(NOT_A_PROFILE);
  }

  if (manifest.format !== FILE_FORMAT) {
    throw badProfile('이 런처로는 읽을 수 없는 형식의 프로필입니다. 런처를 최신 버전으로 업데이트한 뒤 다시 시도해 주세요.');
  }

  if (manifest.minecraft !== MINECRAFT_VERSION) {
    throw badProfile(`Minecraft ${text(manifest.minecraft, 20) || '다른 버전'} 용 프로필입니다. Minecraft ${MINECRAFT_VERSION} 용 프로필만 쓸 수 있습니다.`);
  }

  if (!Array.isArray(manifest.mods)) {
    throw badProfile(BAD_MOD_LIST);
  }

  if (manifest.mods.length > limits.mods) {
    throw badProfile(`프로필에 모드가 너무 많습니다 (최대 ${limits.mods}개).`);
  }

  const mods = readMods(zip, manifest.mods, limits);
  let settings = null;

  if (manifest.hasSettings === true) {
    try {
      settings = validateClientSettings(readJsonEntry(zip, SETTINGS_ENTRY, limits.settingsBytes));
    } catch {
      throw badProfile('프로필에 들어 있는 Cafeteria 설정이 올바르지 않습니다.');
    }
  }

  return {
    fingerprint: crypto.createHash('sha256').update(buffer).digest('hex'),
    name: profileName(manifest.name),
    createdWith: text(manifest.createdWith),
    settings,
    mods,
    extractJar: (mod) => extractEntry(findEntry(zip, MODS_ENTRY_PREFIX + mod.fileName)),
  };
}

module.exports = { PROFILE_EXTENSION, LIMITS, packProfile, readProfile };
