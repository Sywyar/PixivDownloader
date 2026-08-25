'use strict';

/** 布局映射、问卷 schema、对话框提交与隐私过滤。 */
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

function testEmbeddedSurveyPublicationStates() {
    const available = initHarness({
        currentLayoutId: 'pixiv-batch-portrait',
        adapter: createFakeAdapter({surveys: [defaultSurvey()]})
    });
    return available.api.openEmbedded().then(result => waitForFlush().then(() => {
        eq('嵌入调查可打开', result.status, 'opened');
        eq('嵌入调查强制读取完整发布列表', available.adapter.calls.getAllSurveys[0].forceReload, true);
        eq('嵌入调查使用显式当前布局', available.api.currentLayoutId(), 'pixiv-batch-portrait');
    }).then(() => {
        const submitted = initHarness({
            storage: {[STATE_KEY]: crossTabState('submitted')},
            adapter: createFakeAdapter({surveys: [defaultSurvey()]})
        });
        return submitted.api.openEmbedded().then(result => {
            eq('嵌入调查只阻断已提交状态', result.status, 'blocked');
            eq('已提交时不请求发布列表', submitted.adapter.calls.getAllSurveys.length, 0);
        });
    }).then(() => {
        const never = initHarness({
            storage: {[STATE_KEY]: crossTabState('never')},
            adapter: createFakeAdapter({surveys: [defaultSurvey()]})
        });
        return never.api.openEmbedded().then(result => {
            eq('嵌入调查不受 never 阻断', result.status, 'opened');
        });
    }).then(() => {
        const snoozed = initHarness({
            storage: {[STATE_KEY]: crossTabState('snoozed', 2000000)},
            adapter: createFakeAdapter({surveys: [defaultSurvey()]})
        });
        return snoozed.api.openEmbedded().then(result => {
            eq('嵌入调查不受有效 snooze 阻断', result.status, 'opened');
        });
    })).then(() => {
        const removed = initHarness({
            adapter: createFakeAdapter({surveys: [defaultSurvey()], publishedSurveys: []})
        });
        return removed.api.openEmbedded().then(result => {
            eq('完整发布列表确认目标不存在', result.status, 'removed');
            eq('已删除调查不再请求 active matching', removed.adapter.calls.getSurveys.length, 0);
        });
    }).then(() => {
        const closedSurvey = defaultSurvey();
        closedSurvey.end_date = '2026-08-12T00:00:00Z';
        const closed = initHarness({
            adapter: createFakeAdapter({surveys: [closedSurvey]})
        });
        return closed.api.openEmbedded().then(result => {
            eq('完整发布列表确认目标已关闭', result.status, 'removed');
            eq('已关闭调查不再请求 active matching', closed.adapter.calls.getSurveys.length, 0);
        });
    }).then(() => {
        const unavailable = initHarness({
            adapter: createFakeAdapter({surveys: [defaultSurvey()], surveyLoadFailed: true})
        });
        return unavailable.api.openEmbedded().then(result => {
            eq('完整发布列表网络失败不误判删除', result.status, 'unavailable');
        });
    }).then(() => {
        const ineligible = initHarness({
            adapter: createFakeAdapter({surveys: [], publishedSurveys: [defaultSurvey()]})
        });
        return ineligible.api.openEmbedded().then(result => {
            eq('调查仍发布但当前身份不匹配时保留站内信', result.status, 'ineligible');
        });
    });
}

function testLayoutMapping() {
    let h = initHarness({page: 'batch', batchLayout: 'landscape'});
    eq('landscape 映射为稳定 ID', h.api.currentLayoutId(), 'pixiv-batch-landscape');

    h = initHarness({page: 'batch', batchLayout: 'portrait'});
    eq('portrait 映射为稳定 ID', h.api.currentLayoutId(), 'pixiv-batch-portrait');

    h = initHarness({page: 'alt'});
    eq('alt 页面固定返回 pixiv-batch-alt', h.api.currentLayoutId(), 'pixiv-batch-alt');

    h = initHarness({page: 'batch', batchLayout: 'landscape'});
    h.document.documentElement.setAttribute('data-batch-layout', 'portrait');
    eq('dataset 变化后 currentLayoutId 跟随', h.api.currentLayoutId(), 'pixiv-batch-portrait');

    ok('模块不使用物理屏幕方向 API',
        SOURCE.indexOf('matchMedia') < 0
        && SOURCE.indexOf('orientation') < 0
        && SOURCE.indexOf('screen.orientation') < 0);
    ok('模块不使用宽高比判断', SOURCE.indexOf('aspect-ratio') < 0 && SOURCE.indexOf('innerWidth') < 0);
}

