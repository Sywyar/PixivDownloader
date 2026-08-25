'use strict';

/** 异步生命周期、时间戳与 SDK 配置。 */
const {
    CONFIG_SOURCE,
    CONFIG_SOURCE_PATH,
    CONFIG_WINDOW,
    CSS,
    CSS_PATH,
    EMBED_SOURCE,
    EMBED_SOURCE_PATH,
    LAYOUT_IDS,
    MiniClassList,
    MiniCustomEvent,
    MiniElement,
    MiniEventTarget,
    MiniStorage,
    POSTHOG_SOURCE,
    POSTHOG_SOURCE_PATH,
    SEEN_KEY,
    SERVER_RAW_UUID,
    SERVER_SCOPED_ID,
    SNOOZE_MS,
    SOURCE,
    SOURCES,
    SOURCE_NAMES,
    SOURCE_PATHS,
    STATE_KEY,
    SUBMISSION_ID,
    SUGGESTION_MAX,
    SURVEY_ID,
    ackEvents,
    assert,
    assertFailClosedInvariants,
    captureEvents,
    captureProps,
    createFakeAdapter,
    createFakeI18n,
    createFakeTimers,
    createHarness,
    crossTabFallbackMatrix,
    crossTabState,
    dataAttributeName,
    defaultSurvey,
    directRefresh,
    doesNotThrow,
    eq,
    fs,
    hasLoneSurrogates,
    initHarness,
    jsonEq,
    listenerCountFor,
    localStateValue,
    matchSelector,
    neverView,
    ok,
    openAndPrepareSubmit,
    path,
    refreshSecond,
    refreshWith,
    reinitOptions,
    runTests,
    seenObject,
    seenSeed,
    selectChoice,
    serverStateResponse,
    snoozeStorageValue,
    snoozedView,
    submitWithCaptureOverride,
    submittedView,
    surveyState,
    validFirst,
    vm,
    waitForFlush,
    waitForServerContext,
    walkAll
} = require('./pixiv-layout-feedback-test-support');

function testDestroyDuringSdkLoad() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    let resolved = false;
    const promise = h.api.open().then(value => { resolved = true; return value; });
    return waitForFlush().then(() => {
        eq('SDK 加载中恰好插入一个 script', h.scriptElements().length, 1);
        h.api.destroy();
        return waitForFlush().then(() => {
            ok('open Promise 在 destroy 后安全完成（不永久 pending）', resolved);
            const scripts = h.scriptElements();
            eq('共享 SDK script 不随单个消费者销毁', scripts[0].parentNode !== null, true);
            eq('共享 loader 保留 load listener', (scripts[0].listeners.get('load') || []).length, 1);
            eq('共享 loader 保留 error listener', (scripts[0].listeners.get('error') || []).length, 1);
            eq('destroy 后不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('destroy 后不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
            eq('destroy 后无 Toast', h.toastCalls.length, 0);
            h.timers.advance(30000);
            return waitForFlush();
        });
    }).then(() => {
        eq('SDK 加载取消后无任何后续动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
        const scripts = h.scriptElements();
        eq('共享 loader 超时后移除 script', scripts[0].parentNode === null, true);
        eq('共享 loader 超时后移除 load listener', (scripts[0].listeners.get('load') || []).length, 0);
        eq('共享 loader 超时后移除 error listener', (scripts[0].listeners.get('error') || []).length, 0);
        eq('destroy 后定时器无残留', h.timers.pending().length, 0);
        eq('destroy 后无监听残留', listenerCountFor(h, 'pixiv:batch-layout-changed') === 0
            && h.windowEvents.listenerCount('storage') === 0, true);
    });
}

function testLateScriptLoadAfterDestroy() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('SDK 加载中插入一个 script', h.scriptElements().length, 1);
        h.api.destroy();
        h.sandbox.posthog = createFakeAdapter({surveys: [defaultSurvey()]});
        h.fireScriptLoad();
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('旧 script 迟到 load 不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('旧 script 迟到 load 不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
        eq('旧 script 迟到 load 无 Toast', h.toastCalls.length, 0);
    });
}

