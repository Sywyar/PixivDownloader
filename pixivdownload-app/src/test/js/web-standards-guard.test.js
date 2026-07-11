const assert = require('node:assert/strict');
const path = require('node:path');
const test = require('node:test');
const { pathToFileURL } = require('node:url');

const guardUrl = pathToFileURL(path.resolve(__dirname, '../../../../scripts/check-web-standards.mjs'));

test('前端规范守卫只豁免令牌定义处的颜色字面量', async function () {
    const { analyzeSource } = await import(guardUrl.href);
    const result = analyzeSource('sample.css', [
        ':root { --brand: #123456; }',
        '.good { color: var(--brand); }',
        '.bad { color: #fff; background: rgba(0, 0, 0, .5); }'
    ].join('\n'));
    assert.equal(result.hardcodedColors, 2);
});

test('前端规范守卫识别 HTML 内联样式和事件', async function () {
    const { analyzeSource } = await import(guardUrl.href);
    const result = analyzeSource('sample.html', '<button STYLE="color:red" onClick="run()">Run</button>');
    assert.equal(result.inlineStyles, 1);
    assert.equal(result.inlineHandlers, 1);
});

test('前端规范守卫只拦截原生对话框调用', async function () {
    const { analyzeSource } = await import(guardUrl.href);
    const result = analyzeSource('sample.js', [
        'confirm("continue?");',
        'window.prompt("value");',
        'PixivFeedback.confirm({ message: "continue?" });'
    ].join('\n'));
    assert.equal(result.nativeDialogs, 2);
});

test('前端规范基线允许偿还债务但拒绝新增债务', async function () {
    const { compareSnapshot } = await import(guardUrl.href);
    const baseline = { 'existing.css': { hardcodedColors: 2 } };
    assert.deepEqual(compareSnapshot({ 'existing.css': { hardcodedColors: 1 } }, baseline), []);
    assert.equal(compareSnapshot({ 'existing.css': { hardcodedColors: 3 } }, baseline).length, 1);
    assert.equal(compareSnapshot({ 'new.html': { inlineStyles: 1 } }, baseline).length, 1);
});
