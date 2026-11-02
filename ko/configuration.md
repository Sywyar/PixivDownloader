# 설정 참고

일반 설정은 데스크톱 설정 페이지에서 변경·저장하고 적용 또는 재시작 안내를 따르세요. 직접 편집하기 전 파일 소유자를 확인합니다.

| 종류 | 경로 | 소유자 |
| --- | --- | --- |
| 호스트 설정, 플러그인 활성 상태 | `config/config.yaml` | 애플리케이션 |
| 플러그인 업무 설정 | `config/plugins/{pluginId}.properties` | 해당 플러그인 |
| 플러그인 자격 증명 | `config/credentials/{pluginId}.properties` | 해당 플러그인, 호스트가 암호화 저장 |

첫 실행은 현재 버전 기본 템플릿으로 호스트 설정을 만듭니다. 업그레이드는 누락 키만 추가하고 기존 값을 덮어쓰지 않습니다. UTF-8과 활성 `key: value` 줄을 사용하고 빈 값을 주석 줄로 대신하지 마세요.

## 설정과 환경 이전

Swing과 Compose 설정 화면에서 환경을 이전할 수 있습니다. 보관 파일에는 저장된 값만 들어갑니다. 내보내기 전에 필요한 설정을 저장하고, 가져오기 전에 저장하지 않은 편집을 처리하세요.

1. 원본 설치에서 `.pdbackup`을 내보냅니다. 호스트와 등록된 플러그인이 선언한 설정, 플러그인 활성 상태, 데스크톱 기본 설정이 포함됩니다. 자격 증명, 데이터베이스, 저장 상태, 사용자 리소스는 선택 사항입니다. 자격 증명·상태·데이터베이스에는 암호가 필요하며 내보내는 동안 백엔드가 잠시 중지됩니다.
2. 대상 설치에 호환 플러그인과 데이터베이스 연결을 준비하고 보관 파일을 미리 봅니다. 없는 플러그인의 설정과 파일은 쓰지 않습니다. 버전 목록과 저장소 주소는 누락 항목을 설치할 때 참고하는 정보이며, 패키지를 설치하거나 신뢰 권한을 복원하지 않습니다.
3. 필드, 데이터베이스, 파일 그룹을 선택하고 경로를 확인합니다. 데이터베이스와 파일 그룹은 기본적으로 선택되지 않으며, 리소스는 해당 데이터베이스와 함께 가져옵니다. 대상을 백업한 뒤 전체 프로세스를 다시 시작하여 복원합니다. 이전 화면을 다시 열어 최종 상태를 확인하세요.

대상의 데이터베이스 연결, 제공자 선택, 설치 식별자는 유지합니다. 자격 증명은 대상 키로 다시 암호화합니다. 관리자 자격 증명을 선택하면 로그인 정보를 바꾸고 세션을 지우지만 대상의 실행 모드는 유지합니다. 예약 작업은 비활성화하고 수동 대기열은 일시 중지 상태로 복원합니다. 타사 플러그인의 비공개 데이터 의미는 해당 플러그인의 선언에 따릅니다.

프로그램, 플러그인 패키지, 작품 파일, 캐시, 신뢰 키와 권한은 포함하지 않습니다. 지원 리소스에는 컬렉션 아이콘과 소설 참조 음성이 있습니다. 작품은 따로 복사하세요. 경로 매핑은 선언된 필드와 데이터베이스 접두사에만 적용하며, `{0}`은 확인한 절대 디렉터리로 고정합니다. 등록 접두사 밖의 절대 경로는 바꾸지 않습니다. 기존 작품과 새 다운로드를 각각 확인하세요.

백업과 데이터베이스별 보고서는 `data/environment-transfers/<작업 ID>/`, 마지막 상태는 `pending.json`에 남습니다. 주 데이터베이스와 비공개 데이터베이스는 따로 커밋합니다. 복구 필요 상태는 전체 롤백을 뜻하지 않으며 업무 처리를 시작하지 않습니다. 작업 디렉터리를 보존하고 보고서와 백업을 대조하세요. 강제 시작을 위해 로그를 지우지 마세요. 완료되지 않은 파일 복구가 있으면 원래 설치에서 먼저 완료한 뒤 다시 내보냅니다.

### Windows 설치 디렉터리 이동

옮긴 디렉터리에서 앱을 실행하면 등록 상태를 확인할 수 있습니다. 설치 프로그램에서 새 디렉터리를 명시하고 이전을 확인하면 레지스트리 설치 위치, 일반 바로가기, 제거 기록을 갱신합니다. 기존 현재 사용자 자동 시작 바로가기는 활성 상태와 인수를 유지하며 대상을 바꿉니다. 꺼진 자동 시작을 켜지는 않습니다.

