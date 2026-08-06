'use strict';
/**
 * Quality Gate workflow 本地契约测试：
 * - 17.1 trusted materialization：真实临时 Git 仓库中验证 checkout-index --stdin < paths-file
 *   （缺少重定向的错误实现必须失败，不能静默产出空目录）；
 * - 17.2 YAML contract：合法 workflow 通过；删除 job / 候选 guard / test:i18n=true /
 *   删除 result 传播 / github.sha^ 回退 → 拒绝；
 * - 17.3 package scripts：test:i18n = true、i18n:check = echo ok → 拒绝；
 * - 17.4 action 版本：项目认可的 maintained major（checkout/setup-node/upload-artifact v7、
 *   setup-java v5）；旧 major 与 FORCE_JAVASCRIPT_ACTIONS_TO_NODE24 → 拒绝。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runAcceptCore } from '../accept.mjs';
import { runGenerate } from '../generate-static.mjs';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');

// 契约使用 yaml 解析候选 workflow：fixture 仓库没有 node_modules，
// 通过 NODE_PATH 指向真实仓库的 node_modules 完成解析（与 CI 的 npm ci 等价）。
process.env.NODE_PATH = process.env.NODE_PATH || path.join(REPO_ROOT, 'node_modules');

const YAML = createRequire(path.join(REPO_ROOT, 'package.json'))('yaml');

const CATALOG = `{
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

const APP_I18N = path.join('pixivdownload-app', 'src', 'main', 'resources', 'i18n');
const GOOD_ZH = 'greeting=你好 {name}\ntitle=作品标题\n';
const GOOD_EN = 'greeting=Hello {name}\ntitle=Artwork title\n';
const WORKFLOW_REL = path.join('.github', 'workflows', 'quality-gate.yml');

function git(args, cwd, opts = {}) {
    const result = spawnSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...opts });
    if (result.status !== 0 && !opts.allowFailure) {
        throw new Error('git ' + args.join(' ') + ' failed: ' + (result.stderr || result.stdout));
    }
    return result;
}

function hasBash() {
    try {
        execFileSync('bash', ['--version'], { stdio: 'ignore' });
        return true;
    } catch (e) {
        return false;
    }
}

/** 完整候选仓库：C1（enforcement start，完整 gate bundle 无 policy）+ C2（真实 v2 policy）。 */
function makeFullCandidateRepo() {
    const dir = path.join(os.tmpdir(), 'pixiv qg repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);
    fs.writeFileSync(path.join(dir, '.gitignore'), 'build/\nnode_modules/\n', 'utf8');
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'), { force: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'hooks'), path.join(dir, 'scripts', 'hooks'), { recursive: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'ci'), path.join(dir, 'scripts', 'ci'), { recursive: true });
    fs.mkdirSync(path.join(dir, '.github', 'workflows'), { recursive: true });
    fs.copyFileSync(path.join(REPO_ROOT, WORKFLOW_REL), path.join(dir, WORKFLOW_REL));
    fs.copyFileSync(path.join(REPO_ROOT, 'package.json'), path.join(dir, 'package.json'));
    const i18nDir = path.join(dir, APP_I18N);
    fs.mkdirSync(path.join(i18nDir, 'web'), { recursive: true });
    fs.writeFileSync(path.join(i18nDir, 'locales.json'), CATALOG, 'utf8');
    fs.writeFileSync(path.join(i18nDir, 'web', 'common.properties'), GOOD_ZH, 'utf8');
    fs.writeFileSync(path.join(i18nDir, 'web', 'common_en.properties'), GOOD_EN, 'utf8');
    const bootstrap = runAcceptCore(dir, { bootstrap: true });
    if (!bootstrap.ok) {
        throw new Error('fixture bootstrap failed: ' + bootstrap.refused.join('\n'));
    }
    runGenerate(dir);
    git(['add', '--chmod=+x', 'scripts/hooks/pre-commit', 'scripts/hooks/pre-push', 'scripts/hooks/pre-push-guard.sh', 'scripts/hooks/execfile-shim.cjs'], dir);
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'init'], dir);
    const start = git(['rev-parse', 'HEAD'], dir).stdout.trim();
    const policy = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
    policy.i18nEnforcementStartCommit = start;
    fs.writeFileSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'),
        JSON.stringify(policy, null, 2) + '\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'add gate policy'], dir);
    git(['config', '--local', 'core.hooksPath', 'scripts/hooks'], dir);
    const anchor = git(['rev-parse', 'HEAD'], dir).stdout.trim();
    git(['config', '--local', 'pixiv.i18n.trustedGateRef', anchor], dir);
    return dir;
}

