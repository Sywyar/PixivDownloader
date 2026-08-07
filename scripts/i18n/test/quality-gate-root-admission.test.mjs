'use strict';
/**
 * Gate Epoch 2 root admission + reusable input 优先级测试：
 * - §35：direct push / PR / merge group / reusable（push + 显式 input）/
 *   workflow_dispatch + 显式 input / 恶意 input（== candidate / 不含 root）→ 拒绝；
 * - §36：root tag 缺失 → fail；缺失 + 显式 dispatch root_admission → ROOT_ADMISSION；
 *   candidate == root → ROOT_ADMISSION；candidate 是 root 后代 → NORMAL；
 *   candidate 与 root 无关 → fail；candidate 是 root 祖先 → fail；
 *   root tag 指向缺 contract 的提交 → 物化 / contract fail closed。
 * 全部使用临时 repo + 临时 tag（i18n-gate-epoch-2-root），绝不持久创建真实 tag。
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

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');
const RESOLVER = path.join(REPO_ROOT, 'scripts', 'ci', 'resolve-trusted-base.mjs');

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

/** 夹具：C1（enforcement start，完整 gate bundle 无 policy）+ C2（Epoch 2 policy）。 */
function makeRepo() {
    const dir = path.join(os.tmpdir(), 'pixiv root repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
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
    fs.copyFileSync(path.join(REPO_ROOT, '.github', 'workflows', 'quality-gate.yml'),
        path.join(dir, '.github', 'workflows', 'quality-gate.yml'));
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
    git(['config', '--local', 'pixiv.i18n.trustedGateEpoch', '2'], dir);
    git(['config', '--local', 'pixiv.i18n.trustedGateRef', git(['rev-parse', 'HEAD'], dir).stdout.trim()], dir);
    return dir;
}

function commitBypass(root, message) {
    git(['add', '-A'], root);
    git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', message], root);
}

function runResolver(root, args) {
    return spawnSync('node', [RESOLVER, '--repo-root', root, ...args],
        { cwd: root, encoding: 'utf8' });
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

test('root admission：tag 缺失 → fail closed；显式 dispatch root_admission=true + root_candidate_sha==candidate → ROOT_ADMISSION', () => {
    const root = makeRepo();
    try {
        const candidate = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const common = ['--candidate', candidate, '--before', '0000000000000000000000000000000000000000',
            '--default-branch', 'master', '--mode'];

        // 普通 event 无 tag → fail
        const noTag = runResolver(root, [...common, '--event-name', 'push']);
        assert.notEqual(noTag.status, 0, 'tag 缺失且非显式 admission 必须 fail closed');
        assert.match(noTag.stderr, /Gate Epoch 2 trust root has not been installed/);

        // workflow_dispatch + root_admission=true + sha 匹配 → ROOT_ADMISSION（base == candidate）
        const admit = runResolver(root, [...common, '--event-name', 'workflow_dispatch',
            '--root-admission', 'true', '--root-candidate-sha', candidate]);
        assert.equal(admit.status, 0, admit.stdout + admit.stderr);
        const j = JSON.parse(admit.stdout);
        assert.equal(j.mode, 'ROOT_ADMISSION');
        assert.equal(j.base, candidate);
        assert.equal(j.root, candidate);

        // workflow_dispatch + root_admission=true + sha 不匹配 → fail
        const mismatch = runResolver(root, [...common, '--event-name', 'workflow_dispatch',
            '--root-admission', 'true', '--root-candidate-sha', '1111111111111111111111111111111111111111']);
        assert.notEqual(mismatch.status, 0, 'root_candidate_sha 与 candidate 不一致必须 fail closed');

        // 普通 push 即使带 root_admission input 也不进入（只有 workflow_dispatch 允许人工触发）
        const pushAdmit = runResolver(root, [...common, '--event-name', 'push',
            '--root-admission', 'true']);
        assert.notEqual(pushAdmit.status, 0, '非 workflow_dispatch 不得自行进入 ROOT_ADMISSION');
    } finally {
        cleanRepo(root);
    }
});

test('root admission：candidate == root → ROOT_ADMISSION；root 后代 → NORMAL；祖先 / 无关 → fail', () => {
    const root = makeRepo();
    try {
        const c2 = git(['rev-parse', 'HEAD'], root).stdout.trim();
        // 临时 tag 指向 C2（fixture 内创建，绝不持久）
        git(['tag', 'i18n-gate-epoch-2-root', c2], root);
        const before = '0000000000000000000000000000000000000000';

        // candidate == root → ROOT_ADMISSION（即使 event=push）
        const same = runResolver(root, ['--event-name', 'push', '--candidate', c2, '--before', before,
            '--default-branch', 'master', '--mode']);
        assert.equal(same.status, 0, same.stdout + same.stderr);
        assert.equal(JSON.parse(same.stdout).mode, 'ROOT_ADMISSION');

        // root 后代（正常 commit）→ NORMAL（push before == root → base == root）
        fs.mkdirSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js'), { recursive: true });
        fs.writeFileSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 'x.js'),
            'var x = 1;\n', 'utf8');
        commitBypass(root, 'normal commit');
        const c3 = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const normal = runResolver(root, ['--event-name', 'push', '--candidate', c3, '--before', c2,
            '--default-branch', 'master', '--mode']);
        assert.equal(normal.status, 0, normal.stdout + normal.stderr);
        const nj = JSON.parse(normal.stdout);
        assert.equal(nj.mode, 'NORMAL');
        assert.equal(nj.base, c2);

        // candidate 是 root 的祖先 → fail
        const ancestor = runResolver(root, ['--event-name', 'push', '--candidate', c2 + '^', '--before', c2,
            '--default-branch', 'master', '--mode']);
        assert.notEqual(ancestor.status, 0, 'candidate 是 root 祖先必须 fail closed');
        assert.match(ancestor.stderr, /does not descend from the Gate Epoch 2 trust root/);

        // 无关历史（orphan）→ fail
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
        const unrelated = runResolver(root, ['--event-name', 'push', '--candidate', orphanSha, '--before', before,
            '--default-branch', 'master', '--mode']);
        assert.notEqual(unrelated.status, 0, '与 root 无关的 candidate 必须 fail closed');
        assert.match(unrelated.stderr, /does not descend from the Gate Epoch 2 trust root/);
        fs.rmSync(orphanDir, { recursive: true, force: true });

        git(['tag', '-d', 'i18n-gate-epoch-2-root'], root, { allowFailure: true });
    } finally {
        cleanRepo(root);
    }
});

