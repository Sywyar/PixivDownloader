'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const VERIFIER = path.join(ROOT, 'scripts', 'ci', 'release-gate-verifier.mjs');
const POLICY_REL = 'scripts/ci/release-gate-policy.json';

function git(root, args) {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
    if (result.status !== 0) throw new Error(result.stderr || result.stdout);
    return result.stdout.trim();
}

function copy(root, rel) {
    const target = path.join(root, ...rel.split('/'));
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(ROOT, ...rel.split('/')), target);
}

function commit(root, message) {
    git(root, ['add', '-A']);
    git(root, ['commit', '-q', '-m', message]);
    return git(root, ['rev-parse', 'HEAD']);
}

function fixture() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv release gate v5 '));
    git(root, ['init', '-q']);
    git(root, ['config', 'user.email', 'test@example.com']);
    git(root, ['config', 'user.name', 'test']);
    copy(root, POLICY_REL);
    const policy = JSON.parse(fs.readFileSync(path.join(ROOT, POLICY_REL), 'utf8'));
    for (const rel of policy.protectedCore) copy(root, rel);
    for (const rel of fs.readdirSync(path.join(ROOT, '.github', 'workflows'))
        .filter((name) => /\.ya?ml$/.test(name)).map((name) => `.github/workflows/${name}`)) {
        copy(root, rel);
    }
    copy(root, 'package.json');
    try {
        fs.symlinkSync(path.join(ROOT, 'node_modules'), path.join(root, 'node_modules'),
            process.platform === 'win32' ? 'junction' : 'dir');
    } catch {
        fs.cpSync(path.join(ROOT, 'node_modules'), path.join(root, 'node_modules'), { recursive: true });
    }
    const base = commit(root, 'trusted root');
    git(root, ['tag', 'release-gate-epoch-5-root', base]);
    git(root, ['update-ref', 'refs/remotes/origin/master', base]);
    return { root, base };
}

function verify(root, candidate, trusted, extra = []) {
    return spawnSync(process.execPath, [VERIFIER, '--repo-root', root,
        '--candidate-ref', candidate, '--trusted-ref', trusted, ...extra], {
        cwd: root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024,
    });
}

function mutatePolicy(root, mutate) {
    const file = path.join(root, ...POLICY_REL.split('/'));
    const policy = JSON.parse(fs.readFileSync(file, 'utf8'));
    mutate(policy);
    fs.writeFileSync(file, JSON.stringify(policy, null, 2) + '\n', 'utf8');
}

