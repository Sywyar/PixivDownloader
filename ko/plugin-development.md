# 타사 플러그인 SDK

## 먼저 신뢰 경계를 이해하기

플러그인은 호스트가 제공하는 공개 API와 수명 주기 계약 안에서 실행됩니다. 호스트 데이터베이스, 자격 증명, 다른 플러그인의 사설 구현과 내부 라우트를 직접 사용하지 마세요.

## SDK 경계

플러그인은 descriptor, 설정, 정적 리소스, 웹 라우트, i18n, 알림 템플릿과 다운로드 유형을 기여할 수 있습니다. 호스트는 등록·시작·중지·reload·unload 시 publication을 관리합니다.

## 템플릿에서 시작하기

`plugin-templates/download-type-plugin`은 다운로드 유형 예제이고 `minimal-feature-plugin`은 최소 기능 예제입니다. 템플릿을 복사한 뒤 ID, 패키지, 클래스, 리소스와 표시 이름을 모두 독립적인 값으로 바꾸세요.

### SDK 아티팩트

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

## 빌드·테스트·디버그·설치

필수 테스트는 Java 단위 테스트, 웹 표준, i18n, 패키지 구조와 격리된 classloader 로딩을 포함합니다. 개발 모드에서 설치한 JAR은 중복 로드되지 않도록 캐시와 이전 아티팩트를 정리하세요.

## 서명과 게시

아티팩트의 SHA-256과 detached Ed25519 서명을 생성하고, catalog manifest의 ID·버전·크기·URL·해시·서명을 일치시킵니다. 개인 키를 저장소에 커밋하지 말고 보호된 release 환경에서만 사용하세요.

## 사용자 지정 저장소

사용자는 신뢰하는 저장소 URL과 공개 키를 설정할 수 있습니다. 로컬 업로드를 임의의 신뢰 루트로 사용하게 만들지 말고, 호스트의 서명·크기·해시 검증을 유지하세요.

## 기여 전 checklist

- public API와 plugin API version 확인
- descriptor, 라우트, 정적 파일과 i18n 등록
- 시작/중지/reload/unload와 실패 복구 테스트
- JAR 구조, SHA-256, 서명과 catalog 검증
- 문서, 테스트 결과와 변경 범위를 PR에 기록
