# HTTP API 및 프로그램 권한

외부 프로그램은 `Authorization: Bearer <프로그램-키>`를 사용할 수 있습니다. 보안 페이지의 '프로그램 액세스 키'에서 이름, 권한, 허용할 출발지 IP를 지정해 키를 만드세요. 전체 키는 한 번만 표시되며 서버에는 다이제스트만 저장됩니다. 기본 만료일은 없으며 권한과 출발지를 수정하거나 키를 폐기할 수 있습니다. 플러그인 전체를 선택해도 앞으로 추가될 권한은 포함되지 않습니다.

출발지 제한에는 숫자 형식의 IPv4, IPv6 및 CIDR 네트워크를 사용할 수 있습니다. 제한 모드에서는 항목이 하나 이상 필요하며 '모든 출발지'는 직접 선택해야 합니다. 역방향 프록시를 사용하기 전에 신뢰할 프록시를 설정하세요. 키와 관리자 세션, 초대 인증 또는 GUI 인증을 함께 보내지 마세요. 수집용 인증 정보는 별도의 `X-Acquisition-Credential`로 전달합니다.

관리자 세션은 계속 `X-Session-Token`으로 기존 API를 사용할 수 있습니다. 프로그램 키는 권한 카탈로그에 명시된 메서드와 경로에만 접근할 수 있습니다. 나머지 API는 기존의 관리자 직접 조작 또는 로컬 절차를 유지합니다. 키 관리와 관리자 인증 정보 변경에는 관리자 본인의 로그인이 필요합니다. 이 API는 MCP 서버를 제공하지 않습니다.

Cookie를 사용하는 쓰기 요청에는 동일 출처 검사가 적용됩니다. 브라우저의 교차 출처 요청은 거부되며 플러그인 관리와 예약 작업 등에는 명시적인 동일 출처 신호도 필요합니다. `/api/gui/**`는 로컬 컴퓨터와 GUI token 제한을 유지합니다. 원격 프로그램은 아래 업무 API를 사용합니다. 플러그인 기능은 해당 플러그인과 종속 기능이 사용 가능한 경우에만 제공됩니다.

## 엔드포인트 목록

| 기능 | 엔드포인트 |
| --- | --- |
| 설정 항목·기본값·비밀을 가린 현재 값 | GET `/api/settings`; GET `/api/settings/presets` |
| 설정 검증과 저장 | POST `/api/settings/validate`; POST `/api/settings` |
| 플러그인 저장소 | GET / PUT `/api/settings/repositories` |
| 데이터베이스 설정과 연결 테스트 | GET `/api/settings/database`, `/providers`, `/status`; POST `/check` |
| 데이터베이스 이전 | POST `/api/settings/database/migration-previews`, `/migrations`; GET `/migration` |
| 환경 아카이브 | POST `/api/settings/environment/preview`, `/restore`, `/exports`; GET `/restore`, `/exports`, `/exports/{id}/archive`, `/installation` |
| 메타데이터 보완 | GET `/api/tools/backfill/defaults`, `/tasks/current`, `/tasks/{id}`; POST `/candidates`, `/tasks`, `/tasks/{id}/cancel` |
| 미디어 유지보수와 기록 파일 가져오기 | GET `/api/tools/media`, `/api/tools/file-import`; 각 엔드포인트 아래: POST `/previews`, `/tasks`, `/tasks/cancel`, GET `/tasks/current` |
| 미디어 기능과 가져오기 완료 확인 | POST `/api/tools/media/capabilities`; POST `/api/tools/file-import/completion/acknowledge` |
| 도구 기록과 로그 첨부 | GET `/api/tools/history`, `/api/tools/logs/{fileName}` |
| 디렉터리 참조 확인과 수정 | GET / POST `/api/tools/directories` |
| 경로 접두사 | `/api/tools/path-prefixes` |
| 이미지 분류 | `/api/tools/classifier/settings`, `/folders`, `/images`, `/image`, `/moves` |
| FFmpeg 상태와 설치 | GET `/api/tools/ffmpeg`; POST `/api/tools/ffmpeg/tasks` |
| 시스템과 제어 센터 | `/api/admin/system`, `/api/admin/control-center` |
| AI·메일·푸시 테스트 | `/api/admin/ai/test`, `/models`; `/api/admin/mail/test`, `/test-all`; `/api/admin/push/test`, `/test-all` |
| 소프트웨어 업데이트 | `/api/admin/update/check`, `/last`, `/download`, `/download/progress`, `/install` |
| 개별 다운로드와 기록 | `/api/download/tasks`, `/api/download/tasks/history` |
| 백그라운드 수동 배치 | `/api/download/batches` |

표의 짧은 경로는 같은 행의 API 계열 공통 접두사에 이어집니다. 번역은 기존 `/api/translation/**`를 사용합니다. 용어집, 낭독, 갤러리, 컬렉션, 예약 작업, 유지보수와 플러그인 마켓도 각 API를 사용합니다. 데스크톱 창, 파일 선택기, 로컬 브라우저 열기는 클라이언트 작업입니다.

## 설정 저장

먼저 항목 목록을 읽고 반환된 `owner`, `packageId`, `generation`, `key`로 변경을 제출합니다. 호스트 항목은 owner/packageId가 null이고 generation은 0입니다. 민감한 항목은 `stored` 표시만 반환하며 값이나 기본 비밀 값은 반환하지 않습니다. `KEEP`, `SET`, `CLEAR`로 유지, 교체, 삭제를 지정합니다. 프리셋은 같은 저장 API로 적용하는 항목 값의 모음입니다. `effect`는 적용 시점을 나타내며 저장 자체로 백엔드나 프로세스가 재시작되지는 않습니다.

POST `/api/settings/validate` 또는 `/api/settings`의 본문 예시:

```json
{
  "changes": [
    {
      "owner": null,
      "packageId": null,
      "generation": 0,
      "key": "server.port",
      "action": "SET",
      "value": "6999"
    }
  ]
}
```

저장소 편집에는 GET이 반환한 `revision`이 필요합니다. 저장소 추가 또는 신뢰·주소·네트워크 정책 변경에는 `trustConfirmed:true`를 보냅니다. 충돌하면 다시 읽고 다른 편집을 덮어쓰지 마세요. 연결 테스트와 도구의 임시 옵션은 설정을 저장하지 않습니다.

