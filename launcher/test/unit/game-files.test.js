'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { afterEach, test } = require('node:test');
const { DOWNLOAD_HOSTS } = require('../../src/main/constants');
const { ensureGameFiles, layout, planGameFiles, quickStatus } = require('../../src/main/game-files');
const http = require('../../src/main/http');
const { fakeFetch, live, tempDir } = require('./helpers');

afterEach(() => http.setFetch((...args) => globalThis.fetch(...args)));

const sha1 = (content) => crypto.createHash('sha1').update(content).digest('hex');

/** 가짜 Mojang·Fabric 서버. world.routes 를 fakeFetch 에 넘기고, world.pins 를 ensureGameFiles 에 넘긴다. */
function makeWorld({ clientContent = 'client jar', javaContent = 'javaw binary' } = {}) {
  const routes = {};
  const serve = (url, content) => {
    routes[url] = { body: content };
    return { url, sha1: sha1(content), size: Buffer.byteLength(content) };
  };

  const assetA = serve(`https://resources.download.minecraft.net/${sha1('sound a').slice(0, 2)}/${sha1('sound a')}`, 'sound a');
  const assetB = serve(`https://resources.download.minecraft.net/${sha1('lang b').slice(0, 2)}/${sha1('lang b')}`, 'lang b');
  const index = JSON.stringify({
    objects: {
      'minecraft/sounds/a.ogg': { hash: assetA.sha1, size: assetA.size },
      'minecraft/sounds/a-copy.ogg': { hash: assetA.sha1, size: assetA.size },
      'minecraft/lang/b.json': { hash: assetB.sha1, size: assetB.size },
    },
  });
  const assetIndex = serve('https://piston-meta.mojang.com/v1/packages/index/19.json', index);
  const library = serve('https://libraries.minecraft.net/com/example/lib/1.0/lib-1.0.jar', 'vanilla library');
  const macLibrary = serve('https://libraries.minecraft.net/com/example/mac/1.0/mac-1.0.jar', 'mac only');
  const client = serve('https://piston-data.mojang.com/v1/objects/client/client.jar', clientContent);
  const versionJson = JSON.stringify({
    id: '1.21.4',
    assetIndex: { id: '19', sha1: assetIndex.sha1, size: assetIndex.size, url: assetIndex.url },
    downloads: { client: { sha1: client.sha1, size: client.size, url: client.url } },
    libraries: [
      { name: 'com.example:lib:1.0', downloads: { artifact: { path: 'com/example/lib/1.0/lib-1.0.jar', ...library } } },
      { name: 'com.example:mac:1.0', rules: [{ action: 'allow', os: { name: 'osx' } }], downloads: { artifact: { path: 'com/example/mac/1.0/mac-1.0.jar', ...macLibrary } } },
    ],
  });
  const version = serve('https://piston-meta.mojang.com/v1/packages/version/1.21.4.json', versionJson);
  const javaw = serve('https://piston-data.mojang.com/v1/objects/javaw/javaw.exe', javaContent);
  const modules = serve('https://piston-data.mojang.com/v1/objects/modules/modules', 'java modules');
  const runtimeManifest = JSON.stringify({
    files: {
      bin: { type: 'directory' },
      'bin/javaw.exe': { type: 'file', executable: true, downloads: { raw: javaw, lzma: { sha1: 'unused', size: 1, url: 'https://piston-data.mojang.com/unused' } } },
      'lib/modules': { type: 'file', executable: false, downloads: { raw: modules } },
      legal: { type: 'directory' },
    },
  });
  const runtime = serve('https://piston-meta.mojang.com/v1/packages/runtime/manifest.json', runtimeManifest);
  const fabricLibrary = serve('https://maven.fabricmc.net/net/example/loader/0.1/loader-0.1.jar', 'fabric loader');

  return {
    routes,
    pins: {
      minecraftVersion: '1.21.4',
      version: { url: version.url, sha1: version.sha1 },
      runtime: { component: 'java-runtime-delta', version: '21.0.7', manifestUrl: runtime.url, sha1: runtime.sha1 },
      fabric: { mavenUrl: 'https://maven.fabricmc.net/', libraries: [{ name: 'net.example:loader:0.1', sha1: fabricLibrary.sha1, size: fabricLibrary.size }] },
    },
    /** dataDir 기준 상대 경로('/' 구분) → 내용. 받아져야 하는 파일 전부. */
    expected: {
      'versions/1.21.4/1.21.4.json': versionJson,
      'versions/1.21.4/1.21.4.jar': clientContent,
      'libraries/net/example/loader/0.1/loader-0.1.jar': 'fabric loader',
      'libraries/com/example/lib/1.0/lib-1.0.jar': 'vanilla library',
      'assets/indexes/19.json': index,
      [`assets/objects/${assetA.sha1.slice(0, 2)}/${assetA.sha1}`]: 'sound a',
      [`assets/objects/${assetB.sha1.slice(0, 2)}/${assetB.sha1}`]: 'lang b',
      'runtime/java-runtime-delta-21.0.7.json': runtimeManifest,
      'runtime/java-runtime-delta-21.0.7/bin/javaw.exe': javaContent,
      'runtime/java-runtime-delta-21.0.7/lib/modules': 'java modules',
    },
    urls: { client: client.url, library: library.url, assetA: assetA.url, javaw: javaw.url, version: version.url },
  };
}

