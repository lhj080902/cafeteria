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

  try {
    await fsp.writeFile(temporary, content);
    await fsp.rename(temporary, file);
  } catch (error) {
    // 다 쓰지 못했거나 이름을 바꾸지 못하면(대상 자리에 폴더가 있는 경우 등) 임시 파일이 남는다. 지우고 원래 오류를 알린다.
    await fsp.rm(temporary, { force: true }).catch(() => {});
    throw error;
  }
}

async function readJson(file, fallback) {
  try {
    return JSON.parse(await fsp.readFile(file, 'utf8'));
  } catch {
    return fallback;
  }
}

module.exports = { exists, writeFileAtomic, readJson };