function testInitDestroy() {
    const h = initHarness({});
    const before = (h.document._events && h.document._events.get('pixiv:batch-layout-changed') || []).length;
    const storageBefore = h.windowEvents.listenerCount('storage');
    doesNotThrow('init 幂等', () => h.api.init({page: 'batch'}));
    const after = (h.document._events && h.document._events.get('pixiv:batch-layout-changed') || []).length;
    eq('init 重复调用不重复注册监听', after, before);
    eq('storage 监听不重复', h.windowEvents.listenerCount('storage'), storageBefore);

    doesNotThrow('destroy 安全', () => h.api.destroy());
    doesNotThrow('destroy 可重复调用', () => h.api.destroy());

    const afterDestroy = (h.document._events && h.document._events.get('pixiv:batch-layout-changed') || []).length;
    eq('destroy 移除布局监听', afterDestroy, 0);
    eq('destroy 移除 storage 监听', h.windowEvents.listenerCount('storage'), 0);

    doesNotThrow('destroy 后可重新 init', () => h.api.init({page: 'batch'}));
}

function testSingleDialogAtMostOne() {
    const h = initHarness({});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('open 后恰好一个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('重复 open 不创建第二个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testChoiceSchemaVariants() {
    const internals = initHarness({}).api._internals;

    const valid = defaultSurvey();
    ok('合法单选用例通过', internals.resolveChoiceQuestion(valid) !== null);

    const noId = defaultSurvey();
    noId.questions[0].id = '';
    eq('id 空则无效', internals.resolveChoiceQuestion(noId), null);

    const wrongType = defaultSurvey();
    wrongType.questions[0].type = 'multiple_choice';
    eq('type 非 single_choice 无效', internals.resolveChoiceQuestion(wrongType), null);

    const optional = defaultSurvey();
    optional.questions[0].optional = true;
    eq('optional=true 无效', internals.resolveChoiceQuestion(optional), null);

    const missingChoice = defaultSurvey();
    missingChoice.questions[0].choices = [
        {id: 'pixiv-batch-landscape'},
        {id: 'pixiv-batch-portrait'}
    ];
    eq('choices 缺 ID 无效', internals.resolveChoiceQuestion(missingChoice), null);

    const extraChoice = defaultSurvey();
    extraChoice.questions[0].choices.push({id: 'pixiv-batch-unknown'});
    eq('choices 多出未知选项无效', internals.resolveChoiceQuestion(extraChoice), null);

    const shuffled = defaultSurvey();
    shuffled.questions[0].choices = shuffled.questions[0].choices.slice().reverse();
    ok('不依赖后台选项顺序', internals.resolveChoiceQuestion(shuffled) !== null);

    const stringChoices = defaultSurvey();
    stringChoices.questions[0].choices = ['pixiv-batch-alt', 'pixiv-batch-landscape', 'pixiv-batch-portrait'];
    ok('choices 字符串数组兼容', internals.resolveChoiceQuestion(stringChoices) !== null);

    const duplicate = defaultSurvey();
    duplicate.questions.push(JSON.parse(JSON.stringify(duplicate.questions[0])));
    eq('多个匹配单选题视为 schema 歧义', internals.resolveChoiceQuestion(duplicate), null);
}

function testSuggestionSchemaVariants() {
    const internals = initHarness({}).api._internals;

    eq('缺失建议题返回 q-suggestion', internals.resolveSuggestionQuestion(defaultSurvey()).id, 'q-suggestion');
    const noSuggestion = defaultSurvey();
    noSuggestion.questions = [noSuggestion.questions[0]];
    eq('第二题缺失返回 null', internals.resolveSuggestionQuestion(noSuggestion), null);

    const openText = defaultSurvey();
    openText.questions[1].type = 'open_text';
    eq('open_text 兼容', internals.resolveSuggestionQuestion(openText).id, 'q-suggestion');

    const required = defaultSurvey();
    required.questions[1].optional = false;
    eq('optional=false 视为异常不显示 textarea', internals.resolveSuggestionQuestion(required), null);

    const ambiguous = defaultSurvey();
    ambiguous.questions.push({type: 'open', id: 'q-other', optional: true});
    eq('多个开放题不猜测', internals.resolveSuggestionQuestion(ambiguous), null);

    const noIdSuggestion = defaultSurvey();
    noIdSuggestion.questions[1].id = '';
    eq('建议题 id 为空视为缺失', internals.resolveSuggestionQuestion(noIdSuggestion), null);
}

function testSurveyNotFoundOrHidden() {
    const h = initHarness({surveys: []});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('无目标调查时不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
    }).then(() => {
        const h2 = initHarness({surveys: [Object.assign({}, defaultSurvey(), {id: 'other-id'})]});
        return h2.api.open().then(() => waitForFlush()).then(() => {
            eq('surveyId 不匹配不展示', h2.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h3 = initHarness({surveys: [Object.assign({}, defaultSurvey(), {type: 'popover'})]});
        return h3.api.open().then(() => waitForFlush()).then(() => {
            eq('type 非 api 不展示', h3.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

function testSurveyShownOncePerDialog() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('shown 发送一次', captureEvents(h).filter(e => e === 'survey shown').length, 1);
        h.api.refreshLanguage(createFakeI18n({}));
        h.api.refreshLanguage(createFakeI18n({}));
        eq('语言切换不重复 shown', captureEvents(h).filter(e => e === 'survey shown').length, 1);
        const shown = captureProps(h, 'survey shown');
        eq('shown 带 $survey_id', shown['$survey_id'], h.config.surveyId);
        eq('shown 带 survey_schema_version', shown.survey_schema_version, '1');
        eq('shown 带 current_layout', shown.current_layout, 'pixiv-batch-landscape');
        eq('shown 带 app_version', shown.app_version, '1.2.3');
    });
}

function testSubmitSendsOnceWithQuestionId() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-portrait');
        const submit = h.submitButton();
        eq('选择后提交按钮可用', submit.disabled, false);
        submit.click();
        submit.click();
        submit.dispatchEvent({type: 'click'});
        return waitForFlush();
    }).then(() => {
        const sent = captureEvents(h).filter(e => e === 'survey sent');
        eq('双击 / 重复触发只发一次 survey sent', sent.length, 1);
        eq('survey sent 使用服务端稳定提交 UUID', ackEvents(h)[0].uuid, SUBMISSION_ID);
        const props = captureProps(h, 'survey sent');
        eq('回答属性使用 question.id', props['$survey_response_q-layout'], 'pixiv-batch-portrait');
        ok('回答属性不以数组位置构造', Object.keys(props).every(k => k !== '$survey_response' && k !== '$survey_response_1'));
        eq('sent 带 $survey_id', props['$survey_id'], h.config.surveyId);
        eq('sent 带 app_version', props.app_version, '1.2.3');
        eq('sent 带 current_layout 快照', props.current_layout, 'pixiv-batch-landscape');
        const state = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('提交后写 submitted', state.status, 'submitted');
        eq('submitted 绑定 surveyId', state.surveyId, h.config.surveyId);
        eq('提交后关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('提交后 toast 成功', h.toastCalls.length, 1);
    });
}

function testThreeLayoutsSubmit() {
    const promises = LAYOUT_IDS.map(layoutId => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, layoutId);
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const props = captureProps(h, 'survey sent');
            eq('稳定 ID ' + layoutId + ' 可提交', props['$survey_response_q-layout'], layoutId);
        });
    });
    return Promise.all(promises);
}

function testSuggestionHandling() {
    return Promise.resolve().then(() => {
        const h1 = initHarness({batchLayout: 'landscape'});
        return h1.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h1, 'pixiv-batch-landscape');
            h1.textarea().value = '  信息密度可以更高  ';
            h1.textarea().dispatchEvent({type: 'input'});
            h1.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const props = captureProps(h1, 'survey sent');
            eq('建议 trim 后发送', props['$survey_response_q-suggestion'], '信息密度可以更高');
        });
    }).then(() => {
        const h2 = initHarness({batchLayout: 'landscape'});
        return h2.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h2, 'pixiv-batch-alt');
            h2.textarea().value = '   ';
            h2.textarea().dispatchEvent({type: 'input'});
            h2.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const props = captureProps(h2, 'survey sent');
            ok('纯空白建议不发送第二题属性', Object.keys(props).every(k => k.indexOf('$survey_response_q-suggestion') !== 0));
            ok('不发送空字符串', props['$survey_response_q-suggestion'] === undefined);
        });
    }).then(() => {
        const h3 = initHarness({batchLayout: 'landscape'});
        return h3.api.open().then(() => waitForFlush()).then(() => {
            ok('不再使用 UTF-16 语义的原生 maxlength', h3.textarea().getAttribute('maxlength') === null);
            selectChoice(h3, 'pixiv-batch-landscape');
            const emoji = '\ud83d\ude00';
            h3.textarea().value = emoji.repeat(500);
            h3.textarea().dispatchEvent({type: 'input'});
            eq('代理对按 code point 计数', h3.counter().textContent.split(' ')[0], '500');
            h3.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const props = captureProps(h3, 'survey sent');
            ok('1000 个 code point 内可提交', props['$survey_response_q-suggestion'] !== undefined);
        });
    }).then(() => {
        const h4 = initHarness({batchLayout: 'landscape'});
        return h4.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h4, 'pixiv-batch-landscape');
            h4.textarea().value = 'a'.repeat(1001);
            h4.textarea().dispatchEvent({type: 'input'});
            eq('input 事件把 1001 个字符截断为 1000', h4.textarea().value.length, 1000);
            eq('截断后计数器显示 1000', h4.counter().textContent.split(' ')[0], '1000');
            h4.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const props = captureProps(h4, 'survey sent');
            eq('截断后按 1000 code point 提交', props['$survey_response_q-suggestion'], 'a'.repeat(1000));
        });
    });
}

