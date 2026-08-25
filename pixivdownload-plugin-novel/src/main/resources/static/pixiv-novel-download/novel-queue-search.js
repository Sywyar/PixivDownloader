'use strict';
(function () {
if (!window.PixivBatch || !window.PixivBatch.queueTypes) return;
window.PixivBatch.queueTypes.registerSubmodule(function (shared) {
let _novelSearchVue = null;          // { app, vm, state, root, area } 或 null（未挂载）
let _novelSearchLatestModel = null;  // 供在途异步挂载读取的最新模型
let _novelSearchMounting = false;    // 防止并发触发多次挂载

// 渲染钩子（descriptor.acquisition.search.render）：优先 Vue reactive 挂载，Vue 不可用即命令式回退。
function renderNovelSearchResults(area, view) {
    view = view || getSearchView();
    // Vue helper 未接线（运行时单一来源未加载）→ 纯命令式渲染（旧行为，逐字不变）。
    if (!window.PixivVue) {
        applyNovelSearchImperative(area, view);
        return;
    }
    const model = buildNovelSearchModel(view);
    _novelSearchLatestModel = model;
    // 已挂载且根节点仍在当前 area 内 → 仅更新 reactive 状态，卡片 / in-queue 由 Vue 自动重渲染。
    if (_novelSearchVue && _novelSearchVue.area === area && area.contains(_novelSearchVue.root)) {
        assignNovelSearchState(_novelSearchVue.state, model);
        return;
    }
    // 尚未挂载，或 area 被宿主清空（空结果 / 切到插画搜索）→ 先命令式即时出图（兼作回退与首屏占位），
    // 再异步挂载 Vue 覆盖（运行时已缓存时挂载发生在同帧微任务内、无可见闪烁）。
    applyNovelSearchImperative(area, view);
    ensureNovelSearchMounted(area);
}

// 异步确保 Vue 组件挂到 area 内的专属根节点（与宿主直接写 area.innerHTML 的命令式路径隔离，
// 便于按 area.contains(root) 探测「被宿主清空」并按需重挂）。挂载失败一律收敛、不打断宿主。
function ensureNovelSearchMounted(area) {
    if (_novelSearchMounting) return;
    _novelSearchMounting = true;
    window.PixivVue.ensure().then(function (Vue) {
        if (!shared.context || !shared.context.isActive()) return;
        // 卸载可能存在的旧 app（area 曾被宿主清空 → 旧挂载已失效，避免悬挂的 vnode 树）。
        if (_novelSearchVue) {
            try { _novelSearchVue.app.unmount(); } catch (e) { /* 卸载失败忽略 */ }
            _novelSearchVue = null;
        }
        const state = Vue.reactive(buildEmptyNovelSearchState());
        assignNovelSearchState(state, _novelSearchLatestModel || buildEmptyNovelSearchState());
        // 先在游离根上 createApp + mount：成功后才替换 area 内容。若 createApp / mount 抛错，
        // 此刻尚未触碰 area，命令式首屏结果（applyNovelSearchImperative 已写入 area）保持完整。
        const root = document.createElement('div');
        root.className = 'novel-search-vue-root';
        const app = Vue.createApp(buildNovelSearchComponent(state));
        const vm = app.mount(root);
        if (!shared.context || !shared.context.isActive()) {
            try { app.unmount(); } catch (e) { /* best effort */ }
            return;
        }
        // 挂载成功，才用 Vue 根替换命令式首屏结果，并登记句柄。
        area.innerHTML = '';
        area.appendChild(root);
        _novelSearchVue = {app: app, vm: vm, state: state, root: root, area: area};
    }).catch(function (e) {
        // Vue 运行时不可用 / createApp / mount 抛错 → 命令式首屏结果原样保留（area 未被清空），
        // _novelSearchVue 置空，绝不向宿主 init 抛异常（优雅降级）。
        _novelSearchVue = null;
        console.warn('[novel-search] Vue 运行时不可用，沿用命令式渲染：', e);
    }).finally(function () {
        _novelSearchMounting = false;
    });
}

// 据当前搜索视图构造 reactive 模型：cards 只存原始数据（显示文案模板期派生）；summary 为本次 render 的
// 瞬时文案数组（与命令式实现同源、同样每次 render 重算）；inQueueIds 为队列 id 集合（驱动 in-queue 高亮）。
function buildNovelSearchModel(view) {
    view = view || getSearchView();
    const inQueue = new Set(state.queue.map(q => q.id));
    const summary = searchState.submode === 'batch'
        ? [batchSummaryText(view)]
        : [
            searchStatText('total', searchState.total),
            bt('search.summary.current-page-index', '当前第 {page} 页', {page: searchState.currentPage}),
            searchStatText('returned', searchState.pixivPageCount)
        ];
    if (searchState.submode !== 'batch' && hasExtraSearchFilter()) {
        summary.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
    }
    const cards = view.items.map((item, i) => ({
        idx: view.base + i,
        id: item.id,
        queueId: 'n' + String(item.id),
        title: item.title || '',
        userName: item.userName || '',
        xr: Number(item.xRestrict ?? 0),
        isAi: Number(item.aiType ?? 0) >= 2,
        isOriginal: !!item.isOriginal,
        wc: Number(item.wordCount ?? item.textLength ?? 0),
        bookmarkCount: getSearchBookmarkCount(item)
    }));
    return {cards: cards, summary: summary, noCookie: !!searchState.noCookie, inQueueIds: Array.from(inQueue)};
}

function buildEmptyNovelSearchState() {
    return {cards: [], summary: [], noCookie: false, inQueueIds: new Set()};
}

// 把模型批量写入 reactive 状态（替换引用即触发重渲染；inQueueIds 用 Set 以 has() 驱动 in-queue 绑定）。
function assignNovelSearchState(target, model) {
    target.cards = model.cards;
    target.summary = model.summary;
    target.noCookie = model.noCookie;
    target.inQueueIds = new Set(model.inQueueIds);
}

// 小说搜索网格 Vue 组件模板：文案全部经 t* 方法在渲染期派生（不 bake 进模型）；in-queue 高亮与 tooltip
// 经 isInQueue() 读 reactive inQueueIds，队列变化时自动更新；点击入队复用宿主 addSearchItemToQueue。
const NOVEL_SEARCH_TEMPLATE = `
<div v-if="state.noCookie" style="font-size:12px;color:var(--warning-text);margin-bottom:8px;">{{ tNoCookie() }}</div>
<div style="font-size:12px;color:var(--muted);margin-bottom:10px;">
  <template v-for="(s, i) in state.summary" :key="i"><span>{{ s }}</span>{{ i < state.summary.length - 1 ? sep() : '' }}</template>
</div>
<div class="novel-search-grid">
  <div v-for="card in state.cards" :key="card.queueId" class="novel-search-card"
       :class="{ 'in-queue': isInQueue(card) }" :data-novel-idx="card.idx"
       :title="cardTitle(card)" @click="onCardClick(card.idx)">
    <div class="nsc-title">{{ displayTitle(card) }}</div>
    <div class="nsc-author">{{ displayAuthor(card) }}</div>
    <div class="nsc-meta">
      <span v-if="card.xr === 1" class="nsc-r18">R-18</span>
      <span v-else-if="card.xr === 2" class="nsc-r18g">R-18G</span>
      <span v-if="card.isAi" class="nsc-ai">AI</span>
      <span v-if="card.isOriginal" class="nsc-original">{{ tOriginal() }}</span>
      <span v-if="card.wc > 0">{{ tWords(card.wc) }}</span>
      <span v-if="card.bookmarkCount !== null">{{ tBookmark(card.bookmarkCount) }}</span>
    </div>
    <span class="nsc-in-queue-mark">✓</span>
  </div>
</div>`;

function buildNovelSearchComponent(reactiveState) {
    return {
        setup() {
            const tNoCookie = () => bt('status.search-no-cookie-warning', '⚠ 未保存 Cookie，搜索结果可能减少');
            const tOriginal = () => bt('novel:batch.search.original', '原创');
            const tWords = (wc) => bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()});
            const tBookmark = (n) => bt('search.summary.bookmark-badge', '收藏 {count}', {count: n.toLocaleString()});
            const displayTitle = (card) => card.title || bt('novel:status.unknown-novel', '小说 {id}', {id: card.id});
            const displayAuthor = (card) => card.userName || bt('novel:status.unknown-author', '未知');
            const isInQueue = (card) => reactiveState.inQueueIds.has(card.queueId);
            const cardTitle = (card) => {
                const bookmarkTip = buildBookmarkTip(card.bookmarkCount);
                const queueTip = buildQueueToggleTip(isInQueue(card));
                return `${displayTitle(card)} (${displayAuthor(card)})${bookmarkTip}${queueTip}`;
            };
            const sep = () => summarySeparator();
            const onCardClick = (idx) => addSearchItemToQueue(Number(idx));
            return {
                state: reactiveState,
                tNoCookie, tOriginal, tWords, tBookmark,
                displayTitle, displayAuthor, isInQueue, cardTitle, sep, onCardClick
            };
        },
        template: NOVEL_SEARCH_TEMPLATE
    };
}

// 命令式回退渲染（旧实现逐字保留）：Vue 不可用 / 加载失败时使用，并兼作 Vue 挂载前的首屏即时占位。
function applyNovelSearchImperative(area, view) {
    view = view || getSearchView();
    const inQueue = new Set(state.queue.map(q => q.id));
    const summary = searchState.submode === 'batch'
        ? [batchSummaryText(view)]
        : [
            searchStatText('total', searchState.total),
            bt('search.summary.current-page-index', '当前第 {page} 页', {page: searchState.currentPage}),
            searchStatText('returned', searchState.pixivPageCount)
        ];
    if (searchState.submode !== 'batch' && hasExtraSearchFilter()) {
        summary.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
    }
    const cards = view.items.map((item, i) => {
        const idx = view.base + i;
        const xr = Number(item.xRestrict ?? 0);
        const isAi = Number(item.aiType ?? 0) >= 2;
        const wc = Number(item.wordCount ?? item.textLength ?? 0);
        const bookmarkCount = getSearchBookmarkCount(item);
        const queueId = 'n' + String(item.id);
        const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
        const meta = [];
        if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
        else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
        if (isAi) meta.push('<span class="nsc-ai">AI</span>');
        if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
        if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
        if (bookmarkCount !== null) {
            meta.push(`<span>${esc(bt('search.summary.bookmark-badge', '收藏 {count}', {count: bookmarkCount.toLocaleString()}))}</span>`);
        }
        const fallbackTitle = bt('novel:status.unknown-novel', '小说 {id}', {id: item.id});
        const fallbackAuthor = bt('novel:status.unknown-author', '未知');
        const bookmarkTip = buildBookmarkTip(bookmarkCount);
        const queueTip = buildQueueToggleTip(inQueue.has(queueId));
        const cardTitle = `${item.title || fallbackTitle} (${item.userName || fallbackAuthor})${bookmarkTip}${queueTip}`;
        return `<div class="novel-search-card${inQueueClass}" data-novel-idx="${idx}" title="${esc(cardTitle)}">
        <div class="nsc-title">${esc(item.title || fallbackTitle)}</div>
        <div class="nsc-author">${esc(item.userName || fallbackAuthor)}</div>
        <div class="nsc-meta">${meta.join('')}</div>
        <span class="nsc-in-queue-mark">✓</span>
      </div>`;
    }).join('');
    area.innerHTML = `
    ${searchState.noCookie ? `<div style="font-size:12px;color:var(--warning-text);margin-bottom:8px;">${esc(bt('status.search-no-cookie-warning', '⚠ 未保存 Cookie，搜索结果可能减少'))}</div>` : ''}
    <div style="font-size:12px;color:var(--muted);margin-bottom:10px;">
      ${summary.map(s => `<span>${esc(s)}</span>`).join(summarySeparator())}
    </div>
    <div class="novel-search-grid">${cards}</div>`;
    area.querySelectorAll('.novel-search-card').forEach(card => {
        card.addEventListener('click', () => addSearchItemToQueue(Number(card.dataset.novelIdx)));
    });
}

// 队列态同步钩子（descriptor.acquisition.search.syncQueueState）：Vue 已挂载时替换 reactive in-queue 集合，
// 卡片高亮 / tooltip 自动跟随（不再手动遍历 DOM）；未挂载（命令式回退）时沿用旧的逐卡 DOM 局部更新。
function syncNovelSearchQueueState(results, inQueue) {
    if (_novelSearchVue && _novelSearchVue.state) {
        _novelSearchVue.state.inQueueIds = new Set(inQueue);
        return;
    }
    results.forEach((item, idx) => {
        const el = document.querySelector(`.novel-search-card[data-novel-idx="${idx}"]`);
        if (!el) return;
        el.classList.toggle('in-queue', inQueue.has('n' + String(item.id)));
    });
}

// —— User 模式：画师小说作品网格 ——

function disposeNovelSearch() {
    _novelSearchLatestModel = null;
    _novelSearchMounting = false;
    if (_novelSearchVue) {
        try { _novelSearchVue.app.unmount(); } catch (e) { /* best effort */ }
        _novelSearchVue = null;
    }
}
Object.assign(shared, {
    renderNovelSearchResults, ensureNovelSearchMounted, buildNovelSearchModel, buildEmptyNovelSearchState,
    assignNovelSearchState, NOVEL_SEARCH_TEMPLATE, buildNovelSearchComponent, applyNovelSearchImperative,
    syncNovelSearchQueueState, disposeNovelSearch
});
});
})();
