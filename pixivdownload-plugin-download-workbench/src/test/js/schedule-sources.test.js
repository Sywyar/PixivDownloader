'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {
    source,
    manifest,
    harness,
    validInitializer,
    pixivModuleSource
} = require('./schedule-sources-test-support');

test('来源 manifest 声明的模式与模块共同决定可调用行为', async () => {
    const installers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js')]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);

    assert.equal(runtime.isAvailable('source-a'), true);
    assert.equal(runtime.previewForMode('search', {mode: 'search'}), null);
    assert.equal(runtime.previewForMode('user', {mode: 'user'}).sourceType, 'source-a');
    assert.deepEqual(
        JSON.parse(JSON.stringify(runtime.captureForMode('user', {mode: 'user'}).params)),
        {source: {id: '1'}}
    );
});
test('取得输入与回灌 helper 绑定 owner publication 并在失活后拒绝调用', async () => {
    let leasedContext = null;
    const restores = [];
    const quickSource = {
        sourceType: 'source-a',
        source: {id: 'quick-1'},
        metadata: ['stable']
    };
    const host = {
        input(mode) {
            assert.equal(mode, 'single-import');
            return 'https://example.invalid/work/456 | title';
        },
        restore(mode, value) {
            restores.push({mode, value});
            return true;
        }
    };
    const install = runtime => runtime.registerModule('/plugins/source-a.js', api => {
        api.registerSource('source-a', {
            matches(context) {
                return context.acquisitionInput('single-import').includes('/456');
            },
            capture(context) {
                leasedContext = context;
                return {params: {input: context.acquisitionInput('single-import')}};
            },
            restore(_task, context) {
                context.restoreAcquisition('single-import', 'restored-value');
                return {mode: 'single-import'};
            },
            summary: () => ({sections: []})
        });
    });
    const installers = new Map([['/plugins/source-a.js', install]]);
    const first = source({acquisitionModes: ['single-import']});
    const second = source({
        acquisitionModes: ['single-import'],
        pluginGeneration: 2,
        publicationId: 22,
        activationToken: 'activation-b'
    });
    const runtime = harness([manifest(1, [first]), manifest(2, [second])], installers);
    const context = {
        mode: 'single-import',
        editingSourceType: 'source-a',
        workTypes: ['work-a'],
        admin: true,
        unknownHostState: {secret: 'must-not-cross'},
        quickSource,
        __scheduleAcquisitionHost: host
    };

    await runtime.refresh(false);
    const captured = runtime.captureForMode('single-import', context);
    assert.equal(captured.params.input, 'https://example.invalid/work/456 | title');
    assert.equal(Object.prototype.hasOwnProperty.call(leasedContext,
        '__scheduleAcquisitionHost'), false);
    assert.deepEqual(Object.keys(leasedContext).sort(), [
        'acquisitionInput', 'mode', 'quickSource', 'restoreAcquisition'
    ]);
    assert.notEqual(leasedContext.quickSource, quickSource);
    assert.deepEqual(JSON.parse(JSON.stringify(leasedContext.quickSource)), quickSource);
    assert.equal(Object.isFrozen(leasedContext.quickSource), true);
    assert.equal(Object.isFrozen(leasedContext.quickSource.source), true);
    assert.equal(Object.isFrozen(leasedContext.quickSource.metadata), true);
    assert.throws(() => { leasedContext.quickSource.source.id = 'forged'; },
        /read only|Cannot assign/i);
    assert.throws(() => leasedContext.quickSource.metadata.push('forged'),
        /not extensible|Cannot add property/i);
    assert.equal(quickSource.source.id, 'quick-1');
    assert.throws(() => leasedContext.acquisitionInput('search'),
        /acquisition mode is unavailable/);
    assert.deepEqual(JSON.parse(JSON.stringify(runtime.restoreTask({
        sourceType: 'source-a'
    }, context))), {mode: 'single-import'});
    assert.deepEqual(restores, [{mode: 'single-import', value: 'restored-value'}]);

    const cyclicQuickSource = {sourceType: 'source-a'};
    cyclicQuickSource.self = cyclicQuickSource;
    runtime.captureForMode('single-import', Object.assign({}, context, {
        quickSource: cyclicQuickSource
    }));
    assert.equal(leasedContext.quickSource, null);

    const oldContext = leasedContext;
    await runtime.refresh(false);
    assert.throws(() => oldContext.acquisitionInput('single-import'), /stale/);
    assert.throws(() => oldContext.restoreAcquisition('single-import', 'late'), /stale/);
    assert.equal(restores.length, 1);
});

