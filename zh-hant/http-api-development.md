# 撰寫 HTTP API 與權限宣告

新增或修改 Web、Swing、Compose、CLI 的業務功能時，應同步檢查是否需要提供配套的 HTTP API、程式金鑰權限宣告與多語言 OpenAPI 文件。用戶端呼叫方式與完整權限清單見 [OpenAPI 與程式金鑰](/zh-hant/http-api)，外掛擴充契約見 [第三方外掛 SDK](/zh-hant/plugin-development)。

## 什麼時候需要配套 HTTP API

- **業務能力統一提供 HTTP 介面**：查詢業務狀態、讀取與儲存設定、套用預設、下載、翻譯、匯入、匯出、資料庫遷移、維護和系統管理均應能透過 HTTP 完成。已有等價介面時直接重用同一業務服務，不為不同 GUI 複製執行邏輯。
- **分離用戶端呈現與伺服器端業務**：視窗大小、焦點、主題預覽、剪貼簿、系統檔案選擇器和開啟本機瀏覽器屬於用戶端呈現，無需伺服器端模擬。流程中包含檔案操作時，遠端用戶端檔案必須透過上傳傳輸，伺服器端路徑參數始終指向伺服器本地檔案系統，兩者不得混用。
- **長任務生命週期**：非同步長任務須提供啟動（`202 Accepted`）、狀態查詢、依執行身分取消及結果查詢介面，並在文件中寫明取消收尾語意（請求取消不代表已立即停止，查不到任務不代表可安全重投）、處理程序重新啟動後的狀態以及冪等範圍。

## 宣告路由與程式權限

路由由功能所屬 owner 在 `PixivFeaturePlugin.routes()` 中宣告。`AccessPolicy` 控制人員登入、訪客與本機 GUI 流程；程式金鑰存取則透過 `WebRouteContribution.withProgramPermissions(...)` 依 HTTP 方法明確宣告（未宣告的操作預設拒絕程式金鑰，不繼承管理員權限）：

```java
WebRouteContribution.admin("/api/example/items")
    .withProgramPermissions(Map.of(
        HttpMethod.GET, new ProgramPermission("items.read", "permission.items.read"),
        HttpMethod.POST, new ProgramPermission("items.create", "permission.items.create")
    ));
```

