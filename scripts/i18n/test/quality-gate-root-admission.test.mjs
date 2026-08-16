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

function fixture() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv resolver '));
    git(root, ['init', '-q']);
    git(root, ['config', 'user.email', 'test@example.com']);
    git(root, ['config', 'user.name', 'test']);
    for (const rel of CORE) {
        const target = path.join(root, ...rel.split('/'));
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.copyFileSync(path.join(ROOT, ...rel.split('/')), target);
    }
    git(root, ['add', '-A']);
    git(root, ['commit', '-q', '-m', 'root']);
    const gateRoot = git(root, ['rev-parse', 'HEAD']);
    git(root, ['tag', 'i18n-gate-epoch-4-root', gateRoot]);
    git(root, ['update-ref', 'refs/remotes/origin/master', gateRoot]);
    fs.writeFileSync(path.join(root, 'ordinary.txt'), 'ordinary\n');
    git(root, ['add', 'ordinary.txt']);
    git(root, ['commit', '-q', '-m', 'ordinary']);
    return { root, gateRoot, candidate: git(root, ['rev-parse', 'HEAD']) };
}

function resolve(f, candidate, extra = []) {
    return spawnSync(process.execPath, [path.join(f.root, 'scripts/ci/resolve-trusted-base.mjs'),
        '--repo-root', f.root, '--event-name', 'push', '--candidate', candidate,
        '--before', '0000000000000000000000000000000000000000', '--ref', 'refs/heads/feature',
        '--default-branch', 'master', '--mode', ...extra], { cwd: f.root, encoding: 'utf8' });
}

test('trusted base：root 与普通后代模式区分明确', () => {
    const f = fixture();
    try {
        const rootResult = resolve(f, f.gateRoot);
        assert.equal(rootResult.status, 0, rootResult.stderr);
        assert.equal(JSON.parse(rootResult.stdout).mode, 'ROOT_ADMISSION');
        const normal = resolve(f, f.candidate);
        assert.equal(normal.status, 0, normal.stderr);
        assert.deepEqual(JSON.parse(normal.stdout), {
            mode: 'NORMAL', base: f.gateRoot, root: f.gateRoot,
        });
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('trusted base：缺 root tag 时只允许精确显式 admission', () => {
    const f = fixture();
    try {
        git(f.root, ['tag', '-d', 'i18n-gate-epoch-4-root']);
        const ordinary = resolve(f, f.candidate);
        assert.notEqual(ordinary.status, 0);
        const admitted = spawnSync(process.execPath,
            [path.join(f.root, 'scripts/ci/resolve-trusted-base.mjs'), '--repo-root', f.root,
                '--event-name', 'workflow_dispatch', '--candidate', f.candidate,
                '--default-branch', 'master', '--root-admission', 'true',
                '--root-candidate-sha', f.candidate, '--mode'], { cwd: f.root, encoding: 'utf8' });
        assert.equal(admitted.status, 0, admitted.stderr);
        assert.equal(JSON.parse(admitted.stdout).mode, 'ROOT_ADMISSION');
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});
