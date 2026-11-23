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

  USER_AGENT: 'CafeteriaLauncher/1.1.0',

  /** 새 버전 설치 파일이 올라오는 곳 (자동 업데이트를 지원하지 않는 실행 방식에서 안내하는 페이지). */
  RELEASES_URL: 'https://github.com/lhj080902/cafeteria-client/releases/latest',

  /** 다운로드를 허용하는 호스트. 검색 결과에 다른 주소가 들어 있어도 여기 없는 곳에서는 받지 않는다. */
  DOWNLOAD_HOSTS: ['cdn.modrinth.com', 'edge.forgecdn.net', 'mediafilez.forgecdn.net', 'maven.fabricmc.net'],

  /** 런처에서 기본 브라우저로 열 수 있는 사이트. */
  EXTERNAL_HOSTS: ['modrinth.com', 'www.curseforge.com', 'support.curseforge.com', 'console.curseforge.com', 'www.minecraft.net', 'github.com'],
};
