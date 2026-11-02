# PixivDownloader 사용 문서

PixivDownloader는 Pixiv 일러스트, 만화, 움짤과 소설을 로컬 컴퓨터나 서버에 다운로드하고 로컬 갤러리에서 관리하는 프로그램입니다. 데스크톱 UI, 브라우저 다운로드 페이지와 Tampermonkey 스크립트로 사용할 수 있습니다. 접근이 제한된 콘텐츠를 다운로드하려면 자신의 Pixiv Cookie를 설정해야 합니다.

## 처음 사용하기

1. [설치 및 실행](/ko/installation): Windows 설치 패키지, Java 배포 패키지 또는 Docker 배포를 선택하고 압축을 모두 풉니다.
2. [초기 설정](/ko/first-setup): 이 프로그램의 관리자 계정을 만들고 백엔드 프록시를 설정합니다.
3. [첫 다운로드](/ko/first-download): Cookie를 저장하고 작품 하나를 다운로드한 다음, 로컬 폴더와 갤러리에서 결과를 확인합니다.

기본 데스크톱 UI는 Compose이며 배포 패키지에는 Swing도 포함됩니다. 간체 중국어, 번체 중국어, 영어, 일본어와 한국어를 지원합니다. 기본 데이터베이스는 SQLite입니다. PostgreSQL을 사용하려면 해당 데이터베이스 플러그인을 먼저 설치한 후 설정 페이지에서 데이터를 검사하고 마이그레이션해야 합니다.

## 작업별 문서

| 작업 | 문서 |
| --- | --- |
| 내 북마크, 작품, 팔로우 새 작품 또는 컬렉션 다운로드 | [빠른 가져오기](/ko/quick-access) |
| 여러 작품 링크나 ID를 붙여넣어 다운로드 | [URL 일괄 다운로드](/ko/batch-download) |
| 지정한 작가의 작품 다운로드 | [작가 일괄 다운로드](/ko/user-download) |
| 키워드로 Pixiv를 검색하여 다운로드 | [검색 다운로드](/ko/search) |
| 소설 저장, EPUB 합본, 읽기, 번역 또는 낭독 | [소설 및 낭독](/ko/novel) |
| 새 작품을 주기적으로 찾아 자동 다운로드 | [예약 작업](/ko/scheduled-tasks) |
| Pixiv 웹페이지에서 다운로드 도우미 실행 | [사용자 스크립트](/ko/userscripts) |
| PixivBatchDownloader로 다운로드한 작품을 갤러리에 등록 | [외부 다운로드 가져오기](/ko/pixiv-batch-downloader-import) |
| 등록한 작품 탐색, 필터링, 내보내기 및 삭제 | [작품 갤러리](/ko/gallery) |
| 설정, 프록시, 데이터베이스와 미디어 형식 변경 | [설정 참조](/ko/configuration) |
| 작품과 데이터 폴더 백업, 복원 또는 이전 | [저장소 및 백업](/ko/storage) |
| 시작 인수, 파일명 템플릿, 유지 관리 도구와 게스트 초대 확인 | [CLI 및 고급 참조](/ko/usage-guide) |
| 외부 요청 대상, 전송 데이터와 비활성화 방법 확인 | [네트워크 접근 및 외부 서비스](/ko/network-access) |
| 시작, 연결, Cookie 또는 다운로드 문제 해결 | [자주 묻는 질문](/ko/faq) |
| 플러그인 설치, 업데이트, 활성화, 비활성화 또는 복구 | [플러그인 관리](/ko/plugin-management) |
| 페이지 레이아웃, 스타일과 사용자 정의 컴포넌트 편집 | [페이지 편집기](/ko/page-editor) |
| 로그인 등의 양식에 온라인 또는 오프라인 CAPTCHA 설정 | [작업 CAPTCHA](/ko/captcha) |

## 개발 및 참여

- [서드파티 플러그인 SDK](/ko/plugin-development): 템플릿으로 프로젝트를 만들고 확장 계약을 구현하며 서명하고 제출하는 방법입니다.
- [주 저장소 개발](/ko/development): 환경 준비, 빌드, 테스트와 PR 제출 방법입니다.
- [SDK 다운로드](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) · [버전별 API 참조](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)

## 버전 및 도움 요청

배포 패키지는 [GitHub Releases](https://github.com/Sywyar/PixivDownloader/releases)에서 다운로드하고 변경 사항은 [CHANGELOG](https://github.com/Sywyar/PixivDownloader/blob/master/CHANGELOG.md)에서 확인할 수 있습니다. 문제 해결 절차를 수행해도 해결되지 않으면 [Issues](https://github.com/Sywyar/PixivDownloader/issues)에 버전, 재현 단계, 실제 결과와 민감 정보를 제거한 로그를 제출하세요. Cookie, 비밀번호나 토큰은 포함하지 마세요.

기본 Web 주소는 `http://localhost:6999/`입니다. 사용할 수 있는 페이지는 활성화된 플러그인에 따라 달라집니다. 복구 페이지가 표시되면 먼저 필수 플러그인과 선택한 데이터베이스 상태를 확인하세요.

이 프로젝트는 [Sywyar](https://github.com/Sywyar)가 관리하며 [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) 및 동봉된 [플러그인 링크 예외](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)를 적용합니다. 다운로드한 작품의 저작권은 원작자에게 있으며 이 프로젝트는 Pixiv와 관계가 없습니다. 서비스 약관을 준수하고 요청 빈도를 적절히 조절하세요.
