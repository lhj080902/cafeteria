# Cafeteria (카페테리아)

Minecraft **1.21.4 (Fabric)** 용 PvP 클라이언트 모드와 Windows 런처. 사용자는 한국어로 대화하며, 코드 주석·화면 문구·커밋 메시지도 한국어로 쓴다.

현재 상태, 남은 일, 지금까지의 결정은 **`docs/HANDOFF.md`** 에 정리돼 있다. 작업을 이어받을 때 먼저 읽을 것.
사용자용 설명(기능, 사용법, 검증 현황)은 `README.md`.

## 구성

| 폴더 | 내용 |
| --- | --- |
| `mod/` | Cafeteria Client – Fabric 모드 (Java 21, Mojang 공식 매핑, Mixin + MixinExtras) |
| `launcher/` | Cafeteria 런처 – Electron. Microsoft 로그인, 게임 파일 받기와 게임 바로 실행(Windows x64, 공식 Minecraft 런처 불필요), 모드 관리. 설정에서 공식 런처에 프로필을 등록하고 여는 예전 방식도 고를 수 있음 |
| `branding/`, `tools/` | 아이콘과 아이콘 생성 스크립트 |
| `docs/` | 인수인계 문서, README 용 스크린샷 |

## 명령어

모드 (`mod/` 에서, Java 21 필요. Windows 는 `gradlew.bat`):

```bash
./gradlew build                        # build/libs/cafeteria-client-<버전>+1.21.4.jar
./gradlew runClientGameTest            # 실제 게임을 띄워서 모듈 동작 검사 (개발 환경)
./gradlew runProductionClientGameTest  # 배포용 jar 를 정식 MC + Fabric Loader 에 올려 같은 검사
```

- 화면이 없는 Linux 에서는 `xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 ./gradlew ...` 로 감싼다.
- 다른 모드와 함께 검사: `-PextraMods=/경로/a.jar,/경로/b.jar` (`runProductionClientGameTest` 전용).
- Gradle 이 결과를 캐시하므로 다시 돌릴 때는 `--rerun` 을 붙인다.
- 게임 테스트가 중간에 실패하면 실행 폴더에 그때의 설정이 남아서 다음 실행이 엉뚱한 곳에서 실패할 수 있다. 다시 돌리기 전에 `mod/build/run/<실행 폴더>/config/cafeteria.json` 을 지운다.
- 스크린샷: `mod/build/run/{clientGameTest,productionClientGameTest}/screenshots/`
- GitHub Actions(`.github/workflows/gametest.yml`)가 `mod/` 가 바뀐 push 마다 두 게임 테스트를 돌린다. 개발 PC 에서 게임 창을 띄울 수 없을 때(사용자가 게임 중일 때)는 작업 브랜치를 push 해서 확인한다: `gh run watch <번호>`, 스크린샷과 로그는 `gh run download <번호>`.

런처 (`launcher/` 에서, Node.js 22 이상):

```bash
npm install
npm start               # 모드 jar 를 복사한 뒤 런처 실행 (먼저 mod 를 빌드해 둘 것)
npm test                # 단위 테스트. Modrinth·Fabric 실제 서버 검사 포함 (CAFETERIA_OFFLINE=1 이면 건너뜀)
node test/e2e/run.js    # 런처를 띄워 전체 흐름 검사 (Playwright 필요, Linux 에서만 확인됨)
npm run build:win       # dist/ 에 Setup.exe, zip, latest.yml, .blockmap
```

## 지켜야 할 것

