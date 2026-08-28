import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { changedConsumerPoms, evaluateBaselineState, evaluateContract } from '../sdk-contract.mjs';
import { parseSdkVersion } from '../sdk-version.mjs';

const CONSUMER_POM_MODULES = [
    'pixivdownload-sdk-info',
    'pixivdownload-plugin-api',
    'pixivdownload-core-api',
    'pixivdownload-sdk-bom'
];

function identity(version, legacyRevision = 0) {
    const parsed = parseSdkVersion(version);
    return legacyRevision
        ? { ...parsed, legacyRevision, releaseId: `sdk-api-v${version}-r${legacyRevision}` }
        : parsed;
}

test('公开表面变化必须同时更新 SDK 身份', () => {
    assert.throws(() => evaluateContract({
        baseIdentity: identity('1.0.0-rc1'),
        candidateIdentity: identity('1.0.0-rc1'),
        baseSurface: 'A\n',
        candidateSurface: 'A\nB\n'
    }), /without a new SDK release identity/u);
    assert.equal(evaluateContract({
        baseIdentity: identity('1.0.0-rc1'),
        candidateIdentity: identity('1.0.0-rc2'),
        baseSurface: 'A\n',
        candidateSurface: 'A\nB\n'
    }).outcome, 'PUBLISH');
});

test('模板、POM 或发行包装变化必须同时更新 SDK 身份', () => {
    const identity = parseSdkVersion('1.2.3');
    assert.throws(() => evaluateContract({
        baseIdentity: identity,
        candidateIdentity: identity,
        baseSurface: 'type A',
        candidateSurface: 'type A',
        releaseInputChanges: ['plugin-templates/download-type-plugin/pom.xml'],
    }), /release inputs changed without a new SDK release identity/u);
    const result = evaluateContract({
        baseIdentity: identity,
        candidateIdentity: parseSdkVersion('1.2.4'),
        baseSurface: 'type A',
        candidateSurface: 'type A',
        releaseInputChanges: ['plugin-templates/download-type-plugin/pom.xml'],
    });
    assert.deepEqual(result.releaseInputChanges, ['plugin-templates/download-type-plugin/pom.xml']);
});

test('预发布版本必须单调递增且首次结构化身份可从旧元数据迁移', () => {
    assert.throws(() => evaluateContract({
        baseIdentity: identity('1.0.0-rc2'),
        candidateIdentity: identity('1.0.0-rc1'),
        baseSurface: 'A\n',
        candidateSurface: 'A\n'
    }), /must increase/u);
    assert.equal(evaluateContract({
        baseIdentity: identity('1.0.0', 1),
        candidateIdentity: identity('1.0.0-rc1'),
        baseSurface: 'A\n',
        candidateSurface: 'A\n'
    }).outcome, 'PUBLISH');
});

test('首次结构化身份把旧基线缺少的 consumer POM 视为发行输入变化', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-sdk-consumer-poms-'));
    const base = path.join(root, 'base');
    const candidate = path.join(root, 'candidate');
    try {
        for (const module of CONSUMER_POM_MODULES) {
            const target = path.join(candidate, module, 'target');
            fs.mkdirSync(target, { recursive: true });
            fs.writeFileSync(path.join(target, 'flattened-pom.xml'), `<project>${module}</project>\n`, 'utf8');
        }
        assert.deepEqual(changedConsumerPoms(base, candidate, true),
                CONSUMER_POM_MODULES.map((module) => `${module}/consumer-pom.xml`));
        assert.throws(() => changedConsumerPoms(base, candidate), /Missing base consumer POM/u);
        fs.rmSync(path.join(candidate, CONSUMER_POM_MODULES[0], 'target', 'flattened-pom.xml'));
        assert.throws(() => changedConsumerPoms(base, candidate, true), /Missing candidate consumer POM/u);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('同主版本稳定基线禁止删除并要求兼容新增提升次版本', () => {
    const stableBaseline = { identity: identity('1.0.0'), surface: 'A\n' };
    assert.throws(() => evaluateContract({
        baseIdentity: identity('1.0.0'),
        candidateIdentity: identity('1.1.0-rc1'),
        baseSurface: 'A\n',
        candidateSurface: 'B\n',
        stableBaseline
    }), /removes public API/u);
    assert.throws(() => evaluateContract({
        baseIdentity: identity('1.0.0'),
        candidateIdentity: identity('1.0.1-rc1'),
        baseSurface: 'A\n',
        candidateSurface: 'A\nB\n',
        stableBaseline
    }), /higher SDK minor/u);
    assert.equal(evaluateContract({
        baseIdentity: identity('1.0.0'),
        candidateIdentity: identity('1.1.0-rc1'),
        baseSurface: 'A\n',
        candidateSurface: 'A\nB\n',
        stableBaseline
    }).outcome, 'PUBLISH');
});

test('预发布不能建立或改写稳定基线', () => {
    const base = { identity: identity('1.0.0'), metadataText: '{}\n', surface: 'A\n' };
    assert.throws(() => evaluateBaselineState({
        base: null,
        candidate: base,
        candidateIdentity: identity('1.1.0-rc1'),
        candidateSurface: 'A\n',
        directory: 'sdk-baselines/v1'
    }), /cannot establish/u);
    assert.throws(() => evaluateBaselineState({
        base,
        candidate: { ...base, surface: 'A\nB\n' },
        candidateIdentity: identity('1.1.0-rc1'),
        candidateSurface: 'A\nB\n',
        directory: 'sdk-baselines/v1'
    }), /cannot modify/u);
});
