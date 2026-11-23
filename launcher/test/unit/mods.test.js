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
