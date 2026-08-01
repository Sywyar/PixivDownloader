'use strict';
/* ============================================================
   alt-filters — 附加筛选（各模式共用）
   匹配语义逐字移植自 pixiv-batch/batch-filters.js（内容分级 / AI / 标签 /
   类型 / 页数 / 字数 / 收藏数）；书签 meta 补齐直接走作品 meta 端点
   （与内置插画 bookmarkCountFetch 同路径）；UI 为右侧抽屉。
   ============================================================ */
function defaultSearchFilters() {
    return {
        content: 'all',
        ai: 'all',
        type: 'all',
        pageMin: null,
        pageMax: null,
        bookmarkMin: null,
        bookmarkMax: null,
        wordsMin: null,
        wordsMax: null,
        tagsExact: [],
        tagsFuzzy: []
    };
}

// 标签输入：逗号（半/全角）分隔，去空白去重；不按空格切分（标签本身可能含空格）
function parseTagTerms(value) {
    if (Array.isArray(value)) {
        value = value.join(',');
    }
    const seen = new Set();
    const out = [];
    String(value || '').split(/[,，]/).forEach(raw => {
        const t = raw.trim();
        if (t && !seen.has(t.toLowerCase())) {
            seen.add(t.toLowerCase());
            out.push(t);
        }
    });
    return out;
}

function parseSearchFilterNumber(value, min) {
    if (value === null || value === undefined) return null;
    const raw = String(value).trim();
    if (!raw) return null;
    const n = Math.floor(Number(raw));
    if (!Number.isFinite(n)) return null;
    return Math.max(min, n);
}

function normalizeSearchFilters(filters) {
    const out = defaultSearchFilters();
    out.content = ['all', 'safe', 'r18plus', 'r18', 'r18g'].includes(filters?.content) ? filters.content : 'all';
    out.ai = ['all', 'exclude', 'only'].includes(filters?.ai) ? filters.ai : 'all';
    out.type = ['all', 'illust', 'manga', 'ugoira'].includes(filters?.type) ? filters.type : 'all';
    out.pageMin = parseSearchFilterNumber(filters?.pageMin, 1);
    out.pageMax = parseSearchFilterNumber(filters?.pageMax, 1);
    out.bookmarkMin = parseSearchFilterNumber(filters?.bookmarkMin, 0);
    out.bookmarkMax = parseSearchFilterNumber(filters?.bookmarkMax, 0);
    out.wordsMin = parseSearchFilterNumber(filters?.wordsMin, 0);
    out.wordsMax = parseSearchFilterNumber(filters?.wordsMax, 0);
    out.tagsExact = parseTagTerms(filters?.tagsExact);
    out.tagsFuzzy = parseTagTerms(filters?.tagsFuzzy);
    if (out.pageMin !== null && out.pageMax !== null && out.pageMin > out.pageMax) {
        [out.pageMin, out.pageMax] = [out.pageMax, out.pageMin];
    }
    if (out.bookmarkMin !== null && out.bookmarkMax !== null && out.bookmarkMin > out.bookmarkMax) {
        [out.bookmarkMin, out.bookmarkMax] = [out.bookmarkMax, out.bookmarkMin];
    }
    if (out.wordsMin !== null && out.wordsMax !== null && out.wordsMin > out.wordsMax) {
        [out.wordsMin, out.wordsMax] = [out.wordsMax, out.wordsMin];
    }
    return out;
}

let extraFilters = defaultSearchFilters();

function saveSearchFilterPrefs(filters) {
    storeSet('pixiv_search_filters', JSON.stringify({
        content: filters.content,
        ai: filters.ai,
        type: filters.type,
        pageMin: filters.pageMin,
        pageMax: filters.pageMax,
        bookmarkMin: filters.bookmarkMin,
        bookmarkMax: filters.bookmarkMax,
        wordsMin: filters.wordsMin,
        wordsMax: filters.wordsMax,
        tagsExact: filters.tagsExact,
        tagsFuzzy: filters.tagsFuzzy
    }));
}

function loadSearchFilterPrefs() {
    try {
        const raw = storeGet('pixiv_search_filters');
        extraFilters = raw ? normalizeSearchFilters(JSON.parse(raw)) : defaultSearchFilters();
    } catch {
        extraFilters = defaultSearchFilters();
    }
}

function hasBookmarkFilter(filters = extraFilters) {
    return filters.bookmarkMin !== null || filters.bookmarkMax !== null;
}

