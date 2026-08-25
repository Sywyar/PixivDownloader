'use strict';

/** 服务端身份装载、命令与跨标签页协调。 */
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

function testBootstrapIdentitySemantics() {
    // 服务端 seenLayouts 为空：本页首次体验当前布局需要以 record_seen 命令提交；
    // 首次下载完成触发仍正常启动展示流程（无历史体验限制）。
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: []})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('solo 服务端模式自动展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        const cfg = h.adapter.sdkConfig();
        ok('sdk config 使用 bootstrap', cfg && cfg.bootstrap);
        eq('bootstrap.distinctID 为 scoped ID', cfg.bootstrap.distinctID, SERVER_SCOPED_ID);
        eq('bootstrap.isIdentifiedID === false', cfg.bootstrap.isIdentifiedID, false);
        eq('sdk config 不再包含 distinct_id 初始化字段', cfg.distinct_id, undefined);
        ok('sdk.get_distinct_id() 等于 scoped ID', h.adapter.get_distinct_id() === SERVER_SCOPED_ID);
        const shown = h.adapter.calls.results.find(r => r && r.event === 'survey shown');
        ok('shown 事件携带 scoped distinct_id',
            shown && shown.properties.distinct_id === SERVER_SCOPED_ID);
        const posts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        ok('布局体验以 record_seen 命令提交（不发送完整 seen）', posts.length >= 1);
        posts.forEach(p => {
            ok('record_seen 只含合法布局 ID', Array.isArray(p.body.layoutIds)
                && p.body.layoutIds.every(id => LAYOUT_IDS.indexOf(id) >= 0));
            ok('record_seen 不携带完整 state / seen', !p.body.state && !p.body.seen);
            ok('record_seen 携带 surveyId 且不含 expectedRevision / 客户端时间',
                p.body.surveyId === h.config.surveyId
                && p.body.expectedRevision === undefined
                && p.body.snoozedUntil === undefined
                && p.body.updatedAt === undefined);
        });
        const localStateRaw = h.storage.getItem(STATE_KEY);
        ok('server 无 state 时协调缓存不残留 STATE_KEY',
            localStateRaw === null || JSON.parse(localStateRaw).surveyId !== h.config.surveyId);
    });
}

function testBootstrapIdentitySemanticsLocalCache() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(600);
        return waitForFlush();
    }).then(() => {
        const localSeen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('serverBacked 用权威快照维护 SEEN_KEY 协调缓存',
            localSeen && LAYOUT_IDS.every(id => localSeen[id]
                && typeof localSeen[id].lastSeenAt === 'number' && localSeen[id].lastSeenAt > 0));
        const localState = h.storage.getItem(STATE_KEY);
        ok('server 无 state 时协调缓存不残留旧 STATE_KEY',
            localState === null || JSON.parse(localState).surveyId !== h.config.surveyId);
    });
}

function testBootstrapIdentityMismatchFailsClosed() {
    const h = initHarness({
        page: 'alt',
        adapter: createFakeAdapter({surveys: [defaultSurvey()], distinctId: 'some-other-id'}),
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('get_distinct_id 不一致 fail closed：不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('不一致不请求 Survey', h.adapter.calls.getSurveys.length, 0);
        eq('不一致不发送事件', h.adapter.calls.capture.length, 0);
        const warnings = JSON.stringify(h.consoleWarn);
        ok('记录安全 warning', warnings.indexOf('does not match') >= 0);
        ok('warning 不含 scoped ID / token / survey', warnings.indexOf(SERVER_SCOPED_ID) < 0
            && warnings.indexOf(h.config.projectToken) < 0
            && warnings.indexOf(h.config.surveyId) < 0);
    });
}

function testBootstrapIdentityMismatchViaSurveyFlowNeverShown() {
    // 手动 open 场景：身份不一致同样 fail closed。
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], distinctId: 'stale-id'}),
        batchLayout: 'landscape',
        serverState: serverStateResponse({})
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('身份不一致时不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('身份不一致不发送 shown', captureEvents(h).length, 0);
    });
}

function testServerModeSubmittedStateGatesTrigger() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: LAYOUT_IDS.slice()
        })
    });
    // 先让服务端状态装载完成再派发首次下载完成事件
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('服务端 submitted 不再展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('不发送 shown', captureEvents(h).filter(e => e === 'survey shown').length, 0);
        eq('不初始化 SDK', h.adapter.sdkConfig() === null, true);
    });
}

