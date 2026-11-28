'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { test } = require('node:test');
const AdmZip = require('adm-zip');
const { DOWNLOAD_HOSTS } = require('../../src/main/constants');
const http = require('../../src/main/http');
const { ModManager } = require('../../src/main/mods');
const { LIMITS, PROFILE_EXTENSION, RESERVED_MOD_IDS, applyProfile, exportProfile, inspectProfile } = require('../../src/main/profile');
const modrinth = require('../../src/main/sources/modrinth');
const { live, makeJar, tempDir } = require('./helpers');

const CAFETERIA_JAR = 'cafeteria-client-1.5.0+1.21.4.jar';
const FABRIC_API_JAR = 'fabric-api-0.119.4+1.21.4.jar';
const TEMP_PREFIX = '.cafeteria-profile-tmp-';
const SETTINGS = { modules: { zoom: { enabled: true, settings: { zoom_factor: 6 } } } };

const sha1 = (data) => crypto.createHash('sha1').update(data).digest('hex');
const sha512 = (data) => crypto.createHash('sha512').update(data).digest('hex');
const sha1OfFile = (file) => sha1(fs.readFileSync(file));
const sizeOf = (file) => fs.statSync(file).size;

/** 폴더 안의 모든 것(하위 폴더 포함): 상대 경로 → 파일이면 sha1, 폴더면 'dir'. 적용 앞뒤를 통째로 비교하는 데 쓴다. */
function tree(root, relative = '') {
  const result = {};
  const directory = path.join(root, relative);

  for (const name of fs.existsSync(directory) ? fs.readdirSync(directory).sort() : []) {
    const child = relative ? `${relative}/${name}` : name;

    if (fs.statSync(path.join(root, child)).isDirectory()) {
      result[child] = 'dir';
      Object.assign(result, tree(root, child));
    } else {
      result[child] = sha1OfFile(path.join(root, child));
    }
  }

  return result;
}

/** 가짜 Modrinth. add 로 알려 준 파일만 알고, 그 파일만 내려 준다. 묻고 받은 기록을 lookups, downloads 에 남긴다. */
function fakeModrinth() {
  const versions = new Map();
  const contents = new Map();

  const site = {
    lookups: [],
    downloads: [],

    /** 파일을 Modrinth 에 올라와 있는 것으로 만든다. otherFiles 는 같은 버전에 든 다른 파일들(이 파일보다 앞에 놓인다). */
    add(file, { projectId = 'P1', versionId = 'V1', otherFiles = [] } = {}) {
      const data = fs.readFileSync(file);
      const url = `https://cdn.modrinth.com/data/${projectId}/versions/${versionId}/${path.basename(file)}`;
      contents.set(url, data);
      versions.set(sha1(data), {
        id: versionId,
        project_id: projectId,
        files: [...otherFiles, { filename: path.basename(file), url, primary: true, hashes: { sha1: sha1(data), sha512: sha512(data) } }],
      });
      return url;
    },

    identifyFiles: async (hashes) => {
      site.lookups.push(hashes);
      return Object.fromEntries(hashes.filter((hash) => versions.has(hash)).map((hash) => [hash, versions.get(hash)]));
    },

    download: async (url, destination, options) => {
      site.downloads.push({ url, destination, options });

      if (site.failingUrls.includes(url)) {
        throw new Error(`받다가 끊김: ${url}`);
      }

      fs.writeFileSync(destination, contents.get(url));
    },

    failingUrls: [],
  };

  return site;
}

/** 보내는 쪽 게임 폴더: A(Modrinth 에 있음), B(없음), C(꺼짐), Cafeteria 모드, Fabric API. */
function senderWorld(t) {
  const gameDir = tempDir(t);
  const manager = new ModManager(gameDir);
  const jar = (name, metadata) => makeJar(path.join(manager.modsDir, name), metadata);
  const files = {
    a: jar('alpha-1.0.jar', { id: 'alpha', name: 'Alpha', version: '1.0' }),
    b: jar('beta-2.0.jar', { id: 'beta', name: 'Beta', version: '2.0' }),
    c: jar('gamma-1.0.jar.disabled', { id: 'gamma', name: 'Gamma', version: '1.0' }),
    cafeteria: jar(CAFETERIA_JAR, { id: 'cafeteria', name: 'Cafeteria Client', version: '1.5.0' }),
    fabricApi: jar(FABRIC_API_JAR, { id: 'fabric-api', name: 'Fabric API', version: '0.119.4' }),
  };
  const site = fakeModrinth();
  const urlOfA = site.add(files.a, { projectId: 'PA', versionId: 'VA' });

  return { gameDir, manager, jar, files, site, urlOfA, filePath: path.join(tempDir(t), `내 프로필.${PROFILE_EXTENSION}`) };
}

function exportFrom(sender, options = {}) {
  return exportProfile({
    modManager: sender.manager,
    gameDir: sender.gameDir,
    filePath: sender.filePath,
    name: '내 PvP 세팅',
    includeSettings: false,
    appVersion: '1.5.0',
    identifyFiles: sender.site.identifyFiles,
    ...options,
  });
}

/** A(Modrinth 에 있음)와 B(파일째)가 든 프로필을 내보내 둔다. */
async function exportedProfile(t, options) {
  const sender = senderWorld(t);
  await exportFrom(sender, options);
  return sender;
}

/** 받는 쪽 게임 폴더. 기본 모드 둘이 들어 있다. */
function receiverWorld(t) {
  const gameDir = tempDir(t);
  const manager = new ModManager(gameDir);
  const jar = (name, metadata) => makeJar(path.join(manager.modsDir, name), metadata);
  jar(CAFETERIA_JAR, { id: 'cafeteria', name: 'Cafeteria Client', version: '1.5.0' });
  jar(FABRIC_API_JAR, { id: 'fabric-api', name: 'Fabric API', version: '0.119.4' });

  return { gameDir, manager, modsDir: manager.modsDir, jar };
}

/**
 * 바꿀 것이 종류별로 하나씩 있는 받는 쪽: 예전 버전의 B(바뀔 것), 꺼져 있는 A(켜질 것), 프로필에 없는 D(꺼질 것).
 * disableOthers 로 적용하면 파일을 네 번 옮긴다: 예전 B 를 치우고, 새 B 를 넣고, A 를 켜고, D 를 끈다.
 */
async function busyReceiver(t, profile) {
  const receiver = receiverWorld(t);
  receiver.oldB = receiver.jar('beta-1.0.jar', { id: 'beta', name: 'Beta', version: '1.0' });
  fs.copyFileSync(profile.files.a, path.join(receiver.modsDir, 'alpha-1.0.jar.disabled'));
  receiver.jar('delta-1.0.jar', { id: 'delta', name: 'Delta', version: '1.0' });
  await receiver.manager.record('beta-1.0.jar', { source: 'local' });
  await receiver.manager.record('delta-1.0.jar', { source: 'modrinth', projectId: 'PD', versionId: 'VD', title: 'Delta' });

  return receiver;
}

function applyTo(receiver, profile, options = {}) {
  return applyProfile({
    filePath: profile.filePath,
    modManager: receiver.manager,
    gameDir: receiver.gameDir,
    disableOthers: false,
    applySettings: false,
    identifyFiles: profile.site.identifyFiles,
    download: profile.site.download,
    ...options,
  });
}

/** 적용이 expected 대로 거절되고, 받는 쪽 게임 폴더가 (임시 폴더 하나 남지 않고) 적용 전 그대로인지 확인한다. */
async function assertRefused(receiver, attempt, expected, message) {
  const before = tree(receiver.gameDir);
  await assert.rejects(attempt(), expected, message);
  assert.deepEqual(tree(receiver.gameDir), before, message);
}

/** 오류의 코드가 맞고 안내문에 include 가 모두 들어 있으며 exclude 는 하나도 없는지 확인하는 함수. */
function errorWith(code, { include = [], exclude = [] } = {}) {
  return (error) => {
    assert.equal(error.code, code);

    for (const text of include) {
      assert.ok(error.message.includes(text), `안내문에 ${text} 가 있어야 한다: ${error.message}`);
    }

    for (const text of exclude) {
      assert.equal(error.message.includes(text), false, `안내문에 ${text} 가 없어야 한다: ${error.message}`);
    }

    return true;
  };
}

/**
 * 남이 손으로 만든 프로필 파일을 흉내 낸다. 아무것도 주지 않으면 모드가 없는 올바른 프로필이고,
 * 테스트마다 한 군데씩만 어긋나게 만든다.
 *
 * @param {string} filePath
 * @param {object} [options]
 * @param {object | null} [options.manifest] profile.json 에 덮어쓸 값. null 이면 profile.json 을 넣지 않는다
 * @param {object[]} [options.mods] 목록의 항목. jar(파일 경로)나 data(Buffer)를 주면 그 내용의 sha1·size 를 적고,
 *   kind 가 file 이면 내용을 zip 에 넣는다 (entryName 으로 넣을 자리를 바꾸고, null 이면 넣지 않는다. stored 면 압축하지 않는다).
 *   나머지 값은 목록에 그대로 적힌다
 * @param {Record<string, string>} [options.entries] 더 넣을 zip 항목 (이름 → 내용)
 * @param {(buffer: Buffer) => void} [options.patch] 다 만든 zip 의 바이트를 고친다
 */
