'use strict';

const assert = require('node:assert/strict');
const { afterEach, test } = require('node:test');
const http = require('../../src/main/http');
const auth = require('../../src/main/ms-auth');
const { fakeFetch } = require('./helpers');

afterEach(() => http.setFetch((...args) => globalThis.fetch(...args)));

const TOKEN_URL = 'https://login.live.com/oauth20_token.srf';
const XBOX_URL = 'https://user.auth.xboxlive.com/user/authenticate';
const XSTS_URL = 'https://xsts.auth.xboxlive.com/xsts/authorize';
const MINECRAFT_URL = 'https://api.minecraftservices.com/authentication/login_with_xbox';
const PROFILE_URL = 'https://api.minecraftservices.com/minecraft/profile';
/** 진짜처럼 생긴 Minecraft 토큰 (JWT). payload 에 xuid 가 들어 있다. */
const MC_TOKEN = `h.${Buffer.from(JSON.stringify({ xuid: '2535400000000000' })).toString('base64url')}.s`;

function routes(overrides = {}) {
  return {
    [TOKEN_URL]: { json: { access_token: 'MS_ACCESS', refresh_token: 'MS_REFRESH' } },
    [XBOX_URL]: { json: { Token: 'XBL_TOKEN', DisplayClaims: { xui: [{ uhs: 'UHS' }] } } },
    [XSTS_URL]: { json: { Token: 'XSTS_TOKEN', DisplayClaims: { xui: [{ uhs: 'UHS' }] } } },
    [MINECRAFT_URL]: { json: { access_token: MC_TOKEN, expires_in: 86400 } },
    [PROFILE_URL]: { json: { id: '0123456789abcdef0123456789abcdef', name: 'Steve' } },
    ...overrides,
  };
}

test('로그인 주소에 클라이언트 ID, 돌아올 주소, 범위, 계정 선택이 들어간다', () => {
  const url = new URL(auth.authorizeUrl());
  assert.equal(url.origin + url.pathname, 'https://login.live.com/oauth20_authorize.srf');
  assert.equal(url.searchParams.get('client_id'), '00000000402b5328');
  assert.equal(url.searchParams.get('response_type'), 'code');
  assert.equal(url.searchParams.get('redirect_uri'), 'https://login.live.com/oauth20_desktop.srf');
  assert.equal(url.searchParams.get('scope'), 'XboxLive.signin offline_access');
  assert.equal(url.searchParams.get('prompt'), 'select_account');
});

test('돌아온 주소에서 코드나 오류를 꺼낸다', () => {
  assert.deepEqual(auth.parseRedirect('https://login.live.com/oauth20_desktop.srf?code=M.C123_BAY&lc=1042'), { code: 'M.C123_BAY' });
  assert.deepEqual(auth.parseRedirect('https://login.live.com/oauth20_desktop.srf?error=access_denied&error_description=x'), { error: 'access_denied' });
  assert.deepEqual(auth.parseRedirect('https://login.live.com/oauth20_desktop.srf'), { error: 'no_code' });
  assert.equal(auth.parseRedirect('https://login.live.com/oauth20_authorize.srf?client_id=1'), null);
  assert.equal(auth.parseRedirect('https://evil.example.com/oauth20_desktop.srf?code=1'), null);
  assert.equal(auth.parseRedirect('https://login.live.com.evil.example.com/oauth20_desktop.srf?code=1'), null);
  assert.equal(auth.parseRedirect('not a url'), null);
});

test('코드를 Microsoft 토큰으로 바꾼다', async () => {
  const fetch = fakeFetch(routes());
  http.setFetch(fetch);
  assert.deepEqual(await auth.redeemCode('CODE'), { accessToken: 'MS_ACCESS', refreshToken: 'MS_REFRESH' });
  const body = new URLSearchParams(fetch.calls[0].body);
  assert.equal(fetch.calls[0].method, 'POST');
  assert.deepEqual(Object.fromEntries(body), {
    client_id: '00000000402b5328',
    code: 'CODE',
    grant_type: 'authorization_code',
    redirect_uri: 'https://login.live.com/oauth20_desktop.srf',
  });
});

test('refresh 토큰으로 새 토큰을 받는다', async () => {
  const fetch = fakeFetch(routes());
  http.setFetch(fetch);
  assert.deepEqual(await auth.refreshTokens('OLD_REFRESH'), { accessToken: 'MS_ACCESS', refreshToken: 'MS_REFRESH' });
  const body = new URLSearchParams(fetch.calls[0].body);
  assert.equal(body.get('grant_type'), 'refresh_token');
  assert.equal(body.get('refresh_token'), 'OLD_REFRESH');
  assert.equal(body.get('client_id'), '00000000402b5328');
});

