'use strict';

const { execFile, spawn } = require('node:child_process');
const path = require('node:path');
const { exists } = require('./fs-util');

/** MSI 로 설치한 공식 런처의 기본 위치들. */
function windowsCandidates(env = process.env) {
  const roots = [env['ProgramFiles(x86)'], env.ProgramFiles, env.ProgramW6432].filter(Boolean);
  return [...new Set(roots)].map((root) => path.win32.join(root, 'Minecraft Launcher', 'MinecraftLauncher.exe'));
}

function run(command, args, { timeoutMs = 15_000 } = {}) {
  return new Promise((resolve) => {
    execFile(command, args, { timeout: timeoutMs, windowsHide: true }, (error, stdout) => {
      resolve(error ? '' : String(stdout).trim());
    });
  });
}

/** 프로그램을 런처와 분리해서 실행한다 (런처를 닫아도 계속 떠 있게). 실행 자체에 실패하면 false. */
function startDetached(command, args) {
  return new Promise((resolve) => {
    let child;

    try {
      child = spawn(command, args, { detached: true, stdio: 'ignore', windowsHide: false });
    } catch {
      resolve(false);
      return;
    }

    child.once('error', () => resolve(false));
    child.once('spawn', () => {
      child.unref();
      resolve(true);
    });
  });
}

/**
 * 시작 메뉴에 등록된 "Minecraft Launcher" 의 앱 ID 를 찾는다.
 * Microsoft Store/Xbox 앱으로 설치한 런처는 실행 파일 경로로 직접 실행할 수 없어서 이 ID 로 실행해야 한다.
 */
async function findWindowsStartApp() {
  const script = "(Get-StartApps | Where-Object { $_.Name -like 'Minecraft Launcher*' } | Select-Object -First 1).AppID";
  const appId = await run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script]);
  // 앱 ID 에 올 수 있는 글자만 허용한다 (셸 인자로 넘기므로).
  return /^[\w.!{}\\ -]+$/.test(appId) ? appId : '';
}

/**
 * 공식 Minecraft 런처를 실행한다.
 * @returns {Promise<{launched: boolean, method: 'custom'|'exe'|'start-menu'|'command'|'none'}>}
 */
async function launchOfficialLauncher({ customPath = '', platform = process.platform, env = process.env } = {}) {
  if (customPath && (await exists(customPath))) {
    if (await startDetached(customPath, [])) {
      return { launched: true, method: 'custom' };
    }
  }

  if (platform === 'win32') {
    for (const candidate of windowsCandidates(env)) {
      if ((await exists(candidate)) && (await startDetached(candidate, []))) {
        return { launched: true, method: 'exe' };
      }
    }

    const appId = await findWindowsStartApp();

    if (appId && (await startDetached('explorer.exe', [`shell:AppsFolder\\${appId}`]))) {
      return { launched: true, method: 'start-menu' };
    }
  } else if (platform === 'darwin') {
    if ((await exists('/Applications/Minecraft.app')) && (await startDetached('open', ['-a', 'Minecraft']))) {
      return { launched: true, method: 'command' };
    }
  } else if (await startDetached('minecraft-launcher', [])) {
    return { launched: true, method: 'command' };
  }

  return { launched: false, method: 'none' };
}

/** 공식 런처가 이미 떠 있는지 확인한다 (Windows 전용, 확인할 수 없으면 false). */
async function isOfficialLauncherRunning({ platform = process.platform } = {}) {
  if (platform !== 'win32') {
    return false;
  }

  const output = await run('tasklist.exe', ['/FO', 'CSV', '/NH']);
  return /^"(MinecraftLauncher|Minecraft)\.exe"/im.test(output);
}

module.exports = { launchOfficialLauncher, isOfficialLauncherRunning, windowsCandidates };
