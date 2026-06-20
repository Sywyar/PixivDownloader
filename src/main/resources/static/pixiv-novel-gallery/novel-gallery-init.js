'use strict';

function setupNovelCrossPageHandoff() {
    // 类型切换链接由 /js/pixiv-navigation.js 异步渲染进 slot，渲染时机不确定：
    //  · 点击：用事件委托（监听 document），无论链接何时生成都能在跳转前写入跨页交接；
    //  · href：监听 'pixivnav:rendered'，每次导航渲染完成后把当前 view 同步进 slot 内链接
    //    （slot 重新生成会覆盖 href，故需重新同步），不特判对方页面路径。
    document.addEventListener('click', e => {
        const link = e.target.closest && e.target.closest('.gallery-type-switch a[href]');
        if (link) writeNovelGalleryCrossTransfer();
    });
    window.addEventListener('pixivnav:rendered', syncNovelTypeSwitchHrefs);
    syncNovelTypeSwitchHrefs();
}

async function init() {
    restoreSidebarState();

    pageI18n = await PixivI18n.create({ namespaces: ['gallery', 'novel', 'common'] });
    pageI18n.apply();
    updateSearchPlaceholder();
    updateOrderToggleLabel();
    await PixivLangSwitcher.mount({
        mountPoint: document.getElementById('langSwitcherAnchor'),
        i18n: pageI18n,
        onChange: (next) => {
            pageI18n = next;
            pageI18n.apply();
            if (window.PixivNav) PixivNav.refresh();
            updateSearchPlaceholder();
            updateOrderToggleLabel();
            syncCollectionFormTitle();
            reloadCurrentView();
        }
    });
    PixivTheme.mount({ mountPoint: document.getElementById('langSwitcherAnchor') });
    setupEventHandlers();
    setupAdminMode();
    wireBatchManage();

    // 在拉取列表数据前恢复持久化的状态（搜索文字会影响 loadTags / loadAuthors / loadSeries 请求）
    const params = new URLSearchParams(location.search);
    const hasUrlFilters = NOVEL_URL_FILTER_KEYS.some(key => params.has(key));
    if (!hasUrlFilters) {
        restoreNovelGalleryState();
        applyCrossTransferToNovelGallery(consumeNovelGalleryCrossTransfer());
    }

    await loadCollections();
    await loadTags();
    await loadSeries();
    await loadAuthors();
    // URL 中的导航筛选参数优先级最高（含 view 切换）
    applyInitialUrlState();
    applyNovelGalleryStateToUi();
    setActiveViewNav();
    syncViewParamInUrl();
    setupNovelCrossPageHandoff();
    reloadCurrentView();
    applyOnboardingDisplayName();
}

// 个性化称呼：拉取后端保存的称呼填入侧边栏底部用户卡片（替换占位 “Pixiv User”）。
// 仅对「全局可见」范围（solo / 已登录管理员）放行；其余情况静默保留占位。
async function applyOnboardingDisplayName() {
    try {
        const res = await fetch('/api/onboarding/profile', {credentials: 'same-origin'});
        if (!res.ok) return;
        const data = await res.json();
        const name = data && data.displayName ? data.displayName.trim() : '';
        if (!name) return;
        const nameEl = document.getElementById('userName');
        const avatarEl = document.getElementById('userAvatar');
        if (nameEl) nameEl.textContent = name;
        if (avatarEl) avatarEl.textContent = name.charAt(0).toUpperCase();
    } catch (_) { /* 非管理员 / 网络异常：保留占位 */ }
}

