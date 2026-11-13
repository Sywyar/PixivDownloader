'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {setup} = require('./batch-alt-schedule-session-support');
const {harness} = require('./schedule-submit-test-support');
const staticRoot = resolve(__dirname, '../../main/resources/static');
const read = file => readFileSync(resolve(staticRoot, file), 'utf8');

function installAcquisition(c, kind) {
    let descriptor;
    const qt = c.window.PixivBatch.queueTypes ||= {};
    const activation = {type: 'illust', signal: new AbortController().signal, onCleanup() {}, assertActive() {}};
    c.SINGLE_IMPORT_MODE = 'single-import';
    c.processIllustItem = () => {};
    for (const name of ['renderPixivUserResults', 'renderPixivSearchResults', 'renderPixivSeriesResults',
        'renderQuickIllustGrid', 'pixivQuickInnerCard', 'followingQuickScheduleSource', 'collectionQuickScheduleSource']) c[name] ||= () => {};
    c.QUICK_PAGE_SIZE_ILLUST = 20;
    qt.registerModule = init => { descriptor = init(activation).descriptor; };
    vm.runInContext(read('pixiv-batch/pixiv-queue-type.js'), c);
    const shared = {context: activation};
    qt.registerSubmodule = init => init(shared);
    vm.runInContext(readFileSync(resolve(__dirname,
        '../../../../pixivdownload-plugin-novel/src/main/resources/static/pixiv-novel-download/novel-queue-acquisition.js'), 'utf8'), c);
    qt.acquisition = () => kind === 'novel' ? {fetchMeta: shared.getNovelUserMeta} : descriptor.acquisition.user;
    qt.acquisitionLease = () => ({signal: activation.signal, assertCurrent: activation.assertActive});
    c.getCookie = () => { throw new Error('Manual credential must not be read'); };
    c.storeGet = () => '["unavailable-manual-entry"]';
    vm.runInContext(read('pixiv-batch/credential-library.js'), c);
}

function editor(alt, kind, scenario) {
    const editing = ['saved', 'changed'].includes(scenario);
    const source = {userId: '7', username: 'Saved user', acquisitionMode: 'user'};
    const params = {kind, source};
    let h, c, save, task, choose = async () => {};
    if (alt) {
        h = setup({sourceType: 'user-new', paramsJson: JSON.stringify(params),
            proxy: 'plan.proxy:8080', credentialPolicy: {bound: true}});
        c = h.context; task = h.task; h.confirm(true);
        if (editing) { h.context.openScheduleEditor(task); h.options(); }
        else {
            h.run(`state.mode='user';userState.kind='${kind}';renderStage();`);
            h.field('abUserInput').value = '7';
            h.context.openScheduleEditor(); h.field('abScheduleField0').value = 'New schedule';
        }
        if (scenario === 'changed') h.field('abUserInput').value = '8';
        h.field('abScheduleField4').value = 'plan.proxy:8080';
        if (scenario === 'inline') h.field('abScheduleField5').value = 'PHPSESSID=synthetic-plan';
        h.drawer.body.isConnected = true;
        save = editing ? h.save : () => h.drawer.footer.querySelector('.ab-btn--primary').listeners.get('click')[0]();
    } else {
        h = harness({supportsProxy: true, supportsCredential: true, cookieChecked: editing || ['inline', 'selection'].includes(scenario),
            cookieValue: scenario === 'inline' ? 'PHPSESSID=synthetic-plan' : '', savedSelection: {ids: ['plan-entry'], label: 'Plan credential'},
            sourcePreview: {sourceType: 'user-new'}});
        c = h.context;
        task = {id: 17, sourceType: 'user-new', paramsJson: JSON.stringify(params), stateVersion: 9,
            proxy: 'plan.proxy:8080', credentialPolicy: {bound: true}};
        if (editing) h.setEditing(task.id, [task]);
        h.element('sch-proxy-enabled').checked = true; h.element('sch-proxy').value = 'plan.proxy:8080';
        const runtime = c.window.PixivBatch.scheduleSources;
        let handler;
        runtime.registerModule = (_url, init) => init({descriptors: [{sourceType: 'user-new'}], registerSource(_id, value) { handler = value; }});
        vm.runInContext(read('pixiv-batch/pixiv-schedule-defaults.js'), c);
        vm.runInContext(read('pixiv-batch/pixiv-schedule-sources.js'), c);
        runtime.captureForMode = () => ({sourceType: 'user-new', activationToken: 'token-a', params: {
            kind, source: {userId: scenario === 'changed' ? '8' : '7', username: '', acquisitionMode: 'user'}}});
        runtime.prepareSubmission = async (snapshot, context) => ({...snapshot,
            params: await handler.prepareDefinition(snapshot.params, {...context, editingTask: context.task},
                {...runtime.activationLease(), sourceType: snapshot.sourceType})});
        save = h.submit;
        if (scenario === 'selection') choose = () => h.fillSavedCredential('sch-cookie');
    }
    installAcquisition(c, kind);
    if (alt) {
        c.validateScheduleCredential = async () => null;
        c.bindScheduleCredential = async () => ({ok: true});
        c.PixivBatch.scheduleSources.selectSavedCredential = async () => ({ids: ['plan-entry'], label: 'Plan credential'});
        if (scenario === 'selection') choose = () => Array.from(h.drawer.body.querySelectorAll('button'))
            .find(button => /保存|saved/.test(button.textContent)).listeners.get('click')[0]();
    }
    const requests = [];
    let metadata = async () => ({ok: true, json: async () => ({name: 'Selected user'})});
    c.fetch = async (url, init = {}) => {
        requests.push({url, init});
        if (url.endsWith('/meta/schedule')) return metadata();
        return {ok: true, json: async () => ({...task, stateVersion: 10})};
    };
    return {h, c, task, save, choose, requests, setMetadata(fn) { metadata = fn; }};
}

