# 网络访问与第三方服务

本文列出 PixivDownloader 当前代码可能访问的外部网络目标，以及负责发起请求的宿主组件或插件、请求用途、触发条件和默认状态。文中的“插件”采用插件管理页显示的插件 ID；“应用宿主”表示请求由核心应用 `pixivdownload-app` 发起，不归属于可选插件。

?> 本清单涵盖当前代码内固定的默认地址和允许的动态地址范围。第三方服务可能使用 DNS、CDN、重定向或临时下载地址，因此实际连接的 IP 地址和最终子域可能发生变化。管理员配置的自定义 URL 不在可预先穷举的范围内。

## 网络访问概览

- AI、TTS、推送、邮件和 Douyin 仅在相应功能完成配置并被调用时访问外部服务；`notification` 是例外，它会在启用并启动后自动检查固定公告索引。
- 在线更新和自动检查均启用时，应用宿主会在启动就绪后检查 GitHub Releases；检查频率受缓存间隔限制。
- 访问应用介绍页时，浏览器会加载 Google Fonts。该请求不由后端发起，也不经过 PixivDownloader 的出站代理。
- `plugin-catalog.enabled` 默认为 `true`，内嵌官方仓库也默认启用；启动本身不拉取清单，管理员打开或刷新插件市场、执行安装时才会访问仓库。
- 两个官方 PostHog 调查的四个参数分别由发布调查的插件持有，但源码 / fork 构建的发行激活位默认为 `false`，默认不会连接 PostHog。
- Pixiv、Douyin、AI、TTS、推送和邮件请求可能包含用户内容或访问凭据，具体范围见后续各节。

## 核心功能及默认网络请求

| 请求所有者 | 目标地址 | 用途与主要发送内容 | 触发场景与默认状态 | 代理与关闭方式 |
| --- | --- | --- | --- | --- |
| 应用宿主 | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json` 与相邻的 `update.json.sig`；nightly 使用 `/releases/download/nightly/` 下的同名文件；重定向后可能进入 GitHub Release 资产 CDN | 获取正式版或 nightly 更新清单及 Ed25519 detached 签名；只发送 User-Agent、IP 等标准连接元数据，不发送当前版本、平台、Pixiv Cookie 或其它凭据。响应分别受 1 MiB / 16 KiB 上限约束，清单会在解析前使用应用内置官方公钥验签 | 应用就绪后自动检查；`update.enabled=true`、`update.auto-check=true` 时启用，默认均开启；手动检查也会访问。启用每夜版检查后，即使正式版清单下载或验签失败，仍独立请求并验证每夜版清单；正式版失败信息保留，未通过验证的渠道不能下载 | 使用宿主出站代理配置；仅允许 HTTPS 和默认公网地址，最多跟随五跳重定向且每一跳都重新校验。可关闭在线更新或自动检查；自定义 manifest 必须是持有有效官方签名的公网 HTTPS 镜像 |
| 应用宿主 | 已验签更新清单中当前平台对应的安装包 URL，默认来自 GitHub Release | 下载更新安装包，并强制匹配签名清单中的 SHA-256 和精确大小；请求不携带 Cookie 或其它凭据 | 检查到更新且明确启动下载和安装后触发；更新检查本身不会自动安装 | 目标由已验签清单决定，仍只允许 HTTPS 和默认公网地址，最多跟随五跳重定向且每一跳都重新校验，总响应不超过 500 MiB；关闭在线更新可完全停用该链路 |
| 应用宿主的介绍页 | `https://fonts.googleapis.com/css2?...`、`https://fonts.gstatic.com/...` | 获取 Noto Sans SC 样式和字体文件；浏览器会正常暴露 IP 地址、User-Agent 等连接元数据 | 访问介绍页时由浏览器触发 | 不经过宿主代理；域名被阻止时使用后备字体，下载功能不受影响 |
| 应用宿主 | `https://www.pixiv.net/` | Pixiv 连通性探测，不携带 Pixiv Cookie | 首次配置或执行 Pixiv 连通性检查时触发，不是持续心跳 | 使用宿主的 Pixiv 出站路由；未执行探测时不发起该请求 |
| `notification` 插件 | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json`、相邻的 `index.json.sig` 与 `.../announcements/<message-id>/<locale>.html` | 读取公开公告索引及其 detached Ed25519 签名；索引在解析前使用应用内置官方信任根验签，并校验有效期、递增序列和每份正文的 SHA-256。仅为未知稳定 ID 下载已签名索引要求的各语言受控 HTML 正文；请求禁用 Cookie，只发送 IP、User-Agent 等标准连接元数据，不发送账号、作品、本地路径或其它凭据。验证或传输失败时保留既有可信快照。HTML 快照保存在本地，管理员浏览器只读取本地鉴权端点，不再直连外部正文 | 插件每次启动后随机等待 0–30 分钟，之后约每 6 小时并加入 ±15% 随机抖动检查。完整可信导入后保存 `ETag` 与 `Last-Modified`，仅在已签名索引有效期内发送条件请求；收到 `304 Not Modified` 时不再下载签名与正文。HTTP 429 按最长 24 小时的 `Retry-After` 延后，传输失败和 5xx 按 5 分钟、15 分钟、1 小时、6 小时逐级退避。只有首次发现未知 ID 或已签名正文摘要变化时才有界下载对应的各语言 HTML。同一 ID 已保存且元数据未变，或已显式删除时不再请求正文。官方默认插件集合包含 `notification`，启用并成功启动时会自动访问 | 使用宿主继承出站路由，可使用已启用的全局代理；禁用/卸载 `notification` 会停止检查，插件停止或重载时立即取消后续轮询 |

## Pixiv 下载与浏览

Pixiv 业务请求的 HTTP 传输由应用宿主提供，业务触发方则可能是下载工作台、小说插件或油猴脚本。下表按业务请求归属进行区分，不将共享传输层请求统一归类为核心请求。

| 请求所有者 | 目标地址 | 用途与主要发送内容 | 触发场景 | 代理与关闭方式 |
| --- | --- | --- | --- | --- |
| `download-workbench` 插件 | `https://www.pixiv.net/ajax/illust/**`、`/ajax/user/**`、`/ajax/search/artworks/**`、`/ajax/series/**`、`/ajax/collection/**`、`/ajax/follow_latest/illust`、`/rpc/index.php` | 获取插画、动图、画师、系列、搜索、收藏夹、关注动态和约稿等元数据；需要登录的请求会使用已配置的 Pixiv Cookie | 预览、快捷获取、提交下载、执行计划任务、回填作品信息或浏览相关取得模式时触发 | 经宿主提供给插件的 Pixiv HTTP 能力和所选代理路由；`download-workbench` 是必需插件（required），其缺失或不可用时主要下载功能不可用 |
| `download-workbench` 插件 | `https://www.pixiv.net/ajax/illusts/bookmarks/add` | 下载完成后收藏插画；发送作品 ID、收藏可见性和标签，并使用 Pixiv 登录凭据 | 仅在启用“下载后收藏”等相应选项且作品下载完成后触发；请求失败不会回滚已完成的下载 | 关闭下载后收藏功能可停用该请求 |
| `novel` 插件 | `https://www.pixiv.net/ajax/novel/**`、`/ajax/novel/series/**`、`/ajax/novel/series_content/**`、`/ajax/user/**/novels`、`/ajax/user/**/novels/bookmarks`、`/ajax/search/novels/**` | 获取小说正文、小说系列、作者小说列表、收藏和搜索结果；需要登录的请求会使用 Pixiv Cookie。下载工作台通过绑定当前 owner、作品与取得凭据的一次性短期票据复用预览时已验证的小说响应，不会在提交下载时再次请求同一小说正文 | 预览小说、系列合订、小说搜索、画师小说和计划任务处理时触发；紧随预览的下载提交会复用响应，不带票据的兼容客户端直接提交仍由后端抓取，无效票据会被拒绝 | 经宿主 Pixiv HTTP 能力；禁用 `novel` 会撤回小说页面和相关能力 |
| `novel` 插件 | `https://www.pixiv.net/ajax/novels/bookmarks/add` | 下载完成后收藏小说 | 仅在启用相应选项且小说下载完成后触发 | 关闭下载后收藏功能即可停用 |
| 应用宿主共享图片传输，调用方主要为 `download-workbench`、`novel` | HTTPS `*.pximg.net`；缩略图还允许 `embed.pixiv.net` | 下载 Pixiv 原图、封面、缩略图、动图压缩包和小说内嵌图片；通常发送 Pixiv Referer，图片地址来自 Pixiv API 响应 | 下载作品、生成封面/缩略图、浏览预览或生成小说文件时触发 | 使用 Pixiv 图片下载路由；停止相关下载或预览即可避免 |
| 根目录油猴脚本，不属于插件 | `https://www.pixiv.net/ajax/**`、HTTPS `*.pximg.net` | 在 Pixiv 网页中直接读取作品/小说信息、收藏作品或下载文件；`GM_xmlhttpRequest` 会使用浏览器/Pixiv 登录态 | 用户在 Pixiv 页面点击脚本功能、抓取页面或执行本地下载时触发 | 由浏览器/脚本管理器直连，不经过宿主代理；禁用或卸载相应脚本即可停用 |
| 根目录油猴脚本，不属于插件 | 管理员配置的 PixivDownloader 地址，默认 `http://localhost:6999` | 调用下载提交、队列、状态和 SSE 等后端 API；发送所选作品和下载参数。本机单人模式下载小说时，还会把脚本从 Pixiv 取得的有界小说响应发送给本机小说插件，换取短期一次性票据；不发送 Pixiv Cookie | 使用 Java 后端版、批量脚本或工具箱功能时触发 | 默认仅连接本机；配置为远端地址后，请求将发送至相应远端服务器，但小说响应导入会关闭并改由后端自行抓取 |

后端对普通 Pixiv JSON 使用 4 MiB 响应预算，对小说系列详情与分页内容响应使用 1 MiB 预算；存在 `Content-Length` 时会先校验声明长度，并始终按实际流读取量执行上限，超限时中止处理并返回受控错误。小说元数据最多接受 512 个内嵌图片映射；短期票据只保留移除正文和内嵌图片表后的原始元数据，且不超过 256 KiB。

