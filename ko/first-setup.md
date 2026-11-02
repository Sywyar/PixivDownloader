# 초기 설정

초기 설정에서는 PixivDownloader 자체의 관리자 계정을 만들고 백엔드가 외부 서비스에 접근할 때 사용할 HTTP 프록시를 설정합니다. Pixiv 로그인 Cookie는 다운로드 페이지에 들어간 후 별도로 저장합니다.

[설치 및 실행](/ko/installation)을 마친 후 실행 환경에 맞는 초기화 방법을 선택하세요.

| 실행 환경 | 초기화 방법 | 초기화 후 작업 |
| --- | --- | --- |
| 로컬 데스크톱 | 데스크톱 초기 안내 또는 로컬 브라우저에서 `http://localhost:6999/setup.html` 접속 | 다운로드 페이지를 열고 로그인 |
| 데스크톱이 없는 서버(`--no-gui`) | 먼저 `--setup` 실행 | 이후 `--no-gui`로 시작 |
| Docker | 먼저 `docker compose run --rm app --setup` 실행 | 이후 `docker compose up -d` 실행 |

## 데스크톱 또는 로컬 브라우저에서 초기화

1. 프로그램을 시작하고 백엔드가 준비될 때까지 기다립니다. 필수 플러그인이 없거나 데이터베이스를 사용할 수 없다는 메시지가 표시되면 먼저 [플러그인 관리](/ko/plugin-management)에 따라 복구하세요. 계정을 다시 초기화해도 플러그인이나 데이터베이스 문제는 해결되지 않습니다.
2. 관리자 사용자 이름과 비밀번호를 설정합니다. 비밀번호는 8자 이상이어야 하며 12자 이상을 권장합니다. 8~11자 비밀번호는 추가 확인이 필요합니다.
3. 실제 네트워크 환경에 맞게 HTTP 프록시 호스트와 포트를 입력합니다. 로컬 프록시라면 `127.0.0.1`과 `7890` 등이 해당합니다. 대상 서비스에 직접 연결할 수 있으면 프록시를 끌 수 있습니다.
4. 저장한 후 `http://localhost:6999/pixiv-batch.html`에 접속하여 방금 만든 관리자 계정으로 로그인합니다.

데스크톱과 브라우저 초기 설정 화면은 개인용 모드(`solo`)를 고정으로 사용합니다. `setup.html`은 로컬 루프백 접근만 허용합니다. 원격 서버나 컨테이너에서는 아래 CLI 초기화를 사용하세요. 이후 일반 설정은 데스크톱의 '설정' 페이지에서 저장하세요. 프록시와 각 플러그인 네트워크 경로의 적용 범위는 [네트워크 접근 및 외부 서비스](/ko/network-access)를 참고하세요.

## 명령줄 초기화(서버 / 헤드리스 모드)

압축을 모두 푼 배포 폴더로 이동하고 같은 데이터 폴더를 사용하는 인스턴스가 실행 중이지 않은지 확인합니다. 아래 `APP.jar`는 실제 주 프로그램 JAR 이름으로 바꾸세요.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --mode=solo
```

안내에 따라 관리자 사용자 이름, 비밀번호와 프록시 설정을 입력합니다. 계정 상태는 `state/setup_config.json`에, 프록시 설정은 `config/config.yaml`에 기록됩니다. 완료 후 헤드리스 서비스를 시작합니다.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

자동화 스크립트에서는 모든 인수를 한 번에 전달할 수 있습니다. 비밀번호와 프록시는 실제 값으로 바꾸세요. 명령줄 인수는 Shell 기록과 프로세스 목록에 표시됩니다.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

프록시가 필요 없으면 `--proxy-enabled=false`를 사용합니다. CLI는 `--mode=solo`와 `--mode=multi`를 계속 지원합니다. 게스트에게 읽기 전용으로 공유하려면 [고급 참조의 게스트 초대](/ko/usage-guide#게스트-초대)를 우선 사용하세요.

## Docker 초기화

[Docker 설치 안내](/ko/installation#방법-3-docker-배포)에 따라 이미지와 마운트 폴더를 준비한 후 `docker-compose.yml`이 있는 폴더에서 실행합니다.

```bash
docker compose run --rm app --setup --mode=solo
docker compose up -d
docker compose logs -f app
```

컨테이너 안의 `127.0.0.1`은 컨테이너 자체를 가리킵니다. 호스트의 프록시를 재사용하려면 프록시 호스트에 `host.docker.internal`을 입력하고 호스트 프록시가 이 연결을 허용하는지 확인하세요. `--setup`을 완료하지 않고 헤드리스 서비스를 시작하면 종료 코드 `78`로 종료됩니다.

## 결과 확인 및 비밀번호 변경

관리자 계정으로 다운로드 페이지에 로그인하여 작업 화면이 정상적으로 열리는지 확인한 후 [첫 다운로드](/ko/first-download)에 따라 다운로드를 테스트하세요. 저장이나 시작에 실패하면 오류 내용을 보존하고 `log/latest.log`, 폴더 쓰기 권한, 포트 사용 여부와 데이터베이스 상태를 확인하세요. 초기화 파일을 반복해서 삭제하며 문제를 해결하려 하지 마세요.

실행 중인 인스턴스를 중지한 후 배포 폴더에서 관리자 비밀번호를 변경하거나 재설정할 수 있습니다.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --change-password
java -Dfile.encoding=UTF-8 -jar APP.jar --reset-password
```

`--change-password`는 기존 비밀번호 확인이 필요하며, `--reset-password`는 비밀번호를 잊었을 때 로컬에서 강제로 재설정하는 데 사용합니다. 성공하면 기존 로그인 세션이 모두 무효화되며 작품 파일과 데이터베이스에는 영향이 없습니다. 초기화 상태를 실제로 다시 만들어야 할 때만 프로그램을 중지하고 백업한 후 `state/setup_config.json`을 다른 곳으로 옮기고 `--setup`을 다시 실행하세요.
