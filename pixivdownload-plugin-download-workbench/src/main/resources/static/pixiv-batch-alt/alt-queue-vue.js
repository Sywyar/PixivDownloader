'use strict';
/* ============================================================
   alt-queue-vue — 下载坞「统计 / 当前下载 / 队列列表」与计划任务「本轮队列详情」
   的 Vue reactive 岛。

   主路径：PixivVue.ensure() 懒加载核心 Vue 运行时。下载坞一个共享 reactive
   store + 三个挂载点（.ab-dock-stats / #abCurrentCard / #abQueueList）；
   计划任务本轮队列详情每个展开任务一个 store + 一个挂载点（#abScheduleQueue-<id>）。
   进度扇出只改 store，Vue 按 :key 与绑定仅 patch 变化的行 / 字段，替代每个
   进度事件整块 innerHTML 重建造成的卡顿。命令式渲染（alt-queue.js /
   alt-schedule.js）保留为首屏即时占位与回退：window.PixivVue 缺失 / 运行时
   加载失败 / 挂载抛错一律收敛为「未激活」，调用方继续走命令式路径，绝不向
   init 抛异常。

   约定（同「Vue 运行时与槽位挂载约定」）：
   - 模板 v-if / v-for 条件一律走 setup 暴露的 computed / 方法（规避
     vue.global.prod.js 运行时编译器对成员链 && / || 的静态折叠崩溃）。
   - 模型只存 raw 字段，文案渲染期经 bt() 派生；语言切换经 renderDock()
     重建挂载点后由 ensure() 重挂，渲染期重新派生。
   - 结构逐字镜像命令式 renderDock / renderCurrent / queueItemRow /
     renderScheduleQueue 的 id / class / data-* 契约，两条路径视觉与行为一致。
   ============================================================ */

/* —— 高频更新合批：同一帧内多次 store 同步合并为一次（同 key 去重，与 batch-queue-vue 同语义） —— */
const aqvRaf = (typeof requestAnimationFrame === 'function')
    ? cb => requestAnimationFrame(cb)
    : cb => setTimeout(cb, 16);
const aqvJobs = new Map();
let aqvRafScheduled = false;

function aqvRunJobs() {
    aqvRafScheduled = false;
    const fns = [];
    aqvJobs.forEach(fn => fns.push(fn));
    aqvJobs.clear();
    fns.forEach(fn => {
        try { fn(); } catch (e) { aqvWarn('合批任务执行失败', e); }
    });
}

function aqvSchedule(key, fn) {
    aqvJobs.set(key, fn);
    if (!aqvRafScheduled) {
        aqvRafScheduled = true;
        aqvRaf(aqvRunJobs);
    }
}

// 立即执行待处理的合批任务（挂载后即时填充用）。
function aqvFlush() {
    if (aqvJobs.size) aqvRunJobs();
}

function aqvWarn(msg, e) {
    try { console.warn('[alt-queue-vue] ' + msg, e); } catch (ignored) { /* 无 console：忽略 */ }
}

/* —— Vue 共享 helper 可用性与运行时（懒加载缓存） —— */
let aqvVue = null;
let aqvStore = null;
let aqvApps = [];
let aqvActive = false;
let aqvMounting = false;

function aqvHelper() {
    return window.PixivVue;
}

function aqvHelperAvailable() {
    const h = aqvHelper();
    return !!(h && typeof h.ensure === 'function' && typeof h.mountOn === 'function');
}

function aqvT(key, fallback, vars) {
    return (typeof bt === 'function') ? bt(key, fallback, vars) : (fallback != null ? fallback : key);
}

function aqvIcon(name) {
    return (typeof abIcon === 'function') ? abIcon(name) : '';
}

