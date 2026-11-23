'use strict';

const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { test } = require('node:test');
const { UpdateManager } = require('../../src/main/updater');

/** electron-updater 의 autoUpdater 를 흉내 낸 것. script 에 적은 순서대로 이벤트를 낸다. */
function fakeAutoUpdater(script = []) {
  const updater = new EventEmitter();
  updater.checks = 0;
  updater.installed = [];
  updater.checkForUpdates = async () => {
    updater.checks += 1;
    updater.emit('checking-for-update');

    for (const [event, payload] of script) {
      if (event === 'throw') {
        throw payload;
      }

      updater.emit(event, payload);
    }
  };
  updater.quitAndInstall = (...args) => updater.installed.push(args);
  return updater;
}

function manager(autoUpdater, canInstall = true) {
  const states = [];
  const updates = new UpdateManager({ autoUpdater, canInstall, unsupportedReason: '지원하지 않는 실행 방식입니다.', onChange: (state) => states.push(state) });
  return { updates, states };
}

test('지원하지 않는 환경에서는 아무것도 하지 않고 이유를 알려 준다', async () => {
  const { updates, states } = manager(null);
  assert.deepEqual(updates.state, { status: 'unsupported', message: '지원하지 않는 실행 방식입니다.', canInstall: false });
  assert.deepEqual(await updates.check(true), updates.state);
  assert.equal(updates.install(), false);
  assert.deepEqual(states, []);
});

test('설치된 런처: 새 버전을 내려받고, 준비되면 재시작해서 설치할 수 있다', async () => {
  const updater = fakeAutoUpdater([
    ['update-available', { version: '1.2.0' }],
    ['download-progress', { percent: 42.4 }],
    ['download-progress', { percent: 100 }],
    ['update-downloaded', { version: '1.2.0' }],
  ]);
  const { updates, states } = manager(updater, true);
  assert.equal(updater.autoDownload, true);
  assert.equal(updater.autoInstallOnAppQuit, true);
  assert.equal(updates.install(), false, '받기 전에는 설치할 수 없다');

  await updates.check(false);
  assert.deepEqual(states.map((state) => [state.status, state.percent]), [
    ['checking', undefined],
    ['downloading', 0],
    ['downloading', 42],
    ['downloading', 100],
    ['ready', undefined],
  ]);
  assert.equal(updates.state.version, '1.2.0');
  assert.ok(states.every((state) => state.canInstall));

  assert.equal(updates.install(), true);
  assert.deepEqual(updater.installed, [[false, true]]);

  // 이미 받아 둔 업데이트가 있으면 다시 확인하지 않는다
  await updates.check(true);
  assert.equal(updater.checks, 1);
});

test('압축본으로 실행 중: 내려받지 않고 새 버전이 있다는 것만 알린다', async () => {
  const updater = fakeAutoUpdater([['update-available', { version: '1.2.0' }]]);
  const { updates } = manager(updater, false);
  assert.equal(updater.autoDownload, false);
  assert.equal(updater.autoInstallOnAppQuit, false);

  await updates.check(false);
  assert.equal(updates.state.status, 'available');
  assert.equal(updates.state.version, '1.2.0');
  assert.equal(updates.state.canInstall, false);
  assert.match(updates.state.message, /지원하지 않는 실행 방식/);
  assert.equal(updates.install(), false);
  assert.deepEqual(updater.installed, []);
});

test('최신 버전이면 그렇게 알려 준다', async () => {
  const { updates } = manager(fakeAutoUpdater([['update-not-available', { version: '1.1.0' }]]));
  await updates.check(true);
  assert.equal(updates.state.status, 'up-to-date');
});

test('자동 확인에서 난 오류는 조용히 넘기고, 직접 확인했을 때만 알려 준다', async () => {
  const error = new Error('net::ERR_INTERNET_DISCONNECTED\n    at 긴 스택...');
  const { updates } = manager(fakeAutoUpdater([['error', error]]));

  await updates.check(false);
  assert.deepEqual(updates.state, { status: 'idle', canInstall: true });

  await updates.check(true);
  assert.equal(updates.state.status, 'error');
  assert.equal(updates.state.message, '업데이트를 확인하지 못했습니다: net::ERR_INTERNET_DISCONNECTED');
});

test('이벤트 없이 실패해도 "확인 중"에 멈춰 있지 않는다', async () => {
  const { updates } = manager(fakeAutoUpdater([['throw', new Error('404 릴리스 없음')]]));
  await updates.check(false);
  assert.equal(updates.state.status, 'idle');
  await updates.check(true);
  assert.equal(updates.state.status, 'error');
  assert.match(updates.state.message, /404 릴리스 없음/);
});

test('오류가 난 뒤에는 다시 확인할 수 있다', async () => {
  const updater = fakeAutoUpdater([['error', new Error('일시적 오류')]]);
  const { updates } = manager(updater);
  await updates.check(true);
  await updates.check(true);
  assert.equal(updater.checks, 2);
});
