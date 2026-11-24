'use strict';

/** 런처가 설치하는 버전 구성. 모드(mod/gradle.properties)와 같은 버전으로 맞춰 둔다. */
const MINECRAFT_VERSION = '1.21.4';
const LOADER_VERSION = '0.20.1';
const FABRIC_API_VERSION = '0.119.4+1.21.4';

module.exports = {
  MINECRAFT_VERSION,
  LOADER_VERSION,
  FABRIC_API_VERSION,

  /** 공식 런처의 versions 폴더에 들어가는 이름. Fabric 공식 설치기와 같은 규칙을 쓴다. */
  VERSION_ID: `fabric-loader-${LOADER_VERSION}-${MINECRAFT_VERSION}`,
  FABRIC_PROFILE_URL: `https://meta.fabricmc.net/v2/versions/loader/${MINECRAFT_VERSION}/${LOADER_VERSION}/profile/json`,

  FABRIC_API: {
    fileName: `fabric-api-${FABRIC_API_VERSION}.jar`,
    url: `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API_VERSION}/fabric-api-${FABRIC_API_VERSION}.jar`,
    sha1: '1c7871b6af04edc8b8f0dbad12606d67f6118a11',
    sha512: '16b679f6a42549ca3b6bc147de710b2c8ba2d5e4661ed88df22782c05a4a5f6c1163d7b13867687ed91acde0fdfe5a5037d05662431c946865b58396284e7ee7',
    modrinthProjectId: 'P7dR8mSH',
    curseforgeProjectId: '306612',
  },

  /** 공식 런처의 launcher_profiles.json 안에서 쓰는 프로필 키와 표시 이름. */
  PROFILE_KEY: 'cafeteria-client',
  PROFILE_NAME: 'Cafeteria',

  /** 런처가 직접 관리해서 사용자가 끄거나 지울 수 없는 모드의 fabric.mod.json id. */
  BUILTIN_MOD_IDS: ['cafeteria', 'fabric-api'],
  CAFETERIA_MOD_ID: 'cafeteria',
  CAFETERIA_MOD_FILE_PREFIX: 'cafeteria-client-',

  USER_AGENT: 'CafeteriaLauncher/1.2.2',

  /** 새 버전 설치 파일이 올라오는 곳 (자동 업데이트를 지원하지 않는 실행 방식에서 안내하는 페이지). */
  RELEASES_URL: 'https://github.com/lhj080902/cafeteria/releases/latest',

  /** 다운로드를 허용하는 호스트. 검색 결과에 다른 주소가 들어 있어도 여기 없는 곳에서는 받지 않는다. */
  DOWNLOAD_HOSTS: ['cdn.modrinth.com', 'edge.forgecdn.net', 'mediafilez.forgecdn.net', 'maven.fabricmc.net', 'piston-meta.mojang.com', 'piston-data.mojang.com', 'libraries.minecraft.net', 'resources.download.minecraft.net'],

  /** 바로 실행에 쓰는 Minecraft 버전 정보. 주소가 내용의 sha1 로 정해지므로 내용이 바뀌지 않는다. */
  MINECRAFT_VERSION_JSON: {
    url: 'https://piston-meta.mojang.com/v1/packages/c88775961e316f2be09859c54e13017660ee7770/1.21.4.json',
    sha1: 'c88775961e316f2be09859c54e13017660ee7770',
  },

  /** 게임을 실행할 Java (Mojang 이 배포하는 런타임, Windows x64). 1.21.4 는 java-runtime-delta(Java 21)를 요구한다. */
  JAVA_RUNTIME: {
    component: 'java-runtime-delta',
    version: '21.0.7',
    manifestUrl: 'https://piston-meta.mojang.com/v1/packages/cb4394a27089d19f65d5baa6cf0482c27c3c7865/manifest.json',
    sha1: 'cb4394a27089d19f65d5baa6cf0482c27c3c7865',
  },

  /**
   * Fabric 로더 실행 구성. FABRIC_PROFILE_URL 의 내용을 옮겨 고정한 것이다 (그 JSON 은 고정할 해시가 없다).
   * LOADER_VERSION 을 바꾸면 여기도 같이 바꾼다. constants.test.js 가 서버 내용과 비교한다.
   */
  FABRIC: {
    mainClass: 'net.fabricmc.loader.impl.launch.knot.KnotClient',
    jvmArgs: ['-XX:+UnlockDiagnosticVMOptions', '-XX:-UseBASE64Intrinsics', '-DFabricMcEmu= net.minecraft.client.main.Main '],
    mavenUrl: 'https://maven.fabricmc.net/',
    libraries: [
      { name: 'org.ow2.asm:asm:9.10.1', sha1: 'ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236', size: 126151 },
      { name: 'org.ow2.asm:asm-analysis:9.10.1', sha1: '8d49f14d51f632cb1d87c88d1ceaf50db0d8af1b', size: 35140 },
      { name: 'org.ow2.asm:asm-commons:9.10.1', sha1: '4229e4c55fd8e01c23f9fe9884075cc628aacc50', size: 74840 },
      { name: 'org.ow2.asm:asm-tree:9.10.1', sha1: 'e244332a17564c1d1572449399a842de35881be2', size: 51958 },
      { name: 'org.ow2.asm:asm-util:9.10.1', sha1: '7bb9d450e8d4cbf9f9e04096c44bbfe7fba80b15', size: 95628 },
      { name: 'net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7', sha1: '0cef8519a91129d0c21fa03bb9378f0e8ca810e5', size: 1551593 },
      { name: 'net.fabricmc:intermediary:1.21.4', sha1: 'de610a8c6216662541bf345ba07ab8d099e1ec25', size: 701826 },
      { name: 'net.fabricmc:fabric-loader:0.20.1', sha1: '7c7f9831e9f27b303d5d08decb5f4258a9f03296', size: 1988880 },
    ],
  },

  /** Microsoft 로그인. 마인크래프트 런처의 공개 클라이언트 ID 를 쓴다 (msmc 라이브러리의 기본값과 같다). */
  MSA: {
    clientId: '00000000402b5328',
    redirectUri: 'https://login.live.com/oauth20_desktop.srf',
    scope: 'XboxLive.signin offline_access',
  },

  /** 런처에서 기본 브라우저로 열 수 있는 사이트. */
  EXTERNAL_HOSTS: ['modrinth.com', 'www.curseforge.com', 'support.curseforge.com', 'console.curseforge.com', 'www.minecraft.net', 'github.com'],
};
