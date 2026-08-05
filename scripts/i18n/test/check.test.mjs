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
import staleLock from '../lib/stale-lock.mjs';

const CATALOG = `{
  "schemaVersion": 1,
  "sourceLocale": "zh-CN",
  "defaultLocale": "en-US",
  "fallbackLocale": "en-US",
  "languageCookieName": "pixiv_lang",
  "languageParameterName": "lang",
  "locales": [
    {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh", "zh-Hans"]},
    {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["en"]},
    {"tag": "ja-JP", "nativeName": "日本語", "resourceSuffix": "ja", "status": "candidate", "direction": "ltr", "aliases": ["ja"]},
    {"tag": "es-ES", "nativeName": "Español", "resourceSuffix": "es", "status": "disabled", "direction": "ltr", "aliases": ["es"]}
  ]
}`;

const APP_I18N = path.join('pixivdownload-app', 'src', 'main', 'resources', 'i18n');
const STATIC_DIR = path.join('pixivdownload-app', 'src', 'main', 'resources', 'static', 'i18n-static');

function makeRepo(files) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-fixture-'));
    const i18nDir = path.join(root, APP_I18N);
    fs.mkdirSync(path.join(i18nDir, 'web'), { recursive: true });
    fs.writeFileSync(path.join(i18nDir, 'locales.json'), CATALOG, 'utf8');
    for (const [rel, content] of Object.entries(files)) {
        const file = path.join(i18nDir, rel);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, content, 'utf8');
    }
    // 静态资源同步是统一检查契约的一部分：fixture 默认生成并提交预期的静态文件
    runGenerate(root);
    return root;
}

function cleanRepo(root) {
    fs.rmSync(root, { recursive: true, force: true });
}

function errorsOf(report, type, locale) {
    return report.issues.filter((i) => i.type === type && (!locale || i.locale === locale));
}

function coverageRow(report, locale, baseName) {
    return report.coverage.find((c) => c.locale === locale && c.baseName === baseName);
}

const OK_ZH = 'greeting=你好 {name}\ntitle=作品标题\n';
const OK_EN = 'greeting=Hello {name}\ntitle=Artwork title\n';

test('完全一致的中英文：check 通过、覆盖率 100%、无 stale（接受后）', () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        const first = runCheck(root);
        // 未 accept → 两个 key 均为 new-unaccepted（error）
        assert.equal(first.issues.filter((i) => i.severity === 'error').length, 2);
        assert.ok(errorsOf(first, 'new-unaccepted', 'en-US').length === 2);
        assert.equal(first.coverage[0].coverage, '0.00%');

        const accepted = runAccept(root, { bootstrap: true });
        assert.equal(accepted.ok, true);
        assert.equal(accepted.updated, 2);

        const report = runCheck(root);
        assert.equal(report.issues.filter((i) => i.severity === 'error').length, 0);
        assert.equal(report.coverage[0].coverage, '100.00%');
        assert.equal(report.coverage[0].translated, 2);
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
        assert.equal(report.warnings.length, 0);
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

test('时序漏洞状态机：S1/T1 → T2 重审 → 源变拒绝 → 双变通过', () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        // S1/T1 被接受
        assert.equal(runAccept(root, { bootstrap: true }).ok, true);

        // 英文从 T1 改成 T2，中文仍 S1 → translation-unaccepted
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common_en.properties'),
            'greeting=Hello there {name}\ntitle=Artwork title\n', 'utf8');
        runGenerate(root);
        let report = runCheck(root);
        assert.ok(errorsOf(report, 'translation-unaccepted', 'en-US').length === 1);
        assert.equal(errorsOf(report, 'stale', 'en-US').length, 0);

        // accept 后锁更新为 S1/T2
        assert.equal(runAccept(root, { locale: 'en-US' }).ok, true);
        assert.equal(runCheck(root).issues.filter((i) => i.severity === 'error').length, 0);

        // 中文改为 S2，英文仍 T2 → accept 必须按「源变、翻译未变」拒绝
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common.properties'),
            'greeting=你好呀 {name}\ntitle=作品标题\n', 'utf8');
        runGenerate(root);
        report = runCheck(root);
        assert.ok(errorsOf(report, 'stale', 'en-US').length === 1);
        assert.equal(errorsOf(report, 'translation-unaccepted', 'en-US').length, 0);
        const refused = runAccept(root, { locale: 'en-US' });
        assert.equal(refused.ok, false);
        assert.match(refused.refused[0], /source changed but translation unchanged/);

        // 显式 --allow-unchanged 允许确认
        const forced = runAccept(root, { locale: 'en-US', allowUnchanged: true });
        assert.equal(forced.ok, true);
        assert.equal(runCheck(root).issues.filter((i) => i.severity === 'error').length, 0);

        // 英文改成 T3 → 源已按 S2 重审、翻译又变 → translation-unaccepted，accept 直接更新为 S2/T3
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common_en.properties'),
            'greeting=Hello again {name}\ntitle=Artwork title\n', 'utf8');
        runGenerate(root);
        report = runCheck(root);
        assert.equal(errorsOf(report, 'translation-unaccepted', 'en-US').length, 1);
        const accepted = runAccept(root, { locale: 'en-US' });
        assert.equal(accepted.ok, true);
        assert.equal(accepted.updated, 1);
        assert.equal(runCheck(root).issues.filter((i) => i.severity === 'error').length, 0);
    } finally {
        cleanRepo(root);
    }
});

