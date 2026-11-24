'use strict';

const { BrowserWindow, session } = require('electron');
const { LauncherError } = require('./errors');
const { authorizeUrl, parseRedirect } = require('./ms-auth');

/** 주소를 불러오다 멈춘 경우 (돌아올 주소로의 이동을 막았을 때 등). 오류로 보지 않는다. */
const ERR_ABORTED = -3;

/**
 * Microsoft 로그인 창을 띄우고 로그인 코드를 돌려준다.
 * 이 창에는 preload 가 없어서 런처 기능이 전혀 노출되지 않는다.
 * 코드와 주소는 로그에 남기지 않는다.
 *
 * @param {{parent: BrowserWindow | null}} options
 * @returns {Promise<string>} 로그인 코드. 창을 닫거나 로그인을 거절하면 LOGIN_CANCELLED
 */
function openLoginWindow({ parent }) {
  // 디스크에 남지 않는 세션을 창마다 새로 만든다 ('persist:' 를 붙이지 않는다).
  const loginSession = session.fromPartition(`cafeteria-login-${Date.now()}`);
  // 런처는 app.userAgentFallback 을 'CafeteriaLauncher/x.y.z' 로 바꿔 두는데, Microsoft 로그인 페이지에는 일반 브라우저 형식을 보낸다.
  loginSession.setUserAgent(`Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${process.versions.chrome} Safari/537.36`);

  const loginWindow = new BrowserWindow({
    parent,
    modal: true,
    width: 520,
    height: 680,
    title: 'Microsoft 로그인',
    backgroundColor: '#ffffff',
    webPreferences: {
      session: loginSession,
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  loginWindow.removeMenu();
  loginWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));

  return new Promise((resolve, reject) => {
    let settled = false;

    // 결과는 한 번만 정한다. 정한 뒤에는 창을 닫는다.
    const finish = (settle) => {
      if (!settled) {
        settled = true;
        settle();
      }

      if (!loginWindow.isDestroyed()) {
        loginWindow.close();
      }
    };

    /** 돌아올 주소면 결과를 정하고 true. 그 주소로의 이동은 막는다. */
    const check = (address, event) => {
      const result = parseRedirect(address);

      if (!result) {
        return false;
      }

      if (event) {
        event.preventDefault();
      }

      if (result.code) {
        finish(() => resolve(result.code));
      } else {
        finish(() => reject(cancelled()));
      }

      return true;
    };

    const { webContents } = loginWindow;
    webContents.on('will-redirect', (details) => check(details.url, details));
    webContents.on('will-navigate', (details) => check(details.url, details));
    webContents.on('did-navigate', (_event, url) => check(url, null));
    webContents.on('did-fail-load', (_event, errorCode, _errorDescription, validatedURL, isMainFrame) => {
      // 돌아올 주소를 불러오지 못한 경우에도 주소에 든 코드는 쓸 수 있다.
      if (!isMainFrame || errorCode === ERR_ABORTED || check(validatedURL, null)) {
        return;
      }

      finish(() => reject(new LauncherError('NETWORK', 'Microsoft 로그인 페이지를 열지 못했습니다. 인터넷 연결을 확인해 주세요.')));
    });

    loginWindow.on('closed', () => {
      loginSession.clearStorageData().catch(() => {});

      if (!settled) {
        settled = true;
        reject(cancelled());
      }
    });

    // 불러오기 실패는 did-fail-load 에서 처리한다. 오류 내용에 주소가 들어 있으므로 여기서는 로그를 남기지 않는다.
    loginWindow.loadURL(authorizeUrl()).catch(() => {});
  });
}

function cancelled() {
  return new LauncherError('LOGIN_CANCELLED', '로그인을 취소했습니다.');
}

module.exports = { openLoginWindow };
