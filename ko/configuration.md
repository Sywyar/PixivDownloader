# 설정 참조

일상적인 설정은 데스크톱 GUI의 '구성 / 설정' 페이지에서 수정하고 저장한 후 안내에 따라 즉시 적용하거나 재시작하세요. 파일을 수동 편집하기 전에는 설정 항목이 속한 파일과 소유자를 확인하세요.

| 설정 유형 | 경로 | 소유자 |
| --- | --- | --- |
| 호스트 설정 및 플러그인 활성화 상태 | `config/config.yaml` | 애플리케이션 호스트 |
| 플러그인 업무 설정 | `config/plugins/{pluginId}.properties` | 해당 플러그인 |
| 플러그인 민감 자격 증명 | `config/credentials/{pluginId}.properties` | 해당 플러그인. 호스트가 암호화하여 관리 |

최초 시작 시 기본 `config/config.yaml`을 생성합니다. 업그레이드할 때는 누락된 호스트 키만 추가하고 기존 값을 덮어쓰지 않습니다. 수동 편집에는 UTF-8의 평면 `key: value` 행을 사용해야 합니다. 빈 값 대신 설정을 주석 처리하거나 이미 외부 플러그인으로 분리된 `mail.*`, `push.*`, `notification.*`, `download.novel-*` 등의 키를 `config.yaml`에 다시 넣지 마세요.

## 설정 및 환경 이전

Swing과 Compose 설정 페이지 모두 '설정 및 환경 이전'을 제공하며 저장된 설정만 읽습니다. 내보내기 전에 변경 사항을 저장하고 가져오기 전에는 저장하지 않은 초안을 처리하세요.

1. **`.pdbackup` 내보내기**: 기본적으로 호스트 및 등록된 플러그인 설정, 플러그인 스위치와 데스크톱 환경설정을 포함합니다. 자격 증명, 데이터베이스, 저장 상태와 사용자 리소스(컬렉션 아이콘, 소설 참조 음성)를 선택할 수 있습니다. 자격 증명, 상태나 데이터베이스를 포함하면 아카이브 비밀번호를 반드시 설정해야 하며 내보내기 동안 백엔드를 잠시 중지합니다.
2. **미리보기 및 가져오기**: 대상 환경에 호환 플러그인과 데이터베이스 연결을 준비한 후 아카이브를 선택하여 미리 봅니다. 설치하지 않은 플러그인의 설정과 파일은 기록하지 않으며 아카이브는 플러그인을 자동 설치하거나 신뢰 승인을 복원하지 않습니다. 가져올 필드, 데이터베이스와 파일 그룹을 선택합니다. 리소스는 데이터베이스와 함께 가져와야 합니다. 확인 후 대상을 먼저 백업하고 전체 프로세스 재시작 후 이전을 실행합니다.

가져오기는 대상 환경의 데이터베이스 연결, 데이터베이스 provider 선택과 설치 식별자를 유지하고 대상 마스터 키로 자격 증명을 다시 암호화합니다. 관리자 자격 증명을 가져오면 로그인 계정을 교체하고 기존 세션을 무효화합니다. 예약 작업은 비활성화 상태로, 수동 대기열은 일시 중지 상태로 복원합니다. 프로그램 본체, 플러그인 패키지, 작품 파일과 캐시는 아카이브에 포함되지 않으므로 작품 폴더는 별도로 복사해야 합니다. 경로 매핑은 선언된 경로 필드와 데이터베이스 접두사만 갱신하며 `{0}`은 확인한 절대 폴더로 고정합니다. 작업 백업과 데이터베이스별 보고서는 `data/environment-transfers/<작업 ID>/`에 저장되며 최신 상태는 `pending.json`에서 확인합니다. '복구 필요'가 표시되면 애플리케이션이 시작을 막으므로 보고서와 백업을 확인하세요. 강제로 시작하려고 로그를 삭제하지 마세요.

### Windows 설치 폴더 이전

