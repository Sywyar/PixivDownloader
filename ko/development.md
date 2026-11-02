# 주 저장소 개발

이 페이지는 주 프로그램과 공식 외부 플러그인의 개발 환경, 모듈 경계, 빌드와 테스트, 개발 모드 디버깅, 로컬 패키징과 PR 규칙을 설명합니다. 독립 서드파티 플러그인 개발은 [서드파티 플러그인 SDK](/ko/plugin-development), 플러그인 설치와 문제 해결은 [플러그인 관리](/ko/plugin-management)를 참고하세요.

## 환경 요구 사항

| 도구 | 버전 요구 사항 | 용도 |
| --- | --- | --- |
| JDK | 17 | Java 모듈 컴파일 및 실행 |
| Maven | 3.9+ 또는 저장소에 포함된 `mvnw` | Reactor 빌드 및 테스트 |
| Git | 현재 지원되는 버전 | 버전 관리 및 브랜치 관리 |
| Node.js / npm | 현재 CI와 동일 | 프런트엔드 테스트, i18n 검사와 경계 검사 |
| PowerShell | 5.1+ | Windows 로컬 패키징 스크립트 |
| Inno Setup | 6.x(선택 사항) | Windows `setup.exe` 설치 패키지 빌드 |

모든 Java 명령에 UTF-8을 명시해야 합니다. Windows PowerShell에서는 다음과 같이 설정할 수 있습니다.

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

## 다중 모듈 구조 및 의존성 방향

루트의 `pom.xml`은 Maven Reactor 집계 프로젝트입니다.

| 모듈 또는 폴더 | 책임 |
| --- | --- |
| `pixivdownload-sdk-info/`, `pixivdownload-plugin-api/`, `pixivdownload-core-api/`, `pixivdownload-sdk/` | SDK 버전의 사실 원본, 플러그인 확장 계약, 호스트의 안정적 의미 인터페이스와 통합 컴파일 진입점 |
| `pixivdownload-plugin-signature/` | 플러그인 패키지, 저장소 매니페스트와 커뮤니티 작업의 Ed25519 서명 및 검증 도구 |
| `pixivdownload-plugin-runtime/` | PF4J 로딩, Spring 자식 컨텍스트, worker 프로세스와 설치 트랜잭션 수명 주기 |
| `pixivdownload-plugin-*/` | 각 공식 외부 플러그인 모듈 |
| `pixivdownload-app/` | 애플리케이션 호스트, 어댑터와 실행 가능한 Spring Boot JAR |
| `pixivdownload-official-plugins/` | 공식 외부 플러그인 개발 집계 및 `dev-mode` 시작 진입점 |
| `plugin-templates/` | 복사하여 사용할 수 있는 독립 서드파티 플러그인 프로젝트 템플릿 |

플러그인은 `plugin-api`에 의존하며 필요에 따라 `core-api`를 사용합니다. `pixivdownload-app`이나 호스트 내부 구현 클래스에 역방향으로 의존해서는 안 됩니다. `douyin` 플러그인 소스는 [독립 저장소](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)에서 관리하며 고정 커밋으로 SDK 격리 소비자 호환성 검증에 참여합니다.

## Fork, 빌드 및 테스트

저장소를 Fork한 후 `upstream/master`를 기준으로 기능 브랜치를 만듭니다. `YOUR_USERNAME`과 브랜치 이름은 바꾸세요.

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/your-change upstream/master
```

자주 사용하는 빌드 및 검증 명령:

```powershell
# 테스트를 건너뛰고 모든 모듈 패키징
.\mvnw.cmd package -DskipTests

# 전체 Maven 테스트 실행
.\mvnw.cmd test

# 단일 모듈과 Reactor 상위 의존성 테스트(-Dtest=...와 함께 쓰면 -Dsurefire.failIfNoSpecifiedTests=false 추가)
.\mvnw.cmd -pl pixivdownload-plugin-api -am test

