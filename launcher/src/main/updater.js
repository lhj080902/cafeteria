'use strict';

const fs = require('node:fs');

/*
 * 런처 자동 업데이트.
 *
 * GitHub 릴리스에 올린 새 설치 파일을 electron-updater 로 받아 둔다. 받아 둔 것이 설치되는 길은 두 가지다.
 * ① 한가할 때 스스로 다시 시작: 런처가 하는 일이 없으면 곧 다시 시작한다고 알리고, 잠깐 뒤에 quitAndInstall(false, true) 로
 *    껐다가 새 버전으로 다시 켠다. "재시작해서 설치" 버튼도 같은 호출을 기다리지 않고 한다.
 *    스스로 하는 시도는 버전마다 한 번뿐이다. 설치가 끝나지 않고 같은 버전으로 다시 켜지면 버튼에 맡긴다 (createAttemptStore).
 *    시도했다는 기록을 읽거나 쓰지 못할 때도 스스로 설치하지 않고 버튼에 맡긴다 (켤 때마다 다시 시작하는 것을 막을 수 없으므로).
 * ② 런처를 끌 때 조용히 설치: 전부터 있던 autoInstallOnAppQuit. 게임을 띄우고 런처를 닫는 설정이면 이쪽으로 설치되는 일이 많다.
 *
 * 확인된 정도: 버튼을 눌러 quitAndInstall(false, true) 로 설치하는 것은 실제 Windows 에서 확인됐다 (1.2.2 → 1.3.0).
 * 스스로 다시 시작하는 판단(①)과 끌 때의 조용한 설치(②)는 실제 Windows 에서 해 본 적이 없다.
 *
 * - 설치 파일(Setup.exe)로 설치한 Windows 런처에서만 자동으로 설치된다.
 * - 압축본(zip)으로 쓰는 경우나 다른 OS에서는 새 버전이 있다는 것만 알려 주고 다운로드 페이지를 안내한다.
 * - Cafeteria 모드 jar 는 런처 안에 들어 있으므로, 런처가 업데이트되면 다음 플레이 때 모드도 함께 바뀐다.
 */

/**
 * 화면에 보여 줄 업데이트 상태.
 * @typedef {object} UpdateState
 * @property {'idle'|'unsupported'|'checking'|'up-to-date'|'available'|'downloading'|'ready'|'installing'|'error'} status
 *   'ready' 는 받아 두고 설치를 기다리는 상태, 'installing' 은 곧 스스로 다시 시작해서 설치한다고 알린 상태
 * @property {string} [version] 새 버전
 * @property {number} [percent] 다운로드 진행률(0~100)
 * @property {string} [message] 안내문
 * @property {'off'|'waiting'|'failed'} [auto] 'ready' 일 때만 있다. 스스로 설치하는 일이 어떻게 돼 있는지:
 *   'off' 는 하지 않음(자동 업데이트를 껐거나 스스로 설치할 수 없는 런처), 'waiting' 은 하던 일이 끝나면 함,
 *   'failed' 는 이 버전을 이미 한 번 시도함(시도 기록을 읽거나 쓰지 못한 때도 이렇게 본다)
 * @property {boolean} canInstall 이 런처가 스스로 설치까지 할 수 있는지
 */

/** 'ready' 상태의 안내문. auto 값마다 다르다. */
const READY_MESSAGES = {
  off: (version) => `새 버전 ${version} 준비 완료. 런처를 다시 시작하면 설치됩니다.`,
  waiting: (version) => `새 버전 ${version} 준비 완료. 하던 일이 끝나면 자동으로 설치합니다.`,
  failed: (version) => `새 버전 ${version} 준비 완료. 자동 설치가 끝나지 않았습니다. "재시작해서 설치"를 눌러 주세요.`,
};

