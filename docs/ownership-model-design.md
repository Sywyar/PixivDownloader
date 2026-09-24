# 文件归属模型设计说明（草案）

> **目的**：为「同一作者的作品共用一个目录」（`download.artwork-folder-template` 非空）建立一套可评审的文件归属模型，使下载提交、文件定位、删除、归档、恢复全部读同一份归属依据，从而在基础适配完成前**不开放**该配置项。
>
> **范围**：只覆盖磁盘上「正式作品文件」的归属事实与围绕它的写入/提交/删除/归档/恢复协议。**不含**代码改动、不含 UI 设计、不含 Pixiv 侧抓取逻辑变更。
>
> **状态**：草案，待上游原作者评审。第 11 章显式列出尚未拍板的点，本文不把未定项写成已定项。
>
> **依据**：`prs/implementation-plan.md` 的只读核实结论（F01/F02/F03/F08/F09/F12/F13 逐条含 `文件:行号` 证据）。本文所有论断均可追溯到该文档或下述仓库实际代码；行号以本次核实的 HEAD `4a09c3b7`（分支 `feat/artwork-folder-template`，工作树干净）为准。已确认该 HEAD 相对实施清单的核实基线 `d23e50ab` 仅差 i18n 文案两行（`git diff --stat d23e50ab 4a09c3b7` = `i18n/catalog-lock.json` + `messages_en.properties`），故实施清单中的行号全部继续有效。
>
> **本文档只做设计说明，未修改仓库内任何文件。**

---

## 1. 问题陈述

### 1.1 「一个目录 = 一个作品」这条假设在哪些地方被硬编码

`download.artwork-folder-template` 让作品直接落在 `{下载根}/{渲染结果}/`，**不再追加 `{artworkId}` 层级**（`ArtworkDownloadExecutor.java:1053-1073`、`DownloadConfig.java:32-34`）。但程序中大量逻辑把「目录」当作「作品」的同义词：

| 编号 | 失效场景（已核实） | 证据 |
| --- | --- | --- |
| F01-a | 配额侧删除 = `Files.walk(整个目录)` 全删，`File::delete` 返回值被丢弃，删失败仍删 DB 行 | `UserQuotaService.java:610-630`（`:613-615` 递归删丢弃返回值、`:618-620` catch 吞异常、`:621-629` 无条件 `deleteArtwork(artworkId)`） |
| F01-b | 从目录名反推作品 ID（`Long.parseLong(目录名)`），纯数字目录会被当成作品 ID 删掉 DB 行 | `UserQuotaService.java:645-655`、入口 `:597-599` |
| F01-c | 归档打包 `Files.walk(folder)` 整目录入包，共享目录下会连**其它作品、其它访客**的文件与处理中的临时产物一起打包 | `UserQuotaService.java:263-328`（`:276` 输入是「下载目录集合」、`:288-305` 整目录遍历、`:294` 只排除 `*.meta.json`） |
| F01-d | `pack-and-delete`（默认模式，`MultiModeConfig.java:23`）打包后对整个目录调删除 → 一次访客打包删掉整共享目录 | `UserQuotaService.java:315-320`、`:321` |
| F01-e | 定时清理按 `folder`（优先 `move_folder`）递归删整目录 | `UserQuotaService.java:583-594`、`:602-607`、`:632-643` |
| F01-f | 管理员按作品打包在 `Set<Path>` 上按**目录**去重，作品维度直接退化为目录维度 | `ArchiveController.java:122-167`（`:134` `uniqueFolders`、`:156` `add(folder)`、`:163-164` `workCount = folders.size()`） |
| F02 | 唯一性判据只做「模板含 `{artwork_id}`」+「渲染结果 `contains(作品ID)`」两项字符串检查，四个反例全部绕过（ID 拼接歧义 / 跨模板 / `_thumb` 撞名 / 标题含 ID 数字绕过自检）；`ensureUnique` 去重范围仅限单作品（`used`/`baseCounts` 是每次调用新建的局部集合） | `SharedDirectoryNameGuard.java:37-42`、`:52-60`、`:62-71`；`PixivWorkFileNameFormatter.java:220-250`（`:226-227` 局部集合）、`:257-259`（仅此处折叠大小写） |
| F03 | 活动行重新下载时 `INSERT OR IGNORE` 全程 no-op，`folder/count/extensions/time/moved/move_folder` 一概不更新且全库无处清除 `move_folder` → 记录指向旧目录、磁盘在新目录 | `PixivMapper.java:88-106`、`:134-154`、`:111-115`；全仓库无 `moved = 0` / `move_folder = NULL` 语句 |
| F08 | 动图临时路径只含 `artworkId`，同作品两个任务拿到同一 `zipPath`/`tempDir`，入口 `cleanup` 删掉对方正在用的目录 | `UgoiraTempPaths.java:25-37`、`UgoiraService.java:80-93`（`:93` 入口 cleanup）、`:157-159` |
| F08-b | 入口无按作品/目标路径的排他控制；状态表是普通 `ConcurrentHashMap.put`（覆盖式），跨访客 `statusKey` 天然不同 | `DownloadTaskController.java:47-122`；`ArtworkDownloadExecutor.java:105`、`:192-196`、`:985-987` |
| F09-a | 小说侧删除对该目录**递归枚举全部常规文件**后删除并清空目录树；作品目录模板可渲染出 `novel-77` | `LocalWorkAssetService.java:223-247`（`:233-237` 全枚举、`:245` 清目录树）、`:250-262`；`NovelDownloadService.java:158`；`ArtworkFolderTemplate.java:34-57` 无保留名校验 |
| F09-b | 归档输出目录 `{root}/_archives` 有 5 处字面量；打包时若输入目录就是 `_archives`，正在写的 zip 会被枚举进包，且 `pack-and-delete` 会删掉目录下所有历史 zip | `UserQuotaService.java:279`、`:342`、`:397`、`:524`、`:534`；`:288-305`、`:315-320`；`:531-560` 启动时按扩展名全删 `.zip` |
| F09-c | 现有路径守卫只比较「路径是否完全相同」（`equals`），**父子重合完全不可见** | `ArtworkFileLocator.java:200-209`（`:206` `absolute.equals(downloadRoot)`）、`LocalWorkAssetService.java:305-314`；`CoreApiOwnershipGuardTest.java:833-839` 把目录布局当契约记录 |
| F12 | `hasArtworkFiles()` 命中第 0 页即返回 `true`；调用方在 false 时**物理删除作品记录** | `ArtworkFileService.java:147-163`（`:156-161` 任一页命中即 `true`）、`DownloadedArtworkService.java:61-65`、`:73-81` |
| F12-b | 恢复流程的连续性判定只检测**中间空洞**，检测不到尾部缺页；搜索目录固定 `{root}/{id}`、只认默认模板 | `ArtworkMetadataRecoveryService.java:186-189`（`pageExt.size() == maxPage + 1`）、`:90-94`、`:147-151`、`:191-209`（`:196-197` 正则） |
| F13 | 落盘用请求参数、入库用被 `ArtworkMetadataQuality` 过滤后的参数，二者可分离；命名参数**全部**取自 DB 记录并参与重放，其中 `title/isAi/xRestrict/file_author_name_id/file_name/file_name_max_length` 都会被重下改写 | `ArtworkDownloadExecutor.java:989-1037`、`:300-303`；`ArtworkMetadataQuality.java:9-16`；`ArtworkDownloadHistoryAdapter.java:38-44`、`:88`；`ArtworkFileLocator.java:93-121`；`PixivMapper.java:11`、`:135-141` |
| F13-b | 普通图片逐页 `.part` + `REPLACE_EXISTING` 直接覆盖目标文件，失败页被跳过且**不回滚**；整体不成功不写历史，但已覆盖的页留在磁盘 | `PixivImageDownloadService.java:102-120`；`ArtworkDownloadExecutor.java:235-273`（`:266-271` 逐页 catch）、`:282-297`（部分失败不写历史） |
| F13-c | 动图在 `runFfmpeg` 内就把 WebP 发布到目标路径，之后才复制缩略图；缩略图失败时 WebP 留在磁盘且 `cleanup` 不覆盖它 | `UgoiraService.java:137-144`（`:140-142` 缩略图复制）、`:146-159`、`:779-791` |

### 1.2 两条必须区分的「不等于」

- **作品独占目录 ≠ 任务独占工作区**：即使目录不共享，F08 依然成立（`UgoiraTempPaths.java:25-37` 只含作品 ID）。
- **作品独占目录 ≠ 文件完整**：即使目录不共享，F12 依然成立（`ArtworkFileService.java:156-161` 命中一页即 `true`）。

因此本设计**不把「目录不共享」当作任何问题的消解前提**。

### 1.3 删除链路为什么危险（本次特别强调）

`hasArtworkFiles()` 返回 false 时，调用方**物理删除整条作品记录**（`DownloadedArtworkService.java:64-65` → `:73-81` → `PixivDatabase.deleteArtwork`）。若只把该方法改成「所有页都存在才返回 true」，则**缺一页的作品会走进删除记录的流程**，补齐下载所需的目录与命名信息随之丢失（`ArtworkMetadataRecoveryService.java:90-94` 的恢复又只搜 `{root}/{id}` 与默认模板，补不回来）。第 6 章的三态判定就是为此而设，且必须与判重、恢复流程**一起**调整（作者特别警告项）。

---

## 2. 设计目标与不变量

以下断言使用「任何时刻都必须成立」的形式。它们是验收的判据，也是第 10 章各阶段「自身安全可用」论证的引用对象。

| 编号 | 不变量 | 当前状态 |
| --- | --- | --- |
| I1 | 清单中 `state = committed` 的每一条文件记录，其 `(root_ref, rel_path)` 解析后的绝对路径必须**真实存在**；若不存在，该行必须已被置为 `missing` 并记录 `verified_at` | 不成立（无清单） |
| I2 | 磁盘上任何属于某作品命名空间的**正式文件**（非临时），必须能在清单中找到一条 `committed` 行；不允许存在「磁盘有、清单无」的正式文件 | 不成立 |
| I3 | 任何目标的**原子预留**在写入前完成：同一 `(root_ref, path_key)` 在任何时刻最多被一个**未终结**的代次（任务）持有 | 不成立（`UserQuotaService.java:613-615` 直接覆盖写） |
| I4 | 删除只作用于「该作品清单声明的文件集合」∪「该任务的临时工作区」；**不得**作用于任何目录的递归内容 | 不成立（F01-a、F09-a） |
| I5 | 归属未确认（清单缺失或状态不明）时，**不得**依据文件名主干/前缀执行任何删除 | 不成立（`ArtworkFileLocator.java:218-248` 正是主干匹配删除） |
| I6 | 删除失败（`StagedFileDeletion.deleteAtomically` 返回 false）时，DB 侧清理必须中止；文件与记录在失败后仍能一一对应 | 配额链路不成立（`UserQuotaService.java:613-629`） |
| I7 | 任何时刻不得存在「以删除为目的」的操作波及保留目录：`{root}/_archives`、`{root}/_staging`、`novel-{id}` 独占目录，以及它们的任意祖孙关系 | 不成立（只有 `equals` 判定，F09-c） |
| I8 | 命名事实（用于定位）与展示元数据（`title`/作者/分级）分离：后者被回填/改写后，前者的解析结果必须逐字节不变 | 不成立（F13） |
| I9 | 完整性判定必须三态化：`COMPLETE` / `PARTIAL` / `UNVERIFIABLE` 互不混同；只有 `ABSENT` 才允许删除作品记录 | 不成立（F12） |
| I10 | 阶段 0 门禁（共享目录不可启用）在归属模型落地前始终生效；每个合并进主线的版本自身安全可用 | 门禁存在（`SharedDirectoryNameGuard.java:62-71` + `ArtworkDownloadExecutor.java:1015-1027`），但该门禁本身基于 F02 的伪充分条件 |
| I11 | 新增校验导致的拒绝必须**带明确原因**返回，且不得消耗配额、不得被当作可重试的网络错误 | 不成立（配额先于预检预留 `DownloadTaskController.java:85-103`；计划任务把一切异常归为 `RETRYABLE_NETWORK` `PixivScheduledIllustWorkExecutor.java:151-154`） |

---

## 3. 存储结构

### 3.1 总体形态：清单（事实源）+ 受限默认命名（减少重名）

按已对齐的方案（`prs/105-reply3.md:32`、`:38`）：**以精确文件清单作为定位与判断归属的事实源**，**同时采用受限的默认命名**。两者各司其职：

- **清单**记录**实际已经提交的文件**——这是被判定的唯一权威来源。
- **受限标识**（作品 ID / 页码 / 产物角色）只用于**减少新文件重名**，不作为归属判据（因为单靠命名不足以保证唯一，见第 4 章与 F02 的四个反例）。

### 3.2 新增三张表

Schema 声明落在 `pixivdownload-app` 的 `SchemaContribution` 体系内（现有范式见 `ArtworkSchemaContribution.java:24-74`；`SchemaSpecs.java:17-36` 提供 `column` / `autoIncrementPrimaryKey` / `explicitIndex` / `uniqueConstraint` 四个构造助手）。复合主键在该 DSL 中可表达——`artwork_tags` 就用 `primaryKeyPosition` 1/2 建了复合主键（`ArtworkSchemaContribution.java:57-66`）。

