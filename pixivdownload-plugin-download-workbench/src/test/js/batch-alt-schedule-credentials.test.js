'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {setup} = require('./batch-alt-schedule-session-support');
const {source, manifest, harness, validInitializer} = require('./schedule-sources-test-support');

async function editor(options = {}) {
    const h = setup({credentialPolicy: {bound: true}});
    const calls = [];
    const operation = name => (...args) => {
        calls.push({name, args});
        return options[name]?.(...args) || {ok: true};
    };
    const runtime = harness([manifest(1, [source({sourceType: 'search', acquisitionModes: ['search'],
        possibleWorkTypes: ['illust', 'novel']})])], new Map([
        ['/plugins/source-a.js', validInitializer('/plugins/source-a.js', {
            ...h.handlers.get('search'),
            credentialContribution: () => ({supportsCredential: true, supportsProxy: true,
                ...options.capabilities}),
            validateCredential: (...args) => {
                calls.push({name: 'validate', args});
                return options.validate?.(...args) || null;
            },
            bindCredential: operation('bind'),
            bindSavedCredential: operation('saved'),
            revokeCredential: operation('revoke')
        })]
    ]));
    await runtime.refresh(false);
    h.load('alt-schedule-actions.js');
    h.context.PixivBatch.scheduleSources = runtime;
    h.context.bt = key => key;
    let modal = null;
    let closes = 0;
    h.context.openModal = spec => {
        modal?.body.remove();
        modal = spec;
        h.context.document.body.append(spec.body);
        Object.defineProperty(spec.body, 'isConnected', {get: () => !!spec.body.parentNode});
    };
    h.context.closeModal = () => { closes++; modal.body.remove(); };
    const button = key => modal.body.querySelectorAll('button').find(node =>
        node.textContent === key || node.querySelector('span')?.textContent === key);
    const click = key => button(key).listeners.get('click')[0]();
    h.context.openScheduleOverride(h.task);
    return {h, runtime, calls, button, click,
        get modal() { return modal; }, get closes() { return closes; },
        credential: () => modal.body.querySelector('input[type="password"]'),
        proxy: () => modal.body.querySelector('input[type="text"]'),
        status: () => modal.body.querySelector('[role="status"]').textContent};
}

test('新版计划读取真实来源能力和通用绑定状态，撤回后只保留绑定事实', async t => {
    const e = await editor();
    t.after(() => e.runtime.dispose());
    assert.equal(e.h.context.scheduleTaskCredentialUi(e.h.task).showOverride, true);
    assert.equal(e.h.context.scheduleTaskCredentialUi(e.h.task).badgeLabel, 'schedule.credential.bound');
    assert.ok(e.button('schedule.override.clear-credential'));
    e.runtime.dispose();
    const ui = e.h.context.scheduleTaskCredentialUi(e.h.task);
    assert.equal(ui.showOverride, false);
    assert.equal(ui.badgeLabel, 'schedule.credential.bound');
    await e.click('common.save');
    assert.equal(e.calls.length, 0);
    assert.equal(e.closes, 0);
    assert.equal(e.status(), 'schedule.error.source-editor-unavailable');
});

test('计划代理与凭证弹窗的字段有标签，显隐切换不回显已绑定凭证', async t => {
    const e = await editor();
    t.after(() => e.runtime.dispose());
    assert.equal(e.modal.id, 'schedule-override');
    assert.ok(e.proxy().getAttribute('aria-label'));
    const credential = e.credential();
    assert.ok(credential.getAttribute('aria-label'));
    assert.equal(credential.value, '');
    const toggle = e.button('cookie.toggle.show');
    await e.click('cookie.toggle.show');
    assert.equal(credential.type, 'text');
    assert.equal(toggle.getAttribute('aria-pressed'), 'true');
    await e.click('cookie.toggle.hide');
    assert.equal(credential.type, 'password');
    assert.equal(toggle.getAttribute('aria-pressed'), 'false');
});

