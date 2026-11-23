'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const resources = path.join(__dirname, '../../main/resources');
const read = file => fs.readFileSync(path.join(resources, file), 'utf8');

function loadModel() {
    const context = {};
    context.window = context;
    vm.runInNewContext(read('static/intro/intro-model.js'), context);
    return context.PixivIntroModel;
}

test('首屏滚动进度在区段两端取 0 和 1，区段不足一屏时直接完成', () => {
    const model = loadModel();
    assert.equal(model.scrollProgress(0, 2800, 900), 0);
    assert.equal(model.scrollProgress(120, 2800, 900), 0);
    assert.equal(model.scrollProgress(-950, 2800, 900), 0.5);
    assert.equal(model.scrollProgress(-1900, 2800, 900), 1);
    assert.equal(model.scrollProgress(-5000, 2800, 900), 1);
    assert.equal(model.scrollProgress(0, 600, 900), 1);
});

test('首屏从标题与散落作品过渡到完整画廊', () => {
    const model = loadModel();
    const start = model.sceneState(0);
    const end = model.sceneState(1);
    assert.deepEqual([start.copy, start.chrome, start.caption], [1, 0, 0]);
    assert.deepEqual([end.copy, end.chrome, end.caption], [0, 1, 1]);
    for (const order of [0, 0.5, 1]) {
        assert.equal(model.looseness(0, order), 1);
        assert.equal(model.looseness(1, order), 0);
    }

    // 归位过程单调，且靠外的作品不会早于靠内的作品归位。
    let previous = 1;
    for (let step = 0; step <= 100; step += 1) {
        const progress = step / 100;
        const inner = model.looseness(progress, 0);
        assert.ok(inner <= previous);
        assert.ok(model.looseness(progress, 1) >= inner);
        previous = inner;
    }
});

test('散落位置逐一分配且不重复，位置不足时返回空值', () => {
    const model = loadModel();
    const points = [[0.9, 0.9], [0.1, 0.1], [0.5, 0.9]];
    const slots = [[0.1, 0.2, 1, 0], [0.8, 0.8, 1, 0], [0.5, 1, 1, 0]];
    assert.deepEqual(Array.from(model.assignSlots(points, slots)), [slots[1], slots[0], slots[2]]);
    assert.deepEqual(Array.from(model.assignSlots(points, slots.slice(0, 2))), [slots[1], slots[0], null]);

    for (const layout of [model.WIDE_SLOTS, model.NARROW_SLOTS]) {
        const grid = layout.map((slot, index) => [(index % 6) / 5, Math.floor(index / 6) / 2]);
        const assigned = model.assignSlots(grid, layout);
        assert.equal(new Set(assigned).size, layout.length);
    }
});

test('标签按 未筛选 → 包含 → 排除 循环，筛选要求包含全部且不含排除', () => {
    const model = loadModel();
    assert.equal(model.nextTagState('ignore'), 'include');
    assert.equal(model.nextTagState('include'), 'exclude');
    assert.equal(model.nextTagState('exclude'), 'ignore');

    const tags = ['landscape', 'night'];
    assert.equal(model.matchesTags(tags, {}), true);
    assert.equal(model.matchesTags(tags, {landscape: 'include', night: 'include', sea: 'ignore'}), true);
    assert.equal(model.matchesTags(tags, {landscape: 'include', sea: 'include'}), false);
    assert.equal(model.matchesTags(tags, {landscape: 'include', night: 'exclude'}), false);
    assert.equal(model.matchesTags(tags, {sea: 'exclude'}), true);
});

// 视口 900、页面可滚动到 5000：首屏起止 0 与 1620，章节 A 高于视口，章节 B 不足一屏，
// 末尾章节只比视口高 10 像素（只是底部留白），其顶部贴近页尾。
const plain = value => JSON.parse(JSON.stringify(value));
function sampleLayout(model) {
    return model.snapLayout([
        {top: 0, height: 0},
        {top: 1620, height: 0},
        {top: 2520, height: 1500, free: true},
        {top: 4020, height: 700, free: true},
        {top: 4990, height: 910, free: true}
    ], 900, 5000);
}

