# 설치 및 실행

[Releases](https://github.com/Sywyar/PixivDownloader/releases)에서 환경에 맞는 배포 패키지를 선택하세요. 아래 파일명의 `*` 또는 `x.x.x`는 버전 번호를 뜻합니다. 명령의 `APP.jar`는 실제 주 프로그램 JAR 이름으로 바꾸고 압축을 모두 푼 폴더에서 실행하세요.

| 사용 환경 | 배포 패키지 | 추가 준비 |
| --- | --- | --- |
| Windows x64 데스크톱 | `*-win-x64-setup.exe` | JRE 내장. 필요하면 FFmpeg 설치 |
| Windows / Linux / macOS에서 Java 실행 | `*-java.zip` | Java 25+. 필요하면 FFmpeg 설치 |
| 선택적 데이터베이스, 가져오기, CAPTCHA 등 공식 플러그인 사전 설치 | `*-full-offline.zip` | Java 25+. 필요하면 FFmpeg 설치 |
| Docker 상시 서비스 | 저장소의 `Dockerfile`과 완전한 Java 배포 폴더로 빌드 | Docker Engine, Compose, 쓰기 가능한 마운트 폴더 |

Java 표준 패키지와 Windows 설치 패키지에는 기본 공식 플러그인 모음이 포함됩니다. 오프라인 전체 패키지에는 `database-postgresql`, `pixiv-batch-downloader-import` 등 필요에 따라 사용하는 공식 플러그인도 포함됩니다. 두 Java 패키지 모두 JRE, FFmpeg와 서드파티 `douyin` 플러그인은 포함하지 않습니다. '오프라인 전체'는 선택적 공식 플러그인이 미리 포함되었다는 뜻이며 Pixiv, 온라인 AI나 온라인 TTS에 접근하려면 여전히 네트워크가 필요합니다.

## 방법 1: Java 표준 패키지 / 오프라인 전체 패키지

1. Java 25를 설치합니다. [Adoptium](https://adoptium.net/)에서 운영 체제와 아키텍처에 맞는 패키지를 선택한 뒤 버전을 확인하세요.

   ```bash
   java -version
   ```

2. `PixivDownload-*-java.zip` 또는 `PixivDownload-*-full-offline.zip`을 다운로드하여 **압축을 모두 풉니다**. 같은 폴더의 시작 스크립트와 `plugins/`를 유지해야 합니다. 프로그램은 시작할 때 작업 폴더의 `plugins/`에서 외부 플러그인을 로드합니다.

   ?> 단독 `PixivDownload-*.jar`는 외부 플러그인이 없는 코어 셸 빌드 입력이며 일반 사용자용 배포 패키지가 아닙니다. 직접 실행해도 다운로드 작업 화면이나 데이터베이스 플러그인이 사전 설치되지 않습니다.

3. 프로그램을 시작합니다. Windows에서는 `run.bat`, Linux / macOS에서는 압축을 푼 폴더에서 `sh run.sh`를 실행하거나 다음 명령으로 직접 실행합니다.

   ```text
   java -Dfile.encoding=UTF-8 -jar APP.jar
   ```

   시작 스크립트에는 `-Dfile.encoding=UTF-8`이 포함되어 있습니다. 수동 실행 시에도 이 인수를 유지하여 중국어 Windows 콘솔의 문자 깨짐을 방지하세요.

4. [초기 설정](/ko/first-setup)에 따라 관리자 계정을 만들고 프록시를 설정합니다.

### 데스크톱이 없는 서버(`--no-gui`)

서버에는 데스크톱 안내가 없고 `setup.html`은 로컬 접근만 허용합니다. 초기화하지 않고 `--no-gui`로 시작하면 종료 코드 `78`로 종료됩니다. 처음 시작하기 전에 같은 폴더에서 CLI 초기화를 실행하세요.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

자동화 스크립트에서 비대화형 초기화가 필요하면 인수를 명시적으로 전달할 수 있습니다. 비밀번호와 프록시는 실제 값으로 바꾸세요. 명령줄 비밀번호는 Shell 기록이나 프로세스 목록에 남으므로 일상적인 작업에서는 대화형 모드를 우선 사용하세요.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

프록시가 필요 없으면 `--proxy-enabled=false`로 바꿉니다. Linux / macOS 백그라운드 실행 예시:

```bash
nohup java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui > app.log 2>&1 &
```

백엔드 프록시는 호스트 또는 작업 라우팅을 사용하는 요청(Pixiv 다운로드, 온라인 업데이트, FFmpeg 다운로드와 일부 온라인 TTS)에 적용됩니다. 브라우저 직접 연결, SMTP와 데이터베이스 연결은 각각의 경로를 사용합니다. 자세한 내용은 [네트워크 접근 및 외부 서비스](/ko/network-access)를 참고하세요.

## 방법 2: Windows 설치 패키지

1. `PixivDownload-x.x.x-win-x64-setup.exe`를 실행하고 설치 언어(간체 중국어, 번체 중국어, 영어, 일본어, 한국어 지원)와 설치 폴더를 선택합니다.
2. 필요하면 'FFmpeg 다운로드 및 설치'를 선택합니다. 일반 정적 이미지에는 FFmpeg가 필요하지 않으며 설치 후 데스크톱의 '상태' 페이지에서도 다운로드할 수 있습니다.
3. 설치 완료 후 프로그램을 시작하거나 시작 메뉴 또는 바탕 화면 바로가기로 실행합니다.

설치 전에 `PixivDownload.exe`가 실행 중인 것으로 감지되면 설치 프로그램이 먼저 종료한 후 다시 시도하라고 안내합니다. 설치 프로그램을 다시 실행하면 복구, 구성 요소 변경이나 제거를 수행할 수 있습니다. 설치 프로그램, 설치된 애플리케이션과 포터블 실행기는 기본적으로 관리자 권한을 요청합니다. 호스트가 실제로 높은 권한으로 실행되면 `host-process-full-trust` 플러그인도 같은 권한을 상속하며 플러그인 관리 페이지에 이 상태가 계속 표시됩니다.

## 방법 3: Docker 배포

저장소의 `Dockerfile`은 서명된 완전한 Java 배포 폴더로 이미지를 빌드합니다. 이미지에는 Java 25, FFmpeg와 curl이 포함됩니다.

1. `*-java.zip` 또는 `*-full-offline.zip`의 압축을 저장소의 `build/dist/default-downloader/`에 모두 풉니다. 해당 폴더 바로 아래에 주 프로그램 JAR, `plugins/`, `plugins-manifest.json`과 `SHA256SUMS`가 있는지 확인하고 이미지를 빌드합니다.

   ```bash
   docker compose build
   ```

2. 컨테이너는 UID / GID `10001`로 실행됩니다. Linux에 새로 배포할 때는 먼저 `docker-compose.yml`이 있는 폴더에서 쓰기 가능한 마운트 폴더를 만듭니다.

   ```bash
   sudo install -d -o 10001 -g 10001 config state data pixiv-download log
   ```

3. 초기 설정을 실행한 후 상시 컨테이너를 시작합니다.

   ```bash
   docker compose run --rm app --setup --mode=solo
   docker compose up -d
   docker compose logs -f app
   ```

!> `--setup`을 완료하지 않고 `docker compose up -d`를 바로 실행하면 종료 코드 `78`로 반복 재시작합니다.

기본 포트는 호스트의 `127.0.0.1:6999`에만 바인딩됩니다. 로컬 브라우저에서 `http://127.0.0.1:6999/`을 여세요. 원격 접근에는 SSH 터널이나 리버스 프록시를 사용하세요. 서비스 중지는 `docker compose down`으로 수행하며 데이터를 보존하려면 볼륨을 삭제하는 `-v`를 붙이지 마세요.

### 컨테이너 프록시 및 영구 저장 폴더

컨테이너의 `127.0.0.1`은 컨테이너 자체를 가리킵니다. `docker-compose.yml`은 `host.docker.internal`을 호스트 게이트웨이에 매핑합니다. 호스트 프록시를 재사용하려면 `--setup` 또는 `config/config.yaml`에서 프록시 호스트를 `host.docker.internal`로 설정하고 호스트 프록시가 컨테이너 네트워크 대역의 연결을 허용하는지 확인하세요.

```bash
docker compose run --rm app --setup --mode=solo --proxy-host=host.docker.internal --proxy-port=7890
```

마운트한 `config/config.yaml`을 수정하면 `docker compose restart app`을 실행해야 적용됩니다. `server.port`를 바꾸면 `docker-compose.yml`의 포트 매핑과 상태 검사 주소도 함께 바꿔야 합니다.

| 호스트 마운트 또는 볼륨 | 영구 저장 내용 |
| --- | --- |
| `./config/` | `config.yaml`, 플러그인 업무 설정과 암호화된 자격 증명 |
| `./state/` | 로그인 상태, `setup_config.json`, 대기열 체크포인트, 인스턴스 잠금과 SQLite 네이티브 라이브러리 압축 해제 폴더 |
| `./data/` | SQLite 데이터베이스, 컬렉션 아이콘, 썸네일과 TTS 캐시, 데이터 보완 상태, 참조 음성 |
| `./pixiv-download/` | 다운로드한 일러스트, 만화, 움짤, 소설과 임시 아카이브 `_archives` |
| `./log/` | 실행 로그 |
| 이름 있는 볼륨 `plugins` | 설치한 플러그인과 provenance |

`state/`와 `state/instance/`는 쓰기 가능하고 SQLite 네이티브 라이브러리 로드를 허용해야 합니다. 컨테이너 루트 파일 시스템은 읽기 전용으로, `/tmp`는 `noexec,nosuid,nodev`로 유지합니다. 이미지를 업데이트해도 기존 `plugins` 볼륨을 자동으로 덮어쓰지 않습니다. 컨테이너를 다시 만든 후 플러그인 관리 페이지에서 플러그인 버전과 호환 상태를 확인하세요.

## FFmpeg 및 사용자 스크립트(선택 사항)

- **FFmpeg**: 움짤(Ugoira)을 WebP / GIF / APNG / MP4로 변환하거나 미디어를 처리하려면 FFmpeg가 필요합니다. MP4에는 H.264 인코더가 필요합니다. Windows 설치 시 선택하거나 데스크톱 GUI의 '상태' 페이지에서 'FFmpeg 다운로드'를 누를 수 있습니다. 프로젝트의 `ffmpeg-stable` Release에서 서명을 검증한 Windows x64, Linux x64/arm64, macOS x64/arm64 자산을 가져옵니다. [FFmpeg 공식 사이트](https://ffmpeg.org/download.html)에서 설치하여 시스템 `PATH`에 추가하고 `ffmpeg -version`으로 확인할 수도 있습니다.
- **사용자 스크립트**: 설치하지 않아도 `pixiv-batch.html`에서 일괄 다운로드할 수 있습니다. Pixiv 페이지에서 직접 작업하려면 백엔드 실행 후 다운로드 페이지의 '사용자 스크립트'에서 설치하거나 소스 저장소의 개별 `.user.js`를 설치하세요. 다른 컴퓨터에 배포한 경우 스크립트의 백엔드 주소와 `@connect`를 함께 설정해야 합니다. [사용자 스크립트](/ko/userscripts)를 참고하세요.

## 설치 확인 및 플러그인 안내

[초기 설정](/ko/first-setup) 완료 후 `http://localhost:6999/`에 로그인하고 [첫 다운로드](/ko/first-download)에 따라 작품 하나를 다운로드하여 디스크에 파일이 생성되고 갤러리에서 작품이 열리는지 확인하세요. `GET /actuator/health`의 `{"status":"UP"}` 응답은 HTTP 서비스가 시작되었다는 뜻일 뿐 플러그인, 데이터베이스, 프록시와 Pixiv Cookie가 모두 준비되었다는 뜻은 아닙니다.

자주 사용하는 주소:

- `http://localhost:6999/`: 활성 다운로드 페이지로 기본 이동
- `http://localhost:6999/setup.html`: 로컬 초기 설정 페이지(초기화 후 자동 리디렉션)
- `http://localhost:6999/pixiv-batch.html`: 다운로드 작업 화면(기본 설치된 `download-workbench` 제공)
- `http://localhost:6999/pixiv-gallery.html`: 작품 갤러리(기본 설치된 `gallery` 제공)
- `http://localhost:6999/monitor.html`: 다운로드 모니터

기본 설치 공식 플러그인에는 `download-workbench`, `database-sqlite`, `gui-compose`(기본 데스크톱 UI), `gui-swing`(대체 데스크톱 UI), `gallery`, `gallery-tools`, `novel`, `notification`, `push`, `mail`, `tts`, `ai`, `posthog`, `multi-mode-decision-survey` 등이 있습니다. `database-postgresql`, `pixiv-batch-downloader-import`, `page-editor`, `captcha`, `translation` 등은 필요에 따라 플러그인 마켓이나 오프라인 전체 패키지에서 받을 수 있습니다. 현재 선택한 주 데이터베이스 플러그인을 사용할 수 없으면 복구 모드로 들어가 로그인, 플러그인 관리와 복구 기능만 열립니다. 선택적 플러그인이 없거나 비활성화되면 해당 페이지와 기능만 철회됩니다. [플러그인 관리](/ko/plugin-management)와 [저장소 및 백업](/ko/storage)을 참고하세요.