test('reusable input 优先级：显式 trusted_base_sha 优先于 event；input == candidate / 不含 root → 拒绝', () => {
    const root = makeRepo();
    try {
        const c2 = git(['rev-parse', 'HEAD'], root).stdout.trim();
        git(['tag', 'i18n-gate-epoch-2-root', c2], root);
        fs.mkdirSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js'), { recursive: true });
        fs.writeFileSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 'x.js'),
            'var x = 1;\n', 'utf8');
        commitBypass(root, 'normal commit');
        const c3 = git(['rev-parse', 'HEAD'], root).stdout.trim();

        // event=push + 显式 input → input 优先（即使 before 不同）
        const reusable = runResolver(root, ['--event-name', 'push', '--candidate', c3, '--before', c2,
            '--input-base', c2, '--default-branch', 'master', '--mode']);
        assert.equal(reusable.status, 0, reusable.stdout + reusable.stderr);
        assert.equal(JSON.parse(reusable.stdout).base, c2, '显式 input 必须优先于 event.before');

        // event=workflow_dispatch + 显式 input → input 优先
        const dispatch = runResolver(root, ['--event-name', 'workflow_dispatch', '--candidate', c3,
            '--input-base', c2, '--default-branch', 'master', '--mode']);
        assert.equal(dispatch.status, 0, dispatch.stdout + dispatch.stderr);
        assert.equal(JSON.parse(dispatch.stdout).base, c2);

        // 恶意 input == candidate → 拒绝
        const selfInput = runResolver(root, ['--event-name', 'workflow_dispatch', '--candidate', c3,
            '--input-base', c3, '--default-branch', 'master', '--mode']);
        assert.notEqual(selfInput.status, 0, 'input == candidate 必须拒绝');
        assert.match(selfInput.stderr, /equals the candidate/);

        // 恶意 input 不含 root（root 的祖先 = Epoch 1 历史）→ 拒绝
        const c1 = git(['rev-parse', 'HEAD~2'], root).stdout.trim();
        const badInput = runResolver(root, ['--event-name', 'workflow_dispatch', '--candidate', c3,
            '--input-base', c1, '--default-branch', 'master', '--mode']);
        assert.notEqual(badInput.status, 0, 'input 不含 Epoch 2 root 必须拒绝');
        assert.match(badInput.stderr, /does not descend from the Gate Epoch 2 trust root/);

        // event=push + 空 input → event.before
        const beforePush = runResolver(root, ['--event-name', 'push', '--candidate', c3, '--before', c2,
            '--input-base', '', '--default-branch', 'master', '--mode']);
        assert.equal(beforePush.status, 0, beforePush.stdout + beforePush.stderr);
        assert.equal(JSON.parse(beforePush.stdout).base, c2);

        // event=pull_request + 空 input → pr base
        const pr = runResolver(root, ['--event-name', 'pull_request', '--candidate', c3,
            '--pr-base', c2, '--input-base', '', '--default-branch', 'master', '--mode']);
        assert.equal(pr.status, 0, pr.stdout + pr.stderr);
        assert.equal(JSON.parse(pr.stdout).base, c2);

        // event=merge_group + 空 input → merge group base
        const mg = runResolver(root, ['--event-name', 'merge_group', '--candidate', c3,
            '--merge-group-base', c2, '--input-base', '', '--default-branch', 'master', '--mode']);
        assert.equal(mg.status, 0, mg.stdout + mg.stderr);
        assert.equal(JSON.parse(mg.stdout).base, c2);

        // workflow_call 语义（event 是调用方原始 event）+ 空 input → 无法解析 → fail closed
        const noInput = runResolver(root, ['--event-name', 'schedule', '--candidate', c3,
            '--input-base', '', '--default-branch', 'master', '--mode']);
        assert.notEqual(noInput.status, 0, '无法解析 base 必须 fail closed');
        assert.match(noInput.stderr, /fail closed/);

        git(['tag', '-d', 'i18n-gate-epoch-2-root'], root, { allowFailure: true });
    } finally {
        cleanRepo(root);
    }
});

