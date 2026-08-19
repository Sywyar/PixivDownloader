# 網絡訪問與第三方服務

本文列出 PixivDownloader 當前代碼可能訪問的外部網絡目標，以及負責發起請求的宿主組件或插件、請求用途、觸發條件和默認狀態。文中的“插件”採用插件管理頁顯示的插件 ID；“應用宿主”表示請求由核心應用 `pixivdownload-app` 發起，不歸屬於可選插件。

?> 本清單涵蓋當前代碼內固定的默認地址和允許的動態地址範圍。第三方服務可能使用 DNS、CDN、重定向或臨時下載地址，因此實際連接的 IP 地址和最終子域可能發生變化。管理員配置的自定義 URL 不在可預先窮舉的範圍內。

## 網絡訪問概覽

- AI、TTS、推送、郵件和 Douyin 僅在相應功能完成配置並被調用時訪問外部服務；`notification` 是例外，它會在啓用並啓動後自動檢查固定公告索引。
- 在線更新和自動檢查均啓用時，應用宿主會在啓動就緒後檢查 GitHub Releases；檢查頻率受緩存間隔限制。
- 訪問應用介紹頁時，瀏覽器會加載 Google Fonts。該請求不由後端發起，也不經過 PixivDownloader 的出站代理。
- `plugin-catalog.enabled` 默認爲 `true`，內嵌官方倉庫也默認啓用；啓動本身不拉取清單，管理員打開或刷新插件市場、執行安裝時纔會訪問倉庫。
- 兩個官方 PostHog 調查的四個參數分別由發佈調查的插件持有，但源碼 / fork 構建的發行激活位默認爲 `false`，默認不會連接 PostHog。
- Pixiv、Douyin、AI、TTS、推送和郵件請求可能包含用戶內容或訪問憑據，具體範圍見後續各節。

## 核心功能及默認網絡請求