복구 백업은 새 프로그램 디렉터리의 `state/installation-backups/`에 남습니다. 이전 디렉터리와 사용자 데이터를 보존하므로 새 위치에서 시작과 데이터 접근을 확인한 뒤 이전 복사본을 처리하세요. 이전 디렉터리의 제거 프로그램을 실행하지 마세요. 포터블 디렉터리 복사는 Windows에 등록하지 않지만 설치 프로그램을 실행하면 대상을 등록합니다. 이전 MSI 설치는 먼저 내보내고 Windows에서 제거한 다음 새 위치에 설치하여 가져오세요.

## 호스트 설정

### 서비스와 다운로드

| 키 | 기본값 | 의미 |
| --- | --- | --- |
| `server.port` | `6999` | HTTP/HTTPS 포트 |
| `debug.enabled` | `false` | 디버그 모드 |
| `download.root-folder` | `pixiv-download` | 작품 파일 루트 |
| `download.user-flat-folder` | `false` | 작가 디렉터리 계층 생략 |
| `download.max-concurrent` | `10` | 동시 다운로드 상한, 별도 대기는 최대 100작품이며 가득 차면 새 요청에 429 |
| `database.maximum-pool-size` | `28` | 주 데이터베이스 연결 풀 상한, 최소 8 |

다운로드 루트는 작품만 저장합니다. 설정·데이터베이스·상태·캐시는 별도입니다. 소설, Douyin 등 전용 설정은 해당 플러그인이 제공합니다.

Pixiv 이미지와 소설 표지·삽화는 한 장당 100 MiB, 일반 일러스트·소설 작업 하나에서 이 응답들의 합계는 1 GiB까지입니다. `Content-Length`와 실제 디코딩한 스트림을 모두 검사하며 초과 시 전송을 중지하고 부분 파일을 지웁니다. 확장자는 URL 경로, `Content-Type`, 검증한 파일 헤더로 이미지 허용 목록에서 고릅니다. URL 쿼리는 파일명에 넣지 않습니다. 이 한도는 설정으로 늘릴 수 없습니다.

Ugoira ZIP은 100 MiB, 500개 항목·프레임, 항목별 압축 해제 32 MiB, 합계 200 MiB, 프레임별 25,000,000픽셀까지입니다. 변환 프로세스는 최대 10분, 출력은 100 MiB입니다. 초과하면 임시 파일을 정리하되 보관하도록 선택한 ZIP·프레임 시간 파일은 남깁니다.

### 주 데이터베이스와 마이그레이션

기본 SQLite 플러그인은 `data/pixiv_download.db`와 기존 경로 규칙을 유지합니다. PostgreSQL 17·18(UTF8)을 쓰려면 마켓에서 해당 플러그인을 설치하고 완전히 재시작한 뒤 연결을 선택합니다. 주 데이터베이스와 호환 계약으로 구현된 플러그인 전용 저장소는 선택한 같은 엔진을 사용합니다. SQLite 전용 저장소는 `data/{pluginId}/plugin.db`, PostgreSQL은 owner별 독립 스키마와 제한된 계정입니다.

#### 설정 화면에서 전환

Swing·Compose의 설정 → 실행 및 네트워크 → 주 데이터베이스 또는 마켓의 데이터베이스 설정을 사용합니다.

1. 로드된 공급자를 선택하고 연결 정보를 입력합니다. 비밀번호를 비워 두면 기존 값을 유지합니다. 삭제하려면 비밀번호 지우기를 선택하고 저장합니다.
2. 연결 테스트로 주소·계정을 확인합니다. 성공해도 설정 저장, 데이터베이스 생성, 데이터 이동은 하지 않습니다. 설정 변경 후 검사하고 계속을 눌러 앱과 모든 플러그인 데이터의 원본·대상·처리 방식을 검사합니다.
3. 확인 목록을 검토합니다. 데이터가 있는 대상을 안전하게 교체할 수 있으면 백업 후 교체를 안내하고, 지원하지 않으면 거부합니다. 취소하면 입력값을 유지한 채 돌아가며 데이터베이스 연결은 아직 저장하지 않습니다.
4. 확인하면 같은 페이지의 다른 설정을 먼저 저장하고 업무를 중지한 뒤 전체 재시작합니다. 새 프로세스가 데이터 이전·검증을 완료한 후에만 연결을 전환하고 시작합니다. 같은 데이터베이스의 연결 정보만 고칠 때는 연결 적용 및 재시작을 사용하며 데이터는 복사하지 않습니다.

화면에서 현재 사용 중인 연결과 재시작 후 연결을 구분합니다. 환경 변수·실행 인수가 덮어쓰고 있으면 먼저 해당 값을 수정하세요.

대상을 교체하기 전에 기존 대상 백업을 남기고 검증된 데이터를 데이터베이스별로 교체합니다. 백업·기록은 `data/database-migrations/`의 작업 디렉터리에 있습니다. 예약 자격 증명이 들어 있을 수 있으므로 민감 자료로 보관하세요. 작품·참조 음성·자격 증명 파일은 원래 위치에 남습니다.

