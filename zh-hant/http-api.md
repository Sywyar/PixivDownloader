# HTTP API 與程式權限

外部程式可使用 `Authorization: Bearer <程式金鑰>`。在安全頁面的「程式存取金鑰」建立金鑰，指定名稱、權限及允許的來源 IP。完整金鑰僅顯示一次，伺服器只儲存摘要。金鑰預設不過期，可編輯權限及來源範圍，也可撤銷。選取整個外掛只包含目前列出的權限，不包含未來新增權限。

來源限制接受數值 IPv4、IPv6 及 CIDR 網段。受限模式至少填寫一項；「任何來源」需要明確選擇。使用反向代理前先設定可信代理。不要同時傳送程式金鑰、管理員工作階段、邀請碼或 GUI 憑證。擷取憑證仍透過獨立的 `X-Acquisition-Credential` 傳遞。

管理員工作階段仍可透過 `X-Session-Token` 使用原有介面。程式金鑰只能存取權限目錄明確列出的方法及路徑，其他介面保留人類管理員或本機流程。金鑰管理及管理員憑證修改僅允許人類管理員。此 API 不提供 MCP 服務。

使用 Cookie 的寫入請求須通過同源檢查；瀏覽器跨來源請求會被拒絕，外掛管理與計畫任務等入口還要求明確的同源訊號。`/api/gui/**` 保留本機與 GUI token 限制，遠端程式使用下列業務介面。外掛須處於活動狀態且依賴可用。

## 介面索引

| 功能 | 入口 |
| --- | --- |
| 設定欄位、預設值與脫敏目前值 | GET `/api/settings`；GET `/api/settings/presets` |
| 驗證與儲存設定 | POST `/api/settings/validate`；POST `/api/settings` |
| 外掛儲存庫設定 | GET / PUT `/api/settings/repositories` |
| 資料庫設定與連線測試 | GET `/api/settings/database`、`/providers`、`/status`；POST `/check` |
| 資料庫遷移 | POST `/api/settings/database/migration-previews`、`/migrations`；GET `/migration` |
| 環境封存 | POST `/api/settings/environment/preview`、`/restore`、`/exports`；GET `/restore`、`/exports`、`/exports/{id}/archive`、`/installation` |
| 中繼資料回填 | GET `/api/tools/backfill/defaults`、`/tasks/current`、`/tasks/{id}`；POST `/candidates`、`/tasks`、`/tasks/{id}/cancel` |
| 媒體維護與記錄檔匯入 | GET `/api/tools/media`、`/api/tools/file-import`；各入口下 POST `/previews`、`/tasks`、`/tasks/cancel`，GET `/tasks/current` |
| 媒體能力與匯入完成確認 | POST `/api/tools/media/capabilities`；POST `/api/tools/file-import/completion/acknowledge` |
| 工具歷史與日誌附件 | GET `/api/tools/history`、`/api/tools/logs/{fileName}` |
| 目錄參照檢查與修正 | GET / POST `/api/tools/directories` |
| 路徑前綴 | `/api/tools/path-prefixes` |
| 圖片分類 | `/api/tools/classifier/settings`、`/folders`、`/images`、`/image`、`/moves` |
| FFmpeg 狀態與安裝 | GET `/api/tools/ffmpeg`；POST `/api/tools/ffmpeg/tasks` |
| 系統與控制中心 | `/api/admin/system`、`/api/admin/control-center` |
| AI、郵件與推播測試 | `/api/admin/ai/test`、`/models`；`/api/admin/mail/test`、`/test-all`；`/api/admin/push/test`、`/test-all` |
| 軟體更新 | `/api/admin/update/check`、`/last`、`/download`、`/download/progress`、`/install` |
| 單項下載與歷史 | `/api/download/tasks`、`/api/download/tasks/history` |
| 背景手動批次 | `/api/download/batches` |

同一列的簡寫路徑使用該 API 功能的共同前綴。翻譯已有 `/api/translation/**`；術語表、朗讀、畫廊、收藏夾、計畫、維護與外掛市場沿用各自介面。桌面視窗、檔案選擇器與開啟本機瀏覽器屬於用戶端操作。

## 儲存設定

先讀取欄位目錄，再按傳回的 `owner`、`packageId`、`generation` 與 `key` 提交變更。宿主欄位的 owner/packageId 為 null、generation 為 0。敏感欄位只回傳 `stored` 標記，不回傳明文或預設秘密；以 `KEEP`、`SET`、`CLEAR` 保留、替換或清除。預設是欄位值集合，使用相同儲存介面套用。`effect` 表示生效方式，儲存不代表後端或程序已重新啟動。

POST `/api/settings/validate` 或 `/api/settings` 的範例本文：

```json
{
  "changes": [
    {
      "owner": null,
      "packageId": null,
      "generation": 0,
      "key": "server.port",
      "action": "SET",
      "value": "6999"
    }
  ]
}
```

儲存庫編輯須帶上 GET 傳回的 `revision`；新增儲存庫或變更信任、位址與網路策略時須傳 `trustConfirmed:true`。修訂衝突時重新讀取，不覆寫其他編輯。連線測試與工具暫時參數不儲存設定。

## 背景手動批次

