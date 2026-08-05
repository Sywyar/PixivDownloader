'use strict';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { runCheck } from '../check.mjs';
import { runAccept } from '../accept.mjs';
import { runGenerate } from '../generate-static.mjs';
import catalogLib from '../lib/catalog.mjs';

const CATALOG = `{
  "schemaVersion": 1,
  "sourceLocale": "zh-CN",
  "defaultLocale": "zh-CN",
  "fallbackLocale": "en-US",
  "languageCookieName": "pixiv_lang",
  "languageParameterName": "lang",
  "locales": [
    {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh"]},
    {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["en"]},
    {"tag": "ja-JP", "nativeName": "日本語", "resourceSuffix": "ja", "status": "candidate", "direction": "ltr", "aliases": ["ja"]}
  ]
}`;

function makeRepo(files) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-fixture-'));
    const i18nDir = path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'i18n');
    fs.mkdirSync(path.join(i18nDir, 'web'), { recursive: true });
    fs.writeFileSync(path.join(i18nDir, 'locales.json'), CATALOG, 'utf8');
    for (const [rel, content] of Object.entries(files)) {
        const file = path.join(i18nDir, rel);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, content, 'utf8');
    }
    return root;
}

function cleanRepo(root) {
    fs.rmSync(root, { recursive: true, force: true });
}

const OK_ZH = 'greeting=你好 {name}\ntitle=作品标题\n';
const OK_EN = 'greeting=Hello {name}\ntitle=Artwork title\n';

test('完全一致的中英文：check 通过、覆盖率 100%、无 stale（接受后）', () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        const first = runCheck(root);
        assert.equal(first.issues.filter((i) => i.severity === 'error').length, 2); // 未 accept → 两个 key 均 stale
        assert.equal(first.coverage[0].coverage, '0.00%');

        const accepted = runAccept(root, { bootstrap: true });
        assert.equal(accepted.ok, true);
        assert.equal(accepted.updated, 2);

        const report = runCheck(root);
        assert.equal(report.issues.filter((i) => i.severity === 'error').length, 0);
        assert.equal(report.coverage[0].coverage, '100.00%');
    } finally {
        cleanRepo(root);
    }
});

test('missing / extra / empty 检测', () => {
    const root = makeRepo({
        'web/common.properties': 'a=1\nb=2\nc=3\n',
        'web/common_en.properties': 'a=1\nc=\nd=4\n',
    });
    try {
        const report = runCheck(root);
        const errors = report.issues.filter((i) => i.severity === 'error');
        const types = errors.map((i) => i.type).sort();
        assert.ok(types.includes('missing') && types.includes('empty') && types.includes('extra'));
        assert.ok(errors.some((i) => i.key === 'b' && i.type === 'missing'));
        assert.ok(errors.some((i) => i.key === 'c' && i.type === 'empty'));
        assert.ok(errors.some((i) => i.key === 'd' && i.type === 'extra'));
    } finally {
        cleanRepo(root);
    }
});

test('占位符不一致检测（名称 / 数量 / 重复次数）', () => {
    const cases = [
        ['greeting=你好 {name}\n', 'greeting=Hello {other}\n', 'placeholder mismatch'],
        ['greeting=你好 {name}\n', 'greeting=Hello\n', 'placeholder mismatch'],
        ['greeting=你好 {name} {name}\n', 'greeting=Hello {name}\n', 'placeholder mismatch'],
        ['greeting=你好 {name}\n', 'greeting=Hello {name} {extra}\n', 'placeholder mismatch'],
    ];
    for (const [zh, en, expected] of cases) {
        const root = makeRepo({ 'web/common.properties': zh, 'web/common_en.properties': en });
        try {
            const report = runCheck(root);
            const invalid = report.issues.filter((i) => i.type === 'invalid');
            assert.ok(invalid.length > 0, 'expected invalid for: ' + zh);
            assert.match(invalid[0].message, new RegExp(expected));
        } finally {
            cleanRepo(root);
        }
    }
});

test('顺序不同但多重集合一致的占位符不报错', () => {
    const root = makeRepo({
        'web/common.properties': 'msg=你好 {name}，共 {count} 项\n',
        'web/common_en.properties': 'msg=Hello {count} items, {name}!\n',
    });
    try {
        const report = runCheck(root);
        assert.equal(report.issues.filter((i) => i.type === 'invalid').length, 0);
    } finally {
        cleanRepo(root);
    }
});