function testServerModeSubmitPersistsToServer() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('服务端模式提交成功', captureEvents(h).filter(e => e === 'survey sent').length, 1);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('本地协调缓存写 submitted', localState.status, 'submitted');
        eq('本地缓存绑定 surveyId', localState.surveyId, h.config.surveyId);
        const post = h.serverPosts.find(p => p.body.command === 'submitted');
        ok('PostHog 接受后发送 submitted 命令', !!post);
        eq('submitted 命令携带 surveyId', post.body.surveyId, h.config.surveyId);
        eq('submitted 命令不携带建议 / 布局回答 / 完整状态 / expectedRevision / 时间戳',
            post.body.suggestion === undefined
            && post.body.selectedChoice === undefined
            && post.body.state === undefined && post.body.seen === undefined
            && post.body.expectedRevision === undefined
            && post.body.updatedAt === undefined, true);
    });
}

function testServerModeSnoozeAndNeverPersist() {
    return Promise.resolve().then(() => {
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            const post = h.serverPosts.find(p => p.body.command === 'snooze');
            ok('稍后再说以 snooze 命令持久化', !!post);
            eq('snooze 不携带客户端时间戳 / expectedRevision', post.body.snoozedUntil === undefined
                && post.body.updatedAt === undefined
                && post.body.retryAfterMs === undefined
                && post.body.expectedRevision === undefined, true);
            const localState = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('本地协调缓存写 snoozed', localState.status, 'snoozed');
        });
    }).then(() => {
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            const post = h.serverPosts.find(p => p.body.command === 'never');
            ok('不再询问以 never 命令持久化', !!post);
            eq('never 不携带 layoutIds', post.body.layoutIds === undefined, true);
        });
    });
}

function testServerModeSeenRecordsServerSide() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        h.timers.advance(600);
        return waitForFlush();
    }).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        ok('布局体验以 record_seen 命令提交', posts.length >= 1);
        const layoutIds = posts[posts.length - 1].body.layoutIds;
        ok('record_seen 布局 ID 去重且合法', Array.isArray(layoutIds)
            && layoutIds.indexOf('pixiv-batch-portrait') >= 0
            && layoutIds.every((id, index) => layoutIds.indexOf(id) === index));
        ok('record_seen 不携带完整 seen / state / expectedRevision',
            posts.every(p => !p.body.seen && !p.body.state && p.body.expectedRevision === undefined));
        ok('serverBacked 仍写 SEEN_KEY 本地协调缓存',
            h.storage.getItem(SEEN_KEY) !== null);
    });
}

function testServerModeUnavailableFallsBackToLocal() {
    return Promise.resolve().then(() => {
        const h = initHarness({
            page: 'alt',
            serverFetch: '403'
        });
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('403（multi 模式）回退 localStorage 展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
            const cfg = h.adapter.sdkConfig();
            ok('回退模式使用调查隔离匿名 ID', /^ps_[0-9a-f]{64}$/.test(cfg.bootstrap.distinctID));
            eq('回退模式不包含 distinct_id 初始化字段', cfg.distinct_id, undefined);
        });
    }).then(() => {
        const h = initHarness({
            page: 'alt',
            serverFetch: 'fail'
        });
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('服务端不可达回退 localStorage 展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
            ok('服务端不可达使用调查隔离匿名 ID',
                /^ps_[0-9a-f]{64}$/.test(h.adapter.sdkConfig().bootstrap.distinctID));
        });
    }).then(() => {
        const h = initHarness({
            page: 'alt',
            serverFetch: 'pending'
        });
        h.dispatchFirstDownload();
        // 服务端 GET 超时（SERVER_STATE_TIMEOUT_MS=3s）后回退 local 模式，流程继续。
        h.timers.advance(3000);
        return waitForFlush().then(() => {
            eq('服务端超时回退 localStorage 展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
            eq('超时后 Promise 不悬挂（SDK 已初始化）', h.adapter.sdkConfig() !== null, true);
        });
    });
}

function testServerGetUrlCarriesEncodedSurveyId() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({})
    });
    return waitForServerContext(h).then(() => {
        const url = h.fetchCalls.find(c => c.url.indexOf('/api/layout-feedback/state') >= 0
            && !(c.init && c.init.method === 'POST'));
        ok('GET 请求存在', !!url);
        ok('GET 携带 surveyId 查询参数', url.url.indexOf('surveyId=' + encodeURIComponent(SURVEY_ID)) >= 0);
        ok('GET 使用 same-origin credentials', url.init.credentials === 'same-origin');
    });
}

