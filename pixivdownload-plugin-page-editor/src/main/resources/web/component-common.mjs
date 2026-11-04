import {decorateIcons} from './editor-icons.mjs';
import {createViewMotion} from './view-motion.mjs';
import {createEditorFeedback, errorMessage} from './editor-feedback.mjs';
export const $ = id => document.getElementById(id);
export const lifetime = new AbortController();
export const motion = createViewMotion(lifetime.signal);
let i18n;
export const t = (key, values) => i18n?.t('page-editor:' + key, key, values) || key;
const notify = createEditorFeedback(lifetime.signal, t);
export async function api(path, data) {
    const response = await fetch('/api/page-editor/components' + path, {
        method: data === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: data === undefined ? {} : {'Content-Type': 'application/json'},
        body: data === undefined ? undefined : JSON.stringify(data), signal: lifetime.signal
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.code || 'PAGE_OPERATION_FAILED');
    return result;
}
export function message(key, code = '') {
    const text = code ? errorMessage(code, t) : key ? t(key) : '';
    $('custom-message').textContent = text;
    $('custom-message').dataset.state = ['failed', 'conflict'].includes(key) ? 'error'
        : key === 'working' ? 'busy' : ['saved', 'custom.published', 'custom.withdrawn', 'custom.version-loaded'].includes(key) ? 'success' : 'idle';
    notify($('custom-message').dataset.state, text);
}
export function failure(error) {
    if (error.name !== 'AbortError') message(error.message === 'PAGE_REVISION_CONFLICT' ? 'conflict' : 'failed', error.message);
}
export const confirmAction = key => window.PixivFeedback.confirm({message: t(key), confirmLabel: t('confirm'), cancelLabel: t('cancel')});
export function tabs(root, select) {
    const buttons = [...root.querySelectorAll('[role=tab]')];
    const activate = button => {
        if (button.getAttribute('aria-selected') === 'true') return;
        buttons.forEach(item => { item.setAttribute('aria-selected', String(item === button)); item.tabIndex = item === button ? 0 : -1; });
        select(button.dataset.tab);
        motion.reveal($(button.getAttribute('aria-controls')));
    };
    buttons.forEach((button, index) => {
        button.addEventListener('click', () => activate(button));
        button.addEventListener('keydown', event => {
            if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
            event.preventDefault();
            const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
                : (index + (['ArrowRight', 'ArrowDown'].includes(event.key) ? 1 : -1) + buttons.length) % buttons.length;
            activate(buttons[next]); buttons[next].focus();
        });
    });
    activate(buttons[0]);
}
export async function start(title) {
    i18n = await window.PixivI18n.create({namespaces: ['page-editor', 'common'], signal: lifetime.signal});
    i18n.apply(document.body, 'page-editor'); document.title = t(title);
    decorateIcons(document.body);
    window.PixivTheme?.mount({mountPoint: $('preferences')});
    await window.PixivLangSwitcher?.mount({mountPoint: $('preferences'), variant: 'editor', i18n, onChange: () => location.reload()});
    window.addEventListener('pagehide', () => { lifetime.abort(); i18n.dispose(); }, {once: true});
}
