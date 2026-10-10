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
