'use strict';
/*
 * 插件管理页渲染层：据共享状态把概览统计、筛选标签、插件卡片、空 / 加载 / 错误状态与恢复横幅渲染进 DOM。
 * 纯命令式渲染（按状态重绘相应容器）；顶层事件绑定与状态写入收拢在 plugin-manage-init.js。
 */
(function (global) {
    var PM = global.PixivPluginManage;
    var E = PM.escapeHtml;

    function toneColor(tone) {
        if (tone === 'ok') return 'var(--status-green)';
        if (tone === 'info') return 'var(--status-blue)';
        if (tone === 'warn') return 'var(--status-yellow)';
        if (tone === 'bad') return 'var(--status-red)';
        return 'var(--status-idle)';
    }

    function switchTitle(vm) {
        if (!vm.managed) return PM.t('switch.builtin', '内置插件，随主程序编译，不可热启停。');
        if (!vm.allowDisable) return PM.t('switch.required', '必须插件，不可停用。');
        return vm.running ? PM.t('switch.disable', '点击停用') : PM.t('switch.enable', '点击启用');
    }

    // —— 概览统计（信息区） ——
    function statTile(num, key, fallback, icon, color) {
        return '<div class="pm-stat pm-stat--' + color + '">'
            + '<div class="pm-stat-num">' + E(num) + '</div>'
            + '<div class="pm-stat-label">' + E(PM.t(key, fallback)) + '</div>'
            + '<i class="fa-solid ' + icon + ' pm-stat-icon"></i>'
            + '</div>';
    }

    function renderStats(models) {
        var s = PM.stats(models);
        document.getElementById('pm-stats').innerHTML = [
            statTile(s.total, 'stat.total', '已安装插件', 'fa-puzzle-piece', 'pixiv'),
            statTile(s.enabled, 'stat.enabled', '已启用', 'fa-circle-check', 'green'),
            statTile(s.external, 'stat.external', '外置插件', 'fa-cube', 'amber'),
            statTile(s.required, 'stat.required', '必须插件', 'fa-shield-halved', 'blue')
        ].join('');
    }

    // —— 筛选标签 ——
    function renderTabs(models) {
        var tabs = PM.tabsModel(models);
        var active = PM.state.activeTab;
        document.getElementById('pm-tabs').innerHTML = tabs.map(function (tab) {
            var cls = 'pm-tab' + (tab.id === active ? ' active' : '');
            return '<button type="button" class="' + cls + '" data-pm-tab="' + tab.id + '">'
                + '<i class="fa-solid ' + tab.icon + '"></i>'
                + E(PM.t(tab.labelKey, tab.id)) + ' ' + tab.count
                + '</button>';
        }).join('');
    }

    // —— 恢复 / 补齐模式横幅 ——
    function renderRecovery() {
        var host = document.getElementById('pm-recovery');
        if (PM.state.report && PM.state.report.recoveryMode) {
            host.hidden = false;
            host.className = 'pm-recovery';
            host.innerHTML = '<i class="fa-solid fa-triangle-exclamation"></i>'
                + '<div><div class="pm-recovery-title">' + E(PM.t('recovery.title', '恢复 / 补齐模式')) + '</div>'
                + '<div class="pm-recovery-desc">' + E(PM.t('recovery.desc', '缺少必须的下载插件，正常业务功能未开放。')) + '</div></div>';
        } else {
            host.hidden = true;
            host.innerHTML = '';
        }
    }

    // —— 单张插件卡片 ——
    function cardHtml(vm) {
        var busy = PM.state.busyId === vm.id;
        var parts = [];
        parts.push('<div class="pm-card" data-pm-card="' + E(vm.id) + '">');

        // 更新横幅（占位：后端暂无更新机制，vm.hasUpdate 恒为 false）。
        if (vm.hasUpdate) {
            parts.push('<div class="pm-card-ribbon"><i class="fa-solid fa-circle-arrow-up"></i>'
                + E(PM.t('update.available', '有新版本可更新到 v{latest}', { latest: vm.latest })) + '</div>');
        }

        // 头部：图标贴片 + 标题块 + 开关。
        parts.push('<div class="pm-card-head">');
        parts.push('<div class="pm-card-icon" style="background: color-mix(in srgb, ' + vm.iconColor
            + ' 14%, transparent); color: ' + vm.iconColor + ';"><i class="' + E(vm.icon) + '"></i></div>');
        parts.push('<div class="pm-card-titleblock">');
        parts.push('<div class="pm-card-name-row"><span class="pm-card-name">' + E(vm.name) + '</span>'
            + '<span class="pm-badge pm-badge--' + vm.badgeTone + '">' + E(PM.t(vm.badgeKey, vm.source)) + '</span>'
            + (vm.requiredByPolicy
                ? '<span class="pm-badge pm-badge--warn">' + E(PM.t('badge.required', '必须')) + '</span>'
                : '')
            + '</div>');
        parts.push('<div class="pm-card-sub" title="' + E(vm.sub) + '">' + E(vm.sub) + '</div>');
        parts.push('</div>');

        var switchCls = 'pm-switch' + (vm.running ? ' on' : '') + (vm.toggleable ? '' : ' pm-switch--locked');
        var switchAttrs = 'type="button" role="switch" aria-checked="' + (vm.running ? 'true' : 'false') + '"'
            + ' data-pm-toggle="' + E(vm.id) + '" title="' + E(switchTitle(vm)) + '"'
            + ((!vm.toggleable || busy) ? ' disabled' : '');
        parts.push('<button class="' + switchCls + '" ' + switchAttrs + '></button>');
        parts.push('</div>'); // head

        if (vm.desc) {
            parts.push('<p class="pm-card-desc">' + E(vm.desc) + '</p>');
        }

        if (vm.tags.length) {
            parts.push('<div class="pm-tags">' + vm.tags.map(function (tag) {
                return '<span class="pm-tag">#' + E(tag) + '</span>';
            }).join('') + '</div>');
        }

        // 更新进度（占位：vm.updating 恒为 false）。
        if (vm.updating) {
            parts.push('<div class="pm-progress"><div class="pm-progress-head"><span>'
                + E(PM.t('update.updating', '更新中…')) + '</span><span>' + vm.progress + '%</span></div>'
                + '<div class="pm-progressbar"><span style="width:' + vm.progress + '%;"></span></div></div>');
        }

        // 诊断信息（后端 messages）。
        if (vm.messages.length) {
            parts.push('<div class="pm-tags">' + vm.messages.map(function (msg) {
                return '<span class="pm-tag"><i class="fa-solid fa-circle-info"></i> ' + E(msg) + '</span>';
            }).join('') + '</div>');
        }

        // 底栏：左侧真实元信息（状态 / 核心 API / 依赖数）+ 右侧后端可用动词按钮。
        parts.push('<div class="pm-card-foot">');
        parts.push('<div class="pm-meta">');
        parts.push('<span class="pm-meta-item"><span class="pm-status-dot" style="background:'
            + toneColor(vm.statusTone) + ';"></span>' + E(vm.statusLabel) + '</span>');
        // 运行期阶段：仅受管外置插件有精确阶段（内置 / 未安装无此概念）。
        if (vm.managed && vm.phaseLabel) {
            parts.push('<span class="pm-meta-item"><i class="fa-solid fa-circle-half-stroke" style="color:'
                + toneColor(vm.phaseTone) + ';"></i>' + E(vm.phaseLabel) + '</span>');
        }
        if (vm.api) {
            var apiCls = vm.api.specified ? (vm.api.satisfied ? 'pm-meta-item--ok' : 'pm-meta-item--bad') : '';
            var apiText = vm.api.specified
                ? PM.t('api.requires', '核心 API {version}', { version: vm.api.required })
                : PM.t('api.any', '不限核心 API 版本');
            parts.push('<span class="pm-meta-item ' + apiCls + '"><i class="fa-solid fa-code-branch"></i>'
                + E(apiText) + '</span>');
        }
        if (vm.deps.length) {
            var depTitle = vm.deps.map(function (d) {
                return d.pluginId + (d.optional ? ' ' + PM.t('deps.optional', '(可选)') : '');
            }).join(', ');
            parts.push('<span class="pm-meta-item" title="' + E(depTitle) + '"><i class="fa-solid fa-diagram-project"></i>'
                + E(PM.t('deps.count', '{n} 个依赖', { n: vm.deps.length })) + '</span>');
        }
        parts.push('</div>'); // meta

        parts.push('<div class="pm-card-actions">');
        if (vm.availableActions.length) {
            parts.push(vm.availableActions.map(function (verb) {
                var meta = PM.verbMeta(verb);
                return '<button type="button" class="pm-btn pm-btn--sm pm-btn--' + meta.variant + '"'
                    + ' data-pm-action="' + E(verb) + '" data-pm-id="' + E(vm.id) + '"'
                    + (busy ? ' disabled' : '') + '>'
                    + '<i class="fa-solid ' + meta.icon + '"></i>' + E(PM.t('action.' + verb, verb))
                    + '</button>';
            }).join(''));
        } else {
            parts.push('<span class="pm-actions-empty">' + E(PM.t('action.none', '暂无可执行的操作')) + '</span>');
        }
        parts.push('</div>'); // actions
        parts.push('</div>'); // foot

        parts.push('</div>'); // card
        return parts.join('');
    }

    function stateHtml(kind, message) {
        if (kind === 'loading') {
            return '<div class="pm-state"><i class="fa-solid fa-spinner fa-spin"></i>'
                + E(PM.t('status.loading', '正在加载插件状态…')) + '</div>';
        }
        if (kind === 'error') {
            return '<div class="pm-state pm-state--error"><i class="fa-solid fa-circle-exclamation"></i>'
                + E(message || PM.t('status.error', '加载插件状态失败，请重试。')) + '</div>';
        }
        return '';
    }

    function emptyHtml(noPluginsAtAll) {
        if (noPluginsAtAll) {
            return '<div class="pm-empty"><i class="fa-solid fa-plug-circle-xmark"></i>'
                + '<div class="pm-empty-title">' + E(PM.t('empty.none', '未发现任何插件。')) + '</div></div>';
        }
        return '<div class="pm-empty"><i class="fa-solid fa-plug-circle-xmark"></i>'
            + '<div class="pm-empty-title">' + E(PM.t('empty.filtered', '没有匹配的插件')) + '</div>'
            + '<div class="pm-empty-hint">' + E(PM.t('empty.hint', '试试切换筛选标签，或清空搜索关键词。')) + '</div></div>';
    }

    // 主渲染入口：据当前状态重绘统计 / 标签 / 网格 / 状态占位 / 恢复横幅。
    function renderAll() {
        var models = PM.allViewModels();
        var grid = document.getElementById('pm-grid');
        var stateHost = document.getElementById('pm-state-host');
        var countEl = document.getElementById('pm-result-count');

        renderRecovery();
        renderStats(models);
        renderTabs(models);

        if (PM.state.loading && !PM.state.report) {
            grid.innerHTML = '';
            stateHost.innerHTML = stateHtml('loading');
            countEl.textContent = '';
            return;
        }
        if (PM.state.error && !PM.state.report) {
            grid.innerHTML = '';
            stateHost.innerHTML = stateHtml('error', PM.state.error);
            countEl.textContent = '';
            return;
        }

        var filtered = PM.filterModels(models);
        countEl.textContent = PM.t('result.count', '{n} 个结果', { n: filtered.length });

        if (!filtered.length) {
            grid.innerHTML = '';
            stateHost.innerHTML = emptyHtml(models.length === 0);
            return;
        }
        stateHost.innerHTML = '';
        grid.innerHTML = filtered.map(cardHtml).join('');
    }

    var toastTimer = null;
    function toast(message, kind) {
        var el = document.getElementById('pm-toast');
        if (!el) return;
        el.textContent = message;
        el.className = 'pm-toast pm-toast--' + (kind || 'info') + ' show';
        if (toastTimer) clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { el.className = 'pm-toast'; }, 3400);
    }

    PM.renderAll = renderAll;
    PM.toast = toast;
})(window);
