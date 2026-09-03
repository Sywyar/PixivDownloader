# 설치 가이드

## 요구 사항

Java 패키지에는 Java 17 이상이 필요합니다. Windows 설치 프로그램은 포함된 런타임을 사용할 수 있으며, Docker는 Docker Engine과 Compose를 필요로 합니다.

## 방법 1: Java 표준/전체 오프라인 패키지

### 1. Java 설치

```bash
java -version
```

Java 17 이상이 출력되는지 확인하세요.

### 2. 다운로드 및 압축 해제

릴리스에서 `*-java.zip` 또는 `*-full-offline.zip`을 받으세요. 두 패키지는 Windows 설치 프로그램과 동일한 공식 플러그인 배포 세트를 포함하며 Douyin은 포함하지 않습니다. 전체 압축을 해제하고 `plugins/`, 실행 스크립트와 모든 파일을 함께 보존하세요.

?> 단독 `PixivDownload-*.jar`는 필수 `download-workbench`가 없는 코어 셸입니다. 내부 빌드 입력일 뿐 일반 사용자용 첨부 파일이 아니며, 직접 실행하면 복구/수리 모드로 진입합니다.

### 3. 실행

```bash
# Windows
run.bat

# Linux/macOS
sh run.sh
```

### 4. 백그라운드 실행

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui
nohup sh run.sh > app.log 2>&1 &
```

## 방법 2: Windows 설치 프로그램

최신 `PixivDownload-*-win-x64-setup.exe`를 실행하고 언어, 설치 폴더와 선택적 구성 요소를 선택합니다. 설치가 끝나면 시작 메뉴에서 앱을 실행하세요. 유지보수 모드에서는 복구, 변경과 제거를 선택할 수 있습니다.

설치 프로그램이 애플리케이션 디렉터리에 쓸 때 UAC를 요청합니다. 설치된 앱과 portable 실행 프로그램도 기본적으로 관리자 권한을 요청합니다. 호스트가 실제로 승격된 경우 `host-process-full-trust` 플러그인은 같은 권한을 상속하며 플러그인 관리 페이지에 경고가 계속 표시됩니다.

## 방법 3: Docker

```bash
docker compose run --rm pixivdownload --setup
docker compose up -d
```

초기 설정에서 계정, 실행 모드와 프록시를 지정합니다. `config/`, `state/`, `data/`를 호스트 볼륨에 보존하고, 컨테이너에서 호스트 프록시에 접근할 때는 환경에 맞는 게이트웨이를 사용하세요.

## 사용자 스크립트 설치(선택)

웹 관리 페이지의 사용자 스크립트 카드에서 설치하거나 릴리스의 개별 파일을 내려받습니다. 독립 스크립트와 All-in-One을 동시에 활성화하지 마세요. 자세한 내용은 [사용자 스크립트](/ko/userscripts)를 참고하세요.

## FFmpeg 설치(선택)

상태 페이지 또는 Windows 설치 프로그램의 자동 설치는 FFmpeg 공식 최신 안정 소스로 빌드한 프로젝트 관리 `ffmpeg-stable` Release를 사용합니다. Windows x64, Linux x64/arm64, macOS x64/arm64용 자산을 자동으로 선택합니다. 다른 플랫폼에서는 시스템 FFmpeg를 설치하고 `ffmpeg`와 `ffprobe`를 PATH 또는 앱의 tools 디렉터리에 배치하세요.

## 디렉터리 구조

- `config/`: 설정
- `state/`: 실행 상태와 초기 설정
- `data/`: 플러그인 데이터베이스와 캐시
- `plugins/`: 외부 플러그인 아티팩트
- 다운로드 루트: 작품 파일과 메타데이터

## 설치 확인

상태 페이지에서 필수 플러그인, 포트, 다운로드 루트와 FFmpeg 상태를 확인한 뒤 테스트 작품 하나를 다운로드하세요.

## 공식 플러그인과 패키지

Windows 설치 프로그램, Java 표준 패키지와 full-offline 패키지는 모두 `download-workbench`, `gui-compose`, `gui-swing`, `gallery-tools`, `posthog`, `gallery`, `novel`, `notification`, `multi-mode-decision-survey`, `push`, `mail`, `tts`, `ai`로 구성된 같은 공식 플러그인 배포 세트를 사용합니다. `gui-compose`가 기본 데스크톱 UI이고 `gui-swing`은 자동 대체 provider입니다. Douyin은 공식 저장소, 서명 또는 릴리스 패키지로 배포되지 않으므로 사용자 지정 저장소나 로컬 패키지에서 설치해야 합니다.
