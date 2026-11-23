'use strict';

/*
 * 빌드된 Cafeteria 모드 jar 와 아이콘을 런처 리소스 폴더로 복사한다.
 * 먼저 ../mod 에서 `./gradlew build` 를 실행해 두어야 한다.
 */
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const libs = path.join(root, 'mod', 'build', 'libs');
const resources = path.join(__dirname, '..', 'resources');
const modsDir = path.join(resources, 'mods');

const jars = fs.existsSync(libs)
  ? fs.readdirSync(libs).filter((name) => /^cafeteria-client-.*\.jar$/.test(name) && !name.endsWith('-sources.jar'))
  : [];

if (jars.length === 0) {
  console.error(`모드 jar 를 찾을 수 없습니다: ${libs}\n먼저 mod 폴더에서 "./gradlew build" 를 실행해 주세요.`);
  process.exit(1);
}

// 가장 최근에 빌드된 jar 하나만 넣는다.
const newest = jars
  .map((name) => ({ name, time: fs.statSync(path.join(libs, name)).mtimeMs }))
  .sort((a, b) => b.time - a.time)[0].name;

fs.mkdirSync(modsDir, { recursive: true });

for (const name of fs.readdirSync(modsDir)) {
  if (name.endsWith('.jar')) {
    fs.rmSync(path.join(modsDir, name));
  }
}

fs.copyFileSync(path.join(libs, newest), path.join(modsDir, newest));
console.log(`모드 복사 완료: ${newest}`);
