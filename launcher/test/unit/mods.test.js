'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { ModManager } = require('../../src/main/mods');
const { makeJar, tempDir } = require('./helpers');

function setup(t) {
  const gameDir = tempDir(t);
  const manager = new ModManager(gameDir);
  return { gameDir, manager, modsDir: manager.modsDir };
}

test('mods 폴더가 없으면 빈 목록', async (t) => {
  const { manager } = setup(t);
  assert.deepEqual(await manager.list(), []);
});

test('jar 안의 fabric.mod.json 에서 이름과 버전을 읽고, 기본 모드를 맨 위에 둔다', async (t) => {
  const { manager, modsDir } = setup(t);
  makeJar(path.join(modsDir, 'sodium.jar'), { id: 'sodium', name: 'Sodium', version: '0.6.13', description: '렌더링 최적화' });
  makeJar(path.join(modsDir, 'cafeteria-client-1.0.0.jar'), { id: 'cafeteria', name: 'Cafeteria Client', version: '1.0.0' });
  makeJar(path.join(modsDir, 'off.jar.disabled'), { id: 'off', name: 'Alpha Off', version: '1' });
  makeJar(path.join(modsDir, 'not-fabric.jar'), null);
  fs.writeFileSync(path.join(modsDir, 'broken.jar'), 'zip 이 아님');
  fs.writeFileSync(path.join(modsDir, 'readme.txt'), '모드가 아닌 파일');

  const mods = await manager.list();
  assert.deepEqual(
    mods.map((mod) => [mod.name, mod.enabled, mod.builtin]),
    [
      ['Cafeteria Client', true, true],
      ['Alpha Off', false, false],
      ['broken.jar', true, false],
      ['not-fabric.jar', true, false],
      ['Sodium', true, false],
    ],
  );
  const sodium = mods.find((mod) => mod.modId === 'sodium');
  assert.equal(sodium.version, '0.6.13');
  assert.equal(sodium.description, '렌더링 최적화');
  assert.equal(sodium.source, 'local');
});

test('모드를 끄면 .jar.disabled 로, 켜면 .jar 로 이름이 바뀐다', async (t) => {
  const { manager, modsDir } = setup(t);
  makeJar(path.join(modsDir, 'sodium.jar'), { id: 'sodium', name: 'Sodium' });

  assert.equal(await manager.setEnabled('sodium.jar', false), 'sodium.jar.disabled');
  assert.deepEqual(fs.readdirSync(modsDir), ['sodium.jar.disabled']);
  assert.equal(await manager.setEnabled('sodium.jar.disabled', true), 'sodium.jar');
  assert.deepEqual(fs.readdirSync(modsDir), ['sodium.jar']);
  // 이미 같은 상태면 그대로 둔다
  assert.equal(await manager.setEnabled('sodium.jar', true), 'sodium.jar');
});

test('모드를 삭제하면 파일과 출처 기록이 함께 지워진다', async (t) => {
  const { manager, modsDir } = setup(t);
  makeJar(path.join(modsDir, 'sodium.jar'), { id: 'sodium', name: 'Sodium' });
  await manager.record('sodium.jar', { source: 'modrinth', projectId: 'AANobbMI' });
  assert.deepEqual([...(await manager.installedProjects('modrinth'))], ['AANobbMI']);

  await manager.remove('sodium.jar');
  assert.deepEqual(fs.readdirSync(modsDir), []);
  assert.deepEqual([...(await manager.installedProjects('modrinth'))], []);
});

test('꺼 둔 모드도 설치된 프로젝트로 센다. 파일이 없어진 기록은 세지 않는다', async (t) => {
  const { manager, modsDir } = setup(t);
  makeJar(path.join(modsDir, 'a.jar.disabled'), { id: 'a' });
  await manager.record('a.jar', { source: 'modrinth', projectId: 'AAA' });
  await manager.record('gone.jar', { source: 'modrinth', projectId: 'GONE' });
  await manager.record('cf.jar', { source: 'curseforge', projectId: '123' });
  assert.deepEqual([...(await manager.installedProjects('modrinth'))], ['AAA']);
});

