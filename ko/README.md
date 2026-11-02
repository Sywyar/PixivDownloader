# PixivDownloader 문서

PixivDownloader는 Pixiv 일러스트, 만화, Ugoira, 소설을 자신의 컴퓨터나 서버에 다운로드하고 로컬 갤러리에서 정리하는 도구입니다. 데스크톱 화면, 브라우저 다운로드 페이지, Tampermonkey 스크립트로 사용할 수 있습니다. 로그인이 필요한 작품에는 자신의 Pixiv Cookie를 등록해야 합니다.

## 처음 사용하는 경우

1. [설치와 실행](/ko/installation): Windows 설치 프로그램, Java 배포 패키지 또는 Docker를 선택합니다.
2. [최초 설정](/ko/first-setup): 이 프로그램의 관리자 계정을 만들고 백엔드 프록시를 설정합니다.
3. [첫 다운로드](/ko/first-download): Cookie를 저장하고 작품을 추가한 뒤 파일과 갤러리 기록을 확인합니다.

기본 데스크톱 화면은 Compose이며 배포 패키지에 Swing도 포함됩니다. 간체 중국어, 번체 중국어, 영어, 일본어, 한국어를 지원합니다. 기본 데이터베이스는 SQLite입니다. PostgreSQL을 사용하려면 해당 데이터베이스 플러그인을 설치한 뒤 설정에서 데이터 확인과 마이그레이션을 진행하세요.

## 하려는 작업으로 찾기

| 작업 | 문서 |
| --- | --- |
| 내 북마크, 팔로우한 작가의 신작, 컬렉션 다운로드 | [빠른 가져오기](/ko/quick-access) |
| 작품 링크나 ID 목록 다운로드 | [URL 일괄 다운로드](/ko/batch-download) |
| 특정 작가의 작품 다운로드 | [작가 일괄 다운로드](/ko/user-download) |
| Pixiv 검색 결과에서 작품 선택 | [검색 다운로드](/ko/search) |
| 소설 저장, EPUB 합본, 읽기, 번역, 듣기 | [소설 다운로드](/ko/novel) |
| 로컬 작품 탐색, 필터, 내보내기, 삭제 | [작품 갤러리](/ko/gallery) |
| 작가, 검색 결과, 시리즈 정기 확인 | [예약 작업](/ko/scheduled-tasks) |
| Pixiv 웹페이지의 다운로드 보조 기능 | [사용자 스크립트](/ko/userscripts) |
| PixivBatchDownloader의 새 다운로드를 갤러리에 등록 | [가져오기 지원](/ko/pixiv-batch-downloader-import) |
| 프록시, 데이터베이스, 미디어 형식 등 설정 변경 | [설정](/ko/configuration) |
| 플러그인 설치, 활성화, 중지, 복구 | [플러그인 관리](/ko/plugin-management) |
| 네트워크 요청, 전송 데이터, 비활성화 방법 확인 | [네트워크 액세스 및 타사 서비스](/ko/network-access) |
| 백업, 복원, 작품 디렉터리 이동 | [저장 원칙](/ko/storage) |
| 실행 인수, 파일명 템플릿, 유지 관리, 게스트 초대 | [고급 사용 안내](/ko/usage-guide) |
| 실행, 연결, 다운로드 문제 해결 | [FAQ](/ko/faq) |

## 플러그인 개발과 코드 기여

- [타사 플러그인 SDK](/ko/plugin-development): SDK 선택, 프로젝트 생성, 확장 계약, 서명과 제출.
- [주 저장소 개발 안내](/ko/development): 환경 준비, 빌드, 테스트와 PR.
- [SDK 다운로드](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases)와 [버전별 API 문서](https://sywyar.github.io/PixivDownloader-Plugin-SDK/).

## 버전과 도움말

프로그램은 [GitHub Releases](https://github.com/Sywyar/PixivDownloader/releases)에서 받고, 변경 사항은 [CHANGELOG](https://github.com/Sywyar/PixivDownloader/blob/master/CHANGELOG.md)에서 확인하세요. 문제를 해결하지 못했다면 [Issues](https://github.com/Sywyar/PixivDownloader/issues)에 버전, 재현 단계, 실제 결과, 자격 증명을 제거한 로그를 첨부하세요.

기본 웹 주소는 `http://localhost:6999/`입니다. 사용 가능한 페이지는 활성화된 플러그인에 따라 달라집니다. 복구 페이지가 표시되면 필수 플러그인과 데이터베이스 상태를 먼저 확인하세요.

프로젝트는 [Sywyar](https://github.com/Sywyar)가 관리합니다. 소프트웨어 라이선스는 [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)과 [플러그인 링크 예외](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)를 참고하세요. 다운로드한 콘텐츠의 저작권은 각 권리자에게 있습니다. 이 프로젝트는 Pixiv와 제휴 관계가 없습니다. 콘텐츠 라이선스와 서비스 규칙을 지키고 요청 빈도를 적절히 조절하며 Cookie를 안전하게 보관하세요.
