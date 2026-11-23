'use strict';

const { MINECRAFT_VERSION } = require('../constants');
const { LauncherError } = require('../errors');
const http = require('../http');

/*
 * CurseForge REST API (https://docs.curseforge.com/rest-api/)
 * 모든 요청에 x-api-key 헤더가 필요하다. 키는 사용자가 설정 화면에 직접 넣는다.
 */
const API = 'https://api.curseforge.com';
const GAME_ID_MINECRAFT = '432';
const CLASS_ID_MODS = '6';
const MOD_LOADER_FABRIC = '4';
const SORT_BY_POPULARITY = '2';
const RELATION_REQUIRED = 3;
const RELEASE_TYPE_RELEASE = 1;

function headers(apiKey) {
  if (!apiKey) {
    throw new LauncherError('NO_CURSEFORGE_KEY', 'CurseForge를 쓰려면 설정에서 CurseForge API 키를 먼저 입력해 주세요.');
  }

  return { 'x-api-key': apiKey };
}

async function call(url, apiKey) {
  try {
    return await http.getJson(url, { headers: headers(apiKey) });
  } catch (error) {
    if (error instanceof LauncherError && error.code === 'HTTP' && [401, 403].includes(error.details.status)) {
      throw new LauncherError('BAD_CURSEFORGE_KEY', 'CurseForge API 키가 올바르지 않거나 권한이 없습니다. 설정에서 키를 다시 확인해 주세요.');
    }

    throw error;
  }
}

/** CurseForge 에서 1.21.4 Fabric 용 모드를 검색한다. */
async function search(query, { offset = 0, limit = 20 } = {}, apiKey) {
  const params = new URLSearchParams({
    gameId: GAME_ID_MINECRAFT,
    classId: CLASS_ID_MODS,
    gameVersion: MINECRAFT_VERSION,
    modLoaderType: MOD_LOADER_FABRIC,
    sortField: SORT_BY_POPULARITY,
    sortOrder: 'desc',
    index: String(offset),
    pageSize: String(limit),
  });

  if (query) {
    params.set('searchFilter', query);
  }

  const response = await call(`${API}/v1/mods/search?${params}`, apiKey);
  const mods = Array.isArray(response.data) ? response.data : [];

  return {
    total: Number(response.pagination && response.pagination.totalCount) || mods.length,
    items: mods.map((mod) => ({
      source: 'curseforge',
      id: String(mod.id),
      title: mod.name,
      description: mod.summary || '',
      author: (mod.authors && mod.authors[0] && mod.authors[0].name) || '',
      downloads: Number(mod.downloadCount) || 0,
      iconUrl: (mod.logo && (mod.logo.thumbnailUrl || mod.logo.url)) || null,
      pageUrl: (mod.links && mod.links.websiteUrl) || `https://www.curseforge.com/minecraft/mc-mods/${encodeURIComponent(mod.slug || '')}`,
    })),
  };
}

/** 해시 문자열의 길이로 알고리즘을 구분해서 sha1 값만 꺼낸다 (sha1 = 16진수 40자, md5 = 32자). */
function sha1Of(file) {
  const hash = (file.hashes || []).find((candidate) => typeof candidate.value === 'string' && /^[0-9a-f]{40}$/i.test(candidate.value));
  return hash ? hash.value : undefined;
}

/**
 * 프로젝트에서 1.21.4 Fabric 용 최신 파일을 고른다. 정식(release) 파일을 우선하고, 없으면 가장 최근 것을 쓴다.
 * 제작자가 외부 프로그램 배포를 막아 둔 모드는 downloadUrl 이 비어 있어서 받을 수 없다.
 */
async function resolveFile(projectId, apiKey) {
  const id = encodeURIComponent(projectId);
  const params = new URLSearchParams({ gameVersion: MINECRAFT_VERSION, modLoaderType: MOD_LOADER_FABRIC, pageSize: '50' });
  const [project, files] = await Promise.all([
    call(`${API}/v1/mods/${id}`, apiKey),
    call(`${API}/v1/mods/${id}/files?${params}`, apiKey),
  ]);
  const mod = project.data || {};
  const title = mod.name || `CurseForge #${projectId}`;
  const candidates = (Array.isArray(files.data) ? files.data : [])
    .filter((file) => file.isAvailable !== false && typeof file.fileName === 'string' && file.fileName.toLowerCase().endsWith('.jar'))
    .sort((a, b) => String(b.fileDate).localeCompare(String(a.fileDate)));

  if (candidates.length === 0) {
    throw new LauncherError('NO_COMPATIBLE_FILE', `"${title}"에는 Minecraft ${MINECRAFT_VERSION} Fabric용 파일이 없습니다.`);
  }

  const file = candidates.find((candidate) => candidate.releaseType === RELEASE_TYPE_RELEASE) || candidates[0];

  if (!file.downloadUrl) {
    throw new LauncherError(
      'DISTRIBUTION_BLOCKED',
      `"${title}"은(는) 제작자가 외부 런처에서의 다운로드를 막아 두었습니다. CurseForge 사이트에서 직접 받은 뒤 "모드 파일 추가"로 넣어 주세요.`,
      { pageUrl: mod.links && mod.links.websiteUrl },
    );
  }

  return {
    projectId: String(projectId),
    title,
    versionId: String(file.id),
    version: file.displayName || file.fileName,
    fileName: file.fileName,
    url: file.downloadUrl,
    hashes: { sha1: sha1Of(file) },
    dependencies: [
      ...new Set((file.dependencies || []).filter((dependency) => dependency.relationType === RELATION_REQUIRED).map((dependency) => String(dependency.modId))),
    ],
  };
}

module.exports = { search, resolveFile };
