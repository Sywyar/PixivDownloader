'use strict';
/* ============================================================
   alt-init — 页面装配（最后加载）
   ============================================================ */
let pageLangSwitcher = null;

function applyPageLanguageViews(nextClient) {
    pageI18n = nextClient || pageI18n;
    if (pageI18n) pageI18n.apply();
    document.title = bt('page.title', '下载工作台 · Pixiv 下载助手');
    renderRail();
    renderStage();
    renderDock();
    renderAuthButton();
    refreshCookieUi();
    renderBackendBanner();
    syncFilterButtonBadge();
    refreshGuideFab();
    if (window.PixivLayoutFeedback && typeof window.PixivLayoutFeedback.refreshLanguage === 'function') {
        try {
            window.PixivLayoutFeedback.refreshLanguage(pageI18n);
        } catch (e) {
            console.warn('[batch-alt] 布局偏好调查语言刷新失败：', e);
        }
    }
}

async function initPageI18n() {
    try {
        pageI18n = await PixivI18n.create({namespaces: ['batch-alt', 'batch', 'common', 'tour', 'layout-feedback']});
    } catch {
        pageI18n = null;
    }
    if (pageI18n) {
        pageI18n.apply();
        document.title = bt('page.title', '下载工作台 · Pixiv 下载助手');
    }
    const langAnchor = document.getElementById('abLangAnchor');
    if (langAnchor && window.PixivLangSwitcher && pageI18n) {
        try {
            pageLangSwitcher = await PixivLangSwitcher.mount({
                mountPoint: langAnchor,
                i18n: pageI18n,
                variant: 'topbar',
                onChange: applyPageLanguageViews
            });
        } catch (e) {
            console.warn('[batch-alt] 语言切换挂载失败：', e);
        }
    }
    const themeAnchor = document.getElementById('abThemeAnchor');
    if (themeAnchor && window.PixivTheme) {
        try {
            PixivTheme.mount({
                mountPoint: themeAnchor,
                variant: 'topbar',
                titleDark: bt('theme.to-light', '切换到浅色模式'),
                titleLight: bt('theme.to-dark', '切换到深色模式')
            });
        } catch (e) {
            console.warn('[batch-alt] 主题切换挂载失败：', e);
        }
    }
}

function setupTour(auto) {
    if (typeof PixivTour === 'undefined') return;
    PixivTour.init({
        pageKey: 'batch',
        i18n: pageI18n,
        auto,
        steps: [
            {target: '#abCookieChip', titleKey: 'tour:batch.cookie.title', bodyKey: 'tour:batch.cookie.body'},
            {target: '#abRail', titleKey: 'tour:batch.mode.title', bodyKey: 'tour:batch.mode.body'},
            {target: '#abBtnStart', titleKey: 'tour:batch.start.title', bodyKey: 'tour:batch.start.body'},
            {target: '#abDock', titleKey: 'tour:batch.queue.title', bodyKey: 'tour:batch.queue.body'},
            {
                target: 'a.ab-topnav-link[data-nav-markers~="first-download-result"]',
                titleKey: 'tour:batch.gallery.title',
                bodyKey: 'tour:batch.gallery.body'
            }
        ]
    });
}

async function setupOnboardingOrTour() {
    const eligible = appMode === 'solo' || isAdmin;
    if (eligible && typeof PixivOnboarding !== 'undefined') {
        let savedName = '';
        try {
            const res = await fetch('/api/onboarding/profile', {credentials: 'same-origin'});
            if (res.ok) {
                const data = await res.json();
                savedName = (data && data.displayName) || '';
            }
        } catch {}
        PixivOnboarding.boot(buildOnboardingConfig(savedName));
        return;
    }
    setupTour(false);
    const controller = typeof PixivTour !== 'undefined' && PixivTour.get('batch');
    if (controller) controller.start(false);
}

function refreshGuideFab() {
    const eligible = appMode === 'solo' || isAdmin;
    if (eligible && typeof PixivOnboarding !== 'undefined') {
        PixivOnboarding.refreshFab(pageI18n);
    } else {
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
            cookieCard: '#abCookieChip',
            scriptsCard: '#abScriptsBtn',
            tabs: '#abRail',
            singleImportTab: '#abRailModes [data-mode="single-import"]',
            importTextarea: '#abImportInput',
            importButton: '#abBtnImport',
            filtersCard: '#abFilterBtn',
            settingsCard: '#abSettingsBtn',
            startButton: '#abBtnStart',
            progressArea: '#abDock',
            firstDownloadResultEntry: 'a.ab-topnav-link[data-nav-markers~="first-download-result"]'
        },
        hooks: {
            switchToSingleImport: () => switchMode(SINGLE_IMPORT_MODE),
            hasLoginCookie: () => cookieHasPhpsessid(),
            isExampleQueued: id => state.queue.some(item => String(item.id) === String(id)),
            beforeStart: () => openDock(),
            isRunning: () => state.isRunning,
            applyName: () => {}
        }
    };
}

async function init() {
    hydrateIcons(document);
    await initPageI18n();

    // 基础环境：运行模式 → 登录态 → 后端可达性 → solo 服务器状态
    await detectMode();
    await detectAuthState();
    chromeState.backendAvailable = await checkBackend();
    if (appMode === 'solo') {
        await loadServerState();
    }

    loadSettings();
    loadSearchFilterPrefs();
    const blurPref = storeGet('pixiv_search_blur_r18');
    if (blurPref !== null) searchState.blurR18 = blurPref === 'true';

    bindChrome();
    loadAppInfo();
    renderAuthButton();
    refreshCookieUi();
    await refreshBatchCollections();
    await bootstrapAltExtensions();
    if (pageLangSwitcher && typeof pageLangSwitcher.refresh === 'function') {
        pageLangSwitcher.refresh(pageI18n);
    }

    // 队列与坞
    loadQueueForMode();
    renderDock();
    if (appMode === 'multi') {
        await initQuota();
    }

    // 模式（恢复上次；计划任务仅管理员）
    let savedMode = storeGet('pixiv_mode') || QUICK_FETCH_MODE;
    if (!AB_MODES.some(m => m.id === savedMode)) savedMode = QUICK_FETCH_MODE;
    if (savedMode === 'schedule' && !isAdmin) savedMode = QUICK_FETCH_MODE;
    state.mode = savedMode;
    renderRail();
    renderStage();
    if (savedMode === 'schedule') enterScheduleMode();

    renderBackendBanner();
    syncFilterButtonBadge();
    window.addEventListener('resize', debounce(moveRailIndicator, 120));
    try {
        window.dispatchEvent(new CustomEvent('pixivbatchalt:ready'));
    } catch {}

    setupOnboardingOrTour();
    // 布局偏好调查（PostHog API Survey）：不阻塞核心初始化；内部自行延迟与门禁
    if (window.PixivLayoutFeedback && typeof window.PixivLayoutFeedback.init === 'function') {
        try {
            window.PixivLayoutFeedback.init({page: 'alt', i18n: pageI18n});
        } catch (e) {
            console.warn('[batch-alt] 布局偏好调查初始化失败：', e);
        }
    }
}

document.addEventListener('DOMContentLoaded', () => {
    init().catch(e => console.error('[batch-alt] 初始化失败：', e));
});
