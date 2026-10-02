'use strict';
/* 安装记录拥有独立 DOM；Vue 与基础视图只提供挂载位置，不重建记录或重发安装。 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var records = [], rows = new Map();
    var timer, watchedId, busy = false, disposed = false, mounted = false, generation = 0;
    var loaded = false, queryFailed = false, expanded = false, refreshQueued = false;
    var button, buttonLabel, badge, panel, panelHost, list, notice, refreshButton, closeButton;
    var title, hint, retentionTitle, retention, manageLink;

    function element(tag, className, parent) {
        var node = document.createElement(tag);
        node.className = className || '';
        if (parent) parent.appendChild(node);
        return node;
    }

    function text(node, value) {
        value = value == null ? '' : String(value);
        if (node.textContent !== value) node.textContent = value;
    }

    function status(record) {
        var result = record.result || {};
        if (!record.finished) return record.started ? record.operation : 'prepared';
        if (result.recoveryBlocked) return 'recovery-blocked';
        if (result.rolledBack) return 'rolled-back';
        if (record.failure) return 'FAILED';
        if (result.activated) return 'activated';
        if (result.accepted && result.effectiveAfterRestart) return 'pending-restart';
        if (result.accepted) return 'accepted';
        return 'FAILED';
    }

    function tone(state) {
        if (['FAILED', 'rolled-back', 'recovery-blocked'].indexOf(state) !== -1) return 'bad';
        if (state === 'pending-restart') return 'warn';
        if (state === 'activated' || state === 'accepted') return 'ok';
        return 'info';
    }

    function createRow() {
        var row = element('li', 'pmk-operation');
        var head = element('div', 'pmk-operation-head', row);
        var name = element('h3', 'pmk-operation-name', head);
        var state = element('span', '', head);
        var meta = element('p', 'pmk-operation-meta', row);
        var message = element('p', 'pmk-operation-message', row);
        var details = element('details', 'pmk-operation-details', row);
        var summary = element('summary', '', details);
        var diagnostic = element('p', 'pmk-operation-diagnostic', details);
        return {node: row, name: name, state: state, meta: meta, message: message,
            details: details, summary: summary, diagnostic: diagnostic, fingerprint: null};
    }

    function updateRow(row, record) {
        var fingerprint = PMK.currentLang() + JSON.stringify(record);
        if (row.fingerprint === fingerprint) return;
        row.fingerprint = fingerprint;
        var state = status(record);
        var raw = record.result || record.failure;
        var result = record.result ? PMK.data.installResult(raw) : raw ? PMK.data.catalogError(raw) : null;
        text(row.name, record.pluginId + ' · ' + record.version);
        text(row.state, PMK.t('operations.state.' + state, state));
        row.state.className = 'pmk-operation-status pmk-operation-status--' + tone(state);
        var date = new Date(record.createdAt);
        var dateLabel = Number.isNaN(date.getTime()) ? '' : date.toLocaleString(PMK.currentLang());
        text(row.meta, PMK.t('operations.source', '', {source: record.repositoryId}) + (dateLabel ? ' · ' + dateLabel : ''));
        text(row.message, !record.started ? PMK.t('operations.not-started') : result ? result.message :
            record.currentPluginId && record.currentPluginId !== record.pluginId ?
                PMK.t('operations.current-plugin', '', {plugin: record.currentPluginId}) : '');
        row.message.hidden = !row.message.textContent;
        text(row.summary, PMK.t('operations.details'));
        var diagnostics = [PMK.t('operations.identity', '', {
            id: record.id, transaction: record.transactionId || (raw && raw.transactionId) || '—'
        })];
        if (raw) {
            if (raw.code || raw.outcome) diagnostics.push(raw.code || raw.outcome);
            (result.errors || []).concat(result.warnings || []).forEach(function (item) { diagnostics.push(item); });
            (raw.dependencyInstallResults || []).forEach(function (item) {
                diagnostics.push(PMK.t('operations.dependency', '', {
                    plugin: item.pluginId, version: item.version,
                    state: PMK.t('operations.state.' + status({finished: true, result: item})),
                    transaction: item.transactionId || '—'
                }));
            });
        }
        text(row.diagnostic, diagnostics.join('\n'));
    }

    function render() {
        if (!panel) return;
        var running = records.filter(function (item) { return item.started && !item.finished; }).length;
        text(buttonLabel, PMK.t('operations.title'));
        text(badge, queryFailed ? PMK.t('operations.unavailable') : running ?
            PMK.t('operations.active', '', {count: running}) : records.length);
        badge.hidden = !queryFailed && !records.length;
        badge.className = 'pmk-operation-count' + (queryFailed ? ' pmk-operation-count--bad' : '');
        button.setAttribute('aria-expanded', String(expanded));
        if (panelHost) panelHost.hidden = !expanded;
        panel.hidden = !expanded;
        text(title, PMK.t('operations.title'));
        text(hint, PMK.t('operations.hint'));
        text(refreshButton, PMK.t(busy ? 'operations.refreshing' : 'operations.refresh'));
        refreshButton.disabled = busy;
        text(closeButton, PMK.t('operations.close'));
        text(manageLink, PMK.t('seg.installed'));
        text(retentionTitle, PMK.t('operations.about'));
        text(retention, PMK.t('operations.retention'));
        notice.hidden = !queryFailed && (loaded && !!records.length);
        notice.className = 'pmk-operation-notice' + (queryFailed ? ' pmk-operation-notice--error' : '');
        notice.setAttribute('role', queryFailed ? 'alert' : 'status');
        text(notice, PMK.t(queryFailed ? 'operations.query-failed' : loaded ? 'operations.empty' : 'loading'));
        var retained = new Set();
        records.forEach(function (record, index) {
            retained.add(record.id);
            var row = rows.get(record.id);
            if (!row) { row = createRow(); rows.set(record.id, row); }
            updateRow(row, record);
            if (list.children[index] !== row.node) list.insertBefore(row.node, list.children[index] || null);
        });
        rows.forEach(function (row, id) {
            if (!retained.has(id)) { row.node.remove(); rows.delete(id); }
        });
    }

    function setExpanded(value) {
        expanded = value;
        render();
        if (expanded) refresh();
        else button.focus();
    }

    async function refresh() {
        clearTimeout(timer);
        if (!mounted || disposed || document.hidden) return;
        if (busy) { refreshQueued = true; return; }
        var token = generation;
        busy = true;
        render();
        try {
            var next = await PMK.api.fetchOperations();
            if (token === generation && !disposed) { records = next; loaded = true; queryFailed = false; }
        } catch (error) {
            if (token === generation && !disposed) queryFailed = true;
        } finally {
            busy = false;
            if (!disposed) render();
            var queued = refreshQueued;
            refreshQueued = false;
            if (!disposed && !document.hidden) {
                if (queued || token !== generation) refresh();
                else if (watchedId || records.some(function (item) { return item.started && !item.finished; }))
                    timer = setTimeout(refresh, 2000);
            }
        }
    }

    PMK.operations = {
        refresh: refresh,
        watch: function (id) { watchedId = id; refresh(); },
        settled: function (id) { if (watchedId === id) watchedId = null; refresh(); },
        render: render,
        mountButton: function (host) { if (host && button && button.parentNode !== host) host.appendChild(button); },
        mountPanel: function (host) {
            if (!host) return;
            panelHost = host;
            if (panel && panel.parentNode !== host) host.appendChild(panel);
            host.hidden = !expanded;
        },
        mount: function () {
            if (mounted) return;
            mounted = true;
            button = element('button', 'pmk-btn pmk-btn--gray');
            button.type = 'button';
            button.setAttribute('aria-controls', 'pmk-operations');
            var icon = element('i', 'fa-solid fa-clock-rotate-left', button);
            icon.setAttribute('aria-hidden', 'true');
            buttonLabel = element('span', '', button);
            badge = element('span', 'pmk-operation-count', button);
            badge.setAttribute('aria-live', 'polite');
            button.addEventListener('click', function () { setExpanded(!expanded); });
            panel = element('section', 'pmk-operations');
            panel.id = 'pmk-operations';
            panel.setAttribute('aria-labelledby', 'pmk-operations-title');
            var head = element('div', 'pmk-operations-head', panel);
            var heading = element('div', '', head);
            title = element('h2', '', heading);
            title.id = 'pmk-operations-title';
            hint = element('p', 'pmk-operation-meta', heading);
            var actions = element('div', 'pmk-operations-actions', head);
            manageLink = element('a', 'pmk-btn pmk-btn--gray', actions);
            manageLink.href = '/plugin-manage.html';
            refreshButton = element('button', 'pmk-btn pmk-btn--gray', actions);
            refreshButton.type = 'button';
            refreshButton.addEventListener('click', refresh);
            closeButton = element('button', 'pmk-btn pmk-btn--gray', actions);
            closeButton.type = 'button';
            closeButton.addEventListener('click', function () { setExpanded(false); });
            panel.addEventListener('keydown', function (event) {
                if (event.key === 'Escape') { event.stopPropagation(); setExpanded(false); }
            });
            notice = element('p', 'pmk-operation-notice', panel);
            list = element('ol', 'pmk-operation-list', panel);
            var about = element('details', 'pmk-operation-about', panel);
            retentionTitle = element('summary', '', about);
            retention = element('p', 'pmk-operation-meta', about);
            document.addEventListener('visibilitychange', function () {
                clearTimeout(timer);
                if (!document.hidden) refresh();
            });
            global.addEventListener('pagehide', function () { disposed = true; generation++; clearTimeout(timer); });
            global.addEventListener('pageshow', function () { disposed = false; refresh(); });
            refresh();
        }
    };
})(window);
