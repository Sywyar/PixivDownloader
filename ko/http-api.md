# OpenAPI와 프로그램 키

PixivDownloader는 대화형 OpenAPI 문서와 세분화된 **프로그램 액세스 키**(Program Key)를 내장하여 스크립트, 자동화 도구나 AI 에이전트가 다운로드, 배치, 갤러리, 번역 및 유지보수 API를 안전하게 호출할 수 있게 합니다(이 프로그램은 HTTP API만 제공하며 별도의 MCP 서버는 제공하지 않음. 새 API 작성 규칙은 [HTTP API 및 권한 선언 작성](/ko/http-api-development) 참고).

## 스크립트나 에이전트에서 API 사용하기

1. **대화형 문서 확인**: 실행 중인 인스턴스에서 `/openapi`(로컬 기본값 `http://localhost:6999/openapi`)를 엽니다. 접근 안내는 누구나 볼 수 있으며, 관리자로 로그인하면 전체 API 목록, 요청 예시, 작업별 필수 권한을 확인하고 응답 목록 아래의 '관련 작업' 링크(`links` / `x-related-operations` / `externalDocs`)로 후속 작업과 사용 가이드 사이를 바로 이동할 수 있습니다.
2. **프로그램 액세스 키 생성**: 데스크톱 **보안 → 프로그램 액세스 키**에서 키를 생성합니다(**설정 → 접근 제어**에서도 이동할 수 있음). 데스크톱이 없는 환경에서는 [명령줄](#명령줄에서-키-관리하기)로 생성합니다. 허용할 출발지 IP/CIDR와 필요한 권한을 선택합니다. 스크립트나 에이전트가 API 명세를 직접 읽어야 한다면 `core:api-reference.read`도 함께 선택하세요.
3. **API 호출 또는 에이전트 프롬프트 복사**: 클라이언트는 `Authorization: Bearer <프로그램 키>` 헤더와 함께 `GET /api/openapi.json`(권한 확인만 할 때는 `HEAD /api/openapi.json`)을 요청해 기계 가독형 OpenAPI JSON을 받을 수 있습니다. 에이전트를 사용할 때는 키를 만든 뒤 또는 키 목록의 **에이전트용 프롬프트**에서 프롬프트를 복사해 인스턴스의 문서 주소를 에이전트에 전달하고, 비밀 키는 에이전트 실행 환경에 별도로 설정하세요. Agent Skills를 지원하는 에이전트에는 대신 [스킬을 설치](#ai-에이전트에-스킬-설치하기)하면 관련 작업에서 자동으로 사용됩니다.

### AI 에이전트에 스킬 설치하기

메인 저장소는 [Agent Skills](https://agentskills.io/) 형식의 `pixivdownloader` 스킬을 제공합니다. 이 스킬은 인스턴스 연결, 프로그램 액세스 키 사용법, 자주 쓰는 API(Pixiv 둘러보기, 작품 다운로드, 다운로드한 작품 찾기), 오류 처리 방법을 에이전트에게 알려 줍니다. 설치하면 Agent Skills를 지원하는 에이전트(Claude Code, Codex, Cursor, GitHub Copilot, Gemini CLI 등)가 관련 작업에서 자동으로 사용하므로 매번 프롬프트를 붙여 넣을 필요가 없습니다.

1. 스킬을 설치합니다(Node.js 필요). 명령이 설치할 에이전트를 묻습니다. `-g`를 붙이면 모든 프로젝트에서 사용할 수 있습니다:

   ```bash
   npx skills add Sywyar/PixivDownloader --skill pixivdownloader -g
   ```

   나중에 `npx skills update -g`로 새 버전을 받습니다.
2. 에이전트의 실행 환경에서 `PIXIV_PROGRAM_KEY`를 프로그램 액세스 키로 설정합니다. 인스턴스 주소가 `http://localhost:6999`가 아니면 `PIXIV_BASE_URL`도 그 주소로 설정하거나 대화에서 에이전트에게 알려 주세요. 키는 대화에 붙여 넣지 마세요.
3. 작업에 필요한 권한을 키에 부여합니다. 에이전트는 먼저 `/self`로 부여된 권한을 확인합니다. `core:api-reference.read`도 부여하면 전체 API 목록을 참고할 수 있습니다.

?> 스킬에는 오래 바뀌지 않는 규칙과 자주 쓰는 API만 담겨 있습니다. 필드, 매개변수, 값은 인스턴스의 `GET /api/openapi.json`을 따릅니다. Pixiv 둘러보기 API는 `pixiv-content` 플러그인이 제공하며, 설치되지 않았거나 비활성화된 경우 에이전트가 설치나 활성화를 요청합니다.

### 작가와 태그로 Pixiv 작품 검색

작품 태그마다 `tag`를 하나씩 지정합니다. 예를 들어 `GET /api/pixiv-content/users/28329224/artworks?tag=furry&tag=original`은 이 작가의 작품 중 두 태그가 모두 붙은 작품을 반환합니다. 소설은 `/users/{id}/novels`를 사용합니다. 서로 다른 태그를 최대 10개까지 지정할 수 있습니다. 여러 태그를 지정했을 때 각 태그에 해당하는 작품이 모두 2048개를 초과하면 `422 PIXIV_TAG_SEARCH_TOO_BROAD`가 반환됩니다.

닉네임과 태그를 함께 검색하려면 `GET /api/pixiv-content/search/users-and-tags?word=cat&userPage=1&tagPage=1`을 사용합니다. 응답의 `users`와 `tags`는 각각 1페이지부터 따로 조회합니다. Pixiv가 로그인을 요구하면 수집 자격 증명이 필요합니다.

### 명령줄에서 키 관리하기

데스크톱이 없는 환경(서버, Docker)에서는 명령줄로 키를 관리합니다. 명령은 실행 중인 인스턴스에 연결해 관리자로 로그인하고 아래 관리 API를 호출한 뒤 로그아웃하고 종료하므로 서비스를 멈출 필요가 없습니다. 권한 목록은 인스턴스가 로드된 플러그인으로 만들기 때문에 인스턴스가 실행 중이어야 합니다.

| 명령 | 용도 |
| --- | --- |
| `--program-keys` | 키 목록 표시: 이름, ID, 권한 수, 출발지, 사용 시각 |
| `--program-key-permissions` | 이 인스턴스에서 부여할 수 있는 권한과 해당 API 표시 |
| `--program-key-create` | 키 생성. 전체 키는 출력에 한 번만 표시됨 |
| `--program-key-revoke --key-id=<ID>` | 확인 후 키 폐기 |

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --program-key-create
java -Dfile.encoding=UTF-8 -jar APP.jar --program-key-create --key-name=agent --key-permissions=core:api-reference.read,core:downloads.read --key-sources=127.0.0.1
```

- `--key-name`, `--key-permissions`, `--key-sources`를 생략하면 하나씩 입력을 요청하며, 권한은 목록 번호로 고를 수 있습니다. `--key-sources`에는 `any` 또는 쉼표로 구분한 IP 주소 / CIDR를 지정합니다.
- 관리자 계정은 `--username`, `--password`로 넘기거나 프롬프트에서 입력합니다. 명령줄의 비밀번호는 셸 기록이나 프로세스 목록에 남으므로 평소에는 프롬프트 입력을 사용하세요.
- 인스턴스 주소는 기본적으로 `config/config.yaml`의 `server.port`, `server.ssl.enabled`, `ssl.domain`에서 구하며, `--server-url=https://<호스트>:<포트>`로 지정할 수도 있습니다. 명령은 시스템 프록시를 쓰지 않고 리디렉션도 따르지 않습니다. 인스턴스가 HTTP를 HTTPS로 리디렉션하면 안내된 HTTPS 주소를 지정하세요. 자체 서명 인증서를 쓴다면 먼저 인증서를 Java 신뢰 저장소로 가져오세요.
- Docker Compose에서는 새 컨테이너에서 실행하고 서비스 이름을 지정합니다:

  ```text
  docker compose run --rm app --program-key-create --server-url=http://app:6999
  ```

- 인스턴스가 로그인 추가 확인(예: CAPTCHA)을 요구하면 명령은 로그인할 수 없으며 서버가 돌려준 이유를 표시합니다.
- 종료 코드: `0` 성공, `1` 연결·로그인 실패 또는 서버 거부, `2` 잘못된 인수 또는 취소. 생성이나 폐기 요청을 보낸 뒤 연결이 끊겼다면 변경이 이미 적용되었을 수 있으니, 다시 시도하기 전에 `--program-keys`로 확인하세요.

### 키 보안, 출발지 제한 및 CSRF 규칙

- **일회성 키 표시 및 한도**: 전체 키 문자열은 생성 성공 시 한 번만 표시되며 서버는 다이제스트만 저장합니다(기본 만료일은 없으며 언제든 권한과 출발지 범위를 수정하거나 폐기 가능. 플러그인 전체를 선택하면 현재 나열된 권한만 부여되며 미래에 추가될 권한을 자동 부여하지 않음). 설치 인스턴스당 최대 128개의 키(이름 최대 80자, 키당 최대 512개 권한 및 64개 출발지 네트워크, 관리 요청 본문 최대 128 KiB)를 만들 수 있습니다.
- **HTTPS 및 출발지 IP 제한**: 로컬호스트나 로컬 네트워크 외부에서 평문 HTTP로 보낸 프로그램 키 요청은 기본적으로 거부됩니다(외부 HTTP 예외를 허용하려면 해당 키의 위험을 검토한 뒤 명시적으로 확인해야 하며, 이후 권한이나 출발지를 수정하면 다시 HTTPS 필수로 돌아감. 서버가 거부했더라도 평문으로 전송된 키는 이미 노출되었을 수 있으므로 즉시 폐기하세요). 출발지 제한에는 IPv4, IPv6, CIDR 대역을 지정할 수 있으며 제한 모드에서는 최소 1개 이상 입력해야 합니다('모든 출발지'는 명시적으로 선택해야 하며, 리버스 프록시 뒤에서 사용할 때는 먼저 `server.trusted-proxy-cidrs`를 설정하세요).
- **인증 격리 및 상태 코드**: 한 요청에 프로그램 키와 관리자 세션(`X-Session-Token` / Cookie), 초대 코드 또는 로컬 GUI token을 함께 보내지 마세요(수집용 자격 증명은 `X-Acquisition-Credential` 또는 `X-Acquisition-Credentials`로 별도 전달).
  - 프로그램 키를 지원하지 않는 작업(익명 접근 허용 작업 포함)에 유효한 Bearer 키만 보내면 `403 auth.program.unsupported`를 반환합니다(문서의 `x-program-alternative`에서 대체 프로그램 키 엔드포인트 확인). 유효하지 않은 키나 인증 정보 혼용 시 `401 auth.program.invalid`를 반환합니다.
  - `/api/gui/**`, 키 관리와 관리자 자격 증명 변경은 로컬 GUI 또는 관리자 본인의 세션만 사용할 수 있습니다.
- **동일 출처 신호(`x-csrf-origin`)**: Cookie를 사용하는 쓰기 요청은 동일 출처 검사를 통과해야 합니다. Bearer 인증으로 쓰기 요청을 보낼 때도 각 작업의 `x-csrf-origin`을 확인하세요. 예를 들어 `POST /api/schedule/tasks`(`origin-or-referer`)는 동일 출처 `Origin` 또는 `Referer`가 필요하지만, 브라우저 Cookie가 없는 네이티브 Bearer 클라이언트가 `POST /api/download/tasks`와 `POST /api/download/batches`를 호출할 때는 두 헤더를 모두 생략할 수 있습니다.
- **자체 확인, 관리 API 및 키 교체**:
  - `GET /api/security/program-keys/self`를 호출하면 별도 업무 권한 없이 현재 키의 `id`와 `permissions`(빈 목록일 수 있음)를 확인할 수 있습니다.
  - 관리자 세션에서는 `GET /api/security/program-keys`(목록, 비밀 값이나 다이제스트 제외), `GET /api/security/program-keys/permissions`(현재 설치의 전체 권한 카탈로그), `POST /api/security/program-keys`(`name`, `permissions`, `anyIp`, `networks`로 생성하여 `key`와 일회성 `secret` 반환), `PUT /api/security/program-keys/{id}`(`{"revision": 1, "policy": {...}}`로 수정), `DELETE /api/security/program-keys/{id}`(`{"revision": 1}`로 폐기)를 사용할 수 있습니다.
  - **교체 순서**: 새 키 생성 → 클라이언트 키 전환 후 `/self`로 확인 → 이전 키 폐기. 이전 키로 만든 다운로드 작업과 배치는 새 키로 조회할 수 없으므로 폐기 전에 종료 상태까지 폴링하거나 관리자 세션으로 추적하세요(키 폐기는 새 요청만 차단하며 이미 수락된 진행 중 작업을 자동 취소하지 않음).

## 엔드포인트 목록

같은 행의 짧은 경로는 첫 번째 경로의 공통 접두사에 이어집니다. 번역(`/api/translation/**`), 용어집, 낭독, 갤러리, 컬렉션, 예약 작업, 유지보수와 플러그인 마켓의 전체 경로는 아래 [프로그램 권한 목록](#프로그램-권한-목록)이나 `/openapi`에서 확인하세요.

| 기능 | 주요 엔드포인트 |
| --- | --- |
| 설정 항목·기본값·마스킹된 현재 값 및 프리셋 | `GET /api/settings`, `GET /api/settings/presets` |
| 설정 검증과 저장 | `POST /api/settings/validate`, `POST /api/settings` |
| 플러그인 저장소 설정 | `GET` / `PUT /api/settings/repositories` |
| 데이터베이스 설정·연결 테스트·이전 | `GET /api/settings/database`, `/providers`, `/status`, `/migration`; `POST /check`, `/migration-previews`, `/migrations` |
| 환경 아카이브 내보내기와 복원 | `POST /api/settings/environment/preview`, `/restore`, `/exports`; `GET /restore`, `/exports`, `/exports/{id}/archive`, `/installation` |
| 메타데이터 보완 | `GET /api/tools/backfill/defaults`, `/tasks/current`, `/tasks/{id}`; `POST /candidates`, `/tasks`, `/tasks/{id}/cancel` |
| 미디어 유지보수와 기록 파일 가져오기 | `GET /api/tools/media`, `/api/tools/file-import`(각 엔드포인트 아래 `POST /previews`, `/tasks`, `/tasks/cancel`, `GET /tasks/current`); `POST /api/tools/media/capabilities`; `POST /api/tools/file-import/completion/acknowledge` |
| 로컬 작품 가져오기와 시작 캐시 | `GET /api/local-import/presets`, `POST /previews`, `POST /tasks`; `GET /api/tools/startup-cache`, `POST /tasks`, `POST /tasks/{id}/cancel` |
| 디렉터리 참조 및 경로 접두사 | `GET` / `POST /api/tools/directories` · `/api/tools/path-prefixes` |
| FFmpeg 상태·설치·취소 및 도구 기록·로그 | `GET /api/tools/ffmpeg`, `POST /api/tools/ffmpeg/tasks`, `POST /api/tools/ffmpeg/tasks/{executionId}/cancel`; `GET /api/tools/history`, `/api/tools/logs/{fileName}` |
| 시스템·제어 센터·업데이트 | `/api/admin/system`, `/api/admin/control-center`; `/api/admin/update/check`, `/last`, `/download`, `/download/progress`, `/install` |
| AI·메일·푸시·받은 편지함 알림 테스트 | `/api/admin/ai/test`, `/models`; `/api/admin/mail/test`, `/test-all`; `/api/admin/push/test`, `/test-all`; `/api/admin/notifications/test`, `/test-all` |
| 개별 다운로드·기록·백그라운드 수동 배치 | `/api/download/tasks`, `/api/download/tasks/history`; `/api/download/batches` |

복원 상태에 포함된 `jobId`로 애플리케이션 로그를 검색하면 해당 작업이 실패한 이유를 확인할 수 있습니다. 확인 후 복원 실행 전에 대상이 바뀌면 `failureCode`는 `transfer.target-changed`입니다. 다시 미리 본 뒤 복원을 확인하세요.

`POST /api/tools/path-prefixes`는 접두사 수정과 등록을 하나의 트랜잭션에서 커밋합니다. `registerPaths`의 각 항목에는 기존 디렉터리의 절대 경로를 문자열로 입력하세요. 성공 응답에서 `registered`는 새로 등록한 수, `alreadyRegistered`는 재사용한 수, `applied`는 수정 건수와 신규 등록 건수의 합계입니다. 항목이 유효하지 않으면 400과 함께 `/registerPaths/i`를 반환합니다. `i`는 0부터 시작합니다. 쓰기에 실패하면 `500 PATH_PREFIX_WRITE_FAILED`를 반환하고 모든 변경을 취소합니다.

Windows 드라이브 경로와 UNC 경로는 대소문자나 구분 기호가 달라도 같은 접두사를 재사용합니다. 경로에 독립된 `.` 요소가 있어도 같은 접두사 ID를 사용합니다. POSIX 경로는 대소문자를 구분합니다. 이전에 중복 등록한 항목이 있어도 수정은 지정한 ID를 참조하는 레코드에만 적용됩니다. `POST /api/tools/path-prefixes/pin`에서 쓰기에 실패하면 `500 PATH_PREFIX_WRITE_FAILED`를 반환하며 기존 참조는 그대로 유지됩니다.

이미지 분류 도구의 지원이 종료되었습니다. 다음 경로는 기존 권한을 확인한 뒤 승인된 요청에 빈 `410 Gone` 응답을 반환합니다.

- `GET, PUT /api/tools/classifier/settings`
- `GET, DELETE /api/tools/classifier/folders`
- `GET /api/tools/classifier/images`
- `GET /api/tools/classifier/image`
- `POST /api/tools/classifier/moves`
- `GET /api/downloaded/by-move-folder`
- `POST /api/downloaded/move/{artworkId}`
- `POST /api/library/artworks/{artworkId}/move`

보관된 작업을 조회하거나 취소할 때 소유자가 확인되었지만 기록을 읽을 수 없으면 `503 DOWNLOAD_TASK_RECEIPT_UNAVAILABLE` 가 반환됩니다. 다른 사용자의 작업과 존재하지 않는 작업은 모두 404를 반환합니다.

## 설정 저장

`POST /api/settings/validate` 는 변경 대상의 설정과 자격 증명만 읽어 입력을 검사합니다. 설정을 저장하거나 자격 증명을 이전하지 않습니다.

먼저 `GET /api/settings`로 항목 카탈로그를 읽고 반환된 `owner`, `packageId`, `generation`, `key`로 변경을 제출합니다(호스트 항목은 `owner`와 `packageId`가 `null`이고 `generation`이 `0`). 민감한 항목은 불리언 `stored` 표시만 반환하며 평문이나 기본 비밀 값은 반환하지 않습니다. `action`(`KEEP`, `SET`, `CLEAR`)으로 유지, 교체, 삭제를 지정합니다. 응답의 `effect`는 적용 방식을 나타냅니다(저장만으로 백엔드나 프로세스가 자동 재시작되지는 않음):

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

프리셋도 같은 저장 API로 적용합니다. 플러그인 저장소 편집(`PUT /api/settings/repositories`)에는 `GET`이 반환한 `revision`이 필요하며, 저장소를 추가하거나 신뢰·주소·네트워크 정책을 바꿀 때는 `"trustConfirmed": true`를 함께 보내야 합니다(충돌 시 다시 조회하며 다른 편집을 덮어쓰지 않음). 데이터베이스 연결 테스트와 도구의 일회성 옵션은 해당 실행에만 적용되며 설정 파일을 바꾸지 않습니다.

## 백그라운드 수동 배치

프로세스당 동시에 하나의 수동 배치만 보관합니다(클라이언트 연결이 끊어져도 백그라운드에서 계속 실행되지만 프로세스 재시작 후에는 복원되지 않음. 새 배치를 만들기 전에 유휴 상태의 기존 배치를 먼저 삭제하세요):

1. **수집원 조회**: `GET /api/schedule/sources`에서 현재 `sourceType`과 `activationToken`을 가져옵니다(정의 형식은 해당 플러그인이 해석하며 `explicit-list`, 작가 게시물, 커미션, 검색, 시리즈, 북마크, 팔로우 새 작품, 컬렉션 지원).
2. **배치 생성**: `POST /api/download/batches`로 `name`, `sourceType`, `activationToken`, `definition`(JSON 객체, UTF-8 직렬화 후 최대 1 MiB, 전체 요청 최대 8 MiB) 및 선택적 `proxy`를 제출합니다. 제출 시 `403`이 반환되면 `programPermission`에서 확인된 누락 권한을 읽을 수 있으며, `202`를 받으면 `core:batches.read` 권한으로 `Location`을 조회합니다(백그라운드 실행 중 권한이 부족하면 배치 결과의 `programPermission`에도 권한 ID가 기록됨).
3. **상태 및 항목 폴링**: `GET /api/download/batches/{id}`로 전체 상태를 조회하고(`state`로 단계 판별, `code`는 선택적 사유 코드로 `batch.work-failed`이면 `items` 확인, 작가 배치의 `schedule.pixiv.discovery-not-found`이면 작가 ID 확인), `GET /api/download/batches/{id}/items?offset=0&limit=100`으로 항목을 페이지별로 조회합니다(페이지당 최대 200개. 항목 식별자는 배치 UUID + `workType` + `id`이며 개별 다운로드 작업의 실행 UUID와 다름).
4. **제어 및 재개**: `POST /api/download/batches/{id}/pause`, `/cancel`, `/resume`, `/retry`는 각각 일시 중지, 취소, 탐색 재개, 이미 발견된 실패 항목만 재시도합니다(중지 중 상태는 실제 실행이 종료된 뒤에야 종료 상태가 되며, 완료되었거나 필터로 건너뛴 작품은 재개 시 다시 실행하지 않음).
5. **자격 증명 교체, 보류 항목 처리 및 삭제**:
   - 유휴 상태에서 `PUT /api/download/batches/{id}/credentials`(본문 `{"expectedRun": 1}`, 최대 16 KiB. 새 자격 증명은 요청 헤더로 전달하고 헤더 생략 시 삭제하며 재개 전 다시 검사)를 호출합니다.
   - `POST /api/download/batches/{id}/resolve`로 보류 항목을 처리합니다(본문에 `expectedRun`, `workType`, `workId`, `reasonCode`, `userAction`, `rememberForRun` 포함, 최대 16 KiB. 사유와 실행 회차가 현재 보류 기록과 일치해야 함). `DOWNLOAD_PATH_ACTION_REQUIRED`에는 `TRUNCATE`, `DEFAULT_NAME` 또는 `CANCEL`을 지정하세요. 다른 값을 보내면 400 응답의 `/userAction` 필드에 오류가 표시됩니다. 작품은 확인 대기 상태이므로 값을 고쳐 바로 다시 보낼 수 있습니다.
   - 유휴 상태에서 `DELETE /api/download/batches/{id}`로 배치 기록을 삭제합니다.

배치는 최대 5,000개 항목과 인코딩된 내용 예약 예산 64 MiB 중 먼저 도달하는 한도를 적용합니다. 필터는 콘텐츠 등급, AI, 태그, 일러스트 유형, 페이지 수, 소설 글자 수와 최소 북마크 수를 지원합니다(북마크 수 필터가 켜져 있을 때 북마크 수를 알 수 없는 작품은 통과하지 못함).

- **검색 페이지 요청 간격(`pageDelayMs`)**: `search` 소스에서는 `download.pageDelayMs`로 연속된 두 페이지 요청 시작 시각 사이의 최소 간격(밀리초. 생략 시 고정 페이지 수는 `0`, `maxPages = -1`은 `10000`이며 명시적으로 `0`을 입력하면 추가 대기 생략)을 지정할 수 있습니다. 수동 배치에서는 `definition`에 JSON 객체로 넣고 예약 작업에서는 `definitionJson` 문자열로 직렬화합니다(공유 다운로드 프로필은 `settings.pageDelayMs`를 사용하며 기본값은 `null`):

  ```json
  {"source":{"word":"landscape","maxPages":3},"download":{"pageDelayMs":5000}}
  ```

- **일러스트·소설 혼합 목록(`explicit-list`) `definition` 예시**(작품 ID는 양의 정수 십진 문자열이어야 하며 같은 유형 안에서 중복될 수 없습니다. 예시 ID는 실제 작품으로 바꾸세요. `/definition/source/acquisitionMode`가 없으면 배치 생성 전에 400 반환):

  ```json
  {
    "kind": "mixed",
    "source": {
      "acquisitionMode": "single-import",
      "works": [
        {"kind": "illust", "id": "123"},
        {"kind": "novel", "id": "456"}
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

## 도구, 갤러리 일괄 작업 및 재시작 작업

모든 도구 API의 경로 매개변수는 원격 클라이언트가 아닌 **백엔드가 실행 중인 서버의 로컬 파일 시스템**을 가리킵니다:

- **미디어 유지보수와 기록 파일 가져오기**: 먼저 카탈로그를 `GET`하고 그 안의 정확한 `identity`(`pluginId`, `packageId`, `generation`, `publication`)와 `options`로 미리 봅니다. 실행 시작에는 같은 `identity`와 미리보기 `token`을 보내고, 취소할 때는 `executionId`도 함께 보내야 합니다(플러그인 세대가 교체되거나 데스크톱에서 대체 작업을 시작하면 이전 token/UUID는 무효화됨).
- **메타데이터 보완 및 수동 복구**: `POST /api/tools/backfill/tasks`(본문 최대 4 KiB, `limit: 0`은 전체 후보, `202`는 수락):

  ```json
  {
    "delayMs": 0,
    "artworkIds": [12345],
    "dryRun": true,
    "alwaysNetwork": false
  }
  ```

  단일 일러스트의 누락된 필드를 호출자가 제공한 값으로 직접 채우려면 `POST /api/library/artworks/{artworkId}/recover-metadata`를 호출합니다(기존 비어 있지 않은 값은 보존하며 본문이 없거나 유효한 필드가 없으면 400 반환. Pixiv에서 태그와 시리즈를 가져오려면 작품 ID를 지정해 데이터 보완을 시작하세요. [가져온 작품의 메타데이터 보충](/ko/local-import?id=metadata-backfill) 참고).
- **갤러리 파일 관리 방식 필터 및 일괄 내보내기/삭제**:
  - `GET /api/gallery/artworks?fileManagement=EXTERNAL_REFERENCE`로 외부 읽기 전용 참조 작품을 필터링할 수 있습니다(삭제 시 원본 파일은 남기고 등록 기록만 제거). `MANAGED`는 앱이 관리하는 파일을 뜻합니다(삭제 시 파일도 함께 제거하며 중복 확인용으로 `deleted=true` 기록 유지).
  - 일러스트와 소설의 일괄 API는 `"mode": "filter"`(모든 페이지 대상, `filter`는 JSON 객체 필수) 또는 `ids` 배열(`42` 같은 양의 JSON 정수만 허용하며 소수·문자열·0·음수는 HTTP 400과 `field` 반환)을 지원합니다. `"filter": {}`나 페이지 매개변수만 있는 `filter`를 명시하면 해당 유형의 전체 작품을 선택하므로, 삭제하거나 '내보내기 후 원본 삭제'를 수행할 때는 `"confirmAll": true`와 해당 갤러리 삭제 권한이 필수입니다.
  - 내보내기에서 `archiveToken`이 반환되면 `core:archives.read`로 `GET /api/archive/status/{token}`을 폴링하고, `ready` 상태가 되면 `core:archives.download`로 `GET /api/archive/download/{token}`을 호출해 내려받습니다.
- **시작 캐시, 데이터베이스 이전 및 환경 아카이브**:
  - 시작 캐시 학습은 `POST /api/tools/startup-cache/tasks`(본문 `{}`)로 시작하고 `GET /api/tools/startup-cache`로 상태를 확인합니다.
  - 데이터베이스 이전과 환경 복원은 모두 미리보기에서 받은 `token`으로 확정합니다(환경 복원은 `RESTORE_PENDING`을 반환한 뒤 대체 프로세스가 실행). 환경 내보내기(`POST /api/settings/environment/exports`)는 서버 절대 경로 `destination`(기존 파일을 덮어쓰지 않음)과 `"confirmRestart": true`가 필수이며, 자격 증명·데이터베이스·상태를 포함할 때는 `password`를 설정해야 합니다(`includeResources`를 포함하려면 `includeDatabases`도 함께 포함 필요). 내보내기는 백엔드를 중지하고 전체 프로세스 재시작을 요청하므로 자동 재시작에 실패하면 서버에서 수동으로 시작하세요. 온라인 업데이트 설치에는 검증된 패키지의 SHA-256이 필요합니다.

## 오류 처리, 폴링 및 페이지네이션

오류 응답은 기계용 `code`와 현지화된 `error`를 포함합니다(`400` 잘못된 입력, `401`/`403` 인증 또는 권한 거부, `404` 대상 없음, `409` 작업 실행 중/리비전 충돌/허용되지 않는 상태, `413` 본문 크기 초과, `503` 플러그인 또는 데이터베이스 기능 사용 불가). 모든 비 2xx 응답을 무조건 자동 재시도하지 말고 업무 상태에 맞게 처리하세요.

- **단일 다운로드 제출 및 폴링(`POST /api/download/tasks`)**:
  - OpenAPI `x-download-submissions`에서 활성화된 `workType`을 선택합니다(게시되지 않은 유형은 `400 DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE` 반환. 원본 사이트에서 작품을 찾을 수 없을 때는 최초 제출 시 `404 WORK_NOT_FOUND` 반환. 호출자가 조회할 수 없는 작업 ID는 `404 DOWNLOAD_TASK_NOT_FOUND` 반환).
  - 매개변수 오류는 `in`, `field`(본문 필드는 `/options/pages` 같은 JSON Pointer, 쿼리·경로·헤더·multipart는 선언된 매개변수 이름)와 `reason`(`out_of_range` 등)을 반환합니다.
  - `202` 응답 후에는 `Location`(경로 매개변수 `taskId`는 응답의 `task.attempt.attemptId`)을 폴링합니다. 처음에는 **2초** 간격으로 조회하고 `revision`이 바뀌지 않으면 최대 **10초**까지 점진적으로 늘렸다가, 갱신되면 다시 2초로 줄이며 `phase`가 `COMPLETED`, `FAILED`, `CANCELLED` 중 하나가 되면 중단합니다(`Retry-After`가 있으면 최소 해당 시간만큼 대기). `progress`가 있으면 같은 단위(`PAGES`, `IMAGES`, `BYTES`)의 `progress.unit`, `completed`, `total`을 함께 읽습니다.
  - **멱등성 및 작업 기록**: 프로세스가 멱등 기록을 보관하는 동안(종료 결과 저장 후 약 5분 뒤 만료) 같은 호출자가 동일한 `requestId`로 같은 명령을 재시도하면 원래 영수증을 반환합니다. `GET /api/download/tasks/history`는 저장된 종료 결과만 나열하며(페이지당 기본 50개, 최대 200개) 진행 중인 다운로드는 복원하지 않습니다.

| 데이터 항목 | 읽기 및 전송 형식 |
| --- | --- |
| Pixiv 작품 ID | 갤러리 `artworkId`와 내보내기 `ids[]`는 JSON 정수이며, Pixiv 검색 `id`, 작업 `workId`, 배치 `key.id`는 10진 문자열입니다(변환 시 64비트 정수 정밀도 유지. 다른 제공자는 자체 `workId` 형식 정의) |
| 다운로드 옵션 타입 | 단일 작업 `options`는 문자열 값(`{"bookmark":"true","mediaQuality":"80","mediaWebpLossless":"true"}`), 수동 배치 `definition.download`는 기본 JSON 타입(`{"bookmark":true,"mediaQuality":80,"mediaWebpLossless":true}`) 사용 |
| 시간과 경로 | 작업 `updatedAt`은 ISO 8601, 배치 `createdTime`·갤러리 `time`·예약 `nextRunTime`은 Unix 밀리초입니다. 작업 `outputPath`는 예정된 서버 폴더이고 갤러리 `folder`는 등록된 위치이므로 작품 ID로 연결하세요 |
| 주요 응답 형식 | `GET /api/authors`는 JSON 배열, `GET /api/authors/paged`는 `content`가 포함된 페이지 객체, `GET /api/downloaded/statistics`는 `success`와 개수를 담은 객체 반환 |

| 페이지네이션 엔드포인트 | 매개변수 | 다음 페이지 조회 방법 |
| --- | --- | --- |
| `GET /api/gallery/artworks` | `page`, `size` | 첫 페이지는 `page=0`을 보내고 이후 `page`를 `1`씩 증가 |
| `GET /api/download/batches/{id}/items` | `offset`, `limit` | `offset=0`에서 시작해 실제로 받은 항목 수만큼 `offset` 증가 |
| `GET /api/download/tasks/history` | `limit`, `beforeTime`, `beforeId` | 첫 요청에서는 두 커서를 모두 생략하고, 다음 페이지에는 반환된 `beforeTime`과 `beforeId`를 그대로 함께 전달 |
| `GET /api/pixiv/search` | `page` | `page=1`부터 시작(`1` 미만은 1페이지로 처리) |
| `GET /api/plugin-market/catalog` | `cursor`, `limit` | 응답의 `nextCursor`를 다음 요청의 `cursor`로 전달(플러그인 버전 목록은 `nextVersionCursor` 사용) |

## 프로그램 권한 목록

현재 설치의 실시간 권한 카탈로그는 관리자가 `GET /api/security/program-keys/permissions`로 조회할 수 있습니다. 키에는 명시적인 권한 ID만 저장할 수 있으며 플러그인이나 경로 와일드카드는 지원하지 않습니다(아래 표의 `*`는 단일 경로 구간을 뜻함):

### 권한 범위와 전용 흐름

- **복합 권한 검증**:
  - **공유 설정, 미디어 유지보수 및 파일 가져오기**: `core` 진입점 권한과 대상 플러그인의 같은 권한이 모두 필요합니다(목록은 허용된 대상만 표시).
  - **개별 다운로드 및 수동 배치**: 개별 다운로드에는 `core:downloads.create` + 실제 다운로드 유형 소유 플러그인의 `<plugin>:downloads.create`가 필요합니다. 수동 배치에는 `core:batches.create`(또는 `core:batches.resume`) + 수집원 플러그인 및 실행 계획에 포함된 모든 작품 플러그인의 `<plugin>:batches.execute`가 필요합니다(수집원 카탈로그 조회는 `core:schedule.sources.read`).
  - **영구 예약 작업**: `core:schedule.*`의 해당 동작 권한 + 수집원 플러그인의 `<plugin>:schedule.manage`가 필요합니다(계정 정책 동작에는 정책 owner의 같은 권한 필요. 예약 작업은 설치 전체의 영구 설정이므로 생성한 키를 폐기해도 예약 작업이 삭제되거나 비활성화되지 않음).
  - **플러그인 수명 주기 및 보안 정책**: `core:plugins.*`의 해당 동작 권한 + 대상 플러그인의 `<plugin>:plugins.manage`가 필요합니다(역방향 소비자 플러그인이 있으면 해당 소비자 권한도 확인. `core:plugins.install`과 `plugin-market:install`은 서버 코드 실행을 허용하고 `core:hooks.publish`는 전역 작업 보호 정책을 변경하므로 신중히 부여).
  - **수집 자격 증명**: `core:credentials.read`는 자격 증명 이름과 상태만 반환하며 비밀 값은 반환하지 않습니다. `X-Acquisition-Credentials` 헤더로 저장된 공유 자격 증명을 참조하려면 업무 권한 외에 `core:credentials.use`가 필요합니다(해당 요청의 일회성 자격 증명을 직접 전달할 때는 `X-Acquisition-Credential` 사용).
- **데이터 격리와 설치 전체 범위**: 다운로드 작업과 수동 배치는 키 신원별로 격리됩니다(각 키는 자신이 만든 작업과 배치만 조회·취소·관리할 수 있으며 관리자는 전체 조회 가능. 단 프로세스당 1개인 수동 배치 슬롯은 모든 키가 공유). 갤러리, 소설 라이브러리, 컬렉션, 번역, 받은 편지함, 아카이브, 페이지 편집, 설정, 로그, 업데이트, 유지보수, 데이터베이스 이전과 환경 아카이브는 공유 설치 전체에 직접 적용되며 키별 전용 사본이 아닙니다. 로컬 전용 유지보수·복구 API는 계속 로컬 루프백 출발지를 요구합니다.
- **프로그램 전용 진입점**: 초기 인증 설정, 키 관리, GUI token, CAPTCHA 상호작용, 브라우저 수집 티켓, 익명 할당량 및 페이지 조립은 전용 흐름을 유지하며 이전 브라우저 호환 라우트에는 프로그램 권한을 부여하지 않습니다. 프로그램에서는 `/api/download/tasks`(다운로드 및 진행 상황), `/api/tools/file-import`(기록 파일 가져오기), `/api/library/artworks/{id}`(작품 관리), `/api/page-editor`(페이지 편집) 및 `/api/admin/notifications/test` 또는 `/test-all`(받은 편지함 알림 테스트)을 사용하세요.

| 권한 | 용도 | 메서드 및 경로 |
| --- | --- | --- |
| `ai:connection.test` | AI 연결 테스트(모델 호출) | POST `/api/admin/ai/test` |
| `ai:models.read` | 사용 가능한 AI 모델 조회 | POST `/api/admin/ai/models` |
| `ai:status.read` | AI 설정 상태 보기 | GET `/api/admin/ai/status` |
| `core:api-reference.read` | HTTP API 명세 읽기 및 접근 권한 확인 | GET, HEAD `/api/openapi.json` |
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
| `core:classifier.configure` | 이미지 분류 설정 변경 (지원 종료, HTTP 410) | PUT `/api/tools/classifier/settings` |
| `core:classifier.delete` | 이미지 분류 디렉터리 삭제 (지원 종료, HTTP 410) | DELETE `/api/tools/classifier/folders` |
| `core:classifier.read` | 이미지 분류 및 이미지 조회 (지원 종료, HTTP 410) | GET `/api/tools/classifier/folders`; GET `/api/tools/classifier/image`; GET `/api/tools/classifier/images`; GET `/api/tools/classifier/settings` |
| `core:classifier.write` | 분류된 이미지 이동 (지원 종료, HTTP 410) | POST `/api/tools/classifier/moves` |
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
| `core:ffmpeg.cancel` | FFmpeg 설치 취소 | POST `/api/tools/ffmpeg/tasks/{executionId}/cancel` |
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
| `core:library.move` | 작품 이동 기록 (지원 종료) · HTTP 410 | POST `/api/library/artworks/*/move` |
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
| `<plugin>:schedule.manage` | 이 플러그인의 영구 일정 및 자격 증명 정책 관리 | `core:schedule.*`. POST `/api/pixiv/user/{userId}/meta/schedule` 요청에 이 플러그인을 수집원으로 사용하는 작업의 `taskId`를 지정할 때도 필요합니다. |
| `<plugin>:plugins.manage` | 이 플러그인의 수명 주기, 활성화 상태 및 신뢰 관리 허용 | `core:plugins.*` |
