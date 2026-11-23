# Cafeteria 인수인계 (2026-11-23 기준)

Claude Code 클라우드 세션에서 만든 작업을 로컬(Claude Code CLI)로 넘기기 위한 문서다.
코드 규칙과 명령어는 저장소 루트의 `CLAUDE.md`, 사용자용 설명은 `README.md` 에 있다. 여기에는 **지금 상태, 남은 일, 왜 이렇게 만들었는지**만 적는다.

## 1. 지금 상태

- 저장소: https://github.com/lhj080902/cafeteria (`main`, 공개)
- 최신 버전: **1.2.2** (모드와 런처가 같은 번호를 쓴다)
- 릴리스: [v1.2.2](https://github.com/lhj080902/cafeteria/releases/tag/v1.2.2) – `Cafeteria-Setup-1.2.2.exe`, `.blockmap`, `latest.yml` 세 파일이 올라가 있고, 빌드 결과와 같은 파일인지 확인했다.
- 대상: Minecraft 1.21.4, Fabric Loader 0.20.1, Fabric API 0.119.4+1.21.4, Windows. (버전은 `mod/gradle.properties` 와 `launcher/src/main/constants.js` 에서 맞춘다)

### 사용자가 처음에 정한 것

- 루나 클라이언트처럼 PvP 기능만 기본으로 넣고, 나머지는 사용자가 모드를 직접 추가한다.
- 런처 이름은 **카페테리아(Cafeteria)**. Minecraft **1.21.4 하나만**, Fabric, Windows.
- 로그인과 게임 실행은 **공식 Minecraft 런처에 맡긴다** (런처는 Fabric 프로필과 모드를 설치하고 공식 런처를 연다). 이유는 4번 참고.
- CurseForge API 키는 런처에 넣지 않고 사용자가 설정에 직접 입력한다.
- 결과물은 설치 파일(Setup.exe)로 받는다.

## 2. 들어 있는 기능

### 모드 (`mod/`)

| 묶음 | 모듈 id (기본 상태) |
| --- | --- |
| 기능 | `freelook`(켜짐), `zoom`(켜짐), `hitbox`(켜짐), `fullbright`(꺼짐), `low_fire`(꺼짐), `no_tutorial`(켜짐), `menu_background`(켜짐), `chat_time`(켜짐), `infinite_chat`(켜짐), `screenshot`(켜짐) |
| HUD | `fps`, `cps`, `ping`, `keystrokes`, `armor`, `potions`(켜짐) / `coordinates`, `combo`, `reach`(꺼짐) |

기본 키: 모드 설정 = 오른쪽 Shift, 프리룩 = 왼쪽 Alt, 줌 = C, 풀브라이트 = G.

코드 길잡이 (`mod/src/main/java/io/github/lhj080902/cafeteria/`):

| 파일 | 역할 |
| --- | --- |
| `CafeteriaClient.java` | 진입점. 키 등록, 틱 처리, HUD 레이어 등록, 클라이언트 명령어 |
| `Modules.java` | 모든 모듈의 정적 목록. 새 모듈은 여기에 추가 |
| `KeyState.java` | 키 입력을 직접 센다. 바닐라는 키 하나에 키 설정 하나만 연결해서, 다른 모드와 키가 겹치면 한쪽이 눌림을 못 받는다 (Xaero 월드맵도 오른쪽 Shift 를 쓴다) |
| `module/`, `module/setting/` | 모듈과 설정 종류(Boolean, Number, Mode, Color) |
| `hud/` | HUD 요소. 위치는 화면 여유 공간에 대한 비율로 저장 |
| `gui/` | 모드 설정 화면, 세부 설정 화면, HUD 위치 편집 화면 |
| `mixin/` | 바닐라에 끼워 넣는 부분. 무엇을 왜 건드리는지 각 파일 주석에 적혀 있다 |
| `util/ScreenshotClipboard.java` | 스크린샷을 클립보드에 그림으로 넣기 |

### 런처 (`launcher/`)

홈(플레이), 내 모드(추가·켜기/끄기·삭제·업데이트), 모드 찾기(Modrinth, CurseForge), 설정 가져오기(루나·기본 마인크래프트), 설정, 런처 자동 업데이트.

| 파일 (`src/main/`) | 역할 |
| --- | --- |
| `main.js`, `preload.js` | Electron 메인 프로세스와 화면에 노출하는 함수 |
| `installer.js` | Fabric 버전 정보·기본 모드 설치, 공식 런처 프로필 등록 |
| `official-launcher.js` | 공식 런처 찾아서 실행 |
| `mods.js` | mods 폴더 관리, 출처 기록(`cafeteria-mods.json`), 같은 모드 교체 |
| `mod-installer.js`, `mod-updater.js` | 모드 설치(필수 의존성 포함), 새 버전 확인·교체 |
| `sources/modrinth.js`, `sources/curseforge.js` | 모드 사이트 API |
| `importer.js` | 다른 클라이언트의 설정 가져오기 |
| `updater.js` | 런처 자동 업데이트 상태 처리 (electron-updater) |
| `settings.js`, `http.js`, `paths.js`, `constants.js` | 설정 저장, 다운로드(호스트 허용 목록·해시 검증), 경로, 버전 상수 |

## 3. 검증한 것과 못 한 것

모든 검증은 **Linux 컨테이너**에서 했다. Windows 에서는 한 번도 실행해 보지 못했다.

| 대상 | 결과 |
| --- | --- |
| 모드 게임 테스트 (개발 환경, 배포용 jar) | 통과 |
| 모드 + Xaero's Minimap 26.5.0 + World Map 1.46.0 + FastPack 0.3.1 | 통과 |
| 런처 단위 테스트 106개 (Modrinth 실제 서버 포함) | 통과 |
| 런처 화면 테스트(E2E): 소스 실행, 패키징된 Linux 실행 파일 | 1.2.0 에서 통과. 그 뒤로는 모드와 버전 번호만 바뀌어 다시 돌리지 않았다 |
| 설치 파일: 내용물, NSIS 무결성(CRC), `latest.yml` 해시 | 통과 |
| **Windows 에서 설치·실행, 공식 런처 자동 실행** | 미검증 |
| **런처 자동 업데이트가 실제로 내려받아지는지** | 미검증. 다음 버전 릴리스를 올린 뒤에 확인할 수 있다 |
| **스크린샷 복사 (Windows)** | 미검증. Linux(X11)에서만 확인. PowerShell 예비 경로는 실행해 본 적 없음 |
| **CurseForge 실제 서버** | 미검증. API 키가 없어 가짜 응답으로만 검사 |
| **루나 클라이언트의 실제 폴더 구조** | 미검증. 가짜 폴더로만 검사 |
| **Sodium·Iris 등 성능 모드와의 호환** | 미검증 |
| **Windows·macOS 에서 `npm run build:win`** | 미검증. Linux 에서만 빌드했다 |
| **E2E 테스트를 Windows 에서 실행** | 미검증. 가짜 공식 런처가 셸 스크립트라서 그대로는 안 될 가능성이 높다 |

## 4. 남은 일

### 사용자의 결정을 기다리는 것

1. **Microsoft 로그인 + 게임 직접 실행** (공식 런처를 거치지 않기)
   - 사용자가 제안했고 아직 결정하지 않았다. "지금 만들기"와 "승인 절차부터 안내" 중에서 고르기로 했다.
   - 걸림돌: 서드파티 런처가 Minecraft 토큰을 받으려면 Azure 앱을 등록하고 Minecraft API 사용 허가를 신청해야 한다. 허가 전에는 `api.minecraftservices.com/authentication/login_with_xbox` 가 403(`Invalid app registration`)을 돌려준다. 개인 개발자가 허가받는 기준은 확인하지 못했다. ([Minecraft Wiki](https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Microsoft_Authentication_Scheme), [Microsoft Q&A](https://learn.microsoft.com/en-us/answers/questions/5768276/how-to-get-xboxlive-signin-permission-for-azure-ap))
   - 사용자가 "토큰 방식은 안 되나"라고 물었고 안 된다고 답했다: 런처가 자체 발급한 토큰은 정품 서버에서 통하지 않고, Mojang 토큰을 붙여 넣게 하는 방식은 하루면 만료되며 계정 탈취 수법과 같은 형태다. 비정품(오프라인) 로그인도 넣지 않는다.
   - 만든다면 필요한 것: Microsoft 기기 코드 로그인 → Xbox Live → XSTS → Minecraft 토큰 → 프로필, 게임 파일·Java 런타임 내려받기, 실행 인수 조립, 계정 화면. 앱 ID 는 CurseForge 키처럼 설정에서 입력받는다.
2. **포션 HUD 의 "기본 아이콘 숨김" 기본값**: 지금은 꺼짐. 켜짐으로 바꿀지 물어봤고 답을 받지 못했다.
3. **릴리스 자동화 (GitHub Actions)**: 태그를 올리면 빌드해서 릴리스를 만드는 워크플로를 제안했고 답을 받지 못했다.

### 확인이 필요한 것

- 3번 표의 미검증 항목들. 특히 Windows 에서 설치 → 플레이 → 게임 안 기능을 한 번 돌려 보는 것.
- 다음 버전을 낼 때 1.2.2 런처가 스스로 업데이트하는지 확인. (1.2.2 이전 버전은 업데이트 주소가 옛 저장소 이름 `cafeteria-client` 로 돼 있어서 자동으로 올라오지 않는다)

### 이 저장소 밖의 일: FastPack

- FastPack 은 사용자의 다른 저장소 `lhj080902/jamsuham` 의 `tools/fastpack-mod` 에 있는 모드다 (서버 리소스팩을 재접속 때 다시 적용하지 않게 함).
- 0.3.0 의 문제 두 개를 찾아 0.3.1 로 고쳤다. 고친 jar 와 패치 파일(`fastpack-0.3.1.patch`)을 사용자에게 전달했고, **jamsuham 저장소에는 아직 반영하지 않았다.**
  1. 연결이 맺어지기 전에 `connection.getRemoteAddress()` 를 읽어 null 이 나오면 같은 서버를 "다른 서버"로 판정해 팩을 다시 받았다 → 접속 화면에서 누른 주소(`host:port`)를 서버 키로 쓰도록 수정.
  2. 싱글플레이 월드에 들어갈 때 남겨 둔 서버 팩이 그대로 적용됐다 → 월드 로드 전에 정리하도록 수정. (`doWorldLoad` 안에서 리소스를 다시 읽으면 멈추므로, 로드를 취소하고 정리가 끝난 뒤에 다시 호출한다)
- 이런 모드의 호환성은 `mod/tools/pack_server.py` 와 `ServerResourcePackTest` 로 확인한다 (README 의 개발 항목 참고).

## 5. 알아 두면 좋은 결정과 함정

- **줌과 손**: 줌은 월드를 그리는 시야각만 바꾼다(`GameRendererMixin` 이 `renderLevel` 안의 `getFov` 호출 결과만 수정). 손은 확대되지 않는다. 사용자가 "줌하면 팔도 같이 줌된다"고 한 것은 손이 커진다는 뜻이 아니라 **줌한 화면에 손이 남아 시야를 가린다**는 뜻이었다. 그래서 줌하는 동안 손을 화면 아래로 내리는 것을 기본으로 했다(`zoom_hand_hidden`).
- **메뉴 배경**: 바닐라는 메뉴 배경을 25% 만 어둡게 하고 흐림 효과를 건다. 사용자 환경에서 ESC 메뉴 배경이 까맣게 나왔는데, 원인은 재현하지 못했다(흐림 효과가 깨지는 것으로 추정). `menu_background` 모듈이 흐림을 건너뛰고 어둡기를 직접 정한다.
- **HUD 는 채팅보다 앞에** 그린다 (`IdentifiedLayer.CHAT` 뒤에 레이어를 붙임). 사용자 요청이다.
- **스크린샷 복사**: 게임이 `java.awt.headless=true` 로 켜 두는 것을 모드 초기화 때 false 로 바꿔서 AWT 클립보드를 쓴다. macOS 는 지원하지 않는다.
- **설정 id 를 일부러 바꾼 적이 있다**: `zoom_hide_hand`(기본 꺼짐) → `zoom_hand_hidden`(기본 켜짐). 이미 저장된 "꺼짐"이 남지 않게 하려는 것이었다.
- **모드 업데이트는 자동으로 바꾸지 않는다.** 확인만 자동이고 교체는 버튼을 누를 때만 한다. 새 파일을 받아 해시를 검증한 뒤에 예전 파일을 지운다.
- **같은 모드 id 의 jar 를 추가하면 예전 파일을 지운다.** 사이트에 없는 모드(FastPack 등)의 업데이트 방법이다. Cafeteria 모드 자체는 런처가 관리하므로 따로 넣지 못하게 막았다.
- **설치 파일은 Wine 없이 만든다.** `launcher/scripts/build-win.js` 가 electron-builder 안의 `UninstallerReader` 를 쓰도록 바꾸고, 빌드 뒤에 NSIS 무결성 검사를 직접 해 본다.
- **서명되지 않은 실행 파일**이라 Windows 가 처음 실행할 때 경고를 띄운다.
- **클라우드 세션의 제약**(로컬 CLI 에는 없음): 릴리스를 만들 수 없었고, 사용자가 요청하지 않은 외부 모드(Sodium·Iris)를 받아 실행하는 것은 권한 단계에서 거부됐다.

## 6. 버전 기록

| 버전 | 내용 |
| --- | --- |
| 1.0.0 | 모드(프리룩, 줌, 히트박스, FPS·CPS·핑·키스트로크 HUD, 설정 화면), 런처(설치, 모드 관리, Modrinth·CurseForge 검색·설치, 설정) |
| 1.1.0 | 풀브라이트 단축키, HUD 편집(맞춤·방향키·휠), 좌표·갑옷·포션·콤보·리치 HUD, 낮은 화염, 튜토리얼 끄기 / 런처: 직접 넣은 모드 아이콘, 설정 가져오기, 자동 업데이트 |
| 1.2.0 | 깨끗한 메뉴 배경, HUD 박스 선택, 채팅 시간, 무한 채팅, 스크린샷 [복사] 버튼, HUD 를 채팅 앞에 / 런처: 모드 업데이트 확인·교체 |
| 1.2.1 | 포션 효과 HUD 에 효과 아이콘, 바닐라 효과 아이콘 숨기기 옵션 |
| 1.2.2 | 줌하는 동안 손을 화면 아래로 내림 (기본 켜짐) |

## 7. 로컬에서 시작하기

필요한 것: Git, JDK 21, Node.js 22 이상. 릴리스까지 하려면 GitHub CLI(`gh`).

```bash
git clone https://github.com/lhj080902/cafeteria
```

```bash
cd cafeteria
```

```bash
claude
```

처음 빌드할 때 Gradle 이 Minecraft 와 라이브러리를 내려받으므로 시간이 걸린다. Windows 에서는 게임 테스트가 실제 게임 창을 띄운다.