/** 폴더 안의 모든 파일을 상대 경로('/' 구분) → 내용으로 읽는다. */
function readTree(root) {
  const tree = {};
  const walk = (directory) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const full = path.join(directory, entry.name);

      if (entry.isDirectory()) {
        walk(full);
      } else {
        tree[path.relative(root, full).split(path.sep).join('/')] = fs.readFileSync(full, 'utf8');
      }
    }
  };

  if (fs.existsSync(root)) {
    walk(root);
  }

  return tree;
}

test('처음에는 모든 파일을 받아 제자리에 두고, 임시 파일을 남기지 않는다', async (t) => {
  const world = makeWorld();
  const dataDir = tempDir(t);
  http.setFetch(fakeFetch(world.routes));
  assert.equal(await quickStatus(dataDir, world.pins), false);

  const result = await ensureGameFiles({ dataDir, pins: world.pins });
  assert.deepEqual(readTree(dataDir), world.expected);
  assert.equal(result.versionJson.id, '1.21.4');
  assert.deepEqual(result.layout, layout(dataDir, world.pins));
  assert.equal(result.layout.javaPath, path.join(dataDir, 'runtime', 'java-runtime-delta-21.0.7', 'bin', 'javaw.exe'));
  assert.equal(result.layout.clientJar, path.join(dataDir, 'versions', '1.21.4', '1.21.4.jar'));
  assert.ok(fs.statSync(result.layout.nativesDir).isDirectory());
  assert.ok(fs.statSync(path.join(result.layout.runtimeDir, 'legal')).isDirectory());
  assert.equal(await quickStatus(dataDir, world.pins), true);
});

test('두 번째에는 아무것도 받지 않는다', async (t) => {
  const world = makeWorld();
  const dataDir = tempDir(t);
  http.setFetch(fakeFetch(world.routes));
  await ensureGameFiles({ dataDir, pins: world.pins });

  const fetch = fakeFetch(world.routes);
  http.setFetch(fetch);
  await ensureGameFiles({ dataDir, pins: world.pins });
  assert.deepEqual(fetch.calls, []);
});

test('잘린 파일과 남은 임시 파일이 있어도 그 파일만 다시 받아 복구한다', async (t) => {
  const world = makeWorld();
  const dataDir = tempDir(t);
  http.setFetch(fakeFetch(world.routes));
  const { layout: files } = await ensureGameFiles({ dataDir, pins: world.pins });
  fs.writeFileSync(files.clientJar, 'cli');
  fs.writeFileSync(`${files.clientJar}.part`, '받다 만 조각');
  fs.rmSync(files.javaPath);

  const fetch = fakeFetch(world.routes);
  http.setFetch(fetch);
  await ensureGameFiles({ dataDir, pins: world.pins });
  assert.deepEqual(fetch.calls.map((call) => call.url).sort(), [world.urls.client, world.urls.javaw].sort());
  assert.deepEqual(readTree(dataDir), world.expected);
});

