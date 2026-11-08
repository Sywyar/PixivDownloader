const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../../../..');
const shared = fs.readFileSync(path.join(root, 'scripts/shared/installed-server.js'), 'utf8');
const scripts = fs.readdirSync(root).filter(name => name.endsWith('.user.js'))
    .map(name => ({name, source: fs.readFileSync(path.join(root, name), 'utf8')}))
    .filter(script => /@connect\s+YOUR_SERVER_HOST/.test(script.source));

function resolve(info, modern = false) {
    return vm.runInNewContext(shared + '\ninstalledServer()', {URL, ...(modern ? {GM: {info}} : {GM_info: info})});
}

test('安装来源保留协议、主机、非默认端口与上下文路径，兼容各元数据入口', () => {
    for (const base of ['http://localhost:7000', 'http://127.0.0.1:8123',
        'http://[::1]:8124', 'https://downloads.example:8443/pixiv']) {
        for (const suffix of ['.user.js', '/install']) {
            const url = base + '/api/scripts/fixture-script' + suffix;
            for (const info of [{script: {downloadURL: url}}, {script: {updateURL: url}}, {scriptUpdateURL: url}]) {
                assert.equal(resolve(info), base);
                assert.equal(resolve(info, true), base);
            }
        }
    }
    assert.equal(resolve({scriptUpdateURL: 'https://EXAMPLE.com:443/api/scripts/fixture.user.js'}), 'https://example.com');
});

test('源码安装或无效元数据使用源码回退地址，不能把凭据或查询写入默认地址', () => {
    const fallback = resolve();
    assert.equal(new URL(fallback).hostname, 'localhost');
    for (const url of ['https://raw.githubusercontent.com/example/fixture.user.js',
        'file:///api/scripts/fixture.user.js', 'https://name:secret@example.com/api/scripts/fixture.user.js',
        'https://example.com/api/scripts/fixture.user.js?token=secret',
        'https://example.com/api/scripts/fixture.user.js#fragment', 'not-a-url']) {
        assert.equal(resolve({script: {downloadURL: url}}), fallback);
    }
});

test('所有后端独立脚本在初始化时采用安装地址，并保留已有连接选择', () => {
    assert.ok(scripts.length > 0);
    for (const {name, source} of scripts) {
        for (const saved of [undefined, 'https://saved.example:9443/']) {
            let selected;
            const stop = new Error('Configuration observed');
            const origin = 'http://localhost:7000';
            assert.throws(() => vm.runInNewContext(source, {
                URL, GM_info: {scriptUpdateURL: origin + '/api/scripts/fixture.user.js'},
                GM_getValue(key, fallback) {
                    assert.equal(key, 'pixiv_server_base', name);
                    assert.equal(fallback, origin, name);
                    return {replace(pattern, replacement) {
                        selected = (saved ?? fallback).replace(pattern, replacement);
                        throw stop;
                    }};
                },
                GM_setValue() { assert.fail('初始化不得保存或覆盖连接设置'); }
            }), error => error === stop, name);
            assert.equal(selected, saved ? 'https://saved.example:9443' : origin, name);
        }
    }
});
