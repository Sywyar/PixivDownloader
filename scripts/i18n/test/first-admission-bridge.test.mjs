'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
    assertExactCoreReduction,
    normalizeFirstAdmissionCandidate,
    validateFirstAdmissionSpec,
} from '../trust-gate.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const SPEC = JSON.parse(fs.readFileSync(
    path.join(REPO_ROOT, 'scripts', 'i18n', 'epoch-3-first-admission.json'), 'utf8'));

function git(root, args, options = {}) {
    return execFileSync('git', args, {
        cwd: root,
        encoding: 'utf8',
        stdio: ['pipe', 'pipe', 'pipe'],
        ...options,
    }).trim();
}

function write(root, rel, value) {
    const file = path.join(root, ...rel.split('/'));
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, value, 'utf8');
}

function commitTree(root, message, parent = null) {
    git(root, ['add', '-A']);
    const tree = git(root, ['write-tree']);
    const args = ['commit-tree', tree];
    if (parent) args.push('-p', parent);
    return git(root, args, { input: message + '\n' });
}

function makeRepo() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv epoch bridge '));
    git(root, ['init', '-q']);
    git(root, ['config', 'user.email', 'test@example.com']);
    git(root, ['config', 'user.name', 'test']);
    return root;
}

test('first-admission：只接受精确的 Epoch 3 到 4 声明', () => {
    assert.equal(validateFirstAdmissionSpec(structuredClone(SPEC)).targetEpoch, 4);
    const widened = structuredClone(SPEC);
    widened.targetEpoch = 5;
    assert.throws(() => validateFirstAdmissionSpec(widened), /invalid Epoch 3/);
});

test('first-admission：候选归一化后必须精确恢复可信来源树', () => {
    const root = makeRepo();
    try {
        write(root, 'kept.txt', 'trusted\n');
        write(root, 'deleted.txt', 'trusted\n');
        const source = commitTree(root, 'source');
        git(root, ['update-ref', 'HEAD', source]);

        write(root, 'kept.txt', 'candidate\n');
        fs.rmSync(path.join(root, 'deleted.txt'));
        write(root, 'added.txt', 'candidate\n');
        const candidate = commitTree(root, 'candidate', source);
        const normalized = normalizeFirstAdmissionCandidate(root, candidate, source, {
            allowedChangedPaths: ['kept.txt', 'deleted.txt', 'added.txt'],
        });

        assert.equal(git(root, ['rev-parse', normalized + '^{tree}']),
            git(root, ['rev-parse', source + '^{tree}']));
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('first-admission：目标 policy、核心不变量和 Ruleset 必须精确匹配', () => {
    const root = makeRepo();
    try {
        const rootProtection = { allowDeletion: false, allowNonFastForward: false, allowBypass: false };
        write(root, 'scripts/i18n/gate-policy.json', JSON.stringify({ gateEpoch: 3 }) + '\n');
        write(root, 'scripts/i18n/epoch-3-first-admission.json', '{}\n');
        write(root, 'scripts/ci/github-ruleset-invariants.json', JSON.stringify({
            master: { requiredChecks: SPEC.targetRuleset.master.requiredChecks },
            'i18n-gate-epoch-3-root': rootProtection,
        }) + '\n');
        const source = commitTree(root, 'source');
        git(root, ['update-ref', 'HEAD', source]);

        write(root, 'scripts/i18n/gate-policy.json', JSON.stringify(SPEC.targetPolicy, null, 2) + '\n');
        write(root, 'scripts/ci/gate-invariants.json', JSON.stringify(SPEC.targetInvariants, null, 2) + '\n');
        write(root, 'scripts/ci/github-ruleset-invariants.json', JSON.stringify({
            master: SPEC.targetRuleset.master,
            'i18n-gate-epoch-3-root': rootProtection,
            'i18n-gate-epoch-4-root': rootProtection,
        }, null, 2) + '\n');
        fs.rmSync(path.join(root, 'scripts', 'i18n', 'epoch-3-first-admission.json'));
        for (const rel of SPEC.targetPolicy.minimumTrustedVerifier.requiredFiles) {
            if (!fs.existsSync(path.join(root, ...rel.split('/')))) write(root, rel, 'trusted core\n');
        }
        const candidate = commitTree(root, 'candidate', source);

        assert.doesNotThrow(() => assertExactCoreReduction(root, candidate, source, SPEC));

        const weakened = structuredClone(SPEC);
        weakened.targetRuleset.master.requiredChecks = ['java-tests'];
        assert.throws(() => assertExactCoreReduction(root, candidate, source, weakened),
            /master ruleset invariants/);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