| 請求所有者 | 目標地址 | 用途與主要發送內容 | 觸發場景與默認狀態 | 代理與關閉方式 |
| --- | --- | --- | --- | --- |
| 應用宿主 | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json` 與相鄰的 `update.json.sig`；nightly 使用 `/releases/download/nightly/` 下的同名文件；重定向後可能進入 GitHub Release 資產 CDN | 獲取正式版或 nightly 更新清單及 Ed25519 detached 簽名；只發送 User-Agent、IP 等標準連接元數據，不發送當前版本、平臺、Pixiv Cookie 或其它憑據。響應分別受 1 MiB / 16 KiB 上限約束，清單會在解析前使用應用內置官方公鑰驗籤 | 應用就緒後自動檢查；`update.enabled=true`、`update.auto-check=true` 時啓用，默認均開啓；手動檢查也會訪問 | 使用宿主出站代理配置；僅允許 HTTPS 和默認公網地址，最多跟隨五跳重定向且每一跳都重新校驗。可關閉在線更新或自動檢查；自定義 manifest 必須是持有有效官方簽名的公網 HTTPS 鏡像 |
| 應用宿主 | 已驗籤更新清單中當前平臺對應的安裝包 URL，默認來自 GitHub Release | 下載更新安裝包，並強制匹配簽名清單中的 SHA-256 和精確大小；請求不攜帶 Cookie 或其它憑據 | 檢查到更新且明確啓動下載和安裝後觸發；更新檢查本身不會自動安裝 | 目標由已驗籤清單決定，仍只允許 HTTPS 和默認公網地址，最多跟隨五跳重定向且每一跳都重新校驗，總響應不超過 500 MiB；關閉在線更新可完全停用該鏈路 |
| 應用宿主的介紹頁 | `https://fonts.googleapis.com/css2?...`、`https://fonts.gstatic.com/...` | 獲取 Noto Sans SC 樣式和字體文件；瀏覽器會正常暴露 IP 地址、User-Agent 等連接元數據 | 訪問介紹頁時由瀏覽器觸發 | 不經過宿主代理；域名被阻止時使用後備字體，下載功能不受影響 |
| 應用宿主 | `https://www.pixiv.net/` | Pixiv 連通性探測，不攜帶 Pixiv Cookie | 首次配置或執行 Pixiv 連通性檢查時觸發，不是持續心跳 | 使用宿主的 Pixiv 出站路由；未執行探測時不發起該請求 |
| `notification` 插件 | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json`、相鄰的 `index.json.sig` 與 `.../announcements/<message-id>/<locale>.html` | 讀取公開公告索引及其 detached Ed25519 簽名；索引在解析前使用應用內置官方信任根驗籤，並校驗有效期、遞增序列和每份正文的 SHA-256。僅爲未知穩定 ID 下載已簽名索引要求的各語言受控 HTML 正文；請求禁用 Cookie，只發送 IP、User-Agent 等標準連接元數據，不發送賬號、作品、本地路徑或其它憑據。驗證或傳輸失敗時保留既有可信快照。HTML 快照保存在本地，管理員瀏覽器只讀取本地鑑權端點，不再直連外部正文 | 插件每次啓動後隨機等待 0–30 分鐘，之後約每 6 小時並加入 ±15% 隨機抖動檢查。完整可信導入後保存 `ETag` 與 `Last-Modified`，僅在已簽名索引有效期內發送條件請求；收到 `304 Not Modified` 時不再下載簽名與正文。HTTP 429 按最長 24 小時的 `Retry-After` 延後，傳輸失敗和 5xx 按 5 分鐘、15 分鐘、1 小時、6 小時逐級退避。只有首次發現未知 ID 或已簽名正文摘要變化時纔有界下載對應的各語言 HTML。同一 ID 已保存且元數據未變，或已顯式刪除時不再請求正文。官方默認插件集合包含 `notification`，啓用併成功啓動時會自動訪問 | 使用宿主繼承出站路由，可使用已啓用的全局代理；禁用/卸載 `notification` 會停止檢查，插件停止或重載時立即取消後續輪詢 |

## Pixiv 下載與瀏覽

Pixiv 業務請求的 HTTP 傳輸由應用宿主提供，業務觸發方則可能是下載工作臺、小說插件或油猴腳本。下表按業務請求歸屬進行區分，不將共享傳輸層請求統一歸類爲核心請求。

| 請求所有者 | 目標地址 | 用途與主要發送內容 | 觸發場景 | 代理與關閉方式 |
| --- | --- | --- | --- | --- |
| `download-workbench` 插件 | `https://www.pixiv.net/ajax/illust/**`、`/ajax/user/**`、`/ajax/search/artworks/**`、`/ajax/series/**`、`/ajax/collection/**`、`/ajax/follow_latest/illust`、`/rpc/index.php` | 獲取插畫、動圖、畫師、系列、搜索、收藏夾、關注動態和約稿等元數據；需要登錄的請求會使用已配置的 Pixiv Cookie | 預覽、快捷獲取、提交下載、執行計劃任務、回填作品信息或瀏覽相關取得模式時觸發 | 經宿主提供給插件的 Pixiv HTTP 能力和所選代理路由；`download-workbench` 是必需插件（required），其缺失或不可用時主要下載功能不可用 |
| `download-workbench` 插件 | `https://www.pixiv.net/ajax/illusts/bookmarks/add` | 下載完成後收藏插畫；發送作品 ID、收藏可見性和標籤，並使用 Pixiv 登錄憑據 | 僅在啓用“下載後收藏”等相應選項且作品下載完成後觸發；請求失敗不會回滾已完成的下載 | 關閉下載後收藏功能可停用該請求 |
| `novel` 插件 | `https://www.pixiv.net/ajax/novel/**`、`/ajax/novel/series/**`、`/ajax/novel/series_content/**`、`/ajax/user/**/novels`、`/ajax/user/**/novels/bookmarks`、`/ajax/search/novels/**` | 獲取小說正文、小說系列、作者小說列表、收藏和搜索結果；需要登錄的請求會使用 Pixiv Cookie。下載工作臺通過綁定當前 owner、作品與取得憑據的一次性短期票據複用預覽時已驗證的小說響應，不會在提交下載時再次請求同一小說正文 | 預覽小說、系列合訂、小說搜索、畫師小說和計劃任務處理時觸發；緊隨預覽的下載提交會複用響應，不帶票據的兼容客戶端直接提交仍由後端抓取，無效票據會被拒絕 | 經宿主 Pixiv HTTP 能力；禁用 `novel` 會撤回小說頁面和相關能力 |
| `novel` 插件 | `https://www.pixiv.net/ajax/novels/bookmarks/add` | 下載完成後收藏小說 | 僅在啓用相應選項且小說下載完成後觸發 | 關閉下載後收藏功能即可停用 |
| 應用宿主共享圖片傳輸，調用方主要爲 `download-workbench`、`novel` | HTTPS `*.pximg.net`；縮略圖還允許 `embed.pixiv.net` | 下載 Pixiv 原圖、封面、縮略圖、動圖壓縮包和小說內嵌圖片；通常發送 Pixiv Referer，圖片地址來自 Pixiv API 響應 | 下載作品、生成封面/縮略圖、瀏覽預覽或生成小說文件時觸發 | 使用 Pixiv 圖片下載路由；停止相關下載或預覽即可避免 |
| 根目錄油猴腳本，不屬於插件 | `https://www.pixiv.net/ajax/**`、HTTPS `*.pximg.net` | 在 Pixiv 網頁中直接讀取作品/小說信息、收藏作品或下載文件；`GM_xmlhttpRequest` 會使用瀏覽器/Pixiv 登錄態 | 用戶在 Pixiv 頁面點擊腳本功能、抓取頁面或執行本地下載時觸發 | 由瀏覽器/腳本管理器直連，不經過宿主代理；禁用或卸載相應腳本即可停用 |
| 根目錄油猴腳本，不屬於插件 | 管理員配置的 PixivDownloader 地址，默認 `http://localhost:6999` | 調用下載提交、隊列、狀態和 SSE 等後端 API；發送所選作品和下載參數。本機單人模式下載小說時，還會把腳本從 Pixiv 取得的有界小說響應發送給本機小說插件，換取短期一次性票據；不發送 Pixiv Cookie | 使用 Java 後端版、批量腳本或工具箱功能時觸發 | 默認僅連接本機；配置爲遠端地址後，請求將發送至相應遠端服務器，但小說響應導入會關閉並改由後端自行抓取 |

