#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { inspectSdkVersion, SDK_GROUP_ID } from './sdk-version.mjs';

const MODULES = [
    ['pixivdownload-sdk-info', 'jar'],
    ['pixivdownload-plugin-api', 'jar'],
    ['pixivdownload-core-api', 'jar'],
    ['pixivdownload-sdk-bom', 'pom'],
];
const ARCHIVE_TIME = new Date('1980-01-01T00:00:00.000Z');

function fail(message) {
    throw new Error(message);
}

export function sha256(file) {
    return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

export function createProjectManifest(identity, sourceSha, minimumVerifiedHostRelease = '') {
    return {
        schemaVersion: 1,
        sdkVersion: identity.version,
        major: identity.major,
        minor: identity.minor,
        patch: identity.patch,
        prereleaseChannel: identity.prereleaseChannel || null,
        prereleaseSequence: identity.prereleaseSequence || null,
        prerelease: identity.prerelease,
        releaseId: identity.releaseId,
        sourceRepository: 'https://github.com/Sywyar/PixivDownloader',
        sourceCommitSha: sourceSha,
        minimumVerifiedHostRelease: minimumVerifiedHostRelease || null,
        verifiedHostSourceSha: minimumVerifiedHostRelease ? sourceSha : null,
        javaVersion: 17,
        mavenCoordinates: MODULES.map(([artifactId, packaging]) => ({
            groupId: SDK_GROUP_ID,
            artifactId,
            version: identity.version,
            packaging,
        })),
    };
}

export function createReleaseManifest(projectManifest, assets) {
    return {
        ...projectManifest,
        artifacts: assets.map(asset => ({
            file: path.basename(asset.file),
            sha256: asset.sha256,
        })),
    };
}

export function assertThinJarEntries(entries) {
    if (!entries.includes('plugin.properties')) {
        fail('SDK template artifact must contain root plugin.properties');
    }
    const forbidden = [
        'BOOT-INF/',
        'lib/',
        'top/sywyar/pixivdownload/plugin/api/',
        'top/sywyar/pixivdownload/core/',
        'top/sywyar/pixivdownload/sdk/',
        'org/pf4j/',
        'org/springframework/',
        'com/fasterxml/jackson/',
    ];
    const violation = entries.find(entry => forbidden.some(prefix => entry.startsWith(prefix)));
    if (violation) {
        fail(`SDK template artifact contains forbidden bundled entry: ${violation}`);
    }
}

function requireFile(file) {
    if (!fs.statSync(file, { throwIfNoEntry: false })?.isFile()) {
        fail(`missing SDK release input: ${file}`);
    }
}

function assertConsumerPom(file, artifactId, version) {
    requireFile(file);
    const pom = fs.readFileSync(file, 'utf8');
    for (const marker of ['<parent>', '<repositories>', '<pluginRepositories>']) {
        if (pom.includes(marker)) fail(`${artifactId} consumer POM must not contain ${marker}`);
    }
    for (const [label, expected] of [
        ['groupId', SDK_GROUP_ID],
        ['artifactId', artifactId],
        ['version', version],
    ]) {
        if (!pom.includes(`<${label}>${expected}</${label}>`)) {
            fail(`${artifactId} consumer POM must declare ${label}=${expected}`);
        }
    }
    for (const element of ['name', 'description', 'url', 'licenses', 'developers', 'scm', 'issueManagement']) {
        if (!new RegExp(`<${element}(?:\\s|>)`, 'u').test(pom)) {
            fail(`${artifactId} consumer POM must contain <${element}>`);
        }
    }
    if (/\$\{/u.test(pom)) {
        fail(`${artifactId} consumer POM contains an unresolved property`);
    }
    if (pom.includes('<properties>') || pom.includes('<build>')) {
        fail(`${artifactId} consumer POM contains build-only configuration`);
    }
    if (artifactId === 'pixivdownload-sdk-bom') {
        const managed = [...pom.matchAll(/<dependency>[\s\S]*?<\/dependency>/gu)]
                .map(match => match[0])
                .filter(block => block.includes(`<groupId>${SDK_GROUP_ID}</groupId>`));
        const expected = new Set(['pixivdownload-sdk-info', 'pixivdownload-plugin-api', 'pixivdownload-core-api']);
        if (managed.length !== expected.size) fail('SDK BOM consumer POM must manage exactly three SDK artifacts');
        for (const block of managed) {
            const managedArtifact = block.match(/<artifactId>([^<]+)<\/artifactId>/u)?.[1];
            if (!expected.delete(managedArtifact) || !block.includes(`<version>${version}</version>`)) {
                fail(`SDK BOM consumer POM contains an invalid managed artifact: ${managedArtifact ?? 'missing'}`);
            }
        }
        if (expected.size !== 0) fail(`SDK BOM consumer POM is missing: ${[...expected].join(', ')}`);
    }
}

function validateReleaseInputs(root, identity) {
    for (const [artifactId, packaging] of MODULES) {
        const moduleRoot = path.join(root, artifactId);
        assertConsumerPom(path.join(moduleRoot, 'target', 'flattened-pom.xml'), artifactId, identity.version);
        if (packaging === 'jar') {
            for (const suffix of ['.jar', '-sources.jar', '-javadoc.jar']) {
                requireFile(path.join(moduleRoot, 'target', `${artifactId}-${identity.version}${suffix}`));
            }
        }
    }
    requireFile(path.join(root, 'target', 'sdk-javadocs', 'index.html'));
}

function safeOutput(root, output) {
    const target = path.resolve(root, 'target');
    const resolved = path.resolve(output);
    const relative = path.relative(target, resolved);
    if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) {
        fail(`SDK release output must be a child of ${target}`);
    }
    return resolved;
}

function copyTree(source, destination) {
    fs.cpSync(source, destination, {
        recursive: true,
        filter(entry) {
            const relative = path.relative(source, entry);
            const parts = relative.split(path.sep);
            return !parts.includes('target') && !parts.includes('.flattened-pom.xml');
        },
    });
}

function renderOverlay(overlay, destination, values) {
    const overlayFiles = regularFiles(overlay).map(file => path.relative(overlay, file));
    copyTree(overlay, destination);
    for (const relative of overlayFiles) {
        const file = path.join(destination, relative);
        let text = fs.readFileSync(file, 'utf8');
        for (const [token, value] of Object.entries(values)) {
            text = text.replaceAll(token, value);
        }
        fs.writeFileSync(file, text, 'utf8');
    }
}

function regularFiles(root) {
    const files = [];
    const pending = [root];
    while (pending.length > 0) {
        const current = pending.pop();
        for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
            const item = path.join(current, entry.name);
            if (entry.isDirectory()) pending.push(item);
            else if (entry.isFile()) files.push(item);
        }
    }
    return files.sort();
}

