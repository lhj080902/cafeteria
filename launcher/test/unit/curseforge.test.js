'use strict';

/*
 * CurseForge API 는 키가 있어야 호출할 수 있어서, 공식 문서의 응답 형식대로 만든 가짜 응답으로 검사한다.
 * (실제 서버를 상대로 한 검사는 아니다.)
 */
const assert = require('node:assert/strict');
const { afterEach, test } = require('node:test');
const http = require('../../src/main/http');
const curseforge = require('../../src/main/sources/curseforge');
const { fakeFetch } = require('./helpers');

const KEY = 'test-api-key';
const SHA1 = 'c'.repeat(40);

afterEach(() => http.setFetch((...args) => globalThis.fetch(...args)));

function file(overrides) {
  return {
    id: 1,
    isAvailable: true,
    displayName: 'Mod 1.0',
    fileName: 'mod-1.0.jar',
    releaseType: 1,
    fileDate: '2025-01-01T00:00:00Z',
    downloadUrl: 'https://edge.forgecdn.net/files/1/2/mod-1.0.jar',
    hashes: [{ value: 'd'.repeat(32), algo: 2 }, { value: SHA1, algo: 1 }],
    dependencies: [],
    ...overrides,
  };
}

test('검색: 1.21.4 Fabric 조건과 API 키를 붙여 요청하고 결과를 화면용으로 바꾼다', async () => {
  const fetch = fakeFetch({
    'https://api.curseforge.com/v1/mods/search*': {
      json: {
        data: [
          {
            id: 238222,
            name: 'Just Enough Items',
            slug: 'jei',
            summary: '아이템과 조합법 보기',
            downloadCount: 123456789,
            authors: [{ name: 'mezz' }],
            logo: { thumbnailUrl: 'https://media.forgecdn.net/avatars/thumbnails/1.png', url: 'https://media.forgecdn.net/avatars/1.png' },
            links: { websiteUrl: 'https://www.curseforge.com/minecraft/mc-mods/jei' },
          },
          { id: 5, name: '로고 없는 모드', slug: 'no-logo' },
        ],
        pagination: { index: 20, pageSize: 20, resultCount: 2, totalCount: 57 },
      },
    },
  });
  http.setFetch(fetch);

  const result = await curseforge.search('jei', { offset: 20, limit: 20 }, KEY);
  assert.equal(result.total, 57);
  assert.deepEqual(result.items[0], {
    source: 'curseforge',
    id: '238222',
    title: 'Just Enough Items',
    description: '아이템과 조합법 보기',
    author: 'mezz',
    downloads: 123456789,
    iconUrl: 'https://media.forgecdn.net/avatars/thumbnails/1.png',
    pageUrl: 'https://www.curseforge.com/minecraft/mc-mods/jei',
  });
  assert.deepEqual(result.items[1], {
    source: 'curseforge',
    id: '5',
    title: '로고 없는 모드',
    description: '',
    author: '',
    downloads: 0,
    iconUrl: null,
    pageUrl: 'https://www.curseforge.com/minecraft/mc-mods/no-logo',
  });

  const call = fetch.calls[0];
  const params = new URL(call.url).searchParams;
  assert.equal(call.headers['x-api-key'], KEY);
  assert.deepEqual(Object.fromEntries(params), {
    gameId: '432',
    classId: '6',
    gameVersion: '1.21.4',
    modLoaderType: '4',
    sortField: '2',
    sortOrder: 'desc',
    index: '20',
    pageSize: '20',
    searchFilter: 'jei',
  });
});

test('검색어가 없으면 searchFilter 를 보내지 않는다', async () => {
  const fetch = fakeFetch({ 'https://api.curseforge.com/v1/mods/search*': { json: { data: [], pagination: { totalCount: 0 } } } });
  http.setFetch(fetch);
  assert.deepEqual(await curseforge.search('', {}, KEY), { total: 0, items: [] });
  assert.equal(new URL(fetch.calls[0].url).searchParams.has('searchFilter'), false);
});

test('키가 없으면 요청을 보내지 않고 안내한다', async () => {
  const fetch = fakeFetch({});
  http.setFetch(fetch);
  await assert.rejects(curseforge.search('jei', {}, ''), { code: 'NO_CURSEFORGE_KEY' });
  await assert.rejects(curseforge.resolveFile('1', undefined), { code: 'NO_CURSEFORGE_KEY' });
  assert.equal(fetch.calls.length, 0);
});

