import assert from 'node:assert/strict';
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
    readRuntimeInput,
    sha256,
    verifyReleaseDirectory,
} from '../sdk-release.mjs';
import { inspectSdkVersion } from '../sdk-version.mjs';

const IDENTITY = inspectSdkVersion(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..'));
const HOST_VERSION = [IDENTITY.major, IDENTITY.minor, IDENTITY.patch].join('.');

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
});
