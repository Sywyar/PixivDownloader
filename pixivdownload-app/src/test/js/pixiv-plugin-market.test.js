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
    t: (prefixedKey, fallback) => {
        if (prefixedKey === 'plugin-market:category.dependency.description') {
            return '这是其他插件的依赖，会自动安装，一般无需手动安装。';
        }
        return fallback != null ? fallback : prefixedKey;
    }
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
            recommended: true,
            defaultInstalled: false
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
    ['notification', '通知', 'bell', 'teal', 'dependency', 'fa-solid fa-bell'],
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
eq('分类顺序包含下载类型扩展与依赖', PMK.CATEGORY_ORDER.join(','),
    'all,translate,download-type,download,convert,notify,backup,security,ui,utility,dependency');
eq('下载类型扩展使用 plug 图标', PMK.iconClass(PMK.CATEGORY_ICON['download-type']), 'fa-solid fa-plug');
eq('依赖分类使用 layer-group 图标', PMK.iconClass(PMK.CATEGORY_ICON.dependency), 'fa-solid fa-layer-group');
eq('分类说明从 i18n 动态解析', PMK.categoryDescription('dependency'),
    '这是其他插件的依赖，会自动安装，一般无需手动安装。');
const categoryList = PMK.data.categoryList({categories: [
    {category: 'download-type', count: 2},
    {category: 'dependency', count: 1}
]});
eq('下载类型扩展分类使用后端派生计数', categoryList.find(cat => cat.id === 'download-type').count, 2);
eq('依赖分类使用后端派生计数', categoryList.find(cat => cat.id === 'dependency').count, 1);

const defaultInstalled = entry('bundled-tool', '默认内置工具', 'summary', 'puzzle-piece', 'green', 'utility');
defaultInstalled.market.defaultInstalled = true;
const manuallyInstalled = entry('manual-tool', '手动安装工具', 'summary', 'puzzle-piece', 'blue', 'utility');
manuallyInstalled.installStatus = 'INSTALLED';
manuallyInstalled.installedVersion = '1.0.0';
const dependency = entry('shared-base', '共享依赖', 'summary', 'layer-group', 'teal', 'dependency');
const legacyEntry = entry('legacy-tool', '旧清单工具', 'summary', 'puzzle-piece', 'amber', 'utility');
delete legacyEntry.market.defaultInstalled;
const filteredIds = PMK.data.filterAndSort(
    [defaultInstalled, manuallyInstalled, dependency, legacyEntry],
    {
        category: 'all', search: '', sort: 'name', onlyOfficial: false, onlyCompatible: false,
        hideDefaultInstalled: true, hideDependencies: true
    }
).map(item => item.pluginId).sort().join(',');
eq('默认开启两个隐藏筛选时只排除 catalog 标记的默认内置项与依赖分类', filteredIds,
    'legacy-tool,manual-tool');
const hideDefaultOnlyIds = PMK.data.filterAndSort(
    [defaultInstalled, manuallyInstalled, dependency, legacyEntry],
    {category: 'all', search: '', sort: 'name', hideDefaultInstalled: true, hideDependencies: false}
).map(item => item.pluginId).sort().join(',');
eq('只隐藏默认安装插件时仍显示依赖插件', hideDefaultOnlyIds,
    'legacy-tool,manual-tool,shared-base');
const hideDependenciesOnlyIds = PMK.data.filterAndSort(
    [defaultInstalled, manuallyInstalled, dependency, legacyEntry],
    {category: 'all', search: '', sort: 'name', hideDefaultInstalled: false, hideDependencies: true}
).map(item => item.pluginId).sort().join(',');
eq('只隐藏依赖插件时仍显示默认安装插件', hideDependenciesOnlyIds,
    'bundled-tool,legacy-tool,manual-tool');
const showAllIds = PMK.data.filterAndSort(
    [defaultInstalled, manuallyInstalled, dependency, legacyEntry],
    {category: 'all', search: '', sort: 'name', hideDefaultInstalled: false, hideDependencies: false}
).map(item => item.pluginId).sort().join(',');
eq('关闭两个隐藏筛选后恢复全部条目', showAllIds,
    'bundled-tool,legacy-tool,manual-tool,shared-base');
ok('手动安装状态不冒充默认安装事实', !PMK.data.entryDefaultInstalled(manuallyInstalled));
ok('旧清单缺 defaultInstalled 字段时稳定视为非默认安装', !PMK.data.entryDefaultInstalled(legacyEntry));
ok('依赖判定只消费中性 category', PMK.data.entryDependency(dependency));
ok('默认安装判定不硬编码任何插件 id', DATA_SRC.includes('m.defaultInstalled') && !DATA_SRC.includes("'douyin'"));

console.log('pixiv-plugin-market.test.js: ' + passed + ' assertions passed');