/** trusted copy：真实 scripts/i18n + hooks + scripts/ci + workflow + package.json，policy 指向夹具。 */
function makeTrustedCopy(fixtureRoot) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv qg trusted-'));
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'hooks'), path.join(dir, 'scripts', 'hooks'), { recursive: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'ci'), path.join(dir, 'scripts', 'ci'), { recursive: true });
    fs.mkdirSync(path.join(dir, '.github', 'workflows'), { recursive: true });
    fs.copyFileSync(path.join(REPO_ROOT, WORKFLOW_REL), path.join(dir, WORKFLOW_REL));
    fs.copyFileSync(path.join(REPO_ROOT, 'package.json'), path.join(dir, 'package.json'));
    const policy = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
    const start = git(['rev-parse', 'HEAD~1'], fixtureRoot).stdout.trim();
    policy.i18nEnforcementStartCommit = start;
    fs.writeFileSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'),
        JSON.stringify(policy, null, 2) + '\n', 'utf8');
    return dir;
}

function commitBypass(root, message) {
    git(['add', '-A'], root);
    git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', message], root);
}

function runContract(trustedCopy, repoRoot, args) {
    return spawnSync('node', [path.join(trustedCopy, 'scripts', 'i18n', 'gate-contract.mjs'), ...args],
        { cwd: repoRoot, encoding: 'utf8', maxBuffer: 128 * 1024 * 1024 });
}

function cleanRepo(root) {
    if (!root) {
        return;
    }
    for (let attempt = 0; attempt < 6; attempt += 1) {
        try {
            fs.rmSync(root, { recursive: true, force: true });
            return;
        } catch (e) {
            if (attempt === 5) {
                throw e;
            }
            execFileSync('bash', ['-c', 'sleep 0.5'], { stdio: 'ignore' });
        }
    }
}

function readWorkflow(root) {
    return YAML.parse(fs.readFileSync(path.join(root, WORKFLOW_REL), 'utf8'));
}

function writeWorkflow(root, doc) {
    fs.writeFileSync(path.join(root, WORKFLOW_REL), YAML.stringify(doc), 'utf8');
}

// ---------------------------------------------------------------------------
// 17.1 trusted materialization（真实临时 Git 仓库）
// ---------------------------------------------------------------------------

