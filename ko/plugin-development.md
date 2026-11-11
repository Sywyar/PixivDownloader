# 서드파티 플러그인 SDK

서드파티 플러그인은 SDK 개발 패키지의 독립 프로젝트 템플릿에서 시작합니다. JDK 25, Node.js 및 패키지에 포함된 빌드 도구와 실행 매니페스트만 있으면 되며 주 저장소를 복제할 필요가 없습니다.

SDK 2.x는 JDK 25, Spring Framework 7, Servlet 6.1, Jackson 3를 사용하며 Spring Boot 4 호스트에 대응합니다. SDK 1.x 플러그인은 설치 또는 시작 검사에서 거부됩니다. `plugin.requires`만 변경해서는 업그레이드할 수 없으며 새 인터페이스에 맞게 수정한 뒤 다시 컴파일해야 합니다.

SDK 1.x에서 이전할 때 `PluginDataSource`를 `PluginDatabase`로, SQL 문자열을 `TableSpec.check`의 중립 표현식으로, `DesktopUiHost.resolveDatabasePath`를 데이터베이스 대상 계약으로 바꾸세요. 리소스 bundle에는 `LocaleBundlePolicy`를 명시해야 합니다. 취득 자격 증명은 `X-Acquisition-Credential`을 사용하고 예약 작업 자격 증명의 연결/해제는 공통 `POST` / `DELETE /api/schedule/tasks/{id}/credential`을 사용합니다. 해당 API의 활성화 토큰 및 권한 요구 사항도 충족해야 합니다. Jackson 데이터 바인딩 클래스는 `tools.jackson.*`로 이동하지만 어노테이션은 `com.fasterxml.jackson.annotation`을 유지합니다. Spring 헤더를 Map으로 다룰 때는 `HttpHeaders.asMultiValueMap()`을 사용하세요.

`ScheduleTaskView`에서 `type`, `lastStatus`, `lastMessage`, `runStartedTime`, `pendingRetryArmed`가 제거되었습니다. 소스는 `sourceType`, 실행 상태는 `runState`, 결과는 `lastOutcome`, 일시 중단 상태는 `suspendReason` / `suspendCode`에서 읽으세요. 결과 메시지는 `outcomeCode` / `outcomeMessage`, 최근 실행 시각은 `lastRunTime`을 사용합니다.

