# Network Access and Third-Party Services

This page lists the external network destinations that the current PixivDownloader code may access, together with the host component or plugin responsible for each request, its purpose, trigger conditions, and default state. Plugin names use the plugin IDs shown on the Plugin Management page. “Application host” means that the core application `pixivdownload-app` initiates the request and that the request is not owned by an optional plugin.

?> This inventory covers fixed default addresses in the current code and the permitted ranges of dynamic destinations. Third-party services may use DNS, CDNs, redirects, or temporary download URLs, so the actual IP address and final subdomain may change. Administrator-defined custom URLs cannot be enumerated in advance.

## Network access overview

- AI, TTS, push, mail, and Douyin access external services only when the corresponding feature is configured and invoked. `notification` is the exception: once enabled and started, it automatically checks a fixed announcement index.
- When online updates and automatic checking are enabled, the application host checks GitHub Releases after startup readiness. The check frequency is limited by a cache interval.
- When the application intro page is opened, the browser loads Google Fonts. This request is not initiated by the backend and does not use PixivDownloader's outbound proxy.
- `plugin-catalog.enabled` defaults to `false`, so the plugin market does not contact the official plugin repository by default.
- The PostHog public configuration for the layout feedback survey is `enabled: false` in the tracked source, so it does not contact PostHog by default.
- Pixiv, Douyin, AI, TTS, push, and mail requests may contain user content or access credentials, as specified in the following sections.

## Core and default network requests

| Request owner | Destination | Purpose and main data sent | Trigger and default state | Proxy and disable control |
| --- | --- | --- | --- | --- |
| Application host | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json`; nightly uses `/releases/download/nightly/update.json`; redirects may lead to the GitHub Release asset CDN | Checks stable or nightly releases; sends the current version, platform, and standard HTTP request metadata, but no Pixiv cookie | Runs after application readiness when `update.enabled=true` and `update.auto-check=true`; both default to enabled. A manual check uses the same endpoint | Uses the host outbound HTTP route. Disable online updates or automatic checking, or replace the manifest URL |
| Application host | Installer URL for the current platform from the update manifest, GitHub Releases by default | Downloads an update installer and verifies its SHA-256 and size | Triggered only after an update is found and installation is explicitly started; an update check does not install automatically | The manifest determines the destination. Disable online updates to stop this chain |
| Application intro page | `https://fonts.googleapis.com/css2?...`, `https://fonts.gstatic.com/...` | Loads Noto Sans SC CSS and font files. The browser exposes normal connection metadata such as IP address and User-Agent | Triggered when the intro page is opened | Bypasses the host proxy. Blocking the domains only causes a font fallback |
| Application host | `https://www.pixiv.net/` | Pixiv connectivity probe without a Pixiv cookie | Triggered during first-time setup or when the Pixiv connectivity check is run; it is not a heartbeat | Uses the host Pixiv route. The request is not made unless the probe runs |
| `notification` plugin | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/index.json` | Reads public announcement IDs, publication times, severity, localized titles/summaries, and controlled document URLs. It sends only standard HTTP connection metadata—no cookies, account data, works, local paths, or other credentials | Checks asynchronously once after each plugin start and then about every 6 hours. The official default plugin set includes `notification`, so an enabled, successfully started plugin accesses it automatically | Uses the inherited host outbound route and can use an enabled global proxy. Disabling or uninstalling `notification` stops checks; stopping or reloading the plugin cancels future polling immediately |
| Inbox announcement detail page | `https://sywyar.github.io/PixivDownloader-Remote-Content/announcements/<message-id>/<locale>.html` | Displays a public announcement document in an isolated iframe when an administrator opens it. The browser still exposes normal public connection metadata such as IP/User-Agent, but the iframe uses `credentialless`, `no-referrer`, and sandboxing and carries no PixivDownloader session or third-party credentials | Browser-triggered only when an administrator opens an announcement that has a remote document. The background index check does not prefetch HTML | The browser connects directly and bypasses the Java backend proxy. If the host is blocked, the saved title and summary remain readable. Disabling `notification` withdraws the entire inbox feature |

## Pixiv downloading and browsing

