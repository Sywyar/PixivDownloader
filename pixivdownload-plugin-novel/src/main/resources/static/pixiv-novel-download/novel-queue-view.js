'use strict';
(function () {
if (!window.PixivBatch || !window.PixivBatch.queueTypes) return;
window.PixivBatch.queueTypes.registerSubmodule(function (shared) {
/* ============================================================
   取得侧 UI 槽位：小说作品类型向下载页贡献的 DOM 片段（kind 单选 / 入口按钮 / 导入提示 /
   专属筛选 / 设置卡）。下载页据 /api/download/extensions 报告的启用情况，把这些片段注入宿主页
   同名 <template data-qt-slot> 锚点（见 batch-queue-types.js 的 renderSlots），作为真实兄弟节点
   就位。小说插件被禁用 → 本模块不加载 → 这些片段不注入 → 下载页对应取得侧入口自然消失。
   片段内的 id / class / data-* / onclick / data-i18n 与下载页既有交互契约一致：宿主在 init 阶段
   据此绑定 kind 单选、读写小说设置、按 .search-novel-only 显隐字数筛选等（这些行为仍在宿主既有模块，
   其按模式 / kind 的显隐裁决待后续细粒度取得侧钩子收口）。
============================================================ */
const NOVEL_SLOTS = {
    // User 模式 kind 单选「小说」选项（插画 / 约稿为宿主内置、留在 HTML）
    'kind-option-user':
        '<label data-kind="novel">' +
        '<input type="radio" name="user-kind" value="novel">' +
        ' <span data-i18n="novel:batch.user.kind-novel">小说</span></label>',
    // Search 模式 kind 单选「小说」选项
    'kind-option-search':
        '<label data-kind="novel">' +
        '<input type="radio" name="search-kind" value="novel">' +
        ' <span data-i18n="novel:batch.search.kind-novel">小说</span></label>',
    // 快捷获取二层预览的 kind 单选「小说」选项（珍藏集 / 用户作品里切换插画 / 小说网格）
    'kind-option-quick':
        '<label data-quick-kind="novel"><input type="radio" name="quick-inner-kind" value="novel">' +
        ' <span data-i18n="quick.preview.kind-novel">小说</span></label>',
    // 快捷获取「我的收藏（小说）」入口按钮（公开 / 不公开）
    'quick-actions-bookmarks':
        '<button class="btn btn-blue quick-action" data-quick="my-novel-bookmarks-show"' +
        ' data-pixiv-click="quickLoad(\'my-novel-bookmarks-show\')" data-i18n="quick.action.novel-bookmarks-show">📚 我的收藏（小说，公开）</button>' +
        '<button class="btn btn-purple quick-action" data-quick="my-novel-bookmarks-hide"' +
        ' data-pixiv-click="quickLoad(\'my-novel-bookmarks-hide\')" data-i18n="quick.action.novel-bookmarks-hide">🔒 我的收藏（小说，不公开）</button>',
    // 快捷获取「我自己的作品（小说）」入口按钮
    'quick-actions-mine':
        '<button class="btn btn-green quick-action" data-quick="my-novels"' +
        ' data-pixiv-click="quickLoad(\'my-novels\')" data-i18n="quick.action.my-novels">✍ 我自己的作品（小说，含 hide）</button>',
    // 批量导入单作品的小说链接示例
    'import-hint':
        '<div><span data-i18n="label.import-novel-example">小说链接示例：</span>' +
        '<span class="import-example-code" data-i18n="label.import-novel-example-value">' +
        'https://www.pixiv.net/novel/show.php?id=12345678 | 示例标题</span></div>',
    // 附加筛选里的小说专属字段（最少 / 最多字数）；保留 .search-novel-only，由宿主 applySearchKindUI 按模式显隐
    'search-filter':
        '<div class="search-extra-item search-novel-only" style="display:none;">' +
        '<label for="search-words-min" data-i18n="novel:batch.search.words-min">最少字数</label>' +
        '<input type="number" id="search-words-min" min="0" step="100" data-i18n-placeholder="search.unlimited" placeholder="不限"' +
        ' data-pixiv-change="handleSearchFilterChange()"></div>' +
        '<div class="search-extra-item search-novel-only" style="display:none;">' +
        '<label for="search-words-max" data-i18n="novel:batch.search.words-max">最多字数</label>' +
        '<input type="number" id="search-words-max" min="0" step="100" data-i18n-placeholder="search.unlimited" placeholder="不限"' +
        ' data-pixiv-change="handleSearchFilterChange()"></div>',
    // 小说设置卡（格式 / 合订）；下载即自动翻译由 AI 插件追加到本卡片内。
    // id / class 与宿主 loadSettings/syncSettings/applyNovelSettingsVisibility 既有契约一致
    'settings-card':
        '<div class="card" id="novel-settings-card">' +
        '<div class="card-title" data-i18n="card.novel-settings">小说设置</div>' +
        '<div class="settings-grid">' +
        '<div class="setting-item">' +
        '<label for="s-novel-format" data-i18n="novel:batch.format-label">小说格式:</label>' +
        '<select id="s-novel-format" style="padding:4px 6px;border:1px solid var(--line);border-radius:4px;font-size:13px;">' +
        '<option value="txt" data-i18n="novel:format.txt">纯文本（TXT）</option>' +
        '<option value="html" data-i18n="novel:format.html">网页（HTML）</option>' +
        '<option value="epub" data-i18n="novel:format.epub">电子书（EPUB）</option>' +
        '</select></div>' +
        '<div class="setting-item">' +
        '<input type="checkbox" id="s-novel-merge">' +
        '<label for="s-novel-merge" style="cursor:pointer;" data-i18n="novel:batch.merge-label">系列下载完成后生成合订本</label>' +
        '</div>' +
        '<div class="setting-item" id="s-novel-merge-format-row">' +
        '<label for="s-novel-merge-format" data-i18n="novel:batch.merge-format-label">合订本格式:</label>' +
        '<select id="s-novel-merge-format" style="padding:4px 6px;border:1px solid var(--line);border-radius:4px;font-size:13px;">' +
        '<option value="epub" data-i18n="novel:batch.merge-format-epub">电子书（EPUB，推荐）</option>' +
        '<option value="txt" data-i18n="novel:batch.merge-format-txt">纯文本（TXT）</option>' +
        '<option value="html" data-i18n="novel:batch.merge-format-html">网页（HTML）</option>' +
        '</select></div>' +
        '<div class="setting-item" id="s-novel-merge-format-hint"' +
        ' style="grid-column:1/-1;font-size:12px;color:var(--muted);line-height:1.5;"' +
        ' data-i18n="novel:batch.merge-format-hint">推荐 EPUB：内嵌封面与插图、按「小说 → 章节」生成可跳转的多级目录、带书名/作者/简介等信息可在阅读器书架显示；TXT/HTML 为无插图的纯文本 / 单页备选。</div>' +
        '</div></div>'
};

/* ============================================================
   取得侧行为：小说作品类型向下载页各取得模式（user / search / series / quick）+ 批量导入 + 附加筛选
   贡献的抓取 / 渲染 / 队列 meta / 专属筛选逻辑。下载页宿主（modes/*.js、batch-filters.js、
   single-import.js）只面向 queueTypes 的 acquisition / import / filters 钩子调用，自身不再按
   作品类型字面量分流；小说插件被禁用 → 本模块不加载 → 这些钩子缺席 → 宿主回退插画内置路径、不发起
   小说专属请求。以下函数 / 卡片渲染均运行在下载页全局作用域，复用宿主既有工具函数（esc/bt/state/
   addSearchItemToQueue/addUserItemToQueue/addSeriesItemToQueue/quickInnerToggleQueue/getSearchBookmarkCount/
   getSeriesFallbackOrder/loadQuickMyWorks/quickRenderOuterWorks/renderQuickPagination 等）。
============================================================ */


function renderNovelUserResults(area, ctx) {
    const inQueue = ctx.inQueue;
    const cards = ctx.items.map((item, idx) => {
        const xr = Number(item.xRestrict ?? 0);
        const isAi = Number(item.aiType ?? 0) >= 2;
        const wc = Number(item.wordCount ?? item.textLength ?? 0);
        const bookmarkCount = getSearchBookmarkCount(item, 'novel');
        const queueId = 'n' + String(item.id);
        const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
        const meta = [];
        if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
        else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
        if (isAi) meta.push('<span class="nsc-ai">AI</span>');
        if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
        if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
        if (bookmarkCount !== null) meta.push(`<span>${esc(bt('search.summary.bookmark-badge', '收藏 {count}', {count: bookmarkCount.toLocaleString()}))}</span>`);
        const fallbackTitle = bt('queue.novel-fallback', '小说 {id}', {id: item.id});
        const fallbackAuthor = ctx.username || bt('novel:status.unknown-author', '未知');
        const bookmarkTip = buildBookmarkTip(bookmarkCount);
        const queueTip = buildQueueToggleTip(inQueue.has(queueId));
        const cardTitle = `${item.title || fallbackTitle} (${item.userName || fallbackAuthor})${bookmarkTip}${queueTip}`;
        return `<div class="novel-search-card${inQueueClass}" data-user-novel-idx="${idx}" id="user-novel-card-${idx}" title="${esc(cardTitle)}">
        <div class="nsc-title">${esc(item.title || fallbackTitle)}</div>
        <div class="nsc-author">${esc(item.userName || fallbackAuthor)}</div>
        <div class="nsc-meta">${meta.join('')}</div>
        <span class="nsc-in-queue-mark">✓</span>
      </div>`;
    }).join('');
    area.innerHTML = ctx.summaryHtml + `<div class="novel-search-grid">${cards}</div>`;
    area.querySelectorAll('.novel-search-card').forEach(card => {
        card.addEventListener('click', () => addUserItemToQueue(Number(card.dataset.userNovelIdx)));
    });
}

// —— Series 模式：小说系列卡片列表 + 封面 + 专属 meta 文案 ——
function renderSeriesNovelTags(tags) {
    if (!Array.isArray(tags) || tags.length === 0) {
        return `<span>${esc(bt('series.tags-empty', '无标签'))}</span>`;
    }
    return tags.slice(0, 8).map(tag => {
        const name = typeof tag === 'string' ? tag : (tag.name || tag.tag || '');
        const translated = typeof tag === 'object' && tag ? (tag.translatedName || tag.translation || '') : '';
        const label = translated ? `${name} / ${translated}` : name;
        return label ? `<span>${esc(label)}</span>` : '';
    }).join('');
}

function formatNovelUploadDate(timestamp) {
    const value = Number(timestamp || 0);
    if (!Number.isFinite(value) || value <= 0) return '';
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return '';
    const text = d.toLocaleDateString(uiLang(), {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit'
    });
    return bt('series.meta.upload', '上传：{time}', {time: text});
}

function formatNovelReadingTime(seconds) {
    const text = formatNovelReadingDuration(seconds);
    return text ? bt('series.meta.reading-time', '预计阅读：{time}', {time: text}) : '';
}

function formatNovelReadingDuration(seconds) {
    const total = Math.floor(Number(seconds || 0));
    if (!Number.isFinite(total) || total <= 0) return '';
    const hour = Math.floor(total / 3600);
    const minute = Math.floor((total % 3600) / 60);
    const second = total % 60;
    const parts = [];
    if (hour > 0) parts.push(bt('duration.hour', '{count} 小时', {count: hour}));
    if (minute > 0) parts.push(bt('duration.minute', '{count} 分钟', {count: minute}));
    if (second > 0 && hour === 0) parts.push(bt('duration.second', '{count} 秒', {count: second}));
    if (!parts.length) parts.push(bt('duration.second', '{count} 秒', {count: total}));
    return parts.join(' ');
}

async function loadNovelSeriesCoversBatched(items, renderToken) {
    const BATCH = 10;
    for (let i = 0; i < items.length; i += BATCH) {
        if (renderToken !== seriesState.renderToken) return;
        const batch = items.slice(i, i + BATCH);
        await Promise.allSettled(batch.map((item, offset) => loadSingleNovelSeriesCover(item, i + offset, renderToken)));
    }
}

async function loadSingleNovelSeriesCover(item, idx, renderToken) {
    if (!item.coverUrl) return;
    const coverEl = document.getElementById(`series-novel-cover-${idx}`);
    if (!coverEl) return;
    try {
        const params = new URLSearchParams({url: item.coverUrl});
        const res = await fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()});
        if (!res.ok) return;
        const blob = await res.blob();
        if (renderToken !== seriesState.renderToken) return;
        const blobUrl = URL.createObjectURL(blob);
        seriesState.activeBlobUrls.push(blobUrl);
        if (coverEl.isConnected) {
            coverEl.innerHTML = `<img src="${blobUrl}" alt="${esc(item.title || '')}">`;
        }
    } catch {
    }
}

function renderNovelSeriesResults(area, ctx) {
    const inQueue = ctx.inQueue;
    area.innerHTML = `
    <div class="novel-series-list">
      ${ctx.items.map((item, idx) => {
        const xr = Number(item.xRestrict ?? 0);
        const isAi = Number(item.aiType ?? 0) >= 2;
        const wc = Number(item.wordCount ?? item.textLength ?? 0);
        const seriesOrder = Number(item.seriesOrder || getSeriesFallbackOrder(idx));
        const queueId = 'n' + String(item.id);
        const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
        const meta = [];
        if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
        else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
        if (isAi) meta.push('<span class="nsc-ai">AI</span>');
        const uploadText = formatNovelUploadDate(item.uploadTimestamp);
        if (uploadText) meta.push(`<span>${esc(uploadText)}</span>`);
        if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
        const readingText = formatNovelReadingTime(item.readingTimeSeconds);
        if (readingText) meta.push(`<span>${esc(readingText)}</span>`);
        const fallbackTitle = bt('queue.novel-fallback', '小说 {id}', {id: item.id});
        const fallbackAuthor = bt('novel:status.unknown-author', '未知');
        const queueTip = buildQueueToggleTip(inQueue.has(queueId));
        const tagsHtml = renderSeriesNovelTags(item.tags || []);
        return `<div class="novel-series-card${inQueueClass}" id="series-novel-card-${idx}"
                         data-pixiv-click="addSeriesItemToQueue(${idx})" title="#${seriesOrder} ${esc(item.title || fallbackTitle)} (${esc(item.userName || fallbackAuthor)})${queueTip}">
          <div class="novel-series-cover" id="series-novel-cover-${idx}"><span>${esc(bt('series.cover-placeholder', '封面'))}</span></div>
          <div class="novel-series-info">
            <div class="novel-series-title">#${seriesOrder} ${esc(item.title || fallbackTitle)}</div>
            <div class="nsc-author">${esc(item.userName || fallbackAuthor)}</div>
            <div class="novel-series-tags">${tagsHtml}</div>
            <div class="novel-series-meta">${meta.join('')}</div>
          </div>
          <span class="nsc-in-queue-mark">✓</span>
        </div>`;
    }).join('')}
    </div>`;
    loadNovelSeriesCoversBatched(ctx.items, ctx.renderToken);
}

// —— Quick 模式：小说收藏入口加载 + 小说网格 + 混合珍藏集里的小说卡 ——
async function loadQuickNovelBookmarks(rest, page) {
    const offset = (page - 1) * QUICK_PAGE_SIZE_NOVEL;
    const params = new URLSearchParams({rest, offset: String(offset), limit: String(QUICK_PAGE_SIZE_NOVEL)});
    const data = await quickFetchJson(`${BASE}/api/pixiv/me/novel-bookmarks?${params}`);
    quickState.rawItems = data.items || [];
    quickState.total = data.total || 0;
    quickState.offset = offset;
    quickState.page = page;
    const titleKey = rest === 'hide' ? 'quick.title.novel-bookmarks-hide' : 'quick.title.novel-bookmarks-show';
    const titleFallback = rest === 'hide' ? '我的收藏（小说，不公开）' : '我的收藏（小说，公开）';
    quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: quickState.total.toLocaleString()})}`);
    quickShowToolbar({showBack: false, showAdd: quickState.rawItems.length > 0, showSearch: false, showKindSwitcher: false});
    await quickRenderOuterWorks();
    renderQuickPagination(page, Math.max(1, Math.ceil(quickState.total / QUICK_PAGE_SIZE_NOVEL)),
        p => loadQuickNovelBookmarks(rest, p));
    updateExtraFiltersCardVisibility();
    updateSaveScheduleCardVisibility();
    applyNovelSettingsVisibility();
}

function renderQuickNovelGrid(items, idPrefix, summaryHtml = '') {
    const area = document.getElementById('quick-preview-area');
    if (!area) return;
    if (!items.length) {
        const emptyMsg = summaryHtml
            ? bt('status.search-no-filtered-results', '附加筛选后无结果')
            : bt('quick.empty.no-items', '该范围内没有作品');
        area.innerHTML = summaryHtml + `<div class="quick-empty">${esc(emptyMsg)}</div>`;
        return;
    }
    const inQueue = new Set(state.queue.map(q => q.id));
    area.innerHTML = summaryHtml + `<div class="novel-search-grid">${items.map((item, idx) => {
        const xr = Number(item.xRestrict ?? 0);
        const isAi = Number(item.aiType ?? 0) >= 2;
        const wc = Number(item.wordCount ?? item.textLength ?? 0);
        const queueId = 'n' + String(item.id);
        const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
        const meta = [];
        if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
        else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
        if (isAi) meta.push('<span class="nsc-ai">AI</span>');
        if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
        if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
        const fallbackTitle = bt('queue.novel-fallback', '小说 {id}', {id: item.id});
        const title = item.title || fallbackTitle;
        return `<div class="novel-search-card${inQueueClass}" id="${idPrefix}-novel-card-${idx}"
                     data-pixiv-click="quickToggleItemQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
          <div class="nsc-title">${esc(title)}</div>
          <div class="nsc-author">${esc(item.userName || '')}</div>
          <div class="nsc-meta">${meta.join('')}</div>
          <span class="nsc-in-queue-mark">✓</span>
        </div>`;
    }).join('')}</div>`;
}

function novelQuickInnerCard(item, idx, inQueue) {
    const title = item.title || bt('queue.novel-fallback', '小说 {id}', {id: item.id});
    const xr = Number(item.xRestrict ?? 0);
    const isAi = Number(item.aiType ?? 0) >= 2;
    const wc = Number(item.wordCount ?? item.textLength ?? 0);
    const inQueueClass = inQueue.has('n' + String(item.id)) ? ' in-queue' : '';
    const meta = [];
    if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
    else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
    if (isAi) meta.push('<span class="nsc-ai">AI</span>');
    if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
    if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
    return `<div class="novel-search-card${inQueueClass}" id="quick-inner-card-${idx}"
                         data-pixiv-click="quickInnerToggleQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
              <div class="nsc-title">${esc(title)}</div>
              <div class="nsc-author">${esc(item.userName || '')}</div>
              <div class="nsc-meta">${meta.join('')}</div>
              <span class="nsc-in-queue-mark">✓</span>
            </div>`;
}

// 小说作品类型 descriptor：下载行为（process）+ 取得侧 UI 槽位（slots）+ 取得侧行为钩子
// （acquisition：user/search/series/quick）+ 批量导入解析（import）+ 附加筛选（filters）+ 设置卡（settings）。
Object.assign(shared, {
    NOVEL_SLOTS, renderNovelUserResults, renderSeriesNovelTags, formatNovelUploadDate,
    formatNovelReadingTime, formatNovelReadingDuration, loadNovelSeriesCoversBatched, loadSingleNovelSeriesCover,
    renderNovelSeriesResults, loadQuickNovelBookmarks, renderQuickNovelGrid, novelQuickInnerCard
});
});
})();