#### 表 1：`work_files`（清单，归属事实源）

| 字段 | 类型 | 约束 | 含义 | 依据/理由 |
| --- | --- | --- | --- | --- |
| `id` | INTEGER | PK AUTOINCREMENT | 代理主键 | 复用 `SchemaSpecs.autoIncrementPrimaryKey`（`SchemaSpecs.java:26-28`） |
| `work_type` | TEXT | NOT NULL | 作品类型：`ARTWORK` / `NOVEL` | 复用现有枚举 `WorkType`（`WorkType.java:6-15`）；`DownloadTaskController.java:13`、`:68` 已按 `WorkType.ARTWORK` 查询 |
| `work_id` | INTEGER | NOT NULL | 作品 ID | 与 `artworks.artwork_id`（`ArtworkSchemaContribution.java:29`）、`novels.novel_id`（`NovelSchemaContribution.java:33`）同域 |
| `generation_id` | TEXT | NOT NULL | **下载代次 / 任务标识** | 见 3.5 |
| `root_ref` | TEXT | NOT NULL | **下载根目录引用**，形如 `{0}` / `{N}`（`PathPrefixCodec.java:43-46`、`:163-186`） | 直接复用 `path_prefixes` 机制（`CoreSchemaContribution.java:25-39`）；`artworks.folder` 已用同一编码（`PixivDatabase.java:90` → `:160`） |
| `rel_path` | TEXT | NOT NULL | **相对路径**（`/` 分隔，大小写保真） | 写入用 |
| `path_key` | TEXT | NOT NULL | 规范化键：`/` 分隔 + `Locale.ROOT` 小写折叠 | 用于大小写不敏感卷的唯一性判定（F02 反例 3 的同源问题：`PixivWorkFileNameFormatter.java:257-259` 只在一处折叠） |
| `page` | INTEGER | NOT NULL DEFAULT -1 | **页码**；非分页产物（sidecar 等）为 -1 | 与 `artworks.count`（`ArtworkSchemaContribution.java:32`）对齐 |
| `role` | TEXT | NOT NULL | **产物角色**：`original` / `ugoira_webp` / `thumbnail` / `sidecar` / `novel_text` / `novel_cover` | 见 3.4 |
| `state` | TEXT | NOT NULL | **状态**：`committed` / `missing` / `publishing_delete` / `deleted` | 见 3.6 |
| `naming_snapshot_id` | INTEGER | NULL | → `file_naming_snapshots.id` | 命名参数快照（3.3） |
| `base_name` | TEXT | NULL | **落盘时实际使用的主干名**（不含扩展名） | 让定位不再依赖模板重放（I8） |
| `extension` | TEXT | NULL | 实际扩展名 | `ArtworkFileLocator.java:64` 现按 `artworks.extensions` 拆分 |
| `size_bytes` | INTEGER | NULL | 提交时的文件字节数 | 崩溃恢复时的比对凭据（第 5 章） |
| `committed_at` | INTEGER | NOT NULL | 提交时间 | 审计 |
| `verified_at` | INTEGER | NULL | 最近一次完整性核验时间 | I1 |

索引与约束：

| 名称 | 形态 | 作用 |
| --- | --- | --- |
| `work_files_generation_role_page` | `UNIQUE(work_type, work_id, generation_id, role, page)` | 一个代次内每个角色每页唯一 |
| `idx_work_files_work` | `CREATE INDEX (work_type, work_id, state)` | 按作品取清单（定位/删除/归档/完整性判定） |
| `idx_work_files_path` | `CREATE INDEX (root_ref, path_key)` | 按路径反查归属 |
| `idx_work_files_generation` | `CREATE INDEX (generation_id)` | 崩溃恢复按代次扫描 |

**为什么路径唯一性不放在这张表上**：见 3.7（`IndexSpec` 不支持部分索引）。

#### 表 2：`work_file_reservations`（写入前的原子预留）

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `root_ref` | TEXT | PK 第 1 列 | 下载根引用 |
| `path_key` | TEXT | PK 第 2 列 | 规范化相对路径 |
| `display_path` | TEXT | NOT NULL | 大小写保真的相对路径（实际写入用） |
| `work_type` / `work_id` | TEXT / INTEGER | NOT NULL | 持有者 |
| `generation_id` | TEXT | NOT NULL | 代次 |
| `role` / `page` | TEXT / INTEGER | NOT NULL | 产物角色 / 页码 |
| `task_owner` | TEXT | NULL | 请求者标识（访客 uuid / `admin`），仅诊断用；与 `ArtworkDownloadExecutor.java:986` 的 `statusKey` 语义不同，**不作为互斥键** |
| `reserved_at` | INTEGER | NOT NULL | 预留时间 |

**复合主键 `(root_ref, path_key)` 本身就是原子预留的载体**：并发插入同一路径时第二个 `INSERT` 必然违反主键，这就是「写入前检查并原子预留」的落地点，而不是「只查一次有没有重名、然后直接写入」。

#### 表 3：`file_naming_snapshots`（命名参数快照）

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT | 主键 |
| `work_type` / `work_id` / `generation_id` | TEXT / INTEGER / TEXT | 归属（`UNIQUE` 三元组） |
| `file_template` | TEXT | 本次使用的**文件名模板**（规范化后） |
| `folder_template` | TEXT | 本次使用的**目录模板**；空串表示内置结构 |
| `title` | TEXT | 命名时**实际用于渲染**的标题（未经过展示元数据过滤） |
| `author_id` / `author_name` / `file_author_name_id` | INTEGER / TEXT / INTEGER | 命名时的作者标识 |
| `x_restrict` / `is_ai` | INTEGER | 命名时的分级与 AI 标记 |
| `record_time` / `count` / `max_length` | INTEGER | 命名时的时间戳、页数、长度上限 |
| `created_at` | INTEGER | 创建时间 |

这张表就是「**命名参数快照可以保留，用来解释文件名是怎么生成的，也方便恢复**」。它与 `file_name_templates`（`FileNameSchemaContribution.java:24-33`，唯一约束在 `template` 上）的关系是：模板池继续做 intern，快照表记录**这一次代次实际用了哪个模板 + 哪些变量值**。现有 `artworks.file_name` 只存模板 ID（`ArtworkSchemaContribution.java:39`），无法表达「同一次下载的变量值」。

### 3.3 与现有表的关系

| 现有表 | 关系 |
| --- | --- |
| `artworks`（`ArtworkSchemaContribution.java:26-56`） | 保留为**展示元数据 + 摘要**的主体。`title`/`R18`/`is_ai`/`author_id`/`description`/`series_*` 继续被回填（`PixivMapper.java:134-143`），**不再作为文件定位的输入**。`folder`/`move_folder` 的语义降级为「最近一次已知落点（提示性）」，权威落点由清单推导 |
| `file_name_templates`（`FileNameSchemaContribution.java:24-33`） | 保留；`work_files.base_name` + `file_naming_snapshots` 取代「每次从模板重放」（`ArtworkFileLocator.java:93-116`） |
| `path_prefixes`（`CoreSchemaContribution.java:25-39`） | 直接复用：`work_files.root_ref` 存 `{N}`；`PathPrefixCodec.resolve`（`:191-221`）还原绝对路径。`{0}` 符号根（`:43-46`、`:32-36` 的 javadoc）使「整个软件目录被搬迁后记录自动跟随」这一既有能力对清单同样成立 |
| `novels`（`NovelSchemaContribution.java:29-64`） | 同构保留；`work_type = NOVEL` 的清单行与其 `folder` 并存 |
| `artwork_tags` / `novel_tags` | 无关 |

**注意 `artworks.time` 的 `UNIQUE` 约束**（`ArtworkSchemaContribution.java:52`）：清单不复制 `time`；代次身份由 `generation_id` 承担（3.5），正是为了不再依赖 `time`（F03 已证明重下时 `time` 不更新）。

### 3.4 作品类型如何区分（插画 / 小说 / 动图等）

| 维度 | 承载字段 | 依据 |
| --- | --- | --- |
| 插画 vs 小说 | `work_type` ∈ {`ARTWORK`, `NOVEL`} | `WorkType.java:6-15`；跨类型隔离是 F09-a 的正面要求（插画不得落入 `novel-{id}`） |
| 静态插画 vs 动图 | **不新增 `work_type`**，由 `role = ugoira_webp` + `role = thumbnail` 表达；`extensions` 仍可含 `webp` | 动图是插画的**变体**而非并列类型：`DownloadRequest.Other.isUgoira()` 是标志位（`ArtworkDownloadExecutor.java:215`、`:282`），仓库无 `UGOIRA` 枚举值 |
| 是否为真实下载 | `generation_id` 非空 = 由下载任务提交；`generation_id` 为空/哨兵 = 恢复或迁移产生的记录 | 3.5、第 9 章 |

> **待决策 D1**：是否把 `UGOIRA` 加入 `WorkType`。加值会改动 `pixivdownload-core-api`（SDK 模块，`scripts/ci/sdk-version.mjs:10-17`）的公开枚举，触发 release identity 要求（`scripts/ci/sdk-contract.mjs:111-112`）。本设计的**推荐**是不加，理由如上表。

### 3.5 下载代次 / 任务标识怎么生成

**推荐**：每个下载任务在**开始**时生成一个 `generation_id = UUID.randomUUID().toString()`，在一次任务内贯穿：

1. 写进 `work_file_reservations.generation_id`（预留）；
2. 写进 `work_files.generation_id`（提交）；
3. 写进 `file_naming_snapshots.generation_id`（命名快照）；
4. 作为临时工作区的目录名（第 5 章）。

**为什么不复用 `artworks.time`**：`time` 由 `PixivDatabase.getUniqueTime` 分配且带 `UNIQUE`（`ArtworkSchemaContribution.java:52`、`PixivDatabase.java:62-83`），看起来很合适，但 F03 已核实**重下时 `time` 不更新**（`PixivMapper.java:135-143` 的 SET 列表不含 `time`），因此它无法标识「第二次提交」。UUID 与代次一一对应，且不参与任何用户可见排序。

**为什么不用「任务状态对象」代替**：`downloadStatusMap`（`ArtworkDownloadExecutor.java:105`）是进程内、按 `(owner, artworkId)` 键、覆盖式 `put`（`:192-196`、`:985-987`），跨访客天然分键、进程重启即失。它只能展示状态，不能承载归属（F08-b）。

**代次的生命周期**（与第 5 章的提交协议一致）：

| 阶段 | 预留表 | 清单 | 临时工作区 |
| --- | --- | --- | --- |
| 预检通过、开始下载 | 插入本代次的全部目标路径行 | 无 | `{root}/_staging/{generationId}/` 建立 |
| 全部产物就绪 | 同上 | 无 | 就绪 |
| 提交中 | 行仍在 | 在同一 DB 事务内插入 `committed` 行并删除本代次预留行 | 逐个 `ATOMIC_MOVE` 到目标 |
| 提交完成 | 本代次行已删除 | `committed` | 目录删除 |
| 提交失败（回滚） | 本代次行已删除（回滚后） | 无本代次行 | 目录删除 |
| 崩溃 | 本代次行残留（`publishing` 标记） | 可能部分存在 | 目录残留 → 启动恢复（5.4） |

### 3.6 状态取值与迁移

| 状态 | 含义 | 谁写入 |
| --- | --- | --- |
| `committed` | 该文件已随某次**已完成的提交**发布到目标路径 | 提交事务 |
| `missing` | 曾被提交，但最近一次核验发现文件不在；**记录保留**（这是 I9 的关键） | 完整性核验 |
| `publishing_delete` | 已决定删除、文件删除进行中 | 删除事务第 1 步（第 8 章） |
| `deleted` | 文件已确认删除，历史行保留用于解释与防重 | 删除事务第 3 步 |

`state` 的取值域由代码校验（现有 `TableSpec` 不支持 CHECK，`SchemaSpecs.java:17-23` 只有 name/type/notNull/defaultValue/primaryKeyPosition），因此取值域校验放在写入侧的统一入口。

### 3.7 一个明确的实现约束：`IndexSpec` 不支持部分索引

`IndexSpec` 只有 `name` / `IndexOrigin` / `unique` / `columns`（`SchemaSpecs.java:30-36` 的构造入口，record 定义在 `pixivdownload-plugin-api` 的 `schema/IndexSpec.java`），**没有 `WHERE` 子句**。因此「同一路径同时只允许一条活着的清单行、但允许历史 `deleted` 行共存」这条约束**无法**用清单表上的部分唯一索引表达。

本设计的处理：**路径唯一性只落在 `work_file_reservations` 的复合主键上**（活路径才有预留行；提交后预留行被删除，清单行留存历史）。这样：

- 不需要扩展 `IndexSpec`（不触发 `pixivdownload-plugin-api` 的 SDK 公开面变化，`scripts/ci/sdk-version.mjs:12`）；
- 唯一性约束的作用域恰好等于「正在进行的写入」，符合「任何目标的原子预留」的语义；
- 清单表只承担历史与定位，允许同一路径在不同代次间出现多行（那是合法的重下历史）。

