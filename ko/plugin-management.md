# 플러그인 관리

`/plugin-manage.html`에서는 플러그인 상태와 수명 주기를 관리하고, `/plugin-market.html`에서는 저장소 패키지를 찾고 설치합니다. 두 페이지는 같은 검증, 설치 트랜잭션과 수명 주기 경계를 사용합니다.

## 실행 보안

서명은 게시자 신원과 아티팩트 무결성을 증명할 뿐 안전성 검토나 실행 권한을 부여하지 않습니다.

모든 외부 플러그인은 `plugin.properties`에 `pixiv.execution-mode`를 명시해야 합니다.

| 값 | 실행 위치 | 경계 |
| --- | --- | --- |
| `host-process-full-trust` | 호스트 JVM | 호스트 프로세스의 파일, 네트워크와 OS 권한을 상속 |
| `declarative-process` | 별도 worker JVM | 제한된 프로토콜로 선언형 라우트와 capability만 게시 |

값이 없거나 비어 있거나 알 수 없으면 플러그인 코드 실행 전에 거부됩니다. worker도 호스트와 같은 OS 계정을 사용하므로 프로세스, 프로토콜과 리소스 수준의 제한적 격리만 제공하며 완전한 OS 샌드박스는 아닙니다. 현재 OS 샌드박스 provider나 이를 요구하는 JVM 스위치는 없습니다. 프로덕션 모드는 디렉터리 형태의 `declarative-process` 플러그인을 거부합니다. 명시적 개발 모드는 이를 `host-process-full-trust`로 낮추고 상태와 로그에 실제 모드를 표시합니다.

신뢰는 실행 경계를 넘어 자동으로 확대되지 않습니다. 게시자가 같아도 `declarative-process`에서 `host-process-full-trust`로 업데이트하면 관리자가 다시 확인해야 합니다. SDK 주 버전이 바뀌거나 신뢰가 철회된 뒤에도 재확인이 필요합니다. 호스트가 실제 관리자 권한으로 실행되면 full-trust 플러그인도 그 권한을 상속하며 관리 페이지에 경고가 계속 표시됩니다.

worker 기본값은 heap 128 MiB, metaspace 128 MiB, direct memory 64 MiB이며 OOM 시 종료됩니다. 초기화, 명령, 종료 제한 시간은 10,000 / 5,000 / 2,000 ms입니다. 비정상 종료 뒤 최대 3회 다시 시작하며 지연은 500 ms에서 최대 10,000 ms까지 증가합니다. stderr는 최대 1 MiB를 읽고 마지막 16 KiB를 보존합니다. worker마다 실행 중 1개와 대기 중 1개 요청만 허용합니다. worker가 종료되면 복구를 시도하기 전에 해당 라우트와 capability를 철회합니다.

JVM 시작 전에 `pixivdownload.plugin-worker.*`의 `initialize-timeout-ms`, `command-timeout-ms`, `shutdown-timeout-ms`, `restart-attempts`, `restart-initial-delay-ms`, `restart-max-delay-ms`, `stderr-max-bytes`로 해당 값을 조정할 수 있습니다.

### 패키지 수락 한도

기본값은 아카이브 192 MiB, 엔트리 48,000개, 실제 전체 압축 해제 672 MiB, 단일 엔트리 64 MiB, descriptor 1 MiB, 압축 비율 200(64 KiB 이상인 엔트리만 검사), 엔트리 이름 1,024자, 경로 깊이 64입니다. 다음 JVM 속성에 양의 정수를 지정해 변경할 수 있습니다.

- `pixivdownload.plugin.package.max-archive-bytes`
- `pixivdownload.plugin.package.max-entries`
- `pixivdownload.plugin.package.max-total-uncompressed-bytes`
- `pixivdownload.plugin.package.max-entry-uncompressed-bytes`
- `pixivdownload.plugin.package.max-descriptor-bytes`
- `pixivdownload.plugin.package.max-compression-ratio`
- `pixivdownload.plugin.package.max-entry-name-length`
- `pixivdownload.plugin.package.max-entry-depth`

잘못된 값은 기본값으로 조용히 대체되지 않고 플러그인 런타임 초기화를 실패시킵니다.

## 플러그인의 출처

