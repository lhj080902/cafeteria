'use strict';

/*
 * 처리 중인 요청 세기.
 *
 * 화면이 보낸 요청을 처리하는 동안에는 런처가 업데이트를 설치하려고 스스로 다시 시작하면 안 된다 (updater.js).
 * main.js 의 handle() 이 처리기를 run 으로 감싸서, 지금 몇 개가 돌고 있는지와 하나가 끝난 때를 알 수 있게 한다.
 */

/**
 * @param {() => void} onSettled 요청 하나가 끝날 때마다(성공하든 던지든) 수를 내린 뒤에 부른다
 * @returns {{run: (task: () => any) => Promise<any>, active: () => number}}
 */
function createInFlight(onSettled) {
  let count = 0;

  return {
    /** task 를 실행하는 동안 수를 하나 올린다. 결과나 예외는 그대로 넘긴다. */
    async run(task) {
      count += 1;

      try {
        return await task();
      } finally {
        count -= 1;
        onSettled();
      }
    },

    /** 지금 처리 중인 요청 수. */
    active: () => count,
  };
}

module.exports = { createInFlight };