- **전달 전에 실제로 돌려서 확인한다.** 사용자는 "오류 점검 후 작동까지 확인하고 전달"을 원한다. 모드를 고쳤으면 게임 테스트를, 런처를 고쳤으면 단위 테스트(화면이 바뀌면 E2E 도)를 돌린다. 확인하지 못한 것(특히 Windows 실제 동작)은 못 했다고 분명히 말한다.
- **새 기능에는 게임 테스트를 같이 넣는다.** `mod/src/gametest/.../CafeteriaClientGameTest.java` 는 스크린샷 픽셀까지 재서 검사한다. 배포 환경에서는 클래스·필드 이름이 난독화돼 있으니 리플렉션은 이름이 아니라 형식으로 찾는다.
- **믹스인**: 핵심 기능(줌, 프리룩, 히트박스, 키 입력)은 필수, 부가 기능(채팅, 메뉴 배경, 스크린샷, 손 숨기기, 포션 아이콘)은 `require = 0` 으로 둬서 다른 모드와 겹쳐도 게임이 꺼지지 않게 한다. `@Redirect`·`@ModifyConstant` 대신 MixinExtras(`@WrapOperation`, `@ModifyExpressionValue` 등)를 쓴다.
- **설정 id 를 바꾸면 저장된 값이 버려진다** (`config/cafeteria.json` 은 id 로 읽는다). 기본값을 모든 사용자에게 새로 적용하려는 경우가 아니면 id 를 바꾸지 않는다.
- **번역 키**는 `ko_kr.json` 과 `en_us.json` 에 항상 같이 넣는다. 모듈은 `cafeteria.module.<id>`(+`.desc`), 설정은 `cafeteria.setting.<id>`, 선택지는 `cafeteria.mode.<값>`.
- **런처 보안 규칙**: 화면(renderer)은 `preload.js` 에 노출한 함수만 쓴다. 다운로드는 `constants.js` 의 `DOWNLOAD_HOSTS` 에 있는 곳에서만, 해시 검증 후에 받는다. CurseForge API 키 값은 화면으로 넘기지 않는다. 사용자에게 비밀 값을 채팅에 붙여 넣으라고 하지 않는다.
- **로그인·게임 실행 보안 규칙**: 토큰(`access_token`, `refresh_token`, Xbox 토큰)은 메인 프로세스 밖으로 나가지 않는다 (화면, 로그, 오류 `details` 모두). 실행 인수 전체는 기록하지 않는다 (`--accessToken` 이 들어 있다). 게임 파일은 `constants.js` 에 고정한 해시에서 이어지는 sha1 로 검증한 것만 쓴다.
- **Minecraft·Fabric 로더·Java 버전을 올릴 때**는 `constants.js` 의 `MINECRAFT_VERSION_JSON`, `JAVA_RUNTIME`, `FABRIC`(Fabric 라이브러리 8개의 이름과 sha1 포함)을 같이 바꾸고 `constants.test.js` 로 확인한다.
- **버전을 올릴 때 고칠 곳**: `mod/gradle.properties`(`mod_version`), `launcher/package.json` 과 `package-lock.json`(맨 위 두 군데), `launcher/src/main/constants.js`(`USER_AGENT`), `launcher/test/unit/http.test.js`, `README.md` 의 파일 이름.
- **electron-builder 버전은 고정**돼 있다. `scripts/build-win.js` 가 내부 모듈을 건드려 Wine 없이 설치 파일을 만들기 때문이다. 올리려면 빌드 스크립트를 함께 확인한다.
- **Loom 은 1.13.x** 를 쓴다. 더 새 Loom 은 Java 25 를 요구한다.

## 새 버전 내기

1. 위 "버전을 올릴 때 고칠 곳"을 모두 고친다.
2. `mod/` 에서 `./gradlew build`, 게임 테스트 통과 확인.
3. `launcher/` 에서 `npm test`, `npm run build:win`.
4. GitHub 저장소 `lhj080902/cafeteria` 에 `v<버전>` 태그로 릴리스를 만들고 `launcher/dist/` 의 세 파일을 **이름 그대로** 올린다:
   `Cafeteria-Setup-<버전>.exe`, `Cafeteria-Setup-<버전>.exe.blockmap`, `latest.yml`

```bash
gh release create v1.2.3 --repo lhj080902/cafeteria --title "Cafeteria 1.2.3" --notes "..." launcher/dist/Cafeteria-Setup-1.2.3.exe launcher/dist/Cafeteria-Setup-1.2.3.exe.blockmap launcher/dist/latest.yml
```

설치된 런처는 이 릴리스의 `latest.yml` 을 보고 자동으로 업데이트한다. 세 파일 중 하나라도 빠지거나 이름이 다르면 업데이트가 되지 않는다.
