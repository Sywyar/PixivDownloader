'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const root = resolve(__dirname, '../../main/resources/static');
const load = (c, file) => vm.runInContext(readFileSync(resolve(root, file), 'utf8'), c, {filename:file});
const run = (c, code) => vm.runInContext(code, c);
function context() {
    const c = vm.createContext({console, AbortController, URLSearchParams,
        bt: (key, fallback) => fallback || key, document:{addEventListener() {}}});
    c.window = c;
    c.PixivBatch = {};
    c.PixivBatchAlt = {state:{},filters:{},settings:{}};
    load(c, 'pixiv-batch/batch-download-defaults.js');
    load(c, 'pixiv-batch/filename-template.js');
    load(c, 'pixiv-batch/search-filters.js');
    return c;
}

test('两版手动下载使用同一默认设置，加载个人偏好时保留已有值', () => {
    for (const alt of [false,true]) {
        const c = context();
        load(c, alt ? 'pixiv-batch-alt/alt-state.js' : 'pixiv-batch/batch-state.js');
        for (const [key,value] of Object.entries(c.PixivBatch.downloadDefaults))
            assert.equal(run(c, 'state.settings')[key], value);
        if (alt) {
            load(c, 'pixiv-batch-alt/alt-settings.js');
            c.storeGet = key => key === 'pixiv_batch_settings'
                ? JSON.stringify({concurrent:10,interval:7,skipHistory:true}) : null;
            c.loadSettings();
            assert.equal(run(c, 'state.settings.concurrent'), 10);
            assert.equal(run(c, 'state.settings.interval'), 7);
            assert.equal(run(c, 'state.settings.skipHistory'), true);
        }
    }
});

test('收藏数和类型筛选由作品 owner 提供，不误查同 ID 的插画', async () => {
    const c = context(), calls = [];
    c.PixivBatch.queueTypes = {filtersFor:type => type === 'custom-text' ? {
        bookmarkCountFetch: async id => { calls.push(id); return {bookmarkCount:12}; },
        matchExtra: item => item.words >= 10 && item.id === '42',
        evaluateSkip: item => item.words >= 10 ? null : 'owner-filter-reason'
    } : null};
    load(c, 'pixiv-batch-alt/alt-state.js');
    load(c, 'pixiv-batch-alt/alt-filters.js');
    const filters = c.normalizeSearchFilters({bookmarkMin:10, pageMin:100, wordsMin:100});
    const result = await c.computeFilteredItems([{id:'text42',__acquisitionId:'42',words:11,bookmarkCount:null},
        {id:'text43',__acquisitionId:'43',words:1,bookmarkCount:''}],
        filters, 'custom-text', () => false);
    assert.deepEqual(calls, ['42','43']);
    assert.deepEqual(Array.from(result.filtered, x=>x.id), ['text42']);
    assert.equal(c.evaluateTypeExtraSkip({words:1}, filters, 'custom-text'), 'owner-filter-reason');
    const missing = await c.computeFilteredItems([{id:'42'}], c.normalizeSearchFilters({bookmarkMin:10}), 'missing', () => false);
    assert.equal(missing.filtered.length, 0);
    assert.equal(missing.stats.bookmarkMetaMissing, 1);
});

function pageFilters(page) {
    const c = vm.createContext({window: {PixivBatch: {}, PixivBatchAlt: {filters: {}}}});
    const html = readFileSync(resolve(root, page), 'utf8');
    for (const [, src] of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) {
        if (/\/(search-filters|batch-filters|alt-filters)\.js$/.test(src)) load(c, '.' + src);
    }
    return c;
}

