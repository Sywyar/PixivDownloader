# 저장소 및 백업

PixivDownloader는 다운로드 작품, 호스트 실행 파일과 외부 플러그인 패키지를 별도 폴더로 관리합니다. 모든 상대 경로는 JAR이 있는 폴더가 아닌 프로그램의 **현재 작업 폴더**를 기준으로 합니다. 배포 패키지의 시작 스크립트와 Windows 바로가기는 작업 폴더를 프로그램 루트로 설정합니다.

## 최상위 폴더 구조

| 분류 | 기본 경로 | 용도 |
| --- | --- | --- |
| 설정 | `config/` | 호스트 `config.yaml`, 플러그인 업무 설정과 암호화된 자격 증명 |
| 상태 | `state/` | 초기화 상태, 로그인 세션, 대기열 체크포인트, GUI 안내 표시와 플러그인 상태 |
| 데이터 | `data/` | SQLite 주 DB, 플러그인 전용 DB, 컬렉션 아이콘, 썸네일/TTS 캐시와 마이그레이션/복구 기록 |
| 플러그인 | `plugins/` | 외부 플러그인 설치 패키지, provenance와 실행 시 전용 고정 작업 공간 |
| 로그 | `log/` | GUI와 백엔드 실행 로그 |
| 작품 | `{rootFolder}/` | `download.root-folder`(기본값 `pixiv-download`)로 지정한 작품 파일 |

작품 메타데이터와 페이지별 미디어 기록은 선택한 데이터베이스에 모아 저장합니다. 새 다운로드는 작품 폴더에 `.meta.json`이나 `.media.properties`를 생성하지 않습니다. Ugoira의 원본 ZIP을 보존할 때는 프레임 타이밍을 담은 `.frames.properties`가 함께 제공됩니다. 설정, 데이터베이스, 플러그인 패키지, 상태나 캐시를 작품 다운로드 루트 폴더에 넣지 마세요.

## 실행 시 파일 상세

### 설정, 상태 및 데이터

| 경로 | 내용 및 주의 사항 |
| --- | --- |
| `config/config.yaml` | 호스트 설정과 `plugins.{id}.enabled` 활성화 상태 |
| `config/plugins/{pluginId}.properties` | 해당 플러그인의 비민감 업무 설정 |
| `config/credentials/{pluginId}.properties` | 호스트가 암호화하여 관리하고 owner별로 주입하는 민감 자격 증명 봉투 |
| `config/image_classifier.properties` | 이미지 분류 도구 폴더 설정 |
| `config/web-publications.enrolled` | 페이지 편집기의 정식 게시 등록 파일. 캐시로 취급하여 삭제하면 안 됨 |
| `state/setup_config.json` | 초기 관리자 계정, 실행 모드와 로그인 상태. 삭제하면 다시 초기화해야 함 |
| `state/download-workbench/` | 다운로드 작업 화면의 일괄 대기열 체크포인트와 레이아웃 피드백 상태 |
| `state/gui/`, `state/download_root_marker.txt` | GUI 안내 표시, 마지막으로 해석한 다운로드 루트 절대 경로 |
| `state/{pluginId}/` | 플러그인이 `RuntimePathProvider`로 얻는 owner 상태 폴더 |
| `data/pixiv_download.db` | 기본 SQLite 주 DB. 실행 중 `-wal` / `-shm`이 동반될 수 있으므로 백업 전 정상 종료 필요 |
| `data/collection_icons/`, `data/novel/narration-voice/` | 컬렉션 사용자 정의 아이콘, 소설 다중 화자 낭독의 참조 음성 |
| `data/gallery_thumbs/`, `data/tts/`, `data/backfill/` | 재생성 가능한 갤러리 썸네일 캐시, Edge TTS 버전 캐시, 데이터 보완 시 접근 불가 기록 |
| `data/install_identity.txt` | 최초 실행 시 생성하는 영구 설치 식별자 UUID |
| `data/delete-staging/`, `data/database-migrations/`, `data/environment-transfers/`, `data/web-publications/` | 삭제/게시 복구용 임시 저장, 데이터베이스 전환과 환경 이전 백업 보고서, 게시된 페이지 상태. 캐시로 취급하여 삭제하지 말 것 |
| `data/{pluginId}/` | 플러그인 영구 데이터와 SQLite 전용 DB `plugin.db` |

### 외부 플러그인 폴더(`plugins/`)

| 경로 | 용도 |
| --- | --- |
| `plugins/*.jar`, `plugins/*.zip` | 설치된 원본 플러그인 패키지. 오프라인 재검증 및 신원 신뢰의 원본 |
| `plugins/provenance/*.pixiv-plugin-provenance` | 플러그인 출처, 다이제스트, 서명과 재검증 기록 |
| `plugins/runtime/` | 각 실행 세대(generation)가 독점하는 전용 고정 작업 공간. 공유 캐시나 설치 원본이 아님 |
| `plugins/.preparing/`, `.staging/`, `.transaction-cleanup/`, `.pixivdownload-runtime.lock` | 설치 트랜잭션, 충돌 복구와 실행 폴더 잠금 |

