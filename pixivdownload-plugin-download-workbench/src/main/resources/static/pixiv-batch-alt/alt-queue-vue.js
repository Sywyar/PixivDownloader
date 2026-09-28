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
   - 组件直接使用 Vue.h 渲染，遵守 script-src self，不在浏览器编译字符串模板。
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
const aqvDirtyRows = new Map();
const aqvRowIndexes = new Map();
let aqvQueue = null;
let aqvFullRefresh = false;

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
        items: [],          // 行组件直接订阅对应下标，进度变化不触发列表结构重建。
        rowKeys: []         // 仅完整同步时更新，与索引共用复合键，不保留另一份作品内容。
    });
}

/* —— 展示模型派生：一次渲染内一次性算出该行全部展示字段，模板只读字段、显隐走方法 —— */
function aqvMiniProgress(label, value, progress, cls, key, active = true) {
    const h = aqvVue.h;
    const percent = progress == null || !Number.isFinite(Number(progress))
        ? null : Math.max(0, Math.min(100, Math.round(progress)));
    return h('div', {key, class: 'ab-mini-prog'}, [
        h('div', {class: 'ab-mini-prog-label'}, [h('span', label),
            h('span', [value, percent == null ? '' : percent + '%'].filter(Boolean).join(' · '))]),
        h('div', {class: 'ab-mini-prog-bar', role: 'progressbar',
            'aria-label': [label, value].filter(Boolean).join(' · '),
            'aria-valuemin': '0', 'aria-valuemax': '100',
            'aria-valuenow': percent == null ? undefined : String(percent),
            'aria-busy': String(percent == null && active)}, [h('div', {
            class: ['ab-mini-prog-fill', cls, {'is-indeterminate': percent == null && active}],
            style: {width: (percent == null ? 35 : percent) + '%'}
        })])
    ]);
}

function aqvExtras(q) {
    const entries = typeof progressExtrasModel === 'function' ? progressExtrasModel(q) : [];
    if (!entries.length) return null;
    const h = aqvVue.h;
    return h('div', {class: 'ab-progress-extras'}, entries.map(entry => entry.kind === 'progress'
        ? aqvMiniProgress(entry.label, entry.value, entry.progress, entry.cls, entry.key, entry.active)
        : h('p', {key: entry.key, class: ['ab-progress-note', entry.tone ? 'ab-progress-note--' + entry.tone : '']}, [
            entry.badge ? h('span', {class: ['ab-mini-badge', {'ab-mini-badge--ai': entry.ai}]}, entry.badge) : null,
            (entry.badge ? ' ' : '') + entry.text
        ])));
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
        ref: q
    };
}

/* —— 三个挂载点的组件：结构逐字镜像命令式 renderDock 统计卡 / renderCurrent / queueItemRow —— */
function aqvStatsComponent() {
    return {
        setup() {
            return {store: aqvStore, t: aqvT, icon: aqvIcon};
        },
        render() {
            const h = aqvVue.h;
            return [
                ['Pending', 'pending', 'clock', 'stats.queued', '队列'],
                ['Success', 'success', 'check', 'stats.success', '成功'],
                ['Failed', 'failed', 'x', 'stats.failed', '失败'],
                ['Active', 'active', 'download', 'stats.active', '进行中'],
                ['Skipped', 'skipped', 'chevron-right', 'stats.skipped', '跳过'],
                ['Speed', null, 'gauge', null, null]
            ].map(([id, key, icon, label, fallback]) => h('div', {key: id, class: 'ab-stat stat-card' + (key ? '' : ' ab-stat--speed')}, [
                h('span', {class: 'ab-icon ab-stat-icon', 'aria-hidden': 'true', innerHTML: aqvIcon(icon)}),
                h('strong', {class: 'ab-stat-value', id: 'abStat' + id}, key ? aqvStore.stats[key] : aqvStore.speed.value),
                h('span', {class: 'ab-stat-label', id: key ? undefined : 'abStatSpeedUnit'}, key ? aqvT(label, fallback) : aqvStore.speed.unit)
            ]));
        }
    };
}

