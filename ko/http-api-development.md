# HTTP API 및 권한 선언 작성

Web, Swing, Compose, CLI의 업무 기능을 추가하거나 수정할 때는 대응하는 HTTP API, 프로그램 키 권한 선언 및 다국어 OpenAPI 문서를 함께 제공해야 하는지 확인하세요. 클라이언트 호출 방식과 전체 권한 목록은 [OpenAPI와 프로그램 키](/ko/http-api), 플러그인 확장 계약은 [서드파티 플러그인 SDK](/ko/plugin-development)를 참고하세요.

## 동반 HTTP API가 필요한 경우

- **업무 기능을 HTTP 인터페이스로 일관되게 제공**: 업무 상태 조회, 설정 읽기와 저장, 프리셋 적용, 다운로드, 번역, 가져오기, 내보내기, 데이터베이스 이전, 유지보수와 시스템 관리는 모두 HTTP로 수행할 수 있어야 합니다. 동등한 인터페이스가 이미 있으면 같은 업무 서비스를 재사용하고 GUI마다 실행 로직을 복제하지 마세요.
- **클라이언트 표현과 서버 업무의 분리**: 창 크기, 포커스, 테마 미리보기, 클립보드, 시스템 파일 선택기와 로컬 브라우저 열기는 클라이언트 측 표현이므로 서버에서 흉내 낼 필요가 없습니다. 파일 처리가 포함된 흐름에서는 원격 클라이언트 파일은 업로드로 받고, 서버 경로 매개변수는 항상 서버 로컬 파일 시스템을 가리키도록 엄격히 구분하세요.
- **장기 실행 작업 수명 주기**: 비동기 장기 작업은 시작(`202 Accepted`), 상태 조회, 실행 식별자 기준 취소와 결과 조회 인터페이스를 제공하고, 취소 종료 시맨틱(취소 요청이 즉시 종료를 뜻하지 않으며 작업을 찾을 수 없다고 안전하게 다시 제출할 수 있는 것은 아님), 프로세스 재시작 후 상태와 멱등 범위를 문서에 명시해야 합니다.

## 라우트 및 프로그램 권한 선언

라우트는 기능의 실제 owner가 `PixivFeaturePlugin.routes()`에서 선언합니다. `AccessPolicy`는 관리자 본인, 게스트와 로컬 GUI 흐름을 관리하며, 프로그램 키 접근은 `WebRouteContribution.withProgramPermissions(...)`로 HTTP 메서드별로 명시 선언합니다(선언하지 않은 작업은 기본적으로 프로그램 키를 거부하며 관리자 권한을 상속하지 않음):

```java
WebRouteContribution.admin("/api/example/items")
    .withProgramPermissions(Map.of(
        HttpMethod.GET, new ProgramPermission("items.read", "permission.items.read"),
        HttpMethod.POST, new ProgramPermission("items.create", "permission.items.create")
    ));
```