function testSuggestionMissingHidesTextarea() {
    const survey = defaultSurvey();
    survey.questions = [survey.questions[0]];
    const h = initHarness({surveys: [survey], batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('第二题缺失时不显示 textarea', h.textarea() === null || h.textarea().parentNode.hidden, true);
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        const props = captureProps(h, 'survey sent');
        ok('第二题缺失时只收集布局选择', Object.keys(props).some(k => k === '$survey_response_q-layout'));
    });
}

function testNoChoiceCannotSubmit() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('未选择前提交按钮禁用', h.submitButton().disabled, true);
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('未选择不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
        eq('未选择显示错误', h.error().hidden, false);
    });
}

function testSnoozeNeverDismissSemantics() {
    return Promise.resolve().then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            h.actionButton('snooze').click();
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('稍后再说写 snoozed', state.status, 'snoozed');
            ok('snoozedUntil 约为 7 天后', Math.abs(state.snoozedUntil - (h.timers.now() + SNOOZE_MS)) < 2000);
            eq('稍后再说不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
            eq('稍后再说关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('不再询问写 never', state.status, 'never');
            eq('不再询问发送 dismissed', captureEvents(h).indexOf('survey dismissed') >= 0, true);
        });
    });
}

function testEscapeOverlayCloseSendNoDismissed() {
    return Promise.resolve().then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            h.document.dispatchEvent({type: 'keydown', key: 'Escape'});
            eq('Escape 关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('Escape 不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('Escape 等同稍后再说', state.status, 'snoozed');
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            h.actionButton('close').click();
            eq('关闭按钮等同稍后再说', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('关闭按钮不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('关闭按钮写 snoozed', state.status, 'snoozed');
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            const backdrop = h.document.querySelector('.plf-backdrop');
            backdrop.dispatchEvent({type: 'mousedown', target: backdrop});
            eq('点击遮罩关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('遮罩不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        });
    });
}