function hasExtraSearchFilter(filters = extraFilters) {
    return filters.content !== 'all'
        || filters.ai !== 'all'
        || filters.type !== 'all'
        || filters.pageMin !== null
        || filters.pageMax !== null
        || filters.wordsMin !== null
        || filters.wordsMax !== null
        || (filters.tagsExact && filters.tagsExact.length > 0)
        || (filters.tagsFuzzy && filters.tagsFuzzy.length > 0)
        || hasBookmarkFilter(filters);
}

// 作品标签词元：兼容字符串数组（搜索 / 画师卡片）与 TagDto 对象数组（小说系列）。
// 对象同时取原名与英文翻译作为可匹配词元（与计划任务后台逐作品筛选语义一致）。
function itemTagTokens(item) {
    const out = [];
    for (const t of (Array.isArray(item.tags) ? item.tags : [])) {
        if (typeof t === 'string') {
            if (t) out.push(t.toLowerCase());
        } else if (t) {
            const name = t.name || t.tag;
            if (name) out.push(String(name).toLowerCase());
            const tr = t.translatedName || t.translation;
            if (tr) out.push(String(tr).toLowerCase());
        }
    }
    return out;
}

// 标签筛选：逗号分隔多标签全部命中(AND)；精确=与某个作品标签完全相等，
// 模糊=被某个作品标签包含；两框都填则两者都需满足(AND)。大小写不敏感。
function matchTagFilters(item, filters) {
    const exact = filters.tagsExact || [];
    const fuzzy = filters.tagsFuzzy || [];
    if (!exact.length && !fuzzy.length) return true;
    const tags = itemTagTokens(item);
    for (const term of exact) {
        const t = term.toLowerCase();
        if (!tags.some(x => x === t)) return false;
    }
    for (const term of fuzzy) {
        const t = term.toLowerCase();
        if (!tags.some(x => x.includes(t))) return false;
    }
    return true;
}

// 内容分级匹配：all=不限 / safe=仅全年龄 / r18plus=R-18+R-18G / r18=仅 R-18 / r18g=仅 R-18G。
function matchContentRating(xRestrict, content) {
    const xr = Number(xRestrict ?? 0);
    switch (content) {
        case 'safe': return xr === 0;
        case 'r18plus': return xr >= 1;
        case 'r18': return xr === 1;
        case 'r18g': return xr === 2;
        default: return true; // all
    }
}

// 类型专属字段：插画=作品类型(illust/manga/ugoira)+页数；小说=字数。
function matchTypeExtraFilters(item, filters, kind) {
    const k = kind || item.kind || 'illust';
    if (k === 'novel') {
        const words = Number(item.wordCount ?? item.textLength ?? 0);
        if (filters.wordsMin !== null && words < filters.wordsMin) return false;
        if (filters.wordsMax !== null && words > filters.wordsMax) return false;
        return true;
    }
    const illustType = Number(item.illustType ?? 0);
    if (filters.type === 'illust' && illustType !== 0) return false;
    if (filters.type === 'manga' && illustType !== 1) return false;
    if (filters.type === 'ugoira' && illustType !== 2) return false;
    const pageCount = Number(item.pageCount ?? 0);
    if (filters.pageMin !== null && pageCount < filters.pageMin) return false;
    if (filters.pageMax !== null && pageCount > filters.pageMax) return false;
    return true;
}

function getInlineSearchBookmarkCount(item) {
    const count = Number(item?.bookmarkCount);
    return Number.isFinite(count) && count >= 0 ? count : null;
}

function getSearchBookmarkCount(item, kind) {
    const cached = searchState.metaCache[(kind || 'illust') + ':' + String(item.id)];
    if (cached && cached.bookmarkResolved) {
        const count = Number(cached.bookmarkCount);
        if (Number.isFinite(count) && count >= 0) return count;
    }
    return getInlineSearchBookmarkCount(item);
}

// 按需补齐逐作品收藏数 meta（收藏数筛选用）。与内置插画 bookmarkCountFetch 同路径。
async function ensureBookmarkMeta(items, kind, isStale) {
    const missingIds = [];
    const seen = new Set();
    for (const item of items) {
        const id = String(item.id);
        if (seen.has(id)) continue;
        seen.add(id);
        if (getInlineSearchBookmarkCount(item) !== null) continue;
        const cached = searchState.metaCache[(kind || 'illust') + ':' + id];
        if (!cached || !cached.bookmarkResolved) missingIds.push(id);
    }
    if (!missingIds.length) return;

    let cursor = 0;
    const workers = [];
    const workerCount = Math.min(6, missingIds.length);
    for (let i = 0; i < workerCount; i++) {
        workers.push((async () => {
            while (cursor < missingIds.length) {
                if (isStale()) return;
                const id = missingIds[cursor++];
                const cacheKey = (kind || 'illust') + ':' + id;
                try {
                    const meta = await apiGet(`/api/pixiv/artwork/${encodeURIComponent(id)}/meta`);
                    if (isStale()) return;
                    searchState.metaCache[cacheKey] = {
                        ...(searchState.metaCache[cacheKey] || {}),
                        bookmarkCount: Number(meta?.bookmarkCount ?? -1),
                        bookmarkResolved: true
                    };
                } catch {
                    if (isStale()) return;
                    searchState.metaCache[cacheKey] = {
                        ...(searchState.metaCache[cacheKey] || {}),
                        bookmarkCount: -1,
                        bookmarkResolved: true,
                        bookmarkError: true
                    };
                }
            }
        })());
    }
    await Promise.all(workers);
}

