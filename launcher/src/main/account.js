'use strict';

const fsp = require('node:fs/promises');
const { readJson, writeFileAtomic } = require('./fs-util');
const { LauncherError } = require('./errors');

/** Minecraft 토큰이 이 시간 안에 만료되면 미리 갱신한다. */
const REFRESH_MARGIN_MS = 5 * 60_000;

/**
 * Microsoft 계정 저장소 (userData/account.json).
 * refresh 토큰은 OS 보안 저장소로 암호화해서 디스크에 두고, Minecraft 접근 토큰은 메모리에만 둔다.
 * 토큰은 화면(renderer)에 넘기지 않고 로그에도 남기지 않는다.
 */
class AccountManager {
  /**
   * @param {{file: string, vault?: {isAvailable: () => boolean, encrypt: (text: string) => Buffer, decrypt: (data: Buffer) => string} | null, auth?: object, now?: () => number}} options
   */
  constructor({ file, vault = null, auth = require('./ms-auth'), now = Date.now }) {
    this.file = file;
    this.vault = vault;
    this.auth = auth;
    this.now = now;
    this.account = null;
    this.minecraft = null;
    this.pending = null;
    /** 로그아웃·다시 로그인할 때마다 올라간다. 진행 중이던 갱신이 그 사이 바뀐 계정을 덮어쓰지 않게 하는 용도. */
    this.generation = 0;
  }

  async load() {
    this.account = null;
    this.minecraft = null;
    const stored = await readJson(this.file, null);
    const refreshToken = this.decryptToken(stored && stored.refreshToken);

    if (refreshToken && typeof stored.name === 'string') {
      this.account = { name: stored.name, uuid: typeof stored.uuid === 'string' ? stored.uuid : '', refreshToken };
    }

    return this;
  }

  /** 화면에 넘겨도 되는 값만. */
  publicView() {
    return { loggedIn: Boolean(this.account), name: this.account ? this.account.name : '' };
  }

  async completeLogin(code) {
    const { accessToken, refreshToken } = await this.auth.redeemCode(code);
    const minecraft = await this.auth.minecraftLogin(accessToken, this.now());
    this.generation += 1;
    await this.store(refreshToken, minecraft);
    return this.publicView();
  }

  /** 게임 실행에 쓸 유효한 세션. 만료가 가까우면 갱신하고, 동시에 여러 번 불러도 갱신은 한 번만 한다. */
  async session() {
    if (!this.account) {
      throw loginRequired();
    }

    if (!this.minecraft || this.minecraft.expiresAt <= this.now() + REFRESH_MARGIN_MS) {
      // refresh 토큰은 쓸 때마다 바뀌므로, 진행 중인 갱신이 있으면 그 결과를 함께 기다린다.
      this.pending = this.pending || this.refresh().finally(() => {
        this.pending = null;
      });
      await this.pending;
    }

    if (!this.minecraft) {
      throw loginRequired();
    }

    const { accessToken, name, uuid, xuid } = this.minecraft;
    return { accessToken, name, uuid, xuid };
  }

  async logout() {
    await this.clear();
    return this.publicView();
  }

  async refresh() {
    const generation = this.generation;

    try {
      const { accessToken, refreshToken } = await this.auth.refreshTokens(this.account.refreshToken);
      const minecraft = await this.auth.minecraftLogin(accessToken, this.now());

      if (generation !== this.generation) {
        throw loginRequired();
      }

      await this.store(refreshToken, minecraft);
    } catch (error) {
      if (generation !== this.generation) {
        // 갱신하는 사이 계정이 바뀌었다. 이전 계정의 결과는 새 계정에 영향을 주지 않는다.
        throw loginRequired();
      }

      if (error && error.code === 'LOGIN_EXPIRED') {
        await this.clear();
      }

      throw error;
    }
  }

  async store(refreshToken, minecraft) {
    this.account = { name: minecraft.name, uuid: minecraft.uuid, refreshToken };
    this.minecraft = {
      accessToken: minecraft.accessToken,
      expiresAt: minecraft.expiresAt,
      name: minecraft.name,
      uuid: minecraft.uuid,
      xuid: minecraft.xuid,
    };

    // 암호화를 쓸 수 없으면 평문으로 쓰지 않고 실행 중에만 유지한다.
    if (this.vault && this.vault.isAvailable()) {
      const encrypted = this.vault.encrypt(refreshToken).toString('base64');
      await writeFileAtomic(this.file, JSON.stringify({ name: minecraft.name, uuid: minecraft.uuid, refreshToken: { encrypted } }, null, 2));
    }
  }

  async clear() {
    this.generation += 1;
    this.account = null;
    this.minecraft = null;
    await fsp.rm(this.file, { force: true });
  }

  /** 저장된 refresh 토큰을 푼다. 형식이 틀렸거나 다른 PC·계정에서 암호화된 값이면 빈 문자열. */
  decryptToken(stored) {
    if (!stored || typeof stored.encrypted !== 'string' || !this.vault || !this.vault.isAvailable()) {
      return '';
    }

    try {
      const text = this.vault.decrypt(Buffer.from(stored.encrypted, 'base64'));
      return typeof text === 'string' ? text : '';
    } catch {
      return '';
    }
  }
}

function loginRequired() {
  return new LauncherError('LOGIN_REQUIRED', 'Microsoft 계정으로 로그인해 주세요.');
}

module.exports = { AccountManager, REFRESH_MARGIN_MS };
