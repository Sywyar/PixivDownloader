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
| 作品 | `{rootFolder}/` | `download.root-folder`（預設 `pixiv-download`）指定的作品檔案 |

作品中繼資料與逐頁媒體紀錄集中儲存在所選資料庫中，新下載不再在作品目錄產生 `.meta.json` 或 `.media.properties`（Ugoira 保留原始 ZIP 時仍附帶 `.frames.properties` 影格時序）。請勿將設定、資料庫、外掛套件、狀態或快取放入作品下載根目錄。

## 執行期檔案明細

### 設定、狀態與資料

| 路徑 | 內容與注意事項 |
| --- | --- |
| `config/config.yaml` | 宿主設定與 `plugins.{id}.enabled` 啟停狀態 |
| `config/plugins/{pluginId}.properties` | 對應外掛的非敏感業務設定 |
| `config/credentials/{pluginId}.properties` | 由宿主加密維護、依 owner 注入的敏感憑證封裝 |
| `config/image_classifier.properties` | 圖片分類工具目錄設定 |
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

| 預設路徑 | 內容 |
| --- | --- |
| `{root}/{artworkId}/` | 單件作品、URL 批次、搜尋等插畫 / 漫畫 / 動圖（含合成的 `_p0.webp` 與 `_p0_thumb.jpg`） |
| `{root}/{artist}/{artworkId}/` | 畫師批次下載（`download.user-flat-folder=true` 時省略 `{artist}/` 層） |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | 漫畫系列封面 |
| `{root}/novel-{novelId}/` | 單篇小說的 TXT / HTML / EPUB 與內嵌圖片 |
| `{root}/novel-series-{seriesId}/` | 小說系列封面與合訂本 |
| `{root}/douyin/{owner}/...` | Douyin 外掛預設下載目錄（或該外掛設定的自訂目錄） |
| `{root}/_archives/{token}.zip` | 批次匯出與多人模式配額的短期壓縮檔 |

第三方下載類型外掛同樣應將作品儲存在 `{root}/{pluginId}/` 或使用者在外掛設定中指定的作品目錄。工具頁「舊資料與中繼資料匯入」可從已登記作品目錄匯入歷史 `<作品 ID>.meta.json` 快照。沒有快照但路徑含作品 ID 時，可透過[本機作品匯入](/zh-hant/local-import)重建檔案參照；路徑中沒有的中繼資料仍須補全。

## 資料庫路徑前綴與目錄搬遷

資料庫將檔案路徑儲存為 `{N}/relative/path`：

- **`{0}` 符號根**：當 `download.root-folder` 為相對路徑（如預設的 `pixiv-download`）時，下載根內的檔案記錄為 `{0}/...`，每次啟動動態解析為「目前工作目錄 + 相對下載根」。**整體搬遷整個程式目錄（含 `pixiv-download/`）時，無需手動修改資料庫路徑。**
- **一般 `{N}` 前綴**：當下載根或收藏夾使用絕對路徑時，紀錄會參照 `path_prefixes` 表中的絕對路徑前綴。

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
