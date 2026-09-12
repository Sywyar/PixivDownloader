import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
    assertThinJarEntries,
    createArchive,
    createProjectManifest,
    createReleaseManifest,
    extractArchive,
    initializeProjectGit,
    readRuntimeInput,
    sha256,
    stagePluginTemplates,
    verifyReleaseDirectory,
} from '../sdk-release.mjs';
import { inspectSdkVersion } from '../sdk-version.mjs';

const IDENTITY = inspectSdkVersion(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..'));
const HOST_VERSION = [IDENTITY.major, IDENTITY.minor, IDENTITY.patch].join('.');

test('SDK 的四个可选工程在归档及初始 Git 树中保留相同的标准标识', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-sdk-markers-'));
    try {
        const repoRoot = fileURLToPath(new URL('../../../', import.meta.url));
        const sourceSha = execFileSync('git', ['-C', repoRoot, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
        const workspace = path.join(root, 'workspace');
        stagePluginTemplates(repoRoot, workspace, IDENTITY, sourceSha);
        initializeProjectGit(workspace, { repoRoot, sourceSha, sdkVersion: IDENTITY.version });
        const archive = path.join(root, 'sdk.zip');
        createArchive(workspace, archive);
        const extracted = path.join(root, 'extracted');
        fs.mkdirSync(extracted);
        extractArchive(archive, extracted);
        const git = args => execFileSync('git', ['-C', extracted, ...args]);
        const markers = ['', 'examples/download-type-plugin/', 'examples/gradle-plugin/', 'examples/sbt-plugin/']
                .map(prefix => `${prefix}.pixivdownloader-plugin-project`).sort();
        const tracked = git(['ls-files', '-z']).toString('utf8').split('\0').filter(Boolean);
        assert.deepEqual(tracked.filter(file => file.endsWith('.pixivdownloader-plugin-project')).sort(), markers);
        for (const marker of markers) {
            const bytes = Buffer.from('pixivdownloader-plugin-project-v1\n', 'ascii');
            assert.deepEqual(fs.readFileSync(path.join(extracted, marker)), bytes);
            assert.deepEqual(git(['show', `HEAD:${marker}`]), bytes);
            assert.match(git(['ls-tree', 'HEAD', '--', marker]).toString('utf8'), /^100644 /u);
        }
        assert.equal(git(['status', '--porcelain']).toString('utf8'), '');
        const invalidRoot = path.join(root, 'invalid');
        const invalid = path.join(invalidRoot, 'plugin-templates', 'minimal-feature-plugin');
        fs.mkdirSync(invalid, { recursive: true });
        for (const content of [null, '', '\ufeffpixivdownloader-plugin-project-v1\n',
            'pixivdownloader-plugin-project-v1', 'pixivdownloader-plugin-project-v2\n']) {
            if (content !== null) fs.writeFileSync(path.join(invalid, '.pixivdownloader-plugin-project'), content, 'utf8');
            assert.throws(() => stagePluginTemplates(invalidRoot, path.join(root, 'rejected'), IDENTITY, sourceSha),
                    /project marker/u);
        }
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

const runtime = {
    hostVersion: HOST_VERSION, hostSourceCommitSha: 'a'.repeat(40), platforms: ['windows-x64'],
    downloadUrl: `https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/${IDENTITY.releaseId}/PixivDownload-${HOST_VERSION}-full-offline.zip`,
    archive: { file: `PixivDownload-${HOST_VERSION}-full-offline.zip`, size: 8, sha256: 'b'.repeat(64) },
    host: { file: `PixivDownload-${HOST_VERSION}.jar`, size: 4, sha256: 'c'.repeat(64) },
    pluginsManifest: { file: 'plugins-manifest.json', size: 4, sha256: 'd'.repeat(64) },
};

test('SDK 发布元数据使用结构化版本、精确源码与附件摘要', () => {
    const project = createProjectManifest(IDENTITY, 'a'.repeat(40), '', runtime);
    assert.equal(project.schemaVersion, 3);
    assert.deepEqual(project.developmentRuntime, runtime);
    assert.equal(project.prerelease, IDENTITY.prerelease);
    assert.equal(project.prereleaseChannel, IDENTITY.prereleaseChannel || null);
    assert.equal(project.prereleaseSequence, IDENTITY.prereleaseSequence || null);
    assert.equal(project.minimumVerifiedHostRelease, null);
    assert.equal(project.verifiedHostSourceSha, null);
    assert.equal(project.mavenCoordinates.length, 5);
    assert.ok(project.mavenCoordinates.every(item => item.groupId === 'io.github.sywyar.pixivdownloader'));
    const hostVerified = createProjectManifest(IDENTITY, 'a'.repeat(40), `v${HOST_VERSION}`, runtime);
    assert.equal(hostVerified.minimumVerifiedHostRelease, `v${HOST_VERSION}`);
    assert.equal(hostVerified.verifiedHostSourceSha, 'a'.repeat(40));
    const release = createReleaseManifest(project, [{ file: '/tmp/sdk.zip', size: 10, sha256: 'f'.repeat(64) }]);
    assert.equal(release.schemaVersion, 4);
    assert.deepEqual(release.artifacts, [{ file: 'sdk.zip', size: 10, sha256: 'f'.repeat(64) }]);
});

test('运行附件必须与固定源码、发行地址和实际字节一致', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-sdk-runtime-'));
    try {
        const archive = path.join(root, runtime.archive.file);
        fs.writeFileSync(archive, 'runtime\n', 'utf8');
        const metadata = structuredClone(runtime);
        metadata.archive.sha256 = sha256(archive);
        const file = path.join(root, 'sdk-runtime.json');
        const input = { schemaVersion: 1, sdkVersion: IDENTITY.version, sourceCommitSha: 'a'.repeat(40), developmentRuntime: metadata };
        const write = () => fs.writeFileSync(file, JSON.stringify(input), 'utf8');
        write();
        const read = () => readRuntimeInput(IDENTITY, 'a'.repeat(40), file);
        assert.equal(read().archive, archive);
        input.sourceCommitSha = 'e'.repeat(40); write();
        assert.throws(read, /source commit/u);
        input.sourceCommitSha = 'a'.repeat(40);
        metadata.downloadUrl = metadata.downloadUrl.replace(IDENTITY.releaseId, 'latest'); write();
        assert.throws(read, /identity mismatch/u);
        metadata.downloadUrl = runtime.downloadUrl; write();
        fs.writeFileSync(archive, 'changed\n', 'utf8');
        assert.throws(read, /bytes do not match/u);
        assert.throws(() => createProjectManifest(IDENTITY, 'a'.repeat(40)), /fixed development runtime/u);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('SDK ZIP 使用固定时间产生可重复字节', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-sdk-archive-'));
    try {
        const source = path.join(root, 'source');
        fs.mkdirSync(path.join(source, 'nested'), { recursive: true });
        fs.writeFileSync(path.join(source, 'README.md'), 'SDK\n', 'utf8');
        fs.writeFileSync(path.join(source, 'nested', 'file.txt'), 'content\n', 'utf8');
        const first = path.join(root, 'first.zip');
        const second = path.join(root, 'second.zip');
        assert.equal(createArchive(source, first), createArchive(source, second));
        assert.equal(sha256(first), sha256(second));
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('SDK ZIP 初始提交使用源码作者和打包时间，英文要点记录版本且可跟踪修改', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-sdk-git-'));
    try {
        const repoRoot = path.join(root, 'repository');
        fs.mkdirSync(repoRoot);
        const authorName = 'Source Author 作者';
        const authorEmail = 'source@example.invalid';
        const sourceDate = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
        const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^GIT_/iu.test(key)));
        Object.assign(env, {
            GIT_CONFIG_NOSYSTEM: '1',
            GIT_CONFIG_GLOBAL: process.platform === 'win32' ? 'NUL' : '/dev/null',
            GIT_AUTHOR_NAME: authorName, GIT_AUTHOR_EMAIL: authorEmail, GIT_AUTHOR_DATE: sourceDate,
            GIT_COMMITTER_NAME: authorName, GIT_COMMITTER_EMAIL: authorEmail, GIT_COMMITTER_DATE: sourceDate,
        });
        const sourceGit = args => execFileSync('git', ['-C', repoRoot, ...args], { env, encoding: 'utf8' });
        sourceGit(['init', '--quiet', '--template=']);
        const sourceMessage = path.join(repoRoot, '.git', 'fixture-message');
        fs.writeFileSync(sourceMessage, 'chore: Create source fixture\n', 'utf8');
        sourceGit(['commit', '--quiet', '--allow-empty', '-F', sourceMessage]);
        const sourceSha = sourceGit(['rev-parse', 'HEAD']).trim();
        const options = { repoRoot, sourceSha, sdkVersion: IDENTITY.version };
        const files = new Map([
            ['mvnw', Buffer.from('#!/bin/sh\nprintf "%s\\n" "$1"\n')],
            ['examples/gradle-plugin/gradlew', Buffer.from('#!/bin/sh\nprintf "%s\\n" "$1"\n')],
            ['源码 文件.txt', Buffer.from('source\n')],
            ['tools/payload.bin', Buffer.from([0, 1, 128, 255])],
            [`${'nested/'.repeat(40)}source.txt`, Buffer.from('long path\n')],
            ['.gitignore', Buffer.from('/target/\n*.bin\n')],
        ]);
        const source = path.join(root, 'workspace');
        for (const [relative, bytes] of files) {
            const file = path.join(source, relative);
            fs.mkdirSync(path.dirname(file), { recursive: true });
            fs.writeFileSync(file, bytes);
        }
        const before = Math.floor(Date.now() / 1000);
        initializeProjectGit(source, options);
        const after = Math.floor(Date.now() / 1000);
        const zip = path.join(root, 'sdk.zip');
        createArchive(source, zip);
        const extracted = path.join(root, 'extracted');
        fs.mkdirSync(extracted);
        extractArchive(zip, extracted);
        // 同时检验 Windows 创建的 ZIP 权限；Unix 再直接执行解压出的脚本。
        execFileSync(process.platform === 'win32' ? 'python' : 'python3', ['-X', 'utf8', '-c', `
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name in sys.argv[2:]:
        entry = archive.getinfo(name)
        assert entry.create_system == 3 and (entry.external_attr >> 16) & 0o111, name
`, zip, 'mvnw', 'examples/gradle-plugin/gradlew']);
        const git = args => execFileSync('git', ['-C', extracted, ...args]);
        for (const script of ['mvnw', 'examples/gradle-plugin/gradlew']) {
            assert.match(git(['ls-tree', 'HEAD', '--', script]).toString(), /^100755 /u);
            if (process.platform !== 'win32') {
                assert.equal(execFileSync(path.join(extracted, script), ['SDK 源码'], { encoding: 'utf8' }), 'SDK 源码\n');
            }
        }
        const metadata = git(['log', '-1', '--format=%an%x00%ae%x00%cn%x00%ce%x00%at%x00%ct'])
                .toString('utf8').trimEnd().split('\0');
        assert.deepEqual(metadata.slice(0, 4), [authorName, authorEmail, authorName, authorEmail]);
        assert.equal(metadata[4], metadata[5]);
        assert.ok(Number(metadata[5]) >= before && Number(metadata[5]) <= after);
        const [subject, separator, ...body] = git(['log', '-1', '--format=%B']).toString('utf8').trimEnd().split('\n');
        assert.match(subject, /^chore\(sdk\): [\x20-\x7e]+$/u);
        assert.equal(separator, '');
        assert.ok(body.length > 0 && body.every(line => /^- [\x20-\x7e]+$/u.test(line)));
        assert.ok(body.some(line => line.includes(IDENTITY.version)));
        assert.ok(body.some(line => line.includes(sourceSha)));
        assert.equal(git(['rev-list', '--count', 'HEAD']).toString().trim(), '1');
        assert.equal(git(['remote']).toString(), '');
        assert.equal(git(['status', '--porcelain']).toString(), '');
        assert.deepEqual(git(['ls-files', '-z']).toString('utf8').split('\0').filter(Boolean).sort(),
                [...files.keys()].sort());
        for (const [relative, bytes] of files) {
            assert.deepEqual(git(['show', `HEAD:${relative}`]), bytes);
        }
        fs.appendFileSync(path.join(extracted, '源码 文件.txt'), 'changed\n', 'utf8');
        fs.mkdirSync(path.join(extracted, 'target'));
        fs.writeFileSync(path.join(extracted, 'target', 'build.txt'), 'output\n', 'utf8');
        assert.equal(git(['diff', '--name-only', '-z']).toString('utf8'), '源码 文件.txt\0');
        assert.equal(git(['ls-files', '--others', '--exclude-standard']).toString(), '');
        const head = git(['rev-parse', 'HEAD']);
        assert.throws(() => initializeProjectGit(extracted, options), /already contains Git metadata/u);
        assert.deepEqual(git(['rev-parse', 'HEAD']), head);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('发布恢复复用完整原始附件，拒绝换源码、漏附件和篡改字节', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-sdk-frozen-'));
    try {
        const identity = IDENTITY;
        const source = 'a'.repeat(40);
        const fixedRuntime = structuredClone(runtime);
        const sdkName = `PixivDownloader-Plugin-SDK-${identity.version}.zip`;
        for (const file of [sdkName, runtime.archive.file]) fs.writeFileSync(path.join(root, file), 'fixture\n', 'utf8');
        const describe = file => ({ file, size: 8, sha256: sha256(path.join(root, file)) });
        fixedRuntime.archive = describe(runtime.archive.file);
        const metadata = createReleaseManifest(createProjectManifest(identity, source, '', fixedRuntime),
                [describe(sdkName), fixedRuntime.archive]);
        const write = () => {
            fs.writeFileSync(path.join(root, 'sdk-release.json'), JSON.stringify(metadata), 'utf8');
            fs.writeFileSync(path.join(root, 'SHA256SUMS'), [...metadata.artifacts,
                { file: 'sdk-release.json', sha256: sha256(path.join(root, 'sdk-release.json')) }]
                    .map(item => `${item.sha256}  ${item.file}\n`).join(''), 'utf8');
        };
        write();
        assert.deepEqual(verifyReleaseDirectory(root, identity, source), metadata);
        assert.throws(() => verifyReleaseDirectory(root, identity, 'c'.repeat(40)), /source commit/u);
        const removed = metadata.artifacts.pop(); write();
        assert.throws(() => verifyReleaseDirectory(root, identity, source), /artifacts/u);
        metadata.artifacts.push(removed); write();
        fs.appendFileSync(path.join(root, 'SHA256SUMS'), `${'d'.repeat(64)}  extra.zip\n`, 'utf8');
        assert.throws(() => verifyReleaseDirectory(root, identity, source), /checksums/u);
        write();
        fs.writeFileSync(path.join(root, runtime.archive.file), 'changed\n', 'utf8');
        assert.throws(() => verifyReleaseDirectory(root, identity, source), /bytes do not match/u);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('SDK 消费者拒绝打入宿主提供类的非 thin JAR', () => {
    assert.doesNotThrow(() => assertThinJarEntries(['plugin.properties', 'com/example/ExamplePlugin.class']));
    assert.throws(
            () => assertThinJarEntries(['plugin.properties', 'org/pf4j/Plugin.class']),
            /forbidden bundled entry/u);
    assert.throws(() => assertThinJarEntries(['com/example/ExamplePlugin.class']), /root plugin\.properties/u);
    assert.throws(() => assertThinJarEntries(['plugin.properties', '.pixivdownloader-plugin-project']),
            /forbidden bundled entry/u);
});
