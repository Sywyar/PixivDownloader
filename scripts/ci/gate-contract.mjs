#!/usr/bin/env node
'use strict';

import { execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const cliArgs = process.argv.slice(2);
const repoIndex = cliArgs.indexOf('--repo-root');
const dispatchRoot = path.resolve(repoIndex >= 0 ? cliArgs[repoIndex + 1] : process.cwd());

if (process.argv.length === 3 && process.argv[2] === '--version') {
    console.log('gate-contract 6');
} else if (configuredEpoch(dispatchRoot) !== '5') {
    await import('../i18n/gate-contract.mjs');
} else {
    const args = cliArgs;
    const value = (name) => {
        const index = args.indexOf(name);
        return index >= 0 ? args[index + 1] : null;
    };
    const repo = path.resolve(value('--repo-root') || process.cwd());
    const trusted = git(repo, ['config', '--local', '--get', 'pixiv.release.trustedGateRef']);
    let candidate = value('--candidate-ref');
    if (!candidate && value('--candidate-snapshot') === 'index') {
        const tree = git(repo, ['write-tree']);
        candidate = git(repo, ['commit-tree', tree, '-p', trusted], {
            GIT_AUTHOR_NAME: 'PixivDownloader Gate', GIT_AUTHOR_EMAIL: 'gate@localhost',
            GIT_COMMITTER_NAME: 'PixivDownloader Gate', GIT_COMMITTER_EMAIL: 'gate@localhost',
        }, 'local staged snapshot\n');
    }
    if (!candidate) throw new Error('--candidate-ref or --candidate-snapshot index is required');
    const candidateSha = git(repo, ['rev-parse', '--verify', `${candidate}^{commit}`]);
    const trustedSha = git(repo, ['rev-parse', '--verify', `${trusted}^{commit}`]);
    const ownRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
    const verifyArgs = [path.join(ownRoot, 'scripts', 'ci', 'release-gate-verifier.mjs'),
        '--repo-root', repo, '--candidate-ref', candidateSha];
    if (candidateSha === trustedSha) verifyArgs.push('--invariants');
    else verifyArgs.push('--trusted-ref', trustedSha);
    const result = spawnSync(process.execPath, verifyArgs,
        { cwd: repo, stdio: 'inherit', windowsHide: true });
    if (result.status !== 0) process.exit(result.status || 1);
    console.log(`GATE CONTRACT OK (candidate ${candidateSha})`);
}

function git(root, args, env = {}, input) {
    return execFileSync('git', args, {
        cwd: root, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], input,
        env: { ...process.env, ...env },
    }).trim();
}

function configuredEpoch(root) {
    try {
        return git(root, ['config', '--local', '--get', 'pixiv.release.trustedGateEpoch']);
    } catch {
        return '';
    }
}