test('candidate：missing/stale/translation-unaccepted 只报告；invalid/extra 阻断', () => {
    const zhEn = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        runAccept(zhEn, { bootstrap: true });

        // candidate 部分 key：missing 只报告
        fs.writeFileSync(path.join(zhEn, APP_I18N, 'web', 'common_ja.properties'),
            'greeting=こんにちは {name}\n', 'utf8');
        runGenerate(zhEn);
        let report = runCheck(zhEn);
        assert.equal(report.issues.filter((i) => i.severity === 'error').length, 0);
        const missing = report.issues.find((i) => i.locale === 'ja-JP' && i.type === 'missing');
        assert.ok(missing);
        assert.equal(missing.severity, 'report');
        assert.equal(missing.targetFile, 'pixivdownload-app/src/main/resources/i18n/web/common_ja.properties');

        // candidate 已有翻译但占位符错误 → 阻断
        fs.writeFileSync(path.join(zhEn, APP_I18N, 'web', 'common_ja.properties'),
            'greeting=こんにちは {wrong}\ntitle=タイトル\n', 'utf8');
        runGenerate(zhEn);
        report = runCheck(zhEn);
        assert.ok(report.issues.some((i) => i.locale === 'ja-JP' && i.type === 'invalid'));

        // candidate extra key → 阻断
        fs.writeFileSync(path.join(zhEn, APP_I18N, 'web', 'common_ja.properties'),
            'greeting=こんにちは {name}\ntitle=タイトル\nsurplus=多餘\n', 'utf8');
        runGenerate(zhEn);
        report = runCheck(zhEn);
        assert.ok(report.issues.some((i) => i.locale === 'ja-JP' && i.type === 'extra'));

        // candidate translation-unaccepted → 报告不阻断
        fs.writeFileSync(path.join(zhEn, APP_I18N, 'web', 'common_ja.properties'),
            'greeting=こんにちは {name}\ntitle=タイトル\n', 'utf8');
        runGenerate(zhEn);
        const jaAccept = runAccept(zhEn, { locale: 'ja-JP' });
        assert.equal(jaAccept.ok, true);
        assert.equal(jaAccept.updated, 2);
        fs.writeFileSync(path.join(zhEn, APP_I18N, 'web', 'common_ja.properties'),
            'greeting=こんにちは {name}!\ntitle=タイトル\n', 'utf8');
        runGenerate(zhEn);
        report = runCheck(zhEn);
        assert.equal(report.issues.filter((i) => i.severity === 'error').length, 0);
        assert.ok(report.issues.some((i) => i.locale === 'ja-JP' && i.type === 'translation-unaccepted'));
    } finally {
        cleanRepo(zhEn);
    }
});