function craftProfile(filePath, { manifest = {}, mods = [], entries = {}, patch } = {}) {
  const zip = new AdmZip();

  const list = mods.map(({ jar, data = jar ? fs.readFileSync(jar) : null, entryName, stored, ...fields }) => {
    const mod = {
      kind: 'file',
      title: '',
      version: '',
      ...(jar ? { fileName: path.basename(jar) } : {}),
      ...(data ? { sha1: sha1(data), size: data.length } : {}),
      ...fields,
    };

    if (data && mod.kind === 'file' && entryName !== null) {
      const entry = zip.addFile(entryName || `mods/${mod.fileName}`, data);

      if (stored) {
        entry.header.method = 0;
      }
    }

    return mod;
  });

  for (const [name, content] of Object.entries(entries)) {
    zip.addFile(name, Buffer.from(content));
  }

  if (manifest) {
    const content = { format: 1, type: 'cafeteria-profile', name: '받은 프로필', createdWith: '1.5.0', minecraft: '1.21.4', hasSettings: false, mods: list, ...manifest };
    zip.addFile('profile.json', Buffer.from(JSON.stringify(content)));
  }

  const buffer = zip.toBuffer();

  if (patch) {
    patch(buffer);
  }

  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, buffer);
  return filePath;
}

/** adm-zip 은 항목 이름을 정리해서 넣으므로(../ 가 사라진다), 길이가 같은 이름으로 넣은 뒤 로컬·중앙 머리의 이름을 바꿔 쓴다. */
function renameZipEntry(buffer, from, to) {
  assert.equal(Buffer.byteLength(from), Buffer.byteLength(to));
  let count = 0;

  for (let index = buffer.indexOf(from); index !== -1; index = buffer.indexOf(from, index + 1)) {
    buffer.write(to, index);
    count += 1;
  }

  assert.equal(count, 2, '로컬 머리와 중앙 머리에 한 번씩 있어야 한다');
}

/** zip 항목 하나의 로컬 머리와 중앙 머리에 적힌 "풀린 크기"를 고친다 (주석이 없는 zip). */
function patchUncompressedSize(buffer, entryName, size) {
  const end = buffer.length - 22;
  const count = buffer.readUInt16LE(end + 10);
  let position = buffer.readUInt32LE(end + 16);

  for (let index = 0; index < count; index += 1) {
    const nameLength = buffer.readUInt16LE(position + 28);

    if (buffer.toString('utf8', position + 46, position + 46 + nameLength) === entryName) {
      buffer.writeUInt32LE(size, position + 24);
      buffer.writeUInt32LE(size, buffer.readUInt32LE(position + 42) + 22);
      return;
    }

    position += 46 + nameLength + buffer.readUInt16LE(position + 30) + buffer.readUInt16LE(position + 32);
  }

  throw new Error(`zip 에 ${entryName} 항목이 없다`);
}

/** at 번째 호출(1부터)에서만, 또는 from 번째 호출부터 계속 EBUSY 로 실패하는 rename. 나머지는 실제로 옮긴다. */
function failingRename({ at, from }) {
  const rename = async (source, target) => {
    rename.calls += 1;

    if (rename.calls === at || rename.calls >= from) {
      throw Object.assign(new Error(`EBUSY: resource busy or locked, rename '${source}' -> '${target}'`), { code: 'EBUSY' });
    }

    return fsp.rename(source, target);
  };

  rename.calls = 0;
  return rename;
}

function readIndex(manager) {
  return JSON.parse(fs.readFileSync(manager.indexFile, 'utf8')).files;
}

function writeGameSettings(gameDir, settings) {
  const file = path.join(gameDir, 'config', 'cafeteria.json');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(settings));
  return file;
}

function zipEntryNames(filePath) {
  return new AdmZip(filePath).getEntries().map((entry) => entry.entryName).sort();
}

function manifestOf(filePath) {
  return JSON.parse(new AdmZip(filePath).getEntry('profile.json').getData().toString('utf8'));
}

test('켜진 모드만, 기본 모드는 빼고 내보낸다', async (t) => {
  const sender = senderWorld(t);
  const { a, b } = sender.files;

  assert.deepEqual(await exportFrom(sender), { mods: 2, embedded: ['beta-2.0.jar'], hasSettings: false });

  assert.deepEqual(zipEntryNames(sender.filePath), ['mods/beta-2.0.jar', 'profile.json']);
  assert.deepEqual(manifestOf(sender.filePath), {
    format: 1,
    type: 'cafeteria-profile',
    name: '내 PvP 세팅',
    createdWith: '1.5.0',
    minecraft: '1.21.4',
    hasSettings: false,
    mods: [
      { kind: 'modrinth', title: 'Alpha', version: '1.0', fileName: 'alpha-1.0.jar', sha1: sha1OfFile(a), size: sizeOf(a) },
      { kind: 'file', title: 'Beta', version: '2.0', fileName: 'beta-2.0.jar', sha1: sha1OfFile(b), size: sizeOf(b) },
    ],
  });
  assert.equal(sha1(new AdmZip(sender.filePath).getEntry('mods/beta-2.0.jar').getData()), sha1OfFile(b));
  // Modrinth 에는 내보낼 두 파일만 한 번에 묻는다.
  assert.deepEqual(sender.site.lookups, [[sha1OfFile(a), sha1OfFile(b)]]);
  // 내보낸 파일 말고는 아무것도 남기지 않는다.
  assert.deepEqual(fs.readdirSync(path.dirname(sender.filePath)), [path.basename(sender.filePath)]);
});

test('내장 기능과 겹치는 모드는 내보내지 않는다', async (t) => {
  const sender = senderWorld(t);
  const overlapping = sender.jar('overlap-1.0.jar', { id: 'fastpack', name: 'Overlap', version: '1.0' });

  const result = await exportFrom(sender);

  assert.deepEqual(result, { mods: 2, embedded: ['beta-2.0.jar'], hasSettings: false });
  assert.deepEqual(manifestOf(sender.filePath).mods.map((mod) => mod.fileName), ['alpha-1.0.jar', 'beta-2.0.jar']);
  assert.deepEqual(zipEntryNames(sender.filePath), ['mods/beta-2.0.jar', 'profile.json']);
  // 사용자가 직접 만든 비공개 모드일 수 있으므로 해시도 밖으로 내보내지 않는다.
  assert.equal(sender.site.lookups.flat().includes(sha1OfFile(overlapping)), false);
});

test('너무 큰 jar 는 넣지 않는다', async (t) => {
  const sender = senderWorld(t);
  const sizeOfB = sizeOf(sender.files.b);

  await assert.rejects(
    exportFrom(sender, { limits: { ...LIMITS, jarBytes: sizeOfB - 1 } }),
    errorWith('PROFILE_TOO_LARGE', { include: ['beta-2.0.jar'] }),
  );
  assert.deepEqual(fs.readdirSync(path.dirname(sender.filePath)), []);

  // 만든 zip 전체가 한도를 넘는 경우
  await assert.rejects(
    exportFrom(sender, { limits: { ...LIMITS, fileBytes: sizeOfB } }),
    errorWith('PROFILE_TOO_LARGE', { include: ['프로필 파일이 너무 큽니다'], exclude: ['beta-2.0.jar'] }),
  );
  assert.deepEqual(fs.readdirSync(path.dirname(sender.filePath)), []);

  // 한도와 크기가 같으면 넣는다. Modrinth 에서 받을 모드(A)의 크기는 따지지 않는다.
  const result = await exportFrom(sender, { limits: { ...LIMITS, jarBytes: sizeOfB } });
  assert.deepEqual(result.embedded, ['beta-2.0.jar']);
});

test('설정을 넣으면 config/cafeteria.json 이 들어간다', async (t) => {
  const sender = senderWorld(t);

  // 설정 파일이 없으면 넣어 달라고 해도 넣을 것이 없다.
  assert.equal((await exportFrom(sender, { includeSettings: true })).hasSettings, false);
  assert.equal(manifestOf(sender.filePath).hasSettings, false);
  assert.equal(zipEntryNames(sender.filePath).includes('config/cafeteria.json'), false);

  const settingsFile = writeGameSettings(sender.gameDir, SETTINGS);
  assert.equal((await exportFrom(sender, { includeSettings: true })).hasSettings, true);
  assert.equal(manifestOf(sender.filePath).hasSettings, true);
  assert.deepEqual(JSON.parse(new AdmZip(sender.filePath).getEntry('config/cafeteria.json').getData().toString('utf8')), SETTINGS);

  // 넣지 말라고 하면 설정 파일이 있어도 넣지 않는다.
  assert.equal((await exportFrom(sender, { includeSettings: false })).hasSettings, false);
  assert.equal(zipEntryNames(sender.filePath).includes('config/cafeteria.json'), false);

  // 설정처럼 생기지 않은 파일, 한도보다 큰 파일은 넣지 않는다.
  for (const content of ['{ 깨진 파일', '{"modules":[]}']) {
    fs.writeFileSync(settingsFile, content);
    assert.equal((await exportFrom(sender, { includeSettings: true })).hasSettings, false, content);
    assert.equal(zipEntryNames(sender.filePath).includes('config/cafeteria.json'), false, content);
  }

  fs.writeFileSync(settingsFile, JSON.stringify(SETTINGS));
  const tooSmall = { ...LIMITS, settingsBytes: sizeOf(settingsFile) - 1 };
  assert.equal((await exportFrom(sender, { includeSettings: true, limits: tooSmall })).hasSettings, false);
});

test('Modrinth 에 묻지 못하면 내보내지 않는다', async (t) => {
  const sender = senderWorld(t);
  const identifyFiles = async () => {
    throw new Error('연결 끊김');
  };

  await assert.rejects(exportFrom(sender, { identifyFiles }), {
    code: 'PROFILE_LOOKUP_FAILED',
    message: 'Modrinth 에서 모드 정보를 확인하지 못했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요.',
  });
  assert.deepEqual(fs.readdirSync(path.dirname(sender.filePath)), []);
});