// 行身份与 batch-queue-vue 同口径：优先 queueTypes.queueKey，缺失时本地同规则编码。
function aqvRowKey(item) {
    const runtime = window.PixivBatch && window.PixivBatch.queueTypes;
    if (runtime && typeof runtime.queueKey === 'function') return runtime.queueKey(item);
    const q = item && typeof item === 'object' ? item : {};
    const enc = value => {
        const raw = value == null ? '' : String(value);
        let out = '';
        for (let i = 0; i < raw.length; i++) {
            out += raw.charCodeAt(i).toString(16).padStart(4, '0');
        }
        return out;
    };
    const type = q.workType != null ? q.workType : q.kind;
    const workId = q.workId != null ? q.workId : q.id;
    return 'q:' + enc(type == null ? '' : String(type).trim()) + '.' + enc(workId);
}

function aqvBuildStore() {
    return aqvVue.reactive({
        stats: {pending: 0, success: 0, failed: 0, active: 0, skipped: 0},
        speed: {value: '0', unit: 'B/s'},
        paused: false,      // 暂停标志镜像（当前卡响应式派生用；暂停 / 恢复时由渲染门面同步）
        items: []           // state.queue 浅快照（行对象引用，渲染期读最新字段）
    });
}

/* —— 统计数字滚动（与命令式 animateCount 同参数：420ms、ease 1-(1-t)^3、token 取消） —— */
const aqvStatTweens = {};

function aqvTweenStat(key, to) {
    if (!aqvStore) return;
    const target = Number(to) || 0;
    const from = Number(aqvStore.stats[key] || 0);
    const token = String(Math.random());
    aqvStatTweens[key] = token;
    if (from === target) {
        aqvStore.stats[key] = target;
        return;
    }
    const duration = 420;
    const start = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
    const step = () => {
        if (aqvStatTweens[key] !== token || !aqvStore) return;
        const now = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
        const t = Math.min(1, (now - start) / duration);
        const eased = 1 - Math.pow(1 - t, 3);
        aqvStore.stats[key] = Math.round(from + (target - from) * eased);
        if (t < 1) aqvRaf(step);
    };
    aqvRaf(step);
}

/* —— 展示模型派生：一次渲染内一次性算出该行全部展示字段，模板只读字段、显隐走方法 —— */
function aqvExtrasHtml(q) {
    if (typeof progressExtras !== 'function') return '';
    const node = progressExtras(q);
    return node ? node.outerHTML : '';
}

function aqvRowModel(q) {
    const runtime = window.PixivBatch && window.PixivBatch.queueTypes;
    const xr = q.xRestrict == null ? null : Number(q.xRestrict);
    let xrTag;
    if (xr === 2) xrTag = {key: 'xr', cls: 'ab-queue-tag--r18g', text: 'R-18G'};
    else if (xr === 1) xrTag = {key: 'xr', cls: 'ab-queue-tag--r18', text: 'R-18'};
    else if (xr === null || !Number.isFinite(xr)) {
        xrTag = {key: 'xr', cls: 'ab-queue-tag--unknown', text: aqvT('queue.unknown', '未知')};
    } else xrTag = {key: 'xr', cls: 'ab-queue-tag--sfw', text: 'SFW'};
    const tags = [
        {key: 'source', cls: 'ab-queue-tag--source', text: queueDataSourceText(q)},
        {key: 'mode', cls: 'ab-queue-tag--mode', text: queueSourceText(q.source)},
        xrTag
    ];
    const contributed = runtime && typeof runtime.queueTags === 'function' ? runtime.queueTags(q) : [];
    contributed.forEach(tag => {
        if (!tag || !tag.label) return;
        tags.push({key: 'plugin-' + (tag.id || tag.label), cls: 'ab-queue-tag--plugin', text: tag.label});
    });
    const percent = q.totalImages > 0 ? pct(q) : 0;
    return {
        key: aqvRowKey(q),
        queueId: String(q.id),
        status: q.status,
        title: queueItemDisplayTitle(q),
        url: queueItemCanonicalUrl(q),
        canCancel: q.status === 'downloading'
            && !!(runtime && typeof runtime.canCancel === 'function' && runtime.canCancel(q)),
        removable: q.status !== 'downloading',
        tags,
        idLine: 'ID: ' + (q.kind === 'novel'
            ? (q.novelId || String(q.id).replace(/^n/, '')) + ' (Novel)'
            : q.id) + ' | ',
        message: queueItemMessage(q),
        progress: q.totalImages > 0 ? {
            label: aqvT('status.image-progress', '{downloaded} / {total} 张',
                {downloaded: q.downloadedCount || 0, total: q.totalImages}),
            text: percent + '%',
            cls: 'is-' + q.status,
            width: percent + '%'
        } : null,
        extrasHtml: aqvExtrasHtml(q),
        ref: q
    };
}

