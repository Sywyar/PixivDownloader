'use strict';
/*
 * Douyin 下载类型前端模块 smoke。
 *
 * 在 Node vm 沙箱里加载真实 batch-queue-types.js 与真实 douyin-queue-type.js，验证：
 *   1) Douyin URL / 分享文本解析只识别 douyin.com / iesdouyin.com。
 *   2) 行为模块通过 scoped registerModule 按后端 owner token 接入。
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
const DOUYIN_TEST_SOURCE = DOUYIN_SOURCE.replace(/\}\)\(\);\s*$/, [
    'window.__testDouyinFetchJson = douyinFetchJson;',
    'window.__testLoadQuickDouyinAccount = loadQuickDouyinAccount;',
    'window.__testLoadQuickDouyinFavoriteCollections = loadQuickDouyinFavoriteCollections;',
    '})();'
].join('\n'));
assert.notStrictEqual(DOUYIN_TEST_SOURCE, DOUYIN_SOURCE,
    '测试应能临时暴露真实 douyinFetchJson 助手');

class El {
    constructor(tag) {
        this.tag = tag;
        this.children = [];
        this.parent = null;
        this.attrs = {};
        this.dataset = {};
        this.onload = null;
        this.onerror = null;
        this.src = '';
    }
    setAttribute(k, v) { this.attrs[k] = v; }
    getAttribute(k) { return this.attrs[k]; }
    appendChild(child) {
        child.parent = this;
        this.children.push(child);
        if (typeof this.onAppend === 'function') {
            this.onAppend(child);
        } else if (child.tag === 'script' && typeof child.onload === 'function') {
            setTimeout(() => child.onload(), 0);
        }
        return child;
    }
    insertAdjacentHTML() {}
    remove() {
        if (!this.parent) return;
        const index = this.parent.children.indexOf(this);
        if (index >= 0) this.parent.children.splice(index, 1);
        this.parent = null;
    }
    querySelectorAll() { return []; }
}

function makeSandbox() {
    const storage = new Map();
    const requests = [];
    const apiResponses = [];
    const document = {
        head: new El('head'),
        body: new El('body'),
        documentElement: new El('html'),
        currentScript: null,
        createElement: tag => new El(tag),
        getElementById: () => null,
        querySelectorAll: () => []
    };
    const window = {
        location: {origin: 'https://local.test'},
        addEventListener() {}, removeEventListener() {},
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
        AbortController,
        URL,
        URLSearchParams,
        STATUS_TIMEOUT_MS: 100,
        CustomEvent: function CustomEvent(type, init) { return {type, detail: init && init.detail}; },
        localStorage: {
            getItem: key => storage.get(key) || '',
            setItem: (key, value) => { storage.set(key, value); }
        },
        fetch: (url, options) => {
            requests.push({url: String(url), options: options || {}});
            if (String(url).includes('/api/douyin/user/')
                || /\/api\/douyin\/me\/(liked|favorites)\?/.test(String(url))
                || String(url).includes('/api/douyin/me/favorite-collections?')) {
                const body = apiResponses.shift() || {};
                return Promise.resolve({ok: true, json: () => Promise.resolve(body)});
            }
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
                epoch: 'douyin-test-epoch',
                revision: 1,
                downloadTypes: [
                    {contractVersion: 1, type: 'douyin', ownerPluginId: 'douyin', packageId: 'douyin',
                        pluginGeneration: 3, publicationId: 9, order: 30,
                        moduleUrl: '/pixiv-douyin-download/douyin-queue-type.js',
                        acquisitionModes: ['single-import', 'user', 'search', 'series', 'quick'],
                        uiSlots: ['kind-option-user', 'kind-option-search', 'kind-option-quick',
                            'quick-actions-bookmarks', 'quick-actions-mine', 'import-hint', 'cookie-tools'],
                        filters: []}
                ],
                tabs: [],
                uiSlots: ['kind-option-user', 'kind-option-search', 'kind-option-quick',
                    'quick-actions-bookmarks', 'quick-actions-mine', 'import-hint', 'cookie-tools']
                    .map(target => ({
                        slotId: `douyin.${target}`, target,
                        moduleUrl: '/pixiv-douyin-download/douyin-queue-type.js', order: 30,
                        owner: {pluginId: 'douyin', packageId: 'douyin', generation: 3, publicationId: 9}
                    }))
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
        pixivHeader: () => ({'X-Pixiv-Cookie': 'pixiv-secret-that-must-not-leak'}),
        addUserItemToQueue() {},
        addSearchItemToQueue() {},
        addSeriesItemToQueue() {},
        quickLoad() {},
        quickToggleItemQueue() {},
        quickState: {},
        quickSetTitle() {},
        quickShowToolbar() {},
        quickRenderOuterWorks: () => Promise.resolve(),
        renderQuickCollectionGrid(items) { sandbox.renderedCollections = items; },
        renderQuickPagination(page, totalPages, jump) {
            sandbox.pagination = {page, totalPages, jump};
        },
        updateExtraFiltersCardVisibility() {},
        updateSaveScheduleCardVisibility() {},
        applyNovelSettingsVisibility() {}
    };
    sandbox.requests = requests;
    sandbox.apiResponses = apiResponses;
    vm.createContext(sandbox);
    vm.runInContext(QT_SOURCE, sandbox);
    document.head.onAppend = child => {
        const pathname = new URL(child.src, window.location.origin).pathname;
        setTimeout(() => {
            if (pathname !== '/pixiv-douyin-download/douyin-queue-type.js') {
                if (typeof child.onload === 'function') child.onload();
                return;
            }
            document.currentScript = child;
            vm.runInContext(DOUYIN_TEST_SOURCE, sandbox);
            document.currentScript = null;
            if (typeof child.onload === 'function') child.onload();
        }, 0);
    };
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

    const descriptor = qt.descriptor('douyin');
    const parser = descriptor.cookie.parseInput;
    const video = parser('https://www.douyin.com/video/7351234567890123456?from=share');
    ok('解析 www.douyin.com/video 单作品', video && video.kind === 'single' && video.workId === '7351234567890123456');
    ok('解析纯数字作品 ID', parser('7351234567890123456').workId === '7351234567890123456');

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
    ok('解析 music URL 为音乐关联作品来源', parser('https://www.douyin.com/music/12345').kind === 'music');
    ok('拒绝 TikTok URL', parser('https://www.tiktok.com/@demo/video/123') === null);
    ok('拒绝伪造子域', parser('https://douyin.example.com/video/123') === null);

    ok('Douyin 行为模块已注册', qt.has('douyin') === true);
    ok('Douyin 类型启用后可用', qt.isTypeAvailable('douyin') === true);
    ok('后端 downloadTypes descriptor 可读取', qt.downloadTypes().some(d => d.type === 'douyin' && d.contractVersion === 1));

    ok('descriptor contractVersion=1', descriptor.contractVersion === 1);
    ok('process(item) 存在', typeof descriptor.process === 'function');
    ok('Douyin 不提前贡献计划任务队列映射', descriptor.scheduledQueueItem == null);
    ok('slots 包含 cookie-tools', !!descriptor.slots['cookie-tools']);
    ok('slots 不包含 settings-card', !descriptor.slots['settings-card']);
    ok('slots 暴露主页/搜索/快捷入口',
        !!descriptor.slots['kind-option-user'] && !!descriptor.slots['kind-option-search'] && !!descriptor.slots['kind-option-quick']);
    ok('不暴露未经上游验证的附加筛选', qt.filtersFor('douyin') === null);
    ok('settings 不再暴露抖音下载设置卡', qt.settingsFor('douyin') === null);
    ok('Cookie 登录态字段校验通过',
        descriptor.cookie.validate('ttwid=tt; passport_csrf_token=csrf; sessionid=sid').ok === true);
    ok('Cookie 缺少会话字段校验失败',
        descriptor.cookie.validate('ttwid=tt; passport_csrf_token=csrf').missing
            .includes('sessionid / sessionid_ss / sid_tt / sid_guard'));
    ok('Cookie 不再把 msToken/odin_tt 作为硬性字段',
        descriptor.cookie.validate('ttwid=tt; passport_csrf_token=csrf; sid_tt=sid').suggestedMissing
            .includes('msToken'));
    ok('acquisition.series 可发现', typeof qt.acquisition('douyin', 'series').parseUrl === 'function');
    sandbox.localStorage.setItem('pixiv_douyin_cookie',
        'ttwid=tt; passport_csrf_token=csrf; sessionid=sid');
    const seriesRequestInit = qt.acquisition('douyin', 'series').requestInit();
    ok('Douyin series 预览请求保持同源凭据策略', seriesRequestInit.credentials === 'same-origin');
    ok('Douyin series 预览请求只携带中性取得凭证',
        seriesRequestInit.headers['X-Acquisition-Credential'].includes('passport_csrf_token=csrf')
        && !seriesRequestInit.headers['X-Pixiv-Cookie']
        && !seriesRequestInit.headers['X-Douyin-Cookie']);
    ok('Douyin 声明全部五种取得模式', ['single-import', 'user', 'search', 'series', 'quick']
        .every(mode => qt.supports('douyin', mode)));
    ok('acquisition.search 提供真实关键词请求',
        qt.acquisition('douyin', 'search').buildRequest({word: '猫', page: 2}).params.word === '猫');
    ok('acquisition.quick 贡献账号作品、喜欢、收藏与合集入口',
        Object.keys(qt.quickActionsFor('douyin')).sort().join(',') ===
        'douyin-favorite-collections,douyin-favorites,douyin-liked,douyin-own-works');
    const douyinQuickActions = qt.quickActionsFor('douyin');
    const ownScheduleSource = douyinQuickActions['douyin-own-works'].scheduleSource({});
    const likedScheduleSource = douyinQuickActions['douyin-liked'].scheduleSource({});
    const favoriteScheduleSource = douyinQuickActions['douyin-favorites'].scheduleSource({});
    ok('账号作品快捷动作贡献精确的 Douyin 计划来源与作品类型',
        ownScheduleSource.sourceType === 'douyin.account.own-works'
        && likedScheduleSource.sourceType === 'douyin.account.liked-works'
        && favoriteScheduleSource.sourceType === 'douyin.account.favorite-works'
        && [ownScheduleSource, likedScheduleSource, favoriteScheduleSource]
            .every(source => source.kind === 'douyin' && source.workTypes.join(',') === 'douyin'));
    const favoriteCollectionSchedule = douyinQuickActions['douyin-favorite-collections'];
    ok('收藏合集列表未选具体合集时不伪造周期来源',
        favoriteCollectionSchedule.scheduleSource({inner: null}) === null);
    const favoriteCollectionScheduleSource = favoriteCollectionSchedule.scheduleSource({
        inner: {type: 'collection', id: 'mix-7', name: 'My collection'}
    });
    ok('收藏合集内层贡献可恢复的具体合集计划来源',
        favoriteCollectionScheduleSource.sourceType === 'douyin.account.favorite-collection'
        && favoriteCollectionScheduleSource.source.collectionId === 'mix-7'
        && favoriteCollectionScheduleSource.kind === 'douyin');
    const quickButtonsHtml = descriptor.slots['quick-actions-bookmarks']
        + descriptor.slots['quick-actions-mine'];
    const quickButtonActions = Array.from(quickButtonsHtml.matchAll(/<button\b[^>]*data-quick="([^"]+)"[^>]*>/g),
        match => match[1]).sort();
    ok('四个 Douyin quick button 都以可点击 button 贡献到宿主 slot',
        quickButtonActions.join(',') ===
            'douyin-favorite-collections,douyin-favorites,douyin-liked,douyin-own-works'
        && (quickButtonsHtml.match(/type="button"/g) || []).length === 4
        && (quickButtonsHtml.match(/class="[^"]*quick-action[^"]*"/g) || []).length === 4
        && !/\sdisabled(?:\s|=|>)/i.test(quickButtonsHtml));
    const userAcquisition = qt.acquisition('douyin', 'user');
    ok('acquisition.user 使用可选分页取得钩子且不再声明 ID + cards 预览路径',
        typeof userAcquisition.fetchPage === 'function'
        && typeof userAcquisition.fetchIds !== 'function'
        && typeof userAcquisition.cardsEndpoint !== 'function');
    sandbox.apiResponses.push({
        items: [{id: 'user-page-2'}], total: 49, nextCursor: 'opaque-3', hasMore: true
    });
    sandbox.requests.length = 0;
    const userPage = await userAcquisition.fetchPage('sec-demo', {
        offset: 24, limit: 24, cursor: 'opaque-2', signal: new AbortController().signal
    });
    const userPageRequest = sandbox.requests.find(req => req.url.includes('/api/douyin/user/sec-demo/works/ids'));
    ok('用户预览页把 offset、limit 与真实游标传给单个分页请求',
        userPage.items[0].id === 'user-page-2'
        && userPageRequest.url.includes('offset=24')
        && userPageRequest.url.includes('limit=24')
        && userPageRequest.url.includes('cursor=opaque-2'));
    ok('用户预览页不再发起逐 ID cards 请求',
        sandbox.requests.length === 1 && !sandbox.requests.some(req => req.url.includes('/works/cards')));
    const quickAcq = qt.acquisition('douyin', 'quick');
    const quickUserPageRequest = quickAcq.buildUserPageRequest('sec-demo', {
        offset: 24, limit: 24, cursor: 'opaque-2'
    });
    ok('快捷关注用户预览贡献相同的中性分页请求形状',
        quickUserPageRequest.endpoint.includes('offset=24')
        && quickUserPageRequest.endpoint.includes('limit=24')
        && quickUserPageRequest.endpoint.includes('cursor=opaque-2')
        && typeof quickAcq.buildUserIdsRequest !== 'function');
    const favoriteCollectionMeta = quickAcq.buildQueueMeta({id: 'work-1'}, {
        action: 'douyin-favorite-collections',
        inner: {type: 'collection', id: 'collection-7', name: 'Favorites'}
    });
    ok('quick 二层珍藏集使用真实 collection id 建立来源关系',
        favoriteCollectionMeta.typeData.sourceId === 'collection-7'
        && favoriteCollectionMeta.typeData.sourceRelations[0].sourceId === 'collection-7');
    const followingUserMeta = quickAcq.buildQueueMeta({id: 'work-2'}, {
        action: 'my-following',
        inner: {type: 'following-user', userId: 'sec-user', name: 'Creator'}
    });
    ok('quick 二层关注用户使用用户来源而非误记为本人作品',
        followingUserMeta.typeData.sourceType === 'douyin.user'
        && followingUserMeta.typeData.sourceId === 'sec-user');
    const wrongOwnerMeta = quickAcq.buildQueueMeta({id: 'work-3'}, {
        action: 'douyin-own-works', accountOwner: 'illust', accountId: 'pixiv-user'
    });
    const ownWorksMeta = quickAcq.buildQueueMeta({id: 'work-4'}, {
        action: 'douyin-own-works', accountOwner: 'douyin', accountId: 'douyin-user'
    });
    const ownWorksIdMeta = quickAcq.buildQueueMetaFromId('work-5', {
        accountOwner: 'douyin', accountId: 'douyin-user'
    });
    ok('本人作品来源只接受 Douyin owner 的账号 UID',
        wrongOwnerMeta.typeData.sourceId === 'own-works'
        && ownWorksMeta.typeData.sourceId === 'douyin-user'
        && ownWorksIdMeta.typeData.sourceId === 'douyin-user');

    const livePhoto = {
        id: '7350000000000000099', kind: 'LIVE_PHOTO',
        pageUrl: 'https://www.douyin.com/note/7350000000000000099',
        authorId: 'live-author',
        media: [
            {type: 'IMAGE', url: 'https://signed.example/image?token=ephemeral'},
            {type: 'LIVE_PHOTO_VIDEO', url: 'https://signed.example/video?token=ephemeral'}
        ]
    };
    const livePhotoMeta = userAcquisition.buildQueueMeta(livePhoto, {
        userId: 'live-author', username: 'Live Creator'
    });
    ok('实况照片按可重解析的作品身份映射为单个 Douyin 队列项',
        userAcquisition.queueId(livePhoto) === `d${livePhoto.id}`
        && livePhotoMeta.kind === 'douyin'
        && livePhotoMeta.typeData.douyinId === '7350000000000000099'
        && livePhotoMeta.typeData.input === 'https://www.douyin.com/note/7350000000000000099'
        && livePhotoMeta.typeData.sourceType === 'douyin.user'
        && !JSON.stringify(livePhotoMeta.typeData).includes('signed.example'));

    const collectionAction = qt.quickActionsFor('douyin')['douyin-favorite-collections'];
    const collectionWorksRequest = collectionAction.buildCollectionWorksPageRequest('mix-1', {
        offset: 24, limit: 24, cursor: 'work-cursor-2'
    });
    ok('收藏合集集内作品贡献 cursor/pageSize 分页请求且移除全量入口',
        collectionWorksRequest.endpoint.includes('/favorite-collections/mix-1/works?')
        && collectionWorksRequest.endpoint.includes('cursor=work-cursor-2')
        && collectionWorksRequest.endpoint.includes('pageSize=24')
        && typeof collectionAction.buildCollectionWorksRequest !== 'function');
    sandbox.apiResponses.push({
        collections: [{id: 'mix-1', title: '第一页'}], total: 25,
        nextCursor: 'collection-2', hasMore: true
    });
    sandbox.requests.length = 0;
    await collectionAction.load();
    ok('收藏合集第一页传入 cursor=0 与真实 pageSize',
        sandbox.requests[0].url.includes('cursor=0')
        && sandbox.requests[0].url.includes('pageSize=24')
        && sandbox.renderedCollections[0].id === 'mix-1');
    sandbox.apiResponses.push({
        collections: [{id: 'mix-2', title: '第二页'}], total: 25,
        nextCursor: '', hasMore: false
    });
    await sandbox.pagination.jump(2);
    ok('收藏合集第二页使用上一页返回的真实游标且不重放全量',
        sandbox.requests[1].url.includes('cursor=collection-2')
        && sandbox.requests[1].url.includes('pageSize=24')
        && sandbox.renderedCollections.length === 1
        && sandbox.renderedCollections[0].id === 'mix-2');

    sandbox.apiResponses.push({
        items: [{id: 'lease-1'}], total: 2, nextCursor: 'lease-cursor-2', hasMore: true
    });
    sandbox.requests.length = 0;
    sandbox.quickState.accountOwner = 'douyin';
    sandbox.quickState.uid = 'douyin-user';
    let actionCurrent = true;
    const actionContext = {
        isCurrent() { return actionCurrent; },
        assertCurrent() {
            if (!actionCurrent) {
                const error = new Error('stale action lease');
                error.code = 'STALE_ACQUISITION';
                throw error;
            }
        }
    };
    await sandbox.window.__testLoadQuickDouyinAccount(
        'liked', 'douyin.account.liked-works', 'quick.liked', 'Liked works', 1, actionContext);
    const leasedJump = sandbox.pagination.jump;
    const requestCountBeforeStaleJump = sandbox.requests.length;
    actionCurrent = false;
    await assert.rejects(leasedJump(2), error => error && error.code === 'STALE_ACQUISITION');
    ok('Douyin quick 分页回调继承 action lease 且失效后不再请求或写状态',
        sandbox.requests.length === requestCountBeforeStaleJump
        && sandbox.quickState.rawItems[0].id === 'lease-1');

    const importHook = qt.contributionsOf('import').find(item => item.type === 'douyin');
    const match = importHook.matchUrl('https://www.douyin.com/video/7351234567890123456 | title');
    const item = importHook.buildItem(match, 'Custom title');
    ok('import.matchUrl 返回结构化结果', match && match.workId === '7351234567890123456');
    ok('import.buildItem 生成 Douyin 队列项', item.id === 'd7351234567890123456' && item.kind === 'douyin');
    ok('import.buildItem 保留标题与 URL', item.title === 'Custom title' && /douyin\.com\/video/.test(item.url));
    ok('import.buildItem 写入 typeData.input', item.typeData && /douyin\.com\/video/.test(item.typeData.input));
    ok('import.buildItem 同步建立单项来源关系列表', item.typeData.sourceRelations.length === 1
        && item.typeData.sourceRelations[0].sourceType === 'douyin.single');

    const collectionMatch = importHook.matchUrl('https://www.douyin.com/collection/12345');
    const collectionItem = importHook.buildItem(collectionMatch, 'Collection title');
    ok('import 支持合集 URL 入队', collectionItem.typeData.seriesId === '12345'
        && collectionItem.typeData.input === 'https://www.douyin.com/collection/12345');
    const userItem = importHook.buildItem(importHook.matchUrl('https://www.douyin.com/user/sec-demo'), 'User');
    ok('import 用户主页使用明确的用户来源关系', userItem.typeData.sourceType === 'douyin.user');
    const musicItem = importHook.buildItem(importHook.matchUrl('https://www.douyin.com/music/12345'), 'Music');
    ok('import 音乐链接使用关联作品来源关系', musicItem.typeData.sourceType === 'douyin.music');

    ok('Douyin descriptor 暴露中性的 typeData 合并 hook', typeof descriptor.mergeQueueTypeData === 'function');
    ok('Douyin descriptor 暴露中性的 canonical URL hook', typeof descriptor.canonicalUrl === 'function');
    const mergedRelations = descriptor.mergeQueueTypeData(
        {sourceType: 'douyin.search', sourceId: 'cat', sourceTitle: 'Cat'},
        {sourceType: 'douyin.collection', sourceId: 'mix-1', sourceUrl: 'https://www.douyin.com/mix/mix-1'}
    );
    ok('新来源按首次发现顺序合并并要求重处理现有队列项', mergedRelations.keepExisting === true
        && mergedRelations.reprocessExisting === true
        && mergedRelations.typeData.sourceRelations.map(r => r.sourceId).join(',') === 'cat,mix-1');
    const sameRelation = descriptor.mergeQueueTypeData(mergedRelations.typeData, {
        sourceType: 'douyin.collection', sourceId: 'mix-1', sourceTitle: 'Collection'
    });
    ok('同 sourceType+sourceId 稳定去重且不改变点击移除语义', sameRelation.keepExisting === false
        && sameRelation.reprocessExisting === false
        && sameRelation.typeData.sourceRelations.length === 2
        && sameRelation.typeData.sourceRelations[1].sourceTitle === 'Collection');
    const bounded = descriptor.mergeQueueTypeData({
        sourceRelations: Array.from({length: 70}, (_, index) => ({
            sourceType: 'douyin.search', sourceId: 'source-' + index
        }))
    }, null).typeData.sourceRelations;
    ok('sourceRelations 有界且保持稳定顺序', bounded.length === 64
        && bounded[0].sourceId === 'source-0' && bounded[63].sourceId === 'source-63');
    ok('canonical URL 优先使用 typeData.input/url',
        descriptor.canonicalUrl({id: 'd1', typeData: {input: 'https://v.douyin.com/input/'}})
            === 'https://v.douyin.com/input/'
        && descriptor.canonicalUrl({id: 'd2', typeData: {url: 'https://www.douyin.com/video/2'}})
            === 'https://www.douyin.com/video/2');

    sandbox.requests.length = 0;
    await sandbox.window.__testDouyinFetchJson('/api/douyin/me', {
        headers: {
            'Content-Type': 'application/json',
            'x-pixiv-cookie': 'caller-pixiv-secret',
            'X-Douyin-Cookie': 'caller-douyin-secret',
            'X-Acquisition-Credential': 'caller-generic-secret'
        }
    });
    const helperRequest = sandbox.requests.find(req => req.url.includes('/api/douyin/me'));
    ok('douyinFetchJson 请求使用插件当前的中性取得凭证',
        helperRequest.options.headers['X-Acquisition-Credential'].includes('passport_csrf_token=csrf')
        && !helperRequest.options.headers['X-Acquisition-Credential'].includes('caller-generic-secret'));
    ok('douyinFetchJson 不继承来源专用凭证头',
        !Object.keys(helperRequest.options.headers).some(name => {
            const normalized = name.toLowerCase();
            return normalized === 'x-pixiv-cookie' || normalized === 'x-douyin-cookie';
        }));
    ok('douyinFetchJson 保留请求自身所需的非 Pixiv 请求头',
        helperRequest.options.headers['Content-Type'] === 'application/json');
    ok('douyinFetchJson 保持同源凭据策略', helperRequest.options.credentials === 'same-origin');

    await descriptor.process({
        id: 'dshort-XUyPmdu7naU',
        kind: 'douyin',
        title: 'Queued short',
        typeData: {
            input: 'https://v.douyin.com/XUyPmdu7naU/',
            sourceType: 'douyin.search',
            sourceId: 'cat',
            sourceRelations: [
                {sourceType: 'douyin.search', sourceId: 'cat', sourceTitle: 'Cat'},
                {sourceType: 'douyin.collection', sourceId: 'mix-1', sourceOrder: 3},
                {sourceType: 'douyin.search', sourceId: 'cat', sourceUrl: 'https://www.douyin.com/search/cat'}
            ]
        }
    });
    const typedRequest = sandbox.requests.find(req => req.url.includes('/api/douyin/download'));
    const typedBody = JSON.parse(typedRequest.options.body);
    ok('process(item) 使用 typeData.input 作为下载输入',
        typedBody.input === 'https://v.douyin.com/XUyPmdu7naU/');
    ok('process(item) 通过中性取得凭证头提交完整 Douyin Cookie',
        typedRequest.options.headers['X-Acquisition-Credential'].includes('passport_csrf_token=csrf'));
    ok('process(item) 不把 Douyin Cookie 写入 JSON 请求体',
        typedBody.cookie === null);
    ok('process(item) 向后端发送保序去重后的全部来源关系', typedBody.sourceRelations.length === 2
        && typedBody.sourceRelations[0].sourceId === 'cat'
        && typedBody.sourceRelations[0].sourceUrl === 'https://www.douyin.com/search/cat'
        && typedBody.sourceRelations[1].sourceId === 'mix-1');

    sandbox.requests.length = 0;
    const inflightItem = {
        id: 'd7351234567890123456', kind: 'douyin', title: 'In-flight relation merge',
        typeData: {
            input: 'https://www.douyin.com/video/7351234567890123456',
            sourceType: 'douyin.search', sourceId: 'source-a'
        }
    };
    let relationInjected = false;
    sandbox.sleep = () => {
        if (!relationInjected) {
            const merged = descriptor.mergeQueueTypeData(inflightItem.typeData, {
                sourceType: 'douyin.collection', sourceId: 'source-b'
            });
            inflightItem.typeData = merged.typeData;
            relationInjected = true;
        }
        return Promise.resolve();
    };
    await descriptor.process(inflightItem);
    sandbox.sleep = () => Promise.resolve();
    const inflightRequests = sandbox.requests.filter(req => req.url.includes('/api/douyin/download'));
    const firstInflightBody = JSON.parse(inflightRequests[0].options.body);
    const secondInflightBody = JSON.parse(inflightRequests[1].options.body);
    ok('下载进行中新增来源会在首次完成后再次 POST', inflightRequests.length === 2
        && firstInflightBody.sourceRelations.length === 1
        && secondInflightBody.sourceRelations.length === 2);
    ok('重处理直到来源 fingerprint 稳定后才进入 completed', inflightItem.status === 'completed'
        && secondInflightBody.sourceRelations.map(relation => relation.sourceId).join(',')
            === 'source-a,source-b');

    sandbox.requests.length = 0;
    sandbox.localStorage.setItem('pixiv_cookie_fmt', 'json');
    sandbox.localStorage.setItem('pixiv_douyin_cookie',
        '{"ttwid":"tt","passport_csrf_token":"csrf","sessionid":"sid"}');
    await descriptor.process({id: 'd7351234567890123456', kind: 'douyin', title: 'JSON cookie'});
    const jsonCookieRequest = sandbox.requests.find(req => req.url.includes('/api/douyin/download'));
    ok('process(item) 按父级 JSON Cookie 格式归一化后提交凭证头',
        jsonCookieRequest.options.headers['X-Acquisition-Credential'].includes('sessionid=sid'));
    ok('process(item) 对 JSON Cookie 同样保持请求体无凭证',
        JSON.parse(jsonCookieRequest.options.body).cookie === null);

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