test('크기는 같고 내용이 다른 파일은 설치 복구에서 잡는다', async (t) => {
  const world = makeWorld();
  const dataDir = tempDir(t);
  http.setFetch(fakeFetch(world.routes));
  const { layout: files } = await ensureGameFiles({ dataDir, pins: world.pins });
  fs.writeFileSync(files.clientJar, 'CLIENT JAR');

  let fetch = fakeFetch(world.routes);
  http.setFetch(fetch);
  await ensureGameFiles({ dataDir, pins: world.pins });
  assert.deepEqual(fetch.calls, []);

  fetch = fakeFetch(world.routes);
  http.setFetch(fetch);
  await ensureGameFiles({ dataDir, pins: world.pins, repair: true });
  assert.deepEqual(fetch.calls.map((call) => call.url), [world.urls.client]);
  assert.equal(fs.readFileSync(files.clientJar, 'utf8'), 'client jar');
});

test('고정 해시가 바뀌면 디스크의 예전 목록 파일을 쓰지 않고 새로 받는다', async (t) => {
  const dataDir = tempDir(t);
  const before = makeWorld();
  http.setFetch(fakeFetch(before.routes));
  await ensureGameFiles({ dataDir, pins: before.pins });

  // 런처가 업데이트돼서 client.jar 가 바뀐 새 버전 정보를 가리키게 된 상황
  const after = makeWorld({ clientContent: 'new client jar!!' });
  http.setFetch(fakeFetch(after.routes));
  const result = await ensureGameFiles({ dataDir, pins: after.pins });
  assert.equal(fs.readFileSync(result.layout.versionJson, 'utf8'), after.expected['versions/1.21.4/1.21.4.json']);
  assert.equal(fs.readFileSync(result.layout.clientJar, 'utf8'), 'new client jar!!');
});

test('공식 런처 폴더에 같은 파일이 있고 해시가 맞으면 받지 않고 복사한다', async (t) => {
  const world = makeWorld();
  const dataDir = tempDir(t);
  const seedDir = tempDir(t);
  const seed = (relative, content) => {
    fs.mkdirSync(path.dirname(path.join(seedDir, relative)), { recursive: true });
    fs.writeFileSync(path.join(seedDir, relative), content);
  };
  seed('versions/1.21.4/1.21.4.jar', 'client jar');
  seed('libraries/com/example/lib/1.0/lib-1.0.jar', '크기도 내용도 다른 파일');
  seed('runtime/java-runtime-delta-21.0.7/bin/javaw.exe', 'javaw binary');

  const fetch = fakeFetch(world.routes);
  http.setFetch(fetch);
  await ensureGameFiles({ dataDir, seedDir, pins: world.pins });
  const urls = fetch.calls.map((call) => call.url);
  assert.ok(!urls.includes(world.urls.client), 'client.jar 는 복사한다');
  assert.ok(urls.includes(world.urls.library), '해시가 다른 파일은 받는다');
  assert.ok(urls.includes(world.urls.javaw), 'Java 는 복사 대상이 아니다');
  assert.deepEqual(readTree(dataDir), world.expected);
  assert.equal(fs.readFileSync(path.join(seedDir, 'versions/1.21.4/1.21.4.jar'), 'utf8'), 'client jar', '원본은 그대로 둔다');
});

test('받은 내용의 해시가 다르면 실패하고 파일을 남기지 않는다', async (t) => {
  const world = makeWorld();
  const dataDir = tempDir(t);
  http.setFetch(fakeFetch({ ...world.routes, [world.urls.client]: { body: '바꿔치기한 내용' } }));
  await assert.rejects(ensureGameFiles({ dataDir, pins: world.pins }), { code: 'HASH_MISMATCH' });
  assert.equal(fs.existsSync(layout(dataDir, world.pins).clientJar), false);
});

