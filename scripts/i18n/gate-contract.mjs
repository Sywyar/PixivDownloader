#!/usr/bin/env node
'use strict';
/**
 * 可信 Gate Contract：由 trusted anchor 物化并执行，黑盒验证候选 gate（index 或 ref）。
 * 本实现属于 Gate Epoch 2 单一标准；Epoch 1 及更早的兼容逻辑已整体移除。
 *
 * 用法：
 *   node gate-contract.mjs --repo-root <repo> --candidate-snapshot index
 *   node gate-contract.mjs --repo-root <repo> --candidate-ref <sha>
 *   node gate-contract.mjs --repo-root <repo> --candidate-ref <sha> --force-self-protection
 *   node gate-contract.mjs --version
 *
 * 信任模型：
 * - 本脚本自身必须运行在 trusted anchor 物化的 gate bundle 内（同目录 gate-policy.json 是
 *   可信事实）；候选快照只作为被检查对象，candidate 的 checker / contract / guard 不能自批准；
 * - Epoch 2 信任根是仓库外的受保护 annotated tag refs/tags/i18n-gate-epoch-2-root；
 *   普通候选必须由 trusted predecessor 审核；root admission 是唯一显式人工例外
 *   （candidate == root 时运行 root 自身 gate + 全量 root self-protection suite，
 *   由 --force-self-protection 关闭归纳跳过）；
 * - candidate 可以提出新 policy，但当前 trusted contract 必须审核它：gateEpoch 不得改变、
 *   contractVersion / schemaVersion / minimumTrustedVerifier 不得降低、
 *   i18nEnforcementStartCommit 不得向后移动或删除、
 *   required paths / protectedBranches / requiredWorkflowJobs / requiredPackageScripts /
 *   requiredExternalChecks 集合不得减少（允许新增）；
 * - required paths 使用 trusted ∪ candidate 并集：candidate 新声明的 required path 必须在
 *   同一候选快照中真实存在；被候选删除 → fail closed（candidate gate bundle incomplete）；
 *   candidate 早于 trusted required path 引入（enforcement start 自身等）→ 只报告不阻断；
 * - candidate checker 做黑盒行为测试（每个负面场景独立 fixture，只施加一个故障，并断言
 *   report.json 的 issue type）：合法 fixture 必须通过，坏占位符 / 缺英文文件 / missing key /
 *   stale / translation-unaccepted / invalid lock / static 失步 / 硬编码语言必须失败；
 * - candidate hooks 实际运行验证（候选 hook 文件是执行对象）：pre-commit 必须找到 trusted
 *   anchor（epoch == 2）并由 trusted checker/contract 判定；no-op pre-commit / no-op pre-push /
 *   删除 trustedGateRef 读取 / pre-push 改回使用 candidate checker → 一律失败；
 * - candidate quality-gate.yml（真实 YAML 解析）与 package.json scripts 契约：触发器、
 *   必需 job、关键行为（经 shell 规范化：注释 / || true / if false 包裹全部拒绝）、
 *   action 版本、result 传播、github.sha^ 回退禁令、root tag / ROOT_ADMISSION 机制、
 *   reusable input 优先级、trusted helper 交叉验证、gate parity 步骤；
 * - 外围 workflow（shared-snippets-check / release / nightly / publish-plugins，真实 YAML
 *   解析）语义契约：shared-snippet 必须真实运行 ./scripts/sync-shared-snippets.ps1 -Check；
 *   release / nightly / publish 发布链必须经过 quality-gate（uses + trusted_base_sha +
 *   needs 链 + publish 的 needs.quality-gate.result == 'success'），禁止 always() 绕过；
 * - gate-surface.json 清单契约：candidate manifest 只能被检查（不能决定检查范围），
 *   不得删除 trusted 条目，不得声明不存在的路径；
 * - github-ruleset-invariants.json 契约：candidate 必须携带该清单，schemaVersion 只增不减，
 *   requiredChecks 只增（trusted ⊆ candidate），requireStrict 只允许 false→true，
 *   allowBypass / allowDeletion / allowNonFastForward 只允许 true→false；
 * - 本 contract 自身就是 trusted verifier：启动时断言自身满足当前 verifier baseline
 *   （contractVersion / schemaVersion / verifier 本体文件），旧 verifier → fail closed；
 * - 外部 checker：candidate scripts/sync-shared-snippets.ps1 内容守卫 + 黑盒行为
 *   （drift fixture 必须 exit != 0，合法同步 exit 0）；
 * - 自保护：构造「下一代恶意 gate」（no-op checker / hooks / guard / workflow 弱化 /
 *   package scripts 弱化 / policy 减少 / epoch 改变），运行 candidate contract 必须拒绝它；
 * - 归纳跳过：candidate 的某部分与 trusted bundle 逐字节一致时，其行为由 trust 链归纳保证，
 *   跳过对应行为测试（只在行为可能被候选改变时才黑盒运行）；root admission 用
 *   --force-self-protection 显式关闭自保护部分的归纳跳过。
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

const CONTRACT_VERSION = '4';
const OWN_DIR = path.dirname(fileURLToPath(import.meta.url));

/** 嵌套契约调用深度（main() 初始化；runHookScenarios 用它跳过重复的 hook 执行场景）。 */
let GATE_CONTRACT_DEPTH = 0;

const APP_I18N = path.posix.join('pixivdownload-app', 'src', 'main', 'resources', 'i18n');
const STATIC_REL = path.posix.join('pixivdownload-app', 'src', 'main', 'resources', 'static', 'i18n-static');
const WORKFLOW_REL = path.posix.join('.github', 'workflows', 'quality-gate.yml');
const PACKAGE_JSON_REL = path.posix.join('package.json');
const SURFACE_REL = path.posix.join('scripts', 'ci', 'gate-surface.json');
const SYNC_REL = path.posix.join('scripts', 'sync-shared-snippets.ps1');

/** 外围发布 / 外部检查 workflow（与 gate-surface.json 的 .github/workflows 条目一一对应）。 */
const EXTERNAL_WORKFLOW_RELS = [
    path.posix.join('.github', 'workflows', 'shared-snippets-check.yml'),
    path.posix.join('.github', 'workflows', 'release.yml'),
    path.posix.join('.github', 'workflows', 'nightly.yml'),
    path.posix.join('.github', 'workflows', 'publish-plugins.yml'),
];

/** 候选快照物化路径范围：gate 文件 + CI / 外围 workflow + package scripts 必须全部可见。 */
const CANDIDATE_PATHS = [
    'scripts/i18n',
    'scripts/hooks',
    'scripts/ci',
    'scripts/sync-shared-snippets.ps1',
    '.github/workflows/quality-gate.yml',
    '.github/workflows/shared-snippets-check.yml',
    '.github/workflows/release.yml',
    '.github/workflows/nightly.yml',
    '.github/workflows/publish-plugins.yml',
    'package.json',
    'package-lock.json',
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

function hasPwsh() {
    try {
        execFileSync('pwsh', ['-NoProfile', '-Command', '$true'], { stdio: 'ignore' });
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
    const args = { repoRoot: null, mode: null, ref: null, reportRoot: null, version: false,
        forceSelfProtection: false };
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
        } else if (argv[i] === '--force-self-protection') {
            args.forceSelfProtection = true;
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
        results.push({ name: 'checker-bundle', kind: 'report', expected: null, status: null, ok: false,
            diagnostic: 'candidate has no scripts/i18n/check.mjs (part of the current verifier'
                + ' baseline; deletion must fail closed)' });
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
            // 链接失败不阻断：yaml 解析走 NODE_PATH 兜底（等价 CI 的 npm ci）
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
    for (const rel of EXTERNAL_WORKFLOW_RELS) {
        if (fs.existsSync(path.join(candidateRoot, ...rel.split('/')))) {
            writeFile(repo, rel, readFile(candidateRoot, rel));
        }
    }
    if (fs.existsSync(path.join(candidateRoot, ...SYNC_REL.split('/')))) {
        writeFile(repo, SYNC_REL, readFile(candidateRoot, SYNC_REL));
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
    // Epoch 2 单一标准：hooks 要求 epoch == 2 才运行 trusted gate
    git(['config', '--local', 'pixiv.i18n.trustedGateEpoch', '2'], repo);
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
        results.push({ name: 'hooks-bundle', kind: 'report', expected: null, status: null, ok: false,
            diagnostic: 'candidate has no scripts/hooks (part of the current verifier baseline;'
                + ' deletion must fail closed)' });
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

            // 直接场景：candidate pre-commit = exit 0 → trusted contract 必须拒绝该候选
            // （执行恶意 hook 本身只会 exit 0；正确做法是把恶意 hook 作为候选提交，
            // 由 trusted contract 黑盒审核它——见 runHookContentChecks 的锚点语义守卫）
            await hookScenario('candidate pre-commit = exit 0 must fail (trusted contract)', false, async () => {
                writeFile(repo, 'scripts/hooks/pre-commit', EXIT_ZERO);
                git(['add', '-A'], repo);
                git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'malicious hook'], repo);
                const malicious = git(['rev-parse', 'HEAD'], repo);
                const result = run(['node', path.join(OWN_DIR, 'gate-contract.mjs'),
                    '--repo-root', repo, '--candidate-ref', malicious], { cwd: repo });
                git(['reset', '-q', '--hard', 'HEAD~1'], repo, { stdio: 'ignore' });
                return result;
            });
            git(['reset', '-q', '--hard', 'HEAD'], repo, { stdio: 'ignore' });

            // 直接场景：candidate pre-commit 删除 trustedGateRef 读取（改检工作树）→ 必须拒绝
            await hookScenario('candidate pre-commit without trustedGateRef must fail', false, async () => {
                writeFile(repo, 'scripts/hooks/pre-commit',
                    '#!/usr/bin/env bash\nset -euo pipefail\nnode "$(git rev-parse --show-toplevel)/scripts/i18n/check.mjs"\n');
                git(['add', '-A'], repo);
                git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'hook without anchor'], repo);
                const malicious = git(['rev-parse', 'HEAD'], repo);
                const result = run(['node', path.join(OWN_DIR, 'gate-contract.mjs'),
                    '--repo-root', repo, '--candidate-ref', malicious], { cwd: repo });
                git(['reset', '-q', '--hard', 'HEAD~1'], repo, { stdio: 'ignore' });
                return result;
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

            // 直接场景：candidate pre-push = exit 0 → trusted contract 必须拒绝该候选
            const remote3 = tempDir('remote3');
            git(['init', '-q', '--bare', remote3], repo);
            git(['remote', 'add', 'origin3', remote3], repo);
            git(['-c', 'core.hooksPath=/dev/null', 'push', '-q', 'origin3', 'master'], repo, { stdio: 'ignore' });

            await hookScenario('candidate pre-push = exit 0 must fail (trusted contract)', false, async () => {
                writeFile(repo, 'scripts/hooks/pre-push', EXIT_ZERO);
                git(['add', '-A'], repo);
                git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'malicious pre-push'], repo);
                const malicious = git(['rev-parse', 'HEAD'], repo);
                const result = run(['node', path.join(OWN_DIR, 'gate-contract.mjs'),
                    '--repo-root', repo, '--candidate-ref', malicious], { cwd: repo });
                git(['reset', '-q', '--hard', 'HEAD~1'], repo, { stdio: 'ignore' });
                return result;
            });
            rmrf(remote3);

            // 直接场景：candidate pre-push 改回使用 candidate checker（自批准）→ 必须拒绝
            const remote4 = tempDir('remote4');
            git(['init', '-q', '--bare', remote4], repo);
            git(['remote', 'add', 'origin4', remote4], repo);
            git(['-c', 'core.hooksPath=/dev/null', 'push', '-q', 'origin4', 'master'], repo, { stdio: 'ignore' });

            await hookScenario('candidate pre-push using the candidate checker must fail', false, async () => {
                writeFile(repo, 'scripts/hooks/pre-push',
                    '#!/usr/bin/env bash\nset -euo pipefail\nnode "$(git rev-parse --show-toplevel)/scripts/i18n/check.mjs" --version\n');
                // 候选 checker = 工作树里的 exit-0 checker（hook 直接使用它 → 自我批准）
                writeFile(repo, 'scripts/i18n/check.mjs', '#!/usr/bin/env node\nprocess.exit(0);\n');
                git(['add', '-A'], repo);
                git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'self-approving pre-push'], repo);
                const malicious = git(['rev-parse', 'HEAD'], repo);
                const result = run(['node', path.join(OWN_DIR, 'gate-contract.mjs'),
                    '--repo-root', repo, '--candidate-ref', malicious], { cwd: repo });
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
    'actions/download-artifact': '7',
    'actions/setup-java': '5',
};
/** Epoch 2 门禁的最低 package scripts 集合（policy.requiredPackageScripts 在此基础上只增不减）。 */
const REQUIRED_SCRIPTS = ['test:i18n', 'i18n:check', 'i18n:generate-static', 'i18n:trust-gate',
    'i18n:gate-contract', 'i18n:gate-parity', 'test:js', 'test:web-standards', 'doctor:github-gate'];

