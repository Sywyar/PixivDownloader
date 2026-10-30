# 타사 플러그인 SDK

[Douyin 플러그인 예제](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)는 별도 저장소에서 관리합니다.

## 먼저 신뢰 경계를 이해하기

플러그인은 호스트가 제공하는 공개 API와 수명 주기 계약 안에서 실행됩니다. 호스트 데이터베이스, 자격 증명, 다른 플러그인의 사설 구현과 내부 라우트를 직접 사용하지 마세요.

## SDK 경계

플러그인은 descriptor, 설정, 정적 리소스, 웹 라우트, i18n, 알림 템플릿과 다운로드 유형을 기여할 수 있습니다. 호스트는 등록·시작·중지·reload·unload 시 publication을 관리합니다.

### 선언형 데스크톱 UI 경계

Swing과 Compose가 동일한 페이지를 각각 유지하지 않습니다. 애플리케이션 셸이 toolkit-neutral 전체 `DesktopUiDocument`와 페이지 구조, 상태, 설정 저장, 백엔드 상호작용, typed event 처리를 소유합니다. `gui-swing`과 `gui-compose`는 같은 문서를 그리는 범용 `DesktopUiProvider`이며 renderer, 창, tray, theme와 플랫폼 통합만 담당합니다. provider에서 페이지 ID, 플러그인 ID, 설정 key 또는 i18n key를 기준으로 전용 레이아웃을 만들지 마세요.

기능 플러그인은 pure-data `GuiConfigContribution`의 field, group, section, layout, action, preset으로 자신의 설정 section 전체 구조만 선언합니다. 호스트가 신뢰된 owner 기준으로 병합·검증·저장합니다. Swing / Compose component나 최상위 창을 반환하지 마세요. 공통 위젯이 필요하면 중립 `DesktopUiNode` 계약을 확장하고 모든 provider에서 범용으로 구현합니다.

공식 `gui-swing`은 기본 설치 및 기본 provider이고, `gui-compose`는 필요할 때 설치합니다. 둘 다 `process-restart`이므로 전환, 설치, 업데이트, 비활성화 또는 제거 후 전체 애플리케이션을 다시 시작해야 합니다. Compose 플러그인의 Kotlin / Compose 컴파일과 JAR-with-lib 생성은 Gradle Wrapper가 수행하고 Maven reactor가 공식 빌드, 서명, 배포 흐름에 연결합니다.

## 템플릿에서 시작하기

`plugin-templates/download-type-plugin`은 다운로드 유형 예제이고 `minimal-feature-plugin`은 최소 기능 예제입니다. 템플릿을 복사한 뒤 ID, 패키지, 클래스, 리소스와 표시 이름을 모두 독립적인 값으로 바꾸세요.

### SDK 아티팩트

SDK 개발 패키지는 단일 `pixivdownload-sdk` 컴파일 의존성, 독립 Maven 프로젝트와 Gradle / sbt 예제를 제공합니다. 고정 런타임 목록이 포함된 패키지에서는 Run / Debug로 현재 플러그인을 빌드하고 호환되는 호스트와 공식 플러그인 전체를 자동으로 준비할 수 있습니다. 실행 데이터는 각 프로젝트의 `.dev/`에 저장됩니다. 다운로드할 수 있는 버전과 사용법은 해당 [SDK Release](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases)와 패키지의 README를 확인하세요.

현재 저장소의 API 버전과 템플릿 POM을 기준으로 SDK를 선택합니다. 호스트와 플러그인의 계약 버전이 맞지 않으면 로드가 거부될 수 있습니다.

## 플러그인 패키지와 진입점

`plugin.properties`에는 plugin ID, 버전, 클래스와 API 버전을 선언합니다. JAR 루트에는 descriptor, 클래스와 리소스를 두고, 사설 의존성은 지정된 `lib/` 경로에만 둡니다.

## PostHog 브라우저 클라이언트 재사용

PostHog 기능은 vendor 전용 foundation과 플러그인 소유 consumer를 분리합니다. 플러그인은 자신의 공개 파라미터, 스키마, 익명 ID와 `beforeSend` 허용 목록을 소유하며, 브라우저 설정은 비밀이 아닙니다.