test('candidate 缺整个文件：逐 key missing report + coverage + Agent prompt 精确路径', async () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        runAccept(root, { bootstrap: true });
        const report = runCheck(root);
        await import('../lib/agent-prompts.mjs').then((m) => m.default.write(root, report));
        // candidate ja-JP 文件不存在 → 逐 key missing（report 级）
        const jaMissing = report.issues.filter((i) => i.locale === 'ja-JP' && i.type === 'missing');
        assert.equal(jaMissing.length, 2);
        for (const issue of jaMissing) {
            assert.equal(issue.severity, 'report');
            assert.equal(issue.targetFile, 'pixivdownload-app/src/main/resources/i18n/web/common_ja.properties');
            assert.ok(issue.sourceValue);
            assert.ok(issue.placeholders);
        }
        // coverage：translated=0、missing=sourceKeys
        const row = coverageRow(report, 'ja-JP', 'web/common');
        assert.equal(row.sourceKeys, 2);
        assert.equal(row.translated, 0);
        assert.equal(row.missing, 2);
        assert.equal(row.coverage, '0.00%');
        // master 不得显示「没有任务」
        const master = fs.readFileSync(path.join(root, 'build', 'reports', 'i18n', 'prompts', 'master.md'), 'utf8');
        assert.match(master, /ja-JP/);
        assert.doesNotMatch(master, /没有需要翻译/);
        // Agent prompt 给出精确目标路径
        const prompt = fs.readFileSync(path.join(root, 'build', 'reports', 'i18n', 'prompts',
            'ja-JP', 'pixivdownload-app__common.md'), 'utf8');
        assert.match(prompt, /pixivdownload-app\/src\/main\/resources\/i18n\/web\/common_ja\.properties/);
        assert.match(prompt, /你好 \{name\}/);
    } finally {
        cleanRepo(root);
    }
});

test('supported 缺整个文件：文件级 error + 逐 key missing error + coverage', () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH });
    try {
        const report = runCheck(root);
        const fileError = errorsOf(report, 'missing-language-file', 'en-US');
        assert.equal(fileError.length, 1);
        assert.match(fileError[0].message, /common_en\.properties/);
        const missing = errorsOf(report, 'missing', 'en-US');
        assert.equal(missing.length, 2);
        for (const issue of missing) {
            assert.equal(issue.severity, 'error');
            assert.equal(issue.targetFile, 'pixivdownload-app/src/main/resources/i18n/web/common_en.properties');
        }
        const row = coverageRow(report, 'en-US', 'web/common');
        assert.equal(row.sourceKeys, 2);
        assert.equal(row.translated, 0);
        assert.equal(row.missing, 2);
    } finally {
        cleanRepo(root);
    }
});

test('disabled：只检查可解析与重复 key；占位符不一致不报、缺文件不报、无覆盖率', () => {
    // disabled 文件存在：占位符不同但可解析 → 无 error
    const root = makeRepo({
        'web/common.properties': 'a=你好 {name}\n',
        'web/common_en.properties': 'a=Hello {name}\n',
        'web/common_es.properties': 'a=Hola {nombre}\n',
    });
    try {
        runAccept(root, { bootstrap: true });
        const report = runCheck(root);
        assert.equal(report.issues.filter((i) => i.severity === 'error').length, 0);
        assert.equal(report.coverage.some((c) => c.locale === 'es-ES'), false);
        assert.equal(report.issues.some((i) => i.locale === 'es-ES'), false);
    } finally {
        cleanRepo(root);
    }
    // disabled 文件语法错误 → error
    const bad = makeRepo({
        'web/common.properties': 'a=1\n',
        'web/common_en.properties': 'a=1\n',
        'web/common_es.properties': 'a=\\u12ZZ\n',
    });
    try {
        const report = runCheck(bad);
        assert.ok(report.issues.some((i) => i.type === 'invalid-properties' && i.locale === 'es-ES'));
    } finally {
        cleanRepo(bad);
    }
    // disabled 缺文件 → 不报告
    const missing = makeRepo({ 'web/common.properties': 'a=1\n', 'web/common_en.properties': 'a=1\n' });
    try {
        const report = runCheck(missing);
        assert.equal(report.issues.some((i) => i.locale === 'es-ES'), false);
    } finally {
        cleanRepo(missing);
    }
});

