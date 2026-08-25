'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const scheduleRoot = path.join(
    __dirname, '../../main/resources/static/pixiv-batch/modes');
const source = [
    'schedule-core.js', 'schedule-editor.js', 'schedule-view.js',
    'schedule-queue.js', 'schedule.js'
].map(file => fs.readFileSync(path.join(scheduleRoot, file), 'utf8')).join('\n') + `
window.__setScheduleEditing = function (id, tasks) {
    scheduleEditingId = id;
    scheduleTasksCache = tasks || [];
    if (id == null) {
        scheduleEditingToken = null;
        return;
    }
    const task = scheduleTasksCache.find(value => Number(value.id) === Number(id)) || {};
    scheduleEditingToken = Object.freeze({
        taskId: Number(id),
        stateVersion: Number.isSafeInteger(Number(task.stateVersion)) ? Number(task.stateVersion) : 0,
        sourceType: task.sourceType || 'source-a',
        activationToken: task.sourceActivationToken || 'token-a'
    });
};
window.__replaceScheduleTasks = function (tasks) {
    scheduleTasksCache = tasks || [];
};
window.__scheduleTaskCredentialUi = scheduleTaskCredentialUi;
window.__scheduleStatusLight = scheduleStatusLight;
window.__scheduleItemToQueue = scheduleItemToQueue;
window.__localizeScheduleQueueItem = localizeScheduleQueueItem;
window.__scheduleQueueItemKey = scheduleQueueItemKey;
window.__mergeScheduleQueueModel = mergeScheduleQueueModel;
window.__setScheduleQueueModel = function (id, items) {
    scheduleQueueModels[Number(id)] = items || [];
};
window.__getScheduleQueueModel = function (id) {
    return scheduleQueueModels[Number(id)] || [];
};
window.__loadScheduleQueueModel = getScheduleQueueModel;
window.__clearScheduleQueueModel = function (id) {
    delete scheduleQueueModels[Number(id)];
};
window.__readScheduleQueueCache = readScheduleQueueCache;
window.__writeScheduleQueueCache = writeScheduleQueueCache;
window.__applyScheduleQueueSse = applyScheduleQueueSse;
window.__subscribeScheduleQueueSse = subscribeScheduleQueueSse;
window.__unsubscribeScheduleQueueSse = unsubscribeScheduleQueueSse;
window.__pendingReasonText = pendingReasonText;
window.__renderScheduleTaskCard = renderScheduleTaskCard;
window.__renderScheduleSnapshotBody = renderScheduleSnapshotBody;
window.__deleteScheduleTask = deleteScheduleTask;
window.__applyScheduleOverrides = applyScheduleOverrides;
window.__fillScheduleCookieFromSaved = fillScheduleCookieFromSaved;
window.__scheduleTaskCredentialPolicy = scheduleTaskCredentialPolicy;
window.__scheduleStatusLabel = scheduleStatusLabel;
window.__setScheduleCredentialPolicyGroups = function (groups) {
    scheduleCredentialPolicyGroupsCache = groups || [];
};
window.__renderCredentialPolicyBanners = renderCredentialPolicyBanners;
window.__applyScheduleCredentialPolicyAction = applyScheduleCredentialPolicyAction;
window.__showScheduleOverrideModal = showScheduleOverrideModal;
window.__saveScheduleOverride = saveScheduleOverride;
window.__updateScheduleFetchLimitVisibility = updateScheduleFetchLimitVisibility;
window.__scheduleSourceContext = scheduleSourceContext;`;

function deferred() {
    let resolve;
    const promise = new Promise(value => { resolve = value; });
    return {promise, resolve};
}

function taskCredentialPolicy(overrides) {
    return Object.assign({
        ownerPluginId: 'example.plugin',
        policyId: 'policy-a',
        accountKey: 'account-a',
        bound: false,
        available: true,
        publicationId: 7,
        statusCode: null,
        acknowledgedEventTime: null
    }, overrides || {});
}