function testSubmitFailureAndRetry() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], capture: 'throw'}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.textarea().value = ' 保留的建议 ';
        h.textarea().dispatchEvent({type: 'input'});
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        const stateRaw = h.storage.getItem(STATE_KEY);
        eq('capture 同步抛错不写 submitted', stateRaw === null || JSON.parse(stateRaw).status !== 'submitted', true);
        eq('失败保留弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('失败显示错误', h.error().hidden, false);
        eq('失败恢复提交按钮', h.submitButton().disabled, false);
        const radio = h.radios().find(r => r.checked);
        eq('失败保留布局选择', radio && radio.value, 'pixiv-batch-landscape');
        eq('失败保留建议文本', h.textarea().value, ' 保留的建议 ');
        const firstSubmissionId = ackEvents(h)[0].uuid;
        h.adapter.ackOk = true;
        h.submitButton().click();
        return waitForFlush().then(() => {
            eq('远端结果不明确后重试复用同一 UUID', ackEvents(h)[1].uuid, firstSubmissionId);
            eq('远端确认后才写 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        });
    });
}

function testSubmitLockDuringInFlight() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-alt');
        h.submitButton().click();
        h.submitButton().click();
        h.submitButton().dispatchEvent({type: 'click'});
        return waitForFlush();
    }).then(() => {
        eq('提交锁保证单次 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 1);
    });
}

