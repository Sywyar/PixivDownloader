# 儲存與備份

PixivDownloader 將下載作品、宿主執行期檔案與外部外掛套件分目錄管理。所有相對路徑均以程式的**目前工作目錄**為基準（而非 JAR 所在目錄）；發行包啟動腳本與 Windows 捷徑會將工作目錄設為程式根目錄。

## 頂層目錄結構

| 類別 | 預設路徑 | 用途 |
| --- | --- | --- |
| 設定 | `config/` | 宿主 `config.yaml`、外掛業務設定與加密憑證 |
| 狀態 | `state/` | 初始化狀態、登入工作階段、佇列續傳狀態、GUI 引導標記與外掛狀態 |
| 資料 | `data/` | SQLite 主資料庫、外掛私有資料庫、收藏夾圖示、縮圖/TTS 快取與遷移/還原紀錄 |
| 外掛 | `plugins/` | 外部外掛安裝包、provenance 與執行期私有凍結工作區 |
| 日誌 | `log/` | GUI 與後端執行日誌 |
| 作品 | 依儲存目錄範本展開 | 插畫與小說的最終作品目錄；可依模式覆寫 |

作品中繼資料與逐頁媒體紀錄集中儲存在所選資料庫中，新下載不再在作品目錄產生 `.meta.json` 或 `.media.properties`（Ugoira 保留原始 ZIP 時仍附帶 `.frames.properties` 影格時序）。請勿將設定、資料庫、外掛套件、狀態或快取放入作品下載根目錄。

## 執行期檔案明細

### 設定、狀態與資料

| 路徑 | 內容與注意事項 |
| --- | --- |
| `config/config.yaml` | 宿主設定與 `plugins.{id}.enabled` 啟停狀態 |
| `config/plugins/{pluginId}.properties` | 對應外掛的非敏感業務設定 |
| `config/credentials/{pluginId}.properties` | 由宿主加密維護、依 owner 注入的敏感憑證封裝 |
| `config/image_classifier.properties` | 保留的舊版圖片分類設定 |
| `config/web-publications.enrolled` | 頁面編輯器正式發布登記檔案（不可當作快取清理） |
| `state/setup_config.json` | 首次初始化帳號、執行模式與登入狀態（刪除會導致需要重新初始化） |
| `state/download-workbench/` | 下載工作台批次佇列續傳狀態與版面回饋狀態 |
| `state/gui/`、`state/download_root_marker.txt` | GUI 引導標記、上次解析的下載根絕對路徑 |
| `state/{pluginId}/` | 外掛透過 `RuntimePathProvider` 取得的 owner 狀態目錄 |
| `data/pixiv_download.db` | 預設 SQLite 主資料庫（執行期可能伴隨 `-wal` / `-shm`，備份前須正常停止程式） |
| `data/collection_icons/`、`data/novel/narration-voice/` | 收藏夾自訂圖示、小說多角色朗讀參考音 |
| `data/gallery_thumbs/`、`data/tts/`、`data/backfill/` | 可重建的畫廊縮圖快取、Edge TTS 版本快取、回填時無法存取的紀錄 |
| `data/install_identity.txt` | 首次執行產生的持久安裝識別碼 UUID |
| `data/delete-staging/`、`data/database-migrations/`、`data/environment-transfers/`、`data/web-publications/` | 刪除/發布復原暫存、資料庫切換與環境遷移備份報告、已發布頁面狀態（勿當作快取刪除） |
| `data/{pluginId}/` | 外掛持久資料與 SQLite 私有資料庫 `plugin.db` |

### 外部外掛目錄（`plugins/`）

| 路徑 | 用途 |
| --- | --- |
| `plugins/*.jar`、`plugins/*.zip` | 已安裝的原始外掛套件（離線重新驗證與身分信任來源） |
| `plugins/provenance/*.pixiv-plugin-provenance` | 外掛來源、摘要、簽章與重新驗證紀錄 |
| `plugins/runtime/` | 各執行世代（generation）獨占的私有凍結工作區（非共用快取或安裝來源） |
| `plugins/.preparing/`、`.staging/`、`.transaction-cleanup/`、`.pixivdownload-runtime.lock` | 安裝交易、當機復原與執行期目錄鎖 |

可透過 JVM 屬性 `pixivdownload.plugins-dir` 指定外掛根目錄。切勿在程式執行時手動覆寫、移動或刪除 `plugins/` 下的檔案；安裝、更新與解除安裝統一透過[外掛管理](/zh-hant/plugin-management)交易執行。

## 下載作品目錄配置

插畫與小說的儲存路徑由[作品儲存目錄範本](/zh-hant/configuration?id=directory-templates)決定（插畫預設為 `pixiv-download/{artwork_id}`，小說預設為 `pixiv-download/novel-{artwork_id}`，User 等取得模式可單獨覆寫）。下表中的 `{root}` 指預設插畫範本在首個變數之前的固定前綴：

| 預設路徑 | 內容 |
| --- | --- |
| `pixiv-download/{artwork_id}/` | 單件作品、URL 批次、搜尋等插畫 / 漫畫 / 動圖（含合成的 `_p0.webp` 與 `_p0_thumb.jpg`） |
| `pixiv-download/{username}/{R18}/{artwork_id}/` | 畫師批次下載（插畫 User 模式預設範本） |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | 漫畫系列封面 |
| `pixiv-download/novel-{artwork_id}/` | 單篇小說的 TXT / HTML / EPUB 與內嵌圖片 |
| `{root}/novel-series-{seriesId}/` | 小說系列封面與合訂本 |
| `{root}/douyin/{owner}/...` | Douyin 外掛預設下載目錄（或該外掛設定的自訂目錄） |
| `{root}/_archives/{token}.zip` | 批次匯出與多人模式配額的短期壓縮檔 |

