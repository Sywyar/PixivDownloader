#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

import { inspectSdkVersion, SDK_ARTIFACTS, SDK_GROUP_ID } from './sdk-version.mjs';
import { stageCommunityBundle } from './community-contracts.mjs';
const EXECUTABLE_ENTRIES = ['mvnw', 'examples/gradle-plugin/gradlew', 'run.sh'];

function fail(message) {
    throw new Error(message);
}

export function sha256(file) {
    const hash = crypto.createHash('sha256');
    const descriptor = fs.openSync(file, 'r');
    try {
        const buffer = Buffer.alloc(128 * 1024);
        for (let count; (count = fs.readSync(descriptor, buffer)) > 0;) hash.update(buffer.subarray(0, count));
        return hash.digest('hex');
    } finally {
        fs.closeSync(descriptor);
    }
}

export function createProjectManifest(identity, sourceSha, minimumVerifiedHostRelease = '', developmentRuntime) {
    if (!developmentRuntime) fail('SDK project requires a fixed development runtime');
    return {
        schemaVersion: 3,
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
        developmentRuntime,
        mavenCoordinates: SDK_ARTIFACTS.map(([artifactId, packaging]) => ({
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
        schemaVersion: 4,
        artifacts: assets.map(asset => ({
            file: path.basename(asset.file),
            size: asset.size,
            sha256: asset.sha256,
        })),
    };
}

export function readRuntimeInput(identity, sourceSha, manifestFile) {
    requireFile(manifestFile);
    if (fs.statSync(manifestFile).size > 1024 * 1024) fail('SDK runtime metadata exceeds the byte limit');
    const input = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
    const runtime = input.developmentRuntime;
    if (input.schemaVersion !== 1 || input.sdkVersion !== identity.version || input.sourceCommitSha !== sourceSha) {
        fail('SDK runtime does not identify this SDK and source commit');
    }
    validateDevelopmentRuntime(identity, sourceSha, runtime);
    const archive = path.join(path.dirname(manifestFile), runtime.archive.file);
    verifyArtifact(archive, runtime.archive);
    return { developmentRuntime: runtime, archive };
}

function validateDevelopmentRuntime(identity, sourceSha, runtime) {
    if (!runtime || runtime.hostSourceCommitSha !== sourceSha
            || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u.test(runtime.hostVersion)) {
        fail('SDK runtime does not identify this SDK and source commit');
    }
    const allowedPlatforms = ['windows-x64', 'windows-arm64', 'linux-x64', 'linux-arm64', 'macos-arm64'];
    if (!Array.isArray(runtime.platforms) || runtime.platforms.length === 0
            || new Set(runtime.platforms).size !== runtime.platforms.length
            || runtime.platforms.some(platform => !allowedPlatforms.includes(platform))) fail('invalid SDK runtime platforms');
    for (const artifact of [runtime.archive, runtime.host, runtime.pluginsManifest]) {
        if (!artifact || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/u.test(artifact.file)
                || !Number.isSafeInteger(artifact.size) || artifact.size <= 0 || artifact.size > 512 * 1024 * 1024
                || !/^[0-9a-f]{64}$/u.test(artifact.sha256)) fail('invalid SDK runtime artifact');
    }
    if (runtime.archive.file !== `PixivDownload-${runtime.hostVersion}-full-offline.zip`
            || runtime.host.file !== `PixivDownload-${runtime.hostVersion}.jar`
            || runtime.pluginsManifest.file !== 'plugins-manifest.json'
            || runtime.downloadUrl !== `https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/${identity.releaseId}/${runtime.archive.file}`) {
        fail('SDK runtime archive identity mismatch');
    }
}

function verifyArtifact(file, expected) {
    requireFile(file);
    if (!Number.isSafeInteger(expected.size) || expected.size <= 0 || expected.size > 512 * 1024 * 1024
            || !/^[0-9a-f]{64}$/u.test(expected.sha256)
            || fs.statSync(file).size !== expected.size || sha256(file) !== expected.sha256) {
        fail(`SDK artifact bytes do not match the fixed manifest: ${path.basename(file)}`);
    }
}

// 发布 workflow 负责验签；这里验证签名覆盖的完整发行附件。
export function verifyReleaseDirectory(directory, identity, sourceSha) {
    const metadataFile = path.join(directory, 'sdk-release.json');
    requireFile(metadataFile);
    if (fs.statSync(metadataFile).size > 1024 * 1024) fail('SDK release metadata exceeds the byte limit');
    const metadata = JSON.parse(fs.readFileSync(metadataFile, 'utf8'));
    validateDevelopmentRuntime(identity, sourceSha, metadata.developmentRuntime);
    const { artifacts, ...project } = metadata;
    const expectedProject = createProjectManifest(identity, sourceSha, metadata.minimumVerifiedHostRelease,
            metadata.developmentRuntime);
    if (!isDeepStrictEqual(project, { ...expectedProject, schemaVersion: 4 })) {
        fail('SDK release metadata does not identify the exact source and coordinates');
    }
    const expectedNames = [`PixivDownloader-Plugin-SDK-${identity.version}.zip`, metadata.developmentRuntime.archive.file];
    if (!Array.isArray(artifacts) || artifacts.length !== 2
            || !isDeepStrictEqual(artifacts.map(item => item.file).sort(), [...expectedNames].sort())
            || !isDeepStrictEqual(artifacts.find(item => item.file === expectedNames[1]), metadata.developmentRuntime.archive)) {
        fail('SDK release contains unexpected or inconsistent artifacts');
    }
    for (const artifact of artifacts) verifyArtifact(path.join(directory, artifact.file), artifact);
    const checksumFile = path.join(directory, 'SHA256SUMS');
    requireFile(checksumFile);
    if (fs.statSync(checksumFile).size > 1024 * 1024) fail('SDK checksums exceed the byte limit');
    const expectedChecksums = [...artifacts, { file: 'sdk-release.json', sha256: sha256(metadataFile) }]
            .map(item => `${item.sha256}  ${item.file}`).sort();
    if (!isDeepStrictEqual(fs.readFileSync(checksumFile, 'utf8').trimEnd().split(/\r?\n/u).sort(), expectedChecksums)) {
        fail('SDK release checksums do not match the complete frozen payload');
    }
    return metadata;
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
    const violation = entries.find(entry => forbidden.some(prefix => entry.startsWith(prefix))
            || entry.split('/').at(-1) === '.pixivdownloader-plugin-project');
    if (violation) {
        fail(`SDK template artifact contains forbidden bundled entry: ${violation}`);
    }
}

function requireFile(file) {
    if (!fs.statSync(file, { throwIfNoEntry: false })?.isFile()) {
        fail(`missing SDK release input: ${file}`);
    }
}

function assertConsumerPom(file, artifactId, version, sourceSha) {
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
    if (!pom.includes(`<tag>${sourceSha}</tag>`)) {
        fail(`${artifactId} consumer POM must identify source commit ${sourceSha}`);
    }
    if (artifactId === 'pixivdownload-sdk-bom') {
        const managed = [...pom.matchAll(/<dependency>[\s\S]*?<\/dependency>/gu)]
                .map(match => match[0])
                .filter(block => block.includes(`<groupId>${SDK_GROUP_ID}</groupId>`));
        const expected = new Set(SDK_ARTIFACTS.filter(([, packaging]) => packaging === 'jar')
                .map(([artifact]) => artifact));
        if (managed.length !== expected.size) fail('SDK BOM consumer POM must manage all SDK JAR artifacts');
        for (const block of managed) {
            const managedArtifact = block.match(/<artifactId>([^<]+)<\/artifactId>/u)?.[1];
            if (!expected.delete(managedArtifact) || !block.includes(`<version>${version}</version>`)) {
                fail(`SDK BOM consumer POM contains an invalid managed artifact: ${managedArtifact ?? 'missing'}`);
            }
        }
        if (expected.size !== 0) fail(`SDK BOM consumer POM is missing: ${[...expected].join(', ')}`);
    }
}

function validateReleaseInputs(root, identity, sourceSha) {
    for (const [artifactId, packaging] of SDK_ARTIFACTS) {
        const moduleRoot = path.join(root, artifactId);
        assertConsumerPom(path.join(moduleRoot, 'target', 'flattened-pom.xml'), artifactId, identity.version,
                sourceSha);
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

export function stagePluginTemplates(root, workspace, identity, sourceSha) {
    const markerName = '.pixivdownloader-plugin-project';
    for (const template of ['minimal-feature-plugin', 'download-type-plugin']) {
        const marker = path.join(root, 'plugin-templates', template, markerName);
        if (!fs.lstatSync(marker, { throwIfNoEntry: false })?.isFile()
                || !fs.readFileSync(marker).equals(Buffer.from('pixivdownloader-plugin-project-v1\n', 'ascii'))) {
            fail(`invalid SDK plugin project marker: ${template}`);
        }
    }
    copyTree(path.join(root, 'plugin-templates', 'minimal-feature-plugin'), workspace);
    const downloadExample = path.join(workspace, 'examples', 'download-type-plugin');
    copyTree(path.join(root, 'plugin-templates', 'download-type-plugin'), downloadExample);
    if (!fs.existsSync(path.join(downloadExample, 'README_en.md'))) {
        fs.copyFileSync(path.join(downloadExample, 'README.md'), path.join(downloadExample, 'README_en.md'));
    }
    renderOverlay(path.join(root, 'plugin-templates', 'sdk-package'), workspace, {
        '@SDK_VERSION@': identity.version,
        '@SDK_RELEASE_ID@': identity.releaseId,
        '@SOURCE_SHA@': sourceSha,
    });
    for (const tool of ['gradle', 'sbt']) {
        const example = path.join(workspace, 'examples', `${tool}-plugin`);
        copyTree(path.join(root, 'plugin-templates', 'minimal-feature-plugin', 'src', 'main'),
                path.join(example, 'src', 'main'));
        fs.copyFileSync(path.join(workspace, markerName), path.join(example, markerName));
    }
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
        // 仅在 SDK 开发包中生成 IDEA 配置，避免模板被主仓库工程加载。
        if (relative.endsWith('.run.xml.template')) {
            fs.renameSync(file, file.slice(0, -'.template'.length));
        }
    }
}

function addMavenRuntimeTasks(project, toolsRelativePath) {
    const pom = path.join(project, 'pom.xml');
    let source = fs.readFileSync(pom, 'utf8');
    const marker = /(<artifactId>exec-maven-plugin<\/artifactId>[\s\S]*?<executions>)/u;
    if (!marker.test(source)) fail('SDK Maven template is missing its exec plugin');
    const executions = ['run', 'debug', 'debug-connect', 'stop', 'prepare'].map(command => `
                    <execution>
                        <id>sdk-${command}</id>
                        <goals><goal>exec</goal></goals>
                        <configuration>
                            <executable>java</executable>
                            <arguments>
                                <argument>-Dfile.encoding=UTF-8</argument>
                                <argument>-jar</argument>
                                <argument>\${project.basedir}/${toolsRelativePath}</argument>
                                <argument>${command === 'debug-connect' ? 'debug' : command}</argument>
                                <argument>\${project.basedir}</argument>${['run', 'debug', 'debug-connect'].includes(command) ? `
                                <argument>\${project.build.directory}/\${project.build.finalName}.jar</argument>` : ''}${command === 'debug-connect' ? `
                                <argument>--debug-connect</argument>` : ''}
                            </arguments>
                        </configuration>
                    </execution>`).join('');
    source = source.replace(marker, `$1${executions}`);
    fs.writeFileSync(pom, source, 'utf8');
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

export function initializeProjectGit(workspace, { repoRoot, sourceSha, sdkVersion }) {
    const gitDirectory = path.join(workspace, '.git');
    if (fs.existsSync(gitDirectory)) fail('SDK workspace already contains Git metadata');
    // 作者来自指定源码提交，时间记录本次打包，不继承构建机的仓库位置、模板或签名配置。
    const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^GIT_/iu.test(key)));
    Object.assign(env, {
        GIT_CONFIG_NOSYSTEM: '1',
        GIT_CONFIG_GLOBAL: process.platform === 'win32' ? 'NUL' : '/dev/null',
        GIT_ATTR_NOSYSTEM: '1',
    });
    const [authorName, authorEmail] = execFileSync('git', [
        '-C', repoRoot, 'show', '--no-patch', '--format=%an%x00%ae', sourceSha, '--',
    ], { env, encoding: 'utf8', windowsHide: true }).trimEnd().split('\0');
    const commitDate = new Date().toISOString();
    Object.assign(env, {
        GIT_AUTHOR_NAME: authorName,
        GIT_AUTHOR_EMAIL: authorEmail,
        GIT_COMMITTER_NAME: authorName,
        GIT_COMMITTER_EMAIL: authorEmail,
        GIT_AUTHOR_DATE: commitDate,
        GIT_COMMITTER_DATE: commitDate,
    });
    const git = args => execFileSync('git', ['-C', workspace, ...args], {
        env, encoding: 'utf8', windowsHide: true,
    });
    git(['init', '--quiet', '--template=', '--initial-branch=main', '--object-format=sha1']);
    fs.writeFileSync(path.join(gitDirectory, 'config'),
            '[core]\n\trepositoryformatversion = 0\n\tfilemode = false\n\tbare = false\n'
            + '\tlogallrefupdates = true\n\tautocrlf = false\n\tlongpaths = true\n', 'utf8');
    git(['add', '--all', '--force', '--', '.']);
    const executables = EXECUTABLE_ENTRIES.filter(file => fs.existsSync(path.join(workspace, file)));
    if (executables.length) git(['update-index', '--chmod=+x', '--', ...executables]);
    const message = path.join(gitDirectory, 'initial-message');
    fs.writeFileSync(message, 'chore(sdk): Initialize plugin development baseline\n\n'
            + `- Set up the development workspace for SDK ${sdkVersion}\n`
            + '- Include plugin examples, build tools and API documentation\n'
            + `- Generate the baseline from source commit ${sourceSha}\n`, 'utf8');
    git(['-c', 'maintenance.auto=false', 'commit', '--quiet', '-F', message]);
    fs.rmSync(message);
    // 索引只保留提交树，排除构建机的 inode、ctime 等信息。
    git(['read-tree', '--empty']);
    git(['read-tree', 'HEAD']);
}

export function createArchive(source, destination) {
    fs.rmSync(destination, { force: true });
    const entries = regularFiles(source)
            .map(file => path.relative(source, file).split(path.sep).join('/'))
            .sort();
    // zipfile 显式写入 Unix 权限，Windows 打包也生成可直接执行的启动脚本。
    execFileSync(process.platform === 'win32' ? 'python' : 'python3', ['-X', 'utf8', '-c', `
import json, shutil, sys, zipfile
entries, executables = json.load(sys.stdin)
with zipfile.ZipFile(sys.argv[1], 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for name in entries:
        entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        entry.create_system = 3
        entry.external_attr = (0o100755 if name in executables else 0o100644) << 16
        entry.compress_type = zipfile.ZIP_DEFLATED
        with open(name, 'rb') as source, archive.open(entry, 'w') as target:
            shutil.copyfileobj(source, target)
`, path.resolve(destination)], { cwd: source, input: JSON.stringify([entries, EXECUTABLE_ENTRIES]), windowsHide: true });
    requireFile(destination);
    return sha256(destination);
}

export function extractArchive(archive, destination) {
    const command = process.platform === 'win32' ? 'jar' : 'unzip';
    const args = process.platform === 'win32' ? ['--extract', '--file', path.resolve(archive)] : ['-q', path.resolve(archive)];
    execFileSync(command, args, { cwd: destination, stdio: 'inherit', windowsHide: true });
}

function writeJson(file, value) {
    fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function parseArguments(argv) {
    const options = { repoRoot: '.', output: '', sourceSha: '', minimumHostRelease: '', runtimeManifest: '', toolsJar: '' };
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        const value = argv[index + 1];
        if (argument === '--repo-root') options.repoRoot = value;
        else if (argument === '--output') options.output = value;
        else if (argument === '--source-sha') options.sourceSha = value;
        else if (argument === '--minimum-host-release') options.minimumHostRelease = value;
        else if (argument === '--runtime-manifest') options.runtimeManifest = value;
        else if (argument === '--tools-jar') options.toolsJar = value;
        else if (argument === '--verify-directory') options.verifyDirectory = value;
        else fail(`unknown argument: ${argument}`);
        index += 1;
    }
    if ((!options.verifyDirectory && (!options.output || !options.runtimeManifest || !options.toolsJar))
            || !/^[0-9a-f]{40}$/u.test(options.sourceSha)) {
        fail('usage: sdk-release.mjs --repo-root <path> --output <target-child> --source-sha <40-hex> --runtime-manifest <file> --tools-jar <file> [--minimum-host-release <version>]');
    }
    return options;
}

export function assembleRelease(options) {
    const root = path.resolve(options.repoRoot);
    const output = safeOutput(root, options.output);
    const identity = inspectSdkVersion(root);
    validateReleaseInputs(root, identity, options.sourceSha);
    const runtime = readRuntimeInput(identity, options.sourceSha, path.resolve(options.runtimeManifest));
    requireFile(options.toolsJar);
    fs.rmSync(output, { recursive: true, force: true });
    fs.mkdirSync(output, { recursive: true });

    const work = path.join(output, '.work');
    const workspace = path.join(work, 'workspace');
    fs.mkdirSync(path.dirname(workspace), { recursive: true });
    stagePluginTemplates(root, workspace, identity, options.sourceSha);
    fs.cpSync(path.join(root, '.mvn'), path.join(workspace, '.mvn'), { recursive: true });
    for (const file of ['mvnw', 'mvnw.cmd', 'LICENSE']) {
        fs.copyFileSync(path.join(root, file), path.join(workspace, file));
    }
    if (process.platform !== 'win32') fs.chmodSync(path.join(workspace, 'mvnw'), 0o755);

    fs.mkdirSync(path.join(workspace, 'tools'), { recursive: true });
    fs.copyFileSync(options.toolsJar, path.join(workspace, 'tools', 'sdk-tools.jar'));
    stageCommunityBundle(root, workspace, path.join(workspace, 'tools', 'sdk-tools.jar'), options.sourceSha);
    const projectManifest = createProjectManifest(identity, options.sourceSha, options.minimumHostRelease,
            runtime.developmentRuntime);
    writeJson(path.join(workspace, 'sdk-project.json'), projectManifest);
    const downloadExample = path.join(workspace, 'examples', 'download-type-plugin');
    writeJson(path.join(downloadExample, 'sdk-project.json'), projectManifest);
    addMavenRuntimeTasks(workspace, 'tools/sdk-tools.jar');
    addMavenRuntimeTasks(downloadExample, '../../tools/sdk-tools.jar');
    for (const tool of ['gradle', 'sbt']) {
        const example = path.join(workspace, 'examples', `${tool}-plugin`);
        writeJson(path.join(example, 'sdk-project.json'), projectManifest);
    }
    const gradle = path.join(workspace, 'examples', 'gradle-plugin');
    const gradleSource = path.join(root, 'pixivdownload-plugin-gui-compose');
    fs.cpSync(path.join(gradleSource, 'gradle'), path.join(gradle, 'gradle'), { recursive: true });
    for (const wrapper of ['gradlew', 'gradlew.bat']) {
        fs.copyFileSync(path.join(gradleSource, wrapper), path.join(gradle, wrapper));
    }
    if (process.platform !== 'win32') fs.chmodSync(path.join(gradle, 'gradlew'), 0o755);
    copyTree(path.join(root, 'target', 'sdk-javadocs'), path.join(workspace, 'docs', 'javadocs'));
    initializeProjectGit(workspace, { repoRoot: root, sourceSha: options.sourceSha, sdkVersion: identity.version });

    const sdkZip = path.join(output, `PixivDownloader-Plugin-SDK-${identity.version}.zip`);
    const assets = [
        { file: sdkZip, sha256: createArchive(workspace, sdkZip) },
    ];
    assets[0].size = fs.statSync(sdkZip).size;
    const runtimeArchive = path.join(output, path.basename(runtime.archive));
    fs.copyFileSync(runtime.archive, runtimeArchive);
    assets.push({ file: runtimeArchive, size: runtime.developmentRuntime.archive.size,
        sha256: runtime.developmentRuntime.archive.sha256 });
    const releaseMetadata = path.join(output, 'sdk-release.json');
    writeJson(releaseMetadata, createReleaseManifest(projectManifest, assets));
    const checksumEntries = [...assets, { file: releaseMetadata, sha256: sha256(releaseMetadata) }];
    fs.writeFileSync(path.join(output, 'SHA256SUMS'),
            `${checksumEntries.map(asset => `${asset.sha256}  ${path.basename(asset.file)}`).join('\n')}\n`, 'utf8');
    fs.rmSync(work, { recursive: true, force: true });
    return { identity, output, assets, releaseMetadata };
}

function main() {
    const options = parseArguments(process.argv.slice(2));
    if (options.verifyDirectory) {
        verifyReleaseDirectory(path.resolve(options.verifyDirectory), inspectSdkVersion(options.repoRoot), options.sourceSha);
        process.stdout.write('Verified the complete fixed SDK release payload.\n');
        return;
    }
    const result = assembleRelease(options);
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
