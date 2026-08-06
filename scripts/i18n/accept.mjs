#!/usr/bin/env node
'use strict';
/**
 * 接受已审核翻译，更新 i18n/catalog-lock.json 基线。
 *
 * 用法：
 *   node scripts/i18n/accept.mjs --locale en-US
 *   node scripts/i18n/accept.mjs --locale en-US --module <module>
 *   node scripts/i18n/accept.mjs --locale en-US --namespace <namespace>
 *   node scripts/i18n/accept.mjs --locale en-US --key <key>
 *   node scripts/i18n/accept.mjs --locale en-US --allow-unchanged   # 危险：确认源变而翻译未变的条目
 *   node scripts/i18n/accept.mjs --bootstrap                        # 一次性初始基线（要求 supported 100%）
 *   node scripts/i18n/accept.mjs --bootstrap --force                # 迁移参数：重建完整基线（仅 supported）
 *   node scripts/i18n/accept.mjs --prune                            # 清理 orphan / disabled 历史 lock entry
 *
 * 审核状态机（每 (locale, module, baseName, key)）：
 *   accepted                currentSource == acceptedSource && currentTranslation == acceptedTranslation
 *   translation-unaccepted  currentSource == acceptedSource && currentTranslation != acceptedTranslation
 *   source-stale            currentSource != acceptedSource
 *   new-unaccepted          无 lock entry
 * 状态转换：
 *   - translation-unaccepted：人工审核后 accept → 只更新 acceptedTranslationHash；
 *   - source-stale 且翻译未变：默认拒绝（source changed, translation unchanged since last accepted baseline），
 *     仅 --allow-unchanged 显式确认（CI=true 时拒绝）；
 *   - source-stale 且翻译也变：结构与占位符合法时允许，同时更新两个 hash；
 *   - new-unaccepted：翻译非空且合法时建立初始记录；
 *   - bootstrap 仅用于完整基线：只作用于 supported、要求 100%、lock 非空时默认拒绝，
 *     除非显式 --force（重建完整基线：丢弃 orphan / candidate / disabled / 已删除 key / 未知 locale）；
 *   - disabled locale 一律拒绝（outside translation coverage and review）；
 *   - prune 删除 orphan 与 disabled 历史条目（不得为 disabled 新增锁记录）。
 *
 * 核心 / CLI 边界分离：
 * - runAcceptCore(repoRoot, args)：纯核心状态机，不读取 process.env、不判断 CI、
 *   不因调用者是测试改变翻译校验规则；测试夹具直接调用它做 bootstrap；
 * - validateCliPolicy(args, environment)：CLI 边界安全策略，由 main() 显式传入环境；
 * - main() 是唯一读取 process.env 的位置：CI=true 时拒绝 --bootstrap / --allow-unchanged / --force。
 *
 * 安全要求：
 * - 先执行结构校验（catalog / properties / 重复 key / 空源值 / 占位符）；
 * - 检查器绝不自动修改翻译内容（accept 只写锁文件）；
 * - CI=true 时拒绝 --allow-unchanged、--bootstrap 与 --force。
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import catalogLib from './lib/catalog.mjs';
import parser from './lib/properties-parser.mjs';
import discover from './lib/discover-bundles.mjs';
import placeholders from './lib/placeholders.mjs';
import staleLock from './lib/stale-lock.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function parseArgs(argv) {
    const args = {
        locale: null, module: null, namespace: null, key: null,
        allowUnchanged: false, bootstrap: false, force: false, prune: false,
        repoRoot: null,
    };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        if (arg === '--locale') {
            args.locale = argv[++i];
        } else if (arg === '--module') {
            args.module = argv[++i];
        } else if (arg === '--namespace') {
            args.namespace = argv[++i];
        } else if (arg === '--key') {
            args.key = argv[++i];
        } else if (arg === '--repo-root') {
            args.repoRoot = argv[++i];
        } else if (arg === '--allow-unchanged') {
            args.allowUnchanged = true;
        } else if (arg === '--bootstrap') {
            args.bootstrap = true;
        } else if (arg === '--force') {
            args.force = true;
        } else if (arg === '--prune') {
            args.prune = true;
        }
    }
    return args;
}

/**
 * CLI 边界安全策略：只有 main() 调用，CI 判定来自显式传入的环境对象。
 * 核心库函数绝不读取 process.env。
 */
export function validateCliPolicy(args, environment) {
    const isCI = environment && (environment.isCI === true
        || environment.isCI === 'true' || environment.isCI === '1');
    if (isCI && (args.bootstrap || args.allowUnchanged || args.force)) {
        throw new Error('CI=true: refusing --bootstrap / --allow-unchanged / --force '
            + '(dangerous acceptance modes are for manual review only)');
    }
}

