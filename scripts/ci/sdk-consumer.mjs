#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL, fileURLToPath } from 'node:url';

import { assertThinJarEntries } from './sdk-release.mjs';
import { inspectSdkVersion, SDK_GROUP_ID } from './sdk-version.mjs';

function fail(message) {
    throw new Error(message);
}

function run(command, args, options = {}) {
    const result = spawnSync(command, args, { stdio: 'inherit', ...options });
    if (result.status !== 0) fail(`${path.basename(command)} exited with ${result.status ?? 'no status'}`);
}

function safeWorkDirectory(repoRoot, requested) {
    const target = path.resolve(repoRoot, 'target');
    const resolved = path.resolve(requested);
    const relative = path.relative(target, resolved);
    if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) {
        fail(`SDK consumer work directory must be a child of ${target}`);
    }
    return resolved;
}

function settingsXml(repository) {
    const url = pathToFileURL(repository).href;
    return `<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <activeProfiles><activeProfile>sdk-staging</activeProfile></activeProfiles>
  <profiles>
    <profile>
      <id>sdk-staging</id>
      <repositories>
        <repository>
          <id>sdk-staging</id>
          <url>${url}</url>
          <releases><enabled>true</enabled><updatePolicy>always</updatePolicy><checksumPolicy>fail</checksumPolicy></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
</settings>
`;
}

function parseArguments(argv) {
    const options = { repoRoot: '.', sdkZip: '', sdkRepository: '', workDirectory: '' };
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        const value = argv[index + 1];
        if (argument === '--repo-root') options.repoRoot = value;
        else if (argument === '--sdk-zip') options.sdkZip = value;
        else if (argument === '--sdk-repository') options.sdkRepository = value;
        else if (argument === '--work-dir') options.workDirectory = value;
        else fail(`unknown argument: ${argument}`);
        index += 1;
    }
    if (!options.sdkZip || !options.sdkRepository || !options.workDirectory) {
        fail('usage: sdk-consumer.mjs --repo-root <path> --sdk-zip <zip> --sdk-repository <dir> --work-dir <target-child>');
    }
    return options;
}

const SDK_ARTIFACTS = [
    ['pixivdownload-sdk-bom', ['pom']],
    ['pixivdownload-sdk-info', ['pom', 'jar']],
    ['pixivdownload-plugin-api', ['pom', 'jar']],
    ['pixivdownload-core-api', ['pom', 'jar']],
];

function sdkArtifactFiles(repository, version) {
    const group = SDK_GROUP_ID.split('.');
    return SDK_ARTIFACTS.flatMap(([artifact, extensions]) => extensions.map((extension) => ({
        artifact,
        file: path.join(repository, ...group, artifact, version, `${artifact}-${version}.${extension}`),
    })));
}

export function assertSdkResolution(localRepository, sdkRepository, version) {
    const sources = sdkArtifactFiles(sdkRepository, version);
    const resolved = sdkArtifactFiles(localRepository, version);
    for (let index = 0; index < sources.length; index += 1) {
        if (!fs.statSync(resolved[index].file, { throwIfNoEntry: false })?.isFile()) {
            fail(`isolated consumer did not resolve ${resolved[index].artifact}:${version}`);
        }
        if (!fs.readFileSync(resolved[index].file).equals(fs.readFileSync(sources[index].file))) {
            fail(`${resolved[index].artifact}:${version} does not match the supplied SDK repository`);
        }
    }
}

export function stageSdkArtifacts(localRepository, sdkRepository, version) {
    const group = SDK_GROUP_ID.split('.');
    fs.rmSync(path.join(localRepository, ...group), { recursive: true, force: true });
    for (const artifact of sdkArtifactFiles(sdkRepository, version)) {
        if (!fs.statSync(artifact.file, { throwIfNoEntry: false })?.isFile()) {
            fail(`SDK repository is missing ${artifact.artifact}:${version}`);
        }
        const relative = path.relative(sdkRepository, artifact.file);
        const target = path.join(localRepository, relative);
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.copyFileSync(artifact.file, target);
    }
    assertSdkResolution(localRepository, sdkRepository, version);
}

