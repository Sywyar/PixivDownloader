'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {Element, all} = require('./vue-render-fixture');
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return {promise, resolve}; }

function fixture(selectedProvider = 'server-owner') {
    let document;
    class Node extends Element {
        constructor(tag) { super(tag); this.listeners = {}; }
        replaceChildren() { this.children = []; }
        querySelector(tag) { return all(this, node => node.tag === tag)[0] || null; }
        addEventListener(type, fn) { this.listeners[type] = fn; }
        focus() { document.activeElement = this; }
        emit(type) { return this.listeners[type]?.({preventDefault() {}}); }
    }
    const root = new Node('section'); root.id = 'pmk-database';
    document = {createElement: tag => new Node(tag), getElementById: id => all(root, n => n.id === id)[0]};
    let response = {
        providers: [{owner: 'file-owner', databaseId: 'portable-fixture', connectionFields: []},
            {owner: 'server-owner', databaseId: 'remote-fixture', connectionFields: [
                {key: 'database.jdbc-url', type: 'STRING'}, {key: 'database.username', type: 'STRING'},
                {key: 'database.password', type: 'PASSWORD'}]},
            {owner: 'address-owner', databaseId: 'third-fixture', connectionFields: [{key: 'database.jdbc-url', type: 'STRING'}]}],
        settings: {provider: selectedProvider, jdbcUrl: '', username: '', passwordStored: true},
        state: {activeTarget: 'DATABASE_UNAVAILABLE', nextTarget: 'next-target', overriddenKeys: ['database.username']},
        migrationGuide: 'https://example.invalid/guide', selectedProvider: {owner: 'file-owner', enableAllowed: false}
    };
    const calls = [], events = {};
    const api = {
        fetchDatabaseSettings: async () => response,
        checkDatabase: async draft => { calls.push(['check', draft]); return {outcome: 'CONNECTED', target: 'draft-target'}; },
        previewDatabaseMigration: async draft => { calls.push(['preview', draft]); return {token: 'plan', ready: true,
            stores: [{owner: 'main', source: 'source-main', target: 'target-main', phase: 'REPLACE_TARGET'},
                {owner: 'private:fixture', source: 'source-private', target: 'target-private', phase: 'READY'}]}; },
        startDatabaseMigration: async token => { calls.push(['migrate', token]); return {accepted: true}; },
        fetchDatabaseMigration: async () => ({status: '', failure: '', stores: []}),
        enableDatabaseProvider: async owner => { calls.push(['enable', owner]); return {id: owner, enabled: true}; }
    };
    let locale = 'en';
    const market = {api, t: (key, fallback, values) => locale + ':' + key + (values ? JSON.stringify(values) : '')};
    const window = {PixivPluginMarket: market, location: {href: 'http://localhost/plugin-market.html'},
        PixivFeedback: {confirm: async () => true}, addEventListener(type, listener) { events[type] = listener; }};
    const sandbox = vm.createContext({window, document, URL, AbortController}, {codeGeneration: {strings: false, wasm: false}});
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/plugin-market/plugin-market-database.js'), 'utf8'), sandbox);
    market.database.mount();
    const get = id => document.getElementById('pmk-database-' + id);
    const nodes = predicate => all(root, predicate);
    const button = key => nodes(n => n.tag === 'button' && n.textContent.includes(key))[0];
    const status = () => nodes(n => n.props.role === 'status')[0].textContent;
    const input = (id, value, type = 'input') => { get(id).value = value; get(id).emit(type); };
    const save = () => nodes(n => n.tag === 'form')[0].emit('submit');
    return {root, market, window, calls, events, get, nodes, button, status, input, save,
        response: () => response, setResponse(value) { response = value; }, locale(value) { locale = value; market.database.render(); }};
}