## PF4J와 Spring child context

플러그인 Bean은 플러그인 child context에서 생성하고 호스트 Bean과 이름 충돌을 피합니다. stop/reload/unload 시 등록한 라우트와 publication을 정확히 철회해야 합니다.

## 알림 템플릿

알림 publisher는 HTML, 의미와 i18n을 소유합니다. `notification`은 inbox와 분류를 소유하고, 메일·푸시 consumer는 각 채널 전달만 담당합니다.

## 웹 라우트, 정적 리소스와 i18n

플러그인 이름공간 아래에 라우트와 정적 리소스를 두고, source bundle과 `en-US`·`zh-Hant`·`ja-JP`·`ko-KR` 번역의 키와 placeholder를 맞춥니다. 생성된 정적 리소스는 저장소 workflow로 갱신합니다.

## 다운로드 유형 추가 workflow

현재 소스의 다운로드 작업 API는 플러그인이 제출 처리기, 옵션 훅, 이벤트 관찰자를 등록할 수 있도록 지원합니다. 요청 중복 방지, 특정 실행 취소, 재연결 시 상태 스냅샷을 제공하며 SDK 기본 클래스를 상속할 필요가 없습니다. [다운로드 유형 템플릿](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates/download-type-plugin)에서 사용법을 확인할 수 있습니다. 같은 계약이 포함된 SDK와 호스트를 사용하세요. 현재 소스에 있는 API가 이전 릴리스에도 포함되어 있다고 가정하지 마세요.

1. `DownloadTypeDescriptor`로 ID, 표시 이름과 capability를 선언합니다.
2. 프런트엔드 모듈에서 가져오기·큐·진행률·상태 화면을 구현합니다.
3. 백엔드에서 신뢰할 수 있는 owner를 확인합니다.
4. `QueueOperations`를 통해 enqueue, cancel, retry와 history를 연결합니다.
5. UI slot과 작업 모드에 기여합니다.
6. 예약 작업 capability와 source descriptor를 추가합니다.

각 단계에서 사용자 입력, 인증, 실패 상태와 재시작 후 복구를 명시적으로 처리하세요.

## 플러그인 소유 갤러리

갤러리 provider와 데이터 계약을 플러그인에 두고, core는 중립적인 registry와 공통 UI만 제공합니다. Pixiv·소설·Douyin의 source-specific 필드는 서로 복사하지 않습니다.

## 설정, 자격 증명과 파일

설정 소유자, 자격 증명 소유자와 파일 소유자를 분리합니다. `state/{pluginId}`는 재생성 가능한 상태, `data/{pluginId}`는 플러그인 전용 데이터베이스와 캐시, 다운로드 루트는 사용자 작품 파일에 사용합니다.

## 외부 HTTP와 WebSocket

호스트가 제공하는 `OutboundHttpClient`와 proxy 정책을 사용하세요. 플러그인에서 임의의 `HttpClient`나 Apache 타입을 만들지 말고, 인증 헤더와 사이트별 프로토콜은 해당 업무 모듈에 둡니다.

## 독립 서비스 계약과 조합

아래 API에는 SDK 1.4.0이 필요합니다. `typed-service-plugin` 템플릿으로 API, 계약 운반자, 제공자, 소비자 모듈을 분리합니다. 업무 버전은 계약이 관리합니다. 운반자는 `pixiv.contract-api`와 `pixiv.contract-sha256`으로 원본 API JAR을 지정하며 `process-restart`를 사용합니다. 제공자와 소비자는 운반자에 대한 의존성을 선언하고 API 클래스를 각각 중복 포함하지 않습니다.

`PixivFeaturePlugin.serviceImports()`에 사용할 key를, `serviceExports()`에 구현 Bean을 선언하고 child context에 `PluginServices`를 주입합니다. 생성자에서 접근자를 보관하거나 binding을 등록할 수 있지만 업무 호출에는 소비자의 활성 publication이 필요합니다. 인터페이스와 record/enum은 같은 계약 classloader와 코드 출처에 속해야 합니다. 계약 패키지 간 의존성은 지원하지 않으므로 경계 DTO를 명시적으로 변환합니다.

