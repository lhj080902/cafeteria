'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
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
  await fsp.mkdir(path.dirname(destination), { recursive: true });
  const file = fs.createWriteStream(temporary);
  let received = 0;

  try {
    for await (const chunk of response.body) {
      const buffer = Buffer.from(chunk);
      sha1.update(buffer);
      sha512.update(buffer);
      received += buffer.length;

      if (!file.write(buffer)) {
        await new Promise((resolve) => file.once('drain', resolve));
      }

      if (onProgress) {
        onProgress(received, total);
      }
    }

    await new Promise((resolve, reject) => {
      file.once('error', reject);
      file.end(resolve);
    });

    const actual = { sha1: sha1.digest('hex'), sha512: sha512.digest('hex') };

    for (const algorithm of ['sha512', 'sha1']) {
      if (hashes[algorithm] && hashes[algorithm].toLowerCase() !== actual[algorithm]) {
        throw new LauncherError('HASH_MISMATCH', '받은 파일이 손상되었거나 원본과 다릅니다. 다시 시도해 주세요.', {
          url: String(url),
          algorithm,
        });
      }
    }

    await fsp.rename(temporary, destination);
    return { size: received, ...actual };
  } catch (error) {
    file.destroy();
    await fsp.rm(temporary, { force: true });

    if (error instanceof LauncherError) {
      throw error;
    }

    throw new LauncherError('NETWORK', '다운로드가 중간에 끊겼습니다. 다시 시도해 주세요.', { url: String(url), cause: error.message });
  }
}

module.exports = { setFetch, getJson, postJson, download };
