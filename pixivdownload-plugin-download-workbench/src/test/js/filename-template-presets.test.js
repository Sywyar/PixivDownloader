'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

function fixture(settings = {}, enabled = true) {
    const document = {};
    class Element extends MiniElement {
        replaceChildren() { this.children.slice().forEach(child => this.removeChild(child)); }
        setCustomValidity(message) { this.validationMessage = message; }
        reportValidity() { return !this.validationMessage; }
    }
    document.createElement = tag => new Element(tag, document);
    const context = vm.createContext({document, Event: class {
        constructor(type, options) { this.type = type; Object.assign(this, options); }
    }});
    context.window = context;
    const input = document.createElement('input');
    const select = document.createElement('select');
    let persisted;
    let fail = false;
    let language = 'en';
    let changes = 0;
    input.addEventListener('change', () => { settings.fileNameTemplate = input.value; changes++; });
    vm.runInContext(readFileSync(resolve(__dirname,
        '../../main/resources/static/pixiv-batch/filename-template-presets.js'), 'utf8'), context);
    const save = () => {
        if (fail) throw new Error('storage unavailable');
        persisted = JSON.stringify(settings);
    };
    const bind = () => context.PixivFilenameTemplatePresets.bind(input, select, settings, save, key => language + ':' + key, enabled);
    bind();
    return {
        input, select, settings, bind,
        choose(value) { select.value = value; select.onchange(); },
        saved: () => JSON.parse(persisted), changes: () => changes,
        fail() { fail = true; }, language(value) { language = value; }
    };
}

test('模板集保存输入内容、去重、恢复，并通过原有 change 事件替换模板', () => {
    const first = fixture();
    first.input.value = '{author_name}_{artwork_id}';
    first.choose('save');
    first.choose('save');
    assert.deepEqual(first.saved().fileNameTemplates, ['{author_name}_{artwork_id}']);
    const second = fixture(first.saved());
    second.input.value = 'draft';
    second.choose('0');
    assert.equal(second.input.value, '{author_name}_{artwork_id}');
    assert.equal(second.settings.fileNameTemplate, second.input.value);
    assert.equal(second.changes(), 1);
    assert.equal(second.select.value, '');
});

test('空白与损坏条目不进入选项，模板文本不作为 HTML 解析，语言在显示时更新', () => {
    const f = fixture({fileNameTemplates: [null, 5, '', '  ', '<img src=x>', '<img src=x>']});
    assert.equal(f.select.children.length, 3);
    assert.equal(f.select.children[0].hidden, true);
    assert.equal(f.select.children[0].disabled, true);
    assert.equal(f.select.children[0].textContent, '');
    assert.deepEqual(f.select.children.filter(option => !option.hidden).map(option => option.textContent),
        ['<img src=x>', 'en:template-set.save']);
    assert.equal(f.select.children[1].textContent, '<img src=x>');
    assert.equal(f.select.children[1].children.length, 0);
    f.input.value = '  ';
    f.choose('save');
    assert.equal(f.select.children.at(-1).disabled, true);
    f.language('updated');
    f.select.onfocus();
    assert.equal(f.select.getAttribute('aria-label'), 'updated:template-set.label');
    assert.equal(f.select.children.at(-1).textContent, 'updated:template-set.save');
    assert.equal(fixture({fileNameTemplates: {bad: true}}).select.children.length, 2);
});

test('存储失败保留输入与原模板集，重新绑定不重复触发，非管理员没有入口', () => {
    const f = fixture({fileNameTemplates: ['saved']});
    f.input.value = 'draft';
    f.fail();
    f.choose('save');
    assert.deepEqual(f.settings.fileNameTemplates, ['saved']);
    assert.equal(f.input.value, 'draft');
    assert.match(f.select.validationMessage, /template-set.save-failed/);
    f.bind();
    f.choose('0');
    assert.equal(f.changes(), 1);
    const guest = fixture({}, false);
    assert.equal(guest.select.hidden, true);
    assert.equal(guest.select.disabled, true);
    assert.equal(guest.select.onchange, null);
});
