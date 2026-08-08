'use strict';
/**
 * trust-gate CLI 测试（Gate Epoch 2）：--show / --adopt-root / --advance。
 * - adopt-root 是人工 TOFU / root admission：只接受完整 commit、干净状态、
 *   完整 i18n tests、ref snapshot check、signature guard、root contract self-test
 *   （--force-self-protection）、gate parity（--invariants）、required files，CI 禁止；
 *   全部通过才写 epoch == 2 + ref；
 * - advance 由 trusted Epoch 2 contract 审核候选：no-op checker / no-op contract /
 *   删除 required file / 弱化 policy（含 epoch 改变）/ 门禁减少一律拒绝；不自动发生；
 * - 旧 epoch anchor 不迁移：advance 直接 OBSOLETE GATE EPOCH 拒绝。
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
const CLI = path.join(SCRIPTS_DIR, 'trust-gate.mjs');

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

/** 与 hooks 测试同构的夹具；withAnchor=false 时不写 trusted anchor（供 CLI 自行建立）。 */
function makeRepo(withAnchor = false, anchorEpoch = '2') {
    const dir = path.join(os.tmpdir(), 'pixiv trust repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);
    // 与真实仓库一致：build/ 忽略（checker/contract 的报告目录）
    fs.writeFileSync(path.join(dir, '.gitignore'), 'build/\nnode_modules/\n', 'utf8');
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'), { force: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'hooks'), path.join(dir, 'scripts', 'hooks'), { recursive: true });
    // adopt-root 要求 policy 的 required paths 全部存在：夹具必须携带完整 gate bundle
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
    git(['commit', '-q', '-m', 'init'], dir); // C1
    const start = git(['rev-parse', 'HEAD'], dir).stdout.trim();
    const policy = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
    policy.i18nEnforcementStartCommit = start;
    fs.writeFileSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'),
        JSON.stringify(policy, null, 2) + '\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'add gate policy'], dir); // C2
    git(['config', '--local', 'core.hooksPath', 'scripts/hooks'], dir);
    if (withAnchor) {
        const anchor = git(['rev-parse', 'HEAD'], dir).stdout.trim();
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

function rewriteFixtureForNextEpoch(root) {
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
                let content = fs.readFileSync(file, 'utf8');
                content = content
                    .replaceAll('i18n-gate-epoch-2-root', 'i18n-gate-epoch-3-root')
                    .replace('export const CURRENT_GATE_EPOCH = 2;', 'export const CURRENT_GATE_EPOCH = 3;')
                    .replaceAll('"gateEpoch": 2', '"gateEpoch": 3');
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
        assert.match(show.stdout, /--adopt-root --ref HEAD --epoch 2/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：adopt-root 写入 epoch 2 + ref；--show 输出 SHA 与 contract version', () => {
    const root = makeRepo();
    try {
        const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);
        assert.match(adopt.stdout + adopt.stderr, /ROOT ADMISSION/);
        assert.match(adopt.stdout + adopt.stderr, /Gate Epoch 2 root adopted/);
        const configured = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        assert.equal(configured, head, 'adopt-root 必须写入当前 HEAD 的完整 SHA');
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch'], root).stdout.trim(), '2');

        const show = runCli(root, ['--show']);
        assert.equal(show.status, 0, show.stdout + show.stderr);
        assert.match(show.stdout, new RegExp('trustedGateRef: ' + head));
        assert.match(show.stdout, /trustedGateEpoch: 2/);
        assert.match(show.stdout, /contractVersion: 4/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：adopt-root 只写 local 配置（不写 global）', () => {
    const root = makeRepo();
    try {
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);
        const origin = git(['config', '--show-origin', '--get', 'pixiv.i18n.trustedGateRef'], root, { allowFailure: true });
        assert.match(origin.stdout, /\.git[/\\]config/);
        const global = git(['config', '--global', '--get', 'pixiv.i18n.trustedGateRef'], root, { allowFailure: true });
        assert.notEqual(global.status, 0, 'global 配置不得写入');
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：CI 环境禁止 prepare-root / adopt-root / advance', () => {
    const root = makeRepo();
    try {
        const prepare = runCli(root, ['--prepare-root', '--epoch', '2'], { CI: 'true' });
        assert.notEqual(prepare.status, 0, 'CI=true 必须拒绝 prepare-root');
        assert.match(prepare.stderr, /forbidden in CI/);
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { CI: 'true' });
        assert.notEqual(adopt.status, 0, 'CI=true 必须拒绝 adopt-root');
        assert.match(adopt.stderr, /forbidden in CI/);
        const configured = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root, { allowFailure: true });
        assert.notEqual(configured.status, 0, 'CI 拒绝后不得写入配置');

        const adoptOk = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
        assert.equal(adoptOk.status, 0, adoptOk.stdout + adoptOk.stderr);
        const advance = runCli(root, ['--advance', '--ref', 'HEAD'], { CI: 'true' });
        assert.notEqual(advance.status, 0, 'CI=true 必须拒绝 advance');
        assert.match(advance.stderr, /forbidden in CI/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：adopt-root 只接受完整 commit + epoch 2（拒绝工作树路径 / 非 commit / 非 2 epoch）', () => {
    const root = makeRepo();
    try {
        for (const bad of ['HEAD^{tree}', './scripts', 'README.md', 'not-a-ref']) {
            const adopt = runCli(root, ['--adopt-root', '--ref', bad, '--epoch', '2'], { clearCI: true });
            assert.notEqual(adopt.status, 0, '必须拒绝: ' + bad);
            assert.match(adopt.stderr, /must resolve to a full commit/);
        }
        for (const badEpoch of ['1', '3', 'x']) {
            const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', badEpoch], { clearCI: true });
            assert.notEqual(adopt.status, 0, 'epoch ' + badEpoch + ' 必须拒绝');
            assert.match(adopt.stderr, /--epoch must be exactly 2/);
        }
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：adopt-root 脏工作树 / 已存在 anchor 拒绝', () => {
    const root = makeRepo();
    try {
        fs.writeFileSync(path.join(root, 'dirty.txt'), 'dirty\n', 'utf8');
        const refused = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
        assert.notEqual(refused.status, 0, '脏工作树必须拒绝');
        assert.match(refused.stderr, /worktree is not clean/);
        fs.rmSync(path.join(root, 'dirty.txt'));

        const ok = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
        assert.equal(ok.status, 0, ok.stdout + ok.stderr);
        const again = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
        assert.notEqual(again.status, 0, '已有 anchor 必须拒绝再次 adopt-root');
        assert.match(again.stderr, /already exists/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：prepare-root 精确绑定 staged tree + parent，提交后才允许采用下一 Epoch root', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeRepo(true);
    try {
        const previousRoot = git(['rev-parse', 'HEAD'], root).stdout.trim();
        git(['tag', 'i18n-gate-epoch-2-root', previousRoot], root);
        rewriteFixtureForNextEpoch(root);
        git(['add', '-A'], root);

        const prepare = runRepoCli(root, ['--prepare-root', '--epoch', '3'], { clearCI: true });
        assert.equal(prepare.status, 0, prepare.stdout + prepare.stderr);
        assert.match(prepare.stdout + prepare.stderr, /Gate Epoch 3 root prepared/);
        const preparedTree = git(['config', '--local', '--get', 'pixiv.i18n.preparedRootTree'], root)
            .stdout.trim();
        assert.equal(preparedTree, git(['write-tree'], root).stdout.trim());

        const commit = git(['commit', '-q', '-m', 'epoch 3 root'], root);
        assert.equal(commit.status, 0, commit.stdout + commit.stderr);
        const adopt = runRepoCli(root,
            ['--adopt-root', '--ref', 'HEAD', '--epoch', '3'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch'], root)
            .stdout.trim(), '3');
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root)
            .stdout.trim(), git(['rev-parse', 'HEAD'], root).stdout.trim());
        assert.notEqual(git(['config', '--local', '--get', 'pixiv.i18n.preparedRootTree'], root,
            { allowFailure: true }).status, 0, 'adopt-root 成功后必须清除 preparation ticket');
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
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
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
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
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
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch'], root).stdout.trim(), '2');
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

        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
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
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
        assert.equal(adopt.status, 0, adopt.stdout + adopt.stderr);
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch'], root).stdout.trim(), '2');
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
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
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
        const adopt = runCli(root, ['--adopt-root', '--ref', 'HEAD', '--epoch', '2'], { clearCI: true });
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

        // 6) policy 弱化：gateEpoch 改变（2 → 3，未来 epoch）
        const policy3 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        policy3.gateEpoch = 3;
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
