#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL, fileURLToPath } from 'node:url';

import { assertThinJarEntries, extractArchive } from './sdk-release.mjs';
import { inspectSdkVersion, SDK_ARTIFACTS, SDK_GROUP_ID } from './sdk-version.mjs';

function fail(message) {
    throw new Error(message);
}

export function readMavenPluginVersion(pomFile, artifactId) {
    const pom = fs.readFileSync(pomFile, 'utf8');
    const plugin = [...pom.matchAll(/<plugin>([\s\S]*?)<\/plugin>/gu)]
        .find(match => match[1].includes(`<artifactId>${artifactId}</artifactId>`))?.[1];
    const version = /<version>\s*([^<$]+)\s*<\/version>/u.exec(plugin ?? '')?.[1].trim();
    if (!version) fail(`Missing explicit build plugin version: ${artifactId} in ${pomFile}`);
    return version;
}

function run(command, args, options = {}) {
    const result = spawnSync(command, args, { stdio: 'inherit', ...options });
    if (result.status !== 0) fail(`${path.basename(command)} exited with ${result.status ?? 'no status'}`);
}

function thinJarEntries(pluginJar) {
    const entries = jarEntries(pluginJar);
    assertThinJarEntries(entries);
    return entries;
}

function jarEntries(pluginJar) {
    if (!fs.statSync(pluginJar, { throwIfNoEntry: false })?.isFile()) {
        fail(`isolated consumer plugin JAR is missing: ${pluginJar}`);
    }
    const listing = spawnSync('jar', ['--list', '--file', pluginJar], { encoding: 'utf8' });
    if (listing.status !== 0) fail(`cannot inspect isolated consumer plugin JAR: ${pluginJar}`);
    const entries = listing.stdout.split(/\r?\n/u).filter(Boolean);
    return entries;
}

export function verifyTypedServiceArtifacts(project, toolsJar) {
    project = path.resolve(project);
    const api = currentPluginJar(path.join(project, 'api'));
    const apiBytes = fs.readFileSync(api);
    const apiDigest = createHash('sha256').update(apiBytes).digest('hex');
    fs.mkdirSync(path.join(project, 'target'), { recursive: true });
    const extracted = fs.mkdtempSync(path.join(project, 'target', 'contract-check-'));
    try {
        for (const module of ['api', 'contract', 'provider', 'consumer']) {
            const jar = currentPluginJar(path.join(project, module));
            const entries = jarEntries(jar);
            if (module === 'api' && toolsJar) {
                for (const [name, value] of Object.entries({ Request: { texts: ['abc', '你好'] }, Result: { lengths: [3, 2], total: 5 } })) {
                    const schema = `META-INF/pixivdownload/schema/com.example.textmetrics.api.TextMetrics.${name}.schema.json`;
                    if (!entries.includes(schema)) fail(`typed service API is missing its generated ${name} schema`);
                    run('jar', ['--extract', '--file', jar, schema], { cwd: extracted });
                    const data = path.join(extracted, `${name}.json`);
                    fs.writeFileSync(data, JSON.stringify(value), 'utf8');
                    run('java', ['-jar', toolsJar, 'schema-check', path.join(extracted, schema), data]);
                }
            }
            const classes = entries.filter(entry => entry.endsWith('.class'));
            if (!classes.length || classes.some(entry => !entry.startsWith(`com/example/textmetrics/${module}/`)
                    && !(module === 'consumer' && entry === 'pixivdownload/generated/remote/com/example/textmetrics/api/TextMetricsRemoteAdapter.class'))) {
                fail(`typed service ${module} contains unexpected or duplicated contract/host classes`);
            }
            const allowedLibrary = module === 'contract' ? 'lib/text-metrics-api.jar' : '';
            if (entries.some(entry => entry.endsWith('.jar') && entry !== allowedLibrary)) {
                fail(`typed service ${module} contains an unexpected library`);
            }
            if (module !== 'api') assertThinJarEntries(entries.filter(entry =>
                module !== 'contract' || (entry !== 'lib/' && entry !== allowedLibrary)));
            if (module === 'contract') {
                run('jar', ['--extract', '--file', jar, 'plugin.properties', allowedLibrary], { cwd: extracted });
                const descriptor = fs.readFileSync(path.join(extracted, 'plugin.properties'), 'utf8');
                if (!fs.readFileSync(path.join(extracted, allowedLibrary)).equals(apiBytes)
                        || !descriptor.split(/\r?\n/u).includes(`pixiv.contract-sha256=${apiDigest}`)
                        || !descriptor.split(/\r?\n/u).includes(`pixiv.contract-api=${allowedLibrary}`)) {
                    fail('typed service contract must bind and carry the original API JAR bytes');
                }
            }
        }
    } finally {
        fs.rmSync(extracted, { recursive: true, force: true });
    }
    return { apiDigest };
}