test('프로필 이름은 앞뒤 공백을 떼고 60자까지 쓴다', async (t) => {
  const sender = senderWorld(t);
  const cases = [
    ['  내 PvP 세팅  ', '내 PvP 세팅'],
    ['', 'Cafeteria 프로필'],
    ['   ', 'Cafeteria 프로필'],
    [undefined, 'Cafeteria 프로필'],
    ['가'.repeat(61), '가'.repeat(60)],
  ];

  for (const [name, expected] of cases) {
    await exportFrom(sender, { name });
    assert.equal(manifestOf(sender.filePath).name, expected, JSON.stringify(name));
    assert.equal((await inspectProfile({ filePath: sender.filePath, modManager: sender.manager })).name, expected);
  }
});

test('내보낼 곳에 쓰지 못하면 PROFILE_EXPORT_FAILED 로 알린다', async (t) => {
  const sender = senderWorld(t);
  // 내보낼 파일 자리에 폴더가 있다.
  fs.mkdirSync(sender.filePath);

  await assert.rejects(exportFrom(sender), (error) => {
    assert.equal(error.code, 'PROFILE_EXPORT_FAILED');
    assert.match(error.details.cause, /^E[A-Z]+$/);
    return true;
  });
  assert.deepEqual(fs.readdirSync(path.dirname(sender.filePath)), [path.basename(sender.filePath)]);
});

test('내보낸 프로필을 빈 폴더에 적용하면 같은 구성이 된다', async (t) => {
  const profile = await exportedProfile(t);
  const manager = new ModManager(tempDir(t));
  const progress = [];

  const result = await applyTo({ manager, gameDir: manager.gameDir }, profile, { onProgress: (update) => progress.push(update) });

  assert.deepEqual(result, { installed: ['beta-2.0.jar', 'alpha-1.0.jar'].sort(), present: 0, replaced: [], disabled: [], settingsApplied: false });
  // mods 폴더에는 두 모드만 있고 임시 폴더는 남지 않는다.
  assert.deepEqual(tree(manager.modsDir), { 'alpha-1.0.jar': sha1OfFile(profile.files.a), 'beta-2.0.jar': sha1OfFile(profile.files.b) });
  assert.deepEqual(readIndex(manager), {
    'alpha-1.0.jar': { source: 'modrinth', projectId: 'PA', versionId: 'VA', title: 'Alpha' },
    'beta-2.0.jar': { source: 'local', title: 'Beta' },
  });

  // 파일째 들어 있는 B 는 묻지도 받지도 않는다.
  assert.deepEqual(profile.site.lookups.at(-1), [sha1OfFile(profile.files.a)]);
  assert.equal(profile.site.downloads.length, 1);
  const [download] = profile.site.downloads;
  assert.equal(download.url, profile.urlOfA);
  assert.deepEqual(download.options.allowedHosts, DOWNLOAD_HOSTS);
  assert.deepEqual(download.options.hashes, { sha1: sha1OfFile(profile.files.a), sha512: sha512(fs.readFileSync(profile.files.a)) });
  // mods 폴더에 바로 받지 않고 그 안의 임시 폴더에 받는다.
  assert.equal(path.dirname(path.dirname(download.destination)), manager.modsDir);
  assert.ok(path.basename(path.dirname(download.destination)).startsWith(TEMP_PREFIX));

  assert.ok(progress.length >= 2);
  assert.ok(progress.every((update) => typeof update.message === 'string' && update.message.length > 0 && update.fraction >= 0 && update.fraction <= 1));
  assert.equal(progress[0].fraction, 0);
  assert.equal(progress.at(-1).fraction, 1);
  assert.deepEqual(progress.map((update) => update.fraction), progress.map((update) => update.fraction).sort((a, b) => a - b), '진행률은 줄어들지 않는다');
});

test('이미 있는 파일은 다시 받지 않고, 꺼져 있으면 켠다', async (t) => {
  const profile = await exportedProfile(t);
  const receiver = receiverWorld(t);
  // 내용은 A 와 같고 이름만 다르다. 꺼져 있다.
  fs.copyFileSync(profile.files.a, path.join(receiver.modsDir, 'my-alpha.jar.disabled'));
  const lookupsBefore = profile.site.lookups.length;

  const result = await applyTo(receiver, profile);

  assert.deepEqual(result, { installed: ['beta-2.0.jar'], present: 1, replaced: [], disabled: [], settingsApplied: false });
  assert.equal(profile.site.downloads.length, 0);
  assert.equal(profile.site.lookups.length, lookupsBefore, '받을 것이 없으면 Modrinth 에 묻지도 않는다');
  assert.deepEqual(Object.keys(tree(receiver.modsDir)), ['beta-2.0.jar', CAFETERIA_JAR, FABRIC_API_JAR, 'my-alpha.jar']);
  assert.equal(sha1OfFile(path.join(receiver.modsDir, 'my-alpha.jar')), sha1OfFile(profile.files.a));
});

test('같은 내용의 파일이 켜진 것과 꺼진 것으로 둘 있으면 건드리지 않는다', async (t) => {
  const profile = await exportedProfile(t);
  const receiver = receiverWorld(t);
  fs.copyFileSync(profile.files.a, path.join(receiver.modsDir, 'alpha-off.jar.disabled'));
  fs.copyFileSync(profile.files.a, path.join(receiver.modsDir, 'alpha-on.jar'));

  const result = await applyTo(receiver, profile);

  // 꺼진 쪽까지 켜면 같은 모드가 두 벌이 된다.
  assert.equal(result.present, 1);
  assert.deepEqual(Object.keys(tree(receiver.modsDir)), ['alpha-off.jar.disabled', 'alpha-on.jar', 'beta-2.0.jar', CAFETERIA_JAR, FABRIC_API_JAR]);
});

test('같은 모드의 예전 버전을 바꾼다', async (t) => {
  const profile = await exportedProfile(t);
  const receiver = receiverWorld(t);
  receiver.jar('beta-1.0.jar', { id: 'beta', name: 'Beta', version: '1.0' });
  await receiver.manager.record('beta-1.0.jar', { source: 'modrinth', projectId: 'OLD', versionId: 'OLD-1', title: 'Beta' });

  const result = await applyTo(receiver, profile);

  assert.deepEqual(result.replaced, ['beta-1.0.jar → beta-2.0.jar']);
  assert.deepEqual(result.installed.sort(), ['alpha-1.0.jar', 'beta-2.0.jar']);
  assert.deepEqual(Object.keys(tree(receiver.modsDir)), ['alpha-1.0.jar', 'beta-2.0.jar', CAFETERIA_JAR, FABRIC_API_JAR]);
  assert.equal(sha1OfFile(path.join(receiver.modsDir, 'beta-2.0.jar')), sha1OfFile(profile.files.b));
  // 예전 파일의 출처 기록은 지우고 새 파일의 기록을 적는다.
  assert.deepEqual(Object.keys(readIndex(receiver.manager)).sort(), ['alpha-1.0.jar', 'beta-2.0.jar']);
});

test('꺼 둔 예전 버전과 이름이 같은 파일도 바꾼다', async (t) => {
  const profile = await exportedProfile(t);
  const receiver = receiverWorld(t);
  // 같은 모드의 꺼 둔 예전 버전
  receiver.jar('beta-0.9.jar.disabled', { id: 'beta', name: 'Beta', version: '0.9' });
  // 모드 id 는 다르지만 이름이 같은 파일 (Windows 에서는 대소문자만 달라도 같은 파일이다), 꺼진 채로
  receiver.jar('Alpha-1.0.jar.disabled', { id: 'other', name: 'Other', version: '1' });
  await receiver.manager.record('Alpha-1.0.jar', { source: 'local' });

  const result = await applyTo(receiver, profile);

  assert.deepEqual(result.replaced.sort(), ['Alpha-1.0.jar.disabled → alpha-1.0.jar', 'beta-0.9.jar.disabled → beta-2.0.jar']);
  assert.deepEqual(tree(receiver.modsDir), {
    'alpha-1.0.jar': sha1OfFile(profile.files.a),
    'beta-2.0.jar': sha1OfFile(profile.files.b),
    [CAFETERIA_JAR]: sha1OfFile(path.join(receiver.modsDir, CAFETERIA_JAR)),
    [FABRIC_API_JAR]: sha1OfFile(path.join(receiver.modsDir, FABRIC_API_JAR)),
  });
  assert.deepEqual(Object.keys(readIndex(receiver.manager)).sort(), ['alpha-1.0.jar', 'beta-2.0.jar']);
});

