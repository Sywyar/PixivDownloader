'use strict';

const QUEUE_RECOVERY_POLL_MS = 5000;
const QUEUE_RECOVERY_TIMEOUT_MS = 10000;
let queueRecoveryTimer = null;
let queueRecoveryRequest = null;
let queueRecoveryPromise = null;
let queueRecoverySuspended = false;

function restoreInterruptedQueueItem(item) {
    if (appMode !== 'solo' || !isAdmin) return false;
    if (item.status !== 'downloading' && !item.recoveryState) return false;
    setQueueRecoveryState(item, 'unknown');
    return true;
}

function setQueueRecoveryState(item, recoveryState) {
    item.recoveryState = recoveryState;
    item.status = recoveryState === 'running' ? 'downloading' : 'paused';
    item.statusMessageKey = 'batch:queue.recovery.' + recoveryState;
    item.lastMessage = '';
    item.lastMessageParts = null;
    item.liveStatus = null;
}

function renderQueueRecovery() {
    const host = document.getElementById('queue-recovery');
    if (!host) return;
    host.hidden = appMode !== 'solo' || !isAdmin;
    if (host.hidden) return;
    if (!host.firstChild) {
        const help = document.createElement('details');
        help.className = 'queue-recovery-help';
        const summary = document.createElement('summary');
        summary.className = 'layout-touch';
        summary.dataset.recoverySummary = '';
        help.appendChild(summary);
        const notice = document.createElement('p');
        notice.dataset.recoveryNotice = '';
        help.appendChild(notice);
        host.appendChild(help);
        const message = document.createElement('p');
        message.dataset.recoveryMessage = '';
        message.setAttribute('role', 'status');
        host.appendChild(message);
        const actions = document.createElement('div');
        actions.dataset.recoveryActions = '';
        host.appendChild(actions);
        for (const [action, handler] of [
            ['check', reconcileRestoredQueue],
            ['retry', retryUnconfirmedQueueItems]
        ]) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = host.dataset.buttonClass || 'btn layout-touch';
            button.dataset.recoveryAction = action;
            button.addEventListener('click', handler);
            actions.appendChild(button);
        }
    }
    host.querySelector('[data-recovery-summary]').textContent = bt('batch:queue.recovery.keep-open');
    host.querySelector('[data-recovery-notice]').textContent = bt('batch:queue.recovery.notice');
    const pending = state.queue.some(item => item.recoveryState
        && (!item.taskId || item.recoveryState === 'unknown'));
    const message = host.querySelector('[data-recovery-message]');
    message.hidden = !pending;
    message.textContent = bt('batch:queue.recovery.waiting');
    host.querySelector('[data-recovery-actions]').hidden = !pending;
    for (const action of ['check', 'retry']) {
        const button = host.querySelector('[data-recovery-action="' + action + '"]');
        button.textContent = bt('batch:queue.recovery.' + action);
        button.disabled = !!queueRecoveryPromise || (action === 'retry'
            && !state.queue.some(item => !item.taskObserved && item.recoveryState === 'unknown'));
    }
}

function applyQueueRecoveryResult(item, result) {
    if (!result || !['running', 'completed', 'failed', 'cancelled'].includes(result.status)) {
        setQueueRecoveryState(item, 'unknown');
        return;
    }
    for (const field of ['totalImages', 'downloadedCount']) {
        if (Number.isSafeInteger(result[field]) && result[field] >= 0) item[field] = result[field];
    }
    if (result.status === 'running') {
        setQueueRecoveryState(item, 'running');
        item.imageProgress = result.imageProgress || null;
        item.ugoiraProgress = result.ugoiraProgress || null;
        return;
    }
    delete item.recoveryState;
    item.imageProgress = null;
    item.ugoiraProgress = null;
    item.status = result.status === 'cancelled' ? 'paused' : result.status;
    item.statusMessageKey = 'batch:queue.recovery.' + result.status;
    item.lastMessage = '';
    item.lastMessageParts = null;
    item.endTime = new Date().toISOString();
}

