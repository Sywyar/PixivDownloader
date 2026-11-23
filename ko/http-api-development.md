# HTTP API 및 권한 선언 작성

메서드, 경로, 매개변수는 Spring MVC에서 가져옵니다. 컴파일할 때 보존한 Javadoc을 설명에 사용할 수 있습니다. 예시, 직접 파싱하는 JSON과 응답 정보는 `@Operation`, `@Schema`, OpenAPI 요청 본문 어노테이션으로 보완하세요. 권한은 활성 경로 선언을 따릅니다. API를 추가하면 `/openapi`에서 입력, 성공 상태, 대상 플러그인 제한과 주요 오류를 확인하세요. 비동기 작업은 결과 조회 방법도 설명해야 합니다.

Web, Swing, Compose, CLI의 업무 기능을 추가하거나 변경할 때 같은 변경에서 HTTP API 지원 범위도 확인합니다. 호출 방법과 목록은 [HTTP API](/ko/http-api), 플러그인 계약은 [서드파티 플러그인 SDK](/ko/plugin-development)를 참고하세요.

컨트롤러의 HTTP 언어 리소스에서 `guide`에 그룹 안내서 URL을 지정하세요. 개별 작업의 페이지나 절은 `<Controller>.<method>.<VERB>.guide`로 지정합니다. 생성된 작업의 `externalDocs`에 직접 안내서가, `x-related-guides`에 관련 작업이 참조하는 안내서가 중복 없이 표시됩니다. 응답 값으로 다음 요청의 인자를 만들 수 있으면 해당 `@ApiResponse`에 `links`를 선언하세요.

라우트 권한은 `x-program-permission`에서 확인합니다. 추가 검사가 있는 작업에서는 `x-program-permissions.route`도 같은 값입니다. `discovery`는 소스 토큰을 가져오는 작업을 가리킵니다. `submit` 배열은 고정 권한, 헤더 전송 시 필요한 권한 또는 카탈로그 조회 조건을 나열합니다. `x-schedule-sources`에서 일정 관리에는 `schedulePermission`, 배치 제출에는 `batchSubmitPermission`을 사용합니다. 고정 소스로 수동 배치를 제출하는 플러그인 작업은 `x-manual-batch-source`로 소스 유형을 지정합니다. 이때 호출자는 소스 토큰을 따로 조회할 필요가 없습니다. 인스턴스가 내보낸 OpenAPI 문서의 최상위 `x-program-permission-conditions`에서 각 단계와 필드, `request#`, `task#`, `batch#`, `item#`의 해석 규칙을 확인할 수 있습니다.

조건부 권한은 `when.keyFrom`이 가리키는 JSON 필드의 값을 `when.value` 리터럴과 비교합니다. 예를 들어 갤러리 내보내기는 `request#/deleteAfter`가 불리언 `true`일 때 삭제 권한도 요구합니다.

`execution`의 `executePermissionsByWorkType`에서 이번 실행 계획에 필요한 작품 유형의 권한을 확인합니다. 백그라운드 실행 시 소스 권한도 다시 확인합니다. 누락된 권한 ID는 `reportedIn.pointer`의 JSON Pointer가 가리키는 위치에 있습니다. 제출 시에는 HTTP 403 응답 본문을 읽고, 접수된 뒤에는 `core:batches.read` 권한으로 202 응답의 `Location`을 조회해 배치 결과를 읽습니다. 다운로드 제출 예외에서는 `DownloadTaskException.programPermission()`으로 확인된 권한을 얻을 수 있습니다. `auth.program.unsupported`를 받으면 `x-program-alternative`에서 대체 작업을 확인하세요.

`x-csrf-origin`이 `origin-or-referer`이면 네이티브 클라이언트도 동일 출처의 Origin 또는 Referer를 보내야 합니다. `origin-required`이면 Origin 매개변수의 제약을 따르세요. `conditional`일 때의 브라우저 출처 신호와 쿠키 조건은 매개변수 설명에 있습니다.

이름이 있는 JSON SSE 이벤트는 `text/event-stream`의 content에 이벤트 DTO 스키마를 지정하고 content 확장 `x-sse-data-event`에 이벤트 이름을 설정합니다. 생성된 미디어 유형은 문자열 전송 형식을 유지하며 `x-sse-events`에서 이벤트 이름으로 DTO 스키마를 찾을 수 있습니다. 브라우저 작업에 프로그램 키용 대안을 연결하려면 작업의 `x-program-alternative`에 `POST /api/download/tasks`처럼 메서드와 경로를 지정하세요.

