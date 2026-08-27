import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { inspectSdkVersion, parseSdkVersion } from '../sdk-version.mjs';

const MODULES = [
    'pixivdownload-sdk-info',
    'pixivdownload-plugin-api',
    'pixivdownload-core-api',
    'pixivdownload-sdk-bom'
];

function write(root, relativePath, content) {
    const file = path.join(root, ...relativePath.split('/'));
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content, 'utf8');
}

function createFixture() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-sdk-version-'));
    write(root, 'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties',
            'version=1.0.0-rc1\n');
    write(root, 'pom.xml', '<properties><revision>1.0.0-rc1</revision>'
            + '<pixivdownload.sdk.version>${revision}</pixivdownload.sdk.version></properties>');
    for (const module of MODULES) {
        write(root, `${module}/pom.xml`, `<project><artifactId>${module}</artifactId>`
                + '<version>${revision}</version>'
                + (module === 'pixivdownload-sdk-bom'
                    ? '<version>${pixivdownload.sdk.version}</version>'.repeat(3)
                    : '')
                + '</project>');
    }
    for (const template of ['minimal-feature-plugin', 'download-type-plugin']) {
        write(root, `plugin-templates/${template}/pom.xml`,
                '<properties><pixivdownload.sdk.version>1.0.0-rc1</pixivdownload.sdk.version></properties>');
    }
    return root;
}

test('版本解析区分稳定版与结构化预发布版', () => {
    assert.deepEqual(parseSdkVersion('2.3.4'), {
        version: '2.3.4',
        major: 2,
        minor: 3,
        patch: 4,
        prereleaseChannel: '',
        prereleaseSequence: 0,
        prerelease: false,
        releaseId: 'sdk-api-v2.3.4',
        compatibilityVersion: '2.3'
    });
    assert.equal(parseSdkVersion('2.3.4-alpha2').prereleaseSequence, 2);
    assert.equal(parseSdkVersion('2.3.4-beta3').prereleaseChannel, 'beta');
    assert.equal(parseSdkVersion('2.3.4-rc12').releaseId, 'sdk-api-v2.3.4-rc12');
    for (const invalid of ['1.0.0-r1', '1.0.0-rc.1', '1.0.0-rc0', '01.0.0', '1.0']) {
        assert.throws(() => parseSdkVersion(invalid), /Invalid SDK semantic version/u);
    }
});

test('SDK 身份事实源与 Maven、BOM 及模板投影必须一致', () => {
    const root = createFixture();
    try {
        assert.equal(inspectSdkVersion(root).releaseId, 'sdk-api-v1.0.0-rc1');
        fs.writeFileSync(path.join(root, 'plugin-templates', 'minimal-feature-plugin', 'pom.xml'),
                '<pixivdownload.sdk.version>1.0.0-rc2</pixivdownload.sdk.version>', 'utf8');
        assert.throws(() => inspectSdkVersion(root), /minimal-feature-plugin.*must be 1\.0\.0-rc1/u);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('旧 revision 元数据和 Maven 版本漂移会被拒绝', () => {
    const root = createFixture();
    try {
        const metadata = path.join(root, 'pixivdownload-sdk-info', 'src', 'main', 'resources', 'META-INF',
                'pixivdownload-sdk.properties');
        fs.appendFileSync(metadata, 'revision=1\n', 'utf8');
        assert.throws(() => inspectSdkVersion(root), /removed revision axis/u);
        fs.writeFileSync(metadata, 'version=1.0.0-rc1\n', 'utf8');
        fs.writeFileSync(path.join(root, 'pom.xml'), '<properties><revision>1.0.0-rc2</revision>'
                + '<pixivdownload.sdk.version>${revision}</pixivdownload.sdk.version></properties>', 'utf8');
        assert.throws(() => inspectSdkVersion(root), /Maven SDK version projection must be 1\.0\.0-rc1/u);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
