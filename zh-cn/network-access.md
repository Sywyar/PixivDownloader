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
| 应用宿主 | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json` 与相邻的 `update.json.sig`；nightly 使用 `/releases/download/nightly/` 下的同名文件；重定向后可能进入 GitHub Release 资产 CDN | 获取正式版或 nightly 更新清单及 Ed25519 detached 签名；只发送 User-Agent、IP 等标准连接元数据，不发送当前版本、平台、Pixiv Cookie 或其它凭据。响应分别受 1 MiB / 16 KiB 上限约束，清单会在解析前使用应用内置官方公钥验签 | 应用就绪后自动检查；`update.enabled=true`、`update.auto-check=true` 时启用，默认均开启；手动检查也会访问 | 使用宿主出站代理配置；仅允许 HTTPS 和默认公网地址，最多跟随五跳重定向且每一跳都重新校验。可关闭在线更新或自动检查；自定义 manifest 必须是持有有效官方签名的公网 HTTPS 镜像 |
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
| 腾讯混元 | `https://api.hunyuan.cloud.tencent.com/v1` |
| 百度千帆/ERNIE | `https://qianfan.baidubce.com/v2` |
| 讯飞星火 | `https://spark-api-open.xf-yun.com/v1` |
| MiniMax | `https://api.minimaxi.com/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| SiliconFlow | `https://api.siliconflow.cn/v1` |
| Ollama | `http://localhost:11434/v1` |
| LM Studio | `http://localhost:1234/v1` |

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
| 应用宿主的插件市场 | 已信任描述符中的 HTTPS catalog endpoint、可选 `revocations.json` / `repository-update.json` 及相邻 `.sig`，以及目录指定的插件 JAR/ZIP URL | `manifest-v1` 读取清单和清单签名；`paged-v2` 分页读取列表、详情和指定版本；安装前刷新强制撤销状态、重新解析指定版本并下载包，再校验大小、SHA-256、发布者签名和包内 descriptor。重新导入已信任仓库时可能读取连续性证明 | 浏览/搜索/翻页、查看详情、明确安装/更新或重新导入时触发；启动只读取本地最后有效撤销快照，不自动请求第三方仓库。使用描述符映射后的固定安全网络档位；禁用/删除仓库或关闭 `plugin-catalog.enabled` 可停用后续请求 |
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

