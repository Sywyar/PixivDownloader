'use strict';
/*
 * 插件市场页后端调用：拉取受信仓库列表 / 指定仓库 catalog / 插件详情，以及按受控标识发起安装。
 * 全部 admin-only（非管理员会被 AuthFilter 拦截）；安装<b>只</b>按路径 repositoryId+pluginId+version 解析，<b>绝不</b>传任意 URL。
 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var API = PMK.api = {};
    var catalogRequest = null;
    API.cancelCatalog = function () {
        if (catalogRequest) catalogRequest.abort();
        catalogRequest = null;
    };

    function enc(v) { return encodeURIComponent(v); }

    async function getJson(url, signal) {
        var res = await fetch(url, { headers: { 'Accept': 'application/json' }, credentials: 'same-origin', signal: signal });
        if (!res.ok) {
            var body = await res.json().catch(function () { return null; });
            var error = new Error(body && (body.error || body.message) || ('HTTP ' + res.status));
            error.body = body;
            error.httpStatus = res.status;
            throw error;
        }
        return res.json();
    }

    // GET /api/plugin-market/repositories → 主开关 + SDK 版本 + 默认仓库 + 仓库只读投影。
    API.fetchRepositories = function () {
        return getJson('/api/plugin-market/repositories');
    };

    API.contentImageUrl = function (repository, plugin, image, role, index) {
        var asset = image && image.asset;
        if (!asset || !/^[a-f0-9]{64}$/.test(asset.sha256)) return null;
        return '/api/plugin-market/content/' + enc(repository) + '/' + enc(plugin) + '/image?role='
            + enc(role) + '&index=' + enc(index || 0) + '&sha256=' + enc(asset.sha256);
    };
    API.fetchContent = function (model, document, signal) {
        return getJson('/api/plugin-market/content/' + enc(model.repositoryId) + '/' + enc(model.pluginId)
            + '/' + enc(model.version) + '/' + enc(document.kind) + '?locale=' + enc(document.locale)
            + '&sha256=' + enc(document.asset.sha256), signal);
    };

    // GET /api/plugins/status → 恢复模式 + 插件失败 / 必选缺失诊断（复用插件管理只读投影）。
    API.fetchPluginStatus = function () {
        return Promise.all([getJson('/api/plugins/status'), PMK.recovery ? PMK.recovery.refresh() : null])
            .then(function (results) { return results[0]; });
    };

    API.fetchRecovery = function () { return getJson('/api/plugins/recovery'); };
    API.recoveryAction = function (action) {
        if (action !== 'restart' && action !== 'exit') return Promise.reject(new Error('Invalid recovery action'));
        return postJson('/api/plugins/recovery/' + action, {});
    };

    // GET /api/plugin-market/catalog?repositoryId= → 指定仓库（空取默认）的分页摘要 + 分类计数 + 已安装数 + 安装状态。
    API.fetchCatalog = function (repositoryId, options) {
        API.cancelCatalog();
        var request = typeof AbortController === 'function' ? new AbortController() : null;
        catalogRequest = request;
        var url = '/api/plugin-market/catalog';
        var params = [];
        if (repositoryId) params.push('repositoryId=' + enc(repositoryId));
        options = options || {};
        ['cursor', 'query', 'category', 'publisher', 'channel'].forEach(function (key) {
            if (options[key]) params.push(key + '=' + enc(options[key]));
        });
        if (options.limit) params.push('limit=' + enc(options.limit));
        if (params.length) url += '?' + params.join('&');
        var signal = request ? request.signal : options.signal;
        return getJson(url, signal).then(function (catalog) {
            return options.cursor ? catalog : includeRecoveryEntries(catalog, signal);
        }).finally(function () {
            if (catalogRequest === request) catalogRequest = null;
        });
    };

    async function includeRecoveryEntries(catalog, signal) {
        var focus = PMK.recovery && PMK.recovery.focus && PMK.recovery.focus();
        if (!focus || !catalog.enabled || !catalog.repositoryId) return catalog;
        var ids = Object.keys(focus.plugins || {});
        var categories = (focus.categories || []).filter(function (category) { return category === 'ui'; });
        if (!ids.length && !categories.length) return catalog;
        // shortcut: 每次仓库加载最多补查 32 个 ID 和 100 个 GUI 候选；更大仓库通过分页继续查看。
        var request = new AbortController();
        var abort = function () { request.abort(); };
        if (signal) {
            if (signal.aborted) abort();
            else signal.addEventListener('abort', abort, {once: true});
        }
        var timer = setTimeout(abort, 15000);
        var additions = [];
        catalog.focusIncomplete = false;
        try {
            if (categories.length) {
                try {
                    var gui = await getJson('/api/plugin-market/catalog?repositoryId=' + enc(catalog.repositoryId)
                        + '&category=ui&limit=100', request.signal);
                    if (gui.generation === catalog.generation) additions = gui.entries || [];
                    else catalog.focusIncomplete = true;
                    if (gui.nextCursor) catalog.focusIncomplete = true;
                } catch (failure) { catalog.focusIncomplete = true; }
            }
            var known = new Set((catalog.entries || []).concat(additions).map(function (entry) { return entry.pluginId; }));
            var missing = ids.filter(function (id) { return !known.has(id); });
            if (missing.length > 32) catalog.focusIncomplete = true;
            var jobs = missing.slice(0, 32);
            var next = 0;
            async function worker() {
                while (next < jobs.length && !request.signal.aborted) {
                    var id = jobs[next++];
                    try {
                        var entry = await API.fetchPluginDetail(catalog.repositoryId, id, {limit: 1, signal: request.signal});
                        if (entry && entry.pluginId === id) additions.push(entry);
                        else catalog.focusIncomplete = true;
                    } catch (failure) { catalog.focusIncomplete = true; }
                }
            }
            await Promise.all([worker(), worker(), worker()]);
            if (signal && signal.aborted) throw new DOMException('Catalog request cancelled', 'AbortError');
            if (request.signal.aborted) catalog.focusIncomplete = true;
            catalog.entries = PMK.data.mergeEntries(catalog.entries, additions);
            return catalog;
        } finally {
            clearTimeout(timer);
            if (signal) signal.removeEventListener('abort', abort);
        }
    }

    API.fetchPluginDetail = function (repositoryId, pluginId, options) {
        var url = '/api/plugin-market/plugins/' + enc(repositoryId) + '/' + enc(pluginId);
        var params = [];
        options = options || {};
        if (options.cursor) params.push('cursor=' + enc(options.cursor));
        if (options.limit) params.push('limit=' + enc(options.limit));
        if (params.length) url += '?' + params.join('&');
        return getJson(url, options.signal);
    };

    API.fetchPackageFacts = function (repositoryId, pluginId, version) {
        return getJson('/api/plugin-market/plugins/' + enc(repositoryId) + '/' + enc(pluginId) + '/' + enc(version) + '/facts');
    };

    function postJson(url, body) {
        return fetch(url, {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(function (res) {
            return res.json().catch(function () { return null; }).then(function (data) {
                if (!res.ok) {
                    var error = new Error(data && (data.error || data.message) || ('HTTP ' + res.status));
                    error.body = data; error.httpStatus = res.status; throw error;
                }
                return data;
            });
        });
    }

    API.previewInstall = function (repositoryId, pluginId, version) {
        return getJson('/api/plugin-market/' + enc(repositoryId) + '/' + enc(pluginId)
            + '/' + enc(version) + '/install-preview');
    };

    API.fetchOperations = function () { return getJson('/api/plugins/acquisitions'); };
    API.fetchOperation = function (id) { return getJson('/api/plugins/acquisitions/' + enc(id)); };
    API.discardOperation = function (id) {
        return postJson('/api/plugin-market/operations/' + enc(id) + '/discard', {});
    };

    function operationResult(value) {
        if (value.finished && value.result) return { kind: 'install', body: value.result,
            operationId: value.id, httpStatus: value.result.status };
        if (value.finished && value.failure) return { kind: 'error', body: value.failure, httpStatus: value.failure.status };
        return { kind: 'error', body: {code: 'OPERATION_RUNNING', operationId: value.id,
            message: PMK.t('operations.running', '', {id: value.id})} };
    }

    async function executeConfirmedPlan(repositoryId, pluginId, version, confirmations, onProgress) {
        if (onProgress) onProgress('PREPARING');
        var prepared = await postJson('/api/plugin-market/operations', {
            repositoryId: repositoryId, pluginId: pluginId, version: version,
            fingerprint: confirmations.fingerprint, confirmTrust: confirmations.trustSha256,
            previousOperationId: confirmations.previousOperationId
        });
        if (!prepared || !prepared.id) throw new Error(PMK.t('operations.unknown'));
        if (PMK.operations) PMK.operations.watch(prepared.id, onProgress);
        try {
            return operationResult(await postJson('/api/plugin-market/operations/' + enc(prepared.id) + '/execute', {}));
        } catch (failure) {
            // 已取得身份后只读查询；丢失 HTTP 响应不意味着安装失败，也不重新提交执行。
            try {
                return operationResult(await API.fetchOperation(prepared.id));
            } catch (queryFailure) {
                return {kind: 'error', body: {code: 'OPERATION_UNKNOWN', operationId: prepared.id,
                    message: PMK.t('operations.unknown') + ' (' + prepared.id + ')'}};
            }
        } finally {
            if (PMK.operations) PMK.operations.settled(prepared.id);
        }
    }

    // POST /api/plugin-market/{repositoryId}/{pluginId}/{version}/install（请求体不含 URL）。
    // 后端对「已决安装结局」返回 PluginInstallResponse（带稳定 outcome，含各类拒绝），对「拿到包之前的 catalog / 下载层
    // 失败」返回错误体（带稳定 code）。据响应体字段归一化：outcome → install；code → error；都没有 → 抛错（如 401 跳登录）。
    API.installPlugin = function (repositoryId, pluginId, version, confirmations, onProgress) {
        var url = '/api/plugin-market/' + enc(repositoryId) + '/' + enc(pluginId) + '/' + enc(version) + '/install';
        confirmations = confirmations || {};
        if (confirmations.fingerprint) {
            return executeConfirmedPlan(repositoryId, pluginId, version, confirmations, onProgress);
        }
        var query = [];
        if (confirmations.trustSha256) query.push('confirmTrust=' + enc(confirmations.trustSha256));
        if (query.length) url += '?' + query.join('&');
        return fetch(url, { method: 'POST', headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
            .then(function (res) {
                return res.json().catch(function () { return null; }).then(function (body) {
                    if (body && typeof body.outcome === 'string') {
                        return { kind: 'install', body: body, httpStatus: res.status };
                    }
                    if (body && typeof body.code === 'string') {
                        return { kind: 'error', body: body, httpStatus: res.status };
                    }
                    var err = new Error('HTTP ' + res.status);
                    err.httpStatus = res.status;
                    throw err;
                });
            });
    };
})(window);
