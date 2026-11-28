'use strict';

const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { setTimeout: sleep } = require('node:timers/promises');
const { UpdateManager, createAttemptStore } = require('../../src/main/updater');
const { tempDir } = require('./helpers');

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

/**
 * 스스로 설치하는 흐름을 검사할 관리자. world 의 값을 바꾸면 그 뒤로 autoInstall·isBusy·attempts 가 그 값을 따른다.
 * 가짜 타이머는 걸린 것을 timers 에 쌓기만 하고, 테스트가 callback 을 직접 부른다. 지운 것은 cleared 에 쌓인다.
 * world.readError·writeError 에 오류를 넣으면 기록을 읽거나 쓸 때 그 오류를 던진다. log 로 넘어온 인수는 logs 에 쌓인다.
 */
function autoManager(overrides = {}) {
  const world = { canInstall: true, autoInstall: true, busy: false, attempted: null, readError: null, writeError: null, ...overrides };
  const updater = fakeAutoUpdater();
  const states = [];
  const timers = [];
  const cleared = [];
  const logs = [];
  const updates = new UpdateManager({
    autoUpdater: updater,
    canInstall: world.canInstall,
    unsupportedReason: '지원하지 않는 실행 방식입니다.',
    onChange: (state) => states.push(state),
    autoInstall: () => world.autoInstall,
    isBusy: () => world.busy,
    attempts: {
      read: () => {
        if (world.readError) {
          throw world.readError;
        }

        return world.attempted;
      },
      write: (version) => {
        if (world.writeError) {
          throw world.writeError;
        }

        world.attempted = version;
      },
    },
    timers: {
      set: (callback, ms) => {
        const timer = { callback, ms };
        timers.push(timer);
        return timer;
      },
      clear: (timer) => cleared.push(timer),
    },
    log: (...args) => logs.push(args),
  });
  return { updater, updates, world, states, timers, cleared, logs };
}

test('한가하면 3초 뒤 스스로 설치한다', () => {
  // 예전 버전을 시도한 기록은 새 버전을 막지 않는다.
  const { updater, updates, world, timers } = autoManager({ attempted: '9.9.8' });
  // 설치가 끝나지 않고 같은 버전으로 다시 켜졌을 때를 위해, 시도했다는 기록이 설치보다 먼저 남아야 한다.
  const attemptedAtInstall = [];
  const quitAndInstall = updater.quitAndInstall;
  updater.quitAndInstall = (...args) => {
    attemptedAtInstall.push(world.attempted);
    quitAndInstall(...args);
  };

  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'installing');
  assert.equal(updates.state.version, '9.9.9');
  assert.deepEqual(timers.map((timer) => timer.ms), [3000]);
  assert.deepEqual(updater.installed, [], '알린 뒤 3초가 지나기 전에는 설치하지 않는다');

  timers[0].callback();
  assert.deepEqual(updater.installed, [[false, true]]);
  assert.equal(world.attempted, '9.9.9');
  assert.deepEqual(attemptedAtInstall, ['9.9.9']);

  // 설치가 시작되지 않아 런처가 그대로 켜져 있어도, 같은 버전을 또 시도하지 않는다.
  updates.reconsider();
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'failed');
  assert.equal(timers.length, 1);
});

test('타이머를 따로 주지 않으면 실제 타이머로 기다렸다가 설치한다', async () => {
  const updater = fakeAutoUpdater();
  const updates = new UpdateManager({
    autoUpdater: updater,
    canInstall: true,
    unsupportedReason: '지원하지 않는 실행 방식입니다.',
    onChange: () => {},
    autoInstall: () => true,
    delayMs: 5,
  });

  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'installing');
  assert.deepEqual(updater.installed, []);

  // 먼저 건 5ms 타이머가 이 20ms 타이머보다 반드시 먼저 울린다.
  await sleep(20);
  assert.deepEqual(updater.installed, [[false, true]]);
});

test('바쁘면 기다렸다가 한가해지면 설치한다', () => {
  const { updater, updates, world, states, timers, cleared } = autoManager({ busy: true });

  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'waiting');
  assert.match(updates.state.message, /하던 일이 끝나면/);
  assert.equal(timers.length, 0);

  world.busy = false;
  updates.reconsider();
  assert.equal(updates.state.status, 'installing');
  assert.equal(timers.length, 1);

  // 기다리는 3초 사이에 다른 요청이 끝나서 다시 판단해도, 달라진 것이 없으면 타이머를 새로 걸지 않는다.
  const notified = states.length;
  updates.reconsider();
  assert.equal(timers.length, 1);
  assert.deepEqual(cleared, []);
  assert.equal(states.length, notified);
});

