'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const importer = require('../../src/main/importer');
const { tempDir } = require('./helpers');

function write(root, relative, content = 'x') {
  const file = path.join(root, relative);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
}

function listFiles(root) {
  const files = [];
  const walk = (directory) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const full = path.join(directory, entry.name);

      if (entry.isDirectory()) {
        walk(full);
      } else {
        files.push(path.relative(root, full).split(path.sep).join('/'));
      }
    }
  };

  if (fs.existsSync(root)) {
    walk(root);
  }

  return files.sort();
}

/** 공식 런처와 루나 클라이언트를 쓰던 PC를 흉내 낸 홈 폴더. */
function setup(t) {
  const home = tempDir(t);
  const minecraft = path.join(home, '.minecraft');
  write(minecraft, 'options.txt', 'mouseSensitivity:0.3\n');
  write(minecraft, 'servers.dat', 'servers');
  write(minecraft, 'config/xaero/minimap/client.cfg', 'current_profile = default');
  write(minecraft, 'config/xaero/minimap/profiles/default.cfg', 'size = 2');
  write(minecraft, 'config/xaerohud.txt', 'hud');
  write(minecraft, 'config/sodium-options.json', '{}');
  write(minecraft, 'config/cafeteria.json', '{"다른 PC의":"Cafeteria 설정"}');
  write(minecraft, 'xaero/minimap/Multiplayer_play.example.com/dim%0/mw$default_1.txt', 'waypoint:home');
  write(minecraft, 'xaero/world-map/Multiplayer_play.example.com/null/region.zip', 'map');
  write(minecraft, 'resourcepacks/pvp-pack.zip', 'pack');
  write(minecraft, 'saves/My World/level.dat', 'level');
  write(minecraft, 'mods/old-mod.jar', 'jar');
  write(minecraft, 'screenshots/1.png', 'png');

  const lunar = path.join(home, '.lunarclient');
  write(lunar, 'settings/game/default/mods.json', '{}');
  // 루나가 따로 쓰는 게임 폴더 (예전 Xaero 버전의 폴더 구조)
  write(lunar, 'profiles/lunar/1.21/config/xaerominimap.txt', 'old minimap config');
  write(lunar, 'profiles/lunar/1.21/XaeroWaypoints/Multiplayer_old/waypoints.txt', 'old waypoint');
  // 훑지 않아야 하는 곳
  write(lunar, 'jre/options.txt', 'not a game dir');
  write(lunar, 'offline/multiver/cache/options.txt', 'not a game dir');

  const gameDir = path.join(home, '.cafeteria');
  return { home, minecraft, lunar, gameDir, options: { gameDir, home, platform: 'linux', env: {} } };
}

test('기본 마인크래프트 폴더와 루나 폴더에서 가져올 수 있는 설정을 찾는다', async (t) => {
  const { minecraft, lunar, options } = setup(t);
  const result = await importer.findSources(options);

  assert.deepEqual(result.sources.map((source) => source.directory), [minecraft, path.join(lunar, 'profiles', 'lunar', '1.21')]);
  assert.equal(result.sources[0].label, '기본 마인크래프트 폴더 (.minecraft)');
  assert.equal(result.sources[1].label, `루나 클라이언트 폴더 (${path.join('profiles', 'lunar', '1.21')})`);
  assert.equal(result.lunarDirectory, lunar);
  assert.equal(result.lunarSettingsDirectory, path.join(lunar, 'settings'));

  const items = Object.fromEntries(result.sources[0].items.map((item) => [item.id, item]));
  assert.deepEqual(Object.keys(items), ['options', 'servers', 'xaero-config', 'xaero-data', 'mod-config', 'resourcepacks', 'saves']);
  assert.deepEqual(items['xaero-config'].paths, [path.join('config', 'xaero'), path.join('config', 'xaerohud.txt')]);
  assert.equal(items['xaero-config'].files, 3);
  assert.deepEqual(items['xaero-data'].paths, ['xaero']);
  assert.equal(items['xaero-data'].files, 2);
  assert.deepEqual(items['mod-config'].paths, [path.join('config', 'sodium-options.json')], 'xaero 와 cafeteria.json 은 "다른 모드 설정"에 넣지 않는다');
  assert.equal(items.options.bytes, 'mouseSensitivity:0.3\n'.length);
  assert.deepEqual(
    result.sources[0].items.filter((item) => item.selected).map((item) => item.id),
    ['options', 'servers', 'xaero-config', 'xaero-data', 'resourcepacks'],
    '용량이 크거나 조건이 붙는 항목은 기본으로 고르지 않는다',
  );

  // 예전 폴더 구조도 Xaero 항목으로 잡힌다
  assert.deepEqual(result.sources[1].items.map((item) => [item.id, item.paths]), [
    ['xaero-config', [path.join('config', 'xaerominimap.txt')]],
    ['xaero-data', ['XaeroWaypoints']],
  ]);
});

