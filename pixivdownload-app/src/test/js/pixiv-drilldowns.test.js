'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pixiv-drilldowns.js'), 'utf8');

function fixture(response) {
    const warnings = [];
    const sandbox = {Promise, console: {warn: (...args) => warnings.push(args)},
        fetch: async (url, options) => {
            assert.equal(url, '/api/drilldowns');
            assert.equal(options.credentials, 'same-origin');
            return response;
        }};
    sandbox.window = sandbox;
    vm.runInNewContext(source, sandbox, {codeGeneration: {strings: false, wasm: false}});
    return {api: sandbox.PixivDrilldowns, warnings};
}

test('下钻未加载、贡献缺席及请求失败时返回空入口', async () => {
    for (const response of [{ok: true, json: async () => []}, {ok: false, status: 503}]) {
        const {api, warnings} = fixture(response);
        for (const placement of ['missing', 'constructor', '__proto__', 'toString']) {
            assert.equal(api.href(placement), null);
        }
        await api.ready();
        for (const placement of ['missing', 'constructor', '__proto__', 'toString']) {
            assert.equal(api.href(placement), null);
        }
        assert.equal(warnings.length, response.ok ? 0 : 1);
    }
});

test('合法 placement 不与对象原型冲突，并保留服务端首个贡献', async () => {
    const placements = ['stats.top-authors', 'constructor', '__proto__', 'toString', 'hasOwnProperty'];
    const {api, warnings} = fixture({ok: true, json: async () => [
        {placements, hrefTemplate: '/first?value={value}'},
        {placements, hrefTemplate: '/later?value={value}'}
    ]});
    await api.ready();
    for (const placement of placements) {
        assert.equal(api.href(placement, {value: 'a&b'}), '/first?value=a%26b');
    }
    assert.equal(warnings.length, 0);
});

test('模板编码变量、接受显式空值，缺参时不改选较低优先级贡献', async () => {
    const {api} = fixture({ok: true, json: async () => [
        {placements: ['detail'], hrefTemplate: '/view/{id}?name={name}&again={name}'},
        {placements: ['detail'], hrefTemplate: '/fallback'},
        {placements: ['own'], hrefTemplate: '/view?value={constructor}'}
    ]});
    await api.ready();
    assert.equal(api.href('detail', {id: 0, name: '中文/#?&={x}'}),
        '/view/0?name=%E4%B8%AD%E6%96%87%2F%23%3F%26%3D%7Bx%7D&again=%E4%B8%AD%E6%96%87%2F%23%3F%26%3D%7Bx%7D');
    assert.equal(api.href('detail', {id: 0, name: null}), '/view/0?name=&again=');
    assert.equal(api.href('detail', {id: 0}), null);
    assert.equal(api.href('own'), null);
    assert.equal(api.href('own', {constructor: 'value'}), '/view?value=value');
});
