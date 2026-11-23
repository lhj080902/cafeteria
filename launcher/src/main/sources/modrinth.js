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

/**
 * 프로젝트에서 1.21.4 Fabric 용 최신 파일을 고른다. 정식(release) 버전을 우선하고, 없으면 가장 최근 것을 쓴다.
 * @returns {Promise<{projectId: string, title: string, versionId: string, version: string, fileName: string, url: string, hashes: object, dependencies: string[]}>}
 */
async function resolveFile(projectId) {
  const id = encodeURIComponent(projectId);
  const [project, versions] = await Promise.all([
    http.getJson(`${API}/project/${id}`),
    http.getJson(`${API}/project/${id}/version?${new URLSearchParams({ loaders: LOADERS, game_versions: GAME_VERSIONS })}`),
  ]);

  if (!Array.isArray(versions) || versions.length === 0) {
    throw new LauncherError('NO_COMPATIBLE_FILE', `"${project.title}"에는 Minecraft ${MINECRAFT_VERSION} Fabric용 파일이 없습니다.`);
  }

  const version = versions.find((candidate) => candidate.version_type === 'release') || versions[0];
  const file = (version.files || []).find((candidate) => candidate.primary) || (version.files || [])[0];

  if (!file) {
    throw new LauncherError('NO_COMPATIBLE_FILE', `"${project.title}"의 최신 버전에 받을 수 있는 파일이 없습니다.`);
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
    projectId: project.id,
    title: project.title,
    versionId: version.id,
    version: version.version_number,
    fileName: file.filename,
    url: file.url,
    hashes: { sha1: file.hashes && file.hashes.sha1, sha512: file.hashes && file.hashes.sha512 },
    dependencies: [...new Set(dependencies)],
  };
}

module.exports = { search, resolveFile };
