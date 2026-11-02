# 저장 원칙

PixivDownloader는 다운로드한 작품, 호스트 실행 데이터, 외부 플러그인 설치 파일을 분리합니다. 상대 경로의 기준은 JAR 위치가 아니라 **현재 작업 디렉터리**입니다. 배포판 실행 스크립트와 Windows 바로가기는 배포 디렉터리를 작업 디렉터리로 설정합니다.

## 최상위 디렉터리

| 경로 | 내용 |
| --- | --- |
| `config/` | 호스트·플러그인 설정, 암호화된 자격 증명 |
| `state/` | 설치 상태, 대기열 이어받기, GUI 표시, 플러그인 복구 상태 |
| `data/` | SQLite, 사용자 리소스, 캐시, 플러그인 영구 데이터 |
| `plugins/` | 원본 플러그인 artifact, provenance, 실행 중 고정 복사본 |
| `log/` | GUI와 백엔드 로그 |
| `{rootFolder}/` | `download.root-folder`가 지정한 작품 파일 |

기본 다운로드 루트는 상대 경로 `pixiv-download`입니다. 작품 등록과 페이지별 미디어 기록은 선택한 데이터베이스에 저장합니다. 작품 디렉터리에 설정, 데이터베이스, 플러그인, 상태, 캐시를 넣지 마세요.

## 설정과 상태

| 경로 | 용도 |
| --- | --- |
| `config/config.yaml` | 호스트 설정, `plugins.{id}.enabled` |
| `config/plugins/{pluginId}.properties` | 해당 플러그인의 비민감 업무 설정 |
| `config/credentials/{pluginId}.properties` | 호스트가 암호화해 해당 플러그인에만 주입하는 자격 증명 |
| `config/image_classifier.properties` | 이미지 분류 도구의 대상 디렉터리 |
| `state/setup_config.json` | 초기 설치, 실행 모드, 로그인 상태 |
| `state/download-workbench/batch_state.json` | 다운로드 작업대의 일괄 대기열 이어받기 |
| `state/download-workbench/layout-feedback-state.json` | 작업대 화면 피드백 중복 방지 |
| `state/gui/` | GUI 안내와 프록시 단계 표시 |
| `state/download_root_marker.txt` | 이전에 해석한 다운로드 루트 절대 경로 |
| `state/{pluginId}/` | `RuntimePathProvider`가 제공하는 플러그인 소유 상태 루트, 필요할 때 생성 |

소유자가 다른 설정·자격 증명 파일을 교환, 병합, 이름 변경하지 마세요. [설정](/ko/configuration)의 소유권 설명을 참고하세요. 상태를 삭제하면 초기화, 로그인, 대기열 이어받기 또는 플러그인 상태가 사라질 수 있습니다. 정리 전에 어떤 기능이 소유한 파일인지 확인하세요.

## 데이터

| 경로 | 용도 |
| --- | --- |
| `data/pixiv_download.db` | SQLite 주 데이터베이스, 실행 중 `-wal`·`-shm`도 생길 수 있음 |
| `data/collection_icons/{id}.{ext}` | 컬렉션 아이콘 |
| `data/gallery_thumbs/{artworkId}/p{n}-{edge}.{ext}` | 재생성 가능한 갤러리 썸네일 |
| `data/tts/chromium-version.txt` | Edge TTS Chromium 버전 캐시 |
| `data/novel/narration-voice/{castId}/{characterId}.{ext}` | 소설 캐릭터 참조 음성 |
| `data/backfill/unreachable.json` | 보충 도구의 접근 불가 작품 |
| `data/install_identity.txt` | 첫 실행에서 만든 뒤 계속 재사용하는 설치 UUID |
| `data/delete-staging/` | 삭제와 게시의 영구 복구 백업, 일반 캐시로 정리하지 않음 |
| `data/database-migrations/` | 데이터베이스 전환 보고서, 설정 스냅샷, 원본·대상 백업 |
| `data/{pluginId}/` | `RuntimePathProvider`의 플러그인 소유 데이터 루트, 필요할 때 생성 |

