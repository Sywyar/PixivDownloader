'use strict';
(async () => {
    try {
        pageI18n = await PixivI18n.create({ namespaces: ['invite', 'common'] });
        window.PixivInvitesI18n = pageI18n;
        if (pageI18n.apply) pageI18n.apply();
    } catch (_) {}
    if (!inviteId) {
        document.getElementById('wrap').innerHTML =
            '<div class="card"><div class="error">'
            + escapeHtml(tr('invite:detail.missing-id', '缺少 ?id 参数'))
            + '</div></div>';
        return;
    }
    if (window.PixivLangSwitcher && window.PixivLangSwitcher.mount && pageI18n) {
        try {
            await window.PixivLangSwitcher.mount({
                mountPoint: document.getElementById('langSwitcherAnchor'),
                i18n: pageI18n,
                onChange: client => { pageI18n = client; window.PixivInvitesI18n = client; render(); }
            });
        } catch (_) {}
    }
    if (window.PixivTheme && window.PixivTheme.mount) {
        try { window.PixivTheme.mount({ mountPoint: document.getElementById('langSwitcherAnchor') }); } catch (_) {}
    }
    load();
})();