class UpdateManager {
  /**
   * @param {object} options
   * @param {object | null} options.autoUpdater electron-updater 의 autoUpdater. 지원하지 않는 환경이면 null
   * @param {boolean} options.canInstall 설치 파일로 설치된 런처인지 (true 일 때만 자동으로 내려받아 설치)
   * @param {string} options.unsupportedReason canInstall 이 false 이거나 autoUpdater 가 없을 때의 안내문
   * @param {(state: UpdateState) => void} options.onChange 상태가 바뀔 때마다 호출
   * @param {() => boolean} [options.autoInstall] 받아 둔 업데이트를 스스로 설치해도 되는지 (자동 업데이트 설정)
   * @param {() => boolean} [options.isBusy] 런처가 지금 하는 일이 있는지. 있으면 스스로 다시 시작하지 않는다
   * @param {{read: () => string | null, write: (version: string) => void}} [options.attempts] 스스로 설치를 시도한 버전을 적어 두는 곳
   * @param {number} [options.delayMs] 곧 다시 시작한다고 알린 뒤 실제로 설치하기까지의 시간
   * @param {{set: (callback: () => void, ms: number) => any, clear: (timer: any) => void}} [options.timers] 타이머 (테스트에서 바꿔 끼운다)
   * @param {(message: string, code: string | undefined) => void} [options.log] 시도 기록을 읽거나 쓰지 못해 스스로 설치하지 않게 됐을 때 남길 곳.
   *   짧은 안내문과 오류 코드만 넘긴다 (경로나 운영체제가 준 오류 글은 넘기지 않는다)
   */
  constructor({
    autoUpdater,
    canInstall,
    unsupportedReason,
    onChange,
    autoInstall = () => false,
    isBusy = () => false,
    attempts = { read: () => null, write: () => {} },
    delayMs = 3000,
    timers = { set: setTimeout, clear: clearTimeout },
    log = () => {},
  }) {
    this.autoUpdater = autoUpdater;
    this.canInstall = Boolean(autoUpdater) && canInstall;
    this.unsupportedReason = unsupportedReason;
    this.onChange = onChange;
    this.autoInstall = autoInstall;
    this.isBusy = isBusy;
    this.attempts = attempts;
    this.delayMs = delayMs;
    this.timers = timers;
    this.log = log;
    /** @type {UpdateState} */
    this.state = autoUpdater
      ? { status: 'idle', canInstall: this.canInstall }
      : { status: 'unsupported', message: unsupportedReason, canInstall: false };
    /** 사용자가 직접 "지금 확인"을 눌렀는지. 자동 확인에서 난 오류는 조용히 넘긴다. */
    this.manual = false;
    /**
     * 스스로 설치하려고 걸어 둔 타이머. 'installing' 상태에서만 있다.
     * @type {{handle: any} | null}
     */
    this.installTimer = null;
    /**
     * 이번 실행에서 더는 스스로 설치하지 않을 버전: 이미 한 번 시도했거나, 시도 기록을 읽거나 쓰지 못했다.
     * @type {string | null}
     */
    this.noRetryVersion = null;

    if (autoUpdater) {
      this.bind(autoUpdater);
    }
  }

  bind(autoUpdater) {
    // 설치할 수 있는 런처에서만 자동으로 내려받는다. 그 밖에는 새 버전이 있다는 것만 확인한다.
    autoUpdater.autoDownload = this.canInstall;
    autoUpdater.autoInstallOnAppQuit = this.canInstall;

    autoUpdater.on('checking-for-update', () => this.set({ status: 'checking' }));
    autoUpdater.on('update-not-available', () => this.set({ status: 'up-to-date', message: '최신 버전을 쓰고 있습니다.' }));
    autoUpdater.on('update-available', (info) => {
      this.set(
        this.canInstall
          ? { status: 'downloading', version: info.version, percent: 0, message: `새 버전 ${info.version} 을 내려받는 중입니다.` }
          : { status: 'available', version: info.version, message: `새 버전 ${info.version} 이 나왔습니다. ${this.unsupportedReason}` },
      );
    });
    autoUpdater.on('download-progress', (progress) => {
      this.set({ status: 'downloading', version: this.state.version, percent: Math.round(progress.percent || 0), message: this.state.message });
    });
    autoUpdater.on('update-downloaded', (info) => {
      this.set(this.readyState(info.version));
      this.reconsider();
    });
    autoUpdater.on('error', (error) => {
      // 자동 확인이 실패한 것(인터넷 없음, 아직 릴리스가 없음 등)은 방해하지 않고 넘어간다.
      if (this.manual) {
        this.set({ status: 'error', message: `업데이트를 확인하지 못했습니다: ${firstLine(error)}` });
      } else {
        this.set({ status: 'idle' });
      }
    });
  }

