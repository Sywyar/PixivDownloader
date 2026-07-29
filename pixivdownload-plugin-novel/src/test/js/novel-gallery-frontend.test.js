'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const SOURCE = fs.readFileSync(path.resolve(
    __dirname,
    '../../main/resources/static/pixiv-novel-gallery/novel-gallery-frontend.js'
), 'utf8');

class FakeElement {
    constructor(tagName, ownerDocument) {
        this.nodeType = 1;
        this.tagName = String(tagName).toUpperCase();
        this.ownerDocument = ownerDocument;
        this.children = [];
        this.className = '';
        this.href = '';
        this._textContent = '';
    }

    appendChild(child) {
        this.children.push(child);
        return child;
    }

    set textContent(value) {
        this._textContent = String(value);
        this.children = [];
    }

    get textContent() {
        if (this.children.length > 0) {
            return this.children.map(child => child.textContent).join('');
        }
        return this._textContent;
    }
}

class FakeDocument {
    createElement(tagName) {
        return new FakeElement(tagName, this);
    }
}

function loadModule(fetchImpl) {
    const document = new FakeDocument();
    let initializer;
    let generation = 17;
    const window = {
        document,
        fetch: fetchImpl,
        PixivGalleryFrontend: {
            registerModule(_moduleUrl, value) {
                initializer = value;
            },
            generation() {
                return generation;
            }
        }
    };
    vm.runInNewContext(SOURCE, {window, document});

    let renderer;
    initializer({
        registerMediaRenderer(value) {
            renderer = value;
        },
        registerDetailAction() {}
    });
    return {
        document,
        renderer,
        setGeneration(value) {
            generation = value;
        }
    };
}

function context(harness, media) {
    return {
        host: harness.document.createElement('div'),
        work: {key: {sourceWorkId: '123'}},
        media,
        t(key) {
            return {
                'novel-gallery:frontend.text.loading': '正在加载正文',
                'novel-gallery:frontend.text.empty': '暂无正文内容',
                'novel-gallery:frontend.text.error': '正文加载失败'
            }[key] || key;
        }
    };
}

async function settle() {
    await new Promise(resolve => setImmediate(resolve));
    await new Promise(resolve => setImmediate(resolve));
}

test('正文由小说插件端点按需读取并只经 textContent 渲染', async () => {
    const requests = [];
    const harness = loadModule(async (url, options) => {
        requests.push({url, options});
        return {
            ok: true,
            async json() {
                return {content: '<script>原始正文</script>'};
            }
        };
    });

    const article = harness.renderer.render(context(harness, {
        kind: 'TEXT',
        url: '/api/gallery/novel/123/content',
        content: '不得读取的内联正文'
    }));

    assert.equal(article.textContent, '正在加载正文');
    await settle();
    assert.equal(article.textContent, '<script>原始正文</script>');
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, '/api/gallery/novel/123/content');
    assert.equal(requests[0].options.credentials, 'same-origin');
    assert.equal(requests[0].options.headers.Accept, 'application/json');
    assert.ok(!article.textContent.includes('不得读取的内联正文'));
});

test('非法资源定位或端点失败时显示本地化错误且不泄露异常', async () => {
    let requests = 0;
    const harness = loadModule(async () => {
        requests++;
        return {ok: false, async json() { return {}; }};
    });

    const rejected = harness.renderer.render(context(harness, {
        kind: 'TEXT',
        url: 'https://attacker.invalid/private'
    }));
    assert.equal(rejected.textContent, '正文加载失败');
    assert.equal(requests, 0);

    const failed = harness.renderer.render(context(harness, {
        kind: 'TEXT',
        url: '/api/gallery/novel/123/content'
    }));
    await settle();
    assert.equal(failed.textContent, '正文加载失败');
    assert.equal(requests, 1);
});

test('画廊代际变化后旧正文响应不得回写', async () => {
    let resolveResponse;
    const harness = loadModule(() => new Promise(resolve => {
        resolveResponse = resolve;
    }));
    const article = harness.renderer.render(context(harness, {
        kind: 'TEXT',
        url: '/api/gallery/novel/123/content'
    }));

    harness.setGeneration(18);
    resolveResponse({
        ok: true,
        async json() {
            return {content: '旧代正文'};
        }
    });
    await settle();

    assert.equal(article.textContent, '正在加载正文');
});
