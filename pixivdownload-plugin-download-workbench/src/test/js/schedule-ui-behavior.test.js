'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {harness, deferred, taskCredentialPolicy} = require('./schedule-submit-test-support');

const translations = {
    'schedule.action.run': 'Run',
    'schedule.action.edit': 'Edit',
    'schedule.action.delete': 'Delete',
    'schedule.light.source-unavailable': 'Source unavailable',
    'schedule.light.executor-unavailable': 'Executor unavailable',
    'schedule.light.quiesced': 'Quiescing',
    'schedule.light.migration-error': 'Migration required',
    'schedule.light.interrupted': 'Interrupted',
    'schedule.light.ok': 'Succeeded',
    'schedule.meta.next-capability': 'Waiting for capability',
    'schedule.meta.next-suspended': 'Waiting for manual recovery'
};

function buttonDisabled(html, label) {
    const button = html.match(new RegExp(`<button\\b([^>]*)>${label}</button>`));
    assert.ok(button, `missing ${label} action`);
    return /\bdisabled\b/.test(button[1]);
}

test('能力阻断分别显示状态，阻止运行但保留空闲任务的编辑与删除', () => {
    const h = harness({translations});
    for (const [suspendReason, tone, text, automatic] of [
        ['SOURCE_UNAVAILABLE', 'red', 'Source unavailable', true],
        ['EXECUTOR_UNAVAILABLE', 'red', 'Executor unavailable', true],
        ['QUIESCED', 'yellow', 'Quiescing', true],
        ['MIGRATION_ERROR', 'red', 'Migration required', false]
    ]) {
        const task = {id: 7, name: 'task', sourceType: 'source-a', enabled: true, suspendReason};
        const light = h.statusLight(task);
        assert.deepEqual({...light}, {tone, live: false, text});
        const html = h.renderTaskCard(task);
        assert.equal(buttonDisabled(html, 'Run'), true);
        assert.equal(buttonDisabled(html, 'Edit'), false);
        assert.equal(buttonDisabled(html, 'Delete'), false);
        assert.ok(html.includes(automatic ? 'Waiting for capability' : 'Waiting for manual recovery'));
        assert.equal(buttonDisabled(h.renderTaskCard({...task, sourceAvailable: false}), 'Edit'), true);
        for (const runState of ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED']) {
            const busy = h.renderTaskCard({...task, runState});
            for (const label of ['Run', 'Edit', 'Delete']) assert.equal(buttonDisabled(busy, label), true);
        }
    }
    const available = h.renderTaskCard({id: 7, sourceType: 'source-a', enabled: true});
    assert.equal(buttonDisabled(available, 'Run'), false);
    assert.equal(buttonDisabled(h.renderTaskCard({id: 7, enabled: false}), 'Run'), true);
});

test('中断结果驱动恢复状态灯，遗留开始时间不能把成功任务显示为中断', () => {
    const h = harness({translations});
    assert.equal(h.statusLight({enabled: true, lastOutcome: 'INTERRUPTED'}).text, 'Interrupted');
    assert.equal(h.statusLight({enabled: true, lastStatus: 'OK', runStartedTime: 123}).text, 'Succeeded');
});

test('来源缺席时快照展示已保存摘要，不解释来源私有定义', () => {
    const h = harness({sourceActive: false});
    for (const presentationField of ['presentation', 'presentationJson']) {
        const presentation = {title: 'Saved title', summary: 'Saved summary', attributes: {account: 'Saved account'}};
        const html = h.renderSnapshot({
            id: 7, sourceType: 'unknown.source', sourceAvailable: false,
            definitionJson: '{"private":"DO_NOT_RENDER"}',
            [presentationField]: presentationField === 'presentation' ? presentation : JSON.stringify(presentation)
        });
        for (const text of ['Saved title', 'Saved summary', 'Saved account']) assert.ok(html.includes(text));
        assert.doesNotMatch(html, /DO_NOT_RENDER/);
    }
});

test('待重试详情按首个有效原因解析，空白字段继续查找后续机器码', () => {
    const h = harness({pluginTranslations: {'example:failure': 'Localized failure'}});
    for (const field of ['message', 'reason', 'reasonCode', 'legacyReason']) {
        const detail = {message: ' ', reason: null, reasonCode: '', legacyReason: ''};
        detail[field] = 'example.failure';
        assert.equal(h.pendingReason({reasonDetailJson: JSON.stringify(detail)}, 'source-a'), 'Localized failure');
    }
});

