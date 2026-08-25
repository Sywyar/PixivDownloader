'use strict';
/* 布局 DOM 投影、失败回滚与变更事件契约。 */
const fs = require('fs');
const assert = require('assert');
const {
    SOURCE_PATH,
    HTML_PATH,
    CSS_FILES,
    CSS_PATHS,
    MAIN_SCRIPT_FILES,
    SOURCE,
    HTML,
    CSS,
    STORAGE_KEY,
    ACTION_IDS,
    API_FUNCTIONS,
    MiniEventTarget,
    MiniElement,
    hasOwn,
    buildDocument,
    createHarness,
    rootLayout,
    buttonState,
    businessCallCount,
    actionIdsIn,
    actionOccurrenceCount,
    actionPlacementSnapshot,
    actionPlacementsMatch,
    actionsAreAtOrigins
} = require('./batch-layout-fixture');

let passed = 0;
function ok(label, condition) {
    assert.ok(condition, label);
    passed++;
}
function eq(label, actual, expected) {
    assert.strictEqual(actual, expected, label);
    passed++;
}
function jsonEq(label, actual, expected) {
    assert.strictEqual(JSON.stringify(actual), JSON.stringify(expected), label);
    passed++;
}
function doesNotThrow(label, action) {
    assert.doesNotThrow(action, label);
    passed++;
}