/** 结构校验：重复 key / 解析错误 / 未知后缀，accept 必须先通过。 */
function structuralProblems(discovery, parsed) {
    const problems = [];
    for (const entry of discovery.rawFiles) {
        const result = parsed.get(entry.relPath);
        if (!result) {
            continue;
        }
        for (const error of result.errors) {
            problems.push(entry.relPath + ': line ' + error.line + ': ' + error.message);
        }
        for (const dup of result.duplicateKeys) {
            problems.push(entry.relPath + ': duplicate key "' + dup.key + '" at lines ' + dup.lines.join(', '));
        }
    }
    for (const entry of discovery.unknownSuffixFiles) {
        problems.push(entry.relPath + ': unknown locale suffix "' + entry.suffix + '"');
    }
    return problems;
}

/**
 * 纯核心 accept 状态机（可测试库函数）。
 * 不读取 process.env、不判断 CI、无任何测试后门；翻译校验规则与 CLI 完全一致。
 * @returns {{ok: boolean, updated: number, refused: Array<string>, messages: Array<string>}}
 */
export function runAcceptCore(repoRoot, args) {
    const catalog = catalogLib.load(repoRoot);
    const messages = [];

    let targetLocales;
    if (args.bootstrap) {
        targetLocales = catalog.locales.filter((d) => d.status === 'supported');
    } else if (args.prune) {
        targetLocales = [];
    } else {
        if (!args.locale) {
            throw new Error('i18n:accept requires --locale <tag> (or --bootstrap)');
        }
        const descriptor = catalogLib.descriptorByTag(catalog, args.locale);
        if (!descriptor) {
            throw new Error('i18n:accept: unknown locale: ' + args.locale);
        }
        if (descriptor.status === 'source') {
            throw new Error('i18n:accept: cannot accept the source locale (' + descriptor.tag + ')');
        }
        if (descriptor.status === 'disabled') {
            throw new Error('i18n:accept: disabled locale cannot be accepted because it is outside '
                + 'translation coverage and review (' + descriptor.tag + ')');
        }
        targetLocales = [descriptor];
    }

    if (args.allowUnchanged) {
        messages.push('WARNING: --allow-unchanged enabled — entries whose source changed but whose '
            + 'translation is unchanged since the last accepted baseline will be re-accepted; '
            + 'make sure this is intentional.');
    }

    // ---- 结构校验（accept 必须先通过）----
    const discovery = discover.discover(repoRoot, catalog);
    const parsed = new Map();
    for (const entry of discovery.rawFiles) {
        parsed.set(entry.relPath, parser.parse(fs.readFileSync(entry.filePath, 'utf8')));
    }
    const problems = structuralProblems(discovery, parsed);
    if (problems.length > 0) {
        return { ok: false, updated: 0, refused: problems, messages };
    }

    const lock = staleLock.load(repoRoot);

    // ---- prune：删除已确认不再存在的 bundle / key / locale 条目，以及 disabled 历史条目 ----
    if (args.prune) {
        const sourceMaps = buildSourceMaps(discovery, parsed, catalog);
        const { errors } = staleLock.validateAgainstCatalog(lock, catalog, discovery.bundles, sourceMaps);
        if (errors.length > 0 && errors.some((e) => !/unknown locale/.test(e))) {
            return { ok: false, updated: 0, refused: errors, messages };
        }
        const removed = staleLock.prune(repoRoot, lock, catalog, discovery.bundles, sourceMaps);
        return {
            ok: true,
            updated: 0,
            refused: [],
            messages: ['pruned ' + removed + ' orphan lock entr' + (removed === 1 ? 'y' : 'ies') + '.'],
        };
    }

    // ---- bootstrap 前置条件 ----
    if (args.bootstrap) {
        if (args.force && (args.module || args.namespace || args.key)) {
            return {
                ok: false, updated: 0,
                refused: ['--bootstrap --force rebuilds the complete baseline and cannot be combined '
                    + 'with --module / --namespace / --key'],
                messages,
            };
        }
        if (lock.entries.length > 0 && !args.force) {
            return {
                ok: false, updated: 0,
                refused: ['lock is not empty (' + lock.entries.length + ' entries); bootstrap is only for the '
                    + 'initial baseline. Pass --force only as an explicit migration step.'],
                messages,
            };
        }
        const bootstrapProblems = [];
        for (const bundle of [...discovery.bundles.values()]) {
            const zhFile = bundle.files[catalog.sourceLocale];
            if (!zhFile) {
                bootstrapProblems.push(bundle.bundleId + ': missing source bundle');
                continue;
            }
            const zhResult = parsed.get(zhFile.relPath);
            const zhMap = new Map(zhResult.entries.map((e) => [e.key, e.value]));
            for (const [key, value] of zhMap) {
                if (value === '') {
                    bootstrapProblems.push(bundle.bundleId + ': empty source value for ' + key);
                }
            }
            for (const descriptor of targetLocales) {
                const file = bundle.files[descriptor.tag];
                const result = file ? parsed.get(file.relPath) : null;
                if (!result) {
                    bootstrapProblems.push(bundle.bundleId + ': missing ' + descriptor.tag + ' file');
                    continue;
                }
                const localeMap = new Map(result.entries.map((e) => [e.key, e.value]));
                for (const key of zhMap.keys()) {
                    if (!localeMap.has(key)) {
                        bootstrapProblems.push(bundle.bundleId + ': ' + descriptor.tag + ' missing key ' + key);
                        continue;
                    }
                    if (localeMap.get(key) === '') {
                        bootstrapProblems.push(bundle.bundleId + ': ' + descriptor.tag + ' empty value for ' + key);
                        continue;
                    }
                    const quality = placeholders.checkTranslation(zhMap.get(key), localeMap.get(key));
                    for (const message of quality.errors) {
                        bootstrapProblems.push(bundle.bundleId + ': ' + descriptor.tag + ' ' + key + ': ' + message);
                    }
                }
                for (const key of localeMap.keys()) {
                    if (!zhMap.has(key)) {
                        bootstrapProblems.push(bundle.bundleId + ': ' + descriptor.tag + ' extra key ' + key);
                    }
                }
            }
        }
        if (bootstrapProblems.length > 0) {
            return { ok: false, updated: 0, refused: bootstrapProblems, messages };
        }
        if (args.force) {
            return rebuildLock(repoRoot, catalog, discovery, parsed, targetLocales, messages);
        }
    }

    // ---- 收集待接受条目（状态机）----
    const lockIndex = staleLock.index(lock);
    const updated = [];
    const refused = [];

    for (const descriptor of targetLocales) {
        for (const bundle of [...discovery.bundles.values()]) {
            if (args.module && bundle.module !== args.module) {
                continue;
            }
            if (args.namespace && discover.namespaceOf(bundle.baseName) !== args.namespace) {
                continue;
            }
            const zhFile = bundle.files[catalog.sourceLocale];
            const zhResult = zhFile ? parsed.get(zhFile.relPath) : null;
            if (!zhResult) {
                continue;
            }
            const file = bundle.files[descriptor.tag];
            const result = file ? parsed.get(file.relPath) : null;
            if (!result) {
                refused.push(bundle.bundleId + ': missing ' + descriptor.tag + ' file; '
                    + 'missing translations cannot be accepted');
                continue;
            }
            const zhMap = new Map(zhResult.entries.map((e) => [e.key, e.value]));
            const localeMap = new Map(result.entries.map((e) => [e.key, e.value]));

            for (const [key, zhValue] of zhMap) {
                if (args.key && key !== args.key) {
                    continue;
                }
                const base = {
                    locale: descriptor.tag,
                    module: bundle.module,
                    baseName: bundle.baseName,
                    key,
                };
                const existing = lockIndex.get(staleLock.entryKey(base));
                const sourceHash = staleLock.hashValue(zhValue);

                if (existing
                    && existing.acceptedSourceHash === sourceHash
                    && existing.acceptedTranslationHash === staleLock.hashValue(localeMap.get(key))) {
                    continue; // 已 accepted，无需更新
                }

                const translation = localeMap.get(key);
                if (translation == null || translation === '') {
                    refused.push(bundle.bundleId + ' ' + key + ': missing/empty translation, cannot accept');
                    continue;
                }
                const quality = placeholders.checkTranslation(zhValue, translation);
                if (quality.errors.length > 0) {
                    refused.push(bundle.bundleId + ' ' + key + ': ' + quality.errors.join('; '));
                    continue;
                }
                const translationHash = staleLock.hashValue(translation);

                if (existing && existing.acceptedSourceHash !== sourceHash
                    && existing.acceptedTranslationHash === translationHash
                    && !args.allowUnchanged) {
                    // source-stale + 翻译未变：默认拒绝
                    refused.push(bundle.bundleId + ' ' + key
                        + ': source changed but translation unchanged since last accepted baseline; '
                        + 'review the translation first, or pass --allow-unchanged to confirm');
                    continue;
                }

                updated.push({
                    ...base,
                    acceptedSourceHash: sourceHash,
                    acceptedTranslationHash: translationHash,
                });
            }
        }
    }

    if (refused.length > 0) {
        return { ok: false, updated: 0, refused, messages };
    }

    if (updated.length === 0) {
        return { ok: true, updated: 0, refused: [], messages };
    }

    for (const entry of updated) {
        lockIndex.set(staleLock.entryKey(entry), entry);
    }
    lock.entries = [...lockIndex.values()];
    staleLock.save(repoRoot, lock);

    return {
        ok: true,
        updated: updated.length,
        refused: [],
        messages: [
            'accepted ' + updated.length + ' translation(s) for '
                + targetLocales.map((d) => d.tag).join(', ') + '.',
            'updated: i18n/catalog-lock.json',
            ...(args.bootstrap ? ['(initial baseline established)'] : []),
        ],
    };
}