test('루나가 없고 .minecraft 도 없으면 빈 결과', async (t) => {
  const home = tempDir(t);
  const result = await importer.findSources({ gameDir: path.join(home, '.cafeteria'), home, platform: 'linux', env: {} });
  assert.deepEqual(result, { sources: [], lunarDirectory: null, lunarSettingsDirectory: null });
});

test('Cafeteria 게임 폴더 자신은 가져올 곳으로 나오지 않는다', async (t) => {
  const { minecraft, home } = setup(t);
  const result = await importer.findSources({ gameDir: minecraft, home, platform: 'linux', env: {} });
  assert.equal(result.sources.some((source) => source.directory === minecraft), false);
});

test('고른 항목만 복사하고 원본은 그대로 둔다', async (t) => {
  const { minecraft, gameDir } = setup(t);
  const before = listFiles(minecraft);
  const result = await importer.importItems({ sourceDirectory: minecraft, itemIds: ['options', 'xaero-config', 'xaero-data'], gameDir });

  assert.deepEqual(listFiles(gameDir), [
    'config/xaero/minimap/client.cfg',
    'config/xaero/minimap/profiles/default.cfg',
    'config/xaerohud.txt',
    'options.txt',
    'xaero/minimap/Multiplayer_play.example.com/dim%0/mw$default_1.txt',
    'xaero/world-map/Multiplayer_play.example.com/null/region.zip',
  ]);
  assert.equal(fs.readFileSync(path.join(gameDir, 'options.txt'), 'utf8'), 'mouseSensitivity:0.3\n');
  assert.deepEqual(result, {
    copied: 6,
    skipped: 0,
    items: [
      { id: 'options', label: '게임 설정', copied: 1, skipped: 0 },
      { id: 'xaero-config', label: 'Xaero 미니맵·월드맵 설정', copied: 3, skipped: 0 },
      { id: 'xaero-data', label: 'Xaero 웨이포인트·지도 데이터', copied: 2, skipped: 0 },
    ],
  });
  assert.deepEqual(listFiles(minecraft), before);
});

test('이미 있는 파일은 기본으로 건너뛰고, 덮어쓰기를 켜면 바꾼다', async (t) => {
  const { minecraft, gameDir } = setup(t);
  write(gameDir, 'options.txt', 'Cafeteria 에서 바꾼 설정');
  write(gameDir, 'config/xaero/minimap/client.cfg', 'Cafeteria 에서 바꾼 미니맵');

  const kept = await importer.importItems({ sourceDirectory: minecraft, itemIds: ['options', 'xaero-config'], gameDir });
  assert.deepEqual([kept.copied, kept.skipped], [2, 2]);
  assert.equal(fs.readFileSync(path.join(gameDir, 'options.txt'), 'utf8'), 'Cafeteria 에서 바꾼 설정');
  assert.equal(fs.readFileSync(path.join(gameDir, 'config/xaero/minimap/client.cfg'), 'utf8'), 'Cafeteria 에서 바꾼 미니맵');

  const replaced = await importer.importItems({ sourceDirectory: minecraft, itemIds: ['options', 'xaero-config'], gameDir, overwrite: true });
  assert.deepEqual([replaced.copied, replaced.skipped], [4, 0]);
  assert.equal(fs.readFileSync(path.join(gameDir, 'options.txt'), 'utf8'), 'mouseSensitivity:0.3\n');
  assert.equal(fs.readFileSync(path.join(gameDir, 'config/xaero/minimap/client.cfg'), 'utf8'), 'current_profile = default');
});

