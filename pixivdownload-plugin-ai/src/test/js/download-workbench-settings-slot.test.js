'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-ai', 'download-novel-ai-settings-slot.js'), 'utf8');

assert.ok(SOURCE.includes('queueTypes.registerUiModule(function (context)'),
    'AI 下载设置槽位应使用受控 uiSlot initializer');
assert.ok(SOURCE.includes("removeEventListener('pixivbatch:slotsrendered'")
    && SOURCE.includes('context.onCleanup(removeRendered)'),
    'AI 下载设置槽位应在 activation cleanup 中移除 listener 与 DOM');

console.log('download-workbench-settings-slot.test.js: 2 assertions passed ✓');