test('Epoch 5 root policy and immutable core pass the self-protection suite', () => {
    const f = fixture();
    try {
        const result = verify(f.root, f.base, f.base, ['--invariants']);
        assert.equal(result.status, 0, result.stderr);
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('candidate cannot shrink the immutable core or historical root registry', () => {
    for (const mutate of [
        (policy) => policy.protectedCore.pop(),
        (policy) => delete policy.ruleset.roots['refs/tags/i18n-gate-epoch-2-root'],
    ]) {
        const f = fixture();
        try {
            mutatePolicy(f.root, mutate);
            const candidate = commit(f.root, 'weaken policy');
            const result = verify(f.root, candidate, f.base);
            assert.notEqual(result.status, 0);
        } finally {
            fs.rmSync(f.root, { recursive: true, force: true });
        }
    }
});

test('monotonic policy additions do not require another Epoch', () => {
    const f = fixture();
    try {
        mutatePolicy(f.root, (policy) => {
            policy.ruleset.requiredChecks.push('future-security-check');
            policy.ruleset.minimumApprovals = 1;
            policy.qualityGate.allowedPushExclusions = [];
        });
        const qualityGate = path.join(f.root, '.github', 'workflows', 'quality-gate.yml');
        fs.writeFileSync(qualityGate, fs.readFileSync(qualityGate, 'utf8')
            .replace('branches-ignore: [gh-pages]', 'branches-ignore: []'), 'utf8');
        const candidate = commit(f.root, 'strengthen policy');
        const result = verify(f.root, candidate, f.base);
        assert.equal(result.status, 0, result.stderr);
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('required predecessor-verifier jobs cannot retire without another Epoch', () => {
    const f = fixture();
    try {
        const qualityGate = path.join(f.root, '.github', 'workflows', 'quality-gate.yml');
        const body = fs.readFileSync(qualityGate, 'utf8')
            .replace(/\n  signature-guard:[\s\S]*?(?=\n  i18n-check:)/u, '\n');
        fs.writeFileSync(qualityGate, body, 'utf8');
        const candidate = commit(f.root, 'retire Epoch 4 bootstrap');
        const result = verify(f.root, candidate, f.base);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /quality-gate\.yml jobs removed signature-guard/u);
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('all workflow files are discovered and write-capable jobs require Gate plus Environment', () => {
    const f = fixture();
    try {
        const workflow = path.join(f.root, '.github', 'workflows', 'manual-publish.yml');
        fs.writeFileSync(workflow, [
            'name: Manual publish',
            'on: workflow_dispatch',
            'permissions:',
            '  contents: write',
            'jobs:',
            '  publish:',
            '    runs-on: ubuntu-latest',
            '    steps:',
            '      - run: echo publish',
            '',
        ].join('\n'), 'utf8');
        const candidate = commit(f.root, 'add unsafe workflow');
        const result = verify(f.root, candidate, f.base);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /manual-publish\.yml.*publish/u);
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('a new read-only workflow is ordinary maintenance', () => {
    const f = fixture();
    try {
        const workflow = path.join(f.root, '.github', 'workflows', 'manual-audit.yaml');
        fs.writeFileSync(workflow, [
            'name: Manual audit',
            'on: workflow_dispatch',
            'permissions:',
            '  contents: read',
            'jobs:',
            '  audit:',
            '    runs-on: ubuntu-latest',
            '    steps:',
            '      - run: echo audit',
            '',
        ].join('\n'), 'utf8');
        const candidate = commit(f.root, 'add read-only workflow');
        const result = verify(f.root, candidate, f.base);
        assert.equal(result.status, 0, result.stderr);
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('secret source names in ordinary workflows can change without another Epoch', () => {
    const f = fixture();
    try {
        const release = path.join(f.root, '.github', 'workflows', 'release.yml');
        const before = fs.readFileSync(release, 'utf8');
        const after = before.replace(/\$\{\{\s*secrets\.[A-Z_][A-Z0-9_]*\s*\}\}/u,
            '${{ secrets.RENAMED_RELEASE_TOKEN }}');
        assert.notEqual(after, before);
        fs.writeFileSync(release, after, 'utf8');
        const candidate = commit(f.root, 'rename secret sources');
        const result = verify(f.root, candidate, f.base);
        assert.equal(result.status, 0, result.stderr);
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('top-level writes, inherited secrets and modified trusted core fail closed', () => {
    for (const mutate of [
        (root) => {
            const workflow = path.join(root, '.github', 'workflows', 'shared-snippets-check.yml');
            fs.writeFileSync(workflow, fs.readFileSync(workflow, 'utf8')
                .replace('contents: read', 'contents: write'), 'utf8');
        },
        (root) => {
            const workflow = path.join(root, '.github', 'workflows', 'manual-secrets.yml');
            fs.writeFileSync(workflow, [
                'name: Secret pass-through',
                'on: workflow_dispatch',
                'permissions:',
                '  contents: read',
                'jobs:',
                '  call:',
                '    uses: ./.github/workflows/quality-gate.yml',
                '    secrets: inherit',
                '',
            ].join('\n'), 'utf8');
        },
        (root) => {
            const workflow = path.join(root, '.github', 'workflows', 'bracket-secret.yml');
            fs.writeFileSync(workflow, [
                'name: Bracket secret',
                'on: workflow_dispatch',
                'permissions:',
                '  contents: read',
                'jobs:',
                '  publish:',
                '    runs-on: ubuntu-latest',
                "    env: { TOKEN: \"${{ secrets['TOKEN'] }}\" }",
                '    steps:',
                '      - run: echo publish',
                '',
            ].join('\n'), 'utf8');
        },
        (root) => fs.appendFileSync(path.join(root, 'scripts', 'ci', 'release-gate-verifier.mjs'),
            '\n// candidate change\n', 'utf8'),
        (root) => {
            const workflow = path.join(root, '.github', 'workflows', 'quality-gate.yml');
            fs.writeFileSync(workflow, fs.readFileSync(workflow, 'utf8')
                .replace('node "$GATE_DIR/scripts/ci/release-gate-verifier.mjs"',
                    'node candidate.mjs'), 'utf8');
        },
        (root) => fs.appendFileSync(path.join(root, 'scripts', 'ci', 'resolve-trusted-base.mjs'),
            '\n// candidate change\n', 'utf8'),
    ]) {
        const f = fixture();
        try {
            mutate(f.root);
            const candidate = commit(f.root, 'weaken authority');
            assert.notEqual(verify(f.root, candidate, f.base).status, 0);
        } finally {
            fs.rmSync(f.root, { recursive: true, force: true });
        }
    }
});
