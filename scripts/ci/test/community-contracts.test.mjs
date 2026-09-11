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

test('版本状态向量由独立消费者验证，旧恢复请求不能解除新下架', () => {
    const vectors = JSON.parse(fs.readFileSync(path.join(ROOT, 'contracts/community/v1/vectors/state-transitions.json'), 'utf8'));
    for (const item of vectors.versionStatus) {
        let error, after;
        if (item.before === 'REVOKED' || item.action === 'YANK' && item.before !== 'ACTIVE'
                || item.action === 'UNYANK' && item.before !== 'YANKED') error = 'INVALID_STATE_TRANSITION';
        else if (item.action === 'UNYANK' && item.yankedDecisionSha256 !== item.decisionSha256) error = 'BASELINE_CHANGED';
        else after = { YANK: 'YANKED', UNYANK: 'ACTIVE', REVOKE: 'REVOKED' }[item.action];
        assert.deepEqual({ after, error }, { after: item.after, error: item.error }, item.id);
    }
});

test('Java 共用的严格编码与 JCS 正反向量由独立 Python 和 Node 消费', () => {
    const vectorPath = path.join(ROOT, 'contracts/community/v1/vectors/json.json');
    const vectors = JSON.parse(fs.readFileSync(vectorPath, 'utf8'));
    const python = process.platform === 'win32' ? 'python' : 'python3';
    const outcomes = JSON.parse(execFileSync(python, [path.join(ROOT, 'scripts/ci/fixtures/community-json-consumer.py'), vectorPath], { encoding: 'utf8' }));
    assert.deepEqual(outcomes, vectors.strictJson.map(({ id, accepted }) => ({ id, accepted })));
    // 独立测试编码器使用 ECMAScript 原生数字及字符串序列化，不进入发行工具。
    const canonical = value => {
        if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
        if (value !== null && typeof value === 'object') {
            return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
        }
        return JSON.stringify(value);
    };
    for (const vector of vectors.jcs) assert.equal(canonical(JSON.parse(vector.input)), vector.canonical, vector.id);
});

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
