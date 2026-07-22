'use strict';
/*
 * 插件管理页启动与顶层事件收口（最后加载）：初始化 i18n + 绿色变体语言 / 主题切换、拉取后端状态、绑定刷新 /
 * 安装 / 筛选 / 搜索 / 卡片动作事件、执行运行期动词后重拉状态并刷新导航。
 * 所有顶层立即执行语句（DOMContentLoaded 启动、事件绑定）都集中在本模块。
 */
(function (global) {
    var PM = global.PixivPluginManage;
    var loadedNamespaces = ['plugins', 'common'];

    function applyStaticTranslations() {
        if (PM.i18n.client) {
            PM.i18n.client.apply(document.body);
        }
        document.title = PM.t('page.title', '插件管理 · Pixiv 下载助手');
    }

    // 在固定锚点（重）挂载绿色变体的语言切换 + 主题切换；幂等（先清空锚点，避免命名空间扩展时重复挂载）。
    // await 语言切换挂载完成再返回，避免在途挂载与下一次重挂（命名空间增长时）竞态。
    async function mountChrome() {
        var anchor = document.getElementById('langSwitcherAnchor');
        anchor.innerHTML = '';
        await PixivLangSwitcher.mount({
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

    // 确保 i18n 客户端已加载所需 namespace（含插件展示名所在的动态 namespace）；命名空间增长时重建并重挂 chrome。
    async function ensureI18n(extraNamespaces) {
        var need = loadedNamespaces.slice();
        (extraNamespaces || []).forEach(function (ns) {
            if (need.indexOf(ns) === -1) need.push(ns);
        });
        var grew = need.length !== loadedNamespaces.length;
        if (!PM.i18n.client || grew) {
            loadedNamespaces = need;
            PM.i18n.client = await PixivI18n.create({
                lang: PM.i18n.client ? PM.i18n.client.lang : undefined,
                namespaces: loadedNamespaces
            });
            await mountChrome();
        }
        applyStaticTranslations();
    }

    // 拉取插件状态：先确定需要的展示 namespace，再据此（重）建 i18n，最后整体渲染。
    async function load() {
        PM.state.loading = true;
        PM.state.error = null;
        PM.renderAll();
        var report = null;
        try {
            report = await PM.fetchStatus();
            PM.state.report = report;
        } catch (e) {
            PM.state.error = PM.t('status.error', '加载插件状态失败，请重试。');
        } finally {
            PM.state.loading = false;
        }
        if (report) {
            await ensureI18n(PM.collectNamespaces(report));
        }
        PM.renderAll();
    }

    // 开关：热重载插件沿用 start / stop；其它策略持久化 enabled 后按策略提示重启。
    function onToggle(id) {
        var models = PM.allViewModels();
        var vm = null;
        for (var i = 0; i < models.length; i++) {
            if (models[i].id === id) { vm = models[i]; break; }
        }
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
                await PM.setEnabled(vm.id, targetEnabled);
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
            var result = await PM.performAction(id, verb);
            var actionLabel = PM.t('action.' + (result && result.action || verb), (result && result.action) || verb);
            PM.toast(PM.t('action.done', '已执行：{action}', { action: actionLabel }), 'ok');
        } catch (e) {
            var message = (e && e.message) || PM.t('action.error.generic', '操作失败');
            PM.toast(PM.t('action.failed', '操作失败：{message}', { message: message }), 'error');
        } finally {
            PM.state.busyId = null;
        }
        await load();
        if (global.PixivNav) PixivNav.refresh();
    }

    // 本地包安装：提交当前选中的 .jar / .zip，后端统一完成校验、事务替换、激活与失败回滚。
    async function submitInstall() {
        if (PM.state.installBusy) return;
        var fileInput = document.getElementById('pm-install-file');
        var file = fileInput && fileInput.files && fileInput.files.length ? fileInput.files[0] : null;
        if (!file) {
            // 未选文件：本地校验，不发请求。
            PM.showInstallResult(PM.localInstallNotice(
                PM.t('install.choose-file', '请先选择要安装的插件包（.jar / .zip）。'), 'warn'));
            return;
        }
        if (!PM.hasAcceptedExtension(file.name)) {
            // 扩展名非 .jar / .zip：本地校验，不发请求（后端仍是权威校验）。
            PM.showInstallResult(PM.localInstallNotice(
                PM.t('install.invalid-extension', '仅支持 .jar / .zip 插件包，请重新选择。'), 'warn'));
            return;
        }
        var allow = document.getElementById('pm-install-allow-downgrade');
        var allowDowngrade = !!(allow && allow.checked);

        PM.setInstallSubmitting(true);
        PM.clearInstallResult();
        try {
            var response = await PM.installPackage(file, allowDowngrade);
            var model = PM.buildInstallResult(response);
            PM.showInstallResult(model);
            var feedback = PM.installFeedback(model);
            PM.toast(feedback.message, feedback.tone);
        } catch (e) {
            if (e && e.localValidation) {
                PM.showInstallResult(PM.localInstallNotice(
                    PM.t('install.choose-file', '请先选择要安装的插件包（.jar / .zip）。'), 'warn'));
            } else {
                PM.showInstallResult(PM.localInstallNotice(
                    PM.t('install.error.generic', '安装请求失败，请重试。'), 'bad'));
                PM.toast(PM.t('install.error.generic', '安装请求失败，请重试。'), 'error');
            }
        } finally {
            PM.setInstallSubmitting(false);
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

        var modal = document.getElementById('pm-install-modal');
        modal.addEventListener('click', function (e) {
            if (e.target.closest('[data-pm-install-dismiss]')) {
                PM.closeInstallModal();
            }
        });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && !modal.hidden) {
                PM.closeInstallModal();
            }
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
            syncPluginSegment(e.detail && e.detail.items);
        });
        document.getElementById('refreshBtn').addEventListener('click', function () { load(); });
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

        document.getElementById('pm-grid').addEventListener('click', function (e) {
            var toggle = e.target.closest('[data-pm-toggle]');
            if (toggle && !toggle.disabled) {
                onToggle(toggle.getAttribute('data-pm-toggle'));
                return;
            }
            var action = e.target.closest('[data-pm-action]');
            if (action && !action.disabled) {
                onAction(action.getAttribute('data-pm-id'), action.getAttribute('data-pm-action'));
            }
        });
    }

    async function init() {
        wireEvents();
        await ensureI18n([]);   // 初始 i18n（plugins + common）+ 挂载语言 / 主题切换，先把页面 chrome 翻译就位
        await load();           // 拉取状态、按需扩展 namespace、整体渲染
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
