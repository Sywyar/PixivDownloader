'use strict';

/** 本地缓存投影、snooze 与幂等写入。 */
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

function testServerViewToLocalState() {
    // serverViewToLocalState 纯函数：服务端 retryAfterMs 只按本地时长转换，
    // 与客户端 / 服务端绝对时间差异无关；submitted / never 保留同业务状态旧对象。
    const internals = initHarness({}).api._internals;
    const localStateValue = (status, snoozedUntil) => ({
        surveyId: SURVEY_ID,
        status,
        updatedAt: 100,
        snoozedUntil: snoozedUntil === undefined ? 0 : snoozedUntil
    });
    return Promise.resolve().then(() => {
        // A. server snoozed + retryAfterMs=20 分钟：本地 snoozedUntil = clientNow + 20 分钟
        const view = {state: localStateValue('snoozed', 1000000 + 20 * 60 * 1000), source: 'server'};
        const out = internals.serverViewToLocalState(view, 1000000, null);
        eq('本地 snoozedUntil = clientNow + 20 分钟', out.snoozedUntil, 1000000 + 20 * 60 * 1000);
        // B. 结果只依赖 duration：无论「浏览器时间与服务器差多少」，转换只依赖
        //    clientNow + retryAfterMs（这里 clientNow 就是 1000000）。
        eq('转换只依赖本地时长', out.snoozedUntil - 1000000, 20 * 60 * 1000);
        // C. 已有同 Survey 本地 snooze 且差距 <= 5 秒：保留旧对象（不无意义重写）
        const existing = localStateValue('snoozed', 1000000 + 20 * 60 * 1000 - 3000);
        const kept = internals.serverViewToLocalState(view, 1000000, existing);
        eq('容差内保留旧对象（updatedAt 保留）', kept.updatedAt, 100);
        eq('容差内保留旧对象（snoozedUntil 保留）', kept.snoozedUntil, existing.snoozedUntil);
        // D. 已有同 Survey 本地 snooze 但差距超过容差：写新截止时间
        const far = localStateValue('snoozed', 1000000 + 20 * 60 * 1000 - 30 * 1000);
        const rewritten = internals.serverViewToLocalState(view, 1000000, far);
        eq('超容差重写为服务端剩余时长', rewritten.snoozedUntil, 1000000 + 20 * 60 * 1000);
        // E. server submitted：已有相同 submitted 保留旧对象；否则新建
        const subView = {state: localStateValue('submitted'), source: 'server'};
        const keptSub = internals.serverViewToLocalState(subView, 1000000,
            localStateValue('submitted'));
        eq('server submitted 保留已有同状态对象', keptSub.updatedAt, 100);
        const freshSub = internals.serverViewToLocalState(subView, 1000000, null);
        eq('server submitted 无旧对象时新建', freshSub.status, 'submitted');
        // F. server never：本地已有 submitted 时保留 submitted（更强状态由
        //    effectiveStateRecord 保证，这里验证转换不降级）
        const neverView = {state: localStateValue('never'), source: 'server'};
        const neverOut = internals.serverViewToLocalState(neverView, 1000000,
            localStateValue('submitted'));
        eq('server never 不覆盖已有本地 submitted', neverOut.status, 'submitted');
    });
}

