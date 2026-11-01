# 操作验证码插件

官方 `captcha` 插件提供 Turnstile、reCAPTCHA v2、ALTCHA SHA-256、ALTCHA PBKDF2、字符输入和滑块拼图六种表单验证组件。插件按需安装，普通发行包不预装，离线全量包包含。将组件放入兼容表单并发布后，服务端会强制验证证明。

验证码用于增加自动化提交的成本，不能代替网络层 DDoS 防护、反向代理限流或访问控制。相关外部连接见[网络访问与第三方服务](/zh-cn/network-access)。

## 选择验证方式

| 方式 | 用户看到的交互 | 验证在哪里完成 | 需要准备什么 |
| --- | --- | --- | --- |
| Turnstile 在线验证 | 托管模式下，点击“开始验证”后显示 Cloudflare 控件，由其决定是否需要进一步交互 | 浏览器加载 Cloudflare 控件，服务端调用 Cloudflare 验证票据 | Cloudflare 账号、Sitekey、Secret key 和允许的主机名 |
| ALTCHA SHA-256 | 组件名为“ALTCHA 离线验证”；点击后等待浏览器计算，完成时显示“已完成验证” | 浏览器完成 SHA-256 工作量证明，本机服务签发挑战并验签 | 首次启用自动生成本地签名密钥，无需第三方账号 |
| ALTCHA PBKDF2 | 组件名为“ALTCHA PBKDF2 离线验证”；交互与 SHA-256 相同 | 浏览器通过 PBKDF2/SHA-256 密钥派生完成工作量证明，本机服务验签 | 与 SHA-256 共用本地签名密钥，无需第三方账号 |
| 字符验证码（离线） | 输入图片中的 5 位字符并确认 | Hutool 本地生成，服务端核对答案 | 无额外配置 |
| 滑块拼图（离线） | 拖动滑块对准缺口并确认 | Tianai 本地生成，服务端核对位置 | 无额外配置 |
| reCAPTCHA v2 | 勾选复选框，可能出现识图题 | Google 控件与 Siteverify | v2 Checkbox 的站点公钥、密钥及主机名 |

