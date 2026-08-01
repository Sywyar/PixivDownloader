'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(
    __dirname,
    '../../main/resources/static/pixiv-batch/modes/schedule.js'
), 'utf8') + `
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
window.__postScheduleCookie = postScheduleCookie;
window.__showScheduleOverrideModal = showScheduleOverrideModal;
window.__saveScheduleOverride = saveScheduleOverride;
window.__updateScheduleFetchLimitVisibility = updateScheduleFetchLimitVisibility;
window.__scheduleSourceContext = scheduleSourceContext;`;

function deferred() {
    let resolve;
    const promise = new Promise(value => { resolve = value; });
    return {promise, resolve};
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
        descriptor: () => ({presentation: {
            displayNamespace: config.descriptorNamespace || 'example'
        }}),
        isAvailable: () => config.sourceActive !== false,
        credentialActions() {
            if (Object.prototype.hasOwnProperty.call(config, 'credentialActionsResult')) {
                return config.credentialActionsResult;
            }
            return {
                supportsProxy: config.supportsProxy === true,
                supportsCookie: config.supportsCookie === true,
                presentation: config.credentialPresentation || null
            };
        },
        invokeCredentialAction() {
            return config.validation ? config.validation.promise : Promise.resolve(null);
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
        postCookie: sandbox.window.__postScheduleCookie,
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
        get switchedModes() { return switchedModes.slice(); },
        storageValue(key) { return storage.get(String(key)); }
    };
}

