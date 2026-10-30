'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/login/login.js'), 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));

async function page(redirect = '', valid = false) {
    let url = new URL('https://app.example.test/login.html?redirect=' + encodeURIComponent(redirect));
    const nodes = new Map();
    const listeners = new Map();
    const posts = [];
    let actions;
    let slotOptions;
    const properties = [];
    function node(id) {
        if (!nodes.has(id)) {
            const classes = new Set(id === 'inviteCard' ? ['hidden'] : []);
            nodes.set(id, {
                value: '', checked: false, disabled: false, textContent: '',
                classList: {add: value => classes.add(value), remove: value => classes.delete(value),
                    contains: value => classes.has(value)},
                addEventListener(type, handler) { this[type] = handler; }, focus() {}
            });
        }
        return nodes.get(id);
    }
    const location = {
        get href() { return url.href; }, set href(value) { url = new URL(value, url); },
        get search() { return url.search; }, get origin() { return url.origin; }
    };
    const document = {body: {}, getElementById: node,
        addEventListener: (type, handler) => listeners.set(type, handler)};
    const fetch = (endpoint, options) => {
        if (endpoint === '/api/auth/check') return Promise.resolve({json: async () => ({valid})});
        return new Promise((resolve, reject) => posts.push({endpoint, options, resolve, reject}));
    };
    const PixivUiSlots = {connect(options) { slotOptions = options; return {
        refresh: async () => [], update: async value => properties.push(JSON.parse(JSON.stringify(value)))
    }; }};
    vm.runInNewContext(source, {window: {location, PixivUiSlots}, document, URL, URLSearchParams, fetch,
        PixivActions: {bind: (_, handlers) => { actions = handlers.click; }}});
    await settle();
    node('username').value = 'test-admin';
    node('password').value = 'test-password';
    node('inviteCodeInput').value = 'test-invite';
    return {node, listeners, posts, actions, location, slotOptions, properties};
}

test('挑战槽位只接收挑战纯值，证明绑定当前token，修改凭证后清除', async () => {
    const s = await page();
    const pending = s.actions.doLogin();
    s.posts[0].resolve({ok: false, status: 409, json: async () => ({code: 'setup.login.verification.required',
        verificationToken: 'attempt', challenges: {'neutral-policy': 'challenge'}, error: 'Verification required'})});
    await pending;
    assert.deepEqual(s.properties.at(-1), {verificationToken: 'attempt', challenges: {'neutral-policy': 'challenge'}});
    s.slotOptions.onEvent('proof', {verificationToken: 'old', policyId: 'neutral-policy', proof: 'stale'});
    s.slotOptions.onEvent('proof', {verificationToken: 'attempt', policyId: 'unknown', proof: 'wrong'});
    s.slotOptions.onEvent('proof', {verificationToken: 'attempt', policyId: 'neutral-policy', proof: 'accepted'});
    s.listeners.get('keydown')({key: 'Enter', target: {closest: () => true}});
    assert.equal(s.posts.length, 1, 'proof button Enter does not submit login');
    const response = s.actions.doLogin();
    const body = JSON.parse(s.posts[1].options.body);
    assert.deepEqual(body.verificationProofs, {'neutral-policy': 'accepted'});
    assert.equal(body.verificationToken, 'attempt');
    s.posts[1].resolve({ok: false, status: 409, json: async () => ({code: 'setup.login.verification.required',
        verificationToken: 'next', challenges: {'neutral-policy': 'challenge'}})});
    await response;
    s.node('password').input();
    assert.deepEqual(s.properties.at(-1), {verificationToken: '', challenges: {}});
    const cleared = s.actions.doLogin();
    assert.deepEqual(JSON.parse(s.posts[2].options.body).verificationProofs, {});
    s.posts[2].resolve({ok: true, json: async () => ({success: true})});
    await cleared;
});

test('登录后的回跳按浏览器URL语义限制在当前来源', async () => {
    const blocked = [String.raw`/\outside.example.test/`, '/\t/outside.example.test/',
        '//outside.example.test/', 'https://outside.example.test/', 'javascript:alert(1)', '/\\[invalid'];
    for (const valid of [false, true]) {
        for (const redirect of [...blocked, '/gallery?title=%E4%BD%9C%E5%93%81#page', '/a/..//nested']) {
            const s = await page(redirect, valid);
            if (!valid) {
                const login = s.actions.doLogin();
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
            if (first === 'doInviteRedeem') s.node('inviteCard').classList.remove('hidden');
            const request = s.actions[first]();
            s.actions[first]();
            s.listeners.get('keydown')({key: 'Enter'});
            const other = first === 'doLogin' ? 'doInviteRedeem' : 'doLogin';
            s.actions[other]();
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
            assert.equal(s.posts.length, 2);
            s.posts[1].resolve({ok: true, json: async () => ({success: true, redirect: '/guest-landing'})});
            await retry;
            assert.equal(new URL(s.location.href).pathname,
                other === 'doInviteRedeem' ? '/guest-landing' : '/redirect');
        }
    });
}
