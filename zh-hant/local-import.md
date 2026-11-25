# 本地作品匯入與本地匯入預設

本地作品匯入可根據檔案路徑中的作品 ID，將磁碟上既有的插畫、漫畫和動圖以**唯讀參照**方式批次登記到作品庫（保留原目錄與原始檔案，刪除畫廊紀錄不會刪除原始檔案；路徑中無法擷取的標題、作者、標籤等中繼資料可在匯入後透過[資料回填](#metadata-backfill)補齊）。匯入 PixivBatchDownloader 匯出的歷史 JSON 請使用[匯入外部下載](/zh-hant/pixiv-batch-downloader-import)。

?> 本地作品匯入是線上工具，需保持後端執行（不進入停機維護模式）。Compose（位於「工具 → 快捷工具」彈窗，關閉彈窗後背景任務繼續執行）與 Swing 均從桌面工具頁進入；HTTP API 中的路徑同樣指執行後端的伺服器本地路徑。

## 路徑範本與可用變數

**路徑範本**以大括號 `{變數}` 標出路徑中的可變部分，供程式反向擷取作品資訊：

- 每條範本必須包含 `{artwork_id}`（可位於目錄名或檔名中），並使用絕對路徑（Windows 支援 `/` 或 `\`）。範本僅用於比對與解析，不會建立目錄或重新命名檔案。
- **資料夾範本與單一檔案範本**：以 `/` 結尾代表**資料夾範本**（遞迴收集該目錄及子目錄中的受支援檔案）；未以 `/` 結尾代表**單一檔案範本**（需寫明檔名與 `.{extension}`）。
- 變數名稱固定使用小寫底線（精確比對）；**相鄰變數之間必須有固定分隔字元**（如 `{artwork_id}_{name}`，不得連寫為 `{artwork_id}{name}`），且 `*` 不作為萬用字元。

| 既有檔案的實際路徑 | 填入的路徑範本 | 解析結果 |
| --- | --- | --- |
| `D:/xxx/12345/pic.png` | `D:/xxx/{artwork_id}/` | 作品 ID 為 `12345`，收集該目錄及子目錄中的受支援檔案 |
| `D:/xxx/12345_xxx.png` | `D:/xxx/{artwork_id}_xxx.{extension}` | 作品 ID 為 `12345`，副檔名為 `png`（`_xxx` 為固定文字） |
| `D:/678/12345/pic.png` | `D:/{author_id}/{artwork_id}/` | 作者 ID 為 `678`，作品 ID 為 `12345` |
| `D:/xxx/12345_p0.jpg` | `D:/xxx/{artwork_id}_p{page}.{extension}` | 作品 ID 為 `12345`，第 `0` 頁 |

| 變數 | 含義 |
| --- | --- |
| `{artwork_id}` | 必填，正整數作品 ID |
| `{author_id}` | 正整數作者 ID |
| `{artwork_title}` | 路徑中既有的作品標題 |
| `{author_name}` | 路徑中既有的作者名稱 |
| `{page}` | 頁碼（從 `0` 開始） |
| `{extension}` | 副檔名（不含前導 `.`） |
| `{name}` | 比對單一路徑片段中的其他名稱（不作為作品標題寫入資料庫） |

## 操作步驟

工具彈窗中的「本次匯入選項」（含臨時路徑範本、作品 ID、指定檔案）預設收起：已儲存預設時三個輸入框均可留空並直接點擊「掃描並預覽」；未儲存預設且臨時範本留空時，點擊掃描會自動展開該區域並標紅提示填寫範本。

### 方式一：單次填寫臨時範本

1. 開啟桌面「工具 → 本地作品匯入」，展開「本次匯入選項」。
2. 在「臨時路徑範本」中輸入絕對路徑範本（每行一條，如 `D:/xxx/{artwork_id}/`；填寫臨時範本時僅使用臨時範本，且不會寫回設定檔）。
3. **作品 ID（選用）**：單件填 `12345`，多件以換行、空白或逗號分隔；留空則掃描範本比對到的全部作品。
4. **指定檔案（選用）**：僅登記單一檔案時填寫其路徑（仍需提供能比對該檔案的範本）。
5. 點擊「掃描並預覽」，核對作品 ID、路徑與預覽狀態後點擊「開始匯入」。

### 方式二：儲存本地匯入預設（適合固定目錄經常匯入）

**本地匯入預設**（設定鍵 `local-import.presets`）儲存常用路徑範本清單，掃描時自動啟用全部已儲存路徑：

1. 開啟「工具 → 本地作品匯入」，點擊彈窗內的「本地匯入預設」。
2. 點擊「新增路徑」，輸入 `D:/{author_id}/{artwork_id}/` 等絕對路徑範本（每行一條，支援雙擊編輯或移除；盡量指向存放作品的具體子目錄以縮小掃描範圍）。
3. 點擊「儲存預設」（立即生效，無需重新啟動；取消不改動已儲存清單）。
4. 返回匯入彈窗，三個輸入框均留空即可掃描全部預設路徑；若在「作品 ID」填入 `12345`，程式會自動在 `D:/` 下各作者目錄中定位該作品，無需預先提供作者 ID。

## 預覽狀態、快取與匯入計數

- **受支援格式**：`JPG`、`JPEG`、`PNG`、`APNG`、`GIF`、`WebP`、`WebM`、`MP4` 與 `ZIP`（須通過檔頭驗證，ZIP 僅用於動圖影格包，不會當作任意壓縮檔解包匯入）。
- **掃描索引快取**：最近一次目錄掃描索引在記憶體中保留 5 分鐘，不自動監聽磁碟變更；新增、移動或刪除檔案後，請勾選「重新掃描目錄，更新索引」再預覽。
- **取消與備份**：取消任務會等待目前正在登記的作品收尾，已登記作品保留。外部檔案為唯讀參照，移動或刪除原始檔案會導致畫廊參照失效，備份時請同時備份資料庫與外部來源目錄。

| 預覽結果 | 含義 | 執行時計入的統計項（依作品數統計） |
| --- | --- | --- |
| `NEW` | 登記新作品 | 寫入成功計入 `imported`，無實際變更計入 `skipped`，寫入出錯計入 `failed` |
| `SUPPLEMENT` | 為已匯入作品補充外部檔案或頁面 | 寫入成功計入 `imported`，無實際變更計入 `skipped`，寫入出錯計入 `failed` |
| `DUPLICATE` | 所有檔案均已登記 | `skipped` |
| `CONFLICT` | 存在衝突（如同一頁比對到多個檔案，不會強制覆寫） | `failed` |
| `MISSING` | 找不到相符檔案 | `failed` |

例如 4 部作品均為 `CONFLICT` 時，執行結果為 `imported: 0, skipped: 0, failed: 4`；具體原因可在預覽項目的 `reason` 或任務結果的 `problems` 中查看。

## HTTP API

管理員或具備 `core:local-import.*` 權限的程式金鑰可透過 `/api/local-import` 執行相同流程（同一執行個體同時僅執行一個匯入任務，預覽與索引不跨處理程序重新啟動保留）：

1. `GET /api/local-import/presets`：讀取已儲存預設。
2. `POST /api/local-import/previews`：提交掃描請求（回傳 `202` 與預覽 ID；`templates` 留空表示使用全部已儲存預設）：

   ```json
   {
     "templates": [],
     "artworkIds": [12345, 12346],
     "refreshIndex": false,
     "selectedFile": ""
   }
   ```

3. `GET /api/local-import/previews/{id}`：輪詢至 `phase` 為 `READY`，透過 `GET /api/local-import/previews/{id}/items?offset=0&limit=20` 分頁查看明細。
4. `POST /api/local-import/tasks`：提交 `{"previewId": "<預覽 UUID>"}` 啟動匯入。
5. `GET /api/local-import/tasks/{id}`：查詢匯入進度與結果（取消掃描或匯入分別呼叫 `POST /api/local-import/previews/{id}/cancel` 或 `POST /api/local-import/tasks/{id}/cancel`）。

讀取已匯入插畫的原始檔案請依頁碼呼叫 `GET /api/downloaded/rawfile/{artworkId}/{page}`（首頁為 `0`；匯入紀錄的 `folder` 可能僅為範本公共前綴，各頁檔案可分散在不同子目錄）。

## 匯入後補齊作品資料 :id=metadata-backfill

僅靠 `{artwork_id}` 匯入時，作品會立即計入畫廊總數，但標題、作者、標籤和系列為空。如需依標題、標籤或系列檢索，可在桌面開啟「工具 → 資料回填」（開啟啟動回填後下次啟動也會自動檢查），或透過 HTTP API 定向回填剛匯入的作品 ID（將 `12345` 替換為實際作品 ID；省略 `artworkIds` 則在全庫插畫與小說中依 `limit` 選擇候選）：

1. **查看候選數**（需 `core:backfill.read`）：`POST /api/tools/backfill/candidates`，請求主體 `{"artworkIds": [12345]}`。
2. **預演回填**（需 `core:backfill.execute`）：`POST /api/tools/backfill/tasks`，請求主體 `{"artworkIds": [12345], "dryRun": true}`，收到 `202` 後依 `Location` 輪詢至結束。
3. **正式回填**（需 `core:backfill.execute`）：`POST /api/tools/backfill/tasks`，請求主體 `{"artworkIds": [12345], "dryRun": false}`，同樣依 `Location` 輪詢至結束。

回填預設先讀取本地既有中繼資料，仍有缺少再依需求向 Pixiv 請求（詳見[網路存取與第三方服務](/zh-hant/network-access)）。同一時間僅執行一個回填任務；終態紀錄保留 1 小時（最多 64 筆），過期、被擠出或服務重新啟動後依 ID 查詢會回傳 `404`。