## 작업 설명과 선택 사항인 ‘상세 정보’ 작성

컨트롤러의 `@HttpApiGroup("core-batch")`는 해당 모듈의 `src/main/resources/i18n/http/core-batch.properties` 파일을 지정합니다. 설명은 이 파일에, 번역은 `core-batch_en.properties`처럼 같은 디렉터리에 있는 언어별 파일에 작성하세요. 호스트는 OpenAPI 문서를 생성할 때 문서 언어에 맞는 리소스를 읽습니다.

키는 패키지명을 뺀 컨트롤러 클래스명, Java 메서드명, 대문자로 쓴 HTTP 메서드 순으로 시작합니다. 메서드 매핑에서 HTTP 메서드를 하나로 지정하지 않았다면 `ANY`를 사용하세요. 예를 들어 `POST /api/download/batches`에서는 `ManualBatchController.create.POST.summary`가 제목, `.description`이 개요, `ManualBatchController.create.POST.details`가 선택 사항인 Markdown 본문입니다.

호스트가 본문을 OpenAPI 작업의 `description`에 덧붙이면 `/openapi`에 ‘상세 정보’가 접힌 상태로 표시됩니다. 제목을 누르면 본문 전체가 펼쳐집니다. `POST /api/download/tasks`에서는 활성 다운로드 제공자가 `schema.DownloadSubmission.<workType>.description`으로 유형별 설명을 추가할 수도 있습니다. 작업과 제공자 모두 본문을 제공하지 않으면 이 절은 표시되지 않습니다.

작성 전에 HTTP 진입점부터 도달할 수 있는 모든 실행 경로를 끝까지 확인하세요. 해당 작업의 권한 검사, 입력 검증, 제공자 호출, 비동기 실행, 파일 쓰기, 결과 조회와 실패 복구 경로를 포함합니다. 접수 시점에 알 수 있는 결과와 작업이 끝나야 알 수 있는 결과를 구분해 적으세요. 각 언어의 `/api/openapi.json`을 확인한 뒤 `/openapi`에서 해당 메서드와 경로를 열어 ‘상세 정보’가 처음에는 접혀 있는지 확인하고 키보드로 펼쳐 본문을 확인하세요.

## API가 필요한 기능

업무 상태 조회, 설정 읽기와 저장, 프리셋 적용, 다운로드, 번역, 가져오기, 내보내기, 마이그레이션, 유지보수, 시스템 관리에는 HTTP API를 제공합니다. 동일한 API가 있으면 업무 서비스를 재사용하며 GUI마다 실행 로직을 복제하지 않습니다.

창 크기, 포커스, 테마 미리보기, 클립보드, 시스템 파일 선택기, 로컬 브라우저 열기는 클라이언트 상호작용입니다. 서버에서 흉내 내지 말고 업무 부분을 분리합니다. 원격 클라이언트의 파일은 업로드해야 하며 서버 경로는 서버 파일 시스템을 가리킵니다.

장시간 작업에는 시작, 상태 조회, 실행 ID를 이용한 취소, 필요한 결과 조회를 제공합니다. 202는 접수를 뜻하며 취소 요청은 종료를 뜻하지 않습니다. 작업이 없다고 해서 안전하게 다시 제출할 수 있는 것은 아닙니다. 재시작 후 상태와 멱등성 범위를 명시합니다.

## 권한 선언

실제 기능 owner가 `PixivFeaturePlugin.routes()`에서 경로를 선언합니다. `AccessPolicy`는 사람, 게스트, 로컬 흐름의 규칙을 유지합니다. 프로그램 접근은 `WebRouteContribution.programPermissions`에서 HTTP 메서드별로 명시합니다. 선언되지 않은 프로그램 접근은 거부되며 관리자 권한을 상속하지 않습니다.

```java
WebRouteContribution.admin("/api/example/items")
    .withProgramPermissions(Map.of(
        HttpMethod.GET, new ProgramPermission("items.read", "permission.items.read"),
        HttpMethod.POST, new ProgramPermission("items.create", "permission.items.create")
    ));
```

