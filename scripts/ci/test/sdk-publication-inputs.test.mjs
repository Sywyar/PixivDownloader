import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { verifyInputs, transferInputs } from '../sdk-publication-inputs.mjs';
import { sha256 } from '../sdk-release.mjs';

test('SDK 发布输入绑定同一源码与原始字节，拒绝越界路径、重复项及篡改后恢复', t => {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-publication-inputs-'));
    t.after(() => fs.rmSync(work, { recursive: true, force: true }));
    const bundle = path.join(work, 'bundle');
    const destination = path.join(work, 'repo');
    const relative = 'target/sdk-runtime-inputs/host.jar';
    const file = path.join(bundle, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, 'tested bytes');
    const sourceSha = 'a'.repeat(40);
    const valid = { schemaVersion: 1, sourceSha, hostVersion: '7.8.9',
        files: [{ file: relative, size: fs.statSync(file).size, sha256: sha256(file) }] };
    const write = value => fs.writeFileSync(path.join(bundle, 'inputs.json'), JSON.stringify(value));
    write(valid);
    assert.deepEqual(verifyInputs(bundle, sourceSha), valid);
    transferInputs(destination, bundle, sourceSha, true);
    assert.deepEqual(fs.readFileSync(path.join(destination, relative)), fs.readFileSync(file));
    for (const candidate of [
        { ...valid, sourceSha: 'b'.repeat(40) },
        { ...valid, files: [] },
        { ...valid, files: [valid.files[0], valid.files[0]] },
        ...['../escape.jar', 'target/sdk-runtime-inputs/../outside.jar', '.github/workflows/publish-sdk.yml',
            'target/sdk-runtime-inputs/plugins/secret.pem', 'target/sdk-runtime-inputs/plugins/c:escape.jar',
            'target/sdk-runtime-inputs/plugins/..\\..\\escape.jar',
            'pixivdownload-plugin-api/target/..\\..\\escape.jar']
            .map(name => ({ ...valid, files: [{ ...valid.files[0], file: name }] })),
        { ...valid, files: [{ ...valid.files[0], size: 256 * 1024 ** 2 + 1 }] },
        { ...valid, files: [{ ...valid.files[0], sha256: '0'.repeat(64) }] },
    ]) {
        write(candidate);
        const rejected = path.join(work, 'rejected');
        assert.throws(() => transferInputs(rejected, bundle, sourceSha, true));
        assert.equal(fs.existsSync(rejected), false);
    }
    write(valid);
    fs.appendFileSync(file, 'modified');
    assert.throws(() => verifyInputs(bundle, sourceSha), /bytes mismatch/u);
});
