import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
    assertThinJarEntries,
    createArchive,
    createProjectManifest,
    createReleaseManifest,
    sha256,
} from '../sdk-release.mjs';
import { parseSdkVersion } from '../sdk-version.mjs';

test('SDK 发布元数据使用结构化版本、精确源码与附件摘要', () => {
    const project = createProjectManifest(parseSdkVersion('1.2.3-rc4'), 'a'.repeat(40));
    assert.equal(project.prerelease, true);
    assert.equal(project.prereleaseChannel, 'rc');
    assert.equal(project.prereleaseSequence, 4);
    assert.equal(project.minimumVerifiedHostRelease, null);
    assert.equal(project.verifiedHostSourceSha, null);
    assert.equal(project.mavenCoordinates.length, 4);
    assert.ok(project.mavenCoordinates.every(item => item.groupId === 'io.github.sywyar.pixivdownloader'));
    const hostVerified = createProjectManifest(parseSdkVersion('1.2.3-rc4'), 'a'.repeat(40), 'v1.14.0');
    assert.equal(hostVerified.minimumVerifiedHostRelease, 'v1.14.0');
    assert.equal(hostVerified.verifiedHostSourceSha, 'a'.repeat(40));
    const release = createReleaseManifest(project, [{ file: '/tmp/sdk.zip', sha256: 'f'.repeat(64) }]);
    assert.deepEqual(release.artifacts, [{ file: 'sdk.zip', sha256: 'f'.repeat(64) }]);
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

test('SDK 消费者拒绝打入宿主提供类的非 thin JAR', () => {
    assert.doesNotThrow(() => assertThinJarEntries(['plugin.properties', 'com/example/ExamplePlugin.class']));
    assert.throws(
            () => assertThinJarEntries(['plugin.properties', 'org/pf4j/Plugin.class']),
            /forbidden bundled entry/u);
    assert.throws(() => assertThinJarEntries(['com/example/ExamplePlugin.class']), /root plugin\.properties/u);
});
