'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { afterEach, test } = require('node:test');
const http = require('../../src/main/http');
const { applyModUpdate, checkModUpdates } = require('../../src/main/mod-updater');
const { ModManager } = require('../../src/main/mods');
const modrinth = require('../../src/main/sources/modrinth');
const { fakeFetch, live, makeJar, tempDir } = require('./helpers');

afterEach(() => http.setFetch((...args) => globalThis.fetch(...args)));

const MODRINTH = 'https://api.modrinth.com/v2';
const CURSEFORGE = 'https://api.curseforge.com';
const sha1 = (data) => crypto.createHash('sha1').update(data).digest('hex');
const sha1OfFile = (file) => sha1(fs.readFileSync(file));

/** Modrinth 버전 정보 한 건. */
function version(id, type, date, { fileName = `${id}.jar`, body = `${id} 내용`, dependencies = [] } = {}) {
  return {
    id,
    version_number: id,
    version_type: type,
    date_published: date,
    dependencies,
    files: [{ primary: true, filename: fileName, url: `https://cdn.modrinth.com/${fileName}`, hashes: { sha1: sha1(body) } }],
  };
}

/**
 * 모드 다섯 개가 들어 있는 게임 폴더를 만든다.
 *  - 런처로 Modrinth 에서 받은 모드 (정식 버전 사용 중)
 *  - 직접 넣었지만 Modrinth 에 올라와 있는 모드 (베타 사용 중)
 *  - 직접 만든 모드 (어느 사이트에도 없음)
 *  - 런처로 CurseForge 에서 받은 모드
 *  - 기본 모드 (Cafeteria)
 */
async function setup(t) {
  const manager = new ModManager(tempDir(t));
  const mods = manager.modsDir;
  const files = {
    released: makeJar(path.join(mods, 'released-1.0.jar'), { id: 'released', name: '정식 모드', version: '1.0' }),
    beta: makeJar(path.join(mods, 'beta-2.0-beta1.jar'), { id: 'betamod', name: '베타 모드', version: '2.0-beta1' }),
    mine: makeJar(path.join(mods, 'mine-0.3.jar'), { id: 'mine', name: '내가 만든 모드', version: '0.3' }),
    forge: makeJar(path.join(mods, 'cf-mod-1.jar'), { id: 'cfmod', name: '커스포지 모드', version: '1' }),
    builtin: makeJar(path.join(mods, 'cafeteria-client-1.2.0.jar'), { id: 'cafeteria', name: 'Cafeteria Client', version: '1.2.0' }),
  };
  await manager.record('released-1.0.jar', { source: 'modrinth', projectId: 'P1', versionId: 'r1', title: '정식 모드' });
  await manager.record('beta-2.0-beta1.jar', { source: 'local' });
  await manager.record('mine-0.3.jar', { source: 'local' });
  await manager.record('cf-mod-1.jar', { source: 'curseforge', projectId: '111', versionId: '1000', title: '커스포지 모드' });

  const known = {
    [sha1OfFile(files.released)]: { ...version('r1', 'release', '2026-01-01T00:00:00Z'), project_id: 'P1' },
    [sha1OfFile(files.beta)]: { ...version('b1', 'beta', '2026-02-01T00:00:00Z'), project_id: 'P2' },
  };
  const routes = {
    [`${MODRINTH}/version_files`]: (url, options) => {
      const { hashes } = JSON.parse(options.body);
      return { json: Object.fromEntries(hashes.filter((hash) => known[hash]).map((hash) => [hash, known[hash]])) };
    },
    // 정식 버전을 쓰는 중: 더 새로운 베타(r3-beta)가 있어도 정식 버전(r2)으로만 올린다.
    [`${MODRINTH}/project/P1/version*`]: {
      json: [
        version('r3-beta', 'beta', '2026-05-01T00:00:00Z'),
        version('r2', 'release', '2026-04-01T00:00:00Z', { dependencies: [{ dependency_type: 'required', project_id: 'LIB' }] }),
        version('r1', 'release', '2026-01-01T00:00:00Z'),
      ],
    },
    // 베타를 쓰는 중: 더 새로운 정식 버전이 없으면 더 새로운 베타(b2)로 올린다.
    [`${MODRINTH}/project/P2/version*`]: {
      json: [version('b2', 'beta', '2026-03-01T00:00:00Z'), version('b1', 'beta', '2026-02-01T00:00:00Z'), version('old-release', 'release', '2025-12-01T00:00:00Z')],
    },
    [`${CURSEFORGE}/v1/mods/111`]: { json: { data: { id: 111, name: '커스포지 모드' } } },
    [`${CURSEFORGE}/v1/mods/111/files*`]: {
      json: {
        data: [
          { id: 2000, fileName: 'cf-mod-2.jar', displayName: '커스포지 모드 2', releaseType: 1, fileDate: '2026-04-01T00:00:00Z', downloadUrl: 'https://edge.forgecdn.net/cf-mod-2.jar', hashes: [], dependencies: [] },
          { id: 1000, fileName: 'cf-mod-1.jar', displayName: '커스포지 모드 1', releaseType: 1, fileDate: '2026-01-01T00:00:00Z', downloadUrl: 'https://edge.forgecdn.net/cf-mod-1.jar', hashes: [], dependencies: [] },
        ],
      },
    },
    'https://cdn.modrinth.com/r2.jar': { body: 'r2 내용' },
    'https://cdn.modrinth.com/b2.jar': { body: 'b2 내용' },
    'https://cdn.modrinth.com/LIB.jar': { body: 'LIB 내용' },
    'https://edge.forgecdn.net/cf-mod-2.jar': { body: 'cf 2 내용' },
  };
  const fetch = fakeFetch(routes);
  http.setFetch(fetch);
  return { manager, files, fetch, routes };
}