function testWriteStateIdempotentAcrossTime() {
    const DAY = 24 * 60 * 60 * 1000;
    const stateSets = (h) => h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
    const postsOf = (h, command) => h.serverPosts.filter(p => p.body.command === command).length;
    return Promise.resolve().then(() => {
        // A. repeated submitted：时间前进后重复写不刷新 updatedAt / 不重复写 / 不重复命令
        const h = initHarness({
            serverState: serverStateResponse({seenLayouts: []}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            const first = h.api._internals.writeState('submitted');
            const updatedAt = first.effectiveState.updatedAt;
            eq('首次 submitted 被接受', first.transitionAccepted, true);
            eq('首次写一次 setItem', stateSets(h), 1);
            eq('首次 submitted 命令一次', postsOf(h, 'submitted'), 1);
            h.timers.advance(5000);
            return waitForFlush();
        }).then(() => {
            const second = h.api._internals.writeState('submitted');
            eq('重复 submitted 不被接受', second.transitionAccepted, false);
            eq('updatedAt 保持原值', second.effectiveState.updatedAt, 1000000);
            eq('不重复 setItem', stateSets(h), 1);
            eq('不重复 submitted 命令', postsOf(h, 'submitted'), 1);
            ok('previousState 为已有最强状态', second.previousState !== null
                && second.previousState.status === 'submitted');
        });
    }).then(() => {
        // B. repeated never：同样验证
        const h = initHarness({
            serverState: serverStateResponse({seenLayouts: []}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            const first = h.api._internals.writeState('never');
            eq('首次 never 被接受', first.transitionAccepted, true);
            h.timers.advance(5000);
            return waitForFlush();
        }).then(() => {
            const second = h.api._internals.writeState('never');
            eq('重复 never 不被接受', second.transitionAccepted, false);
            eq('updatedAt 保持原值', second.effectiveState.updatedAt, 1000000);
            eq('不重复 setItem', stateSets(h), 1);
            eq('不重复 never 命令', postsOf(h, 'never'), 1);
        });
    }).then(() => {
        // C. repeated same snooze：相同 snoozedUntil，now 前进
        const h = initHarness({
            serverState: serverStateResponse({seenLayouts: []}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            const first = h.api._internals.writeState('snoozed', 2000000);
            eq('首次 snooze 被接受', first.transitionAccepted, true);
            h.timers.advance(5000);
            return waitForFlush();
        }).then(() => {
            const second = h.api._internals.writeState('snoozed', 2000000);
            eq('相同 snoozedUntil 不被接受', second.transitionAccepted, false);
            eq('updatedAt 保持原值', second.effectiveState.updatedAt, 1000000);
            eq('snoozedUntil 保持', second.effectiveState.snoozedUntil, 2000000);
            eq('不重复 setItem', stateSets(h), 1);
            eq('不重复 snooze 命令', postsOf(h, 'snooze'), 1);
        });
    }).then(() => {
        // D. longer snooze：接受、updatedAt 更新、发送命令
        const h = initHarness({
            serverState: serverStateResponse({seenLayouts: []}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            h.api._internals.writeState('snoozed', 1000000 + DAY);
            h.timers.advance(1000);
            return waitForFlush();
        }).then(() => {
            const longer = h.api._internals.writeState('snoozed', 1000000 + 7 * DAY);
            eq('更长 snooze 被接受', longer.transitionAccepted, true);
            eq('updatedAt 更新', longer.effectiveState.updatedAt, 1001000);
            eq('发送一次 snooze 命令', postsOf(h, 'snooze'), 2);
        });
    }).then(() => {
        // E. shorter snooze：拒绝、保留原对象
        const h = initHarness({
            serverState: serverStateResponse({seenLayouts: []}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            h.api._internals.writeState('snoozed', 1000000 + 7 * DAY);
            h.timers.advance(1000);
            return waitForFlush();
        }).then(() => {
            const shorter = h.api._internals.writeState('snoozed', 1000000 + DAY);
            eq('更短 snooze 被拒绝', shorter.transitionAccepted, false);
            eq('保留原对象', shorter.effectiveState.snoozedUntil, 1000000 + 7 * DAY);
            eq('snooze 命令仍只有一次', postsOf(h, 'snooze'), 1);
        });
    });
}

function testDismissedIdempotentAcrossTime() {
    return Promise.resolve().then(() => {
        // 首次 never：transitionAccepted=true + 一条 dismissed；时间推进后再次
        // 通过测试入口打开并触发 never：不重复 dismissed / updatedAt 不变 /
        // localStorage 不重复写 / 不重复 never 命令。
        let firstUpdatedAt = 0;
        let setsAfterFirst = 0;
        let neverPostsAfterFirst = 0;
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('首次 never 写状态', state.status, 'never');
            firstUpdatedAt = state.updatedAt;
            eq('首次 dismissed 发送一次', captureEvents(h).filter(e => e === 'survey dismissed').length, 1);
            setsAfterFirst = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
            neverPostsAfterFirst = h.serverPosts.filter(p => p.body.command === 'never').length;
            eq('首次 never 命令一次', neverPostsAfterFirst, 1);
            h.timers.advance(5000);
            return waitForFlush();
        }).then(() => h.api.open().then(() => waitForFlush())).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            eq('重复 never 不重复 dismissed', captureEvents(h).filter(e => e === 'survey dismissed').length, 1);
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('updatedAt 不变化', state.updatedAt, firstUpdatedAt);
            eq('localStorage 不重复写', h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, setsAfterFirst);
            eq('不重复 never 命令', h.serverPosts.filter(p => p.body.command === 'never').length, neverPostsAfterFirst);
        });
    }).then(() => {
        // 已 submitted 后 never：dismissed 为 0
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({
                status: 'submitted',
                canShow: false,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            })
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            eq('已 submitted 后 never 不发送 dismissed', captureEvents(h).filter(e => e === 'survey dismissed').length, 0);
        });
    });
}

