# 安裝指南

## 環境要求

| 依賴 | 最低版本 | 說明 |
|------|----------|------|
| **Java** | 17+ | Java 標準包 / 離線全量包運行必需；Windows 安裝包已內置 JRE |
| **操作系統** | Windows / macOS / Linux | 跨平臺支持 |
| **Tampermonkey** | 最新版 | 如需使用油猴腳本 |
| **ffmpeg** | 任意 | Ugoira 動圖轉 WebP 所需（可選） |

---

## 方式一：Java 標準包 / 離線全量包（跨平臺）

### 1. 安裝 Java 17+

- **Windows**: 從 [Adoptium](https://adoptium.net/) 下載安裝
- **macOS**: `brew install openjdk@17`
- **Linux**: `sudo apt install openjdk-17-jdk`（Debian/Ubuntu）或 `sudo dnf install java-17-openjdk`（Fedora）

驗證安裝：

```bash
java -version
# 應輸出類似：openjdk version "17.0.x" ...
```

### 2. 下載並解壓

從 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下載：

- `PixivDownload-*-java.zip` — Java 標準包，與 Windows 安裝包默認插件集合一致（不含 Douyin）
- `PixivDownload-*-full-offline.zip` — 離線全量包，額外包含 Douyin

下載後必須**完整解壓**，不要只提取其中的 JAR：啓動腳本與 `plugins/` 目錄缺一不可，程序啓動時會從工作目錄的 `plugins/` 加載官方外置插件。

?> 單獨的 `PixivDownload-*.jar` 是核心殼 JAR，不攜帶必需的下載工作臺插件，只作爲內部構建輸入、不作爲普通用戶附件；直接運行它會進入恢復 / 修復模式。

### 3. 啓動

Windows 上執行 `run.bat`；Linux / macOS 在解壓目錄內執行 `sh run.sh`。也可以手動啓動：

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar
```

> [!IMPORTANT]
> 務必添加 `-Dfile.encoding=UTF-8` 參數，否則在中文 Windows 下可能出現亂碼（`run.bat` / `run.sh` 已內置該參數）。

### 4. 後臺運行（服務器/Docker）

```bash
# 無 GUI 模式（適合 headless 服務器）
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui

# 使用 nohup 後臺運行
nohup java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui > app.log 2>&1 &
```

> [!IMPORTANT]
> **無頭/`--no-gui` 模式下首次啓動必須先完成 setup**：服務器上沒有 GUI 引導、`setup.html` 又只允許本機訪問，因此從 v1.10.0 起，未完成首次初始化時 `--no-gui` 啓動會被中止並提示使用 CLI 命令。請先執行：
>
> ```bash
> # 交互式：依次輸入用戶名、密碼、運行模式 (solo|multi)，並配置 HTTP 代理（是否啓用、主機、端口）
> java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup
>
> # 或一行非交互（密碼會出現在 shell 歷史/進程列表，僅用於自動化腳本）
> java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
>     --username=admin --password='YourPassword123' --mode=solo \
>     --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
> ```
>
> 代理用於按宿主或任務路由選擇代理的後端訪問，例如 Pixiv 下載、在線更新、FFmpeg 下載和部分在線 TTS；瀏覽器請求、SMTP 和顯式直連策略可能繞過它，完整邊界見[網絡訪問與第三方服務](/zh-hant/network-access)。省略 `--proxy-*` 時會交互式詢問，無需代理可加 `--proxy-enabled=false`。後續可用 `--change-password` 修改密碼、`--reset-password` 在忘記密碼時重置密碼。詳見 [使用指南 → 啓動參數](zh-Usage-Guide)。

---

## 方式二：Windows 安裝包（推薦 Windows 用戶）

### 1. 下載並運行安裝器

從 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下載 `PixivDownload-x.x.x-win-x64-setup.exe`。

?> Windows 默認安裝包預置除 Douyin 外的全部官方插件，啓動後即可使用下載頁。離線全量包會額外攜帶 Douyin，適合不方便聯網安裝插件的環境；Douyin 也可從 Web 插件市場按需安裝。

### 2. 安裝過程

1. 選擇安裝語言（中文/English）
2. 選擇安裝目錄
3. **可選安裝任務**：勾選「下載並安裝 FFmpeg」
   - FFmpeg 用於 Ugoira 動圖轉 WebP
   - 不勾選不影響普通圖片下載
   - 安裝後也可在 GUI 狀態頁重新下載

### 3. 維護模式

安裝完成後再次運行安裝器可進入維護模式，支持：
- **修復** — 重新安裝程序文件
- **更改** — 修改安裝組件
- **卸載** — 完整卸載程序

### 4. 啓動

安裝完成後自動啓動，也可從開始菜單或桌面快捷方式啓動。

> [!NOTE]
> 安裝器支持語言選擇，並在安裝前會檢測 `PixivDownload.exe` 是否仍在運行，提示關閉後重試。

---

## 方式三：Docker（服務器常駐）

倉庫根目錄已提供 `Dockerfile`（multi-stage 構建，運行鏡像內置動圖所需的 ffmpeg）與 `docker-compose.yml`。

### 1. 環境要求

- Docker 20.10+ 與 Docker Compose（`docker compose` 命令）。

### 2. 首次初始化（務必先做）

容器內沒有桌面 GUI、`setup.html` 又只允許本機訪問，因此首次初始化只能用 CLI `--setup`：

```bash
# 在倉庫根目錄（含 Dockerfile / docker-compose.yml）執行
docker compose run --rm app --setup
# 交互式依次輸入：用戶名、密碼、運行模式 (solo|multi)，並配置 HTTP 代理（是否啓用、主機、端口）
# 賬號寫入 state/setup_config.json，代理寫入 config/config.yaml
```

> [!WARNING]
> **請勿跳過這一步直接 `up`。** 未完成初始化時容器會以退出碼 78 反覆重啓（日誌會打印需運行 `--setup` 的提示）。

### 3. 常駐運行

```bash
docker compose up -d         # 後臺常駐
docker compose logs -f app   # 查看日誌
docker compose down          # 停止
```

啓動後瀏覽器訪問 `http://<宿主IP>:6999/`。登錄、監控和已安裝插件貢獻的頁面均通過會話鑑權遠程可用；setup 嚮導與桌面 GUI 在容器內不可用（如需改配置見下）。

### 4. 代理配置（關鍵）

按宿主或任務路由選擇代理的後端訪問會使用此代理，例如 Pixiv 下載、在線更新、FFmpeg 下載和部分在線 TTS；瀏覽器請求、SMTP 和顯式直連策略可能繞過它，完整邊界見[網絡訪問與第三方服務](/zh-hant/network-access)。`config.yaml` 默認 `proxy.host: 127.0.0.1`，在容器內指向容器自身、不可達。`docker-compose.yml` 已聲明 `host.docker.internal -> 宿主網關`。

推薦在上面第 2 步 `--setup` 時直接配置代理（無需事後編輯文件）：

```bash
# 複用宿主機上運行的代理（端口沿用，如 7890）
docker compose run --rm app --setup --proxy-host=host.docker.internal --proxy-port=7890
# 若無需代理（已有其它出網路徑），加：
#   --proxy-enabled=false
```

也可初始化後編輯掛載出的 `config/config.yaml`：

```yaml
proxy.host: host.docker.internal   # 複用宿主機上運行的代理（端口沿用，如 7890）
# 若無需代理（已有其它出網路徑）：
# proxy.enabled: false
```

改完執行 `docker compose restart app` 生效。

### 5. 數據持久化

`docker-compose.yml` 已將下列目錄掛載到宿主，重啓/重建容器數據不丟：

| 宿主路徑 | 用途 |
|----------|------|
| `./config/` | 運行時配置 `config.yaml` |
| `./state/` | 登錄態、`setup_config.json`、批量下載狀態、GUI 引導標記 |
| `./data/` | SQLite 數據庫 `pixiv_download.db`、收藏夾圖標、縮略圖緩存、TTS 緩存、回填狀態、朗讀參考音 |
| `./pixiv-download/` | 下載的作品/小說/系列文件（含臨時打包 `_archives`） |
| `./log/` | 運行日誌（可選） |

### 6. 健康檢查

鏡像與 compose 均配置了健康探針，指向公開的 actuator 端點：

- `GET /actuator/health` — 返回 `{"status":"UP"}`（無需登錄，不泄露內部明細）。
- `GET /actuator/info` — 返回應用名稱與版本。

`docker compose ps` 的 `STATUS` 列會顯示 `healthy`/`unhealthy`。

> [!NOTE]
> 修改端口、代理、SSL 等需編輯 `config/config.yaml` 後 `docker compose restart app`；若改了 `server.port`，需同步調整 compose 的端口映射與健康檢查地址。

---

## 安裝油猴腳本（可選）

> [!TIP]
> 推薦優先使用 Web 端 `pixiv-batch.html`，無需安裝腳本即可完成批量下載。

### 方式一：通過 Web 管理頁一鍵安裝（推薦）

1. 啓動 PixivDownloader 後端
2. 訪問 `http://localhost:6999/pixiv-batch.html`
3. 點擊頁面頂部 「🧩 油猴腳本」卡片展開
4. 點擊對應腳本的「⬇ 安裝」按鈕

> [!WARNING]
> 通過 Web 端安裝時，腳本更新檢查會指向當前後端。當後端地址變更時，需要重新安裝或手動修改腳本頭部的 `@connect`。

### 方式二：從 Release 下載

從 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下載腳本文件，拖入 Tampermonkey 管理面板安裝。

Release 附件中的腳本：

| 腳本文件 | 說明 |
|----------|------|
| `Pixiv All-in-One.user.js` | 整合包（推薦），包含頁面批量、User 批量、URL 批量導入、單作品下載（Java 後端版）、體驗增強工具箱 |
| `Pixiv 單作品圖片下載器(Local Download).user.js` | 瀏覽器本地下載，無需 Java 後端 |

### 完整腳本列表

| 腳本名稱 | 功能 | 獲取方式 |
|----------|------|----------|
| 頁面批量下載器 (Page Scrape) | 從 Pixiv 頁面 DOM 抓取作品 | Web 端 / GitHub 代碼區 |
| User 批量下載器 (User Batch) | 從用戶主頁批量下載 | Web 端 / GitHub 代碼區 |
| URL 批量導入單作品下載器 (URL Batch) | 批量導入作品 URL | Web 端 / GitHub 代碼區 |
| 單作品圖片下載器 (Java後端版) | 單作品頁通過後端下載 | Web 端 / GitHub 代碼區 |
| 單作品圖片下載器 (Local Download) | 瀏覽器本地下載，無需後端 | Release / Web 端 / GitHub 代碼區 |
| 體驗增強工具箱 (Toolbox) | 已下載作品標記、Cookie 導入等 | Web 端 / GitHub 代碼區 |

### 非 localhost 部署的額外配置

<details>
<summary><strong>點擊展開</strong></summary>

Tampermonkey 的 `@connect` 白名單默認只允許連接 `localhost`。如果後端部署在其他機器：

1. 打開 Tampermonkey 管理面板 → 找到對應腳本 → 點擊編輯
2. 將腳本頭部的 `// @connect      YOUR_SERVER_HOST` 替換爲實際地址
3. 保存腳本（Ctrl+S）

如果通過 Web 端 `pixiv-batch.html` 安裝，`@connect` 會自動替換爲當前後端地址。

</details>

---

## 安裝 FFmpeg（可選）

FFmpeg 用於 Ugoira 動圖轉換爲 WebP，普通圖片下載不需要。

### 自動安裝（推薦）

- **Windows 安裝包**：安裝時勾選「下載並安裝 FFmpeg」
- **GUI 工具**：啓動後在 GUI「狀態」標籤頁點擊「下載 FFmpeg」按鈕

### 手動安裝

1. 從 [FFmpeg 官網](https://ffmpeg.org/download.html) 下載
2. 將 `ffmpeg.exe` 所在目錄添加到系統 `PATH` 環境變量
3. 驗證：`ffmpeg -version`

---

## 目錄結構

首次啓動後，程序會在工作目錄生成以下文件：

```
工作目錄/
├── config/                      # 運行時配置目錄
│   ├── config.yaml              # 主配置文件
│   └── image_classifier.properties  # 圖片分類器配置
├── state/                       # 運行狀態目錄
│   ├── setup_config.json        # 賬號/初始化配置
│   ├── batch_state.json         # 批量下載狀態
│   └── gui/                     # GUI 引導、代理步驟等本地狀態標記
├── data/                        # 應用數據目錄
│   ├── pixiv_download.db        # SQLite 數據庫（含 -wal / -shm 伴隨文件）
│   ├── collection_icons/        # 用戶自定義收藏夾圖標
│   ├── gallery_thumbs/          # 圖庫二進制縮略圖緩存
│   ├── tts/                     # TTS 版本號本地緩存
│   ├── backfill/                # 數據回填工具本地狀態
│   └── narration-voice/         # 多角色朗讀參考音音頻
├── plugins/                     # 外置插件原始包
│   ├── provenance/              # 已安裝插件的 provenance sidecar
│   └── runtime/                 # JAR-with-lib / ZIP 物化後的運行時緩存
├── pixiv-download/              # 下載文件存儲（默認根目錄）
│   ├── artwork-{id}/            # 作品目錄
│   ├── novel-{id}/              # 小說目錄
│   ├── novel-series-{id}/       # 小說系列
│   └── ...
└── log/                         # 運行日誌
```

---

## 驗證安裝

啓動後在瀏覽器訪問：

- `http://localhost:6999/` — 自動跳轉到下載頁
- `http://localhost:6999/setup.html` — 首次配置嚮導（如已完成則重定向）
- `http://localhost:6999/intro.html` — 產品介紹頁
- `http://localhost:6999/pixiv-batch.html` — 批量下載頁（需要 `download-workbench`，Java 標準包、離線全量包和 Windows 默認安裝包均已攜帶）
- `http://localhost:6999/monitor.html` — 下載監控頁
- `http://localhost:6999/pixiv-gallery.html` — 作品畫廊（需要可選 `gallery` 插件）

---

## 官方插件與安裝包

`download-workbench` 是 required 外置插件，負責下載頁、下載 API、隊列、userscript 入口、Pixiv 插畫代理和計劃任務宿主。缺失、損壞、不兼容或離線複驗失敗時，程序進入恢復路徑，只開放登錄、插件管理和安裝修復入口。

官方插件默認安裝集合包括 `download-workbench`、`stats`、`duplicate`、`gallery`、`novel`、`notification`、`push`、`mail`、`tts`、`ai` 和 `gui-theme`；`douyin` 是唯一的按需安裝插件。缺失或禁用可選插件只會讓對應頁面、API、導航、靜態資源、i18n、GUI 配置字段或能力貢獻缺席，不會讓程序進入恢復路徑。

- Windows 安裝包：內置 JRE，預置除 `douyin` 外的全部官方插件；`douyin` 可從 Web 插件市場按需安裝。
- Java 標準包（`*-java.zip`）：與 Windows 安裝包默認插件集合一致，不含 `douyin`；不含 JRE、不含 FFmpeg。
- 離線全量包（`*-full-offline.zip`）：在 Java 標準包集合基礎上額外攜帶 `douyin`；不含 JRE、不含 FFmpeg。
- `duplicate` 缺失不影響圖片 Hash 寫入和歷史 Hash 數據。
- `gallery` 缺失不影響下載頁、下載 API、userscript、Pixiv 插畫代理、計劃任務宿主、作品元數據、下載事實、Hash 與本地資源索引。
- `novel` 缺失不影響小說下載核心、正文保存、翻譯狀態、系列合訂、計劃任務小說執行器、TTS / AI 能力降級與歷史數據讀取。
