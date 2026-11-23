'use strict';

/*
 * 런처를 실제로 띄워서 주요 흐름을 처음부터 끝까지 확인하는 테스트.
 *
 *   node test/e2e/run.js            (화면이 없는 환경: xvfb-run -a node test/e2e/run.js)
 *
 * - Playwright 가 설치돼 있어야 한다 (런처 실행에는 필요 없는 개발용 도구).
 * - 진짜 .minecraft 는 건드리지 않고 임시 폴더에 가짜 공식 런처 환경을 만들어 쓴다.
 * - Modrinth 와 Fabric 서버에는 실제로 접속한다.
 * - 스크린샷은 test/e2e/output 에 저장된다.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { _electron: electron } = require('playwright');

const launcherDir = path.join(__dirname, '..', '..');
const outputDir = path.join(__dirname, 'output');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cafeteria-e2e-'));
const minecraftDir = path.join(root, '.minecraft');
const gameDir = path.join(root, '.cafeteria');
const userDataDir = path.join(root, 'user-data');
const launchedMarker = path.join(root, 'official-launcher-started');
const profilesFile = path.join(minecraftDir, 'launcher_profiles.json');
const settingsFile = path.join(userDataDir, 'settings.json');
const modsDir = path.join(gameDir, 'mods');

const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
const cafeteriaProfile = () => readJson(profilesFile).profiles['cafeteria-client'];

function prepareFakeComputer() {
  fs.mkdirSync(minecraftDir, { recursive: true });
  fs.mkdirSync(userDataDir, { recursive: true });
  fs.mkdirSync(outputDir, { recursive: true });
  fs.writeFileSync(profilesFile, JSON.stringify({ profiles: { vanilla: { name: '', type: 'latest-release', lastVersionId: 'latest-release' } }, settings: {} }));

  // "공식 런처" 역할을 하는 가짜 프로그램: 실행되면 표시 파일을 남긴다.
  const fakeLauncher = path.join(root, 'fake-official-launcher.sh');
  fs.writeFileSync(fakeLauncher, `#!/bin/sh\necho started > "${launchedMarker}"\n`, { mode: 0o755 });
  fs.writeFileSync(settingsFile, JSON.stringify({ launcherPath: fakeLauncher }));
}

async function main() {
  prepareFakeComputer();

  // CAFETERIA_E2E_EXECUTABLE 을 주면 빌드된(패키징된) 런처 실행 파일을 대상으로 같은 검사를 한다.
  const packaged = process.env.CAFETERIA_E2E_EXECUTABLE;
  const app = await electron.launch({
    // 기본은 런처 폴더에 설치된 Electron 으로 소스를 바로 실행한다 (require('electron') 은 실행 파일 경로를 돌려준다).
    executablePath: packaged || require('electron'),
    cwd: launcherDir,
    // --no-sandbox 는 컨테이너에서 root 로 돌릴 때만 필요한 옵션. 일반 PC에서는 없어도 된다.
    args: [...(process.getuid && process.getuid() === 0 ? ['--no-sandbox'] : []), ...(packaged ? [] : ['.'])],
    env: { ...process.env, CAFETERIA_MINECRAFT_DIR: minecraftDir, CAFETERIA_GAME_DIR: gameDir, CAFETERIA_USER_DATA: userDataDir },
  });
  const page = await app.firstWindow();
  const consoleErrors = [];
  page.on('console', (message) => {
    if (message.type() === 'error') {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(String(error)));
  page.on('dialog', (dialog) => dialog.accept());

  let step = 0;
  const shot = async (name) => {
    step += 1;
    await page.screenshot({ path: path.join(outputDir, `${String(step).padStart(2, '0')}-${name}.png`) });
  };
  const text = (selector) => page.locator(selector).innerText();
  const waitForText = (selector, expected) => page.locator(selector, { hasText: expected }).first().waitFor({ timeout: 60_000 });
  const nav = (view) => page.locator(`.nav-item[data-view="${view}"]`).click();
  const toastGone = () => page.locator('.toast').first().waitFor({ state: 'detached', timeout: 15_000 }).catch(() => {});

  try {
    // ── 홈: 설치 전 상태 ──
    await waitForText('#install-status', '설치 전');
    assert.equal(await text('#version-label'), 'Minecraft 1.21.4');
    assert.match(await text('#hero-subtitle'), /Fabric Loader 0\.20\.1/);
    await shot('home-before-install');

    // ── 플레이: 설치 + 공식 런처 실행 ──
    await page.locator('#play-button').click();
    await waitForText('#home-notice', '"Cafeteria" 프로필이 선택돼 있는지');
    await waitForText('#install-status', '설치됨');
    assert.equal(await text('#play-message'), '공식 Minecraft 런처를 열었습니다.');
    assert.equal(await text('#play-button'), '플레이');

    const profile = cafeteriaProfile();
    assert.equal(profile.name, 'Cafeteria');
    assert.equal(profile.lastVersionId, 'fabric-loader-0.20.1-1.21.4');
    assert.equal(profile.gameDir, gameDir);
    assert.match(profile.javaArgs, /^-Xmx4G /);
    assert.ok(readJson(profilesFile).profiles.vanilla, '기존 프로필은 남아 있어야 한다');
    assert.ok(fs.existsSync(path.join(minecraftDir, 'versions', 'fabric-loader-0.20.1-1.21.4', 'fabric-loader-0.20.1-1.21.4.json')));
    assert.deepEqual(fs.readdirSync(modsDir).sort(), ['cafeteria-client-1.0.0+1.21.4.jar', 'fabric-api-0.119.4+1.21.4.jar']);

    for (let i = 0; i < 100 && !fs.existsSync(launchedMarker); i++) {
      await page.waitForTimeout(50);
    }

    assert.ok(fs.existsSync(launchedMarker), '공식 런처(가짜)가 실행돼야 한다');
    await shot('home-after-play');

    // ── 내 모드: 기본 모드 표시 ──
    await nav('mods');
    await waitForText('#mods-list .row-title', 'Cafeteria Client');
    await waitForText('#mods-list .row-title', 'Fabric API');
    assert.equal(await page.locator('#mods-list .row').count(), 2);
    assert.equal(await page.locator('#mods-list .chip', { hasText: '기본 모드' }).count(), 2);
    assert.equal(await page.locator('#mods-list .button.danger').count(), 0, '기본 모드에는 삭제 버튼이 없다');
    await shot('mods-builtin');

    // ── 모드 찾기: Modrinth 인기순 → 검색 → 설치 ──
    await nav('browse');
    await page.locator('#browse-results .row').first().waitFor({ timeout: 60_000 });
    assert.equal(await page.locator('#browse-results .row').count(), 20);
    await shot('browse-popular');

    await page.locator('#browse-more').click();
    await page.waitForFunction(() => document.querySelectorAll('#browse-results .row').length === 40, null, { timeout: 60_000 });

    await page.locator('#browse-query').fill('sodium');
    await page.locator('#browse-form button[type="submit"]').click();
    await waitForText('#browse-results .row-title', 'Sodium');
    const sodiumRow = page.locator('#browse-results .row').filter({ has: page.locator('.row-title', { hasText: /^Sodium\s/ }) }).first();
    const installButton = sodiumRow.locator('.button.primary');
    assert.equal(await installButton.innerText(), '설치');
    await installButton.click();
    await sodiumRow.locator('.button.primary', { hasText: '설치됨' }).waitFor({ timeout: 120_000 });
    await waitForText('.toast.success', 'Sodium 설치 완료');
    const sodiumFile = fs.readdirSync(modsDir).find((name) => name.startsWith('sodium-fabric-'));
    assert.ok(sodiumFile, 'Sodium jar 가 mods 폴더에 있어야 한다');
    await page.waitForFunction(() => document.querySelectorAll('#browse-results .row-icon:not(.placeholder)').length > 0);
    await page.waitForTimeout(800);
    await shot('browse-search-installed');

    // 다시 검색하면 처음부터 "설치됨" 으로 나온다
    await page.locator('#browse-form button[type="submit"]').click();
    await sodiumRow.locator('.button.primary', { hasText: '설치됨' }).waitFor({ timeout: 60_000 });
    assert.equal(await sodiumRow.locator('.button.primary').isDisabled(), true);

    // ── 내 모드: 끄기 / 켜기 / 삭제 ──
    await nav('mods');
    const sodiumMod = page.locator('#mods-list .row').filter({ hasText: 'Sodium' });
    await sodiumMod.waitFor();
    assert.match(await sodiumMod.locator('.row-meta').innerText(), /^Modrinth · sodium-fabric-/);
    await shot('mods-with-sodium');

    await sodiumMod.locator('.switch').click();
    await page.locator('#mods-list .row.disabled').waitFor();
    assert.ok(fs.existsSync(path.join(modsDir, `${sodiumFile}.disabled`)), '끄면 .disabled 로 바뀐다');
    await page.locator('#mods-list .row.disabled .switch').click();
    await page.locator('#mods-list .row.disabled').waitFor({ state: 'detached' });
    assert.ok(fs.existsSync(path.join(modsDir, sodiumFile)), '켜면 .jar 로 돌아온다');

    await sodiumMod.locator('.button.danger').click();
    await sodiumMod.waitFor({ state: 'detached' });
    assert.equal(fs.existsSync(path.join(modsDir, sodiumFile)), false, '삭제하면 파일이 없어진다');
    await toastGone();

    // ── 내 모드: jar 끌어다 놓기 ──
    // 실제 경로가 붙은 File 객체를 만들기 위해 파일 입력 요소를 잠깐 쓰고, 그 파일들로 drop 이벤트를 보낸다.
    const AdmZip = require(path.join(launcherDir, 'node_modules', 'adm-zip'));
    const localJar = path.join(root, 'my-local-mod.jar');
    const localZip = new AdmZip();
    localZip.addFile('fabric.mod.json', Buffer.from(JSON.stringify({ id: 'my-local-mod', name: '내가 만든 모드', version: '0.1' })));
    localZip.writeZip(localJar);
    const notJar = path.join(root, 'notes.txt');
    fs.writeFileSync(notJar, 'jar 가 아닌 파일');
    await page.evaluate(() => {
      const input = document.createElement('input');
      input.type = 'file';
      input.multiple = true;
      input.id = 'e2e-files';
      document.body.append(input);
    });
    await page.locator('#e2e-files').setInputFiles([localJar, notJar]);
    await page.evaluate(() => {
      const transfer = new DataTransfer();

      for (const file of document.querySelector('#e2e-files').files) {
        transfer.items.add(file);
      }

      document.querySelector('#view-mods').dispatchEvent(new DragEvent('drop', { dataTransfer: transfer, bubbles: true, cancelable: true }));
    });
    await waitForText('.toast.success', '모드 1개를 추가했습니다.');
    await waitForText('.toast.error', 'notes.txt');
    const localRow = page.locator('#mods-list .row').filter({ hasText: '내가 만든 모드' });
    await localRow.waitFor();
    assert.match(await localRow.locator('.row-meta').innerText(), /^직접 추가 · my-local-mod\.jar/);
    assert.ok(fs.existsSync(path.join(modsDir, 'my-local-mod.jar')));
    await shot('mods-dropped-file');
    await localRow.locator('.button.danger').click();
    await localRow.waitFor({ state: 'detached' });
    await page.locator('.toast').first().waitFor({ state: 'detached', timeout: 15_000 }).catch(() => {});
    await toastGone();
    await toastGone();

    // ── CurseForge: 키가 없으면 안내 후 설정으로 ──
    await nav('browse');
    await page.locator('.segment[data-source="curseforge"]').click();
    await waitForText('#browse-results', 'API 키가 필요합니다');
    await shot('browse-curseforge-no-key');
    await page.locator('#browse-go-settings').click();
    await page.locator('#view-settings.active').waitFor();

    // ── 설정 ──
    await page.locator('#setting-memory').evaluate((slider) => {
      slider.value = '8';
      slider.dispatchEvent(new Event('input', { bubbles: true }));
      slider.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await waitForText('#setting-memory-value', '8 GB');
    assert.equal(await page.locator('#setting-resolution-width').isDisabled(), true);
    await page.locator('#setting-resolution-enabled').check();
    await page.waitForFunction(() => !document.querySelector('#setting-resolution-width').disabled);
    await page.locator('#setting-resolution-width').fill('1920');
    await page.locator('#setting-resolution-width').blur();
    await page.locator('#setting-resolution-height').fill('99999');
    await page.locator('#setting-resolution-height').blur();
    await page.waitForFunction(() => document.querySelector('#setting-resolution-height').value === '4320');
    await page.locator('#setting-resolution-height').fill('1080');
    await page.locator('#setting-resolution-height').blur();
    await page.locator('#setting-jvm-args').fill('-Dcafeteria.test=1');
    await page.locator('#setting-jvm-args').blur();
    await page.waitForFunction(() => document.querySelector('#setting-resolution-height').value === '1080');
    await page.waitForTimeout(300);

    let saved = readJson(settingsFile);
    assert.equal(saved.memoryGb, 8);
    assert.equal(saved.resolutionEnabled, true);
    assert.equal(saved.resolutionWidth, 1920);
    assert.equal(saved.resolutionHeight, 1080);
    assert.equal(saved.jvmArgs, '-Dcafeteria.test=1');

    await page.locator('#curseforge-key').fill('e2e-dummy-key');
    await page.locator('#curseforge-form button[type="submit"]').click();
    await waitForText('#curseforge-key-state', '저장됨');
    assert.equal(await page.locator('#curseforge-key').inputValue(), '', '저장한 키는 입력칸에 남기지 않는다');
    await toastGone();
    await shot('settings');
    await page.locator('#view-settings').evaluate((view) => view.parentElement.scrollTo(0, view.scrollHeight));
    await shot('settings-bottom');

    // 키가 생기면 CurseForge 에 실제로 요청한다. (가짜 키라서 서버가 거부하거나, 접속이 막힌 환경이면 연결 오류가 나온다.)
    await nav('browse');
    await page.locator('.segment[data-source="curseforge"]').click();
    await page.locator('#browse-results .empty', { hasText: /API 키가 올바르지 않거나|서버에 연결하지 못했습니다|서버가 요청을 거부/ }).waitFor({ timeout: 60_000 });
    console.log(`  CurseForge(가짜 키) 응답: ${(await text('#browse-results .empty')).replace(/\s+/g, ' ')}`);
    await shot('browse-curseforge-dummy-key');

    // ── 바꾼 설정이 다음 플레이 때 프로필에 반영된다 ──
    fs.rmSync(launchedMarker);
    await nav('home');
    await page.locator('#play-button').click();
    await waitForText('#play-message', '공식 Minecraft 런처를 열었습니다.');
    const updated = cafeteriaProfile();
    assert.match(updated.javaArgs, /^-Xmx8G .* -Dcafeteria\.test=1$/);
    assert.deepEqual(updated.resolution, { width: 1920, height: 1080 });
    assert.equal(updated.created, profile.created);

    // ── 관리: 설치 복구, 프로필 제거, 설정 초기화 ──
    await nav('settings');
    const versionJson = path.join(minecraftDir, 'versions', 'fabric-loader-0.20.1-1.21.4', 'fabric-loader-0.20.1-1.21.4.json');
    fs.writeFileSync(versionJson, '손상된 파일');
    await page.locator('#setting-repair').click();
    await waitForText('.toast.success', '설치를 복구했습니다.');
    assert.equal(readJson(versionJson).id, 'fabric-loader-0.20.1-1.21.4');
    await toastGone();

    await page.locator('#setting-remove-profile').click();
    await waitForText('.toast.success', 'Cafeteria 프로필을 제거했습니다.');
    assert.equal(cafeteriaProfile(), undefined);
    assert.ok(fs.existsSync(path.join(modsDir, 'cafeteria-client-1.0.0+1.21.4.jar')), '게임 폴더는 남아 있어야 한다');
    await toastGone();

    await page.locator('#setting-reset').click();
    await waitForText('.toast.success', '설정을 기본값으로 되돌렸습니다.');
    await waitForText('#setting-memory-value', '4 GB');
    saved = readJson(settingsFile);
    assert.equal(saved.memoryGb, 4);
    assert.equal(saved.jvmArgs, '');
    assert.ok(saved.curseforgeKey, 'CurseForge 키는 유지된다');

    await nav('home');
    await waitForText('#install-status', '설치 전');

    assert.deepEqual(consoleErrors.filter((message) => !/Failed to load resource/.test(message)), [], '화면 스크립트 오류가 없어야 한다');

    // ── 창 닫기 버튼 ──
    const closed = app.waitForEvent('close');
    await page.locator('#window-close').click();
    await closed;
    console.log(`E2E 통과: 스크린샷 ${step}장 → ${outputDir}`);
  } catch (error) {
    await page.screenshot({ path: path.join(outputDir, 'failure.png') }).catch(() => {});
    await app.close().catch(() => {});
    throw error;
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
