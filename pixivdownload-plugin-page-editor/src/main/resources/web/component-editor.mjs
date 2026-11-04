import {mountFrame} from '/page-components/runtime.mjs';
import {$, api, t, start, message, failure, confirmAction, tabs, lifetime, motion} from './component-common.mjs';

let state, cm, view, active = 'html', baseline = '', busy = false, preview, previewToken, previewSlot;
let editorStates = {};
const propertyRows = [];
const codeTabs = ['html', 'css', 'javascript'];
function source() {
    if (view) editorStates[active] = view.state;
    const properties = Object.fromEntries(propertyRows.map(row => [row.name.value, {type: row.type.value, label: row.label.value,
        default: row.type.value === 'boolean' ? row.value.checked : row.type.value === 'number' ? row.value.valueAsNumber : row.value.value}]));
    return {name: $('custom-name').value, ...Object.fromEntries(codeTabs.map(tab => [tab, editorStates[tab].doc.toString()])), properties};
}
function dirty() { return !!state && JSON.stringify(source()) !== baseline; }
function syncSaveState() {
    const unsaved = dirty();
    $('custom-dirty').setAttribute('aria-hidden', String(!unsaved));
    $('custom-name').setAttribute('aria-describedby', unsaved ? 'custom-dirty' : '');
    motion.text($('custom-save-state'), t(unsaved ? 'unsaved' : state?.id ? 'saved' : 'custom.draft'));
}
function changed() {
    syncSaveState();
    if (preview) motion.text($('custom-preview-state'), t('custom.preview-stale'));
}
function valid() {
    if (!$('custom-name').reportValidity()) return false;
    const names = new Set();
    for (const row of propertyRows) {
        row.name.setCustomValidity(names.has(row.name.value) ? t('custom.property-duplicate') : ''); names.add(row.name.value);
        for (const field of [row.name, row.label, row.value]) if (!field.checkValidity()) {
            $('section-properties').click(); field.reportValidity(); return false;
        }
    }
    return true;
}
async function run(work) {
    if (busy || !state) return;
    const focus = document.activeElement;
    busy = true; $('component-fields').inert = true;
    $('custom-name').disabled = true;
    $('custom-save').disabled = true; $('custom-publish').disabled = true;
    $('component-fields').setAttribute('aria-busy', 'true'); message('working');
    try { await work(); }
    catch (error) { failure(error); }
    finally {
        busy = false; $('component-fields').inert = false;
        $('custom-name').disabled = false;
        $('custom-save').disabled = false; $('custom-publish').disabled = false;
        $('component-fields').setAttribute('aria-busy', 'false');
        if (focus?.isConnected) focus.focus({preventScroll: true});
    }
}
function editState(tab, doc) {
    const language = tab === 'html' ? cm.html() : tab === 'javascript' ? cm.javascript() : cm.cssLanguage;
    return cm.EditorState.create({doc, extensions: [cm.lineNumbers(), cm.history(), cm.drawSelection(), cm.highlightActiveLine(),
        cm.bracketMatching(), cm.closeBrackets(), language, cm.autocompletion(), cm.indentOnInput(), cm.EditorView.lineWrapping,
        cm.syntaxHighlighting(cm.HighlightStyle.define([
            {tag: [cm.tags.keyword, cm.tags.tagName, cm.tags.propertyName], class: 'css-token-property'},
            {tag: [cm.tags.number, cm.tags.bool, cm.tags.string], class: 'css-token-value'},
            {tag: cm.tags.comment, class: 'css-token-muted'}
        ])), cm.keymap.of([...cm.closeBracketsKeymap, ...cm.completionKeymap, ...cm.defaultKeymap, ...cm.historyKeymap]),
        cm.EditorView.contentAttributes.of({'aria-label': t('custom.' + tab), spellcheck: 'false'}),
        cm.EditorView.updateListener.of(update => { if (update.docChanged) changed(); })]});
}
function showCode(tab) {
    if (view) editorStates[active] = view.state;
    active = tab;
    if (view) view.setState(editorStates[tab]);
    else view = new cm.EditorView({parent: $('custom-code'), state: editorStates[tab]});
    $('custom-code').setAttribute('aria-labelledby', 'custom-tab-' + tab);
    $('custom-source-help').textContent = t('custom.help-' + tab);
}
function addProperty(name = '', property = {type: 'string', label: '', default: ''}) {
    const element = document.createElement('div'); element.className = 'property-row';
    const row = {element};
    function field(key, input) {
        const label = document.createElement('label'); label.className = 'editor-field';
        const span = document.createElement('span'); span.textContent = t('custom.property-' + key);
        label.append(span, input); element.append(label); row[key] = input;
    }
    field('name', document.createElement('input')); row.name.value = name; row.name.required = true;
    row.name.pattern = '[A-Za-z][A-Za-z0-9_\\-]*'; row.name.maxLength = 64;
    field('label', document.createElement('input')); row.label.value = property.label; row.label.maxLength = 128;
    field('type', document.createElement('select'));
    for (const type of ['string', 'number', 'boolean']) { const option = document.createElement('option'); option.value = type; option.textContent = t('custom.type-' + type); row.type.append(option); }
    row.type.value = property.type;
    field('value', document.createElement('input'));
    function setValue(value) {
        row.value.type = row.type.value === 'boolean' ? 'checkbox' : row.type.value === 'number' ? 'number' : 'text';
        row.value.required = row.type.value === 'number'; row.value.step = 'any';
        if (row.type.value === 'boolean') row.value.checked = value === true;
        else row.value.value = String(value);
    }
    setValue(property.default);
    row.type.addEventListener('change', () => { setValue(row.type.value === 'boolean' ? false : row.type.value === 'number' ? 0 : ''); motion.reveal(row.value); changed(); });
    const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = t('delete');
    remove.addEventListener('click', () => {
        propertyRows.splice(propertyRows.indexOf(row), 1); motion.remove(element);
        $('properties-empty').hidden = !!propertyRows.length; motion.reveal($('properties-empty'));
        changed(); $('property-add').focus();
    });
    element.append(remove); element.addEventListener('input', changed);
    propertyRows.push(row); $('property-fields').append(element); $('properties-empty').hidden = true;
    motion.insert(element);
    return row;
}
function loadSource(value) {
    view?.destroy(); view = null;
    editorStates = Object.fromEntries(codeTabs.map(tab => [tab, editState(tab, value[tab])]));
    $('custom-name').value = value.name;
    propertyRows.length = 0; $('property-fields').replaceChildren();
    for (const [name, property] of Object.entries(value.properties)) addProperty(name, property);
    $('properties-empty').hidden = !!propertyRows.length;
    showCode(active);
}
function publication() {
    motion.text($('custom-publication'), t(!state.latest ? 'custom.draft' : state.enabled ? 'custom.published' : 'custom.withdrawn'));
    $('custom-availability').hidden = !state.latest;
    $('custom-availability').textContent = t(state.enabled ? 'custom.withdraw' : 'custom.enable');
    $('versions-empty').hidden = !!state.versions.length; $('custom-versions').replaceChildren();
    for (let index = state.versions.length - 1; index >= 0; index--) {
        const version = state.versions[index];
        const row = document.createElement('li');
        const label = document.createElement('span'); label.className = 'version-identity';
        const title = document.createElement('strong'); title.textContent = t('custom.version-number', {number: index + 1});
        const fingerprint = document.createElement('code'); fingerprint.textContent = version.slice(0, 12);
        label.append(title, fingerprint);
        if (version === state.latest) { const latest = document.createElement('span'); latest.className = 'component-state'; latest.textContent = t('custom.latest'); label.append(latest); }
        const load = document.createElement('button'); load.textContent = t('custom.load-version');
        load.addEventListener('click', () => run(async () => {
            if (dirty() && !await confirmAction('discard')) { message(''); return; }
            const restored = await api('/' + state.id + '/versions/' + version);
            loadSource(restored); changed(); message('custom.version-loaded');
        }));
        row.append(label, load); $('custom-versions').append(row);
    }
    motion.reveal($('custom-versions'));
}
async function runPreview() {
    const value = source();
    const ref = await api('/preview', {source: value});
    const props = Object.fromEntries(Object.entries(value.properties).map(([name, property]) => [name, String(property.default)]));
    const slot = document.createElement('div'); slot.style.cssText = 'visibility:hidden;height:0;overflow:hidden'; $('custom-preview').append(slot);
    let next;
    motion.text($('custom-preview-state'), t('loading-preview'));
    try {
        next = await mountFrame(slot, ref.url, props, {signal: lifetime.signal, title: t('preview'), onError: code => { message('failed', code); $('custom-preview-state').textContent = t('custom.preview-failed'); }});
    } catch (error) {
        slot.remove(); await api('/previews/' + ref.token + '/close', {}).catch(() => {});
        $('custom-preview-state').textContent = t('custom.preview-failed'); throw error;
    }
    preview?.destroy(); previewSlot?.remove(); const previous = previewToken;
    preview = next; previewSlot = slot; previewToken = ref.token; slot.style.cssText = '';
    if (previous) await api('/previews/' + previous + '/close', {});
    $('custom-preview-state').classList.remove('empty');
    motion.text($('custom-preview-state'), t('custom.preview-ready'));
    motion.reveal($('custom-preview'));
}
async function save() {
    const value = source();
    state = await api('/save', {id: state.id, revision: state.revision, source: value});
    baseline = JSON.stringify(value);
    history.replaceState(null, '', '/page-editor/component.html?id=' + encodeURIComponent(state.id));
    $('component-title').textContent = value.name; document.title = value.name + ' · ' + t('custom.editor');
    syncSaveState(); publication();
}
async function init() {
    await start('custom.editor'); message('working');
    cm = await import('./vendor/codemirror/codemirror.mjs');
    const id = new URL(location.href).searchParams.get('id');
    state = id ? await api('/' + encodeURIComponent(id)) : {id: '', revision: 0, latest: '', enabled: true, versions: [],
        draft: {name: '', html: '<footer><span></span></footer>', css: 'footer { padding: 16px; text-align: center; }',
            javascript: "root.querySelector('span').textContent = context.properties.title;",
            properties: {title: {type: 'string', label: t('text'), default: t('custom.sample')}}}};
    loadSource(state.draft); baseline = JSON.stringify(source());
    $('component-title').textContent = state.id ? state.draft.name : t('custom.new');
    tabs($('source-tabs'), showCode);
    tabs($('component-sections'), tab => {
        for (const section of ['code', 'properties', 'versions']) $('component-' + section + '-panel').hidden = tab !== section;
        if (tab === 'code') view.requestMeasure();
    });
    const narrow = matchMedia('(max-width: 56.25em)');
    const orientation = () => $('component-sections').setAttribute('aria-orientation', narrow.matches ? 'horizontal' : 'vertical');
    orientation(); narrow.addEventListener('change', orientation, {signal: lifetime.signal});
    $('custom-name').addEventListener('input', changed);
    $('property-add').addEventListener('click', () => { addProperty().name.focus(); changed(); });
    $('custom-save').addEventListener('click', () => { if (valid()) run(async () => { await save(); message('saved'); }); });
    $('custom-run').addEventListener('click', () => { if (valid()) run(async () => { await runPreview(); message(''); }); });
    $('custom-publish').addEventListener('click', () => {
        if (!valid()) return;
        run(async () => {
            if (!await confirmAction('custom.publish-confirm')) { message(''); return; }
            await runPreview(); await save();
            state = await api('/' + state.id + '/publish', {revision: state.revision}); publication(); message('custom.published');
        });
    });
    $('custom-availability').addEventListener('click', () => run(async () => {
        if (!await confirmAction('custom.availability-confirm')) { message(''); return; }
        state = await api('/' + state.id + '/availability', {revision: state.revision, enabled: !state.enabled});
        publication(); message(state.enabled ? 'custom.published' : 'custom.withdrawn');
    }));
    $('custom-back').addEventListener('click', async event => {
        if (!dirty() && !busy) return;
        event.preventDefault();
        if (!busy && await confirmAction('discard')) { baseline = JSON.stringify(source()); location.href = $('custom-back').href; }
    });
    window.addEventListener('beforeunload', event => { if (dirty() || busy) { event.preventDefault(); event.returnValue = ''; } });
    lifetime.signal.addEventListener('abort', () => {
        view?.destroy(); preview?.destroy();
        if (previewToken) fetch('/api/page-editor/components/previews/' + previewToken + '/close',
            {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}', keepalive: true}).catch(() => {});
    }, {once: true});
    publication(); changed(); message('');
    $('component-fields').inert = false; $('custom-name').disabled = false; $('custom-save').disabled = false; $('custom-publish').disabled = false;
    motion.reveal($('component-fields'));
    if (!state.id) $('custom-name').focus();
}
init().catch(failure);
