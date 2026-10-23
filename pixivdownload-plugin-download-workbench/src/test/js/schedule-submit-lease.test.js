'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
    deferred,
    taskCredentialPolicy,
    harness
} = require('./schedule-submit-test-support');

test('validation await 期间 A→B 后旧 submit 零状态写与零请求', async () => {
    const validation = deferred();
    const h = harness({validation, supportsCredential: true, cookieChecked: true, cookieValue: 'cookie'});
    const pending = h.submit();
    h.stale();
    validation.resolve('old validation error');
    await pending;
    assert.equal(h.status.textContent, '');
    assert.equal(h.fetchCount, 0);
    assert.equal(h.confirmCount, 0);
});
test('full-fetch confirm await 期间 A→B 后旧 submit 不继续请求', async () => {
    const confirm = deferred();
    const h = harness({confirm, fetchLimitMode: 'per-run'});
    const pending = h.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(h.confirmCount, 1);
    assert.equal(h.confirmCalls[0].key, 'schedule.confirm.full-fetch');
    assert.doesNotMatch(h.confirmCalls[0].fallback, /Pixiv/i);
    h.stale();
    confirm.resolve(true);
    await pending;
    assert.equal(h.status.textContent, '');
    assert.equal(h.fetchCount, 0);
});

test('有界来源无需全量确认时，创建与编辑均直接保存零上限', async () => {
    for (const editing of [false, true]) {
        const h = harness({
            fetchLimitMode: 'per-run',
            fetchLimitPresentation: {fullFetchConfirmRequired: false},
            response: {ok: true, json: async () => ({id: 7})}
        });
        if (editing) h.setEditing(7, [{id: 7, sourceType: 'source-a'}]);
        await h.submit();
        assert.equal(h.confirmCount, 0);
        const request = h.requests.find(value => value.init.method === (editing ? 'PUT' : 'POST'));
        assert.ok(request);
        assert.equal(JSON.parse(JSON.parse(request.init.body).definitionJson).fetchLimit, 0);
    }
});

test('来源抓取提示使用受控 key 且未知来源保持中性文案', async () => {
    const generic = harness({
        sourcePreview: {fetchLimitMode: 'watermark', fetchLimitPresentation: null}
    });
    generic.updateFetchLimit();
    assert.equal(generic.element('sch-fetch-limit-row').style.display, '');
    assert.doesNotMatch(generic.element('sch-fetch-limit-hint-watermark').textContent, /Pixiv/i);

    const confirm = deferred();
    const pixiv = harness({
        confirm,
        fetchLimitMode: 'watermark',
        fetchLimitPresentation: {
            namespace: 'batch',
            watermarkHintKey: 'schedule.pixiv.fetch-limit.hint.watermark',
            perRunHintKey: 'schedule.pixiv.fetch-limit.hint.per-run',
            fullFetchConfirmKey: 'schedule.pixiv.confirm.full-fetch'
        },
        sourcePreview: {
            fetchLimitMode: 'per-run',
            fetchLimitPresentation: {
                namespace: 'batch',
                watermarkHintKey: 'schedule.pixiv.fetch-limit.hint.watermark',
                perRunHintKey: 'schedule.pixiv.fetch-limit.hint.per-run',
                fullFetchConfirmKey: 'schedule.pixiv.confirm.full-fetch'
            }
        },
        translations: {
            'batch:schedule.pixiv.fetch-limit.hint.per-run': 'PIXIV_SOURCE_RISK'
        }
    });
    pixiv.updateFetchLimit();
    assert.equal(pixiv.element('sch-fetch-limit-hint-per-run').textContent, 'PIXIV_SOURCE_RISK');
    const pending = pixiv.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(pixiv.confirmCalls[0].key, 'batch:schedule.pixiv.confirm.full-fetch');
    confirm.resolve(false);
    await pending;
});

