'use strict';

const { app, BrowserWindow, dialog, ipcMain, net, safeStorage, shell } = require('electron');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { AccountManager } = require('./account');
const { SETTINGS_EXTENSION, exportFlow, importFlow } = require('./client-settings');
const { EXTERNAL_HOSTS, LOADER_VERSION, MINECRAFT_VERSION, RELEASES_URL, USER_AGENT } = require('./constants');
const { LauncherError } = require('./errors');
const { quickStatus } = require('./game-files');
const http = require('./http');
const importer = require('./importer');
const { createInFlight } = require('./in-flight');
const installer = require('./installer');
const { openLoginWindow } = require('./login-window');
const { installMod } = require('./mod-installer');
const { applyModUpdate, checkModUpdates } = require('./mod-updater');
const { ModManager, baseName } = require('./mods');
const { isOfficialLauncherRunning, launchOfficialLauncher } = require('./official-launcher');
const paths = require('./paths');
const { playDirect, repairDirect } = require('./play-direct');
const { SettingsStore } = require('./settings');
const { UpdateManager, createAttemptStore } = require('./updater');
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
/** 공식 런처 없이 바로 실행하는 것은 Windows x64 에서만 지원한다. */
const directSupported = process.platform === 'win32' && process.arch === 'x64';

/** @type {BrowserWindow | null} */
let window = null;
/** @type {SettingsStore} */
let settings;
/** @type {AccountManager} */
let account;
/** @type {UpdateManager} */
let updates;
let playing = false;
/** 로그인 창이 열려 있는 동안 true. 동시에 두 개를 띄우지 않는다. */
let loggingIn = false;
/**
 * 바로 실행으로 띄운 게임 프로세스. 없으면 null.
 * @type {import('node:child_process').ChildProcess | null}
 */
let game = null;
/**
 * 이번 실행에서 공식 런처를 띄운 적이 있는지. 공식 런처에서 켠 게임은 언제 꺼지는지 런처가 알 수 없어서,
 * 한 번 띄운 뒤로는 런처를 끌 때까지 업데이트를 설치하려고 스스로 다시 시작하지 않는다 (createUpdateManager 의 isBusy).
 * 받아 둔 업데이트는 런처를 끌 때 조용히 설치되거나(autoInstallOnAppQuit) 다음에 켰을 때 설치된다.
 */
let officialLaunched = false;
let identifyingMods = false;
/**
 * 마지막으로 확인한 모드 업데이트. 화면에는 이름과 버전만 넘기고, 실제로 받을 주소는 여기 보관한 것만 쓴다.
 * @type {{updates: import('./mod-updater').ModUpdate[], checked: number, unknown: string[], notes: string[], checkedAt: number | null}}
 */
let modUpdates = { updates: [], checked: 0, unknown: [], notes: [], checkedAt: null };
/** 진행 중인 업데이트 확인. 동시에 두 번 확인하지 않는다. */
let modUpdateCheck = null;
/**
 * 화면이 보낸 요청 가운데 아직 처리 중인 것. 요청이 하나 끝날 때마다 런처가 한가해졌는지,
 * 자동 업데이트 설정이 바뀌었는지 볼 수 있게 받아 둔 런처 업데이트를 다시 판단한다. updates 는 앱이 준비된 뒤에 만들어진다.
 */
const inFlight = createInFlight(() => {
  if (updates) {
    updates.reconsider();
  }
});

function gameDir() {
  return settings.get('gameDir') || paths.defaultGameDir();
}

function modManager() {
  return new ModManager(gameDir());
}

function send(channel, payload) {
  if (window && !window.isDestroyed()) {
    window.webContents.send(channel, payload);
  }
}

function sendProgress(scope, progress) {
  send('progress', { scope, ...progress });
}

/** 지금 쓸 실행 방식. 바로 실행을 지원하지 않는 환경에서는 설정과 상관없이 공식 런처를 쓴다. */
function launchMode() {
  return directSupported && settings.get('launchMode') === 'direct' ? 'direct' : 'official';
}