> **待决策 D2**：若原作者更希望「清单表自身就有路径唯一性」，则需要给 `IndexSpec` 增加可选 `where` 并在 migration 层生成部分索引——这是 SDK 公开面变化，需要一次 release identity。本设计推荐前者。

---

## 4. 受限的默认命名

### 4.1 受限标识的形式

「受限标识」= 固定的作品 / 页码 / 产物角色标识。既然它**不是**归属判据，它的目标就只有一个：**让新文件在不受模板影响的情况下不易重名**。

| 产物角色 `role` | 受限默认主干 | 说明 | 现有同源实现 |
| --- | --- | --- | --- |
| `original`（第 `p` 页） | `{artwork_id}_p{page}` | 与内置默认模板**逐字节相同** | `PixivWorkFileNameFormatter.java:21` `DEFAULT_TEMPLATE`、`:296-298` `fallbackBaseName` |
| `ugoira_webp` | `{artwork_id}_p0` | 动图成品只有一页，`expectedCount = 1` | `ArtworkDownloadExecutor.java:282`（`other.isUgoira() ? 1 : imageUrls.size()`） |
| `thumbnail` | 上者 + `_thumb` | 与现有命名一致 | `UgoiraService.java:140-142`、`ArtworkFileLocator.java:89`、`ArtworkFileService.java:102` |
| `sidecar` | `{artwork_id}.meta.json` | 已是 per-work 命名，天然不撞 | `WorkSidecarFiles.java:13-14`、`:26-28` |

**关键性质**：受限标识里 `{artwork_id}` 是**整段**而非子串，页与角色是**定长固定后缀**，因此不存在 F02 的四个反例：

| F02 反例 | 受限标识为何不中招 |
| --- | --- |
| ID 拼接歧义：`{artwork_id}{artwork_title}` 让 A(123,"45") 与 B(1234,"5") 都得 `12345` | 受限标识不含标题，ID 后始终紧跟 `_p` |
| 跨模板：不同作品用不同历史模板渲染出同主干 | 受限标识与模板无关，是**唯一形态** |
| `_thumb` 撞名：A 主干 `111_222` 的缩略图与 B 的主干 `111_222_thumb` 同名 | 受限标识下 B 的原图是 `111222_p0` 形态，不可能等于 `{id}_p{p}_thumb` |
| 标题含 ID 数字绕过 `contains` 自检 | 判据不再是 `contains`（`SharedDirectoryNameGuard.java:52-60`），而是预留表的精确 `path_key` |

### 4.2 为什么仍然需要清单：单靠命名不足

受限标识只对「**新文件**」和「**走受限默认名的文件**」成立。它不能覆盖：

1. **用户自定义模板**：现有机制允许任意模板，路径超限时回退默认名（`DownloadPathPlan.defaultName()` → `ArtworkDownloadExecutor.java:1013-1015`、`:1033`，端口定义见 `DownloadPathGuard.java:29-40`），因此同一目录内会**同时存在**受限名文件与自定义名文件。
2. **历史文件**：`file_name` 存的是模板 ID（`ArtworkSchemaContribution.java:39`），历史文件是按各自历史模板落盘的；跨模板碰撞对任何一方都不可见（F02 反例 2，`SharedDirectoryNameGuard.java:69` 只拿到本次模板）。
3. **展示元数据后续变化**：标题被回填后按记录重放会得到不同主干（F13、`ArtworkFileLocator.java:93-116`）。
4. **大小写不敏感卷**：`sanitize` 不做大小写折叠（`PixivWorkFileNameFormatter.java:267-277`），`ABC` 与 `abc` 会落到同一文件。
5. **归属判定本身**：受限标识只能证明「这个名字里有我的 ID」，无法证明「这个文件是我提交的」——这正是 F02 标题绕过所揭示的（`contains` 会在标题里命中）。

因此：**受限标识减少重名，清单判定归属**。二者一起用。

### 4.3 与现有用户自定义模板的关系

| 场景 | 处理 |
| --- | --- |
| 共享目录启用 + 用户模板渲染结果**全部唯一**（预留表无冲突） | 沿用用户模板的渲染结果，**不擅自改名**（`SharedDirectoryNameGuard.java:10-14` 的既有禁令继续有效） |
| 共享目录启用 + 预留时发现目标路径已被占用 | **下载前**拒绝并返回明确错误码（而不是写入前改个后缀——那会让记录与磁盘永久对不上） |
| 共享目录启用 + 路径超限触发了默认名回退 | 落盘用受限默认名，且该名字**写入清单与快照**，此后定位不再重放模板 |
| 共享目录**未**启用（内置结构） | 行为不变；用户模板继续只做单作品内去重（`PixivWorkFileNameFormatter.java:220-250`） |

**历史模板的处理**：不迁移、不改名。历史文件靠「清单 → 快照主干名 → 模板重放」的三级读取顺序定位（第 9 章）。新下载对该作品的**新代次**建立新清单行；老代次的行若仍在，则保留为历史。

> **待决策 D3**：受限默认命名是「**仅在默认/回退时使用**」（本设计推荐，兼容历史与用户模板）还是「共享目录下**强制取代**用户模板」（更安全但会让用户看到文件名变化，`PixivWorkFileNameFormatter.java:21` 的 `DEFAULT_TEMPLATE` 契约也会被改写）。

---

## 5. 写入与提交协议

### 5.1 目标路径的原子预留

**流程**（在**任何文件写入之前**、且在**配额预留之前**完成，见 5.5）：

```
1. 计算本代次的全部目标 (rel_path, role, page) 集合
   —— 由 resolveArtworkDirectory（ArtworkDownloadExecutor.java:1053-1073）+ 受限/自定义命名的
      渲染结果逐页展开；动图含 ugoira_webp 与 thumbnail 两项
2. 对每个目标算 path_key = 小写折叠(root_ref 相对路径)
3. 一个数据库事务内：
   INSERT INTO work_file_reservations (...) VALUES (...)   × N
   —— 任一行的复合主键冲突 → 整批回滚，返回 CONFLICT（携带冲突路径与持有者）
4. 事务提交成功 = 预留成功；此后才允许建立临时工作区并开始传输
```

**为什么不是「查一次再写」**：查询与写入之间存在竞态窗口，另一个并发任务可在此期间提交同名文件（F08-b：入口无排他、状态表覆盖式 `put`）。复合主键的 `INSERT` 把「检查 + 占用」合并成**一个原子操作**。

**冲突的处理策略**（需要区分两种冲突）：

| 冲突方 | 处理 |
| --- | --- |
| 同一作品（`work_type`+`work_id` 相同）的另一任务 | **不报错**：同作品的第二个任务按代次串行化（5.2），后者等待前者提交完成后再重新计算（因为前者的提交会改写清单，见 F03 的位置回写） |
| 不同作品 / 不同目标的另一任务 | **拒绝**：返回明确错误码（4xx），文案说明「目标路径已被占用」并附路径；不得静默改名 |

### 5.2 同一作品 / 同一目标路径的提交顺序协调

| 互斥层 | 键 | 载体 | 作用域 | 理由 |
| --- | --- | --- | --- | --- |
| 作品级串行 | `(work_type, work_id)` | 进程内 `SingleFlight` / 以键分片的锁（可复用 `QueueTaskTracker` 的同步边界，`QueueTaskTracker.java:245-252`） | 单进程 | 同一作品的两次下载会争抢同一批目标路径，且会改写同一条 `artworks` 行（F03） |
| 目标路径级原子占用 | `(root_ref, path_key)` | `work_file_reservations` 复合主键 | 跨进程（DB 级） | 跨访客、计划任务与交互任务并发时的唯一保证 |

**必须跨两条下载链路共用同一把锁**：交互入口 `ArtworkDownloadExecutor.downloadImages`（`:151-166`）与计划任务 `PixivScheduledIllustWorkExecutor.java:249`（`downloadImagesBlocking`）走同一个 `ArtworkDownloader` 实现，因此互斥键必须落在 `ArtworkDownloadExecutor` 内部，而不是 `DownloadTaskController`（`:47-122`）里。

**互斥键不能用 `statusKey`**：`statusKey = (ownerUuid ?? "admin") + ":" + artworkId`（`ArtworkDownloadExecutor.java:985-987`）——不同访客天然不同键，无法互斥；且它是展示键，`put` 还会顶掉旧状态对象（`:192-196`）。

### 5.3 文件发布与数据库更新的可恢复流程

**核心决定：所有产物先落进本代次的临时工作区，全部就绪后一次性提交。** 提交 = 逐文件 `ATOMIC_MOVE` 到目标 + 一个 DB 事务写清单。

```
阶段 A：下载（无目标副作用）
  workspace = {root}/_staging/{generationId}/
  每个产物写到 workspace 下的目标相对路径（保留子目录层级）
  普通图片：沿用 .part + move 的单文件原子写（PixivImageDownloadService.java:102-120）
  动图：zip、解帧、ffmpeg 产物全部落在 workspace 内
        —— 修掉 F13-c：WebP 与 _thumb.jpg 在同一提交步骤内发布，不再"先发布成品再补缩略图"

阶段 B：提交（可恢复、幂等）
  B1. DB 事务 1（"开票"）：把本代次的预留行标记 publishing（或写入独立 commit journal 行）
      —— 崩溃后据此知道"有代次正在提交"
  B2. 对每个目标：
        if 目标已存在且 (size, role, page) 与本代次预期一致 → 视为已发布，跳过
        else Files.move(workspace/rel, 目标, ATOMIC_MOVE)   ← 同卷 rename，原子
  B3. DB 事务 2（"落账"）：同一事务内
        INSERT work_files(... state = 'committed') × N
        INSERT file_naming_snapshots(...)          × 1
        DELETE work_file_reservations WHERE generation_id = ?
        更新 artworks 的落点与规模列（F03 要求的 folder/count/extensions/time/file_name_max_length）
  B4. 删除 workspace 目录

阶段 C：崩溃恢复（启动时，先于任何下载）
  扫描 work_file_reservations 中仍处于 publishing 的代次（以及 _staging 下残留目录）：
    对每个目标：
      目标存在且与本代次预期一致 → 向前滚（补写清单行，落账）
      目标不存在但 workspace 里还在 → 向前滚（重做 B2 的 move）
      两者都不在 → 本代次标记失败：删除本代次预留行，不写清单行
    全部目标有确定结论后：删除 workspace，清理残留预留行
```

**为什么这样设计**（逐条对应 I1/I2/I3/I6）：

- **I3 原子预留**：预留表在 A 阶段之前就已占位，与写入顺序无关。
- **I2（磁盘有、清单无是禁止态）**：B2 的每个 `move` 都由 B3 的落账收尾，而 B2 的开始由 B1 的"开票"记录；崩溃只会停在「B1 已做、B2/B3 未完成」，恢复流程能判定并向前滚。
- **I1（清单有、磁盘无是禁止态）**：清单行只在 B3 写入，而 B3 在 B2 全部完成之后；B4 之前崩溃也不会产生「清单有、磁盘无」。
- **幂等**：B2 对「已存在且预期一致」直接跳过，使恢复可重复执行。比对凭据是 `size_bytes`（可选加入内容摘要）。

**复用既有成熟机制而非另起一套**：

| 既有机制 | 复用于 | 依据 |
| --- | --- | --- |
| 恢复清单 + 启动恢复（先写清单再动原文件；崩溃后按清单复原；清单损坏则**保留**不清理） | 本节的 B1/B3 与阶段 C 的形状 | `DeleteStagingManifest.java:29-40`、`:64-115`（write）、`:117-137`（`recoverLeftovers`）、`:139-162`、`:226-285`（read 的严格校验：版本/条数/不安全 staged 名一律判损坏） |
| `RuntimeFiles.prepareRuntimeFiles` 在 Spring 上下文启动前调用恢复 | 阶段 C 的挂载点 | `RuntimeFiles.java:259-267`（`:266` 调 `recoverDeleteStagingLeftovers`）、`:184-193` |
| `PlainFilePathGuard.requirePlainParent(path, true)`：逐段校验父目录是普通目录（拒绝符号链接）并在缺失时创建 | 提交时创建目标父目录、并挡住符号链接逃逸 | `PlainFilePathGuard.java:46-66`、`:83-89`（`NOFOLLOW_LINKS`）；用途对照 `StagedFileDeletion.java:64`、`:103`、`:119` |

### 5.4 临时文件与任务工作区的隔离