function testServerSnoozeExpiryByRetryAfterMs() {
    // 服务端独立判断到期：浏览器只消费 retryAfterMs 并转换为本地截止时间。
    // A. 距到期 20 分钟：本地截止 = clientNow + 20 分钟；页面不展示；
    //    首次下载完成触发在本地截止过后才重新 GET 服务端权威状态。
    // B. 服务端到期（canShow=true / retryAfterMs=0）：本地 snooze 清理，可重新展示。
    return Promise.resolve().then(() => {
        // 服务器剩余时间随请求递减（真实服务端每次返回当前剩余时长）。
        let getCalls = 0;
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverFetch: () => {
                getCalls++;
                if (getCalls === 1) {
                    return {ok: true, json: () => Promise.resolve(serverStateResponse({
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 20 * 60 * 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))};
                }
                // 同 revision 动态视图：retryAfterMs 递减到 1 分钟（服务端尚未到期）。
                return {ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 0,
                    status: 'snoozed',
                    canShow: false,
                    retryAfterMs: 60 * 1000,
                    seenLayouts: LAYOUT_IDS.slice()
                }))};
            }
        });
        return waitForServerContext(h).then(() => {
            const localState = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('localStorage 保存 clientNow + retryAfterMs（20 分钟）',
                localState.snoozedUntil, 1000000 + 20 * 60 * 1000);
            eq('serverLocalBlockUntil = clientNow + 20 分钟',
                h.api._internals.serverLocalBlockUntil(), 1000000 + 20 * 60 * 1000);
            // 服务端到期前触发：不展示。
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq('服务端 canShow=false：不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
            // 浏览器设备时间任意调快：本地截止时间（clientNow + retryAfterMs）随之
            // 提前，不提前展示（服务端视图 canShow=false 仍是旧值，本地截止未到）。
            h.timers.setWallNow(1000000 + 19 * 60 * 1000);
            return waitForFlush();
        }).then(() => {
            eq('设备时间调快 19 分钟：仍不展示（本地截止未到）',
                h.document.querySelectorAll('.plf-backdrop').length, 0);
            // 本地截止时间过后：触发时允许重新 GET 服务端权威状态。
            h.timers.setWallNow(1000000 + 21 * 60 * 1000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq('本地截止过后触发重新 GET（stateFetchCount 增加）', h.stateFetchCount() >= 2, true);
            eq('服务端尚未到期（剩余 1 分钟）：仍不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('动态视图更新：本地截止 = 重新 GET 时 now + 1 分钟',
                h.api._internals.serverRetryAfterMs(), 60 * 1000);
        });
    }).then(() => {
        // 服务端到达 snoozedUntil：GET 返回 canShow=true / retryAfterMs=0。
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverFetch: refreshWith(
                {status: 'snoozed', canShow: false, retryAfterMs: 20 * 60 * 1000, seenLayouts: []},
                () => ({ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 1,
                    status: 'snoozed',
                    canShow: true,
                    retryAfterMs: 0,
                    seenLayouts: []
                }))}))
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(21 * 60 * 1000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq('服务端到期后本地 snooze 清理', h.storage.getItem(STATE_KEY), null);
            eq('页面重新满足展示条件', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    });
}

