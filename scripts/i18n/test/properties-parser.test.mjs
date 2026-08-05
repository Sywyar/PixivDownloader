'use strict';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import parser from '../lib/properties-parser.mjs';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ORACLE_SRC = path.join(SCRIPTS_DIR, 'test', 'java', 'PropertiesOracle.java');
const ORACLE_CLASS_DIR = path.join(SCRIPTS_DIR, '..', '..', 'build', 'classes', 'i18n-oracle');

const FIXTURES = [
    ['a=1\n', [['a', '1']]],
    ['a = 1\n', [['a', '1']]],
    ['a:   1\n', [['a', '1']]],
    ['a=   \n', [['a', '']]],
    ['a:\t\n', [['a', '']]],
    ['a one\n', [['a', 'one']]],
    ['a=hello\\\n    world\n', [['a', 'helloworld']]],
    ['a=hello\\\n\tworld\n', [['a', 'helloworld']]], // 续接行开头的字面 tab 被跳过（与 JVM 一致）
    ['a=hello\\\n\\tworld\n', [['a', 'hello\tworld']]],
    ['a=one\\\\\ntwo\n', [['a', 'one\\'], ['two', '']]],
    ['a=one\\\\\\\ntwo\n', [['a', 'one\\two']]],
    ['a=x=y:z\n', [['a', 'x=y:z']]],
    ['a\\ b=1\n', [['a b', '1']]],
    ['a\\=b=2\n', [['a=b', '2']]],
    ['a\\:b=3\n', [['a:b', '3']]],
    ['a=\\u4E2D\\u6587\n', [['a', '中文']]],
    ['a=hello   \n', [['a', 'hello   ']]],
    ['a=one  two\n', [['a', 'one  two']]],
    ['   a=1\n', [['a', '1']]],
    ['a==value\n', [['a', '=value']]],
    ['a : =value\n', [['a', '=value']]],
    ['a : :value\n', [['a', ':value']]],
    ['a=one\\', [['a', 'one']]],
    ['a=one\\\\', [['a', 'one\\']]],
    ['a=one\\\\\\', [['a', 'one\\']]],
    ['a=1\r\nb=2\r\n', [['a', '1'], ['b', '2']]],
    ['# comment\n! also\n\na=1\n', [['a', '1']]],
    ['a=hello\\\n# not a comment\n', [['a', 'hello# not a comment']]],
    ['a\\ b=1\na\\=b=2\na\\:b=3\n', [['a b', '1'], ['a=b', '2'], ['a:b', '3']]],
    ['a=\\t\\n\\f\\r\n', [['a', '\t\n\f\r']]],
    ['a=back\\\\slash\n', [['a', 'back\\slash']]],
    ['a=\\u00e9\n', [['a', 'é']]],
    ['a=1\na=2\n', [['a', '2']]], // duplicate: JVM last-wins
    ['=1\n', [['', '1']]], // JVM 允许空 key
    ['a=one\\\r\ntwo\n', [['a', 'onetwo']]],
];

// Java 会抛异常的非法 Unicode fixture：Node 必须同样失败。
const INVALID_UNICODE_FIXTURES = [
    'a=\\u12ZZ\n',
    'a=\\u123\n',
    'a=\\u\n',
    'a=x\\u12G4\n',
];

function keys(text) {
    return parser.parse(text).entries.map((e) => [e.key, e.value]);
}

function javaAvailable() {
    try {
        execFileSync('javac', ['-version'], { stdio: 'ignore' });
        execFileSync('java', ['-version'], { stdio: 'ignore' });
        return true;
    } catch (e) {
        return false;
    }
}

function ensureOracleCompiled() {
    if (fs.existsSync(path.join(ORACLE_CLASS_DIR, 'PropertiesOracle.class'))) {
        return;
    }
    fs.mkdirSync(ORACLE_CLASS_DIR, { recursive: true });
    execFileSync('javac', ['-encoding', 'UTF-8', '-d', ORACLE_CLASS_DIR, ORACLE_SRC],
        { stdio: ['ignore', 'pipe', 'pipe'] });
}

