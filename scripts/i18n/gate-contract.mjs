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
 *   i18nEnforcementStartCommit 不得向后移动或删除、required paths 不得减少（允许新增）、
 *   protectedBranches 与 requiredWorkflowJobs 集合不得减少（允许新增）；
 * - required paths 使用 trusted ∪ candidate 并集：candidate 新声明的 required path 必须在
 *   同一候选快照中真实存在；被候选删除 → fail closed（candidate gate bundle incomplete）；
 *   candidate 早于 trusted required path 引入（enforcement start 自身等）→ 只报告不阻断；
 * - candidate checker 做黑盒行为测试（每个负面场景独立 fixture，只施加一个故障，并断言
 *   report.json 的 issue type）：合法 fixture 必须通过，坏占位符 / 缺英文文件 / missing key /
 *   stale / translation-unaccepted / invalid lock / static 失步 / 硬编码语言必须失败；
 * - candidate hooks 实际运行验证（候选 hook 文件是执行对象）：pre-commit 必须找到 trusted
 *   anchor 并由 trusted checker/contract 判定；no-op pre-commit / no-op pre-push / 删除
 *   trustedGateRef 读取 / pre-push 改回使用 candidate checker → 一律失败；
 * - candidate quality-gate.yml（真实 YAML 解析）与 package.json scripts 契约：触发器、
 *   必需 job、关键行为、action 版本、result 传播、github.sha^ 回退禁令；
 * - 自保护：构造「下一代恶意 gate」（no-op checker / hooks / guard / workflow 弱化 /
 *   package scripts 弱化 / required paths 减少），运行 candidate contract 必须拒绝它；
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
import { createRequire } from 'module';
import { fileURLToPath } from 'url';

import trustedGate from './lib/trusted-gate.mjs';
import snapshot from './lib/repository-snapshot.mjs';

const CONTRACT_VERSION = '2';
const OWN_DIR = path.dirname(fileURLToPath(import.meta.url));

/** 嵌套契约调用深度（main() 初始化；runHookScenarios 用它跳过重复的 hook 执行场景）。 */
let GATE_CONTRACT_DEPTH = 0;

const APP_I18N = path.posix.join('pixivdownload-app', 'src', 'main', 'resources', 'i18n');
const STATIC_REL = path.posix.join('pixivdownload-app', 'src', 'main', 'resources', 'static', 'i18n-static');
const WORKFLOW_REL = path.posix.join('.github', 'workflows', 'quality-gate.yml');
const PACKAGE_JSON_REL = path.posix.join('package.json');

/** 候选快照物化路径范围：gate 文件 + CI workflow + package scripts 必须全部可见。 */
const CANDIDATE_PATHS = [
    'scripts/i18n',
    'scripts/hooks',
    'scripts/ci',
    '.github/workflows/quality-gate.yml',
    'package.json',
];

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
const EXIT_ZERO = '#!/usr/bin/env bash\nexit 0\n';

function fail(message) {
    console.error('gate-contract ERROR: ' + message);
    process.exit(2);
}

function git(args, repoRoot, opts = {}) {
    // (execFileSync(...) || '')：stdio:'ignore' 时 execFileSync 返回 null（Node 怪癖），归一化为 ''
    return (execFileSync('git', args, {
        cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], ...opts,
    }) || '').trim();
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

