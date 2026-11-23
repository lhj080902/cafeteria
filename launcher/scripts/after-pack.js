'use strict';

/*
 * electron-builder 의 afterPack 훅: Windows 실행 파일에 아이콘과 버전 정보를 넣는다.
 * electron-builder 는 이 작업에 Windows 전용 도구(rcedit)를 쓰기 때문에 Linux/macOS 에서 빌드하면 Wine 이 필요한데,
 * 순수 자바스크립트로 구현된 resedit 를 대신 써서 어느 OS에서 빌드해도 같은 결과가 나오게 한다.
 */
const fs = require('node:fs');
const path = require('node:path');

exports.default = async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') {
    return;
  }

  const ResEdit = await import('resedit');
  const info = context.packager.appInfo;
  const { productFilename } = info;
  const exePath = path.join(context.appOutDir, `${productFilename}.exe`);
  const executable = ResEdit.NtExecutable.from(fs.readFileSync(exePath), { ignoreCert: true });
  const resources = ResEdit.NtExecutableResource.from(executable);

  // 아이콘 교체: 기존 아이콘 그룹(Electron 기본 아이콘)의 ID 를 그대로 써서 덮어쓴다.
  const iconFile = ResEdit.Data.IconFile.from(fs.readFileSync(path.join(__dirname, '..', 'resources', 'icon.ico')));
  const iconGroups = ResEdit.Resource.IconGroupEntry.fromEntries(resources.entries);
  const groupId = iconGroups.length > 0 ? iconGroups[0].id : 1;
  const language = iconGroups.length > 0 ? iconGroups[0].lang : 1033;
  ResEdit.Resource.IconGroupEntry.replaceIconsForResource(
    resources.entries,
    groupId,
    language,
    iconFile.icons.map((icon) => icon.data),
  );

  // 파일 속성(자세히 탭)에 보이는 이름과 버전
  const versions = ResEdit.Resource.VersionInfo.fromEntries(resources.entries);

  if (versions.length > 0) {
    const versionInfo = versions[0];
    const [major = 0, minor = 0, patch = 0] = info.version.split('.').map((part) => Number.parseInt(part, 10) || 0);
    versionInfo.setFileVersion(major, minor, patch, 0, language);
    versionInfo.setProductVersion(major, minor, patch, 0, language);
    versionInfo.setStringValues(
      { lang: language, codepage: 1200 },
      {
        ProductName: info.productName,
        FileDescription: info.productName,
        CompanyName: info.companyName || info.productName,
        LegalCopyright: `Copyright © ${new Date().getFullYear()} ${info.companyName || info.productName}`,
        OriginalFilename: `${productFilename}.exe`,
        InternalName: productFilename,
      },
    );
    versionInfo.outputToResourceEntries(resources.entries);
  }

  resources.outputResource(executable);
  fs.writeFileSync(exePath, Buffer.from(executable.generate()));
  console.log(`  • 실행 파일 아이콘·버전 정보 적용: ${path.basename(exePath)}`);
};