주 데이터베이스에는 작품, 경로 참조, 이력, 설치된 기능의 도메인 데이터를 저장합니다. 플러그인 전용 테이블의 스키마와 수명 주기는 해당 플러그인이 관리합니다. 실행 중인 SQLite에서 `.db`만 복사하고 WAL을 빠뜨리지 마세요. 백업 전 정상 종료해야 합니다.

## 외부 플러그인

| 경로 | 용도 |
| --- | --- |
| `plugins/*.jar`, `plugins/*.zip` | 설치 원본, 관리 식별과 오프라인 재검증의 신뢰 자료 |
| `plugins/provenance/<artifact>.pixiv-plugin-provenance` | 출처, 해시, 서명, 마지막 검증 결과 |
| `plugins/runtime/` | 실행 중 각 세대의 임의 비공개 고정 작업 공간 |
| `plugins/.preparing/`, `plugins/.staging/`, `plugins/.transaction-cleanup/` | 설치 트랜잭션과 중단 복구 |
| `plugins/.pixivdownload-runtime.lock` | 실행 디렉터리 lease |

시스템 속성 `pixivdownload.plugins-dir`로 플러그인 루트를 바꿀 수 있습니다. 디렉터리가 없으면 자동 생성하지 않고 진단을 표시하지만 핵심 복구 화면은 사용할 수 있습니다.

실행 중에 이 파일들을 덮어쓰거나 이동·삭제하지 마세요. 설치, 업데이트, 제거, 롤백은 플러그인 관리 수명 주기를 통해 artifact와 provenance를 함께 처리해야 합니다. `plugins/runtime/`은 검증된 원본으로 재생성할 수 있지만 다른 프로세스와 공유하는 다운로드 캐시가 아닙니다.

## 작품 디렉터리

| 경로 | 내용 |
| --- | --- |
| `{root}/{artworkId}/` | 단일 작품, URL 일괄, 검색 등 Pixiv 일러스트 |
| `{root}/{artist}/{artworkId}/` | 작가 일괄, `download.user-flat-folder=true`이면 작가 계층 생략 |
| `{root}/{artworkId}/{filename}_p0.webp`, `..._p0_thumb.jpg` | Ugoira WebP와 첫 프레임 썸네일 |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | 만화 시리즈 표지 |
| `{root}/novel-{novelId}/` | 단일 소설 TXT/HTML/EPUB와 관련 파일 |
| `{root}/novel-series-{seriesId}/` | 소설 시리즈 표지와 선택적 합본 |
| `{root}/douyin/{owner}/...` | Douyin 기본 다운로드 위치 |
| `{root}/_archives/{token}.zip` | 다중 사용자 모드 할당량 및 갤러리 내보내기의 임시 압축 파일 |

Pixiv 메타데이터와 페이지별 미디어 형식은 선택한 데이터베이스에 있습니다. 기본 SQLite는 `data/pixiv_download.db`입니다. 새 다운로드는 `.meta.json`이나 `.media.properties`를 만들지 않습니다. 이미 받은 응답의 페이지, 원본 주소, 게시 시각, 번역 정보를 저장하며 추가 Pixiv 요청은 보내지 않습니다.

백업과 이동에는 데이터베이스와 미디어 파일이 모두 필요합니다. 데이터베이스를 잃으면 파일만으로 갤러리 작품 등록을 복원할 수 없습니다. 기존 보조 파일은 자동 일괄 삭제하지 않습니다. Ugoira ZIP을 저장하면 프레임 시간을 담은 `.frames.properties`도 보존합니다.

Douyin은 `DownloadSettings.getRootFolder()` 아래 `douyin`을 기본으로 하며 요청 owner별로 구분합니다. `douyin.download.directory`가 비어 있지 않으면 해당 경로를 사용하고 과거 `data/douyin/downloads`는 사용하지 않습니다. 컬렉션도 기본 루트 밖의 독립 디렉터리를 지정할 수 있습니다.

