'use strict';
/*
 * 插件管理页渲染层：据共享状态把概览统计、筛选标签、插件卡片、空 / 加载 / 错误状态与恢复横幅渲染进 DOM。
 * 纯命令式渲染（按状态重绘相应容器）；顶层事件绑定与状态写入收拢在 plugin-manage-init.js。
 */
(function (global) {
    var PM = global.PixivPluginManage;
    var E = PM.escapeHtml;

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
        var host = document.getElementById('pm-tabs');
        var focusedElement = document.activeElement;
        var focusedTab = host.contains(focusedElement) && focusedElement.getAttribute('data-pm-tab');
        var html = tabs.map(function (tab) {
            var cls = 'pm-tab' + (tab.id === active ? ' active' : '');
            return '<button type="button" class="' + cls + '" data-pm-tab="' + tab.id + '">'
                + '<i class="fa-solid ' + tab.icon + '"></i>'
                + E(PM.t(tab.labelKey, tab.id)) + ' ' + tab.count
                + '</button>';
        }).join('');
        if (host.pmMarkup !== html) { host.innerHTML = html; host.pmMarkup = html; }
        if (focusedTab && !focusedElement.isConnected) {
            Array.from(host.querySelectorAll('[data-pm-tab]')).find(function (node) {
                return node.getAttribute('data-pm-tab') === focusedTab;
            }).focus({preventScroll: true});
        }
    }

    // —— 恢复 / 补齐模式横幅 ——
    function renderRecovery() {
        var host = document.getElementById('pm-recovery');
        var privilege = document.getElementById('pm-host-privilege');
        privilege.hidden = !(PM.state.report && PM.state.report.hostElevated);
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

    // 运行期操作使用卡片标题旁的菜单，无可用操作时隐藏入口。
    function actionMenuHtml(vm, busy) {
        if (!vm.availableActions.length && !vm.trustApprovable && !vm.trustRevocable) return '';
        var aria = PM.t('action.menu.aria', '{plugin} 的可用操作', { plugin: vm.name });
        var title = PM.t('action.menu', '操作');
        var menuId = 'pm-action-menu-' + vm.id;
        var parts = ['<div class="pm-action-menu-wrap">',
            '<button type="button" class="pm-icon-btn pm-action-menu-toggle"'
                + ' data-pm-action-menu-toggle data-pm-id="' + E(vm.id) + '" aria-haspopup="menu"'
                + ' aria-expanded="false" aria-controls="' + E(menuId) + '"'
                + ' aria-label="' + E(aria) + '" title="' + E(title) + '"' + (busy ? ' disabled' : '') + '>',
            '<i class="fa-solid fa-ellipsis" aria-hidden="true"></i></button>',
            '<div class="pm-action-menu" id="' + E(menuId) + '" role="menu" aria-label="' + E(aria) + '">'];
        parts.push(vm.availableActions.map(function (verb) {
            var meta = PM.verbMeta(verb);
            return '<button type="button" role="menuitem"' + (meta.variant === 'danger' ? ' class="danger"' : '')
                + ' data-pm-action="' + E(verb) + '" data-pm-id="' + E(vm.id) + '"'
                + (busy ? ' disabled' : '') + '><i class="fa-solid ' + E(meta.icon) + '" aria-hidden="true"></i>'
                + E(PM.t('action.' + verb, verb)) + '</button>';
        }).join(''));
        if (vm.trustApprovable) {
            parts.push('<button type="button" role="menuitem" data-pm-trust-action="approve" data-pm-id="'
                + E(vm.id) + '"' + (busy ? ' disabled' : '')
                + '><i class="fa-solid fa-shield-halved" aria-hidden="true"></i>'
                + E(PM.t('trust.action.approve', '重新批准执行信任')) + '</button>');
        }
        if (vm.trustRevocable) {
            parts.push('<button type="button" role="menuitem" class="danger" data-pm-trust-action="revoke" data-pm-id="'
                + E(vm.id) + '"' + (busy ? ' disabled' : '')
                + '><i class="fa-solid fa-shield-circle-xmark" aria-hidden="true"></i>'
                + E(PM.t('trust.action.revoke', '撤销执行信任')) + '</button>');
        }
        parts.push('</div></div>');
        return parts.join('');
    }

    function fieldsHtml(fields) {
        return '<dl class="pm-facts">' + fields.map(function (field) {
            return '<div><dt>' + E(field.label) + '</dt><dd>' + E(field.value) + '</dd></div>';
        }).join('') + '</dl>';
    }

    function sectionHtml(section, index) {
        var tag = section.collapsed ? 'details' : 'section';
        return '<' + tag + ' class="pm-detail-section' + (section.collapsed ? ' accordion accordion-item pixiv-disclosure' : '') + '" data-pm-section="' + index + '">'
            + '<' + (section.collapsed ? 'summary class="accordion-button collapsed"' : 'h3') + '>' + E(section.title)
            + '</' + (section.collapsed ? 'summary' : 'h3') + '>'
            + (section.collapsed ? '<div class="accordion-body">' : '')
            + fieldsHtml(section.fields || [])
            + (section.paragraphs || []).map(function (text) { return '<p>' + E(text) + '</p>'; }).join('')
            + (section.collapsed ? '</div>' : '')
            + '</' + tag + '>';
    }

    function cardHtml(vm) {
        var busy = PM.state.busyId === vm.id;
        var tone = (vm.managed && vm.phaseLabel) ? vm.phaseTone : vm.statusTone;
        var label = (vm.managed && vm.phaseLabel) ? vm.phaseLabel : vm.statusLabel;
        var parts = ['<article class="pm-card pm-card--' + tone + '" data-pm-card="' + E(vm.id) + '">'];
        parts.push('<div class="pm-card-head"><div class="pm-card-icon pm-card-icon--' + E(vm.colorToken)
            + '"><i class="' + E(vm.icon) + '" aria-hidden="true"></i></div><div class="pm-card-titleblock">'
            + '<h2 class="pm-card-name">' + E(vm.name) + '</h2><div class="pm-card-name-row">'
            + '<span class="pm-card-version">' + E(vm.version || PM.t('common:plugin-info.not-installed')) + '</span>'
            + '<span class="pm-badge pm-badge--' + vm.badgeTone + '">' + E(PM.t(vm.badgeKey, vm.source)) + '</span>'
            + (vm.requiredByPolicy ? '<span class="pm-badge pm-badge--warn">' + E(PM.t('badge.required')) + '</span>' : '')
            + '</div></div>' + actionMenuHtml(vm, busy) + '</div>');
        if (vm.desc) parts.push('<p class="pm-card-desc">' + E(vm.desc) + '</p>');
        if (vm.messages.length) parts.push('<div class="pm-notes">' + vm.messages.map(function (msg) {
            return '<p class="pm-note">' + E(msg) + '</p>';
        }).join('') + '</div>');
        if (vm.updating) parts.push('<p class="pm-note" role="status"><i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>'
            + E(PM.t('operation.running', '', {operation: vm.operation})) + '</p>');
        if (vm.source === 'external' && vm.status === 'FAILED') parts.push('<button type="button" class="pm-btn pm-btn--gray" data-pm-repair'
            + (busy || PM.state.installBusy ? ' disabled' : '') + '>' + E(PM.t('repair.replace')) + '</button>');
        parts.push('<div class="pm-summary-facts">');
        if (vm.showExecutionTag) parts.push('<span>' + E(vm.executionLabel) + '</span>');
        if (vm.showLifecycleTag) parts.push('<span>' + E(vm.lifecycleLabel) + '</span>');
        if (vm.verificationLabel) parts.push('<span class="pm-meta-item--' + E(vm.verificationTone) + '">' + E(vm.verificationLabel) + '</span>');
        if (vm.trustLabel && vm.source === 'external') parts.push('<span class="pm-meta-item--' + E(vm.trustTone) + '">' + E(vm.trustLabel) + '</span>');
        parts.push('</div><div class="pm-card-controls"><span class="pm-card-status pm-card-status--' + tone
            + '"><span class="pm-status-dot"></span>' + E(label) + '</span>');
        if (vm.toggleable) parts.push('<label class="pm-toggle-label"><span>' + E(PM.t('common:plugin-info.enabled'))
            + '</span><button type="button" class="pm-switch' + (vm.enabled ? ' on' : '')
            + '" role="switch" aria-checked="' + vm.enabled + '" data-pm-toggle="' + E(vm.id)
            + '" aria-label="' + E(switchTitle(vm)) + '"' + (busy ? ' disabled' : '') + '></button></label>');
        parts.push('<button type="button" class="pm-details-link" data-pm-details="' + E(vm.id)
            + '" aria-haspopup="dialog" aria-label="' + E(vm.name + ' · ' + PM.t('common:plugin-info.details')) + '">'
            + E(PM.t('common:plugin-info.details')) + '<i class="fa-solid fa-chevron-right" aria-hidden="true"></i></button></div></article>');
        return parts.join('');
    }

    function detailHtml(vm) {
        var busy = PM.state.busyId === vm.id;
        var parts = ['<p class="pm-detail-status">' + E(vm.phaseLabel || vm.statusLabel) + '</p><p class="pm-detail-description">' + E(vm.desc) + '</p><div class="pm-detail-grid">'];
        parts.push(sectionHtml({title: PM.t('common:plugin-info.versions'), fields: [{label: PM.t('common:plugin-info.id'), value: vm.id}].concat(vm.fields)}, 'versions'));
        parts.push(sectionHtml({title: PM.t('common:plugin-info.dependencies'),
            fields: (vm.sdk ? [{label: PM.t('common:plugin-info.sdk'), value: vm.sdk.specified ? vm.sdk.required : PM.t('sdk.any')}] : []).concat([{
                label: PM.t('common:plugin-info.dependency-plugins'),
                value: vm.deps.length ? vm.deps.map(function (dep) {
                    return PM.pluginLabel(dep.pluginId) + (dep.versionSupport ? ' · ' + dep.versionSupport : '')
                        + (dep.optional ? ' · ' + PM.t('deps.optional') : '');
                }).join('\n') : PM.t('common:plugin-info.no-dependencies')
            }])
        }, 'dependencies'));
        if (vm.showExecutionTag) parts.push(vm.trustSections.map(sectionHtml).join(''));
        if (vm.trustPublisher || vm.trustArtifactSha256 || vm.trustPublisherKeyFingerprint) parts.push(sectionHtml({
            title: PM.t('common:plugin-info.artifact'), collapsed: true, fields: [
                {label: PM.t('common:plugin-info.publisher'), value: vm.trustPublisher || PM.t('common:plugin-info.unknown')},
                {label: 'SHA-256', value: vm.trustArtifactSha256 || PM.t('common:plugin-info.unknown')},
                {label: PM.t('common:plugin-info.fingerprint'), value: vm.trustPublisherKeyFingerprint || PM.t('common:plugin-info.unknown')}
            ]
        }, 'artifact'));
        parts.push('</div>');
        if (vm.trustFacts.revocation && vm.trustFacts.revocation.refreshAvailable) parts.push(
            '<button type="button" class="pm-btn pm-btn--gray" data-pm-revocations="' + E(vm.trustFacts.revocation.repositoryId)
            + '"' + (busy ? ' disabled' : '') + '>' + E(PM.t('revocation.refresh')) + '</button>');
        return parts.join('');
    }

    var detailId = null;
    var detailReturnFocus = null;
    var detailPreviousOverflow = '';
    function renderDetail() {
        if (!detailId) return;
        var vm = PM.allViewModels().find(function (item) { return item.id === detailId; });
        if (!vm) { closeDetail(); return; }
        var modal = document.getElementById('pm-detail-modal');
        var content = document.getElementById('pm-detail-content');
        document.getElementById('pm-detail-title').textContent = vm.name;
        var html = detailHtml(vm);
        if (content.pmMarkup === html) return;
        if (content.dataset.pluginId !== vm.id) { content.innerHTML = ''; content.dataset.pluginId = vm.id; }
        var focused = document.activeElement;
        var section = focused && focused.closest('[data-pm-section]');
        var open = Array.from(content.querySelectorAll('details[open]')).map(function (item) { return item.dataset.pmSection; });
        content.innerHTML = html;
        content.pmMarkup = html;
        content.querySelectorAll('details').forEach(function (item) { item.open = open.includes(item.dataset.pmSection); });
        if (focused && !focused.isConnected && modal.open) {
            var target = section && Array.from(content.querySelectorAll('[data-pm-section]')).find(function (item) {
                return item.dataset.pmSection === section.dataset.pmSection;
            });
            (target && target.querySelector('summary') || modal.querySelector('[data-pm-detail-dismiss]')).focus();
        }
    }

    function openDetail(id) {
        var modal = document.getElementById('pm-detail-modal');
        if (modal.open) return;
        detailId = id;
        detailReturnFocus = document.activeElement;
        detailPreviousOverflow = document.body.style.overflow;
        renderDetail();
        if (!detailId) return;
        document.body.style.overflow = 'hidden';
        modal.hidden = false;
        modal.showModal();
        document.getElementById('pm-detail-content').scrollTop = 0;
        modal.querySelector('[data-pm-detail-dismiss]').focus();
    }

    function closeDetail() {
        var modal = document.getElementById('pm-detail-modal');
        modal.close();
        modal.hidden = true;
        document.body.style.overflow = detailPreviousOverflow;
        var trigger = detailReturnFocus && detailReturnFocus.isConnected ? detailReturnFocus
            : Array.from(document.querySelectorAll('[data-pm-details]')).find(function (item) { return item.dataset.pmDetails === detailId; });
        if (!trigger) trigger = document.getElementById('pm-search-input');
        detailId = null;
        if (trigger) trigger.focus({preventScroll: true});
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
    var pendingFocus = null;
    var installResult = null;
    function renderAll() {
        renderDetail();
        if (installResult) showInstallResult(installResult);
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
        stateHost.innerHTML = PM.state.error ? stateHtml('error', PM.state.error) : '';
        var existing = new Map(Array.from(grid.children).map(function (node) { return [node.getAttribute('data-pm-card'), node]; }));
        var focus = document.activeElement;
        var focusCard = focus && focus.closest && focus.closest('[data-pm-card]');
        var focusId = focusCard && focusCard.getAttribute('data-pm-card');
        if (focusId) {
            var owner = focus.closest('[data-pm-section]');
            var attribute = focus.hasAttribute('data-pm-details') ? 'data-pm-details'
                : focus.hasAttribute('data-pm-toggle') ? 'data-pm-toggle'
                : owner && focus.tagName === 'SUMMARY' ? 'data-pm-section' : 'data-pm-action-menu-toggle';
            pendingFocus = {id: focusId, attribute: attribute,
                value: attribute === 'data-pm-section' ? owner.getAttribute(attribute) : null};
        }
        filtered.forEach(function (vm, index) {
            var html = cardHtml(vm);
            var node = existing.get(vm.id);
            existing.delete(vm.id);
            if (!node || node.pmMarkup !== html) {
                var menuOpen = node && node.querySelector('.pm-action-menu.open');
                var open = node ? Array.from(node.querySelectorAll('details[open]')).map(function (item) { return item.getAttribute('data-pm-section'); }) : [];
                var holder = document.createElement('template');
                holder.innerHTML = html;
                var replacement = holder.content.firstElementChild;
                replacement.pmMarkup = html;
                replacement.querySelectorAll('details').forEach(function (item) { item.open = open.indexOf(item.getAttribute('data-pm-section')) !== -1; });
                if (menuOpen && replacement.querySelector('.pm-action-menu')) {
                    replacement.querySelector('.pm-action-menu').classList.add('open');
                    replacement.querySelector('[data-pm-action-menu-toggle]').setAttribute('aria-expanded', 'true');
                    replacement.classList.add('has-open-menu');
                }
                if (node) node.replaceWith(replacement);
                node = replacement;
            }
            if (grid.children[index] !== node) grid.insertBefore(node, grid.children[index] || null);
            if (pendingFocus && pendingFocus.id === vm.id && (document.activeElement === document.body || !focus.isConnected)) {
                var target = Array.from(node.querySelectorAll('[' + pendingFocus.attribute + ']')).find(function (item) {
                    return pendingFocus.value == null || item.getAttribute(pendingFocus.attribute) === pendingFocus.value;
                });
                if (target && target.tagName === 'DETAILS') target = target.querySelector('summary');
                if (target && !target.disabled) { target.focus({preventScroll: true}); pendingFocus = null; }
            }
        });
        existing.forEach(function (node) { node.remove(); });
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
            + E(model.localValidation ? model.message : PM.installFeedback(model).message) + '</span>');
        parts.push('</div>'); // head

        // 恢复阻断优先于落盘 / 激活成功字段：事务恢复完成前不得渲染任何绿色成功说明。
        if (model.recoveryBlocked) {
            parts.push('<div class="pm-install-restart"><i class="fa-solid fa-triangle-exclamation"></i>'
                + E(PM.t('install.recovery-blocked-note', '安装事务需要在重启后恢复；恢复完成前请勿继续安装插件。')) + '</div>');
        } else {
            if (model.effectiveAfterRestart) {
                parts.push('<div class="pm-install-restart"><i class="fa-solid fa-rotate-right"></i>'
                    + E(PM.t('install.restart-note', '插件已安装，将在完整重启程序后生效。')) + '</div>');
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
        if (model.outcome) meta.push(installMetaRow('install.outcome-code', '', model.outcome));
        if (model.pluginId) meta.push(installMetaRow('install.field.plugin-id', '插件 ID', model.pluginId));
        if (model.version) meta.push(installMetaRow('install.field.version', '版本', model.version));
        if (model.previousVersion) meta.push(installMetaRow('install.field.previous-version', '原版本', model.previousVersion));
        if (model.operation) meta.push(installMetaRow('install.field.operation', '事务操作', model.operation));
        if (model.runtimePhase) meta.push(installMetaRow('install.field.runtime-phase', '运行阶段', model.runtimePhase));
        if (model.rollbackVersion) meta.push(installMetaRow('install.field.rollback-version', '已恢复版本', model.rollbackVersion));
        if (model.transactionId) meta.push(installMetaRow('install.field.transaction-id', '事务 ID', model.transactionId));
        if (meta.length) {
            parts.push('<details class="pm-result-details accordion accordion-item pixiv-disclosure"><summary class="accordion-button collapsed">' + E(PM.t('common:plugin-info.diagnostics')) + '</summary><div class="accordion-body">'
                + '<div class="pm-install-meta">' + meta.join('') + '</div>'
                + (model.message ? '<p>' + E(model.message) + '</p>' : '') + '</div></details>');
        }

        parts.push(installList('install.warnings', '尚未满足的依赖', model.warnings, 'fa-diagram-project'));
        if (model.errors && model.errors.length) parts.push('<ul class="pm-install-diagnostics">' + model.errors.map(function (text) { return '<li>' + E(text) + '</li>'; }).join('') + '</ul>');

        parts.push('</div>'); // box
        return parts.join('');
    }

    function installModalEl() {
        return document.getElementById('pm-install-modal');
    }

    function showInstallResult(model) {
        var sameResult = installResult === model;
        installResult = model;
        var host = document.getElementById('pm-install-result');
        if (!host) return;
        var html = renderInstallResultHtml(model);
        if (sameResult && host.pmMarkup === html) return;
        var previous = sameResult && host.querySelector('.pm-result-details');
        var expanded = previous && previous.open;
        var focused = previous && previous.contains(document.activeElement);
        host.innerHTML = html;
        host.pmMarkup = html;
        var details = host.querySelector('.pm-result-details');
        if (details) {
            details.open = !!expanded;
            if (focused) details.querySelector('summary').focus({preventScroll: true});
        }
    }

    function clearInstallResult() {
        installResult = null;
        var host = document.getElementById('pm-install-result');
        if (host) host.innerHTML = '';
    }

    // 文件名标签：选中文件 → 显示文件名并摘掉 data-i18n（避免语言切换时被 apply 覆盖回「未选择文件」）；
    // 清空 → 还原 data-i18n + 当前语言的「未选择文件」文案。
    function setPickedFilename(elementId, i18nKey, fallback, name) {
        var el = document.getElementById(elementId);
        if (!el) return;
        if (name) {
            el.removeAttribute('data-i18n');
            el.textContent = name;
            el.classList.add('has-file');
        } else {
            el.setAttribute('data-i18n', i18nKey);
            el.textContent = PM.t(i18nKey, fallback);
            el.classList.remove('has-file');
        }
    }

    function setInstallFilename(name) {
        setPickedFilename('pm-install-filename', 'install.no-file', '未选择文件', name);
    }

    function setInstallSignatureFilename(name) {
        setPickedFilename('pm-install-signature-filename', 'install.signature.no-file', '未选择签名文件', name);
    }

    function setInstallSubmitting(busy) {
        var btn = document.getElementById('pm-install-submit');
        if (btn) btn.disabled = !!busy;
        PM.state.installBusy = !!busy;
    }

    var installReturnFocus = null;
    var installPreviousOverflow = '';
    function openInstallModal() {
        var modal = installModalEl();
        if (!modal || modal.open) return;
        installReturnFocus = document.activeElement;
        installPreviousOverflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        modal.hidden = false;
        modal.showModal();
        modal.querySelector('.pm-modal-close').focus();
    }

    function closeInstallModal() {
        var modal = installModalEl();
        if (!modal) return;
        modal.close();
        modal.hidden = true;
        document.body.style.overflow = installPreviousOverflow;
        if (installReturnFocus && installReturnFocus.isConnected) installReturnFocus.focus();
    }

    PM.renderAll = renderAll;
    PM.renderCardHtml = cardHtml;
    PM.renderDetailHtml = detailHtml;
    PM.openDetail = openDetail;
    PM.closeDetail = closeDetail;
    PM.toast = toast;
    PM.renderInstallResultHtml = renderInstallResultHtml;
    PM.showInstallResult = showInstallResult;
    PM.clearInstallResult = clearInstallResult;
    PM.setInstallFilename = setInstallFilename;
    PM.setInstallSignatureFilename = setInstallSignatureFilename;
    PM.setInstallSubmitting = setInstallSubmitting;
    PM.openInstallModal = openInstallModal;
    PM.closeInstallModal = closeInstallModal;
})(window);
