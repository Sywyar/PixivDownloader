const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/pixiv-series/series-actions.js'), 'utf8');

function load({form, fetcher, current = 'zh-CN@youdao'} = {}) {
    const nodes = [], requests = [], downloads = [], revoked = [];
    function element(tag) {
        const node = {tag, children: [], value: '', handlers: {},
            appendChild(child) { this.children.push(child); if (tag === 'select' && this.children.length === 1) this.value = child.value; },
            replaceChildren() { this.children = []; this.value = ''; },
            addEventListener(type, handler) { this.handlers[type] = handler; },
            click() { downloads.push(this.download); }, remove() {}, focus() { this.focused = true; }
        };
        nodes.push(node);
        return node;
    }
    const button = element('button'), status = element('p');
    class DownloadURL extends URL {}
    DownloadURL.createObjectURL = () => 'blob:merged';
    DownloadURL.revokeObjectURL = url => revoked.push(url);
    const context = {
        AbortController, Intl, URL: DownloadURL, URLSearchParams,
        state: {seriesId: 42, detail: {title: 'Original'}}, activeContentLang: current,
        seriesTranslatedLangs: ['zh-CN@ai', 'zh-CN@youdao', 'en@ai', 'ja'],
        isNovelMode: () => true, t: key => key,
        document: {documentElement: {lang: 'en'}, createElement: element,
            body: element('body'), getElementById: id => id === 'downloadMergedBtn' ? button : status},
        PixivFeedback: {form: async options => form ? form(options, nodes) : options.readValue()},
        setTimeout: callback => callback(),
        fetch: async (url, options) => {
            requests.push({url, options});
            return fetcher ? fetcher(url, options) : {ok: true, status: 200,
                headers: {get: () => "attachment; filename*=UTF-8''translated.epub"}, blob: async () => new Uint8Array([1])};
        }
    };
    context.window = context;
    vm.runInNewContext(source, context);
    return {context, requests, downloads, revoked, button, status, nodes, open: () => context.openDownloadMergedDialog()};
}

test('合订本默认当前语言与引擎，下载携带精确身份并释放附件 URL', async () => {
    const h = load({form: async (options, nodes) => {
        assert.equal(nodes.find(node => node.name === 'language').value, 'zh-CN');
        assert.equal(nodes.find(node => node.name === 'engine').value, 'zh-CN@youdao');
        return options.readValue();
    }});
    await h.open();
    const request = new URL(h.requests[0].url, 'https://series.test');
    assert.equal(request.pathname, '/api/novel/series/42/merged');
    assert.equal(request.searchParams.get('variant'), 'zh-CN@youdao');
    assert.equal(request.searchParams.get('format'), 'epub');
    assert.deepEqual(h.downloads, ['translated.epub']);
    assert.deepEqual(h.revoked, ['blob:merged']);
    assert.equal(h.button.disabled, false);
    assert.equal(h.button.focused, true);
});

test('语言与引擎独立联动，旧译文保留未知来源，原文不携带译文参数', async () => {
    const h = load({form: async (options, nodes) => {
        const language = nodes.find(node => node.name === 'language');
        const engine = nodes.find(node => node.name === 'engine');
        const hint = nodes.find(node => node.className === 'series-merge-hint');
        assert.deepEqual(engine.children.map(node => node.value), ['zh-CN@ai', 'zh-CN@youdao']);
        engine.value = 'zh-CN@ai';
        language.value = 'en'; language.handlers.change();
        assert.equal(engine.value, 'en@ai');
        language.value = 'ja'; language.handlers.change();
        assert.equal(engine.children[0].textContent, 'merge.engine.unknown');
        assert.equal(hint.hidden, false);
        language.value = ''; language.handlers.change();
        assert.equal(engine.disabled, true);
        assert.equal(hint.hidden, true);
        return options.readValue();
    }});
    await h.open();
    assert.equal(new URL(h.requests[0].url, 'https://series.test').searchParams.has('variant'), false);
    assert.equal(h.context.activeContentLang, 'zh-CN@youdao');
});

test('取消不发下载请求；不存在的当前译文默认原文', async () => {
    const canceled = load({form: async () => null});
    await canceled.open();
    assert.equal(canceled.requests.length, 0);
    const absent = load({current: 'de@ai'});
    await absent.open();
    assert.equal(new URL(absent.requests[0].url, 'https://series.test').searchParams.has('variant'), false);
});

test('下载失败使用受控文案，恢复下载入口', async () => {
    const h = load({fetcher: async () => ({ok: false, status: 500, json: async () => ({error: 'private failure'})})});
    await h.open();
    assert.equal(h.status.textContent, 'merge.failed');
    assert.equal(h.button.disabled, false);
    assert.equal(h.downloads.length, 0);
});

test('重复点击只提交一次，取消在途请求后拒绝迟到下载', async () => {
    let finish;
    const h = load({fetcher: () => new Promise(resolve => { finish = resolve; })});
    const pending = h.open();
    await new Promise(setImmediate);
    await h.open();
    assert.equal(h.requests.length, 1);
    h.context.cancelMergedDownload();
    assert.equal(h.requests[0].options.signal.aborted, true);
    finish({ok: true, status: 200, blob: async () => new Uint8Array([1])});
    await pending;
    assert.equal(h.downloads.length, 0);
    assert.equal(h.button.disabled, false);
    assert.notEqual(h.status.textContent, 'merge.failed');
});
