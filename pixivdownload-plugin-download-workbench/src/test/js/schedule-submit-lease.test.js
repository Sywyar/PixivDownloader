'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
    deferred,
    taskCredentialPolicy,
    harness,
    source
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
