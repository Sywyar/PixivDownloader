# 插件管理

插件管理頁位於 `/plugin-manage.html`，插件市場位於 `/plugin-market.html`。兩者最終使用同一套包校驗、安裝事務和生命週期協調器；本地上傳不是繞過運行時邊界的另一套安裝器。

在 GUI 的「設定 → 外掛程式市集設定」中新增或編輯自訂儲存庫。彈窗頂部可切換「使用儲存庫描述檔」和「自行填寫」：前者預覽發布者、連線主機及完整公開金鑰指紋，確認後加入設定草稿；後者填寫儲存庫位址、網路策略及信任金鑰。統一儲存設定後重新啟動生效。Web 外掛程式市集用於瀏覽及安裝外掛程式。

## 安全模型

簽名能夠證明 artifact 的發佈者與字節完整性，但不代表安全審查，也不會授予額外運行權限。只安裝你信任的發佈者和倉庫。

宿主在加載前會檢查：

- 包大小、壓縮比、路徑和 JAR/ZIP 結構；
- `plugin.properties` 的 id、版本、核心 API 要求和依賴；
- SHA-256、結構化 Ed25519 簽名和 provenance；
- 當前插件 API 兼容性、依賴版本與 required 插件約束。

校驗、描述符解析和加載都使用同一份有界凍結字節。通過校驗後不會重新打開可被外部進程替換的公開安裝路徑。

### 執行模式與隔離邊界

每個外置插件都必須在 `plugin.properties` 顯式聲明 `pixiv.execution-mode`，只接受：

| 值 | 運行位置 | 權限邊界 |
| --- | --- | --- |
| `host-process-full-trust` | 宿主 JVM | 繼承宿主進程的文件、網絡和 OS 權限 |
| `declarative-process` | 獨立 worker JVM | 只通過有界協議發佈聲明式路由和能力 |

缺失、空白或未知值會在任何插件代碼執行前被拒絕。worker 仍使用宿主的 OS 賬號，只提供進程、協議和資源層面的有限隔離；當前沒有完整 OS 沙箱，也沒有要求 OS 沙箱的 JVM 開關。生產模式拒絕目錄形式的 `declarative-process` 插件；顯式開發模式會將其降級爲 `host-process-full-trust`，狀態和日誌顯示實際生效模式。

插件信任不會跨執行邊界靜默擴大。即使發佈者未變，從 `declarative-process` 升級爲 `host-process-full-trust`、SDK 主版本變化或信任撤銷後也必須由管理員重新確認。宿主實際以管理員或其它高權限運行時，full-trust 插件會繼承該權限，管理頁會持續顯示警告。

worker 默認使用 128 MiB heap、128 MiB metaspace、64 MiB direct memory，並在 OOM 時退出；初始化、命令、關閉超時分別爲 10,000 / 5,000 / 2,000 ms。異常退出後最多重啓 3 次，退避從 500 ms 增至最多 10,000 ms；stderr 最多讀取 1 MiB，並保留末尾 16 KiB。每個 worker 同時只允許 1 個在途請求和 1 個排隊請求。退出時宿主先撤回該插件的路由與能力，再按上述上限嘗試恢復。

可在 JVM 啓動前用 `pixivdownload.plugin-worker.*` 的 `initialize-timeout-ms`、`command-timeout-ms`、`shutdown-timeout-ms`、`restart-attempts`、`restart-initial-delay-ms`、`restart-max-delay-ms` 和 `stderr-max-bytes` 調整對應值。

### 插件包資源上限

默認准入上限爲 192 MiB 歸檔、48,000 個條目、672 MiB 實際解壓總量、64 MiB 單條目、1 MiB 描述符、壓縮比 200（只檢查至少 64 KiB 的條目）、1,024 個字符的條目名和 64 層路徑。對應 JVM 屬性爲：

- `pixivdownload.plugin.package.max-archive-bytes`
- `pixivdownload.plugin.package.max-entries`
- `pixivdownload.plugin.package.max-total-uncompressed-bytes`
- `pixivdownload.plugin.package.max-entry-uncompressed-bytes`
- `pixivdownload.plugin.package.max-descriptor-bytes`
- `pixivdownload.plugin.package.max-compression-ratio`
- `pixivdownload.plugin.package.max-entry-name-length`
- `pixivdownload.plugin.package.max-entry-depth`

