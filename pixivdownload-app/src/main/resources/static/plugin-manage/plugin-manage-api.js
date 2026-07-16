'use strict';
/*
 * 插件管理页后端调用：拉取插件状态、执行运行期生命周期动词、持久化启停配置与请求后端重启。
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

    // POST /api/plugins/{id}/{verb}（verb ∈ load/start/quiesce/stop/unload/restart/reload）。
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

    // PUT /api/plugins/{id}/enabled，持久化插件启停配置；实际生效方式由 lifecyclePolicy 决定。
    async function setEnabled(id, enabled) {
        var url = PM.ACTION_URL_PREFIX + encodeURIComponent(id) + '/enabled';
        var res = await fetch(url, {
            method: 'PUT',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            },
            credentials: 'same-origin',
            body: JSON.stringify({ enabled: enabled === true })
        });
        var body = null;
        try {
            body = await res.json();
        } catch (parseError) {
            body = null;
        }
        if (!res.ok) {
            var err = new Error((body && body.message) || ('HTTP ' + res.status));
            err.code = body && body.code;
            err.httpStatus = res.status;
            err.pluginId = body && body.pluginId;
            throw err;
        }
        return body;
    }

    // POST /api/plugins/backend-restart，仅重启 Spring Boot 后端；完整进程重启不由本页触发。
    async function restartBackend() {
        var res = await fetch(PM.BACKEND_RESTART_URL, {
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
            err.code = body && body.code;
            err.httpStatus = res.status;
            throw err;
        }
        return body;
    }

    // POST /api/plugins/install（multipart/form-data：file + allowDowngrade）。
    // 后端对所有「已决结局」（accepted / 各类拒绝 / 失败）都返回结构化 PluginInstallResponse（带稳定 outcome + 本地化
    // message），HTTP 状态由 outcome 派生。故只要响应体带 outcome 就<b>原样返回</b>（即便 4xx / 5xx）交结果区按
    // outcome 渲染；只有缺文件（不发请求）或拿不到结构化响应（如 401 跳登录 / 413 过大 / 网关 HTML）才抛错。
    async function installPackage(file, allowDowngrade) {
        if (!file) {
            // 防御性：绝不发送无文件的 multipart 安装请求（与提交前的本地校验一致）。
            var localError = new Error('no plugin package selected');
            localError.localValidation = true;
            throw localError;
        }
        if (!PM.hasAcceptedExtension(file.name)) {
            // 防御性：扩展名不是 .jar / .zip 时绝不发请求（后端仍是权威校验，前端只减少误操作）。
            var extError = new Error('unsupported plugin package extension');
            extError.localValidation = true;
            extError.invalidExtension = true;
            throw extError;
        }
        var form = new FormData();
        form.append('file', file);
        form.append('allowDowngrade', allowDowngrade ? 'true' : 'false');
        var res = await fetch(PM.INSTALL_URL, {
            method: 'POST',
            body: form,
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        });
        var body = null;
        try {
            body = await res.json();
        } catch (parseError) {
            body = null;
        }
        if (body && typeof body.outcome === 'string') {
            return body;
        }
        var err = new Error((body && body.message) || ('HTTP ' + res.status));
        err.httpStatus = res.status;
        throw err;
    }

    PM.fetchStatus = fetchStatus;
    PM.performAction = performAction;
    PM.setEnabled = setEnabled;
    PM.restartBackend = restartBackend;
    PM.installPackage = installPackage;
})(window);
