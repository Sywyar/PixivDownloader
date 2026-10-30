import {mountComponent, renderPage, pureValue} from '/js/pixiv-pages.mjs';
import {installCssEditor} from './editor-css.mjs';
import {installEditorMotion} from './editor-motion.mjs';

const $ = id => document.getElementById(id);
let i18n;
let componentI18n;
const lifetime = new AbortController();
let editor;
let cssEditor;
let motion;
let catalog;
let state;
let pageId;
let dirty = false;
let savedSource = '';
let loading = false;
let busy = false;
let inspector;
let inspectorGeneration = 0;
let connections = [];
let preview;
let previewAbort;
let previewWork;
let needsRepair = false;
const componentViews = new Set();
const t = (key, fallback) => i18n?.t('page-editor:' + key, fallback || key) || fallback || key;
const componentDefinition = type => catalog.components.find(entry => entry.definition.type === type);
const label = definition => componentI18n.t(definition.namespace + ':' + definition.labelKey, definition.type || definition.id);
const activePage = () => catalog.pages.find(entry => entry.definition.id === pageId);
const regionDefinition = id => activePage()?.definition.regions.find(region => region.id === id);
const pageT = (key, fallback) => componentI18n.t(activePage().definition.namespace + ':' + key, fallback || key);
const status = key => {
    $('status').textContent = t(key);
    $('status').dataset.state = ['saved', 'published', 'restored', 'css-applied'].includes(key) ? 'success'
        : ['failed', 'conflict', 'required-region', 'css-pending'].includes(key) ? 'error' : 'idle';
    if (key !== 'unsaved') motion?.reveal($('status'));
};

function canMove(model, target) {
    if (!target?.get('droppable')) return false;
    let parent = target;
    while (parent && parent.get('type') !== 'page-region') parent = parent.parent();
    const parentId = parent?.get('region') || '';
    function allowed(node, expected) {
        if (node.get('type') === 'page-region') {
            if (regionDefinition(node.get('region'))?.parent !== expected) return false;
            expected = node.get('region');
        }
        return node.components().every(child => allowed(child, expected));
    }
    return allowed(model, parentId);
}

