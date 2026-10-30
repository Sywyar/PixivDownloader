import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {LEGACY_PLUGIN, verifyLegacyPlugin, downloadLegacyPlugin} from '../sdk-legacy-plugin.mjs';

test('旧插件验收拒绝缺失、错误长度和等长篡改，缓存不能绕过摘要', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-legacy-'));
    try {
        const file = path.join(directory, 'original.jar');
        assert.throws(() => verifyLegacyPlugin(undefined), /required/);
        assert.throws(() => verifyLegacyPlugin(file));
        fs.writeFileSync(file, 'invalid');
        assert.throws(() => verifyLegacyPlugin(file), /length mismatch/);
        fs.writeFileSync(file, Buffer.alloc(LEGACY_PLUGIN.bytes));
        assert.throws(() => verifyLegacyPlugin(file), /digest mismatch/);
        assert.throws(() => downloadLegacyPlugin(file), /digest mismatch/);
        assert.equal(fs.statSync(file).size, LEGACY_PLUGIN.bytes);
    } finally { fs.rmSync(directory, {recursive: true, force: true}); }
});
