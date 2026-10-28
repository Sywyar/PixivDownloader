(function () {
    'use strict';
    const navigation = document.getElementById('home-navigation');
    const status = document.getElementById('home-status');
    const statusText = document.getElementById('home-status-text');
    const retry = document.getElementById('home-retry');
    let client;
    let language;
    let theme;
    let disposed = false;
    let retrying = false;

    function showState(key) {
        status.hidden = !key;
        if (key) {
            statusText.setAttribute('data-i18n', key);
            const fallback = {
                'home.loading': 'Loading your features…',
                'home.empty': 'No features are available. Check your installed plugins in plugin management.',
                'home.error': 'Could not load your features. Please try again.'
            }[key];
            statusText.textContent = client ? client.t(key, fallback) : fallback;
        }
        retry.hidden = key !== 'home.error';
        retry.disabled = key === 'home.loading';
        navigation.setAttribute('aria-busy', String(key === 'home.loading'));
        if (client) client.apply(document);
    }

    window.addEventListener('pixivnav:rendered', function (event) {
        showState(event.detail.items == null ? 'home.error'
            : navigation.children.length ? null : 'home.empty');
        if (retrying) {
            retrying = false;
            const target = event.detail.items == null ? retry : navigation.querySelector('a');
            if (target) target.focus();
        }
    });
    retry.addEventListener('click', function () {
        showState('home.loading');
        retrying = true;
        window.PixivNav.mount();
    });

    async function init() {
        const next = await window.PixivI18n.create({ namespaces: ['common'] });
        if (disposed) { next.dispose(); return; }
        client = next;
        client.apply(document);
        language = await window.PixivLangSwitcher.mount({
            mountPoint: document.getElementById('home-language'),
            i18n: client,
            variant: 'home',
            onChange: function (updated) {
                client = updated;
                client.apply(document);
                window.PixivNav.refresh();
            }
        });
        if (disposed) { language.destroy(); return; }
        theme = window.PixivTheme.mount({ mountPoint: document.getElementById('home-theme'), variant: 'home' });
    }
    window.addEventListener('pagehide', function (event) {
        if (event.persisted) return;
        disposed = true;
        if (language) language.destroy();
        else if (client) client.dispose();
        if (theme) theme.destroy();
    });
    init().catch(function (error) { console.warn('Home language initialization failed', error); });
})();
