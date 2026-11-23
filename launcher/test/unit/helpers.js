'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const AdmZip = require('adm-zip');

/** 테스트가 끝나면 지워지는 임시 폴더를 만든다. */
function tempDir(t, prefix = 'cafeteria-test-') {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return directory;
}

/** fabric.mod.json 이 들어 있는 가짜 모드 jar 를 만든다. metadata 가 null 이면 Fabric 모드가 아닌 jar 가 된다. */
function makeJar(file, metadata) {
  const zip = new AdmZip();

  if (metadata) {
    zip.addFile('fabric.mod.json', Buffer.from(JSON.stringify(metadata)));
  }

  zip.addFile('dummy.txt', Buffer.from('cafeteria test'));
  fs.mkdirSync(path.dirname(file), { recursive: true });
  zip.writeZip(file);
  return file;
}

/**
 * 주소별로 정해 둔 응답을 돌려주는 가짜 fetch.
 * routes: { [url]: { status?, json?, body?, finalUrl? } | (url, options) => 같은 형태 }
 */
function fakeFetch(routes) {
  const calls = [];

  const fetch = async (url, options = {}) => {
    const key = String(url);
    calls.push({ url: key, headers: options.headers || {}, method: options.method || 'GET', body: options.body });
    let route = routes[key];

    if (!route) {
      const prefix = Object.keys(routes).find((candidate) => candidate.endsWith('*') && key.startsWith(candidate.slice(0, -1)));
      route = prefix ? routes[prefix] : null;
    }

    if (typeof route === 'function') {
      route = route(key, options);
    }

    if (!route) {
      throw new Error(`테스트에 등록되지 않은 주소: ${key}`);
    }

    const status = route.status || 200;
    const body = route.body !== undefined ? Buffer.from(route.body) : Buffer.from(JSON.stringify(route.json === undefined ? null : route.json));

    return {
      ok: status >= 200 && status < 300,
      status,
      url: route.finalUrl || key,
      headers: new Headers({ 'content-length': String(body.length) }),
      json: async () => JSON.parse(body.toString('utf8')),
      body: (async function* chunks() {
        // 여러 조각으로 나눠 보내서 이어 받기 처리를 확인한다.
        const middle = Math.floor(body.length / 2);
        yield body.subarray(0, middle);
        yield body.subarray(middle);
      })(),
    };
  };

  fetch.calls = calls;
  return fetch;
}

/** 실제 서버에 접속하는 테스트는 CAFETERIA_OFFLINE=1 일 때 건너뛴다. */
const live = process.env.CAFETERIA_OFFLINE === '1' ? { skip: 'CAFETERIA_OFFLINE=1' } : {};

module.exports = { tempDir, makeJar, fakeFetch, live };