function applyInitialUrlState() {
    const params = new URLSearchParams(location.search);
    let changed = false;

    const ids = parsePositiveIdList(
        [params.get('seriesId'), params.get('seriesIds')]
            .filter(Boolean)
            .join(',')
    );
    if (ids.length) {
        state.selectedSeries.clear();
        ids.forEach(id => state.selectedSeries.set(id, 'must'));
        if (ids.length === 1 && !state.series.some(s => Number(s.seriesId) === ids[0])) {
            const title = params.get('seriesTitle') || `#${ids[0]}`;
            state.series = [{ seriesId: ids[0], title, authorId: null, authorName: '', novelCount: 0 }, ...state.series];
        }
        renderSeriesChips();
        changed = true;
    }

    let tagIds = parsePositiveIdList(
        [params.get('tagId'), params.get('tagIds'), params.get('filterTagId')]
            .filter(Boolean)
            .join(',')
    );
    const tagName = params.get('tagName') || params.get('filterTag') || '';
    const translatedName = params.get('tagTranslatedName') || params.get('filterTagTranslated') || '';
    if (!tagIds.length) {
        const normalizedTagName = normalizeFilterText(tagName);
        const normalizedTranslatedName = normalizeFilterText(translatedName);
        const loaded = state.tags.find(t =>
            (normalizedTagName && normalizeFilterText(t.name) === normalizedTagName)
            || (normalizedTranslatedName && normalizeFilterText(t.translatedName) === normalizedTranslatedName));
        if (loaded && Number(loaded.tagId) > 0) {
            tagIds = [Number(loaded.tagId)];
        }
    }
    if (tagIds.length) {
        state.selectedTags.clear();
        tagIds.forEach(id => state.selectedTags.set(id, 'must'));
        if (tagIds.length === 1 && !state.tags.some(t => Number(t.tagId) === tagIds[0])) {
            state.tags = [{ tagId: tagIds[0], name: tagName || `#${tagIds[0]}`, translatedName, novelCount: 0 }, ...state.tags];
        }
        renderTagChips();
        changed = true;
    }

    const collectionIds = parsePositiveIdList(params.get('collectionIds'));
    if (collectionIds.length) {
        state.selectedCollections.clear();
        collectionIds.forEach(id => state.selectedCollections.add(id));
        renderCollections();
        renderCollectionFilterChips();
        changed = true;
    }

    const requestedView = readViewParam(params);
    if (requestedView && requestedView !== state.view) {
        state.view = requestedView;
        state.page = 0;
        state.authorsView.page = 0;
        state.seriesView.page = 0;
    }

    if (params.get('createCollection') === '1') {
        openCollectionFormModal(null);
    }

    setFilterPanelOpen(params.get('openFilter') === '1');
    if (changed) updateFilterBadge();
}

function setupEventHandlers() {
    // Search
    document.getElementById('searchInput').addEventListener('input', debounce(() => {
        state.search = document.getElementById('searchInput').value.trim();
        state.page = 0;
        reloadCurrentView();
    }, 250));
    const searchTypeEl = document.getElementById('searchType');
    if (searchTypeEl) {
        searchTypeEl.addEventListener('change', () => {
            if (state.view !== 'all') {
                updateSearchPlaceholder();
                return;
            }
            const v = searchTypeEl.value;
            state.searchType = NOVEL_SEARCH_TYPE_VALUES.has(v) ? v : 'all';
            updateSearchPlaceholder();
            state.page = 0;
            if (state.search) {
                reloadNovels();
            } else {
                persistNovelGalleryState();
            }
        });
    }
    // Filter toggle
    document.getElementById('filterToggle').addEventListener('click', () => {
        const panel = document.getElementById('filterPanel');
        setFilterPanelOpen(!panel.classList.contains('open'));
    });
    // Sort chips
    document.querySelectorAll('.chip[data-sort]').forEach(chip => {
        chip.addEventListener('click', () => {
            document.querySelectorAll('.chip[data-sort]').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            state.sort = chip.dataset.sort;
            state.page = 0;
            reloadCurrentView();
        });
    });
    // Order toggle
    document.getElementById('orderToggle').addEventListener('click', () => {
        const btn = document.getElementById('orderToggle');
        const next = btn.dataset.order === 'desc' ? 'asc' : 'desc';
        btn.dataset.order = next;
        updateOrderToggleLabel();
        state.order = next;
        state.page = 0;
        reloadCurrentView();
    });
    // R18 chips
    document.querySelectorAll('.chip[data-r18]').forEach(chip => {
        chip.addEventListener('click', () => {
            document.querySelectorAll('.chip[data-r18]').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            state.r18 = chip.dataset.r18;
            state.page = 0;
            updateFilterBadge();
            reloadCurrentView();
        });
    });
    // AI chips
    document.querySelectorAll('.chip[data-ai]').forEach(chip => {
        chip.addEventListener('click', () => {
            document.querySelectorAll('.chip[data-ai]').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            state.ai = chip.dataset.ai;
            state.page = 0;
            updateFilterBadge();
            reloadCurrentView();
        });
    });
    // Mode group buttons (tag/author/series — series only must/not)
    bindModeGroup('tagModeButtons', mode => { state.tagMode = mode; });
    bindModeGroup('authorModeButtons', mode => { state.authorMode = mode; });
    // Search inputs
    document.getElementById('tagSearchInput').addEventListener('input', debounce(() => {
        state.tagSearch = document.getElementById('tagSearchInput').value.trim();
        loadTags();
    }, 250));
    document.getElementById('seriesSearchInput').addEventListener('input', debounce(() => {
        state.seriesSearch = document.getElementById('seriesSearchInput').value.trim();
        loadSeries();
    }, 250));
    document.getElementById('authorSearchInput').addEventListener('input', debounce(() => {
        state.authorSearch = document.getElementById('authorSearchInput').value.trim();
        loadAuthors();
    }, 250));
    // Reset / expand
    document.getElementById('tagFilterReset').addEventListener('click', () => { state.selectedTags.clear(); renderTagChips(); state.page = 0; updateFilterBadge(); reloadCurrentView(); });
    document.getElementById('seriesFilterReset').addEventListener('click', () => { state.selectedSeries.clear(); renderSeriesChips(); state.page = 0; updateFilterBadge(); reloadCurrentView(); });
    document.getElementById('authorFilterReset').addEventListener('click', () => { state.selectedAuthors.clear(); state.exclusiveAuthorId = null; renderAuthorChips(); updateAuthorFilterBar(); state.page = 0; updateFilterBadge(); reloadCurrentView(); });
    document.getElementById('tagExpandToggle').addEventListener('click', () => toggleExpand('filterTagChips', 'tagExpandToggle'));
    document.getElementById('seriesExpandToggle').addEventListener('click', () => toggleExpand('filterSeriesChips', 'seriesExpandToggle'));
    document.getElementById('authorExpandToggle').addEventListener('click', () => toggleExpand('filterAuthorChips', 'authorExpandToggle'));
    document.getElementById('filterReset').addEventListener('click', resetAllFilters);
    document.getElementById('authorFilterClear').addEventListener('click', () => {
        state.exclusiveAuthorId = null; state.selectedAuthors.clear(); renderAuthorChips(); updateAuthorFilterBar(); state.page = 0; updateFilterBadge(); reloadCurrentView();
    });

    // View nav
    document.querySelectorAll('.nav-item[data-view], .gallery-type-option[data-view]').forEach(el => {
        el.addEventListener('click', e => {
            e.preventDefault();
            switchView(el.dataset.view);
        });
    });

    document.getElementById('btnCreateCollection').addEventListener('click', () => openCollectionFormModal(null));
    document.getElementById('collectionFormClose').addEventListener('click', closeCollectionFormModal);
    document.getElementById('collectionFormCancel').addEventListener('click', closeCollectionFormModal);
    document.getElementById('collectionFormIconChoose').addEventListener('click', () => {
        document.getElementById('collectionFormIconFile').click();
    });
    document.getElementById('collectionFormIconFile').addEventListener('change', e => {
        const file = e.target.files[0];
        if (!file) return;
        if (file.size > 1024 * 1024) {
            toast(pageI18n.t('gallery:toast.icon-too-large', '图标大小不能超过 1MB'), 'error');
            e.target.value = '';
            return;
        }
        state.pendingIconFile = file;
        state.pendingIconClear = false;
        updateFormIconPreview(state.editingCollection);
    });
    document.getElementById('collectionFormIconClear').addEventListener('click', () => {
        state.pendingIconFile = null;
        state.pendingIconClear = true;
        document.getElementById('collectionFormIconFile').value = '';
        updateFormIconPreview(state.editingCollection);
    });
    document.getElementById('collectionFormName').addEventListener('input', renderCollectionDownloadRootPreview);
    document.getElementById('collectionDownloadRoot').addEventListener('input', renderCollectionDownloadRootPreview);
    document.getElementById('collectionDownloadRootEnabled').addEventListener('change', syncCollectionDownloadRootControls);
    document.getElementById('collectionFormSubmit').addEventListener('click', submitCollectionForm);
    document.getElementById('modalCollectionForm').addEventListener('click', e => {
        if (e.target.id === 'modalCollectionForm') closeCollectionFormModal();
    });
}

