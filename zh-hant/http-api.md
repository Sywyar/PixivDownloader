# 管理員 HTTP API

外部程式可用管理員登入取得的 `X-Session-Token` 呼叫業務介面。這是人員工作階段，沿用撤銷與維護模式限制；這些介面不提供獨立應用程式金鑰或 MCP 服務。Pixiv 取得憑據透過 `X-Acquisition-Credential` 傳遞，與管理員身分分開。

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
