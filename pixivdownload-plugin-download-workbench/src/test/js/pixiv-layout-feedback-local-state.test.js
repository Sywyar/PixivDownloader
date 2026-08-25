'use strict';

/** 本地状态、触发门禁、语言切换与 Unicode 边界。 */
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

function testSubmittedNeverSnoozedGatesTrigger() {
    const surveyId = SURVEY_ID;
    const state = (status, snoozedUntil) => JSON.stringify({
        surveyId, status, updatedAt: 100, snoozedUntil: snoozedUntil || 0
    });
    return Promise.resolve().then(() => {
        const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: state('submitted')})});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('submitted 后首次下载完成不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: state('never')})});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('never 后首次下载完成不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: state('snoozed', 2000000)})});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('snoozed 未到期不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: state('snoozed', 1000000)})});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('snoozed 到期后展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: JSON.stringify({
            surveyId: 'other-survey-id-000', status: 'never', updatedAt: 100, snoozedUntil: 0
        })}), initialWall: 1000000});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('Survey ID 变化后旧状态不拦截', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    });
}

function testCorruptStateIsCleaned() {
    const h = initHarness({
        page: 'alt',
        storage: {[STATE_KEY]: '{not json', [SEEN_KEY]: '[[['}
    });
    const removeCallsBefore = h.storage.removeCalls.length;
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('损坏状态安全清理后仍可展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        ok('损坏 SEEN_KEY 在记录/清理路径中被 removeItem', h.storage.removeCalls
            .some(k => k === SEEN_KEY));
        ok('损坏 STATE_KEY 在门禁检查路径中被 removeItem', h.storage.removeCalls
            .slice(removeCallsBefore).some(k => k === STATE_KEY));
    });
}

function testCorruptStateRemoveThrowsStillSafe() {
    const h = initHarness({
        page: 'alt',
        storage: {[STATE_KEY]: '{not json', [SEEN_KEY]: '[[['},
        throwOnRemove: true
    });
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('removeItem 抛错时损坏状态仍安全降级', h.document.querySelectorAll('.plf-backdrop').length, 1);
        ok('removeItem 抛错时仍尝试清理', h.storage.removeCalls
            .some(k => k === STATE_KEY || k === SEEN_KEY));
    });
}

function testStorageThrowSafe() {
    return Promise.resolve().then(() => {
        const h = initHarness({page: 'alt', throwOnGet: true});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('getItem 抛错不影响调查展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape', throwOnSet: true});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.actionButton('snooze').click();
            eq('setItem 抛错仍关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape', throwOnRemove: true});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.submitButton().click();
            return waitForFlush().then(() => {
                eq('removeItem 抛错不影响提交', captureEvents(h).filter(e => e === 'survey sent').length, 1);
            });
        });
    });
}