test('"다른 모드 설정"은 다른 PC의 Cafeteria 설정을 덮어쓰지 않는다', async (t) => {
  const { minecraft, gameDir } = setup(t);
  await importer.importItems({ sourceDirectory: minecraft, itemIds: ['mod-config'], gameDir, overwrite: true });
  assert.deepEqual(listFiles(gameDir), ['config/sodium-options.json']);
});

test('모드 파일과 스크린샷처럼 목록에 없는 것은 가져오지 않는다', async (t) => {
  const { minecraft, gameDir } = setup(t);
  await importer.importItems({ sourceDirectory: minecraft, itemIds: importer.ITEMS.map((item) => item.id), gameDir });
  const files = listFiles(gameDir);
  assert.equal(files.some((file) => file.startsWith('mods/') || file.startsWith('screenshots/')), false);
  assert.ok(files.includes('saves/My World/level.dat'));
  assert.ok(files.includes('resourcepacks/pvp-pack.zip'));
});

test('심볼릭 링크는 따라가지 않는다', { skip: process.platform === 'win32' }, async (t) => {
  const { minecraft, gameDir, home } = setup(t);
  write(home, 'secret/private.txt', '게임 폴더 밖의 파일');
  fs.symlinkSync(path.join(home, 'secret'), path.join(minecraft, 'config', 'xaero-link'));
  fs.symlinkSync(path.join(home, 'secret', 'private.txt'), path.join(minecraft, 'resourcepacks', 'link.txt'));

  await importer.importItems({ sourceDirectory: minecraft, itemIds: ['xaero-config', 'resourcepacks'], gameDir });
  assert.equal(listFiles(gameDir).some((file) => file.includes('link') || file.includes('private')), false);
});

test('잘못된 요청은 안내 오류', async (t) => {
  const { minecraft, gameDir, home } = setup(t);
  await assert.rejects(importer.importItems({ sourceDirectory: path.join(home, 'nope'), itemIds: ['options'], gameDir }), { code: 'BAD_SOURCE' });
  await assert.rejects(importer.importItems({ sourceDirectory: path.join(minecraft, 'options.txt'), itemIds: ['options'], gameDir }), { code: 'BAD_SOURCE' });
  await assert.rejects(importer.importItems({ sourceDirectory: null, itemIds: ['options'], gameDir }), { code: 'BAD_SOURCE' });
  await assert.rejects(importer.importItems({ sourceDirectory: minecraft, itemIds: ['options'], gameDir: minecraft }), { code: 'BAD_SOURCE' });
  await assert.rejects(importer.importItems({ sourceDirectory: minecraft, itemIds: ['options'], gameDir: path.join(minecraft, 'inside') }), { code: 'BAD_SOURCE' });
  await assert.rejects(importer.importItems({ sourceDirectory: minecraft, itemIds: [], gameDir }), { code: 'BAD_REQUEST' });
  await assert.rejects(importer.importItems({ sourceDirectory: minecraft, itemIds: ['../../etc', 'unknown'], gameDir }), { code: 'BAD_REQUEST' });
  assert.deepEqual(listFiles(gameDir), []);
});

test('직접 고른 폴더에 가져올 것이 없으면 항목이 비어 있다', async (t) => {
  const directory = tempDir(t);
  write(directory, 'readme.txt', 'x');
  assert.deepEqual(await importer.describeSource(directory, '직접 고른 폴더'), { directory, label: '직접 고른 폴더', items: [] });
});
