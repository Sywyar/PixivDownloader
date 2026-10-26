# 配置參考

PixivDownloader 的配置按所有權分爲三類，不能混用：

| 配置類型 | 路徑 | 所有者 |
| --- | --- | --- |
| 宿主配置和插件啓用狀態 | `config/config.yaml` | 應用殼 |
| 插件業務配置 | `config/plugins/{pluginId}.properties` | 對應插件 |
| 插件憑據 | `config/credentials/{pluginId}.properties` | 對應插件；由宿主加密存儲 |

推薦在桌面 GUI 的“配置”頁修改。首次啓動時，應用會用當前版本的默認模板創建 `config/config.yaml`；升級後只追加缺失的宿主鍵，不覆蓋已有值。手工編輯時使用 UTF-8，並保留活躍的 `key: value` 行，不要用註釋行代替空值。

## 宿主配置

### 服務、調試和下載

| 鍵 | 默認值 | 說明 |
| --- | --- | --- |
| `server.port` | `6999` | HTTP/HTTPS 服務端口 |
| `debug.enabled` | `false` | 調試模式 |
| `download.root-folder` | `pixiv-download` | 作品產物根目錄 |
| `download.user-flat-folder` | `false` | 畫師目錄是否使用平鋪佈局 |
| `download.max-concurrent` | `10` | 宿主下載併發上限；最多另排隊 100 個作品，隊列已滿時新任務返回 429 |
| `database.maximum-pool-size` | `28` | SQLite 連接池上限 |

`download.root-folder` 只存放下載作品。配置、數據庫、插件狀態和緩存不會寫入該目錄。小說、Douyin 等下載類型的私有設置不屬於宿主配置；安裝插件後由插件自己的配置貢獻管理。

Pixiv 作品圖片、小說封面和內嵌圖的固定安全上限爲每張 100 MiB；單個普通插畫或小說下載任務內的這些響應累計最多 1 GiB。服務同時檢查 `Content-Length` 和實際解碼後的響應流，超過上限會中止傳輸並清理部分文件。最終擴展名根據 URL 路徑、響應 `Content-Type` 與已驗證的文件頭從圖片白名單中選擇，查詢參數不會進入文件名。這些上限不能通過配置提高。

Ugoira 的 ZIP 上限為 100 MiB、最多 500 個項目和影格；單項目解壓最多 32 MiB、總解壓量最多 200 MiB，單影格最多 25,000,000 像素。每個轉碼程序最多執行 10 分鐘、輸出 100 MiB。超限會停止處理並清理暫存檔；已選擇保留的 ZIP 和影格時序檔仍保留。

### 主資料庫與歷史遷移

預設安裝獨立的 SQLite 資料庫外掛，保留 `data/pixiv_download.db` 與舊目錄定位規則。需要 PostgreSQL 17 或 18（UTF8）時，從外掛市場安裝 PostgreSQL 資料庫外掛並完整重新啟動，再選擇連線目標。主庫和已適配的外掛私有儲存使用同一選定引擎；SQLite 私庫位於 `data/{pluginId}/plugin.db`，PostgreSQL 私庫依 owner 使用獨立 schema 和受限帳號。

#### 在設定中切換資料庫

在 Swing 或 Compose 的「設定 → 執行與網路 → 主資料庫」，或外掛市場的資料庫設定中操作：

1. 選擇已載入的資料庫外掛並填寫連線資訊。密碼留白會保留已存值；需要刪除時，勾選清除密碼並儲存。
2. 點擊「測試連線」檢查位址和帳號。通過只表示連線可用，不會儲存設定、建立資料庫或遷移資料。修改後點擊「檢查並繼續」，查看應用資料及全部外掛資料的來源、目標與處理方式。
3. 核對確認清單。目標已有資料且提供者支援安全替換時，清單會說明先備份再替換；不支援的目標會被拒絕。取消可返回修改，保留輸入，且連線設定尚未儲存。
4. 確認後，程式先儲存本頁其他設定，再自動停止業務並完整重新啟動。新程序遷移並核驗清單中的資料，所有資料庫通過後才切換連線設定並繼續啟動。僅修正同一資料庫連線時，使用「套用連線並重新啟動」，不複製資料。

