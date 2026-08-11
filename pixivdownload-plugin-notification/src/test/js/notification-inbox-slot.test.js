'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-notifications', 'batch-inbox-slot.js'), 'utf8');
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
assert.ok(SOURCE.includes("data-i18n-title', 'notification:inbox.open'")
    && SOURCE.includes("data-i18n', 'notification:inbox.latest'"),
    '动态顶栏入口与弹窗必须跟随下载页语言切换');
assert.ok(SOURCE.includes('var snapshot = {messages: [], unreadCount: 0};')
    && SOURCE.includes('global.PixivVue.prepareSlotHosts(document);')
    && /createPopover\(\);[\s\S]*prepareSlotHosts\(document\);[\s\S]*render\(\);[\s\S]*refresh\(\);/.test(SOURCE),
    '入口必须先建立共享槽位并渲染，不能依赖首次消息请求成功');
assert.strictEqual((CSS.match(/--notification-topbar-icon:\s*var\(--brand-text, var\(--text,/g) || []).length, 2,
    '顶栏图标必须兼容旧版品牌色与新版明暗主题文本色');

console.log('notification-inbox-slot.test.js: 8 assertions passed ✓');