function testServerBackedStateAndSeenFromAuthoritativeSnapshot() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({
            status: 'snoozed',
            canShow: false,
            retryAfterMs: 20 * 60 * 1000,
            seenLayouts: LAYOUT_IDS.slice()
        })
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('权威视图 snooze 生效：有效 snooze 不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('权威视图同步到本地协调缓存', localState.status, 'snoozed');
        eq('本地截止时间 = clientNow + retryAfterMs', localState.snoozedUntil,
            1000000 + 20 * 60 * 1000);
        const localSeen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('权威 seenLayouts 同步到本地协调缓存',
            localSeen && localSeen['pixiv-batch-alt']
                && typeof localSeen['pixiv-batch-alt'].lastSeenAt === 'number'
                && localSeen['pixiv-batch-alt'].lastSeenAt > 0);
    });
}

function testServerModeSubmitPreflightBlocksOnFreshServerState() {
    // 弹窗打开后另一设备把服务端写成 submitted：提交前 preflight GET 必须发现并取消。
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.setServerState(serverStateResponse({
            revision: 2,
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: LAYOUT_IDS.slice()
        }));
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('preflight 发现 submitted：不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
        eq('preflight 拦截后关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('不额外发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        ok('显示已在其他页面处理提示', h.toastCalls.length === 1);
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('不发送 submitted 命令', posts.length, 0);
    });
}

function testServerModeSubmitPreflightAllowsNeverAndSnooze() {
    return Promise.resolve().then(() => {
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            selectChoice(h, 'pixiv-batch-portrait');
            h.setServerState(serverStateResponse({
                revision: 2,
                status: 'never',
                canShow: false,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('preflight 发现 never：仍发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 1);
            eq('never 后提交升级为 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
            eq('never 后发送 submitted 命令', h.serverPosts.filter(p => p.body.command === 'submitted').length, 1);
        });
    }).then(() => {
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            selectChoice(h, 'pixiv-batch-alt');
            h.setServerState(serverStateResponse({
                revision: 2,
                status: 'snoozed',
                canShow: false,
                retryAfterMs: 20 * 60 * 1000,
                seenLayouts: LAYOUT_IDS.slice()
            }));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('preflight 发现有效 snooze：仍发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 1);
            eq('snooze 后提交升级为 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
            eq('snooze 后发送 submitted 命令', h.serverPosts.filter(p => p.body.command === 'submitted').length, 1);
        });
    });
}

function testServerModePreflightAllowsCaptureThenSendsSubmitted() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('preflight 允许后正常 capture', captureEvents(h).filter(e => e === 'survey sent').length, 1);
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('capture 接受后发送 submitted 命令', posts.length, 1);
        eq('submitted 命令不含 expectedRevision', posts[0].body.expectedRevision, undefined);
    });
}

function testServerCommandNetworkFailureSafeDegrade() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
        serverPostResponse: 'fail'
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        h.actionButton('snooze').click();
        return waitForFlush();
    }).then(() => {
        eq('服务端命令失败仍关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('失败保留本地协调缓存回退', localState.status, 'snoozed');
        ok('记录不含用户数据的 warning', h.consoleWarn.some(args => {
            const text = JSON.stringify(args);
            return text.indexOf('server state save failed') >= 0
                && text.indexOf(h.config.projectToken) < 0
                && text.indexOf(h.config.surveyId) < 0;
        }));
    });
}