連線詳細資訊顯示正在使用及重新啟動後將使用的資料庫。環境變數或啟動參數覆寫連線設定時，先修正覆寫項目再切換。

替換前會備份舊目標；遷入資料先核驗，再逐庫替換。備份和紀錄位於 `data/database-migrations/` 的對應任務目錄，可能包含排程憑證，應按敏感檔案保管。作品、參考音與憑證檔案保留原位。

失敗時保留原資料庫連線設定，並顯示各項資料的實際結果。已遷入並儲存的資料不會因其他項目失敗而自動撤銷；「結果不確定」不能當作已回復。保留原資料庫和備份，修正提示的問題後重新檢查。重試會核對來源是否改變、目標是否仍匹配，僅重用核驗通過的已遷入資料。

未安裝資料庫外掛、選定外掛缺失／停用／不相容／載入失敗，或啟動時無法連線，都會進入修復模式：業務頁面前往市場，業務 API 回傳 503；直接語義查詢回傳空值並標明資料庫不可用，寫入及依賴資料庫的背景操作停止。恢復外掛或修正連線後，可經檢查和確認流程重新啟動。程式不會自動換用其他引擎或新建空 SQLite。原連線無法解析時，確認清單會明確說明只修正連線、不遷移原資料。

#### PostgreSQL 連線準備

先建立專用資料庫和主庫 schema，授予應用帳號建表、索引、序列、函式、觸發器及資料讀寫權限。使用外掛私庫還需要 `CREATEROLE` 和資料庫上的 `CREATE` 權限，以建立獨立登入角色和 schema。不得向 `PUBLIC` 開放主庫或其他 owner 的資料權限，否則隔離檢查會拒絕私庫連線。

`currentSchema` 只能指定一個由小寫字母、數字或底線組成的名稱，首字元不能是數字，最多 63 字元；省略時使用 `public`。遠端連線建議使用 `sslmode=verify-full`，自訂 CA 使用 `sslrootcert`。JDBC 直接連線至管理員指定的伺服器，不使用 Pixiv HTTP 代理。

以下是扁平鍵範例。有歷史資料時優先使用設定頁的遷移確認流程；直接修改連線不會複製資料。密碼放在獨立設定或 `DATABASE_PASSWORD` 環境變數，不能放入 JDBC URL。

```yaml
database.provider: database-postgresql
database.engine: postgresql
database.jdbc-url: "jdbc:postgresql://db.example.com:5432/pixiv?currentSchema=pixiv&sslmode=verify-full"
database.username: pixiv_app
database.password: "替換為資料庫密碼"
```

#### 管理員選用：停機遷移工具

需要自行安排停機或檢查報告時，可使用 CLI。它不會自動切換應用設定，也不提供設定頁的非空目標備份替換流程。

安裝來源及目標資料庫外掛，停止所有使用來源庫的應用、外掛和離線寫入工具；保留作品、私有庫、參考音、設定和憑證。分別將來源與目標連線設定存成 `source.yaml`、`target.yaml`，保持現行設定不變。在完整解壓目錄執行，將 `<boot.jar>` 換成實際應用 boot JAR 檔名：

`source.yaml` 和 `target.yaml` 都必須明確填寫 `database.provider`。SQLite 來源可設定 `database.provider: database-sqlite` 和 `database.jdbc-url: "jdbc:sqlite:data/pixiv_download.db"`。保留兩份設定，遷移後不要覆寫 `source.yaml`，私庫檢查仍需用它定位原庫。

```powershell
java '-Dloader.main=top.sywyar.pixivdownload.migration.DatabaseMigration' -cp '<boot.jar>' org.springframework.boot.loader.launch.PropertiesLauncher --source-config source.yaml --target-config target.yaml --backup migration/source.backup --report migration/report.json
```

