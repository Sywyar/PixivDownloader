(function () {
    'use strict';

    var RETRY_SECONDS = 30;
    var client;
    var countdownEl;
    var countdown = RETRY_SECONDS;
    var timerId;

    function applyTranslations() {
        document.title = client.t('maintenance:page.title', '维护中');
        client.apply();
        if (countdownEl) {
            countdownEl.textContent = client.t('maintenance:page.auto-retry', '{0} 秒后自动重试', [String(countdown)]);
        }
    }

    function tick() {
        countdown--;
        if (countdown <= 0) {
            location.href = '/';
            return;
        }
        countdownEl.textContent = client.t('maintenance:page.auto-retry', '{0} 秒后自动重试', [String(countdown)]);
    }

    function startCountdown() {
        countdownEl = document.getElementById('autoRetryText');
        if (!countdownEl) return;
        countdownEl.textContent = client.t('maintenance:page.auto-retry', '{0} 秒后自动重试', [String(countdown)]);
        timerId = setInterval(tick, 1000);
    }

    async function main() {
        client = await PixivI18n.create({ namespaces: ['maintenance', 'common'] });

        await PixivLangSwitcher.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            i18n: client,
            onChange: function (nextClient) {
                client = nextClient;
                applyTranslations();
            }
        });

        PixivTheme.mount({
            mountPoint: document.getElementById('langSwitcherAnchor')
        });

        applyTranslations();
        startCountdown();
    }

    document.getElementById('btnRetry').addEventListener('click', function () {
        if (timerId) clearInterval(timerId);
        location.href = '/';
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', main);
    } else {
        main();
    }
})();
