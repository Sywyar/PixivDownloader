'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CONTRACT = path.join(ROOT, 'scripts/ci/gate-contract.mjs');
const EPOCH_4_CONTRACT = path.join(ROOT, 'scripts/i18n/gate-contract.mjs');

test('gate-contract：Epoch 4 兼容实现只委托共享检查器', () => {
    const body = fs.readFileSync(EPOCH_4_CONTRACT, 'utf8');
    assert.match(body, /gate-parity\.mjs/);
    assert.match(body, /spawnSync/);
    assert.doesNotMatch(body, /REQUIRED_COMMANDS|APPROVED_ACTIONS|pixiv\.layout-survey/);
});

test('gate-contract：版本与缺参均 fail closed', () => {
    const version = spawnSync(process.execPath, [CONTRACT, '--version'], { cwd: ROOT, encoding: 'utf8' });
    assert.equal(version.status, 0, version.stderr);
    assert.match(version.stdout, /gate-contract 5/);
    const invalidVersion = spawnSync(process.execPath, [CONTRACT, '--version', '--unknown'], {
        cwd: ROOT, encoding: 'utf8',
    });
    assert.notEqual(invalidVersion.status, 0);
    const missing = spawnSync(process.execPath, [CONTRACT], { cwd: ROOT, encoding: 'utf8' });
    assert.notEqual(missing.status, 0);
    assert.match(missing.stderr, /required/);
});
