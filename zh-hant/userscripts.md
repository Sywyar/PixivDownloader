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

1. 啟動 PixivDownloader，開啟目前執行個體的下載頁，例如 `http://localhost:6999/pixiv-batch.html`；連接埠以實際位址為準。
2. 展開「油猴腳本」入口，點擊目標腳本的「安裝」按鈕並在 Tampermonkey 中確認。
3. 重新整理下載頁與已開啟的 Pixiv 頁面，確認腳本狀態與頁面浮動面板。

透過後端安裝時，腳本的更新連結與偵測範圍會綁定目前位址，包含實際連接埠。後端下載腳本首次連線也採用該安裝位址；先前手動儲存的位址優先。支援遠端存取的腳本會替換 `@connect YOUR_SERVER_HOST`，外部匯入腳本仍限本機連線。若安裝了 **All-in-One 整合包**，請停用其中已包含的獨立下載腳本，避免頁面出現雙面板（兩者的本地儲存彼此獨立）。

### 方式二：從原始碼儲存庫安裝獨立腳本

在[原始碼儲存庫](https://github.com/Sywyar/PixivDownloader)開啟已提交的 `.user.js` Raw 內容，由 Tampermonkey 安裝，隨後核對腳本設定中的後端位址與 `@connect` 許可。All-in-One 整合包在建置時產生，且發布流程不會上傳獨立 userscript Release 附件，請直接從執行中的後端安裝。

## 略過歷史下載與非本機部署

各後端下載腳本的設定面板均支援「略過歷史下載」，開啟後可進一步勾選：

- **實際目錄檢查**：同時檢查磁碟檔案是否存在，缺少檔案時重新下載。
- **允許重新下載已刪除的作品**：畫廊刪除的作品保留[刪除標記](/zh-hant/gallery#刪除作品)，預設會視為已下載而略過；勾選後重新下載，並在成功後清除刪除標記。

後端部署在其他機器或非預設連接埠時（預設 `http://localhost:6999`）：

1. 從目標後端安裝後，首次連線會採用實際的通訊協定、主機和連接埠。若曾儲存其他位址，請在腳本設定中更改。
2. 透過該後端的 Web 頁面重新安裝腳本，以更新 `@connect` 主機許可（手動安裝的腳本需在腳本標頭核對 `@connect`；僅修改 `@connect` 不會改變請求傳送目標）。
3. 重新整理 Pixiv 頁面並提交一件作品，確認目標後端佇列收到任務。

## 安裝位址與執行偵測

例如從 `http://localhost:7000` 安裝，更新連結與下載頁偵測均使用 7000，不會掃描其他連接埠。腳本在 Pixiv 頁面的連線設定與下載頁偵測分別生效；修改連線設定不會擴大偵測範圍。

| 位置 | 用途 |
| --- | --- |
| `@updateURL`、`@downloadURL` | 完整安裝位址，包含通訊協定、主機、連接埠與應用程式子路徑 |
| `@include` 與注入的 `origin` / `pages` | 限定腳本可回應偵測的後端頁面；`@include` 為正規表示式，原始碼中的斜線會跳脫 |
| `@connect` | 允許請求的主機；此處不填寫連接埠，也不決定請求目標 |

偵測透過同一頁面內的 `window.postMessage` 完成，回傳腳本實際執行的版本，不向後端發送偵測請求。卡片右上角合併顯示偵測版本與可安裝版本，相同版本只顯示一次；可更新版本以橙色置於首行，已偵測到為綠色，多合一涵蓋為藍色，較舊版本與未偵測到為中性色，並保留狀態文字。

僅比較同一腳本的一至三段數字版本，以及 `alpha.N`、`beta.N`、`rc.N` 預發行版本。自訂格式、多個偵測版本排序衝突時，僅顯示可安裝版本。多合一版本不與獨立腳本版本比較。

### 開發者：宣告偵測頁面與預設位址

腳本透過外掛的 `UserscriptContribution` 宣告。在 userscript 標頭結束標記之前加入：

```javascript
// @pixiv-presence-page /pixiv-batch.html
// @pixiv-presence-page /pixiv-batch-alt.html
```

在最外層 IIFE 的 `'use strict'` 之後、業務初始化之前加入 `// @pixiv-presence-bootstrap`。兩部分缺一不可；只有引導標記時，宿主不會補寫 `@include`。後端頁面應只回應偵測，不啟動 Pixiv 業務。

宿主 `ScriptController.buildInstallUrl` 從目前 HTTP 請求取得通訊協定、主機、連接埠與 context path，改寫更新連結；`UserscriptPresenceInstaller` 從同一位址產生偵測範圍。根目錄後端腳本的 `scripts/shared/installed-server.js` 讀取 `GM_info`（相容 `GM.info`）中的 `script.downloadURL`、`script.updateURL` 或 `scriptUpdateURL`，從安裝 URL 取得預設連線位址。只有沒有有效後端安裝位址時才回退至原始碼預設的 `http://localhost:6999`；透過 `GM_getValue` 保留使用者設定，不在初始化時覆寫。

共享片段透過 `scripts/sync-shared-snippets.ps1` 同步，All-in-One 在建置時產生。外部匯入腳本有自己的 `installedServer()`，仍只連線至無子路徑的本機位址；Local Download 不需要預設後端位址。

## 常見問題排解

- **Pixiv 頁面沒有出現腳本面板**：確認 Tampermonkey 擴充功能與對應腳本均已啟用、目前 URL 符合腳本 `@match` 規則（如 User Batch 僅在 `/users/` 頁面生效），並重新整理頁面。Chrome 138+ 還需在擴充功能詳細資料中開啟「允許使用者腳本」。
- **下載頁顯示「未偵測到」**：表示目前頁面未收到腳本的頁內訊息回應，不代表未安裝。請核對腳本是否啟用、網站權限及安裝來源的連接埠；需要更新時從目前後端安裝並重新整理下載頁。若安裝回應仍沒有 `@include`，需更新提供腳本的外掛；重裝相同內容無法補齊缺少的宣告。
- **提示「後端未執行」或「連線失敗」**：核對後端處理程序狀態、單人模式登入狀態及腳本中的後端位址。一鍵匯入 Cookie 失敗時，依[第一次下載](/zh-hant/first-download#方式-c：使用者腳本「一鍵匯入-cookie」)檢查 Tampermonkey 的 HttpOnly Cookie 權限。
