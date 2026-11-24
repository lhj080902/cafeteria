'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { Transform } = require('node:stream');
const { pipeline } = require('node:stream/promises');
const { DOWNLOAD_HOSTS, FABRIC, JAVA_RUNTIME, MINECRAFT_VERSION, MINECRAFT_VERSION_JSON } = require('./constants');
const { exists } = require('./fs-util');
const http = require('./http');
const { launchLibraries } = require('./version-meta');

/** 받을 파일 전체가 이어지는 고정 해시. 테스트에서는 가짜 서버에 맞춘 값을 넘긴다. */
const DEFAULT_PINS = { minecraftVersion: MINECRAFT_VERSION, version: MINECRAFT_VERSION_JSON, runtime: JAVA_RUNTIME, fabric: FABRIC };
const CONCURRENCY = 8;
/** 연결이 끊기거나(NETWORK) 서버가 잠깐 바쁜 경우(HTTP 429, 5xx)에만 파일당 이만큼 시도한다. 해시 불일치 같은 다른 오류는 바로 실패한다. */
const NETWORK_ATTEMPTS = 3;
/** 다시 시도하기 전에 (이 값 × 시도 횟수) 밀리초 기다린다. 테스트에서는 setRetryDelay(0) 으로 줄인다. */
let retryDelayMs = 500;

function setRetryDelay(milliseconds) {
  retryDelayMs = milliseconds;
}
const ASSET_HOST = 'https://resources.download.minecraft.net';
const CHECKING_MESSAGE = '게임 파일을 확인하는 중...';

/** 바로 실행에 쓰는 파일들의 위치. 모두 path.join 으로 만든 절대 경로다. */
function layout(dataDir, pins = DEFAULT_PINS) {
  const versionDir = path.join(dataDir, 'versions', pins.minecraftVersion);
  const runtimeName = `${pins.runtime.component}-${pins.runtime.version}`;
  const runtimeDir = path.join(dataDir, 'runtime', runtimeName);

  return {
    versionJson: path.join(versionDir, `${pins.minecraftVersion}.json`),
    clientJar: path.join(versionDir, `${pins.minecraftVersion}.jar`),
    nativesDir: path.join(versionDir, 'natives'),
    librariesDir: path.join(dataDir, 'libraries'),
    assetsDir: path.join(dataDir, 'assets'),
    runtimeDir,
    runtimeManifest: path.join(dataDir, 'runtime', `${runtimeName}.json`),
    javaPath: path.join(runtimeDir, 'bin', 'javaw.exe'),
  };
}

/** 'a/b/c' 처럼 '/' 로 구분된 경로를 base 아래의 경로로 바꾼다. */
function under(base, slashPath) {
  return path.join(base, ...slashPath.split('/'));
}

/** 파일의 sha1 을 스트림으로 계산한다. 파일이 없으면 null. */
async function fileSha1(file) {
  const hash = crypto.createHash('sha1');

  try {
    await pipeline(fs.createReadStream(file), hash);
  } catch {
    return null;
  }

  return hash.digest('hex');
}

/** 다시 시도할 만한 오류인가. WRITE_FAILED, HASH_MISMATCH, UNTRUSTED_URL, 404 같은 다른 HTTP 상태는 아니다. */
function isRetryable(error) {
  return error.code === 'NETWORK' || (error.code === 'HTTP' && (error.details.status === 429 || error.details.status >= 500));
}

/** 고정 해시로 받는다. 일시적인 오류만 잠깐 기다렸다가 다시 시도한다. */
async function downloadVerified(url, destination, sha1, onProgress) {
  for (let attempt = 1; ; attempt += 1) {
    try {
      return await http.download(url, destination, { allowedHosts: DOWNLOAD_HOSTS, hashes: { sha1 }, onProgress });
    } catch (error) {
      if (!isRetryable(error) || attempt >= NETWORK_ATTEMPTS) {
        throw error;
      }

      await new Promise((resolve) => setTimeout(resolve, retryDelayMs * attempt));
    }
  }
}