test('清除确认只使用校验后的来源 key，第三方默认文案保持中性', async () => {
    const proxyConfirm = deferred();
    const proxy = harness({confirm: proxyConfirm, supportsProxy: true});
    proxy.setEditing(7, [{id: 7, sourceType: 'source-a', proxy: '127.0.0.1:7890'}]);
    const proxyPending = proxy.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(proxy.confirmCalls[0].key, 'schedule.confirm.clear-proxy');
    assert.doesNotMatch(proxy.confirmCalls[0].fallback, /Pixiv|Cookie|R-18|我的收藏/i);
    proxyConfirm.resolve(false);
    await proxyPending;

    const credentialConfirm = deferred();
    const credential = harness({confirm: credentialConfirm, supportsCredential: true});
    credential.setEditing(8, [{
        id: 8,
        sourceType: 'source-a',
        credentialPolicy: taskCredentialPolicy({bound: true})
    }]);
    const credentialPending = credential.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(credential.confirmCalls[0].key, 'schedule.confirm.clear-credential');
    assert.doesNotMatch(credential.confirmCalls[0].fallback, /Pixiv|Cookie|R-18|我的收藏/i);
    credentialConfirm.resolve(false);
    await credentialPending;

    const pixivConfirm = deferred();
    const pixiv = harness({
        confirm: pixivConfirm,
        supportsProxy: true,
        descriptorNamespace: 'batch',
        credentialPresentation: {
            clearProxyConfirmI18nKey: 'batch:schedule.pixiv.confirm.clear-proxy'
        }
    });
    pixiv.setEditing(9, [{id: 9, sourceType: 'source-a', proxy: '127.0.0.1:7890'}]);
    const pixivPending = pixiv.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(pixiv.confirmCalls[0].key, 'batch:schedule.pixiv.confirm.clear-proxy');
    pixivConfirm.resolve(false);
    await pixivPending;

    const forgedConfirm = deferred();
    const forged = harness({
        confirm: forgedConfirm,
        supportsProxy: true,
        descriptorNamespace: 'example',
        credentialPresentation: {}
    });
    forged.setEditing(10, [{id: 10, sourceType: 'source-a', proxy: '127.0.0.1:7890'}]);
    const forgedPending = forged.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(forged.confirmCalls[0].key, 'schedule.confirm.clear-proxy');
    forgedConfirm.resolve(false);
    await forgedPending;
});

test('删除确认在来源缺席时不泄漏 Cookie 或 Pixiv 语义', async () => {
    const confirm = deferred();
    const h = harness({confirm, sourceActive: false});
    const pending = h.deleteTask(7);
    assert.equal(h.confirmCalls[0].key, 'schedule.confirm.delete');
    assert.doesNotMatch(h.confirmCalls[0].fallback, /Pixiv|Cookie|PHPSESSID/i);
    confirm.resolve(false);
    await pending;
});

test('override-clear confirm await 期间 A→B 后旧 submit 不继续请求', async () => {
    const confirm = deferred();
    const h = harness({confirm, supportsProxy: true});
    h.setEditing(7, [{id: 7, sourceType: 'source-a', proxy: '127.0.0.1:7890'}]);
    const pending = h.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(h.confirmCount, 1);
    h.stale();
    confirm.resolve(true);
    await pending;
    assert.equal(h.status.textContent, '');
    assert.equal(h.fetchCount, 0);
});

test('非 2xx error JSON await 期间 A→B 后旧错误不回写表单', async () => {
    const errorBody = deferred();
    const response = {ok: false, json: () => errorBody.promise};
    const h = harness({response});
    const pending = h.submit();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(h.fetchCount, 1);
    h.stale();
    errorBody.resolve({error: 'old publication error'});
    await pending;
    assert.equal(h.status.textContent, '');
});

test('编辑提交固定打开表单时的版本且轮询新 cache 不得抬高版本', async () => {
    const response = {ok: false, json: () => Promise.resolve({error: 'conflict'})};
    const h = harness({response});
    h.setEditing(7, [{
        id: 7,
        sourceType: 'source-a',
        sourceActivationToken: 'token-a',
        stateVersion: 4
    }]);
    h.replaceTasks([{
        id: 7,
        sourceType: 'source-a',
        sourceActivationToken: 'token-a',
        stateVersion: 9
    }]);

    await h.submit();

    assert.equal(h.fetchCount, 1);
    assert.equal(h.requests[0].url, '/api/schedule/tasks/7');
    assert.equal(h.requests[0].init.method, 'PUT');
    assert.equal(JSON.parse(h.requests[0].init.body).expectedStateVersion, 4);
});

