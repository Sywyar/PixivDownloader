#!/usr/bin/env node
'use strict';
/**
 * i18n 门禁检查器（清单驱动）。
 *
 * 用法：
 *   node scripts/i18n/check.mjs                          # 检查当前工作树（默认）
 *   node scripts/i18n/check.mjs --strict                 # 同上（默认严格，兼容 package.json i18n:check）
 *   node scripts/i18n/check.mjs --report-only            # 只生成报告，不因错误退出
 *   node scripts/i18n/check.mjs --hardcoded-only         # 轻量模式：只跑硬编码语言守卫
 *   node scripts/i18n/check.mjs --snapshot index         # 严格检查 Git index（pre-commit 使用）
 *   node scripts/i18n/check.mjs --snapshot ref --ref <sha>   # 严格检查给定 commit（pre-push 使用）
 *   node scripts/i18n/check.mjs --repo-root <path>       # 检查对象根（预推钩子用 HEAD 物化的检查器时指向仓库）
 *   node scripts/i18n/check.mjs --report-root <path>     # 报告输出根（快照检查时指向原始仓库）
 *
 * 快照语义：
 * - snapshotRoot = 被检查的文件系统快照（worktree 原样 / index / ref 物化到临时目录）；
 * - reportRoot   = 原始仓库，build/reports 与 prompts 写到这里，不随临时快照清理丢失；
 * - index / ref 模式严格读取 Git 快照，绝不混入未暂存 / 未提交的工作树内容；
 * - 快照检查不修改用户工作树；临时目录在 finally 中清理。
 *
 * 检查内容（全量模式）：
 *   catalog 校验 → bundle discovery / properties 解析 → 硬编码守卫 → 锁校验（orphan）→
 *   逐语言完整度与 accepted/stale/translation-unaccepted 状态 → 覆盖率（exact bundle，
 *   只有 accepted 计入 translated）→ 静态生成资源同步 → 写报告与 Agent 提示词。
 * 轻量模式（--hardcoded-only）：只扫描生产代码硬编码语言，供 pre-commit 每次提交使用。
 */

import fs from 'fs';
import os from 'os';
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
import snapshotLib from './lib/repository-snapshot.mjs';
import { runGenerate } from './generate-static.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

const STATIC_DIR_REL = path.join('pixivdownload-app', 'src', 'main', 'resources', 'static', 'i18n-static');

function parseArgs(argv) {
    const args = { strict: false, reportOnly: false, hardcodedOnly: false, snapshot: 'worktree', ref: null, repoRoot: null, reportRoot: null };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        if (arg === '--strict') {
            args.strict = true;
        } else if (arg === '--report-only') {
            args.reportOnly = true;
        } else if (arg === '--hardcoded-only') {
            args.hardcodedOnly = true;
        } else if (arg === '--write-agent-prompts') {
            // 兼容旧脚本：提示词现在总是生成，本标志为 no-op
        } else if (arg === '--snapshot') {
            args.snapshot = argv[++i];
        } else if (arg === '--ref') {
            args.ref = argv[++i];
        } else if (arg === '--repo-root') {
            args.repoRoot = argv[++i];
        } else if (arg === '--report-root') {
            args.reportRoot = argv[++i];
        }
    }
    if (!['worktree', 'index', 'ref'].includes(args.snapshot)) {
        throw new Error('invalid --snapshot "' + args.snapshot + '" (expected worktree | index | ref)');
    }
    if (args.snapshot === 'ref' && !args.ref) {
        throw new Error('--snapshot ref requires --ref <commit-sha>');
    }
    return args;
}