실패하면 원래 연결 설정을 유지하고 각 데이터의 실제 결과를 보여 줍니다. 다른 항목 실패 때문에 이미 저장한 데이터가 자동 취소되지는 않습니다. 결과 불확실은 저장 여부를 아직 확인하지 못했다는 뜻이며 롤백 완료가 아닙니다. 원본과 백업을 보존하고 원인을 고친 뒤 다시 검사하세요. 재시도는 원본 변경과 대상 일치를 확인하고 검증된 이전 결과만 재사용합니다.

선택한 공급자의 누락·비활성화·비호환·로드 실패 또는 연결 실패는 복구 모드로 이어집니다. 업무 페이지는 마켓으로 이동하고 API는 503을 반환합니다. 직접 의미 조회는 데이터베이스 사용 불가를 명시한 빈 결과를 반환하며 쓰기와 관련 백그라운드 처리는 중지됩니다. 공급자나 연결을 복구한 뒤 확인 절차로 재시작하세요. 다른 엔진이나 빈 SQLite로 자동 전환하지 않습니다. 원래 연결을 해석할 수 없다면 목록에 데이터 이전 없이 연결만 수정한다고 명시합니다.

#### PostgreSQL 준비

전용 데이터베이스와 주 스키마를 만들고 계정에 테이블·인덱스·시퀀스·함수·트리거 및 데이터 읽기·쓰기 권한을 부여합니다. 플러그인 전용 저장소에는 독립 로그인 역할·스키마 생성을 위한 `CREATEROLE`과 데이터베이스 `CREATE`도 필요합니다. `PUBLIC`에 주 스키마나 다른 owner의 데이터 권한을 열면 격리 검사에서 연결을 거부합니다.

`currentSchema`는 소문자·숫자·밑줄로 된 한 이름만 허용하며 숫자로 시작할 수 없고 최대 63자입니다. 생략하면 `public`입니다. 원격 연결에는 `sslmode=verify-full`, 별도 CA에는 `sslrootcert`를 사용하세요. JDBC는 지정 서버에 직접 연결하며 Pixiv HTTP 프록시를 사용하지 않습니다.

다음은 예시이며 실제 서버·계정·비밀번호로 바꿔야 합니다. 비밀번호는 별도 키 또는 `DATABASE_PASSWORD`에 넣고 JDBC URL에는 넣지 마세요.

```yaml
database.provider: database-postgresql
database.engine: postgresql
database.jdbc-url: "jdbc:postgresql://db.example.com:5432/pixiv?currentSchema=pixiv&sslmode=verify-full"
database.username: pixiv_app
database.password: "REPLACE_WITH_DATABASE_PASSWORD"
```

직접 편집하면 다음 실행의 연결만 바뀝니다. 기존 데이터는 복사하지 않으므로 이전이 필요하면 설정 화면을 사용하세요.

#### 관리자용 오프라인 도구

독립적인 중단 시간이나 보고서가 필요할 때 CLI를 사용합니다. 앱 설정은 자동 전환하지 않으며 설정 화면의 비어 있지 않은 대상 백업·교체도 제공하지 않습니다.

원본·대상 플러그인을 설치하고 원본에 쓰는 앱·플러그인·오프라인 도구를 모두 중지하세요. 작품, 플러그인 전용 저장소, 참조 음성, 설정, 자격 증명을 유지합니다. 실제 연결을 `source.yaml`, `target.yaml`로 준비하되 현재 유효 설정은 바꾸지 않습니다. 완전히 압축 해제한 디렉터리의 PowerShell에서 `<boot.jar>`를 실제 파일명으로 바꿔 실행합니다.

`source.yaml`과 `target.yaml` 모두 `database.provider`를 명시해야 합니다. SQLite 원본에는 `database.provider: database-sqlite`와 `database.jdbc-url: "jdbc:sqlite:data/pixiv_download.db"`를 사용할 수 있습니다. 두 파일을 보관하고 마이그레이션 후에도 `source.yaml`을 덮어쓰지 마세요. 전용 데이터베이스 검사에서 원본을 찾는 데 계속 사용합니다.

```powershell
java '-Dloader.main=top.sywyar.pixivdownload.migration.DatabaseMigration' -cp '<boot.jar>' org.springframework.boot.loader.launch.PropertiesLauncher --source-config source.yaml --target-config target.yaml --backup migration/source.backup --report migration/report.json
```

도구는 인스턴스 잠금을 얻고 원본 공급자로 일관된 백업·구조·데이터를 준비한 뒤 빈 대상에 가져와 검증합니다. SQLite 백업은 커밋된 WAL을 포함하고 이전 구조는 작업 복사본에서 업그레이드합니다. SQLite↔PostgreSQL 양방향에서 ID, 밀리초 시각, NULL, 소프트 삭제, 메타데이터, 미디어, 파일 참조를 보존하고 테이블별 행 수·해시·생성 키 시퀀스를 확인합니다. 비어 있지 않은 대상, 소유 선언 없는 원본 테이블, 호환되지 않는 유형은 거부하며 지우거나 무시하지 않습니다.

