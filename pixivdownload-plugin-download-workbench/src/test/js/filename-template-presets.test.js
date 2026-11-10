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
    const root = document.createElement('div');
    root.appendChild(input);
    root.appendChild(select);
    input.value = settings.fileNameTemplate || '';
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
        input, select, settings, bind, root,
        button: value => root.querySelector('[data-i18n="batch:template-set.' + value + '"]'),
        status: () => root.querySelector('[role="status"]').textContent,
        choose(value) {
            if (value === 'save' || value === 'remove') {
                root.querySelector('[data-i18n="batch:template-set.' + value + '"]').onclick();
            } else { select.value = value; select.onchange(); }
        },
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
    assert.equal(second.select.value, '0');
    assert.equal(second.button('save').disabled, true);
    assert.equal(second.button('remove').disabled, false);
});

test('空白与损坏条目不进入选项，模板文本不作为 HTML 解析，语言在显示时更新', () => {
    const f = fixture({fileNameTemplates: [null, 5, '', '  ', '<img src=x>', '<img src=x>']});
    assert.equal(f.select.children.length, 2);
    assert.equal(f.select.children[0].disabled, true);
    assert.equal(f.select.children[0].textContent, 'en:template-set.choose');
    assert.equal(f.select.children[1].textContent, '<img src=x>');
    assert.equal(f.select.children[1].children.length, 0);
    f.input.value = '  ';
    f.choose('save');
    assert.equal(f.button('save').disabled, true);
    f.language('updated');
    f.select.onfocus();
    assert.equal(f.select.getAttribute('aria-label'), 'updated:template-set.label');
    assert.equal(f.button('save').textContent, 'updated:template-set.save');
    const empty = fixture({fileNameTemplates: {bad: true}});
    assert.equal(empty.select.children.length, 1);
    assert.equal(empty.select.children[0].textContent, 'en:template-set.empty');
    assert.equal(empty.select.disabled, true);
});

test('存储失败保留输入与原模板集，重新绑定不重复触发，非管理员没有入口', () => {
    const f = fixture({fileNameTemplates: ['saved']});
    f.input.value = 'draft';
    f.fail();
    f.choose('save');
    assert.deepEqual(f.settings.fileNameTemplates, ['saved']);
    assert.equal(f.input.value, 'draft');
    assert.match(f.status(), /template-set.save-failed/);
    f.bind();
    assert.equal(f.root.querySelectorAll('.filename-template-presets').length, 1);
    f.choose('0');
    assert.equal(f.changes(), 1);
    const guest = fixture({}, false);
    assert.equal(guest.select.hidden, true);
    assert.equal(guest.select.disabled, true);
    assert.equal(guest.select.onchange, null);
    assert.equal(guest.root.querySelector('.filename-template-presets'), null);
});

test('移除当前模板只更新模板集，保留输入，保存失败时恢复原集合', () => {
    const f = fixture({fileNameTemplates: ['first', 'second'], fileNameTemplate: 'second'});
    f.choose('1');
    assert.equal(f.button('remove').disabled, false);
    f.choose('remove');
    assert.deepEqual(f.saved().fileNameTemplates, ['first']);
    assert.equal(f.input.value, 'second');
    assert.equal(f.settings.fileNameTemplate, 'second');
    assert.equal(f.changes(), 1);
    f.choose('0');
    f.fail();
    f.choose('remove');
    assert.deepEqual(Array.from(f.settings.fileNameTemplates), ['first']);
    assert.equal(f.input.value, 'first');
    assert.match(f.status(), /template-set.save-failed/);
});

test('编辑草稿立即更新模板选择与按钮，不提前保存，重新绑定释放旧按钮', () => {
    const f = fixture({fileNameTemplates: ['saved'], fileNameTemplate: 'saved'});
    f.input.value = 'draft';
    f.input.dispatchEvent({type: 'input'});
    assert.equal(f.select.value, '');
    assert.equal(f.button('save').disabled, false);
    assert.equal(f.button('remove').disabled, true);
    assert.equal(f.settings.fileNameTemplate, 'saved');
    const oldSave = f.button('save');
    f.bind();
    assert.equal(oldSave.onclick, null);
    f.choose('0');
    assert.equal(f.changes(), 1);
    assert.equal(f.input.value, 'saved');
});
