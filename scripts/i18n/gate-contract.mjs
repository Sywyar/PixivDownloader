#!/usr/bin/env node
'use strict';
/**
 * 可信 Gate Contract：由 trusted anchor 物化并执行，黑盒验证候选 gate（index 或 ref）。
 *
 * 用法：
 *   node gate-contract.mjs --repo-root <repo> --candidate-snapshot index
 *   node gate-contract.mjs --repo-root <repo> --candidate-ref <sha>
 *   node gate-contract.mjs --version
 *
 * 信任模型：
 * - 本脚本自身必须运行在 trusted anchor 物化的 gate bundle 内（同目录 gate-policy.json 是
 *   可信事实）；候选快照只作为被检查对象，candidate 的 checker / contract / guard 不能自批准；
 * - candidate 可以提出新 policy，但旧 trusted contract 必须审核它：contractVersion 不得降低、
 *   i18nEnforcementStartCommit 不得向后移动或删除、required paths 不得减少（允许新增）；
 * - required paths 被候选删除 → fail closed（candidate gate bundle incomplete → fail closed）；
 *   candidate 早于某 required path 引入（enforcement start 自身等）→ 只报告不阻断；
 * - candidate checker 做黑盒行为测试：合法 fixture 必须通过，坏占位符 / 缺英文文件 / missing key /
 *   stale / translation-unaccepted / invalid lock / static 失步 / 硬编码语言必须失败；
 *   `--version` 只作为完整性检查，不能作为安全判定；
 * - candidate hooks 实际运行验证（pre-commit 使用 trusted anchor、no-op checker 仍失败、
 *   删除 required checker 失败、pre-push 使用 trusted anchor、guard no-op 被 trusted guard 发现）；
 * - 自保护：构造「下一代恶意 gate」（check.mjs / pre-commit / pre-push 全部 exit 0），
 *   运行 candidate contract 必须拒绝它——即 candidate contract 自身也不能被简单弱化；
 * - 归纳跳过：candidate 的某部分与 trusted bundle 逐字节一致时，其行为由 trust 链归纳保证，
 *   跳过对应行为测试（只在行为可能被候选改变时才黑盒运行）。
 *
 * 本地 Git hooks 是开发便利性门禁，用户始终可以主动修改 hook、修改 .git/config 或使用
 * --no-verify，因此不能宣称其绝对不可绕过。真正的最终门禁必须由 GitHub Ruleset /
 * 分支保护 / required check 提供，并且可信 workflow 或检查器不能由同一个候选提交自行批准。
 */

import { execFileSync, spawnSync } from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath } from 'url';

import trustedGate from './lib/trusted-gate.mjs';
import snapshot from './lib/repository-snapshot.mjs';

const CONTRACT_VERSION = '1';
const OWN_DIR = path.dirname(fileURLToPath(import.meta.url));

const APP_I18N = path.posix.join('pixivdownload-app', 'src', 'main', 'resources', 'i18n');
const STATIC_REL = path.posix.join('pixivdownload-app', 'src', 'main', 'resources', 'static', 'i18n-static');

const CATALOG_BASIC = `{
  "schemaVersion": 1,
  "sourceLocale": "zh-CN",
  "defaultLocale": "en-US",
  "fallbackLocale": "en-US",
  "languageCookieName": "pixiv_lang",
  "languageParameterName": "lang",
  "locales": [
    {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh", "zh-Hans"]},
    {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["en"]}
  ]
}`;

const CATALOG_WITH_DISABLED = `{
  "schemaVersion": 1,
  "sourceLocale": "zh-CN",
  "defaultLocale": "en-US",
  "fallbackLocale": "en-US",
  "languageCookieName": "pixiv_lang",
  "languageParameterName": "lang",
  "locales": [
    {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh", "zh-Hans"]},
    {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["en"]},
    {"tag": "es-ES", "nativeName": "Español", "resourceSuffix": "es", "status": "disabled", "direction": "ltr", "aliases": ["es"]}
  ]
}`;

const GOOD_ZH = 'greeting=你好 {name}\ntitle=作品标题\n';
const GOOD_EN = 'greeting=Hello {name}\ntitle=Artwork title\n';
const BAD_EN = 'greeting=Hello {wrong}\ntitle=Artwork title\n';
const ZH_CHANGED = 'greeting=你好呀 {name}\ntitle=作品标题\n';
const EN_CHANGED = 'greeting=Hello there {name}\ntitle=Artwork title\n';
const EN_MISSING_TITLE = 'greeting=Hello {name}\n';

function fail(message) {
    console.error('gate-contract ERROR: ' + message);
    process.exit(2);
}

function git(args, repoRoot, opts = {}) {
    return execFileSync('git', args, {
        cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], ...opts,
    }).trim();
}

function hasBash() {
    try {
        execFileSync('bash', ['--version'], { stdio: 'ignore' });
        return true;
    } catch (e) {
        return false;
    }
}

function run(args, opts = {}) {
    const result = spawnSync(args[0], args.slice(1), {
        encoding: 'utf8', maxBuffer: 128 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe'], ...opts,
    });
    // spawnSync 的 .output 是 buffer 数组；统一为字符串（status + stdout + stderr）
    return { status: result.status, output: (result.stdout || '') + (result.stderr || '') };
}

function tempDir(label) {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-gate-contract-' + label + '-'));
}

