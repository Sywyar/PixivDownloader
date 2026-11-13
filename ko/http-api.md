# 관리자 HTTP API

외부 프로그램은 관리자 로그인 세션을 `X-Session-Token`으로 전달할 수 있습니다. 기존 세션 취소와 유지보수 모드 제한이 적용되는 사용자 세션이며, 별도 애플리케이션 키나 MCP 서버를 제공하지 않습니다. Pixiv 수집 자격 증명은 관리자 신원과 별도로 `X-Acquisition-Credential`로 보냅니다.

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
