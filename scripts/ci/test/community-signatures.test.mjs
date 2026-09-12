import assert from 'node:assert/strict';
import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const vectorPath = fileURLToPath(new URL('../../../contracts/community/v1/vectors/signatures.properties', import.meta.url));
const sha = bytes => createHash('sha256').update(bytes).digest();
const integer = (value, width) => {
  const bytes = Buffer.alloc(width);
  if (width === 4) bytes.writeInt32BE(value); else bytes.writeBigInt64BE(BigInt(value));
  return bytes;
};
const text = value => { const bytes = Buffer.from(value, 'utf8'); return Buffer.concat([integer(bytes.length, 4), bytes]); };
const join = (...parts) => Buffer.concat(parts);

// 独立 Node 编码器消费公开协议夹具，不调用 Java codec，也不用于生产签名。
export function messages(v) {
  const format = integer(1, 4);
  const prefix = domain => [text(domain), format];
  const identity = [text(v.algorithm), text(v.keyId)];
  const raw = Buffer.from(v.rawHex, 'hex');
  const body = Buffer.from(v.canonicalHex, 'hex');
  const document = domain => join(...prefix(domain), text(v.repositoryId), integer(v.sequence, 8), integer(raw.length, 8), sha(raw));
  const operation = domain => join(...prefix(domain), ...identity, integer(body.length, 8), sha(body));
  return {
    package: join(...prefix('pixivdownloader-community-package-v1'), ...identity, text(v.repositoryId),
      text(v.pluginId), text(v.version), integer(raw.length, 8), sha(raw), text(v.assuranceLevel),
      text(v.sourceCommit), Buffer.from(v.reviewRecordSha256, 'hex')),
    directory: document('pixivdownloader-community-directory-root-v1'),
    rotation: operation('pixivdownloader-community-publisher-key-rotation-v1'),
    status: operation('pixivdownloader-community-version-status-request-v1'),
    transfer: operation('pixivdownloader-community-ownership-transfer-v1'),
    artifact: join(...prefix('PixivDownloader plugin artifact signature v1'), ...identity,
      text(v.pluginId), text(v.version), integer(raw.length, 8), sha(raw)),
    repositoryUpdate: document('pixivdownloader-repository-update-v1'),
    revocations: document('pixivdownloader-plugin-revocations-v1'),
  };
}

test('公开签名向量由独立语言编码、签名、验签，逐字节篡改均失败', () => {
  const v = Object.fromEntries(fs.readFileSync(vectorPath, 'utf8').split('\n')
    .filter(line => line && !line.startsWith('#')).map(line => {
      const separator = line.indexOf('=');
      return [line.slice(0, separator), line.slice(separator + 1)];
    }));
  const privateKey = createPrivateKey({ key: Buffer.from(v.publicTestPrivateKey, 'base64'), format: 'der', type: 'pkcs8' });
  const publicKey = createPublicKey({ key: Buffer.from(v.publicKey, 'base64'), format: 'der', type: 'spki' });
  assert.equal(sha(Buffer.from(v.publicKey, 'base64')).toString('hex'), v.fingerprint);
  const encoded = messages(v);
  for (const [name, message] of Object.entries(encoded)) {
    assert.equal(message.toString('hex'), v[`${name}.messageHex`], name);
    const signature = Buffer.from(v[`${name}.signature`], 'base64');
    assert.equal(sign(null, message, privateKey).toString('base64'), signature.toString('base64'), name);
    assert.equal(verify(null, message, publicKey, signature), true, name);
    assert.equal(verify(null, message.subarray(1), publicKey, signature), false);
    for (let i = 0; i < message.length; i++) {
      const changed = Buffer.from(message);
      changed[i] ^= 1;
      assert.equal(verify(null, changed, publicKey, signature), false, `${name}: byte ${i}`);
    }
    for (const other of Object.values(encoded)) {
      if (other !== message) assert.equal(verify(null, other, publicKey, signature), false);
    }
  }
});
