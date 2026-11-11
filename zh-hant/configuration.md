# 設定參考

日常設定優先在桌面 GUI「組態 / 設定」頁修改並儲存，再依介面提示立即套用或重新啟動。手動編輯設定檔前，先確認設定項目所屬檔案與擁有者：

| 設定類型 | 路徑 | 擁有者 |
| --- | --- | --- |
| 宿主設定與外掛啟停狀態 | `config/config.yaml` | 應用程式宿主 |
| 外掛業務設定 | `config/plugins/{pluginId}.properties` | 對應外掛 |
| 外掛敏感憑證 | `config/credentials/{pluginId}.properties` | 對應外掛（由宿主加密維護） |

首次啟動時會產生預設 `config/config.yaml`，升級時只新增缺少的宿主鍵，不覆寫既有值。手動編輯必須使用 UTF-8 扁平 `key: value` 行，不要將設定項目註解掉以取代空值，也不要將已移至外部外掛的鍵（如 `mail.*`、`push.*`、`notification.*`、`download.novel-*`）寫回 `config.yaml`。

## 憑證金鑰與備份

每個安裝實例都會產生獨立的隨機主金鑰，保護後儲存於 `config/credentials.key`。Windows 預設使用目前使用者的 DPAPI；服務應固定以同一帳號執行。更換 Windows 使用者或電腦前，請先匯出包含憑證且設有密碼的 `.pdbackup`，再於目標環境匯入，僅複製設定目錄不足以還原憑證。

Linux、macOS 與 Docker 必須在首次儲存或升級憑證前提供獨立的保護金鑰檔案。內容為 32 個隨機位元組的標準 Base64 編碼，只允許執行帳號讀取，並放在設定目錄及其備份之外。安裝 OpenSSL 後，可在 Bash 中首次建立檔案並設定啟動環境變數；既有檔案不會被覆寫：

```bash
umask 077
mkdir -p "$HOME/.config/pixivdownload-secrets"
( set -C; openssl rand -base64 32 > "$HOME/.config/pixivdownload-secrets/credentials.key" )
export PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE="$HOME/.config/pixivdownload-secrets/credentials.key"
```

請在同一終端機啟動應用程式；背景服務須在服務環境中設定此變數。Docker 將檔案以唯讀方式掛載至 `/run/secrets/pixivdownload-credentials`，並將變數設為該容器內路徑。也可使用 Java 啟動參數 `-Dpixivdownload.credential-key-file=/絕對路徑`。保護金鑰應另行安全備份，勿在每次啟動時重新產生；金鑰遺失或不符時，應用程式會保留原憑證並拒絕解密。

一般讀寫會自動升級舊版憑證，停用外掛的憑證也會遷移。舊金鑰僅用於舊格式升級；全部檔案遷移完成後拒絕重放舊格式。損壞檔案會保留供修復與重試，其他外掛仍可使用。遷移不會改變既有離線備份的保護方式；跨環境還原使用加密 `.pdbackup`，匯入後以目標安裝金鑰重新加密。

儲存庫的 Compose 範本會自動掛載保護金鑰，宿主環境變數仍填宿主檔案路徑，未設定時會拒絕啟動。Linux 宿主須讓容器帳號 UID/GID `10001:10001` 獨占讀取檔案，例如產生後執行 `sudo chown 10001:10001 "$PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE"` 與 `sudo chmod 400 "$PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE"`；檔案型 Compose secret 沿用宿主權限。

## 設定與環境遷移

Swing 與 Compose 設定頁均提供「設定與環境遷移」，僅讀取已儲存的設定（匯出前請先儲存變更，匯入前先處理未儲存草稿）：

1. **匯出 `.pdbackup`**：預設包含宿主與已註冊外掛設定、外掛開關及桌面偏好設定；可依需求勾選憑證、資料庫、已儲存狀態和使用者資源（收藏夾圖示、小說參考音）。包含憑證、狀態或資料庫時，必須設定封存密碼，匯出期間會暫時停止後端。
2. **預覽與匯入**：在目標環境準備好相容外掛與資料庫連線後，選擇封存檔預覽（未安裝外掛的設定與檔案不會寫入，封存檔不會自動安裝外掛或還原信任授權）。勾選要匯入的欄位、資料庫和檔案群組（資源須與資料庫一同匯入），確認後先備份目標，並在完整處理程序重新啟動後執行遷移。