1. GET `/api/schedule/sources` 取得目前的 `sourceType` 與 `activationToken`，定義格式由來源外掛解釋。
2. POST `/api/download/batches` 提交 `name`、`sourceType`、`activationToken`、`definitionJson` 與可選 `proxy`。`definitionJson` 是 JSON 字串，上限為 1 MiB UTF-8；整個請求上限為 8 MiB。
3. 以傳回的 UUID 查詢 GET `/api/download/batches/{id}` 與 `/{id}/items?offset=0&limit=100`。逐項身分是批次 UUID 加 `workType` 與 `id`，不是單項下載的執行 UUID。
4. POST `/{id}/pause`、`/cancel`、`/resume`、`/retry` 分別暫停、取消、恢復掃描或只重試失敗項。停止中的狀態須等實際執行退出後才終結。重試已發現的失敗項不會補掃尚未完成的來源。
5. 閒置時 DELETE `/{id}` 移除記錄。一個程序只保留一個批次，建立新批次前須刪除舊批次；結果不跨程序恢復。

`explicit-list` 支援混合插畫與小說。將下列物件序列化成 `definitionJson`；作品 ID 須為正整數十進位字串，同類型不得重複：

```json
{
  "kind": "mixed",
  "source": {
    "acquisitionMode": "single-import",
    "works": [
      {
        "kind": "illust",
        "id": "123"
      },
      {
        "kind": "novel",
        "id": "456"
      }
    ]
  },
  "filters": {
    "content": "safe",
    "aiFilter": "exclude",
    "tagsExact": [],
    "tagsFuzzy": []
  },
  "download": {
    "concurrent": 1,
    "intervalMs": 1000,
    "bookmark": false
  }
}
```

範例作品 ID 請替換為目標作品。其他來源包含作者投稿、約稿、搜尋、系列、收藏、關注新作與珍藏集。篩選支援分級、AI、標籤、插畫類型、頁數、小說字數與收藏數；啟用收藏數篩選時，未知收藏數不能通過。恢復時不重複執行已完成或因篩選略過的作品。

批次最多 5,000 項，編碼內容預留預算為 64 MiB，先達到的限制生效；這不是 JVM 堆積記憶體保證。每頁最多 200 項。PUT `/{id}/credentials` 以 `{"expectedRun":1}` 替換請求標頭中的憑據，未提供標頭表示清除；只允許目前輪次已閒置的批次，恢復前重新探測憑據。POST `/{id}/resolve` 包含 `expectedRun`、`workType`、`workId`、`reasonCode`、`userAction`、`rememberForRun`；原因與輪次須符合待處理記錄。兩種請求本文上限均為 16 KiB。

## 工具與重新啟動操作

媒體維護與記錄檔匯入須先取清單，使用精確 `identity`（pluginId/packageId/generation/publication）和 `options` 預覽；啟動須帶相同身分與預覽 `token`，取消還須帶 `executionId`。外掛換代或桌面啟動替代任務後，舊 token/UUID 不會作用於新任務。路徑指伺服器檔案系統，不是遠端用戶端的本機路徑。

回填 POST `/api/tools/backfill/tasks` 範例：

```json
{
  "delayMs": 0,
  "limit": 100,
  "dryRun": true,
  "alwaysNetwork": false
}
```

`limit:0` 表示全部候選，本文最多 4 KiB。202 只表示接納，須繼續查詢執行狀態。分類移動分別回傳複製、刪除來源與資料庫登記結果；部分完成後先檢查結果，不盲目重試移動。

資料庫遷移以預覽 token 確認。環境恢復傳入預覽 token、選取欄位、路徑映射與密碼，`RESTORE_PENDING` 表示由替代程序執行。匯出須給伺服器絕對 `destination` 與 `confirmRestart:true`，可選 `password`、`includeCredentials`、`includeDatabases`、`includeState`、`includeResources`。資源須同時包含資料庫；憑據、資料庫或狀態須設密碼。已有目標不會覆寫。後端停止、匯出後請求完整重新啟動，用戶端重新連線查詢；重新啟動失敗時須手動啟動應用程式。更新安裝須提交目前已驗證安裝包的 SHA-256。

## 錯誤與歷史

錯誤包含機器 `code` 與本地化 `error`。400 為非法參數，401/403 為身分或權限拒絕，404 為身分不存在，409 為忙碌、衝突或狀態不允許，413 為本文超限，503 為能力不可用。按業務狀態處理，不將所有非 2xx 自動重試。

GET `/api/download/tasks/history` 預設每頁 50 項、最多 200 項，以傳回的 `beforeTime` 與 `beforeId` 一起續頁。只列出已儲存終態，不恢復在途下載。單項下載去重限於目前程序的保留期間，查不到執行不代表可安全重投。工具歷史與批次結果各有保留規則。
## 程式權限清單

管理員可呼叫 GET `/api/security/program-keys/permissions` 取得目前安裝的權威目錄。下表列出內建宣告，外部外掛以目前目錄為準。`*` 表示一個路徑段。金鑰只儲存明確權限，不支援外掛或路徑萬用授權。

共用設定、媒體工具及檔案匯入同時要求 `core` 入口權限和目標外掛的同名權限，目錄會依權限篩選目標。單項下載需要 `core:downloads.create` 及實際下載類型所屬外掛的 `downloads.create`。批次需要 `core:batches.create` 或 `core:batches.resume`，以及來源與完整執行計畫中每個作品外掛的 `batches.execute`。來源目錄使用 `core:schedule.sources.read`。

程式只能查詢、取消及管理自己建立的下載任務和批次；管理員仍可管理整個安裝。每個程序仍共用一個批次，不會因金鑰增加而增加批次名額。翻譯、工具、設定、日誌、資料庫及環境權限作用於對應外掛或整個安裝，不是各金鑰的私有副本。資料庫遷移與環境封存包含整套安裝中選定的內容。

