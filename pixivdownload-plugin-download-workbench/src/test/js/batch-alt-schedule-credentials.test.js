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
            selectSavedCredential: options.select || (() => ({})),
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
    const button = key => [...modal.body.querySelectorAll('button'), ...modal.footer.querySelectorAll('button')].find(node =>
        node.textContent === key || node.querySelector('span')?.textContent === key);
    const click = key => button(key).listeners.get('click')[0]();
    h.context.openScheduleOverride(h.task, options.section || (options.capabilities?.supportsCredential === false ? 'proxy' : 'credential'));
    return {h, runtime, calls, button, click,
        get modal() { return modal; }, get closes() { return closes; },
        credential: () => modal.body.querySelector('input[type="password"]'),
        proxy: () => modal.body.querySelector('input[type="text"]'),
        status: () => modal.body.querySelector('[role="status"]').textContent};
}

async function scheduleEditor(options = {}) {
    const lists = [];
    let e;
    e = await editor({...options,
        select: options.select || ((...args) => e.h.handlers.get('search').selectSavedCredential(...args))});
    Object.assign(e.h.context, {AbortSignal, DOMException});
    e.h.load('../pixiv-batch/credential-library.js');
    const fetch = e.h.context.fetch;
    e.h.context.fetch = (url, init) => {
        if (url !== '/api/acquisition/credentials') return fetch(url, init);
        const pending = Promise.withResolvers();
        lists.push({...pending, signal: init.signal});
        return pending.promise;
    };
    e.h.confirm(true);
    e.h.run("state.mode='search'; searchState.word='fixture'; searchState.maxPages=1;");
    e.h.context.openScheduleEditor(options.editing ? e.h.task : null);
    if (options.editing) e.h.options();
    const body = e.h.drawer.body;
    Object.defineProperty(body, 'isConnected', {get: () => e.h.context.document.body.contains(body)});
    e.h.field('abScheduleField0').value = 'Fixture schedule';
    const credential = e.h.field('abScheduleField5');
    const saved = body.querySelectorAll('button').find(node => node.textContent === 'schedule.override.use-saved');
    return {...e, lists, credential, saved,
        select: () => saved.listeners.get('click')[0](),
        save: () => options.editing ? e.h.save()
            : e.h.drawer.footer.querySelector('.ab-btn--primary').listeners.get('click')[0](),
        respond: (index = 0, id = 'old-account') => lists[index].resolve({ok: true, json: async () => ({revision: 'fixture',
            entries: [{id, name: id, enabled: true}]})})};
}

test('计划编辑的迟到单 Cookie 列表不覆盖后续输入或多行粘贴，保存绑定新草稿', async t => {
    for (const editing of [false, true]) {
        for (const paste of [false, true]) {
            const e = await scheduleEditor({editing});
            t.after(() => e.runtime.dispose());
            const selecting = e.select();
            const draft = paste ? 'fixture-new\nfixture-second' : 'fixture-new';
            if (paste) {
                e.credential.dispatchEvent({type: 'paste', clipboardData: {getData: () => draft}, preventDefault() {}});
            } else {
                e.credential.value = draft;
                e.credential.dispatchEvent({type: 'input'});
            }
            e.respond();
            await selecting;
            assert.equal(e.credential.readCredential(), draft);
            assert.equal(e.lists[0].signal.aborted, true);
            await e.save();
            assert.deepEqual(e.calls.map(call => call.name), ['validate', 'bind']);
            assert.equal(e.calls[1].args[1], draft);
        }
    }
});

test('计划编辑保存开始后取消旧选择，迟到响应不改变提交且不提前解锁按钮', async t => {
    for (const editing of [false, true]) {
        const validation = Promise.withResolvers();
        const e = await scheduleEditor({editing, validate: () => validation.promise});
        t.after(() => e.runtime.dispose());
        e.credential.setCredential('fixture-draft');
        const selecting = e.select();
        const saving = e.save();
        e.respond();
        await selecting;
        assert.equal(e.credential.readCredential(), 'fixture-draft');
        assert.equal(e.saved.disabled, true);
        assert.equal(e.lists[0].signal.aborted, true);
        validation.resolve('fixture validation');
        await saving;
        assert.equal(e.saved.disabled, false);
        assert.equal(e.credential.readCredential(), 'fixture-draft');
        assert.equal(e.h.requests.length, 0);
    }
});

test('关闭计划设置或替换编辑上下文后，旧凭据选择不能回填', async t => {
    for (const editing of [false, true]) {
        const e = await scheduleEditor({editing});
        t.after(() => e.runtime.dispose());
        e.credential.setCredential('fixture-draft');
        const selecting = e.select();
        assert.equal(await e.h.drawer.beforeClose(), true);
        assert.equal(e.lists[0].signal.aborted, true);
        e.respond();
        await selecting;
        assert.equal(e.credential.readCredential(), 'fixture-draft');
    }
    const e = await scheduleEditor({editing: true});
    t.after(() => e.runtime.dispose());
    e.credential.setCredential('fixture-draft');
    const selecting = e.select();
    e.h.context.openScheduleEditor({...e.h.task, id: 43});
    e.respond();
    await selecting;
    assert.equal(e.credential.readCredential(), 'fixture-draft');
    assert.equal(e.calls.length, 0);
});

