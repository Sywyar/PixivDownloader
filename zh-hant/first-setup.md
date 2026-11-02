# 首次設定

首次設定用於建立 PixivDownloader 本程式的管理員帳號，並設定後端存取外部服務時使用的 HTTP 代理。Pixiv 登入 Cookie 在進入下載頁後另行儲存。

完成[安裝與啟動](/zh-hant/installation)後，依執行環境選擇初始化方式：

| 執行環境 | 初始化入口 | 初始化後操作 |
| --- | --- | --- |
| 本機桌面 | 桌面首次引導，或本機瀏覽器存取 `http://localhost:6999/setup.html` | 開啟下載頁並登入 |
| 無桌面伺服器（`--no-gui`） | 先執行 `--setup` | 再以 `--no-gui` 啟動 |
| Docker | 先執行 `docker compose run --rm app --setup` | 再執行 `docker compose up -d` |

## 桌面或本機瀏覽器初始化

1. 啟動程式並等待後端就緒。若提示缺少必要外掛或資料庫無法使用，先依[外掛管理](/zh-hant/plugin-management)修復；重新初始化帳號不會解決外掛或資料庫故障。
2. 設定管理員使用者名稱與密碼。密碼至少 8 碼，建議 12 碼以上；使用 8–11 碼密碼時需再次確認。
3. 依實際網路填寫 HTTP 代理主機與連接埠（如本機代理 `127.0.0.1` 與 `7890`）；可直接連線至目標服務時，可關閉代理。
4. 儲存後存取 `http://localhost:6999/pixiv-batch.html`，使用剛建立的管理員帳號登入。

桌面與瀏覽器初始化介面固定使用自用模式（`solo`）。`setup.html` 僅允許本機迴路存取；遠端伺服器或容器請使用下方 CLI 初始化。後續修改一般設定請在桌面「設定」頁儲存，代理與各外掛網路路徑的適用範圍見[網路存取與第三方服務](/zh-hant/network-access)。

## 命令列初始化（伺服器 / 無頭模式）

進入完整解壓縮後的發行目錄，確認同一資料目錄沒有正在執行的執行個體。將下方 `APP.jar` 替換為實際主程式 JAR 名稱：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --mode=solo
```

依提示輸入管理員使用者名稱、密碼和代理設定。帳號狀態寫入 `state/setup_config.json`，代理設定寫入 `config/config.yaml`。完成後啟動無頭服務：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

自動化腳本可一次傳入全部參數（請將密碼與代理替換為實際值；命令列參數會出現在 Shell 歷史紀錄和處理程序清單中）：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

不需要代理時使用 `--proxy-enabled=false`。CLI 仍支援 `--mode=solo` 或 `--mode=multi`；訪客唯讀分享優先使用[進階參考中的訪客邀請](/zh-hant/usage-guide#訪客邀請)。

## Docker 初始化

依 [Docker 安裝說明](/zh-hant/installation#方式三：docker-部署)準備好映像檔與掛載目錄後，在 `docker-compose.yml` 所在目錄執行：

```bash
docker compose run --rm app --setup --mode=solo
docker compose up -d
docker compose logs -f app
```

容器內 `127.0.0.1` 指向容器本身；使用宿主機代理時，將代理主機填寫為 `host.docker.internal`，並確認宿主代理允許該連線。尚未完成 `--setup` 就啟動無頭服務，會以結束代碼 `78` 結束。

## 確認結果與修改密碼

用管理員帳號登入下載頁，確認工作台正常載入，再依[第一次下載](/zh-hant/first-download)測試下載。若儲存或啟動失敗，保留錯誤訊息並檢查 `log/latest.log`、目錄寫入權限、連接埠占用與資料庫狀態，不要靠反覆刪除初始化檔案排解問題。

停止正在執行的執行個體後，可在發行目錄修改或重設管理員密碼：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --change-password
java -Dfile.encoding=UTF-8 -jar APP.jar --reset-password
```

`--change-password` 需驗證原密碼，`--reset-password` 用於忘記密碼時在本機強制重設。執行成功後，現有登入工作階段全部失效，作品檔案與資料庫不受影響。僅在確實需要重建初始化狀態時，才在停止程式並備份後，移走 `state/setup_config.json` 並重新執行 `--setup`。