屬性只接受正整數；非法值會使插件運行時初始化失敗，不會靜默回退。

## 安裝來源

### 發行包預置

Windows、Java 標準包和 full-offline 包在 `plugins/` 預置同一官方分發集合，包括 required `download-workbench`、默認 `gui-compose` 和後備 `gui-swing`。Douyin 是普通第三方插件，只從自定義倉庫或本地包安裝。預置插件仍是獨立 artifact，不會合入核心 Boot JAR。

### 本地上傳

在插件管理頁選擇 `.jar` 或兼容 `.zip`，也可同時提供 detached `.sig`。本地上傳不建立自定義信任根：簽名存在時必須與精確 artifact 匹配並通過適用信任根驗證；未簽名包會標爲 `LOCAL_UPLOAD / UNSIGNED_ALLOWED`。非官方本地包在生產模式也能安裝，但任何代碼執行前都必須確認風險；簽名包按發佈者指紋批准，未簽名包只批准當前精確 SHA-256。更新、換 key、撤銷或執行權限提升可能再次要求確認。

遠程倉庫包始終要求倉庫清單聲明的簽名，不能降級爲本地 unsigned。需要長期信任自有 key 的第三方分發應配置自定義倉庫。

### 插件市場

市場與內嵌官方倉庫默認啓用；啓動本身不訪問倉庫，打開或刷新市場、執行安裝時才發起請求。可在配置中關閉 `plugin-catalog.enabled`。官方倉庫使用程序內嵌的地址和信任根；自定義倉庫必須在 `config.yaml` 中配置自己的 HTTPS manifest 和 Ed25519 公鑰，詳見[配置參考](/zh-hant/configuration)。

市場狀態碼含義：

| 狀態 | 含義 |
| --- | --- |
| `NOT_INSTALLED` | 有兼容的可安裝版本 |
| `INSTALLED` | 已安裝，且沒有嚴格更高的兼容版本 |
| `UPDATE_AVAILABLE` | 存在嚴格更高的兼容版本 |
| `INCOMPATIBLE` | 最新可安裝版本不滿足當前核心 API |
| `UNAVAILABLE` | 清單沒有可下載的版本製品 |

市集操作記錄保存後端取得與安裝狀態；最終生效情況仍須結合安裝結果和實際執行狀態判斷。

## 安裝與更新事務

本地上傳和市場安裝都遵循同一流程：

1. 有界讀取並凍結候選 artifact；
2. 校驗結構、描述符、API、依賴、摘要、簽名和來源；
3. 撤回舊 generation 的新請求接納並等待它 drain；
4. 原子替換 artifact 與 provenance；
5. 按生命週期策略激活新包；
6. 任一步失敗時恢復舊 artifact、provenance 和可用 generation。

因此更新是**事務化替換**，不是在舊類實例上打補丁。瀏覽器頁面應在操作後重新讀取狀態，不要僅憑按鈕返回猜測插件已生效。

## 三類生命週期策略

插件在 `plugin.properties` 中用 `pixiv.lifecycle-policy` 聲明策略：

| 值 | 安裝/更新 | 啓用/禁用 | 適用情況 |
| --- | --- | --- | --- |
| `hot-reload` | 當前進程事務替換並即時激活 | 直接 start/stop | 沒有啓動期專屬資源，能夠完整撤回貢獻並釋放任務/客戶端 |
| `backend-restart` | 當前進程事務替換並即時激活 | 保存狀態後提示重啓後端 | 需要重建 Spring 後端上下文，但不要求結束桌面進程 |
| `process-restart` | 包先安全落盤，完整進程重啓後激活 | 保存狀態後提示重啓軟件 | 桌面 provider、主題、托盤等會被進程級組件長期持有的能力 |

未填寫時默認 `hot-reload`。值區分大小寫，只接受上表三個 token。

