'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../main/resources/static');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const shared = read('pixiv-batch/batch-path-actions.js');

function harness(choice, alt) {
    const requests = [];
    let prompts = 0;
    const task = {id: 1, stateVersion: 7, suspendReason: 'USER_ACTION_REQUIRED'};
    const context = vm.createContext({
        window: {PixivBatch: {}, PixivFeedback: {choose: async options => {
            assert.equal(options.title, 'batch:path.overflow.title');
            prompts++; return choice;
        }}},
        BASE: '', bt: key => key, setScheduleCardTip() {}, scheduleTaskById: () => task,
        submitScheduleTask() {}, resetScheduleForm() {}, closeScheduleSnapshotModal() {}, startEditScheduleTask() {},
        loadScheduleTasks() {}, abToast() {},
        fetch: async (url, options) => {
            requests.push({url, body: options.body && JSON.parse(options.body)});
            if (url.endsWith('/pending')) return {ok: true, json: async () => [1, 2].map(id => ({
                workId: String(id), workType: 'illust', reasonCode: 'DOWNLOAD_PATH_ACTION_REQUIRED',
                reasonDetailJson: '{"requiresUserAction":true}'
            }))};
            return {ok: true, json: async () => ({...task, stateVersion: ++task.stateVersion})};
        }
    });
    vm.runInContext(shared, context);
    vm.runInContext(read(alt ? 'pixiv-batch-alt/alt-schedule-actions.js' : 'pixiv-batch/modes/schedule.js'), context);
    return {requests, prompts: () => prompts,
        resume: () => alt ? context.scheduleVerb(task, 'resume') : context.resumeScheduleTask(1)};
}

for (const alt of [false, true]) {
test(`${alt ? '新版' : '普通'}计划恢复先逐作品确认并保存递增版本，再恢复任务`, async () => {
    const h = harness({value: 'TRUNCATE', remember: false}, alt);
    await h.resume();
    assert.equal(h.prompts(), 2);
    const choices = h.requests.filter(request => request.url.endsWith('/pending/resolve'));
    assert.deepEqual(choices.map(request => request.body.expectedStateVersion), [7, 8]);
    assert.deepEqual(choices.map(request => request.body.workId), ['1', '2']);
    assert.equal(h.requests.at(-1).url, '/api/schedule/tasks/1/resume');
});

test(`${alt ? '新版' : '普通'}勾选仅授权下一轮，关闭对话框时计划继续挂起`, async () => {
    const remembered = harness({value: 'DEFAULT_NAME', remember: true}, alt);
    await remembered.resume();
    assert.equal(remembered.prompts(), 1);
    assert.equal(remembered.requests[1].body.rememberForRun, true);
    const waiting = harness(null, alt);
    await waiting.resume();
    assert.equal(waiting.requests.length, 1);
    assert.equal(waiting.requests[0].url, '/api/schedule/tasks/1/pending');
});
}
