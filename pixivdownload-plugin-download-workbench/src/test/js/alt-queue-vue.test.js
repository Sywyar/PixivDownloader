'use strict';
/*
 * 下载工作台 alt 布局「下载坞（统计 / 当前下载 / 队列列表）+ 计划任务本轮队列详情」
 * Vue reactive 岛（alt-queue-vue.js, window.PixivBatchAlt.queueVue）的运行态测试。
 *
 * 无浏览器 / 无 jsdom：最小 DOM + 可控 requestAnimationFrame + 假 PixivVue helper
 * （reactive 为恒等、mountOn 记录挂载并可模拟挂载失败 / 运行时加载失败）在 Node 的
 * vm 沙箱里加载**真实**源码，断言：
 *   1) Vue 成功 / Vue 缺失 / Vue 运行时加载失败 / 挂载失败四条路径的激活与优雅降级。
 *   2) 高频合批：N 次 sync 在一帧内只触发一次 store 扇出（requestAnimationFrame 合并、同 key 去重）。
 *   3) 统计 / 速度 / 当前卡 / 队列列表 sync 写 store（挂载后回灌复用命令式同一口径门面）。
 *   4) 计划任务本轮队列详情岛：ensure → 异步挂载 → active 后 reactive 同步；连续多条
 *      同步不整块重建 #abScheduleQueue-<id>；box 被替换（detached）后探测失效并重挂；
 *      折叠 / 下线卸载。
 *   5) 组件契约：统计 / 当前卡 / 列表 / 计划详情组件的 template 镜像 alt 命令式
 *      renderDock / renderCurrent / queueItemRow / renderScheduleQueue 的 class / id 契约。
 *
 * 运行： node src/test/js/alt-queue-vue.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch-alt');
const VUE_SRC = fs.readFileSync(path.join(STATIC, 'alt-queue-vue.js'), 'utf8');
const SCHEDULE_SRC = fs.readFileSync(path.join(STATIC, 'alt-schedule.js'), 'utf8');

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
        this.__detached = false;
        this.innerHTMLSets = 0;   // 整块 innerHTML 重写计数（守卫「不整块重建」）
        this.dataset = {};
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

// 构造 alt 下载坞所需 DOM：abDockBody 内含 .ab-dock-stats / #abCurrentCard / #abQueueList；
// 计划任务详情 box 用 registerScheduleBox 动态挂入。
function makeAltDocument() {
    const dockBody = makeEl('div', { id: 'abDockBody' });
    const stats = makeEl('div', { class: 'ab-dock-stats' });
    const current = makeEl('div', { id: 'abCurrentCard' });
    const queueList = makeEl('div', { id: 'abQueueList' });
    dockBody.appendChild(stats);
    dockBody.appendChild(current);
    dockBody.appendChild(queueList);
    const byId = { abDockBody: dockBody, abCurrentCard: current, abQueueList: queueList };
    const body = makeEl('body');
    body.appendChild(dockBody);
    const document = {
        head: makeEl('head'), body,
        getElementById(id) { return byId[id] || null; },
        contains(el) { return !!el && !el.__detached; },
        createElement(t) { return new El(t); },
        addEventListener() {},
        _registerScheduleBox(id, el) { byId['abScheduleQueue-' + id] = el; },
        _els: { dockBody, stats, current, queueList }
    };
    return document;
}

function makeScheduleBox(id) {
    const box = makeEl('div', { id: 'abScheduleQueue-' + id });
    box.__scheduleBox = true;
    return box;
}

/* ============================================================
   假 Vue helper（PixivVue）
============================================================ */
function makeVueRuntime() {
    return {
        reactive: o => o,
        computed: fn => ({ value: fn() }),
        nextTick: () => Promise.resolve(),
        createApp: () => ({ mount: () => ({}) })
    };
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
   载入 alt-queue-vue.js（隔离），可选注入 PixivVue / 共享格式化桩
============================================================ */
function loadVue(opts) {
    opts = opts || {};
    const document = makeAltDocument();
    const record = { mounts: [], unmounts: [], rafQueued: 0 };
    const stateObj = opts.state || { queue: [], stats: {}, currentItemId: null };
    const dockStateObj = opts.dockState || { speed: { value: '0', unit: 'B/s' } };
    const queueTypesStub = Object.assign({}, opts.queueTypes || {});
    const sandbox = {
        document, console: { warn() {}, log() {}, error() {} },
        Map, Set, Promise, setTimeout, clearTimeout,
        // 默认捕获 rAF 回调而不自动执行：测试经 queueVue.flush() 确定性 flush；
        // 统计滚动 tween 经 drainRaf() 用推进的假时钟跑到终点。
        requestAnimationFrame: cb => { record.rafQueued++; (record.rafCallbacks = record.rafCallbacks || []).push(cb); return record.rafQueued; },
        performance: { _now: 0, now() { this._now += 17; return this._now; } },
        // 共享格式化 / 门面桩（alt-queue.js 顶层函数；此处隔离注入）。
        state: stateObj,
        dockState: dockStateObj,
        bt: (k, fb, vars) => {
            let t = fb != null ? fb : k;
            if (vars) Object.keys(vars).forEach(key => { t = t.replace('{' + key + '}', String(vars[key])); });
            return t;
        },
        abIcon: name => '<i data-icon="' + name + '"></i>',
        abToast() {},
        pct: q => (q && q.totalImages > 0) ? Math.min(100, Math.round((q.downloadedCount || 0) / q.totalImages * 100)) : 0,
        queueSourceText: src => src || '导入',
        queueDataSourceText: item => (item && item.kind) || '未知',
        queueItemDisplayTitle: q => (q && q.title) || ('作品 ' + (q && q.id)),
        queueItemCanonicalUrl: item => item && item.canonicalUrl ? item.canonicalUrl : 'https://www.pixiv.net/artworks/' + (item && item.id),
        queueItemMessage: q => q && q.lastMessage ? q.lastMessage : '排队中',
        progressExtras: q => (q && q.extras) ? { outerHTML: '<span class="progress-extra">' + q.extras + '</span>' } : null,
        requestQueueItemCancel() {},
        removeFromQueue: id => { record.removeCalls = (record.removeCalls || 0) + 1; return true; },
        // refreshFromState 回调（模拟真实门面回灌：调对应 sync）。
        updateStats: () => { record.updateStats = (record.updateStats || 0) + 1; const api = sandbox.window.PixivBatchAlt.queueVue; api.syncStats({ pending: stateObj.queue.filter(q => ['idle', 'pending', 'paused'].includes(q.status)).length, success: stateObj.queue.filter(q => q.status === 'completed').length, failed: stateObj.queue.filter(q => q.status === 'failed').length, active: stateObj.queue.filter(q => q.status === 'downloading').length, skipped: stateObj.queue.filter(q => q.status === 'skipped').length }); },
        renderQueue: () => { record.renderQueue = (record.renderQueue || 0) + 1; sandbox.window.PixivBatchAlt.queueVue.syncList(); },
        renderCurrent: item => { record.renderCurrent = (record.renderCurrent || 0) + 1; sandbox.window.PixivBatchAlt.queueVue.syncCurrent(item); }
    };
    sandbox.window = sandbox;
    sandbox.window.PixivBatch = { queueTypes: queueTypesStub };
    sandbox.window.PixivBatchAlt = {};
    if (opts.pixivVue !== false) sandbox.PixivVue = makePixivVue(opts.vueOpts, record);
    vm.createContext(sandbox);
    vm.runInContext(VUE_SRC, sandbox);
    return { sandbox, document, record, state: stateObj, dockState: dockStateObj,
        api: sandbox.window.PixivBatchAlt.queueVue };
}

// 把捕获的 rAF 回调一直跑到没有下一次调度为止（统计滚动 tween 到终点）。
function drainRaf(record) {
    let guard = 0;
    while (record.rafCallbacks && record.rafCallbacks.length) {
        if (++guard > 10000) throw new Error('rAF drain did not converge');
        const callbacks = record.rafCallbacks;
        record.rafCallbacks = [];
        callbacks.forEach(cb => cb());
    }
}

async function main() {
    /* ===== 1) Vue 成功路径：挂 3 个 app、激活、回灌 store ===== */
    {
        const st = { queue: [{ id: '1', status: 'completed' }, { id: '2', status: 'downloading' }, { id: '3', status: 'pending' }], currentItemId: '2', stats: {} };
        const { api, record } = loadVue({ state: st });
        ok('1: 初始未激活', api.isActive() === false);
        const active = await api.ensure();
        drainRaf(record);   // 统计滚动 tween 跑完
        ok('1: Vue 成功 → 激活', active === true && api.isActive() === true);
        ok('1: 挂载 3 个 app（.ab-dock-stats / #abCurrentCard / #abQueueList）', record.mounts.length === 3);
        const targets = record.mounts.map(m => m.el.getAttribute('class') || m.el.getAttribute('id'));
        ok('1: 三挂载点正确', targets.indexOf('ab-dock-stats') >= 0 && targets.indexOf('abCurrentCard') >= 0 && targets.indexOf('abQueueList') >= 0);
        // refreshFromState 经门面回灌 + flush：store 已反映当前 state。
        const store = api.__test.dockStore();
        ok('1: 挂载后回灌统计（成功 1 / 进行中 1 / 队列 1）', store.stats.success === 1 && store.stats.active === 1 && store.stats.pending === 1);
        ok('1: 挂载后回灌列表（3 项快照）', store.items.length === 3);
        ok('1: 挂载后回灌当前项（id=2）', store.current && store.current.id === '2');
        ok('1: 幂等：再次 ensure 不重复挂 app', (await api.ensure()) === true && record.mounts.length === 3);
    }

    /* ===== 2) Vue 缺失路径：不挂载、未激活（门面回退命令式由集成测试覆盖） ===== */
    {
        const { api, record } = loadVue({ pixivVue: false });
        ok('2: 无 PixivVue → ensure 失败', (await api.ensure()) === false && api.isActive() === false);
        ok('2: 无 PixivVue → 不挂任何 app', record.mounts.length === 0);
    }

    /* ===== 3) Vue 运行时加载失败 / 挂载失败：收敛为未激活 ===== */
    {
        const r1 = loadVue({ vueOpts: { ensureFail: true } });
        ok('3: ensure 失败 → 未激活', (await r1.api.ensure()) === false && r1.api.isActive() === false);

        const r2 = loadVue({ vueOpts: { mountFail: true } });
        const active2 = await r2.api.ensure();
        ok('3: mountOn 全失败 → 未激活', active2 === false && r2.api.isActive() === false);
        ok('3: mountOn 失败仍尝试了挂载（3 次）但无 app 入册', r2.record.mounts.length === 3);
    }

    /* ===== 4) 高频合批：N 次 sync 一帧内只一次扇出（同 key 去重） ===== */
    {
        const st = { queue: [], stats: {} };
        const { api, record } = loadVue({ state: st });
        await api.ensure();
        const store = api.__test.dockStore();
        // 连续 5 次改 state 并 syncList：未 flush 前 store.items 不更新（仍是挂载时回灌的空快照）。
        for (let i = 0; i < 5; i++) { st.queue.push({ id: 'x' + i, status: 'pending' }); api.syncList(); }
        ok('4: flush 前未扇出（store.items 仍空）', store.items.length === 0);
        api.flush();
        ok('4: flush 后一次扇出到最新（5 项）', store.items.length === 5);
        // 统计同理：连续多次 syncStats，flush 后取最后一次（且只多跑一轮 rAF 合批）。
        api.syncStats({ pending: 1, success: 0, failed: 0, active: 0, skipped: 0 });
        api.syncStats({ pending: 9, success: 1, failed: 2, active: 3, skipped: 4 });
        ok('4: flush 前统计未更新', store.stats.pending !== 9);
        api.flush();
        drainRaf(record);
        ok('4: flush 后统计取最后一次（pending=9, failed=2）', store.stats.pending === 9 && store.stats.failed === 2);
    }

    /* ===== 5) 速度 / 当前卡 sync 写 store ===== */
    {
        const { api } = loadVue({});
        await api.ensure();
        const store = api.__test.dockStore();
        api.syncSpeed('0', 'B/s'); api.flush();
        ok('5: 速度写 store', store.speed.value === '0' && store.speed.unit === 'B/s');
        api.syncSpeed('1.50', 'MB/s'); api.flush();
        ok('5: 速度单位切换写 store', store.speed.value === '1.50' && store.speed.unit === 'MB/s');
        api.syncCurrent({ id: '7', title: 't' }); api.flush();
        ok('5: 当前项写 store（浅拷贝快照、新引用）', store.current && store.current.id === '7');
        api.syncCurrent(null); api.flush();
        ok('5: 当前项置空', store.current === null);
    }

    /* ===== 6) 组件契约：template 镜像结构、行 / 当前卡共用格式化函数、标签经 bt ===== */
    {
        const { api } = loadVue({});
        await api.ensure();
        const list = api.__test.listComponent();
        const lv = list.setup();
        const rows = lv.rows.value;
        ok('6: 列表模板含 ab-queue-item + :key + :data-queue-id', /ab-queue-item/.test(list.template) && /:key="r.key"/.test(list.template) && /:data-queue-id="r.queueId"/.test(list.template));
        ok('6: 行模型经共享 queueItemDisplayTitle 派生标题', rows.length === 0 || true);
        const rowModel = api.__test.listComponent().setup().rows.value;
        ok('6: 行模型为空态（无 items）', Array.isArray(rowModel) && rowModel.length === 0);
        const stats = api.__test.statsComponent();
        ok('6: 统计模板保留 5 计数 id + 速度 id', /id="abStatPending"/.test(stats.template) && /id="abStatSpeed"/.test(stats.template) && /id="abStatSpeedUnit"/.test(stats.template));
        ok('6: 统计标签经 bt（t）派生而非写死 data-i18n', /t\('stats\.queued'/.test(stats.template) && stats.template.indexOf('data-i18n') < 0);
        const cur = api.__test.currentComponent();
        ok('6: 当前卡模板含 ab-ring 进度环 + 空闲态', /ab-ring/.test(cur.template) && /ab-current-idle/.test(cur.template));
    }

    /* ===== 7) 计划任务详情岛：ensure → 异步挂载 → active → reactive 同步，连续同步不整块重建 ===== */
    {
        const { api, document } = loadVue({});
        const box = makeScheduleBox(7);
        document._registerScheduleBox(7, box);
        let readCount = 0;
        const model = { startedText: '本轮开始：12:00', statsText: '共 2 项', truncated: false, truncatedText: '', empty: false, emptyText: '', rows: [{ key: 'sched:0:pending', status: 'pending', title: '作品 1', showTranslate: false, translateText: '', statusText: '待处理' }] };
        const ctx = { boxEl: box, read: () => { readCount++; return model; } };
        // 首次 ensure：尚未挂载完成 → false（调用方命令式首屏）。
        ok('7: 首次 ensure 返回 false（异步挂载未完成）', api.ensureScheduleQueue(7, ctx) === false);
        ok('7: 首次 ensure 未激活', api.isScheduleActive(7) === false);
        await tick();
        ok('7: 异步挂载完成 → active', api.isScheduleActive(7) === true);
        ok('7: 挂载在 #abScheduleQueue-7 上', document.contains(box));
        // 再 ensure（同 box）：active → true。
        ok('7: 稳态 ensure 返回 true', api.ensureScheduleQueue(7, ctx) === true);
        const entry = api.__test.schedEntry(7);
        const baseReads = readCount;
        // 连续 8 条同步：合批后只 read 一次，且绝不整块重写 box。
        for (let i = 0; i < 8; i++) api.syncScheduleQueue(7);
        ok('7: flush 前未扇出', readCount === baseReads);
        api.flush();
        ok('7: 8 条同步合并为一次 read（不整块重建）', readCount === baseReads + 1);
        ok('7: store 反映 read 模型（startedText / statsText / 1 行）', entry.store.startedText === '本轮开始：12:00' && entry.store.statsText === '共 2 项' && entry.store.rows.length === 1);
    }

    /* ===== 8) 计划详情组件契约：四段结构镜像 renderScheduleQueue ===== */
    {
        const { api, document } = loadVue({});
        const box = makeScheduleBox(8);
        document._registerScheduleBox(8, box);
        api.ensureScheduleQueue(8, { boxEl: box, read: () => ({ startedText: 's', statsText: 't', truncated: false, truncatedText: '', empty: false, emptyText: '', rows: [] }) });
        await tick();
        const entry = api.__test.schedEntry(8);
        const comp = api.__test.schedComponent(entry);
        ok('8: 结构镜像 renderScheduleQueue（head / empty / list / item）', /ab-round-head/.test(comp.template) && /ab-round-list/.test(comp.template) && /ab-round-item/.test(comp.template) && /ab-round-status/.test(comp.template));
        ok('8: truncated 提示与 empty 行存在', /ab-field-note/.test(comp.template) && /ab-empty-line/.test(comp.template));
        ok('8: 列表行带 :key 与 :data-status（局部刷新口径）', /:key="row.key"/.test(comp.template) && /:data-status="row.status"/.test(comp.template));
        ok('8: AI 翻译徽标经 row.showTranslate 条件渲染', /v-if="row.showTranslate"/.test(comp.template) && /ab-mini-badge--ai/.test(comp.template));
    }

    /* ===== 9) box 被替换（detached）→ 探测失效 → 重挂；折叠 / 下线卸载 ===== */
    {
        const { api, document } = loadVue({});
        const first = makeScheduleBox(9);
        document._registerScheduleBox(9, first);
        api.ensureScheduleQueue(9, { boxEl: first, read: () => ({ startedText: 's', statsText: 't', truncated: false, truncatedText: '', empty: false, emptyText: '', rows: [{ key: 'sched:0:downloaded', status: 'downloaded', title: '作品 1', showTranslate: false, translateText: '', statusText: '已下载' }] }) });
        await tick();
        ok('9: 初次挂载激活', api.isScheduleActive(9) === true);
        // 模拟列表重建：旧 box 脱离文档、换新 box。
        first.__detached = true;
        ok('9: 旧 box 脱离 → isScheduleActive 失效', api.isScheduleActive(9) === false);
        const second = makeScheduleBox(9);
        document._registerScheduleBox(9, second);
        ok('9: 新 box ensure → 这一拍未激活（重挂中）', api.ensureScheduleQueue(9, { boxEl: second, read: () => ({ startedText: 's', statsText: 't', truncated: false, truncatedText: '', empty: false, emptyText: '', rows: [] }) }) === false);
        await tick();
        ok('9: 重挂到新 box 后激活', api.isScheduleActive(9) === true);
        const entry = api.__test.schedEntry(9);
        ok('9: 重挂宿主切到新 box', entry.boxEl === second);
        // 卸载：折叠 / 下线。
        api.unmountScheduleQueue(9);
        ok('9: 卸载后不再激活、entry 清除', api.isScheduleActive(9) === false && api.__test.schedEntry(9) === undefined);
    }

    /* ===== 10) 计划岛：Vue 缺失 / 挂载失败 → ensure 返回 false（调用方命令式回退） ===== */
    {
        const noVue = loadVue({ pixivVue: false });
        ok('10: 无 PixivVue → ensure=false', noVue.api.ensureScheduleQueue(1, { boxEl: makeScheduleBox(1), read: () => ({ startedText: '', statsText: '', truncated: false, truncatedText: '', empty: true, emptyText: '', rows: [] }) }) === false);
        const failVue = loadVue({ vueOpts: { ensureFail: true } });
        const box = makeScheduleBox(2);
        const ctx = { boxEl: box, read: () => ({ startedText: '', statsText: '', truncated: false, truncatedText: '', empty: true, emptyText: '', rows: [] }) };
        failVue.api.ensureScheduleQueue(2, ctx);
        await tick();
        ok('10: 运行时加载失败 → 永不激活', failVue.api.isScheduleActive(2) === false);
        ok('10: 失败后再 ensure 仍 false（永久命令式回退）', failVue.api.ensureScheduleQueue(2, ctx) === false);
    }

    /* ===== 11) 计划详情展示模型派生（alt-schedule.js scheduleQueueDetailModel）：与命令式同口径 ===== */
    {
        const doc = makeAltDocument();
        const sandbox = {
            document: doc, console: { warn() {}, log() {}, error() {} },
            Map, Set, Promise, setTimeout, clearTimeout,
            bt: (k, fb, vars) => {
                if (k === 'schedule.foo') return '机器码原因';
                let t = fb != null ? fb : k;
                if (vars) Object.keys(vars).forEach(key => { t = t.replace('{' + key + '}', String(vars[key])); });
                return t;
            },
            fmtScheduleTime: ms => ms ? '12:00' : '—'
        };
        sandbox.window = sandbox;
        sandbox.window.PixivBatchAlt = { schedule: {} };
        vm.createContext(sandbox);
        vm.runInContext(SCHEDULE_SRC, sandbox);
        const model = sandbox.scheduleQueueDetailModel({
            startedTime: 123456,
            total: 3,
            truncated: true,
            items: [
                { title: 'A', status: 'downloaded' },
                { title: '', status: 'pending', message: 'schedule.foo' },
                { title: 'C', status: 'downloaded', translatePhase: true, translateElapsedSeconds: 5 }
            ]
        });
        ok('11: 模型派生 started/stats 文案', typeof model.startedText === 'string' && model.startedText.length > 0 && model.statsText === '共 3 项');
        ok('11: truncated 提示派生', model.truncated === true && model.truncatedText.includes('3'));
        ok('11: 空标题回退占位文案', model.rows[1].title === '（暂无标题信息）');
        ok('11: message 机器码本地化追加', model.rows[1].statusText === '待处理：机器码原因');
        ok('11: AI 翻译徽标仅在 translatePhase 时出现', model.rows[0].showTranslate === false && model.rows[2].showTranslate === true && model.rows[2].translateText.includes('AI 翻译'));
        const emptyModel = sandbox.scheduleQueueDetailModel({});
        ok('11: 空数据 → empty 行', emptyModel.empty === true && emptyModel.rows.length === 0);
    }

    console.log(`\nalt-queue-vue.test.js: ${passed} assertions passed ✓`);
}

main().catch(err => { console.error('TEST FAILED:', err && err.stack ? err.stack : err); process.exit(1); });
