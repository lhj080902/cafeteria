'use strict';

const { contextBridge, ipcRenderer, webUtils } = require('electron');

const invoke = (channel, ...args) => ipcRenderer.invoke(channel, ...args);

/** 화면(renderer)에서 쓸 수 있는 기능 목록. 여기 없는 것은 화면에서 호출할 수 없다. */
contextBridge.exposeInMainWorld('cafeteria', {
  getState: () => invoke('state:get'),
  play: () => invoke('game:play'),
  repair: () => invoke('game:repair'),
  removeProfile: () => invoke('game:removeProfile'),
  openGameFolder: () => invoke('game:openFolder'),
  openLogsFolder: () => invoke('game:openLogs'),

  listMods: () => invoke('mods:list'),
  addModFiles: () => invoke('mods:addDialog'),
  /** 끌어다 놓은 파일(File 객체 배열)을 mods 폴더에 추가한다. */
  addDroppedFiles: (files) => invoke('mods:addPaths', Array.from(files, (file) => webUtils.getPathForFile(file)).filter(Boolean)),
  setModEnabled: (fileName, enabled) => invoke('mods:setEnabled', fileName, enabled),
  removeMod: (fileName) => invoke('mods:remove', fileName),
  openModsFolder: () => invoke('mods:openFolder'),

  searchMods: (source, query, page) => invoke('browse:search', source, query, page),
  installMod: (source, projectId) => invoke('browse:install', source, projectId),

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
  onProgress: (callback) => {
    const listener = (_event, progress) => callback(progress);
    ipcRenderer.on('progress', listener);
    return () => ipcRenderer.removeListener('progress', listener);
  },
});