Windows 설치 폴더를 이동한 후 설치 패키지를 실행하고 새 폴더를 선택하여 이전을 확인하면 레지스트리 설치 경로, 바로가기, 제거 기록과 기존 현재 사용자 자동 시작 항목을 갱신합니다. 원래 꺼져 있던 자동 시작을 켜지는 않습니다. 복구 백업은 새 폴더의 `state/installation-backups/`에 저장합니다. 새 위치에서 시작과 데이터가 정상인지 확인한 후 이전 폴더를 정리하고 이전 폴더의 제거 프로그램은 실행하지 마세요. 이전 MSI 버전은 먼저 백업을 내보내고 MSI를 제거한 다음 새 버전을 설치하여 가져와야 합니다.

## 호스트 설정(`config/config.yaml`)

### 서비스, 다운로드 및 동시 작업 상한

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `server.port` | `6999` | HTTP / HTTPS 수신 포트 |
| `debug.enabled` | `false` | 디버그 모드 |
| `download.root-folder` | `pixiv-download` | 작품 산출물 루트 폴더. 다운로드한 작품만 저장 |
| `download.user-flat-folder` | `false` | 작가 일괄 다운로드 시 작가 하위 폴더 생략 여부 |
| `download.max-concurrent` | `10` | 동시 다운로드 상한. 추가 대기 작품은 최대 100개이며 가득 차면 429 반환 |
| `database.maximum-pool-size` | `28` | 주 데이터베이스 연결 풀 상한. 최솟값 `8` |
| `ffmpeg.max-concurrent` | `2` | FFmpeg 변환과 미디어 탐색의 공유 동시 작업 수. 범위 `1–8`, 백엔드 재시작 후 적용 |

설정으로 높일 수 없는 고정 안전 상한이 있습니다. 작품 이미지 / 표지 / 내장 이미지 하나는 최대 100 MiB, 단일 일러스트 또는 소설 다운로드 작업의 누적 상한은 1 GiB입니다. Ugoira ZIP은 최대 100 MiB, 프레임/항목 500개, 항목당 압축 해제 32 MiB, 총 압축 해제 200 MiB, 프레임당 25,000,000픽셀입니다. 한 번의 변환은 최대 10분, 출력은 100 MiB입니다. 서비스는 `Content-Length`, 실제 디코딩 스트림과 이미지 파일 헤더 허용 목록을 함께 검증하며 상한을 넘으면 중단하고 불완전한 파일을 정리합니다.

### 주 데이터베이스 및 엔진 전환

기본값은 `database-sqlite`(`data/pixiv_download.db`)입니다. PostgreSQL 17 / 18(UTF8)로 전환하려면 먼저 플러그인 마켓에서 `database-postgresql`을 설치하고 완전히 재시작하세요. 주 DB와 대응된 플러그인 전용 DB는 선택한 엔진을 공유합니다. SQLite 전용 DB는 `data/{pluginId}/plugin.db`에 있으며 PostgreSQL 전용 DB는 owner별 schema와 제한된 역할로 격리합니다.

데스크톱의 '설정 → 실행 및 네트워크 → 주 데이터베이스' 또는 플러그인 마켓의 데이터베이스 설정에서 전환합니다.

1. 로드된 데이터베이스 플러그인을 선택하고 연결 정보를 입력한 후 '연결 테스트'를 누릅니다. 비밀번호를 비워 두면 저장된 값을 유지합니다.
2. '확인 후 계속'을 눌러 주 DB와 모든 플러그인 전용 DB의 원본, 대상과 처리 방식을 사전 검사합니다. 비어 있지 않은 대상은 provider가 안전한 백업 교체를 지원할 때만 계속할 수 있습니다.
3. 목록을 확인하면 애플리케이션이 이 페이지의 설정을 저장하고 자동으로 완전히 재시작합니다. 새 프로세스에서 백업, 데이터 이전, DB별 검증(타입별 SHA-256, 행 수, 생성 키 워터마크와 NUL 문자 정리) 및 원자적 교체를 수행합니다. 모두 통과해야 연결 설정을 전환합니다. 같은 데이터베이스의 연결 인수만 수정할 때는 '연결 설정 적용 후 재시작'을 사용합니다.