工具取得執行個體鎖，由來源提供者保存一致備份、準備結構與資料，再由目標提供者匯入空目標並核驗。SQLite 備份包含已提交的 WAL 資料，舊結構在工作副本升級。SQLite 與 PostgreSQL 支援雙向遷移，保留 ID、毫秒時間、NULL、軟刪除、中繼資料、媒體和檔案參照，核對逐表筆數、摘要及產生鍵序列。非空目標、未聲明的來源表或不相容型別會使流程停止，不會清空目標或略過未知表。

來源小說庫含可再生 SQLite 物件時，明確加入 `--rebuild-objects novels_fts,novel_soft_delete_cleanup`，只列出實際存在的物件。PostgreSQL 啟動後由提供者依小說外掛聲明重建軟刪除觸發器，正文搜尋讀取遷移後的內容。其他輔助物件須先確認目標可重建；普通歷史表不能用此參數排除。

已安裝但停用的外掛仍提供遷移聲明。準備卸載且保留資料時，可用同一入口的 `--export-schema schema.json` 匯出聲明；遷移時用 `--retained-schema schema.json` 補入缺席 owner，檔案必須匹配目前 SDK。需要執行私有結構升級的外掛仍須安裝。缺少聲明時恢復外掛或補入核對過的聲明，不要刪除歷史表。

工具也會遷移已聲明相容私有 schema 的 owner 資料，未知 owner 或缺少私有聲明會在複製前拒絕。主庫與私庫分別提交，部分失敗不會自動回復已提交庫。儲存資料庫選擇和啟動前，會核對私庫遷移收據及來源變化。第三方自帶 JDBC 或 SQLite 專用 SQL 不會自動相容另一引擎，須由作者適配。

只有 `DATA_VERIFIED` 表示複製與核驗完成。核對報告、待重建物件和各項結果後，才修改生效設定並啟動，驗證畫廊、小說搜尋、通知、排程與檔案存取。`IN_PROGRESS` 或 `FAILED_TARGET_NOT_VERIFIED` 不是成功；保留備份並確認目標交易結果。CLI 重試使用新備份和經確認的空目標，不提供續傳。切換後若目標已有新寫入，直接恢復舊連線會遺漏增量；應透過設定頁重新檢查並遷回，或由管理員停機處理。

#### 遷移備份與核驗細節

連線欄位及類型由資料庫插件宣告：SQLite 不顯示連線欄位，PostgreSQL 顯示位址、使用者名稱和密碼。僅修復無法解析的原連線時，應用會保留原設定快照，私庫遷移核驗仍然有效。

設定頁替換 SQLite 目標時，提供者鎖定舊庫，保留包含已提交 WAL 的一致備份，再遷入暫存庫。全部庫核驗後，各庫在原位置分別透過交易替換內容；替換提交前失敗會保留舊內容，已提交庫不會撤銷。檢查後目標發生變化或含有未知表時拒絕替換。每個任務的 `target-*.backup` 分別保存主庫和各私庫的舊內容。請關閉連線兩端的其它應用及離線寫入工具，保留 `data/database-migrations/` 中的記錄、設定快照和備份；啟動檢查及重試仍會使用它們，設定與備份可能含憑據。

PostgreSQL 來源須符合目前支援的結構。備份是包含資料、序列及受支援物件定義的格式 1 邏輯 XML，不是 `pg_dump` 檔案；目前工具沒有直接還原該封存檔的入口。來源提供者缺席或停用會阻止遷移，不能借用其它驅動。符合 SDK 遷移契約的提供者自行實作匯出及匯入，不需要宿主新增專用遷移分支。

遷移也保留二進位內容，使用類型化 SHA-256 摘要和產生鍵歷史高水位核對資料，並回報參照警告；不會刪除孤立記錄。透過中性儲存介面寫入的文字參數由提供者移除 NUL（U+0000），保留換行、定位字元、其它有效 Unicode、空字串及 NULL；二進位不變，非法編碼仍拒絕。遷移既有文字時只處理暫存工作副本，再核對目標實際內容，來源庫與原始備份保持不變。清理造成唯一鍵衝突或違反約束時失敗，不會自動合併或刪除記錄。