test('HTML 标签集合差异是 warning（可忽略）；明显闭合损坏是 error', () => {
    const root = makeRepo({
        'web/common.properties': 'a=点击 <b>这里</b> 查看\n',
        'web/common_en.properties': 'a=Click <b>here</b> to view\n',
    });
    try {
        const report = runCheck(root);
        assert.equal(report.issues.filter((i) => i.type === 'invalid').length, 0);
        assert.equal(report.warnings.length, 0); // 标签集合一致且值不同 → 无 warning
    } finally {
        cleanRepo(root);
    }
    const tagDiff = makeRepo({
        'web/common.properties': 'a=点击 <b>这里</b> 查看\n',
        'web/common_en.properties': 'a=Click <i>here</i> to view\n',
    });
    try {
        const report = runCheck(tagDiff);
        assert.ok(report.warnings.some((w) => /HTML tag set differs/.test(w.message)));
    } finally {
        cleanRepo(tagDiff);
    }
    const broken = makeRepo({
        'web/common.properties': 'a=点击 <b>这里</b> 查看\n',
        'web/common_en.properties': 'a=Click <b>here to view\n',
    });
    try {
        const report = runCheck(broken);
        assert.ok(report.issues.some((i) => i.type === 'invalid' && /broken HTML/.test(i.message)));
    } finally {
        cleanRepo(broken);
    }
});

test('stale：源文案变化后标记 stale，accept 更新基线', () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        assert.equal(runAccept(root, { bootstrap: true }).ok, true);
        fs.writeFileSync(path.join(root, 'pixivdownload-app/src/main/resources/i18n/web/common.properties'),
            'greeting=你好啊 {name}\ntitle=作品标题\n', 'utf8');
        const report = runCheck(root);
        assert.ok(report.issues.some((i) => i.type === 'stale' && i.key === 'greeting'));

        // 翻译未修改 → 默认拒绝
        const refused = runAccept(root, { locale: 'en-US' });
        assert.equal(refused.ok, false);
        assert.match(refused.refused[0], /unchanged since last acceptance/);

        // 显式 --allow-unchanged 才允许
        const forced = runAccept(root, { locale: 'en-US', allowUnchanged: true });
        assert.equal(forced.ok, true);
        assert.equal(runCheck(root).issues.filter((i) => i.severity === 'error').length, 0);
    } finally {
        cleanRepo(root);
    }
});

test('candidate：missing/stale 只报告不阻断；已存在翻译的 invalid 仍阻断', () => {
    const zhEn = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        runAccept(zhEn, { bootstrap: true });
        fs.writeFileSync(path.join(zhEn, 'pixivdownload-app/src/main/resources/i18n/web/common_ja.properties'),
            'greeting=こんにちは {name}\n', 'utf8'); // ja 缺 title → candidate missing
        const report = runCheck(zhEn);
        assert.equal(report.issues.filter((i) => i.severity === 'error').length, 0); // missing 不阻断
        assert.ok(report.issues.some((i) => i.locale === 'ja-JP' && i.type === 'missing'));

        // ja 已有翻译但占位符错误 → 阻断
        fs.writeFileSync(path.join(zhEn, 'pixivdownload-app/src/main/resources/i18n/web/common_ja.properties'),
            'greeting=こんにちは {wrong}\ntitle=タイトル\n', 'utf8');
        const report2 = runCheck(zhEn);
        assert.ok(report2.issues.some((i) => i.locale === 'ja-JP' && i.type === 'invalid'));
    } finally {
        cleanRepo(zhEn);
    }
});

test('supported 晋升不足 100% 时失败（bootstrap 拒绝）', () => {
    const root = makeRepo({
        'web/common.properties': 'a=1\nb=2\n',
        'web/common_en.properties': 'a=1\n',
    });
    try {
        const result = runAccept(root, { bootstrap: true });
        assert.equal(result.ok, false);
        assert.ok(result.refused.some((r) => /missing key/.test(r)));
    } finally {
        cleanRepo(root);
    }
});

test('重复 key / 未知语言后缀 / 冲突资源在 check 中失败', () => {
    const dup = makeRepo({ 'web/common.properties': 'a=1\na=2\n', 'web/common_en.properties': 'a=1\n' });
    try {
        const report = runCheck(dup);
        assert.ok(report.issues.some((i) => i.type === 'duplicate-key'));
    } finally {
        cleanRepo(dup);
    }
    const unknown = makeRepo({
        'web/common.properties': 'a=1\n', 'web/common_en.properties': 'a=1\n',
        'web/common_fr.properties': 'a=1\n',
    });
    try {
        const report = runCheck(unknown);
        assert.ok(report.issues.some((i) => i.type === 'unknown-locale-suffix'));
    } finally {
        cleanRepo(unknown);
    }
    const conflict = makeRepo({
        'web/common.properties': 'a=1\n', 'web/common_en.properties': 'a=1\n',
        'web/common_.properties': 'a=1\n',
    });
    try {
        const report = runCheck(conflict);
        // common_.properties 的后缀为空且不是合法语言文件 → 未知后缀错误
        assert.ok(report.issues.some((i) => i.type === 'unknown-locale-suffix'
            && i.file.endsWith('common_.properties')));
    } finally {
        cleanRepo(conflict);
    }
});

test('源 bundle 缺失 / 空源值失败', () => {
    const root = makeRepo({ 'web/common_en.properties': OK_EN });
    try {
        const report = runCheck(root);
        assert.ok(report.issues.some((i) => i.type === 'missing-source-bundle'));
    } finally {
        cleanRepo(root);
    }
    const empty = makeRepo({
        'web/common.properties': 'a=\n', 'web/common_en.properties': 'a=x\n',
    });
    try {
        const report = runCheck(empty);
        assert.ok(report.issues.some((i) => i.type === 'empty'));
    } finally {
        cleanRepo(empty);
    }
});