# 프런트엔드 테스트 및 Web 표준 검사 실행
npm run test:js
npm run test:web-standards
```

빌드 결과는 `BUILD SUCCESS`와 종료 코드 `0`으로 판단합니다. 테스트를 건너뛴 패키징은 테스트 통과를 뜻하지 않습니다. `pixivdownload-app/target/PixivDownload-*-boot.jar`만 실행하면 외부 플러그인이 없는 코어 셸만 시작하므로 전체 기능을 함께 디버깅하려면 아래 개발 모드를 사용하세요.

## 공식 외부 플러그인 개발 모드 및 복구 모드

저장소 루트에서 `dev-mode` profile을 명시하여 전체 개발 환경을 시작합니다. IDE의 Maven 패널에서 이 profile을 전역으로 선택하지 마세요.

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

`dev-mode`는 각 공식 플러그인 모듈의 `target/classes`와 `plugins/`의 서명이 검증된 설치 패키지를 모두 찾습니다. 같은 플러그인 ID가 양쪽에 있으면 `plugin-conflict`로 로드를 거부하고 복구 페이지에서 충돌 사본을 제거한 후 재시작하라고 안내합니다. IDE 디버깅은 저장소에 포함된 공유 설정을 직접 사용하세요. IntelliJ IDEA의 `.run/Developer Mode.run.xml`, VS Code의 `.vscode/launch.json`, Eclipse의 `eclipse/Developer Mode.launch`입니다.

필수 설치 플러그인 부재나 복구 모드를 검증하려면 `Missing Required Plugin` 공유 설정 또는 다음 명령을 사용합니다.

```powershell
mvn -pl pixivdownload-app -am -Precovery-mode process-classes -Dexec.skip=true
```

## 사용자 스크립트 동기화 및 i18n 작업 흐름

- **사용자 스크립트**: 루트의 `*.user.js`와 `scripts/build-userscript-bundle.ps1`이 생성한 All-in-One은 Maven `generate-resources` 단계에서 `download-workbench` 리소스 폴더로 복사됩니다. 공유 코드 조각이나 스크립트를 수정하면 `./scripts/sync-shared-snippets.ps1`, `./scripts/build-userscript-bundle.ps1`을 실행하고 Maven 빌드를 한 번 수행하세요.
- **i18n 국제화**: 유일한 언어 카탈로그는 `pixivdownload-app/src/main/resources/i18n/locales.json`입니다. 소스 언어는 `zh-CN`, 전역 대체 언어는 `en-US`이며 `zh-Hant`, `ja-JP`, `ko-KR`를 포함합니다. 문구를 추가하거나 수정한 후 순서대로 실행하세요.

```bash
npm run setup:hooks
npm run doctor:hooks
npm run i18n:check
npm run i18n:generate-static
npm run test:i18n
```

## 로컬 Windows 패키징

- **로컬 무서명 설치 프로그램 검증**: 현재 소스로 플러그인을 빌드하고 격리된 `build/out-local-unsigned/`에 출력합니다. 외부 배포는 금지합니다.

  ```powershell
  .\scripts\package-installer-with-plugins.ps1 -Version 0.0.1-local -PluginSource Local -AllowUnsignedLocalPlugins
  ```

- **플러그인 없는 코어 셸 포터블 패키지**: 복구 모드/개발 검증 전용입니다.

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipPlugins -SkipInstaller
  ```

- **서명된 플러그인 입력으로 정식 패키지 빌드**: `build/out/`에 출력합니다. 개인 키를 저장소, 빌드 폴더나 로그에 넣지 마세요.

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -PrebuiltPluginsDir C:\path\to\signed-plugin-inputs -SignatureToolJar C:\path\to\signature-tool.jar
  ```

## 커밋 및 PR 규칙

1. 변경 위험에 맞춰 집중 테스트, 모듈 회귀 테스트와 프런트엔드/i18n 검사를 실행합니다. 커밋 전에 `git diff --check`와 `git diff --staged`를 확인하고 `target/`, `build/`, 실행 설정, 자격 증명이나 테스트 데이터를 커밋하지 마세요.
2. 사용자에게 표시되는 문자열은 i18n을 사용하고 실행 로그는 영어로 고정합니다. HTTP API는 명시적 DTO를 사용하고 기존 인증 및 예외 매핑을 재사용합니다. 플러그인 전용 설정/자격 증명/데이터는 해당 플러그인에 귀속시킵니다.
3. 브랜치를 최신 `upstream/master`와 동기화한 후 `master`를 대상으로 PR을 만듭니다. 설명에는 변경 이유, 범위와 실제 실행한 검증 명령 및 결과를 적으세요. 온라인 문서는 별도의 `gh-pages` 브랜치에서 관리합니다.