function rmrf(dir) {
    for (let attempt = 0; attempt < 6; attempt += 1) {
        try {
            fs.rmSync(dir, { recursive: true, force: true });
            return;
        } catch (e) {
            if (attempt === 5) {
                throw e;
            }
            execFileSync('bash', ['-c', 'sleep 0.3'], { stdio: 'ignore' });
        }
    }
}

function writeFile(root, rel, content) {
    const file = path.join(root, ...rel.split('/'));
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content, 'utf8');
}

function fileUrl(file) {
    return 'file://' + path.resolve(file).split(path.sep)
        .map((seg, i) => (i === 0 ? encodeURI(seg) : encodeURIComponent(seg))).join('/');
}

/** bash 参数里的路径必须用正斜杠（反斜杠会被 bash 当作转义吃掉）。 */
function toPosix(p) {
    return p.split(path.sep).join('/');
}

async function importScript(file) {
    return import(fileUrl(file));
}

/** 目录逐字节比较（可排除相对子路径）。 */
function dirsIdentical(a, b, { exclude = [] } = {}) {
    const normalized = exclude.map((e) => e.split(path.sep).join('/'));
    const walk = (dirA, dirB, rel) => {
        let entriesA;
        try {
            entriesA = fs.readdirSync(dirA, { withFileTypes: true }).sort((x, y) => x.name.localeCompare(y.name));
        } catch (e) {
            return false;
        }
        let entriesB;
        try {
            entriesB = fs.readdirSync(dirB, { withFileTypes: true }).sort((x, y) => x.name.localeCompare(y.name));
        } catch (e) {
            return false;
        }
        if (entriesA.length !== entriesB.length) {
            return false;
        }
        for (let i = 0; i < entriesA.length; i += 1) {
            const aEntry = entriesA[i];
            const bEntry = entriesB[i];
            if (aEntry.name !== bEntry.name) {
                return false;
            }
            const relPath = rel ? rel + '/' + aEntry.name : aEntry.name;
            if (normalized.includes(relPath)) {
                continue;
            }
            const fullA = path.join(dirA, aEntry.name);
            const fullB = path.join(dirB, bEntry.name);
            if (aEntry.isDirectory() !== bEntry.isDirectory()) {
                return false;
            }
            if (aEntry.isDirectory()) {
                if (!walk(fullA, fullB, relPath)) {
                    return false;
                }
            } else if (aEntry.isFile() && bEntry.isFile()) {
                const bufA = fs.readFileSync(fullA);
                const bufB = fs.readFileSync(fullB);
                if (!bufA.equals(bufB)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    };
    return walk(a, b, '');
}

// 进程退出（含 process.exit 的失败路径）也必须清理会话级临时目录
let pendingCandidateRoot = null;
process.on('exit', () => {
    try {
        if (pendingCandidateRoot) {
            fs.rmSync(pendingCandidateRoot, { recursive: true, force: true });
        }
    } catch (ignored) {
        // 退出清理失败不掩盖 verdict
    }
    try {
        snapshot.cleanupAll();
    } catch (ignored) {
        // 同上
    }
});

// ---------------------------------------------------------------------------
// 参数解析
// ---------------------------------------------------------------------------

function parseArgs(argv) {
    const args = { repoRoot: null, mode: null, ref: null, reportRoot: null, version: false };
    for (let i = 0; i < argv.length; i += 1) {
        if (argv[i] === '--repo-root') {
            args.repoRoot = argv[++i];
        } else if (argv[i] === '--candidate-snapshot') {
            args.mode = argv[++i];
        } else if (argv[i] === '--candidate-ref') {
            args.ref = argv[++i];
            args.mode = 'ref';
        } else if (argv[i] === '--report-root') {
            args.reportRoot = argv[++i];
        } else if (argv[i] === '--version') {
            args.version = true;
        } else {
            throw new Error('unknown argument: ' + argv[i]);
        }
    }
    if (!args.version && !args.repoRoot) {
        throw new Error('--repo-root <path> is required');
    }
    if (!args.version) {
        if (args.mode === 'index') {
            args.ref = null;
        } else if (args.mode === 'ref') {
            if (!args.ref) {
                throw new Error('--candidate-ref <sha> is required');
            }
        } else {
            throw new Error('--candidate-snapshot index or --candidate-ref <sha> is required');
        }
    }
    return args;
}

// ---------------------------------------------------------------------------
// 黑盒 checker 行为测试（对 candidate check.mjs）
// ---------------------------------------------------------------------------

function copyCandidateChecker(candidateRoot, fixtureRoot, { dropLib } = {}) {
    const src = path.join(candidateRoot, 'scripts', 'i18n');
    const dst = path.join(fixtureRoot, 'scripts', 'i18n');
    fs.mkdirSync(dst, { recursive: true });
    if (fs.existsSync(src)) {
        for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
            if (entry.name === 'test') {
                continue;
            }
            fs.cpSync(path.join(src, entry.name), path.join(dst, entry.name), { recursive: true });
        }
    }
    if (dropLib) {
        fs.rmSync(path.join(dst, 'lib', dropLib), { force: true });
    }
}

/** 构造一个 i18n 合法的 fixture 仓库（candidate checker + bundle + lock + 静态资源）。 */
async function buildCheckerFixture(candidateRoot, catalogJson, zh, en) {
    const fixture = tempDir('checker');
    copyCandidateChecker(candidateRoot, fixture);
    writeFile(fixture, APP_I18N + '/locales.json', catalogJson);
    writeFile(fixture, APP_I18N + '/web/common.properties', zh);
    writeFile(fixture, APP_I18N + '/web/common_en.properties', en);
    const accept = await importScript(path.join(fixture, 'scripts', 'i18n', 'accept.mjs'));
    const bootstrap = accept.runAcceptCore(fixture, { bootstrap: true });
    if (!bootstrap.ok) {
        throw new Error('fixture bootstrap failed: ' + bootstrap.refused.join('\n'));
    }
    const gen = await importScript(path.join(fixture, 'scripts', 'i18n', 'generate-static.mjs'));
    gen.runGenerate(fixture);
    return fixture;
}

function runChecker(fixture) {
    const checker = path.join(fixture, 'scripts', 'i18n', 'check.mjs');
    if (!fs.existsSync(checker)) {
        return { status: 2, output: 'candidate check.mjs missing' };
    }
    return run(['node', checker, '--repo-root', fixture], { cwd: fixture });
}

async function regen(fixture) {
    const gen = await importScript(path.join(fixture, 'scripts', 'i18n', 'generate-static.mjs'));
    gen.runGenerate(fixture);
}

/**
 * 黑盒场景矩阵：base fixture 原地演化，首个失败场景即止（报告保留全部已执行场景）。
 * @returns {Array<{name, kind, expected, status, ok, diagnostic}>}
 */
async function runCheckerScenarios(candidateRoot, hasChecker, skip) {
    const results = [];
    if (skip) {
        results.push({ name: 'checker behavior (black-box)', kind: 'black-box', expected: null,
            status: null, ok: true,
            diagnostic: 'candidate scripts/i18n is byte-identical to the trusted bundle; behavior is guaranteed by the trust chain (inductive skip)' });
        return results;
    }
    if (!hasChecker) {
        results.push({ name: 'checker-bundle', kind: 'report', expected: null, status: null, ok: true,
            diagnostic: 'candidate has no scripts/i18n/check.mjs (predates the i18n gate); black-box scenarios skipped' });
        return results;
    }

    let failed = false;
    async function scenario(name, expectedZero, mutate, extra) {
        if (failed) {
            return;
        }
        try {
            const result = await mutate();
            const ok = (result.status === 0) === expectedZero;
            results.push({
                name, kind: 'black-box', expected: expectedZero ? 'exit 0' : 'exit != 0',
                status: result.status, ok,
                diagnostic: ok ? '' : 'expected ' + (expectedZero ? 'exit 0' : 'exit != 0')
                    + ' but got exit ' + result.status + ': ' + (result.output || '').split('\n').slice(-12).join(' | '),
            });
            if (!ok) {
                failed = true;
            }
        } catch (e) {
            results.push({
                name, kind: 'black-box', expected: expectedZero ? 'exit 0' : 'exit != 0',
                status: null, ok: false, diagnostic: 'scenario failed to run: ' + e.message,
            });
            failed = true;
        } finally {
            if (extra) {
                rmrf(extra);
            }
        }
    }

    let base = await buildCheckerFixture(candidateRoot, CATALOG_BASIC, GOOD_ZH, GOOD_EN);

    await scenario('legal fixture (full zh/en, correct lock, synced static) must pass', true, async () => {
        return runChecker(base);
    });

    await scenario('bad placeholder must fail', false, async () => {
        writeFile(base, APP_I18N + '/web/common_en.properties', BAD_EN);
        await regen(base);
        return runChecker(base);
    });

    await scenario('missing en file must fail', false, async () => {
        fs.rmSync(path.join(base, ...(APP_I18N + '/web/common_en.properties').split('/')));
        await regen(base);
        return runChecker(base);
    });

    await scenario('missing key must fail', false, async () => {
        writeFile(base, APP_I18N + '/web/common_en.properties', EN_MISSING_TITLE);
        await regen(base);
        return runChecker(base);
    });

    await scenario('stale source hash must fail', false, async () => {
        writeFile(base, APP_I18N + '/web/common.properties', ZH_CHANGED);
        await regen(base);
        return runChecker(base);
    });

    await scenario('translation-unaccepted must fail', false, async () => {
        writeFile(base, APP_I18N + '/web/common.properties', GOOD_ZH);
        writeFile(base, APP_I18N + '/web/common_en.properties', EN_CHANGED);
        await regen(base);
        return runChecker(base);
    });

    await scenario('invalid lock must fail', false, async () => {
        writeFile(base, 'i18n/catalog-lock.json', JSON.stringify({ version: 999, entries: [] }, null, 2) + '\n');
        return runChecker(base);
    });

    await scenario('static out-of-sync must fail', false, async () => {
        writeFile(base, APP_I18N + '/web/common.properties', GOOD_ZH);
        writeFile(base, APP_I18N + '/web/common_en.properties', GOOD_EN);
        await regen(base);
        fs.rmSync(path.join(base, ...STATIC_REL.split('/'), 'meta.json'));
        return runChecker(base);
    });

    await scenario('hardcoded locale in business file must fail', false, async () => {
        writeFile(base, 'pixivdownload-app/src/main/resources/static/js/hack.js',
            "const supportedLocales = ['en-US'];\n");
        return runChecker(base);
    });

    // disabled 语言不完整：不因覆盖率失败
    let disabledFixture = await buildCheckerFixture(candidateRoot, CATALOG_WITH_DISABLED, GOOD_ZH, GOOD_EN);
    await scenario('disabled language incomplete must NOT fail coverage', true, async () => {
        return runChecker(disabledFixture);
    }, disabledFixture);

    rmrf(base);

    // candidate 缺文件：只报告，不阻断（broken checker 本身由 legal fixture 场景判失败）
    try {
        const partial = tempDir('checker-partial');
        copyCandidateChecker(candidateRoot, partial, { dropLib: 'placeholders.mjs' });
        writeFile(partial, APP_I18N + '/locales.json', CATALOG_BASIC);
        writeFile(partial, APP_I18N + '/web/common.properties', GOOD_ZH);
        writeFile(partial, APP_I18N + '/web/common_en.properties', GOOD_EN);
        const result = run(['node', path.join(partial, 'scripts', 'i18n', 'check.mjs'),
            '--repo-root', partial], { cwd: partial });
        results.push({
            name: 'candidate bundle missing auxiliary file (report-only)', kind: 'report',
            expected: null, status: result.status, ok: true,
            diagnostic: 'candidate checker crashed with exit ' + result.status
                + ' (expected for an incomplete bundle): '
                + (result.output || '').split('\n').slice(-6).join(' | '),
        });
        rmrf(partial);
    } catch (e) {
        results.push({
            name: 'candidate bundle missing auxiliary file (report-only)', kind: 'report',
            expected: null, status: null, ok: true, diagnostic: 'cannot probe incomplete bundle: ' + e.message,
        });
    }

    return results;
}

// ---------------------------------------------------------------------------
// candidate hooks 行为测试（实际运行 candidate hooks）
// ---------------------------------------------------------------------------

/**
 * 构造 hook 行为测试仓库：
 * - 工作树 = candidate 的 scripts/i18n + scripts/hooks（candidate hooks 是执行对象）；
 * - C1（root，enforcement start）= candidate bundle（无 policy）；
 * - C2 = 引入 policy（start = C1）；
 * - C3 = trusted anchor commit：把 gate bundle 替换为 trusted bundle（本 contract 自身目录），
 *   trustedGateRef = C3 —— 候选 hooks 运行时只能使用 trusted anchor 的 checker/contract/guard。
 */
async function makeContractRepo(candidateRoot, trustedPolicy) {
    const repo = tempDir('hooks');
    git(['init', '-q'], repo);
    git(['config', 'user.email', 't@example.com'], repo);
    git(['config', 'user.name', 'test'], repo);
    git(['config', 'core.autocrlf', 'false'], repo);
    fs.cpSync(path.join(candidateRoot, 'scripts', 'i18n'), path.join(repo, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(repo, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    fs.rmSync(path.join(repo, 'scripts', 'i18n', 'gate-policy.json'), { force: true });
    if (fs.existsSync(path.join(candidateRoot, 'scripts', 'hooks'))) {
        fs.cpSync(path.join(candidateRoot, 'scripts', 'hooks'), path.join(repo, 'scripts', 'hooks'), { recursive: true });
    }
    writeFile(repo, APP_I18N + '/locales.json', CATALOG_BASIC);
    writeFile(repo, APP_I18N + '/web/common.properties', GOOD_ZH);
    writeFile(repo, APP_I18N + '/web/common_en.properties', GOOD_EN);
    const accept = await importScript(path.join(repo, 'scripts', 'i18n', 'accept.mjs'));
    const bootstrap = accept.runAcceptCore(repo, { bootstrap: true });
    if (!bootstrap.ok) {
        throw new Error('fixture bootstrap failed: ' + bootstrap.refused.join('\n'));
    }
    const gen = await importScript(path.join(repo, 'scripts', 'i18n', 'generate-static.mjs'));
    gen.runGenerate(repo);
    git(['add', '--chmod=+x', 'scripts/hooks/pre-commit', 'scripts/hooks/pre-push', 'scripts/hooks/pre-push-guard.sh'], repo);
    git(['add', '-A'], repo);
    git(['commit', '-q', '-m', 'init'], repo); // C1
    const start = git(['rev-parse', 'HEAD'], repo);
    const policy = JSON.parse(JSON.stringify(trustedPolicy));
    policy.i18nEnforcementStartCommit = start;
    writeFile(repo, 'scripts/i18n/gate-policy.json', JSON.stringify(policy, null, 2) + '\n');
    git(['add', '-A'], repo);
    git(['commit', '-q', '-m', 'add gate policy'], repo); // C2
    // C3：trusted anchor commit（gate bundle 替换为 trusted bundle；OWN_DIR = <trusted>/scripts/i18n）
    // --allow-empty：candidate bundle 与 trusted bundle 一致时树无变化也必须有 anchor commit
    const trustedRoot = path.join(OWN_DIR, '..', '..');
    fs.rmSync(path.join(repo, 'scripts', 'i18n'), { recursive: true, force: true });
    fs.rmSync(path.join(repo, 'scripts', 'hooks'), { recursive: true, force: true });
    fs.cpSync(path.join(trustedRoot, 'scripts', 'i18n'), path.join(repo, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(repo, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    if (fs.existsSync(path.join(trustedRoot, 'scripts', 'hooks'))) {
        fs.cpSync(path.join(trustedRoot, 'scripts', 'hooks'), path.join(repo, 'scripts', 'hooks'), { recursive: true });
    }
    writeFile(repo, 'scripts/i18n/gate-policy.json', JSON.stringify(policy, null, 2) + '\n');
    git(['add', '-A'], repo);
    git(['commit', '-q', '--allow-empty', '-m', 'trust anchor'], repo); // C3
    const anchor = git(['rev-parse', 'HEAD'], repo);
    git(['config', '--local', 'core.hooksPath', 'scripts/hooks'], repo);
    git(['config', '--local', 'pixiv.i18n.trustedGateRef', anchor], repo);
    return repo;
}

async function runHookScenarios(candidateRoot, trustedPolicy, hasHooks, skip, skipReason) {
    const results = [];
    if (skip) {
        results.push({ name: 'hooks behavior (execution)', kind: 'hooks', expected: null, status: null, ok: true,
            diagnostic: skipReason || 'candidate scripts/hooks is byte-identical to the trusted bundle;'
                + ' behavior is guaranteed by the trust chain (inductive skip)' });
        return results;
    }
    if (!hasHooks) {
        results.push({ name: 'hooks-bundle', kind: 'report', expected: null, status: null, ok: true,
            diagnostic: 'candidate has no scripts/hooks; hook behavior scenarios skipped' });
        return results;
    }
    if (!hasBash()) {
        results.push({ name: 'hooks-execution', kind: 'report', expected: null, status: null, ok: true,
            diagnostic: 'bash is not available; hook execution scenarios skipped' });
    } else {
        const repo = await makeContractRepo(candidateRoot, trustedPolicy);
        try {
            const preCommit = path.join(repo, 'scripts', 'hooks', 'pre-commit');
            const prePush = path.join(repo, 'scripts', 'hooks', 'pre-push');

            async function hookScenario(name, expectedZero, fn) {
                const result = await fn();
                const ok = (result.status === 0) === expectedZero;
                results.push({
                    name, kind: 'hooks', expected: expectedZero ? 'exit 0' : 'exit != 0',
                    status: result.status, ok,
                    diagnostic: ok ? '' : 'expected ' + (expectedZero ? 'exit 0' : 'exit != 0')
                        + ' but got exit ' + result.status + ': '
                        + (result.output || '').split('\n').slice(-12).join(' | '),
                });
            }

            await hookScenario('candidate pre-commit uses trusted anchor: staged bad / worktree good must fail', false, async () => {
                writeFile(repo, APP_I18N + '/web/common_en.properties', BAD_EN);
                git(['add', '-A'], repo);
                writeFile(repo, APP_I18N + '/web/common_en.properties', GOOD_EN);
                return run(['bash', 'scripts/hooks/pre-commit'], { cwd: repo });
            });
            git(['reset', '-q', '--hard', 'HEAD'], repo, { stdio: 'ignore' });

            await hookScenario('candidate pre-commit: staged deletion of required checker must fail', false, async () => {
                git(['rm', '-q', '--cached', 'scripts/i18n/check.mjs'], repo);
                fs.rmSync(path.join(repo, 'scripts', 'i18n', 'check.mjs'));
                return run(['bash', 'scripts/hooks/pre-commit'], { cwd: repo });
            });
            git(['reset', '-q', '--hard', 'HEAD'], repo, { stdio: 'ignore' });

            await hookScenario('candidate pre-commit: staged deletion of required hook must fail', false, async () => {
                git(['rm', '-q', '--cached', 'scripts/hooks/pre-push'], repo);
                fs.rmSync(path.join(repo, 'scripts', 'hooks', 'pre-push'));
                return run(['bash', 'scripts/hooks/pre-commit'], { cwd: repo });
            });
            git(['reset', '-q', '--hard', 'HEAD'], repo, { stdio: 'ignore' });

            await hookScenario('candidate pre-commit: worktree checker no-op + staged bad translation must fail', false, async () => {
                writeFile(repo, APP_I18N + '/web/common_en.properties', BAD_EN);
                git(['add', '-A'], repo);
                writeFile(repo, 'scripts/i18n/check.mjs', '#!/usr/bin/env node\nprocess.exit(0);\n');
                return run(['bash', 'scripts/hooks/pre-commit'], { cwd: repo });
            });
            git(['reset', '-q', '--hard', 'HEAD'], repo, { stdio: 'ignore' });

            // pre-push：candidate ref 自带 no-op checker（翻译合法）→ trusted contract 必须失败
            const remote = tempDir('remote');
            git(['init', '-q', '--bare', remote], repo);
            git(['remote', 'add', 'origin', remote], repo);
            git(['-c', 'core.hooksPath=/dev/null', 'push', '-q', 'origin', 'master'], repo, { stdio: 'ignore' });

            await hookScenario('candidate pre-push: candidate ref with no-op checker must fail (trusted contract)', false, async () => {
                writeFile(repo, 'scripts/i18n/check.mjs', '#!/usr/bin/env node\nprocess.exit(0);\n');
                git(['add', '-A'], repo);
                git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'noop checker'], repo);
                const result = run(['bash', 'scripts/hooks/pre-push', 'origin', remote], { cwd: repo });
                git(['reset', '-q', '--hard', 'HEAD~1'], repo, { stdio: 'ignore' });
                return result;
            });
            rmrf(remote);

            // pre-push：candidate guard no-op + 标记 → trusted guard（anchor）仍发现
            const remote2 = tempDir('remote2');
            git(['init', '-q', '--bare', remote2], repo);
            git(['remote', 'add', 'origin2', remote2], repo);
            git(['-c', 'core.hooksPath=/dev/null', 'push', '-q', 'origin2', 'master'], repo, { stdio: 'ignore' });

            await hookScenario('candidate pre-push: guard no-op + marker commit must fail (trusted guard)', false, async () => {
                writeFile(repo, 'scripts/hooks/pre-push-guard.sh', '#!/usr/bin/env bash\nexit 0\n');
                // 标记动态拼接：contract 源文件本身不含字面量，signature guard 不会误报本文件
                const marker = 'DouyinXBogus' + 'Signer';
                writeFile(repo, 'pixivdownload-app/src/main/java/Bad.java',
                    'class Bad { String s = "' + marker + '"; }\n');
                git(['add', '-A'], repo);
                git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'guard noop + marker'], repo);
                const result = run(['bash', 'scripts/hooks/pre-push', 'origin2', remote2], { cwd: repo });
                git(['reset', '-q', '--hard', 'HEAD~1'], repo, { stdio: 'ignore' });
                return result;
            });
            rmrf(remote2);
        } finally {
            rmrf(repo);
        }
    }

    // 候选 hook 静态约束（不需要 bash；候选文件文本检查）
    const hooksRoot = path.join(candidateRoot, 'scripts', 'hooks');
    if (fs.existsSync(hooksRoot)) {
        for (const hook of ['pre-commit', 'pre-push']) {
            const file = path.join(hooksRoot, hook);
            if (!fs.existsSync(file)) {
                results.push({ name: 'candidate hook constraint: missing ' + hook, kind: 'hooks-static',
                    expected: null, status: null, ok: true,
                    diagnostic: 'candidate lacks ' + hook + ' (report; required paths govern deletion)' });
                continue;
            }
            const content = fs.readFileSync(file, 'utf8')
                .split('\n').filter((line) => !/^\s*#/.test(line)).join('\n');
            for (const [pattern, label] of [
                [/(git archive|\btar\s|\brsync\s|\bzip\s|\bpython\w*[\s.]|\bpowershell\w*[\s.])/i, 'tar/rsync/zip/python/powershell'],
                [/--remotes=\*/i, '--remotes=*'],
                [/remote_ref%%\/\*/i, 'remote_ref-derived remote name'],
            ]) {
                results.push({
                    name: 'candidate hook constraint: ' + label + ' (' + hook + ')',
                    kind: 'hooks-static', expected: null, status: null, ok: !pattern.test(content),
                    diagnostic: pattern.test(content)
                        ? 'candidate ' + hook + ' uses forbidden ' + label + '; fail closed' : '',
                });
            }
        }
    }
    return results;
}

// ---------------------------------------------------------------------------
// 自保护：candidate contract 必须能拒绝「下一代恶意 gate」
// ---------------------------------------------------------------------------

async function runSelfProtection(candidateRoot, trustedPolicy, hasContract, skip, skipReason) {
    const results = [];
    if (skip) {
        results.push({ name: 'self-protection (candidate contract vs next malicious gate)', kind: 'self-protection',
            expected: null, status: null, ok: true,
            diagnostic: skipReason || 'candidate contract bundle is byte-identical to the trusted bundle;'
                + ' protection is guaranteed by the trust chain (inductive skip)' });
        return results;
    }
    if (!hasContract) {
        results.push({ name: 'self-protection', kind: 'report', expected: null, status: null, ok: true,
            diagnostic: 'candidate has no scripts/i18n/gate-contract.mjs; self-protection cannot run'
                + ' (predates the contract architecture)' });
        return results;
    }
    if (!hasBash()) {
        results.push({ name: 'self-protection', kind: 'report', expected: null, status: null, ok: true,
            diagnostic: 'bash is not available; self-protection scenario skipped' });
        return results;
    }
    const repo = await makeContractRepo(candidateRoot, trustedPolicy);
    try {
        writeFile(repo, 'scripts/i18n/check.mjs', '#!/usr/bin/env node\nprocess.exit(0);\n');
        writeFile(repo, 'scripts/hooks/pre-commit', '#!/usr/bin/env bash\nexit 0\n');
        writeFile(repo, 'scripts/hooks/pre-push', '#!/usr/bin/env bash\nexit 0\n');
        git(['add', '-A'], repo);
        git(['commit', '-q', '-m', 'malicious next gate'], repo);
        const malicious = git(['rev-parse', 'HEAD'], repo);
        const candidateContract = path.join(candidateRoot, 'scripts', 'i18n', 'gate-contract.mjs');
        const result = run(['node', candidateContract, '--repo-root', repo, '--candidate-ref', malicious],
            { cwd: repo });
        const ok = result.status !== 0;
        results.push({
            name: 'candidate contract must reject the next malicious gate (no-op checker/hooks)',
            kind: 'self-protection', expected: 'exit != 0', status: result.status, ok,
            diagnostic: ok ? '' : 'candidate contract accepted a no-op checker gate (exit 0);'
                + ' the candidate contract cannot protect the next upgrade',
        });
    } finally {
        rmrf(repo);
    }
    return results;
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

function writeReport(reportRoot, payload) {
    const dir = path.join(reportRoot, 'build', 'reports', 'i18n');
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'contract.json'), JSON.stringify(payload, null, 2) + '\n', 'utf8');
}

function emitFailure(reportRoot, payload, candidateLabel, extraLines) {
    writeReport(reportRoot, payload);
    console.error('GATE CONTRACT FAILED (candidate ' + candidateLabel + ')');
    for (const check of payload.checks) {
        if (!check.ok && check.kind !== 'report') {
            console.error('  - ' + check.name + (check.diagnostic ? ': ' + check.diagnostic : ''));
        }
    }
    for (const line of extraLines || []) {
        console.error('  ' + line);
    }
    console.error('contract report: ' + path.join(reportRoot, 'build', 'reports', 'i18n', 'contract.json'));
}

async function main() {
    let args;
    try {
        args = parseArgs(process.argv.slice(2));
    } catch (e) {
        fail(e.message);
        return;
    }
    if (args.version) {
        console.log('i18n-gate-contract ' + CONTRACT_VERSION);
        return;
    }

    const repoRoot = path.resolve(args.repoRoot);
    const reportRoot = path.resolve(args.reportRoot || repoRoot);

    // 可信 policy 只来自本脚本所在目录（OWN_DIR = <trusted>/scripts/i18n，policy 与 contract 同目录）
    let trustedPolicy = null;
    const ownPolicyPath = path.join(OWN_DIR, 'gate-policy.json');
    if (fs.existsSync(ownPolicyPath)) {
        try {
            trustedPolicy = JSON.parse(fs.readFileSync(ownPolicyPath, 'utf8'));
            trustedGate.validatePolicyStructure(trustedPolicy);
        } catch (e) {
            fail('trusted gate-policy.json is invalid: ' + e.message);
            return;
        }
    }
    if (!trustedPolicy) {
        fail('gate-contract must run from the trusted gate materialization'
            + ' (gate-policy.json missing next to gate-contract.mjs)');
        return;
    }

    const checks = [];
    const diagnostics = [];

    // 1. 候选快照物化（只取 gate 路径；候选只作为被检查对象）
    let candidateRoot = null;
    let historyRef = null;
    let candidateRef = null;
    try {
        if (args.mode === 'ref') {
            candidateRef = trustedGate.resolveCommit(repoRoot, args.ref);
            if (!candidateRef) {
                fail('--candidate-ref ' + args.ref + ' does not resolve to a commit');
                return;
            }
            candidateRoot = snapshot.materializePaths(repoRoot, candidateRef,
                ['scripts/i18n', 'scripts/hooks']).root;
            pendingCandidateRoot = candidateRoot;
            historyRef = candidateRef;
        } else {
            candidateRoot = snapshot.materializeIndexPathsTo(repoRoot,
                ['scripts/i18n', 'scripts/hooks'], fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-contract-index-')));
            pendingCandidateRoot = candidateRoot;
            historyRef = 'HEAD';
        }
    } catch (e) {
        fail('cannot materialize the candidate snapshot: ' + e.message);
        return;
    }

    let candidatePolicy = null;
    try {
        // 2. candidate policy 审核（由旧 trusted contract）
        candidatePolicy = trustedGate.loadPolicyFromDir(candidateRoot);
        if (candidatePolicy) {
            try {
                trustedGate.validatePolicyStructure(candidatePolicy);
            } catch (e) {
                checks.push({ name: 'candidate policy structure', kind: 'policy', expected: 'valid', status: null, ok: false, diagnostic: e.message });
                diagnostics.push('candidate policy invalid: ' + e.message);
                emitFailure(reportRoot, {
                    contractVersion: CONTRACT_VERSION,
                    trustedPolicy,
                    candidate: { mode: args.mode, ref: candidateRef, policyProposal: null },
                    requiredPaths: { missing: [], predated: [] },
                    checks, verdict: 'fail', diagnostics,
                }, args.mode === 'ref' ? candidateRef : 'index');
                process.exit(1);
                return;
            }
            if (candidatePolicy.contractVersion < trustedPolicy.contractVersion) {
                checks.push({
                    name: 'candidate contractVersion not lowered', kind: 'policy',
                    expected: '>= ' + trustedPolicy.contractVersion, status: null, ok: false,
                    diagnostic: 'candidate contractVersion ' + candidatePolicy.contractVersion
                        + ' < trusted ' + trustedPolicy.contractVersion,
                });
            } else {
                checks.push({
                    name: 'candidate contractVersion not lowered', kind: 'policy',
                    expected: '>= ' + trustedPolicy.contractVersion, status: null, ok: true, diagnostic: '',
                });
            }
            // enforcement start 不得向后移动或删除：候选 start 必须是 trusted start 的祖先（或相等）
            const candidateStart = candidatePolicy.i18nEnforcementStartCommit;
            const trustedStart = trustedPolicy.i18nEnforcementStartCommit;
            if (candidateStart !== trustedStart) {
                const startOk = trustedGate.isAncestor(repoRoot, candidateStart, trustedStart);
                checks.push({
                    name: 'i18nEnforcementStartCommit not moved later / not deleted', kind: 'policy',
                    expected: 'ancestor-or-equal of ' + trustedStart, status: null, ok: startOk,
                    diagnostic: startOk ? '' : 'candidate moved the enforcement start to ' + candidateStart
                        + ' (trusted: ' + trustedStart + '); fail closed',
                });
            } else {
                checks.push({
                    name: 'i18nEnforcementStartCommit not moved later / not deleted', kind: 'policy',
                    expected: 'ancestor-or-equal of ' + trustedStart, status: null, ok: true, diagnostic: '',
                });
            }
            // required paths 不得减少（允许新增）
            const trustedSet = new Set(trustedPolicy.requiredPaths);
            const candidateSet = new Set(candidatePolicy.requiredPaths);
            const removed = trustedPolicy.requiredPaths.filter((p) => !candidateSet.has(p));
            checks.push({
                name: 'required paths not reduced', kind: 'policy',
                expected: 'candidate keeps all trusted required paths', status: null,
                ok: removed.length === 0,
                diagnostic: removed.length > 0 ? 'candidate dropped required paths: ' + removed.join(', ') : '',
            });
        } else {
            checks.push({
                name: 'candidate policy proposal', kind: 'policy', expected: null, status: null, ok: true,
                diagnostic: 'candidate has no gate-policy.json (predates the policy); trusted required paths still enforced',
            });
        }

        // 3. required paths：删除 fail closed；早于引入只报告
        const required = trustedGate.checkRequiredPaths(repoRoot, historyRef, candidateRoot, trustedPolicy.requiredPaths);
        checks.push({
            name: 'candidate keeps required gate files', kind: 'required-files',
            expected: 'no required path deleted', status: null,
            ok: required.missing.length === 0,
            diagnostic: required.missing.length > 0
                ? 'candidate gate bundle incomplete — required paths deleted: '
                    + required.missing.join(', ') + '; fail closed'
                : (required.predated.length > 0
                    ? 'candidate predates required paths (report only): ' + required.predated.join(', ') : ''),
        });
        diagnostics.push(...required.predated.map((p) => 'report: candidate predates required path ' + p));
        if (required.missing.length > 0) {
            diagnostics.push('candidate gate bundle incomplete -> fail closed: '
                + required.missing.join(', '));
            emitFailure(reportRoot, {
                contractVersion: CONTRACT_VERSION,
                trustedPolicy,
                candidate: { mode: args.mode, ref: candidateRef, policyProposal: candidatePolicy || null },
                requiredPaths: required,
                checks, verdict: 'fail', diagnostics,
            }, args.mode === 'ref' ? candidateRef : 'index',
            ['candidate gate bundle incomplete -> fail closed']);
            process.exit(1);
            return;
        }

        const hasChecker = fs.existsSync(path.join(candidateRoot, 'scripts', 'i18n', 'check.mjs'));
        const hasHooks = fs.existsSync(path.join(candidateRoot, 'scripts', 'hooks'));
        const hasContract = fs.existsSync(path.join(candidateRoot, 'scripts', 'i18n', 'gate-contract.mjs'));

        // 归纳跳过：与 trusted bundle 逐字节一致的部分行为由信任链保证，不重复黑盒
        const candidateScriptsIdentical = dirsIdentical(
            path.join(candidateRoot, 'scripts', 'i18n'), path.join(OWN_DIR),
            { exclude: ['gate-policy.json', 'test'] });
        const candidateHooksIdentical = dirsIdentical(
            path.join(candidateRoot, 'scripts', 'hooks'), path.join(OWN_DIR, '..', 'hooks'));
        const candidateContractIdentical = dirsIdentical(
            path.join(candidateRoot, 'scripts', 'i18n'), path.join(OWN_DIR),
            { exclude: ['gate-policy.json', 'test', 'check.mjs', 'generate-static.mjs', 'accept.mjs', 'doctor-hooks.mjs', 'snapshot-cli.mjs', 'trust-gate.mjs'] });

        // 4. candidate checker 黑盒行为
        const checkerScenarios = await runCheckerScenarios(candidateRoot, hasChecker, candidateScriptsIdentical);
        checks.push(...checkerScenarios);
        const checkerOk = checkerScenarios.every((c) => c.ok);

        // 5. candidate hooks 行为（黑盒失败后仍收集静态文本约束，执行场景跳过）
        const hookScenarios = await runHookScenarios(candidateRoot, trustedPolicy, hasHooks,
            candidateHooksIdentical || !checkerOk,
            !checkerOk ? 'candidate checker behavior already failed; hook execution scenarios skipped'
                : undefined);
        checks.push(...hookScenarios);
        const hooksOk = hookScenarios.every((c) => c.ok);

        // 6. 自保护：candidate contract 必须拒绝恶意下一代 gate
        const selfProtection = await runSelfProtection(candidateRoot, trustedPolicy, hasContract,
            candidateContractIdentical || !checkerOk || !hooksOk,
            !checkerOk || !hooksOk ? 'candidate behavior already failed; self-protection skipped'
                : undefined);
        checks.push(...selfProtection);

        for (const check of checks) {
            if (!check.ok && check.kind !== 'report') {
                diagnostics.push('FAIL: ' + check.name + (check.diagnostic ? ' — ' + check.diagnostic : ''));
            } else if (check.diagnostic) {
                diagnostics.push('report: ' + check.name + ' — ' + check.diagnostic);
            }
        }

        const verdict = checks.every((c) => c.ok) ? 'pass' : 'fail';
        const payload = {
            contractVersion: CONTRACT_VERSION,
            trustedPolicy,
            candidate: { mode: args.mode, ref: candidateRef, policyProposal: candidatePolicy || null },
            requiredPaths: required,
            checks, verdict, diagnostics,
        };
        writeReport(reportRoot, payload);

        if (verdict === 'fail') {
            emitFailure(reportRoot, payload, args.mode === 'ref' ? candidateRef : 'index');
            process.exit(1);
            return;
        }
        console.log('GATE CONTRACT OK (candidate ' + (args.mode === 'ref' ? candidateRef : 'index')
            + '; ' + checks.length + ' checks)');
    } finally {
        if (candidateRoot) {
            try {
                fs.rmSync(candidateRoot, { recursive: true, force: true });
            } catch (ignored) {
                // 清理失败不能掩盖 verdict
            }
        }
        snapshot.cleanupAll();
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main().catch((e) => {
        console.error('gate-contract ERROR: ' + (e && e.message ? e.message : e));
        process.exit(2);
    });
}
