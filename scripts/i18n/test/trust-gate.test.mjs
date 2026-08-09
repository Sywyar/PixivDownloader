'use strict';
/**
 * trust-gate CLI 测试（Gate Epoch 3）：--show / --adopt-root / --advance。
 * - adopt-root 是人工 TOFU / root admission：只接受完整 commit、干净状态、
 *   完整 i18n tests、ref snapshot check、signature guard、root contract self-test
 *   （--force-self-protection）、gate parity（--invariants）、required files，CI 禁止；
 *   全部通过才写 epoch == 3 + ref；
 * - advance 由 trusted Epoch 3 contract 审核候选：no-op checker / no-op contract /
 *   删除 required file / 弱化 policy（含 epoch 改变）/ 门禁减少一律拒绝；不自动发生；
 * - 旧 epoch anchor 不迁移：advance 直接 OBSOLETE GATE EPOCH 拒绝。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runAcceptCore } from '../accept.mjs';
import { runGenerate } from '../generate-static.mjs';
import trustedGate from '../lib/trusted-gate.mjs';
import { copyGateSurfaceFiles } from './lib/surface-fixture.mjs';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');
const CLI = path.join(SCRIPTS_DIR, 'trust-gate.mjs');
const FIRST_ADMISSION_SPEC_REL = 'scripts/i18n/epoch-2-first-admission.json';

// advance 的 trusted contract 使用 yaml 解析候选 workflow：fixture 仓库没有 node_modules，
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
const EXIT_ZERO_CHECKER = '#!/usr/bin/env node\nprocess.exit(0);\n';

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

function materializeGateRef(ref) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv historical gate '));
    const paths = git(['ls-tree', '-r', '--name-only', ref, '--',
        ...trustedGate.GATE_PATHS], REPO_ROOT).stdout.trim().split(/\r?\n/).filter(Boolean);
    for (const rel of paths) {
        const target = path.join(dir, ...rel.split('/'));
        fs.mkdirSync(path.dirname(target), { recursive: true });
        const shown = git(['show', ref + ':' + rel], REPO_ROOT, { encoding: null });
        fs.writeFileSync(target, shown.stdout);
    }
    return dir;
}

function findFirstAdmissionSource() {
    for (const sha of git(['rev-list', 'HEAD'], REPO_ROOT).stdout.trim().split(/\r?\n/)) {
        if (git(['cat-file', '-e', sha + ':' + FIRST_ADMISSION_SPEC_REL], REPO_ROOT,
            { allowFailure: true }).status !== 0) {
            continue;
        }
        const policy = JSON.parse(git(['show', sha + ':scripts/i18n/gate-policy.json'], REPO_ROOT).stdout);
        if (policy.gateEpoch === 2) {
            return sha;
        }
    }
    throw new Error('cannot find the historical Epoch 2 first-admission source');
}

/** 与 hooks 测试同构的夹具；withAnchor=false 时不写 trusted anchor（供 CLI 自行建立）。 */
function makeRepo(withAnchor = false, anchorEpoch = '3', fixtureRef = null) {
    const dir = path.join(os.tmpdir(), 'pixiv trust repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);
    // 与真实仓库一致：build/ 忽略（checker/contract 的报告目录）
    fs.writeFileSync(path.join(dir, '.gitignore'), 'build/\nnode_modules/\n', 'utf8');
    const fixtureRoot = fixtureRef ? materializeGateRef(fixtureRef) : REPO_ROOT;
    let policyTemplate;
    try {
        fs.cpSync(path.join(fixtureRoot, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
        policyTemplate = JSON.parse(fs.readFileSync(
            path.join(fixtureRoot, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
        fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
        fs.rmSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'), { force: true });
        fs.cpSync(path.join(fixtureRoot, 'scripts', 'hooks'), path.join(dir, 'scripts', 'hooks'), { recursive: true });
        // adopt-root 要求 policy 的 required paths 全部存在：夹具必须携带完整 gate bundle
        fs.cpSync(path.join(fixtureRoot, 'scripts', 'ci'), path.join(dir, 'scripts', 'ci'), { recursive: true });
        fs.mkdirSync(path.join(dir, '.github', 'workflows'), { recursive: true });
        fs.copyFileSync(path.join(fixtureRoot, '.github', 'workflows', 'quality-gate.yml'),
            path.join(dir, '.github', 'workflows', 'quality-gate.yml'));
        copyGateSurfaceFiles(fixtureRoot, dir);
        fs.copyFileSync(path.join(fixtureRoot, 'package.json'), path.join(dir, 'package.json'));
        fs.copyFileSync(path.join(fixtureRoot, 'package-lock.json'), path.join(dir, 'package-lock.json'));
    } finally {
        if (fixtureRef) {
            fs.rmSync(fixtureRoot, { recursive: true, force: true });
        }
    }
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
    for (const hook of ['pre-commit', 'pre-push', 'pre-push-guard.sh']) {
        fs.chmodSync(path.join(dir, 'scripts', 'hooks', hook), 0o755);
    }
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'init'], dir); // C1
    const start = git(['rev-parse', 'HEAD'], dir).stdout.trim();
    const policy = policyTemplate;
    policy.i18nEnforcementStartCommit = start;
    fs.writeFileSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'),
        JSON.stringify(policy, null, 2) + '\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'add gate policy'], dir); // C2
    const anchor = git(['rev-parse', 'HEAD'], dir).stdout.trim();
    const originDir = path.join(dir, '.git', 'test-origin.git');
    git(['init', '--bare', '-q', originDir], dir);
    git(['remote', 'add', 'origin', originDir], dir);
    git(['push', '-q', 'origin', 'HEAD:refs/heads/master'], dir);
    git(['update-ref', 'refs/remotes/origin/master', anchor], dir);
    git(['config', '--local', 'core.hooksPath', 'scripts/hooks'], dir);
    if (withAnchor) {
        git(['config', '--local', 'pixiv.i18n.trustedGateEpoch', anchorEpoch], dir);
        git(['config', '--local', 'pixiv.i18n.trustedGateRef', anchor], dir);
    }
    return dir;
}

function commitBypass(root, message) {
    git(['add', '-A'], root);
    git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', message], root);
}

function runCli(root, args, env = {}) {
    // clearCI：CI 环境的测试进程会继承 CI=true，CLI 会因此拒绝 adopt-root/advance；
    // 除了「CI 禁止」专项测试外，其余测试都要显式清除 CI（等价于非 CI 机器）。
    const merged = { ...process.env, ...env };
    if (env.clearCI) {
        delete merged.CI;
        delete merged.clearCI;
    }
    return spawnSync('node', [CLI, ...args], { cwd: root, encoding: 'utf8', env: merged, timeout: 600000 });
}

function runRepoCli(root, args, env = {}) {
    const merged = { ...process.env, ...env };
    if (env.clearCI) {
        delete merged.CI;
        delete merged.clearCI;
    }
    return spawnSync('node', [path.join(root, 'scripts', 'i18n', 'trust-gate.mjs'), ...args],
        { cwd: root, encoding: 'utf8', env: merged, timeout: 600000 });
}

function runRepoCliWithStdoutAction(root, args, marker, action, env = {}) {
    const merged = { ...process.env, ...env };
    if (env.clearCI) {
        delete merged.CI;
        delete merged.clearCI;
    }
    return new Promise((resolve, reject) => {
        const child = spawn('node', [path.join(root, 'scripts', 'i18n', 'trust-gate.mjs'), ...args],
            { cwd: root, env: merged });
        let stdout = '';
        let stderr = '';
        let actionRun = false;
        let actionError = null;
        const timer = setTimeout(() => child.kill(), 600000);
        child.stdout.on('data', (chunk) => {
            stdout += chunk.toString();
            if (!actionRun && stdout.includes(marker)) {
                actionRun = true;
                try {
                    action();
                } catch (e) {
                    actionError = e;
                    child.kill();
                }
            }
        });
        child.stderr.on('data', (chunk) => {
            stderr += chunk.toString();
        });
        child.once('error', reject);
        child.once('close', (status) => {
            clearTimeout(timer);
            if (actionError) {
                reject(actionError);
                return;
            }
            resolve({ status: status === null ? 1 : status, stdout, stderr, actionRun });
        });
    });
}

function runTrustedCli(root, trustedSource, args, env = {}) {
    const bundle = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv trusted first admission '));
    try {
        const paths = git(['ls-tree', '-r', '--name-only', trustedSource, '--',
            ...trustedGate.GATE_PATHS], root).stdout.trim().split(/\r?\n/).filter(Boolean);
        for (const rel of paths) {
            const target = path.join(bundle, ...rel.split('/'));
            fs.mkdirSync(path.dirname(target), { recursive: true });
            const shown = spawnSync('git', ['show', trustedSource + ':' + rel], {
                cwd: root, encoding: null, maxBuffer: 64 * 1024 * 1024,
            });
            assert.equal(shown.status, 0, shown.stderr && shown.stderr.toString('utf8'));
            fs.writeFileSync(target, shown.stdout);
        }
        const merged = { ...process.env, ...env };
        if (env.clearCI) {
            delete merged.CI;
            delete merged.clearCI;
        }
        return spawnSync('node', [path.join(bundle, 'scripts', 'i18n', 'trust-gate.mjs'),
            ...args, '--trusted-source', trustedSource], {
            cwd: root, encoding: 'utf8', env: merged, timeout: 600000,
        });
    } finally {
        fs.rmSync(bundle, { recursive: true, force: true });
    }
}

function setLiveMaster(root, sha) {
    const originDir = git(['remote', 'get-url', 'origin'], root).stdout.trim();
    git(['--git-dir', originDir, 'update-ref', 'refs/heads/master', sha], root);
}

function rewriteFixtureForNextEpoch(root) {
    const spec = JSON.parse(fs.readFileSync(path.join(root, ...FIRST_ADMISSION_SPEC_REL.split('/')), 'utf8'));
    const contextReplacements = new Map(Object.entries(spec.requiredContextReplacements));
    const roots = [path.join(root, 'scripts'), path.join(root, '.github', 'workflows')];
    for (const start of roots) {
        const pending = [start];
        while (pending.length > 0) {
            const current = pending.pop();
            for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
                const file = path.join(current, entry.name);
                if (entry.isDirectory()) {
                    pending.push(file);
                    continue;
                }
                if (file.endsWith(path.join('scripts', 'i18n', 'epoch-2-first-admission.json'))) {
                    fs.rmSync(file);
                    continue;
                }
                if (file.endsWith(path.join('scripts', 'ci', 'github-ruleset-invariants.json'))) {
                    const rules = JSON.parse(fs.readFileSync(file, 'utf8'));
                    rules.master.requiredChecks = [...contextReplacements.values()];
                    rules['i18n-gate-epoch-3-root'] = { ...rules['i18n-gate-epoch-2-root'] };
                    fs.writeFileSync(file, JSON.stringify(rules, null, 2) + '\n', 'utf8');
                    continue;
                }
                let content = fs.readFileSync(file, 'utf8');
                content = content
                    .replaceAll('i18n-gate-epoch-2-root', 'i18n-gate-epoch-3-root')
                    .replace('export const CURRENT_GATE_EPOCH = 2;', 'export const CURRENT_GATE_EPOCH = 3;')
                    .replaceAll('"gateEpoch": 2', '"gateEpoch": 3');
                for (const [oldValue, newValue] of contextReplacements) {
                    content = content.replaceAll(oldValue, newValue);
                }
                if (file.endsWith(path.join('scripts', 'i18n', 'gate-policy.json'))) {
                    const policy = JSON.parse(content);
                    policy.requiredPaths = policy.requiredPaths
                        .filter((entryPath) => entryPath !== 'scripts/i18n/epoch-2-first-admission.json');
                    policy.minimumTrustedVerifier.requiredFiles = policy.minimumTrustedVerifier.requiredFiles
                        .filter((entryPath) => entryPath !== 'scripts/i18n/epoch-2-first-admission.json');
                    content = JSON.stringify(policy, null, 2) + '\n';
                }
                fs.writeFileSync(file, content, 'utf8');
            }
        }
    }
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

test('trust-gate：--show 未设置时提示 adopt-root', () => {
    const root = makeRepo();
    try {
        const show = runCli(root, ['--show']);
        assert.equal(show.status, 0, show.stdout + show.stderr);
        assert.match(show.stdout, /<not set>/);
        assert.match(show.stdout, /--adopt-root --ref HEAD --epoch 3/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：adopt-root 写入 epoch 3 + ref；--show 输出 SHA 与 contract version', () => {
    const root = makeRepo();
    try {
        const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);
        assert.match(adopt.stdout + adopt.stderr, /ROOT ADMISSION/);
        assert.match(adopt.stdout + adopt.stderr, /Gate Epoch 3 root adopted/);
        const configured = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        assert.equal(configured, head, 'adopt-root 必须写入当前 HEAD 的完整 SHA');
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch'], root).stdout.trim(), '3');

        const show = runCli(root, ['--show']);
        assert.equal(show.status, 0, show.stdout + show.stderr);
        assert.match(show.stdout, new RegExp('trustedGateRef: ' + head));
        assert.match(show.stdout, /trustedGateEpoch: 3/);
        assert.match(show.stdout, /contractVersion: 4/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：adopt-root 只写 local 配置（不写 global）', () => {
    const root = makeRepo();
    try {
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);
        const origin = git(['config', '--show-origin', '--get', 'pixiv.i18n.trustedGateRef'], root, { allowFailure: true });
        assert.match(origin.stdout, /\.git[/\\]config/);
        const global = git(['config', '--global', '--get', 'pixiv.i18n.trustedGateRef'], root, { allowFailure: true });
        assert.notEqual(global.status, 0, 'global 配置不得写入');
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：CI 环境禁止 prepare-root / seal-root / adopt-root / advance', () => {
    const root = makeRepo();
    try {
        const prepare = runCli(root,
            ['--prepare-root', '--epoch', '3', '--trusted-source', '0'.repeat(40)], { CI: 'true' });
        assert.notEqual(prepare.status, 0, 'CI=true 必须拒绝 prepare-root');
        assert.match(prepare.stderr, /forbidden in CI/);
        const seal = runCli(root, ['--seal-root', '--ref', 'HEAD', '--trusted-source', '0'.repeat(40)],
            { CI: 'true' });
        assert.notEqual(seal.status, 0, 'CI=true 必须拒绝 seal-root');
        assert.match(seal.stderr, /forbidden in CI/);
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { CI: 'true' });
        assert.notEqual(adopt.status, 0, 'CI=true 必须拒绝 adopt-root');
        assert.match(adopt.stderr, /forbidden in CI/);
        const configured = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root, { allowFailure: true });
        assert.notEqual(configured.status, 0, 'CI 拒绝后不得写入配置');

        const adoptOk = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(adoptOk.status, 0, adoptOk.stdout + adoptOk.stderr);
        const advance = runCli(root, ['--advance', '--ref', 'HEAD'], { CI: 'true' });
        assert.notEqual(advance.status, 0, 'CI=true 必须拒绝 advance');
        assert.match(advance.stderr, /forbidden in CI/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：adopt-root 只接受完整 commit + epoch 3（拒绝工作树路径 / 非 commit / 非 3 epoch）', () => {
    const root = makeRepo();
    try {
        for (const bad of ['HEAD^{tree}', './scripts', 'README.md', 'not-a-ref']) {
            const adopt = runCli(root, ['--adopt-root', '--ref', bad, '--epoch', '3'], { clearCI: true });
            assert.notEqual(adopt.status, 0, '必须拒绝: ' + bad);
            assert.match(adopt.stderr, /must resolve to a full commit/);
        }
        for (const badEpoch of ['1', '2', '4', 'x']) {
            const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', badEpoch], { clearCI: true });
            assert.notEqual(adopt.status, 0, 'epoch ' + badEpoch + ' 必须拒绝');
            assert.match(adopt.stderr, /--epoch must be exactly 3/);
        }
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：adopt-root 脏工作树 / 已存在 anchor 拒绝', () => {
    const root = makeRepo();
    try {
        fs.writeFileSync(path.join(root, 'dirty.txt'), 'dirty\n', 'utf8');
        const refused = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.notEqual(refused.status, 0, '脏工作树必须拒绝');
        assert.match(refused.stderr, /worktree is not clean/);
        fs.rmSync(path.join(root, 'dirty.txt'));

        const ok = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(ok.status, 0, ok.stdout + ok.stderr);
        const again = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.notEqual(again.status, 0, '已有 anchor 必须拒绝再次 adopt-root');
        assert.match(again.stderr, /already exists/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：trusted bridge 以实时 master tip 准备 tree、唯一封存 candidate SHA 后才允许采用', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeRepo(true, '2', findFirstAdmissionSource());
    try {
        const previousRoot = git(['rev-parse', 'HEAD'], root).stdout.trim();
        git(['tag', trustedGate.rootTagNameForEpoch(2), previousRoot], root);
        rewriteFixtureForNextEpoch(root);
        git(['add', '-A'], root);

        const staleMaster = git(['rev-parse', previousRoot + '^'], root).stdout.trim();
        setLiveMaster(root, staleMaster);
        const unprotected = runTrustedCli(root, previousRoot,
            ['--prepare-root', '--epoch', '3'], { clearCI: true });
        assert.notEqual(unprotected.status, 0, 'source 不等于实时 protected master tip 时必须拒绝');
        assert.match(unprotected.stdout + unprotected.stderr, /local and live protected master tip/);
        assert.notEqual(git(['config', '--local', '--get', 'pixiv.i18n.firstAdmissionTree'], root,
            { allowFailure: true }).status, 0, '失败 bridge 不得部分写 ticket');
        setLiveMaster(root, previousRoot);

        const candidateLauncher = runRepoCli(root,
            ['--prepare-root', '--epoch', '3', '--trusted-source', previousRoot], { clearCI: true });
        assert.notEqual(candidateLauncher.status, 0, 'candidate CLI 不得代理 first-admission bridge');
        assert.match(candidateLauncher.stdout + candidateLauncher.stderr,
            /external materialized trusted bundle/);

        const policyFile = path.join(root, 'scripts', 'i18n', 'gate-policy.json');
        const badPolicy = JSON.parse(fs.readFileSync(policyFile, 'utf8'));
        const sourceSpec = JSON.parse(git(['show', previousRoot + ':' + FIRST_ADMISSION_SPEC_REL], root).stdout);
        badPolicy.requiredExternalCheckDefinitions[0].requiredContext =
            Object.keys(sourceSpec.requiredContextReplacements).at(-1);
        fs.writeFileSync(policyFile, JSON.stringify(badPolicy, null, 2) + '\n', 'utf8');
        git(['add', 'scripts/i18n/gate-policy.json'], root);
        const dualContext = runTrustedCli(root, previousRoot,
            ['--prepare-root', '--epoch', '3'], { clearCI: true });
        assert.notEqual(dualContext.status, 0, '旧 context 或双 context 必须拒绝');
        assert.match(dualContext.stdout + dualContext.stderr, /requiredExternalCheckDefinitions|required context/);
        badPolicy.requiredExternalCheckDefinitions[0].requiredContext = 'check-shared-snippets';
        fs.writeFileSync(policyFile, JSON.stringify(badPolicy, null, 2) + '\n', 'utf8');
        git(['add', 'scripts/i18n/gate-policy.json'], root);

        fs.writeFileSync(path.join(root, 'unrelated.txt'), 'not part of the root transition\n', 'utf8');
        git(['add', 'unrelated.txt'], root);
        const expanded = runTrustedCli(root, previousRoot,
            ['--prepare-root', '--epoch', '3'], { clearCI: true });
        assert.notEqual(expanded.status, 0, 'first admission 不得夹带无关改动');
        assert.match(expanded.stdout + expanded.stderr, /out-of-scope or non-mechanical change/);
        git(['rm', '-q', '-f', 'unrelated.txt'], root);

        const prepare = runTrustedCli(root, previousRoot,
            ['--prepare-root', '--epoch', '3'], { clearCI: true });
        assert.equal(prepare.status, 0, prepare.stdout + prepare.stderr);
        assert.match(prepare.stdout + prepare.stderr, /Gate Epoch 3 root tree prepared/);
        const preparedTree = git(['config', '--local', '--get', 'pixiv.i18n.firstAdmissionTree'], root)
            .stdout.trim();
        assert.equal(preparedTree, git(['write-tree'], root).stdout.trim());
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.firstAdmissionSourceEpoch'], root)
            .stdout.trim(), '2');
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.firstAdmissionTargetEpoch'], root)
            .stdout.trim(), '3');
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.firstAdmissionTrustedSource'], root)
            .stdout.trim(), previousRoot);
        assert.notEqual(git(['config', '--local', '--get', 'pixiv.i18n.firstAdmissionCandidate'], root,
            { allowFailure: true }).status, 0, 'prepare 阶段不得猜测最终 candidate SHA');

        git(['config', '--local', 'pixiv.i18n.firstAdmissionTrustedSource', '0'.repeat(40)], root);
        const refusedCommit = git(['commit', '-q', '-m', 'mismatched epoch 3 root'], root,
            { allowFailure: true });
        assert.notEqual(refusedCommit.status, 0, 'ticket trusted source 不匹配时 pre-commit 必须拒绝');
        git(['config', '--local', 'pixiv.i18n.firstAdmissionTrustedSource', previousRoot], root);
        const commit = git(['commit', '-q', '-m', 'epoch 3 root'], root);
        assert.equal(commit.status, 0, commit.stdout + commit.stderr);

        const unsealedAdopt = runRepoCli(root,
            ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.notEqual(unsealedAdopt.status, 0, '未由 trusted bridge seal 的 commit 不得 adoption');
        assert.match(unsealedAdopt.stdout + unsealedAdopt.stderr, /sealed candidate SHA/);

        const candidateSeal = runRepoCli(root,
            ['--seal-root', '--ref', 'HEAD', '--trusted-source', previousRoot], { clearCI: true });
        assert.notEqual(candidateSeal.status, 0, 'candidate CLI 不得自行 seal candidate SHA');
        assert.match(candidateSeal.stdout + candidateSeal.stderr,
            /external materialized trusted bundle/);

        const seal = runTrustedCli(root, previousRoot,
            ['--seal-root', '--ref', 'HEAD'], { clearCI: true });
        assert.equal(seal.status, 0, seal.stdout + seal.stderr);
        const candidateSha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.firstAdmissionCandidate'], root)
            .stdout.trim(), candidateSha, 'trusted bridge 必须封存唯一 candidate SHA');
        const reseal = runTrustedCli(root, previousRoot,
            ['--seal-root', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(reseal.status, 0, '同一 ticket 不得再次 seal');

        setLiveMaster(root, staleMaster);
        const movedMasterAdopt = runRepoCli(root,
            ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.notEqual(movedMasterAdopt.status, 0, 'adopt 时必须再次确认实时 protected master tip');
        assert.match(movedMasterAdopt.stdout + movedMasterAdopt.stderr, /live protected master tip/);
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root)
            .stdout.trim(), previousRoot, '实时 tip 校验失败不得推进 anchor');
        setLiveMaster(root, previousRoot);

        git(['config', '--local', 'pixiv.i18n.firstAdmissionCandidate', '0'.repeat(40)], root);
        const refusedCandidate = runRepoCli(root,
            ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.notEqual(refusedCandidate.status, 0, 'sealed candidate SHA 不匹配时 adoption 必须拒绝');
        git(['config', '--local', 'pixiv.i18n.firstAdmissionCandidate', candidateSha], root);

        git(['config', '--local', 'pixiv.i18n.firstAdmissionParent', '0'.repeat(40)], root);
        const refusedAdopt = runRepoCli(root,
            ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.notEqual(refusedAdopt.status, 0, 'ticket parent 不匹配时 adoption 必须拒绝');
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root)
            .stdout.trim(), previousRoot, '失败 adoption 不得推进 anchor');
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.firstAdmissionParent'], root)
            .stdout.trim(), '0'.repeat(40), '失败 adoption 不得消费或改写 ticket');
        git(['config', '--local', 'pixiv.i18n.firstAdmissionParent', previousRoot], root);
        const adopt = runRepoCli(root,
            ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch'], root)
            .stdout.trim(), '3');
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root)
            .stdout.trim(), git(['rev-parse', 'HEAD'], root).stdout.trim());
        assert.notEqual(git(['config', '--local', '--get', 'pixiv.i18n.firstAdmissionTree'], root,
            { allowFailure: true }).status, 0, 'adopt-root 成功后必须清除 preparation ticket');
        assert.notEqual(git(['config', '--local', '--get', 'pixiv.i18n.firstAdmissionCandidate'], root,
            { allowFailure: true }).status, 0, 'adopt-root 成功后必须清除 candidate seal');
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：adopt-root 在 ROOT_ADMISSION 后按 commit-point 重验 live master', async () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeRepo(true, '2', findFirstAdmissionSource());
    try {
        const previousRoot = git(['rev-parse', 'HEAD'], root).stdout.trim();
        git(['tag', trustedGate.rootTagNameForEpoch(2), previousRoot], root);
        rewriteFixtureForNextEpoch(root);
        git(['add', '-A'], root);
        const prepare = runTrustedCli(root, previousRoot,
            ['--prepare-root', '--epoch', '3'], { clearCI: true });
        assert.equal(prepare.status, 0, prepare.stdout + prepare.stderr);
        const commit = git(['commit', '-q', '-m', 'epoch 3 root'], root);
        assert.equal(commit.status, 0, commit.stdout + commit.stderr);
        const seal = runTrustedCli(root, previousRoot,
            ['--seal-root', '--ref', 'HEAD'], { clearCI: true });
        assert.equal(seal.status, 0, seal.stdout + seal.stderr);
        const candidateSha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const ticketKeys = [
            trustedGate.FIRST_ADMISSION_SOURCE_EPOCH_KEY,
            trustedGate.FIRST_ADMISSION_TARGET_EPOCH_KEY,
            trustedGate.FIRST_ADMISSION_TRUSTED_SOURCE_KEY,
            trustedGate.FIRST_ADMISSION_PARENT_KEY,
            trustedGate.FIRST_ADMISSION_TREE_KEY,
            trustedGate.FIRST_ADMISSION_CANDIDATE_KEY,
        ];
        const ticketBefore = ticketKeys.map((key) =>
            git(['config', '--local', '--get', key], root).stdout.trim());
        const originDir = git(['remote', 'get-url', 'origin'], root).stdout.trim();
        const advancedMaster = git(['--git-dir', originDir,
            '-c', 'user.name=test', '-c', 'user.email=t@example.com',
            'commit-tree', previousRoot + '^{tree}', '-p', previousRoot], root,
            { input: 'advance protected master\n' }).stdout.trim();

        const adopt = await runRepoCliWithStdoutAction(root,
            ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'],
            'running the full i18n test suite', () => setLiveMaster(root, advancedMaster),
            { clearCI: true });
        assert.equal(adopt.actionRun, true, '必须在 ROOT_ADMISSION suite 运行期间推进 live master');
        assert.notEqual(adopt.status, 0, 'commit-point 必须拒绝 suite 期间发生的 live master 变化');
        assert.match(adopt.stdout + adopt.stderr, /commit-point revalidation failed.*live protected master tip/s);
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch'], root)
            .stdout.trim(), '2', '失败后必须保留 Epoch 2');
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root)
            .stdout.trim(), previousRoot, '失败后必须保留 Epoch 2 trusted source');
        assert.deepEqual(ticketKeys.map((key) =>
            git(['config', '--local', '--get', key], root).stdout.trim()), ticketBefore,
        '失败后必须保留完整 first-admission ticket 与 candidate seal');
        assert.notEqual(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root)
            .stdout.trim(), candidateSha, '失败后不得建立 Epoch 3 anchor');
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：adopt-root 测试套件失败 → 拒绝', () => {
    const root = makeRepo();
    try {
        // 放入一个必失败的测试文件并提交（bypass：工作树保持干净）→ suite 失败 → adopt-root 拒绝
        const testDir = path.join(root, 'scripts', 'i18n', 'test');
        fs.mkdirSync(testDir, { recursive: true });
        fs.writeFileSync(path.join(testDir, 'failing.test.mjs'),
            "import { test } from 'node:test';\nimport assert from 'node:assert/strict';\ntest('must fail', () => assert.equal(1, 2));\n", 'utf8');
        git(['add', '-A'], root);
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'add failing test'], root);
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.notEqual(adopt.status, 0, '测试套件失败时 adopt-root 必须拒绝\nSTDOUT: '
            + adopt.stdout + '\nSTDERR: ' + adopt.stderr);
        assert.match(adopt.stderr, /full i18n tests failed/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：advance 合法推进锚点；不自动发生；无 anchor 拒绝；旧 epoch anchor 拒绝', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeRepo();
    try {
        const c2 = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);

        // 正常提交 C3（经 pre-commit）→ 锚点不自动推进
        const jsDir = path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js');
        fs.mkdirSync(jsDir, { recursive: true });
        fs.writeFileSync(path.join(jsDir, 'x.js'), 'var x = 1;\n', 'utf8');
        git(['add', '-A'], root);
        const commit = git(['commit', '-q', '-m', 'normal commit'], root);
        assert.equal(commit.status, 0, commit.stdout + commit.stderr);
        const afterCommit = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        assert.equal(afterCommit, c2, '普通提交不得自动推进锚点');

        // advance --ref HEAD → 合法推进
        const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const advance = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.equal(advance.status, 0, advance.stdout + advance.stderr);
        const afterAdvance = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        assert.equal(afterAdvance, head, 'advance 必须推进到候选 SHA');
        assert.notEqual(afterAdvance, c2);
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch'], root).stdout.trim(), '3');
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：advance 无 anchor 拒绝；候选非 commit 拒绝', () => {
    const root = makeRepo();
    try {
        const noAnchor = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(noAnchor.status, 0, '无 anchor 必须提示 adopt-root');
        assert.match(noAnchor.stderr, /adopt-root/);

        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);
        const badRef = runCli(root, ['--advance', '--ref', 'HEAD^{tree}'], { clearCI: true });
        assert.notEqual(badRef.status, 0, '非 commit 候选必须拒绝');
        assert.match(badRef.stderr, /must resolve to a full commit/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：旧 epoch anchor 不迁移 —— advance 直接 OBSOLETE GATE EPOCH；adopt-root 明确人工命令成功', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeRepo(true, '1');
    try {
        // epoch 1 anchor：普通 advance 必须拒绝且提示 adopt-root
        const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
        fs.mkdirSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js'), { recursive: true });
        fs.writeFileSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 'x.js'),
            'var x = 1;\n', 'utf8');
        commitBypass(root, 'normal commit');
        const advance = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(advance.status, 0, 'epoch 1 anchor 的 advance 必须拒绝');
        assert.match(advance.stderr, /OBSOLETE GATE EPOCH/);
        assert.match(advance.stderr, /adopt-root/);
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch'], root).stdout.trim(), '1',
            '失败 advance 不得改动 anchor');

        // 显式人工 adopt-root（覆盖旧 epoch）→ 成功
        git(['config', '--local', '--unset', 'pixiv.i18n.trustedGateRef'], root);
        git(['config', '--local', '--unset', 'pixiv.i18n.trustedGateEpoch'], root);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch'], root).stdout.trim(), '3');
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim(), head);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：advance 单调推进 —— 向后 / sibling / 无共同历史拒绝；等于 current no-op；后代推进', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeRepo();
    try {
        const c2 = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);

        // 正常提交 C3（走本地 pre-commit，锚点不自动推进）
        const jsDir = path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js');
        fs.mkdirSync(jsDir, { recursive: true });
        fs.writeFileSync(path.join(jsDir, 'x.js'), 'var x = 1;\n', 'utf8');
        git(['add', '-A'], root);
        const commit = git(['commit', '-q', '-m', 'normal commit'], root);
        assert.equal(commit.status, 0, commit.stdout + commit.stderr);
        const c3 = git(['rev-parse', 'HEAD'], root).stdout.trim();

        // 向后推进：candidate = C1（current 的严格祖先）→ 拒绝
        const c1 = git(['rev-parse', 'HEAD~2'], root).stdout.trim();
        const backward = runCli(root, ['--advance', '--ref', c1], { clearCI: true });
        assert.notEqual(backward.status, 0, '向后推进必须拒绝');
        assert.match(backward.stderr, /not a descendant of the current anchor/);

        // 无共同历史：orphan 分支 → 拒绝
        const orphanDir = path.join(os.tmpdir(), 'pixiv orphan ' + Date.now() + '-' + Math.random().toString(36).slice(2));
        fs.mkdirSync(orphanDir, { recursive: true });
        git(['init', '-q', orphanDir], root);
        git(['config', 'user.email', 't@example.com'], orphanDir);
        git(['config', 'user.name', 'test'], orphanDir);
        fs.writeFileSync(path.join(orphanDir, 'orphan.txt'), 'orphan\n', 'utf8');
        git(['add', '-A'], orphanDir);
        git(['commit', '-q', '-m', 'orphan'], orphanDir);
        const orphanSha = git(['rev-parse', 'HEAD'], orphanDir).stdout.trim();
        git(['fetch', '-q', orphanDir, 'master'], root);
        const noHistory = runCli(root, ['--advance', '--ref', orphanSha], { clearCI: true });
        assert.notEqual(noHistory.status, 0, '无共同历史的推进必须拒绝');
        assert.match(noHistory.stderr, /not a descendant of the current anchor/);
        fs.rmSync(orphanDir, { recursive: true, force: true });

        // candidate == current → no-op（不报错）
        const currentAnchor = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        const equalRun = runCli(root, ['--advance', '--ref', currentAnchor], { clearCI: true });
        assert.equal(equalRun.status, 0, 'candidate == current 必须 no-op: ' + equalRun.stdout + equalRun.stderr);
        assert.match(equalRun.stdout, /nothing to do/);

        // 正常后代 C3 → 推进成功（锚点 = C3）
        const advance = runCli(root, ['--advance', '--ref', c3], { clearCI: true });
        assert.equal(advance.status, 0, advance.stdout + advance.stderr);
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim(), c3);

        // sibling：从 C2 分出分支提交 C3'（C3 不是 C3' 的祖先，反之亦然）→ 拒绝
        git(['checkout', '-q', '-b', 'sibling', c2], root);
        fs.mkdirSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js'), { recursive: true });
        fs.writeFileSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 's.js'),
            'var s = 2;\n', 'utf8');
        git(['add', '-A'], root);
        const siblingCommit = git(['commit', '-q', '-m', 'sibling commit'], root);
        assert.equal(siblingCommit.status, 0, siblingCommit.stdout + siblingCommit.stderr);
        const siblingSha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const sibling = runCli(root, ['--advance', '--ref', siblingSha], { clearCI: true });
        assert.notEqual(sibling.status, 0, 'sibling 推进必须拒绝');
        assert.match(sibling.stderr, /not a descendant of the current anchor/);
        // 失败推进不得改动锚点
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim(), c3);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：advance 删除 required file → 拒绝；no-op checker → 拒绝；no-op contract → 拒绝；policy 弱化 → 拒绝', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeRepo();
    try {
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);
        const anchor = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();

        // 1) 删除 required file
        git(['rm', '-q', 'scripts/i18n/check.mjs'], root);
        commitBypass(root, 'delete checker');
        const del = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(del.status, 0, '删除 required file 必须拒绝 advance');
        assert.match(del.stderr, /GATE CONTRACT FAILED|required gate files|incomplete/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 2) no-op checker
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'), EXIT_ZERO_CHECKER, 'utf8');
        commitBypass(root, 'noop checker');
        const noopChecker = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(noopChecker.status, 0, 'no-op checker 必须拒绝 advance');
        assert.match(noopChecker.stderr, /GATE CONTRACT FAILED/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 3) no-op contract（自保护）
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'gate-contract.mjs'), EXIT_ZERO_CHECKER, 'utf8');
        commitBypass(root, 'noop contract');
        const noopContract = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(noopContract.status, 0, 'no-op contract 必须拒绝 advance（不能保护下一次升级）');
        assert.match(noopContract.stderr, /GATE CONTRACT FAILED/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 4) policy 弱化：requiredPaths 减少
        const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');
        const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        policy.requiredPaths = policy.requiredPaths.filter((p) => p !== 'scripts/i18n/check.mjs');
        fs.writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n', 'utf8');
        commitBypass(root, 'weaken policy');
        const weaken = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(weaken.status, 0, 'requiredPaths 减少必须拒绝 advance');
        assert.match(weaken.stderr, /GATE CONTRACT FAILED|required paths/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 5) policy 弱化：enforcement start 后移（向后移动）
        const policy2 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        policy2.i18nEnforcementStartCommit = 'ffffffffffffffffffffffffffffffffffffffff';
        fs.writeFileSync(policyPath, JSON.stringify(policy2, null, 2) + '\n', 'utf8');
        commitBypass(root, 'move enforcement start');
        const moved = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(moved.status, 0, 'enforcement start 后移必须拒绝 advance');
        assert.match(moved.stderr, /GATE CONTRACT FAILED|enforcement/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 6) policy 弱化：gateEpoch 改变（3 → 4，未来 epoch）
        const policy3 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        policy3.gateEpoch = 4;
        fs.writeFileSync(policyPath, JSON.stringify(policy3, null, 2) + '\n', 'utf8');
        commitBypass(root, 'change gate epoch');
        const epochChanged = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(epochChanged.status, 0, 'gateEpoch 改变必须拒绝 advance');
        assert.match(epochChanged.stderr, /GATE CONTRACT FAILED|epoch/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 锚点未被任何失败 advance 改动
        const still = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        assert.equal(still, anchor, '失败 advance 不得修改锚点');
    } finally {
        cleanRepo(root);
    }
});