test('기본 모드(Cafeteria, Fabric API)는 끄거나 지울 수 없다', async (t) => {
  const { manager, modsDir } = setup(t);
  makeJar(path.join(modsDir, 'cafeteria-client-1.0.0.jar'), { id: 'cafeteria' });
  makeJar(path.join(modsDir, 'fabric-api.jar'), { id: 'fabric-api' });

  await assert.rejects(manager.setEnabled('cafeteria-client-1.0.0.jar', false), { code: 'BUILTIN_MOD' });
  await assert.rejects(manager.remove('fabric-api.jar'), { code: 'BUILTIN_MOD' });
  assert.equal(fs.readdirSync(modsDir).length, 2);
});

test('mods 폴더 밖을 가리키는 이름은 거부한다', async (t) => {
  const { manager, gameDir } = setup(t);
  fs.writeFileSync(path.join(gameDir, 'outside.jar'), 'x');

  for (const name of ['../outside.jar', '..\\outside.jar', 'sub/mod.jar', '/etc/passwd', 'mod.txt', '', null, 'a.jar/../../outside.jar']) {
    await assert.rejects(manager.remove(name), { code: 'BAD_FILE_NAME' }, `거부해야 하는 이름: ${name}`);
    await assert.rejects(manager.setEnabled(name, false), { code: 'BAD_FILE_NAME' });
  }

  assert.equal(fs.existsSync(path.join(gameDir, 'outside.jar')), true);
});

test('없는 모드를 지우려 하면 안내 오류', async (t) => {
  const { manager } = setup(t);
  await assert.rejects(manager.remove('nothing.jar'), { code: 'MOD_NOT_FOUND' });
});

test('PC의 jar 파일을 mods 폴더로 복사하고, jar 가 아닌 것은 거부한다', async (t) => {
  const { manager, modsDir } = setup(t);
  const downloads = tempDir(t);
  const jar = makeJar(path.join(downloads, 'my-mod.jar'), { id: 'my-mod', name: 'My Mod' });
  const text = path.join(downloads, 'notes.txt');
  fs.writeFileSync(text, 'x');
  // 같은 모드가 꺼진 상태로 이미 있었던 경우
  makeJar(path.join(modsDir, 'my-mod.jar.disabled'), { id: 'my-mod' });

  const result = await manager.addLocalFiles([jar, text, path.join(downloads, 'missing.jar'), downloads]);
  assert.deepEqual(result.added, ['my-mod.jar']);
  assert.deepEqual(result.rejected, ['notes.txt', 'missing.jar', path.basename(downloads)]);
  assert.deepEqual(fs.readdirSync(modsDir), ['my-mod.jar']);
  assert.equal(fs.existsSync(jar), true, '원본은 그대로 둔다');
});

