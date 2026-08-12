# 网络访问与第三方服务

本文列出 PixivDownloader 当前代码可能访问的外部网络目标，以及负责发起请求的宿主组件或插件、请求用途、触发条件和默认状态。文中的“插件”采用插件管理页显示的插件 ID；“应用宿主”表示请求由核心应用 `pixivdownload-app` 发起，不归属于可选插件。

?> 本清单涵盖当前代码内固定的默认地址和允许的动态地址范围。第三方服务可能使用 DNS、CDN、重定向或临时下载地址，因此实际连接的 IP 地址和最终子域可能发生变化。管理员配置的自定义 URL 不在可预先穷举的范围内。

## 网络访问概览

- AI、TTS、推送、邮件和 Douyin 仅在相应功能完成配置并被调用时访问外部服务；`notification` 是例外，它会在启用并启动后自动检查固定公告索引。
- 在线更新和自动检查均启用时，应用宿主会在启动就绪后检查 GitHub Releases；检查频率受缓存间隔限制。
- 访问应用介绍页时，浏览器会加载 Google Fonts。该请求不由后端发起，也不经过 PixivDownloader 的出站代理。
- `plugin-catalog.enabled` 默认为 `false`，因此插件市场默认不会访问官方插件仓库。
- 布局反馈调查的 PostHog 公开配置在当前源码中为 `enabled: false`，默认不会连接 PostHog。
- Pixiv、Douyin、AI、TTS、推送和邮件请求可能包含用户内容或访问凭据，具体范围见后续各节。

## 核心功能及默认网络请求