test('키가 거부되면(401/403) 키를 확인하라고 안내한다', async () => {
  for (const status of [401, 403]) {
    http.setFetch(fakeFetch({ 'https://api.curseforge.com/v1/mods/search*': { status, body: 'Forbidden' } }));
    await assert.rejects(curseforge.search('jei', {}, KEY), { code: 'BAD_CURSEFORGE_KEY' });
  }

  http.setFetch(fakeFetch({ 'https://api.curseforge.com/v1/mods/search*': { status: 500, body: 'error' } }));
  await assert.rejects(curseforge.search('jei', {}, KEY), { code: 'HTTP' });
});

test('파일 선택: 받을 수 있는 jar 중 정식 버전의 최신 파일을 고르고 필수 의존성만 남긴다', async () => {
  const fetch = fakeFetch({
    'https://api.curseforge.com/v1/mods/100': { json: { data: { id: 100, name: '테스트 모드', links: { websiteUrl: 'https://www.curseforge.com/minecraft/mc-mods/test' } } } },
    'https://api.curseforge.com/v1/mods/100/files*': {
      json: {
        data: [
          file({ id: 11, fileName: 'mod-1.0.jar', fileDate: '2025-01-01T00:00:00Z' }),
          file({ id: 14, fileName: 'mod-2.0-beta.jar', releaseType: 2, fileDate: '2025-04-01T00:00:00Z' }),
          file({
            id: 12,
            fileName: 'mod-1.1.jar',
            displayName: 'Mod 1.1',
            fileDate: '2025-02-01T00:00:00Z',
            dependencies: [
              { modId: 306612, relationType: 3 },
              { modId: 777, relationType: 3 },
              { modId: 888, relationType: 2 },
              { modId: 999, relationType: 5 },
              { modId: 777, relationType: 3 },
            ],
          }),
          file({ id: 13, fileName: 'mod-1.2.jar', fileDate: '2025-03-01T00:00:00Z', isAvailable: false }),
          file({ id: 15, fileName: 'mod-1.3-sources.zip', fileDate: '2025-03-15T00:00:00Z' }),
        ],
      },
    },
  });
  http.setFetch(fetch);

  assert.deepEqual(await curseforge.resolveFile('100', KEY), {
    projectId: '100',
    title: '테스트 모드',
    versionId: '12',
    version: 'Mod 1.1',
    fileName: 'mod-1.1.jar',
    url: 'https://edge.forgecdn.net/files/1/2/mod-1.0.jar',
    hashes: { sha1: SHA1 },
    dependencies: ['306612', '777'],
  });

  const filesCall = fetch.calls.find((call) => call.url.includes('/files?'));
  assert.deepEqual(Object.fromEntries(new URL(filesCall.url).searchParams), { gameVersion: '1.21.4', modLoaderType: '4', pageSize: '50' });
  assert.ok(fetch.calls.every((call) => call.headers['x-api-key'] === KEY));
});

test('정식 버전이 없으면 가장 최근 파일을 쓴다', async () => {
  http.setFetch(
    fakeFetch({
      'https://api.curseforge.com/v1/mods/100': { json: { data: { id: 100, name: '베타뿐인 모드' } } },
      'https://api.curseforge.com/v1/mods/100/files*': {
        json: { data: [file({ id: 1, releaseType: 3, fileDate: '2025-01-01T00:00:00Z' }), file({ id: 2, releaseType: 2, fileDate: '2025-05-01T00:00:00Z' })] },
      },
    }),
  );
  assert.equal((await curseforge.resolveFile('100', KEY)).versionId, '2');
});

test('제작자가 외부 다운로드를 막은 모드는 직접 받으라고 안내한다', async () => {
  http.setFetch(
    fakeFetch({
      'https://api.curseforge.com/v1/mods/100': { json: { data: { id: 100, name: '잠긴 모드', links: { websiteUrl: 'https://www.curseforge.com/minecraft/mc-mods/locked' } } } },
      'https://api.curseforge.com/v1/mods/100/files*': { json: { data: [file({ downloadUrl: null })] } },
    }),
  );
  await assert.rejects(
    curseforge.resolveFile('100', KEY),
    (error) => error.code === 'DISTRIBUTION_BLOCKED' && error.message.includes('잠긴 모드') && error.details.pageUrl.endsWith('/locked'),
  );
});

test('1.21.4 Fabric 용 파일이 없으면 안내 오류', async () => {
  http.setFetch(
    fakeFetch({
      'https://api.curseforge.com/v1/mods/100': { json: { data: { id: 100, name: '옛날 모드' } } },
      'https://api.curseforge.com/v1/mods/100/files*': { json: { data: [] } },
    }),
  );
  await assert.rejects(curseforge.resolveFile('100', KEY), { code: 'NO_COMPATIBLE_FILE' });
});
