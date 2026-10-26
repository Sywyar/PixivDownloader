'use strict';
/* ============================================================
   alt-filters — 附加筛选（各模式共用）
   类型专属筛选与收藏数查询由 queueTypes 的 filters 贡献提供。
   ============================================================ */
let extraFilters = defaultSearchFilters();

function saveSearchFilterPrefs(filters) {
    if (scheduleState.editing) return;
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

function matchTypeExtraFilters(item, filters, kind) {
    const k = kind || item.kind || 'illust';
    const contribution = window.PixivBatch.queueTypes.filtersFor(k);
    if (typeof contribution?.matchExtra === 'function') return contribution.matchExtra(
        item.__acquisitionId == null ? item : {...item, id: item.__acquisitionId}, filters);
    const illustType = Number(item.illustType ?? 0);
    if (filters.type === 'illust' && illustType !== 0) return false;
    if (filters.type === 'manga' && illustType !== 1) return false;
    if (filters.type === 'ugoira' && illustType !== 2) return false;
    const pageCount = Number(item.pageCount ?? 0);
    if (filters.pageMin !== null && pageCount < filters.pageMin) return false;
    if (filters.pageMax !== null && pageCount > filters.pageMax) return false;
    return true;
}

function getSearchBookmarkCount(item, kind) {
    const cached = getCachedSearchMeta(item.__acquisitionId ?? item.id, kind || 'illust');
    if (cached && cached.bookmarkResolved) {
        const count = Number(cached.bookmarkCount);
        if (Number.isFinite(count) && count >= 0) return count;
    }
    return getInlineSearchBookmarkCount(item);
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
        if (!await ensureBookmarkMeta(source, kind, isStale) || isStale()) return null;
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
    const filters = normalizeSearchFilters(scheduleState.editing?.original.filters || extraFilters);
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
    const contribution = window.PixivBatch.queueTypes.filtersFor(kind || 'illust');
    if (typeof contribution?.evaluateSkip === 'function') return contribution.evaluateSkip(meta, filters);
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
        btn.setAttribute('aria-pressed', String(current === opt.value));
        btn.addEventListener('click', () => {
            wrap.querySelectorAll('.ab-seg-item').forEach(b => {
                b.classList.remove('is-active');
                b.setAttribute('aria-pressed', 'false');
            });
            btn.classList.add('is-active');
            btn.setAttribute('aria-pressed', 'true');
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

    const illustFields = el('div', 'search-illust-only');
    illustFields.hidden = true;
    illustFields.appendChild(el('div', 'ab-field-label', bt('filters.type.title', '作品类型')));
    illustFields.appendChild(filterSegment('type', [
        {value: 'all', label: bt('filters.type.all', '全部')},
        {value: 'illust', label: bt('filters.type.illust', '插画')},
        {value: 'manga', label: bt('filters.type.manga', '漫画')},
        {value: 'ugoira', label: bt('filters.type.ugoira', '动图')}
    ], f.type));

    illustFields.appendChild(filterNumberField(bt('filters.pages', '页数'), 'pageMin', 'pageMax', f.pageMin, f.pageMax));
    body.appendChild(illustFields);
    body.appendChild(filterNumberField(bt('filters.bookmarks', '收藏数'), 'bookmarkMin', 'bookmarkMax', f.bookmarkMin, f.bookmarkMax));
    const novelFields = filterNumberField(bt('filters.words', '字数（小说）'), 'wordsMin', 'wordsMax', f.wordsMin, f.wordsMax);
    novelFields.classList.add('search-novel-only');
    novelFields.hidden = true;
    body.appendChild(novelFields);
    const source = state.mode === QUICK_FETCH_MODE ? quickState
        : state.mode === 'search' ? searchState : state.mode === 'user' ? userState : seriesState;
    const mode = state.mode === QUICK_FETCH_MODE ? 'quick' : state.mode;
    const kind = state.mode === QUICK_FETCH_MODE ? quickState.drill?.kind || source.kind : source.kind;
    const acquisition = altAcquisition(mode, source.source, kind);
    const mixed = dockState.open || state.mode === SINGLE_IMPORT_MODE || (state.mode === QUICK_FETCH_MODE
        && (quickState.drill?.type === 'collection' || quickState.kind === 'mixed'));
    window.PixivBatch.queueTypes.contributionsOf('filters').forEach(contribution => {
        if (contribution.extraSelector) body.querySelectorAll(contribution.extraSelector).forEach(node => {
            node.hidden = !mixed && contribution.type !== acquisition?.type;
        });
    });
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
    openDownloadOptionsDrawer('filters');
}

function buildFiltersDrawerFooter(body) {
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
    return footer;
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
    document.querySelectorAll('[data-active-filters]').forEach(host => {
        const f = extraFilters;
        const labels = [];
        for (const field of ['content', 'ai', 'type']) {
            if (f[field] !== 'all') labels.push(field === 'content' && ['r18', 'r18g'].includes(f[field])
                ? (f[field] === 'r18' ? 'R-18' : 'R-18G') : bt('filters.' + field + '.' + f[field], f[field]));
        }
        for (const [field, key] of [['page', 'pages'], ['bookmark', 'bookmarks'], ['words', 'words']]) {
            if (f[field + 'Min'] !== null || f[field + 'Max'] !== null) {
                labels.push(bt('filters.' + key, key) + ': ' + (f[field + 'Min'] ?? 0) + '–' + (f[field + 'Max'] ?? '∞'));
            }
        }
        for (const [field, key] of [['tagsExact', 'tags-exact'], ['tagsFuzzy', 'tags-fuzzy']]) {
            if (f[field].length) labels.push(bt('filters.' + key, key) + ': ' + f[field].join(', '));
        }
        labels.forEach((label, index) => {
            let button = host.children[index];
            if (!button) {
                button = el('button', 'ab-pill ab-pill--brand');
                button.type = 'button';
                button.addEventListener('click', openFiltersDrawer);
                host.appendChild(button);
            }
            if (button.textContent !== label) button.textContent = label;
        });
        while (host.children.length > labels.length) host.lastElementChild.remove();
    });
}

window.PixivBatchAlt.filters = Object.assign(window.PixivBatchAlt.filters, {
    defaultSearchFilters, normalizeSearchFilters, parseTagTerms,
    hasBookmarkFilter, hasExtraSearchFilter, matchSearchFilters,
    matchContentRating, computeFilteredItems, evaluateDownloadFilterSkip,
    loadSearchFilterPrefs, saveSearchFilterPrefs, openFiltersDrawer,
    activeFilterCount, syncFilterButtonBadge
});