test('旧选择结束不解锁后续选择，正常单 Cookie 选择仍使用选定 ID 绑定', async t => {
    const e = await scheduleEditor();
    t.after(() => e.runtime.dispose());
    const first = e.select();
    e.credential.value = 'fixture-draft';
    e.credential.dispatchEvent({type: 'input'});
    const second = e.select();
    assert.equal(e.lists.length, 2);
    e.respond(0);
    await first;
    assert.equal(e.saved.disabled, true);
    assert.equal(e.credential.readCredential(), 'fixture-draft');
    e.respond(1);
    await second;
    assert.equal(e.credential.readCredential(), '');
    assert.equal(e.saved.disabled, false);
    await e.save();
    assert.deepEqual(e.calls.map(call => call.name), ['saved']);
    assert.deepEqual(Array.from(e.calls[0].args[1].savedCredentialSelection.ids), ['old-account']);
});

test('取消计划凭据选择保留输入，旧选择失败不覆盖保存失败状态', async t => {
    const cancelled = await scheduleEditor({select: async () => null});
    t.after(() => cancelled.runtime.dispose());
    cancelled.credential.setCredential('fixture-draft');
    await cancelled.select();
    assert.equal(cancelled.credential.readCredential(), 'fixture-draft');
    await cancelled.save();
    assert.deepEqual(cancelled.calls.map(call => call.name), ['validate', 'bind']);

    const e = await scheduleEditor({validate: () => 'fixture validation'});
    t.after(() => e.runtime.dispose());
    e.credential.setCredential('fixture-draft');
    const selecting = e.select();
    await e.save();
    e.lists[0].reject(new Error('fixture list failure'));
    await selecting;
    assert.equal(e.h.drawer.footer.querySelector('[role="status"]').textContent, 'fixture validation');
    assert.equal(e.credential.readCredential(), 'fixture-draft');
});

test('任务保存请求等待期间，迟到的新选择不能替换已提交的凭据选择', async t => {
    const e = await scheduleEditor();
    t.after(() => e.runtime.dispose());
    const first = e.select();
    e.respond();
    await first;
    const pending = Promise.withResolvers();
    const fetch = e.h.context.fetch;
    e.h.context.fetch = async (url, init) => {
        if (url === '/api/schedule/tasks') await pending.promise;
        return fetch(url, init);
    };
    const selecting = e.select();
    const saving = e.save();
    await new Promise(resolve => setImmediate(resolve));
    e.respond(1, 'different-account');
    await selecting;
    assert.equal(e.saved.disabled, true);
    pending.resolve();
    await saving;
    assert.deepEqual(e.calls.map(call => call.name), ['saved']);
    assert.deepEqual(Array.from(e.calls[0].args[1].savedCredentialSelection.ids), ['old-account']);
});

test('固定凭证弹窗只显示绑定信息，代理仍可修改', async t => {
    const capabilities = {credentialReadOnly: true, singleCredential: true,
        presentation: {credentialHint: 'Fixed account'}};
    const e = await editor({capabilities});
    t.after(() => e.runtime.dispose());
    assert.equal(e.credential(), null);
    assert.equal(e.button('common.save'), undefined);
    assert.equal(e.button('schedule.override.clear-credential'), undefined);
    assert.ok(e.modal.body.querySelectorAll('p').some(node => node.textContent === 'Fixed account'));
    await e.click('common:button.close');
    assert.equal(e.closes, 1);
    assert.equal(e.calls.length, 0);
    const proxy = await editor({capabilities, section: 'proxy'});
    t.after(() => proxy.runtime.dispose());
    assert.ok(proxy.button('common.save'));
    assert.equal(proxy.proxy().disabled, false);
});

test('保存使用点击时的凭证选择，取消选择保留输入且关闭后不应用迟到结果', async t => {
    let chosen = {ids: ['second', 'first'], label: 'Second、First'};
    const e = await editor({select: async () => chosen});
    t.after(() => e.runtime.dispose());
    await e.click('schedule.override.use-saved');
    chosen = {ids: ['different'], label: 'Different'};
    await e.click('common.save');
    assert.deepEqual(Array.from(e.calls[0].args[1].savedCredentialSelection.ids), ['second', 'first']);

    const cancelled = await editor({select: async () => null});
    t.after(() => cancelled.runtime.dispose());
    cancelled.credential().setCredential('draft');
    await cancelled.click('schedule.override.use-saved');
    assert.equal(cancelled.credential().readCredential(), 'draft');

    let finish;
    const delayed = await editor({select: () => new Promise(resolve => { finish = resolve; })});
    t.after(() => delayed.runtime.dispose());
    const selecting = delayed.click('schedule.override.use-saved');
    delayed.modal.beforeClose();
    delayed.h.context.closeModal();
    finish({ids: ['stale'], label: 'Stale'});
    await selecting;
    assert.equal(delayed.credential().value, '');
    assert.doesNotMatch(delayed.credential().placeholder, /Stale/);
    assert.equal(delayed.calls.length, 0);
});

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
        assert.equal(e.credential().parentNode.parentNode.hidden, !supportsCredential);
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
