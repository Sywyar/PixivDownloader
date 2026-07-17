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
    const calls = {search: 0, series: 0, user: 0, quick: 0, workers: 0, statuses: []};
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
        setStatus: (message, tone) => { calls.statuses.push({message, tone}); },
        SINGLE_IMPORT_MODE: 'single-import',
        SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
        QUICK_FETCH_MODE: 'quick-fetch',
        bt(key, fallback, args) {
            const messages = {
                'demo:source.pixiv': 'Pixiv',
                'demo:source.douyin': '抖音',
                'queue.source.search': 'Search',
                'queue.source.series': 'Series',
                'queue.source.quick-fetch': 'Quick',
                'queue.source.import': 'Import',
                'queue.source.schedule': 'Schedule',
                'queue.unknown': 'Unknown'
            };
            let value = messages[key] || fallback || key;
            Object.entries(args || {}).forEach(([name, replacement]) => {
                value = value.replace('{' + name + '}', String(replacement));
            });
            return value;
        },
        esc: value => String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;'),
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

(async function () {

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
    sandbox.window.PixivBatch.queueTypes = {
        get() { return null; },
        dataSourceForType() {
            return {
                id: 'pixiv', displayNamespace: 'batch', displayI18nKey: 'data-source.pixiv'
            };
        }
    };
    resetCalls(calls);
    const added = queue.addItemsToQueue(['333', '444'], [
        {
            typeData: {input: 'https://example.test/work/333', token: 'abc'},
            cancelWorkKey: ' opaque/path ? # 中文 ',
            dataSource: {id: 'forged', displayNamespace: 'forged', displayI18nKey: 'source.forged'}
        },
        {}
    ], 'search', '', null, '');
    ok('5: 新增 2 项', added === 2);
    ok('5: 队列含 2 项', sandbox.state.queue.length === 2);
    ok('5: 新队列项的动态状态文案键初始为空', sandbox.state.queue[0].statusMessageKey === null);
    ok('5: 保留类型私有队列数据', sandbox.state.queue[0].typeData.input === 'https://example.test/work/333'
        && sandbox.state.queue[0].typeData.token === 'abc');
    ok('5: 顶层取消键按不透明原始字符串保留且不从展示 id 派生',
        sandbox.state.queue[0].cancelWorkKey === ' opaque/path ? # 中文 '
        && !Object.prototype.hasOwnProperty.call(sandbox.state.queue[1], 'cancelWorkKey'));
    ok('5: 仅持久化数据来源稳定 token 与展示键，不写入本地化标签',
        sandbox.state.queue[0].dataSource.id === 'pixiv'
        && sandbox.state.queue[0].dataSource.displayNamespace === 'batch'
        && sandbox.state.queue[0].dataSource.displayI18nKey === 'data-source.pixiv'
        && !Object.prototype.hasOwnProperty.call(sandbox.state.queue[0].dataSource, 'label'));
    ok('5: 四个 sync 各被回调一次（含 quick）', allFour(calls));
}

// ===== 6) 中性 hook 在 add/toggle 入口合并类型私有数据，并保留同来源点击移除判定 =====
{
    const {sandbox, queue} = load();
    const reasons = [];
    const behavior = relationMergeBehavior(reasons);
    sandbox.window.PixivBatch.queueTypes = {
        get: type => type === 'demo' ? behavior : null,
        dataSourceForType() {
            return {id: 'owned', displayNamespace: 'demo', displayI18nKey: 'source.owned'};
        }
    };
    const firstAdded = queue.addItemsToQueue(['demo-1'], [{kind: 'demo', typeData: {values: ['source-a']}}]);
    sandbox.state.queue[0].dataSource = {id: 'forged'};
    const duplicateAdded = queue.addItemsToQueue(['demo-1'], [{kind: 'demo', typeData: {values: ['source-b']}}]);
    ok('6: 新项计入 added、重复项只合并不重复计数', firstAdded === 1 && duplicateAdded === 0);
    ok('6: add 入口保序合并类型私有数据', sandbox.state.queue[0].typeData.values.join(',') === 'source-a,source-b');
    ok('6: 重复项修补始终以活动 acquisition 的 owner 绑定来源覆盖自报值',
        sandbox.state.queue[0].dataSource.id === 'owned');
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
            if (type === 'demo') return {
                canonicalUrl(item) { return item.typeData.input || item.typeData.url; }
            };
            if (type === 'novel') return {
                canonicalUrl(item) {
                    return `https://www.pixiv.net/novel/show.php?id=${item.novelId}`;
                }
            };
            return null;
        }
    };
    const lines = queue.buildQueueExportLines([
        {id: 'demo-input', kind: 'demo', title: 'Input', typeData: {input: 'https://example.test/input'}},
        {id: 'demo-url', kind: 'demo', title: 'URL', typeData: {url: 'https://example.test/url'}},
        {id: 'n2468', novelId: '2468', kind: 'novel', title: 'Novel'},
        {id: '12345', kind: 'illust', title: 'Pixiv'},
        {id: 'inactive', kind: 'inactive-plugin', title: 'Unavailable'}
    ]);
    ok('10: 类型 hook 的 input/url 进入实际导出行', lines[0] === 'https://example.test/input | Input'
        && lines[1] === 'https://example.test/url | URL');
    ok('10: 小说类型 hook 生成可重新导入的 Pixiv 小说链接',
        lines[2] === 'https://www.pixiv.net/novel/show.php?id=2468 | Novel');
    ok('10: 未贡献 hook 的 Pixiv 导出 URL 逐字保持旧格式',
        lines[3] === 'https://www.pixiv.net/artworks/12345 | Pixiv');
    ok('10: 未启用的非 Pixiv 类型不会伪装成 Pixiv URL',
        lines[4] === ' | Unavailable' && !lines[4].includes('pixiv.net'));
}

