'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { test } = require('node:test');
const root = path.join(__dirname, '../../main/resources/static');
function page() {
    const sandbox = { console };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    for (const file of ['js/pixiv-plugin-presentation-tokens.js', 'plugin-market/plugin-market-core.js',
        'plugin-market/plugin-market-data.js', 'plugin-manage/plugin-manage-core.js', 'plugin-manage/plugin-manage-views.js']) {
        vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), sandbox);
    }
    return sandbox;
}

test('来源、版本审核和声明彼此独立，未知 token 保留且空声明不表示安全', () => {
    const p = page();
    const client = { t: (key, fallback) => key };
    const lines = p.PixivPluginPresentationTokens.trustLines;
    const base = { repositoryTrustSource: 'SELF_TRUSTED', assuranceLevel: 'PUBLISHER_SIGNED' };
    assert.ok(lines(base, client).some(line => line.includes('plugin-trust.unknown')));
    assert.ok(lines({ ...base, riskDeclaration: { present: false, signals: [] } }, client)
        .some(line => line.includes('plugin-trust.undeclared')));
    assert.ok(lines({ ...base, riskDeclaration: { present: true, signals: [] } }, client)
        .some(line => line.includes('plugin-trust.empty')));
    assert.ok(lines({ ...base, riskDeclaration: { present: true, signals: ['FUTURE_TOKEN'] } }, null)
        .some(line => line.includes('FUTURE_TOKEN')));
    assert.equal(p.PixivPluginMarket.data.entryOfficial({ assuranceLevel: 'PUBLISHER_SIGNED',
        market: { sourceType: 'official' } }), false);
    assert.ok(lines({ ...base, assuranceLevel: 'SOURCE_REVIEWED' }, client)
        .some(line => line.includes('plugin-trust.review-note')));
    assert.ok(!lines(base, client).some(line => line.includes('plugin-trust.review-note')));
});

test('市场与本地安装确认使用相同包内事实，并显示更新新增、移除及执行模式变化', () => {
    const p = page();
    const requirement = { pluginId: 'demo', version: '2.3.4', artifactSha256: 'ab'.repeat(32),
        signed: false, repositoryTrustSource: 'LOCAL', assuranceLevel: 'UNVERIFIED',
        executionMode: 'HOST_PROCESS_FULL_TRUST', previousExecutionMode: 'DECLARATIVE_PROCESS',
        riskDeclaration: { present: true, signals: ['FILE_WRITE', '<img src=x onerror=alert(1)>'] },
        previousRiskDeclaration: { present: true, signals: ['NETWORK'] } };
    for (const options of [p.PixivPluginMarket.trustConfirmationOptions(requirement),
        p.PixivPluginManage.trustConfirmationOptions(requirement)]) {
        assert.ok(options.message.includes('FILE_WRITE'));
        assert.ok(options.message.includes('NETWORK'));
        assert.ok(options.message.includes('Previous execution mode'));
        assert.ok(options.message.includes(requirement.artifactSha256));
    }
    p.PixivPluginManage.applyReport({ plugins: [{ id: 'demo', source: 'external', status: 'STARTED',
        executionMode: 'HOST_PROCESS_FULL_TRUST', verification: requirement, messages: [] }] }, true);
    const model = p.PixivPluginManage.allViewModels()[0];
    assert.ok(model.trustLines.some(line => line.includes('<img')));
    assert.ok(p.PixivPluginManage.escapeHtml(model.trustLines.join('\n')).includes('&lt;img'));
});
