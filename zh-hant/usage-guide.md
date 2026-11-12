# CLI 與進階參考

本頁彙整命令列啟動參數、檔名範本變數、媒體與維護工具及訪客邀請說明。一般下載與管理操作請直接查閱[快速取得](/zh-hant/quick-access)、[URL 批次下載](/zh-hant/batch-download)、[畫師批次下載](/zh-hant/user-download)、[搜尋下載](/zh-hant/search)、[小說與朗讀](/zh-hant/novel)、[作品畫廊](/zh-hant/gallery)、[排程工作](/zh-hant/scheduled-tasks)和[使用者腳本](/zh-hant/userscripts)。

## 啟動與管理參數

在完整解壓縮的發行包目錄內執行（將 `APP.jar` 替換為實際主程式 JAR 名稱；單行命令在 PowerShell 與 Bash 中通用）：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --help
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

| 參數 | 作用 |
| --- | --- |
| `--no-gui` | 不啟動桌面 GUI，適用於無頭伺服器或 Docker（首次啟動前必須先完成 `--setup`，否則以結束代碼 `78` 結束） |
| `--intro` | 啟動時在瀏覽器開啟產品介紹頁 |
| `--help`、`-h` | 印出命令列說明並結束 |
| `--setup` | 首次初始化管理員帳號、執行模式與 HTTP 代理 |
| `--change-password` | 驗證原密碼後修改管理員密碼 |
| `--reset-password` | 忘記密碼時在本機強制重設管理員密碼 |
| `--web-publications` | 離線查詢或還原頁面編輯器的已發布頁面狀態（詳見[頁面編輯器](/zh-hant/page-editor#表單驗證與資源授權)） |

執行 `--setup`、`--change-password`、`--reset-password` 等管理命令前，必須先停止使用同一資料目錄的執行個體。密碼修改或重設會使現有工作階段失效，重新啟動後請用新密碼驗證登入（詳見[首次設定](/zh-hant/first-setup)）。Windows 安裝版可直接執行 `PixivDownload.exe`；發行包同時包含 Compose（預設）和 Swing 桌面介面，切換 `app.gui-provider` 後需完整重新啟動。

## 檔名範本變數

在下載頁「下載設定 → 檔名範本」中可使用以下 11 個變數（檔名中的非法字元會自動過濾，新範本僅對後續新下載生效）：

| 變數 | 含義 |
| --- | --- |
| `{artwork_id}` | 作品 ID |
| `{artwork_title}` | 作品標題 |
| `{author_id}` | 作者 ID |
| `{author_name}` | 作者名稱 |
| `{timestamp}` | 下載時的 Unix 時間戳記（毫秒） |
| `{page}` | 目前頁面索引（從 `0` 開始） |
| `{count}` | 作品總頁數 |
| `{ai}` | AI 生成作品為 `AI`，否則為空字串 |
| `{ai+}` | `AI` 或 `Human` |
| `{R18}` | `R18`、`R18G` 或空字串 |
| `{R18+}` | `SFW`、`R18` 或 `R18G` |

範例：`{author_name}/{artwork_id}_p{page}` 會依作者名稱建立子資料夾儲存圖片。

## 自動收藏與動圖（Ugoira）處理

- **自動收藏**：在下載頁勾選「自動收藏」後，後端在作品下載完成時，使用已儲存的 Cookie（需含 `PHPSESSID`）呼叫 Pixiv API 收藏作品；使用者腳本則使用瀏覽器目前的 Pixiv 登入狀態收藏，不向後端轉送 `document.cookie`。收藏會盡力執行（best-effort），收藏失敗不會使已完成的下載變為失敗。
- **動圖（Ugoira）**：下載 ZIP 影格包並依影格順序解壓縮後，呼叫系統 `PATH` 或自動安裝的 `ffmpeg` 轉碼為所選格式（預設 WebP，可選 GIF、APNG、MP4 或保留原始 ZIP，詳見[設定參考](/zh-hant/configuration#圖片與-ugoira-儲存格式)），同時將首個影格儲存為 `_p0_thumb.jpg` 縮圖。

## 下載監控、疑似重複偵測與桌面工具

- **下載監控（`http://localhost:6999/monitor.html`）**：查看目前作用中的下載進度、依作者 / 標籤 / AI 狀態篩選或模糊搜尋歷史紀錄，以及下載趨勢圖。
- **疑似重複偵測（`http://localhost:6999/pixiv-duplicates.html`，僅管理員，需啟用 `gallery-tools`）**：依感知雜湊（dHash）查找視覺相似圖片（即使檔名或解析度不同），可調整漢明距離閾值（預設 `10`，越小越嚴格）、切換「跨作品模式 / 全庫模式」，並點擊縮圖進入詳細資料頁處理。
- **桌面 GUI 工具頁**：提供**圖片分類工具**、**資料庫目錄檢查**、**資料回填工具**、**舊資料與中繼資料匯入**、**歷史媒體處理**和 **PixivBatchDownloader 匯入支援**。

Compose 工具頁依操作方式分組。**快捷工具**包括資料回填、PixivBatchDownloader 匯入支援和本機作品匯入，使用彈窗；收起彈窗後，已啟動的任務繼續執行，可從工具頁重新查看或取消。**實用工具**包括圖片分類、目錄檢查、FFmpeg 及其工具，使用整頁工作區；退出或切換側欄頁面時請求停止目前任務，保留已完成的修改。正在寫入的工具可能需要等待目前操作結束才能退出。外掛提供的工具隨啟用狀態顯示，分類內自動排列。

!> 目錄檢查等離線維護工具會使用獨占維護工作階段，由 GUI 自動停止並恢復後端。執行前請先備份所選資料庫（PostgreSQL 還需備份伺服器端資料庫），切勿同時啟動使用同一資料目錄的另一執行個體。操作結束後請核對工具報告、後端狀態與畫廊存取，不要將「關閉工具視窗」視為執行成功。

本機作品匯入是需要後端執行的線上工具，不使用上述停機維護流程。可依路徑範本批次登記，或儲存預設後只輸入作品 ID，請見[使用說明與範例](/zh-hant/local-import)。

## 訪客邀請

訪客邀請在 `solo` 與 `multi` 模式下皆可使用。管理員在畫廊右上角點擊「邀請訪客」，設定到期時間、允許的內容分級（SFW / R18 / R18G）及標籤 / 作者白名單後，即可複製 `http://host:port/invite?code=xxx`，分享給外部訪客唯讀瀏覽。所有邀請碼可在 `http://localhost:6999/pixiv-invite-manage.html` 集中查看、停用或刪除。

邀請連結只開啟預填的兌換頁面，GET 不建立工作階段。訪客提交邀請碼並完成已發布的驗證後，宿主簽發與邀請碼不同的工作階段憑證。舊的原始邀請碼 Cookie 無法登入，須重新兌換。暫停、刪除或到期的邀請仍依既有權限規則拒絕存取，登出會撤銷當次憑證。