PostgreSQL 來源的 `--rebuild-objects` 應列出實際存在的觸發器及函式名稱，並先確認所屬插件能在目標重建它們。提供者負責私庫探索、隔離、實體映射、SQL 方言及遷移；業務插件宣告結構和業務轉換，透過中性儲存契約讀寫。工具不會自動翻譯第三方插件的任意 SQL。

所有庫複製並核驗後才開始逐庫提交。切換及啟動檢查透過可信來源提供者複核私庫來源變化憑據與精確目標身分；來源設定、來源資料或目標改變時拒絕切換。報告和私庫收據使用 `formatVersion: 1`，交換模型與收據拒絕未知格式。

報告的 `stores` 逐庫記錄 `COMMITTED`、`ROLLED_BACK` 或 `UNKNOWN`。取消或失敗會中止尚未嘗試提交的工作階段，不能將部分提交回報為整體回滾。提交時斷線、程序被強制終止或報告寫入失敗可能留下 `COMMITTING`、`UNKNOWN` 或舊進度，須人工核對目標。失敗後可能留下空資料庫檔案、私庫 schema 或角色，工具不會自動刪除。

`DATA_VERIFIED` 還要求全部提交、收據及資源釋放均已完成。切換前檢查參照警告，保留來源資料及原設定。目標尚無新寫入時，可停機恢復原設定；已有增量時不能直接切回。

### 圖片與 Ugoira 儲存

在桌面「設定 / 配置 → 下載」選擇預設格式、品質、WebP 無損和最長邊，儲存後重新啟動後端生效。格式下拉選單可多選，至少保留一項：

- 圖片：原始格式（預設）、PNG、JPG、WebP。可取消原始格式；所有選定產物成功後才移除原圖，失敗或取消時保留。選擇與原圖相同的格式會直接使用原檔。
- Ugoira：WebP（預設）、GIF、APNG、MP4、原始 ZIP。MP4 需要目前 FFmpeg 提供 H.264 編碼器；保留 ZIP 時同時儲存影格時序，供離線補轉。
- 品質預設 90，可設 1–100；WebP 無損預設關閉；最長邊預設 0（保留尺寸），可設 1–16,383。這些設定只影響新編碼的副本，不重新編碼直接使用的原檔。

兩版網頁下載設定都有格式多選，可用於本次下載及新建排程。預設值儲存在 `config/plugins/download-workbench.properties`，鍵為 `download-workbench.media.image-formats`、`ugoira-formats`、`quality`、`webp-lossless`、`maximum-edge`（後四項也使用相同前綴）。

FFmpeg 共用並行數預設 2，可在桌面下載設定改為 1–8，儲存後重新啟動後端生效。宿主鍵為 `config/config.yaml` 中的 `ffmpeg.max-concurrent`；轉碼與媒體探測共用額度。

既有作品在 Compose 桌面「工具 → FFmpeg 及其工具 → 歷史媒體處理」中選擇目標格式並偵測缺失項目，無須填寫作品 ID。檢視結果後點擊開始，只補缺失格式和縮圖，保留既有檔案；返回不會取消工作。Swing 在工具頁提供相同的偵測與處理。Ugoira 補轉需要保留的 ZIP 與影格時序；FFmpeg 管理中可檢查實際編解碼能力，MP4 需要 H.264 編碼器。

每批最多處理 500 個符合條件的作品、10,000 個候選檔案，同時只執行一項工作；達到上限會提示處理後再次偵測。偵測不產生檔案，修改選項後須重新偵測，修改下載設定不會自動開始歷史處理。

畫廊優先顯示仍保留的原圖，其次為 WebP、PNG、JPG；原圖已移除時使用實際存在的副本，MP4 使用影片播放器。

### 插件市場

