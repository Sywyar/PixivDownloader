'use strict';
/* 布局清单、存储归一与切换绑定契约。 */
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
    // 0) 编码守卫：生产脚本与测试文件不得包含 Unicode U+FFFD 替换字符。
    for (const [label, filePath] of [
        ['batch-layout.js', SOURCE_PATH],
        ['batch-layout.test.js', __filename]
    ]) {
        const text = fs.readFileSync(filePath, 'utf8');
        const bytes = fs.readFileSync(filePath);
        ok(label + ' 文本不含 U+FFFD 替换字符', text.indexOf('\uFFFD') === -1);
        ok(label + ' 字节流不含 U+FFFD 替换字符', (() => {
            for (let i = 0; i < bytes.length - 2; i++) {
                if (bytes[i] === 0xEF && bytes[i + 1] === 0xBF && bytes[i + 2] === 0xBD) return false;
            }
            return true;
        })());
    }

    ok('系列下载声明与快捷获取同语义的数据来源 radiogroup',
        HTML.includes('class="series-data-source-control data-source-control"')
        && HTML.includes('id="series-data-source-switcher" role="radiogroup"')
        && HTML.includes('aria-labelledby="series-data-source-label"'));
    const cssAssetPositions = CSS_FILES.map(file => HTML.indexOf(`/pixiv-batch/${file}`));
    ok('下载页按基础、共享组件、导航、搜索、计划、快捷获取、工作区和折叠组件顺序加载样式',
        cssAssetPositions.every(position => position >= 0)
        && cssAssetPositions.every((position, index) => index === 0 || position > cssAssetPositions[index - 1]));
    const scriptAssetPositions = MAIN_SCRIPT_FILES.map(file => HTML.indexOf(`/pixiv-batch/${file}`));
    ok('下载页按模型、动作、视图和 facade 的依赖顺序加载主页面职责脚本',
        scriptAssetPositions.every(position => position >= 0)
        && scriptAssetPositions.every((position, index) => index === 0 || position > scriptAssetPositions[index - 1]));
    ok('数据来源布局样式由通用类复用而非快捷获取专属选择器',
        CSS.includes('.data-source-control {')
        && CSS.includes('.data-source-control .kind-switcher')
        && CSS.includes('.data-source-label {'));
    ok('系列下载提供来源浏览器的中性宿主容器',
        HTML.includes('class="series-source-browser"')
        && HTML.includes('id="series-source-browser" hidden'));
    const seriesBrowserCssStart = CSS.indexOf('.series-source-browser {');
    const seriesBrowserCssEnd = CSS.indexOf('\n.quick-account {', seriesBrowserCssStart);
    const seriesBrowserCss = CSS.slice(seriesBrowserCssStart, seriesBrowserCssEnd);
    ok('来源浏览器样式覆盖标题、状态、列表、项目与分页导航',
        seriesBrowserCssStart >= 0 && seriesBrowserCssEnd > seriesBrowserCssStart
        && seriesBrowserCss.includes('.series-source-browser-title')
        && seriesBrowserCss.includes('.series-source-browser-status')
        && seriesBrowserCss.includes('.series-source-browser-list')
        && seriesBrowserCss.includes('.series-source-browser-item')
        && seriesBrowserCss.includes('.series-source-browser-navigation'));
    ok('来源浏览器颜色只复用主题变量',
        seriesBrowserCss.includes('var(--surface-muted)')
        && seriesBrowserCss.includes('var(--line)')
        && seriesBrowserCss.includes('var(--text)')
        && seriesBrowserCss.includes('var(--muted)')
        && seriesBrowserCss.includes('var(--brand)')
        && !/#[0-9a-f]{3,8}\b|rgba?\s*\(/i.test(seriesBrowserCss));

    // 1) 声明式清单、默认值和首屏应用；i18n ready 前按钮保持隐藏。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'classic'
        });
        ok('暴露 window.PixivBatch.layout', !!h.api);
        API_FUNCTIONS.forEach(name => ok('布局 API 暴露 ' + name, typeof h.api[name] === 'function'));
        ok('加性挂载不覆盖 PixivBatch 既有门面', h.sandbox.PixivBatch.sentinel.preserved === true);
        jsonEq('availableLayouts 按 link DOM 顺序返回', Array.from(h.api.availableLayouts()), ['workbench', 'classic']);
        ok('availableLayouts 返回冻结快照', Object.isFrozen(h.api.availableLayouts()));
        eq('根声明默认布局有效时采用该值', h.api.defaultLayout(), 'workbench');
        eq('无偏好 readStoredLayout 返回声明默认值', h.api.readStoredLayout(), 'workbench');
        eq('无偏好不清理 localStorage', h.storage.removeCalls.length, 0);
        h.api.applyStoredLayout();
        eq('首屏应用声明默认布局', rootLayout(h), 'workbench');
        ok('i18n ready 前按钮仍隐藏', buttonState(h).hidden);
        eq('i18n ready 前不解析按钮文案', h.translationCalls.length, 0);
        eq('模块加载与首屏应用不提前绑定 click', h.dom.button.listenerCount('click'), 0);
        eq('模块加载与首屏应用不提前绑定 storage', h.windowEvents.listenerCount('storage'), 0);
    }

    // 2) token 发现去重且严格；default 失配回退第一项，外部快照不能反向污染。
    {
        const h = createHarness({
            layouts: ['alpha', 'alpha', '', ' Classic ', 'beta-token', 'gamma'],
            defaultLayout: 'missing',
            initialLayout: 'GAMMA'
        });
        jsonEq('清单跳过重复、空白和非 kebab token', Array.from(h.api.availableLayouts()),
            ['alpha', 'beta-token', 'gamma']);
        eq('失配 default 回退第一项', h.api.defaultLayout(), 'alpha');
        eq('陈旧根 token 经 normalize 回退 default', h.api.currentLayout(), 'alpha');
        const invalid = ['', ' ', ' alpha ', 'Alpha', 'ALPHA', 'unknown', null, undefined,
            true, false, 1, {}, [], new String('alpha')];
        invalid.forEach((value, index) => {
            eq('非法布局值 #' + index + ' 回退 default', h.api.normalizeLayout(value), 'alpha');
        });
        eq('精确可用 token 合法', h.api.normalizeLayout('gamma'), 'gamma');
        const snapshot = h.api.availableLayouts();
        doesNotThrow('外部尝试修改冻结快照不影响控制器', () => {
            try { snapshot.pop(); } catch (_) { /* frozen snapshot */ }
        });
        jsonEq('重新读取清单不受外部快照修改影响', Array.from(h.api.availableLayouts()),
            ['alpha', 'beta-token', 'gamma']);
    }

    // 3) 多布局按 DOM 顺序循环；按钮目标、持久化、语言刷新与幂等绑定一致。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        h.api.bindLayoutToggle();
        h.api.bindLayoutToggle();
        h.api.bindLayoutToggle();
        let state = buttonState(h);
        ok('多布局绑定后显示按钮', !state.hidden && !state.disabled);
        eq('按钮 data-layout 记录当前布局', state.layout, 'workbench');
        eq('按钮 data-layout-target 记录下一布局', state.target, 'classic');
        eq('按钮 text/title/aria 使用目标动作文案', state.text, '切换到经典布局');
        eq('按钮 label i18n key 指向目标', state.textKey, 'layout.switch-to-classic');
        eq('按钮 title i18n key 指向目标', state.titleKey, 'layout.switch-to-classic');
        eq('按钮 aria i18n key 指向目标', state.ariaKey, 'layout.switch-to-classic');
        eq('重复 bind 只有一个 click 监听', h.dom.button.listenerCount('click'), 1);
        eq('重复 bind 只有一个 storage 监听', h.windowEvents.listenerCount('storage'), 1);

        const button = state.button;
        const label = state.label;
        const bodyChildren = h.dom.body.children.slice();
        h.dom.button.focus();
        h.resetBusinessCalls();
        h.dom.button.click();
        state = buttonState(h);
        eq('点击从 workbench 切到 classic', rootLayout(h), 'classic');
        eq('点击持久化下一布局', h.storage.values.get(STORAGE_KEY), 'classic');
        eq('一次点击只写一次 storage', h.storage.setCalls.length, 1);
        eq('classic 的下一目标是 workbench', state.target, 'workbench');
        eq('classic 状态显示 workbench 动作文案', state.text, '切换到工作台布局');
        ok('点击不替换按钮与标签节点', state.button === button && state.label === label);
        ok('点击不替换页面顶层 DOM 子节点', h.dom.body.children.length === bodyChildren.length
            && h.dom.body.children.every((node, index) => node === bodyChildren[index]));
        eq('点击保持按钮焦点', h.dom.document.activeElement, button);
        eq('点击不改根节点哨兵属性', h.dom.html.getAttribute('data-sentinel'), 'keep');
        eq('点击不触发 reload/fetch/业务函数或业务状态读取', businessCallCount(h.calls), 0);

        h.messages['layout.switch-to-classic'] = 'Switch to classic';
        h.messages['layout.switch-to-workbench'] = 'Switch to workbench';
        h.api.refreshLayoutToggle();
        eq('语言刷新从资源重新派生当前目标文案', buttonState(h).text, 'Switch to workbench');
        h.dom.button.click();
        eq('第二次点击按清单循环回 workbench', rootLayout(h), 'workbench');
        eq('循环后目标回到 classic', buttonState(h).target, 'classic');
    }

    // 4) 三布局证明控制器没有二元 token 分支。
    {
        const h = createHarness({
            layouts: ['alpha', 'beta', 'gamma'],
            defaultLayout: 'alpha',
            initialLayout: 'alpha',
            messages: {
                'layout.switch-to-alpha': 'to alpha',
                'layout.switch-to-beta': 'to beta',
                'layout.switch-to-gamma': 'to gamma'
            }
        });
        h.api.bindLayoutToggle();
        eq('三布局初始目标取 DOM 下一项', buttonState(h).target, 'beta');
        h.dom.button.click();
        eq('三布局 alpha → beta', rootLayout(h), 'beta');
        eq('beta 的目标是 gamma', buttonState(h).target, 'gamma');
        h.dom.button.click();
        eq('三布局 beta → gamma', rootLayout(h), 'gamma');
        eq('gamma 的目标循环到 alpha', buttonState(h).target, 'alpha');
        h.dom.button.click();
        eq('三布局 gamma → alpha', rootLayout(h), 'alpha');
        eq('三次切换各持久化一次', h.storage.setCalls.length, 3);
    }

    // 5) 仅 workbench：裁撤偏好回退并清理，按钮无死 click，storage 仍归一。
    {
        const h = createHarness({
            layouts: ['workbench'],
            defaultLayout: 'workbench',
            initialLayout: 'classic',
            storage: {[STORAGE_KEY]: 'classic'}
        });
        eq('单 workbench 读取已裁撤 classic 回退 workbench', h.api.readStoredLayout(), 'workbench');
        eq('单 workbench 尝试清理 stale 偏好', h.storage.removeCalls.length, 1);
        h.storage.seed(STORAGE_KEY, 'classic');
        h.api.applyStoredLayout();
        eq('单 workbench 首屏根布局归一', rootLayout(h), 'workbench');
        ok('单 workbench 六个动作恢复到各自 origin', actionsAreAtOrigins(h));
        ok('单 workbench 投影 host 保持隐藏', h.dom.actionHost.hidden);
        h.api.bindLayoutToggle();
        h.api.bindLayoutToggle();
        ok('单 workbench 按钮 hidden 且 disabled', buttonState(h).hidden && buttonState(h).disabled);
        eq('单 workbench 不解析不存在的目标 i18n', h.translationCalls.length, 0);
        eq('单 workbench 不绑定 click', h.dom.button.listenerCount('click'), 0);
        eq('单 workbench 幂等绑定一个 storage listener', h.windowEvents.listenerCount('storage'), 1);
        const setBefore = h.storage.setCalls.length;
        const removeBefore = h.storage.removeCalls.length;
        h.resetBusinessCalls();
        eq('单 workbench 显式 toggle 返回唯一布局', h.api.toggleLayout(), 'workbench');
        h.dom.button.click();
        eq('单 workbench toggle/click 不改根布局', rootLayout(h), 'workbench');
        eq('单 workbench toggle/click 不写 storage', h.storage.setCalls.length, setBefore);
        eq('单 workbench toggle/click 不触业务', businessCallCount(h.calls), 0);
        h.dom.html.setAttribute('data-batch-layout', 'classic');
        h.dispatchStorage(STORAGE_KEY, 'classic');
        eq('单 workbench storage 已裁撤 token 回退唯一布局', rootLayout(h), 'workbench');
        h.dom.html.setAttribute('data-batch-layout', 'classic');
        h.dispatchStorage(STORAGE_KEY, null);
        eq('单 workbench storage null 回退唯一布局', rootLayout(h), 'workbench');
        eq('storage 同步不写回或清理 storage', h.storage.setCalls.length, setBefore);
        eq('storage 同步不额外清理 stale key', h.storage.removeCalls.length, removeBefore);
    }

    // 6) 仅 classic：与单 workbench 对称，旧 workbench 偏好和事件安全回退。
    {
        const h = createHarness({
            layouts: ['classic'],
            defaultLayout: 'classic',
            initialLayout: 'workbench',
            storage: {[STORAGE_KEY]: 'workbench'}
        });
        h.api.applyStoredLayout();
        eq('单 classic 清理旧 workbench 偏好', h.storage.removeCalls.length, 1);
        eq('单 classic 应用唯一布局', rootLayout(h), 'classic');
        jsonEq('单 classic 按声明顺序投影六个动作', actionIdsIn(h.dom.actionHost), ACTION_IDS);
        ok('单 classic 六个动作均只有 host 一个父级', ACTION_IDS.every(id =>
            h.dom.actions.get(id).parentNode === h.dom.actionHost));
        ok('单 classic 保留暂停按钮 disabled 状态', h.dom.actions.get('btn-pause').disabled);
        h.api.bindLayoutToggle();
        ok('单 classic 按钮 hidden 且 disabled', buttonState(h).hidden && buttonState(h).disabled);
        eq('单 classic 不绑定 click', h.dom.button.listenerCount('click'), 0);
        eq('单 classic 仍绑定 storage', h.windowEvents.listenerCount('storage'), 1);
        const setBefore = h.storage.setCalls.length;
        eq('单 classic 显式 toggle 返回唯一布局', h.api.toggleLayout(), 'classic');
        h.dom.html.setAttribute('data-batch-layout', 'workbench');
        h.dispatchStorage(STORAGE_KEY, 'workbench');
        eq('单 classic storage 已裁撤 token 回退 classic', rootLayout(h), 'classic');
        h.dom.html.setAttribute('data-batch-layout', 'workbench');
        h.dispatchStorage(STORAGE_KEY, null);
        eq('单 classic storage null 回退 classic', rootLayout(h), 'classic');
        eq('单 classic toggle/storage 不写 storage', h.storage.setCalls.length, setBefore);
    }

    // 7) 零布局闭环：所有读写 API 返回 null，不改根属性，不接触 storage。
    {
        const h = createHarness({
            layouts: [],
            defaultLayout: 'workbench',
            initialLayout: 'legacy-layout',
            storage: {[STORAGE_KEY]: 'classic'}
        });
        jsonEq('零布局 availableLayouts 为空', Array.from(h.api.availableLayouts()), []);
        eq('零布局 defaultLayout 返回 null', h.api.defaultLayout(), null);
        eq('零布局 normalizeLayout 返回 null', h.api.normalizeLayout('workbench'), null);
        eq('零布局 currentLayout 返回 null', h.api.currentLayout(), null);
        eq('零布局 readStoredLayout 返回 null', h.api.readStoredLayout(), null);
        eq('零布局读取 API 不访问 storage', h.storage.accessCount(), 0);
        eq('零布局 applyStoredLayout 返回 null', h.api.applyStoredLayout(), null);
        eq('零布局 applyLayout 返回 null', h.api.applyLayout('workbench', {persist: true}), null);
        eq('零布局 toggleLayout 返回 null', h.api.toggleLayout(), null);
        h.api.bindLayoutToggle();
        h.api.bindLayoutToggle();
        h.dispatchStorage(STORAGE_KEY, null);
        h.dispatchStorage(STORAGE_KEY, 'classic');
        eq('零布局所有操作保留原根属性', rootLayout(h), 'legacy-layout');
        eq('零布局所有操作不访问 storage', h.storage.accessCount(), 0);
        ok('零布局不移动任何动作节点', actionsAreAtOrigins(h));
        ok('零布局 host 保持空且由基础 CSS 控制可见性', actionIdsIn(h.dom.actionHost).length === 0);
        ok('零布局按钮保持 hidden 且 disabled', buttonState(h).hidden && buttonState(h).disabled);
        eq('零布局无 click 监听', h.dom.button.listenerCount('click'), 0);
        eq('零布局幂等绑定一个 no-op storage 监听', h.windowEvents.listenerCount('storage'), 1);
        ok('零布局未写入 null/undefined 属性', !JSON.stringify(h.dom.button.attributes).includes('undefined')
            && !JSON.stringify(h.dom.button.attributes).includes('null'));
        ok('零布局未写入 null/undefined storage', !Array.from(h.storage.values.values())
            .some(value => value === 'null' || value === 'undefined'));
    }

    // 8) stale 偏好、storage null/非法值和 get/set/remove 异常全部安全降级。
    {
        const stale = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'classic',
            initialLayout: 'workbench',
            storage: {[STORAGE_KEY]: 'removed-layout'}
        });
        stale.api.applyStoredLayout();
        eq('stale 初始偏好回退声明 default', rootLayout(stale), 'classic');
        eq('stale 初始偏好被清理', stale.storage.removeCalls.length, 1);

        const removeFailure = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'classic',
            storage: {[STORAGE_KEY]: 'removed-layout'},
            throwOnRemove: true
        });
        doesNotThrow('removeItem 抛错不传播', () => removeFailure.api.applyStoredLayout());
        eq('removeItem 抛错仍应用 default', rootLayout(removeFailure), 'workbench');
        eq('removeItem 抛错仍尝试一次清理', removeFailure.storage.removeCalls.length, 1);

        const getFailure = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'classic',
            initialLayout: 'workbench',
            throwOnGet: true
        });
        doesNotThrow('getItem 抛错不传播', () => getFailure.api.applyStoredLayout());
        eq('getItem 抛错应用 default', rootLayout(getFailure), 'classic');

        const setFailure = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench',
            throwOnSet: true
        });
        doesNotThrow('setItem 抛错不传播', () => setFailure.api.applyLayout('classic', {persist: true}));
        eq('setItem 抛错仍即时应用布局', rootLayout(setFailure), 'classic');

        const storageEvent = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'classic'
        });
        storageEvent.api.bindLayoutToggle();
        storageEvent.dispatchStorage('other-key', 'classic');
        eq('storage 忽略其它 key', rootLayout(storageEvent), 'classic');
        storageEvent.dispatchStorage(STORAGE_KEY, null);
        eq('storage null 应用 default', rootLayout(storageEvent), 'workbench');
        ok('storage null 同步恢复动作 origin', actionsAreAtOrigins(storageEvent));
        storageEvent.api.applyLayout('classic', {persist: false});
        jsonEq('apply classic 同步动作投影', actionIdsIn(storageEvent.dom.actionHost), ACTION_IDS);
        storageEvent.dispatchStorage(STORAGE_KEY, ' Classic ');
        eq('storage 空白/大小写非法值应用 default', rootLayout(storageEvent), 'workbench');
        ok('storage 非法值同步恢复动作 origin', actionsAreAtOrigins(storageEvent));
        storageEvent.api.applyLayout('classic', {persist: false});
        storageEvent.dispatchStorage(STORAGE_KEY, 'removed-layout');
        eq('storage 已移除 token 应用 default', rootLayout(storageEvent), 'workbench');
        storageEvent.dispatchStorage(STORAGE_KEY, 'classic');
        eq('storage 合法 token 正常同步', rootLayout(storageEvent), 'classic');
        jsonEq('storage 合法 classic 同步动作投影', actionIdsIn(storageEvent.dom.actionHost), ACTION_IDS);
        eq('storage 事件从不 setItem', storageEvent.storage.setCalls.length, 0);
        eq('storage 事件从不 removeItem', storageEvent.storage.removeCalls.length, 0);
    }

    // 9) 从多布局退化到单/零布局时旧 click 被移除，storage listener 保持幂等 no-op。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        h.api.bindLayoutToggle();
        eq('多布局初始有 click', h.dom.button.listenerCount('click'), 1);
        eq('多布局初始有 storage', h.windowEvents.listenerCount('storage'), 1);
        h.dom.setLayouts(['workbench']);
        h.api.refreshLayoutToggle();
        eq('退化为单布局移除 click', h.dom.button.listenerCount('click'), 0);
        eq('退化为单布局保留 storage', h.windowEvents.listenerCount('storage'), 1);
        const setBefore = h.storage.setCalls.length;
        h.dom.button.click();
        eq('退化后的旧按钮 click 无副作用', h.storage.setCalls.length, setBefore);
        h.dom.setLayouts([]);
        h.api.refreshLayoutToggle();
        eq('退化为零布局保留一个 no-op storage', h.windowEvents.listenerCount('storage'), 1);
        const accessesBefore = h.storage.accessCount();
        h.dispatchStorage(STORAGE_KEY, null);
        eq('零布局 storage handler 不读写 storage', h.storage.accessCount(), accessesBefore);
        eq('零布局 storage handler 不改根属性', rootLayout(h), 'workbench');
        ok('退化为零布局按钮保持隐藏', buttonState(h).hidden);
    }

    // 10) 初始 classic 与反复往返只重排同一批动作节点，并完整保留节点状态。
    console.log(`\nbatch-layout.test.js: ${passed} assertions passed ✓`);
})().catch(error => {
    console.error('TEST FAILED:', error && error.stack ? error.stack : error);
    process.exit(1);
});
