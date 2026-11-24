'use strict';

const childProcess = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const constants = require('./constants');
const { LauncherError } = require('./errors');
const { javaArgs } = require('./installer');
const { ruleAllows } = require('./version-meta');

const PLACEHOLDER = /\$\{([^}]*)\}/g;

/** 공백으로 나누되 큰따옴표로 묶은 부분은 하나로 본다. 따옴표 자체는 뺀다. */
function splitArgs(text) {
  const result = [];
  let current = '';
  let started = false;
  let quoted = false;

  for (const char of text) {
    if (char === '"') {
      quoted = !quoted;
      started = true;
    } else if (/\s/.test(char) && !quoted) {
      if (started) {
        result.push(current);
        current = '';
        started = false;
      }
    } else {
      current += char;
      started = true;
    }
  }

  if (started) {
    result.push(current);
  }

  return result;
}

/** 버전 JSON 의 인수 목록을 문자열 배열로 풀어낸다. 규칙이 막은 항목은 뺀다. */
function expand(entries, features) {
  const result = [];

  for (const entry of entries || []) {
    if (typeof entry === 'string') {
      result.push(entry);
    } else if (ruleAllows(entry.rules, features)) {
      result.push(...(Array.isArray(entry.value) ? entry.value : [entry.value]));
    }
  }

  return result;
}

/** ${이름} 을 치환한다. 모르는 이름이 남으면 null. */
function substitute(text, values) {
  let unresolved = false;
  const replaced = text.replace(PLACEHOLDER, (match, name) => {
    if (Object.hasOwn(values, name) && values[name] !== undefined) {
      return values[name];
    }
    unresolved = true;
    return match;
  });
  return unresolved ? null : replaced;
}

/** 게임 인수를 치환한다. 값이 비었거나 모르는 이름이 남는 옵션은 이름과 값을 함께 뺀다. */
function substituteGame(tokens, values) {
  const result = [];

  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];
    const next = tokens[i + 1];

    if (token.startsWith('--') && next !== undefined && !next.startsWith('--')) {
      const value = substitute(next, values);
      i += 1;

      if (value) {
        result.push(token, value);
      }
    } else {
      const value = substitute(token, values);

      if (value) {
        result.push(value);
      }
    }
  }

  return result;
}

/** java 실행 파일 뒤에 올 인수 전체를 만든다. */
function buildArgs({ versionJson, layout, libraries, session, gameDir, launcherVersion, memoryGb, jvmArgs = '', resolution = null, fabric = constants.FABRIC }) {
  const features = { has_custom_resolution: Boolean(resolution) };
  const classpath = [
    ...libraries.map((library) => path.join(layout.librariesDir, ...library.path.split('/'))),
    layout.clientJar,
  ].join(';');
  const values = {
    natives_directory: layout.nativesDir,
    launcher_name: 'Cafeteria',
    launcher_version: launcherVersion,
    classpath,
    auth_player_name: session.name,
    auth_uuid: session.uuid,
    auth_access_token: session.accessToken,
    auth_xuid: session.xuid,
    version_name: constants.VERSION_ID,
    game_directory: gameDir,
    assets_root: layout.assetsDir,
    assets_index_name: versionJson.assetIndex.id,
    user_type: 'msa',
    version_type: 'release',
  };

  if (resolution) {
    values.resolution_width = String(resolution.width);
    values.resolution_height = String(resolution.height);
  }

  const vanillaJvm = expand(versionJson.arguments.jvm, features).map((arg) => substitute(arg, values) ?? arg);

  return [
    ...splitArgs(javaArgs(memoryGb, jvmArgs)),
    ...vanillaJvm,
    ...fabric.jvmArgs,
    fabric.mainClass,
    ...substituteGame(expand(versionJson.arguments.game, features), values),
  ];
}

/**
 * 게임을 런처와 분리해서 시작한다. 표준 오류만 logs/launcher-stderr.log 에 남긴다.
 * args 에는 액세스 토큰이 들어 있으므로 오류나 로그에 넣지 않는다.
 */
async function launchGame({ javaPath, args, gameDir, spawn = childProcess.spawn }) {
  const logDir = path.join(gameDir, 'logs');
  fs.mkdirSync(logDir, { recursive: true });
  const fd = fs.openSync(path.join(logDir, 'launcher-stderr.log'), 'w');

  try {
    return await new Promise((resolve, reject) => {
      const child = spawn(javaPath, args, { cwd: gameDir, detached: true, stdio: ['ignore', 'ignore', fd] });

      child.once('spawn', () => {
        child.unref();
        resolve(child);
      });
      child.once('error', (error) => {
        reject(new LauncherError('LAUNCH_FAILED', '게임을 시작하지 못했습니다. Java 실행 파일을 확인해 주세요.', { javaPath, cause: error.message }));
      });
    });
  } finally {
    fs.closeSync(fd);
  }
}

module.exports = { splitArgs, buildArgs, launchGame };
