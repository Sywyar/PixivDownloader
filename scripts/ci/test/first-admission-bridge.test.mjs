'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CORE = [
    'scripts/ci/gate-invariants.json',
    'scripts/ci/gate-parity.mjs',
    'scripts/ci/resolve-trusted-base.mjs',
    'scripts/i18n/gate-policy.json',
    'scripts/i18n/trust-gate.mjs',
];

test('Epoch 4 root：保护面只包含可信发布核心', () => {
    const policy = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/i18n/gate-policy.json'), 'utf8'));
    const invariants = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/ci/gate-invariants.json'), 'utf8'));
    assert.equal(policy.gateEpoch, 4);
    assert.equal(policy.contractVersion, 5);
    assert.deepEqual(policy.minimumTrustedVerifier.requiredFiles, CORE);
    assert.deepEqual(invariants.protectedPaths, CORE);
    assert.equal(fs.existsSync(path.join(ROOT, 'scripts/i18n/epoch-3-first-admission.json')), false);
});

test('Epoch 4 root：Ruleset 要求 PR-only 且审批数为零', () => {
    const rules = JSON.parse(fs.readFileSync(
        path.join(ROOT, 'scripts/ci/github-ruleset-invariants.json'), 'utf8'));
    assert.equal(rules.master.requireStrict, true);
    assert.equal(rules.master.requirePullRequest, true);
    assert.equal(rules.master.requiredApprovals, 0);
    assert.equal(rules.master.allowBypass, false);
    assert.deepEqual(rules['i18n-gate-epoch-4-root'], {
        allowDeletion: false,
        allowNonFastForward: false,
        allowBypass: false,
    });
});

test('Epoch 4 root：受保护 CLI 报告当前版本', () => {
    const result = spawnSync(process.execPath, ['scripts/i18n/trust-gate.mjs', '--version'], {
        cwd: ROOT, encoding: 'utf8',
    });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /i18n-trust-gate 4/);
});

test('Gate 通用入口报告当前可信发布版本', () => {
    const result = spawnSync(process.execPath, ['scripts/ci/trust-gate.mjs', '--version'], {
        cwd: ROOT, encoding: 'utf8',
    });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /trusted-release-gate 5/);
    const invalid = spawnSync(process.execPath,
        ['scripts/ci/trust-gate.mjs', '--version', '--unknown'], { cwd: ROOT, encoding: 'utf8' });
    assert.notEqual(invalid.status, 0);
});
