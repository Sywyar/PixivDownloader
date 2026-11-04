import {$, api, t, start, message, failure, motion} from './component-common.mjs';
import {icon} from './editor-icons.mjs';
let entries = [], filter = 'all';
const status = entry => entry.unavailable ? 'unavailable' : !entry.enabled ? 'custom.withdrawn' : entry.latest ? 'custom.published' : 'custom.draft';
const inScope = (entry, scope) => scope === 'all' || scope === 'withdrawn' && !entry.enabled
    || scope === 'draft' && entry.enabled && !entry.latest || scope === 'published' && entry.latest && entry.enabled;
function render() {
    const query = $('component-search').value.trim().toLocaleLowerCase();
    const visible = entries.filter(entry => entry.name.toLocaleLowerCase().includes(query)
        && inScope(entry, filter));
    for (const button of $('component-filter').querySelectorAll('button')) {
        button.setAttribute('aria-pressed', String(button.dataset.filter === filter));
        button.querySelector('[data-count]').textContent = entries.filter(entry => inScope(entry, button.dataset.filter)).length;
    }
    $('library-heading').textContent = t('custom.' + filter);
    $('component-count').textContent = t('custom.count', {count: visible.length});
    $('component-list').replaceChildren();
    for (const entry of visible) {
        const row = document.createElement('li');
        const link = document.createElement('a'); link.className = 'component-row';
        link.href = '/page-editor/component.html?id=' + encodeURIComponent(entry.id);
        const symbol = document.createElement('span'); symbol.className = 'component-symbol'; symbol.innerHTML = icon('component');
        const name = document.createElement('strong'); name.textContent = entry.name;
        const caption = document.createElement('span'); caption.className = 'component-caption';
        caption.textContent = t(entry.unavailable ? 'unavailable' : !entry.enabled ? 'custom.withdrawn-detail' : entry.latest ? 'custom.published-detail' : 'custom.draft-detail');
        const identity = document.createElement('span'); identity.className = 'component-identity'; identity.append(name, caption);
        const state = document.createElement('span'); state.className = 'component-state'; state.textContent = t(status(entry));
        const arrow = document.createElement('span'); arrow.className = 'row-chevron'; arrow.innerHTML = icon('chevron');
        link.append(symbol, identity, state, arrow); row.append(link); $('component-list').append(row);
    }
    $('component-empty').hidden = !!entries.length;
    $('component-no-match').hidden = !entries.length || !!visible.length;
    motion.reveal($('component-list'));
    motion.reveal($('component-empty'));
    motion.reveal($('component-no-match'));
}
async function refresh() {
    $('component-refresh').disabled = true; $('component-list').setAttribute('aria-busy', 'true'); message('working');
    try { entries = await api(''); render(); message(''); }
    catch (error) { failure(error); }
    finally { $('component-refresh').disabled = false; $('component-list').setAttribute('aria-busy', 'false'); }
}
start('custom.library').then(async () => {
    $('component-search').addEventListener('input', render);
    $('component-filter').addEventListener('click', event => {
        const button = event.target.closest('button[data-filter]');
        if (button) { filter = button.dataset.filter; render(); }
    });
    $('component-clear').addEventListener('click', () => { filter = 'all'; $('component-search').value = ''; render(); $('component-search').focus(); });
    $('component-refresh').addEventListener('click', refresh);
    await refresh();
    motion.reveal(document.querySelector('.library-main'));
}).catch(failure);