function readFile(root, rel) {
    return fs.readFileSync(path.join(root, ...rel.split('/')), 'utf8');
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

/** 加载 yaml 解析器：优先 repoRoot（CI 中 npm ci 后的 node_modules），其次 NODE_PATH（测试夹具）。 */
function loadYamlModule(repoRoot) {
    const anchors = [path.join(repoRoot, 'package.json')];
    if (process.env.NODE_PATH) {
        let nodePath = process.env.NODE_PATH;
        // WSL node：Windows 盘符路径（D:\...）在 POSIX 下不可解析，转为 /mnt/<drive>/ 形式
        if (process.platform !== 'win32' && /^[A-Za-z]:[\\/]/.test(nodePath)) {
            nodePath = '/mnt/' + nodePath[0].toLowerCase() + '/' + nodePath.slice(2).replace(/\\/g, '/');
        }
        anchors.push(path.join(nodePath, 'package.json'));
    }
    let lastError = null;
    for (const anchor of anchors) {
        try {
            const yaml = createRequire(anchor)('yaml');
            if (yaml && typeof yaml.parse === 'function' && typeof yaml.stringify === 'function') {
                return yaml;
            }
        } catch (e) {
            lastError = e;
        }
    }
    fail('the yaml parser dependency is required by the trusted gate contract'
        + ' (npm ci / npm install first): ' + (lastError ? lastError.message : 'yaml not found'));
    return null;
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
// 黑盒 checker 行为测试（对 candidate check.mjs；每个负面场景独立 fixture）
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

/** 读取 checker 在 fixture 中生成的机器报告；缺失或解析失败返回 null。 */
function readCheckerReport(fixture) {
    const reportFile = path.join(fixture, 'build', 'reports', 'i18n', 'report.json');
    if (!fs.existsSync(reportFile)) {
        return null;
    }
    try {
        return JSON.parse(fs.readFileSync(reportFile, 'utf8'));
    } catch (e) {
        return null;
    }
}

async function regen(fixture) {
    const gen = await importScript(path.join(fixture, 'scripts', 'i18n', 'generate-static.mjs'));
    gen.runGenerate(fixture);
}

/**
 * 黑盒场景矩阵：每个场景从合法状态独立创建 fixture，只施加一个故障，
 * 执行 checker 后读取 report.json 并断言具体 issue type（不得只断言 exit != 0）。
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

    /**
     * 独立场景：新建 fixture → 只施加一个故障 → 运行 checker → 读取 report.json 断言 issue type
     * （致命错误场景断言 catalogError 文本，普通故障断言 issue.type）。
     * @param {string} name
     * @param {{mutate: Function, expectPass?: boolean, issueType?: string, errorPattern?: RegExp, catalog?: string}} spec
     */
    async function scenario(name, spec) {
        let fixture = null;
        try {
            fixture = await buildCheckerFixture(
                candidateRoot, spec.catalog || CATALOG_BASIC, GOOD_ZH, GOOD_EN);
            await spec.mutate(fixture);
            const result = runChecker(fixture);
            const report = readCheckerReport(fixture);
            const issueTypes = report && Array.isArray(report.issues)
                ? [...new Set(report.issues.map((i) => i.type))]
                : [];
            if (spec.expectPass) {
                const ok = result.status === 0;
                results.push({
                    name, kind: 'black-box', expected: 'exit 0', status: result.status, ok,
                    diagnostic: ok ? '' : 'expected exit 0 but got exit ' + result.status + ': '
                        + (result.output || '').split('\n').slice(-8).join(' | '),
                });
                return;
            }
            let typeFound = false;
            if (spec.issueType) {
                typeFound = issueTypes.includes(spec.issueType);
            } else if (spec.errorPattern) {
                const errorText = report && report.catalogError
                    ? report.catalogError
                    : (report && report.catalog && report.catalog.error) || '';
                typeFound = !!errorText && spec.errorPattern.test(errorText);
            }
            const ok = result.status !== 0 && typeFound;
            results.push({
                name, kind: 'black-box',
                expected: 'exit != 0 + ' + (spec.issueType
                    ? 'issue.type == ' + spec.issueType
                    : 'catalog error matches ' + spec.errorPattern),
                status: result.status, ok,
                diagnostic: ok ? '' : 'expected failure'
                    + (spec.issueType
                        ? ' with issue type ' + spec.issueType + ' (got: ' + (issueTypes.join(', ') || 'none') + ')'
                        : ' with catalog error matching ' + spec.errorPattern
                            + ' (got: ' + JSON.stringify(report && (report.catalogError
                                || (report.catalog && report.catalog.error))) + ')')
                    + ' but got exit ' + result.status + ': '
                    + (result.output || '').split('\n').slice(-8).join(' | '),
            });
        } catch (e) {
            results.push({
                name, kind: 'black-box',
                expected: spec.expectPass ? 'exit 0' : 'exit != 0', status: null, ok: false,
                diagnostic: 'scenario failed to run: ' + e.message,
            });
        } finally {
            if (fixture) {
                rmrf(fixture);
            }
        }
    }

    await scenario('legal fixture (full zh/en, correct lock, synced static) must pass', {
        expectPass: true,
        mutate: async () => undefined,
    });

    await scenario('bad-placeholder → issue.type == invalid', {
        issueType: 'invalid',
        mutate: async (fixture) => {
            writeFile(fixture, APP_I18N + '/web/common_en.properties', BAD_EN);
            await regen(fixture);
        },
    });

    await scenario('missing-en-file → issue.type == missing', {
        issueType: 'missing',
        mutate: async (fixture) => {
            fs.rmSync(path.join(fixture, ...(APP_I18N + '/web/common_en.properties').split('/')));
            await regen(fixture);
        },
    });

    await scenario('missing-key → issue.type == missing', {
        issueType: 'missing',
        mutate: async (fixture) => {
            writeFile(fixture, APP_I18N + '/web/common_en.properties', EN_MISSING_TITLE);
            await regen(fixture);
        },
    });

    await scenario('source-stale → issue.type == stale', {
        issueType: 'stale',
        mutate: async (fixture) => {
            writeFile(fixture, APP_I18N + '/web/common.properties', ZH_CHANGED);
            await regen(fixture);
        },
    });

    await scenario('translation-unaccepted → issue.type == translation-unaccepted', {
        issueType: 'translation-unaccepted',
        mutate: async (fixture) => {
            writeFile(fixture, APP_I18N + '/web/common.properties', GOOD_ZH);
            writeFile(fixture, APP_I18N + '/web/common_en.properties', EN_CHANGED);
            await regen(fixture);
        },
    });

    await scenario('invalid-lock → catalogError mentions the unsupported lock version', {
        errorPattern: /invalid-lock|unsupported lock version/i,
        mutate: async (fixture) => {
            writeFile(fixture, 'i18n/catalog-lock.json', JSON.stringify({ version: 999, entries: [] }, null, 2) + '\n');
        },
    });

    await scenario('static-out-of-sync → issue.type == static-out-of-sync', {
        issueType: 'static-out-of-sync',
        mutate: async (fixture) => {
            fs.rmSync(path.join(fixture, ...STATIC_REL.split('/'), 'meta.json'));
        },
    });

    await scenario('hardcoded-locale → issue.type == hardcoded-locale', {
        issueType: 'hardcoded-locale',
        mutate: async (fixture) => {
            writeFile(fixture, 'pixivdownload-app/src/main/resources/static/js/hack.js',
                "const supportedLocales = ['en-US'];\n");
        },
    });

    await scenario('disabled language incomplete must NOT fail coverage', {
        expectPass: true,
        catalog: CATALOG_WITH_DISABLED,
        mutate: async () => undefined,
    });

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
// candidate hooks 行为测试（实际运行候选 hook 文件）
// ---------------------------------------------------------------------------

/**
 * 构造 hook 行为测试仓库（candidate hooks 是真正的执行对象）：
 * - C1（root，enforcement start）= candidate gate bundle（scripts/i18n + hooks + scripts/ci +
 *   quality-gate.yml + package.json，无 policy）；
 * - C2 = 引入 policy（start = C1）；
 * - C3 = trusted anchor commit：把 scripts/i18n 与 hooks 替换为 trusted bundle
 *   （本 contract 自身目录），trustedGateRef = C3；
 * - anchor 提交后，把 candidate 的 scripts/hooks/pre-commit 与 pre-push 重新写入工作树：
 *   后续场景实际运行的是 candidate hook 文件；candidate hook 内部必须找到 trusted anchor，
 *   由 trusted checker / contract / guard 完成真实判定。
 */
async function makeContractRepo(repoRoot, candidateRoot, trustedPolicy) {
    const repo = tempDir('hooks');
    git(['init', '-q'], repo);
    git(['config', 'user.email', 't@example.com'], repo);
    git(['config', 'user.name', 'test'], repo);
    git(['config', 'core.autocrlf', 'false'], repo);
    // 契约内的 contract/hook 场景需要 yaml：把仓库根的 node_modules 链接进 fixture
    // （等价 CI 的 npm ci；WSL bash 环境不继承 Windows NODE_PATH，链接最可靠）
    writeFile(repo, '.gitignore', 'build/\nnode_modules/\n');
    const repoNodeModules = path.join(repoRoot, 'node_modules');
    if (fs.existsSync(repoNodeModules)) {
        try {
            if (process.platform === 'win32') {
                fs.symlinkSync(repoNodeModules, path.join(repo, 'node_modules'), 'junction');
            } else {
                fs.symlinkSync(repoNodeModules, path.join(repo, 'node_modules'));
            }
        } catch (ignored) {
            // 链接失败不阻断：候选 predates workflow 时不需要 yaml
        }
    }
    // C1：candidate gate bundle（无 policy）
    fs.cpSync(path.join(candidateRoot, 'scripts', 'i18n'), path.join(repo, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(repo, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    fs.rmSync(path.join(repo, 'scripts', 'i18n', 'gate-policy.json'), { force: true });
    if (fs.existsSync(path.join(candidateRoot, 'scripts', 'hooks'))) {
        fs.cpSync(path.join(candidateRoot, 'scripts', 'hooks'), path.join(repo, 'scripts', 'hooks'), { recursive: true });
    }
    if (fs.existsSync(path.join(candidateRoot, 'scripts', 'ci'))) {
        fs.cpSync(path.join(candidateRoot, 'scripts', 'ci'), path.join(repo, 'scripts', 'ci'), { recursive: true });
    }
    if (fs.existsSync(path.join(candidateRoot, ...WORKFLOW_REL.split('/')))) {
        writeFile(repo, WORKFLOW_REL, readFile(candidateRoot, WORKFLOW_REL));
    }
    if (fs.existsSync(path.join(candidateRoot, ...PACKAGE_JSON_REL.split('/')))) {
        writeFile(repo, PACKAGE_JSON_REL, readFile(candidateRoot, PACKAGE_JSON_REL));
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
    // anchor 提交后：candidate hooks 重新写入工作树并提交（C4，bypass hooks）。
    // 执行对象 = candidate hooks；提交它们使场景的 git add -A 不再暂存 hook 差异
    // （避免每个场景都触发嵌套 contract 调用；显式 gate 暂存场景由深度守卫兜底）。
    restoreCandidateHooks(repo, candidateRoot);
    git(['add', '-A'], repo);
    // --allow-empty：候选 hooks 与 trusted hooks 逐字节一致时树无变化也必须有 C4
    git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '--allow-empty', '-m', 'restore candidate hooks'], repo);
    return repo;
}

/** 把 candidate 的 scripts/hooks 重新写入工作树（幂等；候选 hook 是执行对象）。 */
function restoreCandidateHooks(repo, candidateRoot) {
    if (fs.existsSync(path.join(candidateRoot, 'scripts', 'hooks'))) {
        fs.cpSync(path.join(candidateRoot, 'scripts', 'hooks'), path.join(repo, 'scripts', 'hooks'), { recursive: true });
    }
}

/** 候选 hook 文件内容；candidate 缺该 hook 时返回 null。 */
function candidateHookContent(candidateRoot, hook) {
    const file = path.join(candidateRoot, 'scripts', 'hooks', hook);
    return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

async function runHookScenarios(repoRoot, candidateRoot, trustedPolicy, hasHooks, skip, skipReason) {
    const results = [];
    if (skip) {
        results.push({ name: 'hooks behavior (execution)', kind: 'hooks', expected: null, status: null, ok: true,
            diagnostic: skipReason || 'candidate scripts/hooks is byte-identical to the trusted bundle;'
                + ' behavior is guaranteed by the trust chain (inductive skip)' });
        return results;
    }
    if (GATE_CONTRACT_DEPTH >= 1) {
        results.push({ name: 'hooks behavior (execution)', kind: 'hooks', expected: null, status: null, ok: true,
            diagnostic: 'nested trusted contract invocation (depth guard): hook execution scenarios are'
                + ' covered by the outermost contract run' });
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
        const repo = await makeContractRepo(repoRoot, candidateRoot, trustedPolicy);
        try {
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
                restoreCandidateHooks(repo, candidateRoot);
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

            // 直接场景：candidate pre-commit = exit 0 → trusted contract 必须失败
            await hookScenario('candidate pre-commit = exit 0 must fail (trusted contract)', false, async () => {
                writeFile(repo, 'scripts/hooks/pre-commit', EXIT_ZERO);
                writeFile(repo, APP_I18N + '/web/common_en.properties', BAD_EN);
                git(['add', '-A'], repo);
                writeFile(repo, APP_I18N + '/web/common_en.properties', GOOD_EN);
                return run(['bash', 'scripts/hooks/pre-commit'], { cwd: repo });
            });
            git(['reset', '-q', '--hard', 'HEAD'], repo, { stdio: 'ignore' });

            // 直接场景：candidate pre-commit 删除 trustedGateRef 读取（改检工作树）→ 必须失败
            await hookScenario('candidate pre-commit without trustedGateRef must fail', false, async () => {
                writeFile(repo, 'scripts/hooks/pre-commit',
                    '#!/usr/bin/env bash\nset -euo pipefail\nnode "$(git rev-parse --show-toplevel)/scripts/i18n/check.mjs"\n');
                writeFile(repo, APP_I18N + '/web/common_en.properties', BAD_EN);
                git(['add', '-A'], repo);
                writeFile(repo, APP_I18N + '/web/common_en.properties', GOOD_EN);
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
                const result = run(['git', 'push', 'origin', 'master'], { cwd: repo });
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
                const result = run(['git', 'push', 'origin2', 'master'], { cwd: repo });
                git(['reset', '-q', '--hard', 'HEAD~1'], repo, { stdio: 'ignore' });
                return result;
            });
            rmrf(remote2);

            // 直接场景：candidate pre-push = exit 0 → 必须失败
            const remote3 = tempDir('remote3');
            git(['init', '-q', '--bare', remote3], repo);
            git(['remote', 'add', 'origin3', remote3], repo);
            git(['-c', 'core.hooksPath=/dev/null', 'push', '-q', 'origin3', 'master'], repo, { stdio: 'ignore' });

            await hookScenario('candidate pre-push = exit 0 must fail (trusted contract)', false, async () => {
                writeFile(repo, 'scripts/hooks/pre-push', EXIT_ZERO);
                writeFile(repo, APP_I18N + '/web/common_en.properties', BAD_EN);
                git(['add', '-A'], repo);
                git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'bad translation'], repo);
                const result = run(['git', 'push', 'origin3', 'master'], { cwd: repo });
                git(['reset', '-q', '--hard', 'HEAD~1'], repo, { stdio: 'ignore' });
                return result;
            });
            rmrf(remote3);

            // 直接场景：candidate pre-push 改回使用 candidate checker（自批准）→ 必须失败
            const remote4 = tempDir('remote4');
            git(['init', '-q', '--bare', remote4], repo);
            git(['remote', 'add', 'origin4', remote4], repo);
            git(['-c', 'core.hooksPath=/dev/null', 'push', '-q', 'origin4', 'master'], repo, { stdio: 'ignore' });

            await hookScenario('candidate pre-push using the candidate checker must fail', false, async () => {
                writeFile(repo, 'scripts/hooks/pre-push',
                    '#!/usr/bin/env bash\nset -euo pipefail\nnode "$(git rev-parse --show-toplevel)/scripts/i18n/check.mjs" --version\n');
                // 候选 checker = 工作树里的 exit-0 checker（hook 直接使用它 → 自我批准）
                writeFile(repo, 'scripts/i18n/check.mjs', '#!/usr/bin/env node\nprocess.exit(0);\n');
                writeFile(repo, APP_I18N + '/web/common_en.properties', BAD_EN);
                git(['add', '-A'], repo);
                git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'bad translation'], repo);
                const result = run(['git', 'push', 'origin4', 'master'], { cwd: repo });
                git(['reset', '-q', '--hard', 'HEAD~1'], repo, { stdio: 'ignore' });
                return result;
            });
            rmrf(remote4);
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
// 候选 quality-gate.yml + package.json 语义契约（真实 YAML 解析）
// ---------------------------------------------------------------------------

const REQUIRED_TRIGGERS = ['push', 'pull_request', 'merge_group', 'workflow_dispatch', 'workflow_call'];
const REQUIRED_WORKFLOW_JOBS = ['java-tests', 'javascript-tests', 'signature-guard', 'trusted-gate-contract', 'i18n-check'];
/** 项目认可的官方 action 主版本（升级即更新；SHA pin 等价允许）。 */
const APPROVED_ACTIONS = {
    'actions/checkout': '7',
    'actions/setup-node': '7',
    'actions/upload-artifact': '7',
    'actions/setup-java': '5',
};
const REQUIRED_SCRIPTS = ['test:i18n', 'i18n:check', 'i18n:generate-static', 'test:js', 'test:web-standards'];

/** 可信 gate 执行必须来自物化的 trusted bundle，禁止直接运行候选工作树的 contract/guard。 */
const TRUSTED_LOC = /\$GATE_DIR|\$RUNNER_TEMP|\bguard\/out\b|materialize-trusted-gate/;

function stepRun(step) {
    return typeof step.run === 'string' ? step.run : '';
}

function stepUses(step) {
    return typeof step.uses === 'string' ? step.uses : '';
}

function stepIf(step) {
    return typeof step.if === 'string' ? step.if : '';
}

function stepName(step) {
    return typeof step.name === 'string' ? step.name : '';
}

function jobSteps(job) {
    return job && Array.isArray(job.steps) ? job.steps : [];
}

function jobHasRun(job, re) {
    return jobSteps(job).some((s) => re.test(stepRun(s)));
}

function jobHasUses(job, re) {
    return jobSteps(job).some((s) => re.test(stepUses(s)));
}

function jobHasStepWith(job, usesRe, withKey, valueRe) {
    return jobSteps(job).some((s) => usesRe.test(stepUses(s))
        && s.with && typeof s.with[withKey] === 'string' && valueRe.test(s.with[withKey]));
}

function jobHasUploadAlways(job) {
    const uploads = jobSteps(job).filter((s) => /actions\/upload-artifact@/.test(stepUses(s)));
    return uploads.length > 0 && uploads.every((s) => /always\(\)/.test(stepIf(s)));
}

function isBannedScript(value) {
    if (typeof value !== 'string') {
        return true;
    }
    const v = value.trim();
    if (/^(true|:|exit(\s+0)?|echo|printf|:\s*true)/i.test(v)) {
        return true;
    }
    // 指向真实入口：必须引用 node / mvn 或路径
    return !(/\b(node|mvn)\b/.test(v) || v.includes('/'));
}

function pushCheck(checks, name, ok, diagnostic) {
    checks.push({ name, kind: 'workflow', expected: ok ? 'present' : 'absent', status: null, ok,
        diagnostic: ok ? '' : diagnostic });
}

function pushPackageCheck(checks, name, ok, diagnostic) {
    checks.push({ name, kind: 'package', expected: ok ? 'valid' : 'invalid', status: null, ok,
        diagnostic: ok ? '' : diagnostic });
}

/**
 * 解析候选 quality-gate.yml（真实 YAML parser）并验证：
 * 触发器 / 必需 job / job 级 continue-on-error 禁令 / 关键行为 / 关键命令不得改为 echo|true /
 * action 主版本 / github.sha^ 回退禁令 / FORCE_JAVASCRIPT_ACTIONS_TO_NODE24 清理。
 * 候选缺失 workflow 文件（predates）时只报告不阻断。
 */
function runWorkflowContractChecks(repoRoot, candidateRoot) {
    const checks = [];
    const workflowFile = path.join(candidateRoot, ...WORKFLOW_REL.split('/'));
    if (!fs.existsSync(workflowFile)) {
        checks.push({ name: 'candidate quality-gate.yml contract', kind: 'workflow', expected: null,
            status: null, ok: true,
            diagnostic: 'candidate predates .github/workflows/quality-gate.yml (report only)' });
        return checks;
    }
    let doc;
    try {
        const YAML = loadYamlModule(repoRoot);
        doc = YAML.parse(fs.readFileSync(workflowFile, 'utf8'));
    } catch (e) {
        pushCheck(checks, 'candidate quality-gate.yml parses as YAML', false,
            'cannot parse the candidate workflow: ' + e.message);
        return checks;
    }
    if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
        pushCheck(checks, 'candidate quality-gate.yml structure', false,
            'the candidate workflow must be a YAML mapping');
        return checks;
    }

    // 6.1 触发器
    const triggers = doc.on && typeof doc.on === 'object' ? doc.on : {};
    for (const trigger of REQUIRED_TRIGGERS) {
        pushCheck(checks, 'trigger ' + trigger + ' is preserved', triggers[trigger] !== undefined,
            'candidate workflow dropped the ' + trigger + ' trigger');
    }

    // 6.2 必需 job
    const jobs = doc.jobs && typeof doc.jobs === 'object' ? doc.jobs : {};
    for (const jobId of REQUIRED_WORKFLOW_JOBS) {
        pushCheck(checks, 'job ' + jobId + ' is preserved', jobs[jobId] && typeof jobs[jobId] === 'object',
            'candidate workflow dropped the required job ' + jobId);
    }
    for (const jobId of REQUIRED_WORKFLOW_JOBS) {
        const job = jobs[jobId];
        if (job && job['continue-on-error']) {
            pushCheck(checks, 'job ' + jobId + ' must not set continue-on-error', false,
                'candidate workflow sets continue-on-error on the required job ' + jobId
                    + ' without final propagation guarantees');
        }
    }

    // 6.3 关键行为
    const jJava = jobs['java-tests'];
    pushCheck(checks, 'java-tests: checkout full tested commit', jobHasUses(jJava, /actions\/checkout@/),
        'java-tests must checkout the tested commit');
    pushCheck(checks, 'java-tests: setup JDK 17', jobHasStepWith(jJava, /actions\/setup-java@/, 'java-version', /17/),
        'java-tests must set up JDK 17 via actions/setup-java');
    pushCheck(checks, 'java-tests: compile external plugin fixtures',
        jobHasRun(jJava, /mvn/) && jobHasRun(jJava, /pixivdownload-official-plugins/) && jobHasRun(jJava, /compile/),
        'java-tests must compile the external plugin fixtures (mvn -pl pixivdownload-official-plugins -am compile)');
    pushCheck(checks, 'java-tests: full maven tests',
        jobHasRun(jJava, /mvn/) && jobHasRun(jJava, /test/) && jobHasRun(jJava, /exec\.skip/),
        'java-tests must run the full maven tests (mvn test -Dexec.skip=true)');

    const jJs = jobs['javascript-tests'];
    pushCheck(checks, 'javascript-tests: checkout tested commit', jobHasUses(jJs, /actions\/checkout@/),
        'javascript-tests must checkout the tested commit');
    pushCheck(checks, 'javascript-tests: setup Node 24', jobHasStepWith(jJs, /actions\/setup-node@/, 'node-version', /24/),
        'javascript-tests must set up Node.js 24 via actions/setup-node');
    pushCheck(checks, 'javascript-tests: npm run test:js', jobHasRun(jJs, /npm run test:js/),
        'javascript-tests must run npm run test:js');
    pushCheck(checks, 'javascript-tests: npm run test:web-standards', jobHasRun(jJs, /npm run test:web-standards/),
        'javascript-tests must run npm run test:web-standards');

    const jGuard = jobs['signature-guard'];
    pushCheck(checks, 'signature-guard: trusted base determination',
        jobHasRun(jGuard, /(base_sha|event\.before|trusted_base_sha)/),
        'signature-guard must determine a trusted base from GitHub event data (no github.sha^ fallback)');
    // 只匹配实际「执行 guard」的 step（bash <guard>），物化 step 中的 test -f 引用不算
    const guardSteps = jobSteps(jGuard).filter((s) => /(^|\n)\s*bash\s+[^\n]*pre-push-guard\.sh/.test(stepRun(s)));
    pushCheck(checks, 'signature-guard: trusted guard materialization', guardSteps.length > 0,
        'signature-guard must materialize and run the trusted pre-push-guard.sh');
    const guardOk = guardSteps.length > 0
        && guardSteps.every((s) => TRUSTED_LOC.test(stepRun(s)) && /github\.sha/.test(stepRun(s)));
    pushCheck(checks, 'signature-guard: guard comes from the trusted base, checks github.sha', guardOk,
        'signature-guard must run the guard from the materialized trusted bundle against github.sha'
            + ' (candidate guard self-approval is refused)');

    const jContract = jobs['trusted-gate-contract'];
    pushCheck(checks, 'trusted-gate-contract: trusted base determination',
        jobHasRun(jContract, /(base_sha|event\.before|trusted_base_sha)/),
        'trusted-gate-contract must determine a trusted base from GitHub event data');
    const contractSteps = jobSteps(jContract).filter((s) => /gate-contract\.mjs/.test(stepRun(s)));
    const contractOk = contractSteps.length > 0
        && contractSteps.every((s) => TRUSTED_LOC.test(stepRun(s))
            && /--candidate-ref/.test(stepRun(s)) && /github\.sha/.test(stepRun(s)));
    pushCheck(checks, 'trusted-gate-contract: trusted contract checks github.sha', contractOk,
        'trusted-gate-contract must run the materialized trusted gate-contract.mjs'
            + ' with --candidate-ref ${{ github.sha }} (candidate contract self-approval is refused)');
    pushCheck(checks, 'trusted-gate-contract: report upload', jobHasUploadAlways(jContract),
        'trusted-gate-contract must upload the contract report with if: always()');

    const jI18n = jobs['i18n-check'];
    pushCheck(checks, 'i18n-check: trusted base determination',
        jobHasRun(jI18n, /(base_sha|event\.before|trusted_base_sha)/),
        'i18n-check must determine a trusted base from GitHub event data');
    const ciTestsOk = jobSteps(jI18n).some((s) => /npm run test:i18n/.test(stepRun(s))
        && s.env && typeof s.env.CI === 'string' && /true/i.test(s.env.CI));
    pushCheck(checks, 'i18n-check: CI=true npm run test:i18n', ciTestsOk,
        'i18n-check must run npm run test:i18n with CI=true');
    const i18nContractSteps = jobSteps(jI18n).filter((s) => /gate-contract\.mjs/.test(stepRun(s)));
    const i18nContractOk = i18nContractSteps.length > 0
        && i18nContractSteps.every((s) => TRUSTED_LOC.test(stepRun(s))
            && /--candidate-ref/.test(stepRun(s)) && /github\.sha/.test(stepRun(s)));
    pushCheck(checks, 'i18n-check: trusted base contract checks github.sha', i18nContractOk,
        'i18n-check must run the materialized trusted gate-contract.mjs against github.sha');
    pushCheck(checks, 'i18n-check: ref snapshot check',
        jobHasRun(jI18n, /check\.mjs/) && jobHasRun(jI18n, /--snapshot ref/) && jobHasRun(jI18n, /--ref/),
        'i18n-check must run the ref snapshot check (check.mjs --snapshot ref --ref github.sha)');
    pushCheck(checks, 'i18n-check: worktree check', jobHasRun(jI18n, /npm run i18n:check/),
        'i18n-check must run the worktree i18n check');
    pushCheck(checks, 'i18n-check: static generation', jobHasRun(jI18n, /i18n:generate-static/),
        'i18n-check must generate the static i18n resources');
    pushCheck(checks, 'i18n-check: static diff',
        jobHasRun(jI18n, /git diff --exit-code/) && jobHasRun(jI18n, /i18n-static/),
        'i18n-check must verify the generated resources with git diff --exit-code');
    pushCheck(checks, 'i18n-check: report upload', jobHasUploadAlways(jI18n),
        'i18n-check must upload the i18n report with if: always()');
    pushCheck(checks, 'i18n-check: final propagation',
        jobHasRun(jI18n, /outcome/) && jobHasRun(jI18n, /GITHUB_STEP_SUMMARY|check_outcome/),
        'i18n-check must propagate all collected outcomes (tests/trusted/ref/worktree/static) at the end');

    // 关键命令不得改成 echo / true（各必需 job 的行为检查已覆盖对应 run 内容）
    const bannedStepRun = /^\s*(echo\b|true\b|exit\s+0\b|:\s*true\b)/;
    const bannedRuns = [];
    for (const jobId of Object.keys(jobs)) {
        for (const step of jobSteps(jobs[jobId])) {
            if (bannedStepRun.test(stepRun(step))) {
                bannedRuns.push(jobId + ': ' + stepName(step) || '(unnamed step)');
            }
        }
    }
    pushCheck(checks, 'no key step is reduced to echo / true', bannedRuns.length === 0,
        'candidate workflow reduces critical steps to echo/true: ' + bannedRuns.join(', '));

    // action 主版本（16）：官方 action 必须是项目认可的 maintained major 或 release commit SHA pin
    const badActions = [];
    for (const jobId of Object.keys(jobs)) {
        for (const step of jobSteps(jobs[jobId])) {
            const uses = stepUses(step);
            const match = /^([a-zA-Z0-9._-]+\/[a-zA-Z0-9._-]+)@(.+)$/.exec(uses);
            if (!match) {
                continue;
            }
            const approvedMajor = APPROVED_ACTIONS[match[1]];
            if (approvedMajor === undefined) {
                continue;
            }
            const version = match[2];
            if (version === 'v' + approvedMajor || /^[0-9a-f]{40}$/.test(version)) {
                continue;
            }
            badActions.push(jobId + ': ' + uses);
        }
    }
    pushCheck(checks, 'official actions use the approved maintained majors', badActions.length === 0,
        'candidate workflow uses deprecated action versions (expected '
            + Object.entries(APPROVED_ACTIONS).map(([a, v]) => a + '@v' + v).join(', ')
            + ' or release SHA pins): ' + badActions.join(', '));

    // FORCE_JAVASCRIPT_ACTIONS_TO_NODE24：action v7 原生支持 Node 24，不再需要该兼容变量
    if (doc.env && doc.env.FORCE_JAVASCRIPT_ACTIONS_TO_NODE24) {
        pushCheck(checks, 'FORCE_JAVASCRIPT_ACTIONS_TO_NODE24 compat env is removed', false,
            'candidate workflow reintroduced FORCE_JAVASCRIPT_ACTIONS_TO_NODE24;'
                + ' the approved action majors are Node 24 native');
    }

    // 7.4 新分支 trusted base 不得回退到 github.sha^（candidate 父提交不可信）
    const shaCaret = /github\.sha\s*(\^|\}\}\s*\^)/;
    const badShaCaret = [];
    for (const jobId of Object.keys(jobs)) {
        for (const step of jobSteps(jobs[jobId])) {
            if (shaCaret.test(stepRun(step))) {
                badShaCaret.push(jobId + ': ' + (stepName(step) || '(unnamed step)'));
            }
        }
    }
    pushCheck(checks, 'trusted base never falls back to github.sha^', badShaCaret.length === 0,
        'candidate workflow uses the untrusted parent fallback github.sha^: ' + badShaCaret.join(', '));

    return checks;
}

/** 候选 package.json scripts 契约：五个入口必须指向真实测试入口。 */
function runPackageContractChecks(candidateRoot) {
    const checks = [];
    const pkgFile = path.join(candidateRoot, ...PACKAGE_JSON_REL.split('/'));
    if (!fs.existsSync(pkgFile)) {
        checks.push({ name: 'candidate package.json scripts contract', kind: 'package', expected: null,
            status: null, ok: true,
            diagnostic: 'candidate predates package.json (report only)' });
        return checks;
    }
    let pkg;
    try {
        pkg = JSON.parse(fs.readFileSync(pkgFile, 'utf8'));
    } catch (e) {
        pushPackageCheck(checks, 'candidate package.json parses as JSON', false,
            'cannot parse the candidate package.json: ' + e.message);
        return checks;
    }
    const scripts = pkg && typeof pkg.scripts === 'object' ? pkg.scripts : {};
    for (const script of REQUIRED_SCRIPTS) {
        const value = scripts[script];
        pushPackageCheck(checks, 'package script ' + script + ' points at a real entry',
            typeof value === 'string' && !isBannedScript(value),
            'candidate package.json script ' + script + ' must be a real test entry'
                + ' (got ' + JSON.stringify(value) + '); test:i18n = true / i18n:check = echo ok are refused');
    }
    return checks;
}

// ---------------------------------------------------------------------------
// 自保护：candidate contract 必须能拒绝「下一代恶意 gate」
// ---------------------------------------------------------------------------

async function runSelfProtection(repoRoot, candidateRoot, trustedPolicy, hasContract, skip, skipReason) {
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
    const repo = await makeContractRepo(repoRoot, candidateRoot, trustedPolicy);
    try {
        // 下一代恶意 gate：checker / pre-commit / pre-push / guard 全部 no-op，
        // workflow 删除关键 jobs + 关键命令改为 true，package script 改为 true，required paths 减少
        writeFile(repo, 'scripts/i18n/check.mjs', '#!/usr/bin/env node\nprocess.exit(0);\n');
        writeFile(repo, 'scripts/hooks/pre-commit', EXIT_ZERO);
        writeFile(repo, 'scripts/hooks/pre-push', EXIT_ZERO);
        writeFile(repo, 'scripts/hooks/pre-push-guard.sh', '#!/usr/bin/env bash\nexit 0\n');
        const workflowFile = path.join(repo, ...WORKFLOW_REL.split('/'));
        if (fs.existsSync(workflowFile)) {
            const YAML = loadYamlModule(repo);
            const doc = YAML.parse(fs.readFileSync(workflowFile, 'utf8'));
            if (doc && doc.jobs && typeof doc.jobs === 'object') {
                delete doc.jobs['java-tests'];
                if (doc.jobs['i18n-check'] && Array.isArray(doc.jobs['i18n-check'].steps)) {
                    const tests = doc.jobs['i18n-check'].steps.find((s) => typeof s.run === 'string'
                        && s.run.includes('npm run test:i18n'));
                    if (tests) {
                        tests.run = 'true';
                    }
                }
            }
            fs.writeFileSync(workflowFile, YAML.stringify(doc), 'utf8');
        }
        const pkgFile = path.join(repo, ...PACKAGE_JSON_REL.split('/'));
        if (fs.existsSync(pkgFile)) {
            const pkg = JSON.parse(fs.readFileSync(pkgFile, 'utf8'));
            if (pkg && pkg.scripts && typeof pkg.scripts === 'object') {
                pkg.scripts['test:i18n'] = 'true';
            }
            fs.writeFileSync(pkgFile, JSON.stringify(pkg, null, 2) + '\n', 'utf8');
        }
        const policyPath = path.join(repo, 'scripts', 'i18n', 'gate-policy.json');
        if (fs.existsSync(policyPath)) {
            const pol = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
            if (pol && Array.isArray(pol.requiredPaths)) {
                pol.requiredPaths = pol.requiredPaths.filter((p) => p !== 'scripts/i18n/check.mjs');
            }
            fs.writeFileSync(policyPath, JSON.stringify(pol, null, 2) + '\n', 'utf8');
        }
        git(['add', '-A'], repo);
        git(['commit', '-q', '-m', 'malicious next gate'], repo);
        const malicious = git(['rev-parse', 'HEAD'], repo);
        const candidateContract = path.join(candidateRoot, 'scripts', 'i18n', 'gate-contract.mjs');
        const result = run(['node', candidateContract, '--repo-root', repo, '--candidate-ref', malicious],
            { cwd: repo });
        const ok = result.status !== 0;
        results.push({
            name: 'candidate contract must reject the next malicious gate (no-op checker/hooks/guard, weakened workflow/package/policy)',
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

    // 递归深度守卫：hook 场景内的候选 hook 会再次调用 anchor contract（嵌套契约）。
    // 嵌套调用的 hook 执行场景由最外层契约统一覆盖，避免 fixture 层层递归；
    // policy / required paths / checker 黑盒 / workflow / package 检查在每一层都执行。
    GATE_CONTRACT_DEPTH = parseInt(process.env.GATE_CONTRACT_DEPTH || '0', 10) || 0;
    process.env.GATE_CONTRACT_DEPTH = String(GATE_CONTRACT_DEPTH + 1);

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

    // 1. 候选快照物化（gate 路径 + workflow + package scripts；候选只作为被检查对象）
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
            candidateRoot = snapshot.materializePaths(repoRoot, candidateRef, CANDIDATE_PATHS).root;
            pendingCandidateRoot = candidateRoot;
            historyRef = candidateRef;
        } else {
            candidateRoot = snapshot.materializeIndexPathsTo(repoRoot,
                CANDIDATE_PATHS, fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-contract-index-')));
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
            const removed = trustedGate.policySetReduced(trustedPolicy.requiredPaths, candidatePolicy.requiredPaths);
            checks.push({
                name: 'required paths not reduced', kind: 'policy',
                expected: 'candidate keeps all trusted required paths', status: null,
                ok: removed.length === 0,
                diagnostic: removed.length > 0 ? 'candidate dropped required paths: ' + removed.join(', ') : '',
            });
            // 5.1：protectedBranches / requiredWorkflowJobs 集合不得减少（允许新增）
            const removedBranches = trustedGate.policySetReduced(
                trustedPolicy.protectedBranches, candidatePolicy.protectedBranches);
            checks.push({
                name: 'protected branches not reduced', kind: 'policy',
                expected: 'candidate keeps all trusted protected branches', status: null,
                ok: removedBranches.length === 0,
                diagnostic: removedBranches.length > 0
                    ? 'candidate dropped protected branches: ' + removedBranches.join(', ') : '',
            });
            const removedJobs = trustedGate.policySetReduced(
                trustedPolicy.requiredWorkflowJobs, candidatePolicy.requiredWorkflowJobs);
            checks.push({
                name: 'required workflow jobs not reduced', kind: 'policy',
                expected: 'candidate keeps all trusted required workflow jobs', status: null,
                ok: removedJobs.length === 0,
                diagnostic: removedJobs.length > 0
                    ? 'candidate dropped required workflow jobs: ' + removedJobs.join(', ') : '',
            });
        } else {
            checks.push({
                name: 'candidate policy proposal', kind: 'policy', expected: null, status: null, ok: true,
                diagnostic: 'candidate has no gate-policy.json (predates the policy); trusted required paths still enforced',
            });
        }

        // 3. required paths 并集（5.3）：trusted ∪ candidate；candidate 新声明的路径必须真实存在
        const required = trustedGate.checkUnionRequiredPaths(repoRoot, historyRef, candidateRoot,
            trustedPolicy.requiredPaths, candidatePolicy ? candidatePolicy.requiredPaths : []);
        checks.push({
            name: 'candidate keeps required gate files (trusted ∪ candidate)', kind: 'required-files',
            expected: 'no required path deleted or phantom-declared', status: null,
            ok: required.missing.length === 0,
            diagnostic: required.missing.length > 0
                ? 'candidate gate bundle incomplete — required paths missing: '
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

        // 3.5 candidate quality-gate.yml + package.json 语义契约（真实 YAML 解析）
        checks.push(...runWorkflowContractChecks(repoRoot, candidateRoot));
        checks.push(...runPackageContractChecks(candidateRoot));

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

        // 4. candidate checker 黑盒行为（独立 fixture + issue type 断言）
        const checkerScenarios = await runCheckerScenarios(candidateRoot, hasChecker, candidateScriptsIdentical);
        checks.push(...checkerScenarios);
        const checkerOk = checkerScenarios.every((c) => c.ok);

        // 5. candidate hooks 行为（黑盒失败后仍收集静态文本约束，执行场景跳过）
        const hookScenarios = await runHookScenarios(repoRoot, candidateRoot, trustedPolicy, hasHooks,
            candidateHooksIdentical || !checkerOk,
            !checkerOk ? 'candidate checker behavior already failed; hook execution scenarios skipped'
                : undefined);
        checks.push(...hookScenarios);
        const hooksOk = hookScenarios.every((c) => c.ok);

        // 6. 自保护：candidate contract 必须拒绝恶意下一代 gate
        const selfProtection = await runSelfProtection(repoRoot, candidateRoot, trustedPolicy, hasContract,
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
