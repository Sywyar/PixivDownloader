import {build} from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const output = path.resolve(root, '../../src/main/resources/web/vendor/codemirror');
fs.mkdirSync(output, {recursive: true});
const result = await build({entryPoints: [path.join(root, 'entry.mjs')], bundle: true, format: 'esm',
    target: 'es2022', minify: true, legalComments: 'none', outfile: path.join(output, 'codemirror.mjs'), metafile: true});
const lock = JSON.parse(fs.readFileSync(path.join(root, 'package-lock.json'), 'utf8'));
const packages = [...new Set(Object.keys(result.metafile.inputs).map(input =>
    input.replaceAll('\\', '/').match(/node_modules\/((?:@[^/]+\/)?[^/]+)/)?.[1]).filter(Boolean))].sort();
const manifest = {license: 'MIT', packages: {}, files: {}};
const notices = [];
for (const name of packages) {
    const pkg = lock.packages['node_modules/' + name];
    manifest.packages[name] = {version: pkg.version, source: pkg.resolved, integrity: pkg.integrity};
    const dir = path.join(root, 'node_modules', name);
    const license = fs.readdirSync(dir).find(file => /^licen[sc]e(?:\.txt|\.md)?$/i.test(file));
    if (!license) throw new Error('Missing license: ' + name);
    notices.push(name + '\n' + fs.readFileSync(path.join(dir, license), 'utf8'));
}
fs.writeFileSync(path.join(output, 'LICENSES.txt'), notices.join('\n\n'), 'utf8');
for (const file of ['codemirror.mjs', 'LICENSES.txt']) {
    manifest.files[file] = createHash('sha256').update(fs.readFileSync(path.join(output, file))).digest('hex');
}
fs.writeFileSync(path.join(output, 'vendor.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');
console.log('CodeMirror local bundle:', fs.statSync(path.join(output, 'codemirror.mjs')).size, 'bytes');