/* —— 三个挂载点的组件：结构逐字镜像命令式 renderDock 统计卡 / renderCurrent / queueItemRow —— */
function aqvStatsComponent() {
    return {
        setup() {
            return {store: aqvStore, t: aqvT, icon: aqvIcon};
        },
        template:
            '<div class="ab-stat stat-card"><span class="ab-icon ab-stat-icon" v-html="icon(\'clock\')"></span>'
            + '<strong class="ab-stat-value" id="abStatPending">{{ store.stats.pending }}</strong>'
            + '<span class="ab-stat-label">{{ t(\'stats.queued\', \'队列\') }}</span></div>'
            + '<div class="ab-stat stat-card"><span class="ab-icon ab-stat-icon" v-html="icon(\'check\')"></span>'
            + '<strong class="ab-stat-value" id="abStatSuccess">{{ store.stats.success }}</strong>'
            + '<span class="ab-stat-label">{{ t(\'stats.success\', \'成功\') }}</span></div>'
            + '<div class="ab-stat stat-card"><span class="ab-icon ab-stat-icon" v-html="icon(\'x\')"></span>'
            + '<strong class="ab-stat-value" id="abStatFailed">{{ store.stats.failed }}</strong>'
            + '<span class="ab-stat-label">{{ t(\'stats.failed\', \'失败\') }}</span></div>'
            + '<div class="ab-stat stat-card"><span class="ab-icon ab-stat-icon" v-html="icon(\'download\')"></span>'
            + '<strong class="ab-stat-value" id="abStatActive">{{ store.stats.active }}</strong>'
            + '<span class="ab-stat-label">{{ t(\'stats.active\', \'进行中\') }}</span></div>'
            + '<div class="ab-stat stat-card"><span class="ab-icon ab-stat-icon" v-html="icon(\'chevron-right\')"></span>'
            + '<strong class="ab-stat-value" id="abStatSkipped">{{ store.stats.skipped }}</strong>'
            + '<span class="ab-stat-label">{{ t(\'stats.skipped\', \'跳过\') }}</span></div>'
            + '<div class="ab-stat stat-card ab-stat--speed"><span class="ab-icon ab-stat-icon" v-html="icon(\'gauge\')"></span>'
            + '<strong class="ab-stat-value" id="abStatSpeed">{{ store.speed.value }}</strong>'
            + '<span class="ab-stat-label" id="abStatSpeedUnit">{{ store.speed.unit }}</span></div>'
    };
}

function aqvCurrentComponent() {
    return {
        setup() {
            // 当前卡内容由 alt-queue.js 的 computeCurrentCardHtml 从 reactive 队列镜像 + 暂停标志派生
            //（与 pixiv-batch.html 的 computeCurrentCardHtml 同手法：内容节点构建 + 剩余计数行，含进度环 /
            // 流式图片进度条 / 附加进度）。任何列表同步或暂停 / 恢复同步都会让 Vue 重算本函数并只 patch 这一张
            // 卡；文案在渲染期经 bt 派生（跟随语言切换）。单 v-html 模板（无 v-if / 成员链条件）规避 prod 模板
            // 编译器的静态折叠崩溃，与命令式回退共用同一派生口径。
            return {
                store: aqvStore,
                currentHtml() {
                    return (typeof computeCurrentCardHtml === 'function')
                        ? computeCurrentCardHtml(aqvStore.items, aqvStore.paused)
                        : '';
                }
            };
        },
        template: '<span style="display:contents" v-html="currentHtml()"></span>'
    };
}

