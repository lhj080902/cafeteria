'use strict';

const api = window.cafeteria;
const $ = (selector) => document.querySelector(selector);
const compactNumber = new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 });

const SOURCE_LABELS = { modrinth: 'Modrinth', curseforge: 'CurseForge', local: '직접 추가', builtin: '기본 설치' };

const state = {
  /** 메인 프로세스가 알려 준 현재 상태 (버전, 설치 여부, 설정) */
  info: null,
  browse: { source: 'modrinth', query: '', page: 0, started: false, requestId: 0 },
  /** 설치 진행률을 표시할 버튼: 진행 범위 이름 → 버튼 */
  installButtons: new Map(),
  /** "설정 가져오기" 화면을 한 번이라도 불러왔는지 */
  importLoaded: false,
  /** 마지막으로 확인한 모드 업데이트 (메인 프로세스가 알려 준 것) */
  modUpdates: { updates: [], checked: 0, unknown: [], notes: [], checkedAt: null },
  /** play() 가 도는 동안 true. 이때는 play() 가 플레이 버튼과 안내 문구를 직접 쓴다. */
  playing: false,
  /** 안내 문구(#play-message)를 마지막으로 어느 상황('direct', 'official', 'running')에 맞춰 썼는지 */
  playMessageFor: null,
  /** 아직 끝나지 않은 login() 호출 수. 0 이 되면 로그인 버튼을 되돌린다. */
  loginPending: 0,
  /** 설치 복구가 도는 동안 true. 이때만 'repair' 진행률을 복구 버튼에 쓴다. */
  repairing: false,
};

/** 플레이 버튼 옆 기본 안내 문구 */
const PLAY_MESSAGES = {
  direct: '플레이를 누르면 게임 파일을 확인하고 바로 실행합니다.',
  official: '플레이를 누르면 설치를 확인하고 공식 Minecraft 런처를 엽니다.',
  running: '게임이 실행 중입니다.',
};

/** { ok, data } 응답에서 data 를 꺼낸다. 실패 응답이면 안내문을 담은 오류를 던진다. */
async function unwrap(promise) {
  const response = await promise;

  if (!response.ok) {
    const error = new Error(response.error.message);
    error.code = response.error.code;
    throw error;
  }

  return response.data;
}

/** 요소를 만든다. 외부에서 온 글자는 항상 textContent 로 넣어서 HTML 로 해석되지 않게 한다. */
function el(tag, { className, text, attrs = {}, on = {} } = {}, children = []) {
  const node = document.createElement(tag);

  if (className) {
    node.className = className;
  }

  if (text !== undefined) {
    node.textContent = text;
  }

  for (const [name, value] of Object.entries(attrs)) {
    node.setAttribute(name, value);
  }

  for (const [name, listener] of Object.entries(on)) {
    node.addEventListener(name, listener);
  }

  node.append(...children.filter(Boolean));
  return node;
}

function toast(message, kind = 'info') {
  const node = el('div', { className: `toast ${kind}`, text: message });
  $('#toasts').append(node);
  setTimeout(() => node.remove(), kind === 'error' ? 9000 : 5000);
}

function showError(error) {
  toast(error.message, 'error');
}

