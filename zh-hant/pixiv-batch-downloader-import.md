# PixivBatchDownloader 匯入支援

官方 `pixiv-batch-downloader-import` 外掛搭配專用使用者腳本，可將 PixivBatchDownloader 下載完成的插畫、漫畫、動圖和小說，以**唯讀參照**方式登記到本機畫廊。來源檔案不複製、不移動，刪除畫廊紀錄也會保留原始檔案。另支援將既有的 PixivBatchDownloader 歷史紀錄批次匯入畫廊。

瀏覽器與本程式必須在同一台機器上執行，且本程式使用單人模式（`solo`）。腳本僅連線至本機迴路服務，無需開啟瀏覽器「允許存取檔案網址」權限。

## 啟用自動匯入

一般發行包未預先安裝此外掛，離線完整包已包含。

1. 在外掛市場安裝並啟用「PixivBatchDownloader 匯入支援」，完全結束並重新啟動程式。
2. 在瀏覽器啟用 PixivBatchDownloader 與 Tampermonkey（Chrome 138+ 需在「擴充功能 → Tampermonkey → 詳細資料」開啟「允許使用者腳本」，其他版本參見 [Tampermonkey 權限說明](https://www.tampermonkey.net/faq.php?ext=dhdg&q=Q209)）。
3. 設定外部下載根目錄：可在桌面「設定 → PixivBatchDownloader 匯入支援」中選擇，或在 `config/plugins/pixiv-batch-downloader-import.properties` 中填寫絕對路徑 `pixiv-batch-downloader-import.source-root=D:/Downloads`（Windows 使用正斜線，儲存後立即生效）；也可等首次下載完成後，在彈出視窗中確認候選目錄。
4. 在瀏覽器登入本機服務後，存取 `http://localhost:6999/api/scripts/pixiv-batch-downloader-import.user.js`（或從下載頁「油猴腳本」入口）安裝並啟用匯入腳本，隨後重新整理 Pixiv 頁面。
5. 保持本機後端執行，正常使用 PixivBatchDownloader 下載；整部作品全部檔案寫入磁碟後，會自動登記到對應畫廊。

未預先設定目錄時，首個完整作品的絕對路徑會觸發桌面彈出視窗，要求確認候選目錄。候選目錄取自該作品所有檔案的共同父目錄（可能只是單部作品子目錄），請選擇去掉 PixivBatchDownloader 相對路徑後的頂層下載根（例如檔案在 `D:/Downloads/pixiv/作者/123_p0.png`、相對路徑為 `pixiv/作者`，應選 `D:/Downloads`），點擊「儲存目錄」後生效並自動重試。取消不會寫入設定，同一候選不會重複彈出視窗。

## 自動匯入狀態與統計

下載完成卻未出現在畫廊時，在 Pixiv 頁面的 Tampermonkey 選單開啟「自動匯入狀態」：

- `SOURCE_ROOT_REQUIRED`：需在桌面彈出視窗確認候選目錄，或在設定中指定下載根目錄。
- `SERVER_UNAVAILABLE`：檢查本機後端是否執行中、單人模式是否已登入，以及腳本選單「設定本機服務位址」中的連接埠是否正確。

後端暫時無法使用時，待登記紀錄儲存在 Tampermonkey 儲存空間中（最多 1,000 部作品、5,000 個檔案、8 MiB），開啟 Pixiv 頁面時會自動退避重試；缺頁、中繼資料未知、略過下載或路徑衝突的作品，不會作為完整作品登記到資料庫。上游事件依據 [PixivBatchDownloader 固定版本原始碼](https://github.com/xuejianxianzun/PixivBatchDownloader/tree/e3bbdfdd2fb452cc9073450b8aa1ad065f982b64)，同批中繼資料須先於下載成功事件抵達。

桌面首頁「已匯入的作品」依 `(作品類型, 作品 ID)` 去重統計此外掛成功匯入的作品數，儲存在 `data/pixiv-batch-downloader-import/plugin.db`（備份時請一併保留）；刪除畫廊紀錄不會扣減計數。

## 檔案格式、唯讀參照與上限

- 實際檔案必須位於已設定根目錄內，會拒絕符號連結、junction、超出目錄範圍的路徑、遺失檔案與非法檔頭。
- 圖片支援 JPEG、PNG、WebP、GIF；動圖支援 WebP、GIF、APNG、WebM、MP4、ZIP（ZIP 縮圖取首個圖片項目，原始檔案維持 ZIP）；小說支援 TXT、EPUB（即時自動匯入的正文取自同次擷取的中繼資料，暫不匯入小說封面、附件和原始發布時間，上傳時間顯示為登記時間）。
- 既有紀錄（含軟刪除）不覆寫、不重複登記；小說外掛無法使用時，會保留待處理紀錄，不回傳成功。媒體維護工具會略過外部唯讀作品；移動原始檔案後需修復路徑，程式不會猜測新位置。
- 單頁檔案上限 1 GiB，單部作品上限 8 GiB / 1,000 頁；單次 HTTP 請求上限 12 MiB、JSON 深度 20、小說正文 300 萬字元。

## 匯入歷史紀錄

安裝外掛前的歷史下載有兩種補登方式，皆沿用上述檔案格式與唯讀參照規則，既有作品（含軟刪除）會自動略過；匯入後可用資料回填工具補齊受支援欄位，但不能還原遺失的來源檔案。

歷史匯入僅處理本地紀錄與檔案。頁數依本地連續頁紀錄計算，無法確認是否與 Pixiv 目前頁數一致。成功登記後，執行中的桌面 GUI 會要求焦點，詢問是否啟動資料回填；選擇「稍後」或關閉提示不會啟動回填，也可從「工具 → 資料回填」再次執行。回填檢查畫廊中所有缺少資料的作品，透過後端補齊標題、作者、標籤等欄位，並將裁剪後的中繼資料儲存至 `metadata_json`，保留既有值。取得失敗不會撤銷匯入。小說原始檔案仍可存取，但歷史匯入與中繼資料回填不還原線上閱讀、翻譯所需的正文。

### 方式一：從使用者腳本直接讀取瀏覽器歷史（無需匯出檔案）

1. 保持本機後端執行，在外掛設定中儲存「外部下載目錄」，重新整理已登入的 Pixiv 頁面。
2. 在使用 PixivBatchDownloader 的同一瀏覽器設定檔中開啟原 Pixiv 網站，暫停外部下載，點擊 Tampermonkey 選單「讀取瀏覽器下載歷史 → 讀取並檢查」（腳本以唯讀方式分批掃描目前網站歷史資料庫，單次上限 1 GiB，期間勿修改或清空歷史）。
3. 核對待匯入、已存在及遺失 / 衝突計數（最多顯示 100 筆明細；待匯入為 `0` 時檢查即結束），點擊「開始匯入」。腳本將歷史紀錄交給本機服務，驗證原始檔案後登記；此過程不向 Pixiv 請求中繼資料，也不讀取或傳送 Pixiv Cookie。

匯入期間請保持頁面開啟；100% 表示處理完畢，不代表全部成功。取消、離開頁面或切換伺服器會停止後續處理，已登記作品會保留。暫存資料位於 `data/pixiv-batch-downloader-import/browser-history/`，正常結束或閒置約 15 分鐘後自動清理。

### 方式二：在桌面工具匯入匯出的 JSON

1. 在桌面 GUI「工具」中開啟「PixivBatchDownloader 匯入支援」。Compose 將其放在「快捷工具」中，以彈窗開啟。
2. 在 PixivBatchDownloader 中匯出歷史 JSON；若有多個分片檔案，放入同一獨立目錄並選擇該目錄（單檔上限 512 MiB，最多 64 個檔案、合計 1 GiB）。
3. 選擇來源檔案根目錄（與紀錄中的相對路徑 `n` 組合後需對應真實檔案；此次選擇不會改寫自動匯入設定），點擊「檢查並預覽」。
4. 點擊「開始匯入」並核對成功、略過與失敗計數，無需填寫 Pixiv Cookie。暫存整理目錄位於 `data/pixiv-batch-downloader-import/history-import/`，任務結束或程式正常退出時會清理。

使用「PixivBatchDownloader 匯入支援」腳本匯入歷史下載資料更方便，可依上面的方式一直接讀取瀏覽器歷史。桌面匯入也可以獨立使用；關閉匯入彈窗後任務繼續執行，需要停止時請點擊取消。
