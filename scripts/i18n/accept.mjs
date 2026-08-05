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
 *   node scripts/i18n/accept.mjs --locale en-US --allow-unchanged   # 危险：允许确认未修改的 stale 翻译
 *   node scripts/i18n/accept.mjs --bootstrap                        # 一次性初始基线（要求 100% 合法）
 *
 * 安全要求：
 * - 先执行结构校验（catalog / properties / 重复 key / 空源值 / 占位符）；
 * - stale 后翻译内容与上次 accepted 完全没变时默认拒绝；
 * - 检查器绝不自动修改翻译内容（accept 只写锁文件）。
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
    const args = { locale: null, module: null, namespace: null, key: null, allowUnchanged: false, bootstrap: false };
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
        } else if (arg === '--allow-unchanged') {
            args.allowUnchanged = true;
        } else if (arg === '--bootstrap') {
            args.bootstrap = true;
        }
    }
    return args;
}

/**
 * @returns {{ok: boolean, updated: number, refused: Array<string>, messages: Array<string>}}
 */
export function runAccept(repoRoot, args) {
    const catalog = catalogLib.load(repoRoot);
    const messages = [];

    let targetLocales;
    if (args.bootstrap) {
        targetLocales = catalog.locales.filter((d) => d.status === 'supported');
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
        targetLocales = [descriptor];
    }

    if (args.allowUnchanged) {
        messages.push('WARNING: --allow-unchanged enabled — stale translations whose content is unchanged '
            + 'from the last accepted baseline will be re-accepted; make sure this is intentional.');
    }

    // ---- 结构校验（accept 必须先通过）----
    const discovery = discover.discover(repoRoot, catalog);
    const problems = [];
    const parsed = new Map();
    for (const entry of discovery.rawFiles) {
        const result = parser.parse(fs.readFileSync(entry.filePath, 'utf8'));
        parsed.set(entry.relPath, result);
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
    if (problems.length > 0) {
        return { ok: false, updated: 0, refused: problems, messages };
    }

    // ---- bootstrap 前置条件：supported 语言 100% 合法 ----
    if (args.bootstrap) {
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
    }

    // ---- 收集待接受条目 ----
    const lock = staleLock.load(repoRoot);
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
                refused.push(bundle.bundleId + ': missing ' + descriptor.tag + ' file; missing translations cannot be accepted');
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
                if (existing && existing.acceptedSourceHash === sourceHash) {
                    continue; // 未 stale，无需更新
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
                if (existing && existing.acceptedTranslationHash === translationHash
                    && !args.allowUnchanged && !args.bootstrap) {
                    refused.push(bundle.bundleId + ' ' + key
                        + ': translation unchanged since last acceptance (source changed); refusing — '
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

function main() {
    const args = parseArgs(process.argv.slice(2));
    let result;
    try {
        result = runAccept(REPO_ROOT, args);
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