| 鍵 | 默認值 | 說明 |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | 插件市場主開關；打開 / 刷新市場或安裝插件時訪問倉庫，關閉後不訪問任何倉庫 |
| `plugin-catalog.official-repository-enabled` | `true` | 是否啓用內嵌官方倉庫 |
| `plugin-catalog.connect-timeout-ms` | `15000` | 全局連接超時 |
| `plugin-catalog.read-timeout-ms` | `60000` | 全局讀取超時 |
| `plugin-catalog.max-manifest-bytes` | `1048576` | 單個清單上限 |
| `plugin-catalog.max-package-bytes` | `104857600` | 單個插件包上限 |
| `plugin-catalog.repositories` | 空列表 | 自定義倉庫列表 |

官方倉庫的地址和信任根內嵌於程序。自定義倉庫必須顯式配置自己的 HTTPS 清單和 Ed25519 公鑰，不繼承官方信任根。推薦用 GUI 的倉庫編輯器；手寫示例：

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

倉庫 id 必須唯一，且不能使用保留值 `official` 或 `configured`。代理策略爲：

- `direct-strict`：直連、僅 HTTPS、拒絕非公網地址和重定向。
- `proxy-trusted`：使用應用代理，只對內置信任主機允許最多五次重定向；每一跳都會重新校驗。
- `custom`：使用條目中的 `allow-redirects`、`strict-https`、`allow-non-public-addresses` 和 `use-proxy`。

倉庫條目還可覆蓋 `connect-timeout-ms`、`read-timeout-ms`、`max-manifest-bytes`、`max-package-bytes`；省略或設爲 `0` 表示繼承全局值。

### 出站代理

| 鍵 | 默認值 | 說明 |
| --- | --- | --- |
| `proxy.enabled` | `true` | 啓用宿主出站 HTTP 代理 |
| `proxy.host` | `127.0.0.1` | 代理主機 |
| `proxy.port` | `7890` | 代理端口 |

插件需要代理時應使用穩定 HTTP/WebSocket SDK 或 `core-api` 的代理語義端口，不應讀取宿主 `ProxyConfig` 實現類。

#### 讓網頁版 Pixiv 走後端配置的代理（無需開啓系統代理）

後端訪問 Pixiv 走配置裏指定的代理（默認 `127.0.0.1:7890`），不依賴系統代理。如果你還希望在瀏覽器裏直接打開 `pixiv.net`（例如配合油猴腳本），又不想爲此開啓 Clash 的「系統代理 / system proxy」，可以使用內置的代理自動配置（PAC）：

在系統或瀏覽器的「自動代理配置腳本（PAC）URL」處填入 `http://localhost:6999/proxy.pac`（端口與你的配置一致；啓用 HTTPS 時爲 `https://<域名>:<端口>/proxy.pac`），即可讓僅 Pixiv 相關域名走後端配置的同一個代理、其餘流量直連。該地址僅本機可訪問，代理變更（含熱重載）會自動反映到 PAC 內容；不再需要來回切換系統代理。

Firefox 的設定入口為 `about:preferences#general`，Windows 的代理設定入口為 `ms-settings:network-proxy`。

### 多人模式配額和限流

| 鍵 | 默認值 |
| --- | --- |
| `multi-mode.quota.enabled` | `true` |
| `multi-mode.quota.max-artworks` | `50` |
| `multi-mode.quota.reset-period-hours` | `24` |
| `multi-mode.quota.archive-expire-minutes` | `60` |
| `multi-mode.quota.limit-image` | `0` |
| `multi-mode.quota.max-proxy-requests` | `200` |
| `multi-mode.quota.archive-max-concurrent` | `10` |
| `multi-mode.post-download-mode` | `pack-and-delete` |
| `multi-mode.delete-after-hours` | `72` |
| `multi-mode.request-limit-minute` | `300` |
| `multi-mode.static-resource-request-limit-minute` | `1200` |

`multi-mode.post-download-mode` 支持 `pack-and-delete`、`never-delete`、`timed-delete`。`multi-mode.limit-page=0` 表示不限制頁數；當前默認值爲 `3`。

