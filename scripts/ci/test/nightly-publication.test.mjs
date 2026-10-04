import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { assertPublicationOrder, manifestVersions, releaseVersions } from '../assert-nightly-publication.mjs';

const candidate = '2.3.4-nightly.20260101.12.2';
test('Nightly 发布拒绝迟到运行与迟到 attempt，允许同次重试', () => {
    for (const previous of ['9.8.7-nightly.20270101.11.9', candidate, '2.3.4-nightly.20270101.12.1']) {
        assert.doesNotThrow(() => assertPublicationOrder(candidate, [previous]));
    }
    for (const previous of ['2.3.4-nightly.20250101.13.1', '2.3.4-nightly.20250101.12.3']) {
        assert.throws(() => assertPublicationOrder(candidate, [previous]), /stale Nightly/u);
    }
    for (const previous of [undefined, '', '2.3.4', '2.3.4-nightly.20260101.0.1']) {
        assert.throws(() => assertPublicationOrder(candidate, [previous]), /Invalid Nightly/u);
    }
    assert.throws(() => manifestVersions({ entries: [] }), /no entries/u);
    assert.deepEqual(releaseVersions({ tag_name: 'nightly', name: `Nightly Build ${candidate}` }), [candidate]);
    for (const release of [{}, { tag_name: 'nightly', name: candidate },
        { tag_name: 'stable', name: `Nightly Build ${candidate}` }]) {
        assert.throws(() => releaseVersions(release), /identity/u);
    }
});

test('实际插件发布入口检查全部目录项，坏目录失败并保留原文件', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nightly-publication-'));
    const manifest = path.join(directory, 'manifest.json');
    const script = fileURLToPath(new URL('../assert-nightly-publication.mjs', import.meta.url));
    const run = () => spawnSync(process.execPath, [script, 'plugins', candidate, manifest], { encoding: 'utf8' });
    try {
        assert.equal(run().status, 0);
        for (const [entries, status] of [
            [[{ pluginId: 'sample', packages: [{ version: '1.0.0-nightly.20260101.11.1' }, { version: candidate }] }], 0],
            [[{ pluginId: 'sample', packages: [{ version: candidate }, { version: '1.0.0-nightly.20260101.13.1' }] }], 1],
            [[{ pluginId: 'first', packages: [{ version: candidate }] },
                { pluginId: 'second', packages: [{ version: '1.0.0-nightly.20260101.12.3' }] }], 1],
            [[], 1], [[{}], 1], [[null], 1], [[{ version: candidate }], 1],
            [[{ packages: [] }], 1], [[{ packages: {} }], 1],
            [[{ packages: [{ version: candidate }, {}] }], 1],
            [[{ packages: [null] }], 1], [[{ packages: [{ version: '' }] }], 1],
            [[{ packages: [{ version: 123 }] }], 1], [[{ packages: [{ version: '1.0.0' }] }], 1],
        ]) {
            const bytes = JSON.stringify({ entries });
            fs.writeFileSync(manifest, bytes);
            const result = run();
            assert.equal(result.status, status, bytes + '\n' + result.stderr);
            assert.equal(fs.readFileSync(manifest, 'utf8'), bytes);
        }
        fs.writeFileSync(manifest, '{');
        assert.equal(run().status, 1);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});
