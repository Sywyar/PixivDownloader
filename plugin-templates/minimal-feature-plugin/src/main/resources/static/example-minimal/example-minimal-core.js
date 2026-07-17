'use strict';

let exampleMinimalI18n = null;

function exampleMinimalSetStatus(messageKey, state) {
    const statusElement = document.getElementById('example-status-message');
    statusElement.dataset.state = state;
    statusElement.textContent = exampleMinimalI18n.t(messageKey, messageKey);
}

async function exampleMinimalRefreshStatus() {
    const refreshButton = document.getElementById('example-refresh');
    refreshButton.disabled = true;
    exampleMinimalSetStatus('status.loading', 'loading');

    try {
        const response = await fetch('/api/example-minimal/status', {credentials: 'same-origin'});
        if (!response.ok) {
            throw new Error('example-minimal.status-request-failed');
        }
        const payload = await response.json();
        if (payload.code !== 'example-minimal.ready') {
            throw new Error('example-minimal.unexpected-status');
        }
        exampleMinimalSetStatus(payload.messageKey || 'status.ready', 'ready');
    } catch (error) {
        exampleMinimalSetStatus('status.error', 'error');
    } finally {
        refreshButton.disabled = false;
    }
}
