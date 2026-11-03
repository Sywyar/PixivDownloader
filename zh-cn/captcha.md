# 操作验证码

官方 `captcha` 插件为登录等受保护表单提供六种验证组件：Turnstile、reCAPTCHA v2、ALTCHA SHA-256、ALTCHA PBKDF2、字符验证码（离线）和滑块拼图（离线）。普通发行包不预装，可从插件市场按需安装或使用离线全量包。将组件拖入兼容表单并发布页面后，前后端会强制核验挑战证明。

验证码用于提高自动化提交成本，不能替代网络层 DDoS 防护、反向代理限流或访问控制。相关外部请求说明见[网络访问与第三方服务](/zh-cn/network-access)。

## 验证方式对比

| 组件 | 用户交互 | 验证位置 | 前置准备 |
| --- | --- | --- | --- |
| ALTCHA SHA-256（离线） | 点击后等待浏览器完成 SHA-256 工作量证明 | 本机签发挑战并验签，无需第三方服务 | 首次启用自动生成本地签名密钥 |
| ALTCHA PBKDF2（离线） | 点击后等待浏览器完成 PBKDF2/SHA-256 密钥派生证明 | 本机签发挑战并验签，无需第三方服务 | 与 SHA-256 共用本地签名密钥 |
| 字符验证码（离线） | 输入图片中的 5 位字母或数字（不区分大小写）并确认 | Hutool 本机生成图片，服务端内存核对 | 无额外配置 |
| 滑块拼图（离线） | 拖动滑块（或用方向键 + Enter）将拼图对准缺口并确认 | Tianai 本机生成拼图，服务端内存核对 | 无额外配置 |
| Turnstile 在线验证 | 托管模式下点击「开始验证」加载 Cloudflare 控件 | 浏览器加载 Cloudflare，服务端调用 Siteverify | Cloudflare 账号、Sitekey、Secret key 与允许主机名 |
| reCAPTCHA v2 在线验证 | 勾选复选框，按 Google 策略可能出现识图题 | 浏览器加载 Google 控件，服务端调用 Siteverify | Google reCAPTCHA v2 Checkbox 公钥、密钥与主机名 |

!> 通常每个表单选择一种组件即可。**同一表单放入多个验证码组件时，必须全部完成才能提交**，不会在某个在线服务不可用时自动回退为离线组件。

## 安装并绑定到表单

1. 在[插件管理](/zh-cn/plugin-management)中安装并启用「操作验证码」与「页面编辑器」。
2. 若使用 Turnstile 或 reCAPTCHA v2，先在桌面「设置 → 操作验证码」中完成下方密钥与主机名配置并保存（离线组件无需预先配置）。
3. 打开[页面编辑器](/zh-cn/page-editor)，选择登录页等目标页面，从左侧「添加组件 → 验证码」拖入所需组件，确认右侧「保护操作」已绑定到目标表单。
4. 点击「发布」并确认所需的外部资源权限（仅保存草稿不会启用正式页面的验证码保护）。
5. 用未登录窗口打开正式登录页，填写用户名与密码后完成验证码，再点击「登录」。

修改用户名、密码或「保持登录状态」会清空当次证明；密码错误、验证失败或挑战过期后需重新验证。

## 离线组件配置与说明

- **ALTCHA（SHA-256 / PBKDF2）**：首次启用自动生成 32 字节随机签名密钥，由宿主加密保存在 `config/credentials/captcha.properties`（无到期时间，日常无需修改；手动替换须至少 32 字符，清除凭据后需重新启用插件才会生成新密钥）。Worker 脚本随插件内置，无需外部 CDN；浏览器计算依赖 [Web Crypto `SubtleCrypto`](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto)，请通过 `localhost`、`127.0.0.1` 或 HTTPS 访问（普通局域网明文 HTTP 下浏览器可能禁用 Web Crypto）。
- **字符输入与滑块拼图**：图片在本机生成，挑战有效期 120 秒、单次消费；点击「换一张」会重置当前表单的全部验证状态。这两种方式仅提供基础防自动化能力（滑块不含行为轨迹模型），建议结合请求限流使用；无障碍场景优先使用无需识图的 ALTCHA。

