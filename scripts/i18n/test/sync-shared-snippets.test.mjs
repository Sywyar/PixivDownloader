'use strict';
/**
 * shared-snippet 真实 checker 行为测试（scripts/sync-shared-snippets.ps1）：
 * - drift fixture（shared source != .user.js 标记区）→ -Check 必须 exit != 0；
 * - 合法同步（写回）后重新 -Check 必须 exit 0；
 * - 恶意 stub（exit 0）在 drift fixture 下必须静默通过（被 trusted contract 内容守卫拒绝，
 *   此处只证明行为差异，说明 stub 会让 drift 不被发现）。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');
const SYNC = path.join(REPO_ROOT, 'scripts', 'sync-shared-snippets.ps1');

function hasPwsh() {
    try {
        execFileSync('pwsh', ['-NoProfile', '-Command', '$true'], { stdio: 'ignore' });
        return true;
    } catch (e) {
        return false;
    }
}

function cleanRepo(root) {
    if (!root) {
        return;
    }
    for (let attempt = 0; attempt < 6; attempt += 1) {
        try {
            fs.rmSync(root, { recursive: true, force: true });
            return;
        } catch (e) {
            if (attempt === 5) {
                throw e;
            }
            execFileSync('bash', ['-c', 'sleep 0.5'], { stdio: 'ignore' });
        }
    }
}

/** fixture：scripts/shared/sse-manager.js + 引用它的 test.user.js（可注入 drift）。 */
function makeSyncFixture(scriptContent, userJsContent) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv sync fixture '));
    fs.mkdirSync(path.join(dir, 'scripts', 'shared'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'scripts', 'shared', 'sse-manager.js'), 'export const sse = 1;\n', 'utf8');
    fs.writeFileSync(path.join(dir, 'scripts', 'sync-shared-snippets.ps1'), scriptContent, 'utf8');
    fs.writeFileSync(path.join(dir, 'test.user.js'), userJsContent, 'utf8');
    return dir;
}

function runCheck(fixture) {
    return spawnSync('pwsh', ['-NoProfile', '-File', path.join(fixture, 'scripts', 'sync-shared-snippets.ps1'), '-Check'],
        { cwd: fixture, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
}

const GOOD_USER_JS = '// >>> SHARED:sse-manager.js\nexport const sse = 1;\n// <<< SHARED:sse-manager.js';
const DRIFTED_USER_JS = '// >>> SHARED:sse-manager.js\n// stale block\n// <<< SHARED:sse-manager.js\n';
const STUB = 'exit 0\n';

test('sync-shared-snippets.ps1 -Check：drift 必须 exit != 0；合法同步后 -Check exit 0', { skip: !hasPwsh() && 'pwsh 不可用' }, () => {
    const fixture = makeSyncFixture(fs.readFileSync(SYNC, 'utf8'), DRIFTED_USER_JS);
    try {
        const drift = runCheck(fixture);
        assert.notEqual(drift.status, 0,
            'drift fixture 必须被 -Check 拒绝（exit != 0）: ' + (drift.stdout + drift.stderr));
        assert.match(drift.stdout + drift.stderr, /Drift/);

        const sync = spawnSync('pwsh', ['-NoProfile', '-File', path.join(fixture, 'scripts', 'sync-shared-snippets.ps1')],
            { cwd: fixture, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
        assert.equal(sync.status, 0, '合法同步必须 exit 0: ' + (sync.stdout + sync.stderr));

        const recheck = runCheck(fixture);
        assert.equal(recheck.status, 0, '同步后 -Check 必须 exit 0: ' + (recheck.stdout + recheck.stderr));
    } finally {
        cleanRepo(fixture);
    }
});

test('sync-shared-snippets.ps1 -Check：无 drift 时 exit 0', { skip: !hasPwsh() && 'pwsh 不可用' }, () => {
    const fixture = makeSyncFixture(fs.readFileSync(SYNC, 'utf8'), GOOD_USER_JS);
    try {
        const result = runCheck(fixture);
        assert.equal(result.status, 0, '无 drift 时 -Check 必须 exit 0: ' + (result.stdout + result.stderr));
    } finally {
        cleanRepo(fixture);
    }
});

test('sync-shared-snippets.ps1 恶意 stub：drift 不被发现（exit 0）→ 必须由 trusted contract 内容守卫拒绝', { skip: !hasPwsh() && 'pwsh 不可用' }, () => {
    const fixture = makeSyncFixture(STUB, DRIFTED_USER_JS);
    try {
        const result = runCheck(fixture);
        assert.equal(result.status, 0, '测试前提：stub 在 drift 下静默通过（证明 stub 是空操作）');
    } finally {
        cleanRepo(fixture);
    }
});