function testServerModeCrossTabCoordination() {
    // 标签页 A（serverBacked）提交 → 标签页 B 收到 storage 事件即时关闭弹窗，
    // 并触发一次有限服务端刷新；storage 消息不直接伪造 serverRevision。
    return Promise.resolve().then(() => {
        const hA = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(hA).then(() => {
            hA.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            selectChoice(hA, 'pixiv-batch-landscape');
            hA.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const localState = JSON.parse(hA.storage.getItem(STATE_KEY));
            eq('标签页 A 本地协调缓存为 submitted', localState.status, 'submitted');
            // 标签页 B：共享同一 localStorage（协调缓存）
            const hB = initHarness({
                batchLayout: 'landscape',
                storage: {[STATE_KEY]: JSON.stringify(localState), [SEEN_KEY]: hA.storage.getItem(SEEN_KEY)},
                serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
            });
            return waitForServerContext(hB).then(() => hB.api.open()).then(() => waitForFlush())
                .then(() => {
                    eq('标签页 B 弹窗已打开', hB.document.querySelectorAll('.plf-backdrop').length, 1);
                    hB.dispatchStorage(STATE_KEY, JSON.stringify(localState));
                    return waitForFlush();
                }).then(() => {
                    eq('标签页 B 收到 storage 事件后关闭', hB.document.querySelectorAll('.plf-backdrop').length, 0);
                    eq('标签页 B 不重复发送 dismissed', captureEvents(hB).indexOf('survey dismissed'), -1);
                    eq('storage 事件触发一次有限服务端刷新', hB.stateFetchCount() >= 2, true);
                });
        });
    }).then(() => {
        // 永不伪造 serverRevision：storage 值再强也不直接改 revision
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        let before = 0;
        return waitForServerContext(h).then(() => {
            before = h.stateFetchCount();
            h.dispatchStorage(STATE_KEY, JSON.stringify({
                surveyId: h.config.surveyId, status: 'submitted', updatedAt: 1, snoozedUntil: 0
            }));
            return waitForFlush();
        }).then(() => {
            ok('storage 事件后发起有限刷新', h.stateFetchCount() > before);
            eq('刷新次数有限（仅一次事件一次刷新）', h.stateFetchCount() - before, 1);
        });
    });
}

function testLocalStateReconciliation() {
    // 服务端恢复返回空状态，本地曾提交 submitted → 有限回放 submitted 命令。
    const submitted = JSON.stringify({
        surveyId: SURVEY_ID,
        status: 'submitted', updatedAt: 100, snoozedUntil: 0
    });
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[STATE_KEY]: submitted, [SEEN_KEY]: JSON.stringify(seenObject())},
        serverState: serverStateResponse({seenLayouts: []})
    });
    return waitForServerContext(h).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('本地 submitted + 服务端空状态 → 回放 submitted', posts.length, 1);
        const seenPosts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        ok('本地 seen 合并为 record_seen 命令', seenPosts.length >= 1);
        ok('回放不发送 PostHog 事件', h.adapter.calls.capture.length === 0);
    });
}

function testLocalStateReconciliationNeverOverSnoozed() {
    const localNever = JSON.stringify({
        surveyId: SURVEY_ID,
        status: 'never', updatedAt: 100, snoozedUntil: 0
    });
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[STATE_KEY]: localNever},
        serverState: serverStateResponse({
            status: 'snoozed',
            canShow: false,
            retryAfterMs: 20 * 60 * 1000,
            seenLayouts: []
        })
    });
    return waitForServerContext(h).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'never');
        eq('本地 never + 服务端 snoozed → 回放 never 升级', posts.length, 1);
    });
}

function testLocalStateReconciliationNeverDowngrades() {
    const localSnoozed = JSON.stringify({
        surveyId: SURVEY_ID,
        status: 'snoozed', updatedAt: 100, snoozedUntil: 2000000
    });
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[STATE_KEY]: localSnoozed},
        serverState: serverStateResponse({
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: []
        })
    });
    return waitForServerContext(h).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'snooze'
            || p.body.command === 'never' || p.body.command === 'submitted');
        eq('服务端 submitted 时本地 snoozed 不回放（不降级）', posts.length, 0);
    });
}

function testLocalStateReconciliationIgnoresInvalidOrOtherSurvey() {
    return Promise.resolve().then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: JSON.stringify({
                surveyId: SURVEY_ID,
                status: 'weird', updatedAt: 100, snoozedUntil: 0
            })},
            serverState: serverStateResponse({})
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command !== 'record_seen');
            eq('非法本地状态不上传', posts.length, 0);
        });
    }).then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: JSON.stringify({
                surveyId: 'old-survey-id-123456',
                status: 'submitted', updatedAt: 100, snoozedUntil: 0
            })},
            serverState: serverStateResponse({})
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command !== 'record_seen');
            eq('旧 Survey 本地状态不上传', posts.length, 0);
        });
    });
}

