# 首次設定

首次設定用來建立 PixivDownloader 管理員帳號，並設定後端連線時使用的 HTTP 代理。這個帳號用於登入本程式；Pixiv Cookie 要在進入下載頁後另外儲存。

完成[安裝](/zh-hant/installation)後，依啟動方式選擇入口：

| 使用方式 | 初始化方法 | 完成後 |
| --- | --- | --- |
| 本機桌面 | 在桌面引導中建立管理員帳號 | 開啟下載頁並登入 |
| 無桌面伺服器或 `--no-gui` | 先執行 `--setup` | 再以 `--no-gui` 啟動 |
| Docker | 先執行 `docker compose run --rm app --setup` | 再執行 `docker compose up -d` |

## 在桌面完成設定

1. 啟動程式，等待後端就緒。若顯示缺少必要外掛或資料庫無法使用，先依[外掛管理](/zh-hant/plugin-management)排查，重新初始化帳號無法解決這類問題。
2. 在引導中填寫管理員使用者名稱和密碼。密碼至少 8 個字元；程式建議至少 12 個字元，較短的密碼需要再次確認。
3. 依實際網路填寫 HTTP 代理主機和連接埠。例如，代理確實監聽本機的 `7890` 連接埠時，可填寫 `127.0.0.1` 和 `7890`。能直接存取所需服務時，可關閉代理。
4. 完成儲存，開啟 `http://localhost:6999/pixiv-batch.html`，使用剛建立的管理員帳號登入。

桌面與瀏覽器初始化介面使用自用模式（`solo`），不提供多人模式選項。引導的分頁與步驟隨桌面介面而異，以目前視窗提示為準。

本機瀏覽器的 `http://localhost:6999/setup.html` 也能完成帳號和代理設定，儲存後會前往登入頁。此頁面只允許本機存取；無頭啟動在初始化前會結束，應先執行下方的 CLI 命令。

代理只影響採用宿主或任務代理路由的請求。瀏覽器請求、SMTP、資料庫連線和明確直連的請求各有網路路徑，詳見[網路存取與第三方服務](/zh-hant/network-access)。日後修改一般設定，請在桌面設定頁儲存，並依提示套用或重新啟動。

## 在命令列完成設定

先進入完整解壓縮後的發行目錄，並停止同一執行個體。以下命令適用於 PowerShell 或 Bash；將 `APP.jar` 換成目錄中實際的主程式 JAR 檔名，保留同目錄的 `plugins/`。

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --mode=solo
```

依提示輸入使用者名稱、密碼和代理設定。密碼至少 8 個字元；未達建議的 12 個字元時，命令會讓你重新考慮或確認。CLI 仍接受 `--mode=solo` 或 `--mode=multi`；本頁使用 solo，邀請他人瀏覽請參閱[進階使用指南](/zh-hant/usage-guide)。

初始化成功後，帳號狀態寫入 `state/setup_config.json`，提交的代理設定寫入 `config/config.yaml`。接著啟動背景服務：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

需要自動化輸入時，可明確提供全部參數：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

將密碼、主機和連接埠換成自己的值；不需要代理時改用 `--proxy-enabled=false`。命令列密碼可能出現在 Shell 歷史和處理程序清單中，日常操作優先使用互動式輸入。

## 在 Docker 中完成設定

先依[Docker 安裝說明](/zh-hant/installation)準備映像檔和可寫入的掛載目錄，在含有 `docker-compose.yml` 的目錄執行：

```bash
docker compose run --rm app --setup --mode=solo
docker compose up -d
docker compose logs -f app
```

容器中的 `127.0.0.1` 指向容器本身。使用宿主機代理時，依 compose 設定填寫 `host.docker.internal` 和代理實際連接埠，並確認宿主代理接受這個連線。

尚未初始化就啟動無頭服務，會以退出碼 `78` 結束；有自動重啟策略的容器可能反覆重啟。先停止服務、完成 `--setup`，再啟動。

## 確認設定成功

使用管理員帳號登入下載頁，確認可以看到下載工作台，再依[第一次下載](/zh-hant/first-download)新增一部作品。登入成功只證明本程式帳號有效；Pixiv 連線和 Cookie 是否有效仍需分別檢查。

若儲存或啟動失敗，保留錯誤訊息及 `log/` 中對應的紀錄。先檢查是否已有執行個體、設定目錄能否寫入、必要外掛和資料庫是否可用；不要反覆刪除初始化檔案。

## 修改或重設密碼

停止同一執行個體後，在發行目錄執行。修改密碼需要原密碼；忘記原密碼時使用本機重設命令：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --change-password
java -Dfile.encoding=UTF-8 -jar APP.jar --reset-password
```

兩條命令擇一執行。成功後既有工作階段失效，重新啟動並使用新密碼登入。這些命令不會刪除作品和資料庫。

確實需要重新建立初始化狀態時，先停止程式並備份 `state/setup_config.json`，再移走此檔案並重新初始化。這會重設帳號、執行模式和登入狀態；找回密碼應優先使用 `--reset-password`。
