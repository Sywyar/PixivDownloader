# 配置參考

PixivDownloader 的配置按所有權分爲三類，不能混用：

| 配置類型 | 路徑 | 所有者 |
| --- | --- | --- |
| 宿主配置和插件啓用狀態 | `config/config.yaml` | 應用殼 |
| 插件業務配置 | `config/plugins/{pluginId}.properties` | 對應插件 |
| 插件憑據 | `config/credentials/{pluginId}.properties` | 對應插件；由宿主加密存儲 |

推薦在桌面 GUI 的“配置”頁修改。首次啓動時，應用會用當前版本的默認模板創建 `config/config.yaml`；升級後只追加缺失的宿主鍵，不覆蓋已有值。手工編輯時使用 UTF-8，並保留活躍的 `key: value` 行，不要用註釋行代替空值。

## 宿主配置

### 服務、調試和下載

| 鍵 | 默認值 | 說明 |
| --- | --- | --- |
| `server.port` | `6999` | HTTP/HTTPS 服務端口 |
| `debug.enabled` | `false` | 調試模式 |
| `download.root-folder` | `pixiv-download` | 作品產物根目錄 |
| `download.user-flat-folder` | `false` | 畫師目錄是否使用平鋪佈局 |
| `download.max-concurrent` | `10` | 宿主下載併發上限；最多另排隊 100 個作品，隊列已滿時新任務返回 429 |
| `database.maximum-pool-size` | `28` | SQLite 連接池上限 |

`download.root-folder` 只存放下載作品。配置、數據庫、插件狀態和緩存不會寫入該目錄。小說、Douyin 等下載類型的私有設置不屬於宿主配置；安裝插件後由插件自己的配置貢獻管理。

Pixiv 作品圖片、小說封面和內嵌圖的固定安全上限爲每張 100 MiB；單個普通插畫或小說下載任務內的這些響應累計最多 1 GiB。服務同時檢查 `Content-Length` 和實際解碼後的響應流，超過上限會中止傳輸並清理部分文件。最終擴展名根據 URL 路徑、響應 `Content-Type` 與已驗證的文件頭從圖片白名單中選擇，查詢參數不會進入文件名。這些上限不能通過配置提高。

Ugoira 處理將 ZIP 下載限制爲 100 MiB、最多 500 個條目、單條目最多展開 32 MiB、總展開量最多 200 MiB、單條目壓縮比最多 100:1、最多 500 幀且單幀最多 25,000,000 像素。Ugoira 轉換同一時間只運行一個 ffmpeg 進程；每個進程最多運行 10 分鐘並輸出 100 MiB。達到任一上限都會終止處理，並清理 ZIP、已解壓幀和部分輸出。

### 插件市場

| 鍵 | 默認值 | 說明 |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | 插件市場主開關；打開 / 刷新市場或安裝插件時訪問倉庫，關閉後不訪問任何倉庫 |
| `plugin-catalog.official-repository-enabled` | `true` | 是否啓用內嵌官方倉庫 |
| `plugin-catalog.connect-timeout-ms` | `15000` | 全局連接超時 |
| `plugin-catalog.read-timeout-ms` | `60000` | 全局讀取超時 |
| `plugin-catalog.max-manifest-bytes` | `1048576` | 單個清單上限 |
| `plugin-catalog.max-package-bytes` | `104857600` | 單個插件包上限 |
| `plugin-catalog.repositories` | 空列表 | 自定義倉庫列表 |

官方倉庫的地址和信任根內嵌於程序。自定義倉庫必須顯式配置自己的 HTTPS 清單和 Ed25519 公鑰，不繼承官方信任根。推薦用 GUI 的倉庫編輯器；手寫示例：

```yaml
plugin-catalog.enabled: true
plugin-catalog.repositories:
  - id: example
    display-name-key: plugin.market.repository.example.name
    manifest-url: https://plugins.example.com/manifest.json
    enabled: true
    proxy-policy: direct-strict
    trusted-keys:
      - key-id: example-2026
        algorithm: Ed25519
        public-key: BASE64_X509_SUBJECT_PUBLIC_KEY_INFO
        state: ACTIVE
        publisher: Example Publisher
        trust-label: Example repository release key
```

倉庫 id 必須唯一，且不能使用保留值 `official` 或 `configured`。代理策略爲：

- `direct-strict`：直連、僅 HTTPS、拒絕非公網地址和重定向。
- `proxy-trusted`：使用應用代理，只對內置信任主機允許最多五次重定向；每一跳都會重新校驗。
- `custom`：使用條目中的 `allow-redirects`、`strict-https`、`allow-non-public-addresses` 和 `use-proxy`。

