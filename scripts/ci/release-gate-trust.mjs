#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { readGit, readPolicy } from './gate-upgrade.mjs';
import { verifyCandidate } from './release-gate-verifier.mjs';

const REF_KEY = 'pixiv.release.trustedGateRef';
const EPOCH_KEY = 'pixiv.release.trustedGateEpoch';
function fail(message) { throw new Error(message); }
function main() {
    const repo = readGit(process.cwd(), ['rev-parse', '--show-toplevel']).trim();
    const config = (key) => readGit(repo, ['config', '--local', '--get', key]).trim();
    const commit = (ref) => readGit(repo, ['rev-parse', '--verify', '--end-of-options', ref + '^{commit}']).trim();
    const args = process.argv.slice(2);
    if (args.length !== 3 || !['--advance', '--adopt-root'].includes(args[0]) || args[1] !== '--ref') {
        fail('usage: release-gate-trust.mjs --advance|--adopt-root --ref <commit>');
    }
    if (process.env.CI === 'true') fail('anchor changes are forbidden in CI');
    if (readGit(repo, ['status', '--porcelain']).trim()) fail('trust commands require a clean worktree');
    const trusted = commit(config(REF_KEY)), candidate = commit(args[2]);
    const before = readPolicy(repo, trusted), after = readPolicy(repo, candidate);
    if (config(EPOCH_KEY) !== String(before.gateEpoch)
        || readGit(repo, ['symbolic-ref', '--quiet', 'HEAD']).trim() !== 'refs/heads/master'
        || candidate !== commit('HEAD') || candidate !== commit('refs/remotes/origin/master')) {
        fail('anchor changes require the configured predecessor and protected master tip');
    }
    if ((args[0] === '--adopt-root') !== (before.gateEpoch !== after.gateEpoch)) {
        fail('use --adopt-root for an approved epoch transition, otherwise --advance');
    }
    verifyCandidate({ repo, trusted, candidate });
    const common = readGit(repo, ['rev-parse', '--path-format=absolute', '--git-common-dir']).trim();
    const file = path.join(common, 'config'), lock = file + '.lock';
    const fd = fs.openSync(lock, 'wx');
    try {
        try { fs.writeFileSync(fd, fs.readFileSync(file)); } finally { fs.closeSync(fd); }
        // 锁内再次核对原锚点，验证期间的并发推进不能被覆盖。
        for (const [key, expected] of [[REF_KEY, trusted], [EPOCH_KEY, String(before.gateEpoch)]]) {
            if (readGit(repo, ['config', '--file', lock, '--get', key]).trim() !== expected) fail('anchor changed during verification');
        }
        readGit(repo, ['config', '--file', lock, EPOCH_KEY, String(after.gateEpoch)]);
        readGit(repo, ['config', '--file', lock, REF_KEY, candidate]);
        fs.renameSync(lock, file);
    } finally {
        if (fs.existsSync(lock)) fs.unlinkSync(lock);
    }
    if (config(REF_KEY) !== candidate || config(EPOCH_KEY) !== String(after.gateEpoch)) fail('anchor update did not persist');
    console.log('release-gate-trust: trusted anchor advanced to ' + candidate + ' (epoch ' + after.gateEpoch + ')');
}
try { main(); } catch (error) { console.error('release-gate-trust: ' + error.message); process.exitCode = 1; }