`find(key)`는 부재 시 빈 값, `require(key)`는 `UNAVAILABLE`을 반환합니다. 두 메서드 모두 구현이 여럿이면 `AMBIGUOUS`입니다. `findAll(key)`는 불변 `List<ServiceProvider<T>>`를 반환하며 빈 목록도 유효합니다. 최대 128개입니다. `service()`는 소비자와 제공자의 정확한 publication에 고정된 프록시이고 `publication()`은 신뢰된 식별 값입니다. 게시 순서는 업무 우선순위가 아닙니다. 소비자가 선택·정렬하며 철회된 프록시는 대체 구현으로 연결하지 않습니다.

유일한 의존성은 `bind(keys, activation)`, 빈 목록을 포함한 구현 집합은 `bindAll(keys, activation)`을 사용합니다. 해당 회차의 `scope.require(key)` 또는 `scope.findAll(key)`에서 프록시를 받고 `scope.acquire(...)`, `scope.guard(...)`로 자원과 보조 콜백을 관리합니다. 구성원이 바뀌면 이전 회차의 진입을 닫고 실제 종료와 정리를 기다린 뒤 다음 회차를 활성화합니다. activation에서 업무 명령을 재실행하면 안 됩니다. `FAILED`, `CLOSE_FAILED`는 명시적으로 `retry()`할 수 있으며 정리 완료를 증명하는 상태는 `CLOSED`입니다.

단순 방송은 독립 `EventSink.onEvent(record)` 계약과 `findAll` 순회로 구현합니다. 각 `ServiceAccessException`은 업무 계약에 따라 처리합니다. best effort는 권위 없는 보조 효과에만 적용합니다. 필수 검증은 쓰기 전에 끝내며 필수 구현의 부재나 검증 실패는 쓰기를 거부합니다. 집합 API는 영속 메시지, 자동 재시도, 역압력, 전달 보장을 제공하지 않습니다.

장기 작업은 업무 계약의 순수 값 프로토콜 `start → jobId → query/cancel/result`를 사용합니다. `PluginRuntimeTaskRegistrar`에 작업을 등록하고 `PluginResources`로 실행기와 자원을 소유하며 제공자 자체에서 취소에 응답합니다. 핸들 취소는 작업 종료 증거가 아니며 호스트는 실제 drain을 기다립니다. 재시작 복구, checkpoint, 멱등성은 데이터 owner의 사설 DB에서 정의합니다. Future, 스레드, 콜백을 서비스 값으로 넘기지 않습니다.

`binding.diagnostic()`은 제한된 크기의 순수 값으로 원인, 상태, 의존성, publication을 제공합니다. 관리 화면의 서비스 관계와 중지 영향은 같은 런타임 사실에서 계산하며 선언된 import와 drain 중인 binding을 포함합니다. 대기, 모호함, 활성화 실패, 실행기 거부, 정리 실패를 구분합니다. `truncated`는 표시가 불완전하다는 뜻이며 의존성 부재를 뜻하지 않습니다. 진단 조회는 업무를 재시도하거나 생명주기를 변경하지 않습니다.

## GUI 필드 없이 사용하는 자격 증명과 로그인 검증

`credentialKeys()`에 `my-plugin.token`처럼 owner 접두사가 있는 키를 선언하고 `PluginCredentials`를 주입합니다. `read(key)`는 `Optional<String>`, `update(Map)`는 원자적 병합입니다. 생략한 키는 유지하며 공백 값은 삭제합니다. `delete(key)`는 멱등적입니다. 선언·갱신은 최대 128개, 값당 UTF-8 64 KiB, 한 번에 1 MiB까지입니다. 미선언 키와 오래된 generation 핸들은 거부되며 다른 owner를 지정할 수 없습니다.