function harness(options) {
    const config = options || {};
    const storage = new Map(Object.entries(config.storageEntries || {}));
    const elements = new Map();
    const element = (id, values) => {
        const value = Object.assign({
            id, value: '', checked: false, textContent: '', hidden: false,
            style: {}, dataset: {}, classList: {add() {}, remove() {}, toggle() {}}
        }, values || {});
        elements.set(id, value);
        return value;
    };
    element('sch-name', {value: 'task'});
    element('sch-trigger', {value: 'interval'});
    element('sch-interval', {value: '30'});
    element('sch-cron', {value: ''});
    element('sch-proxy-enabled', {checked: false});
    element('sch-proxy', {value: ''});
    element('sch-cookie-enabled', {checked: !!config.cookieChecked});
    element('sch-cookie', {value: config.cookieValue || ''});
    element('schedule-override-modal', {hidden: true});
    element('schedule-override-title');
    element('schedule-override-intro');
    element('sch-ov-status');
    element('sch-ov-proxy-enabled');
    element('sch-ov-proxy');
    element('sch-ov-proxy-row');
    element('sch-ov-cookie-enabled');
    element('sch-ov-cookie');
    element('sch-ov-cookie-row');
    element('save-as-schedule-card', {style: {display: ''}});
    element('sch-fetch-limit-row');
    element('sch-fetch-limit-hint-watermark');
    element('sch-fetch-limit-hint-per-run');
    element('single-import-textarea', {value: config.singleImportValue || ''});
    const status = element('sch-form-status');

    let current = true;
    let fetchCount = 0;
    const requests = [];
    const confirmCalls = [];
    const promptCalls = [];
    const credentialCalls = [];
    const switchedModes = [];
    const sseListeners = new Map();
    const lease = {
        activationToken: 'token-a',
        signal: new AbortController().signal,
        isCurrent: () => current,
        assertCurrent() {
            if (!current) throw new Error('schedule source handler became stale');
        }
    };
    const runtime = {
        captureForMode() {
            if (config.captureError) throw config.captureError;
            return {
                sourceType: 'source-a', activationToken: 'token-a',
                params: {fetchLimit: 0}, fetchLimitMode: config.fetchLimitMode || null,
                fetchLimitPresentation: config.fetchLimitPresentation || null
            };
        },
        previewForMode() {
            return config.sourcePreview || null;
        },
        activationLease: () => lease,
        descriptor: () => ({
            ownerPluginId: config.ownerPluginId || 'example.plugin',
            publicationId: config.publicationId || 7,
            presentation: {displayNamespace: config.descriptorNamespace || 'example'}
        }),
        isAvailable: () => config.sourceActive !== false,
        credentialContribution() {
            if (Object.prototype.hasOwnProperty.call(config, 'credentialContributionResult')) {
                const value = config.credentialContributionResult;
                if (value && typeof value.then === 'function') {
                    Promise.resolve(value).catch(() => {});
                    return null;
                }
                return value;
            }
            return {
                supportsProxy: config.supportsProxy === true,
                supportsCredential: config.supportsCredential === true,
                presentation: config.credentialPresentation || null
            };
        },
        validateCredential(sourceType, credential, context) {
            credentialCalls.push({method: 'validateCredential', sourceType, credential, context});
            return config.validation ? config.validation.promise : Promise.resolve(null);
        },
        bindCredential(sourceType, taskId, credential, context) {
            credentialCalls.push({method: 'bindCredential', sourceType, taskId, credential, context});
            return Promise.resolve(config.bindCredentialResult || {ok: true, status: 'bound'});
        },
        bindSavedCredential(sourceType, taskId, context) {
            credentialCalls.push({method: 'bindSavedCredential', sourceType, taskId, context});
            return Promise.resolve(config.bindSavedCredentialResult || {ok: true, status: 'bound'});
        },
        revokeCredential(sourceType, taskId, context) {
            credentialCalls.push({method: 'revokeCredential', sourceType, taskId, context});
            return Promise.resolve(config.revokeCredentialResult || {ok: true, status: 'revoked'});
        },
        credentialTaskPresentation(sourceType, task, context) {
            if (typeof config.credentialTaskPresentation === 'function') {
                return config.credentialTaskPresentation(sourceType, task, context);
            }
            return config.credentialTaskPresentation || null;
        },
        credentialPolicyGroups(tasks, context) {
            credentialCalls.push({method: 'credentialPolicyGroups', tasks, context});
            return config.credentialPolicyGroups || [];
        },
        applyCredentialPolicyAction(sourceType, request, context) {
            credentialCalls.push({method: 'applyCredentialPolicyAction', sourceType, request, context});
            return Promise.resolve(config.credentialPolicyActionResult || {ok: true, status: 'applied'});
        }
    };
    const sandbox = {
        window: {
            PixivBatch: {
                scheduleSources: runtime,
                queueTypes: config.queueTypes,
                modes: {}
            }
        },
        document: {
            visibilityState: 'visible', body: {classList: {add() {}, remove() {}}},
            getElementById: id => elements.get(id) || null,
            querySelectorAll: () => [],
            addEventListener() {}
        },
        state: {mode: 'user', queue: [], currentItemId: null, sseListeners: {}},
        QUICK_FETCH_MODE: 'quick-fetch',
        isAdmin: true,
        appMode: 'solo',
        BASE: '',
        STATUS_COLORS: {info: 'info', error: 'error', success: 'success'},
        bt: (key, fallback, vars) => {
            let value = Object.prototype.hasOwnProperty.call(config.translations || {}, key)
                ? config.translations[key] : fallback;
            Object.entries(vars || {}).forEach(([name, replacement]) => {
                value = String(value).replaceAll(`{${name}}`, String(replacement));
            });
            return value;
        },
        pageI18n: {
            t: (key, fallback) => Object.prototype.hasOwnProperty.call(
                config.pluginTranslations || {}, key)
                ? config.pluginTranslations[key] : fallback
        },
        esc: value => String(value == null ? '' : value),
        escHtml: value => String(value == null ? '' : value),
        uiConfirmKey(key, fallback, vars) {
            confirmCalls.push({key, fallback, vars});
            return config.confirm ? config.confirm.promise : Promise.resolve(true);
        },
        switchMode(mode) {
            switchedModes.push(mode);
            sandbox.state.mode = mode;
        },
        uiPromptKey(key, fallback, value, options) {
            promptCalls.push({key, fallback, value, options});
            return config.prompt ? config.prompt.promise : Promise.resolve(value);
        },
        mergeUgoiraProgress(current, incoming) {
            return incoming || current || null;
        },
        ensureSharedSSE() {},
        addSSEListener(workId, listener) {
            const key = String(workId);
            const listeners = sseListeners.get(key) || [];
            listeners.push(listener);
            sseListeners.set(key, listeners);
        },
        removeSSEListener(workId, listener) {
            const key = String(workId);
            const listeners = sseListeners.get(key) || [];
            const index = listeners.indexOf(listener);
            if (index >= 0) listeners.splice(index, 1);
            if (listeners.length) sseListeners.set(key, listeners);
            else sseListeners.delete(key);
        },
        storeGet(key) {
            return storage.has(String(key)) ? storage.get(String(key)) : null;
        },
        storeSet(key, value) {
            storage.set(String(key), String(value));
        },
        storeRemove(key) {
            storage.delete(String(key));
        },
        fetch(url, init) {
            fetchCount++;
            requests.push({url, init: init || {}});
            if (config.response) return Promise.resolve(config.response);
            throw new Error('unexpected fetch');
        },
        setInterval: () => 1,
        clearInterval() {},
        setTimeout,
        clearTimeout,
        AbortController,
        Map,
        Set,
        Promise,
        console: {warn() {}, log() {}, error() {}}
    };
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox, {filename: 'schedule.js'});
    return {
        submit: sandbox.window.PixivBatch.modes.schedule.submitScheduleTask,
        setEditing: sandbox.window.__setScheduleEditing,
        replaceTasks: sandbox.window.__replaceScheduleTasks,
        credentialUi: sandbox.window.__scheduleTaskCredentialUi,
        statusLight: sandbox.window.__scheduleStatusLight,
        queueItem: sandbox.window.__scheduleItemToQueue,
        localizeQueueItem: sandbox.window.__localizeScheduleQueueItem,
        queueKey: sandbox.window.__scheduleQueueItemKey,
        mergeQueue: sandbox.window.__mergeScheduleQueueModel,
        setQueueModel: sandbox.window.__setScheduleQueueModel,
        getQueueModel: sandbox.window.__getScheduleQueueModel,
        loadQueueModel: sandbox.window.__loadScheduleQueueModel,
        clearQueueModel: sandbox.window.__clearScheduleQueueModel,
        readQueueCache: sandbox.window.__readScheduleQueueCache,
        writeQueueCache: sandbox.window.__writeScheduleQueueCache,
        applyQueueSse: sandbox.window.__applyScheduleQueueSse,
        subscribeQueueSse: sandbox.window.__subscribeScheduleQueueSse,
        unsubscribeQueueSse: sandbox.window.__unsubscribeScheduleQueueSse,
        dispatchQueueSse(workId, data) {
            (sseListeners.get(String(workId)) || []).slice().forEach(listener => listener(data));
        },
        queueSseListenerCount(workId) {
            return (sseListeners.get(String(workId)) || []).length;
        },
        pendingReason: sandbox.window.__pendingReasonText,
        renderTaskCard: sandbox.window.__renderScheduleTaskCard,
        renderSnapshot: sandbox.window.__renderScheduleSnapshotBody,
        deleteTask: sandbox.window.__deleteScheduleTask,
        applyOverrides: sandbox.window.__applyScheduleOverrides,
        fillSavedCredential: sandbox.window.__fillScheduleCookieFromSaved,
        credentialPolicy: sandbox.window.__scheduleTaskCredentialPolicy,
        statusLabel: sandbox.window.__scheduleStatusLabel,
        setCredentialPolicyGroups: sandbox.window.__setScheduleCredentialPolicyGroups,
        renderCredentialPolicyBanners: sandbox.window.__renderCredentialPolicyBanners,
        applyCredentialPolicyAction: sandbox.window.__applyScheduleCredentialPolicyAction,
        showOverride: sandbox.window.__showScheduleOverrideModal,
        saveOverride: sandbox.window.__saveScheduleOverride,
        updateFetchLimit: sandbox.window.__updateScheduleFetchLimitVisibility,
        sourceContext: sandbox.window.__scheduleSourceContext,
        element: id => elements.get(id),
        status,
        stale() { current = false; },
        switchToken(value) { lease.activationToken = value; },
        get fetchCount() { return fetchCount; },
        get requests() { return requests; },
        get confirmCount() { return confirmCalls.length; },
        get confirmCalls() { return confirmCalls.slice(); },
        get promptCalls() { return promptCalls.slice(); },
        get credentialCalls() { return credentialCalls.slice(); },
        get switchedModes() { return switchedModes.slice(); },
        storageValue(key) { return storage.get(String(key)); }
    };
}

module.exports = {deferred, taskCredentialPolicy, harness, source};