(async function main() {
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench',
            storage: {[STORAGE_KEY]: 'classic'}
        });
        const identities = new Map(ACTION_IDS.map(id => [id, h.dom.actions.get(id)]));
        const pause = h.dom.actions.get('btn-pause');
        pause.focus();
        h.resetBusinessCalls();
        eq('初始 classic 偏好成功应用', h.api.applyStoredLayout(), 'classic');
        eq('初始 classic 更新根 token', rootLayout(h), 'classic');
        jsonEq('初始 classic host 使用声明的旧按钮顺序', actionIdsIn(h.dom.actionHost), ACTION_IDS);
        ok('初始 classic 显示 action host', !h.dom.actionHost.hidden);
        eq('初始 classic 不回写已有偏好', h.storage.setCalls.length, 0);
        eq('投影后恢复动作按钮焦点', h.dom.document.activeElement, pause);
        ok('投影保留 pause.disabled', pause.disabled);
        ok('投影保留按钮数据状态', ACTION_IDS.every(id =>
            identities.get(id).getAttribute('data-state') === id + '-state'));
        ok('投影不触发任何业务函数或状态读取', businessCallCount(h.calls) === 0);

        eq('classic → workbench 切换成功', h.api.toggleLayout(), 'workbench');
        ok('workbench 精确恢复六个 origin', actionsAreAtOrigins(h));
        ok('workbench 隐藏 action host', h.dom.actionHost.hidden);
        ok('恢复后 more-menu open 状态不变', h.dom.moreMenu.open);
        eq('恢复后焦点仍在同一 pause 节点', h.dom.document.activeElement, pause);

        for (let index = 0; index < 10; index++) {
            const expected = index % 2 === 0 ? 'classic' : 'workbench';
            eq('反复切换 #' + (index + 1) + ' 返回预期布局', h.api.toggleLayout(), expected);
            ok('反复切换 #' + (index + 1) + ' 保持动作节点身份', ACTION_IDS.every(id =>
                h.dom.document.getElementById(id) === identities.get(id)));
            ok('反复切换 #' + (index + 1) + ' 每个动作仅出现一次', ACTION_IDS.every(id =>
                actionOccurrenceCount(h.dom.html, identities.get(id)) === 1));
            if (expected === 'classic') {
                jsonEq('反复切换 #' + (index + 1) + ' classic 顺序正确',
                    actionIdsIn(h.dom.actionHost), ACTION_IDS);
            } else {
                ok('反复切换 #' + (index + 1) + ' workbench origin 正确', actionsAreAtOrigins(h));
            }
        }
        ok('偶数次往返最终恢复 workbench origin', actionsAreAtOrigins(h));
        ok('所有动作始终只有唯一父级', ACTION_IDS.every(id => !!identities.get(id).parentNode));
        ok('所有动作最终仍保留 disabled/data 状态', pause.disabled && ACTION_IDS.every(id =>
            identities.get(id).getAttribute('data-state') === id + '-state'));
    }

    // 11) 缺少任一 origin 时预检原子失败，不移动节点、不改根布局、不持久化。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench',
            missingActionOrigin: 'btn-export'
        });
        const placement = actionPlacementSnapshot(h);
        const hostHidden = h.dom.actionHost.hidden;
        eq('缺 origin 的 canonical workbench 也拒绝不完整投影契约', h.api.applyStoredLayout(), null);
        h.api.bindLayoutToggle();
        h.resetBusinessCalls();
        eq('缺 origin 时 toggle 返回 null', h.api.toggleLayout(), null);
        eq('缺 origin 时根布局保持 workbench', rootLayout(h), 'workbench');
        eq('缺 origin 时不写 localStorage', h.storage.setCalls.length, 0);
        ok('缺 origin 时没有任何部分移动', actionPlacementsMatch(placement));
        ok('缺 origin 时 action host 可见状态不变且为空', h.dom.actionHost.hidden === hostHidden
            && actionIdsIn(h.dom.actionHost).length === 0);
        ok('缺 origin 时六个节点仍各出现一次', ACTION_IDS.every(id =>
            actionOccurrenceCount(h.dom.html, h.dom.actions.get(id)) === 1));
        eq('缺 origin 时不触发业务副作用', businessCallCount(h.calls), 0);
    }

    // 12) 浏览器 DOM 操作意外抛错时回滚已移动节点，错误布局不得落根或持久化。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        const placement = actionPlacementSnapshot(h);
        eq('异常模拟前 canonical workbench 投影同步成功', h.api.applyStoredLayout(), 'workbench');
        const pause = h.dom.actions.get('btn-pause');
        pause.focus();
        const appendChild = h.dom.actionHost.appendChild.bind(h.dom.actionHost);
        let appendCalls = 0;
        h.dom.actionHost.appendChild = function (child) {
            appendCalls++;
            if (appendCalls === 3) throw new Error('simulated append failure');
            return appendChild(child);
        };
        h.resetBusinessCalls();
        eq('DOM 中途异常时 applyLayout 返回 null',
            h.api.applyLayout('classic', {persist: true}), null);
        eq('DOM 中途异常时根布局保持 workbench', rootLayout(h), 'workbench');
        eq('DOM 中途异常时不持久化 classic', h.storage.setCalls.length, 0);
        ok('DOM 中途异常回滚全部动作位置', actionPlacementsMatch(placement));
        ok('DOM 中途异常后 host 恢复隐藏且为空', h.dom.actionHost.hidden
            && actionIdsIn(h.dom.actionHost).length === 0);
        eq('DOM 中途异常后恢复原焦点', h.dom.document.activeElement, pause);
        ok('DOM 中途异常后所有节点仍唯一', ACTION_IDS.every(id =>
            actionOccurrenceCount(h.dom.html, h.dom.actions.get(id)) === 1));
        eq('DOM 中途异常不触发业务副作用', businessCallCount(h.calls), 0);
    }

    // 13) pixiv:batch-layout-changed 事件：只在布局真正成功变化后派发；投影失败 /
    //     根属性写入失败 / 重复应用同一布局不派发；previousLayout 表示应用前有效布局（无法识别为 null）。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        h.api.applyStoredLayout();
        h.dom.layoutChangeEvents.length = 0;

        eq('成功变化返回新布局', h.api.applyLayout('classic', {persist: true}), 'classic');
        eq('成功变化后根布局更新', rootLayout(h), 'classic');
        eq('成功变化派发一次事件', h.dom.layoutChangeEvents.length, 1);
        jsonEq('事件 detail.layout 正确', h.dom.layoutChangeEvents[0].layout, 'classic');
        jsonEq('事件 detail.previousLayout 正确', h.dom.layoutChangeEvents[0].previousLayout, 'workbench');

        eq('切换回 workbench', h.api.applyLayout('workbench', {persist: true}), 'workbench');
        eq('再次成功变化派发事件', h.dom.layoutChangeEvents.length, 2);
        jsonEq('回切 previousLayout 为 classic', h.dom.layoutChangeEvents[1].previousLayout, 'classic');

        h.dom.layoutChangeEvents.length = 0;
        eq('重复应用同一布局返回原布局', h.api.applyLayout('workbench', {persist: true}), 'workbench');
        eq('重复应用同一布局不派发', h.dom.layoutChangeEvents.length, 0);

        eq('未知布局归一化到默认布局', h.api.applyLayout('unknown', {persist: true}), 'workbench');
        eq('未知布局归一化后不派发', h.dom.layoutChangeEvents.length, 0);
        eq('未知布局不改根布局', rootLayout(h), 'workbench');
    }

    // 14) 投影失败 / 根属性写入失败 / localStorage 失败时的事件语义。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench',
            missingActionOrigin: 'btn-export'
        });
        const placement = actionPlacementSnapshot(h);
        h.api.applyStoredLayout();
        h.dom.layoutChangeEvents.length = 0;
        eq('缺 origin 导致投影预检失败时 applyLayout 返回 null',
            h.api.applyLayout('classic', {persist: true}), null);
        eq('投影失败不派发事件', h.dom.layoutChangeEvents.length, 0);
        eq('投影失败不改根布局', rootLayout(h), 'workbench');
        ok('投影失败不移动任何节点', actionPlacementsMatch(placement));
    }

    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        h.api.applyStoredLayout();
        const setAttribute = h.dom.html.setAttribute.bind(h.dom.html);
        h.dom.html.setAttribute = function (name, value) {
            if (name === 'data-batch-layout') throw new Error('simulated setAttribute failure');
            return setAttribute(name, value);
        };
        h.dom.layoutChangeEvents.length = 0;
        eq('根属性写入失败时 applyLayout 返回 null', h.api.applyLayout('classic', {persist: true}), null);
        eq('根属性写入失败不派发事件', h.dom.layoutChangeEvents.length, 0);
        eq('根属性写入失败根布局保持 workbench', rootLayout(h), 'workbench');
    }

    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        h.api.applyStoredLayout();
        h.storage.throwOnSet = true;
        h.dom.layoutChangeEvents.length = 0;
        eq('localStorage 写入失败但布局成功时 applyLayout 返回新布局',
            h.api.applyLayout('classic', {persist: true}), 'classic');
        eq('localStorage 写入失败仍派发事件（布局已成功）', h.dom.layoutChangeEvents.length, 1);
        jsonEq('事件语义与持久化无关', h.dom.layoutChangeEvents[0].layout, 'classic');
        eq('localStorage 写入失败根布局已更新', rootLayout(h), 'classic');
    }

    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench'
        });
        h.api.applyStoredLayout();
        h.dom.html.setAttribute('data-batch-layout', 'no-such-layout');
        h.dom.layoutChangeEvents.length = 0;
        eq('无法识别 previousLayout 时仍派发事件', h.api.applyLayout('classic', {persist: true}), 'classic');
        eq('事件数量正确', h.dom.layoutChangeEvents.length, 1);
        jsonEq('无法识别 previousLayout 时为 null', h.dom.layoutChangeEvents[0].previousLayout, null);
    }

    // 15) 事件派发与持久化解耦：persist=false、applyStoredLayout、storage 同步均只在
    //     实际布局变化时派发一次；重复同步同一布局不派发。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        h.api.applyStoredLayout();
        h.dom.layoutChangeEvents.length = 0;

        eq('persist=false 成功变化返回新布局', h.api.applyLayout('classic', {persist: false}), 'classic');
        eq('persist=false 成功变化派发一次事件', h.dom.layoutChangeEvents.length, 1);
        jsonEq('persist=false 事件 detail.layout 正确', h.dom.layoutChangeEvents[0].layout, 'classic');
        jsonEq('persist=false 事件 detail.previousLayout 正确', h.dom.layoutChangeEvents[0].previousLayout, 'workbench');
        eq('persist=false 不写 localStorage', h.storage.setCalls.length, 0);

        h.dom.layoutChangeEvents.length = 0;
        eq('persist=false 重复应用当前布局不派发', h.api.applyLayout('classic', {persist: false}), 'classic');
        eq('persist=false 重复应用事件数为 0', h.dom.layoutChangeEvents.length, 0);
    }

    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench',
            storage: {[STORAGE_KEY]: 'classic'}
        });
        h.dom.layoutChangeEvents.length = 0;
        eq('applyStoredLayout 保存布局与当前不同时派发', h.api.applyStoredLayout(), 'classic');
        eq('applyStoredLayout 派发一次事件', h.dom.layoutChangeEvents.length, 1);
        jsonEq('applyStoredLayout previousLayout 为当前布局', h.dom.layoutChangeEvents[0].previousLayout, 'workbench');

        h.dom.layoutChangeEvents.length = 0;
        eq('applyStoredLayout 再次应用同一布局不派发', h.api.applyStoredLayout(), 'classic');
        eq('applyStoredLayout 重复应用事件数为 0', h.dom.layoutChangeEvents.length, 0);
    }

    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        h.api.bindLayoutToggle();
        h.dom.layoutChangeEvents.length = 0;
        h.dispatchStorage(STORAGE_KEY, 'classic');
        eq('storage 同步外部偏好导致实际变化时派发', h.dom.layoutChangeEvents.length, 1);
        jsonEq('storage 同步 detail.layout 正确', h.dom.layoutChangeEvents[0].layout, 'classic');
        jsonEq('storage 同步 detail.previousLayout 正确', h.dom.layoutChangeEvents[0].previousLayout, 'workbench');

        h.dom.layoutChangeEvents.length = 0;
        h.dispatchStorage(STORAGE_KEY, 'classic');
        eq('重复 storage 同步同一布局不派发', h.dom.layoutChangeEvents.length, 0);
        eq('重复 storage 同步后根布局保持 classic', rootLayout(h), 'classic');
    }

    console.log(`\nbatch-layout-effects.test.js: ${passed} assertions passed ✓`);
})().catch(error => {
    console.error('TEST FAILED:', error && error.stack ? error.stack : error);
    process.exit(1);
});
