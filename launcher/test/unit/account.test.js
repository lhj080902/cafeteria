'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const { AccountManager } = require('../../src/main/account');
const { LauncherError } = require('../../src/main/errors');
const { tempDir } = require('./helpers');

const DAY = 86_400_000;

function fakeVault(available = true) {
  return {
    isAvailable: () => available,
    encrypt: (text) => Buffer.from(`enc:${text}`),
    decrypt: (data) => {
      const text = data.toString();

      if (!text.startsWith('enc:')) {
        throw new Error('풀 수 없는 값');
      }

      return text.slice(4);
    },
  };
}

/** 호출을 기록하는 가짜 로그인 모듈. refresh 토큰은 쓸 때마다 R1 → R2 → R3 으로 바뀐다. */
function fakeAuth(clock, overrides = {}) {
  const calls = [];
  let issued = 1;
  return {
    calls,
    redeemCode: async (code) => (calls.push(`redeem:${code}`), { accessToken: 'MS1', refreshToken: 'R1' }),
    refreshTokens: async (refresh) => (calls.push(`refresh:${refresh}`), { accessToken: `MS${(issued += 1)}`, refreshToken: `R${issued}` }),
    minecraftLogin: async (ms) => (calls.push(`minecraft:${ms}`), { accessToken: `MC-${ms}`, expiresAt: clock.now + DAY, name: 'Steve', uuid: 'uuid-1', xuid: 'xuid-1' }),
    ...overrides,
  };
}

function setup(t, { vault = fakeVault(), overrides } = {}) {
  const clock = { now: 1_000_000 };
  const file = path.join(tempDir(t), 'account.json');
  const auth = fakeAuth(clock, overrides);
  const create = () => new AccountManager({ file, vault, auth, now: () => clock.now }).load();
  return { clock, file, auth, create };
}

test('로그인하면 이름을 보여 주고 refresh 토큰을 암호화해서 저장한다', async (t) => {
  const { file, create } = setup(t);
  const account = await create();
  assert.deepEqual(account.publicView(), { loggedIn: false, name: '' });
  assert.deepEqual(await account.completeLogin('CODE'), { loggedIn: true, name: 'Steve' });

  const stored = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.deepEqual(stored, { name: 'Steve', uuid: 'uuid-1', refreshToken: { encrypted: Buffer.from('enc:R1').toString('base64') } });
  assert.deepEqual(await account.session(), { accessToken: 'MC-MS1', name: 'Steve', uuid: 'uuid-1', xuid: 'xuid-1' });
});

test('런처를 다시 켜면 네트워크 없이 이름을 보여 주고, 세션을 요청할 때 갱신한다', async (t) => {
  const { file, auth, create } = setup(t);
  await (await create()).completeLogin('CODE');
  auth.calls.length = 0;

  const account = await create();
  assert.deepEqual(account.publicView(), { loggedIn: true, name: 'Steve' });
  assert.deepEqual(auth.calls, []);
  assert.equal((await account.session()).accessToken, 'MC-MS2');
  assert.deepEqual(auth.calls, ['refresh:R1', 'minecraft:MS2']);
  assert.equal(JSON.parse(fs.readFileSync(file, 'utf8')).refreshToken.encrypted, Buffer.from('enc:R2').toString('base64'));
});

test('유효한 세션은 다시 요청하지 않고, 만료 5분 전부터 갱신한다', async (t) => {
  const { clock, auth, create } = setup(t);
  const account = await create();
  await account.completeLogin('CODE');
  auth.calls.length = 0;

  clock.now += DAY - 6 * 60_000;
  await account.session();
  assert.deepEqual(auth.calls, []);

  clock.now += 2 * 60_000;
  assert.equal((await account.session()).accessToken, 'MC-MS2');
  assert.deepEqual(auth.calls, ['refresh:R1', 'minecraft:MS2']);
});

