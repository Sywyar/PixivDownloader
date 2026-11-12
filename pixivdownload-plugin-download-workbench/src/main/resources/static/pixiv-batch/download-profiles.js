'use strict';

window.PixivDownloadProfiles = (() => {
    const extensions = new Map();
    const mounts = new WeakMap();
    let mountId = 0;
    function registerSettings(context, defaults, validate = () => true) {
        const key = Symbol();
        extensions.set(key, {context, defaults: {...defaults}, validate});
        context.onCleanup(() => extensions.delete(key));
    }
    const commonKeys = ['interval', 'intervalUnit', 'imageDelay', 'imageDelayUnit', 'concurrent',
        'autoStartOnEnqueue', 'skipHistory', 'verifyHistoryFiles', 'redownloadDeleted', 'bookmark',
        'fileNameTemplate', 'pathOverflowAction'];
    const clone = value => JSON.parse(JSON.stringify(value));
    const plain = value => value && typeof value === 'object' && !Array.isArray(value);
    const same = (a, b) => {
        if (Array.isArray(a) || Array.isArray(b)) return JSON.stringify(a) === JSON.stringify(b);
        if (!plain(a) || !plain(b)) return a === b;
        return Object.keys(a).length === Object.keys(b).length
            && Object.keys(a).every(key => Object.hasOwn(b, key) && same(a[key], b[key]));
    };

    function capture(settings, filters, contributions) {
        const values = {};
        commonKeys.forEach(key => {
            if (Object.hasOwn(settings, key)) values[key] = settings[key];
        });
        for (const contribution of contributions || []) {
            if (typeof contribution.profileSettings === 'function') {
                Object.assign(values, contribution.profileSettings(settings));
            }
        }
        for (const {context, defaults} of extensions.values()) {
            if (!context.isActive()) continue;
            Object.entries(defaults).forEach(([key, fallback]) => {
                values[key] = settings[key] ?? fallback;
            });
        }
        return clone({settings: values, filters: normalizeSearchFilters(filters)});
    }

    function prepare(profile, settings, contributions) {
        if (!plain(profile?.settings) || !plain(profile?.filters)) throw new Error('profiles.invalid');
        const known = capture(settings, {}, contributions).settings;
        const values = profile.settings;
        if (Object.keys(values).some(key => !Object.hasOwn(known, key))) throw new Error('profiles.unavailable');
        for (const key of Object.keys(values)) {
            if (typeof values[key] !== typeof known[key] || typeof values[key] === 'object')
                throw new Error('profiles.invalid');
        }
        for (const key of ['interval', 'imageDelay', 'concurrent']) {
            if (Object.hasOwn(values, key) && (!Number.isFinite(values[key])
                || values[key] < (key === 'concurrent' ? 1 : 0)
                || key === 'concurrent' && !Number.isInteger(values[key]))) throw new Error('profiles.invalid');
        }
        for (const key of ['intervalUnit', 'imageDelayUnit']) {
            if (Object.hasOwn(values, key) && !['s', 'ms'].includes(values[key])) throw new Error('profiles.invalid');
        }
        if (values.pathOverflowAction && !['ASK', 'TRUNCATE', 'DEFAULT_NAME', 'CANCEL'].includes(values.pathOverflowAction))
            throw new Error('profiles.invalid');
        for (const {context, validate} of extensions.values()) {
            if (context.isActive() && !validate({...known, ...values})) throw new Error('profiles.invalid');
        }
        const normalized = capture({...settings, ...values}, {}, contributions).settings;
        if (Object.keys(values).some(key => !same(values[key], normalized[key]))) throw new Error('profiles.invalid');
        return clone({settings: values, filters: normalizeSearchFilters(profile.filters)});
    }

    function mount(root, adapter) {
        if (!root || !isAdmin) return;
        mounts.get(root)?.dispose();
        const controller = new AbortController();
        const context = adapter.context();
        let catalog = null;
        let busy = false;
        const current = () => !controller.signal.aborted && root.isConnected && adapter.context() === context;
        const t = key => adapter.translate('profiles.' + key);
        const make = (tag, key) => {
            const node = document.createElement(tag);
            if (key) { node.textContent = t(key); node.setAttribute('data-i18n', 'batch:profiles.' + key); }
            return node;
        };
        root.classList.add('download-profiles');
        const label = make('label', 'label');
        const heading = make('div');
        heading.className = 'download-profiles-heading';
        const modified = make('span', 'modified');
        modified.className = 'download-profiles-modified';
        heading.append(label, modified);
        const select = make('select');
        select.className = adapter.inputClass || '';
        select.id = 'download-profile-' + ++mountId;
        label.htmlFor = select.id;
        select.setAttribute('aria-label', t('label'));
        const picker = make('div');
        picker.className = 'download-profiles-picker';
        const actions = make('div');
        actions.className = 'download-profiles-actions';
        const management = make('details');
        management.className = 'download-profiles-management';
        const summary = make('summary', 'manage');
        management.append(summary, actions);
        management.append(make('p', 'scope'));
        management.addEventListener('keydown', event => {
            if (event.key === 'Escape' && management.open) {
                event.preventDefault();
                event.stopPropagation();
                management.open = false;
                summary.focus();
            }
        });
        const status = make('p');
        status.className = 'download-profiles-status';
        status.setAttribute('role', 'status');
        const help = make('p', adapter.draft ? 'draft-help' : 'help');
        help.className = 'download-profiles-help';
        picker.append(select);
        root.append(heading, picker, help, management, status);
        const buttons = [];
        let renderedCatalog;
        function render() {
            if (!current()) return;
            const selected = adapter.selected();
            if (renderedCatalog !== catalog) {
                select.replaceChildren();
                const custom = make('option', 'custom');
                custom.value = '';
                select.append(custom);
                for (const profile of catalog?.profiles || []) {
                    const option = make('option');
                    option.value = profile.id;
                    option.textContent = profile.name;
                    select.append(option);
                }
                renderedCatalog = catalog;
            }
            select.value = selected || '';
            select.disabled = busy || !catalog;
            buttons.forEach(button => {
                button.disabled = busy || !catalog || button.dataset.requiresSelection === '1' && !select.value;
            });
            reload.disabled = busy;
            root.setAttribute('aria-busy', String(busy));
            refreshStatus();
        }
        function refreshStatus() {
            if (!current()) return;
            const applied = catalog?.profiles.find(profile => profile.id === adapter.selected());
            modified.hidden = !applied || same(adapter.capture(), {settings: applied.settings, filters: applied.filters});
        }
        function error(error) {
            message(['profiles.conflict', 'profiles.invalid', 'profiles.unavailable',
                'profiles.too-large'].includes(error?.message) ? error.message.slice(9) : 'failed', 'error');
        }
        function message(key, tone = '') {
            status.textContent = key ? t(key) : '';
            if (key) status.setAttribute('data-i18n', 'batch:profiles.' + key);
            else status.removeAttribute('data-i18n');
            status.dataset.tone = tone;
        }
        function restoreFocus(focused) {
            if (current() && focused?.isConnected
                && (document.activeElement === document.body || document.activeElement === root.closest('dialog')))
                (focused.disabled ? select : focused).focus();
        }
        async function request(options) {
            const response = await fetch(BASE + '/api/batch/profiles', {
                credentials: 'same-origin', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(15000)]), ...options
            });
            if (!response.ok) {
                const code = response.status === 409 ? 'conflict' : response.status === 413 ? 'too-large' : 'failed';
                throw new Error('profiles.' + code);
            }
            return response.json();
        }
        async function load() {
            if (busy) return;
            const focused = document.activeElement;
            busy = true;
            message('loading');
            render();
            try { const result = await request(); if (current()) { catalog = result; message(''); } }
            catch (failure) { if (current()) error(failure); }
            finally { busy = false; render(); restoreFocus(focused); }
        }
        async function run(action) {
            if (busy || !catalog) return;
            const focused = document.activeElement;
            busy = true;
            message('');
            render();
            try {
                await action();
                if (current()) { busy = false; render(); }
            } catch (failure) {
                busy = false;
                if (current()) {
                    if (failure.message === 'profiles.conflict') catalog = null;
                    render();
                    error(failure);
                }
            } finally {
                restoreFocus(focused);
            }
        }
        async function save(profiles) {
            const result = await request({method: 'PUT', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({revision: catalog.revision, profiles})});
            if (current()) { catalog = result; message('saved', 'success'); }
        }
        function button(key, requiresSelection, action, parent = actions, primary = false) {
            const button = make('button', key);
            button.type = 'button';
            button.className = primary ? adapter.primaryButtonClass : adapter.buttonClass;
            if (key === 'delete') button.classList.add('credential-delete');
            button.dataset.requiresSelection = requiresSelection ? '1' : '0';
            button.addEventListener('click', () => run(action));
            buttons.push(button);
            parent.append(button);
        }
        select.addEventListener('change', () => {
            if (busy) return;
            const profile = catalog?.profiles.find(profile => profile.id === select.value);
            if (!profile) {
                if (!select.value) { adapter.select(''); render(); }
                return;
            }
            run(async () => {
                await adapter.apply(clone(profile));
                if (current()) {
                    adapter.select(profile.id);
                    adapter.applied?.();
                }
            });
        });
        button('new', false, async () => {
            const name = await adapter.prompt(t('name'), '');
            if (!current() || name == null) return;
            if (!name.trim() || name.trim().length > 80) throw new Error('profiles.invalid');
            const profile = {id: crypto.randomUUID(), name: name.trim(), ...adapter.capture()};
            await save([...catalog.profiles, profile]);
            if (current()) adapter.select(profile.id);
        }, picker);
        button('save', true, async () => {
            const id = adapter.selected();
            if (!await adapter.confirm('overwrite') || !current()) return;
            const values = adapter.capture();
            await save(catalog.profiles.map(profile => profile.id === id ? {...profile, ...values} : profile));
        }, picker, true);
        button('rename', true, async () => {
            const profile = catalog.profiles.find(profile => profile.id === adapter.selected());
            const name = await adapter.prompt(t('name'), profile.name);
            if (name == null || !current()) return;
            if (!name.trim() || name.trim().length > 80) throw new Error('profiles.invalid');
            await save(catalog.profiles.map(item => item.id === profile.id ? {...item, name: name.trim()} : item));
        });
        button('delete', true, async () => {
            const id = adapter.selected();
            if (!await adapter.confirm('delete-confirm') || !current()) return;
            await save(catalog.profiles.filter(profile => profile.id !== id));
            if (current()) adapter.select('');
        });
        const reload = make('button', 'reload');
        reload.type = 'button';
        reload.className = adapter.buttonClass;
        reload.addEventListener('click', () => { if (!busy) load(); });
        actions.append(reload);
        root.addEventListener('focusin', refreshStatus);
        const controls = {refresh: refreshStatus, dispose: () => {controller.abort(); root.removeEventListener('focusin', refreshStatus);}};
        mounts.set(root, controls);
        render();
        load();
        return controls;
    }

    function refresh() {
        document.querySelectorAll('.download-profiles').forEach(root => mounts.get(root)?.refresh());
    }
    return Object.freeze({capture, prepare, mount, same, registerSettings, refresh});
})();
