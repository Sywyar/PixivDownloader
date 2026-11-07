# 주 저장소 개발

이 페이지는 주 프로그램과 공식 외부 플러그인의 개발 환경, 모듈 경계, 빌드와 테스트, 개발 모드 디버깅, 로컬 패키징과 PR 규칙을 설명합니다. 독립 서드파티 플러그인 개발은 [서드파티 플러그인 SDK](/ko/plugin-development), 플러그인 설치와 문제 해결은 [플러그인 관리](/ko/plugin-management)를 참고하세요.

## 환경 요구 사항

호스트, SDK와 공식 플러그인은 JDK 25를 사용하며 Java/Kotlin 컴파일 대상도 25로 통일합니다. 서드파티 플러그인 개발에도 JDK 25가 필요합니다. Temurin 25로 ProGuard 빌드를 실행하려면 JDK의 전체 버전과 일치하는 JMOD를 내려받아 `jmods/`에 배치해야 합니다. CI는 `actions/setup-java`의 `java-package: jdk+jmods`로 두 구성 요소를 준비하고 체크섬과 서명을 검증합니다.

프레임워크 기준은 Spring Boot 4.1.2, Spring Framework 7, Jackson 3, SDK 2.0.0입니다. 플러그인을 업그레이드하기 전에 [SDK 이전](/ko/plugin-development)을 완료하세요. SDK 1.x 바이너리는 이 호스트에 직접 불러올 수 없습니다.

| 도구 | 버전 요구 사항 | 용도 |
| --- | --- | --- |
| JDK | 25 | Java 모듈 컴파일 및 실행. 호스트 CI 테스트는 25 사용 |
| Maven | 3.9+ 또는 저장소에 포함된 `mvnw` | Reactor 빌드 및 테스트 |
| Git | 현재 지원되는 버전 | 버전 관리 및 브랜치 관리 |
| Node.js / npm | 현재 CI와 동일 | 프런트엔드 테스트, i18n 검사와 경계 검사 |
| PowerShell | 5.1+ | Windows 로컬 패키징 스크립트 |
| Inno Setup | 6.x(선택 사항) | Windows `setup.exe` 설치 패키지 빌드 |

모든 Java 명령에 UTF-8을 명시해야 합니다. Windows PowerShell에서는 다음과 같이 설정할 수 있습니다.

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

### 여러 JDK가 설치된 환경에서 선택

루트 POM은 Maven의 `validate` 단계에서 JDK `[25,26)`을 요구합니다. 현재 Maven JVM이 25이면 그대로 사용합니다. 그렇지 않으면 `~/.m2/toolchains.xml`의 일치하는 등록을 확인한 뒤 Maven의 로컬 자동 탐색을 시도합니다. 컴파일, 테스트, Maven 개발 실행, AOT와 Maven이 호출하는 Gradle / ProGuard가 같은 선택을 사용합니다. 25를 찾지 못하면 빌드가 실패하며 JDK를 다운로드하거나 17 또는 26으로 대체하지 않습니다.

사용자 지정 설치 경로는 한 번 등록하면 모든 clone에서 사용할 수 있습니다. 아래 `jdkHome`을 기존 JDK 25의 절대 경로로 바꾸고 `~/.m2/toolchains.xml`에 저장하세요. 파일이 이미 있으면 다른 등록을 보존하고 `toolchain` 요소만 추가하세요. 자동 탐색은 `JAVA25_HOME` 등의 환경 변수와 일반적인 설치 위치를 지원하지만 임의의 디스크 전체를 검색하지는 않습니다. 이전 JDK의 현지화된 출력 때문에 탐색이 실패할 때도 명시적으로 등록하세요.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0">
  <toolchain>
    <type>jdk</type>
    <provides><version>25</version></provides>
    <configuration><jdkHome>C:/path/to/jdk-25</jdkHome></configuration>
  </toolchain>