| 请求所有者 | 目标地址 | 用途与主要发送内容 | 触发场景与默认状态 | 代理与关闭方式 |
| --- | --- | --- | --- | --- |
| 应用宿主 | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json`；nightly 使用 `/releases/download/nightly/update.json`；重定向后可能进入 GitHub Release 资产 CDN | 检查正式版或 nightly 更新；发送当前版本、平台和标准 HTTP 请求信息，不发送 Pixiv Cookie | 应用就绪后自动检查；`update.enabled=true`、`update.auto-check=true` 时启用，默认均开启；手动检查也会访问 | 使用宿主出站 HTTP 路由；可关闭在线更新或自动检查，也可修改 manifest URL |
| 应用宿主 | 更新清单中当前平台对应的安装包 URL，默认来自 GitHub Release | 下载更新安装包，并按清单校验 SHA-256 和大小 | 检查到更新且明确启动下载和安装后触发；更新检查本身不会自动安装 | 目标由更新清单决定；关闭在线更新可完全停用该链路 |
| 应用宿主的介绍页 | `https://fonts.googleapis.com/css2?...`、`https://fonts.gstatic.com/...` | 获取 Noto Sans SC 样式和字体文件；浏览器会正常暴露 IP 地址、User-Agent 等连接元数据 | 访问介绍页时由浏览器触发 | 不经过宿主代理；域名被阻止时使用后备字体，下载功能不受影响 |
| 应用宿主 | `https://www.pixiv.net/` | Pixiv 连通性探测，不携带 Pixiv Cookie | 首次配置或执行 Pixiv 连通性检查时触发，不是持续心跳 | 使用宿主的 Pixiv 出站路由；未执行探测时不发起该请求 |
| `notification` 插件 | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json` 与 `.../announcements/<message-id>/<locale>.html` | 读取公开公告索引，并仅为未知稳定 ID 下载当前语言的受控 HTML 正文；请求禁用 Cookie，只发送 IP、User-Agent 等标准连接元数据，不发送账号、作品、本地路径或其它凭据。HTML 快照保存在本地，管理员浏览器只读取本地鉴权端点，不再直连外部正文 | 插件每次启动后异步检查索引一次，之后约每 6 小时检查；只有首次发现未知 ID 时才有界下载一次对应 HTML。同一 ID 已保存或已显式删除时不再请求正文。官方默认插件集合包含 `notification`，启用并成功启动时会自动访问 | 使用宿主继承出站路由，可使用已启用的全局代理；禁用/卸载 `notification` 会停止检查，插件停止或重载时立即取消后续轮询 |

## Pixiv 下载与浏览

Pixiv 业务请求的 HTTP 传输由应用宿主提供，业务触发方则可能是下载工作台、小说插件或油猴脚本。下表按业务请求归属进行区分，不将共享传输层请求统一归类为核心请求。

| 请求所有者 | 目标地址 | 用途与主要发送内容 | 触发场景 | 代理与关闭方式 |
| --- | --- | --- | --- | --- |
| `download-workbench` 插件 | `https://www.pixiv.net/ajax/illust/**`、`/ajax/user/**`、`/ajax/search/artworks/**`、`/ajax/series/**`、`/ajax/collection/**`、`/ajax/follow_latest/illust`、`/rpc/index.php` | 获取插画、动图、画师、系列、搜索、收藏夹、关注动态和约稿等元数据；需要登录的请求会使用已配置的 Pixiv Cookie | 预览、快捷获取、提交下载、执行计划任务、回填作品信息或浏览相关取得模式时触发 | 经宿主提供给插件的 Pixiv HTTP 能力和所选代理路由；`download-workbench` 是必需插件（required），其缺失或不可用时主要下载功能不可用 |
| `download-workbench` 插件 | `https://www.pixiv.net/ajax/illusts/bookmarks/add` | 下载完成后收藏插画；发送作品 ID、收藏可见性和标签，并使用 Pixiv 登录凭据 | 仅在启用“下载后收藏”等相应选项且作品下载完成后触发；请求失败不会回滚已完成的下载 | 关闭下载后收藏功能可停用该请求 |
| `novel` 插件 | `https://www.pixiv.net/ajax/novel/**`、`/ajax/novel/series/**`、`/ajax/novel/series_content/**`、`/ajax/user/**/novels`、`/ajax/user/**/novels/bookmarks`、`/ajax/search/novels/**` | 获取小说正文、小说系列、作者小说列表、收藏和搜索结果；需要登录的请求会使用 Pixiv Cookie | 预览或下载小说、系列合订、小说搜索、画师小说和计划任务处理时触发 | 经宿主 Pixiv HTTP 能力；禁用 `novel` 会撤回小说页面和相关能力 |
| `novel` 插件 | `https://www.pixiv.net/ajax/novels/bookmarks/add` | 下载完成后收藏小说 | 仅在启用相应选项且小说下载完成后触发 | 关闭下载后收藏功能即可停用 |
| 应用宿主共享图片传输，调用方主要为 `download-workbench`、`novel` | HTTPS `*.pximg.net`；缩略图还允许 `embed.pixiv.net` | 下载 Pixiv 原图、封面、缩略图、动图压缩包和小说内嵌图片；通常发送 Pixiv Referer，图片地址来自 Pixiv API 响应 | 下载作品、生成封面/缩略图、浏览预览或生成小说文件时触发 | 使用 Pixiv 图片下载路由；停止相关下载或预览即可避免 |
| 根目录油猴脚本，不属于插件 | `https://www.pixiv.net/ajax/**`、HTTPS `*.pximg.net` | 在 Pixiv 网页中直接读取作品/小说信息、收藏作品或下载文件；`GM_xmlhttpRequest` 会使用浏览器/Pixiv 登录态 | 用户在 Pixiv 页面点击脚本功能、抓取页面或执行本地下载时触发 | 由浏览器/脚本管理器直连，不经过宿主代理；禁用或卸载相应脚本即可停用 |
| 根目录油猴脚本，不属于插件 | 管理员配置的 PixivDownloader 地址，默认 `http://localhost:6999` | 调用下载提交、队列、状态和 SSE 等后端 API；发送所选作品和下载参数 | 使用 Java 后端版、批量脚本或工具箱功能时触发 | 默认仅连接本机；配置为远端地址后，请求将发送至相应远端服务器 |

