# 使用者腳本

透過 Tampermonkey 腳本可在 Pixiv 網頁直接勾選、擷取作品，或將任務傳送至本地後端。使用者腳本為選用項目，不安裝腳本也可在 `pixiv-batch.html` 完成批次下載。

腳本目錄與安裝 API 由應用程式宿主提供，不依賴 `download-workbench`。宿主僅列出目前已啟用外掛宣告的腳本；停用下載工作台會移除其下載輔助腳本，其他外掛（如外部匯入外掛）宣告的腳本仍可安裝。單人模式連線至後端需先登入本程式；`@updateURL` 指向後端時，不保證 Tampermonkey 背景更新請求會攜帶登入工作階段。

## 前提與腳本清單

瀏覽器需安裝 [Tampermonkey](https://www.tampermonkey.net/)。使用 Java 後端版腳本或工具箱時，後端必須執行中且已在單人模式下登入；純瀏覽器 `Local Download` 無需後端，但不會寫入中繼資料或登記畫廊。

| 腳本名稱 | 生效位置與用途 |
| --- | --- |
| All-in-One 整合包 | 合併內建的下載輔助腳本與工具箱（不含外部匯入等其他外掛腳本） |
| 頁面批次下載器 (Page Scrape) | 在搜尋、關注動態、排行榜等清單頁勾選縮圖，批次加入佇列 |
| User 批次下載器 (User Batch) | 在畫師首頁（`/users/<id>` 及子頁）依插畫 / 小說一鍵將全部作品加入佇列 |
| URL 批次匯入 (URL Batch) | 在任意 Pixiv 頁面開啟浮動面板，貼上 URL 批次下載 |
| 單件作品下載器 (Java 後端版) | 在單件作品頁（`/artworks/<id>`）經後端下載，保留中繼資料與自動收藏 |
| 單件作品下載器 (Local Download) | 在單件作品頁由瀏覽器直接儲存檔案，無需 Java 後端，不登記到畫廊資料庫 |
| 體驗增強工具箱 (Toolbox) | 在 Pixiv 頁面右側提供已下載作品彩色邊框標記、一鍵匯入 Cookie 等功能 |

[匯入外部下載](/zh-hant/pixiv-batch-downloader-import)腳本由 `pixiv-batch-downloader-import` 外掛單獨提供，不包含在 All-in-One 中；安裝外掛並重新啟動後，需單獨安裝該腳本。

## 安裝步驟

### 方式一：從後端下載頁安裝（建議）

1. 啟動 PixivDownloader，開啟目前執行個體的下載頁（預設 `http://localhost:6999/pixiv-batch.html`，連接埠與子路徑以實際存取位址為準）。
2. 展開「油猴腳本」入口，點擊目標腳本的「安裝」按鈕並在 Tampermonkey 中確認。
3. 重新整理下載頁與已開啟的 Pixiv 頁面，確認腳本狀態與頁面浮動面板。

從後端安裝時，宿主會自動將腳本的更新連結、下載頁偵測範圍及首次預設連線位址綁定至目前執行個體位址（含實際連接埠與 context path；若瀏覽器已手動儲存過連線位址，則優先保留使用者設定），並將遠端腳本的 `@connect YOUR_SERVER_HOST` 替換為目前主機（外部匯入腳本仍僅限本機迴路連線）。若安裝了 **All-in-One 整合包**，請停用其中已包含的獨立下載腳本，避免頁面出現雙面板（兩者的本地儲存彼此獨立）。

### 方式二：從原始碼儲存庫安裝獨立腳本

在[原始碼儲存庫](https://github.com/Sywyar/PixivDownloader)開啟已提交的 `.user.js` Raw 內容，由 Tampermonkey 安裝，隨後核對腳本設定中的後端位址與 `@connect` 許可。All-in-One 整合包在建置時產生，且發布流程不會上傳獨立 userscript Release 附件，請直接從執行中的後端安裝。

## 略過歷史下載與非本機部署

各後端下載腳本的設定面板均支援「略過歷史下載」，開啟後可進一步勾選：

- **實際目錄檢查**：同時檢查磁碟檔案是否存在，缺少檔案時重新下載。
- **允許重新下載已刪除的作品**：畫廊刪除的作品保留[刪除標記](/zh-hant/gallery#刪除作品)，預設會視為已下載而略過；勾選後重新下載，並在成功後清除刪除標記。

後端部署在其他機器或非預設連接埠時（預設 `http://localhost:6999`）：

1. 直接從目標後端的 Web 下載頁安裝腳本（首次連線會自動採用該後端的通訊協定、主機與連接埠，並寫入對應的 `@connect` 主機許可）。
2. 若此前已儲存過舊位址，在 Pixiv 頁面開啟腳本設定將其改為目標後端位址（僅修改腳本標頭的 `@connect` 不會改變實際請求目標）。
3. 重新整理 Pixiv 頁面並提交一件作品，確認目標後端佇列收到任務。

## 安裝位址與執行偵測

下載頁的腳本狀態偵測與腳本在 Pixiv 頁面上的後端連線位址彼此獨立：例如從 `http://localhost:7000` 安裝腳本後，更新連結與下載頁偵測均固定綁定 `7000` 連接埠，不會掃描其他連接埠，在 Pixiv 頁面修改腳本連線位址也不會擴大偵測範圍。

| 腳本中繼資料 | 作用 |
| --- | --- |
| `@updateURL`、`@downloadURL` | 指向完整安裝位址（含通訊協定、主機、連接埠與應用程式子路徑） |
| `@include` 與注入的 `origin` / `pages` | 限定腳本回應頁內偵測的後端頁面（`@include` 為正規表示式，原始碼中的斜線會自動跳脫） |
| `@connect` | 宣告允許跨來源請求的主機名稱（不含連接埠，也不決定實際請求目標） |

下載頁透過頁內 `window.postMessage` 偵測腳本實際執行版本（不向後端發送偵測請求），並在卡片右上角合併顯示偵測版本與可安裝版本（版本號相同時只顯示一次）：可更新版本以橙色置頂，已偵測到為綠色，由 All-in-One 涵蓋為藍色，較舊版本或未偵測到為中性色，同時附帶狀態文字。版本比較僅針對同一腳本的 1–3 段數字版本及 `alpha.N`、`beta.N`、`rc.N` 預發行後綴；自訂版本格式、多個偵測版本排序衝突時僅顯示可安裝版本，All-in-One 版本不與獨立腳本版本交叉比較。

### 開發者：宣告偵測頁面與預設位址

外掛透過 `UserscriptContribution` 貢獻腳本時，需同時在腳本標頭結束前與最外層 IIFE 的 `'use strict'` 之後宣告偵測頁面與引導標記（兩者缺一不可，僅寫引導標記不會補寫 `@include`；腳本在後端頁面上應僅回應偵測，不啟動 Pixiv 頁面業務）：

```javascript
// 1. 放在 userscript 中繼資料標頭結束標記之前：
// @pixiv-presence-page /pixiv-batch.html
// @pixiv-presence-page /pixiv-batch-alt.html

// 2. 放在最外層 IIFE 的 'use strict' 之後、業務初始化之前：
// @pixiv-presence-bootstrap
```

- **伺服器端改寫**：`ScriptController.buildInstallUrl` 從目前 HTTP 請求解析通訊協定、主機、連接埠與 context path 改寫更新連結，`UserscriptPresenceInstaller` 基於同一位址注入偵測範圍。
- **用戶端預設位址解析**：根目錄後端腳本透過 `scripts/shared/installed-server.js` 讀取 `GM_info`（相容 `GM.info`）中的 `script.downloadURL`、`script.updateURL` 或 `scriptUpdateURL` 作為預設後端位址（無有效安裝位址時退回 `http://localhost:6999`，並透過 `GM_getValue` 優先保留使用者已儲存設定）。
- **建置同步**：修改共用片段後執行 `scripts/sync-shared-snippets.ps1` 同步並重新產生 All-in-One（外部匯入腳本使用自有 `installedServer()` 且僅連線至無子路徑的本機位址；`Local Download` 不連線至後端）。

## 常見問題排解

- **Pixiv 頁面沒有出現腳本面板**：確認 Tampermonkey 擴充功能與對應腳本均已啟用、目前 URL 符合腳本 `@match` 規則（如 User Batch 僅在 `/users/` 頁面生效），並重新整理頁面。Chrome 138+ 還需在擴充功能詳細資料中開啟「允許使用者腳本」。
- **下載頁顯示「未偵測到」**：表示目前下載頁未收到腳本的頁內訊息回應，不代表未安裝。請核對腳本是否啟用、網站權限及安裝來源連接埠是否與目前下載頁一致；需要更新時從目前後端重新安裝並重新整理下載頁（若安裝回應仍無 `@include`，需先更新提供該腳本的外掛）。
- **提示「後端未執行」或「連線失敗」**：核對後端處理程序狀態、單人模式登入狀態及腳本中的後端位址。一鍵匯入 Cookie 失敗時，依[第一次下載](/zh-hant/first-download#方式-c：使用者腳本「一鍵匯入-cookie」)檢查 Tampermonkey 的 HttpOnly Cookie 權限。
