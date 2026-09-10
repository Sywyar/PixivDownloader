import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { stageSdkArtifacts } from './sdk-consumer.mjs';
import { inspectSdkVersion, SDK_ARTIFACTS, SDK_GROUP_ID } from './sdk-version.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const options = {};
for (let index = 2; index < process.argv.length; index += 2) {
    const key = process.argv[index];
    assert.ok(['--sdk-repository', '--tool', '--sbt-launcher'].includes(key), `Unknown argument: ${key}`);
    assert.ok(process.argv[index + 1], `Missing value: ${key}`);
    options[key] = process.argv[index + 1];
}
assert.ok(options['--sdk-repository'], '--sdk-repository is required');
const selected = options['--tool'] ? [options['--tool']] : ['maven', 'gradle', 'sbt'];
assert.ok(selected.every(tool => ['maven', 'gradle', 'sbt'].includes(tool)), 'Unknown build tool');
if (selected.includes('sbt')) assert.ok(options['--sbt-launcher'], '--sbt-launcher is required for sbt');
const repository = path.resolve(options['--sdk-repository']);
const identity = inspectSdkVersion(root);
const target = path.join(root, 'target');
fs.mkdirSync(target, { recursive: true });
const work = fs.mkdtempSync(path.join(target, 'sdk-build-tools-'));
fs.cpSync(path.join(root, 'scripts/ci/fixtures/sdk-consumers'), work, { recursive: true });
const env = { ...process.env, SDK_VERSION: identity.version, SDK_REPOSITORY: repository,
    MAVEN_USER_HOME: path.join(work, 'maven-home'), COURSIER_CACHE: path.join(work, 'coursier') };

function run(command, args, cwd) {
    const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
    assert.equal(result.status, 0, `${command} failed: ${result.error?.message ?? result.status}`);
}

function wrapper(command, args, cwd) {
    if (process.platform !== 'win32') return run(command, args, cwd);
    const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
        '$sdkBuildArgs = ConvertFrom-Json $env:SDK_BUILD_ARGUMENTS; & $env:SDK_BUILD_WRAPPER @sdkBuildArgs; exit $LASTEXITCODE'], {
        cwd, stdio: 'inherit', env: { ...env, SDK_BUILD_WRAPPER: command, SDK_BUILD_ARGUMENTS: JSON.stringify(args) },
    });
    assert.equal(result.status, 0, `Build wrapper failed: ${result.error?.message ?? result.status}`);
}

function verifyClasspath(file) {
    const files = fs.readFileSync(file, 'utf8').trim().split(path.delimiter);
    for (const [artifact, packaging] of SDK_ARTIFACTS) {
        if (packaging !== 'jar') continue;
        const name = `${artifact}-${identity.version}.jar`;
        const resolved = files.filter(item => path.basename(item) === name);
        assert.equal(resolved.length, 1, `Expected one resolved ${name}`);
        const supplied = path.join(repository, ...SDK_GROUP_ID.split('.'), artifact, identity.version, name);
        assert.ok(fs.readFileSync(resolved[0]).equals(fs.readFileSync(supplied)), `Wrong SDK bytes: ${name}`);
    }
}

for (const tool of selected) {
    const project = path.join(work, tool);
    if (tool === 'maven') {
        const settings = path.join(work, 'settings.xml');
        fs.writeFileSync(settings, '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>', 'utf8');
        const local = path.join(work, 'm2');
        stageSdkArtifacts(local, repository, identity.version);
        const args = ['-B', '-ntp', '-s', settings, '-gs', settings,
            `-Dmaven.repo.local=${local}`, `-Dsdk.version=${identity.version}`, 'clean', 'package',
            'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath',
            '-DincludeScope=compile', '-Dmdep.outputFile=target/sdk-classpath.txt'];
        const command = path.join(root, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
        wrapper(command, args, project);
        stageSdkArtifacts(local, repository, identity.version);
        wrapper(command, ['-o', ...args], project);
        verifyClasspath(path.join(project, 'target/sdk-classpath.txt'));
        wrapper(command, ['-o', '-B', '-ntp', '-s', settings, '-gs', settings,
            `-Dmaven.repo.local=${local}`, `-Dsdk.version=${identity.version}`,
            'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath',
            '-DincludeScope=runtime', '-Dmdep.outputFile=target/runtime-classpath.txt'], project);
        const runtime = path.join(project, 'target/runtime-classpath.txt');
        assert.ok(!fs.existsSync(runtime) || fs.readFileSync(runtime, 'utf8').trim() === '', 'Runtime dependencies leaked');
    } else if (tool === 'gradle') {
        const command = path.join(root, 'pixivdownload-plugin-gui-compose',
            process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
        const args = ['--no-daemon', '--project-cache-dir', path.join(work, 'gradle-project-cache'),
            '--gradle-user-home', path.join(work, 'gradle-home'), '-p', project, 'clean', 'verifySdk'];
        wrapper(command, args, project);
        wrapper(command, ['--offline', ...args], project);
        verifyClasspath(path.join(project, 'build/sdk-classpath.txt'));
    } else {
        const repositories = path.join(work, 'sbt-repositories');
        fs.writeFileSync(repositories, `[repositories]\nsdk: ${pathToFileURL(repository).href}\ncentral: https://repo.maven.apache.org/maven2\n`, 'utf8');
        run('java', ['-Dfile.encoding=UTF-8', '-Duser.language=en', `-Dsbt.repository.config=${repositories}`, '-Dsbt.override.build.repos=true',
            `-Dsbt.boot.directory=${path.join(work, 'sbt-boot')}`, `-Dsbt.global.base=${path.join(work, 'sbt-global')}`,
            `-Dsbt.ivy.home=${path.join(work, 'ivy')}`, '-Dsbt.supershell=false', '-Dsbt.log.noformat=true',
            '-jar', path.resolve(options['--sbt-launcher']), 'clean', 'verifySdk'], project);
        verifyClasspath(path.join(project, 'target/sdk-classpath.txt'));
    }
    process.stdout.write(`${tool}: compiled the single SDK dependency; SDK bytes and runtime scope verified\n`);
}
process.stdout.write(`Evidence: ${work}\n`);
