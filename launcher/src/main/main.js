'use strict';

const { app, BrowserWindow, dialog, ipcMain, net, safeStorage, shell } = require('electron');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { EXTERNAL_HOSTS, LOADER_VERSION, MINECRAFT_VERSION, USER_AGENT } = require('./constants');
const { LauncherError } = require('./errors');
const http = require('./http');
const installer = require('./installer');
const { installMod } = require('./mod-installer');
const { ModManager } = require('./mods');
const { isOfficialLauncherRunning, launchOfficialLauncher } = require('./official-launcher');
const paths = require('./paths');
const { SettingsStore } = require('./settings');
const curseforge = require('./sources/curseforge');
const modrinth = require('./sources/modrinth');

const SEARCH_PAGE_SIZE = 20;
const MAX_DROPPED_FILES = 200;

// 테스트나 휴대용 설치에서 런처 데이터 위치를 바꿀 수 있게 한다.
if (process.env.CAFETERIA_USER_DATA) {
  app.setPath('userData', process.env.CAFETERIA_USER_DATA);
}

app.userAgentFallback = USER_AGENT;

const resourcesDir = app.isPackaged ? process.resourcesPath : path.join(__dirname, '..', '..', 'resources');

/** @type {BrowserWindow | null} */
let window = null;
/** @type {SettingsStore} */
let settings;
let playing = false;

function gameDir() {
  return settings.get('gameDir') || paths.defaultGameDir();
}

function modManager() {
  return new ModManager(gameDir());
}

function sendProgress(scope, progress) {
  if (window && !window.isDestroyed()) {
    window.webContents.send('progress', { scope, ...progress });
  }
}