test('workflow：trusted materialization —— checkout-index --stdin < paths-file 必须物化 check.mjs 与 guard', () => {
    const root = makeFullCandidateRepo();
    try {
        const base = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv qg mat-'));
        try {
            const paths = path.join(tmp, 'paths.txt');
            const index = path.join(tmp, 'index');
            const out = path.join(tmp, 'out');
            const gatePaths = ['scripts/i18n', 'scripts/hooks', 'scripts/ci',
                '.github/workflows/quality-gate.yml', 'package.json'];

            // 正确的物化序列（与 workflow bootstrap 一致）
            const list = spawnSync('git', ['ls-tree', '-r', '--name-only', base, '--', ...gatePaths],
                { cwd: root, encoding: 'utf8' });
            assert.equal(list.status, 0, list.stderr);
            assert.ok(list.stdout.trim().length > 0, 'paths.txt 必须有内容');
            fs.writeFileSync(paths, list.stdout, 'utf8');
            assert.ok(fs.statSync(paths).size > 0, 'paths.txt 非空（test -s 通过）');

            const readTree = spawnSync('git', ['read-tree', base], { cwd: root, encoding: 'utf8',
                env: { ...process.env, GIT_INDEX_FILE: index } });
            assert.equal(readTree.status, 0, readTree.stderr);
            fs.mkdirSync(out, { recursive: true });

            const checkout = spawnSync('git', ['-c', 'core.autocrlf=false', 'checkout-index', '--stdin', '--prefix=' + out + '/'],
                { cwd: root, encoding: 'utf8', env: { ...process.env, GIT_INDEX_FILE: index },
                    input: fs.readFileSync(paths, 'utf8') });
            assert.equal(checkout.status, 0, checkout.stderr);
            assert.ok(fs.existsSync(path.join(out, 'scripts', 'i18n', 'check.mjs')), '输出目录必须包含 check.mjs');
            assert.ok(fs.existsSync(path.join(out, 'scripts', 'hooks', 'pre-push-guard.sh')), '输出目录必须包含 guard');
            assert.ok(fs.existsSync(path.join(out, 'scripts', 'i18n', 'gate-contract.mjs')));
            assert.ok(fs.existsSync(path.join(out, 'scripts', 'i18n', 'gate-policy.json')));
            assert.ok(fs.existsSync(path.join(out, 'scripts', 'i18n', 'lib', 'trusted-gate.mjs')));

            // 缺少 stdin 重定向的错误实现：stdin 为空 → git 退出 0 但物化空目录
            // （这就是 Quality Gate run 31118950886 的根因：空 gate 目录一路执行到 bash 才报文件不存在）
            const outBroken = path.join(tmp, 'out-broken');
            fs.mkdirSync(outBroken, { recursive: true });
            const broken = spawnSync('git', ['-c', 'core.autocrlf=false', 'checkout-index', '--stdin', '--prefix=' + outBroken + '/'],
                { cwd: root, encoding: 'utf8', env: { ...process.env, GIT_INDEX_FILE: index }, input: '' });
            assert.equal(broken.status, 0, '错误实现退出码（git 对空 stdin 静默成功）');
            assert.equal(fs.existsSync(path.join(outBroken, 'scripts', 'i18n', 'check.mjs')), false,
                '缺少重定向时输出目录必须为空 → 后续 test -f 必须失败');

            // test -s 守卫：不存在的路径过滤器 → ls-tree 空 → 必须提前失败
            const emptyPaths = path.join(tmp, 'empty.txt');
            const emptyList = spawnSync('git', ['ls-tree', '-r', '--name-only', base, '--', 'scripts/does-not-exist'],
                { cwd: root, encoding: 'utf8' });
            fs.writeFileSync(emptyPaths, emptyList.stdout, 'utf8');
            assert.equal(fs.statSync(emptyPaths).size, 0, '空 paths.txt 必须被 test -s 拦截');
        } finally {
            fs.rmSync(tmp, { recursive: true, force: true });
        }
    } finally {
        cleanRepo(root);
    }
});

// ---------------------------------------------------------------------------
// 17.2 / 17.3 / 17.4 workflow + package 契约
// ---------------------------------------------------------------------------

