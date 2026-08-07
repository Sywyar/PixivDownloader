'use strict';
/**
 * Gate Monotonicity 审计测试（Epoch 2 门禁不可减少）：
 * 程序化构造 21 种 downgrade mutation，全部必须被 gate-parity 拒绝。
 * 不是「当前 workflow 看起来正确」，而是「候选试图降低要求时一定失败」。
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

/** 完整候选仓库：C1（enforcement start，完整 gate bundle 无 policy）+ C2（真实 Epoch 2 policy）。 */
function makeFullCandidateRepo() {
    const dir = path.join(os.tmpdir(), 'pixiv mono repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
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
    git(['config', '--local', 'pixiv.i18n.trustedGateEpoch', '2'], dir);
    git(['config', '--local', 'pixiv.i18n.trustedGateRef', git(['rev-parse', 'HEAD'], dir).stdout.trim()], dir);
    return dir;
}

/** trusted copy：真实 scripts/i18n + hooks + scripts/ci + workflow + package.json，policy 指向夹具。 */
function makeTrustedCopy(fixtureRoot) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv mono trusted-'));
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

function runParity(trustedCopy, repoRoot, args) {
    return spawnSync('node', [path.join(trustedCopy, 'scripts', 'ci', 'gate-parity.mjs'), ...args],
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

/**
 * downgrade mutation 矩阵：每个 mutation 先改 workflow / policy / package.json，
 * 提交后运行 gate-parity（trusted vs candidate），必须非零退出。
 */
const MUTATIONS = [
    {
        name: '删除 Java job',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            delete doc.jobs['java-tests'];
            await writeWorkflow(root, doc);
        },
    },
    {
        name: 'Java mvn test → mvn compile（缩小测试范围）',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            const step = doc.jobs['java-tests'].steps.find((s) => typeof s.run === 'string'
                && s.run.includes('mvn -B -ntp test'));
            step.run = 'mvn -B -ntp -pl pixivdownload-official-plugins -am compile -Dexec.skip=true';
            await writeWorkflow(root, doc);
        },
    },
    {
        name: 'Java test 加 -DskipTests',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            const step = doc.jobs['java-tests'].steps.find((s) => typeof s.run === 'string'
                && s.run.includes('mvn -B -ntp test'));
            step.run = 'mvn -B -ntp test -DskipTests -Dexec.skip=true -Duser.language=en -Duser.country=US';
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 JS tests',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            doc.jobs['javascript-tests'].steps = doc.jobs['javascript-tests'].steps
                .filter((s) => !(typeof s.run === 'string' && s.run.includes('npm run test:js')));
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 Web Standards',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            doc.jobs['javascript-tests'].steps = doc.jobs['javascript-tests'].steps
                .filter((s) => !(typeof s.run === 'string' && s.run.includes('test:web-standards')));
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 signature guard',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            doc.jobs['signature-guard'].steps = doc.jobs['signature-guard'].steps
                .filter((s) => !(typeof s.run === 'string' && s.run.includes('pre-push-guard.sh')));
            await writeWorkflow(root, doc);
        },
    },
    {
        name: 'trusted guard → candidate guard（无 GATE_DIR）',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            const step = doc.jobs['signature-guard'].steps.find((s) => typeof s.run === 'string'
                && s.run.includes('pre-push-guard.sh'));
            step.run = 'bash scripts/hooks/pre-push-guard.sh --repo-root "$PWD" --ref "${{ github.sha }}"';
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 i18n tests',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            doc.jobs['i18n-check'].steps = doc.jobs['i18n-check'].steps
                .filter((s) => !(typeof s.run === 'string' && s.run.includes('npm run test:i18n')));
            await writeWorkflow(root, doc);
        },
    },
    {
        name: 'CI=true → CI=false',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            const step = doc.jobs['i18n-check'].steps.find((s) => typeof s.run === 'string'
                && s.run.includes('npm run test:i18n'));
            step.env = { ...(step.env || {}), CI: 'false' };
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 ref check',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            doc.jobs['i18n-check'].steps = doc.jobs['i18n-check'].steps
                .filter((s) => !(typeof s.run === 'string' && s.run.includes('--snapshot ref')));
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 worktree check',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            doc.jobs['i18n-check'].steps = doc.jobs['i18n-check'].steps
                .filter((s) => !(typeof s.run === 'string' && s.run.includes('npm run i18n:check')));
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 static generation',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            doc.jobs['i18n-check'].steps = doc.jobs['i18n-check'].steps
                .filter((s) => !(typeof s.run === 'string' && s.run.includes('i18n:generate-static')));
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 static diff',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            doc.jobs['i18n-check'].steps = doc.jobs['i18n-check'].steps
                .filter((s) => !(typeof s.run === 'string' && s.run.includes('git diff --exit-code')));
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 report upload',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            for (const jobId of ['trusted-gate-contract', 'i18n-check']) {
                doc.jobs[jobId].steps = doc.jobs[jobId].steps
                    .filter((s) => !(typeof s.uses === 'string' && s.uses.includes('actions/upload-artifact')));
            }
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 final propagation',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            doc.jobs['i18n-check'].steps = doc.jobs['i18n-check'].steps
                .filter((s) => !(typeof s.run === 'string' && /outcome|check_outcome/.test(s.run)));
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 Shared Snippet workflow 声明（requiredExternalChecks）',
        mutate: async (root) => {
            const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');
            const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
            policy.requiredExternalChecks = [];
            fs.writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n', 'utf8');
        },
    },
    {
        name: '删除 required path',
        mutate: async (root) => {
            const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');
            const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
            policy.requiredPaths = policy.requiredPaths.filter((p) => p !== 'scripts/i18n/check.mjs');
            fs.writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n', 'utf8');
        },
    },
    {
        name: '删除 protected master',
        mutate: async (root) => {
            const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');
            const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
            policy.protectedBranches = policy.protectedBranches.filter((r) => r !== 'refs/heads/master');
            fs.writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n', 'utf8');
        },
    },
    {
        name: '删除 workflow trigger',
        mutate: async (root) => {
            const doc = await readWorkflow(root);
            delete doc.on.push;
            await writeWorkflow(root, doc);
        },
    },
    {
        name: '删除 package script（i18n:gate-parity → 真值删除）',
        mutate: async (root) => {
            const pkgPath = path.join(root, 'package.json');
            const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
            delete pkg.scripts['i18n:gate-parity'];
            fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n', 'utf8');
        },
    },
    {
        name: '降低 contractVersion',
        mutate: async (root) => {
            const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');
            const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
            policy.contractVersion = 1;
            fs.writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n', 'utf8');
        },
    },
    {
        name: '改变 gateEpoch',
        mutate: async (root) => {
            const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');
            const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
            policy.gateEpoch = 3;
            fs.writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n', 'utf8');
        },
    },
    {
        name: 'shared-snippets-check.yml 关键命令改 true',
        mutate: async (root) => {
            const file = path.join(root, '.github', 'workflows', 'shared-snippets-check.yml');
            const doc = YAML.parse(fs.readFileSync(file, 'utf8'));
            const step = doc.jobs['check-shared-snippets'].steps.find((s) => typeof s.run === 'string'
                && s.run.includes('sync-shared-snippets.ps1'));
            step.run = 'true';
            fs.writeFileSync(file, YAML.stringify(doc), 'utf8');
        },
    },
    {
        name: 'shared-snippets-check.yml 删除 check-shared-snippets job',
        mutate: async (root) => {
            const file = path.join(root, '.github', 'workflows', 'shared-snippets-check.yml');
            const doc = YAML.parse(fs.readFileSync(file, 'utf8'));
            delete doc.jobs['check-shared-snippets'];
            fs.writeFileSync(file, YAML.stringify(doc), 'utf8');
        },
    },
    {
        name: 'release.yml 删除 draft-quality-gate',
        mutate: async (root) => {
            const file = path.join(root, '.github', 'workflows', 'release.yml');
            const doc = YAML.parse(fs.readFileSync(file, 'utf8'));
            delete doc.jobs['draft-quality-gate'];
            fs.writeFileSync(file, YAML.stringify(doc), 'utf8');
        },
    },
    {
        name: 'release.yml build-jar 去掉 needs publish-plugins',
        mutate: async (root) => {
            const file = path.join(root, '.github', 'workflows', 'release.yml');
            const doc = YAML.parse(fs.readFileSync(file, 'utf8'));
            const needs = Array.isArray(doc.jobs['build-jar'].needs) ? doc.jobs['build-jar'].needs : [doc.jobs['build-jar'].needs];
            doc.jobs['build-jar'].needs = needs.filter((n) => n !== 'publish-plugins');
            fs.writeFileSync(file, YAML.stringify(doc), 'utf8');
        },
    },
    {
        name: 'publish-plugins.yml publish if 改 always()',
        mutate: async (root) => {
            const file = path.join(root, '.github', 'workflows', 'publish-plugins.yml');
            const doc = YAML.parse(fs.readFileSync(file, 'utf8'));
            doc.jobs['publish'].if = '${{ always() }}';
            fs.writeFileSync(file, YAML.stringify(doc), 'utf8');
        },
    },
    {
        name: 'publish-plugins.yml 删除 quality-gate job',
        mutate: async (root) => {
            const file = path.join(root, '.github', 'workflows', 'publish-plugins.yml');
            const doc = YAML.parse(fs.readFileSync(file, 'utf8'));
            delete doc.jobs['quality-gate'];
            fs.writeFileSync(file, YAML.stringify(doc), 'utf8');
        },
    },
    {
        name: 'nightly.yml build-jar 去掉 needs publish-plugins',
        mutate: async (root) => {
            const file = path.join(root, '.github', 'workflows', 'nightly.yml');
            const doc = YAML.parse(fs.readFileSync(file, 'utf8'));
            doc.jobs['build-jar'].needs = doc.jobs['build-jar'].needs.filter((n) => n !== 'publish-plugins');
            fs.writeFileSync(file, YAML.stringify(doc), 'utf8');
        },
    },
    {
        name: 'gate-surface.json 删除 trusted 条目',
        mutate: async (root) => {
            const file = path.join(root, 'scripts', 'ci', 'gate-surface.json');
            const surface = JSON.parse(fs.readFileSync(file, 'utf8'));
            surface.paths = surface.paths.filter((p) => p !== 'scripts/ci');
            fs.writeFileSync(file, JSON.stringify(surface, null, 2) + '\n', 'utf8');
        },
    },
    {
        name: 'sync-shared-snippets.ps1 = exit 0（checker 本体弱化）',
        mutate: async (root) => {
            fs.writeFileSync(path.join(root, 'scripts', 'sync-shared-snippets.ps1'), 'exit 0\n', 'utf8');
        },
    },
    {
        name: 'resolve-trusted-base.mjs 删除 base-ancestor 检查',
        mutate: async (root) => {
            const file = path.join(root, 'scripts', 'ci', 'resolve-trusted-base.mjs');
            const text = fs.readFileSync(file, 'utf8');
            fs.writeFileSync(file, text.replace('if (!isAncestor(repoRoot, base, candidate)) {',
                'if (false) {'), 'utf8');
        },
    },
];

for (const mutation of MUTATIONS) {
    test('downgrade mutation 必须失败: ' + mutation.name, async () => {
        const root = makeFullCandidateRepo();
        const trusted = makeTrustedCopy(root);
        try {
            await mutation.mutate(root);
            commitBypass(root, 'mutation: ' + mutation.name);
            const sha = git(['rev-parse', 'HEAD'], root).stdout.trim();
            const run = runParity(trusted, root,
                ['--repo-root', root, '--trusted-dir', trusted, '--candidate-ref', sha]);
            assert.notEqual(run.status, 0,
                'mutation 必须被 gate parity 拒绝: ' + mutation.name + '\nSTDOUT: ' + run.stdout
                + '\nSTDERR: ' + run.stderr);
            assert.match(run.stdout + run.stderr, /GATE PARITY FAILED/);
        } finally {
            cleanRepo(root);
            fs.rmSync(trusted, { recursive: true, force: true });
        }
    });
}