test('candidate 晋升 supported：覆盖不足 100% 时 check 失败', () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        runAccept(root, { bootstrap: true });
        // ja 只有部分 key
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common_ja.properties'),
            'greeting=こんにちは {name}\n', 'utf8');
        runGenerate(root);
        // 晋升：status candidate → supported
        const catalogPath = path.join(root, APP_I18N, 'locales.json');
        const catalog = fs.readFileSync(catalogPath, 'utf8').replace(
            '"tag": "ja-JP", "nativeName": "日本語", "resourceSuffix": "ja", "status": "candidate"',
            '"tag": "ja-JP", "nativeName": "日本語", "resourceSuffix": "ja", "status": "supported"');
        fs.writeFileSync(catalogPath, catalog, 'utf8');
        runGenerate(root);
        const report = runCheck(root);
        // 缺 title → supported missing → error
        assert.ok(report.issues.some((i) => i.locale === 'ja-JP' && i.type === 'missing'
            && i.severity === 'error'));
        assert.ok(report.issues.some((i) => i.locale === 'ja-JP' && i.type === 'missing-language-file'
            || i.locale === 'ja-JP' && i.type === 'missing'));
    } finally {
        cleanRepo(root);
    }
});

test('supported 晋升不足 100% 时 bootstrap 拒绝', () => {
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
        assert.ok(report.issues.some((i) => i.type === 'unknown-locale-suffix'
            && i.file.endsWith('common_.properties')));
    } finally {
        cleanRepo(conflict);
    }
});

test('下划线 bundle：download_status 单独存在是 source；未知日语文件才报未知后缀', () => {
    const root = makeRepo({
        'web/download_status.properties': 'pending=等待中\n',
        'web/download_status_en.properties': 'pending=Pending\n',
    });
    try {
        const report = runCheck(root);
        assert.equal(report.issues.some((i) => i.type === 'unknown-locale-suffix'), false);
        assert.equal(report.coverage.some((c) => c.baseName === 'web/download_status'), true);
    } finally {
        cleanRepo(root);
    }
    // download_status + download_status_fr（fr 不在 catalog 中）→ 报未知后缀
    const unknown = makeRepo({
        'web/download_status.properties': 'pending=等待中\n',
        'web/download_status_en.properties': 'pending=Pending\n',
        'web/download_status_fr.properties': 'pending=En attente\n',
    });
    try {
        const report = runCheck(unknown);
        const hits = report.issues.filter((i) => i.type === 'unknown-locale-suffix');
        assert.ok(hits.length === 1);
        assert.match(hits[0].file, /download_status_fr\.properties/);
    } finally {
        cleanRepo(unknown);
    }
    // 单独 foo_fr.properties（fr 不在 catalog、无 sibling 证据）→ 视为 root bundle
    const lone = makeRepo({ 'web/foo_fr.properties': 'a=1\n' });
    try {
        const report = runCheck(lone);
        assert.equal(report.issues.some((i) => i.type === 'unknown-locale-suffix'), false);
        assert.ok(report.coverage.some((c) => c.baseName === 'web/foo_fr'));
    } finally {
        cleanRepo(lone);
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

test('lock orphan：check 失败并提示 prune；prune 只删已确认消失的条目', () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        runAccept(root, { bootstrap: true });
        // 锁里塞一个不存在的 key 条目
        const lock = staleLock.load(root);
        lock.entries.push({
            locale: 'en-US', module: 'pixivdownload-app', baseName: 'web/common',
            key: 'deleted.key',
            acceptedSourceHash: '0'.repeat(64),
            acceptedTranslationHash: '0'.repeat(64),
        });
        staleLock.save(root, lock);

        let report = runCheck(root);
        assert.ok(report.issues.some((i) => i.type === 'orphan-lock-entry'));
        assert.ok(report.issues.some((i) => /i18n:accept -- --prune/.test(i.message)));

        // 普通 check 不自动修改 lock
        const lockAfter = staleLock.load(root);
        assert.equal(lockAfter.entries.length, 3);

        // prune 只删除 orphan；仍存在的 stale 条目不删除
        const pruned = runAccept(root, { prune: true });
        assert.equal(pruned.ok, true);
        assert.match(pruned.messages[0], /pruned 1/);
        const lockPruned = staleLock.load(root);
        assert.equal(lockPruned.entries.length, 2);
        // 仍存在的 key 条目不受影响
        assert.ok(lockPruned.entries.some((e) => e.key === 'greeting'));
    } finally {
        cleanRepo(root);
    }
});

test('lock 结构校验：重复条目 / 非法 hash / 未知版本 / 未知 locale 全部失败', () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        runAccept(root, { bootstrap: true });
        const lockPath = path.join(root, 'i18n', 'catalog-lock.json');
        const lock = staleLock.load(root);

        // 重复条目
        const dup = JSON.parse(JSON.stringify(lock));
        dup.entries.push(dup.entries[0]);
        fs.writeFileSync(lockPath, JSON.stringify(dup), 'utf8');
        assert.throws(() => runCheck(root), /duplicate lock entry/);

        // 非法 hash
        const badHash = JSON.parse(JSON.stringify(lock));
        badHash.entries[0].acceptedSourceHash = 'zzz';
        fs.writeFileSync(lockPath, JSON.stringify(badHash), 'utf8');
        assert.throws(() => runCheck(root), /64-digit hex/);

        // 错误版本
        const badVersion = JSON.parse(JSON.stringify(lock));
        badVersion.version = 999;
        fs.writeFileSync(lockPath, JSON.stringify(badVersion), 'utf8');
        assert.throws(() => runCheck(root), /unsupported lock version/);

        // 未知 locale
        const unknownLocale = JSON.parse(JSON.stringify(lock));
        unknownLocale.entries[0].locale = 'fr-FR';
        fs.writeFileSync(lockPath, JSON.stringify(unknownLocale), 'utf8');
        const report = runCheck(root);
        assert.ok(report.issues.some((i) => i.type === 'invalid-lock-entry'));
        assert.ok(report.issues.some((i) => i.type === 'orphan-lock-entry'));
    } finally {
        cleanRepo(root);
    }
});

