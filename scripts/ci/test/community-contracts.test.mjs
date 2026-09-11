import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { checkCommunitySource, describeCommunityBundle, stageCommunityBundle, verifyCommunityBundle } from '../community-contracts.mjs';
import { createArchive, extractArchive, initializeProjectGit } from '../sdk-release.mjs';
import { inspectSdkVersion } from '../sdk-version.mjs';

const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
const sha = bytes => crypto.createHash('sha256').update(bytes).digest();

test('合同清单固定全部资源和真实工具版本，SDK 初始 Git 与解压副本保持同一摘要', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'community-contract-'));
    try {
        const workspace = path.join(root, 'workspace');
        fs.mkdirSync(path.join(workspace, 'tools'), { recursive: true });
        const tool = path.join(workspace, 'tools/sdk-tools.jar');
        fs.writeFileSync(tool, 'tool fixture\n', 'utf8');
        const sourceSha = execFileSync('git', ['-C', ROOT, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
        const metadata = stageCommunityBundle(ROOT, workspace, tool, sourceSha);
        assert.equal(metadata.manifestSha256, checkCommunitySource(ROOT));
        assert.equal(metadata.tool.sha256, sha(fs.readFileSync(tool)).toString('hex'));
        initializeProjectGit(workspace, { repoRoot: ROOT, sourceSha, sdkVersion: inspectSdkVersion(ROOT).version });
        const zip = path.join(root, 'sdk.zip'); createArchive(workspace, zip);
        const extracted = path.join(root, 'extracted'); fs.mkdirSync(extracted); extractArchive(zip, extracted);
        const copy = path.join(extracted, 'contracts/community/v1');
        assert.deepEqual(verifyCommunityBundle(copy, metadata.manifestSha256), describeCommunityBundle(ROOT));
        for (const file of ['contracts/community/v1/bundle-manifest.json', 'tools/community-contract.json']) {
            assert.deepEqual(execFileSync('git', ['-C', extracted, 'show', `HEAD:${file}`]), fs.readFileSync(path.join(extracted, file)));
        }
        const target = path.join(copy, 'messages.json');
        const original = fs.readFileSync(target); fs.appendFileSync(target, ' ');
        assert.throws(() => verifyCommunityBundle(copy, metadata.manifestSha256), /COMMUNITY_RESOURCE_HASH/u);
        fs.writeFileSync(target, original); fs.writeFileSync(path.join(copy, 'unexpected.json'), '{}');
        assert.throws(() => verifyCommunityBundle(copy, metadata.manifestSha256), /COMMUNITY_RESOURCE_SET/u);
        assert.throws(() => verifyCommunityBundle(copy, '0'.repeat(64)), /COMMUNITY_MANIFEST_HASH/u);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('签名格式资源由独立消费者编码并与公开真实签名向量一致', () => {
    const root = path.join(ROOT, 'contracts/community/v1');
    const format = JSON.parse(fs.readFileSync(path.join(root, 'messages.json'), 'utf8'));
    const v = Object.fromEntries(fs.readFileSync(path.join(root, 'vectors/signatures.properties'), 'utf8').split('\n')
        .filter(line => line && !line.startsWith('#')).map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)]; }));
    const raw = Buffer.from(v.rawHex, 'hex'), canonical = Buffer.from(v.canonicalHex, 'hex');
    const values = { ...v, formatVersion: format.formatVersion, size: raw.length, sha256: sha(raw), canonicalSize: canonical.length,
        requestId: sha(canonical), reviewRecordSha256: Buffer.from(v.reviewRecordSha256, 'hex') };
    for (const message of format.messages) {
        const parts = message.fields.map(field => {
            const [name, type] = field.split(':'); const value = name === 'domain' ? message.domain : values[name];
            if (type === 'digest') { assert.equal(value.length, 32); return value; }
            if (type === 'string') { const bytes = Buffer.from(value, 'utf8'); const length = Buffer.alloc(4); length.writeUInt32BE(bytes.length); return Buffer.concat([length, bytes]); }
            const bytes = Buffer.alloc(type === 'u32' ? 4 : 8);
            if (type === 'u32') bytes.writeUInt32BE(Number(value)); else bytes.writeBigUInt64BE(BigInt(value));
            return bytes;
        });
        assert.equal(Buffer.concat(parts).toString('hex'), v[`${message.id}.messageHex`], message.id);
    }
});
