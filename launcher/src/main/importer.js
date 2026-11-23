'use strict';

const fsp = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { LauncherError } = require('./errors');
const paths = require('./paths');

/*
 * 다른 클라이언트(루나 등)나 기본 마인크래프트 폴더에서 쓰던 설정을 Cafeteria 게임 폴더로 복사한다.
 * 원본은 건드리지 않는다.
 */

/**
 * 가져올 수 있는 항목.
 * - paths: 게임 폴더 기준 상대 경로(파일 또는 폴더)
 * - configMatch: config 폴더 바로 아래의 이름을 받아 이 항목에 속하는지 알려 주는 함수
 */
const ITEMS = [
  {
    id: 'options',
    label: '게임 설정',
    description: '키 설정, 마우스 감도, 비디오·소리 설정 (options.txt)',
    paths: ['options.txt'],
    selected: true,
  },
  {
    id: 'servers',
    label: '서버 목록',
    description: '멀티플레이 서버 목록 (servers.dat)',
    paths: ['servers.dat'],
    selected: true,
  },
  {
    id: 'xaero-config',
    label: 'Xaero 미니맵·월드맵 설정',
    description: '미니맵 크기·위치, 표시 옵션 등 (config 폴더의 xaero 파일들)',
    configMatch: (name) => /^xaero/i.test(name),
    selected: true,
  },
  {
    id: 'xaero-data',
    label: 'Xaero 웨이포인트·지도 데이터',
    description: '찍어 둔 웨이포인트와 탐험한 지도 (xaero 폴더, 예전 버전의 XaeroWaypoints·XaeroWorldMap 폴더)',
    paths: ['xaero', 'XaeroWaypoints', 'XaeroWorldMap'],
    selected: true,
  },
  {
    id: 'mod-config',
    label: '다른 모드 설정',
    description: 'config 폴더의 나머지 파일. Cafeteria 에 같은 모드를 넣었을 때만 의미가 있습니다',
    configMatch: (name) => !/^xaero/i.test(name) && name !== 'cafeteria.json',
    selected: false,
  },
  {
    id: 'resourcepacks',
    label: '리소스팩',
    description: 'resourcepacks 폴더',
    paths: ['resourcepacks'],
    selected: true,
  },
  {
    id: 'shaderpacks',
    label: '셰이더팩',
    description: 'shaderpacks 폴더 (셰이더 모드를 넣었을 때만 쓰입니다)',
    paths: ['shaderpacks'],
    selected: false,
  },
  {
    id: 'saves',
    label: '싱글플레이 월드',
    description: 'saves 폴더. 용량이 클 수 있습니다',
    paths: ['saves'],
    selected: false,
  },
];

/** 루나 폴더를 훑을 때 들어가지 않는 폴더 (용량이 크거나 설정과 무관한 곳). */
const SKIPPED_DIRECTORIES = new Set([
  'jre', 'natives', 'textures', 'licenses', 'logs', 'cache', 'assets', 'libraries', 'versions', 'crash-reports',
  'saves', 'resourcepacks', 'shaderpacks', 'screenshots', 'mods', 'config', 'xaero', 'node_modules',
]);
const MAX_SCAN_DEPTH = 6;
const MAX_SCANNED_DIRECTORIES = 4000;
/** 항목 크기를 셀 때 이 개수를 넘으면 세기를 멈춘다 (아주 큰 월드 폴더에서 오래 걸리지 않게). */
const MAX_COUNTED_FILES = 50_000;

async function stat(file) {
  return fsp.lstat(file).catch(() => null);
}

/** 항목이 가리키는 경로 중 실제로 있는 것들을 게임 폴더 기준 상대 경로로 돌려준다. */
async function resolveItemPaths(directory, item) {
  const found = [];

  for (const relative of item.paths || []) {
    if (await stat(path.join(directory, relative))) {
      found.push(relative);
    }
  }

  if (item.configMatch) {
    const names = await fsp.readdir(path.join(directory, 'config')).catch(() => []);

    for (const name of names.sort()) {
      if (item.configMatch(name)) {
        found.push(path.join('config', name));
      }
    }
  }

  return found;
}

/** 파일 하나 또는 폴더 안의 모든 파일을 (상대 경로, 크기)로 나열한다. 심볼릭 링크는 따라가지 않는다. */
async function* walkFiles(directory, relative) {
  const info = await stat(path.join(directory, relative));

  if (!info || info.isSymbolicLink()) {
    return;
  }

  if (info.isFile()) {
    yield { relative, size: info.size };
  } else if (info.isDirectory()) {
    const names = await fsp.readdir(path.join(directory, relative)).catch(() => []);

    for (const name of names.sort()) {
      yield* walkFiles(directory, path.join(relative, name));
    }
  }
}

/** 폴더 하나에서 가져올 수 있는 항목을 조사한다. 가져올 것이 없는 항목은 빠진다. */
async function describeSource(directory, label) {
  const items = [];

  for (const item of ITEMS) {
    const relativePaths = await resolveItemPaths(directory, item);
    let files = 0;
    let bytes = 0;

    for (const relative of relativePaths) {
      for await (const file of walkFiles(directory, relative)) {
        files += 1;
        bytes += file.size;

        if (files >= MAX_COUNTED_FILES) {
          break;
        }
      }
    }

    if (files > 0) {
      items.push({
        id: item.id,
        label: item.label,
        description: item.description,
        selected: item.selected,
        paths: relativePaths,
        files,
        bytes,
        countCapped: files >= MAX_COUNTED_FILES,
      });
    }
  }

  return { directory, label, items };
}

