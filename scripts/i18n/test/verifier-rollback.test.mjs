'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CORE = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/ci/gate-invariants.json'), 'utf8')).protectedPaths;

function git(root, args) {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
    if (result.status !== 0) throw new Error(result.stderr || result.stdout);
    return result.stdout.trim();
}

function commit(root, message) {
    git(root, ['add', '-A']);
    git(root, ['commit', '-q', '-m', message]);
    return git(root, ['rev-parse', 'HEAD']);
}

function resolver(root, candidate, base) {
    return spawnSync(process.execPath, [path.join(root, 'scripts/ci/resolve-trusted-base.mjs'),
        '--repo-root', root, '--event-name', 'workflow_call', '--candidate', candidate,
        '--input-base', base, '--default-branch', 'master', '--mode'], {
        cwd: root, encoding: 'utf8',
    });
}

test('verifier rollback：protected predecessor 必须满足当前五文件能力基线', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv verifier rollback '));
    try {
        git(root, ['init', '-q']);
        git(root, ['config', 'user.email', 'test@example.com']);
        git(root, ['config', 'user.name', 'test']);
        for (const rel of CORE) {
            const target = path.join(root, ...rel.split('/'));
            fs.mkdirSync(path.dirname(target), { recursive: true });
            fs.copyFileSync(path.join(ROOT, ...rel.split('/')), target);
        }
        const gateRoot = commit(root, 'root');
        git(root, ['tag', 'i18n-gate-epoch-4-root', gateRoot]);
        fs.writeFileSync(path.join(root, 'ordinary.txt'), 'base\n');
        const goodBase = commit(root, 'good base');
        fs.writeFileSync(path.join(root, 'ordinary.txt'), 'candidate\n');
        const candidate = commit(root, 'candidate');
        git(root, ['update-ref', 'refs/remotes/origin/master', goodBase]);
        const accepted = resolver(root, candidate, goodBase);
        assert.equal(accepted.status, 0, accepted.stderr);

        git(root, ['checkout', '-q', goodBase]);
        const policyFile = path.join(root, 'scripts/i18n/gate-policy.json');
        const policy = JSON.parse(fs.readFileSync(policyFile, 'utf8'));
        policy.contractVersion = 4;
        fs.writeFileSync(policyFile, JSON.stringify(policy, null, 2) + '\n');
        const weakBase = commit(root, 'weak verifier');
        fs.writeFileSync(path.join(root, 'ordinary.txt'), 'weak candidate\n');
        const weakCandidate = commit(root, 'weak candidate');
        git(root, ['update-ref', 'refs/remotes/origin/master', weakBase]);
        const rejected = resolver(root, weakCandidate, weakBase);
        assert.notEqual(rejected.status, 0);
        assert.match(rejected.stderr, /contractVersion 4 < current minimum 5/);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
