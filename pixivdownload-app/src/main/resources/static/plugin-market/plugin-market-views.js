'use strict';
/*
 * 插件市场页渲染模块（纯命令式渲染）：据共享状态重绘主开关横幅、受信仓库卡片、加载 / 错误 / 空状态。
 * 仓库展示名暂以 repositoryId 呈现（完整本地化展示名解析随后续完整市场页提供）。
 */
(function (global) {
    var PMK = global.PixivPluginMarket;

    function badge(cls, text) {
        return '<span class="pmk-badge ' + cls + '">' + PMK.escapeHtml(text) + '</span>';
    }

    // 单个仓库卡片：名称（repositoryId）+ 清单地址 + 启用 / 官方 / 内嵌 / 兼容 / 默认徽标 + 代理策略。
    function repoCard(repo) {
        var badges = [];
        badges.push(repo.enabled
            ? badge('pmk-badge--on', PMK.t('repo.enabled', '已启用'))
            : badge('pmk-badge--off', PMK.t('repo.disabled', '已禁用')));
        badges.push(repo.official
            ? badge('pmk-badge--official', PMK.t('repo.official', '官方'))
            : badge('', PMK.t('repo.community', '自定义')));
        if (repo.builtIn) badges.push(badge('', PMK.t('repo.builtin', '内嵌')));
        if (repo.legacy) badges.push(badge('', PMK.t('repo.legacy', '兼容')));
        if (repo.defaultRepository) badges.push(badge('pmk-badge--default', PMK.t('repo.default', '默认')));

        var proxy = PMK.t('repo.proxy', '代理策略') + '：' + PMK.escapeHtml(repo.proxyPolicy || '');
        if (!repo.proxyPolicySupported) {
            proxy += '（' + PMK.t('repo.proxy.unsupported', '不支持') + '）';
        }

        return '' +
            '<div class="pmk-repo-card">' +
            '  <div class="pmk-repo-head">' +
            '    <span class="pmk-repo-name">' + PMK.escapeHtml(repo.repositoryId) + '</span>' +
            '  </div>' +
            '  <div class="pmk-repo-url">' + PMK.escapeHtml(repo.manifestUrl || '') + '</div>' +
            '  <div class="pmk-badges">' + badges.join('') + '</div>' +
            '  <div class="pmk-repo-url">' + proxy + '</div>' +
            '</div>';
    }

    function renderMaster(data) {
        var el = document.getElementById('pmk-master');
        if (!el) return;
        el.hidden = false;
        if (data.enabled) {
            el.className = 'pmk-master pmk-master--on';
            var meta = PMK.t('core.api.version', '核心 API 版本') + ' ' + PMK.escapeHtml(data.coreApiVersion || '');
            if (data.defaultRepositoryId) {
                meta += ' · ' + PMK.t('default.repository', '默认仓库') + ' ' + PMK.escapeHtml(data.defaultRepositoryId);
            }
            el.innerHTML = '<i class="fa-solid fa-circle-check"></i>' +
                '<span>' + PMK.t('master.enabled', '插件市场已开启') + '</span>' +
                '<span class="pmk-master-meta">' + meta + '</span>';
        } else {
            el.className = 'pmk-master pmk-master--off';
            el.innerHTML = '<i class="fa-solid fa-circle-exclamation"></i>' +
                '<span>' + PMK.t('master.disabled', '插件市场未开启') + '</span>';
        }
    }

    function renderState(html) {
        var host = document.getElementById('pmk-state-host');
        if (host) host.innerHTML = html;
    }

    PMK.renderAll = function () {
        var grid = document.getElementById('pmk-repo-grid');
        var master = document.getElementById('pmk-master');

        if (PMK.state.loading) {
            if (grid) grid.innerHTML = '';
            if (master) master.hidden = true;
            renderState('<div class="pmk-state"><i class="fa-solid fa-spinner fa-spin"></i><span>' +
                PMK.t('loading', '正在加载…') + '</span></div>');
            return;
        }
        if (PMK.state.error) {
            if (grid) grid.innerHTML = '';
            if (master) master.hidden = true;
            renderState('<div class="pmk-state pmk-state--error"><i class="fa-solid fa-triangle-exclamation"></i>' +
                '<span>' + PMK.escapeHtml(PMK.state.error) + '</span></div>');
            return;
        }

        var data = PMK.state.data || { enabled: false, repositories: [] };
        renderMaster(data);

        var repos = data.repositories || [];
        if (grid) grid.innerHTML = repos.map(repoCard).join('');
        if (!repos.length) {
            renderState('<div class="pmk-state">' + PMK.t('repo.empty', '尚未配置任何受信仓库。') + '</div>');
        } else {
            renderState('');
        }
    };
})(window);
