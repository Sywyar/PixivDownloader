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

    // 开关：据当前运行态决定 start / stop（仅受管 + 允许停用的插件可切换）。
    function onToggle(id) {
        var models = PM.allViewModels();
        var vm = null;
        for (var i = 0; i < models.length; i++) {
            if (models[i].id === id) { vm = models[i]; break; }
        }
        if (!vm || !vm.toggleable) return;
        onAction(id, vm.running ? 'stop' : 'start');
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

    function wireEvents() {
        document.getElementById('refreshBtn').addEventListener('click', function () { load(); });

        // 从 URL 安装：后端暂无安装端点；先给出明确提示，待后端实现后接线。
        document.getElementById('installBtn').addEventListener('click', function () {
            PM.toast(PM.t('install.todo', '「从 URL 安装」功能尚未接入后端。'), 'info');
        });

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
