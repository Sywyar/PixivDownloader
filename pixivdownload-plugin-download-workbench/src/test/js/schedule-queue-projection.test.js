'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');
const {
    harness,
    deferred,
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


test('计划队列接收媒体等待和清空快照，失败与取消不会显示为完成', () => {
    const h = harness();
    const item = h.queueItem({workId: '123', workType: 'illust', status: 'pending'}, 'illust');
    h.setQueueModel(7, [item]);
    const emit = data => h.applyQueueSse(7, h.queueKey(item), data);
    emit({downloadedCount: 0, imageProgress: {phase: 'ffmpeg-waiting', outputFormat: 'png'}});
    assert.equal(item.status, 'downloading');
    assert.equal(item.imageProgress.phase, 'ffmpeg-waiting');
    emit({downloadedCount: 0, imageProgress: null});
    assert.equal(item.imageProgress, null);
    emit({completed: true, failed: true});
    assert.equal(item.status, 'failed');
    emit({completed: true, cancelled: true});
    assert.equal(item.status, 'paused');
    assert.equal(item.rawStatus, 'cancelled');
});


test('计划实时标题和标签经过类型映射，同轮轮询保留且不串写同 ID 的其他类型', async () => {
    const runtime = await require('./scheduled-metadata-test-support').metadataRuntime();
    const h = harness({queueTypes: runtime});
    h.replaceTasks([{id: 7, sourceType: 'user-new', runState: 'RUNNING'}]);
    const raw = [{workType: 'illust', workId: '123', status: 'pending'},
        {workType: 'other', workId: '123', status: 'pending'}];
    h.setQueueModel(7, h.mergeQueue(7, raw, 'user-new'));
    h.subscribeQueueSse(7);
    h.dispatchQueueSse('123', {title: '下载中的标题', presentationAttributes: {xRestrict: '2', ai: 'true', illustType: '2'},
        totalImages: 90, downloadedCount: 43});
    const merged = h.mergeQueue(7, raw, 'user-new');
    assert.equal(merged[0].rawTitle, '下载中的标题');
    assert.equal(merged[0].xRestrict, 2);
    assert.equal(merged[0].typeData.illustType, 2);
    assert.equal(merged[0].isAi, true);
    assert.equal(merged[0].downloadedCount, 43);
    assert.equal(merged[1].rawTitle, null);
    assert.deepEqual(Array.from(runtime.queueTags(merged[0]), tag => tag.id), ['media.ugoira', 'attribute.ai']);
    const completed = runtime.scheduledQueueItem('illust', {workId: '123', resultAttributes:
        {title: '完成后的标题', xRestrict: '0', ai: 'false', illustType: '0'}}, {previous: merged[0]});
    assert.equal(completed.rawTitle, '完成后的标题');
    assert.equal(completed.xRestrict, 0);
    assert.equal(completed.isAi, false);
    assert.equal(completed.typeData.illustType, 0);
    h.unsubscribeQueueSse(7);
});

for (const [outcome, status] of [['ERROR', 'failed'], ['CANCELLED', 'paused']]) {
    test(`持续展开的计划队列同轮 ${outcome} 保留元数据，换轮清空并拒绝旧监听`, async t => {
        const runtime = await require('./scheduled-metadata-test-support').metadataRuntime();
        const raw = {workType: 'illust', workId: '123', status: 'pending'};
        let snapshot = {startedTime: 100, items: [raw]};
        const h = harness({queueTypes: runtime, fetch: async () => ({ok: true, json: async () => snapshot})});
        t.after(() => h.unsubscribeQueueSse(7));
        const task = {id: 7, sourceType: 'user-new', runState: 'RUNNING', lastRunTime: 42};
        h.replaceTasks([task]);
        h.state.mode = 'schedule';
        h.setExpanded(7, true);
        await h.fetchQueue(7);
        const oldListener = h.queueSseListeners('123')[0];
        const metadata = {title: 'old title', presentationAttributes: {xRestrict: '2', ai: 'true', illustType: '2'},
            totalImages: 4, downloadedCount: 2};
        h.dispatchQueueSse('123', metadata);

        task.runState = null;
        task.lastOutcome = outcome;
        task.lastRunTime = 150;
        snapshot = {startedTime: 100, items: [{...raw, status, message: 'pixiv.illust.download-failed'}]};
        await h.fetchQueue(7);
        const finished = h.getQueueModel(7)[0];
        assert.equal(finished.rawTitle, 'old title');
        assert.equal(finished.xRestrict, 2);
        assert.equal(finished.isAi, true);
        assert.equal(finished.typeData.illustType, 2);
        assert.equal(finished.rawStatus, status);
        assert.equal(finished.downloadedCount, 2);
        assert.equal(h.queueSseListenerCount('123'), 0);
        oldListener({...metadata, title: 'late title'});
        assert.equal(finished.rawTitle, 'old title');
        assert.equal(finished.rawStatus, status);

        task.runState = 'RUNNING';
        snapshot = {startedTime: 200, items: [raw]};
        await h.fetchQueue(7);
        const fresh = h.getQueueModel(7)[0];
        assert.equal(fresh.rawTitle, null);
        assert.equal(fresh.xRestrict, null);
        assert.equal(fresh.isAi, false);
        assert.equal(fresh.typeData.illustType, undefined);
        assert.equal(fresh.downloadedCount, 0);
        assert.equal(fresh.status, 'pending');
        assert.equal(h.queueSseListenerCount('123'), 1);
        oldListener(metadata);
        assert.equal(fresh.rawTitle, null, '解绑前已排队的旧回调不能污染新一轮');
        assert.equal(fresh.status, 'pending');
        h.dispatchQueueSse('123', {...metadata, title: 'new title'});
        assert.equal(fresh.rawTitle, 'new title');
        snapshot = {startedTime: 300, items: [raw]};
        await h.fetchQueue(7);
        assert.equal(h.getQueueModel(7)[0].rawTitle, null, '漏过中间终态时也按新轮次清空');
        assert.equal(h.getQueueModel(7)[0].downloadedCount, 0);
    });
}

class QueueElement extends MiniElement {
    set innerHTML(value) {
        this.children.slice().forEach(child => this.removeChild(child));
        this.html = String(value);
    }
    get innerHTML() {
        return (this.html || '') + this.children.map(child =>
            `<${child.tagName.toLowerCase()}>${child.textContent}${child.innerHTML}</${child.tagName.toLowerCase()}>`).join('');
    }
}

async function cachedQueueHarness(t) {
    const runtime = await require('./scheduled-metadata-test-support').metadataRuntime();
    const raw = {workType: 'illust', workId: '123', status: 'pending'};
    let response = {startedTime: 100, items: [raw]};
    let httpResponse = null;
    const wrap = new QueueElement('div'), body = new QueueElement('div');
    body.classList.add('schedule-queue-body');
    wrap.appendChild(body);
    const h = harness({queueTypes: runtime, querySelector: () => wrap,
        createElement: tag => new QueueElement(tag),
        translations: {
            'schedule.queue.loading': 'Queue loading fixture',
            'schedule.queue.load-failed': 'Queue error fixture',
            'schedule.queue.retry': 'Queue retry fixture',
            'schedule.queue.no-title': 'Queue title missing fixture'
        },
        fetch: async () => httpResponse || ({ok: true, json: () => response})});
    t.after(() => h.stopPolling());
    const task = {id: 7, sourceType: 'user-new', runState: 'RUNNING', lastRunTime: 42};
    h.replaceTasks([task]);
    h.state.mode = 'schedule';
    h.setExpanded(7, true);
    await h.fetchQueue(7);
    h.dispatchQueueSse('123', {title: 'known title', presentationAttributes: {xRestrict: '1', ai: 'true', illustType: '1'},
        totalImages: 4, downloadedCount: 2});
    await h.fetchQueue(7);
    assert.equal(h.readQueueCache(7).items[0].rawTitle, 'known title');
    h.toggleQueue(7);
    h.refreshQueues();
    assert.equal(h.queueSseListenerCount('123'), 0);
    return {h, task, raw, body, setResponse: value => { response = value; },
        setHttpResponse: value => { httpResponse = value; }};
}

for (const failure of ['network', 'http-503']) {
    for (const startedTime of [100, 200]) {
        test(`缓存核验 ${failure} 显示错误，点击重试恢复轮次 ${startedTime}`, async t => {
            const {h, task, raw, body, setResponse, setHttpResponse} = await cachedQueueHarness(t);
            const pending = deferred();
            setHttpResponse(pending.promise);
            h.replaceTasks([{...task, runState: null, lastRunTime: 150, lastOutcome: 'ERROR'}]);
            h.toggleQueue(7);
            assert.equal(body.hidden, false);
            assert.match(body.innerHTML, /Queue loading fixture/);
            assert.doesNotMatch(body.innerHTML, /known title/);
            assert.equal(body.getAttribute('aria-busy'), 'true');
            if (failure === 'network') pending.resolve(Promise.reject(new Error('connection failed')));
            else pending.resolve({ok: false, status: 503});
            await new Promise(resolve => setImmediate(resolve));
            assert.match(body.innerHTML, /Queue error fixture/);
            assert.equal(body.getAttribute('aria-busy'), 'false');
            assert.equal(h.readQueueCache(7).items[0].rawTitle, 'known title');
            h.renderQueue(7);
            h.refreshQueues();
            assert.match(body.innerHTML, /Queue error fixture/, '重绘不能将未核验缓存当成当前事实');
            assert.doesNotMatch(body.innerHTML, /known title/);
            const retry = body.querySelector('button');
            assert.ok(retry);
            assert.equal(retry.textContent, 'Queue retry fixture');
            const recovery = deferred();
            setHttpResponse(null);
            setResponse(recovery.promise);
            const reads = h.fetchCount;
            retry.click();
            assert.equal(h.fetchCount, reads + 1);
            assert.match(body.innerHTML, /Queue loading fixture/);
            recovery.resolve({startedTime, items: [{...raw, status: 'failed'}]});
            await new Promise(resolve => setImmediate(resolve));
            assert.doesNotMatch(body.innerHTML, /Queue error fixture|Queue loading fixture/);
            const item = h.getQueueModel(7)[0];
            assert.equal(item.rawTitle, startedTime === 100 ? 'known title' : null);
            assert.equal(item.xRestrict, startedTime === 100 ? 1 : null);
            assert.equal(item.isAi, startedTime === 100);
            assert.equal(item.typeData.illustType, startedTime === 100 ? 1 : undefined);
            assert.match(body.innerHTML, startedTime === 100 ? /known title/ : /Queue title missing fixture/);
            retry.click();
            assert.equal(h.fetchCount, reads + 1, '已撤下的重试按钮失效');
        });
    }
}

for (const action of ['collapse', 'leave', 'hidden', 'deleted', 'next-run', 'replaced']) {
    for (const result of ['success', 'network', 'http-503']) {
        test(`缓存核验在 ${action} 后忽略旧 ${result} 响应`, async t => {
            const {h, task, raw, body, setHttpResponse} = await cachedQueueHarness(t);
            const pending = deferred();
            setHttpResponse(pending.promise);
            const finished = {...task, runState: null, lastRunTime: 150};
            h.replaceTasks([finished]);
            h.toggleQueue(7);
            assert.match(body.innerHTML, /Queue loading fixture/);
            if (action === 'collapse' || action === 'replaced') h.toggleQueue(7);
            if (action === 'leave') { h.stopPolling(); h.state.mode = 'user'; }
            if (action === 'hidden') h.document.visibilityState = 'hidden';
            if (action === 'deleted') h.replaceTasks([]);
            if (action === 'next-run') h.replaceTasks([{...finished, runState: 'RUNNING'}]);
            const replacement = deferred();
            if (action === 'replaced') { setHttpResponse(replacement.promise); h.toggleQueue(7); }
            const html = body.innerHTML;
            if (result === 'success') pending.resolve({ok: true, json: async () => ({startedTime: 100, items: []})});
            else if (result === 'network') pending.resolve(Promise.reject(new Error('old failure')));
            else pending.resolve({ok: false, status: 503});
            await new Promise(resolve => setImmediate(resolve));
            assert.equal(body.innerHTML, html);
            assert.equal(h.getQueueModel(7)[0].rawTitle, 'known title');
            assert.equal(h.queueSseListenerCount('123'), 0);
            if (action !== 'next-run') {
                const reads = h.fetchCount;
                for (let i = 0; i < 3; i++) h.refreshQueues();
                assert.equal(h.fetchCount, reads, '失效页面不补查，替代请求在途时不叠加读取');
            }
            if (action === 'replaced') {
                replacement.resolve({ok: true, json: async () => ({startedTime: 100, items: [{...raw, status: 'failed'}]})});
                await new Promise(resolve => setImmediate(resolve));
                assert.match(body.innerHTML, /known title/);
                assert.doesNotMatch(body.innerHTML, /Queue loading fixture|Queue error fixture/);
            }
        });
    }
}

for (const oldResult of ['success', 'network', 'http-503']) {
    for (const startedTime of [100, 200]) {
        test(`未观察到 RUNNING 时，过期 ${oldResult} 核验由普通轮询补查并确认轮次 ${startedTime}`, async t => {
            const {h, task, raw, body, setHttpResponse, setResponse} = await cachedQueueHarness(t);
            const pending = deferred();
            setHttpResponse(pending.promise);
            const finished = {...task, runState: null, lastRunTime: 150, lastOutcome: 'ERROR'};
            h.replaceTasks([finished]);
            h.toggleQueue(7);
            const reads = h.fetchCount;
            h.replaceTasks([{...finished, lastRunTime: 250}]);
            h.refreshQueues();
            assert.equal(h.fetchCount, reads, '旧请求未结算时保持单一在途请求');
            if (oldResult === 'success') pending.resolve({ok: true, json: async () => ({startedTime: 100, items: []})});
            else if (oldResult === 'network') pending.resolve(Promise.reject(new Error('stale request failed')));
            else pending.resolve({ok: false, status: 503});
            await new Promise(resolve => setImmediate(resolve));
            assert.equal(h.fetchCount, reads, '旧回调不自行发起读取');
            assert.equal(h.readQueueCache(7).lastRunTime, 42);
            assert.equal(h.readQueueCache(7).items[0].rawTitle, 'known title');

            const recovery = deferred();
            setHttpResponse(null);
            setResponse(recovery.promise);
            for (let i = 0; i < 3; i++) h.refreshQueues();
            assert.equal(h.fetchCount, reads + 1, '普通轮询补查当前任务且不叠加慢请求');
            recovery.resolve({startedTime, items: [{...raw, status: 'failed'}]});
            await new Promise(resolve => setImmediate(resolve));
            assert.equal(body.getAttribute('data-schedule-queue-feedback'), null);
            assert.notEqual(body.getAttribute('aria-busy'), 'true');
            assert.doesNotMatch(body.innerHTML, /Queue loading fixture|Queue error fixture/);
            assert.match(body.innerHTML, startedTime === 100 ? /known title/ : /Queue title missing fixture/);
            const item = h.getQueueModel(7)[0];
            assert.equal(item.rawTitle, startedTime === 100 ? 'known title' : null);
            assert.equal(item.xRestrict, startedTime === 100 ? 1 : null);
            assert.equal(item.isAi, startedTime === 100);
            assert.equal(item.typeData.illustType, startedTime === 100 ? 1 : undefined);
            assert.equal(h.readQueueCache(7).lastRunTime, 250);
            h.refreshQueues();
            assert.equal(h.fetchCount, reads + 1, '核验完成后不持续补查已结束任务');
        });
    }
}

test('未观察到 RUNNING 的补查失败后提供手动重试，普通轮询不循环重试错误', async t => {
    const {h, task, raw, body, setHttpResponse, setResponse} = await cachedQueueHarness(t);
    const pending = deferred();
    setHttpResponse(pending.promise);
    h.replaceTasks([{...task, runState: null, lastRunTime: 150}]);
    h.toggleQueue(7);
    h.replaceTasks([{...task, runState: null, lastRunTime: 250}]);
    pending.resolve({ok: true, json: async () => ({startedTime: 100, items: []})});
    await new Promise(resolve => setImmediate(resolve));
    setHttpResponse({ok: false, status: 503});
    h.refreshQueues();
    await new Promise(resolve => setImmediate(resolve));
    assert.match(body.innerHTML, /Queue error fixture/);
    assert.equal(body.getAttribute('aria-busy'), 'false');
    const reads = h.fetchCount;
    for (let i = 0; i < 3; i++) h.refreshQueues();
    assert.equal(h.fetchCount, reads);
    setHttpResponse(null);
    setResponse({startedTime: 100, items: [{...raw, status: 'failed'}]});
    body.querySelector('button').click();
    await new Promise(resolve => setImmediate(resolve));
    assert.match(body.innerHTML, /known title/);
    assert.doesNotMatch(body.innerHTML, /Queue loading fixture|Queue error fixture/);
    assert.equal(h.readQueueCache(7).lastRunTime, 250);
});

for (const scenario of [
    {name: '同轮失败', outcome: 'ERROR', status: 'failed', startedTime: 100, retain: true},
    {name: '同轮取消', outcome: 'CANCELLED', status: 'paused', startedTime: 100, retain: true},
    {name: '真实换轮', startedTime: 200},
    {name: '新轮空队列', startedTime: 200, empty: true},
    {name: '后端重启且完成记录未变', startedTime: null, empty: true, retain: true, lastRunTime: 42},
    {name: '后端重启且完成记录已变', outcome: 'ERROR', startedTime: null, empty: true}
]) {
    test(`收起后重新展开：${scenario.name}按响应确认缓存`, async t => {
        const {h, task, raw, setResponse} = await cachedQueueHarness(t);
        const pending = deferred();
        setResponse(pending.promise);
        h.replaceTasks([{...task, runState: scenario.outcome ? null : 'RUNNING',
            lastRunTime: scenario.lastRunTime ?? 150, lastOutcome: scenario.outcome}]);
        h.toggleQueue(7);
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(h.getQueueModel(7)[0]?.rawTitle, 'known title', '响应前保留待核验的内存模型');
        assert.equal(h.readQueueCache(7)?.items[0]?.rawTitle, 'known title', '响应前不删除持久缓存');
        pending.resolve({startedTime: scenario.startedTime,
            items: scenario.empty ? [] : [{...raw, status: scenario.status || 'pending',
                message: scenario.status === 'failed' ? 'pixiv.illust.download-failed' : null}]});
        await new Promise(resolve => setImmediate(resolve));
        const model = h.getQueueModel(7);
        if (scenario.retain) {
            assert.equal(model[0].rawTitle, 'known title');
            assert.equal(model[0].xRestrict, 1);
            assert.equal(model[0].isAi, true);
            assert.equal(model[0].typeData.illustType, 1);
            assert.equal(model[0].downloadedCount, 2);
            if (scenario.status) assert.equal(model[0].rawStatus, scenario.status);
            assert.equal(h.readQueueCache(7).items[0].rawTitle, 'known title');
        } else if (scenario.empty) {
            assert.equal(model.length, 0);
            assert.equal(h.readQueueCache(7).items.length, 0);
        } else {
            assert.equal(model[0].rawTitle, null);
            assert.equal(model[0].xRestrict, null);
            assert.equal(model[0].isAi, false);
            assert.equal(model[0].typeData.illustType, undefined);
            assert.equal(model[0].downloadedCount, 0);
            assert.equal(model[0].status, 'pending');
        }
        if (scenario.outcome) assert.equal(h.queueSseListenerCount('123'), 0);
    });
}

for (const action of ['collapse', 'leave', 'network-error']) {
    test(`重新展开核验缓存时 ${action} 不销毁原数据，迟到空响应不清空`, async t => {
        const {h, task, setResponse} = await cachedQueueHarness(t);
        const pending = deferred();
        setResponse(pending.promise);
        h.replaceTasks([{...task, runState: null, lastRunTime: 150, lastOutcome: 'ERROR'}]);
        h.toggleQueue(7);
        await new Promise(resolve => setImmediate(resolve));
        if (action === 'collapse') h.toggleQueue(7);
        if (action === 'leave') { h.stopPolling(); h.state.mode = 'user'; }
        if (action === 'network-error') pending.resolve(Promise.reject(new Error('response failed')));
        else pending.resolve({startedTime: null, items: []});
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(h.getQueueModel(7)[0]?.rawTitle, 'known title');
        assert.equal(h.readQueueCache(7)?.items[0]?.rawTitle, 'known title');
        assert.equal(h.queueSseListenerCount('123'), 0);
        if (action !== 'network-error') assert.equal(h.requests.at(-1).init.signal.aborted, true);
    });
}