/** 필수 모드를 받을 때 쓰는 가짜 사이트 함수. */
const resolveLibrary = async (id) => ({
  projectId: id,
  title: `라이브러리 ${id}`,
  versionId: `${id}-1`,
  fileName: `${id}.jar`,
  url: `https://cdn.modrinth.com/${id}.jar`,
  hashes: { sha1: sha1(`${id} 내용`) },
  dependencies: [],
});

test('받은 모드와 직접 넣은 모드의 새 버전을 찾고, 사이트에 없는 모드와 기본 모드는 따로 구분한다', async (t) => {
  const { manager } = await setup(t);
  const result = await checkModUpdates({ modManager: manager, curseforgeKey: 'test-key' });

  assert.deepEqual(
    result.updates.map((update) => [update.name, update.source, update.currentVersion, update.newVersion, update.file.fileName]),
    [
      ['베타 모드', 'modrinth', '2.0-beta1', 'b2', 'b2.jar'],
      ['정식 모드', 'modrinth', '1.0', 'r2', 'r2.jar'],
      ['커스포지 모드', 'curseforge', '1', '커스포지 모드 2', 'cf-mod-2.jar'],
    ],
  );
  assert.equal(result.checked, 3);
  assert.deepEqual(result.unknown, ['내가 만든 모드']);
  assert.deepEqual(result.notes, []);
});

test('CurseForge 키가 없으면 CurseForge 에서 받은 모드는 확인하지 못했다고 알려 준다', async (t) => {
  const { manager, fetch } = await setup(t);
  const result = await checkModUpdates({ modManager: manager });

  assert.deepEqual(result.updates.map((update) => update.name), ['베타 모드', '정식 모드']);
  assert.equal(result.notes.length, 1);
  assert.match(result.notes[0], /커스포지 모드.*API 키/);
  assert.ok(!fetch.calls.some((call) => call.url.startsWith(CURSEFORGE)), '키 없이 CurseForge 를 호출하면 안 된다');
});

