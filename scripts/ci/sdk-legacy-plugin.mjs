import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

// 固定已发布原始二进制，验证历史插件合同；不能随当前插件版本滚动更新。
export const LEGACY_PLUGIN = Object.freeze({
    repository: 'Sywyar/PixivDownloader-plugins', release: 'gui-swing-v1.0.2',
    asset: 647429117, file: 'pixivdownload-plugin-gui-swing-1.0.2.jar', bytes: 5445262,
    sha256: 'ef5f245cbf10345c273ea9bd641373cf034d7ad3e40017ac4d9881920fb65e5d'
});

function verifyBytes(bytes) {
    assert.equal(bytes.length, LEGACY_PLUGIN.bytes, 'Published legacy plugin length mismatch');
    assert.equal(createHash('sha256').update(bytes).digest('hex'), LEGACY_PLUGIN.sha256, 'Published legacy plugin digest mismatch');
}

export function verifyLegacyPlugin(file) {
    assert.ok(file && fs.lstatSync(file).isFile(), 'Original published legacy plugin is required');
    assert.equal(fs.statSync(file).size, LEGACY_PLUGIN.bytes, 'Published legacy plugin length mismatch');
    verifyBytes(fs.readFileSync(file));
}

export function downloadLegacyPlugin(destination) {
    if (fs.existsSync(destination)) { verifyLegacyPlugin(destination); return; }
    let bytes;
    try {
        bytes = execFileSync('gh', ['api', `repos/${LEGACY_PLUGIN.repository}/releases/assets/${LEGACY_PLUGIN.asset}`,
            '-H', 'Accept: application/octet-stream'], {
            maxBuffer: LEGACY_PLUGIN.bytes + 1, timeout: 60_000, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
        });
    } catch { throw new Error('Published legacy plugin download failed'); }
    verifyBytes(bytes);
    fs.mkdirSync(path.dirname(destination), {recursive: true});
    fs.writeFileSync(destination, bytes, {flag: 'wx'});
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    assert.equal(process.argv.length, 3, 'Expected one destination path');
    downloadLegacyPlugin(path.resolve(process.argv[2]));
    console.log(`SDK_LEGACY_PLUGIN_VERIFIED ${LEGACY_PLUGIN.sha256}`);
}