“重啓後端”只重建 Spring 後端上下文，不等於完整進程重啓；它不能讓 `process-restart` 插件生效。桌面生命週期管理器不可用時，管理頁不能代替操作系統重啓進程。

官方 `gui-compose` 是默認桌面 provider，`gui-swing` 自動後備。兩者分別擁有自己的頁面與交互，共享應用業務語義，均爲 `process-restart`；安裝、更新、啓停、移除或在“配置 → 界面”切換 provider 後都必須完整退出並重新啓動軟件。

## 生命週期動作

對可管理的 `hot-reload` 外置插件，管理 API 提供八個動作：

| 動作 | 語義 |
| --- | --- |
| `load` | 從已安裝 artifact 創建類加載器並加載插件，尚不發佈運行能力 |
| `start` | 啓動已加載插件、創建子上下文併發布貢獻 |
| `quiesce` | 停止接納新工作並等待當前 publication 的任務/調用排空 |
| `stop` | 撤回貢獻並停止插件實例；不會刪除安裝包 |
| `unload` | 在停止後釋放 PF4J 插件與類加載器；不會刪除安裝包 |
| `remove` | 完成安全清退後刪除已安裝 artifact 和對應 provenance |
| `restart` | stop/start 當前實例，保留 generation 與 classloader |
| `reload` | quiesce、stop、unload 後重新 load/start，創建新的 generation 與 classloader |

沒有 `purge` 動作。需要刪除插件時使用 `remove`；插件自己的 `config/`、`state/`、`data/` 是否保留屬於數據保留策略，不應由一個含糊的別名隱式清空。

可選的 `process-restart` 插件也可以透過管理頁移除。操作會刪除已安裝包及其來源證明，保留設定、憑據、資料庫、狀態和已下載檔案。若目前進程已載入該插件，頁面會提示完整退出並重新啟動；在此之前，目前實例繼續執行。必選插件和仍有活動依賴者的插件會被拒絕移除。

更新或移除後，管理頁分別顯示磁碟安裝狀態與目前進程仍載入的版本，並提示重新啟動後生效。新包按自身內容和來源證明驗證；舊實例的驗證結果不能代替新包驗證。重新安裝後仍可使用保留的資料。

`restart` 適合重新創建服務足跡但不需要換類；代碼或資源 artifact 已變化時使用 `reload`。異步插件必須讓 `quiesce` 返回真實可等待的正 generation drain，不能用“已完成”哨兵掩蓋仍在運行的後臺任務。

## 啓用、禁用與 required 插件

啓用狀態保存在 `config/config.yaml` 的 `plugins.{pluginId}.enabled`。`hot-reload` 插件的開關直接執行 start/stop；其它策略保存狀態後給出相應重啓提示。

required 插件不能被停用或移除到不滿足狀態。required 套件缺失、損壞、不相容、離線複驗失敗或啟動失敗時，核心進入恢復模式，保留管理與修復入口。可選插件故障只隔離其能力。市集橫幅列出不可用的必選插件及診斷，並顯示預設安裝插件以便修復。

## 依賴與版本

`plugin.dependencies` 使用 PF4J 依賴表達式。安裝或啓動前，宿主會檢查所需插件是否存在、版本是否滿足以及依賴圖是否可解。升級公共依賴插件前，先確認所有消費者的版本範圍。

`plugin.requires` 表示所需核心 API 版本，不是宿主應用的營銷版本。市場把不兼容的未安裝包顯示爲 `INCOMPATIBLE`，不會嘗試加載後再碰運氣。

## 文件邊界

安裝身份由 `plugins/` 根目錄的原始 artifact 和 `plugins/provenance/` sidecar 組成。`plugins/runtime/` 只是每個 generation 的私有凍結工作區。不要：

- 在運行時手工覆蓋插件 JAR；
- 把 `plugins/runtime/` 當安裝目錄、簽名源或共享緩存；
- 只複製 artifact 而遺漏遠程來源的 provenance；
- 把私鑰放進 `plugins/`、源碼、構建輸出或日誌。

