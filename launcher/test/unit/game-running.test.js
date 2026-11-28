'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { assertGameNotRunning } = require('../../src/main/game-running');

test('게임이 켜져 있으면 GAME_RUNNING 으로 거절한다', async () => {
  const expected = { code: 'GAME_RUNNING', message: '게임이 실행 중입니다. 게임을 끈 뒤 다시 시도해 주세요.' };

  // 켜져 있는지는 바로 답해도 되고 Promise 로 답해도 된다.
  await assert.rejects(assertGameNotRunning(() => true), expected);
  await assert.rejects(assertGameNotRunning(async () => true), expected);
  await assertGameNotRunning(() => false);
  await assertGameNotRunning(async () => false);
});
