import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { changedMavenContracts, evaluateContract } from '../sdk-contract.mjs';
import { inspectSdkVersion, parseSdkVersion } from '../sdk-version.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CURRENT = inspectSdkVersion(ROOT);
const STABLE = `${CURRENT.major}.${CURRENT.minor}.${CURRENT.patch}`;
const NEXT_PATCH = `${CURRENT.major}.${CURRENT.minor}.${CURRENT.patch + 1}`;
const NEXT_MINOR = `${CURRENT.major}.${CURRENT.minor + 1}.${CURRENT.patch}`;
const SEQUENCE = Math.max(1, CURRENT.prereleaseSequence);
const rc = (offset = 0) => `${STABLE}-rc.${SEQUENCE + offset}`;

const CLI = path.join(ROOT, 'scripts', 'ci', 'sdk-contract.mjs');

const CONSUMER_POM_MODULES = [
    'pixivdownload-sdk-info',
    'pixivdownload-plugin-api',
    'pixivdownload-core-api',
    'pixivdownload-sdk-bom',
    'pixivdownload-sdk'
];

function identity(version, legacyRevision = 0) {
    const parsed = parseSdkVersion(version);
    return legacyRevision
        ? { ...parsed, legacyRevision, releaseId: `sdk-api-v${version}-r${legacyRevision}` }
        : parsed;
}

function dependency(groupId, artifactId, version, scope = '') {
    return `<dependency>
      <groupId>${groupId}</groupId>
      <artifactId>${artifactId}</artifactId>
      <version>${version}</version>
      ${scope ? `<scope>${scope}</scope>` : ''}
    </dependency>`;
}

function consumerPom(module, sdkVersion, label, servletVersion = STABLE) {
    const directDependencies = module === 'pixivdownload-plugin-api'
        ? `<dependencies>${dependency('jakarta.servlet', 'jakarta.servlet-api', servletVersion, 'provided')}</dependencies>`
        : '';
    const managedDependencies = module === 'pixivdownload-sdk-bom'
        ? `<dependencyManagement><dependencies>${[
            'pixivdownload-sdk-info',
            'pixivdownload-plugin-api',
            'pixivdownload-core-api'
        ].map((artifact) => dependency('io.github.sywyar.pixivdownloader', artifact, sdkVersion)).join('')}</dependencies></dependencyManagement>`
        : '';
    return `<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.sywyar.pixivdownloader</groupId>
  <artifactId>${module}</artifactId>
  <version>${sdkVersion}</version>
  ${module === 'pixivdownload-sdk-bom' ? '<packaging>pom</packaging>' : ''}
  <name>${label}</name>
  <description>${label} metadata</description>
  ${directDependencies}
  ${managedDependencies}
</project>\n`;
}

function writeConsumerPoms(root, sdkVersion, label, servletVersion = STABLE) {
    for (const module of CONSUMER_POM_MODULES) {
        const target = path.join(root, module, 'target');
        fs.mkdirSync(target, { recursive: true });
        fs.writeFileSync(path.join(target, 'flattened-pom.xml'),
                consumerPom(module, sdkVersion, label, servletVersion), 'utf8');
    }
}

function git(root, args) {
    return execFileSync('git', args, {
        cwd: root,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe']
    }).trim();
}

function copy(root, relativePath, version = '27.3.0') {
    const target = path.join(root, ...relativePath.split('/'));
    fs.mkdirSync(path.dirname(target), { recursive: true });
    const identity = parseSdkVersion(version);
    const text = fs.readFileSync(path.join(ROOT, ...relativePath.split('/')), 'utf8')
            .replaceAll(CURRENT.version, version)
            .replace(/^plugin\.requires=.*$/mu, `plugin.requires=${identity.compatibilityVersion}`);
    fs.writeFileSync(target, text, 'utf8');
}


test('日常维护记录公开表面变化，发行时要求更新 SDK 身份', () => {
    const maintenance = evaluateContract({
        baseIdentity: identity(rc()),
        candidateIdentity: identity(rc()),
        baseSurface: 'A\n',
        candidateSurface: 'A\nB\n'
    });
    assert.equal(maintenance.outcome, 'NO_PUBLISH');
    assert.equal(maintenance.surfaceChanged, true);
    assert.throws(() => evaluateContract({
        baseIdentity: identity(rc()),
        candidateIdentity: identity(rc()),
        baseSurface: 'A\n',
        candidateSurface: 'A\nB\n',
        requireReleaseIdentity: true
    }), /without a new SDK release identity/u);
    assert.equal(evaluateContract({
        baseIdentity: identity(rc()),
        candidateIdentity: identity(rc(1)),
        baseSurface: 'A\n',
        candidateSurface: 'A\nB\n'
    }).outcome, 'PUBLISH');
});

