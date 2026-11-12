const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2], 'utf8');
const base = new URL(process.argv[3]);
const id = process.argv[4];
const version = source.match(/^\/\/\s*@version\s+(\S+)/m)[1];
const include = source.match(/^\/\/\s*@include\s+\/(.+)\/$/m);
assert.ok(include, '安装响应必须包含检测页面');
const matches = new RegExp(include[1]);
for (const page of ['/pixiv-batch.html', '/pixiv-batch-alt.html']) {
    const address = new URL(base.href.replace(/\/$/, '') + page);
    assert.ok(matches.test(address.href));
    assert.ok(matches.test(address.href + '?lang=en#scripts'));
    const wrongPort = new URL(address);
    wrongPort.port = String(Number(address.port || 80) + 1);
    assert.equal(matches.test(wrongPort.href), false);
    assert.equal(matches.test('https://attacker.example/?url=' + address.href), false);
    assert.equal(matches.test(address.origin + '/other.html'), false);
    const responses = [];
    let receive;
    const win = {
        addEventListener(type, fn) { assert.equal(type, 'message'); receive = fn; },
        postMessage(data, origin) { assert.equal(origin, address.origin); responses.push(data); }
    };
    win.top = win;
    const pageWindow = {};
    vm.runInNewContext(source, {
        window: win, document: {defaultView: pageWindow}, location: address,
        GM_info: {script: {version}},
        GM_getValue() { assert.fail('检测页面不得读取业务存储'); },
        GM_xmlhttpRequest() { assert.fail('检测页面不得发出业务请求'); }
    });
    assert.equal(typeof receive, 'function');
    const probe = {type: 'pixivdownloader:userscript-probe', protocol: 1, requestId: 'fixture-123'};
    receive({source: pageWindow, origin: wrongPort.origin, data: probe});
    receive({source: {}, origin: address.origin, data: probe});
    assert.equal(responses.length, 0);
    receive({source: pageWindow, origin: address.origin, data: probe});
    assert.equal(responses.length, 1);
    assert.deepEqual(JSON.parse(JSON.stringify(responses[0])), {
        type: 'pixivdownloader:userscript-presence', protocol: 1, requestId: probe.requestId, id, version
    });
}