test('来源模块返回无效任务定义时提供稳定错误码', async () => {
    const installers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            capture: () => null
        })]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);

    assert.throws(() => runtime.captureForMode('user', {}), error =>
        error && error.code === 'SCHEDULE_SOURCE_DEFINITION_INVALID'
        && /invalid definition/.test(error.message));
});

test('抓取上限展示只投影受控 i18n token 并拒绝嵌套 thenable', async () => {
    const installers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            preview: () => ({
                fetchLimitMode: 'watermark',
                fetchLimitPresentation: {
                    namespace: 'example',
                    watermarkHintKey: 'schedule.fetch.watermark',
                    perRunHintKey: 'schedule.fetch.per-run',
                    fullFetchConfirmKey: 'schedule.fetch.confirm',
                    ignoredText: 'not projected'
                }
            }),
            capture: () => ({
                params: {source: {id: '1'}},
                fetchLimitMode: 'per-run',
                fetchLimitPresentation: {
                    namespace: 'example',
                    watermarkHintKey: 'schedule.fetch.watermark',
                    perRunHintKey: 'schedule.fetch.per-run',
                    fullFetchConfirmKey: 'schedule.fetch.confirm',
                    ignoredText: 'not projected'
                }
            })
        })]
    ]);
    const runtime = harness([manifest(1, [source()])], installers);
    await runtime.refresh(false);

    const preview = runtime.previewForMode('user', {});
    assert.equal(preview.fetchLimitMode, 'watermark');
    assert.deepEqual(JSON.parse(JSON.stringify(preview.fetchLimitPresentation)), {
        namespace: 'example',
        watermarkHintKey: 'schedule.fetch.watermark',
        perRunHintKey: 'schedule.fetch.per-run',
        fullFetchConfirmKey: 'schedule.fetch.confirm'
    });
    const captured = runtime.captureForMode('user', {});
    assert.equal(captured.fetchLimitMode, 'per-run');
    assert.deepEqual(JSON.parse(JSON.stringify(captured.fetchLimitPresentation)), {
        namespace: 'example',
        watermarkHintKey: 'schedule.fetch.watermark',
        perRunHintKey: 'schedule.fetch.per-run',
        fullFetchConfirmKey: 'schedule.fetch.confirm'
    });

    const invalidInstallers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            preview: () => ({
                fetchLimitMode: 'watermark',
                fetchLimitPresentation: Promise.reject(new Error('rejected preview presentation'))
            }),
            capture: () => ({
                params: {},
                fetchLimitMode: 'watermark',
                fetchLimitPresentation: Promise.reject(
                    new Error('rejected capture presentation'))
            })
        })]
    ]);
    const invalid = harness([manifest(1, [source()])], invalidInstallers);
    await invalid.refresh(false);
    assert.equal(invalid.previewForMode('user', {}).fetchLimitPresentation, null);
    assert.equal(invalid.captureForMode('user', {}).fetchLimitPresentation, null);
    await new Promise(resolve => setImmediate(resolve));

    const mismatchedInstallers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            preview: () => ({
                fetchLimitMode: 'watermark',
                fetchLimitPresentation: {
                    namespace: 'another-plugin', fullFetchConfirmKey: 'schedule.fetch.confirm'
                }
            })
        })]
    ]);
    const mismatched = harness([manifest(1, [source()])], mismatchedInstallers);
    await mismatched.refresh(false);
    assert.equal(mismatched.previewForMode('user', {}).fetchLimitPresentation, null);
});

test('旧来源别名在描述、回灌、摘要、凭据与 activation lease 上统一归一化', async () => {
    let credentialInvocation = null;
    const installers = new Map([
        ['/plugins/source-a.js', runtime => runtime.registerModule('/plugins/source-a.js', api => {
            api.registerSource('source-a', {
                matches: () => true,
                capture: () => ({params: {captured: true}}),
                restore: task => ({restoredFrom: task.sourceType}),
                summary: task => ({summaryFrom: task.sourceType}),
                bindCredential: (taskId, value, _context, lease) => {
                    lease.assertCurrent();
                    credentialInvocation = {
                        taskId, value, sourceType: lease.sourceType,
                        activationToken: lease.activationToken
                    };
                    return {ok: true, status: 'bound'};
                }
            });
        })]
    ]);
    const runtime = harness([
        manifest(1, [source({legacyAliases: ['SOURCE_A']})])
    ], installers);
    await runtime.refresh(false);

    assert.equal(runtime.descriptor('SOURCE_A').sourceType, 'source-a');
    assert.equal(runtime.isAvailable('SOURCE_A'), true);
    assert.equal(runtime.activationToken('SOURCE_A'), 'activation-a');
    assert.equal(runtime.activationLease('SOURCE_A').sourceType, 'source-a');
    assert.equal(runtime.activationLease('SOURCE_A').activationToken, 'activation-a');
    assert.equal(runtime.previewForMode('user', {editingSourceType: 'SOURCE_A'}).sourceType, 'source-a');
    assert.equal(runtime.captureForMode('user', {editingSourceType: 'SOURCE_A'}).sourceType, 'source-a');
    assert.equal(runtime.restoreTask({sourceType: 'SOURCE_A'}, {}).restoredFrom, 'SOURCE_A');
    assert.equal(runtime.summary({sourceType: 'SOURCE_A'}, {}).summaryFrom, 'SOURCE_A');
    const credentialResult = await runtime.bindCredential('SOURCE_A', 7, 'ok', {});
    assert.equal(credentialResult.ok, true);
    assert.equal(credentialResult.status, 'bound');
    assert.deepEqual(JSON.parse(JSON.stringify(credentialInvocation)), {
        taskId: 7, value: 'ok', sourceType: 'source-a', activationToken: 'activation-a'
    });
});

