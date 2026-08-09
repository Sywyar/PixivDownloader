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
import { copyGateSurfaceFiles } from './lib/surface-fixture.mjs';

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
    copyGateSurfaceFiles(REPO_ROOT, dir);
    fs.copyFileSync(path.join(REPO_ROOT, 'package.json'), path.join(dir, 'package.json'));
    fs.copyFileSync(path.join(REPO_ROOT, 'package-lock.json'), path.join(dir, 'package-lock.json'));
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
    git(['add', '--chmod=+x', 'scripts/hooks/pre-commit', 'scripts/hooks/pre-push', 'scripts/hooks/pre-push-guard.sh'], dir);
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
    // Epoch 2 单一标准：hooks 要求 epoch == 2 才运行 trusted gate
    git(['config', '--local', 'pixiv.i18n.trustedGateEpoch', '2'], dir);
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
    copyGateSurfaceFiles(REPO_ROOT, dir);
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

test('workflow：feature push 以归一化 before 调用 contract v4 trusted resolver', () => {
    const doc = readWorkflow(REPO_ROOT);
    for (const jobId of ['signature-guard', 'trusted-gate-contract', 'i18n-check']) {
        const step = doc.jobs[jobId].steps.find((candidate) =>
            typeof candidate.run === 'string' && /resolve-trusted-base\.mjs/.test(candidate.run));
        assert.ok(step, jobId + ' 必须调用 trusted resolver');
        assert.match(step.run, /helper_before="\$\{\{ github\.event\.before \}\}"/);
        assert.match(step.run,
            /GITHUB_REF" != "refs\/heads\/\$helper_default_branch"[\s\S]*helper_before="\$zero"/);
        assert.match(step.run, /--before "\$helper_before"/);
        assert.doesNotMatch(step.run, /--candidate "\$GITHUB_SHA" --ref "\$GITHUB_REF"/,
            jobId + ' 不得向 contract v4 trusted resolver 传递新增参数');
    }
});

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

// ---------------------------------------------------------------------------
// 23.1 shell 规范化：注释 / || true / ; true / if false 包裹 必须全部拒绝
// ---------------------------------------------------------------------------

test('workflow 契约：test:js 被注释 + : 伪装 → 拒绝（注释不能伪装成命令）', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        const step = doc.jobs['javascript-tests'].steps.find((s) => typeof s.run === 'string'
            && s.run.includes('npm run test:js'));
        assert.ok(step, '测试前提：必须存在 test:js step');
        step.run = '# npm run test:js\n:';
        writeWorkflow(root, doc);
        commitBypass(root, 'comment + colon instead of test:js');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, '注释 + : 伪装必须被拒绝');
        assert.match(run.stdout + run.stderr, /no-op|test:js/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：npm run test:js || true → 拒绝（吞掉失败）', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        const step = doc.jobs['javascript-tests'].steps.find((s) => typeof s.run === 'string'
            && s.run.includes('npm run test:js'));
        assert.ok(step, '测试前提：必须存在 test:js step');
        step.run = 'npm run test:js || true';
        writeWorkflow(root, doc);
        commitBypass(root, 'swallow test:js failure');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'npm run test:js || true 必须被拒绝');
        assert.match(run.stdout + run.stderr, /swallows|true/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：npm run test:js; true → 拒绝（分号吞掉失败）', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        const step = doc.jobs['javascript-tests'].steps.find((s) => typeof s.run === 'string'
            && s.run.includes('npm run test:js'));
        assert.ok(step, '测试前提：必须存在 test:js step');
        step.run = 'npm run test:js; true';
        writeWorkflow(root, doc);
        commitBypass(root, 'semicolon + true after test:js');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'npm run test:js; true 必须被拒绝');
        assert.match(run.stdout + run.stderr, /swallows|true/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：if false; then npm run test:js; fi → 拒绝（条件跳过）', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        const step = doc.jobs['javascript-tests'].steps.find((s) => typeof s.run === 'string'
            && s.run.includes('npm run test:js'));
        assert.ok(step, '测试前提：必须存在 test:js step');
        step.run = 'if false; then npm run test:js; fi';
        writeWorkflow(root, doc);
        commitBypass(root, 'if false wrapper around test:js');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'if false; then 包裹必须被拒绝');
        assert.match(run.stdout + run.stderr, /if false|conditional/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

// ---------------------------------------------------------------------------
// Epoch 2 机制：input 优先级 / root tag / 物化交叉验证
// ---------------------------------------------------------------------------

test('workflow 契约：push branches-ignore [gh-pages] → branches [master] → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        doc.on.push = { branches: ['master'] };
        writeWorkflow(root, doc);
        commitBypass(root, 'narrow push coverage');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'Quality Gate push 覆盖缩回 master-only 必须拒绝');
        assert.match(run.stdout + run.stderr, /push coverage|branches allow-list|excluded branches/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：minimumTrustedVerifier 只在 ROOT_ADMISSION 分支执行 → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        const baseStep = doc.jobs['signature-guard'].steps.find((s) =>
            /minimum_json=.*minimumTrustedVerifier/.test(s.run || ''));
        assert.ok(baseStep, '测试前提：必须存在 minimumTrustedVerifier bootstrap');
        const original = baseStep.run;
        baseStep.run = baseStep.run
            .replace(/([^\n]*ROOT ADMISSION MODE:[^\n]*\n)\s*fi\n(\s*# Candidate policy proposes)/,
                '$1$2')
            .replace(/(\s*done <<< "\$minimum_files"\n)/, '$1fi\n');
        assert.notEqual(baseStep.run, original, '测试前提：必须成功把 baseline 移入 ROOT_ADMISSION');
        writeWorkflow(root, doc);
        commitBypass(root, 'restrict minimum verifier baseline to root admission');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'NORMAL candidate 跳过 minimumTrustedVerifier 必须拒绝');
        assert.match(run.stdout + run.stderr, /minimumTrustedVerifier applies|NORMAL.*baseline/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：删除 inputs.trusted_base_sha 优先级（reusable 语义）→ 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        const baseStep = doc.jobs['signature-guard'].steps.find((s) => /base_sha|event\.before/.test(s.run || ''));
        assert.ok(baseStep, '测试前提：必须存在 trusted base 解析 step');
        baseStep.run = baseStep.run.replace(/inputs\.trusted_base_sha/g, 'inputs.missing_input');
        writeWorkflow(root, doc);
        commitBypass(root, 'drop input precedence');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'input 优先级被移除必须被拒绝（workflow_call 不能依赖 event）');
        assert.match(run.stdout + run.stderr, /input takes priority|trusted_base_sha/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：删除 Epoch 2 root tag 解析 → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        const baseStep = doc.jobs['signature-guard'].steps.find((s) => /base_sha|event\.before/.test(s.run || ''));
        assert.ok(baseStep, '测试前提：必须存在 trusted base 解析 step');
        baseStep.run = baseStep.run.replace(/i18n-gate-epoch-3-root/g, 'i18n-gate-epoch-1-root');
        writeWorkflow(root, doc);
        commitBypass(root, 'swap root tag name');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'root tag 解析被移除必须被拒绝');
        assert.match(run.stdout + run.stderr, /root tag|ROOT_ADMISSION/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('workflow 契约：删除物化交叉验证（materialize-trusted-gate.sh）→ 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const doc = readWorkflow(root);
        const baseStep = doc.jobs['signature-guard'].steps.find((s) => /base_sha|event\.before/.test(s.run || ''));
        assert.ok(baseStep, '测试前提：必须存在 trusted base 解析 step');
        baseStep.run = baseStep.run.replace(/materialize-trusted-gate\.sh/g, 'materialize-other.sh');
        writeWorkflow(root, doc);
        commitBypass(root, 'drop materialization cross-check');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, '物化交叉验证被移除必须被拒绝');
        assert.match(run.stdout + run.stderr, /materialization cross-check/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

// ---------------------------------------------------------------------------
// 外围 workflow 语义契约 mutation（shared-snippets / release / nightly / publish）
// ---------------------------------------------------------------------------

function readExternalWorkflow(root, rel) {
    return YAML.parse(fs.readFileSync(path.join(root, rel), 'utf8'));
}

function writeExternalWorkflow(root, rel, doc) {
    fs.writeFileSync(path.join(root, rel), YAML.stringify(doc), 'utf8');
}

function runExternalMutation(root, trusted, rel, mutate, expectPattern) {
    const doc = readExternalWorkflow(root, rel);
    mutate(doc);
    writeExternalWorkflow(root, rel, doc);
    commitBypass(root, 'mutation: ' + rel);
    const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
    const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
    assert.notEqual(run.status, 0, 'mutation of ' + rel + ' must be rejected');
    assert.match(run.stdout + run.stderr, expectPattern);
}

test('shared-snippets 契约：删除 check-shared-snippets job → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/shared-snippets-check.yml',
            (d) => { delete d.jobs['check-shared-snippets']; }, /check-shared-snippets/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('shared-snippets 契约：workflow 只 echo（去掉真实 -Check）→ 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/shared-snippets-check.yml',
            (d) => {
                const step = d.jobs['check-shared-snippets'].steps.find((s) => typeof s.run === 'string'
                    && s.run.includes('sync-shared-snippets.ps1'));
                step.run = 'echo ok';
            }, /sync-shared-snippets|-Check/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('shared-snippets 契约：删除 -Check 参数 → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/shared-snippets-check.yml',
            (d) => {
                const step = d.jobs['check-shared-snippets'].steps.find((s) => typeof s.run === 'string'
                    && s.run.includes('sync-shared-snippets.ps1'));
                step.run = './scripts/sync-shared-snippets.ps1';
            }, /-Check|sync-shared-snippets/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('shared-snippets 契约：step continue-on-error → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/shared-snippets-check.yml',
            (d) => {
                const step = d.jobs['check-shared-snippets'].steps.find((s) => typeof s.run === 'string'
                    && s.run.includes('sync-shared-snippets.ps1'));
                step['continue-on-error'] = true;
            }, /continue-on-error|sync-shared-snippets/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('shared-snippets 契约：if false 包裹 → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/shared-snippets-check.yml',
            (d) => {
                const step = d.jobs['check-shared-snippets'].steps.find((s) => typeof s.run === 'string'
                    && s.run.includes('sync-shared-snippets.ps1'));
                step.run = 'if false; then ./scripts/sync-shared-snippets.ps1 -Check; fi';
            }, /if false|sync-shared-snippets/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('shared-snippets 契约：sync-shared-snippets.ps1 = exit 0 → 拒绝（checker 本体受保护）', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        fs.writeFileSync(path.join(root, 'scripts', 'sync-shared-snippets.ps1'), 'exit 0\n', 'utf8');
        commitBypass(root, 'sync checker = exit 0');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'sync-shared-snippets.ps1 = exit 0 必须被拒绝');
        assert.match(run.stdout + run.stderr, /sync-shared-snippets|drift gate/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('release 契约：删除 draft-quality-gate → 拒绝（手动发布不能跳门禁）', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/release.yml',
            (d) => { delete d.jobs['draft-quality-gate']; }, /draft-quality-gate/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('release 契约：build-jar 去掉 needs: publish-plugins → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/release.yml',
            (d) => {
                const needs = Array.isArray(d.jobs['build-jar'].needs) ? d.jobs['build-jar'].needs : [d.jobs['build-jar'].needs];
                d.jobs['build-jar'].needs = needs.filter((n) => n !== 'publish-plugins');
            }, /publish-plugins/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('release 契约：release job 改 always() → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/release.yml',
            (d) => { d.jobs['release'].if = '${{ always() }}'; }, /always\(\)/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('publish 契约：删除 quality-gate → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/publish-plugins.yml',
            (d) => { delete d.jobs['quality-gate']; }, /quality-gate/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('publish 契约：publish 去掉 needs: quality-gate → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/publish-plugins.yml',
            (d) => {
                const needs = Array.isArray(d.jobs['publish'].needs) ? d.jobs['publish'].needs : [d.jobs['publish'].needs];
                d.jobs['publish'].needs = needs.filter((n) => n !== 'quality-gate');
            }, /quality-gate/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('publish 契约：publish if 改 always() → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/publish-plugins.yml',
            (d) => { d.jobs['publish'].if = '${{ always() }}'; }, /always\(\)|success/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('nightly 契约：删除 publish-plugins 依赖（build-jar）→ 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/nightly.yml',
            (d) => {
                d.jobs['build-jar'].needs = d.jobs['build-jar'].needs.filter((n) => n !== 'publish-plugins');
            }, /publish-plugins/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('nightly 契约：删除 publish-plugins job → 拒绝（nightly 产物不能脱离质量门禁）', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        runExternalMutation(root, trusted, '.github/workflows/nightly.yml',
            (d) => { delete d.jobs['publish-plugins']; }, /publish-plugins/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

// ---------------------------------------------------------------------------
// Ruleset invariant 契约（github-ruleset-invariants.json 安全语义只增不减）
// ---------------------------------------------------------------------------

function mutateRulesetInvariants(root, mutate) {
    const file = path.join(root, 'scripts', 'ci', 'github-ruleset-invariants.json');
    const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
    mutate(doc);
    fs.writeFileSync(file, JSON.stringify(doc, null, 2) + '\n', 'utf8');
}

test('ruleset 契约：恶意候选（requireStrict=false / allowBypass=true / allowDeletion=true / allowNonFastForward=true / requiredChecks=[]）→ 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        mutateRulesetInvariants(root, (doc) => {
            doc.master = {
                requiredChecks: [],
                requireStrict: false,
                allowBypass: true,
                allowDeletion: true,
                allowNonFastForward: true,
            };
            doc['i18n-gate-epoch-3-root'] = {
                allowDeletion: true,
                allowNonFastForward: true,
                allowBypass: true,
            };
        });
        commitBypass(root, 'weaken ruleset invariants');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, '弱化 ruleset 不变量必须被 contract 拒绝');
        assert.match(run.stdout + run.stderr, /requireStrict|allowBypass|required checks not reduced/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('ruleset 契约：删除 github-ruleset-invariants.json → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        fs.rmSync(path.join(root, 'scripts', 'ci', 'github-ruleset-invariants.json'));
        commitBypass(root, 'delete ruleset invariants');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, '删除 ruleset 不变量文件必须被拒绝');
        assert.match(run.stdout + run.stderr, /github-ruleset-invariants\.json/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('ruleset 契约：schemaVersion 降低 → 拒绝', () => {
    const root = makeFullCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        mutateRulesetInvariants(root, (doc) => { doc.schemaVersion = 0; });
        commitBypass(root, 'lower ruleset schemaVersion');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'schemaVersion 降低必须被拒绝');
        assert.match(run.stdout + run.stderr, /schemaVersion/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});
