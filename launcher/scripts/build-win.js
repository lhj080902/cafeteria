'use strict';

/*
 * Windows 용 설치 파일(Cafeteria-Setup-x.y.z.exe)과 압축본(zip)을 만든다.
 *
 *   npm run build:win
 *
 * electron-builder 는 설치 파일에 넣을 언인스톨러를 만들 때, 중간 단계의 설치 프로그램을 한 번 "실행"한다.
 * Windows 가 아닌 곳에서는 이때 Wine 이 필요한데, electron-builder 안에는 실행하지 않고 파일에서 언인스톨러를
 * 직접 꺼내는 구현(UninstallerReader, macOS 빌드용)이 들어 있다. Linux/macOS 에서는 그 구현을 쓰도록 바꿔서
 * Wine 없이도 어느 OS에서나 같은 설치 파일이 나오게 한다.
 *
 * electron-builder 내부 모듈을 건드리므로 package.json 의 electron-builder 버전은 고정해 둔다.
 * 시스템에 설치된 Wine 을 쓰고 싶으면 USE_SYSTEM_WINE=true 로 실행한다.
 */
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const { Arch, Platform, build } = require('electron-builder');

require('./copy-mod');

const NSIS_SIGNATURE = Buffer.concat([Buffer.from([0xef, 0xbe, 0xad, 0xde]), Buffer.from('NullsoftInst')]);

/**
 * NSIS 설치 프로그램이 실행될 때 스스로 하는 무결성 검사(CRC)를 미리 해 본다.
 * 검사에 실패하는 파일은 Windows 에서 "설치 프로그램 무결성 검사 실패" 오류를 내며 실행되지 않으므로 빌드를 중단한다.
 */
function assertNsisIntegrity(file) {
  const data = fs.readFileSync(file);
  // 데이터 헤더는 512바이트 경계에서 시작한다: flags(4) 서명(16) 헤더 길이(4) 뒤따르는 전체 길이(4)
  let position = data.indexOf(NSIS_SIGNATURE);

  while (position !== -1 && (position - 4) % 512 !== 0) {
    position = data.indexOf(NSIS_SIGNATURE, position + 1);
  }

  if (position === -1) {
    throw new Error(`NSIS 데이터 헤더를 찾을 수 없습니다: ${file}`);
  }

  const start = position - 4;
  const end = start + data.readUInt32LE(start + 24) - 4;

  if (end + 4 > data.length) {
    throw new Error(`NSIS 데이터가 잘려 있습니다: ${file}`);
  }

  // 실행 파일의 첫 512바이트를 뺀 나머지(실행부 + 데이터)가 검사 대상이고, 그 뒤 4바이트에 기대값이 저장돼 있다.
  if (zlib.crc32(data.subarray(512, end)) !== data.readUInt32LE(end)) {
    throw new Error(`NSIS 무결성 검사(CRC)에 실패했습니다: ${file}`);
  }
}

function useBuiltInUninstallerExtraction() {
  const { WineVmManager } = require('app-builder-lib/out/vm/WineVm');
  const { UninstallerReader } = require('app-builder-lib/out/targets/nsis/nsisUtil');
  const runWithWine = WineVmManager.prototype.exec;

  WineVmManager.prototype.exec = async function exec(file, args, ...rest) {
    const isUninstallerStub = /\.exe$/i.test(file) && (!args || args.length === 0);

    if (!isUninstallerStub) {
      return runWithWine.call(this, file, args, ...rest);
    }

    // NsisTarget 이 기대하는 언인스톨러 파일 이름과 같은 규칙.
    const uninstaller = path.join(path.dirname(file), `${path.basename(file, 'exe')}__uninstaller.exe`);
    await UninstallerReader.exec(file, uninstaller);
    // 꺼낸 언인스톨러가 실제로 실행 가능한 형태인지 확인한 뒤에만 설치 파일에 넣는다.
    assertNsisIntegrity(uninstaller);
    console.log(`  • 언인스톨러 추출 (Wine 없이, 무결성 확인): ${path.basename(uninstaller)}`);
    return '';
  };
}

async function main() {
  if (process.platform !== 'win32' && process.env.USE_SYSTEM_WINE !== 'true') {
    useBuiltInUninstallerExtraction();
  }

  const artifacts = await build({ targets: Platform.WINDOWS.createTarget(['nsis', 'zip'], Arch.x64), publish: 'never' });
  console.log('\n빌드 완료:');

  for (const artifact of artifacts) {
    if (/-Setup-.*\.exe$/i.test(artifact)) {
      assertNsisIntegrity(artifact);
    }

    console.log(`  ${path.relative(process.cwd(), artifact)}`);
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