function normalizeTimes(root) {
    const entries = [root];
    for (let index = 0; index < entries.length; index += 1) {
        const current = entries[index];
        if (fs.statSync(current).isDirectory()) {
            for (const child of fs.readdirSync(current)) entries.push(path.join(current, child));
        }
    }
    for (const entry of entries.sort((left, right) => right.length - left.length)) {
        fs.utimesSync(entry, ARCHIVE_TIME, ARCHIVE_TIME);
    }
}

export function createArchive(source, destination) {
    fs.rmSync(destination, { force: true });
    normalizeTimes(source);
    const argumentsFile = `${destination}.args`;
    const entries = regularFiles(source)
            .map(file => path.relative(source, file).split(path.sep).join('/'))
            .sort();
    fs.writeFileSync(argumentsFile, `${entries.map(entry => JSON.stringify(entry)).join('\n')}\n`, 'utf8');
    try {
        execFileSync('jar', ['--create', '--file', destination, '--no-manifest', `@${argumentsFile}`], {
            cwd: source,
            stdio: 'inherit',
        });
    } finally {
        fs.rmSync(argumentsFile, { force: true });
    }
    requireFile(destination);
    return sha256(destination);
}

function writeJson(file, value) {
    fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function parseArguments(argv) {
    const options = { repoRoot: '.', output: '', sourceSha: '', minimumHostRelease: '' };
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        const value = argv[index + 1];
        if (argument === '--repo-root') options.repoRoot = value;
        else if (argument === '--output') options.output = value;
        else if (argument === '--source-sha') options.sourceSha = value;
        else if (argument === '--minimum-host-release') options.minimumHostRelease = value;
        else fail(`unknown argument: ${argument}`);
        index += 1;
    }
    if (!options.output || !/^[0-9a-f]{40}$/u.test(options.sourceSha)) {
        fail('usage: sdk-release.mjs --repo-root <path> --output <target-child> --source-sha <40-hex> [--minimum-host-release <version>]');
    }
    return options;
}

