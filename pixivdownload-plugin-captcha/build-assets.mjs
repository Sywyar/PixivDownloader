import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import assert from 'node:assert/strict';
const module = path.dirname(fileURLToPath(import.meta.url));
const install = spawnSync(process.platform === 'win32' ? 'npm.cmd' : 'npm',
    ['ci', '--ignore-scripts', '--no-audit', '--no-fund', ...(process.argv[3] === '--offline=true' ? ['--offline'] : [])],
    {cwd: module, stdio: 'inherit', windowsHide: true, shell: process.platform === 'win32'});
assert.equal(install.status, 0, 'ALTCHA worker dependency installation failed');
const source = path.join(module, 'node_modules/altcha');
const target = path.resolve(process.argv[2] || path.join(module, 'target/classes/web/vendor'));
fs.mkdirSync(target, {recursive: true});
fs.copyFileSync(path.join(source, 'dist/workers/sha.js'), path.join(target, 'sha.js'));
fs.copyFileSync(path.join(source, 'dist/workers/pbkdf2.js'), path.join(target, 'pbkdf2.js'));
fs.copyFileSync(path.join(source, 'LICENSE.txt'), path.join(target, 'ALTCHA-LICENSE.txt'));
