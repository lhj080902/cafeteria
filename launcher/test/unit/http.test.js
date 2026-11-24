'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { Writable } = require('node:stream');
const { afterEach, test } = require('node:test');
const http = require('../../src/main/http');
const { fakeFetch, tempDir } = require('./helpers');

const HOSTS = ['cdn.modrinth.com'];
const URL_OK = 'https://cdn.modrinth.com/data/x/mod.jar';
const BODY = 'jar 파일 내용이라고 치자';
const SHA1 = crypto.createHash('sha1').update(BODY).digest('hex');
const SHA512 = crypto.createHash('sha512').update(BODY).digest('hex');

afterEach(() => http.setFetch((...args) => globalThis.fetch(...args)));

test('받은 파일의 해시가 맞으면 최종 이름으로 저장한다', async (t) => {
  const destination = path.join(tempDir(t), 'mods', 'mod.jar');
  http.setFetch(fakeFetch({ [URL_OK]: { body: BODY } }));
  const progress = [];
  const result = await http.download(URL_OK, destination, {
    allowedHosts: HOSTS,
    hashes: { sha1: SHA1.toUpperCase(), sha512: SHA512 },
    onProgress: (received, total) => progress.push([received, total]),
  });

  assert.equal(fs.readFileSync(destination, 'utf8'), BODY);
  assert.equal(result.sha1, SHA1);
  assert.deepEqual(progress.at(-1), [Buffer.byteLength(BODY), Buffer.byteLength(BODY)]);
  assert.deepEqual(fs.readdirSync(path.dirname(destination)), ['mod.jar'], '임시 파일이 남지 않는다');
});

test('해시가 다르면 실패하고 파일을 남기지 않는다', async (t) => {
  const directory = tempDir(t);
  http.setFetch(fakeFetch({ [URL_OK]: { body: BODY } }));

  for (const hashes of [{ sha1: 'a'.repeat(40) }, { sha512: 'b'.repeat(128) }, { sha1: SHA1, sha512: 'b'.repeat(128) }]) {
    await assert.rejects(http.download(URL_OK, path.join(directory, 'mod.jar'), { allowedHosts: HOSTS, hashes }), { code: 'HASH_MISMATCH' });
  }

  assert.deepEqual(fs.readdirSync(directory), []);
});

test('허용 목록에 없는 주소에서는 받지 않는다', async (t) => {
  const destination = path.join(tempDir(t), 'mod.jar');
  const fetch = fakeFetch({});
  http.setFetch(fetch);

  for (const url of ['https://evil.example.com/mod.jar', 'http://cdn.modrinth.com/mod.jar', 'https://cdn.modrinth.com.evil.example/mod.jar', 'file:///etc/passwd']) {
    await assert.rejects(http.download(url, destination, { allowedHosts: HOSTS }), { code: 'UNTRUSTED_URL' });
  }

  assert.equal(fetch.calls.length, 0, '요청 자체를 보내지 않는다');
});

test('허용되지 않은 주소로 리다이렉트되면 중단한다', async (t) => {
  const directory = tempDir(t);
  http.setFetch(fakeFetch({ [URL_OK]: { body: BODY, finalUrl: 'https://evil.example.com/mod.jar' } }));
  await assert.rejects(http.download(URL_OK, path.join(directory, 'mod.jar'), { allowedHosts: HOSTS }), { code: 'UNTRUSTED_URL' });
  assert.deepEqual(fs.readdirSync(directory), []);
});

test('서버 오류와 연결 실패는 안내 오류로 바뀐다', async (t) => {
  const destination = path.join(tempDir(t), 'mod.jar');
  http.setFetch(fakeFetch({ [URL_OK]: { status: 404, body: 'not found' } }));
  await assert.rejects(http.download(URL_OK, destination, { allowedHosts: HOSTS }), { code: 'HTTP' });
  await assert.rejects(http.getJson(URL_OK), (error) => error.code === 'HTTP' && error.details.status === 404);

  http.setFetch(async () => {
    throw new Error('connect ECONNREFUSED');
  });
  await assert.rejects(http.getJson(URL_OK), { code: 'NETWORK' });
});

test('임시 파일(.part)을 열 수 없으면 WRITE_FAILED 로 실패하고 멈추지 않는다', async (t) => {
  const directory = tempDir(t);
  const destination = path.join(directory, 'mod.jar');
  // 같은 이름의 폴더가 있으면 .part 파일을 열 수 없다.
  fs.mkdirSync(`${destination}.part`);
  http.setFetch(fakeFetch({ [URL_OK]: { body: BODY } }));

  await assert.rejects(http.download(URL_OK, destination, { allowedHosts: HOSTS, hashes: { sha1: SHA1 } }), (error) => {
    assert.equal(error.code, 'WRITE_FAILED');
    assert.equal(error.message, '파일을 저장하지 못했습니다. 디스크 공간과 폴더 권한을 확인한 뒤 다시 시도해 주세요.');
    assert.ok(error.details.cause);
    return true;
  });

  assert.ok(fs.statSync(`${destination}.part`).isDirectory(), '함수가 .part 파일을 남기지 않는다');
  assert.equal(fs.existsSync(destination), false);
});