匯入會保留目標環境的資料庫連線、資料庫 provider 選擇與安裝身分，憑證使用目標主金鑰重新加密；匯入管理員憑證會替換登入帳號並登出舊工作階段；排程工作還原為停用，手動佇列還原為暫停。程式本體、外掛套件、作品檔案與快取不在封存檔內，作品目錄需另行複製（路徑對應僅更新已宣告路徑欄位與資料庫前綴，`{0}` 固定為確認的絕對目錄）。任務備份與各資料庫報告儲存在 `data/environment-transfers/<任務 ID>/`（最新狀態見 `pending.json`）；出現「需要還原」時，應用程式會阻止啟動，請核對報告與備份，勿刪除日誌以強制啟動。

### Windows 安裝目錄搬遷

移動 Windows 安裝目錄後，執行安裝包並選擇新目錄確認遷移，安裝程式會更新登錄中的安裝路徑、捷徑、解除安裝紀錄及既有目前使用者自動啟動項目（不會開啟原本關閉的自動啟動）。修復備份儲存在新目錄 `state/installation-backups/`；確認新位置啟動與資料正常後再清理舊目錄，切勿執行舊目錄的解除安裝程式。舊 MSI 版本需先匯出備份、解除安裝 MSI，再安裝新版並匯入。

## 宿主設定（`config/config.yaml`）

### 服務、下載與並行上限

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `server.port` | `6999` | HTTP / HTTPS 監聽連接埠 |
| `debug.enabled` | `false` | 偵錯模式 |
| `download.root-folder` | `pixiv-download` | 作品產物根目錄（僅存放下載作品） |
| `download.user-flat-folder` | `false` | 畫師批次下載是否省略畫師子目錄 |
| `download.max-concurrent` | `10` | 下載並行上限；另最多排隊 100 件作品，額滿回傳 429 |
| `database.maximum-pool-size` | `28` | 主資料庫連線池上限（最小 `8`） |
| `ffmpeg.max-concurrent` | `2` | FFmpeg 轉碼與媒體探測共用並行數（範圍 `1–8`，重新啟動後端生效） |

固定安全上限（不可透過設定調高）：單張作品圖片 / 封面 / 內嵌圖上限 100 MiB，單次插畫或小說下載任務累計上限 1 GiB；Ugoira ZIP 上限 100 MiB、最多 500 個影格/項目、單項目解壓縮 32 MiB、總解壓縮 200 MiB、單影格 25,000,000 像素，單次轉碼最長 10 分鐘、輸出 100 MiB。服務同時核驗 `Content-Length`、實際解碼串流與圖片檔頭白名單，超限時中止並清理未完成檔案。

### 主資料庫與引擎切換

預設使用 `database-sqlite`（`data/pixiv_download.db`）。如需切換至 PostgreSQL 17 / 18（UTF8），先從外掛市場安裝 `database-postgresql` 並完整重新啟動；主資料庫與已適配外掛私有資料庫共用所選引擎（SQLite 私有資料庫位於 `data/{pluginId}/plugin.db`，PostgreSQL 私有資料庫依 owner 隔離 schema 與受限角色）。

若 GUI 與資料庫同時無法使用，先透過宿主復原視窗進入外掛程式市集修復外掛程式，或在「已安裝」中重新啟用已停用的外掛程式。重新啟動後進入桌面資料庫設定檢查連線；資料庫無法使用時仍可開啟此設定頁。

在桌面「設定 → 執行環境與網路 → 主資料庫」中切換：

1. 選擇已載入的資料庫外掛並填寫連線資訊（密碼留空會保持已儲存值），點擊「測試連線」。
2. 點擊「檢查並繼續」，預先檢查主資料庫與全部外掛私有資料庫的來源、目標及處理方式（非空目標僅在 provider 支援安全備份替換時才允許繼續）。
3. 確認清單後，應用程式會儲存本頁設定並自動完整重新啟動，在新處理程序中完成備份、遷入、逐庫核驗（型別化 SHA-256、列數、生成鍵進度水位與 NUL 字元清理）及原子替換，全部通過後才切換連線設定。僅修正同一資料庫的連線參數時，使用「套用連線並重新啟動」。

