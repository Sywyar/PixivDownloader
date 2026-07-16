'use strict';
/*
 * batch-queue.js 队列变更后预览同步的运行态测试。
 *
 * 背景修复：清除队列 / 移除单项 / 入队后，下载页四个模式预览网格（快捷获取 / User / Search / 系列）
 * 的「✓ 在队列中」标记应与最新 state.queue 对齐。此前清除队列（stopAndClear）只同步 Search、增删
 * （addItemsToQueue / removeFromQueue）只同步 Search/Series/User，均漏了「快捷获取」，导致其预览残留
 * 过期标记。修复引入聚合函数 syncAllResultsQueueState() 统一回调四个 sync，并接到三处队列变更入口。
 *
 * 无浏览器 / 无 jsdom：在 Node 的 vm 沙箱里加载**真实**的 batch-queue.js，用四个 sync 函数 spy + 最小
 * 宿主桩驱动**真实**的 syncAllResultsQueueState / addItemsToQueue / removeFromQueue，断言四个模式预览
 * 同步都被回调（重点守卫此前缺席的「快捷获取」）。stopAndClear 同样调用聚合函数，其断言由此一并覆盖。
 *
 * 运行： node src/test/js/batch-queue-sync.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
const QUEUE_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-queue.js'), 'utf8');

// 在沙箱里加载真实 batch-queue.js，返回 facade + 四个 sync 的调用计数器。
function load() {
    const calls = {search: 0, series: 0, user: 0, quick: 0, workers: 0};
    const sandbox = {
        window: {PixivBatch: {}},
        state: {queue: [], isRunning: false},
        // —— 四个模式预览同步函数 spy（本测试的断言对象，均非 batch-queue.js 自身定义，故桩不被遮蔽）——
        syncSearchResultsQueueState: () => { calls.search++; },
        syncSeriesResultsQueueState: () => { calls.series++; },
        syncUserResultsQueueState: () => { calls.user++; },
        syncQuickQueueState: () => { calls.quick++; },
        // —— batch-queue.js 调用期引用的外部宿主桩 ——
        normalizeAuthorId: v => v,
        ensureWorkers: () => { calls.workers++; },
        updateAdminPackButton: () => {},
        SINGLE_IMPORT_MODE: 'single-import',
        SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
        document: {getElementById: () => ({textContent: '', innerHTML: ''})},
        console: {warn() {}, log() {}, error() {}}
    };
    vm.createContext(sandbox);
    vm.runInContext(QUEUE_SOURCE, sandbox);
    // 中和与本测试正交的 DOM 渲染 / 持久化内部函数（自由变量在调用期动态解析，覆盖全局绑定后即生效）。
    sandbox.updateStats = () => {};
    sandbox.saveQueue = () => {};
    sandbox.renderQueue = () => {};
    return {sandbox, calls, queue: sandbox.window.PixivBatch.queue};
}

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }
const resetCalls = c => { c.search = c.series = c.user = c.quick = c.workers = 0; };
const allFour = c => c.search === 1 && c.series === 1 && c.user === 1 && c.quick === 1;
const noneFired = c => c.search === 0 && c.series === 0 && c.user === 0 && c.quick === 0;
function relationMergeBehavior(reasons) {
    return {
        mergeQueueTypeData(current, incoming, context) {
            reasons.push(context.reason);
            const currentValues = current && Array.isArray(current.values) ? current.values : [];
            const incomingValues = incoming && Array.isArray(incoming.values) ? incoming.values : [];
            const values = currentValues.slice();
            let keepExisting = false;
            incomingValues.forEach(value => {
                if (values.includes(value)) return;
                values.push(value);
                keepExisting = true;
            });
            return {typeData: {values}, keepExisting, reprocessExisting: keepExisting};
        }
    };
}

// ===== 1) facade 暴露 syncAllResultsQueueState =====
{
    const {queue} = load();
    ok('1: facade 暴露 syncAllResultsQueueState', typeof queue.syncAllResultsQueueState === 'function');
}

// ===== 2) syncAllResultsQueueState() 一次性回调四个模式预览同步（含此前缺席的 quick）=====
{
    const {queue, calls} = load();
    queue.syncAllResultsQueueState();
    ok('2: 四个 sync 各被回调一次', allFour(calls));
    ok('2: 快捷获取 sync 被回调（回归守卫）', calls.quick === 1);
}

// ===== 3) removeFromQueue 命中后同步全部四个预览（清除队列 stopAndClear 走同一聚合函数）=====
{
    const {sandbox, queue, calls} = load();
    sandbox.state.queue = [{id: '111', status: 'idle'}];
    resetCalls(calls);
    const removed = queue.removeFromQueue('111');
    ok('3: 移除返回 true', removed === true);
    ok('3: 队列已清空', sandbox.state.queue.length === 0);
    ok('3: 四个 sync 各被回调一次（含 quick）', allFour(calls));
}

// ===== 4) removeFromQueue 未命中 / 下载中不触发任何预览同步 =====
{
    const {sandbox, queue, calls} = load();
    sandbox.state.queue = [{id: '222', status: 'downloading'}];
    resetCalls(calls);
    const r1 = queue.removeFromQueue('999');   // 不存在
    const r2 = queue.removeFromQueue('222');   // 下载中不可移除
    ok('4: 两次移除均返回 false', r1 === false && r2 === false);
    ok('4: 未发生任何预览同步', noneFired(calls));
}

// ===== 5) addItemsToQueue 入队后同步全部四个预览，并保留类型私有队列数据 =====
{
    const {sandbox, queue, calls} = load();
    resetCalls(calls);
    const added = queue.addItemsToQueue(['333', '444'], [
        {typeData: {input: 'https://example.test/work/333', token: 'abc'}},
        {}
    ], 'search', '', null, '');
    ok('5: 新增 2 项', added === 2);
    ok('5: 队列含 2 项', sandbox.state.queue.length === 2);
    ok('5: 保留类型私有队列数据', sandbox.state.queue[0].typeData.input === 'https://example.test/work/333'
        && sandbox.state.queue[0].typeData.token === 'abc');
    ok('5: 四个 sync 各被回调一次（含 quick）', allFour(calls));
}

// ===== 6) 中性 hook 在 add/toggle 入口合并类型私有数据，并保留同来源点击移除判定 =====
{
    const {sandbox, queue} = load();
    const reasons = [];
    const behavior = relationMergeBehavior(reasons);
    sandbox.window.PixivBatch.queueTypes = {get: type => type === 'demo' ? behavior : null};
    const firstAdded = queue.addItemsToQueue(['demo-1'], [{kind: 'demo', typeData: {values: ['source-a']}}]);
    const duplicateAdded = queue.addItemsToQueue(['demo-1'], [{kind: 'demo', typeData: {values: ['source-b']}}]);
    ok('6: 新项计入 added、重复项只合并不重复计数', firstAdded === 1 && duplicateAdded === 0);
    ok('6: add 入口保序合并类型私有数据', sandbox.state.queue[0].typeData.values.join(',') === 'source-a,source-b');
    const sameSource = queue.reconcileQueueItemTypeData(
        sandbox.state.queue[0], {kind: 'demo', typeData: {values: ['source-b']}}, 'toggle');
    ok('6: 同来源 toggle 不要求保留现有项', sameSource.keepExisting === false);
    const newSource = queue.reconcileQueueItemTypeData(
        sandbox.state.queue[0], {kind: 'demo', typeData: {values: ['source-c']}}, 'toggle');
    ok('6: 新来源 toggle 合并并要求保留现有项', newSource.keepExisting === true
        && sandbox.state.queue[0].typeData.values.join(',') === 'source-a,source-b,source-c');
    ok('6: hook 收到 add/toggle 中性上下文', reasons.includes('add') && reasons.includes('toggle'));
}

// ===== 7) 持久化恢复会归一化首项并合并同 id 的残留重复项 =====
{
    const {sandbox, queue} = load();
    const reasons = [];
    const behavior = relationMergeBehavior(reasons);
    sandbox.window.PixivBatch.queueTypes = {get: type => type === 'demo' ? behavior : null};
    const restored = queue.dedupeQueueItems([
        {id: 'demo-1', kind: 'demo', typeData: {values: ['source-a']}},
        {id: 'demo-1', kind: 'demo', typeData: {values: ['source-b']}},
        {id: 'demo-2', kind: 'demo', typeData: {values: ['source-c']}}
    ]);
    ok('7: 恢复后仍按队列 id 去重', restored.length === 2);
    ok('7: 恢复路径保留重复项携带的类型关系', restored[0].typeData.values.join(',') === 'source-a,source-b');
    ok('7: 恢复调用中性 hook', reasons.length === 3 && reasons.every(reason => reason === 'restore'));
}

// ===== 8) 所有卡片 toggle 入口都先调用中性合并 hook =====
{
    const modeSource = name => fs.readFileSync(path.join(STATIC, 'modes', name + '.js'), 'utf8');
    ok('8: Search 卡片 toggle 接入合并 hook',
        modeSource('search').includes("reconcileQueueItemTypeData(alreadyInQueue, meta, 'toggle')"));
    ok('8: User 卡片 toggle 接入合并 hook',
        modeSource('user').includes("reconcileQueueItemTypeData(alreadyInQueue, meta, 'toggle')"));
    ok('8: Series 卡片 toggle 接入合并 hook',
        modeSource('series').includes("reconcileQueueItemTypeData(alreadyInQueue, meta, 'toggle')"));
    const quickMatches = modeSource('quick-fetch').match(
        /reconcileQueueItemTypeData\(existing, meta, 'toggle'\)/g) || [];
    ok('8: Quick 外层与内层卡片 toggle 均接入合并 hook', quickMatches.length === 2);
}

// ===== 9) 新来源晚于终态到达时重置队列项，并在队列运行中补 worker =====
{
    const {sandbox, queue, calls} = load();
    const behavior = relationMergeBehavior([]);
    sandbox.window.PixivBatch.queueTypes = {get: type => type === 'demo' ? behavior : null};
    const completed = {
        id: 'demo-late', kind: 'demo', status: 'completed',
        typeData: {values: ['source-a']}, startTime: 'old-start', endTime: 'old-end',
        lastMessage: 'old-message', lastMessageParts: [{text: 'old'}]
    };
    sandbox.state.queue = [completed];
    const stoppedMerge = queue.reconcileQueueItemTypeData(
        completed, {kind: 'demo', typeData: {values: ['source-b']}}, 'toggle');
    ok('9: 非运行队列的 completed 项收到新来源后回到 idle', stoppedMerge.requeued === true
        && completed.status === 'idle' && completed.startTime === null && completed.endTime === null);

    completed.status = 'completed';
    completed.startTime = 'another-start';
    completed.endTime = 'another-end';
    sandbox.state.isRunning = true;
    resetCalls(calls);
    const runningMerge = queue.reconcileQueueItemTypeData(
        completed, {kind: 'demo', typeData: {values: ['source-c']}}, 'add');
    ok('9: 运行队列的 completed 项收到新来源后回到 pending', runningMerge.requeued === true
        && completed.status === 'pending');
    ok('9: 运行中重排终态项会补足 worker', calls.workers === 1);
}

// ===== 10) 混合队列导出使用类型 canonicalUrl，未贡献 hook 的 Pixiv 保持旧 URL =====
{
    const {sandbox, queue} = load();
    sandbox.window.PixivBatch.queueTypes = {
        get(type) {
            return type === 'demo' ? {
                canonicalUrl(item) { return item.typeData.input || item.typeData.url; }
            } : null;
        }
    };
    const lines = queue.buildQueueExportLines([
        {id: 'demo-input', kind: 'demo', title: 'Input', typeData: {input: 'https://example.test/input'}},
        {id: 'demo-url', kind: 'demo', title: 'URL', typeData: {url: 'https://example.test/url'}},
        {id: '12345', kind: 'illust', title: 'Pixiv'},
        {id: 'inactive', kind: 'inactive-plugin', title: 'Unavailable'}
    ]);
    ok('10: 类型 hook 的 input/url 进入实际导出行', lines[0] === 'https://example.test/input | Input'
        && lines[1] === 'https://example.test/url | URL');
    ok('10: 未贡献 hook 的 Pixiv 导出 URL 逐字保持旧格式',
        lines[2] === 'https://www.pixiv.net/artworks/12345 | Pixiv');
    ok('10: 未启用的非 Pixiv 类型不会伪装成 Pixiv URL',
        lines[3] === ' | Unavailable' && !lines[3].includes('pixiv.net'));
}

console.log(`\nbatch-queue-sync.test.js: ${passed} assertions passed (10 scenarios) ✓`);