test('静态资源同步：快照中 properties 更新但静态文件未更新 → 失败；生成后通过', () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        // 改 properties 但不重新生成静态 → static-out-of-sync
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common.properties'),
            'greeting=你好呀 {name}\ntitle=作品标题\n', 'utf8');
        let report = runCheck(root);
        assert.ok(report.issues.some((i) => i.type === 'static-out-of-sync'));
        assert.ok(report.issues.some((i) => /i18n:generate-static/.test(i.message)));

        runGenerate(root);
        report = runCheck(root);
        assert.equal(report.issues.some((i) => i.type === 'static-out-of-sync'), false);

        // 多余文件 → 失败
        fs.writeFileSync(path.join(root, STATIC_DIR, 'stray.json'), '{}', 'utf8');
        report = runCheck(root);
        assert.ok(report.issues.some((i) => i.type === 'static-out-of-sync'
            && /unexpected/.test(i.message)));
        fs.rmSync(path.join(root, STATIC_DIR, 'stray.json'));
    } finally {
        cleanRepo(root);
    }
});

test('静态 meta.json：aliases 与后端契约一致（每个可见语言都输出 tag/aliases/nativeName/direction/status）', () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        const meta = JSON.parse(fs.readFileSync(path.join(root, STATIC_DIR, 'meta.json'), 'utf8'));
        assert.equal(meta.supportedLocales.length, 2);
        const zh = meta.supportedLocales.find((l) => l.tag === 'zh-CN');
        assert.deepEqual(zh.aliases, ['zh', 'zh-Hans']);
        const en = meta.supportedLocales.find((l) => l.tag === 'en-US');
        assert.deepEqual(en.aliases, ['en']);
        assert.equal(en.status, 'supported');
        assert.equal(zh.direction, 'ltr');
        // candidate / disabled 不暴露
        assert.equal(meta.supportedLocales.some((l) => l.status === 'candidate'
            || l.status === 'disabled'), false);
    } finally {
        cleanRepo(root);
    }
});

test('generate-static：只生成核心 app 的 source/supported，插件 bundle 不静态化，确定性输出', () => {
    const root = makeRepo({
        'web/common.properties': 'a=中文\nb=仅中文\n',
        'web/common_en.properties': 'a=English\n',
        'web/common_ja.properties': 'a=日本語\n',
    });
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

        const enBundle = JSON.parse(fs.readFileSync(path.join(result.outputDir, 'common.en-US.json'), 'utf8'));
        assert.equal(enBundle.messages.a, 'English');
        assert.equal(enBundle.messages.b, '仅中文');

        const zhBundle = JSON.parse(fs.readFileSync(path.join(result.outputDir, 'common.zh-CN.json'), 'utf8'));
        assert.equal(zhBundle.messages.a, '中文');

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
        fs.writeFileSync(path.join(staticDir, 'js', 'page.js'),
            'var defaultLang = "en-US";\nvar supportedLocales = ["zh-CN"];\nLocale.US\n');
        const catalog = catalogLib.load(root);
        const hits = guard.default.scan(root, catalog);
        const jsHits = hits.filter((h) => h.relPath.endsWith('page.js'));
        assert.equal(jsHits.length, 3);
        assert.equal(hits.some((h) => h.relPath.includes('locales.json')), false);
        assert.equal(hits.some((h) => h.relPath.includes('i18n-static')), false);

        const report = runCheck(root);
        assert.ok(report.issues.some((i) => i.type === 'hardcoded-locale'));
    } finally {
        cleanRepo(root);
    }
});

