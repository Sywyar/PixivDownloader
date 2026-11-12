'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const {pathToFileURL} = require('node:url');
const codecs = pathToFileURL(path.resolve(__dirname, '../../../../pixivdownload-plugin-api/src/main/resources/META-INF/pixivdownload/web/value-codecs.mjs')).href;
const runtime = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pixiv-pages.mjs'), 'utf8').replace("'/sdk/values/value-codecs.mjs'", JSON.stringify(codecs));
const runtimeModule = import('data:text/javascript;base64,' + Buffer.from(runtime).toString('base64'));

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/login/login.js'), 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));

async function page(redirect = '', valid = false) {
    let url = new URL('https://app.example.test/login.html?redirect=' + encodeURIComponent(redirect));
    const nodes = new Map();
    const listeners = new Map();
    const posts = [];
    let actions, context;
    function node(id) {
        if (!nodes.has(id)) {
            const classes = new Set(id === 'inviteCard' ? ['hidden'] : []);
            nodes.set(id, {
                value: '', checked: false, disabled: false, hidden: id === 'inviteCard', textContent: '',
                classList: {add: value => classes.add(value), remove: value => classes.delete(value),
                    contains: value => classes.has(value)},
                addEventListener(type, handler) { this[type] = handler; }, focus() {}
            });
        }
        return nodes.get(id);
    }
    const location = {
        get href() { return url.href; }, set href(value) { url = new URL(value, url); },
        get search() { return url.search; }, get origin() { return url.origin; }, get hash() { return url.hash; }, get pathname() { return url.pathname; }
    };
    const document = {body: {}, getElementById: node, querySelector: () => null,
        addEventListener: (type, handler) => listeners.set(type, handler)};
    const fetch = (endpoint, options) => {
        if (endpoint === '/api/auth/check') return Promise.resolve({json: async () => ({valid})});
        return new Promise((resolve, reject) => posts.push({endpoint, options, resolve, reject}));
    };
    context = vm.createContext({window: {location}, document, URL, URLSearchParams, fetch,
        PixivActions: {bind: (_, handlers) => { actions = handlers.click; }}});
    vm.runInContext(source, context);
    await vm.runInContext('initLoginPage()', context);
    await settle();
    node('username').value = 'test-admin';
    node('password').value = 'test-password';
    node('inviteCodeInput').value = 'test-invite';
    return {node, listeners, posts, actions, location};
}

test('业务提交保持原 DTO，统一拦截响应沿用普通失败反馈且允许重新提交', async () => {
    const s = await page();
    const pending = s.actions.doLogin();
    await settle();
    assert.equal(s.posts[0].endpoint, '/api/auth/login');
    assert.deepEqual(JSON.parse(s.posts[0].options.body), {username: 'test-admin', password: 'test-password', rememberMe: false});
    s.posts[0].resolve({ok: false, status: 409, json: async () => ({code: 'HOOK_VERIFICATION_REQUIRED', error: 'Verification required'})});
    await pending;
    assert.equal(s.node('error-msg').textContent, 'Verification required');
    s.listeners.get('keydown')({key: 'Enter', target: {closest: () => true}});
    assert.equal(s.posts.length, 1, 'component Enter does not submit login');
    const invite = s.actions.doInviteRedeem();
    assert.equal(s.posts[1].endpoint, '/api/auth/invite-redeem');
    assert.deepEqual(JSON.parse(s.posts[1].options.body), {code: 'test-invite'});
    s.posts[1].resolve({ok: true, json: async () => ({success: true, redirect: '/guest-landing'})});
    await invite;
});

for (const action of ['doLogin', 'doInviteRedeem']) {
    test(action + ' 不显示服务器诊断、旧式错误正文或网络异常详情', async () => {
        for (const failure of ['server', 'legacy', 'network', 'invalid-json']) {
            const s = await page();
            const pending = s.actions[action]();
            await settle();
            const detail = 'Handler dispatch failed: java.lang.NoSuchMethodError internal-probe';
            if (failure === 'network') s.posts[0].reject(new Error(detail));
            else s.posts[0].resolve({ok: false, status: failure === 'server' ? 500 : 400,
                json: async () => {
                    if (failure === 'invalid-json') throw new SyntaxError(detail);
                    return failure === 'server' ? {code: 'error.unexpected', error: detail} : {error: detail};
                }});
            await pending;
            const message = s.node(action === 'doLogin' ? 'error-msg' : 'invite-error').textContent;
            assert.ok(message.length > 0);
            assert.doesNotMatch(message, /internal-probe|NoSuchMethodError|Handler dispatch/);
            assert.equal(s.node('login-btn').disabled, false);
            assert.equal(s.node('invite-btn').disabled, false);
        }
    });
}