  set(state) {
    // 'installing' 을 벗어나면 걸어 둔 타이머도 함께 지운다.
    this.cancelInstallTimer();
    this.state = { ...state, canInstall: this.canInstall };
    this.onChange(this.state);
  }

  /**
   * 새 버전이 있는지 확인한다.
   * @param {boolean} manual 사용자가 직접 누른 확인이면 true (실패했을 때 안내문을 보여 준다)
   */
  async check(manual) {
    if (!this.autoUpdater) {
      return this.state;
    }

    // 이미 받는 중이거나 받아 둔 업데이트가 있으면 다시 확인하지 않는다.
    if (['checking', 'downloading', 'ready', 'installing'].includes(this.state.status)) {
      return this.state;
    }

    this.manual = manual;

    try {
      await this.autoUpdater.checkForUpdates();
    } catch (error) {
      // 오류는 'error' 이벤트로도 오지만, 이벤트 없이 실패하는 경우를 대비해 한 번 더 정리한다.
      if (this.state.status === 'checking') {
        this.set(manual ? { status: 'error', message: `업데이트를 확인하지 못했습니다: ${firstLine(error)}` } : { status: 'idle' });
      }
    }

    return this.state;
  }

  /**
   * 받아 둔 버전의 'ready' 상태. auto 와 안내문은 지금 조건에 맞춘다.
   * @returns {UpdateState}
   */
  readyState(version) {
    let auto = 'waiting';

    if (!this.canInstall || !this.autoInstall()) {
      auto = 'off';
    } else if (this.alreadyAttempted(version)) {
      // 한 번 시도했는데 같은 버전으로 다시 켜졌다. 끝없이 다시 시작하지 않게 버튼에 맡긴다.
      auto = 'failed';
    }

    return { status: 'ready', version, auto, message: READY_MESSAGES[auto](version) };
  }

  /**
   * 이 버전을 스스로 설치하려고 한 적이 있는지. 기록을 믿을 수 없으면(읽거나 쓰지 못함) 한 적이 있는 것으로 본다.
   * 모르는 채로 다시 시작했다가 설치가 끝나지 않으면 켤 때마다 다시 시작하게 되므로, 그럴 바에는 버튼에 맡긴다.
   */
  alreadyAttempted(version) {
    if (this.noRetryVersion === version) {
      return true;
    }

    try {
      return this.attempts.read() === version;
    } catch (error) {
      // 버전을 기억해 둬서, 다시 판단할 때마다 되풀이해서 읽고 남기지 않는다.
      this.noRetryVersion = version;
      this.log('자동 설치를 시도한 기록을 읽지 못해서 스스로 설치하지 않습니다.', error && error.code);
      return true;
    }
  }

  /**
   * 지금 상태에서 스스로 설치할지 다시 판단한다. 런처가 한가해졌을 수 있을 때(요청이나 게임이 끝남)와
   * 자동 업데이트 설정이 바뀌었을 수 있을 때 부른다. 받아 둔 업데이트가 없으면 아무것도 하지 않는다.
   */
  reconsider() {
    const { status, version } = this.state;

    if (status !== 'ready' && status !== 'installing') {
      return;
    }

    const ready = this.readyState(version);

    if (ready.auto === 'waiting' && !this.isBusy()) {
      // 막는 것이 없다. 이미 알리고 타이머가 걸려 있으면 그대로 둔다. 'installing' 인데 타이머가 없으면(버튼으로 설치를
      // 시작했는데 런처가 꺼지지 않음) 버튼 없이 멈춰 있지 않게 다시 건다.
      if (!this.installTimer) {
        this.scheduleInstall(version);
      }

      return;
    }

    if (status !== 'ready' || this.state.auto !== ready.auto) {
      this.set(ready);
    }
  }