async function setupAdminMode() {
    try {
        const ok = await fetch('/api/admin/invites/access-check', { credentials: 'same-origin' });
        if (ok.ok) {
            document.body.classList.add('admin-mode');
            isNovelAdmin = true;
            updateBatchButtonVisibility();
        }
    } catch (_) { /* not admin */ }
}

document.getElementById('addToCollectionClose').addEventListener('click', closeAddToCollectionModal);
document.getElementById('addToCollectionDone').addEventListener('click', closeAddToCollectionModal);
document.getElementById('modalAddToCollection').addEventListener('click', e => {
    if (e.target.id === 'modalAddToCollection') closeAddToCollectionModal();
});

document.getElementById('quickCreateBtn').addEventListener('click', async () => {
    const input = document.getElementById('quickCreateName');
    const name = input.value.trim();
    if (!name) return;
    const btn = document.getElementById('quickCreateBtn');
    btn.disabled = true;
    try {
        const r = await fetch('/api/collections', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name })
        });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const c = await r.json();
        input.value = '';
        state.collections.push(c);
        state.collections.sort((a, b) => (a.sortOrder - b.sortOrder) || (a.id - b.id));
        if (collectionState.activeNovelId != null) {
            await fetch(`/api/collections/${c.id}/novels/${collectionState.activeNovelId}`, { method: 'POST', credentials: 'same-origin' });
            collectionState.membership.add(c.id);
        }
        renderCollections();
        renderCollectionFilterChips();
        renderAddToCollectionList();
        toast(pageI18n.t('toast.created', '创建成功'), 'success');
    } catch (e) {
        toast(pageI18n.t('toast.create-failed', '创建失败'), 'error');
    } finally {
        btn.disabled = false;
    }
});

init();
