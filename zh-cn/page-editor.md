# 页面编辑器与插件组件

页面编辑器需要 SDK 1.4 或更高版本。安装并启动可选的 `page-editor` 插件，以管理员身份从导航进入“页面编辑器”。当前核心提供登录页；其它页面由页面所属插件声明接入。

## 编辑和恢复

1. 选择页面，将“添加组件”中的组件拖入画布。也可聚焦组件按钮后按 Enter 或空格添加。
2. 在图层列表选择实例，修改属性和样式。容器支持嵌套；移动、嵌套、移出和删除按钮提供键盘可达的替代操作。窄视口模式用于设置响应式样式。
3. 保存草稿后可重新加载；预览不执行组件业务操作。发布会保存当前编辑结果并替换正式版本，公开页面上的组件配置也随之公开。
4. “恢复上一版”将上一份可用发布恢复为正式版和草稿。另一个窗口已经修改页面时，保存会报告冲突；先重新加载，再应用需要保留的修改。

左侧可切换“结构”和“添加组件”。点击画布中的内容或在结构列表中选择，再在右侧调整属性与样式；移动和删除操作也在右侧。“页面设置”中的“浏览器标签标题”只修改标签页标题。点击“预览”后，实际页面布局在中央展开；“返回编辑”保留未保存的修改。预览使用登录页自身的控件和样式，不提交登录或业务请求。

“重载草稿”和“恢复上一发布版”位于顶部的“更多”菜单。没有可恢复的发布版本时，恢复按钮不可用。

登录页的认证表单由核心保留，账户登录、邀请码和回跳沿用原流程。自定义外观下可选择“使用默认布局”；组件缺席、发布内容无效或编辑器停用时也使用默认登录页。页面编辑不会改变认证策略。已登录用户仍按原规则跳转。

缺席或配置版本不兼容的组件会保留原类型、版本、实例身份和配置。可以保存其他布局修改，但不能发布不完整页面；重新启用正确插件版本或执行插件提供的配置迁移后再发布。发布或恢复失败不会覆盖上一个已提交版本。

## 页眉、页脚和登录卡片

初次打开会载入页面 owner 提供的原布局。选择已有的页眉、正文或页脚即可修改样式，也可从“添加组件”拖入这些区域。容器的“区域类型”可在普通容器、页眉、正文、页脚之间切换。

要添加备案信息，选中图层中的“页脚”，添加“文本”或“链接”，再填写文字和链接地址。默认布局采用纵向弹性排列，正文的“弹性伸展”为“占满剩余空间”：内容较短时页脚位于页面底部，较长时跟随正文，不遮挡内容。

登录卡片内的标题、用户名、密码、按钮等是可选择的受控区域。将文字或插件拖到控件之间，或选中一个控件后聚焦组件按钮并按 Enter，即可在其后插入。必需控件不能删除、复制或移出所属区域；普通容器仍可用于分组和调整样式。“显示默认隐藏的区域”用于编辑邀请码卡片，不改变正式页面切换逻辑。

“更多 → 恢复页面默认布局”只替换当前未保存的编辑内容，保存并发布后才影响正式页面。正式登录页的“使用默认布局”只恢复当前浏览器页面，不删除服务器草稿或发布历史。草稿缺少必需区域时，会显示说明和默认登录页预览，保存与发布暂不可用。选择“以此布局开始”只替换未保存的画布；原草稿保留到下一次明确保存。

## 组件 CSS

选中组件，在右侧“样式”中展开“自定义 CSS”，即可使用 CodeMirror 代码编辑区。“布局”“外观”和“自定义 CSS”可分别展开；收起“样式”会收起整个分组，并保留未应用的 CSS。编辑区提供行号、高亮、受支持属性补全和错误定位。点击问号可查看输入格式和快捷键。右上角的对角箭头可全屏编辑，再次点击或按 Esc 返回侧栏，输入和撤销记录会保留。填写属性声明后点击“应用样式”，例如：

```css
padding: 16px;
border-radius: 8px;
```

样式作用于选中组件的根元素及当前画布宽度；文字等可继承属性仍遵循 CSS 继承规则。切换到窄屏可单独设置覆盖，清空输入并应用会移除当前宽度的样式覆盖。应用后可撤销，结果随草稿保存，并用于预览和发布，不另存 `.css` 文件。

