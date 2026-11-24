'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { once } = require('node:events');
const { finished } = require('node:stream/promises');
const { USER_AGENT } = require('./constants');
const { LauncherError } = require('./errors');

const JSON_TIMEOUT_MS = 30_000;
const DOWNLOAD_TIMEOUT_MS = 10 * 60_000;

/** Electron 에서는 시스템 프록시 설정을 따르는 net.fetch 로 바꿔 끼운다. 테스트에서는 가짜 fetch 를 넣는다. */
let fetchImpl = (...args) => globalThis.fetch(...args);

function setFetch(fetchFunction) {
  fetchImpl = fetchFunction;
}

async function request(url, { headers = {}, timeoutMs, method = 'GET', body }) {
  let response;

  try {
    response = await fetchImpl(url, {
      method,
      body,
      headers: { 'User-Agent': USER_AGENT, ...headers },
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (error) {
    throw new LauncherError('NETWORK', '서버에 연결하지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요.', {
      url: String(url),
      cause: error.message,
    });
  }

  if (!response.ok) {
    throw new LauncherError('HTTP', `서버가 요청을 거부했습니다 (HTTP ${response.status}).`, { url: String(url), status: response.status });
  }

  return response;
}

function getJson(url, { headers = {} } = {}) {
  return readJson(url, { headers });
}

/** JSON 본문을 보내고 JSON 응답을 받는다. */
function postJson(url, payload, { headers = {} } = {}) {
  return readJson(url, { headers: { 'Content-Type': 'application/json', ...headers }, method: 'POST', body: JSON.stringify(payload) });
}

async function readJson(url, { headers, method, body }) {
  const response = await request(url, { headers: { Accept: 'application/json', ...headers }, timeoutMs: JSON_TIMEOUT_MS, method, body });

  try {
    return await response.json();
  } catch (error) {
    throw new LauncherError('BAD_RESPONSE', '서버 응답을 해석하지 못했습니다.', { url: String(url), cause: error.message });
  }
}

/**
 * 요청을 보내고 상태 코드와 JSON 본문을 돌려준다. 2xx 가 아니어도 던지지 않으며, 본문이 JSON 이 아니면 body 는 null 이다.
 * 로그인 서버처럼 실패 응답의 본문(오류 코드)을 읽어야 하는 곳에서 쓴다.
 *
 * @param {string} url
 * @param {{method?: string, headers?: object, json?: any, form?: Record<string, string>}} [options]
 * @returns {Promise<{status: number, body: any | null}>}
 */
async function sendJson(url, { method = 'GET', headers = {}, json, form } = {}) {
  const sendHeaders = { Accept: 'application/json', ...headers };
  let body;

  if (form) {
    body = new URLSearchParams(form).toString();
    sendHeaders['Content-Type'] = 'application/x-www-form-urlencoded';
  } else if (json !== undefined) {
    body = JSON.stringify(json);
    sendHeaders['Content-Type'] = 'application/json';
  }

  let response;

  try {
    response = await fetchImpl(url, {
      method,
      body,
      headers: { 'User-Agent': USER_AGENT, ...sendHeaders },
      signal: AbortSignal.timeout(JSON_TIMEOUT_MS),
    });
  } catch (error) {
    throw new LauncherError('NETWORK', '서버에 연결하지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요.', {
      url: String(url),
      cause: error.message,
    });
  }

  try {
    return { status: response.status, body: await response.json() };
  } catch {
    return { status: response.status, body: null };
  }
}

/** 폴더 만들기, 임시 파일 열기·쓰기, 이름 바꾸기처럼 이 컴퓨터 안에서 난 실패. 연결 문제가 아니므로 다시 시도하지 않는다. */
function writeFailed(error) {
  return new LauncherError('WRITE_FAILED', '파일을 저장하지 못했습니다. 디스크 공간과 폴더 권한을 확인한 뒤 다시 시도해 주세요.', {
    cause: error.code || error.message,
  });
}

/**
 * 파일을 내려받는다. 임시 파일(.part)에 받으면서 해시를 계산하고, 검증이 끝난 뒤에만 최종 이름으로 바꾼다.
 *
 * @param {string} url
 * @param {string} destination 최종 파일 경로
 * @param {object} options
 * @param {string[]} options.allowedHosts 이 목록에 없는 호스트의 주소는 받지 않는다
 * @param {{sha1?: string, sha512?: string}} [options.hashes] 주어진 해시와 다르면 실패로 처리한다
 * @param {(received: number, total: number) => void} [options.onProgress]
 */
async function download(url, destination, { allowedHosts, hashes = {}, onProgress, headers = {} }) {
  const parsed = new URL(url);

  if (parsed.protocol !== 'https:' || !allowedHosts.includes(parsed.hostname)) {
    throw new LauncherError('UNTRUSTED_URL', '허용되지 않은 주소라서 다운로드하지 않았습니다.', { url: String(url) });
  }

  const response = await request(url, { headers, timeoutMs: DOWNLOAD_TIMEOUT_MS });
  // 리다이렉트된 최종 주소도 허용 목록 안에 있어야 한다.
  const finalHost = response.url ? new URL(response.url).hostname : parsed.hostname;

  if (!allowedHosts.includes(finalHost)) {
    throw new LauncherError('UNTRUSTED_URL', '허용되지 않은 주소로 연결되어 다운로드를 중단했습니다.', { url: response.url });
  }

  const total = Number(response.headers.get('content-length')) || 0;
  const temporary = `${destination}.part`;
  const sha1 = crypto.createHash('sha1');
  const sha512 = crypto.createHash('sha512');

  try {
    await fsp.mkdir(path.dirname(destination), { recursive: true });
  } catch (error) {
    throw writeFailed(error);
  }

  const file = fs.createWriteStream(temporary);
  // 열기·쓰기 오류는 언제 나도 여기서 받아 둔다. 듣는 곳이 없으면 프로세스 전체가 잡히지 않은 예외로 멈춘다.
  let writeError = null;
  file.on('error', (error) => {
    writeError = writeError || error;
  });
  let received = 0;

  try {
    for await (const chunk of response.body) {
      if (writeError) {
        throw writeFailed(writeError);
      }

      const buffer = Buffer.from(chunk);
      sha1.update(buffer);
      sha512.update(buffer);
      received += buffer.length;

      if (!file.write(buffer)) {
        // events.once 는 기다리는 동안 'error' 가 나면 거부된다.
        await once(file, 'drain').catch((error) => {
          throw writeFailed(error);
        });
      }

      if (onProgress) {
        onProgress(received, total);
      }
    }

    file.end();
    await finished(file).catch((error) => {
      throw writeFailed(writeError || error);
    });

    if (writeError) {
      throw writeFailed(writeError);
    }

    const actual = { sha1: sha1.digest('hex'), sha512: sha512.digest('hex') };

    for (const algorithm of ['sha512', 'sha1']) {
      if (hashes[algorithm] && hashes[algorithm].toLowerCase() !== actual[algorithm]) {
        throw new LauncherError('HASH_MISMATCH', '받은 파일이 손상되었거나 원본과 다릅니다. 다시 시도해 주세요.', {
          url: String(url),
          algorithm,
        });
      }
    }

    try {
      await fsp.rename(temporary, destination);
    } catch (error) {
      throw writeFailed(error);
    }

    return { size: received, ...actual };
  } catch (error) {
    if (!file.closed) {
      await new Promise((resolve) => {
        file.once('close', resolve);
        file.destroy();
      });
    }

    // 같은 이름의 폴더처럼 지울 수 없는 것이 있어도 원래 오류를 그대로 알린다.
    await fsp.rm(temporary, { force: true }).catch(() => {});

    if (error instanceof LauncherError) {
      throw error;
    }

    throw new LauncherError('NETWORK', '다운로드가 중간에 끊겼습니다. 다시 시도해 주세요.', { url: String(url), cause: error.message });
  }
}

module.exports = { setFetch, getJson, postJson, sendJson, download };