後端對普通 Pixiv JSON 使用 4 MiB 響應預算，對小說系列詳情與分頁內容響應使用 1 MiB 預算；存在 `Content-Length` 時會先校驗聲明長度，並始終按實際流讀取量執行上限，超限時中止處理並返回受控錯誤。小說元數據最多接受 512 個內嵌圖片映射；短期票據只保留移除正文和內嵌圖片表後的原始元數據，且不超過 256 KiB。

## `douyin` 插件

`douyin` 是按需安裝插件，默認 Windows 安裝包和 Java 標準包不預置。只有安裝、啓用並使用 Douyin 功能後纔會產生下列請求。

| 目標地址 | 用途與主要發送內容 | 觸發場景 | 代理與關閉方式 |
| --- | --- | --- | --- |
| `https://www.douyin.com/aweme/v1/web/**` | 獲取用戶作品、喜歡列表、合集、搜索、音樂作品、收藏夾、賬號信息和作品詳情；會發送 Douyin Cookie、查詢參數及模擬瀏覽器所需請求頭 | 手動取得、預覽、下載、計劃任務發現、Cookie 探活和 Douyin 畫廊刷新時觸發 | 使用任務級、來源默認或宿主全局路由；禁用/卸載 `douyin` 即完全停用 |
| HTTPS `douyin.com` / `*.douyin.com`、`iesdouyin.com` / `*.iesdouyin.com`，常見爲 `v.douyin.com` | 解析用戶粘貼的短鏈接，最多跟隨受限跳轉 | 輸入 Douyin 短鏈接並開始解析時觸發 | 與該次 Douyin 任務使用相同路由 |
| HTTPS `*.douyinvod.com`、`*.douyinpic.com`、`*.douyinstatic.com`、`*.amemv.com`、`*.byteimg.com`、`*.bytedance.com`、`*.bytecdn.cn`、`*.pstatp.com`、`*.snssdk.com`，以及上述 Douyin 域 | 下載視頻、圖片、封面和實況照片媒體；具體 URL 來自 Douyin API 響應並可能發生受限重定向 | 執行 Douyin 媒體下載時觸發 | 與該次 Douyin 任務使用相同路由；停止任務或禁用插件即可終止後續請求 |

