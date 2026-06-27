'use strict';
/*
 * 插件市场页后端调用：拉取受信仓库列表 / 指定仓库 catalog / 插件详情，以及按受控标识发起安装。
 * 全部 admin-only（非管理员会被 AuthFilter 拦截）；安装<b>只</b>按路径 repositoryId+pluginId+version 解析，<b>绝不</b>传任意 URL。
 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var API = PMK.api = {};

    function enc(v) { return encodeURIComponent(v); }

    async function getJson(url) {
        var res = await fetch(url, { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' });
        if (!res.ok) {
            var error = new Error('HTTP ' + res.status);
            error.httpStatus = res.status;
            throw error;
        }
        return res.json();
    }

    // GET /api/plugin-market/repositories → 主开关 + 核心 API 版本 + 默认仓库 + 仓库只读投影。
    API.fetchRepositories = function () {
        return getJson('/api/plugin-market/repositories');
    };

    // GET /api/plugin-market/catalog?repositoryId= → 指定仓库（空取默认）的 catalog 摘要 + 分类计数 + 已安装数 + 安装状态。
    // catalog 条目已携带完整版本历史 / 依赖 / 兼容信息，故详情弹窗直接用内存中的条目，无需再单独拉 /plugins/{repo}/{id}。
    API.fetchCatalog = function (repositoryId) {
        var url = '/api/plugin-market/catalog';
        if (repositoryId) url += '?repositoryId=' + enc(repositoryId);
        return getJson(url);
    };

    // POST /api/plugin-market/{repositoryId}/{pluginId}/{version}/install（请求体不含 URL）。
    // 后端对「已决安装结局」返回 PluginInstallResponse（带稳定 outcome，含各类拒绝），对「拿到包之前的 catalog / 下载层
    // 失败」返回错误体（带稳定 code）。据响应体字段归一化：outcome → install；code → error；都没有 → 抛错（如 401 跳登录）。
    API.installPlugin = function (repositoryId, pluginId, version) {
        var url = '/api/plugin-market/' + enc(repositoryId) + '/' + enc(pluginId) + '/' + enc(version) + '/install';
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
