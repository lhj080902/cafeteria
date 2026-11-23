'use strict';

/*
 * 런처 자동 업데이트.
 *
 * GitHub 릴리스에 올린 새 설치 파일을 electron-updater 로 받아서, 런처를 끌 때(또는 "지금 재시작"을 누르면) 설치한다.
 * - 설치 파일(Setup.exe)로 설치한 Windows 런처에서만 자동으로 설치된다.
 * - 압축본(zip)으로 쓰는 경우나 다른 OS에서는 새 버전이 있다는 것만 알려 주고 다운로드 페이지를 안내한다.
 * - Cafeteria 모드 jar 는 런처 안에 들어 있으므로, 런처가 업데이트되면 다음 플레이 때 모드도 함께 바뀐다.
 */

/**
 * 화면에 보여 줄 업데이트 상태.
 * @typedef {object} UpdateState
 * @property {'idle'|'unsupported'|'checking'|'up-to-date'|'available'|'downloading'|'ready'|'error'} status
 * @property {string} [version] 새 버전
 * @property {number} [percent] 다운로드 진행률(0~100)
 * @property {string} [message] 안내문
 * @property {boolean} canInstall 이 런처가 스스로 설치까지 할 수 있는지
 */

class UpdateManager {
  /**
   * @param {object} options
   * @param {object | null} options.autoUpdater electron-updater 의 autoUpdater. 지원하지 않는 환경이면 null
   * @param {boolean} options.canInstall 설치 파일로 설치된 런처인지 (true 일 때만 자동으로 내려받아 설치)
   * @param {string} options.unsupportedReason canInstall 이 false 이거나 autoUpdater 가 없을 때의 안내문
   * @param {(state: UpdateState) => void} options.onChange 상태가 바뀔 때마다 호출
   */
  constructor({ autoUpdater, canInstall, unsupportedReason, onChange }) {
    this.autoUpdater = autoUpdater;
    this.canInstall = Boolean(autoUpdater) && canInstall;
    this.unsupportedReason = unsupportedReason;
    this.onChange = onChange;
    /** @type {UpdateState} */
    this.state = autoUpdater
      ? { status: 'idle', canInstall: this.canInstall }
      : { status: 'unsupported', message: unsupportedReason, canInstall: false };
    /** 사용자가 직접 "지금 확인"을 눌렀는지. 자동 확인에서 난 오류는 조용히 넘긴다. */
    this.manual = false;

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
      this.set({ status: 'ready', version: info.version, message: `새 버전 ${info.version} 준비 완료. 런처를 다시 시작하면 설치됩니다.` });
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
    if (['checking', 'downloading', 'ready'].includes(this.state.status)) {
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

  /** 받아 둔 업데이트를 지금 설치한다 (런처가 꺼졌다가 새 버전으로 다시 켜진다). */
  install() {
    if (this.state.status !== 'ready' || !this.canInstall) {
      return false;
    }

    this.autoUpdater.quitAndInstall(false, true);
    return true;
  }
}

function firstLine(error) {
  return String((error && error.message) || error).split('\n')[0].slice(0, 200);
}

module.exports = { UpdateManager };