## `ai` 插件

AI 插件使用 OpenAI 兼容協議，向所選基礎地址的 `/chat/completions` 發送請求。請求通常包含待翻譯或待處理文本、特定用途提示詞、模型名稱和 API Key。插件安裝操作不會發起此類請求；保存有效配置後執行連接測試、翻譯或其他 AI 功能時纔會觸發。

| 預設 | 默認基礎地址 |
| --- | --- |
| OpenAI | `https://api.openai.com/v1` |
| Anthropic 兼容入口 | `https://api.anthropic.com/v1` |
| Gemini OpenAI 兼容入口 | `https://generativelanguage.googleapis.com/v1beta/openai` |
| xAI | `https://api.x.ai/v1` |
| Mistral | `https://api.mistral.ai/v1` |
| Groq | `https://api.groq.com/openai/v1` |
| DeepSeek | `https://api.deepseek.com` |
| 阿里雲百鍊/Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 智譜 | `https://open.bigmodel.cn/api/paas/v4` |
| Moonshot | `https://api.moonshot.cn/v1` |
| 豆包/火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` |
| 騰訊混元 | `https://api.hunyuan.cloud.tencent.com/v1` |
| 百度千帆/ERNIE | `https://qianfan.baidubce.com/v2` |
| 訊飛星火 | `https://spark-api-open.xf-yun.com/v1` |
| MiniMax | `https://api.minimaxi.com/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| SiliconFlow | `https://api.siliconflow.cn/v1` |
| Ollama | `http://localhost:11434/v1` |
| LM Studio | `http://localhost:1234/v1` |

AI 基礎地址可配置爲其他兼容服務，因此完整目標範圍取決於實際配置。自定義目標會收到待處理文本、請求參數和 API Key，也可能訪問本機或內網；只應配置爲管理員信任的本地、自建或第三方服務。攜帶憑據的 AI 請求不保存 Cookie，也不跟隨 HTTP 重定向，避免把憑據轉發到跳轉目標。代理行爲由 AI 配置決定；刪除 API Key、停用或清空配置，或者禁用 `ai` 插件，可停止相關請求。

## `tts` 插件

TTS 請求會把需要朗讀的文本、音色/模型參數和相應服務憑據發送到所選語音服務。只有試聽、生成朗讀、刷新音色列表、連接測試或實際小說朗讀時才觸發。

| 引擎 | 目標地址與用途 | 特殊觸發說明 |
| --- | --- | --- |
| Edge TTS | `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1` 合成語音；同主機 `/consumer/speech/synthesize/readaloud/voices/list` 獲取音色 | 合成、試聽或刷新音色時觸發 |
| Edge TTS 版本探測 | `https://edgeupdates.microsoft.com/api/products?view=enterprise` | Edge TTS 需要刷新客戶端版本信息時觸發，不是獨立遙測 |
| 小米 MiMo | `https://api.xiaomimimo.com/v1/chat/completions` | 使用 MiMo 引擎合成時觸發 |
| Fish Audio | `https://api.fish.audio/v1/tts` | 使用 Fish 引擎合成時觸發 |
| MiniMax | 默認 `https://api.minimax.io/v1/t2a_v2`；國內站可配置爲 `https://api.minimaxi.chat/v1/t2a_v2` | 使用 MiniMax 引擎合成時觸發 |
| ElevenLabs | `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}` | 使用 ElevenLabs 引擎合成時觸發 |
| 阿里雲百鍊/Qwen | 默認 `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`；國際區可配置爲 `https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation` | 生成接口返回臨時音頻 URL 後，插件還會直接下載該 URL；臨時 URL 的主機由服務端決定 |
| 豆包/Seed-TTS | `https://openspeech.bytedance.com/api/v1/tts` | 使用豆包引擎合成時觸發 |
| VoxCPM、CosyVoice | 管理員配置的自建 OpenAI 兼容地址，通常爲 `{base-url}/audio/speech`；VoxCPM 還會訪問 `{base-url}/models` | 默認基礎地址爲空；未配置時不發起請求 |