function aqvListComponent() {
    return {
        setup() {
            const rows = aqvVue.computed(() => aqvStore.items.map(q => aqvRowModel(q)));
            const isEmpty = aqvVue.computed(() => !aqvStore.items.length);
            return {
                rows,
                isEmpty,
                t: aqvT,
                icon: aqvIcon,
                noop() {},
                rowTags: r => r.tags,
                showCancel: r => r.canCancel,
                showRemove: r => r.removable,
                showProgress: r => !!r.progress,
                showExtras: r => !!r.extrasHtml,
                cancelRow(r) {
                    if (typeof requestQueueItemCancel === 'function') requestQueueItemCancel(r.ref.id);
                },
                removeRow(r) {
                    if (typeof removeFromQueue !== 'function') return;
                    if (removeFromQueue(r.ref.id)) {
                        abToast('info', aqvT('queue.toast.removed', '已从队列移除'));
                    } else {
                        abToast('warning', aqvT('queue.toast.remove-blocked', '无法移除：正在下载中'));
                    }
                }
            };
        },
        template:
            '<div v-if="isEmpty" class="ab-empty ab-empty--dock">'
            + '<span class="ab-icon" v-html="icon(\'download\')"></span>'
            + '<p>{{ t(\'status.queue-empty\', \'队列为空\') }}</p>'
            + '</div>'
            + '<template v-else>'
            + '<div class="ab-queue-item" v-for="r in rows" :key="r.key"'
            + ' :data-queue-id="r.queueId" :data-status="r.status">'
            + '<div class="ab-queue-title">'
            + '<span class="ab-queue-name">{{ r.title }}</span>'
            + '<a class="ab-iconbtn ab-iconbtn--xs" :href="r.url" target="_blank" rel="noopener"'
            + ' :title="t(\'queue.open-artwork\', \'打开作品页面\')" @click.stop="noop">'
            + '<span class="ab-icon" v-html="icon(\'external\')"></span></a>'
            + '<button v-if="showCancel(r)" type="button" class="ab-iconbtn ab-iconbtn--xs"'
            + ' :title="t(\'queue.cancel\', \'取消下载\')" @click.stop="cancelRow(r)">'
            + '<span class="ab-icon" v-html="icon(\'stop\')"></span></button>'
            + '<button v-if="showRemove(r)" type="button" class="ab-iconbtn ab-iconbtn--xs"'
            + ' :title="t(\'queue.remove\', \'移除\')" @click.stop="removeRow(r)">'
            + '<span class="ab-icon" v-html="icon(\'x\')"></span></button>'
            + '</div>'
            + '<div class="ab-queue-tags">'
            + '<span v-for="tag in rowTags(r)" :key="tag.key" class="ab-queue-tag" :class="tag.cls">{{ tag.text }}</span>'
            + '</div>'
            + '<div class="ab-queue-meta">{{ r.idLine }}<span class="ab-queue-status" :data-status="r.status">{{ r.message }}</span></div>'
            + '<div v-if="showProgress(r)" class="ab-mini-prog">'
            + '<div class="ab-mini-prog-label"><span>{{ r.progress.label }}</span><span>{{ r.progress.text }}</span></div>'
            + '<div class="ab-mini-prog-bar"><div class="ab-mini-prog-fill" :class="r.progress.cls"'
            + ' :style="{ width: r.progress.width }"></div></div>'
            + '</div>'
            + '<span v-if="showExtras(r)" class="ab-flatten" v-html="r.extrasHtml"></span>'
            + '</div>'
            + '</template>'
    };
}

/* —— 挂载 / 卸载 / 重挂（renderDock 重建挂载点后旧宿主失效，探测并重挂） —— */
let aqvCurrentApp = null;   // 当前卡专属挂载（供 isCurrentActive 判定；与统计 / 列表的岛级激活相互独立）

function aqvMountOne(el, comp, kind) {
    return aqvHelper().mountOn(el, comp).then(h => {
        if (h && h.app) {
            const entry = {el, app: h.app};
            aqvApps.push(entry);
            if (kind === 'current') aqvCurrentApp = entry;
        }
        return h;
    });
}

