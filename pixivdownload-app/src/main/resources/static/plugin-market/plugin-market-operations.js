'use strict';
/* 获取记录只在可见页面轮询；离页后由核心查询入口返回同一操作，不重发安装。 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var records = [];
    var container, refreshButton, timer, watchedId, busy = false, disposed = false;

    function render(error) {
        if (!container) return;
        container.textContent = '';
        if (error || !records.length) {
            var empty = document.createElement('p');
            empty.textContent = PMK.t(error ? 'operations.query-failed' : 'operations.empty');
            container.appendChild(empty);
        }
        records.forEach(function (record) {
            var row = document.createElement('p');
            var result = record.result || record.failure;
            var details = [PMK.t('operations.entry', '', {
                plugin: record.pluginId, version: record.version, source: record.repositoryId,
                state: PMK.t('operations.state.' + (record.finished ? 'finished' : record.started ? record.operation : 'prepared'))
            })];
            if (result) details.push(result.message || result.error || result.code || result.outcome);
            if (result && result.dependencyInstallResults && result.dependencyInstallResults.length) {
                details.push(PMK.t('operations.dependencies', '', {items: result.dependencyInstallResults.map(function (item) {
                    return item.pluginId + ' ' + item.version + ' (' + (item.transactionId || item.outcome) + ')';
                }).join(', ')}));
            }
            details.push(PMK.t('operations.identity', '', {id: record.id, transaction: record.transactionId || '—'}));
            if (!record.started) details.push(PMK.t('operations.not-started'));
            row.textContent = details.join('\n');
            row.className = 'pmk-operation-entry';
            container.appendChild(row);
        });
    }

    async function refresh() {
        clearTimeout(timer);
        if (!container || busy || disposed || document.hidden) return;
        busy = true;
        refreshButton.disabled = true;
        try {
            records = await PMK.api.fetchOperations();
            render(false);
        } catch (error) {
            render(true);
        } finally {
            busy = false;
            refreshButton.disabled = false;
            if (!disposed && !document.hidden && (watchedId || records.some(function (item) { return item.started && !item.finished; }))) {
                timer = setTimeout(refresh, 2000);
            }
        }
    }

    PMK.operations = {
        refresh: refresh,
        watch: function (id) { watchedId = id; refresh(); },
        settled: function (id) { if (watchedId === id) watchedId = null; refresh(); },
        render: function () { render(false); },
        mount: function () {
            container = document.getElementById('pmk-operation-list');
            refreshButton = document.getElementById('pmk-operation-refresh');
            if (!container || !refreshButton) return;
            refreshButton.addEventListener('click', refresh);
            document.addEventListener('visibilitychange', function () {
                clearTimeout(timer);
                if (!document.hidden) refresh();
            });
            global.addEventListener('pagehide', function () { disposed = true; clearTimeout(timer); });
            global.addEventListener('pageshow', function () { disposed = false; refresh(); });
            refresh();
        }
    };
})(window);