具體引擎可配置獨立基礎地址；自定義目標會收到朗讀文本、模型參數和服務憑據，也可能訪問本機或內網，只應使用管理員信任的服務。攜帶憑據的 HTTP 請求不保存 Cookie，也不跟隨重定向；不含調用憑據的 Edge 版本元數據探測繼續使用普通客戶端。代理行爲取決於出站路由和引擎配置。禁用 `tts`、停用對應引擎或清空其配置即可停止請求。

## `push` 插件

Push 插件僅在通知通道啓用後，由通知事件或“發送測試消息”操作觸發。請求包含通知標題、正文及相應通道憑據；部分服務將 Token 或 Key 置於 URL 中。

| 通道 | 固定或默認目標 |
| --- | --- |
| Bark | `https://api.day.app/push`；服務器地址可改爲自建 Bark |
| 釘釘機器人 | `https://oapi.dingtalk.com/robot/send?access_token=...` |
| 飛書機器人 | `https://open.feishu.cn/open-apis/bot/v2/hook/{key}` |
| 企業微信機器人 | `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...` |
| Telegram Bot | `https://api.telegram.org/bot{token}/sendMessage` |
| PushPlus | `https://www.pushplus.plus/send` |
| Server 醬 Turbo | `https://sctapi.ftqq.com/{key}.send` |
| Server 醬³ | `https://{uid}.push.ft07.com/send/{key}.send` |
| 自定義 Webhook | 管理員配置的任意 `http://` 或 `https://` URL |

關閉通道、刪除憑據或禁用 `push` 插件可停止請求。自定義目標會收到通知正文與通道憑據，也可能訪問本機或內網；`http://` 還會明文傳輸這些數據，只應使用管理員信任的目標。推送請求不保存 Cookie，也不跟隨 HTTP 重定向，避免把憑據轉發到跳轉目標。

## `mail` 插件

Mail 插件通過 SMTP 發送配置測試郵件和業務通知。連接會攜帶 SMTP 用戶名/密碼，並把收件人、主題和郵件正文交給所選郵件服務。只有測試發送或通知實際投遞時才連接。

| 預設服務 | 默認 SMTP 目標 |
| --- | --- |
| 網易 | `smtp.163.com:465`、`smtp.126.com:465`、`smtp.yeah.net:465`、`smtp.qiye.163.com:465` |
| 騰訊 | `smtp.qq.com:465`、`smtp.exmail.qq.com:465` |
| 新浪 | `smtp.sina.com:465` |
| Gmail / Google Workspace | `smtp.gmail.com:587` |
| Outlook | `smtp-mail.outlook.com:587` |
| Microsoft 365 | `smtp.office365.com:587` |
| iCloud | `smtp.mail.me.com:587` |
| Yahoo | `smtp.mail.yahoo.com:465` |
| 阿里企業郵 | `smtp.qiye.aliyun.com:465` |

管理員可指定任意 SMTP 主機和端口，也可配置獨立的 SOCKS 代理地址。關閉郵件通知、刪除郵件配置或禁用 `mail` 插件可停止相關連接。

## 插件市場、FFmpeg 與腳本更新