for (const alt of [false, true]) for (const kind of ['illust', 'novel']) {
    const label = `${alt ? '新版' : '经典'} ${kind}`;
    test(`${label} 同一用户编辑复用保存快照，不读取失效手动凭证`, async () => {
        const f = editor(alt, kind, 'saved');
        await f.save();
        assert.equal(f.requests.length, 1);
        assert.equal(f.requests[0].init.method, 'PUT');
        const definition = JSON.parse(JSON.parse(f.requests[0].init.body).definitionJson);
        assert.equal(definition.source.username, 'Saved user');
    });
    for (const scenario of ['changed', 'inline', 'selection', 'anonymous']) {
        test(`${label} ${scenario} 真实元数据请求使用计划上下文与代理`, async () => {
            const f = editor(alt, kind, scenario);
            await f.choose();
            await f.save();
            const query = f.requests[0];
            assert.ok(query, alt ? f.c.document.querySelector('[role="status"]')?.textContent : f.h.status.textContent);
            assert.equal(query.url, `/api/pixiv/user/${scenario === 'changed' ? '8' : '7'}/meta/schedule`);
            assert.equal(query.init.method, 'POST');
            const request = JSON.parse(query.init.body);
            assert.equal(request.proxy, 'plan.proxy:8080');
            assert.equal(request.useTaskCredential, scenario === 'changed');
            assert.equal(request.taskId, scenario === 'changed' ? f.task.id : undefined);
            assert.equal(query.init.headers['X-Acquisition-Credential'], scenario === 'inline' ? 'PHPSESSID=synthetic-plan' : undefined);
            assert.equal(query.init.headers['X-Acquisition-Credentials'], scenario === 'selection' ? 'plan-entry' : undefined);
            const write = f.requests.find(r => /\/api\/schedule\/tasks(?:\/\d+)?$/.test(r.url));
            assert.ok(write);
            assert.equal(JSON.parse(JSON.parse(write.init.body).definitionJson).source.username, 'Selected user');
        });
    }
    test(`${label} 来源撤回时丢弃真实 fetchMeta 的迟到响应`, async () => {
        const f = editor(alt, kind, 'changed');
        let complete;
        f.setMetadata(() => new Promise(resolve => { complete = resolve; }));
        const pending = f.save();
        await new Promise(resolve => setImmediate(resolve));
        assert.ok(complete);
        f.h.stale();
        complete({ok: true, json: async () => ({name: 'Late user'})});
        await pending;
        assert.equal(f.requests.length, 1);
    });
}