test('计划来源 context 只通过宿主 adapter 读取并回灌取得输入', () => {
    const h = harness({singleImportValue: 'original-input'});
    const context = h.sourceContext();
    const host = context.__scheduleAcquisitionHost;

    assert.equal(Object.prototype.hasOwnProperty.call(context, 'editing'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(context, 'admin'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(context, 'workTypeOwnerPluginId'), false);
    assert.equal(host.input('single-import'), 'original-input');
    assert.equal(host.input('search'), null);
    assert.equal(host.restore('single-import', 'restored-input'), true);
    assert.deepEqual(h.switchedModes, ['single-import']);
    assert.equal(h.element('single-import-textarea').value, 'restored-input');
    assert.equal(host.restore('search', 'ignored'), false);
    assert.deepEqual(h.switchedModes, ['single-import']);
});

test('宿主来源错误使用当前语言文案且插件校验消息保持原样', async () => {
    const cases = [
        ['SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
            'schedule.error.source-editor-unavailable', 'SOURCE_EDITOR_LOCALIZED'],
        ['SCHEDULE_SOURCE_EDITOR_AMBIGUOUS',
            'schedule.error.source-editor-ambiguous', 'SOURCE_AMBIGUOUS_LOCALIZED'],
        ['SCHEDULE_SOURCE_DEFINITION_INVALID',
            'schedule.error.source-definition-invalid', 'SOURCE_DEFINITION_LOCALIZED']
    ];
    for (const [code, key, message] of cases) {
        const error = new Error('raw host error');
        error.code = code;
        const localized = harness({captureError: error, translations: {[key]: message}});

        await localized.submit();

        assert.equal(localized.status.textContent, message);
        assert.equal(localized.fetchCount, 0);
    }

    const pluginValidation = harness({captureError: new Error('PLUGIN_LOCALIZED_VALIDATION')});
    await pluginValidation.submit();
    assert.equal(pluginValidation.status.textContent, 'PLUGIN_LOCALIZED_VALIDATION');
});

test('单独来源凭证授权请求携带当前 publication 激活令牌', async () => {
    const h = harness({response: {ok: true}});

    const error = await h.postCookie(
        7, 'PHPSESSID=7_secret', 'token-a', new AbortController().signal);

    assert.equal(error, null);
    assert.equal(h.requests[0].url, '/api/schedule/tasks/7/authorize-cookie');
    assert.equal(h.requests[0].init.headers['X-Acquisition-Credential'], 'PHPSESSID=7_secret');
    assert.deepEqual(JSON.parse(h.requests[0].init.body), {
        activationToken: 'token-a'
    });
});

test('覆盖弹窗固定打开时 token 且 A→B 后不向新 publication 提交旧凭证', async () => {
    const h = harness({supportsCookie: true, response: {ok: true}});
    h.replaceTasks([{
        id: 7,
        sourceType: 'source-a',
        sourceActivationToken: 'token-a',
        cookieBound: false
    }]);

    h.showOverride(7);
    assert.equal(h.element('schedule-override-modal').dataset.sourceActivationToken, 'token-a');
    h.element('sch-ov-cookie-enabled').checked = true;
    h.element('sch-ov-cookie').value = 'PHPSESSID=old_secret';
    h.switchToken('token-b');

    await h.saveOverride();

    assert.equal(h.element('sch-ov-status').textContent, '任务状态已变化，请刷新后重试');
    assert.equal(h.requests.some(request => request.url.includes('/authorize-cookie')), false);
});

test('validation await 期间 A→B 后旧 submit 零状态写与零请求', async () => {
    const validation = deferred();
    const h = harness({validation, supportsCookie: true, cookieChecked: true, cookieValue: 'cookie'});
    const pending = h.submit();
    h.stale();
    validation.resolve('old validation error');
    await pending;
    assert.equal(h.status.textContent, '');
    assert.equal(h.fetchCount, 0);
    assert.equal(h.confirmCount, 0);
});

test('full-fetch confirm await 期间 A→B 后旧 submit 不继续请求', async () => {
    const confirm = deferred();
    const h = harness({confirm, fetchLimitMode: 'per-run'});
    const pending = h.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(h.confirmCount, 1);
    assert.equal(h.confirmCalls[0].key, 'schedule.confirm.full-fetch');
    assert.doesNotMatch(h.confirmCalls[0].fallback, /Pixiv/i);
    h.stale();
    confirm.resolve(true);
    await pending;
    assert.equal(h.status.textContent, '');
    assert.equal(h.fetchCount, 0);
});

test('来源抓取提示使用受控 key 且未知来源保持中性文案', async () => {
    const generic = harness({
        sourcePreview: {fetchLimitMode: 'watermark', fetchLimitPresentation: null}
    });
    generic.updateFetchLimit();
    assert.equal(generic.element('sch-fetch-limit-row').style.display, '');
    assert.doesNotMatch(generic.element('sch-fetch-limit-hint-watermark').textContent, /Pixiv/i);

    const confirm = deferred();
    const pixiv = harness({
        confirm,
        fetchLimitMode: 'watermark',
        fetchLimitPresentation: {
            namespace: 'batch',
            watermarkHintKey: 'schedule.pixiv.fetch-limit.hint.watermark',
            perRunHintKey: 'schedule.pixiv.fetch-limit.hint.per-run',
            fullFetchConfirmKey: 'schedule.pixiv.confirm.full-fetch'
        },
        sourcePreview: {
            fetchLimitMode: 'per-run',
            fetchLimitPresentation: {
                namespace: 'batch',
                watermarkHintKey: 'schedule.pixiv.fetch-limit.hint.watermark',
                perRunHintKey: 'schedule.pixiv.fetch-limit.hint.per-run',
                fullFetchConfirmKey: 'schedule.pixiv.confirm.full-fetch'
            }
        },
        translations: {
            'batch:schedule.pixiv.fetch-limit.hint.per-run': 'PIXIV_SOURCE_RISK'
        }
    });
    pixiv.updateFetchLimit();
    assert.equal(pixiv.element('sch-fetch-limit-hint-per-run').textContent, 'PIXIV_SOURCE_RISK');
    const pending = pixiv.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(pixiv.confirmCalls[0].key, 'batch:schedule.pixiv.confirm.full-fetch');
    confirm.resolve(false);
    await pending;
});

test('NONE、仅代理、仅凭证与来源缺席使用中性动作并只读降级', () => {
    const none = harness({sourceActive: true});
    const noneUi = none.credentialUi({sourceType: 'source-a', cookieBound: true});
    assert.equal(noneUi.badgeLabel, null);
    assert.equal(noneUi.showOverride, false);
    assert.equal(noneUi.overrideLabel, '🌐 指定单独代理');
    assert.equal(noneUi.proxyLabel, '单独代理');

    const proxy = harness({sourceActive: true, supportsProxy: true});
    const proxyUi = proxy.credentialUi({sourceType: 'source-a', cookieBound: false});
    assert.equal(proxyUi.badgeLabel, null);
    assert.equal(proxyUi.showOverride, true);
    assert.equal(proxyUi.overrideLabel, '🌐 指定单独代理');
    assert.doesNotMatch(proxyUi.overrideLabel, /Pixiv|Cookie/i);

    const credential = harness({sourceActive: true, supportsCookie: true});
    const credentialUi = credential.credentialUi({sourceType: 'source-a', cookieBound: true});
    assert.equal(credentialUi.badgeLabel, '已绑定凭证');
    assert.equal(credentialUi.showOverride, true);
    assert.equal(credentialUi.overrideLabel, '🔑 指定单独凭证');
    assert.doesNotMatch(credentialUi.overrideLabel, /Pixiv|Cookie/i);

    const missing = harness({sourceActive: false});
    const missingUi = missing.credentialUi({sourceType: 'source-a', cookieBound: true});
    assert.equal(missingUi.badgeLabel, '已绑定凭证');
    assert.equal(missingUi.showOverride, false);
    const html = missing.renderTaskCard({
        id: 7,
        name: 'task',
        sourceType: 'source-a',
        sourceAvailable: false,
        sourceActivationToken: 'token-a',
        presentation: {attributes: {kind: 'work-a'}},
        enabled: true,
        cookieBound: true,
        proxy: null,
        triggerKind: 'interval',
        intervalMinutes: 30,
        suspendReason: 'SOURCE_UNAVAILABLE'
    });
    assert.match(html, /showScheduleSnapshot\(7\)/);
    assert.match(html, /class="btn btn-purple" disabled[^>]+onclick="startEditScheduleTask\(7\)"/);
    assert.doesNotMatch(html, /showScheduleOverrideModal\(7\)/);
    assert.match(html, /deleteScheduleTask\(7\)/);
    const snapshot = missing.renderSnapshot({
        id: 7,
        name: 'task',
        sourceType: 'source-a',
        sourceAvailable: false,
        presentation: {attributes: {kind: 'work-a'}},
        enabled: true,
        cookieBound: true,
        triggerKind: 'interval',
        intervalMinutes: 30,
        lastStatus: 'AUTH_EXPIRED'
    });
    assert.doesNotMatch(snapshot, /Pixiv|Cookie|PHPSESSID/i);
});

test('来源失败机器码经来源命名空间本地化且未知码不直接展示', () => {
    const h = harness({
        descriptorNamespace: 'douyin',
        pluginTranslations: {
            'douyin:schedule.upstream-response-invalid': '上游响应结构无法识别'
        }
    });

    const translated = h.statusLight({
        sourceType: 'douyin.search',
        enabled: true,
        lastStatus: 'ERROR',
        lastMessage: 'douyin.schedule.upstream-response-invalid'
    });
    const unknown = h.statusLight({
        sourceType: 'douyin.search',
        enabled: true,
        lastStatus: 'ERROR',
        lastMessage: 'douyin.schedule.private-machine-code'
    });

    assert.match(translated.text, /上游响应结构无法识别/);
    assert.doesNotMatch(translated.text, /douyin\.schedule/);
    assert.doesNotMatch(unknown.text, /private-machine-code|douyin\.schedule/);
});

test('永久挂起优先展示已注册容量说明且未知挂起码仍使用中性迁移文案', () => {
    const h = harness({
        descriptorNamespace: 'douyin',
        pluginTranslations: {
            'douyin:schedule.checkpoint-capacity-exceeded': '收藏作品超过检查点容量'
        }
    });

    const capacity = h.statusLight({
        sourceType: 'douyin.account-favorite-works',
        enabled: true,
        suspendReason: 'MIGRATION_ERROR',
        suspendCode: 'douyin.schedule.checkpoint-capacity-exceeded'
    });
    const unknown = h.statusLight({
        sourceType: 'douyin.account-favorite-works',
        enabled: true,
        suspendReason: 'MIGRATION_ERROR',
        suspendCode: 'douyin.schedule.private-machine-code'
    });

    assert.equal(capacity.text, '收藏作品超过检查点容量');
    assert.equal(unknown.text, '任务数据需要修复，无法运行');
    assert.doesNotMatch(unknown.text, /private-machine-code|douyin\.schedule/);
});

test('计划队列只持久化校验后的失败机器码并在渲染时按当前语言本地化', () => {
    const key = 'douyin:schedule.upstream-response-invalid';
    const pluginTranslations = {[key]: '上游响应结构无法识别'};
    const h = harness({
        descriptorNamespace: 'source-owner',
        queueTypes: {
            manifestDescriptor(workType) {
                return workType === 'douyin'
                    ? {i18nNamespace: 'douyin', displayNamespace: 'display-only'} : null;
            }
        },
        pluginTranslations,
        translations: {'schedule.queue.status.failed': '通用失败'}
    });
    const machineCode = 'douyin.schedule.upstream-response-invalid';
    const model = h.queueItem({
        status: 'failed',
        message: machineCode,
        workType: 'douyin',
        workId: 'work-1'
    }, 'douyin.search', null);

    assert.equal(model.failureCode, machineCode);
    assert.equal(model.failureWorkType, 'douyin');
    assert.equal(Object.prototype.hasOwnProperty.call(model, 'failureSourceType'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(model, 'failureMessage'), false);
    assert.equal(h.localizeQueueItem(model).lastMessage, '上游响应结构无法识别');

    pluginTranslations[key] = 'Upstream response is unrecognized';
    assert.equal(h.localizeQueueItem(model).lastMessage, 'Upstream response is unrecognized');

    const legacy = h.localizeQueueItem({
        status: 'failed',
        failureMessage: machineCode,
        kind: 'douyin',
        failureSourceType: 'douyin.search'
    });
    assert.equal(legacy.lastMessage, 'Upstream response is unrecognized');

    const sourceOnlyLegacy = h.localizeQueueItem({
        status: 'failed',
        failureMessage: machineCode,
        failureSourceType: 'douyin.search'
    });
    assert.equal(sourceOnlyLegacy.lastMessage, '通用失败');

    const unknown = h.localizeQueueItem({
        status: 'failed',
        rawStatus: 'failed',
        failureCode: 'douyin.schedule.private-machine-code',
        failureWorkType: 'douyin'
    });
    const maliciousLegacy = h.localizeQueueItem({
        status: 'failed',
        rawStatus: 'forged-status',
        failureMessage: '<img src=x onerror=alert(1)>',
        failureSourceType: 'douyin.search'
    });
    const freeText = h.queueItem({
        status: 'failed',
        message: 'private backend failure details',
        workType: 'douyin',
        workId: 'work-2'
    }, 'douyin.search', null);

    assert.equal(unknown.lastMessage, '通用失败');
    assert.equal(maliciousLegacy.lastMessage, '通用失败');
    assert.equal(freeText.failureCode, null);
    assert.equal(Object.prototype.hasOwnProperty.call(freeText, 'failureMessage'), false);
    assert.equal(h.localizeQueueItem(freeText).lastMessage, '通用失败');
});

test('计划队列只投影中性 DTO 与 raw liveStatus 并把私有语义留给 owner', () => {
    const dto = {
        status: 'running',
        workId: 'opaque-1',
        workType: 'third-party',
        title: 'Neutral title',
        author: 'Neutral author',
        thumbnailReference: 'thumb:opaque-1',
        presentationAttributes: {xRestrict: '2', ai: 'true'},
        resultAttributes: {privateResult: 'done'},
        liveStatus: {phase: 'PRIVATE_PHASE', elapsedSeconds: '12'}
    };
    const fallbackHarness = harness({});
    const fallback = fallbackHarness.queueItem(dto, 'third-party.source', null);

    assert.equal(fallback.id, 'opaque-1');
    assert.equal(fallback.kind, 'third-party');
    assert.equal(fallback.rawTitle, 'Neutral title');
    assert.equal(fallback.author, 'Neutral author');
    assert.equal(fallback.thumbnailReference, 'thumb:opaque-1');
    assert.equal(fallback.presentationAttributes.xRestrict, '2');
    assert.equal(fallback.resultAttributes.privateResult, 'done');
    assert.equal(fallback.liveStatus.phase, 'PRIVATE_PHASE');
    assert.notEqual(fallback.liveStatus, dto.liveStatus);
    assert.equal(Object.prototype.hasOwnProperty.call(fallback, 'xRestrict'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(fallback, 'isAi'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(fallback, 'translatePhase'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(fallback, 'translateElapsed'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(fallback, 'translateSeriesPending'), false);

    let captured = null;
    const ownerHarness = harness({
        queueTypes: {
            scheduledQueueItem(type, item, context) {
                captured = {type, item, context};
                item.workId = 'mutated-input-id';
                item.workType = 'mutated-input-type';
                item.liveStatus.phase = 'MUTATED_INPUT_STATUS';
                return {
                    id: 'owned-' + item.workId,
                    kind: 'forged-kind',
                    workId: 'forged-work-id',
                    workType: 'forged-work-type',
                    queueKey: 'forged-key',
                    rawTitle: item.title
                };
            }
        }
    });
    const task = {id: 7};
    const owned = ownerHarness.queueItem(dto, 'third-party.source', task);
    assert.equal(captured.type, 'third-party');
    assert.equal(captured.item, dto);
    assert.equal(captured.context.sourceType, 'third-party.source');
    assert.equal(captured.context.task, task);
    assert.equal(owned.id, 'opaque-1');
    assert.equal(owned.kind, 'third-party');
    assert.equal(owned.workId, 'opaque-1');
    assert.equal(owned.workType, 'third-party');
    assert.equal(owned.queueKey, ownerHarness.queueKey(owned));
    assert.equal(owned.liveStatus.phase, 'PRIVATE_PHASE');
    assert.match(source, /liveStatus/);
    assert.doesNotMatch(source, /translatePhase|translateElapsed|translateSeriesPending/);
});

test('计划队列持久缓存剥离实时状态且旧缓存恢复时不会复活陈旧状态', () => {
    const h = harness({});
    h.writeQueueCache(7, {
        startedTime: 100,
        items: [{
            workType: 'novel',
            workId: 'opaque-7',
            rawTitle: 'title',
            liveStatus: {phase: 'TRANSLATING', elapsedSeconds: '12'}
        }]
    });

    const persisted = JSON.parse(h.storageValue('pixiv_schedule_queue_7'));
    assert.equal(persisted.items[0].workId, 'opaque-7');
    assert.equal(persisted.items[0].liveStatus, null);

    const legacy = harness({
        storageEntries: {
            pixiv_schedule_queue_8: JSON.stringify({
                startedTime: 80,
                items: [{
                    workType: 'novel',
                    workId: 'opaque-8',
                    liveStatus: {phase: 'DONE'}
                }]
            })
        }
    });
    const restored = legacy.readQueueCache(8);
    assert.equal(restored.items[0].workId, 'opaque-8');
    assert.equal(restored.items[0].liveStatus, null);
    assert.equal(legacy.loadQueueModel(8)[0].liveStatus, null);
});

test('同 workId 的不同 workType 在快照合并与 SSE 更新中保持复合身份隔离', () => {
    const h = harness({});
    const previousIllust = h.queueItem({
        status: 'pending',
        workType: 'illust',
        workId: 'same/id " <tag>',
        liveStatus: {phase: 'ILLUST_OLD'}
    }, 'pixiv.source', null);
    previousIllust.status = 'idle';
    previousIllust.totalImages = 2;
    previousIllust.downloadedCount = 1;
    const previousNovel = h.queueItem({
        status: 'pending',
        workType: 'novel',
        workId: 'same/id " <tag>',
        liveStatus: {phase: 'NOVEL_OLD'}
    }, 'pixiv.source', null);
    previousNovel.status = 'downloading';
    previousNovel.totalImages = 9;
    previousNovel.downloadedCount = 5;
    h.setQueueModel(7, [previousIllust, previousNovel]);

    const merged = h.mergeQueue(7, [{
        status: 'pending',
        workType: 'illust',
        workId: 'same/id " <tag>',
        liveStatus: {phase: 'ILLUST_NEW'}
    }, {
        status: 'pending',
        workType: 'novel',
        workId: 'same/id " <tag>',
        liveStatus: {phase: 'NOVEL_NEW'}
    }], 'pixiv.source');

    assert.equal(merged[0].kind, 'illust');
    assert.equal(merged[0].status, 'pending');
    assert.equal(merged[0].downloadedCount, 1);
    assert.equal(merged[0].liveStatus.phase, 'ILLUST_NEW');
    assert.equal(merged[1].kind, 'novel');
    assert.equal(merged[1].status, 'downloading');
    assert.equal(merged[1].downloadedCount, 5);
    assert.equal(merged[1].liveStatus.phase, 'NOVEL_NEW');
    assert.notEqual(h.queueKey(merged[0]), h.queueKey(merged[1]));
    assert.doesNotMatch(h.queueKey(merged[0]), /same\/id|<tag>|"/);

    h.setQueueModel(7, merged);
    h.applyQueueSse(7, h.queueKey(merged[0]), {
        totalImages: 4,
        downloadedCount: 3
    });
    const afterSse = h.getQueueModel(7);
    assert.equal(afterSse[0].status, 'downloading');
    assert.equal(afterSse[0].downloadedCount, 3);
    assert.equal(afterSse[1].status, 'downloading');
    assert.equal(afterSse[1].downloadedCount, 5);
    assert.equal(afterSse[1].liveStatus.phase, 'NOVEL_NEW');

    const rawWorkId = 'same/id " <tag>';
    h.setQueueModel(7, [afterSse[0]]);
    h.subscribeQueueSse(7);
    assert.equal(h.queueSseListenerCount(rawWorkId), 1);
    h.setQueueModel(7, afterSse);
    h.subscribeQueueSse(7);
    assert.equal(h.queueSseListenerCount(rawWorkId), 2);
    h.dispatchQueueSse(rawWorkId, {totalImages: 8, downloadedCount: 7});
    assert.equal(afterSse[0].downloadedCount, 3);
    assert.equal(afterSse[1].downloadedCount, 5);
    h.dispatchQueueSse(rawWorkId, {
        workType: 'novel',
        totalImages: 8,
        downloadedCount: 7
    });
    assert.equal(afterSse[0].downloadedCount, 3);
    assert.equal(afterSse[1].downloadedCount, 7);
    h.unsubscribeQueueSse(7);
    assert.equal(h.queueSseListenerCount(rawWorkId), 0);
});

test('计划队列 workId 按 String 原样保留空白与特殊字符', () => {
    const h = harness({});
    const workId = `  /"'<> opaque id  `;
    const item = h.queueItem({
        status: 'pending',
        workType: 'third-party',
        workId
    }, 'third-party.source', null);

    assert.equal(item.id, workId);
    assert.equal(item.workId, workId);
    assert.equal(item.kind, 'third-party');
    assert.equal(item.workType, 'third-party');
    assert.equal(item.queueKey, h.queueKey(item));
    assert.doesNotMatch(item.queueKey, /opaque id|[<>"'\/]/);
});

test('pending 原因只展示已注册机器码翻译且不回显未知或畸形详情', () => {
    const machineCode = 'douyin.schedule.upstream-response-invalid';
    const h = harness({
        descriptorNamespace: 'douyin',
        pluginTranslations: {
            'douyin:schedule.upstream-response-invalid': '上游响应结构无法识别'
        },
        translations: {
            'schedule.pending.reason-unavailable': '失败原因不可用'
        }
    });

    assert.equal(h.pendingReason({reasonCode: machineCode}, 'douyin.search'), '上游响应结构无法识别');
    assert.equal(h.pendingReason({
        reasonDetailJson: JSON.stringify({reasonCode: machineCode})
    }, 'douyin.search'), '上游响应结构无法识别');
    assert.equal(h.pendingReason({
        reasonDetailJson: JSON.stringify({legacyReason: machineCode})
    }, 'douyin.search'), '上游响应结构无法识别');
    assert.equal(h.pendingReason({
        reasonCode: 'douyin.schedule.private-machine-code'
    }, 'douyin.search'), '失败原因不可用');
    assert.equal(h.pendingReason({
        reasonDetailJson: JSON.stringify({message: '<img src=x onerror=alert(1)>'})
    }, 'douyin.search'), '失败原因不可用');
    assert.equal(h.pendingReason({
        reasonDetailJson: JSON.stringify({legacyReason: 'private backend failure details'})
    }, 'douyin.search'), '失败原因不可用');
    assert.equal(h.pendingReason({
        reasonDetailJson: '{not-json'
    }, 'douyin.search'), '失败原因不可用');
    assert.equal(h.pendingReason({}, 'douyin.search'), '');
});

test('清除确认只使用校验后的来源 key，第三方默认文案保持中性', async () => {
    const proxyConfirm = deferred();
    const proxy = harness({confirm: proxyConfirm, supportsProxy: true});
    proxy.setEditing(7, [{id: 7, sourceType: 'source-a', proxy: '127.0.0.1:7890'}]);
    const proxyPending = proxy.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(proxy.confirmCalls[0].key, 'schedule.confirm.clear-proxy');
    assert.doesNotMatch(proxy.confirmCalls[0].fallback, /Pixiv|Cookie|R-18|我的收藏/i);
    proxyConfirm.resolve(false);
    await proxyPending;

    const credentialConfirm = deferred();
    const credential = harness({confirm: credentialConfirm, supportsCookie: true});
    credential.setEditing(8, [{id: 8, sourceType: 'source-a', cookieBound: true}]);
    const credentialPending = credential.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(credential.confirmCalls[0].key, 'schedule.confirm.clear-cookie');
    assert.doesNotMatch(credential.confirmCalls[0].fallback, /Pixiv|Cookie|R-18|我的收藏/i);
    credentialConfirm.resolve(false);
    await credentialPending;

    const pixivConfirm = deferred();
    const pixiv = harness({
        confirm: pixivConfirm,
        supportsProxy: true,
        descriptorNamespace: 'batch',
        credentialPresentation: {
            namespace: 'batch',
            clearProxyConfirmKey: 'schedule.pixiv.confirm.clear-proxy'
        }
    });
    pixiv.setEditing(9, [{id: 9, sourceType: 'source-a', proxy: '127.0.0.1:7890'}]);
    const pixivPending = pixiv.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(pixiv.confirmCalls[0].key, 'batch:schedule.pixiv.confirm.clear-proxy');
    pixivConfirm.resolve(false);
    await pixivPending;

    const forgedConfirm = deferred();
    const forged = harness({
        confirm: forgedConfirm,
        supportsProxy: true,
        descriptorNamespace: 'example',
        credentialPresentation: {
            namespace: 'another-plugin',
            clearProxyConfirmKey: 'schedule.pixiv.confirm.clear-proxy'
        }
    });
    forged.setEditing(10, [{id: 10, sourceType: 'source-a', proxy: '127.0.0.1:7890'}]);
    const forgedPending = forged.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(forged.confirmCalls[0].key, 'schedule.confirm.clear-proxy');
    forgedConfirm.resolve(false);
    await forgedPending;
});

test('异步 credentialActions 违约会被隔离且吸收 rejection', async () => {
    const rejected = Promise.reject(new Error('async credential actions are forbidden'));
    const h = harness({credentialActionsResult: rejected});
    const ui = h.credentialUi({sourceType: 'source-a', cookieBound: true});
    assert.equal(ui.showOverride, false);
    assert.equal(ui.badgeLabel, null);
    await new Promise(resolve => setImmediate(resolve));
});

test('删除确认在来源缺席时不泄漏 Cookie 或 Pixiv 语义', async () => {
    const confirm = deferred();
    const h = harness({confirm, sourceActive: false});
    const pending = h.deleteTask(7);
    assert.equal(h.confirmCalls[0].key, 'schedule.confirm.delete');
    assert.doesNotMatch(h.confirmCalls[0].fallback, /Pixiv|Cookie|PHPSESSID/i);
    confirm.resolve(false);
    await pending;
});

test('override-clear confirm await 期间 A→B 后旧 submit 不继续请求', async () => {
    const confirm = deferred();
    const h = harness({confirm, supportsProxy: true});
    h.setEditing(7, [{id: 7, sourceType: 'source-a', proxy: '127.0.0.1:7890'}]);
    const pending = h.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(h.confirmCount, 1);
    h.stale();
    confirm.resolve(true);
    await pending;
    assert.equal(h.status.textContent, '');
    assert.equal(h.fetchCount, 0);
});

test('非 2xx error JSON await 期间 A→B 后旧错误不回写表单', async () => {
    const errorBody = deferred();
    const response = {ok: false, json: () => errorBody.promise};
    const h = harness({response});
    const pending = h.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(h.fetchCount, 1);
    h.stale();
    errorBody.resolve({error: 'old publication error'});
    await pending;
    assert.equal(h.status.textContent, '');
});

test('编辑提交固定打开表单时的版本且轮询新 cache 不得抬高版本', async () => {
    const response = {ok: false, json: () => Promise.resolve({error: 'conflict'})};
    const h = harness({response});
    h.setEditing(7, [{
        id: 7,
        sourceType: 'source-a',
        sourceActivationToken: 'token-a',
        stateVersion: 4
    }]);
    h.replaceTasks([{
        id: 7,
        sourceType: 'source-a',
        sourceActivationToken: 'token-a',
        stateVersion: 9
    }]);

    await h.submit();

    assert.equal(h.fetchCount, 1);
    assert.equal(h.requests[0].url, '/api/schedule/tasks/7');
    assert.equal(h.requests[0].init.method, 'PUT');
    assert.equal(JSON.parse(h.requests[0].init.body).expectedStateVersion, 4);
});

test('同来源编辑 A 等待校验时切到 B 后旧提交零请求', async () => {
    const validation = deferred();
    const h = harness({validation, supportsCookie: true, cookieChecked: true, cookieValue: 'cookie'});
    h.setEditing(7, [{
        id: 7,
        sourceType: 'source-a',
        sourceActivationToken: 'token-a',
        stateVersion: 4
    }]);
    const pending = h.submit();
    h.setEditing(8, [{
        id: 8,
        sourceType: 'source-a',
        sourceActivationToken: 'token-a',
        stateVersion: 6
    }]);
    validation.resolve(null);

    await pending;

    assert.equal(h.fetchCount, 0);
    assert.equal(h.status.textContent, '');
});
