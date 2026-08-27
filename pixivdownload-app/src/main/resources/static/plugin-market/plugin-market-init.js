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
        PixivActions.bind(document, { click: { pmkLogout: global.pmkLogout } });
        await ensureI18n();   // 初始 i18n（plugin-market + common）+ 挂载语言 / 主题切换
        mountRepositoryImport();
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

    function mountRepositoryImport() {
        var form = document.getElementById('pmk-repository-import-form');
        var input = document.getElementById('pmk-repository-url');
        var result = document.getElementById('pmk-repository-preview');
        var confirm = document.getElementById('pmk-repository-confirm');
        var trust = document.getElementById('pmk-repository-trust');
        if (!form || !input || !result || !confirm || !trust) return;
        var preview = null;

        function row(label, value) {
            var dt = document.createElement('dt'); dt.textContent = label;
            var dd = document.createElement('dd'); dd.textContent = value == null || value === '' ? '—' : String(value);
            result.appendChild(dt); result.appendChild(dd);
        }

        function render(data) {
            result.textContent = '';
            row(PMK.t('import.field.repository', '仓库'), data.displayName + ' (' + data.repositoryId + ')');
            row(PMK.t('import.field.publisher', '发布者'), data.publisherDisplayName + ' (' + data.publisherId + ')');
            row(PMK.t('import.field.descriptor', '描述符'), data.descriptorUrl);
            row(PMK.t('import.field.digest', '描述符 SHA-256'), data.descriptorSha256);
            row(PMK.t('import.field.catalog', '目录协议 / 地址'), data.catalogProtocol + ' · ' + data.catalogEndpoint);
            row(PMK.t('import.field.network', '联网边界'), (data.networkHosts || []).join(', ') + ' · ' + data.redirectBoundary);
            row(PMK.t('import.field.revocations', '吊销清单'), data.revocationsUrl);
            row(PMK.t('import.field.update-proof', '更新连续性证明'), data.updateProofStatus);
            row(PMK.t('import.field.directory', '社区目录认证'), data.communityDirectoryStatus);
            (data.trustedKeys || []).forEach(function (key) {
                row(PMK.t('import.field.key', '完整密钥指纹') + ' · ' + key.keyId, key.fingerprintDisplay);
            });
            row(PMK.t('import.field.warning', '安全提示'), PMK.t(
                data.executableCodeWarningKey || 'import.executable-warning',
                '第三方插件与主程序运行在同一 JVM，当前没有代码沙箱；安装后可获得进程权限。'));
            if (data.repositoryIdConflict) row(PMK.t('import.field.conflict', '冲突'),
                PMK.t('import.conflict', '仓库 ID 与已有或内嵌仓库冲突，不能信任。'));
            result.hidden = false;
            confirm.checked = false;
            confirm.disabled = !!data.repositoryIdConflict;
            trust.disabled = true;
        }

        form.addEventListener('submit', function (event) {
            event.preventDefault(); preview = null; trust.disabled = true; confirm.disabled = true;
            PMK.api.previewRepository(input.value).then(function (data) {
                preview = data; render(data);
            }).catch(function (error) {
                result.textContent = error.message; result.hidden = false;
                PMK.toast(error.message, 'error');
            });
        });
        confirm.addEventListener('change', function () {
            trust.disabled = !preview || !confirm.checked || preview.repositoryIdConflict;
        });
        trust.addEventListener('click', function () {
            if (!preview || !confirm.checked) return;
            trust.disabled = true;
            PMK.api.trustRepository(preview).then(function () {
                PMK.toast(PMK.t('import.saved', '仓库信任快照已保存；重启后生效。'), 'ok');
                confirm.checked = false;
            }).catch(function (error) {
                PMK.toast(error.message, 'error'); trust.disabled = false;
            });
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(window);