test('이미 최신이면 업데이트 목록이 비어 있다', async (t) => {
  const { manager, routes } = await setup(t);
  routes[`${MODRINTH}/project/P1/version*`] = { json: [version('r1', 'release', '2026-01-01T00:00:00Z')] };
  routes[`${MODRINTH}/project/P2/version*`] = { json: [version('b1', 'beta', '2026-02-01T00:00:00Z')] };
  routes[`${CURSEFORGE}/v1/mods/111/files*`].json.data.shift();
  const result = await checkModUpdates({ modManager: manager, curseforgeKey: 'test-key' });

  assert.deepEqual(result.updates, []);
  assert.equal(result.checked, 3);
});

test('한 모드의 확인이 실패해도 나머지는 계속 확인한다', async (t) => {
  const { manager, routes } = await setup(t);
  routes[`${MODRINTH}/project/P1/version*`] = { status: 500 };
  const result = await checkModUpdates({ modManager: manager });

  assert.deepEqual(result.updates.map((update) => update.name), ['베타 모드']);
  assert.ok(result.notes.some((note) => note.includes('정식 모드')));
});

test('업데이트하면 새 파일로 바뀌고, 예전 파일과 기록은 지워지며, 새로 필요한 필수 모드도 설치된다', async (t) => {
  const { manager, files } = await setup(t);
  const { updates } = await checkModUpdates({ modManager: manager });
  const update = updates.find((candidate) => candidate.name === '정식 모드');
  const result = await applyModUpdate({ modManager: manager, update, resolveFile: resolveLibrary });

  assert.equal(result.fileName, 'r2.jar');
  assert.deepEqual(result.installed, ['라이브러리 LIB']);
  assert.ok(!fs.existsSync(files.released), '예전 파일이 남아 있다');
  assert.equal(fs.readFileSync(path.join(manager.modsDir, 'r2.jar'), 'utf8'), 'r2 내용');
  assert.equal(fs.readFileSync(path.join(manager.modsDir, 'LIB.jar'), 'utf8'), 'LIB 내용');

  const index = await manager.readIndex();
  assert.equal(index.files['released-1.0.jar'], undefined);
  assert.deepEqual(index.files['r2.jar'], { source: 'modrinth', projectId: 'P1', versionId: 'r2', title: '정식 모드' });
  assert.deepEqual([...(await manager.installedProjects('modrinth'))].sort(), ['LIB', 'P1']);
});

test('꺼 둔 모드를 업데이트하면 새 파일도 꺼진 채로 둔다', async (t) => {
  const { manager } = await setup(t);
  const disabled = await manager.setEnabled('beta-2.0-beta1.jar', false);
  const { updates } = await checkModUpdates({ modManager: manager });
  const update = updates.find((candidate) => candidate.name === '베타 모드');

  assert.equal(update.fileName, disabled);
  const result = await applyModUpdate({ modManager: manager, update, resolveFile: resolveLibrary });
  assert.equal(result.fileName, 'b2.jar.disabled');
  assert.deepEqual(
    (await manager.fileNames()).filter((name) => name.startsWith('b')).sort(),
    ['b2.jar.disabled'],
  );
});

test('받은 파일이 원본과 다르면 예전 모드를 그대로 둔다', async (t) => {
  const { manager, files, routes } = await setup(t);
  const { updates } = await checkModUpdates({ modManager: manager });
  routes['https://cdn.modrinth.com/r2.jar'] = { body: '바꿔치기한 내용' };

  await assert.rejects(
    applyModUpdate({ modManager: manager, update: updates.find((candidate) => candidate.name === '정식 모드'), resolveFile: resolveLibrary }),
    (error) => error.code === 'HASH_MISMATCH',
  );
  assert.ok(fs.existsSync(files.released), '실패했는데 예전 파일이 지워졌다');
  assert.ok(!fs.existsSync(path.join(manager.modsDir, 'r2.jar')));
  assert.equal((await manager.readIndex()).files['released-1.0.jar'].versionId, 'r1');
});

