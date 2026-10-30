import {attachPage} from '/js/pixiv-pages.mjs';

const regions = Object.fromEntries([...document.querySelectorAll('[data-page-region]')]
    .map(element => [element.dataset.pageRegion, element]));
const root = document.getElementById('custom-page');
const resetButton = document.getElementById('default-page');
const i18n = await window.PixivI18n.create({namespaces: ['login', 'common']});
window.addEventListener('pagehide', () => i18n.dispose(), {once: true});
resetButton.textContent = i18n.t('login:page.default');
await attachPage({
    pageId: 'core:login', root, regions, resetButton,
    t: key => i18n.t('common:' + key, key),
    title: value => { window.loginPageTitle = value; document.title = value; },
    onMounted: () => { document.getElementById('native-page').hidden = true; },
    onFallback: () => {
        document.getElementById('native-page').hidden = false;
        window.loginPageTitle = ''; document.title = i18n.t('login:page.title');
    }
});