test('프로필에 없는 모드를 끈다', async (t) => {
  const profile = await exportedProfile(t);

  const replacing = receiverWorld(t);
  replacing.jar('delta-1.0.jar', { id: 'delta', name: 'Delta', version: '1.0' });
  // 프로필에 있는 A 는 이미 켜져 있다. 그대로 켜 둔다.
  fs.copyFileSync(profile.files.a, path.join(replacing.modsDir, 'alpha-1.0.jar'));
  // 내용은 달라도 모드 id 가 프로필의 모드(A)와 같은 파일은 "프로필에 없는 모드"로 치지 않는다.
  replacing.jar('alpha-other.jar', { id: 'alpha', name: 'Alpha', version: '0.9' });
  const result = await applyTo(replacing, profile, { disableOthers: true });
  assert.deepEqual(result, { installed: ['beta-2.0.jar'], present: 1, replaced: [], disabled: ['delta-1.0.jar'], settingsApplied: false });
  // 기본 모드는 프로필에 없어도 켜 둔다.
  assert.deepEqual(Object.keys(tree(replacing.modsDir)), ['alpha-1.0.jar', 'alpha-other.jar', 'beta-2.0.jar', CAFETERIA_JAR, 'delta-1.0.jar.disabled', FABRIC_API_JAR]);

  const adding = receiverWorld(t);
  adding.jar('delta-1.0.jar', { id: 'delta', name: 'Delta', version: '1.0' });
  assert.deepEqual((await applyTo(adding, profile, { disableOthers: false })).disabled, []);
  assert.deepEqual(Object.keys(tree(adding.modsDir)), ['alpha-1.0.jar', 'beta-2.0.jar', CAFETERIA_JAR, 'delta-1.0.jar', FABRIC_API_JAR]);

  // 모드 id 가 없는 파일(Fabric 모드가 아닌 jar)도 내용이 프로필에 있으면 끄지 않는다.
  const stash = tempDir(t);
  const plain = makeJar(path.join(stash, 'plain.jar'), null);
  const withPlain = receiverWorld(t);
  fs.copyFileSync(plain, path.join(withPlain.modsDir, 'plain.jar'));
  withPlain.jar('delta-1.0.jar', { id: 'delta', name: 'Delta', version: '1.0' });
  const filePath = craftProfile(path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`), { mods: [{ jar: plain, kind: 'modrinth' }] });
  const kept = await applyProfile({ filePath, modManager: withPlain.manager, gameDir: withPlain.gameDir, disableOthers: true });
  assert.deepEqual(kept, { installed: [], present: 1, replaced: [], disabled: ['delta-1.0.jar'], settingsApplied: false });
  assert.deepEqual(Object.keys(tree(withPlain.modsDir)), [CAFETERIA_JAR, 'delta-1.0.jar.disabled', FABRIC_API_JAR, 'plain.jar']);
});

test('하나라도 준비하지 못하면 아무것도 바꾸지 않는다', async (t) => {
  const sender = senderWorld(t);
  const e = sender.jar('epsilon-1.0.jar', { id: 'epsilon', name: 'Epsilon', version: '1.0' });
  sender.site.add(e, { projectId: 'PE', versionId: 'VE' });
  await exportFrom(sender);
  // 받는 쪽: 예전 버전의 B, 프로필에 없는 D, 그리고 프로필의 E 를 꺼 둔 채로 가지고 있다. A 는 없다.
  const receiver = await busyReceiver(t, sender);
  fs.rmSync(path.join(receiver.modsDir, 'alpha-1.0.jar.disabled'));
  fs.copyFileSync(e, path.join(receiver.modsDir, 'epsilon-1.0.jar.disabled'));
  // A 를 모르는 Modrinth (그 파일이 더는 없다).
  const emptySite = fakeModrinth();

  // 이미 있는 E 를 켜는 것도, 준비가 전부 끝난 뒤에 한다. 꺼져 있던 것은 꺼진 채 그대로다.
  await assertRefused(
    receiver,
    () => applyTo(receiver, sender, { disableOthers: true, identifyFiles: emptySite.identifyFiles, download: emptySite.download }),
    errorWith('PROFILE_MODS_UNAVAILABLE', { include: ['아무것도 바꾸지 않았습니다', 'alpha-1.0.jar'], exclude: ['beta-2.0.jar', 'epsilon-1.0.jar'] }),
  );
  assert.ok(fs.existsSync(path.join(receiver.modsDir, 'epsilon-1.0.jar.disabled')));
  assert.deepEqual(emptySite.lookups, [[sha1OfFile(sender.files.a)]]);
  assert.equal(emptySite.downloads.length, 0);
});

test('설정 적용', async (t) => {
  const sender = senderWorld(t);
  writeGameSettings(sender.gameDir, SETTINGS);
  await exportFrom(sender, { includeSettings: true });
  const old = { modules: { fullbright: { enabled: false } } };

  const applying = receiverWorld(t);
  const settingsFile = writeGameSettings(applying.gameDir, old);
  assert.equal((await applyTo(applying, sender, { applySettings: true })).settingsApplied, true);
  assert.deepEqual(JSON.parse(fs.readFileSync(settingsFile, 'utf8')), SETTINGS);

  const keeping = receiverWorld(t);
  const untouched = writeGameSettings(keeping.gameDir, old);
  assert.equal((await applyTo(keeping, sender, { applySettings: false })).settingsApplied, false);
  assert.deepEqual(JSON.parse(fs.readFileSync(untouched, 'utf8')), old);
  assert.deepEqual(fs.readdirSync(path.dirname(untouched)), ['cafeteria.json']);

  // 설정이 들어 있지 않은 프로필은 적용해 달라고 해도 설정을 건드리지 않는다.
  const plain = await exportedProfile(t);
  const noSettings = receiverWorld(t);
  const kept = writeGameSettings(noSettings.gameDir, old);
  assert.equal((await applyTo(noSettings, plain, { applySettings: true })).settingsApplied, false);
  assert.deepEqual(JSON.parse(fs.readFileSync(kept, 'utf8')), old);
});

test('악성 프로필을 거절한다', async (t) => {
  const stash = tempDir(t);
  const filePath = path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`);
  const good = makeJar(path.join(stash, 'good-1.0.jar'), { id: 'good', name: 'Good', version: '1.0' });
  const other = makeJar(path.join(stash, 'other-1.0.jar'), { id: 'other', name: 'Other', version: '1.0' });
  const listed = (fields) => ({ kind: 'modrinth', fileName: 'listed.jar', sha1: 'a'.repeat(40), size: 1, ...fields });
  const many = (count) => Array.from({ length: count }, (_, index) => listed({ fileName: `mod-${index}.jar`, sha1: index.toString(16).padStart(40, '0') }));

  const receiver = receiverWorld(t);
  receiver.jar('delta-1.0.jar', { id: 'delta', name: 'Delta', version: '1.0' });
  const site = fakeModrinth();
  const attempt = () => applyProfile({
    filePath,
    modManager: receiver.manager,
    gameDir: receiver.gameDir,
    disableOthers: true,
    applySettings: true,
    identifyFiles: site.identifyFiles,
    download: site.download,
  });

  // 아래 경우들이 "프로필을 만드는 도우미가 틀려서" 거절되는 것이 아님을 먼저 확인한다: 어긋난 데가 없으면 읽힌다.
  // 모드 300개, 파일 이름 255자까지는 된다.
  craftProfile(filePath, { mods: [{ jar: good }, listed({ fileName: `${'n'.repeat(251)}.jar` }), ...many(298)] });
  assert.equal((await inspectProfile({ filePath, modManager: receiver.manager })).mods.length, 300);

  const cases = {
    // 계획의 표에 있는 경우
    "fileName: '../evil.jar'": { mods: [{ jar: good, fileName: '../evil.jar' }] },
    "fileName: 'a/b.jar' (zip 에 mods/a/b.jar 가 있어도)": { mods: [{ jar: good, fileName: 'a/b.jar' }] },
    'file 항목인데 zip 에 mods/<fileName> 이 없음': { mods: [{ jar: good, entryName: null }] },
    '적힌 size 와 zip 의 크기가 다름': { mods: [{ jar: good, size: sizeOf(good) + 1 }] },
    'sha1 이 39자': { mods: [{ jar: good, sha1: sha1OfFile(good).slice(1) }] },
    "fileName: 'cafeteria-client-9.jar'": { mods: [{ jar: good, fileName: 'cafeteria-client-9.jar' }] },
    '모드 301개': { mods: many(301) },
    "minecraft: '1.20.1'": { mods: [{ jar: good }], manifest: { minecraft: '1.20.1' } },
    'type 다름': { mods: [{ jar: good }], manifest: { type: 'cafeteria-settings' } },
    'format: 2': { mods: [{ jar: good }], manifest: { format: 2 } },
    'profile.json 없음': { mods: [{ jar: good }], manifest: null },
    '같은 fileName 두 번': { mods: [listed({ sha1: 'a'.repeat(40) }), listed({ sha1: 'b'.repeat(40) })] },
    '대소문자만 다른 fileName 두 개': { mods: [listed({ fileName: 'Mod.jar', sha1: 'a'.repeat(40) }), listed({ fileName: 'mod.jar', sha1: 'b'.repeat(40) })] },
    '같은 sha1 두 번': { mods: [listed({ fileName: 'one.jar' }), listed({ fileName: 'two.jar' })] },

    // 읽기 규칙의 나머지
    "fileName: 'a\\\\b.jar'": { mods: [listed({ fileName: 'a\\b.jar' })] },
    "fileName: 'C:evil.jar'": { mods: [listed({ fileName: 'C:evil.jar' })] },
    'fileName 이 .jar 로 끝나지 않음': { mods: [listed({ fileName: 'mod.jar.disabled' })] },
    'fileName 이 글자가 아님': { mods: [listed({ fileName: 7 })] },
    'fileName 이 255자보다 김 (파일로 만들 수 없는 이름)': { mods: [listed({ fileName: `${'n'.repeat(252)}.jar` })] },
    "fileName: 'Cafeteria-Client-9.jar' (Windows 에서는 같은 이름)": { mods: [listed({ fileName: 'Cafeteria-Client-9.jar' })] },
    'kind 가 다른 값': { mods: [listed({ kind: 'url' })] },
    'sha1 이 16진수가 아님': { mods: [listed({ sha1: 'g'.repeat(40) })] },
    'sha1 이 글자가 아님': { mods: [listed({ sha1: 12345 })] },
    'size 가 0': { mods: [listed({ size: 0 })] },
    'size 가 정수가 아님': { mods: [listed({ size: 1.5 })] },
    'size 가 글자': { mods: [listed({ size: '1' })] },
    '목록의 항목이 객체가 아님': { manifest: { mods: [null] } },
    'mods 가 배열이 아님': { manifest: { mods: { length: 0 } } },
    'profile.json 이 객체가 아님': { entries: { 'profile.json': '[]' }, manifest: null },
    'profile.json 이 null': { entries: { 'profile.json': 'null' }, manifest: null },
    'profile.json 이 JSON 이 아님': { entries: { 'profile.json': '{ 깨진 파일' }, manifest: null },
    'hasSettings 인데 설정 파일이 없음': { manifest: { hasSettings: true } },
    'hasSettings 인데 설정처럼 생기지 않음': { manifest: { hasSettings: true }, entries: { 'config/cafeteria.json': '{"modules":[]}' } },
    'hasSettings 인데 JSON 이 아님': { manifest: { hasSettings: true }, entries: { 'config/cafeteria.json': '{ 깨진 파일' } },
  };

  for (const [name, options] of Object.entries(cases)) {
    craftProfile(filePath, options);
    await assert.rejects(inspectProfile({ filePath, modManager: receiver.manager }), { code: 'BAD_PROFILE' }, name);
    await assertRefused(receiver, attempt, { code: 'BAD_PROFILE' }, name);
  }

  // zip 이 아닌 파일
  for (const content of ['zip 이 아닌 파일', '']) {
    fs.writeFileSync(filePath, content);
    await assert.rejects(inspectProfile({ filePath, modManager: receiver.manager }), { code: 'BAD_PROFILE' });
    await assertRefused(receiver, attempt, { code: 'BAD_PROFILE' });
  }

  // 다른 Minecraft 버전용이면 그 버전을 알려 준다.
  craftProfile(filePath, { manifest: { minecraft: '1.20.1' } });
  await assert.rejects(inspectProfile({ filePath, modManager: receiver.manager }), errorWith('BAD_PROFILE', { include: ['1.20.1'] }));

  // 거절하는 동안 Modrinth 에 묻거나 받은 것이 없다.
  assert.deepEqual(site.lookups, []);
  assert.deepEqual(site.downloads, []);
});

