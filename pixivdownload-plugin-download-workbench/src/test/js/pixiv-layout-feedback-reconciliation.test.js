'use strict';

/** 服务端恢复对账与协议视图校验。 */
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

function testReconcileSubmittedReplaysAndWaits() {
    // A. local submitted + server null + replay 成功：
    // loadServerContext 必须等待 replay；确认后 server 视图为 submitted、
    // localStorage 为权威视图转换；SDK 不加载、Survey 不请求、弹窗不显示。
    let release = null;
    const h = initHarness({
        // 不设置 batchLayout：避免 init 的 recordSeen 触发 400ms record_seen flush，
        // 使 revision 只由被 gate 的 submitted 回放推进（同 revision 内容一致性校验
        // 要求 mock 响应与当前 revision 严格一致）。
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            const gate = new Promise(resolve => {
                release = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                });
            });
            return gate;
        }
    });
    return waitForFlush().then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('replay 命令已发出', posts.length, 1);
        // 未释放确认前推进自动评估：command 超时（4000ms）先于自动评估（10000ms）
        // 结束 reconciliation，loadServerContext 有限结束；SDK 不得初始化。
        h.timers.advance(10000);
        return waitForFlush();
    }).then(() => {
        eq('replay 未确认前 SDK 不加载（loadServerContext 等待 replay）',
            h.adapter.sdkConfig() === null, true);
        // 超时后迟到响应不得应用：不修改 revision / state / localStorage。
        release();
        return waitForFlush();
    }).then(() => {
        eq('replay 成功只发送一次', h.serverPosts.filter(p => p.body.command === 'submitted').length, 1);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('确认后 localStorage 保留 local fallback', localState.status, 'submitted');
        eq('超时后的迟到响应不修改时间戳（保留本地 updatedAt）', localState.updatedAt, 100);
        eq('迟到响应不推进 revision', h.api._internals.currentServerRevision(), 0);
        eq('SDK 不加载', h.adapter.sdkConfig() === null, true);
        eq('Survey 不请求', h.adapter.calls.getSurveys.length, 0);
        eq('弹窗不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testReconcileSubmittedReplayNetworkFailure() {
    // B. local submitted + server null + replay 网络失败：
    // local submitted 保留；pending fallback 保留（effectiveState 为 submitted）；
    // 首次下载完成触发被阻断：SDK 不加载、Survey 不请求、弹窗不显示。
    const h = initHarness({
        page: 'alt',
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: 'fail'
    });
    return waitForFlush().then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('local submitted 保留', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        eq('effectiveState 仍为 submitted（pending fallback 保留）',
            h.api._internals.effectiveState().status, 'submitted');
        eq('SDK 不加载', h.adapter.sdkConfig() === null, true);
        eq('Survey 不请求', h.adapter.calls.getSurveys.length, 0);
        eq('弹窗不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.timers.advance(5000);
        return waitForFlush();
    }).then(() => {
        eq('失败后无残留定时器', h.timers.pending().length, 0);
    });
}

function testReconcileNeverReplayTimeout() {
    // C. local never + server null + replay 超时：
    // local never 保留；自动流程不展示；reconciliation 在有限时间内结束
    // （decision 超时后 seen 回放仍继续推进，证明前一阶段已 settle）。
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('never'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: () => new Promise(() => {})
    });
    return waitForFlush().then(() => {
        const posts = h.serverPosts.map(p => p.body.command);
        ok('never 命令已发出', posts.indexOf('never') >= 0);
        h.timers.advance(12000);
        return waitForFlush();
    }).then(() => {
        const posts = h.serverPosts.map(p => p.body.command);
        ok('decision 超时后 seen 回放继续（reconciliation 未永久 pending）',
            posts.indexOf('record_seen') > posts.indexOf('never'));
        eq('local never 保留', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'never');
        eq('effectiveState 为 never', h.api._internals.effectiveState().status, 'never');
        eq('自动流程不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('SDK 不加载', h.adapter.sdkConfig() === null, true);
        // 失败链路逐段收敛（decision/seen timeout → recordSeen 补记 → flush 失败终止），
        // 循环推进直到没有残留定时器（带上限保护）。
        return (function drain(rounds) {
            if (h.timers.pending().length === 0) return Promise.resolve();
            if (rounds > 50) throw new Error('drain runaway');
            h.timers.advance(10000);
            return waitForFlush().then(() => drain(rounds + 1));
        })(0);
    }).then(() => {
        eq('超时结束后无残留定时器', h.timers.pending().length, 0);
    });
}

function testReconcileSnoozedReplayFailure() {
    // D. local 有效 snoozed + server null + replay 失败：
    // local snoozedUntil 保留；未到期不展示。
    const snoozedUntil = 2000000;
    const h = initHarness({
        page: 'alt',
        storage: {
            [STATE_KEY]: localStateValue('snoozed', snoozedUntil),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: 'fail'
    });
    return waitForFlush().then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('local snoozed 保留', localState.status, 'snoozed');
        eq('snoozedUntil 保留', localState.snoozedUntil, snoozedUntil);
        eq('未到期不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('SDK 不加载', h.adapter.sdkConfig() === null, true);
    });
}

function testReconcileSeenReplayFailure() {
    // E. local seen 两个布局 + server seen 空 + record_seen 失败：
    // local seen 不被清空；effectiveSeen 仍有两个布局。
    const localSeen = {};
    localSeen['pixiv-batch-landscape'] = {firstSeenAt: 1, lastSeenAt: 100};
    localSeen['pixiv-batch-portrait'] = {firstSeenAt: 2, lastSeenAt: 200};
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[SEEN_KEY]: JSON.stringify(localSeen)},
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: 'fail'
    });
    return waitForServerContext(h).then(() => {
        const stored = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('local seen 不被清空', stored['pixiv-batch-landscape'] && stored['pixiv-batch-portrait']);
        const effective = h.api._internals.effectiveSeen();
        eq('effectiveSeen 仍有两个布局',
            h.api._internals.distinctSeenCount(effective), 2);
    });
}