function testDestroyAndReInit() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('第一次 init 弹窗正常', h.document.querySelectorAll('.plf-backdrop').length, 1);
        h.api.destroy();
        eq('destroy 关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.api.init(reinitOptions(h));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('re-init 后弹窗可再次打开', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('同时最多一个调查弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('storage 监听不重复', h.windowEvents.listenerCount('storage'), 1);
        eq('首次下载完成监听不重复', listenerCountFor(h, 'pixiv:first-download-completed'), 1);
        eq('layout 监听不重复', listenerCountFor(h, 'pixiv:batch-layout-changed'), 1);
    });
}

function testReuseLoadedSdk() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()]});
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    h.sandbox.posthog = adapter;
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('复用已存在 SDK 不插入 script', h.scriptElements().length, 0);
        eq('SDK init 恰好一次', adapter.calls.init.length, 1);
        eq('已加载 SDK 正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('重复 open 相同配置不重复 init', adapter.calls.init.length, 1);
        eq('重复 open 不插入 script', h.scriptElements().length, 0);
        h.api.destroy();
        h.api.init(reinitOptions(h, null));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('destroy 后重新 init 复用已加载 SDK', h.scriptElements().length, 0);
        eq('destroy 后重新 init 相同签名不重复 init', adapter.calls.init.length, 1);
        eq('destroy 后弹窗可再次打开', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testIdentityMismatchFailsClosed() {
    const h = initHarness({batchLayout: 'landscape'});
    let surveysBefore = 0;
    let captureBefore = 0;
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('配置 A 正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        surveysBefore = h.adapter.calls.getSurveys.length;
        captureBefore = h.adapter.calls.capture.length;
        h.api.destroy();
        h.setServerState(serverStateResponse({distinctId: 'plf_' + 'b'.repeat(64)}));
        h.api.init(reinitOptions(h));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('身份变化 fail closed：不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('身份变化不请求 Survey', h.adapter.calls.getSurveys.length, surveysBefore);
        eq('身份变化不发送事件', h.adapter.calls.capture.length, captureBefore);
        const warnings = JSON.stringify(h.consoleWarn);
        ok('记录安全 warning', warnings.indexOf('different configuration') >= 0);
        ok('warning 不含调查身份实际值', warnings.indexOf('plf_' + 'b'.repeat(64)) < 0);
    });
}

function testDestroyDuringAppVersionWait() {
    let resolveAppInfo = null;
    const adapter = createFakeAdapter({surveys: [defaultSurvey()]});
    const h = initHarness({
        adapter,
        batchLayout: 'landscape',
        fetchImpl: (url, init) => {
            if (url.indexOf('/api/app/info') >= 0) {
                return new Promise(resolve => { resolveAppInfo = resolve; });
            }
            // server state 快速 resolve（local 模式），只卡住 app version
            return Promise.resolve({ok: true, json: () => Promise.resolve({available: false})});
        }
    });
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('Survey 流程开始（弹窗已打开）', h.document.querySelectorAll('.plf-backdrop').length, 1);
        ok('appVersion fetch 尚未完成', typeof resolveAppInfo === 'function');
        h.api.destroy();
        resolveAppInfo({ok: true, json: () => Promise.resolve({name: 'x', version: '9.9.9'})});
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('destroy 后 appVersion 完成不补发 shown', captureEvents(h).filter(e => e === 'survey shown').length, 0);
        eq('destroy 后不重新打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后无 Toast', h.toastCalls.length, 0);
        eq('destroy 后不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
    });
}

function testDestroyDuringSurveyWait() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()], stallSurveys: true});
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        ok('Survey 回调已注册', typeof adapter.lastSurveyCallback === 'function');
        eq('Survey 等待期间不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.api.destroy();
        adapter.lastSurveyCallback([defaultSurvey()]);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('destroy 后迟到 Survey 回调不打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后不发送 shown', captureEvents(h).length, 0);
        eq('destroy 后不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
    });
}