export function verifyTypedServiceSchemaRebuild(project, workDirectory, toolsJar, runMaven) {
    fs.cpSync(project, workDirectory, { recursive: true, errorOnExist: true, force: false,
        filter: source => path.basename(source) !== 'target' });
    const probe = path.join(workDirectory, 'api/src/main/java/com/example/textmetrics/api/SchemaRebuildProbe.java');
    const writeProbe = field => fs.writeFileSync(probe, 'package com.example.textmetrics.api;\n'
        + 'import top.sywyar.pixivdownload.plugin.api.contract.schema.*;\n'
        + `@ValueSchema public record SchemaRebuildProbe(@ValueProperty(required=true, nullable=false) String ${field}) {}\n`, 'utf8');
    const build = (enabled, extra = []) => runMaven(['-f', path.join(workDirectory, 'pom.xml'), '-pl', 'api', '-am', 'package',
        ...(enabled ? [`-Dpixivdownload.sdk.tools=${toolsJar}`] : []), ...extra]);
    const requireSuccess = result => {
        if (result.status !== 0) fail(`incremental schema build failed: ${result.status}`);
    };
    const inspect = field => {
        const jar = currentPluginJar(path.join(workDirectory, 'api'));
        const extracted = path.join(workDirectory, 'target', field);
        fs.mkdirSync(extracted, { recursive: true });
        const name = 'META-INF/pixivdownload/schema/com.example.textmetrics.api.SchemaRebuildProbe.schema.json';
        run('jar', ['--extract', '--file', jar, name], { cwd: extracted });
        const schema = JSON.parse(fs.readFileSync(path.join(extracted, name), 'utf8'));
        const properties = schema.$defs['com.example.textmetrics.api.SchemaRebuildProbe'].properties;
        if (JSON.stringify(Object.keys(properties)) !== JSON.stringify([field])) fail('incremental schema does not match source');
        const data = path.join(extracted, 'sample.json');
        fs.writeFileSync(data, JSON.stringify({ [field]: 'sample' }), 'utf8');
        run('java', ['-jar', toolsJar, 'schema-check', path.join(extracted, name), data]);
        fs.writeFileSync(data, JSON.stringify({ [field === 'before' ? 'after' : 'before']: 'sample' }), 'utf8');
        const rejected = spawnSync('java', ['-jar', toolsJar, 'schema-check', path.join(extracted, name), data], { encoding: 'utf8' });
        if (rejected.status !== 1 || !`${rejected.stdout}\n${rejected.stderr}`.includes('SDK_SCHEMA_DATA_INVALID')) {
            fail('incremental schema accepted data from the other contract shape');
        }
        const declaration = spawnSync('javap', ['-classpath', jar, 'com.example.textmetrics.api.SchemaRebuildProbe'], { encoding: 'utf8' });
        if (declaration.status !== 0 || !declaration.stdout.includes(`java.lang.String ${field}()`)) {
            fail('incremental API class does not match source');
        }
        return fs.readFileSync(jar);
    };
    requireSuccess(build(false));
    if (jarEntries(currentPluginJar(path.join(workDirectory, 'api'))).some(entry => entry.startsWith('META-INF/pixivdownload/schema/'))) {
        fail('initial build without schema generation must not contain schemas');
    }
    writeProbe('before');
    requireSuccess(build(true));
    const before = inspect('before');
    writeProbe('after');
    for (const disabled of [() => build(false), () => build(true, ['-P!schemas', '-Dmaven.antrun.skip=true'])]) {
        const result = disabled();
        if (result.status === 0 || !`${result.stdout}\n${result.stderr}`.includes('SDK_SCHEMA_GENERATION_REQUIRED')) {
            fail('non-clean build without schema generation must reject stale output');
        }
        if (!before.equals(inspect('before'))) fail('rejected build changed the previous API JAR');
    }
    requireSuccess(build(true, ['-Dexec.skip=true', '-Dmaven.antrun.skip=true']));
    const after = inspect('after');
    if (before.equals(after)) fail('enabled schema rebuild did not update the API JAR');
    verifyTypedRemoteRebuild(workDirectory, toolsJar, runMaven);
}