test('吸附停靠点包含首屏起止、章节顶部和长章节底部，长章节内部可自由滚动', () => {
    const model = loadModel();
    const layout = sampleLayout(model);
    assert.deepEqual(plain(layout.stops), [0, 1620, 2520, 3120, 4020, 5000]);
    assert.deepEqual(plain(layout.free), [[2520, 3120]]);
    assert.deepEqual(plain(model.freeRange(layout, 2800)), [2520, 3120]);
    assert.equal(model.freeRange(layout, 1000), null);
    assert.equal(model.hasRoom([2520, 3120], 2800, 1), true);
    assert.equal(model.hasRoom([2520, 3120], 3120, 1), false);
    assert.equal(model.hasRoom([2520, 3120], 2520, -1), false);
});

test('翻页逐个经过停靠点，不会越过汇合后的首屏', () => {
    const model = loadModel();
    const layout = sampleLayout(model);
    assert.equal(model.nextStop(layout, 0, 1), 1620);
    assert.equal(model.nextStop(layout, 1620, 1), 2520);
    assert.equal(model.nextStop(layout, 2800, 1), 3120);
    assert.equal(model.nextStop(layout, 2520, -1), 1620);
    assert.equal(model.nextStop(layout, 5000, 1), null);
    assert.equal(model.nextStop(layout, 0, -1), null);
});

test('原生滚动停下后按方向与行程归位，自由区间和停靠点上保持不动', () => {
    const model = loadModel();
    const layout = sampleLayout(model);
    assert.equal(model.settleStop(layout, 100, 1, 0.12), 0);
    assert.equal(model.settleStop(layout, 400, 1, 0.12), 1620);
    assert.equal(model.settleStop(layout, 1400, -1, 0.12), 0);
    assert.equal(model.settleStop(layout, 1550, -1, 0.12), 1620);
    assert.equal(model.settleStop(layout, 400, 0, 0.12), 0);
    assert.equal(model.settleStop(layout, 2800, 1, 0.12), null);
    assert.equal(model.settleStop(layout, 1620, 1, 0.12), null);
});

test('翻页时长随距离增加并受上下限约束，缓动从 0 平滑走到 1', () => {
    const model = loadModel();
    const screen = model.snapDuration(900, 900);
    assert.ok(model.snapDuration(10, 900) <= screen);
    assert.ok(screen < model.snapDuration(1620, 900));
    assert.ok(model.snapDuration(1e6, 900) >= model.snapDuration(9000, 900));
    assert.equal(model.snapDuration(-900, 900), screen);
    assert.equal(model.snapEase(0), 0);
    assert.equal(model.snapEase(0.5), 0.5);
    assert.equal(model.snapEase(1), 1);
    let previous = 0;
    for (let t = 0.05; t <= 1; t += 0.05) {
        const value = model.snapEase(t);
        assert.ok(value >= previous);
        previous = value;
    }
});

// 文案键 lint：页面里写错的键会静默回退到内置中文，资源里多余的键会白白占用翻译。
function referencedKeys(html, script) {
    const keys = new Set();
    for (const match of html.matchAll(/data-i18n(?:-[a-z-]+)?="([^"]+)"/g)) keys.add(match[1]);
    for (const match of script.matchAll(/\.t\('([^']+)'/g)) keys.add(match[1]);
    return keys;
}

test('文案键 lint 能识别元素、属性与脚本中的引用，并忽略参数属性以外的内容', () => {
    const keys = referencedKeys(
        '<p data-i18n="a.text"></p><p data-i18n-html="a.html"></p><nav data-i18n-aria-label="a.label"></nav>'
            + '<p data-key="a.other" class="data-i18n"></p>',
        "client.t('a.script', fallback); list.sort('a.ignored');"
    );
    assert.deepEqual([...keys].sort(), ['a.html', 'a.label', 'a.script', 'a.text']);
});

test('介绍页引用的文案键与源语言资源一一对应', () => {
    const used = referencedKeys(read('static/intro.html'), read('static/intro/intro.js'));
    const declared = new Set(read('i18n/web/intro.properties').split(/\r?\n/)
        .filter(line => line && !line.startsWith('#') && line.includes('='))
        .map(line => line.slice(0, line.indexOf('=')).trim()));

    assert.deepEqual([...used].filter(key => !declared.has(key)), []);
    assert.deepEqual([...declared].filter(key => !used.has(key)), []);
});