for (const page of ['pixiv-batch.html', 'pixiv-batch-alt.html']) {
    test(`${page} 筛选输入保留空值、取整、下限与区间交换语义`, () => {
        const c = pageFilters(page);
        assert.deepEqual(c.normalizeSearchFilters(null), c.defaultSearchFilters());
        assert.deepEqual(c.normalizeSearchFilters({content: 'invalid', ai: '', type: 'unknown'}),
            c.defaultSearchFilters());
        const input = {content: 'r18plus', ai: 'only', type: 'manga',
            pageMin: '8.9', pageMax: '-2', bookmarkMin: ' 0 ', bookmarkMax: Infinity,
            wordsMin: '20', wordsMax: '3', tagsExact: [' Blue Hair ', 'blue hair', '猫'],
            tagsFuzzy: ' cat，Blue, cat '};
        const snapshot = structuredClone(input);
        const actual = JSON.parse(JSON.stringify(c.normalizeSearchFilters(input)));
        assert.deepEqual(actual, {content: 'r18plus', ai: 'only', type: 'manga',
            pageMin: 1, pageMax: 8, bookmarkMin: 0, bookmarkMax: null,
            wordsMin: 3, wordsMax: 20, tagsExact: ['Blue Hair', '猫'], tagsFuzzy: ['cat', 'Blue']});
        assert.deepEqual(input, snapshot);
        for (const value of [null, undefined, '', ' ', 'invalid', Infinity, NaN]) {
            assert.equal(c.parseSearchFilterNumber(value, 1), null);
        }
    });

    test(`${page} 标签按原名和译名匹配并保留分级及收藏数判定`, () => {
        const c = pageFilters(page);
        const item = {tags: ['Blue Hair', {name: '猫', translatedName: 'Cat'},
            {tag: '背景', translation: 'Background'}, null]};
        assert.equal(c.matchTagFilters(item, {tagsExact: ['blue hair', 'CAT'], tagsFuzzy: ['ground']}), true);
        assert.equal(c.matchTagFilters(item, {tagsExact: ['blue'], tagsFuzzy: ['cat']}), false);
        assert.equal(c.matchTagFilters(item, {tagsExact: ['猫'], tagsFuzzy: ['missing']}), false);
        assert.equal(c.matchTagFilters({}, {}), true);
        assert.equal(c.matchTagFilters({}, {tagsFuzzy: ['cat']}), false);
        for (const [content, matching] of [['safe', [0]], ['r18', [1]], ['r18g', [2]],
            ['r18plus', [1, 2, 3]], ['all', [0, 1, 2, 3]]]) {
            for (const rating of [0, 1, 2, 3]) {
                assert.equal(c.matchContentRating(String(rating), content), matching.includes(rating));
            }
        }
        assert.equal(c.matchContentRating(undefined, 'safe'), true);
        assert.equal(c.matchContentRating('invalid', 'r18plus'), false);
        for (const bookmarkCount of [null, undefined, '', -1, 'invalid', Infinity, NaN]) {
            assert.equal(c.getInlineSearchBookmarkCount({bookmarkCount}), null);
        }
        assert.equal(c.getInlineSearchBookmarkCount({bookmarkCount: '0'}), 0);
        assert.equal(c.getInlineSearchBookmarkCount({bookmarkCount: 12}), 12);
    });
}

test('旧版关注新作超过500页继续扫描，未知第1000页仍有后续时整批拒绝', async () => {
    for (const end of [501,1000,1001]) {
        const c = context(), batches = [], statuses = [];
        let pages = 0;
        c.quickState = {rawItems:[{}],allIds:[],action:'my-following-new',loadSeq:0,kind:'illust',pageSize:1};
        load(c, 'pixiv-batch/batch-pagination.js');
        load(c, 'pixiv-batch/modes/quick-fetch-outer.js');
        Object.assign(c, {QUICK_FETCH_MODE:'quick-fetch', uiConfirmKey:async()=>true,
            setQuickBtnLoading() {}, buildQuickQueueMeta:item=>item, setStatus:(message,tone)=>statuses.push(tone),
            currentQuickAction:()=>({ownerType:'illust',buildPageRequest:ctx=>ctx}),
            quickRequestUrl:value=>value,
            quickFetchJson:async ctx=>{pages++; return {items:[{id:String(ctx.page)}],hasNext:ctx.page<end};},
            addItemsToQueue:ids=>{batches.push(ids);return ids.length;}, syncQuickQueueState() {},
            shouldIgnoreQuickOperationError:()=>false});
        await c.quickAddAllToQueue();
        assert.equal(pages, Math.min(end,1000));
        assert.equal(batches.length, end>1000 ? 0 : 1);
        assert.equal(statuses.includes('error'), end>1000);
    }
});