倉庫條目還可覆蓋 `connect-timeout-ms`、`read-timeout-ms`、`max-manifest-bytes`、`max-package-bytes`；省略或設爲 `0` 表示繼承全局值。

### 出站代理

| 鍵 | 默認值 | 說明 |
| --- | --- | --- |
| `proxy.enabled` | `true` | 啓用宿主出站 HTTP 代理 |
| `proxy.host` | `127.0.0.1` | 代理主機 |
| `proxy.port` | `7890` | 代理端口 |

插件需要代理時應使用穩定 HTTP/WebSocket SDK 或 `core-api` 的代理語義端口，不應讀取宿主 `ProxyConfig` 實現類。

### 多人模式配額和限流

| 鍵 | 默認值 |
| --- | --- |
| `multi-mode.quota.enabled` | `true` |
| `multi-mode.quota.max-artworks` | `50` |
| `multi-mode.quota.reset-period-hours` | `24` |
| `multi-mode.quota.archive-expire-minutes` | `60` |
| `multi-mode.quota.limit-image` | `0` |
| `multi-mode.quota.max-proxy-requests` | `200` |
| `multi-mode.quota.archive-max-concurrent` | `10` |
| `multi-mode.post-download-mode` | `pack-and-delete` |
| `multi-mode.delete-after-hours` | `72` |
| `multi-mode.request-limit-minute` | `300` |
| `multi-mode.static-resource-request-limit-minute` | `1200` |

`multi-mode.post-download-mode` 支持 `pack-and-delete`、`never-delete`、`timed-delete`。`multi-mode.limit-page=0` 表示不限制頁數；當前默認值爲 `3`。

邀請訪客在 solo 與 multi 模式都使用獨立限流：

| 鍵 | 默認值 |
| --- | --- |
| `guest-invite.request-limit-minute` | `300` |
| `guest-invite.static-resource-request-limit-minute` | `1200` |
| `guest-invite.tts-request-limit-minute` | `30` |
| `setup.login-rate-limit-minute` | `10` |

### 維護窗口

`maintenance.enabled` 默認 `true`。每日默認值如下：

| 星期 | 啓用鍵/默認值 | 時間鍵/默認值 |
| --- | --- | --- |
| 星期一 | `maintenance.monday.enabled=true` | `maintenance.monday.time=10:00` |
| 星期二 | `maintenance.tuesday.enabled=false` | `maintenance.tuesday.time=10:00` |
| 星期三 | `maintenance.wednesday.enabled=false` | `maintenance.wednesday.time=10:00` |
| 星期四 | `maintenance.thursday.enabled=false` | `maintenance.thursday.time=10:00` |
| 星期五 | `maintenance.friday.enabled=false` | `maintenance.friday.time=10:00` |
| 星期六 | `maintenance.saturday.enabled=false` | `maintenance.saturday.time=10:00` |
| 星期日 | `maintenance.sunday.enabled=false` | `maintenance.sunday.time=10:00` |

### HTTPS 與反向代理

| 鍵 | 默認值 |
| --- | --- |
| `ssl.domain` | `localhost` |
| `ssl.type` | `pem` |
| `server.ssl.enabled` | `false` |
| `server.ssl.certificate` | 空 |
| `server.ssl.certificate-private-key` | 空 |
| `server.ssl.key-store-type` | `JKS` |
| `server.ssl.key-store` | 空 |
| `server.ssl.key-store-password` | 空 |
| `server.trusted-proxy-cidrs` | 空 |
| `ssl.http-redirect` | `false` |
| `ssl.http-redirect-port` | `80` |

`ssl.type=pem` 使用證書和私鑰路徑；`ssl.type=jks` 使用 key store。不要把證書私鑰或 key store 密碼提交到倉庫。

`server.trusted-proxy-cidrs` 是使用反向代理時的信任邊界。它只接受逗號分隔的數字 IPv4/IPv6 CIDR，例如：

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

只填寫實際連接後端的反向代理出口地址或容器網段，不要填寫客戶端網段，也不要信任 `0.0.0.0/0` 或 `::/0`。留空時應用按直連模式運行，並拒絕任何 `Forwarded`、`X-Forwarded-*` 或 `X-Real-IP` 請求頭。

受信代理必須爲每個請求提供以下一種完整格式，不能混用：

