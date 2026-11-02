# PixivDownloader 使用文件

PixivDownloader 將 Pixiv 插畫、漫畫、動圖和小說下載到本機電腦或伺服器，並透過本地畫廊管理作品。支援桌面介面、瀏覽器下載頁和 Tampermonkey 腳本操作；下載受限內容需設定你自己的 Pixiv Cookie。

## 首次使用

1. [安裝與啟動](/zh-hant/installation)：選擇 Windows 安裝包、Java 發行包或 Docker 部署並完整解壓縮。
2. [首次設定](/zh-hant/first-setup)：建立本程式的管理員帳號並設定後端代理。
3. [第一次下載](/zh-hant/first-download)：儲存 Cookie、下載單件作品，並在本地目錄與畫廊核對結果。

預設桌面介面為 Compose，發行包同時包含 Swing；支援簡體中文、繁體中文、英文、日文和韓文。預設資料庫為 SQLite；使用 PostgreSQL 時需先安裝對應資料庫外掛，並在設定頁檢查與遷移資料。

## 依任務查找

| 任務 | 文件 |
| --- | --- |
| 下載自己的收藏、作品、關注新作或珍藏集 | [快速取得](/zh-hant/quick-access) |
| 貼上一組作品連結或 ID 下載 | [URL 批次下載](/zh-hant/batch-download) |
| 下載指定畫師的作品 | [畫師批次下載](/zh-hant/user-download) |
| 依關鍵字搜尋 Pixiv 並下載 | [搜尋下載](/zh-hant/search) |
| 儲存小說、合訂 EPUB、閱讀、翻譯或朗讀 | [小說與朗讀](/zh-hant/novel) |
| 定期自動探索並下載新作 | [排程工作](/zh-hant/scheduled-tasks) |
| 在 Pixiv 網頁直接呼叫下載輔助 | [使用者腳本](/zh-hant/userscripts) |
| 將 PixivBatchDownloader 的下載登記到畫廊 | [匯入外部下載](/zh-hant/pixiv-batch-downloader-import) |
| 瀏覽、篩選、匯出與刪除已登記作品 | [作品畫廊](/zh-hant/gallery) |
| 修改設定、代理、資料庫與媒體格式 | [設定參考](/zh-hant/configuration) |
| 備份、還原或搬遷作品與資料目錄 | [儲存與備份](/zh-hant/storage) |
| 查詢啟動參數、檔名範本、維護工具與訪客邀請 | [CLI 與進階參考](/zh-hant/usage-guide) |
| 核對外部請求目標、傳送資料與關閉方式 | [網路存取與第三方服務](/zh-hant/network-access) |
| 排解啟動、連線、Cookie 或下載問題 | [常見問題](/zh-hant/faq) |
| 安裝、更新、啟停或修復外掛 | [外掛管理](/zh-hant/plugin-management) |
| 編輯頁面版面、樣式與自訂元件 | [頁面編輯器](/zh-hant/page-editor) |
| 為登入等表單設定線上或離線驗證碼 | [操作驗證碼](/zh-hant/captcha) |

## 開發與參與

- [第三方外掛 SDK](/zh-hant/plugin-development)：使用範本建立專案、接入擴充契約、簽署與投稿。
- [主儲存庫開發](/zh-hant/development)：準備環境、建置、測試與提交 PR。
- [SDK 下載](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) · [版本化 API 參考](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)

## 版本與求助

從 [GitHub Releases](https://github.com/Sywyar/PixivDownloader/releases) 下載發行包，在 [CHANGELOG](https://github.com/Sywyar/PixivDownloader/blob/master/CHANGELOG.md) 查看變更紀錄。排解後仍未解決時，在 [Issues](https://github.com/Sywyar/PixivDownloader/issues) 提交版本、重現步驟、實際結果與已移除敏感資訊的日誌（勿包含 Cookie、密碼或權杖）。

預設 Web 位址為 `http://localhost:6999/`。可用頁面取決於已啟用外掛；出現復原頁時，先檢查必要外掛與所選資料庫狀態。

本專案由 [Sywyar](https://github.com/Sywyar) 維護，採用 [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) 及隨附的[外掛連結例外](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)。下載作品的著作權歸原作者所有，專案與 Pixiv 無隸屬關係；請遵守服務條款並合理控制請求頻率。