## `douyin` 插件

`douyin` 是按需安装插件，默认 Windows 安装包和 Java 标准包不预置。只有安装、启用并使用 Douyin 功能后才会产生下列请求。

| 目标地址 | 用途与主要发送内容 | 触发场景 | 代理与关闭方式 |
| --- | --- | --- | --- |
| `https://www.douyin.com/aweme/v1/web/**` | 获取用户作品、喜欢列表、合集、搜索、音乐作品、收藏夹、账号信息和作品详情；会发送 Douyin Cookie、查询参数及模拟浏览器所需请求头 | 手动取得、预览、下载、计划任务发现、Cookie 探活和 Douyin 画廊刷新时触发 | 使用任务级、来源默认或宿主全局路由；禁用/卸载 `douyin` 即完全停用 |
| HTTPS `douyin.com` / `*.douyin.com`、`iesdouyin.com` / `*.iesdouyin.com`，常见为 `v.douyin.com` | 解析用户粘贴的短链接，最多跟随受限跳转 | 输入 Douyin 短链接并开始解析时触发 | 与该次 Douyin 任务使用相同路由 |
| HTTPS `*.douyinvod.com`、`*.douyinpic.com`、`*.douyinstatic.com`、`*.amemv.com`、`*.byteimg.com`、`*.bytedance.com`、`*.bytecdn.cn`、`*.pstatp.com`、`*.snssdk.com`，以及上述 Douyin 域 | 下载视频、图片、封面和实况照片媒体；具体 URL 来自 Douyin API 响应并可能发生受限重定向 | 执行 Douyin 媒体下载时触发 | 与该次 Douyin 任务使用相同路由；停止任务或禁用插件即可终止后续请求 |

## `ai` 插件

AI 插件使用 OpenAI 兼容协议。连接测试、翻译或其他 AI 功能会向所选基础地址的 `/chat/completions` 发送待处理文本、特定用途提示词、模型名称和 API Key。在桌面配置中主动点击“获取可用模型”时，插件会使用当前表单中尚未保存的基础地址、API Key 和代理选项请求同一基础地址下的 `/models`；该请求不发送待处理文本或提示词，返回的模型 ID 和所有者只会以有界纯文本摘要显示在本地。安装插件或仅打开配置不会发起这些请求。

| 预设 | 默认基础地址 |
| --- | --- |
| OpenAI | `https://api.openai.com/v1` |
| Anthropic 兼容入口 | `https://api.anthropic.com/v1` |
| Gemini OpenAI 兼容入口 | `https://generativelanguage.googleapis.com/v1beta/openai` |
| xAI | `https://api.x.ai/v1` |
| Mistral | `https://api.mistral.ai/v1` |
| Groq | `https://api.groq.com/openai/v1` |
| DeepSeek | `https://api.deepseek.com` |
| 阿里云百炼/Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` |
| Moonshot | `https://api.moonshot.cn/v1` |
| 豆包/火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` |
| 腾讯混元 / TokenHub | `https://tokenhub.tencentmaas.com/v1` |
| 百度千帆/ERNIE | `https://qianfan.baidubce.com/v2` |
| 讯飞星火 | `https://spark-api-open.xf-yun.com/v1` |
| MiniMax | `https://api.minimax.cn/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| SiliconFlow | `https://api.siliconflow.cn/v1` |
| Ollama | `http://localhost:11434/v1` |
| LM Studio | `http://localhost:1234/v1` |

主动选择预设时才会回填建议模型和基础地址；已保存的配置不会自动迁移。腾讯混元预设使用 TokenHub，需要在该平台创建 API Key 并开通模型，旧混元平台的密钥不能沿用。旧配置仍使用原地址，迁移时请重新选择预设、填写新密钥并保存。

AI 基础地址可配置为其他兼容服务，因此完整目标范围取决于实际配置。自定义目标会收到相应请求参数和 API Key，也可能访问本机或内网；聊天请求还会收到待处理文本。只应配置为管理员信任的本地、自建或第三方服务。包括模型列表请求在内，携带凭据的 AI 请求不保存 Cookie，也不跟随 HTTP 重定向，避免把凭据转发到跳转目标；直连或代理同样由当前 AI 配置决定。删除 API Key、停用或清空配置，或者禁用 `ai` 插件，可停止相关请求。

## `tts` 插件

TTS 请求会把需要朗读的文本、音色/模型参数和相应服务凭据发送到所选语音服务。只有试听、生成朗读、刷新音色列表、连接测试或实际小说朗读时才触发。

| 引擎 | 目标地址与用途 | 特殊触发说明 |
| --- | --- | --- |
| Edge TTS | `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1` 合成语音；同主机 `/consumer/speech/synthesize/readaloud/voices/list` 获取音色 | 合成、试听或刷新音色时触发 |
| Edge TTS 版本探测 | `https://edgeupdates.microsoft.com/api/products?view=enterprise` | Edge TTS 需要刷新客户端版本信息时触发，不是独立遥测 |
| 小米 MiMo | `https://api.xiaomimimo.com/v1/chat/completions` | 使用 MiMo 引擎合成时触发 |
| Fish Audio | `https://api.fish.audio/v1/tts` | 使用 Fish 引擎合成时触发 |
| MiniMax | 默认 `https://api.minimax.io/v1/t2a_v2`；国内站可配置为 `https://api.minimaxi.chat/v1/t2a_v2` | 使用 MiniMax 引擎合成时触发 |
| ElevenLabs | `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}` | 使用 ElevenLabs 引擎合成时触发 |
| 阿里云百炼/Qwen | 默认 `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`；国际区可配置为 `https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation` | 生成接口返回临时音频 URL 后，插件还会直接下载该 URL；临时 URL 的主机由服务端决定 |
| 豆包/Seed-TTS | `https://openspeech.bytedance.com/api/v1/tts` | 使用豆包引擎合成时触发 |
| VoxCPM、CosyVoice | 管理员配置的自建 OpenAI 兼容地址，通常为 `{base-url}/audio/speech`；VoxCPM 还会访问 `{base-url}/models` | 默认基础地址为空；未配置时不发起请求 |

具体引擎可配置独立基础地址；自定义目标会收到朗读文本、模型参数和服务凭据，也可能访问本机或内网，只应使用管理员信任的服务。携带凭据的 HTTP 请求不保存 Cookie，也不跟随重定向；不含调用凭据的 Edge 版本元数据探测继续使用普通客户端。代理行为取决于出站路由和引擎配置。禁用 `tts`、停用对应引擎或清空其配置即可停止请求。

## `push` 插件

Push 插件仅在通知通道启用后，由通知事件或“发送测试消息”操作触发。请求包含通知标题、正文及相应通道凭据；部分服务将 Token 或 Key 置于 URL 中。

| 通道 | 固定或默认目标 |
| --- | --- |
| Bark | `https://api.day.app/push`；服务器地址可改为自建 Bark |
| 钉钉机器人 | `https://oapi.dingtalk.com/robot/send?access_token=...` |
| 飞书机器人 | `https://open.feishu.cn/open-apis/bot/v2/hook/{key}` |
| 企业微信机器人 | `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...` |
| Telegram Bot | `https://api.telegram.org/bot{token}/sendMessage` |
| PushPlus | `https://www.pushplus.plus/send` |
| Server 酱 Turbo | `https://sctapi.ftqq.com/{key}.send` |
| Server 酱³ | `https://{uid}.push.ft07.com/send/{key}.send` |
| 自定义 Webhook | 管理员配置的任意 `http://` 或 `https://` URL |

关闭通道、删除凭据或禁用 `push` 插件可停止请求。自定义目标会收到通知正文与通道凭据，也可能访问本机或内网；`http://` 还会明文传输这些数据，只应使用管理员信任的目标。推送请求不保存 Cookie，也不跟随 HTTP 重定向，避免把凭据转发到跳转目标。

## `mail` 插件

Mail 插件通过 SMTP 发送配置测试邮件和业务通知。连接会携带 SMTP 用户名/密码，并把收件人、主题和邮件正文交给所选邮件服务。只有测试发送或通知实际投递时才连接。

| 预设服务 | 默认 SMTP 目标 |
| --- | --- |
| 网易 | `smtp.163.com:465`、`smtp.126.com:465`、`smtp.yeah.net:465`、`smtp.qiye.163.com:465` |
| 腾讯 | `smtp.qq.com:465`、`smtp.exmail.qq.com:465` |
| 新浪 | `smtp.sina.com:465` |
| Gmail / Google Workspace | `smtp.gmail.com:587` |
| Outlook | `smtp-mail.outlook.com:587` |
| Microsoft 365 | `smtp.office365.com:587` |
| iCloud | `smtp.mail.me.com:587` |
| Yahoo | `smtp.mail.yahoo.com:465` |
| 阿里企业邮 | `smtp.qiye.aliyun.com:465` |

管理员可指定任意 SMTP 主机和端口，也可配置独立的 SOCKS 代理地址。关闭邮件通知、删除邮件配置或禁用 `mail` 插件可停止相关连接。

## 插件市场、FFmpeg 与脚本更新

