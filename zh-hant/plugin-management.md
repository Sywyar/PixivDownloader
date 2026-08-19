# 插件管理

插件管理頁位於 `/plugin-manage.html`，插件市場位於 `/plugin-market.html`。兩者最終使用同一套包校驗、安裝事務和生命週期協調器；本地上傳不是繞過運行時邊界的另一套安裝器。

## 安全模型

外置插件與宿主運行在同一個 JVM 中，當前沒有進程或 OS 沙箱。簽名能夠證明 artifact 來自受信發佈者且字節未被篡改，但不能證明插件行爲無害。只安裝你信任的發佈者和倉庫。

宿主在加載前會檢查：

- 包大小、壓縮比、路徑和 JAR/ZIP 結構；
- `plugin.properties` 的 id、版本、核心 API 要求和依賴；
- SHA-256、結構化 Ed25519 簽名和 provenance；
- 當前插件 API 兼容性、依賴版本與 required 插件約束。

校驗、描述符解析和加載都使用同一份有界凍結字節。通過校驗後不會重新打開可被外部進程替換的公開安裝路徑。

## 安裝來源

### 發行包預置

標準發行包在 `plugins/` 預置 required 和 default-installed 官方插件。Douyin 是按需安裝插件：標準包不預置，full-offline 包預置，也可以從插件市場安裝。預置仍是獨立 artifact，不會合入核心 Boot JAR。

### 本地上傳

在插件管理頁同時選擇 `.jar`（或兼容 `.zip`）與對應的 detached `.sig` 文件。非插件開發模式要求籤名通過程序內置的官方信任根驗證；缺少簽名、簽名格式錯誤、簽名對應其它 artifact，或簽名來自非官方 key 時都會 fail-closed。需要信任自有 key 的第三方分發應配置自定義倉庫，不應把本地上傳當成自定義信任根入口。

通過驗籤的本地包仍記錄爲 `LOCAL_UPLOAD` 來源，同時保留簽名和 `VERIFIED` provenance，供啓動時離線複驗；管理頁會顯示官方驗證狀態，但不會把來源僞裝成遠程倉庫。只有顯式插件開發模式允許省略 `.sig`，此時 provenance 爲 `LOCAL_UPLOAD / UNSIGNED_ALLOWED`。遠程倉庫無論運行模式如何都不能省略簽名。

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

“安裝中”只是瀏覽器請求在途狀態，最終結果以後端響應和刷新後的真實運行時狀態爲準。

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

官方 `gui-swing` 是默認安裝的默認桌面 provider，`gui-compose` 按需安裝。二者都只渲染應用殼提供的同一份完整聲明式 UI 文檔，均爲 `process-restart`；安裝、更新、啓停、移除或在“配置 → 界面”切換 provider 後都必須完整退出並重新啓動軟件。

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

`restart` 適合重新創建服務足跡但不需要換類；代碼或資源 artifact 已變化時使用 `reload`。異步插件必須讓 `quiesce` 返回真實可等待的正 generation drain，不能用“已完成”哨兵掩蓋仍在運行的後臺任務。

## 啓用、禁用與 required 插件

啓用狀態保存在 `config/config.yaml` 的 `plugins.{pluginId}.enabled`。`hot-reload` 插件的開關直接執行 start/stop；其它策略保存狀態後給出相應重啓提示。

required 插件不能被禁用或移除到不滿足狀態。required 包缺失、損壞、不兼容或離線複驗失敗，或者任意插件在啓動時崩潰，核心殼都會進入恢復模式，不開放依賴插件的業務路由。插件市場會在橫幅中列出缺失的必裝插件，或指出啓動失敗的插件及其診斷原因，並自動顯示默認安裝插件以便修復。

## 依賴與版本

`plugin.dependencies` 使用 PF4J 依賴表達式。安裝或啓動前，宿主會檢查所需插件是否存在、版本是否滿足以及依賴圖是否可解。升級公共依賴插件前，先確認所有消費者的版本範圍。

`plugin.requires` 表示所需核心 API 版本，不是宿主應用的營銷版本。市場把不兼容的未安裝包顯示爲 `INCOMPATIBLE`，不會嘗試加載後再碰運氣。

## 文件邊界

安裝身份由 `plugins/` 根目錄的原始 artifact 和 `plugins/provenance/` sidecar 組成。`plugins/runtime/` 只是每個 generation 的私有凍結工作區。不要：

- 在運行時手工覆蓋插件 JAR；
- 把 `plugins/runtime/` 當安裝目錄、簽名源或共享緩存；
- 只複製 artifact 而遺漏遠程來源的 provenance；
- 把私鑰放進 `plugins/`、源碼、構建輸出或日誌。

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