function testLocalStorageFallbackAcrossReload() {
    // 服务端 snooze 20 分钟 → destroy + re-init 且服务端 GET 超时：
    // 本地缓存继续阻断约 20 分钟（不提前显示、不额外延长）。
    let getCalls = 0;
    const h = initHarness({
        page: 'alt',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            if (getCalls === 1) {
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 20 * 60 * 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
            return Promise.reject(new Error('server unavailable'));
        }
    });
    return waitForServerContext(h).then(() => {
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('localStorage 保存 clientNow + 20 分钟', localState.snoozedUntil, 1000000 + 20 * 60 * 1000);
        h.api.destroy();
        h.api.init(reinitOptions(h, null));
        return waitForFlush();
    }).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('服务器不可用时不提前展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('fallback 仍剩约 20 分钟（不额外延长）', localState.snoozedUntil, 1000000 + 20 * 60 * 1000);
        // 本地截止到达后：fallback 过期且无 terminal 状态，按 availability 策略继续。
        h.timers.advance(21 * 60 * 1000);
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            // reinit 使用真实 script 加载路径：补上 SDK 全局再触发 load。
            h.sandbox.posthog = createFakeAdapter({surveys: [defaultSurvey()]});
            h.fireScriptLoad();
            return waitForFlush();
        });
    }).then(() => {
        eq('本地 fallback 过期后允许展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testServerTerminalReloadStillBlocks() {
    // server submitted / never：重载且服务端不可用时仍阻断。
    return ['submitted', 'never'].reduce((chain, status) => chain.then(() => {
        let getCalls = 0;
        const serverFetch = () => {
            getCalls++;
            if (getCalls === 1) {
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        status,
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
            return Promise.reject(new Error('server unavailable'));
        };
        const h = initHarness({
            page: 'alt',
            serverFetch
        });
        return waitForServerContext(h).then(() => {
            const localState = JSON.parse(h.storage.getItem(STATE_KEY));
            eq(status + ' 本地缓存状态保持', localState.status, status);
            h.api.destroy();
            h.api.init(reinitOptions(h, null));
            return waitForFlush();
        }).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq(status + ' 重载且服务器不可用时仍阻断',
                h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }), Promise.resolve());
}

function testServerSnoozeExpiredClearsLocalSnooze() {
    // server snoozed canShow=true：清理同 Survey 本地 snooze；
    // 不清理本地 submitted / never（终端状态由 effectiveState 强度保证）。
    const h = initHarness({
        batchLayout: 'landscape',
        initialWall: 1000000,
        serverState: serverStateResponse({
            status: 'snoozed',
            canShow: false,
            retryAfterMs: 20 * 60 * 1000,
            seenLayouts: LAYOUT_IDS.slice()
        })
    });
    return waitForServerContext(h).then(() => {
        eq('初始本地有 snooze', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'snoozed');
        h.setServerState(serverStateResponse({
            revision: 1,
            status: 'snoozed',
            canShow: true,
            retryAfterMs: 0,
            seenLayouts: LAYOUT_IDS.slice()
        }));
        return directRefresh(h).then(() => waitForFlush());
    }).then(() => {
        eq('服务端到期后本地 snooze 清理', h.storage.getItem(STATE_KEY), null);
    });
}

function testRepeatedServerSnoozeNoMeaninglessWrites() {
    // 同一服务端 snooze 重复 GET：本地截止时间差在容差内，不产生无意义 localStorage
    // 写入（setStorageIfChanged 去重 + serverViewToLocalState 容差）。
    // 断言必须是精确相等（===），不能用 >= 冒充「没有额外写入」。
    let getCalls = 0;
    let stateKeyBefore = null;
    let setsBeforeRefresh = 0;
    let removesBeforeRefresh = 0;
    let postsBefore = 0;
    const h = initHarness({
        batchLayout: 'landscape',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            return {
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    status: 'snoozed',
                    canShow: false,
                    retryAfterMs: 20 * 60 * 1000,
                    seenLayouts: LAYOUT_IDS.slice()
                }))
            };
        }
    });
    return waitForServerContext(h).then(() => {
        stateKeyBefore = h.storage.getItem(STATE_KEY);
        setsBeforeRefresh = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
        removesBeforeRefresh = h.storage.removeCalls.filter(c => c === STATE_KEY).length;
        postsBefore = h.serverPosts.length;
        h.timers.advance(2000);
        return waitForFlush();
    }).then(() => {
        // 第二次 GET（storage 事件触发）返回同一 snooze：不重复写 STATE_KEY。
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('snoozed', 1000000 + 20 * 60 * 1000)));
        return waitForFlush();
    }).then(() => {
        const setsAfterRefresh = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
        eq('重复服务端 snooze 零额外 STATE_KEY 写入', setsAfterRefresh, setsBeforeRefresh);
        eq('STATE_KEY 序列化内容完全不变', h.storage.getItem(STATE_KEY), stateKeyBefore);
        const state = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('updatedAt 不变', state.updatedAt, 1000000);
        eq('snoozedUntil 在容差内保留旧值', state.snoozedUntil, 1000000 + 20 * 60 * 1000);
        eq('无额外 STATE_KEY remove', h.storage.removeCalls.filter(c => c === STATE_KEY).length,
            removesBeforeRefresh);
        eq('无额外服务端命令', h.serverPosts.length, postsBefore);
    });
}

