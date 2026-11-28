'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { writeFileAtomic } = require('../../src/main/fs-util');
const { tempDir } = require('./helpers');

test('writeFileAtomic 은 실패해도 임시 파일을 남기지 않는다', async (t) => {
  const directory = tempDir(t);
  const file = path.join(directory, 'data.json');

  await writeFileAtomic(file, '{"a":1}');
  assert.equal(fs.readFileSync(file, 'utf8'), '{"a":1}');
  assert.deepEqual(fs.readdirSync(directory), ['data.json']);

  // 대상 자리에 폴더가 있어서, 임시 파일을 다 쓴 뒤 이름을 바꾸지 못한다.
  const blocked = path.join(directory, 'blocked');
  fs.mkdirSync(blocked);

  await assert.rejects(writeFileAtomic(blocked, '쓰지 못할 내용'), (error) => /^E[A-Z]+$/.test(error.code));
  assert.deepEqual(fs.readdirSync(directory).filter((name) => name.endsWith('.tmp')), []);
  assert.deepEqual(fs.readdirSync(directory).sort(), ['blocked', 'data.json']);
  assert.deepEqual(fs.readdirSync(blocked), []);
});
