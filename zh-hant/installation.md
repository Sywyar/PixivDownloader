# 安裝指南

先從 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 選擇套件。下列 `*` 或 `x.x.x` 代表所選版本；命令中的 JAR 名稱必須換成實際檔名，並在完整解壓目錄執行。

| 使用情境 | 選擇 | 額外準備 |
| --- | --- | --- |
| Windows x64 桌面 | `*-win-x64-setup.exe` | 依需要安裝 FFmpeg |
| Windows、Linux、macOS 自行執行 Java | `*-java.zip` | Java 17+，依需要安裝 FFmpeg |
| 預裝可選資料庫和匯入外掛 | `*-full-offline.zip` | Java 17+，依需要安裝 FFmpeg |
| Docker 常駐服務 | 使用儲存庫 Dockerfile 與完整 Java 發行目錄建置 | Docker Engine、Compose、可寫入的資料目錄 |

標準套件包含預設官方外掛，離線完整套件另含 `database-postgresql` 和 `pixiv-batch-downloader-import`。兩種 Java 套件都不含 JRE、FFmpeg 或 Douyin；離線完整套件不代表存取 Pixiv、線上 AI 或 TTS 時不需要網路。

## 環境要求

| 依賴 | 最低版本 | 說明 |
|------|----------|------|
| **Java** | 17+ | Java 標準包 / 離線全量包運行必需；Windows 安裝包已內置 JRE |
| **操作系統** | Windows / macOS / Linux | 跨平臺支持 |
| **Tampermonkey** | 最新版 | 如需使用油猴腳本 |
| **FFmpeg** | 支援所選格式的編碼器 | 媒體轉碼使用；MP4 需要 H.264 編碼器 |

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

- `PixivDownload-*-java.zip` — Java 標準包，與 Windows 安裝包的插件集合一致（不含 Douyin）
- `PixivDownload-*-full-offline.zip` — 離線完整套件，額外包含 PostgreSQL 資料庫與 PixivBatchDownloader 匯入外掛（不含 Douyin）

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
> **無頭/`--no-gui` 模式下首次啓動必須先完成 setup**：服務器上沒有 GUI 引導、`setup.html` 又只允許本機訪問，因此未完成首次初始化時 `--no-gui` 啓動會被中止並提示使用 CLI 命令。請先執行：
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
> 代理用於按宿主或任務路由選擇代理的後端訪問，例如 Pixiv 下載、在線更新、FFmpeg 下載和部分在線 TTS；瀏覽器請求、SMTP 和顯式直連策略可能繞過它，完整邊界見[網絡訪問與第三方服務](/zh-hant/network-access)。省略 `--proxy-*` 時會交互式詢問，無需代理可加 `--proxy-enabled=false`。後續可用 `--change-password` 修改密碼、`--reset-password` 在忘記密碼時重置密碼。詳見[首次設定](/zh-hant/first-setup)。

---

## 方式二：Windows 安裝包（推薦 Windows 用戶）

### 1. 下載並運行安裝器

從 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下載 `PixivDownload-x.x.x-win-x64-setup.exe`。

?> Windows 安裝包預置官方插件分發集合，其中包含必需的 `download-workbench`、默認 `gui-compose` 和後備 `gui-swing`。Java 標準套件使用相同預設集合，離線完整套件另含兩項可選外掛；Douyin 作爲普通第三方插件，需從自定義倉庫或本地包安裝。

安裝器寫入應用目錄時會請求 UAC。安裝後的應用和 portable 啓動器也默認請求管理員權限；宿主實際提權運行時，`host-process-full-trust` 插件會繼承同等權限，插件管理頁會持續顯示警告。

### 2. 安裝過程

1. 從安裝器提供的語言中選擇
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

倉庫的 `Dockerfile` 使用已簽章的 Java 分發目錄建立映像，內含 Java 17、FFmpeg 與 curl。先將 Java 標準包或離線全量包完整解壓到倉庫的 `build/dist/default-downloader/`，確認該目錄直接包含主程式 JAR、`plugins/`、`plugins-manifest.json` 與 `SHA256SUMS`，再執行 `docker compose build`。