function testDestroyDuringServerLoad() {
    const h = initHarness({serverFetch: 'pending', batchLayout: 'landscape'});
    let resolved = false;
    const promise = h.api.open().then(value => { resolved = true; return value; });
    return waitForFlush().then(() => {
        ok('server GET 已发出', h.stateFetchCount() >= 1);
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        ok('destroy 后 open Promise 安全完成', resolved);
        eq('destroy abort 了在途 GET', h.serverAbortCalls.length >= 1, true);
        // 迟到响应不得写任何状态
        h.serverFetchGate.resolve({ok: true, json: () => Promise.resolve(
            serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}))});
        return waitForFlush();
    }).then(() => {
        eq('迟到 GET 响应不初始化 SDK', h.adapter.sdkConfig() === null, true);
        eq('迟到 GET 响应不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('迟到 GET 响应不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
    });
}

function testDestroyThenReinitReProbesServer() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({})
    });
    return waitForServerContext(h).then(() => {
        const initialGets = h.stateFetchCount();
        ok('init 探测服务端', initialGets >= 1);
        h.api.destroy();
        h.api.init(reinitOptions(h));
        return waitForFlush();
    }).then(() => {
        ok('re-init 重新探测服务端（不复用旧视图）', h.stateFetchCount() > 1);
        h.api.destroy();
        h.timers.advance(30000);
        return waitForFlush();
    }).then(() => {
        eq('destroy 后无定时器残留', h.timers.pending().length, 0);
    });
}

function testDestroyDuringServerCommand() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
        serverPostResponse: 'pending'
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        h.actionButton('snooze').click();
        return waitForFlush();
    }).then(() => {
        h.api.destroy();
        // 迟到 POST 响应不得影响新 generation
        return waitForFlush();
    }).then(() => {
        eq('destroy 后无弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后无 Toast', h.toastCalls.length, 0);
        eq('destroy 后定时器已清理', h.timers.pending().length, 0);
    });
}

function testServerSeenDebounceTimerClearedOnDestroy() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        ok('seen 去抖定时器已调度', h.timers.pending().length > 0);
        const postsBefore = h.serverPosts.filter(p => p.body.command === 'record_seen').length;
        h.api.destroy();
        eq('destroy 清除 seen 去抖定时器', h.timers.pending().length, 0);
        h.timers.advance(5000);
        return waitForFlush();
    }).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        eq('destroy 后不再发送 record_seen', posts.length, 1);
    });
}

function testDisabledConfigDoesNotProbeServer() {
    const h = initHarness({
        posthogAvailable: false,
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('enabled=false 不展示调查', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('enabled=false 不加载 SDK', h.scriptElements().length, 0);
        eq('enabled=false 不请求 server state', h.fetchCalls.filter(c =>
            c.url.indexOf('/api/layout-feedback/state') >= 0).length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('enabled=false 触发不动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testWaitForIdentityBeforeSdkInit() {
    // server GET 慢：open() 等待身份，SDK 初始化发生在 GET 完成后。
    const h = initHarness({
        batchLayout: 'landscape',
        serverFetch: 'pending',
        minDistinct: 1
    });
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('server GET 完成前 SDK 未初始化', h.adapter.sdkConfig() === null, true);
        eq('server GET 完成前不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.serverFetchGate.resolve({ok: true, json: () => Promise.resolve(
            serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}))});
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('server GET 完成后 SDK 才初始化', h.adapter.sdkConfig() !== null, true);
        eq('bootstrap 使用 scoped ID', h.adapter.sdkConfig().bootstrap.distinctID, SERVER_SCOPED_ID);
        eq('身份确定后正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testOpenWaitsForServer403Fallback() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverFetch: '403',
        minDistinct: 1
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('403 后浏览器匿名模式初始化', h.adapter.sdkConfig() !== null, true);
        ok('403 后使用调查隔离匿名 ID',
            /^ps_[0-9a-f]{64}$/.test(h.adapter.sdkConfig().bootstrap.distinctID));
        eq('403 后本地模式可展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testServerModePrivacyNoRawUuid() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        const allJson = JSON.stringify({
            fetchCalls: h.fetchCalls.map(c => c.url),
            posts: h.serverPosts,
            warns: h.consoleWarn
        });
        ok('前端任何地方不出现原始安装 UUID', allJson.indexOf(SERVER_RAW_UUID) < 0);
        ok('前端不出现非 plf 前缀身份', allJson.indexOf('install-00000000') < 0);
        const stateRaw = h.storage.getItem(STATE_KEY);
        ok('本地协调缓存不含原始安装 UUID', !stateRaw || stateRaw.indexOf(SERVER_RAW_UUID) < 0);
    });
}

