# 安裝與啟動

從 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 選擇符合環境的發行包。下方檔名中的 `*` 或 `x.x.x` 表示版本號；命令中的 `APP.jar` 請替換為實際主程式 JAR 名稱，並在完整解壓縮目錄內執行。

| 使用情境 | 發行包 | 額外準備 |
| --- | --- | --- |
| Windows x64 桌面 | `*-win-x64-setup.exe` | 內建 JRE；依需求安裝 FFmpeg |
| Windows / Linux / macOS 執行 Java | `*-java.zip` | Java 17+；依需求安裝 FFmpeg |
| 預置選用資料庫、匯入與驗證碼等官方外掛 | `*-full-offline.zip` | Java 17+；依需求安裝 FFmpeg |
| Docker 常駐服務 | 使用儲存庫 `Dockerfile` 與完整 Java 發行目錄建置 | Docker Engine、Compose、可寫入掛載目錄 |

Java 標準包與 Windows 安裝包預置預設官方外掛集合；離線完整包額外包含 `database-postgresql`、`pixiv-batch-downloader-import` 等依需求使用的官方外掛。兩種 Java 套件均不含 JRE、FFmpeg 或第三方 `douyin` 外掛；「離線完整」指預置官方選用外掛，存取 Pixiv、線上 AI 或線上 TTS 仍需網路。

## 方式一：Java 標準包 / 離線完整包

