/* 页面提供业务步骤，共享入口只管理当前页面的引导会话。 */
(function (global) {
    'use strict';

    var ctx = global.PixivOnboardingRuntime;
    if (!ctx || !ctx.overlay) return;
    var suspendedConfig = null;
    var pageHidden = false;

    function dispose() {
        ctx.overlay.destroy();
        ctx.unbindRestartButton();
        ctx.config = null;
        ctx.completionStepNotified = false;
    }

    function boot(cfg) {
        suspendedConfig = null;
        dispose();
        if (!cfg || !cfg.eligible || typeof cfg.start !== 'function') return;
        if (pageHidden) {
            suspendedConfig = cfg;
            return;
        }
        ctx.config = cfg;
        ctx.config.sel = cfg.sel || {};
        ctx.i18n = cfg.i18n || ctx.i18n;
        ctx.bindRestartButton();
        var state = ctx.loadState();
        ctx.setRestartEnabled(state.status === 'completed');
        if (state.status !== 'completed') cfg.start(state);
    }

    function refreshTranslations(client) {
        if (client) ctx.i18n = client;
        ctx.refreshRestartLabel();
        ctx.overlay.refreshTranslations();
    }

    global.addEventListener('pagehide', function () {
        pageHidden = true;
        suspendedConfig = ctx.config;
        dispose();
    });
    global.addEventListener('pageshow', function (event) {
        pageHidden = false;
        if (event.persisted && suspendedConfig) boot(suspendedConfig);
    });

    global.PixivOnboarding = {
        boot: boot,
        restart: ctx.restart,
        dispose: function () { suspendedConfig = null; dispose(); },
        refreshFab: refreshTranslations,
        getName: function () { return ctx.loadState().name || ''; }
    };
})(window);