test('한도를 넘는 프로필을 거절한다', async (t) => {
  const stash = tempDir(t);
  const good = makeJar(path.join(stash, 'good-1.0.jar'), { id: 'good', name: 'Good', version: '1.0' });
  const filePath = craftProfile(path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`), {
    mods: [{ jar: good }],
    manifest: { hasSettings: true },
    entries: { 'config/cafeteria.json': JSON.stringify(SETTINGS) },
  });
  const receiver = receiverWorld(t);
  const entrySize = (name) => new AdmZip(filePath).getEntry(name).header.size;
  const inspect = (limits) => inspectProfile({ filePath, modManager: receiver.manager, limits: { ...LIMITS, ...limits } });
  const exact = {
    fileBytes: sizeOf(filePath),
    manifestBytes: entrySize('profile.json'),
    mods: 1,
    jarBytes: sizeOf(good),
    settingsBytes: entrySize('config/cafeteria.json'),
  };

  // 한도와 딱 같으면 읽는다.
  assert.equal((await inspect(exact)).mods.length, 1);

  for (const key of Object.keys(exact)) {
    const limits = { ...LIMITS, ...exact, [key]: exact[key] - 1 };
    await assert.rejects(inspect(limits), { code: 'BAD_PROFILE' }, key);
    await assertRefused(receiver, () => applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir, limits }), { code: 'BAD_PROFILE' }, key);
  }
});

test('너무 큰 프로필 파일은 메모리에 올리지 않는다', async (t) => {
  const stash = tempDir(t);
  const filePath = craftProfile(path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`));
  const receiver = receiverWorld(t);
  const readFile = t.mock.method(fsp, 'readFile');
  const readsOfProfile = () => readFile.mock.calls.filter((call) => call.arguments[0] === filePath).length;

  await assert.rejects(inspectProfile({ filePath, modManager: receiver.manager, limits: { ...LIMITS, fileBytes: sizeOf(filePath) - 1 } }), { code: 'BAD_PROFILE' });
  assert.equal(readsOfProfile(), 0);

  await inspectProfile({ filePath, modManager: receiver.manager });
  assert.equal(readsOfProfile(), 1, '읽을 수 있는 크기면 한 번만 읽는다');
});

test('항목이 아주 많거나 폴더가 아주 깊은 zip 은 읽지 않는다', async (t) => {
  // adm-zip 은 항목 하나마다, 그리고 이름에 든 폴더마다 큰 객체를 만든다. 그런 zip 은 64KB 로도 메모리를 1GB 넘게 쓰게 한다.
  const stash = tempDir(t);
  const filePath = path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`);
  const receiver = receiverWorld(t);
  const limits = { ...LIMITS, mods: 5 };
  const inspect = () => inspectProfile({ filePath, modManager: receiver.manager, limits });
  const apply = () => applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir, limits });
  const extras = (count) => Object.fromEntries(Array.from({ length: count }, (_, index) => [`extra-${index}.txt`, '읽지 않는 항목']));
  const deep = (folders) => ({ [`${'d/'.repeat(folders)}extra.txt`]: '읽지 않는 항목' });

  // 런처가 만든 프로필의 항목은 profile.json, 설정, 파일째 넣은 모드뿐이다: 많아야 (모드 한도 + 2)개.
  craftProfile(filePath, { entries: extras(6) });
  assert.equal(zipEntryNames(filePath).length, 7);
  assert.deepEqual((await inspect()).mods, []);

  craftProfile(filePath, { entries: extras(7) });
  await assert.rejects(inspect(), { code: 'BAD_PROFILE' });
  await assertRefused(receiver, apply, { code: 'BAD_PROFILE' });

  // 읽지 않는 항목이라도 이름에 폴더가 8단계까지만 있을 수 있다.
  craftProfile(filePath, { entries: deep(8) });
  assert.deepEqual((await inspect()).mods, []);

  craftProfile(filePath, { entries: deep(9) });
  await assert.rejects(inspect(), { code: 'BAD_PROFILE' });
  await assertRefused(receiver, apply, { code: 'BAD_PROFILE' });
});

test('읽지 못하는 파일은 프로필로 보지 않는다', async (t) => {
  const stash = tempDir(t);
  const receiver = receiverWorld(t);

  await assert.rejects(inspectProfile({ filePath: path.join(stash, '없는 파일'), modManager: receiver.manager }), (error) => {
    assert.equal(error.code, 'BAD_PROFILE');
    assert.equal(error.details.cause, 'ENOENT');
    return true;
  });
  // 폴더는 읽어 보지도 않는다. 읽다가 실패한 것이 아니라서 원인이 없다.
  await assert.rejects(inspectProfile({ filePath: stash, modManager: receiver.manager }), { code: 'BAD_PROFILE', details: {} });
});

test('읽은 값은 화면에 쓰기 좋게 다듬는다', async (t) => {
  const stash = tempDir(t);
  const good = makeJar(path.join(stash, 'good-1.0.jar'), { id: 'good', name: 'Good', version: '1.0' });
  const receiver = receiverWorld(t);
  fs.copyFileSync(good, path.join(receiver.modsDir, 'good-1.0.jar'));
  const filePath = craftProfile(path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`), {
    manifest: { name: 12, createdWith: undefined, hasSettings: 'yes' },
    // 해시가 대문자로 적혀 있어도 같은 파일로 알아본다.
    mods: [{ jar: good, kind: 'modrinth', sha1: sha1OfFile(good).toUpperCase(), title: 'T'.repeat(201), version: 3 }],
  });

  const info = await inspectProfile({ filePath, modManager: receiver.manager });

  assert.equal(info.name, 'Cafeteria 프로필');
  assert.equal(info.createdWith, '');
  assert.equal(info.hasSettings, false);
  assert.deepEqual(info.mods, [{ title: 'T'.repeat(200), version: '', fileName: 'good-1.0.jar', kind: 'modrinth', size: sizeOf(good), present: true }]);

  const result = await applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir, applySettings: true });
  assert.deepEqual(result, { installed: [], present: 1, replaced: [], disabled: [], settingsApplied: false });
});

