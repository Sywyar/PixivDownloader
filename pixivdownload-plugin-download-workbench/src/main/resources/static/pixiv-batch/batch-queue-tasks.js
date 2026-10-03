'use strict';

// 执行快照只补充现有队列；保存和移除记录仍由队列负责。
(function () {
    const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    const terminal = phase => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(phase);
    const dismissedKey = 'pixiv_batch_task_dismissed';
    let timer, request, pending, started = false, suspended = false;
    let epoch = '', dismissed = new Set();
    let snapshotRevision = null;
    let savedDismissed = '';
    const cancellations = new Map();

    function flush(changed, structural) {
        if (!changed.length && !structural) return;
        updateStats();
        saveQueue();
        if (structural) {
            renderQueue();
            syncAllResultsQueueState();
        } else changed.forEach(item => renderQueue(item, true));
    }

    function bind(item, taskId) {
        if (!isAdmin || !ID.test(taskId || '') || !state.queue.includes(item)) return false;
        // HTTP 回执可能晚于快照到达：保留原队列项的元数据与进度，合并同一次执行。
        const duplicate = state.queue.find(q => q !== item && q.taskId === taskId && q.taskObserved);
        if (duplicate) state.queue.splice(state.queue.indexOf(duplicate), 1);
        if (item.taskId && item.taskId !== taskId) dismiss([item]);
        item.taskId = taskId;
        delete item.taskPhase;
        delete item.taskMissing;
        delete item.recoveryState;
        snapshotRevision = null;
        flush([item], !!duplicate);
        return true;
    }

    function saveDismissed() {
        const value = JSON.stringify({epoch, ids: Array.from(dismissed)});
        if (value === savedDismissed) return;
        savedDismissed = value;
        storeSet(dismissedKey, value);
    }

    function dismiss(items) {
        snapshotRevision = null;
        for (const item of items) if (ID.test(item.taskId || '')) dismissed.add(item.taskId);
        // 与宿主可查询的执行窗口同界；过期身份在下次成功快照中释放。
        dismissed = new Set(Array.from(dismissed).slice(-4096));
        saveDismissed();
    }

    function apply(snapshot) {
        if (!snapshot || typeof snapshot.epoch !== 'string' || !Array.isArray(snapshot.tasks)
            || !Number.isSafeInteger(snapshot.revision) || snapshot.tasks.length > 4096)
            throw new Error('invalid download task snapshot');
        if (epoch === snapshot.epoch && snapshotRevision === snapshot.revision) return;
        const tasks = new Map(snapshot.tasks.map(task => [task.attempt && task.attempt.attemptId, task]));
        if (epoch !== snapshot.epoch) dismissed.clear();
        epoch = snapshot.epoch;
        for (const id of dismissed) if (!tasks.has(id)) dismissed.delete(id);
        saveDismissed();
        const items = new Map(state.queue.filter(q => q.taskId).map(q => [q.taskId, q]));
        const changed = [];
        let structural = false;
        for (const [id, task] of tasks) {
            if (!ID.test(id || '') || dismissed.has(id)) continue;
            let item = items.get(id);
            if (!item) {
                item = {id: 'task:' + id, taskId: id, taskObserved: true,
                    source: 'task', kind: task.queueType || task.attempt.workType, workId: task.attempt.workId,
                    title: task.title || task.attempt.workId, status: 'downloading'};
                state.queue.push(item);
                structural = true;
            }
            const recovering = !!item.recoveryState;
            const phaseChanged = item.taskPhase !== task.phase || item.taskMissing
                || (item.taskObserved && task.queueType && item.kind !== task.queueType);
            if (!phaseChanged && item.recoveryState !== 'unknown') continue;
            if (item.taskObserved) {
                if (task.queueType) item.kind = task.queueType;
                item.title = task.title || task.attempt.workId;
            }
            item.taskPhase = task.phase;
            delete item.taskMissing;
            // 本页 worker 仍负责详细进度和下载后的收藏等结果。
            if (item.taskObserved || recovering) {
                if (terminal(task.phase)) {
                    delete item.recoveryState;
                    item.status = {COMPLETED: 'completed', FAILED: 'failed',
                        CANCELLED: item.taskObserved ? 'cancelled' : 'paused'}[task.phase];
                    item.endTime = task.updatedAt;
                } else {
                    item.status = 'downloading';
                    if (recovering) item.recoveryState = 'running';
                }
                item.statusMessageKey = 'batch:queue.task.phase.' + task.phase;
                item.lastMessage = '';
                item.lastMessageParts = null;
            }
            changed.push(item);
        }
        for (const item of items.values()) {
            if (tasks.has(item.taskId) || terminal(item.taskPhase) || item.taskMissing
                || (!item.taskObserved && !item.recoveryState)) continue;
            // 查不到执行不能推断失败或重提；用户保存的终态也不随快照过期删除。
            if (item.status === 'downloading' || item.recoveryState) {
                setQueueRecoveryState(item, 'unknown');
                item.taskMissing = true;
                changed.push(item);
            }
        }
        flush(changed, structural);
        snapshotRevision = snapshot.revision;
    }

    function canCancel(item) {
        return !!(isAdmin && item && ID.test(item.taskId || '') && !item.taskMissing
            && (!item.taskPhase || ['QUEUED', 'STARTED'].includes(item.taskPhase))
            && item.status === 'downloading');
    }

    async function cancel(item) {
        if (!canCancel(item)) throw new Error('download task cancellation unavailable');
        const id = item.taskId;
        if (cancellations.has(id)) return cancellations.get(id).promise;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 10000);
        const promise = (async () => {
            const response = await fetch(BASE + '/api/download/tasks/' + id + '/cancel', {
                method: 'POST', credentials: 'same-origin', cache: 'no-store', signal: controller.signal
            });
            if (!response.ok) throw new Error('download task cancellation HTTP ' + response.status);
        })().finally(() => { clearTimeout(timeout); cancellations.delete(id); });
        cancellations.set(id, {controller, promise});
        return promise;
    }

    function refresh() {
        if (pending) return pending;
        pending = poll().finally(() => { pending = null; });
        return pending;
    }

    async function poll() {
        if (!isAdmin || suspended || document.hidden || request) return;
        clearTimeout(timer);
        const controller = new AbortController();
        request = controller;
        const timeout = setTimeout(() => controller.abort(), 10000);
        try {
            const response = await fetch(BASE + '/api/download/tasks', {
                credentials: 'same-origin', cache: 'no-store', signal: controller.signal
            });
            if (!response.ok) throw new Error('download task snapshot HTTP ' + response.status);
            const snapshot = await response.json();
            if (!controller.signal.aborted && !suspended && isAdmin) apply(snapshot);
        } catch (_) {
            // 断线保留记录，已有恢复提示负责待确认状态；不自动重新提交。
            if (!suspended && !document.hidden) {
                snapshotRevision = null;
                const changed = state.queue.filter(q => q.taskId && (q.taskObserved || q.recoveryState)
                    && q.status === 'downloading');
                changed.forEach(q => { setQueueRecoveryState(q, 'unknown'); q.taskMissing = true; });
                flush(changed, false);
            }
        } finally {
            clearTimeout(timeout);
            if (request === controller) request = null;
            if (!suspended && !document.hidden && isAdmin) timer = setTimeout(refresh, 2000);
        }
    }

    function start() {
        if (started || !isAdmin) return;
        started = true;
        try {
            const saved = JSON.parse(storeGet(dismissedKey) || '{}');
            epoch = typeof saved.epoch === 'string' ? saved.epoch : '';
            dismissed = new Set(Array.isArray(saved.ids) ? saved.ids.filter(id => ID.test(id)).slice(-4096) : []);
        } catch (_) { /* 损坏的移除标记不影响队列恢复 */ }
        const pause = () => {
            clearTimeout(timer);
            if (request) request.abort();
            for (const value of cancellations.values()) value.controller.abort();
        };
        document.addEventListener('visibilitychange', () => document.hidden ? pause() : void refresh());
        window.addEventListener('pagehide', () => { suspended = true; pause(); });
        window.addEventListener('pageshow', () => { suspended = false; void refresh(); });
        void refresh();
    }

    window.PixivBatch.queueTasks = Object.freeze({bind, dismiss, apply, canCancel, cancel, refresh, start});
})();