test('기본 모드와 허용되지 않은 주소·파일 이름은 업데이트하지 않는다', async (t) => {
  const { manager } = await setup(t);
  const file = { projectId: 'X', title: 'X', versionId: 'x2', fileName: 'x2.jar', url: 'https://cdn.modrinth.com/r2.jar', hashes: {}, dependencies: [] };

  await assert.rejects(
    applyModUpdate({ modManager: manager, update: { fileName: 'cafeteria-client-1.2.0.jar', name: 'Cafeteria', source: 'modrinth', file }, resolveFile: resolveLibrary }),
    (error) => error.code === 'BUILTIN_MOD',
  );
  await assert.rejects(
    applyModUpdate({
      modManager: manager,
      update: { fileName: 'mine-0.3.jar', name: '내 모드', source: 'modrinth', file: { ...file, url: 'https://example.com/evil.jar' } },
      resolveFile: resolveLibrary,
    }),
    (error) => error.code === 'UNTRUSTED_URL',
  );
  await assert.rejects(
    applyModUpdate({
      modManager: manager,
      update: { fileName: 'mine-0.3.jar', name: '내 모드', source: 'modrinth', file: { ...file, fileName: 'evil.exe' } },
      resolveFile: resolveLibrary,
    }),
    (error) => error.code === 'BAD_FILE_NAME',
  );
  assert.ok(fs.existsSync(path.join(manager.modsDir, 'mine-0.3.jar')));
});

test('새 버전 고르기: 정식 버전 사용자는 정식으로만, 베타 사용자는 더 새로운 것이면 받는다', () => {
  const current = (type) => ({ id: 'now', version_type: type, date_published: '2026-03-01T00:00:00Z' });
  const older = { id: 'older', version_type: 'release', date_published: '2026-01-01T00:00:00Z' };
  const newerBeta = { id: 'newer-beta', version_type: 'beta', date_published: '2026-05-01T00:00:00Z' };
  const newerRelease = { id: 'newer-release', version_type: 'release', date_published: '2026-04-01T00:00:00Z' };

  assert.equal(modrinth.pickNewerVersion(current('release'), [newerBeta, current('release'), older]), null);
  assert.equal(modrinth.pickNewerVersion(current('release'), [newerBeta, newerRelease, older]).id, 'newer-release');
  assert.equal(modrinth.pickNewerVersion(current('beta'), [newerBeta, older]).id, 'newer-beta');
  assert.equal(modrinth.pickNewerVersion(current('beta'), [newerBeta, newerRelease]).id, 'newer-release');
  assert.equal(modrinth.pickNewerVersion(current('release'), []), null);
});

test('[실제 서버] 예전 버전의 모드를 넣어 두면 새 버전을 찾아서 바꿔 넣는다', live, async (t) => {
  const manager = new ModManager(tempDir(t));
  // Sodium (AANobbMI) 의 1.21.4 Fabric 용 버전 중 가장 오래된 것을 "직접 넣은 모드"처럼 넣어 둔다.
  const versions = await modrinth.listVersions('AANobbMI');
  assert.ok(versions.length >= 2, 'Sodium 의 1.21.4 용 버전이 둘 이상 있어야 한다');
  const releases = versions.filter((candidate) => candidate.version_type === 'release');
  assert.ok(releases.length >= 2, '정식 버전이 둘 이상 있어야 한다');
  const oldest = releases[releases.length - 1];
  const newest = releases[0];
  const oldFile = await modrinth.describeVersion(oldest, 'Sodium');
  await http.download(oldFile.url, path.join(manager.modsDir, oldFile.fileName), { allowedHosts: ['cdn.modrinth.com'], hashes: oldFile.hashes });

  const result = await checkModUpdates({ modManager: manager });
  assert.equal(result.updates.length, 1);
  assert.equal(result.updates[0].newVersion, newest.version_number);
  assert.notEqual(result.updates[0].file.fileName, oldFile.fileName);

  const applied = await applyModUpdate({ modManager: manager, update: result.updates[0], resolveFile: modrinth.resolveFile });
  assert.deepEqual(await manager.fileNames(), [applied.fileName]);
  assert.equal(sha1OfFile(path.join(manager.modsDir, applied.fileName)), result.updates[0].file.hashes.sha1);

  // 바꾼 뒤에 다시 확인하면 더 받을 것이 없다.
  assert.deepEqual((await checkModUpdates({ modManager: manager })).updates, []);
});
