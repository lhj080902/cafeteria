'use strict';

const fsp = require('node:fs/promises');
const path = require('node:path');
const { LauncherError } = require('./errors');

/** 게임이 켜져 있는 동안 Cafeteria 모드가 <게임 폴더>/config 에 두고 5초마다 수정 시각을 갱신하는 표지 파일. */
const RUNNING_MARKER = 'cafeteria.running';
/**
 * 표지가 이 시간 안에 갱신됐으면 게임이 켜져 있다고 본다. 게임을 끄면 모드가 표지를 지우지만,
 * 게임이 죽으면 파일이 남는다. 그때도 이 시간이 지나면 꺼진 것으로 본다.
 */
const RUNNING_FRESH_MS = 20000;

/**
 * 게임이 쓰는 파일(설정, 모드)을 바꾸기 전에 부른다. 게임이 켜져 있으면 GAME_RUNNING 으로 거절한다.
 *
 * @param {() => boolean | Promise<boolean>} isGameRunning 지금 게임이 켜져 있는지 알려 주는 함수
 */
async function assertGameNotRunning(isGameRunning) {
  if (await isGameRunning()) {
    throw new LauncherError('GAME_RUNNING', '게임이 실행 중입니다. 게임을 끈 뒤 다시 시도해 주세요.');
  }
}

/**
 * 게임 폴더의 표지 파일을 보고 게임이 켜져 있는지 판단한다. 런처가 직접 띄우지 않은 게임(공식 런처로 켠 게임,
 * 런처를 닫았다 다시 연 뒤에도 켜져 있는 게임)은 이것으로만 알 수 있다. 보기만 하고 아무것도 만들거나 지우지 않는다.
 *
 * @param {string} gameDir Cafeteria 게임 폴더
 * @param {number} [now] 지금 시각 (ms)
 * @returns {Promise<boolean>} 표지가 없거나 읽지 못하면 false
 */
async function markerSaysRunning(gameDir, now = Date.now()) {
  const file = path.join(gameDir, 'config', RUNNING_MARKER);
  let stats;

  try {
    stats = await fsp.stat(file);
  } catch {
    return false;
  }

  // 시계가 조금 어긋나면 수정 시각이 지금보다 뒤일 수도 있어서, 앞뒤를 가리지 않고 차이만 본다.
  return stats.isFile() && Math.abs(now - stats.mtimeMs) < RUNNING_FRESH_MS;
}

/**
 * 지금 게임이 켜져 있는지 알려 주는 함수를 만든다. 런처가 직접 띄운 게임이 있으면 파일을 보지 않고 켜져 있다고 답하고,
 * 없으면 표지 파일을 본다. 게임 폴더는 설정에서 바뀔 수 있어서 물을 때마다 다시 받는다.
 *
 * @param {object} options
 * @param {() => string} options.gameDir 지금 쓰는 게임 폴더
 * @param {() => object | null} options.child 런처가 직접 띄운 게임 프로세스. 없으면 null
 * @returns {() => Promise<boolean>}
 */
function createGameRunningCheck({ gameDir, child }) {
  return async () => child() !== null || markerSaysRunning(gameDir());
}

module.exports = { RUNNING_MARKER, RUNNING_FRESH_MS, assertGameNotRunning, markerSaysRunning, createGameRunningCheck };