function testReconcileSuccessSyncsAuthoritativeSnapshot() {
    // F. replay 成功：pending fallback 清理；权威 server 视图转换写回 localStorage；
    // 首次下载完成触发被服务端 submitted 阻断，不展示。
    const h = initHarness({
        page: 'alt',
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForFlush().then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('replay 成功', posts.length, 1);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('权威视图写回 localStorage', localState.status, 'submitted');
        ok('localStorage 与服务器视图一致（updatedAt 为客户端时钟域）',
            typeof localState.updatedAt === 'number');
        const effective = h.api._internals.effectiveState();
        eq('effectiveState 为服务器确认的 submitted', effective.status, 'submitted');
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('弹窗不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testServerSubmittedWinsOverLocalSnoozed() {
    // G. server 已 submitted + local snoozed：server submitted 胜出；
    // local snoozed 被覆盖；不发降级命令。
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('snoozed', 2000000),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: LAYOUT_IDS.slice()
        })
    });
    return waitForServerContext(h).then(() => {
        const downgrades = h.serverPosts.filter(p => p.body.command === 'snooze'
            || p.body.command === 'never' || p.body.command === 'submitted');
        eq('本地 snoozed 不回放降级命令', downgrades.length, 0);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('localStorage 覆盖为服务端 submitted', localState.status, 'submitted');
        eq('localStorage 使用客户端时钟域时间戳（不复制服务端时间）',
            localState.updatedAt, 1000000);
        eq('effectiveState 为 submitted', h.api._internals.effectiveState().status, 'submitted');
    });
}

function testReconcileDecisionThenSeenOrdering() {
    // H. decision 与 seen 顺序：decision 命令先发出；seen 命令在 decision 确认后
    // 才发出（命令顺序固定，不制造并发状态竞态）。
    let releaseDecision = null;
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            const gate = new Promise(resolve => {
                releaseDecision = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: []
                    }))
                });
            });
            return gate;
        }
    });
    return waitForFlush().then(() => {
        eq('decision 先于 seen（seen 未发出）', h.serverPosts.map(p => p.body.command).join(','), 'submitted');
        releaseDecision();
        return waitForFlush();
    }).then(() => {
        const seenPosts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        ok('decision 确认后 seen 才发出', seenPosts.length >= 1);
        eq('seen 命令不含 expectedRevision', seenPosts[0].body.expectedRevision, undefined);
    });
}

function testCommandTimeoutDestroyClearsTimers() {
    // I. command 超时：destroy 后无残留定时器，Promise 安全结束。
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('never'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: () => new Promise(() => {})
    });
    return waitForFlush().then(() => {
        ok('命令已发出且未解决', h.serverPosts.length >= 1);
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        eq('destroy 后无残留定时器', h.timers.pending().length, 0);
    });
}

function testStateGetsUseNoStoreCache() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchStorage(STATE_KEY, localStateValue('submitted'));
        return waitForFlush();
    }).then(() => {
        const gets = h.fetchCalls.filter(c => c.url.indexOf('/api/layout-feedback/state') >= 0
            && !(c.init && c.init.method === 'POST'));
        ok('存在状态 GET 请求', gets.length >= 1);
        gets.forEach(c => {
            eq('状态 GET 的 fetch init.cache 为 no-store', c.init.cache, 'no-store');
            eq('状态 GET 携带 Accept: application/json', c.init.headers.Accept, 'application/json');
        });
    });
}