function testDntRejectionFailsSubmit() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], capture: 'reject'}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('SDK 拒绝事件视为提交失败', captureEvents(h).filter(e => e === 'survey sent').length, 1);
        eq('SDK 拒绝不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        eq('SDK 拒绝保留弹窗可重试', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testAppVersionUnknown() {
    const h = initHarness({fetch: 'fail', batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        const shown = captureProps(h, 'survey shown');
        eq('版本获取失败时为 unknown', shown.app_version, 'unknown');
    }).then(() => {
        const h2 = initHarness({fetch: 'no-version', batchLayout: 'landscape'});
        return h2.api.open().then(() => waitForFlush()).then(() => {
            const shown = captureProps(h2, 'survey shown');
            eq('版本字段缺失时为 unknown', shown.app_version, 'unknown');
        });
    });
}

function testBeforeSendFilter() {
    const filter = initHarness({}).api._internals.beforeSendFilter;
    const base = {
        event: 'survey sent',
        properties: {
            distinct_id: 'anon-123',
            token: 'phc_x',
            time: 123,
            $lib: 'web',
            $lib_version: '1.409.5',
            $lib_variant: 'full',
            $device_id: 'device-1',
            $session_id: 'session-1',
            $window_id: 'window-1',
            $pageview_id: 'pageview-1',
            '$survey_id': 's1',
            '$survey_response_q-layout': 'pixiv-batch-landscape',
            app_version: '1.0.0',
            current_layout: 'landscape',
            survey_schema_version: 1,
            '$current_url': 'http://localhost:6999/pixiv-batch.html',
            '$referrer': 'http://evil.example',
            '$referring_domain': 'evil.example',
            pathname: '/pixiv-batch.html',
            hostname: 'localhost',
            $browser: 'Chrome',
            $os: 'Windows',
            $screen_width: 1920
        }
    };
    const filtered = filter(base);
    ok('仅保留调查必需字段', Object.keys(filtered.properties).sort().join('|') === [
        '$survey_id', '$survey_response_q-layout', 'app_version', 'current_layout',
        'distinct_id', 'survey_schema_version', 'token'
    ].sort().join('|'));
    ok('删除 SDK 设备、会话、页面和版本属性', [
        'time', '$lib', '$lib_version', '$lib_variant', '$device_id', '$session_id',
        '$window_id', '$pageview_id'
    ].every(key => filtered.properties[key] === undefined));
    ok('删除 $current_url', filtered.properties.$current_url === undefined);
    ok('删除 $referrer', filtered.properties.$referrer === undefined);
    ok('删除 $referring_domain', filtered.properties.$referring_domain === undefined);
    ok('删除 pathname', filtered.properties.pathname === undefined);
    ok('删除 hostname', filtered.properties.hostname === undefined);
    ok('删除无关浏览器环境属性', filtered.properties.$browser === undefined
        && filtered.properties.$os === undefined
        && filtered.properties.$screen_width === undefined);

    eq('拒绝 $pageview', filter({event: '$pageview', properties: {}}), null);
    eq('拒绝 $pageleave', filter({event: '$pageleave', properties: {}}), null);
    eq('拒绝 $autocapture', filter({event: '$autocapture', properties: {}}), null);
    eq('拒绝 $exception', filter({event: '$exception', properties: {}}), null);
    eq('拒绝 $web_vitals', filter({event: '$web_vitals', properties: {}}), null);
    eq('拒绝 $snapshot', filter({event: '$snapshot', properties: {}}), null);
    eq('拒绝 dead click', filter({event: '$dead_click', properties: {}}), null);
    eq('拒绝 rage click', filter({event: '$rageclick', properties: {}}), null);
    eq('拒绝任意非调查事件', filter({event: 'custom_event', properties: {}}), null);
    ok('放行 survey shown', filter({event: 'survey shown', properties: {distinct_id: 'a'}}) !== null);
    ok('放行 survey dismissed', filter({event: 'survey dismissed', properties: {distinct_id: 'a'}}) !== null);
}