test('来源选择先按作品类型收窄并对无上下文的多重匹配拒绝歧义', async () => {
    const sourceA = source({
        sourceType: 'a-source', acquisitionModes: ['user', 'quick'],
        possibleWorkTypes: ['work-a'], frontend: {contractVersion: 1, moduleUrl: '/plugins/a-source.js'}
    });
    const sourceB = source({
        sourceType: 'z-source', ownerPluginId: 'owner-b', packageId: 'package-b',
        publicationId: 22, activationToken: 'activation-b',
        acquisitionModes: ['user', 'quick'], possibleWorkTypes: ['work-b'],
        frontend: {contractVersion: 1, moduleUrl: '/plugins/z-source.js'}
    });
    const installers = new Map([
        ['/plugins/a-source.js', validInitializer('/plugins/a-source.js', {
            capture: () => ({params: {selected: 'a-source'}})
        })],
        ['/plugins/z-source.js', validInitializer('/plugins/z-source.js', {
            capture: () => ({params: {selected: 'z-source'}})
        })]
    ]);
    const runtime = harness([manifest(1, [sourceA, sourceB])], installers);
    await runtime.refresh(false);

    assert.equal(runtime.captureForMode('user', {workTypes: ['work-b']}).params.selected, 'z-source');
    assert.throws(() => runtime.captureForMode('user', {}), error =>
        error && error.code === 'SCHEDULE_SOURCE_EDITOR_AMBIGUOUS'
        && /ambiguous/.test(error.message));
    assert.equal(runtime.captureForMode('quick', {
        quickSource: {sourceType: 'z-source'}
    }).params.selected, 'z-source');
});

test('页面快捷取得码会规范化为中性 quick 契约码', async () => {
    const quickSource = source({
        acquisitionModes: ['quick'],
        possibleWorkTypes: ['work-a']
    });
    const installers = new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            capture: context => ({params: {
                selected: context.quickSource.sourceType,
                pageMode: context.mode
            }})
        })]
    ]);
    const runtime = harness([manifest(1, [quickSource])], installers);
    await runtime.refresh(false);

    const captured = runtime.captureForMode('quick-fetch', {
        mode: 'quick-fetch',
        workTypes: ['work-a'],
        quickSource: {sourceType: 'source-a'}
    });
    assert.equal(captured.sourceType, 'source-a');
    assert.deepEqual(JSON.parse(JSON.stringify(captured.params)), {
        selected: 'source-a',
        pageMode: 'quick-fetch'
    });
});

test('来源别名与其它 canonical 来源冲突时拒绝整个 manifest', async () => {
    const sourceB = source({
        sourceType: 'source-b', ownerPluginId: 'owner-b', packageId: 'package-b',
        publicationId: 22, activationToken: 'activation-b', frontend: null
    });
    const runtime = harness([
        manifest(1, [source({legacyAliases: ['source-b'], frontend: null}), sourceB])
    ], new Map());
    await assert.rejects(runtime.refresh(false), /conflicting schedule source alias/);
});

test('同一 owner publication 的来源模块拒绝不一致 activation token', async () => {
    const sourceB = source({
        sourceType: 'source-b',
        activationToken: 'activation-b'
    });
    const runtime = harness([manifest(1, [source(), sourceB])], new Map());
    await runtime.refresh(false);
    assert.equal(runtime.isAvailable('source-a'), false);
    assert.equal(runtime.isAvailable('source-b'), false);
    assert.equal(runtime.descriptor('source-a').sourceType, 'source-a');
});
