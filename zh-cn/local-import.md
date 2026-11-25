# 本地作品导入与本地导入预设

本地作品导入可根据文件路径中的作品 ID，将磁盘上已有的插画、漫画和动图以**只读引用**方式批量登记到作品库（保留原目录与原文件，删除画廊记录不会删除原文件；路径中无法提取的标题、作者、标签等元数据可在导入后通过[数据回填](#metadata-backfill)补齐）。导入 PixivBatchDownloader 导出的历史 JSON 请使用[导入外部下载](/zh-cn/pixiv-batch-downloader-import)。

?> 本地作品导入是在线工具，需保持后端运行（不进入停服维护模式）。Compose（位于「工具 → 快捷工具」弹窗，关闭弹窗后后台任务继续运行）与 Swing 均从桌面工具页进入；HTTP API 中的路径同样指运行后端的服务器本地路径。

## 路径模板与可用变量

**路径模板**用花括号 `{变量}` 标出路径中的可变部分，供程序反向提取作品信息：

- 每条模板必须包含 `{artwork_id}`（可位于目录名或文件名中），并使用绝对路径（Windows 支持 `/` 或 `\`）。模板仅用于匹配与解析，不会创建目录或重命名文件。
- **文件夹模板与单文件模板**：以 `/` 结尾表示**文件夹模板**（递归收集该目录及子目录中的受支持文件）；不以 `/` 结尾表示**单文件模板**（需写明文件名与 `.{extension}`）。
- 变量名固定使用小写下划线（精确匹配）；**相邻变量之间必须有固定分隔字符**（如 `{artwork_id}_{name}`，不得连写为 `{artwork_id}{name}`），且 `*` 不作为通配符。

| 已有文件的实际路径 | 填入的路径模板 | 解析结果 |
| --- | --- | --- |
| `D:/xxx/12345/pic.png` | `D:/xxx/{artwork_id}/` | 作品 ID 为 `12345`，收集该目录及子目录中的受支持文件 |
| `D:/xxx/12345_xxx.png` | `D:/xxx/{artwork_id}_xxx.{extension}` | 作品 ID 为 `12345`，扩展名为 `png`（`_xxx` 为固定文字） |
| `D:/678/12345/pic.png` | `D:/{author_id}/{artwork_id}/` | 作者 ID 为 `678`，作品 ID 为 `12345` |
| `D:/xxx/12345_p0.jpg` | `D:/xxx/{artwork_id}_p{page}.{extension}` | 作品 ID 为 `12345`，第 `0` 页 |

| 变量 | 含义 |
| --- | --- |
| `{artwork_id}` | 必填，正整数作品 ID |
| `{author_id}` | 正整数作者 ID |
| `{artwork_title}` | 路径中已有的作品标题 |
| `{author_name}` | 路径中已有的作者名称 |
| `{page}` | 页码（从 `0` 开始） |
| `{extension}` | 文件扩展名（不含前导 `.`） |
| `{name}` | 匹配单个路径片段中的其他名称（不作为作品标题入库） |

## 操作步骤

工具弹窗中的「本次导入选项」（含临时路径模板、作品 ID、指定文件）默认收起：已保存预设时三个输入框均可留空并直接点击「扫描并预览」；未保存预设且临时模板留空时，点击扫描会自动展开该区域并标红提示填写模板。

### 方式一：单次填写临时模板

1. 打开桌面「工具 → 本地作品导入」，展开「本次导入选项」。
2. 在「临时路径模板」中输入绝对路径模板（每行一条，如 `D:/xxx/{artwork_id}/`；填写临时模板时仅使用临时模板，且不会写回配置文件）。
3. **作品 ID（可选）**：单件填 `12345`，多件用换行、空格或逗号分隔；留空则扫描模板匹配的全部作品。
4. **指定文件（可选）**：仅登记单个文件时填写其路径（仍需提供能匹配该文件的模板）。
5. 点击「扫描并预览」，核对作品 ID、路径与预览状态后点击「开始导入」。

### 方式二：保存本地导入预设（适合固定目录经常导入）

**本地导入预设**（配置键 `local-import.presets`）保存常用路径模板列表，扫描时自动启用全部已保存路径：

1. 打开「工具 → 本地作品导入」，点击弹窗内的「本地导入预设」。
2. 点击「添加路径」，输入 `D:/{author_id}/{artwork_id}/` 等绝对路径模板（每行一条，支持双击编辑或移除；尽量指向存放作品的具体子目录以缩小扫描范围）。
3. 点击「保存预设」（立即生效，无需重启；取消不改动已保存列表）。
4. 返回导入弹窗，三个输入框均留空即可扫描全部预设路径；若在「作品 ID」填入 `12345`，程序会自动在 `D:/` 下各作者目录中定位该作品，无需预先提供作者 ID。

## 预览状态、缓存与导入计数

- **受支持格式**：`JPG`、`JPEG`、`PNG`、`APNG`、`GIF`、`WebP`、`WebM`、`MP4` 与 `ZIP`（须通过文件头校验，ZIP 仅用于动图帧包，不会当作任意压缩包解包导入）。
- **扫描索引缓存**：最近一次目录扫描索引在内存中保留 5 分钟，不自动监听磁盘变化；新增、移动或删除文件后，请勾选「重新扫描目录，刷新索引」再预览。
- **取消与备份**：取消任务会等待当前正在登记的作品收尾，已登记作品保留。外部文件为只读引用，移动或删除原文件会导致画廊引用失效，备份时请同时备份数据库与外部源目录。

| 预览结果 | 含义 | 执行时计入的统计项（按作品数统计） |
| --- | --- | --- |
| `NEW` | 登记新作品 | 写入成功计入 `imported`，无实际变更计入 `skipped`，写入出错计入 `failed` |
| `SUPPLEMENT` | 为已导入作品补充外部文件或页面 | 写入成功计入 `imported`，无实际变更计入 `skipped`，写入出错计入 `failed` |
| `DUPLICATE` | 所有文件均已登记 | `skipped` |
| `CONFLICT` | 存在冲突（如同一页匹配到多个文件，不会强行覆盖） | `failed` |
| `MISSING` | 未找到匹配文件 | `failed` |

例如 4 部作品均为 `CONFLICT` 时，执行结果为 `imported: 0, skipped: 0, failed: 4`；具体原因可在预览条目的 `reason` 或任务结果的 `problems` 中查看。

## HTTP API

管理员或具备 `core:local-import.*` 权限的程序密钥可通过 `/api/local-import` 执行相同流程（同一实例同时仅运行一个导入任务，预览与索引不跨进程重启保留）：

1. `GET /api/local-import/presets`：读取已保存预设。
2. `POST /api/local-import/previews`：提交扫描请求（返回 `202` 与预览 ID；`templates` 留空表示使用全部已保存预设）：

   ```json
   {
     "templates": [],
     "artworkIds": [12345, 12346],
     "refreshIndex": false,
     "selectedFile": ""
   }
   ```

3. `GET /api/local-import/previews/{id}`：轮询至 `phase` 为 `READY`，通过 `GET /api/local-import/previews/{id}/items?offset=0&limit=20` 分页查看明细。
4. `POST /api/local-import/tasks`：提交 `{"previewId": "<预览 UUID>"}` 启动导入。
5. `GET /api/local-import/tasks/{id}`：查询导入进度与结果（取消扫描或导入分别调用 `POST /api/local-import/previews/{id}/cancel` 或 `POST /api/local-import/tasks/{id}/cancel`）。

读取已导入插画的原始文件请按页调用 `GET /api/downloaded/rawfile/{artworkId}/{page}`（首页为 `0`；导入记录的 `folder` 可能仅为模板公共前缀，各页文件可分散在不同子目录）。

## 导入后补齐作品资料 :id=metadata-backfill

仅靠 `{artwork_id}` 导入时，作品会立即计入画廊总数，但标题、作者、标签和系列为空。如需按标题、标签或系列检索，可在桌面打开「工具 → 数据回填」（开启启动回填后下次启动也会自动检查），或通过 HTTP API 定向回填刚导入的作品 ID（将 `12345` 替换为实际作品 ID；省略 `artworkIds` 则在全库插画与小说中按 `limit` 选择候选）：

1. **查看候选数**（需 `core:backfill.read`）：`POST /api/tools/backfill/candidates`，请求体 `{"artworkIds": [12345]}`。
2. **预演回填**（需 `core:backfill.execute`）：`POST /api/tools/backfill/tasks`，请求体 `{"artworkIds": [12345], "dryRun": true}`，收到 `202` 后按 `Location` 轮询至结束。
3. **正式回填**（需 `core:backfill.execute`）：`POST /api/tools/backfill/tasks`，请求体 `{"artworkIds": [12345], "dryRun": false}`，同样按 `Location` 轮询至结束。

回填默认先读取本地已有元数据，仍有缺失再按需请求 Pixiv（详见[网络访问与第三方服务](/zh-cn/network-access)）。同一时间仅运行一个回填任务；终态记录保留 1 小时（最多 64 条），过期、被挤出或服务重启后按 ID 查询返回 `404`。