export function verifyConsumer(options) {
    const repoRoot = path.resolve(options.repoRoot);
    const identity = inspectSdkVersion(repoRoot);
    const sdkZip = path.resolve(options.sdkZip);
    const sdkRepository = path.resolve(options.sdkRepository);
    const work = safeWorkDirectory(repoRoot, options.workDirectory);
    if (!fs.statSync(sdkZip, { throwIfNoEntry: false })?.isFile()) fail(`missing SDK ZIP: ${sdkZip}`);
    if (!fs.statSync(sdkRepository, { throwIfNoEntry: false })?.isDirectory()) {
        fail(`missing SDK staging repository: ${sdkRepository}`);
    }
    fs.rmSync(work, { recursive: true, force: true });
    const project = path.join(work, 'project');
    const localRepository = path.join(work, 'm2', 'repository');
    const mavenHome = path.join(work, 'maven-home');
    fs.mkdirSync(project, { recursive: true });
    run('jar', ['--extract', '--file', sdkZip], { cwd: project });
    if (!fs.statSync(path.join(project, 'docs', 'javadocs', 'index.html'), { throwIfNoEntry: false })?.isFile()) {
        fail('integrated SDK Javadocs are missing');
    }
    const settings = path.join(work, 'settings.xml');
    fs.writeFileSync(settings, settingsXml(sdkRepository), 'utf8');
    fs.mkdirSync(localRepository, { recursive: true });
    fs.mkdirSync(mavenHome, { recursive: true });
    const wrapper = path.join(project, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
    const command = process.platform === 'win32' ? (process.env.ComSpec || 'cmd.exe') : 'sh';
    const prefix = process.platform === 'win32' ? ['/d', '/s', '/c', wrapper] : [wrapper];
    run(command, [...prefix,
        '-B', '-ntp', '-s', settings, `-Dmaven.repo.local=${localRepository}`, 'clean', 'verify',
    ], {
        cwd: project,
        env: { ...process.env, MAVEN_USER_HOME: mavenHome },
    });
    run(command, [...prefix,
        '-B', '-ntp', '-s', settings, `-Dmaven.repo.local=${localRepository}`,
        'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get',
        `-Dartifact=${SDK_GROUP_ID}:pixivdownload-core-api:${identity.version}`,
    ], {
        cwd: project,
        env: { ...process.env, MAVEN_USER_HOME: mavenHome },
    });
    stageSdkArtifacts(localRepository, sdkRepository, identity.version);
    run(command, [...prefix,
        '-B', '-ntp', '-o', '-s', settings, `-Dmaven.repo.local=${localRepository}`, 'clean', 'verify',
    ], {
        cwd: project,
        env: { ...process.env, MAVEN_USER_HOME: mavenHome },
    });
    run(command, [...prefix,
        '-B', '-ntp', '-o', '-s', settings, `-Dmaven.repo.local=${localRepository}`,
        'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get',
        `-Dartifact=${SDK_GROUP_ID}:pixivdownload-core-api:${identity.version}`,
    ], {
        cwd: project,
        env: { ...process.env, MAVEN_USER_HOME: mavenHome },
    });
    assertSdkResolution(localRepository, sdkRepository, identity.version);
    const pluginJar = path.join(project, 'plugin', 'target', 'example-download-plugin-0.1.0.jar');
    if (!fs.statSync(pluginJar, { throwIfNoEntry: false })?.isFile()) fail('isolated consumer plugin JAR is missing');
    const listing = spawnSync('jar', ['--list', '--file', pluginJar], { encoding: 'utf8' });
    if (listing.status !== 0) fail('cannot inspect isolated consumer plugin JAR');
    assertThinJarEntries(listing.stdout.split(/\r?\n/u).filter(Boolean));
    return { project, pluginJar, localRepository };
}

function main() {
    const result = verifyConsumer(parseArguments(process.argv.slice(2)));
    process.stdout.write(`${JSON.stringify(result)}\n`);
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    try {
        main();
    } catch (error) {
        process.stderr.write(`${error.message}\n`);
        process.exitCode = 1;
    }
}
