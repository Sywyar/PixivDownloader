'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

function clean(root) {
    for (let attempt = 0; attempt < 10; attempt += 1) {
        try {
            fs.rmSync(root, { recursive: true, force: true });
            return;
        } catch (error) {
            if (attempt === 9) throw error;
            Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 200);
        }
    }
}

test('hooks：提前反馈面复用可信发布核心检查器', () => {
    const surface = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/ci/gate-surface.json'), 'utf8'));
    const preCommit = fs.readFileSync(path.join(ROOT, 'scripts/hooks/pre-commit'), 'utf8');
    const prePush = fs.readFileSync(path.join(ROOT, 'scripts/hooks/pre-push'), 'utf8');
    const scripts = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8')).scripts;
    assert.ok(surface.paths.includes('scripts/ci/gate-contract.mjs'));
    assert.ok(surface.paths.includes('scripts/ci/trust-gate.mjs'));
    assert.ok(surface.paths.includes('scripts/ci/release-gate-verifier.mjs'));
    assert.ok(surface.paths.includes('.github/workflows'));
    assert.equal(surface.paths.some((rel) => /^\.github\/workflows\/[^/]+\.ya?ml$/u.test(rel)), false);
    assert.deepEqual(surface.paths.filter((rel) => rel.startsWith('scripts/i18n/')), [
        'scripts/i18n/check.mjs',
        'scripts/i18n/gate-contract.mjs',
        'scripts/i18n/gate-policy.json',
        'scripts/i18n/trust-gate.mjs',
    ]);
    assert.match(preCommit, /gate-surface\.json/);
    assert.match(prePush, /gate-surface\.json/);
    assert.match(preCommit, /gate-contract\.mjs/);
    assert.match(prePush, /gate-contract\.mjs/);
    assert.match(preCommit, /scripts\/ci\/gate-contract\.mjs/);
    assert.match(prePush, /scripts\/ci\/gate-contract\.mjs/);
    assert.equal(scripts['gate:trust'], 'node scripts/ci/trust-gate.mjs');
    assert.equal(scripts['gate:contract'], 'node scripts/ci/gate-contract.mjs');
    for (const hook of [preCommit, prePush]) {
        assert.doesNotMatch(hook, /trustedGateEpoch|trustedGateRef|git\s+ls-remote|refs\/pixiv-i18n-prepush/);
    }
});

test('hooks：shell 语法有效', () => {
    for (const hook of ['pre-commit', 'pre-push']) {
        const result = spawnSync('bash', ['-n', `scripts/hooks/${hook}`], { cwd: ROOT, encoding: 'utf8' });
        assert.equal(result.status, 0, result.stderr);
    }
});

test('pre-commit：无暂存内容快速通过', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv hook smoke '));
    try {
        spawnSync('git', ['init', '-q'], { cwd: root });
        const hookDir = path.join(root, 'scripts/hooks');
        fs.mkdirSync(hookDir, { recursive: true });
        fs.copyFileSync(path.join(ROOT, 'scripts/hooks/pre-commit'), path.join(hookDir, 'pre-commit'));
        const result = spawnSync('bash', ['scripts/hooks/pre-commit'], { cwd: root, encoding: 'utf8' });
        assert.equal(result.status, 0, result.stderr);
        assert.match(result.stdout, /nothing staged/);
    } finally {
        clean(root);
    }
});

test('pre-push：仅删除 ref 时不运行候选检查', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv pre-push deletion '));
    try {
        spawnSync('git', ['init', '-q'], { cwd: root });
        const hookDir = path.join(root, 'scripts/hooks');
        fs.mkdirSync(hookDir, { recursive: true });
        fs.copyFileSync(path.join(ROOT, 'scripts/hooks/pre-push'), path.join(hookDir, 'pre-push'));
        const result = spawnSync('bash', ['scripts/hooks/pre-push', 'origin', 'unused'], {
            cwd: root,
            encoding: 'utf8',
            input: `refs/heads/topic ${'0'.repeat(40)} refs/heads/topic ${'1'.repeat(40)}\n`,
        });
        assert.equal(result.status, 0, result.stderr);
        assert.match(result.stdout, /skipping deletion/);
        assert.match(result.stdout, /no commits to verify/);
    } finally {
        clean(root);
    }
});

test('signature guard：逆向签名标记仍由独立守卫拒绝', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv signature smoke '));
    try {
        spawnSync('git', ['init', '-q'], { cwd: root });
        spawnSync('git', ['config', 'user.email', 'test@example.com'], { cwd: root });
        spawnSync('git', ['config', 'user.name', 'test'], { cwd: root });
        const forbiddenMarker = 'DouyinX' + 'BogusSigner';
        fs.writeFileSync(path.join(root, 'Bad.java'), `class Bad { String value = "${forbiddenMarker}"; }\n`);
        spawnSync('git', ['add', 'Bad.java'], { cwd: root });
        spawnSync('git', ['commit', '-q', '-m', 'bad'], { cwd: root });
        const hookDir = path.join(root, 'scripts/hooks');
        fs.mkdirSync(hookDir, { recursive: true });
        fs.copyFileSync(path.join(ROOT, 'scripts/hooks/pre-push-guard.sh'), path.join(hookDir, 'pre-push-guard.sh'));
        const result = spawnSync('bash', ['scripts/hooks/pre-push-guard.sh', '--repo-root', '.', '--ref', 'HEAD'], {
            cwd: root, encoding: 'utf8',
        });
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /reverse-engineered/);
    } finally {
        clean(root);
    }
});