- **권한 ID 및 경로 제약**: 전체 권한 ID는 호스트 등록 신원에 따라 `<owner>:<permission>`(예: `example:items.read`)으로 결정되며, 표시 라벨 키는 해당 owner의 표시용 i18n 리소스에 둡니다. 프로그램 권한은 명시적인 `/api/` 라우트와 구체적인 HTTP 메서드에만 사용할 수 있습니다(단일 경로 구간 `*`는 리소스 ID로 사용 가능하며 `**`, `OPTIONS`, GUI/LOCAL 전용 흐름은 불가).
- **권한 세분화 및 대상 검증**: 같은 권한 아래의 인터페이스는 일관된 업무 목적과 데이터 범위를 가져야 합니다. UI에서 플러그인 그룹을 선택하면 현재 나열된 권한만 일괄 선택하며 미래의 권한에 대한 와일드카드를 저장하지 않습니다. 공유 인터페이스(설정, 미디어 유지보수, 파일 가져오기, 플러그인 수명 주기, 예약 작업 등)는 `core` 진입점 권한뿐 아니라 부작용이 발생하기 전에 대상 플러그인이나 수집원의 권한도 검증하고, 읽기 목록은 인가된 대상만 남도록 필터링해야 합니다(업무별 복합 권한과 전용 진입점은 [OpenAPI와 프로그램 키: 권한 범위와 전용 흐름](/ko/http-api#권한-범위와-전용-흐름) 참고).

| 업무 영역 | 권한 분리 원칙 | 추가 보안 검증 |
| --- | --- | --- |
| 설정 및 프리셋 | 읽기, 검증, 저장 분리 | 각 필드의 실제 owner 확인. 민감한 값은 `stored` 플래그만 반환하고 평문은 절대 반환하지 않음 |
| 미디어 유지보수 및 파일 가져오기 | 읽기, 미리보기, 실행, 취소, 완료 확인 분리 | 정확한 플러그인 publication, 미리보기 token과 실행 ID 검증 |
| 다운로드 및 번역 | 조회, 생성/재개, 취소, 기록 삭제 분리 | 데이터 범위, 수집 자격 증명과 작업 생성자 소유권 검증 |
| 데이터베이스 및 환경 아카이브 | 조회, 검사, 미리보기, 이전, 내보내기, 복원 분리 | 설치 전체에 영향을 주므로 명시적 확인, 미리보기 token과 쓰기 중지 구간 검증 |
| 플러그인 및 시스템 관리 | 조회, 설치, 활성/비활성화, 삭제, 재시작 분리 | 서버 코드 실행과 프로세스 재시작을 수반하므로 대상 플러그인과 역방향 소비자 권한을 함께 검증 |
| 키 및 관리자 자격 증명 | 관리자 본인 로그인 세션 전용 | 프로그램 키로 키를 생성/수정하거나 자신의 권한을 높이는 행위 금지 |

## 다국어 문서 및 OpenAPI 확장 필드

Spring MVC가 기본 메서드, 경로와 매개변수를 제공하고, 컴파일 시 유지되는 Javadoc, `@Operation`, `@Schema` 및 요청 본문 어노테이션으로 예시, 수동 파싱 JSON 구조와 응답 상태를 보충합니다.

### 다국어 설명 및 접이식 '상세 정보'

Controller에 `@HttpApiGroup("core-batch")`를 붙이면 해당 모듈의 `src/main/resources/i18n/http/core-batch.properties` 및 언어별 파일(`core-batch_en.properties` 등)과 연결됩니다. 키 접두사는 `<Controller클래스명>.<Java메서드명>.<대문자HTTP메서드>`(단일 HTTP 메서드로 제한되지 않으면 `ANY`)입니다:

| 리소스 키 | 역할 |
| --- | --- |
| `guide` / `<접두사>.guide` | 그룹 공통 또는 개별 작업용 가이드 경로(작업의 `externalDocs`에 반영되며, 관련 작업의 가이드는 중복을 제거해 `x-related-guides`에 모음) |
| `<접두사>.summary` / `<접두사>.description` | 작업 제목 및 요약 설명 |
| `<접두사>.details` | 선택적 Markdown 상세 본문(`description`에 추가되며 `/openapi`에서는 기본적으로 접혀 있는 '상세 정보'로 표시) |
| `schema.DownloadSubmission.<workType>.description` | `POST /api/download/tasks`에 대해 활성화된 각 다운로드 제공자의 유형별 설명 보충(작업과 제공자 모두 상세 본문이 없으면 접이식 영역 생략) |

설명을 작성하기 전에 HTTP 진입점부터 인증, 검증, 제공자, 비동기 실행, 디스크 저장, 결과 조회 및 실패 복구까지 전체 호출 경로를 읽고, 제출 즉시 알 수 있는 결과와 비동기 종료 후 알 수 있는 결과를 구분해 적으세요. 작성 후 각 언어별로 `/api/openapi.json`과 `/openapi` 화면에서 초기 접힘 상태와 키보드 펼침 동작을 확인합니다.

### OpenAPI 확장 메타데이터

| 확장 필드 | 의미 및 선언 방식 |
| --- | --- |
| `x-program-permission` / `x-program-permissions` | 단일 라우트 권한은 `x-program-permission`에 기록. 추가 검사가 있으면 `x-program-permissions`에 `route`, `discovery`(수집원 토큰 조회 API), `submit`(고정 권한, 헤더 기반 권한 또는 카탈로그 조건), `execution`(`executePermissionsByWorkType` 등)을 함께 나열. 최상위 `x-program-permission-conditions`에서 `request#`, `task#`, `batch#`, `item#` 해석 규칙과 `when.keyFrom` / `when.value` 조건(예: 내보내기에서 `request#/deleteAfter == true`일 때만 삭제 권한 추가 요구), 누락 권한 위치 `reportedIn.pointer`를 정의 |
| `x-schedule-sources` / `x-manual-batch-source` | `x-schedule-sources`는 각 수집원의 `schedulePermission`과 `batchSubmitPermission`을 나열. 고정 수집원의 수동 배치를 대신 제출하는 플러그인 API는 `x-manual-batch-source`로 수집원 유형 선언 |
| `x-csrf-origin` | 쓰기 요청의 출처 검증 정책 표시: `origin-or-referer`(네이티브 클라이언트도 동일 출처 `Origin` 또는 `Referer` 전송 필요), `origin-required`, `conditional`(Cookie 등 브라우저 신호 유무로 구분) |
| `x-program-alternative` | 프로그램 키를 지원하지 않는 브라우저 작업에서 대체 프로그램 키 엔드포인트(`<METHOD> <path>` 형식, 예: `POST /api/download/tasks`) 안내 |
| `links`(`@ApiResponse`) | 명확한 후속 호출이 있고 응답에서 매개변수를 추출할 수 있을 때 선언하여 `/openapi`의 '관련 작업' 이동 링크 생성 |
| `x-sse-data-event` / `x-sse-events` | 이름이 지정된 JSON SSE 이벤트를 발행할 때 `text/event-stream`의 content에 이벤트 DTO schema와 `x-sse-data-event` 이벤트 이름을 선언. 생성된 명세는 문자열 전송 형식을 유지하면서 `x-sse-events`에 이벤트별 DTO schema를 내보냄 |

## 매개변수 검증, 오류 계약 및 테스트 검증

- **신뢰 경계 및 오류 구조**: 경로, 자격 증명과 JSON은 신뢰 경계에서 검증합니다. 프로그램 키는 `Authorization: Bearer`로만 받고 URL, 로그나 브라우저 영구 저장소에 기록하지 않습니다. 출발지 IP는 `server.trusted-proxy-cidrs` 처리 후의 클라이언트 주소만 사용합니다. 오류 응답은 안정적인 기계용 `code`와 현지화된 `error`를 유지합니다(다운로드 제출 시 권한 부족은 `DownloadTaskException.programPermission()`으로 누락된 권한 ID 노출 가능).
- `@HttpApiGroup`을 붙인 컨트롤러에서는 클래스나 메서드에 SDK의 `@KnownErrorCodes`를 붙여 표준 JSON 오류의 HTTP 상태와 업무 오류 코드를 선언할 수 있습니다. OpenAPI는 이 선언을 바탕으로 해당 4xx 또는 5xx 상태의 `application/json` 응답을 생성합니다. 별도 필드가 있으면 응답 스키마에 명시하세요.
- **구조화된 매개변수 오류(`HttpParameterException`)**: 플러그인이 HTTP 진입점에서 매개변수를 직접 검증할 때는 JDK에만 의존하는 `pixivdownload-core-api`의 `top.sywyar.pixivdownload.core.web.HttpParameterException(in, field, reason)`을 던질 수 있습니다(예: 쿼리 매개변수 `order`가 허용 열거형 밖일 때 `new HttpParameterException("query", "order", "invalid_value")`). 메서드에 `@KnownErrorCodes(status = "400", value = "error.request.param.invalid")`를 붙이면 OpenAPI에 이 오류가 표시됩니다:
  - 호스트는 HTTP 400, `code = "error.request.param.invalid"`, 현지화된 `error` 및 `in`, `field`, `reason` 세 위치 필드를 반환합니다(호출자가 보낸 원본 값이나 자격 증명은 포함하지 마세요. Spring이 처리하는 필수 매개변수 누락이나 타입 변환 오류는 호스트가 자동으로 위치 지정).
  - `in`은 `query`, `path`, `header`, `part`, `body` 중 하나입니다. 앞의 네 가지는 인터페이스에 선언된 매개변수 이름이나 멀티파트 이름을 `field`에 넣고, JSON 본문(`body`)은 JSON Pointer(예: `/options/pages`)를 넣습니다. `reason`에는 `required`, `type`, `invalid_value`, `out_of_range` 같은 안정적인 기계 값을 사용합니다.
- **실제 경로 테스트**: 테스트는 실제 보안 필터나 HTTP 진입점을 통과하여 권한 부족 시 업무 부작용이 전혀 없음을 증명하고, HTTP 메서드 차이, 대상 플러그인 필터링, 출발지 네트워크 제한, 키 폐기, 플러그인 세대 교체 및 저장 실패 롤백을 모두 검증해야 합니다. 인터페이스 변경 시 권한 카탈로그, 호출자, 5개 언어 문서와 회귀 테스트를 함께 갱신하세요.