</toolchains>
```

Maven 자체를 시작하는 JVM은 여전히 터미널의 `JAVA_HOME` 또는 IDE Maven Runner가 선택합니다. IDE의 기본 `Developer Mode` 실행 및 디버깅은 프로젝트 SDK를 사용하므로 IDE에서 설치된 25를 선택해야 합니다. AOT 스크립트를 직접 호출할 때는 `--java-home`이 `JAVA_HOME`보다 우선합니다. Compose의 Java/Kotlin 도구 체인은 25를 요구하며 JDK 자동 다운로드를 사용하지 않습니다.

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

플러그인은 `plugin-api`에 의존하며 필요에 따라 `core-api`를 사용합니다. `pixivdownload-app`이나 호스트 내부 구현 클래스에 역방향으로 의존해서는 안 됩니다. `douyin` 플러그인 소스는 [독립 저장소](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)에서 관리하며 전용 테스트는 해당 저장소의 `Host compatibility` CI에서만 고정된 호스트 커밋을 대상으로 실행합니다. 주 저장소 PR은 범용 SDK 검증을 유지합니다.

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

## 선택 사항: JDK 25 AOT Cache

저장소의 `scripts/aot-cache.mjs`는 최종 boot JAR의 JVM 시작 캐시를 만들고 사용합니다. Node.js와 설치된 JDK 25가 필요합니다. JDK를 내려받거나 Spring AOT를 활성화하지 않습니다. 설치 프로그램은 기존 시작 방식을 유지합니다. CI 산출물 검증에서는 실제 캐시 학습과 strict 시작 검사를 수행합니다. 신뢰할 수 있는 JAR만 학습시키세요. 학습 과정에서 애플리케이션을 실행하지만 격리된 임시 설정을 사용하므로 현재 인스턴스의 사용자 데이터를 읽지 않습니다.

별도로 패키징할 필요 없이 공유 설정 `AOT Prepare`와 `AOT Packaged Run`을 순서대로 실행하세요. IntelliJ IDEA는 `.run`, VS Code는 같은 이름의 작업, Eclipse는 `eclipse/`의 launch 설정을 사용합니다. Prepare는 Maven Toolchains가 선택한 JDK 25로 본체와 공식 플러그인을 빌드하고 별도 실행 디렉터리를 구성한 다음 캐시를 준비합니다. Windows에서는 시스템 PowerShell을 사용하며, Linux / macOS에서는 PATH에 PowerShell 7(`pwsh`)이 있어야 합니다. 해당 명령은 다음과 같습니다.

```powershell
.\mvnw.cmd -N -Paot '-Daot.command=prepare' validate
if ($LASTEXITCODE -ne 0) { throw 'AOT preparation failed' }
.\mvnw.cmd -N -Paot validate
```

기본 디렉터리는 `target/aot-runtime`이며 `-Daot.runtime.directory=...`로 다른 경로를 지정할 수 있습니다. `artifacts/`에는 본체와 공식 배포 플러그인 전체(선택 플러그인 포함, 복구 테스트용 프로브 제외), `cache/`에는 JAR와 JDK 내용별 캐시, `instance/`는 애플리케이션 작업 디렉터리이며 독립적인 설정과 데이터를 저장합니다. `mvn clean`은 `target/` 아래의 기본 실행 디렉터리도 삭제합니다. 데이터를 보존하려면 `target/` 밖의 디렉터리를 지정하고 Prepare와 Run에 같은 값을 사용하세요. 처음 Run을 실행하면 일반 초기 설정을 진행합니다. 저장소 루트의 기존 인스턴스를 읽거나 덮어쓰지 않습니다. 코드 변경 후 Prepare를 다시 실행하면 성공했을 때만 새 산출물로 전환하며 설정과 데이터는 유지합니다. 동일한 산출물은 검증 후 재사용하고, 본체와 JDK가 그대로라면 플러그인만 변경되어도 본체 캐시를 재사용합니다. 단일 인스턴스 잠금은 이 실행 디렉터리의 `instance/ipc/`에 저장되므로 다른 개발용 또는 설치된 인스턴스와 분리됩니다. 동시에 실행하려면 서버 포트도 서로 다르게 설정해야 합니다.

Prepare 빌드는 단위 테스트를 건너뛰며 테스트와 CI 검증은 별도로 실행합니다. 플러그인은 기존의 서명되지 않은 로컬 업로드 정책에 따라 구성하므로 로컬 개발 전용이며 공식 서명 배포물로 배포할 수 없습니다. Run은 다시 빌드하지 않습니다. 같은 디렉터리에서 준비 또는 실행이 진행 중이면 동시 작업을 거부합니다. 실패 시 이전 실행 가능 기록을 보존하며 손상된 내용은 재사용하지 않습니다. 새 실행 디렉터리를 지정해 준비할 수 있습니다. 이전 산출물과 캐시는 자동 삭제되지 않아 디스크 공간을 사용합니다. 관련 프로세스가 종료된 후 불필요한 내용은 삭제할 수 있지만 필요한 `instance/` 데이터는 보존하세요.

`AOT Prepare`와 `AOT Packaged Run`은 일반 Run으로 실행할 수 있습니다. IDE Debug로 시작해도 AOT 자식 프로세스의 환경에서 JDWP와 IDEA 디버거 에이전트 옵션만 제거하며, 힙 크기, 프록시, 인증서 등 다른 옵션은 유지합니다. 애플리케이션 중단점 디버깅에는 `Developer Mode`를 사용하세요. Windows에서 Prepare 또는 실행기를 종료하면 해당 작업이 시작한 빌드와 학습 자식 프로세스도 종료되고 잠금이 해제됩니다. 다른 애플리케이션 인스턴스에는 영향을 주지 않습니다. 잠금이 사용 중이면 같은 실행 디렉터리를 사용하는 Prepare 또는 Run 작업을 종료한 뒤 다시 시도하세요. `.lock` 파일은 삭제하지 마세요. 그 밖의 잠금 오류는 출력에 표시된 원인을 확인하세요.

소스 실행과 중단점 디버깅에는 `Developer Mode`를 사용하세요. JDK 25 AOT Cache는 비어 있지 않은 클래스 디렉터리에서 만들 수 없고 현재 연결 캐시는 JDWP 디버깅과 함께 사용할 수 없습니다. 단위 테스트, JaCoCo, Maven과 Gradle 빌드 프로세스는 애플리케이션 AOT 캐시를 사용하지 않습니다. 학습 비용은 실제 반복 실행 횟수를 기준으로 평가하세요.

아래 저수준 CLI는 기존 JAR를 처리하며 CI에서도 직접 사용합니다. 빌드나 플러그인 배치를 수행하지 않습니다. 저장소 루트에서 실행하고 `<version>`, JDK 경로, 초기 설정을 마친 인스턴스 디렉터리를 바꾸세요. 새 캐시는 아직 없는 디렉터리에 만들며 유효한 기존 캐시는 검증 후 재사용할 수 있습니다. 실행에는 선택한 인스턴스의 설정, 플러그인과 데이터를 사용합니다. 플러그인이 없는 코어 JAR는 복구 모드만 제공합니다.

```powershell
$repo = (Get-Location).Path
$jar = (Resolve-Path 'pixivdownload-app/target/PixivDownload-<version>-boot.jar').Path
$jdk = 'C:/path/to/jdk-25'
$cache = Join-Path $repo 'target/aot-cache'
node "$repo/scripts/aot-cache.mjs" prepare --jar $jar --java-home $jdk --cache-dir $cache
if ($LASTEXITCODE -ne 0) { throw 'AOT preparation failed' }

