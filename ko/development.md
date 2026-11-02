# 개발 가이드

주 프로그램이나 공식 플러그인을 수정할 때 환경 준비·빌드·PR 절차를 안내합니다. 타사 작성자는 [플러그인 SDK](/ko/plugin-development), 설치·복구는 [플러그인 관리](/ko/plugin-management)를 참고하세요.

## 환경

| 도구 | 요구 사항 |
| --- | --- |
| JDK | 17 |
| Maven | 3.9+ 또는 저장소 Maven Wrapper |
| Git | 현재 지원 버전 |
| Node.js / npm | 현재 CI와 같은 버전 |
| PowerShell | Windows 패키징에 5.1+ |
| Inno Setup | Windows 설치판 빌드에 선택적으로 6.x |

Java는 UTF-8을 명시합니다. Windows PowerShell에서는 다음과 같이 설정할 수 있습니다.

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

## 모듈과 의존성

루트 `pom.xml`은 Maven Reactor 집계입니다.

| 디렉터리 | 책임 |
| --- | --- |
| `pixivdownload-plugin-api/` | 안정 플러그인 확장 계약 |
| `pixivdownload-core-api/` | 안정 호스트 의미 포트와 값 모델 |
| `pixivdownload-plugin-signature/` | 패키지·매니페스트 서명과 검증 |
| `pixivdownload-plugin-runtime/` | PF4J, Spring 자식 컨텍스트, 설치 수명 주기 |
| `pixivdownload-plugin-*/` | 각 공식 외부 플러그인 |
| `pixivdownload-app/` | 호스트, 어댑터, 실행 가능한 Boot JAR |
| `pixivdownload-official-plugins/` | 공식 플러그인 집계와 개발 진입점 |
| `plugin-templates/` | 타사 플러그인 템플릿 |

플러그인은 plugin-api와 필요한 안정 core-api에 의존합니다. 타사는 앱 artifact나 호스트 구현 클래스에 의존하면 안 됩니다.

Douyin은 [독립 저장소](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)에서 소스·리소스·테스트를 관리하며 주 Reactor에 포함되지 않습니다. SDK 호환성 검사는 고정된 Douyin 커밋을 대상 SDK로 빌드한 뒤 플러그인 간 상호 작용과 서명·미서명 패키지의 설치·수명 주기를 검증합니다.

## Fork와 브랜치

GitHub에서 Fork한 뒤 `YOUR_USERNAME`과 작업 브랜치를 실제 값으로 바꾸세요.

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/your-change upstream/master
```

PR 전에 `upstream/master`에 맞게 갱신하고 상위 저장소의 `master`로 PR을 보냅니다.

## 빌드와 테스트

저장소 루트 PowerShell에서 필요한 명령을 선택합니다.

```powershell
.\mvnw.cmd package -DskipTests
.\mvnw.cmd test
.\mvnw.cmd -pl pixivdownload-plugin-api -am test
npm run test:js
npm run test:web-standards
```

차례로 테스트 생략 패키징, 전체 Maven 테스트, 해당 모듈과 Reactor 의존성 테스트, 전체 JavaScript, 웹 표준 검사입니다. Maven의 `BUILD SUCCESS`와 프로세스 종료 코드를 확인하세요. 테스트 생략 빌드는 테스트 통과를 뜻하지 않습니다.

패키징한 앱 JAR을 직접 실행할 때는 정확히 하나인지 확인합니다.

```powershell
$appJars = @(Get-ChildItem -LiteralPath pixivdownload-app/target -Filter 'PixivDownload-*-boot.jar')
if ($appJars.Count -ne 1) { throw 'Expected exactly one application JAR' }
java -Dfile.encoding=UTF-8 -jar $appJars[0].FullName
```

핵심 Boot JAR만 실행해도 외부 플러그인이 설치되지는 않습니다. 전체 개발은 아래 개발 진입점을, 배포는 완전한 배포판을 사용하세요. `-am`과 `-Dtest=...`를 함께 쓰는 집중 테스트에는 `-Dsurefire.failIfNoSpecifiedTests=false`를 넣어 해당 테스트가 없는 의존 모듈의 오탐 실패를 피합니다.

## 공식 플러그인 개발 모드

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

`dev-mode`를 명시적으로 활성화하면 현재 개발 빌드의 소스 출력과 `plugins/`의 검증된 패키지를 함께 로드합니다. 양쪽을 합쳐 의존성을 검사하며, 같은 플러그인 ID가 여러 위치에 있으면 충돌하는 사본을 모두 로드하지 않고 복구 화면에 출처와 처리 방법을 표시합니다. 소스를 디버깅할 때는 같은 이름의 설치 패키지를 옮기고, 패키지를 사용할 때는 해당 소스 모듈을 개발 빌드에서 제외한 뒤 다시 시작하세요.

IDE는 IntelliJ의 `.run/Developer Mode.run.xml`, VS Code의 `.vscode/launch.json`, Eclipse의 `eclipse/Developer Mode.launch`를 사용합니다. 필요한 Reactor 모듈을 컴파일한 뒤 `GuiLauncher`를 시작합니다.

필수 플러그인 누락 복구는 `Missing Required Plugin` 공유 구성이나 다음 명령을 사용합니다.

```powershell
mvn -pl pixivdownload-app -am -Precovery-mode process-classes -Dexec.skip=true
```

## 스크립트와 i18n

다운로드 페이지는 `/api/scripts`로 이미 생성된 스크립트 목록을 읽습니다. 독립 `*.user.js`와 `scripts/build-userscript-bundle.ps1`의 묶음은 Maven `generate-resources`에서 download-workbench 리소스로 복사합니다. 변경 뒤 Maven 수명 주기를 한 번 이상 실행하고 오래된 IDE 출력만 사용하지 마세요. 공유 스니펫과 원본으로 동기화·생성하며 생성 All-in-One을 직접 편집하지 않습니다.

언어 목록은 `pixivdownload-app/src/main/resources/i18n/locales.json`입니다. 개발 소스는 `zh-CN`, 전역 대체 언어는 `en-US`입니다.

```bash
npm run setup:hooks
npm run doctor:hooks
npm run i18n:check
npm run i18n:generate-static
npm run test:i18n
```

중국어 새 문구에는 영어도 함께 작성합니다. 번체 중국어·일본어·한국어와 언어 상태·대체 규칙도 유지해야 합니다. 생성 시 앱뿐 아니라 각 리소스 owner의 실제 결과를 확인하세요. 현재 명령과 검사는 `package.json`, `scripts/i18n/`, CI가 기준입니다.

## Windows 로컬 패키징

`scripts/package-local.ps1`은 앱, 공식 플러그인 입력, 온라인·오프라인 portable, 선택적 Inno Setup 설치판을 만듭니다. 배포용 공식 플러그인은 재검증 가능한 서명이 있어야 합니다. `.sig`를 포함한 `-PrebuiltPluginsDir` 또는 로컬 모듈용 `-OfficialKeyId`, 저장소 밖 `-PrivateKeyFile`, 선택적 `-SignatureToolJar`를 사용합니다. 기본 출력은 `build/out/`입니다.

아래 경로는 실제 파일로 바꾸세요. 각 명령은 서로 다른 목적입니다.

```powershell
# 격리된 미서명 로컬 테스트 설치판
.\scripts\package-installer-with-plugins.ps1 -Version 0.0.1-local -PluginSource Local -AllowUnsignedLocalPlugins

