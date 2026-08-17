# PixivDownloader

[简体中文](./README.md) | 繁體中文 | [日本語](./README_ja.md) | [한국어](./README_ko.md) | [English](./README_en.md)

> [!NOTE]
> 此文檔中提及的作品範圍包括 插畫/漫畫/動圖/小說

### 本地 Pixiv 作品批量下載工具，支持小說/漫畫的各種類型下載

- 批量通過作品鏈接下載作品
- 通過用戶ID批量下載作品
- 通過內置搜索代理批量下載作品
- 通過輸入作品系列鏈接或者系列中作品鏈接批量下載整個系列作品
- 通過油猴腳本在 Pixiv 網頁上抓取插畫/漫畫/動圖/小說，或在單作品頁直接下載
- 強大的作品/小說畫廊

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![GitHub Repo stars](https://img.shields.io/github/stars/Sywyar/PixivDownloader)](https://github.com/Sywyar/PixivDownloader/stargazers)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/Sywyar/PixivDownloader)](../../releases)

## 功能特點

> [!WARNING]
> **標記 `*` 的功能尚未在正式版中上線，僅每夜構建版可用**

- 一站式下載網頁，支持快捷獲取、批量導入單作品、User 模式、Search 模式、系列模式
- 快捷獲取：憑已保存的 Cookie 一鍵拉取本賬戶的收藏（插畫/小說，含不公開）、自己的作品（含不公開）、關注列表、珍藏集，可鑽取查看並加入下載隊列
- 頁面批量下載腳本 — 抓取搜索頁、關注動態、排行榜等 Pixiv 頁面中的插畫/漫畫/動圖/小說
- 體驗增強工具箱腳本（已下載標記、Cookie 導入）
- 強大的作品/小說畫廊，支持搜索範圍選擇、篩選排序和收藏夾
- 小說畫廊支持「正文」全文檢索（基於本地全文索引，可與年齡分級/標籤/作者等篩選疊加）
- 統計儀表盤：總覽卡片、按月下載量折線、下載量 Top 作者、熱門標籤詞雲，作者/標籤可點擊直達畫廊篩選
- 疑似重複檢測：基於感知哈希（dHash）識別實質重複的已下載圖片，支持閾值調節、跨作品/全部範圍切換與手動掃描回填
- `*` 插件管理頁：卡片列表展示所有插件的狀態/來源/版本/依賴，支持外部插件的生命週期操作（加載/啓動/停止/卸下/重載）（未上線）
- `*` 插件市場頁：瀏覽/搜索/篩選受信倉庫插件，查看詳情並安裝（重啓生效）；倉庫列表可在桌面 GUI 配置頁維護（未上線）
- 計劃任務：後臺按週期或 Cron 自動發現並下載新作品，支持畫師新作/保存的搜索/系列三類來源
- 郵件/推送通知：需人工介入的事件（鑑權失效、熔斷等）通過郵件與推送通道告知；可在通知配置頁按類型開關
- 小說下載與系列合訂（TXT/HTML/EPUB，EPUB 支持多級目錄和內嵌圖片）
- 小說 AI 翻譯（需配置大模型）：把正文或整個系列翻譯成指定語言並保存到本地，可在原文與譯文之間切換查看
- 小說 AI 多角色朗讀（beta）：大模型逐句歸屬說話人，各角色固定音色合成並連續播放跟隨高亮，分析結果可緩存重播
- 動圖 (Ugoira) 自動轉 WebP
- 自定義文件名模板（11 個變量）
- 已下載校驗：數據庫與磁盤不一致時自動清理髒記錄或反向恢復記錄
- 多用戶場景配額和限流功能
- 訪客邀請系統（分級/標籤/作者白名單）
- 多語言/暗色模式
- 桌面 GUI（Swing + FlatLaf），在線更新

## 使用截圖

> [!NOTE]
> 少許截圖設備啓用了 HDR，顏色效果可能不同

### [淺色模式使用截圖](./zh-CN/md/light-screenshot.md)

### [暗色模式使用截圖](./zh-CN/md/dark-screenshot.md)

## 快速開始

### 下載

從 [Releases](../../releases) 下載最新版：

| 類型                                  | 說明                                 |
|-------------------------------------|------------------------------------|
| `PixivDownload-*-win-x64-setup.exe` | Windows 安裝包，支持修復/更改/卸載，可選安裝 FFmpeg；預置除 Douyin 外的官方插件 |
| `PixivDownload-*-java.zip`          | Java 標準包（跨平臺），需 Java 17；與 Windows 安裝包默認插件集合一致，不含 Douyin |
| `PixivDownload-*-full-offline.zip`  | 離線全量包（跨平臺），需 Java 17；包含含 Douyin 在內的全部面向用戶的官方插件 |

> 核心殼 `PixivDownload-*.jar` 僅作爲內部構建輸入，不作爲普通用戶附件提供；單獨運行它缺少必需的
> `download-workbench` 外置插件，會進入恢復/修復模式。

Java 標準包和離線全量包必須**完整解壓**後使用，不要只提取其中的 JAR：啓動腳本與 `plugins/` 目錄
缺一不可，程序啓動時會從工作目錄的 `plugins/` 加載官方外置插件。

### 啓動

```bash
# Windows 安裝包
PixivDownload.exe

# Java 標準包 / 離線全量包（Windows）
run.bat

# Java 標準包 / 離線全量包（Linux/macOS，需 Java 17）
sh run.sh

# 可選參數
--no-gui    # 禁用 GUI，純命令行運行（適合服務器/Docker）
--intro     # 啓動時打開產品介紹頁
```

首次啓動後按引導完成配置，即可訪問 `http://localhost:6999/pixiv-batch.html` 開始下載。

### 讓網頁版 Pixiv 走後端配置的代理（無需開啓系統代理）

後端訪問 Pixiv 走配置裏指定的代理（默認 `127.0.0.1:7890`），不依賴系統代理。如果你還希望在瀏覽器裏直接打開 `pixiv.net`（例如配合油猴腳本），又不想爲此開啓 Clash 的「系統代理 / system proxy」，可以使用內置的代理自動配置（PAC）：

在系統或瀏覽器的「自動代理配置腳本（PAC）URL」處填入 `http://localhost:6999/proxy.pac`（端口與你的配置一致；啓用 HTTPS 時爲 `https://<域名>:<端口>/proxy.pac`），即可讓僅 Pixiv 相關域名走後端配置的同一個代理、其餘流量直連。該地址僅本機可訪問，代理變更（含熱重載）會自動反映到 PAC 內容；不再需要來回切換系統代理。

各瀏覽器 / 系統的具體設置入口地址（Firefox `about:preferences#general`、Windows `ms-settings:network-proxy` 等）見[配置參考 · 讓網頁版 Pixiv 走同一個代理](https://sywyar.github.io/PixivDownloader/#/zh-hant/configuration)。

---

## 在線文檔

詳細的安裝步驟、使用指南、配置參考、開發指南等請查閱[在線文檔](https://sywyar.github.io/PixivDownloader/#/zh-hant/)，也可切換到[繁體中文文檔](https://sywyar.github.io/PixivDownloader/#/zh-hant/)。各章節快速跳轉：

**快速上手**

- [📥 安裝與啓動](https://sywyar.github.io/PixivDownloader/#/zh-hant/installation)
- [⚙️ 首次配置](https://sywyar.github.io/PixivDownloader/#/zh-hant/first-setup)
- [⬇️ 第一次下載](https://sywyar.github.io/PixivDownloader/#/zh-hant/first-download)

**功能指南**

- [⚡ 快捷獲取](https://sywyar.github.io/PixivDownloader/#/zh-hant/quick-access)
- [📋 URL 批量下載](https://sywyar.github.io/PixivDownloader/#/zh-hant/batch-download)
- [👤 畫師批量下載](https://sywyar.github.io/PixivDownloader/#/zh-hant/user-download)
- [🔍 搜索下載](https://sywyar.github.io/PixivDownloader/#/zh-hant/search)
- [📖 小說下載](https://sywyar.github.io/PixivDownloader/#/zh-hant/novel)
- [🖼️ 作品畫廊](https://sywyar.github.io/PixivDownloader/#/zh-hant/gallery)
- [⏰ 計劃任務](https://sywyar.github.io/PixivDownloader/#/zh-hant/scheduled-tasks)
- [🧩 油猴腳本](https://sywyar.github.io/PixivDownloader/#/zh-hant/userscripts)

**參考**

- [⚙️ 配置參考](https://sywyar.github.io/PixivDownloader/#/zh-hant/configuration)
- [🔌 插件管理](https://sywyar.github.io/PixivDownloader/#/zh-hant/plugin-management)
- [💾 存儲原理](https://sywyar.github.io/PixivDownloader/#/zh-hant/storage)
- [❓ 常見問題](https://sywyar.github.io/PixivDownloader/#/zh-hant/faq)
- [🛠️ 開發指南](https://sywyar.github.io/PixivDownloader/#/zh-hant/development)

---

## 免責聲明

- 本項目僅供個人學習和研究使用，請勿用於任何商業用途。
- 使用本工具下載的內容版權歸原作者所有，請尊重創作者權益，不得二次傳播或商業使用。
- 本工具通過用戶自行提供的 Cookie 或在經過用戶允許下通過油猴腳本提取 Cookie 來訪問 Pixiv，使用者需自行承擔賬號風險
- 本項目與 Pixiv 官方無任何關聯，使用本工具產生的一切後果由使用者自行負責。
- 請合理設置下載間隔，避免對 Pixiv 服務器造成過大壓力。

---

## 閒言碎語

說真的我其實並不推薦這個工具的多人模式，因爲所有的請求走的都是服務器網絡的IP，就算cookie不一樣請求量大也有可能封IP，我也在考慮在多人模式下添加一個登錄機制，但與項目方便的初衷背道而馳，目前只會繼續打磨這個項目

## 友情鏈接

**[PixivBatchDownloader](https://github.com/xuejianxianzun/PixivBatchDownloader)**
如果您喜歡簡約，不想依賴後端程序可以試試這個腳本

功能介紹：

- 超多篩選支持
- 有一些輔助功能，如去除廣告、快速收藏、看圖模式等 `(可以當作一個 Pixiv 的輔助插件？)`
- 下載不依賴第三方工具 `(與本項目最大的區別！安裝十分方便！我也在努力將我的項目的使用變得簡潔)`
- 支持多語言

## 開發計劃
