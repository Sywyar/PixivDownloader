'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function page() {
    const nodes = new Map();
    const node = id => {
        if (!nodes.has(id)) nodes.set(id, {value: '', hidden: false, disabled: false, textContent: '',
            elements: {'key-source': {value: 'restricted'}}, querySelectorAll: () => [], focus() {}});
        return nodes.get(id);
    };
    node('key-name').value = 'Fixture'; node('key-networks').value = '192.0.2.1';
    const calls = [];
    const context = vm.createContext({AbortController, document: {getElementById: node},
        fetch: (url, options) => new Promise((resolve, reject) => calls.push({url, options, resolve, reject})),
        PixivFeedback: {confirm: async () => true}, renderKeys() {}, closeKeyForm() {},
        location: {assign() {}, pathname: '/program-keys.html'}});
    for (const name of ['keys-core.js', 'keys-actions.js']) vm.runInContext(fs.readFileSync(
        path.join(__dirname, '../../main/resources/static/program-keys', name), 'utf8'), context);
    vm.runInContext("keyState.ready = true; keyI18n = {namespaces:['security','common'], t: key => key};", context);
    return {node, calls, run: source => vm.runInContext(source, context)};
}
const settle = () => new Promise(resolve => setImmediate(resolve));
const ok = body => ({ok: true, status: 200, json: async () => body});

test('离页再返回时，旧创建响应不能重新显示一次性秘密', async () => {
    const p = page();
    const saving = p.run('saveKey({preventDefault(){}})'); await settle();
    assert.equal(p.calls.length, 1);
    p.run('keyState.visit++; keyState.active = true;');
    p.calls[0].resolve(ok({secret: 'fixture-secret'})); await settle();
    assert.equal(p.node('key-secret').value, '');
    assert.equal(p.calls.length, 3);
    p.calls[1].resolve(ok([])); p.calls[2].resolve(ok([])); await saving;
    assert.equal(p.node('key-secret').value, '');
    assert.equal(p.run('keyState.busy || keyState.loading'), false);
});

test('保存结果不确定时禁止重复写入，刷新成功后恢复保存', async () => {
    const p = page();
    const saving = p.run('saveKey({preventDefault(){}})'); await settle();
    p.calls[0].reject(new Error('Network unavailable')); await saving;
    assert.equal(p.node('key-save').disabled, true);
    await p.run('saveKey({preventDefault(){}})'); assert.equal(p.calls.length, 1);
    const refresh = p.run('loadKeys()');
    p.calls[1].resolve(ok([])); p.calls[2].resolve(ok([])); await refresh;
    assert.equal(p.node('key-save').disabled, false);
    assert.equal(p.node('key-create').disabled, false);
});

test('查询在途阻止写入，撤销的查询不能替换当前列表', async () => {
    const p = page();
    const refresh = p.run('loadKeys()');
    await p.run('saveKey({preventDefault(){}})'); assert.equal(p.calls.length, 2);
    p.run('keyState.loadController.abort();');
    p.calls[0].resolve(ok([{id: 'old'}])); p.calls[1].resolve(ok([])); await refresh;
    assert.equal(p.run('keyState.keys.length'), 0);
    assert.equal(p.node('key-create').disabled, false);
});