test('잠깐 끊긴 다운로드는 다시 시도하고, 계속 실패하면 오류를 낸다', async (t) => {
  const world = makeWorld();
  let failures = 2;
  const flaky = () => {
    if (failures > 0) {
      failures -= 1;
      throw new Error('ECONNRESET');
    }

    return world.routes[world.urls.assetA];
  };
  http.setFetch(fakeFetch({ ...world.routes, [world.urls.assetA]: flaky }));
  const dataDir = tempDir(t);
  await ensureGameFiles({ dataDir, pins: world.pins });
  assert.deepEqual(readTree(dataDir), world.expected);

  const always = () => {
    throw new Error('ECONNRESET');
  };
  const fetch = fakeFetch({ ...world.routes, [world.urls.assetA]: always });
  http.setFetch(fetch);
  const otherDir = tempDir(t);
  await assert.rejects(ensureGameFiles({ dataDir: otherDir, pins: world.pins }), { code: 'NETWORK' });
  assert.equal(fetch.calls.filter((call) => call.url === world.urls.assetA).length, 3);
});

test('허용 목록에 없는 주소는 받지 않는다', async (t) => {
  const world = makeWorld();
  const version = JSON.parse(world.expected['versions/1.21.4/1.21.4.json']);
  version.downloads.client.url = 'https://evil.example.com/client.jar';
  const body = JSON.stringify(version);
  const url = 'https://piston-meta.mojang.com/v1/packages/evil/1.21.4.json';
  http.setFetch(fakeFetch({ ...world.routes, [url]: { body } }));
  const pins = { ...world.pins, version: { url, sha1: sha1(body) } };
  await assert.rejects(ensureGameFiles({ dataDir: tempDir(t), pins }), { code: 'UNTRUSTED_URL' });
});

test('진행률은 0 과 1 사이에서 줄어들지 않고 1 로 끝난다', async (t) => {
  const world = makeWorld();
  const dataDir = tempDir(t);
  http.setFetch(fakeFetch(world.routes));

  for (let run = 0; run < 2; run += 1) {
    const fractions = [];
    await ensureGameFiles({ dataDir, pins: world.pins, onProgress: (progress) => fractions.push(progress.fraction) });
    assert.ok(fractions.length > 0);
    assert.ok(fractions.every((fraction, index) => fraction >= 0 && fraction <= 1 && (index === 0 || fraction >= fractions[index - 1])), JSON.stringify(fractions));
    assert.equal(fractions[fractions.length - 1], 1);
  }
});

test('실제 서버: 고정한 목록으로 받을 파일을 만들고 종류별로 하나씩 받아 본다', live, async (t) => {
  // ensureGameFiles 전체(약 630MB)를 돌리지 않는다. 목록 파일 3개만 실제로 받고, 계획을 확인한 뒤 작은 파일을 하나씩 받는다.
  const dataDir = tempDir(t);
  const { files } = await planGameFiles({ dataDir });
  const count = (kind) => files.filter((file) => file.kind === kind).length;
  assert.equal(count('client'), 1);
  assert.ok(count('library') > 60, `library ${count('library')}`);
  assert.ok(count('asset') > 3000, `asset ${count('asset')}`);
  assert.ok(count('runtime') > 300, `runtime ${count('runtime')}`);
  assert.ok(files.every((file) => DOWNLOAD_HOSTS.includes(new URL(file.url).hostname) && /^[0-9a-f]{40}$/.test(file.sha1)));
  assert.ok(files.some((file) => file.file === layout(dataDir).javaPath));

  for (const kind of ['library', 'asset', 'runtime']) {
    const smallest = files.filter((file) => file.kind === kind).sort((a, b) => a.size - b.size)[0];
    await http.download(smallest.url, smallest.file, { allowedHosts: DOWNLOAD_HOSTS, hashes: { sha1: smallest.sha1 } });
    assert.equal(fs.statSync(smallest.file).size, smallest.size);
  }
});
