'use strict';
/*
 * 插件管理页核心模块（plugin-manage-core.js, window.PixivPluginManage）的视图模型测试。
 *
 * 无浏览器：在 Node 的 vm 沙箱里加载真实的 plugin-manage-core.js，注入一个最小 i18n 客户端桩，验证后端新增的
 * 三个展示元数据字段（descriptionKey / iconKey / colorToken）在卡片视图模型里的契约：
 *   1) 卡片简介经 tns(displayNamespace, descriptionKey, fallback) 解析（命中 namespace 内的纯 key）。
 *   2) descriptionKey 缺失 → 优雅回退到按来源的通用简介，不抛、不破坏渲染。
 *   3) descriptionKey 命中 namespace 但 bundle 内缺该 key → tns 回退到通用简介（仍不裸露 key）。
 *   4) iconKey 受控白名单：已知 → 固定 FA class；未知 / 缺失 → 回退 puzzle（原始 token 绝不当类名）。
 *   5) colorToken 受控白名单：已知 → 原 token；未知 / 缺失 → 回退 neutral（渲染层据此拼固定 CSS class）。
 *
 * 运行： node src/test/js/pixiv-plugin-manage.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const SRC = fs.readFileSync(
    path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'plugin-manage', 'plugin-manage-core.js'), 'utf8');

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }
function eq(label, actual, expected) { assert.strictEqual(actual, expected, label + ' (got: ' + actual + ')'); passed++; }

// 加载真实 core 模块（无副作用：启动逻辑在 init.js，本模块只定义命名空间）。
const sandbox = { console: { log() {}, warn() {}, error() {} } };
sandbox.window = sandbox;
vm.createContext(sandbox);
vm.runInContext(SRC, sandbox);
const PM = sandbox.window.PixivPluginManage;
ok('PixivPluginManage 已挂载', PM && typeof PM.allViewModels === 'function');

// i18n 客户端桩：
//   t(prefixedKey, fallback) → 返回 fallback（页面自有文案在断言里就用其 fallback 文案）。
//   tns(ns, key, fallback) → 仅对「已知 (ns,key)」返回可识别标记，其余返回 fallback（模拟 bundle 缺 key 的回退）。
const KNOWN = { 'gallery/plugin.summary': 'NS[gallery/plugin.summary]' };
PM.i18n.client = {
    t: (prefixedKey, fallback) => (fallback != null ? fallback : prefixedKey),
    tns: (ns, key, fallback) => {
        const hit = KNOWN[ns + '/' + key];
        return hit != null ? hit : fallback;
    }
};

// 用单条后端条目构建卡片视图模型。
function vmOf(entry) {
    PM.state.report = { plugins: [entry] };
    const models = PM.allViewModels();
    return models[0];
}

// 通用来源回退文案（与 core 的 sourceDesc 默认 fallback 一致）。
const DESC_BUILT_IN = '内置插件，随主程序编译。';
const DESC_EXTERNAL = '外置插件，可在运行期启停。';
const DESC_NOT_INSTALLED = '该插件尚未安装。';

// —— 1) descriptionKey 命中：经 tns(displayNamespace, descriptionKey, fallback) 解析 ——
(function () {
    const vmm = vmOf({ id: 'gallery', source: 'built-in', status: 'STARTED',
        displayNamespace: 'gallery', displayNameKey: 'nav.label', descriptionKey: 'plugin.summary',
        iconKey: 'gallery', colorToken: 'green' });
    eq('descriptionKey 命中 → 卡片简介取 tns(namespace,key) 的解析值', vmm.desc, 'NS[gallery/plugin.summary]');
})();

// —— 2) descriptionKey 缺失 → 回退按来源的通用简介，不抛 ——
(function () {
    const builtIn = vmOf({ id: 'core', source: 'built-in', status: 'STARTED', displayNamespace: 'plugins' });
    eq('缺 descriptionKey（built-in）→ 回退通用简介', builtIn.desc, DESC_BUILT_IN);

    const external = vmOf({ id: 'ext', source: 'external', status: 'STARTED' });
    eq('缺 descriptionKey（external）→ 回退通用简介', external.desc, DESC_EXTERNAL);

    const notInstalled = vmOf({ id: 'miss', source: 'not-installed', status: 'MISSING_REQUIRED' });
    eq('缺 descriptionKey（not-installed）→ 回退通用简介', notInstalled.desc, DESC_NOT_INSTALLED);
})();

// —— 3) descriptionKey 命中 namespace 但 bundle 缺该 key → tns 回退通用简介（不裸露 key）——
(function () {
    const vmm = vmOf({ id: 'novel', source: 'built-in', status: 'STARTED',
        displayNamespace: 'novel', displayNameKey: 'nav.label', descriptionKey: 'plugin.summary' });
    eq('descriptionKey 存在但 bundle 缺 key → 回退通用简介', vmm.desc, DESC_BUILT_IN);
    ok('回退结果不是裸 key', vmm.desc.indexOf('plugin.summary') === -1);
})();

// —— 3b) descriptionKey 存在但 displayNamespace 空白 → tns 短路回退，不抛 ——
(function () {
    const vmm = vmOf({ id: 'ext2', source: 'external', status: 'STARTED',
        displayNamespace: '   ', descriptionKey: 'plugin.summary' });
    eq('descriptionKey 存在但 namespace 空白 → 回退通用简介', vmm.desc, DESC_EXTERNAL);
})();

// —— 4) iconKey 受控白名单：已知 / 未知 / 缺失 ——
(function () {
    eq('iconKey 已知 download → fa-download', vmOf({ id: 'a', source: 'built-in', iconKey: 'download' }).icon,
        'fa-solid fa-download');
    eq('iconKey 已知 chart → fa-chart-line', vmOf({ id: 'b', source: 'external', iconKey: 'chart' }).icon,
        'fa-solid fa-chart-line');
    eq('iconKey 未知 → 回退 puzzle', vmOf({ id: 'c', source: 'built-in', iconKey: 'definitely-unknown' }).icon,
        'fa-solid fa-puzzle-piece');
    eq('iconKey 缺失 → 回退 puzzle', vmOf({ id: 'd', source: 'built-in' }).icon, 'fa-solid fa-puzzle-piece');
    // 原始未知 token 绝不被当作类名渲染
    ok('未知 iconKey 不泄漏进 icon class',
        vmOf({ id: 'e', source: 'built-in', iconKey: 'evil onerror=x' }).icon.indexOf('evil') === -1);
})();

// —— 5) colorToken 受控白名单：已知 / 未知 / 缺失 ——
(function () {
    eq('colorToken 已知 purple → purple', vmOf({ id: 'a', source: 'built-in', colorToken: 'purple' }).colorToken,
        'purple');
    eq('colorToken 已知 pixiv → pixiv', vmOf({ id: 'b', source: 'built-in', colorToken: 'pixiv' }).colorToken,
        'pixiv');
    eq('colorToken 未知 → 回退 neutral', vmOf({ id: 'c', source: 'built-in', colorToken: 'rgb(1,2,3)' }).colorToken,
        'neutral');
    eq('colorToken 缺失 → 回退 neutral', vmOf({ id: 'd', source: 'built-in' }).colorToken, 'neutral');
})();

console.log('pixiv-plugin-manage.test.js: ' + passed + ' assertions passed');
