'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
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

test('JSON 이 아닌 응답은 안내 오류', async () => {
  http.setFetch(fakeFetch({ [URL_OK]: { body: '<html>점검 중</html>' } }));
  await assert.rejects(http.getJson(URL_OK), { code: 'BAD_RESPONSE' });
});

test('요청마다 런처 User-Agent 를 붙인다', async () => {
  const fetch = fakeFetch({ [URL_OK]: { json: { ok: true } } });
  http.setFetch(fetch);
  assert.deepEqual(await http.getJson(URL_OK, { headers: { 'x-api-key': 'k' } }), { ok: true });
  assert.equal(fetch.calls[0].headers['User-Agent'], 'CafeteriaLauncher/1.2.2');
  assert.equal(fetch.calls[0].headers['x-api-key'], 'k');
});

test('postJson 은 JSON 본문을 POST 로 보낸다', async () => {
  const fetch = fakeFetch({ [URL_OK]: { json: { found: 1 } } });
  http.setFetch(fetch);
  assert.deepEqual(await http.postJson(URL_OK, { hashes: ['a'], algorithm: 'sha1' }), { found: 1 });
  assert.equal(fetch.calls[0].method, 'POST');
  assert.equal(fetch.calls[0].body, '{"hashes":["a"],"algorithm":"sha1"}');
  assert.equal(fetch.calls[0].headers['Content-Type'], 'application/json');
  assert.equal(fetch.calls[0].headers['User-Agent'], 'CafeteriaLauncher/1.2.2');
});