/** 게임 폴더처럼 보이는지: 설정 파일이나 설정 폴더가 하나라도 있는가. */
async function looksLikeGameDirectory(directory) {
  for (const marker of ['options.txt', 'servers.dat', 'config', 'xaero', 'XaeroWaypoints']) {
    if (await stat(path.join(directory, marker))) {
      return true;
    }
  }

  return false;
}

/** 루나 클라이언트 폴더(~/.lunarclient) 아래에서 게임 폴더처럼 보이는 곳을 찾는다. */
async function findGameDirectoriesUnder(root) {
  const found = [];
  const queue = [{ directory: root, depth: 0 }];
  let scanned = 0;

  while (queue.length > 0 && scanned < MAX_SCANNED_DIRECTORIES) {
    const { directory, depth } = queue.shift();
    scanned += 1;

    if (depth > 0 && (await looksLikeGameDirectory(directory))) {
      found.push(directory);
      // 게임 폴더 안쪽은 더 들어가지 않는다.
      continue;
    }

    if (depth >= MAX_SCAN_DEPTH) {
      continue;
    }

    const entries = await fsp.readdir(directory, { withFileTypes: true }).catch(() => []);

    for (const entry of entries) {
      if (entry.isDirectory() && !SKIPPED_DIRECTORIES.has(entry.name.toLowerCase())) {
        queue.push({ directory: path.join(directory, entry.name), depth: depth + 1 });
      }
    }
  }

  return found.sort();
}

function sameOrNested(a, b) {
  const first = path.resolve(a);
  const second = path.resolve(b);
  return first === second || first.startsWith(second + path.sep) || second.startsWith(first + path.sep);
}

/**
 * PC에서 설정을 가져올 수 있는 곳을 찾는다.
 * @returns {Promise<{sources: object[], lunarDirectory: string | null, lunarSettingsDirectory: string | null}>}
 */
async function findSources({ gameDir, home = os.homedir(), platform = process.platform, env = process.env } = {}) {
  const candidates = [];
  const minecraftDir = paths.minecraftDir({ platform, env, home });

  if (await stat(minecraftDir)) {
    candidates.push({ directory: minecraftDir, label: '기본 마인크래프트 폴더 (.minecraft)' });
  }

  const lunarDirectory = path.join(home, '.lunarclient');
  const lunarExists = Boolean(await stat(lunarDirectory));

  if (lunarExists) {
    for (const directory of await findGameDirectoriesUnder(lunarDirectory)) {
      candidates.push({ directory, label: `루나 클라이언트 폴더 (${path.relative(lunarDirectory, directory)})` });
    }
  }

  const sources = [];

  for (const candidate of candidates) {
    // Cafeteria 게임 폴더 자신(또는 그 안팎)은 가져올 곳이 아니다.
    if (sameOrNested(candidate.directory, gameDir) || sources.some((source) => path.resolve(source.directory) === path.resolve(candidate.directory))) {
      continue;
    }

    const source = await describeSource(candidate.directory, candidate.label);

    if (source.items.length > 0) {
      sources.push(source);
    }
  }

  const lunarSettings = path.join(lunarDirectory, 'settings');

  return {
    sources,
    lunarDirectory: lunarExists ? lunarDirectory : null,
    lunarSettingsDirectory: lunarExists && (await stat(lunarSettings)) ? lunarSettings : null,
  };
}

/**
 * 고른 항목을 게임 폴더로 복사한다.
 *
 * @param {object} options
 * @param {string} options.sourceDirectory 가져올 폴더
 * @param {string[]} options.itemIds 가져올 항목 ID 목록
 * @param {string} options.gameDir Cafeteria 게임 폴더
 * @param {boolean} [options.overwrite] 이미 있는 파일을 덮어쓸지 (기본: 건너뜀)
 * @returns {Promise<{copied: number, skipped: number, items: {id: string, label: string, copied: number, skipped: number}[]}>}
 */
async function importItems({ sourceDirectory, itemIds, gameDir, overwrite = false }) {
  const sourceInfo = typeof sourceDirectory === 'string' ? await stat(sourceDirectory) : null;

  if (!sourceInfo || !sourceInfo.isDirectory()) {
    throw new LauncherError('BAD_SOURCE', '가져올 폴더를 찾을 수 없습니다.');
  }

  if (sameOrNested(sourceDirectory, gameDir)) {
    throw new LauncherError('BAD_SOURCE', 'Cafeteria 게임 폴더 자신에서는 가져올 수 없습니다.');
  }

  const selected = ITEMS.filter((item) => Array.isArray(itemIds) && itemIds.includes(item.id));

  if (selected.length === 0) {
    throw new LauncherError('BAD_REQUEST', '가져올 항목을 하나 이상 골라 주세요.');
  }

  const result = { copied: 0, skipped: 0, items: [] };

  for (const item of selected) {
    const summary = { id: item.id, label: item.label, copied: 0, skipped: 0 };

    for (const relative of await resolveItemPaths(sourceDirectory, item)) {
      for await (const file of walkFiles(sourceDirectory, relative)) {
        const destination = path.join(gameDir, file.relative);

        if (!overwrite && (await stat(destination))) {
          summary.skipped += 1;
          continue;
        }

        await fsp.mkdir(path.dirname(destination), { recursive: true });
        await fsp.copyFile(path.join(sourceDirectory, file.relative), destination);
        summary.copied += 1;
      }
    }

    result.copied += summary.copied;
    result.skipped += summary.skipped;
    result.items.push(summary);
  }

  return result;
}

module.exports = { findSources, describeSource, importItems, ITEMS };
