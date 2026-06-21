'use strict';

function searchPlaceholderFor(type) {
    switch (type) {
        case 'title': return pageI18n.t('gallery:search.placeholder.title', '按作品标题搜索...');
        case 'author': return pageI18n.t('gallery:search.placeholder.author', '按作者名搜索...');
        case 'id': return pageI18n.t('novel:search.placeholder.id', '输入小说 ID（数字，精确匹配）...');
        case 'authorId': return pageI18n.t('gallery:search.placeholder.author-id', '输入作者 ID（数字，精确匹配）...');
        case 'desc': return pageI18n.t('gallery:search.placeholder.desc', '按作品简介搜索...');
        case 'content': return pageI18n.t('novel:search.placeholder.content', '按正文关键词全文检索...');
        case 'tag': return pageI18n.t('gallery:search.placeholder.tag', '按标签关键词搜索（模糊）...');
        case 'tagExact': return pageI18n.t('gallery:search.placeholder.tag-exact', '输入完整标签名（精确匹配）...');
        default: return pageI18n.t('novel:search.placeholder', '按标题搜索...');
    }
}

function searchPlaceholderForCurrentView() {
    if (state.view === 'authors') {
        return pageI18n
            ? pageI18n.t('gallery:filter.authors.search.placeholder', 'Search authors...')
            : 'Search authors...';
    }
    if (state.view === 'series') {
        return pageI18n
            ? pageI18n.t('novel:filter.series.search.placeholder', 'Search series...')
            : 'Search series...';
    }
    return searchPlaceholderFor(state.searchType || 'all');
}

function syncSearchTypeSelect() {
    const select = document.getElementById('searchType');
    if (!select) return;
    const enabled = state.view === 'all';
    select.hidden = !enabled;
    select.disabled = !enabled;
    select.value = enabled ? (state.searchType || 'all') : 'all';
}

function updateSearchPlaceholder() {
    const el = document.getElementById('searchInput');
    if (el && pageI18n) el.placeholder = searchPlaceholderForCurrentView();
    syncSearchTypeSelect();
}

function setSearchEmptyState(empty) {
    const box = document.querySelector('.search-box');
    const btn = document.getElementById('filterToggle');
    if (box) box.classList.toggle('search-no-result', !!empty);
    if (btn) btn.classList.toggle('search-no-result', !!empty);
}

function rememberTagOption(tag) {
    if (!tag || tag.tagId == null) return;
    const id = Number(tag.tagId);
    if (!Number.isFinite(id)) return;
    if (tag.name) state.tagNames.set(id, tag.name);
    if (tag.translatedName) state.tagTranslatedNames.set(id, tag.translatedName);
}

function rememberAuthorOption(author) {
    if (!author || author.authorId == null) return;
    const id = Number(author.authorId);
    if (!Number.isFinite(id)) return;
    if (author.name) state.authorNames.set(id, author.name);
}

function setFilterPanelOpen(open) {
    document.getElementById('filterPanel').classList.toggle('open', open);
    document.getElementById('filterToggle').classList.toggle('active', open);
}

function updateOrderToggleLabel() {
    const btn = document.getElementById('orderToggle');
    if (!btn) return;
    const key = btn.dataset.order === 'asc' ? 'filter.order.asc' : 'filter.order.desc';
    btn.textContent = pageI18n ? pageI18n.t(key, btn.textContent) : btn.textContent;
}

function bindModeGroup(groupId, onChange) {
    const group = document.getElementById(groupId);
    group.querySelectorAll('.filter-mode-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            group.querySelectorAll('.filter-mode-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            onChange(btn.dataset.filterMode);
        });
    });
}

function toggleExpand(chipsId, btnId) {
    const el = document.getElementById(chipsId);
    el.classList.toggle('expanded');
    document.getElementById(btnId).textContent = el.classList.contains('expanded')
        ? pageI18n.t('filter.collapse', '收起')
        : pageI18n.t('filter.expand', '展开全部');
}

function resetAllFilters() {
    state.r18 = 'any'; state.ai = 'any';
    document.querySelectorAll('.chip[data-r18]').forEach(c => c.classList.toggle('active', c.dataset.r18 === 'any'));
    document.querySelectorAll('.chip[data-ai]').forEach(c => c.classList.toggle('active', c.dataset.ai === 'any'));
    state.selectedCollections.clear();
    state.selectedTags.clear(); state.selectedSeries.clear(); state.selectedAuthors.clear();
    state.exclusiveAuthorId = null;
    renderCollections(); renderTagChips(); renderSeriesChips(); renderAuthorChips();
    renderCollectionFilterChips(); updateAuthorFilterBar();
    state.page = 0;
    updateFilterBadge();
    reloadCurrentView();
}

