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

릴리스에서 `*-java.zip` 또는 `*-full-offline.zip`을 받고 전체 압축을 해제합니다. JAR만 꺼내지 말고 `plugins/`, 실행 스크립트와 모든 파일을 함께 보존하세요.

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

## 방법 3: Docker

```bash
docker compose run --rm pixivdownload --setup
docker compose up -d
```

초기 설정에서 계정, 실행 모드와 프록시를 지정합니다. `config/`, `state/`, `data/`를 호스트 볼륨에 보존하고, 컨테이너에서 호스트 프록시에 접근할 때는 환경에 맞는 게이트웨이를 사용하세요.

## 사용자 스크립트 설치(선택)

웹 관리 페이지의 사용자 스크립트 카드에서 설치하거나 릴리스의 개별 파일을 내려받습니다. 독립 스크립트와 All-in-One을 동시에 활성화하지 마세요. 자세한 내용은 [사용자 스크립트](/ko/userscripts)를 참고하세요.

## FFmpeg 설치(선택)

상태 페이지 또는 Windows 설치 프로그램에서 자동 설치를 시도할 수 있습니다. 수동 설치 시 `ffmpeg`와 `ffprobe`를 PATH 또는 앱의 tools 디렉터리에 배치하고 라이선스를 확인하세요.

## 디렉터리 구조

- `config/`: 설정
- `state/`: 실행 상태와 초기 설정
- `data/`: 플러그인 데이터베이스와 캐시
- `plugins/`: 외부 플러그인 아티팩트
- 다운로드 루트: 작품 파일과 메타데이터

## 설치 확인

상태 페이지에서 필수 플러그인, 포트, 다운로드 루트와 FFmpeg 상태를 확인한 뒤 테스트 작품 하나를 다운로드하세요.

## 공식 플러그인과 패키지

Java 표준 패키지는 Douyin을 제외한 기본 공식 플러그인을 포함하고, 전체 오프라인 패키지는 사용자 대상 공식 플러그인을 모두 포함합니다. 플러그인 시장에서 추가 플러그인을 설치할 때는 서명과 SHA-256 검증 결과를 확인하세요.
