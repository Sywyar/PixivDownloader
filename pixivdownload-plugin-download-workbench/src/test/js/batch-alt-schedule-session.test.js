'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {setup} = require('./batch-alt-schedule-session-support');
const defaults = require('../../../../scripts/schedule/pixiv-defaults.json');

test('缺省任务只改名称时补齐同源下载参数，保留凭证降级与来源范围', async () => {
    for (const mode of [undefined, 'r18']) {
        const h = setup({paramsJson: JSON.stringify({source: {word: 'legacy', mode}})});
        h.run('state.settings.concurrent=8;state.settings.interval=15;state.settings.novelTranslateLang="custom";');
        h.context.openScheduleEditor(h.task);
        assert.equal(h.run('searchState.endPage'), defaults.source.maxPages);
        assert.equal(h.run('searchState.submode'), 'batch');
        h.options(); h.field('abScheduleField0').value = 'renamed';
        await h.drawer.beforeClose(); h.context.closeDrawer();
        await h.save();
        const saved = JSON.parse(h.requests[0].body.definitionJson);
        assert.deepEqual(saved.download, defaults.download);
        assert.equal(saved.source.maxPages, defaults.source.maxPages);
        assert.equal(saved.source.mode, mode);
        assert.equal(saved.filters.content, undefined);
        assert.equal(h.run('state.settings.concurrent'), 8);
    }
});

test('显式零值和用户设置不被默认值覆盖，改动筛选仍正常保存', async () => {
    const download = {...defaults.download, concurrent: 6, intervalMs: 0, imageDelayMs: 0,
        mediaQuality: 77, novelTranslateLanguage: 'custom', novelMerge: true};
    const h = setup({paramsJson: JSON.stringify({source: {word: 'configured', maxPages: -1}, download})});
    h.context.openScheduleEditor(h.task);
    h.run('extraFilters.content="safe";');
    await h.save();
    const saved = JSON.parse(h.requests[0].body.definitionJson);
    assert.deepEqual(saved.download, download);
    assert.equal(saved.source.maxPages, -1);
    assert.equal(saved.source.mode, 'safe');
    assert.equal(saved.filters.content, 'safe');
});

test('编辑跳转来源入口，共用采集逻辑保存原任务，随后恢复手动设置',async()=>{
    const h=setup();const manual=h.run('JSON.stringify(state.settings)');h.context.openScheduleEditor(h.task);
    assert.equal(h.run('state.mode'),'search');assert.equal(h.drawer,null);
    assert.equal(h.field('abSearchInput').value,'original');assert.equal(h.context.saveScheduleButton(),null);
    h.field('abSearchInput').value='edited';h.run('extraFilters.bookmarkMin=150;scheduleState.editing.settings.mediaQuality=75;');
    assert.equal(h.run('JSON.stringify(state.settings)'),manual);
    await h.save();assert.equal(h.requests.length,1);
    const request=h.requests[0];assert.equal(request.method,'PUT');assert.equal(request.url,'/api/schedule/tasks/42');
    assert.equal(request.body.expectedStateVersion,9);const saved=JSON.parse(request.body.definitionJson);
    assert.equal(saved.source.word,'edited');assert.equal(saved.filters.bookmarksMin,150);
    assert.equal(saved.download.mediaQuality,75);assert.deepEqual(saved.custom,{preserved:true});
    assert.equal(h.run('JSON.stringify(state.settings)'),manual);assert.equal(h.run('state.mode'),'schedule');
    assert.equal(h.run('scheduleState.editing'),null);
});

test('切页前确认，拒绝则保留编辑，取消后恢复手动偏好',async()=>{
    const h=setup();h.run('searchState.word="manual";extraFilters.bookmarkMin=12;');h.context.openScheduleEditor(h.task);
    h.options();h.field('abScheduleField0').value='draft name';
    await h.drawer.beforeClose();h.context.closeDrawer();
    const node=h.run('scheduleState.editing.element');h.context.renderStage();h.options();
    assert.equal(h.run('scheduleState.editing.element'),node);assert.equal(h.field('abScheduleField0').value,'draft name');
    await h.drawer.beforeClose();h.context.closeDrawer();
    h.context.switchMode('user');await Promise.resolve();assert.equal(h.run('state.mode'),'search');
    h.confirm(true);await h.context.cancelScheduleEdit();assert.equal(h.run('searchState.word'),'manual');
    assert.equal(h.run('extraFilters.bookmarkMin'),12);assert.equal(h.requests.length,0);
});

