'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const paths = require('../../src/main/paths');

test('Windows 에서는 %APPDATA% 아래의 폴더를 쓴다', () => {
  const options = { platform: 'win32', env: { APPDATA: 'C:\\Users\\me\\AppData\\Roaming' }, home: 'C:\\Users\\me' };
  assert.equal(paths.minecraftDir(options), 'C:\\Users\\me\\AppData\\Roaming\\.minecraft');
  assert.equal(paths.defaultGameDir(options), 'C:\\Users\\me\\AppData\\Roaming\\.cafeteria');
});

test('Windows 에서 APPDATA 가 없으면 홈 폴더 기준으로 찾는다', () => {
  const options = { platform: 'win32', env: {}, home: 'C:\\Users\\me' };
  assert.equal(paths.minecraftDir(options), 'C:\\Users\\me\\AppData\\Roaming\\.minecraft');
});

test('macOS 와 Linux 의 기본 위치', () => {
  assert.equal(paths.minecraftDir({ platform: 'darwin', env: {}, home: '/Users/me' }), '/Users/me/Library/Application Support/minecraft');
  assert.equal(paths.defaultGameDir({ platform: 'darwin', env: {}, home: '/Users/me' }), '/Users/me/Library/Application Support/cafeteria');
  assert.equal(paths.minecraftDir({ platform: 'linux', env: {}, home: '/home/me' }), '/home/me/.minecraft');
  assert.equal(paths.defaultGameDir({ platform: 'linux', env: {}, home: '/home/me' }), '/home/me/.cafeteria');
});

test('환경 변수로 위치를 바꿀 수 있다', () => {
  const env = { CAFETERIA_MINECRAFT_DIR: '/tmp/mc', CAFETERIA_GAME_DIR: '/tmp/game' };
  assert.equal(paths.minecraftDir({ platform: 'linux', env, home: '/home/me' }), '/tmp/mc');
  assert.equal(paths.defaultGameDir({ platform: 'linux', env, home: '/home/me' }), '/tmp/game');
});

test('런처 데이터 폴더는 게임 폴더 설정과 상관없이 기본 위치에 고정된다', () => {
  const home = 'C:\\Users\\a';
  const env = { APPDATA: 'C:\\Users\\a\\AppData\\Roaming', CAFETERIA_GAME_DIR: 'D:\\games' };
  assert.equal(paths.dataDir({ platform: 'win32', env, home }), 'C:\\Users\\a\\AppData\\Roaming\\.cafeteria');
  assert.equal(paths.dataDir({ platform: 'win32', env: { CAFETERIA_DATA_DIR: 'D:\\data' }, home }), 'D:\\data');
  assert.equal(paths.dataDir({ platform: 'linux', env: {}, home: '/home/a' }), '/home/a/.cafeteria');
});
