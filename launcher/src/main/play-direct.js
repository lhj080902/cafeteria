'use strict';

const installer = require('./installer');
const gameFiles = require('./game-files');
const gameLaunch = require('./game-launch');
const versionMeta = require('./version-meta');

const DEFAULT_DEPS = {
  ensureMods: installer.ensureMods,
  ensureGameFiles: gameFiles.ensureGameFiles,
  launchLibraries: versionMeta.launchLibraries,
  buildArgs: gameLaunch.buildArgs,
  launchGame: gameLaunch.launchGame,
};

/** 안쪽 단계의 진행률(0~1)을 전체의 start~end 구간으로 옮긴다. 문구는 그대로 전달한다. */
function stage(onProgress, start, end) {
  return (progress) => {
    const fraction = Math.min(1, Math.max(0, Number(progress.fraction) || 0));
    onProgress({ message: progress.message, fraction: start + (end - start) * fraction });
  };
}

/**
 * 공식 런처 없이 게임을 바로 실행한다 (Windows x64).
 *  1. 로그인 세션 확인 (필요하면 갱신)  2. Cafeteria 모드와 Fabric API  3. 게임 파일  4. 실행
 * 로그인이 안 돼 있으면 아무것도 받지 않고 LOGIN_REQUIRED 로 멈춘다.
 *
 * @param {object} params
 * @param {{session: () => Promise<object>}} params.account AccountManager
 * @param {string} params.gameDir 카페테리아 게임 폴더
 * @param {string} params.dataDir 게임 파일(라이브러리, 에셋, Java) 폴더
 * @param {string | null} params.seedDir 파일을 먼저 복사해 올 공식 런처의 .minecraft 폴더
 * @param {string} params.resourcesDir 런처 리소스 폴더
 * @param {import('./mods').ModManager} params.modManager
 * @param {string} params.launcherVersion
 * @param {{memoryGb: number, jvmArgs: string, javaPath: string, resolution: {width: number, height: number} | null}} params.options
 *   settings.profileOptions() 의 결과
 * @param {(progress: {message: string, fraction: number}) => void} [params.onProgress]
 * @param {object} [params.deps] 테스트에서 바꿔 끼우는 모듈 함수
 * @returns {Promise<import('node:child_process').ChildProcess>}
 */
async function playDirect({ account, gameDir, dataDir, seedDir, resourcesDir, modManager, launcherVersion, options, onProgress = () => {}, deps = {} }) {
  const { ensureMods, ensureGameFiles, launchLibraries, buildArgs, launchGame } = { ...DEFAULT_DEPS, ...deps };

  onProgress({ message: '로그인을 확인하는 중...', fraction: 0 });
  const session = await account.session();
  onProgress({ message: '로그인을 확인하는 중...', fraction: 0.03 });

  await ensureMods({ gameDir, resourcesDir, modManager, onProgress: stage(onProgress, 0.03, 0.1) });
  const { versionJson, layout } = await ensureGameFiles({ dataDir, seedDir, repair: false, onProgress: stage(onProgress, 0.1, 0.97) });

  onProgress({ message: '게임을 시작하는 중...', fraction: 0.97 });
  const args = buildArgs({
    versionJson,
    layout,
    libraries: launchLibraries(versionJson),
    session,
    gameDir,
    launcherVersion,
    memoryGb: options.memoryGb,
    jvmArgs: options.jvmArgs,
    resolution: options.resolution,
  });
  const child = await launchGame({ javaPath: options.javaPath || layout.javaPath, args, gameDir });
  onProgress({ message: '게임을 시작하는 중...', fraction: 1 });
  return child;
}

/**
 * 바로 실행용 설치 복구. 로그인과 실행 없이 모드와 게임 파일을 모두 다시 확인한다 (게임 파일은 해시까지 검사).
 *
 * @param {object} params playDirect 와 같은 이름의 값 (account, launcherVersion, options 는 쓰지 않는다)
 * @returns {Promise<void>}
 */
async function repairDirect({ gameDir, dataDir, seedDir, resourcesDir, modManager, onProgress = () => {}, deps = {} }) {
  const { ensureMods, ensureGameFiles } = { ...DEFAULT_DEPS, ...deps };

  await ensureMods({ gameDir, resourcesDir, modManager, onProgress: stage(onProgress, 0, 0.1) });
  await ensureGameFiles({ dataDir, seedDir, repair: true, onProgress: stage(onProgress, 0.1, 1) });
}

module.exports = { playDirect, repairDirect };