test('기다리는 3초 사이에 바빠지면 취소한다', () => {
  // (a) 타이머가 울렸을 때 바쁘면 설치하지 않고, 시도한 것으로도 적지 않는다
  {
    const { updater, updates, world, timers } = autoManager();
    updater.emit('update-downloaded', { version: '9.9.9' });
    world.busy = true;

    timers[0].callback();
    assert.deepEqual(updater.installed, []);
    assert.equal(updates.state.status, 'ready');
    assert.equal(updates.state.auto, 'waiting');
    assert.equal(world.attempted, null);
  }

  // (b) 타이머가 울리기 전에 다시 판단하면 타이머를 지운다
  {
    const { updater, updates, world, timers, cleared } = autoManager();
    updater.emit('update-downloaded', { version: '9.9.9' });
    world.busy = true;

    updates.reconsider();
    assert.deepEqual(cleared, [timers[0]]);
    assert.equal(updates.state.status, 'ready');
    assert.equal(updates.state.auto, 'waiting');
  }
});

test('같은 버전을 이미 시도했으면 스스로 설치하지 않는다', () => {
  const { updater, updates, timers } = autoManager({ attempted: '9.9.9' });

  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'failed');
  assert.match(updates.state.message, /재시작해서 설치/);
  assert.equal(timers.length, 0);

  assert.equal(updates.install(), true);
  assert.deepEqual(updater.installed, [[false, true]]);
});

test('자동 업데이트를 꺼 두면 스스로 설치하지 않는다', () => {
  const { updater, updates, timers } = autoManager({ autoInstall: false });

  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'off');
  assert.equal(updates.state.message, '새 버전 9.9.9 준비 완료. 런처를 다시 시작하면 설치됩니다.');
  assert.equal(timers.length, 0);
});

test('압축본은 스스로 설치하지 않는다', () => {
  const { updater, updates, timers } = autoManager({ canInstall: false });

  updater.emit('update-available', { version: '9.9.9' });
  assert.equal(updates.state.status, 'available');

  updates.reconsider();
  assert.equal(updates.state.status, 'available');
  assert.equal(timers.length, 0);

  // 받아 둔 것이 있다고 해도 스스로 설치하지 않는다.
  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'off');
  assert.equal(timers.length, 0);
});

test('installing 중에 버튼을 누르면 바로 설치한다', () => {
  const { updater, updates, timers, cleared } = autoManager();
  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'installing');

  assert.equal(updates.install(), true);
  assert.deepEqual(cleared, [timers[0]]);
  assert.deepEqual(updater.installed, [[false, true]]);

  // 지워진 타이머가 뒤늦게 불려도 한 번 더 설치하지 않는다.
  timers[0].callback();
  assert.deepEqual(updater.installed, [[false, true]]);
});

test('설치를 기다리는 중에 자동 업데이트를 끄면 취소한다', () => {
  const { updater, updates, world, timers, cleared } = autoManager();
  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'installing');

  world.autoInstall = false;
  updates.reconsider();
  assert.deepEqual(cleared, [timers[0]]);
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'off');

  timers[0].callback();
  assert.deepEqual(updater.installed, []);
  assert.equal(world.attempted, null);
});

test('기다리는 중에 설정을 끄면 문구가 바뀐다', () => {
  const { updater, updates, world, states } = autoManager({ busy: true });
  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.auto, 'waiting');

  world.autoInstall = false;
  updates.reconsider();
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'off');
  assert.equal(updates.state.message, '새 버전 9.9.9 준비 완료. 런처를 다시 시작하면 설치됩니다.');
  assert.deepEqual(states.at(-1), updates.state, '바뀐 상태를 화면에 알린다');

  const notified = states.length;
  updates.reconsider();
  assert.equal(states.length, notified, '달라진 것이 없으면 알리지 않는다');
});

test('설치를 기다리는 중에는 새 버전을 다시 확인하지 않는다', async () => {
  const { updater, updates, timers } = autoManager();
  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'installing');

  await updates.check(true);
  assert.equal(updater.checks, 0);
  assert.equal(updates.state.status, 'installing');
  assert.equal(timers.length, 1);
});

test('createAttemptStore 는 깨진 파일을 없는 것으로 본다', (t) => {
  const directory = tempDir(t);
  const file = path.join(directory, 'update-state.json');
  const store = createAttemptStore(file);
  assert.equal(store.read(), null, '파일이 없을 때');

  fs.writeFileSync(file, '{');
  assert.equal(store.read(), null, '파일이 깨졌을 때');

  fs.writeFileSync(file, '{"autoInstallAttempt":5}');
  assert.equal(store.read(), null, '버전이 글자가 아닐 때');

  store.write('9.9.9');
  assert.equal(store.read(), '9.9.9');
  assert.deepEqual(JSON.parse(fs.readFileSync(file, 'utf8')), { autoInstallAttempt: '9.9.9' });

  // 없는 것과 읽을 수 없는 것은 다르다. 읽거나 쓰지 못하면 던져서, 모르는 채로 스스로 설치하지 않게 한다.
  assert.throws(() => createAttemptStore(directory).read(), { code: 'EISDIR' }, '경로가 폴더일 때');
  assert.throws(
    () => createAttemptStore(path.join(file, 'update-state.json')).write('9.9.9'),
    (error) => ['ENOENT', 'ENOTDIR'].includes(error.code),
    '경로의 부모가 보통 파일일 때',
  );
});

