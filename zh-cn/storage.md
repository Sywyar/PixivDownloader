# 存储原理

PixivDownloader 把下载作品、宿主运行期文件和外置插件安装包分开管理。路径都相对于程序的**当前工作目录**，不是相对于 JAR 所在目录；发行包的启动脚本和 Windows 快捷方式会把工作目录设为发行目录。

## 顶层目录

| 类别 | 默认路径 | 内容 |
| --- | --- | --- |
| 配置 | `config/` | 宿主配置、插件业务配置和加密凭据 |
| 状态 | `state/` | 安装状态、队列断点、GUI 标记和插件可恢复状态 |
| 数据 | `data/` | SQLite、用户资源、缓存和插件持久数据 |
| 插件 | `plugins/` | 外置插件原始 artifact、provenance 和运行期冻结副本 |
| 日志 | `log/` | GUI 与后端日志 |
| 下载作品 | `{rootFolder}/` | `download.root-folder` 指定的作品产物 |

`download.root-folder` 默认是相对路径 `pixiv-download`。它只存作品本身、作品元数据 sidecar 和临时导出归档；配置、数据库、插件包、状态和缓存不应写入这里。

## 工作目录布局

### 配置

| 路径 | 用途 |
| --- | --- |
| `config/config.yaml` | 宿主配置和 `plugins.{id}.enabled` 状态 |
| `config/plugins/{pluginId}.properties` | 对应插件的非敏感业务配置 |
| `config/credentials/{pluginId}.properties` | 宿主加密维护、只注入对应插件的凭据信封 |
| `config/image_classifier.properties` | 图片分类工具的目标目录设置 |

插件配置和凭据的所有权规则见[配置参考](/zh-cn/configuration)。不要手工交换、合并或重命名不同 owner 的文件。

### 状态

| 路径 | 用途 |
| --- | --- |
| `state/setup_config.json` | 首次安装、运行模式和登录状态 |
| `state/download-workbench/batch_state.json` | 下载工作台的批量队列断点 |
| `state/download-workbench/layout-feedback-state.json` | 下载工作台布局反馈去重状态 |
| `state/gui/` | GUI 引导与代理步骤标记 |
| `state/download_root_marker.txt` | 上次解析出的下载根绝对路径 |
| `state/{pluginId}/` | 插件通过 `RuntimePathProvider` 取得的 owner 状态根；按需创建 |

删除状态不一定只会“重新生成”：可能导致重新安装、重新登录、队列断点或插件状态丢失。清理前先确认具体文件的 owner。

### 数据

| 路径 | 用途 |
| --- | --- |
| `data/pixiv_download.db` | SQLite 主库；运行时还可能有 `-wal` / `-shm` 文件 |
| `data/collection_icons/{id}.{ext}` | 收藏夹自定义图标 |
| `data/gallery_thumbs/{artworkId}/p{n}.{ext}` | 可重建的画廊缩略图缓存 |
| `data/tts/chromium-version.txt` | TTS 插件的 Edge TTS Chromium 版本缓存 |
| `data/novel/narration-voice/{castId}/{characterId}.{ext}` | 小说插件的角色参考音 |
| `data/backfill/unreachable.json` | 回填工具的不可达作品记录 |
| `data/install_identity.txt` | 首次运行生成并永久复用的安装 UUID |
| `data/delete-staging/{operationId}/` | 删除作品时用于失败回滚的原子暂存区 |
| `data/{pluginId}/` | 插件通过 `RuntimePathProvider` 取得的 owner 数据根；按需创建 |

主库保存作品事实、路径引用、历史和已安装功能写入的领域数据。插件私有表仍由对应插件负责 schema 与生命周期。不要只复制 `.db` 而遗漏活跃的 WAL 文件；备份前应正常关闭程序。

### 外置插件

| 路径 | 用途 |
| --- | --- |
| `plugins/*.jar`、`plugins/*.zip` | 已安装的原始插件 artifact；管理身份和离线复验的信任源 |
| `plugins/provenance/<artifact>.pixiv-plugin-provenance` | 来源、摘要、签名和最后验证结果 |
| `plugins/runtime/` | 每个存活 generation 的随机私有冻结工作区，不是共享缓存或安装源 |
| `plugins/.preparing/`、`plugins/.staging/`、`plugins/.transaction-cleanup/` | 安装事务与崩溃恢复的受管目录 |
| `plugins/.pixivdownload-runtime.lock` | 运行期目录 lease |

可以用系统属性 `pixivdownload.plugins-dir` 覆盖插件根。运行时不会因为目录缺失而自动创建它；缺失会形成诊断，核心壳仍可进入恢复流程。

