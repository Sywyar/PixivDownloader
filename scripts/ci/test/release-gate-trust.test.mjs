'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CLI = path.join(ROOT, 'scripts', 'ci', 'release-gate-trust.mjs');
const CONTRACT = path.join(ROOT, 'scripts', 'ci', 'gate-contract.mjs');

function git(root, args) {
    return execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
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

test('Epoch 5 adoption accepts the required Merge commit and verifies both Epochs', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv trust v5 '));
    const remote = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv trust v5 remote '));
    try {
        git(root, ['init', '-q']);
        git(root, ['config', 'user.email', 'test@example.com']);
        git(root, ['config', 'user.name', 'test']);
        copy(root, '.gitignore');
        copy(root, 'package.json');
        const oldPolicy = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/i18n/gate-policy.json'), 'utf8'));
        const oldFiles = new Set([...oldPolicy.minimumTrustedVerifier.requiredFiles,
            ...oldPolicy.requiredWorkflowFiles, 'scripts/ci/github-ruleset-invariants.json',
            'scripts/ci/gate-contract.mjs']);
        for (const rel of oldFiles) copy(root, rel);
        fs.symlinkSync(path.join(ROOT, 'node_modules'), path.join(root, 'node_modules'),
            process.platform === 'win32' ? 'junction' : 'dir');
        const base = commit(root, 'Epoch 4 source');
        git(root, ['branch', '-M', 'master']);
        git(root, ['tag', 'i18n-gate-epoch-4-root', base]);

        git(root, ['switch', '-q', '-c', 'epoch-5']);
        copy(root, 'scripts/ci/release-gate-policy.json');
        const policy = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/ci/release-gate-policy.json'), 'utf8'));
        for (const rel of policy.protectedCore) copy(root, rel);
        commit(root, 'Epoch 5 candidate');
        git(root, ['switch', '-q', 'master']);
        git(root, ['merge', '--no-ff', '-q', '-m', 'Epoch 5 root', 'epoch-5']);
        const candidate = git(root, ['rev-parse', 'HEAD']);
        git(root, ['tag', 'release-gate-epoch-5-root', candidate]);

        git(remote, ['init', '--bare', '-q']);
        git(root, ['remote', 'add', 'origin', remote]);
        git(root, ['push', '-q', 'origin', `${candidate}:refs/heads/master`,
            'refs/tags/i18n-gate-epoch-4-root', 'refs/tags/release-gate-epoch-5-root']);
        git(root, ['update-ref', 'refs/remotes/origin/master', candidate]);
        git(root, ['config', '--local', 'pixiv.i18n.trustedGateEpoch', '4']);
        git(root, ['config', '--local', 'pixiv.i18n.trustedGateRef', base]);

        const env = { ...process.env };
        delete env.CI;
        const result = spawnSync(process.execPath, [CLI, '--adopt-root', '--ref', candidate], {
            cwd: root, env, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024,
        });
        assert.equal(result.status, 0, result.stderr || result.stdout);
        assert.equal(git(root, ['config', '--local', '--get', 'pixiv.release.trustedGateEpoch']), '5');
        assert.equal(git(root, ['config', '--local', '--get', 'pixiv.release.trustedGateRef']), candidate);
        const contract = spawnSync(process.execPath, [CONTRACT, '--repo-root', root,
            '--candidate-ref', candidate], { cwd: root, env, encoding: 'utf8' });
        assert.equal(contract.status, 0, contract.stderr || contract.stdout);
        assert.match(contract.stdout, /TRUSTED RELEASE GATE 5 OK/u);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
        fs.rmSync(remote, { recursive: true, force: true });
    }
});
