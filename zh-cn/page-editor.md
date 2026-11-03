# 页面编辑器与插件组件

页面编辑器（`page-editor` 插件，需 SDK 1.4+）用于可视化编排登录页等受支持页面的布局、响应式样式、自定义组件与表单验证组件。安装并启用插件后，以管理员身份从导航进入「页面编辑器」。

## 编辑、发布与恢复页面

1. 选择目标页面，从左侧「添加组件」将布局、内容或插件组件拖入画布（键盘操作可聚焦组件按 `Enter` / `Space` 打开详情，再选「添加到页面」）。
2. 在左侧「结构」或画布中选中实例，在右侧调整属性、布局与外观，支持容器嵌套、上移/下移、移出、删除及窄视口响应式覆盖。
3. 点击「保存草稿」或「预览」（预览使用页面真实样式，但不提交登录或业务写请求；「返回编辑」保留未保存修改）。
4. 点击「发布」替换正式页面（公开页面上的组件配置与属性默认值随之公开，请勿填入密码或令牌）。
5. 顶部「更多」菜单提供「重载草稿」「恢复上一发布版」（同时替换正式版与草稿）和「恢复页面默认布局」（仅替换未保存画布）。

正式页面的运行与操作保护不依赖编辑器保持开启。正式页面上的「使用默认布局」仅改变当前浏览器呈现，不会清除服务端验证要求。当页面包含缺席或版本不兼容的插件组件时，仍可保存草稿，但须恢复对应插件或完成配置迁移后才能发布。

## 表单验证与资源授权

将验证码组件拖入页面 owner 声明的表单区域后，唯一兼容目标会自动绑定；存在多个目标时需在右侧「保护操作」中明确指定。同一表单绑定多个验证组件时，全部策略均须完成。

发布前会列出受影响的操作、策略变化及组件所需的外部资源来源。确认发布后，服务端暂停目标操作的新请求、等待在途动作排空，再原子提交页面、绑定与 CSP 资源权限（同时保留 `setup.login-verification-policies` 静态登录保护要求）。

正式发布状态保存在 `data/web-publications/`，登记文件为 `config/web-publications.enrolled`，切勿当作缓存删除。需离线修复时，先停止程序，在发行目录查询并核对候选文件与 SHA-256 后执行恢复：

```text
java -jar "<boot-jar>" --no-gui --web-publications
java -jar "<boot-jar>" --no-gui --web-publications --publication-restore=<candidate.json> --publication-sha256=<sha256>
```

## 页眉、页脚与自定义 CSS

默认布局包含页眉、正文（纵向弹性伸展占满剩余空间）与页脚；添加备案号等信息时，选中「页脚」插入「文本」或「链接」即可。登录卡片内的标题、用户名、密码与按钮为受控必需区域，可在控件之间插入文本或组件，但不能删除、复制或移出所属父区域；勾选「显示默认隐藏的区域」可编辑邀请码卡片。

选中组件后，在右侧「样式 → 自定义 CSS」中使用内置 CodeMirror 编辑属性声明（支持行号、补全、错误定位与右上角全屏切换），按 `Ctrl/Cmd+Enter` 或点击「应用样式」生效：

```css
padding: 16px;
border-radius: 8px;
```

- 每个属性值最长 128 字符，作用于选中组件根元素及当前画布宽度（窄屏可单独设置覆盖，清空并应用可移除覆盖）。
- 不接受选择器、注释、`@media` / `@import`、外部资源 URL、`!important`、`display: none` 或自定义 CSS 变量。保存、预览或发布前须先应用或点击「放弃修改」。
- 受支持属性：`display`、`flex-direction`、`flex-wrap`、`justify-content`、`align-items`、`gap`、`padding`（及四个方向）、`margin`（及四个方向）、`width`、`min-width`、`max-width`、`min-height`、`font-size`、`font-weight`、`line-height`、`text-align`、`color`、`background-color`、`border`、`border-color`、`border-width`、`border-style`、`border-radius`、`box-shadow`、`grid-template-columns`、`flex`、`order`。

## 自定义 HTML / CSS / JavaScript 组件库

在「添加组件」点击「新建组件」或「管理组件库」（打开独立标签页，不影响原页面草稿）：

1. 在「源码」编辑 HTML 片段、CSS 与可选 JavaScript 函数体；在「属性定义」添加实例字段（支持文本 `string`、数值 `number`、开关 `boolean`）：

   ```json
   {"title":{"type":"string","label":"Title","default":"Hello"}}
   ```

   例如 HTML 填 `<p></p>`，JavaScript 填 `root.querySelector('p').textContent = context.properties.title;`（接收 `root` 与含 `properties`、`mode` 的 `context`，可返回 `update(context)` 与 `destroy()`）。