export function assembleRelease(options) {
    const root = path.resolve(options.repoRoot);
    const output = safeOutput(root, options.output);
    const identity = inspectSdkVersion(root);
    validateReleaseInputs(root, identity);
    fs.rmSync(output, { recursive: true, force: true });
    fs.mkdirSync(output, { recursive: true });

    const work = path.join(output, '.work');
    const workspace = path.join(work, 'workspace');
    const docs = path.join(work, 'javadocs');
    fs.mkdirSync(path.dirname(workspace), { recursive: true });
    copyTree(path.join(root, 'plugin-templates', 'download-type-plugin'), path.join(workspace, 'plugin'));
    const pluginReadme = path.join(workspace, 'plugin', 'README.md');
    const pluginReadmeEnglish = path.join(workspace, 'plugin', 'README_en.md');
    if (!fs.existsSync(pluginReadmeEnglish)) fs.copyFileSync(pluginReadme, pluginReadmeEnglish);
    copyTree(path.join(root, 'plugin-templates', 'minimal-feature-plugin'),
            path.join(workspace, 'examples', 'minimal-feature-plugin'));
    renderOverlay(path.join(root, 'plugin-templates', 'sdk-package'), workspace, {
        '@SDK_VERSION@': identity.version,
        '@SDK_RELEASE_ID@': identity.releaseId,
        '@SOURCE_SHA@': options.sourceSha,
    });
    fs.cpSync(path.join(root, '.mvn'), path.join(workspace, '.mvn'), { recursive: true });
    for (const file of ['mvnw', 'mvnw.cmd', 'LICENSE']) {
        fs.copyFileSync(path.join(root, file), path.join(workspace, file));
    }
    if (process.platform !== 'win32') fs.chmodSync(path.join(workspace, 'mvnw'), 0o755);

    const projectManifest = createProjectManifest(identity, options.sourceSha, options.minimumHostRelease);
    writeJson(path.join(workspace, 'sdk-project.json'), projectManifest);
    copyTree(path.join(root, 'target', 'sdk-javadocs'), docs);

    const sdkZip = path.join(output, `PixivDownloader-Plugin-SDK-${identity.version}.zip`);
    const docsZip = path.join(output, `PixivDownloader-Plugin-SDK-Javadocs-${identity.version}.zip`);
    const assets = [
        { file: sdkZip, sha256: createArchive(workspace, sdkZip) },
        { file: docsZip, sha256: createArchive(docs, docsZip) },
    ];
    const releaseMetadata = path.join(output, 'sdk-release.json');
    writeJson(releaseMetadata, createReleaseManifest(projectManifest, assets));
    const checksumEntries = [...assets, { file: releaseMetadata, sha256: sha256(releaseMetadata) }];
    fs.writeFileSync(path.join(output, 'SHA256SUMS'),
            `${checksumEntries.map(asset => `${asset.sha256}  ${path.basename(asset.file)}`).join('\n')}\n`, 'utf8');
    fs.rmSync(work, { recursive: true, force: true });
    return { identity, output, assets, releaseMetadata };
}

function main() {
    const result = assembleRelease(parseArguments(process.argv.slice(2)));
    process.stdout.write(`${JSON.stringify({
        releaseId: result.identity.releaseId,
        output: result.output,
        assets: result.assets.map(asset => ({ file: path.basename(asset.file), sha256: asset.sha256 })),
    })}\n`);
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    try {
        main();
    } catch (error) {
        process.stderr.write(`${error.message}\n`);
        process.exitCode = 1;
    }
}
