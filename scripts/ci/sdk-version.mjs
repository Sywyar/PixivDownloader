#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const METADATA_PATH = 'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties';
const SDK_MODULES = [
    'pixivdownload-sdk-info',
    'pixivdownload-plugin-api',
    'pixivdownload-core-api',
    'pixivdownload-sdk-bom'
];
const TEMPLATE_POMS = [
    'plugin-templates/minimal-feature-plugin/pom.xml',
    'plugin-templates/download-type-plugin/pom.xml'
];
export const SDK_GROUP_ID = 'io.github.sywyar.pixivdownloader';
const VERSION_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)([1-9]\d*))?$/;

export function parseSdkVersion(version) {
    const match = VERSION_PATTERN.exec(version);
    if (!match) {
        throw new Error(`Invalid SDK semantic version: ${version}`);
    }
    const prereleaseChannel = match[4] ?? '';
    return Object.freeze({
        version,
        major: Number(match[1]),
        minor: Number(match[2]),
        patch: Number(match[3]),
        prereleaseChannel,
        prereleaseSequence: prereleaseChannel ? Number(match[5]) : 0,
        prerelease: prereleaseChannel !== '',
        releaseId: `sdk-api-v${version}`,
        compatibilityVersion: `${match[1]}.${match[2]}`
    });
}

export function readSdkIdentity(repoRoot, ref = '', allowLegacyRevision = false) {
    const metadata = parseProperties(readText(repoRoot, METADATA_PATH, ref));
    const version = metadata.get('version');
    if (!version) {
        throw new Error('SDK metadata must declare version');
    }
    const identity = parseSdkVersion(version);
    const legacyRevision = metadata.get('revision') ?? '';
    if (legacyRevision && !allowLegacyRevision) {
        throw new Error('SDK metadata must not declare the removed revision axis');
    }
    if (!legacyRevision) {
        return identity;
    }
    if (!/^[1-9]\d*$/u.test(legacyRevision)) {
        throw new Error(`Invalid legacy SDK revision: ${legacyRevision}`);
    }
    return Object.freeze({
        ...identity,
        legacyRevision: Number(legacyRevision),
        releaseId: `sdk-api-v${version}-r${legacyRevision}`
    });
}

function parseProperties(text) {
    const properties = new Map();
    for (const rawLine of text.split(/\r?\n/u)) {
        const line = rawLine.trim();
        if (!line || line.startsWith('#') || line.startsWith('!')) {
            continue;
        }
        const separator = line.search(/[=:]/u);
        if (separator < 1) {
            throw new Error(`Invalid SDK metadata line: ${rawLine}`);
        }
        properties.set(line.slice(0, separator).trim(), line.slice(separator + 1).trim());
    }
    return properties;
}

function readText(repoRoot, relativePath, ref) {
    if (!ref) {
        return fs.readFileSync(path.join(repoRoot, ...relativePath.split('/')), 'utf8');
    }
    return execFileSync('git', ['-C', repoRoot, 'show', `${ref}:${relativePath}`], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe']
    });
}

function oneMatch(text, pattern, label) {
    const matches = [...text.matchAll(pattern)];
    if (matches.length !== 1) {
        throw new Error(`Expected exactly one ${label}, found ${matches.length}`);
    }
    return matches[0][1].trim();
}

function assertEqual(actual, expected, label) {
    if (actual !== expected) {
        throw new Error(`${label} must be ${expected}, found ${actual}`);
    }
}

export function inspectSdkVersion(repoRoot, ref = '') {
    const identity = readSdkIdentity(repoRoot, ref);
    const version = identity.version;
    const rootPom = readText(repoRoot, 'pom.xml', ref);
    const mavenProjection = oneMatch(rootPom, /<revision>\s*([^<]+?)\s*<\/revision>/gu, 'Maven SDK revision projection');
    assertEqual(mavenProjection, version, 'Maven SDK version projection');
    assertEqual(
            oneMatch(rootPom, /<pixivdownload\.sdk\.version>\s*([^<]+?)\s*<\/pixivdownload\.sdk\.version>/gu,
                    'root SDK version property'),
            '${revision}',
            'Root SDK version property'
    );
    for (const module of SDK_MODULES) {
        const pom = readText(repoRoot, `${module}/pom.xml`, ref);
        const artifactGroupId = oneMatch(
                pom,
                new RegExp(`<\\/parent>\\s*<groupId>\\s*([^<]+?)\\s*<\\/groupId>\\s*<artifactId>${module}<\\/artifactId>`, 'gu'),
                `${module} artifact group id`
        );
        assertEqual(artifactGroupId, SDK_GROUP_ID, `${module} artifact group id`);
        const artifactVersion = oneMatch(
                pom,
                new RegExp(`<artifactId>${module}<\\/artifactId>\\s*<version>\\s*([^<]+?)\\s*<\\/version>`, 'gu'),
                `${module} artifact version`
        );
        assertEqual(artifactVersion, '${revision}', `${module} artifact version`);
    }

    const bom = readText(repoRoot, 'pixivdownload-sdk-bom/pom.xml', ref);
    const managedGroups = [...bom.matchAll(/<groupId>\s*(io\.github\.sywyar\.pixivdownloader)\s*<\/groupId>\s*<artifactId>pixivdownload-(?:sdk-info|plugin-api|core-api)<\/artifactId>/gu)];
    if (managedGroups.length !== 3) {
        throw new Error(`SDK BOM must manage exactly three artifacts from ${SDK_GROUP_ID}, found ${managedGroups.length}`);
    }
    const managedVersions = [...bom.matchAll(/<version>\s*(\$\{pixivdownload\.sdk\.version\})\s*<\/version>/g)];
    if (managedVersions.length !== 3) {
        throw new Error(`SDK BOM must manage exactly three SDK artifacts, found ${managedVersions.length}`);
    }

    for (const templatePom of TEMPLATE_POMS) {
        const pom = readText(repoRoot, templatePom, ref);
        const importedBomGroup = oneMatch(
                pom,
                /<groupId>\s*([^<]+?)\s*<\/groupId>\s*<artifactId>pixivdownload-sdk-bom<\/artifactId>/gu,
                `${templatePom} SDK BOM group id`
        );
        assertEqual(importedBomGroup, SDK_GROUP_ID, `${templatePom} SDK BOM group id`);
        const templateVersion = oneMatch(
                pom,
                /<pixivdownload\.sdk\.version>\s*([^<]+?)\s*<\/pixivdownload\.sdk\.version>/gu,
                `${templatePom} SDK version`
        );
        assertEqual(templateVersion, version, `${templatePom} SDK version`);
    }
    return identity;
}

function parseArguments(argv) {
    const options = { repoRoot: '.', ref: '', json: false };
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        if (argument === '--repo-root') {
            options.repoRoot = argv[++index];
        } else if (argument === '--ref') {
            options.ref = argv[++index];
        } else if (argument === '--json') {
            options.json = true;
        } else {
            throw new Error(`Unknown argument: ${argument}`);
        }
    }
    if (!options.repoRoot || (argv.includes('--ref') && !options.ref)) {
        throw new Error('Missing argument value');
    }
    return options;
}

function main() {
    const options = parseArguments(process.argv.slice(2));
    const identity = inspectSdkVersion(path.resolve(options.repoRoot), options.ref);
    if (options.json) {
        process.stdout.write(`${JSON.stringify(identity)}\n`);
    } else {
        process.stdout.write(`SDK version ${identity.version} (${identity.releaseId}) is consistent.\n`);
    }
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    try {
        main();
    } catch (error) {
        process.stderr.write(`${error.message}\n`);
        process.exitCode = 1;
    }
}