| 请求所有者 | 目标地址 | 用途 | 触发场景与默认状态 |
| --- | --- | --- | --- |
| 应用宿主的插件市场 | 正式版使用 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`，每夜构建版使用同仓库的 `nightly-manifest.json`；包地址通常为 GitHub Release，并可能重定向到 `*.githubusercontent.com` | 获取与当前应用发布通道匹配的官方插件清单、下载用户选择的插件包并做签名、SHA-256 和大小校验 | 清单由打包进应用的版本自动选择；`plugin-catalog.enabled` 与内嵌官方仓库默认启用，管理员打开或刷新市场时拉取清单，明确安装插件时下载包；应用启动本身不访问仓库；最多跟随五跳重定向且每一跳都重新校验，关闭主开关可完全停用该链路 |
| 应用宿主的插件市场 | 管理员输入的公网 HTTPS `repository.json` | 预览仓库声明的发布者、目录、撤销/更新证明端点、实际联网主机和完整公钥指纹；请求不携带 Cookie、账号、作品、本地路径或其它应用凭据，也不请求 `repository.json.sig` | 仅在管理员提交预览或确认信任时触发；确认会重新获取并要求描述符 SHA-256 不变。响应最大 64 KiB，所有地址都执行公网 HTTPS 与 SSRF 校验；`DIRECT_STRICT` 不跟随重定向，`GITHUB_RELEASES` 只允许 GitHub 固定主机边界内一跳。取消预览或不确认不会保存/启用仓库 |
| 应用宿主的插件市场 | 已信任描述符中的 HTTPS catalog endpoint、可选 `revocations.json` / `repository-update.json` 及相邻 `.sig`，以及目录指定的插件 JAR/ZIP URL | `manifest-v1` 读取清单和清单签名；`paged-v2` 分页读取列表、详情和指定版本；每次列表、详情和版本事实查询刷新必选撤销文档及签名，同一响应使用同一快照；安装在解析依赖前及下载完成后再次核对。隐藏或撤销的版本不可新装，仍可查看历史诊断；无有效快照或超过 24 小时宽限期时禁止新安装，刷新失败不会清除已知限制。包下载后还校验大小、SHA-256、发布者签名和包内 descriptor。重新导入已信任仓库时可能读取连续性证明 | 浏览/搜索/翻页、查看详情、明确安装/更新或重新导入时触发；启动只读取本地最后有效撤销快照，不自动请求第三方仓库。使用描述符映射后的固定安全网络档位；禁用/删除仓库或关闭 `plugin-catalog.enabled` 可停用后续请求 |
| 应用宿主的社区目录与插件市场 | `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/` 下的 `generated/current.json`、`generated/generations/<sequence>/directory.json` 及按需 shard、`generated/repository.json`、清单与撤销文件；审核、投稿和历史发布者记录来自该仓库中的受控相对路径，包地址来自已验签清单 | 独立社区根验证目录与包签名，核对目录序号、描述符与 key 指纹；所选版本详情最多读取一份审核记录，安装还验证原发布者签名与实际包内声明。请求只包含公开路径及标准连接元数据，不携带 Cookie、私钥或应用凭据 | 社区仓库默认启用，管理员浏览社区来源、查看版本、安装或预览/确认第三方仓库认证时触发；启动只做本地复验，不自动安装。沿用宿主代理和 `GITHUB_RELEASES` 的公网 HTTPS、GitHub 主机与一跳重定向边界。关闭 `plugin-catalog.community-repository-enabled` 停止社区市场请求；第三方仓库认证查询随 `plugin-catalog.enabled` 主开关关闭。目录失败保留已验证的最近一个桶，损坏状态拒绝重置防回滚水位 |
| 应用宿主 FFmpeg 安装器 | `https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/ffmpeg-release.json`、相邻的 `ffmpeg-release.json.sig`、当前系统与架构对应的 `ffmpeg-{windows-x64,linux-x64,linux-arm64,macos-x64,macos-arm64}.zip`，以及 GitHub Release CDN 重定向 | 先使用应用内置官方信任根验证 Ed25519 清单签名，再按清单中的精确资产名、大小和 SHA-256 验证 FFmpeg 官方稳定源码构建，全部通过后才解压。GET 请求只发送 FFmpeg 安装器 User-Agent、IP 等标准连接元数据，不发送 Pixiv Cookie、账号或其它应用凭据 | 仅在 GUI 中明确选择自动安装 FFmpeg 时触发；应用启动本身不会下载；不支持的系统继续使用手动安装。请求沿用宿主代理设置；不执行自动安装即可停用该链路 |
| Windows Setup 的 FFmpeg 可选任务 | 与上一行相同的签名清单、detached signature 和固定的 `ffmpeg-windows-x64.zip`，以及 GitHub Release CDN 重定向 | 在安装期使用安装包内置的官方信任根验证清单签名和 Windows 资产的精确名称、大小、SHA-256，通过后才解压到应用工具目录。目标站点请求不携带应用账号或 Pixiv 凭据；使用系统代理时，代理连接可以使用当前 Windows 用户的默认代理凭据 | 只有用户在 Setup 中明确勾选 FFmpeg 可选任务才触发。Setup 优先使用已启用的系统 HTTP/HTTPS 代理，未找到时直连；不选择该任务即可完全跳过 |
| 油猴脚本管理器，不属于插件 | `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js` | 检查和下载六个独立油猴脚本更新 | 由 Tampermonkey 等脚本管理器按其更新策略触发；禁用脚本自动更新或卸载脚本即可停止 |
| All-in-One 油猴脚本管理器，不属于插件 | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/Pixiv%20All-in-One.user.js` | 检查或下载构建生成的合并脚本 | 仅安装该发行脚本后由脚本管理器触发 |

## 官方插件的可选调查（PostHog）

布局反馈调查属于 `download-workbench` 插件；多人模式保留意愿调查属于 `multi-mode-decision-survey` 插件，并且只在管理员站内信中显示。独立的 `posthog` 插件提供 PostHog JavaScript SDK 和调用方配置的隔离客户端。SDK 已随插件静态资源打包，不会从 CDN 加载。

- 每个发布调查的插件都固定持有自己的 Project Token、Survey ID、`apiHost=https://layout-survey.sywyar.top` 与 `uiHost=https://us.posthog.com`；它们是浏览器可见参数，不是 Secret，也不通过 GitHub Actions、脚本或 properties 文件注入。
- 普通源码 / fork 构建生成的发行激活位为 `false`；官方 Release、Nightly 与官方插件发布使用仓库内的 Maven `official-surveys` profile 把这一位设为 `true`。四个参数不随 profile 改写。
- 启动时的调查站内信注册与幂等写入只读取本地插件声明，不访问 PostHog。用户打开含有效调查的站内信页面（页面会预热内嵌调查），或下载工作台预加载 / 触发布局调查流程时，浏览器才会直接访问上述 PostHog API/UI 主机；发送范围仍受调查发布插件的 `beforeSend` 允许列表约束，且不经过宿主出站代理。
- 多人模式保留意愿调查会发送用户选择或填写的答案、调查标识、调查专用匿名标识、用于投递去重的稳定事件标识、完成状态、事件时间、事件名和公开项目令牌。布局调查会发送问卷回答、调查标识、调查专用匿名标识、用于投递去重的稳定事件标识、应用版本、当前布局、调查结构版本、事件时间、事件名和公开项目令牌。两者的提交身份都由安装身份单向派生并限定在当前调查与 campaign 内，不发送原始安装身份、账号、Cookie、作品或本地路径。
- 调查站内信会在发布插件持续提供时保留；发布插件停止提供后由本地生命周期同步撤下。内嵌页确认 Survey 已从 PostHog 发布列表删除 / 关闭后会留下本地关闭标记，不再显示该站内信；临时网络错误不会误删，之后打开时会重新验证。
- `posthog` 插件缺失或停用时调查静默关闭。已打开页面中的脚本不会被热撤销，停用后刷新页面才完全生效。

## 不包含固定公网目标的官方插件

以下插件本身不增加固定第三方网络目标：

- `stats`：读取本地数据库并生成统计。
- `duplicate`：读取本地文件和 Hash 数据进行重复检测。
- `gallery`：浏览本地下载记录和本地媒体。
- `gui-swing` 与 `gui-compose`：渲染应用的本地桌面文档，提供窗口、托盘、主题和平台资源，不增加固定第三方运行时目标。
- `recovery-sentinel`：仅用于恢复模式验证，不包含在常规用户发行包中。

它们的页面仍会调用当前 PixivDownloader 实例的同源 API，但这不是访问第三方公网。

## 本机、同源及管理员配置的目标地址

- GUI、Web 页面和插件前端会访问当前 PixivDownloader 实例的 `/api/**`、静态资源和 SSE。桌面 GUI 默认连接 `http://localhost:{port}` 或 `https://localhost:{port}`。
- Ollama、LM Studio、VoxCPM、CosyVoice 和油猴脚本后端可配置为本机服务；基础地址指向远端后，该远端即成为新的数据接收方。
- 图片分类器的 `server.url` 默认为 `http://localhost:6999`，也可指向管理员配置的其他 PixivDownloader 实例。
- 自定义 Webhook、AI/TTS 基础地址、插件仓库、Bark、SMTP、SOCKS 和代理端点都由管理员配置，无法形成封闭的固定域名白名单。更新清单 URL 也可由管理员配置，但只接受带有效官方签名的公网 HTTPS 目标。
- 根目录 `cors-js-runner.html` 是开发调试工具，会请求操作者输入的任意 URL；它不属于常规用户运行链路。

## 代理适用范围

“配置了宿主代理”不表示所有网络流量都会经过它：

- Pixiv、更新、FFmpeg、官方插件仓库和部分插件请求会按照宿主或任务级路由选择代理。
- AI、TTS 和 Douyin 可以有功能自身或任务级的直连/代理选择。
- 自定义插件仓库的直连严格策略会明确绕过全局代理；自定义策略以仓库配置为准。
- 油猴脚本、Google Fonts 和 PostHog 是浏览器直接发出的请求，不经过 Java 后端代理。
- SMTP 使用 Mail 插件自己的连接设置，可另配 SOCKS 代理。
- 如果启用 HTTP/SOCKS 代理，程序首先连接管理员配置的代理主机和端口，再由代理访问最终服务。

## 开发、构建和发布流程的网络访问

以下目标不属于已安装应用的常规运行时请求：