function aqvTeardown() {
    aqvApps.splice(0).forEach(entry => {
        try { entry.app.unmount(); } catch (e) { /* 卸载失败忽略 */ }
    });
    aqvCurrentApp = null;
    aqvActive = false;
}

// 经既有门面（updateStats / renderQueue / renderCurrent）回灌当前 state——与命令式同一口径填充。
function aqvRefreshFromState() {
    try {
        if (typeof updateStats === 'function') updateStats();
        if (typeof renderQueue === 'function') renderQueue();
        if (typeof renderCurrent === 'function') {
            const cur = (typeof state !== 'undefined' && state.currentItemId != null)
                ? (state.queue || []).find(q => String(q.id) === String(state.currentItemId)) || null
                : null;
            renderCurrent(cur);
        }
        if (typeof dockState !== 'undefined' && dockState && dockState.speed) {
            aqvSyncSpeed(dockState.speed.value, dockState.speed.unit);
        }
        aqvFlush();
    } catch (e) {
        aqvWarn('下载坞回灌失败', e);
    }
}

// 幂等确保下载坞由 Vue 接管。返回 Promise<boolean>：Vue 不可用 / 挂载失败 → false（调用方命令式兜底）。
function aqvEnsure() {
    if (!aqvHelperAvailable()) return Promise.resolve(false);
    const body = document.getElementById('abDockBody');
    if (!body) return Promise.resolve(false);
    const stale = aqvApps.length > 0 && aqvApps.some(entry => !document.contains(entry.el));
    if (aqvActive && !stale) return Promise.resolve(true);
    if (aqvMounting) return Promise.resolve(false);
    if (stale) aqvTeardown();
    aqvMounting = true;
    return aqvHelper().ensure().then(V => {
        if (!V) {
            aqvMounting = false;
            return false;
        }
        aqvVue = V;
        if (!aqvStore) aqvStore = aqvBuildStore();
        const pending = [];
        const statsEl = body.querySelector('.ab-dock-stats');
        const currentEl = body.querySelector('#abCurrentCard');
        const listEl = body.querySelector('#abQueueList');
        if (statsEl) pending.push(aqvMountOne(statsEl, aqvStatsComponent(), 'stats'));
        if (currentEl) pending.push(aqvMountOne(currentEl, aqvCurrentComponent(), 'current'));
        if (listEl) pending.push(aqvMountOne(listEl, aqvListComponent(), 'list'));
        return Promise.all(pending).then(() => {
            aqvActive = aqvApps.length > 0;
            aqvMounting = false;
            if (aqvActive) aqvRefreshFromState();
            return aqvActive;
        });
    }).catch(e => {
        aqvMounting = false;
        aqvWarn('下载坞 Vue 岛挂载失败，沿用命令式渲染', e);
        return false;
    });
}

function aqvIsActive() {
    return aqvActive && aqvApps.length > 0 && aqvApps.every(entry => document.contains(entry.el));
}

// 当前卡是否仍由 Vue 接管（专属判定）：当前卡挂载失败时即使统计 / 列表岛激活，渲染门面也走命令式当前卡。
function aqvIsCurrentActive() {
    return !!(aqvCurrentApp && document.contains(aqvCurrentApp.el));
}

/* —— 同步入口（由 alt-queue.js 既有门面在 Vue 激活时调用） —— */
function aqvSyncStats(counts) {
    aqvSchedule('stats', () => {
        if (!aqvStore || !counts) return;
        ['pending', 'success', 'failed', 'active', 'skipped'].forEach(key => aqvTweenStat(key, counts[key]));
    });
}

function aqvSyncSpeed(value, unit) {
    aqvSchedule('speed', () => {
        if (aqvStore) aqvStore.speed = {value: String(value), unit: String(unit)};
    });
}

function aqvSyncPaused(paused) {
    aqvSchedule('paused', () => {
        if (aqvStore) aqvStore.paused = !!paused;
    });
}

function aqvSyncList() {
    aqvSchedule('list', () => {
        if (aqvStore && typeof state !== 'undefined') aqvStore.items = (state.queue || []).slice();
    });
}

