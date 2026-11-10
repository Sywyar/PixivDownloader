'use strict';

window.PixivCredentials = (() => {
    const key = 'pixiv_credential_selection';
    const accountKey = 'pixiv_account_credential';
    let catalog = null;
    let initialization = null;
    let busy = false;
    let sequence = 0;
    const views = new Set();
    const t = name => bt('batch:credentials.' + name);

    function attachPopover(trigger, popup) {
        popup.id = 'credential-popover-' + ++sequence;
        popup.setAttribute('popover', 'auto');
        trigger.popoverTargetElement = popup;
        trigger.setAttribute('aria-controls', popup.id);
        trigger.setAttribute('aria-haspopup', 'menu');
        trigger.setAttribute('aria-expanded', 'false');
        let opened = false;
        function position() {
            const anchor = trigger.getBoundingClientRect();
            const rect = {width: popup.offsetWidth, height: popup.offsetHeight};
            const width = document.documentElement.clientWidth;
            const height = window.innerHeight;
            popup.style.left = Math.max(8, Math.min(anchor.right - rect.width, width - rect.width - 8)) + 'px';
            popup.style.top = Math.max(8, Math.min(anchor.bottom + 4 + rect.height <= height - 8
                ? anchor.bottom + 4 : anchor.top - rect.height - 4, height - rect.height - 8)) + 'px';
        }
        function show() {
            if (!opened) popup.showPopover();
            position();
        }
        function hide() {
            if (opened) popup.hidePopover();
        }
        function keydown(event) {
            if (event.key === 'Escape' && opened) {
                event.preventDefault();
                event.stopPropagation();
                hide();
                trigger.focus();
            } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
                event.preventDefault();
                show();
                const items = [...popup.querySelectorAll('button')].filter(item => !item.disabled);
                const index = items.indexOf(document.activeElement);
                const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
                    : index < 0 ? (event.key === 'ArrowUp' ? items.length - 1 : 0)
                    : (index + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length;
                items[next]?.focus();
            }
        }
        const escape = event => { if (event.key === 'Escape') keydown(event); };
        popup.addEventListener('beforetoggle', event => {
            opened = event.newState === 'open';
            trigger.setAttribute('aria-expanded', String(opened));
            if (opened) {
                window.addEventListener('resize', position);
                window.addEventListener('scroll', position, true);
                document.addEventListener('keydown', escape, true);
            } else {
                window.removeEventListener('resize', position);
                window.removeEventListener('scroll', position, true);
                document.removeEventListener('keydown', escape, true);
            }
        });
        trigger.addEventListener('click', event => {
            event.preventDefault();
            if (opened) hide();
            else { show(); popup.querySelector('button:not(:disabled)')?.focus(); }
        });
        trigger.addEventListener('keydown', keydown);
        popup.addEventListener('keydown', keydown);
        for (const element of [trigger, popup]) {
            element.addEventListener('focusout', event => {
                if (event.relatedTarget && !popup.contains(event.relatedTarget) && event.relatedTarget !== trigger) hide();
            });
        }
        return hide;
    }

    function selection() {
        if (!isAdmin) return [];
        try {
            const values = JSON.parse(storeGet(key) || '[]');
            if (!Array.isArray(values) || values.length > 8 || new Set(values).size !== values.length
                || values.some(id => typeof id !== 'string' || !/^[a-zA-Z0-9-]{1,64}$/.test(id))) return [];
            return values;
        } catch { return []; }
    }
    function headers(ids = selection()) {
        return isAdmin && ids.length ? {'X-Acquisition-Credentials': ids.join(',')} : null;
    }
    function changed() {
        for (const view of [...views]) {
            if (!view.root.isConnected) views.delete(view);
            else view.render();
        }
        if (typeof applyCookieDependentUi === 'function') applyCookieDependentUi();
        if (typeof refreshCookieUi === 'function') refreshCookieUi();
    }
    async function request(options) {
        const response = await fetch(BASE + '/api/acquisition/credentials', {credentials: 'same-origin', signal: AbortSignal.timeout(15000), ...options});
        if (!response.ok) throw new Error(response.status === 409 ? 'conflict' : 'failed');
        return response.json();
    }
    async function reload() {
        catalog = await request();
        changed();
    }

    function accountSelection() {
        if (!isAdmin || !catalog) return null;
        const id = storeGet(accountKey);
        const entries = catalog.entries.filter(entry => entry.enabled);
        const entry = entries.find(entry => entry.id === id) || entries[0];
        return entry ? {ids: [entry.id], label: entry.name} : null;
    }
    function accountHeaders() {
        const selected = accountSelection();
        if (!selected) throw new Error(t('account-required'));
        return headers(selected.ids);
    }
    function accountIdentity() { return JSON.stringify([accountSelection()?.ids[0], catalog?.revision]); }
    function mountAccount(root, {inputClass = '', onChange = () => {}, disabled = false} = {}) {
        if (!isAdmin) return;
        const label = document.createElement('label');
        const select = document.createElement('select');
        const status = document.createElement('span');
        select.id = 'account-cookie-' + ++sequence;
        label.htmlFor = select.id;
        label.textContent = t('account-label');
        label.setAttribute('data-i18n', 'batch:credentials.account-label');
        select.className = inputClass;
        status.setAttribute('role', 'status');
        root.classList.add('credential-account');
        root.append(label, select, status);
        let identity;
        const render = () => {
            const selected = accountSelection();
            const current = JSON.stringify([selected?.ids[0], catalog?.revision]);
            select.replaceChildren();
            if (!selected) {
                const placeholder = document.createElement('option');
                placeholder.value = '';
                placeholder.textContent = t(catalog ? 'account-required' : 'account-loading');
                placeholder.setAttribute('data-i18n', 'batch:credentials.' + (catalog ? 'account-required' : 'account-loading'));
                placeholder.disabled = true;
                select.appendChild(placeholder);
            }
            for (const entry of catalog?.entries || []) {
                if (!entry.enabled) continue;
                const option = document.createElement('option');
                option.value = entry.id;
                option.textContent = entry.name;
                select.appendChild(option);
            }
            select.value = selected?.ids[0] || '';
            select.disabled = disabled || !catalog?.entries.some(entry => entry.enabled);
            if (identity !== undefined && current !== identity) onChange();
            identity = current;
        };
        select.addEventListener('change', async () => {
            select.disabled = true;
            status.textContent = '';
            try {
                await persistStoreEntries({[accountKey]: select.value});
                changed();
            } catch { status.textContent = t('failed'); render(); }
        });
        for (const view of views) if (!view.root.isConnected) views.delete(view);
        const view = {root, render};
        views.add(view);
        render();
        return () => views.delete(view);
    }
    async function choose({signal, openDialog, single = false} = {}) {
        if (!isAdmin) throw new Error(bt('batch:schedule.error.no-credential'));
        const snapshot = await request({signal: signal
            ? AbortSignal.any([signal, AbortSignal.timeout(15000)]) : AbortSignal.timeout(15000)})
            .catch(() => { throw new Error(t('pick-failed')); });
        signal?.throwIfAborted();
        const entries = snapshot.entries.filter(entry => entry.enabled);
        if (!entries.length) throw new Error(bt('batch:schedule.error.no-credential'));
        const result = ids => ({ids: [...ids], label: ids.map(id => entries.find(entry => entry.id === id).name).join('、')});
        if (entries.length === 1) return result([entries[0].id]);
        return new Promise(resolve => {
            const node = (tag, className, text) => {
                const element = document.createElement(tag);
                element.className = className;
                if (text) element.textContent = text;
                return element;
            };
            const dialog = node('dialog', 'credential-editor-dialog download-credentials credential-picker');
            dialog.id = 'credential-picker-' + ++sequence;
            const body = node('div', 'credential-picker-body');
            const hint = node('p', 'download-profiles-help', t(single ? 'pick-single-help' : 'pick-help'));
            const list = node('div', 'credential-picker-list');
            const actions = node('div', 'download-profiles-actions');
            const cancel = node('button', 'btn btn-gray', t('cancel'));
            const confirm = node('button', 'btn btn-blue', t('pick-confirm'));
            cancel.type = confirm.type = 'button';
            confirm.disabled = true;
            const ids = [];
            let selection = null;
            let modal;
            const close = () => modal ? modal.close() : dialog.close();
            for (const entry of entries) {
                const row = node('label', 'credential-picker-row');
                const checkbox = node('input', '');
                checkbox.type = single ? 'radio' : 'checkbox';
                checkbox.name = dialog.id;
                const order = node('span', 'credential-order');
                order.setAttribute('aria-hidden', 'true');
                row.append(checkbox, node('span', 'credential-picker-name', entry.name), order);
                checkbox.addEventListener('change', () => {
                    if (single) ids.length = 0;
                    if (checkbox.checked) ids.push(entry.id);
                    else ids.splice(ids.indexOf(entry.id), 1);
                    for (const [index, item] of [...list.children].entries()) {
                        const rank = ids.indexOf(entries[index].id);
                        item.querySelector('.credential-order').textContent = single || rank < 0 ? '' : String(rank + 1);
                    }
                    confirm.disabled = !ids.length;
                });
                list.appendChild(row);
            }
            body.append(hint, list);
            actions.append(cancel, confirm);
            cancel.addEventListener('click', close);
            confirm.addEventListener('click', () => {
                if (!ids.length || signal?.aborted) return;
                selection = result(ids);
                close();
            });
            dialog.addEventListener('cancel', event => { event.preventDefault(); close(); });
            dialog.addEventListener('keydown', event => {
                if (event.key !== 'Escape') return;
                event.preventDefault();
                event.stopPropagation();
                close();
            });
            dialog.addEventListener('close', () => {
                signal?.removeEventListener('abort', close);
                dialog.remove();
                resolve(signal?.aborted ? null : selection);
            }, {once: true});
            document.body.appendChild(dialog);
            if (openDialog) {
                modal = openDialog({root: dialog, title: t('pick-title'), body, footer: actions});
            } else {
                const title = node('h2', '', t('pick-title'));
                title.id = dialog.id + '-title';
                dialog.setAttribute('aria-labelledby', title.id);
                dialog.append(title, body, actions);
                dialog.showModal();
            }
            signal?.addEventListener('abort', close, {once: true});
            if (signal?.aborted) close();
        });
    }
    async function save(entries) {
        catalog = await request({method: 'PUT', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({revision: catalog.revision, entries})});
    }
    async function select(ids) {
        await persistStoreEntries({[key]: JSON.stringify(ids)});
        changed();
    }
    async function initialize() {
        if (!isAdmin) return;
        if (initialization) return initialization;
        initialization = (async () => {
            await reload();
            const legacy = getCookie();
            if (legacy.trim()) await savePrimary(legacy, 'header');
        })().catch(failure => { initialization = null; throw failure; });
        return initialization;
    }
    async function savePrimary(raw, format) {
        if (busy) throw new Error('failed');
        busy = true;
        changed();
        try { await writePrimary(raw, format); }
        finally { busy = false; changed(); }
    }
    async function writePrimary(raw, format) {
        if (!isAdmin) throw new Error('failed');
        if (!catalog) await reload();
        if (!raw) {
            await persistStoreEntries({[key]: '[]', pixiv_cookie: null});
            changed();
            return;
        }
        const validation = validateAndParseCookie(raw, format);
        if (!validation.ok) throw new Error('failed');
        const parsed = parseCookieToHeaderString(raw, format);
        const ids = selection().filter(id => catalog.entries.some(entry => entry.id === id));
        const existing = catalog.entries.find(entry => entry.id === ids[0]);
        const id = existing?.id || (catalog.entries.some(entry => entry.id === 'legacy')
            ? crypto.randomUUID() : 'legacy');
        const next = {id, name: existing?.name || t('primary'), enabled: true, secret: parsed};
        await save([...catalog.entries.filter(entry => entry.id !== id), next]);
        // 服务端已验证加密写入；页面选择与旧明文移除共用一次状态保存。
        await persistStoreEntries({[key]: JSON.stringify([id, ...ids.filter(value => value !== id)]), pixiv_cookie: null});
        const input = document.getElementById('cookie-input');
        if (input) input.value = '';
        changed();
    }
    function mount(root, options = {}) {
        if (!root || !isAdmin) return;
        root.classList.add('download-credentials');
        const node = (tag, text) => {
            const result = document.createElement(tag);
            if (text) {
                result.textContent = t(text);
                result.setAttribute('data-i18n', 'batch:credentials.' + text);
            }
            return result;
        };
        const button = (parent, text, action, primary = false) => {
            const control = node('button', text);
            control.type = 'button';
            control.className = options.buttonClass?.(primary) || (primary ? 'btn btn-blue' : 'btn btn-gray');
            if (action) control.addEventListener('click', action);
            parent.append(control);
            return control;
        };
        const head = node('div');
        head.className = 'credential-toolbar';
        head.append(node('h3', 'title'));
        const toolbar = node('div');
        toolbar.className = 'download-profiles-actions';
        head.append(toolbar);
        const refresh = button(toolbar, 'reload', () => run(reload));
        refresh.classList.add('credential-refresh');
        refresh.setAttribute('data-i18n-title', 'batch:credentials.reload-help');
        refresh.title = t('reload-help');
        const add = button(toolbar, 'add', () => openEditor(null), true);
        const help = node('p', 'help');
        help.className = 'download-profiles-help';
        const list = node('div');
        list.className = 'credential-list';
        const empty = node('p', 'empty');
        empty.className = 'credential-empty';
        const missing = node('div');
        missing.className = 'credential-missing';
        missing.append(node('p', 'selection-unavailable'));
        button(missing, 'clear-selection', () => run(() => select([])));
        const status = node('p');
        status.className = 'download-profiles-status';
        status.setAttribute('role', 'status');
        let statusKey = '';
        function message(key, tone = '') {
            statusKey = key;
            status.textContent = key ? t(key) : '';
            status.dataset.tone = tone;
            editorStatus.textContent = status.textContent;
            editorStatus.dataset.tone = tone;
        }
        const dialog = node('dialog');
        dialog.id = 'credential-editor-' + ++sequence;
        dialog.className = 'credential-editor-dialog download-credentials';
        const editorStatus = node('p');
        editorStatus.className = 'download-profiles-status';
        editorStatus.setAttribute('role', 'status');
        const editor = node('form');
        editor.className = 'credential-editor';
        editor.hidden = true;
        const editorTitle = node('h4');
        editorTitle.id = dialog.id + '-heading';
        dialog.setAttribute('aria-labelledby', editorTitle.id);
        const nameLabel = node('label');
        nameLabel.append(node('span', 'name'));
        const name = node('input');
        name.className = options.inputClass || '';
        name.maxLength = 80;
        name.autocomplete = 'off';
        name.required = true;
        nameLabel.append(name);
        const secretLabel = node('label');
        secretLabel.append(node('span', 'secret'));
        const secret = node('textarea');
        secret.className = (options.inputClass || '') + ' credential-secret is-masked';
        secret.rows = 3;
        secret.maxLength = 16384;
        secret.autocomplete = 'off';
        secret.spellcheck = false;
        secretLabel.append(secret);
        const formatLabel = node('label');
        formatLabel.append(node('span', 'format'));
        const format = node('select');
        format.className = options.inputClass || '';
        for (const value of ['header', 'json', 'netscape']) {
            const option = node('option');
            option.value = value;
            option.textContent = bt('cookie.format.' + value);
            option.setAttribute('data-i18n', 'cookie.format.' + value);
            format.append(option);
        }
        formatLabel.append(format);
        const fields = node('div');
        fields.className = 'credential-fields';
        fields.append(nameLabel, formatLabel);
        const secretTools = node('div');
        secretTools.className = 'download-profiles-actions';
        const visibility = button(secretTools, 'show', () => {
            const masked = secret.classList.toggle('is-masked');
            visibility.textContent = t(masked ? 'show' : 'hide');
            visibility.setAttribute('data-i18n', 'batch:credentials.' + (masked ? 'show' : 'hide'));
            visibility.setAttribute('aria-pressed', String(!masked));
        });
        visibility.setAttribute('aria-pressed', 'false');
        button(secretTools, 'paste', async () => {
            const revision = editorRevision;
            try {
                const value = await navigator.clipboard.readText();
                if (revision !== editorRevision || !root.isConnected || busy) return;
                secret.value = value;
                secret.focus();
            } catch {
                if (revision === editorRevision && root.isConnected) message('paste-failed', 'error');
            }
        });
        const editorHint = node('p', 'replace-help');
        const actions = node('div');
        actions.className = 'download-profiles-actions';
        let editing = null;
        let selectAfterSave = false;
        let focusAfterSave = null;
        let editorTrigger = add;
        let editorRevision = 0;
        let editorModal = null;
        function openEditor(entry, trigger = add) {
            editorRevision++;
            dialog.inert = false;
            editing = entry?.id || null;
            selectAfterSave = !entry;
            editorTrigger = trigger;
            editorTitle.textContent = t(entry ? 'replace' : 'add');
            editorTitle.setAttribute('data-i18n', 'batch:credentials.' + (entry ? 'replace' : 'add'));
            name.value = entry?.name || '';
            secret.value = '';
            secret.required = !entry;
            secret.classList.add('is-masked');
            visibility.textContent = t('show');
            visibility.setAttribute('data-i18n', 'batch:credentials.show');
            visibility.setAttribute('aria-pressed', 'false');
            format.value = 'header';
            editorHint.hidden = !entry;
            editor.hidden = false;
            message('');
            if (options.openEditor) {
                editorModal = options.openEditor(dialog, editor, actions, () => !busy);
                const heading = dialog.querySelector('h2 span');
                heading.textContent = editorTitle.textContent;
                heading.setAttribute('data-i18n', editorTitle.getAttribute('data-i18n'));
                editorTitle.hidden = true;
            } else {
                editor.append(actions);
                dialog.append(editor);
                dialog.showModal();
            }
            render();
            name.focus();
        }
        function closeEditor() {
            editorRevision++;
            dialog.inert = true;
            if (editorModal) editorModal.close();
            else dialog.close();
        }
        function editorClosed() {
            editorRevision++;
            editing = null;
            name.value = '';
            secret.value = '';
            editor.hidden = true;
            render();
            const target = editorTrigger.isConnected ? editorTrigger : add;
            if (busy) focusAfterSave = target;
            else target.focus();
        }
        dialog.addEventListener('close', editorClosed);
        dialog.addEventListener('cancel', event => { event.preventDefault(); if (!busy) closeEditor(); });
        dialog.addEventListener('keydown', event => {
            if (event.key !== 'Escape') return;
            event.preventDefault();
            event.stopPropagation();
            if (!busy) closeEditor();
        });
        dialog.addEventListener('click', event => {
            if (event.target !== dialog || busy) return;
            const rect = dialog.getBoundingClientRect();
            if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) closeEditor();
        });
        async function run(action) {
            if (busy) return;
            const focused = document.activeElement;
            busy = true;
            message('working');
            changed();
            try { const result = await action(); message(result === false ? '' : 'saved', 'success'); }
            catch (failure) { message(failure?.message === 'conflict' ? 'conflict' : 'failed', 'error'); }
            finally {
                busy = false;
                changed();
                if (focusAfterSave) focusAfterSave.focus();
                else if (focused && !focused.isConnected && root.isConnected) add.focus();
                else if (focused?.disabled && root.isConnected) (focused.closest('.credential-entry')?.querySelector('input') || add).focus();
                else if (focused?.isConnected && (document.activeElement === document.body || document.activeElement === focused)) focused.focus();
                focusAfterSave = null;
            }
        }
        const rows = new Map();
        function createRow(id) {
            const row = node('div');
            row.className = 'credential-entry';
            const label = node('label');
            const checkbox = node('input');
            checkbox.type = 'checkbox';
            const rank = node('span');
            rank.className = 'credential-rank';
            rank.setAttribute('aria-hidden', 'true');
            const text = node('span');
            text.className = 'credential-caption';
            const caption = node('span');
            const state = node('span');
            state.className = 'credential-state';
            text.append(caption, state);
            label.append(checkbox, rank, text);
            checkbox.addEventListener('change', () => {
                const checked = checkbox.checked;
                void run(() => {
                    const ids = selection();
                    return select(checked ? [...ids, id] : ids.filter(value => value !== id));
                });
            });
            const tools = node('div');
            tools.className = 'credential-row-actions';
            const moveButtons = [-1, 1].map(offset => {
                const move = button(tools, null, () => run(() => {
                    const ids = selection();
                    const index = ids.indexOf(id);
                    if (index < 0 || index + offset < 0 || index + offset >= ids.length) return;
                    [ids[index], ids[index + offset]] = [ids[index + offset], ids[index]];
                    return select(ids);
                }));
                move.classList.add('credential-move');
                move.textContent = offset < 0 ? '↑' : '↓';
                return move;
            });
            const edit = button(tools, 'replace', () => openEditor(catalog.entries.find(entry => entry.id === id), edit));
            const summary = button(tools, null, null);
            summary.classList.add('credential-more');
            summary.textContent = '⋯';
            summary.setAttribute('data-i18n-aria-label', 'batch:credentials.more');
            summary.setAttribute('data-i18n-title', 'batch:credentials.more');
            const secondary = node('div');
            secondary.className = 'credential-popover credential-menu';
            secondary.setAttribute('role', 'menu');
            secondary.setAttribute('aria-label', t('more'));
            secondary.setAttribute('data-i18n-aria-label', 'batch:credentials.more');
            const toggle = button(secondary, null, () => run(async () => {
                secondary.hidePopover();
                await save(catalog.entries.map(entry => entry.id === id ? {...entry, enabled: !entry.enabled} : entry));
            }));
            const remove = button(secondary, 'delete', () => run(async () => {
                secondary.hidePopover();
                if (!await window.PixivFeedback.confirm({
                    title: bt('batch:dialog.title.confirm'), message: t('delete-confirm'),
                    confirmLabel: bt('common:button.confirm'), cancelLabel: bt('common:button.cancel'), danger: true
                })) return false;
                await save(catalog.entries.filter(entry => entry.id !== id));
                await select(selection().filter(value => value !== id));
            }));
            remove.classList.add('credential-delete');
            toggle.setAttribute('role', 'menuitem');
            remove.setAttribute('role', 'menuitem');
            tools.append(secondary);
            const disposePopover = attachPopover(summary, secondary);
            row.append(label, tools);
            return {row, checkbox, rank, caption, state, moveButtons, edit, toggle, summary, disposePopover};
        }
        function render() {
            root.setAttribute('aria-busy', String(busy));
            const motion = window.matchMedia && !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
            const positions = new Map();
            if (motion) for (const [id, view] of rows) positions.set(id, view.row.getBoundingClientRect());
            for (const view of rows.values()) view.motion?.cancel();
            const ids = selection();
            const entries = [...(catalog?.entries || [])].sort((a, b) =>
                (ids.includes(a.id) ? ids.indexOf(a.id) : 99) - (ids.includes(b.id) ? ids.indexOf(b.id) : 99));
            missing.hidden = !ids.some(id => !entries.some(entry => entry.id === id));
            empty.hidden = !catalog || entries.length > 0;
            const focused = document.activeElement;
            for (const [id, view] of rows) {
                if (!entries.some(entry => entry.id === id)) {
                    view.disposePopover();
                    rows.delete(id);
                    const rect = positions.get(id);
                    if (motion && view.row.animate && rect?.height) {
                        view.row.inert = true;
                        view.row.classList.add('is-leaving');
                        view.row.style.top = rect.top - list.getBoundingClientRect().top + 'px';
                        view.row.style.width = rect.width + 'px';
                        view.row.animate([{opacity: 1}, {opacity: 0, transform: 'translateY(-4px)'}],
                            {duration: 140, easing: 'ease-out'}).finished.then(() => view.row.remove(), () => view.row.remove());
                    } else view.row.remove();
                }
            }
            entries.forEach((entry, order) => {
                if (!rows.has(entry.id)) rows.set(entry.id, createRow(entry.id));
                const view = rows.get(entry.id);
                const index = ids.indexOf(entry.id);
                view.checkbox.checked = index >= 0;
                view.rank.textContent = index >= 0 ? String(index + 1) : '–';
                view.rank.hidden = ids.length < 2 || index < 0;
                view.caption.textContent = entry.name;
                view.state.textContent = t(!entry.enabled ? 'disabled' : index === 0 ? 'preferred' : index > 0 ? 'backup' : 'unused');
                view.toggle.textContent = t(entry.enabled ? 'disable' : 'enable');
                view.summary.setAttribute('aria-label', t('more'));
                view.summary.title = t('more');
                view.row.dataset.selected = String(index >= 0);
                view.row.dataset.enabled = String(entry.enabled);
                view.row.dataset.preferred = String(index === 0 && entry.enabled);
                view.moveButtons.forEach((move, i) => {
                    move.hidden = ids.length < 2 || index < 0;
                    const label = t(i === 0 ? 'up' : 'down') + ' · ' + entry.name;
                    move.setAttribute('aria-label', label);
                    move.title = label;
                });
                const current = [...list.children].filter(row => !row.classList.contains('is-leaving'))[order];
                if (current !== view.row) list.insertBefore(view.row, current || null);
            });
            if (motion) for (const [id, view] of rows) {
                const before = positions.get(id);
                if (!before || !view.row.animate) continue;
                const after = view.row.getBoundingClientRect();
                const delta = before.top - after.top;
                if (Math.abs(delta) < 1) continue;
                view.motion?.cancel();
                view.motion = view.row.animate([{transform: 'translateY(' + delta + 'px)'}, {transform: 'none'}],
                    {duration: 240, easing: 'cubic-bezier(.2,.7,.2,1)'});
            }
            if (focused?.isConnected && list.contains(focused) && focused !== document.activeElement) focused.focus();
            root.querySelectorAll('button, textarea, input, select').forEach(control => {
                control.disabled = busy || !catalog;
            });
            refresh.disabled = busy;
            add.disabled = busy || !catalog || !editor.hidden;
            for (const [id, view] of rows) {
                const entry = entries.find(value => value.id === id);
                const index = ids.indexOf(id);
                view.checkbox.disabled = busy || !entry.enabled && index < 0;
                view.edit.disabled = busy || !editor.hidden;
                view.moveButtons.forEach((move, i) => {
                    move.disabled = busy || index < 0 || index + (i === 0 ? -1 : 1) < 0
                        || index + (i === 0 ? -1 : 1) >= ids.length;
                });
            }
            if (options.importButton) options.importButton.disabled = busy || appMode !== 'solo';
            if (statusKey) status.textContent = editorStatus.textContent = t(statusKey);
        }
        editor.addEventListener('submit', event => {
            event.preventDefault();
            void run(async () => {
                if (!name.value.trim() || !editing && !secret.value.trim()) throw new Error('failed');
                const validation = validateAndParseCookie(secret.value, format.value);
                if (secret.value.trim() && !validation.ok) throw new Error('failed');
                if (editing && !catalog.entries.some(entry => entry.id === editing)) throw new Error('conflict');
                const id = editing || crypto.randomUUID();
                await save([...catalog.entries.filter(entry => entry.id !== id),
                    {id, name: name.value.trim(), enabled: catalog.entries.find(entry => entry.id === id)?.enabled ?? true,
                        secret: secret.value.trim() ? parseCookieToHeaderString(secret.value, format.value) : null}]);
                // 即使页面选择保存失败，重试也更新刚保存的条目，避免重复创建。
                editing = id;
                if (selectAfterSave) await select([...selection().filter(value => value !== id), id]);
                closeEditor();
            });
        });
        const submit = button(actions, 'save', null, true);
        submit.type = 'submit';
        button(actions, 'cancel', () => { closeEditor(); message(''); });
        actions.append(submit);
        const editorBody = node('div');
        editorBody.className = 'credential-editor-body';
        editorBody.append(fields, secretLabel, editorHint, secretTools, editorStatus);
        editor.append(editorTitle, editorBody, actions);
        const submitId = dialog.id + '-form';
        editor.id = submitId;
        submit.setAttribute('form', submitId);
        dialog.append(editor);
        const guideLink = node('a');
        guideLink.textContent = bt('cookie.guide.link');
        guideLink.setAttribute('data-i18n', 'cookie.guide.link');
        guideLink.setAttribute('data-i18n-href', 'cookie.guide.url');
        guideLink.href = bt('cookie.guide.url');
        guideLink.target = '_blank';
        guideLink.rel = 'noopener';
        guideLink.className = 'credential-guide-link';
        secretTools.append(guideLink);
        if (options.importButton) {
            options.importButton.type = 'button';
            const acquisition = node('details');
            acquisition.className = 'credential-acquisition';
            const heading = node('div');
            heading.className = 'credential-acquisition-heading';
            const title = node('summary', 'import-title');
            title.id = dialog.id + '-import-heading';
            acquisition.setAttribute('aria-labelledby', title.id);
            const scope = node('p', 'import-help');
            scope.id = dialog.id + '-import-scope';
            options.importButton.setAttribute('aria-describedby', scope.id);
            heading.append(scope, options.importButton);
            const setup = node('div');
            setup.className = 'credential-import-setup';
            setup.append(node('p', 'import-requirements'), node('p', 'import-permission'));
            const caution = node('div');
            caution.className = 'credential-import-caution';
            const icon = node('span');
            icon.className = 'credential-hint-icon';
            icon.textContent = '!';
            icon.setAttribute('aria-hidden', 'true');
            caution.append(icon, node('p', 'import-risk'));
            const content = node('div');
            content.className = 'credential-acquisition-content';
            content.append(heading, setup, caution);
            if (options.importStatus) content.append(options.importStatus);
            acquisition.append(title, content);
            acquisition.addEventListener('keydown', event => {
                if (event.key !== 'Escape' || !acquisition.open) return;
                event.preventDefault();
                event.stopPropagation();
                acquisition.open = false;
                title.focus();
            });
            editorBody.append(acquisition);
        }
        root.append(head, help, list, empty, missing, dialog, status, node('p', 'scope'));
        for (const existing of views) if (!existing.root.isConnected || existing.root === root) views.delete(existing);
        const view = {root, render};
        views.add(view);
        message('loading');
        initialize().then(() => { message(''); render(); }).catch(() => { message('failed', 'error'); render(); });
        render();
        return () => {
            views.delete(view);
            editorRevision++;
            secret.value = '';
            if (dialog.open) dialog.close();
            for (const row of rows.values()) row.disposePopover();
        };
    }
    return Object.freeze({initialize, mount, choose, selection, headers, accountSelection, accountHeaders, accountIdentity, mountAccount, savePrimary, refresh: changed,
        has: () => selection().length > 0});
})();