/** 바로 실행한 게임 프로세스를 지켜보고, 시작과 종료를 화면에 알린다. */
function trackGame(child) {
  game = child;
  send('game', { running: true, exitCode: null });
  child.once('exit', (code) => {
    if (game === child) {
      game = null;
      send('game', { running: false, exitCode: code });
      // 런처를 켜 둔 채 게임을 하다 껐으면 이제 한가하다. 받아 둔 런처 업데이트를 설치해도 되는지 다시 판단한다.
      updates.reconsider();
    }
  });
}

/** 바로 실행한 게임이 켜져 있는지. 게임이 쓰는 파일을 바꾸는 흐름(game-running.js 의 assertGameNotRunning)에 넘긴다. */
const isGameRunning = () => game !== null;

/**
 * 자동 업데이트 관리자를 만든다. 설치 파일로 설치한 Windows 런처에서만 스스로 설치까지 하고,
 * 압축본으로 실행 중이면 새 버전이 있다는 것만 알려 준다.
 * 다 받은 업데이트는 자동 업데이트가 켜져 있고 런처가 하는 일이 없을 때(처리 중인 요청, 플레이 준비, 로그인, 켜져 있는 게임이 없고
 * 이번 실행에서 공식 런처를 띄운 적도 없을 때) 스스로 다시 시작해서 설치한다.
 */
function createUpdateManager() {
  const supported = app.isPackaged && process.platform === 'win32';
  const autoUpdater = supported ? require('electron-updater').autoUpdater : null;
  // NSIS 설치 프로그램은 실행 파일 옆에 언인스톨러를 둔다. 그 파일이 없으면 압축을 풀어서 쓰는 중이다.
  const installed = supported && fs.existsSync(path.join(path.dirname(process.execPath), `Uninstall ${app.getName()}.exe`));

  return new UpdateManager({
    autoUpdater,
    canInstall: installed,
    unsupportedReason: supported
      ? '압축본으로 실행 중이라 자동으로 설치할 수 없습니다. 새 설치 파일을 받아 주세요.'
      : '설치 파일로 설치한 Windows 런처에서만 자동 업데이트를 지원합니다.',
    onChange: (state) => send('update', state),
    autoInstall: () => settings.get('autoUpdate'),
    isBusy: () => inFlight.active() > 0 || playing || loggingIn || game !== null || officialLaunched,
    attempts: createAttemptStore(path.join(app.getPath('userData'), 'update-state.json')),
    log: (message, code) => console.error('[update]', message, code),
  });
}

/** 화면에 넘겨도 되는 모드 업데이트 정보 (받을 주소는 뺀다). */
function publicModUpdates() {
  return {
    updates: modUpdates.updates.map(({ fileName, name, source, currentVersion, newVersion }) => ({ fileName, name, source, currentVersion, newVersion })),
    checked: modUpdates.checked,
    unknown: modUpdates.unknown,
    notes: modUpdates.notes,
    checkedAt: modUpdates.checkedAt,
  };
}

function setModUpdates(next) {
  modUpdates = next;
  send('mod-updates', publicModUpdates());
}

/** 설치된 모드의 새 버전이 있는지 확인하고 결과를 화면에 알린다. */
function refreshModUpdates() {
  if (!modUpdateCheck) {
    modUpdateCheck = checkModUpdates({ modManager: modManager(), curseforgeKey: settings.curseforgeKey() })
      .then((result) => {
        setModUpdates({ ...result, checkedAt: Date.now() });
        return publicModUpdates();
      })
      .finally(() => {
        modUpdateCheck = null;
      });
  }

  return modUpdateCheck;
}

/** 모드 파일이 바뀌었을 때(삭제, 켜기/끄기, 교체) 보관해 둔 업데이트 목록을 그에 맞춘다. */
function forgetModUpdate(fileName, renamedTo = null) {
  const updates = modUpdates.updates
    .map((update) => (baseName(update.fileName) === baseName(fileName) ? renamedTo && { ...update, fileName: renamedTo } : update))
    .filter(Boolean);
  setModUpdates({ ...modUpdates, updates });
}

async function updateOneMod(fileName) {
  const update = modUpdates.updates.find((candidate) => candidate.fileName === fileName);

  if (!update) {
    throw new LauncherError('NO_UPDATE', '업데이트 정보를 찾을 수 없습니다. "업데이트 확인"을 다시 눌러 주세요.');
  }

  const result = await applyModUpdate({
    modManager: modManager(),
    update,
    resolveFile: sourceApi(update.source).resolveFile,
    onProgress: (progress) => sendProgress(`update:${fileName}`, progress),
  });
  forgetModUpdate(fileName);
  return { name: update.name, version: update.newVersion, ...result };
}

