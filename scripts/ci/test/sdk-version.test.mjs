import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

import { inspectSdkVersion, parseSdkVersion, sdkModulesAtRef } from '../sdk-version.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CURRENT = inspectSdkVersion(ROOT);
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
    for (const channel of ['alpha', 'beta', 'rc']) {
        for (const separator of ['', '.']) {
            const raw = `${STABLE}-${channel}${separator}${SEQUENCE}`;
            const parsed = parseSdkVersion(raw);
            assert.equal(parsed.version, raw);
            assert.equal(parsed.releaseId, `sdk-api-v${raw}`);
            assert.equal(parsed.prereleaseChannel, channel);
            assert.equal(parsed.prereleaseSequence, SEQUENCE);
        }
        for (const suffix of ['.0', '.01', '..1', '.1.2', '.1+build', '.', '']) {
            assert.throws(() => parseSdkVersion(`${STABLE}-${channel}${suffix}`), /Invalid SDK semantic version/u);
        }
    }
    for (const invalid of [`${STABLE}-r${SEQUENCE}`, `${STABLE}-rc0`,
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

test('Pages 下载入口的实际 Bash 校验接受两种后缀且拒绝非法标签', () => {
    const workflow = YAML.parse(fs.readFileSync(path.join(ROOT, '.github/workflows/sdk-pages.yml'), 'utf8'));
    const download = workflow.jobs.build.steps.find(step => step.name === 'Download every immutable SDK Release');
    const guard = download.run.match(/if ! \[\[ "\$tag"[\s\S]*?\n\s*fi/u)?.[0];
    assert.ok(guard);
    const versions = [STABLE, ...['alpha', 'beta', 'rc'].flatMap(channel =>
        ['', '.'].map(separator => `${STABLE}-${channel}${separator}${SEQUENCE}`))];
    for (const [tag, accepted] of [
        ...versions.map(version => [`sdk-api-v${version}`, true]),
        ...['rc.0', 'rc.01', 'rc..1', 'rc.1.2', 'rc.1+build', 'rc.', 'RC.1', 'nightly.1']
            .map(suffix => [`sdk-api-v${STABLE}-${suffix}`, false]),
        [`sdk-api-v${STABLE}/escape`, false]
    ]) {
        const result = spawnSync('bash', ['-c', 'tag="$SDK_TEST_TAG"\n' + guard], {
            encoding: 'utf8', env: { ...process.env, SDK_TEST_TAG: tag }
        });
        assert.equal(result.status === 0, accepted, `${tag}: ${result.error ?? result.stderr}`);
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