test('동시에 여러 번 요청해도 갱신은 한 번만 나간다', async (t) => {
  const { auth, create } = setup(t);
  await (await create()).completeLogin('CODE');
  auth.calls.length = 0;

  const account = await create();
  const sessions = await Promise.all([account.session(), account.session(), account.session()]);
  assert.deepEqual(sessions.map((session) => session.accessToken), ['MC-MS2', 'MC-MS2', 'MC-MS2']);
  assert.deepEqual(auth.calls, ['refresh:R1', 'minecraft:MS2']);
});

test('refresh 토큰이 만료됐으면 계정을 지운다', async (t) => {
  const overrides = {
    refreshTokens: async () => {
      throw new LauncherError('LOGIN_EXPIRED', '로그인이 만료됐습니다. 다시 로그인해 주세요.');
    },
  };
  const { file, create } = setup(t, { overrides });
  await (await create()).completeLogin('CODE');

  const account = await create();
  await assert.rejects(account.session(), { code: 'LOGIN_EXPIRED' });
  assert.deepEqual(account.publicView(), { loggedIn: false, name: '' });
  assert.equal(fs.existsSync(file), false);
});

test('연결 실패나 서버 오류에는 계정을 지우지 않는다', async (t) => {
  for (const code of ['NETWORK', 'LOGIN_FAILED', 'XBOX_CHILD']) {
    const overrides = {
      refreshTokens: async () => {
        throw new LauncherError(code, '실패');
      },
    };
    const { file, create } = setup(t, { overrides });
    await (await create()).completeLogin('CODE');

    const account = await create();
    await assert.rejects(account.session(), { code });
    assert.deepEqual(account.publicView(), { loggedIn: true, name: 'Steve' });
    assert.equal(fs.existsSync(file), true);
  }
});

test('로그인하지 않았으면 LOGIN_REQUIRED', async (t) => {
  const { create } = setup(t);
  await assert.rejects((await create()).session(), { code: 'LOGIN_REQUIRED' });
});

test('로그아웃하면 파일과 메모리의 토큰을 모두 지운다', async (t) => {
  const { file, create } = setup(t);
  const account = await create();
  await account.completeLogin('CODE');
  assert.deepEqual(await account.logout(), { loggedIn: false, name: '' });
  assert.equal(fs.existsSync(file), false);
  await assert.rejects(account.session(), { code: 'LOGIN_REQUIRED' });
  await account.logout();
});

test('암호화를 쓸 수 없으면 디스크에 저장하지 않고 실행 중에만 유지한다', async (t) => {
  const { file, create } = setup(t, { vault: fakeVault(false) });
  const account = await create();
  assert.deepEqual(await account.completeLogin('CODE'), { loggedIn: true, name: 'Steve' });
  assert.equal((await account.session()).accessToken, 'MC-MS1');
  assert.equal(fs.existsSync(file), false);
  assert.deepEqual((await create()).publicView(), { loggedIn: false, name: '' });
});

test('깨진 파일이나 풀 수 없는 토큰은 로그인 안 된 것으로 본다', async (t) => {
  const { file, create } = setup(t);
  fs.writeFileSync(file, '{ 깨진 JSON');
  assert.deepEqual((await create()).publicView(), { loggedIn: false, name: '' });

  fs.writeFileSync(file, JSON.stringify({ name: 'Steve', uuid: 'u', refreshToken: { encrypted: Buffer.from('다른 PC 의 값').toString('base64') } }));
  const account = await create();
  assert.deepEqual(account.publicView(), { loggedIn: false, name: '' });
  await assert.rejects(account.session(), { code: 'LOGIN_REQUIRED' });

  fs.writeFileSync(file, JSON.stringify({ name: 'Steve', refreshToken: { plain: 'R1' } }));
  assert.deepEqual((await create()).publicView(), { loggedIn: false, name: '' });
});

test('화면에 넘기는 값과 저장 파일에 토큰이 평문으로 들어가지 않는다', async (t) => {
  const { file, create } = setup(t);
  const account = await create();
  await account.completeLogin('CODE');
  assert.deepEqual(Object.keys(account.publicView()).sort(), ['loggedIn', 'name']);
  const text = fs.readFileSync(file, 'utf8');

  for (const secret of ['R1', 'MS1', 'MC-MS1']) {
    assert.ok(!text.includes(secret), secret);
  }
});