owner는 호스트의 등록 정보로 결정됩니다. owner가 `example`이면 전체 권한은 `example:items.read`와 `example:items.create`입니다. 레이블은 owner의 표시 네임스페이스에 속하는 i18n 리소스에 둡니다. 프로그램 권한은 명시적인 `/api/` 경로와 메서드에만 적용하며 `**`, OPTIONS, GUI/LOCAL 전용 흐름은 허용하지 않습니다. 전체 경로 구간 `*`는 리소스 ID를 나타낼 수 있습니다.

| 업무 | 분리할 권한 | 추가 확인 |
| --- | --- | --- |
| 설정 및 프리셋 | 읽기, 검증 및 저장 | 필드의 실제 owner, 비밀 값 반환 금지 |
| 미디어 유지보수 및 파일 가져오기 | 읽기, 미리보기, 실행, 취소, 완료 확인 | 정확한 publication, 미리보기 토큰, 실행 ID |
| 다운로드 및 번역 | 조회, 생성 또는 재개, 취소, 기록 삭제 | 데이터 범위, 자격 증명, 작업 소유자 |
| 데이터베이스 및 환경 | 조회, 검사, 미리보기, 마이그레이션, 내보내기, 복원 | 전체 설치 범위, 부작용, 확인, 쓰기 중단 기간 |
| 플러그인 및 시스템 | 조회, 설치, 시작·중지, 삭제, 재시작 | 실행 코드와 프로세스에 미치는 영향 |
| 키 및 관리자 자격 증명 | 사람인 관리자 전용 | 프로그램의 키 생성 및 자체 권한 상승 금지 |

같은 권한을 사용하는 엔드포인트는 업무 목적과 데이터 범위가 일치해야 합니다. 플러그인 선택은 현재 표시된 권한을 한꺼번에 선택하며 앞으로 추가될 권한을 포함하지 않습니다. 권한 카탈로그는 현재 선언과 기능에서 생성합니다. 관리자 세션은 GET `/api/security/program-keys/permissions`로 실제 ID, owner, 레이블, 엔드포인트를 조회할 수 있습니다.

공유 API는 대상 리소스도 확인합니다. 설정, 미디어, 파일 가져오기는 core 진입 권한과 대상 플러그인의 해당 권한이 모두 필요합니다. 반환 목록은 대상별로 필터링하고 일괄 쓰기는 부작용 전에 모든 대상을 확인합니다. 데이터베이스 마이그레이션과 환경 아카이브는 전체 설치에 적용되며 단일 플러그인 작업으로 해석해서는 안 됩니다. 철회되거나 교체된 publication을 다시 사용하거나 다른 owner의 같은 이름 경로로 권한을 얻어서는 안 됩니다.

## 입력, 오류 및 검증

메서드, 경로, 권한, 대상 범위, 요청 필드와 한도, 비동기 상태, 주요 오류를 문서화합니다. 신뢰 경계에서 경로, 자격 증명, JSON을 검증합니다. 키는 `Authorization: Bearer`로만 전달하고 URL, 로그, 브라우저 영구 저장소에 기록하지 않습니다. 원본 전달 헤더를 신뢰하지 말고 신뢰할 수 있는 프록시 필터가 정규화한 클라이언트 주소를 사용합니다. 기존 CSRF, 복구 모드, 플러그인 임대 규칙은 계속 적용됩니다.

오류에는 안정적인 `code`와 현지화된 `error`를 포함합니다. 잘못된 입력, 거부, 대상 없음, 리비전 충돌, 한도 초과, 기능 사용 불가, 결과 불확실을 구분합니다. 통신 중단 후에는 결과를 조회하고 쓰기를 자동 재전송하지 않습니다.

플러그인이 HTTP 입력을 직접 검증할 때는 SDK의 `top.sywyar.pixivdownload.core.web.HttpParameterException`을 사용할 수 있습니다. 이 클래스는 JDK에만 의존하는 `pixivdownload-core-api` 모듈에 있습니다. 예를 들어 `order` 쿼리 값이 허용된 목록에 없으면 `new HttpParameterException("query", "order", "invalid_value")`을 던집니다. 호스트는 HTTP 400과 함께 `code=error.request.param.invalid`, 요청 언어의 `error`, 그리고 `in`, `field`, `reason`을 반환합니다. 기존 플러그인은 자체 오류 응답을 계속 사용할 수 있습니다. 이 오류와 해당 필드를 인스턴스의 OpenAPI 문서에 표시하려면 해당 작업에 `@KnownErrorCodes(status = "400", value = "error.request.param.invalid")`를 추가하세요.