test('workflow 契约：合法 workflow + package scripts + action 版本 → 通过', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const anchor = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', anchor]);
        assert.equal(run.status, 0, run.stdout + run.stderr);
        assert.match(run.stdout, /GATE CONTRACT OK/);
        // 报告包含 workflow / package 契约检查项
        const contract = JSON.parse(fs.readFileSync(path.join(root, 'build', 'reports', 'i18n', 'contract.json'), 'utf8'));
        const kinds = new Set(contract.checks.map((c) => c.kind));
        assert.ok(kinds.has('workflow'), '必须包含 workflow 契约检查');
        assert.ok(kinds.has('package'), '必须包含 package scripts 契约检查');
        assert.equal(contract.checks.filter((c) => !c.ok).length, 0);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：删除 java-tests job → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        delete doc.jobs['java-tests'];
        writeWorkflow(root, doc);
        commitBypass(root, 'delete java-tests');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, '删除 java-tests 必须被拒绝');
        assert.match(run.stdout + run.stderr, /job java-tests|java-tests/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：删除 trusted-gate-contract job → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        delete doc.jobs['trusted-gate-contract'];
        writeWorkflow(root, doc);
        commitBypass(root, 'delete trusted-gate-contract');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, '删除 trusted-gate-contract 必须被拒绝');
        assert.match(run.stdout + run.stderr, /trusted-gate-contract/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：signature guard 改用候选 guard（无 GATE_DIR）→ 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        doc.jobs['signature-guard'].steps.push({
            name: 'Reject reverse-engineered Douyin signature code (candidate guard)',
            run: 'bash scripts/hooks/pre-push-guard.sh --repo-root "$PWD" --ref "${{ github.sha }}"',
        });
        writeWorkflow(root, doc);
        commitBypass(root, 'use candidate guard');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, '候选 guard 自批准必须被拒绝');
        assert.match(run.stdout + run.stderr, /candidate guard|trusted base|materialized/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：i18n-check 删除 result 传播 → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        doc.jobs['i18n-check'].steps = doc.jobs['i18n-check'].steps
            .filter((s) => !(typeof s.run === 'string' && /Propagate result|outcome/.test((s.name || '') + s.run)));
        writeWorkflow(root, doc);
        commitBypass(root, 'remove result propagation');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, '删除 result 传播必须被拒绝');
        assert.match(run.stdout + run.stderr, /propagat/i);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：trusted base 解析回退到 github.sha^（新分支不可信父提交）→ 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        const baseStep = doc.jobs['signature-guard'].steps.find((s) => /base_sha|event\.before/.test(s.run || ''));
        assert.ok(baseStep, '测试前提：必须存在 trusted base 解析 step');
        baseStep.run = baseStep.run + '\n          base="$(git rev-parse --verify --quiet "${{ github.sha }}^" || true)"\n';
        writeWorkflow(root, doc);
        commitBypass(root, 'fall back to github.sha^');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'github.sha^ 回退必须被拒绝');
        assert.match(run.stdout + run.stderr, /github\.sha\^|untrusted parent/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('package 契约：test:i18n = true → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const pkgPath = path.join(root, 'package.json');
        const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
        pkg.scripts['test:i18n'] = 'true';
        fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n', 'utf8');
        commitBypass(root, 'test:i18n = true');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'test:i18n = true 必须被拒绝');
        assert.match(run.stdout + run.stderr, /test:i18n|real test entry/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('package 契约：i18n:check = echo ok → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const pkgPath = path.join(root, 'package.json');
        const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
        pkg.scripts['i18n:check'] = 'echo ok';
        fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n', 'utf8');
        commitBypass(root, 'i18n:check = echo ok');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'i18n:check = echo ok 必须被拒绝');
        assert.match(run.stdout + run.stderr, /i18n:check|real test entry/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：action 版本降级（checkout@v4）→ 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        const step = doc.jobs['java-tests'].steps.find((s) => /actions\/checkout@/.test(s.uses || ''));
        assert.ok(step, '测试前提：必须存在 checkout step');
        step.uses = step.uses.replace(/@v7$/, '@v4');
        writeWorkflow(root, doc);
        commitBypass(root, 'downgrade checkout to v4');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'action 主版本降级必须被拒绝');
        assert.match(run.stdout + run.stderr, /approved maintained majors|deprecated action versions/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：重新引入 FORCE_JAVASCRIPT_ACTIONS_TO_NODE24 → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        doc.env = { ...(doc.env || {}), FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: 'true' };
        writeWorkflow(root, doc);
        commitBypass(root, 'reintroduce node24 compat env');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'FORCE_JAVASCRIPT_ACTIONS_TO_NODE24 必须被拒绝');
        assert.match(run.stdout + run.stderr, /FORCE_JAVASCRIPT_ACTIONS_TO_NODE24/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：job 增加 continue-on-error → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        doc.jobs['java-tests']['continue-on-error'] = true;
        writeWorkflow(root, doc);
        commitBypass(root, 'job continue-on-error');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, '必需 job 的 continue-on-error 必须被拒绝');
        assert.match(run.stdout + run.stderr, /continue-on-error/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});