SQLite의 재생성 가능한 소설 객체가 실제 존재하면 `--rebuild-objects novels_fts,novel_soft_delete_cleanup`을 추가하되 존재하는 이름만 넣습니다. PostgreSQL 시작 시 공급자가 소설 선언에 따라 삭제 트리거를 복구하고 본문 검색은 이전된 본문을 읽습니다. 다른 보조 객체는 공급자의 재생성 지원을 확인해야 하며 일반 이력 테이블을 이 옵션으로 제외할 수 없습니다.

설치되었지만 비활성화한 플러그인도 이전 선언을 제공합니다. 데이터를 남기고 제거하기 전 같은 도구의 `--export-schema schema.json`으로 선언을 저장하고 이전 때 `--retained-schema schema.json`으로 누락 owner를 보완할 수 있습니다. 파일은 현재 SDK와 맞아야 합니다. 전용 저장소 구조 업그레이드를 실행할 플러그인은 설치되어 있어야 하며 선언 파일만으로 대체하지 못합니다. 선언이 없다면 플러그인을 복원하거나 검증한 선언을 준비하고 이력 테이블을 삭제하지 마세요.

호환 전용 스키마를 선언한 owner 데이터도 이전합니다. 알 수 없는 owner나 누락 선언은 복사 전에 거부합니다. 주 데이터베이스와 전용 저장소는 별도 커밋이므로 부분 실패가 이미 커밋한 항목을 자동 롤백하지 않습니다. 선택 저장과 시작 전에 전용 저장소 이전 영수증과 원본 변경을 재검사합니다. 타사 JDBC나 SQLite 전용 SQL은 자동 호환되지 않으므로 작성자의 대응이 필요합니다.

`DATA_VERIFIED`만 복사 검증 완료입니다. 보고서, 재생성 객체, 항목별 결과를 확인한 뒤 유효 연결을 바꾸고 갤러리·소설 검색·알림·예약·파일 접근을 확인하세요. `IN_PROGRESS`, `FAILED_TARGET_NOT_VERIFIED`는 성공이 아닙니다. 백업을 보존하고 대상 트랜잭션 결과를 확인하세요. CLI 재시도는 새 백업과 확인된 빈 대상이 필요하며 이어받기를 제공하지 않습니다. 전환 후 대상에 새 쓰기가 있었다면 이전 연결만 복원하면 증분을 잃습니다. 설정에서 다시 검사해 역이전하거나 관리자가 중지 후 처리해야 합니다.

#### 마이그레이션 백업 및 검증 세부 사항

연결 필드와 형식은 데이터베이스 플러그인이 선언합니다. SQLite는 연결 필드를 표시하지 않고 PostgreSQL은 주소, 사용자 이름과 비밀번호를 표시합니다. 해석할 수 없는 원래 연결만 복구할 때도 원래 설정 스냅샷을 보관하며 전용 데이터베이스 마이그레이션 검사를 수행합니다.

설정 화면에서 SQLite 대상을 교체할 때 제공자는 기존 데이터베이스를 잠그고 커밋된 WAL을 포함하는 일관된 백업을 만든 뒤 임시 데이터베이스로 가져옵니다. 모든 데이터베이스를 검증한 후 원래 위치에서 데이터베이스별 트랜잭션으로 내용을 교체합니다. 교체 커밋 전에 실패하면 기존 내용을 보존하며, 이미 커밋된 데이터베이스는 취소하지 않습니다. 검사 후 대상이 바뀌거나 알 수 없는 테이블이 있으면 교체를 거부합니다. 각 작업의 `target-*.backup`은 주 데이터베이스와 각 전용 데이터베이스의 기존 내용을 따로 보관합니다. 양쪽에 연결된 다른 앱과 오프라인 쓰기 도구를 종료하세요. `data/database-migrations/`의 기록, 설정 스냅샷과 백업은 시작 검사와 재시도에 필요합니다. 인증 정보가 포함될 수 있으므로 안전하게 보관하세요.

PostgreSQL 원본은 현재 지원하는 구조여야 합니다. 백업은 데이터, 시퀀스와 지원하는 객체 정의를 담은 형식 1의 논리 XML이며 `pg_dump` 파일이 아닙니다. 현재 도구에는 이 파일을 직접 복원하는 기능이 없습니다. 원본 제공자가 없거나 비활성화되어 있으면 마이그레이션을 중단하며 다른 드라이버로 대체하지 않습니다. SDK 마이그레이션 계약을 따르는 제공자는 내보내기와 가져오기를 직접 구현하므로 호스트에 제공자별 분기를 추가할 필요가 없습니다.

마이그레이션은 바이너리도 보존하고 형식별 SHA-256 해시와 생성 키의 과거 최댓값을 검증하며 참조 경고를 보고합니다. 고아 레코드는 삭제하지 않습니다. 중립 저장소 인터페이스로 전달한 텍스트 매개변수의 NUL(U+0000)은 제공자가 제거합니다. 줄바꿈, 탭, 다른 유효한 Unicode, 빈 문자열, NULL과 바이너리는 유지하며 잘못된 인코딩은 거부합니다. 기존 텍스트를 옮길 때는 임시 작업 복사본만 처리한 뒤 대상의 실제 내용을 검증하므로 원본과 최초 백업은 바뀌지 않습니다. 정규화로 고유 키 충돌이나 제약 위반이 발생하면 실패로 처리하며 레코드를 자동 병합하거나 삭제하지 않습니다.

