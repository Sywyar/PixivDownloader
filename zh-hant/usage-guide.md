# 使用指南（進階參考）

本頁彙總各專題頁面未單獨成文的進階操作和參數說明。

常見功能的詳細教程請直接查看對應頁面：

| 功能 | 文檔 |
|------|------|
| 快捷獲取 | [快捷獲取](/zh-hant/quick-access) |
| URL 批量下載 | [URL 批量下載](/zh-hant/batch-download) |
| 畫師批量下載 | [畫師批量下載](/zh-hant/user-download) |
| 搜索下載 | [搜索下載](/zh-hant/search) |
| 小說下載 | [小說下載](/zh-hant/novel) |
| 作品畫廊 | [作品畫廊](/zh-hant/gallery) |
| 計劃任務 | [計劃任務](/zh-hant/scheduled-tasks) |
| 油猴腳本 | [油猴腳本](/zh-hant/userscripts) |

---

## 啓動參數

在發行包完整解壓後的目錄執行命令。以下 `APP.jar` 代表包內實際的主程序 JAR 文件名；請替換後執行。單行命令可用於 PowerShell 或 Bash。

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --help
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

| 參數 | 用途 |
|------|------|
| `--no-gui` | 不啓動桌面 GUI，適用於服務器或 Docker |
| `--intro` | 啓動時打開產品介紹頁 |
| `--help`、`-h` | 顯示幫助並退出 |

Windows 安裝版可運行 `PixivDownload.exe`。發行包同時提供 Compose 和 Swing；`app.gui-provider` 爲空時選擇默認的 Compose。切換 provider 後完整重啓。首次無頭啓動前先完成初始化，否則程序以退出碼 `78` 結束。

### CLI 管理命令

| 命令 | 用途 |
|------|------|
| `--setup` | 首次初始化（賬號 + 模式 + 代理） |
| `--change-password` | 修改管理員密碼 |
| `--reset-password` | 忘記密碼時強制重置 |

這些命令會修改管理狀態，執行前停止同一數據目錄的其它實例。修改或重置密碼會使舊會話失效；重啓後用新密碼驗證登錄。具體交互、密碼要求與失敗處理見[首次配置](/zh-hant/first-setup)。

---

## 文件名模板變量

在「下載設置 → 文件名模板」中可使用以下變量：

| 變量 | 說明 |
|------|------|
| `{artwork_id}` | 作品 ID |
| `{artwork_title}` | 作品標題（自動去除非法字符） |
| `{author_id}` | 作者 ID |
| `{author_name}` | 作者名（自動去除非法字符） |
| `{timestamp}` | Unix 時間戳（毫秒） |
| `{page}` | 當前頁索引（從 0 開始） |
| `{count}` | 總頁數 |
| `{ai}` | AI 生成時爲 `AI`，否則爲空 |
| `{ai+}` | `AI` 或 `Human` |
| `{R18}` | `R18` / `R18G` / 空 |
| `{R18+}` | `SFW` / `R18` / `R18G` |

示例：`{author_name}/{artwork_id}_p{page}` → 按作者名分文件夾保存。

---

## 自動收藏

在下載網頁中勾選「**自動收藏**」，下載完成後後端使用已保存的 Cookie 收藏作品。油猴腳本則使用瀏覽器的 Pixiv 登錄態執行收藏，不向後端透傳 `document.cookie`。

!> 需要已保存含 `PHPSESSID` 的有效 Cookie。收藏是 best-effort，收藏失敗不會讓下載任務失敗。

---

## 動圖（Ugoira）下載

動圖作品會自動檢測並走以下流程：

1. 下載 ZIP 幀包
2. 提取幀並按文件名排序
3. 調用 `ffmpeg` 按所選格式生成動圖或視頻；可選格式見[配置參考](configuration.md)
4. 同時保存第一幀爲縮略圖（`_p0_thumb.jpg`）

要求 `ffmpeg` 在系統 PATH 中可用。Windows 安裝包用戶可在 GUI → 狀態頁點「下載 FFmpeg」自動安裝。

---

## 下載監控

訪問 `http://localhost:6999/monitor.html`，實時查看：
- 當前活躍下載進度
- 歷史記錄（按作者/標籤/AI 篩選，支持模糊搜索）
- 下載趨勢統計圖

---

## GUI 工具頁

| 工具 | 說明 |
|------|------|
| **圖片分類工具** | 對已下載圖片進行分類整理 |
| **數據庫目錄檢查** | 檢查數據庫記錄的文件路徑是否仍然有效 |
| **數據回填工具** | 補全因版本更新缺失的數據字段 |

!> 數據庫工具需要獨佔維護會話，GUI 託管後端停止與恢復。操作前備份所選數據庫，不要另開同一數據目錄的實例。所選數據庫 provider 必須可用；PostgreSQL 數據還需單獨備份服務器數據庫。工具結束後檢查報告、後端狀態和畫廊訪問，不要把「工具窗口關閉」當作成功。

---

## 疑似重複檢測

訪問 `http://localhost:6999/pixiv-duplicates.html`（管理員專屬）：

使用感知哈希（dHash）識別視覺上相似的已下載圖片，即便文件名、尺寸不同也能檢出。

- 可調漢明距離閾值（越小越嚴格，dHash 默認 10）
- 跨作品模式（找分佈在不同作品裏的重複圖）/ 全庫模式
- 點擊縮略圖跳轉到詳情頁手動處理

---

## 訪客邀請系統

?> 訪客邀請在 solo / multi 兩種模式下都可用。

管理員可創建邀請碼，讓外部用戶只讀訪問畫廊：

1. 畫廊頁面 → 點「**邀請訪客**」
2. 設置過期時間、內容分級（SFW / R18 / R18G）、標籤/作者白名單
3. 複製邀請鏈接 `http://host:port/invite?code=xxx` 分享

邀請管理頁：`http://localhost:6999/pixiv-invite-manage.html`