遷移紀錄與備份儲存在 `data/database-migrations/`（可能含排程憑證，請妥善保管並保留供啟動複核與重試使用）。所選資料庫外掛缺少、停用或連線失敗時，會進入復原模式（業務 API 回傳 503，背景資料庫任務停止），不會自動改連其他引擎或建立空白 SQLite。

#### PostgreSQL 準備與設定範例

預先建立專用資料庫與主資料庫 schema，授予建表、索引、序列、函式、觸發程序及讀寫權限；使用外掛私有資料庫還需 `CREATEROLE` 與資料庫 `CREATE` 權限，且不得向 `PUBLIC` 開放主資料庫或其他 owner 權限。`currentSchema` 僅支援單一由小寫字母、數字或底線組成的名稱（非數字開頭，最長 63 個字元，預設 `public`）。遠端連線建議使用 `sslmode=verify-full`；密碼放在獨立設定項目或環境變數 `DATABASE_PASSWORD`，禁止寫入 JDBC URL（直接改 `config.yaml` 不會遷移舊資料）：

```yaml
database.provider: database-postgresql
database.engine: postgresql
database.jdbc-url: "jdbc:postgresql://db.example.com:5432/pixiv?currentSchema=pixiv&sslmode=verify-full"
database.username: pixiv_app
database.password: "REPLACE_WITH_DATABASE_PASSWORD"
```

#### 停機 CLI 遷移工具（選用）

需要獨立安排停機並遷移至**空白目標資料庫**時，停止所有使用來源資料庫的處理程序，準備明確包含 `database.provider` 的 `source.yaml` 與 `target.yaml`（SQLite 來源可設定 `database.provider: database-sqlite` 與 `database.jdbc-url: "jdbc:sqlite:data/pixiv_download.db"`），在完整解壓縮目錄執行：

```powershell
java '-Dloader.main=top.sywyar.pixivdownload.migration.DatabaseMigration' -cp '<boot.jar>' org.springframework.boot.loader.launch.PropertiesLauncher --source-config source.yaml --target-config target.yaml --backup migration/source.backup --report migration/report.json
```

- 來源資料庫含小說可重建物件時，明確附加 `--rebuild-objects novels_fts,novel_soft_delete_cleanup`（僅填實際存在的物件）。
- 準備解除安裝但保留資料庫資料的外掛，可在解除安裝前用 `--export-schema schema.json` 匯出宣告，遷移時用 `--retained-schema schema.json` 補入。
- 僅當 `report.json` 狀態為 `DATA_VERIFIED`（且全部 `stores` 為 `COMMITTED`）時，才表示複製核驗完成，隨後再切換生效設定並啟動應用程式。

### 圖片與 Ugoira 儲存格式

在桌面「設定 → 下載」或網頁下載設定中多選儲存格式（至少保留一項，預設值儲存在 `config/plugins/download-workbench.properties` 的 `download-workbench.media.*`）：

- **圖片**：原始格式（預設）、PNG、JPG、WebP。取消原始格式時，僅當所有所選衍生格式產生成功後，才會刪除原圖。
- **Ugoira**：WebP（預設）、GIF、APNG、MP4（需 FFmpeg H.264 編碼器）、原始 ZIP（同時儲存 `.frames.properties` 影格時序，供離線補轉）。
- **轉碼參數**：`quality` 預設 `90`（`1–100`），`webp-lossless` 預設 `false`，`maximum-edge` 預設 `0`（不縮放，可設 `1–16383`）；僅作用於新編碼副本。

既有作品可在桌面「工具 → 歷史媒體處理」依目標格式偵測並補齊缺少的格式與縮圖（單批最多 500 件作品、10,000 個候選檔案）。畫廊依「保留原圖 → WebP → PNG → JPG」順序優先顯示，MP4 使用影片播放器。