PostgreSQL 원본의 `--rebuild-objects`에는 실제 존재하는 트리거와 함수 이름을 지정하고, 소유 플러그인이 대상에서 다시 생성할 수 있는지 먼저 확인하세요. 제공자는 전용 데이터베이스 발견, 격리, 물리 매핑, SQL 방언과 마이그레이션을 담당합니다. 업무 플러그인은 구조와 업무 변환을 선언하고 중립 저장소 계약으로 읽고 씁니다. 도구는 타사 플러그인의 임의 SQL을 자동 변환하지 않습니다.

모든 데이터베이스를 복사하고 검증한 뒤 개별 커밋을 시작합니다. 전환 및 시작 검사는 신뢰한 원본 제공자를 통해 전용 데이터베이스의 원본 변경 정보와 정확한 대상 식별 정보를 확인합니다. 원본 설정, 원본 데이터나 대상이 바뀌면 전환을 거부합니다. 보고서와 전용 데이터베이스 이관 기록은 `formatVersion: 1`을 사용하며 교환 모델과 기록은 알 수 없는 형식을 거부합니다.

보고서의 `stores`에는 데이터베이스별로 `COMMITTED`, `ROLLED_BACK` 또는 `UNKNOWN`을 기록합니다. 취소나 실패 시 아직 커밋을 시도하지 않은 세션을 중단합니다. 일부 커밋된 결과를 전체 롤백으로 보고할 수 없습니다. 커밋 도중 연결 끊김, 강제 종료 또는 보고서 저장 실패가 발생하면 `COMMITTING`, `UNKNOWN`이나 이전 진행 상태가 남을 수 있으므로 대상을 직접 확인해야 합니다. 실패 후 남은 빈 데이터베이스 파일, 전용 schema와 역할은 자동 삭제하지 않습니다.

`DATA_VERIFIED`는 모든 커밋, 이관 기록 저장과 리소스 해제까지 완료되어야 합니다. 전환 전에 참조 경고를 확인하고 원본 데이터와 설정을 보관하세요. 대상에 새로 쓴 데이터가 없다면 앱을 종료하고 원래 설정으로 돌아갈 수 있습니다. 새 쓰기가 있다면 바로 되돌릴 경우 증분 데이터를 잃게 됩니다.

### 이미지와 Ugoira 형식

설정 → 다운로드에서 형식·품질·WebP 무손실·최대 변을 저장하고 백엔드를 재시작합니다. 여러 형식을 선택할 수 있으며 최소 하나는 유지합니다.

- 이미지: 원본(기본), PNG, JPG, WebP. 원본 선택을 해제하면 모든 선택 출력 성공 후에만 원본을 제거합니다. 실패·취소 시 보존하고 같은 형식은 재사용합니다.
- Ugoira: WebP(기본), GIF, APNG, MP4, 원본 ZIP. MP4에는 H.264 인코더가 필요합니다. ZIP 보관 시 프레임 시간도 저장합니다.
- 품질은 기본 90, 범위 1~100입니다. WebP 무손실은 기본 해제, 최대 변은 기본 0(유지) 또는 1~16,383입니다. 새 인코딩에만 적용하고 재사용 원본은 재인코딩하지 않습니다.

두 웹 다운로드 화면에서도 이번 다운로드·새 예약의 형식을 선택합니다. 기본값 파일은 `config/plugins/download-workbench.properties`이며 키는 `download-workbench.media.image-formats`, `ugoira-formats`, `quality`, `webp-lossless`, `maximum-edge`입니다. 모두 같은 `download-workbench.media.` 접두사를 사용합니다.

호스트 `ffmpeg.max-concurrent`는 기본 2, 범위 1~8이며 변환과 미디어 분석이 공유합니다. 저장 후 백엔드를 재시작합니다.

기존 작품은 Compose의 도구 → FFmpeg 및 도구 → 과거 미디어 처리 또는 Swing 도구에서 형식을 선택하고 누락 항목을 검사합니다. ID 입력은 필요 없습니다. 결과 확인 후 시작하면 누락 형식·썸네일만 보충하고 기존 파일을 유지합니다. 뒤로 가도 취소되지 않습니다. Ugoira에는 보관 ZIP·프레임 시간이, MP4에는 실제 H.264 지원이 필요합니다.

한 번에 최대 500작품·10,000후보 파일, 동시 작업은 하나입니다. 상한에 도달하면 처리 후 다시 검사하세요. 검사만으로 파일이 생기지 않으며 옵션 변경 후에는 재검사해야 합니다. 기본 다운로드 설정 변경이 과거 처리 작업을 시작하지 않습니다. 갤러리는 남아 있는 원본, WebP, PNG, JPG 순으로 선택하고 MP4는 동영상으로 보여 줍니다.

