'use strict';
/**
 * gate-contract 黑盒测试：
 * - 合法候选通过（与 trusted bundle 一致 → 归纳跳过 + 行为验证）；
 * - no-op checker / no-op contract / required path 删除 / policy 弱化 → fail closed；
 * - --version 完整性检查；缺参数 usage error。
 * 合约从「重写 policy 的 trusted copy」运行（候选 policy 的 enforcement start 指向夹具仓库）。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
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

/** 夹具：C1（enforcement start，gate bundle 无 policy）+ C2（policy）+ anchor = C2。 */
function makeCandidateRepo() {
    const dir = path.join(os.tmpdir(), 'pixiv contract repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'), { force: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'hooks'), path.join(dir, 'scripts', 'hooks'), { recursive: true });
    // 当前 verifier baseline：fixture 必须携带完整 gate bundle（scripts/ci + workflow + package）
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'ci'), path.join(dir, 'scripts', 'ci'), { recursive: true });
    fs.mkdirSync(path.join(dir, '.github', 'workflows'), { recursive: true });
    fs.copyFileSync(path.join(REPO_ROOT, '.github', 'workflows', 'quality-gate.yml'),
        path.join(dir, '.github', 'workflows', 'quality-gate.yml'));
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
    // fixture 始终采用当前 policy epoch，避免跨 Epoch 时把旧常量伪装成 contract 失败。
    git(['config', '--local', 'pixiv.i18n.trustedGateEpoch', String(policy.gateEpoch)], dir);
    git(['config', '--local', 'pixiv.i18n.trustedGateRef', anchor], dir);
    return dir;
}

/** trusted copy：真实 scripts/i18n + hooks + scripts/ci + workflow + package，policy 的 enforcement start 指向夹具。 */
function makeTrustedCopy(repoRoot) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv contract trusted-'));
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'hooks'), path.join(dir, 'scripts', 'hooks'), { recursive: true });
    // 当前 verifier baseline：trusted verifier 必须携带完整 gate bundle（scripts/ci + workflow + package）
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'ci'), path.join(dir, 'scripts', 'ci'), { recursive: true });
    fs.mkdirSync(path.join(dir, '.github', 'workflows'), { recursive: true });
    fs.copyFileSync(path.join(REPO_ROOT, '.github', 'workflows', 'quality-gate.yml'),
        path.join(dir, '.github', 'workflows', 'quality-gate.yml'));
    copyGateSurfaceFiles(REPO_ROOT, dir);
    fs.copyFileSync(path.join(REPO_ROOT, 'package.json'), path.join(dir, 'package.json'));
    const policy = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
    const start = git(['rev-parse', 'HEAD~1'], repoRoot).stdout.trim();
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