test('数据库字段随选择显隐，密码不回填，预检分别表达保留替换和清除', async () => {
    const f = fixture('file-owner'); await tick();
    assert.equal(f.get('password'), undefined);
    assert.equal(f.get('jdbcUrl'), undefined);
    f.input('provider', 'server-owner', 'change');
    assert.equal(f.get('password').value, '');
    assert.equal(f.get('password').type, 'password');
    assert.equal(f.get('jdbcUrl').type, 'text');
    f.input('jdbcUrl', 'jdbc:postgresql://fixture/db'); f.input('username', 'fixture');
    f.save(); await tick();
    assert.equal(f.calls[0][1].passwordAction, 'KEEP'); assert.ok(!('password' in f.calls[0][1]));
    f.input('password', 'new password'); f.save(); await tick();
    assert.equal(f.calls[1][1].passwordAction, 'REPLACE'); assert.equal(f.calls[1][1].password, 'new password');
    assert.equal(f.get('password').value, 'new password'); assert.match(f.status(), /database.migration.ready/);
    const clear = f.nodes(n => n.type === 'checkbox')[0]; clear.checked = true; clear.emit('change');
    assert.equal(f.get('password').disabled, true);
    f.save(); await tick();
    assert.equal(f.calls[2][1].passwordAction, 'CLEAR'); assert.ok(!('password' in f.calls[2][1]));
    assert.equal(f.response().state.activeTarget, 'DATABASE_UNAVAILABLE');
});

test('提供者只声明地址时不显示账号密码，切换及重载空声明不留下字段', async () => {
    const f = fixture(); await tick();
    f.input('jdbcUrl', 'draft-address');
    f.input('provider', 'address-owner', 'change');
    assert.equal(f.get('jdbcUrl').value, 'draft-address');
    assert.equal(f.get('username'), undefined);
    assert.equal(f.get('password'), undefined);
    f.input('provider', 'file-owner', 'change');
    assert.equal(f.get('jdbcUrl'), undefined);
    f.locale('ja');
    assert.equal(f.get('jdbcUrl'), undefined);
    f.input('provider', 'server-owner', 'change');
    assert.equal(f.get('jdbcUrl').value, 'draft-address');
});

test('检查不保存，重复点击被阻止，编辑及离页使旧结果失效', async () => {
    const f = fixture(); await tick(); const waiting = deferred(); let signal;
    f.market.api.checkDatabase = async (draft, value) => { signal = value; f.calls.push(['check']); return waiting.promise; };
    f.button('database.check').emit('click'); f.button('database.check').emit('click');
    assert.equal(f.calls.length, 1); assert.equal(f.button('database.migrate').disabled, true);
    f.input('username', 'new draft');
    waiting.resolve({outcome: 'CONNECTED', target: 'old target'}); await tick();
    assert.match(f.status(), /database.stale/); assert.equal(signal.aborted, false);
    const late = deferred(); f.market.api.checkDatabase = async (draft, value) => { signal = value; return late.promise; };
    f.button('database.check').emit('click'); f.events.pagehide();
    assert.equal(signal.aborted, true);
    late.resolve({outcome: 'CONNECTED', target: 'late target'}); await tick();
    assert.doesNotMatch(f.status(), /late target/);
    f.events.pageshow({persisted: true}); await tick(); assert.equal(f.calls.length, 1);
    assert.equal(f.get('username').value, 'new draft');
});

test('预检失败和期间编辑均保留草稿，语言刷新保留展开状态和焦点', async () => {
    const f = fixture(); await tick(); f.input('password', 'retained draft');
    f.market.api.previewDatabaseMigration = async () => { throw {body: {code: 'gui.config.database.save-invalid'}}; };
    f.save(); await tick(); assert.match(f.status(), /database.save-invalid/);
    assert.equal(f.get('password').value, 'retained draft');
    f.root.querySelector('details').open = true; f.get('password').focus(); f.locale('ja');
    assert.match(f.status(), /^ja:/); assert.equal(f.root.querySelector('details').open, true);
    assert.equal(f.get('password').value, 'retained draft');
    const waiting = deferred(); f.market.api.previewDatabaseMigration = () => waiting.promise;
    f.save(); f.input('password', 'edited during save');
    waiting.resolve({token: 'old', ready: true, stores: []}); await tick();
    assert.equal(f.get('password').value, 'edited during save'); assert.match(f.status(), /database.stale/);
    assert.equal(f.button('database.migration.confirm'), undefined);
    f.market.api.fetchDatabaseSettings = async () => { throw Error('offline'); };
    await f.market.database.refresh(); assert.match(f.status(), /database.failed/);
});