## `douyin` 插件

`douyin` 是按需安装插件，默认 Windows 安装包和 Java 标准包不预置。只有安装、启用并使用 Douyin 功能后才会产生下列请求。

| 目标地址 | 用途与主要发送内容 | 触发场景 | 代理与关闭方式 |
| --- | --- | --- | --- |
| `https://www.douyin.com/aweme/v1/web/**` | 获取用户作品、喜欢列表、合集、搜索、音乐作品、收藏夹、账号信息和作品详情；会发送 Douyin Cookie、查询参数及模拟浏览器所需请求头 | 手动取得、预览、下载、计划任务发现、Cookie 探活和 Douyin 画廊刷新时触发 | 使用任务级、来源默认或宿主全局路由；禁用/卸载 `douyin` 即完全停用 |
| HTTPS `douyin.com` / `*.douyin.com`、`iesdouyin.com` / `*.iesdouyin.com`，常见为 `v.douyin.com` | 解析用户粘贴的短链接，最多跟随受限跳转 | 输入 Douyin 短链接并开始解析时触发 | 与该次 Douyin 任务使用相同路由 |
| HTTPS `*.douyinvod.com`、`*.douyinpic.com`、`*.douyinstatic.com`、`*.amemv.com`、`*.byteimg.com`、`*.bytedance.com`、`*.bytecdn.cn`、`*.pstatp.com`、`*.snssdk.com`，以及上述 Douyin 域 | 下载视频、图片、封面和实况照片媒体；具体 URL 来自 Douyin API 响应并可能发生受限重定向 | 执行 Douyin 媒体下载时触发 | 与该次 Douyin 任务使用相同路由；停止任务或禁用插件即可终止后续请求 |

## `ai` 插件

AI 插件使用 OpenAI 兼容协议，向所选基础地址的 `/chat/completions` 发送请求。请求通常包含待翻译或待处理文本、特定用途提示词、模型名称和 API Key。插件安装操作不会发起此类请求；保存有效配置后执行连接测试、翻译或其他 AI 功能时才会触发。

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

AI 基础地址可配置为其他兼容服务，因此完整目标范围取决于实际配置。代理行为由 AI 配置决定；删除 API Key、停用或清空配置，或者禁用 `ai` 插件，可停止相关请求。

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

具体引擎可配置独立基础地址，代理行为取决于出站路由和引擎配置。禁用 `tts`、停用对应引擎或清空其配置即可停止请求。

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

