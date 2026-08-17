# 首次配置

?> 僅首次啓動時需要完成此步驟。配置保存後，之後啓動會直接進入主界面。

## 配置入口速查

| 啓動方式 | 配置入口 |
|---------|---------|
| 桌面 GUI（默認） | GUI「首頁」引導向導 |
| 本機瀏覽器 + `--no-gui` 啓動 | 自動打開 `http://localhost:6999/setup.html` |
| 服務器 / Docker（無桌面） | 命令行 `--setup` 參數 |

---

## 方式一：GUI 引導向導（推薦桌面用戶）

安裝並啓動 PixivDownloader 後，GUI 主窗口會停留在「首頁」標籤，引導向導共 7 步，按提示操作即可。

### 第 1 步：等待服務就緒

首頁第一屏顯示後端啓動狀態（實時呼吸燈指示）。

等待狀態變爲「**運行中**」（通常 5–15 秒）後，點「下一步」繼續。

### 第 2 步：設置管理員賬號與運行模式

在同一頁中填寫管理員**用戶名**和**密碼**（密碼至少 6 位），並選擇運行模式：

| 模式 | 適用場景 | 特點 |
|------|---------|------|
| **自用模式 (Solo)** | 自己一人使用 | 需登錄，下載設置保存在服務器 |
| **多人模式 (Multi)** | 與他人共用一臺服務器 | 訪客無需登錄，支持配額和限流 |

?> 這是登錄 PixivDownloader 網頁界面用的賬號，**與 Pixiv 賬號無關**，可以隨便起名。個人用戶幾乎都應該選「**自用模式 (Solo)**」。

點「完成配置」後進入下一步。

### 第 3 步：配置 HTTP 代理

PixivDownloader 後端的 Pixiv 下載、更新檢查、FFmpeg 下載以及部分插件請求會按宿主或任務級路由使用這個代理。瀏覽器直連、SMTP 和明確選擇直連策略的請求不一定經過它；完整邊界見[網絡訪問與第三方服務](/zh-hant/network-access)。

- **有代理工具**（Clash、V2Ray 等）：填入代理地址，常見配置：
  - 主機：`127.0.0.1`
  - 端口：`7890`（Clash 默認）
- **網絡可直連 Pixiv**（非常少見）：關閉代理即可

?> 代理配置支持熱重載，之後可以隨時在 GUI「配置」頁修改，無需重啓。

### 第 4–7 步：引導說明

嚮導依次引導你打開瀏覽器下載頁（第 4 步）、瀏覽畫廊（第 5 步）、瞭解高級功能（第 6 步），最後到達完成頁（第 7 步），按需操作即可。

走到最後「完成」頁後，GUI「首頁」標籤會自動隱藏，下次啓動直接進入「狀態」頁，引導不再重複運行。

?> 如需重新走一遍嚮導，刪除 `state/gui/` 下的進度與完成標記文件即可。

---

## 方式二：瀏覽器 Setup 嚮導

適用於以 `--no-gui` 啓動、但本機有瀏覽器的場景（例如只是想省內存、不想打開 GUI 窗口）。

1. 啓動服務：`java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui`
2. 瀏覽器會自動打開 `http://localhost:6999/setup.html`
3. 按頁面提示填寫賬號、選擇模式、配置代理，點「完成配置」

!> `setup.html` **只允許本機瀏覽器**訪問，在遠程電腦的瀏覽器裏無法打開。

---

## 方式三：CLI 命令（服務器 / Docker）

適用於沒有圖形界面或桌面瀏覽器的服務器 / Docker 環境。

### 交互式初始化（推薦）

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup
```

依次輸入（密碼輸入時不顯示字符）：
1. 管理員用戶名
2. 密碼（至少 6 位）
3. 確認密碼
4. 運行模式（輸入 `solo` 或 `multi`）
5. 是否啓用 HTTP 代理（`y`/`n`）
6. 代理主機和端口（選 `y` 時填寫）

初始化完成後正常啓動服務：

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui
```

### 非交互式（自動化腳本）

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
    --username=admin \
    --password='YourPassword' \
    --mode=solo \
    --proxy-enabled=true \
    --proxy-host=127.0.0.1 \
    --proxy-port=7890
```

!> 密碼會出現在 shell 歷史和進程列表，只建議用於自動化環境。

### Docker 場景

```bash
# 先初始化
docker compose run --rm app --setup

# 再常駐運行
docker compose up -d
```

初始化完成前不要直接 `docker compose up`，否則容器會因檢測到未初始化而以退出碼 78 反覆重啓。

---

## 配置完成後

訪問 `http://localhost:6999/pixiv-batch.html` 即可開始使用。

接下來推薦閱讀 [第一次下載](/zh-hant/first-download) 瞭解基本下載流程。

---

## 後續管理

### 修改密碼

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --change-password
```

### 忘記密碼

先停止正在運行的服務，再執行（不需要舊密碼）：

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --reset-password
```

重置成功後所有現存會話都會失效，用新密碼重新登錄即可。

### 重新初始化

停止服務後刪除 `state/setup_config.json`，再重新運行 `--setup` 或在 GUI 走引導向導。

?> 重新初始化**不會**刪除已下載的文件和數據庫，只重置賬號和運行模式。
