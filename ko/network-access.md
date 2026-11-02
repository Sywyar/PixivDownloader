# 네트워크 접근 및 외부 서비스

이 페이지는 각 기능의 연결 대상, 전송 데이터, 프록시 경로와 비활성화 방법을 설명합니다. 앞부분은 설치된 애플리케이션의 **런타임 요청**이며 빌드, 테스트, CI와 커뮤니티 제출의 개발 요청은 [개발, 빌드 및 배포 과정의 네트워크 접근](#개발-빌드-및-배포-과정의-네트워크-접근)에 별도로 정리했습니다. 이 요청은 애플리케이션을 일상적으로 실행하는 것만으로 발생하지 않습니다.

?> 코드에 고정된 기본 주소와 제어되는 동적 주소 범위를 나열합니다. 외부 서비스는 DNS, CDN, 리디렉션이나 임시 다운로드 링크로 자산을 배포할 수 있습니다. 관리자가 지정한 사용자 정의 URL은 실제 설정에 따라 달라집니다.

## 런타임 개요 및 프록시 경로

- **요청 발생 조건**: AI, 번역, TTS, 푸시, 이메일, CAPTCHA와 Douyin은 활성화되고 호출될 때만 네트워크에 연결합니다. `notification` 플러그인은 활성화 후 백그라운드에서 서명된 공지 색인을 주기적으로 가져옵니다. 온라인 업데이트와 자동 확인을 켜면 호스트가 시작 준비를 마친 후 GitHub Releases를 확인합니다. 데스크톱과 헤드리스 서비스는 준비 후 등록된 작품의 누락 메타데이터를 필요에 따라 백그라운드에서 보완합니다.
- **플러그인 마켓**: `plugin-catalog.enabled`, 공식 저장소와 커뮤니티 저장소는 기본적으로 켜져 있지만 애플리케이션 시작 자체로 원격 매니페스트를 가져오지는 않습니다. 관리자 페이지에서 플러그인 업데이트 확인, 마켓 열기/새로고침, 저장소 미리보기, 플러그인 설치 또는 취소 정보 수동 갱신을 할 때만 해당 저장소에 요청합니다.
- **PostHog 설문**: 레이아웃 설문(`download-workbench`)과 다중 사용자 모드 설문(`multi-mode-decision-survey`)의 공개 인수는 플러그인 소스의 `posthog-config.js`에 저장됩니다. 일반 소스와 fork 빌드가 생성하는 `release-activation.js`는 기본 `false`이므로 PostHog에 기본 연결하지 않습니다.
- **아웃바운드 프록시 경로**:
  - Pixiv, 온라인 업데이트, FFmpeg 자동 다운로드, 공식/커뮤니티 플러그인 저장소, 서버 CAPTCHA 검증과 공지 동기화는 호스트 또는 작업별 프록시 설정을 사용합니다.
  - AI, 번역, TTS와 Douyin은 기능별 또는 작업별 직접 연결 / 프록시 선택을 지원합니다. `direct-strict` 사용자 정의 플러그인 저장소는 직접 연결을 강제합니다.
  - SMTP는 `mail` 플러그인의 자체 연결과 선택적 SOCKS 프록시를 사용합니다. PostgreSQL JDBC는 대상 데이터베이스 서버에 직접 연결하며 HTTP 프록시를 사용하지 않습니다.
  - 브라우저 동일 출처 API, 소개 페이지의 Google Fonts, 브라우저 Web Speech API, CAPTCHA 프런트엔드 위젯, PostHog와 사용자 스크립트는 브라우저가 직접 요청하며 Java 백엔드 프록시를 거치지 않습니다.

## 코어 호스트 및 기본 요청

- **동일 출처 인증 및 국제화**: 로그인 페이지는 동일 출처 `/api/auth/check`, `/api/auth/login`, `/api/auth/invite-redeem`으로 세션을 확인하고 인증을 제출합니다. 복귀 주소는 동일 출처의 사이트 내부 경로만 허용합니다. 프런트엔드 페이지는 `/api/i18n/meta`, `/api/i18n/messages/{namespace}`와 동일 출처 `i18n-static/`에서 사전을 로드합니다. 요청당 시간 제한은 10초이며 누락된 사전은 1.5, 5, 15초 간격으로 최대 3회 재시도합니다. 외부 번역 서비스에 연결하지 않습니다.
- **로컬 GUI 채널**: 데스크톱 모드는 무작위 포트의 `http://127.0.0.1:{随机端口}/api/gui/**`로 상태를 폴링하고 작업을 제출합니다. 백엔드가 생성한 GUI token을 포함하며 로컬 직접 연결을 강제하고 프록시, 리디렉션과 전달 헤더를 거부합니다. `--no-gui` 모드에서는 이 포트를 만들지 않습니다.
- **외부 링크 확인**: 애플리케이션 Web 페이지에서 외부 HTTP(S) 링크를 누르면 대상 주소 확인 대화상자를 먼저 표시하며 확인한 후에만 브라우저로 엽니다.

| 소유자 | 대상 주소 | 용도 및 전송 내용 | 발생 조건 및 비활성화 방법 |
| --- | --- | --- | --- |
| 애플리케이션 호스트(업데이트 확인 및 다운로드) | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json` 및 `.sig`, nightly의 동명 매니페스트, 서명 검증된 매니페스트의 설치 패키지 URL(GitHub Release CDN 리디렉션 포함) | 업데이트 확인 및 내장 Ed25519 서명 검증. 매니페스트/서명 상한 1 MiB / 16 KiB. 사용자가 업데이트를 확인한 후에만 설치 패키지 다운로드. 상한 500 MiB, 정확한 크기와 SHA-256 확인. 현재 버전, Cookie나 자격 증명은 보내지 않음 | `update.enabled`와 `update.auto-check` 기본 활성화. nightly 사용 시 두 매니페스트를 독립 확인. 호스트 프록시 사용, 공인 HTTPS 리디렉션 최대 5회. 해당 설정을 끄면 비활성화 |
| 애플리케이션 호스트(소개 페이지 글꼴) | `https://fonts.googleapis.com/css2?...`, `https://fonts.gstatic.com/...` | 브라우저가 Noto Sans SC를 로드하며 표준 브라우저 연결 정보만 노출 | `intro.html`을 열 때 발생. 백엔드 프록시를 사용하지 않으며 도메인 차단 시 로컬 글꼴로 자동 대체 |
| 애플리케이션 호스트(연결 검사) | `https://www.pixiv.net/` | 백엔드의 Pixiv 연결 검사. Pixiv Cookie를 포함하지 않음 | 초기 설정 안내 또는 수동 검사 시 발생. 백그라운드 heartbeat 없음 |
| `notification` 플러그인 | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json`, 인접한 `.sig` 및 `.../announcements/<message-id>/<locale>.html` | 공식 Ed25519 서명, 유효 기간, 증가 순번과 본문 SHA-256 검증. 알 수 없거나 다이제스트가 바뀐 공지만 다국어 제어 HTML로 다운로드하여 로컬 저장. Cookie나 애플리케이션 자격 증명은 보내지 않음 | 시작 후 0~30분 무작위 대기, 이후 6시간마다(±15%) 확인. `ETag`/`Last-Modified` 조건부 요청과 재시도 지연 지원. 호스트 프록시 사용. `notification` 비활성화 또는 제거 시 중지 |

## Pixiv 다운로드, 탐색 및 가져오기

| 소유자 | 대상 주소 | 용도 및 전송 내용 | 발생 조건 및 비활성화 방법 |
| --- | --- | --- | --- |
| 애플리케이션 호스트(공유 조회 및 썸네일) | `https://www.pixiv.net/ajax/user/{id}?lang=zh`; HTTPS `*.pximg.net`, `embed.pixiv.net` | `/api/pixiv/user/{id}/meta`는 요청이 제공한 가져오기 자격 증명 사용. `/api/pixiv/thumbnail-proxy`는 해당 이미지 도메인만 접근하며 Cookie와 리디렉션 없이 요청당 최대 100 MiB | 페이지가 해당 로컬 API를 요청할 때 발생. 관련 미리보기를 중지하면 방지 가능 |
| 애플리케이션 호스트(메타데이터 보완) | `https://www.pixiv.net/ajax/illust/{id}`, `/ajax/novel/{id}` | 등록된 작품 폴더의 이전 `.meta.json`을 먼저 가져오고 로컬 메타데이터로 보완. 여전히 누락된 경우에만 Cookie 없이 해당 API 요청. 요청당 최대 4 MiB. 404는 `data/backfill/unreachable.json`에 기록하며 연령 제한 응답은 자동 재시도 보류 | 데스크톱과 헤드리스 서비스가 준비된 후 백그라운드 자동 실행 또는 데스크톱 도구 / 독립 CLI에서 수동 실행. 저장된 전역 프록시 사용, 도구 페이지에서 이번 작업만 덮어쓰기 가능. 도구 페이지나 서비스 중지로 취소 |
| `download-workbench` 플러그인 | `https://www.pixiv.net/ajax/illust/**`, `/ajax/user/**`, `/ajax/search/artworks/**`, `/ajax/series/**`, `/ajax/collection/**`, `/ajax/follow_latest/illust`, `/rpc/index.php`, `/ajax/illusts/bookmarks/add` | 일러스트/움짤/작가/시리즈/검색/컬렉션/팔로우 새 작품/커미션 메타데이터 가져오기. 일반 JSON 상한 4 MiB, 팔로우 새 작품의 총 페이지 수를 모르면 최대 1,000페이지 검사. '자동 북마크'를 켜면 다운로드 후 북마크 추가. 필요에 따라 Pixiv Cookie 포함 | 미리보기, 빠른 가져오기, 다운로드, 예약 작업, 북마크 수 필터 추가 조회(한 번에 최대 6개 병렬) 또는 시리즈 소개 추가 조회 시 발생. 플러그인이나 자동 북마크를 끄면 해당 요청 중지 |
| `novel` 플러그인 | `https://www.pixiv.net/ajax/novel/**`, `/ajax/novel/series/**`, `/ajax/novel/series_content/**`, `/ajax/user/**/novels`, `/ajax/user/**/novels/bookmarks`, `/ajax/search/novels/**`, `/ajax/novels/bookmarks/add` | 소설 본문, 시리즈(응답 상한 1 MiB), 작가 소설, 북마크, 검색 결과와 다운로드 후 자동 북마크. 미리보기 직후 다운로드는 owner와 자격 증명에 연결된 256 KiB의 단기 일회용 티켓으로 검증된 응답 재사용 | 소설 미리보기, 합본, 검색과 예약 작업 시 발생. `novel` 비활성화 시 중지 |
| 애플리케이션 호스트(공유 이미지 전송) | HTTPS `*.pximg.net`. 썸네일에는 `embed.pixiv.net`도 허용 | 원본 이미지, 표지, 썸네일, Ugoira ZIP과 소설 내장 이미지(최대 512개 매핑) 다운로드. Pixiv Referer 포함. 2xx가 아니면 오류 본문을 버퍼링하지 않음 | 작품 다운로드, 표지/썸네일 또는 소설 파일 생성 시 발생 |
| 루트 폴더 사용자 스크립트 | `https://www.pixiv.net/ajax/**`, `*.pximg.net`; 설정한 백엔드 주소(기본 `http://localhost:6999`) | Pixiv 페이지에서 브라우저 로그인 상태로 작품 읽기 또는 로컬 다운로드. 백엔드의 다운로드, 대기열, 컬렉션, 소설 상태, `/api/i18n/meta`(3초 제한) 및 도구 모음 세션/Cookie 동기화 API 호출. 소설 티켓 API에는 Pixiv Cookie를 보내지 않음 | Pixiv 페이지에서 스크립트를 조작할 때 브라우저/스크립트 관리자가 직접 연결. 스크립트 비활성화 시 중지 |
| `pixiv-batch-downloader-import` 플러그인 및 스크립트 | 로컬 `/api/pixiv-batch-downloader-import/token`, `/api/pixiv-batch-downloader-import`, `/api/pixiv-batch-downloader-import/history`; 브라우저 동일 출처 `/ajax/illust/{id}`, `/ajax/novel/{id}`; 데스크톱 기록 가져오기는 호스트를 통해 `https://www.pixiv.net/ajax/{illust,novel}/{id}` 요청; 스크립트 업데이트 소스 | 자동 가져오기는 로컬 파일 경로와 메타데이터를 solo 루프백 서비스로 전송. 브라우저 기록 가져오기는 브라우저 안에서 동일 출처 Cookie로 메타데이터를 가져와 로컬에서 검증. 데스크톱 기록 가져오기는 사용자가 선택적으로 입력한 일회성 Cookie로 메타데이터 취득. 모두 원본 작품 파일은 업로드하지 않음 | 해당 플러그인과 스크립트를 설치·활성화하거나 기록 가져오기를 직접 실행할 때만 발생. 스크립트/플러그인 비활성화 또는 작업 취소 시 중지 |

또한 SDK `DownloadTasks.submit` 또는 관리자 `POST /api/download/tasks`로 작업을 제출하면 해당 플러그인이 메타데이터와 미디어를 가져옵니다. 작업 화면이 보이는 동안 동일 출처 `GET /api/download/tasks`를 2초마다 폴링하며 종료 상태는 5분간 유지합니다.

## 서드파티 및 선택적 기능 플러그인

### `douyin` 플러그인(필요에 따라 설치하는 서드파티)

기본 배포 패키지에는 포함되지 않습니다. 수동 설치하고 활성화한 후 Douyin 작업을 실행할 때만 접근합니다.

- `https://www.douyin.com/aweme/v1/web/**`: 사용자 작품, 좋아요, 모음, 검색, 음악, 컬렉션, 계정과 작품 상세 정보를 가져옵니다. 사용자가 설정한 Douyin Cookie, Cookie에서 추출한 UIFID와 로컬에서 계산한 `x-secsdk-web-signature`를 포함합니다.
- HTTPS `*.douyin.com`, `*.iesdouyin.com`(`v.douyin.com` 등): 제한된 리디렉션으로 단축 링크를 해석합니다.
- HTTPS `*.douyinvod.com`, `*.douyinpic.com`, `*.douyinstatic.com`, `*.amemv.com`, `*.byteimg.com`, `*.bytedance.com`, `*.bytecdn.cn`, `*.pstatp.com`, `*.snssdk.com`: 동영상, 이미지 모음, 표지와 라이브 사진을 다운로드합니다.

경로는 '작업별 프록시 → 소스 기본 경로 → 호스트 전역 프록시' 순서로 선택하며 작업 중지나 플러그인 비활성화 시 요청을 중지합니다.

### `ai` 및 `translation` 플러그인

AI 플러그인은 OpenAI 호환 프로토콜을 사용합니다. 연결 테스트, 소설 번역과 다중 화자 분석은 선택한 기본 주소의 `/chat/completions`에 텍스트, 프롬프트, 모델 이름과 API Key를 보냅니다. 소설 번역에는 최대 1,000개의 용어 매핑 항목을 포함합니다. 데스크톱 설정에서 모델 목록 가져오기를 누르면 초안 기본 주소의 `/models`에 GET을 보냅니다. 먼저 익명으로 요청하고 401/403을 받았으며 키가 입력되어 있으면 Bearer 키로 한 번 재시도합니다. `https://api.anthropic.com/v1`은 [Anthropic 프로토콜](https://platform.claude.com/docs/en/api/models/list)에 따라 `anthropic-version`, `x-api-key`와 `after_id` 페이지 처리를 사용합니다. 모든 요청은 Cookie를 저장하거나 리디렉션을 따르지 않습니다.

| AI 프리셋 | 기본 주소 | AI 프리셋 | 기본 주소 |
| --- | --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | Zhipu | `https://open.bigmodel.cn/api/paas/v4` |
| Anthropic 호환 | `https://api.anthropic.com/v1` | Moonshot | `https://api.moonshot.cn/v1` |
| Gemini 호환 | `https://generativelanguage.googleapis.com/v1beta/openai` | Doubao / Volcengine Ark | `https://ark.cn-beijing.volces.com/api/v3` |
| xAI | `https://api.x.ai/v1` | Tencent Hunyuan / TokenHub | `https://tokenhub.tencentmaas.com/v1` |
| Mistral | `https://api.mistral.ai/v1` | Baidu Qianfan / ERNIE | `https://qianfan.baidubce.com/v2` |
| Groq | `https://api.groq.com/openai/v1` | iFLYTEK Spark | `https://spark-api-open.xf-yun.com/v1` |
| DeepSeek | `https://api.deepseek.com` | MiniMax | `https://api.minimax.cn/v1` |
| Alibaba Cloud Model Studio / Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenRouter / SiliconFlow | `https://openrouter.ai/api/v1`, `https://api.siliconflow.cn/v1` |
| Ollama(로컬) | `http://localhost:11434/v1` | LM Studio(로컬) | `http://localhost:1234/v1` |

`translation` 플러그인은 수동 또는 자동 번역을 시작할 때 선택한 엔진을 호출합니다. AI는 위 설정을 재사용합니다. Youdao는 `https://openapi.youdao.com/api`에 텍스트, 애플리케이션 ID와 로컬에서 계산한 v3 서명을 보내며 애플리케이션 비밀 키를 직접 보내지 않습니다. LibreTranslate는 관리자가 설정한 `{libretranslate-url}/translate`를 요청하며 선택적으로 `api_key`를 사용합니다. Youdao와 LibreTranslate의 연결 시간 제한은 30초, 읽기는 120초입니다. 요청당 최대 1,000 Unicode 문자, 응답 최대 1 MiB이며 `translation.use-proxy`로 애플리케이션 프록시 사용 여부를 제어합니다.

### `tts`, `push` 및 `mail` 플러그인

| 플러그인 | 서비스 / 채널 | 기본 대상 주소 |
| --- | --- | --- |
| `tts` | 브라우저 엔진 | 브라우저가 시스템 / 브라우저 Web Speech API 직접 호출. 백엔드를 거치지 않음 |
| `tts` | Edge TTS 및 버전 탐색 | `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`, 동일 호스트 `/voices/list`; 핸드셰이크 403으로 버전 갱신 시 `https://edgeupdates.microsoft.com/api/products?view=enterprise` 요청 |
| `tts` | 클라우드 및 자체 호스팅 TTS | Xiaomi MiMo `https://api.xiaomimimo.com/v1/chat/completions`; Fish Audio `https://api.fish.audio/v1/tts`; MiniMax `https://api.minimax.io/v1/t2a_v2`(중국 내 `https://api.minimaxi.chat/v1/t2a_v2`); ElevenLabs `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}`; Alibaba Cloud Model Studio `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`(국제 리전 `dashscope-intl.aliyuncs.com`) 및 반환된 임시 오디오 URL; Doubao `https://openspeech.bytedance.com/api/v1/tts`; VoxCPM / CosyVoice `{base-url}/audio/speech`(VoxCPM은 `/models`도 조회) |
| `push` | 푸시 채널 | Bark `https://api.day.app/push`(자체 호스팅으로 변경 가능); DingTalk `https://oapi.dingtalk.com/robot/send`; Feishu `https://open.feishu.cn/open-apis/bot/v2/hook/{key}`; WeCom `https://qyapi.weixin.qq.com/cgi-bin/webhook/send`; Telegram `https://api.telegram.org/bot{token}/sendMessage`; PushPlus `https://www.pushplus.plus/send`; ServerChan Turbo `https://sctapi.ftqq.com/{key}.send`; ServerChan³ `https://{uid}.push.ft07.com/send/{key}.send`; 사용자 정의 Webhook URL |
| `mail` | SMTP 프리셋 및 사용자 정의 | NetEase `smtp.163.com:465` / `126.com` / `yeah.net` / `qiye.163.com`; Tencent `smtp.qq.com:465` / `exmail.qq.com:465`; Sina `smtp.sina.com:465`; Gmail `smtp.gmail.com:587`; Outlook `smtp-mail.outlook.com:587`; Microsoft 365 `smtp.office365.com:587`; iCloud `smtp.mail.me.com:587`; Yahoo `smtp.mail.yahoo.com:465`; Alibaba 기업 이메일 `smtp.qiye.aliyun.com:465`; 또는 사용자 정의 SMTP + 선택적 SOCKS 프록시 |

자격 증명을 포함하는 TTS와 Push HTTP 요청은 Cookie를 저장하거나 리디렉션을 따르지 않습니다. 해당 플러그인이나 채널을 끄거나 자격 증명을 비우면 연결을 중지합니다.

### 플러그인 마켓, FFmpeg, 데이터베이스, 페이지 편집기 및 CAPTCHA

- **플러그인 마켓 및 취소 검사**:
  - 공식 저장소는 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`(nightly는 `nightly-manifest.json`), 필요에 따른 기록 조각 `history/<pluginId>-<sha256>.json`, 버전 Release의 마켓 문서/스크린샷 첨부 파일과 플러그인 패키지 URL을 읽습니다.
  - 커뮤니티 저장소는 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/generated/current.json`, 세대 폴더 조각, 취소 목록, 검토 기록과 서명된 Release 자산을 읽습니다.
  - 사용자 정의 저장소는 관리자가 가져온 `repository.json`(최대 64 KiB)과 그 안에서 선언한 `manifest-v1` / `paged-v2` 엔드포인트, `revocations.json`, `repository-update.json`을 읽습니다.
  - 설치된 플러그인의 업데이트 확인 결과는 5분간 캐시하며 일부 실패 시 30초입니다. 호환되는 이전 버전 대체 조회는 플러그인당 최대 10페이지이며 10초의 공유 시간 예산을 사용합니다. `plugin-catalog.enabled`나 해당 저장소 스위치를 끄면 비활성화됩니다.
- **FFmpeg 자동 설치**: 데스크톱 상태 페이지에서 다운로드하거나 Windows 설치 프로그램에서 FFmpeg 작업을 선택하면 `https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/ffmpeg-release.json`, `.sig`와 해당 플랫폼의 `ffmpeg-*.zip`을 요청합니다. 공식 Ed25519 서명, 크기와 SHA-256을 검증한 후 압축을 풉니다. 데스크톱은 호스트 프록시를 사용하며 Windows 설치 프로그램은 현재 사용자의 시스템 HTTP/HTTPS 프록시를 우선 사용합니다.
- **사용자 스크립트 업데이트**: 저장소에서 설치한 개별 스크립트는 Tampermonkey가 `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js`에 접근합니다. 백엔드에서 설치한 스크립트는 해당 백엔드의 `/api/scripts/*.user.js`를 가리킵니다.
- **공식 PostHog 설문 플러그인**: 공식 배포 빌드에서 활성화되어(`release-activation.js`가 `true`) 관리자가 유효한 설문이 있는 알림함 메시지를 열거나 다운로드 페이지 레이아웃 설문을 실행할 때만 브라우저가 `https://layout-survey.sywyar.top`과 `https://us.posthog.com`에 직접 접근합니다. 파생된 설문 범위 익명 식별자와 답변만 보내며 설치 UUID, 계정, Cookie나 경로는 보내지 않습니다. `posthog` 플러그인을 끄면 비활성화됩니다.
- **PostgreSQL 데이터베이스 플러그인**: JDBC로 관리자가 설정한 `database.jdbc-url`에 연결합니다. 기본 포트는 5432이며 연결 사전 검사 시간 제한은 10초입니다. 데이터베이스 인증 정보, 업무 데이터와 전용 DB 데이터를 보내며 대상 서버에 직접 연결하고 HTTP 프록시를 거치지 않습니다. [설정 참조](/ko/configuration#주-데이터베이스-및-엔진-전환)를 참고하세요.
- **고정 외부 공인 대상이 없는 플러그인**: `gallery`, `gallery-tools`, `gui-compose`, `gui-swing`, `page-editor` 자체는 현재 인스턴스의 동일 출처 API만 사용하며 고정된 외부 공인 요청을 보내지 않습니다. `page-editor`는 로컬 GrapesJS와 CodeMirror를 내장하고 CDN과 원격 측정을 끕니다.
- **`captcha` 작업 CAPTCHA 플러그인**:
  - **ALTCHA(SHA-256 / PBKDF2), 문자 CAPTCHA(Hutool), 슬라이더 퍼즐(Tianai)**: 현재 인스턴스와 브라우저 로컬에서 모두 처리하며 외부 서비스에 접근하지 않습니다.
  - **Cloudflare Turnstile**: 연결하고 게시한 후 브라우저가 `https://challenges.cloudflare.com/turnstile/v0/api.js`와 iframe을 로드합니다. 제출 시 서버는 관리되는 HTTP로 `/turnstile/v0/siteverify`에 비밀 키와 티켓을 보냅니다. 애플리케이션 프록시를 상속하고 리디렉션과 Cookie는 금지합니다.
  - **Google reCAPTCHA v2**: 연결하고 게시한 후 브라우저가 `https://www.google.com/recaptcha/api.js?render=explicit` 및 `www.gstatic.com`, `recaptcha.google.com`을 로드합니다. 제출 시 서버는 관리되는 HTTP로 `https://www.google.com/recaptcha/api/siteverify`에 비밀 키와 티켓을 보냅니다. 컴포넌트를 제거하고 페이지를 다시 게시하면 외부 요청이 중지됩니다.

## 개발, 빌드 및 배포 과정의 네트워크 접근

다음 요청은 개발자나 CI가 소스 빌드, 의존성 해석, 테스트, 패키징, 서명, 배포 또는 커뮤니티 제출 도구를 실행할 때만 발생합니다. **설치된 클라이언트의 런타임은 이 요청을 보내지 않습니다.**

| 개발 단계 | 접근 대상 및 용도 |
| --- | --- |
| Maven / Gradle / sbt 및 npm 의존성 해석 | Maven Wrapper와 Java 모듈은 설정된 Maven 저장소/미러(기본 `https://repo.maven.apache.org/maven2`, `https://repo1.maven.org/maven2`, `https://plugins.gradle.org`)에서 빌드 도구와 의존성(Tink, PostgreSQL/SQLite/H2 JDBC, Hutool/Tianai/ALTCHA, Cupertino, WebP/JSON Schema/JCS, sbt-launch 등)을 다운로드. 프런트엔드 검사와 플러그인 리소스 빌드는 잠금 파일의 npm 소스(현재 `https://registry.npmmirror.com`, Web 리소스 유지 시 간혹 `https://registry.npmjs.org`)에서 의존성 다운로드 |
| 브라우저 및 데이터베이스 통합 검증 | Playwright 브라우저 설치는 `cdn.playwright.dev` / `playwright.download.prss.microsoft.com`에서 Chromium 다운로드. `page-editor-acceptance.mjs` CAPTCHA 검증을 명시적으로 실행하면 공식 공개 테스트 키로 Cloudflare Turnstile과 Google reCAPTCHA 테스트 엔드포인트 접근. PostgreSQL 컨테이너 검증은 Docker `postgres:17`을 가져오고 `PIXIV_TEST_POSTGRES_URL`의 격리 DB에만 연결 |
| 유지 관리자 목록, 라이선스 및 로컬 패키징 스크립트 | 애플리케이션 리소스 빌드 시 GitHub API(`api.github.com/repos/Sywyar/PixivDownloader`)와 `avatars.githubusercontent.com`에서 허용 목록의 기여자 정보 및 아바타 취득. 시스템/환경 HTTP(S) 프록시 사용, 30초 제한, 오프라인 빌드는 자동 건너뜀. 라이선스 유지 스크립트는 필요에 따라 `spdx/license-list-data` 조회. `package-local.ps1`은 오프라인 패키지 빌드 시 `ffmpeg-stable` 자산 다운로드 및 서명 검증. `package-installer-with-plugins.ps1 -PluginSource Catalog`는 공식 플러그인 저장소에서 서명 매니페스트와 패키지 다운로드 |
| SDK 검증, 배포 및 저장소 간 Pages 빌드 | `sdk-legacy-plugin.mjs`는 고정된 `gui-swing-v1.0.2` 자산을 다운로드하여 바이너리 호환성 검증. `sdk-consumer.mjs`는 `Sywyar/PixivDownloader-Plugin-Douyin.git`의 고정 커밋을 가져와 격리 소비자 검증. SDK 배포 워크플로는 Sonatype Central Portal에 SDK Maven 좌표 5개를 배포하고 `Sywyar/PixivDownloader-Plugin-SDK`에 서명된 Release 생성 및 Javadoc GitHub Pages 빌드. 개발자가 SDK 프로젝트에서 `sdk-prepare` / `sdk-run`을 처음 실행하면 SDK Release의 고정 실행 패키지를 다운로드하여 `~/.cache/pixivdownloader-sdk/`에 캐시 |
| 주 저장소 CI, Gate Checks, Release 및 공지 재서명 | GitHub Actions가 Quality Gate, 배포 패키지 E2E, 서명된 플러그인 배포와 `Gate Checks` 실행. GitHub API와 App token으로 열린 PR에 필수 검사 발급. `PixivDownloader-Remote-Content`의 예약 워크플로는 `announcements/index.json.sig`를 검사·재서명하고 Pages 배포 실행 |
| 커뮤니티 플러그인 제출 마법사, 격리 빌드 및 검토 배포 | 제출 진입점 `irm https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/tools/submit.ps1 \| iex`는 서명된 `submission-channel.json`과 고정 커밋 도구를 다운로드. `no_proxy` → `https_proxy` / `all_proxy` → Windows 시스템 프록시 순서로 네트워크 해석. 파일당 60초, 최대 3회 시도. GitHub CLI / Git으로 커뮤니티 저장소와 제출자 fork 접근, 후보 Release 자산 및 문서 이미지 업로드. 커뮤니티 CI는 제어되는 Squid 프록시 컨테이너에서 승인된 Maven / Gradle 소스의 의존성만 미리 받고, 네트워크가 끊긴 컨테이너에서 플러그인을 재빌드. GitHub Actions OIDC / Sigstore(`fulcio.sigstore.dev`, `rekor.sigstore.dev`, `tuf-repo.github.com`)로 아카이브 증명을 생성하고 검토 병합 및 취소 목록 재서명 수행 |
