# PixivDownloader Wiki

PixivDownloader 是一款**本地 Pixiv 作品批量下載工具**，基於 Spring Boot 3.5.7 / Java 17 構建，支持可替換桌面 GUI provider（默認 Swing + FlatLaf，可選 Compose Multiplatform + Material 3）、Web 界面和 Tampermonkey 油猴腳本三種交互方式。

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/Sywyar/PixivDownloader)](https://github.com/Sywyar/PixivDownloader/releases)

?> 本文檔中「作品」涵蓋插畫、漫畫、動圖（Ugoira）和小說。

---

## 核心功能

| 功能 | 說明 |
|----|------|
| ⚡ **快捷獲取** | 一鍵拉取你在 Pixiv 的收藏、關注、珍藏集 |
| 🎨 **批量導入** | 粘貼作品 URL / ID 列表，支持插畫小說混合 |
| 👤 **畫師下載** | 輸入畫師 ID 或鏈接，下載其全部作品 |
| 🔍 **搜索下載** | 內置搜索代理，關鍵詞搜索預覽後批量下載 |
| 📚 **系列下載** | 整個漫畫/小說系列一鍵下載，自動跟進更新 |
| ⏰ **計劃任務** | 後臺定時自動發現並補充新作品，無需手動操作 |
| 📖 **小說下載** | TXT / HTML / EPUB 三種格式，支持系列合訂 |
| 🎬 **動圖轉換** | Ugoira 自動通過 ffmpeg 轉爲 WebP |
| 🖼️ **作品畫廊** | 本地畫廊，支持搜索、篩選、收藏夾管理 |
| 🧩 **油猴腳本** | 在 Pixiv 頁面直接操作，6 個專用腳本 + All-in-One 整合包 |
| 🌐 **多語言/暗色模式** | 中英雙語，所有頁面支持暗色模式 |
| 👥 **多人模式** | 多用戶共享服務器，配額與限流控制 |
| 🔗 **訪客邀請** | 邀請碼分享畫廊，支持內容分級/標籤/作者白名單 |

?> Windows 安裝包與 Java 標準包預置默認安裝的官方插件集合，其中包含必需的 `download-workbench` 和默認 `gui-swing` provider，不含按需安裝的 `douyin` 與 `gui-compose`；離線全量包會額外攜帶二者。畫廊、小說、統計、疑似重複、TTS、AI、通知、推送、郵件和桌面 GUI provider 均保持爲獨立官方插件。

---

## 新手入門

第一次使用？按以下順序閱讀：

1. **[📥 安裝與啓動](/zh-hant/installation)** — 下載安裝，環境要求
2. **[⚙️ 首次配置](/zh-hant/first-setup)** — 設置管理員賬號、運行模式、代理
3. **[⬇️ 第一次下載](/zh-hant/first-download)** — 完整的入門下載教程

---

## 按需查看

| 我想要… | 查看 |
|---------|------|
| 下載我的 Pixiv 收藏 | [快捷獲取](/zh-hant/quick-access) |
| 粘貼鏈接批量下載 | [URL 批量下載](/zh-hant/batch-download) |
| 下載某位畫師的全部作品 | [畫師批量下載](/zh-hant/user-download) |
| 按關鍵詞搜索下載 | [搜索下載](/zh-hant/search) |
| 下載小說/合訂 EPUB | [小說下載](/zh-hant/novel) |
| 整理和瀏覽已下載作品 | [作品畫廊](/zh-hant/gallery) |
| 定時自動下載新作品 | [計劃任務](/zh-hant/scheduled-tasks) |
| 在 Pixiv 網頁上直接下載 | [油猴腳本](/zh-hant/userscripts) |
| 查看所有配置項說明 | [配置參考](/zh-hant/configuration) |
| 瞭解文件存在哪裏、如何搬遷備份 | [存儲原理](/zh-hant/storage) |
| 解決常見問題 | [常見問題](/zh-hant/faq) |

---

## 項目信息

| 項目 | 詳情 |
|------|------|
| **作者** | [Sywyar](https://github.com/Sywyar) |
| **許可證** | [GNU AGPL v3](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) |
| **語言** | 中文 / English |
| **Java 版本** | 17 |
| **框架** | Spring Boot 3.5.7 |
| **數據庫** | SQLite (WAL 模式) |
| **默認端口** | 6999 |

## 免責聲明

本項目僅供個人學習和研究使用，請勿用於商業用途。使用本工具下載的內容版權歸原作者所有，請尊重創作者權益。本工具通過用戶自行提供的 Cookie 訪問 Pixiv，使用者需自行承擔賬號風險。本項目與 Pixiv 官方無任何關聯。請合理設置下載間隔，避免對 Pixiv 服務器造成過大壓力。