- RFC `Forwarded`：所選代理邊界必須同時包含 `for`、`proto` 和 `host`；
- 傳統格式：`X-Forwarded-For`、`X-Forwarded-Proto`、`X-Forwarded-Host`，可選 `X-Forwarded-Port`。

應用從代理鏈右側向左查找首個非受信地址作爲客戶端地址，並在鑑權、限流和 CSRF 同源判斷前統一規範化客戶端地址、外部協議、主機與端口。未受信來源提供轉發頭，代理鏈沒有非受信客戶端地址，或受信代理缺少、混用、錯位、僞造格式的代理元數據時，請求會返回 400。代理必須覆蓋所有到後端的請求；如果把 `127.0.0.1/32` 設爲受信代理，同一地址發起但沒有代理頭的直連請求也會被拒絕。

### 語言與桌面界面

| 鍵 | 默認值 | 說明 |
| --- | --- | --- |
| `app.language` | 空 | 跟隨系統；也可使用受支持語言代碼 |
| `app.theme` | `system` | GUI 主題 id |
| `app.config-menu-expand-all` | `false` | 是否默認展開全部配置菜單 |

可用主題由已安裝的主題插件貢獻；配置值不是宿主對具體主題實現的硬編碼清單。

### 在線更新

| 鍵 | 默認值 |
| --- | --- |
| `update.enabled` | `true` |
| `update.manifest-url` | 官方最新正式版 `update.json` |
| `update.nightly-manifest-url` | 官方 nightly `update.json` |
| `update.auto-check` | `true` |
| `update.check-nightly` | nightly 構建爲 `true`，正式版爲 `false` |

### 計劃任務宿主

| 鍵 | 默認值 |
| --- | --- |
| `schedule.enabled` | `true` |
| `schedule.tick-interval-ms` | `60000` |
| `schedule.max-tasks` | `100` |
| `schedule.inbox-check-every` | `500` |
| `schedule.auth-failure-circuit-breaker` | `5` |
| `schedule.pending-max-attempts` | `5` |
| `schedule.overuse-defer-default-minutes` | `60` |

這些鍵配置中性的計劃任務宿主。具體下載來源、認證信息和下載參數由相應插件貢獻並擁有。

### 插件啓用狀態

`plugins.{pluginId}.enabled` 由宿主管理，例如：

```yaml
plugins.douyin.enabled: true
```

required 插件不能通過該鍵禁用。是否能即時生效由插件的 `pixiv.lifecycle-policy` 與當前生命週期操作決定，詳見[插件管理](/zh-hant/plugin-management)。

## 插件業務配置

每個插件只寫 `config/plugins/{pluginId}.properties`。文件使用 UTF-8 Java properties 語法：

```properties
example.timeout-ms=15000
example.output-format=json
```

宿主會拒絕插件配置文件覆蓋宿主默認鍵、`plugins.*.enabled` 或看起來像憑據的鍵。不同插件文件中的鍵也不應重複。插件子 Spring 上下文通過 `Environment`、`@Value` 或 `@ConfigurationProperties` 讀取這些值；第三方插件不應直接讀配置文件，也不應依賴應用殼的配置類。

插件 GUI 配置貢獻是字段定義和保存入口的事實來源。保存後，宿主會刷新插件配置源，並按字段與插件生命週期策略給出即時生效、後端重啓或完整進程重啓結果。手工編輯後不確定時，完整重啓最穩妥。

## 插件憑據

密碼、Cookie、Token、API key、secret 和 webhook key 等敏感值寫入 `config/credentials/{pluginId}.properties`。宿主負責加密、權限、遷移和按 owner 注入；插件只從自己的子上下文 `Environment` 讀取已經解密的屬性值。

不要把憑據放入 `config.yaml` 或 `config/plugins/*.properties`，也不要讓插件讀取、解析或解密憑據文件。備份憑據時必須同時保護憑據主密鑰；缺少原主密鑰時，加密值不能在另一環境中恢復。

## 如何確認當前配置

1. 先看 GUI 配置頁；它會合並宿主字段與當前已安裝插件的配置貢獻。
2. 宿主默認鍵以當前版本的 `DefaultConfigTemplate` 生成結果爲準。
3. 插件字段以對應插件的 `GuiConfigContribution`、`@ConfigurationProperties` 或設置服務爲準。
4. 不要從舊版示例複製已經外置的 `mail.*`、`push.*`、`notification.*` 或 `download.novel-*` 到 `config.yaml`；它們由各自插件擁有。