test('无仓库快照时，发布合同仍允许 Wrapper 纯权限维护并完整读取大 API', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-sdk-wrapper-mode-'));
    const baseSdk = path.join(root, 'base-sdk');
    const candidateSdk = path.join(root, 'candidate-sdk');
    try {
        git(root, ['init', '-q']);
        git(root, ['config', 'user.email', 'test@example.com']);
        git(root, ['config', 'user.name', 'test']);
        git(root, ['config', 'core.filemode', 'true']);
        const identityFiles = [
            'pom.xml',
            'pixivdownload-sdk-info/pom.xml',
            'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties',
            'pixivdownload-plugin-api/pom.xml',
            'pixivdownload-core-api/pom.xml',
            'pixivdownload-sdk-bom/pom.xml',
            'pixivdownload-sdk/pom.xml',
            'plugin-templates/minimal-feature-plugin/pom.xml',
            'plugin-templates/download-type-plugin/pom.xml',
            'plugin-templates/minimal-feature-plugin/src/main/resources/plugin.properties',
            'plugin-templates/download-type-plugin/src/main/resources/plugin.properties'
        ];
        for (const relativePath of identityFiles) copy(root, relativePath);
        fs.writeFileSync(path.join(root, 'mvnw'), '#!/bin/sh\n', 'utf8');
        git(root, ['add', '-A']);
        git(root, ['commit', '-q', '-m', 'base']);
        const base = git(root, ['rev-parse', 'HEAD']);
        git(root, ['update-index', '--chmod=+x', 'mvnw']);
        git(root, ['commit', '-q', '-m', 'make wrapper executable']);
        const candidate = git(root, ['rev-parse', 'HEAD']);
        assert.equal(git(root, ['rev-parse', `${base}:mvnw`]), git(root, ['rev-parse', `${candidate}:mvnw`]));
        assert.match(git(root, ['ls-tree', base, 'mvnw']), /^100644\s/u);
        assert.match(git(root, ['ls-tree', candidate, 'mvnw']), /^100755\s/u);

        const sdkIdentity = inspectSdkVersion(root);
        writeConsumerPoms(baseSdk, sdkIdentity.version, 'Base metadata');
        writeConsumerPoms(candidateSdk, sdkIdentity.version, 'Candidate metadata');
        const baseSurface = path.join(root, 'base-surface.txt');
        const candidateSurface = path.join(root, 'candidate-surface.txt');
        const report = path.join(root, 'report.json');
        const surface = Array.from({ length: 40_000 }, (_, index) => `type 示例公开接口${index}\n`).join('');
        assert.ok(Buffer.byteLength(surface) > 1024 * 1024);
        fs.writeFileSync(baseSurface, surface, 'utf8');
        fs.writeFileSync(candidateSurface, surface, 'utf8');
        const run = (strict = 'true') => spawnSync(process.execPath, [CLI,
            '--repo-root', root,
            '--base-ref', base,
            '--candidate-ref', 'HEAD',
            '--base-surface', baseSurface,
            '--candidate-surface', candidateSurface,
            '--base-sdk-root', baseSdk,
            '--candidate-sdk-root', candidateSdk,
            '--report', report,
            '--require-release-identity', strict
        ], { cwd: root, encoding: 'utf8' });
        const result = run();
        assert.equal(result.status, 0, result.stderr || result.stdout);
        assert.equal(result.stdout, `NO_PUBLISH: ${sdkIdentity.releaseId}\n`);
        assert.deepEqual(JSON.parse(fs.readFileSync(report, 'utf8')).mavenContractChanges, []);
        assert.deepEqual(JSON.parse(fs.readFileSync(report, 'utf8')).stableBaseline,
                { releaseId: sdkIdentity.releaseId, sourceCommitSha: base });
        assert.equal(fs.existsSync(path.join(root, 'sdk-baselines')), false);
        assert.equal(run('false').status, 0);
        assert.equal(JSON.parse(fs.readFileSync(report, 'utf8')).stableBaseline, null);

        for (const [version, api, expected] of [
            ['27.3.0', surface + 'Draft\n', /without a new SDK release identity/u],
            ['27.3.1', surface + 'Draft\n', /higher SDK minor/u],
            ['27.4.0', surface + 'Draft\n', null],
            ['27.4.0', surface + 'Final\n', null],
            ['27.4.0', 'Final\n', /removes public API/u]
        ]) {
            for (const relativePath of identityFiles) copy(root, relativePath, version);
            git(root, ['add', '--', ...identityFiles]);
            git(root, ['commit', '--allow-empty', '-q', '-m', 'development API']);
            writeConsumerPoms(candidateSdk, version, 'Candidate metadata');
            fs.writeFileSync(candidateSurface, api, 'utf8');
            const checked = run();
            if (expected) {
                assert.notEqual(checked.status, 0);
                assert.match(checked.stderr, expected);
            } else {
                assert.equal(checked.status, 0, checked.stderr);
                assert.equal(JSON.parse(fs.readFileSync(report, 'utf8')).outcome, 'PUBLISH');
            }
        }
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('日常维护记录 Maven 合同变化，发行时要求更新 SDK 身份', () => {
    const identity = parseSdkVersion(STABLE);
    assert.equal(evaluateContract({
        baseIdentity: identity, candidateIdentity: identity,
        baseSurface: 'type A', candidateSurface: 'type A',
        mavenContractChanges: ['pixivdownload-plugin-api/maven-consumer-contract']
    }).outcome, 'NO_PUBLISH');
    assert.throws(() => evaluateContract({
        baseIdentity: identity,
        candidateIdentity: identity,
        baseSurface: 'type A',
        candidateSurface: 'type A',
        mavenContractChanges: ['pixivdownload-plugin-api/maven-consumer-contract'],
        requireReleaseIdentity: true
    }), /Maven SDK consumer contract changed without a new SDK release identity/u);
    const result = evaluateContract({
        baseIdentity: identity,
        candidateIdentity: parseSdkVersion(NEXT_PATCH),
        baseSurface: 'type A',
        candidateSurface: 'type A',
        mavenContractChanges: ['pixivdownload-plugin-api/maven-consumer-contract'],
    });
    assert.deepEqual(result.mavenContractChanges, ['pixivdownload-plugin-api/maven-consumer-contract']);
});

test('预发布版本必须单调递增且首次结构化身份可从旧元数据迁移', () => {
    assert.throws(() => evaluateContract({
        baseIdentity: identity(rc(1)),
        candidateIdentity: identity(rc()),
        baseSurface: 'A\n',
        candidateSurface: 'A\n'
    }), /must increase/u);
    assert.equal(evaluateContract({
        baseIdentity: identity(STABLE, 1),
        candidateIdentity: identity(rc()),
        baseSurface: 'A\n',
        candidateSurface: 'A\n'
    }).outcome, 'PUBLISH');
});

test('新 SDK 身份使用点分后缀，历史维护不发布，换拼写不能冒充升级', () => {
    const evaluate = (base, candidate) => evaluateContract({
        baseIdentity: identity(base), candidateIdentity: identity(candidate),
        baseSurface: 'A\n', candidateSurface: 'A\n'
    });
    for (const channel of ['alpha', 'beta', 'rc']) {
        const compact = `${STABLE}-${channel}${SEQUENCE}`;
        const dotted = `${STABLE}-${channel}.${SEQUENCE}`;
        assert.equal(evaluate(compact, compact).outcome, 'NO_PUBLISH');
        assert.equal(evaluate(dotted, dotted).outcome, 'NO_PUBLISH');
        assert.throws(() => evaluate(compact, dotted), /must increase/u);
        assert.throws(() => evaluate(dotted, compact), /must increase/u);
        assert.throws(() => evaluate(compact, `${STABLE}-${channel}${SEQUENCE + 1}`), /must use alpha\.N/u);
        assert.equal(evaluate(compact, `${STABLE}-${channel}.${SEQUENCE + 1}`).outcome, 'PUBLISH');
        assert.equal(evaluate(`${STABLE}-${channel}2`, `${STABLE}-${channel}.10`).outcome, 'PUBLISH');
        assert.throws(() => evaluate(`${STABLE}-${channel}10`, `${STABLE}-${channel}.2`), /must increase/u);
    }
    for (const [base, candidate] of [['alpha.10', 'beta.1'], ['beta10', 'rc.1']]) {
        assert.equal(evaluate(`${STABLE}-${base}`, `${STABLE}-${candidate}`).outcome, 'PUBLISH');
    }
    assert.equal(evaluate(`${STABLE}-rc.10`, STABLE).outcome, 'PUBLISH');
});

test('Maven 合同比较忽略发布元数据和 SDK 自身版本投影', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-sdk-maven-contract-'));
    const base = path.join(root, 'base');
    const candidate = path.join(root, 'candidate');
    try {
        writeConsumerPoms(base, STABLE, 'Old metadata');
        writeConsumerPoms(candidate, NEXT_PATCH, 'New metadata');
        assert.deepEqual(changedMavenContracts(base, candidate, STABLE, NEXT_PATCH), []);

        writeConsumerPoms(candidate, NEXT_PATCH, 'New metadata', NEXT_MINOR);
        assert.deepEqual(changedMavenContracts(base, candidate, STABLE, NEXT_PATCH),
                ['pixivdownload-plugin-api/maven-consumer-contract']);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('首次结构化身份把旧基线缺少的 consumer POM 视为 Maven 合同变化', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-sdk-consumer-poms-'));
    const base = path.join(root, 'base');
    const candidate = path.join(root, 'candidate');
    try {
        writeConsumerPoms(candidate, rc(), 'Candidate metadata');
        assert.deepEqual(changedMavenContracts(base, candidate, STABLE, rc(), true),
                CONSUMER_POM_MODULES.map((module) => `${module}/maven-consumer-contract`));
        assert.throws(() => changedMavenContracts(base, candidate, STABLE, rc()),
                /Missing base consumer POM/u);
        fs.rmSync(path.join(candidate, CONSUMER_POM_MODULES[0], 'target', 'flattened-pom.xml'));
        assert.throws(() => changedMavenContracts(base, candidate, STABLE, rc(), true),
                /Missing candidate consumer POM/u);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('新增坐标区分基线尚未声明与已有坐标构建产物缺失', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-sdk-new-coordinate-'));
    const base = path.join(root, 'base');
    const candidate = path.join(root, 'candidate');
    try {
        writeConsumerPoms(base, rc(), 'Base');
        writeConsumerPoms(candidate, rc(1), 'Candidate');
        fs.rmSync(path.join(base, 'pixivdownload-sdk', 'target', 'flattened-pom.xml'));
        const previousModules = CONSUMER_POM_MODULES.filter(module => module !== 'pixivdownload-sdk');
        const changes = changedMavenContracts(base, candidate, rc(), rc(1), false, previousModules);
        assert.deepEqual(changes, ['pixivdownload-sdk/maven-consumer-contract']);
        assert.throws(() => changedMavenContracts(base, candidate, rc(), rc(1)),
                /Missing base consumer POM/u);
        fs.rmSync(path.join(base, 'pixivdownload-plugin-api', 'target', 'flattened-pom.xml'));
        assert.throws(() => changedMavenContracts(base, candidate, rc(), rc(1), false, previousModules),
                /Missing base consumer POM/u);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('同主版本稳定基线禁止删除并要求兼容新增提升次版本', () => {
    const stableBaseline = { identity: identity(STABLE), surface: 'A\n' };
    assert.throws(() => evaluateContract({
        baseIdentity: identity(STABLE),
        candidateIdentity: identity(`${NEXT_MINOR}-rc.${SEQUENCE}`),
        baseSurface: 'A\n',
        candidateSurface: 'B\n',
        stableBaseline, requireReleaseIdentity: true
    }), /removes public API/u);
    assert.throws(() => evaluateContract({
        baseIdentity: identity(STABLE),
        candidateIdentity: identity(`${NEXT_PATCH}-rc.${SEQUENCE}`),
        baseSurface: 'A\n',
        candidateSurface: 'A\nB\n',
        stableBaseline, requireReleaseIdentity: true
    }), /higher SDK minor/u);
    assert.equal(evaluateContract({
        baseIdentity: identity(STABLE),
        candidateIdentity: identity(`${NEXT_MINOR}-rc.${SEQUENCE}`),
        baseSurface: 'A\n',
        candidateSurface: 'A\nB\n',
        stableBaseline, requireReleaseIdentity: true
    }).outcome, 'PUBLISH');
});