function createWindow() {
  window = new BrowserWindow({
    width: 1040,
    height: 680,
    minWidth: 920,
    minHeight: 600,
    frame: false,
    show: false,
    backgroundColor: '#17120f',
    title: 'Cafeteria',
    icon: path.join(resourcesDir, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  window.removeMenu();
  // 런처 화면은 내장 페이지 하나뿐이다. 다른 페이지로 이동하거나 새 창을 여는 것은 모두 막는다.
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', (event) => event.preventDefault());
  window.once('ready-to-show', () => window.show());
  window.on('closed', () => {
    window = null;
  });
  window.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
}

/**
 * IPC 처리기를 등록한다. 결과는 항상 { ok, data } 또는 { ok: false, error } 형태로 돌려줘서
 * 화면이 한국어 안내문을 그대로 보여 줄 수 있게 한다.
 */
function handle(channel, handler) {
  ipcMain.handle(channel, async (event, ...args) => {
    if (!window || event.sender !== window.webContents) {
      return { ok: false, error: { code: 'FORBIDDEN', message: '허용되지 않은 요청입니다.' } };
    }

    try {
      return { ok: true, data: await handler(...args) };
    } catch (error) {
      if (error instanceof LauncherError) {
        return { ok: false, error: { code: error.code, message: error.message, details: error.details } };
      }

      console.error(`[${channel}]`, error);
      return { ok: false, error: { code: 'UNEXPECTED', message: `예상하지 못한 오류가 발생했습니다: ${error.message}` } };
    }
  });
}

async function currentState() {
  const minecraftDir = paths.minecraftDir();
  const status = await installer.checkStatus({ minecraftDir, gameDir: gameDir(), resourcesDir });

  return {
    appVersion: app.getVersion(),
    minecraftVersion: MINECRAFT_VERSION,
    loaderVersion: LOADER_VERSION,
    platform: process.platform,
    minecraftDir,
    gameDir: gameDir(),
    status,
    settings: settings.publicView(),
  };
}

/** 설치 작업(플레이 준비, 설치 복구)이 동시에 두 번 돌지 않게 한다. */
async function exclusive(task) {
  if (playing) {
    throw new LauncherError('BUSY', '이미 준비하고 있습니다. 잠시만 기다려 주세요.');
  }

  playing = true;

  try {
    return await task();
  } finally {
    playing = false;
  }
}

function install(scope, repair) {
  return installer.ensureInstalled({
    minecraftDir: paths.minecraftDir(),
    gameDir: gameDir(),
    resourcesDir,
    profile: settings.profileOptions(),
    modManager: modManager(),
    repair,
    onProgress: (progress) => sendProgress(scope, progress),
  });
}

function play() {
  return exclusive(async () => {
    // 공식 런처가 떠 있는 동안에는 프로필 파일을 바꿔도 반영되지 않을 수 있어서 미리 확인해 둔다.
    const wasRunning = await isOfficialLauncherRunning();
    await install('play', false);
    const result = await launchOfficialLauncher({ customPath: settings.get('launcherPath') });

    if (result.launched && settings.get('closeOnLaunch')) {
      setTimeout(() => app.quit(), 1500);
    }

    return { launched: result.launched, wasRunning };
  });
}

function sourceApi(source) {
  if (source === 'modrinth') {
    return { search: (query, page) => modrinth.search(query, page), resolveFile: (id) => modrinth.resolveFile(id) };
  }

  if (source === 'curseforge') {
    const key = settings.curseforgeKey();
    return { search: (query, page) => curseforge.search(query, page, key), resolveFile: (id) => curseforge.resolveFile(id, key) };
  }

  throw new LauncherError('BAD_SOURCE', '알 수 없는 모드 사이트입니다.');
}

function registerHandlers() {
  handle('state:get', currentState);
  handle('game:play', play);

  handle('game:repair', () => exclusive(() => install('repair', true)));
  handle('game:removeProfile', () => exclusive(() => installer.removeProfile(paths.minecraftDir())));

  handle('game:openFolder', async () => {
    await fsp.mkdir(gameDir(), { recursive: true });
    await shell.openPath(gameDir());
  });

  handle('game:openLogs', async () => {
    const directory = path.join(gameDir(), 'logs');
    await fsp.mkdir(directory, { recursive: true });
    await shell.openPath(directory);
  });

  handle('mods:list', () => modManager().list());

  handle('mods:addDialog', async () => {
    const result = await dialog.showOpenDialog(window, {
      title: '추가할 모드 파일 선택',
      filters: [{ name: 'Minecraft 모드 (*.jar)', extensions: ['jar'] }],
      properties: ['openFile', 'multiSelections'],
    });

    return result.canceled ? { added: [], rejected: [] } : modManager().addLocalFiles(result.filePaths);
  });

  handle('mods:addPaths', (filePaths) => {
    if (!Array.isArray(filePaths) || filePaths.length > MAX_DROPPED_FILES || filePaths.some((file) => typeof file !== 'string')) {
      throw new LauncherError('BAD_REQUEST', '추가할 파일 목록이 올바르지 않습니다.');
    }

    return modManager().addLocalFiles(filePaths);
  });

  handle('mods:setEnabled', (fileName, enabled) => modManager().setEnabled(fileName, Boolean(enabled)));
  handle('mods:remove', (fileName) => modManager().remove(fileName));

  handle('mods:openFolder', async () => {
    const directory = modManager().modsDir;
    await fsp.mkdir(directory, { recursive: true });
    await shell.openPath(directory);
  });

  handle('browse:search', async (source, query, page) => {
    const api = sourceApi(source);
    const offset = Math.max(0, Math.floor(Number(page) || 0)) * SEARCH_PAGE_SIZE;
    const result = await api.search(String(query || '').trim().slice(0, 100), { offset, limit: SEARCH_PAGE_SIZE });
    const installed = await modManager().installedProjects(source);

    return {
      total: result.total,
      pageSize: SEARCH_PAGE_SIZE,
      items: result.items.map((item) => ({ ...item, installed: installed.has(item.id) })),
    };
  });

  handle('browse:install', (source, projectId) => {
    const api = sourceApi(source);

    if (typeof projectId !== 'string' || !/^[\w-]{1,64}$/.test(projectId)) {
      throw new LauncherError('BAD_REQUEST', '모드 정보가 올바르지 않습니다.');
    }

    return installMod({
      source,
      projectId,
      resolveFile: api.resolveFile,
      modManager: modManager(),
      onProgress: (progress) => sendProgress(`install:${source}:${projectId}`, progress),
    });
  });

  handle('settings:update', (partial) => settings.update(partial));
  handle('settings:reset', () => settings.reset());

  handle('settings:chooseJava', async () => {
    const result = await dialog.showOpenDialog(window, {
      title: 'Java 실행 파일 선택 (javaw.exe 또는 java)',
      filters: process.platform === 'win32' ? [{ name: 'Java 실행 파일 (javaw.exe, java.exe)', extensions: ['exe'] }] : [],
      properties: ['openFile'],
    });

    return result.canceled ? settings.publicView() : settings.update({ javaPath: result.filePaths[0] });
  });
  handle('settings:setCurseforgeKey', (key) => settings.setCurseforgeKey(key));

  handle('settings:chooseLauncher', async () => {
    const result = await dialog.showOpenDialog(window, {
      title: '공식 Minecraft 런처 실행 파일 선택',
      filters: process.platform === 'win32' ? [{ name: '실행 파일 (*.exe)', extensions: ['exe'] }] : [],
      properties: ['openFile'],
    });

    return result.canceled ? settings.publicView() : settings.update({ launcherPath: result.filePaths[0] });
  });

  handle('settings:chooseGameDir', async () => {
    const result = await dialog.showOpenDialog(window, {
      title: '게임 폴더 선택 (모드와 설정이 저장될 곳)',
      properties: ['openDirectory', 'createDirectory'],
    });

    return result.canceled ? settings.publicView() : settings.update({ gameDir: result.filePaths[0] });
  });

  handle('app:openExternal', async (url) => {
    let parsed;

    try {
      parsed = new URL(String(url));
    } catch {
      parsed = null;
    }

    if (!parsed || parsed.protocol !== 'https:' || !EXTERNAL_HOSTS.includes(parsed.hostname)) {
      throw new LauncherError('UNTRUSTED_URL', '열 수 없는 주소입니다.');
    }

    await shell.openExternal(parsed.toString());
  });

  handle('window:minimize', () => window.minimize());
  handle('window:close', () => window.close());
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (window) {
      if (window.isMinimized()) {
        window.restore();
      }

      window.focus();
    }
  });

  app.whenReady().then(async () => {
    // 시스템 프록시 설정을 따르는 Chromium 네트워크 스택으로 요청한다.
    http.setFetch((url, options) => net.fetch(url, options));
    settings = await new SettingsStore(path.join(app.getPath('userData'), 'settings.json'), {
      isAvailable: () => safeStorage.isEncryptionAvailable(),
      encrypt: (text) => safeStorage.encryptString(text),
      decrypt: (data) => safeStorage.decryptString(data),
    }).load();
    registerHandlers();
    createWindow();
  });

  app.on('window-all-closed', () => app.quit());
}
