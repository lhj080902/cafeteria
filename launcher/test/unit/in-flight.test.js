'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createInFlight } = require('../../src/main/in-flight');

test('처리 중인 요청 수를 센다', async () => {
  // onSettled 가 불린 순간의 수를 적어 둔다. 끝난 요청은 이미 빠져 있어야 한다.
  const activeWhenSettled = [];
  const inFlight = createInFlight(() => activeWhenSettled.push(inFlight.active()));
  const first = Promise.withResolvers();
  const second = Promise.withResolvers();

  const firstRun = inFlight.run(() => first.promise);
  const secondRun = inFlight.run(() => second.promise);
  assert.equal(inFlight.active(), 2);
  assert.deepEqual(activeWhenSettled, []);

  first.resolve('첫째');
  assert.equal(await firstRun, '첫째');
  assert.equal(inFlight.active(), 1);
  assert.deepEqual(activeWhenSettled, [1]);

  second.resolve('둘째');
  assert.equal(await secondRun, '둘째');
  assert.equal(inFlight.active(), 0);
  assert.deepEqual(activeWhenSettled, [1, 0]);
});

test('작업이 던져도 수가 내려간다', async () => {
  let settled = 0;
  const inFlight = createInFlight(() => {
    settled += 1;
  });
  const error = new Error('처리기 오류');

  await assert.rejects(
    inFlight.run(() => {
      throw error;
    }),
    (thrown) => thrown === error,
  );
  assert.equal(inFlight.active(), 0);
  assert.equal(settled, 1);
});
