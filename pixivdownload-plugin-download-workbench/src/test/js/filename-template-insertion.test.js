'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const context = vm.createContext({Event});
vm.runInContext(readFileSync(resolve(__dirname, '../../main/resources/static/pixiv-batch/filename-template.js'), 'utf8'), context);

function input(value, start, end = start) {
    return {
        value, selectionStart: start, selectionEnd: end, events: [],
        setRangeText(text, from, to) { this.value = this.value.slice(0, from) + text + this.value.slice(to); },
        dispatchEvent(event) {
            this.events.push([event.type, event.bubbles]);
            // 保存回调可能重新设置 value，使浏览器光标移动到末尾。
            this.selectionStart = this.selectionEnd = this.value.length;
        },
        focus() { this.focused = true; },
        setSelectionRange(from, to) { this.selectionStart = from; this.selectionEnd = to; }
    };
}

for (const [label, value, start, end, expected] of [
    ['开头', 'name', 0, 0, '{series_id}name'],
    ['中间', 'prefix_suffix', 7, 7, 'prefix_{series_id}suffix'],
    ['末尾', 'prefix_', 7, 7, 'prefix_{series_id}'],
    ['选区', 'prefix_old_suffix', 7, 10, 'prefix_{series_id}_suffix'],
    ['空输入', '', 0, 0, '{series_id}'],
    ['全选', 'old', 0, 3, '{series_id}'],
    ['Unicode', '猫🐈尾', 3, 3, '猫🐈{series_id}尾']
]) test('插入变量：' + label, () => {
    const field = input(value, start, end);
    context.insertFileNameTemplateVariable(field, '{series_id}');
    assert.equal(field.value, expected);
    assert.equal(field.selectionStart, start + '{series_id}'.length);
    assert.equal(field.selectionEnd, field.selectionStart);
    assert.equal(field.focused, true);
    assert.deepEqual(field.events, [['input', true], ['change', true]]);
    context.insertFileNameTemplateVariable(field, '{artwork_id}');
    assert.equal(field.value, expected.slice(0, start + 11) + '{artwork_id}' + expected.slice(start + 11));
});

for (const property of ['disabled', 'readOnly']) test(property + ' 输入不修改', () => {
    const field = input('original', 0);
    field[property] = true;
    context.insertFileNameTemplateVariable(field, '{series_id}');
    assert.equal(field.value, 'original');
    assert.deepEqual(field.events, []);
});