/* ============================================================
   计划任务「本轮队列详情」岛：每个展开任务一个 store + 一个挂载点
   （#abScheduleQueue-<taskId>）。数据由 alt-schedule.js 经 ctx.read()
   提供已派生好的展示模型（startedText / statsText / truncated / rows），
   本模块不反向 import schedule 内部模型；模板逐字镜像命令式
   renderScheduleQueue 的 class / data-* 契约。
   ============================================================ */
const aqvSchedEntries = new Map();   // taskId -> {app, store, boxEl, read, active, mounting, failed}

function aqvBuildSchedStore() {
    return aqvVue.reactive({
        startedText: '',
        statsText: '',
        truncated: false,
        truncatedText: '',
        empty: true,
        emptyText: '',
        rows: []            // [{key, status, title, showTranslate, translateText, statusText}]
    });
}

function aqvSeedSchedStore(entry) {
    if (!entry || !entry.store || typeof entry.read !== 'function') return;
    const m = entry.read() || {};
    entry.store.startedText = m.startedText != null ? m.startedText : '';
    entry.store.statsText = m.statsText != null ? m.statsText : '';
    entry.store.truncated = !!m.truncated;
    entry.store.truncatedText = m.truncatedText != null ? m.truncatedText : '';
    entry.store.empty = !!m.empty;
    entry.store.emptyText = m.emptyText != null ? m.emptyText : '';
    entry.store.rows = Array.isArray(m.rows) ? m.rows : [];
}

// 计划队列详情组件：四段结构逐字镜像 renderScheduleQueue（head / truncated 提示 /
// empty 行 / round-list 行），行模型由 alt-schedule.js 派生，模板只读字段。
function aqvSchedComponent(entry) {
    return {
        setup() {
            return {store: entry.store, t: aqvT};
        },
        template:
            '<div class="ab-round-head"><span class="ab-muted">{{ store.startedText }}</span>'
            + '<span class="ab-muted">{{ store.statsText }}</span></div>'
            + '<p v-if="store.truncated" class="ab-field-note">{{ store.truncatedText }}</p>'
            + '<p v-if="store.empty" class="ab-empty-line">{{ store.emptyText }}</p>'
            + '<template v-else>'
            + '<div class="ab-round-list">'
            + '<div class="ab-round-item" v-for="row in store.rows" :key="row.key" :data-status="row.status">'
            + '<span class="ab-round-title">{{ row.title }}</span>'
            + '<span class="ab-round-right">'
            + '<span v-if="row.showTranslate" class="ab-mini-badge ab-mini-badge--ai">{{ row.translateText }}</span>'
            + '<span class="ab-round-status">{{ row.statusText }}</span>'
            + '</span></div></div></template>'
    };
}

function aqvSchedTeardown(entry) {
    if (entry && entry.app) { try { entry.app.unmount(); } catch (e) { /* 卸载失败忽略 */ } }
    if (entry) { entry.app = null; entry.active = false; }
}

// 异步挂载（懒加载 Vue → 在当前 box 上 mountOn）。对同一 (id, box) 幂等，避免重复挂载。
function aqvSchedKickMount(id, ctx) {
    const prev = aqvSchedEntries.get(id);
    if (prev && prev.mounting && prev.boxEl === ctx.boxEl) return;
    aqvSchedTeardown(prev);   // box 被替换 / 失效：卸载旧 app
    const entry = {app: null, store: null, boxEl: ctx.boxEl, read: ctx.read,
        active: false, mounting: true, failed: false};
    aqvSchedEntries.set(id, entry);
    aqvHelper().ensure().then(V => {
        if (aqvSchedEntries.get(id) !== entry) return;   // 期间又被替换：交给更新的一次
        if (!V) { entry.mounting = false; entry.failed = true; return; }
        aqvVue = V;
        entry.store = aqvBuildSchedStore();
        aqvSeedSchedStore(entry);
        return aqvHelper().mountOn(ctx.boxEl, aqvSchedComponent(entry)).then(h => {
            if (aqvSchedEntries.get(id) !== entry) {   // 已被取代：卸载这次的 app
                if (h && h.app) { try { h.app.unmount(); } catch (e) { /* 忽略 */ } }
                return;
            }
            entry.mounting = false;
            if (h && h.app) {
                entry.app = h.app;
                entry.active = true;
            } else {
                entry.failed = true;   // 挂载失败：永久回退命令式
            }
        });
    }).catch(e => {
        entry.mounting = false; entry.failed = true;
        aqvWarn('计划队列详情挂载失败，沿用命令式渲染', e);
    });
}