function matchSearchFilters(item, filters, stats, kind) {
    if (!matchContentRating(item.xRestrict, filters.content)) return false;

    const aiType = Number(item.aiType ?? (item.isAi ? 2 : 0));
    if (filters.ai === 'exclude' && aiType >= 2) return false;
    if (filters.ai === 'only' && aiType < 2) return false;

    if (!matchTagFilters(item, filters)) return false;

    if (!matchTypeExtraFilters(item, filters, kind)) return false;

    if (hasBookmarkFilter(filters)) {
        const bookmarkCount = getSearchBookmarkCount(item, kind);
        if (bookmarkCount === null) {
            stats.bookmarkMetaMissing++;
            return false;
        }
        if (filters.bookmarkMin !== null && bookmarkCount < filters.bookmarkMin) return false;
        if (filters.bookmarkMax !== null && bookmarkCount > filters.bookmarkMax) return false;
    }

    return true;
}

// 通用「逐作品附加筛选」：对任意作品数组应用同一套 matchSearchFilters。
async function computeFilteredItems(items, filters, kind, isStale) {
    const source = Array.isArray(items) ? items : [];
    const bookmarkFilterActive = hasBookmarkFilter(filters);
    if (bookmarkFilterActive) {
        await ensureBookmarkMeta(source, kind, isStale);
        if (isStale()) return null;
    }
    const stats = {
        rawCount: source.length,
        filteredCount: 0,
        bookmarkMetaMissing: 0,
        bookmarkFilterActive
    };
    const filtered = source.filter(item => matchSearchFilters(item, filters, stats, kind));
    stats.filteredCount = filtered.length;
    return {filtered, stats};
}

// 实际下载时的「附加筛选」判定：拉到作品 meta 后调用，返回 null=通过、否则返回本地化的跳过原因。
function evaluateDownloadFilterSkip(meta, kind) {
    const filters = normalizeSearchFilters(extraFilters);
    const xr = Number(meta.xRestrict ?? meta.xrestrict ?? 0);
    if (!matchContentRating(xr, filters.content)) {
        return bt('queue.message.skipped-filter-content', '跳过 — 内容分级不符（要求 {label}）',
            {label: bt('filters.content.' + filters.content, filters.content)});
    }
    const isAi = meta?.isAi === true || Number(meta?.aiType ?? 0) >= 2;
    if (filters.ai === 'exclude' && isAi) {
        return bt('queue.message.skipped-filter-ai-exclude', '跳过 — AI 作品（附加筛选已设为排除 AI）');
    }
    if (filters.ai === 'only' && !isAi) {
        return bt('queue.message.skipped-filter-ai-only', '跳过 — 非 AI 作品（附加筛选已设为仅 AI）');
    }
    if (!matchTagFilters({tags: Array.isArray(meta.tags) ? meta.tags : []}, filters)) {
        return bt('queue.message.skipped-filter-tags', '跳过 — 标签不匹配附加筛选');
    }
    const typeSkip = evaluateTypeExtraSkip(meta, filters, kind);
    if (typeSkip) return typeSkip;
    if (hasBookmarkFilter(filters)) {
        const bc = Number(meta.bookmarkCount ?? -1);
        if (!Number.isFinite(bc) || bc < 0) {
            return bt('queue.message.skipped-filter-bookmarks-unavailable', '跳过 — 收藏数不可用（无法按附加筛选判定）');
        }
        if (filters.bookmarkMin !== null && bc < filters.bookmarkMin) return bt('queue.message.skipped-filter-bookmarks', '跳过 — 收藏数不符附加筛选');
        if (filters.bookmarkMax !== null && bc > filters.bookmarkMax) return bt('queue.message.skipped-filter-bookmarks', '跳过 — 收藏数不符附加筛选');
    }
    return null;
}

