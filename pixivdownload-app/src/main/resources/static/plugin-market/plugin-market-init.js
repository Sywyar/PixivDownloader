'use strict';
/*
 * 插件市场页启动与顶层事件收口（最后加载）：初始化 i18n + 绿色变体语言 / 主题切换、拉取受信仓库状态、绑定刷新。
 * 所有顶层立即执行语句（DOMContentLoaded 启动、事件绑定）都集中在本模块。
 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var namespaces = ['plugin-market', 'common'];

    function applyStaticTranslations() {
        if (PMK.state.i18n.client) {
            PMK.state.i18n.client.apply(document.body);
        }
        document.title = PMK.t('page.title', '插件市场 · Pixiv 下载助手');
    }

    // 在固定锚点挂载绿色变体的语言切换 + 主题切换；切语言后重译 chrome、刷新导航、整体重渲染。
    async function mountChrome() {
        var anchor = document.getElementById('langSwitcherAnchor');
        anchor.innerHTML = '';
        await PixivLangSwitcher.mount({
            mountPoint: anchor,
            i18n: PMK.state.i18n.client,
            variant: 'green',
            onChange: function (nextClient) {
                PMK.state.i18n.client = nextClient;
                applyStaticTranslations();
                if (global.PixivNav) PixivNav.refresh();
                PMK.renderAll();
            }
        });
        PixivTheme.mount({ mountPoint: anchor, variant: 'green' });
    }

    async function ensureI18n() {
        PMK.state.i18n.client = await PixivI18n.create({ namespaces: namespaces });
        await mountChrome();
        applyStaticTranslations();
    }

    // 拉取受信仓库状态并渲染。
    async function load() {
        PMK.state.loading = true;
        PMK.state.error = null;
        PMK.renderAll();
        try {
            PMK.state.data = await PMK.fetchRepositories();
        } catch (e) {
            PMK.state.error = PMK.t('error.load', '加载插件市场失败，请稍后重试。');
        } finally {
            PMK.state.loading = false;
        }
        PMK.renderAll();
    }

    function wireEvents() {
        var refresh = document.getElementById('pmk-refresh-btn');
        if (refresh) refresh.addEventListener('click', function () { load(); });
    }

    async function init() {
        wireEvents();
        await ensureI18n();   // 初始 i18n（plugin-market + common）+ 挂载语言 / 主题切换
        await load();         // 拉取仓库状态并渲染
    }

    // 顶部导航栏「退出」：登出后回到本页（未登录将由 AuthFilter 重定向到登录页）。
    global.pmkLogout = async function () {
        try {
            await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' });
        } catch (e) {
            // 忽略：即便登出请求失败也照常跳转。
        }
        window.location.href = '/plugin-market.html';
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(window);
