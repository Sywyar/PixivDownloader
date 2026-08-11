'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-notifications', 'batch-inbox-slot.js'), 'utf8');
const PAGE_SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-notifications', 'pixiv-notifications.js'), 'utf8');
const PAGE_HTML = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-notifications.html'), 'utf8');
const CSS = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-notifications', 'pixiv-notifications.css'), 'utf8');

assert.ok(SOURCE.includes('queueTypes.registerUiModule(function (context)'),
    '站内信顶栏入口应使用受控下载页 uiSlot initializer');
assert.ok(SOURCE.includes("response.status === 401 || response.status === 403")
    && SOURCE.includes('removeRendered();'),
    '非管理员响应必须移除入口');
assert.ok(SOURCE.includes("context.onCleanup(function ()")
    && SOURCE.includes("removeEventListener('pixivbatch:slotsrendered'")
    && SOURCE.includes('removeRendered();'),
    '插件 publication 清理时必须移除监听器与 DOM');
assert.ok(SOURCE.includes("popover.setAttribute('popover', 'auto')")
    && SOURCE.includes("button.setAttribute('aria-label'"),
    '入口应使用原生 popover 并提供可访问名称');
assert.ok(CSS.includes('.notification-page {') && !/(^|\n)body\s*\{/.test(CSS),
    '插件页面样式不得通过裸 body 选择器污染下载工作台');
assert.ok(CSS.includes('--notification-brand: var(--brand, #0096fa);')
    && /html\[data-theme="dark"\]\s*\{[^}]*--notification-brand:\s*var\(--brand, #4bb3ff\);/s.test(CSS),
    '站内信独立页必须使用蓝白配色并保留宿主品牌色覆盖');
assert.ok(SOURCE.includes("data-i18n-title', 'notification:inbox.open'")
    && SOURCE.includes("data-i18n', 'notification:inbox.latest'"),
    '动态顶栏入口与弹窗必须跟随下载页语言切换');
assert.ok(SOURCE.includes('var snapshot = {messages: [], unreadCount: 0};')
    && SOURCE.includes('global.PixivVue.prepareSlotHosts(document);')
    && /createPopover\(\);[\s\S]*prepareSlotHosts\(document\);[\s\S]*render\(\);[\s\S]*refresh\(\);/.test(SOURCE),
    '入口必须先建立共享槽位并渲染，不能依赖首次消息请求成功');
assert.strictEqual((CSS.match(/--notification-topbar-icon:\s*var\(--brand-text, var\(--text,/g) || []).length, 2,
    '顶栏图标必须兼容旧版品牌色与新版明暗主题文本色');
assert.ok(/\.ab-topbar \.notification-inbox-button\s*\{[^}]*border-radius:\s*var\(--r-sm\);[^}]*background:\s*transparent;/s.test(CSS)
    && /\.ab-topbar \.notification-inbox-button:hover\s*\{[^}]*background:\s*var\(--hover-bg\);/s.test(CSS),
    '新版顶栏站内信入口必须使用纯图标区的透明底与悬停样式');
assert.ok(PAGE_SOURCE.includes('state.selectedMessage && !matchesCategory(state.selectedMessage)')
    && PAGE_SOURCE.includes('clearSelection(true);'),
    '切换分类时必须清除不属于新分类的右侧详情');
assert.ok(/if \(state\.selectedId !== id\) return;[\s\S]*if \(!matchesCategory\(message\)\) \{[\s\S]*clearSelection\(true\);/s.test(PAGE_SOURCE),
    '异步加载完成时必须拒绝过期或分类不匹配的详情');
assert.ok(PAGE_SOURCE.includes('snapshot.categoryUnreadCount')
    && PAGE_SOURCE.includes("query.set('unreadOnly', 'true')")
    && PAGE_SOURCE.includes("api('/api/notifications/read-all' + query"),
    '分类页必须使用当前分类未读数并支持仅看未读与当前分类全部已读');
assert.ok(PAGE_SOURCE.includes('function markSelectedRead(event)')
    && !SOURCE.includes("encodeURIComponent(message.id) + '/read'"),
    '消息详情必须显式标记已读，弹窗点击不能等待冗余已读请求');
assert.ok(PAGE_SOURCE.includes('requestSequence !== loadSequence')
    && PAGE_SOURCE.includes('if (id) selectMessage(id, false); else clearSelection(false);'),
    '列表必须拒绝过期分类响应，历史导航移除 id 时必须清空详情');
assert.ok(PAGE_SOURCE.includes("window.matchMedia('(max-width: 760px)').matches")
    && PAGE_SOURCE.includes("document.visibilityState === 'visible'")
    && SOURCE.includes('global.clearInterval(refreshTimer)'),
    '移动端打开详情应定位内容，可见页面定时刷新且插件卸载时清理计时器');
assert.ok(PAGE_SOURCE.includes("severity === 'warning' || severity === 'error'")
    && CSS.includes('.notification-list-item.severity-warning')
    && CSS.includes('.notification-detail-panel.severity-error h2'),
    '警告与错误消息必须有可辨识的严重程度样式');
assert.ok(PAGE_HTML.includes("frame-src https://sywyar.github.io/PixivDownloader-Remote-Content/")
    && PAGE_HTML.includes("default-src 'self'; script-src 'self'; style-src 'self'")
    && PAGE_HTML.includes("connect-src 'self'")
    && PAGE_HTML.includes("object-src 'none'; base-uri 'none'; form-action 'none'"),
    '详细页 CSP 必须限制自身资源、网络连接和 iframe，并禁止对象、表单与 base 改写');
assert.ok(PAGE_SOURCE.includes("var CONTENT_ORIGIN = 'https://sywyar.github.io';")
    && PAGE_SOURCE.includes('PixivDownloader-Remote-Content')
    && PAGE_SOURCE.includes('url.href === CONTENT_ORIGIN + url.pathname'),
    '浏览器端必须再次校验远程正文的来源、路径和规范 URL');
assert.ok(PAGE_SOURCE.includes("frame.setAttribute('sandbox', '')")
    && PAGE_SOURCE.includes("frame.setAttribute('referrerpolicy', 'no-referrer')")
    && PAGE_SOURCE.includes("frame.setAttribute('credentialless', '')")
    && PAGE_SOURCE.includes("camera 'none'; clipboard-read 'none'; clipboard-write 'none'")
    && !PAGE_SOURCE.includes('allow-scripts')
    && !PAGE_SOURCE.includes('allow-same-origin'),
    '远程正文 iframe 必须无 sandbox 权限、无凭据、无 referrer 且拒绝敏感能力');
assert.ok(PAGE_SOURCE.includes("frame.setAttribute('loading', 'lazy')")
    && CSS.includes('.notification-detail-content-frame {'),
    '远程正文应按需加载并限制在详情面板内');

console.log('notification-inbox-slot.test.js: 21 assertions passed ✓');
