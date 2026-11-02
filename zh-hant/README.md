# PixivDownloader 使用文件

PixivDownloader 將 Pixiv 插畫、漫畫、動圖和小說下載到自己的電腦或伺服器，並透過本地畫廊整理作品。你可以使用桌面介面、瀏覽器下載頁或 Tampermonkey 腳本操作；需要存取受限作品時，須提供自己的 Pixiv Cookie。

## 第一次使用

1. [安裝與啟動](/zh-hant/installation)：選擇 Windows 安裝程式、Java 發行套件或 Docker 部署。
2. [首次設定](/zh-hant/first-setup)：建立本程式的管理員帳號，設定後端代理。
3. [第一次下載](/zh-hant/first-download)：儲存 Cookie、新增作品，檢查檔案和畫廊紀錄。

預設桌面介面為 Compose，發行套件同時包含 Swing。介面支援簡體中文、繁體中文、英文、日文和韓文。預設資料庫為 SQLite；需要 PostgreSQL 時，先安裝對應資料庫外掛，再透過設定頁檢查和遷移資料。

## 依任務查找

| 任務 | 文件 |
| --- | --- |
| 下載自己的收藏、關注新作或珍藏集 | [快捷取得](/zh-hant/quick-access) |
| 下載一組作品連結或 ID | [URL 批次下載](/zh-hant/batch-download) |
| 取得某位畫師的作品 | [畫師批次下載](/zh-hant/user-download) |
| 搜尋 Pixiv 後選擇作品下載 | [搜尋下載](/zh-hant/search) |
| 儲存小說、合訂 EPUB、閱讀、翻譯或聽書 | [小說下載](/zh-hant/novel) |
| 瀏覽、篩選、匯出和刪除本地作品 | [作品畫廊](/zh-hant/gallery) |
| 定期追蹤畫師、搜尋結果或系列 | [排程任務](/zh-hant/scheduled-tasks) |
| 在 Pixiv 網頁使用下載輔助功能 | [使用者腳本](/zh-hant/userscripts) |
| 將 PixivBatchDownloader 的新下載加入畫廊 | [匯入支援](/zh-hant/pixiv-batch-downloader-import) |
| 修改設定、代理、資料庫或媒體格式 | [設定參考](/zh-hant/configuration) |
| 安裝、啟停或修復外掛 | [外掛管理](/zh-hant/plugin-management) |
| 瞭解網路請求、傳送的資料和關閉方式 | [網路存取與第三方服務](/zh-hant/network-access) |
| 備份、還原或移動作品目錄 | [儲存原理](/zh-hant/storage) |
| 使用啟動參數、檔名範本、維護工具和訪客邀請 | [進階使用指南](/zh-hant/usage-guide) |
| 排查啟動、連線或下載問題 | [常見問題](/zh-hant/faq) |

## 開發外掛或貢獻程式碼

- [第三方外掛 SDK](/zh-hant/plugin-development)：選擇 SDK、建立專案、使用擴充契約、簽章和投稿。
- [主儲存庫開發指南](/zh-hant/development)：準備環境、建置、測試和提交 PR。
- [SDK 下載](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases)與[版本化 API 參考](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)。

## 取得版本與協助

從 [GitHub Releases](https://github.com/Sywyar/PixivDownloader/releases) 取得程式，在 [CHANGELOG](https://github.com/Sywyar/PixivDownloader/blob/master/CHANGELOG.md) 查看發行變更。排查後仍無法解決時，到 [Issues](https://github.com/Sywyar/PixivDownloader/issues) 提供版本、操作步驟、實際結果及移除憑證後的紀錄。

程式預設在 `http://localhost:6999/` 提供 Web 介面。可用頁面取決於已啟用的外掛；看到修復頁面時，先檢查必要外掛與資料庫狀態。

專案由 [Sywyar](https://github.com/Sywyar) 維護，軟體授權見 [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)及隨附的[外掛連結例外](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)。下載內容的著作權仍屬相應權利人。本專案與 Pixiv 無隸屬關係；請遵守內容授權與服務規則，合理控制請求頻率，並妥善保管 Cookie。