// ===== 11) 队列标签固定按数据来源、模式、年龄、插件贡献顺序渲染并转义 =====
{
    const {sandbox, queue, calls} = load();
    sandbox.window.PixivBatch.queueTypes = {
        get() { return null; },
        dataSourceForType(kind) {
            return kind === 'douyin'
                ? {id: 'douyin', displayNamespace: 'demo', displayI18nKey: 'source.douyin'}
                : {id: 'pixiv', displayNamespace: 'demo', displayI18nKey: 'source.pixiv'};
        },
        queueTags() {
            return [
                {id: 'media.ugoira', label: '<动图>'},
                {id: 'origin.collection', label: '珍藏集'}
            ];
        }
    };
    const html = queue.buildQueueItemHtml({
        id: '777', kind: 'illust', title: '<Title>', status: 'idle',
        source: 'search-douyin', xRestrict: 1
    });
    const sourceIndex = html.indexOf('data-source-id="pixiv">Pixiv');
    const modeIndex = html.indexOf('>Search</span>');
    const ratingIndex = html.indexOf('>R-18</span>');
    const pluginIndex = html.indexOf('data-queue-tag-id="media.ugoira"');
    ok('11: 标签顺序为数据来源、取得模式、年龄分级、插件标签',
        sourceIndex >= 0 && sourceIndex < modeIndex && modeIndex < ratingIndex
        && ratingIndex < pluginIndex);
    ok('11: 标题与插件标签仅按文本输出并完成 HTML 转义',
        html.includes('&lt;Title&gt;') && html.includes('&lt;动图&gt;')
        && !html.includes('<Title>') && !html.includes('><动图><'));

    const douyinSchedule = queue.buildQueueItemHtml({
        id: 'd1', kind: 'douyin', title: 'Scheduled', status: 'idle',
        source: 'schedule-douyin', xRestrict: null
    });
    const douyinSeries = queue.buildQueueItemHtml({
        id: 'd2', kind: 'douyin', title: 'Series', status: 'idle',
        source: 'series-douyin', xRestrict: 0
    });
    ok('11: 带类型后缀的计划与系列来源不会再误标为导入',
        douyinSchedule.includes('data-source-id="douyin">抖音')
        && douyinSchedule.includes('>Schedule</span>')
        && !douyinSchedule.includes('>Import</span>')
        && douyinSeries.includes('>Series</span>'));
}