两种 ALTCHA 都不要求识别图片或输入字符。“离线”指不依赖第三方验证码服务，浏览器仍需连接当前 PixivDownloader 实例。算法、参数和设备共同决定计算耗时，不能仅凭 PBKDF2 名称判断它一定更快或防护更强；机制见 [ALTCHA 工作量证明说明](https://altcha.org/docs/integration/proof-of-work-captcha/)。

通常在一个表单中选择一种方案。**同一表单放入多种验证码时，全部都必须完成**，并非任选其一，也不会在 Turnstile 不可用时自动改用 ALTCHA。

## 安装并绑定表单

在“设置 → 操作验证码”中，通过“验证组件”下拉框选择 ALTCHA、Cloudflare Turnstile 或 Google reCAPTCHA v2，查看各自的配置。切换选项会保留未保存的输入，不会启用或停用组件；表单使用哪种验证仍由页面编辑器中的绑定决定。

1. 在[插件管理与市场](/zh-cn/plugin-management)中安装并启用“操作验证码”和“页面编辑器”。
2. ALTCHA 保留自动生成的密钥即可；字符和滑块无需额外配置；在线方案先按下文配置。
3. 打开[页面编辑器](/zh-cn/page-editor)，选择登录页等兼容页面，从“验证码”分类拖入所需组件。确认右侧“保护的操作”已绑定到目标表单；移到表单外或出现歧义时，应先修正绑定。
4. 发布页面，并核对发布时列出的资源权限。只保存草稿不会启用正式页面的验证码要求。
5. 用未登录窗口打开正式页面。登录时先填写用户名、密码，再点击“开始验证”；全部验证完成后，点击“登录”。

编辑与预览中的按钮不会运行真实验证。正式页面在证明未完成时不提交登录请求，服务端也会在核对密码和创建会话前验证证明。修改用户名、密码或“保持登录状态”会清除本次证明；密码错误、验证失败或过期后需要重新验证。

正常安装和启用后刷新页面即可，插件支持热加载。若更新的是开发环境中已加载的 Java 类或宿主代码，需要重启开发实例后再刷新页面。

## 配置 ALTCHA 离线验证

两种 ALTCHA 使用同一个“ALTCHA 签名密钥”。首次启用时自动生成 32 字节随机值，由宿主加密保存在当前实例的 `config/credentials/captcha.properties`；自定义配置根目录时随该目录变化。后续启用和重启复用已有密钥，没有自动到期时间，通常无需手工修改。

手工替换须至少 32 字符；设置中的密码框留空保存会保留已存值。“清除已保存凭据”会使正在运行的 ALTCHA 验证拒绝执行，下次重新启用插件时才会生成新密钥。已有密钥无效、存储损坏或保存失败时，ALTCHA 验证会拒绝执行。

Worker 随插件提供，不需要外部 CDN。浏览器计算依赖 Web Crypto，应通过 HTTPS 或浏览器认可的本机安全来源访问；使用普通 HTTP 的局域网 IP 时，该 API 可能不可用。参见 [Web Crypto 的安全上下文要求](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto)。

## 字符输入与滑块拼图

“字符验证码（离线）”使用 Hutool 生成图片，输入其中的 5 位字母或数字（不区分大小写）后点击“确认输入”。“滑块拼图（离线）”使用 Tianai 生成拼图，拖动滑块将拼图对准缺口后点击“确认位置”；也可以聚焦滑块后用方向键调整、Enter 确认。

这两种方案不需要第三方账号或额外密钥。图片由当前实例生成，答案只保存在服务端内存中；挑战 120 秒有效，每次提交只检查一次。看不清时点击“换一张”，这会清除当前表单的全部验证结果。过期或重启后需重新获取图片。“已确认”表示答案已随表单准备好，是否正确由服务端在提交时判断。

字符识别和滑块位置校验只提供基础防自动化能力。当前滑块没有行为识别模型；应结合请求限流使用。需要适配视力受限用户时，可选无需识图的 ALTCHA 方式。

## 申请并配置 Google reCAPTCHA v2

1. 打开 [Google reCAPTCHA 管理控制台](https://www.google.com/recaptcha/admin/create)，创建 **v2 → “我不是机器人”复选框** 类型的站点。不要选 v3 或 Invisible。
2. 添加实际域名；本地测试添加 `localhost`，用 `http://localhost:6999` 访问。只填主机名，不填协议、端口或路径。
3. 在“设置 → 操作验证码”填写“reCAPTCHA 站点公钥”（Site key）、“reCAPTCHA 验签密钥”（同一站点的 Secret key）、“reCAPTCHA 预期主机名”（例如 `localhost`），关闭“允许 reCAPTCHA 官方测试配置”。密钥经加密保存。
4. 拖入“reCAPTCHA v2 在线验证”并发布。修改公钥、预期主机名或测试模式后须重新发布；仅轮换密钥会使旧挑战失效。

浏览器和服务端都须能访问 Google。是否弹出图片选择题由 Google 决定，不能保证每次都出现。插件使用经典 Siteverify 接口，不接入 Enterprise Assessments。[显示配置](https://developers.google.com/recaptcha/docs/display) · [服务端校验](https://developers.google.com/recaptcha/docs/verify)

仅检查集成时，可将公钥设为 `6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI`、密钥设为 `6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe`，开启官方测试配置并重新发布。测试时预期主机名可留空，插件校验 Google 返回的 `testkey.google.com`；无需拥有或访问该域名。这组[官方测试密钥](https://developers.google.com/recaptcha/docs/faq)不提供防自动化保护，也不能用来验收真实识图挑战。正式使用时换回自己的密钥、实际主机名并关闭测试配置。

## 申请并配置 Turnstile

### 获取 Sitekey 和 Secret key

1. 登录 [Cloudflare 控制台](https://dash.cloudflare.com/)，打开 **Turnstile → Add widget**。
2. 填写控件名称，例如 `PixivDownloader`。
3. 在 Hostname management 中添加实际访问域名，例如 `download.example.com`。
4. 验证模式可选 **Managed（托管）**，由 Cloudflare 决定是否要求用户交互。
5. 点击 Create，复制该控件的 Sitekey 和 Secret key。Sitekey 是公开的站点标识，Secret key 仅供服务端验签，应妥善保存。[官方创建教程](https://developers.cloudflare.com/turnstile/get-started/widget-management/dashboard/)

使用 Turnstile 不要求网站经过 Cloudflare 代理；其浏览器控件和服务端验签仍需要访问 Cloudflare。[官方接入说明](https://developers.cloudflare.com/turnstile/get-started/)

### 填入软件设置

打开“设置 → 操作验证码”：

| 设置项 | 填写内容 |
| --- | --- |
| Turnstile 站点公钥 | 上一步的 Sitekey |
| Turnstile 验签密钥 | 同一个控件的 Secret key |
| Turnstile 预期主机名 | 用户实际访问的主机名，例如 `download.example.com` |
| 允许官方测试配置 | 使用自己申请的密钥时关闭 |

主机名不含协议、端口或路径，不能填写 `https://download.example.com:6999/login.html`。Cloudflare 的允许域名和软件的预期主机名都要配置；本插件对验证响应的主机名进行精确匹配。[主机名要求](https://developers.cloudflare.com/turnstile/additional-configuration/hostname-management/)

保存后，将 Turnstile 组件放入表单并发布。修改站点公钥、预期主机名或测试模式后，已有页面也需要重新发布。仅轮换验签密钥会使旧挑战失效，需要重新验证。普通设置保存在 `config/plugins/captcha.properties`，密钥保存在加密凭据文件中，页面文档不保存密钥。

### 在本机使用自己申请的密钥

为本地开发单独创建控件，并按浏览器地址填写：

| 浏览器访问地址 | Cloudflare 允许的主机名 | 软件的预期主机名 |
| --- | --- | --- |
| `http://localhost:6999` | `localhost` | `localhost` |
| `http://127.0.0.1:6999` | `127.0.0.1` | `127.0.0.1` |
| `https://download.example.com` | `download.example.com` | `download.example.com` |

端口改变不影响主机名；切换 `localhost` 和 `127.0.0.1` 则需要相应修改配置并重新发布。**本地运行不需要开启“允许官方测试配置”。** Cloudflare 允许为开发控件添加本机主机名，建议正式控件不允许这些地址。[官方本地测试说明](https://developers.cloudflare.com/turnstile/troubleshooting/testing/)

### 使用官方测试密钥检查提交流程

若只检查组件和提交流程，可使用 Cloudflare 公开的“始终通过”测试值：

| 设置项 | 测试值 |
| --- | --- |
| Turnstile 站点公钥 | `1x00000000000000000000AA` |
| Turnstile 验签密钥 | `1x0000000000000000000000000000000AA` |
| Turnstile 预期主机名 | `example.com`，本插件的官方测试约定 |
| 允许官方测试配置 | 开启 |

保存并重新发布后再测试。这里的 `example.com` 用于校验测试响应，不要求访问或拥有该域名；页面仍可在 `localhost` 打开。测试公钥必须配测试密钥。上述值来自 [Cloudflare 测试文档](https://developers.cloudflare.com/turnstile/troubleshooting/testing/)，**不提供真实防自动化保护**。正式使用前换成自己的密钥和实际主机名、关闭测试配置并重新发布。

## 无法验证时检查什么

| 现象或日志 | 处理方式 |
| --- | --- |
| 编辑器中有组件，正式页面没有 | 确认发布成功，而非仅保存草稿；刷新正式页面 |
| `CAPTCHA_CREDENTIAL_MISSING` / `CAPTCHA_CREDENTIAL_INVALID` | 按日志中的配置键检查密钥是否缺失或无效；不要将密钥贴入日志或问题反馈 |
| `CAPTCHA_CONFIG_INVALID` | 检查日志指出的公钥、主机名或测试模式字段，确保密钥组合一致 |
| `PUBLISHED_POLICY_CHANGED` | 核对配置后重新发布对应页面 |
| `CAPTCHA_REMOTE_UNAVAILABLE` 或控件加载失败 | 检查浏览器和服务端到 Cloudflare 或 Google 的网络、代理与拦截情况 |
| `HOSTNAME_MISMATCH` | 检查浏览器地址、验证码服务允许的域名与软件预期主机名 |
| 加入 ALTCHA 后仍提示某验证策略不可用 | 检查表单是否同时要求未配置的 Turnstile 或 reCAPTCHA；只需离线验证时移除相应组件并重新发布 |
| 完成后又要求验证 | 检查是否修改表单输入、提交过错误密码、挑战已过期或插件配置已变化 |

“必要的登录验证策略不可用”是汇总提示，应查看日志中的具体原因和配置键。验证码插件停用或缺失时，已发布的验证要求仍会阻止受保护的操作；需要取消要求时应修改页面并重新发布，不能通过停用插件绕过。