Windows 설치 프로그램, Java 표준 패키지와 full-offline 패키지는 모두 `download-workbench`, `gui-compose`, `gui-swing`, `gallery-tools`, `posthog`, `gallery`, `novel`, `notification`, `multi-mode-decision-survey`, `push`, `mail`, `tts`, `ai`로 구성된 같은 공식 플러그인 배포 세트를 사용합니다. Douyin은 사용자 지정 저장소나 로컬 패키지에서 설치하는 일반 타사 플러그인입니다. 출처, 버전, SHA-256, 서명과 실행 모드를 확인하세요.

## 표시 정보

관리 페이지의 각 카드에는 플러그인 ID, 버전, 출처, 상태, 의존성, 활성화 여부와 진단 정보가 표시됩니다.

## 가능한 작업

활성화, 비활성화, load, start, quiesce, stop, unload, restart, reload와 remove를 정책에 따라 수행할 수 있습니다. `purge` 작업은 없으며, remove는 플러그인 자체의 설정과 데이터를 자동으로 지우지 않습니다. 카드에 표시된 적용 시점(즉시, 백엔드 재시작 또는 소프트웨어 재시작)을 따르세요.

## 로컬 플러그인 설치

`.jar` 또는 지원되는 `.zip`을 선택하고 필요하면 detached `.sig`를 함께 제공합니다. 서명이 있으면 정확한 아티팩트와 일치하고 적용되는 신뢰 루트로 검증되어야 합니다. 서명되지 않은 패키지는 `LOCAL_UPLOAD / UNSIGNED_ALLOWED`로 기록됩니다. 로컬 업로드는 사용자 지정 신뢰 루트를 만들지 않습니다.

비공식 로컬 패키지는 프로덕션 모드에서도 설치할 수 있지만 코드 실행 전에 위험 확인이 필요합니다. 서명된 패키지는 게시자 지문, 서명되지 않은 패키지는 정확한 SHA-256만 승인합니다. 업데이트, key 변경, 철회 또는 실행 권한 상승 시 다시 확인할 수 있습니다. 원격 저장소 패키지는 manifest가 선언한 서명이 항상 필요하며 로컬 unsigned 처리로 낮아지지 않습니다.

## 웹 플러그인 마켓

공식 또는 신뢰할 수 있는 저장소를 탐색하고 검색·필터링한 뒤 설치합니다. 크기, 해시, 서명과 출처를 검증한 뒤 수명 주기 정책에 따라 즉시 적용하거나 백엔드 또는 전체 애플리케이션 재시작을 안내합니다.

## 복구 경로

플러그인이 실패하면 진단 정보와 로그를 먼저 저장하세요. 필수 플러그인이 없거나 시작하지 못하면 앱은 복구/수리 모드로 진입할 수 있습니다.

## 데스크톱 GUI

선택된 GUI provider가 소유한 플러그인 페이지는 같은 백엔드 상태를 읽기 전용으로 표시합니다. Compose와 Swing은 각자의 UI를 소유하고 애플리케이션 비즈니스 의미를 공유합니다. 설치, 제거, 활성화와 비활성화는 Web 플러그인 관리 페이지에서 수행합니다. `gui-compose`가 기본 provider이고 `gui-swing`은 자동 대체이며 둘 다 공식 배포 세트에 포함됩니다. 두 플러그인 모두 `process-restart`이므로 설치, 업데이트, 활성화 변경, 제거 또는 provider 선택 변경 후 전체 애플리케이션을 다시 시작해야 합니다.

## 파일 시스템 경계

설치 신원은 `plugins/` 루트의 원본 아티팩트와 `plugins/provenance/` sidecar로 구성됩니다. `plugins/runtime/`은 generation별 비공개 고정 작업 영역일 뿐입니다. portable 설치에서는 `plugins/` 루트 자체를 심볼릭 링크나 Windows junction으로 둘 수 있습니다. 런타임은 실제 루트를 먼저 해석해 고정하지만 그 안의 링크된 아티팩트 후보는 개별적으로 거부합니다.

지원되는 파일 시스템에서는 `plugins/runtime/`과 `plugins/provenance/`의 POSIX 권한 또는 Windows ACL을 제한합니다. FAT32, exFAT, SMB 등이 둘 다 제공하지 않으면 진단을 남기고 일반 파일, `NOFOLLOW`, 고정 스냅샷과 해시 검사를 계속 사용합니다.

## 관련 문서

- [타사 플러그인 SDK](/ko/plugin-development)
- [설정](/ko/configuration)
- [저장 원칙](/ko/storage)
