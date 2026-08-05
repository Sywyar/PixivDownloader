#!/usr/bin/env node
'use strict';
/**
 * 完整 i18n 检查器。
 *
 * 用法：
 *   node scripts/i18n/check.mjs                 # 检查 + 报告
 *   node scripts/i18n/check.mjs --strict        # 同上（package.json i18n:check 使用）
 *   node scripts/i18n/check.mjs --report-only   # 只生成报告，不因问题失败
 *
 * 流程：catalog 校验 → 发现 bundle → properties 解析 → 逐语言 / 逐 bundle 检查
 * → 硬编码守卫 → 覆盖率 → 写报告与 Agent 提示词 → 退出码。
 * 覆盖率只基于 exact 文件（loadEffective 永不参与覆盖率）。
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import catalogLib from './lib/catalog.mjs';
import parser from './lib/properties-parser.mjs';
import discover from './lib/discover-bundles.mjs';
import placeholders from './lib/placeholders.mjs';
import staleLock from './lib/stale-lock.mjs';
import ignoreLib from './lib/ignore.mjs';
import reportLib from './lib/report.mjs';
import prompts from './lib/agent-prompts.mjs';
import guard from './lib/hardcoded-guard.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function parseArgs(argv) {
    const args = { strict: false, reportOnly: false };
    for (const arg of argv) {
        if (arg === '--strict') {
            args.strict = true;
        } else if (arg === '--report-only') {
            args.reportOnly = true;
        }
    }
    return args;
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    let report;
    try {
        report = runCheck(REPO_ROOT);
    } catch (e) {
        console.error('I18N CHECK ERROR: ' + e.message);
        reportLib.write(REPO_ROOT, { catalogError: e.message, issues: [], coverage: [], warnings: [] });
        process.exit(1);
        return;
    }

    reportLib.write(REPO_ROOT, report);
    prompts.write(REPO_ROOT, report, report.catalog);

    const failIssues = report.issues.filter((issue) => issue.severity === 'error');
    if (failIssues.length > 0 && !args.reportOnly) {
        console.error('I18N CHECK FAILED');
        console.error('');

        // 按语言聚合覆盖率与问题数（hardcoded-locale 等无语言归属的问题计入 unsorted）
        const perLocale = new Map();
        for (const issue of failIssues) {
            const key = issue.locale || 'other';
            if (!perLocale.has(key)) {
                perLocale.set(key, { missing: 0, stale: 0, invalid: 0, extra: 0, empty: 0, total: 0, translated: 0 });
            }
            const counts = perLocale.get(key);
            counts[issue.type] = (counts[issue.type] || 0) + 1;
        }
        for (const row of report.coverage) {
            const counts = perLocale.get(row.locale) || {
                missing: 0, stale: 0, invalid: 0, extra: 0, empty: 0, total: 0, translated: 0,
            };
            counts.total += row.sourceKeys;
            counts.translated += row.translated;
            counts.missing += row.missing;
            counts.stale += row.stale;
            counts.invalid += row.invalid;
            counts.extra += row.extra;
            perLocale.set(row.locale, counts);
        }
        for (const [locale, counts] of [...perLocale.entries()].sort()) {
            const percent = counts.total > 0
                ? (100 * counts.translated / counts.total).toFixed(2) + '%'
                : 'n/a';
            console.error(`${locale} [${statusOf(report.catalog, locale)}]: ${percent}`);
            console.error(`  missing: ${counts.missing}`);
            console.error(`  stale: ${counts.stale}`);
            console.error(`  invalid: ${counts.invalid}`);
            console.error(`  extra: ${counts.extra}`);
        }
        console.error('');
        console.error('Generated:');
        console.error('  build/reports/i18n/report.json');
        console.error('  build/reports/i18n/summary.md');
        console.error('  build/reports/i18n/prompts/master.md');
        process.exit(1);
        return;
    }

    console.log('I18N CHECK OK');
    if (report.warnings.length > 0) {
        console.log(`  ${report.warnings.length} warning(s) — see build/reports/i18n/summary.md`);
    }
    const totals = new Map();
    for (const row of report.coverage) {
        const t = totals.get(row.locale) || { total: 0, translated: 0 };
        t.total += row.sourceKeys;
        t.translated += row.translated;
        totals.set(row.locale, t);
    }
    for (const [locale, t] of [...totals.entries()].sort()) {
        const percent = t.total > 0 ? (100 * t.translated / t.total).toFixed(2) + '%' : 'n/a';
        console.log(`  ${locale} [${statusOf(report.catalog, locale)}]: ${percent}`);
    }
}

function statusOf(catalog, localeTag) {
    if (!catalog) {
        return 'unknown';
    }
    const descriptor = catalogLib.descriptorByTag(catalog, localeTag);
    return descriptor ? descriptor.status : 'unknown';
}

function rowDefault(catalog) {
    return catalog ? catalog.defaultLocale : '';
}

function runCheck(repoRoot) {
    const catalog = catalogLib.load(repoRoot);
    const ignoreConfig = ignoreLib.load(repoRoot);
    const lock = staleLock.load(repoRoot);
    const lockIndex = staleLock.index(lock);

    const issues = [];
    const warnings = [];

    function fail(locale, module, baseName, bundleId, file, key, type, message, extra = {}) {
        issues.push({
            locale, module, baseName, bundle: bundleId, file, key, type, severity: 'error',
            message, ...extra,
        });
    }

    function warn(locale, module, baseName, bundleId, key, type, message) {
        if (ignoreLib.isIgnored(ignoreConfig, bundleId, key, type)) {
            return;
        }
        warnings.push({ locale, module, baseName, bundle: bundleId, key, type, message });
    }

    // ---- 发现与解析 ----
    const discovery = discover.discover(repoRoot, catalog);
    for (const entry of discovery.unknownSuffixFiles) {
        fail(null, entry.module, entry.baseName, discover.bundleKey(entry.module, entry.baseName),
            entry.relPath, null, 'unknown-locale-suffix',
            'properties file with suffix "' + entry.suffix + '" does not match any catalog locale');
    }
    for (const conflict of discovery.conflicts) {
        fail(conflict.localeTag, conflict.module, conflict.baseName,
            discover.bundleKey(conflict.module, conflict.baseName),
            conflict.relPath, null, 'conflicting-resource',
            'duplicate resource file for locale ' + conflict.localeTag + ': ' + conflict.relPath
                + ' and ' + conflict.other.relPath);
    }

    const parsed = new Map(); // relPath -> { entries, byKey, duplicateKeys, errors }
    for (const entry of discovery.rawFiles) {
        let result;
        try {
            result = parser.parse(fs.readFileSync(entry.filePath, 'utf8'));
        } catch (e) {
            fail(entry.localeTag, entry.module, entry.baseName,
                discover.bundleKey(entry.module, entry.baseName),
                entry.relPath, null, 'unparseable', e.message);
            continue;
        }
        parsed.set(entry.relPath, result);
        for (const error of result.errors) {
            fail(entry.localeTag, entry.module, entry.baseName,
                discover.bundleKey(entry.module, entry.baseName),
                entry.relPath, null, 'invalid-properties',
                'line ' + error.line + ': ' + error.message);
        }
        for (const dup of result.duplicateKeys) {
            fail(entry.localeTag, entry.module, entry.baseName,
                discover.bundleKey(entry.module, entry.baseName),
                entry.relPath, dup.key, 'duplicate-key',
                'key defined at lines ' + dup.lines.join(', '));
        }
    }

    // ---- 逐 bundle 检查 ----
    const coverage = [];
    for (const bundle of [...discovery.bundles.values()].sort((a, b) => a.bundleId.localeCompare(b.bundleId))) {
        const zhFile = bundle.files[catalog.sourceLocale];
        const zhResult = zhFile ? parsed.get(zhFile.relPath) : null;

        if (!zhFile || !zhResult) {
            fail(null, bundle.module, bundle.baseName, bundle.bundleId, null, null,
                'missing-source-bundle',
                'no source bundle (' + catalog.sourceLocale + ') found for baseName ' + bundle.baseName);
            continue;
        }
        const zhKeys = zhResult.entries.map((e) => e.key);
        const zhMap = new Map(zhResult.entries.map((e) => [e.key, e.value]));

        for (const zhKey of zhKeys) {
            if (zhMap.get(zhKey) === '') {
                fail(catalog.sourceLocale, bundle.module, bundle.baseName, bundle.bundleId,
                    zhFile.relPath, zhKey, 'empty', 'source value is empty');
            }
        }

        for (const descriptor of catalog.locales) {
            if (descriptor.tag === catalog.sourceLocale) {
                continue;
            }
            const file = bundle.files[descriptor.tag];
            const result = file ? parsed.get(file.relPath) : null;

            if (!file || !result) {
                if (descriptor.status === 'supported' || descriptor.status === 'source') {
                    fail(descriptor.tag, bundle.module, bundle.baseName, bundle.bundleId, null, null,
                        'missing-language-file',
                        'no ' + descriptor.tag + ' bundle file for baseName ' + bundle.baseName
                            + ' (suffix "' + descriptor.resourceSuffix + '")');
                }
                continue;
            }

            const localeMap = new Map(result.entries.map((e) => [e.key, e.value]));
            const localeKeys = result.entries.map((e) => e.key);

            // candidate 语言的缺失/过期只报告不阻断（severity=report），error 语义仍保留
            const severityFor = descriptor.status === 'candidate' ? 'report' : 'error';

            for (const key of localeKeys) {
                if (!zhMap.has(key)) {
                    fail(descriptor.tag, bundle.module, bundle.baseName, bundle.bundleId,
                        file.relPath, key, 'extra', 'key exists only in ' + descriptor.tag);
                }
            }
            for (const key of zhKeys) {
                if (!localeMap.has(key)) {
                    issues.push({
                        locale: descriptor.tag, module: bundle.module, baseName: bundle.baseName,
                        bundle: bundle.bundleId, file: file.relPath, key,
                        type: 'missing', severity: severityFor,
                        message: 'key missing in ' + descriptor.tag,
                    });
                    continue;
                }
                const zhValue = zhMap.get(key);
                const value = localeMap.get(key);
                if (value === '') {
                    issues.push({
                        locale: descriptor.tag, module: bundle.module, baseName: bundle.baseName,
                        bundle: bundle.bundleId, file: file.relPath, key,
                        type: 'empty', severity: severityFor,
                        message: 'translation value is empty',
                    });
                    continue;
                }
                const quality = placeholders.checkTranslation(zhValue, value);
                for (const message of quality.errors) {
                    fail(descriptor.tag, bundle.module, bundle.baseName, bundle.bundleId,
                        file.relPath, key, 'invalid', message,
                        { placeholders: placeholders.tokens(zhValue) });
                }
                for (const message of quality.warnings) {
                    warn(descriptor.tag, bundle.module, bundle.baseName, bundle.bundleId,
                        key, 'warning:' + message.split(':')[0], message);
                }

                // stale：lock 中 acceptedSourceHash 与当前源 hash 不一致（含无 lock 记录的新 key）
                const sourceHash = staleLock.hashValue(zhValue);
                const lockEntry = lockIndex.get(staleLock.entryKey({
                    locale: descriptor.tag, module: bundle.module, baseName: bundle.baseName, key,
                }));
                const isStale = !lockEntry || lockEntry.acceptedSourceHash !== sourceHash;
                if (isStale && (descriptor.status === 'supported' || descriptor.status === 'source')) {
                    fail(descriptor.tag, bundle.module, bundle.baseName, bundle.bundleId,
                        file.relPath, key, 'stale',
                        lockEntry
                            ? 'source changed since accepted baseline; review translation and run i18n:accept'
                            : 'key not yet accepted; run i18n:accept after reviewing translation');
                } else if (isStale && descriptor.status === 'candidate') {
                    issues.push({
                        locale: descriptor.tag, module: bundle.module, baseName: bundle.baseName,
                        bundle: bundle.bundleId, file: file.relPath, key,
                        type: 'stale', severity: 'report',
                        message: 'source changed since accepted baseline (candidate)',
                    });
                }
            }
        }

        // 覆盖率：supported 参与门禁，candidate 仅报告（基于 exact 文件）
        for (const descriptor of catalog.locales) {
            if (descriptor.status !== 'supported' && descriptor.status !== 'candidate') {
                continue;
            }
            const file = bundle.files[descriptor.tag];
            const result = file ? parsed.get(file.relPath) : null;
            const sourceKeys = zhKeys.length;
            let translated = 0;
            const counts = { missing: 0, empty: 0, stale: 0, invalid: 0, extra: 0 };
            if (file && result) {
                const localeMap = new Map(result.entries.map((e) => [e.key, e.value]));
                const bundlePrefix = `${bundle.module}\u0000${bundle.baseName}`;
                for (const key of zhKeys) {
                    const value = localeMap.get(key);
                    if (value == null) {
                        counts.missing += 1;
                        continue;
                    }
                    if (value === '') {
                        counts.empty += 1;
                        continue;
                    }
                    const quality = placeholders.checkTranslation(zhMap.get(key), value);
                    if (quality.errors.length > 0) {
                        counts.invalid += 1;
                        continue;
                    }
                    const sourceHash = staleLock.hashValue(zhMap.get(key));
                    const lockEntry = lockIndex.get(staleLock.entryKey({
                        locale: descriptor.tag, module: bundle.module, baseName: bundle.baseName, key,
                    }));
                    if (!lockEntry || lockEntry.acceptedSourceHash !== sourceHash) {
                        counts.stale += 1;
                        continue;
                    }
                    translated += 1;
                }
                counts.extra = result.entries.filter((e) => !zhMap.has(e.key)).length;
            }
            const denominator = Math.max(1, sourceKeys);
            coverage.push({
                locale: descriptor.tag,
                module: bundle.module,
                baseName: bundle.baseName,
                bundle: bundle.bundleId,
                sourceKeys,
                translated,
                missing: counts.missing,
                empty: counts.empty,
                stale: counts.stale,
                invalid: counts.invalid,
                extra: counts.extra,
                coverage: (100 * translated / denominator).toFixed(2) + '%',
            });
        }
    }

    // ---- 硬编码语言守卫 ----
    const hits = guard.scan(repoRoot, catalog);
    for (const hit of hits) {
        fail(null, null, null, null, hit.relPath, null, 'hardcoded-locale',
            hit.label + ' at line ' + hit.line + ': ' + hit.text);
    }

    issues.sort(compareIssues);
    warnings.sort(compareWarnings);

    return {
        catalog,
        issues,
        coverage,
        warnings,
    };
}

function compareIssues(a, b) {
    return String(a.locale).localeCompare(String(b.locale))
        || String(a.module).localeCompare(String(b.module))
        || String(a.baseName).localeCompare(String(b.baseName))
        || String(a.key).localeCompare(String(b.key))
        || String(a.type).localeCompare(String(b.type));
}

function compareWarnings(a, b) {
    return String(a.locale).localeCompare(String(b.locale))
        || String(a.module).localeCompare(String(b.module))
        || String(a.baseName).localeCompare(String(b.baseName))
        || String(a.key).localeCompare(String(b.key))
        || String(a.type).localeCompare(String(b.type));
}

// 供测试直接调用
export {  runCheck, REPO_ROOT  };

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}
