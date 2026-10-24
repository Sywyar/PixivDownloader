'use strict';
/*
 * 插件管理页启动与顶层事件收口（最后加载）：初始化 i18n + 绿色变体语言 / 主题切换、拉取后端状态、绑定刷新 /
 * 安装 / 筛选 / 搜索 / 卡片动作事件、执行运行期动词后重拉状态并刷新导航。
 * 所有顶层立即执行语句（DOMContentLoaded 启动、事件绑定）都集中在本模块。
 */
(function (global) {
    var PM = global.PixivPluginManage;
    var loadedNamespaces = ['plugins', 'common'];
    var languageControl = null;
    var statusRequest = null;
    var pageActive = true;

    function applyStaticTranslations() {
        if (PM.i18n.client) {
            PM.i18n.client.apply(document.body);
        }
        document.title = PM.t('page.title', '插件管理 · Pixiv 下载助手');
        renderUpdates();
    }

    function renderUpdates() {
        var box = document.getElementById('pm-updates');
        var summary = PM.state.pluginUpdates;
        box.replaceChildren();
        box.hidden = !summary || !summary.enabled;
        if (box.hidden) return;
        [
            ['compatible', summary.compatibleUpdates, 'pm-update-compatible'],
            ['sdkBlocked', summary.sdkBlockedUpdates, 'pm-update-blocked'],
            ['failed', summary.checkFailed ? 1 : 0, ''],
            ['none', !summary.checkFailed && !summary.compatibleUpdates && !summary.sdkBlockedUpdates ? 1 : 0, '']
        ].forEach(function (item) {
            if (!item[1]) return;
            var line = document.createElement('p');
            line.className = item[2];
            line.textContent = PM.t('updates.' + item[0], '', { count: item[1] });
            box.appendChild(line);
        });
        if (summary.compatibleUpdates || summary.sdkBlockedUpdates) {
            var hint = document.createElement('p');
            hint.textContent = PM.t('updates.market', '');
            box.appendChild(hint);
        }
    }

    async function mountChrome() {
        if (languageControl) {
            languageControl.refresh(PM.i18n.client);
            return;
        }
        var anchor = document.getElementById('langSwitcherAnchor');
        languageControl = await PixivLangSwitcher.mount({
            mountPoint: anchor,
            i18n: PM.i18n.client,
            variant: 'green',
            onChange: function (nextClient) {
                PM.i18n.client = nextClient;
                applyStaticTranslations();
                if (global.PixivNav) PixivNav.refresh();
                PM.renderAll();
            }
        });
        PixivTheme.mount({ mountPoint: anchor, variant: 'green' });
    }

    async function ensureI18n(extraNamespaces, signal) {
        var need = ['plugins', 'common'];
        (extraNamespaces || []).forEach(function (ns) {
            if (need.indexOf(ns) === -1) need.push(ns);
        });
        var changed = need.length !== loadedNamespaces.length || need.some(function (ns) {
            return loadedNamespaces.indexOf(ns) === -1;
        });
        while (!signal.aborted && (!PM.i18n.client || changed)) {
            var previous = PM.i18n.client;
            var client = await PixivI18n.create({
                lang: previous ? previous.lang : undefined,
                namespaces: need,
                signal: signal
            });
            if (signal.aborted || previous !== PM.i18n.client) {
                client.dispose();
                continue;
            }
            loadedNamespaces = need;
            PM.i18n.client = client;
            await mountChrome();
            break;
        }
        if (!signal.aborted) applyStaticTranslations();
    }

    // 拉取插件状态：先确定需要的展示 namespace，再据此（重）建 i18n，最后整体渲染。
    async function load(resetOrder) {
        if (!pageActive) return;
        if (statusRequest) statusRequest.abort();
        var request = new AbortController();
        statusRequest = request;
        PM.state.loading = true;
        PM.state.error = null;
        PM.renderAll();
        try {
            if (!PM.i18n.client) await ensureI18n([], request.signal);
            if (request.signal.aborted) return;
            var report = await PM.fetchStatus(request.signal);
            if (request.signal.aborted) return;
            await ensureI18n(PM.collectNamespaces(report), request.signal);
            if (request.signal.aborted) return;
            PM.applyReport(report, !!resetOrder);
        } catch (e) {
            if (!request.signal.aborted) PM.state.error = 'status.error';
        } finally {
            if (statusRequest === request) {
                statusRequest = null;
                PM.state.loading = false;
                PM.renderAll();
            }
        }
        if (global.PixivNav) PixivNav.ready().then(function () {
            if (pageActive && !request.signal.aborted) return PixivNav.pluginUpdates();
        });
    }

    // 开关：热重载插件沿用 start / stop；其它策略持久化 enabled 后按策略提示重启。
    function onToggle(id) {
        var vm = PM.allViewModels().find(function (candidate) { return candidate.id === id; });
        if (!vm || !vm.toggleable) return;
        if (vm.lifecyclePolicy === 'HOT_RELOAD') {
            onAction(id, vm.running ? 'stop' : 'start');
            return;
        }
        onConfiguredToggle(vm);
    }

    async function onConfiguredToggle(vm) {
        if (PM.state.busyId) return;
        var targetEnabled = !vm.enabled;
        PM.state.busyId = vm.id;
        PM.renderAll();
        try {
            try {
                var fingerprint = await PM.confirmImpact(vm.id, targetEnabled ? 'start' : 'stop');
                if (!fingerprint) return;
                await PM.setEnabled(vm.id, targetEnabled, fingerprint);
            } catch (e) {
                var saveMessage = (e && e.message) || PM.t('toggle.error.generic', '保存插件启停设置失败');
                PM.toast(PM.t('toggle.failed', '保存插件启停设置失败：{message}', { message: saveMessage }), 'error');
                return;
            }

            PM.toast(PM.t(targetEnabled ? 'toggle.saved.enabled' : 'toggle.saved.disabled',
                targetEnabled ? '{plugin} 的启用设置已保存。' : '{plugin} 的停用设置已保存。',
                { plugin: vm.name }), 'ok');
            // 先重拉状态，让开关立即反映 configuredEnabled；运行态仍由后端 runtimePhase 如实展示。
            await load();

            if (vm.lifecyclePolicy === 'BACKEND_RESTART') {
                var restartNow = await global.PixivFeedback.confirm({
                    title: PM.t('restart.backend.title', '重启后端'),
                    message: PM.t('restart.backend.message',
                        '“{plugin}”的启停设置需要重启后端才能生效。是否立即重启？', { plugin: vm.name }),
                    confirmLabel: PM.t('restart.backend.confirm', '立即重启'),
                    cancelLabel: PM.t('restart.backend.later', '稍后')
                });
                if (restartNow) {
                    try {
                        await PM.restartBackend();
                        PM.toast(PM.t('restart.backend.requested', '后端正在重启，请稍候。'), 'ok');
                    } catch (e) {
                        var restartMessage = (e && e.message) || PM.t('restart.backend.error.generic', '后端重启失败');
                        PM.toast(PM.t('restart.backend.failed', '后端重启失败：{message}',
                            { message: restartMessage }), 'error');
                    }
                }
            } else if (vm.lifecyclePolicy === 'PROCESS_RESTART') {
                await global.PixivFeedback.alert({
                    title: PM.t('restart.process.title', '需要重启软件'),
                    message: PM.t('restart.process.message',
                        '“{plugin}”的启停设置需要完整重启软件后才能生效。请手动退出并重新启动。',
                        { plugin: vm.name }),
                    confirmLabel: PM.t('restart.process.done', '知道了')
                });
            }
        } finally {
            PM.state.busyId = null;
            PM.renderAll();
        }
    }

    // 执行运行期动词；动作串行化（busyId），完成后重拉状态 + 刷新导航（启停插件会增减跨插件入口）。
    async function onAction(id, verb) {
        if (PM.state.busyId) return;
        PM.state.busyId = id;
        PM.renderAll();
        try {
            var fingerprint = await PM.confirmImpact(id, verb);
            if (!fingerprint) return;
            var result = await PM.performAction(id, verb, fingerprint);
            var actionLabel = PM.t('action.' + (result && result.action || verb), (result && result.action) || verb);
            PM.toast(PM.t('action.done', '已执行：{action}', { action: actionLabel }), 'ok');
            if (result && result.effectiveAfterRestart) {
                await global.PixivFeedback.alert({
                    title: PM.t('restart.process.title', '需要重启软件'),
                    message: PM.t('remove.restart-message',
                        '插件包已移除，用户数据已保留。当前加载的插件会在完整退出并重新启动软件后移除。'),
                    confirmLabel: PM.t('restart.process.done', '知道了')
                });
            }
        } catch (e) {
            var message = (e && e.message) || PM.t('action.error.generic', '操作失败');
            PM.toast(PM.t('action.failed', '操作失败：{message}', { message: message }), 'error');
        } finally {
            PM.state.busyId = null;
            PM.renderAll();
        }
        await load();
        if (global.PixivNav) PixivNav.mount();
    }

    async function onRevocationRefresh(repositoryId, button) {
        if (button.disabled || PM.state.revocationRefreshing) return;
        PM.state.revocationRefreshing = true;
        button.disabled = true;
        try {
            var result = await PM.refreshRevocations(repositoryId);
            PM.toast(result.refreshed ? PM.t('revocation.refreshed') : PM.t('revocation.failed', '', {code: result.code}),
                result.refreshed ? 'ok' : 'error');
        } catch (failure) {
            PM.toast(PM.t('revocation.failed', '', {code: failure.code || failure.message}), 'error');
        } finally {
            button.disabled = false;
            PM.state.revocationRefreshing = false;
            await load();
        }
    }

    async function onTrustAction(id, action) {
        if (PM.state.busyId) return;
        var vm = PM.allViewModels().find(function (candidate) { return candidate.id === id; });
        if (!vm) return;
        if (action === 'approve') {
            if (!vm.trustApprovable || !vm.trustArtifactSha256) return;
            var approved = await global.PixivFeedback.confirm(PM.trustConfirmationOptions({
                id: vm.id,
                version: vm.version,
                source: vm.trustSource,
                publisher: vm.trustPublisher,
                publisherKeyFingerprint: vm.trustPublisherKeyFingerprint,
                artifactSha256: vm.trustArtifactSha256,
                executionMode: vm.executionMode,
                riskDeclaration: vm.trustFacts.riskDeclaration,
                repositoryTrustSource: vm.trustFacts.repositoryTrustSource,
                assuranceLevel: vm.trustFacts.assuranceLevel,
                revocationStatus: vm.trustFacts.revocationStatus,
                executionLabel: vm.executionLabel,
                hostElevated: !!(PM.state.report && PM.state.report.hostElevated)
            }));
            if (!approved) return;
        } else if (action === 'revoke') {
            if (!vm.trustRevocable) return;
            var revoked = await global.PixivFeedback.confirm({
                title: PM.t('trust.revoke.title', '撤销插件执行信任'),
                message: PM.t('trust.revoke.message',
                    '撤销“{plugin}”的执行信任后，当前已经运行的实例不会被强制终止，但下次加载、重载或启动时会被阻止，直到你重新批准当前精确制品。',
                    { plugin: vm.name }),
                confirmLabel: PM.t('trust.revoke.confirm', '撤销执行信任'),
                cancelLabel: PM.t('trust.revoke.cancel', '取消')
            });
            if (!revoked) return;
        } else {
            return;
        }

        PM.state.busyId = id;
        PM.renderAll();
        try {
            if (action === 'approve') {
                await PM.approveTrust(id, vm.trustArtifactSha256);
                PM.toast(PM.t('trust.toast.approved', '已批准当前精确制品的执行信任。'), 'ok');
            } else {
                await PM.revokeTrust(id);
                PM.toast(PM.t('trust.toast.revoked', '执行信任已撤销；下次执行前必须重新确认。'), 'ok');
            }
        } catch (e) {
            var message = (e && e.message) || PM.t('trust.error.generic', '更新插件执行信任失败');
            PM.toast(PM.t('trust.error.failed', '更新插件执行信任失败：{message}', { message: message }), 'error');
        } finally {
            PM.state.busyId = null;
            await load();
        }
    }

    // 本地包安装：提交当前选中的 .jar / .zip，后端统一完成校验、事务替换、激活与失败回滚。
    async function submitInstall() {
        if (PM.state.installBusy) return;
        var fileInput = document.getElementById('pm-install-file');
        var file = fileInput && fileInput.files && fileInput.files.length ? fileInput.files[0] : null;
        var signatureInput = document.getElementById('pm-install-signature');
        var signature = signatureInput && signatureInput.files && signatureInput.files.length
            ? signatureInput.files[0] : null;
        if (!file) {
            // 未选文件：本地校验，不发请求。
            PM.showInstallResult(PM.localInstallNotice(
                'install.choose-file', 'warn'));
            return;
        }
        if (!PM.hasAcceptedExtension(file.name)) {
            // 扩展名非 .jar / .zip：本地校验，不发请求（后端仍是权威校验）。
            PM.showInstallResult(PM.localInstallNotice(
                'install.invalid-extension', 'warn'));
            return;
        }
        if (signature && !PM.hasAcceptedSignatureExtension(signature.name)) {
            PM.showInstallResult(PM.localInstallNotice(
                'install.invalid-signature-extension', 'warn'));
            return;
        }
        var allow = document.getElementById('pm-install-allow-downgrade');
        var allowDowngrade = !!(allow && allow.checked);

        PM.setInstallSubmitting(true);
        PM.clearInstallResult();
        try {
            var response = await PM.installPackageWithConfirmation(file, signature, allowDowngrade);
            var model = PM.buildInstallResult(response);
            PM.showInstallResult(model);
            var feedback = PM.installFeedback(model);
            PM.toast(feedback.message, feedback.tone);
        } catch (e) {
            if (e && e.invalidSignatureExtension) {
                PM.showInstallResult(PM.localInstallNotice(
                    'install.invalid-signature-extension', 'warn'));
            } else if (e && e.localValidation) {
                PM.showInstallResult(PM.localInstallNotice(
                    'install.choose-file', 'warn'));
            } else {
                PM.showInstallResult(PM.localInstallNotice(
                    'install.error.generic', 'bad'));
                PM.toast(PM.t('install.error.generic', '安装请求失败，请重试。'), 'error');
            }
        } finally {
            PM.setInstallSubmitting(false);
            await load();
            if (global.PixivNav) PixivNav.mount();
        }
    }

    // 安装弹窗事件：打开 / 关闭（按钮 + 背板 + Esc）、文件选择回显、提交。
    function wireInstall() {
        document.getElementById('installBtn').addEventListener('click', function () { PM.openInstallModal(); });
        document.getElementById('pm-install-submit').addEventListener('click', function () { submitInstall(); });

        var fileInput = document.getElementById('pm-install-file');
        fileInput.addEventListener('change', function () {
            var f = fileInput.files && fileInput.files.length ? fileInput.files[0] : null;
            PM.setInstallFilename(f ? f.name : null);
            PM.clearInstallResult();   // 换选文件后清掉上一次结果，避免误读
        });
        var signatureInput = document.getElementById('pm-install-signature');
        signatureInput.addEventListener('change', function () {
            var f = signatureInput.files && signatureInput.files.length ? signatureInput.files[0] : null;
            PM.setInstallSignatureFilename(f ? f.name : null);
            PM.clearInstallResult();
        });

        var modal = document.getElementById('pm-install-modal');
        modal.addEventListener('click', function (e) {
            if (e.target === modal || e.target.closest('[data-pm-install-dismiss]')) {
                PM.closeInstallModal();
            }
        });
        modal.addEventListener('cancel', function (event) {
            event.preventDefault();
            PM.closeInstallModal();
        });
    }

    // 页内分段控件：PixivNav 已按当前身份把 plugins.segment 的完整 contribution 渲染进 HTML 空 slot；本页只据
    // 同一批导航数据控制外层容器显隐，不读取插件 id，也不复制可选页的 href、图标或 i18n 文案。
    function syncPluginSegment(items) {
        var host = document.getElementById('pm-seg-host');
        if (host) host.hidden = !PM.hasNavigationForPlacement(items, 'plugins.segment');
    }

    function wireEvents() {
        // 早绑定（PixivNav 的导航拉取为异步网络请求，本监听器先于其首次 pixivnav:rendered 派发就位）。
        window.addEventListener('pixivnav:rendered', function (e) {
            PM.state.pluginUpdates = e.detail && e.detail.updateSummary;
            renderUpdates();
            syncPluginSegment(e.detail && e.detail.items);
        });
        document.getElementById('refreshBtn').addEventListener('click', function () { load(true); });
        window.addEventListener('pagehide', function () {
            pageActive = false;
            if (statusRequest) statusRequest.abort();
            statusRequest = null;
            PM.state.loading = false;
        });
        window.addEventListener('pageshow', function () {
            if (pageActive) return;
            pageActive = true;
            load();
        });
        wireInstall();

        var search = document.getElementById('pm-search-input');
        search.addEventListener('input', function () {
            PM.state.search = search.value || '';
            PM.renderAll();
        });

        document.getElementById('pm-tabs').addEventListener('click', function (e) {
            var btn = e.target.closest('[data-pm-tab]');
            if (!btn) return;
            PM.state.activeTab = btn.getAttribute('data-pm-tab');
            PM.renderAll();
        });

        var detailModal = document.getElementById('pm-detail-modal');
        detailModal.addEventListener('cancel', function (event) { event.preventDefault(); event.stopPropagation(); PM.closeDetail(); });
        detailModal.addEventListener('click', function (event) {
            if (event.target === detailModal || event.target.closest('[data-pm-detail-dismiss]')) PM.closeDetail();
            var refresh = event.target.closest('[data-pm-revocations]');
            if (refresh) onRevocationRefresh(refresh.getAttribute('data-pm-revocations'), refresh);
        });
        var grid = document.getElementById('pm-grid');

        function closeActionMenus() {
            grid.querySelectorAll('.pm-action-menu.open').forEach(function (menu) {
                menu.classList.remove('open');
                var card = menu.closest('.pm-card');
                if (card) card.classList.remove('has-open-menu');
                var toggle = menu.closest('.pm-action-menu-wrap').querySelector('[data-pm-action-menu-toggle]');
                if (toggle) toggle.setAttribute('aria-expanded', 'false');
            });
        }

        grid.addEventListener('keydown', function (event) {
            var menu = grid.querySelector('.pm-action-menu.open');
            if (!menu) return;
            var toggle = menu.closest('.pm-action-menu-wrap').querySelector('[data-pm-action-menu-toggle]');
            if (event.key === 'Escape') {
                event.preventDefault(); event.stopPropagation(); closeActionMenus(); toggle.focus();
            } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault();
                var items = Array.from(menu.querySelectorAll('button:not(:disabled)'));
                var current = items.indexOf(document.activeElement);
                var next = (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
                if (items[next]) items[next].focus();
            }
        });
        grid.addEventListener('click', function (e) {
            var details = e.target.closest('[data-pm-details]');
            if (details) { closeActionMenus(); PM.openDetail(details.getAttribute('data-pm-details')); return; }
            var repair = e.target.closest('[data-pm-repair]');
            if (repair && !repair.disabled && !PM.state.installBusy) {
                PM.openInstallModal();
                return;
            }
            var refresh = e.target.closest('[data-pm-revocations]');
            if (refresh) {
                onRevocationRefresh(refresh.getAttribute('data-pm-revocations'), refresh);
                return;
            }
            var toggle = e.target.closest('[data-pm-toggle]');
            if (toggle && !toggle.disabled) {
                onToggle(toggle.getAttribute('data-pm-toggle'));
                return;
            }
            var action = e.target.closest('[data-pm-action]');
            if (action && !action.disabled) {
                closeActionMenus();
                onAction(action.getAttribute('data-pm-id'), action.getAttribute('data-pm-action'));
                return;
            }
            var trustAction = e.target.closest('[data-pm-trust-action]');
            if (trustAction && !trustAction.disabled) {
                closeActionMenus();
                onTrustAction(trustAction.getAttribute('data-pm-id'),
                    trustAction.getAttribute('data-pm-trust-action'));
                return;
            }
            var menuToggle = e.target.closest('[data-pm-action-menu-toggle]');
            if (menuToggle && !menuToggle.disabled) {
                var menu = menuToggle.closest('.pm-action-menu-wrap').querySelector('.pm-action-menu');
                var opening = !menu.classList.contains('open');
                closeActionMenus();
                menu.classList.toggle('open', opening);
                var card = menuToggle.closest('.pm-card');
                if (card) card.classList.toggle('has-open-menu', opening);
                menuToggle.setAttribute('aria-expanded', opening ? 'true' : 'false');
                if (opening) {
                    var anchor = menuToggle.getBoundingClientRect();
                    menu.classList.toggle('pm-action-menu--above', anchor.bottom + menu.offsetHeight + 8 > window.innerHeight);
                }
                return;
            }
        });

        document.addEventListener('click', function (e) {
            if (!e.target.closest('.pm-action-menu-wrap')) closeActionMenus();
        });
    }

    async function init() {
        PixivActions.bind(document, { click: { pmLogout: global.pmLogout } });
        wireEvents();
        await load();
    }

    // 顶部导航栏「退出」：登出后回到本页（未登录将由 AuthFilter 重定向到登录页）。
    global.pmLogout = async function () {
        try {
            await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' });
        } catch (e) {
            // 忽略：即便登出请求失败也照常跳转。
        }
        window.location.href = '/plugin-manage.html';
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(window);