### 플러그인 마켓

| 키 | 기본값 |
| --- | --- |
| `plugin-catalog.enabled` | `true` |
| `plugin-catalog.official-repository-enabled` | `true` |
| `plugin-catalog.connect-timeout-ms` | `15000` |
| `plugin-catalog.read-timeout-ms` | `60000` |
| `plugin-catalog.max-manifest-bytes` | `1048576` |
| `plugin-catalog.max-package-bytes` | `201326592` |
| `plugin-catalog.repositories` | 빈 목록 |

마켓 열기·새로고침·설치 시 저장소에 접근합니다. 전체 스위치를 끄면 어느 저장소에도 접근하지 않습니다. 시간은 밀리초, 크기는 바이트이며 기본 매니페스트는 1 MiB, 패키지는 192 MiB입니다.

공식 주소·신뢰 루트는 내장되어 있습니다. 사용자 저장소는 자신의 HTTPS 매니페스트와 Ed25519 공개 키를 명시해야 하며 공식 신뢰를 상속하지 않습니다. GUI 편집기를 권장하며 다음은 대체값이 필요한 예시입니다.

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

ID는 고유해야 하며 `official`, `configured`는 사용할 수 없습니다. `direct-strict`는 HTTPS 직접 연결, 공인 주소만 허용하고 리다이렉트를 거부합니다. `proxy-trusted`는 앱 프록시를 사용하며 내장 허용 호스트로 최대 5회 리다이렉트하고 매번 검사합니다. `custom`은 `allow-redirects`, `strict-https`, `allow-non-public-addresses`, `use-proxy`를 따릅니다.

항목별 `connect-timeout-ms`, `read-timeout-ms`, `max-manifest-bytes`, `max-package-bytes`를 덮어쓸 수 있으며 생략·0은 전역값을 상속합니다.

### 외부 프록시

`proxy.enabled=true`, `proxy.host=127.0.0.1`, `proxy.port=7890`이 기본입니다. 플러그인은 안정 HTTP/WebSocket SDK 또는 core-api 프록시 계약을 사용해야 하며 호스트 `ProxyConfig` 구현을 직접 읽으면 안 됩니다.

#### 백엔드 설정 프록시를 통해 웹에서 Pixiv 접속(시스템 프록시 불필요)

백엔드는 설정에 지정된 프록시(기본값 `127.0.0.1:7890`)를 통해 Pixiv에 접속하며 시스템 프록시에 의존하지 않습니다. Clash의 시스템 프록시를 켜지 않고 브라우저에서 `pixiv.net`을 직접 열고 싶다면 내장 프록시 자동 구성(PAC)을 사용하세요.

운영 체제/브라우저의 "자동 프록시 구성 스크립트(PAC) URL"을 `http://localhost:6999/proxy.pac`으로 설정하세요(설정한 포트에 맞추고 HTTPS를 활성화하면 `https://<domain>:<port>/proxy.pac`이 됩니다). 그러면 Pixiv 관련 도메인만 동일한 백엔드 설정 프록시를 통과하고 나머지는 직접 연결됩니다. 이 엔드포인트는 로컬 전용이며 프록시 변경(핫 리로드 포함)이 자동으로 반영되므로 시스템 프록시를 반복해서 전환할 필요가 없습니다.

Firefox 설정은 `about:preferences#general`, Windows 프록시 설정은 `ms-settings:network-proxy`에서 열 수 있습니다。

### 다중 사용자와 초대 게스트

`multi-mode.*`는 multi 모드의 로그인하지 않은 방문자용입니다. 로그인한 관리자나 초대 게스트와 혼동하지 마세요.

| 키 | 기본값 |
| --- | --- |
| `multi-mode.quota.enabled` | `true` |
| `multi-mode.quota.max-artworks` | `50` |
| `multi-mode.quota.reset-period-hours` | `24` |
| `multi-mode.quota.archive-expire-minutes` | `60` |
| `multi-mode.quota.limit-image` | `0` |
| `multi-mode.quota.max-proxy-requests` | `200` |
| `multi-mode.quota.archive-max-concurrent` | `10` |
| `multi-mode.post-download-mode` | `pack-and-delete` |
| `multi-mode.delete-after-hours` | `72` |
| `multi-mode.request-limit-minute` | `300` |
| `multi-mode.static-resource-request-limit-minute` | `1200` |

다운로드 후 모드는 `pack-and-delete`, `never-delete`, `timed-delete`입니다. `multi-mode.limit-page`는 기본 3이며 0은 제한 없음입니다.

초대 게스트는 solo·multi에서 별도 제한을 사용합니다.

| 키 | 기본값 |
| --- | --- |
| `guest-invite.request-limit-minute` | `300` |
| `guest-invite.static-resource-request-limit-minute` | `1200` |
| `guest-invite.tts-request-limit-minute` | `30` |
| `setup.login-rate-limit-minute` | `10` |

