import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const destination = path.resolve(process.argv[2] || path.join(repo, 'target/page-editor-acceptance'));
assert.ok(destination.startsWith(path.join(repo, 'target') + path.sep) && !fs.existsSync(destination), 'Use a new target directory');
fs.mkdirSync(destination, {recursive: true});
const frozen = {};
const hash = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function copy(source, target) {
    fs.mkdirSync(path.dirname(target), {recursive: true}); fs.copyFileSync(source, target); frozen[target] = hash(target);
}
function artifact(module) {
    const files = fs.readdirSync(path.join(repo, module, 'target')).filter(file => module === 'pixivdownload-app'
        ? file.endsWith('-boot.jar')
        : file.endsWith('.jar') && !/-(sources|javadoc|original|processed|plain)\.jar$/.test(file));
    assert.equal(files.length, 1, module + ': expected one primary JAR: ' + files.join(','));
    return path.join(repo, module, 'target', files[0]);
}
function run(command, args, cwd = repo) {
    const result = spawnSync(command, args, {cwd, stdio: 'inherit', windowsHide: true});
    assert.equal(result.status, 0, command + ' failed: ' + (result.error?.message || result.status));
}
copy(artifact('pixivdownload-app'), path.join(destination, 'host.jar'));
copy(artifact('pixivdownload-sdk-tools'), path.join(destination, 'sdk-tools.jar'));
for (const module of ['pixivdownload-sdk-info', 'pixivdownload-plugin-api', 'pixivdownload-core-api']) {
    const jar = artifact(module); copy(jar, path.join(destination, 'sdk', path.basename(jar)));
}
for (const id of ['page-editor', 'database-sqlite', 'download-workbench', 'ai', 'tts']) {
    copy(artifact('pixivdownload-plugin-' + id), path.join(destination, id + '.jar'));
}
fs.writeFileSync(path.join(destination, 'frozen.json'), JSON.stringify(frozen, null, 2), 'utf8');
const host = path.join(destination, 'host'); fs.mkdirSync(host);
run('jar', ['--extract', '--file', path.join(destination, 'host.jar')], host);
run(process.platform === 'win32' ? 'python' : 'python3', [path.join(repo, 'pixivdownload-app/src/test/resources/page-components/build.py'), destination]);
const classes = path.join(destination, 'acceptance-classes'); fs.mkdirSync(classes);
const classpath = [host, path.join(host, 'BOOT-INF/classes'), path.join(host, 'BOOT-INF/lib/*')].join(path.delimiter);
run('javac', ['-proc:none', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes,
    path.join(repo, 'pixivdownload-app/src/test/java/acceptance/PageEditorAcceptance.java')]);
run('java', ['-Dfile.encoding=UTF-8', '-cp', classes + path.delimiter + classpath, 'acceptance.PageEditorAcceptance',
    destination, path.join(repo, 'pixivdownload-app/src/test/resources/page-components/browser.mjs')]);
for (const [file, identity] of Object.entries(frozen)) assert.equal(hash(file), identity, 'Frozen artifact modified: ' + file);
console.log('PAGE_EDITOR_FROZEN_INPUTS_VERIFIED');