test('任务保存后凭证绑定失败，重试复用任务和代理响应的版本且保留并发冲突', async t => {
    for (const editing of [false, true]) {
        const e = await editor({bind: () => ({ok: false, error: 'fixture rejection'})});
        t.after(() => e.runtime.dispose());
        e.h.confirm(true);
        const requests = [];
        e.h.context.fetch = async (url, init) => {
            requests.push({url, method: init.method, body: JSON.parse(init.body)});
            return {ok: requests.length < 3, json: async () => ({...e.h.task,
                proxy: requests.length > 1 ? '127.0.0.1:8080' : null,
                stateVersion: 9 + requests.length, error: 'Version conflict'})};
        };
        e.h.run("state.mode='search'; searchState.word='fixture'; searchState.maxPages=1;");
        e.h.context.openScheduleEditor(editing ? e.h.task : null);
        if (editing) e.h.options();
        e.h.field('abScheduleField0').value = 'Fixture schedule';
        e.h.field('abScheduleField4').value = '127.0.0.1:8080';
        e.h.field('abScheduleField5').setCredential('fixture-value');
        const save = () => editing ? e.h.save()
            : e.h.drawer.footer.querySelector('.ab-btn--primary').listeners.get('click')[0]();
        await save();
        assert.equal(e.h.field('abScheduleField5').readCredential(), 'fixture-value');
        await save();
        assert.deepEqual(requests.map(request => request.method), [editing ? 'PUT' : 'POST', 'POST', 'PUT']);
        assert.equal(requests[2].url, '/api/schedule/tasks/42');
        assert.equal(requests[2].body.expectedStateVersion, 11);
        assert.equal(e.calls.filter(call => call.name === 'bind').length, 1);
        const statuses = e.h.context.document.body.querySelectorAll('[role="status"]');
        assert.match(statuses.at(-1).textContent, /Version conflict/);
    }
});

test('已保存凭证仅记录遮罩选择，由来源执行绑定且缺失时保留表单', async t => {
    const e = await editor({saved: () => ({ok: false, status: 'missing'})});
    t.after(() => e.runtime.dispose());
    await e.click('schedule.override.use-saved');
    assert.equal(e.credential().value, '');
    assert.match(e.credential().placeholder, /••••••••/);
    assert.equal(e.calls.length, 0);
    await e.click('common.save');
    assert.deepEqual(e.calls.map(call => call.name), ['saved']);
    assert.equal(e.calls[0].args[0], e.h.task.id);
    assert.equal(e.calls[0].args.at(-1).activationToken, 'activation-a');
    assert.equal(e.credential().value, '');
    assert.equal(e.status(), 'schedule.error.no-credential');
    assert.equal(e.closes, 0);
    assert.equal(e.modal.beforeClose(), true);
});

test('多行粘贴替换已保存选择，校验和绑定消费同一完整凭证', async t => {
    const e = await editor();
    t.after(() => e.runtime.dispose());
    await e.click('schedule.override.use-saved');
    const value = 'fixture-one\nfixture-two';
    for (const listener of e.credential().listeners.get('paste')) {
        listener({clipboardData: {getData: () => value}, preventDefault() {}});
    }
    await e.click('common.save');
    assert.deepEqual(e.calls.map(call => call.name), ['validate', 'bind']);
    assert.equal(e.calls[0].args[0], value);
    assert.equal(e.calls[1].args[1], value);
    assert.equal(e.h.requests.length, 0, '宿主不绕过来源直接发送凭证请求');
    assert.equal(e.closes, 1);
    e.credential().value = 'edited';
    e.credential().dispatchEvent({type: 'input'});
    assert.equal(e.credential().readCredential(), 'edited');
});

test('新建与编辑保存期间锁定字段，失败后恢复原有禁用状态', async t => {
    for (const editing of [false, true]) {
        let finish;
        const validation = new Promise(resolve => { finish = resolve; });
        const e = await editor({validate: () => validation});
        t.after(() => e.runtime.dispose());
        e.h.run("state.mode='search'; searchState.word='fixture'; searchState.maxPages=1;");
        e.h.context.openScheduleEditor(editing ? e.h.task : null);
        if (editing) e.h.options();
        const name = e.h.field('abScheduleField0');
        const credential = e.h.field('abScheduleField5');
        name.value = 'Fixture schedule';
        credential.setCredential('fixture-value');
        const disabled = e.h.field('abScheduleField4');
        disabled.disabled = true;
        const save = () => editing ? e.h.save()
            : e.h.drawer.footer.querySelector('.ab-btn--primary').listeners.get('click')[0]();
        const saving = save();
        assert.equal(name.disabled, true);
        assert.equal(credential.disabled, true);
        assert.equal(e.h.drawer.beforeClose(), false);
        await save();
        assert.equal(e.calls.length, 1);
        finish('fixture validation');
        await saving;
        assert.equal(name.disabled, false);
        assert.equal(credential.disabled, false);
        assert.equal(disabled.disabled, true);
        assert.equal(credential.readCredential(), 'fixture-value');
        assert.equal(e.h.requests.length, 0);
    }
});

