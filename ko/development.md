# 개발 가이드

## 사전 요구 사항

Java 17+, Maven, Node.js와 npm이 필요합니다. 저장소 지침과 작업별 `CLAUDE.md` 라우팅을 먼저 확인하세요.

## 멀티 모듈 구조

핵심 API와 앱은 호스트 계약을 제공하고, 다운로드 워크벤치·갤러리·소설·AI·알림 등은 독립 플러그인으로 동작합니다. 외부 플러그인은 숙주가 제공하는 API와 수명 주기 계약을 사용해야 합니다.

## 포크와 브랜치

작업별 linked worktree와 기능 브랜치를 사용하고, 기존 사용자의 변경을 덮어쓰지 마세요. 변경 범위를 작게 유지하고 관련 파일만 커밋합니다.

## 빌드, 테스트 및 실행

```bash
# 테스트 없이 모든 모듈 빌드
./mvnw -B -ntp package -DskipTests

# 전체 Maven 테스트
./mvnw -B -ntp test

# 웹 표준 및 JavaScript 테스트
npm run test:js
npm run test:web-standards
```

개발 모드에서는 `spring-boot:run` 또는 IDE의 개발 실행 구성을 사용합니다. 패키징된 앱은 필요한 외부 플러그인과 함께 실행하세요.

## 외부 플러그인 개발 모드

플러그인 JAR은 별도 작업 영역에 배치하고 개발 모드에서 로드합니다. 동일한 플러그인이나 경로를 중복 로드하면 라우트와 Bean 충돌이 발생할 수 있으므로 이전 아티팩트를 정리한 후 다시 시작하세요.

## 사용자 스크립트 리소스

공유 스니펫은 `scripts/userscript-snippets`에서 관리하고, 변경 후 동기화 검사와 `node --check`를 실행합니다. All-in-One 파일은 원본 스크립트에서 생성하며 직접 편집하지 않습니다.

## i18n 현지화 workflow

중국어 소스 bundle과 번역 bundle의 키·placeholder·properties 구조를 함께 유지합니다. 새 locale은 `locales.json`에 등록하고, 검토 후 `npm run i18n:accept -- --locale ko-KR`, `npm run i18n:generate-static`, `npm run i18n:check`를 실행합니다.

## 로컬 Windows 패키징

Inno Setup과 Java 패키징 스크립트는 서명된 플러그인 입력을 사용합니다. 로컬 unsigned 테스트 패키지는 배포하지 말고, 공식 아티팩트는 지정된 서명과 카탈로그 검증을 거쳐야 합니다.

## 커밋과 Pull Request

Conventional Commits 형식을 사용하고, 번역·문서·생성 결과를 같은 범위로 묶습니다. 테스트 결과와 남은 서명/배포 단계는 PR에 명확히 기록하세요.

## CI와 릴리스

필수 검사는 GitHub Actions에서 다시 실행됩니다. 로컬 테스트나 대체 실행기는 GitHub Actions의 required check를 대신하지 않습니다.

## 코드 경계

플러그인 소유 설정, 라우트, 정적 리소스, i18n과 데이터 저장 영역을 다른 플러그인에 복사하지 마세요. 공용 계약은 core API에 두고, 실제 기능은 소유 플러그인에 둡니다.