export function verifyTypedRemoteRebuild(project, toolsJar, runMaven) {
    const source = path.join(project, 'api/src/main/java/com/example/textmetrics/api/TextMetrics.java');
    const original = fs.readFileSync(source, 'utf8');
    const output = path.join(project, 'consumer/target');
    const marker = path.join(output, 'remote-generation.complete');
    const service = 'com.example.textmetrics.api.TextMetrics';
    const build = (extra = [], tool = toolsJar) => runMaven(['-f', path.join(project, 'pom.xml'), 'package',
        `-Dpixivdownload.sdk.tools=${tool}`, ...extra]);
    let ordinal = 0;
    const inspect = () => {
        const extracted = path.join(project, 'target', `remote-inspect-${++ordinal}`);
        fs.mkdirSync(extracted, { recursive: true });
        run('jar', ['--extract', '--file', currentPluginJar(path.join(project, 'api')), 'META-INF/pixivdownload/schema'], { cwd: extracted });
        run('jar', ['--extract', '--file', currentPluginJar(path.join(project, 'consumer')), 'web/generated',
            'pixivdownload/generated/remote/com/example/textmetrics/api/TextMetricsRemoteAdapter.class'], { cwd: extracted });
        const schema = JSON.parse(fs.readFileSync(path.join(extracted, `META-INF/pixivdownload/schema/${service}.remote.json`), 'utf8'));
        for (const prefix of ['META-INF/pixivdownload/schema/', 'web/generated/']) {
            for (const suffix of ['.mjs', '.d.mts']) {
                if (!fs.readFileSync(path.join(extracted, prefix + service + suffix), 'utf8').includes(schema.identity))
                    fail('packaged remote artifacts have different identities');
            }
        }
        if (!fs.readFileSync(path.join(extracted, 'pixivdownload/generated/remote/com/example/textmetrics/api/TextMetricsRemoteAdapter.class')).includes(Buffer.from(schema.identity)))
            fail('packaged adapter uses a stale contract identity');
        const completed = JSON.parse(fs.readFileSync(marker, 'utf8'));
        if (Object.keys(completed).length !== 1 || completed[service] !== schema.identity)
            fail('remote completion does not identify packaged contract');
        return schema.identity;
    };
    const success = (extra = [], tool = toolsJar) => {
        if (build(extra, tool).status !== 0) fail('incremental remote build failed');
        return inspect();
    };
    const rejected = (code, extra = []) => {
        const previous = fs.readFileSync(currentPluginJar(path.join(project, 'consumer')));
        const result = build(extra);
        if (result.status === 0 || !`${result.stdout}\n${result.stderr}`.includes(code)) fail(`expected ${code}`);
        if (!previous.equals(fs.readFileSync(currentPluginJar(path.join(project, 'consumer'))))) fail('rejected build rewrote consumer JAR');
    };
    const first = success();
    try {
        fs.writeFileSync(source, original.replace('/api/text-metrics/remote', '/api/text-metrics/changed'), 'utf8');
        rejected('SDK_REMOTE_GENERATION_REQUIRED', ['-P!remote', '-Dmaven.antrun.skip=true']);
        if (fs.existsSync(marker)) fail('disabled remote generator retained completion');
        const changed = success(['-Dexec.skip=true', '-Dmaven.antrun.skip=true']);
        if (first === changed) fail('remote contract change retained identity');
        fs.writeFileSync(source, original.replace('AccessPolicy.ADMIN', 'AccessPolicy.PUBLIC'), 'utf8');
        rejected('SDK_SCHEMA_COMPILE');
        fs.writeFileSync(source, original.replace(/@RemoteService\([^\n]*\)\s*/u, '')
            .replace(/@RemoteOperation\([\s\S]*?\)\s*/u, ''), 'utf8');
        rejected('SDK_REMOTE_CONTRACT_MISSING');
        for (const file of ['remote-generation.complete', `classes/web/generated/${service}.mjs`,
            `classes/web/generated/${service}.d.mts`, 'classes/pixivdownload/generated/remote/com/example/textmetrics/api/TextMetricsRemoteAdapter.class']) {
            if (fs.existsSync(path.join(output, file))) fail(`removed remote declaration retained ${file}`);
        }
    } finally { fs.writeFileSync(source, original, 'utf8'); }
    if (success() !== first) fail('restored source did not reproduce remote identity');
    const toolsDirectory = path.join(project, 'target', 'changed-tools');
    fs.mkdirSync(toolsDirectory, { recursive: true });
    const changedTools = path.join(toolsDirectory, 'sdk-tools.jar');
    fs.copyFileSync(toolsJar, changedTools);
    const runtimeEntry = 'BOOT-INF/classes/remote/client-runtime.mjs';
    run('jar', ['--extract', '--file', changedTools, runtimeEntry], { cwd: toolsDirectory });
    fs.appendFileSync(path.join(toolsDirectory, runtimeEntry), '\n', 'utf8');
    run('jar', ['--update', '--file', changedTools, '-C', toolsDirectory, runtimeEntry]);
    if (success([], changedTools) === first) fail('changed generator did not invalidate remote identity');
    if (success() !== first) fail('original generator did not restore complete output');
}

