'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

const root = resolve(__dirname, '../../main/resources/static');

function pageCookies(page) {
    const values = new Map();
    const warnings = [];
    const input = {value: ''};
    const c = vm.createContext({
        console: {warn: value => warnings.push(value)},
        bt: key => key,
        document: {getElementById: id => id === 'cookie-input' ? input : null},
        storeGet: key => values.get(key),
        storeSet: (key, value) => values.set(key, value),
        storeRemove: key => values.delete(key),
        PixivBatch: {}, PixivBatchAlt: {cookie: {}}
    });
    c.window = c;
    const html = readFileSync(resolve(root, page), 'utf8');
    for (const [, src] of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) {
        if (/\/(cookie-storage|batch-cookie|alt-cookie)\.js$/.test(src)) {
            vm.runInContext(readFileSync(resolve(root, '.' + src), 'utf8'), c, {filename: src});
        }
    }
    return {c, values, warnings, input};
}

for (const page of ['pixiv-batch.html', 'pixiv-batch-alt.html']) {
    test(`${page} Cookie 读取保留格式归一化、原值与解析失败回退`, () => {
        const {c, warnings} = pageCookies(page);
        assert.equal(c.parseCookieToHeaderString('', 'json'), '');
        const raw = '  PHPSESSID=fixture; empty=  ';
        assert.equal(c.parseCookieToHeaderString(raw, 'unknown'), raw);
        assert.equal(c.parseCookieToHeaderString('{"PHPSESSID":"fixture","count":0,"empty":null}', ' JSON '),
            'PHPSESSID=fixture; count=0; empty=null');
        assert.equal(c.parseCookieToHeaderString('# comment\ninvalid\n.pixiv.net\tTRUE\t/\tTRUE\t0\tPHPSESSID\tfixture \r\n', 'NETSCAPE'),
            'PHPSESSID=fixture');
        assert.equal(c.parseCookieToHeaderString('# comment\ninvalid', 'netscape'), '');
        for (const malformed of ['{broken', 'null']) {
            assert.equal(c.parseCookieToHeaderString(malformed, 'json'), malformed);
        }
        assert.deepEqual(warnings, ['cookie.warn.parse-fallback', 'cookie.warn.parse-fallback']);
    });

    test(`${page} Cookie 来源键隔离且存储错误继续传播`, () => {
        const {c, values} = pageCookies(page);
        assert.equal(c.cookieStorageKey(' PIXIV '), 'pixiv_cookie');
        assert.equal(c.cookieStorageKey(' / '), 'pixiv_cookie');
        assert.equal(c.cookieStorageKey(' Other-Type_1! '), 'pixiv_other-type_1_cookie');
        c.setStoredCookie('pixiv', '{"PHPSESSID":"fixture"}');
        c.setStoredCookie('other', '{"session":"other"}');
        values.set('pixiv_cookie_fmt', ' JSON ');
        assert.equal(c.getCookie(), 'PHPSESSID=fixture');
        assert.equal(c.getCookieHeaderStringFor('other'), 'session=other');
        c.removeStoredCookie('other');
        assert.equal(c.getStoredCookie('other'), '');
        assert.equal(c.getCookie(), 'PHPSESSID=fixture');
        const failure = new Error('storage unavailable');
        c.storeSet = () => { throw failure; };
        assert.throws(() => c.setStoredCookie('pixiv', 'new'), error => error === failure);
        assert.equal(c.getCookie(), 'PHPSESSID=fixture');
        const facade = page === 'pixiv-batch.html' ? c.PixivBatch.cookie : c.PixivBatchAlt.cookie;
        assert.equal(facade.getCookieHeaderStringFor('pixiv'), 'PHPSESSID=fixture');
    });
}

test('经典页按输入草稿判断登录态，新版按已保存 Cookie 判断', () => {
    const classic = pageCookies('pixiv-batch.html');
    const alt = pageCookies('pixiv-batch-alt.html');
    for (const h of [classic, alt]) {
        h.values.set('pixiv_cookie', 'PHPSESSID=saved');
        h.input.value = 'other=draft';
    }
    assert.equal(classic.c.cookieHasPhpsessid(), false);
    assert.equal(alt.c.cookieHasPhpsessid(), true);
    classic.input.value = 'PHPSESSID=draft';
    assert.equal(classic.c.cookieHasPhpsessid(), true);
});
