'use strict';

/** 刷新结果、提交预检与代际隔离。 */
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

function testRefreshResultContract() {
    const submittedSnapshot = (revision) => ({
        ok: true,
        json: () => Promise.resolve(serverStateResponse({
            revision, status: 'submitted', canShow: false, retryAfterMs: 0, seenLayouts: []
        }))
    });
    return Promise.resolve().then(() => {
        // A1. APPLIED → fresh / viewResult=applied
        const h = initHarness({
            serverFetch: refreshSecond(() => ({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 1, status: 'submitted', canShow: false, retryAfterMs: 0, seenLayouts: []
                }))
            }))
        });
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('APPLIED → status=fresh', result.status, 'fresh');
            eq('APPLIED → viewResult=applied', result.viewResult, 'applied');
            eq('APPLIED 已提交视图', h.api._internals.currentServerRevision(), 1);
        });
    }).then(() => {
        // A2. SAME → fresh，无副作用
        const h = initHarness({
            serverFetch: refreshWith(
                submittedView({revision: 2}),
                submittedSnapshot(2))
        });
        return waitForServerContext(h).then(() => {
            const warnsBefore = h.consoleWarn.length;
            const stateBefore = h.storage.getItem(STATE_KEY);
            return directRefresh(h).then(result => {
                eq('SAME → status=fresh', result.status, 'fresh');
                eq('SAME → viewResult=same', result.viewResult, 'same');
                eq('SAME 不推进 revision', h.api._internals.currentServerRevision(), 2);
                eq('SAME 无新 warning', h.consoleWarn.length, warnsBefore);
                eq('SAME 不改写协调缓存', h.storage.getItem(STATE_KEY), stateBefore);
            });
        });
    }).then(() => {
        // A3. STALE → fresh，当前高 revision 状态不变
        const h = initHarness({
            serverFetch: refreshWith(
                submittedView({revision: 2}),
                () => ({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []
                    }))
                }))
        });
        return waitForServerContext(h).then(() => {
            const warnsBefore = h.consoleWarn.length;
            return directRefresh(h).then(result => {
                eq('STALE → status=fresh', result.status, 'fresh');
                eq('STALE → viewResult=stale', result.viewResult, 'stale');
                eq('STALE 不覆盖高 revision', h.api._internals.currentServerRevision(), 2);
                eq('STALE 状态不变', h.api._internals.effectiveState().status, 'submitted');
                eq('STALE 无新 warning', h.consoleWarn.length, warnsBefore);
            });
        });
    }).then(() => {
        // A4. 同 revision 动态字段更新（retryAfterMs 递减）→ fresh / updated
        const h = initHarness({
            serverFetch: refreshWith(
                snoozedView(20 * 60 * 1000, {revision: 2}),
                () => ({ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 2,
                    status: 'snoozed',
                    canShow: false,
                    retryAfterMs: 10 * 60 * 1000,
                    seenLayouts: []
                }))}))
        });
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('同 revision 动态更新 → status=fresh', result.status, 'fresh');
            eq('同 revision 动态更新 → viewResult=updated', result.viewResult, 'updated');
            eq('动态字段应用：retryAfterMs 10 分钟', h.api._internals.serverRetryAfterMs(), 10 * 60 * 1000);
        });
    }).then(() => {
        // B1. 网络 reject → unavailable
        const h = initHarness({serverFetch: refreshSecond(() => Promise.reject(new Error('network down')))});
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('网络 reject → unavailable', result.status, 'unavailable');
        });
    }).then(() => {
        // B2. 超时 → unavailable
        let gateResolve = null;
        const h = initHarness({serverFetch: refreshSecond(() => new Promise(resolve => { gateResolve = resolve; }))});
        return waitForServerContext(h).then(() => {
            const p = directRefresh(h);
            return waitForFlush().then(() => h.timers.advance(3000)).then(() => p).then(result => {
                eq('超时 → unavailable', result.status, 'unavailable');
                eq('超时 reason=timeout', result.reason, 'timeout');
            });
        });
    }).then(() => {
        // B3. HTTP 408 / 429 / 500 / 503 → unavailable
        return [408, 429, 500, 503].reduce((chain, status) => chain.then(() => {
            const h = initHarness({serverFetch: refreshSecond(() => ({
                ok: false, status, json: () => Promise.resolve({})
            }))});
            return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
                eq('HTTP ' + status + ' → unavailable', result.status, 'unavailable');
            });
        }), Promise.resolve());
    }).then(() => {
        // C1. scoped 身份变化 → invalid
        const h = initHarness({
            serverFetch: refreshSecond(() => ({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    distinctId: 'plf_' + 'cd'.repeat(32), seen: seenObject()
                }))
            }))
        });
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('身份变化 → invalid', result.status, 'invalid');
        });
    }).then(() => {
        // C2. 同 revision 内容冲突 → invalid
        const h = initHarness({
            serverFetch: refreshWith(
                submittedView({revision: 2}),
                () => ({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 2, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []
                    }))
                }))
        });
        return waitForServerContext(h).then(() => {
            const warnsBefore = h.consoleWarn.length;
            return directRefresh(h).then(result => {
                eq('同 revision status 冲突 → invalid', result.status, 'invalid');
                eq('冲突不修改当前状态', h.api._internals.effectiveState().status, 'submitted');
                ok('冲突记录安全 warning', h.consoleWarn.length > warnsBefore);
            });
        });
    }).then(() => {
        // C3. 2xx 非 JSON → invalid
        const h = initHarness({serverFetch: refreshSecond(() => ({
            ok: true, json: () => Promise.reject(new Error('bad json'))
        }))});
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('2xx 非 JSON → invalid', result.status, 'invalid');
        });
    }).then(() => {
        // C4. 2xx schema 非法 → invalid
        const h = initHarness({serverFetch: refreshSecond(() => ({
            ok: true, json: () => Promise.resolve({available: 'yes'})
        }))});
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('2xx schema 非法 → invalid', result.status, 'invalid');
        });
    }).then(() => {
        // C5. HTTP 400 / 401 / 403 / 404 → invalid
        return [400, 401, 403, 404].reduce((chain, status) => chain.then(() => {
            const h = initHarness({serverFetch: refreshSecond(() => ({
                ok: false, status, json: () => Promise.resolve({})
            }))});
            return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
                eq('HTTP ' + status + ' → invalid', result.status, 'invalid');
            });
        }), Promise.resolve());
    }).then(() => {
        // D1. destroy → cancelled
        let gateResolve = null;
        const h = initHarness({serverFetch: refreshSecond(() => new Promise(resolve => { gateResolve = resolve; }))});
        return waitForServerContext(h).then(() => {
            const g = h.api._internals.currentGeneration();
            const p = h.api._internals.refreshServerContext(g);
            return waitForFlush().then(() => {
                h.api.destroy();
                return p.then(result => {
                    eq('destroy → cancelled', result.status, 'cancelled');
                    eq('取消原因为 destroy', result.reason, 'destroy');
                });
            });
        });
    }).then(() => {
        // D2. generation stale → cancelled
        const h = initHarness({serverFetch: validFirst()});
        return waitForServerContext(h).then(() => {
            const g = h.api._internals.currentGeneration();
            return h.api._internals.refreshServerContext(g + 5).then(result => {
                eq('generation stale → cancelled', result.status, 'cancelled');
            });
        });
    }).then(() => {
        // D3. 迟到 response → 无副作用
        let gateResolve = null;
        const h = initHarness({serverFetch: refreshSecond(() => new Promise(resolve => { gateResolve = resolve; }))});
        let warnsBefore = 0;
        return waitForServerContext(h).then(() => {
            const g = h.api._internals.currentGeneration();
            const p = h.api._internals.refreshServerContext(g);
            return waitForFlush().then(() => {
                h.api.destroy();
                return p.then(result => {
                    eq('destroy 后 refresh 结果 cancelled', result.status, 'cancelled');
                });
            });
        }).then(() => {
            warnsBefore = h.consoleWarn.length;
            gateResolve({ok: true, json: () => Promise.resolve(serverStateResponse({
                revision: 9, status: 'submitted', canShow: false, retryAfterMs: 0, seenLayouts: []
            }))});
            return waitForFlush();
        }).then(() => {
            eq('迟到响应不推进 revision', h.api._internals.currentServerRevision(), 0);
            eq('迟到响应不写状态', h.storage.getItem(STATE_KEY), null);
            eq('迟到响应无新 warning', h.consoleWarn.length, warnsBefore);
            eq('无残留定时器', h.timers.pending().length, 0);
        });
    });
}

