# PixivDownloader

[简体中文](./README.md) | [繁體中文](./README_zh-Hant.md) | [日本語](./README_ja.md) | 한국어 | [English](./README_en.md)

> [!NOTE]
> 이 문서에서 "작품"은 일러스트, 만화, 우고이라 및 소설을 포함합니다.

### 소설, 만화 및 기타 작품 유형을 지원하는 Pixiv 로컬 일괄 다운로드 도구

- 작품 링크로 작품 일괄 다운로드
- 사용자 ID로 작품 일괄 다운로드
- 내장 검색 프록시를 통한 작품 일괄 다운로드
- 시리즈 링크 또는 해당 시리즈의 작품 링크를 입력하여 시리즈 전체 다운로드
- Tampermonkey 사용자 스크립트로 Pixiv 페이지에서 일러스트, 만화, 우고이라 및 소설을 수집하거나 단일 작품 페이지에서 직접 다운로드
- 강력한 작품 및 소설 갤러리

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![GitHub Repo stars](https://img.shields.io/github/stars/Sywyar/PixivDownloader)](https://github.com/Sywyar/PixivDownloader/stargazers)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/Sywyar/PixivDownloader)](../../releases)

## 기능

> [!WARNING]
> `*` 표시가 있는 항목은 안정 버전에서 아직 제공되지 않으며 nightly 빌드에서만 사용할 수 있습니다.

- 원스톱 다운로드 웹 페이지: 빠른 가져오기, 단일 작품 일괄 가져오기, 사용자 모드, 검색 모드, 시리즈 모드
- 빠른 가져오기: 저장된 Cookie로 내 북마크(일러스트/소설 및 비공개 항목 포함), 내 작품(비공개 항목 포함), 팔로잉 목록 및 컬렉션을 한 번에 불러오고 다운로드 대기열에 추가
- 페이지 일괄 다운로드 사용자 스크립트 — 검색 결과, 팔로잉 피드, 랭킹 등에서 작품 수집
- 사용 환경 향상 도구 상자(다운로드 작품 표시, Cookie 원클릭 가져오기)
- 검색 범위 선택, 필터링, 정렬 및 컬렉션을 지원하는 강력한 작품 및 소설 갤러리
- 소설 갤러리의 전문 "본문" 검색(로컬 전문 색인 기반, 연령 등급/태그/작가 필터와 함께 사용 가능)
- 통계 대시보드: 개요 카드, 월별 다운로드 선 그래프, 다운로드 수 기준 상위 작가, 인기 태그 클라우드; 작가와 태그를 클릭하여 필터링된 갤러리 보기로 이동
- 중복 의심 작품 감지: 지각 해시(dHash)로 실질적으로 중복된 다운로드 이미지를 식별하며, 임계값 조정, 작품 간/전체 범위 전환 및 수동 스캔 보충을 지원
- `*` 플러그인 관리 페이지: 모든 플러그인의 상태, 출처, 버전 및 의존성을 보여 주는 카드 목록; 외부 플러그인의 수명 주기 작업(아직 출시되지 않음)
- `*` 플러그인 마켓: 신뢰한 저장소의 플러그인을 탐색·검색·페이지 조회하고 설치합니다. 공개 HTTPS `repository.json`을 입력한 뒤 게시자, 연결 호스트와 공개 키 전체 지문을 확인하여 타사 저장소를 저장할 수 있습니다. 설치 전에는 버전을 다시 조회하고 크기, SHA-256, 서명과 패키지 descriptor를 검증합니다
- 예약 작업: 고정 주기 또는 cron 일정으로 백그라운드에서 새 작품을 자동으로 검색하고 다운로드하며 세 가지 소스 유형 지원
- 이메일/푸시 알림: 수동 확인이 필요한 이벤트를 이메일 및 푸시 채널로 전달하고 알림 유형별로 활성화 여부 설정
- 소설 다운로드 및 시리즈 통합(TXT/HTML/EPUB, 다단계 목차 및 삽입 이미지 지원)
- 소설 AI 번역(LLM 설정 필요): 소설 또는 전체 시리즈를 선택한 언어로 번역하여 로컬에 저장하고, 원문과 번역문을 전환하여 보기
- 소설 AI 다중 화자 내레이션(베타): LLM이 문장의 화자를 식별하고 각 인물을 고정 음성으로 합성하여 재생하며, 따라 읽기 강조 표시를 제공; 분석 결과는 캐시되어 다시 재생 가능

- 애니메이션 이미지(Ugoira) 자동 WebP 변환
- 사용자 지정 파일 이름 템플릿(11개 변수)
- 다운로드 상태 검증: 오래된 DB 기록 자동 정리; 디스크에서 누락된 기록을 복원하여 재다운로드를 건너뜀
- 다중 사용자 시나리오를 위한 할당량 및 속도 제한
- 게스트 초대 시스템(연령 등급/태그/작가 허용 목록)
- 다국어/다크 모드
- 온라인 업데이트를 지원하는 데스크톱 GUI(Swing + FlatLaf)

## 스크린샷

> [!NOTE]
> 일부 스크린샷 기기는 HDR이 활성화되어 있어 색상 효과가 다르게 보일 수 있습니다.

### [라이트 모드 스크린샷](./en-US/md/light-screenshot.md)

### [다크 모드 스크린샷](./en-US/md/dark-screenshot.md)

## 빠른 시작

### 다운로드

[릴리스](../../releases)에서 최신 버전을 다운로드하세요.

| 유형 | 설명 |
|---|---|
| `PixivDownload-*-win-x64-setup.exe` | Windows 설치 프로그램. 복구/변경/제거와 선택적 FFmpeg 설치를 지원하며 Douyin을 제외한 모든 공식 플러그인을 사전 설치합니다. |
| `PixivDownload-*-java.zip` | Java 표준 패키지(크로스 플랫폼). Java 17이 필요하며 Windows 설치 프로그램과 동일한 기본 플러그인 구성을 사용하고 Douyin은 포함하지 않습니다. |
| `PixivDownload-*-full-offline.zip` | 전체 오프라인 패키지(크로스 플랫폼). Java 17이 필요하며 Douyin을 포함한 모든 사용자 대상 공식 플러그인을 포함합니다. |

> 코어 셸 `PixivDownload-*.jar`는 내부 빌드 입력일 뿐 일반 사용자에게 첨부 파일로 제공되지 않습니다. 단독 실행하면 필수 외부 플러그인 `download-workbench`가 없어 복구/수리 모드로 진입합니다.

Java 표준 패키지와 전체 오프라인 패키지는 사용 전에 **전체 압축을 해제**해야 합니다. JAR 파일만 꺼내지 마세요. 시작 시 외부 공식 플러그인이 작업 디렉터리의 `plugins/` 폴더에서 로드되므로 실행 스크립트와 `plugins/` 디렉터리가 모두 필요합니다.

### 실행

```bash
# Windows 설치 프로그램
PixivDownload.exe

# Java 표준/전체 오프라인 패키지(Windows)
run.bat

# Java 표준/전체 오프라인 패키지(Linux/macOS, Java 17 필요)
sh run.sh

# 선택적 인수
--no-gui    # GUI를 비활성화하고 CLI 전용 모드로 실행(서버/Docker)
--intro     # 시작 시 제품 소개 페이지 열기
```

처음 시작한 후 마법사를 따라 설정을 완료하고 `http://localhost:6999/pixiv-batch.html`에 접속하여 다운로드를 시작하세요.

### 백엔드 설정 프록시를 통해 웹에서 Pixiv 접속(시스템 프록시 불필요)

백엔드는 설정에 지정된 프록시(기본값 `127.0.0.1:7890`)를 통해 Pixiv에 접속하며 시스템 프록시에 의존하지 않습니다. Clash의 시스템 프록시를 켜지 않고 브라우저에서 `pixiv.net`을 직접 열고 싶다면 내장 프록시 자동 구성(PAC)을 사용하세요.

운영 체제/브라우저의 "자동 프록시 구성 스크립트(PAC) URL"을 `http://localhost:6999/proxy.pac`으로 설정하세요(설정한 포트에 맞추고 HTTPS를 활성화하면 `https://<domain>:<port>/proxy.pac`이 됩니다). 그러면 Pixiv 관련 도메인만 동일한 백엔드 설정 프록시를 통과하고 나머지는 직접 연결됩니다. 이 엔드포인트는 로컬 전용이며 프록시 변경(핫 리로드 포함)이 자동으로 반영되므로 시스템 프록시를 반복해서 전환할 필요가 없습니다.

브라우저/OS별 정확한 설정 경로(Firefox `about:preferences#general`, Windows `ms-settings:network-proxy` 등)는 [설정 · 동일한 프록시를 통한 웹 Pixiv 연결](https://sywyar.github.io/PixivDownloader/#/ko/configuration)을 참고하세요.

---

## 온라인 문서

자세한 설치 절차, 사용 가이드, 설정 참고 자료 및 개발 가이드는 [온라인 문서](https://sywyar.github.io/PixivDownloader/#/ko/)를 참고하세요. 각 섹션으로 바로 이동할 수 있습니다.

**빠른 시작**

- [📥 설치 및 시작](https://sywyar.github.io/PixivDownloader/#/ko/installation)
- [⚙️ 최초 설정](https://sywyar.github.io/PixivDownloader/#/ko/first-setup)
- [⬇️ 첫 다운로드](https://sywyar.github.io/PixivDownloader/#/ko/first-download)

**기능 가이드**

- [⚡ 빠른 가져오기](https://sywyar.github.io/PixivDownloader/#/ko/quick-access)
- [📋 URL 일괄 다운로드](https://sywyar.github.io/PixivDownloader/#/ko/batch-download)
- [👤 작가 일괄 다운로드](https://sywyar.github.io/PixivDownloader/#/ko/user-download)
- [🔍 검색 다운로드](https://sywyar.github.io/PixivDownloader/#/ko/search)
- [📖 소설 다운로드](https://sywyar.github.io/PixivDownloader/#/ko/novel)
- [🖼️ 작품 갤러리](https://sywyar.github.io/PixivDownloader/#/ko/gallery)
- [⏰ 예약 작업](https://sywyar.github.io/PixivDownloader/#/ko/scheduled-tasks)
- [🧩 사용자 스크립트](https://sywyar.github.io/PixivDownloader/#/ko/userscripts)

**참고 자료**

- [⚙️ 설정](https://sywyar.github.io/PixivDownloader/#/ko/configuration)
- [🔌 플러그인 관리](https://sywyar.github.io/PixivDownloader/#/ko/plugin-management)
- [💾 저장 원칙](https://sywyar.github.io/PixivDownloader/#/ko/storage)
- [❓ FAQ](https://sywyar.github.io/PixivDownloader/#/ko/faq)
- [🛠️ 개발](https://sywyar.github.io/PixivDownloader/#/ko/development)

---

## 면책 조항

- 이 프로젝트는 개인 학습 및 연구용이며 상업적 용도로 사용하지 마세요.
- 이 도구로 다운로드한 콘텐츠의 저작권은 원작자에게 있습니다. 원작자의 권리를 존중하고 재배포하거나 상업적으로 사용하지 마세요.
- 이 도구는 사용자가 제공한 Cookie를 사용하거나, 사용자의 허가를 받아 Tampermonkey 사용자 스크립트로 Cookie를 추출하여 Pixiv에 접속합니다. 계정과 관련된 위험은 사용자 본인이 부담합니다.
- 이 프로젝트는 Pixiv와 아무런 관련이 없으며, 이 도구 사용으로 발생하는 모든 결과는 사용자의 책임입니다.
- Pixiv 서버에 과도한 부하를 주지 않도록 적절한 다운로드 간격을 설정하세요.

---

## 추가 참고

솔직히 말해 이 도구의 다중 모드는 모든 요청이 서버의 네트워크 IP를 통해 전송되므로 권장하지 않습니다. Cookie가 달라도 많은 요청으로 인해 IP가 차단될 수 있습니다. 다중 모드에 로그인 기능을 추가하는 방안을 고려하고 있지만, 이는 프로젝트의 단순성이라는 본래 의도와 맞지 않습니다. 당분간은 계속 프로젝트를 개선해 나가겠습니다.

## 추천 링크

**[PixivBatchDownloader](https://github.com/xuejianxianzun/PixivBatchDownloader)**
백엔드 프로그램에 의존하지 않고 간단하게 사용하고 싶다면 이 스크립트를 사용해 보세요.

기능:

- 다양한 필터링 옵션
- 광고 제거, 빠른 북마크, 이미지 뷰어 모드 등 유용한 보조 기능(픽시브 도우미 플러그인으로도 사용할 수 있습니다.)
- 타사 도구에 의존하지 않는 다운로드(이 프로젝트와 가장 큰 차이점이며 설치가 쉽습니다.)
- 다국어 지원

## 개발 계획