邀請訪客在 solo 與 multi 模式都使用獨立限流：

| 鍵 | 默認值 |
| --- | --- |
| `guest-invite.request-limit-minute` | `300` |
| `guest-invite.static-resource-request-limit-minute` | `1200` |
| `guest-invite.tts-request-limit-minute` | `30` |
| `setup.login-rate-limit-minute` | `10` |

### 維護窗口

`maintenance.enabled` 默認 `true`。每日默認值如下：

| 星期 | 啓用鍵/默認值 | 時間鍵/默認值 |
| --- | --- | --- |
| 星期一 | `maintenance.monday.enabled=true` | `maintenance.monday.time=10:00` |
| 星期二 | `maintenance.tuesday.enabled=false` | `maintenance.tuesday.time=10:00` |
| 星期三 | `maintenance.wednesday.enabled=false` | `maintenance.wednesday.time=10:00` |
| 星期四 | `maintenance.thursday.enabled=false` | `maintenance.thursday.time=10:00` |
| 星期五 | `maintenance.friday.enabled=false` | `maintenance.friday.time=10:00` |
| 星期六 | `maintenance.saturday.enabled=false` | `maintenance.saturday.time=10:00` |
| 星期日 | `maintenance.sunday.enabled=false` | `maintenance.sunday.time=10:00` |

### HTTPS 與反向代理

| 鍵 | 默認值 |
| --- | --- |
| `ssl.domain` | `localhost` |
| `ssl.type` | `pem` |
| `server.ssl.enabled` | `false` |
| `server.ssl.certificate` | 空 |
| `server.ssl.certificate-private-key` | 空 |
| `server.ssl.key-store-type` | `JKS` |
| `server.ssl.key-store` | 空 |
| `server.ssl.key-store-password` | 空 |
| `server.trusted-proxy-cidrs` | 空 |
| `ssl.http-redirect` | `false` |
| `ssl.http-redirect-port` | `80` |

`ssl.type=pem` 使用證書和私鑰路徑；`ssl.type=jks` 使用 key store。不要把證書私鑰或 key store 密碼提交到倉庫。

`server.trusted-proxy-cidrs` 是使用反向代理時的信任邊界。它只接受逗號分隔的數字 IPv4/IPv6 CIDR，例如：

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

只填寫實際連接後端的反向代理出口地址或容器網段，不要填寫客戶端網段，也不要信任 `0.0.0.0/0` 或 `::/0`。留空時應用按直連模式運行，並拒絕任何 `Forwarded`、`X-Forwarded-*` 或 `X-Real-IP` 請求頭。

受信代理必須爲每個請求提供以下一種完整格式，不能混用：

- RFC `Forwarded`：所選代理邊界必須同時包含 `for`、`proto` 和 `host`；
- 傳統格式：`X-Forwarded-For`、`X-Forwarded-Proto`、`X-Forwarded-Host`，可選 `X-Forwarded-Port`。

應用從代理鏈右側向左查找首個非受信地址作爲客戶端地址，並在鑑權、限流和 CSRF 同源判斷前統一規範化客戶端地址、外部協議、主機與端口。未受信來源提供轉發頭，代理鏈沒有非受信客戶端地址，或受信代理缺少、混用、錯位、僞造格式的代理元數據時，請求會返回 400。代理必須覆蓋所有到後端的請求；如果把 `127.0.0.1/32` 設爲受信代理，同一地址發起但沒有代理頭的直連請求也會被拒絕。

### 語言與桌面界面

| 鍵 | 默認值 | 說明 |
| --- | --- | --- |
| `app.language` | 空 | 跟隨系統；也可使用受支持語言代碼 |
| `app.theme` | `system` | GUI 主題 id |
| `app.config-menu-expand-all` | `false` | 只控制 Swing 的設定選單展開；Compose 不提供此選項，也不改寫此偏好 |
| `app.gui-provider` | 空 | 桌面 GUI provider id；只能選擇當前活動插件提供的值 |