/** 목록 파일은 매번 디스크 내용의 sha1 을 확인해서, 맞으면 그대로 쓰고 아니면 받는다. */
async function loadMetadata(file, { url, sha1 }) {
  if ((await fileSha1(file)) !== sha1.toLowerCase()) {
    await downloadVerified(url, file, sha1);
  }

  return JSON.parse(await fsp.readFile(file, 'utf8'));
}

/**
 * 목록 파일 3개를 준비하고, 게임에 필요한 파일 전부의 목록을 만든다 (아직 아무것도 확인하거나 받지 않는다).
 *
 * @returns {Promise<{versionJson: object, layout: object, directories: string[],
 *   files: {kind: 'client' | 'library' | 'asset' | 'runtime', file: string, sha1: string, size: number, url: string}[]}>}
 */
async function planGameFiles({ dataDir, pins = DEFAULT_PINS }) {
  const files = layout(dataDir, pins);
  const versionJson = await loadMetadata(files.versionJson, pins.version);
  const assetIndex = await loadMetadata(path.join(files.assetsDir, 'indexes', `${versionJson.assetIndex.id}.json`), versionJson.assetIndex);
  const runtime = await loadMetadata(files.runtimeManifest, { url: pins.runtime.manifestUrl, sha1: pins.runtime.sha1 });

  const client = versionJson.downloads.client;
  const plan = [{ kind: 'client', file: files.clientJar, sha1: client.sha1, size: client.size, url: client.url }];

  for (const library of launchLibraries(versionJson, pins.fabric)) {
    plan.push({ kind: 'library', file: under(files.librariesDir, library.path), sha1: library.sha1, size: library.size, url: library.url });
  }

  const seenAssets = new Set();

  for (const { hash, size } of Object.values(assetIndex.objects)) {
    if (!seenAssets.has(hash)) {
      seenAssets.add(hash);
      const prefix = hash.slice(0, 2);
      plan.push({ kind: 'asset', file: path.join(files.assetsDir, 'objects', prefix, hash), sha1: hash, size, url: `${ASSET_HOST}/${prefix}/${hash}` });
    }
  }

  const directories = [];

  for (const [relative, entry] of Object.entries(runtime.files)) {
    if (entry.type === 'directory') {
      directories.push(under(files.runtimeDir, relative));
    } else if (entry.type === 'file') {
      const { sha1, size, url } = entry.downloads.raw;
      plan.push({ kind: 'runtime', file: under(files.runtimeDir, relative), sha1, size, url });
    }
  }

  return { versionJson, layout: files, directories, files: plan };
}

/** 작업자 8개가 목록에서 하나씩 꺼내 처리한다. 하나라도 실패하면 새 작업을 시작하지 않고, 진행 중인 것이 끝난 뒤 그 오류를 던진다. */
async function runPool(items, worker) {
  let next = 0;
  let failure = null;

  const run = async () => {
    while (!failure && next < items.length) {
      const item = items[next];
      next += 1;

      try {
        await worker(item);
      } catch (error) {
        failure = failure || { error };
      }
    }
  };

  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, items.length) }, run));

  if (failure) {
    throw failure.error;
  }
}

/** 있고 크기가 같으면 통과. repair 면 sha1 까지 같아야 통과. */
async function isIntact(item, repair) {
  try {
    const stat = await fsp.stat(item.file);

    if (!stat.isFile() || stat.size !== item.size) {
      return false;
    }
  } catch {
    return false;
  }

  return !repair || (await fileSha1(item.file)) === item.sha1;
}

/** 공식 런처 폴더의 같은 파일을 해시를 확인하며 복사한다. 원본은 읽기만 한다. 쓸 수 없으면 false. */
async function copyFromSeed(item, dataDir, seedDir) {
  const source = path.join(seedDir, path.relative(dataDir, item.file));

  try {
    const stat = await fsp.stat(source);

    if (!stat.isFile() || stat.size !== item.size) {
      return false;
    }
  } catch {
    return false;
  }

  const temporary = `${item.file}.part`;
  const hash = crypto.createHash('sha1');
  const hashing = new Transform({
    transform(chunk, encoding, callback) {
      hash.update(chunk);
      callback(null, chunk);
    },
  });

  try {
    await fsp.mkdir(path.dirname(item.file), { recursive: true });
    await pipeline(fs.createReadStream(source), hashing, fs.createWriteStream(temporary));

    if (hash.digest('hex') !== item.sha1) {
      await fsp.rm(temporary, { force: true });
      return false;
    }

    await fsp.rename(temporary, item.file);
    return true;
  } catch {
    await fsp.rm(temporary, { force: true });
    return false;
  }
}