| 請求所有者 | 目標地址 | 用途 | 觸發場景與默認狀態 |
| --- | --- | --- | --- |
| 應用宿主的插件市場 | `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`；包地址通常爲 GitHub Release，並可能重定向到 `*.githubusercontent.com` | 獲取官方插件清單、下載用戶選擇的插件包並做簽名、SHA-256 和大小校驗 | `plugin-catalog.enabled` 與內嵌官方倉庫默認啓用；管理員打開或刷新市場時拉取清單，明確安裝插件時下載包；應用啓動本身不訪問倉庫；最多跟隨五跳重定向且每一跳都重新校驗，關閉主開關可完全停用該鏈路 |
| 應用宿主的插件市場 | 管理員配置的自定義 HTTPS manifest 和其中聲明的包 URL | 使用第三方/自建插件倉庫 | 只有配置並啓用對應倉庫後觸發；直連嚴格策略可能明確不使用全局代理；最多跟隨五跳重定向且每一跳都重新校驗，具體以倉庫策略爲準 |
| 應用宿主 FFmpeg 安裝器 | `https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip`，以及其 Release CDN 重定向 | 下載 Windows FFmpeg LGPL 構建 | 僅在 GUI 中明確選擇自動安裝 FFmpeg 時觸發；應用啓動本身不會下載 |
| 油猴腳本管理器，不屬於插件 | `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js` | 檢查和下載六個獨立油猴腳本更新 | 由 Tampermonkey 等腳本管理器按其更新策略觸發；禁用腳本自動更新或卸載腳本即可停止 |
| All-in-One 油猴腳本管理器，不屬於插件 | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/Pixiv%20All-in-One.user.js` | 檢查或下載構建生成的合併腳本 | 僅安裝該發行腳本後由腳本管理器觸發 |

## 官方插件的可選調查（PostHog）

佈局反饋調查屬於 `download-workbench` 插件；多人模式保留意願調查屬於 `multi-mode-decision-survey` 插件，並且只在管理員站內信中顯示。獨立的 `posthog` 插件提供 PostHog JavaScript SDK 和調用方配置的隔離客戶端。SDK 已隨插件靜態資源打包，不會從 CDN 加載。

- 每個發佈調查的插件都固定持有自己的 Project Token、Survey ID、`apiHost=https://layout-survey.sywyar.top` 與 `uiHost=https://us.posthog.com`；它們是瀏覽器可見參數，不是 Secret，也不通過 GitHub Actions、腳本或 properties 文件注入。
- 普通源碼 / fork 構建生成的發行激活位爲 `false`；官方 Release、Nightly 與官方插件發佈使用倉庫內的 Maven `official-surveys` profile 把這一位設爲 `true`。四個參數不隨 profile 改寫。
- 啓動時的調查站內信註冊與冪等寫入只讀取本地插件聲明，不訪問 PostHog。用戶打開含有效調查的站內信頁面（頁面會預熱內嵌調查），或下載工作臺預加載 / 觸發佈局調查流程時，瀏覽器纔會直接訪問上述 PostHog API/UI 主機；發送範圍仍受調查發佈插件的 `beforeSend` 允許列表約束，且不經過宿主出站代理。
- 多人模式保留意願調查會發送用戶選擇或填寫的答案、調查標識、調查專用匿名標識、用於投遞去重的穩定事件標識、完成狀態、事件時間、事件名和公開項目令牌。佈局調查會發送問卷回答、調查標識、調查專用匿名標識、用於投遞去重的穩定事件標識、應用版本、當前佈局、調查結構版本、事件時間、事件名和公開項目令牌。兩者的提交身份都由安裝身份單向派生並限定在當前調查與 campaign 內，不發送原始安裝身份、賬號、Cookie、作品或本地路徑。
- 調查站內信會在發佈插件持續提供時保留；發佈插件停止提供後由本地生命週期同步撤下。內嵌頁確認 Survey 已從 PostHog 發佈列表刪除 / 關閉後會留下本地關閉標記，不再顯示該站內信；臨時網絡錯誤不會誤刪，之後打開時會重新驗證。
- `posthog` 插件缺失或停用時調查靜默關閉。已打開頁面中的腳本不會被熱撤銷，停用後刷新頁面才完全生效。

## 不包含固定公網目標的官方插件

以下插件本身不增加固定第三方網絡目標：

- `stats`：讀取本地數據庫並生成統計。
- `duplicate`：讀取本地文件和 Hash 數據進行重複檢測。
- `gallery`：瀏覽本地下載記錄和本地媒體。
- `gui-swing` 與 `gui-compose`：渲染應用的本地桌面文檔，提供窗口、托盤、主題和平台資源，不增加固定第三方運行時目標。
- `recovery-sentinel`：僅用於恢復模式驗證，不包含在常規用戶發行包中。

它們的頁面仍會調用當前 PixivDownloader 實例的同源 API，但這不是訪問第三方公網。

