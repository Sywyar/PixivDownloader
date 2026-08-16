#!/usr/bin/env node
'use strict';

import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const VERSION = '5';
const OWN_DIR = path.dirname(fileURLToPath(import.meta.url));
const CORE_ROOT = path.resolve(OWN_DIR, '..', '..');

function git(root, args) {
    return execFileSync('git', args, {
        cwd: root, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
}

function parseArgs(argv) {
    const args = { repoRoot: null, candidateRef: null, snapshot: null, reportRoot: null, version: false };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        if (arg === '--repo-root') args.repoRoot = argv[++i];
        else if (arg === '--candidate-ref') args.candidateRef = argv[++i];
        else if (arg === '--candidate-snapshot') args.snapshot = argv[++i];
        else if (arg === '--report-root') args.reportRoot = argv[++i];
        else if (arg === '--force-self-protection') continue;
        else if (arg === '--version') args.version = true;
        else throw new Error('unknown argument: ' + arg);
    }
    if (!args.version && (!args.repoRoot || (!args.candidateRef && args.snapshot !== 'index'))) {
        throw new Error('--repo-root and a candidate ref or index snapshot are required');
    }
    return args;
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (args.version) {
        console.log(`i18n-gate-contract ${VERSION}`);
        return;
    }
    const repoRoot = path.resolve(args.repoRoot);
    const policy = JSON.parse(fs.readFileSync(path.join(OWN_DIR, 'gate-policy.json'), 'utf8'));
    const configuredEpoch = (() => {
        try {
            return git(repoRoot, ['config', '--local', '--get', 'pixiv.i18n.trustedGateEpoch']);
        } catch {
            return '';
        }
    })();
    const parity = path.join(CORE_ROOT, 'scripts', 'ci', 'gate-parity.mjs');
    const childArgs = [parity, '--repo-root', repoRoot];
    if (args.candidateRef) childArgs.push('--candidate-ref', args.candidateRef);
    else childArgs.push('--candidate-snapshot', 'index');
    if (args.reportRoot) childArgs.push('--report-root', path.resolve(args.reportRoot));
    if (configuredEpoch !== String(policy.gateEpoch)) {
        childArgs.push('--invariants');
    } else {
        const trustedRef = git(repoRoot, ['config', '--local', '--get', 'pixiv.i18n.trustedGateRef']);
        childArgs.push('--trusted-dir', CORE_ROOT, '--trusted-ref', trustedRef);
    }
    const result = spawnSync(process.execPath, childArgs, {
        cwd: repoRoot, encoding: 'utf8', stdio: 'inherit', maxBuffer: 128 * 1024 * 1024,
    });
    if (result.status !== 0) process.exit(result.status || 1);
    console.log(`GATE CONTRACT OK (candidate ${args.candidateRef || 'index'})`);
}

try {
    main();
} catch (error) {
    console.error('gate-contract ERROR: ' + error.message);
    process.exit(2);
}