## 백그라운드 수동 배치

1. GET `/api/schedule/sources`에서 현재 `sourceType`과 `activationToken`을 가져옵니다. 정의 형식은 원본 플러그인이 해석합니다.
2. POST `/api/download/batches`로 `name`, `sourceType`, `activationToken`, `definitionJson`과 선택적 `proxy`를 제출합니다. `definitionJson`은 최대 1 MiB UTF-8의 JSON 문자열이고 전체 요청은 최대 8 MiB입니다.
3. 반환된 UUID로 GET `/api/download/batches/{id}`와 `/{id}/items?offset=0&limit=100`을 조회합니다. 항목은 배치 UUID와 `workType`, `id`의 조합으로 식별하며 개별 다운로드 실행 UUID와 다릅니다.
4. POST `/{id}/pause`, `/cancel`, `/resume`, `/retry`는 각각 일시 중지, 취소, 검색 재개, 실패 항목만 재시도합니다. 실제 실행이 끝나야 중지 상태가 종료됩니다. 발견된 실패 항목의 재시도로 미완료 원본 검색까지 수행하지는 않습니다.
5. 유휴 상태에서 DELETE `/{id}`로 기록을 삭제합니다. 프로세스는 배치 하나만 보관하므로 새 배치를 만들기 전에 기존 배치를 삭제해야 합니다. 프로세스 재시작 후 결과는 복원되지 않습니다.

`explicit-list`는 일러스트와 소설을 함께 받습니다. 다음 객체를 `definitionJson` 문자열로 직렬화하세요. 작품 ID는 양의 정수 십진 문자열이며 같은 유형 안에서 중복될 수 없습니다:

```json
{
  "kind": "mixed",
  "source": {
    "acquisitionMode": "single-import",
    "works": [
      {
        "kind": "illust",
        "id": "123"
      },
      {
        "kind": "novel",
        "id": "456"
      }
    ]
  },
  "filters": {
    "content": "safe",
    "aiFilter": "exclude",
    "tagsExact": [],
    "tagsFuzzy": []
  },
  "download": {
    "concurrent": 1,
    "intervalMs": 1000,
    "bookmark": false
  }
}
```

예시 ID를 대상 작품 ID로 바꾸세요. 다른 원본으로 작가 게시물, 의뢰, 검색, 시리즈, 북마크, 팔로우 새 작품, 컬렉션을 사용할 수 있습니다. 필터는 등급, AI, 태그, 일러스트 유형, 페이지 수, 소설 글자 수, 북마크 수를 지원합니다. 북마크 수 필터가 켜져 있으면 알 수 없는 수치는 통과하지 못합니다. 재개 시 완료되거나 필터로 건너뛴 작품은 다시 실행하지 않습니다.

배치는 최대 5,000항목과 인코딩된 내용 예약 예산 64 MiB 중 먼저 도달하는 한도가 적용됩니다. JVM 힙 사용량 보장은 아닙니다. 페이지당 최대 200항목입니다. PUT `/{id}/credentials`에 `{"expectedRun":1}`을 보내 헤더의 자격 증명으로 교체하며 헤더를 생략하면 삭제합니다. 해당 실행 회차가 유휴 상태여야 하며 재개 전에 다시 검사합니다. POST `/{id}/resolve`는 `expectedRun`, `workType`, `workId`, `reasonCode`, `userAction`, `rememberForRun`을 받습니다. 사유와 회차는 현재 보류 기록과 일치해야 합니다. 두 요청의 본문 한도는 각각 16 KiB입니다.

## 도구와 재시작 작업

미디어 유지보수와 기록 파일 가져오기는 먼저 목록을 읽고 정확한 `identity`(pluginId/packageId/generation/publication)와 `options`로 미리 봅니다. 같은 신원과 미리보기 `token`으로 시작하고 취소에는 `executionId`도 보냅니다. 플러그인이 교체되거나 데스크톱에서 대체 작업을 시작하면 오래된 token/UUID는 새 작업에 영향을 주지 않습니다. 경로는 원격 클라이언트가 아닌 서버 파일 시스템을 가리킵니다.

POST `/api/tools/backfill/tasks`의 본문 예시:

```json
{
  "delayMs": 0,
  "limit": 100,
  "dryRun": true,
  "alwaysNetwork": false
}
```

`limit:0`은 전체 후보이며 본문은 최대 4 KiB입니다. 202는 수락만 의미하므로 실행 상태를 계속 조회하세요. 분류 이동은 복사, 원본 디렉터리 삭제, 데이터베이스 등록 결과를 따로 반환합니다. 일부만 완료되면 결과를 확인한 후 이동을 재시도하세요.

데이터베이스 이전은 미리보기 token으로 확정합니다. 환경 복원은 미리보기 token, 선택 항목, 경로 매핑, 비밀번호를 받으며 `RESTORE_PENDING` 이후 대체 프로세스가 실행합니다. 내보내기는 서버의 절대 `destination`과 `confirmRestart:true`가 필수이며 `password`, `includeCredentials`, `includeDatabases`, `includeState`, `includeResources`를 선택할 수 있습니다. 리소스에는 데이터베이스도 포함해야 하며 자격 증명·데이터베이스·상태에는 비밀번호가 필요합니다. 기존 대상은 덮어쓰지 않습니다. 백엔드를 중지하고 내보낸 뒤 전체 재시작을 요청하므로 다시 연결하여 조회하세요. 재시작에 실패하면 애플리케이션을 수동으로 시작해야 합니다. 업데이트 설치에는 현재 검증된 패키지의 SHA-256이 필요합니다.

## 오류와 기록

오류에는 기계용 `code`와 번역된 `error`가 포함됩니다. 400은 잘못된 입력, 401/403은 인증·권한 거부, 404는 식별자 없음, 409는 사용 중·충돌·허용되지 않는 상태, 413은 본문 초과, 503은 기능 사용 불가입니다. 모든 비 2xx 응답을 자동 재시도하지 말고 업무 상태에 따라 처리하세요.

GET `/api/download/tasks/history`는 기본 50개, 최대 200개입니다. 반환된 `beforeTime`과 `beforeId`를 함께 보내 다음 페이지를 조회합니다. 저장된 종료 결과만 나열하며 진행 중 다운로드는 복원하지 않습니다. 개별 다운로드 중복 제거는 현재 프로세스의 보관 기간에만 적용됩니다. 알 수 없는 실행을 안전하게 다시 제출할 수 있다는 뜻은 아닙니다. 도구 기록과 배치 결과에는 별도 보관 규칙이 적용됩니다.
## 프로그램 권한 목록