/** bundleId → 源 key → 源 value 的映射（用于 lock orphan 校验）。 */
function buildSourceMaps(discovery, parsed, catalog) {
    const maps = new Map();
    for (const bundle of discovery.bundles.values()) {
        const zhFile = bundle.files[catalog.sourceLocale];
        if (!zhFile) {
            continue;
        }
        const result = parsed.get(zhFile.relPath);
        if (!result) {
            continue;
        }
        maps.set(bundle.bundleId, new Map(result.entries.map((e) => [e.key, e.value])));
    }
    return maps;
}

/**
 * --bootstrap --force：用当前 source + 当前 supported 重建完整基线。
 * 前置条件（supported 100%、结构合法）已由调用方验证。
 * 新建空 lock index，只写入当前 catalog 中 supported locale、当前存在的 bundle、当前存在的 source key；
 * 不保留已删除 key / 已删除 bundle / 旧 candidate / disabled / 未知 locale；原子替换；输出统计。
 * @returns {{ok: boolean, updated: number, refused: Array<string>, messages: Array<string>}}
 */
function rebuildLock(repoRoot, catalog, discovery, parsed, targetLocales, messages) {
    const rebuilt = [];
    const lockIndex = new Map();
    for (const descriptor of targetLocales) {
        for (const bundle of [...discovery.bundles.values()]) {
            const zhFile = bundle.files[catalog.sourceLocale];
            const zhResult = zhFile ? parsed.get(zhFile.relPath) : null;
            if (!zhResult) {
                continue;
            }
            const file = bundle.files[descriptor.tag];
            const result = file ? parsed.get(file.relPath) : null;
            if (!result) {
                continue;
            }
            const zhMap = new Map(zhResult.entries.map((e) => [e.key, e.value]));
            const localeMap = new Map(result.entries.map((e) => [e.key, e.value]));
            for (const key of zhMap.keys()) {
                const entry = {
                    locale: descriptor.tag,
                    module: bundle.module,
                    baseName: bundle.baseName,
                    key,
                    acceptedSourceHash: staleLock.hashValue(zhMap.get(key)),
                    acceptedTranslationHash: staleLock.hashValue(localeMap.get(key)),
                };
                lockIndex.set(staleLock.entryKey(entry), entry);
            }
        }
    }
    const rebuiltEntries = [...lockIndex.values()];
    const previous = staleLock.load(repoRoot).entries.length;
    staleLock.save(repoRoot, { version: staleLock.LOCK_VERSION, entries: rebuiltEntries });
    messages.push('force bootstrap: rebuilt the complete baseline '
        + (previous > 0 ? 'from ' + previous + ' old lock entry(ies), ' : '')
        + 'wrote ' + rebuiltEntries.length + ' current supported entr'
        + (rebuiltEntries.length === 1 ? 'y' : 'ies') + '.');
    return {
        ok: true,
        updated: rebuiltEntries.length,
        refused: [],
        messages: [
            'force bootstrap: rebuilt ' + rebuiltEntries.length + ' translation(s) for '
                + targetLocales.map((d) => d.tag).join(', ') + '.',
            'updated: i18n/catalog-lock.json',
            ...messages,
        ],
    };
}