### 1. 環境要求

- Docker Engine 與 Docker Compose（`docker compose` 命令）。

容器以 UID/GID 10001 執行。Linux 新建實例時，先建立該使用者可寫入的掛載目錄：

```bash
sudo install -d -o 10001 -g 10001 config state data pixiv-download log
```

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

預設連接埠只綁定主機的 `127.0.0.1:6999`，請在主機瀏覽器開啟 `http://127.0.0.1:6999/`。遠端存取可使用既有 SSH 通道或另行設定反向代理；管理員登入後可安裝、查詢與修復外掛。

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

`plugins` 命名卷保存已安裝套件；`config/`、`state/`、`data/`、`pixiv-download/` 與 `log/` 分別掛載到主機。單實例鎖位於 `state/instance/`，SQLite 原生函式庫暫時解壓到 `state/`；這兩個路徑須可寫入，原生函式庫須可載入。根檔案系統保持唯讀，`/tmp` 保留 `noexec,nosuid,nodev`。

更新映像不會自動覆蓋既有外掛卷。重建後在外掛管理頁核對版本與停用狀態；不相容的必要外掛會使主機進入復原模式，應安裝與主機匹配的受信任套件。保留外掛卷、設定、資料庫及下載目錄，停止容器使用 `docker compose down`，不要附加刪除卷的 `-v`。

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
> 通過 Web 端安裝時，腳本更新檢查會指向當前後端。後端位址變更時，重新安裝腳本或更新腳本中的服務位址，並確認 `@connect` 允許新主機。只修改 `@connect` 不會改變請求目的地。

### 方式二：從原始碼倉庫安裝獨立腳本