관리자는 GET `/api/security/program-keys/permissions`로 현재 설치에서 유효한 카탈로그를 조회할 수 있습니다. 표는 내장 선언이며 외부 플러그인은 현재 카탈로그를 확인하세요. `*`는 경로 한 구간을 뜻합니다. 키에는 명시한 권한만 저장되며 플러그인이나 경로 와일드카드 권한은 지원하지 않습니다.

공유 설정, 미디어 도구, 파일 가져오기에는 `core` 진입점 권한과 대상 플러그인의 같은 권한이 모두 필요합니다. 목록에는 허용된 대상만 표시됩니다. 개별 다운로드에는 `core:downloads.create`와 실제 다운로드 유형 소유 플러그인의 `downloads.create`가 필요합니다. 배치에는 `core:batches.create` 또는 `core:batches.resume`와 수집원 및 실행 계획에 포함된 모든 작품 플러그인의 `batches.execute`가 필요합니다. 수집원 목록은 `core:schedule.sources.read`로 조회합니다.

프로그램은 자신이 만든 다운로드 작업과 배치만 조회, 취소, 관리할 수 있습니다. 관리자는 설치 전체를 관리할 수 있습니다. 배치는 프로세스당 하나이며 키를 늘려도 슬롯은 늘어나지 않습니다. 번역, 도구, 설정, 로그, 데이터베이스 및 환경 권한은 해당 플러그인 또는 설치에 적용되며 키별 전용 복사본이 아닙니다. 데이터베이스 마이그레이션과 환경 아카이브는 설치 전체에서 선택한 내용을 다룹니다.

키 관리에는 관리자 세션이 필요합니다. GET `/api/security/program-keys`는 목록, GET `/permissions`는 카탈로그, 루트 경로의 POST는 생성, PUT `/{id}`는 편집, DELETE `/{id}`는 폐기입니다. 생성 시 `name`, `permissions`, `anyIp`, `networks`를 지정합니다. 편집은 `{"revision":1,"policy":{...}}`, 폐기는 `{"revision":1}`을 사용합니다. 조회한 수정 번호를 사용하고 충돌하면 다시 조회하세요. 생성 시 `key`와 한 번만 표시되는 `secret`이 반환되며 목록에는 비밀 값이나 다이제스트가 포함되지 않습니다.

설치당 최대128개의 키를 보관합니다. 이름은80자, 권한은512개, 출발지 네트워크는64개까지이며 관리 요청 본문은128 KiB까지입니다. 마지막 사용 시각은 현재 프로세스에서 확인한 인증 성공을 기록합니다. 키 폐기는 새 요청을 차단하지만 이미 수락된 작업을 자동 취소하지 않습니다.

시작 캐시는 GET `/api/tools/startup-cache`, POST `/api/tools/startup-cache/tasks`, POST `/api/tools/startup-cache/tasks/{id}/cancel`을 사용합니다. 시작 요청 본문은 `{}`입니다. 수락되었다고 학습이 성공한 것은 아니므로 상태를 계속 조회하세요.

### 권한 범위와 전용 흐름

예약 작업에는 `core:schedule.*`의 해당 동작 권한과 수집원 플러그인의 `schedule.manage`가 필요합니다. 계정 정책 동작에는 정책 owner의 같은 권한이 필요합니다. 목록은 허용된 수집원으로 필터링합니다. 여기서 `*`는 문서상의 분류이며 저장할 수 있는 권한이 아닙니다. 예약 작업은 설치의 영구 설정이며, 권한은 해당 수집원의 전체 예약 처리 흐름을 위임합니다. 생성한 키를 폐기해도 예약 작업은 삭제되거나 비활성화되지 않습니다. 이후 실행을 중단하려면 먼저 작업을 비활성화하거나 삭제하세요.

플러그인 수명 주기, 활성 상태 및 신뢰 변경에는 해당 `core:plugins.*` 동작과 대상의 `<plugin>:plugins.manage`가 모두 필요합니다. 의존하는 소비자 플러그인이 있으면 해당 권한도 확인합니다. 설치된 관리 가능 플러그인은 로드되지 않은 상태에서도 선택할 수 있습니다. 설치 권한은 패키지와 종속 항목의 서버 코드 실행을 허용합니다. 전역 Hook 게시 권한은 보안 정책 변경을 허용하므로 이 범위를 고려하여 부여하세요.

공유 자격 증명은 `core:credentials.read`, `core:credentials.write`, `core:credentials.use`로 나뉩니다. 읽기는 정보만 반환하며 비밀 값은 반환하지 않습니다. `X-Acquisition-Credentials`로 저장된 자격 증명을 참조하려면 업무 권한 외에 `credentials.use`가 필요합니다. 현재 작업의 자격 증명을 직접 전달할 때는 `X-Acquisition-Credential`을 사용합니다.

갤러리, 소설 라이브러리, 컬렉션, 받은 편지함, 아카이브, 페이지 편집, 업데이트 및 유지 관리 권한은 공유 설치 전체에 적용되며 해당 키가 만든 데이터로 자동 제한되지 않습니다. 다운로드 작업과 수동 배치는 계속 키 식별자로 격리됩니다. 로컬 유지 관리, 복구 및 이전 메타데이터 마이그레이션에는 로컬 출발지도 필요합니다. 권한은 출처, 확인, 수정 번호 또는 임대 검증을 대체하지 않습니다.

인증 설정, 키 관리, GUI token, CAPTCHA, 브라우저 수집 티켓, 익명 할당량 및 페이지 조립은 전용 흐름을 유지합니다. 프로그램은 다운로드와 진행 상황에 `/api/download/tasks`, 파일 가져오기에 `/api/tools/file-import`, 작품 관리에 `/api/library/artworks/{id}`, 페이지 작업에 `/api/page-editor`를 사용합니다. 받은 편지함 테스트는 `/api/admin/notifications/test` 또는 `/test-all`을 사용합니다. 이전 브라우저 호환 경로에는 별도 프로그램 권한을 부여하지 않습니다.

