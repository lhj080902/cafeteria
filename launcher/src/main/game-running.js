'use strict';

const { LauncherError } = require('./errors');

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

module.exports = { assertGameNotRunning };
