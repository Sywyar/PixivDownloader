import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { materializeGate, readPolicy } from './gate-upgrade.mjs';

export function withTrustedGate(repo, base, candidate, action, { localFeedback = false } = {}) {
    const read = (rel) => execFileSync('git', ['-C', repo, 'show', `${base}:${rel}`],
        { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const policy = JSON.parse(read('scripts/ci/release-gate-policy.json').toString('utf8'));
    const candidateEpoch = readPolicy(repo, candidate).gateEpoch;
    const admission = policy.gateEpoch === 5 && candidateEpoch === 8;
    const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-protected-gate-'));
    const directory = path.join(temporary, 'core');
    try {
        if (!admission) materializeGate({ repo, trusted: base, candidate, directory, localFeedback });
        else {
            for (const name of ['release-gate-trust.mjs', 'release-gate-verifier.mjs', 'resolve-trusted-base.mjs']) {
                const target = path.join(directory, 'scripts/ci', name);
                fs.mkdirSync(path.dirname(target), { recursive: true });
                fs.writeFileSync(target, read(`scripts/ci/gate-admission/${name}`));
            }
            for (const rel of ['package.json', 'package-lock.json']) fs.writeFileSync(path.join(directory, rel), read(rel));
        }
        const install = spawnSync(process.platform === 'win32'
            ? 'npm.cmd ci --offline --ignore-scripts --no-audit --no-fund' : 'npm',
            process.platform === 'win32' ? [] : ['ci', '--offline', '--ignore-scripts', '--no-audit', '--no-fund'], {
                cwd: directory, shell: process.platform === 'win32', windowsHide: true,
                stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8',
            });
        if (install.status !== 0) throw new Error('protected parser is unavailable offline; install the protected base dependencies first');
        return action(directory, { ...process.env,
            TRUSTED_GATE_PACKAGE_JSON: path.join(directory, 'package.json') });
    } finally {
        fs.rmSync(temporary, { recursive: true, force: true });
    }
}

export function verifyProtectedCandidate({ repo, trusted, candidate, ...options }) {
    return withTrustedGate(repo, trusted, candidate, (directory, env) => {
        const result = spawnSync(process.execPath, [path.join(directory, 'scripts/ci/release-gate-verifier.mjs'),
            '--repo-root', repo, '--trusted-ref', trusted, '--candidate-ref', candidate], {
            cwd: repo, env, windowsHide: true, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
        });
        if (result.status !== 0) throw new Error(result.stderr || 'protected verifier rejected the candidate');
        return readPolicy(repo, candidate);
    }, options);
}
