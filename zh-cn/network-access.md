# 网络访问与第三方服务

本页说明各功能连接的目标地址、发送的数据、代理路由与关闭方式。前半部分为已安装应用的**运行时请求**；构建、测试、CI 与社区投稿的工程请求单列在[开发、构建和发布流程的网络访问](#开发、构建和发布流程的网络访问)，不会因日常运行应用而触发。

?> 本页列出代码内固定的默认地址及受控动态地址范围。第三方服务可能经 DNS、CDN、重定向或临时下载链接分发资产；管理员自行配置的自定义 URL 取决于实际设置。

## 运行时概览与代理路由

- **触发原则**：AI、翻译、TTS、推送、邮件、验证码和 Douyin 仅在启用且被调用时联网；管理员或程序密钥调用 HTTP API（数据回填、来源发现、下载与后台批次、AI/邮件/推送测试、FFmpeg 安装、更新检查与下载）同样按需触发对应插件请求（测试参数仅当次有效，HTTP 批次可在客户端断开后继续执行，停止任务或停用功能即停止后续请求）。`notification` 插件启用后在后台定期拉取签名公告索引；启用在线更新与自动检查时，宿主在启动就绪后检查 GitHub Releases；桌面与无头服务在就绪后按需后台回填缺失的已登记作品元数据。
- **插件市场**：`plugin-catalog.enabled`、官方仓库与社区仓库默认开启，但应用启动本身不拉取远程清单；仅在管理员页面检查插件更新、打开/刷新市场、预览仓库、安装插件或手动刷新撤销信息时请求对应仓库。
- **PostHog 调查**：布局调查（`download-workbench`）与多人模式调查（`multi-mode-decision-survey`）的公开参数保存在插件源码的 `posthog-config.js`；普通源码与 fork 构建生成的 `release-activation.js` 默认为 `false`，默认不连接 PostHog。
- **出站代理路由与重试边界**：
  - Pixiv、在线更新、FFmpeg 自动下载、官方/社区插件仓库、服务端验证码校验与公告同步沿用宿主或任务级代理设置。
  - AI、翻译、TTS 和 Douyin 支持功能级或任务级的直连 / 代理选择；`direct-strict` 自定义插件仓库强制直连。
  - SMTP 使用 `mail` 插件自有连接与可选 SOCKS 代理；PostgreSQL JDBC 直连目标数据库服务器，不走 HTTP 代理。
  - 浏览器同源 API、介绍页 Google Fonts、浏览器端 Web Speech API、验证码前端控件、PostHog 与油猴脚本由浏览器直接发起，不经过 Java 后端代理。
  - 插件使用宿主受管 HTTP 客户端时，传输层不会对限流、服务不可用或连接错误自动重发请求；需重试的功能在业务层按各自策略控制次数与退避。

## 核心宿主与默认请求

- **本地导入在线指南**：仅当用户在设置页或工具页点击「使用说明与示例」时，才由系统浏览器打开 `https://sywyar.github.io/PixivDownloader/`（页面从 `cdn.jsdelivr.net` 加载 docsify 资源、从 `raw.githubusercontent.com` 加载项目图标；URL 仅带界面语言路径，不附带作品 ID、目录、文件内容或凭据，走浏览器网络设置而非 Java 后端代理）。
- **同源认证与国际化**：登录页通过同源 `/api/auth/check`、`/api/auth/login`、`/api/auth/invite-redeem` 检查会话与提交认证（回跳地址仅接受同源站内路径）。前端页面通过 `/api/i18n/meta`、`/api/i18n/messages/{namespace}` 与同源 `i18n-static/` 加载词典（单次超时 10 秒，缺失词典最多重试 3 轮：1.5、5、15 秒），不连接外部翻译服务。
- **本机 GUI 通道**：桌面模式通过 `http://127.0.0.1:{随机端口}/api/gui/**` 轮询状态与提交操作，携带后端生成的 GUI token，强制本机直连并拒绝代理、重定向与转发头；`--no-gui` 模式不创建该端口。
- **外部链接确认**：在应用 Web 页面点击外部 HTTP(S) 链接时，会先弹出目标地址确认框，确认后才由浏览器打开。

| 所有者 | 目标地址 | 用途与发送内容 | 触发与关闭方式 |
| --- | --- | --- | --- |
| 应用宿主（更新检查与下载） | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json` 及 `.sig`、nightly 同名清单、已验签清单中的安装包 URL（含 GitHub Release CDN 重定向） | 检查更新并校验内置 Ed25519 签名（清单/签名上限 1 MiB / 16 KiB）；仅在用户确认更新后下载安装包（上限 500 MiB，核对精确大小与 SHA-256）。不发送当前版本、Cookie 或凭据 | `update.enabled` 与 `update.auto-check` 默认开启（启用 nightly 时独立检查两路清单）；沿用宿主代理，最多 5 跳 HTTPS 重定向。本项目 GitHub Release 入口的整条链路限于 `github.com`、`githubusercontent.com` 及其子域；启用代理时由代理解析域名，不受本机 Fake-IP 影响。直连和自定义更新地址仍逐跳检查公网 IP。关闭上述配置即可停用 |
| 应用宿主（介绍页字体） | `https://fonts.googleapis.com/css2?...`、`https://fonts.gstatic.com/...` | 浏览器加载 Noto Sans SC 字体，仅暴露标准浏览器连接信息 | 打开 `intro.html` 时触发；不经后端代理，屏蔽域名后自动回退本地字体 |
| 应用宿主（连通性探测） | `https://www.pixiv.net/` | 检测后端到 Pixiv 的连通性，不携带 Pixiv Cookie | 首次配置引导或手动执行探测时触发，无后台心跳 |
| 应用宿主（程序密钥命令行） | 默认为按 `config/config.yaml` 推导的本机实例地址，或 `--server-url` 指定的 http / https 实例 | 以管理员身份登录 `/api/auth/login`，调用 `/api/security/program-keys` 列出、创建或撤销密钥，结束时调用 `/api/auth/logout`；发送管理员用户名、密码和本次会话 Cookie，单个响应上限 1 MiB | 仅在运行 `--program-keys`、`--program-key-permissions`、`--program-key-create` 或 `--program-key-revoke` 时触发；不读取系统代理，不跟随重定向，不自动重试 |
| `notification` 插件 | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json`、相邻 `.sig` 及 `.../announcements/<message-id>/<locale>.html` | 校验官方 Ed25519 签名、有效期、递增序号与正文 SHA-256，仅为未知或摘要变化的公告下载多语言受控 HTML 并保存在本地；不发送 Cookie 或应用凭据 | 启动后随机等待 0–30 分钟，之后每 6 小时（±15%）检查（支持 `ETag`/`Last-Modified` 条件请求与退避）。沿用宿主代理；禁用或卸载 `notification` 即停止 |

## Pixiv 下载、浏览与导入

| 所有者 | 目标地址 | 用途与发送内容 | 触发与关闭方式 |
| --- | --- | --- | --- |
| 应用宿主（共用查询与缩略图） | `https://www.pixiv.net/ajax/user/{id}?lang=zh`；HTTPS `*.pximg.net`、`embed.pixiv.net` | `/api/pixiv/user/{id}/meta` 使用请求提供的取得凭据；计划查询 `/api/pixiv/user/{id}/meta/schedule` 使用本次计划的凭据和代理，已绑定的 secret 只在服务端读取。`/api/pixiv/thumbnail-proxy` 仅访问上述图片域，不带 Cookie、不跟随重定向，单次上限 100 MiB | 页面请求对应本地接口时触发。保存 User 来源的插画或小说计划时，同一用户已有有效名称快照则复用；新建、换用户或缺少名称时查询并保存名字，查询失败则不保存计划。停止相关预览、不保存此类计划即可避免对应请求 |
| 应用宿主（元数据回填） | `https://www.pixiv.net/ajax/illust/{id}`、`/ajax/novel/{id}` | 先导入已登记作品目录的旧 `.meta.json` 并用本地元数据补缺，仍有缺失才无 Cookie 请求上述接口（单次上限 4 MiB；404 记入 `data/backfill/unreachable.json`，年龄限制响应暂缓自动重试） | 桌面与无头服务启动就绪后后台自动回填，或从桌面工具页 / 独立 CLI 手动运行；读取已保存的全局代理（工具页可单次临时覆盖），可在工具页或停止服务取消 |
| `pixiv-content` (公开查询) | `https://www.pixiv.net/ajax/search/artworks/**`、`/ajax/search/illustrations/**`、`/ajax/search/manga/**`、`/ajax/search/novels/**`、`/ajax/illust/**`、`/ajax/user/**`、`/ajax/series/**`、`/ajax/novel/**`、`/ranking.php`、`/ajax/ranking/novel` | 向 Pixiv 发送关键词、作品类型、页码、日期或 ID，搜索画作和小说，查询创作者的作品与系列，以及画作和小说排行榜。调用方可提供本次采集凭证。 | 默认安装。调用 `/api/pixiv-content/` 下的接口时使用宿主代理设置，停用插件即可停止访问。 |
| `pixiv-content` (内容浏览) | `/ajax/illust/{id}/recommend/init`, `/ajax/illusts/comments/roots`, `/ajax/user/{id}/profile/all`, `/ranking.php`, `/rpc/cps.php`, `/ajax/user/self`, `/ajax/user/{id}`, `https://*.pximg.net` | 按作品查询相关推荐和顶层评论，按作者列出系列，按作品类型读取排行榜。通过标签建议接口输入的关键词会匿名发送给 Pixiv。头像从画师资料给出的 CDN 地址读取，CDN 请求不带 Cookie。 | 调用对应接口时触发。`/me` 用所选凭据核验当前账号后读取该账号资料。 |
| `pixiv-content` | `/ajax/search/*`, `/ajax/{illust,novel}/{new,discovery}`, `/ajax/{illust,novel}/recommend/*`, `/ajax/{illusts,novels}/comments/{roots,replies}`, `/ajax/top/*`, `/ajax/watch_list/*`, `/ajax/user/{id}/*`, `/ajax/user/{id}/{illustmanga,novels}/tag`, `/ajax/collection/{id}`, `/ranking.php` | 作者投稿可按作品标签读取，多标签查询会分批读取候选投稿。 搜索用户、标签和公开珍藏集，读取发现页、全站新作、后续推荐及评论回复。个人首页、追更和收藏标签使用同一已核验账号；收藏数筛选还会读取其会员状态。 | 按需请求。标签搜索匿名发送关键词。公开珍藏集的封面链接可能指向 embed.pixiv.net。 |
| `pixiv-content` (个人内容) | `https://www.pixiv.net/ajax/user/self`、`/ajax/user/{id}/illusts/bookmarks`、`/ajax/user/{id}/novels/bookmarks`、`/ajax/user/{id}/following`、`/ajax/follow_latest/illust`、`/ajax/follow_latest/novel`、`/ajax/user/{id}/profile/all`、`/ajax/user/{id}/profile/collections`、`/ajax/collection/{id}` | 先用选定的 Pixiv Cookie 确认登录账号，再用同一 Cookie 查询收藏（可按标签和公开状态筛选）、关注列表、关注作者的新作和珍藏集。读取珍藏集内的作品前，先确认该珍藏集属于当前账号。 | 仅在调用 `/api/pixiv-content/me/` 下的接口时访问。 |
| `pixiv-content` (图片和动图) | `https://www.pixiv.net/ajax/illust/{id}/pages`、`/ajax/illust/{id}/ugoira_meta`、`/ajax/novel/{id}`、`/ajax/novel/{id}/insert_illusts`、`https://*.pximg.net` | 向 Pixiv 查询作品各页、动图帧、小说封面及文中插图。向 CDN 请求图片或帧 ZIP 时，仅以相应作品页或小说页作为 `Referer`，不发送 Pixiv Cookie。插件在本地将各帧转换为 APNG。 | 读取作品详情时可查询各页信息，读取小说详情时会核验文中引用的画作。仅在调用方请求图片或动图时从 CDN 读取文件。 |
| `download-workbench` 插件 | `https://www.pixiv.net/ajax/illust/**`、`/ajax/user/**`、`/ajax/search/artworks/**`、`/ajax/series/**`、`/ajax/collection/**`、`/ajax/follow_latest/illust`、`/rpc/index.php`、`/ajax/illusts/bookmarks/add` | 获取插画/动图/画师/系列/搜索/收藏夹/关注新作/约稿元数据（普通 JSON 上限 4 MiB，关注新作未知总页数最多扫 1,000 页），以及开启「自动收藏」后在下载完成时添加收藏；按需携带 Pixiv Cookie | 预览、快捷获取、下载、计划任务、收藏数筛选补查（每次最多并行 6 件）或系列简介补查时触发；停用插件或关闭自动收藏可停止对应请求 |
| `download-workbench` 批量搜索与搜索计划 | `https://www.pixiv.net/ajax/search/artworks/**`、`/ajax/search/novels/**` | 手动预览使用当前凭据，计划搜索使用任务凭据与代理，逐页获取作品 | 固定页数搜索在本页完成后请求下一页，达到本轮上限或本页有作品待重试时停止翻页。下载设置中的最短翻页间隔也用于手动批量获取；设置正值时，工作台按间隔逐页请求预览。停用计划可停止后续运行。 |
| `novel` 插件 | `https://www.pixiv.net/ajax/novel/**`、`/ajax/novel/series/**`、`/ajax/novel/series_content/**`、`/ajax/user/**/novels`、`/ajax/user/**/novels/bookmarks`、`/ajax/search/novels/**`、`/ajax/novels/bookmarks/add` | 获取小说正文、系列（响应上限 1 MiB）、作者小说、收藏、搜索结果及下载后自动收藏；预览后紧随的下载通过绑定 owner 与凭据的 256 KiB 短期一次性票据复用已验证响应 | 小说预览、合订、搜索、计划任务时触发；禁用 `novel` 插件即停止 |
| 应用宿主（共享图片传输） | HTTPS `*.pximg.net`（缩略图另允许 `embed.pixiv.net`） | 下载原图、封面、缩略图、Ugoira ZIP 与小说内嵌图（最多 512 个映射），携带 Pixiv Referer；非 2xx 不缓冲错误正文 | 下载作品、生成封面/缩略图或小说文件时触发 |
| 根目录油猴脚本 | `https://www.pixiv.net/ajax/**`、`*.pximg.net`；配置的后端地址（程序默认 `http://localhost:6999`；从后端安装的脚本优先使用安装地址，已有用户设置优先） | 在 Pixiv 页面通过浏览器登录态读取作品或本地下载；向后端调用下载、队列、收藏夹、小说状态、`/api/i18n/meta`（3 秒超时）与工具箱会话/Cookie 同步接口（不把 Pixiv Cookie 发给小说票据接口） | 在 Pixiv 页面操作脚本时由浏览器/脚本管理器直连；禁用脚本即可停止 |
| `pixiv-batch-downloader-import` 插件与脚本 | 本机 `/api/pixiv-batch-downloader-import/token`, `/api/pixiv-batch-downloader-import`, `/api/pixiv-batch-downloader-import/history`; 脚本更新源 | 实时自动导入复用 PixivBatchDownloader 下载事件中的元数据，将本机文件路径与元数据发往 solo 回环服务。浏览器与桌面历史导入仅核验本地记录和原文件，不请求 Pixiv 元数据、不使用 Pixiv Cookie，也不上传原文件。历史导入完成后，可在 GUI 确认启动宿主数据回填 | 自动导入由下载触发；历史导入由用户启动，结束后不会自行启动回填。停用脚本/插件或取消任务即停止对应导入 |

此外，通过 SDK `DownloadTasks.submit` 或管理员 `POST /api/download/tasks` 提交任务时，同样调用对应插件获取元数据与媒体，工作台在可见时每 2 秒轮询同源 `GET /api/download/tasks`（终态保留 5 分钟）。

多 Cookie 只对明确失效或上游 HTTP 401、429 按选择顺序尝试，每次请求每项最多一次。429 按账号冷却至少 30 秒，并遵守更长的 Retry-After；一次元数据请求的候选共用 60 秒期限，媒体请求共用 600 秒期限。公开作品读取及用户名查询可换账号，账号私有来源只使用首选账号的候选，收藏写入不切换或重放。403、404、验证挑战、网络错误、5xx 和本地限流不触发换号。取消、插件停用和风险保护优先；沿用原代理，不增加轮询或第三方服务，带凭证请求不跟随重定向。

## 第三方与可选功能插件

### `douyin` 插件（第三方按需安装）

默认发行包不预置，仅在手动安装、启用并执行 Douyin 操作时访问：

- `https://www.douyin.com/aweme/v1/web/**`：获取用户作品、喜欢、合集、搜索、音乐、收藏、账号与作品详情（携带用户配置的 Douyin Cookie、从 Cookie 提取的 UIFID 及本地计算的 `x-secsdk-web-signature`）。
- HTTPS `*.douyin.com`、`*.iesdouyin.com`（如 `v.douyin.com`）：受限跳转解析短链接。
- HTTPS `*.douyinvod.com`、`*.douyinpic.com`、`*.douyinstatic.com`、`*.amemv.com`、`*.byteimg.com`、`*.bytedance.com`、`*.bytecdn.cn`、`*.pstatp.com`、`*.snssdk.com`：下载视频、图集、封面与实况照片。

路由按「任务单独代理 → 来源默认路由 → 宿主全局代理」选择；停止任务或禁用插件即停止。

### `ai` 与 `translation` 插件

AI 插件使用 OpenAI 兼容协议：连接测试、小说翻译和多角色分析向所选基础地址的 `/chat/completions` 发送文本、提示词、模型名与 API Key（小说翻译附带最多 1,000 条名词映射表项）；在桌面设置点击获取模型列表时，向草稿基础地址的 `/models` 发送 GET（先匿名请求，遇 401/403 且已填密钥时携带 Bearer 密钥重试一次；`https://api.anthropic.com/v1` 按 [Anthropic 协议](https://platform.claude.com/docs/en/api/models/list)使用 `anthropic-version`、`x-api-key` 与 `after_id` 分页）。所有请求不保存 Cookie、不跟随重定向。

| AI 预设 | 默认基础地址 | AI 预设 | 默认基础地址 |
| --- | --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | 智谱 | `https://open.bigmodel.cn/api/paas/v4` |
| Anthropic 兼容 | `https://api.anthropic.com/v1` | Moonshot | `https://api.moonshot.cn/v1` |
| Gemini 兼容 | `https://generativelanguage.googleapis.com/v1beta/openai` | 豆包 / 火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` |
| xAI | `https://api.x.ai/v1` | 腾讯混元 / TokenHub | `https://tokenhub.tencentmaas.com/v1` |
| Mistral | `https://api.mistral.ai/v1` | 百度千帆 / ERNIE | `https://qianfan.baidubce.com/v2` |
| Groq | `https://api.groq.com/openai/v1` | 讯飞星火 | `https://spark-api-open.xf-yun.com/v1` |
| DeepSeek | `https://api.deepseek.com` | MiniMax | `https://api.minimax.cn/v1` |
| 阿里云百炼 / Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenRouter / SiliconFlow | `https://openrouter.ai/api/v1`、`https://api.siliconflow.cn/v1` |
| Ollama（本机） | `http://localhost:11434/v1` | LM Studio（本机） | `http://localhost:1234/v1` |

`translation` 插件在手动或自动翻译时调用所选引擎。AI 复用上表配置；有道单条请求 `https://openapi.youdao.com/api`，批量请求 `https://openapi.youdao.com/v2/api`，发送文本、应用 ID 与本地计算的 v3 签名，不直接发送应用密钥。LibreTranslate 向管理员配置的 `{libretranslate-url}/translate` 发送 JSON，含单条或数组文本及可选 `api_key`。两个引擎分别配置 `translation.<引擎>.use-proxy` 与 `read-timeout-seconds`（默认关闭代理、读取 120 秒）；连接超时为 30 秒。字符、批量、并发、发送间隔和限流重试参数见[小说与朗读](/zh-cn/novel)。响应读取预算取 1 MiB 与本批实际输入码点数乘 16 字节的较大值，最大 16,000,000 字节，超限拒绝。仅明确限流时有限重试，超时等其他错误不自动重放。

选择 LibreTranslate 后，打开翻译弹窗或重新加载语言时，`translation` 插件会向同一配置地址的 `/languages` 发送 GET 请求，读取目标语言，不发送正文或 API 密钥。查询沿用上述代理、超时和 1 MiB 响应限制；不打开弹窗或切换引擎即可避免此查询。有道选项使用内置官方语种表，无额外查询。

手动、系列或下载后自动翻译时，`translation` 插件将所选的标题、简介或正文片段及目标语言发送给选定服务。下表中的云服务均需主动选择，默认直连，可在设置中为各引擎启用代理。切换引擎后，新任务使用新服务。密钥和令牌通过 HTTPS 传输；签名接口发送访问 ID 和签名，签名密钥保留在本地。

| 提供商 | HTTPS 目标 | 鉴权 |
| --- | --- | --- |
| DeepL | `api-free.deepl.com/v2/translate`, `api.deepl.com/v2/translate` | `DeepL-Auth-Key` |
| Google 翻译 | `translation.googleapis.com/language/translate/v2` | `X-Goog-Api-Key` |
| Microsoft Azure 翻译 | `api.cognitive.microsofttranslator.com/translate` | `Ocp-Apim-Subscription-Key, Ocp-Apim-Subscription-Region` |
| 百度翻译 | `fanyi-api.baidu.com/api/trans/vip/translate` | `appid / MD5` |
| 腾讯云 TMT | `tmt.tencentcloudapi.com/` | `TC3-HMAC-SHA256` |
| 火山翻译 | `translate.volcengineapi.com/?Action=TranslateText&Version=2020-06-01` | `HMAC-SHA256` |
| 彩云小译 | `api.interpreter.caiyunai.com/v1/translator` | `X-Authorization` |
| 小牛翻译 | `api.niutrans.com/NiuTransServer/translation` | `apikey` |
| 阿里云机器翻译 | `mt.aliyuncs.com/` | `ACS3-HMAC-SHA256` |

选择 DeepL 后，打开翻译弹窗或重新加载目标语言还会携带密钥访问同一域名的 `/v3/languages?resource=translate_text`。下表中的其他云服务使用内置语言列表。

### `tts`、`push` 与 `mail` 插件

| 插件 | 服务 / 通道 | 默认目标地址 |
| --- | --- | --- |
| `tts` | 浏览器引擎 | 浏览器直接调用系统 / 浏览器 Web Speech API（不经后端） |
| `tts` | Edge TTS 与版本探测 | `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`、同主机 `/voices/list`；握手 403 刷新版本时请求 `https://edgeupdates.microsoft.com/api/products?view=enterprise` |
| `tts` | 云端与自建 TTS | 小米 MiMo `https://api.xiaomimimo.com/v1/chat/completions`；Fish Audio `https://api.fish.audio/v1/tts`；MiniMax `https://api.minimax.io/v1/t2a_v2`（国内 `https://api.minimaxi.chat/v1/t2a_v2`）；ElevenLabs `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}`；阿里云百炼 `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`（国际区 `dashscope-intl.aliyuncs.com`）及返回的临时音频 URL；豆包 `https://openspeech.bytedance.com/api/v1/tts`；VoxCPM / CosyVoice `{base-url}/audio/speech`（VoxCPM 另查 `/models`） |
| `push` | 推送通道 | Bark `https://api.day.app/push`（可改自建）；钉钉 `https://oapi.dingtalk.com/robot/send`；飞书 `https://open.feishu.cn/open-apis/bot/v2/hook/{key}`；企业微信 `https://qyapi.weixin.qq.com/cgi-bin/webhook/send`；Telegram `https://api.telegram.org/bot{token}/sendMessage`；PushPlus `https://www.pushplus.plus/send`；Server 酱 Turbo `https://sctapi.ftqq.com/{key}.send`；Server 酱³ `https://{uid}.push.ft07.com/send/{key}.send`；自定义 Webhook URL |
| `mail` | SMTP 预设与自定义 | 网易 `smtp.163.com:465` / `126.com` / `yeah.net` / `qiye.163.com`；腾讯 `smtp.qq.com:465` / `exmail.qq.com:465`；新浪 `smtp.sina.com:465`；Gmail `smtp.gmail.com:587`；Outlook `smtp-mail.outlook.com:587`；Microsoft 365 `smtp.office365.com:587`；iCloud `smtp.mail.me.com:587`；Yahoo `smtp.mail.yahoo.com:465`；阿里企业邮 `smtp.qiye.aliyun.com:465`；或自定义 SMTP + 可选 SOCKS 代理 |

携带凭据的 TTS 与 Push HTTP 请求均不保存 Cookie、不跟随重定向。停用对应插件、关闭通道或清空凭据即可停止连接。

### 插件市场、FFmpeg、数据库、页面编辑器与验证码

- **插件市场与撤销检查**：
  - 官方仓库读取 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`（nightly 为 `nightly-manifest.json`）、按需历史分片 `history/<pluginId>-<sha256>.json`、版本 Release 中的市场文档/截图附件及插件包 URL。
  - 社区仓库读取 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/generated/current.json`、世代目录分片、撤销清单、审核记录及已签名 Release 资产。
  - 自定义仓库读取管理员导入的 `repository.json`（上限 64 KiB）及其声明的 `manifest-v1` / `paged-v2` 端点、`revocations.json` 与 `repository-update.json`。
  - 已安装插件更新检查结果缓存 5 分钟（部分失败缓存 30 秒）；兼容旧版回退查询单插件最多读取 10 页、共享 10 秒预算。关闭 `plugin-catalog.enabled` 或对应仓库开关即可停用。
- **FFmpeg 自动安装**：桌面状态页下载或 Windows 安装器勾选 FFmpeg 任务时，请求 `https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/ffmpeg-release.json`、`.sig` 及对应平台 `ffmpeg-*.zip`，校验官方 Ed25519 签名、大小与 SHA-256 后解压（桌面端走宿主代理，Windows 安装器优先使用当前用户系统 HTTP/HTTPS 代理）。
- **油猴脚本更新**：从仓库安装的独立脚本由 Tampermonkey 访问 `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js`；从后端安装的脚本指向该后端 `/api/scripts/*.user.js`。
- **官方 PostHog 调查插件**：仅在官方发行构建激活（`release-activation.js` 为 `true`）且管理员打开含有效调查的站内信或触发下载页布局调查时，浏览器才直接访问 `https://layout-survey.sywyar.top` 与 `https://us.posthog.com`（仅发送派生的调查作用域匿名标识与答卷，不发送安装 UUID、账号、Cookie 或路径；停用 `posthog` 插件即关闭）。
- **PostgreSQL 数据库插件**：通过 JDBC 连接管理员配置的 `database.jdbc-url`（默认端口 5432，连接预检超时 10 秒），发送数据库认证材料、业务与私库数据；直连目标服务器，不经过 HTTP 代理。详见[配置参考](/zh-cn/configuration#主数据库与引擎切换)。
- **无固定外部公网目标的插件**：`gallery`、`gallery-tools`、`gui-compose`、`gui-swing`、`page-editor`（内置本地 GrapesJS 与 CodeMirror，关闭 CDN 与遥测）本身只访问当前实例同源 API，不发起固定第三方公网请求。
- **`captcha` 操作验证码插件**：
  - **ALTCHA（SHA-256 / PBKDF2）、字符验证码（Hutool）、滑块拼图（Tianai）**：均在当前实例与浏览器本地完成，不访问第三方服务。
  - **Cloudflare Turnstile**：绑定并发布后，浏览器加载 `https://challenges.cloudflare.com/turnstile/v0/api.js` 与 iframe；提交时服务端经受管 HTTP（继承应用代理、禁重定向与 Cookie）向 `/turnstile/v0/siteverify` 发送密钥与票据。
  - **Google reCAPTCHA v2**：绑定并发布后，浏览器加载 `https://www.google.com/recaptcha/api.js?render=explicit`（及 `www.gstatic.com`、`recaptcha.google.com`）；提交时服务端经受管 HTTP 向 `https://www.google.com/recaptcha/api/siteverify` 发送密钥与票据。移除组件并重新发布页面即可停止外部请求。

## 开发、构建和发布流程的网络访问

以下请求仅在开发者或 CI 执行源码构建、依赖解析、测试、打包、签名、发布或运行社区投稿工具时发生，**已安装的客户端运行时不会发起这些请求**：

| 工程环节 | 访问目标与用途 |
| --- | --- |
| JDK 与容器运行环境 | CI 的 `actions/setup-java` 从 `api.adoptium.net` 查询并经 GitHub Releases / CDN 下载 Temurin 25 JDK、校验文件与签名（ProGuard 任务使用 `jdk+jmods` 同时拉取同版本 JMOD；请求仅含版本与平台标识，可带 job 令牌，不发应用数据）。Docker 构建与 SDK 工程 `Plugin candidate` 工作流从 Docker Hub registry / CDN 拉取 `eclipse-temurin:25-jre` 或固定摘要的 Temurin 25 JDK 镜像（走 runner / Docker 代理配置，可通过 workflow 或 `Dockerfile` 替换来源；客户端运行不触发下载） |
| Maven / Gradle / sbt 与 npm 依赖解析 | Maven Wrapper 与 Java 模块从配置的 Maven 仓库/镜像（默认 `https://repo.maven.apache.org/maven2`、`https://repo1.maven.org/maven2`、`https://plugins.gradle.org`）下载构建工具与依赖（含 `maven-toolchains-plugin`、Tink、PostgreSQL/SQLite/H2 JDBC、Hutool/Tianai/ALTCHA、Cupertino、WebP/JSON Schema/JCS、sbt-launch 等；Toolchains 与 Compose 仅使用本机已安装的 JDK 25，不自动下载 JDK）；前端检查与插件资源构建从锁文件配置的 npm 源（当前为 `https://registry.npmmirror.com`，维护 Web 资源时偶用 `https://registry.npmjs.org`）下载依赖 |
| AOT 开发准备 | 显式运行 `AOT Prepare` 时调用上述 Maven / Gradle 构建与维护者资源生成（沿用 Maven 仓库、镜像、代理与离线 `-o` 设置，仅发送依赖坐标，不上传实例配置或作品数据；缓存训练本身在本机隔离目录执行，已有产物的 CI 验收不重复构建） |
| 浏览器与数据库集成验收 | Playwright 浏览器安装从 `cdn.playwright.dev` / `playwright.download.prss.microsoft.com` 下载 Chromium；显式运行 `page-editor-acceptance.mjs` 验证码验收时使用官方公开测试密钥访问 Cloudflare Turnstile 与 Google reCAPTCHA 测试端点；PostgreSQL 容器验收拉取 Docker `postgres:17` 并只连接 `PIXIV_TEST_POSTGRES_URL` 隔离库 |
| 贡献者名单 | Maven 构建应用资源时，生成脚本向 `api.github.com/graphql` 发送仓库名和当前 Git 历史中的代表提交 SHA，查询作者与共同作者。通过 `/repos/Sywyar/PixivDownloader` 和 `/user/{id}` 读取仓库所有者和账号资料，并从 `avatars.githubusercontent.com` 下载 GitHub 返回的头像。`GITHUB_TOKEN` 或 `GH_TOKEN` 只发送到 API。请求使用环境或系统 HTTP(S) 代理，每次限时 30 秒。Maven `-o` 或缺少令牌时跳过。 |
| 许可证与本地打包脚本 | 许可证维护脚本按需读取 `spdx/license-list-data`；`package-local.ps1` 构建离线包时下载并验签 `ffmpeg-stable` 资产；`package-installer-with-plugins.ps1 -PluginSource Catalog` 从官方插件仓库下载签名清单与插件包 |
| CI 依赖缓存与验收产物传输 | `actions/cache` 与 `gradle/actions/setup-gradle` 通过 runner 的 GitHub Actions 缓存服务读写 Maven/Gradle 依赖及输入匹配的 Compose 编译与 ProGuard 输出（主线构建配置、SDK/Compose 源码或 ProGuard 规则变化时在 Linux/Windows 预热缓存，仅实际运行 Gradle 的任务才初始化 Gradle 缓存并校验官方 Wrapper 摘要；不上传 Build Scan 或依赖图）。SDK 构建 job 通过 Actions artifact 服务上传冻结产物，下游验收按同次 run 的 artifact ID 下载并校验 SHA-256 |
| SDK 验证、发布与跨仓 Pages 构建 | `sdk-legacy-plugin.mjs` 下载固定 `gui-swing-v1.0.2` 资产验证 SDK 2.x 在加载前拒绝 SDK 1.x 插件；主仓库仅验证通用 SDK 消费者；独立仓库 `Sywyar/PixivDownloader-Plugin-Douyin` 的 `Host compatibility` CI 拉取主仓库固定宿主提交并按 Maven 源解析依赖以验证插件兼容（只读权限、不发用户凭据、不发布插件，可停用 workflow 或更新 `HOST_REVISION`）；SDK 发布工作流向 Sonatype Central Portal 发布 5 个 SDK Maven 坐标、向 `Sywyar/PixivDownloader-Plugin-SDK` 创建签名 Release 并构建 Javadoc GitHub Pages；开发者在 SDK 工程首次执行 `sdk-prepare` / `sdk-run` 时从 SDK Release 下载固定运行包并缓存到 `~/.cache/pixivdownloader-sdk/` |
| 主仓库 CI、Gate Checks、Release 与公告续签 | GitHub Actions 运行 Quality Gate、发行包 E2E、签名插件发布与 `Gate Checks`（通过 GitHub API 与 App token 为开放 PR 签发必需检查）；`PixivDownloader-Remote-Content` 的定时工作流检查并续签 `announcements/index.json.sig` 及触发 Pages 部署 |
| 官方插件下载统计与发布结转 | `publish-plugins.yml` 默认每日 18:33 UTC 在完整 Quality Gate 通过后，用 `release` Environment 跨仓令牌分页读取 `api.github.com/repos/Sywyar/PixivDownloader-plugins/releases`（每页 100 条），汇总正式版各版本与 nightly 当前附件下载量，并通过 Git HTTPS 向该仓库 `master` 提交签名清单（私钥留在 runner，移除 `schedule` 即可停用每日刷新；插件发布脚本替换附件前会经 `releases/{release_id}` 将累计下载量与附件 ID 写入 Release 说明供重试去重） |
| 社区插件投稿向导、隔离构建与审核发布 | 投稿入口 `irm https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/tools/submit.ps1 \| iex` 下载已签名的 `submission-channel.json` 与固定提交工具，按 `no_proxy` → `https_proxy` / `all_proxy` → Windows 系统代理解析网络（单文件 60 秒、最多 3 次尝试），通过 GitHub CLI / Git 访问社区仓库与投稿者 fork、上传候选 Release 资产与文档图片；社区 CI 在受控 Squid 代理容器内仅从批准的 Maven / Gradle 源预取依赖，在断网容器内重建插件，并通过 GitHub Actions OIDC / Sigstore（`fulcio.sigstore.dev`、`rekor.sigstore.dev`、`tuf-repo.github.com`）生成归档证明、执行审核合并与撤销清单续签 |
| 社区撤销清单定时续签 | `Renew community revocations` 默认按计划运行。创建或复用开放的续签 PR 后，GitHub 托管运行器使用 GitHub CLI 和该作业的 `GITHUB_TOKEN`，向 `api.github.com/repos/Sywyar/PixivDownloader-community-plugins/actions/workflows/community-status.yml/dispatches` 发送 `master` 分支名、PR 编号和完整 head SHA，启动受保护的签名与合并。维护者可在 Actions 中停用续签工作流以停止定时派发 |