兩個桌面介面都提供 `system`、`light`、`dark`，額外佈景主題由目前選擇的介面提供。GUI provider 下拉選單來自目前啟用的 `DesktopUiProvider`。發行套件預設包含 Compose 和 Swing，首次未指定時選擇 Compose；切換介面需要完整重新啟動。

語言和佈景主題可立即預覽，按下設定頁共用的儲存按鈕後才會記住選擇。語言設定只影響介面，日誌使用英文。

### 在線更新

| 鍵 | 默認值 |
| --- | --- |
| `update.enabled` | `true` |
| `update.manifest-url` | 官方最新正式版 `update.json` |
| `update.nightly-manifest-url` | 官方 nightly `update.json` |
| `update.auto-check` | `true` |
| `update.check-nightly` | nightly 構建爲 `true`，正式版爲 `false` |

### 計劃任務宿主

| 鍵 | 默認值 |
| --- | --- |
| `schedule.enabled` | `true` |
| `schedule.tick-interval-ms` | `60000` |
| `schedule.max-tasks` | `100` |
| `schedule.inbox-check-every` | `500` |
| `schedule.auth-failure-circuit-breaker` | `5` |
| `schedule.pending-max-attempts` | `5` |
| `schedule.overuse-defer-default-minutes` | `60` |

這些鍵配置中性的計劃任務宿主。具體下載來源、認證信息和下載參數由相應插件貢獻並擁有。

### 插件啓用狀態

`plugins.{pluginId}.enabled` 由宿主管理，例如：

```yaml
plugins.douyin.enabled: true
```

required 插件不能通過該鍵禁用。是否能即時生效由插件的 `pixiv.lifecycle-policy` 與當前生命週期操作決定，詳見[插件管理](/zh-hant/plugin-management)。

## 插件業務配置

每個插件只寫 `config/plugins/{pluginId}.properties`。文件使用 UTF-8 Java properties 語法：

```properties
example.timeout-ms=15000
example.output-format=json
```

宿主會拒絕插件配置文件覆蓋宿主默認鍵、`plugins.*.enabled` 或看起來像憑據的鍵。不同插件文件中的鍵也不應重複。插件子 Spring 上下文通過 `Environment`、`@Value` 或 `@ConfigurationProperties` 讀取這些值；第三方插件不應直接讀配置文件，也不應依賴應用殼的配置類。

插件的 `GuiConfigContribution` 是其完整配置 section 的事實來源，可聲明字段、分組、佈局、動作、預設和聯動。應用殼把它與核心配置合併爲一份工具包無關的桌面文檔並負責統一保存；Swing / Compose 只渲染，不各自維護插件頁面。保存後，宿主會刷新插件配置源，並按字段與插件生命週期策略給出即時生效、後端重啓或完整進程重啓結果。手工編輯後不確定時，完整重啓最穩妥。

## 插件憑據

密碼、Cookie、Token、API key、secret 和 webhook key 等敏感值寫入 `config/credentials/{pluginId}.properties`。宿主負責加密、權限、遷移和按 owner 注入；插件只從自己的子上下文 `Environment` 讀取已經解密的屬性值。

不要把憑據放入 `config.yaml` 或 `config/plugins/*.properties`，也不要讓插件讀取、解析或解密憑據文件。備份憑據時必須同時保護憑據主密鑰；缺少原主密鑰時，加密值不能在另一環境中恢復。

## 如何確認當前配置

1. 先看 GUI 配置頁；它會合並宿主字段與當前已安裝插件的配置貢獻。
2. 宿主默認鍵以當前版本的 `DefaultConfigTemplate` 生成結果爲準。
3. 插件字段以對應插件的 `GuiConfigContribution`、`@ConfigurationProperties` 或設置服務爲準。
4. 不要從舊版示例複製已經外置的 `mail.*`、`push.*`、`notification.*` 或 `download.novel-*` 到 `config.yaml`；它們由各自插件擁有。
