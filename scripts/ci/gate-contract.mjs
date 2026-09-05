#!/usr/bin/env node
'use strict';

import { execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import fs from 'node:fs';
import os from 'node:os';
import { withTrustedGate } from './trusted-gate-runner.mjs';

const cliArgs = process.argv.slice(2);
const repoIndex = cliArgs.indexOf('--repo-root');
const dispatchRoot = path.resolve(repoIndex >= 0 ? cliArgs[repoIndex + 1] : process.cwd());

if (process.argv.length === 3 && process.argv[2] === '--version') {
    const policy = JSON.parse(fs.readFileSync(new URL('./release-gate-policy.json', import.meta.url), 'utf8'));
    console.log(`gate-contract ${policy.contractVersion}`);
} else if (!['5', '8'].includes(configuredEpoch(dispatchRoot))) {
    throw new Error('configured release Gate epoch 5 or 8 is required');
} else {
    const args = cliArgs;
    const value = (name) => {
        const index = args.indexOf(name);
        return index >= 0 ? args[index + 1] : null;
    };
    const repo = path.resolve(value('--repo-root') || process.cwd());
    const trusted = git(repo, ['config', '--local', '--get', 'pixiv.release.trustedGateRef']);
    let candidate = value('--candidate-ref');
    const snapshot = value('--candidate-snapshot');
    if (!candidate && ['index', 'worktree'].includes(snapshot)) {
        let tree;
        if (snapshot === 'index') tree = git(repo, ['write-tree']);
        else {
            const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-gate-index-'));
            try {
                const env = { GIT_INDEX_FILE: path.join(temporary, 'index') };
                git(repo, ['read-tree', 'HEAD'], env);
                git(repo, ['add', '--all', '--', '.'], env);
                tree = git(repo, ['write-tree'], env);
            } finally { fs.rmSync(temporary, { recursive: true, force: true }); }
        }
        candidate = git(repo, ['commit-tree', tree, '-p', trusted], {
            GIT_AUTHOR_NAME: 'PixivDownloader Gate', GIT_AUTHOR_EMAIL: 'gate@localhost',
            GIT_COMMITTER_NAME: 'PixivDownloader Gate', GIT_COMMITTER_EMAIL: 'gate@localhost',
        }, 'local staged snapshot\n');
    }
    if (!candidate) throw new Error('--candidate-ref or --candidate-snapshot index is required');
    const candidateSha = git(repo, ['rev-parse', '--verify', `${candidate}^{commit}`]);
    const trustedSha = git(repo, ['rev-parse', '--verify', `${trusted}^{commit}`]);
    const policy = JSON.parse(git(repo, ['show', `${candidateSha}:scripts/ci/release-gate-policy.json`]));
    withTrustedGate(repo, trustedSha, policy.gateEpoch, (directory, env) => {
        const verifyArgs = [path.join(directory, 'scripts/ci/release-gate-verifier.mjs'),
            '--repo-root', repo, '--candidate-ref', candidateSha];
        if (candidateSha === trustedSha) verifyArgs.push('--invariants');
        else verifyArgs.push('--trusted-ref', trustedSha);
        if (policy.gateEpoch === 8 && snapshot) verifyArgs.push('--local-feedback');
        if (args.includes('--signature')) verifyArgs.push('--signature');
        const result = spawnSync(process.execPath, verifyArgs,
            { cwd: repo, env, stdio: 'inherit', windowsHide: true });
        if (result.status !== 0) throw new Error('protected predecessor rejected the candidate');
    });
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
