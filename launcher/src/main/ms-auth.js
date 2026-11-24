'use strict';

const { MSA } = require('./constants');
const { LauncherError } = require('./errors');
const { sendJson } = require('./http');

const AUTHORIZE_URL = 'https://login.live.com/oauth20_authorize.srf';
const TOKEN_URL = 'https://login.live.com/oauth20_token.srf';
const XBOX_URL = 'https://user.auth.xboxlive.com/user/authenticate';
const XSTS_URL = 'https://xsts.auth.xboxlive.com/xsts/authorize';
const MINECRAFT_URL = 'https://api.minecraftservices.com/authentication/login_with_xbox';
const PROFILE_URL = 'https://api.minecraftservices.com/minecraft/profile';

/** XSTS 가 돌려주는 XErr 값별 오류. */
const XBOX_ERRORS = {
  2148916233: ['XBOX_NO_PROFILE', 'Xbox 프로필이 없는 계정입니다.'],
  2148916235: ['XBOX_REGION', 'Xbox Live 를 쓸 수 없는 국가의 계정입니다.'],
  2148916236: ['XBOX_ADULT_VERIFY', '성인 인증이 필요한 계정입니다.'],
  2148916237: ['XBOX_ADULT_VERIFY', '성인 인증이 필요한 계정입니다.'],
  2148916238: ['XBOX_CHILD', '미성년자 계정은 보호자의 가족 그룹에 들어 있어야 합니다.'],
};

/** 사용자가 Microsoft 계정으로 로그인하는 페이지 주소. */
function authorizeUrl() {
  const url = new URL(AUTHORIZE_URL);
  url.searchParams.set('client_id', MSA.clientId);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('redirect_uri', MSA.redirectUri);
  url.searchParams.set('scope', MSA.scope);
  url.searchParams.set('prompt', 'select_account');
  return url.toString();
}

/** 로그인이 끝나 돌아온 주소에서 코드나 오류를 꺼낸다. 돌아올 주소가 아니면 null. */
function parseRedirect(address) {
  let url;

  try {
    url = new URL(address);
  } catch {
    return null;
  }

  if (url.origin + url.pathname !== MSA.redirectUri) {
    return null;
  }

  const code = url.searchParams.get('code');

  if (code) {
    return { code };
  }

  return { error: url.searchParams.get('error') || 'no_code' };
}

function loginFailed(step, status) {
  return new LauncherError('LOGIN_FAILED', `로그인하지 못했습니다 (${step} 단계, HTTP ${status}). 잠시 후 다시 시도해 주세요.`, { step, status });
}

function isOk(response) {
  return response.status >= 200 && response.status < 300;
}

/** 응답이 2xx 이고 본문이 객체일 때만 본문을 돌려주고, 아니면 LOGIN_FAILED 를 던진다. */
function requireBody(step, response) {
  if (!isOk(response) || !response.body || typeof response.body !== 'object') {
    throw loginFailed(step, response.status);
  }

  return response.body;
}

async function requestTokens(grant) {
  const response = await sendJson(TOKEN_URL, {
    method: 'POST',
    form: { client_id: MSA.clientId, redirect_uri: MSA.redirectUri, ...grant },
  });

  if (grant.grant_type === 'refresh_token' && !isOk(response) && response.body?.error === 'invalid_grant') {
    throw new LauncherError('LOGIN_EXPIRED', '로그인이 만료됐습니다. 다시 로그인해 주세요.', { step: 'Microsoft', status: response.status });
  }

  const body = requireBody('Microsoft', response);

  if (typeof body.access_token !== 'string' || typeof body.refresh_token !== 'string') {
    throw loginFailed('Microsoft', response.status);
  }

  return { accessToken: body.access_token, refreshToken: body.refresh_token };
}

/** 로그인 후 받은 코드를 Microsoft 토큰으로 바꾼다. */
function redeemCode(code) {
  return requestTokens({ grant_type: 'authorization_code', code });
}

/** 저장해 둔 refresh 토큰으로 새 Microsoft 토큰을 받는다. */
function refreshTokens(refreshToken) {
  return requestTokens({ grant_type: 'refresh_token', refresh_token: refreshToken });
}

/** Minecraft 토큰(JWT)에 들어 있는 xuid. 읽지 못하면 빈 문자열. */
function readXuid(token) {
  try {
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    return typeof payload.xuid === 'string' ? payload.xuid : '';
  } catch {
    return '';
  }
}

/** Microsoft 토큰으로 Xbox Live, XSTS 를 거쳐 Minecraft 세션과 프로필을 얻는다. */
async function minecraftLogin(msAccessToken, now = Date.now()) {
  const xboxResponse = await sendJson(XBOX_URL, {
    method: 'POST',
    json: {
      Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: `d=${msAccessToken}` },
      RelyingParty: 'http://auth.xboxlive.com',
      TokenType: 'JWT',
    },
  });
  const xbox = requireBody('Xbox Live', xboxResponse);
  const uhs = xbox.DisplayClaims?.xui?.[0]?.uhs;

  if (typeof xbox.Token !== 'string' || typeof uhs !== 'string') {
    throw loginFailed('Xbox Live', xboxResponse.status);
  }

  const xstsResponse = await sendJson(XSTS_URL, {
    method: 'POST',
    json: {
      Properties: { SandboxId: 'RETAIL', UserTokens: [xbox.Token] },
      RelyingParty: 'rp://api.minecraftservices.com/',
      TokenType: 'JWT',
    },
  });

  if (!isOk(xstsResponse)) {
    const known = XBOX_ERRORS[xstsResponse.body?.XErr];

    if (known) {
      throw new LauncherError(known[0], known[1], { step: 'Xbox 보안 토큰', status: xstsResponse.status });
    }
  }

  const xsts = requireBody('Xbox 보안 토큰', xstsResponse);

  if (typeof xsts.Token !== 'string') {
    throw loginFailed('Xbox 보안 토큰', xstsResponse.status);
  }

  const minecraftResponse = await sendJson(MINECRAFT_URL, {
    method: 'POST',
    json: { identityToken: `XBL3.0 x=${uhs};${xsts.Token}` },
  });
  const minecraft = requireBody('Minecraft', minecraftResponse);

  if (typeof minecraft.access_token !== 'string' || !Number.isFinite(minecraft.expires_in)) {
    throw loginFailed('Minecraft', minecraftResponse.status);
  }

  const profileResponse = await sendJson(PROFILE_URL, { headers: { Authorization: `Bearer ${minecraft.access_token}` } });

  if (profileResponse.status === 404) {
    throw new LauncherError('NO_MINECRAFT', '이 계정에는 Minecraft: Java Edition 이 없습니다.', { step: '프로필', status: 404 });
  }

  const profile = requireBody('프로필', profileResponse);

  if (typeof profile.id !== 'string' || typeof profile.name !== 'string') {
    throw loginFailed('프로필', profileResponse.status);
  }

  return {
    accessToken: minecraft.access_token,
    expiresAt: now + minecraft.expires_in * 1000,
    name: profile.name,
    uuid: profile.id,
    xuid: readXuid(minecraft.access_token),
  };
}

module.exports = { authorizeUrl, parseRedirect, redeemCode, refreshTokens, minecraftLogin };
