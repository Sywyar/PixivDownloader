'use strict';
    window.PixivBatch.layout.applyStoredLayout();

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
        if (window.PixivBatch.modeControls && ['user', 'search'].includes(normalizedMode)) {
            window.PixivBatch.modeControls.render(normalizedMode);
        }
        // 进入带预览的模式时，按当前附加筛选输入重新过滤已加载的预览页（筛选可能在别的模式被改过）
        if (normalizedMode === 'user' && userState.rawItems.length) {
            applyUserFilters({});
        } else if (normalizedMode === 'series' && seriesState.rawItems.length) {
            applySeriesFilters({});
        }
        if (normalizedMode === QUICK_FETCH_MODE) {
            const quickMode = window.PixivBatch && window.PixivBatch.modes && window.PixivBatch.modes.quick;
            if (quickMode && typeof quickMode.renderQuickDataSourceSwitcher === 'function') {
                quickMode.renderQuickDataSourceSwitcher();
            }
            updateQuickAccountBar();
            // 重新按当前附加筛选过滤已加载的快捷获取预览（筛选可能在别的模式被改过）
            if (quickHasWorksGrid()) quickReapplyFilters();
        }
        if (normalizedMode === 'series') {
            const seriesMode = window.PixivBatch && window.PixivBatch.modes && window.PixivBatch.modes.series;
            if (seriesMode && typeof seriesMode.renderSeriesDataSourceSwitcher === 'function') {
                seriesMode.renderSeriesDataSourceSwitcher();
            }
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
        // 下载页扩展点（取得侧 UI 动态装配）：先拉取已启用作品类型、加载其行为模块、把各类型贡献的取得侧
        // 控件（kind 单选 / 设置卡 / 专属筛选 / 入口按钮）注入宿主锚点，使后续 init 的事件绑定 / 设置加载在
        // 「静态 HTML + 已注入槽位」的完整 DOM 上进行（与这些控件曾经写死在 HTML 时同序）。拉取失败 → 不注入
        // 任何插件槽位（插画内置照常）；某类型禁用 → 其槽位缺席（取得侧入口自然消失，残留队列项由 processSingle 暂停）。
        await window.PixivBatch.queueTypes.bootstrap();
        window.PixivBatch.modeControls.bind();
        window.PixivBatch.modeControls.renderAll();
        applyCookieHint();
        updateBatchLimitNote();
        await detectAuthState();
        if (isAdmin && window.PixivBatch.scheduleSources) {
            try {
                await window.PixivBatch.scheduleSources.refresh(false);
            } catch (e) {
                // 来源 manifest 暂不可用时仍可进入页面；任务列表使用持久化 presentation 降级。
            }
        }
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

        document.getElementById('cookie-clear').addEventListener('click', async () => {
            if (!await uiConfirmKey('dialog.confirm-clear-cookie', '确认清除已保存的 Cookie？')) return;
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
        window.PixivBatch.settings.bindDelegatedSettingAutosave(document);
        document.getElementById('s-collection').addEventListener('click', () => {
            refreshBatchCollections();
        });

        // Kind switchers (User / Search)
        bindKindSwitcher('user-kind-switcher', 'userKind', next => {
            const type = window.PixivBatch.queueTypes.resolveSelectionForMode(next, 'user');
            if (type) window.PixivBatch.modeControls.syncType('user', type);
            applyNovelSettingsVisibility();
            // User 模式作品类型变化时，同步共享「附加筛选」里页数/字数字段的显隐
            applySearchKindUI();
            updateSaveScheduleCardVisibility();
            // 切换插画/小说后旧预览结果不再适用，清空避免误导
            clearUserPreview();
        });
        bindKindSwitcher('search-kind-switcher', 'searchKind', next => {
            const type = window.PixivBatch.queueTypes.resolveSelectionForMode(next, 'search');
            if (type) window.PixivBatch.modeControls.syncType('search', type);
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
        window.PixivBatch.modeControls.onChange(
            'user', window.PixivBatch.modes.user.handleUserModeControlChange);
        window.PixivBatch.modeControls.onChange(
            'search', window.PixivBatch.modes.search.handleSearchModeControlChange);
        bindSubmodeSwitcher();
        // 排序变化会影响「翻页到底 -1」搜索是首轮封顶（date_d）还是每轮上限（非 date_d），刷新首次抓取上限提示。
        document.querySelectorAll('input[name="search-order"]').forEach(r =>
            r.addEventListener('change', updateScheduleFetchLimitVisibility));

        // Mode
        const savedMode = normalizeImportMode(storeGet('pixiv_mode') || QUICK_FETCH_MODE);
        state.mode = savedMode;

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
        window.PixivBatch.modes.user.initUserInputDraftPersistence();
        await refreshBatchCollections();
        applyCookieDependentUi();

        // Queue
        loadQueueForMode();
        updateButtonsState();
        updateStats();

        // 把下载队列 / 统计 + 速度 / 当前卡升级为 Vue reactive 岛（加性、幂等、失败回退命令式）：异步懒加载
        // Vue、不阻塞 init；挂载完成后经现有门面（updateStats / renderQueue / setCurrent）回灌当前 state。
        // Vue 不可用 / 加载或挂载失败时各门面继续走命令式渲染。
        if (window.PixivBatch && window.PixivBatch.queueVue) {
            window.PixivBatch.queueVue.mountDownloadQueue();
        }
    }

    document.addEventListener('DOMContentLoaded', async () => {
        await initPageI18n();
        window.PixivBatch.layout.bindLayoutToggle();
        loadAppInfo();
        await init();
        setupOnboardingOrTour();
    });

    async function refreshScheduleSourceManifest() {
        if (!isAdmin || !window.PixivBatch.scheduleSources) return;
        try {
            await window.PixivBatch.scheduleSources.refresh(false);
            updateSaveScheduleCardVisibility();
            if (state.mode === 'schedule') await loadScheduleTasks();
        } catch (e) {
            // 网络或插件重启窗口内保持只读降级，不中断现有页面。
        }
    }

    let queueTypeRefreshInFlight = null;
    function refreshQueueTypeManifest() {
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        if (!queueTypes || typeof queueTypes.refresh !== 'function') return Promise.resolve();
        if (!queueTypeRefreshInFlight) {
            queueTypeRefreshInFlight = queueTypes.refresh(false)
                .catch(() => null)
                .finally(() => { queueTypeRefreshInFlight = null; });
        }
        return queueTypeRefreshInFlight;
    }

    function reconcileQueueTypeUi(event) {
        const ready = !(event && event.detail && event.detail.ready === false);
        const controls = window.PixivBatch && window.PixivBatch.modeControls;
        if (controls && typeof controls.reconcile === 'function') controls.reconcile(ready);
        const settings = window.PixivBatch && window.PixivBatch.settings;
        if (ready && settings && typeof settings.reconcileQueueTypeSettings === 'function') {
            settings.reconcileQueueTypeSettings();
        }
        const modes = window.PixivBatch && window.PixivBatch.modes;
        [
            modes && modes.user && modes.user.reconcileUserTypeAvailability,
            modes && modes.search && modes.search.reconcileSearchTypeAvailability,
            modes && modes.series && modes.series.reconcileSeriesTypeAvailability,
            modes && modes.quick && modes.quick.reconcileQuickTypeAvailability
        ].forEach(reconcile => {
            if (typeof reconcile !== 'function') return;
            try { reconcile(ready); } catch (e) { console.warn('[batch] 作品类型 UI 收敛失败：', e); }
        });
        applyCookieHint();
        updateBatchLimitNote();
        updateButtonsState();
        if (state.mode === QUICK_FETCH_MODE) updateQuickAccountBar();
        refreshPageI18nNamespaces().catch(e => {
            console.warn('[batch] 刷新扩展 i18n namespace 失败：', e);
        });
    }

    window.addEventListener('focus', () => {
        refreshQueueTypeManifest();
        refreshScheduleSourceManifest();
    });
    window.addEventListener('pixivbatch:queuetypeschanged', reconcileQueueTypeUi);
    window.addEventListener('pixivbatch:schedulesourceschanged', () => {
        updateSaveScheduleCardVisibility();
        if (state.mode === 'schedule') loadScheduleTasks();
        refreshPageI18nNamespaces().catch(e => {
            console.warn('[batch] 刷新计划来源 i18n namespace 失败：', e);
        });
    });
    document.addEventListener('change', event => {
        const target = event && event.target;
        if (!target) return;
        if (target.name === 'quick-data-source') {
            const quickMode = window.PixivBatch && window.PixivBatch.modes && window.PixivBatch.modes.quick;
            if (quickMode && typeof quickMode.selectQuickDataSource === 'function') {
                quickMode.selectQuickDataSource(target.value);
            }
            return;
        }
        if (target.name === 'series-data-source') {
            const seriesMode = window.PixivBatch && window.PixivBatch.modes && window.PixivBatch.modes.series;
            if (seriesMode && typeof seriesMode.selectSeriesDataSource === 'function') {
                seriesMode.selectSeriesDataSource(target.value);
            }
        }
    });
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') {
            refreshQueueTypeManifest();
            refreshScheduleSourceManifest();
        }
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
                // Cookie 与油猴脚本卡现折叠进「工具」抽屉：向导高亮抽屉条（始终可见），避免高亮抽屉内的隐藏元素。
                cookieCard: '#tools-drawer',
                scriptsCard: '#tools-drawer',
                tabs: '.tabs',
                singleImportTab: '#tab-single-import',
                importTextarea: '#single-import-textarea',
                importButton: '#btn-single-import',
                filtersCard: '#extra-filters-card',
                settingsCard: '#download-settings-card',
                startButton: '#btn-start',
                progressArea: '#download-progress-area',
                firstDownloadResultEntry: 'a.app-nav-link[data-nav-markers~="first-download-result"]'
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
