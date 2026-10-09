'use strict';

function beginScheduleEdit(task) {
    const runtime = altScheduleSources();
    const lease = runtime.activationLease(task.sourceType || task.type);
    if (task.sourceAvailable === false || task.sourceActivationToken !== lease.activationToken
        || !Number.isSafeInteger(task.stateVersion) || task.stateVersion < 0) {
        throw new Error(bt('schedule.error.concurrent-change'));
    }
    const original = {settings: state.settings, filters: extraFilters,
        user: userState, search: searchState, series: seriesState, quick: quickState,
        drafts: new Map(modeDrafts)};
    for (const model of [userState, searchState, seriesState, quickState]) model.loading = false;
    const session = {task, lease, original, settings: {...state.settings},
        mode: null, element: null, saving: false};
    scheduleState.editing = session;
    extraFilters = {...extraFilters};
    userState = {...userState};
    searchState = {...searchState};
    seriesState = {...seriesState};
    quickState = {...quickState, loadSeq: quickState.loadSeq + 1,
        accountRevision: quickState.accountRevision + 1};
    modeDrafts.clear();
    try {
        const restored = withScheduleEditSettings(() => runtime.restoreTask(task, {}));
        lease.assertCurrent();
        if (!restored || !AB_MODES.some(mode => mode.id === restored.mode && mode.id !== 'schedule')) {
            throw new Error(bt('schedule.error.source-editor-unavailable'));
        }
        session.mode = restored.mode;
        session.quickSource = restored.quickSource || null;
        return session;
    } catch (error) {
        finishScheduleEdit();
        throw error;
    }
}

// 共享来源钩子同步读写草稿；后台手动下载始终使用原设置。
function withScheduleEditSettings(action) {
    const session = scheduleState.editing;
    const manual = state.settings;
    state.settings = session.settings;
    try {
        return action();
    } finally {
        session.settings = state.settings;
        state.settings = manual;
    }
}

function finishScheduleEdit(nextMode = 'schedule') {
    const session = scheduleState.editing;
    if (!session) return;
    const original = session.original;
    state.settings = original.settings;
    extraFilters = original.filters;
    userState = original.user;
    searchState = original.search;
    seriesState = original.series;
    quickState = original.quick;
    modeDrafts.clear();
    original.drafts.forEach((value, key) => modeDrafts.set(key, value));
    scheduleState.editing = null;
    const panel = document.getElementById('abModePanel');
    if (panel) panel.dataset.mode = '';
    switchMode(nextMode);
    renderStage();
}

async function cancelScheduleEdit(nextMode = 'schedule') {
    const session = scheduleState.editing;
    if (!session || session.saving) return;
    if (!await abConfirm('dialog.discard-draft', '放弃未保存的修改？')) return;
    if (scheduleState.editing === session) finishScheduleEdit(nextMode);
}

function mountScheduleEdit(panel) {
    const session = scheduleState.editing;
    if (!session || session.mode !== state.mode || !session.element) return;
    const anchor = panel.querySelector('.ab-mode-heading');
    if (anchor) anchor.after(session.element);
    else panel.prepend(session.element);
}

window.addEventListener('beforeunload', event => {
    if (!scheduleState.editing) return;
    event.preventDefault();
    event.returnValue = '';
});
