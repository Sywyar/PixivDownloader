# 編寫 HTTP API 與權限宣告

方法、路徑與參數取自 Spring MVC，說明可取自編譯時保留的 Javadoc。使用 `@Operation`、`@Schema` 與 OpenAPI 請求內容註解補充範例、手動解析的 JSON 與回應狀態。權限來自生效的路由宣告。在 `/openapi` 核對輸入、成功狀態、目標外掛限制與主要錯誤，非同步操作須說明如何查詢結果。

新增或修改 Web、Swing、Compose、CLI 的業務功能時，同時檢查是否需要配套 HTTP API。呼叫方式與介面清單見 [HTTP API](/zh-hant/http-api)，外掛契約見 [第三方外掛 SDK](/zh-hant/plugin-development)。

在控制器所屬的 HTTP 多語言資源中，用 `guide` 設定整組操作共用的指南，用 `<Controller>.<method>.<VERB>.guide` 指定單一操作的頁面或章節。產生的操作將直接指南放在 `externalDocs`，將相關操作引用的指南去重後放在 `x-related-guides`。可從回應取得下一次呼叫所需的參數時，請在對應的 `@ApiResponse` 宣告 `links`。

從 `x-program-permission` 讀取路由權限。有額外檢查時，`x-program-permissions.route` 會給出相同的值。`discovery` 指出取得來源權杖的介面，`submit` 陣列逐項列出固定權限、由請求標頭觸發的權限或目錄查詢條件。在 `x-schedule-sources` 中，管理排程查 `schedulePermission`，提交批次查 `batchSubmitPermission`。外掛介面若代為提交固定來源的手動批次，以 `x-manual-batch-source` 宣告來源類型，呼叫端可直接提交。 實例匯出的 OpenAPI 在頂層 `x-program-permission-conditions` 中列出各階段、欄位以及 `request#`、`task#`、`batch#`、`item#` 的解析規則。

條件授權以 `when.keyFrom` 讀取 JSON 欄位，再與 `when.value` 的字面值比較。例如圖庫匯出僅在 `request#/deleteAfter` 為布林值 `true` 時額外要求刪除權限。

`execution` 透過 `executePermissionsByWorkType` 列出執行器權限，依本次計畫所需的作品類型授權。背景執行時也會複查來源權限。缺少的權限 ID 位於 `reportedIn.pointer` 指定的 JSON Pointer。提交時讀取 HTTP 403 回應本文，受理後用 `core:batches.read` 存取 202 回應的 `Location`，讀取批次結果。下載提交例外可透過 `DownloadTaskException.programPermission()` 取得已知權限。收到 `auth.program.unsupported` 時，可從 `x-program-alternative` 查找替代介面。

`x-csrf-origin` 為 `origin-or-referer` 時，原生用戶端須傳送同源 Origin 或 Referer。`origin-required` 的具體限制見 Origin 參數。`conditional` 依參數說明區分瀏覽器來源訊號與 Cookie。

發布具名 JSON SSE 事件時，在 `text/event-stream` 的 content 上用事件 DTO 宣告 schema，並將 content 擴充 `x-sse-data-event` 設為事件名稱。產生的媒體類型保留字串傳輸格式，`x-sse-events` 依事件名稱提供 DTO schema。瀏覽器操作可用 `x-program-alternative` 指向程式金鑰入口，值為方法和路徑，例如 `POST /api/download/tasks`。

## 填寫介面說明與選用的「詳細資訊」

控制器上的 `@HttpApiGroup("core-batch")` 指定資源檔 `src/main/resources/i18n/http/core-batch.properties`。請在控制器所屬模組填寫說明，並在同一目錄的語言檔（例如 `core-batch_en.properties`）提供譯文。主程式產生 OpenAPI 文件時會依文件語言讀取這些鍵。

鍵的前綴依序是控制器類別名稱（不含套件名）、Java 方法名稱和大寫的 HTTP 方法。若方法映射未指定唯一的 HTTP 方法，最後一段用 `ANY`。以 `POST /api/download/batches` 為例，`ManualBatchController.create.POST.summary` 是操作標題，`.description` 是簡介，`ManualBatchController.create.POST.details` 是選用的 Markdown 內文。