| 项 | 决定 | 理由 |
| --- | --- | --- |
| 工作区位置 | `{root}/_staging/{generationId}/` | 同卷 ⇒ `ATOMIC_MOVE` 是真原子 rename；不用系统临时目录（跨卷只能复制，且受 OS 清理影响，`RuntimeFiles.java:168-173` 对 delete-staging 已有同样取舍的先例） |
| 名字 | 用 `generation_id`，而非作品 ID | 直接修掉 F08：`UgoiraTempPaths.java:25-37` 现在只含 `artworkId` |
| `_staging` 的定位 | 与 `_archives` 同级，作为**保留目录**纳入统一清单（第 7 章），使归档枚举自动排除 | 现有 `_archives` 的 5 处字面量（`UserQuotaService.java:279/342/397/524/534`）就是「不抽常量就会每个新入口漏一次」的先例 |
| 路径长度预留 | `UgoiraTempPaths.pathSentinels`（`:44-47`）目前靠 `"{" + token + ...` 字面量参与长度自检（`ArtworkDownloadExecutor.java:1010`）。工作区改成 `_staging/{generationId}` 后，**相对路径变化，哨兵必须同步改**，否则长度自检漏算 | `UgoiraTempPaths.java:39-47` 的 javadoc 已自行声明「两处各写一份字面量，改名字时极易漏改」 |
| 遗留的旧临时文件 | `_ugoira_{id}_frames.zip(.part)` / `_ugoira_{id}_frames_tmp/`（`UgoiraTempPaths.java:22`、`:30-37`）在升级后会变成无人清理的垃圾；启动恢复阶段 C 一并按「已知的旧命名模式」清理（**只删这两类精确模式**，不递归删目录内容） | I5：不做猜测性删除；精确模式来自代码常量而非目录扫描 |
| 用户目录洁净 | 工作区不在作品目录内，因此归档 `Files.walk(作品目录)`（`UserQuotaService.java:288-305`）本就不会枚举到它——但**归属模型仍要求归档改走清单**（第 8 章），不依赖"恰好不在目录里" | 双重保险 |

### 5.5 校验失败与配额（作者特别要求项）

**现状（已核实）**：`DownloadTaskController` 在 `:85-103` 先 `checkAndReserve`（`UserQuotaService.java:60-85`，直接 `artworksUsed.addAndGet(weight)`），然后 `:106` 调 `downloadImages`；而 `buildFileNamePlan` 在 `ArtworkDownloadExecutor.java:155` **同步**执行，共享目录校验会在此抛 `LocalizedException.badRequest`（`:1015-1027`）。**配额已经扣掉，全库没有任何退还入口**（`UserQuotaService.java` 只有 `checkAndReserve*`，无 refund/release）。

**设计**：

| 顺序 | 内容 |
| --- | --- |
| 1 | 校验配置（目录模板保留名/重合/长度、文件名模板受限性） |
| 2 | 计算目标路径集合 + **原子预留**（5.1） |
| 3 | 预留成功后才 `checkAndReserve` 配额 |
| 4 | 任一步在 1/2 失败 → 返回带原因的 4xx，**配额从未被扣**；预留已在失败路径回滚 |
| 5 | 若 3 之后才失败（罕见：预留成功但入队被拒）→ **撤销预留**，并把配额预留一并退回（新增退还入口，或把「预留+配额」放在同一临界区） |

**错误分类（I11）**：配置错误必须带**对应的原因码**返回，不能被当成网络错误重试：

- 交互链路：`LocalizedException.badRequest` 已成 400 且带 i18n 码（`DownloadTaskController.java:134-137`、`WorkbenchErrorResponses.localized`），问题在于**触发时机**（配额之后）而不是分类。
- 计划任务链路：**现状会误分类**——`PixivScheduledIllustWorkExecutor.java:249` 调 `downloadImagesBlocking`，任何异常落到 `:151-154` 的 `catch (Exception)` 被归为 `ScheduledFailure.Category.RETRYABLE_NETWORK`（`pixiv.illust.fetch-failed`）。配置错误会被**无限重试**。修法：为「配置/预检」类失败新增一个**不可重试**的分类（对照既有 `USER_ACTION_REQUIRED`，`:130-131`），并在预检阶段就抛出。
- 五语言：新增错误码必须在默认 + `en` / `ja` / `ko` / `zh-Hant` 五份 bundle 中同时落地（现有 `messages*.properties` 五件，见 `download.filename-template.shared-directory-needs-artwork-id` 在 5 个文件的 `:39`）。

---

## 6. 完整性判定（三态）

### 6.1 判定依据与三态定义

**判据输入**：清单（`work_files`）中该作品全部 `state ∈ {committed, missing}` 的行 + 文件系统的**可判定性**。

| 状态 | 判定条件 | 处理 | 绝不做 |
| --- | --- | --- | --- |
| `COMPLETE` | 清单中 `0..count-1` 每一页的 `role=original`（动图则 `ugoira_webp` + `thumbnail`）都有 `committed` 行，且核验时文件存在 | 视为已下载；`verifyFiles` 判重短路返回记录 | — |
| `PARTIAL` | 清单存在且有 `committed` 行，但存在 `missing` 行，或页集合不满足 `0..count-1` | **保留记录**，标记「不完整 / 待补齐」；判重**不**短路（允许补齐下载）；补齐所需信息（`folder` 提示、命名快照、`count`、`file_name`）全部保留 | **不删记录**（这正是「缺一页也进入删除记录流程」的防护点） |
| `UNVERIFIABLE` | 目录/根**无法检查**：`readAttributes` 抛 `IOException`（权限、网络盘、I/O 错误）、或 DB 查询失败 | 保留记录，返回「暂不可判定」；判重**不**短路为「已下载」，但**也不**判为「不存在」；不下发任何删除或覆盖动作 | **不当作文件已不存在** |
| `ABSENT` | 清单中存在该作品的**历史行**（`committed`/`deleted`）或该作品从未有清单；且目标目录**确认不存在**（`Files.notExists(path, NOFOLLOW_LINKS)` 且其父目录可读） | 唯一允许走「陈旧记录清理」的状态 | — |

**当前实现为什么无法区分**：`ArtworkFileService.hasArtworkFiles` 用 `new File(directoryPath).isDirectory()`（`:152-153`）——该方法对「目录不存在」与「目录不可访问」都返回 false，两者被合并。`PARTIAL` 与 `COMPLETE` 也被合并（`:156-161` 任一页命中即 `true`）。修复必须改用 `Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS)`（仓库既有范式：`PlainFilePathGuard.java:83-89`），把 `NoSuchFileException` 与其它 `IOException` 分开。

### 6.2 「哪些文件必须存在才算下载完成」

| 作品形态 | 必须存在（完成条件） | 可选（不影响完成判定） | 依据 |
| --- | --- | --- | --- |
| 静态插画（`count` 页） | `role=original`，`page=0..count-1`，各一 | `thumbnail`（可再生）、`sidecar` | `artworks.count` 是期望页数的权威来源（`ArtworkSchemaContribution.java:32`）；缩略图对静态图由 `ArtworkFileService.getThumbnailFile`（`:50-89`）按需生成并缓存，故非必需 |
| 动图 | `role=ugoira_webp`（1）**且** `role=thumbnail`（1） | `sidecar` | **缩略图对动图是必需的**：`ArtworkFileLocator.resolveHashSourceFile`（`:84-90`）与 `ArtworkFileService.resolveThumbnailSourceFile`（`:97-103`）在扩展名为 `webp` 时要求 `_thumb.jpg` 存在，否则哈希源与缩略图链路**持续**缺失（F13-c 的现存缺口） |
| 小说 | 正文文件 + 封面（`novels.cover_ext`） | 译文等 | `NovelSchemaContribution.java:54`（`cover_ext`） |

**期望页数的权威来源**：`artworks.count`。这正是「部分缺页」可判定的前提——恢复流程在没有记录的场景下**没有**期望页数，因此第 9 章的恢复必须显式降级（不得把恢复结果写成完整作品）。现状反例：`ArtworkMetadataRecoveryService.contiguousPageCount`（`:186-189`）只检查 `pageExt.size() == maxPage + 1`，因此 3 页作品只下 p0/p1 会**恢复成 `count=2` 的「完整两页作品」**，此后判重挡住补齐。该类自己的 javadoc（`:180-183`）声称拦住了缺页，与实现不符。

### 6.3 与判重、恢复流程的联动

| 环节 | 现状 | 改后 |
| --- | --- | --- |
| 判重入口 `ArtworkDownloadLookup.isDownloaded(id, verifyFiles)`（`ArtworkDownloadLookup.java:15`） | `getDownloadedRecord(...) != null`（`ArtworkDownloadLookupAdapter.java:19-20`） | 不变（接口契约不动）；语义由 `getDownloadedRecord` 内部三态化承载 |
| `getDownloadedRecord`（`DownloadedArtworkService.java:50-71`） | `:61-63` 命中即返回；`:64-65` 否则**物理删记录** | `PARTIAL` → 返回记录 + 「不完整」标记（**不** `removeStaleArtworkRecord`）；`UNVERIFIABLE` → 返回记录 + 「暂不可判定」；仅 `ABSENT` → `:64-65` 的删除路径 |
| `removeStaleArtworkRecord`（`:73-81`） | 无条件 `pixivDatabase.deleteArtwork` | 只允许在 `ABSENT` 且清单无该作品任何历史行时调用 |
| 恢复 `ArtworkMetadataRecoveryService`（`:90-104`、`:141-178`） | 固定搜 `{root}/{id}`、只认默认模板、把连续性结果直接当 `count` 写入 | 搜索范围改为「清单 → 该作品任一历史落点（含 `move_folder`）→ 配置的目录模板渲染结果 → 内置 `{root}/{id}`」；**恢复出的记录必须显式标记为「不完整/待校验」**（例如 `generation_id` 为哨兵 + 快照缺失），使判重不把它当成完整作品 |
| 计划任务判重 | `isAlreadyCompleted`（`PixivScheduledIllustWorkExecutor.java:121-123`、`PixivScheduledLocalWorkLookup.java:16`）与 `scheduled.download().isVerifyFiles()` | 同上：三态后 `PARTIAL` 不得返回「已完成」 |

**为什么这三处必须一起改**（作者警告项的直接落地）：只改 `hasArtworkFiles` 的严格度而不改 `DownloadedArtworkService:64-65` 的删除行为，会把数据可用性从「保留旧记录」倒退成「删掉整条记录」（实施清单 F12 风险段已明确指出）；只改判定而不改恢复流程，则 `PARTIAL` 永远无法被补齐（恢复侧仍写「完整」记录）。

---

## 7. 路径与保留目录规则

### 7.1 保留目录清单（单一来源）

| 名称 | 实际路径 | 保护对象 | 依据 |
| --- | --- | --- | --- |
| 归档输出目录 | `{下载根}/_archives` | 归档 zip、其它访客正在下载的包 | 5 处字面量：`UserQuotaService.java:279`、`:342`、`:397`、`:524`、`:534` |
| 提交工作区 | `{下载根}/_staging` | 本设计新增（5.4）；`{generationId}` 子目录 | 本文 |
| 小说独占目录 | 任意目录中最后一段等于 `novel-{novelId}` | 小说正文/封面；插画**不得**落入，小说删除**不得**波及插画 | `NovelDownloadService.java:158`、`LocalWorkAssetService.java:318-320`（名字必须严格等于 `novel-{id}`） |
| 运行期私有目录 | `config` / `state` / `data` / `plugins` 及 `data` 下的 `delete-staging`、`gallery_thumbs`、`collection_icons`、`backfill` | 程序自身数据 | `RuntimeFiles.java:43-70`、`:174-182`、`:144-146` |

**必须收口为单一常量来源**：现有 `_archives` 是 5 处独立字面量（同上），每新增一个打包入口就会再漏一次（实施清单 F09「必须改成什么」第 4 条已列）。

### 7.2 允许 / 拒绝规则表

判定输入：候选**实际路径** `<abs>`（`Path.toAbsolutePath().normalize()`）与它的**祖先链**。

| # | 情形 | 判定 | 理由 |
| --- | --- | --- | --- |
| R1 | `<abs>` 是 OS/盘根 | **拒绝** | 既有守卫，保留：`ArtworkFileLocator.java:196-199`、`LocalWorkAssetService.java:298-304` |
| R2 | `<abs>` 等于 `download.root-folder` 本身 | **拒绝** | 既有守卫，保留：`ArtworkFileLocator.java:206-209`、`LocalWorkAssetService.java:305-314`、`DownloadPathGuardAdapter.java:37-43`（词法 `startsWith` 已挡住「等于/在根外」） |
| R3 | `<abs>` **不由** `PathPrefixCodec` 判定在下载根之内（`requireWithinDownloadRoot`，`ArtworkDownloadExecutor.java:406-416`） | **拒绝** | 既有守卫；插画初始落点恒在根内（`:999`）。分类器搬移到根外的既有行为不受影响（`ArtworkFileLocator.java:183-187` 的 javadoc 明确允许 `move_folder` 在根外），但那时**不新建**文件，只改 `move_folder`（`ArtworkMoveService.java:44`） |
| R4 | `<abs>` **等于**任一保留目录 | **拒绝** | I7；`_archives` 尤其严重（F09-b） |
| R5 | `<abs>` 是任一保留目录的**祖先** | **拒绝** | 会把保留目录包进作品作用域（归档时会枚举、递归删除时会波及） |
| R6 | `<abs>` 是任一保留目录的**后代**（例 `{root}/_archives/sub`、`{root}/_staging/x/sub`） | **拒绝** | 同上，反向；现有 `equals` 判定完全看不见这一类（F09-c） |
| R7 | `<abs>` 的最后一段等于 `novel-{n}`，且本次作品的 `work_type ≠ NOVEL` 或 `work_id ≠ n` | **拒绝** | `LocalWorkAssetService.java:233-237` 会递归枚举该目录**全部**常规文件后删除（F09-a）；必须是硬拒绝，不能只警告 |
| R8 | `<abs>` 是某个 `novel-{n}` 独占目录的**后代**（例 `{root}/novel-77/123`） | **拒绝** | 同上；即使叶目录名等于自己的 ID 也无济于事——小说侧照样递归（`prs/105-reply3.md:5` 的第 3 个反例） |
| R9 | `<abs>` 由**目录模板渲染段**拼成，且任一段命中「保留名」或 `safePathSegment` 拒绝的形态 | **拒绝** | 现状**无任何保留名校验**：`ArtworkFolderTemplate.java:34-57` 只做 `sanitize` + 丢空段（`:49-55`），`ArtworkDownloadExecutor.java:1053-1073` 只做 `resolve` + `normalize` |
| R10 | 两个**作品目录**互为祖先或后代（父子重合），且删除/归档走清单 | **允许** | 这是本次特性的**主要用例**：`{author_name}/{artwork_id}` 在作者名缺失（空段被丢弃，`ArtworkFolderTemplate.java:49-55`）时退化成 `{root}/{artwork_id}/`，成为另一作品目录的父目录。**注意**：只有第 8 章的「按清单删除」落地后 R10 才安全；见 7.3 |
| R11 | `<abs>` 的路径中包含**符号链接**（任何一段） | **拒绝**（写入侧）；删除侧已是拒绝 | 见 7.4 |
| R12 | `<abs>` 的长度超出该卷的实测限制（`DownloadPathGuard.limits` / `pathSupport`，`DownloadPathGuard.java:29-40`、`FileSystemPathLimits`） | 走既有 `DownloadPathPlan` 的溢出动作（回退默认名 / 返回 409 `NeedsAction`） | `ArtworkDownloadExecutor.java:1001-1011`、`:1013-1015`；`DownloadTaskController.java:139-145` 的 409 通道 |
| R13 | 渲染段命中 Windows 保留名（`CON`/`PRN`/`NUL`/`COM1`…） | **允许**（已被清理掉） | `PixivWorkFileNameFormatter.sanitize`（`:267-277`、`:29-33` 保留名集合、`:273-275` 加 `_` 前缀）对目录模板的**每一段**都生效（`ArtworkFolderTemplate.java:50`）——这是既有正确行为，规则表中保留以免回归 |