2. 点击「运行预览」检查效果，再点击「发布」保存为不可变版本并加入组件面板。
3. 将组件拖入页面并发布页面。后续组件发布新版不会自动篡改已发布页面；需在页面实例属性中选择新版本、点击「使用所选版本并重置属性」后重新发布页面。
4. 「撤回组件」可停止现有实例渲染并保留源码与历史版本。

自定义组件运行在无 `allow-same-origin` 的沙箱 iframe 中，CSP 禁止 `fetch`/`XHR`、外部脚本与外部样式，HTML 内禁止 `<script>`、`<style>`、`<form>`、内嵌页、外部资源与内联事件属性。组件库保存在 `data/page-editor/components/`（单份源码上限 1 MiB，最多 128 个组件、单组件最多 4,096 个版本与 64 个属性）。

## 开发第三方插件组件与接入页面

插件可通过公开契约贡献组件与可编辑页面，无需修改宿主或编辑器源码（完整验收示例见主仓库 [`page-components` 夹具](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-app/src/test/resources/page-components)）：

| 扩展点 | 职责与约束 |
| --- | --- |
| 业务契约与 `sdk-tools` | 在独立 API 包声明操作与配置 record，生成 JSON Schema、浏览器客户端与 Java HTTP adapter |
| `PageContribution.components()` / `componentCategories()` | 声明 `PageComponent`（稳定 `owner:type`、正整数配置版本、模块 URL、schema/defaults、`publicRenderable`、端口）与分类 `owner:local-id`；实现 `validate` 与可选 `migrate` |
| 渲染与属性编辑模块 | 导出 `mount(root, context)`（及复杂属性的 `mountProperties(root, context)`）；使用 `context.fetch`、`context.signal` 与 `context.onCleanup`，不暴露 GrapesJS 对象，禁止在 `edit`/`preview` 模式直接发业务写请求 |
| `PageContribution.pages()` | 声明 `EditablePage`（页面 ID、路径、`regions`、`defaultProject`、`previewModuleUrl`、最多 16 份同 owner CSS）；前端调用 `/js/pixiv-pages.mjs` 的 `attachPage`（参考核心 `login/login-page.mjs`） |

```js
import {createClient} from './generated/my.contract.mjs';

export function mount(root, context) {
    const client = createClient(context.fetch);
    const button = document.createElement('button');
    button.textContent = context.t('run');
    button.disabled = context.mode !== 'display';
    context.on(button, 'click', async () => {
        try {
            const result = await client.run(context.properties, {signal: context.signal});
            if (!context.signal.aborted) context.emit('result', result);
        } catch (error) {
            if (!context.signal.aborted) root.dataset.failed = 'true';
        }
    });
    root.append(button);
    return {
        update(properties) { /* 更新显示，不执行写操作 */ },
        destroy() { root.replaceChildren(); }
    };
}
```

每份页面文档上限 1 MiB、512 个节点、24 层嵌套、256 条端口连线、标题 256 字符。独立浏览器与生命周期验收命令：

```powershell
mvn -pl pixivdownload-plugin-page-editor,pixivdownload-app,pixivdownload-sdk-tools,pixivdownload-plugin-download-workbench -am package '-DskipTests' '-Dexec.skip=true'
node scripts/ci/page-editor-acceptance.mjs target/page-editor-acceptance
```

## 官方验证码插件

官方 `captcha` 插件提供 Turnstile、reCAPTCHA v2、ALTCHA SHA-256、ALTCHA PBKDF2、字符输入和滑块拼图六种验证组件。安装、表单绑定、密钥配置、官方测试密钥与排障见[操作验证码](/zh-cn/captcha)。

受管页面通过 `attachPage` 加载统一提交运行时。已有声明动作直接选择；未声明动作可在「操作回调」中配置表单 ID、提交按钮 ID、组件区域和目标接口。原生 form 的 action/method 与 submitter 覆盖属性用于生成候选，最终由服务端目录核对；JavaScript 请求请显式选择实际接口。一个表单有多个动作时分别配置，不自动选第一个。将组件拖入相应区域后确认「保护操作」，预览来源、目标及权限，再正式发布。

共享运行时处理受管目标的原生提交、fetch（含 Request）和异步 XHR，保持业务正文与响应契约；仅在确认尚未进入 controller 时等待交互后发送。同步 XHR、无法安全提取或复用的请求体会明确拒绝，未配置的跨域请求不进入适配。服务端独立强制执行，页面回退、组件缺席和编辑器停用不会删除正式要求。共享操作需明确选中关联草稿或各页 previous 后原子发布/恢复；未选草稿不被覆盖，修订或权限变化使旧确认失效。