### 유지 관리 시간

`maintenance.enabled`는 기본 `true`입니다. `maintenance.<day>.time`은 모두 `10:00`이며 `maintenance.monday.enabled=true`, 나머지 `tuesday`, `wednesday`, `thursday`, `friday`, `saturday`, `sunday`의 `.enabled`는 `false`입니다.

### HTTPS와 리버스 프록시

| 키 | 기본값 |
| --- | --- |
| `ssl.domain` | `localhost` |
| `ssl.type` | `pem` |
| `server.ssl.enabled` | `false` |
| `server.ssl.certificate` | 빈 값 |
| `server.ssl.certificate-private-key` | 빈 값 |
| `server.ssl.key-store-type` | `JKS` |
| `server.ssl.key-store` | 빈 값 |
| `server.ssl.key-store-password` | 빈 값 |
| `server.trusted-proxy-cidrs` | 빈 값 |
| `ssl.http-redirect` | `false` |
| `ssl.http-redirect-port` | `80` |

`pem`은 인증서·개인 키 경로, `jks`는 key store를 사용합니다. 개인 키와 비밀번호를 저장소에 넣지 마세요.

`server.trusted-proxy-cidrs`는 쉼표로 구분한 숫자 IPv4/IPv6 CIDR만 받습니다. 예: `127.0.0.1/32,172.18.0.0/16`. 실제 백엔드에 연결하는 프록시 출구나 컨테이너 네트워크만 지정하세요. 클라이언트 네트워크, `0.0.0.0/0`, `::/0`를 신뢰하지 마세요. 비워 두면 직접 연결 모드이며 모든 `Forwarded`, `X-Forwarded-*`, `X-Real-IP` 헤더를 거부합니다.

신뢰 프록시는 매 요청에 다음 중 완전한 한 형식만 제공해야 합니다.

- RFC `Forwarded`: 선택된 프록시 경계에 `for`, `proto`, `host` 모두 포함.
- 기존 형식: `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host`, 선택적 `X-Forwarded-Port`.

프록시 체인의 오른쪽부터 첫 비신뢰 주소를 클라이언트로 선택하고 인증·제한·CSRF 동일 출처 확인 전에 주소·외부 프로토콜·호스트·포트를 정규화합니다. 비신뢰 출처의 전달 헤더, 비신뢰 클라이언트가 없는 체인, 누락·혼합·불일치·위조 형식은 400입니다. 모든 요청에 프록시 메타데이터가 필요하므로 `127.0.0.1/32`를 신뢰하면 같은 주소에서 헤더 없이 직접 보낸 요청도 거부합니다.

### 언어와 데스크톱

| 키 | 기본값 | 의미 |
| --- | --- | --- |
| `app.language` | 빈 값 | 시스템 언어 또는 지원 언어 코드 |
| `app.theme` | `system` | GUI 테마 ID |
| `app.config-menu-expand-all` | `false` | Swing의 설정 메뉴 펼침만 제어합니다. Compose는 이 옵션을 제공하거나 저장된 값을 변경하지 않습니다 |
| `app.gui-provider` | 빈 값 | 기본 공급자 또는 활성 공급자 ID |

두 데스크톱 UI 모두 `system`, `light`, `dark`를 제공하며 추가 테마는 선택한 UI에 따라 달라집니다. GUI 공급자 목록은 활성 `DesktopUiProvider`에서 가져옵니다. 배포본에는 Compose와 Swing이 포함되며, 최초 실행 시 공급자를 지정하지 않으면 Compose를 선택합니다. UI 전환에는 앱 전체 재시작이 필요합니다.

언어와 테마는 즉시 미리 볼 수 있습니다. 선택을 유지하려면 설정 페이지의 공통 저장 기능을 사용하세요. 언어 설정은 화면에 적용되며 로그는 영어로 출력됩니다.

### 업데이트와 예약 호스트

| 키 | 기본값 |
| --- | --- |
| `update.enabled` | `true` |
| `update.manifest-url` | 공식 최신 안정 `update.json` |
| `update.nightly-manifest-url` | 공식 nightly `update.json` |
| `update.auto-check` | `true` |
| `update.check-nightly` | nightly는 `true`, 안정판은 `false` |
| `schedule.enabled` | `true` |
| `schedule.tick-interval-ms` | `60000` |
| `schedule.max-tasks` | `100` |
| `schedule.inbox-check-every` | `500` |
| `schedule.auth-failure-circuit-breaker` | `5` |
| `schedule.pending-max-attempts` | `5` |
| `schedule.overuse-defer-default-minutes` | `60` |

예약 키는 중립 호스트를 설정합니다. 다운로드 원본·인증·다운로드 인수는 각 플러그인이 소유합니다.

`plugins.{pluginId}.enabled`는 호스트가 관리합니다. 예를 들어 `plugins.douyin.enabled: true`입니다. required 플러그인은 이 키로 끌 수 없습니다. 즉시 반영 여부는 `pixiv.lifecycle-policy`와 현재 작업에 따릅니다. [플러그인 관리](/ko/plugin-management)를 참고하세요.

