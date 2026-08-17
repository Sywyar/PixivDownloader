# 存儲原理

PixivDownloader 把下載作品、宿主運行期文件和外置插件安裝包分開管理。路徑都相對於程序的**當前工作目錄**，不是相對於 JAR 所在目錄；發行包的啓動腳本和 Windows 快捷方式會把工作目錄設爲發行目錄。

## 頂層目錄

| 類別 | 默認路徑 | 內容 |
| --- | --- | --- |
| 配置 | `config/` | 宿主配置、插件業務配置和加密憑據 |
| 狀態 | `state/` | 安裝狀態、隊列斷點、GUI 標記和插件可恢復狀態 |
| 數據 | `data/` | SQLite、用戶資源、緩存和插件持久數據 |
| 插件 | `plugins/` | 外置插件原始 artifact、provenance 和運行期凍結副本 |
| 日誌 | `log/` | GUI 與後端日誌 |
| 下載作品 | `{rootFolder}/` | `download.root-folder` 指定的作品產物 |

`download.root-folder` 默認是相對路徑 `pixiv-download`。它只存作品本身、作品元數據 sidecar 和臨時導出歸檔；配置、數據庫、插件包、狀態和緩存不應寫入這裏。

## 工作目錄佈局

### 配置

| 路徑 | 用途 |
| --- | --- |
| `config/config.yaml` | 宿主配置和 `plugins.{id}.enabled` 狀態 |
| `config/plugins/{pluginId}.properties` | 對應插件的非敏感業務配置 |
| `config/credentials/{pluginId}.properties` | 宿主加密維護、只注入對應插件的憑據信封 |
| `config/image_classifier.properties` | 圖片分類工具的目標目錄設置 |

插件配置和憑據的所有權規則見[配置參考](/zh-hant/configuration)。不要手工交換、合併或重命名不同 owner 的文件。

### 狀態

| 路徑 | 用途 |
| --- | --- |
| `state/setup_config.json` | 首次安裝、運行模式和登錄狀態 |
| `state/download-workbench/batch_state.json` | 下載工作臺的批量隊列斷點 |
| `state/download-workbench/layout-feedback-state.json` | 下載工作臺佈局反饋去重狀態 |
| `state/gui/` | GUI 引導與代理步驟標記 |
| `state/download_root_marker.txt` | 上次解析出的下載根絕對路徑 |
| `state/{pluginId}/` | 插件通過 `RuntimePathProvider` 取得的 owner 狀態根；按需創建 |

刪除狀態不一定只會“重新生成”：可能導致重新安裝、重新登錄、隊列斷點或插件狀態丟失。清理前先確認具體文件的 owner。

### 數據

| 路徑 | 用途 |
| --- | --- |
| `data/pixiv_download.db` | SQLite 主庫；運行時還可能有 `-wal` / `-shm` 文件 |
| `data/collection_icons/{id}.{ext}` | 收藏夾自定義圖標 |
| `data/gallery_thumbs/{artworkId}/p{n}.{ext}` | 可重建的畫廊縮略圖緩存 |
| `data/tts/chromium-version.txt` | TTS 插件的 Edge TTS Chromium 版本緩存 |
| `data/novel/narration-voice/{castId}/{characterId}.{ext}` | 小說插件的角色參考音 |
| `data/backfill/unreachable.json` | 回填工具的不可達作品記錄 |
| `data/install_identity.txt` | 首次運行生成並永久複用的安裝 UUID |
| `data/delete-staging/{operationId}/` | 刪除作品時用於失敗回滾的原子暫存區 |
| `data/{pluginId}/` | 插件通過 `RuntimePathProvider` 取得的 owner 數據根；按需創建 |

主庫保存作品事實、路徑引用、歷史和已安裝功能寫入的領域數據。插件私有表仍由對應插件負責 schema 與生命週期。不要只複製 `.db` 而遺漏活躍的 WAL 文件；備份前應正常關閉程序。

### 外置插件

| 路徑 | 用途 |
| --- | --- |
| `plugins/*.jar`、`plugins/*.zip` | 已安裝的原始插件 artifact；管理身份和離線複驗的信任源 |
| `plugins/provenance/<artifact>.pixiv-plugin-provenance` | 來源、摘要、簽名和最後驗證結果 |
| `plugins/runtime/` | 每個存活 generation 的隨機私有凍結工作區，不是共享緩存或安裝源 |
| `plugins/.preparing/`、`plugins/.staging/`、`plugins/.transaction-cleanup/` | 安裝事務與崩潰恢復的受管目錄 |
| `plugins/.pixivdownload-runtime.lock` | 運行期目錄 lease |

可以用系統屬性 `pixivdownload.plugins-dir` 覆蓋插件根。運行時不會因爲目錄缺失而自動創建它；缺失會形成診斷，核心殼仍可進入恢復流程。