test('登录后的回跳按浏览器URL语义限制在当前来源', async () => {
    const blocked = [String.raw`/\outside.example.test/`, '/\t/outside.example.test/',
        '//outside.example.test/', 'https://outside.example.test/', 'javascript:alert(1)', '/\\[invalid'];
    for (const valid of [false, true]) {
        for (const redirect of [...blocked, '/gallery?title=%E4%BD%9C%E5%93%81#page', '/a/..//nested']) {
            const s = await page(redirect, valid);
            if (!valid) {
                const login = s.actions.doLogin();
                await settle();
                s.posts[0].resolve({ok: true, json: async () => ({success: true})});
                await login;
            }
            const target = new URL(s.location.href);
            assert.equal(target.origin, 'https://app.example.test', redirect);
            if (blocked.includes(redirect)) assert.equal(target.pathname, '/redirect');
            else assert.equal(target.href, new URL(redirect, 'https://app.example.test').href);
        }
    }
});

for (const first of ['doLogin', 'doInviteRedeem']) {
    test(first + ' 在请求完成前拒绝Enter重复提交和切换身份提交，失败后允许重试', async () => {
        for (const failure of ['http', 'network']) {
            const s = await page();
            if (first === 'doInviteRedeem') s.node('inviteCard').hidden = false;
            const request = s.actions[first]();
            s.actions[first]();
            s.listeners.get('keydown')({key: 'Enter'});
            const other = first === 'doLogin' ? 'doInviteRedeem' : 'doLogin';
            s.actions[other]();
            await settle();
            assert.equal(s.posts.length, 1);
            assert.equal(s.node('login-btn').disabled, true);
            assert.equal(s.node('invite-btn').disabled, true);
            if (failure === 'http') s.posts[0].resolve({ok: false, json: async () => ({error: 'denied'})});
            else s.posts[0].reject(new Error('offline'));
            await request;
            assert.equal(s.node('login-btn').disabled, false);
            assert.equal(s.node('invite-btn').disabled, false);
            assert.equal(s.node('password').value, 'test-password');
            assert.equal(s.node('inviteCodeInput').value, 'test-invite');
            const retry = s.actions[other]();
            await settle();
            assert.equal(s.posts.length, 2);
            s.posts[1].resolve({ok: true, json: async () => ({success: true, redirect: '/guest-landing'})});
            await retry;
            assert.equal(new URL(s.location.href).pathname,
                other === 'doInviteRedeem' ? '/guest-landing' : '/redirect');
        }
    });
}

test('准备合并并发，取消拒绝迟到响应，多策略齐备后才能提交，失效撤销证明', async () => {
    const {createOperationTarget} = await runtimeModule;
    const requests = [];
    const target = createOperationTarget('independent:submit', undefined,
        signal => new Promise(resolve => requests.push({signal, resolve})));
    target.configure('revision');
    const binding = policy => ({instanceId: policy, operation: target.operation, policy,
        protocol: {stateSchema: '{"type":"string"}', responseSchema: '{"type":"string"}'}});
    const first = target.connect(binding('first')), second = target.connect(binding('second'));
    const old = target.prepare();
    assert.equal(target.prepare(), old);
    await settle();
    target.reset();
    assert.equal(requests[0].signal.aborted, true);
    requests[0].resolve({token: 'old', revision: 'revision', challenges: {first: 'question'}});
    await assert.rejects(old, {name: 'AbortError'});
    assert.throws(() => target.request(), /OPERATION_VERIFICATION_REQUIRED/);
    const pending = first.prepare();
    await settle();
    requests[1].resolve({token: 'current', revision: 'revision', challenges: {first: 'one', second: 'two'}});
    await pending;
    assert.equal(first.respond('current', 'answer'), true);
    await second.prepare();
    assert.equal(requests.length, 2, 'second component reuses the same form preparation');
    assert.equal(target.ready(), false);
    assert.equal(second.respond('current', 'answer'), true);
    assert.equal(target.ready(), true);
    assert.equal(first.invalidate('old'), false);
    assert.equal(first.invalidate('current'), true);
    assert.equal(target.ready(), false);
    target.destroy();
    await assert.rejects(first.prepare(), /PAGE_INTERACTION_CLOSED/);
});