/** 직접 넣은 모드의 이름·아이콘을 Modrinth 에서 찾아 두고, 새로 알아낸 것이 있으면 화면에 알린다. */
function identifyModsInBackground(manager) {
  if (identifyingMods) {
    return;
  }

  identifyingMods = true;
  manager
    .identifyLocalMods()
    .then((found) => {
      if (found) {
        send('mods-updated');
      }
    })
    .catch((error) => console.error('[mods:identify]', error))
    .finally(() => {
      identifyingMods = false;
    });
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
  window.once('ready-to-show', () => {
    window.show();

    if (settings.get('autoUpdate')) {
      updates.check(false);
    }

    // 모드의 새 버전은 확인만 해 두고 "내 모드"에 표시한다. 인터넷이 없으면 조용히 넘어간다.
    if (settings.get('modUpdateCheck')) {
      refreshModUpdates().catch((error) => console.error('[mods:checkUpdates]', error.message));
    }
  });
  window.on('closed', () => {
    window = null;
  });
  window.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
}

/** 로그에 남겨도 되는 오류 정보 키. 토큰이나 실행 인수가 들어갈 수 있는 값은 넣지 않는다. */
const LOGGED_DETAIL_KEYS = ['step', 'status', 'cause', 'url', 'algorithm', 'javaPath'];

