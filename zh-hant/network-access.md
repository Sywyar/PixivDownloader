# 網路存取與第三方服務

本頁說明各功能連線的目標位址、傳送的資料、代理路由與關閉方式。前半部為已安裝應用程式的**執行期請求**；建置、測試、CI 與社群投稿的工程請求另列於[開發、建置和發布流程的網路存取](#開發、建置和發布流程的網路存取)，不會因日常執行應用程式而觸發。

?> 本頁列出程式碼內固定的預設位址及受控動態位址範圍。第三方服務可能經 DNS、CDN、重新導向或暫時下載連結散布資源；管理員自行設定的自訂 URL 取決於實際設定。

## 執行期概覽與代理路由

- **觸發原則**：AI、翻譯、TTS、推播、郵件、驗證碼和 Douyin 僅在啟用且被呼叫時連網；`notification` 外掛啟用後，會在背景定期取得簽署公告索引；啟用線上更新與自動檢查時，宿主會在啟動就緒後檢查 GitHub Releases；桌面與無頭服務會在就緒後，依需求於背景回填已登記作品缺少的中繼資料。
- **外掛市場**：`plugin-catalog.enabled`、官方儲存庫與社群儲存庫預設開啟，但應用程式啟動本身不會取得遠端清單；僅在管理員頁面檢查外掛更新、開啟/重新整理市場、預覽儲存庫、安裝外掛或手動重新整理撤銷資訊時，才請求對應儲存庫。
- **PostHog 調查**：版面調查（`download-workbench`）與多人模式調查（`multi-mode-decision-survey`）的公開參數儲存在外掛原始碼的 `posthog-config.js`；一般原始碼與 fork 建置產生的 `release-activation.js` 預設為 `false`，預設不連線至 PostHog。
- **出站代理路由**：
  - Pixiv、線上更新、FFmpeg 自動下載、官方/社群外掛儲存庫、伺服器端驗證碼核驗與公告同步，沿用宿主或任務層級代理設定。
  - AI、翻譯、TTS 和 Douyin 支援功能層級或任務層級的直接連線 / 代理選擇；`direct-strict` 自訂外掛儲存庫強制直接連線。
  - SMTP 使用 `mail` 外掛自有連線與選用 SOCKS 代理；PostgreSQL JDBC 直接連線至目標資料庫伺服器，不經 HTTP 代理。
  - 瀏覽器同源 API、介紹頁 Google Fonts、瀏覽器端 Web Speech API、驗證碼前端控制項、PostHog 與使用者腳本由瀏覽器直接發起，不經 Java 後端代理。

## 核心宿主與預設請求

- **同源驗證與國際化**：登入頁透過同源 `/api/auth/check`、`/api/auth/login`、`/api/auth/invite-redeem` 檢查工作階段與提交驗證（返回位址僅接受同源站內路徑）。前端頁面透過 `/api/i18n/meta`、`/api/i18n/messages/{namespace}` 與同源 `i18n-static/` 載入字典（單次逾時 10 秒，缺少字典時最多重試 3 輪：1.5、5、15 秒），不連線至外部翻譯服務。
- **本機 GUI 通道**：桌面模式透過 `http://127.0.0.1:{隨機連接埠}/api/gui/**` 輪詢狀態與提交操作，攜帶後端產生的 GUI token，強制本機直接連線並拒絕代理、重新導向與轉送標頭；`--no-gui` 模式不建立此連接埠。
- **外部連結確認**：在應用程式 Web 頁面點擊外部 HTTP(S) 連結時，會先出現目標位址確認視窗，確認後才由瀏覽器開啟。

| 擁有者 | 目標位址 | 用途與傳送內容 | 觸發與關閉方式 |
| --- | --- | --- | --- |
| 應用程式宿主（更新檢查與下載） | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json` 及 `.sig`、nightly 同名清單、已核驗簽章清單中的安裝包 URL（含 GitHub Release CDN 重新導向） | 檢查更新並核驗內建 Ed25519 簽章（清單/簽章上限 1 MiB / 16 KiB）；僅在使用者確認更新後下載安裝包（上限 500 MiB，核對精確大小與 SHA-256）。不傳送目前版本、Cookie 或憑證 | `update.enabled` 與 `update.auto-check` 預設開啟（啟用 nightly 時會獨立檢查兩路清單）；沿用宿主代理，最多 5 跳公網 HTTPS 重新導向。關閉上述設定即可停用 |
| 應用程式宿主（介紹頁字型） | `https://fonts.googleapis.com/css2?...`、`https://fonts.gstatic.com/...` | 瀏覽器載入 Noto Sans SC 字型，僅暴露標準瀏覽器連線資訊 | 開啟 `intro.html` 時觸發；不經後端代理，封鎖網域後會自動退回本地字型 |
| 應用程式宿主（連線探測） | `https://www.pixiv.net/` | 偵測後端至 Pixiv 的連線能力，不攜帶 Pixiv Cookie | 首次設定引導或手動執行探測時觸發，無背景心跳 |
| `notification` 外掛 | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json`、相鄰 `.sig` 及 `.../announcements/<message-id>/<locale>.html` | 核驗官方 Ed25519 簽章、有效期限、遞增序號與正文 SHA-256，僅為未知或摘要變更的公告下載多語言受控 HTML 並儲存在本地；不傳送 Cookie 或應用程式憑證 | 啟動後隨機等待 0–30 分鐘，之後每 6 小時（±15%）檢查（支援 `ETag`/`Last-Modified` 條件請求與退避）。沿用宿主代理；停用或解除安裝 `notification` 即停止 |

## Pixiv 下載、瀏覽與匯入

| 擁有者 | 目標位址 | 用途與傳送內容 | 觸發與關閉方式 |
| --- | --- | --- | --- |
| 應用程式宿主（共用查詢與縮圖） | `https://www.pixiv.net/ajax/user/{id}?lang=zh`；HTTPS `*.pximg.net`、`embed.pixiv.net` | `/api/pixiv/user/{id}/meta` 使用請求提供的取得憑證；`/api/pixiv/thumbnail-proxy` 僅存取上述圖片網域，不帶 Cookie、不跟隨重新導向，單次上限 100 MiB | 頁面請求對應本地介面時觸發；停止相關預覽即可避免 |
| 應用程式宿主（中繼資料回填） | `https://www.pixiv.net/ajax/illust/{id}`、`/ajax/novel/{id}` | 先匯入已登記作品目錄的舊 `.meta.json` 並用本地中繼資料補缺，仍有缺少才不帶 Cookie 請求上述介面（單次上限 4 MiB；404 記入 `data/backfill/unreachable.json`，年齡限制回應會暫緩自動重試） | 桌面與無頭服務啟動就緒後，於背景自動回填，或從桌面工具頁 / 獨立 CLI 手動執行；讀取已儲存的全域代理（工具頁可單次暫時覆寫），可在工具頁或停止服務取消 |
| `download-workbench` 外掛 | `https://www.pixiv.net/ajax/illust/**`、`/ajax/user/**`、`/ajax/search/artworks/**`、`/ajax/series/**`、`/ajax/collection/**`、`/ajax/follow_latest/illust`、`/rpc/index.php`、`/ajax/illusts/bookmarks/add` | 取得插畫/動圖/畫師/系列/搜尋/收藏夾/關注新作/約稿中繼資料（一般 JSON 上限 4 MiB，關注新作總頁數未知時，最多掃描 1,000 頁），以及開啟「自動收藏」後，在下載完成時新增收藏；依需求攜帶 Pixiv Cookie | 預覽、快速取得、下載、排程工作、收藏數篩選補查（每次最多並行 6 件）或系列簡介補查時觸發；停用外掛或關閉自動收藏可停止對應請求 |
| `novel` 外掛 | `https://www.pixiv.net/ajax/novel/**`、`/ajax/novel/series/**`、`/ajax/novel/series_content/**`、`/ajax/user/**/novels`、`/ajax/user/**/novels/bookmarks`、`/ajax/search/novels/**`、`/ajax/novels/bookmarks/add` | 取得小說正文、系列（回應上限 1 MiB）、作者小說、收藏、搜尋結果及下載後自動收藏；預覽後緊接的下載，會透過綁定 owner 與憑證的 256 KiB 短期一次性票據，重用已驗證回應 | 小說預覽、合訂、搜尋、排程工作時觸發；停用 `novel` 外掛即停止 |
| 應用程式宿主（共用圖片傳輸） | HTTPS `*.pximg.net`（縮圖另允許 `embed.pixiv.net`） | 下載原圖、封面、縮圖、Ugoira ZIP 與小說內嵌圖（最多 512 個對應），攜帶 Pixiv Referer；非 2xx 不緩衝錯誤正文 | 下載作品、產生封面/縮圖或小說檔案時觸發 |
| 根目錄使用者腳本 | `https://www.pixiv.net/ajax/**`、`*.pximg.net`；設定的後端位址（預設 `http://localhost:6999`） | 在 Pixiv 頁面透過瀏覽器登入狀態讀取作品或進行本地下載；向後端呼叫下載、佇列、收藏夾、小說狀態、`/api/i18n/meta`（3 秒逾時）與工具箱工作階段/Cookie 同步介面（不將 Pixiv Cookie 傳送至小說票據介面） | 在 Pixiv 頁面操作腳本時，由瀏覽器/腳本管理器直接連線；停用腳本即可停止 |
| `pixiv-batch-downloader-import` 外掛與腳本 | 本機 `/api/pixiv-batch-downloader-import/token`、`/api/pixiv-batch-downloader-import`、`/api/pixiv-batch-downloader-import/history`；瀏覽器同源 `/ajax/illust/{id}`、`/ajax/novel/{id}`；桌面歷史匯入經宿主請求 `https://www.pixiv.net/ajax/{illust,novel}/{id}`；腳本更新來源 | 自動匯入將本機檔案路徑與中繼資料傳送至 solo 迴路服務；瀏覽器歷史匯入在瀏覽器內用同源 Cookie 取回中繼資料，再交本機核驗；桌面歷史匯入使用使用者可選擇填寫的單次 Cookie 取得中繼資料。均不上傳原作品檔案 | 僅在安裝啟用此外掛及腳本，或主動執行歷史匯入時觸發；停用腳本/外掛或取消任務即停止 |

此外，透過 SDK `DownloadTasks.submit` 或管理員 `POST /api/download/tasks` 提交任務時，同樣會呼叫對應外掛取得中繼資料與媒體；工作台在可見時每 2 秒輪詢同源 `GET /api/download/tasks`（終止狀態保留 5 分鐘）。

## 第三方與選用功能外掛

### `douyin` 外掛（第三方，依需求安裝）

預設發行包未預置，僅在手動安裝、啟用並執行 Douyin 操作時存取：

- `https://www.douyin.com/aweme/v1/web/**`：取得使用者作品、喜歡、合集、搜尋、音樂、收藏、帳號與作品詳細資料（攜帶使用者設定的 Douyin Cookie、從 Cookie 擷取的 UIFID 及本地計算的 `x-secsdk-web-signature`）。
- HTTPS `*.douyin.com`、`*.iesdouyin.com`（如 `v.douyin.com`）：透過受限重新導向解析短連結。
- HTTPS `*.douyinvod.com`、`*.douyinpic.com`、`*.douyinstatic.com`、`*.amemv.com`、`*.byteimg.com`、`*.bytedance.com`、`*.bytecdn.cn`、`*.pstatp.com`、`*.snssdk.com`：下載影片、圖集、封面與實況照片。

路由依「任務獨立代理 → 來源預設路由 → 宿主全域代理」選擇；停止任務或停用外掛即停止。

### `ai` 與 `translation` 外掛

AI 外掛使用 OpenAI 相容協定：連線測試、小說翻譯和多角色分析，向所選基底位址的 `/chat/completions` 傳送文字、提示詞、模型名稱與 API Key（小說翻譯附帶最多 1,000 筆名詞對照表項目）；在桌面設定點擊取得模型清單時，向草稿基底位址的 `/models` 傳送 GET（先匿名請求，遇到 401/403 且已填金鑰時，攜帶 Bearer 金鑰重試一次；`https://api.anthropic.com/v1` 依 [Anthropic 協定](https://platform.claude.com/docs/en/api/models/list)使用 `anthropic-version`、`x-api-key` 與 `after_id` 分頁）。所有請求不儲存 Cookie、不跟隨重新導向。

| AI 預設組合 | 預設基底位址 | AI 預設組合 | 預設基底位址 |
| --- | --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | 智譜 | `https://open.bigmodel.cn/api/paas/v4` |
| Anthropic 相容 | `https://api.anthropic.com/v1` | Moonshot | `https://api.moonshot.cn/v1` |
| Gemini 相容 | `https://generativelanguage.googleapis.com/v1beta/openai` | 豆包 / 火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` |
| xAI | `https://api.x.ai/v1` | 騰訊混元 / TokenHub | `https://tokenhub.tencentmaas.com/v1` |
| Mistral | `https://api.mistral.ai/v1` | 百度千帆 / ERNIE | `https://qianfan.baidubce.com/v2` |
| Groq | `https://api.groq.com/openai/v1` | 訊飛星火 | `https://spark-api-open.xf-yun.com/v1` |
| DeepSeek | `https://api.deepseek.com` | MiniMax | `https://api.minimax.cn/v1` |
| 阿里雲百煉 / Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenRouter / SiliconFlow | `https://openrouter.ai/api/v1`、`https://api.siliconflow.cn/v1` |
| Ollama（本機） | `http://localhost:11434/v1` | LM Studio（本機） | `http://localhost:1234/v1` |

`translation` 外掛在啟動手動或自動翻譯時，會呼叫所選引擎：AI 使用上表設定；有道請求 `https://openapi.youdao.com/api`（傳送文字、應用程式 ID 與本地計算的 v3 簽章，不直接傳送應用程式金鑰）；LibreTranslate 請求管理員設定的 `{libretranslate-url}/translate`（選用 `api_key`）。有道與 LibreTranslate 連線逾時 30 秒、讀取逾時 120 秒，單次最多 1,000 個 Unicode 字元、回應上限 1 MiB，由 `translation.use-proxy` 控制是否使用應用程式代理。

### `tts`、`push` 與 `mail` 外掛

| 外掛 | 服務 / 通道 | 預設目標位址 |
| --- | --- | --- |
| `tts` | 瀏覽器引擎 | 瀏覽器直接呼叫系統 / 瀏覽器 Web Speech API（不經後端） |
| `tts` | Edge TTS 與版本探測 | `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`、同主機 `/voices/list`；交握 403 更新版本時，請求 `https://edgeupdates.microsoft.com/api/products?view=enterprise` |
| `tts` | 雲端與自架 TTS | 小米 MiMo `https://api.xiaomimimo.com/v1/chat/completions`；Fish Audio `https://api.fish.audio/v1/tts`；MiniMax `https://api.minimax.io/v1/t2a_v2`（中國境內 `https://api.minimaxi.chat/v1/t2a_v2`）；ElevenLabs `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}`；阿里雲百煉 `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`（國際區 `dashscope-intl.aliyuncs.com`）及回傳的暫時音訊 URL；豆包 `https://openspeech.bytedance.com/api/v1/tts`；VoxCPM / CosyVoice `{base-url}/audio/speech`（VoxCPM 另查 `/models`） |
| `push` | 推播通道 | Bark `https://api.day.app/push`（可改為自架）；釘釘 `https://oapi.dingtalk.com/robot/send`；飛書 `https://open.feishu.cn/open-apis/bot/v2/hook/{key}`；企業微信 `https://qyapi.weixin.qq.com/cgi-bin/webhook/send`；Telegram `https://api.telegram.org/bot{token}/sendMessage`；PushPlus `https://www.pushplus.plus/send`；Server 醬 Turbo `https://sctapi.ftqq.com/{key}.send`；Server 醬³ `https://{uid}.push.ft07.com/send/{key}.send`；自訂 Webhook URL |
| `mail` | SMTP 預設組合與自訂 | 網易 `smtp.163.com:465` / `126.com` / `yeah.net` / `qiye.163.com`；騰訊 `smtp.qq.com:465` / `exmail.qq.com:465`；新浪 `smtp.sina.com:465`；Gmail `smtp.gmail.com:587`；Outlook `smtp-mail.outlook.com:587`；Microsoft 365 `smtp.office365.com:587`；iCloud `smtp.mail.me.com:587`；Yahoo `smtp.mail.yahoo.com:465`；阿里企業郵 `smtp.qiye.aliyun.com:465`；或自訂 SMTP + 選用 SOCKS 代理 |

攜帶憑證的 TTS 與 Push HTTP 請求均不儲存 Cookie、不跟隨重新導向。停用對應外掛、關閉通道或清空憑證即可停止連線。

### 外掛市場、FFmpeg、資料庫、頁面編輯器與驗證碼

- **外掛市場與撤銷檢查**：
  - 官方儲存庫讀取 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`（nightly 為 `nightly-manifest.json`）、依需求取得的歷史分片 `history/<pluginId>-<sha256>.json`、版本 Release 中的市場文件/截圖附件及外掛套件 URL。
  - 社群儲存庫讀取 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/generated/current.json`、世代目錄分片、撤銷清單、審核紀錄及已簽署 Release 資源。
  - 自訂儲存庫讀取管理員匯入的 `repository.json`（上限 64 KiB）及其宣告的 `manifest-v1` / `paged-v2` 端點、`revocations.json` 與 `repository-update.json`。
  - 已安裝外掛更新檢查結果快取 5 分鐘（部分失敗快取 30 秒）；相容舊版回退查詢單一外掛最多讀取 10 頁、共用 10 秒時間額度。關閉 `plugin-catalog.enabled` 或對應儲存庫開關即可停用。
- **FFmpeg 自動安裝**：桌面狀態頁下載或 Windows 安裝程式勾選 FFmpeg 任務時，請求 `https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/ffmpeg-release.json`、`.sig` 及對應平台 `ffmpeg-*.zip`，核驗官方 Ed25519 簽章、大小與 SHA-256 後解壓縮（桌面端使用宿主代理，Windows 安裝程式優先使用目前使用者的系統 HTTP/HTTPS 代理）。
- **使用者腳本更新**：從儲存庫安裝的獨立腳本，由 Tampermonkey 存取 `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js`；從後端安裝的腳本指向該後端 `/api/scripts/*.user.js`。
- **官方 PostHog 調查外掛**：僅在官方發行建置啟用（`release-activation.js` 為 `true`），且管理員開啟含有效調查的站內信或觸發下載頁版面調查時，瀏覽器才直接存取 `https://layout-survey.sywyar.top` 與 `https://us.posthog.com`（僅傳送衍生的調查範圍匿名識別碼與答卷，不傳送安裝 UUID、帳號、Cookie 或路徑；停用 `posthog` 外掛即關閉）。
- **PostgreSQL 資料庫外掛**：透過 JDBC 連線至管理員設定的 `database.jdbc-url`（預設連接埠 5432，連線預先檢查逾時 10 秒），傳送資料庫驗證資料、業務與私有資料庫資料；直接連線至目標伺服器，不經 HTTP 代理。詳見[設定參考](/zh-hant/configuration#主資料庫與引擎切換)。
- **無固定外部公網目標的外掛**：`gallery`、`gallery-tools`、`gui-compose`、`gui-swing`、`page-editor`（內建本地 GrapesJS 與 CodeMirror，關閉 CDN 與遙測）本身僅存取目前執行個體的同源 API，不發起固定第三方公網請求。
- **`captcha` 操作驗證碼外掛**：
  - **ALTCHA（SHA-256 / PBKDF2）、字元驗證碼（Hutool）、滑塊拼圖（Tianai）**：均在目前執行個體與瀏覽器本地完成，不存取第三方服務。
  - **Cloudflare Turnstile**：綁定並發布後，瀏覽器載入 `https://challenges.cloudflare.com/turnstile/v0/api.js` 與 iframe；提交時，伺服器端經受管 HTTP（繼承應用程式代理、禁止重新導向與 Cookie）向 `/turnstile/v0/siteverify` 傳送密鑰與票據。
  - **Google reCAPTCHA v2**：綁定並發布後，瀏覽器載入 `https://www.google.com/recaptcha/api.js?render=explicit`（及 `www.gstatic.com`、`recaptcha.google.com`）；提交時，伺服器端經受管 HTTP 向 `https://www.google.com/recaptcha/api/siteverify` 傳送密鑰與票據。移除元件並重新發布頁面，即可停止外部請求。

## 開發、建置和發布流程的網路存取

以下請求僅在開發者或 CI 執行原始碼建置、相依項目解析、測試、打包、簽署、發布或執行社群投稿工具時發生，**已安裝的用戶端執行期不會發起這些請求**：

| 工程環節 | 存取目標與用途 |
| --- | --- |
| Maven / Gradle / sbt 與 npm 相依項目解析 | Maven Wrapper 與 Java 模組從設定的 Maven 儲存庫/鏡像（預設 `https://repo.maven.apache.org/maven2`、`https://repo1.maven.org/maven2`、`https://plugins.gradle.org`）下載建置工具與相依項目（如 Tink、PostgreSQL/SQLite/H2 JDBC、Hutool/Tianai/ALTCHA、Cupertino、WebP/JSON Schema/JCS、sbt-launch 等）；前端檢查與外掛資源建置從鎖定檔設定的 npm 來源（目前為 `https://registry.npmmirror.com`，維護 Web 資源時偶爾使用 `https://registry.npmjs.org`）下載相依項目 |
| 瀏覽器與資料庫整合驗收 | Playwright 瀏覽器安裝從 `cdn.playwright.dev` / `playwright.download.prss.microsoft.com` 下載 Chromium；明確執行 `page-editor-acceptance.mjs` 驗證碼驗收時，使用官方公開測試金鑰存取 Cloudflare Turnstile 與 Google reCAPTCHA 測試端點；PostgreSQL 容器驗收取得 Docker `postgres:17`，並僅連線至 `PIXIV_TEST_POSTGRES_URL` 隔離資料庫 |
| 維護者目錄、授權條款與本地打包腳本 | 建置應用程式資源時，透過 GitHub API（`api.github.com/repos/Sywyar/PixivDownloader`）與 `avatars.githubusercontent.com` 取得白名單貢獻者資料與頭像（遵循系統/環境 HTTP(S) 代理，30 秒逾時，離線建置會自動略過）；授權條款維護腳本依需求讀取 `spdx/license-list-data`；`package-local.ps1` 建置離線包時下載並核驗 `ffmpeg-stable` 資源簽章；`package-installer-with-plugins.ps1 -PluginSource Catalog` 從官方外掛儲存庫下載簽署清單與外掛套件 |
| SDK 驗證、發布與跨儲存庫 Pages 建置 | `sdk-legacy-plugin.mjs` 下載固定 `gui-swing-v1.0.2` 資源以驗證二進位相容性；`sdk-consumer.mjs` 取得 `Sywyar/PixivDownloader-Plugin-Douyin.git` 固定提交以驗證隔離消費者；SDK 發布工作流程向 Sonatype Central Portal 發布 5 個 SDK Maven 座標、向 `Sywyar/PixivDownloader-Plugin-SDK` 建立簽署 Release，並建置 Javadoc GitHub Pages；開發者在 SDK 專案首次執行 `sdk-prepare` / `sdk-run` 時，從 SDK Release 下載固定執行包並快取至 `~/.cache/pixivdownloader-sdk/` |
| 主儲存庫 CI、Gate Checks、Release 與公告續簽 | GitHub Actions 執行 Quality Gate、發行包 E2E、簽署外掛發布與 `Gate Checks`（透過 GitHub API 與 App token 為開啟中的 PR 簽發必要檢查）；`PixivDownloader-Remote-Content` 的定時工作流程檢查並續簽 `announcements/index.json.sig`，以及觸發 Pages 部署 |
| 社群外掛投稿精靈、隔離建置與審核發布 | 投稿入口 `irm https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/tools/submit.ps1 \| iex` 下載已簽署的 `submission-channel.json` 與固定提交工具，依 `no_proxy` → `https_proxy` / `all_proxy` → Windows 系統代理解析網路（單檔 60 秒、最多 3 次嘗試），透過 GitHub CLI / Git 存取社群儲存庫與投稿者 fork、上傳候選 Release 資源與文件圖片；社群 CI 在受控 Squid 代理容器內，僅從批准的 Maven / Gradle 來源預先取得相依項目，在斷網容器內重建外掛，並透過 GitHub Actions OIDC / Sigstore（`fulcio.sigstore.dev`、`rekor.sigstore.dev`、`tuf-repo.github.com`）產生封存證明、執行審核合併與撤銷清單續簽 |
