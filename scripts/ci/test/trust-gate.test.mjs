'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { historicalGateFile } from './lib/historical-gate.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const FILES = [
    'package.json',
    'scripts/ci/gate-invariants.json',
    'scripts/ci/gate-parity.mjs',
    'scripts/ci/github-ruleset-invariants.json',
    'scripts/ci/resolve-trusted-base.mjs',
    'scripts/i18n/gate-contract.mjs',
    'scripts/i18n/gate-policy.json',
    'scripts/i18n/trust-gate.mjs',
    '.github/workflows/quality-gate.yml',
    '.github/workflows/shared-snippets-check.yml',
    '.github/workflows/release.yml',
    '.github/workflows/nightly.yml',
    '.github/workflows/publish-plugins.yml',
];
const KEYS = {
    sourceEpoch: 'pixiv.i18n.firstAdmissionSourceEpoch',
    targetEpoch: 'pixiv.i18n.firstAdmissionTargetEpoch',
    trustedSource: 'pixiv.i18n.firstAdmissionTrustedSource',
    parent: 'pixiv.i18n.firstAdmissionParent',
    tree: 'pixiv.i18n.firstAdmissionTree',
    candidate: 'pixiv.i18n.firstAdmissionCandidate',
};

function runGit(root, args) {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
    if (result.status !== 0) throw new Error(result.stderr || result.stdout);
    return result.stdout.trim();
}

function fixture() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv adopt root '));
    runGit(root, ['init', '-q']);
    runGit(root, ['config', 'user.email', 'test@example.com']);
    runGit(root, ['config', 'user.name', 'test']);
    const oldPolicy = path.join(root, 'scripts/i18n/gate-policy.json');
    fs.mkdirSync(path.dirname(oldPolicy), { recursive: true });
    fs.writeFileSync(oldPolicy, '{"gateEpoch":3}\n');
    fs.writeFileSync(path.join(root, '.gitignore'), 'node_modules/\n');
    runGit(root, ['add', '-A']);
    runGit(root, ['commit', '-q', '-m', 'previous trusted source']);
    const source = runGit(root, ['rev-parse', 'HEAD']);
    runGit(root, ['tag', 'i18n-gate-epoch-3-root', source]);
    runGit(root, ['update-ref', 'refs/remotes/origin/master', source]);
    for (const rel of FILES) {
        const target = path.join(root, ...rel.split('/'));
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.writeFileSync(target, historicalGateFile(ROOT, rel));
    }
    try {
        fs.symlinkSync(path.join(ROOT, 'node_modules'), path.join(root, 'node_modules'),
            process.platform === 'win32' ? 'junction' : 'dir');
    } catch {
        fs.cpSync(path.join(ROOT, 'node_modules'), path.join(root, 'node_modules'), { recursive: true });
    }
    runGit(root, ['add', '-A']);
    runGit(root, ['commit', '-q', '-m', 'new trusted root']);
    const candidate = runGit(root, ['rev-parse', 'HEAD']);
    const tree = runGit(root, ['rev-parse', 'HEAD^{tree}']);
    runGit(root, ['config', '--local', 'pixiv.i18n.trustedGateEpoch', '3']);
    runGit(root, ['config', '--local', 'pixiv.i18n.trustedGateRef', source]);
    const values = { sourceEpoch: '3', targetEpoch: '4', trustedSource: source,
        parent: source, tree, candidate };
    for (const [field, key] of Object.entries(KEYS)) runGit(root, ['config', '--local', key, values[field]]);
    return { root, source, candidate };
}

function cli(f, args, ci = 'false') {
    return spawnSync(process.execPath, [path.join(f.root, 'scripts/i18n/trust-gate.mjs'), ...args], {
        cwd: f.root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024,
        env: { ...process.env, CI: ci },
    });
}

test('trust-gate：sealed root adoption 原子推进 anchor 并消费票据', () => {
    const f = fixture();
    try {
        const result = cli(f, ['--adopt-root', '--ref', 'HEAD', '--epoch', '4']);
        assert.equal(result.status, 0, result.stderr);
        assert.equal(runGit(f.root, ['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch']), '4');
        assert.equal(runGit(f.root, ['config', '--local', '--get', 'pixiv.i18n.trustedGateRef']), f.candidate);
        for (const key of Object.values(KEYS)) {
            const missing = spawnSync('git', ['config', '--local', '--get', key], { cwd: f.root });
            assert.notEqual(missing.status, 0);
        }
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('trust-gate：票据不匹配时保持旧 anchor', () => {
    const f = fixture();
    try {
        runGit(f.root, ['config', '--local', KEYS.tree, '0000000000000000000000000000000000000000']);
        const result = cli(f, ['--adopt-root', '--ref', 'HEAD', '--epoch', '4']);
        assert.notEqual(result.status, 0);
        assert.equal(runGit(f.root, ['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch']), '3');
        assert.equal(runGit(f.root, ['config', '--local', '--get', 'pixiv.i18n.trustedGateRef']), f.source);
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('trust-gate：CI 环境拒绝 root adoption 并保持旧 anchor', () => {
    const f = fixture();
    try {
        const result = cli(f, ['--adopt-root', '--ref', 'HEAD', '--epoch', '4'], 'true');
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /root adoption is forbidden in CI/);
        assert.equal(runGit(f.root, ['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch']), '3');
        assert.equal(runGit(f.root, ['config', '--local', '--get', 'pixiv.i18n.trustedGateRef']), f.source);
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('trust-gate：未来 root 必须使用独立评审的 predecessor bridge', () => {
    const result = spawnSync(process.execPath, ['scripts/i18n/trust-gate.mjs', '--prepare-root', '--epoch', '5'], {
        cwd: ROOT, encoding: 'utf8',
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /separately reviewed predecessor admission bridge/);
});
