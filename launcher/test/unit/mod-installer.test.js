'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const { afterEach, test } = require('node:test');
const { LauncherError } = require('../../src/main/errors');
const http = require('../../src/main/http');
const { installMod } = require('../../src/main/mod-installer');
const { ModManager } = require('../../src/main/mods');
const { fakeFetch, tempDir } = require('./helpers');

afterEach(() => http.setFetch((...args) => globalThis.fetch(...args)));

const sha1 = (text) => crypto.createHash('sha1').update(text).digest('hex');

/** 프로젝트 ID → 파일 정보를 돌려주는 가짜 모드 사이트. 파일 내용은 "<id> 내용" 이다. */
function fakeSite(projects) {
  const routes = {};
  const resolved = [];

  for (const [id, project] of Object.entries(projects)) {
    routes[`https://cdn.modrinth.com/${id}.jar`] = { body: `${id} 내용` };
    project.id = id;
  }

  http.setFetch(fakeFetch(routes));

  return {
    resolved,
    resolveFile: async (id) => {
      resolved.push(id);
      const project = projects[id];

      if (!project) {
        throw new LauncherError('NO_COMPATIBLE_FILE', `${id} 는 받을 수 없음`);
      }

      return {
        projectId: project.projectId || id,
        title: project.title || `모드 ${id}`,
        versionId: `${id}-v1`,
        fileName: project.fileName || `${id}.jar`,
        url: project.url || `https://cdn.modrinth.com/${id}.jar`,
        hashes: { sha1: project.sha1 || sha1(`${id} 내용`) },
        dependencies: project.dependencies || [],
      };
    },
  };
}

function install(t, site, projectId, manager = new ModManager(tempDir(t))) {
  return installMod({ source: 'modrinth', projectId, resolveFile: site.resolveFile, modManager: manager }).then((result) => ({ ...result, manager }));
}

test('의존성을 따라가며 설치하고, 서로를 요구하는 모드도 한 번씩만 받는다', async (t) => {
  const site = fakeSite({
    A: { dependencies: ['B', 'C'] },
    B: { dependencies: ['C', 'A'] },
    C: { dependencies: ['A'] },
  });
  const { installed, warnings, manager } = await install(t, site, 'A');
  assert.deepEqual(installed, ['모드 A', '모드 B', '모드 C']);
  assert.deepEqual(warnings, []);
  assert.deepEqual(site.resolved, ['A', 'B', 'C']);
  assert.deepEqual(fs.readdirSync(manager.modsDir).sort(), ['A.jar', 'B.jar', 'C.jar']);
  assert.deepEqual([...(await manager.installedProjects('modrinth'))].sort(), ['A', 'B', 'C']);
});

test('Fabric API 는 런처가 넣어 주므로 의존성으로 받지 않는다', async (t) => {
  const site = fakeSite({ A: { dependencies: ['P7dR8mSH', 'fabric-api'] } });
  const { installed } = await install(t, site, 'A');
  assert.deepEqual(installed, ['모드 A']);
  assert.deepEqual(site.resolved, ['A']);
});

test('이미 설치된 모드는 다시 받지 않는다: 직접 고른 경우는 알려 주고, 의존성이면 건너뛴다', async (t) => {
  const site = fakeSite({ A: { dependencies: ['B'] }, B: {} });
  const { manager } = await install(t, site, 'B');
  await assert.rejects(install(t, site, 'B', manager), { code: 'ALREADY_INSTALLED' });

  site.resolved.length = 0;
  const { installed } = await install(t, site, 'A', manager);
  assert.deepEqual(installed, ['모드 A']);
  assert.deepEqual(site.resolved, ['A']);
});

test('의존성을 받지 못해도 본 모드는 설치하고 무엇이 빠졌는지 알려 준다', async (t) => {
  const site = fakeSite({ A: { dependencies: ['MISSING', 'B'] }, B: {} });
  const { installed, warnings, manager } = await install(t, site, 'A');
  assert.deepEqual(installed, ['모드 A', '모드 B']);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /MISSING/);
  assert.deepEqual(fs.readdirSync(manager.modsDir).sort(), ['A.jar', 'B.jar']);
});

test('직접 고른 모드를 받을 수 없으면 오류', async (t) => {
  await assert.rejects(install(t, fakeSite({}), 'NOPE'), { code: 'NO_COMPATIBLE_FILE' });
});

test('해시가 다른 파일은 설치하지 않는다', async (t) => {
  const manager = new ModManager(tempDir(t));
  await assert.rejects(install(t, fakeSite({ A: { sha1: 'f'.repeat(40) } }), 'A', manager), { code: 'HASH_MISMATCH' });
  assert.deepEqual(fs.readdirSync(manager.modsDir), []);
  assert.deepEqual([...(await manager.installedProjects('modrinth'))], []);
});

test('허용되지 않은 주소나 이상한 파일 이름은 설치하지 않는다', async (t) => {
  await assert.rejects(install(t, fakeSite({ A: { url: 'https://evil.example.com/A.jar' } }), 'A'), { code: 'UNTRUSTED_URL' });
  await assert.rejects(install(t, fakeSite({ A: { fileName: 'virus.exe' } }), 'A'), { code: 'BAD_FILE_NAME' });
  await assert.rejects(install(t, fakeSite({ A: { fileName: 'a:b.jar' } }), 'A'), { code: 'BAD_FILE_NAME' });
});

test('경로가 섞인 파일 이름은 이름 부분만 써서 mods 폴더 안에 저장한다', async (t) => {
  const { manager } = await install(t, fakeSite({ A: { fileName: '../../outside/A.jar' } }), 'A');
  assert.deepEqual(fs.readdirSync(manager.modsDir), ['A.jar']);
  assert.deepEqual(fs.readdirSync(manager.gameDir).sort(), ['cafeteria-mods.json', 'mods']);
});

test('의존성이 끝없이 이어져도 정해진 깊이에서 멈춘다', async (t) => {
  const projects = {};

  for (let i = 0; i < 20; i++) {
    projects[`M${i}`] = { dependencies: [`M${i + 1}`] };
  }

  const { installed } = await install(t, fakeSite(projects), 'M0');
  assert.equal(installed.length, 6, '본 모드 + 의존성 5단계');
});