The application host supplies the Pixiv HTTP transport, while the business request may be initiated by the download workbench, the novel plugin, or a userscript. The table distinguishes request ownership instead of attributing every shared-transport request to the core.

| Request owner | Destination | Purpose and main data sent | Trigger | Proxy and disable control |
| --- | --- | --- | --- | --- |
| `download-workbench` plugin | `https://www.pixiv.net/ajax/illust/**`, `/ajax/user/**`, `/ajax/search/artworks/**`, `/ajax/series/**`, `/ajax/collection/**`, `/ajax/follow_latest/illust`, `/rpc/index.php` | Retrieves artwork, ugoira, artist, series, search, collection, followed-work, and commission metadata. Requests that require authentication use the configured Pixiv cookie | Triggered by preview, Quick Fetch, download submission, scheduled tasks, metadata backfill, and the corresponding acquisition modes | Uses the host Pixiv HTTP capability and selected route. `download-workbench` is required; if missing or unavailable, the main download features are unavailable |
| `download-workbench` plugin | `https://www.pixiv.net/ajax/illusts/bookmarks/add` | Bookmarks an artwork after download; sends the artwork ID, visibility, tags, and Pixiv credentials | Triggered only when the bookmark-after-download option is enabled and the download has completed | Disable bookmark-after-download to stop it |
| `novel` plugin | `https://www.pixiv.net/ajax/novel/**`, `/ajax/novel/series/**`, `/ajax/novel/series_content/**`, `/ajax/user/**/novels`, `/ajax/user/**/novels/bookmarks`, `/ajax/search/novels/**` | Retrieves novel text, series data, author novel lists, bookmarks, and search results. Authenticated requests use the Pixiv cookie | Triggered by novel preview/download, series compilation, novel search, author novel views, and scheduled work | Uses the host Pixiv HTTP capability. Disabling `novel` withdraws the novel pages and capabilities |
| `novel` plugin | `https://www.pixiv.net/ajax/novels/bookmarks/add` | Bookmarks a downloaded novel | Triggered only when the corresponding option is enabled and the novel download has completed | Disable bookmark-after-download to stop it |
| Shared image transport in the application host, mainly called by `download-workbench` and `novel` | HTTPS `*.pximg.net`; thumbnails may also use `embed.pixiv.net` | Downloads originals, covers, thumbnails, ugoira archives, and embedded novel images. Requests normally include a Pixiv Referer, and URLs come from Pixiv API responses | Triggered by downloading, cover/thumbnail generation, previews, and novel file generation | Uses the Pixiv image route. Stop the related download or preview to avoid it |
| Root userscripts, not plugins | `https://www.pixiv.net/ajax/**`, HTTPS `*.pximg.net` | Reads artwork/novel data, bookmarks works, or downloads files directly in the Pixiv page. `GM_xmlhttpRequest` uses the browser/Pixiv login context | Triggered when the user invokes a script action, scrapes a page, or performs a local download | The browser/userscript manager connects directly and bypasses the host proxy. Disable or uninstall the script to stop it |
| Root userscripts, not plugins | User-configured PixivDownloader address, `http://localhost:6999` by default | Calls download submission, queue, status, and SSE APIs and sends the selected works and download options | Triggered when Java-backend, batch, or Toolbox userscript features are used | Local by default. If changed to a remote URL, the request goes to that server |

## `douyin` plugin

`douyin` is an on-demand plugin and is not preinstalled by the default Windows installer or Java standard archive. These requests occur only after it is installed, enabled, and used.

| Destination | Purpose and main data sent | Trigger | Proxy and disable control |
| --- | --- | --- | --- |
| `https://www.douyin.com/aweme/v1/web/**` | Retrieves user posts, liked works, mixes, searches, music works, collections, account information, and work details. It sends the Douyin cookie, query parameters, and browser-like request headers | Triggered by manual acquisition, preview, download, scheduled discovery, cookie probing, and Douyin gallery refresh | Uses the task route, source default, or host global route. Disable or uninstall `douyin` to stop all of it |
| HTTPS `douyin.com` / `*.douyin.com`, `iesdouyin.com` / `*.iesdouyin.com`, commonly `v.douyin.com` | Resolves a pasted short link with a limited redirect chain | Triggered when a Douyin short link is submitted for parsing | Uses the same route as the Douyin task |
| HTTPS `*.douyinvod.com`, `*.douyinpic.com`, `*.douyinstatic.com`, `*.amemv.com`, `*.byteimg.com`, `*.bytedance.com`, `*.bytecdn.cn`, `*.pstatp.com`, `*.snssdk.com`, plus the Douyin domains above | Downloads video, image, cover, and live-photo media. Exact URLs come from Douyin API responses and may use limited redirects | Triggered when a Douyin media download is performed | Uses the same route as the task. Stop the task or disable the plugin to prevent subsequent requests |