function updateFilterBadge() {
    let n = 0;
    if (state.r18 !== 'any') n++;
    if (state.ai !== 'any') n++;
    if (state.selectedCollections.size) n += state.selectedCollections.size;
    if (state.selectedTags.size) n += state.selectedTags.size;
    if (state.selectedSeries.size) n += state.selectedSeries.size;
    if (state.selectedAuthors.size) n += state.selectedAuthors.size;
    if (state.exclusiveAuthorId != null) n++;
    const badge = document.getElementById('filterBadge');
    badge.textContent = String(n);
    badge.classList.toggle('visible', n > 0);
}

// ---------- Tags ----------
async function loadTags() {
    try {
        const params = new URLSearchParams({ limit: '300' });
        if (state.tagSearch) params.set('search', state.tagSearch);
        const r = await fetch(`/api/gallery/novels/tags?${params.toString()}`);
        if (!r.ok) return;
        const data = await r.json();
        state.tags = data.tags || [];
        state.tags.forEach(rememberTagOption);
        renderTagChips();
    } catch (e) { console.warn(pageI18n ? pageI18n.t('gallery:log.tags-failed', '加载标签失败') : '加载标签失败', e); }
}

function renderTagChips() {
    const box = document.getElementById('filterTagChips');
    let filtered = state.tags.slice();
    filtered.sort((a, b) => compareFilterSelectionOrder(state.selectedTags, a.tagId, b.tagId));

    const loadedIds = new Set(filtered.map(t => Number(t.tagId)));
    const missing = [...state.selectedTags.keys()].filter(id => !loadedIds.has(Number(id)));
    if (missing.length) {
        const stubs = missing.map(id => ({
            tagId: id,
            name: state.tagNames.get(id) || `#${id}`,
            translatedName: state.tagTranslatedNames.get(id) || '',
            novelCount: 0
        }));
        filtered = stubs.concat(filtered);
        filtered.sort((a, b) => compareFilterSelectionOrder(state.selectedTags, a.tagId, b.tagId));
    }

    if (!filtered.length) {
        box.innerHTML = `<span class="chip-empty">${esc(pageI18n.t('filter.tags.empty', '暂无可用标签'))}</span>`;
        return;
    }
    box.innerHTML = filtered.map(t => {
        const tagId = Number(t.tagId);
        const mode = state.selectedTags.get(tagId);
        const cls = mode ? `filter-selected mode-${mode}` : '';
        const trans = t.translatedName ? `<span class="tag-trans">${esc(t.translatedName)}</span>` : '';
        return `<button class="chip ${cls}" data-tag-id="${tagId}">${esc(t.name || ('#' + tagId))}${trans}<span class="chip-count">${t.novelCount}</span></button>`;
    }).join('');
    box.querySelectorAll('.chip[data-tag-id]').forEach(chip => {
        chip.addEventListener('click', () => toggleTagSelection(Number(chip.dataset.tagId)));
    });
}

function toggleTagSelection(tagId) {
    const current = state.selectedTags.get(tagId);
    if (current === state.tagMode) state.selectedTags.delete(tagId);
    else state.selectedTags.set(tagId, state.tagMode);
    state.page = 0;
    renderTagChips();
    updateFilterBadge();
    reloadCurrentView();
}

function setTagFilterExclusive(tagId, name, translatedName) {
    tagId = Number(tagId);
    if (!Number.isFinite(tagId) || tagId <= 0) return;
    if (!state.tags.some(t => Number(t.tagId) === tagId)) {
        state.tags = [{
            tagId,
            name: name || `#${tagId}`,
            translatedName: translatedName || '',
            novelCount: 0
        }, ...state.tags];
    }
    rememberTagOption({ tagId, name: name || `#${tagId}`, translatedName: translatedName || '' });
    state.selectedTags.clear();
    state.selectedTags.set(tagId, 'must');
    state.tagMode = 'must';
    document.querySelectorAll('#tagModeButtons .filter-mode-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.filterMode === 'must');
    });
    state.page = 0;
    renderTagChips();
    updateFilterBadge();
    setFilterPanelOpen(false);
    if (state.view !== 'all') {
        switchView('all');
    } else {
        syncViewParamInUrl();
        reloadCurrentView();
    }
}

// ---------- Series ----------
async function loadSeries() {
    try {
        const params = new URLSearchParams({ size: '200', sort: 'novels' });
        if (state.seriesSearch) params.set('search', state.seriesSearch);
        const r = await fetch(`/api/gallery/novels/series?${params.toString()}`);
        if (!r.ok) return;
        const data = await r.json();
        state.series = data.content || [];
        renderSeriesChips();
    } catch (e) { console.warn(pageI18n ? pageI18n.t('gallery:log.series-options-failed', '加载系列失败') : '加载系列失败', e); }
}