## 本機、同源及管理員配置的目標地址

- GUI、Web 頁面和插件前端會訪問當前 PixivDownloader 實例的 `/api/**`、靜態資源和 SSE。桌面 GUI 默認連接 `http://localhost:{port}` 或 `https://localhost:{port}`。
- Ollama、LM Studio、VoxCPM、CosyVoice 和油猴腳本後端可配置爲本機服務；基礎地址指向遠端後，該遠端即成爲新的數據接收方。
- 圖片分類器的 `server.url` 默認爲 `http://localhost:6999`，也可指向管理員配置的其他 PixivDownloader 實例。
- 自定義 Webhook、AI/TTS 基礎地址、插件倉庫、Bark、SMTP、SOCKS 和代理端點都由管理員配置，無法形成封閉的固定域名白名單。更新清單 URL 也可由管理員配置，但只接受帶有效官方簽名的公網 HTTPS 目標。
- 根目錄 `cors-js-runner.html` 是開發調試工具，會請求操作者輸入的任意 URL；它不屬於常規用戶運行鏈路。

## 代理適用範圍

“配置了宿主代理”不表示所有網絡流量都會經過它：

- Pixiv、更新、FFmpeg、官方插件倉庫和部分插件請求會按照宿主或任務級路由選擇代理。
- AI、TTS 和 Douyin 可以有功能自身或任務級的直連/代理選擇。
- 自定義插件倉庫的直連嚴格策略會明確繞過全局代理；自定義策略以倉庫配置爲準。
- 油猴腳本、Google Fonts 和 PostHog 是瀏覽器直接發出的請求，不經過 Java 後端代理。
- SMTP 使用 Mail 插件自己的連接設置，可另配 SOCKS 代理。
- 如果啓用 HTTP/SOCKS 代理，程序首先連接管理員配置的代理主機和端口，再由代理訪問最終服務。

## 開發、構建和發佈流程的網絡訪問

以下目標不屬於已安裝應用的常規運行時請求：

| 工具/流程 | 目標或來源 | 用途 |
| --- | --- | --- |
| Git 和發佈腳本 | 當前 `origin`、`https://api.github.com`（或 `GITHUB_API_URL`）、GitHub Release | `fetch`、遠端引用檢查、質量門禁審計、插件和應用發佈 |
| GitHub Actions | GitHub Actions、Artifact、Release 服務以及 workflow 引用的 `actions/*`、`softprops/action-gh-release` | CI、構建、上傳產物和發佈 |
| Maven / Maven Wrapper | `https://repo.maven.apache.org/maven2` | 下載 Maven 3.9.11、Java 依賴和構建插件 |
| npm | 當前鎖文件中的 `https://registry.npmmirror.com` | 安裝 Node 構建/檢查依賴 |
| Docker | 配置的 OCI 鏡像倉庫，默認情況下解析 `eclipse-temurin:17-jre`；基礎鏡像配置的 Debian 軟件源 | 拉取基礎鏡像，以及安裝 FFmpeg、curl 等系統包 |
| Windows CI | Chocolatey 配置的軟件源 | 安裝 Inno Setup 等打包工具 |

構建機、代理、鏡像或包管理器配置可以改寫最終下載主機，因此這些傳遞依賴無法僅憑倉庫源碼列出穩定的完整域名集合。

## 鏈接及非請求型 URL

應用和文檔中還包含指向 GitHub、Releases、在線文檔、Tampermonkey 和許可證站點的鏈接。此類鏈接僅在被訪問或由瀏覽器實際加載資源時產生請求。XML 命名空間、POM Schema、許可證正文中的 URL 和示例域名不屬於自動網絡請求目標。

在應用 Web UI 中點擊外部 HTTP(S) 鏈接（包括本地公告/調查 HTML 快照內的鏈接）時，全站確認彈窗會先展示目標地址；只有明確確認後，瀏覽器纔會直接連接該地址，取消則不會產生請求。該瀏覽器導航不經過 Java 後端或全局代理，實際目標由所點擊的鏈接決定；站內同源鏈接保持直接跳轉。
