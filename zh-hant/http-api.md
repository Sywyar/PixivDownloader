# OpenAPI 與程式金鑰

PixivDownloader 內建互動式 OpenAPI 文件與細粒度**程式存取金鑰**（Program Key），供指令碼、自動化工具或 AI Agent 安全呼叫下載、批次、畫廊、翻譯與維護介面（本程式僅提供 HTTP API，不內建獨立 MCP 服務；開發新介面的規範見[撰寫 HTTP API 與權限宣告](/zh-hant/http-api-development)）。

## 使用指令碼或 Agent 存取 API

1. **查看互動文件**：啟動執行個體後在瀏覽器開啟 `/openapi`（本機預設 `http://localhost:6999/openapi`）。存取說明公開可讀；以管理員登入後可瀏覽完整介面清單、請求範例、所需權限，並透過回應下方的「相關介面」連結（`links` / `x-related-operations` / `externalDocs`）在關聯操作與使用指南間跳轉。
2. **建立程式存取金鑰**：在桌面「安全 → 程式存取金鑰」中建立金鑰（「設定 → 存取控制」也提供跳轉按鈕）；沒有桌面環境時用[命令列](#命令列管理金鑰)建立。設定允許的來源 IP/網段並勾選所需權限。若指令碼或 Agent 需自行取得介面定義，請同時勾選 `core:api-reference.read`。
3. **呼叫介面或產生 Agent 提示詞**：用戶端透過請求標頭 `Authorization: Bearer <程式金鑰>` 請求 `GET /api/openapi.json`（或以 `HEAD /api/openapi.json` 探測權限）取得機器可讀的 OpenAPI 文件；搭配 Agent 使用時，可在建立金鑰後，或從金鑰清單的「給 Agent 的提示詞」複製提示詞，將文件網址交給 Agent，再將金鑰單獨設定至 Agent 執行環境。支援 Agent Skills 的 Agent 也可以[安裝 skill](#為-ai-agent-安裝-skill)，在相關任務中自動啟用。

### 為 AI Agent 安裝 skill

主儲存庫提供 [Agent Skills](https://agentskills.io/) 格式的 `pixivdownloader` skill，說明如何連線到執行個體、使用程式存取金鑰、呼叫常用介面（瀏覽 Pixiv、下載作品、尋找已下載的作品）以及處理錯誤。安裝後，支援 Agent Skills 的 Agent（如 Claude Code、Codex、Cursor、GitHub Copilot、Gemini CLI）會在相關任務中自動啟用它，不必每次複製提示詞。

1. 安裝 skill（需要 Node.js）。命令會詢問要裝到哪些 Agent；`-g` 表示對所有專案生效：

   ```bash
   npx skills add Sywyar/PixivDownloader --skill pixivdownloader -g
   ```

   之後可用 `npx skills update -g` 取得新版本。
2. 在 Agent 的執行環境中把 `PIXIV_PROGRAM_KEY` 設為程式存取金鑰。執行個體不在 `http://localhost:6999` 時，再把 `PIXIV_BASE_URL` 設為執行個體位址，或在對話中告訴 Agent。不要把金鑰貼進對話。
3. 依任務為金鑰勾選權限。Agent 會先呼叫 `/self` 查看已授予的權限；建議同時勾選 `core:api-reference.read`，讓它能查閱完整介面目錄。

?> skill 只寫長期不變的約定和常用介面，欄位、參數和取值以執行個體的 `GET /api/openapi.json` 為準。瀏覽 Pixiv 的介面由 `pixiv-content` 外掛提供；外掛未安裝或已停用時，Agent 會提示你安裝或啟用。

### 依作者和標籤搜尋 Pixiv 投稿

每個 `tag` 參數填入一個作品標籤。例如，`GET /api/pixiv-content/users/28329224/artworks?tag=furry&tag=original` 只傳回這位作者同時帶有這兩個標籤的投稿。小說使用 `/users/{id}/novels`。一次最多指定 10 個不同標籤。若指定多個標籤，且每個標籤都符合超過 2048 件投稿，介面會傳回 `422 PIXIV_TAG_SEARCH_TOO_BROAD`。

使用 `GET /api/pixiv-content/search/users-and-tags?word=cat&userPage=1&tagPage=1` 可同時查詢暱稱和標籤。回應中的 `users` 和 `tags` 可分別翻頁，兩者都從第 1 頁開始。Pixiv 要求登入時須提供擷取憑證。

### 命令列管理金鑰

沒有桌面環境（伺服器、Docker）時，用命令列管理金鑰。命令會連線到正在執行的實例，以管理員身分登入並呼叫下文的管理介面，結束時登出並退出，不需要停止服務。權限目錄由實例依已載入的外掛產生，因此實例必須正在執行。

| 命令 | 作用 |
| --- | --- |
| `--program-keys` | 列出金鑰：名稱、ID、權限數量、來源和使用時間 |
| `--program-key-permissions` | 列出目前實例可授予的權限及其涵蓋的介面 |
| `--program-key-create` | 建立金鑰；完整金鑰只在輸出中顯示一次 |
| `--program-key-revoke --key-id=<ID>` | 確認後撤銷指定金鑰 |

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --program-key-create
java -Dfile.encoding=UTF-8 -jar APP.jar --program-key-create --key-name=agent --key-permissions=core:api-reference.read,core:downloads.read --key-sources=127.0.0.1
```

- 省略 `--key-name`、`--key-permissions` 或 `--key-sources` 時會逐項提示，權限可依清單編號選擇。`--key-sources` 填 `any`，或以逗號分隔的 IP / CIDR。
- 管理員帳號可用 `--username`、`--password` 傳入，省略時互動輸入。命令列中的密碼會留在 Shell 歷史或程序清單中，日常請使用互動模式。
- 實例位址預設依 `config/config.yaml` 的 `server.port`、`server.ssl.enabled` 和 `ssl.domain` 推導，也可用 `--server-url=https://<主機>:<連接埠>` 指定。命令不讀取系統代理，也不跟隨重新導向；實例將 HTTP 重新導向到 HTTPS 時，請改用提示中的 HTTPS 位址。使用自簽憑證時，需先將憑證匯入 Java 信任庫。
- 使用 Docker Compose 時，在新容器中執行並指向服務名稱：

  ```text
  docker compose run --rm app --program-key-create --server-url=http://app:6999
  ```

- 實例要求登入驗證（例如驗證碼）時，命令列無法完成登入，會顯示伺服器傳回的原因。
- 結束代碼：`0` 成功；`1` 無法連線、登入失敗或伺服器拒絕；`2` 參數無效或操作已取消。建立或撤銷請求送出後連線中斷時，結果可能已經生效，請先用 `--program-keys` 查看再決定是否重試。

### 金鑰安全、來源限制與 CSRF 規則

- **一次性金鑰與配額**：完整金鑰僅在建立成功時顯示一次，伺服器端只儲存摘要（金鑰預設不過期，可隨時修改權限、來源範圍或撤銷；勾選整個外掛僅包含目前已列出的權限，不會自動授予未來新增權限）。每個安裝執行個體最多建立 128 個金鑰（名稱最長 80 個字元，單一金鑰最多 512 項權限與 64 個來源網段，管理請求主體上限 128 KiB）。
- **HTTPS 與來源 IP 限制**：來自本機或區域網路以外的明文 HTTP 程式金鑰請求預設被拒絕（確需公網 HTTP 時須逐把金鑰明確確認風險，後續編輯權限或來源會自動恢復 HTTPS 要求；即使請求遭拒，明文傳輸的金鑰仍可能已外洩，請立即撤銷）。來源限制支援 IPv4、IPv6 與 CIDR 網段（受限模式至少填一項，「任何來源」需明確選擇；反向代理部署須先設定 `server.trusted-proxy-cidrs`）。
- **驗證隔離與狀態碼**：請勿在同一請求中混用程式金鑰、管理員工作階段（`X-Session-Token` / Cookie）、邀請碼或本機 GUI token（擷取憑證仍透過 `X-Acquisition-Credential` 或 `X-Acquisition-Credentials` 獨立傳遞）。
  - 持有效程式金鑰存取未開放程式權限的介面（含匿名介面）回傳 `403 auth.program.unsupported`（可查看文件中的 `x-program-alternative` 改用程式專用介面）；無效金鑰或混用憑證回傳 `401 auth.program.invalid`。
  - `/api/gui/**`、金鑰管理與管理員密碼修改僅限本機 GUI 或人類管理員工作階段。
- **同源訊號（`x-csrf-origin`）**：攜帶 Cookie 的寫入請求必須通過同源檢查。原生 Bearer 用戶端也需留意介面的 `x-csrf-origin` 宣告：例如 `POST /api/schedule/tasks`（`origin-or-referer`）要求傳送同源 `Origin` 或 `Referer`；而無瀏覽器 Cookie 的原生 Bearer 用戶端呼叫 `POST /api/download/tasks` 與 `POST /api/download/batches` 時可省略這兩個請求標頭。
- **自檢、管理 API 與金鑰輪換**：
  - 呼叫 `GET /api/security/program-keys/self` 可無需額外業務權限核對目前金鑰的 `id` 與 `permissions`（權限清單可為空）。
  - 管理員工作階段可透過 `GET /api/security/program-keys`（清單，不含秘密或摘要）、`GET /api/security/program-keys/permissions`（目前安裝的權威權限目錄）、`POST /api/security/program-keys`（傳入 `name`、`permissions`、`anyIp`、`networks` 建立，回傳 `key` 與一次性 `secret`）、`PUT /api/security/program-keys/{id}`（傳入 `{"revision": 1, "policy": {...}}` 修改）與 `DELETE /api/security/program-keys/{id}`（傳入 `{"revision": 1}` 撤銷）管理金鑰。
  - **輪換流程**：建立新金鑰 → 用戶端切換並以 `/self` 核對 → 撤銷舊金鑰。新金鑰無法查看舊金鑰建立的下載任務或批次；請在撤銷前輪詢舊任務至終態，或改用管理員工作階段追蹤（撤銷僅阻止新請求，不會自動取消已接納的進行中任務）。

## 介面索引

下表中同一列的短路徑接在首個完整路徑前綴之後；翻譯（`/api/translation/**`）、術語表、朗讀、畫廊、收藏夾、排程工作、維護與外掛市場完整路徑見下方[程式權限清單](#程式權限清單)或執行個體 `/openapi` 頁面。

| 功能 | 主要入口 |
| --- | --- |
| 設定欄位、預設值、脫敏目前值與預設 | `GET /api/settings`、`GET /api/settings/presets` |
| 驗證與儲存設定 | `POST /api/settings/validate`、`POST /api/settings` |
| 外掛儲存庫設定 | `GET` / `PUT /api/settings/repositories` |
| 資料庫設定、連線測試與遷移 | `GET /api/settings/database`、`/providers`、`/status`、`/migration`；`POST /check`、`/migration-previews`、`/migrations` |
| 環境封存匯出與還原 | `POST /api/settings/environment/preview`、`/restore`、`/exports`；`GET /restore`、`/exports`、`/exports/{id}/archive`、`/installation` |
| 中繼資料回填 | `GET /api/tools/backfill/defaults`、`/tasks/current`、`/tasks/{id}`；`POST /candidates`、`/tasks`、`/tasks/{id}/cancel` |
| 媒體維護與記錄檔匯入 | `GET /api/tools/media`、`/api/tools/file-import`（各前綴下 `POST /previews`、`/tasks`、`/tasks/cancel`、`GET /tasks/current`）；`POST /api/tools/media/capabilities`；`POST /api/tools/file-import/completion/acknowledge` |
| 本地作品匯入與啟動快取 | `GET /api/local-import/presets`、`POST /previews`、`POST /tasks`；`GET /api/tools/startup-cache`、`POST /tasks`、`POST /tasks/{id}/cancel` |
| 目錄參照與路徑前綴 | `GET` / `POST /api/tools/directories` · `/api/tools/path-prefixes` |
| FFmpeg 狀態、安裝與取消，工具歷史與日誌 | `GET /api/tools/ffmpeg`、`POST /api/tools/ffmpeg/tasks`、`POST /api/tools/ffmpeg/tasks/{executionId}/cancel`；`GET /api/tools/history`、`/api/tools/logs/{fileName}` |
| 系統、控制中心與更新 | `/api/admin/system`、`/api/admin/control-center`；`/api/admin/update/check`、`/last`、`/download`、`/download/progress`、`/install` |
| AI、郵件、推播與站內信測試 | `/api/admin/ai/test`、`/models`；`/api/admin/mail/test`、`/test-all`；`/api/admin/push/test`、`/test-all`；`/api/admin/notifications/test`、`/test-all` |
| 單項下載、歷史與背景手動批次 | `/api/download/tasks`、`/api/download/tasks/history`；`/api/download/batches` |

還原狀態傳回 `jobId`。可用此 ID 搜尋應用程式記錄，查看這次還原失敗的詳情。若確認後、執行還原前目標已變更，`failureCode` 為 `transfer.target-changed`，請重新預覽後再確認。

`POST /api/tools/path-prefixes` 在同一筆交易中修改並登記路徑字首。`registerPaths` 的每一項都必須是現有目錄的絕對路徑字串。成功回應中，`registered` 是新增數量，`alreadyRegistered` 是沿用數量，`applied` 是修改與新增數量的總和。登記項目無效時回傳 400，並標示 `/registerPaths/i`（`i` 從 0 起算）；寫入失敗時回傳 `500 PATH_PREFIX_WRITE_FAILED`，整筆交易會復原。

Windows 磁碟代號與 UNC 路徑即使大小寫或分隔符號不同，登記時仍沿用同一個字首。路徑中獨立的 `.` 區段會沿用同一個字首 ID。POSIX 路徑區分大小寫。歷史記錄依各自的字首 ID 解析，修改一個 ID 只會影響引用該 ID 的記錄。固定符號根目錄的 `POST /api/tools/path-prefixes/pin` 若寫入失敗，同樣回傳 `500 PATH_PREFIX_WRITE_FAILED`，原有引用仍然有效。

圖片分類工具已移除。下列介面仍會檢查原有的身分與權限，通過後傳回空回應 `410 Gone`。

- `GET, PUT /api/tools/classifier/settings`
- `GET, DELETE /api/tools/classifier/folders`
- `GET /api/tools/classifier/images`
- `GET /api/tools/classifier/image`
- `POST /api/tools/classifier/moves`
- `GET /api/downloaded/by-move-folder`
- `POST /api/downloaded/move/{artworkId}`
- `POST /api/library/artworks/{artworkId}/move`

查詢或取消已封存的任務時，若能確認記錄屬於呼叫者，但無法讀取回執，介面會傳回 `503 DOWNLOAD_TASK_RECEIPT_UNAVAILABLE`。他人的任務和不存在的任務均傳回 404。

## 儲存設定

`POST /api/settings/validate` 僅驗證草稿，只讀取本次變更涉及的設定和憑證，不儲存設定或遷移憑證。

先呼叫 `GET /api/settings` 讀取欄位目錄，再依傳回的 `owner`、`packageId`、`generation` 與 `key` 提交修改（宿主欄位的 `owner` 與 `packageId` 為 `null`、`generation` 為 `0`）。敏感欄位僅回傳 `stored` 布林標記，不回傳明文或預設秘密；提交時透過 `action`（`KEEP`、`SET`、`CLEAR`）明確保留、替換或清除。傳回結果中的 `effect` 標示生效方式（儲存成功不代表已自動重新啟動後端或處理程序）：

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

預設透過同一儲存介面套用。修改外掛儲存庫（`PUT /api/settings/repositories`）須攜帶 `GET` 傳回的 `revision`，新增儲存庫或修改信任、位址及網路策略時須明確傳入 `"trustConfirmed": true`（修訂衝突時需重讀，不會覆寫他人修改）。資料庫連線測試與工具單次執行參數僅當次有效，不會寫回設定檔。

## 背景手動批次

每個處理程序同一時間僅保留一個手動批次（用戶端斷線後仍在背景繼續執行，但不跨處理程序重新啟動恢復；建立新批次前必須先刪除已閒置的舊批次）：

1. **查詢可用來源**：`GET /api/schedule/sources` 取得目前 `sourceType` 與 `activationToken`（來源定義由所屬外掛解釋，支援單一作品清單 `explicit-list`、作者投稿、約稿、搜尋、系列、收藏、關注新作和珍藏集）。
2. **建立批次**：`POST /api/download/batches` 提交 `name`、`sourceType`、`activationToken`、`definition`（JSON 物件，UTF-8 序列化上限 1 MiB，整請求上限 8 MiB）及選用 `proxy`。若提交時回傳 `403`，可從回應主體 `programPermission` 讀取缺少的權限 ID；回傳 `202` 後持 `core:batches.read` 存取 `Location`（背景執行若缺權，批次結果同樣在 `programPermission` 中標示）。
3. **輪詢狀態與項目**：`GET /api/download/batches/{id}` 查看整體狀態（以 `state` 判斷階段；`code` 為選用原因碼，如 `batch.work-failed` 可進一步查 `items`，作者批次 `schedule.pixiv.discovery-not-found` 需核對作者 ID），`GET /api/download/batches/{id}/items?offset=0&limit=100` 分頁讀取項目（單頁最多 200 項；逐項識別為批次 UUID + `workType` + `id`，不同於單項下載的執行 UUID）。
4. **控制與恢復**：`POST /api/download/batches/{id}/pause`、`/cancel`、`/resume`、`/retry` 分別暫停、取消、恢復掃描或僅重試已發現的失敗項（停止中狀態須等待進行中執行實際退出後才進入終態；已完成和因篩選略過的作品在恢復時不會重複執行）。
5. **替換憑證、處理待確認項與刪除**：
   - 在批次閒置時呼叫 `PUT /api/download/batches/{id}/credentials`（主體 `{"expectedRun": 1}`，上限 16 KiB；透過請求標頭傳入新憑證，省略請求標頭表示清除，恢復前會重新探測）。
   - 呼叫 `POST /api/download/batches/{id}/resolve` 處理待確認項（主體含 `expectedRun`、`workType`、`workId`、`reasonCode`、`userAction`、`rememberForRun`，上限 16 KiB；原因與輪次須符合目前紀錄）。 遇到 `DOWNLOAD_PATH_ACTION_REQUIRED` 時可選 `TRUNCATE`、`DEFAULT_NAME` 或 `CANCEL`。其他值會回傳 400，欄位為 `/userAction`。作品仍待確認，可直接修正後重送。
   - 閒置時呼叫 `DELETE /api/download/batches/{id}` 刪除批次紀錄。

單一批次最多接納 5,000 項作品，並受 64 MiB 編碼內容預留預算限制（先達到者生效）。篩選支援內容分級、AI、標籤、插畫類型、頁數、小說字數和最低收藏數（啟用收藏數篩選時，未知收藏數不予通過）。

- **搜尋翻頁間隔（`pageDelayMs`）**：搜尋來源可在 `download.pageDelayMs` 指定相鄰兩次請求開始時間的最短間隔（毫秒；省略時固定頁數預設 `0`、`maxPages = -1` 預設 `10000`，明確填 `0` 取消額外等待）。手動批次將其作為 JSON 物件放入 `definition`（排程工作則序列化為 `definitionJson` 字串；共用下載方案使用 `settings.pageDelayMs`，填 `null` 採用預設值）：

  ```json
  {"source":{"word":"landscape","maxPages":3},"download":{"pageDelayMs":5000}}
  ```

- **混合作品清單（`explicit-list`）`definition` 範例**（作品 ID 須為正整數十進位字串，同類型不可重複；請將示範 ID 替換為實際作品，缺少 `/definition/source/acquisitionMode` 會在建立前傳回 400）：

  ```json
  {
    "kind": "mixed",
    "source": {
      "acquisitionMode": "single-import",
      "works": [
        {"kind": "illust", "id": "123"},
        {"kind": "novel", "id": "456"}
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

## 工具、圖庫批次操作與重新啟動操作

所有工具介面中的路徑參數均指**執行後端的伺服器本地路徑**，而非遠端用戶端路徑：

- **媒體維護與記錄檔匯入**：先 `GET` 工具清單，再提交清單中的精確 `identity`（`pluginId`、`packageId`、`generation`、`publication`）與 `options` 執行預覽；啟動任務須攜帶同一 `identity` 與預覽 `token`，取消須同時攜帶 `executionId`（外掛換代或桌面啟動替代任務後舊 token/UUID 自動失效）。
- **中繼資料回填與手動恢復**：`POST /api/tools/backfill/tasks`（請求主體上限 4 KiB，`limit: 0` 表示全量，`202` 僅代表已接納）：

  ```json
  {
    "delayMs": 0,
    "artworkIds": [12345],
    "dryRun": true,
    "alwaysNetwork": false
  }
  ```

  單一插畫也可呼叫 `POST /api/library/artworks/{artworkId}/recover-metadata` 直接提交欄位補缺（保留既有非空值；請求主體缺失或無可用欄位回傳 400；需從 Pixiv 取得標籤和系列時請依作品 ID 啟動回填，見[匯入後補齊作品資料](/zh-hant/local-import?id=metadata-backfill)）。
- **畫廊檔案管理篩選與批次匯出/刪除**：
  - `GET /api/gallery/artworks?fileManagement=EXTERNAL_REFERENCE` 可篩選外部唯讀參照作品（刪除僅移除登記，保留原檔）；`MANAGED` 表示應用程式管理檔案（刪除時連同檔案清理，並在資料庫中保留 `deleted=true` 供查重）。
  - 插畫與小說的批次介面支援 `"mode": "filter"`（涵蓋所有相符頁，要求 `filter` 為 JSON 物件）或依 ID 陣列選擇（僅接受正整數如 `42`，小數、字串、零或負數均傳回 HTTP 400 及對應 `field`）。當明確傳入 `"filter": {}` 或僅含分頁參數的 `filter` 時會選取全庫該類作品，執行刪除或「匯出後刪除」必須同時傳入 `"confirmAll": true` 且金鑰須具備對應刪除權限。
  - 匯出傳回 `archiveToken` 後，持 `core:archives.read` 輪詢 `GET /api/archive/status/{token}`，狀態變為 `ready` 後持 `core:archives.download` 請求 `GET /api/archive/download/{token}`。
- **啟動快取、資料庫遷移與環境封存**：
  - 啟動快取訓練呼叫 `POST /api/tools/startup-cache/tasks`（請求主體 `{}`），回傳接納後輪詢 `GET /api/tools/startup-cache`。
  - 資料庫遷移與環境還原均使用預覽傳回的 `token` 確認（環境還原傳回 `RESTORE_PENDING` 後由替代處理程序完成）。環境匯出（`POST /api/settings/environment/exports`）須提供伺服器絕對路徑 `destination`（不覆寫既有檔案）與 `"confirmRestart": true`，包含憑證、資料庫或狀態時必須設定 `password`（勾選 `includeResources` 須同時勾選 `includeDatabases`）；匯出會停止後端並請求完整處理程序重新啟動，重新啟動失敗時需在伺服器手動啟動。安裝線上更新須提交已驗證安裝包的 SHA-256。

## 錯誤處理、輪詢與分頁

錯誤回應統一包含機器碼 `code` 與本地化說明 `error`（`400` 參數無效、`401`/`403` 驗證或權限拒絕、`404` 物件不存在、`409` 任務忙碌/修訂衝突/狀態不允許、`413` 請求主體超限、`503` 外掛或資料庫能力不可用）。請依業務狀態處理，不要對所有非 2xx 回應無條件重試。

- **單項下載提交與輪詢（`POST /api/download/tasks`）**：
  - 從 OpenAPI `x-download-submissions` 中選擇已啟用的 `workType`（未啟用類型傳回 `400 DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE`；上游明確回傳作品不存在時首次提交傳回 `404 WORK_NOT_FOUND`；依工作 ID 查詢不到屬於目前呼叫端的任務時傳回 `404 DOWNLOAD_TASK_NOT_FOUND`）。
  - 參數驗證失敗傳回 `in`、`field`（請求主體為 JSON Pointer 如 `/options/pages`，查詢/路徑/標頭/multipart 為宣告參數名）與 `reason`（如 `out_of_range`）。
  - 傳回 `202` 後依 `Location`（路徑參數 `taskId` 取回執中的 `task.attempt.attemptId`）輪詢：建議初始間隔 **2 秒**，`revision` 連續不變時逐步退避至 **10 秒**，有更新時恢復 2 秒，直至 `phase` 進入 `COMPLETED`、`FAILED` 或 `CANCELLED`（收到 `Retry-After` 時至少等待指定時長）。當 `progress` 非空時可讀取同一單位（`PAGES`、`IMAGES` 或 `BYTES`）的 `progress.unit`、`completed` 與 `total`。
  - **冪等與任務歷史**：同一呼叫端在目前處理程序冪等紀錄有效期內（終態儲存後約 5 分鐘到期），以相同 `requestId` 重試相同命令會傳回原回執；`GET /api/download/tasks/history` 僅列舉已儲存的終態紀錄（預設每頁 50 項，上限 200 項），不恢復進行中下載。

| 資料項 | 讀取與提交格式約定 |
| --- | --- |
| Pixiv 作品 ID | 畫廊 `artworkId`、匯出 `ids[]` 使用 JSON 整數；Pixiv 搜尋 `id`、下載任務 `workId`、手動批次 `key.id` 使用十進位字串（轉換時須保留 64 位元整數精度，第三方下載外掛自行定義 `workId` 格式） |
| 下載選項型別 | 單一任務 `options` 使用字串值（如 `{"bookmark":"true","mediaQuality":"80","mediaWebpLossless":"true"}`）；手動批次 `definition.download` 使用原生 JSON 型別（如 `{"bookmark":true,"mediaQuality":80,"mediaWebpLossless":true}`） |
| 時間戳記與路徑 | 下載任務 `updatedAt` 為 ISO 8601 字串；手動批次 `createdTime`、畫廊 `time`、排程工作 `nextRunTime` 為 Unix 毫秒時間戳記。任務 `outputPath` 為預定伺服器目錄，畫廊 `folder` 為實際登記目錄，兩者依作品 ID 關聯 |
| 常見回應結構 | `GET /api/authors` 傳回 JSON 陣列；`GET /api/authors/paged` 傳回含 `content` 的分頁物件；`GET /api/downloaded/statistics` 傳回含 `success` 與各統計計數的物件 |

| 分頁端點 | 分頁參數 | 翻頁方式 |
| --- | --- | --- |
| `GET /api/gallery/artworks` | `page`, `size` | 首頁傳 `page=0`，後續每次將 `page` 加 `1` |
| `GET /api/download/batches/{id}/items` | `offset`, `limit` | 從 `offset=0` 開始，每次依本頁實際傳回項目數累加 `offset` |
| `GET /api/download/tasks/history` | `limit`, `beforeTime`, `beforeId` | 首頁省略兩個游標參數；續頁時原樣傳回上一頁末尾的 `beforeTime` 與 `beforeId` |
| `GET /api/pixiv/search` | `page` | 從 `page=1` 開始（小於 `1` 時依第 1 頁處理） |
| `GET /api/plugin-market/catalog` | `cursor`, `limit` | 將回應中的 `nextCursor` 原樣傳為下一頁 `cursor`（外掛版本清單續頁使用 `nextVersionCursor`） |

## 程式權限清單

目前安裝執行個體的即時權限目錄由管理員呼叫 `GET /api/security/program-keys/permissions` 取得。金鑰僅接受精確權限 ID，不支援外掛或路徑萬用字元（下表路由中的 `*` 代表單一路徑區段）：

### 權限範圍與專用入口

- **複合權限核驗**：
  - **共用設定、媒體維護與檔案匯入**：須同時具備 `core` 入口權限與目標外掛的同名權限（讀取清單僅傳回已獲授權的目標）。
  - **單項下載與手動批次**：單項下載需 `core:downloads.create` + 目標下載類型外掛的 `<plugin>:downloads.create`；手動批次需 `core:batches.create`（或 `core:batches.resume`）+ 來源外掛與執行計畫涉及的每個作品外掛的 `<plugin>:batches.execute`（查詢來源目錄需 `core:schedule.sources.read`）。
  - **持久排程工作**：需 `core:schedule.*` 對應動作權限 + 來源外掛的 `<plugin>:schedule.manage`（帳號策略動作需策略 owner 的同名權限；排程屬於安裝層級持久設定，撤銷建立該排程的金鑰不會刪除或停用排程）。
  - **外掛生命週期與安全策略**：需 `core:plugins.*` 對應動作權限 + 目標外掛的 `<plugin>:plugins.manage`（有反向依賴消費者時還須具備消費者權限；`core:plugins.install` 與 `plugin-market:install` 允許在伺服器執行程式碼，`core:hooks.publish` 允許修改全域操作保護策略，請謹慎授權）。
  - **擷取憑證**：`core:credentials.read` 僅傳回憑證名稱與狀態、不傳回秘密；請求標頭透過 `X-Acquisition-Credentials` 引用已儲存共用憑證時，除業務權限外還需 `core:credentials.use`（單次直接傳入憑證仍用 `X-Acquisition-Credential`）。
- **資料隔離與安裝層級作用域**：下載任務與手動批次依金鑰身分隔離（每把金鑰只能查詢、取消和管理自己建立的任務與批次，管理員仍可查看全域；多把金鑰仍共用單一處理程序唯一手動批次名額）。畫廊、小說庫、收藏夾、翻譯、站內信、封存、頁面編輯、設定、日誌、更新、維護、資料庫遷移與環境封存均直接作用於共用安裝執行個體，不為每把金鑰維護私有副本；本機專屬維護與復原介面仍強制核驗本機迴路來源。
- **程式專用入口**：身分建立、金鑰管理、GUI token、驗證碼互動、瀏覽器擷取票據、匿名配額與頁面組裝保留專用流程，舊瀏覽器相容路由不開放程式權限。程式呼叫請使用 `/api/download/tasks`（下載與進度）、`/api/tools/file-import`（記錄檔匯入）、`/api/library/artworks/{id}`（作品管理）、`/api/page-editor`（頁面編輯）以及 `/api/admin/notifications/test`、`/test-all`（站內信測試）。

| 權限 | 用途 | 方法與路徑 |
| --- | --- | --- |
| `ai:connection.test` | 測試 AI 連線（會呼叫模型） | POST `/api/admin/ai/test` |
| `ai:models.read` | 查詢可用 AI 模型 | POST `/api/admin/ai/models` |
| `ai:status.read` | 檢視 AI 設定狀態 | GET `/api/admin/ai/status` |
| `core:api-reference.read` | 讀取或探測 HTTP 介面目錄 | GET, HEAD `/api/openapi.json` |
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
| `core:classifier.configure` | 修改圖片分類設定 (已退役, HTTP 410) | PUT `/api/tools/classifier/settings` |
| `core:classifier.delete` | 刪除圖片分類目錄 (已退役, HTTP 410) | DELETE `/api/tools/classifier/folders` |
| `core:classifier.read` | 查看圖片分類與圖片 (已退役, HTTP 410) | GET `/api/tools/classifier/folders`; GET `/api/tools/classifier/image`; GET `/api/tools/classifier/images`; GET `/api/tools/classifier/settings` |
| `core:classifier.write` | 移動分類圖片 (已退役, HTTP 410) | POST `/api/tools/classifier/moves` |
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
| `core:ffmpeg.cancel` | 取消 FFmpeg 安裝 | POST `/api/tools/ffmpeg/tasks/{executionId}/cancel` |
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
| `core:library.move` | 作品移動登記（已退役） · HTTP 410 | POST `/api/library/artworks/*/move` |
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
| `<plugin>:schedule.manage` | 管理此插件來源的持久排程與憑證策略 | `core:schedule.*`。呼叫 POST `/api/pixiv/user/{userId}/meta/schedule` 並傳入此來源外掛的排程 `taskId` 時，也需要這項權限。 |
| `<plugin>:plugins.manage` | 允許管理此插件的生命週期、啟用狀態與信任 | `core:plugins.*` |