test('목록의 entry 값은 읽지 않는다', async (t) => {
  const root = tempDir(t);
  // mods 폴더에서 ../../ 는 root/outer, mods 안의 임시 폴더에서는 게임 폴더, 게임 폴더에서는 root 다.
  const gameDir = path.join(root, 'outer', 'game');
  const manager = new ModManager(gameDir);
  const stash = tempDir(t);
  const good = makeJar(path.join(stash, 'good-1.0.jar'), { id: 'good', name: 'Good', version: '1.0' });
  const filePath = craftProfile(path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`), {
    mods: [{ jar: good, entry: '../../evil.jar' }],
    // 목록에 없는 zip 항목들. 어느 것도 풀지 않는다.
    entries: { 'xx/xx/evil.jar': '밖으로 나가려는 파일', 'mods/unlisted.jar': '목록에 없는 jar', 'options.txt': '게임 설정' },
    patch: (buffer) => renameZipEntry(buffer, 'xx/xx/evil.jar', '../../evil.jar'),
  });
  assert.deepEqual(zipEntryNames(filePath), ['../../evil.jar', 'mods/good-1.0.jar', 'mods/unlisted.jar', 'options.txt', 'profile.json']);

  const result = await applyProfile({ filePath, modManager: manager, gameDir, disableOthers: true, applySettings: true });

  assert.deepEqual(result, { installed: ['good-1.0.jar'], present: 0, replaced: [], disabled: [], settingsApplied: false });
  assert.deepEqual(tree(root), {
    outer: 'dir',
    'outer/game': 'dir',
    'outer/game/cafeteria-mods.json': sha1OfFile(manager.indexFile),
    'outer/game/mods': 'dir',
    'outer/game/mods/good-1.0.jar': sha1OfFile(good),
  });
});

test('내용이 해시와 다른 jar 를 거절한다', async (t) => {
  const stash = tempDir(t);
  const good = makeJar(path.join(stash, 'good-1.0.jar'), { id: 'good', name: 'Good', version: '1.0' });
  const fine = makeJar(path.join(stash, 'fine-1.0.jar'), { id: 'fine', name: 'Fine', version: '1.0' });
  // 크기는 같고 내용만 다르다.
  const differentHash = sha1(Buffer.alloc(sizeOf(good), 1));
  const filePath = craftProfile(path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`), { mods: [{ jar: good, sha1: differentHash }, { jar: fine }] });
  const receiver = receiverWorld(t);

  await assertRefused(
    receiver,
    () => applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir }),
    errorWith('PROFILE_MODS_UNAVAILABLE', { include: ['good-1.0.jar'], exclude: ['fine-1.0.jar'] }),
  );
});

test('쓰면 안 되는 모드 id 를 가진 jar 를 거절한다', async (t) => {
  const stash = tempDir(t);
  const filePath = path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`);
  const receiver = receiverWorld(t);
  // 기본 모드가 아직 없는 mods 폴더 (런처가 기본 모드를 넣기 전). 여기서도 기본 모드의 id 를 가진 jar 는 받지 않는다.
  const empty = { manager: new ModManager(tempDir(t)) };
  empty.gameDir = empty.manager.gameDir;
  fs.mkdirSync(empty.manager.modsDir);
  assert.deepEqual(RESERVED_MOD_IDS, ['minecraft', 'java', 'fabricloader']);

  for (const id of ['fabric-api', 'cafeteria', 'fastpack', 'minecraft', 'fabricloader', 'java']) {
    const jar = makeJar(path.join(stash, `disguised-${id}.jar`), { id, name: 'Disguised', version: '1.0' });
    craftProfile(filePath, { mods: [{ jar }] });

    for (const { manager, gameDir } of [receiver, empty]) {
      await assertRefused(
        { gameDir },
        () => applyProfile({ filePath, modManager: manager, gameDir }),
        errorWith('PROFILE_MODS_UNAVAILABLE', { include: [`disguised-${id}.jar`] }),
        id,
      );
    }
  }

  // 사이트에서 받는 항목도 받은 jar 를 똑같이 본다.
  const site = fakeModrinth();
  const fromSite = makeJar(path.join(stash, 'from-site.jar'), { id: 'fabric-api', name: 'From Site', version: '9' });
  site.add(fromSite);
  craftProfile(filePath, { mods: [{ jar: fromSite, kind: 'modrinth' }] });

  await assertRefused(
    receiver,
    () => applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir, identifyFiles: site.identifyFiles, download: site.download }),
    errorWith('PROFILE_MODS_UNAVAILABLE', { include: ['from-site.jar'] }),
  );
  assert.equal(site.downloads.length, 1);

  // 받는 쪽에 이미 있는 jar 도 똑같이 본다: 런처가 꺼 둔 겹치는 모드를 프로필이 다시 켜지 못한다.
  const overlapping = receiver.jar('overlap-1.0.jar.disabled', { id: 'fastpack', name: 'Overlap', version: '1.0' });
  craftProfile(filePath, { mods: [{ jar: overlapping, kind: 'modrinth', fileName: 'overlap-1.0.jar' }] });

  await assertRefused(
    receiver,
    () => applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir, identifyFiles: site.identifyFiles, download: site.download }),
    errorWith('PROFILE_MODS_UNAVAILABLE', { include: ['overlap-1.0.jar'] }),
  );
  assert.ok(fs.existsSync(overlapping), '꺼진 채 그대로 있어야 한다');
});

test('프로필 안에 같은 모드가 둘이면 거절한다', async (t) => {
  const stash = tempDir(t);
  const first = makeJar(path.join(stash, 'twin-1.0.jar'), { id: 'twin', name: 'Twin', version: '1.0' });
  const second = makeJar(path.join(stash, 'twin-2.0.jar'), { id: 'twin', name: 'Twin', version: '2.0' });
  const single = makeJar(path.join(stash, 'single-1.0.jar'), { id: 'single', name: 'Single', version: '1.0' });
  // Fabric 모드가 아닌 파일은 모드 id 가 없다. 둘이 있어도 "같은 모드"가 아니다.
  const plainOne = makeJar(path.join(stash, 'plain-1.jar'), null);
  const plainTwo = path.join(stash, 'plain-2.jar');
  fs.writeFileSync(plainTwo, 'jar 가 아닌 내용');
  const filePath = path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`);
  const refused = errorWith('PROFILE_MODS_UNAVAILABLE', { include: ['twin-1.0.jar', 'twin-2.0.jar'], exclude: ['single-1.0.jar', 'plain-1.jar', 'plain-2.jar'] });

  const receiver = receiverWorld(t);
  craftProfile(filePath, { mods: [{ jar: first }, { jar: second }, { jar: single }, { jar: plainOne }, { jar: plainTwo }] });
  await assertRefused(receiver, () => applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir }), refused);

  // 하나는 받는 쪽에 이미 있는 것이어도 같다.
  fs.copyFileSync(first, path.join(receiver.modsDir, 'twin-1.0.jar'));
  await assertRefused(receiver, () => applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir }), refused);

  // 겹치는 것이 없으면 모드 id 가 없는 파일 둘도 함께 들어간다.
  craftProfile(filePath, { mods: [{ jar: second }, { jar: single }, { jar: plainOne }, { jar: plainTwo }] });
  const result = await applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir });
  assert.deepEqual(result.installed.sort(), ['plain-1.jar', 'plain-2.jar', 'single-1.0.jar', 'twin-2.0.jar']);
  assert.deepEqual(result.replaced, ['twin-1.0.jar → twin-2.0.jar']);
});

test('Modrinth 버전에 파일이 여럿이면 해시가 같은 것을 받는다', async (t) => {
  const sender = senderWorld(t);
  const otherFile = { filename: 'alpha-1.0-sources.jar', url: 'https://cdn.modrinth.com/data/PA/versions/VA/alpha-1.0-sources.jar', primary: true, hashes: { sha1: 'f'.repeat(40), sha512: '0'.repeat(128) } };
  const urlOfA = sender.site.add(sender.files.a, { projectId: 'PA', versionId: 'VA', otherFiles: [otherFile] });
  await exportFrom(sender);
  const receiver = receiverWorld(t);

  // 내보낼 때도 버전의 파일 중 해시가 같은 것이 있으면 Modrinth 에 있는 모드로 본다.
  assert.equal(manifestOf(sender.filePath).mods[0].kind, 'modrinth');

  const result = await applyTo(receiver, sender);

  assert.deepEqual(sender.site.downloads.map((download) => download.url), [urlOfA]);
  assert.deepEqual(result.installed.sort(), ['alpha-1.0.jar', 'beta-2.0.jar']);

  // 버전은 알지만 그 해시의 파일이 버전에 없으면 받을 수 없다 (첫 번째 파일을 대신 받지 않는다).
  const mismatched = fakeModrinth();
  mismatched.identifyFiles = async (hashes) => Object.fromEntries(hashes.map((hash) => [hash, { id: 'VA', project_id: 'PA', files: [otherFile] }]));
  const untouched = receiverWorld(t);
  await assertRefused(untouched, () => applyTo(untouched, sender, { identifyFiles: mismatched.identifyFiles, download: mismatched.download }), {
    code: 'PROFILE_MODS_UNAVAILABLE',
  });
  assert.equal(mismatched.downloads.length, 0);
});

test('옮기는 도중 실패하면 되돌린다', async (t) => {
  const profile = await exportedProfile(t);

  // 실패하지 않으면 파일을 네 번 옮긴다.
  const counting = failingRename({});
  const succeeded = await applyTo(await busyReceiver(t, profile), profile, { disableOthers: true, rename: counting });
  assert.deepEqual(succeeded, { installed: ['beta-2.0.jar'], present: 1, replaced: ['beta-1.0.jar → beta-2.0.jar'], disabled: ['delta-1.0.jar'], settingsApplied: false });
  assert.equal(counting.calls, 4);

  for (let at = 1; at <= counting.calls; at += 1) {
    const receiver = await busyReceiver(t, profile);
    const rename = failingRename({ at });

    // 게임 폴더를 통째로 비교한다: mods 폴더의 이름과 내용, 임시 폴더, 모드 기록(cafeteria-mods.json).
    await assertRefused(
      receiver,
      () => applyTo(receiver, profile, { disableOthers: true, rename }),
      { code: 'FILE_IN_USE', message: '모드 파일을 바꿀 수 없습니다. 게임이 켜져 있으면 끄고 다시 시도해 주세요.' },
      `${at}번째에서 실패`,
    );
    // 실패한 것 앞까지 옮긴 만큼(at - 1) 되돌렸다.
    assert.equal(rename.calls, at + (at - 1), `${at}번째에서 실패`);
  }
});