在 [GitHub 原始碼倉庫](https://github.com/Sywyar/PixivDownloader) 選擇與後端相符的 tag，開啟需要的獨立 `.user.js` 檔案並按 Raw，交由 Tampermonkey 安裝。整合包由後端提供；具體選擇及更新方式見[油猴腳本指南](userscripts.md)。

### 完整腳本列表

| 腳本名稱 | 功能 | 獲取方式 |
|----------|------|----------|
| 頁面批量下載器 (Page Scrape) | 從 Pixiv 頁面 DOM 抓取作品 | Web 端 / GitHub 代碼區 |
| User 批量下載器 (User Batch) | 從用戶主頁批量下載 | Web 端 / GitHub 代碼區 |
| URL 批量導入單作品下載器 (URL Batch) | 批量導入作品 URL | Web 端 / GitHub 代碼區 |
| 單作品圖片下載器 (Java後端版) | 單作品頁通過後端下載 | Web 端 / GitHub 代碼區 |
| 單作品圖片下載器 (Local Download) | 瀏覽器本地下載，無需後端 | Web 端 / GitHub 代碼區 |
| 體驗增強工具箱 (Toolbox) | 已下載作品標記、Cookie 導入等 | Web 端 / GitHub 代碼區 |

### 非 localhost 部署的額外配置

<details>
<summary><strong>點擊展開</strong></summary>

Tampermonkey 的 `@connect` 白名單默認只允許連接 `localhost`。如果後端部署在其他機器：

1. 打開 Tampermonkey 管理面板 → 找到對應腳本 → 點擊編輯
2. 將腳本的服務位址改成實際後端位址，並在 `@connect` 加入對應主機；權限清單不負責選擇目的地
3. 保存腳本（Ctrl+S）

如果通過 Web 端 `pixiv-batch.html` 安裝，`@connect` 會自動替換爲當前後端地址。

</details>

---

## 安裝 FFmpeg（可選）

FFmpeg 用於 Ugoira 動圖轉換爲 WebP，普通圖片下載不需要。

### 自動安裝（推薦）

- **Windows 安裝包**：安裝時勾選「下載並安裝 FFmpeg」
- **GUI 工具**：啓動後在 GUI「狀態」標籤頁點擊「下載 FFmpeg」按鈕

自動安裝使用項目維護的 `ffmpeg-stable` Release，內容來自 FFmpeg 官方最新穩定源碼，並按 Windows x64、Linux x64/arm64、macOS x64/arm64 自動選擇資產。其它平臺請使用系統 FFmpeg。

### 手動安裝

1. 從 [FFmpeg 官網](https://ffmpeg.org/download.html) 下載
2. 將 `ffmpeg.exe` 所在目錄添加到系統 `PATH` 環境變量
3. 驗證：`ffmpeg -version`

---

## 目錄結構

發行目錄的 `plugins/` 保存外掛安裝套件。執行後，設定寫入 `config/`、狀態寫入 `state/`、資料庫與使用者資源及快取寫入 `data/`、紀錄寫入 `log/`。作品預設保存在 `pixiv-download/`，實際路徑也受收藏夾與外掛設定影響。

這些目錄不一定都在首次啟動建立。完整路徑、資料庫伴隨檔案、憑證和備份界線見[儲存原理](/zh-hant/storage)。升級前正常停止程式並備份持久資料；只保留作品檔案會遺失資料庫中的登記。

---

## 驗證安裝

先完成[首次設定](/zh-hant/first-setup)，登入下載頁並新增一部作品。確認下載成功、檔案存在且畫廊能開啟作品，才完成這條使用路徑。健康檢查 `UP` 不代表外掛、Cookie 與下載流程均可用。

可依需要開啟：

- `http://localhost:6999/` — 自動跳轉到下載頁
- `http://localhost:6999/setup.html` — 首次配置嚮導（如已完成則重定向）
- `http://localhost:6999/intro.html` — 產品介紹頁
- `http://localhost:6999/pixiv-batch.html` — 批量下載頁（需要 `download-workbench`，Java 標準包、離線全量包和 Windows 默認安裝包均已攜帶）
- `http://localhost:6999/monitor.html` — 下載監控頁
- `http://localhost:6999/pixiv-gallery.html` — 作品畫廊（需要可選 `gallery` 插件）

---

## 官方插件與安裝包

`download-workbench` 是 required 外置插件，負責下載頁、下載 API、隊列、userscript 入口、Pixiv 插畫代理和計劃任務宿主。缺失、損壞、不兼容或離線複驗失敗時，程序進入恢復路徑，只開放登錄、插件管理和安裝修復入口。

官方插件分發集合包括 `download-workbench`、`database-sqlite`、`gui-compose`、`gui-swing`、`gallery-tools`、`posthog`、`gallery`、`novel`、`notification`、`multi-mode-decision-survey`、`push`、`mail`、`tts` 和 `ai`。`gui-compose` 是默認桌面界面，`gui-swing` 自動後備；Douyin 不再由官方倉庫、簽名或發行包分發。缺失或禁用可選插件只會讓所屬頁面、API、導航、靜態資源、i18n、GUI 配置 section 或能力貢獻缺席，未選用的可選能力缺席不會觸發修復；目前選定的資料庫提供者不可用時，仍會進入修復模式。

- Windows 安裝包：內置 JRE 和官方插件分發集合；FFmpeg 僅在安裝時勾選後下載，不含 Douyin。
- Java 標準包（`*-java.zip`）：插件集合相同；不含 JRE、FFmpeg 與 Douyin。
- 離線完整套件（`*-full-offline.zip`）：預設集合加 `database-postgresql`、`pixiv-batch-downloader-import`，不含 JRE、FFmpeg 與 Douyin。
- `gallery-tools` 缺失不影響圖片 Hash 寫入和歷史 Hash 數據。
- `gallery` 缺失不影響下載頁、下載 API、userscript、Pixiv 插畫代理、計劃任務宿主、作品元數據、下載事實、Hash 與本地資源索引。
- `novel` 缺失會讓小說下載、小說畫廊、正文保存、翻譯狀態、系列合訂和計劃任務小說執行器一併缺席；核心不保留平行實現。
