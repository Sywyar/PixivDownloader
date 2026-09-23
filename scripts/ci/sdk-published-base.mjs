#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { inspectSdkVersion, parseSdkVersion, SDK_ARTIFACTS, SDK_GROUP_ID } from './sdk-version.mjs';
import { compareVersions } from './sdk-contract.mjs';

const REPOSITORY = 'Sywyar/PixivDownloader-Plugin-SDK';

function gh(...args) {
    return execFileSync('gh', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

export function selectPublishedRelease(releases, identity, mode) {
    const published = releases.filter(release => !release.draft && release.tag_name?.startsWith('sdk-api-v'));
    if (mode === 'current') {
        const current = published.find(release => release.tag_name === identity.releaseId);
        if (!current) throw new Error(`SDK ${identity.releaseId} has no public Release`);
        return current;
    }
    const comparable = published.flatMap(release => {
        try { return [{ release, version: parseSdkVersion(release.tag_name.slice('sdk-api-v'.length)) }]; }
        catch { return []; }
    });
    comparable.sort((left, right) => compareVersions(right.version, left.version));
    if (comparable.length === 0) throw new Error('No public SDK Release can establish the publication baseline');
    return comparable[0].release;
}

export function verifyPublishedMetadata(release, metadata) {
    const version = release.tag_name.slice('sdk-api-v'.length);
    const assets = new Set(release.assets.map(asset => asset.name));
    for (const name of [`PixivDownloader-Plugin-SDK-${version}.zip`, 'sdk-release.json', 'SHA256SUMS']) {
        if (!assets.has(name)) throw new Error(`Public SDK Release lacks ${name}`);
    }
    if (metadata.releaseId !== release.tag_name || metadata.sdkVersion !== version
            || !/^[0-9a-f]{40}$/u.test(metadata.sourceCommitSha)) {
        throw new Error('Public SDK Release metadata does not match its identity');
    }
    return metadata.sourceCommitSha;
}

async function verifyCentral(version, sourceSha) {
    const groupPath = SDK_GROUP_ID.replaceAll('.', '/');
    for (const [artifact] of SDK_ARTIFACTS) {
        const url = `https://repo1.maven.org/maven2/${groupPath}/${artifact}/${version}/${artifact}-${version}.pom`;
        const response = await fetch(url, { signal: AbortSignal.timeout(60_000) });
        if (!response.ok) throw new Error(`Maven Central lacks ${artifact}:${version}: HTTP ${response.status}`);
        const pom = await response.text();
        if (!pom.includes(`<tag>${sourceSha}</tag>`)) {
            throw new Error(`Maven Central ${artifact}:${version} does not identify the published source`);
        }
    }
}

async function main() {
    const [mode, repoRoot = '.'] = process.argv.slice(2);
    if (!['current', 'latest'].includes(mode)) throw new Error('Usage: sdk-published-base.mjs current|latest [repo-root]');
    const identity = inspectSdkVersion(repoRoot);
    const releases = JSON.parse(gh('api', '--paginate', '--slurp', `repos/${REPOSITORY}/releases?per_page=100`)).flat();
    const release = selectPublishedRelease(releases, identity, mode);
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-sdk-published-'));
    try {
        gh('release', 'download', release.tag_name, '--repo', REPOSITORY,
            '--pattern', 'sdk-release.json', '--dir', directory);
        const metadata = JSON.parse(fs.readFileSync(path.join(directory, 'sdk-release.json'), 'utf8'));
        const sourceSha = verifyPublishedMetadata(release, metadata);
        execFileSync('git', ['-C', repoRoot, 'merge-base', '--is-ancestor', sourceSha, 'HEAD']);
        await verifyCentral(release.tag_name.slice('sdk-api-v'.length), sourceSha);
        process.stdout.write(`${sourceSha}\n`);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main().catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
}