function renderSeriesChips() {
    const box = document.getElementById('filterSeriesChips');
    if (!state.series.length) {
        box.innerHTML = `<span class="chip-empty">${esc(pageI18n.t('filter.series.empty', '暂无系列'))}</span>`;
        return;
    }
    box.innerHTML = state.series.map(s => {
        const mode = state.selectedSeries.get(s.seriesId);
        const cls = mode ? `filter-selected mode-${mode}` : '';
        return `<button class="chip ${cls}" data-series-id="${s.seriesId}">${esc(s.title)}<span class="chip-count">${s.novelCount}</span></button>`;
    }).join('');
    box.querySelectorAll('.chip[data-series-id]').forEach(chip => {
        chip.addEventListener('click', () => toggleSeriesSelection(Number(chip.dataset.seriesId)));
    });
}

function toggleSeriesSelection(seriesId) {
    const current = state.selectedSeries.get(seriesId);
    // 系列只支持 must/not（点击循环：none → must → not → none）
    const next = current === 'must' ? 'not' : (current === 'not' ? null : 'must');
    if (next === null) state.selectedSeries.delete(seriesId);
    else state.selectedSeries.set(seriesId, next);
    state.page = 0;
    renderSeriesChips();
    updateFilterBadge();
    reloadCurrentView();
}

// ---------- Authors ----------
async function loadAuthors() {
    try {
        const params = new URLSearchParams({ size: '200', sort: 'novels' });
        if (state.authorSearch) params.set('search', state.authorSearch);
        const r = await fetch(`/api/gallery/novels/authors?${params.toString()}`);
        if (!r.ok) return;
        const data = await r.json();
        state.authors = data.content || [];
        state.authors.forEach(rememberAuthorOption);
        renderAuthorChips();
    } catch (e) { console.warn(pageI18n ? pageI18n.t('gallery:log.author-options-failed', '加载作者失败') : '加载作者失败', e); }
}

function renderAuthorChips() {
    const box = document.getElementById('filterAuthorChips');
    let filtered = state.authors.slice();
    filtered.sort((a, b) => compareFilterSelectionOrder(state.selectedAuthors, a.authorId, b.authorId));

    const loadedIds = new Set(filtered.map(a => Number(a.authorId)));
    const missing = [...state.selectedAuthors.keys()].filter(id => !loadedIds.has(Number(id)));
    if (missing.length) {
        const stubs = missing.map(id => ({
            authorId: id,
            name: state.authorNames.get(id) || `#${id}`,
            novelCount: 0
        }));
        filtered = stubs.concat(filtered);
        filtered.sort((a, b) => compareFilterSelectionOrder(state.selectedAuthors, a.authorId, b.authorId));
    }

    if (!filtered.length) {
        box.innerHTML = `<span class="chip-empty">${esc(pageI18n.t('filter.authors.empty', '暂无作者'))}</span>`;
        return;
    }
    box.innerHTML = filtered.map(a => {
        const authorId = Number(a.authorId);
        const mode = state.selectedAuthors.get(authorId);
        const cls = mode ? `filter-selected mode-${mode}` : '';
        return `<button class="chip ${cls}" data-author-id="${authorId}">${esc(a.name || ('#' + authorId))}<span class="chip-count">${a.novelCount}</span></button>`;
    }).join('');
    box.querySelectorAll('.chip[data-author-id]').forEach(chip => {
        chip.addEventListener('click', () => toggleAuthorSelection(Number(chip.dataset.authorId)));
    });
}

function toggleAuthorSelection(authorId) {
    const current = state.selectedAuthors.get(authorId);
    if (current === state.authorMode) state.selectedAuthors.delete(authorId);
    else state.selectedAuthors.set(authorId, state.authorMode);
    state.page = 0;
    renderAuthorChips();
    updateFilterBadge();
    reloadCurrentView();
}

function setAuthorFilterExclusive(authorId, name) {
    state.exclusiveAuthorId = authorId;
    state.exclusiveAuthorName = name || ('#' + authorId);
    state.selectedAuthors.clear();
    state.view = 'all';
    syncViewParamInUrl();
    setActiveViewNav();
    state.page = 0;
    updateAuthorFilterBar();
    renderAuthorChips();
    updateFilterBadge();
    reloadCurrentView();
}

function updateAuthorFilterBar() {
    const bar = document.getElementById('authorFilterBar');
    if (state.exclusiveAuthorId != null) {
        document.getElementById('authorFilterName').textContent = state.exclusiveAuthorName;
        bar.classList.add('visible');
    } else {
        bar.classList.remove('visible');
    }
}
