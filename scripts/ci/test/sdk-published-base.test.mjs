import assert from 'node:assert/strict';
import test from 'node:test';

import { selectPublishedRelease, verifyPublishedMetadata } from '../sdk-published-base.mjs';
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
        release('1.0.0-rc5'), release('1.0.0-rc.14')];
    const latest = selectPublishedRelease(releases, identity, 'latest');
    assert.equal(latest.tag_name, 'sdk-api-v1.0.0-rc.14');
    assert.throws(() => selectPublishedRelease(releases, identity, 'current'), /no public Release/u);

    const sourceCommitSha = 'a'.repeat(40);
    assert.equal(verifyPublishedMetadata(latest, {
        releaseId: latest.tag_name, sdkVersion: '1.0.0-rc.14', sourceCommitSha
    }), sourceCommitSha);
    assert.throws(() => verifyPublishedMetadata({ ...latest, assets: [] }, {
        releaseId: latest.tag_name, sdkVersion: '1.0.0-rc.14', sourceCommitSha
    }), /lacks/u);
});
