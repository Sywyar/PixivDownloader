'use strict';
    // 跨页新手向导（详情页阶段）：逐区域讲解作品图 / 功能区 / 简介区 / 作者区 / 系列相关。
    // 资格闸 /api/onboarding/profile 仅对「全局可见」范围（solo / 已登录管理员）放行。
    async function setupArtworkOnboarding() {
        if (typeof PixivOnboarding === 'undefined') return;
        let eligible = false;
        try {
            const res = await fetch('/api/onboarding/profile', {credentials: 'same-origin'});
            eligible = res.ok;
        } catch (_) { /* 非管理员 / 网络异常：不参与向导 */ }
        PixivOnboarding.boot({
            page: 'artwork',
            i18n: pageI18n,
            eligible: eligible,
            sel: {
                viewer: '#viewerPanel',
                actions: '.viewer-actions',
                author: '#authorPanel',
                detail: '#detailPanel',
                series: '#seriesPanel',
                related: '#relatedPanel'
            }
        });
    }

    (async function initArtworkPage() {
        await initPageI18n();
        setupAdminMode();
        loadArtwork();
        setupArtworkOnboarding();
    })();

// ---- PixivArtwork facade ----
window.PixivArtwork.init = window.PixivArtwork.init || {};
window.PixivArtwork.init = Object.assign(window.PixivArtwork.init, { setupArtworkOnboarding });