이전 기록과 백업은 `data/database-migrations/`에 저장합니다. 계획에 사용한 자격 증명이 포함될 수 있으므로 안전하게 보관하고 시작 시 재검사와 재시도를 위해 유지하세요. 선택한 데이터베이스 플러그인이 없거나 비활성화되어 있거나 연결에 실패하면 복구 모드로 들어갑니다. 업무 API는 503을 반환하고 백그라운드 데이터베이스 작업을 중지하며 다른 엔진에 자동 연결하거나 빈 SQLite를 만들지 않습니다.

#### PostgreSQL 준비 및 설정 예시

전용 데이터베이스와 주 DB schema를 미리 만들고 테이블, 인덱스, 시퀀스, 함수, 트리거 생성 및 읽기/쓰기 권한을 부여하세요. 플러그인 전용 DB를 사용하려면 `CREATEROLE`과 데이터베이스 `CREATE` 권한도 필요하며 `PUBLIC`에 주 DB나 다른 owner의 권한을 공개해서는 안 됩니다. `currentSchema`는 소문자, 숫자와 밑줄로 이루어진 단일 이름만 지원합니다. 숫자로 시작할 수 없고 최대 63자이며 기본값은 `public`입니다. 원격 연결에는 `sslmode=verify-full`을 권장합니다. 비밀번호는 별도 설정 항목이나 `DATABASE_PASSWORD` 환경 변수에 넣고 JDBC URL에 넣지 마세요. `config.yaml`을 직접 바꿔도 기존 데이터가 이전되지는 않습니다.

```yaml
database.provider: database-postgresql
database.engine: postgresql
database.jdbc-url: "jdbc:postgresql://db.example.com:5432/pixiv?currentSchema=pixiv&sslmode=verify-full"
database.username: pixiv_app
database.password: "REPLACE_WITH_DATABASE_PASSWORD"
```

#### 중지 상태의 CLI 마이그레이션 도구(선택 사항)

중지 시간을 별도로 정하고 **빈 대상 DB**로 이전하려면 원본 DB를 사용하는 모든 프로세스를 중지하고 `database.provider`를 명시한 `source.yaml`과 `target.yaml`을 준비하세요. SQLite 원본에는 `database.provider: database-sqlite`와 `database.jdbc-url: "jdbc:sqlite:data/pixiv_download.db"`를 사용할 수 있습니다. 압축을 모두 푼 폴더에서 실행합니다.

```powershell
java '-Dloader.main=top.sywyar.pixivdownload.migration.DatabaseMigration' -cp '<boot.jar>' org.springframework.boot.loader.launch.PropertiesLauncher --source-config source.yaml --target-config target.yaml --backup migration/source.backup --report migration/report.json
```

- 원본 DB에 재생성 가능한 소설 객체가 있으면 `--rebuild-objects novels_fts,novel_soft_delete_cleanup`을 명시적으로 추가합니다. 실제 존재하는 객체만 입력하세요.
- 데이터베이스 데이터를 보존한 채 제거할 플러그인은 제거 전에 `--export-schema schema.json`으로 선언을 내보내고 이전 시 `--retained-schema schema.json`으로 보완할 수 있습니다.
- `report.json` 상태가 `DATA_VERIFIED`이고 모든 `stores`가 `COMMITTED`일 때만 복사 검증 완료를 뜻합니다. 그 후 적용할 설정을 전환하고 애플리케이션을 시작하세요.

### 이미지 및 Ugoira 저장 형식

데스크톱의 '설정 → 다운로드' 또는 웹 다운로드 설정에서 저장 형식을 여러 개 선택할 수 있습니다. 최소 하나는 유지해야 하며 기본값은 `config/plugins/download-workbench.properties`의 `download-workbench.media.*`에 저장합니다.