Set-Location 'C:/path/to/initialized-instance'
node "$repo/scripts/aot-cache.mjs" run --jar $jar --java-home $jdk --cache-dir $cache -- --no-gui
if ($LASTEXITCODE -ne 0) { throw 'AOT application failed' }
```

저수준 도구는 새 캐시를 준비하면 `AOT prepare: ready`, 유효한 캐시를 재사용하면 `AOT prepare: reusing verified cache`를 출력합니다. 시작 전에 원본 JAR, 압축을 푼 의존성, JDK와 캐시의 SHA-256을 확인합니다. 손상되거나 불완전한 캐시는 보존하며 `--cache-dir`로 새 디렉터리를 지정합니다. 임시 학습 데이터는 종료 후 삭제합니다. 매니페스트는 로컬 일관성 검사이며 배포 서명이나 플러그인 허용 검증을 대신하지 않습니다.

일반 실행은 현재 환경을 유지하며 캐시가 호환되지 않으면 JVM이 캐시 없이 실행합니다. 캐시 사용을 검증하려면 `run`의 `--` 앞에 `--strict`를 추가하고 JVM의 `Using AOT-linked classes: true` 진단을 확인하세요. 정상 종료만으로 캐시 사용을 입증할 수는 없습니다. 학습 비용을 반복 실행의 시간 절감으로 회수할 수 있는지, 같은 JAR, JDK, 플러그인, 데이터와 시작 완료 조건으로 측정하세요. 동작 원리는 [Spring Boot AOT Cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html)를 참고하세요.

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
