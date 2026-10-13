'use strict';
/* 数据库修复表单与市场两种渲染器共用；目录请求失败时仍可检查和保存连接。 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var root, snapshot, draft, statusNode, checkButton, saveButton, migrationNode;
    var migrationPlan = null, migrationState = null, planRevision = -1;
    var dirty = false, busy = false, revision = 0, generation = 0, request = null;
    var enabledOwner = '';
    var feedback = 'database.loading', result = null;
    function el(tag, text, parent) {
        var node = document.createElement(tag);
        if (text) node.textContent = text;
        parent.appendChild(node);
        return node;
    }
    function changed() {
        dirty = true; revision += 1; result = null; feedback = ''; migrationPlan = null;
        renderMigration();
        updateStatus();
    }
    function updateStatus() {
        if (statusNode) statusNode.textContent = feedback ? PMK.t(feedback, null, result || {}) : '';
        if (checkButton) checkButton.disabled = busy || !snapshot;
        if (saveButton) saveButton.disabled = busy || !snapshot;
        if (root) root.setAttribute('aria-busy', String(busy));
    }
    function field(parent, key, type, value) {
        var row = el('label', '', parent);
        row.className = 'pmk-database-field';
        el('span', PMK.t('database.' + key), row);
        var input = el('input', '', row);
        input.id = 'pmk-database-' + key;
        input.type = type;
        input.value = value || '';
        input.autocomplete = type === 'password' ? 'new-password' : 'off';
        return input;
    }
    function render() {
        if (!root) return;
        var wasOpen = root.querySelector('details');
        var open = wasOpen ? wasOpen.open : false;
        var activeId = document.activeElement && document.activeElement.id;
        root.replaceChildren();
        var details = el('details', '', root);
        details.open = open;
        el('summary', PMK.t('database.title'), details);
        el('p', PMK.t('database.notice'), details);
        var refresh = el('button', PMK.t('refresh'), details);
        refresh.type = 'button'; refresh.className = 'pmk-btn pmk-btn--gray';
        refresh.disabled = busy;
        refresh.addEventListener('click', refreshSettings);
        statusNode = el('p', '', details);
        statusNode.setAttribute('role', 'status'); statusNode.setAttribute('aria-live', 'polite');
        if (snapshot && draft) {
            var state = snapshot.state || {};
            el('p', PMK.t('database.current', null, {target: state.activeTarget && state.activeTarget !== 'DATABASE_UNAVAILABLE' ? state.activeTarget : PMK.t('database.unavailable')}), details);
            el('p', PMK.t('database.next', null, {target: state.nextTarget || PMK.t('database.unavailable')}), details);
            if (state.overriddenKeys && state.overriddenKeys.length)
                el('p', PMK.t('database.overridden', null, {keys: state.overriddenKeys.join(', ')}), details);
            var selected = snapshot.selectedProvider;
            if (selected && selected.enableAllowed && enabledOwner !== selected.owner) {
                var enable = el('button', PMK.t('database.enable', null, {owner: selected.owner}), details);
                enable.type = 'button'; enable.className = 'pmk-btn pmk-btn--gray'; enable.disabled = busy;
                enable.addEventListener('click', enableSelected);
            }
            if (snapshot.settings.connectionRedacted) el('p', PMK.t('database.redacted'), details);
            var form = el('form', '', details);
            form.className = 'pmk-database-form';
            var label = el('label', '', form);
            label.className = 'pmk-database-field';
            el('span', PMK.t('database.provider'), label);
            var select = el('select', '', label);
            select.id = 'pmk-database-provider';
            var providers = snapshot.providers || [];
            if (!providers.some(function (p) { return p.owner === draft.provider; })) {
                var missing = el('option', draft.provider || PMK.t('database.select'), select);
                missing.value = draft.provider;
            }
            providers.forEach(function (provider) {
                var option = el('option', provider.owner + ' (' + provider.databaseId + ')', select);
                option.value = provider.owner;
            });
            select.value = draft.provider;
            var remote = el('div', '', form);
            remote.className = 'pmk-database-remote';
            function renderConnectionFields() {
                remote.replaceChildren();
                var provider = providers.find(function (p) { return p.owner === draft.provider; });
                var bindings = {'database.jdbc-url': 'jdbcUrl', 'database.username': 'username', 'database.password': 'password'};
                (provider ? provider.connectionFields : []).forEach(function (definition) {
                    var key = bindings[definition.key];
                    if (!key || (definition.type !== 'STRING' && definition.type !== 'PASSWORD')) return;
                    var secret = definition.type === 'PASSWORD';
                    var input = field(remote, key, secret ? 'password' : 'text', draft[key]);
                    input.addEventListener('input', function () {
                        draft[key] = input.value;
                        if (secret) draft.passwordAction = input.value ? 'REPLACE' : 'KEEP';
                        changed();
                    });
                    if (!secret) return;
                    input.placeholder = PMK.t(snapshot.settings.passwordStored ? 'database.password-kept' : 'database.password-empty');
                    var clearLabel = el('label', '', remote);
                    clearLabel.className = 'pmk-database-clear';
                    var clear = el('input', '', clearLabel);
                    clear.type = 'checkbox'; clear.checked = draft.passwordAction === 'CLEAR';
                    input.disabled = clear.checked;
                    el('span', PMK.t('database.clear-password'), clearLabel);
                    clear.addEventListener('change', function () {
                        draft.passwordAction = clear.checked ? 'CLEAR' : draft.password ? 'REPLACE' : 'KEEP';
                        input.disabled = clear.checked; changed();
                    });
                });
            }
            select.addEventListener('change', function () { draft.provider = select.value; changed(); renderConnectionFields(); });
            renderConnectionFields();
            var actions = el('div', '', form);
            actions.className = 'pmk-recovery-actions';
            checkButton = el('button', PMK.t('database.check'), actions);
            checkButton.type = 'button'; checkButton.className = 'pmk-btn pmk-btn--gray';
            checkButton.addEventListener('click', function () { perform('check'); });
            saveButton = el('button', PMK.t('database.migrate'), actions);
            saveButton.type = 'submit'; saveButton.className = 'pmk-btn pmk-btn--primary';
            form.addEventListener('submit', function (event) { event.preventDefault(); perform('save'); });
            migrationNode = el('div', '', details);
            migrationNode.setAttribute('aria-live', 'polite');
            renderMigration();
            var guide = el('a', PMK.t('database.guide'), details);
            var address = new URL(snapshot.migrationGuide, global.location.href);
            if (address.protocol === 'https:' || address.protocol === 'http:') guide.href = address.href;
            guide.target = '_blank'; guide.rel = 'noopener noreferrer';
        }
        updateStatus();
        var focused = activeId && document.getElementById(activeId);
        if (focused && root.contains(focused)) focused.focus();
    }
    function renderMigration() {
        if (!migrationNode) return;
        migrationNode.replaceChildren();
        var current = migrationPlan || migrationState;
        if (!current) return;
        if (current.status) el('p', PMK.t('database.migration.status.' + current.status.toLowerCase().replaceAll('_', '-')), migrationNode);
        (current.stores || []).forEach(function (store) {
            var item = el('div', '', migrationNode);
            el('strong', store.owner, item);
            el('p', PMK.t('database.migration.source', null, {target: store.source}), item);
            el('p', PMK.t('database.migration.target', null, {target: store.target}), item);
            el('p', PMK.t('database.migration.phase.' + store.phase.toLowerCase().replaceAll('_', '-')), item);
            if (store.rebuildObjects && store.rebuildObjects.length)
                el('p', PMK.t('database.migration.rebuild', null, {objects: store.rebuildObjects.join(', ')}), item);
        });
        if (current.failure) el('p', PMK.t('database.migration.failure', null, {code: current.failure}), migrationNode);
        if (migrationPlan && migrationPlan.ready) {
            el('p', PMK.t('database.migration.confirm-notice'), migrationNode);
            var confirm = el('button', PMK.t('database.migration.confirm'), migrationNode);
            confirm.type = 'button'; confirm.className = 'pmk-btn pmk-btn--primary';
            confirm.disabled = busy || planRevision !== revision;
            confirm.addEventListener('click', confirmMigration);
        }
    }
    async function confirmMigration() {
        if (busy || !migrationPlan || !migrationPlan.ready || planRevision !== revision) return;
        var plan = migrationPlan, token = ++generation;
        request = new AbortController(); busy = true; updateStatus(); renderMigration();
        try {
            var value = await PMK.api.startDatabaseMigration(plan.token, request.signal);
            if (token !== generation) return;
            if (!value || value.accepted !== true) throw new Error('MIGRATION_NOT_ACCEPTED');
            migrationState = {status: 'PENDING', failure: '', stores: plan.stores};
            migrationPlan = null; draft.password = ''; draft.passwordAction = 'KEEP';
            feedback = 'database.migration.status.pending';
        } catch (failure) {
            if (token === generation) feedback = 'database.migration.failed';
        } finally {
            if (token === generation) { busy = false; request = null; render(); }
        }
    }
    async function enableSelected() {
        var selected = snapshot && snapshot.selectedProvider;
        if (busy || !selected || !selected.enableAllowed || enabledOwner === selected.owner || !global.PixivFeedback) return;
        var token = ++generation;
        busy = true; render();
        try {
            var confirmed = await global.PixivFeedback.confirm({
                title: PMK.t('database.enable', null, {owner: selected.owner}),
                message: PMK.t('database.enable-confirm'),
                confirmLabel: PMK.t('database.enable', null, {owner: selected.owner}),
                cancelLabel: PMK.t('install.trust.cancel')
            });
            if (!confirmed || token !== generation) return;
            request = new AbortController();
            var value = await PMK.api.enableDatabaseProvider(selected.owner, request.signal);
            if (token !== generation) return;
            if (!value || value.enabled !== true || value.id !== selected.owner) throw new Error('ENABLE_NOT_CONFIRMED');
            enabledOwner = selected.owner; feedback = 'database.enabled'; result = null;
        } catch (failure) {
            if (token === generation) feedback = 'database.save-failed';
        } finally {
            if (token === generation) { busy = false; request = null; render(); }
        }
    }
    async function refreshSettings() {
        if (busy) return;
        var token = ++generation;
        request = new AbortController(); busy = true; updateStatus();
        try {
            var value = await PMK.api.fetchDatabaseSettings(request.signal);
            if (token !== generation) return;
            snapshot = value;
            try { migrationState = await PMK.api.fetchDatabaseMigration(request.signal); }
            catch (failure) { /* 保留上次结果；连接恢复后由同一刷新操作重新读取。 */ }
            if (token !== generation) return;
            if (!dirty) draft = {provider: value.settings.provider, jdbcUrl: value.settings.jdbcUrl,
                username: value.settings.username, passwordAction: 'KEEP', password: ''};
            if (feedback === 'database.loading' || feedback === 'database.failed') feedback = '';
        } catch (failure) {
            if (token === generation) feedback = feedback === 'database.saved' ? 'database.saved-status-unavailable' : 'database.failed';
        } finally {
            if (token === generation) { busy = false; request = null; render(); }
        }
    }
    async function perform(action) {
        if (busy || !snapshot || !draft) return;
        var token = ++generation, edited = revision;
        var submitted = {provider: draft.provider, jdbcUrl: draft.jdbcUrl, username: draft.username,
            passwordAction: draft.passwordAction};
        if (draft.passwordAction === 'REPLACE') submitted.password = draft.password;
        request = new AbortController(); busy = true; result = null;
        feedback = action === 'check' ? 'database.checking' : 'database.saving'; updateStatus();
        try {
            var value = await (action === 'check' ? PMK.api.checkDatabase : PMK.api.previewDatabaseMigration)(submitted, request.signal);
            if (token !== generation) return;
            if (action === 'save') {
                if (edited !== revision) feedback = 'database.stale';
                else {
                    migrationPlan = value; planRevision = edited;
                    feedback = value.ready ? 'database.migration.ready' : 'database.migration.failure';
                    result = {code: value.failure};
                }
            } else if (edited !== revision) feedback = 'database.stale';
            else {
                var outcomes = {CONNECTED: 'connected', NOT_CREATED: 'not-created', FAILED: 'connection-failed', BUSY: 'busy'};
                feedback = 'database.' + (outcomes[value.outcome] || 'failed');
                result = {target: value.target, sqlState: value.sqlState};
            }
        } catch (failure) {
            if (token === generation) feedback = failure.body && failure.body.code === 'gui.config.database.save-invalid'
                ? 'database.save-invalid' : action === 'save' ? 'database.save-failed' : 'database.failed';
        } finally {
            if (token === generation) {
                busy = false; request = null; updateStatus(); renderMigration();
            }
        }
    }
    PMK.database = {
        refresh: refreshSettings,
        render: render,
        mount: function () {
            root = document.getElementById('pmk-database');
            render(); refreshSettings();
            global.addEventListener('pagehide', function () {
                generation += 1; if (request) request.abort(); request = null; busy = false;
                if (feedback === 'database.checking') feedback = 'database.stale';
                if (feedback === 'database.saving') feedback = 'database.save-failed';
            });
            global.addEventListener('pageshow', function (event) { if (event.persisted) refreshSettings(); });
        }
    };
})(window);