const megabytes = (bytes) => (bytes / (1024 * 1024)).toFixed(1);

/**
 * 게임 실행에 필요한 파일을 모두 확인하고, 없거나 다른 파일은 공식 런처 폴더에서 복사하거나 내려받는다.
 *
 * @param {{dataDir: string, seedDir?: string | null, repair?: boolean, onProgress?: (progress: {message: string, fraction: number}) => void, pins?: object}} options
 * @returns {Promise<{versionJson: object, layout: object}>}
 */
async function ensureGameFiles({ dataDir, seedDir = null, repair = false, onProgress = () => {}, pins = DEFAULT_PINS }) {
  let lastFraction = 0;
  let reported = false;
  // 작업자 8개가 동시에 알려도 값이 줄지 않게 하고, 너무 잦은 알림은 걸러 낸다.
  const report = (message, fraction, force = false) => {
    const value = Math.min(1, Math.max(lastFraction, fraction));

    if (force || !reported || value - lastFraction >= 0.001) {
      lastFraction = value;
      reported = true;
      onProgress({ message, fraction: value });
    }
  };

  report(CHECKING_MESSAGE, 0, true);
  const plan = await planGameFiles({ dataDir, pins });
  await Promise.all(plan.directories.map((directory) => fsp.mkdir(directory, { recursive: true })));

  const pending = [];
  let checked = 0;
  await runPool(plan.files, async (item) => {
    if (!(await isIntact(item, repair))) {
      pending.push(item);
    }

    checked += 1;
    report(CHECKING_MESSAGE, (0.05 * checked) / plan.files.length);
  });

  const totalBytes = pending.reduce((sum, item) => sum + item.size, 0);
  const receivedByFile = new Map();
  let receivedBytes = 0;
  // 다시 시도하는 파일은 받은 양이 처음부터 다시 세어지므로, 파일마다 가장 많이 받은 양만 더한다.
  const advance = (item, received) => {
    const capped = Math.min(received, item.size);
    const previous = receivedByFile.get(item) || 0;

    if (capped > previous) {
      receivedByFile.set(item, capped);
      receivedBytes += capped - previous;
      const fraction = totalBytes ? 0.05 + (0.95 * receivedBytes) / totalBytes : 1;
      report(`게임 파일을 내려받는 중... ${megabytes(receivedBytes)} / ${megabytes(totalBytes)}MB`, fraction);
    }
  };

  await runPool(pending, async (item) => {
    const copied = seedDir && item.kind !== 'runtime' && (await copyFromSeed(item, dataDir, seedDir));

    if (!copied) {
      await downloadVerified(item.url, item.file, item.sha1, (received) => advance(item, received));
    }

    advance(item, item.size);
  });

  await fsp.mkdir(plan.layout.nativesDir, { recursive: true });
  report(pending.length ? `게임 파일을 내려받는 중... ${megabytes(totalBytes)} / ${megabytes(totalBytes)}MB` : CHECKING_MESSAGE, 1, true);
  return { versionJson: plan.versionJson, layout: plan.layout };
}

/** 게임 파일이 준비돼 있을 가능성이 높은지 빠르게 본다 (clientJar 와 javaPath 가 둘 다 있으면 true). */
async function quickStatus(dataDir, pins = DEFAULT_PINS) {
  const files = layout(dataDir, pins);
  return (await exists(files.clientJar)) && (await exists(files.javaPath));
}

module.exports = { DEFAULT_PINS, layout, planGameFiles, ensureGameFiles, quickStatus, setRetryDelay };