function currentPluginJar(project) {
    const directory = path.join(project, 'target');
    const files = fs.readdirSync(directory).filter(name => name.endsWith('.jar')
            && !/-(sources|javadoc|original)\.jar$/u.test(name));
    if (files.length !== 1) fail(`expected one current plugin JAR under ${directory}`);
    return path.join(directory, files[0]);
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
        if (argument === '--typed-service-only') {
            options.typedServiceOnly = true;
            continue;
        }
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

function sdkArtifactFiles(repository, version) {
    const group = SDK_GROUP_ID.split('.');
    return SDK_ARTIFACTS.flatMap(([artifact, packaging]) => (packaging === 'pom' ? ['pom'] : ['pom', 'jar']).map((extension) => ({
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
    const dependencyVersion = readMavenPluginVersion(path.join(repoRoot, 'pom.xml'), 'maven-dependency-plugin');
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
    extractArchive(sdkZip, project);
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
    // HTTP 验收独立从原始 SDK 创建夹具，不等待或共享另一 job 的可变工程。
    if (options.typedServiceOnly) {
        const typedProject = path.join(project, 'examples', 'typed-service-plugin');
        const toolsJar = path.join(project, 'tools', 'sdk-tools.jar');
        const buildTypedService = offline => runMaven([...(offline ? ['-o'] : []),
            '-s', settings, `-Dmaven.repo.local=${localRepository}`,
            `-Dpixivdownload.sdk.tools=${toolsJar}`, '-f', path.join(typedProject, 'pom.xml'), 'clean', 'verify']);
        buildTypedService(false);
        stageSdkArtifacts(localRepository, sdkRepository, identity.version);
        buildTypedService(true);
        assertSdkResolution(localRepository, sdkRepository, identity.version);
        verifyTypedServiceArtifacts(typedProject, toolsJar);
        return { project: typedProject, sdkVersion: identity.version };
    }
    const buildTemplates = offline => {
        for (const pom of ['pom.xml', 'examples/download-type-plugin/pom.xml', 'examples/typed-service-plugin/pom.xml']) {
            runMaven([...(offline ? ['-o'] : []), '-s', settings,
                ...(pom.includes('typed-service-plugin') ? [`-Dpixivdownload.sdk.tools=${path.join(project, 'tools', 'sdk-tools.jar')}`] : []),
                `-Dmaven.repo.local=${localRepository}`, '-f', path.join(project, pom), 'clean', 'verify']);
        }
    };
    buildTemplates(false);
    runMaven(['-s', settings, `-Dmaven.repo.local=${localRepository}`,
        `org.apache.maven.plugins:maven-dependency-plugin:${dependencyVersion}:get`,
        `-Dartifact=${SDK_GROUP_ID}:pixivdownload-core-api:${identity.version}`,
    ]);
    stageSdkArtifacts(localRepository, sdkRepository, identity.version);
    buildTemplates(true);
    verifyTypedServiceSchemaRebuild(path.join(project, 'examples', 'typed-service-plugin'),
            path.join(work, 'schema-incremental'), path.join(project, 'tools', 'sdk-tools.jar'), args => {
        const result = spawnSync(command, [...prefix, '-B', '-ntp', '-o', '-s', settings,
            `-Dmaven.repo.local=${localRepository}`, ...args], { cwd: project, env: mavenEnvironment, encoding: 'utf8' });
        process.stdout.write(result.stdout || '');
        process.stderr.write(result.stderr || '');
        return result;
    });
    runMaven(['-o', '-s', settings, `-Dmaven.repo.local=${localRepository}`,
        `org.apache.maven.plugins:maven-dependency-plugin:${dependencyVersion}:get`,
        `-Dartifact=${SDK_GROUP_ID}:pixivdownload-core-api:${identity.version}`,
    ]);
    assertSdkResolution(localRepository, sdkRepository, identity.version);
    const pluginJar = currentPluginJar(path.join(project, 'examples', 'download-type-plugin'));
    thinJarEntries(pluginJar);
    thinJarEntries(currentPluginJar(project));
    const typedServices = verifyTypedServiceArtifacts(path.join(project, 'examples', 'typed-service-plugin'),
            path.join(project, 'tools', 'sdk-tools.jar'));
    run(process.execPath, [fileURLToPath(new URL('./sdk-remote.mjs', import.meta.url)),
        path.join(project, 'examples', 'typed-service-plugin')]);
    return { project, localRepository, pluginJar, typedServices };
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
