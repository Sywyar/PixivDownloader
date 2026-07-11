'use strict';
/*
 * 下载工作台「队列 / 统计 + 速度 / 计划任务本轮队列详情」Vue reactive 岛（batch-queue-vue.js,
 * window.PixivBatch.queueVue）+ batch-queue.js 兼容门面分支的运行态测试。
 *
 * 无浏览器 / 无 jsdom：最小 DOM + 可控 requestAnimationFrame + 假 PixivVue helper（reactive 为恒等、
 * mountOn 记录挂载并可模拟挂载失败 / 运行时加载失败）在 Node 的 vm 沙箱里加载**真实**源码，断言：
 *   1) Vue 成功 / Vue 缺失 / Vue 运行时加载失败 / 挂载失败四条路径的激活与优雅降级。
 *   2) 高频合批：N 次 sync 在一帧内只触发一次 store 扇出（requestAnimationFrame 合并、同 key 去重）。
 *   3) 普通队列门面 renderQueue() 在 Vue 激活时不整队列重建 #queue-list（连续 N 次仅合并、innerHTML 不被命令式重写）；
 *      Vue 缺失时命令式回退仍整块渲染。
 *   4) 统计卡 / 速度卡 / 当前卡门面（updateStats / renderDownloadSpeed / setCurrent）在 Vue 激活时写 reactive store、
 *      缺失时命令式写 DOM；速度卡启动 / 采样 / 单位切换 / 停止归零两路都覆盖。
 *   5) 计划任务本轮队列详情岛：ensure → 异步挂载 → active 后 reactive 同步；连续多条 SSE 同步不整块重建
 *      .schedule-queue-body；卡片 body 被替换（detached）后探测失效并重挂；折叠 / 下线卸载；滚动位置在重挂时保留。
 *   6) 组件契约：列表 / 统计 / 当前卡 / 计划详情组件的 template 镜像 DOM 结构、行与当前卡共用 buildQueueItemHtml /
 *      formatCurrentCardHtml（不分叉），标签经 bt 派生（语言切换由门面重调触发重渲染）。
 *
 * 运行： node src/test/js/batch-queue-vue.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', '..', '..', 'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static', 'pixiv-batch');
const VUE_SRC = fs.readFileSync(path.join(STATIC, 'batch-queue-vue.js'), 'utf8');
const QUEUE_SRC = fs.readFileSync(path.join(STATIC, 'batch-queue.js'), 'utf8');

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }
const tick = () => new Promise(r => setTimeout(r, 0));

/* ============================================================
   最小 DOM
============================================================ */
class El {
    constructor(tag) {
        this.tag = String(tag).toLowerCase();
        this.attrs = {};
        this.children = [];
        this.parent = null;
        this._html = '';
        this.textContent = '';
        this.style = {};
        this.scrollTop = 0;
        this.__detached = false;
        this.innerHTMLSets = 0;   // 整块 innerHTML 重写计数（守卫「不整队列 / 整块重建」）
    }
    setAttribute(k, v) { this.attrs[k] = String(v); }
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k) ? this.attrs[k] : null; }
    hasAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k); }
    get innerHTML() { return this._html; }
    set innerHTML(v) { this._html = String(v); this.innerHTMLSets++; this.children = []; }
    appendChild(c) { c.parent = this; this.children.push(c); return c; }
    matches(sel) {
        if (sel.charAt(0) === '.') return (this.attrs.class || '').split(/\s+/).indexOf(sel.slice(1)) >= 0;
        if (sel.charAt(0) === '#') return this.attrs.id === sel.slice(1);
        return this.tag === sel.toLowerCase();
    }
    querySelector(sel) {
        let found = null;
        (function walk(n) {
            if (found) return;
            n.children.forEach(c => { if (found) return; if (c.matches(sel)) found = c; walk(c); });
        })(this);
        return found;
    }
}

function makeEl(tag, opts) {
    const el = new El(tag);
    opts = opts || {};
    if (opts.id) el.attrs.id = opts.id;
    if (opts.class) el.attrs.class = opts.class;
    (opts.children || []).forEach(c => el.appendChild(c));
    return el;
}