主程式將內文附加到 OpenAPI 操作的 `description`，`/openapi` 預設收合「詳細資訊」，點選標題即可展開全文。對於 `POST /api/download/tasks`，已啟用的下載提供者還可用 `schema.DownloadSubmission.<workType>.description` 補充各類型的說明。操作和提供者都沒有內文時，頁面會省略整節。

填寫前須從 HTTP 入口讀完所有可達的執行路徑，涵蓋適用的授權、驗證、提供者、非同步執行、檔案寫入、結果查詢和失敗復原流程。說明哪些結果在送出時就能取得，哪些要等工作結束。逐一核對各語言的 `/api/openapi.json`，再到 `/openapi` 找到對應的方法和路徑，確認章節預設收合，再用鍵盤展開並核對內文。

## 何時需要配套介面

查詢業務狀態、讀取與儲存設定、套用預設、下載、翻譯、匯入、匯出、遷移、維護和系統管理，都應能透過 HTTP 完成。已有等價介面時共用同一業務服務，不為每種 GUI 複製執行邏輯。

視窗大小、焦點、主題預覽、剪貼簿、系統檔案選擇器和開啟本機瀏覽器屬於用戶端互動，不要求伺服器模擬。流程包含這些操作時，將業務部分獨立出來。遠端用戶端的檔案需要上傳；伺服器路徑指向伺服器檔案系統，不能混用。

長時間工作提供開始、狀態、依執行身分取消及必要的結果查詢。202 表示已接受，要求取消不表示已結束；查不到工作不表示可以安全重新提交。介面應說明重新啟動後的狀態和冪等範圍。

## 宣告權限

路由由實際功能 owner 的 `PixivFeaturePlugin.routes()` 宣告。原有 `AccessPolicy` 管理人員、訪客與本機流程；程式存取使用 `WebRouteContribution.programPermissions` 依 HTTP 方法明確宣告。未宣告的程式呼叫會被拒絕，不繼承管理員權限。

```java
WebRouteContribution.admin("/api/example/items")
    .withProgramPermissions(Map.of(
        HttpMethod.GET, new ProgramPermission("items.read", "permission.items.read"),
        HttpMethod.POST, new ProgramPermission("items.create", "permission.items.create")
    ));
```

owner 由宿主註冊身分決定；若為 `example`，完整權限為 `example:items.read` 與 `example:items.create`。標籤 key 放在 owner 顯示命名空間的 i18n 資源中。程式權限僅用於明確的 `/api/` 路由與方法，不接受 `**`、OPTIONS 或 GUI/LOCAL 專用流程。完整路徑區段 `*` 可表示資源 ID。

| 業務 | 分開的權限 | 額外檢查 |
| --- | --- | --- |
| 設定與預設 | 讀取、驗證與儲存 | 每個欄位的實際 owner；不回傳敏感值 |
| 媒體維護、檔案匯入 | 讀取、預覽、執行、取消、完成確認 | 精確外掛 publication、預覽 token 與執行 ID |
| 下載、翻譯 | 查詢、建立或恢復、取消、刪除紀錄 | 資料範圍、憑證與工作歸屬 |
| 資料庫、環境 | 查詢、檢查、預覽、遷移、匯出、還原 | 整個安裝範圍、副作用、確認與停寫時段 |
| 外掛、系統 | 查詢、安裝、啟停、刪除、重新啟動 | 可執行程式碼與程序影響 |
| 金鑰及管理員憑證 | 人員管理員專用 | 程式不得建立金鑰或提升自身權限 |

同一權限下的介面應有一致的業務目的與資料範圍。選取外掛僅批次選取目前列出的權限，不授予未來權限。權限目錄依目前宣告與能力產生；管理員工作階段可透過 GET `/api/security/program-keys/permissions` 讀取實際 ID、owner、標籤與端點。

共用介面還須檢查目標資源。設定、媒體與檔案匯入同時要求 core 入口權限及目標外掛的相應權限；依目標篩選回傳清單，批次寫入在任何副作用前檢查全部目標。資料庫遷移與環境封存作用於整個安裝，不能解釋為單一外掛的資料操作。外掛撤回或換代後不得呼叫舊 publication，也不能借用另一 owner 的同名路由授權。

## 輸入、錯誤與驗證

