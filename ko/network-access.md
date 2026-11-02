# 네트워크 접근과 외부 서비스

작업이 어디에 연결되는지, 어떤 데이터를 보내는지, 이후 요청을 어떻게 중단하는지 확인하는 페이지입니다. 실행 중 접근은 기능별로, 개발·빌드·배포 접근은 별도로 설명합니다. 개발 도구의 연결이 설치된 프로그램에서 모두 발생하는 것은 아닙니다.

플러그인 이름은 관리 페이지의 ID이며 ‘호스트’는 코어 `pixivdownload-app`입니다. 목적지만 보지 말고 실행 조건, 자격 증명과 프록시 규칙을 함께 확인하세요. 기본 도메인 하나를 차단해도 사용자 지정 목적지까지 막히지는 않습니다.

?> 현재 코드의 고정 기본 주소와 동적 목적지의 허용 범위를 정리했습니다. 외부 서비스의 DNS, CDN, 리디렉션과 임시 다운로드 URL 때문에 실제 IP와 최종 하위 도메인은 달라질 수 있습니다. 관리자가 입력하는 URL은 미리 모두 열거할 수 없습니다.

## 연결이 시작되는 시점

AI, TTS, 푸시, 메일과 Douyin은 해당 기능을 설정하고 호출할 때 외부에 연결합니다. `notification`은 활성화되어 시작하면 고정 공지 목록을 자동 조회합니다. 온라인 업데이트와 자동 검사가 켜져 있으면 호스트가 시작 준비 완료 뒤 GitHub Releases를 검사하며 캐시 간격으로 빈도를 제한합니다.

소개 페이지를 열면 브라우저가 Google Fonts를 읽습니다. `plugin-catalog.enabled`와 내장 공식 저장소는 기본 활성화되지만 프로그램 시작 자체는 카탈로그를 읽지 않습니다. 관리자 페이지의 플러그인 업데이트 검사, 마켓 탐색·새로고침·설치, 관리 페이지에서 명시적으로 실행한 철회 정보 갱신이 저장소에 연결할 수 있습니다.

공식 PostHog 설문마다 게시 플러그인이 버전 관리하는 `posthog-config.js`에 공개 매개변수 네 개가 있습니다. 별도로 빌드하는 `release-activation.js`는 일반 소스·fork 빌드에서 기본 `false`이므로 이 설문은 기본적으로 PostHog에 연결하지 않습니다. Pixiv, Douyin, AI, TTS, 푸시와 메일은 아래 조건에 따라 사용자 콘텐츠나 자격 증명을 전송합니다.

## 프록시 적용 범위

| 요청 주체 | 적용 경로 |
| --- | --- |
| Pixiv, 업데이트, FFmpeg, 공식 플러그인 저장소와 일부 플러그인 | 호스트 또는 작업별 경로 |
| AI, TTS, Douyin | 기능별 또는 작업별 직접 연결·프록시 선택 가능 |
| 사용자 지정 플러그인 저장소 | 저장소 정책 적용. direct-strict는 전역 프록시를 우회 |
| 유저스크립트, Google Fonts, PostHog | 브라우저 요청이며 Java 백엔드 프록시를 우회 |
| SMTP | Mail 자체 연결 설정과 별도 SOCKS 프록시 사용 가능 |

HTTP/SOCKS 프록시가 켜져 있으면 애플리케이션은 먼저 지정된 프록시 호스트·포트에 연결하고 프록시가 최종 서비스에 연결합니다.

## 코어와 기본 요청

로그인 페이지를 열면 애플리케이션과 같은 출처의 `/api/auth/check`에서 세션을 확인합니다. 제출 시 관리자 이름, 비밀번호, 로그인 유지 선택은 `/api/auth/login`으로, 초대 코드는 `/api/auth/invite-redeem`으로 보내며 같은 출처 세션 쿠키를 사용합니다. 두 방식은 진행 중인 제출 하나를 공유하며 실패 후 다시 시도할 수 있지만 자동 재전송하지 않습니다. 관리자 로그인 후 돌아갈 주소는 브라우저 URL 해석 후에도 같은 출처인 사이트 내부 경로만 허용하고, 잘못된 주소는 기본 다운로드 페이지로 돌아갑니다. 이 요청은 브라우저 네트워크 설정을 사용하며 백엔드의 외부 통신 프록시를 거치지 않습니다. 로그인 페이지를 떠나면 이후 동작은 중단되지만 이미 보낸 인증 요청은 완료될 수 있습니다.

페이지를 열거나 언어를 바꾸면 브라우저는 같은 출처의 `/api/i18n/meta`, `/api/i18n/messages/{namespace}?lang=...`에서 언어 목록과 사전을 읽고 실패하면 페이지 상대 경로 `i18n-static/`을 사용합니다. 언어, namespace와 같은 출처 세션 쿠키를 보내며 번역 서비스나 백엔드 프록시는 사용하지 않습니다. 각 요청은 캐시를 우회하며 제한 시간은 10초입니다. 누락 사전은 1.5초, 5초, 15초 뒤 최대 세 차례 자동 복구하고 성공한 사전은 유지합니다. 재연결이나 페이지 포커스도 재시도를 유발할 수 있습니다. 페이지를 닫거나 언어 클라이언트를 교체하면 해당 클라이언트의 이후 재시도가 중단됩니다.

| 주체·목적지 | 목적과 전송 데이터 | 실행 조건·중단 방법 |
| --- | --- | --- |
| 호스트: `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json`와 인접 `update.json.sig`. Nightly는 `/releases/download/nightly/` 아래 대응 파일 | 안정·Nightly 목록과 detached Ed25519 서명을 읽습니다. User-Agent·IP 등 일반 연결 정보만 보내며 현재 버전, 플랫폼, Pixiv 쿠키나 다른 자격 증명은 보내지 않습니다. 응답은 각각 1 MiB / 16 KiB로 제한하고 내장 공식 키로 원본 바이트를 검증한 뒤 해석합니다 | `update.enabled=true`와 `update.auto-check=true`가 기본이며 준비 완료 뒤 검사합니다. 수동 검사도 같은 주소를 사용합니다. Nightly 검사 활성 시 안정 목록 실패와 별도로 Nightly를 검증하고 안정 실패도 기록합니다. 검증되지 않은 채널은 다운로드를 제공하지 않습니다. 호스트 프록시를 사용하며 기본 공개 HTTPS 주소만 허용하고 최대 5회 리디렉션을 매번 재검증합니다. 온라인 업데이트나 자동 검사를 끄세요. 사용자 지정 미러도 유효한 공식 서명이 필요합니다 |
| 호스트: 검증된 업데이트 목록의 현재 플랫폼 설치 프로그램 URL, 기본 GitHub Releases/CDN | 쿠키·자격 증명 없이 설치 파일을 읽고 서명된 목록의 정확한 SHA-256과 크기를 검사합니다 | 업데이트 발견 뒤 사용자가 설치를 시작할 때만 요청합니다. 검사만으로 설치하지 않습니다. 최대 5회 리디렉션에 HTTPS·공개 주소 검사를 적용하고 전체 응답을 500 MiB로 제한합니다. 온라인 업데이트를 끄면 중단됩니다 |
| 소개 페이지: `https://fonts.googleapis.com/css2?...`, `https://fonts.gstatic.com/...` | Noto Sans SC CSS·글꼴을 읽고 일반 브라우저 연결 정보만 노출합니다 | 소개 페이지를 열 때 요청합니다. 호스트 프록시를 우회하며 도메인을 차단하면 대체 글꼴을 사용합니다 |
| 호스트: `https://www.pixiv.net/` | Pixiv 쿠키 없는 연결 검사 | 최초 설정이나 명시적 Pixiv 연결 검사 때만 실행하며 heartbeat가 아닙니다. 호스트 Pixiv 경로를 사용합니다. 검사를 실행하지 않으면 요청하지 않습니다 |
| `notification`: `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json`, 인접 `index.json.sig`, `.../announcements/<message-id>/<locale>.html` | 원본 목록 바이트의 공식 Ed25519 서명, 유효 기간, 단조 증가 순서와 문서 SHA-256을 검사합니다. 쿠키를 끄고 IP·User-Agent만 보내며 계정, 작품, 로컬 경로나 자격 증명은 보내지 않습니다. 새 안정 ID에 필요한 모든 언어의 제한된 HTML을 내려받아 로컬에 저장합니다. 관리자 브라우저는 인증된 로컬 경로만 읽습니다. 검증·전송 실패 시 마지막 신뢰 스냅샷을 유지합니다 | 플러그인 시작 후 무작위 0~30분 대기하고 약 6시간마다 ±15% 지터로 검사합니다. 신뢰 가져오기가 끝나면 ETag·Last-Modified를 저장하고 서명 목록 유효 기간에만 조건부 요청합니다. 304면 서명·문서 다운로드를 생략합니다. 429의 Retry-After는 최대 24시간, 전송·5xx 오류는 5분·15분·1시간·6시간 지연을 적용합니다. 새 ID나 서명 문서 해시 변경 때만 HTML을 읽고, 같은 메타데이터의 기존 ID와 명시적으로 삭제한 ID는 다시 읽지 않습니다. 기본 배포에 포함되므로 활성·시작 성공 시 자동 연결합니다. 호스트 경로·전역 프록시를 상속합니다. 비활성화·제거하면 검사가 멈추며 stop/reload는 이후 폴링을 즉시 취소합니다 |

## Pixiv 다운로드와 탐색

썸네일 프록시는 HTTPS `*.pximg.net`과 `embed.pixiv.net`에만 접근하며, Pixiv Cookie를 보내거나 리디렉션을 따르지 않습니다. 응답 하나의 최대 크기는 100 MiB(104,857,600바이트)이며, 이를 넘으면 로드에 실패합니다. 호스트의 공용 이미지 전송은 성공이 아닌 HTTP 상태를 실패로 처리하며, 오류 본문 전체를 이미지나 예외 상세 정보로 버퍼링하지 않습니다.

호스트가 Pixiv JSON을 읽거나 작품을 북마크할 때 4xx/5xx 응답은 실패로 처리하며, 파싱이나 로그 기록을 위해 오류 본문을 읽지 않습니다. 홈페이지 요청이 실패하면 북마크 요청을 보내지 않습니다. 북마크 실패는 이미 완료된 다운로드에 영향을 주지 않습니다.

호스트의 메타데이터 보충은 등록된 작품 폴더의 기존 `.meta.json`을 가져온 뒤 로컬 메타데이터로 일러스트의 누락 필드와 소설 업로드 시간을 채웁니다. 정보가 부족하면 `https://www.pixiv.net/ajax/illust/{id}` 또는 `https://www.pixiv.net/ajax/novel/{id}`에 요청합니다. 데스크톱 시작 시 필요한 데이터베이스 열이 이미 있어도 누락 정보를 확인합니다. 항상 네트워크에서 가져오기를 선택하면 로컬 값을 사용하지 않고 누락 필드를 조회하며, 기존 값은 덮어쓰지 않습니다. 작품 ID와 일반 AJAX 헤더를 보내지만 Pixiv 쿠키는 보내지 않습니다. 응답은 4 MiB로 제한하며, 초과하면 완료된 결과를 유지하고 중단합니다. 개별 HTTP 요청은 자동 재시도하지 않습니다. 네트워크 오류와 401/403/407/5xx는 완료 결과와 로그를 남기고 배치를 중단합니다. 429는 속도 제한으로 중단하며, 404는 해당 작품 유형과 ID를 사용 불가로 기록하여 이후 보충에서 그 작품의 네트워크 요청을 건너뜁니다.

연령 제한 응답은 별도의 보류 상태로 기록합니다. 이후 데스크톱 시작에서는 다시 요청하지 않으며 작품을 삭제된 것으로 표시하지도 않습니다. 도구 화면의 수동 보충은 다시 시도하고, 로컬 우선 방식은 새로 얻은 메타데이터로 복구할 수 있습니다. 복구가 완료되거나 유효한 네트워크 응답을 받으면 보류 기록을 지웁니다. 사전 가져오기는 스냅샷만 저장하며, 필드 보충은 지정한 처리 수 제한을 따르고 기존 값을 유지합니다.

데스크톱과 헤드리스 서버는 백엔드가 준비되면 기본적으로 백그라운드에서 자동 보충을 시작하며, 현재 데이터베이스 연결 풀, 다운로드 폴더, 프록시 설정을 사용합니다. 데이터베이스를 사용할 수 없으면 보충을 건너뛰고 복구용 서비스를 유지합니다. Swing과 Compose 도구 화면에서 진행 상황을 확인하고 중지할 수 있으며 패널을 닫아도 작업은 계속됩니다. 헤드리스 모드는 창이나 브라우저를 열지 않으며 서비스를 정상 종료하면 보충도 취소됩니다. 중지 시 저장된 데이터는 유지하고 작업의 자원 해제를 기다립니다. 주 프로그램 로그와 독립 보충 CLI는 시작, 각 단계의 첫 진입, 처리 수, 업데이트 수, 정보가 부족한 작품 수, 결과를 출력합니다. 반복 진행 로그는 최대 30초에 한 번 기록합니다. 독립 CLI는 백엔드를 중지한 뒤 실행하며 `--dry-run`으로 미리 확인하고 `--limit`으로 처리 수를 제한하며 `--proxy` 또는 `--no-proxy`로 프록시를 지정할 수 있습니다. 자동 보충은 이미 확인된 열람 제한 작품의 네트워크 요청을 보류하지만 수동 보충은 다시 시도하며 새 로컬 정보로도 복구할 수 있습니다.

데스크톱 시작과 도구 페이지에서 보충을 실행합니다. 저장된 `proxy.enabled`, `proxy.host`, `proxy.port`를 기본으로 사용하고 도구의 임시 재정의는 그 실행에만 적용합니다. 수정하지 않은 필드는 새로 저장한 설정을 따릅니다. 자동 FFmpeg 설치도 저장된 전역 프록시를 읽습니다. 설정 읽기 실패나 활성 프록시 오류는 직접 연결로 전환하지 않고 중단합니다. 프록시를 끄면 프로세스 기본 프록시를 상속하지 않는 직접 연결입니다. 해당 도구를 중단하거나 실행하지 않으면 요청을 피할 수 있습니다.

북마크 필터에 수가 없으면 두 다운로드 페이지 모두 원래 ID로 해당 작품 유형 플러그인에 조회합니다. 일러스트와 소설은 아래 각 메타데이터 경로를 사용하며 조회 불가를 0으로 취급하지 않습니다. 팔로우 작품 전체 조회는 총수를 모를 때 최대 1,000페이지를 검사합니다. 1,000번째에도 다음 결과가 있으면 오류를 표시하고 불완전한 목록을 큐에 넣지 않습니다.

새 페이지는 작업 공간이 보일 때 4초마다 로컬 API로 일정 목록과 펼친 큐를 갱신합니다. 페이지 숨김, 다른 공간 선택이나 다운로드 목록 열기에서는 멈추고 돌아오면 즉시 재개합니다. 다운로드 가이드는 예시의 기존 페이지 큐를 2초마다 읽을 뿐 추가 Pixiv 요청은 하지 않습니다. 5분 동안 결과가 확인되지 않으면 더 기다리거나 페이지로 돌아갈 수 있습니다. 가이드에서 나가도 제출한 다운로드는 취소되지 않습니다.

호스트는 공통 Pixiv HTTP 전송을 제공하고 실제 업무 요청은 워크벤치, 소설 플러그인이나 유저스크립트가 시작할 수 있습니다.