关闭通道、删除凭据或禁用 `push` 插件可停止请求。自定义 Webhook 的安全性和数据接收方由填写该 URL 的管理员负责。

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
| 应用宿主的插件市场 | `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`；包地址通常为 GitHub Release，并可能重定向到 `*.githubusercontent.com` | 获取官方插件清单、下载用户选择的插件包并做签名、SHA-256 和大小校验 | `plugin-catalog.enabled` 默认为 `false`；管理员开启市场并刷新清单或安装插件时才触发 |
| 应用宿主的插件市场 | 管理员配置的自定义 HTTPS manifest 和其中声明的包 URL | 使用第三方/自建插件仓库 | 只有配置并启用对应仓库后触发；直连严格策略可能明确不使用全局代理，具体以仓库策略为准 |
| 应用宿主 FFmpeg 安装器 | `https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip`，以及其 Release CDN 重定向 | 下载 Windows FFmpeg LGPL 构建 | 仅在 GUI 中明确选择自动安装 FFmpeg 时触发；应用启动本身不会下载 |
| 油猴脚本管理器，不属于插件 | `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js` | 检查和下载六个独立油猴脚本更新 | 由 Tampermonkey 等脚本管理器按其更新策略触发；禁用脚本自动更新或卸载脚本即可停止 |
| All-in-One 油猴脚本管理器，不属于插件 | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/Pixiv%20All-in-One.user.js` | 检查或下载构建生成的合并脚本 | 仅安装该发行脚本后由脚本管理器触发 |

## `download-workbench` 可选布局调查（PostHog）

布局反馈逻辑属于 `download-workbench` 插件。PostHog JavaScript SDK 已随插件静态资源打包，不会从 CDN 加载 SDK。

- 当前源码公开配置为 `enabled: false`，`apiHost` 和 `uiHost` 均为空，因此默认构建不会访问 PostHog。
- 只有发行构建显式注入完整的 Project Token、Survey ID、`apiHost` 和 `uiHost` 并启用调查后，相关页面才会初始化调查。
- 启用后，浏览器会直接访问构建配置指定的 PostHog API/UI 主机，可能发送调查展示状态、回答、身份模式和页面交互信息；不经过宿主出站代理。
- 目标主机不能根据常规运行时配置推断；应以发行包实际携带的 `pixiv-layout-feedback/public-config.js` 为准。

## 不包含固定公网目标的官方插件

以下插件本身不增加固定第三方网络目标：

- `stats`：读取本地数据库并生成统计。
- `duplicate`：读取本地文件和 Hash 数据进行重复检测。
- `gallery`：浏览本地下载记录和本地媒体。
- `gui-theme`：提供本地 GUI 主题资源。
- `recovery-sentinel`：仅用于恢复模式验证，不包含在常规用户发行包中。

它们的页面仍会调用当前 PixivDownloader 实例的同源 API，但这不是访问第三方公网。

## 本机、同源及管理员配置的目标地址

- GUI、Web 页面和插件前端会访问当前 PixivDownloader 实例的 `/api/**`、静态资源和 SSE。桌面 GUI 默认连接 `http://localhost:{port}` 或 `https://localhost:{port}`。
- Ollama、LM Studio、VoxCPM、CosyVoice 和油猴脚本后端可配置为本机服务；基础地址指向远端后，该远端即成为新的数据接收方。
- 图片分类器的 `server.url` 默认为 `http://localhost:6999`，也可指向管理员配置的其他 PixivDownloader 实例。
- 自定义 Webhook、AI/TTS 基础地址、更新清单、插件仓库、Bark、SMTP、SOCKS 和代理端点都由管理员配置，无法形成封闭的固定域名白名单。
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
| Maven / Maven Wrapper | `https://repo.maven.apache.org/maven2` | 下载 Maven 3.9.11、Java 依赖和构建插件 |
| npm | 当前锁文件中的 `https://registry.npmmirror.com` | 安装 Node 构建/检查依赖 |
| Docker | 配置的 OCI 镜像仓库，默认情况下解析 `eclipse-temurin:17-jre`；基础镜像配置的 Debian 软件源 | 拉取基础镜像，以及安装 FFmpeg、curl 等系统包 |
| Windows CI | Chocolatey 配置的软件源 | 安装 Inno Setup 等打包工具 |

构建机、代理、镜像或包管理器配置可以改写最终下载主机，因此这些传递依赖无法仅凭仓库源码列出稳定的完整域名集合。

## 链接及非请求型 URL

应用和文档中还包含指向 GitHub、Releases、在线文档、Tampermonkey 和许可证站点的链接。此类链接仅在被访问或由浏览器实际加载资源时产生请求。XML 命名空间、POM Schema、许可证正文中的 URL 和示例域名不属于自动网络请求目标。

在应用 Web UI 中点击外部 HTTP(S) 链接（包括本地公告/调查 HTML 快照内的链接）时，全站确认弹窗会先展示目标地址；只有明确确认后，浏览器才会直接连接该地址，取消则不会产生请求。该浏览器导航不经过 Java 后端或全局代理，实际目标由所点击的链接决定；站内同源链接保持直接跳转。