# 플러그인이 없는 복구·개발용 portable
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipPlugins -SkipInstaller

# 서명과 재검증을 마친 입력으로 빌드
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -PrebuiltPluginsDir C:\path\to\signed-plugin-inputs -SignatureToolJar C:\path\to\signature-tool.jar
```

주요 옵션은 `-PrebuiltJar`, `-PrebuiltPluginsDir`, `-SkipPlugins`, `-RunTests`, `-SkipPortable`, `-SkipOfflinePortable`, `-SkipInstaller`, `-RedownloadFfmpeg`입니다. `-SkipPlugins` 결과는 복구용입니다. 미서명 설치판은 `build/out-local-unsigned/`에 격리하며 배포하면 안 됩니다. 개인 키를 저장소·출력·로그에 넣지 마세요.

## PR과 CI

변경 위험에 맞는 집중 검사를 먼저 실행하고 영향받는 모듈·전체 검사로 확장합니다.

```bash
git diff --check
git diff --staged
```

PR에는 문제, 변경 범위, 실제 검증 명령과 결과를 쓰세요. UI는 스크린샷, 패키징은 검사한 artifact를 설명합니다. `target/`, `build/`, 실행 설정, 자격 증명, 다운로드 데이터는 제출하지 않습니다.

품질 검사는 Java, JavaScript, i18n, 의존성, 배포 경계를 검사합니다. `v*` 태그는 공식 플러그인, 앱, Java 배포판, Windows 설치판과 Release를 실행합니다. `workflow_dispatch`는 품질 검사 후 지정 태그의 초안 Release를 만드는 데 사용합니다. 문서 사이트는 독립 `gh-pages`이며 앱 정적 리소스의 태그 미리보기가 아닙니다. 실제 발행 전에 현재 `.github/workflows/`와 `scripts/`를 확인하세요.

## 코드 경계

공개 문구는 i18n을, 웹은 기존 다크 모드·CSS 변수·HTML/CSS/JavaScript 분리를 따릅니다. HTTP API는 명시적 DTO와 기존 인증·오류 매핑을 사용합니다. 테이블 변경은 관리 스키마·이전 테스트를 함께 갱신합니다. 플러그인 전용 설정·자격 증명·상태·데이터·의존성은 해당 owner에 유지합니다. 확장 계약은 먼저 plugin-api/core-api와 검사를 바꾸고 템플릿·예시·SDK 문서를 맞추세요.
