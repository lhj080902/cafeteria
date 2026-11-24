'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { DOWNLOAD_HOSTS, FABRIC, FABRIC_PROFILE_URL, JAVA_RUNTIME, MINECRAFT_VERSION, MINECRAFT_VERSION_JSON } = require('../../src/main/constants');
const http = require('../../src/main/http');
const { live, tempDir } = require('./helpers');

test('고정한 1.21.4.json 과 Java 목록은 주소의 내용이 고정한 sha1 과 같다', live, async (t) => {
  const directory = tempDir(t);
  await http.download(MINECRAFT_VERSION_JSON.url, path.join(directory, 'version.json'), { allowedHosts: DOWNLOAD_HOSTS, hashes: { sha1: MINECRAFT_VERSION_JSON.sha1 } });
  await http.download(JAVA_RUNTIME.manifestUrl, path.join(directory, 'runtime.json'), { allowedHosts: DOWNLOAD_HOSTS, hashes: { sha1: JAVA_RUNTIME.sha1 } });
  const version = JSON.parse(fs.readFileSync(path.join(directory, 'version.json'), 'utf8'));
  assert.equal(version.id, MINECRAFT_VERSION);
  assert.equal(version.javaVersion.component, JAVA_RUNTIME.component);
});

test('고정한 Fabric 구성이 Fabric 서버의 지금 내용과 같다', live, async () => {
  const profile = await http.getJson(FABRIC_PROFILE_URL);
  assert.equal(profile.mainClass, FABRIC.mainClass);
  assert.deepEqual(profile.arguments.jvm, FABRIC.jvmArgs);
  assert.deepEqual(profile.arguments.game, []);
  assert.deepEqual(profile.libraries.map((library) => library.name), FABRIC.libraries.map((library) => library.name));

  for (const [index, library] of profile.libraries.entries()) {
    assert.equal(library.url, FABRIC.mavenUrl);
    // 서버가 해시를 주는 라이브러리는 고정한 값과 같아야 한다.
    if (library.sha1) {
      assert.equal(library.sha1, FABRIC.libraries[index].sha1);
      assert.equal(library.size, FABRIC.libraries[index].size);
    }
  }
});