金鑰管理僅供管理員工作階段：GET `/api/security/program-keys` 取得清單、GET `/permissions` 取得目錄、POST 根路徑建立、PUT `/{id}` 編輯、DELETE `/{id}` 撤銷。建立內容為 `name`、`permissions`、`anyIp`、`networks`；編輯使用 `{"revision":1,"policy":{...}}`，撤銷使用 `{"revision":1}`。請使用讀取時取得的修訂號；衝突後重新讀取，不覆蓋他人的修改。建立成功回傳 `key` 與一次性的 `secret`；清單不回傳秘密或摘要。

每個安裝最多 128 個金鑰，每個名稱最多 80 個字元、512 項權限及 64 個來源網段；管理寫入內容最多 128 KiB。最近使用時間只記錄目前程序觀察到的成功驗證。撤銷會阻止新請求，不會自動取消已接納的任務。

啟動快取使用 GET `/api/tools/startup-cache`、POST `/api/tools/startup-cache/tasks` 及 POST `/api/tools/startup-cache/tasks/{id}/cancel`；開始內容為 `{}`。接納不代表訓練成功，請繼續查詢狀態。

### 權限範圍與專用入口

排程任務使用 `core:schedule.*` 中對應動作的精確權限，並要求來源外掛的 `schedule.manage`；帳號策略動作要求策略 owner 的同名權限。清單依來源授權篩選。此處的 `*` 只是文件分組，不是可儲存的權限。排程屬於安裝的持久設定，授權包含該來源的完整排程業務；撤銷建立金鑰不會刪除或停用排程。需要取消後續執行時，請先停用或刪除排程。

外掛生命週期、啟用狀態及信任操作同時要求對應的 `core:plugins.*` 動作與目標的 `<plugin>:plugins.manage`；存在依賴消費者時，也檢查消費者授權。已安裝但尚未載入的可管理外掛也會列於目標目錄。安裝權限允許套件及其依賴在伺服器執行程式碼；全域 Hook 發布權限允許修改安全策略，請依此實際影響授權。

共用憑證分為 `core:credentials.read`、`core:credentials.write` 及 `core:credentials.use`。讀取只回傳資訊，不回傳秘密；使用 `X-Acquisition-Credentials` 引用已儲存憑證時，除了業務權限，還需要 `credentials.use`。直接提供當次取得憑證仍使用 `X-Acquisition-Credential`。

圖庫、小說庫、收藏夾、站內信、封存、頁面編輯、更新及維護權限作用於共用安裝，不會自動限制為該金鑰建立的資料。下載任務與手動批次仍依金鑰身分隔離。本機維護、復原及舊中繼資料遷移仍要求本機來源；權限不會取代來源、確認、修訂或租約檢查。

身分建立與金鑰管理、GUI token、驗證碼、瀏覽器擷取票據、匿名配額及頁面組裝保留專用流程。程式下載與進度使用 `/api/download/tasks`，檔案匯入使用 `/api/tools/file-import`，作品管理使用 `/api/library/artworks/{id}`，頁面業務使用 `/api/page-editor`。站內信測試使用 `/api/admin/notifications/test` 或 `/test-all`。舊瀏覽器相容路由不另授予程式權限。

