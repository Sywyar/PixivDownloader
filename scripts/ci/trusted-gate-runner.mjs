import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';

export function withTrustedGate(repo, base, candidateEpoch, action) {
    const read = (rel) => execFileSync('git', ['-C', repo, 'show', `${base}:${rel}`],
        { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const policy = JSON.parse(read('scripts/ci/release-gate-policy.json').toString('utf8'));
    if (![5, 6].includes(policy.gateEpoch) || ![5, 6].includes(candidateEpoch)) {
        throw new Error('unsupported trusted gate epoch');
    }
    const admission = policy.gateEpoch === 5 && candidateEpoch === 6;
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-protected-gate-'));
    try {
        for (const name of ['release-gate-trust.mjs', 'release-gate-verifier.mjs', 'resolve-trusted-base.mjs']) {
            const rel = `scripts/ci/${name}`;
            const target = path.join(directory, rel);
            fs.mkdirSync(path.dirname(target), { recursive: true });
            fs.writeFileSync(target, read(admission ? `scripts/ci/gate-admission/${name}` : rel));
        }
        for (const rel of ['package.json', 'package-lock.json']) fs.writeFileSync(path.join(directory, rel), read(rel));
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
        fs.rmSync(directory, { recursive: true, force: true });
    }
}
