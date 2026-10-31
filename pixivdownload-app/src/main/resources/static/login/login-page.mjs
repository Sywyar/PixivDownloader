import {attachPage} from '/js/pixiv-pages.mjs';

const regions = Object.fromEntries([...document.querySelectorAll('[data-page-region]')]
    .map(element => [element.dataset.pageRegion, element]));
const root = document.getElementById('custom-page');
const i18n = await window.PixivI18n.create({namespaces: ['login', 'common']});
window.addEventListener('pagehide', () => i18n.dispose(), {once: true});
await attachPage({
    pageId: 'core:login', root, regions,
    t: key => i18n.t('common:' + key, key),
    title: value => { window.loginPageTitle = value; document.title = value || lt('page.title', i18n.t('login:page.title')); },
    onMounted: () => { document.getElementById('native-page').hidden = true; },
    onFallback: () => {
        document.getElementById('native-page').hidden = false;
        window.loginPageTitle = ''; document.title = lt('page.title', i18n.t('login:page.title'));
    }
});
