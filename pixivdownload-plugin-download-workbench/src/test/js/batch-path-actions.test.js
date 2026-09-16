'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-batch/batch-path-actions.js'), 'utf8');

function harness(choose) {
    const calls = [];
    const prompts = [];
    const context = vm.createContext({
        AbortController, isAdmin: true, state: {settings: {pathOverflowAction: 'ASK'}},
        bt: key => key,
        window: {PixivBatch: {}, PixivFeedback: {choose: async options => {
            prompts.push(options);
            return choose(options);
        }}},
        fetch: async (url, options) => {
            const body = JSON.parse(options.body);
            calls.push(body);
            const action = body.other.pathOverflowAction;
            if (action === 'CANCEL') return {json: async () => ({code: 'DOWNLOAD_PATH_CANCELLED'})};
            return {ok: action !== 'ASK', json: async () => action === 'ASK'
                ? {code: 'DOWNLOAD_PATH_ACTION_REQUIRED', retryToken: 'replacement-ticket',
                    pathProblem: {originalPath: '/long', truncatedPath: '/short', defaultPath: '/id'}}
                : {success: true}};
        }
    });
    vm.runInContext(source, context);
    return {context, calls, prompts, submit: context.window.PixivBatch.pathActions.submit};
}

test('并发作品串行询问、仅本批次复用勾选结果，下次下载重新询问', async () => {
    const h = harness(() => ({value: 'TRUNCATE', remember: true}));
    await Promise.all([h.submit('/download', {artworkId: 1}), h.submit('/download', {artworkId: 2})]);
    assert.equal(h.prompts.length, 1);
    assert.equal(h.calls.filter(value => value.other.pathOverflowAction === 'TRUNCATE').length, 2);
    assert.equal(h.context.state.settings.pathOverflowAction, 'ASK');
    vm.runInContext('beginPathActionBatch()', h.context);
    await h.submit('/download', {artworkId: 3});
    assert.equal(h.prompts.length, 2);
});

test('未勾选时逐作品授权，小说重试携服务端新票据', async () => {
    const h = harness(() => ({value: 'DEFAULT_NAME', remember: false}));
    await h.submit('/novel', {novelId: 1, fetchToken: 'original-ticket'});
    await h.submit('/download', {artworkId: 2});
    assert.equal(h.prompts.length, 2);
    assert.equal(h.calls[1].fetchToken, 'replacement-ticket');
});

test('取消跳过当前作品，关闭弹窗保留待处理，不继续提交', async () => {
    const cancel = harness(() => ({value: 'CANCEL', remember: false}));
    await assert.rejects(cancel.submit('/download', {artworkId: 1}), {code: 'DOWNLOAD_PATH_CANCELLED'});
    assert.equal(cancel.calls.length, 1);
    const wait = harness(() => null);
    await assert.rejects(wait.submit('/download', {artworkId: 1}), {code: 'DOWNLOAD_PATH_WAIT'});
    const item = {};
    assert.equal(wait.context.window.PixivBatch.pathActions.handleError(item, {code: 'DOWNLOAD_PATH_WAIT'}), true);
    assert.equal(item.status, 'paused');
});

test('清空批次关闭待确认弹窗，不能提交迟到的确认', async () => {
    let resolve;
    const h = harness(() => new Promise(done => { resolve = done; }));
    const pending = h.submit('/download', {artworkId: 1});
    while (!resolve) await Promise.resolve();
    vm.runInContext('endPathActionBatch()', h.context);
    assert.equal(h.prompts[0].signal.aborted, true);
    resolve({value: 'TRUNCATE', remember: true});
    await assert.rejects(pending, {code: 'DOWNLOAD_PATH_WAIT'});
    assert.equal(h.calls.length, 1);
});