자격 증명 키를 일반 GUI 필드로도 선언할 수 없습니다. 같은 키의 필드는 민감 필드여야 합니다. 충돌하면 호스트는 해당 owner의 자격 증명 및 데스크톱 설정 스냅샷을 거부하고 발견된 민감 키의 마스킹을 유지합니다. GUI의 자격 증명 스냅샷, 쓰기, 실패 롤백은 하나의 owner 잠금 안에서 실행합니다. 로드와 다시 로드에서도 자격 증명 읽기와 마이그레이션 판단을 같은 잠금 안에서 수행하여 오래된 스냅샷이나 평문이 성공한 프로그램 갱신을 덮어쓰지 않도록 합니다. 자격 증명을 포함하는 환경 내보내기는 GUI가 없는 선언된 키도 포함하며 암호화가 필수입니다. 자격 증명을 제외하면 해당 값은 내보내지 않으며 복원 시에도 owner와 설치 세대를 확인합니다.

저장소는 기존 호스트 암호화와 파일 잠금을 사용합니다. 기존 GUI의 민감한 필드도 같은 선언과 마스킹 범위에 포함됩니다. 저장해도 기존 `@Value` 또는 설정 객체를 자동으로 다시 바인딩하지 않습니다. 필요할 때 읽거나 기존 재시작 정책을 따릅니다. 평문을 서비스 DTO, 예외, 로그, 진단에 넣지 마세요.

Spring Bean으로 `LoginVerification`을 구현하고 `policyId()`에서 안정적인 키를 반환합니다. 관리자가 `setup.login-verification-policies`에 필수 정책을 쉼표로 구분하여 설정하면 백엔드 재시작 후 적용됩니다. 빈 값은 검증을 끄며 최대 16개입니다. 호스트는 속도 제한과 비밀번호 확인 이후, 세션 생성 전에 호출합니다. 입력에는 사용자 이름, 클라이언트 주소, attemptId, 불투명 proof가 있고 비밀번호나 세션 권한은 없습니다.

결과는 `APPROVED`, `REJECTED`, `CHALLENGE`입니다. 필수 정책의 부재·모호함·예외는 503, 거절은 403, 챌린지는 409와 `verificationToken`, 정책별 `challenges`를 반환합니다. 다음 `/api/auth/login` 요청에 원래 자격 증명, token, `verificationProofs`를 보냅니다. 챌린지는 사용자 이름, 주소, 정확한 publication에 고정되며 5분간 유효하고 응답은 한 번만 받습니다. 오래된 챌린지는 409입니다. 챌린지/proof는 각 UTF-8 16 KiB, 합계 64 KiB, 대기 중 최대 256개입니다. 실패 응답은 세션 Cookie를 만들지 않습니다.

검증 UI가 필요하면 일반 `login.verification` UiSlot에 모듈을 기여하고 정적 모듈의 공개 경로를 선언합니다. 호스트는 `/api/web/login-verification-slots`에서 이 대상만 공개합니다. 속성은 `{verificationToken, challenges}`, `proof` 이벤트 값은 `{verificationToken, policyId, proof}`입니다. 사용자가 로그인을 다시 눌러 제출합니다. 자격 증명을 편집하면 proof를 지웁니다. 타입은 SDK JAR의 `META-INF/pixivdownload/web/login-verification.d.ts`에 있습니다. 화면 표시는 백엔드 검증을 대신하지 않으며 설치만으로 필수 정책이 켜지지 않습니다.

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

시험판 SDK는 정확한 계약을 사용하며 properties에 `plugin.requires==7.2.3-rc.4`처럼 씁니다(예시). 안정판은 `major.minor`를 사용합니다. 컴파일 의존성, 설명자와 실행 패키지를 일치시키고 서로 다른 RC의 호환성을 가정하지 마세요. 원래 프로젝트와 `.dev/`를 보존하고 새 SDK를 별도 경로에 풀어 도구, 계약 리소스와 실행 매니페스트를 함께 갱신합니다. 플러그인이 자체 데이터 마이그레이션을 책임지며 알 수 없는 형식이나 실패 시 기존 데이터를 보존하고 시작을 중단해야 합니다.

