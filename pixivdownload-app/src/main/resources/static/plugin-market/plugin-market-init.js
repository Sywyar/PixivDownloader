'use strict';
/*
 * 插件市场页启动与顶层事件收口（最后加载）：初始化 i18n + 绿色变体语言 / 主题切换，挂载主渲染器（Vue reactive，
 * 失败回退命令式），并提供 toast / 登出。所有顶层立即执行语句（DOMContentLoaded 启动）都集中在本模块。
 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var namespaces = ['plugin-market', 'common'];
    var toastTimer = null;

    function applyStaticTranslations() {
        if (PMK.state.i18n.client) {
            PMK.state.i18n.client.apply(document.body);
        }
        document.title = PMK.t('page.title', '插件市场 · Pixiv 下载助手');
    }

    // 在固定锚点挂载绿色变体的语言切换 + 主题切换；切语言后重译静态 chrome、刷新导航、并让当前渲染器重渲染。
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
                if (PMK.state.activeView && PMK.state.activeView.rerender) {
                    PMK.state.activeView.rerender();
                }
            }
        });
        PixivTheme.mount({ mountPoint: anchor, variant: 'green' });
    }

    async function ensureI18n() {
        PMK.state.i18n.client = await PixivI18n.create({ namespaces: namespaces });
        await mountChrome();
        applyStaticTranslations();
    }

    // 轻提示（安装结果 / 错误反馈）。文案由调用方解析后传入，本函数只设文本（textContent，不拼 HTML）。
    PMK.toast = function (message, tone) {
        var el = document.getElementById('pmk-toast');
        if (!el) return;
        el.textContent = message;
        el.className = 'pmk-toast pmk-toast--' + (tone || 'info') + ' show';
        if (toastTimer) clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { el.className = 'pmk-toast'; }, 3600);
    };

    // 顶部导航栏「退出」：登出后回到本页（未登录将由 AuthFilter 重定向到登录页）。
    global.pmkLogout = async function () {
        try {
            await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' });
        } catch (e) {
            // 忽略：即便登出请求失败也照常跳转。
        }
        window.location.href = '/plugin-market.html';
    };

    async function init() {
        await ensureI18n();   // 初始 i18n（plugin-market + common）+ 挂载语言 / 主题切换
        var root = document.getElementById('pmk-app-root');
        var mounted = false;
        try {
            mounted = await PMK.vue.tryMount(root);   // 主路径：Vue reactive
        } catch (e) {
            mounted = false;
        }
        if (!mounted) {
            PMK.fallback.render(root);                // 回退：命令式渲染（可诊断降级、浏览 / 安装仍可用）
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(window);