test('同来源编辑 A 等待校验时切到 B 后旧提交零请求', async () => {
    const validation = deferred();
    const h = harness({validation, supportsCredential: true, cookieChecked: true, cookieValue: 'cookie'});
    h.setEditing(7, [{
        id: 7,
        sourceType: 'source-a',
        sourceActivationToken: 'token-a',
        stateVersion: 4
    }]);
    const pending = h.submit();
    h.setEditing(8, [{
        id: 8,
        sourceType: 'source-a',
        sourceActivationToken: 'token-a',
        stateVersion: 6
    }]);
    validation.resolve(null);

    await pending;

    assert.equal(h.fetchCount, 0);
    assert.equal(h.status.textContent, '');
});

for (const reopenId of [7, 8]) {
    test(`专用设置校验期间关闭并打开任务 ${reopenId}，旧保存不再提交`, async () => {
        const validation = deferred();
        const h = harness({validation, supportsCredential: true});
        h.replaceTasks([7, 8].map(id => ({
            id, sourceType: 'source-a', sourceActivationToken: 'token-a'
        })));
        h.showOverride(7);
        h.element('sch-ov-cookie-enabled').checked = true;
        h.element('sch-ov-cookie').value = 'credential-a';
        const pending = h.saveOverride();
        h.closeOverride();
        h.showOverride(reopenId);
        validation.resolve(null);
        await pending;
        assert.deepEqual(h.credentialCalls.map(call => call.method), ['validateCredential']);
        assert.equal(h.element('schedule-override-modal').hidden, false);
        assert.equal(h.element('sch-ov-status').textContent, '');
    });
}

for (const ok of [true, false]) {
    test(`专用设置写入${ok ? '成功' : '失败'}不会关闭或污染新弹窗`, async () => {
        const response = deferred();
        const h = harness({supportsProxy: true, fetch: () => response.promise});
        h.replaceTasks([7, 8].map(id => ({
            id, sourceType: 'source-a', sourceActivationToken: 'token-a'
        })));
        h.showOverride(7);
        h.element('sch-ov-proxy-enabled').checked = true;
        h.element('sch-ov-proxy').value = '127.0.0.1:7890';
        const pending = h.saveOverride();
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(h.fetchCount, 1);
        h.closeOverride();
        h.showOverride(8);
        h.element('sch-ov-proxy').value = 'new-draft:8080';
        response.resolve({ok, json: async () => ({error: 'Old failure'})});
        await pending;
        assert.equal(h.element('schedule-override-modal').hidden, false);
        assert.equal(h.element('sch-ov-status').textContent, '');
        assert.equal(h.element('sch-ov-proxy').value, 'new-draft:8080');
        assert.equal(h.requests[0].url, '/api/schedule/tasks/7/proxy');
    });
}

test('专用设置保存期间重复点击只校验和提交一次，失败允许重试', async () => {
    const validation = deferred();
    const h = harness({validation, supportsCredential: true,
        bindCredentialResult: {ok: false, error: 'Cannot bind'}});
    h.replaceTasks([{id: 7, sourceType: 'source-a', sourceActivationToken: 'token-a'}]);
    h.showOverride(7);
    h.element('sch-ov-cookie-enabled').checked = true;
    h.element('sch-ov-cookie').value = 'credential-a';
    const first = h.saveOverride();
    const second = h.saveOverride();
    validation.resolve(null);
    await Promise.all([first, second]);
    assert.deepEqual(h.credentialCalls.map(call => call.method), ['validateCredential', 'bindCredential']);
    assert.equal(h.element('sch-ov-status').textContent, 'Cannot bind');
    await h.saveOverride();
    assert.equal(h.credentialCalls.filter(call => call.method === 'bindCredential').length, 2);
});