test('预检列出主库与私库，只有确认精确计划后才安排一次迁移', async () => {
    const f = fixture(); await tick();
    f.input('password', 'target-secret');
    f.save(); await tick();
    assert.deepEqual(f.calls.map(call => call[0]), ['preview']);
    assert.ok(f.nodes(n => n.tag === 'strong').some(n => n.textContent.includes('database.migration.main')));
    assert.ok(f.nodes(n => n.tag === 'strong').some(n => n.textContent.includes('database.migration.plugin') && n.textContent.includes('fixture')));
    assert.ok(f.nodes(n => n.tag === 'p').some(n => n.textContent.includes('database.migration.phase.replace-target')));
    f.button('database.migration.confirm').emit('click'); await tick();
    assert.deepEqual(f.calls.map(call => call[0]), ['preview', 'migrate']);
    assert.equal(f.calls[1][1], 'plan');
    assert.equal(f.get('password').value, '');
    assert.match(f.status(), /database.migration.status.pending/);
    assert.equal(f.button('database.migration.confirm'), undefined);
});

function visible(node) {
    for (let child = node, parent = node.parentNode; parent; child = parent, parent = parent.parentNode)
        if (parent.tag === 'details' && !parent.open && child.tag !== 'summary') return false;
    return true;
}

test('确认清单显示数据归属、目标和替换风险，技术细节默认折叠，取消不丢草稿', async () => {
    const f = fixture(); await tick();
    f.input('password', 'retained draft');
    f.save(); await tick();
    const visibleText = () => f.nodes(visible).map(n => n.textContent || '').join('\n');
    assert.match(visibleText(), /database.migration.replacement-notice/);
    assert.match(visibleText(), /source-main/);
    assert.match(visibleText(), /target-main/);
    assert.doesNotMatch(visibleText(), /source-private|target-private|private:fixture/);
    const technical = f.nodes(n => n.tag === 'details' && n.children.some(child =>
        child.textContent?.includes('source-private')))[0];
    assert.equal(technical.open, false);
    technical.open = true; technical.emit('toggle');
    assert.match(visibleText(), /source-private|target-private/);
    f.locale('ja');
    assert.match(visibleText(), /ja:database.migration.plugin/);
    assert.match(visibleText(), /source-private/);
    f.button('database.migration.cancel').emit('click');
    assert.equal(f.get('password').value, 'retained draft');
    assert.equal(f.button('database.migration.confirm'), undefined);
    assert.deepEqual(f.calls.map(call => call[0]), ['preview']);
});

test('连接错误消费服务端文案键，诊断码按需查看且切换语言重新解析', async () => {
    const f = fixture(); await tick();
    f.market.api.checkDatabase = async () => ({outcome: 'FAILED', target: 'remote: fixture',
        sqlState: '28P01', errorKey: 'database.error.authentication'});
    f.button('database.check').emit('click'); await tick();
    assert.match(f.status(), /database.error.authentication/);
    assert.doesNotMatch(f.status(), /28P01/);
    const code = f.nodes(n => n.textContent?.includes('28P01'))[0];
    assert.equal(visible(code), false);
    f.locale('ja');
    assert.match(f.status(), /^ja:database.error.authentication/);
    f.input('username', 'corrected');
    assert.equal(f.status(), '');
    assert.equal(f.nodes(n => n.textContent?.includes('28P01')).length, 0);
});