第三方下載類型外掛同樣應將作品儲存在 `{root}/{pluginId}/` 或使用者在外掛設定中指定的作品目錄。工具頁「舊資料與中繼資料匯入」可從已登記作品目錄匯入歷史 `<作品 ID>.meta.json` 快照；若無快照但路徑含作品 ID，可使用[本地作品匯入](/zh-hant/local-import)重新登記檔案參照並搭配資料回填補齊中繼資料。

## 資料庫路徑前綴與目錄搬遷

已登記的路徑前綴可用 `{N}/relative/path` 參照。修正作品目錄時，如果前綴後方還有 `..` 路徑區段，資料庫會保留原本的絕對路徑。日後單獨變更該前綴，作品仍指向登記時的目錄。

- **`{0}` 符號根目錄**：當預設插畫範本的固定前綴為相對路徑（如 `pixiv-download/{artwork_id}` 中的 `pixiv-download`）時，該前綴下的檔案記錄為 `{0}/...`，啟動時依「目前工作目錄 + 相對前綴」動態解析。**整體搬遷整個程式目錄及該相對下載目錄時，無需手動修改資料庫路徑。**
- **一般 `{N}` 前綴**：當下載根或收藏夾使用絕對路徑時，紀錄會參照 `path_prefixes` 表中的絕對路徑前綴。 依原路徑還原時，即使兩個歷史前綴指向同一個目錄，也會保留各自的 ID 和作品參照。手動變更對應時，請選擇與其他前綴不同的目標目錄。

跨系統還原時，程式依來源系統的路徑規則比對作品記錄。變更一般前綴的對應時，即使經過符號連結，也不能與另一個一般前綴指向同一個現有目錄。還原和桌面下載根目錄遷移都會依檔案系統解析符號連結之後的 `..`。在 POSIX 上，登記或變更前綴、固定符號根目錄、同步下載根目錄及修正單件作品目錄時，都會保留目錄名稱結尾的空格。

還原作品時，POSIX 檔名中的反斜線會保留原樣，Windows 前綴下舊作品參照中的反斜線則視為目錄分隔符號。桌面下載根目錄的名稱不能含有大括號。在 POSIX 上，下載根目錄的名稱也不能含有反斜線字元，可以保留開頭及結尾的空格。

在 Windows 上還原 POSIX 備份時，如果檔名含有反斜線字元（例如單一檔名 `volume\one.jpg`）或冒號（例如 `portrait:blue.jpg`），還原會在取代目標資料庫前停止。可從還原記錄查看資料表名稱、欄名和封存檔中的列號，修正來源檔名及登錄路徑後再匯出。重新掃描已還原的作品時，指向同一檔案的頁面會略過，新增頁面時會保留原有的參照。

還原時會保留頁面檔名的大小寫。如果兩個不同的來源檔案在目標目錄中對應同一路徑，還原會在取代資料庫前停止，並在記錄中列出雙方的作品及頁碼。跨系統移轉時，請先在來源端將外部作品目錄登記為路徑前綴，重新匯出後再指定目標目錄。還原失敗時，可用桌面提示中的工作 ID 搜尋應用程式記錄。

對應作品目錄時，請將頁面檔案放在所屬的作品根目錄下。尚未複製的頁面可在還原後補上。新備份會記錄來源端可確認的頁面檔案身分，同一檔案以不同大小寫登記時，還原會保留各筆登記的原始拼寫。若確認後目標設定或資料已變更，請重新預覽並確認。

僅搬移作品下載根目錄時：

1. 正常停止程式。
2. 在檔案系統中將作品目錄移動到新位置。
3. 啟動程式，在桌面 GUI「狀態」頁開啟「遷移下載目錄」，選擇實際新位置並確認同步更新 `config.yaml` 與資料庫路徑前綴（遷移工具只更新路徑參照，不會替你移動磁碟檔案）。
4. 重新啟動後，抽查歷史作品、畫廊預覽與一次新下載。

SQLite 與 PostgreSQL 之間的資料庫引擎切換屬於獨立流程，見[設定參考中的資料庫切換](/zh-hant/configuration#主資料庫與引擎切換)。

## 備份與還原

停止程式後，完整備份應包含：

1. `config/`、`state/`、`data/`、`plugins/`；
2. `download.root-folder`、收藏夾自訂下載目錄及第三方外掛自訂作品目錄；
3. 若使用 PostgreSQL，還須備份伺服器上的主資料庫與全部外掛私有 schema；
4. 若使用[匯入外部下載](/zh-hant/pixiv-batch-downloader-import)或[本機作品匯入](/zh-hant/local-import)，還須備份以唯讀方式參照的外部來源目錄；
5. 若需跨機器/跨部署還原 `config/credentials/` 中的加密憑證，須同時保留對應的憑證主金鑰（否則需在目標環境重新輸入憑證）。

還原後，請依序驗證管理員登入、畫廊與小說正文讀取、排程工作狀態及一次新下載。