test('jar 안의 아이콘을 목록용 이미지로 읽는다. 너무 크거나 PNG 가 아니면 쓰지 않는다', async (t) => {
  const AdmZip = require('adm-zip');
  const { manager, modsDir } = setup(t);
  fs.mkdirSync(modsDir, { recursive: true });
  const write = (name, iconPath, iconData) => {
    const zip = new AdmZip();
    zip.addFile('fabric.mod.json', Buffer.from(JSON.stringify({ id: name, name, icon: iconPath })));

    if (iconData) {
      zip.addFile(iconPath.replace(/^\.\//, ''), iconData);
    }

    zip.writeZip(path.join(modsDir, `${name}.jar`));
  };
  write('a-small', 'assets/a/icon.png', Buffer.from('png-bytes'));
  write('b-dot-slash', './icon.png', Buffer.from('png-bytes'));
  write('c-huge', 'icon.png', Buffer.alloc(300 * 1024));
  write('d-missing', 'assets/nope.png', null);
  write('e-not-png', 'icon.svg', Buffer.from('<svg/>'));

  const icons = Object.fromEntries((await manager.list()).map((mod) => [mod.name, mod.icon]));
  const expected = `data:image/png;base64,${Buffer.from('png-bytes').toString('base64')}`;
  assert.deepEqual(icons, { 'a-small': expected, 'b-dot-slash': expected, 'c-huge': null, 'd-missing': null, 'e-not-png': null });
});

test('직접 넣은 모드를 Modrinth 에서 찾아 이름과 아이콘을 채운다', async (t) => {
  const crypto = require('node:crypto');
  const http = require('../../src/main/http');
  const { fakeFetch } = require('./helpers');
  t.after(() => http.setFetch((...args) => globalThis.fetch(...args)));
  const { manager, modsDir } = setup(t);
  const sha1 = (name) => crypto.createHash('sha1').update(fs.readFileSync(path.join(modsDir, name))).digest('hex');

  // 이름이 적혀 있지 않은 모드 파일 두 개 (내용이 달라야 해시가 다르다)
  makeJar(path.join(modsDir, 'known.jar'), { id: 'known' });
  makeJar(path.join(modsDir, 'unknown.jar.disabled'), { id: 'homemade', name: '직접 만든 모드' });
  makeJar(path.join(modsDir, 'weird-icon.jar'), { id: 'weird' });
  makeJar(path.join(modsDir, 'from-launcher.jar'), { id: 'sodium' });
  await manager.record('from-launcher.jar', { source: 'modrinth', projectId: 'AANobbMI' });

  const fetch = fakeFetch({
    'https://api.modrinth.com/v2/version_files': (url, options) => {
      const { hashes, algorithm } = JSON.parse(options.body);
      assert.equal(algorithm, 'sha1');
      assert.deepEqual([...hashes].sort(), [sha1('known.jar'), sha1('unknown.jar.disabled'), sha1('weird-icon.jar')].sort(), '출처를 아는 파일은 묻지 않는다');
      return {
        json: {
          [sha1('known.jar')]: { id: 'ver1', project_id: 'PROJ1' },
          [sha1('weird-icon.jar')]: { id: 'ver2', project_id: 'PROJ2' },
        },
      };
    },
    'https://api.modrinth.com/v2/projects*': {
      json: [
        { id: 'PROJ1', title: '알려진 모드', icon_url: 'https://cdn.modrinth.com/data/PROJ1/icon.webp' },
        { id: 'PROJ2', title: '이상한 아이콘', icon_url: 'https://evil.example.com/icon.png' },
      ],
    },
  });
  http.setFetch(fetch);

  assert.equal(await manager.identifyLocalMods(), true);
  const mods = Object.fromEntries((await manager.list()).map((mod) => [mod.fileName, mod]));
  assert.equal(mods['known.jar'].name, '알려진 모드');
  assert.equal(mods['known.jar'].icon, 'https://cdn.modrinth.com/data/PROJ1/icon.webp');
  assert.equal(mods['known.jar'].source, 'local', '직접 넣은 파일이라는 표시는 유지한다');
  assert.equal(mods['weird-icon.jar'].name, '이상한 아이콘');
  assert.equal(mods['weird-icon.jar'].icon, null, 'Modrinth 가 아닌 주소의 아이콘은 쓰지 않는다');
  assert.equal(mods['unknown.jar.disabled'].name, '직접 만든 모드');
  assert.deepEqual([...(await manager.installedProjects('modrinth'))].sort(), ['AANobbMI', 'PROJ1', 'PROJ2']);

  // 한 번 찾아본 파일은 다시 묻지 않는다
  const callsBefore = fetch.calls.length;
  assert.equal(await manager.identifyLocalMods(), false);
  assert.equal(fetch.calls.length, callsBefore);
});

test('Modrinth 에 연결하지 못하면 기록하지 않고 다음에 다시 찾는다', async (t) => {
  const http = require('../../src/main/http');
  const { fakeFetch } = require('./helpers');
  t.after(() => http.setFetch((...args) => globalThis.fetch(...args)));
  const { manager, modsDir } = setup(t);
  makeJar(path.join(modsDir, 'mod.jar'), null);

  http.setFetch(async () => {
    throw new Error('offline');
  });
  assert.equal(await manager.identifyLocalMods(), false);
  assert.deepEqual((await manager.readIndex()).files, {});

  const fetch = fakeFetch({ 'https://api.modrinth.com/v2/version_files': { json: {} } });
  http.setFetch(fetch);
  assert.equal(await manager.identifyLocalMods(), false);
  assert.equal(fetch.calls.length, 1, '다시 연결되면 물어본다');
  assert.deepEqual((await manager.readIndex()).files, { 'mod.jar': { source: 'local', lookedUp: true } });
});

test('같은 모드의 새 파일을 넣으면 예전 파일(켜진 것, 꺼진 것)을 새 파일로 바꾼다', async (t) => {
  const manager = new ModManager(tempDir(t));
  const inbox = tempDir(t);
  makeJar(path.join(manager.modsDir, 'fastpack-0.3.0.jar'), { id: 'fastpack', name: 'FastPack', version: '0.3.0' });
  makeJar(path.join(manager.modsDir, 'fastpack-0.2.0.jar.disabled'), { id: 'fastpack', name: 'FastPack', version: '0.2.0' });
  makeJar(path.join(manager.modsDir, 'other-1.0.jar'), { id: 'other', name: '다른 모드', version: '1.0' });
  await manager.record('fastpack-0.3.0.jar', { source: 'local' });

  const result = await manager.addLocalFiles([makeJar(path.join(inbox, 'fastpack-0.3.1.jar'), { id: 'fastpack', name: 'FastPack', version: '0.3.1' })]);

  assert.deepEqual(result.added, ['fastpack-0.3.1.jar']);
  assert.deepEqual(result.rejected, []);
  assert.deepEqual(
    result.replaced.sort((a, b) => a.old.localeCompare(b.old)),
    [
      { old: 'fastpack-0.2.0.jar.disabled', new: 'fastpack-0.3.1.jar' },
      { old: 'fastpack-0.3.0.jar', new: 'fastpack-0.3.1.jar' },
    ],
  );
  assert.deepEqual((await manager.fileNames()).sort(), ['fastpack-0.3.1.jar', 'other-1.0.jar']);
  assert.equal((await manager.readIndex()).files['fastpack-0.3.0.jar'], undefined);
  assert.deepEqual((await manager.list()).map((mod) => `${mod.name} ${mod.version}`).sort(), ['FastPack 0.3.1', '다른 모드 1.0']);
});

test('같은 이름의 파일을 다시 넣으면 덮어쓰고, Cafeteria 모드는 따로 넣을 수 없다', async (t) => {
  const manager = new ModManager(tempDir(t));
  const inbox = tempDir(t);
  makeJar(path.join(manager.modsDir, 'mymod.jar'), { id: 'mymod', name: '내 모드', version: '1.0' });
  makeJar(path.join(manager.modsDir, 'cafeteria-client-1.2.0.jar'), { id: 'cafeteria', name: 'Cafeteria Client', version: '1.2.0' });

  const result = await manager.addLocalFiles([
    makeJar(path.join(inbox, 'mymod.jar'), { id: 'mymod', name: '내 모드', version: '2.0' }),
    makeJar(path.join(inbox, 'my-cafeteria-build.jar'), { id: 'cafeteria', name: 'Cafeteria Client', version: '9.9.9' }),
  ]);

  assert.deepEqual(result.added, ['mymod.jar']);
  assert.deepEqual(result.rejected, ['my-cafeteria-build.jar']);
  assert.deepEqual(result.replaced, []);
  assert.deepEqual((await manager.fileNames()).sort(), ['cafeteria-client-1.2.0.jar', 'mymod.jar']);
  assert.equal((await manager.list()).find((mod) => mod.modId === 'mymod').version, '2.0');
});
