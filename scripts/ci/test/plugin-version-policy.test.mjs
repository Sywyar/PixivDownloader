import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { verifyPluginVersion, publishedVersions, sdkBaselineVersion, checkPluginVersions } from '../plugin-version-policy.mjs';

test('真实 Git 差异区分测试、SDK 声明、插件资源和私有依赖，允许未发布版本继续开发', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'plugin-version-policy-'));
    const git = (...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    const write = (file, text) => { fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true }); fs.writeFileSync(path.join(root, file), text); };
    const descriptor = version => `plugin.id=fixture\nplugin.version=${version}\nplugin.requires=8.1\n`;
    const pom = (name, deps = '') => `<project><groupId>top.sywyar.lovepopup</groupId><artifactId>${name}</artifactId><dependencies>${deps}</dependencies></project>`;
    try {
        git('init');
        git('config', 'user.email', 'fixture@example.invalid');
        git('config', 'user.name', 'fixture');
        const parent = pom('parent').replace('</project>', '<properties><revision>8.1.0</revision><pixivdownload.sdk.version>${revision}</pixivdownload.sdk.version><compiler.release>${java.release}</compiler.release><java.release>17</java.release></properties><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration><release>${compiler.release}</release></configuration></plugin></plugins></build></project>');
        write('pom.xml', parent);
        write('feature/pom.xml', pom('feature', '<dependency><groupId>top.sywyar.lovepopup</groupId><artifactId>private-lib</artifactId><version>1.0.0</version></dependency><dependency><groupId>io.github.sywyar.pixivdownloader</groupId><artifactId>pixivdownload-sdk</artifactId><version>${pixivdownload.sdk.version}</version><scope>provided</scope></dependency>'));
        write('private-lib/pom.xml', pom('private-lib'));
        write('private-lib/src/main/Library.java', 'class Library {}');
        write('feature/src/main/resources/plugin.properties', descriptor('2.0.0'));
        write('feature/src/test/Test.java', 'class Test {}');
        write('sdk/src/main/Api.java', 'class Api {}');
        write('build-support/plugin.pro', '-keep class Fixture');
        write('private-lib/pom.xml', pom('private-lib').replace('</project>', '<build><plugins><plugin><artifactId>maven-antrun-plugin</artifactId><configuration><file>${maven.multiModuleProjectDirectory}/build-support/plugin.pro</file></configuration></plugin></plugins></build></project>'));
        git('add', '.'); git('commit', '-m', 'fixture baseline');
        const base = git('rev-parse', 'HEAD');
        const inspect = () => checkPluginVersions(root, base, [{ Id: 'fixture', Module: 'feature' }], [{ tag_name: 'fixture-v2.0.0' }]);
        write('feature/src/test/Test.java', 'class Test { int more; }');
        write('sdk/src/main/Api.java', 'class Api { int addition; }');
        assert.deepEqual(inspect()[0].changed, []);
        write('pom.xml', parent.replace('<revision>8.1.0</revision>', '<revision>8.2.0</revision>'));
        assert.deepEqual(inspect()[0].changed, []);
        write('pom.xml', parent.replace('</plugins>', '<plugin><artifactId>maven-surefire-plugin</artifactId><configuration><argLine>test-only</argLine></configuration></plugin></plugins>'));
        assert.deepEqual(inspect()[0].changed, []);
        write('pom.xml', parent.replace('<java.release>17</java.release>', '<java.release>21</java.release>'));
        assert.throws(inspect, /pom.xml/);
        write('pom.xml', parent);
        write('build-support/plugin.pro', '-keep class Changed');
        assert.throws(inspect, /build-support\/plugin.pro/);
        write('build-support/plugin.pro', '-keep class Fixture');
        write('private-lib/src/main/Library.java', 'class Library { int addition; }');
        assert.throws(inspect, /private-lib\/src\/main\/Library.java/);
        write('feature/src/main/resources/plugin.properties', descriptor('2.1.0'));
        assert.doesNotThrow(inspect);
        git('add', '.'); git('commit', '-m', 'unpublished plugin version');
        write('feature/src/main/resources/plugin.properties', descriptor('2.1.0') + 'plugin.description=changed\n');
        assert.doesNotThrow(() => checkPluginVersions(root, git('rev-parse', 'HEAD'),
            [{ Id: 'fixture', Module: 'feature' }], [{ tag_name: 'fixture-v2.0.0' }]));
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('已发布内容变化须提升版本，同一未发布版本可以继续合入，测试改动不触发发布', () => {
    const input = { id: 'fixture', previousVersion: '7.2.0', publishedVersion: '7.2.0', changed: ['src/main/Plugin.java'] };
    assert.throws(() => verifyPluginVersion({ ...input, version: '7.2.0' }), /increase plugin.version/);
    assert.doesNotThrow(() => verifyPluginVersion({ ...input, version: '7.2.1' }));
    assert.doesNotThrow(() => verifyPluginVersion({ ...input, previousVersion: '7.2.1', version: '7.2.1' }));
    assert.doesNotThrow(() => verifyPluginVersion({ ...input, version: '7.2.0', changed: [] }));
    assert.throws(() => verifyPluginVersion({ ...input, version: '7.1.9', changed: [] }), /cannot decrease/);
    assert.doesNotThrow(() => verifyPluginVersion({ ...input, version: '7.2.1', changed: [] }));
});

test('发行基线按版本排序，排除草稿和滚动 Nightly，查询结果不能用源码版本代替', () => {
    const releases = ['fixture-v7.2.9', 'fixture-v7.2.10', 'fixture-nightly', 'fixture-v7.3.0-nightly.20260101.1.1', 'another-v9.0.0']
        .map(tag_name => ({ tag_name, draft: false }));
    releases.push({ tag_name: 'fixture-v8.0.0', draft: true });
    assert.equal(publishedVersions(releases, [{ Id: 'fixture' }]).get('fixture'), '7.2.10');
});

test('声明当前未发布 SDK 可直接检查候选，稳定范围按主次版本下界检查，精确要求不丢补丁或候选身份', () => {
    assert.equal(sdkBaselineVersion('9.3', '9.3.0'), 'candidate');
    assert.equal(sdkBaselineVersion('>=9.2.7', '9.3.0'), '9.2.0');
    assert.equal(sdkBaselineVersion('=9.2.7', '9.3.0'), '9.2.7');
    assert.equal(sdkBaselineVersion('9.3.0-beta.2', '9.3.0-beta.2'), 'candidate');
    assert.equal(sdkBaselineVersion('9.3', '9.3.1-beta.1'), '9.3.0');
    for (const invalid of ['', '*', '<9.2', '9.2 || 9.3', '=9.2']) {
        assert.throws(() => sdkBaselineVersion(invalid, '9.3.0'));
    }
});
