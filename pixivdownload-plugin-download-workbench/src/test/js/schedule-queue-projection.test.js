'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
    deferred,
    taskCredentialPolicy,
    harness,
    source
} = require('./schedule-submit-test-support');

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