| 工具/流程 | 目标或来源 | 用途 |
| --- | --- | --- |
| Git 和发布脚本 | 当前 `origin`、`https://api.github.com`（或 `GITHUB_API_URL`）、GitHub Release | `fetch`、远端引用检查、质量门禁审计、插件和应用发布 |
| GitHub Actions | GitHub Actions、Artifact、Release 服务以及 workflow 引用的 `actions/*`、`softprops/action-gh-release` | CI、构建、上传产物和发布 |
| 主仓库 SDK 发布 workflow | `org.sonatype.central` 官方发布插件按当前 Central Portal 协议选择的 API，以及 `https://repo1.maven.org/maven2/io/github/sywyar/pixivdownloader/` | 只有受保护 `master` 上的 SDK 身份变化，或维护者明确触发发行恢复时才运行；在同一源码 SHA 的 Quality Gate 通过后，用 `release` Environment 中的 Central token 与 PGP 材料发布四个 SDK 坐标，再从公共 Maven Central 下载 POM、JAR、sources、Javadoc、签名和 SHA-256 做隔离消费者复验。Portal API 的最终主机 / 路径由官方 Maven 插件和 Central 服务决定，可被构建机代理改写；已安装应用不执行该流程 |
| 主仓库 SDK 发布 workflow 与 SDK 仓库 Pages workflow | `https://api.github.com/repos/Sywyar/PixivDownloader-Plugin-SDK`（或对应 `GITHUB_API_URL`）、该仓库 GitHub Releases / Release 资产 CDN，以及 GitHub Pages / Actions artifact deployment | 主仓库 workflow 使用限定到 SDK 仓库的跨仓 token 检查并创建不可变 `sdk-api-v*` Tag / Release、上传签名发行附件，再通过公开 Release 下载复验；SDK 仓库 workflow 使用仓库 `GITHUB_TOKEN` 枚举并下载全部非草稿 SDK Release，校验摘要与元数据后生成版本化 Javadoc Pages artifact。手工 workflow dispatch、Tag / Release、Pages 设置和 deployment 均需对应授权；已安装应用不访问这些目标 |
| 手动 FFmpeg 稳定版构建 workflow | `https://ffmpeg.org/download.html`、`https://ffmpeg.org/releases/`、`https://ffmpeg.org/ffmpeg-devel.asc`、`https://chromium.googlesource.com/webm/libwebp`、Linux runner 与 macOS Homebrew 配置的软件源，以及 GitHub Actions / Artifact / API / Release | 仅由维护者在主分支手动触发：解析并验证 FFmpeg 官方最新稳定源码及签名，取得固定提交的 libwebp，构建五个平台资产，生成包含精确大小与 SHA-256 的发行清单并用 `release` Environment 中的官方私钥签名，再使用跨仓库令牌更新 Remote Content 的 `ffmpeg-stable` Release；已安装应用不会访问这些源码与构建依赖地址，私钥不会发送给下载目标 |
| 本地 Windows 打包脚本 `scripts/package-local.ps1` | `https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/` 下的 `ffmpeg-release.json`、`ffmpeg-release.json.sig`、`ffmpeg-windows-x64.zip`，以及 GitHub Release CDN 重定向 | 默认构建离线 Windows portable 时通过 `curl.exe` 下载公开的签名清单和资产；先用仓库内官方信任根验签，再核对精确资产名、大小和 SHA-256，验证通过后才写入离线包。请求不携带应用账号、Pixiv Cookie 或发布凭据；已缓存文件只有重新验证通过才会复用。运行本地打包且未指定 `-SkipOfflinePortable` 时触发；`-RedownloadFfmpeg` 强制重新下载。使用构建机的 curl / 代理配置；指定 `-SkipOfflinePortable` 可跳过这组请求，已安装应用不会执行该脚本 |
| Maven / Maven Wrapper | `https://repo.maven.apache.org/maven2`；SDK 公开发行复验还使用 `https://repo1.maven.org/maven2` | 下载 Maven 3.9.11、Java 依赖和构建插件；SDK 发布后从公共 Central 端点验证可解析性与不可变内容 |
| npm | 当前锁文件中的 `https://registry.npmmirror.com` | 安装 Node 构建/检查依赖 |
| 应用维护者目录生成器 | `https://api.github.com/repos/Sywyar/PixivDownloader`、`/contributors`、`/users/{login}`，以及 API 返回的 `https://avatars.githubusercontent.com` 头像地址 | 每次构建应用资源时读取仓库所有者、贡献者及本地提交作者/共同作者，与人工维护的真人白名单求交后下载获准头像，并把 JSON 与图片字节打包进程序；API 请求可使用构建环境的 `GITHUB_TOKEN` / `GH_TOKEN`，头像请求不携带凭据；该步骤没有独立关闭开关，生成无法完成时构建失败；已安装应用不会自动访问这些地址 |
| Docker | 配置的 OCI 镜像仓库，默认情况下解析 `eclipse-temurin:17-jre`；基础镜像配置的 Debian 软件源 | 拉取基础镜像，以及安装 FFmpeg、curl 等系统包 |
| Windows CI | Chocolatey 配置的软件源 | 安装 Inno Setup 等打包工具 |

构建机、代理、镜像或包管理器配置可以改写最终下载主机，因此这些传递依赖无法仅凭仓库源码列出稳定的完整域名集合。

## 链接及非请求型 URL

应用和文档中还包含指向 GitHub、Releases、在线文档、Tampermonkey 和许可证站点的链接。此类链接仅在被访问或由浏览器实际加载资源时产生请求。XML 命名空间、POM Schema、许可证正文中的 URL 和示例域名不属于自动网络请求目标。

在应用 Web UI 中点击外部 HTTP(S) 链接（包括本地公告/调查 HTML 快照内的链接）时，全站确认弹窗会先展示目标地址；只有明确确认后，浏览器才会直接连接该地址，取消则不会产生请求。该浏览器导航不经过 Java 后端或全局代理，实际目标由所点击的链接决定；站内同源链接保持直接跳转。
