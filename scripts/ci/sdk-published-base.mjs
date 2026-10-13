#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { compareVersions, inspectSdkVersion, parseSdkVersion, readSdkIdentity, SDK_ARTIFACTS, SDK_GROUP_ID } from './sdk-version.mjs';
import { collectApiSurface } from './sdk-api-surface.mjs';
import { extractArchive } from './sdk-release.mjs';

const REPOSITORY = 'Sywyar/PixivDownloader-Plugin-SDK';

function gh(...args) {
    return execFileSync('gh', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

export function selectPublishedRelease(releases, identity, mode) {
    const published = releases.filter(release => !release.draft && release.tag_name?.startsWith('sdk-api-v'));
    if (mode === 'current' || mode === 'if-present') {
        const current = published.find(release => release.tag_name === identity.releaseId);
        if (!current && mode === 'current') throw new Error(`SDK ${identity.releaseId} has no public Release`);
        return current ?? null;
    }
    const comparable = published.flatMap(release => {
        try { return [{ release, version: parseSdkVersion(release.tag_name.slice('sdk-api-v'.length)) }]; }
        catch { return []; }
    });
    const candidates = mode === 'stable'
        ? comparable.filter(({ release, version }) => !release.prerelease && !version.prerelease
            && version.major === identity.major && compareVersions(version, identity) <= 0)
        : comparable;
    candidates.sort((left, right) => compareVersions(right.version, left.version));
    if (candidates.length === 0 && mode !== 'stable') {
        throw new Error('No public SDK Release can establish the publication baseline');
    }
    return candidates[0]?.release ?? null;
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

export async function resolvePublishedBase(mode, repoRoot, identity = inspectSdkVersion(repoRoot)) {
    const releases = JSON.parse(gh('api', '--paginate', '--slurp', `repos/${REPOSITORY}/releases?per_page=100`)).flat();
    const release = selectPublishedRelease(releases, identity, mode);
    if (!release) return null;
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-sdk-published-'));
    try {
        gh('release', 'download', release.tag_name, '--repo', REPOSITORY,
            '--pattern', 'sdk-release.json', '--dir', directory);
        const metadata = JSON.parse(fs.readFileSync(path.join(directory, 'sdk-release.json'), 'utf8'));
        const sourceSha = verifyPublishedMetadata(release, metadata);
        execFileSync('git', ['-C', repoRoot, 'merge-base', '--is-ancestor', sourceSha, 'HEAD']);
        const publishedIdentity = readSdkIdentity(repoRoot, sourceSha);
        if (publishedIdentity.releaseId !== release.tag_name) {
            throw new Error('Public SDK source does not match its release identity');
        }
        await verifyCentral(release.tag_name.slice('sdk-api-v'.length), sourceSha);
        return { identity: publishedIdentity, sourceSha };
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
}

export function buildPublishedSurface(repoRoot, published) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-sdk-stable-'));
    try {
        const archive = path.join(directory, 'source.zip');
        const source = path.join(directory, 'source');
        fs.mkdirSync(source);
        execFileSync('git', ['-C', repoRoot, 'archive', '--format=zip', '--output', archive, published.sourceSha]);
        extractArchive(archive, source);
        // 参数均为固定构建选项，Windows 仅通过系统 shell 启动 Maven 的批处理入口。
        const args = ['-B', '-ntp', '-pl',
            'pixivdownload-sdk-info,pixivdownload-plugin-api,pixivdownload-core-api', '-am', 'package',
            '-Dmaven.test.skip=true', '-Dmaven.javadoc.skip=true', '-Dmaven.source.skip=true', '-Dexec.skip=true'];
        const windows = process.platform === 'win32';
        execFileSync(windows ? (process.env.ComSpec || 'cmd.exe') : 'mvn',
                windows ? ['/d', '/s', '/c', 'mvn', ...args] : args,
                { cwd: source, stdio: ['ignore', 2, 2], windowsHide: true });
        return collectApiSurface(['sdk-info', 'plugin-api', 'core-api'].map(name => ({
            name,
            path: path.join(source, `pixivdownload-${name}`, 'target',
                    `pixivdownload-${name}-${published.identity.version}.jar`)
        })));
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
}

export async function resolveStableBaseline({ repoRoot, baseRef, baseIdentity, baseSurface, candidateIdentity },
        resolve = resolvePublishedBase, build = buildPublishedSurface) {
    // 正式发行前驱已经验证并构建过，直接复用本轮结果。
    if (!baseIdentity.prerelease && baseIdentity.major === candidateIdentity.major) {
        return { identity: baseIdentity, sourceSha: baseRef, surface: baseSurface };
    }
    const published = await resolve('stable', repoRoot, candidateIdentity);
    return published ? { ...published, surface: build(repoRoot, published) } : null;
}

async function main() {
    const [mode, repoRoot = '.'] = process.argv.slice(2);
    if (!['current', 'latest', 'if-present'].includes(mode)) {
        throw new Error('Usage: sdk-published-base.mjs current|latest|if-present [repo-root]');
    }
    const published = await resolvePublishedBase(mode, repoRoot);
    if (published) process.stdout.write(`${published.sourceSha}\n`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main().catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
}
