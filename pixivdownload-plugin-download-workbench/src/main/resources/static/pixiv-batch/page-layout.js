'use strict';

window.PixivBatchPageLayout = {
    bind(isAdmin, errorText, flushSettings) {
        const button = document.getElementById('batch-page-layout-switch');
        if (!button) return;
        button.hidden = !isAdmin;
        button.onclick = null;
        if (!isAdmin) return;
        button.onclick = async () => {
            if (button.disabled) return;
            button.disabled = true;
            try {
                await flushSettings();
                const response = await fetch('/api/batch/page', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({page: button.dataset.downloadPage})
                });
                if (!response.ok) throw new Error('HTTP ' + response.status);
                // 同页带锚点的 assign 可能只触发页内导航，必须重新请求服务端。
                if (window.location.pathname === '/pixiv-batch.html') window.location.reload();
                else window.location.assign('/pixiv-batch.html' + window.location.search + window.location.hash);
            } catch {
                window.PixivFeedback.toast({kind: 'error', message: errorText()});
            } finally {
                button.disabled = false;
            }
        };
        window.addEventListener('pageshow', event => {
            if (event.persisted) window.location.reload();
        });
    }
};
