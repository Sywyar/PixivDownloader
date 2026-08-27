#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { inspectSdkVersion, parseSdkVersion, readSdkIdentity } from './sdk-version.mjs';

function lines(text) {
    return new Set(text.split(/\r?\n/u).filter(Boolean));
}

export function compareSurfaces(baseSurface, candidateSurface) {
    const base = lines(baseSurface);
    const candidate = lines(candidateSurface);
    return Object.freeze({
        additions: [...candidate].filter((entry) => !base.has(entry)).sort(),
        removals: [...base].filter((entry) => !candidate.has(entry)).sort()
    });
}

function compareVersions(left, right) {
    for (const key of ['major', 'minor', 'patch']) {
        if (left[key] !== right[key]) {
            return left[key] < right[key] ? -1 : 1;
        }
    }
    if (!left.prerelease && !right.prerelease) return 0;
    if (!left.prerelease) return 1;
    if (!right.prerelease) return -1;
    const order = { alpha: 0, beta: 1, rc: 2 };
    if (order[left.prereleaseChannel] !== order[right.prereleaseChannel]) {
        return order[left.prereleaseChannel] < order[right.prereleaseChannel] ? -1 : 1;
    }
    return Math.sign(left.prereleaseSequence - right.prereleaseSequence);
}

export function evaluateContract({
    baseIdentity,
    candidateIdentity,
    baseSurface,
    candidateSurface,
    stableBaseline = null
}) {
    const predecessorDiff = compareSurfaces(baseSurface, candidateSurface);
    const surfaceChanged = predecessorDiff.additions.length > 0 || predecessorDiff.removals.length > 0;
    const identityChanged = baseIdentity.releaseId !== candidateIdentity.releaseId;
    if (surfaceChanged && !identityChanged) {
        throw new Error('Public SDK surface changed without a new SDK release identity');
    }
    if (identityChanged && !baseIdentity.legacyRevision && compareVersions(candidateIdentity, baseIdentity) <= 0) {
        throw new Error(`SDK version must increase from ${baseIdentity.version} to a newer identity`);
    }

    let stableDiff = null;
    if (stableBaseline) {
        if (stableBaseline.identity.major !== candidateIdentity.major) {
            throw new Error('Stable SDK baseline major does not match the candidate major');
        }
        stableDiff = compareSurfaces(stableBaseline.surface, candidateSurface);
        if (stableDiff.removals.length > 0) {
            throw new Error('Candidate removes public API from the stable same-major baseline');
        }
        if (stableDiff.additions.length > 0
                && candidateIdentity.minor <= stableBaseline.identity.minor) {
            throw new Error('Compatible public API additions require a higher SDK minor version');
        }
    }
    return Object.freeze({
        outcome: identityChanged ? 'PUBLISH' : 'NO_PUBLISH',
        identityChanged,
        surfaceChanged,
        predecessorDiff,
        stableDiff
    });
}

function readAtRef(repoRoot, relativePath, ref) {
    if (!ref) {
        const file = path.join(repoRoot, ...relativePath.split('/'));
        return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
    }
    try {
        return execFileSync('git', ['-C', repoRoot, 'show', `${ref}:${relativePath}`], {
            encoding: 'utf8',
            stdio: ['ignore', 'pipe', 'ignore']
        });
    } catch {
        return null;
    }
}

function readBaseline(repoRoot, directory, ref) {
    const metadataText = readAtRef(repoRoot, `${directory}/metadata.json`, ref);
    const surface = readAtRef(repoRoot, `${directory}/api-surface.txt`, ref);
    if (!metadataText && !surface) return null;
    if (!metadataText || !surface) throw new Error(`Stable SDK baseline is incomplete: ${directory}`);
    const metadata = JSON.parse(metadataText);
    if (metadata.schemaVersion !== 1 || typeof metadata.sdkVersion !== 'string') {
        throw new Error(`Invalid stable SDK baseline metadata: ${directory}`);
    }
    const identity = parseSdkVersion(metadata.sdkVersion);
    if (identity.prerelease) throw new Error(`Invalid stable SDK baseline identity: ${metadata.sdkVersion}`);
    return { identity, metadataText, surface };
}

export function evaluateBaselineState({ base, candidate, candidateIdentity, candidateSurface, directory }) {
    if (candidateIdentity.prerelease) {
        if (!base && candidate) throw new Error('A prerelease cannot establish a stable SDK baseline');
        if (base && (!candidate
                || candidate.metadataText !== base.metadataText
                || candidate.surface !== base.surface)) {
            throw new Error('A prerelease cannot modify the stable SDK baseline');
        }
        return base;
    }
    if (!candidate) throw new Error(`Stable SDK ${candidateIdentity.version} must materialize ${directory}`);
    if (candidate.identity.version !== candidateIdentity.version || candidate.surface !== candidateSurface) {
        throw new Error(`Stable SDK ${candidateIdentity.version} must update ${directory}/api-surface.txt`);
    }
    return base;
}

function stableBaseline(repoRoot, candidateIdentity, baseRef, candidateRef, candidateSurface) {
    const directory = `sdk-baselines/v${candidateIdentity.major}`;
    const base = readBaseline(repoRoot, directory, baseRef);
    const candidate = readBaseline(repoRoot, directory, candidateRef);
    return evaluateBaselineState({ base, candidate, candidateIdentity, candidateSurface, directory });
}

function parseArguments(argv) {
    const options = { repoRoot: '.', baseRef: '', candidateRef: '', baseSurface: '', candidateSurface: '', report: '' };
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        const key = {
            '--repo-root': 'repoRoot',
            '--base-ref': 'baseRef',
            '--candidate-ref': 'candidateRef',
            '--base-surface': 'baseSurface',
            '--candidate-surface': 'candidateSurface',
            '--report': 'report'
        }[argument];
        if (!key) throw new Error(`Unknown argument: ${argument}`);
        options[key] = argv[++index] ?? '';
    }
    if (!options.baseRef || !options.baseSurface || !options.candidateSurface || !options.report) {
        throw new Error('--base-ref, --base-surface, --candidate-surface and --report are required');
    }
    return options;
}

function main() {
    const options = parseArguments(process.argv.slice(2));
    const repoRoot = path.resolve(options.repoRoot);
    const baseIdentity = readSdkIdentity(repoRoot, options.baseRef, true);
    const candidateIdentity = inspectSdkVersion(repoRoot, options.candidateRef);
    const baseSurface = fs.readFileSync(path.resolve(options.baseSurface), 'utf8');
    const candidateSurface = fs.readFileSync(path.resolve(options.candidateSurface), 'utf8');
    const baseline = stableBaseline(
            repoRoot,
            candidateIdentity,
            options.baseRef,
            options.candidateRef,
            candidateSurface
    );
    const result = evaluateContract({ baseIdentity, candidateIdentity, baseSurface, candidateSurface,
        stableBaseline: baseline });
    const report = {
        schemaVersion: 1,
        baseReleaseId: baseIdentity.releaseId,
        candidateReleaseId: candidateIdentity.releaseId,
        ...result
    };
    fs.mkdirSync(path.dirname(path.resolve(options.report)), { recursive: true });
    fs.writeFileSync(path.resolve(options.report), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
    process.stdout.write(`${result.outcome}: ${candidateIdentity.releaseId}\n`);
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    try {
        main();
    } catch (error) {
        process.stderr.write(`${error.message}\n`);
        process.exitCode = 1;
    }
}