## 플러그인 설정과 자격 증명

플러그인은 자신의 `config/plugins/{pluginId}.properties`만 사용합니다. UTF-8 Java properties 예시는 다음과 같습니다.

```properties
example.timeout-ms=15000
example.output-format=json
```

호스트 기본 키, `plugins.*.enabled`, 자격 증명으로 보이는 키를 덮어쓰면 거부합니다. 플러그인 간 키도 중복하지 않아야 합니다. 플러그인의 Spring 자식 컨텍스트에서 `Environment`, `@Value`, `@ConfigurationProperties`로 읽고 파일이나 앱 구현 설정 클래스를 직접 사용하지 않습니다.

데스크톱 설정은 플러그인이 선언한 필드를 표시하고 앱이 통합 저장합니다. Compose와 Swing은 각자 화면을 구현합니다. 필드와 수명 주기에 따라 즉시 적용, 백엔드 재시작, 전체 재시작을 안내합니다. 직접 편집한 경우도 같은 재시작 요구를 따릅니다. 작성자는 [SDK 안내](/ko/plugin-development)를 참고하세요.

비밀번호, Cookie, Token, API key, secret, webhook key는 `config/credentials/{pluginId}.properties`에 암호화 저장합니다. 호스트가 권한·이전·owner별 주입을 관리하고 플러그인은 자기 자식 컨텍스트 `Environment`에서 복호화된 값을 읽습니다. 일반 YAML·업무 properties에 넣거나 플러그인이 자격 증명 파일을 직접 파싱·복호화하지 마세요. 백업할 때 마스터 키도 보호해야 하며 원래 키 없이는 다른 환경에서 복구할 수 없습니다.

SDK 1.4.0부터 플러그인은 `PluginCredentials`로 선언된 자신의 자격 증명을 읽고 쓸 수도 있습니다. 자격 증명 선언에는 GUI 필드가 필요하지 않습니다.

## 저장 결과 확인

설정을 다시 열어 값과 반영 안내를 확인하세요. 데이터베이스 전환은 이전 결과를, 경로 변경은 과거 작품과 새 다운로드를 확인합니다. 저장 실패 시 원본과 오류를 보존하고 다른 설정 파일로 전체를 덮어쓰지 마세요.

GUI는 호스트 필드와 설치된 플러그인 설정을 합쳐 보여 줍니다. 현재 호스트 기본값은 `DefaultConfigTemplate`, 플러그인 필드는 `GuiConfigContribution`, `@ConfigurationProperties` 또는 설정 서비스가 기준입니다. 과거 예시의 `mail.*`, `push.*`, `notification.*`, `download.novel-*`를 호스트 YAML로 복사하지 마세요. 각 플러그인 소유입니다.

## 번역 설정

`translation`이 데스크톱 번역 설정을 제공합니다. `translation.engine`의 기본값은 `ai`이며 `youdao`, `libretranslate`도 선택할 수 있습니다. 일반 설정은 `config/plugins/translation.properties`, 민감한 값은 해당 플러그인의 암호화된 인증 정보 파일에 저장됩니다. Youdao 앱 ID·비밀 키와 LibreTranslate API 키는 데스크톱 설정에서 입력하세요.

`translation.libretranslate-url`에는 `/translate`를 제외한 서비스 기본 URL을 입력합니다. 로컬 예시는 `http://127.0.0.1:5000`입니다. `translation.use-proxy`의 기본값은 `false`이며 Youdao와 LibreTranslate에 적용됩니다. AI는 기존 AI 프록시 설정을 사용합니다. `translation.max-concurrent`는 자동 번역 동시 실행 수이며 기본값은 `10`입니다. 같은 시리즈는 순서대로 처리합니다. 변경 후 저장하고 백엔드를 재시작하세요.

기존 AI 설정·번역·용어집은 유지됩니다. `download.novel-translate-max-concurrent`를 바꿨다면 번역 설정에서 동시 실행 수를 다시 지정하세요. 준비와 제한은 [소설 안내](/ko/novel)를 참고하세요.

## 로그인 검증 정책

`setup.login-verification-policies`의 기본값은 빈 문자열이며 추가 검증을 요구하지 않습니다. 플러그인의 `LoginVerification.policyId()`가 선언한 키를 쉼표로 구분해 최대 16개 설정하며 백엔드 재시작 후 적용됩니다. 필수 정책의 부재, 여러 구현, 실행 실패는 로그인을 거부합니다. 기존 비밀번호 및 속도 제한 검사는 유지합니다. 설치만으로 정책이 켜지지 않습니다. 챌린지는 로그인 화면에서 제출하며 실패 시 세션을 만들지 않습니다.

설정 전에 제공 플러그인이 사용 가능한지 확인하세요. 여전히 필수인 제공자를 제거하면 새 로그인이 차단됩니다. [플러그인 개발](plugin-development.md)의 로그인 검증 계약을 참조하세요.