/** github-ruleset-invariants.json 相对仓库根的路径（doctor 的期望不变量声明）。 */
const RULESET_INVARIANTS_REL = path.posix.join('scripts', 'ci', 'github-ruleset-invariants.json');

/** 可信 gate 执行必须来自物化的 trusted bundle，禁止直接运行候选工作树的 contract/guard。 */
const TRUSTED_LOC = /\$GATE_DIR|\$RUNNER_TEMP|\bguard\/out\b|materialize-trusted-gate/;

/**
 * Shell 命令序列规范化（23.1）：只保留实际可执行的命令文本。
 * - 删除纯注释行与行尾注释（# 到行尾；单引号内的 # 例外不处理，属已知保守简化）；
 * - 以 ; / && / || 拆分命令序列；
 * - 删除纯 no-op 命令（true / : / exit 0 / echo 单行）；
 * - 返回实际命令列表；空列表 = 该 step 什么都不执行。
 */
function extractCommands(script) {
    const lines = String(script || '').split(/\r?\n/);
    const statements = [];
    for (const raw of lines) {
        const line = raw.replace(/#.*$/, '').trim();
        if (!line) {
            continue;
        }
        const parts = line.split(/[;|&]{1,2}\s*/).map((p) => p.trim()).filter(Boolean);
        for (const part of parts) {
            const command = part.trim();
            if (!command) {
                continue;
            }
            if (/^(true|:|exit(\s+0)?|echo(\s.*)?)$/.test(command)) {
                continue;
            }
            statements.push(command);
        }
    }
    return statements;
}

/** 候选 step 是否实际执行过匹配命令（规范化后；注释 / no-op 不能伪装成命令）。 */
function commandsInclude(script, re) {
    return extractCommands(script).some((c) => re.test(c));
}

/** step 是否吞掉失败：|| true / || : / || exit 0 / ; true / ; exit 0 等（; exit 1 是合法失败传播）。 */
function hasNoopSwallow(script) {
    const s = String(script || '');
    return /(\|\||;)\s*(true|:)(\s|;|$|\|\||&&)/.test(s)
        || /(\|\||;)\s*exit(?:\s*0)?(?=\s*(?:;|$|\|\||&&))/.test(s)
        || /;\s*exit(?!\s*[1-9])/.test(s);
}

/** step 是否用 if false; then ... fi 条件包裹（默认跳过）。 */
function hasConditionalSkip(script) {
    return /if\s+false\s*;?\s*then/.test(String(script || ''));
}

/** step 是否被降级为纯 no-op（注释 + : / true / exit 0 / echo）。 */
function isNoopStep(script) {
    const stripped = String(script || '').replace(/#.*$/g, '').replace(/\s+/g, ' ').trim();
    if (!stripped) {
        return true;
    }
    return /^(true|:|exit(\s+0)?|echo(\s.*)?)$/.test(stripped);
}

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

/** job 的 needs 归一化为数组（string 或 array）。 */
function jobNeeds(job) {
    if (!job || typeof job.needs !== 'string' && !Array.isArray(job.needs)) {
        return [];
    }
    return typeof job.needs === 'string' ? [job.needs] : job.needs;
}

function stepShell(step) {
    return typeof step.shell === 'string' ? step.shell : '';
}

/** job 中任一 step 的规范化命令序列包含匹配命令。 */
function jobHasRun(job, re) {
    return jobSteps(job).some((s) => commandsInclude(stepRun(s), re));
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

/** step 是否运行了关键门禁命令（只有关键命令被吞掉才构成降级）。 */
const CRITICAL_RUN_RE = [
    /\bmvn\b/,
    /npm run test:js/,
    /npm run test:web-standards/,
    /npm run test:i18n/,
    /npm run i18n:check/,
    /i18n:generate-static/,
    /git diff --exit-code/,
    /gate-contract\.mjs/,
    /gate-parity\.mjs/,
    /pre-push-guard\.sh/,
    /check\.mjs/,
];

function isCriticalStep(script) {
    return extractCommands(script).some((c) => CRITICAL_RUN_RE.some((re) => re.test(c)));
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
 * 触发器 / 必需 job / job 级 continue-on-error 禁令 / 关键行为（shell 规范化）/
 * 关键命令不得改为 echo|true 或 || true / if false 包裹 / action 主版本 /
 * github.sha^ 回退禁令 / FORCE_JAVASCRIPT_ACTIONS_TO_NODE24 清理 /
 * Epoch 2 root tag 与 ROOT_ADMISSION 机制 / reusable input 优先级 / trusted helper 交叉验证 /
 * gate parity 步骤。
 * 候选缺失 workflow 文件 = 删除 baseline 文件 → fail closed（无 predates 报告路径）。
 */
function runWorkflowContractChecks(repoRoot, candidateRoot) {
    const checks = [];
    const workflowFile = path.join(candidateRoot, ...WORKFLOW_REL.split('/'));
    if (!fs.existsSync(workflowFile)) {
        pushCheck(checks, 'candidate quality-gate.yml contract', false,
            'candidate has no .github/workflows/quality-gate.yml (part of the current verifier'
                + ' baseline; deletion must fail closed)');
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
    const pushConfig = triggers.push && typeof triggers.push === 'object' ? triggers.push : {};
    const hasCandidateBranches = Object.prototype.hasOwnProperty.call(pushConfig, 'branches');
    pushCheck(checks, 'push coverage does not use a positive branches allow-list',
        !hasCandidateBranches,
        'candidate quality-gate.yml narrowed push coverage with branches: '
            + JSON.stringify(pushConfig.branches) + '; fail closed');
    try {
        const YAML = loadYamlModule(repoRoot);
        const trustedWorkflow = YAML.parse(fs.readFileSync(
            path.join(OWN_DIR, '..', '..', ...WORKFLOW_REL.split('/')), 'utf8'));
        const trustedPush = trustedWorkflow.on && trustedWorkflow.on.push
            && typeof trustedWorkflow.on.push === 'object' ? trustedWorkflow.on.push : {};
        const trustedIgnored = Array.isArray(trustedPush['branches-ignore'])
            ? trustedPush['branches-ignore']
            : (typeof trustedPush['branches-ignore'] === 'string' ? [trustedPush['branches-ignore']] : []);
        const candidateIgnored = Array.isArray(pushConfig['branches-ignore'])
            ? pushConfig['branches-ignore']
            : (typeof pushConfig['branches-ignore'] === 'string' ? [pushConfig['branches-ignore']] : []);
        const addedIgnored = candidateIgnored.filter((branch) => !trustedIgnored.includes(branch));
        pushCheck(checks, 'push excluded branches not increased', addedIgnored.length === 0,
            'candidate excluded additional push branches: ' + addedIgnored.join(', ') + '; fail closed');
    } catch (e) {
        pushCheck(checks, 'trusted push coverage can be loaded', false,
            'cannot load trusted quality-gate push coverage: ' + e.message);
    }

    // Epoch 2：workflow_dispatch 必须提供显式 root admission inputs（人工触发专用）
    const dispatch = triggers.workflow_dispatch && typeof triggers.workflow_dispatch === 'object'
        ? triggers.workflow_dispatch : {};
    const dispatchInputs = dispatch.inputs && typeof dispatch.inputs === 'object' ? dispatch.inputs : {};
    pushCheck(checks, 'workflow_dispatch exposes root_admission input',
        dispatchInputs.root_admission !== undefined,
        'workflow_dispatch must expose the explicit root_admission boolean input'
            + ' (only a human-triggered dispatch may enter ROOT_ADMISSION)');
    pushCheck(checks, 'workflow_dispatch exposes root_candidate_sha input',
        dispatchInputs.root_candidate_sha !== undefined,
        'workflow_dispatch must expose the root_candidate_sha input for root admission verification');

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

    // trusted helper 行为 / 内容检查（23.2 的 wrapper 语义应用于 helpers）：
    // 候选弱化 scripts/ci 共享实现（exit 0 / echo）必须被拒绝；CI 侧还经交叉验证兜底。
    const matHelper = path.join(candidateRoot, 'scripts', 'ci', 'materialize-trusted-gate.sh');
    if (fs.existsSync(matHelper)) {
        const matText = fs.readFileSync(matHelper, 'utf8');
        const matOk = /ls-tree/.test(matText) && /read-tree/.test(matText)
            && /checkout-index/.test(matText) && /test -s/.test(matText)
            && /pre-push-guard\.sh/.test(matText) && /minimumTrustedVerifier/.test(matText)
            && !isNoopStep(matText);
        pushCheck(checks, 'materialize-trusted-gate.sh keeps its materialization behavior', matOk,
            'candidate weakened scripts/ci/materialize-trusted-gate.sh (must keep ls-tree/read-tree/'
                + 'checkout-index/test -s/pre-push-guard.sh semantics; exit-0 stubs are refused)');
    }
    const resolver = path.join(candidateRoot, 'scripts', 'ci', 'resolve-trusted-base.mjs');
    if (fs.existsSync(resolver)) {
        const resText = fs.readFileSync(resolver, 'utf8');
        const resOk = /i18n-gate-epoch-2-root/.test(resText)
            && /ROOT_ADMISSION/.test(resText)
            && /trusted_base_sha/.test(resText)
            && /minimumTrustedVerifier/.test(resText)
            && /refs\/remotes\/origin/.test(resText)
            && /--ref/.test(resText)
            && /args\.gitRef\s*===\s*'refs\/heads\/'\s*\+\s*args\.defaultBranch[\s\S]{0,500}args\.before[\s\S]{0,1500}resolveForkBase/.test(resText)
            && /isAncestor\(repoRoot, base, candidate\)/.test(resText)
            && (resText.match(/isAncestor\(repoRoot, (root|base),/g) || []).length >= 3
            && /'merge-base', candidate/.test(resText)
            && !isNoopStep(resText);
        pushCheck(checks, 'resolve-trusted-base.mjs keeps root/input-precedence/ancestry semantics', resOk,
            'candidate weakened scripts/ci/resolve-trusted-base.mjs (must keep the Epoch 2 root tag /'
                + ' ROOT_ADMISSION / trusted_base_sha input-precedence / minimumTrustedVerifier'
                + ' / protected-default-history logic plus the full ancestry'
                + ' provenance root <= base < candidate — root->candidate, root->base, base->candidate'
                + ' isAncestor checks and the new-branch fork-base merge-base(candidate, default);'
                + ' exit-0 stubs are refused)');
    }
    const doctorFile = path.join(candidateRoot, 'scripts', 'ci', 'doctor-github-ruleset.mjs');
    if (fs.existsSync(doctorFile)) {
        const doctorText = fs.readFileSync(doctorFile, 'utf8');
        const doctorOk = (doctorText.match(
            /bypassActors\.length\s*>\s*0\s*&&\s*!invariants\.allowBypass/g) || []).length >= 2
            && !/\.filter\([\s\S]{0,200}bypass_mode\s*===\s*['"]always['"]/.test(doctorText)
            && !isNoopStep(doctorText);
        pushCheck(checks, 'doctor-github-ruleset rejects every bypass actor when allowBypass=false',
            doctorOk,
            'candidate weakened doctor-github-ruleset.mjs: allowBypass=false must reject every'
                + ' master/root-tag bypass actor, not only bypass_mode=always');
    }

    // 关键行为 step 的失败吞没 / 条件跳过禁令：只针对运行关键门禁命令的 step
    // （bootstrap 里 get-or-empty 的 `|| true` 解析惯用法不构成降级）；
    // 必需 job 里任何纯 no-op step 一律拒绝。
    for (const jobId of REQUIRED_WORKFLOW_JOBS) {
        const job = jobs[jobId];
        for (const step of jobSteps(job)) {
            if (!stepRun(step)) {
                continue;
            }
            if (isCriticalStep(stepRun(step))) {
                pushCheck(checks, jobId + ': no critical step swallows failures (|| true / ; true / exit 0)',
                    !hasNoopSwallow(stepRun(step)),
                    'candidate ' + jobId + ' step "' + (stepName(step) || '(unnamed)')
                        + '" swallows the gate command with || true / ; true / ; exit 0; fail closed');
                pushCheck(checks, jobId + ': no critical step hides behind if false; then',
                    !hasConditionalSkip(stepRun(step)),
                    'candidate ' + jobId + ' step "' + (stepName(step) || '(unnamed)')
                        + '" hides the gate command behind if false; then ... fi; fail closed');
            }
            pushCheck(checks, jobId + ': no step reduced to comments + no-op',
                !isNoopStep(stepRun(step)),
                'candidate ' + jobId + ' step "' + (stepName(step) || '(unnamed)')
                    + '" reduces to comments + no-op commands; fail closed');
        }
    }

    // 6.3 关键行为（23.1：规范化 shell，注释不能伪装成命令）
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
    pushCheck(checks, 'java-tests: no -DskipTests in the test step',
        !jobSteps(jJava).some((s) => commandsInclude(stepRun(s), /mvn/) && /-DskipTests/.test(stepRun(s))),
        'java-tests must never run mvn with -DskipTests');

    const jJs = jobs['javascript-tests'];
    pushCheck(checks, 'javascript-tests: checkout tested commit', jobHasUses(jJs, /actions\/checkout@/),
        'javascript-tests must checkout the tested commit');
    pushCheck(checks, 'javascript-tests: setup Node 24', jobHasStepWith(jJs, /actions\/setup-node@/, 'node-version', /24/),
        'javascript-tests must set up Node.js 24 via actions/setup-node');
    pushCheck(checks, 'javascript-tests: npm ci', jobHasRun(jJs, /npm\s+ci/),
        'javascript-tests must run npm ci');
    pushCheck(checks, 'javascript-tests: npm run test:js', jobHasRun(jJs, /npm run test:js/),
        'javascript-tests must run npm run test:js');
    pushCheck(checks, 'javascript-tests: npm run test:web-standards', jobHasRun(jJs, /npm run test:web-standards/),
        'javascript-tests must run npm run test:web-standards');

    // 模式解析：root tag + ROOT_ADMISSION 机制必须在三个 gate job 中体现
    for (const jobId of ['signature-guard', 'trusted-gate-contract', 'i18n-check']) {
        const job = jobs[jobId];
        const hasMode = jobSteps(job).some((s) => /i18n-gate-epoch-2-root/.test(stepRun(s))
            && /ROOT_ADMISSION/.test(stepRun(s)));
        pushCheck(checks, jobId + ': Epoch 2 root tag + ROOT_ADMISSION mode machinery',
            hasMode,
            jobId + ' must resolve refs/tags/i18n-gate-epoch-2-root and branch on ROOT_ADMISSION/NORMAL');
        const hasInputFirst = jobSteps(job).some((s) => /inputs\.trusted_base_sha/.test(stepRun(s)));
        pushCheck(checks, jobId + ': trusted_base_sha input takes priority (reusable semantics)',
            hasInputFirst,
            jobId + ' must prefer the explicit inputs.trusted_base_sha before any event-based fallback'
                + ' (github.event_name == workflow_call cannot be assumed)');
        const hasHelper = jobSteps(job).some((s) => /resolve-trusted-base\.mjs/.test(stepRun(s)));
        pushCheck(checks, jobId + ': trusted helper cross-validation (resolve-trusted-base.mjs)',
            hasHelper,
            jobId + ' must cross-validate the inline bootstrap against the trusted resolve-trusted-base.mjs');
        const hasMatHelper = jobSteps(job).some((s) => /materialize-trusted-gate\.sh/.test(stepRun(s)));
        pushCheck(checks, jobId + ': materialization cross-check with the trusted helper',
            hasMatHelper,
            jobId + ' must cross-check inline materialization against scripts/ci/materialize-trusted-gate.sh');
        const minimumRunsInBothModes = jobSteps(job).some((s) =>
            /ROOT ADMISSION MODE:[^\n]*\n\s*fi[\s\S]{0,300}minimum_json=.*minimumTrustedVerifier/
                .test(stepRun(s)));
        pushCheck(checks, jobId + ': minimumTrustedVerifier applies to NORMAL and ROOT_ADMISSION',
            minimumRunsInBothModes,
            jobId + ' must close the NORMAL/ROOT_ADMISSION branch before enforcing'
                + ' minimumTrustedVerifier; NORMAL candidates cannot skip the baseline');
    }

    const jGuard = jobs['signature-guard'];
    pushCheck(checks, 'signature-guard: trusted base determination',
        jobHasRun(jGuard, /(base_sha|event\.before|trusted_base_sha|ROOT_ADMISSION)/),
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
        jobHasRun(jContract, /(base_sha|event\.before|trusted_base_sha|ROOT_ADMISSION)/),
        'trusted-gate-contract must determine a trusted base from GitHub event data');
    // 只匹配实际执行 contract 的 step（同时含 gate-contract.mjs 与 --candidate-ref；
    // bootstrap step 里的 test -f gate-contract.mjs 引用不算）
    const contractSteps = jobSteps(jContract).filter((s) => /gate-contract\.mjs/.test(stepRun(s))
        && /--candidate-ref/.test(stepRun(s)));
    const contractOk = contractSteps.length > 0
        && contractSteps.every((s) => TRUSTED_LOC.test(stepRun(s))
            && /--candidate-ref/.test(stepRun(s)) && /github\.sha/.test(stepRun(s)));
    pushCheck(checks, 'trusted-gate-contract: trusted contract checks github.sha', contractOk,
        'trusted-gate-contract must run the materialized trusted gate-contract.mjs'
            + ' with --candidate-ref ${{ github.sha }} (candidate contract self-approval is refused)');
    const rootContractStep = contractSteps[0] || null;
    pushCheck(checks, 'trusted-gate-contract: root admission forces self-protection',
        !!rootContractStep && /--force-self-protection/.test(stepRun(rootContractStep)),
        'trusted-gate-contract must run the contract with --force-self-protection'
            + ' so ROOT_ADMISSION runs the full root self-protection suite');
    pushCheck(checks, 'trusted-gate-contract: gate parity audit step',
        jobHasRun(jContract, /gate-parity\.mjs/),
        'trusted-gate-contract must run scripts/ci/gate-parity.mjs (no gate may be weakened)');
    pushCheck(checks, 'trusted-gate-contract: report upload', jobHasUploadAlways(jContract),
        'trusted-gate-contract must upload the contract report with if: always()');

    const jI18n = jobs['i18n-check'];
    pushCheck(checks, 'i18n-check: trusted base determination',
        jobHasRun(jI18n, /(base_sha|event\.before|trusted_base_sha|ROOT_ADMISSION)/),
        'i18n-check must determine a trusted base from GitHub event data');
    const ciTestsOk = jobSteps(jI18n).some((s) => commandsInclude(stepRun(s), /npm run test:i18n/)
        && s.env && typeof s.env.CI === 'string' && /true/i.test(s.env.CI));
    pushCheck(checks, 'i18n-check: CI=true npm run test:i18n', ciTestsOk,
        'i18n-check must run npm run test:i18n with CI=true');
    const i18nContractSteps = jobSteps(jI18n).filter((s) => /gate-contract\.mjs/.test(stepRun(s))
        && /--candidate-ref/.test(stepRun(s)));
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
    pushCheck(checks, 'i18n-check: gate parity audit step',
        jobHasRun(jI18n, /gate-parity\.mjs/),
        'i18n-check must run scripts/ci/gate-parity.mjs (no gate may be weakened)');
    pushCheck(checks, 'i18n-check: report upload', jobHasUploadAlways(jI18n),
        'i18n-check must upload the i18n report with if: always()');
    pushCheck(checks, 'i18n-check: final propagation',
        jobHasRun(jI18n, /outcome/) && jobHasRun(jI18n, /GITHUB_STEP_SUMMARY|check_outcome/),
        'i18n-check must propagate all collected outcomes (tests/trusted/ref/worktree/static) at the end');

    // 关键命令不得改成 echo / true（各必需 job 的行为检查已覆盖对应 run 内容）
    const bannedRuns = [];
    for (const jobId of REQUIRED_WORKFLOW_JOBS) {
        const job = jobs[jobId];
        for (const step of jobSteps(job)) {
            if (!stepRun(step)) {
                continue;
            }
            if (isNoopStep(stepRun(step))) {
                bannedRuns.push(jobId + ': ' + (stepName(step) || '(unnamed step)'));
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

    // root admission 运行模式提示：ROOT ADMISSION MODE 必须在 workflow 中出现
    const hasRootModeBanner = Object.values(jobs).some((job) =>
        jobSteps(job).some((s) => /ROOT ADMISSION MODE/.test(stepRun(s))));
    pushCheck(checks, 'ROOT ADMISSION MODE banner is explicit', hasRootModeBanner,
        'the workflow must explicitly print ROOT ADMISSION MODE when running the root gate');

    return checks;
}

// ---------------------------------------------------------------------------
// 外围 workflow 语义契约（shared-snippets / release / nightly / publish-plugins）
// ---------------------------------------------------------------------------

/** 外部 workflow 的最低语义契约：name / triggers / jobs。 */
const EXTERNAL_WORKFLOW_SPECS = {
    '.github/workflows/shared-snippets-check.yml': {
        requiredName: 'Shared Snippet Drift Check',
        requiredTriggers: ['push', 'pull_request', 'workflow_dispatch'],
        requiredJobs: ['check-shared-snippets'],
    },
    '.github/workflows/release.yml': {
        requiredName: 'Release',
        requiredTriggers: ['push', 'workflow_dispatch'],
        requiredJobs: ['trusted-base', 'draft-quality-gate', 'publish-plugins', 'build-jar',
            'build-windows-installer', 'release', 'create-draft-release'],
    },
    '.github/workflows/nightly.yml': {
        requiredName: 'Nightly Build',
        requiredTriggers: ['schedule', 'workflow_dispatch'],
        requiredJobs: ['resolve-version', 'publish-plugins', 'build-jar', 'build-windows-installer',
            'release-nightly'],
    },
    '.github/workflows/publish-plugins.yml': {
        requiredName: 'Publish plugins',
        requiredTriggers: ['workflow_call', 'workflow_dispatch'],
        requiredJobs: ['trusted-base', 'quality-gate', 'publish'],
    },
};

/** job 的 with 输入值（reusable workflow 调用参数）。 */
function jobWithValue(job, key) {
    return job && job.with && typeof job.with[key] === 'string' ? job.with[key] : '';
}

/** job 级 uses（reusable workflow 调用：uses 在 job 顶层而非 step）。 */
function jobUsesValue(job) {
    return job && typeof job.uses === 'string' ? job.uses : '';
}

function jobHasUsesAtJobLevel(job, re) {
    return re.test(jobUsesValue(job));
}

/**
 * 外围 workflow 语义契约（真实 YAML 解析）：
 * - shared-snippets-check.yml：冻结 name / 触发器 / check-shared-snippets job /
 *   真实 ./scripts/sync-shared-snippets.ps1 -Check 命令；删除 -Check / 改 true / echo /
 *   || true / ; true / continue-on-error / if false / job-level continue-on-error 全部拒绝；
 * - release.yml：draft-quality-gate（workflow_dispatch）必须 uses quality-gate.yml 并传
 *   trusted_base_sha；tag push 链 publish-plugins → build-jar（needs publish-plugins）→
 *   build-windows-installer → release（needs build-jar + build-windows-installer），
 *   release / build 链不得 always() 绕过失败；
 * - publish-plugins.yml：trusted-base → quality-gate（uses quality-gate.yml +
 *   trusted_base_sha）→ publish（needs quality-gate + if 必须检查
 *   needs.quality-gate.result == 'success'）；禁止 always() / 无 success 检查的 !cancelled()；
 * - nightly.yml：publish-plugins（uses publish-plugins.yml）→ build-jar（needs
 *   publish-plugins，即 quality gate 传递生效）→ release-nightly（needs 构建产物）。
 * 候选缺文件 = 删除 baseline 文件 → fail closed（无 predates 报告路径）。
 */
function runExternalWorkflowContracts(repoRoot, candidateRoot) {
    const checks = [];
    let yamlMod = null;
    let yamlLoaded = false;
    for (const rel of EXTERNAL_WORKFLOW_RELS) {
        const file = path.join(candidateRoot, ...rel.split('/'));
        if (!fs.existsSync(file)) {
            checks.push({ name: rel + ' contract', kind: 'workflow', expected: 'present',
                status: null, ok: false,
                diagnostic: rel + ' is missing from the candidate snapshot (part of the current'
                    + ' verifier baseline; deletion must fail closed)' });
            continue;
        }
        const spec = EXTERNAL_WORKFLOW_SPECS[rel];
        let doc;
        try {
            if (!yamlLoaded) {
                yamlMod = loadYamlModule(repoRoot);
                yamlLoaded = true;
            }
            doc = yamlMod.parse(fs.readFileSync(file, 'utf8'));
        } catch (e) {
            pushCheck(checks, rel + ' parses as YAML', false,
                'cannot parse the candidate ' + rel + ': ' + e.message);
            continue;
        }
        if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
            pushCheck(checks, rel + ' structure', false, rel + ' must be a YAML mapping');
            continue;
        }
        pushCheck(checks, rel + ': workflow name is preserved', doc.name === spec.requiredName,
            rel + ' must keep the exact name "' + spec.requiredName + '"'
                + ' (got ' + JSON.stringify(doc.name) + '); the required check context depends on it');

        // action 主版本：外围 workflow 的官方 action 也必须是项目认可的 maintained major
        const jobsAll = doc.jobs && typeof doc.jobs === 'object' ? doc.jobs : {};
        const badActions = [];
        for (const jobId of Object.keys(jobsAll)) {
            for (const step of jobSteps(jobsAll[jobId])) {
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
        pushCheck(checks, rel + ': official actions use the approved maintained majors',
            badActions.length === 0,
            rel + ' uses deprecated action versions (expected '
                + Object.entries(APPROVED_ACTIONS).map(([a, v]) => a + '@v' + v).join(', ')
                + ' or release SHA pins): ' + badActions.join(', '));

        const triggers = doc.on && typeof doc.on === 'object' ? doc.on : {};
        for (const trigger of spec.requiredTriggers) {
            pushCheck(checks, rel + ': trigger ' + trigger + ' is preserved', triggers[trigger] !== undefined,
                rel + ' dropped the ' + trigger + ' trigger');
        }
        const jobs = doc.jobs && typeof doc.jobs === 'object' ? doc.jobs : {};
        for (const jobId of spec.requiredJobs) {
            pushCheck(checks, rel + ': job ' + jobId + ' is preserved',
                jobs[jobId] && typeof jobs[jobId] === 'object',
                rel + ' dropped the required job ' + jobId);
        }

        if (rel === '.github/workflows/shared-snippets-check.yml') {
            // push 触发器必须覆盖受保护默认分支 master
            const pushOn = triggers.push && typeof triggers.push === 'object' ? triggers.push : {};
            const branches = pushOn.branches || [];
            pushCheck(checks, rel + ': push trigger covers master',
                Array.isArray(branches) && branches.includes('master'),
                rel + ' must run on push to master');
            const job = jobs['check-shared-snippets'];
            if (job) {
                pushCheck(checks, rel + ': job must not set continue-on-error',
                    !job['continue-on-error'],
                    rel + ' sets continue-on-error on check-shared-snippets');
                const checkSteps = jobSteps(job).filter((s) => commandsInclude(stepRun(s), /sync-shared-snippets\.ps1/));
                pushCheck(checks, rel + ': runs the real -Check command',
                    checkSteps.length > 0
                        && checkSteps.every((s) => commandsInclude(stepRun(s), /-Check/)
                            && !hasNoopSwallow(stepRun(s)) && !hasConditionalSkip(stepRun(s))
                            && !isNoopStep(stepRun(s)) && !s['continue-on-error']
                            && /pwsh|powershell/i.test(stepShell(s))),
                    rel + ' must run ./scripts/sync-shared-snippets.ps1 -Check under pwsh/powershell'
                        + ' (deleting -Check / echo / true / || true / ; true / continue-on-error /'
                        + ' if false / non-pwsh shell are all refused)');
            }
        }

        if (rel === '.github/workflows/release.yml') {
            const jDraft = jobs['draft-quality-gate'];
            const jPublishPlugins = jobs['publish-plugins'];
            const jBuildJar = jobs['build-jar'];
            const jWin = jobs['build-windows-installer'];
            const jRelease = jobs['release'];
            const jDraftRelease = jobs['create-draft-release'];
            pushCheck(checks, 'release: draft-quality-gate uses the quality gate',
                !!jDraft && jobHasUsesAtJobLevel(jDraft, /\.github\/workflows\/quality-gate\.yml/),
                'release workflow_dispatch must call ./.github/workflows/quality-gate.yml'
                    + ' (no manual dispatch may skip the gate)');
            pushCheck(checks, 'release: draft-quality-gate needs trusted-base',
                !!jDraft && jobNeeds(jDraft).includes('trusted-base'),
                'release draft-quality-gate must depend on trusted-base');
            pushCheck(checks, 'release: draft-quality-gate passes trusted_base_sha',
                !!jDraft && /needs\.trusted-base\.outputs\.sha/.test(jobWithValue(jDraft, 'trusted_base_sha')),
                'release draft-quality-gate must pass the trusted_base_sha from trusted-base'
                    + ' (the gate proves the base; the caller only proposes it)');
            pushCheck(checks, 'release: create-draft-release needs draft-quality-gate',
                !!jDraftRelease && jobNeeds(jDraftRelease).includes('draft-quality-gate'),
                'create-draft-release must depend on the gated draft-quality-gate');
            pushCheck(checks, 'release: tag push publish-plugins uses publish-plugins.yml',
                !!jPublishPlugins && jobHasUsesAtJobLevel(jPublishPlugins, /\.github\/workflows\/publish-plugins\.yml/),
                'release tag push must call ./.github/workflows/publish-plugins.yml');
            pushCheck(checks, 'release: build-jar needs publish-plugins',
                !!jBuildJar && jobNeeds(jBuildJar).includes('publish-plugins'),
                'release build-jar must depend on publish-plugins (plugin gate failure blocks the app jar)');
            pushCheck(checks, 'release: build-windows-installer needs build-jar',
                !!jWin && jobNeeds(jWin).includes('build-jar'),
                'release build-windows-installer must depend on build-jar');
            pushCheck(checks, 'release: release needs both build artifacts',
                !!jRelease && jobNeeds(jRelease).includes('build-jar')
                    && jobNeeds(jRelease).includes('build-windows-installer'),
                'release must depend on build-jar AND build-windows-installer');
            for (const [jobId, job] of [['build-jar', jBuildJar], ['build-windows-installer', jWin],
                ['release', jRelease], ['create-draft-release', jDraftRelease]]) {
                if (job) {
                    pushCheck(checks, 'release: ' + jobId + ' never bypasses failures with always()',
                        !/always\(\)/.test(stepIf(job)),
                        'release ' + jobId + ' uses if: always() / always(); failure bypass is refused');
                }
            }
        }

        if (rel === '.github/workflows/publish-plugins.yml') {
            const jGate = jobs['quality-gate'];
            const jPublish = jobs['publish'];
            pushCheck(checks, 'publish: quality-gate uses the quality gate',
                !!jGate && jobHasUsesAtJobLevel(jGate, /\.github\/workflows\/quality-gate\.yml/),
                'publish-plugins must call ./.github/workflows/quality-gate.yml');
            pushCheck(checks, 'publish: quality-gate needs trusted-base',
                !!jGate && jobNeeds(jGate).includes('trusted-base'),
                'publish quality-gate must depend on trusted-base');
            pushCheck(checks, 'publish: quality-gate passes trusted_base_sha',
                !!jGate && /needs\.trusted-base\.outputs\.sha/.test(jobWithValue(jGate, 'trusted_base_sha')),
                'publish quality-gate must pass the trusted_base_sha from trusted-base');
            pushCheck(checks, 'publish: publish needs quality-gate',
                !!jPublish && jobNeeds(jPublish).includes('quality-gate'),
                'publish must depend on quality-gate');
            if (jPublish) {
                const publishIf = stepIf(jPublish);
                pushCheck(checks, 'publish: publish if checks needs.quality-gate.result == success',
                    /needs\.quality-gate\.result/.test(publishIf) && /success/.test(publishIf),
                    'publish must gate on needs.quality-gate.result == \'success\''
                        + ' (always() / !cancelled() without a success check are refused)');
                pushCheck(checks, 'publish: publish never bypasses with always()',
                    !/always\(\)/.test(publishIf),
                    'publish uses if: always(); failure bypass is refused');
                pushCheck(checks, 'publish: publish must not set continue-on-error',
                    !jPublish['continue-on-error'],
                    'publish sets continue-on-error; the gate result would be swallowed');
            }
        }

        if (rel === '.github/workflows/nightly.yml') {
            const jPublishPlugins = jobs['publish-plugins'];
            const jBuildJar = jobs['build-jar'];
            const jWin = jobs['build-windows-installer'];
            const jRelease = jobs['release-nightly'];
            pushCheck(checks, 'nightly: publish-plugins uses publish-plugins.yml',
                !!jPublishPlugins && jobHasUsesAtJobLevel(jPublishPlugins, /\.github\/workflows\/publish-plugins\.yml/),
                'nightly must call ./.github/workflows/publish-plugins.yml (which gates on the quality gate)');
            pushCheck(checks, 'nightly: build-jar needs publish-plugins',
                !!jBuildJar && jobNeeds(jBuildJar).includes('publish-plugins'),
                'nightly build-jar must depend on publish-plugins (nightly artifacts cannot be produced'
                    + ' unless the required quality gates succeeded)');
            pushCheck(checks, 'nightly: build-windows-installer needs build-jar',
                !!jWin && jobNeeds(jWin).includes('build-jar'),
                'nightly build-windows-installer must depend on build-jar');
            pushCheck(checks, 'nightly: release-nightly needs both build artifacts',
                !!jRelease && jobNeeds(jRelease).includes('build-jar')
                    && jobNeeds(jRelease).includes('build-windows-installer'),
                'nightly release-nightly must depend on build-jar AND build-windows-installer');
            if (jRelease) {
                pushCheck(checks, 'nightly: release-nightly never bypasses failures with always()',
                    !/always\(\)/.test(stepIf(jRelease)),
                    'nightly release-nightly uses if: always(); failure bypass is refused');
            }
        }
    }
    return checks;
}

// ---------------------------------------------------------------------------
// Gate Surface 清单契约（candidate manifest 只能被检查，不能决定检查范围）
// ---------------------------------------------------------------------------

function runGateSurfaceContract(candidateRoot, trustedPolicy) {
    const checks = [];
    const candidateFile = path.join(candidateRoot, ...SURFACE_REL.split('/'));
    const trustedFile = path.join(OWN_DIR, '..', 'ci', 'gate-surface.json');
    if (!fs.existsSync(candidateFile)) {
        // gate-surface.json 属于当前 verifier baseline：候选缺失 = 删除 → fail closed
        pushCheck(checks, 'candidate gate-surface.json present', false,
            'candidate has no scripts/ci/gate-surface.json (the gate surface manifest is part of'
                + ' the current verifier baseline; deletion must fail closed)');
        return checks;
    }
    let candidateSurface = null;
    try {
        candidateSurface = JSON.parse(fs.readFileSync(candidateFile, 'utf8'));
    } catch (e) {
        pushCheck(checks, 'candidate gate-surface.json parses as JSON', false,
            'cannot parse the candidate gate-surface.json: ' + e.message);
        return checks;
    }
    const candidatePaths = Array.isArray(candidateSurface && candidateSurface.paths)
        ? candidateSurface.paths.filter((p) => typeof p === 'string') : [];
    pushCheck(checks, 'candidate gate-surface.json declares a non-empty path list',
        candidatePaths.length > 0,
        'candidate gate-surface.json must declare a non-empty paths array');
    // trusted manifest 缺失 → trusted verifier 低于 baseline → fail closed（无兼容路径）
    if (!fs.existsSync(trustedFile)) {
        pushCheck(checks, 'trusted verifier carries gate-surface.json', false,
            'trusted verifier does not satisfy the current verifier baseline'
                + ' (missing scripts/ci/gate-surface.json); fail closed');
        return checks;
    }
    let trustedSurface = null;
    try {
        trustedSurface = JSON.parse(fs.readFileSync(trustedFile, 'utf8'));
    } catch (e) {
        pushCheck(checks, 'trusted gate-surface.json parses as JSON', false,
            'trusted gate-surface.json is invalid; fail closed: ' + e.message);
        return checks;
    }
    const trustedPaths = Array.isArray(trustedSurface && trustedSurface.paths)
        ? trustedSurface.paths.filter((p) => typeof p === 'string') : [];
    const removed = trustedPaths.filter((p) => !candidatePaths.includes(p));
    pushCheck(checks, 'candidate gate-surface.json keeps the trusted gate surface',
        removed.length === 0,
        'candidate dropped gate surface entries: ' + removed.join(', ')
            + '; pre-commit / pre-push / contract / parity must share one surface');
    // phantom 声明禁令：候选 manifest 里声明的每个路径必须在同一候选快照中真实存在
    const phantom = candidatePaths.filter((p) => !fs.existsSync(path.join(candidateRoot, ...p.split('/'))));
    pushCheck(checks, 'candidate gate-surface.json declares only real paths',
        phantom.length === 0,
        'candidate gate-surface.json declares paths that do not exist in the candidate snapshot: '
            + phantom.join(', ') + '; the candidate manifest cannot enlarge what is checked');
    return checks;
}

// ---------------------------------------------------------------------------
// github-ruleset-invariants.json 契约（Ruleset 安全语义只增不减 + 自保护）
// ---------------------------------------------------------------------------

/**
 * 候选 github-ruleset-invariants.json 审核：
 * - 文件必须存在（当前 verifier baseline 组成）；
 * - schemaVersion 必须是整数且 >= trusted（缺失 → fail closed）；
 * - master.requiredChecks：trusted ⊆ candidate（只能增加）；
 * - master.requireStrict 只允许 false→true；master / root-tag 的
 *   allowBypass / allowDeletion / allowNonFastForward 只允许 true→false；
 * - trusted 文件缺失 → trusted verifier 低于 baseline → fail closed。
 */
function runRulesetInvariantsContract(candidateRoot) {
    const checks = [];
    const candidateFile = path.join(candidateRoot, ...RULESET_INVARIANTS_REL.split('/'));
    if (!fs.existsSync(candidateFile)) {
        pushCheck(checks, 'candidate github-ruleset-invariants.json present', false,
            'candidate has no scripts/ci/github-ruleset-invariants.json (part of the current'
                + ' verifier baseline; deletion must fail closed)');
        return checks;
    }
    let candidate;
    try {
        candidate = JSON.parse(fs.readFileSync(candidateFile, 'utf8'));
    } catch (e) {
        pushCheck(checks, 'candidate github-ruleset-invariants.json parses as JSON', false,
            'candidate github-ruleset-invariants.json is invalid: ' + e.message);
        return checks;
    }
    const cs = Number.isInteger(candidate.schemaVersion) ? candidate.schemaVersion : null;
    if (cs === null) {
        pushCheck(checks, 'candidate ruleset invariants schemaVersion present', false,
            'candidate github-ruleset-invariants.json has no integer schemaVersion; fail closed');
        return checks;
    }
    const trustedFile = path.join(OWN_DIR, '..', '..', ...RULESET_INVARIANTS_REL.split('/'));
    if (!fs.existsSync(trustedFile)) {
        pushCheck(checks, 'trusted verifier carries github-ruleset-invariants.json', false,
            'trusted verifier does not satisfy the current verifier baseline; fail closed');
        return checks;
    }
    let trusted;
    try {
        trusted = JSON.parse(fs.readFileSync(trustedFile, 'utf8'));
    } catch (e) {
        pushCheck(checks, 'trusted github-ruleset-invariants.json parses as JSON', false,
            'trusted github-ruleset-invariants.json is invalid: ' + e.message);
        return checks;
    }
    const ts = Number.isInteger(trusted.schemaVersion) ? trusted.schemaVersion : null;
    pushCheck(checks, 'ruleset invariants schemaVersion not lowered',
        ts !== null && cs >= ts,
        'candidate ruleset invariants schemaVersion ' + cs + (ts === null
            ? ' (trusted schemaVersion missing)' : ' < trusted ' + ts) + '; fail closed');

    const tMaster = trusted.master && typeof trusted.master === 'object' ? trusted.master : null;
    const cMaster = candidate.master && typeof candidate.master === 'object' ? candidate.master : null;
    const rootTagEntries = (value) => Object.entries(value)
        .filter(([name, rule]) => /^i18n-gate-epoch-[2-9][0-9]*-root$/.test(name)
            && rule && typeof rule === 'object');
    const trustedTags = new Map(rootTagEntries(trusted));
    const candidateTags = new Map(rootTagEntries(candidate));
    const tChecks = Array.isArray(tMaster && tMaster.requiredChecks)
        ? tMaster.requiredChecks.filter((c) => typeof c === 'string') : [];
    const cChecks = Array.isArray(cMaster && cMaster.requiredChecks)
        ? cMaster.requiredChecks.filter((c) => typeof c === 'string') : [];
    const removedChecks = tChecks.filter((c) => !cChecks.includes(c));
    pushCheck(checks, 'ruleset required checks not reduced', removedChecks.length === 0,
        'candidate dropped required status checks: ' + removedChecks.join(', '));
    for (const [key, stricterIs] of [['requireStrict', true], ['allowBypass', false],
        ['allowDeletion', false], ['allowNonFastForward', false]]) {
        if (!tMaster || !cMaster || typeof tMaster[key] !== 'boolean' || typeof cMaster[key] !== 'boolean') {
            pushCheck(checks, 'ruleset master.' + key + ' strictness not weakened', false,
                'ruleset invariants master.' + key + ' must be a boolean on both sides; fail closed');
            continue;
        }
        const weakened = stricterIs
            ? (tMaster[key] === true && cMaster[key] === false)
            : (tMaster[key] === false && cMaster[key] === true);
        pushCheck(checks, 'ruleset master.' + key + ' strictness not weakened', !weakened,
            'candidate weakened master.' + key + ' from ' + tMaster[key] + ' to '
                + cMaster[key] + '; fail closed');
    }
    for (const [name, tTag] of trustedTags) {
        const cTag = candidateTags.get(name);
        for (const key of ['allowDeletion', 'allowNonFastForward', 'allowBypass']) {
            const valid = !!cTag && typeof tTag[key] === 'boolean' && typeof cTag[key] === 'boolean';
            const weakened = valid && tTag[key] === false && cTag[key] === true;
            pushCheck(checks, 'ruleset root tag ' + name + ' ' + key + ' strictness not weakened',
                valid && !weakened,
                'candidate removed or weakened root-tag ' + name + '.' + key + '; fail closed');
        }
    }
    for (const [name, cTag] of candidateTags) {
        for (const key of ['allowDeletion', 'allowNonFastForward', 'allowBypass']) {
            pushCheck(checks, 'ruleset root tag ' + name + ' ' + key + ' is fail-closed',
                cTag[key] === false,
                'candidate root-tag ' + name + '.' + key + ' must be false; fail closed');
        }
    }
    return checks;
}

// ---------------------------------------------------------------------------
// 外部 checker 行为测试（candidate scripts/sync-shared-snippets.ps1）
// ---------------------------------------------------------------------------

/**
 * 外部 checker 行为测试（candidate scripts/sync-shared-snippets.ps1 是执行对象）：
 * - 内容守卫：必须保留 -Check / drift 失败语义（exit 1），不得退化为 no-op stub；
 * - 行为：drift fixture（shared source != .user.js 标记区）→ -Check 必须 exit != 0；
 *   合法同步（无 -Check 写回）后重新 -Check 必须 exit 0；
 * - 历史已引入却被删除 → fail closed（本轮起由内容守卫 + parity + gate-surface 清单拦截；
 *   requiredPaths 条目由下一个 NORMAL successor 落地）。
 * 与 trusted bundle 逐字节一致时归纳跳过（行为由信任链保证）。
 */
async function runSyncScenarios(repoRoot, candidateRoot, skip) {
    const results = [];
    if (skip) {
        results.push({ name: 'sync-shared-snippets.ps1 behavior (black-box)', kind: 'external-checker',
            expected: null, status: null, ok: true,
            diagnostic: 'candidate scripts/sync-shared-snippets.ps1 is byte-identical to the trusted'
                + ' script; behavior is guaranteed by the trust chain (inductive skip)' });
        return results;
    }
    const scriptFile = path.join(candidateRoot, ...SYNC_REL.split('/'));
    if (!fs.existsSync(scriptFile)) {
        results.push({ name: 'sync-shared-snippets.ps1 content', kind: 'external-checker',
            expected: 'present', status: null, ok: false,
            diagnostic: 'scripts/sync-shared-snippets.ps1 is missing from the candidate snapshot'
                + ' (part of the current verifier baseline; deletion must fail closed)' });
        return results;
    }
    const content = fs.readFileSync(scriptFile, 'utf8');
    const contentOk = /-Check/.test(content) && /exit\s+1/.test(content)
        && /[Dd]rift/.test(content) && !isNoopStep(content);
    results.push({
        name: 'sync-shared-snippets.ps1 keeps the -Check drift gate',
        kind: 'external-checker', expected: 'real -Check + exit-1 drift semantics', status: null,
        ok: contentOk,
        diagnostic: contentOk ? '' : 'candidate scripts/sync-shared-snippets.ps1 was weakened'
            + ' (missing -Check / exit 1 / drift detection, or reduced to a no-op stub); fail closed',
    });
    if (!hasPwsh()) {
        results.push({ name: 'sync-shared-snippets.ps1 behavior (execution)', kind: 'report',
            expected: null, status: null, ok: true,
            diagnostic: 'pwsh is not available; sync behavior scenarios skipped' });
        return results;
    }
    const fixture = tempDir('sync');
    try {
        fs.mkdirSync(path.join(fixture, 'scripts', 'shared'), { recursive: true });
        fs.writeFileSync(path.join(fixture, 'scripts', 'shared', 'sse-manager.js'),
            'export const sse = 1;\n', 'utf8');
        fs.copyFileSync(scriptFile, path.join(fixture, 'scripts', 'sync-shared-snippets.ps1'));
        const scriptArgs = ['-NoProfile', '-File', toPosix(path.join(fixture, 'scripts', 'sync-shared-snippets.ps1'))];
        const driftedUserJs = '// >>> SHARED:sse-manager.js\n// stale block\n// <<< SHARED:sse-manager.js\n';
        const runOne = (args) => run(['pwsh', ...args], { cwd: fixture });

        fs.writeFileSync(path.join(fixture, 'test.user.js'), driftedUserJs, 'utf8');
        const driftRun = runOne([...scriptArgs, '-Check']);
        results.push({
            name: 'sync-shared-snippets.ps1 -Check fails when the .user.js snippet drifts',
            kind: 'external-checker', expected: 'exit != 0', status: driftRun.status,
            ok: driftRun.status !== 0,
            diagnostic: driftRun.status === 0
                ? 'drift fixture passed -Check; the checker is a no-op (output: '
                    + (driftRun.output || '').split('\n').slice(-6).join(' | ') + ')'
                : (driftRun.status === null
                    ? 'pwsh failed to run the checker: ' + (driftRun.output || '').slice(-300)
                    : ''),
        });

        const syncRun = runOne(scriptArgs);
        const recheckRun = runOne([...scriptArgs, '-Check']);
        results.push({
            name: 'sync-shared-snippets.ps1 legal sync + recheck exit 0',
            kind: 'external-checker', expected: 'exit 0', status: recheckRun.status,
            ok: syncRun.status === 0 && recheckRun.status === 0,
            diagnostic: syncRun.status !== 0 || recheckRun.status !== 0
                ? 'legal sync (exit ' + syncRun.status + ') or recheck (exit ' + recheckRun.status + ') failed: '
                    + (syncRun.output || '').split('\n').slice(-4).join(' | ')
                    + ' | ' + (recheckRun.output || '').split('\n').slice(-4).join(' | ') : '',
        });
    } finally {
        rmrf(fixture);
    }
    return results;
}

// ---------------------------------------------------------------------------
// 候选 hook 内容守卫（无条件执行，不依赖归纳跳过 / bash / 场景执行）：
// pre-commit / pre-push 必须保留 trusted-anchor 语义（trustedGateRef +
// trustedGateEpoch 路由）且不得退化为 no-op——恶意 hook（exit 0 / 自批准）一律拒绝。
// ---------------------------------------------------------------------------

function runHookContentChecks(candidateRoot) {
    const checks = [];
    const hooksRoot = path.join(candidateRoot, 'scripts', 'hooks');
    if (!fs.existsSync(hooksRoot)) {
        checks.push({ name: 'candidate hooks bundle', kind: 'hooks-static', expected: 'present',
            status: null, ok: false,
            diagnostic: 'candidate has no scripts/hooks (part of the current verifier baseline;'
                + ' deletion must fail closed)' });
        return checks;
    }
    for (const hook of ['pre-commit', 'pre-push']) {
        const file = path.join(hooksRoot, hook);
        if (!fs.existsSync(file)) {
            checks.push({ name: 'candidate hook constraint: missing ' + hook, kind: 'hooks-static',
                expected: 'present', status: null, ok: false,
                diagnostic: 'candidate lacks ' + hook + ' (part of the current verifier baseline;'
                    + ' deletion must fail closed)' });
            continue;
        }
        const content = fs.readFileSync(file, 'utf8');
        const preparedRootBound = hook !== 'pre-commit'
            || (/preparedRootEpoch/.test(content)
                && /preparedRootParent/.test(content)
                && /preparedRootTree/.test(content)
                && /git write-tree/.test(content)
                && /prepared_parent.*current_parent/.test(content)
                && /prepared_tree.*current_tree/.test(content));
        const localGitEnvIsolated = /git rev-parse --local-env-vars/.test(content)
            && /unset "\$name"/.test(content)
            && /run_without_local_git_env node/.test(content);
        const ok = !isNoopStep(content)
            && /trustedGateRef/.test(content)
            && /trustedGateEpoch/.test(content)
            && /minimumTrustedVerifier/.test(content)
            && localGitEnvIsolated
            && preparedRootBound;
        checks.push({
            name: 'candidate ' + hook + ' keeps trusted-anchor semantics', kind: 'hooks-static',
            expected: 'trustedGateRef + trustedGateEpoch + minimumTrustedVerifier routing'
                + ' + isolated trusted-contract Git environment'
                + (hook === 'pre-commit' ? ' + exact prepared-root parent/tree binding' : ''), status: null, ok,
            diagnostic: ok ? '' : 'candidate ' + hook + ' was weakened (missing trustedGateRef /'
                + ' trustedGateEpoch routing / trusted-contract Git environment isolation / prepared-root'
                + ' binding, or reduced to a no-op stub); fail closed',
        });
    }
    return checks;
}

/** 候选 package.json scripts 契约：required scripts 必须指向真实测试入口。 */
function runPackageContractChecks(candidateRoot, trustedPolicy) {
    const checks = [];
    const pkgFile = path.join(candidateRoot, ...PACKAGE_JSON_REL.split('/'));
    if (!fs.existsSync(pkgFile)) {
        pushPackageCheck(checks, 'candidate package.json scripts contract', false,
            'candidate has no package.json (part of the current verifier baseline; deletion must'
                + ' fail closed)');
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
    const required = new Set(REQUIRED_SCRIPTS);
    if (trustedPolicy && Array.isArray(trustedPolicy.requiredPackageScripts)) {
        for (const s of trustedPolicy.requiredPackageScripts) {
            required.add(s);
        }
    }
    for (const script of required) {
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

async function runSelfProtection(repoRoot, candidateRoot, trustedPolicy, hasContract, skip, skipReason,
    forceSelfProtection) {
    const results = [];
    if (skip && !forceSelfProtection) {
        results.push({ name: 'self-protection (candidate contract vs next malicious gate)', kind: 'self-protection',
            expected: null, status: null, ok: true,
            diagnostic: skipReason || 'candidate contract bundle is byte-identical to the trusted bundle;'
                + ' protection is guaranteed by the trust chain (inductive skip)' });
        return results;
    }
    if (!hasContract) {
        results.push({ name: 'self-protection', kind: 'report', expected: null, status: null, ok: false,
            diagnostic: 'candidate has no scripts/i18n/gate-contract.mjs (part of the current'
                + ' verifier baseline; deletion must fail closed)' });
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
        // workflow 删除关键 jobs + 关键命令改为 true + 候选 guard + continue-on-error，
        // package script 改为 true / echo ok，policy 各集合减少 + gateEpoch / contractVersion 改变
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
                delete doc.jobs['trusted-gate-contract'];
                const sigSteps = doc.jobs['signature-guard'] && Array.isArray(doc.jobs['signature-guard'].steps)
                    ? doc.jobs['signature-guard'].steps : [];
                for (const step of sigSteps) {
                    if (typeof step.run === 'string' && /pre-push-guard\.sh/.test(step.run)) {
                        step.run = 'bash scripts/hooks/pre-push-guard.sh --repo-root "$PWD" --ref "${{ github.sha }}"';
                    }
                }
                if (doc.jobs['i18n-check'] && Array.isArray(doc.jobs['i18n-check'].steps)) {
                    const tests = doc.jobs['i18n-check'].steps.find((s) => typeof s.run === 'string'
                        && s.run.includes('npm run test:i18n'));
                    if (tests) {
                        tests.run = 'true';
                        if (tests.env && typeof tests.env === 'object') {
                            tests.env.CI = 'false';
                        }
                    }
                }
                doc.jobs['java-tests'] = { ...(doc.jobs['java-tests'] || {}), 'continue-on-error': true };
                doc.jobs['javascript-tests'] = { ...(doc.jobs['javascript-tests'] || {}), 'continue-on-error': true };
            }
            fs.writeFileSync(workflowFile, YAML.stringify(doc), 'utf8');
        }
        // 下一代恶意 gate 同时弱化外围 workflow：shared-snippet 改 true /
        // release 删 quality gate 与 publish 依赖 / publish 删 quality gate /
        // nightly 删 publish 依赖 → 新 contract 必须拒绝
        const extMutations = [
            ['.github/workflows/shared-snippets-check.yml', (d) => {
                if (d && d.jobs && d.jobs['check-shared-snippets'] && Array.isArray(d.jobs['check-shared-snippets'].steps)) {
                    const step = d.jobs['check-shared-snippets'].steps.find((s) => typeof s.run === 'string'
                        && s.run.includes('sync-shared-snippets.ps1'));
                    if (step) {
                        step.run = 'true';
                    }
                }
            }],
            ['.github/workflows/release.yml', (d) => {
                if (d && d.jobs && typeof d.jobs === 'object') {
                    delete d.jobs['draft-quality-gate'];
                    if (d.jobs['build-jar']) {
                        const needs = Array.isArray(d.jobs['build-jar'].needs)
                            ? d.jobs['build-jar'].needs
                            : [d.jobs['build-jar'].needs].filter(Boolean);
                        d.jobs['build-jar'].needs = needs.filter((n) => n !== 'publish-plugins');
                    }
                }
            }],
            ['.github/workflows/publish-plugins.yml', (d) => {
                if (d && d.jobs && typeof d.jobs === 'object') {
                    delete d.jobs['quality-gate'];
                    if (d.jobs['publish']) {
                        d.jobs['publish'].if = '${{ always() }}';
                    }
                }
            }],
            ['.github/workflows/nightly.yml', (d) => {
                if (d && d.jobs && d.jobs['build-jar'] && Array.isArray(d.jobs['build-jar'].needs)) {
                    d.jobs['build-jar'].needs = d.jobs['build-jar'].needs.filter((n) => n !== 'publish-plugins');
                }
            }],
        ];
        for (const [rel, mutate] of extMutations) {
            const extFile = path.join(repo, ...rel.split('/'));
            if (!fs.existsSync(extFile)) {
                continue;
            }
            try {
                const YAML = loadYamlModule(repo);
                const doc = YAML.parse(fs.readFileSync(extFile, 'utf8'));
                mutate(doc);
                fs.writeFileSync(extFile, YAML.stringify(doc), 'utf8');
            } catch (e) {
                throw new Error('self-protection: cannot mutate ' + rel + ': ' + e.message);
            }
        }
        const pkgFile = path.join(repo, ...PACKAGE_JSON_REL.split('/'));
        if (fs.existsSync(pkgFile)) {
            const pkg = JSON.parse(fs.readFileSync(pkgFile, 'utf8'));
            if (pkg && pkg.scripts && typeof pkg.scripts === 'object') {
                pkg.scripts['test:i18n'] = 'true';
                pkg.scripts['i18n:check'] = 'echo ok';
            }
            fs.writeFileSync(pkgFile, JSON.stringify(pkg, null, 2) + '\n', 'utf8');
        }
        const policyPath = path.join(repo, 'scripts', 'i18n', 'gate-policy.json');
        if (fs.existsSync(policyPath)) {
            const pol = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
            if (pol && Array.isArray(pol.requiredPaths)) {
                pol.requiredPaths = pol.requiredPaths.filter((p) => p !== 'scripts/i18n/check.mjs');
            }
            if (Array.isArray(pol.protectedBranches)) {
                pol.protectedBranches = pol.protectedBranches.filter((r) => r !== 'refs/heads/master');
            }
            if (Array.isArray(pol.requiredWorkflowJobs)) {
                pol.requiredWorkflowJobs = pol.requiredWorkflowJobs.filter((j) => j !== 'i18n-check');
            }
            if (Array.isArray(pol.requiredPackageScripts)) {
                pol.requiredPackageScripts = pol.requiredPackageScripts.filter((s) => s !== 'test:i18n');
            }
            if (Array.isArray(pol.requiredExternalChecks)) {
                pol.requiredExternalChecks = [];
            }
            pol.gateEpoch = 3;
            pol.contractVersion = 0;
            fs.writeFileSync(policyPath, JSON.stringify(pol, null, 2) + '\n', 'utf8');
        }
        // 下一代恶意 gate 同时弱化 Ruleset 不变量声明（github-ruleset-invariants.json）：
        // requireStrict=false / allowBypass=true / allowDeletion=true / allowNonFastForward=true /
        // requiredChecks 清空 / root tag 放开 → 新 contract 必须拒绝
        const rulesetPath = path.join(repo, 'scripts', 'ci', 'github-ruleset-invariants.json');
        if (fs.existsSync(rulesetPath)) {
            fs.writeFileSync(rulesetPath, JSON.stringify({
                schemaVersion: 1,
                master: {
                    requiredChecks: [],
                    requireStrict: false,
                    allowBypass: true,
                    allowDeletion: true,
                    allowNonFastForward: true,
                },
                'i18n-gate-epoch-2-root': {
                    allowDeletion: true,
                    allowNonFastForward: true,
                    allowBypass: true,
                },
            }, null, 2) + '\n', 'utf8');
        }
        git(['add', '-A'], repo);
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'malicious next gate'], repo);
        const malicious = git(['rev-parse', 'HEAD'], repo);
        const candidateContract = path.join(candidateRoot, 'scripts', 'i18n', 'gate-contract.mjs');
        const result = run(['node', candidateContract, '--repo-root', repo, '--candidate-ref', malicious],
            { cwd: repo });
        const ok = result.status !== 0;
        results.push({
            name: 'candidate contract must reject the next malicious gate (no-op checker/hooks/guard, weakened workflow/package/policy, epoch change)',
            kind: 'self-protection', expected: 'exit != 0', status: result.status, ok,
            diagnostic: ok ? '' : 'candidate contract accepted a no-op checker gate (exit 0);'
                + ' the candidate contract cannot protect the next upgrade',
        });

        git(['reset', '-q', '--hard', 'HEAD~1'], repo, { stdio: 'ignore' });
        const minimumPolicy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        minimumPolicy.minimumTrustedVerifier.contractVersion -= 1;
        fs.writeFileSync(policyPath, JSON.stringify(minimumPolicy, null, 2) + '\n', 'utf8');
        git(['add', '-A'], repo);
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'lower minimum verifier baseline'], repo);
        const minimumCandidate = git(['rev-parse', 'HEAD'], repo);
        const minimumResult = run(['node', candidateContract, '--repo-root', repo,
            '--candidate-ref', minimumCandidate], { cwd: repo });
        results.push({
            name: 'candidate contract must reject a minimumTrustedVerifier downgrade by itself',
            kind: 'self-protection', expected: 'exit != 0', status: minimumResult.status,
            ok: minimumResult.status !== 0,
            diagnostic: minimumResult.status !== 0 ? ''
                : 'candidate contract accepted minimumTrustedVerifier.contractVersion downgrade',
        });

        git(['reset', '-q', '--hard', 'HEAD~1'], repo, { stdio: 'ignore' });
        const coverageDoc = loadYamlModule(repo).parse(fs.readFileSync(workflowFile, 'utf8'));
        coverageDoc.on.push = { branches: ['master'] };
        fs.writeFileSync(workflowFile, loadYamlModule(repo).stringify(coverageDoc), 'utf8');
        git(['add', '-A'], repo);
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'narrow quality gate push coverage'], repo);
        const coverageCandidate = git(['rev-parse', 'HEAD'], repo);
        const coverageResult = run(['node', candidateContract, '--repo-root', repo,
            '--candidate-ref', coverageCandidate], { cwd: repo });
        results.push({
            name: 'candidate contract must reject branches-ignore to branches master downgrade by itself',
            kind: 'self-protection', expected: 'exit != 0', status: coverageResult.status,
            ok: coverageResult.status !== 0,
            diagnostic: coverageResult.status !== 0 ? ''
                : 'candidate contract accepted narrowed quality-gate push coverage',
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
    // 本 contract 自身就是 trusted verifier：它必须满足当前 verifier baseline。
    // 低于最低能力（旧 verifier）→ fail closed，绝不 predates / fallback / legacy 兼容。
    try {
        trustedGate.assertSupportedTrustedVerifierDir(path.join(OWN_DIR, '..', '..'));
    } catch (e) {
        fail('trusted verifier does not satisfy the current Gate Epoch 2 verifier baseline;'
            + ' fail closed: ' + e.message);
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
            if (candidatePolicy.schemaVersion < trustedPolicy.schemaVersion) {
                checks.push({
                    name: 'candidate schemaVersion not lowered', kind: 'policy',
                    expected: '>= ' + trustedPolicy.schemaVersion, status: null, ok: false,
                    diagnostic: 'candidate schemaVersion ' + candidatePolicy.schemaVersion
                        + ' < trusted ' + trustedPolicy.schemaVersion,
                });
            } else {
                checks.push({
                    name: 'candidate schemaVersion not lowered', kind: 'policy',
                    expected: '>= ' + trustedPolicy.schemaVersion, status: null, ok: true, diagnostic: '',
                });
            }
            const trustedMinimum = trustedPolicy.minimumTrustedVerifier;
            const candidateMinimum = candidatePolicy.minimumTrustedVerifier;
            for (const key of ['contractVersion', 'schemaVersion']) {
                const ok = candidateMinimum[key] >= trustedMinimum[key];
                checks.push({
                    name: 'minimum trusted verifier ' + key + ' not lowered', kind: 'policy',
                    expected: '>= ' + trustedMinimum[key], status: null, ok,
                    diagnostic: ok ? '' : 'candidate minimumTrustedVerifier.' + key + ' '
                        + candidateMinimum[key] + ' < trusted ' + trustedMinimum[key] + '; fail closed',
                });
            }
            const removedVerifierFiles = trustedGate.policySetReduced(
                trustedMinimum.requiredFiles, candidateMinimum.requiredFiles);
            checks.push({
                name: 'minimum trusted verifier required files not reduced', kind: 'policy',
                expected: 'candidate keeps all trusted verifier files', status: null,
                ok: removedVerifierFiles.length === 0,
                diagnostic: removedVerifierFiles.length > 0
                    ? 'candidate dropped minimumTrustedVerifier.requiredFiles: '
                        + removedVerifierFiles.join(', ') : '',
            });
            // 7.1 Epoch 不得变化：正常 advance 只能 same epoch；2 → 1 / 2 → 3 属于另一轮人工 root reset
            if (candidatePolicy.gateEpoch !== trustedPolicy.gateEpoch) {
                checks.push({
                    name: 'candidate gateEpoch unchanged', kind: 'policy',
                    expected: '== ' + trustedPolicy.gateEpoch, status: null, ok: false,
                    diagnostic: 'candidate gateEpoch ' + candidatePolicy.gateEpoch
                        + ' != trusted ' + trustedPolicy.gateEpoch
                        + '; epoch changes are a separate manual root admission, not a normal advance',
                });
            } else {
                checks.push({
                    name: 'candidate gateEpoch unchanged', kind: 'policy',
                    expected: '== ' + trustedPolicy.gateEpoch, status: null, ok: true, diagnostic: '',
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
            // requiredWorkflowFiles（受保护 workflow 本体）不得减少
            const removedWorkflowFiles = trustedGate.policySetReduced(
                trustedPolicy.requiredWorkflowFiles, candidatePolicy.requiredWorkflowFiles);
            checks.push({
                name: 'required workflow files not reduced', kind: 'policy',
                expected: 'candidate keeps all trusted required workflow files', status: null,
                ok: removedWorkflowFiles.length === 0,
                diagnostic: removedWorkflowFiles.length > 0
                    ? 'candidate dropped required workflow files: ' + removedWorkflowFiles.join(', ') : '',
            });
            // 7.7 / 7.8：requiredPackageScripts / requiredExternalChecks 集合不得减少
            const removedScripts = trustedGate.policySetReduced(
                trustedPolicy.requiredPackageScripts, candidatePolicy.requiredPackageScripts);
            checks.push({
                name: 'required package scripts not reduced', kind: 'policy',
                expected: 'candidate keeps all trusted required package scripts', status: null,
                ok: removedScripts.length === 0,
                diagnostic: removedScripts.length > 0
                    ? 'candidate dropped required package scripts: ' + removedScripts.join(', ') : '',
            });
            const removedExternal = trustedGate.policySetReduced(
                trustedPolicy.requiredExternalChecks, candidatePolicy.requiredExternalChecks);
            checks.push({
                name: 'required external checks not reduced', kind: 'policy',
                expected: 'candidate keeps all trusted required external checks', status: null,
                ok: removedExternal.length === 0,
                diagnostic: removedExternal.length > 0
                    ? 'candidate dropped required external checks: ' + removedExternal.join(', ') : '',
            });
            // 结构化 external check 定义（workflow / name / job / required context）不得减少
            const removedDefinitions = trustedGate.policySetReduced(
                trustedPolicy.requiredExternalCheckDefinitions, candidatePolicy.requiredExternalCheckDefinitions);
            checks.push({
                name: 'required external check definitions not reduced', kind: 'policy',
                expected: 'candidate keeps all trusted required external check definitions', status: null,
                ok: removedDefinitions.length === 0,
                diagnostic: removedDefinitions.length > 0
                    ? 'candidate dropped required external check definitions: '
                        + removedDefinitions.join(', ') : '',
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
        checks.push(...runExternalWorkflowContracts(repoRoot, candidateRoot));
        checks.push(...runPackageContractChecks(candidateRoot, trustedPolicy));
        checks.push(...runGateSurfaceContract(candidateRoot, trustedPolicy));
        checks.push(...runRulesetInvariantsContract(candidateRoot));
        checks.push(...runHookContentChecks(candidateRoot));

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

        // 4.5 外部 checker：shared-snippet 真实 -Check 行为（candidate ps1 是执行对象；
        // 与 trusted 逐字节一致时归纳跳过）
        const candidateSyncIdentical = (() => {
            const candidateFile = path.join(candidateRoot, ...SYNC_REL.split('/'));
            const trustedFile = path.join(OWN_DIR, '..', '..', ...SYNC_REL.split('/'));
            if (!fs.existsSync(candidateFile) || !fs.existsSync(trustedFile)) {
                return false;
            }
            return fs.readFileSync(candidateFile, 'utf8') === fs.readFileSync(trustedFile, 'utf8');
        })();
        const syncScenarios = await runSyncScenarios(repoRoot, candidateRoot, candidateSyncIdentical);
        checks.push(...syncScenarios);
        const syncOk = syncScenarios.every((c) => c.ok);

        // 5. candidate hooks 行为（黑盒失败后仍收集静态文本约束，执行场景跳过）
        const hookScenarios = await runHookScenarios(repoRoot, candidateRoot, trustedPolicy, hasHooks,
            candidateHooksIdentical || !checkerOk || !syncOk,
            !checkerOk || !syncOk
                ? 'candidate behavior already failed; hook execution scenarios skipped'
                : undefined);
        checks.push(...hookScenarios);
        const hooksOk = hookScenarios.every((c) => c.ok);

        // 6. 自保护：candidate contract 必须拒绝恶意下一代 gate。
        // root admission（--force-self-protection）时关闭归纳跳过，强制运行自保护套件。
        const selfProtection = await runSelfProtection(repoRoot, candidateRoot, trustedPolicy, hasContract,
            candidateContractIdentical || !checkerOk || !hooksOk || !syncOk,
            !checkerOk || !hooksOk || !syncOk
                ? 'candidate behavior already failed; self-protection skipped'
                : undefined,
            args.forceSelfProtection);
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
