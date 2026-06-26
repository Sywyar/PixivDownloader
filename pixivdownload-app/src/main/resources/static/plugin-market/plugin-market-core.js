'use strict';
/*
 * 插件市场页核心模块（最先加载）：命名空间 PixivPluginMarket、页面状态、i18n 解析助手、HTML 转义与后端调用。
 * 完整的浏览 / 筛选 / 安装界面随后提供，本页只读受信仓库状态 + 核心 API 版本。
 */
(function (global) {
    var PMK = global.PixivPluginMarket = global.PixivPluginMarket || {};

    // 页面状态：i18n 客户端（init 创建 / 切语言时替换）、仓库响应、加载 / 错误标记。
    PMK.state = {
        i18n: { client: null },
        data: null,      // GET /api/plugin-market/repositories 响应
        loading: false,
        error: null
    };

    function interpolate(template, vars) {
        if (!vars) return String(template);
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, function (match, name) {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match;
        });
    }

    // 页面自有文案：在 plugin-market namespace 内解析（缺失回退到提供的默认文案）。
    PMK.t = function (key, fallback, vars) {
        var client = PMK.state.i18n.client;
        if (client) {
            return client.t('plugin-market:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    };

    PMK.escapeHtml = function (str) {
        return String(str == null ? '' : str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    };

    // 拉取受信仓库列表 + 主开关状态（admin-only；非管理员会被 AuthFilter 拦截）。
    PMK.fetchRepositories = function () {
        return fetch('/api/plugin-market/repositories', { credentials: 'same-origin' })
            .then(function (resp) {
                if (!resp.ok) {
                    throw new Error('HTTP ' + resp.status);
                }
                return resp.json();
            });
    };
})(window);
