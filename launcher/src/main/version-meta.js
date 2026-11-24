'use strict';

const constants = require('./constants');

/** 바로 실행은 Windows x64 전용이므로 규칙은 항상 이 대상으로 평가한다. */
const TARGET_OS = { name: 'windows', arch: 'x64' };

function ruleMatches(rule, features) {
  if (rule.os) {
    if (rule.os.name !== undefined && rule.os.name !== TARGET_OS.name) {
      return false;
    }

    if (rule.os.arch !== undefined && rule.os.arch !== TARGET_OS.arch) {
      return false;
    }
  }

  if (rule.features) {
    for (const [feature, value] of Object.entries(rule.features)) {
      if ((features[feature] ?? false) !== value) {
        return false;
      }
    }
  }

  return true;
}

/**
 * Mojang 버전 JSON 의 rules 를 평가한다. 규칙이 없으면 허용하고, 있으면 조건이 맞는 마지막 규칙의 action 을 따른다 (기본은 거부).
 *
 * @param {object[] | undefined} rules
 * @param {object} [features] has_custom_resolution 같은 기능 플래그. 적히지 않은 항목은 false 로 본다
 */
function ruleAllows(rules, features = {}) {
  if (!rules || rules.length === 0) {
    return true;
  }

  let allowed = false;

  for (const rule of rules) {
    if (ruleMatches(rule, features)) {
      allowed = rule.action === 'allow';
    }
  }

  return allowed;
}

/** 'a.b:c:1.0[:분류자]' → 'a/b/c/1.0/c-1.0[-분류자].jar' ('/' 구분. 주소와 디스크 경로 양쪽에 쓴다). */
function libraryPath(name) {
  const [group, artifact, version, classifier] = name.split(':');
  const file = classifier ? `${artifact}-${version}-${classifier}.jar` : `${artifact}-${version}.jar`;
  return `${group.split('.').join('/')}/${artifact}/${version}/${file}`;
}

/** 버전을 뺀 '그룹:이름[:분류자]'. 같은 라이브러리의 다른 버전이 겹치는지 볼 때 쓴다. */
function libraryKey(name) {
  const [group, artifact, , classifier] = name.split(':');
  return classifier ? `${group}:${artifact}:${classifier}` : `${group}:${artifact}`;
}

/**
 * 실행에 쓸 라이브러리 목록. Fabric 라이브러리를 먼저, 그다음 규칙을 통과한 바닐라 라이브러리를 JSON 순서대로 둔다.
 * 같은 '그룹:이름[:분류자]' 가 앞에 이미 있으면 (Fabric 이 더 새 ASM 을 쓰는 경우 등) 뒤의 것은 건너뛴다.
 *
 * @returns {{name: string, path: string, sha1: string, size: number, url: string}[]}
 */
function launchLibraries(versionJson, fabric = constants.FABRIC) {
  const result = [];
  const seen = new Set();

  for (const library of fabric.libraries) {
    const libPath = libraryPath(library.name);
    seen.add(libraryKey(library.name));
    result.push({ name: library.name, path: libPath, sha1: library.sha1, size: library.size, url: `${fabric.mavenUrl}${libPath}` });
  }

  for (const library of versionJson.libraries || []) {
    const artifact = library.downloads && library.downloads.artifact;

    if (!artifact || !ruleAllows(library.rules) || seen.has(libraryKey(library.name))) {
      continue;
    }

    seen.add(libraryKey(library.name));
    result.push({ name: library.name, path: artifact.path, sha1: artifact.sha1, size: artifact.size, url: artifact.url });
  }

  return result;
}

module.exports = { ruleAllows, libraryPath, launchLibraries };