function main() {
    let args;
    try {
        args = parseArgs(process.argv.slice(2));
    } catch (e) {
        console.error('I18N CHECK ERROR: ' + e.message);
        process.exit(2);
        return;
    }

    const workRoot = args.repoRoot ? path.resolve(args.repoRoot) : REPO_ROOT;
    const reportRoot = args.reportRoot ? path.resolve(args.reportRoot) : workRoot;

    let materialized = null;
    let report;
    let fatalError = null;
    try {
        if (args.snapshot === 'index') {
            materialized = snapshotLib.materializeIndex(workRoot);
        } else if (args.snapshot === 'ref') {
            materialized = snapshotLib.materializeRef(workRoot, args.ref);
        } else {
            materialized = snapshotLib.materializeWorktree(workRoot);
        }
        const snapshotRoot = materialized.root;
        report = runCheck({
            snapshotRoot,
            reportRoot,
            hardcodedOnly: args.hardcodedOnly,
        });
    } catch (e) {
        fatalError = e;
    } finally {
        // 无论成功、失败还是异常退出，都必须清理临时快照 / 临时 index
        if (materialized) {
            materialized.cleanup();
        }
        snapshotLib.cleanupAll();
    }

    if (fatalError !== null) {
        console.error('I18N CHECK ERROR: ' + fatalError.message);
        reportLib.write(reportRoot, { catalogError: fatalError.message, issues: [], coverage: [], warnings: [] });
        process.exit(1);
        return;
    }

    reportLib.write(reportRoot, report);
    if (!args.hardcodedOnly) {
        prompts.write(reportRoot, report);
    }

    const failIssues = report.issues.filter((issue) => issue.severity === 'error');
    if (failIssues.length > 0 && !args.reportOnly) {
        console.error('I18N CHECK FAILED');
        console.error('');

        const perLocale = new Map();
        for (const issue of failIssues) {
            const key = issue.locale || 'other';
            if (!perLocale.has(key)) {
                perLocale.set(key, { total: 0, translated: 0, missing: 0, stale: 0, invalid: 0, extra: 0, empty: 0 });
            }
            const counts = perLocale.get(key);
            counts[issue.type] = (counts[issue.type] || 0) + 1;
        }
        for (const row of report.coverage) {
            const counts = perLocale.get(row.locale) || {
                total: 0, translated: 0, missing: 0, stale: 0, invalid: 0, extra: 0, empty: 0,
            };
            counts.total += row.sourceKeys;
            counts.translated += row.translated;
            counts.missing += row.missing;
            counts.stale += row.stale + row.translationUnaccepted + row.newUnaccepted;
            counts.invalid += row.invalid;
            counts.extra += row.extra;
            counts.empty += row.empty;
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

/** 覆盖率行。 */
function coverageRow(catalog, descriptor, bundle, sourceKeys, counts, translated) {
    return {
        locale: descriptor.tag,
        module: bundle.module,
        baseName: bundle.baseName,
        bundle: bundle.bundleId,
        sourceKeys,
        translated,
        missing: counts.missing,
        empty: counts.empty,
        stale: counts.stale,
        translationUnaccepted: counts.translationUnaccepted,
        newUnaccepted: counts.newUnaccepted,
        invalid: counts.invalid,
        extra: counts.extra,
        coverage: (100 * translated / Math.max(1, sourceKeys)).toFixed(2) + '%',
    };
}

/** 缺整个语言文件：supported → 文件级 error + 逐 key missing（error）；candidate → 逐 key missing（report）。 */
function missingFileIssues(report, issues, fail, catalog, bundle, descriptor) {
    const severity = descriptor.status === 'candidate' ? 'report' : 'error';
    const sourceResult = bundle.sourceResult;
    const sourceFile = bundle.files[catalog.sourceLocale];
    const targetFile = discover.targetPathFor(bundle.module, bundle.baseName, descriptor.resourceSuffix);
    if (severity === 'error') {
        fail(descriptor.tag, bundle.module, bundle.baseName, bundle.bundleId, null, null,
            'missing-language-file',
            'no ' + descriptor.tag + ' bundle file for baseName ' + bundle.baseName
                + ' (suffix "' + descriptor.resourceSuffix + '"); create ' + targetFile);
    }
    for (const entry of sourceResult.entries) {
        issues.push({
            locale: descriptor.tag, module: bundle.module, baseName: bundle.baseName,
            bundle: bundle.bundleId, file: null, targetFile, key: entry.key,
            type: 'missing', severity,
            message: 'key missing in ' + descriptor.tag + ' (language file missing; create ' + targetFile + ')',
            sourceValue: entry.value,
            placeholders: placeholders.tokens(entry.value),
            sourceFile: sourceFile ? sourceFile.relPath : null,
        });
    }
    return targetFile;
}

/** 静态生成资源同步：用快照内容生成预期文件，与快照中的静态目录比较。 */
function checkStaticSync(snapshotRoot, issues, fail) {
    const expectedDir = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-static-expected-'));
    let expected;
    try {
        const result = runGenerate(snapshotRoot, expectedDir);
        expected = new Map(result.files.map((name) => [name, fs.readFileSync(path.join(expectedDir, name), 'utf8')]));
    } catch (e) {
        fail(null, null, null, null, null, null, 'static-generation-failed',
            'cannot generate expected static resources: ' + e.message);
        return;
    } finally {
        fs.rmSync(expectedDir, { recursive: true, force: true });
    }

    const staticDir = path.join(snapshotRoot, STATIC_DIR_REL);
    const actual = new Map();
    if (fs.existsSync(staticDir)) {
        for (const name of fs.readdirSync(staticDir).sort()) {
            actual.set(name, fs.readFileSync(path.join(staticDir, name), 'utf8'));
        }
    }

    for (const name of [...expected.keys()].sort()) {
        if (!actual.has(name)) {
            fail(null, null, null, null, null, null, 'static-out-of-sync',
                'static resource ' + name + ' is missing from the snapshot; run: npm run i18n:generate-static && git add ...');
        } else if (actual.get(name) !== expected.get(name)) {
            fail(null, null, null, null, null, null, 'static-out-of-sync',
                'static resource ' + name + ' is stale in the snapshot; run: npm run i18n:generate-static && git add ...');
        }
    }
    for (const name of [...actual.keys()].sort()) {
        if (!expected.has(name)) {
            fail(null, null, null, null, null, null, 'static-out-of-sync',
                'unexpected static resource ' + name + ' in the snapshot; regenerate and commit the canonical set');
        }
    }
}

/**
 * 运行检查。
 * @param {string|Object} repoRootOrOptions 兼容旧签名：传仓库根字符串（worktree 语义）；
 *   或 { snapshotRoot, reportRoot, hardcodedOnly }
 * @returns {{catalog, issues, coverage, warnings}}
 */
function runCheck(repoRootOrOptions) {
    if (typeof repoRootOrOptions === 'string') {
        return runCheckInner({
            snapshotRoot: repoRootOrOptions,
            reportRoot: repoRootOrOptions,
            hardcodedOnly: false,
        });
    }
    return runCheckInner(repoRootOrOptions);
}

function runCheckInner({ snapshotRoot, hardcodedOnly }) {
    const catalog = catalogLib.load(snapshotRoot);
    const issues = [];
    const warnings = [];

    function fail(locale, module, baseName, bundleId, file, key, type, message, extra = {}) {
        issues.push({
            locale, module, baseName, bundle: bundleId, file, key, type, severity: 'error',
            message, ...extra,
        });
    }

    // ---- 轻量模式：只跑硬编码守卫 ----
    if (hardcodedOnly) {
        for (const hit of guard.scan(snapshotRoot, catalog)) {
            fail(null, null, null, null, hit.relPath, null, 'hardcoded-locale',
                hit.label + ' at line ' + hit.line + ': ' + hit.text);
        }
        return { catalog, issues, coverage: [], warnings };
    }

    const ignoreConfig = ignoreLib.load(snapshotRoot);
    const lock = staleLock.load(snapshotRoot);
    const lockIndex = staleLock.index(lock);

    function warn(locale, module, baseName, bundleId, key, type, message) {
        if (ignoreLib.isIgnored(ignoreConfig, bundleId, key, type)) {
            return;
        }
        warnings.push({ locale, module, baseName, bundle: bundleId, key, type, message });
    }

    // ---- discovery 与解析 ----
    const discovery = discover.discover(snapshotRoot, catalog);
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

    const parsed = new Map();
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

    // ---- 锁校验：orphan / 未知 locale / source locale entry ----
    const sourceMaps = new Map();
    for (const bundle of discovery.bundles.values()) {
        const sourceFile = bundle.files[catalog.sourceLocale];
        const sourceResult = sourceFile ? parsed.get(sourceFile.relPath) : null;
        if (sourceFile && sourceResult) {
            sourceMaps.set(bundle.bundleId, new Map(sourceResult.entries.map((e) => [e.key, e.value])));
        }
    }
    const lockValidation = staleLock.validateAgainstCatalog(lock, catalog, discovery.bundles, sourceMaps);
    for (const message of lockValidation.errors) {
        fail(null, null, null, null, null, null, 'invalid-lock-entry', message);
    }
    for (const entry of lockValidation.orphans) {
        fail(null, entry.module, entry.baseName, null, null, entry.key, 'orphan-lock-entry',
            'orphan lock entry for ' + entry.locale + ' / ' + entry.module + ' / ' + entry.baseName + ' / '
                + entry.key + ' (bundle or key no longer exists); run: npm run i18n:accept -- --prune');
    }

    // ---- 逐 bundle 检查 ----
    const coverage = [];
    for (const bundle of [...discovery.bundles.values()].sort((a, b) => a.bundleId.localeCompare(b.bundleId))) {
        const sourceFile = bundle.files[catalog.sourceLocale];
        const sourceResult = sourceFile ? parsed.get(sourceFile.relPath) : null;

        if (!sourceFile || !sourceResult) {
            fail(null, bundle.module, bundle.baseName, bundle.bundleId, null, null,
                'missing-source-bundle',
                'no source bundle (' + catalog.sourceLocale + ') found for baseName ' + bundle.baseName);
            continue;
        }
        bundle.sourceResult = sourceResult;

        const zhKeys = sourceResult.entries.map((e) => e.key);
        const zhMap = new Map(sourceResult.entries.map((e) => [e.key, e.value]));

        for (const zhKey of zhKeys) {
            if (zhMap.get(zhKey) === '') {
                fail(catalog.sourceLocale, bundle.module, bundle.baseName, bundle.bundleId,
                    sourceFile.relPath, zhKey, 'empty', 'source value is empty');
            }
        }

        for (const descriptor of catalog.locales) {
            if (descriptor.status === 'source') {
                continue;
            }
            const file = bundle.files[descriptor.tag];
            const result = file ? parsed.get(file.relPath) : null;

            if (!file || !result) {
                if (descriptor.status === 'supported' || descriptor.status === 'candidate') {
                    missingFileIssues({ issues }, issues, fail, catalog, bundle, descriptor);
                    // coverage：translated = 0，missing = 源 key 数
                    coverage.push(coverageRow(catalog, descriptor, bundle, zhKeys.length,
                        { missing: zhKeys.length, empty: 0, stale: 0, translationUnaccepted: 0, newUnaccepted: 0, invalid: 0, extra: 0 },
                        0));
                }
                // disabled 缺文件不报告
                continue;
            }

            if (descriptor.status === 'disabled') {
                // disabled：只要求可解析、Unicode escape 合法、无重复 key（已全局检查）；
                // 不比较 key parity、不查 missing/empty/stale/placeholder/coverage，不生成翻译任务。
                continue;
            }

            const severityFor = descriptor.status === 'candidate' ? 'report' : 'error';
            const localeMap = new Map(result.entries.map((e) => [e.key, e.value]));
            const counts = { missing: 0, empty: 0, stale: 0, translationUnaccepted: 0, newUnaccepted: 0, invalid: 0, extra: 0 };
            let translated = 0;

            for (const key of result.entries.map((e) => e.key)) {
                if (!zhMap.has(key)) {
                    counts.extra += 1;
                    fail(descriptor.tag, bundle.module, bundle.baseName, bundle.bundleId,
                        file.relPath, key, 'extra', 'key exists only in ' + descriptor.tag);
                }
            }

            for (const key of zhKeys) {
                if (!localeMap.has(key)) {
                    counts.missing += 1;
                    issues.push({
                        locale: descriptor.tag, module: bundle.module, baseName: bundle.baseName,
                        bundle: bundle.bundleId, file: file.relPath, key,
                        type: 'missing', severity: severityFor,
                        message: 'key missing in ' + descriptor.tag,
                        sourceValue: zhMap.get(key),
                        placeholders: placeholders.tokens(zhMap.get(key)),
                        sourceFile: sourceFile.relPath,
                        targetFile: file.relPath,
                    });
                    continue;
                }
                const value = localeMap.get(key);
                if (value === '') {
                    counts.empty += 1;
                    issues.push({
                        locale: descriptor.tag, module: bundle.module, baseName: bundle.baseName,
                        bundle: bundle.bundleId, file: file.relPath, key,
                        type: 'empty', severity: severityFor,
                        message: 'translation value is empty',
                        sourceValue: zhMap.get(key),
                        translationValue: value,
                        sourceFile: sourceFile.relPath,
                        targetFile: file.relPath,
                    });
                    continue;
                }
                const quality = placeholders.checkTranslation(zhMap.get(key), value);
                if (quality.errors.length > 0) {
                    counts.invalid += 1;
                    fail(descriptor.tag, bundle.module, bundle.baseName, bundle.bundleId,
                        file.relPath, key, 'invalid', quality.errors[0],
                        {
                            placeholders: placeholders.tokens(zhMap.get(key)),
                            sourceValue: zhMap.get(key),
                            translationValue: value,
                            sourceFile: sourceFile.relPath,
                            targetFile: file.relPath,
                        });
                    continue;
                }
                for (const message of quality.warnings) {
                    warn(descriptor.tag, bundle.module, bundle.baseName, bundle.bundleId,
                        key, 'warning:' + message.split(':')[0], message);
                }

                // accepted / source-stale / translation-unaccepted / new-unaccepted
                const sourceHash = staleLock.hashValue(zhMap.get(key));
                const translationHash = staleLock.hashValue(value);
                const lockEntry = lockIndex.get(staleLock.entryKey({
                    locale: descriptor.tag, module: bundle.module, baseName: bundle.baseName, key,
                }));
                const issueBase = {
                    locale: descriptor.tag, module: bundle.module, baseName: bundle.baseName,
                    bundle: bundle.bundleId, file: file.relPath, key,
                    sourceValue: zhMap.get(key),
                    translationValue: value,
                    placeholders: placeholders.tokens(zhMap.get(key)),
                    sourceFile: sourceFile.relPath,
                    targetFile: file.relPath,
                };
                if (!lockEntry) {
                    counts.newUnaccepted += 1;
                    issues.push({
                        ...issueBase,
                        type: 'new-unaccepted', severity: severityFor,
                        message: 'key not yet accepted; review the translation and run i18n:accept',
                    });
                } else if (lockEntry.acceptedSourceHash !== sourceHash) {
                    counts.stale += 1;
                    issues.push({
                        ...issueBase,
                        type: 'stale', severity: severityFor,
                        message: 'source changed since accepted baseline; review translation and run i18n:accept',
                    });
                } else if (lockEntry.acceptedTranslationHash !== translationHash) {
                    counts.translationUnaccepted += 1;
                    issues.push({
                        ...issueBase,
                        type: 'translation-unaccepted', severity: severityFor,
                        message: 'translation changed since accepted baseline; review and run i18n:accept',
                    });
                } else {
                    translated += 1;
                }
            }

            coverage.push(coverageRow(catalog, descriptor, bundle, zhKeys.length, counts, translated));
        }
    }

    // ---- 静态生成资源同步（同一快照）----
    checkStaticSync(snapshotRoot, issues, fail);

    // ---- 硬编码语言守卫 ----
    for (const hit of guard.scan(snapshotRoot, catalog)) {
        fail(null, null, null, null, hit.relPath, null, 'hardcoded-locale',
            hit.label + ' at line ' + hit.line + ': ' + hit.text);
    }

    issues.sort(compareIssues);
    warnings.sort(compareWarnings);

    return { catalog, issues, coverage, warnings };
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

export { runCheck, REPO_ROOT };

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}