// ===== 12) 外部类型 patch 只能原子更新受控字段，并立即统计 / 持久化 / 渲染 =====
{
    const {sandbox, queue} = load();
    const item = {
        id: 'demo-patch', kind: 'demo', status: 'downloading',
        downloadedCount: 0, totalImages: 1, lastMessage: 'old baked text'
    };
    sandbox.state.queue = [item];
    sandbox.testPatchItem = item;
    const calls = {stats: 0, save: 0, render: 0};
    sandbox.updateStats = () => { calls.stats++; };
    sandbox.saveQueue = () => { calls.save++; };
    sandbox.renderQueue = () => { calls.render++; };
    const completedAt = '2026-07-17T01:02:03.000Z';
    sandbox.testCompletedAt = completedAt;
    const result = vm.runInContext(`window.PixivBatch.queue.commitQueueItemPatch(testPatchItem, {
        status: 'failed',
        rawStatus: 'failed',
        failureCode: 'example.queue-failed',
        statusMessageKey: 'example-download:error.queue',
        downloadedCount: 0,
        totalImages: 1,
        cancelWorkKey: ' authoritative/work:key ',
        endTime: testCompletedAt
    })`, sandbox);
    ok('12: patch 绑定并返回活动队列项', result === item && item.status === 'failed');
    ok('12: 机器状态、命名空间文案键和 ISO 时间按既有口径写入',
        item.rawStatus === 'failed'
        && item.failureCode === 'example.queue-failed'
        && item.statusMessageKey === 'example-download:error.queue'
        && item.cancelWorkKey === ' authoritative/work:key '
        && item.endTime === completedAt);
    ok('12: 每次有效 patch 立即统计、持久化并渲染',
        calls.stats === 1 && calls.save === 1 && calls.render === 1);

    const snapshot = JSON.stringify(item);
    assert.throws(() => vm.runInContext(
        "window.PixivBatch.queue.commitQueueItemPatch(testPatchItem, {status:'completed',title:'forged'})",
        sandbox), /unsupported queue item patch field/);
    assert.throws(() => vm.runInContext(
        "window.PixivBatch.queue.commitQueueItemPatch(testPatchItem, {status:'completed',endTime:'not-an-iso-time'})",
        sandbox), /ISO-8601/);
    assert.throws(() => vm.runInContext(
        "window.PixivBatch.queue.commitQueueItemPatch(testPatchItem, {cancelWorkKey:'   '})",
        sandbox), /cancelWorkKey/);
    ok('12: 任一非法字段会在写入前原子拒绝', JSON.stringify(item) === snapshot);
    ok('12: 非法 patch 不触发统计、持久化或渲染',
        calls.stats === 1 && calls.save === 1 && calls.render === 1);
    assert.throws(() => vm.runInContext(
        "window.PixivBatch.queue.commitQueueItemPatch({id:'detached'}, {status:'failed'})", sandbox),
    /not active/);
    ok('12: 脱离当前队列的对象不可跨代回写', calls.stats === 1 && calls.save === 1 && calls.render === 1);
}

// ===== 13) 队列卡片动态翻译 statusMessageKey，并按 canonicalUrl / Pixiv fallback 决定链接 =====
{
    const {sandbox, queue} = load();
    sandbox.window.PixivBatch.queueTypes = {get() { return null; }, queueTags() { return []; }};
    const failed = {
        id: 'demo-link', kind: 'demo', title: 'Demo', status: 'failed',
        canonicalUrl: 'https://example.invalid/work/demo-link',
        statusMessageKey: 'example-download:error.queue',
        lastMessage: 'stale baked English',
        lastMessageParts: [{text: 'stale baked part', tone: 'error'}]
    };
    sandbox.bt = (key, fallback) => key === 'example-download:error.queue'
        ? 'Current English error' : (fallback || key);
    const english = queue.buildQueueItemHtml(failed);
    sandbox.bt = (key, fallback) => key === 'example-download:error.queue'
        ? '当前中文错误' : (fallback || key);
    const chinese = queue.buildQueueItemHtml(failed);
    ok('13: statusMessageKey 每次渲染按当前语言解析且优先于旧 lastMessage',
        english.includes('Current English error') && !english.includes('stale baked English')
        && !english.includes('stale baked part')
        && chinese.includes('当前中文错误') && !chinese.includes('Current English error'));
    ok('13: 非 Pixiv 类型卡片使用其 canonicalUrl',
        chinese.includes('href="https://example.invalid/work/demo-link"'));

    const novel = queue.buildQueueItemHtml({
        id: 'n2468', novelId: '2468', kind: 'novel', title: 'Novel', status: 'idle'
    });
    const illust = queue.buildQueueItemHtml({id: '1357', kind: 'illust', title: 'Illust', status: 'idle'});
    const unavailable = queue.buildQueueItemHtml({
        id: 'opaque', kind: 'inactive-plugin', title: 'Inactive', status: 'idle'
    });
    ok('13: 小说无 canonicalUrl 时保留 show.php fallback',
        novel.includes('href="https://www.pixiv.net/novel/show.php?id=2468"'));
    ok('13: Pixiv 插画无 canonicalUrl 时保留 artworks fallback',
        illust.includes('href="https://www.pixiv.net/artworks/1357"'));
    ok('13: 非 Pixiv 类型无 canonicalUrl 时不渲染伪链接',
        !unavailable.includes('<a href=') && !unavailable.includes('pixiv.net'));
}