function testServerModeReinitIdentityChangeFailsClosed() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('第一代正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        const surveysBefore = h.adapter.calls.getSurveys.length;
        h.api.destroy();
        // 身份变化（同一页面重新 init 后服务器下发另一个 scoped ID）
        h.setServerState(serverStateResponse({
            distinctId: 'plf_' + 'cd'.repeat(32),
            seenLayouts: LAYOUT_IDS.slice()
        }));
        h.api.init(reinitOptions(h));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('身份变化不静默复用旧 singleton（fail closed 不展示）',
            h.document.querySelectorAll('.plf-backdrop').length, 0);
        const warnings = JSON.stringify(h.consoleWarn);
        ok('记录安全 warning', warnings.indexOf('different configuration') >= 0);
        ok('warning 不含 scoped ID', warnings.indexOf('plf_') < 0);
    });
}

runTests('pixiv-layout-feedback-server-bootstrap.test.js', [
    ['testBootstrapIdentitySemantics', testBootstrapIdentitySemantics],
    ['testBootstrapIdentitySemanticsLocalCache', testBootstrapIdentitySemanticsLocalCache],
    ['testBootstrapIdentityMismatchFailsClosed', testBootstrapIdentityMismatchFailsClosed],
    ['testBootstrapIdentityMismatchViaSurveyFlowNeverShown', testBootstrapIdentityMismatchViaSurveyFlowNeverShown],
    ['testServerModeSubmittedStateGatesTrigger', testServerModeSubmittedStateGatesTrigger],
    ['testServerModeSubmitPersistsToServer', testServerModeSubmitPersistsToServer],
    ['testServerModeSnoozeAndNeverPersist', testServerModeSnoozeAndNeverPersist],
    ['testServerModeSeenRecordsServerSide', testServerModeSeenRecordsServerSide],
    ['testServerModeUnavailableFallsBackToLocal', testServerModeUnavailableFallsBackToLocal],
    ['testServerGetUrlCarriesEncodedSurveyId', testServerGetUrlCarriesEncodedSurveyId],
    ['testServerBackedStateAndSeenFromAuthoritativeSnapshot', testServerBackedStateAndSeenFromAuthoritativeSnapshot],
    ['testServerModeSubmitPreflightBlocksOnFreshServerState', testServerModeSubmitPreflightBlocksOnFreshServerState],
    ['testServerModeSubmitPreflightAllowsNeverAndSnooze', testServerModeSubmitPreflightAllowsNeverAndSnooze],
    ['testServerModePreflightAllowsCaptureThenSendsSubmitted', testServerModePreflightAllowsCaptureThenSendsSubmitted],
    ['testServerCommandNetworkFailureSafeDegrade', testServerCommandNetworkFailureSafeDegrade],
    ['testServerModeCrossTabCoordination', testServerModeCrossTabCoordination],
    ['testLocalStateReconciliation', testLocalStateReconciliation],
    ['testLocalStateReconciliationNeverOverSnoozed', testLocalStateReconciliationNeverOverSnoozed],
    ['testLocalStateReconciliationNeverDowngrades', testLocalStateReconciliationNeverDowngrades],
    ['testLocalStateReconciliationIgnoresInvalidOrOtherSurvey', testLocalStateReconciliationIgnoresInvalidOrOtherSurvey],
    ['testDestroyDuringServerLoad', testDestroyDuringServerLoad],
    ['testDestroyThenReinitReProbesServer', testDestroyThenReinitReProbesServer],
    ['testDestroyDuringServerCommand', testDestroyDuringServerCommand],
    ['testServerSeenDebounceTimerClearedOnDestroy', testServerSeenDebounceTimerClearedOnDestroy],
    ['testDisabledConfigDoesNotProbeServer', testDisabledConfigDoesNotProbeServer],
    ['testWaitForIdentityBeforeSdkInit', testWaitForIdentityBeforeSdkInit],
    ['testOpenWaitsForServer403Fallback', testOpenWaitsForServer403Fallback],
    ['testServerModePrivacyNoRawUuid', testServerModePrivacyNoRawUuid],
    ['testServerModeReinitIdentityChangeFailsClosed', testServerModeReinitIdentityChangeFailsClosed]
]).catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
