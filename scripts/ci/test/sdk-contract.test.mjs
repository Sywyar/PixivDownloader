import assert from 'node:assert/strict';
import test from 'node:test';

import { evaluateBaselineState, evaluateContract } from '../sdk-contract.mjs';
import { parseSdkVersion } from '../sdk-version.mjs';

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