function testSubmitPreflightFailClosed() {
    return Promise.resolve().then(() => {
        // 1. scoped 身份变化：fail-closed，输入保留，控件恢复，安全 warning
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.setServerState(serverStateResponse({
                distinctId: 'plf_' + 'cd'.repeat(32),
                seenLayouts: LAYOUT_IDS.slice()
            }));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            assertFailClosedInvariants(h, '身份冲突');
            h.api.refreshLanguage(createFakeI18n({
                'layout-feedback:error-state-verification':
                    'The survey state could not be verified. Please try again later.'
            }));
            eq('语言切换后错误文案刷新', h.error().textContent,
                'The survey state could not be verified. Please try again later.');
            eq('语言切换不重复 shown', captureEvents(h).filter(e => e === 'survey shown').length, 1);
            const warns = JSON.stringify(h.consoleWarn);
            ok('warning 不含 A/B 身份、token、Survey ID',
                warns.indexOf('plf_') < 0
                && warns.indexOf(h.config.projectToken) < 0
                && warns.indexOf(h.config.surveyId) < 0);
        });
    }).then(() => {
        // 2. 同 revision 持久化字段冲突：同样 fail-closed
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            // 以当前真实 revision 构造同 revision 不同持久化内容响应（reconcile 可能已推进
            // revision，不能写死 0，否则会落入 STALE 而不是冲突）。
            const currentRevision = h.api._internals.currentServerRevision();
            h.setServerState(serverStateResponse({
                revision: currentRevision,
                status: 'submitted',
                canShow: false,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            assertFailClosedInvariants(h, '同 revision 冲突');
            const warns = JSON.stringify(h.consoleWarn);
            ok('同 revision 冲突 warning 不含 token / Survey ID / scoped ID',
                warns.indexOf(h.config.projectToken) < 0
                && warns.indexOf(h.config.surveyId) < 0
                && warns.indexOf('plf_') < 0);
        });
    }).then(() => {
        // 3. 2xx 响应 malformed JSON：fail-closed
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: refreshSecond(() => ({
                ok: true, json: () => Promise.reject(new Error('bad json'))
            }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            assertFailClosedInvariants(h, 'malformed 响应');
        });
    }).then(() => {
        // 4. HTTP 403：fail-closed
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: refreshSecond(() => ({
                ok: false, status: 403, json: () => Promise.resolve({})
            }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            assertFailClosedInvariants(h, 'HTTP 403');
        });
    });
}

