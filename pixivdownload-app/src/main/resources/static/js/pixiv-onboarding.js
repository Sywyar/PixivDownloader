/* 跨页新用户引导的稳定公共入口。 */
(function (global) {
    'use strict';

    var ctx = global.PixivOnboardingRuntime;
    if (!ctx || !ctx.overlay || !ctx.download || !ctx.gallery
        || typeof ctx.download.phaseWelcome !== 'function'
        || typeof ctx.gallery.phaseDetail !== 'function') {
        return;
    }

    function boot(cfg) {
        if (!cfg || !cfg.page) {
            return;
        }
        ctx.config = cfg;
        ctx.i18n = cfg.i18n || ctx.i18n;
        ctx.config.sel = ctx.config.sel || {};

        if (!cfg.eligible) {
            return; // 仅 solo / 已登录管理员
        }

        // 下载页常驻「操作指引」FAB（无论是否已完成都可随时重看）
        if (cfg.page === 'batch') {
            ctx.ensureFab();
        }

        var s = ctx.loadState();
        if (s.status === 'completed') {
            ctx.showFab(); // 已完成：仅保留 FAB 供重看，不自动弹
            return;
        }

        if (cfg.page === 'batch') {
            ctx.hideFab(); // 自动运行期间隐藏 FAB
            bootBatch(s);
        } else if (cfg.page === 'gallery') {
            bootGallery(s);
        } else if (cfg.page === 'artwork') {
            bootArtwork(s);
        }
    }

    function bootBatch(s) {
        if (s.status === 'new') {
            ctx.download.phaseWelcome();
            return;
        }
        // 续跑（同标签刷新 / 多标签）
        if (s.phase === 'welcome') {
            ctx.download.phaseWelcome();
        } else if (s.phase === 'download') {
            ctx.download.phaseDownload();
        } else if (s.phase === 'await-gallery') {
            // 已下载完成、等待去画廊：重新提示画廊入口
            ctx.download.monitorSucceeded();
        }
    }

    function bootGallery(s) {
        if (s.status === 'new') {
            ctx.gallery.phaseGalleryRedirect();
            return;
        }
        if (s.phase === 'await-gallery' || s.phase === 'gallery') {
            ctx.gallery.phaseGallery();
        }
        // 其它阶段（welcome/download 正在下载页进行）：画廊不打扰
    }

    function bootArtwork(s) {
        if (s.status !== 'active') {
            return;
        }
        if (s.phase === 'detail' || s.phase === 'gallery' || s.phase === 'await-gallery') {
            ctx.patchState({phase: 'detail'});
            ctx.gallery.phaseDetail();
        }
    }

    global.PixivOnboarding = {
        boot: boot,
        restart: ctx.restart,
        refreshFab: function (client) {
            if (client) {
                ctx.i18n = client;
            }
            ctx.refreshFabLabel();
        },
        EXAMPLE_ID: ctx.EXAMPLE_ID,
        EXAMPLE_URL: ctx.EXAMPLE_URL,
        getName: function () {
            return ctx.loadState().name || '';
        }
    };
})(window);