Ctrl/Cmd+Enter 应用样式，Tab 移到下一个控件，Esc 先关闭补全列表或帮助，再退出全屏或收起编辑区。收起会保留未应用的输入；选择“放弃修改”可重新载入当前组件样式。未应用的 CSS 不会随页面自动保存；保存、预览或发布前需要先应用或放弃。切换组件或画布宽度后，原输入仍保留，但不能应用到另一个目标。

面板展开、组件移动和操作反馈使用可打断的过渡；启用系统的“减少动态效果”后停止这些动画，状态文字和操作结果仍会显示。

接受下列属性及其有效值。每个值最多 128 字符；不接受选择器、注释、`@media` / `@import`、外部资源、`!important`、隐藏页面的 `display: none` 或自定义属性定义。输入无效时保留内容并提示错误。

`display`, `flex-direction`, `flex-wrap`, `justify-content`, `align-items`, `gap`, `padding`, `padding-top`, `padding-right`, `padding-bottom`, `padding-left`, `margin`, `margin-top`, `margin-right`, `margin-bottom`, `margin-left`, `width`, `min-width`, `max-width`, `min-height`, `font-size`, `font-weight`, `line-height`, `text-align`, `color`, `background-color`, `border`, `border-color`, `border-width`, `border-style`, `border-radius`, `box-shadow`, `grid-template-columns`, `flex`, `order`.

## 添加第三方组件

新增组件只修改自己的插件及独立业务契约，不需要改宿主、中央 SDK 或编辑器的组件类型列表。参考主仓库的 [独立清单示例](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-app/src/test/resources/page-components)。它具有嵌套 `items[]`、自定义属性面板、两个独立实例和生成客户端调用；它是验收夹具，不是随程序安装的业务插件。

| 文件或入口 | 组件开发者负责的内容 |
| --- | --- |
| 业务契约及 record | 在独立公开契约包声明业务操作和配置类型；用 SDK 工具生成 JSON Schema、浏览器客户端和 Java HTTP adapter |
| `PageContribution` Bean | 返回 `PageComponent` 声明；实现业务配置校验及需要的版本迁移 |
| 渲染模块 | 导出 `mount(root, context)`，返回 `update(properties)` 和 `destroy()` |
| 属性编辑模块 | 复杂配置导出 `mountProperties(root, context)`，通过 `context.change(value)` 提交完整纯值快照 |
| 插件配置类与描述符 | 注册 Bean、自己的生成 adapter、服务导入/导出和所需契约依赖；声明最低 SDK 版本 |
| 静态资源、路由与 i18n | 贡献同源模块及词典；公开渲染模块需显式 PUBLIC，编辑资源和业务操作按各自鉴权声明 |

`PageComponent` 包含稳定的 `owner:type` 身份、正整数配置版本、显示 key/namespace、渲染和可选属性编辑模块 URL、schema/defaults JSON、`publicRenderable` 及输入/输出端口名。类型命名空间必须属于实际插件 owner；同名类型冲突会拒绝发布。目录聚合多个 owner，不通过单 provider 的 `PluginServices` key 收集组件。

配置 schema 使用 SDK 当前生成器支持的语义，服务器复用同一校验器；不要将任意 JSON Schema 草案当成受支持合同。基础属性面板处理字符串、布尔值和数值；嵌套对象和数组由插件属性编辑器负责。业务约束在 `validate(type, json)` 中再次检查。版本迁移由 `migrate(type, fromVersion, json)` 返回新配置，服务器验证结果后才接受；默认不支持迁移。