介面說明列出方法、路徑、權限、目標範圍、請求欄位與預算、非同步狀態及主要錯誤。在信任邊界驗證路徑、憑證與 JSON。金鑰僅透過 `Authorization: Bearer` 傳遞，不寫入 URL、日誌或瀏覽器持久儲存。來源 IP 使用可信代理處理後的用戶端位址，不自行信任轉送標頭。既有 CSRF、復原模式與外掛租約繼續生效。

錯誤保留穩定 `code` 與本地化 `error`。區分參數錯誤、拒絕、物件不存在、修訂衝突、超限、能力不可用及結果不確定。網路中斷後先查詢結果，不自動重送寫入操作。

外掛在 HTTP 入口自行驗證參數時，可使用 SDK 中的 `top.sywyar.pixivdownload.core.web.HttpParameterException`。這項契約位於僅依賴 JDK 的 `pixivdownload-core-api`。例如，確認查詢參數 `order` 不在允許的選項中時，可拋出 `new HttpParameterException("query", "order", "invalid_value")`。宿主傳回 HTTP 400、`code=error.request.param.invalid`、當前語言的 `error`，以及 `in`、`field`、`reason`。既有外掛可沿用原來的錯誤回應。 在可能拋出此例外的操作加上 `@KnownErrorCodes(status = "400", value = "error.request.param.invalid")`，實例的 OpenAPI 就會列出這項錯誤及其欄位。

`in` 可為 `query`、`path`、`header`、`part` 或 `body`。前四種的 `field` 填入介面宣告的參數名稱或表單部分名稱，JSON 請求本文則填入 JSON Pointer，例如 `/options/pages`。`reason` 使用穩定的機器值，如 `required`、`type` 或 `invalid_value`。這三個欄位僅描述位置與原因，不含呼叫端傳入的值。Spring 綁定參數時偵測到的缺少參數或型別轉換錯誤，由宿主指出位置。

測試須經過真實過濾器或 HTTP 入口，證明權限不足時沒有業務副作用，並涵蓋方法差異、目標外掛、來源網段、撤銷、換代與儲存失敗。修改介面時同步權限目錄、呼叫端、五語言文件與相關迴歸；宣告或編譯通過不能取代互動與執行驗證。

## 權限範圍與專用入口

排程任務使用 `core:schedule.*` 中對應動作的精確權限，並要求來源外掛的 `schedule.manage`；帳號策略動作要求策略 owner 的同名權限。清單依來源授權篩選。此處的 `*` 只是文件分組，不是可儲存的權限。排程屬於安裝的持久設定，授權包含該來源的完整排程業務；撤銷建立金鑰不會刪除或停用排程。需要取消後續執行時，請先停用或刪除排程。

外掛生命週期、啟用狀態及信任操作同時要求對應的 `core:plugins.*` 動作與目標的 `<plugin>:plugins.manage`；存在依賴消費者時，也檢查消費者授權。已安裝但尚未載入的可管理外掛也會列於目標目錄。安裝權限允許套件及其依賴在伺服器執行程式碼；全域 Hook 發布權限允許修改安全策略，請依此實際影響授權。

共用憑證分為 `core:credentials.read`、`core:credentials.write` 及 `core:credentials.use`。讀取只回傳資訊，不回傳秘密；使用 `X-Acquisition-Credentials` 引用已儲存憑證時，除了業務權限，還需要 `credentials.use`。直接提供當次取得憑證仍使用 `X-Acquisition-Credential`。

圖庫、小說庫、收藏夾、站內信、封存、頁面編輯、更新及維護權限作用於共用安裝，不會自動限制為該金鑰建立的資料。下載任務與手動批次仍依金鑰身分隔離。本機維護、復原及舊中繼資料遷移仍要求本機來源；權限不會取代來源、確認、修訂或租約檢查。

身分建立與金鑰管理、GUI token、驗證碼、瀏覽器擷取票據、匿名配額及頁面組裝保留專用流程。程式下載與進度使用 `/api/download/tasks`，檔案匯入使用 `/api/tools/file-import`，作品管理使用 `/api/library/artworks/{id}`，頁面業務使用 `/api/page-editor`。站內信測試使用 `/api/admin/notifications/test` 或 `/test-all`。舊瀏覽器相容路由不另授予程式權限。
