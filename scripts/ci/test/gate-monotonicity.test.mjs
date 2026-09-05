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

function git(root, args) {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
    if (result.status !== 0) throw new Error(result.stderr || result.stdout);
    return result.stdout.trim();
}

function write(root, rel, content) {
    const file = path.join(root, ...rel.split('/'));
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content, 'utf8');
}

function fixture() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv core parity '));
    git(root, ['init', '-q']);
    git(root, ['config', 'user.email', 'test@example.com']);
    git(root, ['config', 'user.name', 'test']);
    for (const rel of FILES) write(root, rel, historicalGateFile(ROOT, rel));
    try {
        fs.symlinkSync(path.join(ROOT, 'node_modules'), path.join(root, 'node_modules'),
            process.platform === 'win32' ? 'junction' : 'dir');
    } catch {
        fs.cpSync(path.join(ROOT, 'node_modules'), path.join(root, 'node_modules'), { recursive: true });
    }
    git(root, ['add', '-A']);
    git(root, ['commit', '-q', '-m', 'trusted root']);
    const base = git(root, ['rev-parse', 'HEAD']);
    git(root, ['tag', 'i18n-gate-epoch-4-root', base]);
    git(root, ['update-ref', 'refs/remotes/origin/master', base]);
    const trustedDir = path.join(root, 'trusted');
    for (const rel of JSON.parse(fs.readFileSync(path.join(root, 'scripts/ci/gate-invariants.json'))).protectedPaths) {
        const target = path.join(trustedDir, ...rel.split('/'));
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.copyFileSync(path.join(root, ...rel.split('/')), target);
    }
    return { root, base, trustedDir };
}

function commit(root, message) {
    git(root, ['add', '-A']);
    git(root, ['commit', '-q', '-m', message]);
    return git(root, ['rev-parse', 'HEAD']);
}

function parity(f, ref, extra = []) {
    return spawnSync(process.execPath, [path.join(f.root, 'scripts/ci/gate-parity.mjs'),
        '--repo-root', f.root, '--candidate-ref', ref, ...extra], {
        cwd: f.root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024,
    });
}

function mutateJson(root, rel, mutate) {
    const file = path.join(root, ...rel.split('/'));
    const value = JSON.parse(fs.readFileSync(file, 'utf8'));
    mutate(value);
    fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n', 'utf8');
}

test('trusted release core：root 不变量通过，required job 删除被拒绝', () => {
    const f = fixture();
    try {
        assert.equal(parity(f, f.base, ['--invariants']).status, 0);
        const file = path.join(f.root, '.github/workflows/quality-gate.yml');
        fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace(/^  java-tests:[\s\S]*?^  javascript-tests:/m,
            '  javascript-tests:'), 'utf8');
        const bad = commit(f.root, 'remove required job');
        const result = parity(f, bad, ['--invariants']);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /java-tests/);
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});

test('trusted release core：失败绕过和 Environment 移除均被拒绝', () => {
    for (const mutate of [
        (text) => text.replace("if: ${{ github.repository == 'Sywyar/PixivDownloader' }}",
            'if: ${{ always() }}'),
        (text) => text.replace('    environment: release\n', ''),
    ]) {
        const f = fixture();
        try {
            const file = path.join(f.root, '.github/workflows/publish-plugins.yml');
            fs.writeFileSync(file, mutate(fs.readFileSync(file, 'utf8')), 'utf8');
            const bad = commit(f.root, 'weaken publication boundary');
            assert.notEqual(parity(f, bad, ['--invariants']).status, 0);
        } finally {
            fs.rmSync(f.root, { recursive: true, force: true });
        }
    }
});

test('trusted release core：日常 workflow 维护允许，核心变更要求新 Epoch', () => {
    const f = fixture();
    try {
        fs.appendFileSync(path.join(f.root, '.github/workflows/release.yml'), '\n# ordinary maintenance\n');
        const ordinary = commit(f.root, 'ordinary workflow maintenance');
        const accepted = parity(f, ordinary, ['--trusted-dir', f.trustedDir, '--trusted-ref', f.base]);
        assert.equal(accepted.status, 0, accepted.stderr);

        fs.appendFileSync(path.join(f.root, 'scripts/i18n/trust-gate.mjs'), '\n// trust model change\n');
        const protectedChange = commit(f.root, 'change protected core');
        const rejected = parity(f, protectedChange,
            ['--trusted-dir', f.trustedDir, '--trusted-ref', f.base]);
        assert.notEqual(rejected.status, 0);
        assert.match(rejected.stderr, /new Gate Epoch/);
    } finally {
        fs.rmSync(f.root, { recursive: true, force: true });
    }
});