## `ai` plugin

The AI plugin uses the OpenAI-compatible protocol and sends requests to `/chat/completions` under the selected base URL. A request normally contains the text being translated or processed, a task-specific prompt, the model name, and the API key. Installing the plugin does not initiate this request. It is triggered only after valid settings are saved and a connection test, translation, or another AI feature is run.

| Preset | Default base URL |
| --- | --- |
| OpenAI | `https://api.openai.com/v1` |
| Anthropic-compatible endpoint | `https://api.anthropic.com/v1` |
| Gemini OpenAI-compatible endpoint | `https://generativelanguage.googleapis.com/v1beta/openai` |
| xAI | `https://api.x.ai/v1` |
| Mistral | `https://api.mistral.ai/v1` |
| Groq | `https://api.groq.com/openai/v1` |
| DeepSeek | `https://api.deepseek.com` |
| Alibaba Model Studio/Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Zhipu | `https://open.bigmodel.cn/api/paas/v4` |
| Moonshot | `https://api.moonshot.cn/v1` |
| Doubao/Volcengine Ark | `https://ark.cn-beijing.volces.com/api/v3` |
| Tencent Hunyuan | `https://api.hunyuan.cloud.tencent.com/v1` |
| Baidu Qianfan/ERNIE | `https://qianfan.baidubce.com/v2` |
| iFlytek Spark | `https://spark-api-open.xf-yun.com/v1` |
| MiniMax | `https://api.minimaxi.com/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| SiliconFlow | `https://api.siliconflow.cn/v1` |
| Ollama | `http://localhost:11434/v1` |
| LM Studio | `http://localhost:1234/v1` |

An administrator may replace the AI base URL with any compatible service, so the full destination range depends on the saved settings. Proxy use is selected by the AI settings. Remove the API key, clear/disable the settings, or disable `ai` to stop these requests.

## `tts` plugin

TTS requests send the text to be narrated, voice/model options, and the corresponding service credentials. They are triggered only by preview, narration generation, voice-list refresh, connection testing, or actual novel narration.

| Engine | Destination and purpose | Trigger details |
| --- | --- | --- |
| Edge TTS | `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1` for synthesis; `/consumer/speech/synthesize/readaloud/voices/list` on the same host for voices | Triggered by synthesis, preview, or refreshing voices |
| Edge TTS version probe | `https://edgeupdates.microsoft.com/api/products?view=enterprise` | Triggered when Edge TTS needs fresh client-version information; it is not separate telemetry |
| Xiaomi MiMo | `https://api.xiaomimimo.com/v1/chat/completions` | Triggered when MiMo synthesis is used |
| Fish Audio | `https://api.fish.audio/v1/tts` | Triggered when Fish synthesis is used |
| MiniMax | Defaults to `https://api.minimax.io/v1/t2a_v2`; the China endpoint can be configured as `https://api.minimaxi.chat/v1/t2a_v2` | Triggered when MiniMax synthesis is used |
| ElevenLabs | `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}` | Triggered when ElevenLabs synthesis is used |
| Alibaba Model Studio/Qwen | Defaults to `https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`; the international endpoint can be configured as `https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation` | After generation, the plugin also downloads the temporary audio URL returned by the service. Its host is service-controlled |
| Doubao/Seed-TTS | `https://openspeech.bytedance.com/api/v1/tts` | Triggered when the Doubao engine is used |
| VoxCPM and CosyVoice | Administrator-configured self-hosted OpenAI-compatible endpoint, normally `{base-url}/audio/speech`; VoxCPM also probes `{base-url}/models` | The default base URL is empty, so no request occurs until configured |

