'use strict';

const { MINECRAFT_VERSION } = require('../constants');
const { LauncherError } = require('../errors');
const http = require('../http');

const API = 'https://api.modrinth.com/v2';
const LOADERS = JSON.stringify(['fabric']);
const GAME_VERSIONS = JSON.stringify([MINECRAFT_VERSION]);

/** Modrinth 에서 1.21.4 Fabric 용 모드를 검색한다. 검색어가 없으면 다운로드 수 순으로 보여 준다. */
async function search(query, { offset = 0, limit = 20 } = {}) {
  const params = new URLSearchParams({
    query: query || '',
    limit: String(limit),
    offset: String(offset),
    index: query ? 'relevance' : 'downloads',
    facets: JSON.stringify([['project_type:mod'], ['categories:fabric'], [`versions:${MINECRAFT_VERSION}`]]),
  });
  const data = await http.getJson(`${API}/search?${params}`);
  const hits = Array.isArray(data.hits) ? data.hits : [];

  return {
    total: Number(data.total_hits) || hits.length,
    items: hits.map((hit) => ({
      source: 'modrinth',
      id: hit.project_id,
      title: hit.title,
      description: hit.description || '',
      author: hit.author || '',
      downloads: Number(hit.downloads) || 0,
      iconUrl: hit.icon_url || null,
      pageUrl: `https://modrinth.com/mod/${encodeURIComponent(hit.slug || hit.project_id)}`,
    })),
  };
}

/** 프로젝트에 올라온 1.21.4 Fabric 용 버전 목록 (최신순). */
async function listVersions(projectId) {
  const versions = await http.getJson(
    `${API}/project/${encodeURIComponent(projectId)}/version?${new URLSearchParams({ loaders: LOADERS, game_versions: GAME_VERSIONS })}`,
  );
  return Array.isArray(versions) ? versions : [];
}

/**
 * Modrinth 의 버전 하나를 런처가 설치할 수 있는 파일 정보로 바꾼다.
 * @returns {Promise<{projectId: string, title: string, versionId: string, version: string, fileName: string, url: string, hashes: object, dependencies: string[]}>}
 */
async function describeVersion(version, title) {
  const file = (version.files || []).find((candidate) => candidate.primary) || (version.files || [])[0];

  if (!file) {
    throw new LauncherError('NO_COMPATIBLE_FILE', `"${title}"의 최신 버전에 받을 수 있는 파일이 없습니다.`);
  }

  const dependencies = [];

  for (const dependency of version.dependencies || []) {
    if (dependency.dependency_type !== 'required') {
      continue;
    }

    if (dependency.project_id) {
      dependencies.push(dependency.project_id);
    } else if (dependency.version_id) {
      // 버전만 지정된 의존성은 그 버전이 속한 프로젝트를 찾아서 넣는다.
      const dependencyVersion = await http.getJson(`${API}/version/${encodeURIComponent(dependency.version_id)}`);
      dependencies.push(dependencyVersion.project_id);
    }
  }

  return {
    projectId: version.project_id,
    title,
    versionId: version.id,
    version: version.version_number,
    fileName: file.filename,
    url: file.url,
    hashes: { sha1: file.hashes && file.hashes.sha1, sha512: file.hashes && file.hashes.sha512 },
    dependencies: [...new Set(dependencies)],
  };
}

/** 프로젝트에서 1.21.4 Fabric 용 최신 파일을 고른다. 정식(release) 버전을 우선하고, 없으면 가장 최근 것을 쓴다. */
async function resolveFile(projectId) {
  const [project, versions] = await Promise.all([http.getJson(`${API}/project/${encodeURIComponent(projectId)}`), listVersions(projectId)]);

  if (versions.length === 0) {
    throw new LauncherError('NO_COMPATIBLE_FILE', `"${project.title}"에는 Minecraft ${MINECRAFT_VERSION} Fabric용 파일이 없습니다.`);
  }

  const version = versions.find((candidate) => candidate.version_type === 'release') || versions[0];
  return describeVersion({ ...version, project_id: project.id }, project.title);
}

/**
 * 파일 해시(sha1)로 그 파일이 Modrinth 의 어느 프로젝트·어느 버전인지 찾는다.
 * @param {string[]} hashes
 * @returns {Promise<Record<string, object>>} 해시 → 버전 정보. Modrinth 에 없는 파일은 빠진다.
 */
async function identifyFiles(hashes) {
  if (hashes.length === 0) {
    return {};
  }

  const found = await http.postJson(`${API}/version_files`, { hashes, algorithm: 'sha1' });
  return found && typeof found === 'object' ? found : {};
}

/**
 * 지금 쓰는 버전보다 나중에 올라온 버전 중 바꿔 넣을 것을 고른다. 없으면 null.
 * 정식(release) 버전을 쓰는 중이면 정식 버전으로만 올리고, 베타·알파를 쓰는 중이면 더 새로운 베타·알파도 받는다.
 */
function pickNewerVersion(current, versions) {
  const publishedAt = (version) => Date.parse(version.date_published) || 0;
  const newer = versions.filter((version) => version.id !== current.id && publishedAt(version) > publishedAt(current)).sort((a, b) => publishedAt(b) - publishedAt(a));
  const release = newer.find((version) => version.version_type === 'release');
  return release || (current.version_type !== 'release' ? newer[0] : null) || null;
}

module.exports = { search, resolveFile, listVersions, describeVersion, identifyFiles, pickNewerVersion };