| 요청 주체·목적지 | 목적·전송 데이터 | 실행 조건·중단 방법 |
| --- | --- | --- |
| `download-workbench`: `https://www.pixiv.net/ajax/illust/**`, `/ajax/user/**`, `/ajax/search/artworks/**`, `/ajax/series/**`, `/ajax/collection/**`, `/ajax/follow_latest/illust`, `/rpc/index.php` | 작품, 우고이라, 작가, 시리즈, 검색, 컬렉션, 팔로우 작품과 커미션 메타데이터. 인증 필요 요청은 설정한 Pixiv 쿠키 사용 | 미리보기, 빠른 가져오기, 다운로드 제출, 예약 작업, 보충과 해당 조회 모드. 호스트 Pixiv HTTP와 선택 경로를 사용합니다. 필수 플러그인이므로 없거나 사용 불가이면 주요 다운로드 기능도 사용할 수 없습니다 |
| `download-workbench`: `https://www.pixiv.net/ajax/illusts/bookmarks/add` | 작품 ID, 공개 범위, 태그와 Pixiv 자격 증명으로 북마크 | 다운로드 후 북마크 옵션을 켜고 다운로드가 완료될 때만 실행. 해당 옵션으로 중단 |
| `novel`: `https://www.pixiv.net/ajax/novel/**`, `/ajax/novel/series/**`, `/ajax/novel/series_content/**`, `/ajax/user/**/novels`, `/ajax/user/**/novels/bookmarks`, `/ajax/search/novels/**` | 소설 본문, 시리즈, 작가 목록, 북마크·검색 결과. 인증 요청은 Pixiv 쿠키 사용 | 미리보기, 합본, 검색, 작가 소설 보기와 예약 작업. 미리보기에서 검증한 응답은 현재 owner·작품·취득 자격 증명에 묶인 단기 일회용 티켓으로 재사용하므로 직후 다운로드가 같은 본문을 다시 요청하지 않습니다. 티켓 없는 호환 클라이언트의 직접 제출은 백엔드가 읽고 잘못된 티켓은 거부합니다. 호스트 Pixiv HTTP를 사용하며 `novel`을 끄면 페이지와 기능을 철회합니다 |
| `novel`: `https://www.pixiv.net/ajax/novels/bookmarks/add` | 다운로드한 소설 북마크 | 해당 옵션과 소설 다운로드 완료 때만 실행. 옵션으로 중단 |
| 호스트 이미지 전송, 주 호출자 워크벤치·소설: HTTPS `*.pximg.net`, 썸네일의 `embed.pixiv.net` | 원본, 표지, 썸네일, 우고이라 ZIP, 소설 삽화. Pixiv API 응답의 URL과 보통 Pixiv Referer 사용 | 다운로드, 표지·썸네일 생성, 미리보기와 소설 파일 생성. Pixiv 이미지 경로를 사용하며 관련 작업을 중단하면 이후 요청을 피할 수 있습니다 |
| 루트 유저스크립트: `https://www.pixiv.net/ajax/**`, HTTPS `*.pximg.net` | Pixiv 페이지의 작품·소설 조회, 북마크와 직접 파일 다운로드. `GM_xmlhttpRequest`는 브라우저/Pixiv 로그인 문맥 사용 | 스크립트 동작, 페이지 수집이나 로컬 다운로드. 호스트 프록시를 우회하므로 스크립트를 끄거나 제거해야 중단됩니다 |
| 루트 유저스크립트: 사용자 설정 백엔드, 기본 `http://localhost:6999` | 다운로드 제출, 큐, 상태·SSE와 선택 작품·옵션 전송. 로컬 solo 소설 다운로드는 크기가 제한된 Pixiv 응답을 로컬 소설 플러그인에 보내 일회용 티켓을 받으며 Pixiv 쿠키는 보내지 않음 | Java 백엔드·배치·Toolbox 기능. 원격 주소로 바꾸면 그 서버에 요청하지만 소설 응답 가져오기는 비활성화되고 백엔드가 직접 본문을 읽습니다 |
| 공식 `pixiv-batch-downloader-import` 수집 스크립트: `http://localhost:6999/api/pixiv-batch-downloader-import/token`, `/api/pixiv-batch-downloader-import`. loopback 호스트·포트와 HTTP/HTTPS 조정 가능 | 같은 컴퓨터에 완료 파일 경로, 작품 메타데이터와 소설 본문을 보내 원본 파일 참조 등록. Pixiv 쿠키, 관리자 암호와 주변 쿠키를 보내거나 미디어를 다시 다운로드하지 않음 | [공식 가져오기 플러그인](/ko/pixiv-batch-downloader-import)을 먼저 설치·활성화합니다. 일반 배포에는 없고 전체 오프라인 배포에는 포함됩니다. 설치 후 Pixiv 페이지를 새로고침하면 완료 작품을 자동 전송하고 페이지가 열려 있을 때 대기 기록을 지연 재시도합니다. solo 전용입니다. 원본 루트가 없으면 완성 작품의 절대 경로에서 후보만 제안하고 데스크톱 확인·선택·저장 뒤 가져옵니다. 호스트 프록시를 쓰지 않습니다. 스크립트를 끄면 전송, 플러그인을 끄면 수신, 원본 루트를 비우면 가져오기가 즉시 멈춥니다. 후보 관찰만으로 설정을 저장하지 않으며 파일은 복사하지 않고 갤러리 삭제도 원본을 보존합니다 |
| `pixiv-batch-downloader-import` 기록 스크립트 | 설정한 루프백 서버의 `/api/pixiv-batch-downloader-import/token`, `/api/pixiv-batch-downloader-import/history` 및 현재 Pixiv 출처의 `/ajax/illust/{id}`, `/ajax/novel/{id}` | 로컬 서버에는 기록 분할 파일, 작품 ID, 원본 AJAX 응답을 전송. Pixiv에는 ID와 브라우저 동일 출처 인증 정보를 전송. 쿠키는 로컬로 전달하지 않으며 원본 작품 파일도 업로드하지 않음 | 메뉴에서 현재 브라우저의 기록 데이터베이스를 읽고 확인한 뒤 명시적으로 시작하면 작품 정보를 가져와 등록 | 기본적으로 실행하지 않는 수동 기능이며 solo 루프백과 신뢰하는 Pixiv 출처에 한정. Pixiv는 브라우저 네트워크 설정, 로컬은 GM 요청을 사용하며 호스트 송신 프록시를 사용하지 않음. 리디렉션 거부. 취소·페이지 이동·서버 변경 시 원래 서버에 중지를 요청하며 자동 재개나 쓰기 재전송은 하지 않음 |

작업 제출 계약을 지원하는 호스트에서는 SDK `DownloadTasks.submit` 또는 관리자의 `POST /api/download/tasks`에 작품 유형·ID·출력 옵션을 전달할 수 있습니다. 공식 일러스트·소설 handler는 Pixiv 메타데이터·본문을 읽고 기존 미디어·프록시 경로를 사용합니다. 취득 자격 증명은 별도 인자나 `X-Acquisition-Credential`로 전달하며 공개 이벤트·작업 스냅샷에는 포함하지 않습니다. 워크벤치는 기존 큐에 실행 상태를 반영합니다. 종료 백엔드 스냅샷은 5분 후 만료하지만 저장된 큐 기록은 남습니다. 같은 출처 `GET /api/download/tasks`를 2초마다 폴링하고 페이지 숨김·이탈 시 중단합니다. 취소는 해당 작업의 `/cancel`로 POST하여 협조적 취소를 요청합니다. 조회·취소·새로고침은 Pixiv 다운로드를 다시 제출하지 않습니다. 지원 여부는 설치한 호스트·SDK 버전에 따릅니다.

일반 Pixiv JSON 응답은 4 MiB, 소설 시리즈 상세·페이지 콘텐츠 응답은 1 MiB로 제한합니다. `Content-Length`가 있으면 검사하고 실제 읽은 바이트도 항상 제한하며 초과 시 제어된 오류로 중단합니다. 소설 삽화 매핑은 최대 512개입니다. 티켓에는 본문과 삽화 표를 제거한 원시 메타데이터만 최대 256 KiB 보관합니다.

네 개의 백엔드 다운로드 스크립트는 컬렉션 새로고침이나 컬렉션 선택 제출 때 `GET /api/collections`를 호출합니다. 자동 소설 번역 시 `GET /api/auth/check`로 관리자 세션을 확인하고 목표 언어, 분할 크기와 합본 옵션을 제출합니다. `GET /api/novel/translate-status/{id}`로 번역을 관찰하며 단일 작품 스크립트는 먼저 `GET /api/novel/status/{id}`로 다운로드 완료를 기다립니다. 유저스크립트 관리자의 기존 백엔드 세션을 사용하며 Pixiv 쿠키는 백엔드에 보내지 않습니다. 백엔드가 설정된 AI를 호출합니다. 페이지를 닫으면 상태 조회만 멈추며 제출된 작업은 계속될 수 있습니다.

백엔드에서 설치한 새 스크립트는 해당 주소의 두 다운로드 페이지에서 ID·버전 질의에 답할 수 있지만 Pixiv 다운로드 로직을 실행하지는 않습니다. 현재 페이지 안의 메시지로 감지하며 설치 목록·쿠키·설정을 서버나 외부 서비스에 보내지 않습니다. 응답이 없으면 ‘감지되지 않음’이며 설치·업데이트·활성화 변경 후 페이지를 새로고침해야 합니다.

작품 메타데이터·페이지 이미지·우고이라 프록시는 범위 내 양의 정수 ID만 받아 정규화한 `www.pixiv.net/ajax/illust/{id}`와 고정 하위 경로를 사용합니다. 초대 게스트는 볼 수 있는 작품만 요청할 수 있으며 잘못되거나 보이지 않는 ID는 상위 요청을 만들지 않습니다.

## Douyin 플러그인

`douyin`은 필요할 때 설치하는 플러그인으로 기본 Windows·Java 표준 배포에 포함되지 않습니다. 설치·활성화하고 기능을 사용할 때만 아래에 연결합니다.

| 목적지 | 데이터와 실행 조건 |
| --- | --- |
| `https://www.douyin.com/aweme/v1/web/**` | 사용자 게시물·좋아요·믹스·검색·음악 작품·컬렉션·계정·작품 상세를 조회하며 Douyin 쿠키, 질의 인자와 브라우저 형태 헤더를 전송합니다. 수동 조회, 미리보기, 다운로드, 예약 검색, 쿠키 검사와 갤러리 새로고침 때 요청합니다 |
| HTTPS `douyin.com` / `*.douyin.com`, `iesdouyin.com` / `*.iesdouyin.com`, 대표적으로 `v.douyin.com` | 붙여 넣은 단축 링크를 제한된 리디렉션으로 해석합니다 |
| HTTPS `*.douyinvod.com`, `*.douyinpic.com`, `*.douyinstatic.com`, `*.amemv.com`, `*.byteimg.com`, `*.bytedance.com`, `*.bytecdn.cn`, `*.pstatp.com`, `*.snssdk.com`과 위 Douyin 도메인 | API 응답의 URL로 동영상·이미지·표지·live-photo를 다운로드하며 제한된 리디렉션이 있을 수 있습니다 |

작업 경로, 원본의 기본 경로 또는 호스트 전역 경로를 사용합니다. 해당 작업 중단으로 후속 미디어 요청을 막거나 플러그인을 끄고 제거하여 전체 요청을 중단할 수 있습니다.

`www.douyin.com/aweme/v1/web/` 요청은 사용자가 제공한 Cookie에서 UIFID를 읽어 헤더와 쿼리 매개변수로 전송합니다. 쿼리에는 타임스탬프와 로컬에서 계산한 `x-secsdk-web-signature`도 포함됩니다. 서명 계산은 외부 서비스를 호출하지 않습니다. Cookie에 UIFID가 없으면 대체 식별자를 생성하지 않으며, 보호된 엔드포인트가 요청을 거부할 수 있습니다.

## AI 플러그인

`ai`는 OpenAI 호환 프로토콜을 사용합니다. 연결 검사·번역·AI 기능은 선택한 base URL의 `/chat/completions`로 처리할 텍스트, 작업별 프롬프트, 모델 이름과 API 키를 보냅니다. 데스크톱의 모델 목록 조회는 저장 전 초안 URL과 프록시를 사용해 같은 base URL의 `/models`에 GET하며 작품 본문이나 프롬프트를 보내지 않습니다. 처음에는 키 없이 요청하고 401/403 응답에 초안 키가 있을 때만 한 번 인증 재시도하며 이후 페이지에도 같은 인증을 씁니다. 기존 시간·크기 예산을 공유하고 다른 오류는 바로 표시합니다. 설치나 설정 페이지를 여는 것만으로 요청하지 않습니다.

소설 번역 요청에는 용어집의 원문 용어, 대상 언어와 번역을 최대 1,000개까지 보냅니다. 기존 항목을 우선하고, 장에서 새로 나온 용어를 등장 순서대로 남은 자리에 추가합니다. 이후 분할 요청에도 같은 상한을 적용합니다. 요청에 포함하지 못한 새 용어도 번역 성공 후 용어집에 병합합니다. 항목 수 상한은 토큰 수 상한이 아닙니다.

