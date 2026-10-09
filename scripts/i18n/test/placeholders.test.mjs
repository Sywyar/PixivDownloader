'use strict';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkTranslation, urls } from '../lib/placeholders.mjs';

test('URL 提取排除多语言说明中的句末标点和括号', () => {
    for (const text of [
        '地址 https://example.invalid/v1；选择服务商',
        '位址 https://example.invalid/v1，也可自訂',
        '地址 https://example.invalid/v1（引擎追加路径）',
        'URL は https://example.invalid/v1。続けて設定します',
        'Endpoint: https://example.invalid/v1; choose a provider.',
        'Endpoint (https://example.invalid/v1).',
        '주소: https://example.invalid/v1 (기본값).',
    ]) {
        assert.deepEqual(urls(text), ['https://example.invalid/v1'], text);
    }
});

test('URL 中的查询参数、片段、编码、Unicode 和配对括号保持完整', () => {
    const values = [
        'https://example.invalid/a_(b)?q=x,y;z&next=%2Fdocs#section',
        'https://example.invalid/문서/中文?q=日本語',
        'http://[::1]:8080/api',
        'https://example.invalid/work/{id}',
    ];
    for (const value of values) {
        assert.deepEqual(urls('URL (' + value + ')'), [value]);
    }
});

test('引号中的地址和独立地址不丢弃合法标点', () => {
    const value = 'https://example.invalid/文，書?q=ok;';
    assert.deepEqual(urls(value), [value]);
    assert.deepEqual(urls('<a href="' + value + '">链接</a>'), [value]);
    assert.deepEqual(urls('주소 "' + value + '"입니다.'), [value]);
    assert.deepEqual(urls('URL `' + value + '`'), [value]);
});

test('独立协议前缀不是完整 URL', () => {
    assert.deepEqual(urls('只支持 http:// 或 https://'), []);
    assert.deepEqual(urls('"https://"로 시작해야 합니다'), []);
});

test('翻译检查仍报告地址和出现次数的真实差异', () => {
    const source = '地址 https://example.invalid/v1；选择服务商';
    assert.deepEqual(checkTranslation(source, 'Endpoint: https://example.invalid/v1; choose a provider.').warnings, []);
    for (const translation of [
        'Endpoint: https://other.invalid/v1.',
        'Endpoint: https://example.invalid/v2.',
        'Endpoint: https://example.invalid/v1?q=changed.',
        'Endpoint: https://example.invalid/v1#changed.',
        'Endpoint: https://example.invalid/v1 and https://example.invalid/v1.',
        'No endpoint.',
    ]) {
        assert.ok(checkTranslation(source, translation).warnings.some(w => w.startsWith('URL set differs:')), translation);
    }
    assert.ok(checkTranslation('<a href="https://example.invalid/q?x=1;">源</a>',
        '<a href="https://example.invalid/q?x=1">Target</a>').warnings.some(w => w.startsWith('URL set differs:')));
});
