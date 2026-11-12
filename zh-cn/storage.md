# 存储与备份

PixivDownloader 将下载作品、宿主运行期文件与外置插件包分目录管理。所有相对路径均基于程序的**当前工作目录**（而非 JAR 所在目录）；发行包启动脚本与 Windows 快捷方式会将工作目录设为程序根目录。

## 顶层目录结构

| 类别 | 默认路径 | 用途 |
| --- | --- | --- |
| 配置 | `config/` | 宿主 `config.yaml`、插件业务配置与加密凭据 |
| 状态 | `state/` | 初始化状态、登录会话、队列断点、GUI 引导标记与插件状态 |
| 数据 | `data/` | SQLite 主库、插件私库、收藏夹图标、缩略图/TTS 缓存与迁移/恢复记录 |
| 插件 | `plugins/` | 外置插件安装包、provenance 与运行期私有冻结工作区 |
| 日志 | `log/` | GUI 与后端运行日志 |
| 作品 | `{rootFolder}/` | `download.root-folder`（默认 `pixiv-download`）指定的作品文件 |

作品元数据与逐页媒体记录集中保存在所选数据库中，新下载不再在作品目录生成 `.meta.json` 或 `.media.properties`（Ugoira 保留原始 ZIP 时仍附带 `.frames.properties` 帧时序）。请勿将配置、数据库、插件包、状态或缓存放入作品下载根目录。

## 运行期文件明细

### 配置、状态与数据

| 路径 | 内容与注意事项 |
| --- | --- |
| `config/config.yaml` | 宿主配置与 `plugins.{id}.enabled` 启停状态 |
| `config/plugins/{pluginId}.properties` | 对应插件的非敏感业务配置 |
| `config/credentials/{pluginId}.properties` | 宿主加密维护、按 owner 注入的敏感凭据信封 |
| `config/image_classifier.properties` | 图片分类工具目录配置 |
| `config/web-publications.enrolled` | 页面编辑器正式发布登记文件（不可当作缓存清理） |
| `state/setup_config.json` | 首次初始化账号、运行模式与登录状态（删除会导致需要重新初始化） |
| `state/download-workbench/` | 下载工作台批量队列断点与布局反馈状态 |
| `state/gui/`、`state/download_root_marker.txt` | GUI 引导标记、上次解析的下载根绝对路径 |
| `state/{pluginId}/` | 插件通过 `RuntimePathProvider` 取得的 owner 状态目录 |
| `data/pixiv_download.db` | 默认 SQLite 主库（运行期可能伴随 `-wal` / `-shm`，备份前须正常停止程序） |
| `data/collection_icons/`、`data/novel/narration-voice/` | 收藏夹自定义图标、小说多角色朗读参考音 |
| `data/gallery_thumbs/`、`data/tts/`、`data/backfill/` | 可重建的画廊缩略图缓存、Edge TTS 版本缓存、回填不可达记录 |
| `data/install_identity.txt` | 首次运行生成的持久安装标识 UUID |
| `data/delete-staging/`、`data/database-migrations/`、`data/environment-transfers/`、`data/web-publications/` | 删除/发布恢复暂存、数据库切换与环境迁移备份报告、已发布页面状态（勿当作缓存删除） |
| `data/{pluginId}/` | 插件持久数据与 SQLite 私库 `plugin.db` |

### 外置插件目录（`plugins/`）

| 路径 | 用途 |
| --- | --- |
| `plugins/*.jar`、`plugins/*.zip` | 已安装的原始插件包（离线复验与身份信任源） |
| `plugins/provenance/*.pixiv-plugin-provenance` | 插件来源、摘要、签名与复验记录 |
| `plugins/runtime/` | 各运行代际（generation）独占的私有冻结工作区（非共享缓存或安装源） |
| `plugins/.preparing/`、`.staging/`、`.transaction-cleanup/`、`.pixivdownload-runtime.lock` | 安装事务、崩溃恢复与运行期目录锁 |

可通过 JVM 属性 `pixivdownload.plugins-dir` 指定插件根目录。切勿在程序运行时手动覆盖、移动或删除 `plugins/` 下的文件；安装、更新与卸载统一通过[插件管理](/zh-cn/plugin-management)事务执行。

## 下载作品目录布局

| 默认路径 | 内容 |
| --- | --- |
| `{root}/{artworkId}/` | 单作品、URL 批量、搜索等插画 / 漫画 / 动图（含合成的 `_p0.webp` 与 `_p0_thumb.jpg`） |
| `{root}/{artist}/{artworkId}/` | 画师批量下载（`download.user-flat-folder=true` 时省略 `{artist}/` 层） |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | 漫画系列封面 |
| `{root}/novel-{novelId}/` | 单篇小说的 TXT / HTML / EPUB 与内嵌图片 |
| `{root}/novel-series-{seriesId}/` | 小说系列封面与合订本 |
| `{root}/douyin/{owner}/...` | Douyin 插件默认下载目录（或该插件配置的自定义目录） |
| `{root}/_archives/{token}.zip` | 批量导出与多人模式配额的短期压缩包 |

第三方下载类型插件同样应将作品保存在 `{root}/{pluginId}/` 或用户在插件设置中指定的作品目录。工具页「旧数据与元数据导入」可从已登记作品目录导入历史 `<作品 ID>.meta.json` 快照。没有快照但路径中包含作品 ID 时，可使用[本地作品导入](/zh-cn/local-import)重新登记文件引用；路径不能恢复的元数据仍需补全。

## 数据库路径前缀与目录搬迁

数据库将文件路径保存为 `{N}/relative/path`：

- **`{0}` 符号根**：当 `download.root-folder` 为相对路径（如默认的 `pixiv-download`）时，下载根内的文件记录为 `{0}/...`，每次启动动态解析为「当前工作目录 + 相对下载根」。**整体搬迁整个程序目录（含 `pixiv-download/`）时无需手动修改数据库路径。**
- **普通 `{N}` 前缀**：当下载根或收藏夹使用绝对路径时，记录引用 `path_prefixes` 表中的绝对路径前缀。

只搬移作品下载根目录时：

1. 正常停止程序。
2. 在文件系统中将作品目录移动到新位置。
3. 启动程序，在桌面 GUI「状态」页打开「迁移下载目录」，选择实际新位置并确认同步更新 `config.yaml` 与数据库路径前缀（迁移工具只更新路径引用，不会替你移动磁盘文件）。
4. 重启后抽查历史作品、画廊预览与一次新下载。

SQLite 与 PostgreSQL 之间的数据库引擎切换属于独立流程，见[配置参考中的数据库切换](/zh-cn/configuration#主数据库与引擎切换)。

## 备份与恢复

停止程序后，完整备份应包含：

1. `config/`、`state/`、`data/`、`plugins/`；
2. `download.root-folder`、收藏夹自定义下载目录及第三方插件自定义作品目录；
3. 若使用 PostgreSQL，还须备份服务器上的主库与全部插件私有 schema；
4. 若使用[导入外部下载](/zh-cn/pixiv-batch-downloader-import)或[本地作品导入](/zh-cn/local-import)，还须备份以只读方式引用的外部源目录；
5. 若需跨机器/跨部署恢复 `config/credentials/` 中的加密凭据，须同时保留对应的凭据主密钥（否则需在目标环境重新录入凭据）。

恢复后请依次验证管理员登录、画廊与小说正文读取、计划任务状态及一次新下载。