社区 Node 工具下载公开插件包、源码和构建工具时，先匹配 `no_proxy` / `NO_PROXY` 的直连规则，再依次读取 `https_proxy`、`HTTPS_PROXY`、`all_proxy`、`ALL_PROXY`；小写优先。未设置代理变量时，Windows 读取当前用户的系统代理及绕过设置，其它系统直连。显式空代理值或 `NO_PROXY=*` 可选择直连；支持 HTTP / HTTPS 代理，不支持 SOCKS。代理连接失败会停止下载，不自动改走直连。CONNECT 目标仍固定为本次核对的公网 IP，源站 TLS 验证原始主机；显式代理用户名与口令只用于代理认证，不发送到源站，GitHub 凭据和 Cookie 不进入文件下载。系统代理查询在原有六十秒下载期限内完成。以下各行的独立构建容器、GitHub CLI、Git 和 PowerShell 启动器继续使用各自的网络设置。

社区 Node 下载器对连接中断、DNS 与连接失败、超时及 HTTP 408/500/502/503/504 最多尝试三次，退避一秒、两秒；所有尝试、系统代理查询、重定向和响应体共用六十秒期限。重试会重新核对每跳公网地址、源站 TLS、文件大小与摘要，并清理本次创建的半文件。证书、摘要、大小、URL、本地文件错误及其它 HTTP 状态不重试。初次下载、验证投稿内容和最终复核共用此规则。临时故障耗尽自动重试后，向导显示失败子步骤、轮次及累计次数；手动继续只为该请求或文件开启新一轮预算，保留已完成的步骤，也可保存已有进度后退出。PowerShell 启动器同样按文件累计轮次与尝试次数。

向导在准备 SDK、读取社区数据和询问密钥前，通过 GitHub CLI 的 `GET /user` 核验实际登录账号。未登录或凭据失效会显示登录指引，可在另一终端完成登录后重新检查，也可保存退出；环境变量中的凭据变更需要重启向导。权限不足、对象不存在或不可见、限流、代理、DNS、TLS 和连接中断分别显示说明。证书、请求参数、状态冲突及本地工具或文件错误不进入网络重试。诊断只显示受控错误码、状态码、退出码和发生环节，不输出原生命令、令牌或带参数地址。Git 提交身份和远端认证单独核对，GitHub 登录不能代替 Git 凭据配置。

社区投稿 API 读取与候选二进制下载通过 GitHub CLI 访问。GET 的 HTTP 408/500/502/503/504，以及已识别的临时连接、DNS、超时和 EOF 故障，最多尝试三次，退避一秒、两秒，共用六十秒期限；权限、限流、证书及原因不明的传输失败不自动重放。可恢复错误允许仅重试该请求或保存退出，恢复初始化也适用。创建 fork、候选归档、上传附件、发布候选、Git 传输、创建 PR 与取消请求在各自步骤内恢复：写入重试前回读同一对象并核对身份和内容，可恢复故障每轮最多尝试三次，HTTP 客户端不盲目重放写入，已完成的步骤保留。等待源码 CI 时断线，只继续读取已确认的同一次运行，不重新触发 CI 或延长原等待期限。恢复本地投稿记录仍会重新访问 GitHub 并复核源码和签名。社区主线更新时保留已准备的内容，读取开放 PR 及原始 Git 文件，核对作者、目标仓库和全部投稿字节后复用已有请求；没有匹配请求时按最新社区状态重新验证，提交前再次确认。记录本身不授权远端写入。这些请求继续使用 GitHub CLI 和 Git 各自的认证、代理与 TLS 设置。

完成审核和已签名状态操作向原 PR 追加提交后，会通过 GitHub CLI 回读 PR、原生 Git ref 和社区主线，最多五轮，轮间等待一秒，每次 API 仍限六十秒。仅分支已更新而 PR 视图仍显示原父提交时等待；其它变化立即阻断。写入响应丢失也先核对原生 ref，不重复推送。Release 发布和 PR 通知分为独立步骤，通知使用具备 `pull-requests: write` 的 job token；通知失败时保留已发布产物，恢复时先回读已有结果。主线前进后，通知核对原运行、源码祖先关系及受保护代码未变，并回读 PR head 和状态；空通知不发起请求。签名与状态写入仍绑定当前主线。

所有权转移由接收方创建一个申请 PR。原所有者运行向导时通过仓库 `/issues` API 查询开放 PR，默认同时携带 `labels=type:ownership-transfer` 和 `mentioned=<当前账号 login>`。用户可分别取消或同时取消筛选；只展开命中 PR 的固定 head 请求文件，并重新核对当前绑定与个人身份或组织成员资格。标签和提及仅用于检索，不授予处理权限；筛选参数包含公开标签名和当前账号 login，不包含密钥或密码。确认后用自己的 GitHub CLI 凭据向该 PR 的 `/reviews` API 提交同意或拒绝。Review 使用英文正文，隐藏标记包含决定类型、请求摘要，以及用户选择提供的活动密钥签名；私钥和密码不上传，也不需要访问接收方源码仓库。Review 确认后，向导继续用当前 GitHub CLI 凭据向 `/issues/<PR编号>/comments` 写入一条带 Review 链接和通知标记的评论。写入失败先回读相同 Review 或评论，避免重复提交。该评论通过默认分支的 `issue_comment` 事件唤醒受保护流程，无需批准 fork 上的事件工作流；通知本身不授予权限，普通评论和机器人评论不进入处理。原生 Review 事件仍可唤醒同一流程；不同个人账号的双方签名均有效时，在原 PR 追加结果并按保护规则合并。Gate 和最终合并重新读取原生身份、Review、当前密钥及紧急状态；缺少证明、组织和恢复请求仍走人工审核。通知作业通过数字账号查询当前 login，在中文和英文两条请求信息评论中提醒原个人所有者；明确拒绝时，重新核对当前绑定、请求与决定，再关闭原 PR。通知使用既有 `pull-requests: write` job token，自动追加和合并使用既有受保护维护者凭据。所有请求沿用 GitHub CLI 的代理、认证及六十秒和 32 MiB 预算；不运行向导或停用相关 workflow 即可停止相应请求。