function evaluateTypeExtraSkip(meta, filters, kind) {
    if ((kind || 'illust') === 'novel') {
        const words = Number(meta.wordCount ?? meta.textLength ?? 0);
        if (filters.wordsMin !== null && words < filters.wordsMin) return bt('queue.message.skipped-filter-words', '跳过 — 字数不符附加筛选');
        if (filters.wordsMax !== null && words > filters.wordsMax) return bt('queue.message.skipped-filter-words', '跳过 — 字数不符附加筛选');
        return null;
    }
    const illustType = Number(meta.illustType ?? 0);
    if ((filters.type === 'illust' && illustType !== 0)
        || (filters.type === 'manga' && illustType !== 1)
        || (filters.type === 'ugoira' && illustType !== 2)) {
        return bt('queue.message.skipped-filter-type', '跳过 — 作品类型不符附加筛选');
    }
    const pageCount = Number(meta.pageCount ?? 0);
    if (pageCount > 0) {
        if (filters.pageMin !== null && pageCount < filters.pageMin) return bt('queue.message.skipped-filter-pages', '跳过 — 页数不符附加筛选');
        if (filters.pageMax !== null && pageCount > filters.pageMax) return bt('queue.message.skipped-filter-pages', '跳过 — 页数不符附加筛选');
    }
    return null;
}

/* ============================================================
   筛选抽屉 UI
   ============================================================ */
function filterSegment(name, options, current) {
    const wrap = el('div', 'ab-seg');
    options.forEach(opt => {
        const btn = el('button', 'ab-seg-item' + (current === opt.value ? ' is-active' : ''));
        btn.type = 'button';
        btn.textContent = opt.label;
        btn.dataset.value = opt.value;
        btn.addEventListener('click', () => {
            wrap.querySelectorAll('.ab-seg-item').forEach(b => b.classList.remove('is-active'));
            btn.classList.add('is-active');
        });
        wrap.appendChild(btn);
    });
    wrap.dataset.filterField = name;
    return wrap;
}

function filterNumberField(labelText, minKey, maxKey, minVal, maxVal) {
    const group = el('div', 'ab-field');
    const label = el('label', 'ab-field-label', labelText);
    const row = el('div', 'ab-range-row');
    const minInput = el('input', 'ab-input');
    minInput.type = 'number';
    minInput.min = '0';
    minInput.placeholder = bt('filters.range.min', '最少');
    minInput.value = minVal ?? '';
    minInput.dataset.filterField = minKey;
    const dash = el('span', 'ab-range-dash', '—');
    const maxInput = el('input', 'ab-input');
    maxInput.type = 'number';
    maxInput.min = '0';
    maxInput.placeholder = bt('filters.range.max', '最多');
    maxInput.value = maxVal ?? '';
    maxInput.dataset.filterField = maxKey;
    row.appendChild(minInput);
    row.appendChild(dash);
    row.appendChild(maxInput);
    group.appendChild(label);
    group.appendChild(row);
    return group;
}

function filterTextField(labelText, key, value, placeholder) {
    const group = el('div', 'ab-field');
    group.appendChild(el('label', 'ab-field-label', labelText));
    const input = el('input', 'ab-input');
    input.type = 'text';
    input.value = (value || []).join(', ');
    input.placeholder = placeholder;
    input.dataset.filterField = key;
    group.appendChild(input);
    return group;
}

function readFiltersDrawer(body) {
    const raw = {};
    body.querySelectorAll('[data-filter-field]').forEach(node => {
        const key = node.dataset.filterField;
        if (node.classList.contains('ab-seg')) {
            const active = node.querySelector('.ab-seg-item.is-active');
            raw[key] = active ? active.dataset.value : 'all';
        } else {
            raw[key] = node.value;
        }
    });
    return normalizeSearchFilters({
        content: raw.content,
        ai: raw.ai,
        type: raw.type,
        pageMin: raw.pageMin,
        pageMax: raw.pageMax,
        bookmarkMin: raw.bookmarkMin,
        bookmarkMax: raw.bookmarkMax,
        wordsMin: raw.wordsMin,
        wordsMax: raw.wordsMax,
        tagsExact: raw.tagsExact || '',
        tagsFuzzy: raw.tagsFuzzy || ''
    });
}

