#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash, generateKeyPairSync } from 'node:crypto';
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

function thinJarEntries(pluginJar) {
    if (!fs.statSync(pluginJar, { throwIfNoEntry: false })?.isFile()) {
        fail(`isolated consumer plugin JAR is missing: ${pluginJar}`);
    }
    const listing = spawnSync('jar', ['--list', '--file', pluginJar], { encoding: 'utf8' });
    if (listing.status !== 0) fail(`cannot inspect isolated consumer plugin JAR: ${pluginJar}`);
    const entries = listing.stdout.split(/\r?\n/u).filter(Boolean);
    assertThinJarEntries(entries);
    return entries;
}

export function parsePluginIdentity(propertiesText) {
    const values = new Map();
    for (const rawLine of propertiesText.split(/\r?\n/u)) {
        const line = rawLine.trim();
        if (!line || line.startsWith('#') || line.startsWith('!')) continue;
        const separator = line.search(/[=:]/u);
        if (separator < 1) continue;
        const key = line.slice(0, separator).trim();
        if (key !== 'plugin.id' && key !== 'plugin.version') continue;
        if (values.has(key)) fail(`plugin descriptor declares ${key} more than once`);
        values.set(key, line.slice(separator + 1).trim());
    }
    const id = values.get('plugin.id');
    const version = values.get('plugin.version');
    if (!id || !version) fail('plugin descriptor must declare plugin.id and plugin.version');
    return Object.freeze({ id, version });
}

function douyinPluginIdentity(repoRoot) {
    const descriptor = path.join(
            repoRoot, 'pixivdownload-plugin-douyin', 'src', 'main', 'resources', 'plugin.properties');
    return parsePluginIdentity(fs.readFileSync(descriptor, 'utf8'));
}

function signatureToolJar(repoRoot) {
    const target = path.join(repoRoot, 'pixivdownload-plugin-signature', 'target');
    const candidates = fs.statSync(target, { throwIfNoEntry: false })?.isDirectory()
        ? fs.readdirSync(target)
                .filter(name => /^pixivdownload-plugin-signature-.+\.jar$/u.test(name))
                .filter(name => !name.endsWith('-sources.jar') && !name.endsWith('-javadoc.jar'))
        : [];
    if (candidates.length !== 1) {
        fail(`expected exactly one plugin signature tool JAR under ${target}`);
    }
    return path.join(target, candidates[0]);
}

function deriveDouyinPackages(repoRoot, work, pluginJar) {
    const { id: pluginId, version: pluginVersion } = douyinPluginIdentity(repoRoot);
    const packages = path.join(work, 'douyin-packages');
    const fileName = path.basename(pluginJar);
    const unsignedPluginJar = path.join(packages, 'unsigned', fileName);
    const signedPluginJar = path.join(packages, 'signed', fileName);
    fs.rmSync(packages, { recursive: true, force: true });
    fs.mkdirSync(path.dirname(unsignedPluginJar), { recursive: true });
    fs.mkdirSync(path.dirname(signedPluginJar), { recursive: true });
    fs.copyFileSync(pluginJar, unsignedPluginJar);
    fs.copyFileSync(pluginJar, signedPluginJar);
    const sourceBytes = fs.readFileSync(pluginJar);
    if (!sourceBytes.equals(fs.readFileSync(unsignedPluginJar))
            || !sourceBytes.equals(fs.readFileSync(signedPluginJar))) {
        fail('derived Douyin packages do not preserve the candidate JAR bytes');
    }

    const keyId = 'douyin-third-party-ci';
    const { privateKey, publicKey } = generateKeyPairSync('ed25519');
    const privateKeyFile = path.join(packages, 'third-party-private-key.pem');
    fs.writeFileSync(privateKeyFile, privateKey.export({ format: 'pem', type: 'pkcs8' }), 'utf8');
    const publicKeySpkiBase64 = publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
    const signatureFile = `${signedPluginJar}.sig.json`;
    const tool = signatureToolJar(repoRoot);
    const toolClass = 'top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool';
    run('java', ['-cp', tool, toolClass, 'artifact',
        '--artifact', signedPluginJar,
        '--plugin-id', pluginId,
        '--version', pluginVersion,
        '--key-id', keyId,
        '--private-key', privateKeyFile,
        '--out', signatureFile,
    ]);
    fs.rmSync(privateKeyFile, { force: true });
    run('java', ['-cp', tool, toolClass, 'verify-artifact',
        '--artifact', signedPluginJar,
        '--signature', signatureFile,
        '--plugin-id', pluginId,
        '--version', pluginVersion,
        '--expected-size', String(sourceBytes.length),
        '--sha256', createHash('sha256').update(sourceBytes).digest('hex'),
        '--policy', 'custom',
        '--trusted-key-id', keyId,
        '--trusted-public-key', publicKeySpkiBase64,
        '--trusted-publisher', 'Douyin Third-Party CI',
        '--trusted-label', 'Douyin third-party compatibility canary',
        '--trusted-official', 'false',
    ]);
    if (fs.existsSync(`${unsignedPluginJar}.sig`) || fs.existsSync(`${unsignedPluginJar}.sig.json`)) {
        fail('unsigned Douyin package unexpectedly has a signature sidecar');
    }
    return { signedPluginJar, signatureFile, unsignedPluginJar, publicKeySpkiBase64 };
}