test('凭证保存期间禁止重复提交和关闭，失败保留输入与原控制状态', async t => {
    let finish;
    const pending = new Promise(resolve => { finish = resolve; });
    const e = await editor({bind: () => pending});
    t.after(() => e.runtime.dispose());
    e.credential().setCredential('fixture-value');
    const save = e.click('common.save');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(e.modal.beforeClose(), false);
    assert.equal(e.credential().disabled, true);
    await e.click('common.save');
    assert.equal(e.calls.filter(call => call.name === 'bind').length, 1);
    finish({ok: false, status: 'failed', error: 'fixture rejection'});
    await save;
    assert.equal(e.credential().readCredential(), 'fixture-value');
    assert.equal(e.credential().disabled, false);
    assert.equal(e.status(), 'fixture rejection');
    assert.equal(e.modal.beforeClose(), true);
    assert.equal(e.closes, 0);
});

test('校验期间来源撤回后不写代理或凭证，绑定晚结果不关闭新弹窗', async t => {
    let validated;
    const validation = new Promise(resolve => { validated = resolve; });
    const e = await editor({validate: () => validation});
    t.after(() => e.runtime.dispose());
    e.credential().setCredential('fixture-value');
    e.proxy().value = '127.0.0.1:8080';
    const save = e.click('common.save');
    e.runtime.dispose();
    validated(null);
    await save;
    assert.deepEqual(e.calls.map(call => call.name), ['validate']);
    assert.equal(e.h.requests.length, 0);
    assert.equal(e.closes, 0);

    let bound;
    const binding = new Promise(resolve => { bound = resolve; });
    const later = await editor({bind: () => binding});
    t.after(() => later.runtime.dispose());
    later.credential().setCredential('fixture-value');
    const saving = later.click('common.save');
    await new Promise(resolve => setImmediate(resolve));
    later.h.context.openScheduleOverride(later.h.task);
    bound({ok: true});
    await saving;
    assert.equal(later.closes, 0);
    assert.equal(later.modal.body.isConnected, true);
});

test('撤销凭证经来源契约并保留取消、失败和成功语义', async t => {
    let ok = false;
    const e = await editor({revoke: () => ({ok, error: ok ? null : 'fixture rejection'})});
    t.after(() => e.runtime.dispose());
    await e.click('schedule.override.clear-credential');
    assert.equal(e.calls.length, 0);
    e.h.confirm(true);
    await e.click('schedule.override.clear-credential');
    assert.equal(e.calls[0].name, 'revoke');
    assert.equal(e.calls[0].args[0], e.h.task.id);
    assert.equal(e.status(), 'fixture rejection');
    assert.equal(e.closes, 0);
    ok = true;
    await e.click('schedule.override.clear-credential');
    assert.equal(e.closes, 1);
    assert.equal(e.h.requests.length, 0);
});

test('代理和凭证能力独立控制显隐及写入，隐藏草稿不会越权提交', async t => {
    for (const supportsCredential of [true, false]) {
        const e = await editor({capabilities: {supportsCredential, supportsProxy: !supportsCredential}});
        t.after(() => e.runtime.dispose());
        assert.equal(e.credential().parentNode.hidden, !supportsCredential);
        assert.equal(e.proxy().parentNode.hidden, supportsCredential);
        e.credential().setCredential('fixture-value');
        e.proxy().value = '127.0.0.1:8080';
        await e.click('common.save');
        assert.deepEqual(e.calls.map(call => call.name), supportsCredential ? ['validate', 'bind'] : []);
        assert.equal(e.h.requests.length, supportsCredential ? 0 : 1);
        if (!supportsCredential) assert.match(e.h.requests[0].url, /\/proxy$/);
    }
});

test('新建与编辑入口都经实际来源校验后绑定，格式错误不保存任务', async t => {
    for (const editing of [false, true]) {
        for (const valid of [false, true]) {
            const e = await editor({validate: () => valid ? null : 'fixture validation'});
            t.after(() => e.runtime.dispose());
            e.h.confirm(true);
            e.h.run("state.mode='search'; searchState.word='fixture'; searchState.maxPages=1;");
            e.h.context.openScheduleEditor(editing ? e.h.task : null);
            if (editing) e.h.options();
            e.h.field('abScheduleField0').value = 'Fixture schedule';
            e.h.field('abScheduleField5').setCredential('fixture-value');
            if (editing) await e.h.save();
            else await e.h.drawer.footer.querySelector('.ab-btn--primary').listeners.get('click')[0]();
            assert.deepEqual(e.calls.map(call => call.name), valid ? ['validate', 'bind'] : ['validate'],
                JSON.stringify({editing, valid, status: e.h.drawer?.footer.querySelector('[role="status"]')?.textContent}));
            assert.equal(e.h.requests.length, valid ? 1 : 0);
            if (valid) {
                assert.equal(e.h.requests[0].method, editing ? 'PUT' : 'POST');
                assert.equal(e.calls[1].args[0], e.h.task.id);
                assert.equal(e.calls[1].args[1], 'fixture-value');
            }
        }
    }
});