  /** 곧 다시 시작한다고 알리고(이미 알렸으면 생략), delayMs 뒤에 설치하도록 타이머를 건다. */
  scheduleInstall(version) {
    if (this.state.status !== 'installing') {
      this.set({ status: 'installing', version, message: `새 버전 ${version} 을 설치합니다. 런처가 곧 다시 시작됩니다.` });
    }

    const timer = { handle: null };
    timer.handle = this.timers.set(() => {
      // 지운 타이머가 뒤늦게 불려도 아무것도 하지 않는다.
      if (this.installTimer !== timer) {
        return;
      }

      this.installTimer = null;

      if (this.isBusy()) {
        // 알린 뒤에 다른 일이 시작됐다 (플레이 준비 등). 그 일이 끝나면 reconsider() 가 다시 불린다.
        this.set(this.readyState(version));
        return;
      }

      // 스스로 하는 시도는 여기서 한 번뿐이다. 아래에서 기록을 적지 못해도, 설치가 시작되지 않아 런처가 그대로 켜져 있어도
      // 이번 실행에서는 이 버전을 다시 시도하지 않는다.
      this.noRetryVersion = version;

      // 설치가 끝나지 않고 같은 버전으로 다시 켜졌을 때 또 다시 시작하지 않게, 시도했다는 것을 먼저 적어 둔다.
      try {
        this.attempts.write(version);
      } catch (error) {
        // 적지 못했으면 다시 시작하지 않는다. 기록이 없으면 다음에 켜졌을 때 이미 시도했다는 것을 알 수 없다.
        this.log('자동 설치를 시도한 기록을 쓰지 못해서 스스로 설치하지 않습니다.', error && error.code);
        this.set(this.readyState(version));
        return;
      }

      this.autoUpdater.quitAndInstall(false, true);
    }, this.delayMs);
    this.installTimer = timer;
  }

  cancelInstallTimer() {
    if (this.installTimer) {
      this.timers.clear(this.installTimer.handle);
      this.installTimer = null;
    }
  }

  /**
   * 받아 둔 업데이트를 지금 설치한다 (런처가 꺼졌다가 새 버전으로 다시 켜진다).
   * 스스로 설치하려고 기다리던 중이면 기다리지 않고 바로 한다.
   */
  install() {
    if (!['ready', 'installing'].includes(this.state.status) || !this.canInstall) {
      return false;
    }

    this.cancelInstallTimer();
    this.autoUpdater.quitAndInstall(false, true);
    return true;
  }
}

/**
 * 스스로 설치를 시도한 버전을 적어 두는 파일. 내용은 { "autoInstallAttempt": "<버전>" }.
 * 런처가 꺼졌다 켜져도 남아 있어야 해서 파일에 둔다.
 *
 * 이 기록이 같은 버전을 되풀이해서 설치하려 드는 것을 막는 유일한 장치다. 그래서 읽거나 쓰지 못한 것을 "시도한 적 없음"으로
 * 넘기지 않고 던진다 (UpdateManager 는 던지면 스스로 설치하지 않는다).
 *
 * @param {string} filePath
 * @returns {{read: () => string | null, write: (version: string) => void}}
 *   read 는 파일이 없거나 내용이 형식에 맞지 않을 때만 null, 그 밖의 파일 오류는 던진다. write 는 쓰지 못하면 던진다
 */
function createAttemptStore(filePath) {
  return {
    read() {
      let text;

      try {
        text = fs.readFileSync(filePath, 'utf8');
      } catch (error) {
        // 파일이 없는 것만 "아직 시도한 적 없음"이다. 권한이 없거나 잠겨 있는 것은 "모름"이다.
        if (error.code === 'ENOENT') {
          return null;
        }

        throw error;
      }

      try {
        const version = JSON.parse(text).autoInstallAttempt;
        return typeof version === 'string' ? version : null;
      } catch {
        // 깨진 내용은 없는 것으로 본다. 다음에 쓸 때 덮어쓴다.
        return null;
      }
    },

    write(version) {
      fs.writeFileSync(filePath, JSON.stringify({ autoInstallAttempt: version }));
    },
  };
}

function firstLine(error) {
  return String((error && error.message) || error).split('\n')[0].slice(0, 200);
}

module.exports = { UpdateManager, createAttemptStore };
