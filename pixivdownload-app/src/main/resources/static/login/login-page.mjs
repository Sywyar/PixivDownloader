import {attachPage, createOperationTarget} from '/js/pixiv-pages.mjs';

const operation = createOperationTarget('core:login', state => {
    if (state.ready) showLoginErrorText('');
}, prepareLoginVerification);
const revision = document.querySelector('meta[name="pixiv-page-revision"]')?.content || 'unavailable';
operation.configure(revision);
let slots, i18n, closed = false;
window.addEventListener('pagehide', () => {
    closed = true; slots?.destroy(); operation.destroy(); i18n?.dispose();
}, {once: true});
const anchor = document.querySelector('[data-ui-slot="login.verification"]');
async function presentFixed(view) {
    await slots?.destroy(); slots = undefined;
    const displayed = new Set();
    function visit(node) {
        if (node.target === 'login.verification' && node.policy) displayed.add(node.policy);
        for (const child of node.children || []) visit(child);
    }
    if (view) visit(view.body);
    document.getElementById('login-btn').before(anchor);
    slots = window.PixivUiSlots.connect({
        targetPrefix: 'login.verification', manifestUrl: '/api/web/page-slots/core%3Alogin/login.verification?revision=' + encodeURIComponent(revision),
        filter: slot => !displayed.has(slot.participation.policy),
        interaction: (slot, signal) => operation.connect({instanceId: slot.slotId, operation: operation.operation, ...slot.participation}, signal),
        i18n: () => i18n, onError: () => showLoginErrorKey('error.login-failed', 'error.login-failed')
    });
    await slots.refresh();
}

const regions = Object.fromEntries([...document.querySelectorAll('[data-page-region]')]
    .map(element => [element.dataset.pageRegion, element]));
const root = document.getElementById('custom-page');
i18n = await window.PixivI18n.create({namespaces: ['login', 'common']});
if (closed) i18n.dispose();
else {
    await initLoginPage(operation);
    if (!closed) await attachPage({
        pageId: 'core:login', root, regions, targets: {'login.verification': operation},
        t: key => i18n.t('common:' + key, key),
        title: value => { window.loginPageTitle = value; document.title = value || lt('page.title', i18n.t('login:page.title')); },
        onMounted: async view => { document.getElementById('native-page').hidden = true; await presentFixed(view); },
        onFallback: async available => {
            await slots?.destroy(); slots = undefined;
            document.getElementById('native-page').hidden = false;
            window.loginPageTitle = ''; document.title = lt('page.title', i18n.t('login:page.title'));
            if (available) await presentFixed();
        }
    });
    if (!closed) setAuthButtonsDisabled(false);
}