Each engine can have its own base URL. Proxy behavior depends on the selected outbound route and engine settings. Disable `tts`, disable the engine, or clear its settings to stop these requests.

## `push` plugin

The Push plugin connects only after a channel is enabled and a notification event or “send test message” occurs. Requests contain the notification title/body and channel credentials. Some services place the token or key in the URL.

| Channel | Fixed or default destination |
| --- | --- |
| Bark | `https://api.day.app/push`; the server can be replaced with a self-hosted Bark instance |
| DingTalk bot | `https://oapi.dingtalk.com/robot/send?access_token=...` |
| Feishu bot | `https://open.feishu.cn/open-apis/bot/v2/hook/{key}` |
| WeCom bot | `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...` |
| Telegram Bot | `https://api.telegram.org/bot{token}/sendMessage` |
| PushPlus | `https://www.pushplus.plus/send` |
| ServerChan Turbo | `https://sctapi.ftqq.com/{key}.send` |
| ServerChan 3 | `https://{uid}.push.ft07.com/send/{key}.send` |
| Custom webhook | Any `http://` or `https://` URL entered by the administrator |

Disable the channel, remove its credentials, or disable `push` to stop requests. For a custom webhook, the administrator who enters the URL is responsible for the destination and its handling of notification data.

## `mail` plugin

The Mail plugin uses SMTP for configuration tests and business notifications. The connection carries SMTP credentials and sends the recipient, subject, and message body to the selected provider. It connects only for a test send or an actual notification delivery.

| Preset provider | Default SMTP destination |
| --- | --- |
| NetEase | `smtp.163.com:465`, `smtp.126.com:465`, `smtp.yeah.net:465`, `smtp.qiye.163.com:465` |
| Tencent | `smtp.qq.com:465`, `smtp.exmail.qq.com:465` |
| Sina | `smtp.sina.com:465` |
| Gmail / Google Workspace | `smtp.gmail.com:587` |
| Outlook | `smtp-mail.outlook.com:587` |
| Microsoft 365 | `smtp.office365.com:587` |
| iCloud | `smtp.mail.me.com:587` |
| Yahoo | `smtp.mail.yahoo.com:465` |
| Alibaba enterprise mail | `smtp.qiye.aliyun.com:465` |

An administrator may enter any SMTP host and port and may configure a separate SOCKS proxy. Disable mail notifications, remove the mail settings, or disable `mail` to stop connections.

## Plugin market, FFmpeg, and userscript updates

| Request owner | Destination | Purpose | Trigger and default state |
| --- | --- | --- | --- |
| Plugin market in the application host | `https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json`; packages normally use GitHub Releases and may redirect to `*.githubusercontent.com` | Downloads the official catalog and user-selected plugin packages, then verifies signatures, SHA-256, and size | `plugin-catalog.enabled` defaults to `false`; triggered only after an administrator enables the market and refreshes or installs |
| Plugin market in the application host | Administrator-configured HTTPS manifest and package URLs declared by it | Uses a third-party or self-hosted plugin repository | Triggered only after the repository is configured and enabled. A direct-strict policy may deliberately bypass the global proxy |
| FFmpeg installer in the application host | `https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip` and its Release CDN redirects | Downloads a Windows LGPL FFmpeg build | Triggered only when automatic FFmpeg installation is explicitly selected in the GUI; startup does not download it |
| Userscript manager, not a plugin | `https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/*.user.js` | Checks and downloads updates for the six standalone userscripts | Triggered by Tampermonkey or another manager according to its update policy. Disable automatic updates or uninstall the script to stop it |
| All-in-One userscript manager, not a plugin | `https://github.com/Sywyar/PixivDownloader/releases/latest/download/Pixiv%20All-in-One.user.js` | Checks or downloads the generated combined userscript | Triggered only after that release script is installed |

## Optional layout survey in `download-workbench` (PostHog)

The layout feedback code belongs to the `download-workbench` plugin. The PostHog JavaScript SDK is bundled as a static plugin resource and is not downloaded from a CDN.