### 外掛市場

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | 外掛市場總開關；關閉後不存取任何外掛儲存庫 |
| `plugin-catalog.official-repository-enabled` | `true` | 是否啟用內建官方儲存庫 |
| `plugin-catalog.community-repository-enabled` | `true` | 是否啟用內建社群儲存庫 |
| `plugin-catalog.connect-timeout-ms` | `15000` | 儲存庫連線逾時（毫秒） |
| `plugin-catalog.read-timeout-ms` | `60000` | 儲存庫讀取逾時（毫秒） |
| `plugin-catalog.max-manifest-bytes` | `1048576` | 單一清單大小上限（1 MiB） |
| `plugin-catalog.max-package-bytes` | `201326592` | 單一外掛套件大小上限（192 MiB） |
| `plugin-catalog.repositories` | 空清單 | 自訂外掛儲存庫清單 |

建議透過桌面「設定 → 外掛市場設定」匯入 `repository.json` 描述檔或填寫自訂儲存庫；手動設定範例：

```yaml
plugin-catalog.enabled: true
plugin-catalog.repositories:
  - id: example
    display-name-key: plugin.market.repository.example.name
    manifest-url: https://plugins.example.com/manifest.json
    enabled: true
    proxy-policy: direct-strict
    trusted-keys:
      - key-id: example-2026
        algorithm: Ed25519
        public-key: BASE64_X509_SUBJECT_PUBLIC_KEY_INFO
        state: ACTIVE
        publisher: Example Publisher
        trust-label: Example repository release key
```

儲存庫 `id` 必須唯一，不可使用保留值 `official`、`community` 或 `configured`。`proxy-policy` 支援 `direct-strict`（直接連線、僅限公網 HTTPS、禁止重新導向）、`proxy-trusted`（經由應用程式代理、僅對內建信任主機允許最多 5 跳重新導向並逐跳複核）和 `custom`。

### 出站代理與瀏覽器 PAC

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `proxy.enabled` | `true` | 啟用宿主出站 HTTP 代理 |
| `proxy.host` | `127.0.0.1` | 代理主機 |
| `proxy.port` | `7890` | 代理連接埠 |

若希望瀏覽器存取 `pixiv.net`（如搭配使用者腳本）也經由後端設定的同一代理，但不想開啟全域系統代理，可將系統或瀏覽器的「自動代理設定腳本（PAC）URL」設為 `http://localhost:6999/proxy.pac`（僅本機可存取，隨代理設定熱重載自動更新，僅對 Pixiv 相關網域使用代理）。

### 多人模式配額與訪客限流

`multi-mode.*` 僅限制多人模式下未登入且無邀請碼的**遊客**；持邀請碼的**訪客**在 `solo` / `multi` 模式下皆使用獨立的 `guest-invite.*` 限流：

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `multi-mode.quota.enabled` | `true` | 遊客配額開關 |
| `multi-mode.quota.max-artworks` | `50` | 週期內最大作品配額 |
| `multi-mode.quota.reset-period-hours` | `24` | 配額重設週期（小時） |
| `multi-mode.quota.archive-expire-minutes` | `60` | 打包封存檔到期分鐘數 |
| `multi-mode.quota.limit-image` | `0` | 單件作品超過多少張圖時折算多份配額（`0` 不折算） |
| `multi-mode.quota.max-proxy-requests` | `200` | 代理請求配額 |
| `multi-mode.quota.archive-max-concurrent` | `10` | 封存並行上限 |
| `multi-mode.post-download-mode` | `pack-and-delete` | 可選 `pack-and-delete`、`never-delete`、`timed-delete` |
| `multi-mode.delete-after-hours` | `72` | `timed-delete` 保留小時數 |
| `multi-mode.limit-page` | `3` | 頁數限制（`0` 不限制） |
| `multi-mode.request-limit-minute` | `300` | 遊客每分鐘請求上限 |
| `multi-mode.static-resource-request-limit-minute` | `1200` | 遊客每分鐘靜態資源請求上限 |
| `guest-invite.request-limit-minute` | `300` | 受邀訪客每分鐘請求上限 |
| `guest-invite.static-resource-request-limit-minute` | `1200` | 受邀訪客每分鐘靜態資源上限 |
| `guest-invite.tts-request-limit-minute` | `30` | 受邀訪客每分鐘 TTS 請求上限 |
| `setup.login-rate-limit-minute` | `10` | 登入與邀請碼兌換每分鐘嘗試上限 |

### 維護時段、介面、更新與排程工作

