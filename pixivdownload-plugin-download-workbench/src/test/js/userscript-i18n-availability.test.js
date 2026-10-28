const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../../..');
const sources = [path.join(root, 'scripts/userscript-snippets/pixiv-userscript-i18n.js'),
    ...fs.readdirSync(root).filter(name => name.endsWith('.user.js')).map(name => path.join(root, name))]
    .map(file => ({file, source: fs.readFileSync(file, 'utf8')}))
    .filter(item => item.source.includes('function enrichFromBackend(serverBase)'));

function runtime(source) {
    const requests = [];
    const start = source.indexOf('const PixivUserscriptI18n = (() =>');
    const end = source.indexOf('return api;', start);
    const close = source.indexOf('})();', end) + 5;
    const context = vm.createContext({
        window: {addEventListener() {}}, navigator: {language: 'en-US'},
        localStorage: {getItem() { return null; }, setItem() {}},
        setInterval() {}, console, GM_xmlhttpRequest: request => requests.push(request)
    });
    vm.runInContext(source.slice(start, close), context);
    return {api: context.window.__PixivUserscriptI18n_v1__, requests};
}
const respond = (request, body, status = 200) => request.onload({status, responseText: JSON.stringify(body)});

for (const {file, source} of sources) {
    test(`${path.basename(file)}：缺席词典不发请求，恢复后可加载并拒绝旧服务器响应`, () => {
        const {api, requests} = runtime(source);
        api.register({'en-US': {greeting: 'Local'}});
        api.enrichFromBackend('http://localhost:6999/');
        assert.equal(requests.length, 1);
        assert.equal(requests[0].url, 'http://localhost:6999/api/i18n/meta');
        respond(requests[0], {supportedNamespaces: ['common']});
        assert.equal(requests.length, 1);
        assert.equal(api.t('greeting'), 'Local');

        api.enrichFromBackend('http://localhost:6999');
        respond(requests[1], {supportedNamespaces: ['common', 'userscript']});
        const bundles = requests.slice(2);
        assert.equal(bundles.length, api.listSupported().length);
        assert.ok(bundles.every(request => request.url.startsWith('http://localhost:6999/api/i18n/messages/userscript?lang=')));
        respond(bundles.find(request => request.url.endsWith('en-US')), {messages: {greeting: 'Backend'}});
        assert.equal(api.t('greeting'), 'Backend');

        api.enrichFromBackend('http://localhost:7000');
        respond(bundles.find(request => request.url.endsWith('en-US')), {messages: {greeting: 'Stale'}});
        assert.equal(api.t('greeting'), 'Backend');
        const oldMetadata = requests.at(-1);
        api.enrichFromBackend('http://localhost:7001');
        const count = requests.length;
        respond(oldMetadata, {supportedNamespaces: ['userscript']});
        assert.equal(requests.length, count);
        respond(requests.at(-1), null);
        assert.equal(requests.length, count);
    });
}

test('元数据失败或损坏时保留脚本翻译，不重试词典请求', () => {
    for (const body of [null, {}, {supportedNamespaces: 'userscript'}]) {
        const {api, requests} = runtime(sources[0].source);
        api.enrichFromBackend('http://localhost:6999');
        respond(requests[0], body);
        assert.equal(requests.length, 1);
        assert.equal(api.t('missing', 'Fallback'), 'Fallback');
    }
    const {api, requests} = runtime(sources[0].source);
    api.enrichFromBackend('http://localhost:6999');
    requests[0].onload({status: 200, responseText: '{'});
    respond(requests[0], {supportedNamespaces: ['userscript']}, 503);
    assert.equal(requests.length, 1);
});