### 7.3 父子目录重合判定：判据是「作用域」而不是「路径关系」

**为什么不能一律拒绝父子重合**：`{author_name}` 作父目录让多作品共享，其本身就是一个作品目录成为另一个作品目录的祖先的合法形态；实施清单 F09 风险段已明确警告「守卫必须只拒绝真重合，不能扩大到让小说目录名一律不可用于插画，否则会破坏本次特性的主要用例」（`implementation-plan.md:345`）。

**因此判定拆成两条正交规则**：

| 规则 | 判定对象 | 结论 |
| --- | --- | --- |
| 保留目录重合（R4–R8） | 候选路径与**保留目录清单**的祖孙关系 | 命中即拒绝（保留目录天然是「非作品」的整目录作用域） |
| 作品目录重合（R10） | 候选路径与**其它作品目录**的祖孙关系 | 允许；安全性由「删除/归档/打包只作用于清单声明的文件集合」（I4、第 8 章）保证 |

**实现要点**：把现有 `equals` 判定升级为「相等或互为祖先」——涉及点：

- `ArtworkFileLocator.java:206`（`absolute.equals(downloadRoot)`）
- `LocalWorkAssetService.java:307`（`dir.equals(downloadRoot)`）
- `ArtworkDownloadExecutor.java:999` / `:206`（`requireWithinDownloadRoot`，底层 `DownloadPathGuardAdapter.java:41` 的 `startsWith`）

其中第三处 `startsWith` 已能判「在根内」，本设计需要的是**新增**「与保留目录的祖孙关系」判定（`candidate.startsWith(reserved) || reserved.startsWith(candidate)`），而不是替换既有 `startsWith`。

### 7.4 符号链接策略

**现状是一处不一致，必须显式规定**：

| 链路 | 实现 | 是否解析符号链接 | 证据 |
| --- | --- | --- | --- |
| 写入前校验 | `DownloadPathGuardAdapter.requireWithinRoot`：`toAbsolutePath().normalize()` 后 `startsWith` —— **纯词法** | **不解析** | `DownloadPathGuardAdapter.java:36-44` |
| 删除侧 | `PlainFilePathGuard.isPlainDirectory` / `requirePlainParent`：`readAttributes(..., NOFOLLOW_LINKS)` 并要求既非符号链接也非 `other` | **拒绝任何一段是符号链接** | `PlainFilePathGuard.java:25-34`、`:46-66`、`:83-89`；用例 `ArtworkFileLocator.java:163-166`、`StagedFileDeletion.java:199-203`、`LocalWorkAssetService.java:292-297` |

后果：下载根下**已有的符号链接**（例 `{root}/author -> D:\outside`）在写入侧通过校验，实际写入位置落在根外；而同一路径在删除侧会被拒绝，形成「能写不能删」的不对称。

**策略（明确规定）**：

1. **写入前**：对目标及其每一级父目录执行 `PlainFilePathGuard.requirePlainParent(target, true)`——复用既有能力，缺失目录会被创建、符号链接段会被拒绝（`PlainFilePathGuard.java:46-66`）。
2. **根内校验升级为真实路径校验**：`target.toRealPath()`（父目录已存在时）必须仍落在 `root.toRealPath()` 之下；不满足 → 拒绝并返回明确错误码。这补上词法 `startsWith` 的漏洞（`DownloadPathGuardAdapter.java:41`）。
3. **不自动跟随、不自动修复**：发现根下的符号链接一律**拒绝该次下载**并报告路径，**不**自动删除链接、**不**自动改写到链接目标——避免 PII/越界写入，也避免「猜测性操作」。
4. **删除侧维持现状**（拒绝任何符号链接段）——它比写入侧更严格，方向正确，不改。
5. **清单里记录的是规范化后的实际目标路径**（`root_ref` + `rel_path`）；若某路径在提交时被判定不安全，则不建立提交，代次标记失败。

> **待决策 D4**：是否需要允许「用户有意把下载根的一部分软链到其它卷」的场景（例如 root 内 `_archives` 软链到大容量盘）。本设计推荐**一律拒绝**（R11），因为允许链接就要求清单额外记录「链接目标解析历史」，否则搬迁后定位失效。

---

## 8. 删除与归档

### 8.1 删除统一走 `StagedFileDeletion.deleteAtomically`

**统一入口**（唯一）：

| 调用方 | 现状 | 改后 |
| --- | --- | --- |
| 作品删除（核心） | 已用 | `LocalWorkAssetService.java:82-87` → `ArtworkFileLocator.deleteArtworkFiles`（`:155-181`），`:168` 调 `deleteAtomically` |
| 配额/访客归档后的删除 | **未用**：`Files.walk` 整目录 + 丢弃返回值 | `UserQuotaService.java:610-630` → 改为按清单 |
| 定时清理 | 同上 | `:602-607` + `:632-643` → 按清单 |
| 小说删除 | 已用但有 F09-a 缺陷 | `LocalWorkAssetService.java:242` → 输入改为清单 |

**为什么必须复用**（作者明确要求）：`StagedFileDeletion` 提供「先写恢复清单 → 逐个暂存 → 逐个删原文件 → 任一失败回滚复原」的**文件级原子删除 + 失败回滚**，并已在 `:51-52` 与 `ArtworkFileLocator.java:151-153` 定义了失败传播契约。多条链各自实现会造出多个安全语义。

**输入来源的改变（关键）**：现在是 `resolveArtworkFiles`（`ArtworkFileLocator.java:218-236`）按**文件名主干**枚举匹配（`:238-248` 非递归 `Files.list` + `stems.contains(getBaseName(...))`）。这套匹配正是 I5 禁止的「依据主干/前缀删除」，且 F02 反例 3 已证明 `_thumb` 与主干在同一集合中不可区分（`:226-227`）。改后输入 = 清单里该作品的**全部行**（`role` 全角色、`state ∈ {committed, missing}`）：

- 归属已确认 → 精确路径集合，无猜测；
- 归属未确认（清单缺失）→ **拒绝删除**并返回明确错误（I5），而不是退回主干匹配。

### 8.2 删除中途失败的失败一致性

**两阶段删除**（与 5.3 的提交协议同构）：

```
1. DB 事务 1：把该作品清单的全部目标行置 state = 'publishing_delete'，并记录删除代次
2. StagedFileDeletion.deleteAtomically(清单路径集合)
     返回 false → 文件已被回滚复原 → DB 事务 2a：把行恢复为删除前状态（committed / missing）
     抛 UnsafeDeletionPathException → 同上回滚状态，并把该作品标记为「需人工处理」
3. DB 事务 3：把行置 state = 'deleted'；随后 artworks 软删（markDeletedById，PixivMapper.java:159-161）
4. 启动恢复：
     state = 'publishing_delete' 且文件仍存在 → 重试删除
     state = 'publishing_delete' 且文件已不存在 → 直接落定为 'deleted'
```

**这为什么保证「失败后文件与数据库记录仍能对应」**：任何中间态都有确定语义，且**文件集合**与**清单行集合**始终一一对应（I1/I2）。`StagedFileDeletion` 自身的回滚失败路径已把暂存目录（含恢复清单）保留为最后备份（`:132-159`、`:156-157`），启动恢复会据清单复原（`DeleteStagingManifest.java:117-137`、`:185-215`）。

**顺序方向保持不变**：必须先文件后 DB（现状 `UserQuotaService.java:612-617` → `:625` 方向就是对的，缺的只是失败传播）；`pack-and-delete` 分支的 `downloadedFolders.removeAll(folders)`（`UserQuotaService.java:321`）只在删除成功后执行。

### 8.3 归档与清理：从「整个目录」改为「按作品的文件集合」

| 项 | 现状 | 改后 | 证据 |
| --- | --- | --- | --- |
| 访客归档输入 | `quota.getDownloadedFolders()`（下载**目录**集合） | 该访客名下的**作品清单文件集合** | `UserQuotaService.java:276`、`:288-305`；`ArtworkDownloadExecutor.java:278-280`（现在记的是目录 `downloadPath`） |
| 管理员归档输入 | `Set<Path> uniqueFolders`（按目录去重） | `Set<workId>` → 清单 | `ArchiveController.java:134`、`:156`、`:163-164` |
| 打包遍历 | `Files.walk(folder).filter(Files::isRegularFile)` | 直接使用清单路径集合，**不做目录遍历** | `UserQuotaService.java:288-305`、`:352-372` |
| zip 条目名 | `folderName + "/" + file.getFileName()` —— **丢失子目录层级**，不同子目录同名文件互相覆盖 | 使用 `rel_path`（保留层级） | `UserQuotaService.java:297`、`:356` |
| sidecar 排除 | `*.meta.json`（`:294`） | 语义上由清单表达：归档默认不含 `role=sidecar`，或按现有规则保留排除 | `UserQuotaService.java:294`；`WorkSidecarFiles.java:36-52` |
| 归档自我排除 | 无 | 枚举输入必须排除「本次正在写的 zip 路径」与归档输出目录本身 | F09「必须改成什么」第 3 条 |
| `pack-and-delete` | 打包后对每个 folder 调 `deleteArtworkFolder` | 对**本次打包的作品**逐个按清单删除（8.1/8.2），失败即中止并不 `removeAll` | `UserQuotaService.java:315-320`、`:321` |
| 启动清理 | 按扩展名删 `_archives` 下**所有** `.zip`/`.zip.part` | 只删可判定为「本程序产生且已失效」的包（按 token 记录），不动未知文件 | `UserQuotaService.java:531-560`（`:543-545`） |
| 管理员按作品打包 | `ArchiveController.java:122-167` | 同样改按清单 | **此项不依赖多人模式**：`UniqueFolders` 的退化在单人模式下同样发生 |

**关于「管理员按作品打包的整目录问题不依赖多人模式」**：`ArchiveController.triggerAdminPack`（`:122-167`）与 `buildAdminArchive`（`UserQuotaService.java:330-383`）走的是管理员链路，其输入退化（`:156` 按目录去重、`workCount = folders.size()`）与访客配额无关。因此即使未来移除多人模式，这一段仍必须修——**这与 `prs/105-reply3.md:51` 的结论一致**。

---

## 9. 历史文件兼容与迁移

### 9.1 兼容读取规则（三级，先兼容后迁移）

定位一个文件的解析顺序（`ArtworkFileLocator` 内部职责）：

| 优先级 | 依据 | 适用 | 结果 |
| --- | --- | --- | --- |
| 1 | `work_files` 中该作品该角色该页的 `committed` 行 → `(root_ref, rel_path)` | 有清单（新下载 / 已迁移作品） | 权威；与展示元数据、后续回填完全无关（I8） |
| 2 | `file_naming_snapshots` + `work_files.base_name` | 有清单但路径需重算（例如卷/根搬迁后 `root_ref` 不变、`rel_path` 不变时无需重算；仅在需要解释文件名时使用） | 用于**解释与恢复**，不参与判重 |
| 3 | 现有模板重放：`resolveStoredFileBaseName`（`ArtworkFileLocator.java:93-116`） | 无清单的历史行 | 现状行为，**保持逐字节不变**（这是历史文件的唯一可行定位方式） |

**兼容的关键**：优先级 3 的行为**不得改动**。任何「统一改用快照」的做法都会让历史文件立刻找不到（实施清单 F13 风险段第一条已警示）。快照只在**新代次**上产生。