浏览器类型声明位于 Plugin API JAR 的 `META-INF/pixivdownload/web/page-components.d.mts`。上下文提供实例身份、`edit/preview/display` 模式、纯值属性、取消信号、词典、受控 fetch、监听/计时器清理和声明端口连接，不暴露 GrapesJS 对象。正式业务调用将 `context.fetch` 传给自己的生成客户端：

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
        update(properties) { /* 更新本实例显示；不执行写操作 */ },
        destroy() { root.replaceChildren(); }
    };
}
```

示例中的契约名、操作和端口需替换为自己的声明。真实错误应显示本地化反馈。在 `context.onCleanup` 登记额外订阅、监听器和计时器；异步工作在取消后停止，并忽略迟到结果。属性面板同样遵守此规则。上下文 fetch 固定当前 publication，不会将旧请求转给同 ID 的新插件；后端仍执行原鉴权。浏览器断开不代表服务器业务已经排空。

插件代码属于可信安装内容，iframe 不是隔离沙箱。插件不得在编辑或预览中用原生 fetch 绕过受控上下文执行业务写操作，也不得把函数、脚本、运行时句柄或授权 token 放进页面配置。输入/输出的业务含义和数据校验由组件公开契约定义，页面只连接已声明的端口，不提供 URL、反射方法名或通用脚本执行入口。

## 接入自己的页面

`EditablePage` 还声明 `regions`、`defaultProject`、`previewModuleUrl` 和 `stylesheets`。每个 `PageRegion(id, labelKey, parent, container, previewHidden)` 必须出现一次；`parent` 是最近的受控父区域，空字符串表示不能位于其它受控区域内。只有 `container=true` 的区域允许插入子节点。服务器根据 owner 声明验证这些约束，页面 JSON 不能自行赋权。

`defaultProject` 使用编辑器存档形状 `{"format":1,"project":{...}}`，只包含纯值；为空时按区域父子关系生成最小布局。当前项目节点支持 `page-box`（`role` 为 `section/header/main/footer`）、`page-text`、`page-link`、`page-region` 和 `page-component`。具体完整布局可参考核心 `login/login-layout.json`。链接的 `href` 只允许站内绝对路径或 HTTP(S) 地址，最多 2048 字符；它是导航地址，不能当作业务操作入口。

预览模块沿用 `mount(root, context)`，`context.properties.region` 指定区域。模块只渲染该叶区域的只读外观；容器由编辑器装配子节点。资源必须来自当前 owner，最多声明 16 份 CSS，随 publication 验证和撤回。预览模块地址不进入匿名投影。`attachPage` 的 `onMounted` 可隐藏原页面外壳，`onFallback` 恢复外壳；运行时会恢复被移动区域的原 DOM、子节点和样式。

页面 owner 通过 `PageContribution.pages()` 声明 `EditablePage`：身份、显示信息、真实页面路径、是否公开和必需区域名。页面加载核心 `/js/pixiv-pages.mjs` 的 `attachPage`，传入渲染根、原有区域 DOM、默认回退按钮和标题更新回调。可参考核心 `login/login-page.mjs`。保留 owner 的默认页面和业务监听器，区域 DOM 被移动而不是重新生成。公开声明必须匹配 owner 的实际 PUBLIC 路由。

管理目录和草稿只对 ADMIN 开放。匿名入口 `GET /api/web/page-publications/{pageId}` 只返回已声明公开页面的已发布渲染投影；不返回草稿、schema 或属性编辑器地址。正式页面加载轻量运行时和所需组件，不加载 GrapesJS。页面存储归编辑器 owner，使用运行数据目录下的 `pages`，不改 JAR 或宿主配置文件。

存档格式 1 保存受约束的 GrapesJS project JSON、标题、实例配置和连接；HTML/CSS 是派生内容。每份文档最多 1 MiB、512 个节点、24 层组件嵌套和 256 条连接；标题最多 256 字符。状态文件保留草稿、正式版和上一版，使用 revision 检测并发、同目录原子替换提交。仅支持受控布局/文字样式，不接受脚本、任意 HTML、外部资源 URL 或 CSS 导入。

## 运行独立插件验收

在主仓库用 Java 17、Maven、Python 和 Node.js 构建宿主、SDK 工具、页面编辑器及必需插件。使用已安装的 Playwright/Chromium，通过 `PAGE_EDITOR_PLAYWRIGHT` 指定 Playwright 的 `index.mjs` 绝对路径；需要时用 `PAGE_EDITOR_CHROMIUM` 指定浏览器可执行文件。

```powershell
mvn -pl pixivdownload-plugin-page-editor,pixivdownload-app,pixivdownload-sdk-tools,pixivdownload-plugin-download-workbench -am package '-DskipTests' '-Dexec.skip=true'
node scripts/ci/page-editor-acceptance.mjs target/page-editor-acceptance
```

第一条仅构建聚焦产物；单元测试另行运行。第二条要求目标目录不存在，冻结输入 JAR 后另行生成、编译并通过真实安装入口加载示例插件，使用隔离配置和数据库运行浏览器与生命周期验收，完成时核对冻结产物摘要。它不会操作真实用户数据，也不构成发行包或验证码业务验收。

GrapesJS Core 的固定本地文件、许可证和摘要见编辑器插件的 `web/vendor/grapesjs/vendor.json`。运行期不使用默认 CDN 图标或 GrapesJS 遥测；组件自己的网络访问仍需按业务契约和插件声明处理。