test('Microsoft 토큰으로 Xbox, XSTS 를 거쳐 Minecraft 세션을 얻는다', async () => {
  const fetch = fakeFetch(routes());
  http.setFetch(fetch);
  assert.deepEqual(await auth.minecraftLogin('MS_ACCESS', 1_000_000), {
    accessToken: MC_TOKEN,
    expiresAt: 1_000_000 + 86400 * 1000,
    name: 'Steve',
    uuid: '0123456789abcdef0123456789abcdef',
    xuid: '2535400000000000',
  });
  assert.deepEqual(fetch.calls.map((call) => call.url), [XBOX_URL, XSTS_URL, MINECRAFT_URL, PROFILE_URL]);
  const xbox = JSON.parse(fetch.calls[0].body);
  assert.equal(xbox.Properties.RpsTicket, 'd=MS_ACCESS');
  assert.equal(xbox.RelyingParty, 'http://auth.xboxlive.com');
  const xsts = JSON.parse(fetch.calls[1].body);
  assert.deepEqual(xsts.Properties, { SandboxId: 'RETAIL', UserTokens: ['XBL_TOKEN'] });
  assert.equal(xsts.RelyingParty, 'rp://api.minecraftservices.com/');
  assert.deepEqual(JSON.parse(fetch.calls[2].body), { identityToken: 'XBL3.0 x=UHS;XSTS_TOKEN' });
  assert.equal(fetch.calls[3].headers.Authorization, `Bearer ${MC_TOKEN}`);
});

test('JWT 가 아닌 Minecraft 토큰이면 xuid 는 빈 문자열이다', async () => {
  http.setFetch(fakeFetch(routes({ [MINECRAFT_URL]: { json: { access_token: 'opaque', expires_in: 60 } } })));
  assert.equal((await auth.minecraftLogin('MS_ACCESS')).xuid, '');
});

test('refresh 토큰이 거부되면(invalid_grant) LOGIN_EXPIRED, 다른 거부는 LOGIN_FAILED', async () => {
  http.setFetch(fakeFetch(routes({ [TOKEN_URL]: { status: 400, json: { error: 'invalid_grant' } } })));
  await assert.rejects(auth.refreshTokens('R'), { code: 'LOGIN_EXPIRED' });
  http.setFetch(fakeFetch(routes({ [TOKEN_URL]: { status: 400, json: { error: 'unauthorized_client' } } })));
  await assert.rejects(auth.refreshTokens('R'), { code: 'LOGIN_FAILED' });
  http.setFetch(fakeFetch(routes({ [TOKEN_URL]: { status: 400, json: { error: 'invalid_grant' } } })));
  await assert.rejects(auth.redeemCode('C'), { code: 'LOGIN_FAILED' });
});

test('Xbox 계정 문제는 원인별로 안내한다', async () => {
  const cases = { 2148916233: 'XBOX_NO_PROFILE', 2148916235: 'XBOX_REGION', 2148916236: 'XBOX_ADULT_VERIFY', 2148916237: 'XBOX_ADULT_VERIFY', 2148916238: 'XBOX_CHILD' };

  for (const [xerr, code] of Object.entries(cases)) {
    http.setFetch(fakeFetch(routes({ [XSTS_URL]: { status: 401, json: { XErr: Number(xerr) } } })));
    await assert.rejects(auth.minecraftLogin('MS_ACCESS'), { code });
  }
});

test('게임이 없는 계정, 서버 오류, 연결 실패를 구분한다', async () => {
  http.setFetch(fakeFetch(routes({ [PROFILE_URL]: { status: 404, json: { error: 'NOT_FOUND' } } })));
  await assert.rejects(auth.minecraftLogin('MS_ACCESS'), { code: 'NO_MINECRAFT' });

  http.setFetch(fakeFetch(routes({ [MINECRAFT_URL]: { status: 429, json: {} } })));
  await assert.rejects(auth.minecraftLogin('MS_ACCESS'), (error) => error.code === 'LOGIN_FAILED' && error.message.includes('429') && error.message.includes('Minecraft'));

  http.setFetch(fakeFetch(routes({ [XBOX_URL]: { json: { Token: 'XBL_TOKEN' } } })));
  await assert.rejects(auth.minecraftLogin('MS_ACCESS'), { code: 'LOGIN_FAILED' });

  http.setFetch(async () => {
    throw new Error('offline');
  });
  await assert.rejects(auth.minecraftLogin('MS_ACCESS'), { code: 'NETWORK' });
});

test('오류 안내에 토큰이나 서버 응답 본문이 들어가지 않는다', async () => {
  http.setFetch(fakeFetch(routes({ [MINECRAFT_URL]: { status: 403, json: { secret: 'SERVER_BODY' } } })));
  const error = await auth.minecraftLogin('MS_ACCESS').catch((caught) => caught);
  const text = JSON.stringify({ message: error.message, details: error.details });

  for (const secret of ['MS_ACCESS', 'XBL_TOKEN', 'XSTS_TOKEN', 'UHS', 'SERVER_BODY']) {
    assert.ok(!text.includes(secret), secret);
  }
});