## 配置 Cloudflare Turnstile

1. 登录 [Cloudflare 控制台](https://dash.cloudflare.com/)，进入 **Turnstile → Add widget**，添加实际访问主机名（如 `localhost` 或 `download.example.com`，不含协议、端口或路径），模式选 **Managed**，创建后获取 Sitekey 与 Secret key。
2. 在桌面「设置 → 操作验证码」选择 Cloudflare Turnstile，填写**站点公钥**（Sitekey）、**验签密钥**（Secret key）和**预期主机名**（须与浏览器实际访问主机名精确一致，切换 `localhost` 与 `127.0.0.1` 需同步修改），关闭「允许官方测试配置」并保存。
3. 在页面编辑器发布绑定了 Turnstile 的页面（修改公钥、预期主机名或测试开关后必须重新发布页面；仅轮换密钥无需重发页面）。

**官方测试密钥（仅用于联调提交流程，不提供防自动化保护）**：公钥填 `1x00000000000000000000AA`，密钥填 `1x0000000000000000000000000000000AA`，预期主机名填 `example.com`，开启「允许官方测试配置」并重新发布页面。正式上线前务必换回真实密钥、关闭测试配置并重新发布。

## 配置 Google reCAPTCHA v2

1. 在 [Google reCAPTCHA 管理控制台](https://www.google.com/recaptcha/admin/create)创建 **v2 →「我不是机器人」复选框** 站点（勿选 v3 或 Invisible），添加实际访问主机名（本地测试填 `localhost`）。
2. 在桌面「设置 → 操作验证码」选择 Google reCAPTCHA v2，填写**站点公钥**、**验签密钥**与**预期主机名**，关闭「允许 reCAPTCHA 官方测试配置」并保存。
3. 在页面编辑器拖入「reCAPTCHA v2 在线验证」并发布页面。

**官方测试密钥（仅用于联调，不提供真实保护）**：公钥填 `6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI`，密钥填 `6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe`，预期主机名可留空（校验 `testkey.google.com`），开启「允许 reCAPTCHA 官方测试配置」并重新发布页面。

## 故障排查

| 现象或日志错误码 | 排查与处理 |
| --- | --- |
| 编辑器有组件，正式登录页没有 | 确认已点击「发布」而非仅保存草稿，随后刷新正式页面 |
| `CAPTCHA_CREDENTIAL_MISSING` / `CAPTCHA_CREDENTIAL_INVALID` | 按日志指出的键名检查密钥是否为空或长度不足（切勿把密钥贴入日志或 Issue） |
| `CAPTCHA_CONFIG_INVALID` | 检查公钥、预期主机名与测试模式开关是否匹配（测试密钥必须搭配测试开关） |
| `PUBLISHED_POLICY_CHANGED` | 修改验证码设置后，重新在页面编辑器发布对应页面 |
| `CAPTCHA_REMOTE_UNAVAILABLE` 或控件不加载 | 检查浏览器与后端到 Cloudflare / Google 的网络与代理配置 |
| `HOSTNAME_MISMATCH` | 核对浏览器地址栏主机名、厂商后台允许域名与插件「预期主机名」三者一致 |
| 加入 ALTCHA 后仍提示策略不可用 | 检查表单是否同时残留了未配置的 Turnstile 或 reCAPTCHA 组件；移除多余组件后重新发布 |

停用或卸载 `captcha` 插件不会绕过已发布页面的验证要求（受保护操作会保持拒绝）；如需取消验证码，必须在页面编辑器中移除组件并重新发布页面。

登录与邀请码兑换共用宿主挑战和提交协议；业务接口直接调用也必须满足正式要求。只配置实际使用的在线方案，未使用的 Turnstile 或 reCAPTCHA 无需填写测试密钥。新表单的动作关系配置见[页面编辑器](/zh-cn/page-editor)。
