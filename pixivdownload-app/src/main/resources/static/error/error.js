/* PixivDownloader 错误状态页共享脚本：i18n 流水线 + 语言 / 主题切换 + 重试。
 * 页面经 data-status 声明自身状态码，文案键统一为 status:<code>.<field>。
 * i18n 不可用时页面保留 HTML 内的源语言兜底文案，主题切换仍可挂载。 */
(function () {
    'use strict';

    var status = document.body.getAttribute('data-status') || '500';
    var displayedStatus = resolveDisplayedStatus(status);
    var client = null;

    function resolveDisplayedStatus(declaredStatus) {
        if (!/^[45]xx$/.test(declaredStatus)
                || !window.performance
                || typeof window.performance.getEntriesByType !== 'function') {
            return declaredStatus;
        }
        var navigation = window.performance.getEntriesByType('navigation')[0];
        var responseStatus = Number(navigation && navigation.responseStatus);
        return Number.isInteger(responseStatus)
                && String(responseStatus).charAt(0) === declaredStatus.charAt(0)
                ? String(responseStatus)
                : declaredStatus;
    }

    function applyStatusProjection() {
        document.body.setAttribute('data-actual-status', displayedStatus);
        document.querySelectorAll('[data-error-status-code]').forEach(function (element) {
            element.textContent = displayedStatus;
        });
        document.querySelectorAll('[data-error-status-text]').forEach(function (element) {
            element.textContent = element.textContent.replace(status, displayedStatus);
        });
        if (displayedStatus !== status && document.title.indexOf(status) === 0) {
            document.title = displayedStatus + document.title.substring(status.length);
        }
    }

    function t(key, fallback) {
        return client ? client.t('status:' + key, fallback) : fallback;
    }

    function applyTranslations() {
        if (!client) return;
        document.title = client.t('status:' + status + '.doc-title', document.title);
        client.apply();
        applyStatusProjection();
    }

    async function main() {
        try {
            client = await PixivI18n.create({ namespaces: ['status', 'common'] });
        } catch (e) {
            client = null;
        }

        var anchor = document.getElementById('errControls');
        if (anchor && client && window.PixivLangSwitcher) {
            await PixivLangSwitcher.mount({
                mountPoint: anchor,
                i18n: client,
                variant: 'error',
                onChange: function (nextClient) {
                    client = nextClient;
                    applyTranslations();
                }
            });
        }
        if (anchor && window.PixivTheme) {
            PixivTheme.mount({
                mountPoint: anchor,
                variant: 'error',
                titleDark: t('theme.to-light', 'Switch to light mode'),
                titleLight: t('theme.to-dark', 'Switch to dark mode')
            });
        }

        applyTranslations();
    }

    applyStatusProjection();

    var retryButton = document.getElementById('errRetry');
    if (retryButton) {
        retryButton.addEventListener('click', function () {
            location.reload();
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', main);
    } else {
        main();
    }
})();