- **權限 ID 與適用路徑**：完整權限 ID 由宿主依外掛註冊身分自動組合為 `<owner>:<permission>`（如 `example:items.read`），顯示文字 key 放入該 owner 的顯示 i18n 資源中。程式權限僅支援明確的 `/api/` 路由與具體 HTTP 方法（支援單一路徑區段 `*` 表示資源 ID，不接受 `**`、`OPTIONS` 或 GUI/LOCAL 專用路由）。
- **權限粒度與目標複核**：同一權限下的介面應具有一致的業務目的與資料範圍；外掛分組勾選僅批次選擇目前明確列出的權限，不產生面向未來權限的萬用字元。共用介面（設定、媒體維護、檔案匯入、外掛生命週期、排程工作等）除核驗 `core` 入口權限外，還必須在產生任何副作用前核驗目標外掛或來源的權限，並依已獲授權範圍裁剪讀取清單（各業務域的組合規則與專用入口見 [OpenAPI 與程式金鑰：權限範圍與專用入口](/zh-hant/http-api#權限範圍與專用入口)）。

| 業務域 | 權限拆分原則 | 附加安全核驗 |
| --- | --- | --- |
| 設定與預設 | 讀取、驗證與儲存分離 | 核驗每個欄位的實際 owner；敏感值只回傳 `stored` 標記、絕不回傳明文 |
| 媒體維護與檔案匯入 | 讀取、預覽、執行、取消、完成確認分離 | 核驗精確外掛 publication、預覽 token 與執行 ID |
| 下載與翻譯 | 查詢、建立/恢復、取消、刪除紀錄分離 | 核驗資料範圍、擷取憑證與任務建立者歸屬 |
| 資料庫與環境封存 | 查詢、檢查、預覽、遷移、匯出、還原分離 | 作用於整套安裝，須核驗明確確認、預覽 token 與停寫時段 |
| 外掛與系統管理 | 查詢、安裝、啟停、刪除、重新啟動分離 | 涉及可執行程式碼載入與處理程序重新啟動，須同時核驗目標外掛與反向依賴者權限 |
| 金鑰與管理員憑證 | 僅限人類管理員工作階段 | 嚴禁程式金鑰建立/修改金鑰或提升自身權限 |

## 多語言文件與 OpenAPI 擴充欄位

Spring MVC 提供基礎方法、路徑與參數定義；編譯期保留的 Javadoc、`@Operation`、`@Schema` 與請求主體註解用於補充範例、手動解析的 JSON 結構及回傳狀態。

### 多語言介面說明與摺疊「詳細資訊」

在 Controller 上標註 `@HttpApiGroup("core-batch")`，即可綁定所屬模組下的 `src/main/resources/i18n/http/core-batch.properties` 及各語言資源檔（如 `core-batch_en.properties`）。鍵名前綴為 `<Controller類別名稱>.<Java方法名稱>.<大寫HTTP方法>`（未限定單一 HTTP 方法時用 `ANY`）：

| 資源鍵 | 作用 |
| --- | --- |
| `guide` / `<前綴>.guide` | 整組共用或單一操作專屬的使用指南路徑（產生至操作的 `externalDocs`，關聯操作引用的指南去重彙總至 `x-related-guides`） |
| `<前綴>.summary` / `<前綴>.description` | 操作標題與概要說明 |
| `<前綴>.details` | 選用的 Markdown 詳細說明（附加至 `description`，在 `/openapi` 頁面預設摺疊為「詳細資訊」，點擊或按鍵盤展開） |
| `schema.DownloadSubmission.<workType>.description` | 為 `POST /api/download/tasks` 補充各已啟用下載類型的專屬說明（操作與提供方均無正文時自動省略摺疊區） |

撰寫說明前須通讀從 HTTP 入口到驗證授權、輸入驗證、提供方、非同步執行、寫入磁碟、結果查詢與失敗復原的完整呼叫鏈，區分提交時立即可知的結果與非同步終態。完成後逐語言核對 `/api/openapi.json` 及 `/openapi` 頁面的摺疊展開與鍵盤可存取性。

### OpenAPI 擴充中繼資料

| 擴充欄位 | 語意與宣告方式 |
| --- | --- |
| `x-program-permission` / `x-program-permissions` | 單一路由權限寫入 `x-program-permission`；含附加核驗時在 `x-program-permissions` 中同時列出 `route`、`discovery`（取得來源權杖的介面）、`submit`（固定權限、請求標頭觸發權限或目錄查詢條件）和 `execution`（如 `executePermissionsByWorkType`）。頂層 `x-program-permission-conditions` 定義 `request#`、`task#`、`batch#`、`item#` 的解析規則與 `when.keyFrom` / `when.value` 條件（如匯出僅在 `request#/deleteAfter == true` 時附加刪除權限）及 `reportedIn.pointer` 缺權欄位位置 |
| `x-schedule-sources` / `x-manual-batch-source` | `x-schedule-sources` 列出各來源的 `schedulePermission` 與 `batchSubmitPermission`；代為提交固定來源手動批次的外掛介面以 `x-manual-batch-source` 宣告來源類型 |
| `x-csrf-origin` | 標示寫入請求的來源核驗策略：`origin-or-referer`（原生用戶端亦須傳送同源 `Origin` 或 `Referer`）、`origin-required` 或 `conditional`（依是否攜帶 Cookie 等瀏覽器訊號區分） |
| `x-program-alternative` | 在不支援程式金鑰的瀏覽器介面上指向對應的程式介面（格式為 `<METHOD> <path>`，如 `POST /api/download/tasks`） |
| `links`（`@ApiResponse`） | 有明確後續呼叫且可從回應擷取參數時宣告回應連結，供 `/openapi` 產生「相關介面」跳轉 |
| `x-sse-data-event` / `x-sse-events` | 發布具名 JSON SSE 事件時，在 `text/event-stream` 的 content 上宣告事件 DTO schema 與 `x-sse-data-event` 事件名稱；產生文件保留字串傳輸格式並在 `x-sse-events` 中依事件名稱匯出 DTO schema |

## 參數驗證、錯誤契約與測試驗收

- **信任邊界與錯誤結構**：路徑、憑證和 JSON 在入口邊界嚴格核驗；程式金鑰僅透過 `Authorization: Bearer` 傳遞，禁止寫入 URL、日誌或瀏覽器持久儲存；來源 IP 僅信任經 `server.trusted-proxy-cidrs` 核驗後的用戶端位址。錯誤回應統一保留穩定機器碼 `code` 與本地化 `error`（下載提交缺權可透過 `DownloadTaskException.programPermission()` 暴露缺少的權限 ID）。
- 在標註 `@HttpApiGroup` 的控制器中，可用 SDK 的 `@KnownErrorCodes` 在類別或方法上宣告標準 JSON 錯誤的 HTTP 狀態和業務錯誤碼。OpenAPI 可據此產生該 4xx 或 5xx 狀態的 `application/json` 回應。若回應另有自訂欄位，請在回應模型中說明。
- **結構化參數錯誤（`HttpParameterException`）**：外掛在 HTTP 入口手動驗證參數時，可拋出 `pixivdownload-core-api` 中的 `top.sywyar.pixivdownload.core.web.HttpParameterException(in, field, reason)`（例如查詢參數列舉無效時拋出 `new HttpParameterException("query", "order", "invalid_value")`），並在方法上標註 `@KnownErrorCodes(status = "400", value = "error.request.param.invalid")` 以匯出至 OpenAPI：
  - 宿主統一回傳 HTTP 400、`code = "error.request.param.invalid"`、本地化 `error` 以及 `in`、`field`、`reason` 三個定位欄位（嚴禁在欄位中回顯呼叫端提交的原始值或憑證；Spring 自身的缺參或型別轉換錯誤由宿主自動定位）。
  - `in` 取 `query`、`path`、`header`、`part` 或 `body`；前四者的 `field` 填宣告的參數名或表單分塊名，`body` 填 JSON Pointer（如 `/options/pages`）；`reason` 使用穩定機器碼（如 `required`、`type`、`invalid_value`、`out_of_range`）。
- **真實驗收**：測試必須經過真實安全過濾器或 HTTP 入口，驗證缺權請求無任何業務副作用，並涵蓋 HTTP 方法差異、目標外掛裁剪、來源網段限制、金鑰撤銷、外掛換代及儲存失敗回復；介面或權限變更時同步更新權限目錄、呼叫端、五語言文件與回歸測試。