- **이미지**: 원본 형식(기본값), PNG, JPG, WebP. 원본 형식을 해제하면 선택한 모든 파생 형식이 성공적으로 생성된 후에만 원본 이미지를 삭제합니다.
- **Ugoira**: WebP(기본값), GIF, APNG, MP4(FFmpeg H.264 인코더 필요), 원본 ZIP. ZIP은 오프라인 추가 변환을 위한 `.frames.properties` 프레임 타이밍도 저장합니다.
- **변환 인수**: `quality` 기본 `90`(`1–100`), `webp-lossless` 기본 `false`, `maximum-edge` 기본 `0`(크기 조절 없음, `1–16383` 설정 가능). 새로 인코딩한 사본에만 적용합니다.

기존 작품은 데스크톱의 '도구 → 기존 미디어 처리'에서 대상 형식별로 검사하고 누락된 형식과 썸네일을 보완할 수 있습니다. 배치당 최대 500개 작품, 10,000개 후보 파일입니다. 갤러리는 '보존한 원본 → WebP → PNG → JPG' 순서로 우선 표시하며 MP4는 동영상 플레이어를 사용합니다.

### 플러그인 마켓

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | 플러그인 마켓 전체 스위치. 끄면 모든 플러그인 저장소에 접근하지 않음 |
| `plugin-catalog.official-repository-enabled` | `true` | 내장 공식 저장소 사용 여부 |
| `plugin-catalog.community-repository-enabled` | `true` | 내장 커뮤니티 저장소 사용 여부 |
| `plugin-catalog.connect-timeout-ms` | `15000` | 저장소 연결 시간 제한(밀리초) |
| `plugin-catalog.read-timeout-ms` | `60000` | 저장소 읽기 시간 제한(밀리초) |
| `plugin-catalog.max-manifest-bytes` | `1048576` | 단일 매니페스트 크기 상한(1 MiB) |
| `plugin-catalog.max-package-bytes` | `201326592` | 단일 플러그인 패키지 크기 상한(192 MiB) |
| `plugin-catalog.repositories` | 빈 목록 | 사용자 정의 플러그인 저장소 목록 |

데스크톱의 '설정 → 플러그인 마켓 설정'에서 `repository.json` 설명자를 가져오거나 사용자 정의 저장소를 입력하는 방법을 권장합니다. 수동 설정 예시:

```yaml
plugin-catalog.enabled: true
plugin-catalog.repositories:
  - id: example
    display-name-key: plugin.market.repository.example.name
    manifest-url: https://plugins.example.com/manifest.json
    enabled: true
    proxy-policy: direct-strict
    trusted-keys:
      - key-id: example-2026
        algorithm: Ed25519
        public-key: BASE64_X509_SUBJECT_PUBLIC_KEY_INFO
        state: ACTIVE
        publisher: Example Publisher
        trust-label: Example repository release key
```

저장소 `id`는 고유해야 하며 예약값 `official`, `community`, `configured`를 사용할 수 없습니다. `proxy-policy`는 `direct-strict`(직접 연결, 공인 HTTPS만, 리디렉션 금지), `proxy-trusted`(애플리케이션 프록시 사용, 내장 신뢰 호스트에만 최대 5회 리디렉션을 허용하며 매번 재검증)와 `custom`을 지원합니다.

### 아웃바운드 프록시 및 브라우저 PAC

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `proxy.enabled` | `true` | 호스트 아웃바운드 HTTP 프록시 활성화 |
| `proxy.host` | `127.0.0.1` | 프록시 호스트 |
| `proxy.port` | `7890` | 프록시 포트 |

사용자 스크립트 사용 등으로 브라우저의 `pixiv.net` 접근에도 백엔드와 같은 프록시를 쓰되 시스템 전체 프록시는 켜고 싶지 않다면 시스템 또는 브라우저의 '자동 프록시 설정 스크립트(PAC) URL'을 `http://localhost:6999/proxy.pac`로 설정할 수 있습니다. 로컬에서만 접근할 수 있고 프록시 설정의 실시간 재로딩에 따라 자동 갱신되며 Pixiv 관련 도메인만 프록시로 보냅니다.

