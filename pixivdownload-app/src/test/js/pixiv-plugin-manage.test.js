'use strict';
/*
 * 插件管理页前端模块（plugin-manage-core.js / -api.js / -views.js, window.PixivPluginManage）的契约测试。
 *
 * 无浏览器 / 无 jsdom：在 Node 的 vm 沙箱里加载真实的 core + api + views 三个模块（DOM 仅在被调用的渲染函数里用到，
 * 本测试只调用纯函数与 api 层，故无需 DOM），用最小 i18n / fetch / FormData 桩验证两组契约：
 *
 * 一、展示元数据（既有）——卡片视图模型对后端 descriptionKey / iconKey / colorToken 的解析与受控白名单回退。
 *
 * 二、本地插件包安装（消费 POST /api/plugins/install 的 PluginInstallResponse）：
 *   A) API 层 installPackage：multipart 请求路径 / 方法 / file 字段 / allowDowngrade 默认 false；4xx 仍返回结构化体；
 *      未选文件不发请求（抛 localValidation）；拿不到结构化响应才抛 httpStatus。
 *   B) 视图模型 buildInstallResult：outcome → 色调（accepted=ok、DUPLICATE=info、拒绝/失败=bad）、字段映射。
 *   C) 结果渲染 renderInstallResultHtml：REJECTED_EMPTY 稳定显示；事务成功显示即时激活；
 *      诊断 / 依赖列表显示且全部转义、绝不注入 HTML。
 *
 * 运行： node src/test/js/pixiv-plugin-manage.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'plugin-manage');
const CORE_SRC = fs.readFileSync(path.join(STATIC, 'plugin-manage-core.js'), 'utf8');
const API_SRC = fs.readFileSync(path.join(STATIC, 'plugin-manage-api.js'), 'utf8');
const VIEWS_SRC = fs.readFileSync(path.join(STATIC, 'plugin-manage-views.js'), 'utf8');

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }
function eq(label, actual, expected) { assert.strictEqual(actual, expected, label + ' (got: ' + actual + ')'); passed++; }

// ---- fetch / FormData 桩（供 api 层 installPackage 用；fetch 记录每次调用，响应可逐用例配置）----
const fetchCalls = [];
let nextFetchResponse = null;   // { ok, status, body, throwJson }

class FormDataStub {
    constructor() { this._entries = []; }
    append(k, v) { this._entries.push([String(k), v]); }
    get(k) { const e = this._entries.find(x => x[0] === String(k)); return e ? e[1] : null; }
    has(k) { return this._entries.some(x => x[0] === String(k)); }
}

// 加载真实 core + api + views 模块（无副作用：启动逻辑在 init.js，三模块只在命名空间上定义函数）。
const sandbox = { console: { log() {}, warn() {}, error() {} } };
sandbox.window = sandbox;
sandbox.FormData = FormDataStub;
sandbox.fetch = function (url, opts) {
    fetchCalls.push({ url, opts });
    const r = nextFetchResponse || { ok: true, status: 200, body: {} };
    return Promise.resolve({
        ok: r.ok !== false,
        status: r.status || 200,
        json: () => (r.throwJson ? Promise.reject(new Error('bad json')) : Promise.resolve(r.body))
    });
};
vm.createContext(sandbox);
vm.runInContext(CORE_SRC, sandbox);
vm.runInContext(API_SRC, sandbox);
vm.runInContext(VIEWS_SRC, sandbox);
const PM = sandbox.window.PixivPluginManage;
ok('PixivPluginManage 已挂载（core+api+views）', PM
    && typeof PM.allViewModels === 'function'
    && typeof PM.installPackage === 'function'
    && typeof PM.buildInstallResult === 'function'
    && typeof PM.renderInstallResultHtml === 'function');

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

// ============================================================
// 二、本地插件包安装
// ============================================================

// 后端各 outcome 的最小响应体构造（字段名严格对齐 PluginInstallResponse）。
function installResponse(over) {
    return Object.assign({
        outcome: 'INSTALLED', accepted: true, effectiveAfterRestart: false, status: 200,
        message: 'message', pluginId: null, version: null, previousVersion: null,
        packageId: null, targetVersion: null, operation: null, runtimePhase: null,
        updated: false, activated: false, rolledBack: false, rollbackVersion: null, transactionId: null,
        dependencies: [], unsatisfiedDependencies: [], diagnostics: []
    }, over || {});
}

// —— B) buildInstallResult：色调与字段映射 ——
(function () {
    const installed = PM.buildInstallResult(installResponse(
        { outcome: 'INSTALLED', accepted: true, activated: true,
            pluginId: 'ext-demo', version: '1.2.0', packageId: 'ext-demo', targetVersion: '1.2.0',
            operation: 'INSTALLING', runtimePhase: 'STARTED', transactionId: 'tx-1' }));
    eq('INSTALLED tone=ok', installed.tone, 'ok');
    ok('INSTALLED accepted', installed.accepted === true);
    ok('INSTALLED 即时生效', installed.effectiveAfterRestart === false && installed.activated === true);
    eq('INSTALLED pluginId 映射', installed.pluginId, 'ext-demo');
    eq('INSTALLED version 映射', installed.version, '1.2.0');
    eq('INSTALLED packageId 映射', installed.packageId, 'ext-demo');
    eq('INSTALLED operation 映射', installed.operation, 'INSTALLING');
    eq('INSTALLED runtimePhase 映射', installed.runtimePhase, 'STARTED');

    const upgraded = PM.buildInstallResult(installResponse(
        { outcome: 'UPGRADED', accepted: true, previousVersion: '1.0.0' }));
    eq('UPGRADED tone=ok', upgraded.tone, 'ok');
    eq('UPGRADED previousVersion 映射', upgraded.previousVersion, '1.0.0');

    const duplicate = PM.buildInstallResult(installResponse({ outcome: 'DUPLICATE', accepted: true }));
    eq('DUPLICATE（已存在、无改动）tone=info', duplicate.tone, 'info');

    const downgradeRejected = PM.buildInstallResult(installResponse(
        { outcome: 'DOWNGRADE_REJECTED', accepted: false, effectiveAfterRestart: false, status: 409 }));
    eq('DOWNGRADE_REJECTED（未 accepted）tone=bad', downgradeRejected.tone, 'bad');
    ok('DOWNGRADE_REJECTED not accepted', downgradeRejected.accepted === false);

    const empty = PM.buildInstallResult(installResponse(
        { outcome: 'REJECTED_EMPTY', accepted: false, effectiveAfterRestart: false, status: 400,
            message: '未上传插件包或插件包为空', diagnostics: ['no plugin package uploaded'] }));
    eq('REJECTED_EMPTY tone=bad', empty.tone, 'bad');
    eq('REJECTED_EMPTY message 透传', empty.message, '未上传插件包或插件包为空');
    eq('diagnostics → errors 映射', empty.errors.length, 1);

    const deps = PM.buildInstallResult(installResponse(
        { outcome: 'INSTALLED', unsatisfiedDependencies: ['ghost-plugin', 'other'] }));
    eq('unsatisfiedDependencies → warnings 映射', deps.warnings.length, 2);
})();

// —— C) renderInstallResultHtml：稳定显示 / 即时激活 / 转义 ——
const ACTIVATED_NOTE = '插件已安装并在当前进程中激活。';

// C-1) REJECTED_EMPTY 稳定显示（消息 + 结果码 + bad 色调；无「重启后生效」）。
(function () {
    const html = PM.renderInstallResultHtml(PM.buildInstallResult(installResponse(
        { outcome: 'REJECTED_EMPTY', accepted: false, effectiveAfterRestart: false, status: 400,
            message: '未上传插件包或插件包为空' })));
    ok('REJECTED_EMPTY 渲染含本地化 message', html.indexOf('未上传插件包或插件包为空') !== -1);
    ok('REJECTED_EMPTY 渲染含稳定结果码', html.indexOf('REJECTED_EMPTY') !== -1);
    ok('REJECTED_EMPTY 渲染 bad 色调', html.indexOf('pm-install-result-box--bad') !== -1);
    ok('REJECTED_EMPTY 不渲染「重启后生效」', html.indexOf('重启后生效') === -1);
})();

// C-2) 事务成功 → 渲染即时激活 + ok 色调 + 事务字段。
(function () {
    const html = PM.renderInstallResultHtml(PM.buildInstallResult(installResponse(
        { outcome: 'INSTALLED', accepted: true, activated: true, status: 200,
            message: '插件安装成功', pluginId: 'ext-demo', version: '1.0.0',
            operation: 'INSTALLING', runtimePhase: 'STARTED' })));
    ok('accepted 渲染即时激活语义', html.indexOf(ACTIVATED_NOTE) !== -1);
    ok('accepted 渲染 ok 色调', html.indexOf('pm-install-result-box--ok') !== -1);
    ok('accepted 渲染 pluginId', html.indexOf('ext-demo') !== -1);
    ok('accepted 渲染 version', html.indexOf('1.0.0') !== -1);
    ok('accepted 渲染运行阶段', html.indexOf('STARTED') !== -1);
})();

// C-2b) DUPLICATE：幂等结果保持 info 色调，不伪造重启提示。
(function () {
    const html = PM.renderInstallResultHtml(PM.buildInstallResult(installResponse(
        { outcome: 'DUPLICATE', accepted: true, message: '该版本插件已安装' })));
    ok('DUPLICATE info 色调', html.indexOf('pm-install-result-box--info') !== -1);
    ok('DUPLICATE 不渲染重启提示', html.indexOf('重启后') === -1);
})();

// C-3) errors / warnings 列表显示且全部转义（绝不注入 HTML）。
(function () {
    const evilDiag = '<img src=x onerror=alert(1)>';
    const evilDep = '<script>bad()</script>';
    const html = PM.renderInstallResultHtml(PM.buildInstallResult(installResponse(
        { outcome: 'REJECTED_INVALID', accepted: false, effectiveAfterRestart: false, status: 400,
            message: '<b>恶意 message</b>', diagnostics: [evilDiag], unsatisfiedDependencies: [evilDep] })));
    // 诊断（errors）转义
    ok('诊断项被转义（无原始 <img onerror）', html.indexOf('<img src=x onerror') === -1);
    ok('诊断项转义为实体', html.indexOf('&lt;img src=x onerror=alert(1)&gt;') !== -1);
    // 依赖（warnings）转义
    ok('依赖项被转义（无原始 <script>bad）', html.indexOf('<script>bad()') === -1);
    ok('依赖项转义为实体', html.indexOf('&lt;script&gt;bad()&lt;/script&gt;') !== -1);
    // message 也转义（后端文本一律不可信地注入）
    ok('message 被转义（无原始 <b>）', html.indexOf('<b>恶意 message</b>') === -1);
    ok('message 转义为实体', html.indexOf('&lt;b&gt;恶意 message&lt;/b&gt;') !== -1);
})();

// C-4) 空模型 → 空串（渲染层据此清空结果区）。
(function () {
    eq('空模型渲染为空串', PM.renderInstallResultHtml(null), '');
})();

// D) hasAcceptedExtension：本地扩展名预校验（大小写不敏感；非 .jar/.zip 一律拒绝）。
(function () {
    ok('小写 .zip 通过', PM.hasAcceptedExtension('plugin.zip') === true);
    ok('小写 .jar 通过', PM.hasAcceptedExtension('plugin.jar') === true);
    ok('大写 .JAR 通过', PM.hasAcceptedExtension('PLUGIN.JAR') === true);
    ok('大写 .ZIP 通过', PM.hasAcceptedExtension('PLUGIN.ZIP') === true);
    ok('混合大小写 .Jar 通过', PM.hasAcceptedExtension('a.Jar') === true);
    ok('.exe 拒绝', PM.hasAcceptedExtension('evil.exe') === false);
    ok('无扩展名拒绝', PM.hasAcceptedExtension('noext') === false);
    ok('双后缀 .jar.exe 拒绝', PM.hasAcceptedExtension('a.jar.exe') === false);
    ok('null 拒绝', PM.hasAcceptedExtension(null) === false);
    ok('空串拒绝', PM.hasAcceptedExtension('') === false);
})();

// —— A) installPackage：multipart 请求 / 默认 false / 结构化 4xx / 无文件不发请求 / 非结构化抛错 ——
async function apiTests() {
    const fileStub = { name: 'ext-demo.zip' };

    // A-1) 正常安装：multipart POST 到 INSTALL_URL，file 字段 + allowDowngrade 默认 false。
    fetchCalls.length = 0;
    nextFetchResponse = { ok: true, status: 200, body: installResponse({ outcome: 'INSTALLED' }) };
    const resp = await PM.installPackage(fileStub, false);
    eq('installPackage 返回结构化体', resp.outcome, 'INSTALLED');
    eq('installPackage 只调一次 fetch', fetchCalls.length, 1);
    eq('installPackage 请求路径 = /api/plugins/install', fetchCalls[0].url, '/api/plugins/install');
    eq('installPackage 方法 = POST', fetchCalls[0].opts.method, 'POST');
    const form = fetchCalls[0].opts.body;
    ok('installPackage body 是 FormData', form && typeof form.get === 'function');
    eq('installPackage form 的 file 字段 = 上传文件', form.get('file'), fileStub);
    eq('installPackage allowDowngrade 默认 false', form.get('allowDowngrade'), 'false');
    eq('installPackage 带 same-origin 凭据', fetchCalls[0].opts.credentials, 'same-origin');

    // A-2) allowDowngrade=true → 'true'
    fetchCalls.length = 0;
    nextFetchResponse = { ok: true, status: 200, body: installResponse({ outcome: 'DOWNGRADED' }) };
    await PM.installPackage(fileStub, true);
    eq('installPackage allowDowngrade=true', fetchCalls[0].opts.body.get('allowDowngrade'), 'true');

    // A-3) 4xx 仍返回结构化体（不抛）：结果区据 outcome 渲染。
    fetchCalls.length = 0;
    nextFetchResponse = { ok: false, status: 400, body: installResponse(
        { outcome: 'REJECTED_EMPTY', accepted: false, effectiveAfterRestart: false, status: 400 }) };
    const rej = await PM.installPackage(fileStub, false);
    eq('installPackage 4xx 返回结构化 outcome', rej.outcome, 'REJECTED_EMPTY');

    // A-4) 未选文件 → 不发请求、抛 localValidation。
    fetchCalls.length = 0;
    let localThrew = false;
    try { await PM.installPackage(null, false); } catch (e) { localThrew = !!(e && e.localValidation); }
    ok('installPackage 无文件 → 抛 localValidation', localThrew);
    eq('installPackage 无文件 → 不发请求', fetchCalls.length, 0);

    // A-5) 拿不到结构化响应（如 413 过大 / 网关无 outcome）→ 抛错带 httpStatus。
    fetchCalls.length = 0;
    nextFetchResponse = { ok: false, status: 413, body: null };
    let httpErr = null;
    try { await PM.installPackage(fileStub, false); } catch (e) { httpErr = e; }
    ok('installPackage 非结构化响应 → 抛 httpStatus', httpErr && httpErr.httpStatus === 413);

    // A-6) 非法扩展名（.exe）→ 本地拦截：不发请求、抛 localValidation(invalidExtension)。
    fetchCalls.length = 0;
    let extThrew = false;
    try { await PM.installPackage({ name: 'evil.exe' }, false); }
    catch (e) { extThrew = !!(e && e.localValidation && e.invalidExtension); }
    ok('installPackage 非法扩展名 → 抛 localValidation(invalidExtension)', extThrew);
    eq('installPackage 非法扩展名 → 不发请求', fetchCalls.length, 0);

    // A-7) 大小写 .JAR / .ZIP 视为合法扩展 → 通过本地校验并发请求。
    fetchCalls.length = 0;
    nextFetchResponse = { ok: true, status: 200, body: installResponse({ outcome: 'INSTALLED' }) };
    await PM.installPackage({ name: 'PLUGIN.JAR' }, false);
    eq('installPackage 大写 .JAR 通过本地校验并发请求', fetchCalls.length, 1);

    fetchCalls.length = 0;
    nextFetchResponse = { ok: true, status: 200, body: installResponse({ outcome: 'INSTALLED' }) };
    await PM.installPackage({ name: 'Archive.ZIP' }, false);
    eq('installPackage 大写 .ZIP 通过本地校验并发请求', fetchCalls.length, 1);
}

(async function () {
    await apiTests();
    console.log('pixiv-plugin-manage.test.js: ' + passed + ' assertions passed');
})().catch(err => {
    console.error('TEST FAILED:', err && err.message ? err.message : err);
    process.exit(1);
});
