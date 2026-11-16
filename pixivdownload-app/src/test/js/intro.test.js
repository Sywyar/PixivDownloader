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