### 다중 사용자 모드 할당량 및 게스트 요청 제한

`multi-mode.*`는 다중 사용자 모드에서 로그인하지 않았고 초대 코드도 없는 **익명 방문자**만 제한합니다. 초대 코드를 가진 **게스트**는 `solo` / `multi` 모두에서 별도의 `guest-invite.*` 제한을 사용합니다.

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `multi-mode.quota.enabled` | `true` | 익명 방문자 할당량 스위치 |
| `multi-mode.quota.max-artworks` | `50` | 주기당 최대 작품 할당량 |
| `multi-mode.quota.reset-period-hours` | `24` | 할당량 초기화 주기(시간) |
| `multi-mode.quota.archive-expire-minutes` | `60` | 압축 아카이브 만료 시간(분) |
| `multi-mode.quota.limit-image` | `0` | 작품당 이미지가 이 수를 넘으면 여러 할당량으로 환산. `0`은 환산하지 않음 |
| `multi-mode.quota.max-proxy-requests` | `200` | 프록시 요청 할당량 |
| `multi-mode.quota.archive-max-concurrent` | `10` | 동시 아카이브 작업 상한 |
| `multi-mode.post-download-mode` | `pack-and-delete` | `pack-and-delete`, `never-delete`, `timed-delete` 선택 가능 |
| `multi-mode.delete-after-hours` | `72` | `timed-delete` 보존 시간 |
| `multi-mode.limit-page` | `3` | 페이지 수 제한. `0`은 제한 없음 |
| `multi-mode.request-limit-minute` | `300` | 익명 방문자의 분당 요청 상한 |
| `multi-mode.static-resource-request-limit-minute` | `1200` | 익명 방문자의 분당 정적 리소스 요청 상한 |
| `guest-invite.request-limit-minute` | `300` | 초대 게스트의 분당 요청 상한 |
| `guest-invite.static-resource-request-limit-minute` | `1200` | 초대 게스트의 분당 정적 리소스 요청 상한 |
| `guest-invite.tts-request-limit-minute` | `30` | 초대 게스트의 분당 TTS 요청 상한 |
| `setup.login-rate-limit-minute` | `10` | 로그인 및 초대 코드 교환의 분당 시도 상한 |

### 유지 관리 시간, UI, 업데이트 및 예약 작업

