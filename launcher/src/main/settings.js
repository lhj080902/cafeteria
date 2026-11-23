'use strict';

const { readJson, writeFileAtomic } = require('./fs-util');

const MIN_MEMORY_GB = 2;
const MAX_MEMORY_GB = 32;

const RESOLUTION_LIMITS = Object.freeze({ width: [640, 7680], height: [360, 4320] });
const MAX_JVM_ARGS_LENGTH = 1000;

const DEFAULTS = Object.freeze({
  memoryGb: 4,
  /** 게임 창 크기를 직접 정할지 여부. 꺼져 있으면 마인크래프트 기본 크기로 열린다. */
  resolutionEnabled: false,
  resolutionWidth: 1280,
  resolutionHeight: 720,
  /** 기본 JVM 옵션 뒤에 덧붙일 인수. */
  jvmArgs: '',
  /** 게임을 실행할 Java 실행 파일. 비어 있으면 공식 런처에 들어 있는 Java 를 쓴다. */
  javaPath: '',
  closeOnLaunch: false,
  /** 런처를 켤 때 새 버전이 있는지 확인하고 자동으로 내려받을지 여부. */
  autoUpdate: true,
  /** 공식 런처 실행 파일 경로. 비어 있으면 자동으로 찾는다. */
  launcherPath: '',
  /** 게임 폴더. 비어 있으면 기본 위치(.cafeteria)를 쓴다. */
  gameDir: '',
  /** CurseForge API 키. { encrypted: base64 } 또는 { plain: string } 형태로 저장한다. */
  curseforgeKey: null,
});

/**
 * 런처 설정 저장소 (userData/settings.json).
 * CurseForge API 키는 가능하면 OS 보안 저장소(Windows DPAPI 등)로 암호화해서 저장하고,
 * 화면(renderer)에는 키 값 자체를 절대 넘기지 않는다.
 */
class SettingsStore {
  /**
   * @param {string} file
   * @param {{isAvailable: () => boolean, encrypt: (text: string) => Buffer, decrypt: (data: Buffer) => string} | null} vault
   */
  constructor(file, vault = null) {
    this.file = file;
    this.vault = vault;
    this.values = { ...DEFAULTS };
  }

  async load() {
    const stored = await readJson(this.file, {});
    this.values = { ...DEFAULTS, ...sanitize(stored) };

    if (isStoredKey(stored.curseforgeKey)) {
      this.values.curseforgeKey = stored.curseforgeKey;
    }

    return this;
  }

  /** 화면에 넘겨도 되는 값만 담은 사본. */
  publicView() {
    const { curseforgeKey, ...rest } = this.values;
    return { ...rest, hasCurseforgeKey: Boolean(this.curseforgeKey()) };
  }

  get(name) {
    return this.values[name];
  }

  async update(partial) {
    Object.assign(this.values, sanitize(partial));
    await this.save();
    return this.publicView();
  }

  /** 게임·런처 설정을 기본값으로 되돌린다. 게임 폴더 위치와 CurseForge 키는 그대로 둔다. */
  async reset() {
    this.values = { ...DEFAULTS, gameDir: this.values.gameDir, curseforgeKey: this.values.curseforgeKey };
    await this.save();
    return this.publicView();
  }

  /** 공식 런처 프로필에 넣을 실행 옵션. */
  profileOptions() {
    const { memoryGb, jvmArgs, javaPath, resolutionEnabled, resolutionWidth, resolutionHeight } = this.values;
    return {
      memoryGb,
      jvmArgs,
      javaPath,
      resolution: resolutionEnabled ? { width: resolutionWidth, height: resolutionHeight } : null,
    };
  }

  async setCurseforgeKey(key) {
    const trimmed = typeof key === 'string' ? key.trim() : '';

    if (!trimmed) {
      this.values.curseforgeKey = null;
    } else if (this.vault && this.vault.isAvailable()) {
      this.values.curseforgeKey = { encrypted: this.vault.encrypt(trimmed).toString('base64') };
    } else {
      this.values.curseforgeKey = { plain: trimmed };
    }

    await this.save();
    return this.publicView();
  }

  /** 저장된 CurseForge API 키. 없거나 복호화할 수 없으면 빈 문자열. */
  curseforgeKey() {
    const stored = this.values.curseforgeKey;

    if (!stored) {
      return '';
    }

    if (typeof stored.plain === 'string') {
      return stored.plain;
    }

    if (typeof stored.encrypted === 'string' && this.vault && this.vault.isAvailable()) {
      try {
        return this.vault.decrypt(Buffer.from(stored.encrypted, 'base64'));
      } catch {
        // 다른 PC·계정에서 암호화된 값이면 풀 수 없다. 키가 없는 것으로 취급해 다시 입력받는다.
        return '';
      }
    }

    return '';
  }

  async save() {
    await writeFileAtomic(this.file, JSON.stringify(this.values, null, 2));
  }
}

function isStoredKey(value) {
  return Boolean(value) && typeof value === 'object' && (typeof value.encrypted === 'string' || typeof value.plain === 'string');
}

/** 형식이 맞는 항목만 골라낸다. 알 수 없는 항목과 잘못된 값은 버린다. */
function sanitize(input) {
  const result = {};

  if (!input || typeof input !== 'object') {
    return result;
  }

  if (Number.isFinite(input.memoryGb)) {
    result.memoryGb = Math.min(MAX_MEMORY_GB, Math.max(MIN_MEMORY_GB, Math.round(input.memoryGb)));
  }

  for (const name of ['closeOnLaunch', 'resolutionEnabled', 'autoUpdate']) {
    if (typeof input[name] === 'boolean') {
      result[name] = input[name];
    }
  }

  for (const [name, [min, max]] of [['resolutionWidth', RESOLUTION_LIMITS.width], ['resolutionHeight', RESOLUTION_LIMITS.height]]) {
    if (Number.isFinite(input[name])) {
      result[name] = Math.min(max, Math.max(min, Math.round(input[name])));
    }
  }

  if (typeof input.jvmArgs === 'string') {
    // 프로필 파일에 한 줄로 들어가는 값이라 줄바꿈과 탭은 공백으로 바꾼다.
    result.jvmArgs = input.jvmArgs.replace(/\s+/g, ' ').trim().slice(0, MAX_JVM_ARGS_LENGTH);
  }

  for (const name of ['launcherPath', 'gameDir', 'javaPath']) {
    if (typeof input[name] === 'string') {
      result[name] = input[name].trim();
    }
  }

  return result;
}

module.exports = { SettingsStore, DEFAULTS, MIN_MEMORY_GB, MAX_MEMORY_GB, RESOLUTION_LIMITS };
