'use strict';
/*
 * 插件市场页前端核心契约测试：共享展示 token 映射 + 市场卡片模型。
 *
 * 运行： node src/test/js/pixiv-plugin-market.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC_ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const TOKENS_SRC = fs.readFileSync(path.join(STATIC_ROOT, 'js', 'pixiv-plugin-presentation-tokens.js'), 'utf8');
const CORE_SRC = fs.readFileSync(path.join(STATIC_ROOT, 'plugin-market', 'plugin-market-core.js'), 'utf8');
const DATA_SRC = fs.readFileSync(path.join(STATIC_ROOT, 'plugin-market', 'plugin-market-data.js'), 'utf8');

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }
function eq(label, actual, expected) { assert.strictEqual(actual, expected, label + ' (got: ' + actual + ')'); passed++; }

const sandbox = { console: { log() {}, warn() {}, error() {} } };
sandbox.window = sandbox;
vm.createContext(sandbox);
vm.runInContext(TOKENS_SRC, sandbox);
vm.runInContext(CORE_SRC, sandbox);
vm.runInContext(DATA_SRC, sandbox);

const PMK = sandbox.window.PixivPluginMarket;
ok('PixivPluginMarket 已挂载（core+data）', PMK
    && typeof PMK.iconClass === 'function'
    && PMK.data
    && typeof PMK.data.cardModel === 'function');

PMK.state.i18n.client = {
    lang: 'zh-CN',
    t: (prefixedKey, fallback) => (fallback != null ? fallback : prefixedKey)
};

function entry(id, name, summary, iconToken, colorToken, category) {
    return {
        pluginId: id,
        displayNamespace: id,
        displayNameKey: 'plugin.name',
        descriptionKey: 'plugin.summary',
        latestVersion: '1.0.0',
        market: {
            displayName: { zh: name, en: name },
            summary: { zh: summary, en: summary },
            description: { zh: summary, en: summary },
            author: 'Sywyar',
            sourceType: 'official',
            category: category || 'utility',
            tags: [id],
            totalDownloadCount: 0,
            iconToken: iconToken,
            colorToken: colorToken,
            recommended: true
        },
        packages: [],
        installStatus: 'NOT_INSTALLED',
        compatible: true
    };
}

[
    ['download-workbench', '下载工作台', 'download', 'pixiv', 'download', 'fa-solid fa-download'],
    ['gui-theme', 'GUI 主题', 'palette', 'blue', 'ui', 'fa-solid fa-palette'],
    ['stats', '统计', 'chart-line', 'green', 'utility', 'fa-solid fa-chart-line'],
    ['notification', '通知', 'bell', 'teal', 'notify', 'fa-solid fa-bell'],
    ['push', '推送通知', 'bell', 'blue', 'notify', 'fa-solid fa-bell'],
    ['mail', '邮件通知', 'mail', 'green', 'notify', 'fa-solid fa-envelope'],
    ['tts', 'TTS 朗读', 'audio-lines', 'amber', 'utility', 'fa-solid fa-wave-square'],
    ['ai', 'AI 翻译', 'sparkles', 'teal', 'translate', 'fa-solid fa-wand-magic-sparkles']
].forEach(function (spec) {
    const id = spec[0];
    const name = spec[1];
    const iconToken = spec[2];
    const colorToken = spec[3];
    const category = spec[4];
    const iconClass = spec[5];
    const card = PMK.data.cardModel(entry(id, name, 'summary', iconToken, colorToken, category));
    eq(id + ' 市场名称来自 canonical i18n 字面 map', card.name, name);
    eq(id + ' 图标 token 不回退默认', card.iconClass, iconClass);
    eq(id + ' 颜色 token 不回退默认', card.colorClass, 'pmk-accent--' + colorToken);
});

eq('未知图标 token → 默认 puzzle', PMK.iconClass('definitely-unknown'), 'fa-solid fa-puzzle-piece');
eq('未知颜色 token → 默认 neutral', PMK.colorClass('pink'), 'pmk-accent--neutral');

console.log('pixiv-plugin-market.test.js: ' + passed + ' assertions passed');
