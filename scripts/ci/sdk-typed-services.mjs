import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { inspectSdkVersion, SDK_MODULES, SDK_GROUP_ID } from './sdk-version.mjs';

// 先冻结已构建宿主和 SDK，再编译宿主 classpath 中不存在的业务契约。
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const [hostJar, toolsJar, repository, project, sqliteJar, workbenchJar, destination] = process.argv.slice(2).map(value => path.resolve(value));
assert.ok(destination && !fs.existsSync(destination), 'Expected seven paths and a new work directory');
assert.ok(destination.startsWith(path.join(repo, 'target') + path.sep), 'Work directory must belong to target');
fs.mkdirSync(destination, {recursive:true});
const frozen = new Map();
const hash = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function copy(source, target) {
  fs.mkdirSync(path.dirname(target), {recursive:true}); fs.copyFileSync(source, target);
  frozen.set(target, hash(target));
}
function run(command, args, cwd = repo) {
  const result = spawnSync(command, args, {cwd, stdio:'inherit', windowsHide:true});
  assert.equal(result.status, 0, `${command} failed: ${result.error?.message ?? result.status}`);
}
copy(hostJar, path.join(destination, 'host.jar'));
copy(toolsJar, path.join(destination, 'sdk-tools.jar'));
const version = inspectSdkVersion(repo).version;
for (const artifact of SDK_MODULES) {
  for (const extension of artifact.endsWith('-bom') ? ['pom'] : ['pom','jar']) {
    const relative = path.join(SDK_GROUP_ID.replaceAll('.', '/'), artifact, version, `${artifact}-${version}.${extension}`);
    copy(path.join(repository, relative), path.join(destination, 'sdk-repository', relative));
  }
}
fs.writeFileSync(path.join(destination, 'frozen.json'), JSON.stringify(Object.fromEntries(frozen), null, 2), 'utf8');
const host = path.join(destination, 'host'); fs.mkdirSync(host);
run('jar', ['--extract', '--file', path.join(destination, 'host.jar')], host);
run(process.platform === 'win32' ? 'python' : 'python3', [path.join(repo, 'pixivdownload-app/src/test/resources/typed-services/build.py'),
  destination, host, path.join(destination, 'sdk-tools.jar'), createRequire(import.meta.url).resolve('typescript/bin/tsc')]);
const classes = path.join(destination, 'acceptance-classes'); fs.mkdirSync(classes);
const classpath = [host, path.join(host, 'BOOT-INF/classes'), path.join(host, 'BOOT-INF/lib/*')].join(path.delimiter);
run('javac', ['-proc:none', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes,
  path.join(repo, 'pixivdownload-app/src/test/java/acceptance/TypedServicesAcceptance.java')]);
const temporary = path.join(destination, 'runtime'); fs.mkdirSync(temporary);
run('java', ['-Dfile.encoding=UTF-8', `-Djava.io.tmpdir=${temporary}`, '-cp', classes + path.delimiter + classpath,
  'acceptance.TypedServicesAcceptance', project, sqliteJar, workbenchJar, path.join(destination, 'fault-fixtures'),
  path.join(repo, 'pixivdownload-app/src/test/resources/typed-services/remote-http-probe.mjs')]);
for (const [file, identity] of frozen) assert.equal(hash(file), identity, `Frozen input changed: ${file}`);
console.log('SDK_TYPED_SERVICES_FROZEN_INPUTS_VERIFIED');
