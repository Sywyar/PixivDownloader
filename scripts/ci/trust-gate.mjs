#!/usr/bin/env node
'use strict';

import { execFileSync } from 'node:child_process';

if (process.argv.length === 3 && process.argv[2] === '--version') {
    console.log('trusted-release-gate 5');
} else if (process.argv.includes('--version')) {
    console.error('Usage: trust-gate.mjs --version');
    process.exitCode = 2;
} else if (process.argv.includes('--adopt-root') || configuredEpoch() === '5') {
    await import('./release-gate-trust.mjs');
} else {
    await import('../i18n/trust-gate.mjs');
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
