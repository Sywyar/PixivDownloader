const assert = require('node:assert/strict');
const path = require('node:path');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const { pathToFileURL } = require('node:url');

const guardUrl = pathToFileURL(path.resolve(__dirname, '../../../../scripts/check-web-standards.mjs'));

test('前端规范扫描插件 web 目录中的 ES 模块并排除 vendor', async function (context) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'page-web-standards-'));
    context.after(() => fs.rmSync(root, {recursive: true, force: true}));
    const web = path.join(root, 'pixivdownload-example/src/main/resources/web');
    fs.mkdirSync(path.join(web, 'vendor'), {recursive: true});
    fs.writeFileSync(path.join(web, 'editor.mjs'), 'window.confirm("x");', 'utf8');
    fs.writeFileSync(path.join(web, 'vendor/engine.mjs'), 'window.confirm("x");', 'utf8');
    const {collectSnapshot} = await import(guardUrl.href);
    const snapshot = collectSnapshot(root);
    assert.equal(Object.keys(snapshot).length, 1);
    assert.equal(snapshot['pixivdownload-example/src/main/resources/web/editor.mjs'].nativeDialogs, 1);
});

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

test('前端规范守卫识别 JavaScript 模板中的内联事件', async function () {
    const { analyzeSource } = await import(guardUrl.href);
    const result = analyzeSource('sample.js', [
        'root.innerHTML = `<button onclick="run()">Run</button>`;',
        'button.onclick = run;'
    ].join('\n'));
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

test('前端规范守卫识别右侧为通配的兄弟组合器', async function () {
    const { analyzeSource } = await import(guardUrl.href);
    const flagged = analyzeSource('sample.css', [
        '.hint > * + * { margin-top: .5rem; }',
        '.a~*, .list > li + * { color: inherit; }',
        '.nested { color: inherit; & + * { margin: 0; } }'
    ].join('\n'));
    assert.equal(flagged.universalSiblingSelectors, 4);

    const allowed = analyzeSource('sample.css', [
        '/* .commented + * {} */',
        '.hint > :not(:first-child) { margin-top: .5rem; }',
        '.a + .b, .a ~ [data-x] { color: inherit; }',
        'li:nth-child(2n + 1), li:nth-last-of-type(3n+1) { color: inherit; }',
        '[class~="x"], [href*="pixiv"] + .y { color: inherit; }',
        '.size { width: calc(100% - 2 * 1rem); }',
        '.label::before { content: "+ *"; }',
        '@media (min-width: 10px) { .a + .b { color: inherit; } }'
    ].join('\n'));
    assert.equal(allowed.universalSiblingSelectors, 0);
    assert.equal(analyzeSource('sample.js', 'const s = ".a + * {}";').universalSiblingSelectors, 0);
});

test('前端规范基线允许偿还债务但拒绝新增债务', async function () {
    const { compareSnapshot } = await import(guardUrl.href);
    const baseline = { 'existing.css': { hardcodedColors: 2 } };
    assert.deepEqual(compareSnapshot({ 'existing.css': { hardcodedColors: 1 } }, baseline), []);
    assert.equal(compareSnapshot({ 'existing.css': { hardcodedColors: 3 } }, baseline).length, 1);
    assert.equal(compareSnapshot({ 'new.html': { inlineStyles: 1 } }, baseline).length, 1);
});