不要在程序運行時覆蓋、移動或刪除 `plugins/` 下的文件。安裝、升級、移除和回滾必須走插件管理生命週期，使 artifact 與 provenance 一起事務化處理。`plugins/runtime/` 可由已驗證的安裝 artifact 重建，但不是可以被其它進程複用的下載緩存。

## 下載作品佈局

常見路徑如下；插件可以在自身作品目錄內定義更細的結構。

| 路徑 | 內容 |
| --- | --- |
| `{root}/{artworkId}/` | 單作品、URL 批量、搜索等 Pixiv 插畫下載 |
| `{root}/{artist}/{artworkId}/` | 畫師批量下載；`download.user-flat-folder=true` 時省略畫師層 |
| `{root}/{artworkId}/{filename}_p0.webp` + `..._p0_thumb.jpg` | 動圖合成後的 WebP 與首幀縮略圖 |
| `{root}/{artworkId}/{artworkId}.meta.json` | 網頁、油猴腳本和計劃任務下載時從已有響應生成的 Pixiv 結構性元數據，不會額外請求 Pixiv；隨作品移動 / 刪除，且不計入配額打包或小說導出（小說使用 `novel-{novelId}/{novelId}.meta.json`） |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | Pixiv 漫畫系列封面 |
| `{root}/novel-{novelId}/` | 單本小說的 TXT/HTML/EPUB 與相關作品文件 |
| `{root}/novel-series-{seriesId}/` | 小說系列封面和可選合訂文件 |
| `{root}/douyin/{owner}/...` | Douyin 插件的默認下載位置 |
| `{root}/_archives/{token}.zip` | 多人模式配額和畫廊導出的短期歸檔 |

Douyin 的默認根由 `DownloadSettings.getRootFolder()` 加上 `douyin` 得到，然後按請求 owner 隔離；插件配置 `douyin.download.directory` 非空時改用該目錄。它不會使用舊的 `data/douyin/downloads`。收藏夾也可以配置獨立的作品下載根，該路徑可能位於默認下載根之外。

第三方下載類型同樣應把作品寫入 `download.root-folder` 下以插件 id 命名的目錄，或寫入用戶在該插件配置中明確選擇的作品目錄。`state/{pluginId}` 和 `data/{pluginId}` 只放輔助狀態與數據，不能替代作品目錄。

## 數據庫路徑編碼

數據庫不會爲每條記錄重複保存長絕對路徑，而是使用前綴引用：

```text
{N}/relative/path
```

`N>0` 指向 `path_prefixes` 中的一條絕對路徑。修改這條前綴即可讓所有引用同時指向新的根。

### `{0}` 符號根

當 `download.root-folder` 是相對路徑時，下載根內的記錄可寫爲 `{0}/...`。`{0}` 每次啓動都解析爲“當前工作目錄 + 當前相對下載根”，因此將整個發行目錄連同 `pixiv-download/` 一起搬遷後，歷史記錄仍能定位作品。

當下載根是絕對路徑時，記錄使用普通 `{N}` 前綴。此時移動作品目錄後，應使用 GUI 狀態頁的“遷移下載目錄”更新記錄。

`state/download_root_marker.txt` 保存上次解析結果，用於發現“只改配置、沒有搬文件”的情況。遷移工具只更新配置和數據庫路徑引用，**不會移動磁盤文件**。

## 搬遷

### 整體搬遷

保持 `download.root-folder` 爲相對路徑，關閉程序後移動整個發行目錄。啓動腳本應從新目錄運行，使工作目錄、運行期數據和 `{0}` 一起遷移。

### 只移動下載根

1. 正常關閉程序。
2. 在文件系統中移動作品目錄。
3. 從 GUI 狀態頁打開“遷移下載目錄”，選擇實際新位置並決定是否同步修改 `config.yaml`。
4. 按提示重啓並抽查歷史作品、畫廊和新下載。

不要先手工改 `download.root-folder` 再期待程序移動文件；它不會這樣做。

## 備份與恢復

完整備份建議包含：

- `config/`：包括插件業務配置和加密憑據；
- `state/`：保留安裝、登錄、隊列和插件狀態；
- `data/`：程序關閉後連同數據庫一起復制；
- `plugins/`：保留第三方/按需插件、簽名和 provenance；
- `download.root-folder` 以及收藏夾或插件配置的其它作品目錄。

`log/` 通常只在排障時需要。`data/gallery_thumbs/` 和 `plugins/runtime/` 可以從其它持久數據重建，但爲了簡化恢復可以隨完整目錄一起備份。

恢復時保持相同的相對佈局或通過遷移工具更新絕對路徑。加密憑據還依賴生成這些信封的憑據主密鑰；跨不同構建/部署恢復前確認密鑰兼容，否則應在目標環境重新錄入憑據。
