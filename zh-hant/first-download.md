# 第一次下載

本教學會完成單件 Pixiv 作品的取得、下載，以及本地檔案與畫廊確認。開始前請先完成[安裝與啟動](/zh-hant/installation)和[首次設定](/zh-hant/first-setup)。下文作品 ID 僅為格式範例，請替換為你有權存取的實際作品。

## 1. 開啟下載工作台

在瀏覽器存取 `http://localhost:6999/pixiv-batch.html`（也可從桌面 GUI「狀態」頁點擊「批次下載」開啟），使用管理員帳號登入。新環境預設開啟新版版面 `pixiv-batch-alt.html`；頁面頂部可切換新舊版面，偏好設定會自動儲存。

首次以管理員登入時會自動啟動跨頁面新手引導，依序介紹稱呼設定、Pixiv 連線偵測、Cookie 與使用者腳本、單件作品加入佇列下載及畫廊瀏覽。引導中填寫的稱呼用於畫廊側欄卡片和系統通知郵件問候語，留空時預設顯示「管理員」。引導可隨時略過，也可從頁面右下角「操作指引」重新開啟。

## 2. 儲存 Pixiv Cookie（依需求）

公開全年齡插畫可不設定 Cookie 直接下載；**R-18 / R-18G 作品、搜尋下載、快速取得及需登入才能看見的作品，必須設定含有效 `PHPSESSID` 的 Cookie**（尚未設定時會略過受限作品）。先在瀏覽器登入 [Pixiv](https://www.pixiv.net/)，再任選以下一種方式取得：

### 方式 A：瀏覽器開發者工具（建議，無需額外擴充功能權限）

1. 在已登入的 Pixiv 頁面按 `F12` 開啟開發者工具。
2. **Application（應用程式）面板**：展開左側 `Storage → Cookies → https://www.pixiv.net`，找到 `PHPSESSID`，複製其 `Value`，組成 `PHPSESSID=複製的值`。
3. 或在 **Network（網路）面板**：重新整理頁面，點擊任一發往 `www.pixiv.net` 的請求，複製 `Request Headers → Cookie` 的完整值。
4. 在下載頁展開「Cookie」，格式選擇 **Header String**，貼上內容並點擊「儲存 Cookie」。

### 方式 B：Cookie-Editor 擴充功能

1. 安裝 [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm)，在已登入的 Pixiv 頁面點擊擴充功能圖示。
2. 點擊 `Export → Netscape` 複製匯出內容。
3. 在下載頁「Cookie」中選擇 **Netscape** 格式，貼上並儲存。

### 方式 C：使用者腳本「一鍵匯入 Cookie」

1. 安裝並啟用「體驗增強工具箱 (Toolbox)」腳本（見[使用者腳本](/zh-hant/userscripts)）。
2. 在 Tampermonkey「設定 → 設定模式：進階 → 安全」中，將「允許腳本存取 Cookie」暫時設為 `全部 / All`。
3. 在下載頁 Cookie 區域點擊「一鍵匯入 Cookie」，腳本會讀取 `PHPSESSID` 並回傳後端。

!> `PHPSESSID` 是 HttpOnly Cookie，Tampermonkey 預設無法讀取；但將權限設為 `全部 / All` 後，瀏覽器內所有使用者腳本都能讀取你的登入憑證。建議匯入成功後立即改回 `All except HttpOnly`，或優先使用上面的開發者工具方式。請勿將 Cookie 貼入截圖、日誌或 Issue。

## 3. 新增作品並開始下載

1. 在下載頁選擇「批次匯入單一作品」。
2. 貼上作品連結或純數字插畫 ID（每行一筆）：

   ```text
   https://www.pixiv.net/artworks/12345678
   ```

3. 點擊「取得資訊」，確認預覽中的作品標題與類型。
4. 將作品加入佇列並點擊「開始下載」。
5. 在「下載佇列」查看狀態：等待中、下載中、已完成、已存在而略過與失敗會分別標示；失敗項目可點擊查看具體原因。

## 4. 核對本地檔案與畫廊

在桌面 GUI「狀態」頁查看目前的「下載目錄」。預設路徑為程式工作目錄下的 `pixiv-download/`（取決於啟動時的工作目錄或收藏夾另行指定的目錄）：

```text
pixiv-download/
└── 12345678/
    ├── 12345678_p0.jpg
    └── 12345678_p1.jpg
```

下載根目錄由 `config/config.yaml` 的 `download.root-folder` 控制；修改設定不會移動既有檔案，搬遷目錄請依[儲存與備份](/zh-hant/storage)操作。啟用 `gallery` 外掛時，存取 `http://localhost:6999/pixiv-gallery.html` 即可瀏覽剛登記到資料庫的作品（詳見[作品畫廊](/zh-hant/gallery)）。

## 常見問題排解

- **提示需要 Cookie 或 R-18 被略過**：確認已儲存包含有效 `PHPSESSID` 的 Cookie，且對應 Pixiv 帳號已在網頁端開啟 R-18 顯示設定。
- **代理連線失敗**：確認代理軟體已啟動，並在桌面設定頁核對 `proxy.host` 與 `proxy.port`（瀏覽器能開啟 Pixiv 不代表後端代理設定正確）。
- **提示作品不存在**：在 Pixiv 原站確認作品是否已被刪除、設為私人或僅好P友可見。
- **下載完成但找不到檔案**：在桌面 GUI「狀態」頁核對實際工作目錄與下載目錄，或檢查下載時是否選擇了自訂目錄的本地收藏夾。

需要下載更多內容時，可繼續查看 [URL 批次下載](/zh-hant/batch-download)、[畫師批次下載](/zh-hant/user-download)、[搜尋下載](/zh-hant/search)或[小說與朗讀](/zh-hant/novel)。