function aqvCurrentComponent() {
    return {
        setup() {
            const front = aqvVue.computed(() => currentFrontItem(aqvStore.items, aqvStore.paused));
            const remaining = aqvVue.computed(() => {
                if (!front.value) return '';
                const counts = currentRemainingCounts(aqvStore.items, front.value);
                return currentRemainingLineText(counts.downloading, counts.queued);
            });
            return {front, remaining};
        },
        render() {
            const h = aqvVue.h;
            // 完整同步也承接语言刷新，空队列仍需更新当前卡文案。
            const front = aqvStore.rowKeys.length ? this.front : null;
            const model = currentCardModel(front);
            const children = [h('div', {class: 'ab-current-head'}, [
                h('span', {class: 'ab-icon', 'aria-hidden': 'true', innerHTML: aqvIcon('download')}),
                h('strong', aqvT('current.title', '当前下载'))
            ])];
            if (!model) children.push(h('p', {class: 'ab-current-idle'}, aqvT('status.current-idle', '无')));
            else {
                const circumference = 2 * Math.PI * 26;
                children.push(h('div', {class: 'ab-current-row'}, [
                    h('span', {class: 'ab-ring'}, [
                        h('svg', {viewBox: '0 0 64 64'}, [
                            h('circle', {cx: '32', cy: '32', r: '26', class: 'ab-ring-track'}),
                            h('circle', {cx: '32', cy: '32', r: '26', class: 'ab-ring-fill', style: {
                                strokeDasharray: String(circumference),
                                strokeDashoffset: String(circumference * (1 - Math.min(100, Math.max(0, model.percent)) / 100))
                            }})
                        ]), h('span', {class: 'ab-ring-text'}, Math.round(model.percent) + '%')
                    ]),
                    h('div', {class: 'ab-current-meta'}, [h('div', {class: 'ab-current-title'}, model.title),
                        h('div', {class: 'ab-current-detail'}, model.detail)])
                ]));
                if (front.totalImages > 0) children.push(aqvMiniProgress(model.progressLabel, null, model.percent, 'is-image', 'images'));
                children.push(aqvExtras(front));
                const line = this.remaining;
                if (line) children.push(h('p', {class: 'ab-current-remaining'}, line));
            }
            return h('span', {style: {display: 'contents'}}, children);
        }
    };
}

function aqvRenderRow(item, vm) {
    const h = aqvVue.h;
    const r = aqvRowModel(item);
    const icon = name => h('span', {class: 'ab-icon', 'aria-hidden': 'true', innerHTML: aqvIcon(name)});
    const action = (r, name, label, callback) => h('button', {
        type: 'button', class: 'ab-iconbtn ab-iconbtn--xs button', title: label, 'aria-label': label,
        onClick: event => { event.stopPropagation(); callback(r); }
    }, [icon(name)]);
    return h('div', {key: r.key, class: 'ab-queue-item', 'data-queue-id': r.queueId, 'data-status': r.status}, [
        h('div', {class: 'ab-queue-title'}, [
            h('span', {class: 'ab-queue-name'}, r.title),
            h('a', {class: 'ab-iconbtn ab-iconbtn--xs button', href: r.url, target: '_blank', rel: 'noopener',
                title: aqvT('queue.open-artwork', '打开作品页面'), 'aria-label': aqvT('queue.open-artwork', '打开作品页面'),
                onClick: event => event.stopPropagation()}, [icon('external')]),
            r.canCancel ? action(r, 'stop', aqvT('queue.cancel', '取消下载'), vm.cancelRow) : null,
            r.removable ? action(r, 'x', aqvT('queue.remove', '移除'), vm.removeRow) : null
        ]),
        h('div', {class: 'ab-queue-tags'}, r.tags.map(tag => h('span', {key: tag.key, class: ['ab-queue-tag', tag.cls]}, tag.text))),
        h('div', {class: 'ab-queue-meta'}, [r.idLine, h('span', {class: 'ab-queue-status', 'data-status': r.status}, r.message)]),
        r.progress ? h('div', {class: 'ab-mini-prog'}, [
            h('div', {class: 'ab-mini-prog-label'}, [h('span', r.progress.label), h('span', r.progress.text)]),
            h('div', {class: 'ab-mini-prog-bar progressbar', role: 'progressbar', 'aria-label': r.title,
                'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-valuenow': Number.parseFloat(r.progress.width)}, [
                h('span', {class: ['ab-mini-prog-fill', r.progress.cls], style: {transform: 'translate3d(' + (Number.parseFloat(r.progress.width) - 100) + '%, 0, 0)'}})
            ])
        ]) : null,
        aqvExtras(item)
    ]);
}

const aqvDownloadRow = {
    props: ['index', 'actions'],
    render() { return aqvRenderRow(aqvStore.items[this.index], this.actions); }
};

