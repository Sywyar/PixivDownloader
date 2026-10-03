'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { setImmediate: settle } = require('node:timers/promises');

// 替代 DOM 存储，保留真实内容模型、事件、异步请求及取消协议。
class Element {
    constructor(tag) { this.tag = tag; this.children = []; this.attributes = {}; this.listeners = new Map(); }
    setAttribute(name, value) { this.attributes[name] = value; }
    getAttribute(name) { return this.attributes[name]; }
    removeAttribute(name) { delete this.attributes[name]; if (name === 'src') this.src = ''; }
    append(...children) { this.children.push(...children); }
    appendChild(child) { this.append(child); }
    replaceChildren(...children) { this.children = children; }
    addEventListener(name, listener) { this.listeners.set(name, listener); }
    removeEventListener(name) { this.listeners.delete(name); }
    fire(name) { this.listeners.get(name)?.(); }
    querySelector(tag) { return this.children.find(child => child.tag === tag) || this.children.map(child => child.querySelector(tag)).find(Boolean); }
}

function fixture() {
    const document = { documentElement: new Element('html'), createElement: tag => new Element(tag) };
    const events = new Map(), requests = [];
    const context = { document, URL, AbortController, Intl,
        addEventListener: (name, callback) => events.set(name, callback), removeEventListener: name => events.delete(name) };
    context.window = context;
    vm.createContext(context, { codeGeneration: { strings: false, wasm: false } });
    for (const name of ['core', 'content']) vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/plugin-market/plugin-market-' + name + '.js'), 'utf8'), context);
    const market = context.PixivPluginMarket;
    market.state.i18n.client = { lang: 'en-US', t: key => key };
    market.api = { contentImageUrl: () => '/verified-image', fetchContent: (model, doc, signal) => new Promise(resolve => {
        requests.push({ model, doc, signal, resolve });
    }) };
    return { market, events, requests };
}

test('五语文档与标签共用回退，简繁体不混用，空链接不恢复旧主页', () => {
    const { market } = fixture();
    for (const locale of ['en-US', 'zh-CN', 'zh-Hant', 'ja-JP', 'ko-KR']) {
        market.state.i18n.client.lang = locale;
        assert.equal(market.localeKey({ [locale]: 'exact', en: 'default' }, 'en'), locale);
    }
    market.state.i18n.client.lang = 'zh-Hant';
    assert.equal(market.localeKey({ 'zh-CN': 'simplified', en: 'default' }, 'en'), 'en');
    assert.equal(market.localeKey({ 'zh-CN': 'simplified', 'zh-TW': 'traditional', en: 'default' }, 'en'), 'zh-TW');
    market.state.i18n.client.lang = 'en-US';
    assert.equal(market.localeKey({ 'en-GB': 'English', ja: 'Japanese' }, 'ja'), 'en-GB');
    market.state.i18n.client.lang = 'fr-FR';
    assert.equal(market.localeKey({ zh: 'legacy', en: 'English' }), 'zh');
    const entry = { pluginId: 'sample', market: { defaultLocale: 'en', homepageUrl: 'https://example.org' } };
    assert.equal(market.content.model('repo', entry).links.length, 1);
    entry.market.links = [];
    assert.equal(market.content.model('repo', entry).links.length, 0);
    entry.market.links = [{ kind: 'custom', url: 'javascript:alert(1)', label: { en: 'bad' } },
        { kind: 'custom', url: 'https://user:password@example.org', label: { en: 'bad' } }];
    assert.equal(market.content.model('repo', entry).links.length, 0);
});

test('说明按需读取，收起与换版取消旧响应，离页释放图片且恢复后重新加载', async () => {
    const { market, events, requests } = fixture();
    const host = new Element('div'), owner = market.content.mount(host);
    const model = { repositoryId: 'repo', pluginId: 'sample', version: '3.4.5', links: [],
        screenshots: [{ url: '/verified-image', alt: 'Preview' }],
        documents: [{ kind: 'readme', locale: 'en', asset: { sha256: 'a'.repeat(64) } }] };
    owner.update(model);
    const image = host.querySelector('img'), oldImageError = image.listeners.get('error');
    const section = host.querySelector('details');
    assert.equal(requests.length, 0);
    owner.update(model); assert.equal(host.querySelector('details'), section);
    section.open = true; section.fire('toggle');
    section.open = false; section.fire('toggle');
    assert.equal(requests[0].signal.aborted, true);
    requests[0].resolve({ sha256: model.documents[0].asset.sha256, html: '<h1>late</h1>' });
    await settle(); assert.equal(host.querySelector('iframe'), undefined);
    section.open = true; section.fire('toggle');
    requests[1].resolve({ sha256: model.documents[0].asset.sha256,
        html: '<h1 id="install">current</h1><a href="#install">Jump</a><a href="https://example.org/help">Help</a>' });
    await settle();
    assert.equal(host.querySelector('iframe').getAttribute('sandbox'), 'allow-popups allow-popups-to-escape-sandbox');
    assert.match(host.querySelector('iframe').srcdoc, /href="about:srcdoc#install"/);
    assert.match(host.querySelector('iframe').srcdoc, /href="https:\/\/example\.org\/help"/);
    events.get('pagehide')(); oldImageError();
    assert.equal(image.src, ''); assert.equal(image.listeners.size, 0);
    assert.equal(host.querySelector('iframe'), undefined);
    events.get('pageshow')();
    assert.equal(image.src, '/verified-image'); assert.equal(requests.length, 3);
    owner.update({ ...model, version: '3.4.6' });
    assert.equal(requests[2].signal.aborted, true);
    requests[2].resolve({ sha256: model.documents[0].asset.sha256, html: '<h1>old version</h1>' });
    await settle(); assert.equal(host.querySelector('iframe'), undefined);
    owner.dispose(); oldImageError();
    assert.equal(host.children.length, 0); assert.equal(events.size, 0);
});