`in`에는 `query`, `path`, `header`, `part`, `body` 중 하나를 넣습니다. 앞의 네 경우에는 `field`에 요청에 쓰인 매개변수나 파트의 이름을 넣습니다. JSON 본문에는 `/options/pages`와 같은 JSON Pointer를 사용합니다. `reason`에는 `required`, `type`, `invalid_value`처럼 프로그램에서 판별할 수 있는 고정값을 사용하세요. 세 필드에 클라이언트가 입력한 값은 넣지 않습니다. Spring이 바인딩 중에 발견한 매개변수 누락이나 타입 변환 오류는 호스트가 위치를 식별합니다.

실제 필터 또는 HTTP 진입점에서 권한 부족 시 업무 부작용이 없는지 테스트합니다. 메서드, 대상 플러그인, 원본 네트워크, 철회, 교체, 저장 실패를 포함합니다. API를 변경할 때 권한 카탈로그, 호출부, 다섯 언어 문서, 관련 회귀 테스트를 함께 갱신합니다. 선언과 컴파일만으로 상호작용 및 실행 검증을 대신할 수 없습니다.

## 권한 범위와 전용 흐름

예약 작업에는 `core:schedule.*`의 해당 동작 권한과 수집원 플러그인의 `schedule.manage`가 필요합니다. 계정 정책 동작에는 정책 owner의 같은 권한이 필요합니다. 목록은 허용된 수집원으로 필터링합니다. 여기서 `*`는 문서상의 분류이며 저장할 수 있는 권한이 아닙니다. 예약 작업은 설치의 영구 설정이며, 권한은 해당 수집원의 전체 예약 처리 흐름을 위임합니다. 생성한 키를 폐기해도 예약 작업은 삭제되거나 비활성화되지 않습니다. 이후 실행을 중단하려면 먼저 작업을 비활성화하거나 삭제하세요.

플러그인 수명 주기, 활성 상태 및 신뢰 변경에는 해당 `core:plugins.*` 동작과 대상의 `<plugin>:plugins.manage`가 모두 필요합니다. 의존하는 소비자 플러그인이 있으면 해당 권한도 확인합니다. 설치된 관리 가능 플러그인은 로드되지 않은 상태에서도 선택할 수 있습니다. 설치 권한은 패키지와 종속 항목의 서버 코드 실행을 허용합니다. 전역 Hook 게시 권한은 보안 정책 변경을 허용하므로 이 범위를 고려하여 부여하세요.

공유 자격 증명은 `core:credentials.read`, `core:credentials.write`, `core:credentials.use`로 나뉩니다. 읽기는 정보만 반환하며 비밀 값은 반환하지 않습니다. `X-Acquisition-Credentials`로 저장된 자격 증명을 참조하려면 업무 권한 외에 `credentials.use`가 필요합니다. 현재 작업의 자격 증명을 직접 전달할 때는 `X-Acquisition-Credential`을 사용합니다.

갤러리, 소설 라이브러리, 컬렉션, 받은 편지함, 아카이브, 페이지 편집, 업데이트 및 유지 관리 권한은 공유 설치 전체에 적용되며 해당 키가 만든 데이터로 자동 제한되지 않습니다. 다운로드 작업과 수동 배치는 계속 키 식별자로 격리됩니다. 로컬 유지 관리, 복구 및 이전 메타데이터 마이그레이션에는 로컬 출발지도 필요합니다. 권한은 출처, 확인, 수정 번호 또는 임대 검증을 대체하지 않습니다.

인증 설정, 키 관리, GUI token, CAPTCHA, 브라우저 수집 티켓, 익명 할당량 및 페이지 조립은 전용 흐름을 유지합니다. 프로그램은 다운로드와 진행 상황에 `/api/download/tasks`, 파일 가져오기에 `/api/tools/file-import`, 작품 관리에 `/api/library/artworks/{id}`, 페이지 작업에 `/api/page-editor`를 사용합니다. 받은 편지함 테스트는 `/api/admin/notifications/test` 또는 `/test-all`을 사용합니다. 이전 브라우저 호환 경로에는 별도 프로그램 권한을 부여하지 않습니다.