test('hardcoded-only 轻量模式：只扫硬编码，不查 bundle', async () => {
    const root = makeRepo({ 'web/common.properties': OK_ZH, 'web/common_en.properties': OK_EN });
    try {
        fs.mkdirSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js'), { recursive: true });
        fs.writeFileSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 'feature.js'),
            'const supportedLocales = ["en-US"];\n');
        const report = runCheck({ snapshotRoot: root, reportRoot: root, hardcodedOnly: true });
        assert.ok(report.issues.some((i) => i.type === 'hardcoded-locale'));
        assert.equal(report.coverage.length, 0);
    } finally {
        cleanRepo(root);
    }
});

test('Agent 提示词：locale 为 null 的问题进 maintenance，bundle 名安全化', async () => {
    const prompts = await import('../lib/agent-prompts.mjs');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-prompts-'));
    try {
        const catalog = { sourceLocale: 'zh-CN', fallbackLocale: 'en-US', defaultLocale: 'en-US', locales: [] };
        prompts.default.write(root, {
            catalog,
            issues: [
                { locale: 'en-US', module: 'pixivdownload-app', baseName: 'web/common', bundle: 'pixivdownload-app__common', file: 'x.properties', targetFile: 'pixivdownload-app/src/main/resources/i18n/web/common_en.properties', key: 'greeting', type: 'missing', severity: 'error', message: 'missing', sourceValue: '你好', translationValue: null, placeholders: ['{name}'] },
                { locale: null, module: null, baseName: null, bundle: null, file: 'scripts/i18n/x.mjs', key: null, type: 'hardcoded-locale', severity: 'error', message: 'hardcoded language tag "en-US"' },
            ],
            coverage: [], warnings: [],
        });
        const dir = path.join(root, 'build', 'reports', 'i18n', 'prompts');
        // locale 为 null 的问题 → maintenance.md，不生成 prompts/null
        assert.ok(fs.existsSync(path.join(dir, 'maintenance.md')));
        assert.equal(fs.existsSync(path.join(dir, 'null')), false);
        const maintenance = fs.readFileSync(path.join(dir, 'maintenance.md'), 'utf8');
        assert.match(maintenance, /hardcoded-locale/);
        const master = fs.readFileSync(path.join(dir, 'master.md'), 'utf8');
        assert.match(master, /prompts\/en-US\/pixivdownload-app__common\.md/);
        const prompt = fs.readFileSync(path.join(dir, 'en-US', 'pixivdownload-app__common.md'), 'utf8');
        assert.match(prompt, /pixivdownload-app\/src\/main\/resources\/i18n\/web\/common_en\.properties/);
        assert.match(prompt, /你好/);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
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
        assert.deepEqual(
            runCheck(root).issues.filter((i) => i.locale === 'en-US').map((i) => i.type + ':' + i.key),
            ['empty:a', 'missing:b', 'new-unaccepted:c', 'missing:d', 'extra:e']);
    } finally {
        cleanRepo(root);
    }
});

test('hash 规范：空白敏感（不 trim），CRLF 归一化', () => {
    assert.notEqual(staleLock.hashValue('x '), staleLock.hashValue('x'));
    assert.notEqual(staleLock.hashValue(' x'), staleLock.hashValue('x'));
    assert.notEqual(staleLock.hashValue('x\n'), staleLock.hashValue('x'));
    assert.equal(staleLock.hashValue('x\r\ny'), staleLock.hashValue('x\ny'));
    assert.equal(staleLock.hashValue('a'), staleLock.hashValue('a'));
});