### 9.2 迁移触发条件与步骤

**明确规则，不做全库批量猜测**：

| 触发 | 动作 | 不做什么 |
| --- | --- | --- |
| 某作品**成功重下并提交** | 该代次写入清单与快照；旧代次的清单行（若有）保留为历史 | 不改名、不搬动任何已存在文件 |
| 用户在工具入口显式触发「为作品重建清单」 | 对**单个作品**：用优先级 3 的解析结果（现状模板重放）得到候选路径 → **只登记实际存在的文件**为 `committed` 行，并在清单中显式标注 `origin = 'reconstructed'`（或 `generation_id` 用哨兵值） | **不**根据猜测改名；**不**为找不到的文件造行；重建结果**不**标记为 `COMPLETE`（见 6.3：恢复结果必须标「待校验」） |
| 首次升级启动 | **不做任何批量回填**（大库代价不可控，且猜测性重建违背 I5） | 不遍历全库、不动磁盘 |
| 启动恢复（新增） | 只处理 5.3 阶段 C 与 8.2 第 4 步的**确定性**状态（有开票记录的残留代次），不猜测任何历史行 | 同上 |

> **待决策 D5**：是否需要（以及何时）提供「整库重建清单」的一次性工具。本设计推荐**不提供自动版**，只提供**逐作品、带明确预览与确认**的手动入口，因为自动重建无法区分「缺页」与「本来就少」（无期望页数来源，见 6.2）。

### 9.3 「归属未确认时不得删除」的落地

| 情形 | 删除行为 |
| --- | --- |
| 清单存在，作品 `state` 明确 | 按清单精确删除（8.1） |
| 清单缺失（纯历史行） | **拒绝删除**，返回明确错误码 + 提示可用「重建清单」入口（9.2）。**不得**退回 `resolveArtworkFiles` 的主干匹配（`ArtworkFileLocator.java:218-248`） |
| 清单存在但目标目录是共享目录且存在**无归属**的同名/同主干文件 | 只删清单行指向的路径；无归属文件**保留**并在诊断中列名 |
| 目录整体不可访问（`UNVERIFIABLE`） | 中止删除，保留清单与记录（6.1） |
| 独占目录壳清理 | 保持「仅当目录名等于 `artworkId` 且目录为空时才删」的既有严格条件（`ArtworkFileLocator.java:250-263`），并把「等于 `artworkId`」扩展为「等于该作品的清单归属目录名且为空」；共享目录壳与分类目录壳一律不删 |

**同时必须修正的措辞/文档偏差**（避免后人再次误信）：

- `ArtworkFileLocator.java:148` 的 javadoc 写「基于已知文件名前缀（stems）做枚举式匹配」，实现是**完整主干的精确集合成员匹配**（`:244`）。实施清单 F02 已指出该措辞不准确。
- `DownloadConfig.java:32-34` 与 `ArtworkDownloadExecutor.java:1046-1048` 的 javadoc 把「按主干非递归精确匹配」当作共享目录安全性的依据——与 F02 的结论直接冲突，改后须同步改写。
- `ArtworkMetadataRecoveryService.java:180-183` 的 javadoc 声称拦住缺页，实现只拦中间空洞（`:186-189`）。

---

## 10. 分阶段实施计划

**约束**：每个阶段合并进主线时，**该版本自身安全可用**，不得依赖下一阶段尚未合并的修复来避免覆盖或误删（I10）。因此阶段划分按「**先让现状安全，再引入新模型**」而非按模块划分。

### 阶段 0（已存在，保留）：共享目录硬门禁

| 项 | 内容 |
| --- | --- |
| 交付物 | 现有「目录模板非空时文件名模板必须含 `{artwork_id}`」校验（`SharedDirectoryNameGuard.java:62-71`、`ArtworkDownloadExecutor.java:1015-1027`）继续生效 |
| 自身安全论证 | 门禁下 F01/F02/F08/F12 的**跨作品**触发路径不可达。但**必须同时承认**：门禁的判据本身基于 F02 的伪充分条件（ID 拼接、跨模板、`_thumb`、标题绕过四个反例），且**同作品**的 F08/F12 与目录不共享无关（1.2 节），因此门禁**不是**安全性的完整保证 |
| 依赖 | 无 |

### 阶段 A：不依赖归属模型的独立修复（可并行开工）

见 **附录 A**。这些修复的正确性不依赖「清单 vs 受限标识」的选择，且单独立即提升安全性。

| 项 | 内容 |
| --- | --- |
| 交付物 | 附录 A 全表 |
| 自身安全论证 | 每条都是**收紧**而非放宽：删除失败传播（I6）、去掉目录名反推 ID、父子重合守卫（I7）、`hasArtworkFiles` 严格化 + **同时**分流删除路径（I9，两条必须同 PR，否则是数据可用性倒退） |
| 依赖 | 无（`hasArtworkFiles` 严格化与三态分流必须同 PR，见 6.3） |

> **注意**：`hasArtworkFiles` 严格化若单独合并（不带三态分流），会让「缺一页」变成「删记录」。因此该项**必须**与「`PARTIAL` 不删记录」一起交付。

### 阶段 B：清单 schema + 预留 + 提交协议（后端，门禁仍在）

| 项 | 内容 |
| --- | --- |
| 交付物 | 3.2 的三张表 + 5.1 预留 + 5.2 互斥 + 5.3 提交/恢复 + 5.4 工作区 + 5.5 预检前置与配额退还 |
| 自身安全论证 | 门禁仍生效（共享目录不可用），因此本阶段只影响**独占目录**场景。清单在独占目录下也能自证：`COMPLETE` 判定、I1/I2 可校验。若本阶段出现缺陷，退回阶段 0 语义仍然安全（清单只是额外事实，不改变既有定位路径） |
| 依赖 | 阶段 A（`hasArtworkFiles` 严格化与三态分流必须先到位，否则清单的 `PARTIAL` 状态没有消费者） |

### 阶段 C：定位 / 删除 / 归档 / 恢复统一改读清单

| 项 | 内容 |
| --- | --- |
| 交付物 | 第 8 章（删除与归档）+ 6.3（判重与恢复）+ 9.1 的优先级 1/2 读取 |
| 自身安全论证 | 到此为止 I1、I2、I4、I6、I7、I8 全部成立；**I5 需要额外步骤**：删除入口在清单缺失时**拒绝删除**，因此历史行的删除能力**暂时下降**（从「可能误删」变成「明确拒绝」）。这是**有意的降级**，需要在发布说明中告知，并配套 9.2 的手动重建入口 |
| 依赖 | 阶段 B |

### 阶段 D：历史兼容与失败场景验收

| 项 | 内容 |
| --- | --- |
| 交付物 | 9.2 的迁移入口 + 附录 B 全矩阵的自动化验收（含崩溃/磁盘满/占用/长路径/大小写/五语言） |
| 自身安全论证 | 验收通过即 I1–I11 全部可证；未通过的场景要么被门禁挡住，要么返回明确错误而非静默损坏 |
| 依赖 | 阶段 C |

### 阶段 E：解除门禁，开放 `download.artwork-folder-template`

| 项 | 内容 |
| --- | --- |
| 交付物 | 移除/替换 `SharedDirectoryNameGuard` 的伪充分条件（`SharedDirectoryNameGuard.java:37-71`），改为「受限默认命名 + 预留表精确冲突检测」；保留名/重合同步生效 |
| 自身安全论证 | 共享目录下 I1–I11 与独占目录一致；差异只在「同目录承载多作品」这一维度，而归属判定已完全由清单承担（第 4 章） |
| 依赖 | 阶段 D；**且**配置默认值仍为空（`DownloadConfig.java:38`），启用是用户的显式选择 |

### 阶段间依赖图

```
阶段 0（门禁，已存在）
  └─ 阶段 A（独立修复；hasArtworkFiles 严格化 + 三态分流同 PR）
       └─ 阶段 B（清单 + 预留 + 提交协议）
            └─ 阶段 C（定位/删除/归档/恢复改读清单）
                 └─ 阶段 D（历史兼容 + 验收矩阵）
                      └─ 阶段 E（解除门禁，开放共享目录）
```

**多人模式的位置**：`prs/105-reply3.md:51` 与作者要求一致——「归档逻辑的修复不依赖多人模式」。因此第 8 章的归档改造在**阶段 C**完成，与多人模式是否保留无关；多人模式下只要发布版本仍允许启用，就一并保证**不同用户的文件隔离**，其落点是清单的 `work_id`/访客记录改为作品集合（`ArtworkDownloadExecutor.java:278-280`、`UserQuotaService.java:100-109`）。配额预留与退还（5.5）在**阶段 B**完成，不推迟到最后一阶段。

### SDK release identity 的判断

作者的规则是「以**实际公开接口变化**与**仓库契约检查**为准，同一个未发布 PR 不必每改一个方法就加 RC」（`prs/105-reply3.md:40`）。据此：

| 变更 | 是否触及 SDK 公开面 | 依据 |
| --- | --- | --- |
| 新增三张表、`PathPrefixCodec` 复用、`PixivDatabase`/`PixivMapper` 新方法 | **否**（`pixivdownload-app` 内部 schema 与宿主实现） | `SchemaContribution` 在 app 与 `plugin-api` 声明层之间；表由宿主登记（`ArtworkSchemaContribution.java:15-17` 的 owner 语义） |
| 在 `ArtworkDownloadCompletion` 增加清单/代次字段 | **是**（`pixivdownload-core-api` 是 SDK 模块） | `ArtworkDownloadCompletion.java:36-52`；`scripts/ci/sdk-version.mjs:10-17` 列出 `core-api` 为 SDK artifact；`scripts/ci/sdk-contract.mjs:111-112` 要求「公开面变化必须同时提升 release identity」 |
| 给 `IndexSpec` 增加 `where`（D2 的备选） | **是**（`pixivdownload-plugin-api` 是 SDK 模块） | `scripts/ci/sdk-version.mjs:12` |
| 给 `WorkType` 增加 `UGOIRA`（D1 的备选） | **是**（`core-api`） | `WorkType.java:6-15` |

**结论（推荐路径）**：本设计的**推荐方案不触及 SDK 公开面**——清单的写入由宿主适配器（`ArtworkDownloadHistoryAdapter`，`ArtworkDownloadHistoryAdapter.java:34-102`）承接，下载插件提交的既有 `ArtworkDownloadCompletion` 已经包含 `folder`/`imageCount`/`extensions`/`recordTime`（`:39-42`），而逐文件的 `base_name`/`page`/`role` 可由宿主用同一套命名函数重算（`PixivWorkFileNameFormatter`）与 `folder` 组合得到。

> **但这需要一次确认**：如果「清单里的主干名必须是**落盘时实际使用**的值」，那么宿主重算与下载插件落盘必须用同一份输入；当前二者可分离（F13：落盘用请求参数、入库用过滤后参数，`ArtworkDownloadExecutor.java:300-303`）。**彻底的做法是让下载插件把逐页主干名与命名参数快照随 `ArtworkDownloadCompletion` 一起提交**（即上表的「是」行），代价是一次 release identity。若选择这一步，RC 一次性提（例如 `1.0.0-rc.16`），符合「不逐方法加 RC」。**这一条列为待决策 D6。**

---

## 11. 风险与未决问题

### 11.1 已确定（本设计的主张，可直接实施）

1. 归属事实源 = 清单；受限默认命名只减少重名，不承担归属判定（第 4 章）。
2. 路径唯一性由 `work_file_reservations` 的复合主键原子承载；清单表不设路径唯一性（3.7）。
3. 产物先落 `{root}/_staging/{generationId}/`，提交 = 逐文件 `ATOMIC_MOVE` + 一个 DB 事务（5.3/5.4）。
4. 删除统一走 `StagedFileDeletion.deleteAtomically`，并采用两阶段删除保证失败一致性（8.1/8.2）。
5. 完整性判定三态化，只有 `ABSENT` 允许删除记录（6.1）。
6. 归属未确认时拒绝删除，不退回主干匹配（9.3、I5）。
7. 保留目录与父子重合拆成两条正交规则（7.3）；写入侧补真实路径与符号链接校验（7.4）。
8. 预检与原子预留先于配额预留；拒绝必须带原因且不可重试（5.5、I11）。
9. 归档输入从「目录集合」改为「作品的文件集合」；该改造不依赖多人模式（8.3）。
10. 推荐路径不新增 release identity；若采纳「落盘名随 completion 提交」则一次性提 RC（10 章末）。

### 11.2 待原作者决策（不假装已定）

