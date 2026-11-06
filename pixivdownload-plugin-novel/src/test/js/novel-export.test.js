const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/pixiv-novel/novel-export.js'), 'utf8');

function load({form, status = 200, fetcher} = {}) {
    const nodes = [], calls = [], messages = [], tracked = [], events = {}, downloads = [], revoked = [];
    function element(tag) {
        const node = {tag, children: [], value: '', checked: false, handlers: {},
            appendChild(child) { this.children.push(child); if (tag === 'select' && this.children.length === 1) this.value = child.value; },
            append(...children) { children.forEach(child => this.appendChild(child)); },
            replaceChildren() { this.children = []; this.value = ''; },
            setAttribute() {}, addEventListener(type, callback) { this.handlers[type] = callback; },
            click() { downloads.push({filename: this.download, href: this.href}); }, remove() {}
        };
        nodes.push(node);
        return node;
    }
    const context = {AbortController, Intl, URLSearchParams,
        URL: {createObjectURL: () => 'blob:document', revokeObjectURL: url => revoked.push(url)},
        setTimeout: callback => callback(),
        document: {createElement: element, documentElement: {lang: 'en'}, body: element('body')},
        addEventListener(type, callback) { events[type] = callback; },
        PixivFeedback: {form: async options => form ? form(options, nodes) : options.readValue()},
        PixivSideModules: {trackArchive: (...args) => tracked.push(args), openTasks() {}},
        fetch: async (url, options) => {
            calls.push({url, options, payload: options.body ? JSON.parse(options.body) : null});
            if (fetcher) return fetcher(url, options);
            return {ok: url.endsWith('/options') || status === 200, status,
                blob: async () => 'epub-bytes', headers: {get: () => "attachment; filename*=UTF-8''%E8%AF%91%E6%96%87.epub"},
                json: async () => url.endsWith('/options') ? {variants: ['zh-CN@ai', 'zh-CN@youdao', 'en@ai', 'ja']}
                    : {archiveToken: 'archive'}};
        }
    };
    context.window = context;
    vm.runInNewContext(source, context);
    return {context, nodes, calls, messages, tracked, events, downloads, revoked,
        open: (extra = {}) => context.PixivNovelExport.open({selection: {mode: 'ids', ids: [42]},
            variants: ['zh-CN@ai', 'zh-CN@youdao', 'en@ai', 'ja'],
            initialVariant: 'zh-CN@youdao', i18n: {t: key => key}, toast: (...args) => messages.push(args), ...extra})};
}

test('批量选择一篇或多篇均保留归档选项和任务流程', async () => {
    for (const ids of [[42], [42, 43]]) {
        const h = load();
        await h.open({selection: {mode: 'ids', ids}});
        assert.deepEqual(h.calls.map(call => call.url), ['/api/gallery/novels/export/options', '/api/gallery/novels/export']);
        assert.deepEqual(h.calls[1].payload, {mode: 'ids', ids, variant: 'zh-CN@youdao', groupBy: 'author', format: 'zip', deleteAfter: false});
        assert.equal(h.tracked[0][0], 'archive');
    }
});

test('单篇只选择语言和引擎，直接下载附件并释放 URL，不创建归档', async () => {
    const h = load({form: async options => {
        const names = [];
        function visit(node) {
            if (node.name) names.push(node.name);
            node.children.forEach(visit);
        }
        visit(options.content);
        assert.deepEqual(names, ['language', 'engine']);
        assert.equal(options.title, 'novel-gallery:export.single-title');
        return options.readValue();
    }});
    await h.open({novelId: 42});
    assert.deepEqual(h.calls.map(call => call.url), ['/api/novel/42/export?variant=zh-CN%40youdao']);
    assert.equal(h.calls[0].payload, null);
    assert.deepEqual(h.downloads, [{filename: '译文.epub', href: 'blob:document'}]);
    assert.deepEqual(h.revoked, ['blob:document']);
    assert.equal(h.tracked.length, 0);
});

test('单篇原文不带引擎；取消不发请求；失败不产生下载或归档', async () => {
    const original = load();
    await original.open({novelId: 42, initialVariant: ''});
    assert.equal(original.calls[0].url, '/api/novel/42/export?');
    const canceled = load({form: async () => null});
    await canceled.open({novelId: 42});
    assert.equal(canceled.calls.length, 0);
    for (const status of [404, 413, 500]) {
        const h = load({status});
        await h.open({novelId: 42});
        assert.equal(h.downloads.length, 0);
        assert.equal(h.tracked.length, 0);
        assert.equal(h.messages.at(-1)[1], 'error');
    }
});

test('单篇下载在途防重复，离页后丢弃迟到的附件', async () => {
    let finish;
    const h = load({fetcher: async () => ({ok: true, blob: () => new Promise(resolve => {finish = resolve;})})});
    const pending = h.open({novelId: 42});
    await new Promise(resolve => setImmediate(resolve));
    await h.open({novelId: 42});
    assert.equal(h.calls.length, 1);
    h.events.pagehide();
    finish('late-epub');
    await pending;
    assert.equal(h.calls[0].options.signal.aborted, true);
    assert.equal(h.downloads.length, 0);
    assert.equal(h.tracked.length, 0);
});

test('language and engine are independent; translated-only export preserves sources', async () => {
    const h = load({form: async (options, nodes) => {
        const field = name => nodes.find(node => node.name === name);
        assert.equal(field('language').value, 'zh-CN');
        assert.deepEqual(field('engine').children.map(node => node.value), ['zh-CN@ai', 'zh-CN@youdao']);
        assert.equal(field('deleteAfter').disabled, true);
        field('engine').value = 'zh-CN@ai';
        field('language').value = 'en'; field('language').handlers.change();
        assert.equal(field('engine').value, 'en@ai');
        field('language').value = ''; field('language').handlers.change();
        assert.equal(field('engine').disabled, true);
        field('deleteAfter').checked = true;
        field('language').value = 'ja'; field('language').handlers.change();
        assert.equal(field('engine').value, 'ja');
        assert.equal(field('deleteAfter').checked, false);
        return options.readValue();
    }});
    await h.open();
    assert.equal(h.calls[1].payload.variant, 'ja');
});

test('cancel does not submit; unavailable exports never reach archive tracking', async () => {
    const canceled = load({form: async () => null});
    await canceled.open();
    assert.equal(canceled.calls.length, 1);
    for (const status of [409, 413, 500]) {
        const h = load({status});
        await h.open();
        assert.equal(h.tracked.length, 0);
        assert.equal(h.messages.at(-1)[1], 'error');
    }
});

test('page exit aborts reads and ignores late responses; duplicate opens are coalesced', async () => {
    let finish;
    const h = load({fetcher: () => new Promise(resolve => { finish = resolve; })});
    const pending = h.open();
    await h.open();
    assert.equal(h.calls.length, 1);
    h.events.pagehide();
    assert.equal(h.calls[0].options.signal.aborted, true);
    finish({ok: true, json: async () => ({variants: []})});
    await pending;
    assert.equal(h.tracked.length, 0);
    assert.equal(h.messages.length, 0);
});