| 개발 작업 | 관련 절 |
| --- | --- |
| 첫 플러그인 생성 및 로컬 디버깅 | [템플릿으로 시작](#템플릿으로-시작) · [로컬 개발](#로컬-개발) |
| 새 다운로드 작품 유형 또는 독립 갤러리 추가 | [새 다운로드 유형 추가 전체 흐름](#새-다운로드-유형-추가-전체-흐름) · [플러그인 자체 갤러리 추가](#플러그인-자체-갤러리-추가) |
| 설정, 암호화된 자격 증명, 파일과 전용 데이터베이스 관리 | [설정, 자격 증명 및 파일](#설정-자격-증명-및-파일) · [GUI에 의존하지 않는 자격 증명 및 일반 작업 검증](#gui에-의존하지-않는-자격-증명-및-일반-작업-검증) |
| 플러그인 간 서비스 계약 또는 페이지 편집기 컴포넌트 선언 | [독립 서비스 계약 및 조합](#독립-서비스-계약-및-조합) · [일반 슬롯 및 페이지 컴포넌트](#일반-슬롯-및-페이지-컴포넌트) |
| SDK 업그레이드, 커뮤니티 마켓 제출 또는 자체 저장소 배포 | [외부 프로젝트 유지 관리 및 SDK 업그레이드](#외부-프로젝트-유지-관리-및-sdk-업그레이드) · [마켓 자료 제출](#마켓-자료-제출) · [서명 및 배포](#서명-및-배포) |

아래 `SDK_VERSION`, 패키지 이름, 플러그인 ID, 경로, SHA-256과 서명은 모두 자리표시자이므로 실제 값으로 바꾸세요. 코드 조각은 해당 템플릿의 문맥에 넣고 import와 업무 구현을 보완해야 합니다. 인터페이스 지원 여부는 선택한 [SDK Release](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases)와 [버전별 Javadoc](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)을 기준으로 합니다.

참고 소스: [플러그인 템플릿](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates) · [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info) · [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api) · [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api) · [서명 도구](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature) · [Douyin 전체 예제 플러그인](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin). Douyin은 다운로드, 설정, 프록시, 대기열, 예약 작업, 전용 DB와 독립 갤러리를 보여 주지만 공식 배포 모음에는 포함되지 않습니다. 새 프로젝트는 `plugin-templates`에서 시작하세요.

## 플러그인 라이선스 및 배포 채널

PixivDownloader와 공식 플러그인은 [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)을 사용하며 [플러그인 링크 예외](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)가 동봉된 버전에는 추가 허가를 제공합니다. 공개 SDK / API만으로 개발하고 예외 조건을 충족하는 독립 서드파티 플러그인은 MIT 등의 라이선스를 선택할 수 있습니다. 프로젝트 템플릿을 복사하거나 수정할 때는 원본 저작권 고지와 예외 텍스트를 보존해야 합니다. 호스트, SDK와 공식 플러그인에는 계속 AGPL-3.0이 적용됩니다.

[공식 커뮤니티 플러그인 저장소](https://github.com/Sywyar/PixivDownloader-community-plugins)는 오픈 소스 플러그인만 등록합니다. 완전한 GitHub 소스와 오픈 소스 라이선스 공개, 재현 가능한 빌드와 수동 심사 통과가 필요합니다.

## 신뢰 및 SDK 아키텍처 경계

- **실행 모드 및 신뢰 경계**: `declarative-process`는 별도 worker JVM에서 리소스와 프로토콜을 격리하고 `host-process-full-trust`는 호스트 JVM에서 실행됩니다. 둘 다 현재 OS 계정을 공유하므로 운영체제 수준의 보안 샌드박스가 아닙니다. Ed25519 서명은 게시자 신원과 바이트 무결성만 증명합니다. 설치 전에는 여전히 작성자와 소스를 신뢰해야 합니다([플러그인 관리](/ko/plugin-management) 참고).
- **컴파일 의존성 방향**: 서드파티 플러그인은 단일 `pixivdownload-sdk`(`provided`) 의존성을 선언합니다. 이를 통해 `pixivdownload-sdk-info`, `pixivdownload-plugin-api`, `pixivdownload-core-api` 및 호스트가 제공하는 PF4J, Spring, Servlet, Jackson 컴파일 의존성을 전달받습니다. 세 API 모듈과 BOM을 별도로 사용할 수도 있습니다. **`pixivdownload-app`, 호스트 내부 구현 클래스, 공식 플러그인 전용 클래스, 호스트 `DataSource` 또는 비공개 프런트엔드 전역 변수에 의존해서는 안 됩니다.**
- **기능 제공 및 부재 시 처리**: 플러그인은 `PixivFeaturePlugin`으로 기능을 제공하고 `plugin.properties` / `pixiv.configuration-classes`로 독립 Spring 자식 컨텍스트 설정 클래스를 선언합니다. 비활성화, 제거 또는 세대 교체 시 호스트는 publication별로 라우트, 정적 리소스, i18n, 탐색 항목, 다운로드 유형, 대기열과 예약 기능을 철회합니다. 호출자는 기능 부재를 처리해야 하며 null 포인터나 미완성 작업을 남겨서는 안 됩니다.
- **데스크톱 UI 경계**: 호스트는 `GuiConfigContribution` 등의 툴킷 독립 순수 데이터 계약으로 설정, 안내와 상태를 모읍니다. `gui-compose`(기본값)와 `gui-swing`(대체 UI)이 각각 렌더링하며 기능 플러그인은 Swing / Compose 컴포넌트를 반환하지 않습니다.

### `PixivFeaturePlugin` 주요 기여 진입점

| 메서드 | 제공 내용 |
| --- | --- |
| `id`, `displayName`, `description`, `displayNamespace`, `iconKey`, `colorToken`, `kind` | 플러그인 신원, i18n 표시 키와 제어되는 아이콘/색상/분류 token |
| `start`, `stop` | 플러그인 자체 로컬 리소스 수명 주기. `stop`은 멱등이어야 함 |
| `privateSchema`, `privateStorageMigrations` | 현재 owner의 기준 전용 테이블 구조 및 순차 데이터 마이그레이션 선언 |
| `schema`, `sharedMainStorage` | 호스트 주 DB 협업용 관리 테이블과 공개 저장 영역 신청. 다른 owner 테이블 접근 불가 |
| `routes`, `staticResources`, `i18n` | Web 라우트 접근 정책, classpath 정적 리소스 매핑, 프런트엔드 i18n namespace |
| `navigation`, `startupRoutes`, `landings`, `pageSections`, `uiSlots`, `drilldowns` | 탐색 항목, 시작 도착점, 페이지 영역, Web UI 슬롯과 상세 이동 링크 |
| `guiConfigContributions`, `guiOnboardingSteps`, `guiThemes` | 데스크톱 설정 필드/그룹/작업/프리셋, 안내 단계와 시작 시 테마 |
| `userscripts`, `scheduledSourceDescriptors`, `downloadTypes` | 사용자 스크립트 리소스, 예약 작업 소스 설명자와 다운로드 작업 화면의 작품 유형 설명자 |

## 템플릿으로 시작

| 템플릿 | 용도 | 포함 내용 |
| --- | --- | --- |
| `minimal-feature-plugin` | 선언적 페이지, 탐색 및 i18n | `declarative-process` worker, PF4J 진입점, route/static/i18n과 thin JAR 테스트. Spring 자식 컨텍스트 없음 |
| `download-type-plugin` | 새 다운로드 작품 유형과 갤러리 | `host-process-full-trust`, 다운로드 유형 설명자, 5가지 가져오기 모드, 대기열, 예약 소스, Vue 슬롯, 독립 갤러리와 프런트엔드/백엔드 테스트 |
| `typed-service-plugin` | 플러그인 간 타입 서비스 및 원격 호출 | 독립 `api` / `contract` / `provider` / `consumer` 다중 모듈 프로젝트와 Schema/클라이언트 생성 |

저장소 밖으로 복사한 템플릿은 주 저장소 parent를 상속하지 않는 독립 Maven 프로젝트입니다. `mvn clean verify`로 바로 검증할 수 있습니다. 주 저장소 안에서는 `mvn -f plugin-templates/pom.xml clean verify`를 사용할 수 있습니다.

Maven 의존성 선언은 다음과 같습니다. Java 컨트롤러가 이름을 명시하지 않은 `@PathVariable` / `@RequestParam`을 사용하면 `<maven.compiler.parameters>true</maven.compiler.parameters>` 또는 `-parameters`를 설정해야 합니다.

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>SDK_VERSION</version>
    <scope>provided</scope>
</dependency>
```

Gradle은 `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:SDK_VERSION")`, sbt는 `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "SDK_VERSION" % Provided`를 사용합니다. 템플릿을 복사한 후 Maven `artifactId`, 전역 고유 플러그인 ID, Java 패키지 이름과 클래스 접두사, 버전, 라우트 접두사, 정적 리소스 폴더, 전용 테이블 이름과 i18n namespace를 일관되게 바꾸세요.

## 플러그인 패키지 설명자 및 자식 컨텍스트 구성

JAR 루트에는 `plugin.properties`가 있어야 합니다.

```properties
plugin.id=example-download
plugin.version=0.1.0
plugin.requires=2.0
plugin.class=com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin
plugin.provider=Example Developer
plugin.description=Example download type.
pixiv.display-namespace=example-download
pixiv.display-name-key=plugin.name
pixiv.description-key=plugin.summary
pixiv.icon-key=download
pixiv.color-token=green
pixiv.execution-mode=host-process-full-trust
pixiv.configuration-classes=com.example.pixivdownload.downloadtype.ExampleDownloadConfiguration
pixiv.lifecycle-policy=process-restart
```

- `plugin.id`는 `PixivFeaturePlugin.id()`와 정확히 같아야 합니다. `plugin.requires`는 필요한 SDK 버전을 선언합니다. 안정 버전은 `requiredMajor == hostMajor && requiredMinor <= hostMinor`로 일치 여부를 판단합니다. 프리릴리스는 `alpha.N` / `beta.N` / `rc.N`을 사용하고 정확히 일치해야 합니다.
- `pixiv.execution-mode`는 필수이며 `declarative-process` 또는 `host-process-full-trust`입니다. `pixiv.lifecycle-policy`는 `hot-reload`(기본값), `backend-restart`, `process-restart`를 선택할 수 있습니다. `pixiv.risk-signals`에는 위험 신호 token을 선언할 수 있습니다.
- 외부 진입점은 `PixivPluginProvider`를 구현하고 유일한 `PixivFeaturePlugin`을 반환합니다. Spring 자식 컨텍스트의 명시적 `@Bean` 설정 클래스는 설명자의 `pixiv.configuration-classes`로 선언하며 호스트 패키지 스캔에 의존하면 안 됩니다.

```java
public final class ExampleDownloadPf4jPlugin
        extends org.pf4j.Plugin implements PixivPluginProvider {
    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new ExampleDownloadPlugin();
    }
}
```

### Web 라우트, 정적 리소스, i18n, 알림 템플릿 및 설문 슬롯

모든 Controller 경로, 정적 리소스와 HTML 페이지는 같은 플러그인이 `routes()`에서 `AccessPolicy`를 선언해야 합니다. `publicRoute`, `visitor`, `visitorAndInvitedGuest`, `invitedGuest`, `admin`, `local`, `gui`를 지원하며 선언하지 않은 `path + method`는 404를 반환합니다.

```java
@Override
public List<WebRouteContribution> routes() {
    return List.of(
            WebRouteContribution.admin("/example-download-gallery.html"),
            WebRouteContribution.admin("/example-download-gallery/**"),
            WebRouteContribution.admin("/api/example-download/gallery"));
}
```

- **업무 알림 템플릿**: 시나리오 owner가 자식 컨텍스트에 `NotificationTemplateContributor` Bean을 등록하고 순수 값 `NotificationTemplateContribution(scenarioId, medium, locale, titleTemplate, bodyTemplate)`을 반환합니다. 제목 하나당 최대 16 KiB, 본문 1 MiB, 플러그인당 최대 256개 / 8 MiB이며 신뢰하지 않는 값을 넣기 전에 HTML 이스케이프해야 합니다.
- **PostHog 설문 및 알림함**: Web 플러그인은 `plugin.dependencies=posthog?@1.0`을 선언하고 `/pixiv-posthog/pixiv-posthog.js`를 로드하여 `window.PixivPostHog.createSurveyClient`와 `captureSurveyWithAck`를 호출할 수 있습니다. 관리자 알림함에 계속 표시할 메시지가 필요하면 `new SurveyInboxMessage(...).toUiSlotContribution()`으로 `notification.inbox` 슬롯을 제공합니다.

## 새 다운로드 유형 추가 전체 흐름

다운로드 유형은 같은 플러그인이 함께 제공합니다.

1. **`DownloadTypeDescriptor` 선언**: `PixivFeaturePlugin.downloadTypes()`에서 descriptor를 반환합니다. `contractVersion = DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION`, 전역 고유 `type`, i18n 키, 아이콘과 색상 token, 동일 출처 동작 모듈 `moduleUrl`, 지원하는 가져오기 모드 `single-import` / `user` / `series` / `search` / `quick`, `cancelSupported`, 필터와 설정 허용 목록을 포함합니다.
2. **프런트엔드 동작 모듈 구현**: `moduleUrl`에서 `PixivBatch.queueTypes.registerModule(initializer)`를 호출합니다. 필수 `process(item, context)`를 구현하고 `context.updateItem(patch)`로 상태를 갱신합니다. `DownloadTasks`를 사용하면 `context.bindTask(taskId)`로 실행 UUID를 연결합니다. 선언한 `import`, `acquisition.*`, `filters`, `settings`도 구현합니다. 비동기 콜백이 값을 쓰기 전에 `AbortSignal` / `isActive()`를 확인하고 `onCleanup()`에서 리소스를 해제해야 합니다.
3. **백엔드에서 신뢰할 수 있는 신원 해석**: Controller에 부모 컨텍스트의 `RequestOwnerIdentityResolver`를 주입하여 현재 요청의 신원을 해석합니다. 요청 본문이나 인수가 스스로 제공한 owner UUID를 신뢰해서는 안 됩니다. 파일을 내구성 있게 저장하고 기록을 커밋한 후에만 완료로 표시합니다.
4. **`QueueOperations` 및 실제 Quiesce / Drain 구현**: `workKey`는 불투명 문자열이며 호스트가 `POST /api/download/queue/{queueType}/cancel`의 JSON 요청 본문으로 전달합니다. 비동기나 백그라운드 작업이 있는 대기열은 `prepareQuiesce`를 재정의하여 새 작업 수락을 중지하고 양의 세대를 가진 실제 `QueueDrain`을 반환해야 합니다. 진행 중인 작업이 0이 된 후 drain을 완료합니다.
5. **UI 슬롯 및 예약 작업 기능(선택 사항)**: `uiSlots()`로 `WebUiSlotContribution`을 제공합니다. 호스트 `PixivVue.mountUiSlot`을 사용하며 Vue runtime을 패키징하지 않습니다. `scheduledSourceDescriptors()`와 자식 컨텍스트의 `ScheduledSourceExecutor` / `ScheduledWorkExecutor` Bean으로 예약 작업을 지원합니다. 호스트는 스케줄링, 잠금, 자격 증명과 워터마크 커밋을 담당합니다.

## 플러그인 자체 갤러리 추가

각 다운로드 유형 플러그인은 자체 갤러리 HTML, CSS/JS, 라우트, i18n과 Controller를 모두 소유합니다. `/pixiv-gallery.html`과 `/api/gallery/**`는 공식 Pixiv 갤러리 플러그인에만 속하며 범용 갤러리 집계 API는 없습니다.

갤러리 상단의 유형 전환 막대에 연결하려면 자체 갤러리 HTML 상단에 다음을 넣으세요.

```html
<nav data-nav-slot="gallery.type-switch"></nav>
```

그리고 `navigation()`에서 `NavigationPlacements.GALLERY_TYPE_SWITCH` 탐색 항목을 제공합니다. 사이드바 진입점은 `gallery.sidebar`, `gallery.sidebar.tools`, `novel.sidebar`, `novel.sidebar.tools`를 사용합니다.

```java
new NavigationContribution(
        "example-gallery-type-switch",
        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
        "example-download", "nav.gallery",
        "/example-download-gallery.html", "images",
        AccessPolicy.ADMIN, 50)
```

## 설정, 자격 증명 및 파일

| 데이터 분류 | 디스크 경로 | 플러그인 접근 방법 |
| --- | --- | --- |
| 호스트 설정 및 플러그인 스위치 | `config/config.yaml` | `DownloadSettings`, `OutboundProxySettings` 등의 안정된 Core API 인터페이스로 읽기 전용 접근 |
| 플러그인 업무 설정 | `config/plugins/{pluginId}.properties` | 자식 컨텍스트 `Environment`(`Binder.get(environment).bind(...)` 권장)와 `GuiConfigContribution` |
| 플러그인 민감 자격 증명 | `config/credentials/{pluginId}.properties` | 호스트가 암호화하여 저장. 현재 owner가 선언한 민감 키만 자식 컨텍스트 `Environment` 또는 `PluginCredentials`에 주입 |
| 플러그인 상태 및 영구 데이터 | `state/{pluginId}/`, `data/{pluginId}/` | `RuntimePathProvider`를 주입하여 `stateDirectory()`, `dataDirectory()` 호출 |
| 다운로드 작품 파일 | `{rootFolder}/{pluginId}/` 또는 플러그인 사용자 정의 폴더 | `DownloadSettings.getRootFolder()`에서 파생. `state/`나 `data/`에 기록하면 안 됨 |

- **데이터베이스 접근**: 새 플러그인은 `PluginDatabase`를 주입하고 `privateSchema()`와 `privateStorageMigrations()`로 구조를 선언하며 `read(work)` / `transaction(work)`로 SQL 방언에 독립적인 작업을 수행합니다. SQLite와 PostgreSQL을 지원합니다. 주 DB 협업에는 `PluginMainDatabase`를 주입하여 현재 owner의 관리 테이블이나 신청한 `sharedMainStorage()`에 접근합니다.
- **아웃바운드 HTTP / WebSocket**: `OutboundHttpClientFactory` / `OutboundWebSocketClientFactory`를 주입하여 플러그인 Bean이 소유하고 소멸 시 `close()`하는 클라이언트를 만듭니다. `exchangeStream`이 반환한 응답 스트림은 모든 분기에서 닫아야 합니다. 직접 `HttpClient`, `ProxySelector`를 만들거나 호스트 HTTP 설정 클래스에 의존하면 안 됩니다.

## 독립 서비스 계약 및 조합

SDK 2.x는 플러그인 간 타입 서비스를 지원합니다. `typed-service-plugin` 템플릿을 참고하세요.

- 계약 전달 플러그인은 `pixiv.contract-api`와 `pixiv.contract-sha256`으로 원본 순수 JDK API JAR을 선언하며 `process-restart`를 사용합니다. 제공자는 `serviceExports()`에서 구현을 내보내고 소비자는 `serviceImports()`에서 의존성을 선언하며 `PluginServices`를 주입합니다.
- `find(key)`, `require(key)`, `findAll(key)` 또는 수명 주기 바인딩 `bind(keys, activation)` / `bindAll(keys, activation)`을 호출하여 정확한 publication에 연결된 프록시를 얻습니다.
- `sdk-tools.jar`로 JSON Schema, 원격 호출 어댑터를 생성하고 바이너리 호환성을 검사합니다.

```text
java -jar sdk-tools.jar schema <api-module> <classpath>
java -jar sdk-tools.jar remote <consumer-module> <classpath> example.one.Service example.two.Service
java -jar sdk-tools.jar contract-evolution <old-api.jar> <new-api.jar> <new-report.json>
```

큰 정수는 record 필드에 `@ValueProperty(encoding = ValueEncoding.DECIMAL_STRING)`을 표시합니다. `UUID`, `Instant`, `Duration`, `float`, `double`은 SDK가 정한 고정 무손실 문자열 인코딩을 사용합니다.

## GUI에 의존하지 않는 자격 증명 및 일반 작업 검증

- **`PluginCredentials`**: 플러그인은 `credentialKeys()`에서 현재 owner 접두사 아래의 민감 키를 선언할 수 있습니다. 최대 128개, 값당 64 KiB, 배치당 1 MiB이며 같은 이름의 GUI 필드도 민감 항목으로 표시해야 합니다. `PluginCredentials`를 주입하여 원자적 `read`, `update`, `delete`를 수행합니다.
- **HTTP Hooks**: `OperationHook` Bean에서 `id()`, `definition()`, `generation()`, `invoke(...)`를 구현합니다. 호스트는 실제 Spring 매핑에서 owner, HTTP 메서드, 경로 템플릿과 매칭 조건을 가져옵니다. 일반 컨트롤러와 동적 외부 컨트롤러에 Hook 주석이나 Guard 호출을 추가할 필요가 없습니다. 승인된 연결은 endpoint 또는 명시적으로 설정한 그룹을 선택하며, `OperationEndpoint`는 선택적인 작업 그룹 정보입니다.
- **설정과 검색**: 관리자는 `/api/web/operation-hooks/catalog`, `definitions`, `configuration`을 조회합니다. 기존 설정을 보존하고 같은 접두사의 `prepare`에 `{revision, settings}`를 보낸 뒤 대상, 영향받는 페이지와 권한을 검토하여 `publish`에 `{plan, approvals}`를 보냅니다. 설정은 `groups`와 `bindings`를 포함합니다. 연결은 `id/hook/operation/endpoint/phase/order/completion/configuration/fields`를 지정하며 `operation`과 `endpoint` 중 하나를 선택합니다. `resolveTargets`와 `getFormEndpoints(pageId, formId, actionId)`는 같은 해석기를 사용합니다. 미선언, 모호함, 권한 부족, 사용 불가 결과를 처리해야 합니다. 검색은 호출 또는 연결 권한을 부여하지 않습니다.
- **실행과 데이터**: `BEFORE_INVOKE`는 호스트 인증·권한 검사, CSRF 및 요청 수용 검사 뒤, 컨트롤러 전에 `CONTINUE/REJECT/CHALLENGE`를 반환합니다. 기본 입력은 메타데이터뿐입니다. 본문, 쿼리, 결과 필드는 승인된 `fields` 허용 목록으로 제한하여 추출하며 자격 증명 필드는 금지합니다. servlet 객체나 업무 action은 전달하지 않습니다. `AFTER_COMPLETION`은 `SUCCEEDED/FAILED/BLOCKED/TIMED_OUT/CANCELLED` 또는 `ALL`을 선택합니다. 성공은 HTTP 상태가 400 미만이라는 뜻이며, 본문 안의 업무 성공, 트랜잭션 커밋, 백그라운드 작업 완료를 뜻하지 않습니다. SSE, 다운로드와 비동기 요청은 전체 스트림을 저장하지 않고 완료 메타데이터를 관찰합니다.
- **챌린지와 수명 주기**: 호스트의 `POST /api/operation-hooks/prepare`와 `X-Pixiv-Hook-Ticket`이 준비, 게시 리비전 확인과 일회 소비를 처리합니다. DTO에 증명 필드를 넣거나 요청을 무조건 재전송하지 않습니다. 필수 전처리 provider가 없거나 실패·시간 초과되면 실행을 거부합니다. 후처리 실패는 원래 HTTP 결과를 바꾸지 않으며 영구 전달을 보장하지 않습니다. 시간 초과 뒤에도 Java 작업이 계속되면 같은 세대의 임대를 실제 종료까지 유지합니다. 한도는 페이지당 연결 16개와 양식 관계 128개, 연결당 필드 16개, 실행 중 HTTP 요청 64개, worker 8개, 대기 작업 64개입니다. 전처리 누적 대기와 후처리 대기는 각각 10초입니다.
- **진단과 로컬 호출**: 기본 영어 로그에 상관 ID, 호출 ID, 신뢰된 플러그인 generation/publication, 콜백 진입점, 연결 출처, handler·메서드·경로 템플릿, 단계와 결과를 기록합니다. 증명이나 본문은 기록하지 않습니다. `OperationVerification`은 대화형 전처리 Hook에 연결됩니다. `OperationGuard`는 명시적인 로컬 동기 작업에 사용하며 같은 HTTP 진입점에서 중복 호출하지 않습니다. 컴포넌트는 [작업 CAPTCHA](/ko/captcha)를 참고하세요.

### 어노테이션으로 고정 HTTP 대상 선언하기

SDK 2.x에서는 플러그인의 콜백 메서드에 `@HttpHook`을 사용할 수 있습니다. 아래 `StatusObserver`를 플러그인 자식 컨텍스트에 `@Bean`으로 등록하면 호스트가 선언을 발견합니다. 대상 컨트롤러는 수정할 필요가 없습니다. 이 예제는 성공한 로컬 상태 확인 요청을 셉니다. `/api/download/status`는 작품 다운로드 완료를 뜻하지 않습니다.

```java
import java.util.concurrent.atomic.AtomicLong;
import top.sywyar.pixivdownload.plugin.api.operation.HttpHook;
import top.sywyar.pixivdownload.plugin.api.operation.OperationHook;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

public final class StatusObserver {
    private final AtomicLong completed = new AtomicLong();

    @HttpHook(id = "example-status-observer", targetOwner = "core",
            method = HttpMethod.GET, path = "/api/download/status",
            phase = OperationHook.Phase.AFTER_COMPLETION,
            completion = OperationHook.Completion.SUCCEEDED)
    public void onStatus(OperationHook.Invocation invocation) {
        completed.incrementAndGet();
    }

    public long count() { return completed.get(); }
}
```

플러그인 관리에서 상세 정보를 열고 HTTP Hook 선언의 '승인 내용 미리 보기'를 선택합니다. 대상, 단계, 필드 권한과 영향받는 페이지를 검토한 뒤 '확인 후 게시'를 누릅니다. 동일한 선언은 플러그인을 다시 활성화하면 복구됩니다. 선언, 데이터 계약 또는 대상 매핑이 바뀌면 재승인이 필요합니다. 선택한 선언만 갱신하며 다른 선언은 자동 갱신하지 않습니다. 비활성화나 언로드 후에도 승인된 사전 보호는 유지됩니다. 해제하려면 관리자가 해제 내용을 미리 보고 게시해야 합니다.

싱글턴 Bean의 public 동기 인스턴스 메서드를 사용하고, 유일한 매개변수는 `OperationHook.Invocation`이어야 합니다. 사전 콜백은 `Decision`을 반환하며 완료 콜백은 void도 가능합니다. `@Async`나 작업 완료 전에 반환하는 콜백은 지원하지 않습니다. 호스트 로그에는 실제 클래스와 메서드가 기록됩니다. 인터페이스 방식과 어노테이션 방식을 합쳐 플러그인당 최대 16개 Hook을 제공합니다.

`targetOwner/method/path`는 실제 카탈로그에서 handler를 선택합니다. 같은 경로에 후보가 여러 개이면 `params/headers/consumes/produces`에 카탈로그의 전체 조건을 지정합니다. 비어 있지 않은 목록은 조건 집합과 정확히 일치해야 하며, 빈 목록은 해당 조건을 필터링하지 않습니다. 대상이 없거나 모호하면 승인할 수 없습니다. 같은 handler의 모든 경로 별칭은 바인딩을 공유합니다.

일반 메서드는 메타데이터만 받으며 설정은 `{}`입니다. 필드, 설정 schema, 리소스 또는 대화형 챌린지가 필요하면 `OperationHook`을 구현하고 `invoke`에 어노테이션을 추가합니다. `id()`는 어노테이션 ID와 같아야 합니다. `fields`에는 승인, 민감 필드 검사와 예산이 적용됩니다. 동적 설정과 페이지 편집기 바인딩은 어노테이션 없이도 사용할 수 있습니다.

관리 API는 `GET /api/web/operation-hooks/declarations?provider=<pluginId>`와 `POST /api/web/operation-hooks/declarations/prepare`입니다. 후자는 `{revision, provider, id, fingerprint, remove}`를 받고 반환한 계획은 기존 `publish`에 제출합니다. 카탈로그, 플러그인 또는 게시 리비전이 바뀌면 계획을 다시 받아야 합니다.


## 일반 슬롯 및 페이지 컴포넌트

일반 페이지는 `/js/pixiv-ui-slots.js`의 `PixivUiSlots.connect(...)`로 `data-ui-slot`을 마운트합니다. 플러그인 모듈은 `registerModule(moduleUrl, mount)`를 호출하고 `onCleanup`에서 DOM, 리스너와 요청을 해제합니다. 페이지 편집기 컴포넌트와 편집 가능한 페이지 연결은 [페이지 편집기 및 플러그인 컴포넌트](/ko/page-editor#서드파티-플러그인-컴포넌트-개발-및-페이지-연결)를 참고하세요.

## 외부 프로젝트 유지 관리 및 SDK 업그레이드

### 로컬 개발

SDK 개발 패키지는 IntelliJ IDEA(`Developer Mode` 실행), VS Code(`Run Plugin` / `Debug Plugin`), Eclipse(`eclipse/Run Plugin.launch`) 또는 명령줄에서 직접 빌드하고 함께 제공하는 호스트 및 전체 공식 플러그인을 시작할 수 있습니다. 실행 데이터는 프로젝트의 `.dev/`에 격리합니다. 최초 실행 시 고정된 실행 패키지를 `~/.cache/pixivdownloader-sdk/`에 자동 다운로드하고 검증합니다.

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd verify exec:exec@sdk-debug
.\mvnw.cmd exec:exec@sdk-stop
```

기본 패키징 형태는 루트에 `plugin.properties`가 있고 호스트 공유 클래스를 포함하지 않는 thin PF4J `.jar`입니다. 플러그인 전용 서드파티 의존성이 있으면 PF4J JAR-with-lib를 사용하고 전용 의존성을 `lib/*.jar`에 넣을 수 있습니다. SDK를 업그레이드할 때는 새 개발 패키지를 별도 폴더에 풀고 Javadoc과 대조하여 계약 및 실행 매니페스트를 갱신하세요. 플러그인 관리 페이지에서 활성화/비활성화, reload와 작업 drain을 검증하세요.

## 마켓 자료 제출

커뮤니티 제출 마법사는 플러그인 아이콘, 스크린샷, 외부 링크 목록과 다국어 `README`, 이번 업데이트 설명, `CHANGELOG.md`를 첨부할 수 있습니다. `## [v1.2.0]` 같은 버전 제목으로 자동 추출할 수 있습니다. 모든 문서와 이미지는 같은 버전 Release의 서명으로 고정되며 클라이언트는 정제된 정적 격리 문서로 표시합니다. [커뮤니티 저장소 제출 안내](https://github.com/Sywyar/PixivDownloader-community-plugins#投稿与版本管理)를 참고하세요.

## 서명 및 배포

주 저장소에서 `pixivdownload-plugin-signature`를 빌드한 후 `PluginSignatureTool`로 Ed25519 키와 서명을 관리합니다. 개인 키는 저장소와 빌드 폴더 밖에 보관해야 합니다.

```powershell
# 1. 키 쌍 생성 및 공개 키 지문 내보내기
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool keygen --directory "C:\secure\keys"
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool public-key --public-key "C:\secure\keys\public-key.pem" --key-id example-2026 --out "public.json"

# 2. 플러그인 패키지 및 저장소 manifest.json 서명
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool artifact --artifact "plugin.jar" --plugin-id example-download --version 0.1.0 --key-id example-2026 --private-key "C:\secure\keys\private-key.pem" --out "plugin.jar.sig"
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool manifest --manifest "manifest.json" --repository-id example.plugins --key-id example-2026 --private-key "C:\secure\keys\private-key.pem" --out "manifest.json.sig"
```

자체 플러그인 저장소는 `manifest.json` + `manifest.json.sig`(`manifest-v1` 프로토콜)를 게시하거나 `paged-v2` 페이지 단위 엔드포인트를 구현할 수 있습니다. 사용자에게 데스크톱 설정에서 한 번에 가져올 수 있는 최대 64 KiB의 공인 HTTPS `repository.json` 설명자를 제공하세요. `DIRECT_STRICT` 또는 `GITHUB_RELEASES` 네트워크 정책, `repository-update` 교체 증명과 `plugin-revocations` 취소 목록을 지원합니다.

주 저장소에 SDK 계약이나 공식 플러그인 수정을 기여하려면 먼저 [주 저장소 개발](/ko/development)을 읽으세요.