JVM 속성 `pixivdownload.plugins-dir`로 플러그인 루트 폴더를 지정할 수 있습니다. 프로그램 실행 중 `plugins/` 아래 파일을 수동으로 덮어쓰거나 이동하거나 삭제하지 마세요. 설치, 업데이트와 제거는 [플러그인 관리](/ko/plugin-management) 트랜잭션으로 수행하세요.

## 다운로드 작품 폴더 구조

| 기본 경로 | 내용 |
| --- | --- |
| `{root}/{artworkId}/` | 단일 작품, URL 일괄, 검색 등의 일러스트 / 만화 / 움짤. 합성한 `_p0.webp`와 `_p0_thumb.jpg` 포함 |
| `{root}/{artist}/{artworkId}/` | 작가 일괄 다운로드. `download.user-flat-folder=true`이면 `{artist}/` 단계 생략 |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | 만화 시리즈 표지 |
| `{root}/novel-{novelId}/` | 개별 소설의 TXT / HTML / EPUB와 내장 이미지 |
| `{root}/novel-series-{seriesId}/` | 소설 시리즈 표지와 합본 |
| `{root}/douyin/{owner}/...` | Douyin 플러그인 기본 다운로드 폴더 또는 해당 플러그인에서 설정한 사용자 정의 폴더 |
| `{root}/_archives/{token}.zip` | 일괄 내보내기와 다중 사용자 모드 할당량용 단기 압축 파일 |

서드파티 다운로드 유형 플러그인도 작품을 `{root}/{pluginId}/` 또는 사용자가 플러그인 설정에서 지정한 작품 폴더에 저장해야 합니다. 도구 페이지의 '기존 데이터 및 메타데이터 가져오기'는 등록된 작품 폴더에서 이전 `<작품 ID>.meta.json` 스냅샷을 가져올 수 있지만 데이터베이스가 없으면 폴더의 이미지 파일만으로 작품 등록을 재구성할 수 없습니다.

## 데이터베이스 경로 접두사 및 폴더 이전

데이터베이스는 파일 경로를 `{N}/relative/path`로 저장합니다.

- **`{0}` 기호 루트**: `download.root-folder`가 기본값 `pixiv-download`와 같은 상대 경로이면 다운로드 루트 내부 파일은 `{0}/...`로 기록되며 시작할 때마다 '현재 작업 폴더 + 상대 다운로드 루트'로 해석됩니다. **`pixiv-download/`를 포함한 프로그램 폴더 전체를 이전하면 데이터베이스 경로를 수동으로 수정할 필요가 없습니다.**
- **일반 `{N}` 접두사**: 다운로드 루트나 컬렉션이 절대 경로를 사용하면 기록은 `path_prefixes` 테이블의 절대 경로 접두사를 참조합니다.

작품 다운로드 루트 폴더만 이동하려면:

1. 프로그램을 정상 종료합니다.
2. 파일 시스템에서 작품 폴더를 새 위치로 이동합니다.
3. 프로그램을 시작하고 데스크톱 GUI의 '상태' 페이지에서 '다운로드 디렉터리 마이그레이션'을 엽니다. 실제 새 위치를 선택하고 `config.yaml` 및 데이터베이스 경로 접두사를 함께 갱신하도록 확인합니다. 이전 도구는 경로 참조만 갱신하며 디스크 파일을 대신 이동하지 않습니다.
4. 재시작 후 이전 작품, 갤러리 미리보기와 새 다운로드 한 건을 점검합니다.

SQLite와 PostgreSQL 사이의 데이터베이스 엔진 전환은 별도 절차입니다. [설정 참조의 데이터베이스 전환](/ko/configuration#주-데이터베이스-및-엔진-전환)을 참고하세요.

## 백업 및 복원

프로그램을 중지한 후 전체 백업에 다음을 포함하세요.

1. `config/`, `state/`, `data/`, `plugins/`.
2. `download.root-folder`, 컬렉션의 사용자 정의 다운로드 폴더와 서드파티 플러그인의 사용자 정의 작품 폴더.
3. PostgreSQL을 사용한다면 서버의 주 DB와 모든 플러그인 전용 schema.
4. [외부 다운로드 가져오기](/ko/pixiv-batch-downloader-import)를 사용했다면 읽기 전용으로 참조하는 외부 원본 폴더.
5. 다른 컴퓨터/배포 환경에서 `config/credentials/`의 암호화된 자격 증명을 복원하려면 해당 자격 증명 마스터 키도 보존해야 합니다. 없으면 대상 환경에 자격 증명을 다시 입력해야 합니다.

복원 후 관리자 로그인, 갤러리와 소설 본문 읽기, 예약 작업 상태와 새 다운로드 한 건을 차례로 확인하세요.
