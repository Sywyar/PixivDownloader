import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { inspectSdkVersion, parseSdkVersion, sdkModulesAtRef } from '../sdk-version.mjs';

const CURRENT = inspectSdkVersion(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..'));
const STABLE = `${CURRENT.major}.${CURRENT.minor}.${CURRENT.patch}`;
const NEXT = `${CURRENT.major}.${CURRENT.minor}.${CURRENT.patch + 1}`;
const SEQUENCE = Math.max(1, CURRENT.prereleaseSequence);

const MODULES = [
    'pixivdownload-sdk-info',
    'pixivdownload-plugin-api',
    'pixivdownload-core-api',
    'pixivdownload-sdk-bom',
    'pixivdownload-sdk'
];

function write(root, relativePath, content) {
    const file = path.join(root, ...relativePath.split('/'));
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content, 'utf8');
}

function createFixture() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-sdk-version-'));
    write(root, 'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties',
            `version=${CURRENT.version}\n`);
    write(root, 'pom.xml', `<properties><revision>${CURRENT.version}</revision>`
            + '<pixivdownload.sdk.version>${revision}</pixivdownload.sdk.version></properties>'
            + '<modules>' + MODULES.map(module => `<module>${module}</module>`).join('') + '</modules>');
    for (const module of MODULES) {
        write(root, `${module}/pom.xml`, '<project><parent></parent>'
                + `<groupId>io.github.sywyar.pixivdownloader</groupId><artifactId>${module}</artifactId>`
                + '<version>${revision}</version>'
                + (module === 'pixivdownload-sdk-bom'
                    ? '<groupId>io.github.sywyar.pixivdownloader</groupId><artifactId>pixivdownload-sdk-info</artifactId>'
                        + '<version>${pixivdownload.sdk.version}</version>'
                        + '<groupId>io.github.sywyar.pixivdownloader</groupId><artifactId>pixivdownload-plugin-api</artifactId>'
                        + '<version>${pixivdownload.sdk.version}</version>'
                        + '<groupId>io.github.sywyar.pixivdownloader</groupId><artifactId>pixivdownload-core-api</artifactId>'
                        + '<version>${pixivdownload.sdk.version}</version>'
                        + '<groupId>io.github.sywyar.pixivdownloader</groupId><artifactId>pixivdownload-sdk</artifactId>'
                        + '<version>${pixivdownload.sdk.version}</version>'
                    : '')
                + '</project>');
    }
    for (const template of ['minimal-feature-plugin', 'download-type-plugin']) {
        write(root, `plugin-templates/${template}/pom.xml`,
                '<groupId>io.github.sywyar.pixivdownloader</groupId>'
                + '<artifactId>pixivdownload-sdk</artifactId>'
                + `<properties><pixivdownload.sdk.version>${CURRENT.version}</pixivdownload.sdk.version></properties>`);
    }
    return root;
}

test('版本解析区分稳定版与结构化预发布版', () => {
    assert.deepEqual(parseSdkVersion(STABLE), {
        version: STABLE,
        major: CURRENT.major,
        minor: CURRENT.minor,
        patch: CURRENT.patch,
        prereleaseChannel: '',
        prereleaseSequence: 0,
        prerelease: false,
        releaseId: `sdk-api-v${STABLE}`,
        compatibilityVersion: `${CURRENT.major}.${CURRENT.minor}`
    });
    assert.equal(parseSdkVersion(`${STABLE}-alpha${SEQUENCE}`).prereleaseSequence, SEQUENCE);
    assert.equal(parseSdkVersion(`${STABLE}-beta${SEQUENCE}`).prereleaseChannel, 'beta');
    assert.equal(parseSdkVersion(`${STABLE}-rc${SEQUENCE}`).releaseId, `sdk-api-v${STABLE}-rc${SEQUENCE}`);
    for (const invalid of [`${STABLE}-r${SEQUENCE}`, `${STABLE}-rc.${SEQUENCE}`, `${STABLE}-rc0`,
        `0${CURRENT.major + 1}.${CURRENT.minor}.${CURRENT.patch}`, CURRENT.compatibilityVersion]) {
        assert.throws(() => parseSdkVersion(invalid), /Invalid SDK semantic version/u);
    }
});

test('SDK 身份事实源与 Maven、BOM 及模板投影必须一致', () => {
    const root = createFixture();
    try {
        assert.equal(inspectSdkVersion(root).releaseId, CURRENT.releaseId);
        fs.writeFileSync(path.join(root, 'plugin-templates', 'minimal-feature-plugin', 'pom.xml'),
                '<groupId>io.github.sywyar.pixivdownloader</groupId>'
                + '<artifactId>pixivdownload-sdk</artifactId>'
                + `<pixivdownload.sdk.version>${NEXT}</pixivdownload.sdk.version>`, 'utf8');
        assert.throws(() => inspectSdkVersion(root), error => error.message.includes('minimal-feature-plugin') && error.message.includes(`must be ${CURRENT.version}`));
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
        fs.writeFileSync(metadata, `version=${CURRENT.version}\n`, 'utf8');
        const pom = path.join(root, 'pom.xml');
        fs.writeFileSync(pom, fs.readFileSync(pom, 'utf8').replace(`<revision>${CURRENT.version}</revision>`,
                `<revision>${NEXT}</revision>`), 'utf8');
        assert.throws(() => inspectSdkVersion(root), error => error.message.includes(`Maven SDK version projection must be ${CURRENT.version}`));
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('历史构建只选择当时存在的 SDK 模块，当前候选必须包含完整入口', () => {
    const root = createFixture();
    try {
        assert.deepEqual(sdkModulesAtRef(root), MODULES);
        const pom = path.join(root, 'pom.xml');
        fs.writeFileSync(pom, fs.readFileSync(pom, 'utf8').replace('<module>pixivdownload-sdk</module>', ''), 'utf8');
        assert.deepEqual(sdkModulesAtRef(root), MODULES.slice(0, -1));
        assert.throws(() => inspectSdkVersion(root), /Root SDK modules/u);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