test('파일이 잠긴 것이 아닌 실패는 되돌린 뒤 그대로 알린다', async (t) => {
  const profile = await exportedProfile(t);
  const receiver = await busyReceiver(t, profile);
  let calls = 0;
  const rename = async (source, target) => {
    calls += 1;

    if (calls === 3) {
      throw Object.assign(new Error('ENOSPC: no space left on device'), { code: 'ENOSPC' });
    }

    return fsp.rename(source, target);
  };

  await assertRefused(receiver, () => applyTo(receiver, profile, { disableOthers: true, rename }), { code: 'ENOSPC' });
});

test('되돌리기도 실패하면 원래 파일이 있는 곳을 알려 준다', async (t) => {
  const profile = await exportedProfile(t);
  const receiver = await busyReceiver(t, profile);
  const hashOfOldB = sha1OfFile(receiver.oldB);

  const error = await applyTo(receiver, profile, { disableOthers: true, rename: failingRename({ from: 2 }) }).then(
    () => assert.fail('실패해야 한다'),
    (thrown) => thrown,
  );

  assert.equal(error.code, 'PROFILE_ROLLBACK_FAILED');
  const [temporary] = fs.readdirSync(receiver.modsDir).filter((name) => name.startsWith(TEMP_PREFIX));
  assert.ok(temporary, '임시 폴더가 남아 있어야 한다');
  assert.ok(error.message.includes(temporary), error.message);
  // 먼저 옮겨 둔 원래 파일이 그 안에 있다.
  assert.equal(sha1OfFile(path.join(receiver.modsDir, temporary, 'replaced', 'beta-1.0.jar')), hashOfOldB);
  assert.equal(fs.existsSync(receiver.oldB), false);
});

test('설정을 쓰지 못하면 모드도 되돌린다', async (t) => {
  const sender = senderWorld(t);
  writeGameSettings(sender.gameDir, SETTINGS);
  await exportFrom(sender, { includeSettings: true });
  const receiver = await busyReceiver(t, sender);
  // 설정 파일 자리에 폴더가 있다.
  fs.mkdirSync(path.join(receiver.gameDir, 'config', 'cafeteria.json'), { recursive: true });

  await assertRefused(receiver, () => applyTo(receiver, sender, { disableOthers: true, applySettings: true }), { code: 'SETTINGS_WRITE_FAILED' });
});

test('Modrinth 에 묻지 못하면 적용하지 않는다', async (t) => {
  const profile = await exportedProfile(t);
  const receiver = await busyReceiver(t, profile);
  fs.rmSync(path.join(receiver.modsDir, 'alpha-1.0.jar.disabled'));
  const identifyFiles = async () => {
    throw new Error('연결 끊김');
  };

  await assertRefused(receiver, () => applyTo(receiver, profile, { disableOthers: true, identifyFiles }), {
    code: 'PROFILE_LOOKUP_FAILED',
    message: 'Modrinth 에서 모드 정보를 확인하지 못했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요.',
  });
  assert.equal(profile.site.downloads.length, 0);
});

test('받다가 실패하면 아무것도 바꾸지 않는다', async (t) => {
  const sender = senderWorld(t);
  const e = sender.jar('epsilon-1.0.jar', { id: 'epsilon', name: 'Epsilon', version: '1.0' });
  const urlOfE = sender.site.add(e, { projectId: 'PE', versionId: 'VE' });
  await exportFrom(sender);
  assert.deepEqual(manifestOf(sender.filePath).mods.map((mod) => mod.kind), ['modrinth', 'file', 'modrinth']);
  const receiver = await busyReceiver(t, sender);
  fs.rmSync(path.join(receiver.modsDir, 'alpha-1.0.jar.disabled'));
  sender.site.failingUrls.push(sender.urlOfA);

  await assertRefused(
    receiver,
    () => applyTo(receiver, sender, { disableOthers: true }),
    errorWith('PROFILE_MODS_UNAVAILABLE', { include: ['alpha-1.0.jar'], exclude: ['epsilon-1.0.jar', 'beta-2.0.jar'] }),
  );
  // A 를 받지 못해도 나머지는 계속 준비한다.
  assert.deepEqual(sender.site.downloads.map((download) => download.url), [sender.urlOfA, urlOfE]);
});

test('풀린 크기를 속인 zip 항목을 거절한다', async (t) => {
  const stash = tempDir(t);
  const filePath = path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`);
  const receiver = receiverWorld(t);
  // 잘 눌리는 내용. 머리에는 1000바이트라고 적고, 목록에도 그 크기와 "앞 1000바이트"의 해시를 적는다:
  // 적힌 크기에서 잘라 내기만 하는 구현이라면 크기도 해시도 맞아 보인다.
  const realSize = 123457;
  const claimedSize = 1000;
  const data = Buffer.alloc(realSize, 'a');
  const lying = (stored) => ({
    mods: [{ data, stored, fileName: 'bomb.jar', sha1: sha1(data.subarray(0, claimedSize)), size: claimedSize }],
    patch: (buffer) => patchUncompressedSize(buffer, 'mods/bomb.jar', claimedSize),
  });
  const attempt = () => applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir });
  const refused = errorWith('PROFILE_MODS_UNAVAILABLE', { include: ['bomb.jar'] });

  // 압축한 항목: adm-zip 이 적힌 크기까지만 풀고 던진다. 이 동작에 기대고 있으므로 adm-zip 을 바꿀 때 알 수 있게 직접 확인해 둔다.
  craftProfile(filePath, lying(false));
  const deflated = new AdmZip(filePath).getEntry('mods/bomb.jar');
  assert.deepEqual([deflated.header.method, deflated.header.size], [8, claimedSize]);
  assert.throws(() => deflated.getData(), { code: 'ERR_BUFFER_TOO_LARGE' });
  await assertRefused(receiver, attempt, refused, '압축한 항목');

  // 압축하지 않은 항목: adm-zip 은 적힌 크기와 상관없이 zip 에 든 바이트를 그대로 돌려준다. 꺼내기 전에 막아야 한다.
  craftProfile(filePath, lying(true));
  const stored = new AdmZip(filePath).getEntry('mods/bomb.jar');
  assert.deepEqual([stored.header.method, stored.header.size, stored.header.compressedSize], [0, claimedSize, realSize]);
  assert.equal(stored.getData().length, realSize);

  // 적힌 크기보다 큰 버퍼를 만들지 않는지 본다 (adm-zip 은 꺼낼 때 Buffer.alloc 으로 그 크기의 버퍼를 만든다).
  const alloc = t.mock.method(Buffer, 'alloc');
  await assertRefused(receiver, attempt, refused, '압축하지 않은 항목');
  assert.deepEqual(alloc.mock.calls.map((call) => call.arguments[0]).filter((size) => size >= realSize), []);
  alloc.mock.restore();
});

test('고른 뒤 파일이 바뀌면 적용하지 않는다', async (t) => {
  const profile = await exportedProfile(t);
  const receiver = await busyReceiver(t, profile);
  const { fingerprint } = await inspectProfile({ filePath: profile.filePath, modManager: receiver.manager });

  // 사용자가 요약을 보는 사이에 파일이 다른 (올바른) 프로필로 바뀐다.
  const swapped = senderWorld(t);
  swapped.jar('zeta-1.0.jar', { id: 'zeta', name: 'Zeta', version: '1.0' });
  await exportFrom(swapped, { filePath: profile.filePath });

  await assertRefused(receiver, () => applyTo(receiver, profile, { disableOthers: true, expectedFingerprint: fingerprint }), {
    code: 'PROFILE_CHANGED',
    message: '프로필 파일이 고른 뒤에 바뀌었습니다. 다시 골라 주세요.',
  });

  // 지금 파일을 다시 확인한 것이면 적용된다.
  const current = await inspectProfile({ filePath: profile.filePath, modManager: receiver.manager });
  assert.notEqual(current.fingerprint, fingerprint);
  const result = await applyTo(receiver, swapped, { filePath: profile.filePath, expectedFingerprint: current.fingerprint });
  assert.deepEqual(result.installed.sort(), ['beta-2.0.jar', 'zeta-1.0.jar']);
});

test('inspectProfile 은 있는 것과 없는 것을 구분한다', async (t) => {
  const sender = senderWorld(t);
  writeGameSettings(sender.gameDir, SETTINGS);
  await exportFrom(sender, { includeSettings: true });
  const receiver = receiverWorld(t);
  // 꺼져 있어도, 이름이 달라도 내용이 같으면 있는 것이다.
  fs.copyFileSync(sender.files.a, path.join(receiver.modsDir, 'my-alpha.jar.disabled'));
  const before = tree(receiver.gameDir);

  const info = await inspectProfile({ filePath: sender.filePath, modManager: receiver.manager });

  assert.deepEqual(info, {
    name: '내 PvP 세팅',
    createdWith: '1.5.0',
    hasSettings: true,
    fingerprint: crypto.createHash('sha256').update(fs.readFileSync(sender.filePath)).digest('hex'),
    mods: [
      { title: 'Alpha', version: '1.0', fileName: 'alpha-1.0.jar', kind: 'modrinth', size: sizeOf(sender.files.a), present: true },
      { title: 'Beta', version: '2.0', fileName: 'beta-2.0.jar', kind: 'file', size: sizeOf(sender.files.b), present: false },
    ],
  });
  assert.match(info.fingerprint, /^[0-9a-f]{64}$/);
  assert.equal((await inspectProfile({ filePath: sender.filePath, modManager: receiver.manager })).fingerprint, info.fingerprint);
  assert.deepEqual(tree(receiver.gameDir), before);
});

test('기본 모드 파일의 자리를 차지하려는 jar 를 거절한다', async (t) => {
  const stash = tempDir(t);
  const filePath = path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`);
  const impostor = makeJar(path.join(stash, 'impostor.jar'), { id: 'impostor', name: 'Impostor', version: '1.0' });
  const receiver = receiverWorld(t);

  // 모드 id 는 평범하지만 이름이 받는 쪽의 Fabric API 파일과 같다 (대소문자만 다른 것도 Windows 에서는 같은 파일이다).
  for (const fileName of [FABRIC_API_JAR, FABRIC_API_JAR.toUpperCase().replace('.JAR', '.jar')]) {
    craftProfile(filePath, { mods: [{ jar: impostor, fileName }] });

    await assertRefused(
      receiver,
      () => applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir }),
      errorWith('PROFILE_MODS_UNAVAILABLE', { include: [fileName] }),
      fileName,
    );
  }
});