키는 보통 Bearer 인증입니다. `https://api.anthropic.com/v1`의 모델 조회는 [Anthropic API](https://platform.claude.com/docs/en/api/models/list)에 맞춰 `anthropic-version`을 보내고 인증 재시도에 `x-api-key`를 추가하며 `after_id`로 같은 endpoint를 페이지 조회합니다. 응답 URL을 따라가거나 다른 공급자의 호스트·지역·workspace로 자동 전환하지 않습니다. 실패·미지원 페이지 처리·한도 초과 시 부분 목록을 반환하지 않습니다. 조회 endpoint가 없어도 모델을 직접 입력할 수 있습니다.

모델 ID는 일반 텍스트 선택지이며 선택은 초안에, 저장은 설정에 반영됩니다. 채팅 부적합이 명시된 항목만 제외하고 기능이 불명확한 항목은 유지합니다. 목록은 계정 권한·과금 접근·채팅 호환성·최신 모델임을 보장하지 않으므로 연결 검사로 확인하세요.

| 프리셋 | 기본 base URL |
| --- | --- |
| OpenAI | `https://api.openai.com/v1` |
| Anthropic 호환 | `https://api.anthropic.com/v1` |
| Gemini OpenAI 호환 | `https://generativelanguage.googleapis.com/v1beta/openai` |
| xAI | `https://api.x.ai/v1` |
| Mistral | `https://api.mistral.ai/v1` |
| Groq | `https://api.groq.com/openai/v1` |
| DeepSeek | `https://api.deepseek.com` |
| Alibaba Model Studio/Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Zhipu | `https://open.bigmodel.cn/api/paas/v4` |
| Moonshot | `https://api.moonshot.cn/v1` |
| Doubao/Volcengine Ark | `https://ark.cn-beijing.volces.com/api/v3` |
| Tencent Hunyuan / TokenHub | `https://tokenhub.tencentmaas.com/v1` |
| Baidu Qianfan/ERNIE | `https://qianfan.baidubce.com/v2` |
| iFlytek Spark | `https://spark-api-open.xf-yun.com/v1` |
| MiniMax | `https://api.minimax.cn/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| SiliconFlow | `https://api.siliconflow.cn/v1` |
| Ollama | `http://localhost:11434/v1` |
| LM Studio | `http://localhost:1234/v1` |

프리셋 선택은 권장 모델·URL을 채우며 기존 저장 설정을 자동 이전하지 않습니다. Tencent Hunyuan 프리셋은 TokenHub이므로 TokenHub 콘솔에서 새 키와 모델 접근 권한이 필요합니다. 예전 Hunyuan 키는 재사용할 수 없습니다. 기존 URL은 유지되므로 이전하려면 프리셋 재선택, 새 키 입력과 저장을 수행합니다.

관리자는 호환하는 임의 URL을 지정할 수 있습니다. 사용자 지정 목적지는 요청 인자를 받고, 채팅과 인증 모델 재시도는 API 키를, 채팅은 본문도 받습니다. 로컬·인트라넷도 가능하므로 신뢰하는 서비스만 사용하세요. AI는 쿠키를 저장하거나 HTTP 리디렉션을 따르지 않아 키를 리디렉션 목적지에 전달하지 않습니다. 현재 AI 설정의 직접·프록시 경로를 사용합니다. AI 기능을 꺼도 수동 연결 검사·모델 조회는 가능하고 키를 지워도 익명 조회는 가능합니다. 이 작업을 실행하지 않거나 `ai` 플러그인을 비활성화해야 해당 요청이 중단됩니다.

## `translation` 플러그인

관리자가 번역을 시작하거나 다운로드 후 자동 번역이 활성화된 경우에 접속합니다. AI는 `ai`에 저장된 URL과 인증 정보를 사용합니다. Youdao는 고정 주소 `https://openapi.youdao.com/api`, LibreTranslate는 `translation.libretranslate-url` 아래의 `/translate`로 POST 폼을 보냅니다. 선택한 본문·제목·소개, 원본 언어 자동 감지, 대상 언어가 포함됩니다. Youdao는 앱 ID·시간·임의 값·v3 서명을 보내며 비밀 키 자체는 보내지 않습니다. LibreTranslate는 서비스에서 요구하면 `api_key`를 보냅니다.

`translation.use-proxy` 기본값 `false`는 직접 연결, `true`는 앱 프록시를 사용합니다. AI는 자체 설정을 따릅니다. 연결 제한은 30초, 읽기 제한은 120초이며 리다이렉트·자동 재시도·Cookie 저장을 하지 않습니다. 일반 번역의 요청당 상한은 1,000 Unicode 코드 포인트, 응답은 1 MiB, 결합한 필드별 출력은 64 Mi UTF-16 코드 단위입니다.

LibreTranslate에는 신뢰하는 로컬·인트라넷 URL도 지정할 수 있습니다. 서비스와 모델은 직접 준비해야 합니다. 플러그인은 모델을 다운로드하지 않으며 로컬 환경을 준비하면 오프라인으로 사용할 수 있습니다. 자동 번역을 끄고 수동 작업을 중단하거나 플러그인을 비활성화하면 후속 요청을 막을 수 있습니다. 브라우저 취소가 이미 수락된 작업의 즉시 중지를 보장하지는 않습니다. 설치나 설정 화면 열기만으로는 접속하지 않습니다.

## TTS 플러그인

`tts`는 미리듣기, 음성 생성, 음성 목록 새로고침, 연결 검사나 실제 소설 읽기를 실행할 때 읽을 본문, 음성·모델 옵션과 서비스 자격 증명을 보냅니다.

| 엔진 | 목적지·조건 |
| --- | --- |
| Edge TTS | 합성: `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`. 음성 목록: 같은 호스트의 `/consumer/speech/synthesize/readaloud/voices/list`. 합성·미리듣기·목록 갱신 때 요청 |
| Edge 버전 검사 | `https://edgeupdates.microsoft.com/api/products?view=enterprise`. Edge TTS에 새 클라이언트 버전 정보가 필요할 때 요청하며 별도 원격 측정이 아님 |
| Xiaomi MiMo | `https://api.xiaomimimo.com/v1/chat/completions` |
| Fish Audio | `https://api.fish.audio/v1/tts` |
| MiniMax | 기본 `https://api.minimax.io/v1/t2a_v2`, 중국 endpoint `https://api.minimaxi.chat/v1/t2a_v2` 선택 가능 |
| ElevenLabs | `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}` |
| Alibaba Model Studio/Qwen | 기본 `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`, 국제 endpoint `https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`. 생성 후 서비스가 반환하는 임시 음성 URL도 다운로드하며 그 호스트는 서비스가 결정 |
| Doubao/Seed-TTS | `https://openspeech.bytedance.com/api/v1/tts` |
| VoxCPM·CosyVoice | 관리자가 지정하는 OpenAI 호환 `{base-url}/audio/speech`. VoxCPM은 `{base-url}/models`도 검사. 기본 URL이 비어 있어 설정 전 요청 없음 |

각 엔진은 자체 base URL을 지정할 수 있고 선택한 합성 엔진만 호출합니다. 사용자 지정 로컬·인트라넷 목적지도 본문, 모델 옵션과 자격 증명을 받으므로 신뢰하는 서비스만 사용하세요. 자격 증명이 있는 HTTP 요청은 쿠키를 저장하거나 리디렉션을 따르지 않습니다. 자격 증명 없는 Edge 버전 검사는 표준 클라이언트를 사용합니다. 프록시는 선택 경로와 엔진 설정에 따릅니다. `tts`나 엔진을 끄거나 설정을 비우면 중단됩니다.

## 푸시와 메일

`push`는 채널을 켜고 알림 이벤트 또는 테스트 전송을 실행할 때 제목·본문·채널 자격 증명을 보냅니다. 일부 서비스는 URL에 키나 토큰이 포함됩니다.

| 채널 | 고정·기본 목적지 |
| --- | --- |
| Bark | `https://api.day.app/push`, 자체 Bark 서버로 변경 가능 |
| DingTalk | `https://oapi.dingtalk.com/robot/send?access_token=...` |
| Feishu | `https://open.feishu.cn/open-apis/bot/v2/hook/{key}` |
| WeCom | `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...` |
| Telegram Bot | `https://api.telegram.org/bot{token}/sendMessage` |
| PushPlus | `https://www.pushplus.plus/send` |
| ServerChan Turbo | `https://sctapi.ftqq.com/{key}.send` |
| ServerChan 3 | `https://{uid}.push.ft07.com/send/{key}.send` |
| 사용자 지정 webhook | 관리자가 입력한 `http://` 또는 `https://` URL |

채널을 끄거나 자격 증명을 지우거나 `push`를 비활성화하면 요청이 멈춥니다. 로컬·인트라넷 사용자 지정 목적지도 알림과 채널 자격 증명을 받으며 HTTP는 전송 암호화가 없습니다. 신뢰하는 목적지만 사용하세요. 푸시는 쿠키를 저장하거나 리디렉션을 따르지 않습니다.

`mail`은 SMTP 설정 테스트나 실제 알림 발송 때만 연결합니다. 선택한 공급자에게 SMTP 자격 증명, 수신자, 제목과 본문을 보냅니다.

| 프리셋 | 기본 SMTP 목적지 |
| --- | --- |
| NetEase | `smtp.163.com:465`, `smtp.126.com:465`, `smtp.yeah.net:465`, `smtp.qiye.163.com:465` |
| Tencent | `smtp.qq.com:465`, `smtp.exmail.qq.com:465` |
| Sina | `smtp.sina.com:465` |
| Gmail / Google Workspace | `smtp.gmail.com:587` |
| Outlook | `smtp-mail.outlook.com:587` |
| Microsoft 365 | `smtp.office365.com:587` |
| iCloud | `smtp.mail.me.com:587` |
| Yahoo | `smtp.mail.yahoo.com:465` |
| Alibaba 기업 메일 | `smtp.qiye.aliyun.com:465` |

관리자는 임의 SMTP 호스트·포트와 별도 SOCKS 프록시를 지정할 수 있습니다. 메일 알림을 끄거나 메일 설정을 지우거나 `mail`을 비활성화하면 연결이 멈춥니다.

## 플러그인 마켓·FFmpeg·유저스크립트 업데이트

플러그인 관리 페이지를 열거나 수동으로 새로 고치거나 방문 기록 캐시에서 돌아오면, 애플리케이션과 같은 출처의 `/api/plugins/status`에서 상태를 읽고 현재 플러그인에 필요한 `/api/i18n/**` 사전을 불러옵니다. 수명 주기 작업이나 로컬 업로드가 끝나면 상태와 `/api/navigation` 탐색 항목을 다시 읽습니다. 네트워크 오류가 나도 실제 상태를 확인하며 쓰기 요청을 자동 재전송하지 않습니다. 조회를 교체하거나 페이지를 떠나면 이전 읽기를 취소하여 늦게 도착한 결과가 현재 페이지를 덮어쓰지 못하게 하지만, 이미 보낸 쓰기는 완료될 수 있습니다. 요청은 같은 출처의 세션 쿠키와 브라우저 네트워크 설정을 사용하며 백엔드의 외부 통신 프록시를 거치지 않습니다. 페이지를 닫으면 이후 페이지 조회가 중단됩니다.

마켓은 같은 출처의 `/api/plugin-market/**`에서 저장소, 카탈로그, 상세 정보와 패키지 정보를 조회하고, `/api/plugins/recovery`와 `/api/plugins/acquisitions`에서 복구 상태와 설치 기록을 읽습니다. 조회 교체, 상세 창 닫기 또는 페이지 이탈 시 해당 이전 읽기를 취소하고 돌아오면 다시 조회합니다. 브라우저의 취소가 서버에서 이미 시작한 저장소 접근까지 중단한다고 보장하지는 않습니다. 페이지를 떠나면 아직 제출하지 않은 설치·복구 확인은 무효화되지만, 이미 보낸 쓰기는 완료될 수 있습니다. 쓰기 후 현재 카탈로그, 복구 상태와 탐색 항목을 다시 읽으며 설치를 자동 재전송하지 않습니다. 같은 출처의 세션 쿠키를 사용합니다. 브라우저에서 앱으로 보내는 요청은 브라우저 네트워크 설정을, 호스트에서 저장소로 보내는 요청은 해당 저장소의 프록시 정책을 따릅니다.

관리자가 플러그인 관리 탐색 메뉴가 있는 페이지를 열거나 관리 페이지를 갱신하면 호스트가 검증된 출처의 설치 플러그인 업데이트를 검사합니다. 대응하는 활성 저장소의 카탈로그·서명·철회 정보만 조회합니다. 공식 키로 검증된 로컬 패키지는 공식 저장소를 사용합니다. 성공은 5분, 부분 실패는 30초 캐시하며 설치·저장소 변경 시 무효화합니다. 브라우저가 정기 폴링하거나 자동 설치하지는 않습니다. `plugin-catalog.enabled` 또는 해당 저장소를 끄면 이후 요청이 멈춥니다.

안정 기본 목록은 최신 버전, 최신 안정 버전과 고정 구형 SDK 집합 `1.0.0`, `1.1.0` 각각의 최신 호환 안정 버전을 보존합니다. 그 밖의 버전은 목록 옆 `history/<pluginId>-<sha256>.json`에서 기록 보기·특정 이전 버전 해석·채널 호환 후보 검색 때 읽습니다. 공식 기본 위치는 `raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/history/`입니다. 해시 이름의 기존 파일은 덮어쓰거나 자동 삭제하지 않습니다. 파일마다 1 MiB·900버전 한도와 서명된 주 목록의 크기·SHA-256 검증을 적용합니다. 저장소 프록시·공개 HTTPS·리디렉션 규칙을 따르며 애플리케이션 쿠키·자격 증명은 보내지 않습니다.

설치 계획 확인 뒤 작업 기록 생성은 로컬 메모리만 바꿉니다. 실행과 다운로드 후 검사는 카탈로그·철회 상태를 다시 읽습니다. 첫 실행 신뢰 확인 대기 패키지는 같은 SHA-256 확인을 이어갈 수 있도록 최대 하나를 10분간 보존하지만 설치 시 전체 검증은 반복합니다. 확인 취소·다른 설치 시작·서비스 종료 때도 해제합니다. 진행·작업 조회는 로컬 상태만 읽고 설치를 다시 제출하지 않습니다.

호스트는 네트워크 정책별 HTTP 연결을 재사용하고 프록시·시간 제한 변경 시 클라이언트를 교체합니다. 커뮤니티 인증은 매번 `generated/current.json`을 읽습니다. 해시·위치·서명이 같으면 검증된 디렉터리 루트와 shard를, 현재 인증과 해시가 같으면 설명자를 재사용합니다. 마켓 질의마다 저장소를 한 번 인증합니다. 저장소 전환·새로고침·페이지 이탈은 이전 카탈로그 요청을 취소하지만 이미 시작된 서버 외부 요청의 즉시 중단을 보장하지는 않습니다. 목적지·프록시·서명·철회 규칙은 그대로이며 앱 쿠키나 다운로드 자격 증명은 보내지 않습니다.

문서 지원 마켓은 같은 출처 `/api/plugin-market/content/**`로 목록이 참조하는 아이콘·스크린샷·README·전체 변경 기록·발행 설명을 읽습니다. 보이는 이미지는 필요할 때, 문서는 펼칠 때 읽습니다. 호스트는 저장소 네트워크 정책과 크기·SHA-256·이미지 형식을 검사합니다. 공식·커뮤니티 첨부는 보통 각 GitHub 버전 Release와 허용 CDN을 사용합니다. 브라우저는 검증 이미지와 격리된 정적 문서를 받고 임의 본문 이미지 URL을 읽지 않습니다. 앱 쿠키·다운로드 자격 증명은 보내지 않습니다. 마켓·저장소를 끄면 후속 읽기가 멈추고 외부 링크는 클릭해야 열립니다.

최신 버전이 SDK 비호환이면 같은 저장소의 이전 버전을 자동 조회하여 가장 높은 호환 설치 가능 버전을 선택합니다. 최신·선택 버전을 함께 표시하고 후보 없음과 조회 미완료를 구분합니다. 페이지형 저장소는 플러그인당 최대 100버전씩 10페이지를 더 읽을 수 있습니다. 한 페이지 요청의 호환 조회는 새 요청 시작에 10초 예산을 공유하며 이미 시작한 요청은 저장소 제한 시간을 따릅니다. 버전 메타데이터만 읽고 패키지는 사용자의 설치 확인 뒤 다운로드합니다. 설치된 높은 버전을 자동으로 낮추지 않습니다.

관리자의 명시적 철회 갱신은 설정된 출처의 `revocations.json`과 인접 `.sig`만 요청합니다. 임의 URL을 받지 않고 앱 쿠키·작품·자격 증명을 보내지 않으며 저장소 프록시·HTTPS·출처·크기 검사를 유지합니다. 마켓과 저장소 모두 활성화되어야 합니다. 일반 상태·작업 조회는 로컬만 읽으며 갱신을 예약하지 않습니다.

| 주체·목적지 | 목적·데이터·실행 조건·중단 방법 |
| --- | --- |
| 공식 마켓: 안정 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`, Nightly는 같은 위치 `nightly-manifest.json`. 패키지는 보통 GitHub Releases에서 `*.githubusercontent.com`으로 이동 가능 | 패키징된 앱 버전이 목록을 선택합니다. 마켓·공식 저장소는 기본 켜짐이며 열기·갱신은 목록, 명시적 설치는 패키지를 읽습니다. 시작 자체는 요청하지 않습니다. 서명·SHA-256·크기를 검사하고 최대 5회 리디렉션을 매번 검증합니다. 마켓을 끄면 중단됩니다 |
| GUI 설명자 가져오기: 관리자가 입력한 공개 HTTPS `repository.json` | 게시자, 카탈로그, 철회·갱신 주소, 실제 호스트와 전체 키 지문을 미리 봅니다. 쿠키·계정·작품·경로·자격 증명을 보내거나 `repository.json.sig`를 요청하지 않습니다. 미리보기·확인·새 스냅샷 설정 저장 때 다시 읽고 동일 SHA-256을 요구합니다. 64 KiB 한도와 모든 주소의 공개 HTTPS·SSRF 검사 적용. `DIRECT_STRICT`는 리디렉션 없음, `GITHUB_RELEASES`는 고정 GitHub 범위 안 1회 허용. 취소·미확인은 저장소를 저장하거나 활성화하지 않습니다 |
| 사용자 지정 마켓: 신뢰한 설명자의 HTTPS catalog, 선택적 `revocations.json` / `repository-update.json`와 인접 `.sig`, 목록의 JAR/ZIP | `manifest-v1`은 서명 목록, `paged-v2`는 제한된 목록·상세·정확한 버전 페이지를 읽습니다. 목록·상세·버전 사실 조회마다 필요한 철회 서명을 갱신하고 응답당 하나의 스냅샷을 사용합니다. 설치 전 의존성 해석과 다운로드 뒤에도 확인합니다. YANKED/REVOKED는 새 설치를 막지만 진단 기록에는 남습니다. 유효 스냅샷 없음·24시간 유예 초과는 새 설치를 거부하고 갱신 실패도 기존 제한을 보존합니다. 패키지 크기·해시·게시자 서명·설명자를 검사하며 신뢰 저장소 재가져오기는 연속성 증명을 읽을 수 있습니다. 탐색·검색·페이지·상세·설치·재가져오기가 유발합니다. 시작은 마지막 로컬 철회 정보만 읽습니다. 설명자가 지정한 안전 정책을 따르며 저장소 비활성·삭제 또는 마켓 끄기로 중단됩니다 |
| 커뮤니티: `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/` 아래 `generated/current.json`, `generated/generations/<sequence>/directory.json`, 필요 shard, `generated/repository.json`, catalog·철회 파일 | 독립 커뮤니티 루트로 서명·순서·설명자 신원·키 지문을 검사합니다. 리뷰·제출·과거 게시자 기록은 저장소의 제어된 상대 경로, 패키지는 서명 목록 URL입니다. 버전 상세는 최대 리뷰 1개를 읽고 설치는 원 게시자 서명과 실제 선언도 확인합니다. 공개 경로·일반 연결 정보만 보내며 쿠키·개인 키·앱 자격 증명은 없습니다. 기본 활성, 커뮤니티 탐색·버전·설치·제3자 저장소 인증 미리보기/확인 때 요청합니다. 시작은 로컬 검증만 하고 자동 설치하지 않습니다. 호스트 프록시와 `GITHUB_RELEASES` 공개 HTTPS·GitHub 호스트·1회 리디렉션 규칙을 사용합니다. `plugin-catalog.community-repository-enabled`는 커뮤니티 마켓을, `plugin-catalog.enabled`는 제3자 인증 질의까지 중단합니다. 실패하면 마지막 검증 bucket을 보존하며 손상 상태로 롤백 방지 기준을 초기화하지 않습니다 |
| GUI FFmpeg 설치: `https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/ffmpeg-release.json`, 인접 `ffmpeg-release.json.sig`, `ffmpeg-{windows-x64,linux-x64,linux-arm64,macos-x64,macos-arm64}.zip`와 CDN | 명시적 자동 설치 선택 때만 현재 플랫폼 파일을 읽습니다. 공식 안정 소스 빌드의 목록 Ed25519 서명과 정확한 파일명·크기·SHA-256을 추출 전에 검사합니다. FFmpeg 설치기 User-Agent·IP 등만 보내며 Pixiv·계정 자격 증명은 없습니다. 시작 시 다운로드하지 않으며 미지원 시스템은 수동 설치입니다. 호스트 프록시를 사용하고 자동 설치를 시작하지 않으면 요청하지 않습니다 |
| Windows Setup의 선택 FFmpeg 작업: 위 목록·서명과 고정 `ffmpeg-windows-x64.zip`, CDN | 사용자가 작업을 선택할 때 설치기 내장 공식 키와 파일명·크기·해시를 검증해 앱 도구 폴더에 풉니다. 목적지에 앱·Pixiv 자격 증명은 보내지 않지만 시스템 프록시는 현재 Windows 사용자 기본 프록시 자격 증명을 사용할 수 있습니다. 활성 시스템 HTTP/HTTPS 프록시를 우선하고 없으면 직접 연결합니다. 작업 미선택 시 요청하지 않습니다 |
| 독립 유저스크립트 관리자: `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js` | 여섯 독립 스크립트를 관리자 업데이트 정책에 따라 검사·다운로드합니다. 자동 업데이트를 끄거나 스크립트를 제거하면 중단됩니다 |
| All-in-One 관리자: `https://github.com/Sywyar/PixivDownloader/releases/latest/download/Pixiv%20All-in-One.user.js` | 생성 번들 메타데이터가 이 주소를 선언하므로 관리자가 요청할 수 있지만 성공은 첨부 존재 여부에 따릅니다. 현재 Release workflow는 유저스크립트 첨부를 올리지 않습니다. 백엔드 설치 시 업데이트 URL을 그 백엔드로 바꿉니다 |

GUI 미리보기만으로 설정은 바뀌지 않습니다. 확인한 초안도 설정 저장 시에만 기록되며 새 설명자 스냅샷을 저장할 때 다시 읽어 같은 SHA-256인지 확인합니다.

## 공식 플러그인의 선택 설문(PostHog)

레이아웃 설문은 `download-workbench`, 다중 사용자 모드 유지 설문은 관리자 받은편지함에만 나타나는 `multi-mode-decision-survey`가 소유합니다. 별도 `posthog` 플러그인은 JavaScript SDK와 호출자별 격리 클라이언트를 제공합니다. SDK는 로컬 정적 리소스이며 CDN에서 받지 않습니다.

각 게시 플러그인의 `posthog-config.js`가 프로젝트 토큰, 설문 ID, `apiHost=https://layout-survey.sywyar.top`, `uiHost=https://us.posthog.com`을 고정 관리합니다. 브라우저 공개 값이며 GitHub Actions·스크립트·properties에서 비밀로 주입하지 않습니다. 별도 생성 파일 `release-activation.js`의 활성 비트는 일반 소스·fork 빌드에서 `false`입니다. 공식 Release·Nightly·공식 플러그인 배포는 버전 관리된 Maven `official-surveys` profile로 비트만 `true`로 만들며 네 매개변수는 고치지 않습니다.

시작 시 설문 메시지 등록·멱등 저장은 로컬 선언만 읽습니다. 활성 설문이 있는 받은편지함을 열어 내장 설문을 미리 준비하거나 워크벤치가 레이아웃 설문을 미리 읽거나 실행할 때 브라우저가 위 API/UI 호스트에 연결합니다. 게시 플러그인의 `beforeSend` 허용 목록이 전송을 제한하며 호스트 프록시는 사용하지 않습니다.

| 설문 | 전송 내용 |
| --- | --- |
| 다중 사용자 모드 유지 | 선택·입력 답변, 설문 ID, 설문 범위 익명 식별자, 전달 중복 제거용 안정 이벤트 ID, 완료 상태, 이벤트 시각·이름과 공개 프로젝트 토큰 |
| 레이아웃 | 답변, 설문 ID, 설문 범위 익명 식별자, 중복 제거 이벤트 ID, 앱 버전, 현재 레이아웃, 설문 schema 버전, 이벤트 시각·이름과 공개 프로젝트 토큰 |

두 설문은 설치 신원에서 단방향으로 파생하고 현재 설문·campaign에 한정한 제출 신원을 사용합니다. 원시 설치 신원, 계정, 쿠키, 작품과 로컬 경로는 보내지 않습니다.

게시 플러그인이 메시지를 제공하는 동안 로컬 메시지가 유지되고 제공이 사라지면 수명 주기 정리로 제거됩니다. 내장 페이지가 PostHog의 종료·삭제를 확인하면 로컬 숨김 표식을 저장해 다시 표시하지 않습니다. 일시적 연결 실패는 메시지를 지우지 않고 나중에 열 때 재시도합니다. `posthog`가 없거나 꺼지면 설문은 조용히 기능을 중단합니다. 열린 페이지에 이미 로드한 JavaScript는 즉시 철회할 수 없으므로 비활성화의 전체 효과는 새로고침 뒤 적용됩니다.

## 고정 외부 목적지가 없는 공식 플러그인

`gallery-tools`의 통계·중복 탐지는 로컬 DB·파일·해시를 사용합니다. `stats`와 `duplicate`는 별도 설치 패키지가 아닌 기능입니다. `gallery`는 로컬 작품·미디어를 탐색합니다. `gui-swing`과 `gui-compose`는 각자 로컬 창·트레이·테마·플랫폼 리소스를 사용하며 고정 외부 런타임 목적지를 추가하지 않습니다. `recovery-sentinel`은 일반 배포에 없는 복구 모드 테스트 fixture입니다. 이들의 페이지는 현재 인스턴스의 같은 출처 API를 호출할 수 있습니다.

## PostgreSQL 주 데이터베이스

기본 SQLite는 로컬 파일을 읽습니다. 관리자가 PostgreSQL 플러그인을 설치·선택하면 JDBC가 `database.jdbc-url`의 서버에 연결합니다. 기본 포트는 5432이며 호스트·포트·DB·주 schema는 관리자가 선택합니다.

데스크톱 설정이나 마켓의 연결 검사는 저장 전 초안 목적지에 초안 자격 증명을 보내 버전·인코딩·schema·신원을 읽습니다. 테이블 생성이나 활성 풀 교체는 하지 않습니다. 검사 후 계속은 원본 구조, 모든 private DB, 목적지가 비었는지 또는 백업을 통한 안전 교체가 가능한지도 확인합니다. 재시도는 커밋된 목적지 데이터를 읽어 원본 revision과 데이터 해시를 검증합니다. 확인 전에는 데이터 이전이나 private 자격 증명·역할·schema 생성이 없습니다. PostgreSQL 연결·로그인·socket 대기는 각각 최대 10초이며 더 짧은 설정은 유지합니다. 이는 마이그레이션 전체 제한 시간이 아닙니다.

확인 뒤 다른 페이지 설정을 저장하고 호스트가 업무를 중단한 뒤 전체 재시작하여 이전합니다. 원본·대상 provider가 읽기·쓰기·검증을 수행합니다. 지원되는 비어 있지 않은 대상은 먼저 백업하고 가져온 데이터 검증 뒤 하나씩 교체합니다. 인증 정보, SQL, 작품 메타데이터, 알림, 일정 상태·자격 증명과 선언된 플러그인 private 데이터가 전송됩니다. 모든 주·private DB가 검증된 뒤에만 설정을 전환합니다. 실패하면 원본 설정과 커밋된 목적지 데이터를 보존합니다. SQLite↔PostgreSQL 양방향을 지원하며 별도 오프라인 도구도 있습니다. 시작·schema 갱신·일상 읽기/쓰기·유지보수도 선택 provider로 연결합니다. private 저장소는 owner별 schema·제한된 로그인을 쓰고 암호는 로컬 암호화 저장소에 있으며 일반 플러그인에 주 계정을 주지 않습니다.

JDBC TCP/TLS는 Pixiv HTTP 프록시 밖에서 연결합니다. `sslmode`와 선택 `sslrootcert`로 TLS를 설정하며 원격은 `verify-full`을 사용하세요. 암호는 설정이나 `DATABASE_PASSWORD`로 별도 전달하고 URL 자격 증명은 거부합니다. 연결·provider 불가는 복구 모드로 들어가 DB 의존 업무·배경 작업을 멈추며 다른 엔진을 자동 선택하지 않습니다. 확인 전에 취소하면 이전을 포기할 수 있습니다. 원격 접근을 끊으려면 앱을 중지하고 DB 선택을 바로잡은 뒤 전체 재시작하세요. 설정만 바꾸면 기록이 복사되지는 않습니다. [설정 참고](/ko/configuration)를 확인하세요.

## 로컬·같은 출처·사용자 지정 목적지

이미지 분류기는 양수 작품 ID를 얻은 경우에만 상세 정보를 조회하며, 서비스가 유효한 ID를 반환하지 않으면 폴더 이름을 확인합니다.

데스크톱 상태·동작은 `http://127.0.0.1:{ephemeral-port}/api/gui/**`로 현재 백엔드 GUI 토큰, 언어, 동작 인자와 선언된 동작의 플러그인 owner를 보냅니다. 데스크톱 모드가 기본 생성하는 listener의 포트는 OS가 정하고 백엔드 시작마다 다시 읽으며 설정에 저장하지 않습니다. 시스템·호스트 프록시를 우회하고 리디렉션과 쓰기 자동 재실행을 거부합니다. `ssl.domain`이나 공개 HTTPS 포트를 쓰지 않습니다. loopback에만 바인딩하고 비GUI 경로·전달 헤더를 거부하며 로컬 출처·Host/Origin·토큰 검사를 유지합니다. `--no-gui`와 headless에서는 생성하지 않으며 원격 대체 설정은 없습니다.

Web·플러그인 프런트엔드는 브라우저의 같은 출처 `/api/**`, 정적 리소스와 SSE를 사용합니다. 초대·setup·데스크톱 Web 링크는 실제 공개 listen 프로토콜·포트와 관리자의 `ssl.domain`(기본 `localhost`)으로 만듭니다. 개발 포트가 다르면 설정 포트를 바꾸지 않고 현재 인스턴스를 따릅니다. HTTP→HTTPS 리디렉션은 지정된 공개 listener에만 적용하며 GUI 로컬 통신에는 적용하지 않습니다.

Ollama, LM Studio, VoxCPM, CosyVoice와 스크립트 백엔드는 로컬일 수 있지만 URL을 원격으로 바꾸면 그 서버가 수신자가 됩니다. 이미지 분류기의 `server.url`이 비어 있으면 현재 인스턴스를, 명시하면 다른 PixivDownloader를 사용하며 기존 설정은 유지합니다. 작품 정보를 읽고 이동 후 새 경로를 제출하므로 원격 대상은 이 데이터를 받습니다. 분류기를 닫으면 요청이 멈춥니다.

Webhook, AI/TTS URL, 플러그인 저장소, Bark, SMTP, SOCKS와 프록시 주소는 관리자가 정하므로 완결된 고정 도메인 허용 목록이 될 수 없습니다. 업데이트 목록도 사용자 지정 가능하지만 공개 HTTPS와 공식 서명을 요구합니다. 루트 개발 도구 `cors-js-runner.html`은 조작자가 입력한 임의 URL에 요청하며 표준 사용자 런타임에 포함되지 않습니다.

## 공식 CAPTCHA 플러그인의 런타임 접근

선택 사항인 공식 `captcha` 플러그인은 기본 설치되지 않습니다. 관리자가 Turnstile을 설정하고 폼 연결을 게시하면 챌린지 시 브라우저가 `https://challenges.cloudflare.com/turnstile/v0/api.js`와 같은 출처의 iframe을 로드합니다. 사이트 키, 작업, 시도 ID와 업체 프로토콜이 사용하는 브라우저 네트워크 정보를 보냅니다. 응답 검증 시에만 플러그인이 같은 출처의 `/turnstile/v0/siteverify`로 비밀 키와 토큰을 POST합니다. 로그인 비밀번호, Cookie, 전체 업무 요청은 보내지 않습니다. 브라우저는 자체 네트워크 설정을 사용하고 서버는 앱 프록시를 상속하는 관리형 HTTP를 사용하며 리디렉션과 Cookie를 금지합니다. 연결을 삭제하고 게시하면 해당 페이지의 온라인 검증이 중지됩니다. 플러그인을 중지하면 보호된 작업이 거부됩니다. ALTCHA SHA-256/PBKDF2 Worker는 플러그인에 포함되어 현재 인스턴스에만 접근하며 외부 서비스를 요청하지 않습니다.

reCAPTCHA v2를 연결하고 게시한 후 인증을 시작하면 `https://www.google.com/recaptcha/api.js?render=explicit`를 로드합니다. 스크립트와 내장 리소스는 `www.google.com`, `www.gstatic.com`, `recaptcha.google.com`을 사용합니다. 브라우저는 사이트 식별자와 Google 프로토콜에서 수집한 정보를 보냅니다. 제출 시 서버의 관리형 HTTP는 `https://www.google.com/recaptcha/api/siteverify`에 비밀 키와 토큰만 POST하며 로그인 비밀번호, Cookie, 전체 폼, 추가 remoteip는 보내지 않습니다. 앱 프록시를 따르고 리디렉션과 Cookie를 사용하지 않습니다. 구성 요소를 제거하고 다시 게시하면 해당 페이지의 Google 연결이 중단됩니다. Hutool 문자와 Tianai 퍼즐은 로컬에서 생성하며 브라우저는 동일 출처의 단기 이미지 주소만 요청하고 정답을 타사로 보내지 않습니다.

## 개발·빌드·배포 중의 네트워크 접근

공식 모듈 `pixivdownload-plugin-captcha` 또는 독립 SDK 프로젝트 `examples/captcha-plugin` 빌드는 Maven의 저장소, 미러, 프록시 설정으로 `org.altcha:altcha`, `org.json:json` 및 빌드 의존성을 받습니다. 리소스 생성 시 `npm ci`를 실행하여 잠금 파일에 따라 `altcha`와 전이 의존성을 받으며 현재 호스트는 `registry.npmmirror.com`입니다. 패키지 좌표와 설정된 패키지 관리자 인증을 보내지만 프로젝트 자격 증명은 업로드하지 않습니다. 공식 Worker와 라이선스를 플러그인에 포함합니다. `page-editor-acceptance.mjs`의 CAPTCHA 검증을 명시적으로 실행하면 공개 테스트 키로 Cloudflare 공식 테스트 컴포넌트와 Siteverify에 접근합니다. 일반 앱 시작 시에는 실행하지 않습니다. 빌드나 테스트를 취소하면 이후 요청이 중지되며 검증된 로컬 캐시로 다운로드를 줄일 수 있습니다.

이미지 구성 요소는 Maven 의존성 `cn.hutool:hutool-captcha`(및 `hutool-core`), `cloud.tianai.captcha:tianai-captcha`를 추가하며 설정된 저장소, 미러, 프록시를 사용합니다. CAPTCHA 브라우저 검증을 명시적으로 실행하면 공개 테스트 키로 Google 공식 테스트 위젯과 Siteverify에도 연결합니다. 일반 시작 시에는 실행하지 않습니다.

이 절의 도구는 설치된 앱의 일반 실행과 별개입니다. 명시한 명령·workflow를 실행하거나 해당 이벤트가 발생할 때만 연결합니다. 빌드 머신의 프록시·미러·패키지 관리자 설정으로 최종 호스트가 바뀔 수 있으며 전이 의존성의 모든 목적지를 소스에서 고정 목록으로 확정할 수 없습니다.

### 의존성·검증·문서 첨부의 공통 동작

Maven은 PostgreSQL의 `org.postgresql:postgresql`(`org/postgresql/postgresql/<version>/`), SQLite의 `org.xerial:sqlite-jdbc`(`org/xerial/sqlite-jdbc/<version>/`)를 캐시가 없는 소스 빌드·의존성 준비 때 받습니다. 기본은 Maven Central이며 실제 저장소·미러·프록시는 Maven 설정에 따릅니다. 앱 DB 자격 증명은 보내지 않습니다. 독립 제3 provider 계약 테스트의 `com.h2database:h2`(`com/h2database/h2/<version>/`)도 같은 경로를 사용하지만 테스트 전용이며 배포되지 않고 격리된 프로세스 내부 메모리 DB만 사용합니다.

실제 DB 검증은 명시한 격리 서비스에서 수행합니다. `postgres:17` 컨테이너를 선택하면 Docker 자체 레지스트리·인증·프록시 설정으로 Docker Hub 레지스트리·토큰 서비스와 이미지 CDN에 연결합니다. 테스트는 `PIXIV_TEST_POSTGRES_URL`과 테스트 자격 증명만 사용합니다. 테스트·컨테이너를 중단하면 접근이 멈추며 일반 앱 시작은 DB 이미지를 내려받지 않습니다.

플러그인 버전 검사는 GitHub CLI로 `api.github.com/repos/Sywyar/PixivDownloader-plugins/releases`를 페이지 조회합니다. CI GitHub 자격 증명 또는 로컬 CLI 로그인·프록시를 사용합니다. 아티팩트 검사는 선언된 최소 SDK의 고정 Maven 좌표를 읽으며 현재 소스 SDK와 같으면 현재 빌드 결과를 사용합니다. QG나 명시적 검사 명령이 유발하며 실패하면 검증을 막습니다. 명령·workflow를 취소하면 이후 요청이 멈춥니다.

목록 생성은 GitHub Contents API로 공개 주 목록·서명·참조 기록을 읽고 검증하여 이전 버전을 보존합니다. Release 패키징도 주 목록에 호환 패키지가 없으면 같은 출처 기록을 검증하고 선택 패키지를 내려받습니다. 각 배포·패키징 스크립트의 GitHub 인증·프록시·서명 규칙을 유지합니다.

문서 제출은 고정한 소스와 명시적으로 선택한 로컬 파일을 읽습니다. 작성자 확인 뒤에만 커뮤니티 Node downloader가 문서의 공개 HTTPS 이미지를 아래 제한으로 받습니다. 제출 확인 뒤 GitHub CLI와 Release upload endpoint로 숫자 신원을 검증한 소스 저장소의 `market-content-<plugin>-<version>` Release에 검토된 바이트를 올립니다. 리뷰·커뮤니티 배포는 등록된 첨부를 같은 커뮤니티 버전 Release로 복사하고 공개 다운로드를 검증합니다. GitHub 인증은 API·업로드 endpoint에만 보내며 공개 파일 다운로드에는 없습니다. 쓰기 확인을 취소하면 배포하지 않습니다.

공식 플러그인 배포·목록 생성은 SDK 도구로 같은 플러그인 Release의 `market-content.json`과 등록 첨부를 읽습니다. Release job이나 명시적 스크립트가 유발하며 Java 시스템 프록시, GitHub 호스트 허용 목록과 리디렉션마다 TLS 검증을 사용하고 앱 자격 증명은 없습니다. 콘텐츠 다운로드 작업마다 총 5분, 연결·읽기 각각 최대 15초입니다. 메타데이터 64 KiB, 문서 합계 4 MiB, 문서 이미지 8 MiB, 마켓 이미지 8 MiB 한도와 각 해시를 검사합니다. 공개 검증 실패는 완료를 막습니다.

### 커뮤니티 도구의 전송·재시도

공개 패키지·소스 ZIP·빌드 도구의 Node downloader는 먼저 `no_proxy` / `NO_PROXY`를 적용하고 `https_proxy`, `HTTPS_PROXY`, `all_proxy`, `ALL_PROXY` 순서로 읽습니다. 소문자 변수가 우선합니다. 변수가 없으면 Windows는 현재 사용자 시스템 프록시·우회 설정을 읽고 다른 OS는 직접 연결합니다. 명시적 빈 프록시나 `NO_PROXY=*`도 직접 연결입니다. HTTP/HTTPS 프록시는 지원하고 SOCKS는 지원하지 않습니다. 프록시 실패 시 직접 연결로 전환하지 않습니다.

CONNECT는 검증된 공개 IP를, 원본 TLS는 원래 호스트 이름을 검사합니다. 명시적 프록시 자격 증명은 프록시에만 보내고 파일 다운로드에 GitHub 자격 증명·쿠키는 없습니다. 시스템 프록시 조회도 다운로드 총 60초에 포함됩니다. 격리 빌드 컨테이너, GitHub CLI, Git과 PowerShell launcher는 각자의 설정을 따릅니다.

연결 중단·DNS·연결 실패·시간 초과·HTTP 408/500/502/503/504는 최대 3회 시도하며 1초·2초 기다립니다. 조회·리디렉션·본문을 포함한 전체 시도는 60초를 공유합니다. 매 시도·매 hop에서 공개 주소, 원본 TLS, 크기·해시를 검사하며 자신이 만든 부분 파일을 제거합니다. 인증서·해시·크기·URL·로컬 파일 오류와 그 밖의 HTTP 상태는 재시도하지 않습니다. 최초 취득·제출 검증·최종 확인 모두 같습니다. 자동 복구 소진 시 실패 하위 단계, 회차와 누적 시도를 표시합니다. 수동 재시도는 해당 요청·파일에 새 예산만 주고 완료 단계를 유지합니다. 저장 후 종료는 진행을 보존하며 PowerShell도 파일별 회차·누적 시도를 표시합니다.

SDK 준비·커뮤니티 데이터·키 입력보다 먼저 `GET /user`로 현재 GitHub CLI 로그인을 확인합니다. 자격 증명 누락·거부 시 다른 터미널에서 로그인 후 다시 확인하거나 저장·종료할 수 있습니다. 환경 변수 자격 증명 변경은 wizard 재시작이 필요합니다. 권한·미접근 객체·속도 제한·프록시·DNS·TLS·연결 중단을 구분하며 인증서·요청 검증·상태 충돌·로컬 도구/파일 오류는 네트워크 재시도하지 않습니다. 진단은 제어된 코드·상태·종료 코드·단계만 표시하고 원시 명령·토큰·질의 포함 URL은 숨깁니다. Git commit 신원과 remote 인증은 별도이며 CLI 로그인은 Git 자격 증명을 설정하지 않습니다.

제출 API 읽기와 후보 바이너리 다운로드는 GitHub CLI를 사용합니다. GET의 408/500/502/503/504와 확인된 일시 연결·DNS·timeout·EOF 오류는 동일 60초 안 최대 3회, 1초·2초 대기입니다. 권한·속도 제한·인증서·분류되지 않은 전송 오류는 자동 재시도하지 않습니다. 복구 가능 오류는 재개 초기화에서도 해당 요청만 재시도하거나 저장·종료할 수 있습니다.

Fork 생성·후보 생성·첨부 업로드·공개·Git 전송·PR 생성·철회는 단계별로 복구합니다. 쓰기 재시도 전에 동일 객체의 신원·내용을 다시 읽고 일시 오류에만 회차당 최대 3회 시도합니다. HTTP 클라이언트가 변경 요청을 무조건 반복하지 않습니다. 중단된 소스 CI 관찰은 승인된 같은 run과 원래 마감 시각을 사용하며 CI를 다시 시작하지 않습니다. 로컬 진행 재개도 GitHub에서 소스·서명을 확인하며 저장 기록은 원격 쓰기 승인이 아닙니다.

커뮤니티 mainline이 진행되면 준비한 내용·원 요청 바이트를 유지하고 작업당 최대 세 복구 회차에 현재 계정·브랜치·트리·원 PR을 읽습니다. 제출 브랜치 재사용 전에 `GET /repos/Sywyar/PixivDownloader-community-plugins/compare/<original-base-SHA>...<current-SHA>`로 조상을 확인합니다. 기존 요청의 작성자·목적 저장소·모든 바이트가 일치할 때만 재사용하고 그렇지 않으면 최신 상태에서 재검증 후 쓰기를 확인받습니다. 신원·내용·브랜치 충돌은 중단하고 회차 소진 시 무한 폴링 대신 진행을 보존합니다. 각 API는 기존 CLI 인증·프록시·TLS, 60초·32 MiB 한도입니다. wizard 종료로 이후 접근이 멈춥니다.

리뷰 완료·서명 상태 작업이 PR에 commit을 추가한 뒤 PR·원시 Git ref·mainline을 최대 5회, 1초 간격으로 읽습니다. ref가 새 commit인데 PR 화면만 정확한 부모를 가리킬 때에만 기다리며 다른 변경이면 중단합니다. 쓰기 응답 유실도 ref 조회로 확인하고 push를 반복하지 않습니다. 공개와 PR 알림은 별도 단계입니다. 알림은 `pull-requests: write` job token을 사용하며 실패해도 공개 첨부를 유지하고 기존 결과를 읽어 복구합니다. mainline이 바뀌면 원 run·소스 조상·보호 코드 불변을 검증하고 PR head·상태를 다시 읽습니다. 빈 알림은 요청하지 않습니다. 서명과 상태 쓰기는 여전히 현재 master를 요구합니다.

대기 중인 Gate의 head·base·열림 상태가 바뀌면 현재 PR, 파일, 리뷰와 필수 서명 증명을 CLI로 다시 읽습니다. 보호 job 자격 증명으로 커뮤니티와 검증된 fork만 조회하고 60초·32 MiB 한도를 유지합니다. 대기 job당 전체 버전 재검증은 한 번이며 도중 추가 변경은 승인을 막습니다. 기존 Gate 이벤트·수동 실행이 유발하고 취소하면 이후 요청이 멈춥니다. API 실패 로그는 분류 원인·method·리소스 종류·HTTP 상태·종료 코드만 보존하고 전체 질의·본문·자격 증명·원시 출력은 제외합니다.

합쳐진 결과 검증은 `api.github.com/repos/Sywyar/PixivDownloader-community-plugins/git/commits/<SHA>`, `git/trees/<SHA>?recursive=1`에서 실제 merge 부모와 전체 트리를 읽어 첫 부모와 생성 head의 변경을 계산합니다. 과거 PR의 `/pulls/<number>/files`는 읽지 않지만 열린 PR 리뷰는 원시 파일 목록을 사용합니다. 공개·mainline Gate·명시적 receipt 마이그레이션이 유발하며 CLI 인증·프록시·TLS와 요청당 60초·32 MiB를 사용합니다. 경로·commit·GitHub 자격 증명만 보내며 중단하면 이후 접근이 멈춥니다.

등록 버전 표시 시 `GET /repos/Sywyar/PixivDownloader-community-plugins/releases/tags/<tag>`와 `/releases/<id>/assets`를 읽습니다. tag는 보호된 배포 기록의 원 게시자·플러그인 ID·버전에서 만듭니다. CLI 로그인·프록시와 60초·32 MiB 한도로 공개 상태·파일명·크기·해시를 비교하며 패키지를 받거나 Draft를 공개하지 않습니다. 누락·읽기 실패는 공개 여부 미확정이지 배포 실패 증거가 아닙니다. 이전 요청 복구·이전 요청 탐색도 현재 소유자·게시자·긴급 키 차단을 읽고 오래된 요청에는 사유와 원 PR을 표시합니다.

### 커뮤니티 제출·심사·공개

아래 API의 `api.github.com`, 업로드의 `uploads.github.com`, GitHub가 돌려주는 첨부·Actions 저장소 주소는 서로 다른 전송입니다. 별도 언급이 없으면 GitHub CLI·Git·공식 Actions·runner의 인증·프록시·TLS 설정을 따릅니다. 공개 Node 파일 다운로드는 앞 절의 익명·공개 주소·TLS·재시도 제한을 사용합니다. 도구를 실행하지 않거나 해당 workflow를 비활성화하면 이후 자동 요청을 막을 수 있지만 이미 승인·완료된 쓰기를 되돌리지는 않습니다.

#### 제출 launcher와 고정 SDK

튜토리얼의 수동 `irm ... | iex`는 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/tools/submit.ps1`을 PowerShell `Invoke-RestMethod`로 UTF-8 다운로드하고 `Invoke-Expression`에 전달합니다. 최초 요청은 PowerShell 자체 프록시·리디렉션·시간 제한을 따르며 튜토리얼은 토큰·개인 키·본문을 보내지 않습니다. 보호된 master와 GitHub HTTPS가 launcher 코드·내장 공개 키의 신뢰 근거입니다. 시작 디렉터리를 확인한 뒤 고정 도구 취득을 시작합니다. 검증된 wizard는 프로젝트 표식을 검사하고 SDK 프로젝트가 없으면 관리 작업만 제공합니다. 명령을 실행하지 않으면 요청하지 않습니다.

Launcher는 매 수동 실행에 고정 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/tools/submission-channel.json`을 최대 4 KiB로 익명 조회합니다. 내장 키로 서명·만료·기존 수락 순서를 검사한 뒤 완전한 source commit과 SHA-256이 지정한 `<full-commit>/`의 manifest·도구를 선택합니다. 로컬·캐시는 크기·해시 검증 후 재사용합니다. 내부 다운로드는 플랫폼 프록시와 실행 중 연결 재사용을 지원하지만 쿠키·기본 자격 증명·리디렉션은 사용하지 않습니다.

파일별 회차는 총 60초, 최대 3회와 1초·2초 대기입니다. 연결·읽기 무진행 15초면 조기 재시도하며 데이터 수신 때 idle timer를 초기화합니다. 일시 전송 오류·408/500/502/503/504도 대상입니다. 인증서·서명·해시·크기 오류는 재시도하지 않습니다. 진행과 실패 파일·단계·이유·실제 시도 수를 표시하고 시간 소진·횟수 소진·재시도 불가를 구분합니다. 복구 가능 회차 소진 시 대화형 터미널은 현재 파일 재시도 또는 기본 선택인 종료를 제공합니다. 비대화형은 실패 반환합니다. 수동 재시도만 새 회차를 시작하며 검증 캐시는 유지합니다. 실행 직전에 채널 서명·만료를 다시 검사하며 실패 시 오래된 도구로 전환하지 않습니다. 상태·도구는 로컬 앱 데이터 디렉터리에 저장합니다. 도구 갱신은 서명 채널, 프로토콜·루트 변경은 maintenance PR의 launcher 갱신을 통해 다음 같은 명령 실행에 전달됩니다.

Wizard와 CI는 하나의 도구 manifest에서 고정 SDK 버전·소스 commit·ZIP·리소스 해시를 읽습니다. 캐시 누락 시 `https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/sdk-api-v<pinned-version>/PixivDownloader-Plugin-SDK-<pinned-version>.zip`와 Release CDN에서 익명으로 받고 검증 후 도구·계약·빌드 스크립트만 풉니다. 서명 CLI도 같은 SDK입니다. Node의 시스템/환경 프록시·공개 주소·TLS·60초/3회 규칙을 사용하며 GitHub 토큰·소스·개인 키·암호는 보내지 않습니다. 사용마다 선택 도구 폴더의 `target/sdk/` 캐시를 검증합니다. 유효 캐시는 Release 요청을 피하고 해시 불일치는 실행을 막습니다.

런타임 취득은 latest를 조회하지 않습니다. 유지 관리자가 명시적으로 `sdk-release.mjs --version`을 실행할 때만 현재 CLI 로그인으로 지정된 공개 Release/tag API를 읽고 새 참조를 만듭니다. maintenance PR로 반영한 manifest는 서명 채널이 선택합니다.

`Sign submission tool channel`은 유지 관리자의 수동 실행으로 `https://github.com/Sywyar/PixivDownloader-community-plugins` 보호 소스를 읽고 launcher를 테스트합니다. release Environment 승인 뒤 선택한 mainline 조상의 도구 manifest를 서명합니다. checkout은 읽기 전용 job token, 도구 설치는 공식 Actions 설정입니다. artifact에는 서명 채널만 포함하여 7일 보관하고 job artifact 자격 증명으로 업로드합니다. 전용 개인 키는 로컬 서명 프로세스에만 전달하며 선택 도구 실행·branch push는 하지 않습니다. 채널 반영은 별도 PR입니다.

#### 로컬 wizard와 소유권 이전

수동 실행한 wizard는 `api.github.com`의 사용자·조직·저장소·Git tree/blob/ref·commit·Actions run/attempt/jobs·Release/asset·fork·PR API, 커뮤니티/현재 계정 fork의 Git HTTPS, 고정 commit 소스 ZIP·후보 URL과 `uploads.github.com`을 사용합니다. 숫자 신원·성공 CI·tag commit·첨부를 검사하고 Draft 첨부는 ID로 CLI 인증 다운로드합니다. GitHub 임시 첨부 URL은 Draft에만 허용하며 미리보기·제출은 최종 tag URL을 사용하고 공개 후 익명 검증합니다. 소스 검증도 익명입니다.

최종 미리보기는 소스 pre-release 공개, fork 생성, push와 Ready PR을 나열하며 확인과 재검증 뒤에만 씁니다. rolling Draft의 검증된 패키지·메타데이터를 소스 SHA에 묶인 고정 pre-release로 복사하고 CLI 인증으로 업로드합니다. 원 Draft는 CI가 계속 갱신합니다. 후보가 없으면 현재 기본 브랜치 commit의 CI 재실행을 별도로 확인받으며 이후 제출 취소는 그 복구 실행을 취소하지 않습니다. 메타데이터·공개 키·서명·선택 이미지는 전송하지만 개인 키·암호는 로컬에 남습니다. 키 화면과 최종 검증은 보호된 `emergency-state`도 읽습니다. 긴급 선언은 mainline 키 상태보다 우선하며 이미 선언한 키는 침해됨으로 표시하고 새 선언에서 제외합니다.

소유권 이전은 수신자가 요청 PR 하나를 엽니다. 소유자 wizard는 `/issues` API의 `labels=type:ownership-transfer`, `mentioned=<current account login>`으로 기본 탐색하며 둘 중 하나 또는 모두 해제할 수 있습니다. 일치 PR의 고정 head 요청 파일만 읽고 현재 소유권·개인 신원·조직 membership을 확인합니다. 라벨·mention은 검색 필터이지 권한이 아닙니다. 질의에는 공개 라벨·로그인만 포함하며 개인 키·암호는 없습니다.

확인 뒤 소유자 CLI 자격 증명으로 PR `/reviews`에 승인·거절을 보냅니다. 리뷰는 영어이며 hidden marker에 결정 종류·요청 해시·선택적 활성 키 서명을 담습니다. 개인 키·암호 업로드나 수신자 소스 저장소 접근은 필요 없습니다. 확인된 리뷰 뒤 `/issues/<PR-number>/comments`에 리뷰 링크·알림 표식을 올립니다. 실패는 같은 리뷰·댓글을 읽고 재시도합니다. 이 댓글은 기본 브랜치의 보호된 `issue_comment` workflow를 깨우며 fork workflow 승인을 요구하지 않습니다. 알림 자체는 권한이 아니고 일반·bot 댓글은 처리하지 않습니다.

원시 Review 이벤트도 같은 workflow를 시작할 수 있습니다. 서로 다른 개인 계정 두 당사자의 유효 서명이 있으면 같은 PR에 결과를 추가하고 브랜치 보호에 따라 merge할 수 있습니다. Gate·최종 merge는 신원·리뷰·현재 키·긴급 상태를 다시 검사합니다. 증명 부족·조직·복구 요청은 사람 리뷰가 필요합니다. 알림 job은 숫자 ID로 현재 login을 찾아 중·영 요청 댓글에 원래 개인 소유자를 mention합니다. 명시적 거절은 소유권·요청·결정을 다시 확인한 뒤에만 PR을 닫습니다. 알림은 기존 `pull-requests: write` token, 자동 추가·merge는 보호된 유지 관리자 자격 증명을 사용합니다. CLI 프록시와 요청당 60초·32 MiB를 유지합니다.

개발자의 요청 철회는 같은 저장소 PR·파일·계정·조직 API로 자신의 미merge 요청을 나열하고 확인한 PR만 닫습니다. 쓰기 전에 숫자 작성자·저장소·base·head·merge 상태를 다시 읽고 응답 유실도 같은 PR 조회로 해결합니다. 새 요청 생성이나 첨부 삭제는 하지 않고 개인 키도 보내지 않습니다.

닫힌 요청의 브랜치 정리는 별도 확인이 필요합니다. `api.github.com` 사용자·저장소·PR·branch·규칙 API로 신원·보호·다른 열린 PR을 확인하고 Git HTTPS로 이전 SHA에 묶인 lease를 사용해 해당 ref만 삭제합니다. 기본 브랜치·fork·Release는 유지합니다. 응답 유실 뒤에는 조회하고 재삭제는 다시 확인받습니다. 대상 저장소·branch·SHA와 기존 CLI/Git 인증·프록시를 사용하며 정리를 거절하면 삭제하지 않습니다.

#### 보호된 요청 적용과 브랜치 쓰기

자동 요청·리뷰 완료·긴급 선언은 `review-branch.mjs`로 검증 저장소의 익명 `https://github.com/<verified-repository>/`에서 정확한 부모 commit을 읽고 결과를 만듭니다. fork 쓰기는 `git@github.com:<submission-fork>.git`의 `github.com:22`로 유지 관리자 계정 SSH 키를 사용해 원 PR을 fast-forward합니다. 같은 저장소 branch는 job token·API입니다. 신원·요청·maintainer edits를 검사하고 제출 코드를 checkout하거나 실행하지 않습니다.

SSH는 Git 객체·인증 서명만 전송하고 개인 키 바이트는 보내지 않습니다. 전용 설정은 GitHub Ed25519 host key와 단일 키를 고정하고 agent·대화형 인증을 끄며 연결 15초, Git 프로세스 60초·출력 32 MiB를 제한합니다. HTTPS 읽기는 runner HTTP 프록시를 사용할 수 있지만 SSH는 사용자 SSH 설정·HTTP 프록시를 무시하고 포트 22 직접 연결이며 자동 대체 전송은 없습니다. merge API는 커뮤니티에만 Contents write가 있는 별도 소유자 PAT를 사용합니다. 관련 workflow를 끄면 쓰기가 멈추고 SSH Secret 제거는 fork 갱신을 막습니다.

`Apply signed owner request`는 정적 검사 후 보호 master 도구로 개인 소유자의 버전 상태 요청이나 구·신 키 양측 증명이 있는 통상 키 교체를 검증합니다. GitHub PR·사용자·Environment 정책·Git 객체·Release/asset·check-runs·dispatch API와 공식 attestation·도구 서비스를 사용합니다. 원격은 서명·등록·PR 작성자를 검사하며 로컬 키 쌍 검사만으로 승인하지 않습니다. 조직·복구·분실/침해 키 교체는 사람 리뷰가 필요합니다. `community-status` 루트는 서명 단계에만, SSH는 fork 추가에만, 제한된 소유자 PAT는 merge에만 사용합니다. job token이 나머지 API와 Gate·공개 완료 dispatch를 수행합니다. 최종 job은 Gate로 check를 발행하고 최대 5초 원시 readback 뒤 정확한 head만 기존 보호·사람 거절권 아래 merge합니다. 플러그인을 재빌드하지 않고 요청에는 동작·PR·SHA·서명·상태만 있습니다. 비활성화 후에도 사람 리뷰 경로는 남습니다.

긴급 키 선언은 wizard 확인 뒤 등록된 공개 키를 대상으로 보호 `emergency-state` PR을 생성합니다. `Community emergency`는 master 코드만 실행하고 개인 숫자 계정 또는 검증 가능한 조직 관리자 역할을 확인합니다. 커뮤니티/fork Git 객체·보호·PR·댓글·checks·dispatch, 사용자·조직·`/orgs/<org>/memberships/<login>`과 Action 도구를 읽습니다. job token·SSH·소유자 PAT·Gate App 역할은 위와 같습니다. branch에는 선언·공개 키 지문만 저장합니다. merge는 이후 해당 키 동작을 막지만 기존 배포는 바꾸지 않습니다.

성공 뒤 별도 알림이 계정·키 ID·지문으로 중·영 요청 댓글을 갱신하며 댓글 실패는 동작을 반복하지 않습니다. 이 이벤트는 일반 열린 요청을 열거해 영향을 받는 성공 check를 무효화하고 새 검사를 dispatch합니다. mainline 집계는 열린 PR을 열거하지 않습니다. job은 15분이며 최종 승인·공개 큐를 공유합니다. 선언은 만료·자동 해제되지 않습니다. 조직 권한을 읽을 수 없으면 자동 실행을 막습니다. API 60초·CLI/runner 프록시를 유지하며 workflow를 꺼도 기존 차단은 계속 적용됩니다.

신뢰 job 사이 전달은 같은 Actions run의 artifact 저장소를 사용합니다. 후보 검증·준비·서명·사람 승인은 최종 승인 큐 밖에서 수행하고 다음 job은 같은 run의 불변 artifact ID만 읽어 source·run/attempt·바이트·현재 권한을 재검증합니다. 전달 실패는 기존 성공 check를 유지할 근거가 아닙니다. artifact 보관은 1일, 인코딩 Gate 전달은 32 MiB이며 개인 키는 없습니다. 공식 Action의 임시 job token·runner 네트워크를 사용합니다.

#### 후보 빌드와 정적 검사

SDK 프로젝트 push·PR은 읽기 권한 job으로 실제 빌드 모델을 질의하고 빌드·테스트 후 도구 오프라인 모드로 재빌드합니다. GitHub Actions·artifact, 소스 Release/asset·Git ref/commit, digest 고정 Docker Hub Temurin 이미지·CDN, Ubuntu 미러와 프로젝트 Maven/Gradle Wrapper/sbt 저장소를 사용합니다. sbt launcher는 `repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/`에서 고정 버전·SHA-256으로 받습니다. CI·Wrapper·의존성 설정과 runner 프록시를 따르며 제출자의 로컬 프록시를 가져오지 않습니다.

성공한 기본 브랜치 빌드만 별도 쓰기 job을 시작해 플러그인별 rolling Draft 하나를 재사용합니다. 현재 기본 SHA를 검사하고 이전 메타데이터·패키지를 지운 뒤 새 패키지, 메타데이터 순서로 올립니다. 저장소 내 직렬 처리로 늦은 구 commit 실행이 새 후보를 덮지 못합니다. 공개 Release·Git tag는 바꾸거나 프로젝트 코드를 실행하지 않습니다. Draft는 Actions artifact의 7일 보관을 넘어서 남습니다. 후보 workflow 제거로 자동 요청이 멈춥니다. 커뮤니티의 네트워크 차단 재빌드는 별도이고 로컬 wizard는 빌드 모델 질의를 하지 않습니다.

커뮤니티 PR 정적 검사는 보호 기본 브랜치의 고정 checker가 읽기 token으로 PR·Release/asset·Git tree/blob·소스 commit·숫자 계정 API를 읽고 후보 코드·빌드를 실행하지 않습니다. 공개 패키지 URL과 고정 commit GitHub 소스 ZIP은 인증·쿠키 없이 Node 규칙으로 받고 hop마다 공개 IP를 고정·검증하면서 원래 호스트 TLS를 검사합니다. 최대 5회 이동·전체 60초·실제 크기·SHA-256을 적용합니다. 통과하면 버전 빌드를 허용하지만 승인·공개 권한은 주지 않습니다. 전달 artifact는 현재 job 자격 증명으로 올려 7일 보관합니다.

철회된 기준 패키지는 별도 보호 `Prepare reviewed baseline`이 기술 Draft 읽기에 필요한 Contents write로 읽습니다. 요청·검토 기록을 검증하고 제출 소스를 실행하지 않습니다. 같은 run의 읽기 전용 빌드 job에 1일 artifact로 원 패키지를 전달해 크기·해시를 다시 검사합니다. 준비 15분, 다운로드는 SDK archive 한도와 CLI 60초를 유지합니다. 유지 관리자는 workflow를 꺼서 중단할 수 있습니다.

도구 준비는 Maven Central의 고정 Maven·sbt, `services.gradle.org/distributions/`와 GitHub/CDN, `nodejs.org/dist/`, `archive.ubuntu.com/ubuntu/pool/universe/s/squid/`의 proxy 도구, Docker Hub `eclipse-temurin`·`ubuntu/squid` 이미지·인증/CDN을 사용합니다. 버전 제출·유지 관리자 재계산이 유발하며 프로젝트 실행 전에 익명 취득·고정 SHA-256을 검증합니다. 빌드 도구 각각 192 MiB, 프록시 도구 32 MiB, 60초·5회 리디렉션과 hop별 공개 IP 검사입니다. Docker는 runner 레지스트리 인증·프록시·캐시로 전체 이미지 digest를 pull하며 컨테이너의 승인 호스트와 별개입니다. 공개 도구 경로·버전·이미지 digest·플랫폼만 보내고 제출 소스·커뮤니티 개인 키는 없습니다. 도구 변경은 보호 SDK lock·빌드 정책으로 반영하거나 빌드 workflow를 꺼 접근을 피합니다.

의존성 prefetch는 자격 증명 없는 컨테이너에서 Maven/Gradle/sbt 프로젝트 설정을 실행하고 전용 Squid로 HTTPS 443의 `repo.maven.apache.org`, `repo1.maven.org`, `plugins.gradle.org`, `plugins-artifacts.gradle.org`만 허용합니다. 프록시는 클라이언트 TLS를 종료하여 CONNECT·TLS SNI·HTTP Host를 검사하고 승인 호스트 HTTPS GET/HEAD만 전달합니다. 비공개 주소를 거부하고 원본 인증서를 검사합니다. 빌드 컨테이너는 임시 CA 인증서·trust store만 받고 개인 키는 프록시에만 mount하여 종료 후 지웁니다. 시스템 trust store는 바꾸지 않습니다. 외부 DNS·host bridge 출구·공유 쓰기 캐시가 없으며 최종 재빌드는 새 network-disabled 컨테이너입니다. prefetch·재빌드는 각각 30분으로 버전 빌드에 기본 실행합니다. 허용 소스 변경에는 보호 정책 수정이 필요합니다.

네트워크 회귀 테스트는 격리 프록시 설정에서 `self-signed.badssl.com`과 미승인 `www.cloudflare.com`으로 위장한 요청을 사용합니다. 테스트 전용 self-signed 허용 호스트에서도 원본 TLS 실패가 연결을 막는지, 위장 목적지가 거부되는지 검사합니다. CI 또는 명시적 로컬 테스트만 유발하고 자격 증명·운영 허용 목록·시스템 trust store를 바꾸지 않습니다. 테스트를 실행하지 않으면 요청하지 않습니다.

#### 후보 보관·증명·정리

정적 성공 뒤 읽기 전용 preflight가 같은 정확한 build attempt의 후보 artifact 존재를 5분 안에 확인합니다. 공개 큐 밖에서 실행하고 후보가 있는 run만 보호 archive job에 들어갑니다. `api.github.com`의 같은 저장소 PR·run/attempt·job/log·artifact·Release/asset, `uploads.github.com`과 GitHub 첨부 주소를 사용합니다. 내장 job token이 원시 신원·실행 출처·바이트를 독립 검증한 뒤 숫자 게시자 계정·유형·게시자 ID·플러그인 ID·버전별 Draft를 재사용합니다. 프로젝트 코드 없이 패키지·소스·증명을 바꾸며 구 proof 삭제, 변경 asset·manifest 갱신, 새 proof 저장 순서입니다. 다른 열린/merge 요청은 덮지 않고 구 PR 기준 Draft는 제자리 이전할 수 있습니다.

미merge 요청 PR을 닫으면 wizard 선택과 별개로 정리가 자동 실행됩니다. 내장 Contents write token으로 현재 PR·Draft 소유권·첨부를 읽고 아직 그 요청 소유인 후보 Release·첨부만 삭제합니다. 공개 Release, 새 요청이 재사용한 Draft와 다시 열린 요청은 유지합니다. 삭제 후 재제출은 새 후보를 만듭니다. 소스 저장소 Release는 지우지 않습니다. 보관·정리·리뷰 완료·공개는 하나의 큐입니다.

Self-review·Gate·preflight는 Draft 읽기에 Contents write를 요청하지만 보호 mainline 코드만 실행합니다. API 응답으로 실제 Draft 가시성을 판단하며 완전 후보가 없으면 리뷰를 보류하고 보관·접근 확인을 안내합니다. 목록 API 401/403은 리뷰를 차단합니다. 빌드 job은 읽기 전용을 유지하고 Draft가 보이지 않거나 목록 접근이 명시적으로 거부되면 격리 재빌드합니다. 리뷰·공개는 보관 패키지를 재빌드하지 않습니다. API·다운로드는 CLI 인증·프록시·플랫폼 리디렉션, 호출당 60초, 보관·정리 각각 15분입니다. Actions ZIP은 GitHub JSON media type으로 리디렉션을 받고 Release 첨부는 raw binary를 받습니다. Draft는 공개 카탈로그 밖에 있습니다. 보관을 끄면 새 archive가 필요한 리뷰가 막힙니다.

`Community candidate cleanup`은 미merge close, 매일 04:23 UTC 또는 수동 실행합니다. Release/asset·PR 목록/파일·run·Git tree/blob/ref API, 첨부 CDN과 아래 attestation 서비스를 사용합니다. Contents write·Actions read·Pull requests read의 내장 token이며 서명 키는 없습니다. 후보 소유권·종료를 검사하고 구 operation Draft는 열린 요청 참조 없음·run 완료·90일 미사용을 모두 만족해야 지웁니다.

Merge 증명은 먼저 `migrate-receipts.mjs`로 원 서명·부모 체인을 검증하고 Git 투영을 저장한 뒤 크기·해시로 원 서명 바이트를 복원해야 합니다. 마이그레이션은 commit할 파일만 만들며 원격 쓰기는 없습니다. 보존 시작은 마이그레이션 commit·PR 종료·run 갱신·Release 갱신 중 최신입니다. 삭제 직전 참조·mainline·요청·첨부를 다시 읽고 조회 불완전 시 유지합니다. CLI 인증·프록시·60초/32 MiB, 공유 큐의 15분 job입니다. 정식/소스 Release·tag·참조된 철회 패키지는 유지합니다. workflow를 끄면 중단되며 GitHub가 schedule을 비활성화한 경우 복구하거나 수동 실행해야 합니다.

보호 보관 후 고정 `actions/attest`가 Actions OIDC·저장소 attestation API, Sigstore `fulcio.sigstore.dev`·`rekor.sigstore.dev`에 후보 manifest digest를 증명합니다. 임시 공개 키·서명·해시·공개 workflow 신원을 보내며 GitHub API에는 내장 token을 사용하고 커뮤니티 서명 키는 사용하지 않습니다. 인증서·투명성 로그·신뢰 메타데이터·리디렉션은 공식 서비스가 정합니다. 이후 `gh attestation verify --bundle`은 token/secret 환경 변수를 비우고 별도 빈 CLI 설정으로 `tuf-repo.github.com`, `tuf-repo-cdn.sigstore.dev`의 공개 신뢰 루트를 60초 안 익명 갱신합니다. proof는 Draft에 남습니다. 검증 비활성·실패는 archive 인증을 막으며 attestation 자체가 리뷰 승인은 아닙니다.

#### 리뷰 완료·공개·저장소 관리

유지 관리자는 master에서 `Complete community review`를 수동 실행하여 열린 PR의 정확한 head와 release Environment를 승인합니다. 커뮤니티·검증된 fork의 PR·review·collaborator·Environment·Actions 승인 이력·Git 객체·merge·checks·dispatch·Release/asset, `/user/<accountId>`, `/organizations/<accountId>`, 업로드·CDN·OIDC/attestation/신뢰 루트를 사용합니다. 고정 SDK가 메타데이터·서명을 로컬 준비하고 결과, 경로·해시 receipt와 보호 workflow proof를 원 PR에 추가합니다. 내용은 operation Draft 대신 Git blob을 재사용합니다.

사람 승인은 큐 밖이며 준비·서명·branch 갱신·최신 승인 검사·merge는 30분의 하나의 보호 job에 들어갑니다. preflight는 15분, Draft 읽기 Contents write, 보호 코드만 사용하고 branch 자격 증명은 받지 않습니다. 같은 저장소 쓰기는 job token, fork는 maintainer edits·전용 SSH, merge는 커뮤니티 전용 Contents write PAT를 쓰며 `/user`로 저장소 소유자인지 확인합니다. 누락은 안내와 함께 실패합니다.

전송에는 리뷰·복구·조직 대표 증명, 공개 키·서명·패키지가 있습니다. 커뮤니티 개인 키는 서명 프로세스 stdin에만, SSH 개인 키는 branch 쓰기에만 사용합니다. API·업로드·인증 다운로드는 CLI 프록시·리디렉션·60초 제한입니다. 현재 mainline을 읽되 원 reviewed head를 보존하고 현재 mainline+검증 요청+생성 결과로 tree를 만듭니다. 보호 코드가 같으면 데이터만 진행된 mainline 위에서 실행할 수 있고 검증된 오래된 결과는 fast-forward로 재생성할 수 있습니다. 기존 branch 보호·사람 거절권을 유지합니다. 결과 내용 합계, manifest와 proof는 각각 32 MiB이며 merge된 Git 기록은 보존합니다. 비활성화하면 리뷰 완료가 막힙니다.

Master push 또는 수동 공개 복구는 같은 저장소 Git·Release/asset·merge PR·label/comment, uploads·attestation·공개 패키지 CDN을 사용합니다. 실제 merge 부모·준비 proof·App 승인 checks·후보 서명을 검증하고 긴급 키 차단을 재확인한 뒤 job token으로 기존 Draft를 공개합니다. 익명 Node 다운로드로 공개 바이트를 확인한 뒤 상태·유지 관리자 정보를 갱신합니다.

REVOKED 설치 패키지는 먼저 공용 `archive/revoked-packages` Draft에 해시 중복 제거로 복사하고 다시 내려받아 크기·해시를 검사합니다. mainline·첨부 신원을 다시 확인한 뒤에만 공개 사본을 제거합니다. 정식 tag·철회 공지·역사 서명은 남습니다. 보관 실패·플랫폼 삭제 제한·readback 실패는 실패로 남고 부분 쓰기는 원자적 완료가 아닙니다. 재빌드·새 PR·열린 PR 전체 조회는 하지 않습니다. job은 30분이며 workflow를 끄면 Release 상태가 merge 데이터보다 뒤처질 수 있습니다.

저장소 보호·라벨 스크립트는 `https://api.github.com/repos/Sywyar/PixivDownloader-community-plugins/`의 repository·collaborator·ruleset·Environment·branch·label와 `/user`를 읽습니다. 명시적 preview/readback/apply만 수행하며 현재 CLI login으로 숫자 저장소·소유자 신원을 확인합니다. apply만 보호 설정·관리 라벨을 쓰고 나머지 라벨은 유지합니다. 목표 설정은 제출·maintenance·긴급 PR merge 뒤 해당 저장소 head branch를 GitHub가 삭제하도록 하되 default·emergency-state는 삭제 보호합니다. fork 정리는 제출자의 규칙·권한이며 미merge 요청 취소 뒤 wizard 정리는 별도 확인입니다. 설정·라벨 메타데이터만 보내고 패키지·서명 키는 없으며 CLI 프록시를 사용합니다. 실행하지 않으면 접근하지 않고 대상 변경은 보호 신원 수정이 필요합니다.

승인·리뷰 결정·알림 workflow는 같은 저장소 PR·review·event·collaborator·Actions run/attempt·artifact·checks, 제출 head 저장소 tree/blob, App installation/token과 artifact 서비스를 사용합니다. PR·review·관련 workflow 완료 이벤트 또는 명시한 PR만 재계산합니다. master 코드 검사·라벨 변경·관리 요청의 빈 archive는 전체 열린 요청이나 추가 승인을 유발하지 않습니다. 후보 archive 완료는 리뷰를 시작합니다.

Gate 준비는 큐 밖에서 서명을 검증할 수 있으나 같은 run의 동일 소스·요청·workflow attempt에만 증명을 재사용합니다. 대기 중 mainline 변경은 최종 재검증을 요구합니다. 서명 요청 준비·merge는 공유 큐에서 하며 checks 발행 전 원시 리뷰·권한·긴급 상태를 다시 읽습니다. Self-review/Gate만 Draft 읽기 Contents write를 명시하고 빌드·알림은 각 최소 권한입니다. 고정 SDK의 내장 계약 검증은 Maven을 조회하지 않습니다. 리뷰 폼은 원시 바이트 digest에 묶인 결정 JSON을 저장소 artifact 보관 설정으로 올립니다.

Gate App 개인 키는 공식 Action이 해당 저장소의 단기 `checks:write` token을 얻는 데만 사용합니다. 별도 알림 job은 라벨·댓글 쓰기로 관리 라벨, 상태 댓글, 중·영 요청 설명 두 개를 제자리 갱신합니다. 고정 head의 요청·해시·계약에서 종류·설명·공개 키 ID·서명 검사 결과·소스 링크를 만들며 두 언어는 같은 사실을 사용합니다. 부분 댓글 쓰기는 다음 동기화로 마칠 수 있고 서명 바이트·개인 키는 포함하지 않습니다. 버전 리뷰는 Draft·provenance도 읽습니다. 전송은 저장소·PR·head·숫자 계정·리뷰 이유·결정·check 메타데이터이며 커뮤니티 개인 키는 없습니다. runner/CLI/Actions 네트워크와 GitHub 제공 다운로드 주소를 사용합니다. 비활성화하면 승인이 막힙니다.

PR·기본 브랜치 push 테스트와 보호 검사·결정 job은 GitHub 소스·Actions 및 `actions/setup-node`, `actions/setup-java`가 고른 Node.js·Temurin 서비스를 사용합니다. 고정 Action·도구 버전·플랫폼·일반 연결 정보만 보내고 후보 테스트에 App 개인 키·쓰기 token은 없습니다. 공식 setup Action·runner의 캐시·프록시를 따르며 유지 관리자가 도구 체인을 바꾸거나 실행을 끌 수 있습니다.

커뮤니티 철회 갱신 bot은 월요일 04:23 UTC 또는 수동 실행으로 `api.github.com`의 Git 객체·전용 branch PR·dispatch를 사용합니다. snapshot 잔여 7일 이하이면 갱신 PR을 만들거나 기존 열린 요청을 반환하며 보호 snapshot과 정확한 갱신 branch 이력만 읽습니다. generation·snapshot 해시·Git 객체·PR 본문·check 번호를 보내고 키·플러그인 소스는 없습니다. 기본 권한은 읽기이며 해당 job만 Contents/PR/Actions write 임시 token을 사용합니다. 저장소의 Actions PR 생성 허용이 필요하고 정적 검사·Gate를 명시적으로 dispatch합니다. 서명은 여전히 유지 관리자 리뷰·release Environment 승인을 요구하며 승인 리뷰 workflow가 checks 후 merge합니다. CLI 프록시·60초/32 MiB와 15분 job이며 비활성화로 자동 접근을 중단합니다.

### 공지 갱신·애플리케이션·SDK 배포

원격 콘텐츠의 `Renew announcement index`는 매일 03:37 UTC 또는 master 수동 실행으로 Git HTTPS `github.com/Sywyar/PixivDownloader-Remote-Content`, 해당 저장소의 `api.github.com` ref·Pages build API, 고정 주 저장소 소스·Actions/JDK·Maven에 연결합니다. 만료까지 7일 이하이면 원 서명·내용을 검증하고 순서·30일 유효 기간만 갱신하여 목록·서명을 함께 fast-forward합니다. 그 밖에는 상태만 검사합니다. 현재 mainline·Pages 상태를 읽어 필요한 build를 요청하고 공개를 기다리므로 이미 commit됐지만 미공개인 갱신도 복구합니다.

임시 `GITHUB_TOKEN`은 서명 job에 Contents write만, 공개 job에 Contents read·Pages write만 줍니다. master 제한 `announcement-signing` Environment의 공식 개인 키는 runner 임시 파일에서 로컬 서명 CLI만 사용하고 종료 시 삭제하며 Git/API·의존성 호스트에 보내지 않습니다. 공개 소스 경로·좌표·Git 객체·서명·SHA만 전송하고 공식 Action·CLI·Git·Maven·runner의 프록시·미러·리디렉션을 따릅니다. 검사·서명·공개는 각각 5·30·15분, Pages 폴링은 10초 간격 60회입니다. workflow를 끄면 갱신·공개 복구가 멈춥니다.

#### Release 검증과 주 저장소 Gate

Release/Nightly 아티팩트 E2E는 별도 runner에서 Java 표준·full-offline ZIP을 테스트합니다. 설치기 검증은 정상 시작, 실패 주입, 플러그인 누락+로그 회전의 세 독립 그룹입니다. 각 그룹이 필요한 배포·설치기를 내려받아 설치·검사·제거하며 상태가 이어지는 단계는 순서대로 실행합니다. 성공·실패 모두 결과·로그·창 스크린샷·플러그인 상태를 고유 이름의 Actions artifact로 7일 올립니다. 한 그룹 실패로 다른 그룹을 취소하지 않지만 공개에는 전부 통과해야 합니다. 현재 job runtime 자격 증명을 사용하고 배포 개인 키는 제외합니다. 목적지·리디렉션·프록시는 runner·공식 artifact Action에 따르며 유지 관리자는 공용 release-acceptance Action의 증거 업로드를 바꾸거나 끌 수 있습니다.

Git·배포 스크립트는 현재 `origin`, `https://api.github.com` 또는 `GITHUB_API_URL`, GitHub Releases에서 fetch·remote ref 확인·Gate 감사·앱/플러그인 배포를 수행합니다. Release/Nightly는 플러그인 배포 job이 만든 commit의 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/<published-catalog-commit>/manifest.json` 또는 `nightly-manifest.json`과 서명을 읽어 플러그인을 준비합니다. 공유 플러그인 저장소 공개는 직렬입니다.

Nightly 플러그인 공개는 기존 Git checkout의 현재 목록을 검사합니다. 앱 공개는 첨부 삭제 전에 `https://api.github.com/repos/Sywyar/PixivDownloader/releases/tags/nightly`의 현재 Release 이름을 CLI로 읽어 오래된 run/attempt를 거부합니다. 404만 미존재이며 읽기 오류·알 수 없는 신원은 쓰기를 막습니다. 내장 job token·60초·32 MiB·CLI 프록시를 사용하고 목록은 기존 서명·다운로드 규칙을 유지합니다.

Actions·Artifact·Release와 참조된 `actions/*`, `softprops/action-gh-release`는 CI·빌드·업로드·배포를 제공합니다. 전체 QG는 같은 소스를 checkout한 Java 테스트·SDK 계약·ProGuard 검증의 세 병렬 job이며 Maven 캐시를 공유하므로 cold cache에서는 중복 다운로드가 있을 수 있습니다. 검사는 소스 읽기 권한만 갖고 배포 자격 증명은 없습니다. 배포 실행의 ProGuard job은 unsigned 플러그인·검증 도구·compiled class를 1일 artifact로 올립니다. QG 통과 뒤 소비자는 그 Gate가 내보낸 불변 ID를 같은 run·source SHA에서 내려받습니다. 재시도는 이전 attempt의 통과 producer를 재사용할 수 있지만 SHA·빌드 설정·모든 파일 해시를 검증하고 불일치는 공개를 막습니다.

Release와 Nightly는 source SHA당 전체 QG 한 번 후 필요한 SDK 공개, 앱 의존성 빌드와 검증 플러그인 공개를 병렬 처리합니다. 현재 run Artifact가 코어 JAR·검증 도구·플러그인을 Java 패키지·설치기 병렬 조립에 전달합니다. 배포 자격 증명은 release Environment에만 있습니다. 실제 도구 다운로드·프록시는 아래 빌드 설정을 따릅니다.

주 저장소 `Gate Checks`는 `https://api.github.com/repos/Sywyar/PixivDownloader/`의 workflow·run·job·log·PR·commit·checks, App installation/token, 현재 origin과 log API가 반환한 주소를 사용합니다. CI 이벤트 자동 처리 시 run의 `workflow_id`로 metadata를 읽고 workflow 이름으로 신원, run title로 PR 연관을 판별합니다. PR-only CI에서는 원시 연관이나 run 이름의 PR 번호와 head 저장소·branch를 맞추고 master 대상의 현재 열린 PR에만 checks를 씁니다. 개발 branch 대상·닫힌 PR에는 App token을 발급하지 않습니다.

읽기 전용 `GITHUB_TOKEN`·Git fetch로 증거를 얻은 뒤 공식 `actions/create-github-app-token`이 release Environment의 App 개인 키로 runner에서 JWT를 서명하고 GitHub에 단기 `checks:write` installation token을 요청합니다. 개인 키 자체는 전송하지 않습니다. 현재 필수 check 여섯 개를 PR head에 붙이고 테스트 merge commit의 check도 merge 후 검증용으로 남깁니다. 상세에는 테스트 commit·실제 실행 링크가 있습니다.

GitHub가 test merge를 다시 만들면 commit API로 두 부모·파일 tree를 비교하고 셋이 같을 때 새 commit에 checks를 쓰지 않고 기존 실행을 재사용할 수 있습니다. 한 발행당 최대 두 세트만 쓰며 전후 PR·부모·tree·유효 실행을 재확인합니다. 변경·쓰기 실패 시 두 세트 철회를 시도하고 job 종료 때 token을 폐기합니다. merge 후 실제 master를 검증하며 수동 full QG 복구 증거도 같은 보호 master 이력의 commit이어야 합니다. 그 commit을 가리키는 branch/tag로 실행할 수 있지만 두 검증 경로 모두 App checks를 쓰지 않습니다. 요청 데이터는 저장소·PR/workflow/run/job ID·SHA·판정입니다. runner Git/CLI/프록시와 GitHub log 목적지를 따르며 workflow/App를 끄면 필수 checks가 막힙니다.

#### SDK 공개·복구·Pages

SDK 공개 workflow는 공식 `org.sonatype.central` 플러그인의 현재 Central Portal API와 `https://repo1.maven.org/maven2/io/github/sywyar/pixivdownloader/`를 사용합니다. 앱 Release 또는 명시적 공개·복구가 호출합니다. 일반 master push와 Nightly는 SDK를 공개하지 않고 Nightly는 같은 실행의 SDK 빌드·검증만 합니다.

먼저 현재 공개 SDK Release·Central POM을 읽고 완전한 기존 공개이면 재업로드하지 않습니다. 같은 SHA의 QG 통과 뒤 새 공개는 release Environment의 Central token·PGP로 통합 `pixivdownload-sdk`를 포함한 다섯 SDK 좌표를 올립니다. 공식 플러그인은 `PUBLISHED`까지 최대 3600초 기다립니다. 공개·복구 후 POM·JAR·sources·Javadocs·서명·SHA-256을 공개 다운로드해 격리 소비자를 검사합니다. 최종 Portal 호스트·경로는 공식 Maven 플러그인·Central이 정하며 빌드 프록시가 바꿀 수 있습니다.

새 SDK 후보는 검증 원 호스트·unsigned 플러그인·Maven 아티팩트·도구·Javadocs·해시를 Actions Artifact에 1일 올립니다. 전체 QG 후 공개 job은 같은 run의 불변 ID를 읽어 SHA·경로·크기·해시를 확인하고 release Environment 공식 키로 플러그인을 서명해 최종 SDK를 만듭니다. Actions runtime 자격 증명과 runner 프록시를 쓰고 빌드 바이트·ID만 전송합니다. 공개 개인 키·CI 테스트 키·테스트 신뢰 설정은 전달하지 않습니다. 같은 run 이전 attempt 재사용은 가능하지만 누락·만료·불일치는 전체 workflow 재실행을 요구합니다. 이미 서명된 Release 복구는 artifact 보관 기한과 별개입니다. 일반 QG 호출자는 SDK 후보를 내보내지 않습니다.

주 공개 workflow는 `https://api.github.com/repos/Sywyar/PixivDownloader-Plugin-SDK/releases`, `https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/`와 Release CDN에 연결합니다. release Environment의 SDK 저장소 제한 cross-repository token으로 불변 `sdk-api-v*` tag/Release를 검사·생성하고 서명 첨부를 올린 뒤 공개 검증합니다.

주 저장소의 reusable Pages build는 그 token을 받지 않습니다. 공개 Releases API·첨부를 익명 조회하고 모든 non-draft SDK의 해시·metadata를 검증해 버전별 Javadoc artifact를 만듭니다. SDK 저장소는 고정 주 저장소 commit SHA의 workflow를 호출하고 자체 deploy job이 `pages: write`·Pages OIDC로 배포합니다. dispatch·tag/Release·Pages 설정·배포는 각 권한이 필요합니다. 설치된 앱은 이 공개/Pages 빌드 요청을 하지 않습니다.

서명된 Draft 첨부는 Central 공개 전에 보존하고 다시 내려받아 검증합니다. 모든 공개 좌표를 읽을 수 있어야 고정 첨부·전체 호스트 실행을 검증하고 같은 Release를 공개합니다. Central 전체 공개와 원 Release가 있으면 공개 재시도·명시적 복구는 원 호스트·도구·SDK ZIP을 재사용하고 Central에 다시 올리지 않습니다. Draft는 있는데 Central 공개 좌표가 없으면 업로드 진행 중일 수 있어 원 deployment ID 확인을 요구하며 중단합니다. 일부 좌표만 공개되거나 첨부 누락·충돌도 복구를 막고 공개 첨부를 덮지 않습니다. 공개 후 소비자는 빈 캐시에 runtime을 받습니다. Pages는 SDK·이전 Javadoc ZIP을 익명 검증하고 과거 호스트를 받지 않고 runtime metadata만 검사합니다.

SDK runtime 준비는 명시적 prepare/Run/Debug에서 캐시가 없을 때 `https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/<release-id>/<fixed-runtime.zip>`을 받습니다. IDE import만으로 요청하지 않습니다. JDK URLConnection은 앱과 별개인 개발 JVM 프록시를 쓰고 최대 5회 HTTPS 리디렉션을 `release-assets.githubusercontent.com`, `objects.githubusercontent.com`에 허용합니다. 첨부 경로·GitHub 반환 서명 질의만 보내고 프로젝트 소스·플러그인 JAR·앱 자격 증명·공개 token은 없습니다. 다운로드·캐시 재사용·매 실행에 고정 크기·해시를 확인하며 실패해도 다른 버전을 선택하지 않습니다. 캐시는 `~/.cache/pixivdownloader-sdk/`, 재정의는 `pixivdownload.sdk.cache-dir`입니다. 검증 캐시나 준비/실행 미호출로 다운로드를 피할 수 있습니다.

전체 SDK 호스트 검사는 QG마다 같은 소스의 호스트·모든 공식 플러그인을 빌드하며 Maven/Gradle 저장소·미러·캐시를 사용합니다. 공개 플러그인 패키지를 따로 받지 않습니다. 임시 사용자 지정 저장소 키로 테스트 runtime을 서명하고 로컬 저장소 설정으로 전체 호스트를 검사합니다. 키는 삭제하고 공개 job으로 보내지 않습니다. 통과 후 SDK 공개는 검증된 원 플러그인 바이트에 공식 서명하여 최종 SDK를 검사합니다.

QG는 SDK 버전이 같아도 Compose 테스트·템플릿·전체 Javadocs·격리 소비자를 검사합니다. SDK staging·템플릿·문서·소비자는 한 job에서 순서대로 빌드 결과를 재사용하고 Compose 테스트는 ProGuard 빌드 뒤입니다. Wrapper Maven URL, Maven/Gradle 의존성·리디렉션과 staged SDK file URL을 사용합니다. 소비자는 도구·외부 의존성을 먼저 받고 정확한 후보 SDK로 offline 재빌드·제3자 런타임을 검사합니다. 공개 자격 증명 없이 좌표·경로만 보내며 임시 제3자 테스트 키를 씁니다. 캐시는 요청을 줄일 수 있으나 QG 별도 skip은 없습니다. SDK 공개는 현재 검증 후보와 이후 공개 아티팩트를 재검사합니다.

#### FFmpeg 빌드·로컬 패키징

유지 관리자가 기본 브랜치에서 수동 실행하는 안정 FFmpeg workflow는 `https://ffmpeg.org/download.html`, `https://ffmpeg.org/releases/`, `https://ffmpeg.org/ffmpeg-devel.asc`, 고정 commit의 `https://chromium.googlesource.com/webm/libwebp`, Linux runner·macOS Homebrew 패키지와 GitHub Actions/Artifact/API/Release에 연결합니다. 최신 공식 안정 소스·서명을 검증하고 다섯 플랫폼을 빌드해 정확한 크기·SHA-256 목록을 만들며 release Environment 공식 키로 서명하고 cross-repository token으로 Remote Content의 `ffmpeg-stable`을 갱신합니다. 개인 키는 다운로드 목적지로 보내지 않고 설치된 앱도 소스·빌드 의존성에 연결하지 않습니다.

로컬 `scripts/package-local.ps1`은 기본 오프라인 Windows portable을 만들 때 `curl.exe`로 `https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/`의 `ffmpeg-release.json`, `ffmpeg-release.json.sig`, `ffmpeg-windows-x64.zip`과 CDN을 읽습니다. 저장소 공식 키·정확한 파일명·크기·SHA-256을 검사하고 재검증한 캐시만 재사용합니다. 앱 계정·Pixiv 쿠키·공개 자격 증명은 없습니다. 빌드 머신 curl·프록시를 따르며 `-SkipOfflinePortable`은 이 요청을 생략하고 `-RedownloadFfmpeg`는 새로 받습니다. 설치 앱은 이 스크립트를 실행하지 않습니다.

### 빌드 의존성과 도구 다운로드

| 도구·작업 | 목적지·조건·데이터·중단 방법 |
| --- | --- |
| Maven / Wrapper | `https://repo.maven.apache.org/maven2`, 공개 SDK 검사의 `https://repo1.maven.org/maven2`. Maven 3.9.11·Java 의존성·build plugin을 받습니다. SDK Central 공개는 캐시에 없으면 repository Wrapper의 고정 배포를 다운로드·검증하고 공개 뒤 Central 해석·불변 내용을 확인합니다. Maven 저장소·미러·프록시·캐시를 따릅니다 |
| Compose Cupertino / Web iOS 구성 요소 | Central의 `io/github/robinpcrd/`에서 고정 Cupertino 3.3.1과 전이 의존성을 Gradle이 빌드 때 받습니다. Framework7 9.1.3 CSS·라이선스는 저장소에 있어 일반 빌드·앱은 다운로드하지 않습니다. 유지 관리자의 명시적 갱신만 `https://registry.npmjs.org/framework7`에 연결합니다. 공개 이름·버전·경로만 보내고 앱 자격 증명은 없습니다. 빌드 프록시·캐시를 따르며 배포 후 모두 로컬 사용입니다 |
| 앱·Swing·SDK 도구 의존성 | Central의 `com/networknt/json-schema-validator/`, `io/github/erdtman/java-json-canonicalization/`, `com/twelvemonkeys/imageio/imageio-webp/`와 전이 의존성을 POM 고정 버전으로 캐시 누락 때 받습니다. 앱·Swing·SDK 도구·내부 `pixivdownload-community-contract`가 WebP를, 공통 계약이 Schema/JCS도 사용하며 공개 SDK 다섯 구성 요소에는 추가하지 않습니다. Maven 설정으로 좌표만 보내고 제출·앱 데이터는 없습니다. 캐시 후 offline 빌드 가능하며 이미지·계약 검증은 내장 schema/catalog로 로컬 실행하고 원격 schema를 읽지 않습니다 |
| 커뮤니티 라이선스 리소스 갱신 | 명시적 유지 관리 때 `https://api.github.com/repos/spdx/license-list-data/`의 tag·commit·contents 또는 `https://raw.githubusercontent.com/spdx/license-list-data/<commit>/`에서 SPDX 목록·본문을 읽고 commit·크기·해시를 고정합니다. 일반 빌드·앱은 로컬 리소스만 쓰고 latest를 추적하지 않습니다. 공개 경로만 보내며 CLI는 기존 로그인·프록시를, raw는 공개 자격 증명 없이 사용합니다. 기존 리소스를 유지하면 요청하지 않습니다 |
| npm | 현재 lockfile의 `https://registry.npmmirror.com`. YAML parser가 없는 PowerShell orchestration 테스트는 새 CI에서도 `npm ci --ignore-scripts --no-audit --no-fund`를 실행합니다. 커뮤니티 유지 관리자는 도구 manifest의 인터랙션 bundle을 만들 때 고정 Clack·esbuild·전이 의존성도 설치합니다. npm registry·인증·프록시를 따르고 패키지 경로만 보내며 앱 데이터는 없습니다. 사전 설치·체크인 bundle 재사용으로 요청을 피하고 제출 wizard runtime은 npm에 연결하지 않습니다 |
| Maven·Gradle·sbt SDK 소비자 검사 | 전체 QG·SDK 공개 검사는 세 독립 소비자로 단일 SDK 의존성 컴파일·정확한 바이트·runtime scope를 확인합니다. `https://repo.maven.apache.org/maven2`, 고정 `org/scala-sbt/sbt-launch/1.10.11/sbt-launch-1.10.11.jar`, Maven/Gradle Wrapper 배포·CDN을 사용합니다. CI sbt는 고정 해시로 받고 로컬은 기존 launcher 지정 가능. 도구별 격리 캐시, Maven 빈 settings, Gradle/sbt Central, SDK 로컬 staging입니다. Maven/Gradle은 online 후 offline 재빌드, sbt는 online compile입니다. 공개 자격 증명·앱 데이터 없이 도구 버전·좌표·경로만 보냅니다. 머신 프록시를 따르며 로컬 `--tool`은 하나를 선택할 수 있지만 QG는 셋 모두 필수입니다. 소스 변경은 Wrapper·검사 스크립트 수정이 필요합니다 |
| 유지 관리자 목록 생성 | 매 앱 resource build에 `https://api.github.com/repos/Sywyar/PixivDownloader`, `/contributors`, `/users/{login}`, API 반환 `https://avatars.githubusercontent.com`을 읽습니다. owner·contributors·로컬 commit author/co-author를 관리되는 사람 허용 목록과 교차하여 승인 avatar·JSON을 앱에 넣습니다. CI는 읽기 `GITHUB_TOKEN`, 로컬은 `GITHUB_TOKEN`→`GH_TOKEN`→익명 순서입니다. 자격 증명은 api.github.com에만 보내고 API/avatar 모두 redirect 거부, avatar는 무인증입니다. 요청 30초, 명시적 속도 제한·일시 오류 최대 3회, 총 재시도 대기 180초입니다. 서버 대기를 존중하며 예산 초과·일반 권한 오류는 build 실패입니다. 진단은 비밀을 지운 API 메시지·request ID·rate limit 헤더입니다. Release 경계 검사는 현재 결과를 재사용하며 별도 disable switch가 없습니다. 설치 앱은 자동 연결하지 않습니다 |
| SDK 공개 기준 검사 | SDK Releases API, 공개 `sdk-release.json`, `https://repo1.maven.org/maven2/io/github/sywyar/pixivdownloader/`의 다섯 POM을 Release·수동 SDK 공개 전에 읽어 기존 신원 재사용을 판단합니다. 앱 Release는 같은 공개 소스를 비교하고 새 SDK·일반 PR은 최신 공개 정규 SDK와 API·Maven 소비자 계약을 비교합니다. 검증 Release 소스에서 임시 API 기준을 만들므로 repository snapshot 갱신은 없습니다. 선행판이 시험판·다른 major면 후보보다 높지 않은 같은 major 최신 안정판의 metadata·source·다섯 POM도 검증해 기준을 만듭니다. 안정 선행판은 현재 build를 재사용합니다. 실패는 검사를 막고 명령/workflow 취소로 후속 요청을 중단합니다. Nightly는 정규 선택·조회·공개에서 제외되고 PR은 읽기 검증만 합니다. CI GitHub는 읽기 Actions token, 로컬은 CLI 인증·네트워크, Central POM은 token 없는 Node 자체 네트워크입니다. Maven 의존성은 Maven 설정을 따릅니다. 공개 경로만 보내고 로컬 소스를 업로드하지 않습니다 |
| Docker | 설정한 OCI registry, 보통 `eclipse-temurin:17-jre`, base image의 Debian 패키지 소스. image pull과 FFmpeg·curl 등 설치에 사용하며 Docker·이미지 패키지 관리자 설정을 따릅니다 |
| Windows CI | 설정된 Chocolatey 소스에서 Inno Setup 같은 패키징 도구 설치 |
| Douyin SDK 호환성 | `scripts/ci/sdk-consumer.mjs`가 `scripts/ci/douyin-source.json`의 고정 commit을 `https://github.com/Sywyar/PixivDownloader-Plugin-Douyin.git`에서 fetch하며 main을 추적하지 않습니다. fetch·checkout 각각 300초입니다. 시험 SDK로 빌드·offline 재빌드하고 Java·JavaScript·artifact·호스트 통합 검사를 합니다. Git credential helper·대화형 prompt를 끄고 Git 프록시를 따르며 저장소 경로·commit만 보냅니다. `--douyin-source` 로컬 소스는 GitHub 취득을 피하지만 Maven 의존성 조회는 남습니다. 설치 앱은 실행하지 않습니다 |

## 링크와 자동 요청이 아닌 URL

앱·문서의 GitHub, Releases, 온라인 문서, Tampermonkey와 라이선스 링크는 클릭하거나 브라우저가 해당 리소스를 읽을 때 연결됩니다. XML namespace, POM schema 위치, 라이선스 본문의 URL과 예시 도메인은 그 자체로 자동 요청 목적지가 아닙니다.

앱 Web UI에서 외부 HTTP(S) 링크를 누르면 로컬 공지·설문 HTML 안의 링크도 공통 확인 창에서 목적지를 먼저 표시합니다. 명시적으로 확인해야 브라우저가 직접 연결하고 취소하면 요청하지 않습니다. Java 백엔드·전역 프록시를 우회하며 목적지는 클릭한 링크가 결정합니다. 같은 출처 앱 링크는 바로 이동합니다.