// ===== 14) 下载中队列项仅在活动类型声明 cancel 且携稳定 workKey 时显示委托取消动作 =====
{
    const {sandbox, queue, calls} = load();
    sandbox.window.PixivBatch.queueTypes = {
        get() { return null; },
        queueTags() { return []; },
        canCancel(item) { return item && item.cancelWorkKey === 'raw/work-key'; }
    };
    const cancellable = queue.buildQueueItemHtml({
        id: 'demo:display-id', kind: 'demo', title: 'Running', status: 'downloading',
        cancelWorkKey: 'raw/work-key'
    });
    const unavailable = queue.buildQueueItemHtml({
        id: 'demo:display-id-2', kind: 'demo', title: 'Inactive', status: 'downloading'
    });
    ok('14: 可取消下载中行只携展示 id 定位属性，实际 raw key 留在队列模型交 bridge 读取',
        cancellable.includes('data-queue-cancel-id="demo:display-id"')
        && !cancellable.includes('raw/work-key'));
    ok('14: 类型失活或缺少 raw workKey 时不渲染取消动作',
        !unavailable.includes('data-queue-cancel-id'));
    ok('14: 新取消动作使用委托事件而不新增 inline onclick',
        !/data-queue-cancel-id=[^>]+onclick=/.test(cancellable)
        && typeof queue.bindQueueActions === 'function'
        && typeof queue.requestQueueItemCancel === 'function');
    ok('14: 请求成功或失败均不写入永久 cancelRequested 模型字段',
        !QUEUE_SOURCE.includes('cancelRequested'));

    const item = {
        id: 'demo:display-id', kind: 'demo', title: 'Running', status: 'downloading',
        cancelWorkKey: 'raw/work-key'
    };
    sandbox.state.queue = [item];
    let cancelledItem = null;
    sandbox.window.PixivBatch.queueTypes = {
        canCancel() { return true; },
        async cancel(candidate) { cancelledItem = candidate; }
    };
    let clickHandler = null;
    const root = {
        addEventListener(type, handler) {
            if (type === 'click') clickHandler = handler;
        }
    };
    const button = {
        closest(selector) { return selector === '[data-queue-cancel-id]' ? this : null; },
        getAttribute(name) { return name === 'data-queue-cancel-id' ? item.id : null; }
    };
    ok('14: 队列动作根节点只绑定一次委托 click',
        queue.bindQueueActions(root) === true && queue.bindQueueActions(root) === false
        && typeof clickHandler === 'function');
    clickHandler({target: button, preventDefault() {}, stopPropagation() {}});
    await new Promise(resolve => setImmediate(resolve));
    ok('14: 委托点击把真实队列 item 交给 queueTypes.cancel，由 bridge 读取 raw key',
        cancelledItem === item && cancelledItem.cancelWorkKey === 'raw/work-key');
    ok('14: 取消请求成功只显示瞬时 i18n 状态提示',
        calls.statuses.at(-1).tone === 'success'
        && calls.statuses.at(-1).message === '已请求取消下载'
        && !Object.prototype.hasOwnProperty.call(item, 'cancelRequested'));

    sandbox.window.PixivBatch.queueTypes.cancel = async () => { throw new Error('HTTP 503'); };
    clickHandler({target: button, preventDefault() {}, stopPropagation() {}});
    await new Promise(resolve => setImmediate(resolve));
    ok('14: HTTP 失败只显示错误提示且不留下永久请求态',
        calls.statuses.at(-1).tone === 'error'
        && calls.statuses.at(-1).message === '取消下载请求失败'
        && !Object.prototype.hasOwnProperty.call(item, 'cancelRequested'));
}

console.log(`\nbatch-queue-sync.test.js: ${passed} assertions passed (14 scenarios) ✓`);
})().catch(error => {
    console.error('TEST FAILED:', error && error.stack ? error.stack : error);
    process.exit(1);
});