| 编号 | 问题 | 备选 | 本设计倾向 | 影响面 |
| --- | --- | --- | --- | --- |
| **D1** | 动图是否作为独立 `work_type`（新增 `UGOIRA` 枚举值） | (a) 不加，用 `role` 表达；(b) 加 | **(a)** | (b) 触发 SDK 公开面变化 → 需 release identity（`WorkType.java:6-15`、`scripts/ci/sdk-version.mjs:12`） |
| **D2** | 路径唯一性放预留表还是清单表 | (a) 预留表复合主键；(b) 清单表部分唯一索引（需扩 `IndexSpec`） | **(a)** | (b) 触发 `plugin-api` 公开面变化 → 需 release identity（`SchemaSpecs.java:30-36`） |
| **D3** | 受限默认命名是「仅回退时使用」还是「共享目录下强制取代用户模板」 | (a) 仅回退；(b) 强制 | **(a)** | (b) 用户可见文件名变化；需版本化说明（`PixivWorkFileNameFormatter.java:21` 的默认模板契约） |
| **D4** | 是否允许下载根下的符号链接指向根外 | (a) 一律拒绝；(b) 允许并记录解析历史 | **(a)** | (b) 清单需额外记录链接解析历史，搬迁后定位语义复杂（`DownloadPathGuardAdapter.java:36-44` 现为纯词法） |
| **D5** | 是否为历史文件提供整库批量重建清单 | (a) 只提供逐作品、带预览的手动入口；(b) 提供自动整库重建 | **(a)** | (b) 无期望页数来源，自动重建会产生假「完整」记录（6.2、`ArtworkMetadataRecoveryService.java:186-189`） |
| **D6** | 清单里的落盘主干名由谁产生 | (a) 宿主按同一命名函数重算（不改 SDK）；(b) 下载插件随 `ArtworkDownloadCompletion` 提交逐页主干名与快照（改 SDK，一次 RC） | **(b) 更彻底，(a) 更省**——需要原作者择一 | (b) 才真正闭合 F13 的「落盘用请求参数、读取用记录参数」缺口；一次性 release identity（`ArtworkDownloadCompletion.java:36-52`） |
| **D7** | 大小写 / Unicode 规范化的唯一口径 | (a) 只用于唯一性键 `path_key`（清单与预留），磁盘名保持保真；(b) 也用于磁盘命名 | **(a)** | 现状三处口径互不相同：`ensureUnique` 折叠（`PixivWorkFileNameFormatter.java:257-259`）、`sanitize` 不折叠（`:267-277`）、删除侧精确匹配（`ArtworkFileLocator.java:244`）。NFC/NFKC 与全角→半角是否纳入**尚未有依据**，需原作者定 |
| **D8** | `_staging` 放在下载根内还是 `data/` 下 | (a) 根内（同卷 `ATOMIC_MOVE`）；(b) `data/` 下（不污染用户可见目录，但可能跨卷） | **(a)** | (b) 提交退化为「复制+删除」，破坏原子性与崩溃恢复的简洁性（5.4） |
| **D9** | 多人模式（v1.14 仍在调查）的去留 | — | 不预判 | 只要发布版本仍允许启用，第 8 章的跨用户隔离与 5.5 的配额退还就都必须在**阶段 B/C**交付，不能推迟到 E |
| **D10** | 历史行在清单缺失时的删除是否接受「能力下降」 | (a) 明确拒绝 + 重建入口；(b) 保留受限的主干匹配 | **(a)** | (b) 与 I5 及作者「归属未确认不能凭主干删除」的要求直接冲突，本设计**不建议** |
| **D11** | `thumbnail` 是否纳入静态插画的完成条件 | (a) 不纳入（可再生）；(b) 纳入 | **(a)**，但**动图必须纳入** | 动图现状：`ArtworkFileLocator.java:84-90` 与 `ArtworkFileService.java:97-103` 要求 `_thumb.jpg` |
| **D12** | 恢复流程扩大搜索范围是否接受「跨作品误认」风险 | (a) 只在清单/记录存在时恢复；(b) 按目录模板与文件名模板全盘扫描 | **(a)** | (b) 在共享目录下可能命中同目录其它作品（实施清单 F12 风险段已警示） |

### 11.3 已知风险（已确定，但需要发布侧配套）

| 风险 | 说明 | 配套 |
| --- | --- | --- |
| 严格判定会让历史脏数据触发重下 | 记录 3 页、磁盘 1 页的状态会从「视为已下载」变成「`PARTIAL`」 | 三态化后 `PARTIAL` 只触发补齐、不删记录；但多人模式下补齐会消耗配额（`DownloadTaskController.java:85-103`），需确认可接受（6.3） |
| 阶段 C 会让「清单缺失的历史行」无法删除 | 有意降级 | 9.2 的手动重建入口 + 发布说明 |
| 五语言文案必须同步 | 新增错误码需在 5 份 bundle 落地 | 现有范式：`download.filename-template.shared-directory-needs-artwork-id` 在 `messages.properties` / `_en` / `_ja` / `_ko` / `_zh-Hant` 的 `:39`；`MessageFormat` 陷阱见 `ArtworkDownloadExecutor.java:1020-1025`（文案里不能出现字面量 `{artwork_id}`） |
| 计划的错误分类必须新增不可重试类别 | 否则配置错误被当网络错误重试 | `PixivScheduledIllustWorkExecutor.java:151-154`；对照既有 `USER_ACTION_REQUIRED`（`:130-131`） |
| `_archives` / `_staging` 常量收口 | 5 处字面量已证明会漏 | 7.1；`UserQuotaService.java:279/342/397/524/534` |
| 目录模板可热重载 | `download.artwork-folder-template` 在热重载键列表内（`RuntimeConfigReloadService.java:151-155`） | 清单/快照已记录本次使用的模板（3.2 表 3），因此配置变更不会让已提交文件失联；但**未完成提交**的代次必须按开票时的模板落盘（`ArtworkDownloadExecutor.java:993-995` 的「只读一次」注释已说明同类竞态） |
| `pathSentinels` 与工作区改名联动 | 长度自检依赖字面量 | `UgoiraTempPaths.java:39-47`、`ArtworkDownloadExecutor.java:1010` |
| 布局契约测试需同步 | `CoreApiOwnershipGuardTest.java:833-839`、`:856` 把「`novel-{workId} / asset_thumb.jpg` 位于下载根下」当契约记录 | 新增保留目录/布局时同步更新该测试期望 |

---

## 附录 A：与本次设计无关、可独立开工的修复

来源：`implementation-plan.md:496-513` 的独立项表，加上按本设计判定同样独立的补充项。**这些项不依赖「清单 vs 受限标识」的选择**，可先行实现与验收。

| 来源 | 子问题 | 为什么独立 | 证据 |
| --- | --- | --- | --- |
| F01 | 删除链路复用 `StagedFileDeletion` 并**检查删除结果**；失败时不删 DB 行 | 只补失败传播，输入仍是「要删的文件集合」 | `UserQuotaService.java:610-630` |
| F01 | 去掉 `tryParseArtworkId` 的目录名反推作品 ID | 定时清理路径本就有 `ArtworkRecord`，改为显式传 `artworkId` 是纯负向改动 | `UserQuotaService.java:645-655`、`:602-607` |
| F01 | `pack-and-delete` 分支先判删除成功再 `removeAll(folders)` | 只调整控制流与失败语义 | `UserQuotaService.java:315-321` |
| F01 | `cleanupTimedDeleteArtworks` 触发条件/窗口语义核对 | 配置语义，零归属语义 | `UserQuotaService.java:583-594` |
| F01 | zip 条目名丢失子目录层级导致的同名覆盖 | 纯命名缺陷（层级方案属小设计） | `UserQuotaService.java:297`、`:356` |
| F09 | 路径**父子重合**守卫（`equals` → 「相等或互为祖先」），对象为**保留目录** | 比较强度升级，不涉及「文件属于谁」 | `ArtworkFileLocator.java:206`、`LocalWorkAssetService.java:307`、`ArtworkDownloadExecutor.java:999/206` |
| F09 | 作品目录模板的**保留名校验** | 输入校验，依据是内部目录清单 | `ArtworkDownloadExecutor.java:1053-1073`、`ArtworkFolderTemplate.java:34-57` |
| F09 | 归档打包**自我排除**（排除本次正在写的 zip 与归档目录） | 自我保护，不需知道文件属于谁 | `UserQuotaService.java:288-305`、`:352-372`、`:397-431` |
| F09 | 启动清理不删非本程序管理的 zip | 同上 | `UserQuotaService.java:531-560` |
| F09 | `_archives` 常量收口（5 处字面量） | 常量整理 | 同上 5 处 |
| F12 | `hasArtworkFiles` 改为「`0..count-1` 全部页可解析」 | 期望页数来自 `artworks.count` | `ArtworkFileService.java:147-163` |
| F12 | **必须与上一条同 PR**：「不完整」与「目录丢失」分流，避免 `removeStaleArtworkRecord` 物理删记录 | 否则是数据可用性倒退 | `DownloadedArtworkService.java:61-81` |
| F12 | `contiguousPageCount` 的 javadoc 与实现对齐，并明确尾部缺页的降级策略 | 「无期望总页数时该怎么办」的契约决定 | `ArtworkMetadataRecoveryService.java:180-189` |
| F13 | 动图产物顺序改为「先全部就绪再发布」，`cleanup` 覆盖已发布未提交的 `webpPath` | 单文件级提交语义，不依赖清单 | `UgoiraService.java:137-144`、`:412`、`:779-791` |
| F14 | 计划任务把配置/预检失败归为**不可重试**类别 | 错误分类问题 | `PixivScheduledIllustWorkExecutor.java:151-154`、`:130-131` |
| F10 | 写入侧 `requirePlainParent(path, true)` + 真实路径根内校验 | 路径安全，不涉及归属 | `PlainFilePathGuard.java:46-66`、`DownloadPathGuardAdapter.java:36-44` |
| F11 | 目录段长度与 Windows 保留名的**配置期校验与预览** | 输入校验 | `PixivWorkFileNameFormatter.java:29-33`、`:267-277` |
| — | 修正三处与实现不符的 javadoc | 纯文档 | `ArtworkFileLocator.java:148`、`DownloadConfig.java:32-34`、`ArtworkMetadataRecoveryService.java:180-183` |

**明确依赖归属模型、不宜先行**：归档输入从「目录」改为「作品文件集合」（`UserQuotaService.java:263-383`、`ArchiveController.java:134-164`）；打包后按作品删除（`UserQuotaService.java:315-320`）；小说侧「只删本小说文件」的判据（`LocalWorkAssetService.java:233-247`）；恢复流程的目录搜索范围与模板支持（`ArtworkMetadataRecoveryService.java:90-94`、`:147-151`、`:191-209`）；命名的唯一来源与整体提交语义（`ArtworkDownloadExecutor.java:989-1037`、`:235-303`）；互斥键选「作品」还是「最终目标路径」（`ArtworkDownloadExecutor.java:985-987`）。

---

## 附录 B：验收场景矩阵

每一行给出**触发方式**、**期望结果（可断言）**与**依据**。`清单` = `work_files`；`预留` = `work_file_reservations`。

### B1 命名与撞名

| # | 场景 | 期望 | 依据 |
| --- | --- | --- | --- |
| 1 | 不同 ID，同标题 | 两作品各自 `COMPLETE`；预留无冲突则沿用各自渲染名；冲突则**下载前**拒绝并返回明确错误码 | F02 反例 1（`SharedDirectoryNameGuard.java:52-60`） |
| 2 | 超长标题（触发 `MAX_BASENAME_LENGTH` 截断） | 截断后若两作品主干相同 → 预留冲突 → 第二个被拒绝；若走默认名回退 → 主干为 `{id}_p{p}` | `PixivWorkFileNameFormatter.java:24`、`:286-294`、`ArtworkDownloadExecutor.java:1013-1015` |
| 3 | 历史模板（两作品用不同模板） | 单靠模板无法察觉的碰撞由预留表精确 `path_key` 捕获 | F02 反例 2（`SharedDirectoryNameGuard.java:69`） |
| 4 | 大小写与名称规范化 | `ABC.jpg` 与 `abc.jpg` 在大小写不敏感卷上视为同一目标 → 预留冲突；`path_key` 折叠生效 | F02 反例 + `PixivWorkFileNameFormatter.java:257-259`、`:267-277`；D7 |
| 5 | 原图 / 缩略图 / sidecar 撞名 | A 的 `_thumb` 与 B 的原图主干同名 → 预留冲突（不同 `role` 但同 `path_key`） | F02 反例 3（`ArtworkFileLocator.java:226-227`、`:244`） |

### B2 记录状态与重下

| # | 场景 | 期望 | 依据 |
| --- | --- | --- | --- |
| 6 | 活动记录重新下载（新目录） | 提交事务回写 `folder`/`count`/`extensions`/`time`/`file_name_max_length`；`move_folder` 失效语义明确 | F03（`PixivMapper.java:135-143`、`:111-115`） |
| 7 | 软删除记录重新下载 | 物理删旧行后插入新行，`moved=0`、`move_folder=NULL` | F03（`PixivMapper.java:163-165`、`ArtworkSchemaContribution.java:43-45`） |
| 8 | 已移动记录（分类器 `move_folder`）重新下载 | 新代次清单指向新落点；旧 `move_folder` 不再被优先采用 | F03；`ArtworkFileLocator.java:48-56`、`ArtworkMoveService.java:44` |
| 9 | 规划任务与交互下载并发（同作品） | 同一互斥键 → 只有一个进入提交；另一个等待或明确失败（非静默覆盖） | F08-b（`ArtworkDownloadExecutor.java:985-987`、`PixivScheduledIllustWorkExecutor.java:249`） |
| 10 | 同作者多动图并发 | 各自独立 `generation` 工作区；任一失败不影响另一方的 WebP/thumb | F08（`UgoiraTempPaths.java:25-37`）、F13-c（`UgoiraService.java:779-791`） |
| 11 | 同 ID 双任务（同访客 / 跨访客） | 预留表在同一 `path_key` 上拒绝第二个；跨访客不能被 `statusKey` 分键绕过 | F08-b（`ArtworkDownloadExecutor.java:986`） |
| 12 | 热重载（下载中改 `download.artwork-folder-template`） | 进行中的代次按开票时的模板落盘；已提交文件不失联 | `RuntimeConfigReloadService.java:151-155`、`ArtworkDownloadExecutor.java:993-995` |