function verifyDouyinRuntime(runMaven, repoRoot, settings, localRepository, packages) {
    const common = [
        '-s', settings,
        `-Dmaven.repo.local=${localRepository}`,
        '-f', path.join(repoRoot, 'pom.xml'),
        '-pl', 'pixivdownload-app',
        '-am',
        '-Dtest=DouyinExternalPluginBootContextTest',
        '-Dsurefire.failIfNoSpecifiedTests=false',
        '-Dexec.skip=true',
        '-Dpixivdownload.plugin-dev.enabled=false',
        'test',
    ];
    runMaven([
        `-Ddouyin.third-party.package=${packages.signedPluginJar}`,
        '-Ddouyin.third-party.mode=signed',
        '-Ddouyin.third-party.state-transition=seed',
        `-Ddouyin.third-party.signature=${packages.signatureFile}`,
        `-Ddouyin.third-party.public-key=${packages.publicKeySpkiBase64}`,
        ...common,
    ], repoRoot);
    runMaven([
        `-Ddouyin.third-party.package=${packages.unsignedPluginJar}`,
        '-Ddouyin.third-party.mode=unsigned',
        '-Ddouyin.third-party.state-transition=verify',
        ...common,
    ], repoRoot);
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
    const mavenEnvironment = { ...process.env, MAVEN_USER_HOME: mavenHome };
    const runMaven = (args, cwd = project) => run(command, [...prefix, '-B', '-ntp', ...args], {
        cwd,
        env: mavenEnvironment,
    });
    const douyinPom = path.join(repoRoot, 'pixivdownload-plugin-douyin', 'third-party-pom.xml');
    const douyinBuildDirectory = path.join(work, 'douyin-target');
    if (!fs.statSync(douyinPom, { throwIfNoEntry: false })?.isFile()) {
        fail(`missing Douyin third-party POM: ${douyinPom}`);
    }
    const buildDouyin = offline => runMaven([
        ...(offline ? ['-o'] : []), '-s', settings, `-Dmaven.repo.local=${localRepository}`,
        `-Dpixivdownload.sdk.version=${identity.version}`,
        `-Ddouyin.build.directory=${douyinBuildDirectory}`,
        '-Dmaven.test.skip=true', '-f', douyinPom, 'clean', 'package',
    ], repoRoot);

    runMaven(['-s', settings, `-Dmaven.repo.local=${localRepository}`, 'clean', 'verify']);
    runMaven(['-s', settings, `-Dmaven.repo.local=${localRepository}`,
        'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get',
        `-Dartifact=${SDK_GROUP_ID}:pixivdownload-core-api:${identity.version}`,
    ]);
    buildDouyin(false);
    stageSdkArtifacts(localRepository, sdkRepository, identity.version);
    runMaven(['-o', '-s', settings, `-Dmaven.repo.local=${localRepository}`, 'clean', 'verify']);
    runMaven(['-o', '-s', settings, `-Dmaven.repo.local=${localRepository}`,
        'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get',
        `-Dartifact=${SDK_GROUP_ID}:pixivdownload-core-api:${identity.version}`,
    ]);
    buildDouyin(true);
    assertSdkResolution(localRepository, sdkRepository, identity.version);
    const pluginJar = path.join(project, 'plugin', 'target', 'example-download-plugin-0.1.0.jar');
    thinJarEntries(pluginJar);
    const douyinCandidates = fs.readdirSync(douyinBuildDirectory)
            .filter(name => /^pixivdownload-plugin-douyin-.+\.jar$/u.test(name))
            .filter(name => !name.endsWith('-sources.jar') && !name.endsWith('-javadoc.jar'));
    if (douyinCandidates.length !== 1) {
        fail(`expected exactly one Douyin plugin JAR under ${douyinBuildDirectory}`);
    }
    const douyinPluginJar = path.join(douyinBuildDirectory, douyinCandidates[0]);
    const douyinEntries = thinJarEntries(douyinPluginJar);
    for (const required of [
        'top/sywyar/pixivdownload/douyin/DouyinPf4jPlugin.class',
        'top/sywyar/pixivdownload/douyin/controller/DouyinController.class',
        'top/sywyar/pixivdownload/douyin/download/DouyinQueueOperations.class',
        'top/sywyar/pixivdownload/douyin/schedule/source/DouyinScheduledSourceExecutor.class',
        'static/pixiv-douyin.html',
        'static/pixiv-douyin-gallery.html',
        'static/pixiv-douyin-download/douyin-download.js',
        'i18n/web/douyin.properties',
    ]) {
        if (!douyinEntries.includes(required)) fail(`Douyin third-party JAR is missing ${required}`);
    }
    const douyinPackages = deriveDouyinPackages(repoRoot, work, douyinPluginJar);
    verifyDouyinRuntime(runMaven, repoRoot, settings, localRepository, douyinPackages);
    const { publicKeySpkiBase64, ...packagePaths } = douyinPackages;
    return {
        project,
        pluginJar,
        douyinPluginJar,
        ...packagePaths,
        localRepository,
    };
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