test('프로필이 그대로 쓰는 파일의 자리를 다른 모드에 내주지 않는다', async (t) => {
  const stash = tempDir(t);
  const kept = makeJar(path.join(stash, 'kept-1.0.jar'), { id: 'kept', name: 'Kept', version: '1.0' });
  const taker = makeJar(path.join(stash, 'taker-1.0.jar'), { id: 'taker', name: 'Taker', version: '1.0' });
  const receiver = receiverWorld(t);
  // 받는 쪽은 프로필의 첫 번째 모드를, 두 번째 모드가 쓰려는 이름으로 가지고 있다.
  fs.copyFileSync(kept, path.join(receiver.modsDir, 'shared.jar'));
  const filePath = craftProfile(path.join(stash, `받은 프로필.${PROFILE_EXTENSION}`), {
    mods: [{ jar: kept, kind: 'modrinth' }, { jar: taker, fileName: 'shared.jar' }],
  });

  // 그대로 바꾸면 첫 번째 모드가 사라진 채 "적용했다"고 알리게 된다.
  await assertRefused(
    receiver,
    () => applyProfile({ filePath, modManager: receiver.manager, gameDir: receiver.gameDir }),
    errorWith('PROFILE_MODS_UNAVAILABLE', { include: ['shared.jar'], exclude: ['kept-1.0.jar'] }),
  );
});

test('켤 자리와 끌 자리에 있던 파일도 지우지 않고 치워 두었다가, 실패하면 되돌린다', async (t) => {
  const profile = await exportedProfile(t);

  function world() {
    const receiver = receiverWorld(t);
    // 프로필의 A 는 꺼져 있고, 켠 이름은 같은 모드의 다른 버전이 쓰고 있다.
    fs.copyFileSync(profile.files.a, path.join(receiver.modsDir, 'alpha-1.0.jar.disabled'));
    receiver.otherAlpha = receiver.jar('alpha-1.0.jar', { id: 'alpha', name: 'Alpha', version: '0.9' });
    // 프로필에 없는 D 는 켜져 있고, 끈 이름은 예전에 꺼 둔 다른 파일이 쓰고 있다.
    receiver.delta = receiver.jar('delta-1.0.jar', { id: 'delta', name: 'Delta', version: '1.0' });
    receiver.jar('delta-1.0.jar.disabled', { id: 'delta', name: 'Delta', version: '0.5' });
    return receiver;
  }

  const counting = failingRename({});
  const receiver = world();
  const hashOfDelta = sha1OfFile(receiver.delta);
  const recordOfDelta = { source: 'curseforge', projectId: '111', versionId: '1000', title: 'Delta' };
  await receiver.manager.record('delta-1.0.jar', recordOfDelta);
  const result = await applyTo(receiver, profile, { disableOthers: true, rename: counting });

  assert.deepEqual(result, {
    installed: ['beta-2.0.jar'],
    present: 1,
    replaced: ['alpha-1.0.jar → alpha-1.0.jar', 'delta-1.0.jar.disabled → delta-1.0.jar.disabled'],
    disabled: ['delta-1.0.jar'],
    settingsApplied: false,
  });
  assert.deepEqual(tree(receiver.modsDir), {
    'alpha-1.0.jar': sha1OfFile(profile.files.a),
    'beta-2.0.jar': sha1OfFile(profile.files.b),
    [CAFETERIA_JAR]: sha1OfFile(path.join(receiver.modsDir, CAFETERIA_JAR)),
    'delta-1.0.jar.disabled': hashOfDelta,
    [FABRIC_API_JAR]: sha1OfFile(path.join(receiver.modsDir, FABRIC_API_JAR)),
  });
  // 출처 기록은 파일 이름으로 찾는다. 꺼 둔 D 가 그 이름을 계속 쓰므로, 자리를 내준 파일 때문에 D 의 기록을 지우면 안 된다.
  assert.deepEqual(readIndex(receiver.manager)['delta-1.0.jar'], recordOfDelta);

  // 마지막으로 옮기다 실패하면 치워 둔 두 파일까지 제자리로 돌아온다.
  const failing = world();
  await assertRefused(failing, () => applyTo(failing, profile, { disableOthers: true, rename: failingRename({ at: counting.calls }) }), { code: 'FILE_IN_USE' });
});

test('옮길 자리에 모르는 것이 있으면 덮어쓰지 않는다', async (t) => {
  const profile = await exportedProfile(t);

  // mods 폴더 목록에는 나오지 않는 것(폴더)이 새 파일의 자리에 있다: 바꾸기 전에 알아챈다.
  const blocked = receiverWorld(t);
  fs.mkdirSync(path.join(blocked.modsDir, 'beta-2.0.jar'));
  await assertRefused(blocked, () => applyTo(blocked, profile), errorWith('PROFILE_MODS_UNAVAILABLE', { include: ['beta-2.0.jar'], exclude: ['alpha-1.0.jar'] }));

  // 임시 폴더에서 준비할 자리에 이미 무언가 있다 (파일 시스템이 앞서 준비한 파일과 같은 이름으로 보는 경우를 흉내 낸다).
  const crowded = receiverWorld(t);
  const download = async (url, destination, options) => {
    await profile.site.download(url, destination, options);
    fs.writeFileSync(path.join(path.dirname(destination), 'beta-2.0.jar'), '먼저 자리를 차지한 파일');
  };
  await assertRefused(crowded, () => applyTo(crowded, profile, { download }), errorWith('PROFILE_MODS_UNAVAILABLE', { include: ['beta-2.0.jar'], exclude: ['alpha-1.0.jar'] }));

  // 바꾸는 도중에 그 자리에 파일이 생긴다 (첫 번째로 옮길 때 다른 프로그램이 끼어든 것처럼).
  const receiver = await busyReceiver(t, profile);
  const intruder = path.join(receiver.modsDir, 'beta-2.0.jar');
  const before = tree(receiver.gameDir);
  let calls = 0;
  const rename = async (source, target) => {
    calls += 1;

    if (calls === 1) {
      fs.writeFileSync(intruder, '끼어든 파일');
    }

    return fsp.rename(source, target);
  };

  await assert.rejects(applyTo(receiver, profile, { disableOthers: true, rename }), errorWith('PROFILE_TARGET_EXISTS', { include: ['beta-2.0.jar'] }));
  // 끼어든 파일은 그대로이고, 나머지는 적용 전으로 돌아갔다.
  assert.equal(fs.readFileSync(intruder, 'utf8'), '끼어든 파일');
  fs.rmSync(intruder);
  assert.deepEqual(tree(receiver.gameDir), before);
});

test('Modrinth 실제 서버로 왕복', live, async (t) => {
  const sender = new ModManager(tempDir(t));
  const receiver = new ModManager(tempDir(t));
  const filePath = path.join(tempDir(t), `실제 서버.${PROFILE_EXTENSION}`);
  // modrinth.test.js 의 실제 서버 테스트도 쓰는 Sodium Extra (작은 모드).
  const file = await modrinth.resolveFile('PtjYWJkn');
  await http.download(file.url, path.join(sender.modsDir, file.fileName), { allowedHosts: DOWNLOAD_HOSTS, hashes: file.hashes });

  const exported = await exportProfile({
    modManager: sender,
    gameDir: sender.gameDir,
    filePath,
    name: '실제 서버',
    includeSettings: false,
    appVersion: '1.5.0',
    identifyFiles: modrinth.identifyFiles,
  });
  assert.deepEqual(exported, { mods: 1, embedded: [], hasSettings: false });
  assert.deepEqual(zipEntryNames(filePath), ['profile.json']);

  // download 를 주지 않으면 실제 http.download 로 받는다.
  const applied = await applyProfile({ filePath, modManager: receiver, gameDir: receiver.gameDir, disableOthers: false, applySettings: false, identifyFiles: modrinth.identifyFiles });

  assert.deepEqual(applied, { installed: [file.fileName], present: 0, replaced: [], disabled: [], settingsApplied: false });
  assert.deepEqual(fs.readdirSync(receiver.modsDir), [file.fileName]);
  assert.equal(sha1OfFile(path.join(receiver.modsDir, file.fileName)), file.hashes.sha1);
  assert.equal(sha1OfFile(path.join(receiver.modsDir, file.fileName)), sha1OfFile(path.join(sender.modsDir, file.fileName)));
  const record = readIndex(receiver)[file.fileName];
  assert.deepEqual([record.source, record.projectId, record.versionId], ['modrinth', 'PtjYWJkn', file.versionId]);
});