async function queryQueueRecovery(item) {
    const runtime = window.PixivBatch.queueTypes;
    const behavior = runtime && runtime.get(item.kind || 'illust');
    if (!behavior || typeof behavior.queryDownloadStatus !== 'function') return null;
    const controller = new AbortController();
    queueRecoveryRequest = controller;
    const timeout = setTimeout(() => controller.abort(), QUEUE_RECOVERY_TIMEOUT_MS);
    try {
        const result = await behavior.queryDownloadStatus(
            Object.freeze({id: item.id, kind: item.kind}), controller.signal);
        if (controller.signal.aborted || runtime.get(item.kind || 'illust') !== behavior) return null;
        return result;
    } finally {
        clearTimeout(timeout);
        if (queueRecoveryRequest === controller) queueRecoveryRequest = null;
    }
}

function reconcileRestoredQueue() {
    if (appMode !== 'solo' || !isAdmin || queueRecoverySuspended) return Promise.resolve();
    if (queueRecoveryPromise) return queueRecoveryPromise;
    if (!state.queue.some(item => item.recoveryState)) return Promise.resolve();
    clearTimeout(queueRecoveryTimer);
    queueRecoveryTimer = null;
    queueRecoveryPromise = (async () => {
        if (state.queue.some(item => item.taskId && item.recoveryState)) {
            await window.PixivBatch.queueTasks.refresh();
        }
        // 串行查询避免大队列同时创建请求；只对仍在原队列中的同一个对象回写。
        for (const item of state.queue.filter(value => value.recoveryState && !value.taskId)) {
            if (queueRecoverySuspended) break;
            if (!state.queue.includes(item) || !item.recoveryState) continue;
            let result = null;
            try { result = await queryQueueRecovery(item); } catch (_) { /* 查询失败保留待确认 */ }
            if (queueRecoverySuspended) break;
            if (!state.queue.includes(item) || !item.recoveryState) continue;
            applyQueueRecoveryResult(item, result);
        }
    })().finally(() => {
        queueRecoveryPromise = null;
        if (queueRecoverySuspended) return;
        updateStats();
        saveQueue();
        renderQueue();
        if (state.queue.some(item => !item.taskId && item.recoveryState === 'running')) {
            queueRecoveryTimer = setTimeout(reconcileRestoredQueue, QUEUE_RECOVERY_POLL_MS);
        }
    });
    renderQueueRecovery();
    return queueRecoveryPromise;
}

async function retryUnconfirmedQueueItems() {
    if (appMode !== 'solo' || !isAdmin || queueRecoveryPromise || queueRecoverySuspended) return;
    await reconcileRestoredQueue();
    const items = state.queue.filter(item => !item.taskObserved && item.recoveryState === 'unknown');
    if (!items.length) return;
    if (!window.PixivFeedback || !await window.PixivFeedback.confirm({
        title: bt('batch:dialog.title.confirm'),
        message: bt('batch:queue.recovery.confirm-retry'),
        confirmLabel: bt('common:button.confirm'),
        cancelLabel: bt('common:button.cancel'),
        danger: true
    })) return;
    // 确认框打开时后端可能已经开始执行；提交前再核对，运行中的任务仍不重试。
    await reconcileRestoredQueue();
    if (queueRecoverySuspended) return;
    for (const item of items) {
        if (!state.queue.includes(item) || item.recoveryState !== 'unknown') continue;
        delete item.recoveryState;
        delete item.taskId;
        delete item.taskPhase;
        delete item.taskMissing;
        item.status = 'paused';
        item.statusMessageKey = 'batch:queue.recovery.ready';
    }
    saveQueue();
    renderQueue();
    updateStats();
}

function queueNeedsLeaveWarning() {
    return appMode === 'solo' && isAdmin && state.queue.some(item => item.recoveryState
        || item.status === 'downloading'
        || (state.isRunning && !state.isPaused && item.status === 'pending'));
}

function initQueueRecovery() {
    renderQueueRecovery();
    window.PixivBatch.queueTasks?.start();
    void reconcileRestoredQueue();
    window.addEventListener('beforeunload', event => {
        if (!queueNeedsLeaveWarning()) return;
        event.preventDefault();
        event.returnValue = '';
    });
    window.addEventListener('pagehide', () => {
        queueRecoverySuspended = true;
        clearTimeout(queueRecoveryTimer);
        if (queueRecoveryRequest) queueRecoveryRequest.abort();
    });
    window.addEventListener('pageshow', () => {
        if (!queueRecoverySuspended) return;
        Promise.resolve(queueRecoveryPromise).then(() => {
            queueRecoverySuspended = false;
            return reconcileRestoredQueue();
        });
    });
}
