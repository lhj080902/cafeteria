'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { afterEach, test } = require('node:test');
const http = require('../../src/main/http');
const { installMod } = require('../../src/main/mod-installer');
const { ModManager } = require('../../src/main/mods');
const modrinth = require('../../src/main/sources/modrinth');
const { fakeFetch, live, tempDir } = require('./helpers');

const SODIUM = 'AANobbMI';
const SODIUM_EXTRA = 'PtjYWJkn';

afterEach(() => http.setFetch((...args) => globalThis.fetch(...args)));

test('[실제 서버] 검색 결과에 화면에 필요한 정보가 들어 있다', live, async () => {
  const result = await modrinth.search('sodium', { limit: 5 });
  assert.ok(result.total >= 1);
  const sodium = result.items.find((item) => item.id === SODIUM);
  assert.ok(sodium, '검색 결과에 Sodium 이 있어야 한다');
  assert.equal(sodium.source, 'modrinth');
  assert.equal(sodium.title, 'Sodium');
  assert.ok(sodium.description.length > 0);
  assert.ok(sodium.downloads > 1_000_000);
  assert.match(sodium.iconUrl, /^https:\/\/cdn\.modrinth\.com\//);
  assert.equal(sodium.pageUrl, 'https://modrinth.com/mod/sodium');
});

test('[실제 서버] 검색어가 없으면 인기순으로 나오고 다음 페이지를 받을 수 있다', live, async () => {
  const first = await modrinth.search('', { limit: 5 });
  const second = await modrinth.search('', { limit: 5, offset: 5 });
  assert.equal(first.items.length, 5);
  assert.equal(second.items.length, 5);
  assert.ok(first.items[0].downloads >= first.items[4].downloads);
  assert.equal(first.items.some((item) => second.items.some((other) => other.id === item.id)), false, '페이지가 겹치지 않는다');
});

test('[실제 서버] 1.21.4 Fabric 용 파일을 고른다', live, async () => {
  const file = await modrinth.resolveFile(SODIUM);
  assert.equal(file.projectId, SODIUM);
  assert.equal(file.title, 'Sodium');
  assert.match(file.fileName, /^sodium-fabric-.*1\.21\.4.*\.jar$/);
  assert.match(file.url, /^https:\/\/cdn\.modrinth\.com\//);
  assert.match(file.hashes.sha512, /^[0-9a-f]{128}$/);
  assert.deepEqual(file.dependencies, []);
});

test('[실제 서버] 필수 의존성까지 함께 내려받고, 다시 설치하려 하면 알려 준다', live, async (t) => {
  const manager = new ModManager(tempDir(t));
  const progress = [];
  const result = await installMod({
    source: 'modrinth',
    projectId: SODIUM_EXTRA,
    resolveFile: modrinth.resolveFile,
    modManager: manager,
    onProgress: (update) => progress.push(update),
  });

  assert.deepEqual(result.installed, ['Sodium Extra', 'Sodium']);
  assert.deepEqual(result.warnings, []);
  const mods = await manager.list();
  assert.deepEqual(mods.map((mod) => mod.modId).sort(), ['sodium', 'sodium-extra']);
  assert.ok(mods.every((mod) => mod.source === 'modrinth' && mod.enabled && mod.size > 10_000));
  assert.deepEqual([...(await manager.installedProjects('modrinth'))].sort(), [SODIUM, SODIUM_EXTRA].sort());
  assert.ok(progress.some((update) => update.fraction === 1));
  assert.deepEqual(fs.readdirSync(manager.modsDir).filter((name) => name.endsWith('.part')), []);

  await assert.rejects(installMod({ source: 'modrinth', projectId: SODIUM_EXTRA, resolveFile: modrinth.resolveFile, modManager: manager }), {
    code: 'ALREADY_INSTALLED',
  });
});

test('[실제 서버] Fabric API 를 요구하는 모드를 받아도 Fabric API 는 받지 않는다 (런처가 넣어 준다)', live, async (t) => {
  const manager = new ModManager(tempDir(t));
  const file = await modrinth.resolveFile('mOgUt4GM');
  assert.ok(file.dependencies.includes('P7dR8mSH'), 'Mod Menu 는 Fabric API 를 필수로 요구한다');

  const result = await installMod({ source: 'modrinth', projectId: 'mOgUt4GM', resolveFile: modrinth.resolveFile, modManager: manager });
  assert.equal(result.installed[0], 'Mod Menu');
  assert.equal(result.installed.includes('Fabric API'), false);
  const mods = await manager.list();
  assert.ok(mods.some((mod) => mod.modId === 'modmenu'));
  assert.equal(mods.some((mod) => mod.modId === 'fabric-api'), false);
  assert.equal(mods.length, result.installed.length, '설치했다고 알린 모드와 실제 파일 수가 같다');
});

test('1.21.4 Fabric 용 파일이 없는 모드는 안내 오류', async () => {
  http.setFetch(
    fakeFetch({
      'https://api.modrinth.com/v2/project/old-mod': { json: { id: 'OLD', title: '옛날 모드' } },
      'https://api.modrinth.com/v2/project/old-mod/version*': { json: [] },
    }),
  );
  await assert.rejects(modrinth.resolveFile('old-mod'), (error) => error.code === 'NO_COMPATIBLE_FILE' && error.message.includes('옛날 모드'));
});

test('정식 버전을 베타보다 우선하고, 버전만 지정된 의존성은 프로젝트를 찾아 넣는다', async () => {
  const fetch = fakeFetch({
    'https://api.modrinth.com/v2/project/P1': { json: { id: 'P1', title: '모드' } },
    'https://api.modrinth.com/v2/project/P1/version*': {
      json: [
        { id: 'v-beta', version_number: '2.0-beta', version_type: 'beta', files: [{ filename: 'beta.jar', url: 'https://cdn.modrinth.com/beta.jar', hashes: {} }] },
        {
          id: 'v-release',
          version_number: '1.9',
          version_type: 'release',
          files: [
            { filename: 'sources.jar', url: 'https://cdn.modrinth.com/sources.jar', primary: false, hashes: {} },
            { filename: 'main.jar', url: 'https://cdn.modrinth.com/main.jar', primary: true, hashes: { sha1: 'a'.repeat(40), sha512: 'b'.repeat(128) } },
          ],
          dependencies: [
            { project_id: 'DEP1', dependency_type: 'required' },
            { project_id: 'OPT', dependency_type: 'optional' },
            { project_id: 'BAD', dependency_type: 'incompatible' },
            { project_id: null, version_id: 'dep-version', dependency_type: 'required' },
            { project_id: 'DEP1', dependency_type: 'required' },
          ],
        },
      ],
    },
    'https://api.modrinth.com/v2/version/dep-version': { json: { project_id: 'DEP2' } },
  });
  http.setFetch(fetch);

  const file = await modrinth.resolveFile('P1');
  assert.equal(file.versionId, 'v-release');
  assert.equal(file.fileName, 'main.jar');
  assert.deepEqual(file.dependencies, ['DEP1', 'DEP2']);
  const versionsCall = fetch.calls.find((call) => call.url.includes('/version?'));
  assert.equal(new URL(versionsCall.url).searchParams.get('loaders'), '["fabric"]');
  assert.equal(new URL(versionsCall.url).searchParams.get('game_versions'), '["1.21.4"]');
});
