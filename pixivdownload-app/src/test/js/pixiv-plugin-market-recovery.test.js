'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const staticRoot = path.join(__dirname, '../../main/resources/static');

test('恢复候选按 ID 和 GUI 分类置顶，默认隐藏不遮挡，退出恢复模式后撤销候选', async () => {
    const f = fixture();
    f.setReport({active: true, focus: {plugins: {required: 'required', dependency: 'dependency'}, categories: ['ui']}});
    await f.market.recovery.refresh();
    const entries = [
        {pluginId: 'ordinary', market: {recommended: true}},
        {pluginId: 'required', market: {defaultInstalled: true}},
        {pluginId: 'dependency', market: {category: 'dependency'}},
        {pluginId: 'gui', market: {category: 'ui', defaultInstalled: true}}
    ];
    const options = {hideDefaultInstalled: true, hideDependencies: true};
    assert.deepEqual(Array.from(f.market.data.filterAndSort(entries, options), item => item.pluginId),
        ['required', 'dependency', 'gui', 'ordinary']);
    assert.equal(f.market.data.cardModel(entries[3]).focusReason, 'gui');
    assert.equal(f.market.data.cardModel(entries[1]).suspectedProblem, false);
    f.setReport({active: false});
    await f.market.recovery.refresh();
    assert.deepEqual(Array.from(f.market.data.filterAndSort(entries, options), item => item.pluginId), ['ordinary']);
});

test('相同版本哈希差异与版本更新、未知摘要、开发加载和待重启分别呈现', async () => {
    const f = fixture();
    f.setReport({active: false, installationBlocked: false});
    await f.market.recovery.refresh();
    const pkg = {version: '2.0', sha256: 'a'.repeat(64), compatible: true, installationMatch: 'DIFFERENT_ARTIFACT'};
    const entry = {pluginId: 'fixture', latestVersion: '2.0', packages: [pkg], installStatus: 'NOT_INSTALLED',
        installation: {state: 'PRESENT', version: '2.0', sha256: 'b'.repeat(64), runtimeStatus: 'STARTED', installedArtifactsEnabled: true}};
    assert.equal(f.market.data.cardModel(entry).artifactMismatch, true);
    assert.equal(f.market.data.installationState(entry, pkg), 'mismatch');
    entry.installation.sha256 = 'A'.repeat(64);
    assert.equal(f.market.data.artifactMismatch(entry, pkg), false);
    entry.installation.sha256 = 'unknown';
    assert.equal(f.market.data.artifactMismatch(entry, pkg), false);
    entry.installation.sha256 = 'b'.repeat(64);
    entry.installation.version = '1.0';
    assert.equal(f.market.data.artifactMismatch(entry, pkg), false);
    entry.installation.runtimeVersion = '0.9';
    assert.equal(f.market.data.installationState(entry, pkg), 'runtime-different');
    entry.installation = {state: 'ABSENT', runtimeStatus: 'STARTED', installedArtifactsEnabled: false};
    pkg.installationMatch = 'NOT_INSTALLED';
    assert.equal(f.market.data.installationState(entry, pkg), 'development');
    assert.equal(f.market.data.selectedInstallStatus(entry, pkg), 'INSTALL_DISTRIBUTION');
    assert.equal(f.market.data.installResultStatus({accepted: true, activationBlockedByDevelopmentMode: true},
        'INSTALL_DISTRIBUTION'), 'STORED_DEVELOPMENT');
    pkg.installable = false;
    pkg.verification = {status: 'INVALID_SIGNATURE'};
    assert.equal(f.market.data.selectedInstallStatus(entry, pkg), 'INVALID_SIGNATURE');
    entry.installation.installedArtifactsEnabled = true;
    assert.equal(f.market.data.installationState(entry, pkg), 'runtime-only');
    for (const [local, expected] of [
        [{state: 'UNKNOWN'}, 'unknown'], [{state: 'PRESENT'}, 'not-loaded'],
        [{runtimeStatus: 'DISABLED'}, 'disabled'], [{runtimeStatus: 'STOPPED'}, 'stopped'],
        [{state: 'PRESENT', installedArtifactsEnabled: false}, 'stored-development'],
        [{runtimeStatus: 'INCOMPATIBLE'}, 'incompatible'], [{runtimeStatus: 'INCOMPATIBLE_REQUIRED'}, 'incompatible'],
        [{runtimeStatus: 'MISSING_REQUIRED'}, 'dependency'], [{runtimeStatus: 'LOADED'}, 'not-started']
    ]) assert.equal(f.market.data.installationState({installation: local}, pkg), expected);
});