| 권한 | 용도 | 메서드 및 경로 |
| --- | --- | --- |
| `ai:connection.test` | AI 연결 테스트(모델 호출) | POST `/api/admin/ai/test` |
| `ai:models.read` | 사용 가능한 AI 모델 조회 | POST `/api/admin/ai/models` |
| `ai:status.read` | AI 설정 상태 보기 | GET `/api/admin/ai/status` |
| `core:application.read` | 앱 정보 및 UI 언어 리소스 읽기 | GET `/api/app/info`; GET `/api/i18n/messages/*`; GET `/api/i18n/meta` |
| `core:archives.create` | 일러스트 내보내기 압축 파일 만들기 | POST `/api/archive/pack-artworks` |
| `core:archives.download` | 이 설치의 내보내기 압축 파일 다운로드 | GET `/api/archive/download/*` |
| `core:archives.read` | 이 설치의 내보내기 압축 파일 및 상태 보기 | GET `/api/archive/list`; GET `/api/archive/status/*` |
| `core:assets.export` | 일러스트 원본 파일 다운로드 | GET `/api/downloaded/rawfile/*/*` |
| `core:assets.read` | 일러스트 미리 보기 및 이미지 읽기 | GET `/api/downloaded/image/*/*`; GET `/api/downloaded/thumbnail-file/*/*`; GET `/api/downloaded/thumbnail/*/*` |
| `core:authors.read` | 전체 라이브러리 작가 보기 | GET `/api/authors/paged`; GET `/api/authors` |
| `core:authors.refresh` | Pixiv 작가 정보 새로 고침 | POST `/api/pixiv/user/*/meta/schedule` |
| `core:backfill.cancel` | 기록 보충 취소 | POST `/api/tools/backfill/tasks/*/cancel` |
| `core:backfill.execute` | 기록 보충 실행 | POST `/api/tools/backfill/tasks` |
| `core:backfill.read` | 기록 보충 대상 및 상태 조회 | GET `/api/tools/backfill/defaults`; GET `/api/tools/backfill/tasks/*`; GET `/api/tools/backfill/tasks/current`; POST `/api/tools/backfill/candidates` |
| `core:batches.cancel` | 이 키의 배치 취소 | POST `/api/download/batches/*/cancel` |
| `core:batches.create` | 백그라운드 배치 만들기 | POST `/api/download/batches` |
| `core:batches.credentials` | 이 키의 배치 인증 정보 변경 | PUT `/api/download/batches/*/credentials` |
| `core:batches.delete` | 이 키의 배치 기록 삭제 | DELETE `/api/download/batches/*` |
| `core:batches.pause` | 이 키의 배치 일시 중지 | POST `/api/download/batches/*/pause` |
| `core:batches.read` | 이 키의 배치 및 결과 조회 | GET `/api/download/batches/*/items`; GET `/api/download/batches/*`; GET `/api/download/batches` |
| `core:batches.resolve` | 이 키의 배치에서 확인 대기 항목 처리 | POST `/api/download/batches/*/resolve` |
| `core:batches.resume` | 이 키의 배치 재개 또는 재시도 | POST `/api/download/batches/*/resume`; POST `/api/download/batches/*/retry` |
| `core:classifier.configure` | 이미지 분류 설정 변경 | PUT `/api/tools/classifier/settings` |
| `core:classifier.delete` | 이미지 분류 디렉터리 삭제 | DELETE `/api/tools/classifier/folders` |
| `core:classifier.read` | 이미지 분류 및 이미지 조회 | GET `/api/tools/classifier/folders`; GET `/api/tools/classifier/image`; GET `/api/tools/classifier/images`; GET `/api/tools/classifier/settings` |
| `core:classifier.write` | 분류된 이미지 이동 | POST `/api/tools/classifier/moves` |
| `core:collections.delete` | 컬렉션 삭제 | DELETE `/api/collections/*` |
| `core:collections.members.write` | 컬렉션 작품 추가 및 제거 | DELETE `/api/collections/*/artworks/*`; DELETE `/api/collections/*/novels/*`; POST `/api/collections/*/artworks/*`; POST `/api/collections/*/novels/*` |
| `core:collections.read` | 전체 라이브러리 컬렉션 및 소속 읽기 | GET `/api/collections/*/icon`; GET `/api/collections/novels/of/*`; GET `/api/collections/of/*`; GET `/api/collections`; POST `/api/collections/memberships`; POST `/api/collections/novels/memberships` |
| `core:collections.write` | 컬렉션, 아이콘 및 다운로드 폴더 만들기 및 수정 | DELETE `/api/collections/*/icon`; POST `/api/collections/*/icon`; POST `/api/collections`; PUT `/api/collections/*/download-root`; PUT `/api/collections/*/sort-order`; PUT `/api/collections/*` |
| `core:control-center.directory` | 서버에서 제어 센터 폴더 열기 | POST `/api/admin/control-center/directory` |
| `core:control-center.read` | 제어 센터 상태 보기 | GET `/api/admin/control-center` |
| `core:credentials.read` | 수집 자격 증명 메타데이터 읽기(비밀 제외) | GET `/api/acquisition/credentials` |
| `core:credentials.use` | 허용된 요청에서 공유 수집 자격 증명 사용 | HEADER `X-Acquisition-Credentials` |
| `core:credentials.write` | 공유 수집 자격 증명 라이브러리 수정 | PUT `/api/acquisition/credentials` |
| `core:database.check` | 데이터베이스 연결 확인 | POST `/api/settings/database/check` |
| `core:database.migrate` | 설치 전체의 데이터베이스 마이그레이션 | POST `/api/settings/database/migrations` |
| `core:database.preview` | 설치 전체의 데이터베이스 마이그레이션 미리 보기 | POST `/api/settings/database/migration-previews` |
| `core:database.read` | 데이터베이스 설정 및 마이그레이션 상태 조회 | GET `/api/settings/database/migration`; GET `/api/settings/database/providers`; GET `/api/settings/database/status`; GET `/api/settings/database` |
| `core:directories.read` | 작품 디렉터리 확인 | GET `/api/tools/directories` |
| `core:directories.write` | 작품 디렉터리 기록 변경 | POST `/api/tools/directories` |
| `core:downloads.cancel` | 이 키의 다운로드 작업 취소 | POST `/api/download/queue/*/cancel`; POST `/api/download/queue/clear`; POST `/api/download/tasks/*/cancel` |
| `core:downloads.create` | 다운로드 작업 만들기 | POST `/api/download/tasks` |
| `core:downloads.extensions.read` | 다운로드 확장 기능 보기 | GET `/api/download/extensions` |
| `core:downloads.read` | 이 키의 다운로드 작업 조회 | GET `/api/download/tasks/*`; GET `/api/download/tasks/history`; GET `/api/download/tasks` |
| `core:environment.export` | 전체 환경 아카이브 내보내기 | GET `/api/settings/environment/exports/*/archive`; POST `/api/settings/environment/exports` |
| `core:environment.preview` | 전체 환경 복원 미리 보기 | POST `/api/settings/environment/preview` |
| `core:environment.read` | 설치 및 환경 이전 상태 조회 | GET `/api/settings/environment/exports`; GET `/api/settings/environment/installation`; GET `/api/settings/environment/restore` |
| `core:environment.restore` | 전체 환경 복원 | POST `/api/settings/environment/restore` |
| `core:ffmpeg.install` | FFmpeg 설치 | POST `/api/tools/ffmpeg/tasks` |
| `core:ffmpeg.read` | FFmpeg 상태 조회 | GET `/api/tools/ffmpeg` |
| `core:file-import.acknowledge` | 파일 가져오기 결과 확인 | POST `/api/tools/file-import/completion/acknowledge` |
| `core:file-import.cancel` | 파일 가져오기 취소 | POST `/api/tools/file-import/tasks/cancel` |
| `core:file-import.execute` | 파일 가져오기 실행 | POST `/api/tools/file-import/tasks` |
| `core:file-import.preview` | 파일 가져오기 미리 보기 | POST `/api/tools/file-import/previews` |
| `core:file-import.read` | 파일 가져오기 도구 및 작업 조회 | GET `/api/tools/file-import/tasks/current`; GET `/api/tools/file-import` |
| `core:hooks.prepare` | 작업 훅 설정 변경 미리 보기 | POST `/api/web/operation-hooks/declarations/prepare`; POST `/api/web/operation-hooks/prepare` |
| `core:hooks.publish` | 전역 작업 훅 보안 정책 게시 | POST `/api/web/operation-hooks/publish` |
| `core:hooks.read` | 전역 작업 훅 및 권한 설정 읽기 | GET `/api/web/operation-hooks/catalog`; GET `/api/web/operation-hooks/configuration`; GET `/api/web/operation-hooks/declarations`; GET `/api/web/operation-hooks/definitions`; GET `/api/web/operation-hooks/targets` |
| `core:legacy-migration.execute` | 이전 JSON 메타데이터 이전(로컬 전용) | GET `/api/migration/json-to-sqlite/stream`; POST `/api/migration/json-to-sqlite` |
| `core:library.move` | 서버의 일러스트 파일 이동 | POST `/api/library/artworks/*/move` |
| `core:library.read` | 전체 일러스트 등록 정보, 기록 및 경로 보기 | GET `/api/downloaded/by-move-folder`; GET `/api/downloaded/history/paged`; GET `/api/downloaded/history`; GET `/api/downloaded/statistics`; GET `/api/library/artworks/*`; POST `/api/downloaded/batch` |
| `core:library.recover` | 일러스트 메타데이터 복구 | POST `/api/library/artworks/*/recover-metadata` |
| `core:local-import.cancel` | 로컬 가져오기 검사 및 등록 취소 | POST `/api/local-import/previews/*/cancel`; POST `/api/local-import/tasks/*/cancel` |
| `core:local-import.execute` | 로컬 일러스트를 데이터베이스에 등록 | POST `/api/local-import/tasks` |
| `core:local-import.preview` | 서버의 로컬 일러스트 검사 | POST `/api/local-import/previews` |
| `core:local-import.read` | 로컬 가져오기 프리셋 및 작업 읽기 | GET `/api/local-import/presets`; GET `/api/local-import/previews/*/items`; GET `/api/local-import/previews/*`; GET `/api/local-import/tasks/*/items`; GET `/api/local-import/tasks/*` |
| `core:logs.read` | 실행 로그 조회 | GET `/api/tools/logs/*` |
| `core:maintenance.read` | 유지 관리 상태 보기(로컬 전용) | GET `/api/admin/maintenance/status` |
| `core:maintenance.run` | 유지 관리 실행(로컬 전용) | POST `/api/admin/maintenance/run` |
| `core:media.cancel` | 미디어 처리 취소 | POST `/api/tools/media/tasks/cancel` |
| `core:media.execute` | 미디어 처리 실행 | POST `/api/tools/media/tasks` |
| `core:media.preview` | 미디어 처리 미리 보기 | POST `/api/tools/media/previews` |
| `core:media.read` | 미디어 도구 및 작업 조회 | GET `/api/tools/media/tasks/current`; GET `/api/tools/media`; POST `/api/tools/media/capabilities` |
| `core:paths.read` | 경로 접두사 조회 | GET `/api/tools/path-prefixes` |
| `core:paths.write` | 경로 접두사 변경 | POST `/api/tools/path-prefixes/pin`; POST `/api/tools/path-prefixes` |
| `core:pixiv.read` | Pixiv 온라인 데이터 조회 | GET `/api/pixiv/me/uid`; GET `/api/pixiv/thumbnail-proxy`; GET `/api/pixiv/user/*/meta` |
| `core:plugins.enabled` | 허용된 플러그인의 활성화 상태 변경 | PUT `/api/plugins/*/enabled-preview`; PUT `/api/plugins/*/enabled` |
| `core:plugins.install` | 로컬 플러그인 설치(서버 코드 실행 허용) | POST `/api/plugins/install` |
| `core:plugins.load` | 허용된 플러그인 로드 | POST `/api/plugins/*/actions/load`; POST `/api/plugins/*/load` |
| `core:plugins.quiesce` | 허용된 플러그인의 새 요청 중지 | POST `/api/plugins/*/actions/quiesce`; POST `/api/plugins/*/quiesce` |
| `core:plugins.read` | 플러그인 상태, 의존성 및 설치 작업 보기 | GET `/api/plugins/*/impact`; GET `/api/plugins/acquisitions/*`; GET `/api/plugins/acquisitions`; GET `/api/plugins/status`; GET `/api/plugins/updates` |
| `core:plugins.reload` | 허용된 플러그인 다시 로드 | POST `/api/plugins/*/actions/reload`; POST `/api/plugins/*/reload` |
| `core:plugins.remove` | 허용된 플러그인 패키지 삭제 | POST `/api/plugins/*/actions/remove`; POST `/api/plugins/*/remove` |
| `core:plugins.restart` | 허용된 플러그인 다시 시작 | POST `/api/plugins/*/actions/restart`; POST `/api/plugins/*/restart` |
| `core:plugins.revocations.refresh` | 플러그인 저장소 폐기 정보 새로 고침 | POST `/api/plugins/revocations/*/refresh` |
| `core:plugins.start` | 허용된 플러그인 시작 | POST `/api/plugins/*/actions/start`; POST `/api/plugins/*/start` |
| `core:plugins.stop` | 허용된 플러그인 중지 | POST `/api/plugins/*/actions/stop`; POST `/api/plugins/*/stop` |
| `core:plugins.trust` | 허용된 플러그인의 실행 신뢰 승인 및 취소 | DELETE `/api/plugins/*/trust`; PUT `/api/plugins/*/trust` |
| `core:plugins.unload` | 허용된 플러그인 실행 인스턴스 언로드 | POST `/api/plugins/*/actions/unload`; POST `/api/plugins/*/unload` |
| `core:profile.read` | 이 설치의 표시 이름 읽기 | GET `/api/onboarding/profile` |
| `core:profile.write` | 이 설치의 표시 이름 변경 | POST `/api/onboarding/profile` |
| `core:recovery.read` | 복구 모드 진단 보기 | GET `/api/plugins/recovery` |
| `core:repositories.read` | 플러그인 저장소 설정 조회 | GET `/api/settings/repositories` |
| `core:repositories.write` | 플러그인 저장소 설정 변경 | PUT `/api/settings/repositories` |
| `core:schedule.create` | 영구 일정 만들기(키 폐기 후에도 유지) | POST `/api/schedule/tasks` |
| `core:schedule.credentials` | 일정 자격 증명 및 계정 정책 관리 | DELETE `/api/schedule/tasks/*/credential`; POST `/api/schedule/credential-policies/actions`; POST `/api/schedule/tasks/*/credential` |
| `core:schedule.delete` | 영구 일정 삭제 | DELETE `/api/schedule/tasks/*` |
| `core:schedule.pause` | 영구 일정 일시 중지 | POST `/api/schedule/tasks/*/pause` |
| `core:schedule.pending.write` | 일정 보류 항목 해결 및 지우기 | DELETE `/api/schedule/tasks/*/pending`; POST `/api/schedule/tasks/*/pending/resolve` |
| `core:schedule.read` | 허용된 소스의 영구 일정 읽기 | GET `/api/schedule/tasks/*/pending`; GET `/api/schedule/tasks/*/queue`; GET `/api/schedule/tasks/*`; GET `/api/schedule/tasks` |
| `core:schedule.run` | 영구 일정 활성화, 실행 및 재개 | POST `/api/schedule/tasks/*/enabled`; POST `/api/schedule/tasks/*/resume`; POST `/api/schedule/tasks/*/run` |
| `core:schedule.sources.read` | 배치 및 예약 작업의 수집원 목록 조회 | GET `/api/schedule/sources` |
| `core:schedule.write` | 영구 일정 및 프록시 수정 | POST `/api/schedule/tasks/*/proxy`; PUT `/api/schedule/tasks/*` |
| `core:scripts.read` | 설치된 플러그인의 사용자 스크립트 읽기 | GET `/api/scripts/*.user.js`; GET `/api/scripts/*/install`; GET `/api/scripts/*`; GET `/api/scripts` |
| `core:series.read` | 전체 라이브러리 일러스트 시리즈 보기 | GET `/api/series/*/cover`; GET `/api/series/*`; GET `/api/series/paged`; GET `/api/series` |
| `core:settings.read` | 설정 및 프리셋 읽기 | GET `/api/settings/presets`; GET `/api/settings` |
| `core:settings.write` | 설정 검증 및 저장 | POST `/api/settings/validate`; POST `/api/settings` |
| `core:startup-cache.cancel` | 시작 캐시 학습 취소 | POST `/api/tools/startup-cache/tasks/*/cancel` |
| `core:startup-cache.execute` | 시작 캐시 학습 | POST `/api/tools/startup-cache/tasks` |
| `core:startup-cache.read` | 시작 캐시 상태 보기 | GET `/api/tools/startup-cache` |
| `core:system.check` | 네트워크 연결 확인 | GET `/api/onboarding/connectivity`; POST `/api/admin/system/connectivity` |
| `core:system.exit` | 복구 모드 앱 종료(로컬 전용) | POST `/api/plugins/recovery/exit` |
| `core:system.read` | 시스템 상태 조회 | GET `/api/admin/system` |
| `core:system.reload` | 설정 다시 불러오기 | POST `/api/admin/system/reload` |
| `core:system.restart` | 프로그램 다시 시작 | POST `/api/admin/system/restart`; POST `/api/plugins/backend-restart`; POST `/api/plugins/recovery/restart` |
| `core:tools.history.read` | 도구 실행 기록 조회 | GET `/api/tools/history` |
| `core:updates.download` | 앱 업데이트 다운로드 | POST `/api/admin/update/download` |
| `core:updates.install` | 앱 업데이트 설치 | POST `/api/admin/update/install` |
| `core:updates.read` | 앱 업데이트 및 다운로드 진행 상황 확인 | GET `/api/admin/update/check`; GET `/api/admin/update/download/progress`; GET `/api/admin/update/last` |
| `download-workbench:page.write` | 다운로드 작업대 페이지 전환 | POST `/api/batch/page` |
| `download-workbench:pixiv.read` | Pixiv 온라인 데이터 조회 | GET `/api/pixiv/artwork/*/meta`; GET `/api/pixiv/artwork/*/pages`; GET `/api/pixiv/artwork/*/ugoira`; GET `/api/pixiv/me/collection/*/works`; GET `/api/pixiv/me/collections`; GET `/api/pixiv/me/follow-latest`; GET `/api/pixiv/me/following`; GET `/api/pixiv/me/illust-bookmarks`; GET `/api/pixiv/search/range`; GET `/api/pixiv/search`; GET `/api/pixiv/series/*`; GET `/api/pixiv/user/*/artworks`; GET `/api/pixiv/user/*/illust-cards`; GET `/api/pixiv/user/*/request-artworks` |
| `download-workbench:profiles.read` | 공유 다운로드 프리셋 읽기 | GET `/api/batch/profiles` |
| `download-workbench:profiles.write` | 공유 다운로드 프리셋 수정 | PUT `/api/batch/profiles` |
| `download-workbench:state.read` | 솔로 모드 작업대 상태 읽기 | GET `/api/batch/state` |
| `download-workbench:state.write` | 솔로 모드 작업대 상태 수정 | POST `/api/batch/state` |
| `gallery-tools:duplicates.read` | 중복 작품 및 검사 상태 보기 | GET `/api/duplicates/groups`; GET `/api/duplicates/scan/status` |
| `gallery-tools:duplicates.scan` | 중복 작품 검사 | POST `/api/duplicates/rescan`; POST `/api/duplicates/scan` |
| `gallery-tools:statistics.read` | 전체 라이브러리 통계 보기 | GET `/api/stats/dashboard` |
| `gallery:collections.write` | 일러스트 컬렉션 소속 변경 | POST `/api/gallery/artworks/collect` |
| `gallery:delete` | 라이브러리 일러스트 및 파일 삭제 | DELETE `/api/gallery/artwork/*`; POST `/api/gallery/artworks/delete` |
| `gallery:export` | 라이브러리 일러스트 파일 내보내기 | POST `/api/gallery/artworks/export` |
| `gallery:read` | 전체 라이브러리 일러스트 및 태그 보기 | GET `/api/gallery/artwork/*/by-author`; GET `/api/gallery/artwork/*/by-series`; GET `/api/gallery/artwork/*/related`; GET `/api/gallery/artwork/*/series`; GET `/api/gallery/artwork/*`; GET `/api/gallery/artworks`; GET `/api/gallery/tags/lookup`; GET `/api/gallery/tags` |
| `mail:templates.send` | 모든 이메일 템플릿 보내기 | POST `/api/admin/mail/test-all` |
| `mail:test.send` | 테스트 이메일 보내기 | POST `/api/admin/mail/test` |
| `notification:inbox.delete` | 받은 메시지 삭제 | DELETE `/api/notifications/*` |
| `notification:inbox.read` | 이 설치의 받은 메시지 읽기 | GET `/api/notifications/*/content`; GET `/api/notifications/*`; GET `/api/notifications` |
| `notification:inbox.templates.test` | 모든 받은 메시지 템플릿 보내기 | POST `/api/admin/notifications/test-all` |
| `notification:inbox.test` | 테스트 받은 메시지 보내기 | POST `/api/admin/notifications/test` |
| `notification:inbox.update` | 받은 메시지를 읽음으로 표시하고 설문을 사용 불가로 설정 | POST `/api/notifications/*/read`; POST `/api/notifications/*/survey-unavailable`; POST `/api/notifications/read-all` |
| `novel:collections.write` | 소설 컬렉션 소속 변경 | POST `/api/gallery/novels/collect` |
| `novel:content.read` | 소설 본문 및 삽입 이미지 읽기 | GET `/api/gallery/novel/*/content`; GET `/api/gallery/novel/*/embed/*` |
| `novel:downloads.read` | 이 키로 제출한 소설 다운로드 상태 읽기 | GET `/api/novel/status/*` |
| `novel:glossary.delete` | 소설 용어집 삭제 | DELETE `/api/admin/glossary/*` |
| `novel:glossary.read` | 소설 용어집 읽기 | GET `/api/admin/glossary/*`; GET `/api/admin/glossary/novel/*/default`; GET `/api/admin/glossary/series/*/default`; GET `/api/admin/glossary` |
| `novel:glossary.write` | 소설 용어집 만들기 및 수정 | POST `/api/admin/glossary`; PUT `/api/admin/glossary/*/entries`; PUT `/api/admin/glossary/*` |
| `novel:library.delete` | 소설 및 파일 삭제 | DELETE `/api/gallery/novel/*`; POST `/api/gallery/novels/delete` |
| `novel:library.export` | 소설 파일 내보내기 | GET `/api/novel/*/export`; GET `/api/novel/series/*/merged`; POST `/api/gallery/novels/export/options`; POST `/api/gallery/novels/export` |
| `novel:library.read` | 전체 라이브러리 소설 및 시리즈 보기 | GET `/api/gallery/novel/*/by-series`; GET `/api/gallery/novel/*/cover`; GET `/api/gallery/novel/*/series`; GET `/api/gallery/novel/*`; GET `/api/gallery/novel/series/*/cover`; GET `/api/gallery/novel/series/*`; GET `/api/gallery/novel/translated-titles`; GET `/api/gallery/novels/authors`; GET `/api/gallery/novels/series`; GET `/api/gallery/novels/tags`; GET `/api/gallery/novels`; GET `/api/novel/series/*/novel-ids`; POST `/api/gallery/novels/downloaded-batch` |
| `novel:narration.read` | 낭독 배역 및 음성 읽기 | GET `/api/narration/availability`; GET `/api/narration/cast/voice/reference`; GET `/api/narration/cast`; GET `/api/narration/casts/*/voices`; GET `/api/narration/casts/novel/*/default`; GET `/api/narration/casts`; GET `/api/narration/narrator-presets` |
| `novel:narration.script` | 낭독 대본 생성 | POST `/api/narration/script` |
| `novel:narration.synthesize` | 낭독 및 참조 오디오 합성 | POST `/api/narration/cast/voice/reference/generate`; POST `/api/narration/tts/line`; POST `/api/narration/tts/preview` |
| `novel:narration.write` | 낭독 배역 및 참조 오디오 수정 | DELETE `/api/narration/cast/voice/reference`; POST `/api/narration/cast/voice/reference`; POST `/api/narration/casts`; PUT `/api/narration/cast/voice` |
| `novel:pixiv.read` | Pixiv 온라인 데이터 조회 | GET `/api/pixiv/me/novel-bookmarks`; GET `/api/pixiv/novel-search/range`; GET `/api/pixiv/novel-search`; GET `/api/pixiv/novel/*/bookmark-count`; GET `/api/pixiv/novel/*/meta`; GET `/api/pixiv/novel/series/*`; GET `/api/pixiv/user/*/novel-cards`; GET `/api/pixiv/user/*/novels` |
| `novel:series.merge` | 소설 시리즈 합본 생성 | POST `/api/novel/series/*/merge` |
| `novel:translation.execute` | 소설 및 시리즈 제목 번역 | POST `/api/novel/*/translate`; POST `/api/novel/series/*/translate-title` |
| `novel:translation.probe` | 소설 번역 언어 감지 | POST `/api/novel/translate-lang-probe` |
| `novel:translation.read` | 소설 자동 번역 상태 읽기 | GET `/api/novel/translate-status/*` |
| `page-editor:components.preview` | 컴포넌트 미리 보기 | POST `/api/page-editor/components/preview`; POST `/api/page-editor/components/previews/*/close` |
| `page-editor:components.publish` | 컴포넌트 게시 및 비활성화 | POST `/api/page-editor/components/*/availability`; POST `/api/page-editor/components/*/publish` |
| `page-editor:components.read` | 컴포넌트 라이브러리 읽기 | GET `/api/page-editor/components/*/versions/*`; GET `/api/page-editor/components/*`; GET `/api/page-editor/components` |
| `page-editor:components.write` | 컴포넌트 초안 저장 | POST `/api/page-editor/components/save` |
| `page-editor:pages.preview` | 페이지 미리 보기 및 검증 | POST `/api/page-editor/documents/*/prepare`; POST `/api/page-editor/documents/*/preview`; POST `/api/page-editor/migrate`; POST `/api/page-editor/styles/parse` |
| `page-editor:pages.publish` | 페이지 게시 및 복원 | POST `/api/page-editor/documents/*/publish`; POST `/api/page-editor/documents/*/restore` |
| `page-editor:pages.read` | 페이지 목록 및 문서 읽기 | GET `/api/page-editor/catalog`; GET `/api/page-editor/documents/*/default`; GET `/api/page-editor/documents/*` |
| `page-editor:pages.write` | 페이지 초안 저장 | POST `/api/page-editor/documents/*/save` |
| `plugin-market:install` | 마켓 플러그인 및 의존성 설치(서버 코드 실행 허용) | POST `/api/plugin-market/*/*/*/install-preview`; POST `/api/plugin-market/*/*/*/install`; POST `/api/plugin-market/operations/*/execute` |
| `plugin-market:operations.discard` | 대기 중인 플러그인 가져오기 작업 폐기 | POST `/api/plugin-market/operations/*/discard` |
| `plugin-market:operations.prepare` | 플러그인 가져오기 작업 준비 | POST `/api/plugin-market/operations` |
| `plugin-market:read` | 플러그인 마켓 및 설치 미리 보기 | GET `/api/plugin-market/*/*/*/install-preview`; GET `/api/plugin-market/catalog`; GET `/api/plugin-market/content/*/*/*/*`; GET `/api/plugin-market/content/*/*/image`; GET `/api/plugin-market/plugins/*/*/*/facts`; GET `/api/plugin-market/plugins/*/*`; GET `/api/plugin-market/repositories` |
| `plugin-market:repositories.preview` | 가져올 플러그인 저장소 확인 | POST `/api/plugin-market/repositories/import/preview` |
| `plugin-market:repositories.trust` | 플러그인 저장소 신뢰 및 가져오기 | POST `/api/plugin-market/repositories/import/trust` |
| `push:templates.send` | 모든 푸시 알림 템플릿 보내기 | POST `/api/admin/push/test-all` |
| `push:test.send` | 테스트 푸시 알림 보내기 | POST `/api/admin/push/test` |
| `translation:cancel` | 번역 작업 일시 중지 또는 취소 | POST `/api/translation/jobs/*/cancel`; POST `/api/translation/jobs/*/pause` |
| `translation:delete` | 번역 작업 기록 삭제 | DELETE `/api/translation/jobs/*` |
| `translation:execute` | 소설 및 시리즈 번역 시작 또는 재개 | POST `/api/translation/jobs/*/resume`; POST `/api/translation/jobs`; POST `/api/translation/novel`; POST `/api/translation/series` |
| `translation:read` | 번역 상태, 언어 및 작업 보기 | GET `/api/translation/jobs/*`; GET `/api/translation/jobs`; GET `/api/translation/languages`; GET `/api/translation/status`; POST `/api/translation/language` |
| `tts:synthesize` | 온라인 읽기 오디오 합성 | POST `/api/tts/edge/synthesize` |
| `tts:voices.read` | 온라인 읽기 음성 보기 | GET `/api/tts/edge/voices` |
| `<plugin>:settings.read` | 설정 및 프리셋 읽기 | `core:settings.read` |
| `<plugin>:settings.write` | 설정 검증 및 저장 | `core:settings.write` |
| `<plugin>:media.read` | 미디어 도구 및 작업 조회 | `core:media.read` |
| `<plugin>:media.preview` | 미디어 처리 미리 보기 | `core:media.preview` |
| `<plugin>:media.execute` | 미디어 처리 실행 | `core:media.execute` |
| `<plugin>:media.cancel` | 미디어 처리 취소 | `core:media.cancel` |
| `<plugin>:file-import.read` | 파일 가져오기 도구 및 작업 조회 | `core:file-import.read` |
| `<plugin>:file-import.preview` | 파일 가져오기 미리 보기 | `core:file-import.preview` |
| `<plugin>:file-import.execute` | 파일 가져오기 실행 | `core:file-import.execute` |
| `<plugin>:file-import.cancel` | 파일 가져오기 취소 | `core:file-import.cancel` |
| `<plugin>:file-import.acknowledge` | 파일 가져오기 결과 확인 | `core:file-import.acknowledge` |
| `<plugin>:downloads.create` | 다운로드 작업 만들기 | `core:downloads.create` |
| `<plugin>:batches.execute` | 배치에서 이 플러그인의 수집원 및 작품 실행기 사용 허용 | `core:batches.create`, `core:batches.resume` |
| `<plugin>:schedule.manage` | 이 플러그인의 영구 일정 및 자격 증명 정책 관리 | `core:schedule.*` |
| `<plugin>:plugins.manage` | 이 플러그인의 수명 주기, 활성화 상태 및 신뢰 관리 허용 | `core:plugins.*` |
