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
        if (!vm.toggleable) {
            if (vm.requiredByPolicy || !vm.allowDisable) return PM.t('switch.required', '必须插件，不可停用。');
            if (vm.source === 'built-in') return PM.t('switch.builtin', '内置插件，随主程序编译，不可启停。');
            return PM.t('switch.locked', '当前插件不可启停。');
        }
        var action = vm.enabled ? 'disable' : 'enable';
        if (vm.lifecyclePolicy === 'BACKEND_RESTART') {
            return PM.t('switch.' + action + '.backend-restart',
                vm.enabled ? '点击停用；重启后端后生效' : '点击启用；重启后端后生效');
        }
        if (vm.lifecyclePolicy === 'PROCESS_RESTART') {
            return PM.t('switch.' + action + '.process-restart',
                vm.enabled ? '点击停用；重启软件后生效' : '点击启用；重启软件后生效');
        }
        return vm.enabled ? PM.t('switch.disable', '点击停用') : PM.t('switch.enable', '点击启用');
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

        // 头部：图标贴片 + 标题块 + 开关。图标 class 与强调色 class 均来自 core 的本地白名单（受控 token），
        // 不把后端原始 token 当颜色 / 类名直接注入（颜色由 .pm-card-icon--<token> CSS 规则决定）。
        parts.push('<div class="pm-card-head">');
        parts.push('<div class="pm-card-icon pm-card-icon--' + E(vm.colorToken) + '"><i class="' + E(vm.icon) + '"></i></div>');
        parts.push('<div class="pm-card-titleblock">');
        parts.push('<div class="pm-card-name-row"><span class="pm-card-name">' + E(vm.name) + '</span>'
            + '<span class="pm-badge pm-badge--' + vm.badgeTone + '">' + E(PM.t(vm.badgeKey, vm.source)) + '</span>'
            + (vm.requiredByPolicy
                ? '<span class="pm-badge pm-badge--warn">' + E(PM.t('badge.required', '必须')) + '</span>'
                : '')
            + '</div>');
        parts.push('<div class="pm-card-sub" title="' + E(vm.sub) + '">' + E(vm.sub) + '</div>');
        parts.push('</div>');

        var switchCls = 'pm-switch' + (vm.enabled ? ' on' : '') + (vm.toggleable ? '' : ' pm-switch--locked');
        var switchAttrs = 'type="button" role="switch" aria-checked="' + (vm.enabled ? 'true' : 'false') + '"'
            + ' data-pm-toggle="' + E(vm.id) + '" title="' + E(switchTitle(vm)) + '"'
            + ((!vm.toggleable || busy) ? ' disabled' : '');
        parts.push('<button class="' + switchCls + '" ' + switchAttrs + '></button>');
        parts.push('</div>'); // head

        if (vm.desc) {
            parts.push('<p class="pm-card-desc">' + E(vm.desc) + '</p>');
        }

        parts.push('<div class="pm-tags">' + (vm.showLifecycleTag
            ? '<span class="pm-tag pm-tag--lifecycle-' + E(vm.lifecycleTone) + '">#' + E(vm.lifecycleLabel) + '</span>'
            : '') + vm.tags.map(function (tag) {
                return '<span class="pm-tag">#' + E(tag) + '</span>';
            }).join('') + '</div>');

        // 包级写操作状态。
        if (vm.updating) {
            parts.push('<div class="pm-progress"><div class="pm-progress-head"><span>'
                + E(PM.t('operation.running', '正在执行：{operation}', { operation: vm.operation }))
                + '</span></div><div class="pm-progressbar"><span style="width:100%;"></span></div></div>');
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
        // 受管外置插件只展示 runtimePhase（准确反映当前服务侧状态），内置 / 未安装条目展示 status。
        if (vm.managed && vm.phaseLabel) {
            parts.push('<span class="pm-meta-item"><i class="fa-solid fa-circle-half-stroke" style="color:'
                + toneColor(vm.phaseTone) + ';"></i>' + E(vm.phaseLabel) + '</span>');
        } else {
            parts.push('<span class="pm-meta-item"><span class="pm-status-dot" style="background:'
                + toneColor(vm.statusTone) + ';"></span>' + E(vm.statusLabel) + '</span>');
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
        if (vm.verificationLabel) {
            var verificationTitle = vm.verificationTrustLabel || vm.verificationStatus || '';
            parts.push('<span class="pm-meta-item" title="' + E(verificationTitle) + '"><i class="fa-solid fa-shield-halved" style="color:'
                + toneColor(vm.verificationTone) + ';"></i>' + E(vm.verificationLabel) + '</span>');
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

    // —— 本地插件包安装弹窗 ——

    function installToneIcon(tone) {
        if (tone === 'ok') return 'fa-circle-check';
        if (tone === 'info') return 'fa-circle-info';
        if (tone === 'bad') return 'fa-circle-xmark';
        return 'fa-triangle-exclamation'; // warn / 默认
    }

    function installMetaRow(labelKey, fallback, value) {
        return '<div class="pm-install-meta-row"><span class="pm-install-meta-key">'
            + E(PM.t(labelKey, fallback)) + '</span><span class="pm-install-meta-val">' + E(value) + '</span></div>';
    }

    function installList(labelKey, fallback, items, icon) {
        if (!items || !items.length) return '';
        return '<div class="pm-install-list">'
            + '<div class="pm-install-list-title"><i class="fa-solid ' + icon + '"></i>'
            + E(PM.t(labelKey, fallback)) + '</div>'
            + '<ul>' + items.map(function (it) { return '<li>' + E(it) + '</li>'; }).join('') + '</ul>'
            + '</div>';
    }

    // 安装结果区渲染（纯字符串，所有动态值——含后端 message / outcome / 诊断 / 依赖——均经 E() 转义，绝不注入 HTML）。
    // 空模型 → 空串（渲染层据此清空结果区）。消费 PM.buildInstallResult 的视图模型。
    function renderInstallResultHtml(model) {
        if (!model) return '';
        var tone = model.tone || 'warn';
        var parts = [];
        parts.push('<div class="pm-install-result-box pm-install-result-box--' + tone + '">');
        parts.push('<div class="pm-install-result-head">');
        parts.push('<i class="fa-solid ' + installToneIcon(tone) + '"></i>');
        parts.push('<span class="pm-install-result-msg">'
            + E(model.message || PM.t('install.error.generic', '安装请求失败，请重试。')) + '</span>');
        if (model.outcome) {
            parts.push('<span class="pm-install-code" title="' + E(PM.t('install.outcome-code', '结果代码')) + '">'
                + E(model.outcome) + '</span>');
        }
        parts.push('</div>'); // head

        // 恢复阻断优先于落盘 / 激活成功字段：事务恢复完成前不得渲染任何绿色成功说明。
        if (model.recoveryBlocked) {
            parts.push('<div class="pm-install-restart"><i class="fa-solid fa-triangle-exclamation"></i>'
                + E(PM.t('install.recovery-blocked-note', '安装事务需要在重启后恢复；恢复完成前请勿继续安装插件。')) + '</div>');
        } else {
            if (model.effectiveAfterRestart) {
                parts.push('<div class="pm-install-restart"><i class="fa-solid fa-rotate-right"></i>'
                    + E(PM.t('install.restart-note', '插件包已落盘，但当前运行时无法即时激活；请重启后确认状态。')) + '</div>');
            }
            if (model.activated) {
                parts.push('<div class="pm-install-restart"><i class="fa-solid fa-circle-check"></i>'
                    + E(PM.t('install.activated-note', '插件已安装并在当前进程中激活。')) + '</div>');
            } else if (model.rolledBack) {
                parts.push('<div class="pm-install-restart"><i class="fa-solid fa-rotate-left"></i>'
                    + E(PM.t('install.rollback-note', '新版本激活失败，已恢复原版本。')) + '</div>');
            }
        }

        var meta = [];
        if (model.pluginId) meta.push(installMetaRow('install.field.plugin-id', '插件 ID', model.pluginId));
        if (model.version) meta.push(installMetaRow('install.field.version', '版本', model.version));
        if (model.previousVersion) meta.push(installMetaRow('install.field.previous-version', '原版本', model.previousVersion));
        if (model.operation) meta.push(installMetaRow('install.field.operation', '事务操作', model.operation));
        if (model.runtimePhase) meta.push(installMetaRow('install.field.runtime-phase', '运行阶段', model.runtimePhase));
        if (model.rollbackVersion) meta.push(installMetaRow('install.field.rollback-version', '已恢复版本', model.rollbackVersion));
        if (model.transactionId) meta.push(installMetaRow('install.field.transaction-id', '事务 ID', model.transactionId));
        if (meta.length) {
            parts.push('<div class="pm-install-meta">' + meta.join('') + '</div>');
        }

        parts.push(installList('install.warnings', '尚未满足的依赖', model.warnings, 'fa-diagram-project'));
        parts.push(installList('install.errors', '诊断信息', model.errors, 'fa-circle-info'));

        parts.push('</div>'); // box
        return parts.join('');
    }

    function installModalEl() {
        return document.getElementById('pm-install-modal');
    }

    function showInstallResult(model) {
        var host = document.getElementById('pm-install-result');
        if (host) host.innerHTML = renderInstallResultHtml(model);
    }

    function clearInstallResult() {
        var host = document.getElementById('pm-install-result');
        if (host) host.innerHTML = '';
    }

    // 文件名标签：选中文件 → 显示文件名并摘掉 data-i18n（避免语言切换时被 apply 覆盖回「未选择文件」）；
    // 清空 → 还原 data-i18n + 当前语言的「未选择文件」文案。
    function setInstallFilename(name) {
        var el = document.getElementById('pm-install-filename');
        if (!el) return;
        if (name) {
            el.removeAttribute('data-i18n');
            el.textContent = name;
            el.classList.add('has-file');
        } else {
            el.setAttribute('data-i18n', 'install.no-file');
            el.textContent = PM.t('install.no-file', '未选择文件');
            el.classList.remove('has-file');
        }
    }

    function setInstallSubmitting(busy) {
        var btn = document.getElementById('pm-install-submit');
        if (btn) btn.disabled = !!busy;
        PM.state.installBusy = !!busy;
    }

    // 打开弹窗：每次打开都重置文件选择、降级勾选、高级折叠、结果区与提交态，确保干净起点。
    function openInstallModal() {
        var modal = installModalEl();
        if (!modal) return;
        var fileInput = document.getElementById('pm-install-file');
        if (fileInput) fileInput.value = '';
        var allow = document.getElementById('pm-install-allow-downgrade');
        if (allow) allow.checked = false;
        var advanced = modal.querySelector('.pm-advanced');
        if (advanced) advanced.removeAttribute('open');
        setInstallFilename(null);
        clearInstallResult();
        setInstallSubmitting(false);
        modal.hidden = false;
        modal.classList.add('show');
        if (fileInput && typeof fileInput.focus === 'function') fileInput.focus();
    }

    function closeInstallModal() {
        var modal = installModalEl();
        if (!modal) return;
        modal.classList.remove('show');
        modal.hidden = true;
    }

    PM.renderAll = renderAll;
    PM.toast = toast;
    PM.renderInstallResultHtml = renderInstallResultHtml;
    PM.showInstallResult = showInstallResult;
    PM.clearInstallResult = clearInstallResult;
    PM.setInstallFilename = setInstallFilename;
    PM.setInstallSubmitting = setInstallSubmitting;
    PM.openInstallModal = openInstallModal;
    PM.closeInstallModal = closeInstallModal;
})(window);
