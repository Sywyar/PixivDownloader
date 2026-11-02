# 網絡訪問與第三方服務

用本頁判斷某項操作會連接哪裏、發送什麼數據，以及如何停止後續請求。先按功能查運行期訪問；構建和社區投稿的連接單列在[開發、構建和發佈流程](#開發、建置和發布流程的網路存取)，不會因爲安裝應用就全部發生。

文中的插件使用管理頁顯示的 ID；「應用宿主」指核心 `pixivdownload-app`。地址、觸發條件、憑據與代理範圍應一起閱讀，單獨屏蔽一個默認域名不能代表關閉所有可配置目標。

?> 本清單涵蓋當前代碼內固定的默認地址和允許的動態地址範圍。第三方服務可能使用 DNS、CDN、重定向或臨時下載地址，因此實際連接的 IP 地址和最終子域可能發生變化。管理員配置的自定義 URL 不在可預先窮舉的範圍內。

## 網絡訪問概覽

- AI、TTS、推送、郵件和 Douyin 僅在相應功能完成配置並被調用時訪問外部服務；`notification` 是例外，它會在啓用並啓動後自動檢查固定公告索引。
- 在線更新和自動檢查均啓用時，應用宿主會在啓動就緒後檢查 GitHub Releases；檢查頻率受緩存間隔限制。
- 訪問應用介紹頁時，瀏覽器會加載 Google Fonts。該請求不由後端發起，也不經過 PixivDownloader 的出站代理。
- `plugin-catalog.enabled` 默認爲 `true`，內嵌官方倉庫也默認啓用；啓動本身不拉取清單。管理員頁面檢查插件更新、打開或刷新插件市場、執行安裝，或在插件管理中主動重新整理撤銷資訊時會訪問倉庫。
- 兩個官方 PostHog 調查的四個參數分別由發佈調查的插件持有，但源碼 / fork 構建的發行激活位默認爲 `false`，默認不會連接 PostHog。
- Pixiv、Douyin、AI、TTS、推送和郵件請求可能包含用戶內容或訪問憑據，具體範圍見後續各節。

## 代理適用範圍

按請求發起方檢查路由：

- Pixiv、更新、FFmpeg、官方插件倉庫和部分插件請求會按照宿主或任務級路由選擇代理。
- AI、TTS 和 Douyin 可以有功能自身或任務級的直連/代理選擇。
- 自定義插件倉庫的直連嚴格策略會明確繞過全局代理；自定義策略以倉庫配置爲準。
- 油猴腳本、Google Fonts 和 PostHog 是瀏覽器直接發出的請求，不經過 Java 後端代理。
- SMTP 使用 Mail 插件自己的連接設置，可另配 SOCKS 代理。
- 如果啓用 HTTP/SOCKS 代理，程序首先連接管理員配置的代理主機和端口，再由代理訪問最終服務。

## 核心功能及默認網絡請求

登入頁開啟時向應用同源的 `/api/auth/check` 檢查工作階段；使用者提交後，向 `/api/auth/login` 傳送管理員帳號、密碼和保持登入選項，或向 `/api/auth/invite-redeem` 傳送邀請碼，並按同源規則攜帶工作階段 Cookie。兩個入口共用一個進行中的提交，失敗後可重試，不自動重送。管理員登入回跳僅接受經瀏覽器解析後仍同源的站內路徑，非法地址回到預設下載頁。這些請求使用瀏覽器網路設定，不經過後端出站代理；離開登入頁可停止後續操作，已發出的認證請求仍可能完成。

網頁打開或切換語言時，瀏覽器向應用的 `/api/i18n/meta` 和 `/api/i18n/messages/{namespace}?lang=...` 讀取語言目錄與字典；失敗後嘗試當前頁面相對路徑 `i18n-static/` 下的靜態資源。請求包含語言和 namespace，以及同源會話 Cookie，不訪問外部翻譯服務，也不經過後端代理。每次請求超時 10 秒且不使用緩存；字典失敗後僅重試缺失部分，最多自動重試三輪，間隔爲 1.5、5、15 秒。恢復網絡或重新聚焦頁面可再次觸發恢復；關閉頁面或替換語言客戶端會停止該客戶端的後續重試。

| 請求所有者 | 目標地址 | 用途與主要發送內容 | 觸發場景與默認狀態 | 代理與關閉方式 |
| --- | --- | --- | --- | --- |
| 應用宿主 | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json` 與相鄰的 `update.json.sig`；nightly 使用 `/releases/download/nightly/` 下的同名文件；重定向後可能進入 GitHub Release 資產 CDN | 獲取正式版或 nightly 更新清單及 Ed25519 detached 簽名；只發送 User-Agent、IP 等標準連接元數據，不發送當前版本、平臺、Pixiv Cookie 或其它憑據。響應分別受 1 MiB / 16 KiB 上限約束，清單會在解析前使用應用內置官方公鑰驗籤 | 應用就緒後自動檢查；`update.enabled=true`、`update.auto-check=true` 時啓用，默認均開啓；手動檢查也會訪問。啓用每夜版檢查後，即使正式版清單下載或驗籤失敗，仍獨立請求並驗證每夜版清單；正式版失敗信息保留，未通過驗證的渠道不能下載 | 使用宿主出站代理配置；僅允許 HTTPS 和默認公網地址，最多跟隨五跳重定向且每一跳都重新校驗。可關閉在線更新或自動檢查；自定義 manifest 必須是持有有效官方簽名的公網 HTTPS 鏡像 |
| 應用宿主 | 已驗籤更新清單中當前平臺對應的安裝包 URL，默認來自 GitHub Release | 下載更新安裝包，並強制匹配簽名清單中的 SHA-256 和精確大小；請求不攜帶 Cookie 或其它憑據 | 檢查到更新且明確啓動下載和安裝後觸發；更新檢查本身不會自動安裝 | 目標由已驗籤清單決定，仍只允許 HTTPS 和默認公網地址，最多跟隨五跳重定向且每一跳都重新校驗，總響應不超過 500 MiB；關閉在線更新可完全停用該鏈路 |
| 應用宿主的介紹頁 | `https://fonts.googleapis.com/css2?...`、`https://fonts.gstatic.com/...` | 獲取 Noto Sans SC 樣式和字體文件；瀏覽器會正常暴露 IP 地址、User-Agent 等連接元數據 | 訪問介紹頁時由瀏覽器觸發 | 不經過宿主代理；域名被阻止時使用後備字體，下載功能不受影響 |
| 應用宿主 | `https://www.pixiv.net/` | Pixiv 連通性探測，不攜帶 Pixiv Cookie | 首次配置或執行 Pixiv 連通性檢查時觸發，不是持續心跳 | 使用宿主的 Pixiv 出站路由；未執行探測時不發起該請求 |
| `notification` 插件 | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json`、相鄰的 `index.json.sig` 與 `.../announcements/<message-id>/<locale>.html` | 讀取公開公告索引及其 detached Ed25519 簽名；索引在解析前使用應用內置官方信任根驗籤，並校驗有效期、遞增序列和每份正文的 SHA-256。僅爲未知穩定 ID 下載已簽名索引要求的各語言受控 HTML 正文；請求禁用 Cookie，只發送 IP、User-Agent 等標準連接元數據，不發送賬號、作品、本地路徑或其它憑據。驗證或傳輸失敗時保留既有可信快照。HTML 快照保存在本地，管理員瀏覽器只讀取本地鑑權端點，不再直連外部正文 | 插件每次啓動後隨機等待 0–30 分鐘，之後約每 6 小時並加入 ±15% 隨機抖動檢查。完整可信導入後保存 `ETag` 與 `Last-Modified`，僅在已簽名索引有效期內發送條件請求；收到 `304 Not Modified` 時不再下載簽名與正文。HTTP 429 按最長 24 小時的 `Retry-After` 延後，傳輸失敗和 5xx 按 5 分鐘、15 分鐘、1 小時、6 小時逐級退避。只有首次發現未知 ID 或已簽名正文摘要變化時纔有界下載對應的各語言 HTML。同一 ID 已保存且元數據未變，或已顯式刪除時不再請求正文。官方默認插件集合包含 `notification`，啓用併成功啓動時會自動訪問 | 使用宿主繼承出站路由，可使用已啓用的全局代理；禁用/卸載 `notification` 會停止檢查，插件停止或重載時立即取消後續輪詢 |

## Pixiv 下載與瀏覽

縮圖代理僅存取 HTTPS `*.pximg.net` 和 `embed.pixiv.net`，不傳送 Pixiv Cookie，也不跟隨重新導向。單次回應最多 100 MiB（104,857,600 位元組），超限時載入失敗。宿主共用圖片傳輸按非成功 HTTP 狀態回傳失敗，不將錯誤本文緩衝為圖片或例外詳細資訊。

宿主讀取 Pixiv JSON 或執行作品收藏時，4xx/5xx 回應仍視為失敗，但不會讀取錯誤本文供解析或記錄日誌。收藏首頁讀取失敗後不再傳送收藏請求，已完成的下載不受收藏失敗影響。

宿主元資料回填先匯入已登記作品目錄中的舊 `.meta.json`，再以本地元資料補齊插畫缺失欄位與小說上傳時間；仍有缺項時，才分別請求 `https://www.pixiv.net/ajax/illust/{id}` 或 `https://www.pixiv.net/ajax/novel/{id}`。桌面啟動時會檢查缺失資料，即使資料庫已有相關欄位也一樣。勾選「一律透過網路取得」後會略過本地取值，直接連線補缺，仍不覆寫既有值。請求傳送作品 ID 與標準 AJAX 標頭，不攜帶 Pixiv Cookie。單次回應上限為 4 MiB，超限會停止本批並保留已完成結果。單次 HTTP 請求不自動重試；網路異常、HTTP 401 / 403 / 407 / 5xx 會停止本批並保留已完成結果與日誌。HTTP 429 按限流停止；404 只將對應類型與 ID 的作品記為無法存取，後續回填會略過該作品的網路請求。

年齡限制回應會另行記錄為暫緩狀態，後續桌面啟動不會重複請求，也不會將作品標為已刪除。工具頁的手動回填仍會重試；本地優先模式也可使用新取得的元資料繼續復原。復原完成或網路傳回有效資料後，會清除暫緩記錄。預先匯入只保存快照，欄位補齊遵守本次處理數量限制，並保留既有值。

桌面和無頭伺服器預設在後端就緒後自動背景回填，使用目前的資料庫連線池、下載目錄和代理設定；資料庫無法使用時跳過回填，保留服務的復原入口。Swing 和 Compose 工具頁可查看進度並停止本次工作，關閉面板不停止回填；無頭模式不開啟視窗或瀏覽器，正常停止服務會取消回填。停止後保留已提交的資料，並等待工作釋放資源。主程式日誌和獨立回填 CLI 都輸出開始、首次進入的階段、處理數、更新數、仍缺資料數及結果；重複進度最多每 30 秒彙總一次。獨立 CLI 應在後端停止後執行，可用 `--dry-run` 預演、`--limit` 限制本次處理量，並用 `--proxy` 或 `--no-proxy` 指定代理。自動回填會暫緩已知受限作品的網路請求，手動回填仍可重試，新取得的本地資料也可用於復原。

桌面啓動回填和工具頁的數據回填由應用宿主執行，代理默認讀取已保存的 `proxy.enabled`、`proxy.host` 和 `proxy.port`。工具頁可臨時覆蓋代理，僅用於該次回填，不回寫配置；未手動改動的參數跟隨最新保存值。FFmpeg 自動安裝同樣讀取已保存的全局代理。配置讀取失敗或啓用的代理地址、端口無效時操作報錯，不自動改用直連；關閉代理後使用直連，不繼承進程默認代理。停止或不啓動相應工具即可避免這些請求。

兩版下載頁在收藏數篩選缺少數據時，按作品類型調用相應插件的查詢能力，並使用原始作品 ID。插畫和小說分別走下表中的元數據接口；無法補齊時不把未知收藏數當作 0。關注新作的全量獲取在總頁數未知時最多掃描 1000 頁；第 1000 頁仍有後續數據就報錯，不將不完整結果加入隊列。

新版計劃任務列表及展開隊列在當前頁面可見時每 4 秒刷新一次本地接口，隱藏頁面、切換工作區或打開下載列表後暫停，返回時立即刷新。下載引導每 2 秒讀取頁面已有的示例作品隊列狀態，不額外請求 Pixiv；等待 5 分鐘仍未確認結果時提供繼續等待或返回下載頁。退出引導不會取消已提交的下載。

Pixiv 業務請求的 HTTP 傳輸由應用宿主提供，業務觸發方則可能是下載工作臺、小說插件或油猴腳本。下表按業務請求歸屬進行區分，不將共享傳輸層請求統一歸類爲核心請求。

支持任務提交契約的宿主中，插件調用 SDK `DownloadTasks.submit`，或管理員向 `POST /api/download/tasks` 提交作品類型、ID 和輸出選項，也會觸發所屬插件的下載。官方插畫與小說處理器先從 Pixiv 取得元數據或正文，再沿用既有媒體下載與代理路由；取得憑據通過獨立參數或 `X-Acquisition-Credential` 請求頭傳遞，不進入公開事件和任務快照。工作台將執行狀態合併到現有下載隊列；後端終態快照保留 5 分鐘，已保存的隊列記錄不會因此刪除。工作台每 2 秒從同源 `GET /api/download/tasks` 刷新任務，頁面隱藏或退出時停止輪詢；取消按鈕向具體任務的 `/cancel` 地址發送 POST，只請求該次執行協作停止。查詢與取消本身不向 Pixiv 提交新的下載，刷新頁面也不會重新提交任務。新接口以實際安裝的宿主和 SDK 版本爲準。

| 請求所有者 | 目標地址 | 用途與主要發送內容 | 觸發場景 | 代理與關閉方式 |
| --- | --- | --- | --- | --- |
| `download-workbench` 插件 | `https://www.pixiv.net/ajax/illust/**`、`/ajax/user/**`、`/ajax/search/artworks/**`、`/ajax/series/**`、`/ajax/collection/**`、`/ajax/follow_latest/illust`、`/rpc/index.php` | 獲取插畫、動圖、畫師、系列、搜索、收藏夾、關注動態和約稿等元數據；需要登錄的請求會使用已配置的 Pixiv Cookie | 預覽、快捷獲取、提交下載、執行計劃任務、回填作品信息或瀏覽相關取得模式時觸發 | 經宿主提供給插件的 Pixiv HTTP 能力和所選代理路由；`download-workbench` 是必需插件（required），其缺失或不可用時主要下載功能不可用 |
| `download-workbench` 插件 | `https://www.pixiv.net/ajax/illusts/bookmarks/add` | 下載完成後收藏插畫；發送作品 ID、收藏可見性和標籤，並使用 Pixiv 登錄憑據 | 僅在啓用“下載後收藏”等相應選項且作品下載完成後觸發；請求失敗不會回滾已完成的下載 | 關閉下載後收藏功能可停用該請求 |
| `novel` 插件 | `https://www.pixiv.net/ajax/novel/**`、`/ajax/novel/series/**`、`/ajax/novel/series_content/**`、`/ajax/user/**/novels`、`/ajax/user/**/novels/bookmarks`、`/ajax/search/novels/**` | 獲取小說正文、小說系列、作者小說列表、收藏和搜索結果；需要登錄的請求會使用 Pixiv Cookie。下載工作臺通過綁定當前 owner、作品與取得憑據的一次性短期票據複用預覽時已驗證的小說響應，不會在提交下載時再次請求同一小說正文 | 預覽小說、系列合訂、小說搜索、畫師小說和計劃任務處理時觸發；緊隨預覽的下載提交會複用響應，不帶票據的兼容客戶端直接提交仍由後端抓取，無效票據會被拒絕 | 經宿主 Pixiv HTTP 能力；禁用 `novel` 會撤回小說頁面和相關能力 |
| `novel` 插件 | `https://www.pixiv.net/ajax/novels/bookmarks/add` | 下載完成後收藏小說 | 僅在啓用相應選項且小說下載完成後觸發 | 關閉下載後收藏功能即可停用 |
| 應用宿主共享圖片傳輸，調用方主要爲 `download-workbench`、`novel` | HTTPS `*.pximg.net`；縮略圖還允許 `embed.pixiv.net` | 下載 Pixiv 原圖、封面、縮略圖、動圖壓縮包和小說內嵌圖片；通常發送 Pixiv Referer，圖片地址來自 Pixiv API 響應 | 下載作品、生成封面/縮略圖、瀏覽預覽或生成小說文件時觸發 | 使用 Pixiv 圖片下載路由；停止相關下載或預覽即可避免 |
| 根目錄油猴腳本，不屬於插件 | `https://www.pixiv.net/ajax/**`、HTTPS `*.pximg.net` | 在 Pixiv 網頁中直接讀取作品/小說信息、收藏作品或下載文件；`GM_xmlhttpRequest` 會使用瀏覽器/Pixiv 登錄態 | 用戶在 Pixiv 頁面點擊腳本功能、抓取頁面或執行本地下載時觸發 | 由瀏覽器/腳本管理器直連，不經過宿主代理；禁用或卸載相應腳本即可停用 |
| 根目錄油猴腳本，不屬於插件 | 管理員配置的 PixivDownloader 地址，默認 `http://localhost:6999` | 調用下載提交、隊列、狀態和 SSE 等後端 API；發送所選作品和下載參數。本機單人模式下載小說時，還會把腳本從 Pixiv 取得的有界小說響應發送給本機小說插件，換取短期一次性票據；不發送 Pixiv Cookie | 使用 Java 後端版、批量腳本或工具箱功能時觸發 | 默認僅連接本機；配置爲遠端地址後，請求將發送至相應遠端服務器，但小說響應導入會關閉並改由後端自行抓取 |
| 官方 `pixiv-batch-downloader-import` 插件的採集油猴 | 本機 `http://localhost:6999/api/pixiv-batch-downloader-import/token` 與 `/api/pixiv-batch-downloader-import`；可配置 loopback 主機、端口及 HTTP/HTTPS | 將 PixivBatchDownloader 的成功文件路徑、作品元數據和小說正文發送到同一機器的服務，登記原文件引用；不發送 Pixiv Cookie、管理員密碼或環境 cookie，也不重新請求 Pixiv 媒體 | 先安裝並啓用[官方導入插件](/zh-hant/pixiv-batch-downloader-import)，普通包不預裝、離線全量包包含；安裝腳本並刷新 Pixiv 頁面後監聽成功事件；完整作品自動發送，頁面打開時對離線隊列退避重試。僅支持單人模式；尚未配置目錄時，服務端只從完整作品的絕對路徑提出候選目錄，由桌面彈窗請求用戶確認或改選，確認保存後才導入 | 由腳本管理器訪問本機，不經過宿主出站代理。停用腳本可停止發送；禁用插件可停止接收，清空源目錄立即停止導入；候選路徑本身不會寫入配置。原文件不複製，刪除畫廊記錄保留原文件 |
| `pixiv-batch-downloader-import` 歷史匯入腳本 | 設定的回環服務：`/api/pixiv-batch-downloader-import/token`、`/api/pixiv-batch-downloader-import/history`；目前 Pixiv 來源：`/ajax/illust/{id}`、`/ajax/novel/{id}` | 本機接收歷史分片、作品 ID 和原始 AJAX 回應；Pixiv 接收 ID 與瀏覽器同源登入憑據。Cookie 不轉送至本機，不上傳原作品檔案 | 使用者從腳本選單主動讀取並檢查目前瀏覽器的歷史資料庫，明確開始後才取得中繼資料並登記 | 預設不執行歷史匯入，僅支援 solo 回環與可信 Pixiv 來源。Pixiv 使用瀏覽器網路設定，本機傳輸使用 GM，不經宿主出站代理；拒絕重新導向。取消、離頁或換服會向原位址請求停止，不自動續跑或重送寫入 |

後端對普通 Pixiv JSON 使用 4 MiB 響應預算，對小說系列詳情與分頁內容響應使用 1 MiB 預算；存在 `Content-Length` 時會先校驗聲明長度，並始終按實際流讀取量執行上限，超限時中止處理並返回受控錯誤。小說元數據最多接受 512 個內嵌圖片映射；短期票據只保留移除正文和內嵌圖片表後的原始元數據，且不超過 256 KiB。

四個後端下載器腳本在用戶刷新或提交已選收藏夾時請求配置後端的 `GET /api/collections`。啓用小說自動翻譯時，提交前用 `GET /api/auth/check` 檢查管理員登錄，並在下載參數中發送目標語言、分段大小和合訂選項。下載後通過 `GET /api/novel/translate-status/{id}` 觀察翻譯狀態；單作品腳本先用 `GET /api/novel/status/{id}` 等待下載完成。這些請求使用腳本管理器已有的後端會話，不向後端傳送 Pixiv Cookie。翻譯由後端調用已配置的 AI 服務。關閉腳本頁面會停止狀態查詢，已提交的後端任務仍可繼續。

通過後端頁面安裝的新腳本會獲准在該後端地址的兩種下載頁面運行，只響應頁面發起的腳本 ID 和版本查詢，不在後端頁面執行 Pixiv 下載邏輯。查詢在當前頁面內通過瀏覽器消息完成，不向服務器或第三方發送安裝清單、Cookie 或腳本設置；未響應時顯示「未檢測到」。安裝、更新或啓停後需刷新頁面。

插畫元數據、分頁圖片及 Ugoira 代理只接受範圍內的正整數作品 ID，規範化後用於 `www.pixiv.net/ajax/illust/{id}` 及其固定子路徑。邀請訪客只能請求可見作品；非法或不可見 ID 不會觸發上游請求。

## `douyin` 插件

`douyin` 是按需安裝插件，默認 Windows 安裝包和 Java 標準包不預置。只有安裝、啓用並使用 Douyin 功能後纔會產生下列請求。

| 目標地址 | 用途與主要發送內容 | 觸發場景 | 代理與關閉方式 |
| --- | --- | --- | --- |
| `https://www.douyin.com/aweme/v1/web/**` | 獲取用戶作品、喜歡列表、合集、搜索、音樂作品、收藏夾、賬號信息和作品詳情；會發送 Douyin Cookie、查詢參數及模擬瀏覽器所需請求頭 | 手動取得、預覽、下載、計劃任務發現、Cookie 探活和 Douyin 畫廊刷新時觸發 | 使用任務級、來源默認或宿主全局路由；禁用/卸載 `douyin` 即完全停用 |
| HTTPS `douyin.com` / `*.douyin.com`、`iesdouyin.com` / `*.iesdouyin.com`，常見爲 `v.douyin.com` | 解析用戶粘貼的短鏈接，最多跟隨受限跳轉 | 輸入 Douyin 短鏈接並開始解析時觸發 | 與該次 Douyin 任務使用相同路由 |
| HTTPS `*.douyinvod.com`、`*.douyinpic.com`、`*.douyinstatic.com`、`*.amemv.com`、`*.byteimg.com`、`*.bytedance.com`、`*.bytecdn.cn`、`*.pstatp.com`、`*.snssdk.com`，以及上述 Douyin 域 | 下載視頻、圖片、封面和實況照片媒體；具體 URL 來自 Douyin API 響應並可能發生受限重定向 | 執行 Douyin 媒體下載時觸發 | 與該次 Douyin 任務使用相同路由；停止任務或禁用插件即可終止後續請求 |

`www.douyin.com/aweme/v1/web/` 請求會從使用者提供的 Cookie 讀取 UIFID，並將它作為請求標頭和查詢參數傳送；查詢還包含時間戳記及在本機計算的 `x-secsdk-web-signature`。簽章計算不呼叫第三方服務。Cookie 未包含 UIFID 時不會產生替代身分，受保護的端點仍可能拒絕請求。

## `ai` 插件

AI 插件使用 OpenAI 兼容協議。連接測試、翻譯或其它 AI 功能會向所選基礎地址的 `/chat/completions` 發送待處理文本、特定用途提示詞、模型名稱和 API Key。主動在桌面設置中獲取模型列表時，插件使用當前表單中尚未保存的基礎地址和代理選項向同一基礎地址下的 `/models` 發送 GET 請求，不發送正文或提示詞。模型查詢先不攜帶密鑰；遇到 HTTP 401/403 且表單已填寫密鑰時，才帶上該密鑰重試當前請求，後續分頁沿用該鑑權方式。整輪查詢最多重試一次，共用原有時間和響應大小預算；其它錯誤直接顯示失敗。安裝插件或僅打開配置不會發起這些請求。

小說翻譯會隨請求傳送名詞對照表中的原文、目標語言和譯名，每次最多 1,000 項。既有條目優先，本章新詞按出現順序補充剩餘容量，後續分段也遵守此上限。未隨請求傳送的新詞仍會在翻譯成功後合併到名詞對照表；條目上限不代表 token 上限。

攜帶密鑰時通常使用 Bearer 鑑權；基礎地址爲 `https://api.anthropic.com/v1` 時，模型查詢按 [Anthropic 官方協議](https://platform.claude.com/docs/en/api/models/list)使用 `anthropic-version` 請求頭，鑑權重試時增加 `x-api-key`，並以 `after_id` 游標繼續請求同一端點。不會採用響應中的跳轉地址，也不會爲其它服務商自動改換主機、地區或工作空間。查詢失敗、未知分頁協議或超限時不返回部分列表；不支持該查詢的服務仍可手動填寫模型。

模型 ID 以純文本候選顯示，選定後只回填當前草稿，保存後才更新配置。列表排除明確標註不支持聊天的模型，能力未知的條目仍保留；目錄可見不保證賬戶權限、計費開通或聊天協議兼容，也不保證列出的都是最新發佈模型。選定後可使用連接測試覈實實際調用。

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
| 騰訊混元 / TokenHub | `https://tokenhub.tencentmaas.com/v1` |
| 百度千帆/ERNIE | `https://qianfan.baidubce.com/v2` |
| 訊飛星火 | `https://spark-api-open.xf-yun.com/v1` |
| MiniMax | `https://api.minimax.cn/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| SiliconFlow | `https://api.siliconflow.cn/v1` |
| Ollama | `http://localhost:11434/v1` |
| LM Studio | `http://localhost:1234/v1` |

主動選擇預設時才會回填建議模型和基礎地址；已保存的配置不會自動遷移。騰訊混元預設使用 TokenHub，需要在該平台創建 API Key 並開通模型，舊混元平台的密鑰不能沿用。舊配置仍使用原地址，遷移時請重新選擇預設、填寫新密鑰並保存。

AI 基礎地址可配置爲其它兼容服務，因此完整目標範圍取決於實際配置。自定義目標會收到相應請求參數；聊天請求和模型查詢的鑑權重試還會攜帶已填寫的 API Key，聊天請求另含待處理文本。目標也可能是本機或內網，只應使用管理員信任的本地、自建或第三方服務。AI 請求不保存 Cookie，也不跟隨 HTTP 重定向，避免把憑據轉發到跳轉目標；直連或代理同樣由當前 AI 配置決定。關閉 AI 會停止業務調用，但仍允許主動測試連接和獲取模型列表；刪除密鑰不會阻止匿名查詢。停止這些手動操作或禁用 `ai` 插件可停止相應請求。

## `translation` 翻譯外掛

僅在管理員開始翻譯，或下載後自動翻譯已啟用時連線。AI 使用 `ai` 儲存的網址與憑證。有道向固定的 `https://openapi.youdao.com/api`、LibreTranslate 向設定的 `translation.libretranslate-url` 下的 `/translate` 傳送 POST 表單，包含選定的正文、標題或簡介、來源語言自動辨識與目標語言。有道傳送應用 ID、時間、隨機值及 v3 簽章，應用金鑰僅在本機參與簽章；LibreTranslate 依服務需要傳送 `api_key`。

`translation.use-proxy` 預設 `false` 為直接連線，`true` 使用程式代理；AI 沿用自己的代理設定。連線逾時 30 秒，讀取逾時 120 秒，不跟隨重新導向、不自動重試、不儲存 Cookie。一般翻譯每次最多 1,000 Unicode 碼點，回應最多 1 MiB，拼接後每個欄位最多 64 Mi UTF-16 碼元。

LibreTranslate 可指向可信的本機或內網服務，須自行準備服務與語言模型；外掛不下載模型。準備完成後，本機服務可離線翻譯。關閉自動翻譯、停止手動操作或停用外掛可停止後續請求。取消瀏覽器請求不能保證已接受的處理立即停止。僅安裝外掛或開啟設定不會連線。

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

插件管理頁開啟、手動重新整理及從歷史快取返回時，透過應用同源的 `/api/plugins/status` 讀取狀態，並按目前插件載入 `/api/i18n/**` 詞典。生命週期操作或本機上傳結束後，頁面重新讀取狀態和 `/api/navigation` 導覽；網路失敗也會核對實際狀態，不自動重送寫入。替換查詢或離頁會取消舊讀取，舊結果不覆蓋目前頁面；已發出的寫入仍可能完成。請求攜帶同源工作階段 Cookie，使用瀏覽器網路設定，不經過後端出站代理。關閉頁面停止後續頁面查詢。

市集頁也透過同源 `/api/plugin-market/**` 查詢儲存庫、清單、詳細資料與套件資訊，透過 `/api/plugins/recovery` 和 `/api/plugins/acquisitions` 讀取復原狀態和安裝記錄。替換查詢、關閉詳細資料或離頁時會取消對應的舊讀取；返回頁面重新查詢，瀏覽器取消無法保證伺服器已開始的儲存庫存取停止。離頁前尚未提交的安裝或復原確認失效，已發出的寫入仍可能完成；寫入後重新讀取目前清單、復原狀態和導覽，不自動重送安裝。請求使用同源工作階段 Cookie，瀏覽器到應用程式使用瀏覽器網路設定，宿主到儲存庫沿用該儲存庫的代理策略。

管理員打開帶插件管理導航的頁面，或刷新插件管理狀態時，宿主會爲已安裝且來源已驗證的插件檢查更新。檢查只訪問對應的已啓用倉庫，複用市場的清單、簽名和撤銷查詢；官方簽名的本地安裝包使用官方倉庫。成功結果緩存五分鐘，部分失敗緩存三十秒，安裝狀態或倉庫配置變化後重新檢查。瀏覽器沒有定時輪詢，也不自動安裝；關閉 `plugin-catalog.enabled` 或相應倉庫可停止後續請求。

正式版主清單保留各插件的最新版本、最新穩定版本，以及固定舊 SDK `1.0.0`、`1.1.0` 各自的最新兼容穩定版本。其餘版本存放在同目錄的 `history/<pluginId>-<sha256>.json`。查看歷史、解析指定舊版或尋找兼容及渠道候選時才讀取該文件；默認官方地址位於 `raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/history/`。文件按摘要命名，舊文件不覆蓋或自動刪除。歷史文件最多 1 MiB、900 個版本，必須匹配已簽名主清單中的大小和 SHA-256。請求沿用所屬倉庫的代理、HTTPS、公網地址和重定向策略，不攜帶應用 Cookie 或憑據。

管理員確認安裝計劃後，創建操作記錄只寫入本機內存；執行計劃和包下載後仍讀取並複核目錄與撤銷狀態。首次執行信任確認會暫存已下載的包，用戶確認精確 SHA-256 後可繼續使用同一包，安裝器仍完整複驗。每次最多保留一個待確認包，十分鐘後自動釋放；取消確認、開始另一項安裝或關閉服務也會釋放。操作記錄與進度查詢只讀取本地狀態，不會重新提交安裝。

宿主按網絡策略複用 HTTP 連接，代理或超時配置變化後更新客戶端。社區認證每次仍讀取 `generated/current.json`；其引用的摘要、地址和簽名一致時複用已驗證的目錄根與分片，描述符僅在當前認證摘要匹配時複用。同一次市場查詢只認證一次倉庫。頁面切換倉庫、刷新或離開時會取消舊的清單請求；瀏覽器取消不保證已經發出的服務端外聯立即停止。請求目標、代理邊界、簽名與撤銷檢查保持原規則，不發送應用 Cookie 或下載憑據。

支持文檔展示的市場通過同源 `/api/plugin-market/content/**` 讀取受信目錄所引用的圖標、截圖、README、完整日誌與本次更新說明。圖標和截圖隨可見內容按需加載，文檔僅在展開時請求；宿主使用所屬倉庫的代理、HTTPS、公網地址及重定向規則，下載後覈對大小、SHA-256 和圖片格式。默認官方與社區附件位於各自 GitHub 版本 Release，可能進入允許的資產 CDN。請求不攜帶應用 Cookie 或下載憑據；瀏覽器只接收校驗後的圖片和隔離靜態正文，不直接加載正文任意外部圖片。關閉市場或倉庫可停止後續讀取，相關鏈接只在用戶點擊時打開。

瀏覽市場時，如果最新版不兼容當前 SDK，宿主會自動查詢同一倉庫的歷史版本，並默認選擇最高的兼容可安裝舊版。頁面會同時顯示最新版和所選版本；沒有兼容版本與查詢失敗分別提示。分頁倉庫的查詢可能追加詳情頁請求，每個插件最多讀取 10 頁、每頁最多 100 個版本。同一次頁面查詢共享 10 秒的追加請求預算，已發出的請求仍按倉庫網絡超時結束。請求沿用該倉庫的地址和網絡設置，只讀取版本元數據，不下載插件包；安裝仍需用戶確認。已安裝的較高版本不會因此自動降級。

管理員還可在插件管理頁主動刷新已配置來源的撤銷信息。應用宿主僅向該來源配置的 `revocations.json` 與相鄰 `.sig` 發出請求，不接受請求正文提供任意 URL，不發送應用 Cookie、作品或憑據；繼續使用該倉庫既有代理、HTTPS、來源和大小校驗。需要 `plugin-catalog.enabled` 與倉庫均啓用；不點擊刷新便不會觸發這項請求。管理狀態和安裝操作記錄查詢只讀取本地狀態，不定時向倉庫刷新。

| 請求所有者 | 目標地址 | 用途 | 觸發場景與默認狀態 |
| --- | --- | --- | --- |
| 應用宿主的插件市場 | 正式版使用 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`，每夜構建版使用同倉庫的 `nightly-manifest.json`；包地址通常爲 GitHub Release，並可能重定向到 `*.githubusercontent.com` | 獲取與當前應用發佈渠道匹配的官方插件清單、下載用戶選擇的插件包並做簽名、SHA-256 和大小校驗 | 清單由打包進應用的版本自動選擇；`plugin-catalog.enabled` 與內嵌官方倉庫默認啓用，管理員打開或刷新市場時拉取清單，明確安裝插件時下載包；應用啓動本身不訪問倉庫；最多跟隨五跳重定向且每一跳都重新校驗，關閉主開關可完全停用該鏈路 |
| GUI 倉庫描述符導入 | 管理員輸入的公網 HTTPS `repository.json` | 預覽倉庫聲明的發佈者、目錄、撤銷/更新證明端點、實際聯網主機和完整公鑰指紋；請求不攜帶 Cookie、賬號、作品、本地路徑或其它應用憑據，也不請求 `repository.json.sig` | 在 GUI 預覽、確認和統一保存新描述符快照時觸發；確認會重新獲取並要求描述符 SHA-256 不變。響應最大 64 KiB，所有地址都執行公網 HTTPS 與 SSRF 校驗；`DIRECT_STRICT` 不跟隨重定向，`GITHUB_RELEASES` 只允許 GitHub 固定主機邊界內一跳。取消預覽或不確認不會保存/啓用倉庫 |
| 應用宿主的插件市場 | 已信任描述符中的 HTTPS catalog endpoint、可選 `revocations.json` / `repository-update.json` 及相鄰 `.sig`，以及目錄指定的插件 JAR/ZIP URL | `manifest-v1` 讀取清單和清單簽名；`paged-v2` 分頁讀取列表、詳情和指定版本；每次列表、詳情和版本事實查詢刷新必選撤銷文檔及簽名，同一響應使用同一快照；安裝在解析依賴前及下載完成後再次覈對。隱藏或撤銷的版本不可新裝，仍可查看歷史診斷；無有效快照或超過 24 小時寬限期時禁止新安裝，刷新失敗不會清除已知限制。包下載後還校驗大小、SHA-256、發佈者簽名和包內 descriptor。重新導入已信任倉庫時可能讀取連續性證明 | 瀏覽/搜索/翻頁、查看詳情、明確安裝/更新或重新導入時觸發；啓動只讀取本地最後有效撤銷快照，不自動請求第三方倉庫。使用描述符映射後的固定安全網絡檔位；禁用/刪除倉庫或關閉 `plugin-catalog.enabled` 可停用後續請求 |
| 應用宿主的社區目錄與插件市場 | `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/` 下的 `generated/current.json`、`generated/generations/<sequence>/directory.json` 及按需 shard、`generated/repository.json`、清單與撤銷文件；審核、投稿和歷史發佈者記錄來自該倉庫中的受控相對路徑，包地址來自已驗籤清單 | 獨立社區根驗證目錄與包簽名，覈對目錄序號、描述符與 key 指紋；所選版本詳情最多讀取一份審核記錄，安裝還驗證原發佈者簽名與實際包內聲明。請求只包含公開路徑及標準連接元數據，不攜帶 Cookie、私鑰或應用憑據 | 社區倉庫默認啓用，管理員瀏覽社區來源、查看版本、安裝或預覽/確認第三方倉庫認證時觸發；啓動只做本地複驗，不自動安裝。沿用宿主代理和 `GITHUB_RELEASES` 的公網 HTTPS、GitHub 主機與一跳重定向邊界。關閉 `plugin-catalog.community-repository-enabled` 停止社區市場請求；第三方倉庫認證查詢隨 `plugin-catalog.enabled` 主開關關閉。目錄失敗保留已驗證的最近一個桶，損壞狀態拒絕重置防回滾水位 |
| 應用宿主 FFmpeg 安裝器 | `https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/ffmpeg-release.json`、相鄰的 `ffmpeg-release.json.sig`、當前系統與架構對應的 `ffmpeg-{windows-x64,linux-x64,linux-arm64,macos-x64,macos-arm64}.zip`，以及 GitHub Release CDN 重定向 | 先使用應用內置官方信任根驗證 Ed25519 清單簽名，再按清單中的精確資產名、大小和 SHA-256 驗證 FFmpeg 官方穩定源碼構建，全部通過後才解壓。GET 請求只發送 FFmpeg 安裝器 User-Agent、IP 等標準連接元數據，不發送 Pixiv Cookie、賬號或其它應用憑據 | 僅在 GUI 中明確選擇自動安裝 FFmpeg 時觸發；應用啓動本身不會下載；不支持的系統繼續使用手動安裝。請求沿用宿主代理設置；不執行自動安裝即可停用該鏈路 |
| Windows Setup 的 FFmpeg 可選任務 | 與上一行相同的簽名清單、detached signature 和固定的 `ffmpeg-windows-x64.zip`，以及 GitHub Release CDN 重定向 | 在安裝期使用安裝包內置的官方信任根驗證清單簽名和 Windows 資產的精確名稱、大小、SHA-256，通過後才解壓到應用工具目錄。目標站點請求不攜帶應用賬號或 Pixiv 憑據；使用系統代理時，代理連接可以使用當前 Windows 用戶的默認代理憑據 | 只有用戶在 Setup 中明確勾選 FFmpeg 可選任務才觸發。Setup 優先使用已啓用的系統 HTTP/HTTPS 代理，未找到時直連；不選擇該任務即可完全跳過 |
| 油猴腳本管理器，不屬於插件 | `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js` | 檢查和下載六個獨立油猴腳本更新 | 由 Tampermonkey 等腳本管理器按其更新策略觸發；禁用腳本自動更新或卸載腳本即可停止 |
| All-in-One 油猴腳本管理器，不屬於插件 | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/Pixiv%20All-in-One.user.js` | 檢查或下載構建生成的合併腳本 | 構建生成腳本的元數據仍聲明此更新地址，管理器按其策略嘗試；是否成功取決於目標附件。當前發行流程不上傳 userscript 附件；從後端安裝時更新地址由安裝響應改寫爲該後端 |

GUI 預覽和確認描述符時會聯網；保存包含新描述符快照的設置時，會再次獲取描述符並校驗同一 SHA-256。確認前不修改配置，確認後的草稿也必須通過設置頁統一保存才會寫盤。

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

- `gallery-tools`：提供統計和重複檢測，讀取本地數據庫、文件和 Hash 數據；`stats` / `duplicate` 是其功能，不是另需安裝的兩份官方包。
- `gallery`：瀏覽本地下載記錄和本地媒體。
- `gui-swing` 與 `gui-compose`：分別渲染自己的桌面界面，提供窗口、托盤、主題和平台資源，不增加固定第三方運行時目標。
- `recovery-sentinel`：僅用於恢復模式驗證，不包含在常規用戶發行包中。

它們的頁面仍會調用當前 PixivDownloader 實例的同源 API，但這不是訪問第三方公網。

## PostgreSQL 主數據庫

默認 SQLite 數據庫插件只訪問本機文件。管理員安裝並選擇 PostgreSQL 插件後，連接由該插件的 JDBC 驅動建立，目標是 `database.jdbc-url` 指定的服務器。默認端口爲 5432，實際主機、端口、數據庫和主庫 schema 均由管理員決定。

桌面設置或插件市場的「測試連接」訪問未保存的草稿目標，發送草稿認證材料並讀取版本、編碼、schema 和身份，不建表或替換活動連接池。「檢查並繼續」還檢查源庫結構、全部私庫，以及目標是否爲空或支持安全備份替換；重試時讀取已提交目標的實際數據，覈對源變化和數據摘要。確認前不遷移數據、不創建私庫憑據、角色或 schema。PostgreSQL 連接預檢的連接、登錄和 socket 等待各最多 10 秒，保留更短的已配置值；這不是整個遷移任務的執行時限。

確認清單後，桌面設置先保存本頁其它設置，宿主自動停止業務並通過完整進程重啓編排遷移，源和目標數據庫提供者負責讀寫與覈驗。支持替換的非空目標先保留舊數據備份，遷入覈驗後再逐庫替換。連接會發送認證材料、SQL、作品元數據、通知、計劃狀態、計劃憑據及已聲明的插件私庫數據。全部主私庫覈驗成功才切換配置；失敗保留源配置及已提交目標數據。SQLite 與 PostgreSQL 支持雙向遷移，也可單獨使用停機工具。日常啓動、結構演進、業務讀寫和維護同樣由所選 provider 連接配置目標。私庫使用獨立 owner schema 和受限登錄身份，密碼保存在本地加密憑據存儲中，普通插件不能取得主庫賬號。

JDBC 的 TCP / TLS 連接不經過 Pixiv HTTP 代理；TLS 由 `sslmode` 和可選 `sslrootcert` 控制，遠程連接建議使用 `verify-full`。密碼通過獨立配置項或 `DATABASE_PASSWORD` 提供，URL 不接受憑據。連接或 provider 不可用時進入恢復模式，停止依賴數據庫的業務及後台操作，不改連其它引擎。確認前可取消遷移；停用遠程連接須停止應用，修正數據庫選擇後完整重啓。直接編輯配置不會複製歷史數據，使用方式見[配置參考](configuration.md)。

## 本機、同源及管理員配置的目標地址

圖片分類器只在取得正數作品編號後查詢詳細資料；服務未回傳有效編號時嘗試目錄名稱。

- 桌面 GUI 由應用宿主通過 `http://127.0.0.1:{臨時端口}/api/gui/**` 輪詢狀態和提交操作，攜帶當前後端的 GUI token、語言、操作參數，以及聲明式動作的插件 owner。桌面模式默認創建此入口，端口由系統分配並隨每次後端啓動重新取得，不寫入配置。連接明確繞過系統與宿主代理，不跟隨重定向，不自動重發寫操作，也不使用 `ssl.domain` 或公開 HTTPS 端口。入口僅綁定本機，拒絕非 GUI 路徑和轉發頭，仍校驗本機來源、Host / Origin 與 token。使用 `--no-gui` 或無頭模式時不創建該入口；沒有遠端替換選項。
- Web 頁面和插件前端使用瀏覽器同源地址訪問 `/api/**`、靜態資源和 SSE。邀請鏈接、首次配置和桌面網頁入口由宿主統一構造，使用實際公開監聽協議、端口與管理員配置的 `ssl.domain`（默認 `localhost`）。開發模式自動順延端口後，鏈接隨當前實例更新，不改寫配置端口。公開 HTTP 到 HTTPS 的重定向只作用於配置的重定向入口，不影響 GUI 本機通信。
- Ollama、LM Studio、VoxCPM、CosyVoice 和油猴腳本後端可配置爲本機服務；基礎地址指向遠端後，該遠端即成爲新的數據接收方。
- 圖片分類器的 `server.url` 留空時使用上述當前實例地址；填寫地址後連接指定的 PixivDownloader 實例，已有設置繼續生效。分類器向該目標查詢作品信息，並在移動作品後提交新路徑；目標爲遠端時，這些數據也會發送到遠端。關閉分類器可停止這些請求。
- 自定義 Webhook、AI/TTS 基礎地址、插件倉庫、Bark、SMTP、SOCKS 和代理端點都由管理員配置，無法形成封閉的固定域名白名單。更新清單 URL 也可由管理員配置，但只接受帶有效官方簽名的公網 HTTPS 目標。
- 根目錄 `cors-js-runner.html` 是開發調試工具，會請求操作者輸入的任意 URL；它不屬於常規用戶運行鏈路。

## 官方驗證碼外掛的執行期存取

官方選用外掛 `captcha` 預設不安裝。管理員設定 Turnstile 並發布表單綁定後，實際挑戰會在瀏覽器載入 `https://challenges.cloudflare.com/turnstile/v0/api.js` 及同源 iframe，傳送網站公鑰、操作標記、本次嘗試識別及廠商協定使用的瀏覽器網路資訊。外掛僅在驗證回應時，向同源 `/turnstile/v0/siteverify` POST 驗證金鑰與票據，不傳送登入密碼、Cookie 或完整業務請求。瀏覽器使用自身網路/代理設定；伺服器使用受管 HTTP 並繼承應用代理，禁止重新導向與 Cookie。取消綁定並發布可停止該頁面的線上驗證；停用外掛會拒絕受保護操作。ALTCHA SHA-256/PBKDF2 Worker 隨外掛提供，只存取目前實例，不請求第三方服務。

綁定並發佈 reCAPTCHA v2 後，開始驗證才載入 `https://www.google.com/recaptcha/api.js?render=explicit`，腳本及內嵌資源使用 `www.google.com`、`www.gstatic.com`、`recaptcha.google.com`。瀏覽器傳送網站識別碼及 Google 協定收集的資訊；提交時，伺服器經受管 HTTP 向 `https://www.google.com/recaptcha/api/siteverify` POST 密鑰與票據，不傳送登入密碼、Cookie、完整表單或額外 remoteip 參數。伺服器沿用應用程式代理並停用重新導向及 Cookie。移除元件並重新發佈可停止該頁面的 Google 存取。Hutool 字元及 Tianai 拼圖皆在本機產生；瀏覽器僅取得同源短期圖片，不向第三方傳送答案。

## 開發、建置和發布流程的網路存取

建置官方模組 `pixivdownload-plugin-captcha` 或獨立 SDK 工程 `examples/captcha-plugin` 時，Maven 依設定的儲存庫、鏡像及代理取得 `org.altcha:altcha`、`org.json:json` 與建置依賴；資源建置自動執行 `npm ci`，依鎖定檔取得 `altcha` 與傳遞依賴，目前鎖定檔使用 `registry.npmmirror.com`。請求傳送套件座標及已設定的套件管理器認證，不上傳專案憑證。建置會將官方 Worker 與授權文件寫入外掛。明確執行 `page-editor-acceptance.mjs` 驗證碼驗收時，會以公開測試金鑰存取 Cloudflare 官方測試元件及 Siteverify。一般應用啟動不執行這些工程請求。取消建置/測試會停止後續存取；經驗證的本機依賴快取可減少下載。

圖片元件新增 Maven 依賴 `cn.hutool:hutool-captcha`（及 `hutool-core`）、`cloud.tianai.captcha:tianai-captcha`，沿用設定的倉庫、鏡像與代理。明確執行驗證碼瀏覽器驗收也會以公開測試密鑰連線 Google 官方測試控件與 Siteverify；一般啟動不執行這些請求。

本節供建置、投稿、審核及發行維護者確認網路目的地、傳送資料與停止方式。這些流程由明確命令或 GitHub Actions 事件啟動，不是已安裝應用程式的日常請求。取消命令或對應 workflow 可停止後續請求；已完成的遠端寫入須依其流程另行處理。

### 工程工具的代理、驗證與恢復

各工具使用自己的網路設定。GitHub CLI 使用目前登入身分或作業憑證；Git 另行核對提交身分和遠端認證；Maven 使用 settings 的倉庫、鏡像與代理。應用程式的 Pixiv HTTP 代理不會自動控制這些工具。

社群 Node 工具下載公開套件、原始碼及建置工具時，遵守下列共用規則：

- 先比對 `no_proxy` / `NO_PROXY`，再依序讀取 `https_proxy`、`HTTPS_PROXY`、`all_proxy`、`ALL_PROXY`，小寫優先。未設代理變數時，Windows 讀取目前使用者的系統代理和略過設定，其他系統直連。明確的空代理值或 `NO_PROXY=*` 可選擇直連；支援 HTTP / HTTPS 代理，不支援 SOCKS。代理失敗便停止，不自動改成直連。
- 每次連線與重新導向都核對公網 IP；CONNECT 固定到本次核驗的 IP，來源站 TLS 驗證原始主機名稱。代理帳密只用於代理認證；GitHub 憑證和 Cookie 不進入公開檔案下載。
- 連線中斷、DNS、連線失敗、逾時及 HTTP 408/500/502/503/504 最多嘗試三次，依序等待一秒、兩秒。代理查詢、各次嘗試、重新導向及回應內文共用六十秒期限。每次重試重新驗證 TLS、公網地址、大小與摘要，並清理本次產生的未完成檔案。憑證、摘要、大小、URL、本機檔案錯誤及其他 HTTP 狀態不重試。
- 暫時性錯誤耗盡重試後，向導顯示失敗步驟、輪次與累計次數。手動繼續只為該請求或檔案建立新一輪預算，保留已完成步驟；也可儲存進度後退出。PowerShell 啟動器同樣按檔案累計輪次與嘗試次數，但使用自己的下載實作。

向導準備 SDK、讀取社群資料和詢問金鑰前，先以 GitHub CLI 的 `GET /user` 核驗目前帳號。未登入或憑證失效時，可在另一終端登入後重新檢查，或儲存退出；變更環境變數中的憑證需要重新啟動向導。GitHub 登入不能代替 Git 遠端認證。診斷區分權限不足、物件不可見、限流、代理、DNS、TLS 和斷線，只顯示受控錯誤碼、HTTP 狀態、退出碼及發生步驟，不輸出原生命令、憑證或帶參數地址。

社群 API 的一般單次預算為六十秒、32 MiB。GET 遇到上述可恢復 HTTP 狀態，或已識別的暫時連線、DNS、逾時和 EOF 錯誤，最多嘗試三次並等待一秒、兩秒，共用六十秒；權限、限流、憑證和原因不明的傳輸錯誤不自動重放。寫入則先回讀同一物件並比對身分和內容，再由所屬步驟決定是否恢復，HTTP 用戶端不盲目重送。建立 fork、候選歸檔、上傳、發布、Git 傳輸、建立 PR 和取消請求各自恢復；可恢復故障每輪最多三次。等待原始碼 CI 時斷線，只繼續讀取已確認的同一次執行，不重新觸發 CI，也不延長原期限。

恢復本機投稿記錄仍會存取 GitHub，重新核對原始碼、身分、簽章與完整請求。社群主線前進時，每次操作最多恢復三輪，保留原請求位元組；先讀取帳號、分支、Git 樹和原 PR，再以 `compare/<原基線 SHA>...<目前 SHA>` 核對祖先關係。只有作者、目標倉庫和全部投稿位元組一致才重用原請求；否則依最新狀態重新驗證，寫入前再次確認。到達上限後保留進度，不持續輪詢。記錄本身不授權寫入。

完成審核或簽署狀態操作並向原 PR 追加提交後，工具回讀 PR、原生 Git ref 和社群主線，最多五輪、間隔一秒。僅在分支已更新而 PR 尚顯示原父提交時等待；其他變化立即阻斷。寫入回應遺失也先核對 ref，不重複推送。Release 發布與 PR 通知分開恢復；通知失敗保留已發布產物，重試先讀取已有結果。主線前進後還須確認原執行、祖先關係、受保護程式未變、PR head 與狀態；空通知不發出請求。

### 依賴、版本與市場文件

| 操作 | 目的地與傳送資料 | 觸發、驗證及限制 |
| --- | --- | --- |
| JDBC 與資料庫測試依賴 | Maven 倉庫 / 鏡像中的 `org.postgresql:postgresql`、`org.xerial:sqlite-jdbc` 和測試用 `com.h2database:h2`；只傳依賴座標，不傳應用程式資料庫憑證 | 原始碼建置或預取缺少快取時下載。H2 只供獨立第三提供者測試，在行程內使用隔離記憶體庫，不隨宿主或官方插件分發 |
| 真實 PostgreSQL 驗收 | `PIXIV_TEST_POSTGRES_URL` 指定的隔離服務及測試帳號；若明確使用 `postgres:17`，Docker 另存取其 registry、token 服務和鏡像層 CDN | Docker 使用自己的鏡像源、代理與認證。停止測試及容器即可停止存取；一般應用程式啟動不下載資料庫鏡像 |
| 插件版本與最低 SDK 檢查 | GitHub CLI 分頁讀取 `api.github.com/repos/Sywyar/PixivDownloader-plugins/releases`；Maven 取得插件聲明的最低 SDK 固定座標 | Quality Gate 或明確檢查命令觸發。GitHub 使用 CI 憑證或本機 CLI 登入；Maven 使用自己的 settings。聲明目前原始碼 SDK 時使用當輪產物；請求失敗會阻止驗證通過 |
| 發行目錄與歷史版本 | GitHub Contents API 的已發布主清單、簽章及所引用歷史檔案；選定套件的下載地址 | 目錄生成先驗證並保留舊版本；打包在主清單無相容套件時讀取同源歷史、核對摘要後選包。認證、代理和驗簽規則沿用對應發布 / 打包工具 |
| 文件投稿 | 凍結原始碼、作者明確選取的本機檔案、公網 HTTPS 圖片，以及已核驗數字身分的來源倉庫 GitHub Release API | 只有作者確認後才下載圖片，沿用社群 Node 規則。確認投稿後將已預覽文件與圖片原始位元組上傳至 `market-content-<plugin>-<version>` Release；取消確認不發布。GitHub 認證只用於 API / 上傳，公開附件下載不帶憑證 |
| 官方與社群市場內容 | 同一版本 Release 的 `market-content.json`、已登記文件與圖片附件及其公開下載地址 | 官方插件發布 / 目錄生成由 SDK 工具處理；社群審核與發布將已登記附件複製到社群版本 Release，再逐一公開回讀。Java 工具使用系統代理、GitHub 主機白名單與逐跳 TLS，不傳應用程式憑證。一次內容下載共用五分鐘、單次連線 / 讀取最多十五秒；中繼資料 64 KiB、文件合計 4 MiB、文件圖片 8 MiB、市場圖片 8 MiB。所有摘要及公開回讀成功後才可完成 |
| 已登記版本的公開狀態 | 社群倉庫的 `releases/tags/<tag>` 和 `releases/<id>/assets`，tag 取自受保護發布記錄 | 向導使用目前 GitHub CLI 憑證，核對公開標誌、套件資產名稱、大小與摘要；不下載包、不發布 Draft。查無物件或讀取失敗只表示尚無法確認。恢復舊請求或瀏覽轉移時另核對目前歸屬、發布者與緊急封鎖狀態 |

### 公告發布與續簽

遠端內容倉庫的 `Renew announcement index` 每天 03:37 UTC，或在 `master` 手動執行。距到期不超過七天時驗證原簽章與內容，只更新序號和三十天有效期，重新簽署後將索引及簽章一起快進推送；未臨期只檢查狀態。後續查詢目前主線及 Pages 建置，必要時要求建置並等待發布，也可補發已提交但未發布的續簽。

它存取遠端內容倉庫的 Git HTTPS、GitHub Git ref / Pages build API、固定 SHA 的主倉庫原始碼、Actions / JDK 服務及 Maven 依賴源。Git / API 使用本倉庫暫時 `GITHUB_TOKEN`；簽署作業只有 Contents 寫入，發布作業只有 Contents 讀取與 Pages 寫入。官方私鑰來自限制 `master` 的 `announcement-signing` Environment，只在 runner 暫存檔中供本機 CLI 簽署並於退出時刪除，不傳給 Git、API 或依賴源。請求傳送公開原始碼路徑、依賴座標、Git 物件、簽章及提交 SHA。

網路使用 Actions、GitHub CLI、Git、Maven 和 runner 各自的代理、鏡像及重新導向設定。檢查、簽署、發布作業分別限五、三十、十五分鐘；Pages 最多輪詢六十次、每次間隔十秒。停用 workflow 可停止續簽與補發。

### 社群投稿、審核與發布

以下 GitHub API 均以 `api.github.com` 下已核驗倉庫、帳號、PR、Release 或 Actions 物件為目標，除另列限制外，沿用上文的認證、六十秒 / 32 MiB 預算和受控診斷。公開下載不帶 GitHub 憑證。工作流程中的簽署私鑰只在受保護簽署步驟本機使用，不作為 API 或下載內容。

| 操作 | 網路存取與身分 | 執行條件、傳送資料及停止方式 |
| --- | --- | --- |
| 投稿啟動入口 | `irm ... \| iex` 由 PowerShell 下載 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/tools/submit.ps1`；使用 PowerShell 預設網路設定，不附憑證 | 使用者明確執行才下載，不是背景自動更新；停止啟動器可停止後續下載 |
| 固定 SDK 工具下載 | `submit.ps1` 讀取 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/tools/submission-channel.json` 與驗簽通過的完整 commit 下工具檔，再下載指定 SDK Release；公開 HTTPS，不帶 Cookie 或預設憑證 | 通道最多 4 KiB，核對簽章、有效期及已接受序號，執行工具前再次檢查簽章與有效期。PowerShell 內部下載不接受重新導向。每檔案最多三次、退避一秒 / 兩秒、閒置十五秒、總計六十秒。驗證成功才使用固定 SHA 快取；失敗不退回過期快取。使用者可重試或退出。SDK ZIP 經 Node 公網 / TLS / 摘要規則存入 `target/sdk`；工具使用明確版本，不追隨 latest |
| 啟動通道維護 | GitHub Release、artifact 與固定來源 SHA；發布審批後以受保護主線工具讀取候選 | 只接受目前主線祖先的候選，公開通道簽章在本機完成；唯讀 job token 讀取、artifact 保留七天。此流程不推送分支；取消 workflow 停止後續發布 |
| 本機投稿向導 | GitHub 使用者 / 組織 / 倉庫 / Actions / Draft / 資產 API、`uploads.github.com` 上傳 API，以及 fork 的 Git 傳輸 | 核對數字身分、原始碼及凍結 SHA，準備候選與簽章；私鑰不離開本機。觸發或重跑 CI、遠端寫入、緊急管理均有各自明確確認；登入成功或保存進度不代替寫入授權 |
| SDK 候選 CI | Maven Wrapper、固定摘要 Docker 基底、Ubuntu / 建置工具來源、固定 sbt 及 GitHub Draft 資產 | 依目前原始碼 SHA 串行產生候選；一般建置 job 沒有原始碼寫入權限，不建立 tag。候選版本與對應 Draft 綁定，不任取 latest |
| 投稿靜態檢查 | GitHub 唯讀 API；套件與來源匿名 HTTPS，最多五次重新導向、六十秒並核對固定 IP / TLS / 摘要 | 不執行投稿程式。檢查 artifact 保留七天。需要讀取已審核基線 Draft 時，隔離到具 Contents 寫權限的受信任作業，限十五分鐘；交接 artifact 保留一天，不把憑證交給投稿程式 |
| 隔離建置與依賴預取 | Maven / sbt 來自 Central；Gradle 來自 `services.gradle.org/distributions/` 及其 GitHub / CDN，Node.js 來自 `nodejs.org/dist/`，代理工具來自 `archive.ubuntu.com/ubuntu/pool/universe/s/squid/`；Docker 按完整摘要取得 Temurin / Ubuntu Squid 及 registry 認證 / CDN | 執行投稿程式前匿名取得工具並驗證固定 SHA-256；單個建置工具最多 192 MiB、代理工具最多 32 MiB、下載六十秒、最多五次重新導向。依賴代理僅放行 `repo.maven.apache.org`、`repo1.maven.org`、`plugins.gradle.org`、`plugins-artifacts.gradle.org` 的 HTTPS 443 GET / HEAD，逐次核對 CONNECT、TLS SNI 和 HTTP 主機，固定公網 IP 並驗證來源 TLS。建置容器只讀本次暫時 CA 憑證與信任庫，CA 私鑰只掛給代理並於結束時移除，不改 OS 信任。容器沒有外部 DNS、宿主 bridge 出口或共享可寫快取。預取三十分鐘後，以新建斷網容器重建三十分鐘；Docker 自身網路設定不等於投稿容器白名單 |
| 依賴代理驗收 | `self-signed.badssl.com`，及以 `www.cloudflare.com` 為非批准目標的偽裝請求 | 驗證無效憑證與白名單外目的地被拒絕；是 CI 中明確測試，不是應用程式請求 |
| 候選歸檔及清理 | GitHub Draft、asset、執行紀錄和 attestation API | 預檢五分鐘，核對 run / attempt 後才進入十五分鐘特權寫入作業；回讀原 Draft 身分、資產及證明，不以重跑替換來源。關閉請求只清理自己建立的候選，不刪來源 Release。公開讀取與受信任 Draft 讀取分離，交接依不可變 artifact ID |
| 過期候選清理 | GitHub Actions 執行記錄、Draft 和 artifact API | 每天 04:23 UTC、手動或 PR 關閉時執行，按條件清理超過九十天的舊操作；受驗證回執遷移只在本機 Git 操作。作業十五分鐘，不清理正式發布、tag 或仍在使用的候選 |
| 產物證明 | GitHub OIDC / attestation API、`fulcio.sigstore.dev`、`rekor.sigstore.dev`、`tuf-repo.github.com`、`tuf-repo-cdn.sigstore.dev` | 產生或驗證候選證明；驗證 bundle 時清理多餘認證環境，限六十秒。證明不代表投稿審批，也不提供發布權限 |
| 取消請求 | GitHub 原生 PR、帳號和相關請求 API | 只取消目前帳號自己尚未合併的 PR；核對原生事實並回讀結果。網路錯誤先確認是否已成功，避免重複寫入 |
| 投稿分支清理 | GitHub 使用者 / 倉庫 / PR / branch / Rules API，以及目前 fork 或社群所有者本倉庫的 Git HTTPS | 關閉本人請求後核對分支、保護規則及關聯開放 PR；使用者另行確認後，以精確舊 SHA 的 lease 刪除該 ref。主分支、整個 fork 與 Release 保留。回應遺失只回讀，重試刪除須再次確認；拒絕清理即可避免刪除請求 |

#### 原 PR 的審核結果與身分檢查

社群 Gate 遇到 PR head、基線或開閉狀態變化時，重新讀取目前 PR、檔案、審核及簽章證據，完成校驗後才更新檢查。每次出隊最多重新做一次完整版本驗證；驗證期間再次變化就阻斷。既有 Gate 事件或維護者手動執行才會觸發，取消 workflow 可停止後續請求。

已合併結果的發布、主線 Gate 和回執遷移使用 GitHub `git/commits/<SHA>` 及 `git/trees/<SHA>?recursive=1` 讀取實際合併父鏈和完整 Git 樹，以第一父提交和生成 head 計算差異；不再把歷史 PR 的 files API 當成合併內容。開放 PR 審核仍使用原生檔案列表。請求只傳倉庫路徑、提交識別與 GitHub 憑證。

`review-branch.mjs` 以匿名 Git HTTPS 取得已核驗倉庫的固定父提交，產生審核結果，再用維護者 SSH 金鑰快進推送原 PR 分支；同倉分支使用 job token 經 GitHub API 寫入。必須先通過身分、請求和維護者編輯許可檢查，不 checkout 或執行投稿程式。SSH 直連 `github.com:22`，固定 GitHub Ed25519 主機公鑰、指定私鑰、連線十五秒，停用 agent 和互動認證；每個 Git 行程限六十秒、32 MiB 輸出。HTTPS 讀取可使用 runner 的 HTTP 代理；SSH 不繼承 HTTP 代理或使用者 SSH 設定，也不改用其他傳輸。SSH 只傳 Git 物件及認證簽章，不傳私鑰原文。合併另使用僅授權社群倉庫的所有者 PAT。停用相關 workflow 停止寫回；移除 SSH Secret 會明確阻斷 fork 寫入。

| 操作 | 認證與請求 | 完成條件及限制 |
| --- | --- | --- |
| 已簽署管理請求 | `Apply signed owner request` 由靜態檢查完成喚醒，以受保護 `master` 工具讀取 PR、使用者、Environment、Git、Release / asset、check-runs、Actions dispatch 及官方證明服務 | 個人版本狀態請求或新舊金鑰俱全的例行輪換可自動處理；遠端重新驗證簽章、登記與作者，本機配對不代表遠端授權。組織、恢復及遺失 / 洩露後換鑰仍需人工審核。社群根金鑰僅進入 `community-status` 簽署步驟；SSH 追加、所有者 PAT 合併、job token 其他 API 各自分離。最終作業重用 Gate 簽發並再次查核，App 檢查最多等待五秒；佇列與目前身分 / 緊急狀態仍有效 |
| 緊急封鎖聲明 | 受保護主線 `emergency-state` 流程讀取個人 / 組織管理身分、PR、目前登記、Git、檢查及 Release | 使用受保護工具與 SSH / PAT / App 憑證，十五分鐘作業；聲明不設自動失效，不改寫舊 Release。重新檢查開放 PR，撤回已不成立的成功結果，更新中英通知。普通投稿程式不接觸維護憑證 |
| 作業間交接 | GitHub artifact 服務 | 以不可變 artifact ID、run 和 attempt 綁定，最多 32 MiB、保留一天，不含私鑰；不得以同名 artifact 代替同一次可信交接 |
| 撤銷清單續簽 | 每週一 04:23 UTC 或手動讀取目前撤銷狀態、Git ref 與 PR API | 距到期七天內建立維護 PR；job token 僅具所需 Contents / Pull requests / Actions 三項寫權限，簽署 Environment 在後續受保護步驟使用。作業十五分鐘，取消可停止後續寫入 |
| 人工完成審核 | 在 `master` 手動執行 `complete-review`，經 release Environment 核准後讀取精確 PR head、原生身分、審核與緊急狀態 | 生成證明並追加到同一 PR，再按保護規則合併；fork SSH 與所有者 PAT 分工。私鑰經標準輸入供本機簽署。預檢十五分鐘、最終作業三十分鐘、交接 32 MiB；寫入前重新核對權威事實 |
| 社群 Release 發布 | `master` push 或手動執行，讀取已合併原生證明、狀態、Draft、資產與公開下載 | 重讀緊急狀態及產物結果，作業三十分鐘。撤銷套件先歸檔至 `revoked-packages` Draft，再移除原公開套件；不重新建置、不重開 PR、不掃描所有開放請求。取消 workflow 可停止後續處理 |
| 保護與標籤設定 | 維護腳本預覽 GitHub 倉庫、帳號、Rules 與 labels；套用時使用明確倉庫數字 ID 和使用者身分 | 預覽唯讀，套用需明確操作；自動刪除只處理已合併的自身請求分支，不刪 fork，保護預設分支 |
| Gate 及通知 | 原生 PR / Review / comment 事件對應的單一 PR、GitHub Check API、官方 Actions / 工具與 artifact | 不掃描全部 PR。App token 只寫限定 checks，最終重讀證明、身分及緊急狀態；作業採各自最小權限。中英通知更新固定評論，通知內容不授權。Node / Java 設定與一般測試作業只有讀權限 |

#### 所有權轉移通知

接收方建立轉移申請 PR 後，原所有者向導以社群倉庫 `/issues` API 查詢開放 PR，預設同時帶 `labels=type:ownership-transfer` 與 `mentioned=<目前 login>`。使用者可分別或同時取消篩選。只有命中 PR 才讀取固定 head 請求檔，重新核對目前綁定與個人身分 / 組織資格；標籤與提及只是檢索條件，不授予權限，傳送的參數不含金鑰或密碼。

確認後，以原所有者自己的 CLI 憑證向 `/reviews` 提交同意或拒絕。英文 Review 的隱藏標記包含決定類型、請求摘要及使用者選擇提供的活動金鑰簽章；私鑰和密碼不傳送，也不需存取接收方原始碼倉庫。確認 Review 後再向原 PR 的 `/issues/<PR>/comments` 寫入 Review 連結與通知標記。寫入失敗先回讀相同物件，避免重複。

該評論由預設分支 `issue_comment` 喚醒受保護流程，不需核准 fork workflow；普通評論和機器人評論不處理。原生 Review 也可喚醒同一流程。不同個人帳號的雙方簽章有效時，追加結果到原 PR 並按規則合併；組織、恢復或證明不足仍走人工審核。Gate 和合併重新核驗原生身分、Review、目前金鑰及緊急狀態。通知按數字帳號查詢目前 login，更新中英兩條資訊評論；明確拒絕時重新核對綁定、請求與決定，再關閉原 PR。通知使用既有 `pull-requests: write` job token，自動追加與合併使用受保護維護者憑證。不執行向導或停用相關 workflow 即可停止對應請求。

### 應用程式與 SDK 發行驗證

| 流程 | 目的地、資料與認證 | 觸發、驗證及失敗處理 |
| --- | --- | --- |
| Git 與發行腳本 | 目前 `origin`、`https://api.github.com` 或 `GITHUB_API_URL`、GitHub Release | 執行 fetch、遠端 ref 檢查、門禁稽核及插件 / 應用程式發布；使用 Git / GitHub CLI 及 runner 自己的認證與代理設定 |
| Release / Nightly E2E | GitHub Actions Artifact 的上傳、下載及其重新導向；使用當前 job 的執行期憑證 | Java 標準包、full-offline 各驗收；Windows installer 的正常啟動、故障注入、插件缺失與日誌輪轉分成三個 runner，實際安裝及解除安裝。組內有狀態步驟串行，某組失敗不取消其他組，發布等待全部成功。各組保存結果、日誌、視窗截圖與插件狀態七天，不上傳發布私鑰；維護者可調整共享驗收 Action 的證據上傳 |
| 發行目錄及 Nightly 順序 | 插件倉庫本次目錄 commit 下的 raw `manifest.json` / `nightly-manifest.json` 及簽章；主倉庫 `releases/tags/nightly` API | 插件發布作業串行，按精確 commit 回讀簽章目錄並暫存已驗簽插件。Nightly 先讀目前目錄，刪除應用程式舊附件前讀目前 Release 名稱，拒絕舊 run / attempt 覆蓋新發行；只有 404 視為尚無 Nightly，其他錯誤阻斷。API 使用 job token、六十秒 / 32 MiB，下載沿用驗簽與代理規則 |
| Quality Gate 與發行產物交接 | Actions、Artifact、Release、官方 `actions/*` 與 workflow 指定工具下載服務；傳送同一原始碼的構建位元組與摘要 | Java 測試、SDK 合同、ProGuard 三個唯讀 job 並行，冷快取可能各自下載依賴。發布呼叫的 ProGuard job 產生未簽署插件、驗簽工具與編譯目錄，保留一天；完整 QG 成功後以不可變 artifact ID 取得同一 run / SHA 候選，核對參數及每檔摘要。重跑可重用較早 attempt 已通過的候選，錯配即停止。Release / Nightly 各執行一次完整 QG，再並行處理所需 SDK、應用程式依賴及已驗證插件簽署，於當輪交接給 Java 包與安裝器。發布憑證只在 release Environment 使用 |
| SDK Central 發布 | 官方 `org.sonatype.central` Maven 插件所選 Portal API；`https://repo1.maven.org/maven2/io/github/sywyar/pixivdownloader/` | 主程式 Release 或維護者明確發布 / 恢復時觸發；普通 master push、Nightly 不發布 SDK。先讀公開發行與五個 POM，完整已發布者重用；新發行須同一 SHA QG 成功，以 release Environment 的 Central token / PGP 材料發布五個 SDK 座標。插件最多等待 3600 秒至 PUBLISHED，再從公開 Central 下載 POM、JAR、sources、Javadoc、簽章及 SHA-256 做隔離消費者驗證。Portal 最終主機由官方插件及服務決定 |
| SDK 候選交接 | Actions Artifact，使用執行期憑證傳送 artifact ID、原始宿主、未簽署插件、SDK Maven 產物、工具、Javadoc 與摘要 | 保留一天，完整 QG 後按不可變 ID 核對同一 run / SHA、路徑、大小和摘要，再用 release Environment 官方金鑰簽署插件並組裝 SDK。缺失、過期或錯配須重跑完整 workflow；同一 run 可重用較早 attempt。候選不包含發布私鑰、測試金鑰或測試信任設定；一般 QG 不匯出候選，已簽署 Release 恢復不依賴此保留期 |
| SDK 跨倉庫發布與 Javadoc Pages | SDK 倉庫 Release API、`releases/download/` 及其資產 CDN、Pages / Actions deployment | 發布 workflow 的限定跨倉 token 建立不可變 `sdk-api-v*` tag / Release、上傳簽署附件，再公開回讀。Pages 建置不接收該 token，匿名讀取全部非 Draft SDK Release，校驗摘要與中繼資料後生成版本化 Javadoc artifact。SDK 倉庫以固定主倉庫 SHA 呼叫，部署只用自身 Pages 寫權限與 OIDC。dispatch、tag / Release、Pages 設定及部署均須各自授權 |
| SDK 固定執行包 | 清單指定的 `https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/<release-id>/<fixed-runtime.zip>`；只允許重新導向至 `release-assets.githubusercontent.com`、`objects.githubusercontent.com` | 明確 prepare / Run / Debug 且快取缺失才下載；IDE 匯入不下載。JDK URLConnection 使用 JVM 代理，不讀宿主代理，最多五次 HTTPS 重新導向。每次下載、命中快取與執行均驗證大小及 SHA-256，錯誤不換版。預設 `~/.cache/pixivdownloader-sdk/`，可用 `pixivdownload.sdk.cache-dir` 改目錄；有效快取避免下載。請求只含附件路徑和 GitHub 回傳的簽署查詢參數，不傳專案原始碼、插件 JAR、應用程式憑證或發布 token |
| SDK 完整宿主與消費者驗收 | Maven / Gradle 所設依賴服務、Wrapper 下載與重新導向；當輪 SDK 暫存倉庫為本機 file URL | 每次完整 QG 從同一原始碼建置宿主及全部官方插件，不下載預先發布的官方插件包。使用臨時自訂倉庫簽章與本機設定驗收，金鑰隨後刪除，不進發布 job。模板、Javadoc、產物與隔離消費者串行重用當輪結果，Compose 測試接在 ProGuard 後。消費者先連網預取，再用本次精確 SDK 位元組離線重建並驗證第三方插件；QG 無單獨跳過開關，不含發布憑證 |
| SDK 凍結附件與恢復 | 同一 SDK Release、tag 與資產 API、公開 Central、公開 SDK 下載 | Central 前先持久保存簽署 Draft 附件並回讀；五個座標公開且完整宿主驗證成功後才公開同一 Release。重試與恢復重用原宿主、工具及 SDK ZIP，不重傳已發布 Central。Draft 已有而 Central 空白時，可能仍在處理，流程停止要求檢查原 deployment ID；部分座標、附件缺失或衝突也停止，不覆蓋公開資產。發布後以空快取實際下載執行附件；Pages 只下載 SDK / 歷史 Javadoc ZIP 並驗證執行附件中繼資料，不下載所有歷史宿主 |
| FFmpeg 穩定版建置 | `https://ffmpeg.org/download.html`、`/releases/`、`/ffmpeg-devel.asc`，`https://chromium.googlesource.com/webm/libwebp`，Linux / macOS Homebrew 軟體源及 Actions / Artifact / Release | 維護者只在主分支手動觸發；核驗最新穩定 FFmpeg 原始碼和簽章、固定 libwebp commit，產出五平台資產及精確大小 / SHA-256 清單。release Environment 官方私鑰在本機簽署，跨倉 token 更新 Remote Content 的 `ffmpeg-stable` Release；私鑰不傳給下載目標 |
| Windows 本機離線打包 | Remote Content `ffmpeg-stable` 的 `ffmpeg-release.json`、簽章、`ffmpeg-windows-x64.zip` 及 GitHub CDN | `scripts/package-local.ps1` 未傳 `-SkipOfflinePortable` 時以 curl 下載，使用建置機 curl / 代理設定。官方信任根驗簽後核對名稱、大小及摘要，成功才入包；快取重用也先重驗，`-RedownloadFfmpeg` 強制下載。不帶應用程式帳號、Cookie 或發布憑證 |

#### 主倉庫 Gate Checks 的 GitHub 存取

`Gate Checks` 自動處理訂閱的 CI 事件，讀取主倉庫 workflow、run、job、日誌、PR、commit 與 check-runs API，以及 GitHub App 安裝 / token API、目前 origin 和日誌 API 回傳的下載地址。先按 run 的 `workflow_id` 核驗 workflow 中繼資料，以名稱辨識流程；執行標題只用於 PR 關聯。

PR-only 模式只為目前指向 `master` 的開放 PR 處理檢查，核對原生 PR 或執行名稱中的編號、head 倉庫與分支。開發分支 PR 和已關閉 PR 不取得 App token。唯讀 job token 讀證據、Git 取得提交物件；通過後由官方 App action 在 runner 內以 release Environment 私鑰簽 JWT，向 GitHub 換取僅有 `checks:write` 的短期 token。私鑰不傳送，token 在 job 結束時撤銷。

目前六項必需檢查關聯 PR head，原被測合併提交上的檢查保留供合併後複驗，詳情保存被測提交與真實執行連結。GitHub 重建測試合併提交時，只有雙親和完整檔案樹一致才重用原執行，不必為重建物件另寫檢查。每次最多寫兩組；寫入前後重查 PR、雙親、樹與有效執行，變動或寫入失敗時分別嘗試撤銷兩組結果。

合併後核對實際 master；手動全量 QG 的恢復證據也須對應受保護 master 歷史中的同一提交，可由指向該提交的分支或 tag 觸發。這兩條核對路徑不寫 App 檢查。請求傳倉庫、PR / workflow / run / job 編號、SHA 和檢查結論，使用 runner 的 Git / CLI / 代理設定。停用 workflow 或 App 會阻斷 required checks。

### 建置依賴與工具下載

以下依賴缺少快取時才由對應建置 / 維護命令取得，不傳送應用程式使用者資料。代理、鏡像、認證與快取依所用工具設定；明確的特殊條件列在表內。

| 工具或步驟 | 來源 | 觸發及限制 |
| --- | --- | --- |
| Maven / Wrapper | `https://repo.maven.apache.org/maven2`，公開 SDK 複驗另用 `https://repo1.maven.org/maven2` | 下載固定 Maven 3.9.11、Java 依賴及建置插件。Central 發布使用 Wrapper，快取缺失時驗證固定 Maven 發行包；發布後從公開端點核對可解析性與不可變內容 |
| Compose Cupertino / Web iOS 元件 | Maven Central 的 `io/github/robinpcrd/`；維護 Web 資源時使用 `https://registry.npmjs.org/framework7` | Gradle 解析 Cupertino 3.3.1 及傳遞依賴。Framework7 9.1.3 CSS 和授權隨原始碼保存，只有維護者主動更新才存取 npm，普通建置不下載 CSS；已安裝插件本機載入元件 |
| Schema、JCS 和 WebP 依賴 | Maven Central 的 `com/networknt/json-schema-validator/`、`io/github/erdtman/java-json-canonicalization/`、`com/twelvemonkeys/imageio/imageio-webp/` 及傳遞依賴 | 應用程式、Swing、SDK 工具及內部 community-contract 按 POM 固定版本取得。這些不加入五個公共 SDK Maven 元件；解碼與合同驗證在本機，Schema / 目錄隨工具提供，不遠端解析 Schema。備齊後可離線建置 |
| SPDX 授權資源維護 | `api.github.com/repos/spdx/license-list-data/` 的 tag / commit / contents，或 `raw.githubusercontent.com/spdx/license-list-data/<commit>/` | 明確更新才下載，固定 commit、長度及 SHA-256 後納入原始碼。普通建置 / 執行只讀本機，不追隨最新版本。CLI 可用既有 GitHub 登入，raw 下載不需發布憑證；保留現有資源可避免請求 |
| npm / 社群互動元件 | 鎖檔指定的 `https://registry.npmmirror.com` | 安裝 Node 建置及檢查依賴。PowerShell 編排測試缺少 YAML 解析器時會執行 `npm ci --ignore-scripts --no-audit --no-fund`，包含全新 runner。Clack / esbuild 用於維護並打包互動元件；投稿向導執行已校驗 bundle，不連 npm。預裝鎖定依賴可避免自動安裝 |
| SDK Maven / Gradle / sbt 消費者 | Central、Wrapper 發行地址及重新導向；`org/scala-sbt/sbt-launch/1.10.11/sbt-launch-1.10.11.jar` | CI 核對固定 sbt launcher SHA，本機可提供既有 launcher。三工具採隔離快取；Maven 空 settings，Gradle / sbt 第三方依賴固定 Central，SDK 來自本機暫存。Maven / Gradle 連網後離線重建，sbt 線上編譯。本機 `--tool` 可選一項，QG 必須驗證三項；不帶發布憑證 |
| 維護者目錄生成 | 主倉庫 API、contributors、`/users/{login}` 及回傳的 `https://avatars.githubusercontent.com` | 每次應用程式資源建置讀取所有者、貢獻者及本機作者 / 共同作者，與人工真人白名單求交，下載許可頭像並將 JSON / 圖片打包。CI 明確傳入唯讀 GITHUB_TOKEN；本機依 GITHUB_TOKEN、GH_TOKEN、匿名順序。憑證僅給 api.github.com，API / 頭像均拒絕重新導向，頭像不帶憑證。單 API 三十秒；明確限流 / 暫時錯誤最多三次、累計等待最多一百八十秒，遵守服務端等待值，超預算失敗，普通權限錯誤不重試。日誌脫敏；無獨立關閉開關，失敗阻斷建置。發行驗收重用當輪結果，應用程式執行時不再抓取 |
| SDK 發行基線校驗 | SDK Release API、公開 `sdk-release.json` 及 repo1 上五個 SDK POM | Release / 明確 SDK 發布先查目前公共身分；應用程式發行對應公開原始碼，新 SDK 與日常 PR 對照最新公開常規 SDK 的 API / Maven 合同。基準由已核驗來源即時建置；前驅是預發布或不同主版本時，另查不高於候選的同主版本最新正式版。正式前驅可重用當輪結果。失敗阻斷，PR 只讀不發布，Nightly 不參與常規選版或此組查詢。CI GitHub 用唯讀 token、本機沿用 CLI；Node 讀 Central 不帶 GitHub token，不上傳本機來源 |
| Docker | 設定的 OCI registry，預設 `eclipse-temurin:17-jre`；鏡像設定的 Debian 軟體源 | 拉取基底及安裝 FFmpeg、curl 等套件 |
| Windows CI | Chocolatey 設定的軟體源 | 安裝 Inno Setup 等打包工具 |
| SDK 的 Douyin 相容性驗收 | `https://github.com/Sywyar/PixivDownloader-Plugin-Douyin.git` 的固定 commit，以及該工程 Maven 依賴 | `scripts/ci/sdk-consumer.mjs` 取得 `scripts/ci/douyin-source.json` 固定提交，不追隨 main；Git 取得 / checkout 最多三百秒，停用憑證助手和互動提示，使用 Git 代理。接著以待驗 SDK 連網建置、離線重建及執行 Java、JavaScript、產物和宿主整合測試。明確傳 `--douyin-source` 使用本機原始碼可省略 GitHub 請求，Maven 依賴解析仍存在 |

建置機、代理、鏡像或套件管理器可能改寫最終下載主機，僅憑倉庫原始碼無法列出傳遞依賴的固定完整網域集合。

## 鏈接及非請求型 URL

應用和文檔中還包含指向 GitHub、Releases、在線文檔、Tampermonkey 和許可證站點的鏈接。此類鏈接僅在被訪問或由瀏覽器實際加載資源時產生請求。XML 命名空間、POM Schema、許可證正文中的 URL 和示例域名不屬於自動網絡請求目標。

在應用 Web UI 中點擊外部 HTTP(S) 鏈接（包括本地公告/調查 HTML 快照內的鏈接）時，全站確認彈窗會先展示目標地址；只有明確確認後，瀏覽器纔會直接連接該地址，取消則不會產生請求。該瀏覽器導航不經過 Java 後端或全局代理，實際目標由所點擊的鏈接決定；站內同源鏈接保持直接跳轉。
