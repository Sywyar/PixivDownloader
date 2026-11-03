import {attachPage} from '/js/pixiv-pages.mjs';
const i18n = await window.PixivI18n.create({namespaces: ['operation-form', 'common']});
const regions = Object.fromEntries([...document.querySelectorAll('[data-page-region]')].map(node => [node.dataset.pageRegion, node]));
for (const form of document.querySelectorAll('form[data-target]')) {
    const output = form.querySelector('output'), button = form.querySelector('button');
    form.elements.value.addEventListener('input', () => { output.textContent = ''; });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (button.disabled) return;
        button.disabled = true;
        try {
            const response = await fetch('/api/operation-form/' + form.dataset.target, {method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({value: form.elements.value.value})});
            const result = await response.json();
            output.dataset.status = response.ok ? 'APPROVED' : 'ERROR';
            output.textContent = result.error || i18n.t('operation-form:status.' + output.dataset.status);
        } catch (_) { output.dataset.status = 'UNAVAILABLE'; output.textContent = i18n.t('operation-form:status.UNAVAILABLE'); }
        finally { button.disabled = false; }
    });
}
await attachPage({pageId: document.body.dataset.pageId || 'operation-form:page', root: document.querySelector('#published'), regions,
    title: value => { document.title = value || i18n.t('operation-form:name'); },
    t: key => i18n.t('common:' + key),
    onMounted: () => { document.querySelector('#native').hidden = true; },
    onFallback: () => { document.querySelector('#native').hidden = false; }});
window.addEventListener('pagehide', () => i18n.dispose(), {once: true});
