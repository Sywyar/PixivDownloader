'use strict';
    /* ============================================================
       UI
    ============================================================ */
    function switchMode(mode) {
        let normalizedMode = normalizeImportMode(mode);
        // 计划任务仅管理员可进入；非管理员请求时回退到默认模式
        if (normalizedMode === 'schedule' && !isAdmin) normalizedMode = QUICK_FETCH_MODE;
        state.mode = normalizedMode;
        [QUICK_FETCH_MODE, SINGLE_IMPORT_MODE, 'user', 'search', 'series', 'schedule'].forEach(m => {
            const tab = document.getElementById('tab-' + m);
            const panel = document.getElementById('panel-' + m);
            if (tab) tab.classList.toggle('active', m === normalizedMode);
            if (panel) panel.classList.toggle('active', m === normalizedMode);
        });
        // 计划任务模式下隐藏共享的下载设置 / 队列工作台
        const workbench = document.getElementById('download-workbench');
        if (workbench) workbench.style.display = (normalizedMode === 'schedule') ? 'none' : '';
        storeSet('pixiv_mode', normalizedMode);
        applyNovelSettingsVisibility();
        updateSaveScheduleCardVisibility();
        updateExtraFiltersCardVisibility();
        // 进入带预览的模式时，按当前附加筛选输入重新过滤已加载的预览页（筛选可能在别的模式被改过）
        if (normalizedMode === 'user' && userState.rawItems.length) {
            applyUserFilters({});
        } else if (normalizedMode === 'series' && seriesState.rawItems.length) {
            applySeriesFilters({});
        }
        if (normalizedMode === QUICK_FETCH_MODE) {
            updateQuickAccountBar();
            // 重新按当前附加筛选过滤已加载的快捷获取预览（筛选可能在别的模式被改过）
            if (quickHasWorksGrid()) quickReapplyFilters();
        }
        if (normalizedMode === 'schedule') {
            loadScheduleTasks();
            startSchedulePolling();
        } else {
            stopSchedulePolling();
        }
    }

    /* ============================================================
       初始化
    ============================================================ */
    async function init() {
        // 检测使用模式，solo 模式则从服务器加载状态
        await detectMode();
        applyCookieHint();
        updateBatchLimitNote();
        await detectAuthState();
        // 多人模式：初始化配额状态
        if (appMode === 'multi') {
            await initQuota();
        }
        updateAuthButtons();
        if (appMode === 'solo') {
            await loadServerState();
            applyCookieHint();
        }

        // Cookie
        const savedCookie = storeGet('pixiv_cookie') || '';
        const cookieInput = document.getElementById('cookie-input');
        cookieInput.value = savedCookie;
        cookieInput.addEventListener('input', applyCookieDependentUi);
        setCookieFmt(getCookieFmt());

        document.getElementById('cookie-save').addEventListener('click', () => {
            const raw = document.getElementById('cookie-input').value.trim();
            const result = validateAndParseCookie(raw, getCookieFmt());
            if (!result.ok) {
                setCookieStatus(bt('status.cookie-save-failed', 'Cookie 保存失败：{message}', {message: result.error}), 'error');
                return;
            }
            storeSet('pixiv_cookie', raw);
            if (result.warnings.length) {
                setCookieStatus(
                    bt('status.cookie-saved-warning', 'Cookie 已保存（{count} 个字段）⚠ {warnings}', {
                        count: result.count,
                        warnings: result.warnings.join(uiLang() === 'en-US' ? '; ' : '；')
                    }),
                    'warning'
                );
            } else {
                setCookieStatus(bt('status.cookie-saved', 'Cookie 已保存，共 {count} 个字段', {count: result.count}), 'success');
            }
            applyCookieDependentUi();
        });

        document.getElementById('cookie-toggle').addEventListener('click', () => {
            const inp = document.getElementById('cookie-input');
            if (inp.type === 'password') {
                inp.type = 'text';
            } else {
                inp.type = 'password';
            }
            syncCookieToggleLabel();
        });

        document.getElementById('cookie-clear').addEventListener('click', () => {
            if (!uiConfirmKey('dialog.confirm-clear-cookie', '确认清除已保存的 Cookie？')) return;
            storeRemove('pixiv_cookie');
            document.getElementById('cookie-input').value = '';
            setCookieStatus(bt('status.cookie-cleared', 'Cookie 已清除'), 'success');
            applyCookieDependentUi();
        });

        // 一键导入 Cookie：仅 solo 模式可用（依赖服务器端 /api/batch/state 中转）
        const cookieImportBtn = document.getElementById('cookie-import');
        const cookieImportHint = document.getElementById('cookie-import-hint');
        if (cookieImportBtn) {
            if (appMode === 'solo') {
                cookieImportBtn.hidden = false;
                cookieImportBtn.addEventListener('click', importCookieViaScript);
                if (cookieImportHint) cookieImportHint.hidden = false;
            } else {
                cookieImportBtn.hidden = true;
                if (cookieImportHint) cookieImportHint.hidden = true;
            }
        }

        // Settings change → auto-save
        ['s-interval', 's-image-delay', 's-concurrent', 's-skip', 's-verify-files', 's-redownload-deleted', 's-bookmark', 's-collection', 's-file-name-template', 's-novel-format', 's-novel-merge', 's-novel-merge-format', 's-novel-auto-translate', 's-novel-translate-lang', 's-novel-translate-seg'].forEach(id => {
            const el = document.getElementById(id);
            if (!el) return;
            el.addEventListener('change', syncSettings);
            if (el.type === 'number' || el.type === 'text') el.addEventListener('input', syncSettings);
        });
        document.getElementById('s-collection').addEventListener('click', () => {
            refreshBatchCollections();
        });

        // Kind switchers (User / Search)
        bindKindSwitcher('user-kind-switcher', 'userKind', () => {
            applyNovelSettingsVisibility();
            // User 模式作品类型变化时，同步共享「附加筛选」里页数/字数字段的显隐
            applySearchKindUI();
            // 切换插画/小说后旧预览结果不再适用，清空避免误导
            clearUserPreview();
        });
        bindKindSwitcher('search-kind-switcher', 'searchKind', () => {
            applySearchKindUI();
            applyNovelSettingsVisibility();
            // 切换 kind 后清掉旧结果，避免误导
            cleanupBlobUrls();
            searchState.rawResults = [];
            searchState.results = [];
            searchState.total = 0;
            searchState.currentWord = '';
            searchState.batchInfo = null;
            searchState.localPage = 1;
            searchState.filterSeq++;
            renderSearchResults();
            renderSearchPagination();
            document.getElementById('btn-add-all').disabled = true;
            updateBatchQueueButtons();
        });
        bindSubmodeSwitcher();
        // 排序变化会影响「翻页到底 -1」搜索是首轮封顶（date_d）还是每轮上限（非 date_d），刷新首次抓取上限提示。
        document.querySelectorAll('input[name="search-order"]').forEach(r =>
            r.addEventListener('change', updateScheduleFetchLimitVisibility));

        // Mode
        const savedMode = normalizeImportMode(storeGet('pixiv_mode') || QUICK_FETCH_MODE);
        state.mode = savedMode;

        // 在 switchMode 前先恢复 userId，否则 user 模式下 storageKey() 用空串找不到队列
        if (savedMode === 'user') {
            const savedUserId = storeGet('pixiv_batch_last_user_id') || '';
            const savedUsername = storeGet('pixiv_batch_last_username') || '';
            if (savedUserId) {
                state.userId = savedUserId;
                state.username = savedUsername;
                document.getElementById('user-id-input').value = savedUserId;
                document.getElementById('user-info-display').textContent =
                    savedUsername
                        ? bt('status.user-display', '用户：{name}（ID: {id}）', {name: savedUsername, id: savedUserId})
                        : bt('status.user-display-id', 'ID: {id}', {id: savedUserId});
            }
        }

        if (savedMode === 'search') {
            const blurPref = storeGet('pixiv_search_blur_r18');
            if (blurPref !== null) {
                searchState.blurR18 = blurPref === 'true';
                document.getElementById('search-blur-r18').checked = searchState.blurR18;
            }
        }
        loadSearchFilterPrefs();
        loadSubmodePref();
        // 初始化时按触发方式下拉框的当前选择只渲染对应输入框（否则周期与 Cron 两个输入框会同时出现）
        onScheduleTriggerChange();

        switchMode(savedMode);

        // Settings
        loadSettings();
        await refreshBatchCollections();
        applyCookieDependentUi();

        // Queue
        loadQueueForMode();
        updateButtonsState();
        updateStats();
    }

    document.addEventListener('DOMContentLoaded', async () => {
        await initPageI18n();
        loadAppInfo();
        await init();
        setupOnboardingOrTour();
    });

    // 有「全局可见」权限（solo / 已登录管理员）的用户走新用户跨页向导：首次自动跑，右下角 💡「操作指引」
    // FAB 由向导自身注册（点击重跑，已保存称呼则直接跳到连通性检测）。多人模式访客仍保留旧版 PixivTour
    // 的首次自动指引与 💡 FAB。
    async function setupOnboardingOrTour() {
        const eligible = (appMode === 'solo') || isAdmin;
        if (eligible && typeof PixivOnboarding !== 'undefined') {
            // 读取已保存的称呼（服务端权威值），供向导决定是否跳过称呼步
            let savedName = '';
            try {
                const res = await fetch('/api/onboarding/profile', {credentials: 'same-origin'});
                if (res.ok) {
                    const data = await res.json();
                    savedName = (data && data.displayName) || '';
                }
            } catch (_) { /* best-effort */ }
            PixivOnboarding.boot(buildOnboardingConfig(savedName));
        } else {
            setupTour(false); // 访客：仅注册旧版 💡 FAB，不自动弹
            if (typeof PixivTour !== 'undefined') {
                const ctrl = PixivTour.get('batch');
                if (ctrl) ctrl.start(false); // 访客：旧版首次自动指引（尊重已看标记）
            }
        }
    }

    // 语言切换后刷新右下角指引 FAB 文案：有资格用户刷新新手向导 FAB，访客刷新旧版 PixivTour FAB。
    function refreshGuideFab() {
        const eligible = (appMode === 'solo') || isAdmin;
        if (eligible && typeof PixivOnboarding !== 'undefined') {
            PixivOnboarding.refreshFab(pageI18n);
        } else if (typeof PixivTour !== 'undefined') {
            setupTour(false);
        }
    }

    function buildOnboardingConfig(savedName) {
        return {
            page: 'batch',
            i18n: pageI18n,
            eligible: true,
            savedName: savedName || '',
            sel: {
                cookieCard: '#cookie-card',
                scriptsCard: '#userscripts-card',
                tabs: '.tabs',
                singleImportTab: '#tab-single-import',
                importTextarea: '#single-import-textarea',
                importButton: '#btn-single-import',
                filtersCard: '#extra-filters-card',
                settingsCard: '#download-settings-card',
                startButton: '#btn-start',
                progressArea: '#download-progress-area',
                galleryNav: 'a.app-nav-link[href*="pixiv-gallery"]'
            },
            hooks: {
                switchToSingleImport: () => switchMode(SINGLE_IMPORT_MODE),
                hasLoginCookie: () => cookieHasPhpsessid(),
                isExampleQueued: (id) => state.queue.some(q => String(q.id) === String(id)),
                isRunning: () => state.isRunning,
                applyName: () => { /* 下载页暂无称呼占位，称呼已持久化到服务端 */ }
            }
        };
    }


// ---- PixivBatch facade ----
window.PixivBatch.tabs = window.PixivBatch.tabs || {};
window.PixivBatch.tabs = Object.assign(window.PixivBatch.tabs, { switchMode });
