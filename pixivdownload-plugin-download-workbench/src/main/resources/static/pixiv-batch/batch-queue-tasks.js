'use strict';

// 执行快照只更新已绑定的手动队列项，不导入其他入口的任务。
(function () {
    const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    const terminal = phase => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(phase);
    let timer, request, pending, started = false, suspended = false;
    let epoch = '';
    let snapshotRevision = null;
    let recoveryAfter = '';
    const cancellations = new Map();
    const missingResults = new Set();

    function flush(changed) {
        if (!changed.length) return;
        updateStats();
        saveQueue();
        changed.forEach(item => renderQueue(item, true));
    }

    function bind(item, taskId) {
        if (!isAdmin || !ID.test(taskId || '') || !state.queue.includes(item)) return false;
        item.taskId = taskId;
        delete item.taskPhase;
        delete item.taskMissing;
        delete item.recoveryState;
        snapshotRevision = null;
        flush([item]);
        return true;
    }

    function apply(snapshot) {
        if (!snapshot || typeof snapshot.epoch !== 'string' || !Array.isArray(snapshot.tasks)
            || !Number.isSafeInteger(snapshot.revision) || snapshot.tasks.length > 4096)
            throw new Error('invalid download task snapshot');
        if (epoch === snapshot.epoch && snapshotRevision === snapshot.revision) return;
        const tasks = new Map(snapshot.tasks.map(task => [task.attempt && task.attempt.attemptId, task]));
        if (epoch !== snapshot.epoch) missingResults.clear();
        epoch = snapshot.epoch;
        const changed = [];
        for (const item of state.queue) {
            if (!ID.test(item.taskId || '')) continue;
            const task = tasks.get(item.taskId);
            if (task) {
                if (applyTask(item, task)) changed.push(item);
                continue;
            }
            if (item.taskMissing || !item.recoveryState) continue;
            // 查不到执行不能推断失败或重提；用户保存的终态也不随快照过期删除。
            setQueueRecoveryState(item, 'unknown');
            item.taskMissing = true;
            changed.push(item);
        }
        flush(changed);
        snapshotRevision = snapshot.revision;
    }

    function applyTask(item, task) {
        const recovering = !!item.recoveryState;
        const phaseChanged = item.taskPhase !== task.phase || item.taskMissing;
        if (!phaseChanged && item.recoveryState !== 'unknown') return false;
        item.taskPhase = task.phase;
        delete item.taskMissing;
        // 本页 worker 仍负责详细进度和下载后的收藏等结果。
        if (recovering) {
            if (terminal(task.phase)) {
                delete item.recoveryState;
                item.status = {COMPLETED: 'completed', FAILED: 'failed',
                    CANCELLED: 'paused'}[task.phase];
                item.endTime = task.updatedAt;
            } else {
                item.status = 'downloading';
                item.recoveryState = 'running';
            }
            item.statusMessageKey = 'batch:queue.task.phase.' + task.phase;
            item.lastMessage = '';
            item.lastMessageParts = null;
        }
        return true;
    }

    async function recoverMissing(controller) {
        const items = state.queue.filter(item => ID.test(item.taskId || '') && item.taskMissing
            && item.recoveryState);
        const ids = new Set(items.map(item => item.taskId));
        for (const id of missingResults) if (!ids.has(id)) missingResults.delete(id);
        // 整轮超时后从上次请求的后续项继续，避免慢回执长期饿死其他执行。
        const start = items.findIndex(item => item.taskId === recoveryAfter) + 1;
        for (let offset = 0; offset < items.length; offset++) {
            const item = items[(start + offset) % items.length];
            if (controller.signal.aborted || suspended || document.hidden || !isAdmin) return;
            const id = item.taskId;
            if (missingResults.has(id) || !state.queue.includes(item)) continue;
            recoveryAfter = id;
            try {
                const response = await fetch(BASE + '/api/download/tasks/' + id, {
                    credentials: 'same-origin', cache: 'no-store', signal: controller.signal
                });
                if (controller.signal.aborted || suspended || document.hidden || !isAdmin) return;
                if (response.status === 404) { missingResults.add(id); continue; }
                if (!response.ok) continue;
                const task = await response.json();
                if (controller.signal.aborted || suspended || document.hidden || !isAdmin) return;
                // 迟到回执只能更新仍在队列中的同一次执行，不能复活已移除项或覆盖新执行。
                if (!state.queue.includes(item) || item.taskId !== id || !item.recoveryState
                    || !task || task.attempt?.attemptId !== id || !terminal(task.phase)) continue;
                if (applyTask(item, task)) flush([item]);
            } catch (_) { /* 暂时不可查询时保留待确认，下一次轮询重试。 */ }
        }
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

    function refresh(recheck = true) {
        if (recheck) missingResults.clear();
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
            if (!controller.signal.aborted && !suspended && !document.hidden && isAdmin) {
                apply(snapshot);
                await recoverMissing(controller);
            }
        } catch (_) {
            // 断线保留记录，已有恢复提示负责待确认状态；不自动重新提交。
            if (!suspended && !document.hidden) {
                snapshotRevision = null;
                const changed = state.queue.filter(q => q.taskId && q.recoveryState
                    && q.status === 'downloading');
                changed.forEach(q => { setQueueRecoveryState(q, 'unknown'); q.taskMissing = true; });
                flush(changed);
            }
        } finally {
            clearTimeout(timeout);
            if (request === controller) request = null;
            if (!suspended && !document.hidden && isAdmin) timer = setTimeout(() => refresh(false), 2000);
        }
    }

    function start() {
        if (started || !isAdmin) return;
        started = true;
        try {
            if (storeGet('pixiv_batch_task_dismissed')) storeRemove('pixiv_batch_task_dismissed');
        } catch (_) { /* 存储不可用不影响手动队列的状态查询。 */ }
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

    window.PixivBatch.queueTasks = Object.freeze({bind, apply, canCancel, cancel, refresh, start});
})();