- The tracked public configuration currently has `enabled: false` with empty `apiHost` and `uiHost`, so the default source build does not contact PostHog.
- The survey is initialized only when a distribution build explicitly injects a complete project token, survey ID, `apiHost`, and `uiHost` and enables it.
- When enabled, the browser contacts the configured PostHog API/UI hosts directly and may send survey display state, responses, identity mode, and page interaction information. It bypasses the host outbound proxy.
- The `pixiv-layout-feedback/public-config.js` included in the distribution is authoritative for the destination; it cannot be inferred from standard runtime settings.

## Official plugins without fixed public network destinations

The following plugins do not add a fixed third-party network destination:

- `stats`: reads the local database and calculates statistics.
- `duplicate`: reads local files and hash data for duplicate detection.
- `gallery`: browses local download records and local media.
- `gui-theme`: provides local GUI theme resources.
- `recovery-sentinel`: a recovery-mode test fixture that is not included in standard user distributions.

Their pages may still call same-origin APIs on the current PixivDownloader instance, which is not a third-party public connection.

## Local, same-origin, and administrator-defined destinations

- The GUI, web pages, and plugin frontends call `/api/**`, static resources, and SSE on the current PixivDownloader instance. The desktop GUI normally connects to `http://localhost:{port}` or `https://localhost:{port}`.
- Ollama, LM Studio, VoxCPM, CosyVoice, and userscript backends may be local services. If their base URL is changed to a remote address, that remote service becomes a new data recipient.
- The Image Classifier's `server.url` defaults to `http://localhost:6999` but may point to another administrator-configured PixivDownloader instance.
- Custom webhook, AI/TTS base URL, update manifest, plugin repository, Bark, SMTP, SOCKS, and proxy endpoints are administrator-defined and cannot form a closed fixed-domain allowlist.
- The root `cors-js-runner.html` developer tool requests any URL entered by its operator. It is not part of the standard user runtime.

## Proxy applicability

Configuring the host proxy does not mean that all network traffic uses it:

- Pixiv, updates, FFmpeg, the official plugin repository, and some plugin requests select a proxy through host or task-level routing.
- AI, TTS, and Douyin may have feature-specific or task-specific direct/proxy choices.
- A custom plugin repository using the direct-strict policy deliberately bypasses the global proxy; custom policy behavior follows its repository settings.
- Userscripts, Google Fonts, and PostHog are browser requests and bypass the Java backend proxy.
- SMTP uses the Mail plugin's own connection settings and can use a separate SOCKS proxy.
- When an HTTP/SOCKS proxy is enabled, the application first connects to the configured proxy host and port, and that proxy contacts the final service.

## Network access during development, build, and release

These destinations are not used during the standard runtime of an installed application:

| Tool or workflow | Destination or source | Purpose |
| --- | --- | --- |
| Git and release scripts | Current `origin`, `https://api.github.com` (or `GITHUB_API_URL`), and GitHub Releases | `fetch`, remote-ref checks, quality-gate auditing, and application/plugin publishing |
| GitHub Actions | GitHub Actions, Artifact, and Release services plus referenced `actions/*` and `softprops/action-gh-release` actions | CI, building, artifact upload, and publishing |
| Maven / Maven Wrapper | `https://repo.maven.apache.org/maven2` | Downloads Maven 3.9.11, Java dependencies, and build plugins |
| npm | `https://registry.npmmirror.com` in the current lockfile | Installs Node build/check dependencies |
| Docker | Configured OCI registry, normally resolving `eclipse-temurin:17-jre`, plus Debian package sources configured by the base image | Pulls the base image and installs FFmpeg, curl, and other system packages |
| Windows CI | Configured Chocolatey sources | Installs packaging tools such as Inno Setup |

Build-machine, proxy, mirror, or package-manager settings may replace the final download host. These transitive destinations therefore cannot be listed as a stable complete domain set from repository source alone.

## Links and non-request URL references

The application and documentation also contain links to GitHub, Releases, online documentation, Tampermonkey, and license sites. These links generate network traffic only when followed or when the browser loads the referenced resource. XML namespaces, POM schema locations, URLs in license text, and example domains are not automatic network request destinations.
