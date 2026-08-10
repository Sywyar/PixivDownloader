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
    '非管理员响应必须不渲染入口');
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

console.log('notification-inbox-slot.test.js: 6 assertions passed ✓');
