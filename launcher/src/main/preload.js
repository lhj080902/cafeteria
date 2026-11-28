'use strict';

const { contextBridge, ipcRenderer, webUtils } = require('electron');

const invoke = (channel, ...args) => ipcRenderer.invoke(channel, ...args);

function subscribe(channel, callback) {
  const listener = (_event, payload) => callback(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
}

/** 화면(renderer)에서 쓸 수 있는 기능 목록. 여기 없는 것은 화면에서 호출할 수 없다. */
contextBridge.exposeInMainWorld('cafeteria', {
  getState: () => invoke('state:get'),
  play: () => invoke('game:play'),
  repair: () => invoke('game:repair'),
  removeProfile: () => invoke('game:removeProfile'),
  openGameFolder: () => invoke('game:openFolder'),
  openLogsFolder: () => invoke('game:openLogs'),

  /** Microsoft 로그인 창을 띄우고, 끝나면 계정 정보({ loggedIn, name })를 돌려준다. */
  login: () => invoke('account:login'),
  /** 저장된 Microsoft 계정을 지운다. */
  logout: () => invoke('account:logout'),

  listMods: () => invoke('mods:list'),
  addModFiles: () => invoke('mods:addDialog'),
  /** 끌어다 놓은 파일(File 객체 배열)을 mods 폴더에 추가한다. */
  addDroppedFiles: (files) => invoke('mods:addPaths', Array.from(files, (file) => webUtils.getPathForFile(file)).filter(Boolean)),
  setModEnabled: (fileName, enabled) => invoke('mods:setEnabled', fileName, enabled),
  removeMod: (fileName) => invoke('mods:remove', fileName),
  openModsFolder: () => invoke('mods:openFolder'),
  /** 마지막으로 확인해 둔 모드 업데이트 목록. */
  getModUpdates: () => invoke('mods:getUpdates'),
  /** 설치된 모드의 새 버전이 있는지 지금 확인한다. */
  checkModUpdates: () => invoke('mods:checkUpdates'),
  updateMod: (fileName) => invoke('mods:update', fileName),
  updateAllMods: () => invoke('mods:updateAll'),

  searchMods: (source, query, page) => invoke('browse:search', source, query, page),
  installMod: (source, projectId) => invoke('browse:install', source, projectId),

  findImportSources: () => invoke('import:find'),
  chooseImportFolder: () => invoke('import:chooseFolder'),
  runImport: (sourceDirectory, itemIds, overwrite) => invoke('import:run', sourceDirectory, itemIds, overwrite),

  /** 게임 안 Cafeteria 설정을 파일로 내보낸다. 저장할 곳을 고르지 않으면 null, 아니면 { modules }. */
  exportClientSettings: () => invoke('clientSettings:export'),
  /** 내보낸 Cafeteria 설정 파일을 골라 게임 폴더에 넣는다. 취소하면 null, 아니면 { modules, backedUp }. */
  importClientSettings: () => invoke('clientSettings:import'),

  checkForUpdates: () => invoke('update:check'),
  installUpdate: () => invoke('update:install'),

  updateSettings: (partial) => invoke('settings:update', partial),
  resetSettings: () => invoke('settings:reset'),
  chooseJava: () => invoke('settings:chooseJava'),
  setCurseforgeKey: (key) => invoke('settings:setCurseforgeKey', key),
  chooseLauncher: () => invoke('settings:chooseLauncher'),
  chooseGameDir: () => invoke('settings:chooseGameDir'),

  openExternal: (url) => invoke('app:openExternal', url),
  minimize: () => invoke('window:minimize'),
  close: () => invoke('window:close'),

  /** 설치·다운로드 진행 상황을 받는다. 돌려주는 함수를 부르면 구독을 끊는다. */
  onProgress: (callback) => subscribe('progress', callback),
  /** 런처 업데이트 상태가 바뀔 때마다 받는다. */
  onUpdateState: (callback) => subscribe('update', callback),
  /** 모드 목록의 정보(이름·아이콘)가 새로 채워졌을 때 받는다. */
  onModsUpdated: (callback) => subscribe('mods-updated', callback),
  /** 모드 업데이트 목록이 바뀔 때마다 받는다. */
  onModUpdates: (callback) => subscribe('mod-updates', callback),
  /** 바로 실행한 게임이 시작하거나 끝날 때 받는다 ({ running, exitCode }). */
  onGameState: (callback) => subscribe('game', callback),
});
