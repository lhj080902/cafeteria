'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { ruleAllows, libraryPath, launchLibraries } = require('../../src/main/version-meta');

test('규칙이 없으면 허용, 있으면 조건이 맞는 마지막 규칙을 따른다', () => {
  assert.equal(ruleAllows(undefined), true);
  assert.equal(ruleAllows([]), true);
  assert.equal(ruleAllows([{ action: 'allow', os: { name: 'windows' } }]), true);
  assert.equal(ruleAllows([{ action: 'allow', os: { name: 'osx' } }]), false);
  assert.equal(ruleAllows([{ action: 'allow' }, { action: 'disallow', os: { name: 'osx' } }]), true);
  assert.equal(ruleAllows([{ action: 'allow' }, { action: 'disallow', os: { name: 'windows' } }]), false);
  assert.equal(ruleAllows([{ action: 'allow', os: { arch: 'x86' } }]), false);
  assert.equal(ruleAllows([{ action: 'allow', os: { name: 'windows', arch: 'x64' } }]), true);
  assert.equal(ruleAllows([{ action: 'allow', features: { has_custom_resolution: true } }]), false);
  assert.equal(ruleAllows([{ action: 'allow', features: { has_custom_resolution: true } }], { has_custom_resolution: true }), true);
  assert.equal(ruleAllows([{ action: 'allow', features: { is_demo_user: true } }], { has_custom_resolution: true }), false);
});

test('라이브러리 이름을 Maven 경로로 바꾼다', () => {
  assert.equal(libraryPath('org.ow2.asm:asm:9.10.1'), 'org/ow2/asm/asm/9.10.1/asm-9.10.1.jar');
  assert.equal(libraryPath('org.lwjgl:lwjgl:3.3.3:natives-windows'), 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar');
  assert.equal(libraryPath('net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7'), 'net/fabricmc/sponge-mixin/0.17.5+mixin.0.8.7/sponge-mixin-0.17.5+mixin.0.8.7.jar');
});

test('실행에 쓸 라이브러리: Fabric 먼저, 바닐라는 규칙을 통과하고 겹치지 않는 것만', () => {
  const fabric = { mavenUrl: 'https://maven.fabricmc.net/', libraries: [{ name: 'org.ow2.asm:asm:9.10.1', sha1: 'a'.repeat(40), size: 10 }] };
  const artifact = (name, sha1) => ({ path: libraryPath(name), sha1, size: 5, url: `https://libraries.minecraft.net/${libraryPath(name)}` });
  const versionJson = {
    libraries: [
      { name: 'org.ow2.asm:asm:9.6', downloads: { artifact: artifact('org.ow2.asm:asm:9.6', 'b'.repeat(40)) } },
      { name: 'org.lwjgl:lwjgl:3.3.3', downloads: { artifact: artifact('org.lwjgl:lwjgl:3.3.3', 'c'.repeat(40)) } },
      { name: 'org.lwjgl:lwjgl:3.3.3:natives-windows', rules: [{ action: 'allow', os: { name: 'windows' } }], downloads: { artifact: artifact('org.lwjgl:lwjgl:3.3.3:natives-windows', 'd'.repeat(40)) } },
      { name: 'org.lwjgl:lwjgl:3.3.3:natives-macos', rules: [{ action: 'allow', os: { name: 'osx' } }], downloads: { artifact: artifact('org.lwjgl:lwjgl:3.3.3:natives-macos', 'e'.repeat(40)) } },
    ],
  };

  assert.deepEqual(launchLibraries(versionJson, fabric), [
    { name: 'org.ow2.asm:asm:9.10.1', path: 'org/ow2/asm/asm/9.10.1/asm-9.10.1.jar', sha1: 'a'.repeat(40), size: 10, url: 'https://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar' },
    { name: 'org.lwjgl:lwjgl:3.3.3', ...artifact('org.lwjgl:lwjgl:3.3.3', 'c'.repeat(40)) },
    { name: 'org.lwjgl:lwjgl:3.3.3:natives-windows', ...artifact('org.lwjgl:lwjgl:3.3.3:natives-windows', 'd'.repeat(40)) },
  ]);
});