test('기록을 쓰지 못하면 스스로 설치하지 않는다', () => {
  const { updater, updates, world, timers, logs } = autoManager();
  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'installing');
  world.writeError = Object.assign(new Error("EACCES: permission denied, open 'C:\\Users\\someone\\update-state.json'"), { code: 'EACCES' });

  timers[0].callback();
  assert.deepEqual(updater.installed, [], '기록 없이 다시 시작하면 설치가 끝나지 않았을 때 켤 때마다 되풀이된다');
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'failed');
  assert.deepEqual(logs.map(([, code]) => code), ['EACCES']);
  assert.doesNotMatch(logs[0].join(' '), /someone|permission denied/, '경로나 운영체제가 준 오류 글은 남기지 않는다');

  // 다시 판단해도 또 시도하지 않고, 같은 문제를 또 남기지도 않는다.
  updates.reconsider();
  assert.equal(updates.state.auto, 'failed');
  assert.equal(timers.length, 1);
  assert.equal(logs.length, 1);

  assert.equal(updates.install(), true, '버튼으로는 설치할 수 있다');
  assert.deepEqual(updater.installed, [[false, true]]);
});

test('기록을 읽지 못하면 스스로 설치하지 않는다', () => {
  const readError = Object.assign(new Error("EPERM: operation not permitted, open 'C:\\Users\\someone\\update-state.json'"), { code: 'EPERM' });
  const { updater, updates, states, timers, logs } = autoManager({ readError });

  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'failed');
  assert.deepEqual(states.map((state) => state.auto), ['failed'], '곧 스스로 설치한다고 알린 적이 없다');
  assert.equal(timers.length, 0);

  updates.reconsider();
  updates.reconsider();
  assert.equal(updates.state.auto, 'failed');
  assert.equal(timers.length, 0);
  assert.deepEqual(logs.map(([, code]) => code), ['EPERM']);
  assert.doesNotMatch(logs[0].join(' '), /someone|not permitted/, '경로나 운영체제가 준 오류 글은 남기지 않는다');

  assert.equal(updates.install(), true, '버튼으로는 설치할 수 있다');
  assert.deepEqual(updater.installed, [[false, true]]);
});

test('오류가 나면 기다리던 설치를 취소한다', async () => {
  const { updater, updates, world, timers, cleared } = autoManager();
  // 직접 누른 확인이어야 오류가 화면에 남는다.
  await updates.check(true);
  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.state.status, 'installing');

  updater.emit('error', new Error('설치 파일을 찾을 수 없습니다'));
  assert.deepEqual(cleared, [timers[0]]);
  assert.deepEqual(updates.state, { status: 'error', message: '업데이트를 확인하지 못했습니다: 설치 파일을 찾을 수 없습니다', canInstall: true });

  timers[0].callback();
  assert.deepEqual(updater.installed, []);
  assert.equal(world.attempted, null);
});

test('버튼을 눌렀는데 런처가 꺼지지 않으면 "설치 중"에 멈춰 있지 않는다', () => {
  const { updater, updates, world, states, timers } = autoManager();
  updater.emit('update-downloaded', { version: '9.9.9' });
  assert.equal(updates.install(), true);
  assert.equal(updates.state.status, 'installing');

  // 걸어 둔 타이머가 없는 채로 다시 판단하게 되면(설치 요청이 끝남) 타이머를 다시 건다. 이미 알렸으므로 또 알리지는 않는다.
  const notified = states.length;
  updates.reconsider();
  assert.equal(timers.length, 2);
  assert.equal(states.length, notified);

  // 그 타이머는 시도를 적고 설치를 한 번 더 부른다.
  timers[1].callback();
  assert.deepEqual(updater.installed, [[false, true], [false, true]]);
  assert.equal(world.attempted, '9.9.9');

  // 그래도 꺼지지 않으면 버튼이 보이는 ready 로 가고, 기록이 남았으므로 더는 스스로 시도하지 않는다.
  updates.reconsider();
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'failed');
  updates.reconsider();
  assert.equal(timers.length, 2);
  assert.deepEqual(updater.installed, [[false, true], [false, true]]);
});

test('기록이 사라져도 한 번 시도한 버전은 이번 실행에서 다시 시도하지 않는다', () => {
  const { updater, updates, world, timers } = autoManager();
  updater.emit('update-downloaded', { version: '9.9.9' });
  timers[0].callback();
  assert.deepEqual(updater.installed, [[false, true]]);

  // 설치가 시작되지 않아 런처가 그대로 켜져 있는데, 적어 둔 기록마저 읽히지 않는다.
  world.attempted = null;
  updates.reconsider();
  assert.equal(updates.state.status, 'ready');
  assert.equal(updates.state.auto, 'failed');
  assert.equal(timers.length, 1);
});