/**
 * 向后兼容别名：与 runAcceptCore 完全等价（不含 CLI 安全策略）。
 * 测试与既有调用方（check.test.mjs）继续可用；CI 策略只由 validateCliPolicy + main() 负责。
 */
export function runAccept(repoRoot, args) {
    return runAcceptCore(repoRoot, args);
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    const repoRoot = args.repoRoot ? path.resolve(args.repoRoot) : REPO_ROOT;
    let result;
    try {
        const environment = {
            isCI: process.env.CI === 'true' || process.env.CI === '1',
        };
        validateCliPolicy(args, environment);
        result = runAcceptCore(repoRoot, args);
    } catch (e) {
        console.error('i18n:accept ERROR: ' + e.message);
        process.exit(2);
        return;
    }
    for (const message of result.messages) {
        if (message.startsWith('WARNING:')) {
            console.warn('');
            console.warn('  !!! ' + message + ' !!!');
            console.warn('');
        } else {
            console.log(message);
        }
    }
    if (!result.ok) {
        console.error('i18n:accept: refused ' + result.refused.length + ' item(s), lock not updated:');
        for (const item of result.refused.slice(0, 50)) {
            console.error('  - ' + item);
        }
        process.exit(1);
        return;
    }
    if (result.updated === 0) {
        console.log('i18n:accept: nothing to update (no stale entries in scope).');
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}