test('gate-contract：合法候选（与 trusted bundle 一致）→ 通过', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const anchor = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', anchor]);
        assert.equal(run.status, 0, run.stdout + run.stderr);
        assert.match(run.stdout, /GATE CONTRACT OK/);

        // index 模式（合法暂存状态）同样通过
        const indexRun = runContract(trusted, root, ['--repo-root', root, '--candidate-snapshot', 'index']);
        assert.equal(indexRun.status, 0, indexRun.stdout + indexRun.stderr);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：candidate checker 被改为 exit(0) → 黑盒拒绝（不能自批准）', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'),
            '#!/usr/bin/env node\nprocess.exit(0);\n', 'utf8');
        commitBypass(root, 'noop checker');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'no-op checker 必须被 trusted contract 拒绝');
        assert.match(run.stdout + run.stderr, /GATE CONTRACT FAILED/);
        assert.match(run.stdout + run.stderr, /bad-placeholder|issue\.type|black-box/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：candidate contract 被改为 exit(0) → 自保护拒绝（不能保护下一次升级）', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'gate-contract.mjs'),
            '#!/usr/bin/env node\nprocess.exit(0);\n', 'utf8');
        commitBypass(root, 'noop contract');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'no-op contract 必须被 trusted contract 拒绝');
        assert.match(run.stdout + run.stderr, /GATE CONTRACT FAILED/);
        assert.match(run.stdout + run.stderr, /self-protection|malicious|next upgrade/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：candidate 删除 first-admission source/parent/tree 精确绑定 → 拒绝', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const hookFile = path.join(root, 'scripts', 'hooks', 'pre-commit');
        const hook = fs.readFileSync(hookFile, 'utf8').replace(
            '    && [ "$admission_parent" = "$trusted" ] && [ "$admission_parent" = "$current_parent" ] \\\n'
                + '    && [ "$admission_tree" = "$current_tree" ]; then',
            '    ; then');
        fs.writeFileSync(hookFile, hook, 'utf8');
        assert.notEqual(hook, fs.readFileSync(path.join(trusted, 'scripts', 'hooks', 'pre-commit'), 'utf8'),
            '测试前提：必须成功删除 first-admission 精确绑定');
        commitBypass(root, 'weaken first admission binding');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'first-admission 失去 source/parent/tree 精确绑定必须拒绝');
        assert.match(run.stdout + run.stderr, /prepared-root binding|candidate pre-commit was weakened/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：candidate 修改 first-admission bridge spec 或 launcher → 拒绝', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        for (const rel of ['scripts/i18n/epoch-2-first-admission.json',
            'scripts/i18n/trust-gate.mjs']) {
            const file = path.join(root, ...rel.split('/'));
            fs.appendFileSync(file, '\n', 'utf8');
        }
        commitBypass(root, 'weaken first admission bridge');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'bridge spec/launcher 被修改时 trusted contract 必须拒绝');
        assert.match(run.stdout + run.stderr,
            /first-admission epoch-2-first-admission\.json is frozen/);
        assert.match(run.stdout + run.stderr, /first-admission trust-gate\.mjs is frozen/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：candidate 删除 trusted contract 的 Git 环境隔离 → 拒绝', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        for (const hook of ['pre-commit', 'pre-push']) {
            const hookFile = path.join(root, 'scripts', 'hooks', hook);
            const original = fs.readFileSync(hookFile, 'utf8');
            fs.writeFileSync(hookFile,
                original.replace('git rev-parse --local-env-vars', 'printf GIT_DIR'), 'utf8');
            commitBypass(root, 'remove trusted contract Git environment isolation from ' + hook);
            const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
            const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
            assert.notEqual(run.status, 0, hook + ' 删除 Git 环境隔离必须拒绝');
            assert.match(run.stdout + run.stderr, /Git environment isolation|candidate .* was weakened/);
            git(['reset', '-q', '--hard', 'HEAD~1'], root);
        }
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：required path 删除（check.mjs / pre-push / gate-contract.mjs）→ fail closed', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        for (const rel of ['scripts/i18n/check.mjs', 'scripts/hooks/pre-push', 'scripts/i18n/gate-contract.mjs']) {
            git(['rm', '-q', rel], root);
            commitBypass(root, 'delete ' + rel);
            const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
            const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
            assert.notEqual(run.status, 0, '删除 required path 必须 fail closed: ' + rel);
            assert.match(run.stdout + run.stderr, /GATE CONTRACT FAILED|required gate files|incomplete/);
            git(['reset', '-q', '--hard', 'HEAD~1'], root);
        }
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：candidate policy 弱化（contractVersion 降低 / requiredPaths 减少 / enforcement start 后移）→ 拒绝', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');

        // contractVersion 降低
        const p1 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        p1.contractVersion = 0;
        fs.writeFileSync(policyPath, JSON.stringify(p1, null, 2) + '\n', 'utf8');
        commitBypass(root, 'lower contract version');
        let sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        let run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'contractVersion 降低必须拒绝');
        assert.match(run.stdout + run.stderr, /contractVersion/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // requiredPaths 减少
        const p2 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        p2.requiredPaths = p2.requiredPaths.filter((p) => p !== 'scripts/i18n/check.mjs');
        fs.writeFileSync(policyPath, JSON.stringify(p2, null, 2) + '\n', 'utf8');
        commitBypass(root, 'reduce required paths');
        sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'requiredPaths 减少必须拒绝');
        assert.match(run.stdout + run.stderr, /required paths/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // enforcement start 向后移动
        const p3 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        p3.i18nEnforcementStartCommit = 'ffffffffffffffffffffffffffffffffffffffff';
        fs.writeFileSync(policyPath, JSON.stringify(p3, null, 2) + '\n', 'utf8');
        commitBypass(root, 'move enforcement start');
        sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'enforcement start 后移必须拒绝');
        assert.match(run.stdout + run.stderr, /enforcement/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：minimumTrustedVerifier 三个维度降低（candidate 自身版本不变）→ 拒绝', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');
        for (const [name, mutate] of [
            ['contractVersion 4→3', (minimum) => { minimum.contractVersion = 3; }],
            ['schemaVersion 3→2', (minimum) => { minimum.schemaVersion = 2; }],
            ['requiredFiles 删除 doctor', (minimum) => {
                minimum.requiredFiles = minimum.requiredFiles
                    .filter((rel) => rel !== 'scripts/ci/doctor-github-ruleset.mjs');
            }],
        ]) {
            const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
            mutate(policy.minimumTrustedVerifier);
            fs.writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n', 'utf8');
            commitBypass(root, 'lower minimum verifier baseline: ' + name);
            const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
            const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
            assert.notEqual(run.status, 0, 'minimum verifier baseline 降低必须拒绝: ' + name);
            assert.match(run.stdout + run.stderr, /minimum trusted verifier|minimumTrustedVerifier/);
            git(['reset', '-q', '--hard', 'HEAD~1'], root);
        }
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：candidate 新增 required path 但文件不存在（phantom 声明）→ 拒绝', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');
        const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        policy.requiredPaths = [...policy.requiredPaths, 'scripts/i18n/nonexistent.mjs'];
        fs.writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n', 'utf8');
        commitBypass(root, 'declare phantom required path');
        const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'candidate 声明不存在的 required path 必须被拒绝');
        assert.match(run.stdout + run.stderr, /required|incomplete|missing/);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：candidate 减少 protectedBranches / requiredWorkflowJobs → 拒绝；新增允许', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');

        // protectedBranches 置空数组 → 结构校验拒绝
        const p1 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        p1.protectedBranches = [];
        fs.writeFileSync(policyPath, JSON.stringify(p1, null, 2) + '\n', 'utf8');
        commitBypass(root, 'drop protected branches');
        let sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        let run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'protectedBranches 置空必须拒绝');
        assert.match(run.stdout + run.stderr, /protectedBranch(es)?/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // protectedBranches 字段整体删除 → 集合减少 → 拒绝
        const p1b = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        delete p1b.protectedBranches;
        fs.writeFileSync(policyPath, JSON.stringify(p1b, null, 2) + '\n', 'utf8');
        commitBypass(root, 'omit protected branches');
        sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, '删除 protectedBranches 字段必须拒绝');
        assert.match(run.stdout + run.stderr, /protected branches/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // requiredWorkflowJobs 减少
        const p2 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        p2.requiredWorkflowJobs = p2.requiredWorkflowJobs.filter((j) => j !== 'java-tests');
        fs.writeFileSync(policyPath, JSON.stringify(p2, null, 2) + '\n', 'utf8');
        commitBypass(root, 'drop required workflow jobs');
        sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'requiredWorkflowJobs 减少必须拒绝');
        assert.match(run.stdout + run.stderr, /required workflow jobs/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 新增 protectedBranches / requiredWorkflowJobs 允许（不会减少 trusted 集合）
        const p3 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        p3.protectedBranches = ['refs/heads/master', 'refs/heads/release'];
        p3.requiredWorkflowJobs = [...p3.requiredWorkflowJobs, 'extra-job'];
        fs.writeFileSync(policyPath, JSON.stringify(p3, null, 2) + '\n', 'utf8');
        commitBypass(root, 'add protected branches and jobs');
        sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.equal(run.status, 0, '新增 protectedBranches / requiredWorkflowJobs 必须允许: '
            + run.stdout + run.stderr);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：candidate gateEpoch 改变 / 删除字段 → 拒绝（epoch 升级属另一轮人工 root admission）', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');

        // 当前 gateEpoch → 下一 epoch：结构校验拒绝（未来 epoch）
        const p1 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        const currentEpoch = p1.gateEpoch;
        p1.gateEpoch = currentEpoch + 1;
        fs.writeFileSync(policyPath, JSON.stringify(p1, null, 2) + '\n', 'utf8');
        commitBypass(root, 'change gate epoch forward');
        let sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        let run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'gateEpoch 向前改变必须拒绝');
        assert.match(run.stdout + run.stderr, /gateEpoch/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 当前 gateEpoch → 1：结构校验拒绝（obsolete epoch）
        const p1b = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        p1b.gateEpoch = 1;
        fs.writeFileSync(policyPath, JSON.stringify(p1b, null, 2) + '\n', 'utf8');
        commitBypass(root, 'change gate epoch to 1');
        sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'gateEpoch 向后改变必须拒绝');
        assert.match(run.stdout + run.stderr, /gateEpoch/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：candidate 减少 requiredPackageScripts / requiredExternalChecks → 拒绝；新增允许', () => {
    const root = makeCandidateRepo();
    const trusted = makeTrustedCopy(root);
    try {
        const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');

        // requiredPackageScripts 减少
        const p1 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        p1.requiredPackageScripts = p1.requiredPackageScripts.filter((s) => s !== 'test:i18n');
        fs.writeFileSync(policyPath, JSON.stringify(p1, null, 2) + '\n', 'utf8');
        commitBypass(root, 'drop required package scripts');
        let sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        let run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'requiredPackageScripts 减少必须拒绝');
        assert.match(run.stdout + run.stderr, /required package scripts/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // requiredExternalChecks 减少
        const p2 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        p2.requiredExternalChecks = [];
        fs.writeFileSync(policyPath, JSON.stringify(p2, null, 2) + '\n', 'utf8');
        commitBypass(root, 'drop required external checks');
        sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.notEqual(run.status, 0, 'requiredExternalChecks 置空必须拒绝');
        assert.match(run.stdout + run.stderr, /required external checks|requiredExternalChecks/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 新增允许
        const p3 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        p3.requiredPackageScripts = [...p3.requiredPackageScripts, 'i18n:extra'];
        p3.requiredExternalChecks = [...p3.requiredExternalChecks, 'Extra Check'];
        fs.writeFileSync(policyPath, JSON.stringify(p3, null, 2) + '\n', 'utf8');
        commitBypass(root, 'add package scripts and external checks');
        sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        run = runContract(trusted, root, ['--repo-root', root, '--candidate-ref', sha]);
        assert.equal(run.status, 0, '新增 requiredPackageScripts / requiredExternalChecks 必须允许: '
            + run.stdout + run.stderr);
    } finally {
        cleanRepo(root);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});

test('gate-contract：--version 完整性检查；缺参数 usage error', () => {
    const repo = makeCandidateRepo();
    const trusted = makeTrustedCopy(repo);
    try {
        const version = spawnSync('node', [path.join(trusted, 'scripts', 'i18n', 'gate-contract.mjs'), '--version'],
            { encoding: 'utf8' });
        assert.equal(version.status, 0, version.stdout + version.stderr);
        assert.match(version.stdout, /i18n-gate-contract 4/);

        const noRepo = spawnSync('node', [path.join(trusted, 'scripts', 'i18n', 'gate-contract.mjs'),
            '--candidate-ref', 'x'], { encoding: 'utf8' });
        assert.notEqual(noRepo.status, 0);
        assert.match(noRepo.stderr, /--repo-root/);

        const noCandidate = spawnSync('node', [path.join(trusted, 'scripts', 'i18n', 'gate-contract.mjs'),
            '--repo-root', repo], { encoding: 'utf8' });
        assert.notEqual(noCandidate.status, 0);
        assert.match(noCandidate.stderr, /candidate/);
    } finally {
        cleanRepo(repo);
        fs.rmSync(trusted, { recursive: true, force: true });
    }
});
