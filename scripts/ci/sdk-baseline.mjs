#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { collectApiSurface } from './sdk-api-surface.mjs';
import { compareVersions, evaluateContract, readBaseline } from './sdk-contract.mjs';
import { inspectSdkVersion, readSdkIdentity } from './sdk-version.mjs';

export function updateBaseline(repoRoot, publishedRef) {
    const identity = inspectSdkVersion(repoRoot);
    if (identity.prerelease) throw new Error('A prerelease cannot update the stable SDK baseline');

    // 只用实际发行的源码冻结合同，开发提交不冻结仍未发布的版本。
    if (!/^[0-9a-f]{40}$/u.test(publishedRef ?? '')) throw new Error('A published SDK source commit is required');
    execFileSync('git', ['-C', repoRoot, 'merge-base', '--is-ancestor', publishedRef, 'HEAD']);
    const previous = readSdkIdentity(repoRoot, publishedRef, true);
    if (compareVersions(identity, previous) < 0) {
        throw new Error(`SDK version must increase from ${previous.version} to a newer identity`);
    }
    const directory = `sdk-baselines/v${identity.major}`;
    const base = readBaseline(repoRoot, directory, publishedRef);
    const surface = collectApiSurface(['sdk-info', 'plugin-api', 'core-api'].map(name => ({
        name,
        path: path.join(repoRoot, `pixivdownload-${name}`, 'target',
                `pixivdownload-${name}-${identity.version}.jar`)
    })));
    if (base) {
        evaluateContract({
            baseIdentity: base.identity,
            candidateIdentity: identity,
            baseSurface: base.surface,
            candidateSurface: surface,
            stableBaseline: base,
            requireReleaseIdentity: true
        });
    }

    const destination = path.join(repoRoot, directory);
    fs.mkdirSync(destination, { recursive: true });
    fs.writeFileSync(path.join(destination, 'api-surface.txt'), surface, 'utf8');
    fs.writeFileSync(path.join(destination, 'metadata.json'),
            `${JSON.stringify({ schemaVersion: 1, sdkVersion: identity.version }, null, 2)}\n`, 'utf8');
    return { version: identity.version, directory };
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    try {
        if (process.argv.length !== 2) throw new Error('Usage: npm run sdk:baseline');
        const repoRoot = process.cwd();
        const publishedRef = execFileSync(process.execPath, [
            fileURLToPath(new URL('./sdk-published-base.mjs', import.meta.url)), 'latest', repoRoot
        ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
        const result = updateBaseline(repoRoot, publishedRef);
        process.stdout.write(`Updated stable SDK ${result.version} baseline in ${result.directory}\n`);
    } catch (error) {
        process.stderr.write(`${error.message}\n`);
        process.exitCode = 1;
    }
}