test('恢复候选补查初始页外 ID 和 GUI 分类，局部失败保留目录，后续分页去重', async () => {
    const f = fixture();
    f.setReport({active: true, focus: {plugins: {outside: 'missing', absent: 'required'}, categories: ['ui']}});
    await f.market.recovery.refresh();
    Object.assign(f.sandbox, {AbortController, setTimeout, clearTimeout});
    const calls = [];
    f.sandbox.fetch = async url => {
        calls.push(url);
        if (url.includes('/absent')) return {ok: false, status: 404, json: async () => ({})};
        const data = url.includes('/outside') ? {pluginId: 'outside'}
            : url.includes('category=ui') ? {generation: 'same', entries: [{pluginId: 'gui', market: {category: 'ui'}}]}
            : {enabled: true, repositoryId: 'repo', generation: 'same', nextCursor: 'next', entries: [{pluginId: 'ordinary'}]};
        return {ok: true, json: async () => data};
    };
    vm.runInContext(fs.readFileSync(path.join(staticRoot, 'plugin-market/plugin-market-api.js'), 'utf8'), f.sandbox);
    const result = await f.market.api.fetchCatalog('repo');
    assert.deepEqual(Array.from(result.entries, item => item.pluginId), ['ordinary', 'gui', 'outside']);
    assert.equal(result.focusIncomplete, true);
    assert.equal(result.nextCursor, 'next');
    assert.equal(calls.length, 4);
    await f.market.api.fetchCatalog('repo', {cursor: 'next'});
    assert.equal(calls.length, 5, '普通分页不重复补查');
    assert.equal(f.market.data.mergeEntries(result.entries, [{pluginId: 'outside'}, {pluginId: 'later'}]).length, 4);
});

test('补查有累计条目和并发上限，切仓库取消后不再发布旧目录', async () => {
    const f = fixture();
    f.setReport({active: true, focus: {plugins: Object.fromEntries(
        Array.from({length: 40}, (_, i) => ['plugin-' + i, 'missing'])), categories: []}});
    await f.market.recovery.refresh();
    Object.assign(f.sandbox, {AbortController, DOMException, setTimeout, clearTimeout});
    let active = 0, maximum = 0, lookups = 0, waiting = false;
    f.sandbox.fetch = async (url, options) => {
        if (!url.includes('/plugins/')) return {ok: true, json: async () => ({enabled: true, repositoryId: 'repo', entries: []})};
        active++; lookups++; maximum = Math.max(maximum, active);
        try {
            if (waiting) await new Promise((resolve, reject) => {
                options.signal.addEventListener('abort', () => reject(new DOMException('Cancelled', 'AbortError')), {once: true});
            });
            else await new Promise(resolve => setImmediate(resolve));
            return {ok: true, json: async () => ({pluginId: url.split('/').pop().split('?')[0]})};
        } finally { active--; }
    };
    vm.runInContext(fs.readFileSync(path.join(staticRoot, 'plugin-market/plugin-market-api.js'), 'utf8'), f.sandbox);
    const result = await f.market.api.fetchCatalog('repo');
    assert.equal(lookups, 32);
    assert.equal(maximum, 3);
    assert.equal(result.focusIncomplete, true);
    waiting = true;
    const pending = f.market.api.fetchCatalog('repo');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(active, 3);
    f.market.api.cancelCatalog();
    await assert.rejects(pending, {name: 'AbortError'});
    assert.equal(active, 0);
});

function node(tag) {
    return {tag, children: [], listeners: {}, classList: {toggle() {}},
        appendChild(child) { this.children.push(child); },
        replaceChildren() { this.children = []; },
        setAttribute(name, value) { this[name] = value; },
        addEventListener(name, fn) { this.listeners[name] = fn; }};
}
function fixture() {
    const root = node('section');
    const sandbox = {document: {createElement: node, getElementById: () => root, addEventListener() {}},
        addEventListener() {}, console};
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    for (const file of ['js/pixiv-plugin-presentation-tokens.js', 'plugin-market/plugin-market-core.js',
        'plugin-market/plugin-market-data.js', 'plugin-market/plugin-market-recovery.js']) {
        vm.runInContext(fs.readFileSync(path.join(staticRoot, file), 'utf8'), sandbox);
    }
    const market = sandbox.PixivPluginMarket;
    let report = {active: false, actionsAllowed: false, installationBlocked: false};
    const calls = [];
    market.api = {
        fetchRecovery: async () => report,
        recoveryAction: async action => { calls.push(action); return {accepted: true}; }
    };
    sandbox.PixivFeedback = {confirm: async () => true};
    market.recovery.mount();
    const buttons = () => root.children.find(child => child.className === 'pmk-recovery-actions').children;
    return {root, market, calls, sandbox, buttons, setReport(value) { report = value; }};
}

test('正常模式和无法确认状态时，前端按钮与动作都禁用', async () => {
    const f = fixture();
    assert.ok(f.buttons().every(button => button.disabled));
    await f.market.recovery.refresh();
    assert.ok(f.buttons().every(button => button.disabled));
    assert.equal(await f.market.recovery.perform('restart'), false);
    assert.equal(await f.market.recovery.perform('exit'), false);
    f.market.api.fetchRecovery = async () => { throw new Error('offline'); };
    await f.market.recovery.refresh();
    assert.ok(f.market.recovery.installationBlocked());
    assert.ok(f.buttons().every(button => button.disabled));
    assert.deepEqual(f.calls, []);
});

