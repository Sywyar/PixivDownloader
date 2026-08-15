'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const REPO_ROOT = path.join(__dirname, '..', '..', '..', '..');
const STATIC_ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const RENDERER = fs.readFileSync(path.join(STATIC_ROOT, 'pixiv-novel', 'pixiv-novel-render.js'), 'utf8');
const LOCAL_USERSCRIPT = fs.readFileSync(
    path.join(REPO_ROOT, 'Pixiv 单作品图片下载器(Local Download).user.js'), 'utf8');

const MARKUP = [
    '[[jumpuri:安全 HTTPS > https://example.com/path?a=1&b=2]]',
    '[[jumpuri:安全 HTTP > http://example.org/path]]',
    '[[jumpuri:脚本 > javascript:alert(1)]]',
    '[[jumpuri:数据 > data:text/html,x]]',
    '[[jumpuri:文件 > file:///tmp/private]]',
    '[[jumpuri:VBScript > vbscript:msgbox(1)]]',
    '[[jumpuri:邮件 > mailto:user@example.com]]',
    '[[jumpuri:自定义 > custom://example.com/path]]',
    '[[jumpuri:相对 > /relative/path]]',
    '[[jumpuri:凭据 > https://user@example.com/path]]',
    '[[jumpuri:畸形 > https://exa mple.com/path]]',
    '[[jumpuri:控制字符 > https://exa\tmple.com/path]]'
].join('\n');

const BLOCKED_LABELS = ['脚本', '数据', '文件', 'VBScript', '邮件', '自定义', '相对', '凭据', '畸形', '控制字符'];
const BLOCKED_VALUES = [
    'javascript:', 'data:text', 'file:///', 'vbscript:', 'mailto:', 'custom://',
    '/relative/path', 'user@example.com', 'exa mple.com', 'exa\tmple.com'
];

function assertAllowlist(html) {
    assert.equal((html.match(/<a href=/g) || []).length, 2);
    assert.match(html, /href="https:\/\/example\.com\/path\?a=1&amp;b=2"/);
    assert.match(html, /href="http:\/\/example\.org\/path"/);
    BLOCKED_LABELS.forEach(label => assert.ok(html.includes(label), `应保留普通文本：${label}`));
    BLOCKED_VALUES.forEach(value => assert.ok(!html.includes(value), `不得输出不安全地址：${value}`));
}

test('小说阅读页只链接无凭据的 HTTP(S) 绝对地址', () => {
    const sandbox = {URL};
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(RENDERER, sandbox);

    assertAllowlist(sandbox.PixivNovelRender.render(MARKUP));
});

test('独立小说下载脚本使用相同的外链白名单', () => {
    const start = LOCAL_USERSCRIPT.indexOf('function renderNovelPagesHtml');
    const end = LOCAL_USERSCRIPT.indexOf('async function downloadNovel', start);
    assert.ok(start >= 0 && end > start, '应能定位独立脚本的小说正文渲染器');

    const sandbox = {
        URL,
        escapeXml(value) {
            return String(value == null ? '' : value).replace(/[&<>"']/g, char => ({
                '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
            }[char]));
        }
    };
    vm.createContext(sandbox);
    vm.runInContext(LOCAL_USERSCRIPT.slice(start, end), sandbox);

    assertAllowlist(sandbox.renderNovelPagesHtml(MARKUP, {}).join(''));
});
