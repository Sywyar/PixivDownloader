import fs from 'node:fs';
import path from 'node:path';
const source = path.resolve('node_modules/altcha');
const target = path.resolve('target/classes/web/vendor');
fs.mkdirSync(target, {recursive: true});
fs.copyFileSync(path.join(source, 'dist/workers/sha.js'), path.join(target, 'sha.js'));
fs.copyFileSync(path.join(source, 'dist/workers/pbkdf2.js'), path.join(target, 'pbkdf2.js'));
fs.copyFileSync(path.join(source, 'LICENSE.txt'), path.join(target, 'ALTCHA-LICENSE.txt'));
