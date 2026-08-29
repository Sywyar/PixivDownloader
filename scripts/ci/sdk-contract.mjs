#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { inspectSdkVersion, parseSdkVersion, readSdkIdentity, SDK_GROUP_ID } from './sdk-version.mjs';

const CONSUMER_POM_MODULES = [
    'pixivdownload-sdk-info',
    'pixivdownload-plugin-api',
    'pixivdownload-core-api',
    'pixivdownload-sdk-bom'
];

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

function element(xml, name, fallback = '') {
    const match = new RegExp(`<${name}(?:\\s[^>]*)?>\\s*([^<]*?)\\s*</${name}>`, 'u').exec(xml);
    return match ? match[1].trim() : fallback;
}

function requiredElement(xml, name, owner) {
    const value = element(xml, name);
    if (!value) throw new Error(`${owner} must declare ${name}`);
    return value;
}

function section(xml, name) {
    const match = new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`, 'u').exec(xml);
    return match ? { source: match[0], body: match[1] } : null;
}

function dependencySurface(xml, kind, sdkVersion) {
    const dependencies = section(xml, 'dependencies')?.body ?? '';
    return [...dependencies.matchAll(/<dependency(?:\s[^>]*)?>([\s\S]*?)<\/dependency>/gu)]
            .map((match) => {
                const body = match[1];
                const groupId = requiredElement(body, 'groupId', `${kind} dependency`);
                const artifactId = requiredElement(body, 'artifactId', `${kind} dependency`);
                let version = element(body, 'version');
                if (groupId === SDK_GROUP_ID && version === sdkVersion) version = '@SDK_VERSION@';
                const exclusions = [...body.matchAll(/<exclusion(?:\s[^>]*)?>([\s\S]*?)<\/exclusion>/gu)]
                        .map((exclusion) => `${requiredElement(exclusion[1], 'groupId', 'dependency exclusion')}`
                                + `:${requiredElement(exclusion[1], 'artifactId', 'dependency exclusion')}`)
                        .sort();
                return [
                    kind,
                    groupId,
                    artifactId,
                    version,
                    element(body, 'type', 'jar'),
                    element(body, 'classifier'),
                    element(body, 'scope', 'compile'),
                    element(body, 'optional', 'false'),
                    element(body, 'systemPath'),
                    exclusions.join(',')
                ].join('\t');
            })
            .sort();
}

export function consumerPomSurface(pom, sdkVersion) {
    const management = section(pom, 'dependencyManagement');
    const directPom = management ? pom.replace(management.source, '') : pom;
    return [
        [
            'PROJECT',
            requiredElement(pom, 'groupId', 'consumer POM'),
            requiredElement(pom, 'artifactId', 'consumer POM'),
            element(pom, 'packaging', 'jar')
        ].join('\t'),
        ...dependencySurface(directPom, 'DEPENDENCY', sdkVersion),
        ...dependencySurface(management?.body ?? '', 'MANAGED_DEPENDENCY', sdkVersion)
    ].sort().join('\n');
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
    mavenContractChanges = [],
    stableBaseline = null
}) {
    const predecessorDiff = compareSurfaces(baseSurface, candidateSurface);
    const surfaceChanged = predecessorDiff.additions.length > 0 || predecessorDiff.removals.length > 0;
    const identityChanged = baseIdentity.releaseId !== candidateIdentity.releaseId;
    if (surfaceChanged && !identityChanged) {
        throw new Error('Public SDK surface changed without a new SDK release identity');
    }
    if (mavenContractChanges.length > 0 && !identityChanged) {
        throw new Error(`Maven SDK consumer contract changed without a new SDK release identity: ${mavenContractChanges.join(', ')}`);
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
        mavenContractChanges,
        predecessorDiff,
        stableDiff
    });
}

export function changedMavenContracts(baseSdkRoot, candidateSdkRoot, baseSdkVersion, candidateSdkVersion,
        allowMissingBase = false) {
    const changes = [];
    for (const module of CONSUMER_POM_MODULES) {
        const relative = path.join(module, 'target', 'flattened-pom.xml');
        const basePath = path.join(baseSdkRoot, relative);
        const candidatePath = path.join(candidateSdkRoot, relative);
        if (!fs.existsSync(candidatePath)) {
            throw new Error(`Missing candidate consumer POM: ${relative}`);
        }
        if (!fs.existsSync(basePath)) {
            if (!allowMissingBase) throw new Error(`Missing base consumer POM: ${relative}`);
            changes.push(`${module}/maven-consumer-contract`);
            continue;
        }
        const baseSurface = consumerPomSurface(fs.readFileSync(basePath, 'utf8'), baseSdkVersion);
        const candidateSurface = consumerPomSurface(fs.readFileSync(candidatePath, 'utf8'), candidateSdkVersion);
        if (baseSurface !== candidateSurface) {
            changes.push(`${module}/maven-consumer-contract`);
        }
    }
    return changes;
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
    const options = { repoRoot: '.', baseRef: '', candidateRef: '', baseSurface: '', candidateSurface: '',
        baseSdkRoot: '', candidateSdkRoot: '', report: '' };
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        const key = {
            '--repo-root': 'repoRoot',
            '--base-ref': 'baseRef',
            '--candidate-ref': 'candidateRef',
            '--base-surface': 'baseSurface',
            '--candidate-surface': 'candidateSurface',
            '--base-sdk-root': 'baseSdkRoot',
            '--candidate-sdk-root': 'candidateSdkRoot',
            '--report': 'report'
        }[argument];
        if (!key) throw new Error(`Unknown argument: ${argument}`);
        options[key] = argv[++index] ?? '';
    }
    if (!options.baseRef || !options.baseSurface || !options.candidateSurface || !options.baseSdkRoot
            || !options.candidateSdkRoot || !options.report) {
        throw new Error('--base-ref, both surfaces, both SDK roots and --report are required');
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
        mavenContractChanges: changedMavenContracts(
                path.resolve(options.baseSdkRoot),
                path.resolve(options.candidateSdkRoot),
                baseIdentity.version,
                candidateIdentity.version,
                Boolean(baseIdentity.legacyRevision)),
        stableBaseline: baseline });
    const report = {
        schemaVersion: 2,
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
