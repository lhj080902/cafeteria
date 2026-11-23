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

  const chip = $('#install-status');

  if (!info.status.officialLauncherFound) {
    setChip(chip, '공식 런처 필요', 'error');
  } else if (info.status.installed) {
    setChip(chip, '설치됨', 'ok');
  } else {
    setChip(chip, '설치 전', 'warn');
  }

  $('#launcher-missing').hidden = info.status.officialLauncherFound;

  const { settings } = info;
  $('#setting-memory').value = String(settings.memoryGb);
  $('#setting-memory-value').textContent = `${settings.memoryGb} GB`;
  $('#setting-resolution-enabled').checked = settings.resolutionEnabled;
  $('#setting-resolution-width').value = String(settings.resolutionWidth);
  $('#setting-resolution-height').value = String(settings.resolutionHeight);
  $('#setting-resolution-width').disabled = !settings.resolutionEnabled;
  $('#setting-resolution-height').disabled = !settings.resolutionEnabled;
  $('#setting-java-path').textContent = settings.javaPath || '자동 (공식 런처에 들어 있는 Java 사용)';
  $('#setting-jvm-args').value = settings.jvmArgs;
  $('#setting-close').checked = settings.closeOnLaunch;
  $('#setting-game-dir').textContent = info.gameDir;
  $('#setting-launcher-path').textContent = settings.launcherPath || '자동으로 찾기';
  setChip($('#curseforge-key-state'), settings.hasCurseforgeKey ? '저장됨' : '미설정', settings.hasCurseforgeKey ? 'ok' : null);
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

async function play() {
  const button = $('#play-button');
  button.disabled = true;
  button.textContent = '준비 중...';
  setNotice(null);
  setPlayProgress(0);
  $('#play-progress').hidden = false;

  try {
    const result = await unwrap(api.play());

    if (!result.launched) {
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
    button.disabled = false;
    button.textContent = '플레이';
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

  for (const mod of mods) {
    list.append(modRow(mod));
  }
}

function modRow(mod) {
  const title = el('div', { className: 'row-title', text: mod.name });

  if (mod.version) {
    title.append(el('span', { className: 'muted', text: `  ${mod.version}` }));
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

  if (result.rejected.length > 0) {
    toast(`jar 파일이 아니라서 추가하지 않았습니다:\n${result.rejected.join('\n')}`, 'error');
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

  api.onProgress((progress) => {
    if (progress.scope === 'play') {
      $('#play-message').textContent = progress.message;
      setPlayProgress(progress.fraction);
    } else if (state.installButtons.has(progress.scope)) {
      state.installButtons.get(progress.scope).textContent = `${Math.round(progress.fraction * 100)}%`;
    }
  });

  // 내 모드
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
  for (const segment of document.querySelectorAll('.segment')) {
    segment.addEventListener('click', () => {
      state.browse.source = segment.dataset.source;

      for (const other of document.querySelectorAll('.segment')) {
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
  $('#setting-open-logs').addEventListener('click', () => unwrap(api.openLogsFolder()).catch(showError));

  $('#setting-repair').addEventListener('click', async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    button.textContent = '복구 중...';

    try {
      await unwrap(api.repair());
      toast('설치를 복구했습니다.', 'success');
    } catch (error) {
      showError(error);
    } finally {
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