function testCrossTabStorageSync() {
    // 另一标签页写入 submitted 后，本标签页收到 storage 事件并同步状态；
    // 首次下载完成触发被阻断状态拦截，不展示。
    const h2 = initHarness({
        page: 'alt',
        storage: seenSeed()
    });
    const submitted = JSON.stringify({
        surveyId: SURVEY_ID, status: 'submitted',
        updatedAt: 999, snoozedUntil: 0
    });
    h2.storage.values.set(STATE_KEY, submitted);
    h2.dispatchStorage(STATE_KEY, submitted);
    h2.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('storage 事件同步后不展示', h2.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testSdkLoadFailure() {
    const h = initHarness({page: 'alt', adapter: null});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        // 服务端上下文 resolve 后脚本才插入；脚本加载失败静默结束
        eq('SDK 脚本已插入', h.scriptElements().length, 1);
        h.fireScriptError();
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('SDK 加载失败静默结束', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('SDK 加载失败后不重新插入脚本', h.scriptElements().length, 1);
        eq('SDK 加载失败后触发不再动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testSdkLoadSuccessThroughScript() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        h.sandbox.posthog = h.adapter ? null : createFakeAdapter({surveys: [defaultSurvey()]});
        h.fireScriptLoad();
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('SDK 加载成功后走真实 posthog 全局', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testSdkLoadTimeout() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('SDK 脚本已插入', h.scriptElements().length, 1);
        h.timers.advance(15000);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('SDK 加载超时静默结束', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testFlagsTimeout() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()], stallFlags: true});
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('flags 未就绪时不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.timers.advance(15000);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('flags 超时后仍可获取调查', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testSurveyFetchTimeout() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()], stallSurveys: true});
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        h.timers.advance(40000);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('Survey 获取超时静默结束', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testPreloadWarmsSdkBeforeFirstDownload() {
    const h = initHarness({page: 'alt'});
    const preloadPromise = h.api.preload();
    return Promise.all([preloadPromise, waitForFlush()]).then(() => {
        eq('预加载已完成 SDK 初始化', h.adapter.calls.init.length, 1);
        eq('预加载不弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('首次下载完成事件到达后直接展示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('展示流程复用预加载的 SDK 不重复初始化', h.adapter.calls.init.length, 1);
    });
}

function testPreloadLoadsSdkScriptEarly() {
    const h = initHarness({page: 'alt', adapter: null});
    const preloadPromise = h.api.preload();
    return waitForFlush().then(() => {
        eq('预加载即插入 SDK 脚本', h.scriptElements().length, 1);
        h.sandbox.posthog = createFakeAdapter({surveys: [defaultSurvey()]});
        h.fireScriptLoad();
        return Promise.all([preloadPromise, waitForFlush()]);
    }).then(() => {
        eq('预加载完成不弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('脚本已就绪时事件到达即展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testPreloadSkipsSdkInitWhenStateBlocks() {
    const state = JSON.stringify({
        surveyId: SURVEY_ID,
        status: 'submitted', updatedAt: 100, snoozedUntil: 0
    });
    const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: state})});
    const preloadPromise = h.api.preload();
    return Promise.all([preloadPromise, waitForFlush()]).then(() => {
        eq('状态阻断时预加载不初始化 SDK', h.adapter.calls.init.length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('状态阻断时事件到达不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testPreloadBeforeInitIsNoop() {
    const h = initHarness({page: 'alt'});
    h.api.destroy();
    return h.api.preload().then(() => waitForFlush()).then(() => {
        eq('destroy 后 preload 是安全 no-op', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后 preload 不加载脚本', h.scriptElements().length, 0);
    });
}

function testDisabledConfigDoesNothing() {
    const h = initHarness({officialRelease: false});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('非官方发行不展示调查', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('非官方发行不加载 SDK', h.scriptElements().length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('非官方发行触发不动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testMissingPostHogPluginTreatedAsDisabled() {
    const h = initHarness({posthogAvailable: false});
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('PostHog 插件缺失时静默关闭', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testFirstDownloadTriggerConditions() {
    return Promise.resolve().then(() => {
        const h = initHarness({page: 'alt', batchLayout: 'landscape'});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('alt 页首次下载完成即展示（无布局体验阈值）', h.document.querySelectorAll('.plf-backdrop').length, 1);
            const shown = captureEvents(h).filter(e => e === 'survey shown').length;
            eq('展示发送一次 shown', shown, 1);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', throwOnGet: true});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('getItem 抛错不影响调查展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        const h = initHarness({page: 'batch', batchLayout: 'landscape'});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('batch 页忽略首次下载完成事件（不参与调查）', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

function testTriggerBlockedOverlaySkipsThenAllows() {
    const h = initHarness({page: 'alt'});
    h.body.classList.add('pixiv-feedback-open');
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('有其它弹窗时暂缓且不加载 SDK', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('阻塞时未消耗触发机会（不请求 Survey）', h.adapter.calls.getSurveys.length, 0);
        h.body.classList.remove('pixiv-feedback-open');
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('阻塞解除后再次触发可展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testSeenRecording() {
    return Promise.resolve().then(() => {
        const h = initHarness({page: 'batch', storage: {}});
        h.dispatchLayoutChanged('portrait', 'landscape');
        h.dispatchLayoutChanged('landscape', 'portrait');
        const seen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('layout changed 更新 seen', seen['pixiv-batch-portrait'] && seen['pixiv-batch-portrait'].lastSeenAt > 0);
        ok('seen 记录两个布局', h.api._internals.distinctSeenCount(seen) === 2);
    }).then(() => {
        const h = initHarness({page: 'batch', storage: {}, batchLayout: 'portrait'});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('batch 页触发事件不展示（无需体验阈值语义）', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: {}});
        const seen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('alt 页面记录 pixiv-batch-alt', seen['pixiv-batch-alt'] && seen['pixiv-batch-alt'].lastSeenAt > 0);
    });
}

function testLanguageSwitchPreservesInput() {
    const h = initHarness({i18nMessages: {}, batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-portrait');
        h.textarea().value = '保留的建议';
        h.textarea().dispatchEvent({type: 'input'});
        const en = createFakeI18n({
            'layout-feedback:title': 'Help us choose the default layout',
            'layout-feedback:option-portrait': 'Portrait classic layout',
            'layout-feedback:submit': 'Submit feedback'
        });
        h.api.refreshLanguage(en);
        eq('语言切换不创建第二个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        const radio = h.radios().find(r => r.checked);
        eq('语言切换保留布局选择', radio && radio.value, 'pixiv-batch-portrait');
        eq('语言切换保留建议文本', h.textarea().value, '保留的建议');
        eq('语言切换刷新文案', h.document.getElementById('plf-title').textContent, 'Help us choose the default layout');
        eq('语言切换不重复 shown', captureEvents(h).filter(e => e === 'survey shown').length, 1);
    });
}

function testReducedMotionAndA11yBasics() {
    ok('CSS 遵循 prefers-reduced-motion', CSS.indexOf('@media (prefers-reduced-motion: reduce)') >= 0);
    ok('嵌入调查使用下载工作台蓝色主题', /html\.plf-embedded-page\s*\{[^}]*--brand:\s*#0096fa;/s.test(CSS));
    ok('嵌入调查为输入焦点框保留横向空间', /\.plf-embedded-page \.plf-backdrop\s*\{[^}]*padding:\s*0 3px;/s.test(CSS));
    ok('建议输入框使用边框盒宽度', /\.plf-suggestion-input\s*\{[^}]*width:\s*100%;[^}]*min-width:\s*0;/s.test(CSS));
    ok('嵌入页按调查内容而非 iframe 视口测量高度',
        EMBED_SOURCE.includes("document.querySelector('.plf-backdrop') || statusElement")
        && !EMBED_SOURCE.includes('document.body.getBoundingClientRect().height'));
    ok('嵌入页不重复回报相同高度', EMBED_SOURCE.includes('height === lastReportedHeight'));
    ok('模块使用 aria-modal', SOURCE.indexOf("'aria-modal'") >= 0);
    ok('模块使用 aria-labelledby', SOURCE.indexOf("'aria-labelledby'") >= 0);
    ok('模块使用 aria-describedby', SOURCE.indexOf("'aria-describedby'") >= 0);
    ok('模块使用原生 radio', SOURCE.indexOf("input.type = 'radio'") >= 0);
    ok('模块使用 radiogroup', SOURCE.indexOf("'radiogroup'") >= 0);
    ok('模块处理 Tab 焦点陷阱', SOURCE.indexOf("event.key !== 'Tab'") >= 0);
    ok('模块处理 Escape', SOURCE.indexOf("event.key === 'Escape'") >= 0);
    ok('模块使用 aria-live 错误', SOURCE.indexOf("'aria-live'") >= 0);
    ok('模块使用 aria-busy', SOURCE.indexOf("'aria-busy'") >= 0);
    ok('不使用 innerHTML 插入用户输入', SOURCE.indexOf('innerHTML') < 0);
    ok('不使用 inline onclick', SOURCE.indexOf('onclick') < 0);
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        const dialog = h.dialog();
        eq('弹窗 role=dialog', dialog.getAttribute('role'), 'dialog');
        eq('弹窗 aria-modal', dialog.getAttribute('aria-modal'), 'true');
        eq('弹窗 aria-labelledby', dialog.getAttribute('aria-labelledby'), 'plf-title');
        eq('弹窗 aria-describedby', dialog.getAttribute('aria-describedby'), 'plf-description');
        ok('关闭按钮有 aria-label', h.actionButton('close').getAttribute('aria-label') !== null);
    });
}

function testCurrentLayoutBadge() {
    const h = initHarness({batchLayout: 'portrait'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        const cards = h.document.querySelectorAll('.plf-card');
        eq('三张布局卡片', cards.length, 3);
        const current = cards.find(c => c.getAttribute('data-plf-layout') === 'pixiv-batch-portrait');
        const badge = current.querySelector('.plf-current-badge');
        ok('当前布局徽标显示', badge && badge.hidden === false);
        const other = cards.find(c => c.getAttribute('data-plf-layout') === 'pixiv-batch-landscape');
        const otherBadge = other.querySelector('.plf-current-badge');
        ok('非当前布局徽标隐藏', otherBadge && otherBadge.hidden === true);
    });
}

function testCaptureResultAcceptanceMatrix() {
    return Promise.resolve().then(() => submitWithCaptureOverride(null)).then(h => {
        eq('CaptureResult 对象 → 写 submitted',
            JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        eq('CaptureResult 对象 → 关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('CaptureResult 对象 → 成功 Toast 恰好一次', h.toastCalls.length, 1);
    }).then(() => submitWithCaptureOverride('undefined')).then(h => {
        eq('capture 返回 undefined → 不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        eq('capture 返回 undefined → 保留弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('capture 返回 undefined → 显示可重试错误', h.error().hidden, false);
        eq('capture 返回 undefined → 不显示成功 Toast', h.toastCalls.length, 0);
    }).then(() => submitWithCaptureOverride('null')).then(h => {
        eq('capture 返回 null → 不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        eq('capture 返回 null → 保留弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
    }).then(() => submitWithCaptureOverride('false')).then(h => {
        eq('capture 返回 false → 不写 submitted（防御兼容）', h.storage.getItem(STATE_KEY) === null, true);
        eq('capture 返回 false → 保留弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
    }).then(() => submitWithCaptureOverride('throw')).then(h => {
        eq('capture 同步抛错 → 不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        eq('capture 同步抛错 → 保留弹窗可重试', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testBeforeSendTopLevelFields() {
    const filter = initHarness({}).api._internals.beforeSendFilter;
    const withSet = {
        uuid: 'evt-1',
        event: 'survey sent',
        timestamp: '2026-01-01T00:00:00.000Z',
        $set: {'$survey_test_responded': true},
        $set_once: {'$initial_test': 'x'},
        $unset: ['old'],
        properties: {
            distinct_id: 'anon-1',
            token: 'phc_x',
            '$survey_id': 's1',
            '$survey_response_q-layout': 'pixiv-batch-portrait',
            '$survey_response_q-suggestion': 'keep me',
            $current_url: 'http://localhost:6999/pixiv-batch.html'
        }
    };
    const out = filter(withSet);
    eq('顶层 $set 被删除', out.$set, undefined);
    eq('顶层 $set_once 被删除', out.$set_once, undefined);
    eq('顶层 $unset 被删除', out.$unset, undefined);
    eq('删除 uuid', out.uuid, undefined);
    eq('保留 event', out.event, 'survey sent');
    eq('保留 timestamp', out.timestamp, '2026-01-01T00:00:00.000Z');
    ok('保留 distinct_id / token / $survey_id', out.properties.distinct_id === 'anon-1'
        && out.properties.token === 'phc_x' && out.properties.$survey_id === 's1');
    eq('Survey response 不丢失', out.properties['$survey_response_q-layout'], 'pixiv-batch-portrait');
    eq('建议响应不丢失', out.properties['$survey_response_q-suggestion'], 'keep me');
    eq('环境属性仍被过滤', out.properties.$current_url, undefined);
    ok('输出不携带多余顶层字段', Object.keys(out).every(k => ['event', 'timestamp', 'properties'].indexOf(k) >= 0));
    eq('非 Survey 事件仍返回 null', filter({uuid: 'e', event: '$pageview', properties: {}}), null);
}

function testSdkInitCapturesConfigForBeforeSend() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        ok('fake adapter 保存了 SDK config', h.adapter.sdkConfig() !== null);
        eq('config 含 before_send', typeof h.adapter.sdkConfig().before_send, 'function');
    });
}

function testDntGateSilentSkip() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], optedOut: true}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('DNT opt-out 时不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('DNT opt-out 不调用 getActiveMatchingSurveys', h.adapter.calls.getSurveys.length, 0);
        eq('DNT opt-out 不调用 capture', h.adapter.calls.capture.length, 0);
        eq('DNT opt-out 不写任何反馈状态', h.storage.getItem(STATE_KEY) === null, true);
        eq('DNT opt-out 无错误提示', h.document.querySelectorAll('[data-plf-error]').length, 0);
    });
}

function testIsCapturingFalseSilentSkip() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], isCapturing: false}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('is_capturing() false 时不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('is_capturing() false 不请求 Survey', h.adapter.calls.getSurveys.length, 0);
        eq('is_capturing() false 不调用 capture', h.adapter.calls.capture.length, 0);
    });
}

function testDntGateTriggerSilent() {
    const h = initHarness({
        page: 'alt',
        adapter: createFakeAdapter({surveys: [defaultSurvey()], optedOut: true})
    });
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('DNT opt-out 触发不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('DNT opt-out 触发不发 shown', h.adapter.calls.capture.length, 0);
        eq('DNT opt-out 触发不写状态', h.storage.getItem(STATE_KEY) === null, true);
    });
}

function testDntGateNormalCapturingStillShows() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], optedOut: false}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('正常 capturing 状态仍可显示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('正常 capturing 请求 Survey', h.adapter.calls.getSurveys.length, 1);
    });
}

function testFirstDownloadTriggersOnce() {
    const h = initHarness({page: 'alt'});
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('首次下载完成触发启动一次 Survey 流程', h.adapter.calls.getSurveys.length, 1);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('重复事件不启动第二个 Survey 流程', h.adapter.calls.getSurveys.length, 1);
        eq('重复事件不重复 shown', h.adapter.calls.capture
            .filter(c => c.name === 'survey shown').length, 1);
        eq('弹窗最多一个', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testSyncFlagsCallbackRace() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()], syncFlagsCallback: true});
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('同步 callback 后 off 被调用', (adapter.calls.offCalls || 0) >= 1, true);
        eq('getActiveMatchingSurveys 只调用一次', adapter.calls.getSurveys.length, 1);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('同步 flags 场景正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testSyncFlagsCallbackWithStalledSurveys() {
    const adapter = createFakeAdapter({
        surveys: [defaultSurvey()],
        syncFlagsCallback: true,
        stallSurveys: true
    });
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('flags 超时与同步 callback 竞争只请求一次', adapter.calls.getSurveys.length, 1);
        h.timers.advance(40000);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('竞争场景总超时后安全结束', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('竞争场景 off 已注销', (adapter.calls.offCalls || 0) >= 1, true);
    });
}

function testDestroyCancelsSurveyFetch() {
    const adapter = createFakeAdapter({
        surveys: [defaultSurvey()],
        syncFlagsCallback: true,
        stallSurveys: true
    });
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        h.timers.advance(50000);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('destroy 后不再展示调查', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后不留下活动 flags 监听', (adapter.calls.offCalls || 0) >= 1, true);
    });
}

function testCrossTabSubmittedClosesOtherTab() {
    const h2 = initHarness({batchLayout: 'landscape'});
    return h2.api.open().then(() => waitForFlush()).then(() => {
        eq('标签页 B 弹窗已打开', h2.document.querySelectorAll('.plf-backdrop').length, 1);
        const submitted = crossTabState('submitted');
        h2.storage.values.set(STATE_KEY, submitted);
        h2.dispatchStorage(STATE_KEY, submitted);
        return waitForFlush();
    }).then(() => {
        eq('标签页 B 收到 submitted storage 事件后关闭', h2.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('标签页 B 不发送第二条 dismissed', captureEvents(h2).indexOf('survey dismissed'), -1);
        eq('标签页 B 不写状态覆盖', JSON.parse(h2.storage.getItem(STATE_KEY)).status, 'submitted');
        ok('标签页 B 显示非阻塞提示', h2.toastCalls.length === 1);
    });
}

function testCrossTabNeverAndSnoozeKeepOpenForm() {
    return Promise.resolve().then(() => {
        const h2 = initHarness({batchLayout: 'landscape'});
        return h2.api.open().then(() => waitForFlush()).then(() => {
            const never = crossTabState('never');
            h2.storage.values.set(STATE_KEY, never);
            h2.dispatchStorage(STATE_KEY, never);
            return waitForFlush();
        }).then(() => {
            eq('never 不关闭已打开表单', h2.document.querySelectorAll('.plf-backdrop').length, 1);
            eq('never 不显示已处理提示', h2.toastCalls.length, 0);
        });
    }).then(() => {
        const h3 = initHarness({batchLayout: 'landscape'});
        return h3.api.open().then(() => waitForFlush()).then(() => {
            const snoozed = crossTabState('snoozed', 2000000);
            h3.storage.values.set(STATE_KEY, snoozed);
            h3.dispatchStorage(STATE_KEY, snoozed);
            return waitForFlush();
        }).then(() => {
            eq('有效 snooze 不关闭已打开表单', h3.document.querySelectorAll('.plf-backdrop').length, 1);
            eq('有效 snooze 不显示已处理提示', h3.toastCalls.length, 0);
        });
    });
}

function testFreshCheckPreventsDuplicateSubmit() {
    // 标签页 B：弹窗打开、已选布局，但在提交瞬间另一标签页已写入 submitted。
    const h2 = initHarness({batchLayout: 'landscape'});
    return h2.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h2, 'pixiv-batch-portrait');
        h2.storage.values.set(STATE_KEY, crossTabState('submitted'));
        h2.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('提交前 fresh check 阻止重复提交', captureEvents(h2).filter(e => e === 'survey sent').length, 0);
        eq('fresh check 拦截后关闭弹窗', h2.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('不额外发送 dismissed', captureEvents(h2).indexOf('survey dismissed'), -1);
    });
}

function testUnicodeLengthMatrix() {
    const emoji = '\ud83d\ude00';
    return Promise.resolve().then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.textarea().value = 'a'.repeat(1000);
            h.textarea().dispatchEvent({type: 'input'});
            eq('1000 个普通字符允许', h.counter().textContent.split(' ')[0], '1000');
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('1000 个普通字符可提交', captureProps(h, 'survey sent')['$survey_response_q-suggestion'].length, 1000);
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.textarea().value = 'a'.repeat(1001);
            h.textarea().dispatchEvent({type: 'input'});
            eq('1001 个普通字符截断为 1000', h.textarea().value.length, 1000);
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.textarea().value = emoji.repeat(1000);
            h.textarea().dispatchEvent({type: 'input'});
            eq('1000 个 Emoji 计数器一致', h.counter().textContent.split(' ')[0], '1000');
            eq('1000 个 Emoji 允许（2000 UTF-16 单元不受 maxlength 影响）', h.textarea().value.length, 2000);
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const sent = captureProps(h, 'survey sent')['$survey_response_q-suggestion'];
            eq('Emoji 提交校验与计数器一致', Array.from(sent).length, 1000);
            ok('Emoji 提交内容完整无孤立代理', !hasLoneSurrogates(sent) && sent === emoji.repeat(1000));
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.textarea().value = emoji.repeat(1001);
            h.textarea().dispatchEvent({type: 'input'});
            eq('1001 个 Emoji 截断为 1000 个 code point', h.counter().textContent.split(' ')[0], '1000');
            eq('截断后为 2000 个 UTF-16 单元', h.textarea().value.length, 2000);
            ok('截断结果不包含孤立代理项', !hasLoneSurrogates(h.textarea().value));
            eq('截断结果为完整 Emoji 序列', h.textarea().value, emoji.repeat(1000));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('截断后 Emoji 可提交', captureProps(h, 'survey sent')['$survey_response_q-suggestion'] !== undefined, true);
        });
    });
}

runTests('pixiv-layout-feedback-local-state.test.js', [
    ['testSubmittedNeverSnoozedGatesTrigger', testSubmittedNeverSnoozedGatesTrigger],
    ['testCorruptStateIsCleaned', testCorruptStateIsCleaned],
    ['testCorruptStateRemoveThrowsStillSafe', testCorruptStateRemoveThrowsStillSafe],
    ['testStorageThrowSafe', testStorageThrowSafe],
    ['testCrossTabStorageSync', testCrossTabStorageSync],
    ['testSdkLoadFailure', testSdkLoadFailure],
    ['testSdkLoadSuccessThroughScript', testSdkLoadSuccessThroughScript],
    ['testSdkLoadTimeout', testSdkLoadTimeout],
    ['testFlagsTimeout', testFlagsTimeout],
    ['testSurveyFetchTimeout', testSurveyFetchTimeout],
    ['testPreloadWarmsSdkBeforeFirstDownload', testPreloadWarmsSdkBeforeFirstDownload],
    ['testPreloadLoadsSdkScriptEarly', testPreloadLoadsSdkScriptEarly],
    ['testPreloadSkipsSdkInitWhenStateBlocks', testPreloadSkipsSdkInitWhenStateBlocks],
    ['testPreloadBeforeInitIsNoop', testPreloadBeforeInitIsNoop],
    ['testDisabledConfigDoesNothing', testDisabledConfigDoesNothing],
    ['testMissingPostHogPluginTreatedAsDisabled', testMissingPostHogPluginTreatedAsDisabled],
    ['testFirstDownloadTriggerConditions', testFirstDownloadTriggerConditions],
    ['testTriggerBlockedOverlaySkipsThenAllows', testTriggerBlockedOverlaySkipsThenAllows],
    ['testSeenRecording', testSeenRecording],
    ['testLanguageSwitchPreservesInput', testLanguageSwitchPreservesInput],
    ['testReducedMotionAndA11yBasics', testReducedMotionAndA11yBasics],
    ['testCurrentLayoutBadge', testCurrentLayoutBadge],
    ['testCaptureResultAcceptanceMatrix', testCaptureResultAcceptanceMatrix],
    ['testBeforeSendTopLevelFields', testBeforeSendTopLevelFields],
    ['testSdkInitCapturesConfigForBeforeSend', testSdkInitCapturesConfigForBeforeSend],
    ['testDntGateSilentSkip', testDntGateSilentSkip],
    ['testIsCapturingFalseSilentSkip', testIsCapturingFalseSilentSkip],
    ['testDntGateTriggerSilent', testDntGateTriggerSilent],
    ['testDntGateNormalCapturingStillShows', testDntGateNormalCapturingStillShows],
    ['testFirstDownloadTriggersOnce', testFirstDownloadTriggersOnce],
    ['testSyncFlagsCallbackRace', testSyncFlagsCallbackRace],
    ['testSyncFlagsCallbackWithStalledSurveys', testSyncFlagsCallbackWithStalledSurveys],
    ['testDestroyCancelsSurveyFetch', testDestroyCancelsSurveyFetch],
    ['testCrossTabSubmittedClosesOtherTab', testCrossTabSubmittedClosesOtherTab],
    ['testCrossTabNeverAndSnoozeKeepOpenForm', testCrossTabNeverAndSnoozeKeepOpenForm],
    ['testFreshCheckPreventsDuplicateSubmit', testFreshCheckPreventsDuplicateSubmit],
    ['testUnicodeLengthMatrix', testUnicodeLengthMatrix]
]).catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