function testApplyServerViewRejectsInvalidShapes() {
    // 非法视图字段 / 非法组合 / 缺失身份：整份拒绝，不初始化错误 identity，
    // 回退 local 模式（open 走浏览器匿名身份）。
    const withServerState = (overrides) => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse(overrides)
        });
        return h.api.open().then(() => waitForFlush()).then(() => h);
    };
    const usesFallbackIdentity = h => /^ps_[0-9a-f]{64}$/.test(
        h.adapter.sdkConfig().bootstrap.distinctID);
    return withServerState({revision: 1.5}).then(h => {
        eq('非整数 revision 视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({revision: -1})).then(h => {
        eq('负数 revision 视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({revision: Number.NaN})).then(h => {
        eq('NaN revision 视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({distinctId: ''})).then(h => {
        eq('available=true 但 distinctId 缺失视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({submissionId: ''})).then(h => {
        eq('submissionId 缺失视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({submissionId: 'not-a-uuid'})).then(h => {
        eq('submissionId 非 UUIDv8 视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({status: 'bogus', canShow: false, retryAfterMs: 0})).then(h => {
        eq('未知 status 视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({status: 'submitted', canShow: true, retryAfterMs: 0})).then(h => {
        eq('submitted + canShow=true 组合非法拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({status: 'snoozed', canShow: true, retryAfterMs: 100})).then(h => {
        eq('snoozed canShow=true + retryAfterMs>0 组合非法拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({status: 'snoozed', canShow: false, retryAfterMs: 0})).then(h => {
        eq('snoozed canShow=false + retryAfterMs=0 组合非法拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({status: null, canShow: false, retryAfterMs: 0})).then(h => {
        eq('status=null 必须 canShow=true：组合非法拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({status: null, canShow: true, retryAfterMs: 50})).then(h => {
        eq('status=null 必须 retryAfterMs=0：组合非法拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({
        stateAvailable: false, status: 'never', canShow: false, retryAfterMs: 0
    })).then(h => {
        eq('stateAvailable=false 必须 status=null：组合非法拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({
        status: 'submitted', canShow: false, retryAfterMs: 0,
        seenLayouts: ['pixiv-batch-landscape', 'pixiv-batch-landscape']
    })).then(h => {
        eq('seenLayouts 重复视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({
        status: 'submitted', canShow: false, retryAfterMs: 0,
        seenLayouts: ['pixiv-batch-unknown']
    })).then(h => {
        eq('seenLayouts 未知布局视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({
        status: 'submitted', canShow: false, retryAfterMs: 0,
        seenLayouts: ['pixiv-batch-landscape', 'pixiv-batch-portrait', 'pixiv-batch-alt', 'pixiv-batch-landscape']
    })).then(h => {
        eq('seenLayouts 超过三个视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({canShow: 'yes'})).then(h => {
        eq('canShow 非 boolean 视图整体拒绝', usesFallbackIdentity(h), true);
    }).then(() => withServerState({retryAfterMs: -1})).then(h => {
        eq('负数 retryAfterMs 视图整体拒绝', usesFallbackIdentity(h), true);
    });
}

runTests('pixiv-layout-feedback-reconciliation.test.js', [
    ['testReconcileSubmittedReplaysAndWaits', testReconcileSubmittedReplaysAndWaits],
    ['testReconcileSubmittedReplayNetworkFailure', testReconcileSubmittedReplayNetworkFailure],
    ['testReconcileNeverReplayTimeout', testReconcileNeverReplayTimeout],
    ['testReconcileSnoozedReplayFailure', testReconcileSnoozedReplayFailure],
    ['testReconcileSeenReplayFailure', testReconcileSeenReplayFailure],
    ['testReconcileSuccessSyncsAuthoritativeSnapshot', testReconcileSuccessSyncsAuthoritativeSnapshot],
    ['testServerSubmittedWinsOverLocalSnoozed', testServerSubmittedWinsOverLocalSnoozed],
    ['testReconcileDecisionThenSeenOrdering', testReconcileDecisionThenSeenOrdering],
    ['testCommandTimeoutDestroyClearsTimers', testCommandTimeoutDestroyClearsTimers],
    ['testStateGetsUseNoStoreCache', testStateGetsUseNoStoreCache],
    ['testApplyServerViewRejectsInvalidShapes', testApplyServerViewRejectsInvalidShapes]
]).catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