function testSdkInitConfigPrivacy() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        const init = h.adapter.calls.init[0];
        eq('init 使用 projectToken', init.token, h.config.projectToken);
        const c = init.config;
        eq('api_host 来自配置', c.api_host, h.config.apiHost);
        eq('ui_host 来自配置', c.ui_host, h.config.uiHost);
        eq('autocapture 关闭', c.autocapture, false);
        eq('pageview 关闭', c.capture_pageview, false);
        eq('pageleave 关闭', c.capture_pageleave, false);
        eq('replay 关闭', c.disable_session_recording, true);
        eq('heatmap 关闭（capture_heatmaps）', c.capture_heatmaps, false);
        eq('弃用的 enable_heatmaps 不再作为配置字段', c.enable_heatmaps, undefined);
        eq('error tracking 关闭', c.capture_exceptions, false);
        eq('web vitals 关闭', c.capture_performance, false);
        eq('dead clicks 关闭', c.capture_dead_clicks, false);
        eq('surveys 保持启用', c.disable_surveys, false);
        eq('person_profiles 不创建匿名 Person', c.person_profiles, 'identified_only');
        eq('persistence 仅使用内存', c.persistence, 'memory');
        eq('SDK persistence 显式关闭', c.disable_persistence, true);
        eq('cross_subdomain_cookie 关闭', c.cross_subdomain_cookie, false);
        eq('DNT 尊重', c.respect_dnt, true);
        eq('campaign params 关闭', c.save_campaign_params, false);
        eq('referrer 不保存', c.save_referrer, false);
        eq('rageclick 关闭', c.rageclick, false);
        eq('SDK 默认 Survey 自动展示关闭', c.disable_surveys_automatic_display, true);
        eq('flags 只评估 Survey 相关', c.advanced_only_evaluate_survey_feature_flags, true);
        eq('外部脚本依赖关闭', c.disable_external_dependency_loading, true);
        eq('flags 请求超时较短', c.feature_flag_request_timeout_ms, 5000);
        eq('surveys 请求超时较短', c.surveys_request_timeout_ms, 15000);
        ok('before_send 已注册', typeof c.before_send === 'function');
    });
}

function testPayloadPrivacy() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        const allProps = [];
        h.adapter.calls.capture.forEach(c => allProps.push(c.properties));
        const forbidden = ['cookie', 'PHPSESSID', 'user_id', 'username', 'displayname', 'session',
            'authorization', 'artwork', 'novel', 'path', 'download', 'directory', 'filename'];
        const json = JSON.stringify(allProps).toLowerCase();
        forbidden.forEach(term => {
            ok('payload 不含 ' + term, json.indexOf(term) < 0);
        });
        ok('payload 不含 URL / referrer', json.indexOf('http') < 0 && json.indexOf('referrer') < 0);
    });
}

function testSuggestionNeverLogged() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.textarea().value = 'SECRET_SUGGESTION_TOP_SECRET';
        h.textarea().dispatchEvent({type: 'input'});
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        const logs = JSON.stringify(h.consoleWarn);
        ok('用户建议不进入日志', logs.indexOf('SECRET_SUGGESTION_TOP_SECRET') < 0);
    });
}

runTests('pixiv-layout-feedback-dialog.test.js', [
    ['testEmbeddedSurveyPublicationStates', testEmbeddedSurveyPublicationStates],
    ['testLayoutMapping', testLayoutMapping],
    ['testInitDestroy', testInitDestroy],
    ['testSingleDialogAtMostOne', testSingleDialogAtMostOne],
    ['testChoiceSchemaVariants', testChoiceSchemaVariants],
    ['testSuggestionSchemaVariants', testSuggestionSchemaVariants],
    ['testSurveyNotFoundOrHidden', testSurveyNotFoundOrHidden],
    ['testSurveyShownOncePerDialog', testSurveyShownOncePerDialog],
    ['testSubmitSendsOnceWithQuestionId', testSubmitSendsOnceWithQuestionId],
    ['testThreeLayoutsSubmit', testThreeLayoutsSubmit],
    ['testSuggestionHandling', testSuggestionHandling],
    ['testSuggestionMissingHidesTextarea', testSuggestionMissingHidesTextarea],
    ['testNoChoiceCannotSubmit', testNoChoiceCannotSubmit],
    ['testSnoozeNeverDismissSemantics', testSnoozeNeverDismissSemantics],
    ['testEscapeOverlayCloseSendNoDismissed', testEscapeOverlayCloseSendNoDismissed],
    ['testSubmitFailureAndRetry', testSubmitFailureAndRetry],
    ['testSubmitLockDuringInFlight', testSubmitLockDuringInFlight],
    ['testDntRejectionFailsSubmit', testDntRejectionFailsSubmit],
    ['testAppVersionUnknown', testAppVersionUnknown],
    ['testBeforeSendFilter', testBeforeSendFilter],
    ['testSdkInitConfigPrivacy', testSdkInitConfigPrivacy],
    ['testPayloadPrivacy', testPayloadPrivacy],
    ['testSuggestionNeverLogged', testSuggestionNeverLogged]
]).catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