test('保存冲突保留输入，旧来源租约阻止后续写入',async()=>{
    const h=setup();h.context.openScheduleEditor(h.task);h.fail();h.field('abSearchInput').value='keep';await h.save();
    assert.equal(h.field('abSearchInput').value,'keep');assert.ok(h.run('scheduleState.editing'));
    h.stale();await h.save();assert.equal(h.requests.length,1);
});

test('旧列表令牌拒绝进入编辑，改变来源类型也不提交',async()=>{
    const old=setup({sourceActivationToken:'old'});old.context.openScheduleEditor(old.task);
    assert.equal(old.run('scheduleState.editing'),null);assert.equal(old.toasts.length,1);
    const h=setup({sourceType:'user-new',paramsJson:JSON.stringify({source:{userId:'123'},kind:'illust'})});
    h.context.openScheduleEditor(h.task);assert.equal(h.field('abUserInput').value,'123');
    h.run('userState.kind="request";');await h.save();assert.equal(h.requests.length,0);
});

test('旧任务缺失字段复用共享默认值，编辑设置不写入手动偏好',()=>{
    const h=setup({paramsJson:JSON.stringify({kind:'illust',source:{word:'legacy'}})});h.context.openScheduleEditor(h.task);
    assert.equal(h.run('scheduleState.editing.settings.fileNameTemplate'),h.context.PixivBatch.pixivScheduleDefaults.download.fileNameTemplate);
    assert.equal(h.run('scheduleState.editing.settings.mediaQuality'),h.context.PixivMediaSettings.snapshot({}).mediaQuality);
    h.context.saveSettings();h.context.saveSearchFilterPrefs({});assert.equal(h.requests.length,0);
});

test('计划设置关闭后保留草稿，只有主编辑栏保存才更新原任务',async()=>{
    const h=setup();h.context.openScheduleEditor(h.task);
    assert.equal(h.field('abScheduleField0'),null);
    h.options();h.field('abScheduleField0').value='renamed';
    h.field('abScheduleField2').value='0 15 9 * * ?';
    h.field('abScheduleField3').value='12';
    await h.drawer.beforeClose();h.context.closeDrawer();
    assert.equal(h.requests.length,0);
    h.options();assert.equal(h.field('abScheduleField0').value,'renamed');
    await h.drawer.beforeClose();h.context.closeDrawer();
    await h.save();
    assert.equal(h.requests[0].body.name,'renamed');
    assert.equal(h.requests[0].body.cronExpr,'0 15 9 * * ?');
    assert.equal(JSON.parse(h.requests[0].body.definitionJson).fetchLimit,12);
});

test('进入编辑后迟到的手动搜索结果不覆盖计划草稿',async()=>{
    const h=setup();let resolveRequest;
    h.run('searchState.word="manual";renderSearchStage=()=>{};');
    h.context.PixivBatch.queueTypes.acquisition=()=>({buildRequest:()=>({endpoint:'/fixture'})});
    h.context.altAcquisitionJson=()=>new Promise(resolve=>{resolveRequest=resolve;});
    const pending=h.context.runSearch(1);
    h.context.openScheduleEditor(h.task);
    resolveRequest({items:[{id:'late'}],total:1});
    await pending;
    assert.equal(h.field('abSearchInput').value,'original');
    assert.equal(h.run('searchState.rawResults.length'),0);
    assert.equal(h.run('scheduleState.editing.original.search.loading'),false);
});

test('快捷编辑沿用所选类型贡献，收藏可切换小说而珍藏集保留混合类型',async()=>{
    const h=setup({sourceType:'my-bookmarks',paramsJson:JSON.stringify({kind:'illust',source:{rest:'hide'}})});
    h.context.PixivBatch.queueTypes.acquisition=type=>({actions:{
        'my-illust-bookmarks-hide':{sourceType:'my-bookmarks',scheduleRest:'hide',
            scheduleSource:()=>({sourceType:'my-bookmarks',source:{rest:'hide'},kind:type})}
    }});
    h.context.openScheduleEditor(h.task);
    assert.equal(h.run('quickState.action'),'my-illust-bookmarks-hide');
    h.run('quickState.kind="novel";');
    assert.equal(h.context.altQuickScheduleSource().kind,'novel');
    await h.save();
    const saved=JSON.parse(h.requests[0].body.definitionJson);
    assert.equal(saved.kind,'novel');assert.equal(saved.source.rest,'hide');
    const c=setup({sourceType:'collection',paramsJson:JSON.stringify({kind:'mixed',source:{collectionId:'27'}})});
    c.context.openScheduleEditor(c.task);c.run('quickState.kind="illust";');await c.save();
    const collection=JSON.parse(c.requests[0].body.definitionJson);
    assert.equal(collection.kind,'mixed');assert.equal(collection.source.collectionId,'27');
});