test('已完成记录默认收起，失败和部分提交始终可见，同一连接不重复展示下次启动', async () => {
    const f = fixture(); await tick();
    f.response().state = {activeTarget: 'remote: fixture', nextTarget: 'remote: fixture', overriddenKeys: [], restartRequired: true};
    f.market.api.fetchDatabaseMigration = async () => ({status: 'SUCCEEDED', failure: '', stores: [
        {owner: 'main', source: 'old-target', target: 'new-target', phase: 'COMMITTED'}]});
    await f.market.database.refresh();
    const store = f.nodes(n => n.tag === 'strong' && n.textContent.includes('database.migration.main'))[0];
    assert.equal(visible(store), false);
    assert.equal(f.nodes(n => n.textContent?.includes('database.next')).length, 0);
    assert.equal(f.nodes(n => n.textContent?.includes('database.saved')).length, 1);
    f.market.api.fetchDatabaseMigration = async () => ({status: 'FAILED', failure: 'DATA_MISMATCH',
        failureKey: 'database.error.data-mismatch', stores: [
            {owner: 'main', source: 'old-target', target: 'new-target', phase: 'COMMITTED'},
            {owner: 'private:fixture', source: 'private-source', target: 'private-target', phase: 'UNKNOWN'}]});
    await f.market.database.refresh();
    const texts = f.nodes(visible).map(n => n.textContent || '').join('\n');
    assert.match(texts, /database.migration.status.failed/);
    assert.match(texts, /database.error.data-mismatch/);
    assert.match(texts, /database.migration.phase.committed/);
    assert.match(texts, /database.migration.phase.unknown/);
    assert.doesNotMatch(texts, /DATA_MISMATCH/);
});

test('修复同一数据库连接的确认动作不称为迁移，取消恢复表单焦点', async () => {
    const f = fixture(); await tick();
    f.market.api.previewDatabaseMigration = async () => ({token: 'repair', ready: true, stores: [
        {owner: 'main', source: 'same', target: 'same', phase: 'CONNECTION_REPAIR'},
        {owner: 'private:fixture', source: 'same-private', target: 'same-private', phase: 'SAME_TARGET'}]});
    f.save(); await tick();
    assert.ok(f.button('database.migration.confirm-connection'));
    assert.equal(f.root.querySelector('details').open, false);
    f.button('database.migration.cancel').emit('click');
    assert.equal(f.root.querySelector('details').open, true);
});

test('刷新用最新耐久结果替换已安排或待确认提示，保留草稿且不重放迁移', async () => {
    for (const [confirmed, status] of [[false, 'SUCCEEDED'], [true, 'FAILED']]) {
        const f = fixture(); await tick();
        f.input('username', 'retained draft');
        f.save(); await tick();
        if (confirmed) { f.button('database.migration.confirm').emit('click'); await tick(); }
        assert.match(f.status(), confirmed ? /database.migration.status.pending/ : /database.migration.ready/);
        const callsBeforeRefresh = f.calls.length;
        f.market.api.fetchDatabaseMigration = async () => ({status,
            failure: confirmed ? 'DATA_MISMATCH' : '', failureKey: 'database.error.data-mismatch', stores: []});
        await f.market.database.refresh();
        const texts = f.nodes(visible).map(n => n.textContent || '').join('\n');
        assert.doesNotMatch(texts, /database.migration.ready|database.migration.status.pending/);
        assert.ok(texts.includes('database.migration.status.' + status.toLowerCase()));
        if (confirmed) assert.match(texts, /database.error.data-mismatch/);
        assert.equal(f.get('username').value, 'retained draft');
        assert.equal(f.button('database.migration.confirm'), undefined);
        assert.equal(f.calls.length, callsBeforeRefresh);
    }
});

test('只启用诊断指定的当前插件，取消及离页后的确认不能提交，不重放启用', async () => {
    const f = fixture(); await tick();
    f.response().selectedProvider = {owner: 'disabled-owner', enableAllowed: true};
    await f.market.database.refresh();
    f.window.PixivFeedback.confirm = async () => false;
    await f.button('database.enable').emit('click'); await tick(); assert.equal(f.calls.length, 0);
    const confirmation = deferred(); f.window.PixivFeedback.confirm = () => confirmation.promise;
    f.button('database.enable').emit('click'); f.events.pagehide(); confirmation.resolve(true); await tick();
    assert.equal(f.calls.length, 0);
    f.events.pageshow({persisted: true}); await tick();
    f.window.PixivFeedback.confirm = async () => true;
    f.button('database.enable').emit('click'); await tick();
    assert.deepEqual(f.calls, [['enable', 'disabled-owner']]); assert.match(f.status(), /database.enabled/);
    assert.equal(f.button('database.enable'), undefined);
    f.events.pageshow({persisted: true}); await tick(); assert.equal(f.calls.length, 1);
});
