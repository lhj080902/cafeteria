'use strict';

const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { buildArgs, launchGame, splitArgs } = require('../../src/main/game-launch');
const { tempDir } = require('./helpers');

/** 실제 1.21.4.json 의 arguments 를 그대로 옮긴 것 (2026-11-24). */
const VERSION_JSON = {
  id: '1.21.4',
  assetIndex: { id: '19' },
  arguments: {
    jvm: [
      { rules: [{ action: 'allow', os: { name: 'osx' } }], value: ['-XstartOnFirstThread'] },
      { rules: [{ action: 'allow', os: { name: 'windows' } }], value: '-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump' },
      { rules: [{ action: 'allow', os: { arch: 'x86' } }], value: ['-Xss1M', '-XX:+UnlockDiagnosticVMOptions', '-XX:-UseBASE64Intrinsics'] },
      '-Djava.library.path=${natives_directory}',
      '-Djna.tmpdir=${natives_directory}',
      '-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives_directory}',
      '-Dio.netty.native.workdir=${natives_directory}',
      '-Dminecraft.launcher.brand=${launcher_name}',
      '-Dminecraft.launcher.version=${launcher_version}',
      '-cp',
      '${classpath}',
    ],
    game: [
      '--username', '${auth_player_name}', '--version', '${version_name}', '--gameDir', '${game_directory}',
      '--assetsDir', '${assets_root}', '--assetIndex', '${assets_index_name}', '--uuid', '${auth_uuid}',
      '--accessToken', '${auth_access_token}', '--clientId', '${clientid}', '--xuid', '${auth_xuid}',
      '--userType', '${user_type}', '--versionType', '${version_type}',
      { rules: [{ action: 'allow', features: { is_demo_user: true } }], value: '--demo' },
      { rules: [{ action: 'allow', features: { has_custom_resolution: true } }], value: ['--width', '${resolution_width}', '--height', '${resolution_height}'] },
      { rules: [{ action: 'allow', features: { has_quick_plays_support: true } }], value: ['--quickPlayPath', '${quickPlayPath}'] },
      { rules: [{ action: 'allow', features: { is_quick_play_multiplayer: true } }], value: ['--quickPlayMultiplayer', '${quickPlayMultiplayer}'] },
    ],
  },
};
const DATA = path.join('C:', 'data');
const LAYOUT = {
  clientJar: path.join(DATA, 'versions', '1.21.4', '1.21.4.jar'),
  nativesDir: path.join(DATA, 'versions', '1.21.4', 'natives'),
  librariesDir: path.join(DATA, 'libraries'),
  assetsDir: path.join(DATA, 'assets'),
};
const LIBRARIES = [
  { name: 'net.fabricmc:fabric-loader:0.20.1', path: 'net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar' },
  { name: 'org.lwjgl:lwjgl:3.3.3', path: 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar' },
];
const SESSION = { accessToken: 'ACCESS_TOKEN', name: 'Steve', uuid: 'uuid-1', xuid: 'xuid-1' };
const GAME_DIR = path.join('C:', 'Users', '홍 길동', 'AppData', 'Roaming', '.cafeteria');

function build(overrides = {}) {
  return buildArgs({ versionJson: VERSION_JSON, layout: LAYOUT, libraries: LIBRARIES, session: SESSION, gameDir: GAME_DIR, launcherVersion: '1.3.0', memoryGb: 4, ...overrides });
}

/** 옵션 이름 바로 뒤의 값. 없으면 undefined. */
const valueOf = (args, option) => (args.includes(option) ? args[args.indexOf(option) + 1] : undefined);

test('추가 JVM 인수는 공백으로 나누되 큰따옴표로 묶은 부분은 하나로 본다', () => {
  assert.deepEqual(splitArgs(''), []);
  assert.deepEqual(splitArgs('  -Xmx2G   -Dfoo=bar '), ['-Xmx2G', '-Dfoo=bar']);
  assert.deepEqual(splitArgs('-Dpath="C:\\My Files\\x" -Da=b'), ['-Dpath=C:\\My Files\\x', '-Da=b']);
  assert.deepEqual(splitArgs('"-Dname=a b"'), ['-Dname=a b']);
});

test('JVM 인수: 런처 옵션, 바닐라(Windows 규칙), Fabric 순서이고 그 뒤에 메인 클래스가 온다', () => {
  const args = build({ jvmArgs: '-Dextra="a b"' });
  assert.equal(args[0], '-Xmx4G');
  const main = args.indexOf('net.fabricmc.loader.impl.launch.knot.KnotClient');
  const jvm = args.slice(0, main);
  assert.ok(main > 0);
  assert.ok(jvm.includes('-XX:+UseG1GC'));
  assert.ok(jvm.includes('-Dextra=a b'));
  assert.ok(jvm.includes('-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump'));
  assert.ok(jvm.includes(`-Djava.library.path=${LAYOUT.nativesDir}`));
  assert.ok(jvm.includes('-Dminecraft.launcher.brand=Cafeteria'));
  assert.ok(jvm.includes('-Dminecraft.launcher.version=1.3.0'));
  assert.ok(!jvm.includes('-XstartOnFirstThread'));
  assert.ok(!jvm.includes('-Xss1M'));
  assert.ok(jvm.indexOf('-Dextra=a b') < jvm.indexOf('-cp'));
  assert.deepEqual(jvm.slice(-3), ['-XX:+UnlockDiagnosticVMOptions', '-XX:-UseBASE64Intrinsics', '-DFabricMcEmu= net.minecraft.client.main.Main ']);
});

test('클래스패스는 받은 순서의 라이브러리 뒤에 client.jar 가 오고 한 인수로 들어간다', () => {
  const args = build();
  assert.equal(
    valueOf(args, '-cp'),
    [
      path.join(LAYOUT.librariesDir, 'net', 'fabricmc', 'fabric-loader', '0.20.1', 'fabric-loader-0.20.1.jar'),
      path.join(LAYOUT.librariesDir, 'org', 'lwjgl', 'lwjgl', '3.3.3', 'lwjgl-3.3.3.jar'),
      LAYOUT.clientJar,
    ].join(';'),
  );
});

test('게임 인수에 계정, 폴더, 버전이 들어가고 치환되지 않은 값이 남지 않는다', () => {
  const args = build();
  const game = args.slice(args.indexOf('net.fabricmc.loader.impl.launch.knot.KnotClient') + 1);
  assert.deepEqual(game, [
    '--username', 'Steve', '--version', 'fabric-loader-0.20.1-1.21.4', '--gameDir', GAME_DIR,
    '--assetsDir', LAYOUT.assetsDir, '--assetIndex', '19', '--uuid', 'uuid-1',
    '--accessToken', 'ACCESS_TOKEN', '--xuid', 'xuid-1', '--userType', 'msa', '--versionType', 'release',
  ]);
  assert.ok(args.every((arg) => !arg.includes('${')));
});

test('xuid 가 없으면 --xuid 를 통째로 빼고, 창 크기를 정했을 때만 --width, --height 를 넣는다', () => {
  const withoutXuid = build({ session: { ...SESSION, xuid: '' } });
  assert.ok(!withoutXuid.includes('--xuid'));
  assert.equal(valueOf(withoutXuid, '--userType'), 'msa');
  assert.ok(!withoutXuid.includes('--width'));

  const sized = build({ resolution: { width: 1280, height: 720 } });
  assert.equal(valueOf(sized, '--width'), '1280');
  assert.equal(valueOf(sized, '--height'), '720');
});

test('공백이나 한글이 든 경로도 인수 하나로 그대로 들어간다', () => {
  const args = build();
  assert.equal(valueOf(args, '--gameDir'), GAME_DIR);
  assert.equal(args.filter((arg) => arg.includes('홍 길동')).length, 1);
});

/** 'spawn' 또는 'error' 를 내는 가짜 child_process.spawn. */
function fakeSpawn(outcome) {
  const calls = [];
  const spawn = (command, args, options) => {
    const child = new EventEmitter();
    child.unrefCalled = false;
    child.unref = () => {
      child.unrefCalled = true;
    };
    calls.push({ command, args, options, child });
    setImmediate(() => child.emit(outcome, outcome === 'error' ? new Error('spawn ENOENT') : undefined));
    return child;
  };
  spawn.calls = calls;
  return spawn;
}

test('게임을 런처와 분리해서 실행하고, 표준 오류만 로그 파일에 남긴다', async (t) => {
  const gameDir = tempDir(t);
  const spawn = fakeSpawn('spawn');
  const child = await launchGame({ javaPath: 'C:\\java\\bin\\javaw.exe', args: ['-Xmx4G', 'Main'], gameDir, spawn });
  const [call] = spawn.calls;
  assert.equal(child, call.child);
  assert.equal(call.command, 'C:\\java\\bin\\javaw.exe');
  assert.deepEqual(call.args, ['-Xmx4G', 'Main']);
  assert.equal(call.options.cwd, gameDir);
  assert.equal(call.options.detached, true);
  assert.ok(!call.options.shell);
  assert.ok(!call.options.windowsHide);
  assert.deepEqual(call.options.stdio.slice(0, 2), ['ignore', 'ignore']);
  assert.equal(typeof call.options.stdio[2], 'number');
  assert.equal(child.unrefCalled, true);
  assert.equal(fs.readFileSync(path.join(gameDir, 'logs', 'launcher-stderr.log'), 'utf8'), '');
});

test('Java 를 실행하지 못하면 LAUNCH_FAILED 이고 오류에 실행 인수가 들어가지 않는다', async (t) => {
  const error = await launchGame({ javaPath: 'C:\\없는\\javaw.exe', args: ['--accessToken', 'ACCESS_TOKEN'], gameDir: tempDir(t), spawn: fakeSpawn('error') }).catch((caught) => caught);
  assert.equal(error.code, 'LAUNCH_FAILED');
  assert.ok(!JSON.stringify({ message: error.message, details: error.details }).includes('ACCESS_TOKEN'));
});