test('root admission：tag 指向缺 gate-contract.mjs 的提交 → materialize / contract fail closed', () => {
    const root = makeRepo();
    try {
        const c2 = git(['rev-parse', 'HEAD'], root).stdout.trim();
        // 构造缺 contract 的提交并作为临时 root tag
        git(['rm', '-q', 'scripts/i18n/gate-contract.mjs'], root);
        commitBypass(root, 'drop contract');
        const broken = git(['rev-parse', 'HEAD'], root).stdout.trim();
        git(['tag', 'i18n-gate-epoch-2-root', broken], root);

        // candidate == root（缺 contract）→ ROOT_ADMISSION 模式；随后物化必须失败
        const same = runResolver(root, ['--event-name', 'push', '--candidate', broken,
            '--before', '0000000000000000000000000000000000000000', '--default-branch', 'master', '--mode']);
        assert.equal(same.status, 0, same.stdout + same.stderr);
        assert.equal(JSON.parse(same.stdout).mode, 'ROOT_ADMISSION');

        // 物化（等价 workflow 的 test -f gate-contract.mjs 守卫）→ fail closed
        const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv root mat-'));
        try {
            const pathsFile = path.join(tmp, 'paths.txt');
            const index = path.join(tmp, 'index');
            const out = path.join(tmp, 'out');
            const list = spawnSync('git', ['ls-tree', '-r', '--name-only', broken, '--',
                'scripts/i18n', 'scripts/hooks', 'scripts/ci', '.github/workflows/quality-gate.yml',
                'package.json'], { cwd: root, encoding: 'utf8' });
            fs.writeFileSync(pathsFile, list.stdout, 'utf8');
            fs.mkdirSync(out, { recursive: true });
            spawnSync('git', ['read-tree', broken], { cwd: root, encoding: 'utf8',
                env: { ...process.env, GIT_INDEX_FILE: index } });
            spawnSync('git', ['-c', 'core.autocrlf=false', 'checkout-index', '--stdin', '--prefix=' + out + '/'],
                { cwd: root, encoding: 'utf8', env: { ...process.env, GIT_INDEX_FILE: index },
                    input: fs.readFileSync(pathsFile, 'utf8') });
            const contractExists = fs.existsSync(path.join(out, 'scripts', 'i18n', 'gate-contract.mjs'));
            assert.equal(contractExists, false, 'root 提交缺 contract');
        } finally {
            fs.rmSync(tmp, { recursive: true, force: true });
        }

        // contract 运行（candidate == root）→ required path 缺失 → fail closed
        const trustedCopy = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv root trusted-'));
        try {
            fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(trustedCopy, 'scripts', 'i18n'),
                { recursive: true });
            fs.rmSync(path.join(trustedCopy, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
            const policy = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
            policy.i18nEnforcementStartCommit = c2;
            fs.writeFileSync(path.join(trustedCopy, 'scripts', 'i18n', 'gate-policy.json'),
                JSON.stringify(policy, null, 2) + '\n', 'utf8');
            const contractRun = spawnSync('node', [path.join(trustedCopy, 'scripts', 'i18n', 'gate-contract.mjs'),
                '--repo-root', root, '--candidate-ref', broken], { cwd: root, encoding: 'utf8',
                maxBuffer: 128 * 1024 * 1024 });
            assert.notEqual(contractRun.status, 0, '缺 contract 的 root 必须被 contract fail closed');
        } finally {
            fs.rmSync(trustedCopy, { recursive: true, force: true });
        }

        git(['tag', '-d', 'i18n-gate-epoch-2-root'], root, { allowFailure: true });
    } finally {
        cleanRepo(root);
    }
});