- **유지 관리 시간**: `maintenance.enabled` 기본값은 `true`입니다. `maintenance.monday.enabled=true`이며 다른 요일은 기본 `false`입니다. 각 요일 시간 키 `maintenance.<day>.time`의 기본값은 `10:00`입니다.
- **언어 및 데스크톱 UI**: `app.language` 기본값은 비어 있어 시스템을 따르며 UI에만 영향을 줍니다. 실행 로그는 영어로 고정합니다. `app.theme` 기본값은 `system`이며 `light`, `dark`와 현재 UI 확장 테마를 선택할 수 있습니다. `app.gui-provider` 기본값은 비어 있어 Compose를 선택하며 전환 시 완전 재시작이 필요합니다. `app.config-menu-expand-all`(기본 `false`)은 Swing에만 적용됩니다.
- **온라인 업데이트**: `update.enabled=true`, `update.auto-check=true`입니다. `update.manifest-url`과 `update.nightly-manifest-url`은 공식 서명 매니페스트를 가리킵니다. `update.check-nightly`는 정식 버전에서 기본 `false`, nightly에서 기본 `true`입니다.
- **예약 작업 호스트**: `schedule.*` 키는 [예약 작업 관련 설정](/ko/scheduled-tasks#문제-해결-및-관련-설정)을 참고하세요.
- **플러그인 스위치**: `plugins.{pluginId}.enabled`는 호스트가 관리하며 적용 방식은 플러그인의 수명 주기 정책에 따라 다릅니다([플러그인 관리](/ko/plugin-management) 참고).

### HTTPS 및 리버스 프록시

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `ssl.domain` | `localhost` | 외부 접근 도메인 |
| `ssl.type` | `pem` | `pem` 또는 `jks`. 둘 다 설정하면 PEM 우선 |
| `server.ssl.enabled` | `false` | 내장 HTTPS 활성화 여부 |
| `server.ssl.certificate` / `server.ssl.certificate-private-key` | 비어 있음 | PEM 인증서와 개인 키 경로 |
| `server.ssl.key-store-type` / `key-store` / `key-store-password` | `JKS` / 비어 있음 / 비어 있음 | JKS 키 저장소 설정 |
| `ssl.http-redirect` / `ssl.http-redirect-port` | `false` / `80` | HTTP 자동 HTTPS 리디렉션 |
| `server.trusted-proxy-cidrs` | 비어 있음 | 신뢰하는 리버스 프록시 출구 IPv4 / IPv6 CIDR. 쉼표로 구분 |

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

`server.trusted-proxy-cidrs`가 비어 있으면 직접 연결 모드로 실행하며 `Forwarded`, `X-Forwarded-*` 또는 `X-Real-IP`를 포함한 모든 요청을 거부합니다. 설정할 때는 실제 백엔드에 연결하는 리버스 프록시 출구나 컨테이너 네트워크 대역만 입력하세요. `0.0.0.0/0`, `::/0` 또는 클라이언트 대역은 금지합니다. 신뢰하는 프록시는 모든 요청에 완전하고 단일 형식의 전달 헤더를 제공해야 합니다. `for`/`proto`/`host`를 포함한 RFC `Forwarded` 또는 `X-Forwarded-For` + `X-Forwarded-Proto` + `X-Forwarded-Host` 묶음입니다. 누락, 혼용 또는 신뢰 CIDR에서 헤더 없이 직접 연결하면 HTTP 400을 반환합니다.

## 플러그인 설정, 자격 증명 및 확장 설정

- **플러그인 업무 설정(`config/plugins/{pluginId}.properties`)**: UTF-8 Java properties 형식이며 데스크톱 설정 페이지에 모아 표시하고 함께 저장합니다. 호스트 키를 덮어쓰거나 평문 자격 증명을 기록하면 안 됩니다.
- **플러그인 자격 증명(`config/credentials/{pluginId}.properties`)**: 비밀번호, Cookie, API Key, Secret과 Webhook 비밀 키를 저장합니다. 호스트가 암호화하고 owner별로 플러그인 컨텍스트에 주입합니다. 다른 환경에서 암호화된 자격 증명을 복원하려면 자격 증명 마스터 키도 보존해야 합니다.
- **번역 설정(`translation` 플러그인)**: 일반 항목은 `config/plugins/translation.properties`에 있습니다. `translation.engine` 기본값은 `ai`이며 `youdao`, `libretranslate`를 선택할 수 있습니다. `translation.libretranslate-url`에는 `/translate`를 포함하지 않습니다. `translation.use-proxy` 기본값은 `false`이며 Youdao와 LibreTranslate만 제어합니다. `translation.max-concurrent` 기본값은 `10`입니다. 저장 후 백엔드를 재시작해야 적용됩니다. [소설 및 낭독](/ko/novel#번역-엔진관리자)을 참고하세요.
- **로그인 검증 정책**: `setup.login-verification-policies` 기본값은 비어 있습니다. 쉼표로 구분한 `LoginVerification.policyId()` 정책 키를 최대 16개 설정할 수 있으며 백엔드 재시작 후 적용됩니다. 설정 전에 해당 검증 플러그인이 활성화되어 있는지 확인하세요. [작업 CAPTCHA](/ko/captcha)와 [서드파티 플러그인 SDK](/ko/plugin-development#gui에-의존하지-않는-자격-증명-및-일반-작업-검증)을 참고하세요.
