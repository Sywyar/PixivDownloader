import assert from 'node:assert/strict';
import test from 'node:test';

import { resolveStableBaseline, selectPublishedRelease, verifyPublishedMetadata } from '../sdk-published-base.mjs';
import { evaluateContract } from '../sdk-contract.mjs';
import { parseSdkVersion } from '../sdk-version.mjs';

test('发行基线使用已公开的最新 SDK，当前模式要求精确身份和完整附件', () => {
    const identity = parseSdkVersion('1.0.0-rc.15');
    const release = (version, draft = false) => ({
        tag_name: `sdk-api-v${version}`,
        draft,
        assets: [
            { name: `PixivDownloader-Plugin-SDK-${version}.zip` },
            { name: 'sdk-release.json' },
            { name: 'SHA256SUMS' }
        ]
    });
    const releases = [release('1.0.0-rc.9'), release('1.0.0-rc.15', true),
        release('1.0.0-rc5'), release('1.0.0-rc.14'), release('9.0.0-nightly.20990101.1.1')];
    const latest = selectPublishedRelease(releases, identity, 'latest');
    assert.equal(latest.tag_name, 'sdk-api-v1.0.0-rc.14');
    assert.throws(() => selectPublishedRelease(releases, identity, 'current'), /no public Release/u);
    assert.equal(selectPublishedRelease(releases, identity, 'if-present'), null);
    const exact = release(identity.version);
    assert.equal(selectPublishedRelease([...releases, exact], identity, 'if-present'), exact);

    const sourceCommitSha = 'a'.repeat(40);
    assert.equal(verifyPublishedMetadata(latest, {
        releaseId: latest.tag_name, sdkVersion: '1.0.0-rc.14', sourceCommitSha
    }), sourceCommitSha);
    assert.throws(() => verifyPublishedMetadata({ ...latest, assets: [] }, {
        releaseId: latest.tag_name, sdkVersion: '1.0.0-rc.14', sourceCommitSha
    }), /lacks/u);
});

test('稳定比较选择同主版本已发布正式版，排除未来版本、预发布、草稿和 Nightly', () => {
    const release = (version, fields = {}) => ({ tag_name: `sdk-api-v${version}`, ...fields });
    const stable = release('27.3.0');
    const releases = [stable, release('27.2.9'), release('27.4.0-rc.2'),
        release('27.4.0'), release('27.3.1', { draft: true }),
        release('27.3.2', { prerelease: true }), release('28.0.0'),
        release('27.3.3-nightly.20990101.1.1')];
    assert.equal(selectPublishedRelease(releases, parseSdkVersion('27.4.0-rc.3'), 'stable'), stable);
    assert.equal(selectPublishedRelease(releases, parseSdkVersion('27.3.0'), 'stable'), stable);
    assert.equal(selectPublishedRelease(releases, parseSdkVersion('29.0.0'), 'stable'), null);
    assert.equal(selectPublishedRelease(releases, parseSdkVersion('27.1.0'), 'stable'), null);
});

test('正式发行前驱直接复用已经生成的 API，无额外查询或构建', async () => {
    const baseIdentity = parseSdkVersion('27.3.0');
    const baseSurface = 'type A\n';
    const noIo = () => assert.fail('不应重复查询或构建正式前驱');
    const result = await resolveStableBaseline({ repoRoot: '.', baseRef: 'a'.repeat(40),
        baseIdentity, baseSurface, candidateIdentity: parseSdkVersion('27.4.0') }, noIo, noIo);
    assert.deepEqual(result, { identity: baseIdentity, sourceSha: 'a'.repeat(40), surface: baseSurface });
});

test('RC 前驱仍保护已发布正式 API，允许调整尚未发布的 API', async () => {
    const baseIdentity = parseSdkVersion('27.4.0-rc.1');
    const candidateIdentity = parseSdkVersion('27.4.0-rc.2');
    const published = { identity: parseSdkVersion('27.3.0'), sourceSha: 'b'.repeat(40) };
    const resolve = async (mode, root, identity) => {
        assert.equal(mode, 'stable');
        assert.equal(root, '/fixture');
        assert.equal(identity, candidateIdentity);
        return published;
    };
    let builds = 0;
    const stableBaseline = await resolveStableBaseline({ repoRoot: '/fixture', baseRef: 'a'.repeat(40),
        baseIdentity, baseSurface: 'A\nDraft\n', candidateIdentity }, resolve, (root, release) => {
        assert.equal(root, '/fixture');
        assert.equal(release, published);
        builds++;
        return 'A\n';
    });
    assert.equal(builds, 1);
    const evaluate = candidateSurface => evaluateContract({ baseIdentity, candidateIdentity,
        baseSurface: 'A\nDraft\n', candidateSurface, stableBaseline, requireReleaseIdentity: true });
    assert.equal(evaluate('A\nFinal\n').outcome, 'PUBLISH');
    assert.throws(() => evaluate('Final\n'), /removes public API/u);
});

test('首次正式版或新主版本没有正式发行时不冻结 RC，但查询及构建失败必须阻断', async () => {
    for (const [base, candidate] of [['27.0.0-rc.1', '27.0.0'], ['26.9.0', '27.0.0-rc.1']]) {
        const options = { repoRoot: '.', baseRef: 'a'.repeat(40), baseIdentity: parseSdkVersion(base),
            baseSurface: 'Draft\n', candidateIdentity: parseSdkVersion(candidate) };
        let queries = 0;
        const baseline = await resolveStableBaseline(options, async () => { queries++; return null; },
                () => assert.fail('无正式发行时不构建'));
        assert.equal(queries, 1);
        assert.equal(baseline, null);
        assert.equal(evaluateContract({ ...options, candidateSurface: 'Final\n',
            stableBaseline: baseline, requireReleaseIdentity: true }).outcome, 'PUBLISH');
        await assert.rejects(resolveStableBaseline(options, async () => { throw new Error('query failed'); }),
                /query failed/u);
        await assert.rejects(resolveStableBaseline(options,
                async () => ({ identity: parseSdkVersion('27.0.0'), sourceSha: 'b'.repeat(40) }),
                () => { throw new Error('build failed'); }), /build failed/u);
    }
});
