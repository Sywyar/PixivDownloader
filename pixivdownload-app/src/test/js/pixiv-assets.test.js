'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pixiv-assets.js'), 'utf8');

function fixture(version) {
    const sandbox = {URL, location: {origin: 'http://localhost:8080'},
        document: {baseURI: 'http://localhost:8080/app/page.html',
            currentScript: {getAttribute: () => version}}};
    sandbox.window = sandbox;
    vm.runInNewContext(source, sandbox, {codeGeneration: {strings: false, wasm: false}});
    return sandbox.PixivAssets;
}

test('动态资源保留参数与锚点，同次运行去重、重启更新、重复处理不累加参数', () => {
    const assets = fixture('run-one');
    const first = assets.url('../module.js?keep=1#part');
    assert.equal(first, 'http://localhost:8080/module.js?keep=1&_runtime=run-one#part');
    assert.equal(assets.url(first), first);
    assert.equal(assets.url('../module.js?keep=1#part'), first);
    assert.notEqual(fixture('run-two').url('../module.js?keep=1#part'), first);
});

test('外部与非 HTTP URL 保留，旧宿主无运行标识时不伪造身份', () => {
    const assets = fixture('run-one');
    for (const url of ['https://example.invalid/a.js', '//example.invalid/a.css', 'data:text/css,body{}', 'blob:http://localhost:8080/id']) {
        assert.equal(assets.url(url), url);
    }
    assert.equal(fixture(null).url('/module.js'), '/module.js');
});
