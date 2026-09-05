#!/usr/bin/env node
'use strict';

import { execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import { withTrustedGate } from './trusted-gate-runner.mjs';

if (process.argv.length === 3 && process.argv[2] === '--version') {
    const policy = JSON.parse(fs.readFileSync(new URL('./release-gate-policy.json', import.meta.url), 'utf8'));
    console.log(`trusted-release-gate ${policy.gateEpoch}`);
} else if (process.argv.includes('--version')) {
    console.error('Usage: trust-gate.mjs --version');
    process.exitCode = 2;
} else if (['5', '7'].includes(configuredEpoch())) {
    const repo = git(['rev-parse', '--show-toplevel']);
    const args = process.argv.slice(2);
    const epoch = configuredEpoch();
    const base = git(['config', '--local', '--get', 'pixiv.release.trustedGateRef']);
    if (args.length === 1 && args[0] === '--show') {
        console.log(`trustedGateEpoch=${epoch}`);
        console.log(`trustedGateRef=${base}`);
        console.log(`root=${git(['rev-parse', `refs/tags/release-gate-epoch-${epoch}-root^{commit}`])}`);
    } else {
        if (args.length !== 3 || !['--advance', '--adopt-root'].includes(args[0]) || args[1] !== '--ref') {
            throw new Error('usage: trust-gate.mjs --show | --advance --ref <commit> | --adopt-root --ref <commit>');
        }
        const candidate = git(['rev-parse', '--verify', `${args[2]}^{commit}`]);
        const policy = JSON.parse(git(['show', `${candidate}:scripts/ci/release-gate-policy.json`]));
        withTrustedGate(repo, base, policy.gateEpoch, (directory, env) => {
            const result = spawnSync(process.execPath,
                [path.join(directory, 'scripts/ci/release-gate-trust.mjs'), args[0], '--ref', candidate],
                { cwd: repo, env, stdio: 'inherit', windowsHide: true });
            if (result.status !== 0) throw new Error('protected trust command rejected the operation');
        });
    }
} else {
    throw new Error('configured release Gate epoch 5 or 7 is required');
}

function git(args) {
    return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true }).trim();
}

function configuredEpoch() {
    try {
        return execFileSync('git', ['config', '--local', '--get', 'pixiv.release.trustedGateEpoch'], {
            encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'],
        }).trim();
    } catch {
        return '';
    }
}