// 确保某任务的计划队列详情由 Vue 接管。返回 true 表示 Vue 已（将）接管该 box，
// 调用方据 isScheduleActive 决定是否 syncScheduleQueue；返回 false 表示 Vue 不可用 /
// 已失败，调用方走命令式 innerHTML。
function aqvEnsureScheduleQueue(id, ctx) {
    id = Number(id);
    if (!aqvHelperAvailable() || !ctx || !ctx.boxEl) return false;
    const entry = aqvSchedEntries.get(id);
    if (entry && entry.failed) return false;
    if (entry && entry.active && entry.boxEl === ctx.boxEl && document.contains(entry.boxEl)) {
        entry.read = ctx.read;   // 刷新读取闭包（id 稳定，读最新模型）
        return true;
    }
    aqvSchedKickMount(id, ctx);
    // 首次 / 重挂这一拍尚未激活：让调用方命令式兜底首屏，挂载完成后下一拍 reactive 接管。
    return aqvIsScheduleActive(id);
}

function aqvIsScheduleActive(id) {
    const entry = aqvSchedEntries.get(Number(id));
    return !!(entry && entry.active && document.contains(entry.boxEl));
}

function aqvSyncScheduleQueue(id) {
    id = Number(id);
    aqvSchedule('sched:' + id, () => {
        const entry = aqvSchedEntries.get(id);
        if (entry && entry.active) aqvSeedSchedStore(entry);
    });
}

function aqvUnmountScheduleQueue(id) {
    id = Number(id);
    const entry = aqvSchedEntries.get(id);
    if (entry) { aqvSchedTeardown(entry); aqvSchedEntries.delete(id); }
}

window.PixivBatchAlt.queueVue = Object.assign(window.PixivBatchAlt.queueVue || {}, {
    ensure: aqvEnsure,
    isActive: aqvIsActive,
    isCurrentActive: aqvIsCurrentActive,
    syncStats: aqvSyncStats,
    syncSpeed: aqvSyncSpeed,
    syncPaused: aqvSyncPaused,
    syncList: aqvSyncList,
    // 计划任务本轮队列详情
    ensureScheduleQueue: aqvEnsureScheduleQueue,
    isScheduleActive: aqvIsScheduleActive,
    syncScheduleQueue: aqvSyncScheduleQueue,
    unmountScheduleQueue: aqvUnmountScheduleQueue,
    // 合批 flush（挂载即时填充 / 测试确定性 flush）
    flush: aqvFlush,
    // 测试内省（仅供单测断言，不在生产路径调用）
    __test: {
        dockStore: function () { return aqvStore; },
        statsComponent: aqvStatsComponent,
        currentComponent: aqvCurrentComponent,
        listComponent: aqvListComponent,
        schedEntry: function (id) { return aqvSchedEntries.get(Number(id)); },
        schedComponent: aqvSchedComponent,
        reset: function () {
            aqvJobs.clear();
            aqvRafScheduled = false;
            aqvApps.splice(0).forEach(entry => {
                try { entry.app.unmount(); } catch (e) { /* 忽略 */ }
            });
            aqvCurrentApp = null;
            aqvStore = null;
            aqvActive = false;
            aqvMounting = false;
            aqvSchedEntries.forEach(entry => {
                if (entry.app) { try { entry.app.unmount(); } catch (e) { /* 忽略 */ } }
            });
            aqvSchedEntries.clear();
        }
    }
});