function buildFiltersDrawerBody() {
    const f = extraFilters;
    const body = el('div', 'ab-filters');

    body.appendChild(el('div', 'ab-field-label', bt('filters.content.title', '内容分级')));
    body.appendChild(filterSegment('content', [
        {value: 'all', label: bt('filters.content.all', '全部')},
        {value: 'safe', label: bt('filters.content.safe', '全年龄')},
        {value: 'r18plus', label: bt('filters.content.r18plus', 'R18+')},
        {value: 'r18', label: 'R-18'},
        {value: 'r18g', label: 'R-18G'}
    ], f.content));

    body.appendChild(el('div', 'ab-field-label', bt('filters.ai.title', 'AI 作品')));
    body.appendChild(filterSegment('ai', [
        {value: 'all', label: bt('filters.ai.all', '全部')},
        {value: 'exclude', label: bt('filters.ai.exclude', '排除 AI')},
        {value: 'only', label: bt('filters.ai.only', '仅 AI')}
    ], f.ai));

    body.appendChild(el('div', 'ab-field-label', bt('filters.type.title', '作品类型')));
    body.appendChild(filterSegment('type', [
        {value: 'all', label: bt('filters.type.all', '全部')},
        {value: 'illust', label: bt('filters.type.illust', '插画')},
        {value: 'manga', label: bt('filters.type.manga', '漫画')},
        {value: 'ugoira', label: bt('filters.type.ugoira', '动图')}
    ], f.type));

    body.appendChild(filterNumberField(bt('filters.pages', '页数'), 'pageMin', 'pageMax', f.pageMin, f.pageMax));
    body.appendChild(filterNumberField(bt('filters.bookmarks', '收藏数'), 'bookmarkMin', 'bookmarkMax', f.bookmarkMin, f.bookmarkMax));
    body.appendChild(filterNumberField(bt('filters.words', '字数（小说）'), 'wordsMin', 'wordsMax', f.wordsMin, f.wordsMax));
    body.appendChild(filterTextField(bt('filters.tags-exact', '标签精确匹配'), 'tagsExact', f.tagsExact,
        bt('filters.tags.placeholder', '逗号分隔，全部命中')));
    body.appendChild(filterTextField(bt('filters.tags-fuzzy', '标签模糊匹配'), 'tagsFuzzy', f.tagsFuzzy,
        bt('filters.tags.placeholder', '逗号分隔，全部命中')));

    const note = el('p', 'ab-field-note',
        bt('filters.note', '预览时实时过滤当前页；实际下载时符合条件的作品才会下载，不符合的会被跳过并显示原因。'));
    body.appendChild(note);
    return body;
}

function openFiltersDrawer() {
    const body = buildFiltersDrawerBody();
    const footer = el('div', 'ab-drawer-actions');
    const resetBtn = el('button', 'ab-btn ab-btn--ghost', bt('common.reset', '重置'));
    resetBtn.type = 'button';
    resetBtn.addEventListener('click', async () => {
        extraFilters = defaultSearchFilters();
        saveSearchFilterPrefs(extraFilters);
        closeDrawer();
        await applyFiltersToCurrentMode();
        syncFilterButtonBadge();
    });
    const applyBtn = el('button', 'ab-btn ab-btn--primary', bt('common.apply', '应用筛选'));
    applyBtn.type = 'button';
    applyBtn.addEventListener('click', async () => {
        extraFilters = readFiltersDrawer(body);
        saveSearchFilterPrefs(extraFilters);
        closeDrawer();
        await applyFiltersToCurrentMode();
        syncFilterButtonBadge();
    });
    footer.appendChild(resetBtn);
    footer.appendChild(applyBtn);
    openDrawer({
        id: 'filters',
        icon: 'filter',
        title: bt('filters.title', '附加筛选'),
        body,
        footer
    });
}

function activeFilterCount() {
    const f = extraFilters;
    let n = 0;
    if (f.content !== 'all') n++;
    if (f.ai !== 'all') n++;
    if (f.type !== 'all') n++;
    if (f.pageMin !== null || f.pageMax !== null) n++;
    if (f.bookmarkMin !== null || f.bookmarkMax !== null) n++;
    if (f.wordsMin !== null || f.wordsMax !== null) n++;
    if (f.tagsExact.length) n++;
    if (f.tagsFuzzy.length) n++;
    return n;
}

function syncFilterButtonBadge() {
    document.querySelectorAll('[data-filter-badge]').forEach(badge => {
        const count = activeFilterCount();
        badge.textContent = String(count);
        badge.hidden = count === 0;
    });
}

window.PixivBatchAlt.filters = Object.assign(window.PixivBatchAlt.filters, {
    defaultSearchFilters, normalizeSearchFilters, parseTagTerms,
    hasBookmarkFilter, hasExtraSearchFilter, matchSearchFilters,
    matchContentRating, computeFilteredItems, evaluateDownloadFilterSkip,
    loadSearchFilterPrefs, saveSearchFilterPrefs, openFiltersDrawer,
    activeFilterCount, syncFilterButtonBadge
});
