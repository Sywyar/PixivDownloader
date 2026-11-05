import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import {parseArgs} from 'node:util';
import assert from 'node:assert/strict';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const destination = path.resolve(process.argv[2] || path.join(repo, 'target/page-editor-acceptance'));
const {values, positionals} = parseArgs({args: process.argv.slice(3), allowPositionals: true, options: {
    'publication-recovery': {type: 'boolean'}, 'frozen-inputs': {type: 'string'},
}});
const phase = values['publication-recovery'] ? 'publication-recovery' : null;
const captcha = positionals[0] && !phase ? path.resolve(positionals[0]) : null;
const frozenInputs = values['frozen-inputs'] ? path.resolve(values['frozen-inputs']) : undefined;
assert.ok(destination.startsWith(path.join(repo, 'target') + path.sep) && !fs.existsSync(destination), 'Use a new target directory');
fs.mkdirSync(destination, {recursive: true});
const frozen = {};
const hash = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function copy(source, target) {
    fs.mkdirSync(path.dirname(target), {recursive: true}); fs.copyFileSync(source, target); frozen[target] = hash(target);
}
function artifact(module) {
    if (frozenInputs && module === 'pixivdownload-app') return path.join(frozenInputs, 'host.jar');
    if (frozenInputs && module === 'pixivdownload-sdk-tools') return path.join(repo, 'target/sdk-tools.jar');
    const directory = frozenInputs && module.startsWith('pixivdownload-plugin-') && module !== 'pixivdownload-plugin-api'
        ? path.join(frozenInputs, 'plugins') : path.join(repo, module, 'target');
    const files = fs.readdirSync(directory).filter(file => module === 'pixivdownload-app'
        ? file.endsWith('-boot.jar')
        : file.startsWith(module + '-') && file.endsWith('.jar') && !/-(sources|javadoc|original|processed|plain)\.jar$/.test(file));
    assert.equal(files.length, 1, module + ': expected one primary JAR: ' + files.join(','));
    return path.join(directory, files[0]);
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
run(process.platform === 'win32' ? 'python' : 'python3', [path.join(repo, 'pixivdownload-app/src/test/resources/page-components/build.py'),
    destination, createRequire(import.meta.url).resolve('typescript/bin/tsc')]);
if (captcha) fs.copyFileSync(captcha, path.join(destination, 'fixture/captcha.jar'));
const classes = path.join(destination, 'acceptance-classes'); fs.mkdirSync(classes);
const classpath = [host, path.join(host, 'BOOT-INF/classes'), path.join(host, 'BOOT-INF/lib/*')].join(path.delimiter);
run('javac', ['-proc:none', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes,
    path.join(repo, 'pixivdownload-app/src/test/java/acceptance/PageEditorAcceptance.java'),
    path.join(repo, 'pixivdownload-app/src/test/java/acceptance/OperationFormsAcceptance.java'),
    path.join(repo, 'pixivdownload-app/src/test/java/acceptance/CaptchaAcceptance.java')]);
run('java', ['-Dfile.encoding=UTF-8', '-cp', classes + path.delimiter + classpath, 'acceptance.PageEditorAcceptance',
    destination, path.join(repo, 'pixivdownload-app/src/test/resources/page-components/browser.mjs'),
    ...(phase ? [phase] : captcha ? ['captcha'] : [])]);
function verifyRecoveryCli() {
    const root = path.join(destination, 'recovery-runtime'), store = path.join(root, 'data/web-publications');
    fs.mkdirSync(store, {recursive: true}); fs.mkdirSync(path.join(root, 'config'));
    fs.copyFileSync(path.join(destination, 'runtime/config/web-publications.enrolled'), path.join(root, 'config/web-publications.enrolled'));
    for (const file of fs.readdirSync(path.join(destination, 'runtime/data/web-publications')).filter(file => file.endsWith('.json')))
        fs.copyFileSync(path.join(destination, 'runtime/data/web-publications', file), path.join(store, file));
    fs.writeFileSync(path.join(root, 'config/config.yaml'), 'database.engine: unavailable-provider\ndatabase.jdbc-url: invalid:unreachable\napp.language: en-US\n');
    const committed = JSON.parse(fs.readFileSync(path.join(store, 'current.json'), 'utf8'));
    const before = JSON.parse(fs.readFileSync(path.join(store, committed.file), 'utf8'));
    const args = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true',
        ...['config', 'data', 'state', 'instance', 'plugins'].map(kind => '-Dpixivdownload.' + kind + '-dir=' + path.join(root, kind)),
        '-jar', path.join(destination, 'host.jar'), '--no-gui', '--web-publications'];
    function invoke(extra = [], status = 0) {
        const result = spawnSync('java', [...args, ...extra], {cwd: repo, encoding: 'utf8', windowsHide: true, maxBuffer: 1024 * 1024});
        fs.appendFileSync(path.join(destination, 'recovery-cli.log'), result.stdout + result.stderr);
        assert.equal(result.status, status, 'real recovery CLI exit');
        return result.stdout;
    }
    assert.match(invoke(), /Current publication:/);
    fs.writeFileSync(path.join(store, 'current.json'), '{"corrupt":true}');
    assert.match(invoke(), /unavailable/i);
    invoke(['--publication-restore=' + committed.file, '--publication-sha256=' + '0'.repeat(64)], 1);
    assert.equal(fs.readFileSync(path.join(store, 'current.json'), 'utf8'), '{"corrupt":true}', 'wrong digest preserves failed state');
    invoke(['--publication-restore=' + committed.file, '--publication-sha256=' + committed.digest]);
    const afterCommit = JSON.parse(fs.readFileSync(path.join(store, 'current.json'), 'utf8'));
    const after = JSON.parse(fs.readFileSync(path.join(store, afterCommit.file), 'utf8'));
    // Java Set 跨 JVM 不保证序列化顺序；仍逐项核对成员与全部其它持久化字段。
    for (const snapshot of [before, after]) {
        const hooks = [...Object.values(snapshot.pages).flatMap(page => page.hooks || []),
            ...(snapshot.configuration?.requirements || [])];
        for (const hook of hooks) {
            hook.definition.phases.sort();
            for (const anchor of hook.targetAnchors) {
                for (const field of ['routes', 'paths', 'methods', 'alternatives']) anchor[field].sort();
            }
            hook.targetAnchors.sort((left, right) => {
                const a = JSON.stringify(left), b = JSON.stringify(right);
                return a < b ? -1 : a > b ? 1 : 0;
            });
        }
    }
    assert.deepEqual(after.pages, before.pages, 'recovery preserves page, operation and resource facts');
    assert.deepEqual(after.configuration, before.configuration, 'recovery preserves standalone Hook bindings');
    assert.notEqual(after.epoch, before.epoch, 'recovery invalidates old documents and proofs');
    assert.match(invoke(), /Current publication:/);
    console.log('PAGE_PUBLICATION_REAL_CLI_RECOVERY_WITHOUT_DATABASE_PASSED');
}
verifyRecoveryCli();
for (const [file, identity] of Object.entries(frozen)) assert.equal(hash(file), identity, 'Frozen artifact modified: ' + file);
console.log('PAGE_EDITOR_FROZEN_INPUTS_VERIFIED');