/** 오류 details 에서 위 키만 골라 한 줄로 만든다. 없으면 빈 문자열. */
function loggableDetails(details) {
  const picked = {};

  for (const key of LOGGED_DETAIL_KEYS) {
    if (details && details[key] !== undefined) {
      picked[key] = details[key];
    }
  }

  return Object.keys(picked).length ? JSON.stringify(picked) : '';
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
      return { ok: true, data: await inFlight.run(() => handler(...args)) };
    } catch (error) {
      if (error instanceof LauncherError) {
        // 로그인 창을 닫은 것은 사용자가 고른 일이라 남기지 않는다.
        if (error.code !== 'LOGIN_CANCELLED') {
          console.error(`[${channel}]`, error.code, error.message, loggableDetails(error.details));
        }

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
  const mode = launchMode();

  // 바로 실행은 공식 런처 프로필이 필요 없다. Cafeteria 모드와 게임 파일이 있으면 설치된 것으로 본다.
  if (mode === 'direct') {
    status.installed = status.modInstalled && (await quickStatus(paths.dataDir()));
  }

  return {
    appVersion: app.getVersion(),
    minecraftVersion: MINECRAFT_VERSION,
    loaderVersion: LOADER_VERSION,
    platform: process.platform,
    minecraftDir,
    gameDir: gameDir(),
    status,
    settings: settings.publicView(),
    update: updates.state,
    releasesUrl: RELEASES_URL,
    account: account.publicView(),
    game: { running: Boolean(game) },
    launch: { mode, directSupported },
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

/** 바로 실행에 쓰는 값. 플레이와 설치 복구가 같이 쓴다. */
function directOptions(scope) {
  return {
    gameDir: gameDir(),
    dataDir: paths.dataDir(),
    seedDir: paths.minecraftDir(),
    resourcesDir,
    modManager: modManager(),
    onProgress: (progress) => sendProgress(scope, progress),
  };
}

/**
 * Cafeteria 모드에 같은 기능이 들어 있는 모드가 켜져 있으면 꺼 둔다. 함께 켜지면 기능이 겹치기 때문에 플레이할 때마다 먼저 확인한다.
 *
 * @returns {Promise<string[]>} 꺼 둔 모드의 이름 (이름이 없는 모드는 파일 이름)
 */
async function disableSupersededMods() {
  const manager = modManager();
  const disabled = await manager.disableSuperseded();

  if (disabled.length === 0) {
    return [];
  }

  const mods = await manager.list();
  const names = [];

  for (const fileName of disabled) {
    const mod = mods.find((candidate) => baseName(candidate.fileName) === fileName);
    // 파일 이름이 바뀌었으니 보관해 둔 업데이트 정보도 새 이름을 따라가게 한다.
    forgetModUpdate(fileName, mod ? mod.fileName : null);
    names.push(mod ? mod.name : fileName);
  }

  send('mods-updated');
  return names;
}

function play() {
  return exclusive(async () => {
    const superseded = await disableSupersededMods();

    if (launchMode() === 'direct') {
      if (game) {
        throw new LauncherError('ALREADY_RUNNING', '게임이 이미 실행 중입니다.');
      }

      const child = await playDirect({ ...directOptions('play'), account, launcherVersion: app.getVersion(), options: settings.profileOptions() });
      trackGame(child);

      if (settings.get('closeOnLaunch')) {
        // 게임이 곧바로 꺼지면(설정 오류 등) 런처를 남겨서 알 수 있게 한다.
        setTimeout(() => {
          if (game === child) {
            app.quit();
          }
        }, 8000);
      }

      return { launched: true, mode: 'direct', superseded };
    }

    // 공식 런처가 떠 있는 동안에는 프로필 파일을 바꿔도 반영되지 않을 수 있어서 미리 확인해 둔다.
    const wasRunning = await isOfficialLauncherRunning();
    await install('play', false);
    const result = await launchOfficialLauncher({ customPath: settings.get('launcherPath') });

    if (result.launched) {
      officialLaunched = true;
    }

    if (result.launched && settings.get('closeOnLaunch')) {
      setTimeout(() => app.quit(), 1500);
    }

    return { launched: result.launched, wasRunning, mode: 'official', superseded };
  });
}

/** 직접 고른 jar 파일을 추가한다. 같은 모드의 예전 파일이 교체됐으면 그 파일의 업데이트 정보도 지운다. */
async function addLocalMods(filePaths) {
  const result = await modManager().addLocalFiles(filePaths);

  for (const fileName of [...result.added, ...result.replaced.map((entry) => entry.old)]) {
    forgetModUpdate(fileName);
  }

  return result;
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

  handle('game:repair', () =>
    exclusive(() => {
      if (launchMode() !== 'direct') {
        return install('repair', true);
      }

      // 실행 중인 게임이 쓰고 있는 파일을 바꾸지 않는다.
      if (game) {
        throw new LauncherError('GAME_RUNNING', '게임이 실행 중일 때는 설치를 복구할 수 없습니다. 게임을 끈 뒤 다시 시도해 주세요.');
      }

      return repairDirect(directOptions('repair'));
    }),
  );
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

  handle('mods:list', async () => {
    const manager = modManager();
    const mods = await manager.list();
    identifyModsInBackground(manager);
    return mods;
  });

  handle('mods:addDialog', async () => {
    const result = await dialog.showOpenDialog(window, {
      title: '추가할 모드 파일 선택',
      filters: [{ name: 'Minecraft 모드 (*.jar)', extensions: ['jar'] }],
      properties: ['openFile', 'multiSelections'],
    });

    return result.canceled ? { added: [], rejected: [], replaced: [] } : addLocalMods(result.filePaths);
  });

  handle('mods:addPaths', (filePaths) => {
    if (!Array.isArray(filePaths) || filePaths.length > MAX_DROPPED_FILES || filePaths.some((file) => typeof file !== 'string')) {
      throw new LauncherError('BAD_REQUEST', '추가할 파일 목록이 올바르지 않습니다.');
    }

    return addLocalMods(filePaths);
  });

  handle('mods:setEnabled', async (fileName, enabled) => {
    const renamed = await modManager().setEnabled(fileName, Boolean(enabled));
    forgetModUpdate(fileName, renamed);
    return renamed;
  });
  handle('mods:remove', async (fileName) => {
    await modManager().remove(fileName);
    forgetModUpdate(fileName);
  });

  handle('mods:getUpdates', publicModUpdates);
  handle('mods:checkUpdates', refreshModUpdates);
  handle('mods:update', (fileName) => exclusive(() => updateOneMod(fileName)));
  handle('mods:updateAll', () =>
    exclusive(async () => {
      const updated = [];
      const failed = [];
      const warnings = [];

      // 하나가 실패해도 나머지는 계속 진행하고, 무엇이 실패했는지 알려 준다.
      for (const update of [...modUpdates.updates]) {
        try {
          const result = await updateOneMod(update.fileName);
          updated.push(`${result.name} ${result.version}`.trim());
          warnings.push(...result.warnings);
        } catch (error) {
          failed.push(`${update.name}: ${error.message}`);
        }
      }

      return { updated, failed, warnings };
    }),
  );

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

  handle('import:find', () => importer.findSources({ gameDir: gameDir() }));

  handle('import:chooseFolder', async () => {
    const result = await dialog.showOpenDialog(window, {
      title: '설정을 가져올 게임 폴더 선택 (options.txt 나 config 폴더가 들어 있는 곳)',
      properties: ['openDirectory'],
    });

    return result.canceled ? null : importer.describeSource(result.filePaths[0], '직접 고른 폴더');
  });

  handle('import:run', (sourceDirectory, itemIds, overwrite) =>
    importer.importItems({ sourceDirectory, itemIds, gameDir: gameDir(), overwrite: Boolean(overwrite) }),
  );

  // 순서와 검사는 client-settings.js 의 exportFlow, importFlow 에 있다. 여기서는 대화상자만 댄다.
  handle('clientSettings:export', () =>
    exportFlow({
      gameDir: gameDir(),
      appVersion: app.getVersion(),
      chooseFile: async () => {
        const result = await dialog.showSaveDialog(window, {
          title: 'Cafeteria 설정 내보내기',
          defaultPath: `Cafeteria-설정.${SETTINGS_EXTENSION}`,
          filters: [{ name: 'Cafeteria 설정', extensions: [SETTINGS_EXTENSION] }],
        });

        return result.canceled ? null : result.filePath;
      },
    }),
  );

  handle('clientSettings:import', () =>
    importFlow({
      gameDir: gameDir(),
      isGameRunning,
      chooseFile: async () => {
        const result = await dialog.showOpenDialog(window, {
          title: '가져올 Cafeteria 설정 파일 선택',
          filters: [{ name: 'Cafeteria 설정', extensions: [SETTINGS_EXTENSION] }],
          properties: ['openFile'],
        });

        return result.canceled ? null : result.filePaths[0];
      },
      confirm: async () => {
        const answer = await dialog.showMessageBox(window, {
          type: 'question',
          buttons: ['가져오기', '취소'],
          defaultId: 0,
          cancelId: 1,
          message: 'Cafeteria 설정을 가져올까요?',
          detail: '지금 설정을 덮어씁니다. 기존 설정은 cafeteria.json.bak 으로 남깁니다.',
        });

        return answer.response === 0;
      },
    }),
  );

  handle('account:login', async () => {
    if (loggingIn) {
      throw new LauncherError('BUSY', '이미 로그인 창이 열려 있습니다.');
    }

    loggingIn = true;
    let code;

    try {
      code = await openLoginWindow({ parent: window });
    } finally {
      loggingIn = false;
    }

    return account.completeLogin(code);
  });
  handle('account:logout', () => account.logout());

  handle('update:check', () => updates.check(true));
  handle('update:install', () => updates.install());

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

    if (result.canceled) {
      return settings.publicView();
    }

    const view = await settings.update({ gameDir: result.filePaths[0] });
    // 게임 폴더가 바뀌면 모드 목록도 달라지므로, 예전 폴더 기준의 업데이트 목록은 버린다.
    setModUpdates({ updates: [], checked: 0, unknown: [], notes: [], checkedAt: null });
    return view;
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
    // 설정(CurseForge 키)과 계정(refresh 토큰)이 같은 OS 보안 저장소를 쓴다.
    const vault = {
      isAvailable: () => safeStorage.isEncryptionAvailable(),
      encrypt: (text) => safeStorage.encryptString(text),
      decrypt: (data) => safeStorage.decryptString(data),
    };
    settings = await new SettingsStore(path.join(app.getPath('userData'), 'settings.json'), vault).load();
    account = await new AccountManager({ file: path.join(app.getPath('userData'), 'account.json'), vault }).load();
    updates = createUpdateManager();
    registerHandlers();
    createWindow();
  });

  app.on('window-all-closed', () => app.quit());
}