function testServerSnoozeDeadlineChangeWritesOnce() {
    // 正向对照：新的 retryAfterMs 使本地截止时间变化超过容差时，恰好增加一次
    // STATE_KEY 写入（截止时间更新），不产生第二次多余写入。
    let getCalls = 0;
    let setsAfterFirstWrite = 0;
    let setsBefore = 0;
    const h = initHarness({
        batchLayout: 'landscape',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            return {
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 0,
                    status: 'snoozed',
                    canShow: false,
                    retryAfterMs: getCalls === 1 ? 20 * 60 * 1000 : 20 * 60 * 1000 + 10 * 1000,
                    seenLayouts: LAYOUT_IDS.slice()
                }))
            };
        }
    });
    return waitForServerContext(h).then(() => {
        setsBefore = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
        h.dispatchStorage(SEEN_KEY, JSON.stringify(seenObject()));
        return waitForFlush();
    }).then(() => {
        const setsAfter = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
        eq('截止时间变化超过容差：恰好一次额外 STATE_KEY 写入', setsAfter, setsBefore + 1);
        setsAfterFirstWrite = setsAfter;
        eq('截止时间更新为新值', JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil,
            1000000 + 20 * 60 * 1000 + 10 * 1000);
        // 立即再次刷新（同一新值）：不再写入。
        h.dispatchStorage(SEEN_KEY, JSON.stringify(seenObject()));
        return waitForFlush();
    }).then(() => {
        eq('相同新值不再产生第二次多余写入',
            h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, setsAfterFirstWrite);
    });
}

