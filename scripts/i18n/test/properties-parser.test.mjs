'use strict';
import { test } from 'node:test';
import assert from 'node:assert/strict';

import parser from '../lib/properties-parser.mjs';

function keys(text) {
    return parser.parse(text).entries.map((e) => [e.key, e.value]);
}

test('= / : / 空白三种分隔符', () => {
    assert.deepEqual(keys('a=1\nb:2\nc 3\n'), [['a', '1'], ['b', '2'], ['c', '3']]);
    assert.deepEqual(keys('a = 1\n'), [['a', '1']]);
    assert.deepEqual(keys('a\t=\t1\n'), [['a', '1']]);
});

test('注释与空行被忽略', () => {
    const result = parser.parse('# comment\n\n! also comment\n a=1 \n');
    assert.equal(result.entries.length, 1);
    assert.equal(result.entries[0].key, 'a');
    assert.equal(result.errors.length, 0);
});

test('行续接', () => {
    assert.deepEqual(keys('a=one\\\ntwo\n'), [['a', 'onetwo']]);
    // 偶数个反斜杠不续接：one\ 与 two 是两条逻辑行（Java 语义）
    assert.deepEqual(keys('a=one\\\\\ntwo\n'), [['a', 'one\\'], ['two', '']]);
});

test('转义字符', () => {
    assert.deepEqual(keys('a=tab\\tnew\\nline\\fform\\rreturn\n'), [['a', 'tab\tnew\nline\fform\rreturn']]);
    assert.deepEqual(keys('a=back\\\\slash\n'), [['a', 'back\\slash']]);
});

test('\\uXXXX 转义', () => {
    assert.deepEqual(keys('a=\\u4E2D\\u6587\n'), [['a', '中文']]);
    assert.deepEqual(keys('a=\\u00e9\n'), [['a', 'é']]);
});

test('非法 Unicode escape 报错并保留行号', () => {
    const result = parser.parse('a=\\u12ZZ\nb=1\n');
    assert.equal(result.errors.length, 1);
    assert.match(result.errors[0].message, /invalid \\u escape/);
    assert.equal(result.errors[0].line, 1);
});

test('escaped separator 在 key 中不结束 key', () => {
    assert.deepEqual(keys('a\\:b=1\n'), [['a:b', '1']]);
    assert.deepEqual(keys('a\\=b=2\n'), [['a=b', '2']]);
    assert.deepEqual(keys('a\\ b=3\n'), [['a b', '3']]);
});

test('value 内包含等号与冒号', () => {
    assert.deepEqual(keys('a=x=y:z\n'), [['a', 'x=y:z']]);
});

test('UTF-8 中文与 BOM 剥离', () => {
    assert.deepEqual(keys('\uFEFFa=中文\n'), [['a', '中文']]);
    assert.deepEqual(keys('\uFEFF名字=值\n'), [['名字', '值']]);
});

test('空白分隔后值以 = / : 开头时跳过（Java 语义）', () => {
    assert.deepEqual(keys('a : =value\n'), [['a', '=value']]);
    assert.deepEqual(keys('a : :value\n'), [['a', ':value']]);
    // 非空白分隔时值保留 = / : 开头
    assert.deepEqual(keys('a=:value\n'), [['a', ':value']]);
    assert.deepEqual(keys('a==value\n'), [['a', '=value']]);
});

test('重复 key 检测：报告全部定义行号，不静默覆盖', () => {
    const result = parser.parse('a=1\nb=2\na=3\n');
    assert.equal(result.duplicateKeys.length, 1);
    assert.equal(result.duplicateKeys[0].key, 'a');
    assert.deepEqual(result.duplicateKeys[0].lines, [1, 3]);
    assert.equal(result.entries.length, 3);
});

test('逻辑行与物理行号定位（续接跨行）', () => {
    const result = parser.parse('a=one\\\ntwo\nb=3\n');
    assert.equal(result.entries[0].keyLine, 1);
    assert.deepEqual(result.entries[0].physicalLines, [1, 2]);
    assert.equal(result.entries[1].keyLine, 3);
});

test('CRLF 与 LF 等价', () => {
    assert.deepEqual(keys('a=1\r\nb=2\r\n'), keys('a=1\nb=2\n'));
});

test('canonicalValue：统一换行并去除首尾空白', () => {
    assert.equal(parser.canonicalValue('  x\r\ny  '), 'x\ny');
});
