# 설치 가이드

[Releases](https://github.com/Sywyar/PixivDownloader/releases)에서 환경에 맞는 패키지를 선택하세요. 파일명의 `*`와 `x.x.x`는 선택한 버전입니다. 명령의 `APP.jar`는 실제 주 프로그램 JAR 이름으로 바꾸고 압축을 모두 푼 디렉터리에서 실행합니다.

| 환경 | 패키지 | 별도 준비 |
| --- | --- | --- |
| Windows x64 데스크톱 | `*-win-x64-setup.exe` | 필요하면 FFmpeg |
| Windows·Linux·macOS에서 Java 실행 | `*-java.zip` | Java 17+, 필요하면 FFmpeg |
| 선택적 데이터베이스·가져오기 플러그인 포함 | `*-full-offline.zip` | Java 17+, 필요하면 FFmpeg |
| Docker 상시 서비스 | 저장소 Dockerfile과 완전한 Java 배포 디렉터리로 빌드 | Docker Engine, Compose, 쓰기 가능한 데이터 디렉터리 |

표준 패키지는 기본 공식 플러그인을, full-offline은 추가로 `database-postgresql`과 `pixiv-batch-downloader-import`를 포함합니다. 두 Java 패키지 모두 JRE, FFmpeg, Douyin은 포함하지 않습니다. full-offline도 Pixiv, 온라인 AI, TTS를 사용할 때는 네트워크가 필요합니다.

## 요구 사항

Java 패키지에는 Java 17 이상이 필요하고 Windows 설치판에는 JRE가 포함됩니다. 운영체제는 Windows, macOS, Linux를 지원합니다. 사용자 스크립트에는 최신 Tampermonkey가 필요합니다. 미디어 변환에는 선택한 형식의 인코더를 지원하는 FFmpeg가 필요하며 MP4에는 H.264 인코더가 필요합니다.

## Java 표준 또는 full-offline 패키지

### Java 설치

Windows는 [Adoptium](https://adoptium.net/), macOS는 `brew install openjdk@17`, Debian/Ubuntu는 `sudo apt install openjdk-17-jdk`, Fedora는 `sudo dnf install java-17-openjdk`를 사용할 수 있습니다.

```text
java -version
```

출력 버전이 17 이상인지 확인하세요.

### 압축 해제와 실행

`PixivDownload-*-java.zip` 또는 `PixivDownload-*-full-offline.zip`을 모두 압축 해제합니다. 실행 스크립트와 `plugins/`를 함께 보존하세요. 플러그인은 작업 디렉터리의 `plugins/`에서 로드합니다.

?> 단독 `PixivDownload-*.jar`는 필수 다운로드 작업대가 없는 핵심 JAR입니다. 내부 빌드 입력이며 일반 사용자용 첨부 파일이 아닙니다. 이것만 실행하면 복구 모드로 들어갑니다.

Windows는 `run.bat`, Linux/macOS는 `sh run.sh`를 실행합니다. 직접 실행할 수도 있습니다.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar
```

`-Dfile.encoding=UTF-8`을 유지하세요. 실행 스크립트에는 이미 포함되어 있으며 직접 실행에서 빠뜨리면 일부 Windows 환경에서 문자가 깨질 수 있습니다.

### 화면 없는 서버

먼저 같은 인스턴스를 중지하고 초기화합니다.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
```

이름, 비밀번호, 실행 모드 `solo|multi`, HTTP 프록시 사용 여부·호스트·포트를 입력합니다. 자동화가 필요하면 값을 모두 지정합니다. 다음 비밀번호와 프록시는 자신의 실제 값으로 바꾸세요.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

명령줄 비밀번호는 Shell 기록·프로세스 목록에 남을 수 있습니다. 프록시 인수를 생략하면 대화형으로 묻고 필요 없으면 `--proxy-enabled=false`를 사용합니다.

그다음 `--no-gui`로 실행합니다. `nohup` 예시는 Bash 전용입니다.

```bash
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
nohup java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui > app.log 2>&1 &
```

초기화 전 무화면 실행은 중지됩니다. 서버에는 GUI 안내가 없고 `setup.html`은 로컬 접근만 허용하므로 CLI 초기화를 먼저 해야 합니다. 비밀번호 변경·재설정은 `--change-password`, `--reset-password`를 사용합니다. [최초 설정](/ko/first-setup)을 참고하세요.

프록시는 호스트·작업 경로를 사용하는 Pixiv 다운로드, 온라인 업데이트, FFmpeg 다운로드, 일부 TTS 등에 적용됩니다. 브라우저, SMTP, 명시적 직접 연결 등은 다른 경로를 쓸 수 있습니다. [네트워크 안내](/ko/network-access)에서 범위를 확인하세요.

## Windows 설치 프로그램

`PixivDownload-x.x.x-win-x64-setup.exe`를 실행하고 설치 언어, 디렉터리, 선택 항목을 정합니다. FFmpeg 다운로드·설치를 선택할 수 있으며 선택하지 않아도 일반 이미지는 받을 수 있습니다. 나중에 GUI 상태 페이지에서 받을 수도 있습니다.

기본 공식 플러그인에는 필수 `download-workbench`, 기본 `gui-compose`, 대체 `gui-swing`이 포함됩니다. Douyin은 사용자 지정 저장소나 로컬 패키지로 설치하는 타사 플러그인입니다.

설치 프로그램은 쓰기 작업에 UAC를 요청하며 설치된 앱과 portable 실행 프로그램도 기본적으로 관리자 권한을 요청합니다. 호스트가 실제로 권한 상승 상태라면 `host-process-full-trust` 플러그인도 같은 권한을 가지며 플러그인 관리 화면에 경고가 계속 표시됩니다.

설치 후 자동 실행하거나 시작 메뉴·바로가기를 사용할 수 있습니다. 다시 설치 프로그램을 실행하면 프로그램 파일 복구, 구성 요소 변경, 제거를 할 수 있습니다. 실행 중인 `PixivDownload.exe`가 있으면 닫은 뒤 재시도하도록 안내합니다.

## Docker

저장소 Dockerfile은 서명된 Java 배포 디렉터리로 Java 17, FFmpeg, curl을 포함한 이미지를 만듭니다. Java 표준 또는 full-offline을 `build/dist/default-downloader/`에 완전히 압축 해제하여 바로 아래에 주 JAR, `plugins/`, `plugins-manifest.json`, `SHA256SUMS`가 있도록 한 뒤 `docker compose build`를 실행하세요.

### 디렉터리와 초기화

Docker Engine과 Compose가 필요합니다. 컨테이너는 UID/GID 10001로 실행합니다. Linux에서 새 인스턴스를 만들 때는 쓰기 가능한 디렉터리를 준비합니다.

```bash
sudo install -d -o 10001 -g 10001 config state data pixiv-download log
docker compose run --rm app --setup
```

명령은 Dockerfile과 `docker-compose.yml`이 있는 저장소 루트에서 실행합니다. 계정은 `state/setup_config.json`, 프록시는 `config/config.yaml`에 저장됩니다.

!> 초기화를 건너뛰고 실행하면 종료 코드 78과 함께 재시작을 반복할 수 있습니다. 컨테이너에서는 CLI로 먼저 초기화하세요.

### 실행과 접속

```bash
docker compose up -d
docker compose logs -f app
docker compose down
```

각각 시작, 로그 확인, 중지 명령입니다. 기본 포트는 호스트 `127.0.0.1:6999`에만 연결됩니다. 호스트 브라우저에서 `http://127.0.0.1:6999/`를 여세요. 원격 접속에는 기존 SSH 터널이나 별도 리버스 프록시를 사용합니다. 관리자로 로그인하면 플러그인 설치·조회·복구가 가능합니다.

### 프록시

컨테이너의 `127.0.0.1`은 컨테이너 자신입니다. compose는 `host.docker.internal`을 호스트 게이트웨이에 연결합니다. 초기화 때 실제 호스트 프록시 포트를 지정하세요.

```bash
docker compose run --rm app --setup --proxy-host=host.docker.internal --proxy-port=7890
```

프록시가 필요 없으면 `--proxy-enabled=false`를 사용합니다. 초기화 후 `config/config.yaml`에서 `proxy.host: host.docker.internal`을 설정하고 `docker compose restart app`으로 적용할 수도 있습니다. 백엔드 프록시 범위는 위와 같으며 Docker 이미지·APT 다운로드는 Docker 측 프록시나 미러 설정을 사용합니다.

### 영구 데이터와 업데이트

| 호스트 디렉터리 | 내용 |
| --- | --- |
| `./config/` | 실행 설정 |
| `./state/` | 로그인, 초기화, 대기열, GUI 상태 |
| `./data/` | SQLite, 아이콘, 썸네일·TTS 캐시, 보충 상태, 참조 음성 |
| `./pixiv-download/` | 작품·소설·시리즈, 임시 `_archives` |
| `./log/` | 로그 |

설치 플러그인은 `plugins` 이름 있는 볼륨에 저장됩니다. 인스턴스 잠금은 `state/instance/`, SQLite 네이티브 라이브러리 임시 추출은 `state/`를 사용합니다. 쓰기와 라이브러리 로드가 가능해야 합니다. 루트 파일 시스템은 읽기 전용이고 `/tmp`의 `noexec,nosuid,nodev`를 유지합니다.

이미지 업데이트는 기존 플러그인 볼륨을 덮어쓰지 않습니다. 재생성 후 플러그인 버전과 비활성 상태를 확인하세요. 필수 플러그인 호환 오류는 복구 모드에서 호스트에 맞는 신뢰 패키지를 설치해야 합니다. 볼륨·설정·데이터베이스·다운로드를 보존하려면 `docker compose down`에 볼륨 삭제 옵션 `-v`를 추가하지 마세요.

### 상태 검사

`GET /actuator/health`는 로그인 없이 `{"status":"UP"}`를 반환하며 내부 세부 정보는 노출하지 않습니다. `GET /actuator/info`는 이름과 버전을 반환합니다. 이미지와 compose의 상태 검사를 통해 `docker compose ps`에서 `healthy` 또는 `unhealthy`를 확인할 수 있습니다.

포트·프록시·SSL을 변경하면 설정 저장 후 재시작합니다. `server.port`를 바꾸면 compose 포트 매핑과 상태 검사 주소도 함께 바꾸세요.

## 사용자 스크립트와 FFmpeg

사용자 스크립트 없이도 다운로드 웹페이지에서 대부분의 작업을 할 수 있습니다. 설치하려면 다운로드 페이지의 스크립트 메뉴에서 설치 버튼을 누르거나 저장소에 있는 독립 `.user.js`를 Tampermonkey에 끌어다 놓으세요.

`Pixiv All-in-One.user.js`는 Page Scrape, User Batch, URL Batch, Java 백엔드 단일 작품, Toolbox를 포함합니다. `Pixiv 单作品图片下载器(Local Download).user.js`는 백엔드 없이 브라우저로 저장합니다. 설치 위치와 각 스크립트의 차이는 [사용자 스크립트](/ko/userscripts)를 참고하세요.

웹 설치 스크립트의 업데이트 확인은 현재 백엔드를 사용합니다. 다른 호스트라면 스크립트의 실제 주소를 바꾸고 새 백엔드에서 재설치해 `@connect`도 맞추세요. 권한만 바꿔도 요청 대상이 바뀌지는 않습니다. Pixiv를 새로 고치고 작품 하나가 목표 대기열에 들어오는지 확인합니다. [사용자 스크립트](/ko/userscripts)를 참고하세요.

FFmpeg는 Ugoira·미디어 변환에 사용하며 일반 이미지에는 필요하지 않습니다. Windows 설치 옵션이나 GUI 상태 페이지에서 자동 설치할 수 있습니다. 자동 설치는 공식 안정 소스로 만든 프로젝트의 `ffmpeg-stable` Release에서 Windows x64, Linux x64/arm64, macOS x64/arm64 자산을 선택합니다. 그 밖의 플랫폼은 시스템 FFmpeg를 사용하세요. 직접 설치하려면 [FFmpeg 다운로드](https://ffmpeg.org/download.html)에서 받아 실행 파일 디렉터리를 PATH에 넣고 `ffmpeg -version`으로 확인합니다.

## 디렉터리와 설치 확인

설치 파일은 `plugins/`, 설정은 `config/`, 상태는 `state/`, 데이터베이스·리소스·캐시는 `data/`, 로그는 `log/`에 있습니다. 작품 기본 위치는 `pixiv-download/`이며 컬렉션·플러그인 설정에 따라 달라집니다. 모든 디렉터리가 첫 실행에 생기는 것은 아닙니다.

업그레이드 전 정상 종료하고 영구 데이터를 백업하세요. 작품 파일만 보존하고 데이터베이스를 버리면 안 됩니다. [저장 원칙](/ko/storage)을 참고하세요.

[최초 설정](/ko/first-setup) 후 로그인하고 작품 하나를 다운로드하여 파일이 존재하고 갤러리에서 열리는지 확인합니다. 건강 상태 `UP`만으로 플러그인·Cookie·다운로드 전체가 작동함을 증명하지는 않습니다.

| 경로 | 용도 |
| --- | --- |
| `/` | 다운로드 페이지로 이동 |
| `/setup.html` | 최초 설정, 완료했다면 리다이렉트 |
| `/intro.html` | 제품 소개 |
| `/pixiv-batch.html` | 다운로드 작업대 |
| `/monitor.html` | 다운로드 모니터 |
| `/pixiv-gallery.html` | `gallery`가 제공하는 갤러리 |

기본 서버 주소는 `http://localhost:6999`입니다.

## 공식 플러그인 구성

필수 `download-workbench`는 다운로드 페이지·API·대기열·사용자 스크립트 진입점·Pixiv 이미지 프록시·예약 작업 호스트를 제공합니다. 누락, 손상, 비호환, 오프라인 재검증 실패 시 로그인·플러그인 관리·설치 복구만 제공하는 복구 경로로 들어갑니다.

기본 공식 구성은 `download-workbench`, `database-sqlite`, `gui-compose`, `gui-swing`, `gallery-tools`, `posthog`, `gallery`, `novel`, `notification`, `multi-mode-decision-survey`, `push`, `mail`, `tts`, `ai`입니다. Compose가 기본이며 Swing이 자동 대체합니다. Douyin은 공식 저장소·서명·배포판 구성에 포함되지 않습니다.

선택하지 않은 선택적 기능이 없으면 해당 페이지, API, 탐색, 정적 리소스, i18n, GUI 설정, 기능만 사라집니다. 현재 선택한 데이터베이스 공급자가 없으면 복구 모드로 들어갑니다.

- Windows 설치판은 기본 구성과 JRE를 포함하고 FFmpeg는 선택할 때 다운로드합니다.
- Java 표준은 같은 기본 구성이지만 JRE·FFmpeg·Douyin은 포함하지 않습니다.
- full-offline은 기본 구성과 `database-postgresql`, `pixiv-batch-downloader-import`를 포함하며 JRE·FFmpeg·Douyin은 포함하지 않습니다.
- `gallery-tools`가 없어도 이미지 해시 기록과 기존 해시는 유지됩니다.
- `gallery`가 없어도 다운로드 작업대·API·스크립트·이미지 프록시·예약 작업·메타데이터·다운로드 사실·해시·로컬 리소스 인덱스는 유지됩니다.
- `novel`이 없으면 소설 다운로드·갤러리·본문 저장·번역 상태·합본·예약 소설 실행기가 모두 없어지며 핵심에 별도 구현이 남지 않습니다.
