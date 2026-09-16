'use strict';

let pathActionBatch = null;
let pathActionDialogs = Promise.resolve();

function beginPathActionBatch() {
    endPathActionBatch();
    pathActionBatch = {controller: new AbortController(), action: null};
}

function endPathActionBatch() {
    if (pathActionBatch) pathActionBatch.controller.abort();
    pathActionBatch = null;
}

function pathActionError(code) {
    const error = new Error(code);
    error.code = code;
    return error;
}

async function submitWithPathAction(url, payload, invocation, headers) {
    if (!pathActionBatch) beginPathActionBatch();
    const batch = pathActionBatch;
    const controller = new AbortController();
    const abort = () => controller.abort();
    const signal = invocation && invocation.signal;
    batch.controller.signal.addEventListener('abort', abort, {once: true});
    if (signal) signal.addEventListener('abort', abort, {once: true});
    function active() {
        if (invocation) invocation.assertActive();
        if (batch !== pathActionBatch || batch.controller.signal.aborted || (signal && signal.aborted)) {
            throw pathActionError('DOWNLOAD_PATH_WAIT');
        }
    }
    payload.other = payload.other || {};
    payload.other.pathOverflowAction = batch.action || state.settings.pathOverflowAction || 'ASK';
    try {
        while (true) {
            active();
            const res = await fetch(url, {
                method: 'POST', headers: {...headers, 'Content-Type': 'application/json'}, credentials: 'same-origin',
                signal: controller.signal, body: JSON.stringify(payload)
            });
            const data = await res.json();
            active();
            if (data.code === 'DOWNLOAD_PATH_CANCELLED') throw pathActionError(data.code);
            if (data.code !== 'DOWNLOAD_PATH_ACTION_REQUIRED') return {res, data};
            // 抓取票据为一次性，确认重试只使用服务端重新签发且维持身份绑定的票据。
            if (Object.prototype.hasOwnProperty.call(payload, 'fetchToken')) payload.fetchToken = data.retryToken || null;
            if (!isAdmin || !data.pathProblem || !window.PixivFeedback || !window.PixivFeedback.choose) {
                throw pathActionError('DOWNLOAD_PATH_WAIT');
            }
            const problem = data.pathProblem;
            const allowed = value => value === 'CANCEL'
                || (value === 'TRUNCATE' && problem.truncatedPath)
                || (value === 'DEFAULT_NAME' && problem.defaultPath);
            const decide = async () => {
                active();
                if (batch.action && allowed(batch.action)) return batch.action;
                const choice = await window.PixivFeedback.choose({
                    title: bt('batch:path.overflow.title', null),
                    message: bt('batch:path.overflow.message', null, {id: payload.artworkId || payload.novelId,
                        path: problem.originalPath}),
                    choices: [
                        {value: 'TRUNCATE', label: bt('batch:path.overflow.truncate', null)
                            + (problem.truncatedPath ? '\n' + problem.truncatedPath : ''), disabled: !problem.truncatedPath},
                        {value: 'DEFAULT_NAME', label: bt('batch:path.overflow.default', null)
                            + (problem.defaultPath ? '\n' + problem.defaultPath : ''), disabled: !problem.defaultPath},
                        {value: 'CANCEL', label: bt('batch:path.overflow.cancel', null)}
                    ],
                    rememberLabel: bt('batch:path.overflow.remember', null),
                    confirmLabel: bt('batch:path.overflow.continue', null),
                    cancelLabel: bt('batch:path.overflow.wait', null),
                    signal: controller.signal
                });
                active();
                if (!choice) throw pathActionError('DOWNLOAD_PATH_WAIT');
                if (choice.remember) batch.action = choice.value;
                return choice.value;
            };
            const decision = pathActionDialogs.then(decide, decide);
            pathActionDialogs = decision.catch(() => {});
            const action = await decision;
            if (action === 'CANCEL') throw pathActionError('DOWNLOAD_PATH_CANCELLED');
            payload.other.pathOverflowAction = action;
        }
    } finally {
        batch.controller.signal.removeEventListener('abort', abort);
        if (signal) signal.removeEventListener('abort', abort);
    }
}

function handlePathActionError(item, error) {
    if (!error || !['DOWNLOAD_PATH_CANCELLED', 'DOWNLOAD_PATH_WAIT'].includes(error.code)) return false;
    const cancelled = error.code === 'DOWNLOAD_PATH_CANCELLED';
    item.status = cancelled ? 'skipped' : 'paused';
    item.statusMessageKey = cancelled ? 'batch:path.overflow.cancelled' : 'batch:path.overflow.waiting';
    item.lastMessage = '';
    return true;
}

async function resolveSchedulePathActions(task) {
    if (!task || task.suspendReason !== 'USER_ACTION_REQUIRED') return true;
    const pendingRes = await fetch(`${BASE}/api/schedule/tasks/${task.id}/pending`, {credentials: 'same-origin'});
    if (!pendingRes.ok) throw new Error('pending');
    const pending = await pendingRes.json();
    for (const work of pending) {
        if (work.reasonCode !== 'DOWNLOAD_PATH_ACTION_REQUIRED') continue;
        const detail = JSON.parse(work.reasonDetailJson || '{}');
        if (detail.userAction) continue;
        const choice = await window.PixivFeedback.choose({
            title: bt('batch:path.overflow.title', null),
            message: bt('batch:path.overflow.scheduled', null, {id: work.workId}),
            choices: [
                {value: 'TRUNCATE', label: bt('batch:path.overflow.truncate', null)},
                {value: 'DEFAULT_NAME', label: bt('batch:path.overflow.default', null)},
                {value: 'CANCEL', label: bt('batch:path.overflow.cancel', null)}
            ],
            rememberLabel: bt('batch:path.overflow.remember', null),
            confirmLabel: bt('batch:path.overflow.continue', null),
            cancelLabel: bt('batch:path.overflow.wait', null)
        });
        if (!choice) return false;
        const resolved = await fetch(`${BASE}/api/schedule/tasks/${task.id}/pending/resolve`, {
            method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({expectedStateVersion: task.stateVersion, workType: work.workType,
                workId: work.workId, userAction: choice.value, rememberForRun: choice.remember})
        });
        if (!resolved.ok) throw new Error('resolve');
        task = await resolved.json();
        if (choice.remember) break;
    }
    return true;
}

window.PixivBatch.pathActions = {submit: submitWithPathAction, handleError: handlePathActionError};