function aqvListComponent() {
    return {
        setup() {
            const isEmpty = aqvVue.computed(() => !aqvStore.rowKeys.length);
            return {
                store: aqvStore,
                isEmpty,
                t: aqvT,
                icon: aqvIcon,
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
        render() {
            const h = aqvVue.h;
            const icon = name => h('span', {class: 'ab-icon', innerHTML: this.icon(name)});
            if (this.isEmpty) return h('div', {class: 'ab-empty ab-empty--dock'}, [
                icon('download'), h('p', null, this.t('status.queue-empty', '队列为空'))
            ]);
            return this.store.rowKeys.map((key, index) => h(aqvDownloadRow, {key, index, actions: this}));
        }
    };
}

/* —— 挂载 / 卸载 / 重挂（renderDock 重建挂载点后旧宿主失效，探测并重挂） —— */
let aqvCurrentApp = null;   // 当前卡专属挂载（供 isCurrentActive 判定；与统计 / 列表的岛级激活相互独立）

function aqvMountOne(el, comp, kind) {
    return aqvHelper().mountOn(el, comp).then(h => {
        if (h && h.app) {
            const entry = {el, app: h.app, kind};
            aqvApps.push(entry);
            if (kind === 'current') aqvCurrentApp = entry;
        }
        return h;
    });
}

function aqvTeardown() {
    aqvRowIndexes.clear();
    aqvDirtyRows.clear();
    aqvQueue = null;
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

function aqvIsActive(kind) {
    return aqvActive && aqvApps.length > 0 && aqvApps.every(entry => document.contains(entry.el))
        && (!kind || aqvApps.some(entry => entry.kind === kind));
}

// 当前卡是否仍由 Vue 接管（专属判定）：当前卡挂载失败时即使统计 / 列表岛激活，渲染门面也走命令式当前卡。
function aqvIsCurrentActive() {
    return !!(aqvCurrentApp && document.contains(aqvCurrentApp.el));
}

/* —— 同步入口（由 alt-queue.js 既有门面在 Vue 激活时调用） —— */
function aqvSyncStats(counts) {
    aqvSchedule('stats', () => {
        if (!aqvStore || !counts) return;
        ['pending', 'success', 'failed', 'active', 'skipped'].forEach(key => {
            aqvStore.stats[key] = Number(counts[key]) || 0;
        });
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

function aqvSyncList(changedItem) {
    if (changedItem) aqvDirtyRows.set(aqvRowKey(changedItem), changedItem);
    else aqvFullRefresh = true;
    aqvSchedule('list', () => {
        if (aqvStore && typeof state !== 'undefined') {
            const queue = state.queue || [];
            let rebuild = aqvFullRefresh || aqvQueue !== queue || aqvStore.items.length !== queue.length;
            aqvDirtyRows.forEach((q, key) => {
                const index = aqvRowIndexes.get(key);
                if (index === undefined || queue[index] !== q) rebuild = true;
            });
            if (rebuild) {
                aqvRowIndexes.clear();
                aqvQueue = queue;
                const keys = [];
                aqvStore.items = queue.map((q, index) => {
                    const key = aqvRowKey(q);
                    keys.push(key);
                    aqvRowIndexes.set(key, index);
                    return Object.assign({}, q);
                });
                aqvStore.rowKeys = keys;
            } else {
                // 原始队列在 Vue 外更新，替换脏行快照以保留嵌套字段的刷新语义。
                aqvDirtyRows.forEach((q, key) => {
                    aqvStore.items[aqvRowIndexes.get(key)] = Object.assign({}, q);
                });
            }
        }
        aqvDirtyRows.clear(); aqvFullRefresh = false;
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
        currentHtml: '',
        rows: []
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
    entry.store.currentHtml = m.currentHtml || '';
    entry.store.rows = Array.isArray(m.rows) ? m.rows : [];
}

// 计划队列详情组件：四段结构逐字镜像 renderScheduleQueue（head / truncated 提示 /
// empty 行 / round-list 行），行模型由 alt-schedule.js 派生，模板只读字段。
function aqvSchedComponent(entry) {
    return {
        setup() {
            return {store: entry.store, t: aqvT};
        },
        render() {
            const h = aqvVue.h;
            const store = entry.store;
            return [
                h('div', {class: 'ab-round-head'}, [h('span', {class: 'ab-muted'}, store.startedText), h('span', {class: 'ab-muted'}, store.statsText)]),
                store.truncated ? h('p', {class: 'ab-field-note'}, store.truncatedText) : null,
                store.currentHtml ? h('div', {class: 'ab-round-current', innerHTML: store.currentHtml}) : null,
                store.empty ? h('p', {class: 'ab-empty-line'}, store.emptyText) : h('div', {class: 'ab-round-list'}, store.rows.map(row =>
                    h('div', {key: row.key, class: 'ab-flatten', 'data-queue-key': row.key,
                        'data-status': row.status, innerHTML: row.html})
                ))
            ];
        }
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
            aqvDirtyRows.clear(); aqvFullRefresh = false;
            aqvRowIndexes.clear(); aqvQueue = null;
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