| 工具/流程 | 目标或来源 | 用途 |
| --- | --- | --- |
| 投稿请求分支清理 | `api.github.com` 的用户、仓库、PR、branch 与分支 Rules API；当前账号 fork 或社区所有者本仓库的 Git HTTPS 地址 | 向导关闭本人请求后读取分支身份、保护规则及关联开放 PR。用户另行确认删除后，Git 以精确旧 SHA 的 lease 删除该请求 ref；主分支、整个 fork 和 Release 保留。响应丢失只回读，重试删除须再次确认。使用当前 GitHub CLI / Git 认证与各自代理设置，发送目标仓库、分支和 SHA；拒绝清理即可避免删除请求 |
| 社区审核结果写回 fork | 匿名 Git HTTPS：`https://github.com/<已核验仓库>/`；SSH：`github.com:22`，目标为 `git@github.com:<投稿 fork>.git` | 自动申请、人工审核完成和紧急声明通过 `review-branch.mjs` 拉取固定父提交并构造生成结果，再以维护者账户 SSH 密钥快进推送原 PR 分支；同仓分支仍由 job token 通过 GitHub API 写入。只有通过身份、请求和维护者编辑许可检查才推送；不检出或运行投稿代码。SSH 发送 Git 对象及认证签名，不发送私钥原文；API 合并单独使用只授权本社区仓库的所有者 PAT。专用 SSH 配置固定 GitHub Ed25519 主机公钥、指定私钥及十五秒连接超时，禁用 agent 和交互认证；每个 Git 进程沿用六十秒、32 MiB 输出预算。HTTPS 读取可使用 runner 的 HTTP 代理环境；SSH 直连端口 22，不继承 HTTP 代理或用户 SSH 配置，也不自动改用其它传输。禁用相应 workflow 停止写回；移除 SSH Secret 将明确阻断 fork 写入 |
| 已签名管理请求自动执行 | 社区仓库的 GitHub PR、用户、环境规则、Git 对象、Release/asset、check-runs、Actions dispatch API，以及官方 Action 的证明与工具下载服务 | `Apply signed owner request` 由静态检查完成唤醒，以受保护 `master` 工具核验个人管理者的版本状态请求，或带新旧密钥证明的例行轮换。远端独立核对请求签名、当前登记状态和 PR 作者；本地密钥配对结果不作为远端授权。组织的状态变更和换钥、恢复操作及密钥丢失或泄露后的换钥仍需人工审核。专用 `community-status` Environment 的社区根仅传入签名步骤；维护者 SSH 密钥用于快进追加 fork 提交，限定社区仓库 Contents 写权限的所有者 PAT 仅用于合并，job token 用于其它 API 与显式唤醒 Gate / 发布收尾。最终写入作业直接复用 Gate 签发，并最多等待五秒回读原生检查；合并绑定精确 head 并保留保护规则及人工否决；不重新构建插件；合并后的分支清理由下述仓库设置交给 GitHub 执行。请求包含操作、PR、SHA、签名结果及状态，不发送私钥。代理和下载目标由 GitHub CLI、官方 Action 与 runner 设置决定。禁用该 workflow 可停止自动执行，人工流程仍可使用 |
| 社区密钥泄露声明 | 社区与投稿 fork 的 Git tree/blob/commit/ref、分支保护、PR、评论、check-runs 和 Actions dispatch API；GitHub 用户、组织及 `/orgs/<org>/memberships/<login>`；官方 Action 工具下载服务 | 作者在向导中选择已登记公钥，经确认创建面向受保护 `emergency-state` 的请求。`Community emergency` 只运行受保护 master 代码；按数字账号核对本人，组织声明还要求可验证的管理员角色。工作流以 job token 读写社区对象，以维护者 SSH 密钥追加 fork 提交，以所有者 PAT 普通合并，以 Gate App 写入检查。分支只保存声明与公钥指纹，不保存私钥；合并后拒绝相关密钥的新操作，既有发布不变。处置成功后，独立通知步骤以 job token 同步更新该 PR 的中文和英文两条请求信息评论，展示账号、keyId 与公钥指纹；评论失败不重复处置。紧急事件会枚举开放的普通请求，撤回受影响的旧成功并重触发检查；master 汇总不枚举开放 PR。作业十五分钟，与普通操作的最终授权和发布共用队列；声明无自动到期或撤销。组织权限不可读时拒绝自动执行。CLI 和 runner 决定代理，API 沿用六十秒预算。停用工作流停止新声明，已生效封禁仍被普通流程读取 |
| 社区可信作业间数据交接 | 同一 Actions run 的 artifact 上传、下载及 GitHub 存储服务 | Gate 的候选校验，以及普通请求的准备、签名和人工批准，在最终授权队列外执行。后续作业只按同一 run 输出的不可变 artifact ID 下载数据，并重新核对源码、run/attempt、原始字节与当前授权；交接失败不会保留旧成功检查。artifact 保留一天，Gate 的编码后交接数据最多 32 MiB，不含私钥；官方 Action 使用临时 job token 和 runner 网络配置。停用对应 workflow 会停止交接 |
| 投稿教程的 `irm ... \| iex` | `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/tools/submit.ps1` | 用户在本地目录手动执行教程命令，发布插件时须进入 SDK 工程，PowerShell 原生 `Invoke-RestMethod` 下载受保护 `master` 当前的 UTF-8 入口，再交 `Invoke-Expression` 执行。入口代码及内置公钥以该主线和 GitHub HTTPS 为信任来源。该次下载遵循 PowerShell 的网络、代理、重定向和超时设置，教程不传入 token、私钥或请求正文；入口执行后检查启动目录并进入下列固定工具下载路径。验签后的向导检查工程标识，缺失时仅提供管理操作。不运行命令即可避免访问 |
| 社区投稿 PowerShell 入口 | 固定渠道 `https://raw.githubusercontent.com/Sywyar/PixivDownloader-community-plugins/master/tools/submission-channel.json`，以及验签通过的 `<完整commit>/` 下工具清单和文件 | 用户手动运行入口，检查启动目录后，每次匿名下载最多 4 KiB 的签名渠道数据。内置公钥核对签名、有效期和已接受序号，再按完整源码提交及 SHA-256 读取工具；本地或缓存文件通过大小和摘要核对后复用。内部下载遵循平台默认代理，在一次启动中复用连接，不携带 Cookie 或默认凭据，不接受重定向。每个文件一轮最多尝试三次，分别等待一秒、两秒；连接或读取连续十五秒无进展时提前重试，持续收到数据会重置空闲计时。可恢复的传输错误及 HTTP 408/500/502/503/504 也适用重试。每轮全部尝试、等待与响应体读取共用六十秒期限。证书、签名、摘要和大小校验失败不重试。入口显示文件进度；失败时报告文件、阶段、原因、实际尝试次数，并说明停止是因为总时间耗尽、次数耗尽或错误不可重试。可恢复故障耗尽本轮预算后，交互终端允许重新下载当前文件或退出，默认退出；手动继续才开启新一轮预算，已校验缓存保留。非交互运行直接返回失败。执行工具前再次检查渠道签名与有效期。状态和工具缓存在本地应用数据目录；失败停止，不静默运行旧缓存。不运行入口可避免访问。日常工具更新由签名渠道选择；协议或信任根变更通过维护 PR 更新 `master` 上的入口，用户下次执行同一命令取得更新 |
| 社区投稿工具清单签发 | `https://github.com/Sywyar/PixivDownloader-community-plugins` 的 Git 对象、GitHub Actions 工具分发和 artifact 服务 | 维护者手动运行 `Sign submission tool channel`，获取受保护源码并测试入口，经 `release` Environment 批准后签发指定主线祖先的工具清单。checkout 使用只读 job token，工具安装遵循对应 Action 的代理与下载规则；上传的 artifact 仅含签名清单，保留七天，使用当前 job 的 artifact 凭据。专用私钥仅传给签名进程，不写入产物，不执行目标工具，也不推送分支。维护者通过 PR 更新渠道；不运行 workflow 可避免这些请求 |
| 本地社区投稿向导 | `api.github.com` 的用户、组织、仓库、Git tree/blob/ref、commit、Actions run/attempt/jobs、Release/asset、fork 与 PR API；固定社区仓库或当前账号 fork 的 Git HTTPS 地址；固定提交的公开源码归档与候选包地址；`uploads.github.com` 的 Release asset 上传接口 | 用户主动启动。GitHub CLI 按当前登录核验数字身份、成功 CI、标签提交和附件，再通过资产 ID 和 CLI 认证读取源码仓库 Draft 的二进制附件。GitHub 返回的临时附件地址只在草稿阶段使用，预览与投稿固定为正式 tag 地址，公开后按该地址匿名核验原包。源码核验使用匿名下载。最终预览明确列出源码 Pre-release 发布、fork、push 和 Ready PR，确认并重新核验后执行。滚动草稿中的已核对包与元数据会复制到绑定源码 SHA 的固定 Pre-release，使用当前 GitHub CLI 身份上传；原草稿继续由 CI 覆盖，私钥只留本机。候选缺失时可单独确认重跑默认分支当前提交的 CI；取消最终投稿不撤销此前已确认的恢复。GitHub CLI、Git 和匿名下载器分别使用各自网络、代理与认证设置；发送元数据、公钥、签名和选定图片，不发送私钥或密码。密钥展示也读取受保护 emergency-state 分支，其中已生效的泄露声明优先于主线密钥状态；已声明项显示“声明泄露”，不再列入新的声明选项。最终校验重新读取紧急状态。不运行向导即可避免访问 |
| SDK 源码候选 CI | GitHub Actions 工具 / artifact 服务、源码仓库 Release/asset、Git ref 与 commit API；Docker Hub 固定摘要的 Temurin 镜像及 registry CDN；Ubuntu 包镜像；Maven / Gradle Wrapper 与 sbt 指定的公共制品源 | SDK 工程 push 或 PR 时，读权限 job 查询实际构建模型、构建测试并以工具离线模式重建；sbt launcher 来自 `repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/`，按工程固定版本和 SHA-256 获取。网络请求由 CI 工具、Wrapper 和工程依赖配置决定，使用 runner 代理，不继承投稿者本机代理。默认分支构建成功后，独立 job 用本仓库写 token 为每个插件复用一个 Draft Release：核对默认分支当前 SHA，先删除旧元数据和包，再上传新包，最后上传元数据。仓库内归档串行执行，迟到的旧提交不覆盖新候选，不修改已公开 Release 或移动 Git tag；该 job 不执行工程代码；附件随草稿保留，不依赖 Actions artifact 的七天期限。移除候选 workflow 可停止自动请求。社区正式断网重建仍独立执行；向导本地不再运行构建模型查询 |
| 社区投稿静态检查 | `api.github.com` 的社区仓库 PR、Release/asset、Git tree/blob、源码仓库 commit 与数字账号 API；投稿中的公开 HTTPS 安装包地址；固定 commit 的 GitHub 源码 ZIP 及其重定向；Actions artifact 服务 | PR 事件运行受保护默认分支的固定检查器，使用只读 token 获取身份和原始数据，不执行投稿代码或构建。包和源码下载不携带 GitHub 凭据或 Cookie，代理选择遵循本节的社区 Node 工具规则；每跳验证并固定公网 IP、保持原主机 TLS 验证，最多五次重定向、总计六十秒，并检查实际大小和 SHA-256。API 连接使用 GitHub CLI 配置。静态通过只允许继续进入版本构建；版本构建完成后的交接 artifact 保留七天，上传使用平台当前 job 的运行时凭据。维护者可停用此 workflow，静态结果本身不授予准入或发布权限 撤销基线由独立的受保护 `Prepare reviewed baseline` 作业读取：只为读取技术 Draft 申请 Contents write，核对版本请求和已审核记录，不执行投稿源码。原包交接到同次运行的只读构建作业，artifact 保留一天，消费时重验大小和摘要；准备作业十五分钟，下载沿用 SDK 归档上限和 GitHub CLI 的六十秒预算。 |
| 社区构建工具与容器准备 | Maven / sbt 的固定版本位于上述 Maven Central；Gradle 位于 `services.gradle.org/distributions/` 及其 GitHub/CDN 重定向；Node.js 位于 `nodejs.org/dist/`；Ubuntu OpenSSL 代理工具位于 `archive.ubuntu.com/ubuntu/pool/universe/s/squid/`；Docker Hub 的 `eclipse-temurin`、`ubuntu/squid` 镜像及其认证/CDN 服务 | 版本投稿或维护者请求重算时，在执行投稿代码前匿名下载工具并核对固定 SHA-256；单个构建工具最多 192 MiB、代理工具最多 32 MiB，总下载六十秒、最多五次重定向，每跳固定公网 IP。Docker 原生客户端按完整镜像摘要拉取，registry 认证、代理、缓存与重定向遵循 runner 配置，不能将其下载目标当作投稿容器白名单。发送公共工具路径、版本、镜像摘要及平台信息；不发送投稿源码或社区私钥。维护者通过受保护 SDK 锁和构建策略更新工具，停用构建 workflow 可避免访问 |
| 社区构建依赖预取 | 仅 `repo.maven.apache.org`、`repo1.maven.org`、`plugins.gradle.org`、`plugins-artifacts.gradle.org` 的 HTTPS 443 | Maven / Gradle / sbt 在无凭据容器内执行投稿构建配置，通过本次独立 Squid 代理请求批准主机上的制品路径。路径由实际工程及构建工具决定；代理终止客户端 TLS，逐次核对 CONNECT、TLS SNI 和 HTTP 主机，只转发批准主机的 HTTPS GET/HEAD，拒绝非公网地址并保持源站证书校验。构建容器只读本次临时 CA 证书与信任库，私钥仅挂给代理并在结束时移除，不修改系统信任库。容器没有外部 DNS、宿主网桥出口或共享可写缓存。正式重建使用新建的断网容器，不产生外部请求。默认随版本构建执行，单次预取与重建各限时三十分钟；更改批准源须修改受保护构建策略 |
| 社区代理网络回归 | `self-signed.badssl.com`；以 `www.cloudflare.com` 为非批准目标的伪装请求 | 仓库 CI 或本地显式网络测试使用独立代理配置，临时允许自签名证书测试站点以验证源站 TLS 失败会阻断；域名伪装请求必须在代理处拒绝。只发送无凭据的测试请求，不修改生产批准源或系统信任库；不运行网络测试可避免访问 |
| 社区候选归档与复用 | `api.github.com` 的同仓库 PR、run/attempt、job/log、artifact、Release/asset API；`uploads.github.com` 及 GitHub 返回的资产下载地址 | 静态检查成功后，只读预检查询该构建 attempt 是否产出候选 artifact；预检限时五分钟，不占用发布队列。有候选才进入受保护归档 job。它用本 job 的内置 token 重新核验原生身份、执行来源及原始字节，按发布者数字账号与类型、发布者 ID、插件 ID 和版本复用待审核 Draft Release，更新包、源码与证据，不执行投稿代码。替换先删除旧证明，再更新不同字节的资产、候选清单和新证明；另一开放或已合并请求不能被覆盖。旧 PR 格式草稿可就地迁移。未合并关闭申请 PR 默认触发后台清理，用仓库内置 Contents write token 查询当前 PR、草稿归属与资产，再删除仍属于该申请的候选 Release 及附件，不依赖向导选择。草稿已公开、被新申请复用或请求重新打开时保留；删除后重新投稿会建立新候选。清理不删除源码仓库 Release。归档、清理、完成审核与正式发布共用队列。自审、Gate 和预检用仅执行主线代码的 Contents write 作业读取 Draft，以 Release API 的实际响应判断可见草稿。未找到完整候选时，审核保持等待并提示检查归档和 Draft 访问；列表请求返回 HTTP 401 或 403 时阻断审核。构建作业仍只读，未找到草稿或明确被拒绝读取时执行隔离重建。人工审核等待和正式发布使用已归档原包，不重新构建。API / 下载使用 GitHub CLI，沿用其认证、代理及平台重定向；Actions artifact ZIP 请求接受 GitHub JSON 媒体类型以获取重定向，Release asset 请求接受原始二进制；单调用六十秒，归档和清理 job 各十五分钟。Draft 不进入公开目录。停用归档会阻断需要新归档的版本审核；关闭事件、定时及手动清理的范围见下行 |
| 社区草稿与技术归档清理 | 同仓库 Release/asset、PR 列表及文件、run、Git tree/blob/ref API；GitHub 资产 CDN 及上述归档证明服务 | `Community candidate cleanup` 响应未合并关闭事件，每日 04:23 UTC 扫描，并允许手动运行。使用内置 Contents write、Actions read、Pull requests read token，不使用签名私钥。候选按归属和关闭状态清理；历史 operation Draft 只有无开放请求引用、原运行完成且无用满九十天时才删除。已合并证据须先经 `migrate-receipts.mjs` 验证原签名及父链、迁入 Git，再按原长度和摘要重建验签；迁移只生成待提交文件，不修改远端。保留期从迁移提交、PR 关闭、运行及 Release 更新的最晚时间计算。删除前重读引用、主线、请求及资产；查询不完整时保留。API 沿用 GitHub CLI 的认证、代理和六十秒 / 32 MiB 预算，job 十五分钟并与发布共用队列。不删除正式 Release、源码 Release、tag 或仍被引用的撤销包。停用 workflow 会停止清理；平台停用定时时需维护者恢复或手动运行 |
| 社区归档来源证明 | GitHub Actions OIDC 服务、同仓库 attestation API；公共 Sigstore 的 `fulcio.sigstore.dev`、`rekor.sigstore.dev`；CLI 信任根服务 `tuf-repo.github.com`、`tuf-repo-cdn.sigstore.dev` | 受保护归档完成后，固定 `actions/attest` 使用当前 job 的 OIDC 身份为候选清单摘要生成证明，发送临时公钥、签名、摘要及公开 workflow 身份；仓库内置 token 仅供 GitHub API 使用，不使用社区签名私钥。客户端与官方服务决定证书、透明日志、信任元数据及重定向地址。后续 `gh attestation verify --bundle` 验证本地证明时清除 token / Secret 环境变量并使用空的独立 CLI 配置，匿名更新公共信任根；单次验证最多六十秒。证明随 Draft 保存；停用或验证失败将阻断归档认证，不能将证明视为审核批准 |
| 社区请求取消 | 同一社区仓库的 PR、文件、账号及组织 API | 开发者在投稿向导中选择取消，读取本人仍未合并的请求并再次确认后，仅将精确 PR 关闭。写入前重读作者数字身份、仓库、base、head 与合并状态；响应丢失时读取同一 PR 确认结果，不重复创建请求或删除资产。使用当前 GitHub CLI 身份及代理设置，不发送私钥；不选择取消即可避免这组写入 |
| 社区撤销清单续签机器人 | `api.github.com` 的社区仓库 Git tree/blob/commit/ref、专用分支 PR 与 Actions workflow dispatch API | 每周一 04:23 UTC 或手动运行；清单临近七天到期时创建续签维护 PR，有开放请求则返回原链接。仅读取主线清单和精确续签分支的 PR 历史；发送整代及清单摘要、Git 对象、PR 说明和目标检查编号，不发送密钥或插件源码。使用临时 job token，默认权限继续只读，该 job 单独申请 Contents、Pull requests、Actions 写入，仓库须允许 Actions 创建 PR。显式启动该 PR 的静态检查和 Gate；签发仍需维护者审核与 release Environment 批准，获批的完成审核工作流随后核对检查并合并 PR。API 沿用 GitHub CLI 的代理和六十秒、32 MiB 边界，job 十五分钟。停用该 workflow 可停止自动创建请求 |
| 社区完成审核 | 社区及经数字身份核验的投稿 fork 的 PR、Review、协作者、Environment、Actions 批准历史、Git tree/blob/commit/ref、PR merge、check-runs、Actions dispatch、Release/asset API；`/user`、`/user/<accountId>`、`/organizations/<accountId>`；GitHub 上传与资产 CDN；上述 OIDC、attestation 和公共信任根服务 | 维护者在 `master` 手动运行 `Complete community review`，核对开放请求的精确 head 并批准 `release` Environment。固定 SDK 在 runner 内生成清单与签名；工作流将结果文件、只含路径及摘要的回执、受保护签发证明写入原 PR 的追加提交。正文按 Git blob 复用，不再创建 operation 技术 Draft；生成、签名、原分支追加、重新签发准入检查和合并在同一个排队作业内完成。同仓库使用 job token；fork 须开启维护者编辑，追加使用维护者账户的专用 SSH 密钥，合并使用限定社区仓库 Contents 写权限的 PAT；合并前通过 `/user` 核对 PAT 为仓库所有者，缺少凭据时执行失败并给出提示。请求传输审核、恢复及组织代表证据、公钥、签名和包字节；社区签名私钥只经 stdin 进入本地签名进程；SSH 私钥只供下述分支写入步骤使用。API、上传和认证资产下载沿用 GitHub CLI 的代理、重定向及六十秒预算。预检为读取 Draft 显式申请 Contents write，只执行受保护主线代码，不使用维护者分支凭据；预检十五分钟，人工批准在队列外等待；最终集成作业三十分钟，读取当前主线 ref，保留原审核 head，以最新主线、已核验请求和生成结果构造提交。排队期间仅主线数据变化且保护代码未变时可以继续；旧生成结果经完整证明校验后可重新生成并快进分支。严格分支保护与人工拒绝仍生效；结果正文累计、清单和证明各最多 32 MiB；合并后的 Git 审计记录持久保留。停用 workflow 会阻断新的审核完成 |
| 社区 Release 发布与状态收尾 | 同一社区仓库的 Git、Release/asset、已合并 PR、标签与评论 API，`uploads.github.com`，以及上述归档证明服务；正式包的公开 GitHub Release 地址及重定向 CDN | `master` push 或手动恢复触发。核对原 PR 的真实 merge 父链、生成证明、App 准入与原候选签名，并对尚未公开的候选重新核对紧急分支封禁后，使用 job token 公开既有候选并匿名下载核对，再更新状态和维护者说明。公开下载遵守上述 Node 代理、公网地址、TLS、大小与摘要限制；API 继续使用 GitHub CLI。REVOKED 的安装包先下载并保存到共享 `archive/revoked-packages` Draft，按摘要去重并回读核对大小和摘要，随后复核主线和资产身份再删除公开安装包；正式 tag、撤销说明和历史签名保留。归档失败或平台禁止删除时保持失败。流程不重新构建、不新建 PR，也不扫描开放 PR 列表。job 三十分钟，回读错误保留失败；部分写入不能视为原子完成。维护者可停用 workflow，此时 Release 可能尚未与主线数据同步 |
| 社区仓库保护与标签维护脚本 | `https://api.github.com/repos/Sywyar/PixivDownloader-community-plugins/` 下的仓库、协作者、Ruleset、Environment、分支与标签 API，以及 `/user` | 维护者显式运行预览、回读或应用命令。通过 GitHub CLI 使用当前登录身份核对数字仓库 ID 和所有者；只有显式 apply 写入保护配置或受管标签，保留其它标签。目标配置开启 GitHub 合并后自动删除本仓库来源分支，涵盖普通投稿、维护及紧急请求；默认分支和紧急状态分支仍受禁止删除规则保护。fork 分支由投稿者仓库设置及权限控制，向导取消未合并请求时仍须确认删除。请求包含设置与标签元数据，不包含插件包或社区签名私钥；连接使用 GitHub CLI 的代理配置。不运行命令即可停用，替换目标须同时修改受保护仓库身份配置 |
| 社区仓库准入、人工裁决与通知 workflow | 同一社区仓库的 PR、Review、事件、协作者、Actions run/attempt、artifact 和 check-runs API；投稿 head 仓库的 Git tree/blob API；GitHub App 安装/token API；GitHub Actions artifact 服务及其下载重定向 | PR、Review 和相关工作流完成事件只重算关联 PR；维护者也可显式指定 PR。master 代码检查不扫描开放请求。标签变更及管理请求的空归档不重复触发准入检查；构建产出候选时，由归档完成事件唤醒审核。Gate 准备可在共享队列外验签，只在同一 run 内、源码和请求及 workflow attempt 完全一致时复用证据；排队期间主线推进后，最终 Gate 按最新状态重新校验。签名管理请求的最终准备和合并在共享队列内完成；最终作业仍重读原生审核、权限和紧急状态后签发检查。自审与 Gate 的内置 job token 为读取 Draft 显式申请 Contents write，源码构建及通知读取保持各自最小权限；固定 SDK 在本地验证合同，不下载 Schema 或 Maven 依赖。人工表单上传有原始字节摘要的裁决 JSON，保留期沿用仓库 artifact 设置。现有 Gate App 的私钥只在官方 App action 中用于换取本社区仓库的短期 `checks:write` token；通知 job 使用单独的标签与评论写权限，更新受管标签、状态评论及中文、英文各一条请求信息评论。请求信息来自固定 head 上经摘要和合同校验的请求文件，按操作展示分类、填写的原因、公开密钥标识与验签情况，并附原文链接；两种语言由同一份已验证请求生成，分别更新原评论；部分写入失败后再次同步可补齐，也不输出签名字节或私钥。版本审核还会读取下列 Draft 归档及其来源证明。请求包含仓库/PR/head、账号数字 ID、审核理由、裁决与检查元数据，不包含社区签名私钥。默认启用已声明的事件订阅；网络和代理遵循 runner、GitHub CLI 与官方 Actions 配置，下载目标由 GitHub 返回。维护者可停用对应 workflow，准入因此保持阻断 |
| 社区仓库测试与运行环境准备 | GitHub 源码及 Actions 服务、`actions/setup-node` 和 `actions/setup-java` 选择的 Node.js/Temurin 分发与下载服务 | PR 和默认分支 push 自动运行测试，受保护检查与裁决也准备相同工具链。发送固定 Action 引用、工具版本、平台和标准连接元数据；候选测试不取得 App 私钥或写 token。下载、缓存和代理遵循官方 setup Action 与 runner 配置；维护者通过 workflow 修改工具链或停用执行 |
| Release / Nightly 发行物 E2E | GitHub Actions Artifact 服务，下载、上传地址和重定向由 runner 提供 | Java 标准包和 full-offline 各自验收；Windows installer 按正常启动、故障注入、插件缺失与日志轮转分为三个独立 runner，每组均下载安装器、实际安装并卸载。组内有状态的步骤保持串行。每组下载所需发行物，并在成功或失败后以独立名称上传测试实例的结果、日志、窗口截图和插件状态，保留七天。一组失败不取消其它组，发布须等待全部成功。传输使用 Actions 为当前 job 提供的运行时凭据，不上传发布私钥；目标和代理遵循 runner 与官方 artifact Action 的配置。维护者可修改或停用共享发行验收 Action 中的证据上传步骤 |
| Git 和发布脚本 | 当前 `origin`、`https://api.github.com`（或 `GITHUB_API_URL`）、GitHub Release | `fetch`、远端引用检查、质量门禁审计、插件和应用发布 |
| GitHub Actions | GitHub Actions、Artifact、Release 服务以及 workflow 引用的 `actions/*`、`softprops/action-gh-release` | CI、构建、上传产物和发布。完整 Quality Gate 的 Java 测试、SDK 合同与 ProGuard 产物验证在三个独立 job 中并行运行，分别 checkout 同一源码并使用 Maven 依赖缓存；冷缓存可能重复下载依赖。这些检查仅有源码读取权限，不使用发布凭据。发布调用的 ProGuard job 上传未签名插件、验签工具及编译目录，保留一天；完整 QG 成功后，消费方只下载同一 run、同一源码 SHA 的产物（重跑失败任务时可复用较早 attempt 中已通过产物门禁的候选），并核对源码 SHA、构建参数和每个文件的 SHA-256，失败即阻断。Release / Nightly 在同一源码的完整 QG 通过后并行构建应用依赖闭包和签名发布已经验证的插件，再通过当前 run 的 Artifact 服务传递核心 JAR、验签工具和已验签插件，供 Java 包与安装器并行组装；发布凭据仍只在 `release` Environment 中使用。下载目标与代理边界由下列构建工具配置决定 |
| 主仓库 `Gate Checks` workflow | `https://api.github.com/repos/Sywyar/PixivDownloader/` 下的 Actions workflow、run、job、日志、PR、commit 与 check-runs API，GitHub App 安装及 token API，当前 `origin`，以及日志 API 返回的下载重定向 | 自动响应订阅的 CI 事件。通过 run 的 `workflow_id` 读取并核对 workflow 元数据，使用其名称识别工作流，运行标题只用于 PR 关联。PR-only 启用后，用原生 PR 关联或运行名称中的 PR 编号，结合 head 仓库、分支关联执行结果，只为当前投向 `master` 的开放 PR 写检查；开发分支 PR 和已关闭 PR 不取得 App token。使用只读 `GITHUB_TOKEN` 读取证据，以 Git 获取对应提交对象；通过核对后，官方 `actions/create-github-app-token` 用 `release` Environment 中的 App 私钥在 runner 内签发 JWT，再向 GitHub 换取仅有 `checks:write` 的短期安装 token，在 PR head 上关联必需检查（当前六项），同时保留原被测合并提交上的检查供合并后复验。检查详情保留原被测提交和实际执行链接。若 GitHub 重建测试合并提交，则通过 commit API 核对新对象的两个父提交和文件树；全部一致时可复用原执行，无需为重建对象补写检查。每次最多写两组；发布前后重查 PR、双亲、文件树和有效执行，发现变化或写入失败时分别尝试撤销两组检查。私钥不发送给 GitHub，token 在 job 结束时撤销。合并后核对实际 `master` 提交；手动全量 Quality Gate 的恢复证据也必须对应受保护 `master` 历史中的同一提交，可从指向该提交的分支或 tag 触发，这两条核对路径不写 App 检查。发送的数据包括仓库、PR / workflow / run / job 编号、提交 SHA 和检查结论。请求使用 runner 的 Git / GitHub CLI / 代理配置；日志下载目标由 GitHub 返回。停用 workflow 或 App 会阻塞对应 required checks |
| 主仓库 SDK 发布 workflow | `org.sonatype.central` 官方发布插件按当前 Central Portal 协议选择的 API，以及 `https://repo1.maven.org/maven2/io/github/sywyar/pixivdownloader/` | 受保护 `master` 上的 SDK 身份变化，或维护者手动选择发布 / 恢复时运行；在同一源码 SHA 的 Quality Gate 通过后，新发行用 `release` Environment 中的 Central token 与 PGP 材料发布五个 SDK 坐标（包括统一的 pixivdownload-sdk 入口）。官方插件最多等待 3600 秒，直到状态为 `PUBLISHED`；发布和恢复随后从公共 Maven Central 下载 POM、JAR、sources、Javadoc、签名和 SHA-256 做隔离消费者复验。Portal API 的最终主机 / 路径由官方 Maven 插件和 Central 服务决定，可被构建机代理改写；已安装应用不执行该流程 |
| SDK 候选产物传递 | GitHub Actions Artifact 服务，传输目标与重定向由 runner 提供 | 新 SDK 发布时，SDK job 上传已经验证的未签名 ZIP、元数据与摘要，保留 1 天。完整 QG 成功后，发布 job 通过不可变 artifact ID 下载同一 run 的候选，核对源码 SHA、大小与摘要后才签名，以串行传递复用构建和验证结果。请求使用 Actions 提供的运行时凭据，传输候选字节和 artifact ID，不含发布私钥；目标及代理沿用 runner 和官方 artifact action 配置。重试可复用同一 run 较早 attempt 的产物；缺失、过期或错配时失败，需要重跑完整 workflow。已有签名 Release 的恢复不依赖该保留期；普通 QG 调用方不导出 SDK 候选 |
| 主仓库 SDK 发布 / Pages 构建 workflow 与 SDK 仓库 Pages 调用器 | `https://api.github.com/repos/Sywyar/PixivDownloader-Plugin-SDK/releases`、`https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/`、该仓库 Release 资产 CDN，以及 GitHub Pages / Actions artifact deployment | 主仓库发布 workflow 使用 `release` Environment 中限定到 SDK 仓库的跨仓 token 检查并创建不可变 `sdk-api-v*` Tag / Release、上传签名发行附件，再通过公开 Release 下载复验。主仓库的可复用 Pages 构建 workflow 不接收该 token，而是匿名读取公共 Release API 与资产，校验全部非草稿 SDK Release 的摘要和元数据后生成版本化 Javadoc Pages artifact；SDK 仓库只用固定主仓库 commit SHA 调用它，并由本仓库部署 job 使用 `pages: write` 与 Pages OIDC 发布 artifact。手工 workflow dispatch、Tag / Release、Pages 设置和 deployment 均需对应授权；已安装应用不访问这些目标 |
| SDK 运行包准备工具 | `https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/<release-id>/<fixed-runtime.zip>`，允许重定向到 `release-assets.githubusercontent.com`、`objects.githubusercontent.com` | 显式 prepare / Run / Debug 且固定缓存不存在时，下载 SDK 清单指定的完整运行包；IDE 导入不触发。请求包含固定附件路径和 GitHub 返回的签名查询参数，不发送项目源码、插件 JAR、应用凭据或发布 token。JDK URLConnection 使用开发机的 JVM 代理设置，不读取宿主代理配置；最多 5 次 HTTPS 重定向。下载、缓存命中与每次运行均核对大小和 SHA-256，错误时不回退其它版本。缓存位于 `~/.cache/pixivdownloader-sdk/`，可用 `pixivdownload.sdk.cache-dir` 改目录；使用已验证缓存可避免下载，不执行 SDK 准备 / 运行则无这组请求 |
| SDK 构建的固定官方输入 | `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/<锁定提交>/manifest.json` 及其 `.sig`，随后访问已签名清单中的插件包地址和 GitHub Release CDN 重定向 | 每次完整 QG 都按源码中的插件仓库提交读取清单，用官方公钥验签，再下载全部官方插件。共享分发组装器构建 SDK 开发宿主前，会检查每个插件包的大小、SHA-256、签名和 ProGuard 标记。公开下载使用构建机的 Web 请求代理设置，不携带发布凭据；包缺失或字节变化会使验证失败。已发布 SDK 的开发者取得上行所述 SDK 仓库固定附件；已安装应用不执行此构建步骤 |
| SDK 冻结附件与发布恢复 | 上述 SDK 仓库 Release、Tag 与资产 API | Central 前持久保存已签名 Draft 附件并回读；全部公开坐标可读后，验证冻结附件与完整宿主运行，再公开同一 Release。全部 Central 坐标公开且原始 Release 存在时，普通发布重试和显式恢复均复用原始宿主、工具和 SDK ZIP，不再次上传 Central。已有 Draft 而 Central 为空时，原上传可能仍在处理中，流程停止并提示维护者检查原 deployment ID；部分坐标公开、附件缺失或冲突同样停止，不覆盖公开资产。发布后消费者用空缓存实际下载运行附件。跨仓库 token 只用于发布 workflow 的 GitHub 请求；Pages 匿名下载 SDK / 历史 Javadoc ZIP，校验运行附件元数据，不下载历代宿主 |
| 手动 FFmpeg 稳定版构建 workflow | `https://ffmpeg.org/download.html`、`https://ffmpeg.org/releases/`、`https://ffmpeg.org/ffmpeg-devel.asc`、`https://chromium.googlesource.com/webm/libwebp`、Linux runner 与 macOS Homebrew 配置的软件源，以及 GitHub Actions / Artifact / API / Release | 仅由维护者在主分支手动触发：解析并验证 FFmpeg 官方最新稳定源码及签名，取得固定提交的 libwebp，构建五个平台资产，生成包含精确大小与 SHA-256 的发行清单并用 `release` Environment 中的官方私钥签名，再使用跨仓库令牌更新 Remote Content 的 `ffmpeg-stable` Release；已安装应用不会访问这些源码与构建依赖地址，私钥不会发送给下载目标 |
| 本地 Windows 打包脚本 `scripts/package-local.ps1` | `https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/` 下的 `ffmpeg-release.json`、`ffmpeg-release.json.sig`、`ffmpeg-windows-x64.zip`，以及 GitHub Release CDN 重定向 | 默认构建离线 Windows portable 时通过 `curl.exe` 下载公开的签名清单和资产；先用仓库内官方信任根验签，再核对精确资产名、大小和 SHA-256，验证通过后才写入离线包。请求不携带应用账号、Pixiv Cookie 或发布凭据；已缓存文件只有重新验证通过才会复用。运行本地打包且未指定 `-SkipOfflinePortable` 时触发；`-RedownloadFfmpeg` 强制重新下载。使用构建机的 curl / 代理配置；指定 `-SkipOfflinePortable` 可跳过这组请求，已安装应用不会执行该脚本 |
| Quality Gate 与 SDK 发布前验证 | Maven Wrapper 配置的 Maven 下载地址、Maven / Gradle 依赖仓库及其重定向；本地暂存 SDK 仓库使用 file URL | 每次完整 QG 都运行 Compose 测试、模板、完整 SDK Javadoc 与隔离消费者验证，无需先变更 SDK 版本。SDK 产物暂存、模板、文档和消费者在同一 job 中串行复用构建结果；Compose 测试接在 ProGuard 构建之后。隔离消费者先联网下载构建插件和第三方依赖，再以本次 SDK 的精确字节离线重建并验证第三方插件运行。联网请求包含 artifact 坐标与下载路径，不携带发布凭据；测试签名使用运行时生成的临时第三方密钥。下载目标及代理 / 镜像取决于 Wrapper、构建工具和隔离消费者 settings；缓存可减少请求，QG 无单独跳过开关。正式 SDK 发布消费本次门禁已经验证的候选，并对公开产物再次验证 |
| Maven / Maven Wrapper | `https://repo.maven.apache.org/maven2`；SDK 公开发行复验还使用 `https://repo1.maven.org/maven2` | 下载 Maven 3.9.11、Java 依赖和构建插件；SDK 发布后从公共 Central 端点验证可解析性与不可变内容 |
| 应用与 SDK 内部社区合同构建 | 上述 Maven Central 中的 `com/networknt/json-schema-validator/`、`io/github/erdtman/java-json-canonicalization/`、`com/twelvemonkeys/imageio/imageio-webp/` 及其传递依赖 | 构建应用或 `pixivdownload-sdk-tools` 且缓存缺失时，Maven 为内部共享 `pixivdownload-community-contract` 解析固定的 Schema 与 JCS 依赖；共享合同模块不包含 WebP 解码器；这些依赖不加入五个公共 SDK Maven 组件。请求包含 artifact 路径，不发送投稿内容或应用凭据；使用 Maven 的代理、镜像与缓存设置，已备齐依赖时可离线构建。合同校验本身只读取随工具提供的 Schema 和目录资源，不远程解析 Schema |
| 社区许可证资源维护 | `https://api.github.com/repos/spdx/license-list-data/` 的 tag、commit 和 contents API，或 `https://raw.githubusercontent.com/spdx/license-list-data/<commit>/` | 维护者显式更新资源时取得 SPDX 目录和许可证文本，固定来源 commit、长度与 SHA-256 后纳入源码；普通构建及已安装应用直接读取这些本地资源，不自动追踪最新版本。请求包含公开资源路径；GitHub CLI 可使用开发机已有 GitHub 认证，原始文件下载不需要发布凭据。连接遵循所用 CLI 的代理设置；保留现有资源可避免访问 |
| npm | 当前锁文件中的 `https://registry.npmmirror.com` | 安装 Node 构建/检查依赖；社区投稿工具的维护者也从此处安装锁文件固定的 Clack、esbuild 及传递依赖，用于生成随工具清单交付的交互组件。仅维护者主动执行 npm 安装时访问，遵循本机 npm 的镜像、代理和认证配置；投稿向导直接使用已校验的打包文件，不在运行时访问 npm。跳过依赖安装并使用仓库内的打包文件即可避免此请求 |
| SDK 跨构建工具验证 | `https://repo.maven.apache.org/maven2`，包括 `org/scala-sbt/sbt-launch/1.10.11/sbt-launch-1.10.11.jar`；仓库 Maven / Gradle Wrapper 指定的发行包地址及重定向 | 完整 QG 与 SDK 发布验证默认运行 Maven、Gradle、sbt 独立消费者，检查单个 SDK 依赖的编译能力、精确字节和运行时作用域。CI 下载固定版本、固定 SHA-256 的 sbt launcher；本地命令接受已有 launcher。各工具使用隔离缓存，Maven 使用空 settings，Gradle 与 sbt 固定第三方依赖到 Central；SDK 来自本地暂存目录。Maven 和 Gradle 在线构建后再离线重建，sbt 执行在线编译。请求只包含工具版本、artifact 坐标和下载路径，无应用数据或发布凭据；连接遵循构建机代理配置，Wrapper 服务可能重定向到发行 CDN。本地可用 `--tool` 只验证一个工具，QG 必须验证三者；替换下载源需修改 Wrapper 或验证脚本 |
| 应用维护者目录生成器 | `https://api.github.com/repos/Sywyar/PixivDownloader`、`/contributors`、`/users/{login}`，以及 API 返回的 `https://avatars.githubusercontent.com` 头像地址 | 每次构建应用资源时读取仓库所有者、贡献者及本地提交作者/共同作者，与人工维护的真人白名单求交后下载获准头像，并把 JSON 与图片字节打包进程序。CI 构建入口显式传入只读的内置 `GITHUB_TOKEN`；本地优先使用 `GITHUB_TOKEN`，其次 `GH_TOKEN`，两者均缺失时匿名请求。凭据仅发送给 `api.github.com`，API 与头像请求均拒绝重定向，头像请求不携带凭据。每次 API 请求超时 30 秒，明确限流或暂时性故障最多尝试 3 次，累计重试等待最多 180 秒；遵守服务端等待时间，超过预算直接失败，普通权限错误不重试。诊断输出脱敏的 API 消息、请求编号与限流头。发行边界验收直接消费本次构建产物，不重新生成目录。该步骤没有独立关闭开关，生成无法完成时构建失败；已安装应用不会自动访问这些地址 |
| SDK 发行基线校验 | `https://api.github.com/repos/Sywyar/PixivDownloader-Plugin-SDK/releases`、该仓库公开 Release 的 `sdk-release.json` 附件，以及 `https://repo1.maven.org/maven2/io/github/sywyar/pixivdownloader/` 下的 SDK POM | 正式应用发行和独立 SDK 发布前，CI 读取公开 SDK Release 的源码身份，比较当前 SDK 合同与已发行源码。两条流程都会确认所选版本的五个 Maven POM 已公开且指向同一源码。日常 PR 与 Nightly 不执行这些发行查询。GitHub 请求使用 Actions 内置只读令牌，Maven 请求不带令牌；已安装应用不会执行此检查 |
| Docker | 配置的 OCI 镜像仓库，默认情况下解析 `eclipse-temurin:17-jre`；基础镜像配置的 Debian 软件源 | 拉取基础镜像，以及安装 FFmpeg、curl 等系统包 |
| Windows CI | Chocolatey 配置的软件源 | 安装 Inno Setup 等打包工具 |

构建机、代理、镜像或包管理器配置可以改写最终下载主机，因此这些传递依赖无法仅凭仓库源码列出稳定的完整域名集合。

## 链接及非请求型 URL

应用和文档中还包含指向 GitHub、Releases、在线文档、Tampermonkey 和许可证站点的链接。此类链接仅在被访问或由浏览器实际加载资源时产生请求。XML 命名空间、POM Schema、许可证正文中的 URL 和示例域名不属于自动网络请求目标。

在应用 Web UI 中点击外部 HTTP(S) 链接（包括本地公告/调查 HTML 快照内的链接）时，全站确认弹窗会先展示目标地址；只有明确确认后，浏览器才会直接连接该地址，取消则不会产生请求。该浏览器导航不经过 Java 后端或全局代理，实际目标由所点击的链接决定；站内同源链接保持直接跳转。