1. 安裝 Java 17 或更高版本（Windows 可從 [Adoptium](https://adoptium.net/) 下載，macOS 可用 `brew install openjdk@17`，Debian/Ubuntu 可用 `sudo apt install openjdk-17-jdk`，Fedora 可用 `sudo dnf install java-17-openjdk`），並驗證版本：

   ```bash
   java -version
   ```

2. 下載 `PixivDownload-*-java.zip` 或 `PixivDownload-*-full-offline.zip` 並**完整解壓縮**。必須保留同目錄的啟動腳本與 `plugins/`，程式啟動時會從工作目錄的 `plugins/` 載入外部外掛。

   ?> 單獨的 `PixivDownload-*.jar` 是不含外部外掛的核心殼建置輸入，不是一般使用者發行包；直接執行不會預置下載工作台或資料庫外掛。

3. 啟動程式：Windows 執行 `run.bat`，Linux / macOS 在解壓縮目錄執行 `sh run.sh`，或手動執行：

   ```text
   java -Dfile.encoding=UTF-8 -jar APP.jar
   ```

   啟動腳本已內建 `-Dfile.encoding=UTF-8`；手動啟動時請保留此參數，避免中文 Windows 主控台亂碼。

4. 依[首次設定](/zh-hant/first-setup)建立管理員帳號並設定代理。

### 無桌面伺服器（`--no-gui`）

伺服器上沒有桌面引導，且 `setup.html` 僅允許本機存取；尚未初始化就直接以 `--no-gui` 啟動，會以結束代碼 `78` 停止。首次啟動前，先在同目錄執行 CLI 初始化：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

需要在自動化腳本中以非互動方式初始化時，可明確傳入參數（請將密碼和代理替換為實際值；命令列密碼會留在 Shell 歷史紀錄或處理程序清單中，日常操作優先使用互動模式）：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

不需要代理時改傳 `--proxy-enabled=false`。Linux / macOS 背景執行範例：

```bash
nohup java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui > app.log 2>&1 &
```

後端代理適用於採用宿主或任務路由的請求（Pixiv 下載、線上更新、FFmpeg 下載和部分線上 TTS）；瀏覽器直接連線、SMTP 與資料庫連線使用各自路徑，詳見[網路存取與第三方服務](/zh-hant/network-access)。

## 方式二：Windows 安裝包

1. 執行 `PixivDownload-x.x.x-win-x64-setup.exe`，選擇安裝語言（支援簡中、繁中、英、日、韓）與安裝目錄。
2. 依需求勾選「下載並安裝 FFmpeg」。一般靜態圖片無需 FFmpeg，安裝後也可在桌面「狀態」頁下載。
3. 完成安裝後啟動程式，或從開始功能表、桌面捷徑啟動。

安裝前若偵測到 `PixivDownload.exe` 仍在執行，安裝程式會提示先關閉後重試。再次執行安裝程式可進入修復、變更元件或解除安裝。安裝程式、已安裝應用程式和可攜式啟動器預設請求管理員權限；宿主實際以高權限執行時，`host-process-full-trust` 外掛會繼承相同權限，外掛管理頁會持續提示此狀態。

## 方式三：Docker 部署

儲存庫 `Dockerfile` 使用已簽署的完整 Java 發行目錄建置映像檔（映像檔內含 Java 17、FFmpeg 和 curl）。

1. 將 `*-java.zip` 或 `*-full-offline.zip` 完整解壓縮至儲存庫 `build/dist/default-downloader/`，確認該目錄直接包含主程式 JAR、`plugins/`、`plugins-manifest.json` 和 `SHA256SUMS`，隨後建置映像檔：

   ```bash
   docker compose build
   ```

2. 容器以 UID / GID `10001` 執行。在 Linux 新建部署時，先在含 `docker-compose.yml` 的目錄建立可寫入掛載目錄：

   ```bash
   sudo install -d -o 10001 -g 10001 config state data pixiv-download log
   ```

3. 執行首次初始化，再啟動常駐容器：

   ```bash
   docker compose run --rm app --setup --mode=solo
   docker compose up -d
   docker compose logs -f app
   ```

!> 尚未完成 `--setup` 就直接執行 `docker compose up -d`，會以結束代碼 `78` 反覆重新啟動。

預設連接埠僅綁定宿主機 `127.0.0.1:6999`，在本機瀏覽器開啟 `http://127.0.0.1:6999/`；遠端存取請使用 SSH 通道或反向代理。停止服務執行 `docker compose down`，保留資料時不要附加刪除磁碟區的 `-v`。

### 容器內代理與持久化目錄

容器內的 `127.0.0.1` 指向容器本身。`docker-compose.yml` 已將 `host.docker.internal` 對應至宿主機閘道；使用宿主機代理時，在 `--setup` 或 `config/config.yaml` 中將代理主機設為 `host.docker.internal`（並確認宿主機代理允許來自容器網段的連線）：

```bash
docker compose run --rm app --setup --mode=solo --proxy-host=host.docker.internal --proxy-port=7890
```

修改掛載的 `config/config.yaml` 後，執行 `docker compose restart app` 生效；若修改 `server.port`，需同步調整 `docker-compose.yml` 的連接埠對應與健康檢查位址。

| 宿主掛載或磁碟區 | 持久化內容 |
| --- | --- |
| `./config/` | `config.yaml`、外掛業務設定與加密憑證 |
| `./state/` | 登入狀態、`setup_config.json`、佇列續傳狀態、執行個體鎖與 SQLite 原生程式庫解壓縮目錄 |
| `./data/` | SQLite 資料庫、收藏夾圖示、縮圖與 TTS 快取、回填狀態、參考音 |
| `./pixiv-download/` | 下載的插畫、漫畫、動圖、小說與暫存封存檔 `_archives` |
| `./log/` | 執行日誌 |
| 具名磁碟區 `plugins` | 已安裝外掛與 provenance |

`state/` 與 `state/instance/` 必須可寫入且允許載入 SQLite 原生程式庫；容器根檔案系統維持唯讀，`/tmp` 維持 `noexec,nosuid,nodev`。更新映像檔不會自動覆寫既有 `plugins` 磁碟區，重建容器後，請在外掛管理頁核對外掛版本與相容狀態。

## FFmpeg 與使用者腳本（選用）

- **FFmpeg**：動圖（Ugoira）轉 WebP / GIF / APNG / MP4 及媒體處理需要 FFmpeg（MP4 需 H.264 編碼器）。可在 Windows 安裝時勾選，或在桌面 GUI「狀態」頁點擊「下載 FFmpeg」（從專案 `ffmpeg-stable` Release 取得經簽章核驗的 Windows x64、Linux x64/arm64、macOS x64/arm64 資源）；也可從 [FFmpeg 官網](https://ffmpeg.org/download.html)安裝並加入系統 `PATH`，用 `ffmpeg -version` 驗證。
- **使用者腳本**：不安裝腳本也可在 `pixiv-batch.html` 完成批次下載。如需在 Pixiv 頁面直接操作，啟動後端後從下載頁「油猴腳本」入口安裝，或從原始碼儲存庫安裝獨立 `.user.js`；非本機部署需同步設定腳本中的後端位址與 `@connect`，詳見[使用者腳本](/zh-hant/userscripts)。

## 驗證安裝與外掛說明

完成[首次設定](/zh-hant/first-setup)後，登入 `http://localhost:6999/` 並依[第一次下載](/zh-hant/first-download)下載一件作品，確認磁碟檔案已產生且畫廊可開啟該作品。`GET /actuator/health` 回傳 `{"status":"UP"}` 僅代表 HTTP 服務已啟動，不代表外掛、資料庫、代理和 Pixiv Cookie 均已就緒。

常用入口：

- `http://localhost:6999/`：預設跳轉至作用中的下載頁
- `http://localhost:6999/setup.html`：本機首次設定頁（初始化後自動重新導向）
- `http://localhost:6999/pixiv-batch.html`：下載工作台（由預設安裝的 `download-workbench` 提供）
- `http://localhost:6999/pixiv-gallery.html`：作品畫廊（由預設安裝的 `gallery` 提供）
- `http://localhost:6999/monitor.html`：下載監控頁

預設安裝的官方外掛包括 `download-workbench`、`database-sqlite`、`gui-compose`（預設桌面介面）、`gui-swing`（備用桌面介面）、`gallery`、`gallery-tools`、`novel`、`notification`、`push`、`mail`、`tts`、`ai`、`posthog`、`multi-mode-decision-survey` 等；`database-postgresql`、`pixiv-batch-downloader-import`、`page-editor`、`captcha`、`translation` 等可依需求從外掛市場或離線完整包取得。目前選定的主資料庫外掛無法使用時，會進入復原模式，僅開放登入、外掛管理與修復入口；選用外掛停用或缺少時，僅撤回其所屬頁面與能力，詳見[外掛管理](/zh-hant/plugin-management)與[儲存與備份](/zh-hant/storage)。