// 构造下载工作台所需的 DOM：dash-stats（6 卡）/ current-card / queue-list / stats-bar / 速度 span。
function makeBatchDocument() {
    const dashStats = makeEl('div', { class: 'dash-stats' });
    const currentCard = makeEl('div', { id: 'current-card' });
    const queueList = makeEl('div', { id: 'queue-list' });
    const statsBar = makeEl('div', { id: 'stats-bar' });
    const speedValue = makeEl('span', { id: 'stat-speed-value' });
    const speedUnit = makeEl('span', { id: 'stat-speed-unit' });
    const counts = {};
    ['stat-count-pending', 'stat-count-success', 'stat-count-failed', 'stat-count-active', 'stat-count-skipped']
        .forEach(id => { counts[id] = makeEl('span', { id }); });
    const byId = Object.assign({ 'current-card': currentCard, 'queue-list': queueList, 'stats-bar': statsBar,
        'stat-speed-value': speedValue, 'stat-speed-unit': speedUnit }, counts);
    const bySel = { '.dash-stats': dashStats };
    return {
        head: makeEl('head'), body: makeEl('body'),
        getElementById(id) { return byId[id] || null; },
        querySelector(sel) { return bySel[sel] || (byId[sel.replace(/^#/, '')] || null); },
        contains(el) { return !!el && !el.__detached; },
        createElement(t) { return new El(t); },
        addEventListener() {},
        _els: Object.assign({ dashStats, currentCard, queueList, statsBar, speedValue, speedUnit }, counts)
    };
}

/* ============================================================
   假 Vue helper（PixivVue）
============================================================ */
function makeVueRuntime() {
    return { reactive: o => o, nextTick: () => Promise.resolve(), createApp: () => ({ mount: () => ({}) }) };
}
function makePixivVue(opts, record) {
    opts = opts || {};
    const vue = makeVueRuntime();
    return {
        ensure: () => opts.ensureFail ? Promise.reject(new Error('runtime load failed')) : Promise.resolve(vue),
        mountOn: (el, comp) => {
            record.mounts.push({ el, comp });
            if (opts.mountFail) return Promise.resolve(null);
            return Promise.resolve({ app: { unmount() { record.unmounts.push(el); } }, vm: {}, el });
        }
    };
}

/* ============================================================
   载入 batch-queue-vue.js（隔离），可选注入 PixivVue / 共享格式化桩
============================================================ */
function loadVue(opts) {
    opts = opts || {};
    const document = makeBatchDocument();
    const record = { mounts: [], unmounts: [], rafQueued: 0 };
    const stateObj = opts.state || { queue: [], stats: { success: 0, failed: 0, active: 0, skipped: 0 }, currentItemId: null };
    const sandbox = {
        document, console: { warn() {}, log() {}, error() {} },
        Map, Set, Promise, setTimeout, clearTimeout,
        // 默认捕获 rAF 回调而不自动执行：测试经 queueVue.flush() 确定性 flush。
        requestAnimationFrame: cb => { record.rafQueued++; record.rafCb = cb; return 1; },
        // 共享格式化桩（batch-queue.js 全局函数；此处隔离注入）。
        buildQueueItemHtml: (q, o) => { record.rows = (record.rows || 0) + 1; return '<div class="queue-item" data-id="' + (q && q.id) + '">' + (q && q.id) + (o && o.queueId != null ? ':' + o.queueId : '') + '</div>'; },
        formatCurrentCardHtml: item => '<strong>cur</strong>' + (item ? item.id : 'none'),
        bt: (k, fb) => (fb != null ? fb : k),
        // refreshDownloadFromState 回调（模拟真实门面回灌：调对应 sync）。
        updateStats: () => { record.updateStats = (record.updateStats || 0) + 1; const api = sandbox.window.PixivBatch.queueVue; api.syncDownloadStats(computeStats(stateObj)); },
        renderQueue: () => { record.renderQueue = (record.renderQueue || 0) + 1; sandbox.window.PixivBatch.queueVue.syncDownloadList(); },
        setCurrent: item => { record.setCurrent = (record.setCurrent || 0) + 1; sandbox.window.PixivBatch.queueVue.syncDownloadCurrent(item); }
    };
    sandbox.window = sandbox;
    sandbox.window.PixivBatch = { state: { state: stateObj } };
    if (opts.pixivVue !== false) sandbox.PixivVue = makePixivVue(opts.vueOpts, record);
    vm.createContext(sandbox);
    vm.runInContext(VUE_SRC, sandbox);
    return { sandbox, document, record, state: stateObj, api: sandbox.window.PixivBatch.queueVue };
}

function computeStats(st) {
    const q = st.queue || [];
    return {
        pending: q.filter(x => ['idle', 'pending', 'paused'].includes(x.status)).length,
        success: q.filter(x => x.status === 'completed').length,
        failed: q.filter(x => x.status === 'failed').length,
        active: q.filter(x => x.status === 'downloading').length,
        skipped: q.filter(x => x.status === 'skipped').length
    };
}

/* ============================================================
   载入 batch-queue.js + batch-queue-vue.js（集成：真实门面 + 真实 queueVue）
============================================================ */
function loadIntegration(opts) {
    opts = opts || {};
    const document = makeBatchDocument();
    const record = { mounts: [], unmounts: [] };
    const stateObj = opts.state || { queue: [], stats: { success: 0, failed: 0, active: 0, skipped: 0 }, currentItemId: null };
    const sandbox = {
        document, console: { warn() {}, log() {}, error() {} },
        Map, Set, Promise, setTimeout, clearTimeout,
        requestAnimationFrame: cb => { record.rafCb = cb; return 1; },
        state: stateObj,
        // batch-queue.js 外部依赖桩
        esc: s => String(s), escHtml: s => String(s), bt: (k, fb, vars) => interpolate(fb != null ? fb : k, vars),
        uiLang: () => 'zh-CN', formatBytes: () => '', formatDurationMs: () => '', formatSeconds: () => '',
        statusColor: undefined, normalizeAuthorId: v => v, ensureWorkers() {}, updateAdminPackButton() { record.packBtn = (record.packBtn || 0) + 1; },
        storeSet() {}, storeGet() { return null; }, storeRemove() {},
        syncSearchResultsQueueState() {}, syncSeriesResultsQueueState() {}, syncUserResultsQueueState() {}, syncQuickQueueState() {},
        uiAlertKey() {}, setStatus() {}, downloadTxt() {},
        QUICK_FETCH_MODE: 'quick-fetch', SINGLE_IMPORT_MODE: 'single-import', SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel'
    };
    sandbox.window = sandbox;
    sandbox.window.PixivBatch = { queue: {}, state: { state: stateObj } };
    if (opts.pixivVue !== false) sandbox.PixivVue = makePixivVue(opts.vueOpts, record);
    vm.createContext(sandbox);
    vm.runInContext(QUEUE_SRC, sandbox);
    vm.runInContext(VUE_SRC, sandbox);
    // 用 spy 中和 buildQueueItemHtml / formatCurrentCardHtml（避免拉入全部内部格式化依赖），保留真实 formatSpeed / 门面分支。
    sandbox.buildQueueItemHtml = (q) => '<div class="queue-item">' + (q && q.id) + '</div>';
    sandbox.formatCurrentCardHtml = item => '<strong>cur</strong>' + (item ? item.id : 'none');
    return { sandbox, document, record, state: stateObj };
}

function interpolate(t, vars) {
    if (!vars) return String(t);
    return String(t).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (m, n) => Object.prototype.hasOwnProperty.call(vars, n) ? String(vars[n]) : m);
}

/* ============================================================
   计划任务 body 夹具
============================================================ */
function makeScheduleBody() {
    const list = makeEl('div', { class: 'schedule-queue-list' });
    const body = makeEl('div', { class: 'schedule-queue-body', children: [list] });
    return { body, list };
}

async function main() {
    /* ===== 1) Vue 成功路径：挂 3 个 app、激活、回灌 store ===== */
    {
        const st = { queue: [{ id: '1', status: 'completed' }, { id: '2', status: 'downloading' }, { id: '3', status: 'pending' }], currentItemId: '2', stats: {} };
        const { api, record } = loadVue({ state: st });
        ok('1: 初始未激活', api.isDownloadActive() === false);
        const active = await api.mountDownloadQueue();
        ok('1: Vue 成功 → 激活', active === true && api.isDownloadActive() === true);
        ok('1: 挂载 3 个 app（.dash-stats / #current-card / #queue-list）', record.mounts.length === 3);
        const targets = record.mounts.map(m => m.el.getAttribute('class') || m.el.getAttribute('id'));
        ok('1: 三挂载点正确', targets.indexOf('dash-stats') >= 0 && targets.indexOf('current-card') >= 0 && targets.indexOf('queue-list') >= 0);
        // refreshDownloadFromState 经门面回灌 + flush：store 已反映当前 state。
        const store = api.__test.downloadStore();
        ok('1: 挂载后回灌统计（成功 1 / 进行中 1 / 队列 1）', store.stats.success === 1 && store.stats.active === 1 && store.stats.pending === 1);
        ok('1: 挂载后回灌列表（3 项快照）', store.items.length === 3);
        ok('1: 挂载后回灌当前项（id=2）', store.current && store.current.id === '2');
        ok('1: 幂等：再次挂载不重复挂 app', (await api.mountDownloadQueue()) === true && record.mounts.length === 3);
    }

    /* ===== 2) Vue 缺失路径：不挂载、未激活（门面回退命令式由集成测试覆盖） ===== */
    {
        const { api, record } = loadVue({ pixivVue: false });
        const active = await api.mountDownloadQueue();
        ok('2: 无 PixivVue → 未激活', active === false && api.isDownloadActive() === false);
        ok('2: 无 PixivVue → 不挂任何 app', record.mounts.length === 0);
        ok('2: helperAvailable=false', api.helperAvailable() === false);
    }

    /* ===== 3) Vue 运行时加载失败 / 挂载失败：收敛为未激活 ===== */
    {
        const r1 = loadVue({ vueOpts: { ensureFail: true } });
        ok('3: ensure 失败 → 未激活', (await r1.api.mountDownloadQueue()) === false && r1.api.isDownloadActive() === false);

        const r2 = loadVue({ vueOpts: { mountFail: true } });
        const active2 = await r2.api.mountDownloadQueue();
        ok('3: mountOn 全失败 → 未激活', active2 === false && r2.api.isDownloadActive() === false);
        ok('3: mountOn 失败仍尝试了挂载（3 次）但无 app 入册', r2.record.mounts.length === 3);
    }

    /* ===== 4) 高频合批：N 次 sync 一帧内只一次扇出（同 key 去重） ===== */
    {
        const st = { queue: [], stats: {} };
        const { api, record } = loadVue({ state: st });
        await api.mountDownloadQueue();
        const store = api.__test.downloadStore();
        // 连续 5 次改 state 并 syncDownloadList：未 flush 前 store.items 不更新（仍是挂载时回灌的空快照）。
        for (let i = 0; i < 5; i++) { st.queue.push({ id: 'x' + i, status: 'pending' }); api.syncDownloadList(); }
        ok('4: flush 前未扇出（store.items 仍空）', store.items.length === 0);
        api.flush();
        ok('4: flush 后一次扇出到最新（5 项）', store.items.length === 5);
        // 统计同理：连续多次 syncDownloadStats，flush 后取最后一次。
        api.syncDownloadStats({ pending: 1, success: 0, failed: 0, active: 0, skipped: 0 });
        api.syncDownloadStats({ pending: 9, success: 1, failed: 2, active: 3, skipped: 4 });
        ok('4: flush 前统计未更新', store.stats.pending !== 9);
        api.flush();
        ok('4: flush 后统计取最后一次（pending=9, failed=2）', store.stats.pending === 9 && store.stats.failed === 2);
    }

    /* ===== 5) 速度 / 当前卡 sync 写 store（含归零与单位） ===== */
    {
        const { api } = loadVue({});
        await api.mountDownloadQueue();
        const store = api.__test.downloadStore();
        api.syncDownloadSpeed('0', 'B/s'); api.flush();
        ok('5: 速度归零写 store', store.speed.value === '0' && store.speed.unit === 'B/s');
        api.syncDownloadSpeed('1.50', 'MB/s'); api.flush();
        ok('5: 速度单位切换写 store', store.speed.value === '1.50' && store.speed.unit === 'MB/s');
        api.syncDownloadCurrent({ id: '7', title: 't' }); api.flush();
        ok('5: 当前项写 store（浅拷贝快照、新引用）', store.current && store.current.id === '7');
        const revisionBeforeIdle = store.currentRevision;
        api.syncDownloadCurrent(null); api.flush();
        ok('5: 当前项置空且同为 null 时仍推进刷新 revision（语言切换可重算空闲文案）',
            store.current === null && store.currentRevision === revisionBeforeIdle + 1);
    }

    /* ===== 6) 组件契约：template 镜像结构、行 / 当前卡共用格式化函数、标签经 bt ===== */
    {
        const { api } = loadVue({});
        await api.mountDownloadQueue();
        const list = api.__test.listComponent();
        ok('6: 列表模板含 q-item-host + :key + v-html', /q-item-host/.test(list.template) && /:key="q.id"/.test(list.template) && /v-html="rowHtml\(q\)"/.test(list.template));
        const lv = list.setup();
        ok('6: 行 HTML 走共享 buildQueueItemHtml', /class="queue-item"/.test(lv.rowHtml({ id: '9' })));
        const stats = api.__test.statsComponent();
        ok('6: 统计模板保留 5 计数 id + 速度 id', /id="stat-count-pending"/.test(stats.template) && /id="stat-speed-value"/.test(stats.template) && /id="stat-speed-unit"/.test(stats.template));
        ok('6: 统计标签经 bt（label）派生而非写死 data-i18n', /label\('dashboard.stat.queued'/.test(stats.template) && stats.template.indexOf('data-i18n') < 0);
        const cur = api.__test.currentComponent();
        ok('6: 当前卡走共享 formatCurrentCardHtml + display:contents v-html', /currentHtml\(\)/.test(cur.template) && /display:contents/.test(cur.template) && /<strong>cur<\/strong>/.test(cur.setup().currentHtml()));
        ok('6: 当前卡渲染读取 currentRevision（空闲态显式刷新也能触发 Vue 重渲染）',
            /currentRevision/.test(String(cur.setup().currentHtml)));
    }

    /* ===== 7) 集成：renderQueue Vue 激活时不整队列重建 #queue-list（连续 N 次仅合并） ===== */
    {
        const st = { queue: [{ id: 'a', status: 'pending' }], stats: {}, currentItemId: null };
        const { sandbox, document, record } = loadIntegration({ state: st });
        await sandbox.window.PixivBatch.queueVue.mountDownloadQueue();
        const queueList = document._els.queueList;
        const before = queueList.innerHTMLSets;
        ok('7: 激活后门面识别 Vue', sandbox.downloadQueueVueActive() === true);
        // 连续 10 次进度更新 → renderQueue()：Vue 激活路径只调 syncDownloadList（合并），绝不命令式重写 #queue-list。
        for (let i = 0; i < 10; i++) { st.queue[0].downloadedCount = i; sandbox.renderQueue(); }
        sandbox.window.PixivBatch.queueVue.flush();
        ok('7: 连续 10 次 renderQueue 未整队列重建 #queue-list（innerHTML 重写=0）', queueList.innerHTMLSets === before);
        const store = sandbox.window.PixivBatch.queueVue.__test.downloadStore();
        ok('7: store 反映最新队列', store.items.length === 1);
    }

    /* ===== 8) 集成：Vue 缺失时门面命令式回退（#queue-list 整块渲染、统计 / 速度写 DOM） ===== */
    {
        const st = { queue: [{ id: 'a', status: 'completed' }, { id: 'b', status: 'failed' }], stats: {}, currentItemId: null };
        const { sandbox, document } = loadIntegration({ state: st, pixivVue: false });
        ok('8: 无 Vue → 门面命令式', sandbox.downloadQueueVueActive() === false);
        sandbox.renderQueue();
        ok('8: 命令式整块渲染 #queue-list', document._els.queueList.innerHTMLSets >= 1);
        sandbox.updateStats();
        ok('8: 命令式写 5 张统计卡（成功=1 / 失败=1）', document._els['stat-count-success'].textContent === 1 && document._els['stat-count-failed'].textContent === 1);
        ok('8: 命令式写 sr-only #stats-bar', /成功/.test(document._els.statsBar.textContent));
        // 真实 formatSpeed：renderDownloadSpeed(0) → 归零；2MB/s → 单位切换。
        sandbox.renderDownloadSpeed(0);
        ok('8: 速度归零（命令式）', document._els.speedValue.textContent === '0' && document._els.speedUnit.textContent === 'B/s');
        sandbox.renderDownloadSpeed(2 * 1024 * 1024);
        ok('8: 速度单位切换（命令式，真实 formatSpeed → MB/s）', document._els.speedUnit.textContent === 'MB/s');
    }

    /* ===== 9) 集成：Vue 激活时速度门面写 store（真实 formatSpeed） ===== */
    {
        const { sandbox } = loadIntegration({ state: { queue: [], stats: {}, currentItemId: null } });
        await sandbox.window.PixivBatch.queueVue.mountDownloadQueue();
        const store = sandbox.window.PixivBatch.queueVue.__test.downloadStore();
        sandbox.renderDownloadSpeed(0); sandbox.window.PixivBatch.queueVue.flush();
        ok('9: 速度卡启动 / 停止归零（Vue 路径）', store.speed.value === '0' && store.speed.unit === 'B/s');
        sandbox.renderDownloadSpeed(5 * 1024 * 1024); sandbox.window.PixivBatch.queueVue.flush();
        ok('9: 速度采样 / 单位切换（Vue 路径，真实 formatSpeed → MB/s）', store.speed.unit === 'MB/s' && Number(store.speed.value) >= 4);
    }

    /* ===== 10) 计划任务详情岛：ensure → 异步挂载 → active → reactive 同步，连续 SSE 不整块重建 ===== */
    {
        const { api, document } = loadVue({});
        const { body } = makeScheduleBody();
        let readCount = 0;
        const ctx = {
            bodyEl: body,
            read: () => { readCount++; return { statusText: 'running', statsText: 'stats', current: { id: '5' }, items: [{ id: '5' }, { id: '6' }] }; }
        };
        // 首次 ensure：尚未挂载完成 → false（调用方命令式首屏）。
        ok('10: 首次 ensure 返回 false（异步挂载未完成）', api.ensureScheduleQueue(7, ctx) === false);
        ok('10: 首次 ensure 未激活', api.isScheduleActive(7) === false);
        await tick();
        ok('10: 异步挂载完成 → active', api.isScheduleActive(7) === true);
        ok('10: 挂载在 .schedule-queue-body 上', document.contains(body) && body.innerHTMLSets === 0);
        // 再 ensure（同 body）：active → true。
        ok('10: 稳态 ensure 返回 true', api.ensureScheduleQueue(7, ctx) === true);
        const entry = api.__test.scheduleEntry(7);
        const baseReads = readCount;
        // 连续 8 条 SSE 同步：合批后只 read 一次，且绝不整块重写 .schedule-queue-body。
        for (let i = 0; i < 8; i++) api.syncScheduleQueue(7);
        ok('10: flush 前未扇出', readCount === baseReads);
        api.flush();
        ok('10: 8 条 SSE 合并为一次 read（不整块重建）', readCount === baseReads + 1 && body.innerHTMLSets === 0);
        ok('10: store 反映 read 快照（2 行 + 当前项）', entry.store.items.length === 2 && entry.store.current.id === '5');
    }

    /* ===== 11) 计划详情组件契约：四段结构镜像 + 行 / 当前卡共用格式化 ===== */
    {
        const { api } = loadVue({});
        const { body } = makeScheduleBody();
        api.ensureScheduleQueue(8, { bodyEl: body, read: () => ({ statusText: 's', statsText: 't', current: null, items: [] }) });
        await tick();
        const entry = api.__test.scheduleEntry(8);
        const comp = api.__test.schedComponent(entry);
        ok('11: 四段结构镜像 renderScheduleQueueBody', /schedule-queue-status/.test(comp.template) && /schedule-queue-stats/.test(comp.template) && /schedule-queue-current/.test(comp.template) && /schedule-queue-list/.test(comp.template));
        ok('11: 列表行带 :key + queueId（局部刷新口径） + q-item-host', /q-item-host/.test(comp.template) && /:key="q.id"/.test(comp.template));
        const v = comp.setup();
        ok('11: 计划行走共享 buildQueueItemHtml（removable:false + queueId）', /:5</.test(v.rowHtml({ id: '5' })));
        ok('11: 计划当前卡走共享 formatCurrentCardHtml', typeof v.currentHtml === 'function' && /<strong>cur<\/strong>/.test(v.currentHtml()));
    }

    /* ===== 12) 卡片 body 被替换（detached）→ 探测失效 → 重挂；折叠 / 下线卸载；滚动保留 ===== */
    {
        const { api, document } = loadVue({});
        const first = makeScheduleBody();
        first.list.scrollTop = 120;
        api.ensureScheduleQueue(9, { bodyEl: first.body, read: () => ({ statusText: 's', statsText: 't', current: null, items: [{ id: '1' }] }) });
        await tick();
        ok('12: 初次挂载激活', api.isScheduleActive(9) === true);
        // 模拟卡片 diff：旧 body 脱离文档、换新 body。
        first.body.__detached = true;
        ok('12: 旧 body 脱离 → isScheduleActive 失效', api.isScheduleActive(9) === false);
        const second = makeScheduleBody();
        second.list.scrollTop = 0;
        ok('12: 新 body ensure → 这一拍未激活（重挂中）', api.ensureScheduleQueue(9, { bodyEl: second.body, read: () => ({ statusText: 's', statsText: 't', current: null, items: [{ id: '1' }] }) }) === false);
        await tick();
        ok('12: 重挂到新 body 后激活', api.isScheduleActive(9) === true);
        const entry = api.__test.scheduleEntry(9);
        ok('12: 重挂宿主切到新 body', entry.bodyEl === second.body);
        // 卸载：折叠 / 下线。
        api.unmountScheduleQueue(9);
        ok('12: 卸载后不再激活、entry 清除', api.isScheduleActive(9) === false && api.__test.scheduleEntry(9) === undefined);
    }

    /* ===== 13) 计划岛：Vue 缺失 / 挂载失败 → ensure 返回 false（调用方命令式回退） ===== */
    {
        const noVue = loadVue({ pixivVue: false });
        ok('13: 无 PixivVue → ensure=false', noVue.api.ensureScheduleQueue(1, { bodyEl: makeScheduleBody().body, read: () => ({}) }) === false);
        const failVue = loadVue({ vueOpts: { ensureFail: true } });
        const { body } = makeScheduleBody();
        const ctx = { bodyEl: body, read: () => ({ statusText: '', statsText: '', current: null, items: [] }) };
        failVue.api.ensureScheduleQueue(2, ctx);
        await tick();
        ok('13: 运行时加载失败 → 永不激活', failVue.api.isScheduleActive(2) === false);
        ok('13: 失败后再 ensure 仍 false（永久命令式回退）', failVue.api.ensureScheduleQueue(2, ctx) === false);
    }

    console.log(`\nbatch-queue-vue.test.js: ${passed} assertions passed ✓`);
}

main().catch(err => { console.error('TEST FAILED:', err && err.stack ? err.stack : err); process.exit(1); });
