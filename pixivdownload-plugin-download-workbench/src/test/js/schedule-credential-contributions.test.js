'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
    deferred,
    taskCredentialPolicy,
    harness,
    source
} = require('./schedule-submit-test-support');

test('计划来源 context 只通过宿主 adapter 读取并回灌取得输入', () => {
    const h = harness({singleImportValue: 'original-input'});
    const context = h.sourceContext();
    const host = context.__scheduleAcquisitionHost;

    assert.equal(Object.prototype.hasOwnProperty.call(context, 'editing'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(context, 'admin'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(context, 'workTypeOwnerPluginId'), false);
    assert.equal(host.input('single-import'), 'original-input');
    assert.equal(host.input('search'), null);
    assert.equal(host.restore('single-import', 'restored-input'), true);
    assert.deepEqual(h.switchedModes, ['single-import']);
    assert.equal(h.element('single-import-textarea').value, 'restored-input');
    assert.equal(host.restore('search', 'ignored'), false);
    assert.deepEqual(h.switchedModes, ['single-import']);
});
test('宿主来源错误使用当前语言文案且插件校验消息保持原样', async () => {
    const cases = [
        ['SCHEDULE_SOURCE_EDITOR_UNAVAILABLE',
            'schedule.error.source-editor-unavailable', 'SOURCE_EDITOR_LOCALIZED'],
        ['SCHEDULE_SOURCE_EDITOR_AMBIGUOUS',
            'schedule.error.source-editor-ambiguous', 'SOURCE_AMBIGUOUS_LOCALIZED'],
        ['SCHEDULE_SOURCE_DEFINITION_INVALID',
            'schedule.error.source-definition-invalid', 'SOURCE_DEFINITION_LOCALIZED']
    ];
    for (const [code, key, message] of cases) {
        const error = new Error('raw host error');
        error.code = code;
        const localized = harness({captureError: error, translations: {[key]: message}});

        await localized.submit();

        assert.equal(localized.status.textContent, message);
        assert.equal(localized.fetchCount, 0);
    }

    const pluginValidation = harness({captureError: new Error('PLUGIN_LOCALIZED_VALIDATION')});
    await pluginValidation.submit();
    assert.equal(pluginValidation.status.textContent, 'PLUGIN_LOCALIZED_VALIDATION');
});

test('宿主只通过固定贡献方法绑定来源凭证且不持有凭证 URL', async () => {
    const h = harness({supportsCredential: true});

    const result = await h.applyOverrides(7, {
        supportsProxy: false,
        supportsCredential: true,
        credentialChecked: true,
        credentialValue: 'source-secret',
        useSavedCredential: false,
        presentation: {}
    }, null, 'source-a', new AbortController().signal);

    assert.equal(result.ok, true);
    assert.equal(result.applied, true);
    assert.equal(h.fetchCount, 0);
    assert.deepEqual(h.credentialCalls.map(call => call.method), ['bindCredential']);
    assert.equal(h.credentialCalls[0].sourceType, 'source-a');
    assert.equal(h.credentialCalls[0].taskId, 7);
    assert.equal(h.credentialCalls[0].credential, 'source-secret');
});

test('计划宿主不解释插件凭证状态且不保留任意 action 或凭证兼容 URL', () => {
    assert.doesNotMatch(source, /credentialActions|invokeCredentialAction/);
    assert.doesNotMatch(source, /cookieMode|cookieBound|accountId|ackWarningTime/);
    assert.doesNotMatch(source, /AUTH_EXPIRED|OVERUSE_PAUSED|PIXIV_OVERUSE/);
    assert.doesNotMatch(source, /PHPSESSID/i);
    assert.doesNotMatch(source, /\/api\/schedule\/account\/|\/authorize-cookie|\/revoke-cookie/);
});

test('选择已保存凭证只写入遮罩选择态并通过固定方法绑定', async () => {
    const h = harness({
        supportsCredential: true,
        sourcePreview: {sourceType: 'source-a'}
    });

    await h.fillSavedCredential('sch-cookie');

    assert.equal(h.element('sch-cookie').value, '');
    assert.equal(h.element('sch-cookie').dataset.useSavedCredential, 'true');
    assert.match(h.element('sch-cookie').placeholder, /••••••••/);
    assert.equal(h.fetchCount, 0);
    assert.deepEqual(h.credentialCalls, []);

    const result = await h.applyOverrides(8, {
        supportsProxy: false,
        supportsCredential: true,
        credentialChecked: true,
        credentialValue: '',
        useSavedCredential: true,
        presentation: {}
    }, null, 'source-a', new AbortController().signal);

    assert.equal(result.ok, true);
    assert.deepEqual(h.credentialCalls.map(call => call.method), ['bindSavedCredential']);
    assert.equal(h.element('sch-cookie').value, '');
});

test('宿主只消费 credentialPolicy wire 且私有状态仅由插件展示', () => {
    const h = harness({
        credentialTaskPresentation(_sourceType, task) {
            return task.credentialPolicy.statusCode === 'PLUGIN_PRIVATE_STATUS'
                ? {
                    statusLabel: '插件状态说明',
                    lightTone: 'red',
                    lightText: '插件状态灯说明',
                    suspended: true,
                    manualRecoveryRequired: true
                }
                : null;
        }
    });
    const task = {
        sourceType: 'source-a',
        lastStatus: 'OK',
        enabled: true,
        credentialPolicy: taskCredentialPolicy({
            bound: true,
            statusCode: 'PLUGIN_PRIVATE_STATUS',
            acknowledgedEventTime: 123
        })
    };

    const policy = h.credentialPolicy(task);
    assert.equal(policy.bound, true);
    assert.equal(policy.accountKey, 'account-a');
    assert.equal(policy.statusCode, 'PLUGIN_PRIVATE_STATUS');
    assert.equal(policy.legacy, undefined);
    assert.equal(h.statusLabel(task), '插件状态说明');
    const light = h.statusLight(task);
    assert.equal(light.tone, 'red');
    assert.equal(light.live, false);
    assert.equal(light.text, '插件状态灯说明');

    const absent = h.credentialPolicy({
        sourceType: 'source-a',
        cookieMode: 'legacy',
        cookieBound: true,
        accountId: 'legacy-account',
        ackWarningTime: 456
    });
    assert.equal(absent.bound, false);
    assert.equal(absent.available, false);
    assert.equal(absent.ownerPluginId, '');
    assert.equal(absent.accountKey, '');
    assert.equal(absent.acknowledgedEventTime, null);
    assert.equal(absent.legacy, undefined);
});

test('凭证策略动作完整转发复合 identity 且宿主不拼接账号接口', async () => {
    const h = harness();
    const identity = {
        ownerPluginId: 'example.plugin',
        policyId: 'policy-a',
        publicationId: 7,
        accountKey: 'account-a',
        suspendReason: 'POLICY',
        suspendCode: 'PLUGIN_INCIDENT'
    };
    h.setCredentialPolicyGroups([{
        sourceType: 'source-a',
        identity,
        identityKey: JSON.stringify(identity),
        title: '插件策略标题',
        description: '插件策略说明',
        actions: [{
            actionId: 'plugin-action',
            label: '执行插件动作',
            tone: 'primary',
            confirmMessage: null,
            prompt: {
                parameterName: 'delay',
                message: '输入延迟',
                defaultValue: '15',
                inputType: 'number',
                min: 10,
                step: 1
            }
        }]
    }]);

    const html = h.renderCredentialPolicyBanners([{
        sourceType: 'source-a', identity, title: '插件策略标题', description: '插件策略说明',
        actions: [{actionId: 'plugin-action', label: '执行插件动作', tone: 'primary'}]
    }]);
    assert.match(html, /data-credential-policy-group="0"/);
    assert.match(html, /执行插件动作/);
    assert.doesNotMatch(html, /onclick=/);

    await h.applyCredentialPolicyAction(0, 0, null);

    const call = h.credentialCalls.find(value => value.method === 'applyCredentialPolicyAction');
    assert.deepEqual(call.request.identity, identity);
    assert.equal(call.request.actionId, 'plugin-action');
    assert.equal(call.request.parameters.delay, 15);
    assert.equal(h.fetchCount, 0);
});

test('覆盖弹窗固定打开时 token 且 A→B 后不向新 publication 提交旧凭证', async () => {
    const h = harness({supportsCredential: true, response: {ok: true}});
    h.replaceTasks([{
        id: 7,
        sourceType: 'source-a',
        sourceActivationToken: 'token-a',
        credentialPolicy: taskCredentialPolicy()
    }]);

    h.showOverride(7);
    assert.equal(h.element('schedule-override-modal').dataset.sourceActivationToken, 'token-a');
    h.element('sch-ov-cookie-enabled').checked = true;
    h.element('sch-ov-cookie').value = 'PHPSESSID=old_secret';
    h.switchToken('token-b');

    await h.saveOverride();

    assert.equal(h.element('sch-ov-status').textContent, '任务状态已变化，请刷新后重试');
    assert.equal(h.requests.some(request => request.url.includes('/authorize-cookie')), false);
});

test('NONE、仅代理、仅凭证与来源缺席使用中性动作并只读降级', () => {
    const none = harness({sourceActive: true});
    const noneUi = none.credentialUi({
        sourceType: 'source-a', credentialPolicy: taskCredentialPolicy({bound: true})
    });
    assert.equal(noneUi.badgeLabel, null);
    assert.equal(noneUi.showOverride, false);
    assert.equal(noneUi.overrideLabel, '🌐 指定单独代理');
    assert.equal(noneUi.proxyLabel, '单独代理');

    const proxy = harness({sourceActive: true, supportsProxy: true});
    const proxyUi = proxy.credentialUi({
        sourceType: 'source-a', credentialPolicy: taskCredentialPolicy()
    });
    assert.equal(proxyUi.badgeLabel, null);
    assert.equal(proxyUi.showOverride, true);
    assert.equal(proxyUi.overrideLabel, '🌐 指定单独代理');
    assert.doesNotMatch(proxyUi.overrideLabel, /Pixiv|Cookie/i);

    const credential = harness({sourceActive: true, supportsCredential: true});
    const credentialUi = credential.credentialUi({
        sourceType: 'source-a', credentialPolicy: taskCredentialPolicy({bound: true})
    });
    assert.equal(credentialUi.badgeLabel, '已绑定凭证');
    assert.equal(credentialUi.showOverride, true);
    assert.equal(credentialUi.overrideLabel, '🔑 指定单独凭证');
    assert.doesNotMatch(credentialUi.overrideLabel, /Pixiv|Cookie/i);

    const missing = harness({sourceActive: false});
    const missingUi = missing.credentialUi({
        sourceType: 'source-a', credentialPolicy: taskCredentialPolicy({bound: true})
    });
    assert.equal(missingUi.badgeLabel, '已绑定凭证');
    assert.equal(missingUi.showOverride, false);
    const html = missing.renderTaskCard({
        id: 7,
        name: 'task',
        sourceType: 'source-a',
        sourceAvailable: false,
        sourceActivationToken: 'token-a',
        presentation: {attributes: {kind: 'work-a'}},
        enabled: true,
        credentialPolicy: taskCredentialPolicy({bound: true}),
        proxy: null,
        triggerKind: 'interval',
        intervalMinutes: 30,
        suspendReason: 'SOURCE_UNAVAILABLE'
    });
    assert.match(html, /showScheduleSnapshot\(7\)/);
    assert.match(html, /class="btn btn-purple" disabled[^>]+data-pixiv-click="startEditScheduleTask\(7\)"/);
    assert.doesNotMatch(html, /showScheduleOverrideModal\(7\)/);
    assert.match(html, /deleteScheduleTask\(7\)/);
    const snapshot = missing.renderSnapshot({
        id: 7,
        name: 'task',
        sourceType: 'source-a',
        sourceAvailable: false,
        presentation: {attributes: {kind: 'work-a'}},
        enabled: true,
        credentialPolicy: taskCredentialPolicy({bound: true}),
        triggerKind: 'interval',
        intervalMinutes: 30,
        lastStatus: 'AUTH_EXPIRED'
    });
    assert.doesNotMatch(snapshot, /Pixiv|Cookie|PHPSESSID/i);
});

test('异步 credentialContribution 违约会被隔离且吸收 rejection', async () => {
    const rejected = Promise.reject(new Error('async credential actions are forbidden'));
    const h = harness({credentialContributionResult: rejected});
    const ui = h.credentialUi({
        sourceType: 'source-a', credentialPolicy: taskCredentialPolicy({bound: true})
    });
    assert.equal(ui.showOverride, false);
    assert.equal(ui.badgeLabel, null);
    await new Promise(resolve => setImmediate(resolve));
});