function testDestroyDuringSubmit() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        eq('destroy 后提交完成不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        eq('destroy 后不显示成功 Toast', h.toastCalls.length, 0);
        eq('destroy 后无失败错误显示', h.document.querySelectorAll('[data-plf-error]').length, 0);
        eq('destroy 后无弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
    });
}

function testOldGenerationCannotAffectNewGeneration() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()], stallSurveys: true});
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const oldOpen = h.api.open();
    return waitForFlush().then(() => {
        const gen1Callback = adapter.lastSurveyCallback;
        ok('generation 1 已注册 Survey 回调', typeof gen1Callback === 'function');
        h.api.destroy();
        h.api.init(reinitOptions(h));
        const newOpen = h.api.open();
        return waitForFlush().then(() => {
            h.timers.advance(10000);
            return waitForFlush();
        }).then(() => {
            eq('generation 2 等待 Survey 期间不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
            gen1Callback([defaultSurvey()]);
            adapter.lastSurveyCallback([defaultSurvey()]);
            return Promise.all([oldOpen, newOpen]).then(() => waitForFlush());
        });
    }).then(() => {
        eq('旧 generation 迟到回调不影响新 generation 弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('shown 只来自新 generation', captureEvents(h).filter(e => e === 'survey shown').length, 1);
        eq('storage 监听唯一', h.windowEvents.listenerCount('storage'), 1);
        eq('首次下载完成监听唯一', listenerCountFor(h, 'pixiv:first-download-completed'), 1);
        eq('layout 监听唯一', listenerCountFor(h, 'pixiv:batch-layout-changed'), 1);
        eq('旧 generation 回调不再关闭新弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testDestroyIdempotence() {
    const h = initHarness({batchLayout: 'landscape'});
    doesNotThrow('destroy 一次', () => h.api.destroy());
    doesNotThrow('destroy 二次', () => h.api.destroy());
    doesNotThrow('destroy 三次', () => h.api.destroy());
    eq('destroy 后无 storage 监听', h.windowEvents.listenerCount('storage'), 0);
    eq('destroy 后无 layout 监听', listenerCountFor(h, 'pixiv:batch-layout-changed'), 0);
    eq('destroy 后无首次下载完成监听', listenerCountFor(h, 'pixiv:first-download-completed'), 0);
    eq('destroy 后定时器为空', h.timers.pending().length, 0);
    doesNotThrow('从未 init 时 destroy 安全', () => createHarness({}).api.destroy());
    const h2 = createHarness({batchLayout: 'landscape'});
    doesNotThrow('无配置环境 destroy 安全', () => h2.api.destroy());
}

function testOpenAfterDestroyIsNoop() {
    const h = initHarness({batchLayout: 'landscape'});
    h.api.destroy();
    const promise = h.api.open();
    return waitForFlush().then(() => promise).then(value => {
        eq('destroy 后 open 解析为 null', value, null);
        eq('destroy 后 open 不插入 script', h.scriptElements().length, 0);
        eq('destroy 后 open 不打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后 open 不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
        eq('destroy 后 open 无 Toast', h.toastCalls.length, 0);
        eq('destroy 后 open 不注册 listener', listenerCountFor(h, 'pixiv:batch-layout-changed') === 0
            && h.windowEvents.listenerCount('storage') === 0, true);
        h.timers.advance(30000);
        return waitForFlush();
    }).then(() => {
        eq('destroy 后 open 无定时器残留', h.timers.pending().length, 0);
    });
}

function testOpenBeforeInitIsNoop() {
    const h = createHarness({batchLayout: 'landscape'});
    return waitForFlush().then(() => h.api.open()).then(value => {
        eq('从未 init 时 open 解析为 null', value, null);
        eq('从未 init 时 open 不插 script', h.scriptElements().length, 0);
        eq('从未 init 时 open 不打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('从未 init 时 open 不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
    });
}

function testOpenAfterDestroyDoesNotBlockReinit() {
    const h = initHarness({batchLayout: 'landscape'});
    h.api.destroy();
    return h.api.open().then(() => {
        h.api.init(reinitOptions(h));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('destroy→open→init→open 正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('旧 open 未把 flowRunning 留为 true（不阻塞后续展示）',
            h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('storage 监听唯一', h.windowEvents.listenerCount('storage'), 1);
    });
}

function testIsDateObjectNoThrow() {
    const isDateObject = initHarness({}).api._internals.isDateObject;
    ok('正常 Date 返回 true', isDateObject(new Date('2026-01-01T00:00:00.000Z')) === true);
    ok('非法 Date 返回 false', isDateObject(new Date('invalid')) === false);
    ok('null 返回 false', isDateObject(null) === false);
    ok('undefined 返回 false', isDateObject(undefined) === false);
    ok('普通对象返回 false', isDateObject({}) === false);
    ok('Symbol.toStringTag 伪装 Date 但无 getTime 返回 false',
        isDateObject({[Symbol.toStringTag]: 'Date'}) === false);
    ok('伪装对象 getTime 返回 NaN 返回 false',
        isDateObject({[Symbol.toStringTag]: 'Date', getTime() { return NaN; }}) === false);
    ok('伪装对象 getTime 抛错返回 false',
        isDateObject({[Symbol.toStringTag]: 'Date', getTime() { throw new Error('boom'); }}) === false);
    ok('Object.prototype.toString 抛错的 Proxy 返回 false',
        isDateObject(new Proxy({}, {
            get(target, prop) {
                if (prop === Symbol.toStringTag) throw new Error('trap');
                return target[prop];
            }
        })) === false);
    ok('getTime 抛错的 Proxy 返回 false',
        isDateObject(new Proxy({}, {
            get(target, prop) {
                if (prop === Symbol.toStringTag) return 'Date';
                if (prop === 'getTime') return function () { throw new Error('trap'); };
                return target[prop];
            }
        })) === false);
}

function testBeforeSendTimestampMatrix() {
    const filter = initHarness({}).api._internals.beforeSendFilter;
    const base = {uuid: 'evt-1', event: 'survey sent', properties: {distinct_id: 'anon'}};

    const dateTs = new Date('2026-03-01T12:00:00.000Z');
    const outDate = filter(Object.assign({}, base, {timestamp: dateTs}));
    ok('Date timestamp 保留原始对象', outDate.timestamp === dateTs);
    eq('Date 时间值未被替换为当前时间', outDate.timestamp.getTime(), dateTs.getTime());
    ok('保留的是 Date 而非字符串', Object.prototype.toString.call(outDate.timestamp) === '[object Date]');

    const iso = '2026-03-01T12:00:00.000Z';
    const outIso = filter(Object.assign({}, base, {timestamp: iso}));
    eq('ISO string timestamp 保留', outIso.timestamp, iso);

    eq('null timestamp 省略', filter(Object.assign({}, base, {timestamp: null})).timestamp, undefined);
    eq('undefined timestamp 省略', filter(Object.assign({}, base, {timestamp: undefined})).timestamp, undefined);
    eq('普通对象 timestamp 省略', filter(Object.assign({}, base, {timestamp: {evil: true}})).timestamp, undefined);
    eq('非法 Date 省略', filter(Object.assign({}, base, {timestamp: new Date('invalid')})).timestamp, undefined);

    ok('Date timestamp 不影响 event / properties 且 uuid 被删除',
        outDate.uuid === undefined && outDate.event === 'survey sent'
        && outDate.properties.distinct_id === 'anon');
}

function testBeforeSendDateTimestampWithSurveyFields() {
    const filter = initHarness({}).api._internals.beforeSendFilter;
    const timestamp = new Date('2026-03-01T12:00:00.000Z');
    const out = filter({
        uuid: 'evt-2',
        event: 'survey shown',
        timestamp,
        $set: {'$survey_x_responded': true},
        $set_once: {'$initial_x': 'v'},
        $unset: ['old'],
        properties: {
            distinct_id: 'anon-2',
            token: 'phc_x',
            '$survey_id': 's1',
            '$survey_response_q-layout': 'pixiv-batch-portrait',
            $current_url: 'http://localhost:6999/pixiv-batch.html'
        }
    });
    ok('顶层 $set / $set_once / $unset 仍被删除',
        out.$set === undefined && out.$set_once === undefined && out.$unset === undefined);
    eq('$survey_id 保留', out.properties['$survey_id'], 's1');
    eq('$survey_response_* 保留', out.properties['$survey_response_q-layout'], 'pixiv-batch-portrait');
    ok('Date timestamp 与其他顶层字段并存', out.timestamp === timestamp);
    ok('输出顶层字段仅 event / timestamp / properties',
        Object.keys(out).every(k => ['event', 'timestamp', 'properties'].indexOf(k) >= 0));
}

function testFakeAdapterDefaultTimestampIsDate() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        const result = h.adapter.calls.results.find(r => r && r.event === 'survey shown');
        ok('fake adapter 默认 timestamp 为 Date 对象', result
            && Object.prototype.toString.call(result.timestamp) === '[object Date]');
    });
}

function testFakeAdapterTimestampOverrides() {
    return Promise.resolve().then(() => {
        const adapter = createFakeAdapter({surveys: [defaultSurvey()], timestamp: null});
        const h = initHarness({adapter, batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            const result = h.adapter.calls.results.find(r => r && r.event === 'survey shown');
            eq('null timestamp 在 before_send 后被省略', result.timestamp, undefined);
        });
    }).then(() => {
        const adapter = createFakeAdapter({surveys: [defaultSurvey()], timestamp: {bad: true}});
        const h = initHarness({adapter, batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            const result = h.adapter.calls.results.find(r => r && r.event === 'survey shown');
            eq('非法对象 timestamp 被省略', result.timestamp, undefined);
        });
    }).then(() => {
        const fixed = new Date('2026-06-01T00:00:00.000Z');
        const adapter = createFakeAdapter({surveys: [defaultSurvey()], timestamp: fixed});
        const h = initHarness({adapter, batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            const result = h.adapter.calls.results.find(r => r && r.event === 'survey shown');
            ok('Date 覆盖值原样保留', result && result.timestamp === fixed);
        });
    });
}

function testSdkConfigHeatmapMigration() {
    ok('PostHog 插件源码不再包含 enable_heatmaps 配置字段', POSTHOG_SOURCE.indexOf('enable_heatmaps') < 0);
    ok('PostHog 插件源码使用 capture_heatmaps', POSTHOG_SOURCE.indexOf('capture_heatmaps') >= 0);
}

runTests('pixiv-layout-feedback-lifecycle.test.js', [
    ['testDestroyDuringSdkLoad', testDestroyDuringSdkLoad],
    ['testLateScriptLoadAfterDestroy', testLateScriptLoadAfterDestroy],
    ['testDestroyAndReInit', testDestroyAndReInit],
    ['testReuseLoadedSdk', testReuseLoadedSdk],
    ['testIdentityMismatchFailsClosed', testIdentityMismatchFailsClosed],
    ['testDestroyDuringAppVersionWait', testDestroyDuringAppVersionWait],
    ['testDestroyDuringSurveyWait', testDestroyDuringSurveyWait],
    ['testDestroyDuringSubmit', testDestroyDuringSubmit],
    ['testOldGenerationCannotAffectNewGeneration', testOldGenerationCannotAffectNewGeneration],
    ['testDestroyIdempotence', testDestroyIdempotence],
    ['testOpenAfterDestroyIsNoop', testOpenAfterDestroyIsNoop],
    ['testOpenBeforeInitIsNoop', testOpenBeforeInitIsNoop],
    ['testOpenAfterDestroyDoesNotBlockReinit', testOpenAfterDestroyDoesNotBlockReinit],
    ['testIsDateObjectNoThrow', testIsDateObjectNoThrow],
    ['testBeforeSendTimestampMatrix', testBeforeSendTimestampMatrix],
    ['testBeforeSendDateTimestampWithSurveyFields', testBeforeSendDateTimestampWithSurveyFields],
    ['testFakeAdapterDefaultTimestampIsDate', testFakeAdapterDefaultTimestampIsDate],
    ['testFakeAdapterTimestampOverrides', testFakeAdapterTimestampOverrides],
    ['testSdkConfigHeatmapMigration', testSdkConfigHeatmapMigration]
]).catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