- **維護時段**：`maintenance.enabled` 預設 `true`；`maintenance.monday.enabled=true`，其餘星期預設 `false`，各星期時間鍵 `maintenance.<day>.time` 預設 `10:00`。
- **語言與桌面介面**：`app.language` 預設為空（跟隨系統，僅影響介面，執行日誌固定使用英文）；`app.theme` 預設 `system`（可選 `light`、`dark` 及目前介面的擴充主題）；`app.gui-provider` 預設為空（預設選 Compose，切換需完整重新啟動）；`app.config-menu-expand-all`（預設 `false`）僅作用於 Swing。
- **線上更新**：`update.enabled=true`、`update.auto-check=true`；`update.manifest-url` 與 `update.nightly-manifest-url` 指向官方簽署清單；`update.check-nightly` 在正式版預設 `false`、nightly 版預設 `true`。
- **排程工作宿主**：`schedule.*` 鍵見[排程工作相關設定](/zh-hant/scheduled-tasks#疑難排解與相關設定)。
- **外掛開關**：`plugins.{pluginId}.enabled` 由宿主管理，生效方式取決於外掛的生命週期策略（見[外掛管理](/zh-hant/plugin-management)）。

### HTTPS 與反向代理

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `ssl.domain` | `localhost` | 外部存取網域 |
| `ssl.type` | `pem` | `pem` 或 `jks`（同時設定時 PEM 優先） |
| `server.ssl.enabled` | `false` | 是否啟用內建 HTTPS |
| `server.ssl.certificate` / `server.ssl.certificate-private-key` | 空 | PEM 憑證與私鑰路徑 |
| `server.ssl.key-store-type` / `key-store` / `key-store-password` | `JKS` / 空 / 空 | JKS 憑證庫設定 |
| `ssl.http-redirect` / `ssl.http-redirect-port` | `false` / `80` | HTTP 自動跳轉 HTTPS |
| `server.trusted-proxy-cidrs` | 空 | 受信任反向代理出口 IPv4 / IPv6 CIDR（逗號分隔） |

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

`server.trusted-proxy-cidrs` 留空時會以直接連線模式執行，拒絕任何包含 `Forwarded`、`X-Forwarded-*` 或 `X-Real-IP` 的請求。設定後，只填寫實際連線至後端的反向代理出口或容器網段（嚴禁填 `0.0.0.0/0`、`::/0` 或用戶端網段）；受信任反向代理必須為每個請求提供完整且單一格式的轉送標頭（含 `for`/`proto`/`host` 的 RFC `Forwarded`，或成套 `X-Forwarded-For` + `X-Forwarded-Proto` + `X-Forwarded-Host`），缺少、混用或從受信任 CIDR 不帶標頭直接連線，均會回傳 HTTP 400。

## 外掛設定、憑證與擴充設定

- **外掛業務設定（`config/plugins/{pluginId}.properties`）**：使用 UTF-8 Java properties 格式，由桌面設定頁彙整顯示並統一儲存；禁止覆寫宿主鍵或寫入明文憑證。
- **外掛憑證（`config/credentials/{pluginId}.properties`）**：儲存密碼、Cookie、API Key、Secret 與 Webhook 密鑰，由宿主加密並依 owner 注入外掛上下文。跨環境還原使用包含憑證且設有密碼的 `.pdbackup`；同環境的檔案備份還需保留 `config/credentials.key` 及其解鎖條件。
- **翻譯設定（`translation` 外掛）**：一般項目位於 `config/plugins/translation.properties`（`translation.engine` 預設 `ai`，可選 `youdao`、`libretranslate`；`translation.libretranslate-url` 不含 `/translate`；`translation.use-proxy` 預設 `false`，僅控制有道與 LibreTranslate；`translation.max-concurrent` 預設 `10`），儲存後重新啟動後端生效。詳見[小說與朗讀](/zh-hant/novel#翻譯引擎（管理員）)。
- **登入驗證策略**：`setup.login-verification-policies` 預設為空；可設定最多 16 個逗號分隔的 `LoginVerification.policyId()` 策略鍵，重新啟動後端生效。設定前請確認對應驗證外掛已啟用，詳見[操作驗證碼](/zh-hant/captcha)與[第三方外掛 SDK](/zh-hant/plugin-development#不依賴-gui-的憑證與通用操作驗證)。
