'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');

function setup(runtime) {
    const context = vm.createContext({Date, document: {addEventListener() {}}, altScheduleSources: () => runtime,
        summaryJoin: items => items.filter(Boolean).join(' · ')});
    context.window = context;
    for (const file of ['alt-core.js', 'alt-schedule-presentation.js', 'alt-schedule.js']) {
        vm.runInContext(readFileSync(resolve(__dirname, '../../main/resources/static/pixiv-batch-alt', file), 'utf8'), context);
    }
    return context;
}

test('计划摘要由来源贡献，缺席或失败使用已保存展示，支持按实际内容查找', () => {
    let active = true, fail = false;
    const context = setup({isAvailable: () => active, descriptor: () => null,
        summary: () => { if (fail) throw Error('withdrawn'); return {description: '搜索「机械师」', kind: 'illust'}; }});
    const task = {id: 1, name: '小特', enabled: true, sourceType: 'owner-source',
        paramsJson: '{"secret":"must-not-render"}', presentation: {summary: '保存的来源'}};
    assert.equal(context.scheduleSourceSummary(task), '搜索「机械师」 · 插画');
    assert.equal(context.scheduleVisibleTasks([task], '机械师', 'all').length, 1);
    fail = true;
    assert.equal(context.scheduleSourceSummary(task), '保存的来源');
    active = false;
    task.presentation = {};
    assert.equal(context.scheduleSourceSummary(task), 'owner-source');
});

test('简单日程自然显示，复杂 Cron 保留原文，下次时间只使用服务器事实', () => {
    const context = setup(null);
    const cron = expression => ({triggerKind: 'cron', cronExpr: expression});
    assert.equal(context.scheduleCadenceLabel(cron('0 0 3 * * *')), '每天 03:00');
    for (const expression of ['0 0 9 * * MON-FRI', '0 */15 * * * *', '30 0 3 * * *', '0 61 24 * * *']) {
        assert.equal(context.scheduleCadenceLabel(cron(expression)), 'Cron 表达式 ' + expression);
    }
    assert.equal(context.scheduleCadenceLabel({triggerKind: 'interval', intervalMinutes: 90}), '每 90 分钟');
    const now = new Date(2026, 11, 31, 23, 58);
    const task = {enabled: true, nextRunTime: new Date(2027, 0, 1, 3, 0).getTime()};
    assert.match(context.scheduleNextLabel(task, now), /明天/);
    assert.match(context.scheduleNextLabel(task, new Date(2027, 0, 1)), /今天/);
    for (const extra of [{enabled: false}, {suspendReason: 'MANUAL'}, {lastStatus: 'PAUSED'}, {nextRunTime: 'invalid'}, {nextRunTime: null}]) {
        assert.equal(context.scheduleNextLabel({...task, ...extra}, now), '');
    }
    assert.equal(context.scheduleStatusLight({enabled: true, suspendReason: 'MANUAL', lastStatus: 'OK'}).text, '已手动暂停');
});
