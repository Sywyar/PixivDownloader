# 常見問題 (FAQ)

## 安裝與啓動

### Q: 啓動提示「Java 不是內部或外部命令」

**A**: 需要安裝 Java 17+。從 [Adoptium](https://adoptium.net/) 下載安裝後，重啓終端或手動配置 `JAVA_HOME` 和 `PATH` 環境變量。

### Q: 啓動後中文亂碼

**A**: 啓動命令必須添加 `-Dfile.encoding=UTF-8` 參數：

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar
```

### Q: 提示端口 6999 已被佔用

**A**: 有兩種方式解決：
1. 關閉佔用 6999 端口的程序
2. 修改 `config.yaml` 中的 `server.port` 爲其他端口（需重啓生效）

### Q: Windows 安裝包安裝失敗

**A**:
1. 確保安裝前已關閉正在運行的 `PixivDownload.exe`
2. 檢查是否有舊版 MSI 殘留（安裝器會自動處理遷移）
3. 以管理員身份運行安裝器

### Q: 如何卸載？

**A**: 
- **安裝包版本**：再次運行安裝器，選擇「卸載」；或通過 Windows「設置 → 應用」卸載
- **JAR 版本**：直接刪除程序目錄即可

### Q: 如何重新初始化（重置所有設置）？

**A**: 停止服務後刪除 `state/setup_config.json`，再以 `--setup` 重新初始化（或在 GUI 模式下走「首頁」引導）。注意：這不會刪除已下載的文件和數據庫。

### Q: 服務器 / Docker 上首次啓動總是退出，提示要先 setup？

**A**: 從 v1.10.0 起，無頭 / `--no-gui` 模式下若未完成首次初始化會拒絕啓動，避免起一個沒有任何配置入口的服務。請先用 CLI 完成初始化再啓動服務：

```bash
# 交互式：依次輸入用戶名、密碼、運行模式
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup

# 自動化腳本：一次性傳入（密碼會出現在 shell 歷史/進程列表）
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
    --username=admin --password='YourPassword123' --mode=solo
```

完成後再正常 `--no-gui` 啓動即可。詳見 [使用指南 → CLI 管理命令](zh-Usage-Guide#cli-管理命令-v1100)。

### Q: 管理員忘記密碼了怎麼辦？

**A**: 先停止正在運行的 PixivDownloader，然後用 CLI 重置密碼（不需要原密碼）：

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --reset-password
```

嚮導會要求確認新密碼（至少 6 位）。重置成功後所有現存的登錄會話都會被註銷，需要使用新密碼重新登錄。如果還記得當前密碼、只是想換一個新的，用 `--change-password`（會校驗當前密碼）。

### Q: 啓動時提示「無法識別的啓動參數」？

**A**: 從 v1.10.0 起啓動參數會做嚴格校驗，拼錯的 flag（如 `--no-guii`）、漏寫 `=` 的參數（如 `--username admin`）或純位置參數都會被拒絕。運行 `--help` 查看完整選項；`--key=value` 形式的參數仍然會作爲 Spring 屬性覆蓋原樣轉發。

---

## 代理與網絡

### Q: 代理配置後仍無法下載

**A**:
1. 確認代理配置已保存（GUI 保存或 CLI `--setup` 配置後，代理**支持熱重載**，立即生效，無需重啓；直接手動編輯 `config.yaml` 則需重啓或在 GUI 點保存觸發熱重載）
2. 驗證代理是否可用：在瀏覽器中通過代理訪問 `https://i.pximg.net/`
3. 檢查防火牆是否放行代理端口
4. 提醒：代理用於全部對外訪問（Pixiv、在線更新、下載 FFmpeg、在線 TTS），上述問題也會同樣影響這些功能

### Q: 多人模式部署在反向代理後，所有用戶被限流

**A**: 未配置可信代理時，後端只使用 TCP 源 IP。所有請求都來自同一個反代節點時，會共享同一限流身份。

**解決方案**：把實際反代出口地址或容器網段加入 `server.trusted-proxy-cidrs`，並讓反代爲每個請求發送完整的 `Forwarded`，或 `X-Forwarded-For` + `X-Forwarded-Proto` + `X-Forwarded-Host`：

```yaml
server.trusted-proxy-cidrs: 172.18.0.0/16
```

後端會使用規範化後的真實客戶端地址限流。不要信任公網或客戶端網段；轉發頭缺失或來源不受信時請求會返回 400。完整安全邊界見[配置參考](/zh-hant/configuration#https-與反向代理)。

### Q: 局域網內其他設備無法訪問

**A**:
1. 確認防火牆放行 6999 端口
2. 確認 `config.yaml` 中未限制綁定地址
3. 瀏覽器訪問 `http://<服務器IP>:6999/`

---

## Cookie 相關

### Q: Search 模式搜索不到結果

**A**: Search 模式需要 Pixiv Cookie。請確認：
1. 已在 `pixiv-batch.html` 中正確粘貼 Netscape 格式的 Cookie
2. Cookie 未過期（重新登錄 Pixiv 後重新獲取）
3. 代理配置正確

### Q: Cookie 格式錯誤提示

**A**: 請確保：
1. 使用 Cookie-Editor 擴展的 **Netscape** 格式導出
2. 在頁面上切換到 **Netscape** 格式標籤後再粘貼
3. 不要手動修改 Cookie 內容

### Q: 如何清除已保存的 Cookie？

**A**: 
- Solo 模式：在 `pixiv-batch.html` 點擊清除 Cookie 按鈕，或退出登錄
- 退出登錄時會同步清除服務端保存的 Cookie

---

## 下載相關

### Q: 動圖 (Ugoira) 下載後無法播放

**A**: 動圖需要 ffmpeg 轉換爲 WebP 格式。請安裝 ffmpeg 並確保在系統 PATH 中可用：

```bash
ffmpeg -version
```

Windows 安裝包用戶可在 GUI「狀態」頁點擊「下載 FFmpeg」按鈕。

### Q: 下載的小說 EPUB 封面不顯示

**A**: 封面下載可能需要代理。確保：
1. 代理配置正確
2. 封面 URL 的 host 以 `.pximg.net` 結尾（SSRF 安全限制）
3. 重新下載該小說

### Q: 聽書的「在線引擎」沒有聲音 / 提示合成失敗

**A**: 在線引擎（Edge 神經語音）需要聯網，請依次排查：
1. **網絡/代理**：在線合成經後端代理訪問微軟服務，確保代理可用（與下載共用 `proxy.*` 配置）
2. **握手 403**：版本號過期時後端會自動聯網拉取最新 Edge 版本、校正時鐘偏差後重試，通常會自愈；若持續失敗，檢查本機系統時鐘是否準確
3. **改用瀏覽器引擎**：在播放欄「設置」裏切換到「瀏覽器引擎」，完全離線、無需聯網（前提是操作系統裝了對應語言的語音包）
4. **邀請訪客被限流**：通過邀請鏈接訪問的訪客受 `guest-invite.tts-request-limit-minute` 約束（訪客邀請在 solo/multi 下都可用），過於頻繁會返回 429，稍後再試或讓管理員調高該值；管理員本人不受限

### Q: 批量下載時部分作品失敗

**A**: 常見原因：
1. Cookie 過期 — 重新獲取並保存 Cookie
2. Pixiv 服務器限流 — 適當降低下載併發和頻率
3. 代理不穩定 — 檢查代理服務
4. 作品已被刪除或設爲私有 — 跳過錯過的作品

### Q: 自定義文件名模板不生效

**A**: 檢查模板語法：
- 變量使用花括號 `{artwork_id}`
- 不要使用不支持的變量名
- 模板結果不能包含非法文件名字符（會自動 sanitize）
- 修改模板後，新下載的作品纔會使用新模板，已有作品文件名不會改變

### Q: 動圖下載佔用大量配額

**A**: 多人模式下，可在 `config.yaml` 配置 `multi-mode.quota.limit-image`。當一個作品的總圖片數超過此值時，按多個配額計算：

```yaml
multi-mode.quota.limit-image: 10  # 超過10張圖片的作品按比例消耗配額
```

---

## GUI 相關

### Q: GUI 啓動後服務一直顯示「啓動中」

**A**: 啓動超過 10 秒時會顯示已等待秒數，可能正在更新數據庫。如果長時間無響應：
1. 查看 `log/latest.log` 日誌文件
2. 嘗試刪除數據庫（如有備份）後重啓
3. 確認 Java 版本爲 17+

### Q: GUI 在線更新失敗

**A**:
1. 檢查網絡連接
2. 檢查代理配置（更新下載複用代理配置）
3. 可手動從 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下載安裝包覆蓋安裝

### Q: GUI 錯誤彈窗顯示亂碼

**A**: GUI 錯誤彈窗已改爲統一提示，詳細錯誤信息記錄在日誌文件中。點擊彈窗中的「打開日誌文件」查看詳細錯誤。

---

## 數據庫相關

### Q: 數據庫文件在哪裏？

**A**: 默認位於 `data/pixiv_download.db`。使用 SQLite WAL 模式，可用任意 SQLite 工具打開。

### Q: 數據庫損壞怎麼辦？

**A**: 
1. 停止程序
2. 備份 `pixiv_download.db`、`pixiv_download.db-wal`、`pixiv_download.db-shm`
3. 嘗試用 SQLite 工具修復：`sqlite3 pixiv_download.db "PRAGMA integrity_check;"`
4. 如無法修復，刪除數據庫文件並重啓（已下載文件不受影響），但畫廊數據會丟失

### Q: 數據庫丟了 / 遷移了，已經下載過的作品會被當作未下載重新拉取嗎？

**A**: 不會。重新下載某個作品時，若數據庫無該作品的記錄，但 `{下載根目錄}/{作品ID}/` 下已存在按默認文件名模板（`{作品ID}_p{頁號}.{擴展名}`，如 `123456_p0.jpg`）命名的圖片文件，會按實際頁數與擴展名反向恢復一條數據庫記錄、跳過重新下載，恢復後的作品在畫廊中可像正常下載的作品一樣瀏覽。

注意：僅識別默認文件名模板命名的文件。如果你用過自定義文件名模板下載，那些文件不會被識別，仍會被當作未下載重新拉取；元數據（標題、作者、標籤等）也無法離線恢復，畫廊會按缺字段降級顯示，可用「數據庫數據回填工具」補全。

### Q: 如何備份數據？

**A**: 備份以下目錄：
- `data/` — 數據庫文件
- `pixiv-download/` — 下載的文件
- `state/` — 運行狀態
- `config/` — 配置文件
- `collection_icons/` — 收藏夾圖標

---

## 油猴腳本

### Q: 腳本安裝後不生效

**A**:
1. 確認 Tampermonkey 已啓用
2. 檢查腳本是否在目標頁面啓用（`@match` 規則）
3. 檢查 Tampermonkey 管理面板中腳本是否顯示爲「已啓用」
4. 刷新 Pixiv 頁面

### Q: All-in-One 和獨立腳本同時啓用出現雙面板

**A**: 這是 Tampermonkey 平臺的固有行爲 — All-in-One 和獨立腳本是不同腳本，存儲互不相通。建議：
- 只啓用 **All-in-One 整合包**（推薦）
- 或者只啓用需要的獨立腳本，禁用 All-in-One

### Q: 腳本提示「後端不可用」或「未登錄」

**A**:
1. 確認後端服務正在運行
2. Solo 模式下確認已登錄
3. 非 localhost 部署時需修改腳本頭部的 `@connect` 聲明

---

## 安全相關

### Q: 使用此工具會導致 Pixiv 賬號被封嗎？

**A**: 本項目通過用戶自行提供的 Cookie 訪問 Pixiv，使用者需自行承擔賬號風險。建議：
- 合理設置下載頻率和併發數
- 避免短時間內大量請求
- 遵守 Pixiv 使用條款

### Q: 多人模式下訪客能看到管理員的數據嗎？

**A**: 多人模式下：
- 訪客只能看到自己的下載狀態
- 管理員和 Solo 模式保留全局視圖
- 訪客邀請模式下，訪客只能瀏覽白名單範圍內的作品
- 收藏夾、歷史、統計等數據按訪問權限過濾

### Q: 如何啓用 HTTPS？

**A**: 在 `config.yaml` 配置 SSL 證書：

```yaml
server.ssl.enabled: true
server.ssl.certificate: /path/to/cert.pem
server.ssl.certificate-private-key: /path/to/key.pem
```

推薦使用 PEM 格式。同時配置 PEM 和 JKS 時 PEM 優先。

---

## 其他

### Q: 如何遷移到新電腦？

**A**: 複製以下目錄到新電腦：
1. `data/` — 數據庫文件
2. `pixiv-download/` — 下載的文件
3. `state/` — 運行狀態
4. `config/` — 配置文件
5. `collection_icons/` — 收藏夾圖標（如有）

### Q: 如何升級版本？

**A**: 
- **GUI 模式**：啓動後自動檢查更新，或在「狀態」頁手動檢查
- **JAR 版本**：下載新版本 JAR 覆蓋替換
- **Windows 安裝包**：運行新版本安裝器選擇「修復」或直接覆蓋安裝
- 數據庫會自動遷移，無需手動操作

### Q: 項目如何提供反饋和報告問題？

**A**: 在 [GitHub Issues](https://github.com/Sywyar/PixivDownloader/issues) 提交 issue，請包含：
- 操作系統和版本
- PixivDownloader 版本（GUI「關於」頁查看）
- 問題描述和復現步驟
- 相關日誌（`log/latest.log`）
- 截圖（如有）
