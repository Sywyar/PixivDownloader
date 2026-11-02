# 타사 플러그인 SDK

SDK 개발 패키지의 독립 프로젝트에서 예제를 빌드하고 실행한 다음 페이지, 다운로드 유형, 업무 로직을 추가하세요. JDK 17, Node.js, 선택한 SDK 패키지에 포함된 빌드 도구와 런타임 매니페스트가 필요합니다.

| 작업 | 읽을 곳 |
| --- | --- |
| 첫 플러그인 만들기 | [템플릿](#템플릿에서-시작하기), [로컬-개발](#로컬-개발) |
| 다운로드 유형과 갤러리 추가 | [다운로드 workflow](#다운로드-유형-추가-workflow), [갤러리](#플러그인-소유-갤러리) |
| 설정·자격 증명·데이터 저장 | [소유권과 저장소](#설정-자격-증명과-파일) |
| 기존 프로젝트 업데이트 | [SDK 업그레이드](#외부-프로젝트-유지-관리와-sdk-업그레이드) |
| 배포 | [마켓 콘텐츠](#마켓-콘텐츠-제출), [서명과 게시](#서명과-게시) |

예제의 `SDK_VERSION`, 경로, ID, 해시, 서명은 실제 값으로 바꾸세요. Java와 JavaScript 코드는 템플릿 안에서 사용할 인터페이스 예제입니다. import와 업무 구현을 추가하고, 선택한 릴리스의 Javadoc과 템플릿에서 API 지원 여부를 확인해야 합니다.

- [플러그인 템플릿](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates)
- [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info), [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api), [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api)
- [Douyin 예제](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin), [서명 도구](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature)
- [SDK 다운로드와 릴리스 기록](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases), [버전별 Javadoc](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)

Douyin은 다운로드, 설정, 프록시, 큐, 예약 작업, 비공개 저장소와 자체 갤러리를 포함하는 독립 SDK 예제입니다. 공개 계약만 사용하며 공식 배포에는 포함되지 않습니다. 새 프로젝트는 사이트별 구현까지 복사하지 않도록 `plugin-templates`에서 시작하세요.

## 라이선스와 배포 조건

PixivDownloader와 공식 플러그인은 AGPL-3.0을 사용하며, 해당 예외가 포함된 버전에는 [Plugin Linking Exception](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)이 추가됩니다. 공개 SDK/API 또는 문서화된 확장 인터페이스를 사용하는 독립 타사 플러그인은 예외의 조건을 충족하면 MIT License를 선택할 수 있습니다.

프로젝트가 작성한 플러그인 템플릿의 복사·수정도 예외에 포함되지만, 배포 시 관련 저작권 고지와 예외 문구를 보존해야 합니다. 호스트, SDK/API, 런타임, 공식 플러그인 구현과 그 수정에는 해당 AGPL 의무가 계속 적용되며 타사 의존성에는 각 라이선스가 적용됩니다. 개발 패키지는 원문을 `licenses/pixivdownloader/`에, Maven SDK/API JAR은 `META-INF/`에 보관합니다. 자신의 플러그인 라이선스를 명시하고 실제 사용하는 호스트·SDK 버전의 허용 범위를 확인하세요. 문서 수정으로 예외가 없는 과거 릴리스에 권한이 추가되지는 않습니다.

[공식 커뮤니티 저장소](https://github.com/Sywyar/PixivDownloader-community-plugins)는 완전한 공개 GitHub 소스, 오픈 소스 라이선스, 재현 가능한 빌드와 사람의 검토를 요구합니다. 공개 바이너리나 SPDX 표현만으로는 충분하지 않습니다. 라이선스 허용, 커뮤니티 등록, 패키지 검증과 사용자 신뢰 확인은 각각 충족해야 합니다.

## 먼저 신뢰 경계를 이해하기

`declarative-process`는 별도 worker JVM에서 자원·프로토콜을 분리하고, `host-process-full-trust`는 호스트 JVM에서 실행합니다. 어느 쪽도 OS 보안 샌드박스가 아닙니다. 플러그인은 현재 계정이 읽을 수 있는 파일에 접근하거나 네트워크 요청을 보내고 자원을 사용할 수 있습니다.

Ed25519 서명은 신뢰한 키의 출처와 바이트 무결성을 증명하며 코드의 안전성을 보증하지 않습니다. 사용자는 게시자, 소스, 저장소 운영자를 신뢰해야 합니다. 쿠키, 토큰, 프록시, 작품 경로와 비공개 데이터를 적절히 다루는 책임은 플러그인 작성자에게 있습니다.

호스트는 구조, 크기, 경로, 버전, 의존성, SHA-256, 서명과 provenance를 확인한 뒤 동일한 고정 바이트를 로드합니다. 설치 동작은 [플러그인 관리](/ko/plugin-management)를 참고하세요.

## SDK 경계

얇은 `pixivdownload-sdk` 의존성은 표준 POM으로 세 API 모듈과 호스트 제공 컴파일 의존성을 가져옵니다. API 모듈과 BOM을 개별 사용해도 됩니다. `sdk-info`가 전체 SDK 버전, 사전 릴리스 식별자와 호환 규칙의 기준이며 앱 버전과는 별개입니다. `plugin-api`는 진입점, contribution, 호스트 제어와 owner 범위 저장 기능을, `core-api`는 업무 의미가 안정된 포트, 값 모델과 중립 알고리즘을 제공합니다.

```text
Third-party plugin
  └─ pixivdownload-sdk        provided: one compile dependency
      ├─ pixivdownload-sdk-info    SDK version and compatibility information
      ├─ pixivdownload-plugin-api  Entry points, contributions, paths, and private data source
      ├─ pixivdownload-core-api    Stable download and proxy settings ports
      └─ Host-provided PF4J, Spring, Servlet, Jackson and other compile dependencies

Do not depend on: pixivdownload-app, host implementation classes,
plugin-runtime/installer/signature internals, private services/mappers/controllers
from official plugins, the host DataSource, or private frontend globals
```

호스트는 신뢰한 플러그인·패키지 ID, generation, publication에 능력을 등록합니다. 특정 타사 ID나 작품 유형에 대한 호스트 분기를 추가하지 마세요. 중지, 언로드, 손상, 비호환 시 라우트, 정적 리소스, i18n, 탐색, 다운로드 유형, 큐와 예약 기능이 철회됩니다. 소비자는 기능 부재를 명시적으로 처리해야 하며 빈 페이지, null 오류나 반쪽 작업을 남기면 안 됩니다.

### 기여할 수 있는 기능

사용하지 않는 `PixivFeaturePlugin` 진입점은 기본 빈 목록으로 둡니다.

| 메서드 | 기능 |
| --- | --- |
| `id`, `displayName`, `description`, `displayNamespace` | 식별자와 i18n 표시 키 |
| `iconKey`, `colorToken`, `kind` | 허용된 아이콘·색상·분류 토큰 |
| `start`, `stop` | 직접 소유한 자원의 수명 주기. `stop`은 반복 호출에 안전해야 함 |
| `schema` | 호스트 협업용 관리 테이블·추가 열·경로 열 선언. 일반 비공개 저장용이 아님 |
| `sharedMainStorage` | 공개 호스트 논리 저장 영역 요청 |
| `privateSchema` | 현재 owner의 모든 authoritative 비공개 테이블 |
| `privateStorageMigrations` | 시작 및 오프라인 마이그레이션의 순서 있는 데이터 변환 |
| `routes`, `staticResources`, `i18n` | 접근 정책, 클래스패스 리소스 URL, Web 네임스페이스 |
| `navigation`, `startupRoutes`, `landings` | 탐색 위치, 시작 경로, 사용자 유형별 목적지 |
| `pageSections`, `uiSlots` | 페이지 섹션과 제한된 Web UI 삽입 위치 |
| `guiThemes` | 시작 시 적용할 GUI 테마. 보통 `process-restart` 필요 |
| `guiConfigContributions`, `guiOnboardingSteps` | 설정 필드·그룹·섹션·액션·프리셋과 초기 안내 |
| `drilldowns`, `userscripts` | 의미 기반 연결, 안정된 스크립트 ID와 정확한 클래스패스 리소스 |
| `scheduledSourceDescriptors`, `downloadTypes` | 순수 데이터 예약 소스 및 다운로드 유형 설명자 |

`PixivFeaturePlugin`에서 Spring Bean을 반환하지 않습니다. `PixivPluginProvider.configurationClasses()`로 설정 클래스를 선언하면 호스트가 활성 플러그인마다 별도 자식 `ApplicationContext`를 만듭니다.

### 선언형 데스크톱 UI 경계

호스트는 toolkit과 무관한 안정된 계약으로 업무 상태, 설정 의미와 액션을 제공합니다. `gui-swing`과 `gui-compose`는 각자의 전체 페이지 트리, 레이아웃, 컴포넌트, 상호작용 상태, 창, 트레이, 테마와 플랫폼 통합을 소유합니다. SDK에는 `DesktopUiDocument`, `DesktopUiNode` 같은 공통 뷰 AST가 없으며 두 provider가 페이지 구현을 공유해야 한다는 계약도 없습니다.

기능 플러그인은 `GuiConfigContribution` 같은 순수 데이터 계약으로 업무 의미를 선언합니다. 호스트는 신뢰한 owner 기준으로 병합·검증·저장하고 GUI provider가 자체 UI로 표시합니다. Swing/Compose 컴포넌트를 반환하거나 호스트 최상위 창을 직접 소유하지 마세요. 호스트도 provider ID로 페이지 구조를 결정하지 않습니다.

공식 배포에는 Compose와 Swing이 모두 있으며 기본은 Compose, 대체 UI는 Swing입니다. 두 플러그인은 `process-restart`이므로 변경·설치·업데이트·비활성화·제거 시 앱 전체를 재시작해야 합니다. Compose는 Gradle Wrapper가 Kotlin/Compose와 JAR-with-lib를 빌드하고 Maven reactor가 호출하여 공식 서명·배포에 연결합니다.

## 템플릿에서 시작하기

| 템플릿 | 용도와 포함 기능 |
| --- | --- |
| `minimal-feature-plugin` | 선언형 페이지·탐색·i18n. 별도 worker, PF4J 진입점, feature, 라우트·정적 리소스 및 thin-JAR 테스트. Spring 자식 context 없음 |
| `download-type-plugin` | 다운로드 유형, 다섯 수집 모드, 큐, 예약 소스, Vue slot, 독립 갤러리와 프런트·백엔드 테스트 |

저장소 안에서 템플릿을 검증하는 명령입니다.

```powershell
mvn -f plugin-templates/pom.xml clean verify
mvn -f plugin-templates/pom.xml -pl minimal-feature-plugin -am verify
mvn -f plugin-templates/pom.xml -pl download-type-plugin -am verify
```

밖으로 복사한 템플릿은 루트 parent를 상속하지 않는 독립 Maven 프로젝트입니다. 해당 디렉터리에서 실행합니다.

```powershell
mvn clean verify
```

### SDK 아티팩트

개발 패키지는 독립 Maven 프로젝트, Gradle/sbt 예제와 단일 `pixivdownload-sdk` 의존성을 제공합니다. 고정 런타임 매니페스트가 있는 패키지의 Run/Debug는 현재 플러그인을 빌드하고 대응 호스트 및 공식 플러그인 전체를 준비합니다. 런타임 데이터는 프로젝트 `.dev/`에 저장합니다. 선택한 SDK Release의 README를 먼저 확인하세요.

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>SDK_VERSION</version>
    <scope>provided</scope>
</dependency>
```

이름을 생략한 `@PathVariable`, `@RequestParam`을 쓰려면 컴파일 결과에 매개변수 이름이 있어야 합니다. Maven `<properties>`에 `<maven.compiler.parameters>true</maven.compiler.parameters>`를 설정하거나 컴파일러에 `-parameters`를 전달하세요. 기존 프로젝트도 확인하고, 패키지 엔드포인트를 호스트를 통해 호출해 바인딩을 검사하세요. 컨트롤러 메서드 직접 호출만으로는 확인할 수 없습니다.

Gradle은 `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:SDK_VERSION")`, sbt는 `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "SDK_VERSION" % Provided`를 사용합니다. Ivy의 컴파일 설정 예제입니다.

```xml
<dependency org="io.github.sywyar.pixivdownloader" name="pixivdownload-sdk"
            rev="SDK_VERSION" conf="compile->default"/>
```

Ivy runtime 설정이 이 compile 설정을 상속하게 하지 마세요. PF4J, Spring, Servlet, Jackson 및 공개 API는 호스트가 제공합니다. 이 클래스들을 번들하지 않는 thin PF4J JAR을 만들고 테스트 프레임워크는 별도 선언합니다.

`SDK_VERSION`은 통합 진입점을 포함하는 실제 공개 SDK 버전으로 바꿉니다. 다섯 좌표는 통합 진입점, 세 API 모듈과 BOM입니다. `developmentRuntime`이 있는 릴리스는 고정 호스트를 포함하며 과거 패키지는 그 README를 따릅니다. 공개 Java 표면이나 Maven 소비 의미가 바뀌면 새 SDK 식별자가 필요합니다. 게시한 좌표와 첨부 파일을 덮어쓰지 마세요.

### 복사 후 모든 식별자 바꾸기

| 템플릿 값 | 바꿀 위치 |
| --- | --- |
| `example-download-plugin` | Maven `artifactId` |
| `example-download` | 전역 고유 플러그인 ID, 큐 유형, URL 접두사와 i18n 네임스페이스 |
| `com.example.pixivdownload.downloadtype` | Java 패키지와 디렉터리 |
| `ExampleDownload` | Java 타입 이름 접두사 |
| `0.1.0` | 아티팩트와 `plugin.version` |
| `plugin.requires=1.3` | 지원하는 SDK major.minor |
| `plugin.provider=Example Developer` | 게시자 |

라우트, 정적 경로, 프런트 상수, 비공개 테이블, 테스트와 번역도 함께 바꾸세요. `plugin.properties`만 바꾸면 descriptor·feature·publication ID가 달라져 거부됩니다.

## 플러그인 패키지와 진입점

`plugin.properties`는 JAR 루트에 둡니다. 다음 예제는 현재 소스 템플릿의 SDK 1.3 요구사항입니다. 다른 개발 패키지는 실제 최소 계약에 맞게 컴파일 버전과 `plugin.requires`를 함께 설정해야 합니다. 선언만 바꿔서는 호환되지 않습니다.

```properties
plugin.id=example-download
plugin.version=0.1.0
plugin.requires=1.3
plugin.class=com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin
plugin.provider=Example Developer
plugin.description=Example download type.
pixiv.display-namespace=example-download
pixiv.display-name-key=plugin.name
pixiv.description-key=plugin.summary
pixiv.icon-key=download
pixiv.color-token=green
pixiv.execution-mode=host-process-full-trust
pixiv.lifecycle-policy=process-restart
```

| 필드 | 규칙 |
| --- | --- |
| `plugin.id` | 전역 고유 lowercase kebab-case. `PixivFeaturePlugin.id()`와 같아야 함 |
| `plugin.version` | 플러그인 아티팩트 버전 |
| `plugin.requires` | 앱 버전이 아닌 SDK 요구 버전 |
| `plugin.class` | `PixivPluginProvider`를 구현한 PF4J 메인 클래스 |
| `plugin.provider`, `plugin.description` | 게시자와 설명 |
| `plugin.dependencies` | 선택적 PF4J 의존성 표현 |
| `pixiv.*` 표시 필드 | i18n 네임스페이스·키와 제한된 표시 토큰 |
| `pixiv.replaces` | 대체할 플러그인의 선택적 ID |
| `pixiv.execution-mode` | 필수. `declarative-process` 또는 `host-process-full-trust`. 누락·공백·지원하지 않는 값은 거부 |
| `pixiv.lifecycle-policy` | 대소문자를 구분하는 `hot-reload`, `backend-restart`, `process-restart`. 기본 `hot-reload` |

새 SDK 사전 릴리스는 양수이며 앞에 0이 없는 `alpha.N`, `beta.N`, `rc.N`을 씁니다. 과거 compact 접미사는 기존 릴리스 ID를 유지합니다. 비교는 채널과 숫자 순서로 하므로 `rc2 < rc.10`, `rc2`와 `rc.2`는 같은 우선순위입니다. 표기만 바꿔 재게시할 수 없습니다. 좌표, 태그, 다운로드 경로와 매니페스트는 선택한 릴리스와 정확히 일치해야 합니다. 과거 도구는 동봉된 지침을 따르며 새 표기 지원에는 대응 개발 패키지가 필요합니다.

안정 SDK의 승인 조건은 `requiredMajor == hostMajor && requiredMinor <= hostMinor`이며 PATCH는 승인에 영향을 주지 않습니다. 사전 릴리스는 전체 SDK 버전이 같아야 합니다. 계약 파괴는 MAJOR, 호환 추가는 MINOR, 호환 수정은 PATCH를 올립니다.

## PostHog 브라우저 클라이언트 재사용

설문을 게시하는 Web 플러그인은 공식 `posthog` 플러그인에 의존하고 자신의 스크립트보다 `/pixiv-posthog/pixiv-posthog.js`를 먼저 로드할 수 있습니다. 설문 ID, 질문 schema, 트리거, 상태, 문구, 개인정보 필터와 네 가지 프로젝트 매개변수는 게시 플러그인이 소유합니다.

```properties
plugin.dependencies=posthog?@1.0
```

```js
const posthog = Object.freeze({
  projectToken: 'phc_...',
  surveyId: '...',
  apiHost: 'https://example.invalid',
  uiHost: 'https://us.posthog.com'
});

const client = await window.PixivPostHog?.createSurveyClient({
  ownerKey: 'example-plugin.feedback',
  posthog,
  distinctId: '',
  beforeSend(event) {
    return allowedSurveyEvent(event) ? event : null;
  }
});
if (!client) return; // dependency missing, invalid parameters, SDK failure, or conflict

await window.PixivPostHog.captureSurveyWithAck(
  'example-plugin.feedback',
  'survey sent',
  surveyProperties,
  submissionId
);
```

`ownerKey`는 전역적으로 안정되어야 합니다. 같은 페이지에서 같은 owner를 다시 요청할 때 네 매개변수, `distinctId`, `beforeSend` 함수 객체가 모두 같아야 기존 클라이언트를 재사용하며, 하나라도 다르면 거부합니다. 서로 다른 owner는 다른 프로젝트를 쓸 수 있습니다.

`submissionId`는 설문 ID, campaign 버전과 설문별 익명 ID로 만든 안정된 UUID입니다. 같은 답변의 재시도는 같은 값을 쓰며 campaign 변경 시에만 새 값으로 바꿉니다. 어댑터는 이를 이벤트 최상위 `uuid`로 보내고 고정 수집 endpoint의 2xx 응답 후에만 Promise를 완료합니다. 없거나 잘못된 UUID는 요청 전에 거부합니다. 번들 SDK의 고정 버전을 로드하고 기본 수집을 끄며 이름 있는 격리 인스턴스를 만들지만 설문·ID·답변 필드를 대신 결정하지 않습니다. 이미 열린 페이지의 JavaScript는 비활성화 시 회수할 수 없습니다. 새로고침 후 리소스가 사라지면 클라이언트 부재로 처리해야 합니다.

### 받은편지함에서 설문 표시하기

slot 모듈이 없는 `notification.inbox` 기여를 추가할 수 있습니다.

```java
new SurveyInboxMessage(
        "example-plugin.feedback-survey",
        "campaign-v1",
        "/example-plugin/survey.html",
        "example-plugin",
        "survey.inbox-title",
        "survey.inbox-body",
        100
).toUiSlotContribution()
```

`messageKey`는 안정된 전역 고유 값이고, `instanceKey`는 새 campaign일 때만 바꿉니다. URL은 플러그인이 소유한 동일 출처 절대 경로여야 하며 namespace와 제목·요약 i18n 키를 게시해야 합니다. 관리자 전용 설문은 라우트도 `ADMIN`으로 선언합니다. HTML은 플러그인 페이지에 남고 계약으로 raw HTML, Bean, ClassLoader를 넘기지 않습니다. 잘못된 설문 contribution은 거부되고 로그에 기록됩니다. wrapper 대신 metadata를 직접 만들지 마세요.

이는 선택적 best-effort 표시 기능입니다. notification 플러그인이 없어도 설문 페이지와 PostHog 제출은 사용할 수 있습니다. 호스트는 순수 값 활성 snapshot을 유지하며 quiesce 중 게시자를 호출하지 않습니다. 시작 동기화 또는 slot 변경 후 다음 받은편지함 요청에서 멱등 저장합니다. 같은 instance는 읽음 상태와 unavailable tombstone을 재사용하고, 새 instance는 안 읽은 메시지가 됩니다. contribution 부재, 비활성화, unload, publication 교체 시 메시지는 숨겨지고 같은 instance 복구용 상태는 남습니다.

호스트가 URL에 `notificationId`, `lang`을 추가합니다. 페이지는 `pixiv-content-height` 메시지로 높이를 알릴 수 있습니다. 원격 설문이 영구 종료·삭제되었음을 확인한 경우에만 동일 출처 부모에 `{type: 'pixiv-survey-unavailable', notificationId}`를 보냅니다. 일시적 네트워크 실패에 보내면 안 됩니다. 이 메시지는 같은 instance를 다시 게시해도 유지되는 로컬 숨김 표식을 만듭니다.

## PF4J와 Spring child context

```java
public final class ExampleDownloadPf4jPlugin
        extends org.pf4j.Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new ExampleDownloadPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(ExampleDownloadConfiguration.class);
    }
}
```

외부 패키지는 descriptor와 ID가 같은 null이 아닌 feature를 정확히 하나 반환해야 합니다. 설정 클래스의 `@Bean`으로 명시적으로 조립합니다.

```java
@Configuration(proxyBeanMethods = false)
public class ExampleDownloadConfiguration {

    @Bean
    ExampleDownloadPlugin exampleDownloadPlugin() {
        return new ExampleDownloadPlugin();
    }

    @Bean
    ExampleDownloadController controller(
            ExampleDownloadQueue queue,
            RequestOwnerIdentityResolver ownerResolver) {
        return new ExampleDownloadController(queue, ownerResolver);
    }
}
```

호스트 루트 패키지 스캔에 의존하거나 플러그인의 임의 클래스를 스캔하지 마세요. 자식 context에는 부모가 명시적으로 노출한 Plugin API, Core API, JDK 및 표준 의존성을 주입할 수 있으며 app 구현 클래스는 주입할 수 없습니다.

## 알림 템플릿

템플릿은 mail/push 전송 플러그인이 아니라 업무 시나리오의 owner가 소유합니다. 그 자식 context에서 `top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContributor` Bean을 노출합니다. `NotificationTemplateContribution`은 `scenarioId`, `medium`, `locale`, `titleTemplate`, `bodyTemplate`의 순수 값입니다.

```java
@Bean
NotificationTemplateContributor notificationTemplates() {
    return () -> List.of(new NotificationTemplateContribution(
            "example.completed",
            "mail",
            Locale.US,
            "Example completed",
            """
            <!doctype html>
            <html><body><p>{{summary}}</p></body></html>
            """));
}
```

템플릿 게시가 새 시나리오 등록이나 발송을 의미하지 않습니다. 안정된 scenario/dispatcher 계약이 허용한 ID만 쓰고 다른 플러그인의 tuple을 덮어쓰지 마세요. mail/push는 읽기 전용 `NotificationTemplateCatalog`를 소비하며 설정, 렌더 검증, 발송과 실패 처리는 각각 소유합니다.

호스트는 publication 준비 중 contributor를 호출해 불변 snapshot을 복사하고 stop/reload/unload 때 정확히 그 publication을 제거합니다. 중복 `(scenarioId, medium, locale)`은 즉시 실패합니다. 조회는 정확한 locale, 그다음 결정적인 같은 언어 fallback 순서입니다.

HTML은 같은 JVM에서 크기가 제한된 `String`으로 전달합니다. UTF-8 기준 제목 16 KiB, 본문 1 MiB, 한 publication 최대 256개·합계 8 MiB입니다. `InputStream`, `Path`, Spring `Resource`, `ClassLoader`, Bean, 지연 callback처럼 플러그인 수명이나 I/O를 보유하는 객체를 넘기지 마세요. HTML placeholder에 넣을 외부 값은 escape해야 합니다. 더 큰 데이터나 바이너리가 필요하면 수명·할당량이 명시된 별도 호스트 streaming/blob-handle 계약을 제안하고, 임시 파일 교환이나 기존 한도 확대에 의존하지 마세요.

## 웹 라우트, 정적 리소스와 i18n

모든 controller mapping, 정적 디렉터리와 최상위 HTML을 같은 owner의 `routes()`에 선언합니다. 다른 플러그인의 넓은 prefix는 대신 승인하지 못합니다. 미선언 `path + HTTP method`는 404이며 프런트 메뉴 숨김은 인증이 아닙니다.

| factory | 접근 범위 |
| --- | --- |
| `publicRoute` | 인증 없이 solo/multi 동일 |
| `visitor` | multi의 비로그인 방문자. solo는 세션 필요. 초대 게스트는 불가 |
| `visitorAndInvitedGuest` | multi 방문자와 초대 게스트 읽기 허용 |
| `invitedGuest` | 관리자와 초대 게스트, monitor 보호 적용 |
| `admin` | 관리자 |
| `local` | 로컬 프로세스 절차 예외 |
| `gui` | 신뢰한 로컬 요청과 GUI 토큰 모두 필요 |

메서드 제한은 표준 `WebRouteContribution` 생성자의 명시적 `HttpMethod` 집합으로 설정합니다. 서로 다른 owner의 경로와 메서드가 겹치면 `AccessPolicy`가 같아야 합니다. 다른 정책은 두 owner와 패턴을 알리며 등록을 거부합니다. 같은 정책으로 namespace를 공유할 수 있고, 같은 owner는 자기 broad prefix를 더 구체적인 선언으로 좁힐 수 있습니다.

독립 관리자 페이지의 선언 예제입니다.

```java
@Override
public List<WebRouteContribution> routes() {
    return List.of(
            WebRouteContribution.admin("/example-download-gallery.html"),
            WebRouteContribution.admin("/example-download-gallery/**"),
            WebRouteContribution.admin("/api/example-download/gallery"));
}

@Override
public List<StaticResourceContribution> staticResources() {
    return List.of(
            new StaticResourceContribution(
                    "classpath:/static/", "/example-download-gallery.html", true),
            new StaticResourceContribution(
                    "classpath:/static/example-download-gallery/",
                    "/example-download-gallery/"));
}

@Override
public List<I18nContribution> i18n() {
    return List.of(new I18nContribution(
            "example-download", "i18n.web.example-download"));
}
```

HTML, CSS, JavaScript를 나누고 사용자 문구는 플러그인 namespace에 둡니다. 외부 데이터는 DOM API와 `textContent`로 표시하며 알 수 없는 텍스트를 `innerHTML`에 연결하지 마세요.

## 다운로드 유형 추가 workflow

현재 소스의 task 계약은 제출 handler, option hook, event observer, 요청 중복 제거, 특정 attempt 취소와 재접속 snapshot을 제공합니다. 필수 SDK base class는 없습니다. 대응 계약이 있는 SDK와 호스트를 선택하세요. 현재 소스에 있는 인터페이스가 과거 릴리스에도 있다고 가정하면 안 됩니다.

```text
plugin.properties + provider
        ↓
DownloadTypeDescriptor ──→ the workbench discovers the type and acquisition modes
        ↓
same-origin behavior module → import, discovery, queueing, status, filters, and settings
        ↓
plugin controller/service ─→ parses requests and performs real domain work
        ↓
QueueOperations ─────────→ cancellation, clearing, and lifecycle drain
        ├─ WebUiSlotContribution (optional)
        ├─ ScheduledSourceDescriptor + executor (optional)
        └─ plugin-owned independent gallery (optional)
```

### 1. 유형 설명자

`QueueTypeContribution`, `independentPage`, gallery capability bag이나 descriptor 내부 `uiSlots` 필드는 없습니다. 다운로드 유형, 큐, UI slot, 예약 소스와 페이지는 각각의 계약을 사용합니다.

```java
@Override
public List<DownloadTypeDescriptor> downloadTypes() {
    return List.of(new DownloadTypeDescriptor(
            DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
            "example-download",
            "example-download",
            "batch.kind",
            900,
            "download",
            "green",
            "/example-download/example-download-type.js",
            List.of(
                    DownloadAcquisitionMode.SINGLE_IMPORT,
                    DownloadAcquisitionMode.USER_PROFILE,
                    DownloadAcquisitionMode.SERIES_COLLECTION,
                    DownloadAcquisitionMode.SEARCH,
                    DownloadAcquisitionMode.QUICK),
            true,
            List.of("example-ready-filter"),
            List.of("example-output-setting"),
            "example-download"));
}
```

| 필드 | 조건 |
| --- | --- |
| `contractVersion` | 현재 `CURRENT_CONTRACT_VERSION`, 값 1 |
| `type` | 전역 고유. 보통 `QueueOperations.queueType()`과 같지만 registry가 일대일을 가정하지 않음 |
| `displayNamespace`, `displayI18nKey` | 유형명 namespace와 순수 키 |
| `order` | 안정된 정렬 값 |
| `iconKey`, `colorToken` | 호스트 허용 토큰. URL·HTML·임의 CSS 불가 |
| `moduleUrl` | 같은 owner가 게시한 동일 출처 절대 `.js` 경로, 필수 |
| `acquisitionModes` | `single-import`, `user`, `series`, `search`, `quick`의 부분 집합 |
| `cancelSupported` | true이면 큐 항목 최상위 `cancelWorkKey` 필요 |
| `filters`, `settings` | 모듈이 구현하는 허용된 계약 ID |
| `i18nNamespace` | 모듈 상태·오류 문구 namespace |

### 2. 프런트 동작 모듈

호스트가 실제 `<script>`를 평가하는 구간에서 `PixivBatch.queueTypes.registerModule(initializer)`를 호출합니다. Vue를 복사하거나 호스트 `state`, `saveQueue`, `renderQueue`, `updateStats`, 비공개 DOM ID에 직접 접근하지 마세요.

관리자 페이지는 `DownloadTasks` snapshot을 기존 큐에 병합합니다. `process(item, context)`에서 백엔드가 반환한 실행 UUID로 `context.bindTask(taskId)`를 호출합니다. 작품 ID는 실행 ID가 아닙니다. 다른 경로에서 제출된 작업은 “Other sources”로 표시하며 다시 제출하지 않습니다. 종료 snapshot은 5분 뒤 만료되고 저장된 큐 기록은 남습니다. 확인할 수 없는 실행은 미확인 상태를 유지하며 새로고침으로 재제출하지 않습니다.

| 진입점 | 책임과 생략 조건 |
| --- | --- |
| `process(item, context)` | 필수. 플러그인 API 호출 후 `context.updateItem(patch)`로 허용된 상태 갱신 |
| `import` | 단일 작품 URL 판별, 항목과 `cancelWorkKey` 생성. `single-import` 미선언 시 생략 |
| `acquisition.user` | 사용자 입력, 페이지 발견, 표시와 큐 metadata |
| `acquisition.series` | 시리즈 URL·페이지·순서·metadata |
| `acquisition.search` | 검색·범위 요청·결과 표시·큐 동기화 |
| `acquisition.quick` | 빠른 동작, `context.publishWorks(payload)`로 작품 게시 |
| `filters`, `settings` | 설명자에 있는 ID만 구현. 빈 목록은 추가 기능 없음 |
| `slots` | 동일 출처 유형 모듈의 선언형 조각. 독립 동적 slot은 `WebUiSlotContribution` |

각 acquisition은 해당 모드를 선언하지 않으면 생략합니다. initializer의 `AbortSignal`, `isActive()`, `assertActive()`, `onCleanup()`을 사용하세요. 비동기 결과를 쓰기 전에 publication이 활성인지 확인하고 listener, timer, component를 정리합니다.

### 3. 신뢰한 사용자 owner 확인

HTTP controller는 부모의 `RequestOwnerIdentityResolver`로 현재 요청의 소유자를 확인합니다.

```java
RequestOwnerIdentity identity = ownerResolver.resolve(request);
queue.submit(command, identity);
```

JSON, query, 사용자 header의 owner UUID를 믿지 마세요. `pluginId/packageId/generation/publicationId`는 publication의 유효성만 나타내며 사용자 인증 정보가 아닙니다. 실제 다운로더는 파일과 이력·소스 관계 같은 성공 사실이 영속 commit된 뒤에만 완료를 보고합니다. 템플릿의 메모리 내 완료 응답은 결정적 테스트 fixture입니다.

### 4. 큐 취소와 종료

```java
public final class ExampleQueue implements QueueOperations {
    @Override public String queueType() { return "example-download"; }
    @Override public void cancel(String workKey, String ownerUuid, boolean admin) { /* ... */ }
    @Override public int clearAll() { /* ... */ }
    @Override public int clearForOwner(String ownerUuid) { /* ... */ }
}
```

`workKey`는 큐 유형 안에서 안정된 불투명 문자열이며 숫자일 필요가 없습니다. URL path segment에 넣지 마세요. 호스트는 `POST /api/download/queue/{queueType}/cancel`의 JSON으로 원본 key와 publication 식별자를 전달합니다. 프런트는 요청을 직접 만들지 말고 호스트 bridge를 사용합니다.

백그라운드 작업이 전혀 없는 완전 동기 구현만 기본 generation-0 완료 drain을 쓸 수 있습니다. 큐, executor, 지연 callback 등 현재 호출보다 오래 사는 작업이 있다면 다음 순서를 지킵니다.

1. `prepareQuiesce(registeredQueueType)`에서 새 작업 수락을 원자적으로 중지하고 양수 generation의 실제 `QueueDrain`을 반환합니다.
2. 호스트가 drain을 저장한 뒤 `cancelQuiescedTasks()`에서 협력 취소를 보냅니다.
3. 모든 활성 작업이 종료된 뒤에만 drain을 완료합니다.
4. 반복 prepare는 같은 `queueType + generation`을 반환하고 새 인스턴스에는 새 generation을 씁니다.

완료 sentinel로 비동기 종료를 가장하지 마세요. executor, scheduler, HTTP/WebSocket client는 자식 context가 소유하고 닫힐 때 해제해야 합니다.

### 5. UI slot

slot은 다운로드 설명자와 별도로 게시합니다.

```java
@Override
public List<WebUiSlotContribution> uiSlots() {
    return List.of(
            new WebUiSlotContribution(
                    "example-download.settings-card",
                    "settings-card",
                    "/example-download/example-download-type.js",
                    900),
            new WebUiSlotContribution(
                    "example-download.quick-actions",
                    "quick-actions-mine",
                    "/example-download/example-download-ui-slot.js",
                    900));
}
```

동적 모듈은 `PixivVue.mountUiSlot`과 owner 범위 `context.supports(type, mode)`, `context.dispatchQuickAction(action)`, `context.onCleanup(...)`만 사용합니다. Vue runtime을 번들하지 마세요.

### 6. 예약 기능

feature는 순수 값 `ScheduledSourceDescriptor`를 기여하고, `ScheduledSourceExecutor`, `ScheduledWorkExecutor`는 자식 context Bean으로 제공합니다. 브라우저 소스 모듈은 `capture`로 schema/version 있는 정의를 저장하고 `restore`로 복원하며 `summary`로 제한된 표시 구조를 만듭니다.

호스트가 tick, 영구 상태, 동시 실행 제어, 관리 API와 종료 시 작업 완료 대기를 담당합니다. 원본과 작품 실행기는 각 플러그인이 게시하고 철회하며 다운로드 작업대에 의존하지 않습니다. 자격 증명 관리에는 호스트의 `ScheduleService` 대신 `plugin.api.schedule.management.ScheduledTaskCredentials`를 사용하세요. 관리 페이지는 `NavigationPlacements.DESKTOP_SCHEDULE_MANAGE`로 데스크톱 진입점을 제공할 수 있습니다.

입력은 publication 범위 `context.acquisitionInput(mode)`, `context.restoreAcquisition(mode, value)`로만 접근합니다. 현재 타사 중립 어댑터는 `single-import`만 노출합니다. 비공개 DOM이나 모드 globals로 다른 모드를 흉내 내지 마세요.

source executor는 정의 schema·발견·checkpoint, work executor는 payload schema와 동기 실행을 소유합니다. 호스트는 claim, lease, 자격 증명, guard, pending 상태, 취소와 checkpoint CAS를 소유합니다. `COMPLETED`, `ALREADY_COMPLETED`는 파일과 성공 사실이 영속 commit된 뒤에만 반환합니다. 플러그인·executor 부재 시 작업 데이터는 보존한 채 정지하며 삭제하거나 checkpoint를 미리 진행하지 않습니다.

## 플러그인 소유 갤러리

독립 페이지는 `independentPage`라는 API 필드가 아닙니다. 플러그인이 최상위 HTML과 CSS/JS, 라우트·정적 리소스·i18n, controller/API·접근 검사·모델과 탐색 항목을 소유하는 방식입니다. 위 관리자 페이지 예제로 구현하면 publication 중지 시 함께 철회되며 호스트에 유형별 분기가 필요하지 않습니다.

페이지 상단에는 빈 유형 전환 slot을 둡니다.

```html
<nav data-nav-slot="gallery.type-switch"></nav>
```

`navigation()`에는 자신의 항목만 등록합니다.

```java
new NavigationContribution(
        "example-gallery-type-switch",
        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
        "example-download", "nav.gallery",
        "/example-download-gallery.html", "images",
        AccessPolicy.ADMIN, 50)
```

페이지에 Pixiv, Douyin 등 다른 ID를 하드코딩하지 마세요. 호스트가 활성 publication을 모으고 수명 주기 변경에 따라 항목을 추가·철회합니다.

- `/pixiv-gallery.html`은 공식 Pixiv 갤러리이며 타사 유형의 범용 shell이 아닙니다.
- 범용 gallery provider/registry/broker, `/api/gallery/unified/**`, `unifiedGallery` ABI/wire는 제거되어 호환 인터페이스가 없습니다.
- 타사 페이지는 gallery/novel 비공개 구현을 복사하거나 호스트 DB와 app 클래스에 직접 의존하지 않습니다.
- 파일 제공, 삭제, 가시성, 검색, 컬렉션과 통계는 자체 API와 데이터 모델의 책임입니다. SDK에 협업 기능이 없으면 중립 공개 계약을 제안하세요.

Douyin의 `/pixiv-douyin-gallery.html`, 상세 페이지, `/api/douyin/gallery/**`와 `gallery.type-switch`는 전체 예제이며 신규 프로젝트의 출발점은 `download-type-plugin`입니다.

## 갤러리 탐색 슬롯

`NavigationContribution.placements`의 `gallery.sidebar`는 플러그인·다운로드·갤러리 같은 주 페이지, `gallery.sidebar.tools`는 초대 관리·통계·중복 같은 도구를 담습니다. 소설은 `novel.sidebar`, `novel.sidebar.tools`를 씁니다. 각 그룹은 자체 정렬과 요청 신원 필터를 적용합니다. contribution 철회 시 항목을 제거하고 빈 그룹은 숨깁니다. 페이지는 ID 분류 없이 `data-nav-slot`을 선언합니다.

## 설정, 자격 증명과 파일

| 소유 데이터 | 경로 | 접근 |
| --- | --- | --- |
| 호스트 설정·플러그인 활성화 | `config/config.yaml` | SDK 읽기 전용 의미 포트로 필요한 값만 조회. 파일 직접 읽기·쓰기 금지 |
| 플러그인 업무 설정 | `config/plugins/{pluginId}.properties` | 자식 `Environment`, `@Value`, `@ConfigurationProperties`. 직접 관리 시 `RuntimePathProvider` |
| 자격 증명 | `config/credentials/{pluginId}.properties` | 호스트가 암호화 envelope 관리. 선언된 현재 owner 값만 복호화해 자식 context에 주입 |

업무 키에는 플러그인 ID 접두사를 붙입니다.

```properties
example-download.download.directory=
example-download.proxy.mode=inherit
example-download.download.include-cover=false
```

```java
@Bean
ExampleSettings settings(Environment environment) {
    return new ExampleSettings(
            environment.getProperty("example-download.download.directory", ""),
            environment.getProperty("example-download.proxy.mode", "inherit"));
}
```

GUI 필드는 `GuiConfigContribution`으로 선언하고 호스트가 owner별로 저장하게 합니다. 민감한 값과 `PASSWORD`는 일반 properties에 저장하지 않습니다. 플러그인은 주입 값만 읽고 envelope를 읽거나 복호화·수정하지 않습니다. 디렉터리 구분과 암호화가 같은 JVM의 악성 코드를 격리하지는 않습니다.

### 경로와 데이터베이스

주입된 `RuntimePathProvider`는 이미 owner에 묶여 있어 호출자가 플러그인 ID를 넘기지 않습니다.

```java
Path config = runtimePathProvider.configFile("properties");
Path state = runtimePathProvider.stateDirectory();
Path data = runtimePathProvider.dataDirectory();
```

`state/{pluginId}`는 재구성 가능한 상태, `data/{pluginId}`는 관리 데이터·캐시용이며 작품 파일을 넣지 않습니다. 비공개 DB는 선택한 provider를 사용하므로 data 디렉터리만으로 DB 파일 위치를 가정할 수 없습니다.

교체 가능한 DB를 지원하려면 owner-bound `PluginDatabase`를 주입하고 `privateSchema()`에 모든 authoritative 테이블, `privateStorageMigrations()`에 순서 있는 변환을 선언합니다. provider는 context 시작 전에 테이블 생성, 안전한 열 추가와 구조 검사를 수행합니다. 오프라인 마이그레이션도 같은 선언을 사용합니다. 빈 선언이 기존 비공개 DB의 삭제나 누락을 허용하지는 않습니다.

| API | 계약 |
| --- | --- |
| `PluginDatabase.read(work)` | 같은 스레드의 읽기 전용 callback. 쓰기 거부. 반환 후 session 만료, 구체화한 결과만 반환 |
| `PluginDatabase.transaction(work)` | 현재 owner의 비공개 DB 원자적 쓰기. 호스트 Spring transaction과 별개. 실패 후 rollback만 가능 |
| `available()` | 가용 여부. 빈 DB나 쓰기 성공의 증거가 아님 |
| `databaseId()` | 진단용. SQL 방언 분기에 사용하지 않음 |

중립 관계형 연산을 사용하고 방언·연결·자격 증명은 provider에 맡깁니다. 능력 부재·고장은 SQLState `08003`의 `SQLException`입니다. 조회를 빈 결과로 위장하거나 실패 후 부수 효과를 계속하면 안 됩니다. 번들 드라이버나 SQLite SQL은 자동 변환되지 않습니다.

`PluginMainDatabase`는 자기 관리 main 테이블과 `sharedMainStorage()`로 명시 요청한 공개 논리 영역만 제공합니다. 다른 owner의 테이블·비공개 DB는 접근할 수 없고 알 수 없는 영역은 조립을 거부합니다. read/transaction callback은 동기·동일 스레드이며 쓰기는 호스트 transaction에 참여해 파일 게시와 공동 commit할 수 있습니다. DB 부재, 닫힌 context, 잘못된 선언은 거부하고 실패한 transaction은 commit할 수 없습니다.

기존 `PluginDataSource`는 `data/{owner}/plugin.db`의 SQLite 전용 계약입니다. 다른 방언에서는 SQLState `0A000`으로 실패하며 별도 SQLite fallback을 만들지 않습니다. 소비자가 JDBC 호환성을 책임지며 close·unwrap·main DB 접근을 해서는 안 됩니다. 엔진 간 마이그레이션에는 완전한 private schema와 변환 선언도 필요합니다. 설치 성공 대신 실제 마이그레이션 보고서를 확인하세요.

작품 기본 경로는 `DownloadSettings.getRootFolder()`를 상속하고 자기 하위 디렉터리를 관리합니다.

```java
Path defaultOutput = Path.of(downloadSettings.getRootFolder())
        .resolve("example-download")
        .normalize();
```

Douyin은 `{rootFolder}/douyin` 아래 owner별로 정리하고 `config/plugins/douyin.properties`의 사용자 경로가 있으면 우선합니다. 정확한 파일명·하위 경로·마이그레이션은 각 플러그인 책임입니다.

app의 `RuntimeFiles`, `DownloadConfig`, `ProxyConfig`, mapper, `SqlSessionFactory`, main `DataSource`, executor Bean 이름에 의존하지 마세요. 경로·저장에는 Plugin API, 다운로드·프록시 의미에는 `DownloadSettings`, `OutboundProxySettings`를 사용합니다. [저장 원칙](/ko/storage)과 [설정 참조](/ko/configuration)도 확인하세요.

## 외부 HTTP와 WebSocket

순수 JDK 타입의 안정된 factory를 사용합니다.

```java
@Bean(destroyMethod = "close")
OutboundHttpClient exampleHttpClient(OutboundHttpClientFactory factory) {
    return factory.open(OutboundHttpClientProfile.standard(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            OutboundHttpRoute.inherit()));
}
```

`exchangeStream`의 열린 응답은 모든 경로에서 닫아야 합니다. `exchange`는 전체 응답을 버퍼링하고 자동으로 닫습니다. non-2xx도 정상 응답 객체이므로 업무 코드가 해석합니다.

WebSocket은 `OutboundWebSocketClientFactory.open(profile)`을 쓰고 client를 자식 context Bean이 소유·종료합니다. 플러그인은 timeout, redirect, cookie, pool과 중립 route profile을 선언하고 호스트는 transport, 전역·작업 프록시와 `ProxySelector`를 소유합니다. `java.net.http.HttpClient`나 `ProxySelector`를 직접 만들거나 Apache 타입·app HTTP 설정에 의존하지 마세요. 사이트별 인증/header/메시지는 업무 코드 책임입니다. Douyin이 전체 구현 예제입니다.

## 독립 서비스 계약과 조합

아래 API에는 SDK 1.4.0이 필요합니다. `typed-service-plugin` 템플릿으로 API, 계약 운반자, 제공자, 소비자 모듈을 분리합니다. 업무 버전은 계약이 관리합니다. 운반자는 `pixiv.contract-api`와 `pixiv.contract-sha256`으로 원본 API JAR을 지정하며 `process-restart`를 사용합니다. 제공자와 소비자는 운반자에 대한 의존성을 선언하고 API 클래스를 각각 중복 포함하지 않습니다.

`PixivFeaturePlugin.serviceImports()`에 사용할 key를, `serviceExports()`에 구현 Bean을 선언하고 child context에 `PluginServices`를 주입합니다. 생성자에서 접근자를 보관하거나 binding을 등록할 수 있지만 업무 호출에는 소비자의 활성 publication이 필요합니다. 인터페이스와 record/enum은 같은 계약 classloader와 코드 출처에 속해야 합니다. 계약 패키지 간 의존성은 지원하지 않으므로 경계 DTO를 명시적으로 변환합니다.

`find(key)`는 부재 시 빈 값, `require(key)`는 `UNAVAILABLE`을 반환합니다. 두 메서드 모두 구현이 여럿이면 `AMBIGUOUS`입니다. `findAll(key)`는 불변 `List<ServiceProvider<T>>`를 반환하며 빈 목록도 유효합니다. 최대 128개입니다. `service()`는 소비자와 제공자의 정확한 publication에 고정된 프록시이고 `publication()`은 신뢰된 식별 값입니다. 게시 순서는 업무 우선순위가 아닙니다. 소비자가 선택·정렬하며 철회된 프록시는 대체 구현으로 연결하지 않습니다.

유일한 의존성은 `bind(keys, activation)`, 빈 목록을 포함한 구현 집합은 `bindAll(keys, activation)`을 사용합니다. 해당 회차의 `scope.require(key)` 또는 `scope.findAll(key)`에서 프록시를 받고 `scope.acquire(...)`, `scope.guard(...)`로 자원과 보조 콜백을 관리합니다. 구성원이 바뀌면 이전 회차의 진입을 닫고 실제 종료와 정리를 기다린 뒤 다음 회차를 활성화합니다. activation에서 업무 명령을 재실행하면 안 됩니다. `FAILED`, `CLOSE_FAILED`는 명시적으로 `retry()`할 수 있으며 정리 완료를 증명하는 상태는 `CLOSED`입니다.

단순 방송은 독립 `EventSink.onEvent(record)` 계약과 `findAll` 순회로 구현합니다. 각 `ServiceAccessException`은 업무 계약에 따라 처리합니다. best effort는 권위 없는 보조 효과에만 적용합니다. 필수 검증은 쓰기 전에 끝내며 필수 구현의 부재나 검증 실패는 쓰기를 거부합니다. 집합 API는 영속 메시지, 자동 재시도, 역압력, 전달 보장을 제공하지 않습니다.

장기 작업은 업무 계약의 순수 값 프로토콜 `start → jobId → query/cancel/result`를 사용합니다. `PluginRuntimeTaskRegistrar`에 작업을 등록하고 `PluginResources`로 실행기와 자원을 소유하며 제공자 자체에서 취소에 응답합니다. 핸들 취소는 작업 종료 증거가 아니며 호스트는 실제 drain을 기다립니다. 재시작 복구, checkpoint, 멱등성은 데이터 owner의 사설 DB에서 정의합니다. Future, 스레드, 콜백을 서비스 값으로 넘기지 않습니다.

`binding.diagnostic()`은 제한된 크기의 순수 값으로 원인, 상태, 의존성, publication을 제공합니다. 관리 화면의 서비스 관계와 중지 영향은 같은 런타임 사실에서 계산하며 선언된 import와 drain 중인 binding을 포함합니다. 대기, 모호함, 활성화 실패, 실행기 거부, 정리 실패를 구분합니다. `truncated`는 표시가 불완전하다는 뜻이며 의존성 부재를 뜻하지 않습니다. 진단 조회는 업무를 재시도하거나 생명주기를 변경하지 않습니다.

## GUI 필드 없이 사용하는 자격 증명과 작업 검증

`credentialKeys()`에 `my-plugin.token`처럼 owner 접두사가 있는 키를 선언하고 `PluginCredentials`를 주입합니다. `read(key)`는 `Optional<String>`, `update(Map)`는 원자적 병합입니다. 생략한 키는 유지하며 공백 값은 삭제합니다. `delete(key)`는 멱등적입니다. 선언·갱신은 최대 128개, 값당 UTF-8 64 KiB, 한 번에 1 MiB까지입니다. 미선언 키와 오래된 generation 핸들은 거부되며 다른 owner를 지정할 수 없습니다.

자격 증명 키를 일반 GUI 필드로도 선언할 수 없습니다. 같은 키의 필드는 민감 필드여야 합니다. 충돌하면 호스트는 해당 owner의 자격 증명 및 데스크톱 설정 스냅샷을 거부하고 발견된 민감 키의 마스킹을 유지합니다. GUI의 자격 증명 스냅샷, 쓰기, 실패 롤백은 하나의 owner 잠금 안에서 실행합니다. 로드와 다시 로드에서도 자격 증명 읽기와 마이그레이션 판단을 같은 잠금 안에서 수행하여 오래된 스냅샷이나 평문이 성공한 프로그램 갱신을 덮어쓰지 않도록 합니다. 자격 증명을 포함하는 환경 내보내기는 GUI가 없는 선언된 키도 포함하며 암호화가 필수입니다. 자격 증명을 제외하면 해당 값은 내보내지 않으며 복원 시에도 owner와 설치 세대를 확인합니다.

저장소는 기존 호스트 암호화와 파일 잠금을 사용합니다. 기존 GUI의 민감한 필드도 같은 선언과 마스킹 범위에 포함됩니다. 저장해도 기존 `@Value` 또는 설정 객체를 자동으로 다시 바인딩하지 않습니다. 필요할 때 읽거나 기존 재시작 정책을 따릅니다. 평문을 서비스 DTO, 예외, 로그, 진단에 넣지 마세요.

플러그인의 자식 context에 `OperationVerification` Bean을 등록합니다. `policyId()`는 안정적인 정책 키입니다. 부작용이 없는 `policy()`는 규칙 리비전, 컨텍스트 schema, 상호작용 프로토콜 및 구조화된 리소스 profile을 반환합니다. 규칙이나 리소스가 바뀌면 리비전을 바꾸고 페이지를 다시 게시합니다. `generation()`은 자격 증명 세대를 나타내며 이전 챌린지를 무효화합니다. `verify()`는 필요한 순수 값과 불투명 proof만 받고 `APPROVED`, `REJECTED`, `CHALLENGE`를 반환합니다. 비밀번호, Cookie, 전체 요청, 업무 콜백은 전달받지 않습니다.

업무 owner는 `serviceImports()`에 `OperationVerification.IMPORT`를 선언하고 자신에게 연결된 `OperationGuard`를 주입합니다. 기존 신원을 사용하는 업무 API에서는 신원, 권한, CSRF, 요청 제한과 입력을 확인한 뒤 `execute(operationId, subject, context, token, proofs, action)`을 호출합니다. 작업 ID는 해당 owner 소속이어야 하며 일반 업무의 subject는 신뢰할 수 있는 신원 확인 결과에서 가져옵니다. 로그인 준비는 형식 검사를 마친 사용자 이름을 사용하지만 인증된 신원으로 취급하지 않습니다. 모든 필수 정책을 통과한 경우에만 owner 내부에서 action을 한 번 실행합니다. 리스는 업무가 실제로 끝날 때까지 유지됩니다. 능력 부재, 시간 초과, 세대 변경은 거부되며 업무 실패를 자동 재시도하지 않습니다. 보조 콜백용 `ServiceBindingScope.guard()`를 사용자 명령에 사용하지 마세요.

준비 엔드포인트는 `OperationGuard.prepare(operationId, subject, context)`를 호출하고 업무를 실행하지 않습니다. 제출은 계속 `execute`로 재검증합니다. 브라우저 `createOperationTarget`의 세 번째 인수는 취소 신호를 받는 폼 소유자의 준비 콜백입니다. `ready()`가 true일 때만 `request()`로 제출 데이터를 얻을 수 있습니다. 준비와 제출은 같은 주체와 컨텍스트에 연결해야 합니다. 입력 변경 시 `reset()`, 인증 무효화 시 `invalidate(id)`로 준비를 취소하고 증명을 지웁니다. 업무는 자동 재전송하지 않습니다. 사용법은 [페이지 편집기](page-editor.md)를 참고하세요.

페이지 owner는 `EditablePage.targets`에 `PageInteraction.Target`을 선언하고 `PageComponent` 참여 선언에서 정책과 프로토콜을 지정합니다. 호환 대상이 하나면 자동 연결하고, 여러 개면 선택해야 합니다. 대상이 없으면 초안만 저장할 수 있습니다. 컴포넌트는 먼저 `context.interaction.prepare()`를 호출한 후 `context.interaction.onState()`로 자신의 챌린지를 받고 `respond(id, proof)`로 응답합니다. 상태가 지워지거나 인스턴스가 삭제되면 Worker, 요청, 리스너를 종료해야 합니다. 폼 owner는 `/js/pixiv-pages.mjs`의 `createOperationTarget`을 사용하고 페이지 마운트에 대상을 전달합니다. SDK 타입은 `page-interaction.d.ts`, `operation-verification.d.ts`, `page-components.d.mts`에 있습니다. 일반 `PagePort` 연결은 DAG를 유지합니다.

로그인 제출은 요청 제한, 검증, 비밀번호 확인, 세션 생성 순서입니다. `setup.login-verification-policies`는 최대 16개의 정적 필수 정책을 지정합니다. 빈 값은 정적 요구만 지우며 페이지 게시는 설정된 정적 요구를 제거할 수 없습니다. `POST /api/auth/login/prepare`는 사용자 이름과 rememberMe만 보내며 로그인 요청 제한을 공유합니다. 준비가 성공하면 200과 `token`, `revision`, 정책별 `challenges`를 반환합니다. 모든 증명을 얻은 후 로그인 요청에 `operationToken`, `operationProofs`와 자격 증명을 보냅니다. 증명 없이 직접 제출하면 409 챌린지를 반환합니다. 거부는 403, 필수 능력 이용 불가는 503, 오래된 챌린지는 409이며 실패 시 Cookie를 생성하지 않습니다. 챌린지는 주체, 작업, 컨텍스트, 규칙, 자격 증명, provider 및 공식 리비전에 고정되고 5분 뒤 만료되며 한 번만 소비됩니다. 챌린지/proof는 각 UTF-8 16 KiB, 합계 64 KiB, 대기 중 최대 256개입니다.

고정 `login.verification` UiSlot도 같은 참여 프로토콜을 선언하고 인스턴스 전용 상호작용 채널을 받습니다. 클라이언트의 성공 이벤트는 권한을 부여하지 않습니다. `PagePublications`가 페이지, 연결, 작업 요구, 리소스 승인을 하나의 리비전으로 보관합니다. 사전 검사는 외부 업체를 호출하지 않으며 커밋 때 재검증하고 영향받는 작업의 종료를 기다립니다. 리소스 출처는 관리자의 명시적 승인이 필요합니다. 연결되지 않은 페이지나 사용자 컴포넌트 샌드박스에는 권한이 상속되지 않습니다. [페이지 편집기](page-editor.md)와 Turnstile, ALTCHA SHA-256, PBKDF2를 포함한 [독립 CAPTCHA 예제](https://github.com/Sywyar/PixivDownloader/tree/master/examples/captcha-plugin)를 참고하세요.

## 일반 슬롯과 페이지 구성 요소

`/js/pixiv-ui-slots.js`를 로드하고 `data-ui-slot` 지점을 추가한 다음 `PixivUiSlots.connect({targetPrefix, properties, regions, onEvent, i18n})`, `refresh()`를 호출합니다. 기본 목록은 기존 경로 권한을 유지합니다. 공개 페이지에는 해당 대상으로 제한한 공개 목록이 필요합니다. 모듈은 스크립트 평가 중 `registerModule(moduleUrl, mount)`를 호출하고 선택적으로 `update(properties)`, `destroy()`를 반환합니다.

`mount(root, context)`는 순수 속성, 이벤트, 명시적 region을 사용하며 다른 페이지의 내부 전역 값을 읽지 않습니다. `context.fetch`는 출발 publication에 고정되고 목적지 API의 권한 검사도 유지합니다. `signal`, `on`, `interval`, `onCleanup`, `loadScript`, `loadStyle`은 인스턴스와 함께 정리합니다. 목록은 2초마다 갱신하며 숨겨진 페이지에서는 새 갱신을 멈춥니다. 여러 인스턴스가 모듈 로드를 공유해도 하나를 닫는 것이 다른 인스턴스를 철회하지 않습니다. 비동기 처리 후 signal을 확인하고 늦은 결과로 이전 DOM을 복원하지 마세요. 페이지를 떠날 때 인스턴스, 리스너, 타이머, 요청을 해제합니다. 타입은 `META-INF/pixivdownload/web/ui-slots.d.ts`를 참조하세요.

같은 target의 기여는 목록의 `order`를 따르며 다시 활성화하거나 추가할 때 새 노드를 올바른 위치에 삽입하여 변경되지 않은 인스턴스와 포커스, 선택 영역을 유지합니다. 기존 노드를 이동해야 하면 `moveBefore` 지원 여부를 확인하고 입력란과 편집 가능한 텍스트의 포커스 및 선택 영역을 보존합니다. 이 API가 없는 브라우저에는 호환 경로를 사용합니다. 늦은 목록 응답은 새 인스턴스를 철회할 수 없습니다. `root` 밖에 노드를 만들면 해당 노드 참조를 정리하는 함수를 즉시 `onCleanup`에 등록하세요. 컨트롤러를 만든 뒤에는 해제 함수를 별도로 등록하여 스크립트 실패나 로딩 중 철회에서도 자원을 해제합니다.

다운로드 작업대의 큐 슬롯은 기존 Vue/큐 전용 프로토콜을 유지합니다. 일반 슬롯은 Swing/Compose 공통 데스크톱 뷰 프로토콜이 아닙니다. 소설·시리즈 페이지는 properties, regions, 이벤트를 AI/TTS 슬롯에 제공하고 업무 모델은 양측이 정의합니다.

편집기 포트는 `PagePort(name, contractId, schema)`로 선언합니다. 연결 양쪽의 버전 계약 식별자와 해석한 Schema가 같아야 합니다. Schema는 SDK 생성기가 지원하는 부분집합입니다. 알 수 없는 포트, 중복 간선, 방향 순환은 저장 시 거부하며 런타임에도 값을 검증합니다. mount 중 이벤트는 모든 인스턴스의 mount가 끝난 뒤 순서대로 전달하며 A→B→C를 지원합니다. 나중에 추가한 인스턴스에 과거 이벤트를 재전송하지 않습니다. 페이지 큐는 256개·16 MiB, 연속 전달은 4096회까지이며 초과하면 명시적으로 실패합니다. 프로젝트 데이터, 순수 값, 제한된 스타일만 저장합니다. 업무 요청은 생성 클라이언트와 인스턴스 fetch를 사용합니다.

## 여러 원격 서비스, 값 인코딩과 계약 변경

독립 API 모듈에서 Schema를 생성하고 소비자 모듈의 전체 서비스 집합을 한 번에 지정합니다. 아래 디렉터리, classpath, 클래스 이름을 자신의 값으로 바꾸세요. classpath 구분자는 Windows에서 세미콜론, Unix에서 콜론입니다.

```text
java -jar sdk-tools.jar schema <api-module> <classpath>
java -jar sdk-tools.jar remote <consumer-module> <classpath> example.one.Service example.two.Service
java -jar sdk-tools.jar contract-evolution <old-api.jar> <new-api.jar> <new-report.json>
```

단순 이름이 같아도 전체 클래스 이름으로 Java 어댑터와 `web/generated/<전체이름>.mjs`, `.d.mts`를 구분합니다. `remote`는 최대 128개 서비스를 받으며 빈 목록은 전체 생성물 철회입니다. 서비스를 제거하면 이전 class, JS, 선언도 정리합니다. 실패하면 관리 중인 출력과 완료 표시를 철회하므로 패키징은 `target/remote-generation.complete`를 확인해야 합니다. 전체 컴파일 이후 표시를 게시하지만 여러 파일 쓰기는 하나의 파일 시스템 트랜잭션이 아닙니다. 전체 출력은 16 MiB, 파일당 1 MiB까지입니다.

일반 JSON 정수는 Java 타입 범위와 JS 안전 정수 범위의 교집합으로 제한합니다. 전체 long 범위가 필요한 record 속성에 `@ValueProperty(encoding = ValueEncoding.DECIMAL_STRING)`을 명시합니다. UUID, Instant, Duration, float, double은 고정된 무손실 문자열인 소문자 UUID, `초:9자리나노초`, IEEE 754 고정 길이 소문자 16진수로 인코딩합니다. 비정규 문자열, 범위 초과, 비유한 부동소수를 거부하며 음의 0과 나노초를 보존합니다. JS Date로 정밀도를 버리지 마세요. 생성 클라이언트의 `wireValues.int64/uuid/instant/duration/float32/float64`로 타입 값을 만듭니다. 다른 타입은 독립 계약의 지원 DTO로 명시적으로 변환하며 임의 serializer를 등록하지 않습니다.

`contract-evolution`은 클래스를 로드하지 않고 classfile을 읽어 소스 호환성, 바이너리 링크, 새 호스트의 이전 구현 호출을 각각 보고합니다. 업무 동작은 항상 `NOT_ASSESSED`입니다. 인터페이스 추상 메서드 추가는 이전 링크를 유지하더라도 새 호출 시 미구현 메서드에 도달할 수 있습니다. 재컴파일, 원본 바이트를 유지한 이전 플러그인 로드, 실제 호출 검증은 별도로 필요합니다. 종료 코드 0은 동작 호환성 증명이 아닙니다. 출력 파일은 아직 없어야 합니다. `verify-sdk-usage`는 최소 중앙 SDK 선언을 확인하는 별개의 검사입니다.


## 외부 프로젝트 유지 관리와 SDK 업그레이드

사전 릴리스는 예를 들어 properties의 `plugin.requires==7.2.3-rc.4`처럼 전체 버전을 요구합니다. 안정 SDK는 `MAJOR.MINOR`입니다. 컴파일 의존성, descriptor와 runtime을 맞추며 RC 사이, RC와 안정판 사이 호환을 가정하지 마세요. 과거 major/minor 선언은 컴파일한 RC를 식별하지 못하므로 검증 후 새 플러그인 버전을 게시하고 기존 첨부를 바꾸지 않습니다.

원래 프로젝트와 `.dev/`는 보존하고 새 SDK를 별도 압축 해제합니다. 소스·Javadoc을 비교한 뒤 tools, 계약 리소스와 manifest를 함께 갱신하고 빌드·IDE 설정을 병합합니다. 비공개 DB 변환은 플러그인 책임입니다. 알 수 없는 형식·실패 시 기존 데이터를 보존하고 해당 능력을 중지해야 합니다. 호스트 패키지 transaction은 업무 데이터를 rollback하지 않습니다.

Windows는 `mvnw.cmd clean verify`, 다른 환경은 `./mvnw clean verify`로 검증한 뒤 같은 wrapper로 `verify exec:exec@sdk-run`을 실행합니다. 관리자 플러그인 관리에서 시작·비활성화·재시작과 정책이 허용하는 reload를 확인하세요. 라우트 철회, 오래된 publication 무효화, 실제 task drain, 데이터·미완료 작업 보존을 확인합니다. `process-restart` 교체는 완전 종료 후 로드 버전을 확인하고 `exec:exec@sdk-stop`으로 개발 인스턴스를 끝냅니다. 개발 디렉터리 full-trust 실행만으로 패키지 worker를 검증할 수 없습니다.

`DownloadObserver`는 영속 replay 없는 동기 best-effort 관찰, `DownloadAdmissionPolicy`는 부수 효과 전 허용/거부이며 요청을 재작성하지 않습니다. 둘은 선택적 full-trust Bean으로 게시·철회됩니다. `WorkFileImporter`는 원본 파일의 읽기 전용 참조를 등록하며 유형 owner 부재를 성공으로 보고하면 안 됩니다. 실제 선택한 릴리스 Javadoc을 확인하세요.

제출 오류는 실패한 단계를 먼저 확인합니다. 해시 불일치는 같은 릴리스를 다시 받고, candidate 부재는 기본 브랜치의 현재 CI와 `tools/candidate-projects.json`을 확인합니다. 신원·push 오류는 계정·저장소·권한을 확인하세요. 검토·소유권·원격 상태가 바뀌면 원래 요청을 다시 불러오며 응답 유실을 새 제출로 자동 복구하지 않습니다. 진단 공유 시 ID·버전·소스 SHA·SDK ID·오류 코드는 남기고 자격 증명·비밀 키·개인 경로는 제거합니다.

## 빌드·테스트·디버그·설치

### 필요한 테스트

템플릿의 descriptor/provider/feature 일치, 명시적 context와 controller 조립, 라우트·리소스·i18n·schema·다운로드 게시, 큐 owner·opaque key·clear·drain, 프런트 `node --check`와 동작 테스트, 루트 descriptor와 thin-JAR 경계를 유지합니다.

```powershell
mvn -f plugin-templates/pom.xml clean verify
```

프런트 계약은 Java에서 문자열 검색만 하지 말고 Node로 실행하세요.

### 로컬 개발

JDK 17과 Node.js의 `java`, `node`를 `PATH`에 둡니다. IDE import는 의존성을 해석할 뿐이며 명시적 Run/Debug가 현재 플러그인 빌드, 고정 runtime 준비, 전체 앱 실행을 수행합니다. 빌드 실패 시 진행하지 않습니다. Maven Wrapper가 고정 Maven을 준비하므로 호스트 checkout이나 JAR 수동 복사가 필요하지 않습니다. 앱 초기 설정은 호스트 setup을 따릅니다.

| IDE | 가져오기 | 실행 | 디버그 |
| --- | --- | --- | --- |
| IntelliJ IDEA | 루트 `pom.xml` 열기 | `Developer Mode` Run | 같은 설정 Debug |
| VS Code | 디렉터리 열기·권장 Java Extension Pack 설치 | `Tasks: Run Task > Run Plugin` | `Run and Debug > Debug Plugin` |
| Eclipse | `Import > Existing Maven Projects` | `eclipse/Run Plugin.launch` | `eclipse/Debug Plugin.launch` group |

IntelliJ `Developer Mode`는 native Application입니다. Maven의 `test-compile exec:exec@sdk-prepare` 후 IDE가 JVM을 직접 띄워 호스트, 공식 플러그인과 현재 `target/classes`를 실행합니다. `ExampleMinimalPlugin.java`의 `routes()`에 breakpoint를 두세요. 고정 포트·원격 attach는 필요 없습니다. `src/test/java/sdk/DevelopmentLauncher.java`는 배포 JAR에 포함되지 않습니다. 설정은 도구 JAR을 classpath에 추가해 Spring Boot nested JAR 해석을 지원합니다.

이 설정은 명시적 개발 모드로 현재 소스를 `host-process-full-trust` 실행하며 상태에도 표시합니다. 원본 descriptor는 바뀌지 않습니다. 매 실행 시 다시 컴파일하고 `Stop Plugin` 또는 Application 종료로 끝냅니다.

VS Code, Eclipse와 CLI는 현재 JAR을 설치해 선언된 실행 모드를 유지합니다. 기본 debugger `127.0.0.1:5005`는 declarative worker 또는 full-trust 호스트에 연결합니다. `Stop Plugin`이나 group 전체를 종료해야 합니다. debugger 연결 해제만으로는 앱이 끝나지 않습니다.

Windows:

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd verify exec:exec@sdk-debug
.\mvnw.cmd exec:exec@sdk-stop
```

Linux/macOS:

```bash
./mvnw verify exec:exec@sdk-run
./mvnw verify exec:exec@sdk-debug
./mvnw exec:exec@sdk-stop
```

`sdk-debug`는 IDE attach를 기다리며 debugger를 열지 않습니다. `clean verify`는 검증, `exec:exec@sdk-prepare`는 runtime 준비만 수행합니다. 기본 아티팩트는 `target/example-minimal-plugin-0.1.0.jar`입니다. 현재 JAR 빌드 후 도구를 직접 호출할 수도 있습니다.

```text
java -jar tools/sdk-tools.jar run <absolute-project-path> <absolute-current-plugin-JAR> --no-gui
java -jar tools/sdk-tools.jar debug <absolute-project-path> <absolute-current-plugin-JAR> --debug-port=5005
java -jar tools/sdk-tools.jar stop <absolute-project-path>
```

`--debug-connect`는 이미 대기 중인 IDE에 연결합니다. run/debug는 프로젝트 안이며 `.dev/` 밖에 있는 아티팩트를 받고 선언 모드와 현재 SHA-256을 확인합니다. 공식 플러그인은 개발·패키지 검증 모두 서명과 provenance를 확인합니다. 번들 플러그인과 충돌하지 않는 고유 ID를 쓰세요.

### 런타임 캐시와 프로젝트 데이터

`sdk-project.json`과 릴리스 `sdk-release.json`은 동일한 SDK, 호스트, 공식 manifest, runtime ZIP의 ID·크기·SHA-256을 기록합니다. 별도 Release 첨부 ZIP은 명시적 준비 시 내려받아 `~/.cache/pixivdownloader-sdk/`의 검증된 바이트를 재사용합니다. 캐시 삭제 후에도 같은 바이트를 선택하며 자원 부재·해시 불일치는 실패합니다.

실행마다 비공개 runtime 복사본을 만들고 호스트와 공식 플러그인을 검사합니다. 캐시에는 앱 상태가 없습니다. `.dev/`는 설정·DB·로그·다운로드·실행 복사본을 보관하며 runtime ZIP 해시별로 설정과 상태를 분리합니다. 이 환경은 호스트·공식 플러그인 자동 업데이트가 꺼져 있고 일반 설치 데이터와 분리됩니다.

종료 후 `.dev/`를 삭제하면 그 안의 다운로드를 포함한 개발 데이터가 초기화됩니다. 직접 호출 시 JVM `-Dpixivdownload.sdk.cache-dir=<directory>`로 캐시를 바꿀 수 있으나 프로젝트 runtime은 계속 분리됩니다. 오프라인에는 runtime과 빌드 의존성 캐시가 모두 필요합니다. Maven `-o`, Gradle `--offline`을 쓰며 불완전 캐시는 실패합니다. SDK 변경은 대응 manifest가 있는 새 패키지로 소스를 옮겨 수행합니다.

import·컴파일·완전 실행은 별도 검증입니다. 사용하는 IDE에서 시작, source breakpoint와 정상 종료를 직접 확인하세요. Maven 성공은 IDE GUI 검증이 아닙니다. Windows 패키지 worker 생성 중 경로가 너무 깊어 오류 267이 나면 더 짧은 경로로 옮깁니다. 다른 OS도 해당 환경에서 검증해야 합니다.

Gradle/sbt 예제는 `runPlugin`, `debugPlugin`을 사용합니다. Gradle은 `stopPlugin`, sbt는 실행 중 build 프로세스가 점유되므로 다른 터미널의 예제 디렉터리에서 `java -jar ../../tools/sdk-tools.jar stop .`으로 중지합니다.

주 저장소 공식 플러그인의 개발 모드는 현재 모듈 `target/classes`를 로드합니다.

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

Douyin은 독립 SDK 프로젝트이며 이 명령이 외부 프로젝트를 자동 발견하지 않습니다. 주 저장소 IDE 개발에는 커밋된 IntelliJ/VS Code/Eclipse `Developer Mode` 공유 설정을 사용합니다.

디버그 중 `/plugin-manage.html`의 `STARTED`와 generation, 중지 후 라우트 철회·재시작 복구, 새 JS/CSS/i18n 로드를 확인합니다. `log/`의 라우트 충돌, 중복 ID, context, 버전·서명·drain 오류를 확인하세요. 종료 후 비동기 작업이 파일을 쓰거나 페이지 callback을 호출하거나 이전 classloader를 보유하면 안 됩니다.

### 패키지 형식

기본은 루트 `plugin.properties`가 있는 thin PF4J JAR입니다. `BOOT-INF/`, `lib/*.jar`, plugin-api/core-api/PF4J/Spring/Jackson/Servlet/호스트 클래스 복사본을 포함하지 않습니다.

비공개 의존성이 필요하면 JAR-with-lib를 사용할 수 있습니다. 루트에 descriptor·플러그인 클래스·리소스를 두고 비공개 JAR을 `lib/*.jar`에 둡니다. 공유 계약은 shade·번들하지 않습니다. 이 형식은 구조 검사와 격리 classloader 로드 테스트를 추가해야 합니다. 공식 기본 형식은 ZIP이 아닌 `.jar`입니다.

## 마켓 콘텐츠 제출

문서 지원 SDK 도구는 candidate의 고정 소스 commit에서 `CHANGELOG.md`를 읽고 `## [v8.2.6] - 2031.3.2` 같은 2단계 제목의 정확한 버전을 찾습니다. `### Features`, `### Bug Fixes` 분류를 보존합니다. 일치 없음·중복 시 다른 파일, 수동 메모 또는 명시적 생략을 선택합니다. README는 저장소·로컬 파일·여러 줄 입력의 UTF-8 Markdown/HTML을 받을 수 있습니다. 전체 CHANGELOG와 릴리스 노트는 별도 첨부입니다.

선택한 기본 언어로 문서를 만들고 preview 확인 뒤 바이트를 고정합니다. 상대 이미지는 선택 root 안에 있어야 하며 외부 이미지 다운로드는 명시 확인을 받습니다. 링크는 용도 preset·언어별 label·명시적 빈 상태가 있는 편집 목록입니다. [커뮤니티 가이드](https://github.com/Sywyar/PixivDownloader-community-plugins/blob/master/README_en.md#submissions-and-version-management)에 입력·복구 절차가 있습니다.

버전의 선택적 `content`는 `readme`, `changelog`, `releaseNotes` locale map을 담습니다. 문서는 `format`, `asset`, 선택적 `sourcePath`·`sourceUrl`, 이미지 `resources`를 기록합니다. 저장소 문서의 sourceUrl은 고정 소스 commit에 묶여 상대 링크를 해석합니다. 로컬·직접 입력·필드 없는 과거 기록은 출처를 추정하지 않습니다. 안전한 HTML anchor와 Markdown 제목으로 내부 이동하고 외부 링크는 새 창에서 엽니다. asset 필드는 `name`, `url`, `mediaType`, `size`, `sha256`입니다. market `links` 누락은 homepageUrl fallback, 빈 배열은 링크 없음입니다. 전체 계약은 같은 SDK의 community Schema를 따릅니다.

문서·이미지·패키지는 같은 버전 Release에 게시되고 서명한 catalog가 참조를 묶습니다. 클라이언트는 필요할 때 크기·해시를 확인하고 정제된 HTML을 격리 정적 문서로 표시합니다. JAR 서명이 문서를 덮는 것은 아니며 검토 참조와 catalog가 연결합니다. community 도구의 SDK pin 변경은 공개 바이트 검증 후 수행하고 호스트에도 문서 지원이 있어야 합니다. 마켓 metadata 추가만으로 `plugin.requires`를 올릴 필요는 없습니다. SDK 1.1 호스트도 `requires=1.0`을 받습니다.

## 서명과 게시

### 게시자 키와 커뮤니티 작업 서명

`pixivdownload-plugin-signature` CLI 실행에는 JDK 17이 필요합니다. 소스 루트에서 빌드하고 실제 생성 JAR 경로로 바꿔 호출합니다.

```text
./mvnw -pl pixivdownload-plugin-signature package
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool <command>
```

- `keygen --directory <new-directory>`는 PKCS#8 `private-key.pem`과 SPKI `public-key.pem`을 만듭니다. 디렉터리·개인 키 접근은 현재 사용자로 제한하며 기존 디렉터리는 덮어쓰지 않습니다. 저장소·임시 경로 밖에 보관하고 둘 다 백업합니다.
- `public-key --public-key <public-key.pem> --key-id <id> --out <public.json>`은 SPKI Base64와 SHA-256 지문을 내보냅니다. 대응 공개 키를 읽으며 개인 키에서 공개 키를 유도하지 않습니다.
- `community-operation --operation <PUBLISHER_KEY_ROTATION|VERSION_STATUS_REQUEST|OWNERSHIP_TRANSFER> --canonical-body <canonical-body> --request-id <SHA-256> --key-id <id> --private-key <private-key.pem> --out <sig.json>`은 고정 SDK가 만든 JCS body를 서명합니다. body digest와 requestId가 같아야 하며 출력에는 분리 서명만 있습니다.

공개 키 내보내기와 작업 서명은 기존 출력 파일을 덮어쓰지 않습니다. 패키지는 `artifact`로 서명하며 전체 인수는 `--help`를 참고하세요. 비밀 키를 Git이나 제출 첨부에 넣지 마세요.

### 아티팩트 서명

파일명은 실제 경로로 바꾸고 따옴표를 유지합니다. Ed25519 PKCS#8 PEM 비밀 키는 저장소·빌드 출력 밖에 둡니다. 저장소 설정은 Base64 X.509 SubjectPublicKeyInfo 공개 키를 사용합니다.

```powershell
java -cp "signature-tool.jar" `
  top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool `
  artifact `
  --artifact "plugin.jar" `
  --plugin-id example-download `
  --version 0.1.0 `
  --key-id example-2026 `
  --private-key "ed25519-pkcs8.pem" `
  --out "plugin.jar.sig"
```

출력 JSON은 `formatVersion`, `algorithm=Ed25519`, `keyId`, `value`를 포함합니다. 정확한 바이트 수와 SHA-256도 기록합니다.

```powershell
$artifact = Get-Item -LiteralPath "plugin.jar"
$artifact.Length
(Get-FileHash -Algorithm SHA256 -LiteralPath $artifact.FullName).Hash.ToLowerInvariant()
```

### Catalog manifest

schema 1의 최상위는 `schemaVersion`, `generatedTime`, `entries`입니다. 구조 예제의 도메인·크기·해시·서명·시간을 같은 실제 아티팩트의 값으로 바꾸세요.

```json
{
  "schemaVersion": "1",
  "generatedTime": "2026-08-10T00:00:00Z",
  "entries": [
    {
      "pluginId": "example-download",
      "displayNamespace": "example-download",
      "displayNameKey": "plugin.name",
      "descriptionKey": "plugin.summary",
      "market": {
        "displayName": {"zh": "示例下载", "en": "Example download"},
        "summary": {"zh": "示例下载类型", "en": "Example download type"},
        "description": {"zh": "插件详细说明", "en": "Plugin description"},
        "author": "Example Developer",
        "sourceType": "community",
        "category": "download",
        "tags": ["download"],
        "homepageUrl": "https://example.com/plugin",
        "license": "MIT",
        "latestVersion": "0.1.0",
        "updatedTime": "2026-08-10T00:00:00Z",
        "iconToken": "download",
        "colorToken": "green",
        "recommended": false,
        "officialRequired": false,
        "defaultInstalled": false
      },
      "packages": [
        {
          "version": "0.1.0",
          "packageUrl": "https://plugins.example.com/example-download-0.1.0.jar",
          "expectedSizeBytes": 12345,
          "sha256": "LOWERCASE_SHA256_HEX",
          "signature": {
            "formatVersion": 1,
            "algorithm": "Ed25519",
            "keyId": "example-2026",
            "value": "BASE64_SIGNATURE"
          },
          "signatureUrl": "https://plugins.example.com/example-download-0.1.0.jar.sig",
          "requiredSdk": "1.3",
          "dependencies": [],
          "releasedTime": "2026-08-10T00:00:00Z",
          "changeNotes": ["Initial release"],
          "channel": "stable",
          "deprecated": false
        }
      ]
    }
  ]
}
```

`market`은 표시·검색·정렬용이고 보안 승인에 쓰지 않습니다. manifest와 `packageUrl`은 HTTPS여야 합니다. 설치 시 아티팩트 크기·SHA-256·구조화 서명·내부 descriptor가 기준입니다. 원본 manifest 바이트에 서명합니다.

```powershell
java -cp "signature-tool.jar" `
  top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool `
  manifest `
  --manifest "manifest.json" `
  --repository-id example `
  --key-id example-2026 `
  --private-key "ed25519-pkcs8.pem" `
  --out "manifest.json.sig"
```

manifest, URL에 `.sig`를 붙인 서명, 아티팩트와 선택적 아티팩트 서명을 게시합니다. 서명 후 JSON을 재포맷하지 마세요. 검증 subcommand는 다음과 같습니다.

```text
verify-manifest --manifest "manifest.json" --signature "manifest.json.sig" --repository-id <id> [--policy official|custom]
verify-artifact --artifact <jar> --signature <sig.json> --plugin-id <id> --version <version> --expected-size <bytes> --sha256 <hex> [--policy official|custom]
```

custom root는 `--trusted-key-id`, `--trusted-public-key`도 전달합니다. 선택 필드는 `--trusted-algorithm`, `--trusted-state`, `--trusted-publisher`, `--trusted-label`, `--trusted-official`입니다.

## 사용자 지정 저장소

64 KiB 이하 strict UTF-8 JSON `repository.json`을 게시합니다. GUI 설정 → 플러그인 마켓 설정 → 추가 → 저장소 설명자 사용에 공개 HTTPS URL을 넣습니다.

```json
{
  "schemaVersion": 1,
  "repositoryId": "example.plugins",
  "displayName": "Example Plugins",
  "publisher": {
    "id": "example",
    "displayName": "Example Publisher",
    "homepageUrl": "https://example.com/plugins"
  },
  "catalog": {
    "protocol": "manifest-v1",
    "endpoint": "https://plugins.example.com/manifest.json"
  },
  "networkProfile": "DIRECT_STRICT",
  "revocationsUrl": "https://plugins.example.com/revocations.json",
  "updateProofUrl": "https://plugins.example.com/repository-update.json",
  "trustedKeys": [{
    "keyId": "example-2026",
    "algorithm": "Ed25519",
    "publicKeySpkiBase64": "BASE64_X509_SUBJECT_PUBLIC_KEY_INFO",
    "state": "ACTIVE",
    "publisher": "Example Publisher",
    "trustLabel": "Example release key"
  }]
}
```

`trustedKeys`에는 Ed25519 SPKI 공개 키 항목이 1개 이상 4개 이하여야 하며 각 `keyId`는 중복될 수 없습니다.

첫 신뢰에 `repository.json.sig`를 의존하지 마세요. 자신을 소개하는 새 키의 서명은 자기 선언입니다. 앱은 descriptor 해시·게시자·모든 네트워크 host·전체 `SHA-256(SPKI DER)` 지문을 표시합니다. 확인 때 다시 내려받은 바이트 해시가 같아야 하며 저장된 설정은 재시작 후 적용됩니다. custom 저장소는 공식 root를 상속하지 않습니다. `official`, `configured`, `community`는 예약 ID입니다.

`networkProfile`은 `DIRECT_STRICT`, `GITHUB_RELEASES`만 받습니다. 전자는 선언한 공개 HTTPS host만 허용하고 redirect를 금지합니다. 후자는 고정 GitHub 경계 안의 1회 redirect를 허용합니다. descriptor로 SSRF 검사 비활성화, 사설 주소 허용, 임의 프록시·redirect 목록을 지정할 수 없습니다.

작은 저장소는 `manifest-v1`, 큰 저장소는 API base를 endpoint로 하는 `paged-v2`를 사용할 수 있습니다.

```text
GET {endpoint}/plugins?cursor=&limit=&query=&category=&publisher=&channel=
GET {endpoint}/plugins/{pluginId}?cursor=&limit=
GET {endpoint}/plugins/{pluginId}/versions/{version}
```

기본 page size는 24, 최대 100입니다. 응답은 `generation`, 불투명 cursor를 사용하며 목록 512 KiB·상세 256 KiB 제한입니다. 설치는 저장소 ID·플러그인 ID·버전만 제출합니다. 호스트가 version endpoint를 다시 확인하고 고정 descriptor·크기·해시·게시자 서명과 비교합니다.

첫 신뢰 후 기존 신뢰 키로 단조 증가하는 `repository-update-v1` proof를 서명해 descriptor·키를 교체할 수 있습니다. 보안 철회는 더 높은 sequence의 `revocations-v1`입니다. 각 JSON URL에 `.sig`를 붙이고 별도 서명 도메인을 사용합니다.

```text
repository-update --document repository-update.json --repository-id example.plugins --sequence 2 --key-id example-2026 --private-key <pem> --out repository-update.json.sig
plugin-revocations --document revocations.json --repository-id example.plugins --sequence 1 --key-id example-2026 --private-key <pem> --out revocations.json.sig
```

범위는 `PACKAGE_SHA256`, `PLUGIN_VERSION`, `SIGNING_KEY`, `PUBLISHER`입니다. `YANKED`는 새 설치·업데이트를 차단하고 `REVOKED`는 다음 로드 전에 이미 설치된 해당 바이트도 차단합니다. 실행 모드와 관계없이 현재 OS 계정 권한을 사용하며 서명·철회는 코드 안전성 보증이 아닙니다.

## 프로젝트 기여

일반 타사 플러그인은 호스트 변경이 필요하지 않습니다. 실제 API/runtime/template 결함 수정, 여러 소비자가 필요한 중립 포트, 문서·진단·경계 테스트, 공식 플러그인, 서명·설치 transaction·수명 주기·기능 부재 처리 개선을 주 저장소에 기여할 수 있습니다.

기능이 부족하면 app-private 클래스를 쓰기 전에 실제 소비자, owner, 수명, 오류·부재 의미와 테스트를 설명하는 중립 계약을 제안하세요. 공개 계약 변경은 SDK ID를 올리고 BOM, Javadoc, 템플릿, Douyin과 이 가이드를 함께 갱신해야 하며 게시 candidate를 덮어쓰면 안 됩니다.

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/plugin-api/your-capability upstream/master
```

관련 모듈·템플릿·경계 테스트를 실행하고 ID·라우트·리소스·번역을 맞춥니다. `target/`, `build/`, runtime 설정·자격 증명·비밀 키·다운로드를 커밋하지 않습니다. PR에는 문제, 계약, 실패·부재 동작과 실제 실행한 검증 명령을 씁니다. 코드·템플릿·개발 문서는 PR로 `master`에 제출합니다. 온라인 문서는 별도 `gh-pages`이며 전용 worktree에서 직접 commit/push하는 흐름입니다.

### 공식 저장소 버전·릴리스 검사

배포 내용이 달라지면 공개 버전보다 `plugin.version`을 올립니다. 게시 전 같은 미공개 버전에는 계속 수정할 수 있습니다. 새 SDK 기능을 쓰면 requires와 plugin 버전을 함께 올리지만 SDK 갱신만으로 모든 플러그인이 올라갈 필요는 없습니다. PR gate는 배포 내용과 공개 버전을 비교하고 모든 Quality Gate 빌드는 컴파일된 타입·멤버·callback override를 선언한 최소 SDK와 비교합니다. 게시 재실행은 소스 버전 결정을 반복하지 않습니다. reflection, inline 상수와 동작 계약은 코드 검토가 필요합니다.

플러그인 게시는 소스 SDK의 공개를 선행 조건으로 삼지 않습니다. 안정 앱 Release는 새 안정 SDK를 게시할 수 있습니다. 수동 안정 SDK 게시는 `-f`가 필요해도 immutable 릴리스를 덮어쓰지 못합니다. Beta 앱은 공개 안정 SDK를 재사용하지만 새 안정 SDK를 게시하지 않습니다. 수동 플러그인 게시의 `-f`는 같은 버전 첨부 복구용이며 앱 Release가 자동으로 넘기지 않습니다.

버전 검사는 소스·리소스·descriptor·비공개 의존성·배포 문서·공유 Maven 설정·참조 빌드 파일을 포함합니다. 테스트 전용 변경에는 버전 증가가 필요하지 않습니다. gate는 증가 여부만 검사하므로 major/minor/patch 선택은 계약 검토가 필요합니다. 공식 마켓의 legacy-client 집합은 2026-10-07의 안정 SDK `1.0.0`, `1.1.0`으로 고정되어 `scripts/market-curation.json`의 `_legacySdkVersions`에서 관리하며 자동 확장되지 않습니다. 채널·이력 보존은 [플러그인 관리](/ko/plugin-management)를 참고하세요.

## 기여 전 checklist

- [ ] provided SDK 또는 유효한 BOM 설정을 사용하고 호스트 제공 클래스를 패키지에서 제외
- [ ] 루트 descriptor의 ID·버전·requires·class·실행 모드와 코드 일치, 정확히 하나의 feature 및 명시적 자식 Bean 조립
- [ ] 모든 controller·페이지·정적 경로의 접근 정책 선언, i18n·오류·상태에 비밀 정보 없음
- [ ] 성공은 파일과 성공 사실 commit 후 보고, 요청 owner는 resolver로 확인, workKey는 불투명 문자열 유지
- [ ] 비동기 task·queue·client·executor·scheduler의 실제 quiesce/drain/close 확인
- [ ] 자체 갤러리·API·리소스·번역 소유, 자기 `gallery.type-switch`만 등록
- [ ] 설정·자격 증명·경로·`PluginDatabase`·`PluginMainDatabase`의 owner 경계 준수, 기존 `PluginDataSource`는 SQLite 전용 계약 준수
- [ ] 빌드·프런트 동작·패키지 구조 검사 통과, 게시 크기·해시·서명·manifest가 동일 바이트 참조
- [ ] 비밀 키가 소스·빌드 출력·로그·패키지·공개 저장소에 없음
