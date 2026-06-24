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
    const calls = {search: 0, series: 0, user: 0, quick: 0};
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
        ensureWorkers: () => {},
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
const resetCalls = c => { c.search = c.series = c.user = c.quick = 0; };
const allFour = c => c.search === 1 && c.series === 1 && c.user === 1 && c.quick === 1;
const noneFired = c => c.search === 0 && c.series === 0 && c.user === 0 && c.quick === 0;

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

// ===== 5) addItemsToQueue 入队后同步全部四个预览 =====
{
    const {sandbox, queue, calls} = load();
    resetCalls(calls);
    const added = queue.addItemsToQueue(['333', '444'], [{}, {}], 'search', '', null, '');
    ok('5: 新增 2 项', added === 2);
    ok('5: 队列含 2 项', sandbox.state.queue.length === 2);
    ok('5: 四个 sync 各被回调一次（含 quick）', allFour(calls));
}

console.log(`\nbatch-queue-sync.test.js: ${passed} assertions passed (5 scenarios) ✓`);