for (const entry of ['editor', 'override']) {
    for (const clear of ['proxy', 'credential']) {
        test(`${entry} 清除 ${clear} 等待确认，取消零写入，确认后保留失败与成功结果`, async () => {
            for (const [confirmed, ok] of [[false, true], [true, false], [true, true]]) {
                const confirm = deferred();
                const h = harness({
                    confirm, supportsProxy: clear === 'proxy', supportsCredential: clear === 'credential',
                    revokeCredentialResult: {ok, error: 'Cannot revoke'},
                    fetch: async url => ({
                        ok: entry === 'editor' && url === '/api/schedule/tasks/7' ? true : ok,
                        json: async () => ({id: 7, error: 'Cannot save'})
                    })
                });
                h.setEditing(7, [{
                    id: 7, sourceType: 'source-a', sourceActivationToken: 'token-a', stateVersion: 4,
                    proxy: clear === 'proxy' ? '127.0.0.1:7890' : null,
                    credentialPolicy: taskCredentialPolicy({bound: clear === 'credential'})
                }]);
                if (entry === 'override') {
                    h.showOverride(7);
                    h.element(`sch-ov-${clear === 'proxy' ? 'proxy' : 'cookie'}-enabled`).checked = false;
                }
                const pending = entry === 'editor' ? h.submit() : h.saveOverride();
                await Promise.resolve();
                await Promise.resolve();
                assert.equal(h.confirmCount, 1);
                assert.equal(h.confirmCalls[0].key, `schedule.confirm.clear-${clear}`);
                assert.equal(h.fetchCount, 0);
                assert.equal(h.credentialCalls.length, 0);
                confirm.resolve(confirmed);
                await pending;
                if (!confirmed) {
                    assert.equal(h.fetchCount, 0);
                    assert.equal(h.credentialCalls.length, 0);
                    if (entry === 'override') assert.equal(h.element('schedule-override-modal').hidden, false);
                    continue;
                }
                if (entry === 'editor') {
                    const update = h.requests[0];
                    assert.equal(update.url, '/api/schedule/tasks/7');
                    assert.equal(update.init.method, 'PUT');
                    assert.equal(JSON.parse(update.init.body).expectedStateVersion, 4);
                }
                if (clear === 'proxy') {
                    const update = h.requests.at(-1);
                    assert.equal(update.url, '/api/schedule/tasks/7/proxy');
                    assert.equal(update.init.method, 'POST');
                    assert.deepEqual(JSON.parse(update.init.body), {proxy: null});
                } else {
                    assert.equal(h.credentialCalls.length, 1);
                    assert.equal(h.credentialCalls[0].method, 'revokeCredential');
                    assert.equal(h.credentialCalls[0].taskId, 7);
                    assert.equal(h.credentialCalls[0].sourceType, 'source-a');
                }
                if (entry === 'override') {
                    assert.equal(h.element('schedule-override-modal').hidden, ok);
                    if (!ok) assert.equal(h.element('sch-ov-status').textContent,
                        clear === 'proxy' ? 'Cannot save' : 'Cannot revoke');
                } else {
                    assert.equal(h.status.style.color, ok ? 'success' : 'error');
                    if (!ok) assert.ok(h.status.textContent.includes(clear === 'proxy' ? 'Cannot save' : 'Cannot revoke'));
                }
            }
        });
    }
}

test('待重试按钮使用数据身份提交 JSON，忙碌状态禁用，失败保留当前列表', async () => {
    const workType = 'example.work';
    const workId = 'opaque/a:b?x=1+%雪';
    for (const [runState, ok] of [[null, true], [null, false], ['RUNNING', true], ['QUEUED', true], ['CANCEL_REQUESTED', true]]) {
        let click;
        const h = harness({
            queueTypes: {manifestDescriptor: () => ({displayNamespace: 'example', displayI18nKey: 'work'})},
            pluginTranslations: {'example:work': 'Example work'},
            translations: {
                'schedule.pending.item': '{workType}|{workId}|{attempts}|{manual}',
                'schedule.pending.needs-manual': 'Manual'
            },
            fetch: async (_url, init) => ({
                ok: init.method === 'DELETE' ? ok : true,
                json: async () => [{workType, workId, attempts: 3, needsManual: true}]
            })
        });
        h.setEditing(null, [{id: 7, sourceType: 'source-a', runState}]);
        h.state.mode = 'schedule';
        h.addElement('schedule-pending-7', {querySelectorAll: () => [{
            dataset: {workType, workId},
            addEventListener(event, handler) { assert.equal(event, 'click'); click = handler; }
        }]});
        await h.loadPending(7);
        const panelHtml = h.element('schedule-pending-7').innerHTML;
        assert.ok(panelHtml.includes(`Example work|${workId}|3|Manual`));
        assert.ok(panelHtml.includes(`data-work-type="${workType}"`));
        assert.ok(panelHtml.includes(`data-work-id="${workId}"`));
        assert.doesNotMatch(panelHtml, /onclick=/);
        assert.equal(/<button\b[^>]*\bdisabled\b/.test(panelHtml), runState !== null);
        assert.equal(typeof click, 'function');
        if (runState) continue;
        await click();
        const request = h.requests[1];
        assert.equal(request.url, '/api/schedule/tasks/7/pending');
        assert.equal(request.init.method, 'DELETE');
        assert.equal(request.init.headers['Content-Type'], 'application/json');
        assert.equal(request.init.credentials, 'same-origin');
        assert.deepEqual(JSON.parse(request.init.body), {workType, workId});
        assert.equal(h.requests.length, ok ? 3 : 2);
        if (!ok) assert.equal(h.element('schedule-pending-7').innerHTML, panelHtml);
    }
});

test('待重试查询在刷新、收起及离页后不发布旧结果或错误', async () => {
    for (const action of ['refresh', 'collapse', 'leave']) {
        for (const fails of [false, true]) {
            const old = deferred();
            let reads = 0;
            const h = harness({fetch: async () => ++reads === 1
                ? {ok: true, json: () => old.promise}
                : {ok: true, json: async () => []},
            translations: {'schedule.pending.empty': 'Empty'}});
            h.state.mode = 'schedule';
            h.replaceTasks([{id: 7, sourceType: 'source-a'}]);
            const panel = h.addElement('schedule-pending-7', {hidden: false,
                innerHTML: 'Before', querySelectorAll: () => []});
            const pending = h.loadPending(7);
            await new Promise(resolve => setImmediate(resolve));
            if (action === 'refresh') await h.loadPending(7);
            if (action === 'collapse') await h.togglePending(7);
            if (action === 'leave') { h.stopPolling(); h.state.mode = 'user'; }
            const expected = panel.innerHTML;
            old.resolve(fails ? Promise.reject(new Error('Old failure'))
                : [{workType: 'example', workId: 'old', attempts: 0}]);
            await pending;
            assert.equal(panel.innerHTML, expected, `${action}/${fails}`);
            assert.equal(h.requests[0].init.signal.aborted, true);
        }
    }
});
