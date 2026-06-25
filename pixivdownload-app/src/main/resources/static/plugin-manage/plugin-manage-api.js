'use strict';
/*
 * 插件管理页后端调用：拉取插件状态、执行运行期生命周期动词。
 * 失败时抛出携带后端「稳定机器码」code 的错误（按 code 分支，message 仅作本地化展示）。
 */
(function (global) {
    var PM = global.PixivPluginManage;

    // GET /api/plugins/status → PluginManagementReport。
    async function fetchStatus() {
        var res = await fetch(PM.STATUS_URL, {
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        });
        if (!res.ok) {
            var error = new Error('HTTP ' + res.status);
            error.httpStatus = res.status;
            throw error;
        }
        return res.json();
    }

    // POST /api/plugins/{id}/{verb}（verb ∈ load/start/quiesce/stop/unload/reload）。
    // 成功 → { id, action, phase }；失败 → 抛出携 { code, message, httpStatus, pluginId, action } 的错误。
    async function performAction(id, verb) {
        var url = PM.ACTION_URL_PREFIX + encodeURIComponent(id) + '/' + encodeURIComponent(verb);
        var res = await fetch(url, {
            method: 'POST',
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        });
        var body = null;
        try {
            body = await res.json();
        } catch (parseError) {
            body = null;
        }
        if (!res.ok) {
            var err = new Error((body && body.message) || ('HTTP ' + res.status));
            err.code = body && body.code;          // 稳定机器码：UNKNOWN_PLUGIN / BUILT_IN_PLUGIN / ...
            err.httpStatus = res.status;
            err.pluginId = body && body.pluginId;
            err.action = body && body.action;
            throw err;
        }
        return body;
    }

    PM.fetchStatus = fetchStatus;
    PM.performAction = performAction;
})(window);