async function api(path, data, signal = lifetime.signal) {
    const response = await fetch('/api/page-editor/' + path, {
        method: data === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: data === undefined ? {} : {'Content-Type': 'application/json'}, body: data === undefined ? undefined : JSON.stringify(data), signal
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.code || 'PAGE_OPERATION_FAILED');
    return result;
}
async function action(work) {
    if (busy) return;
    busy = true; syncActions();
    $('status').dataset.state = 'busy'; $('status').textContent = t('working');
    document.querySelector('.editor-actions').setAttribute('aria-busy', 'true');
    closeActions();
    try { await work(); }
    catch (error) {
        if (error.name === 'AbortError') return;
        $('status').textContent = t(error.message === 'PAGE_REVISION_CONFLICT' ? 'conflict' : 'failed') + ' (' + error.message + ')';
        $('status').dataset.state = 'error';
    } finally {
        busy = false; syncActions(); document.querySelector('.editor-actions').setAttribute('aria-busy', 'false');
        if ($('status').dataset.state === 'busy') status(dirty ? 'unsaved' : 'loaded');
    }
}

function closeActions() {
    const menu = $('document-more');
    if (!menu.open) return;
    if (menu.contains(document.activeElement)) menu.querySelector('summary').focus();
    menu.open = false;
}

function syncActions() {
    for (const button of document.querySelectorAll('.editor-actions button')) button.disabled = busy || !state;
    $('page').disabled = busy;
    $('restore').disabled = busy || !state?.previous;
    $('save').disabled = busy || !state || needsRepair;
    $('publish').disabled = busy || !state || needsRepair;
    $('preview').disabled = busy || !state || needsRepair;
    $('repair').disabled = busy;
    $('undo').disabled = !editor?.UndoManager.hasUndo();
    $('redo').disabled = !editor?.UndoManager.hasRedo();
    const selected = editor?.getSelected();
    const parent = selected?.parent();
    const index = selected?.index() ?? -1;
    $('up').disabled = !parent || index <= 0;
    $('down').disabled = !parent || index >= parent.components().length - 1;
    $('nest').disabled = !parent || index <= 0 || !canMove(selected, parent.components().at(index - 1));
    $('outdent').disabled = !parent?.parent() || !canMove(selected, parent.parent());
    $('delete').disabled = !parent || allModels(selected).some(model => model.get('type') === 'page-region');
    $('selection-actions').hidden = !parent;
}

function configureTypes(engine) {
    const components = engine.DomComponents;
    components.addType('page-box', {model: {defaults: {tagName: 'section', role: 'section', droppable: true},
        init() { this.set('tagName', this.get('role')); this.on('change:role', () => this.set('tagName', this.get('role'))); },
        getName() { return t(this.get('role') === 'section' ? 'container' : this.get('role')); }}});
    components.addType('page-text', {model: {defaults: {tagName: 'p', name: t('text'), droppable: false,
        editable: false, content: t('text-default'), style: {'font-size': '24px'}}},
        view: {onRender() { this.el.textContent = this.model.get('content'); },
            init() { this.listenTo(this.model, 'change:content', this.onRender); }}});
    components.addType('page-link', {extend: 'page-text', model: {defaults: {tagName: 'a', href: '/', name: t('link')}},
        view: {events: {click: event => event.preventDefault()}}});
    components.addType('page-region', {model: {defaults: {tagName: 'div',
        removable: false, copyable: false, editable: false},
        init() { this.set('droppable', Boolean(regionDefinition(this.get('region'))?.container)); },
        getName() { const definition = regionDefinition(this.get('region')); return definition ? pageT(definition.labelKey) : t('region'); }},
        view: {
            init() { this.generation = 0; componentViews.add(this); },
            async onRender() {
                const generation = ++this.generation;
                this.abort?.abort(); this.abort = new AbortController();
                await this.instance?.destroy(); this.instance = null;
                if (generation !== this.generation) return;
                const definition = regionDefinition(this.model.get('region'));
                this.el.dataset.pageRegion = this.model.get('region');
                this.el.hidden = Boolean(definition?.previewHidden && !$('show-hidden').checked);
                if (definition?.container) {
                    this.el.dataset.emptyRegion = this.model.getName();
                    return;
                }
                const entry = activePage();
                if (!entry?.definition.previewModuleUrl) { this.el.textContent = this.model.getName(); return; }
                const slot = this.el.ownerDocument.createElement('div'); slot.inert = true;
                slot.style.pointerEvents = 'none'; this.el.replaceChildren(slot);
                try {
                    const instance = await mountComponent(slot, {moduleUrl: entry.definition.previewModuleUrl,
                        publication: entry.publication, inputs: [], outputs: []}, {region: this.model.get('region')},
                        {mode: 'edit', signal: this.abort.signal, instanceId: this.model.getId(), t: pageT,
                            onUnavailable: () => { slot.textContent = t('unavailable'); }});
                    if (generation !== this.generation) await instance.destroy(); else this.instance = instance;
                } catch (_) { if (generation === this.generation) slot.textContent = t('unavailable'); }
            },
            removed() { this.generation++; this.abort?.abort(); this.instance?.destroy(); componentViews.delete(this); }
        }});
    components.addType('page-component', {
        model: {defaults: {tagName: 'div', droppable: false, editable: false, name: t('component')},
            init() { if (!this.get('instanceId')) this.set('instanceId', crypto.randomUUID()); },
            getName() {
                const entry = componentDefinition(this.get('componentType'));
                return entry ? label(entry.definition) : this.get('componentType') || t('component');
            }},
        view: {
            init() {
                this.generation = 0;
                componentViews.add(this);
                this.listenTo(this.model, 'change:configuration change:componentType change:configVersion', this.onRender);
            },
            async onRender() {
                const generation = ++this.generation;
                this.abort?.abort(); this.abort = new AbortController();
                await this.instance?.destroy(); this.instance = null;
                if (generation !== this.generation) return;
                const entry = componentDefinition(this.model.get('componentType'));
                if (!entry || entry.definition.version !== this.model.get('configVersion')) {
                    this.el.textContent = t('unavailable'); return;
                }
                const slot = this.el.ownerDocument.createElement('div'); this.el.replaceChildren(slot);
                try {
                    const instance = await mountComponent(slot, {...entry.definition, publication: entry.publication},
                        this.model.get('configuration'), {mode: 'edit', signal: this.abort.signal, instanceId: this.model.get('instanceId'),
                            t: (key, fallback) => componentI18n.t(entry.definition.namespace + ':' + key, fallback || key),
                            onUnavailable: () => { slot.textContent = t('unavailable'); }});
                    if (generation !== this.generation) await instance.destroy(); else this.instance = instance;
                } catch (_) { if (generation === this.generation) slot.textContent = t('unavailable'); }
            },
            removed() { this.generation++; this.abort?.abort(); this.instance?.destroy(); componentViews.delete(this); }
        }
    });
}

function modelSource(model) {
    const type = model.get('type') || 'wrapper';
    const source = {type, attributes: {id: model.getId()}, style: pureValue(model.getStyle())};
    if (type === 'page-text' || type === 'page-link') source.content = model.get('content') || '';
    if (type === 'page-link') source.href = model.get('href') || '';
    if (type === 'page-box') source.role = model.get('role');
    if (type === 'page-region') source.region = model.get('region');
    if (type === 'page-component') for (const key of ['componentType', 'instanceId', 'configVersion', 'configuration']) source[key] = pureValue(model.get(key));
    source.components = model.components().map(modelSource);
    return source;
}

function documentSource() {
    const project = editor.getProjectData();
    const styles = (project.styles || []).map(rule => ({
        selectors: rule.selectors, style: rule.style, mediaText: rule.mediaText || ''
    }));
    return {format: 1, pageId, title: $('page-title').value, connections: pureValue(connections),
        project: {pages: [{frames: [{component: modelSource(editor.getWrapper())}]}], styles}};
}
function allModels(model = editor.getWrapper()) {
    return [model, ...model.components().models.flatMap(child => allModels(child))];
}
function syncSelection() {
    const select = $('selection');
    if (!select) return;
    const selected = editor.getSelected()?.getId();
    const protect = model => {
        const children = model.components().map(protect);
        const required = model.get('type') === 'page-region' || children.some(Boolean);
        if (model !== editor.getWrapper()) model.set({copyable: !required, removable: !required}, {silent: true});
        return required;
    };
    protect(editor.getWrapper());
    select.replaceChildren();
    const empty = document.createElement('option'); empty.value = ''; empty.textContent = t('select-first'); select.append(empty);
    for (const model of allModels()) {
        const option = document.createElement('option'); option.value = model.getId();
        let depth = 0;
        for (let parent = model.parent(); parent; parent = parent.parent()) depth++;
        const instance = model.get('instanceId');
        option.textContent = '　'.repeat(depth) + model.getName() + (instance ? ' · ' + instance.slice(0, 8) : '');
        select.append(option);
    }
    select.value = selected || '';
    syncActions();
}
function changed() {
    if (!loading) {
        dirty = JSON.stringify(documentSource()) !== savedSource;
        if (dirty) status('unsaved');
    }
    syncSelection(); syncConnections();
}

async function properties(model) {
    const generation = ++inspectorGeneration;
    await inspector?.destroy(); inspector = null;
    if (generation !== inspectorGeneration) return;
    const root = $('properties'); root.replaceChildren();
    motion?.reveal(root);
    $('style-section').hidden = !model && !cssEditor?.pending();
    $('inspector-selection').textContent = model?.getName() || t('select-first');
    if (motion) motion.setDetails($('page-settings'), !model); else $('page-settings').open = !model;
    if (!model) return;
    if (model.get('type') === 'page-text' || model.get('type') === 'page-link') {
        field(root, t('text'), 'text', model.get('content'), value => model.set('content', value));
        if (model.get('type') === 'page-link') field(root, t('href'), 'string', model.get('href'), value => model.set('href', value));
        return;
    }
    if (model.get('type') === 'page-box') {
        const label = document.createElement('label'); label.className = 'editor-field'; label.textContent = t('role');
        const select = document.createElement('select');
        for (const role of ['section', 'header', 'main', 'footer']) {
            const option = document.createElement('option'); option.value = role;
            option.textContent = t(role === 'section' ? 'container' : role); select.append(option);
        }
        select.value = model.get('role'); select.addEventListener('change', () => model.set('role', select.value));
        label.append(select); root.append(label);
    }
    if (model.get('type') !== 'page-component') {
        const notice = document.createElement('p'); notice.className = 'help';
        notice.textContent = t(model.get('type') === 'page-region' ? 'region-help' : 'layout-help');
        root.append(notice); return;
    }
    const entry = componentDefinition(model.get('componentType'));
    if (!entry) { root.textContent = t('unavailable'); return; }
    const definition = entry.definition;
    if (model.get('configVersion') !== definition.version) {
        const button = document.createElement('button'); button.textContent = t('migrate');
        button.addEventListener('click', () => action(async () => {
            const configuration = await api('migrate', {type: definition.type, publication: entry.publication,
                version: model.get('configVersion'), configuration: model.get('configuration')});
            model.set({configVersion: definition.version, configuration}); await properties(model);
        }));
        root.append(button); return;
    }
    if (definition.propertiesModuleUrl) {
        const mount = document.createElement('div'); root.append(mount);
        const instance = await mountComponent(mount, {...definition, publication: entry.publication},
            model.get('configuration'), {mode: 'edit', properties: true, instanceId: model.get('instanceId'),
                t: (key, fallback) => componentI18n.t(definition.namespace + ':' + key, fallback || key),
                onChange: value => { if (generation === inspectorGeneration) model.set('configuration', value); },
                onUnavailable: () => { mount.textContent = t('unavailable'); }});
        if (generation !== inspectorGeneration) await instance.destroy(); else inspector = instance;
        return;
    }
    const schema = JSON.parse(definition.schema);
    let shape = schema;
    if (shape.$ref?.startsWith('#/$defs/')) shape = schema.$defs[shape.$ref.slice(8)];
    for (const [key, property] of Object.entries(shape.properties || {})) {
        const type = property.type;
        if (!['string', 'boolean', 'integer', 'number'].includes(type)) {
            const notice = document.createElement('p'); notice.textContent = key + ': ' + t('custom-editor-needed');
            root.append(notice); continue;
        }
        field(root, componentI18n.t(definition.namespace + ':properties.' + key, key), type, model.get('configuration')[key], value => {
            model.set('configuration', {...model.get('configuration'), [key]: value});
        });
    }
}
function field(root, caption, type, value, update) {
    const label = document.createElement('label'); label.className = 'editor-field'; label.textContent = caption;
    const input = document.createElement(type === 'text' ? 'textarea' : 'input');
    if (type === 'boolean') { input.type = 'checkbox'; input.checked = Boolean(value); }
    else {
        if (input.tagName === 'INPUT') input.type = type === 'integer' || type === 'number' ? 'number' : 'text';
        input.value = value ?? '';
    }
    input.addEventListener('change', () => update(type === 'boolean' ? input.checked :
        type === 'integer' || type === 'number' ? Number(input.value) : input.value));
    label.append(input); root.append(label);
}

function blocks() {
    editor.BlockManager.getAll().reset();
    editor.BlockManager.add('box', {label: t('container'), content: {type: 'page-box',
        style: {display: 'flex', 'flex-direction': 'column', gap: '16px', padding: '24px', 'min-height': '80px'}}});
    editor.BlockManager.add('text', {label: t('text'), content: {type: 'page-text'}});
    editor.BlockManager.add('link', {label: t('link'), content: {type: 'page-link', style: {'font-size': 'inherit'}}});
    for (const role of ['header', 'main', 'footer']) editor.BlockManager.add(role, {
        label: t(role), content: {type: 'page-box', role,
            style: {display: 'flex', 'flex-direction': 'column', gap: '16px', padding: '16px',
                ...(role === 'main' ? {flex: '1'} : {})}}
    });
    for (const entry of catalog.components) {
        const definition = entry.definition;
        editor.BlockManager.add(definition.type, {label: label(definition), content: {
            type: 'page-component', componentType: definition.type, configVersion: definition.version,
            configuration: JSON.parse(definition.defaults)
        }});
    }
    editor.BlockManager.render();
    for (const [index, element] of [...$('blocks').querySelectorAll('.gjs-block')].entries()) {
        const block = editor.BlockManager.getAll().at(index);
        element.dataset.componentBlock = block.getId();
        element.tabIndex = 0; element.setAttribute('role', 'button');
        element.addEventListener('keydown', event => {
            if (event.key !== 'Enter' && event.key !== ' ') return;
            event.preventDefault(); event.stopPropagation();
            const selected = editor.getSelected();
            const parent = selected?.get('droppable') ? selected : selected?.parent() || editor.getWrapper();
            const [model] = parent.append(block.getContent(), selected && parent !== selected ? {at: selected.index() + 1} : {});
            editor.select(model);
        });
    }
}
function syncConnections() {
    if (!editor) return;
    const models = allModels().filter(model => model.get('type') === 'page-component');
    document.querySelector('.connections-section').hidden = !connections.length && !models.some(model => {
        const definition = componentDefinition(model.get('componentType'))?.definition;
        return definition?.inputs.length || definition?.outputs.length;
    });
    for (const id of ['from', 'to']) {
        const previous = $(id).value; $(id).replaceChildren();
        for (const model of models) {
            const option = document.createElement('option'); option.value = model.get('instanceId');
            option.textContent = model.get('componentType') + ' · ' + option.value.slice(0, 8); $(id).append(option);
        }
        if (models.some(model => model.get('instanceId') === previous)) $(id).value = previous;
    }
    ports();
    $('connections').replaceChildren();
    connections.forEach((link, index) => {
        const row = document.createElement('p'); row.textContent = link.from.slice(0, 8) + '.' + link.output + ' → ' + link.to.slice(0, 8) + '.' + link.input + ' ';
        const remove = document.createElement('button'); remove.textContent = t('delete');
        remove.addEventListener('click', () => { connections.splice(index, 1); changed(); });
        row.append(remove); $('connections').append(row);
    });
}
function ports() {
    for (const [id, source, field] of [['output', 'from', 'outputs'], ['input', 'to', 'inputs']]) {
        const previous = $(id).value; $(id).replaceChildren();
        const model = allModels().find(item => item.get('instanceId') === $(source).value);
        const values = componentDefinition(model?.get('componentType'))?.definition[field] || [];
        for (const value of values) {
            const option = document.createElement('option'); option.value = value.name;
            option.textContent = value.name + ' · ' + value.contractId; $(id).append(option);
        }
        if (values.some(value => value.name === previous)) $(id).value = previous;
    }
}
async function loadPage() {
    if ((dirty || cssEditor?.pending()) && !await confirmAction('discard')) { $('page').value = pageId; return; }
    pageId = $('page').value;
    state = await api('documents/' + encodeURIComponent(pageId));
    await loadProject(state.draft);
    savedSource = JSON.stringify(documentSource());
    dirty = false; status('loaded'); syncActions();
    try { await api('documents/' + encodeURIComponent(pageId) + '/preview', {document: state.draft}); }
    catch (error) {
        if (!['PAGE_REQUIRED_REGION', 'PAGE_REGION_PARENT'].includes(error.message)) {
            $('status').textContent = t('failed') + ' (' + error.message + ')'; return;
        }
        needsRepair = true; $('recovery').hidden = false;
        const defaults = await api('documents/' + encodeURIComponent(pageId) + '/default');
        await showPreview(defaults, true); status('repair-title'); syncActions();
    }
}
async function loadProject(source) {
    cssEditor?.reset();
    await closePreview(false);
    needsRepair = false; $('recovery').hidden = true;
    loading = true;
    try {
        editor.select(null);
        editor.loadProjectData(pureValue(source.project));
        editor.Layers.setLayerData(editor.getWrapper(), {open: true});
        $('page-title').value = source.title;
        connections = pureValue(source.connections);
        editor.UndoManager.clear();
    } finally { loading = false; }
    canvasStyles();
    syncSelection(); syncConnections(); await properties(null);
}

function sidebar(name) {
    for (const [id, panel] of [['structure', 'structure-panel'], ['add', 'add-panel']]) {
        $(id + '-tab').setAttribute('aria-selected', String(id === name));
        $(id + '-tab').tabIndex = id === name ? 0 : -1;
        $(panel).hidden = id !== name;
    }
    motion?.reveal($(name + '-panel'));
}

async function closePreview(focus = true) {
    previewAbort?.abort();
    $('preview-panel').hidden = true;
    document.querySelector('.workspace').classList.remove('previewing');
    $('preview').setAttribute('aria-pressed', 'false');
    await previewWork?.catch(() => {}); previewWork = null;
    await preview?.destroy(); preview = null;
    editor?.refresh();
    motion?.reveal($('canvas-area'));
    if (focus) $('preview').focus();
}

async function showPreview(source, recovery = false) {
    await closePreview(false);
    const abort = new AbortController(); previewAbort = abort;
    $('preview-panel').hidden = false;
    $('close-preview').hidden = recovery;
    $('preview-loading').hidden = false; $('preview-loading').textContent = t('loading-preview');
    $('preview-panel').classList.add('loading');
    $('preview-panel').classList.toggle('narrow', $('viewport').value === 'Mobile');
    $('preview').setAttribute('aria-pressed', 'true');
    document.querySelector('.workspace').classList.add('previewing');
    motion?.reveal($('preview-panel'));
    previewWork = (async () => {
        try {
            const view = await api('documents/' + encodeURIComponent(pageId) + '/preview', {document: source}, abort.signal);
            const root = $('preview-root').contentDocument.body;
            root.style.cssText = 'margin:0;min-height:100vh;display:flex;flex-direction:column';
            root.ownerDocument.documentElement.dataset.theme = document.documentElement.dataset.theme || 'light';
            preview = await renderPage(root, view, {mode: 'preview', signal: abort.signal, t, pageT, onUnavailable: () => status('unavailable')});
            if (!abort.signal.aborted) { $('preview-panel').classList.remove('loading'); $('preview-loading').hidden = true; }
        } catch (error) {
            if (!abort.signal.aborted) $('preview-loading').textContent = t('failed') + ' (' + error.message + ')';
            throw error;
        }
    })();
    await previewWork;
    if (!recovery) $('close-preview').focus();
}
function canvasStyles() {
    const document = editor.Canvas.getDocument();
    if (!document) return;
    for (const link of document.querySelectorAll('link[data-page-stylesheet]')) link.remove();
    const page = activePage();
    for (const path of page?.definition.stylesheets || []) {
        const link = document.createElement('link'); link.rel = 'stylesheet'; link.dataset.pageStylesheet = '';
        const url = new URL(path, location.origin); url.searchParams.set('pagePublication', page.publication);
        link.href = url.href; document.head.append(link);
    }
}
async function confirmAction(key) {
    return Boolean(await window.PixivFeedback.confirm({
        message: t(key), confirmLabel: t('confirm'), cancelLabel: t('cancel')
    }));
}
function manipulate(action) {
    const selected = editor.getSelected();
    if (!selected || selected === editor.getWrapper()) return;
    const parent = selected.parent(); const index = selected.index();
    if (action === 'delete') {
        if (selected.get('type') === 'page-region' || allModels(selected).some(model => model.get('type') === 'page-region')) {
            status('required-region'); return;
        }
        const ids = new Set(allModels(selected).map(model => model.get('instanceId')).filter(Boolean));
        connections = connections.filter(link => !ids.has(link.from) && !ids.has(link.to));
        selected.remove(); return;
    }
    if (action === 'up' && index > 0) selected.move(parent, {at: index - 1});
    if (action === 'down' && index < parent.components().length - 1) selected.move(parent, {at: index + 2});
    if (action === 'nest') {
        const previous = parent.components().at(index - 1);
        if (index > 0 && canMove(selected, previous)) selected.move(previous);
    }
    if (action === 'outdent' && parent.parent() && canMove(selected, parent.parent())) selected.move(parent.parent(), {at: parent.index() + 1});
    editor.select(selected);
}

async function loadComponentTranslations(next) {
    const namespaces = [...new Set([...next.components.map(entry => entry.definition.namespace), ...next.pages.map(page => page.definition.namespace)])];
    const client = await window.PixivI18n.create({namespaces, signal: lifetime.signal});
    if (lifetime.signal.aborted) { client.dispose(); return; }
    componentI18n?.dispose(); componentI18n = client;
}

async function start() {
    i18n = await window.PixivI18n.create({namespaces: ['page-editor', 'common']});
    catalog = await api('catalog');
    await loadComponentTranslations(catalog);
    i18n.apply(document.body, 'page-editor');
    document.title = t('plugin.name');
    window.PixivTheme?.mount({mountPoint: $('preferences')});
    await window.PixivLangSwitcher?.mount({mountPoint: $('preferences'), variant: 'editor', i18n, onChange: () => location.reload()});
    for (const {definition: page} of catalog.pages) {
        const option = document.createElement('option'); option.value = page.id; option.textContent = label(page); $('page').append(option);
    }
    const styleLabels = Object.fromEntries(Object.entries(i18n.bundleMap['page-editor'])
        .filter(([key]) => key.startsWith('style.')).map(([key, value]) => [key.slice(6), value]));
    for (const key of Object.keys(styleLabels)) {
        if (/^(padding|margin)-(top|right|bottom|left)$|^border-.*-radius$/.test(key)) styleLabels[key + '-sub'] = styleLabels[key];
    }
    const styleChoices = (property, values, display) => ({property, type: 'select',
        ...(display ? {requires: {display}} : {}),
        options: values.map(value => ({id: value, label: t('value.' + value)}))});
    editor = window.grapesjs.init({
        container: '#editor', height: '', storageManager: false, telemetry: false, cssIcons: '', allowScripts: false, nativeDnD: false,
        canvasCss: ':where(body){min-height:100vh;margin:0;font-family:system-ui,sans-serif;color:var(--editor-text);background:var(--editor-panel)}[hidden]{display:none!important}[data-empty-region]:empty{min-height:4rem;border:1px dashed currentColor;padding:1rem}[data-empty-region]:empty:after{content:attr(data-empty-region)}',
        panels: {defaults: []}, blockManager: {appendTo: '#blocks'},
        layerManager: {appendTo: '#layers', scrollCanvas: {behavior: 'auto', block: 'nearest'}},
        selectorManager: {componentFirst: true}, traitManager: {appendTo: document.createElement('div')},
        canvas: {scripts: [], styles: [], frameStyle: ''}, assetManager: {assets: [], upload: false},
        i18n: {locale: 'current', localeFallback: 'current', detectLocale: false, messages: {current: {
            canvas: {frameTitle: t('canvas')}, domComponents: {names: {wrapper: t('page')}},
            styleManager: {empty: t('select-first'), properties: styleLabels}
        }}},
        deviceManager: {devices: [{name: 'Desktop', width: ''}, {name: 'Mobile', width: '375px', widthMedia: '640px'}]},
        styleManager: {appendTo: '#styles', sectors: [
            {name: t('layout'), open: true, properties: [
                {property: 'display', type: 'select', options: ['block', 'flex', 'grid'].map(value => ({id: value, label: t('display.' + value)}))},
                styleChoices('flex-direction', ['row', 'row-reverse', 'column', 'column-reverse'], ['flex']),
                styleChoices('justify-content', ['flex-start', 'flex-end', 'center', 'space-between', 'space-around', 'space-evenly'], ['flex', 'grid']),
                styleChoices('align-items', ['flex-start', 'flex-end', 'center', 'stretch', 'baseline'], ['flex', 'grid']),
                styleChoices('flex-wrap', ['nowrap', 'wrap', 'wrap-reverse'], ['flex']),
                {property: 'flex', type: 'select', options: [{id: '0 1 auto', label: t('size-content')}, {id: '1', label: t('size-fill')}]},
                {property: 'gap', type: 'integer', units: ['px', 'em', 'rem'], default: '0px'},
                'padding', 'margin', 'width', 'max-width', 'min-height']},
            {name: t('appearance'), open: false, properties: [
                {property: 'font-size', type: 'integer', units: ['px', 'rem', 'em'], default: '16px'},
                {property: 'font-weight', type: 'select', options: ['400', '500', '600', '700'].map(value => ({id: value, label: t('weight.' + value)}))},
                {property: 'line-height', type: 'integer', units: ['', 'px', 'em'], default: '1.5', step: .1},
                styleChoices('text-align', ['left', 'center', 'right', 'justify']), 'color', 'background-color', 'border-radius']}
        ]},
        plugins: [configureTypes]
    });
    await new Promise(resolve => editor.onReady(resolve));
    motion = installEditorMotion(editor, lifetime.signal);
    cssEditor = installCssEditor(editor, api, t, lifetime.signal, catalog.styleProperties, motion);
    const canvasTheme = ({window: canvasWindow} = {}) => {
        const root = (canvasWindow?.document || editor.Canvas.getDocument())?.documentElement;
        if (!root) return;
        const theme = getComputedStyle(document.documentElement);
        for (const name of ['--editor-text', '--editor-panel', '--editor-accent']) root.style.setProperty(name, theme.getPropertyValue(name));
        root.style.colorScheme = document.documentElement.dataset.theme || 'light';
        root.dataset.theme = document.documentElement.dataset.theme || 'light';
        const previewRoot = $('preview-root').contentDocument?.documentElement;
        if (previewRoot) previewRoot.dataset.theme = root.dataset.theme;
    };
    editor.on('canvas:frame:load:body', event => { canvasTheme(event); canvasStyles(); });
    const themes = new MutationObserver(canvasTheme);
    themes.observe(document.documentElement, {attributes: true, attributeFilter: ['data-theme']});
    canvasTheme();
    blocks();
    const selectLabel = document.createElement('label'); selectLabel.className = 'editor-field selection-field';
    selectLabel.textContent = t('selection');
    const selection = document.createElement('select'); selection.id = 'selection';
    selectLabel.append(selection); $('layers').before(selectLabel);
    selection.addEventListener('change', () => editor.select(allModels().find(model => model.getId() === selection.value)));
    let selectionFrame;
    editor.on('component:selected component:deselected', () => {
        inspectorGeneration++;
        $('properties').replaceChildren();
        cancelAnimationFrame(selectionFrame);
        selectionFrame = requestAnimationFrame(() => {
            syncSelection(); properties(editor.getSelected()).catch(() => status('failed'));
        });
    });
    editor.on('block:drag:stop', model => { if (model) editor.select(model); });
    editor.on('component:clone', clone => {
        for (const model of allModels(clone)) if (model.get('type') === 'page-component') model.set('instanceId', crypto.randomUUID());
    });
    editor.on('component:create', model => {
        model.set('draggable', parent => canMove(model, parent), {silent: true});
    });
    editor.on('component:remove:before', (model, remove, options) => {
        if (!loading && !options.temporary && allModels(model).some(child => child.get('type') === 'page-region')) {
            options.abort = true; status('required-region');
        }
    });
    editor.on('update', changed);
    editor.on('undo redo', syncActions);
    document.addEventListener('click', event => {
        if (!$('document-more').contains(event.target)) closeActions();
    });
    $('document-more').addEventListener('keydown', event => {
        if (event.key === 'Escape' && $('document-more').open) {
            event.preventDefault(); event.stopPropagation(); closeActions();
        }
    });
    $('page-title').addEventListener('input', changed);
    for (const name of ['structure', 'add']) {
        $(name + '-tab').addEventListener('click', () => sidebar(name));
        $(name + '-tab').addEventListener('keydown', event => {
            if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
            event.preventDefault();
            const next = event.key === 'Home' ? 'structure' : event.key === 'End' ? 'add' : name === 'add' ? 'structure' : 'add';
            sidebar(next); $(next + '-tab').focus();
        });
    }
    $('page').addEventListener('change', () => action(loadPage));
    $('reload').addEventListener('click', () => action(loadPage));
    const defaults = () => action(async () => {
        if ((dirty || cssEditor.pending()) && !await confirmAction('defaults-confirm')) return;
        const source = await api('documents/' + encodeURIComponent(pageId) + '/default');
        await loadProject(source); changed();
    });
    $('defaults').addEventListener('click', defaults);
    $('repair').addEventListener('click', defaults);
    $('show-hidden').addEventListener('change', () => {
        for (const model of allModels()) if (model.get('type') === 'page-region') {
            const element = model.getEl();
            if (element) element.hidden = Boolean(regionDefinition(model.get('region'))?.previewHidden && !$('show-hidden').checked);
        }
    });
    $('save').addEventListener('click', () => action(async () => {
        if (!await cssEditor.ready()) { status('css-pending'); return; }
        const source = documentSource();
        state = await api('documents/' + encodeURIComponent(pageId) + '/save', {revision: state.revision, document: source});
        savedSource = JSON.stringify(source); changed(); if (!dirty) status('saved');
    }));
    $('publish').addEventListener('click', () => action(async () => {
        if (!await cssEditor.ready()) { status('css-pending'); return; }
        if (!await confirmAction('publish-confirm')) return;
        const source = documentSource();
        state = await api('documents/' + encodeURIComponent(pageId) + '/save', {revision: state.revision, document: source});
        savedSource = JSON.stringify(source); changed();
        state = await api('documents/' + encodeURIComponent(pageId) + '/publish', {revision: state.revision}); status('published');
    }));
    $('restore').addEventListener('click', () => action(async () => {
        if (!await confirmAction('restore-confirm')) return;
        state = await api('documents/' + encodeURIComponent(pageId) + '/restore', {revision: state.revision});
        dirty = false; await loadPage(); status('restored');
    }));
    $('preview').addEventListener('click', () => action(async () => {
        if (!await cssEditor.ready()) { status('css-pending'); return; }
        return $('preview-panel').hidden ? showPreview(documentSource()) : closePreview();
    }));
    $('close-preview').addEventListener('click', () => closePreview());
    $('viewport').addEventListener('change', () => {
        editor.setDevice($('viewport').value);
        $('preview-panel').classList.toggle('narrow', $('viewport').value === 'Mobile');
    });
    $('undo').addEventListener('click', () => editor.UndoManager.undo());
    $('redo').addEventListener('click', () => editor.UndoManager.redo());
    for (const id of ['up', 'down', 'nest', 'outdent', 'delete']) $(id).addEventListener('click', () => manipulate(id));
    for (const id of ['from', 'to']) $(id).addEventListener('change', ports);
    $('connect').addEventListener('submit', event => {
        event.preventDefault();
        if (!$('output').value || !$('input').value) return;
        connections.push({from: $('from').value, to: $('to').value, output: $('output').value, input: $('input').value}); changed();
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && !$('preview-panel').hidden && !needsRepair) { event.preventDefault(); closePreview(); return; }
        if (event.key === 'Delete' && !event.target.closest('input,textarea,select,[contenteditable]')) { event.preventDefault(); manipulate('delete'); }
    });
    window.addEventListener('beforeunload', event => { if (dirty || cssEditor.pending()) { event.preventDefault(); event.returnValue = ''; } });
    window.addEventListener('pagehide', () => {
        inspectorGeneration++; inspector?.destroy(); previewAbort?.abort(); preview?.destroy();
        for (const view of componentViews) view.removed();
        lifetime.abort(); editor.destroy(); themes.disconnect(); i18n.dispose(); componentI18n?.dispose();
        cancelAnimationFrame(selectionFrame);
    }, {once: true});
    await loadPage();
    let refreshing = false;
    const refreshCatalog = async () => {
        if (refreshing || document.hidden) return;
        refreshing = true;
        try {
            const next = await api('catalog');
            if (lifetime.signal.aborted) return;
            if (JSON.stringify(next) !== JSON.stringify(catalog)) {
                await loadComponentTranslations(next);
                if (lifetime.signal.aborted) return;
                catalog = next;
                blocks();
                for (const view of componentViews) view.onRender();
                await properties(editor.getSelected());
            }
        } catch (_) { status('failed'); }
        finally { refreshing = false; }
    };
    const discovery = setInterval(refreshCatalog, 2000);
    window.addEventListener('pagehide', () => clearInterval(discovery), {once: true});
    window.pageEditorReady = true;
}
start().catch(error => { $('status').textContent = t('failed') + ' (' + error.message + ')'; });
