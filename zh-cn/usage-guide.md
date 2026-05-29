# 使用指南（进阶参考）

本页汇总各专题页面未单独成文的进阶操作和参数说明。

常见功能的详细教程请直接查看对应页面：

| 功能 | 文档 |
|------|------|
| 快捷获取 | [快捷获取](/zh-cn/quick-access) |
| URL 批量下载 | [URL 批量下载](/zh-cn/batch-download) |
| 画师批量下载 | [画师批量下载](/zh-cn/user-download) |
| 搜索下载 | [搜索下载](/zh-cn/search) |
| 小说下载 | [小说下载](/zh-cn/novel) |
| 作品画廊 | [作品画廊](/zh-cn/gallery) |
| 计划任务 | [计划任务](/zh-cn/scheduled-tasks) |
| 油猴脚本 | [油猴脚本](/zh-cn/userscripts) |

---

## 启动参数

```bash
# JAR 启动
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar

# Windows EXE 启动
PixivDownload.exe

# 常用参数
--no-gui    # 禁用桌面 GUI（适合服务器/Docker）
--intro     # 启动时打开产品介绍页
--help, -h  # 打印帮助并退出
```

?> 默认启动桌面 GUI（Swing），服务器场景才建议 `--no-gui`。

### CLI 管理命令

| 命令 | 用途 |
|------|------|
| `--setup` | 首次初始化（账号 + 模式 + 代理） |
| `--change-password` | 修改管理员密码 |
| `--reset-password` | 忘记密码时强制重置 |

详见[首次配置](/zh-cn/first-setup)。

---

## 文件名模板变量

在「下载设置 → 文件名模板」中可使用以下变量：

| 变量 | 说明 |
|------|------|
| `{artwork_id}` | 作品 ID |
| `{artwork_title}` | 作品标题（自动去除非法字符） |
| `{author_id}` | 作者 ID |
| `{author_name}` | 作者名（自动去除非法字符） |
| `{timestamp}` | Unix 时间戳（毫秒） |
| `{page}` | 当前页索引（从 0 开始） |
| `{count}` | 总页数 |
| `{ai}` | AI 生成时为 `AI`，否则为空 |
| `{ai+}` | `AI` 或 `Human` |
| `{R18}` | `R18` / `R18G` / 空 |
| `{R18+}` | `SFW` / `R18` / `R18G` |

示例：`{author_name}/{artwork_id}_p{page}` → 按作者名分文件夹保存。

---

## 自动收藏

下载设置里勾选「**自动收藏**」，下载完成后后端会通过 Cookie 调用 Pixiv API 自动收藏该作品。

!> 需要已保存含 `PHPSESSID` 的有效 Cookie。收藏是 best-effort，收藏失败不会让下载任务失败。

---

## 动图（Ugoira）下载

动图作品会自动检测并走以下流程：

1. 下载 ZIP 帧包
2. 提取帧并按文件名排序
3. 调用 `ffmpeg` 合成为 WebP 动图
4. 同时保存第一帧为缩略图（`_p0_thumb.jpg`）

要求 `ffmpeg` 在系统 PATH 中可用。Windows 安装包用户可在 GUI → 状态页点「下载 FFmpeg」自动安装。

---

## 下载监控

访问 `http://localhost:6999/monitor.html`，实时查看：
- 当前活跃下载进度
- 历史记录（按作者/标签/AI 筛选，支持模糊搜索）
- 下载趋势统计图

---

## GUI 工具页

| 工具 | 说明 |
|------|------|
| **图片分类工具** | 对已下载图片进行分类整理 |
| **数据库目录检查** | 检查数据库记录的文件路径是否仍然有效 |
| **数据回填工具** | 补全因版本更新缺失的数据字段 |

!> 数据库检查和回填工具需要独占 SQLite，GUI 会自动托管后端的暂停与恢复，无需手动停服务。

---

## 疑似重复检测

访问 `http://localhost:6999/pixiv-duplicates.html`（管理员专属）：

使用感知哈希（dHash）识别视觉上相似的已下载图片，即便文件名、尺寸不同也能检出。

- 可调汉明距离阈值（越小越严格，dHash 默认 10）
- 跨作品模式（找分布在不同作品里的重复图）/ 全库模式
- 点击缩略图跳转到详情页手动处理

---

## 访客邀请系统

?> 访客邀请在 solo / multi 两种模式下都可用。

管理员可创建邀请码，让外部用户只读访问画廊：

1. 画廊页面 → 点「**邀请访客**」
2. 设置过期时间、内容分级（SFW / R18 / R18G）、标签/作者白名单
3. 复制邀请链接 `http://host:port/invite?code=xxx` 分享

邀请管理页：`http://localhost:6999/pixiv-invite-manage.html`