### B3 归档与清理

| # | 场景 | 期望 | 依据 |
| --- | --- | --- | --- |
| 13 | 仅选一件作品打包 | zip 条目恰为**该作品清单**的文件；不含同目录第三方文件、不含 `*.meta.json`（按现行规则） | F01-c（`UserQuotaService.java:288-305`、`:294`） |
| 14 | 访客分别打包 | 两个访客各自只得到自己的作品文件；共享目录不因一方打包被整目录删除 | F01-d（`UserQuotaService.java:315-320`）、`ArtworkDownloadExecutor.java:278-280` |
| 15 | 旧作品过期清理（`timed-delete`） | 只删过期作品的清单文件；同目录的新作品与其文件保留 | F01-e（`UserQuotaService.java:583-594`、`:632-643`） |
| 16 | 归档目录自身 | `_archives` 不被枚举入包；`pack-and-delete` 后其它 zip 仍在 | F09-b（`UserQuotaService.java:288-305`、`:531-560`） |
| 17 | zip 层级 | 子目录同名文件不互相覆盖（条目名含相对层级） | `UserQuotaService.java:297`、`:356` |

### B4 失败与崩溃

| # | 场景 | 期望 | 依据 |
| --- | --- | --- | --- |
| 18 | 磁盘满 | 提交失败 → 代次标记失败；已存在文件与记录一一对应（I1/I2）；配额按 5.5 退回或不消耗 | `StagedFileDeletion.java:84-89` 形状、`PixivImageDownloadService.java:112-118` |
| 19 | 网络中断（部分页失败） | 不写清单、不写历史（现行语义保持）；**已覆盖的页必须回滚**（现状是无回滚，实施清单 F13-b） | `ArtworkDownloadExecutor.java:282-297` |
| 20 | 缩略图失败（动图） | `ugoira_webp` 与 `thumbnail` 在同一提交步骤内发布；失败则两者都不出现在目标路径，代次标记不完整 | F13-c（`UgoiraService.java:137-144`、`:412`） |
| 21 | 文件被占用 | 删除返回 false → 已回滚 → **DB 清理中止** | `StagedFileDeletion.java:99-103`、`:151-153` |
| 22 | DB 提交失败（文件已发布） | 恢复流程按开票记录向前滚（补写清单）或向后滚（撤回已发布文件），不产生「磁盘有、清单无」 | 5.3 阶段 C |
| 23 | 提交中崩溃 + 重启 | 启动恢复扫描残留代次，按「目标存在/工作区仍有/都不在」三判定收敛；`_staging` 残留被清理 | 5.3 阶段 C；`DeleteStagingManifest.java:117-137`、`RuntimeFiles.java:259-267` |

### B5 完整性判定

| # | 场景 | 期望 | 依据 |
| --- | --- | --- | --- |
| 24 | 全部缺失（`ABSENT`） | 才允许清理陈旧记录 | 6.1 |
| 25 | 内部缺页（p0、p2 有，p1 无） | `PARTIAL`：保留记录、不删、判重不短路（允许补齐） | `ArtworkMetadataRecoveryService.java:186-189`（现只能拦这一类） |
| 26 | 尾部缺页（3 页只有 p0/p1） | `PARTIAL`：**不得**恢复成 `count=2` 的完整记录 | F12-b（`ArtworkMetadataRecoveryService.java:99-104`、`:156-161`） |
| 27 | 缺缩略图（动图） | 不 `COMPLETE`（动图 thumbnail 是必需项，D11） | `ArtworkFileLocator.java:84-90`、`ArtworkFileService.java:97-103` |
| 28 | 只有目录、无完成标记 | 目录存在但清单无 `committed` 行 → `PARTIAL`/`ABSENT` 按目录可判定性决定；不得凭目录存在判为完成 | 6.1、I9 |
| 29 | 目录暂时无法访问（权限/网络盘） | `UNVERIFIABLE`：保留记录与清单，**不**判为不存在、**不**删记录 | 6.1；`ArtworkFileService.java:152-153`（现状无法区分） |

### B6 路径与文件系统

| # | 场景 | 期望 | 依据 |
| --- | --- | --- | --- |
| 30 | Windows 长路径 | 走既有 `DownloadPathPlan` 溢出动作（回退默认名或 409），且 `pathSentinels` 计入工作区占用 | `ArtworkDownloadExecutor.java:1001-1011`、`:1010` |
| 31 | 中文与 emoji | 命名保真（`sanitize` 不剥非 ASCII）；`path_key` 折叠仅用于唯一性键 | `PixivWorkFileNameFormatter.java:267-277`、`:257-259`；D7 |
| 32 | Windows 保留名 | 目录段与文件主干都被加 `_` 前缀，不产生 `CON`/`NUL` 等 | `PixivWorkFileNameFormatter.java:29-33`、`:273-275`；`ArtworkFolderTemplate.java:50` |
| 33 | 大小写不敏感卷 | 预留冲突按 `path_key` 判定（不依赖卷行为） | 同 #4 |
| 34 | junction（Windows）/ Linux 符号链接 | 写入前拒绝含链接段的路径（`requirePlainParent` + 真实路径校验） | 7.4；`PlainFilePathGuard.java:46-66`；D4 |
| 35 | 父子目录重合（`{author_name}/{artwork_id}` 作者名缺失） | 允许（作品目录）；但若退化成保留目录或落在保留目录内 → 拒绝 | 7.3；`ArtworkFolderTemplate.java:49-55`；`prs/105-reply3.md:4` |
| 36 | `novel-{id}` 与插画 | 插画不得渲染进 `novel-{id}` 本身或其任何后代 → 拒绝（R7/R8） | F09-a（`LocalWorkAssetService.java:233-237`、`:318-320`） |
| 37 | 大作者目录 + 多页作品 | 删除/打包按清单（非递归目录）→ 时间与作用域都只与作品规模相关 | I4、8.3 |
| 38 | 外置 ImageClassifier 搬移后的作品 | 清单行随 `move_folder` 语义更新（搬移只改记录、不搬目录的既有行为不变） | `ArtworkMoveService.java:44`、`ArtworkFileLocator.java:48-56` |

### B7 国际化、配置与配额

| # | 场景 | 期望 | 依据 |
| --- | --- | --- | --- |
| 39 | 五语言真实错误响应 | 新增错误码在默认/`en`/`ja`/`ko`/`zh-Hant` 五份 bundle 都有；占位符只有编号 `{0}`/`{1}`，无字面量花括号 | `messages.properties:39` 等 5 文件；`ArtworkDownloadExecutor.java:1020-1025` |
| 40 | 默认文件名回退 | 路径超限回退到受限默认名时，清单记录**实际使用**的主干名 | `ArtworkDownloadExecutor.java:1013-1015`、`:1033` |
| 41 | 计划任务配置错误 | 归为**不可重试**分类并带原因，不再表现为 `RETRYABLE_NETWORK` | `PixivScheduledIllustWorkExecutor.java:151-154`；5.5 |
| 42 | 未入队时的配额处理 | 预检失败 → 配额未被扣；已扣则退回 | `DownloadTaskController.java:85-103`、`UserQuotaService.java:60-85`；5.5 |
| 43 | 新旧 SDK / 宿主 / 插件组合 | 推荐路径不新增 release identity，因此新旧组合不因清单改动而失配；若采纳 D6(b) 则一次性提 RC 并同步 5 处身份承载点 | `scripts/ci/sdk-contract.mjs:111-123`、`prs/105-reply3.md:40` |

---

*本文档为设计说明草案，所有论断均附 `文件:行号` 证据；第 11.2 节的 12 项待决问题需要上游原作者拍板后方可进入编码阶段。*

---

> **以下为需求方补充的约束性章节（R）。**
> 若与前文任何章节冲突，以 R 为准；请在评审时一并考虑。

## 需求来源与动机（为什么需要这个设计）

> 本节说明需求的来源，供评审判断优先级与取舍时参考。技术问题见第 1 章，约束见下节 R。

### M1 · 原始诉求

需求方在本地维护一个约 189 GB 的 Pixiv 归档（约 68,460 件作品 / 197,962 个文件），
希望**下载即可直接归档，并且能在本地文件管理器里浏览与检索** ——
而不必每次都启动应用、回到应用内的画廊界面去找。

### M2 · 两个具体痛点

1. **文件名不可读**：内置默认模板 `{artwork_id}_p{page}` 产出 `12345678_p0.jpg`，
   从文件名无法识别作品，本地检索（按名搜索、排序、扫读）基本不可用。
2. **目录粒度过粗**：内置结构 `{下载根}/{作者}/{作品ID}/{文件}` 下，
   浏览某作者的全部作品必须**逐个进入 `{作品ID}/` 文件夹**，无法一屏铺开看缩略图。

两者叠加，使「本地归档浏览」这一场景基本不可用。

### M3 · 为什么应用内画廊不能替代

应用内画廊解决的是「**启动应用时**」的浏览。本地归档浏览是**另一个场景**：
不启动应用，用资源管理器或第三方看图 / 管理工具直接浏览。二者不可互相替代，
因此这是一条**独立需求**，而不是个人偏好。

### M4 · 现实依据

需求方现有归档本身就是 `(画师ID)画师名/文件` 的结构（68,734 条记录按此导入，
覆盖六种历史命名规范）。也就是说，共享目录**不是本次改动新造的用法**，
而是现实中已经存在的归档约定。

### M5 · 因此对本设计的期望

不要因为「共享目录不安全」而排除该用法；而应通过**文件精确清单**让共享目录变得安全 ——
这正是本设计的价值所在。具体约束见下节 R。

## 补充需求 R · 本地目录浏览粒度（一等需求，不得被本设计排除）

> 本节由需求方补充，作为对前述设计的**约束**而非建议。
> 若与第 2 章的不变量或第 4 章「受限默认命名」的取向冲突，以本节为准。

### R1 · 需求陈述

必须支持「**同一作者的全部作品位于同一个目录**」，使本地文件管理器（资源管理器、看图工具等）能够：

- 在**不启动本应用**的前提下，一屏浏览某作者的全部作品（缩略图 / 按文件名排序 / 搜索）
- 直接按可读文件名识别作品，而不是只有数字 ID

### R2 · 为什么这是独立需求

应用内画廊解决的是「**启动应用时**的浏览」。本需求是**另一个场景**：不启动应用、用本地工具浏览与归档。二者不可互相替代。

### R3 · 现状痛点

内置结构 `{下载根}/{作者}/{作品ID}/{文件}` 下，浏览某作者的全部作品必须**逐个进入 `{作品ID}/` 文件夹**；文件名又只有 `{artwork_id}_p{page}`，无法从文件名识别作品。这两点叠加使本地浏览基本不可用。

> 现实依据：需求方现有约 189 GB 的归档本身就是 `(画师ID)画师名/文件` 这一结构（68,734 条记录按此导入）。共享目录不是个人偏好，而是**既有的现实归档约定**。

### R4 · 与安全的关系（本设计必须吸收，而不是排除）

该需求**只能**通过共享目录满足。共享目录的安全性由**文件清单**保证 —— 这正是本设计的价值所在：

- 归属由清单记录，不再依赖「按模板猜文件名」，因此**多作品共用目录是安全的**
- 删除、归档、恢复都可按清单精确到作品

因此本设计**必须把共享目录作为一等支持场景**，而不能以「安全性」为由排除它。请把「共享目录 + 清单」作为目标形态来设计。

### R5 · 对清单的硬性约束

1. 清单**必须存储实际落盘的相对路径与主干名**（即最终文件名），而不是只存模板 id 与渲染参数。
   —— 只有这样，「可读命名」与「可靠归属」才能并存；否则清单会把可读命名挤掉。
2. 清单中的归属判定**不得**依赖「按模板重新推导文件名」；模板与参数只作为解释与恢复用途。
3. **「受限的默认命名」不得被解释为「取消用户自定义可读模板」。** 受限标识的用途是减少新文件重名，属于防御手段；用户仍应能使用 `({artwork_id}){artwork_title}_p{page}` 这类可读模板，且在共享目录下同样安全。

### R6 · 验收要求（加入附录 B 验收矩阵）

| 场景 | 期望结果 |
| --- | --- |
| 共享目录下某作者含多件作品 | 本地文件管理器一屏可见全部作品文件，无需逐个进入子目录 |
| 可读模板 + 共享目录 | 文件名可读、可检索；不因归属机制被改写为机器命名 |
| 共享目录下删除任一作品 | 仅删除该作品的文件，同目录其它作品不受影响 |
| 共享目录下打包任一作品 | 压缩包内只含该作品的文件 |
| 不启动应用 | 上述浏览能力不依赖应用运行 |