독립 프로젝트에서 Windows는 `mvnw.cmd clean verify`, 그 외에는 `./mvnw clean verify`를 실행하고 같은 Wrapper의 `verify exec:exec@sdk-run`으로 시작합니다. 관리자 페이지에서 시작, 중지, 재시작과 허용된 reload를 수행하여 라우트 철회, 이전 publication 무효화, 실제 작업 drain과 데이터 보존을 확인하세요. `process-restart`는 완전히 종료한 뒤 다시 시작해야 합니다. 개발 인스턴스 종료는 `exec:exec@sdk-stop`을 사용합니다. 개발 디렉터리의 full-trust 실행은 실제 worker 패키지 검증을 대신하지 않습니다.

`DownloadObserver`는 동기식 best-effort 알림이며 영구 재생을 제공하지 않습니다. `DownloadAdmissionPolicy`는 부작용 전에 허용·거부하고 요청을 수정하지 않습니다. 둘 다 호스트가 관리하는 선택적 full-trust Bean입니다. `WorkFileImporter`는 원본 파일의 읽기 전용 참조를 등록하며 타입 owner가 없으면 성공을 반환하지 않습니다. 선택한 Release의 Javadoc에서 API 제공 여부를 확인하세요.

후보가 없으면 기본 브랜치의 현재 CI와 `tools/candidate-projects.json`을, 인증·push 실패면 현재 계정과 권한을 확인합니다. 해시가 다르면 같은 배포물을 다시 받으세요. 검토 사실·소유권 변경이나 응답 유실 시 원래 요청부터 조회하고 자동 재투고하지 않습니다. 보고에는 ID, 버전, 소스 commit, SDK와 오류 코드를 포함하되 자격 증명, 개인 키와 개인 경로는 제거하세요.

## 빌드·테스트·디버그·설치

필수 테스트는 Java 단위 테스트, 웹 표준, i18n, 패키지 구조와 격리된 classloader 로딩을 포함합니다. 개발 모드에서 설치한 JAR은 중복 로드되지 않도록 캐시와 이전 아티팩트를 정리하세요.

## 서명과 게시

### 게시자 키와 커뮤니티 작업 서명

서명 CLI는 `pixivdownload-plugin-signature`에 있으며 JDK 17만 있으면 실행할 수 있습니다. 소스 저장소 루트에서 빌드한 뒤 생성된 JAR로 호출합니다.

```text
./mvnw -pl pixivdownload-plugin-signature package
java -cp <signature-tool.jar> top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool <command>
```