타사 다운로드 유형은 다운로드 루트 아래 플러그인 ID 디렉터리 또는 사용자가 해당 플러그인에서 명시한 작품 디렉터리에 저장해야 합니다. `state/{pluginId}`와 `data/{pluginId}`는 보조 상태·데이터용입니다.

## 데이터베이스 경로와 이동

경로는 긴 절대 경로 대신 `{N}/relative/path`로 기록할 수 있습니다. `N>0`은 `path_prefixes`의 절대 경로를 참조하므로 접두사를 바꾸면 관련 기록도 함께 새 루트를 가리킵니다.

### 상대 루트 `{0}`

다운로드 루트가 상대 경로이면 루트 안 기록에 `{0}/...`을 사용할 수 있습니다. 실행할 때마다 현재 작업 디렉터리와 상대 다운로드 루트로 해석합니다. 따라서 전체 배포 디렉터리와 `pixiv-download/`를 함께 옮겨도 기록이 작품을 찾을 수 있습니다.

절대 다운로드 루트는 일반 `{N}` 접두사를 사용합니다. 이동 후에는 GUI 상태 페이지의 다운로드 디렉터리 마이그레이션으로 기록을 갱신하세요. `state/download_root_marker.txt`는 이전 경로를 저장해 설정만 바꾸고 파일은 옮기지 않은 경우를 찾습니다. 마이그레이션 도구는 설정과 데이터베이스 경로만 바꾸며 **디스크 파일을 이동하지 않습니다**.

### 전체 디렉터리 이동

상대 `download.root-folder`를 유지하고 프로그램을 종료한 뒤 배포 디렉터리 전체를 옮깁니다. 새 디렉터리에서 실행 스크립트를 시작해야 작업 디렉터리, 실행 데이터, `{0}`이 함께 이동합니다.

### 다운로드 루트만 이동

1. 프로그램을 정상 종료합니다.
2. 파일 시스템에서 작품 디렉터리를 옮깁니다.
3. GUI 상태 페이지의 다운로드 디렉터리 마이그레이션에서 실제 새 위치를 선택하고 `config.yaml`도 갱신할지 결정합니다.
4. 안내에 따라 재시작한 뒤 과거 작품, 갤러리, 새 다운로드를 일부 확인합니다.

먼저 설정만 바꾸어도 파일이 자동으로 이동하지는 않습니다. SQLite와 PostgreSQL 간 전환은 별도의 데이터베이스 검사와 마이그레이션입니다. 디렉터리나 연결 설정 변경만으로 데이터베이스가 복사되지는 않습니다. [설정](/ko/configuration)을 참고하세요.

## 백업과 복원

완전한 백업에는 `config/`, `state/`, 종료 후 복사한 `data/`, `plugins/`, 다운로드 루트와 컬렉션·플러그인의 별도 작품 디렉터리를 포함하세요. 플러그인 설정, 암호화 자격 증명, 서명과 provenance도 보존합니다.

PostgreSQL은 서버의 주 데이터베이스와 플러그인 전용 스키마를 별도로 백업해야 합니다. 로컬 `data/` 복사로 대체할 수 없습니다. `data/database-migrations/`와 `data/delete-staging/`은 관련 트랜잭션 상태를 확인할 때까지 보존하세요.

`log/`는 보통 문제 해결에 필요합니다. `data/gallery_thumbs/`와 `plugins/runtime/`은 다른 영구 데이터로 재생성할 수 있지만 복원을 단순하게 하려면 함께 백업해도 됩니다.

복원 후 관리자 로그인, 갤러리·소설 기록, 파일 열기, 예약 설정, 새 다운로드를 확인하세요. 외부 가져오기 작품은 원본 파일을 참조하므로 원본 디렉터리도 필요합니다. 상대 배치를 유지하거나 도구로 절대 경로를 갱신하세요. 암호화 자격 증명은 생성 당시의 마스터 키에 의존합니다. 다른 빌드·배포로 복원할 때 키 호환성을 확인하고 호환되지 않으면 대상 환경에서 다시 입력하세요.