test('无 GUI 允许操作，但确认后状态已恢复或远端不获准时不能提交', async () => {
    const f = fixture();
    f.setReport({active: true, desktopUnavailable: true, actionsAllowed: true});
    await f.market.recovery.refresh();
    assert.ok(f.buttons().every(button => !button.disabled));
    f.sandbox.PixivFeedback.confirm = async () => {
        f.setReport({active: false, actionsAllowed: false});
        return true;
    };
    assert.equal(await f.market.recovery.perform('restart'), false);
    f.setReport({active: true, actionsAllowed: false});
    await f.market.recovery.refresh();
    assert.equal(await f.market.recovery.perform('exit'), false);
    assert.deepEqual(f.calls, []);
});

test('恢复动作失败可重试，成功后禁用重复提交，摘要只能作为文本呈现', async () => {
    const f = fixture();
    const detail = '<img src=x onerror=alert(1)>';
    const advice = 'check permissions\n\ncheck transaction records';
    f.setReport({active: true, actionsAllowed: true, installationBlocked: true, advice, errors: [detail]});
    await f.market.recovery.refresh();
    f.market.api.recoveryAction = async () => { throw new Error('launch failed'); };
    assert.equal(await f.market.recovery.perform('restart'), false);
    assert.ok(f.buttons().every(button => !button.disabled));
    const errors = f.root.children.find(child => child.tag === 'details').children.find(child => child.tag === 'ul');
    assert.match(f.root.children[0].textContent, /recovery.no-gui/);
    assert.equal(errors.children[0].textContent, detail);
    assert.equal(f.root.children.find(child => child.className === 'pmk-recovery-advice').textContent, advice);
    f.market.api.recoveryAction = async action => { f.calls.push(action); return {accepted: true}; };
    assert.equal(await f.market.recovery.perform('restart'), true);
    assert.equal(await f.market.recovery.perform('restart'), false);
    assert.ok(f.buttons().every(button => button.disabled));
    assert.deepEqual(f.calls, ['restart']);
});

test('只有明确失败或崩溃会标记疑似故障，其余安装与运行状态不会误标', async () => {
    const f = fixture();
    await f.market.recovery.refresh();
    for (const status of ['INSTALLED', 'RESOLVED', 'LOADED', 'STARTED', 'STOPPED', 'DISABLED',
        'INCOMPATIBLE', 'MISSING_REQUIRED', 'INCOMPATIBLE_REQUIRED', undefined]) {
        const plugin = {pluginId: 'fixture', installation: {state: 'PRESENT', runtimeStatus: status}};
        assert.equal(f.market.data.cardModel(plugin).suspectedProblem, false, String(status));
    }
    for (const status of ['FAILED', 'CRASHED']) {
        const plugin = {pluginId: 'fixture', installation: {state: 'PRESENT', runtimeStatus: status}};
        assert.equal(f.market.data.cardModel(plugin).suspectedProblem, true, status);
    }
});

test('旧状态响应不能覆盖新状态，取消确认不发送请求', async () => {
    const f = fixture();
    let finish;
    f.market.api.fetchRecovery = () => new Promise(resolve => { finish = resolve; });
    const first = f.market.recovery.refresh();
    const old = finish;
    const second = f.market.recovery.refresh();
    finish({active: false, actionsAllowed: false});
    await second;
    old({active: true, actionsAllowed: true});
    await first;
    assert.ok(f.buttons().every(button => button.disabled));
    f.market.api.fetchRecovery = async () => ({active: true, actionsAllowed: true});
    await f.market.recovery.refresh();
    f.sandbox.PixivFeedback.confirm = async () => false;
    assert.equal(await f.market.recovery.perform('exit'), false);
    assert.deepEqual(f.calls, []);
});

test('明确失败插件优先排序且不被默认隐藏，未知安装态不标记为故障', async () => {
    const f = fixture();
    f.setReport({active: true, actionsAllowed: true, installationBlocked: false});
    await f.market.recovery.refresh();
    const healthy = {pluginId: 'healthy', market: {recommended: true}};
    const failed = {pluginId: 'failed', installation: {state: 'PRESENT', runtimeStatus: 'FAILED'},
        market: {defaultInstalled: true, category: 'dependency'}};
    const unknown = {pluginId: 'unknown', installation: {state: 'UNKNOWN'}};
    const options = {sort: 'name', hideDefaultInstalled: true, hideDependencies: true};
    const result = f.market.data.filterAndSort([healthy, unknown, failed], options);
    assert.deepEqual(Array.from(result, entry => entry.pluginId), ['failed', 'healthy', 'unknown']);
    assert.equal(f.market.data.cardModel(failed).suspectedProblem, true);
    assert.equal(f.market.data.cardModel(unknown).suspectedProblem, false);
    const pkg = {compatible: true, installable: true, installationMatch: 'SAME_ARTIFACT'};
    assert.equal(f.market.data.selectedInstallStatus(failed, pkg), 'REINSTALL');
    assert.equal(f.market.data.installResultStatus({accepted: true, effectiveAfterRestart: true}, 'REINSTALL'), 'PENDING_RESTART');
    f.setReport({active: true, actionsAllowed: true, installationBlocked: true});
    await f.market.recovery.refresh();
    assert.equal(f.market.data.selectedInstallStatus(failed, pkg), 'RECOVERY_BLOCKED');
});
