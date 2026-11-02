# 常見問題 (FAQ)

依故障症狀查找診斷與處理步驟。涉及資料庫、帳號或目錄的操作前，請先確認實際工作目錄並保留備份。下文命令中的 `APP.jar` 請替換為發行包內的實際主程式 JAR 名稱，並在完整解壓縮目錄中執行。

## 安裝與啟動

### 提示「Java 不是內部或外部命令」

尚未安裝 Java 17+ 或未加入環境變數。從 [Adoptium](https://adoptium.net/) 安裝 Java 17+ 後重新開啟終端機，或檢查 `JAVA_HOME` 與 `PATH`。

### 啟動後主控台中文亂碼

手動啟動時加上 `-Dfile.encoding=UTF-8`（發行包附帶的 `run.bat` / `run.sh` 已包含此參數）：

```bash
java -Dfile.encoding=UTF-8 -jar APP.jar
```

### 提示連接埠 6999 已被占用

先檢查是否已有使用同一資料目錄的 PixivDownloader 執行個體正在執行（請勿對同一資料目錄重複啟動多個執行個體）。若被其他程式占用，停止該程式，或修改 `config/config.yaml` 中的 `server.port` 後重新啟動。

### Windows 安裝包安裝失敗

先關閉正在執行的 `PixivDownload.exe`，再以管理員身分重新執行安裝程式。

### 如何解除安裝或重新初始化管理員帳號

- **解除安裝**：Windows 安裝版透過安裝程式或系統「設定 → 應用程式」解除安裝；Java 解壓縮版先依[儲存與備份](/zh-hant/storage)備份作品目錄、`config/`、`state/` 和 `data/`，再刪除程式檔案（切勿直接刪除包含作品和資料庫的整個工作目錄）。
- **忘記密碼或重新初始化**：僅忘記密碼時，停止服務並執行 `--reset-password`。確實需要重建初始化狀態時，停止服務並備份後移走 `state/setup_config.json`，再執行 `--setup` 或桌面引導（不會清空業務設定或重建資料庫，詳見[首次設定](/zh-hant/first-setup)）。

### 伺服器 / Docker 啟動後立即以結束代碼 78 結束

無頭模式（`--no-gui`）尚未完成首次初始化時，會以結束代碼 `78` 中止。先執行互動式初始化，再啟動服務：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

### 管理員忘記密碼

停止正在執行的執行個體後，在本機執行重設命令（無需原密碼，密碼至少 8 碼、建議 12 碼以上，重設後舊工作階段全部失效；記得原密碼時可用 `--change-password`）：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --reset-password
```

### 提示「無法辨識的啟動參數」

啟動參數會經過嚴格核驗：拼寫錯誤的開關（如 `--no-guii`）、漏寫 `=` 的參數（如 `--username admin`）或孤立位置參數均會被拒絕。執行 `--help` 查看受支援選項。

## 代理與網路

### 設定代理後仍無法下載

1. 確認代理已儲存（桌面 GUI 儲存或 CLI `--setup` 支援熱重載；手動編輯 `config/config.yaml` 需重新啟動，或在 GUI 點擊儲存以觸發重載）。
2. 在瀏覽器驗證實際 Pixiv 作品頁可存取，並查看 `log/latest.log` 中的失敗主機與錯誤碼。
3. 檢查防火牆是否放行代理連接埠，容器部署確認使用了 `host.docker.internal` 而非 `127.0.0.1`。
4. 區分全域代理、排程工作獨立代理、外掛自有代理和瀏覽器直接連線請求，詳見[網路存取與第三方服務](/zh-hant/network-access)。

### 多人模式在反向代理後，所有使用者共用同一限流

未設定可信任代理時，後端依 TCP 來源 IP 限流。在 `config/config.yaml` 的 `server.trusted-proxy-cidrs` 中填入實際反向代理出口或容器網段（如 `172.18.0.0/16`），並讓反向代理傳遞完整的 `Forwarded` 或 `X-Forwarded-For` + `X-Forwarded-Proto` + `X-Forwarded-Host`，詳見[設定參考中的 HTTPS 與反向代理](/zh-hant/configuration#https-與反向代理)。

### 區域網路其他裝置無法存取

確認防火牆已放行 `server.port`（預設 `6999`）、服務並非僅綁定 `127.0.0.1`（Docker 預設僅綁定宿主機迴路，需透過反向代理或調整對應開放），並在區域網路裝置存取 `http://<伺服器IP>:6999/`。

## Cookie 與下載

### 搜尋無結果或提示 Cookie 格式錯誤

- 搜尋下載、快速取得與 R-18 作品必須設定含有效 `PHPSESSID` 的 Cookie。
- 貼上時的格式選擇（**Netscape** 或 **Header String**）必須與匯出內容一致；一般前端腳本讀出的 `document.cookie` 不含 HttpOnly 的 `PHPSESSID`，請依[第一次下載](/zh-hant/first-download#_2-儲存-pixiv-cookie（依需求）)重新取得。
- 清除 Cookie 時需在下載頁明確點擊清除；排程工作持有獨立的 Cookie 綁定，還需在對應任務中解除綁定。

### 動圖（Ugoira）無法轉換或小說 EPUB 無封面

- **動圖**：確認 `ffmpeg -version` 可用（或從桌面 GUI「狀態」頁點擊「下載 FFmpeg」），選 MP4 時還需 H.264 編碼器。
- **小說封面**：封面來自 `.pximg.net`，需確保後端代理可正常存取圖片網域，修復代理後重新下載該小說。

### 聽書的「線上引擎」沒有聲音或合成失敗

1. 線上引擎（Edge 神經語音）經後端代理連線至微軟服務，先確認 `proxy.*` 設定可用。
2. 交握回傳 403 時，後端會自動更新 Edge 版本並校正時鐘偏差後重試；若持續失敗，請校準本機系統時間。
3. 可在播放列切換為「瀏覽器引擎」。
4. 受邀訪客受 `guest-invite.tts-request-limit-minute`（預設每分鐘 30 次）限流，超限回傳 429；管理員不受此限制。

### 批次下載部分失敗、檔名範本不生效或配額消耗快

- **部分失敗**：檢查作品是否已被作者刪除/設為私人、Cookie 是否過期；遇到上游限流時，降低並行數並增加作品間隔。
- **檔名範本**：變數須加大括號（如 `{artwork_id}`），且僅對修改後的新下載生效（支援的變數見[檔名範本變數](/zh-hant/usage-guide#檔名範本變數)）。
- **多人模式多圖配額**：`multi-mode.quota.limit-image` 大於 `0` 時，超過該頁數的多圖作品會依比例折算多個配額。

## 桌面 GUI 與資料庫

### GUI 一直顯示「啟動中」、更新失敗或出現錯誤視窗

- **啟動緩慢**：超過 10 秒會顯示等待秒數，可能正在執行資料庫遷移或載入外掛。請查看 `log/latest.log`，保留資料與日誌，**切勿透過刪除資料庫來排解啟動緩慢問題**。
- **線上更新失敗**：檢查後端代理連線，或直接從 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下載新版安裝包覆蓋安裝。
- **錯誤詳細資料**：點擊彈出視窗中的「開啟日誌檔案」，查看 `log/latest.log` 中的完整英文診斷日誌。

### 資料庫位置、損壞處理與備份還原

- **位置**：預設 SQLite 位於 `data/pixiv_download.db`（伴隨 `-wal` / `-shm`）；PostgreSQL 位於所設定的資料庫伺服器。
- **資料庫損壞**：先停止程式並完整備份原資料庫與伴隨檔案，僅對**備份副本**執行 `PRAGMA integrity_check;`（此命令僅檢查，不修復）。確認損壞後，優先還原已驗證備份；作品辨識、頁數、路徑與關聯均以資料庫為準，**遺失資料庫後，不能僅憑作品資料夾還原畫廊或保證略過重新下載**。
- **備份、遷機與升級**：完整備份範圍與目錄搬遷見[儲存與備份](/zh-hant/storage)。Java 發行包升級時，必須完整解壓縮並同步替換主程式與 `plugins/`，保留 `config/`、`state/` 和 `data/`，切勿僅替換單一主 JAR。

## 使用者腳本與安全

### 使用者腳本不顯示、出現雙面板或無法連線至後端

- 確認 Tampermonkey 與腳本已啟用、目前頁面符合 `@match`（如 User Batch 僅在 `/users/` 生效），並重新整理頁面。
- **雙面板**：All-in-One 與獨立下載腳本不要同時啟用，保留其一即可。
- **無法連線至後端**：確認後端已啟動、單人模式已登入；部署並非使用 `localhost:6999` 時，須同時修改腳本內的後端位址並更新 `@connect` 許可，詳見[使用者腳本](/zh-hant/userscripts)。

### 帳號安全、身分隔離與 HTTPS

- 本工具使用你提供的 Cookie 請求 Pixiv，請合理設定並行數與間隔並遵守平台規則。
- **身分區分**：`multi` 模式下未登入且無邀請碼的使用者為**遊客**（依 UUID 與 `multi-mode.*` 隔離）；持邀請碼的使用者為**訪客**（在 `solo` / `multi` 下皆只能唯讀存取白名單內容，受 `guest-invite.*` 限流）。
- **啟用 HTTPS**：在 `config/config.yaml` 中設定 `server.ssl.enabled: true`、`server.ssl.certificate` 與 `server.ssl.certificate-private-key`（詳見[設定參考](/zh-hant/configuration#https-與反向代理)）。

## 提交問題回饋

在 [GitHub Issues](https://github.com/Sywyar/PixivDownloader/issues) 回報問題時，請附上作業系統、程式版本、重現步驟、相關截圖，以及移除 Cookie、密碼、權杖和私人位址後的 `log/latest.log`。