function formatSize(bytes) {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function setChip(node, text, kind) {
  node.textContent = text;
  node.classList.remove('ok', 'warn', 'error');

  if (kind) {
    node.classList.add(kind);
  }
}

/* ── 화면 전환 ─────────────────────────────────── */

function showView(name) {
  for (const button of document.querySelectorAll('.nav-item')) {
    button.classList.toggle('active', button.dataset.view === name);
  }

  for (const view of document.querySelectorAll('.view')) {
    view.classList.toggle('active', view.id === `view-${name}`);
  }

  if (name === 'home') {
    refreshState().catch(showError);
  } else if (name === 'mods') {
    refreshMods();
  } else if (name === 'browse' && !state.browse.started) {
    searchMods(true);
  } else if (name === 'import' && !state.importLoaded) {
    loadImportSources();
  }
}

/* ── 상태 표시 (홈, 설정) ──────────────────────── */

async function refreshState() {
  state.info = await unwrap(api.getState());
  renderState();
}

function renderState() {
  const { info } = state;
  $('#version-label').textContent = `Minecraft ${info.minecraftVersion}`;
  $('#app-version-label').textContent = `런처 v${info.appVersion}`;
  $('#hero-subtitle').textContent = `Minecraft ${info.minecraftVersion} · Fabric Loader ${info.loaderVersion}`;
  $('#browse-subtitle').textContent = `Minecraft ${info.minecraftVersion} · Fabric용 모드만 보여 줍니다.`;

  const direct = info.launch.mode === 'direct';
  const chip = $('#install-status');

  if (direct) {
    if (!info.account.loggedIn) {
      setChip(chip, '로그인 필요', 'warn');
    } else if (info.status.installed) {
      setChip(chip, '준비됨', 'ok');
    } else {
      setChip(chip, '설치 전', 'warn');
    }
  } else if (!info.status.officialLauncherFound) {
    setChip(chip, '공식 런처 필요', 'error');
  } else if (info.status.installed) {
    setChip(chip, '설치됨', 'ok');
  } else {
    setChip(chip, '설치 전', 'warn');
  }

  $('#launcher-missing').hidden = direct || info.status.officialLauncherFound;

  const { account } = info;
  $('#account-row').hidden = !direct;
  $('#account-name').textContent = account.loggedIn ? account.name : '로그인하지 않음';
  $('#account-login').hidden = account.loggedIn;
  $('#account-logout').hidden = !account.loggedIn;
  renderPlayButton();

  for (const button of document.querySelectorAll('#setting-launch-mode [data-launch-mode]')) {
    button.classList.toggle('active', button.dataset.launchMode === info.launch.mode);
    button.disabled = !info.launch.directSupported;
  }

  $('#setting-launch-mode-note').hidden = info.launch.directSupported;

  const { settings } = info;
  $('#setting-memory').value = String(settings.memoryGb);
  $('#setting-memory-value').textContent = `${settings.memoryGb} GB`;
  $('#setting-resolution-enabled').checked = settings.resolutionEnabled;
  $('#setting-resolution-width').value = String(settings.resolutionWidth);
  $('#setting-resolution-height').value = String(settings.resolutionHeight);
  $('#setting-resolution-width').disabled = !settings.resolutionEnabled;
  $('#setting-resolution-height').disabled = !settings.resolutionEnabled;
  $('#setting-java-path').textContent = settings.javaPath || (direct ? '자동 (런처가 내려받은 Java 21 사용)' : '자동 (공식 런처에 들어 있는 Java 사용)');
  $('#setting-jvm-args').value = settings.jvmArgs;
  $('#setting-close').checked = settings.closeOnLaunch;
  $('#setting-auto-update').checked = settings.autoUpdate;
  $('#setting-mod-update-check').checked = settings.modUpdateCheck;
  renderUpdate(info.update);
  $('#setting-game-dir').textContent = info.gameDir;
  $('#setting-launcher-path').textContent = settings.launcherPath || '자동으로 찾기';
  setChip($('#curseforge-key-state'), settings.hasCurseforgeKey ? '저장됨' : '미설정', settings.hasCurseforgeKey ? 'ok' : null);
}

/* ── 런처 업데이트 ─────────────────────────────── */

function renderUpdate(update) {
  if (!update || !state.info) {
    return;
  }

  state.info.update = update;
  const version = `v${state.info.appVersion}`;
  const statusTexts = {
    idle: `현재 버전 ${version}`,
    checking: '새 버전이 있는지 확인하는 중...',
    'up-to-date': `최신 버전을 쓰고 있습니다 (${version}).`,
  };
  $('#update-status').textContent = statusTexts[update.status] || update.message || `현재 버전 ${version}`;

  // 사이드바 알림: 새 버전을 받고 있거나, 받아 뒀거나, 곧 설치하려고 다시 시작하거나, 직접 받아야 할 때만 보인다.
  const box = $('#update-box');
  const action = $('#update-action');
  const showProgress = update.status === 'downloading';
  box.hidden = !['downloading', 'ready', 'installing', 'available'].includes(update.status);
  $('#update-progress').hidden = !showProgress;
  // 런처가 스스로 다시 시작하는 중에는 누를 것이 없다.
  action.hidden = showProgress || update.status === 'installing';

  if (update.status === 'downloading') {
    $('#update-text').textContent = `새 버전 ${update.version} 받는 중 ${update.percent || 0}%`;
    $('#update-progress-fill').style.width = `${update.percent || 0}%`;
  } else if (update.status === 'ready') {
    $('#update-text').textContent =
      update.auto === 'waiting' ? `새 버전 ${update.version} 준비 완료 (하던 일이 끝나면 자동으로 설치)` : `새 버전 ${update.version} 준비 완료`;
    action.textContent = '재시작해서 설치';
  } else if (update.status === 'installing') {
    $('#update-text').textContent = `새 버전 ${update.version} 설치 중... 곧 다시 시작합니다`;
  } else if (update.status === 'available') {
    $('#update-text').textContent = `새 버전 ${update.version} 이 나왔습니다`;
    action.textContent = '받으러 가기';
  }
}

async function runUpdateAction() {
  const { update, releasesUrl } = state.info;

  try {
    if (update.status === 'ready') {
      await unwrap(api.installUpdate());
    } else if (update.status === 'available') {
      await unwrap(api.openExternal(releasesUrl));
    }
  } catch (error) {
    showError(error);
  }
}

/* ── 설정 가져오기 ─────────────────────────────── */

async function loadImportSources() {
  const list = $('#import-sources');
  const notice = $('#import-notice');
  state.importLoaded = true;
  notice.hidden = true;
  list.replaceChildren(el('div', { className: 'empty', text: 'PC에서 가져올 수 있는 설정을 찾는 중...' }));

  let result;

  try {
    result = await unwrap(api.findImportSources());
  } catch (error) {
    list.replaceChildren(el('div', { className: 'empty', text: error.message }));
    return;
  }

  list.replaceChildren();

  if (result.lunarSettingsDirectory) {
    notice.hidden = false;
    notice.textContent =
      '루나 클라이언트 자체 기능(루나의 줌·프리룩·HUD 위치 등)의 설정은 형식이 달라 자동으로 옮길 수 없습니다.\n' +
      '게임 안에서 오른쪽 Shift 를 눌러 Cafeteria 설정 메뉴에서 다시 맞춰 주세요. 아래 항목(게임 설정, 서버 목록, 미니맵 등)은 그대로 가져올 수 있습니다.';
  }

  if (result.sources.length === 0) {
    list.append(
      el('div', {
        className: 'empty',
        text:
          '가져올 수 있는 설정을 찾지 못했습니다.\n' +
          '루나 런처에서 프로필 설정(톱니바퀴) → Mods 탭의 폴더 아이콘을 누르면 실제 폴더가 열립니다.\n' +
          '그 위치를 확인한 뒤 options.txt 나 config 폴더가 들어 있는 게임 폴더를 "폴더 직접 선택"으로 골라 주세요.',
      }),
    );
    return;
  }

  for (const source of result.sources) {
    list.append(sourceCard(source));
  }
}

function sourceCard(source) {
  const checkboxes = new Map();
  const card = el('div', { className: 'source' }, [
    el('div', { className: 'source-title', text: source.label }),
    el('div', { className: 'source-path', text: source.directory }),
  ]);

  for (const item of source.items) {
    const checkbox = el('input', { attrs: { type: 'checkbox', 'data-item': item.id } });
    checkbox.checked = item.selected;
    checkboxes.set(item.id, checkbox);
    card.append(
      el('label', { className: 'import-item' }, [
        checkbox,
        el('div', { className: 'import-item-text' }, [el('strong', { text: item.label }), el('span', { text: item.description })]),
        el('div', { className: 'import-item-size', text: `파일 ${item.files}${item.countCapped ? '+' : ''}개 · ${formatSize(item.bytes)}` }),
      ]),
    );
  }

  const overwrite = el('input', { attrs: { type: 'checkbox' } });
  const button = el('button', { className: 'button primary', text: '가져오기', attrs: { type: 'button' } });
  button.addEventListener('click', async () => {
    const itemIds = [...checkboxes].filter(([, checkbox]) => checkbox.checked).map(([id]) => id);

    if (itemIds.length === 0) {
      toast('가져올 항목을 하나 이상 골라 주세요.', 'error');
      return;
    }

    button.disabled = true;
    button.textContent = '복사 중...';

    try {
      const result = await unwrap(api.runImport(source.directory, itemIds, overwrite.checked));
      const skipped = result.skipped > 0 ? `\n이미 있어서 건너뛴 파일 ${result.skipped}개 (덮어쓰려면 "이미 있는 파일 덮어쓰기"를 켜세요)` : '';
      toast(`파일 ${result.copied}개를 가져왔습니다.${skipped}`, 'success');
    } catch (error) {
      showError(error);
    } finally {
      button.disabled = false;
      button.textContent = '가져오기';
    }
  });

  card.append(el('div', { className: 'source-footer' }, [el('label', {}, [overwrite, el('span', { text: '이미 있는 파일 덮어쓰기' })]), button]));
  return card;
}

async function chooseImportFolder() {
  try {
    const source = await unwrap(api.chooseImportFolder());

    if (!source) {
      return;
    }

    if (source.items.length === 0) {
      toast('이 폴더에는 가져올 수 있는 설정이 없습니다.\noptions.txt 나 config 폴더가 들어 있는 게임 폴더를 골라 주세요.', 'error');
      return;
    }

    const list = $('#import-sources');
    const empty = list.querySelector('.empty');

    if (empty) {
      empty.remove();
    }

    list.prepend(sourceCard(source));
  } catch (error) {
    showError(error);
  }
}

function setNotice(message, kind) {
  const notice = $('#home-notice');
  notice.hidden = !message;
  notice.textContent = message || '';
  notice.classList.toggle('error', kind === 'error');
}

function setPlayProgress(fraction) {
  $('#play-progress-fill').style.width = `${Math.round(Math.min(1, Math.max(0, fraction)) * 100)}%`;
}

/**
 * 플레이 버튼과 기본 안내 문구를 그린다. 플레이를 준비하는 동안에는 play() 가 직접 쓰므로 건드리지 않는다.
 * 안내 문구는 상황(실행 방식, 게임 실행 여부)이 바뀔 때만 다시 써서, 플레이 결과 안내가 덮이지 않게 한다.
 */
function renderPlayButton() {
  if (state.playing) {
    return;
  }

  const { running } = state.info.game;
  const button = $('#play-button');
  button.disabled = running;
  button.textContent = running ? '실행 중' : '플레이';

  const context = running ? 'running' : state.info.launch.mode;

  if (state.playMessageFor !== context) {
    state.playMessageFor = context;
    $('#play-message').textContent = PLAY_MESSAGES[context];
  }
}

/** Microsoft 로그인 창을 띄운다. 로그인했으면 true. 사용자가 창을 닫은 것은 오류로 보이지 않는다. */
async function login() {
  // 창을 닫은 뒤 토큰을 바꾸는 동안에도 기다리는 중임을 보여 준다. renderState() 는 이 버튼의 문구와 disabled 를 건드리지 않는다.
  const button = $('#account-login');
  state.loginPending += 1;
  button.disabled = true;
  button.textContent = '로그인 중...';

  try {
    state.info.account = await unwrap(api.login());
    renderState();
    toast(`${state.info.account.name} 계정으로 로그인했습니다.`);
    return true;
  } catch (error) {
    if (error.code !== 'LOGIN_CANCELLED') {
      showError(error);
    }

    return false;
  } finally {
    // 플레이와 버튼에서 거의 동시에 시작했으면 마지막 것이 끝날 때 되돌린다.
    state.loginPending -= 1;

    if (state.loginPending === 0) {
      button.disabled = false;
      button.textContent = 'Microsoft 계정으로 로그인';
    }
  }
}

async function logout() {
  try {
    state.info.account = await unwrap(api.logout());
    renderState();
  } catch (error) {
    showError(error);
  }
}

async function play() {
  const button = $('#play-button');
  state.playing = true;
  button.disabled = true;
  setNotice(null);

  try {
    // 바로 실행은 로그인이 필요하다. 취소하거나 실패하면 플레이하지 않는다.
    if (state.info.launch.mode === 'direct' && !state.info.account.loggedIn && !(await login())) {
      return;
    }

    button.textContent = '준비 중...';
    setPlayProgress(0);
    $('#play-progress').hidden = false;
    const result = await unwrap(api.play());

    if (result.mode === 'direct') {
      $('#play-message').textContent = '게임을 실행했습니다.';
      // 게임이 끝날 때까지 이 문구를 둔다.
      state.playMessageFor = 'running';
      setNotice('게임 안에서는 오른쪽 Shift 로 모드 설정을 엽니다.');
    } else if (!result.launched) {
      $('#play-message').textContent = '설치는 끝났습니다.';
      setNotice(
        '설치는 끝났지만 공식 Minecraft 런처를 자동으로 열지 못했습니다.\n공식 런처를 직접 실행해서 "Cafeteria" 프로필을 고르거나, 설정에서 런처 위치를 지정해 주세요.',
      );
    } else if (result.wasRunning) {
      $('#play-message').textContent = '공식 런처가 이미 실행 중이었습니다.';
      setNotice('"Cafeteria" 프로필이 보이지 않으면 공식 런처를 완전히 종료한 뒤 플레이를 다시 눌러 주세요.');
    } else {
      $('#play-message').textContent = '공식 Minecraft 런처를 열었습니다.';
      setNotice('공식 런처에서 "Cafeteria" 프로필이 선택돼 있는지 확인하고 플레이를 누르세요.\n게임 안에서는 오른쪽 Shift 로 모드 설정을 엽니다.');
    }
  } catch (error) {
    $('#play-message').textContent = '플레이를 준비하지 못했습니다.';
    setNotice(error.message, 'error');
  } finally {
    state.playing = false;
    renderPlayButton();
    $('#play-progress').hidden = true;
    refreshState().catch(showError);
  }
}

/* ── 내 모드 ───────────────────────────────────── */

async function refreshMods() {
  let mods;

  try {
    mods = await unwrap(api.listMods());
  } catch (error) {
    showError(error);
    return;
  }

  const list = $('#mods-list');
  list.replaceChildren();

  if (mods.length === 0) {
    list.append(
      el('div', {
        className: 'empty',
        text: '아직 모드가 없습니다.\n"모드 찾기"에서 받거나 jar 파일을 여기에 끌어다 놓으세요.\nCafeteria 기본 모드는 처음 플레이할 때 자동으로 설치됩니다.',
      }),
    );
    return;
  }

  const updates = new Map(state.modUpdates.updates.map((update) => [update.fileName, update]));

  for (const mod of mods) {
    list.append(modRow(mod, updates.get(mod.fileName)));
  }
}

/* ── 모드 업데이트 ─────────────────────────────── */

/** 사이드바의 숫자 표시와 "내 모드" 위쪽의 업데이트 안내를 그린다. */
function renderModUpdates() {
  const { updates, unknown, notes, checkedAt } = state.modUpdates;
  const badge = $('#mods-update-badge');
  badge.hidden = updates.length === 0;
  badge.textContent = String(updates.length);

  const lines = [...notes];

  if (unknown.length > 0) {
    const names = unknown.slice(0, 3).join(', ') + (unknown.length > 3 ? ` 외 ${unknown.length - 3}개` : '');
    lines.push(`직접 넣은 모드(${names})는 모드 사이트에 없어서 확인할 수 없습니다. 새 jar 파일을 추가하면 예전 파일과 자동으로 바뀝니다.`);
  }

  $('#mods-update-bar').hidden = checkedAt === null || (updates.length === 0 && lines.length === 0);
  $('#mods-update-summary').textContent = updates.length > 0 ? `새 버전이 나온 모드 ${updates.length}개` : '받은 모드는 모두 최신입니다';
  $('#mods-update-note').textContent = lines.join('\n');
  $('#mods-update-all').hidden = updates.length === 0;
}

async function checkModUpdates() {
  const button = $('#mods-check-updates');
  button.disabled = true;
  button.textContent = '확인 중...';

  try {
    state.modUpdates = await unwrap(api.checkModUpdates());
    renderModUpdates();
    const { updates, checked } = state.modUpdates;
    toast(updates.length > 0 ? `새 버전이 나온 모드가 ${updates.length}개 있습니다.` : `새 버전이 없습니다. (확인한 모드 ${checked}개)`, updates.length > 0 ? 'info' : 'success');
  } catch (error) {
    showError(error);
  } finally {
    button.disabled = false;
    button.textContent = '업데이트 확인';
    refreshMods();
  }
}

function reportModUpdate(result) {
  for (const warning of result.warnings || []) {
    toast(warning, 'error');
  }

  if (result.installed && result.installed.length > 0) {
    toast(`함께 필요한 모드를 설치했습니다: ${result.installed.join(', ')}`, 'info');
  }
}

async function updateAllMods() {
  const button = $('#mods-update-all');
  button.disabled = true;
  button.textContent = '업데이트 중...';

  try {
    const result = await unwrap(api.updateAllMods());

    if (result.updated.length > 0) {
      toast(`모드 ${result.updated.length}개를 업데이트했습니다:\n${result.updated.join('\n')}`, 'success');
    }

    if (result.failed.length > 0) {
      toast(`업데이트하지 못한 모드:\n${result.failed.join('\n')}`, 'error');
    }

    reportModUpdate(result);
  } catch (error) {
    showError(error);
  } finally {
    button.disabled = false;
    button.textContent = '모두 업데이트';
    refreshMods();
  }
}

function modRow(mod, update) {
  const title = el('div', { className: 'row-title', text: mod.name });

  if (mod.version) {
    title.append(el('span', { className: 'muted', text: `  ${mod.version}` }));
  }

  if (update) {
    title.append(el('span', { className: 'chip small update', text: `새 버전 ${update.newVersion}`.trim() }));
  }

  const source = mod.builtin ? SOURCE_LABELS.builtin : SOURCE_LABELS[mod.source] || SOURCE_LABELS.local;
  const body = el('div', { className: 'row-body' }, [
    title,
    mod.description ? el('div', { className: 'row-description', text: mod.description }) : null,
    el('div', { className: 'row-meta', text: `${source} · ${mod.fileName} · ${formatSize(mod.size)}` }),
  ]);
  const actions = el('div', { className: 'row-actions' });

  if (mod.builtin) {
    actions.append(el('span', { className: 'chip ok', text: '기본 모드' }));
  } else {
    const toggle = el('input', {
      attrs: { type: 'checkbox', 'aria-label': `${mod.name} 사용` },
      on: {
        change: async () => {
          toggle.disabled = true;

          try {
            await unwrap(api.setModEnabled(mod.fileName, toggle.checked));
          } catch (error) {
            showError(error);
          }

          refreshMods();
        },
      },
    });
    toggle.checked = mod.enabled;

    if (update) {
      const updateButton = el('button', {
        className: 'button primary',
        text: '업데이트',
        attrs: { type: 'button', title: `${update.currentVersion || '현재 버전'} → ${update.newVersion}` },
        on: {
          click: async () => {
            updateButton.disabled = true;
            updateButton.textContent = '받는 중...';
            state.installButtons.set(`update:${mod.fileName}`, updateButton);

            try {
              const result = await unwrap(api.updateMod(mod.fileName));
              toast(`${result.name} ${result.version} 업데이트 완료`, 'success');
              reportModUpdate(result);
            } catch (error) {
              showError(error);
            } finally {
              state.installButtons.delete(`update:${mod.fileName}`);
            }

            refreshMods();
          },
        },
      });
      actions.append(updateButton);
    }

    actions.append(
      el('label', { className: 'switch', attrs: { title: mod.enabled ? '끄기' : '켜기' } }, [toggle, el('span')]),
      el('button', {
        className: 'button danger',
        text: '삭제',
        attrs: { type: 'button' },
        on: {
          click: async () => {
            if (!window.confirm(`"${mod.name}" 모드를 삭제할까요?`)) {
              return;
            }

            try {
              await unwrap(api.removeMod(mod.fileName));
              toast(`${mod.name} 삭제 완료`, 'success');
            } catch (error) {
              showError(error);
            }

            refreshMods();
          },
        },
      }),
    );
  }

  return el('div', { className: mod.enabled ? 'row' : 'row disabled' }, [iconNode(mod.icon, mod.name), body, actions]);
}

function reportAdded(result) {
  if (result.added.length > 0) {
    toast(`모드 ${result.added.length}개를 추가했습니다.`, 'success');
  }

  if (result.replaced && result.replaced.length > 0) {
    toast(`같은 모드의 예전 파일을 새 파일로 바꿨습니다:\n${result.replaced.map((entry) => `${entry.old} → ${entry.new}`).join('\n')}`, 'info');
  }

  if (result.rejected.length > 0) {
    toast(`추가하지 않았습니다 (jar 모드 파일이 아니거나, 런처가 관리하는 Cafeteria 모드):\n${result.rejected.join('\n')}`, 'error');
  }

  refreshMods();
}

/** 아이콘 이미지. 주소가 없거나 불러오지 못하면 이름 첫 글자를 대신 보여 준다. */
function iconNode(url, name) {
  const placeholder = () => el('div', { className: 'row-icon placeholder', text: (name || '?').trim().charAt(0).toUpperCase() });

  if (!url) {
    return placeholder();
  }

  const image = el('img', { className: 'row-icon', attrs: { src: url, alt: '', loading: 'lazy' } });
  image.addEventListener('error', () => image.replaceWith(placeholder()));
  return image;
}

/* ── 모드 찾기 ─────────────────────────────────── */

async function searchMods(reset) {
  const browse = state.browse;
  const results = $('#browse-results');
  const more = $('#browse-more');
  const requestId = ++browse.requestId;
  browse.started = true;
  more.hidden = true;

  if (reset) {
    browse.page = 0;
    results.replaceChildren();
  }

  if (browse.source === 'curseforge' && !state.info.settings.hasCurseforgeKey) {
    results.replaceChildren(
      el('div', { className: 'empty' }, [
        el('div', { text: 'CurseForge를 쓰려면 API 키가 필요합니다.\n설정에서 본인 키를 입력하면 여기서 바로 검색하고 받을 수 있어요.' }),
        el('button', {
          className: 'button primary',
          text: '설정으로 이동',
          attrs: { type: 'button', id: 'browse-go-settings' },
          on: { click: () => showView('settings') },
        }),
      ]),
    );
    return;
  }

  const loading = el('div', { className: 'empty', text: '불러오는 중...' });
  results.append(loading);

  try {
    const result = await unwrap(api.searchMods(browse.source, browse.query, browse.page));

    // 기다리는 동안 다른 검색을 시작했다면 이 결과는 버린다.
    if (requestId !== browse.requestId) {
      return;
    }

    loading.remove();

    if (reset && result.items.length === 0) {
      results.append(el('div', { className: 'empty', text: '검색 결과가 없습니다.' }));
      return;
    }

    for (const item of result.items) {
      results.append(browseRow(item));
    }

    more.hidden = result.items.length === 0 || (browse.page + 1) * result.pageSize >= result.total;
  } catch (error) {
    if (requestId !== browse.requestId) {
      return;
    }

    loading.remove();
    results.append(el('div', { className: 'empty', text: error.message }));
  }
}

function browseRow(item) {
  const title = el('div', { className: 'row-title', text: item.title });

  if (item.author) {
    title.append(el('span', { className: 'muted', text: `  ${item.author}` }));
  }

  const install = el('button', {
    className: 'button primary',
    text: item.installed ? '설치됨' : '설치',
    attrs: { type: 'button' },
  });
  install.disabled = item.installed;
  install.addEventListener('click', async () => {
    const scope = `install:${item.source}:${item.id}`;
    install.disabled = true;
    install.textContent = '받는 중...';
    state.installButtons.set(scope, install);

    try {
      const result = await unwrap(api.installMod(item.source, item.id));
      install.textContent = '설치됨';
      toast(`${result.installed.join(', ')} 설치 완료`, 'success');

      for (const warning of result.warnings) {
        toast(warning, 'error');
      }
    } catch (error) {
      if (error.code === 'ALREADY_INSTALLED') {
        install.textContent = '설치됨';
      } else {
        install.disabled = false;
        install.textContent = '설치';
        showError(error);
      }
    } finally {
      state.installButtons.delete(scope);
    }
  });

  return el('div', { className: 'row' }, [
    iconNode(item.iconUrl, item.title),
    el('div', { className: 'row-body' }, [
      title,
      el('div', { className: 'row-description', text: item.description }),
      el('div', { className: 'row-meta', text: `다운로드 ${compactNumber.format(item.downloads)}회` }),
    ]),
    el('div', { className: 'row-actions' }, [
      el('button', {
        className: 'button',
        text: '자세히',
        attrs: { type: 'button' },
        on: { click: () => unwrap(api.openExternal(item.pageUrl)).catch(showError) },
      }),
      install,
    ]),
  ]);
}

/* ── 이벤트 연결 ───────────────────────────────── */

function bindEvents() {
  $('#window-minimize').addEventListener('click', () => api.minimize());
  $('#window-close').addEventListener('click', () => api.close());

  for (const button of document.querySelectorAll('.nav-item')) {
    button.addEventListener('click', () => showView(button.dataset.view));
  }

  $('#play-button').addEventListener('click', play);
  $('#account-login').addEventListener('click', login);
  $('#account-logout').addEventListener('click', logout);

  api.onGameState((game) => {
    if (!state.info) {
      return;
    }

    state.info.game = { running: game.running };
    renderState();

    if (!game.running && game.exitCode !== 0 && game.exitCode !== null) {
      setNotice(`게임이 오류로 종료됐습니다 (코드 ${game.exitCode}). 설정의 로그 폴더에서 latest.log 와 launcher-stderr.log 를 확인해 주세요.`, 'error');
    }
  });

  api.onProgress((progress) => {
    if (progress.scope === 'play') {
      $('#play-message').textContent = progress.message;
      setPlayProgress(progress.fraction);
    } else if (progress.scope === 'repair') {
      if (state.repairing) {
        $('#setting-repair').textContent = `복구 중... ${Math.round(progress.fraction * 100)}%`;
      }
    } else if (state.installButtons.has(progress.scope)) {
      state.installButtons.get(progress.scope).textContent = `${Math.round(progress.fraction * 100)}%`;
    }
  });

  api.onUpdateState(renderUpdate);
  api.onModsUpdated(() => {
    if ($('#view-mods').classList.contains('active')) {
      refreshMods();
    }
  });
  $('#update-action').addEventListener('click', runUpdateAction);
  $('#setting-check-update').addEventListener('click', () => unwrap(api.checkForUpdates()).then(renderUpdate).catch(showError));

  // 설정 가져오기
  $('#import-rescan').addEventListener('click', loadImportSources);
  $('#import-choose').addEventListener('click', chooseImportFolder);

  // 내 모드
  api.onModUpdates((updates) => {
    state.modUpdates = updates;
    renderModUpdates();

    if ($('#view-mods').classList.contains('active')) {
      refreshMods();
    }
  });
  $('#mods-check-updates').addEventListener('click', checkModUpdates);
  $('#mods-update-all').addEventListener('click', updateAllMods);
  $('#mods-add').addEventListener('click', () => unwrap(api.addModFiles()).then(reportAdded).catch(showError));
  $('#mods-open-folder').addEventListener('click', () => unwrap(api.openModsFolder()).catch(showError));

  const modsView = $('#view-mods');
  const overlay = $('#drop-overlay');
  let dragDepth = 0;
  const hasFiles = (event) => Array.from(event.dataTransfer.types).includes('Files');

  modsView.addEventListener('dragenter', (event) => {
    if (hasFiles(event)) {
      dragDepth += 1;
      overlay.hidden = false;
    }
  });
  modsView.addEventListener('dragleave', () => {
    dragDepth = Math.max(0, dragDepth - 1);
    overlay.hidden = dragDepth === 0;
  });
  modsView.addEventListener('drop', (event) => {
    dragDepth = 0;
    overlay.hidden = true;

    if (event.dataTransfer.files.length > 0) {
      unwrap(api.addDroppedFiles(Array.from(event.dataTransfer.files))).then(reportAdded).catch(showError);
    }
  });
  // 파일을 창에 떨어뜨렸을 때 브라우저가 그 파일을 열어 버리지 않게 한다.
  document.addEventListener('dragover', (event) => event.preventDefault());
  document.addEventListener('drop', (event) => event.preventDefault());

  // 모드 찾기
  for (const segment of document.querySelectorAll('.segment[data-source]')) {
    segment.addEventListener('click', () => {
      state.browse.source = segment.dataset.source;

      for (const other of document.querySelectorAll('.segment[data-source]')) {
        other.classList.toggle('active', other === segment);
      }

      searchMods(true);
    });
  }

  $('#browse-form').addEventListener('submit', (event) => {
    event.preventDefault();
    state.browse.query = $('#browse-query').value.trim();
    searchMods(true);
  });
  $('#browse-more').addEventListener('click', () => {
    state.browse.page += 1;
    searchMods(false);
  });

  // 설정
  const saveSettings = (partial) =>
    unwrap(api.updateSettings(partial))
      .then(refreshState)
      .catch(showError);

  for (const button of document.querySelectorAll('#setting-launch-mode [data-launch-mode]')) {
    button.addEventListener('click', () => saveSettings({ launchMode: button.dataset.launchMode }));
  }

  $('#setting-memory').addEventListener('input', (event) => {
    $('#setting-memory-value').textContent = `${event.target.value} GB`;
  });
  $('#setting-memory').addEventListener('change', (event) => saveSettings({ memoryGb: Number(event.target.value) }));
  $('#setting-resolution-enabled').addEventListener('change', (event) => saveSettings({ resolutionEnabled: event.target.checked }));

  for (const [selector, name] of [['#setting-resolution-width', 'resolutionWidth'], ['#setting-resolution-height', 'resolutionHeight']]) {
    $(selector).addEventListener('change', (event) => {
      const value = Number(event.target.value);

      // 숫자가 아닌 입력은 저장하지 않고 원래 값으로 되돌린다. 범위를 벗어난 값은 저장할 때 보정된다.
      if (event.target.value.trim() === '' || !Number.isFinite(value)) {
        renderState();
      } else {
        saveSettings({ [name]: value });
      }
    });
  }

  $('#setting-jvm-args').addEventListener('change', (event) => saveSettings({ jvmArgs: event.target.value }));
  $('#setting-choose-java').addEventListener('click', () => unwrap(api.chooseJava()).then(refreshState).catch(showError));
  $('#setting-reset-java').addEventListener('click', () => saveSettings({ javaPath: '' }));
  $('#setting-close').addEventListener('change', (event) => saveSettings({ closeOnLaunch: event.target.checked }));
  $('#setting-auto-update').addEventListener('change', (event) => saveSettings({ autoUpdate: event.target.checked }));
  $('#setting-mod-update-check').addEventListener('change', (event) => saveSettings({ modUpdateCheck: event.target.checked }));
  $('#setting-open-logs').addEventListener('click', () => unwrap(api.openLogsFolder()).catch(showError));

  // Cafeteria 설정 공유. 하나가 끝날 때까지 두 버튼을 모두 잠근다 (가져오기가 두 번 겹치면 두 번째가 bak 을 덮어쓴다).
  const clientSettingsButtons = [$('#client-settings-export'), $('#client-settings-import')];
  const shareClientSettings = async (call, successMessage) => {
    for (const button of clientSettingsButtons) {
      button.disabled = true;
    }

    try {
      const result = await unwrap(call());

      // 대화상자를 취소하면 null 이 온다. 그때는 아무것도 띄우지 않는다.
      if (result) {
        toast(successMessage(result), 'success');
      }
    } catch (error) {
      showError(error);
    } finally {
      for (const button of clientSettingsButtons) {
        button.disabled = false;
      }
    }
  };

  $('#client-settings-export').addEventListener('click', () =>
    shareClientSettings(api.exportClientSettings, (result) => `Cafeteria 설정을 내보냈습니다. (모듈 ${result.modules}개)`),
  );
  $('#client-settings-import').addEventListener('click', () =>
    shareClientSettings(api.importClientSettings, () => 'Cafeteria 설정을 가져왔습니다. 다음에 게임을 켜면 적용됩니다.'),
  );

  $('#setting-repair').addEventListener('click', async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    button.textContent = '복구 중...';
    state.repairing = true;

    try {
      await unwrap(api.repair());
      toast('설치를 복구했습니다.', 'success');
    } catch (error) {
      showError(error);
    } finally {
      state.repairing = false;
      button.disabled = false;
      button.textContent = '복구';
      refreshState().catch(showError);
    }
  });

  $('#setting-remove-profile').addEventListener('click', async () => {
    if (!window.confirm('공식 런처에서 Cafeteria 프로필을 제거할까요?\n게임 폴더(모드, 설정, 월드)는 지워지지 않습니다.')) {
      return;
    }

    try {
      const removed = await unwrap(api.removeProfile());
      toast(removed ? 'Cafeteria 프로필을 제거했습니다.' : '제거할 Cafeteria 프로필이 없습니다.', removed ? 'success' : 'info');
    } catch (error) {
      showError(error);
    }

    refreshState().catch(showError);
  });

  $('#setting-reset').addEventListener('click', async () => {
    if (!window.confirm('설정을 기본값으로 되돌릴까요?\n게임 폴더 위치와 CurseForge 키는 유지됩니다.')) {
      return;
    }

    try {
      await unwrap(api.resetSettings());
      await refreshState();
      toast('설정을 기본값으로 되돌렸습니다.', 'success');
    } catch (error) {
      showError(error);
    }
  });
  $('#setting-open-game-dir').addEventListener('click', () => unwrap(api.openGameFolder()).catch(showError));
  $('#setting-choose-game-dir').addEventListener('click', () => unwrap(api.chooseGameDir()).then(refreshState).catch(showError));
  $('#setting-choose-launcher').addEventListener('click', () => unwrap(api.chooseLauncher()).then(refreshState).catch(showError));
  $('#setting-reset-launcher').addEventListener('click', () => saveSettings({ launcherPath: '' }));

  const saveKey = async (key) => {
    try {
      await unwrap(api.setCurseforgeKey(key));
      $('#curseforge-key').value = '';
      await refreshState();
      toast(key ? 'CurseForge API 키를 저장했습니다.' : 'CurseForge API 키를 삭제했습니다.', 'success');

      // 키 상태가 바뀌었으니 CurseForge 검색 화면을 다시 불러오게 한다.
      if (state.browse.source === 'curseforge') {
        state.browse.started = false;
      }
    } catch (error) {
      showError(error);
    }
  };

  $('#curseforge-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const key = $('#curseforge-key').value.trim();

    if (!key) {
      toast('API 키를 입력해 주세요.', 'error');
      return;
    }

    saveKey(key);
  });
  $('#curseforge-clear').addEventListener('click', () => saveKey(''));

  document.addEventListener('click', (event) => {
    const link = event.target.closest('[data-external]');

    if (link) {
      event.preventDefault();
      unwrap(api.openExternal(link.dataset.external)).catch(showError);
    }
  });
}

bindEvents();
refreshState().catch(showError);
// 런처를 켤 때 미리 확인해 둔 모드 업데이트가 있으면 표시한다.
unwrap(api.getModUpdates())
  .then((updates) => {
    state.modUpdates = updates;
    renderModUpdates();
  })
  .catch(showError);
