'use strict';
// ============================================================
//  PixivBatch.queueVue —— 下载工作台「队列 / 统计 / 速度」与计划任务「本轮队列详情」的 Vue reactive 岛。
//
//  目标：把下载页高频刷新热点（普通下载队列、5 张统计卡 + 总下载速度、计划任务展开后的本轮队列详情）从
//  「每个进度事件整块 innerHTML 重建」改为 reactive 数据驱动——更新只改 reactive store，Vue 据 :key 与
//  v-html 仅 patch 发生变化的单行 / 单字段，避免整队列 / 整块详情重建造成的主线程卡顿（INP 飙高）。
//
//  共享口径（不分叉、不复制第二套 HTML 语义）：行 HTML 仍由 batch-queue.js 的 buildQueueItemHtml 生成、
//  当前下载卡仍由 formatCurrentCardHtml 生成、统计文案 / 速度文案仍由 formatStatsText / formatSpeed 生成；
//  本模块只负责把这些共享格式化函数挂到 reactive 模板里（v-html / 插值），命令式回退路径与 Vue 路径共用同一套。
//
//  渐进式、加性、优雅降级：window.PixivVue 缺失 / Vue 运行时加载失败 / 挂载抛错时，本模块的 ensure/mount 一律
//  收敛为「未激活」，调用方（batch-queue.js / schedule.js 的兼容门面）继续走现有布局的命令式渲染——不白屏、
//  不丢按钮。Vue 经共享 helper 按需懒加载（运行时为单一来源，路径只由 helper 解析、本模块不硬编码）。
//
//  挂载点：普通队列 = 一个共享 reactive store + 三个挂载点（.dash-stats 统计 + 速度卡 / #current-card 当前卡 /
//  #queue-list 列表）；计划任务 = 每个展开任务一个 store + 一个挂载点（.schedule-queue-body）。卡片 diff 替换卡片
//  时本模块按「挂载宿主是否仍在文档、是否仍是同一 body」探测失效并按需重挂（不改卡片列表整体 diff 策略）。
// ============================================================
(function (global) {
    var doc = global.document;

    function warn(msg, e) {
        try { (global.console || console).warn('[queueVue] ' + msg, e); } catch (ignored) { /* 无 console：忽略 */ }
    }

    /* ============================================================
       高频更新合批：把同一帧内的多次 store 同步合并为一次（requestAnimationFrame，缺失时回退 setTimeout）。
       同 key 去重——一帧内多次调度同一 job，只保留最后一次。读侧（Vue 渲染）一帧只触发一次扇出。
    ============================================================ */
    var rafFn = (typeof global.requestAnimationFrame === 'function')
        ? function (cb) { return global.requestAnimationFrame(cb); }
        : function (cb) { return global.setTimeout(cb, 16); };
    var pendingJobs = new global.Map();   // key -> fn（后者覆盖前者）
    var rafScheduled = false;

    function runJobs() {
        rafScheduled = false;
        var fns = [];
        pendingJobs.forEach(function (fn) { fns.push(fn); });
        pendingJobs.clear();
        for (var i = 0; i < fns.length; i++) {
            try { fns[i](); } catch (e) { warn('合批任务执行失败', e); }
        }
    }

    function schedule(key, fn) {
        pendingJobs.set(key, fn);
        if (!rafScheduled) { rafScheduled = true; rafFn(runJobs); }
    }

    // 立即执行待处理的合批任务（挂载后即时填充、测试中确定性 flush 用）。
    function flushNow() {
        if (pendingJobs.size) { runJobs(); }
    }

    /* ============================================================
       Vue 共享 helper 可用性与运行时
    ============================================================ */
    var Vue = null;   // ensure() 解析后的 window.Vue（懒加载缓存）

    function helper() { return global.PixivVue; }

    function helperAvailable() {
        var h = helper();
        return !!(h && typeof h.ensure === 'function' && typeof h.mountOn === 'function');
    }

    // 共享格式化 / 渲染函数为 batch-queue.js / batch-core.js 的顶层函数声明，挂在全局对象上（window.X）；
    // 运行期按名解析，缺失即返回 null（测试隔离加载本模块时由桩注入到沙箱全局）。
    function g(name) {
        return (typeof global[name] !== 'undefined') ? global[name] : null;
    }
    function callG(name, args, fallback) {
        var fn = g(name);
        if (typeof fn === 'function') { try { return fn.apply(null, args || []); } catch (e) { warn(name + ' 调用失败', e); } }
        return fallback;
    }
    function rowHtmlOf(q, opts) { return callG('buildQueueItemHtml', [q, opts], ''); }
    function currentCardHtmlOf(item) { return callG('formatCurrentCardHtml', [item], ''); }
    function tt(key, fallback, vars) {
        var fn = g('bt');
        return (typeof fn === 'function') ? fn(key, fallback, vars) : (fallback != null ? fallback : key);
    }
    // state 是 batch-state.js 的顶层 let（不挂 window），经其门面 PixivBatch.state.state 读取，避免依赖词法全局解析。
    function batchState() {
        var pb = global.PixivBatch;
        var s = (pb && pb.state) ? pb.state.state : null;
        return (s && typeof s === 'object') ? s : null;
    }

    /* ============================================================
       普通下载队列岛：一个共享 reactive store + 三个挂载点。
       - .dash-stats   ：5 张统计卡（队列/成功/失败/进行中/跳过）+ 总下载速度卡（同一 store.speed）。
       - #current-card ：当前下载卡（v-html formatCurrentCardHtml）。
       - #queue-list   ：下载队列列表（v-for + :key + v-html buildQueueItemHtml）。
    ============================================================ */
    var dlStore = null;     // Vue.reactive({ stats, speed, current, items })
    var dlApps = [];        // [{ el, app }]
    var dlActive = false;
    var dlMounting = false;

    function buildDlStore() {
        return Vue.reactive({
            stats: { pending: 0, success: 0, failed: 0, active: 0, skipped: 0 },
            speed: { value: '0', unit: 'B/s' },
            current: null,   // 当前下载项（浅拷贝快照，便于 reactive 触发）或 null
            currentRevision: 0,
            items: []        // state.queue 的浅快照（行对象引用，渲染时由 buildQueueItemHtml 读最新字段）
        });
    }

    // 统计 + 速度卡组件：模板逐字镜像 dash-strip 里的 6 张卡片结构（保留 id / class），数字读 reactive store，
    // 标签经 bt 派生（不写 data-i18n，避免与 pageI18n.apply 抢渲染；语言切换由门面重调 updateStats 触发重渲染）。
    function statsComponent() {
        return {
            setup: function () {
                return {
                    store: dlStore,
                    label: function (key, fb) { return tt(key, fb); }
                };
            },
            template:
                '<div class="stat-card stat-queued"><span class="stat-num" id="stat-count-pending">{{ store.stats.pending }}</span><span class="stat-label">{{ label(\'dashboard.stat.queued\', \'队列\') }}</span></div>'
                + '<div class="stat-card stat-success"><span class="stat-num" id="stat-count-success">{{ store.stats.success }}</span><span class="stat-label">{{ label(\'dashboard.stat.success\', \'成功\') }}</span></div>'
                + '<div class="stat-card stat-failed"><span class="stat-num" id="stat-count-failed">{{ store.stats.failed }}</span><span class="stat-label">{{ label(\'dashboard.stat.failed\', \'失败\') }}</span></div>'
                + '<div class="stat-card stat-active"><span class="stat-num" id="stat-count-active">{{ store.stats.active }}</span><span class="stat-label">{{ label(\'dashboard.stat.active\', \'进行中\') }}</span></div>'
                + '<div class="stat-card stat-skipped"><span class="stat-num" id="stat-count-skipped">{{ store.stats.skipped }}</span><span class="stat-label">{{ label(\'dashboard.stat.skipped\', \'跳过\') }}</span></div>'
                + '<div class="stat-card stat-speed"><span class="stat-num"><span id="stat-speed-value">{{ store.speed.value }}</span><span class="stat-speed-unit" id="stat-speed-unit">{{ store.speed.unit }}</span></span><span class="stat-label">{{ label(\'dashboard.stat.speed\', \'下载速度\') }}</span></div>'
        };
    }

    // 当前下载卡组件：display:contents 透明宿主 + v-html，使 formatCurrentCardHtml 的输出作为 #current-card 的
    // 真实内容（与命令式 el.innerHTML = formatCurrentCardHtml(item) 视觉一致）。
    function currentComponent() {
        return {
            setup: function () {
                return {
                    store: dlStore,
                    currentHtml: function () {
                        void dlStore.currentRevision;
                        return currentCardHtmlOf(dlStore.current);
                    }
                };
            },
            template: '<span style="display:contents" v-html="currentHtml()"></span>'
        };
    }

    // 队列列表组件：每行一个 display:contents 宿主 + v-html buildQueueItemHtml；:key=q.id 复用宿主，
    // 单项进度 / 状态 / message 变化只让该行的 v-html 字符串变化、Vue 仅 patch 该行（不整队列重建）。
    function listComponent() {
        return {
            setup: function () {
                return {
                    store: dlStore,
                    rowHtml: function (q) { return rowHtmlOf(q, { removable: true }); },
                    emptyText: function () { return tt('status.queue-empty', '队列为空'); }
                };
            },
            template:
                '<div v-if="!store.items.length" class="queue-empty">{{ emptyText() }}</div>'
                + '<template v-else><div class="q-item-host" v-for="q in store.items" :key="q.id" v-html="rowHtml(q)"></div></template>'
        };
    }

    function mountOne(el, comp) {
        return helper().mountOn(el, comp).then(function (h) {
            if (h && h.app) { dlApps.push({ el: el, app: h.app }); }
            return h;
        });
    }

    // 幂等挂载下载队列岛。返回 Promise<boolean active>。Vue 不可用 / 加载失败 / 全部挂载失败 → false（门面命令式兜底）。
    function mountDownloadQueue() {
        if (dlActive) { return global.Promise.resolve(true); }
        if (dlMounting) { return global.Promise.resolve(false); }
        if (!helperAvailable()) { return global.Promise.resolve(false); }
        dlMounting = true;
        return helper().ensure().then(function (V) {
            if (!V) { dlMounting = false; return false; }
            Vue = V;
            if (!dlStore) { dlStore = buildDlStore(); }
            var statsEl = doc.querySelector('.dash-stats');
            var currentEl = doc.getElementById('current-card');
            var listEl = doc.getElementById('queue-list');
            var pending = [];
            if (statsEl) { pending.push(mountOne(statsEl, statsComponent())); }
            if (currentEl) { pending.push(mountOne(currentEl, currentComponent())); }
            if (listEl) { pending.push(mountOne(listEl, listComponent())); }
            return global.Promise.all(pending).then(function () {
                dlActive = dlApps.length > 0;
                dlMounting = false;
                // 经现有门面（updateStats / renderQueue / setCurrent）回灌当前 state，再 flush——复用命令式同一口径填充，
                // 不另造 seed 逻辑、不与 updateStats 的计数口径分叉。
                if (dlActive) { refreshDownloadFromState(); }
                return dlActive;
            });
        }).catch(function (e) {
            dlMounting = false;
            warn('下载队列岛挂载失败，沿用命令式渲染', e);
            return false;
        });
    }

    function refreshDownloadFromState() {
        try {
            var updateStats = g('updateStats');
            var renderQueue = g('renderQueue');
            var setCurrent = g('setCurrent');
            if (typeof updateStats === 'function') { updateStats(); }
            if (typeof renderQueue === 'function') { renderQueue(); }
            if (typeof setCurrent === 'function') {
                var st = batchState();
                var cur = (st && st.currentItemId != null)
                    ? (st.queue || []).find(function (q) { return String(q.id) === String(st.currentItemId); }) || null
                    : null;
                setCurrent(cur);
            }
            flushNow();
        } catch (e) { warn('下载队列岛回灌失败', e); }
    }

    function isDownloadActive() { return dlActive; }

    function downloadQueueSnapshot() {
        var st = batchState();
        return (st && Array.isArray(st.queue)) ? st.queue.slice() : [];
    }

    function syncDownloadList() {
        schedule('dl:list', function () { if (dlStore) { dlStore.items = downloadQueueSnapshot(); } });
    }
    function syncDownloadStats(s) {
        schedule('dl:stats', function () {
            if (!dlStore) { return; }
            dlStore.stats = {
                pending: s.pending, success: s.success, failed: s.failed,
                active: s.active, skipped: s.skipped
            };
        });
    }
    function syncDownloadCurrent(item) {
        schedule('dl:current', function () {
            if (dlStore) {
                dlStore.current = item ? Object.assign({}, item) : null;
                // 空闲态的值仍是 null；递增 revision 让语言切换等显式刷新也能重新求值当前卡文案。
                dlStore.currentRevision++;
            }
        });
    }
    function syncDownloadSpeed(value, unit) {
        schedule('dl:speed', function () {
            if (dlStore) { dlStore.speed = { value: String(value), unit: String(unit) }; }
        });
    }

    /* ============================================================
       计划任务「本轮队列详情」岛：每个展开任务一个 store + 一个挂载点（.schedule-queue-body）。
       数据由调用方（schedule.js）经 ctx.read() 提供已派生好的 { statusText, statsText, current, items }——
       本模块不反向 import schedule.js 内部模型，只把读出的快照塞进 reactive store 并用共享格式化函数渲染。
    ============================================================ */
    var schedEntries = new global.Map();   // taskId -> { app, store, bodyEl, read, active, mounting, failed }

    function buildSchedStore() {
        return Vue.reactive({ statusText: '', statsText: '', current: null, items: [] });
    }

    // 计划队列详情组件：四段结构逐字镜像 renderScheduleQueueBody（状态 / 统计 / 当前卡 / 列表），
    // 行与当前卡共用 buildQueueItemHtml / formatCurrentCardHtml（与普通队列同口径、不分叉）。
    function schedComponent(entry) {
        return {
            setup: function () {
                return {
                    store: entry.store,
                    currentHtml: function () { return currentCardHtmlOf(entry.store.current); },
                    rowHtml: function (q) { return rowHtmlOf(q, { removable: false, queueId: q.id }); },
                    emptyText: function () { return tt('status.queue-empty', '队列为空'); }
                };
            },
            template:
                '<div class="schedule-queue-status">{{ store.statusText }}</div>'
                + '<div class="schedule-queue-stats">{{ store.statsText }}</div>'
                + '<div class="schedule-queue-current" v-html="currentHtml()"></div>'
                + '<div class="schedule-queue-list">'
                + '<template v-if="store.items.length"><div class="q-item-host" v-for="q in store.items" :key="q.id" v-html="rowHtml(q)"></div></template>'
                + '<div v-else class="queue-empty">{{ emptyText() }}</div>'
                + '</div>'
        };
    }

    function seedSchedStore(entry) {
        if (!entry || !entry.store || typeof entry.read !== 'function') { return; }
        var snap = entry.read() || {};
        entry.store.statusText = snap.statusText != null ? snap.statusText : '';
        entry.store.statsText = snap.statsText != null ? snap.statsText : '';
        entry.store.current = snap.current || null;
        entry.store.items = Array.isArray(snap.items) ? snap.items : [];
    }

    function listScrollTop(bodyEl) {
        var list = bodyEl && bodyEl.querySelector ? bodyEl.querySelector('.schedule-queue-list') : null;
        return list ? (list.scrollTop || 0) : 0;
    }
    function restoreListScroll(bodyEl, top) {
        if (!top) { return; }
        var list = bodyEl && bodyEl.querySelector ? bodyEl.querySelector('.schedule-queue-list') : null;
        if (list) { list.scrollTop = top; }
    }

    function teardownEntry(entry) {
        if (entry && entry.app) { try { entry.app.unmount(); } catch (e) { /* 卸载失败忽略 */ } }
        if (entry) { entry.app = null; entry.active = false; }
    }

    // 异步挂载（懒加载 Vue → 在当前 body 上 mountOn）。对同一 (id, body) 幂等，避免重复挂载。
    function kickScheduleMount(id, ctx) {
        var prev = schedEntries.get(id);
        if (prev && prev.mounting && prev.bodyEl === ctx.bodyEl) { return; }
        var scrollTop = listScrollTop(ctx.bodyEl);
        teardownEntry(prev);   // body 被替换 / 失效：卸载旧 app
        var entry = { app: null, store: null, bodyEl: ctx.bodyEl, read: ctx.read, active: false, mounting: true, failed: false };
        schedEntries.set(id, entry);
        helper().ensure().then(function (V) {
            if (schedEntries.get(id) !== entry) { return; }   // 期间又被替换：交给更新的一次
            if (!V) { entry.mounting = false; entry.failed = true; return; }
            Vue = Vue || V;
            entry.store = buildSchedStore();
            seedSchedStore(entry);
            return helper().mountOn(ctx.bodyEl, schedComponent(entry)).then(function (h) {
                if (schedEntries.get(id) !== entry) {   // 已被取代：卸载这次的 app
                    if (h && h.app) { try { h.app.unmount(); } catch (e) { /* 忽略 */ } }
                    return;
                }
                entry.mounting = false;
                if (h && h.app) {
                    entry.app = h.app;
                    entry.active = true;
                    restoreListScroll(ctx.bodyEl, scrollTop);
                } else {
                    entry.failed = true;   // 挂载失败：永久回退命令式
                }
            });
        }).catch(function (e) {
            entry.mounting = false; entry.failed = true;
            warn('计划队列详情挂载失败，沿用命令式渲染', e);
        });
    }

    // 确保某任务的计划队列详情由 Vue 接管。返回 true 表示 Vue 已（将）接管该 body，调用方据 isScheduleActive
    // 决定是否 syncScheduleQueue；返回 false 表示 Vue 不可用 / 已失败，调用方走命令式 innerHTML。
    function ensureScheduleQueue(id, ctx) {
        id = Number(id);
        if (!helperAvailable() || !ctx || !ctx.bodyEl) { return false; }
        var entry = schedEntries.get(id);
        if (entry && entry.failed) { return false; }
        if (entry && entry.active && entry.bodyEl === ctx.bodyEl && doc.contains(entry.bodyEl)) {
            entry.read = ctx.read;   // 刷新读取闭包（id 稳定，读最新模型）
            return true;
        }
        kickScheduleMount(id, ctx);
        // 首次 / 重挂这一拍尚未激活：让调用方命令式兜底首屏，挂载完成后下一拍 reactive 接管。
        return isScheduleActive(id);
    }

    function isScheduleActive(id) {
        var entry = schedEntries.get(Number(id));
        return !!(entry && entry.active && doc.contains(entry.bodyEl));
    }

    function syncScheduleQueue(id) {
        id = Number(id);
        schedule('sched:' + id, function () {
            var entry = schedEntries.get(id);
            if (entry && entry.active) { seedSchedStore(entry); }
        });
    }

    function unmountScheduleQueue(id) {
        id = Number(id);
        var entry = schedEntries.get(id);
        if (entry) { teardownEntry(entry); schedEntries.delete(id); }
    }

    /* ============================================================
       门面
    ============================================================ */
    global.PixivBatch = global.PixivBatch || {};
    global.PixivBatch.queueVue = global.PixivBatch.queueVue || {};
    Object.assign(global.PixivBatch.queueVue, {
        // 可用性
        helperAvailable: helperAvailable,
        // 普通下载队列
        mountDownloadQueue: mountDownloadQueue,
        isDownloadActive: isDownloadActive,
        syncDownloadList: syncDownloadList,
        syncDownloadStats: syncDownloadStats,
        syncDownloadCurrent: syncDownloadCurrent,
        syncDownloadSpeed: syncDownloadSpeed,
        // 计划任务本轮队列详情
        ensureScheduleQueue: ensureScheduleQueue,
        isScheduleActive: isScheduleActive,
        syncScheduleQueue: syncScheduleQueue,
        unmountScheduleQueue: unmountScheduleQueue,
        // 合批 flush（挂载即时填充 / 测试确定性 flush）
        flush: flushNow,
        // 测试内省（仅供单测断言，不在生产路径调用）
        __test: {
            downloadStore: function () { return dlStore; },
            scheduleEntry: function (id) { return schedEntries.get(Number(id)); },
            statsComponent: statsComponent,
            currentComponent: currentComponent,
            listComponent: listComponent,
            schedComponent: schedComponent,
            reset: function () {
                pendingJobs.clear(); rafScheduled = false;
                dlStore = null; dlApps = []; dlActive = false; dlMounting = false;
                schedEntries.clear(); Vue = null;
            }
        }
    });
})(typeof window !== 'undefined' ? window : this);