test('쓰는 도중에 디스크 오류가 나면 WRITE_FAILED 로 실패하고 멈추지 않는다', async (t) => {
  const directory = tempDir(t);
  const destination = path.join(directory, 'mod.jar');
  const original = fs.createWriteStream;
  t.after(() => {
    fs.createWriteStream = original;
  });
  // 디스크가 가득 찬 것처럼, 열린 뒤 첫 쓰기에서 ENOSPC 를 낸다. 버퍼를 작게 해서 drain 을 기다리게 만든다.
  fs.createWriteStream = () => new Writable({
    highWaterMark: 1,
    write(chunk, encoding, callback) {
      callback(Object.assign(new Error('no space left on device'), { code: 'ENOSPC' }));
    },
  });
  http.setFetch(fakeFetch({ [URL_OK]: { body: BODY } }));

  await assert.rejects(http.download(URL_OK, destination, { allowedHosts: HOSTS }), (error) => error.code === 'WRITE_FAILED' && error.details.cause === 'ENOSPC');
  assert.equal(fs.existsSync(destination), false);
});

test('최종 이름으로 바꾸지 못하면 WRITE_FAILED 로 실패하고 .part 파일을 지운다', async (t) => {
  const directory = tempDir(t);
  const destination = path.join(directory, 'mod.jar');
  // 비어 있지 않은 폴더가 최종 이름을 차지하고 있으면 이름을 바꿀 수 없다.
  fs.mkdirSync(path.join(destination, 'inner'), { recursive: true });
  http.setFetch(fakeFetch({ [URL_OK]: { body: BODY } }));

  await assert.rejects(http.download(URL_OK, destination, { allowedHosts: HOSTS, hashes: { sha1: SHA1 } }), { code: 'WRITE_FAILED' });
  assert.equal(fs.existsSync(`${destination}.part`), false);
});

test('본문을 받다가 끊기면 NETWORK 오류다', async (t) => {
  const directory = tempDir(t);
  http.setFetch(async (url) => ({
    ok: true,
    status: 200,
    url: String(url),
    headers: new Headers({ 'content-length': '10' }),
    body: (async function* chunks() {
      yield Buffer.from('abc');
      throw new Error('socket hang up');
    })(),
  }));

  await assert.rejects(http.download(URL_OK, path.join(directory, 'mod.jar'), { allowedHosts: HOSTS }), { code: 'NETWORK' });
  assert.deepEqual(fs.readdirSync(directory), []);
});

test('JSON 이 아닌 응답은 안내 오류', async () => {
  http.setFetch(fakeFetch({ [URL_OK]: { body: '<html>점검 중</html>' } }));
  await assert.rejects(http.getJson(URL_OK), { code: 'BAD_RESPONSE' });
});

test('요청마다 런처 User-Agent 를 붙인다', async () => {
  const fetch = fakeFetch({ [URL_OK]: { json: { ok: true } } });
  http.setFetch(fetch);
  assert.deepEqual(await http.getJson(URL_OK, { headers: { 'x-api-key': 'k' } }), { ok: true });
  assert.equal(fetch.calls[0].headers['User-Agent'], 'CafeteriaLauncher/1.3.0');
  assert.equal(fetch.calls[0].headers['x-api-key'], 'k');
});

test('postJson 은 JSON 본문을 POST 로 보낸다', async () => {
  const fetch = fakeFetch({ [URL_OK]: { json: { found: 1 } } });
  http.setFetch(fetch);
  assert.deepEqual(await http.postJson(URL_OK, { hashes: ['a'], algorithm: 'sha1' }), { found: 1 });
  assert.equal(fetch.calls[0].method, 'POST');
  assert.equal(fetch.calls[0].body, '{"hashes":["a"],"algorithm":"sha1"}');
  assert.equal(fetch.calls[0].headers['Content-Type'], 'application/json');
  assert.equal(fetch.calls[0].headers['User-Agent'], 'CafeteriaLauncher/1.3.0');
});

test('sendJson 은 2xx 가 아니어도 상태 코드와 본문을 돌려준다', async () => {
  const fetch = fakeFetch({
    'https://auth.example.com/token': { status: 400, json: { error: 'invalid_grant' } },
    'https://auth.example.com/text': { status: 502, body: 'Bad Gateway' },
  });
  http.setFetch(fetch);

  assert.deepEqual(await http.sendJson('https://auth.example.com/token', { method: 'POST', form: { a: '1', b: 'x y' } }), { status: 400, body: { error: 'invalid_grant' } });
  assert.equal(fetch.calls[0].method, 'POST');
  assert.equal(fetch.calls[0].body, 'a=1&b=x+y');
  assert.equal(fetch.calls[0].headers['Content-Type'], 'application/x-www-form-urlencoded');
  assert.equal(fetch.calls[0].headers.Accept, 'application/json');

  assert.deepEqual(await http.sendJson('https://auth.example.com/text', { method: 'POST', json: { k: 1 } }), { status: 502, body: null });
  assert.equal(fetch.calls[1].body, '{"k":1}');
  assert.equal(fetch.calls[1].headers['Content-Type'], 'application/json');
});

test('sendJson 은 연결에 실패하면 NETWORK 오류를 낸다', async () => {
  http.setFetch(async () => {
    throw new Error('offline');
  });
  await assert.rejects(http.sendJson('https://auth.example.com/token'), { code: 'NETWORK' });
});
