import {attachPage, createOperationTarget} from '/js/pixiv-pages.mjs';
const targets = Object.fromEntries(['alpha', 'beta'].map(id => [id, createOperationTarget('operation-form:' + id, undefined, async signal => {
    const form = document.querySelector('form[data-target="' + id + '"]');
    const response = await fetch('/api/operation-form/' + id + '/prepare', {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({value: form.elements.value.value}), signal});
    const result = await response.json();
    if (!['APPROVED', 'CHALLENGE'].includes(result.status)) throw new Error(result.status);
    return result;
})]));
const revision = document.querySelector('meta[name="pixiv-page-revision"]')?.content || 'unavailable';
for (const target of Object.values(targets)) target.configure(revision);
const i18n = await window.PixivI18n.create({namespaces: ['operation-form', 'common']});
const regions = Object.fromEntries([...document.querySelectorAll('[data-page-region]')].map(node => [node.dataset.pageRegion, node]));
for (const form of document.querySelectorAll('form[data-target]')) {
    const target = targets[form.dataset.target], output = form.querySelector('output'), button = form.querySelector('button');
    form.elements.value.addEventListener('input', () => { target.reset(); output.textContent = ''; });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (button.disabled) return;
        button.disabled = true;
        try {
            await target.prepare();
            if (!target.ready()) {
                output.dataset.status = 'CHALLENGE'; output.textContent = i18n.t('operation-form:status.CHALLENGE'); return;
            }
            const state = target.request();
            const response = await fetch('/api/operation-form/' + form.dataset.target, {method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({value: form.elements.value.value, ...state})});
            const result = await response.json();
            target.reset();
            output.dataset.status = result.status;
            output.textContent = i18n.t('operation-form:status.' + result.status);
        } catch (_) { target.reset(); output.dataset.status = 'UNAVAILABLE'; output.textContent = i18n.t('operation-form:status.UNAVAILABLE'); }
        finally { button.disabled = false; }
    });
}
await attachPage({pageId: 'operation-form:page', root: document.querySelector('#published'), regions, targets,
    title: value => { document.title = value || i18n.t('operation-form:name'); },
    t: key => i18n.t('common:' + key),
    onMounted: () => { document.querySelector('#native').hidden = true; },
    onFallback: () => { document.querySelector('#native').hidden = false; }});
window.addEventListener('pagehide', () => { Object.values(targets).forEach(target => target.destroy()); i18n.dispose(); }, {once: true});
