'use strict';

const fsp = require('node:fs/promises');
const path = require('node:path');

async function exists(file) {
  try {
    await fsp.access(file);
    return true;
  } catch {
    return false;
  }
}

/** 쓰는 도중 프로그램이 꺼져도 기존 파일이 깨지지 않도록 임시 파일에 쓴 뒤 이름을 바꾼다. */
async function writeFileAtomic(file, content) {
  await fsp.mkdir(path.dirname(file), { recursive: true });
  const temporary = `${file}.${process.pid}.tmp`;
  await fsp.writeFile(temporary, content);
  await fsp.rename(temporary, file);
}

async function readJson(file, fallback) {
  try {
    return JSON.parse(await fsp.readFile(file, 'utf8'));
  } catch {
    return fallback;
  }
}

module.exports = { exists, writeFileAtomic, readJson };