function testSeenLayoutsToLocalSeen() {
    // seenLayouts 转本地 seen：不复制服务端时间戳（本地保留自己的时间戳，
    // 服务端新增而本地没有的布局用当前客户端时间）。
    const h = initHarness({
        batchLayout: 'landscape',
        initialWall: 1000000,
        serverState: serverStateResponse({
            seenLayouts: ['pixiv-batch-landscape', 'pixiv-batch-portrait']
        })
    });
    return waitForServerContext(h).then(() => {
        const localSeen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('本地 seen 含服务端布局', localSeen['pixiv-batch-landscape'] && localSeen['pixiv-batch-portrait']);
        eq('服务端新增布局使用当前客户端时间', localSeen['pixiv-batch-portrait'].firstSeenAt, 1000000);
        ok('本地时间戳为客户端时钟域（不是服务端时间）',
            localSeen['pixiv-batch-portrait'].firstSeenAt >= 0);
    });
}

function testNoCasCommandProtocol() {
    // 命令 body 不含 expectedRevision；单次 attempt；snooze 响应 never/submitted 采用
    // 更强状态；never 响应 submitted 成功；record_seen 缺目标布局失败且 pending 保留。
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
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze');
            eq('snooze 只发送一次（无 409 重试）', posts.length, 1);
            eq('body 不含 expectedRevision / 时间戳', posts[0].body.expectedRevision === undefined
                && posts[0].body.snoozedUntil === undefined, true);
            eq('snooze 确认后本地为 snoozed', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'snoozed');
        });
    }).then(() => {
        // snooze 响应 never：成功且采用更强状态。
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
            serverPostResponse: ({body}) => {
                if (body.command !== 'snooze') return undefined;
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'never',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            eq('snooze 响应 never：成功并采用更强状态',
                JSON.parse(h.storage.getItem(STATE_KEY)).status, 'never');
        });
    }).then(() => {
        // never 响应 submitted：成功。
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
            serverPostResponse: ({body}) => {
                if (body.command !== 'never') return undefined;
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            eq('never 响应 submitted：成功并采用更强状态',
                JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        });
    }).then(() => {
        // record_seen 缺目标布局：失败；pending 保留。
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seenLayouts: []}),
            serverPostResponse: ({body}) => {
                if (body.command !== 'record_seen') return undefined;
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: null,
                        canShow: true,
                        retryAfterMs: 0,
                        seenLayouts: []
                    }))
                };
            }
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(600);
            return waitForFlush();
        }).then(() => {
            const effective = h.api._internals.effectiveSeen();
            ok('record_seen 未被确认：pending 保留',
                h.api._internals.distinctSeenCount(effective) >= 1);
            ok('本地 SEEN_KEY 保留布局', h.storage.getItem(SEEN_KEY) !== null);
        });
    });
}

runTests('pixiv-layout-feedback-persistence.test.js', [
    ['testServerViewToLocalState', testServerViewToLocalState],
    ['testWriteStateIdempotentAcrossTime', testWriteStateIdempotentAcrossTime],
    ['testDismissedIdempotentAcrossTime', testDismissedIdempotentAcrossTime],
    ['testServerSnoozeExpiryByRetryAfterMs', testServerSnoozeExpiryByRetryAfterMs],
    ['testLocalStorageFallbackAcrossReload', testLocalStorageFallbackAcrossReload],
    ['testServerTerminalReloadStillBlocks', testServerTerminalReloadStillBlocks],
    ['testServerSnoozeExpiredClearsLocalSnooze', testServerSnoozeExpiredClearsLocalSnooze],
    ['testRepeatedServerSnoozeNoMeaninglessWrites', testRepeatedServerSnoozeNoMeaninglessWrites],
    ['testServerSnoozeDeadlineChangeWritesOnce', testServerSnoozeDeadlineChangeWritesOnce],
    ['testSeenLayoutsToLocalSeen', testSeenLayoutsToLocalSeen],
    ['testNoCasCommandProtocol', testNoCasCommandProtocol]
]).catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
