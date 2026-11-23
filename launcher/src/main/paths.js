'use strict';

const os = require('node:os');
const path = require('node:path');

/** 플랫폼에 맞는 path 구현. 테스트에서 다른 OS의 경로 규칙도 확인할 수 있게 직접 고른다. */
function pathFor(platform) {
  return platform === 'win32' ? path.win32 : path.posix;
}

/** 공식 런처가 쓰는 .minecraft 폴더. */
function minecraftDir({ platform = process.platform, env = process.env, home = os.homedir() } = {}) {
  if (env.CAFETERIA_MINECRAFT_DIR) {
    return env.CAFETERIA_MINECRAFT_DIR;
  }

  const p = pathFor(platform);

  if (platform === 'win32') {
    return p.join(env.APPDATA || p.join(home, 'AppData', 'Roaming'), '.minecraft');
  }

  if (platform === 'darwin') {
    return p.join(home, 'Library', 'Application Support', 'minecraft');
  }

  return p.join(home, '.minecraft');
}

/**
 * 카페테리아 전용 게임 폴더(모드, 설정, 월드가 들어가는 곳).
 * 바닐라 .minecraft 와 분리해서 기존에 쓰던 모드와 섞이지 않게 한다.
 */
function defaultGameDir({ platform = process.platform, env = process.env, home = os.homedir() } = {}) {
  if (env.CAFETERIA_GAME_DIR) {
    return env.CAFETERIA_GAME_DIR;
  }

  const p = pathFor(platform);

  if (platform === 'win32') {
    return p.join(env.APPDATA || p.join(home, 'AppData', 'Roaming'), '.cafeteria');
  }

  if (platform === 'darwin') {
    return p.join(home, 'Library', 'Application Support', 'cafeteria');
  }

  return p.join(home, '.cafeteria');
}

module.exports = { minecraftDir, defaultGameDir };