不要在程序运行时覆盖、移动或删除 `plugins/` 下的文件。安装、升级、移除和回滚必须走插件管理生命周期，使 artifact 与 provenance 一起事务化处理。`plugins/runtime/` 可由已验证的安装 artifact 重建，但不是可以被其它进程复用的下载缓存。

## 下载作品布局

常见路径如下；插件可以在自身作品目录内定义更细的结构。

| 路径 | 内容 |
| --- | --- |
| `{root}/{artworkId}/` | 单作品、URL 批量、搜索等 Pixiv 插画下载 |
| `{root}/{artist}/{artworkId}/` | 画师批量下载；`download.user-flat-folder=true` 时省略画师层 |
| `{root}/{artworkId}/{filename}_p0.webp` + `..._p0_thumb.jpg` | 动图合成后的 WebP 与首帧缩略图 |
| `{root}/{artworkId}/{artworkId}.meta.json` | 网页、油猴脚本和计划任务下载时从已有响应生成的 Pixiv 结构性元数据，不会额外请求 Pixiv；随作品移动 / 删除，且不计入配额打包或小说导出（小说使用 `novel-{novelId}/{novelId}.meta.json`） |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | Pixiv 漫画系列封面 |
| `{root}/novel-{novelId}/` | 单本小说的 TXT/HTML/EPUB 与相关作品文件 |
| `{root}/novel-series-{seriesId}/` | 小说系列封面和可选合订文件 |
| `{root}/douyin/{owner}/...` | Douyin 插件的默认下载位置 |
| `{root}/_archives/{token}.zip` | 多人模式配额和画廊导出的短期归档 |

Douyin 的默认根由 `DownloadSettings.getRootFolder()` 加上 `douyin` 得到，然后按请求 owner 隔离；插件配置 `douyin.download.directory` 非空时改用该目录。它不会使用旧的 `data/douyin/downloads`。收藏夹也可以配置独立的作品下载根，该路径可能位于默认下载根之外。

第三方下载类型同样应把作品写入 `download.root-folder` 下以插件 id 命名的目录，或写入用户在该插件配置中明确选择的作品目录。`state/{pluginId}` 和 `data/{pluginId}` 只放辅助状态与数据，不能替代作品目录。

## 数据库路径编码

数据库不会为每条记录重复保存长绝对路径，而是使用前缀引用：

```text
{N}/relative/path
```

`N>0` 指向 `path_prefixes` 中的一条绝对路径。修改这条前缀即可让所有引用同时指向新的根。

### `{0}` 符号根

当 `download.root-folder` 是相对路径时，下载根内的记录可写为 `{0}/...`。`{0}` 每次启动都解析为“当前工作目录 + 当前相对下载根”，因此将整个发行目录连同 `pixiv-download/` 一起搬迁后，历史记录仍能定位作品。

当下载根是绝对路径时，记录使用普通 `{N}` 前缀。此时移动作品目录后，应使用 GUI 状态页的“迁移下载目录”更新记录。

`state/download_root_marker.txt` 保存上次解析结果，用于发现“只改配置、没有搬文件”的情况。迁移工具只更新配置和数据库路径引用，**不会移动磁盘文件**。

## 搬迁

### 整体搬迁

保持 `download.root-folder` 为相对路径，关闭程序后移动整个发行目录。启动脚本应从新目录运行，使工作目录、运行期数据和 `{0}` 一起迁移。

### 只移动下载根

1. 正常关闭程序。
2. 在文件系统中移动作品目录。
3. 从 GUI 状态页打开“迁移下载目录”，选择实际新位置并决定是否同步修改 `config.yaml`。
4. 按提示重启并抽查历史作品、画廊和新下载。

不要先手工改 `download.root-folder` 再期待程序移动文件；它不会这样做。

## 备份与恢复

完整备份建议包含：

- `config/`：包括插件业务配置和加密凭据；
- `state/`：保留安装、登录、队列和插件状态；
- `data/`：程序关闭后连同数据库一起复制；
- `plugins/`：保留第三方/按需插件、签名和 provenance；
- `download.root-folder` 以及收藏夹或插件配置的其它作品目录。

`log/` 通常只在排障时需要。`data/gallery_thumbs/` 和 `plugins/runtime/` 可以从其它持久数据重建，但为了简化恢复可以随完整目录一起备份。

恢复时保持相同的相对布局或通过迁移工具更新绝对路径。加密凭据还依赖生成这些信封的凭据主密钥；跨不同构建/部署恢复前确认密钥兼容，否则应在目标环境重新录入凭据。
