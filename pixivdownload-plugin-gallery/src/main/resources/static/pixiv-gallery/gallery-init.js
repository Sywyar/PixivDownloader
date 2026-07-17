'use strict';

    function setupGalleryCrossPageHandoff() {
        // 类型切换链接由 /js/pixiv-navigation.js 异步渲染进 slot，渲染时机不确定：
        //  · 点击：用事件委托（监听 document），无论链接何时生成都能在跳转前写入跨页交接；
        // 入口各自声明目标页默认分类；跨类型时不把当前页面的分类名写进其它类型 URL。
        document.addEventListener('click', e => {
            const link = e.target.closest && e.target.closest('.gallery-type-switch a[href]');
            if (link) writeGalleryCrossTransfer();
        });
    }

    // 个性化称呼：拉取后端保存的称呼，写入侧边栏底部用户卡片（替换占位 “Pixiv User”）。
    // 同时作为新手向导的资格闸：/api/onboarding/profile 仅对「全局可见」范围（solo / 已登录管理员）放行，
    // 403 即视为不参与跨页向导（如多人模式访客）。返回 { eligible, displayName }。
    async function loadOnboardingProfile() {
        try {
            const res = await fetch('/api/onboarding/profile', {credentials: 'same-origin'});
            if (!res.ok) return {eligible: false, displayName: null};
            const data = await res.json();
            return {eligible: true, displayName: data && data.displayName ? data.displayName : null};
        } catch (_) {
            return {eligible: false, displayName: null};
        }
    }

    function applyDisplayName(name) {
        const nameEl = document.getElementById('userName');
        const avatarEl = document.getElementById('userAvatar');
        const shown = (name && name.trim()) ? name.trim()
            : (typeof PixivOnboarding !== 'undefined' ? PixivOnboarding.getName() : '');
        if (nameEl && shown) nameEl.textContent = shown;
        if (avatarEl && shown) avatarEl.textContent = shown.charAt(0).toUpperCase();
    }

    async function setupGalleryOnboarding() {
        const profile = await loadOnboardingProfile();
        applyDisplayName(profile.displayName);
        if (typeof PixivOnboarding === 'undefined') return;
        PixivOnboarding.boot({
            page: 'gallery',
            completionStepId: 'local-gallery-guide',
            i18n: pageI18n,
            eligible: profile.eligible,
            sel: {
                grid: '#galleryGrid',
                viewNav: '#galleryViewNav',
                searchBox: '.search-box',
                filterToggle: '#filterToggle'
            },
            hooks: {
                // 刚下载的示例作品卡片（按时间倒序通常在首位，按 data-id 精确定位）
                getExampleCard: (id) => document.querySelector(`#galleryGrid .work-card[data-id="${id}"]`),
                getExampleCardSelector: (id) => `#galleryGrid .work-card[data-id="${id}"]`
            }
        });
    }

    // ---------- Collection CRUD ----------
    document.getElementById('btnCreateCollection').addEventListener('click', () => openCollectionFormModal(null));

    document.getElementById('collectionFormIconFile').addEventListener('change', e => {
        const file = e.target.files[0];
        if (!file) return;
        if (file.size > 1024 * 1024) {
            toast(t('toast.icon-too-large', 'Icon size must not exceed 1MB'), 'error');
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

    document.getElementById('collectionFormName').addEventListener('input', () => {
        renderCollectionDownloadRootPreview();
    });

    document.getElementById('collectionDownloadRoot').addEventListener('input', () => {
        renderCollectionDownloadRootPreview();
    });

    document.getElementById('collectionDownloadRootEnabled').addEventListener('change', () => {
        syncCollectionDownloadRootControls();
    });

    document.getElementById('collectionFormSubmit').addEventListener('click', async () => {
        const name = document.getElementById('collectionFormName').value.trim();
        if (!name) {
            toast(t('toast.name-required', 'Please enter a name'), 'error');
            return;
        }
        const downloadRoot = readCollectionDownloadRoot();
        if (document.getElementById('collectionDownloadRootEnabled').checked && !downloadRoot) {
            toast(t('toast.download-root-required', '请填写下载目录'), 'error');
            return;
        }
        if (downloadRoot && hasControlCharacter(downloadRoot)) {
            toast(t('toast.download-root-invalid', '下载目录不能包含控制字符'), 'error');
            return;
        }
        const btn = document.getElementById('collectionFormSubmit');
        btn.disabled = true;
        try {
            let collection;
            if (state.editingCollection) {
                await api(`/api/collections/${state.editingCollection.id}`, {
                    method: 'PUT',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({name}),
                });
                await api(`/api/collections/${state.editingCollection.id}/download-root`, {
                    method: 'PUT',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({downloadRoot}),
                });
                collection = {...state.editingCollection, name, downloadRoot};
            } else {
                collection = await api('/api/collections', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({name, downloadRoot}),
                });
            }
            if (state.pendingIconFile) {
                const form = new FormData();
                form.append('file', state.pendingIconFile);
                await api(`/api/collections/${collection.id}/icon`, {method: 'POST', body: form});
            } else if (state.pendingIconClear && state.editingCollection) {
                await api(`/api/collections/${collection.id}/icon`, {method: 'DELETE'});
            }
            toast(state.editingCollection ? t('toast.saved', 'Saved') : t('toast.created', 'Created'), 'success');
            closeCollectionFormModal();
            await loadCollections();
        } catch (e) {
            toast(e.message || t('toast.save-failed', 'Save failed'), 'error');
        } finally {
            btn.disabled = false;
        }
    });

    document.addEventListener('click', e => {
        if (!e.target.closest('#collectionContextMenu') && !e.target.closest('.collection-menu')) closeContextMenu();
    });

    contextMenu.addEventListener('click', async e => {
        const action = e.target.closest('.context-item')?.dataset.action;
        if (!action || !contextMenuCollection) return;
        const target = contextMenuCollection;
        closeContextMenu();
        if (action === 'edit') {
            openCollectionFormModal(target);
        } else if (action === 'delete') {
            if (!confirm(t('dialog.delete-collection', 'Delete "{name}"? Artwork links in this collection will be removed.', {name: target.name}))) return;
            try {
                await api(`/api/collections/${target.id}`, {method: 'DELETE'});
                state.collectionIds.delete(target.id);
                toast(t('toast.deleted', 'Deleted'), 'success');
                await loadCollections();
                updateFilterBadge();
                await loadGallery();
            } catch (err) {
                toast(err.message || t('toast.delete-failed', 'Delete failed'), 'error');
            }
        }
    });

    // ---------- Filter panel ----------
    document.getElementById('filterToggle').addEventListener('click', () => {
        const panel = document.getElementById('filterPanel');
        const willOpen = !panel.classList.contains('open');
        if (willOpen) {
            ensureGalleryView();
        }
        setFilterPanelOpen(willOpen);
    });

    document.querySelectorAll('.filter-mode-btn[data-filter-kind][data-filter-mode]').forEach(btn => {
        btn.addEventListener('click', () => {
            setFilterMode(btn.dataset.filterKind, btn.dataset.filterMode);
        });
    });

    document.getElementById('tagFilterReset').addEventListener('click', () => {
        resetFilterKind('tag');
    });

    document.getElementById('seriesFilterReset').addEventListener('click', () => {
        resetSeriesFilterOptions();
    });

    document.getElementById('authorFilterReset').addEventListener('click', () => {
        resetFilterKind('author');
    });

    document.querySelectorAll('.chip[data-sort]').forEach(chip => {
        chip.addEventListener('click', () => {
            document.querySelectorAll('.chip[data-sort]').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            state.sort = chip.dataset.sort;
            state.page = 0;
            refreshGalleryForCurrentState();
        });
    });

    document.getElementById('orderToggle').addEventListener('click', () => {
        const btn = document.getElementById('orderToggle');
        state.order = state.order === 'desc' ? 'asc' : 'desc';
        btn.dataset.order = state.order;
        updateOrderToggleLabel();
        state.page = 0;
        refreshGalleryForCurrentState();
    });

    bindTristate('r18', 'r18');
    bindTristate('ai', 'ai');

    document.querySelectorAll('.chip[data-format]').forEach(chip => {
        chip.addEventListener('click', () => {
            const fmt = chip.dataset.format;
            if (state.formats.has(fmt)) state.formats.delete(fmt); else state.formats.add(fmt);
            chip.classList.toggle('active');
            state.page = 0;
            updateFilterBadge();
            refreshGalleryForCurrentState();
        });
    });

    document.getElementById('filterReset').addEventListener('click', () => {
        state.r18 = 'any';
        state.ai = 'any';
        state.formats.clear();
        state.collectionIds.clear();
        clearFilterSelections('tag');
        clearFilterSelections('author');
        state.seriesFilter = {id: null, title: ''};
        clearFilterSearch('tag');
        clearFilterSearch('series');
        clearFilterSearch('author');
        state.tagFilters.mode = 'must';
        state.authorFilters.mode = 'must';
        state.authorNames.clear();
        document.querySelectorAll('.chip[data-r18]').forEach(c => c.classList.toggle('active', c.dataset.r18 === 'any'));
        document.querySelectorAll('.chip[data-ai]').forEach(c => c.classList.toggle('active', c.dataset.ai === 'any'));
        document.querySelectorAll('.chip[data-format]').forEach(c => c.classList.remove('active'));
        state.page = 0;
        renderFilterModeButtons('tag');
        renderFilterModeButtons('author');
        syncTagFilterBar();
        syncAuthorFilterBar();
        syncSeriesFilterBar();
        renderCollections();
        renderTagChips();
        renderSeriesFilterChips();
        renderAuthorChips();
        updateFilterBadge();
        refreshGalleryForCurrentState();
    });

    document.getElementById('tagSearchInput').addEventListener('input', e => {
        const v = e.target.value;
        clearTimeout(tagSearchTimer);
        tagSearchTimer = setTimeout(() => {
            state.tagFilterText = v;
            renderTagChips();
        }, 150);
    });

    document.getElementById('tagExpandToggle').addEventListener('click', () => {
        state.tagsExpanded = !state.tagsExpanded;
        renderTagChips();
    });

    document.getElementById('seriesSearchInput').addEventListener('input', e => {
        const v = e.target.value;
        clearTimeout(seriesSearchTimer);
        seriesSearchTimer = setTimeout(() => {
            state.seriesFilterText = v;
            renderSeriesFilterChips();
        }, 150);
    });

    document.getElementById('seriesExpandToggle').addEventListener('click', () => {
        state.seriesExpanded = !state.seriesExpanded;
        renderSeriesFilterChips();
    });

    document.getElementById('authorSearchInput').addEventListener('input', e => {
        const v = e.target.value;
        clearTimeout(authorSearchTimer);
        authorSearchTimer = setTimeout(() => {
            state.authorFilterText = v;
            renderAuthorChips();
        }, 150);
    });

    document.getElementById('authorExpandToggle').addEventListener('click', () => {
        state.authorsExpanded = !state.authorsExpanded;
        renderAuthorChips();
    });

    // ---------- Search (debounced) ----------
    let searchTimer = null;
    document.getElementById('searchInput').addEventListener('input', e => {
        const v = e.target.value;
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => {
            state.search = v.trim();
            state.page = 0;
            state.authors.page = 0;
            state.series.page = 0;
            if (state.view === 'authors') {
                loadAuthorsView();
            } else if (state.view === 'series') {
                loadSeriesView();
            } else {
                loadGallery();
            }
        }, 350);
    });

    const searchTypeEl = document.getElementById('searchType');
    if (searchTypeEl) {
        searchTypeEl.addEventListener('change', e => {
            if (state.view !== 'all') {
                updateSearchPlaceholder();
                return;
            }
            const v = e.target.value;
            state.searchType = GALLERY_SEARCH_TYPE_VALUES.has(v) ? v : 'all';
            updateSearchPlaceholder();
            state.page = 0;
            state.authors.page = 0;
            state.series.page = 0;
            if (state.search) {
                loadGallery();
            } else {
                persistGalleryState();
            }
        });
    }

    document.getElementById('quickCreateBtn').addEventListener('click', async () => {
        const input = document.getElementById('quickCreateName');
        const name = input.value.trim();
        if (!name) return;
        const btn = document.getElementById('quickCreateBtn');
        btn.disabled = true;
        try {
            const c = await api('/api/collections', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name}),
            });
            input.value = '';
            state.collections.push(c);
            state.collections.sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
            if (state.activeArtworkId) {
                await api(`/api/collections/${c.id}/artworks/${state.activeArtworkId}`, {method: 'POST'});
                state.collectionMembership.add(c.id);
            }
            renderAddToCollectionList();
            toast(t('toast.created', 'Created'), 'success');
        } catch (e) {
            toast(e.message || t('toast.create-failed', 'Creation failed'), 'error');
        } finally {
            btn.disabled = false;
        }
    });

    document.addEventListener('click', e => {
        const backdrop = e.target.classList && e.target.classList.contains('modal-backdrop');
        if (backdrop) e.target.classList.remove('open');
    });

    // ---------- View switch ----------
    document.querySelectorAll('.nav-item[data-view], .gallery-type-option[data-view]').forEach(el => {
        el.addEventListener('click', e => {
            e.preventDefault();
            switchView(el.dataset.view);
        });
    });

    document.getElementById('tagFilterClear').addEventListener('click', () => {
        clearTagFilter();
    });

    document.getElementById('authorFilterClear').addEventListener('click', () => {
        clearAuthorFilter();
    });

    document.getElementById('seriesFilterClear').addEventListener('click', () => {
        clearSeriesFilter();
    });

    function scheduleGalleryFrontendRefresh(frontend) {
        if (!frontend || typeof frontend.refresh !== 'function') return;
        window.setInterval(async () => {
            const previousGeneration = frontend.generation();
            const snapshot = await frontend.refresh({language: pageI18n && pageI18n.lang});
            if (snapshot && snapshot.generation !== previousGeneration
                && typeof frontend.isGenericRequest === 'function'
                && frontend.isGenericRequest(location.search)) {
                if (typeof frontend.refreshGeneric === 'function') await frontend.refreshGeneric();
                else frontend.rerenderGeneric();
            }
        }, 60000);
    }

    // ---------- Boot ----------
    (async function init() {
        restoreSidebarState();

        const frontend = window.PixivGalleryFrontend;
        const frontendReady = frontend && typeof frontend.bootstrap === 'function'
            ? frontend.bootstrap()
            : Promise.resolve(null);

        if (frontend && typeof frontend.isGenericRequest === 'function'
            && frontend.isGenericRequest(location.search)) {
            document.body.classList.add('gallery-generic-mode');
            document.querySelectorAll('#galleryViewNav .active, #galleryViewNav [aria-current]')
                .forEach(item => {
                    item.classList.remove('active');
                    item.removeAttribute('aria-current');
                });
            const i18nReady = initPageI18n()
                .catch(err => console.error(t('log.i18n-failed', 'i18n 加载失败'), err));
            await Promise.all([frontendReady, i18nReady]);
            scheduleGalleryFrontendRefresh(frontend);
            await frontend.startDataFlow({
                search: location.search,
                loadPrimary: () => null,
                generic: {
                    grid: document.getElementById('galleryGrid'),
                    status: document.getElementById('galleryStatus'),
                    pagination: document.getElementById('pagination'),
                    detail: document.getElementById('galleryGenericDetail'),
                    filters: document.getElementById('galleryGenericFilters')
                }
            });
            return;
        }

        scheduleGalleryFrontendRefresh(frontend);
        wireBatchManage();

        // 立即渲染静态控件，避免主界面被网络请求阻塞
        renderFilterModeButtons('tag');
        renderFilterModeButtons('author');

        // i18n 在后台加载，加载完成后补丁式重渲染已到达的动态内容
        initPageI18n().then(() => {
            if (state.collections.length) renderCollections();
            if (state.tags.length) renderTagChips();
            if (state.seriesOptions.length) renderSeriesFilterChips();
            if (state.authorOptions.length) renderAuthorChips();
            applyGalleryStateToUi();
            // 画廊不再注册旧版 💡 操作指引 FAB；新手向导（含画廊逐区域讲解）由 setupGalleryOnboarding 接管
            setupGalleryOnboarding();
        }).catch(err => console.error(t('log.i18n-failed', 'i18n 加载失败'), err));

        // Load the sidebar immediately; filter option lists load when the panel opens.
        loadCollections();

        // 仅当 URL 携带过滤参数时才需要等 tag 选项就绪后解析过滤；常规进入直接放行
        const params = new URLSearchParams(location.search);
        const hasNavigationFilter = GALLERY_FILTER_QUERY_KEYS.some(key => params.has(key));
        const shouldCreateCollection = params.get('createCollection') === '1';
        const urlView = readViewParam(params);

        // 如果 URL 没有显式筛选参数，则恢复持久化的状态（含分页 / 筛选 / 视图）
        if (!hasNavigationFilter) {
            restoreGalleryState();
            applyCrossTransferToGallery(consumeGalleryCrossTransfer());
        }

        let initialView = urlView;
        if (hasNavigationFilter) {
            initialView = await applyNavigationFiltersFromQuery() || initialView;
        }

        if (shouldCreateCollection) {
            openCollectionFormModal(null);
        }

        // 优先采用 URL 中的 view= 参数，否则使用恢复后的视图
        const targetView = normalizeView(initialView || state.view || 'all');
        state.view = targetView;
        applyGalleryStateToUi();
        applyGalleryViewVisibility();
        syncViewParamInUrl();
        syncViewNavigationHrefs();
        setupGalleryCrossPageHandoff();

        const loadPrimary = () => {
            if (targetView === 'authors') {
                return loadAuthorsView();
            }
            if (targetView === 'series') {
                return loadSeriesView();
            }
            return loadGallery();
        };
        if (frontend && typeof frontend.startDataFlow === 'function') {
            frontend.startDataFlow({search: location.search, loadPrimary});
        } else {
            loadPrimary();
        }
    })();

    // ---------- 邀请访客 ----------
    (async function setupInviteEntry() {
        try {
            const ok = await fetch('/api/admin/invites/access-check', { credentials: 'same-origin' });
            if (!ok.ok) return;
        } catch (_) { return; }
        document.body.classList.add('admin-mode');
        isGalleryAdmin = true;
        updateBatchButtonVisibility();

        const btn = document.getElementById('btnInviteGuest');
        if (!btn) return;
        btn.addEventListener('click', () => {
            const host = window.location.hostname;
            const isLocal = host === 'localhost' || host === '127.0.0.1' || host === '::1';
            if (isLocal) {
                const warning = pageI18n
                    ? pageI18n.t('invite:warning.local',
                        '当前服务器地址为本地（{host}），通过此按钮生成的链接他人可能无法访问。是否继续？',
                        { host })
                    : '当前服务器地址为本地（' + host
                        + '），通过此按钮生成的链接他人可能无法访问。是否继续？';
                if (!window.confirm(warning)) return;
            }
            openCreateModal();
        });

        async function fetchIllustTags() {
            const data = await api('/api/gallery/tags?limit=2000');
            return (data.tags || []).map(t => ({
                id: t.tagId,
                name: t.translatedName ? `${t.name} · ${t.translatedName}` : t.name,
                secondary: ''
            }));
        }
        async function fetchPagedAuthors(endpoint) {
            const all = [];
            let page = 0; let totalPages = 1;
            while (page < totalPages && page < 50) {
                const data = await api(`${endpoint}?page=${page}&size=200&sort=name`);
                const list = data.items || data.content || data.authors || data.data || [];
                for (const a of list) {
                    const id = a.authorId != null ? a.authorId : a.id;
                    if (id == null) continue;
                    all.push({ id, name: a.name || ('#' + id), secondary: '' });
                }
                totalPages = data.totalPages != null ? data.totalPages
                    : (list.length === 200 ? page + 2 : page + 1);
                page++;
                if (list.length === 0) break;
            }
            return all;
        }
        async function fetchNovelTags() {
            const data = await api('/api/gallery/novels/tags?limit=2000');
            return (data.tags || []).map(t => ({
                id: t.tagId,
                name: t.translatedName ? `${t.name} · ${t.translatedName}` : t.name,
                secondary: ''
            }));
        }

        function openCreateModal() {
            const tr = (key, fallback, vars) => pageI18n
                ? pageI18n.t('invite:' + key, fallback, vars)
                : fallback;
            window.InviteModals.openInviteFormModal({
                title: tr('modal.create.title', '邀请访客'),
                submitText: tr('modal.create.submit', '创建邀请链接'),
                fetchTags: fetchIllustTags,
                fetchAuthors: () => fetchPagedAuthors('/api/authors/paged'),
                fetchNovelTags: fetchNovelTags,
                fetchNovelAuthors: () => fetchPagedAuthors('/api/gallery/novels/authors'),
                onSubmit: async (payload) => {
                    const result = await api('/api/admin/invites', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload),
                    });
                    window.InviteModals.openInviteResultModal({ code: result.code, url: result.url });
                }
            });
        }
    })();


// ---- PixivGallery facade ----
window.PixivGallery.init = Object.assign(window.PixivGallery.init || {}, { setupGalleryCrossPageHandoff, loadOnboardingProfile, applyDisplayName, setupGalleryOnboarding });