/** 运行 Java oracle 解析 fixture 文本，返回 { entries, error }。 */
function runOracle(content) {
    ensureOracleCompiled();
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-oracle-')), 'fixture.properties');
    fs.writeFileSync(file, content, 'utf8');
    try {
        const result = spawnSync('java', ['-cp', ORACLE_CLASS_DIR, 'PropertiesOracle', file],
            { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
        if (result.status !== 0) {
            throw new Error('oracle exited with ' + result.status + ': ' + result.stderr);
        }
        return JSON.parse(result.stdout);
    } finally {
        fs.rmSync(path.dirname(file), { recursive: true, force: true });
    }
}

function lastWins(entries) {
    const map = new Map();
    for (const [key, value] of entries) {
        map.set(key, value);
    }
    return [...map.entries()];
}

test('与 java.util.Properties 差分：全部 fixture 逐条一致（Node 解析器 vs Java oracle）', () => {
    if (!javaAvailable()) {
        test.skip('javac/java 不在 PATH，跳过 JVM 差分（CI 会安装 JDK 17）');
        return;
    }
    let compared = 0;
    for (const [content, expected] of FIXTURES) {
        const nodeEntries = lastWins(keys(content));
        const oracle = runOracle(content);
        assert.equal(oracle.error, null, 'oracle 不应报错: ' + JSON.stringify(content));
        assert.deepEqual(nodeEntries, oracle.entries,
            'Node 与 JVM 不一致 for fixture: ' + JSON.stringify(content)
                + ' expected(JVM)=' + JSON.stringify(oracle.entries) + ' node=' + JSON.stringify(nodeEntries));
        assert.deepEqual(nodeEntries, expected,
            'Node 与手写期望不一致 for fixture: ' + JSON.stringify(content));
        compared += 1;
    }
    assert.equal(compared, FIXTURES.length);
});

test('非法 \\uXXXX fixture：Java oracle 报错，Node 同样失败', () => {
    if (!javaAvailable()) {
        test.skip('javac/java 不在 PATH，跳过 JVM 差分（CI 会安装 JDK 17）');
        return;
    }
    for (const content of INVALID_UNICODE_FIXTURES) {
        const oracle = runOracle(content);
        assert.match(oracle.error || '', /Malformed \\uxxxx encoding/, 'oracle 必须报错: ' + JSON.stringify(content));
        const result = parser.parse(content);
        assert.ok(result.errors.length > 0, 'Node 必须报错: ' + JSON.stringify(content));
        assert.match(result.errors[0].message, /Malformed \\uxxxx encoding/);
    }
});

test('= / : / 空白三种分隔符', () => {
    assert.deepEqual(keys('a=1\nb:2\nc 3\n'), [['a', '1'], ['b', '2'], ['c', '3']]);
    assert.deepEqual(keys('a = 1\n'), [['a', '1']]);
    assert.deepEqual(keys('a\t=\t1\n'), [['a', '1']]);
});

test('注释与空行被忽略', () => {
    const result = parser.parse('# comment\n\n! also comment\n a=1 \n');
    assert.equal(result.entries.length, 1);
    assert.equal(result.entries[0].key, 'a');
    assert.equal(result.entries[0].value, '1 ');
    assert.equal(result.errors.length, 0);
});

test('行续接：奇偶反斜杠、续接行前导空白跳过', () => {
    assert.deepEqual(keys('a=one\\\ntwo\n'), [['a', 'onetwo']]);
    assert.deepEqual(keys('a=one\\\\\ntwo\n'), [['a', 'one\\'], ['two', '']]);
    // 悬空反斜杠（奇数个）按 JVM 语义丢弃
    assert.deepEqual(keys('a=one\\\n'), [['a', 'one']]);
});

test('转义字符与未知转义（反斜杠丢弃、字符保留）', () => {
    assert.deepEqual(keys('a=tab\\tnew\\nline\\fform\\rreturn\n'), [['a', 'tab\tnew\nline\fform\rreturn']]);
    assert.deepEqual(keys('a=back\\\\slash\n'), [['a', 'back\\slash']]);
    assert.deepEqual(keys('a=x\\qy\n'), [['a', 'xqy']]);
    assert.deepEqual(keys('a=\\=\\:\\ \\#\\!\n'), [['a', '=: #!']]);
});

test('\\uXXXX 转义', () => {
    assert.deepEqual(keys('a=\\u4E2D\\u6587\n'), [['a', '中文']]);
    assert.deepEqual(keys('a=\\u00e9\n'), [['a', 'é']]);
});

test('非法 Unicode escape 报错并保留行号', () => {
    const result = parser.parse('a=\\u12ZZ\nb=1\n');
    assert.equal(result.errors.length, 1);
    assert.match(result.errors[0].message, /Malformed \\uxxxx encoding/);
    assert.equal(result.errors[0].line, 1);
});

test('escaped separator 在 key 中不结束 key', () => {
    assert.deepEqual(keys('a\\:b=1\n'), [['a:b', '1']]);
    assert.deepEqual(keys('a\\=b=2\n'), [['a=b', '2']]);
    assert.deepEqual(keys('a\\ b=3\n'), [['a b', '3']]);
});

test('value 内包含等号与冒号；空 key 与 JVM 一致（允许）', () => {
    assert.deepEqual(keys('a=x=y:z\n'), [['a', 'x=y:z']]);
    assert.deepEqual(keys('=1\n'), [['', '1']]);
});

test('空白分隔后值以 = / : 开头时跳过（Java 语义）', () => {
    assert.deepEqual(keys('a : =value\n'), [['a', '=value']]);
    assert.deepEqual(keys('a : :value\n'), [['a', ':value']]);
    assert.deepEqual(keys('a=:value\n'), [['a', ':value']]);
    assert.deepEqual(keys('a==value\n'), [['a', '=value']]);
});

test('尾随普通空格保留（与 JVM 一致）', () => {
    assert.deepEqual(keys('a=hello   \n'), [['a', 'hello   ']]);
    assert.deepEqual(keys('a = 1   \n'), [['a', '1   ']]);
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

test('BOM 与 JVM load(Reader) 一致：成为第一个 key 的前缀', () => {
    // java.util.Properties.load(Reader) 不剥离 BOM；运行时用 normalizeKey 补偿。
    assert.deepEqual(keys('\uFEFFa=1\n'), [['\uFEFFa', '1']]);
});

test('canonicalValue：只统一换行，不 trim（保留前导 / 尾随空白）', () => {
    assert.equal(parser.canonicalValue('  x\r\ny  '), '  x\ny  ');
    assert.equal(parser.canonicalValue('x '), 'x ');
    assert.equal(parser.canonicalValue(' x'), ' x');
    assert.equal(parser.canonicalValue('x\n'), 'x\n');
    assert.notEqual(parser.canonicalValue('x '), parser.canonicalValue('x'));
    assert.notEqual(parser.canonicalValue(' x'), parser.canonicalValue('x'));
});