function testSubmitPreflightUnavailableFailOpen() {
    return Promise.resolve().then(() => {
        // 5. 网络超时 → 按设计 fail-open：本地无阻断状态时仍发送一次 survey sent
        let gateResolve = null;
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: refreshSecond(() => new Promise(resolve => { gateResolve = resolve; }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            h.timers.advance(3000);
            return waitForFlush();
        }).then(() => {
            eq('超时 fail-open：发送一次 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 1);
            eq('提交后关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('提交后写 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
            ok('记录不含用户数据的安全 warning',
                h.consoleWarn.some(args => JSON.stringify(args).indexOf('preflight state refresh unavailable') >= 0));
        });
    }).then(() => {
        // 6. 超时后 localStorage 出现 submitted（无 storage 事件）：unavailable 也阻止提交
        let gateResolve = null;
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: refreshSecond(() => new Promise(resolve => { gateResolve = resolve; }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            h.storage.values.set(STATE_KEY, JSON.stringify(surveyState('submitted')));
            h.timers.advance(3000);
            return waitForFlush();
        }).then(() => {
            eq('unavailable + 本地 submitted：不发送 survey sent',
                captureEvents(h).filter(e => e === 'survey sent').length, 0);
            eq('弹窗关闭', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('显示已在其他页面处理', h.toastCalls.length >= 1, true);
            eq('不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        });
    });
}

function testSubmitPreflightDestroyDuring() {
    // 7. preflight 在途时 destroy：不发送、不恢复已销毁 DOM、不显示错误
    const h = initHarness({
        batchLayout: 'landscape',
        serverFetch: refreshSecond(() => new Promise(() => {}))
    });
    return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        eq('destroy during preflight 不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
        eq('不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        eq('destroy 后无弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('无错误显示（已销毁 DOM）', h.document.querySelectorAll('.plf-error').length, 0);
        eq('无 toast', h.toastCalls.length, 0);
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testSubmitPreflightStale() {
    return Promise.resolve().then(() => {
        // 8a. 当前高 revision 无状态 → STALE 迟到低 revision submitted → 允许提交
        // （不设置 batchLayout：避免 init 的 record_seen 推进 revision，保证低 revision
        // 响应真实落在 STALE 分支而不是冲突分支）。
        const h = initHarness({
            serverFetch: refreshWith(
                {revision: 2, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []},
                () => ({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: []
                    }))
                }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('STALE 按当前无状态判断：允许提交', captureEvents(h).filter(e => e === 'survey sent').length, 1);
        });
    }).then(() => {
        // 8b. preflight 在途时另一标签页写入 submitted → 即使 refresh 最终返回
        // 迟到低 revision（STALE），仍按当前更高 revision 的 submitted 阻止提交。
        let gateResolve = null;
        const h = initHarness({
            serverFetch: refreshWith(
                {revision: 2, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []},
                () => new Promise(resolve => { gateResolve = resolve; }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
            return waitForFlush();
        }).then(() => {
            gateResolve({ok: true, json: () => Promise.resolve(serverStateResponse({
                revision: 1, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []
            }))});
            return waitForFlush();
        }).then(() => {
            eq('STALE + 当前已 submitted：阻止提交', captureEvents(h).filter(e => e === 'survey sent').length, 0);
            eq('不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
            eq('弹窗已关闭', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

function testReconcileDecisionInFlightDestroyReinit() {
    // A. gen1 decision command 在途 → destroy → gen2 init → resolve gen1 command：
    // gen1 不进入 reconcileSeen；gen2 pending/state/seen/storage 不变；无旧 warning。
    let releaseDecision = null;
    let calls = 0;
    const h = initHarness({
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverFetch: () => {
            calls++;
            if (calls === 1) {
                return {ok: true, json: () => Promise.resolve(serverStateResponse({seenLayouts: []}))};
            }
            return {ok: true, json: () => Promise.resolve({available: false})};
        },
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            return new Promise(resolve => {
                releaseDecision = () => resolve({
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
        }
    });
    const storedBefore = {};
    let warnsBefore = 0;
    return waitForFlush().then(() => {
        eq('gen1 决策命令已发出', h.serverPosts.filter(p => p.body.command === 'submitted').length, 1);
        storedBefore.state = h.storage.getItem(STATE_KEY);
        warnsBefore = h.consoleWarn.length;
        h.api.destroy();
        h.api.init(reinitOptions(h));
        return waitForFlush();
    }).then(() => {
        // gen2 init 完成后快照 seen（gen2 自己的布局记录属于合法写入）。
        storedBefore.seen = h.storage.getItem(SEEN_KEY);
        releaseDecision();
        return waitForFlush();
    }).then(() => {
        eq('gen1 不进入 reconcileSeen（无 record_seen 命令）',
            h.serverPosts.filter(p => p.body.command === 'record_seen').length, 0);
        eq('gen2 不发送新命令', h.serverPosts.length, 1);
        eq('gen2 localStorage 不被旧链改写', h.storage.getItem(STATE_KEY), storedBefore.state);
        eq('gen2 seen 缓存不被旧链改写', h.storage.getItem(SEEN_KEY), storedBefore.seen);
        eq('gen2 serverRevision 不被旧链改变', h.api._internals.currentServerRevision(), 0);
        eq('旧 generation 迟到完成无 warning', h.consoleWarn.length, warnsBefore);
        ok('无弹窗 / 无 toast / 无错误', h.document.querySelectorAll('.plf-backdrop').length === 0
            && h.toastCalls.length === 0
            && h.document.querySelectorAll('.plf-error').length === 0);
    });
}

function testReconcileSeenInFlightDestroyReinit() {
    // B. gen1 decision 完成、seen command 在途 → destroy → gen2 init →
    // resolve 旧 seen command：无副作用。
    let releaseSeen = null;
    let calls = 0;
    const h = initHarness({
        storage: {
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverFetch: () => {
            calls++;
            if (calls === 1) {
                return {ok: true, json: () => Promise.resolve(serverStateResponse({seenLayouts: []}))};
            }
            return {ok: true, json: () => Promise.resolve({available: false})};
        },
        serverPostResponse: ({body}) => {
            if (body.command !== 'record_seen') return undefined;
            return new Promise(resolve => {
                releaseSeen = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: null,
                        canShow: true,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                });
            });
        }
    });
    const storedBefore = {};
    let warnsBefore = 0;
    return waitForFlush().then(() => {
        eq('gen1 seen 命令已发出', h.serverPosts.filter(p => p.body.command === 'record_seen').length, 1);
        storedBefore.seen = h.storage.getItem(SEEN_KEY);
        storedBefore.state = h.storage.getItem(STATE_KEY);
        warnsBefore = h.consoleWarn.length;
        h.api.destroy();
        h.api.init(reinitOptions(h));
        return waitForFlush();
    }).then(() => {
        // gen2 init 完成后重新快照（gen2 自己的布局记录属于合法写入）。
        storedBefore.seen = h.storage.getItem(SEEN_KEY);
        storedBefore.state = h.storage.getItem(STATE_KEY);
        releaseSeen();
        return waitForFlush();
    }).then(() => {
        eq('旧 seen 命令迟到无副作用：SEEN_KEY 不变', h.storage.getItem(SEEN_KEY), storedBefore.seen);
        eq('STATE_KEY 不变', h.storage.getItem(STATE_KEY), storedBefore.state);
        eq('gen2 不发送新命令', h.serverPosts.length, 1);
        eq('gen2 serverRevision 不变', h.api._internals.currentServerRevision(), 0);
        eq('旧 generation 迟到完成无 warning', h.consoleWarn.length, warnsBefore);
        ok('无弹窗 / 无 toast / 无错误', h.document.querySelectorAll('.plf-backdrop').length === 0
            && h.toastCalls.length === 0
            && h.document.querySelectorAll('.plf-error').length === 0);
    });
}

function testReconcileFinalSyncGuard() {
    // C. reconciliation 结束前 destroy：旧链不得执行 syncServerViewToLocalCache。
    // gen1 若错误同步会把 SEEN_KEY 覆盖为服务端 landscape-only 视图，观察 setItem 次数。
    let releaseDecision = null;
    let calls = 0;
    const h = initHarness({
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverFetch: () => {
            calls++;
            if (calls === 1) {
                return {ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 0,
                    status: null,
                    canShow: true,
                    retryAfterMs: 0,
                    seenLayouts: ['pixiv-batch-landscape']
                }))};
            }
            return {ok: true, json: () => Promise.resolve({available: false})};
        },
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            return new Promise(resolve => {
                releaseDecision = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: ['pixiv-batch-landscape']
                    }))
                });
            });
        }
    });
    let seenSetsBefore = 0;
    let stateBefore = null;
    return waitForFlush().then(() => {
        seenSetsBefore = h.storage.setCalls.filter(c => c[0] === SEEN_KEY).length;
        stateBefore = h.storage.getItem(STATE_KEY);
        h.api.destroy();
        releaseDecision();
        return waitForFlush();
    }).then(() => {
        eq('destroy 后旧链不执行最终缓存同步',
            h.storage.setCalls.filter(c => c[0] === SEEN_KEY).length, seenSetsBefore);
        eq('localStorage 状态未变', h.storage.getItem(STATE_KEY), stateBefore);
        eq('serverRevision 未被旧链推进', h.api._internals.currentServerRevision(), 0);
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testWriteStateMonotonic() {
    const DAY = 24 * 60 * 60 * 1000;
    const ONE_DAY = 1000000 + DAY;
    const SEVEN_DAYS = 1000000 + 7 * DAY;
    return Promise.resolve().then(() => {
        // 1. submitted + snooze：submitted 保留，无 snooze POST
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({status: 'submitted', canShow: false, retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            eq('server submitted + snooze：本地仍 submitted',
                JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
            eq('无 snooze POST', h.serverPosts.filter(p => p.body.command === 'snooze').length, 0);
        });
    }).then(() => {
        // 2. submitted + never：submitted 保留，无 never POST，无 dismissed
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({status: 'submitted', canShow: false, retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            eq('server submitted + never：本地仍 submitted',
                JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
            eq('无 never POST', h.serverPosts.filter(p => p.body.command === 'never').length, 0);
            eq('已有 submitted 时不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        });
    }).then(() => {
        // 3. never + snooze：never 保留，无 snooze POST
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({status: 'never', canShow: false, retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            eq('server never + snooze：本地仍 never',
                JSON.parse(h.storage.getItem(STATE_KEY)).status, 'never');
            eq('无 snooze POST', h.serverPosts.filter(p => p.body.command === 'snooze').length, 0);
        });
    }).then(() => {
        // 4. localStorage snooze 7 天 + 新 snooze 1 天：保留 7 天，不发送较短命令
        const h = initHarness({
            storage: {[STATE_KEY]: localStateValue('snoozed', SEVEN_DAYS)},
            serverState: serverStateResponse({
                status: 'snoozed',
                canShow: false,
                retryAfterMs: SEVEN_DAYS - 1000000,
                seenLayouts: []
            })
        });
        return waitForServerContext(h).then(() => {
            const result = h.api._internals.writeState('snoozed', ONE_DAY);
            return waitForFlush().then(() => {
                eq('较短 snooze 不被接受', result.transitionAccepted, false);
                eq('有效状态保留 7 天', result.effectiveState.snoozedUntil, SEVEN_DAYS);
                eq('不发送较短 snooze 命令', h.serverPosts.filter(p => p.body.command === 'snooze').length, 0);
                eq('本地协调缓存仍为 7 天', JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil, SEVEN_DAYS);
            });
        });
    }).then(() => {
        // 5. localStorage snooze 1 天 + 新 snooze 7 天：7 天生效，发送一次 snooze POST
        const h = initHarness({
            storage: {[STATE_KEY]: localStateValue('snoozed', ONE_DAY)},
            serverState: serverStateResponse({
                status: 'snoozed',
                canShow: false,
                retryAfterMs: ONE_DAY - 1000000,
                seenLayouts: []
            })
        });
        return waitForServerContext(h).then(() => {
            const result = h.api._internals.writeState('snoozed', SEVEN_DAYS);
            return waitForFlush().then(() => {
                eq('更强 snooze 被接受', result.transitionAccepted, true);
                eq('生效状态为 7 天', result.effectiveState.snoozedUntil, SEVEN_DAYS);
                eq('发送一次 snooze POST', h.serverPosts.filter(p => p.body.command === 'snooze').length, 1);
                eq('本地协调缓存更新为 7 天', JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil, SEVEN_DAYS);
            });
        });
    }).then(() => {
        // 6. never + submitted：submitted 生效，发送 submitted POST
        const h = initHarness({
            serverState: serverStateResponse({status: 'never', canShow: false, retryAfterMs: 0, seenLayouts: []})
        });
        return waitForServerContext(h).then(() => {
            const result = h.api._internals.writeState('submitted');
            return waitForFlush().then(() => {
                eq('submitted 升级 never', result.transitionAccepted, true);
                eq('生效状态为 submitted', result.effectiveState.status, 'submitted');
                eq('发送 submitted POST', h.serverPosts.filter(p => p.body.command === 'submitted').length, 1);
                eq('本地协调缓存为 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
            });
        });
    }).then(() => {
        // 7. 多标签近同时写入：storage 最终保留最强状态
        const h = initHarness({
            storage: {[STATE_KEY]: localStateValue('submitted')},
            serverState: serverStateResponse({seenLayouts: []})
        });
        return waitForServerContext(h).then(() => {
            const snoozeResult = h.api._internals.writeState('snoozed', SEVEN_DAYS);
            const neverResult = h.api._internals.writeState('never');
            return waitForFlush().then(() => {
                eq('弱写入不被接受', snoozeResult.transitionAccepted === false
                    && neverResult.transitionAccepted === false, true);
                eq('storage 保留最强状态', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
                eq('不发送降级命令', h.serverPosts.filter(p => p.body.command === 'snooze'
                    || p.body.command === 'never').length, 0);
            });
        });
    }).then(() => {
        // 8. 相同状态重复写：setItem 调用次数不增加
        const h = initHarness({
            serverState: serverStateResponse({seenLayouts: []}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            const first = h.api._internals.writeState('snoozed', 2000000);
            eq('第一次写被接受', first.transitionAccepted, true);
            eq('首次写入一次', h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, 1);
            const second = h.api._internals.writeState('snoozed', 2000000);
            eq('相同状态重复写不再 setItem',
                h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, 1);
            eq('重复写不发送第二次命令', h.serverPosts.filter(p => p.body.command === 'snooze').length, 1);
            eq('重复写保持状态', second.effectiveState.snoozedUntil, 2000000);
        });
    });
}

runTests('pixiv-layout-feedback-refresh.test.js', [
    ['testRefreshResultContract', testRefreshResultContract],
    ['testSubmitPreflightFailClosed', testSubmitPreflightFailClosed],
    ['testSubmitPreflightUnavailableFailOpen', testSubmitPreflightUnavailableFailOpen],
    ['testSubmitPreflightDestroyDuring', testSubmitPreflightDestroyDuring],
    ['testSubmitPreflightStale', testSubmitPreflightStale],
    ['testReconcileDecisionInFlightDestroyReinit', testReconcileDecisionInFlightDestroyReinit],
    ['testReconcileSeenInFlightDestroyReinit', testReconcileSeenInFlightDestroyReinit],
    ['testReconcileFinalSyncGuard', testReconcileFinalSyncGuard],
    ['testWriteStateMonotonic', testWriteStateMonotonic]
]).catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
