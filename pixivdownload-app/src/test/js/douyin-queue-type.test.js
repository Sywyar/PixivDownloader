'use strict';
/*
 * Douyin 下载类型前端模块 smoke。
 *
 * 在 Node vm 沙箱里加载真实 batch-queue-types.js 与真实 douyin-queue-type.js，验证：
 *   1) Douyin URL / 分享文本解析只识别 douyin.com / iesdouyin.com。
 *   2) 行为模块通过 queueTypes.register 按稳定 descriptor 接入。
 *   3) import / series / filters / cookie-tools contract 均可发现，下载设置不再贡献到前端。
 *
 * 运行： node src/test/js/douyin-queue-type.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const QT_PATH = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static', 'pixiv-batch',
    'batch-queue-types.js');
const DOUYIN_PATH = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-douyin', 'src', 'main', 'resources', 'static', 'pixiv-douyin-download',
    'douyin-queue-type.js');
const QT_SOURCE = fs.readFileSync(QT_PATH, 'utf8');
const DOUYIN_SOURCE = fs.readFileSync(DOUYIN_PATH, 'utf8');

class El {
    constructor(tag) {
        this.tag = tag;
        this.children = [];
        this.parent = null;
        this.attrs = {};
        this.onload = null;
        this.onerror = null;
        this.src = '';
    }
    setAttribute(k, v) { this.attrs[k] = v; }
    getAttribute(k) { return this.attrs[k]; }
    appendChild(child) {
        child.parent = this;
        this.children.push(child);
        if (child.tag === 'script' && typeof child.onload === 'function') {
            setTimeout(() => child.onload(), 0);
        }
        return child;
    }
    insertAdjacentHTML() {}
    remove() {}
    querySelectorAll() { return []; }
}

function makeSandbox() {
    const storage = new Map();
    const requests = [];
    const document = {
        head: new El('head'),
        body: new El('body'),
        documentElement: new El('html'),
        createElement: tag => new El(tag),
        getElementById: () => null,
        querySelectorAll: () => []
    };
    const window = {
        addEventListener() {},
        dispatchEvent() {}
    };
    const sandbox = {
        window,
        document,
        BASE: '',
        pageI18n: {apply() {}},
        console: {warn() {}, log() {}, error() {}},
        setTimeout,
        clearTimeout,
        Promise,
        URL,
        STATUS_TIMEOUT_MS: 100,
        CustomEvent: function CustomEvent(type, init) { return {type, detail: init && init.detail}; },
        localStorage: {
            getItem: key => storage.get(key) || '',
            setItem: (key, value) => { storage.set(key, value); }
        },
        fetch: (url, options) => {
            requests.push({url: String(url), options: options || {}});
            if (String(url).includes('/api/douyin/download')) {
                return Promise.resolve({ok: true, json: () => Promise.resolve({id: 'status-1'})});
            }
            if (String(url).includes('/api/douyin/status/status-1')) {
                return Promise.resolve({ok: true, json: () => Promise.resolve({
                    completed: true,
                    failed: false,
                    cancelled: false,
                    title: 'Resolved title',
                    messageKey: 'douyin.status.completed'
                })});
            }
            return Promise.resolve({ok: true, json: () => Promise.resolve({
                queueTypes: [
                    {pluginId: 'download-workbench', type: 'illust', order: 1},
                    {pluginId: 'douyin', type: 'douyin', order: 30,
                        moduleUrl: '/pixiv-douyin-download/douyin-queue-type.js'}
                ],
                downloadTypes: [
                    {contractVersion: 1, pluginId: 'douyin', type: 'douyin', order: 30,
                        acquisitionModes: ['single-import', 'series']}
                ],
                tabs: [],
                uiSlots: []
            })});
        },
        bt(key, fallback, args) {
            let out = fallback || key;
            Object.entries(args || {}).forEach(([k, v]) => { out = out.replace('{' + k + '}', String(v)); });
            return out;
        },
        esc: value => String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'),
        state: {queue: []},
        sleep: () => Promise.resolve(),
        setCurrent() {},
        renderQueue() {},
        updateStats() {},
        saveQueue() {},
        setStatus() {},
        pixivHeader: () => ({}),
        addUserItemToQueue() {},
        addSearchItemToQueue() {},
        addSeriesItemToQueue() {},
        quickLoad() {},
        quickToggleItemQueue() {},
        quickState: {},
        quickSetTitle() {},
        quickShowToolbar() {},
        quickRenderOuterWorks: () => Promise.resolve(),
        renderQuickPagination() {}
    };
    sandbox.requests = requests;
    vm.createContext(sandbox);
    vm.runInContext(QT_SOURCE, sandbox);
    sandbox.window.PixivBatch.cookie = {
        getStoredCookie(type) {
            return storage.get(type === 'pixiv' ? 'pixiv_cookie' : `pixiv_${type}_cookie`) || '';
        },
        setStoredCookie(type, value) {
            storage.set(type === 'pixiv' ? 'pixiv_cookie' : `pixiv_${type}_cookie`, value || '');
        },
        removeStoredCookie(type) {
            storage.delete(type === 'pixiv' ? 'pixiv_cookie' : `pixiv_${type}_cookie`);
        },
        getCookieFmt() {
            return storage.get('pixiv_cookie_fmt') || 'header';
        },
        parseCookieToHeaderString(raw, fmt) {
            if (fmt === 'json') {
                return Object.entries(JSON.parse(raw)).map(([k, v]) => `${k}=${v}`).join('; ');
            }
            return raw || '';
        },
        getCookieHeaderStringFor(type) {
            const key = type === 'pixiv' ? 'pixiv_cookie' : `pixiv_${type}_cookie`;
            return this.parseCookieToHeaderString(storage.get(key) || '', this.getCookieFmt());
        }
    };
    vm.runInContext(DOUYIN_SOURCE, sandbox);
    sandbox.window.PixivBatch.queueTypes.register('illust', {
        pluginId: 'download-workbench',
        type: 'illust',
        process() {}
    });
    return sandbox;
}

let passed = 0;
function ok(label, cond) {
    assert.ok(cond, label);
    passed++;
}

(async function () {
    const sandbox = makeSandbox();
    const qt = sandbox.window.PixivBatch.queueTypes;
    await qt.bootstrap();

    const parser = sandbox.window.PixivDouyin.parseInput;
    const video = parser('https://www.douyin.com/video/7351234567890123456?from=share');
    ok('解析 www.douyin.com/video 单作品', video && video.kind === 'single' && video.workId === '7351234567890123456');

    const short = parser('https://v.douyin.com/AbCd123/');
    ok('解析 v.douyin.com 短链', short && short.kind === 'short' && short.workId === 'AbCd123');
    const short2 = parser('v.iesdouyin.com/AbCd123/');
    ok('解析裸 v.iesdouyin.com 短链', short2 && short2.kind === 'short' && short2.workId === 'AbCd123');

    const share = parser('看看这个视频 https://www.douyin.com/video/7350000000000000001，复制此链接');
    ok('从分享文本提取 URL 并去掉中文标点', share && share.kind === 'single' && share.workId === '7350000000000000001');

    ok('解析 note URL', parser('https://www.douyin.com/note/7350000000000000002').kind === 'single');
    ok('解析 gallery URL', parser('https://www.douyin.com/gallery/7350000000000000003').kind === 'single');
    ok('解析用户主页', parser('https://www.douyin.com/user/MS4wLjABAAAA-demo').kind === 'user');
    ok('解析合集 URL', parser('https://www.douyin.com/collection/12345').kind === 'series');
    ok('解析 mix URL', parser('https://www.douyin.com/mix/12345').kind === 'series');
    ok('解析 music URL 为 unsupported 类型输入', parser('https://www.douyin.com/music/12345').kind === 'music');
    ok('拒绝 TikTok URL', parser('https://www.tiktok.com/@demo/video/123') === null);
    ok('拒绝伪造子域', parser('https://douyin.example.com/video/123') === null);

    ok('Douyin 行为模块已注册', qt.has('douyin') === true);
    ok('Douyin 类型启用后可用', qt.isTypeAvailable('douyin') === true);
    ok('后端 downloadTypes descriptor 可读取', qt.downloadTypes().some(d => d.type === 'douyin' && d.contractVersion === 1));

    const descriptor = qt.descriptor('douyin');
    ok('descriptor contractVersion=1', descriptor.contractVersion === 1);
    ok('process(item) 存在', typeof descriptor.process === 'function');
    ok('slots 包含 cookie-tools', !!descriptor.slots['cookie-tools']);
    ok('slots 不包含 settings-card', !descriptor.slots['settings-card']);
    ok('slots 不暴露主页/搜索/快捷默认入口',
        !descriptor.slots['kind-option-user'] && !descriptor.slots['kind-option-search'] && !descriptor.slots['kind-option-quick']);
    ok('filters 可发现', qt.filtersFor('douyin') && qt.filtersFor('douyin').extraSelector === '.search-douyin-only');
    ok('settings 不再暴露抖音下载设置卡', qt.settingsFor('douyin') === null);
    ok('Cookie 登录态字段校验通过',
        sandbox.window.PixivDouyin.validateCookie('ttwid=tt; passport_csrf_token=csrf; sessionid=sid').ok === true);
    ok('Cookie 缺少会话字段校验失败',
        sandbox.window.PixivDouyin.validateCookie('ttwid=tt; passport_csrf_token=csrf').missing
            .includes('sessionid / sessionid_ss / sid_tt / sid_guard'));
    ok('Cookie 不再把 msToken/odin_tt 作为硬性字段',
        sandbox.window.PixivDouyin.validateCookie('ttwid=tt; passport_csrf_token=csrf; sid_tt=sid').suggestedMissing
            .includes('msToken'));
    ok('acquisition.series 可发现', typeof qt.acquisition('douyin', 'series').parseUrl === 'function');
    ok('acquisition.search 不作为默认能力暴露', qt.acquisition('douyin', 'search') === null);
    ok('acquisition.quick 不作为默认能力暴露', Object.keys(qt.quickActionsFor('douyin')).length === 0);

    const importHook = qt.contributionsOf('import').find(item => item.type === 'douyin');
    const match = importHook.matchUrl('https://www.douyin.com/video/7351234567890123456 | title');
    const item = importHook.buildItem(match, 'Custom title');
    ok('import.matchUrl 返回结构化结果', match && match.workId === '7351234567890123456');
    ok('import.buildItem 生成 Douyin 队列项', item.id === 'd7351234567890123456' && item.kind === 'douyin');
    ok('import.buildItem 保留标题与 URL', item.title === 'Custom title' && /douyin\.com\/video/.test(item.url));
    ok('import.buildItem 写入 typeData.input', item.typeData && /douyin\.com\/video/.test(item.typeData.input));

    const collectionMatch = importHook.matchUrl('https://www.douyin.com/collection/12345');
    const collectionItem = importHook.buildItem(collectionMatch, 'Collection title');
    ok('import 支持合集 URL 入队', collectionItem.typeData.seriesId === '12345'
        && collectionItem.typeData.input === 'https://www.douyin.com/collection/12345');

    sandbox.localStorage.setItem('pixiv_douyin_cookie',
        'ttwid=tt; passport_csrf_token=csrf; sessionid=sid');

    await descriptor.process({
        id: 'dshort-XUyPmdu7naU',
        kind: 'douyin',
        title: 'Queued short',
        typeData: {input: 'https://v.douyin.com/XUyPmdu7naU/'}
    });
    const typedRequest = sandbox.requests.find(req => req.url.includes('/api/douyin/download'));
    ok('process(item) 使用 typeData.input 作为下载输入',
        JSON.parse(typedRequest.options.body).input === 'https://v.douyin.com/XUyPmdu7naU/');
    ok('process(item) 提交完整 Douyin Cookie',
        JSON.parse(typedRequest.options.body).cookie.includes('passport_csrf_token=csrf'));

    sandbox.requests.length = 0;
    sandbox.localStorage.setItem('pixiv_cookie_fmt', 'json');
    sandbox.localStorage.setItem('pixiv_douyin_cookie',
        '{"ttwid":"tt","passport_csrf_token":"csrf","sessionid":"sid"}');
    await descriptor.process({id: 'd7351234567890123456', kind: 'douyin', title: 'JSON cookie'});
    const jsonCookieRequest = sandbox.requests.find(req => req.url.includes('/api/douyin/download'));
    ok('process(item) 按父级 JSON Cookie 格式归一化后提交',
        JSON.parse(jsonCookieRequest.options.body).cookie.includes('sessionid=sid'));

    sandbox.localStorage.setItem('pixiv_cookie_fmt', 'header');
    sandbox.localStorage.setItem('pixiv_douyin_cookie',
        'ttwid=tt; passport_csrf_token=csrf; sessionid=sid');
    sandbox.requests.length = 0;
    await descriptor.process({id: 'dshort-XUyPmdu7naU', kind: 'douyin', title: 'Legacy short'});
    const legacyRequest = sandbox.requests.find(req => req.url.includes('/api/douyin/download'));
    ok('process(item) 兼容旧队列 dshort ID',
        JSON.parse(legacyRequest.options.body).input === 'https://v.douyin.com/XUyPmdu7naU/');

    console.log(`\ndouyin-queue-type.test.js: ${passed} assertions passed ✓`);
})().catch(err => {
    console.error('TEST FAILED:', err && err.message ? err.message : err);
    process.exit(1);
});
