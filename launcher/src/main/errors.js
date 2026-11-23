'use strict';

/**
 * 사용자에게 그대로 보여 줄 수 있는 오류. message 는 한국어 안내문이고 code 로 종류를 구분한다.
 * 그 밖의 오류(버그, 예상 못 한 실패)는 일반 Error 로 남겨 두고 화면에서는 원문과 함께 보여 준다.
 */
class LauncherError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = 'LauncherError';
    this.code = code;
    this.details = details;
  }
}

module.exports = { LauncherError };
