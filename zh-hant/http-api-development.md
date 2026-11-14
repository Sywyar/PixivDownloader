# 編寫 HTTP API 與權限宣告

新增或修改 Web、Swing、Compose、CLI 的業務功能時，同時檢查是否需要配套 HTTP API。呼叫方式與介面清單見 [HTTP API](/zh-hant/http-api)，外掛契約見 [第三方外掛 SDK](/zh-hant/plugin-development)。

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

測試須經過真實過濾器或 HTTP 入口，證明權限不足時沒有業務副作用，並涵蓋方法差異、目標外掛、來源網段、撤銷、換代與儲存失敗。修改介面時同步權限目錄、呼叫端、五語言文件與相關迴歸；宣告或編譯通過不能取代互動與執行驗證。

## 權限範圍與專用入口

排程任務使用 `core:schedule.*` 中對應動作的精確權限，並要求來源外掛的 `schedule.manage`；帳號策略動作要求策略 owner 的同名權限。清單依來源授權篩選。此處的 `*` 只是文件分組，不是可儲存的權限。排程屬於安裝的持久設定，授權包含該來源的完整排程業務；撤銷建立金鑰不會刪除或停用排程。需要取消後續執行時，請先停用或刪除排程。

外掛生命週期、啟用狀態及信任操作同時要求對應的 `core:plugins.*` 動作與目標的 `<plugin>:plugins.manage`；存在依賴消費者時，也檢查消費者授權。已安裝但尚未載入的可管理外掛也會列於目標目錄。安裝權限允許套件及其依賴在伺服器執行程式碼；全域 Hook 發布權限允許修改安全策略，請依此實際影響授權。

共用憑證分為 `core:credentials.read`、`core:credentials.write` 及 `core:credentials.use`。讀取只回傳資訊，不回傳秘密；使用 `X-Acquisition-Credentials` 引用已儲存憑證時，除了業務權限，還需要 `credentials.use`。直接提供當次取得憑證仍使用 `X-Acquisition-Credential`。

圖庫、小說庫、收藏夾、站內信、封存、頁面編輯、更新及維護權限作用於共用安裝，不會自動限制為該金鑰建立的資料。下載任務與手動批次仍依金鑰身分隔離。本機維護、復原及舊中繼資料遷移仍要求本機來源；權限不會取代來源、確認、修訂或租約檢查。

身分建立與金鑰管理、GUI token、驗證碼、瀏覽器擷取票據、匿名配額及頁面組裝保留專用流程。程式下載與進度使用 `/api/download/tasks`，檔案匯入使用 `/api/tools/file-import`，作品管理使用 `/api/library/artworks/{id}`，頁面業務使用 `/api/page-editor`。站內信測試使用 `/api/admin/notifications/test` 或 `/test-all`。舊瀏覽器相容路由不另授予程式權限。
