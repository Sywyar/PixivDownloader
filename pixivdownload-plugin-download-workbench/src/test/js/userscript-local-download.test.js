'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../../../Pixiv 单作品图片下载器(Local Download).user.js'), 'utf8');

function runtime({anchorFails = false, fallback = 'error', unregister = true} = {}) {
    const storage = new Map(), menus = new Map(), timers = new Map(), alerts = [], revoked = [];
    let sequence = 0, blob, removedLinks = 0;
    class DownloadURL extends URL {
        static createObjectURL(value) { blob = value; return 'blob:epub'; }
        static revokeObjectURL(url) { revoked.push(url); }
    }
    const context = {
        document: {
            readyState: 'loading', body: null, documentElement: {appendChild() {}},
            addEventListener() {},
            createElement() { return {style: {}, remove() { removedLinks++; }, click() {
                if (anchorFails) throw new Error('anchor unavailable');
            }}; }
        },
        location: {href: 'https://www.pixiv.net/novel/show.php?id=123'},
        navigator: {language: 'en-US'}, localStorage: {getItem() { return null; }, setItem() {}},
        URL: DownloadURL, Blob, TextEncoder,
        console: {log() {}, warn() {}, error() {}},
        addEventListener() {}, setInterval() {},
        setTimeout(callback, delay) { timers.set(++sequence, {callback, delay}); return sequence; },
        clearTimeout(id) { timers.delete(id); },
        alert(value) { alerts.push(value); }, confirm() { return true; },
        GM_getValue(key, otherwise) { return storage.has(key) ? storage.get(key) : otherwise; },
        GM_setValue(key, value) { storage.set(key, value); },
        GM_registerMenuCommand(label, action) { menus.set(++sequence, {label, action}); return sequence; },
        GM_xmlhttpRequest(options) {
            options.onload({responseText: JSON.stringify({body: {title: 'Sample', content: 'Text', language: 'en'}})});
        },
        GM_download(options) {
            if (fallback === 'throw') throw new Error('download unavailable');
            if (fallback === 'timeout') options.ontimeout();
            else if (fallback === 'success') options.onload();
            else options.onerror({error: 'denied'});
        }
    };
    if (unregister) context.GM_unregisterMenuCommand = id => menus.delete(id);
    context.window = context;
    vm.runInNewContext(source, context);
    return {
        storage, menus, timers, alerts, revoked,
        language: context.__PixivUserscriptI18n_v1__,
        blob: () => blob,
        removedLinks: () => removedLinks,
        async downloadNovel() {
            const label = this.language.t('local.menu.download-current-novel');
            await [...menus.values()].find(menu => menu.label === label).action();
        },
        expireUrls() { for (const {callback} of timers.values()) callback(); }
    };
}

for (const fallback of ['error', 'timeout', 'throw']) {
    test(`本地 EPUB 回退下载${fallback}时释放 URL 且不登记成功`, async () => {
        const r = runtime({anchorFails: true, fallback});
        await r.downloadNovel();
        assert.equal(r.removedLinks(), 1);
        r.expireUrls();
        assert.deepEqual(r.revoked, ['blob:epub']);
        assert.equal(r.storage.has('downloadedNovels'), false);
        assert.ok(r.alerts.length > 1);
    });
}

for (const anchorFails of [false, true]) {
    test(`本地 EPUB ${anchorFails ? 'GM 回退' : '原生链接'}触发成功后登记并延迟释放`, async () => {
        const r = runtime({anchorFails, fallback: 'success'});
        await r.downloadNovel();
        assert.equal(r.removedLinks(), 1);
        assert.equal(r.storage.get('downloadedNovels')['123'].title, 'Sample');
        assert.equal(r.blob().type, 'application/epub+zip');
        const bytes = Buffer.from(await r.blob().arrayBuffer());
        assert.equal(bytes.readUInt32LE(0), 0x04034b50);
        assert.ok(bytes.includes(Buffer.from('application/epub+zip')));
        assert.ok(bytes.includes(Buffer.from('<dc:language>en-US</dc:language>')));
        assert.deepEqual(r.revoked, []);
        r.expireUrls();
        assert.deepEqual(r.revoked, ['blob:epub']);
    });
}

test('旧脚本管理器切换语言时保留原菜单而不重复登记', () => {
    const r = runtime({unregister: false});
    const originals = [...r.menus.values()];
    r.language.setLang('ja-JP');
    r.language.setLang('ko-KR');
    assert.deepEqual([...r.menus.values()], originals);
});

test('韩语下载结果和历史使用真实换行', () => {
    const r = runtime();
    r.language.setLang('ko-KR');
    for (const key of ['local.alert.download-summary', 'local.alert.novel-summary',
        'local.history.header', 'local.history.entry', 'local.history.novel-header', 'local.history.novel-entry',
        'local.confirm.redownload', 'local.confirm.novel-redownload', 'local.alert.ugoira-unsupported']) {
        const text = r.language.t(key);
        assert.ok(text.includes('\n'), key);
        assert.ok(!text.includes('\\n'), key);
    }
});