- `keygen --directory <new-directory>`: `private-key.pem`(PKCS#8)과 해당 `public-key.pem`(SPKI)을 생성합니다. 디렉터리와 개인 키는 현재 사용자만 접근할 수 있습니다. 소스 저장소와 임시 디렉터리 밖의 위치를 선택하고 두 파일을 모두 백업하세요. 기존 디렉터리를 덮어쓰지 않습니다.
- `public-key --public-key <public-key.pem> --key-id <id> --out <public.json>`: 정규화된 SPKI Base64와 SHA-256 공개 키 지문을 내보냅니다. 짝을 이루는 공개 키 파일을 읽으며, 개인 키에서 공개 키를 유도하지 않습니다.
- `community-operation --operation <PUBLISHER_KEY_ROTATION|VERSION_STATUS_REQUEST|OWNERSHIP_TRANSFER> --canonical-body <canonical-body> --request-id <SHA-256> --key-id <id> --private-key <private-key.pem> --out <sig.json>`: 고정된 SDK가 생성한 JCS 본문 바이트에 서명합니다. 본문 해시는 requestId와 일치해야 합니다. 출력에는 분리 서명만 포함되며 개인 키는 포함되지 않습니다.

공개 키 내보내기와 커뮤니티 작업 서명은 기존 출력 파일을 덮어쓰지 않습니다. 플러그인 패키지 서명에는 `artifact`를 사용하세요. 전체 인수는 `--help`에서 확인할 수 있습니다. 개인 키를 Git에 커밋하거나 제출 첨부 파일에 넣지 마세요.

아티팩트의 SHA-256과 detached Ed25519 서명을 생성하고, catalog manifest의 ID·버전·크기·URL·해시·서명을 일치시킵니다. 개인 키를 저장소에 커밋하지 말고 보호된 release 환경에서만 사용하세요.

## 사용자 지정 저장소

타사 저장소는 최대 64 KiB의 엄격한 UTF-8 JSON `repository.json`을 공개합니다. 필수 정보는 `schemaVersion: 1`, 예약되지 않은 `repositoryId`, 표시 이름, 게시자, `catalog.protocol`(`manifest-v1` 또는 `paged-v2`), 공개 HTTPS endpoint, `networkProfile`(`DIRECT_STRICT` 또는 `GITHUB_RELEASES`), Ed25519 SPKI 공개 키 1~4개입니다. `revocationsUrl`과 `updateProofUrl`은 선택 사항입니다. 최초 가져오기에서는 `repository.json.sig`를 사용하지 않습니다. 사용자가 게시자, 모든 연결 호스트와 공개 키의 전체 `SHA-256(SPKI DER)` 지문을 확인하고, 확인 시 다시 받은 descriptor digest가 같을 때만 저장되며 재시작 후 활성화됩니다.

`paged-v2`는 `{endpoint}/plugins`, `{endpoint}/plugins/{pluginId}`, `{endpoint}/plugins/{pluginId}/versions/{version}`을 제공하고 기본 24개·최대 100개 항목, `generation`과 불투명 cursor를 사용합니다. 설치 시 버전 endpoint를 다시 조회하여 동결한 패키지의 크기, SHA-256, 서명과 내부 descriptor를 비교합니다. 키 교체에는 이전 신뢰 키로 서명한 단조 증가 `repository-update-v1`, 보안 철회에는 `revocations-v1`을 사용하며 기존 CLI의 `repository-update`와 `plugin-revocations` 명령으로 서명합니다. `YANKED`는 신규 설치/업데이트를 막고 `REVOKED`는 기존 바이트 로드도 막습니다. 플러그인은 호스트와 같은 JVM에서 실행되며 코드 sandbox는 없습니다.

## 기여 전 checklist

- public API와 plugin API version 확인
- descriptor, 라우트, 정적 파일과 i18n 등록
- 시작/중지/reload/unload와 실패 복구 테스트
- JAR 구조, SHA-256, 서명과 catalog 검증
- 문서, 테스트 결과와 변경 범위를 PR에 기록

## 갤러리 탐색 슬롯

플러그인은 `NavigationContribution.placements`로 항목을 선언합니다. `gallery.sidebar`는 플러그인, 다운로드, 갤러리 같은 주요 페이지용이며, `gallery.sidebar.tools`는 초대 관리, 통계, 중복 후보 같은 도구용입니다. 소설 갤러리는 `novel.sidebar`와 `novel.sidebar.tools`를 사용합니다. 각 그룹에 정렬 순서와 요청 사용자의 접근 권한을 독립적으로 적용합니다. 기여를 철회하면 항목이 사라지고 빈 그룹은 숨겨집니다. 페이지는 `data-nav-slot`만 선언하며 플러그인 ID로 분류하지 않습니다.

관리자 다운로드 페이지는 `DownloadTasks` 실행 스냅샷을 기존 대기열에 통합합니다. 유형 모듈은 `process(item, context)`에서 백엔드가 반환한 실제 실행 UUID로 `context.bindTask(taskId)`를 호출합니다. 기존 항목이 해당 실행에 연결되고 실행 ID로 취소할 수 있습니다. 작품 ID를 실행 ID 대신 사용하지 마세요. 다른 곳에서 제출한 작업에는 “다른 경로”가 표시되며 페이지에서 다시 제출하지 않습니다. 종료된 실행 스냅샷은 5분 후 만료되지만 저장된 대기열 기록은 유지됩니다. 실행 여부를 확인할 수 없는 항목은 미확인 상태로 남으며 페이지를 새로 고쳐도 자동으로 다시 제출되지 않습니다.