test('generate-static：只生成核心 app 的 source/supported，插件 bundle 不静态化，确定性输出', () => {
    const root = makeRepo({
        'web/common.properties': 'a=中文\nb=仅中文\n',
        'web/common_en.properties': 'a=English\n',
        'web/common_ja.properties': 'a=日本語\n',
    });
    // 外置插件模块的 bundle：不得静态化（app boot jar 不得携带外置插件 i18n）
    const pluginI18nDir = path.join(root, 'pixivdownload-plugin-gallery', 'src', 'main', 'resources', 'i18n', 'web');
    fs.mkdirSync(pluginI18nDir, { recursive: true });
    fs.writeFileSync(path.join(pluginI18nDir, 'gallery.properties'), 'title=画集\n', 'utf8');
    fs.writeFileSync(path.join(pluginI18nDir, 'gallery_en.properties'), 'title=Gallery\n', 'utf8');
    try {
        const result = runGenerate(root);
        const files = result.files;
        assert.ok(files.includes('meta.json'));
        assert.ok(files.includes('common.zh-CN.json'));
        assert.ok(files.includes('common.en-US.json'));
        assert.ok(!files.some((f) => f.includes('ja-JP')), 'candidate 不发布');
        assert.ok(!files.some((f) => f.includes('gallery')), '外置插件 bundle 不静态化');

        const meta = JSON.parse(fs.readFileSync(path.join(result.outputDir, 'meta.json'), 'utf8'));
        assert.equal(meta.defaultLang, 'zh-CN');
        assert.equal(meta.supportedLocales.length, 2);
        assert.deepEqual(meta.supportedLocales.map((l) => l.status).sort(), ['source', 'supported']);
        assert.deepEqual(meta.supportedNamespaces, ['common']); // 只含核心 app 的 web namespaces

        const enBundle = JSON.parse(fs.readFileSync(path.join(result.outputDir, 'common.en-US.json'), 'utf8'));
        // effective：en 覆盖中文、仅中文的键回退中文
        assert.equal(enBundle.messages.a, 'English');
        assert.equal(enBundle.messages.b, '仅中文');

        const zhBundle = JSON.parse(fs.readFileSync(path.join(result.outputDir, 'common.zh-CN.json'), 'utf8'));
        assert.equal(zhBundle.messages.a, '中文');

        // 确定性：两次生成字节一致
        const first = fs.readFileSync(path.join(result.outputDir, 'common.en-US.json'), 'utf8');
        runGenerate(root);
        const second = fs.readFileSync(path.join(result.outputDir, 'common.en-US.json'), 'utf8');
        assert.equal(first, second);
    } finally {
        cleanRepo(root);
    }
});

test('硬编码守卫：生产代码命中、允许路径不命中', async () => {
    const guard = await import('../lib/hardcoded-guard.mjs');
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        const staticDir = path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static');
        fs.mkdirSync(path.join(staticDir, 'js'), { recursive: true });
        fs.mkdirSync(path.join(staticDir, 'i18n-static'), { recursive: true });
        fs.writeFileSync(path.join(staticDir, 'js', 'page.js'),
            'var defaultLang = "en-US";\nvar supportedLocales = ["zh-CN"];\nLocale.US\n');
        fs.writeFileSync(path.join(staticDir, 'i18n-static', 'meta.json'),
            '{"defaultLang": "zh-CN"}', 'utf8'); // 生成文件允许
        const catalog = catalogLib.load(root);
        const hits = guard.default.scan(root, catalog);
        const jsHits = hits.filter((h) => h.relPath.endsWith('page.js'));
        assert.equal(jsHits.length, 3);
        assert.equal(hits.some((h) => h.relPath.includes('locales.json')), false);
        assert.equal(hits.some((h) => h.relPath.includes('i18n-static')), false);

        // check 集成：命中即失败
        const report = runCheck(root);
        assert.ok(report.issues.some((i) => i.type === 'hardcoded-locale'));
    } finally {
        cleanRepo(root);
    }
});

test('报告排序稳定（两次运行输出一致）', () => {
    const root = makeRepo({
        'web/common.properties': 'a=1\nb=2\nc=3\nd=4\n',
        'web/common_en.properties': 'a=\nc=5\ne=6\n',
    });
    try {
        const first = JSON.stringify(runCheck(root).issues);
        const second = JSON.stringify(runCheck(root).issues);
        assert.equal(first, second);
        // 确定性：按 (locale, module, baseName, key, type) 排序 —— key 序优先于 type
        assert.deepEqual(
            runCheck(root).issues.map((i) => i.type + ':' + i.key),
            ['empty:a', 'missing:b', 'stale:c', 'missing:d', 'extra:e']);
    } finally {
        cleanRepo(root);
    }
});