test('引导按类型与ID识别作品，重试只移除未在下载的示例项', () => {
    const c = context();
    load(c, 'pixiv-batch-alt/alt-state.js');
    load(c, 'pixiv-batch/batch-onboarding.js');
    c.removeFromQueue = id => run(c, 'state.queue = state.queue.filter(item=>item.id!==' + JSON.stringify(id) + ')');
    run(c, "state.queue=[{id:'42',kind:'novel',status:'completed'},{id:'43',kind:'illust',status:'skipped'}]");
    const guide = c.PixivBatch.onboarding;
    assert.equal(guide.exampleProgress('42').status, 'removed');
    assert.equal(guide.exampleProgress('43').status, 'skipped');
    run(c, "state.queue[1].status='downloading'");
    assert.equal(guide.retryExample('43'), false);
    run(c, "state.queue[1].status='failed'");
    assert.equal(guide.retryExample('43'), true);
    assert.equal(run(c, 'state.queue.length'), 1);
});

test('页面说明复用导航入口，刷新时不叠加点击处理并更新可访问名称', () => {
    const c = context(), label = {textContent:''}, attrs = {};
    const button = {hidden:true, setAttribute:(key,value)=>{attrs[key]=value;}, querySelector:()=>label};
    c.document.getElementById = () => button;
    load(c, 'pixiv-batch/batch-onboarding.js');
    let oldClicks = 0, clicks = 0;
    c.PixivBatch.onboarding.bindTourButton({start:()=>{oldClicks++;}});
    c.bt = () => 'Guide';
    c.PixivBatch.onboarding.bindTourButton({start:force=>{assert.equal(force,true);clicks++;}});
    button.onclick();
    assert.equal(oldClicks,0);
    assert.equal(clicks,1);
    assert.equal(button.hidden,false);
    assert.equal(button.title,'Guide');
    assert.equal(attrs['aria-label'],'Guide');
    assert.equal(label.textContent,'Guide');
});

test('计划列表只在前台每4秒刷新，暂停撤销请求，返回立即刷新且丢弃晚响应', async () => {
    const c = context(), timers = new Map(), requests = [], events = {};
    let next = 0, renders = 0;
    c.document = {hidden:false,addEventListener:(name,fn)=>{events[name]=fn;}};
    Object.assign(c, {setInterval:(fn,ms)=>{const id=++next;timers.set(id,{fn,ms});return id;},
        clearInterval:id=>timers.delete(id),BASE:'',
        releaseAllScheduleQueues() {},altScheduleSources:()=>({refresh:async()=>{}}),
        scheduleHttpError:async()=>new Error('failure'),
        fetch:(_url,init)=>new Promise(resolve=>requests.push({init,resolve}))});
    load(c, 'pixiv-batch-alt/alt-state.js');
    load(c, 'pixiv-batch-alt/alt-schedule.js');
    c.renderScheduleTaskList = ()=>{renders++;};
    run(c, "state.mode='schedule'");
    c.enterScheduleMode();
    await new Promise(resolve=>setImmediate(resolve));
    assert.equal(requests.length, 1);
    assert.equal([...timers.values()][0].ms,4000);
    [...timers.values()][0].fn();
    assert.equal(requests.length, 1);
    c.document.hidden=true;events.visibilitychange();
    assert.equal(timers.size,0);
    assert.equal(requests[0].init.signal.aborted,true);
    c.document.hidden=false;events.visibilitychange();
    await new Promise(resolve=>setImmediate(resolve));
    requests[1].resolve({ok:true,json:async()=>[{id:2}]});
    await new Promise(resolve=>setImmediate(resolve));
    requests[0].resolve({ok:true,json:async()=>[{id:1}]});
    await new Promise(resolve=>setImmediate(resolve));
    assert.equal(run(c,'scheduleState.tasks[0].id'),2);
    assert.equal(renders,1);
    run(c,"state.mode='user'");c.stopSchedulePolling();
    assert.equal(timers.size,0);
});