portable 安裝可以讓 `plugins/` 根本身指向符號鏈接或 Windows junction；運行時會先解析並固定真實根目錄，但仍逐個拒絕根目錄內的鏈接製品。宿主會在文件系統支持時收緊 `plugins/runtime/` 與 `plugins/provenance/` 的 POSIX 權限或 Windows ACL；FAT32、exFAT、SMB 等不支持這些能力時會記錄診斷，並繼續依賴普通文件、`NOFOLLOW`、凍結快照與哈希檢查。

完整佈局見[存儲原理](/zh-hant/storage)。

## 常見故障

### 安裝成功但頁面沒有出現

先查看安裝響應中的 `effectiveAfterRestart` 和插件策略。`process-restart` 必須完整退出並重新啓動軟件；瀏覽器刷新或後端重啓不夠。其它策略刷新管理頁，確認插件處於 `STARTED` 且貢獻沒有註冊診斷。

### 顯示不兼容

檢查 `plugin.requires`、`requiredCoreApi` 和依賴插件版本。不要手工改 descriptor 繞過版本檢查；升級宿主或安裝發佈者提供的兼容版本。

### 簽名或 provenance 失敗

重新從原倉庫下載。遠程包缺簽名、未知/撤銷 key、摘要不一致或 sidecar 與 artifact 不匹配都會 fail-closed，不會降級成本地 unsigned。

### stop/reload 一直等待

插件仍有活動調用、隊列任務、HTTP/WebSocket 客戶端或調度線程。先查看插件日誌；插件作者需要停止新接納、取消或等待任務 drain，並在子上下文關閉時釋放自有客戶端、執行器和 scheduler。

### 移除後配置還在

這是有意的數據保留。`remove` 刪除安裝包和 provenance，不等同於刪除 `config/plugins/{id}.properties`、加密憑據或 owner 數據。確認不再需要且已經備份後，再在程序停止時按 owner 精確清理。

## 開發者下一步

要創建插件、貢獻下載類型或獨立畫廊，請閱讀[第三方插件 SDK](/zh-hant/plugin-development)。從模板開始，不要複製應用殼實現類。

## 確認變更與查詢安裝結果

市集安裝前統一列出目標套件、相依套件的來源與版本、共享相依套件的使用者、衝突及重新啟動影響。執行前重新核對事實；狀態改變時須重新預覽並確認。目錄未提供新套件的生命週期資訊時，重新啟動要求以實際安裝結果為準。

各相依套件使用獨立交易。父套件失敗時，已成功安裝的相依套件仍保留，結果會列出版本及 transactionId。先核對管理頁再決定下一步。

操作記錄涵蓋下載準備到安裝結束。關閉頁面不會取消已開始的後端請求；重新開啟市集可查詢原操作。同一操作 ID 的重複執行只回傳原狀態。記錄只存於目前程序，最多 64 筆，非執行中的記錄最長保留 24 小時。程序重新啟動、過期或記錄淘汰後，結果為未知；先核對已安裝版本、診斷及交易狀態，再明確發起新操作。查詢失敗不會自動重新安裝。

## 啟動失敗修復與撤銷資訊

失敗的外部插件卡片提供換包修復，開啟既有上傳流程；請選擇相同插件 ID 的相容修復套件並完成驗證及風險確認。必選插件可換包但不可移除，可選插件移除仍須通過相依與生命週期檢查。設定、資料及未完成任務均保留，業務資料遷移仍由插件負責。可選插件故障只隔離該能力，必選能力不可用才進入全域恢復模式。

管理頁顯示最後成功重新整理時間、有效期、寬限截止及限制原因。管理員可主動重新整理已設定且啟用的來源，市集瀏覽與安裝沿用既有更新流程。管理狀態查詢不連線，沒有背景定時重新整理。離線或更新失敗會保留最後有效的已驗證快照。

`YANKED` 阻止新安裝及更新；`REVOKED` 還阻止後續載入或啟動。無有效快照或超過 24 小時寬限期時，新安裝會被拒絕。目前程序可能仍持有舊實例，重新整理撤銷資訊不會強制終止 full-trust 程式碼；須完整退出應用程式，核對修復後再啟動。