| 權限 | 用途 | 方法與路徑 |
| --- | --- | --- |
| `ai:connection.test` | 測試 AI 連線（會呼叫模型） | POST `/api/admin/ai/test` |
| `ai:models.read` | 查詢可用 AI 模型 | POST `/api/admin/ai/models` |
| `ai:status.read` | 檢視 AI 設定狀態 | GET `/api/admin/ai/status` |
| `core:application.read` | 讀取程式資訊與介面語言資源 | GET `/api/app/info`; GET `/api/i18n/messages/*`; GET `/api/i18n/meta` |
| `core:archives.create` | 建立插畫匯出封存檔 | POST `/api/archive/pack-artworks` |
| `core:archives.download` | 下載安裝實例的匯出封存檔 | GET `/api/archive/download/*` |
| `core:archives.read` | 檢視安裝實例的匯出封存檔與狀態 | GET `/api/archive/list`; GET `/api/archive/status/*` |
| `core:assets.export` | 下載插畫原始檔案 | GET `/api/downloaded/rawfile/*/*` |
| `core:assets.read` | 讀取插畫預覽與圖片 | GET `/api/downloaded/image/*/*`; GET `/api/downloaded/thumbnail-file/*/*`; GET `/api/downloaded/thumbnail/*/*` |
| `core:authors.read` | 檢視全庫作者 | GET `/api/authors/paged`; GET `/api/authors` |
| `core:authors.refresh` | 重新整理 Pixiv 作者資料 | POST `/api/pixiv/user/*/meta/schedule` |
| `core:backfill.cancel` | 取消記錄回填 | POST `/api/tools/backfill/tasks/*/cancel` |
| `core:backfill.execute` | 執行記錄回填 | POST `/api/tools/backfill/tasks` |
| `core:backfill.read` | 查看記錄回填候選與狀態 | GET `/api/tools/backfill/defaults`; GET `/api/tools/backfill/tasks/*`; GET `/api/tools/backfill/tasks/current`; POST `/api/tools/backfill/candidates` |
| `core:batches.cancel` | 取消此金鑰的批次 | POST `/api/download/batches/*/cancel` |
| `core:batches.create` | 建立背景批次 | POST `/api/download/batches` |
| `core:batches.credentials` | 替換此金鑰的批次憑證 | PUT `/api/download/batches/*/credentials` |
| `core:batches.delete` | 刪除此金鑰的批次記錄 | DELETE `/api/download/batches/*` |
| `core:batches.pause` | 暫停此金鑰的批次 | POST `/api/download/batches/*/pause` |
| `core:batches.read` | 查看此金鑰的批次與結果 | GET `/api/download/batches/*/items`; GET `/api/download/batches/*`; GET `/api/download/batches` |
| `core:batches.resolve` | 處理此金鑰批次的待確認項 | POST `/api/download/batches/*/resolve` |
| `core:batches.resume` | 繼續或重試此金鑰的批次 | POST `/api/download/batches/*/resume`; POST `/api/download/batches/*/retry` |
| `core:classifier.configure` | 修改圖片分類設定 | PUT `/api/tools/classifier/settings` |
| `core:classifier.delete` | 刪除圖片分類目錄 | DELETE `/api/tools/classifier/folders` |
| `core:classifier.read` | 查看圖片分類與圖片 | GET `/api/tools/classifier/folders`; GET `/api/tools/classifier/image`; GET `/api/tools/classifier/images`; GET `/api/tools/classifier/settings` |
| `core:classifier.write` | 移動分類圖片 | POST `/api/tools/classifier/moves` |
| `core:collections.delete` | 刪除精選集 | DELETE `/api/collections/*` |
| `core:collections.members.write` | 新增及移除精選集作品 | DELETE `/api/collections/*/artworks/*`; DELETE `/api/collections/*/novels/*`; POST `/api/collections/*/artworks/*`; POST `/api/collections/*/novels/*` |
| `core:collections.read` | 讀取全庫精選集與歸屬 | GET `/api/collections/*/icon`; GET `/api/collections/novels/of/*`; GET `/api/collections/of/*`; GET `/api/collections`; POST `/api/collections/memberships`; POST `/api/collections/novels/memberships` |
| `core:collections.write` | 建立及修改精選集、圖示與下載目錄 | DELETE `/api/collections/*/icon`; POST `/api/collections/*/icon`; POST `/api/collections`; PUT `/api/collections/*/download-root`; PUT `/api/collections/*/sort-order`; PUT `/api/collections/*` |
| `core:control-center.directory` | 在伺服器上開啟控制中心目錄 | POST `/api/admin/control-center/directory` |
| `core:control-center.read` | 檢視控制中心狀態 | GET `/api/admin/control-center` |
| `core:credentials.read` | 讀取擷取憑證庫資訊（不含秘密） | GET `/api/acquisition/credentials` |
| `core:credentials.use` | 在已授權請求中使用共用擷取憑證 | HEADER `X-Acquisition-Credentials` |
| `core:credentials.write` | 修改共用擷取憑證庫 | PUT `/api/acquisition/credentials` |
| `core:database.check` | 檢查資料庫連線 | POST `/api/settings/database/check` |
| `core:database.migrate` | 遷移整套安裝的資料庫 | POST `/api/settings/database/migrations` |
| `core:database.preview` | 預覽整套安裝的資料庫遷移 | POST `/api/settings/database/migration-previews` |
| `core:database.read` | 查看資料庫設定與遷移狀態 | GET `/api/settings/database/migration`; GET `/api/settings/database/providers`; GET `/api/settings/database/status`; GET `/api/settings/database` |
| `core:directories.read` | 檢查作品目錄 | GET `/api/tools/directories` |
| `core:directories.write` | 修改作品目錄記錄 | POST `/api/tools/directories` |
| `core:downloads.cancel` | 取消此金鑰的下載任務 | POST `/api/download/queue/*/cancel`; POST `/api/download/queue/clear`; POST `/api/download/tasks/*/cancel` |
| `core:downloads.create` | 建立下載任務 | POST `/api/download/tasks` |
| `core:downloads.extensions.read` | 檢視下載擴充能力 | GET `/api/download/extensions` |
| `core:downloads.read` | 查看此金鑰的下載任務 | GET `/api/download/tasks/*`; GET `/api/download/tasks/history`; GET `/api/download/tasks` |
| `core:environment.export` | 匯出完整環境封存檔 | GET `/api/settings/environment/exports/*/archive`; POST `/api/settings/environment/exports` |
| `core:environment.preview` | 預覽完整環境還原 | POST `/api/settings/environment/preview` |
| `core:environment.read` | 查看安裝與環境遷移狀態 | GET `/api/settings/environment/exports`; GET `/api/settings/environment/installation`; GET `/api/settings/environment/restore` |
| `core:environment.restore` | 還原完整環境 | POST `/api/settings/environment/restore` |
| `core:ffmpeg.install` | 安裝 FFmpeg | POST `/api/tools/ffmpeg/tasks` |
| `core:ffmpeg.read` | 查看 FFmpeg 狀態 | GET `/api/tools/ffmpeg` |
| `core:file-import.acknowledge` | 確認檔案匯入結果 | POST `/api/tools/file-import/completion/acknowledge` |
| `core:file-import.cancel` | 取消檔案匯入 | POST `/api/tools/file-import/tasks/cancel` |
| `core:file-import.execute` | 執行檔案匯入 | POST `/api/tools/file-import/tasks` |
| `core:file-import.preview` | 預覽檔案匯入 | POST `/api/tools/file-import/previews` |
| `core:file-import.read` | 查看檔案匯入工具與任務 | GET `/api/tools/file-import/tasks/current`; GET `/api/tools/file-import` |
| `core:hooks.prepare` | 預覽操作 Hook 設定變更 | POST `/api/web/operation-hooks/declarations/prepare`; POST `/api/web/operation-hooks/prepare` |
| `core:hooks.publish` | 發佈全域操作 Hook 安全策略 | POST `/api/web/operation-hooks/publish` |
| `core:hooks.read` | 讀取全域操作 Hook 與授權設定 | GET `/api/web/operation-hooks/catalog`; GET `/api/web/operation-hooks/configuration`; GET `/api/web/operation-hooks/declarations`; GET `/api/web/operation-hooks/definitions`; GET `/api/web/operation-hooks/targets` |
| `core:legacy-migration.execute` | 遷移舊 JSON 中繼資料（僅本機） | GET `/api/migration/json-to-sqlite/stream`; POST `/api/migration/json-to-sqlite` |
| `core:library.move` | 移動伺服器上的插畫檔案 | POST `/api/library/artworks/*/move` |
| `core:library.read` | 檢視全庫插畫登錄、歷史與路徑 | GET `/api/downloaded/by-move-folder`; GET `/api/downloaded/history/paged`; GET `/api/downloaded/history`; GET `/api/downloaded/statistics`; GET `/api/library/artworks/*`; POST `/api/downloaded/batch` |
| `core:library.recover` | 復原插畫中繼資料 | POST `/api/library/artworks/*/recover-metadata` |
| `core:local-import.cancel` | 取消本機匯入掃描與登錄 | POST `/api/local-import/previews/*/cancel`; POST `/api/local-import/tasks/*/cancel` |
| `core:local-import.execute` | 將本機插畫登錄至資料庫 | POST `/api/local-import/tasks` |
| `core:local-import.preview` | 掃描伺服器上的本機插畫 | POST `/api/local-import/previews` |
| `core:local-import.read` | 讀取本機匯入預設與工作 | GET `/api/local-import/presets`; GET `/api/local-import/previews/*/items`; GET `/api/local-import/previews/*`; GET `/api/local-import/tasks/*/items`; GET `/api/local-import/tasks/*` |
| `core:logs.read` | 讀取執行日誌 | GET `/api/tools/logs/*` |
| `core:maintenance.read` | 檢視維護狀態（僅本機） | GET `/api/admin/maintenance/status` |
| `core:maintenance.run` | 執行維護（僅本機） | POST `/api/admin/maintenance/run` |
| `core:media.cancel` | 取消媒體處理 | POST `/api/tools/media/tasks/cancel` |
| `core:media.execute` | 執行媒體處理 | POST `/api/tools/media/tasks` |
| `core:media.preview` | 預覽媒體處理 | POST `/api/tools/media/previews` |
| `core:media.read` | 查看媒體工具與任務 | GET `/api/tools/media/tasks/current`; GET `/api/tools/media`; POST `/api/tools/media/capabilities` |
| `core:paths.read` | 查看路徑前綴 | GET `/api/tools/path-prefixes` |
| `core:paths.write` | 修改路徑前綴 | POST `/api/tools/path-prefixes/pin`; POST `/api/tools/path-prefixes` |
| `core:pixiv.read` | 查詢 Pixiv 線上資料 | GET `/api/pixiv/me/uid`; GET `/api/pixiv/thumbnail-proxy`; GET `/api/pixiv/user/*/meta` |
| `core:plugins.enabled` | 修改已授權插件的啟用狀態 | PUT `/api/plugins/*/enabled-preview`; PUT `/api/plugins/*/enabled` |
| `core:plugins.install` | 安裝本機插件套件（允許伺服器執行程式碼） | POST `/api/plugins/install` |
| `core:plugins.load` | 載入已授權插件 | POST `/api/plugins/*/actions/load`; POST `/api/plugins/*/load` |
| `core:plugins.quiesce` | 靜默已授權插件 | POST `/api/plugins/*/actions/quiesce`; POST `/api/plugins/*/quiesce` |
| `core:plugins.read` | 檢視插件狀態、相依項與安裝操作 | GET `/api/plugins/*/impact`; GET `/api/plugins/acquisitions/*`; GET `/api/plugins/acquisitions`; GET `/api/plugins/status`; GET `/api/plugins/updates` |
| `core:plugins.reload` | 重新載入已授權插件 | POST `/api/plugins/*/actions/reload`; POST `/api/plugins/*/reload` |
| `core:plugins.remove` | 刪除已授權插件的安裝套件 | POST `/api/plugins/*/actions/remove`; POST `/api/plugins/*/remove` |
| `core:plugins.restart` | 重新啟動已授權插件 | POST `/api/plugins/*/actions/restart`; POST `/api/plugins/*/restart` |
| `core:plugins.revocations.refresh` | 重新整理插件倉庫的撤銷資訊 | POST `/api/plugins/revocations/*/refresh` |
| `core:plugins.start` | 啟動已授權插件 | POST `/api/plugins/*/actions/start`; POST `/api/plugins/*/start` |
| `core:plugins.stop` | 停止已授權插件 | POST `/api/plugins/*/actions/stop`; POST `/api/plugins/*/stop` |
| `core:plugins.trust` | 核准及撤銷已授權插件的執行信任 | DELETE `/api/plugins/*/trust`; PUT `/api/plugins/*/trust` |
| `core:plugins.unload` | 卸載已授權插件的執行實例 | POST `/api/plugins/*/actions/unload`; POST `/api/plugins/*/unload` |
| `core:profile.read` | 讀取安裝實例的顯示稱呼 | GET `/api/onboarding/profile` |
| `core:profile.write` | 修改安裝實例的顯示稱呼 | POST `/api/onboarding/profile` |
| `core:recovery.read` | 檢視復原模式診斷 | GET `/api/plugins/recovery` |
| `core:repositories.read` | 查看外掛儲存庫設定 | GET `/api/settings/repositories` |
| `core:repositories.write` | 修改外掛儲存庫設定 | PUT `/api/settings/repositories` |
| `core:schedule.create` | 建立持久排程（撤銷金鑰不會刪除排程） | POST `/api/schedule/tasks` |
| `core:schedule.credentials` | 管理排程憑證與帳號策略 | DELETE `/api/schedule/tasks/*/credential`; POST `/api/schedule/credential-policies/actions`; POST `/api/schedule/tasks/*/credential` |
| `core:schedule.delete` | 刪除持久排程 | DELETE `/api/schedule/tasks/*` |
| `core:schedule.pause` | 暫停持久排程 | POST `/api/schedule/tasks/*/pause` |
| `core:schedule.pending.write` | 處理及清除排程待處理項 | DELETE `/api/schedule/tasks/*/pending`; POST `/api/schedule/tasks/*/pending/resolve` |
| `core:schedule.read` | 讀取已授權來源的持久排程 | GET `/api/schedule/tasks/*/pending`; GET `/api/schedule/tasks/*/queue`; GET `/api/schedule/tasks/*`; GET `/api/schedule/tasks` |
| `core:schedule.run` | 啟用、執行及恢復持久排程 | POST `/api/schedule/tasks/*/enabled`; POST `/api/schedule/tasks/*/resume`; POST `/api/schedule/tasks/*/run` |
| `core:schedule.sources.read` | 查看批次與計畫來源目錄 | GET `/api/schedule/sources` |
| `core:schedule.write` | 修改持久排程與代理 | POST `/api/schedule/tasks/*/proxy`; PUT `/api/schedule/tasks/*` |
| `core:scripts.read` | 讀取已安裝插件的使用者腳本 | GET `/api/scripts/*.user.js`; GET `/api/scripts/*/install`; GET `/api/scripts/*`; GET `/api/scripts` |
| `core:series.read` | 檢視全庫插畫系列 | GET `/api/series/*/cover`; GET `/api/series/*`; GET `/api/series/paged`; GET `/api/series` |
| `core:settings.read` | 讀取設定與預設 | GET `/api/settings/presets`; GET `/api/settings` |
| `core:settings.write` | 驗證與儲存設定 | POST `/api/settings/validate`; POST `/api/settings` |
| `core:startup-cache.cancel` | 取消啟動快取訓練 | POST `/api/tools/startup-cache/tasks/*/cancel` |
| `core:startup-cache.execute` | 訓練啟動快取 | POST `/api/tools/startup-cache/tasks` |
| `core:startup-cache.read` | 檢視啟動快取狀態 | GET `/api/tools/startup-cache` |
| `core:system.check` | 檢查網路連線 | GET `/api/onboarding/connectivity`; POST `/api/admin/system/connectivity` |
| `core:system.exit` | 結束復原模式中的程式（僅本機） | POST `/api/plugins/recovery/exit` |
| `core:system.read` | 查看系統狀態 | GET `/api/admin/system` |
| `core:system.reload` | 重新載入設定 | POST `/api/admin/system/reload` |
| `core:system.restart` | 重新啟動程式 | POST `/api/admin/system/restart`; POST `/api/plugins/backend-restart`; POST `/api/plugins/recovery/restart` |
| `core:tools.history.read` | 查看工具執行歷程 | GET `/api/tools/history` |
| `core:updates.download` | 下載程式更新 | POST `/api/admin/update/download` |
| `core:updates.install` | 安裝程式更新 | POST `/api/admin/update/install` |
| `core:updates.read` | 檢查程式更新與下載進度 | GET `/api/admin/update/check`; GET `/api/admin/update/download/progress`; GET `/api/admin/update/last` |
| `download-workbench:page.write` | 切換下載工作台頁面 | POST `/api/batch/page` |
| `download-workbench:pixiv.read` | 查詢 Pixiv 線上資料 | GET `/api/pixiv/artwork/*/meta`; GET `/api/pixiv/artwork/*/pages`; GET `/api/pixiv/artwork/*/ugoira`; GET `/api/pixiv/me/collection/*/works`; GET `/api/pixiv/me/collections`; GET `/api/pixiv/me/follow-latest`; GET `/api/pixiv/me/following`; GET `/api/pixiv/me/illust-bookmarks`; GET `/api/pixiv/search/range`; GET `/api/pixiv/search`; GET `/api/pixiv/series/*`; GET `/api/pixiv/user/*/artworks`; GET `/api/pixiv/user/*/illust-cards`; GET `/api/pixiv/user/*/request-artworks` |
| `download-workbench:profiles.read` | 讀取共用下載預設 | GET `/api/batch/profiles` |
| `download-workbench:profiles.write` | 修改共用下載預設 | PUT `/api/batch/profiles` |
| `download-workbench:state.read` | 讀取單人模式的工作台狀態 | GET `/api/batch/state` |
| `download-workbench:state.write` | 修改單人模式的工作台狀態 | POST `/api/batch/state` |
| `gallery-tools:duplicates.read` | 檢視重複作品與掃描狀態 | GET `/api/duplicates/groups`; GET `/api/duplicates/scan/status` |
| `gallery-tools:duplicates.scan` | 掃描重複作品 | POST `/api/duplicates/rescan`; POST `/api/duplicates/scan` |
| `gallery-tools:statistics.read` | 檢視全庫統計 | GET `/api/stats/dashboard` |
| `gallery:collections.write` | 修改插畫的精選集歸屬 | POST `/api/gallery/artworks/collect` |
| `gallery:delete` | 刪除全庫中的插畫及檔案 | DELETE `/api/gallery/artwork/*`; POST `/api/gallery/artworks/delete` |
| `gallery:export` | 匯出全庫中的插畫檔案 | POST `/api/gallery/artworks/export` |
| `gallery:read` | 檢視全庫插畫與標籤 | GET `/api/gallery/artwork/*/by-author`; GET `/api/gallery/artwork/*/by-series`; GET `/api/gallery/artwork/*/related`; GET `/api/gallery/artwork/*/series`; GET `/api/gallery/artwork/*`; GET `/api/gallery/artworks`; GET `/api/gallery/tags/lookup`; GET `/api/gallery/tags` |
| `mail:templates.send` | 傳送所有郵件範本 | POST `/api/admin/mail/test-all` |
| `mail:test.send` | 傳送測試郵件 | POST `/api/admin/mail/test` |
| `notification:inbox.delete` | 刪除站內信 | DELETE `/api/notifications/*` |
| `notification:inbox.read` | 讀取安裝實例的站內信 | GET `/api/notifications/*/content`; GET `/api/notifications/*`; GET `/api/notifications` |
| `notification:inbox.templates.test` | 傳送所有站內信範本 | POST `/api/admin/notifications/test-all` |
| `notification:inbox.test` | 傳送測試站內信 | POST `/api/admin/notifications/test` |
| `notification:inbox.update` | 標記站內信已讀及調查無法使用 | POST `/api/notifications/*/read`; POST `/api/notifications/*/survey-unavailable`; POST `/api/notifications/read-all` |
| `novel:collections.write` | 修改小說的精選集歸屬 | POST `/api/gallery/novels/collect` |
| `novel:content.read` | 讀取小說內文與內嵌圖片 | GET `/api/gallery/novel/*/content`; GET `/api/gallery/novel/*/embed/*` |
| `novel:downloads.read` | 讀取此金鑰提交的小說下載狀態 | GET `/api/novel/status/*` |
| `novel:glossary.delete` | 刪除小說術語表 | DELETE `/api/admin/glossary/*` |
| `novel:glossary.read` | 讀取小說術語表 | GET `/api/admin/glossary/*`; GET `/api/admin/glossary/novel/*/default`; GET `/api/admin/glossary/series/*/default`; GET `/api/admin/glossary` |
| `novel:glossary.write` | 建立及修改小說術語表 | POST `/api/admin/glossary`; PUT `/api/admin/glossary/*/entries`; PUT `/api/admin/glossary/*` |
| `novel:library.delete` | 刪除小說及檔案 | DELETE `/api/gallery/novel/*`; POST `/api/gallery/novels/delete` |
| `novel:library.export` | 匯出小說檔案 | GET `/api/novel/*/export`; GET `/api/novel/series/*/merged`; POST `/api/gallery/novels/export/options`; POST `/api/gallery/novels/export` |
| `novel:library.read` | 檢視全庫小說與系列 | GET `/api/gallery/novel/*/by-series`; GET `/api/gallery/novel/*/cover`; GET `/api/gallery/novel/*/series`; GET `/api/gallery/novel/*`; GET `/api/gallery/novel/series/*/cover`; GET `/api/gallery/novel/series/*`; GET `/api/gallery/novel/translated-titles`; GET `/api/gallery/novels/authors`; GET `/api/gallery/novels/series`; GET `/api/gallery/novels/tags`; GET `/api/gallery/novels`; GET `/api/novel/series/*/novel-ids`; POST `/api/gallery/novels/downloaded-batch` |
| `novel:narration.read` | 讀取聽書角色與音色 | GET `/api/narration/availability`; GET `/api/narration/cast/voice/reference`; GET `/api/narration/cast`; GET `/api/narration/casts/*/voices`; GET `/api/narration/casts/novel/*/default`; GET `/api/narration/casts`; GET `/api/narration/narrator-presets` |
| `novel:narration.script` | 產生聽書腳本 | POST `/api/narration/script` |
| `novel:narration.synthesize` | 合成聽書及參考音訊 | POST `/api/narration/cast/voice/reference/generate`; POST `/api/narration/tts/line`; POST `/api/narration/tts/preview` |
| `novel:narration.write` | 修改聽書角色與參考音訊 | DELETE `/api/narration/cast/voice/reference`; POST `/api/narration/cast/voice/reference`; POST `/api/narration/casts`; PUT `/api/narration/cast/voice` |
| `novel:pixiv.read` | 查詢 Pixiv 線上資料 | GET `/api/pixiv/me/novel-bookmarks`; GET `/api/pixiv/novel-search/range`; GET `/api/pixiv/novel-search`; GET `/api/pixiv/novel/*/bookmark-count`; GET `/api/pixiv/novel/*/meta`; GET `/api/pixiv/novel/series/*`; GET `/api/pixiv/user/*/novel-cards`; GET `/api/pixiv/user/*/novels` |
| `novel:series.merge` | 產生小說系列合訂本 | POST `/api/novel/series/*/merge` |
| `novel:translation.execute` | 翻譯小說與系列標題 | POST `/api/novel/*/translate`; POST `/api/novel/series/*/translate-title` |
| `novel:translation.probe` | 偵測小說翻譯語言 | POST `/api/novel/translate-lang-probe` |
| `novel:translation.read` | 讀取小說自動翻譯狀態 | GET `/api/novel/translate-status/*` |
| `page-editor:components.preview` | 預覽元件 | POST `/api/page-editor/components/preview`; POST `/api/page-editor/components/previews/*/close` |
| `page-editor:components.publish` | 發佈及停用元件 | POST `/api/page-editor/components/*/availability`; POST `/api/page-editor/components/*/publish` |
| `page-editor:components.read` | 讀取元件庫 | GET `/api/page-editor/components/*/versions/*`; GET `/api/page-editor/components/*`; GET `/api/page-editor/components` |
| `page-editor:components.write` | 儲存元件草稿 | POST `/api/page-editor/components/save` |
| `page-editor:pages.preview` | 預覽及驗證頁面 | POST `/api/page-editor/documents/*/prepare`; POST `/api/page-editor/documents/*/preview`; POST `/api/page-editor/migrate`; POST `/api/page-editor/styles/parse` |
| `page-editor:pages.publish` | 發佈及還原頁面 | POST `/api/page-editor/documents/*/publish`; POST `/api/page-editor/documents/*/restore` |
| `page-editor:pages.read` | 讀取頁面目錄與文件 | GET `/api/page-editor/catalog`; GET `/api/page-editor/documents/*/default`; GET `/api/page-editor/documents/*` |
| `page-editor:pages.write` | 儲存頁面草稿 | POST `/api/page-editor/documents/*/save` |
| `plugin-market:install` | 安裝市場插件與相依項（允許伺服器執行程式碼） | POST `/api/plugin-market/*/*/*/install-preview`; POST `/api/plugin-market/*/*/*/install`; POST `/api/plugin-market/operations/*/execute` |
| `plugin-market:operations.discard` | 放棄待執行的插件取得操作 | POST `/api/plugin-market/operations/*/discard` |
| `plugin-market:operations.prepare` | 準備插件取得操作 | POST `/api/plugin-market/operations` |
| `plugin-market:read` | 瀏覽插件市場與安裝預覽 | GET `/api/plugin-market/*/*/*/install-preview`; GET `/api/plugin-market/catalog`; GET `/api/plugin-market/content/*/*/*/*`; GET `/api/plugin-market/content/*/*/image`; GET `/api/plugin-market/plugins/*/*/*/facts`; GET `/api/plugin-market/plugins/*/*`; GET `/api/plugin-market/repositories` |
| `plugin-market:repositories.preview` | 檢查待匯入的插件倉庫 | POST `/api/plugin-market/repositories/import/preview` |
| `plugin-market:repositories.trust` | 信任並匯入插件倉庫 | POST `/api/plugin-market/repositories/import/trust` |
| `push:templates.send` | 傳送所有推播範本 | POST `/api/admin/push/test-all` |
| `push:test.send` | 傳送測試推播 | POST `/api/admin/push/test` |
| `translation:cancel` | 暫停或取消翻譯工作 | POST `/api/translation/jobs/*/cancel`; POST `/api/translation/jobs/*/pause` |
| `translation:delete` | 刪除翻譯工作紀錄 | DELETE `/api/translation/jobs/*` |
| `translation:execute` | 開始或恢復小說與系列翻譯 | POST `/api/translation/jobs/*/resume`; POST `/api/translation/jobs`; POST `/api/translation/novel`; POST `/api/translation/series` |
| `translation:read` | 檢視翻譯狀態、語言與工作 | GET `/api/translation/jobs/*`; GET `/api/translation/jobs`; GET `/api/translation/languages`; GET `/api/translation/status`; POST `/api/translation/language` |
| `tts:synthesize` | 線上合成朗讀音訊 | POST `/api/tts/edge/synthesize` |
| `tts:voices.read` | 檢視線上朗讀音色 | GET `/api/tts/edge/voices` |
| `<plugin>:settings.read` | 讀取設定與預設 | `core:settings.read` |
| `<plugin>:settings.write` | 驗證與儲存設定 | `core:settings.write` |
| `<plugin>:media.read` | 查看媒體工具與任務 | `core:media.read` |
| `<plugin>:media.preview` | 預覽媒體處理 | `core:media.preview` |
| `<plugin>:media.execute` | 執行媒體處理 | `core:media.execute` |
| `<plugin>:media.cancel` | 取消媒體處理 | `core:media.cancel` |
| `<plugin>:file-import.read` | 查看檔案匯入工具與任務 | `core:file-import.read` |
| `<plugin>:file-import.preview` | 預覽檔案匯入 | `core:file-import.preview` |
| `<plugin>:file-import.execute` | 執行檔案匯入 | `core:file-import.execute` |
| `<plugin>:file-import.cancel` | 取消檔案匯入 | `core:file-import.cancel` |
| `<plugin>:file-import.acknowledge` | 確認檔案匯入結果 | `core:file-import.acknowledge` |
| `<plugin>:downloads.create` | 建立下載任務 | `core:downloads.create` |
| `<plugin>:batches.execute` | 允許批次呼叫此外掛的來源與作品執行器 | `core:batches.create`, `core:batches.resume` |
| `<plugin>:schedule.manage` | 管理此插件來源的持久排程與憑證策略 | `core:schedule.*` |
| `<plugin>:plugins.manage` | 允許管理此插件的生命週期、啟用狀態與信任 | `core:plugins.*` |
