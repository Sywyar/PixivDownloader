import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { readMavenPluginVersion, stageSdkArtifacts } from './sdk-consumer.mjs';
import { assertThinJarEntries, extractArchive } from './sdk-release.mjs';
import { inspectSdkVersion, SDK_ARTIFACTS, SDK_GROUP_ID } from './sdk-version.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const options = {};
for (let index = 2; index < process.argv.length; index += 2) {
    const key = process.argv[index];
    assert.ok(['--sdk-repository', '--tool', '--sbt-launcher', '--sdk-zip'].includes(key), `Unknown argument: ${key}`);
    assert.ok(process.argv[index + 1], `Missing value: ${key}`);
    options[key] = process.argv[index + 1];
}
assert.ok(options['--sdk-repository'], '--sdk-repository is required');
const selected = options['--tool'] ? [options['--tool']] : (options['--sdk-zip'] ? ['gradle', 'sbt'] : ['maven', 'gradle', 'sbt']);
assert.ok(selected.every(tool => ['maven', 'gradle', 'sbt'].includes(tool)), 'Unknown build tool');
assert.ok(!options['--sdk-zip'] || !selected.includes('maven'), 'Final ZIP Maven projects use sdk-consumer.mjs');
if (selected.includes('sbt')) assert.ok(options['--sbt-launcher'], '--sbt-launcher is required for sbt');
const repository = path.resolve(options['--sdk-repository']);
const identity = inspectSdkVersion(root);
const target = path.join(root, 'target');
fs.mkdirSync(target, { recursive: true });
const work = fs.mkdtempSync(path.join(target, 'sdk-build-tools-'));
fs.cpSync(path.join(root, 'scripts/ci/fixtures/sdk-consumers'), work, { recursive: true });
fs.mkdirSync(path.join(work, 'sbt/project'), { recursive: true });
fs.copyFileSync(path.join(root, 'plugin-templates/sdk-package/examples/sbt-plugin/project/build.properties'),
    path.join(work, 'sbt/project/build.properties'));
const templatePom = path.join(root, 'plugin-templates/minimal-feature-plugin/pom.xml');
const dependencyVersion = readMavenPluginVersion(path.join(root, 'pom.xml'), 'maven-dependency-plugin');
const mavenPluginVersions = ['compiler', 'jar'].map(name =>
    `-Dmaven.${name}.version=${readMavenPluginVersion(templatePom, `maven-${name}-plugin`)}`);
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

// 验收只注入暂存仓库与可观察的 classpath / artifact 输出，保留 ZIP 自带的构建和运行任务。
const sdkProject = path.join(work, 'project');
if (options['--sdk-zip']) {
    fs.mkdirSync(sdkProject);
    extractArchive(options['--sdk-zip'], sdkProject);
}

for (const tool of selected) {
    const project = options['--sdk-zip'] ? path.join(sdkProject, 'examples', `${tool}-plugin`) : path.join(work, tool);
    if (tool === 'maven') {
        const settings = path.join(work, 'settings.xml');
        fs.writeFileSync(settings, '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>', 'utf8');
        const local = path.join(work, 'm2');
        stageSdkArtifacts(local, repository, identity.version);
        const args = ['-B', '-ntp', '-s', settings, '-gs', settings,
            `-Dmaven.repo.local=${local}`, `-Dsdk.version=${identity.version}`, ...mavenPluginVersions, 'clean', 'package',
            `org.apache.maven.plugins:maven-dependency-plugin:${dependencyVersion}:build-classpath`,
            '-DincludeScope=compile', '-Dmdep.outputFile=target/sdk-classpath.txt'];
        const command = path.join(root, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
        wrapper(command, args, project);
        stageSdkArtifacts(local, repository, identity.version);
        wrapper(command, ['-o', ...args], project);
        verifyClasspath(path.join(project, 'target/sdk-classpath.txt'));
        wrapper(command, ['-o', '-B', '-ntp', '-s', settings, '-gs', settings,
            `-Dmaven.repo.local=${local}`, `-Dsdk.version=${identity.version}`, ...mavenPluginVersions,
            `org.apache.maven.plugins:maven-dependency-plugin:${dependencyVersion}:build-classpath`,
            '-DincludeScope=runtime', '-Dmdep.outputFile=target/runtime-classpath.txt'], project);
        const runtime = path.join(project, 'target/runtime-classpath.txt');
        assert.ok(!fs.existsSync(runtime) || fs.readFileSync(runtime, 'utf8').trim() === '', 'Runtime dependencies leaked');
    } else if (tool === 'gradle') {
        const command = path.join(options['--sdk-zip'] ? project : path.join(root, 'pixivdownload-plugin-gui-compose'),
            process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
        const extra = [];
        if (options['--sdk-zip']) {
            const init = path.join(work, 'verify-sdk.gradle');
            fs.writeFileSync(init, `allprojects {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(System.getenv('SDK_REPOSITORY')) } }
            filter { includeGroup('io.github.sywyar.pixivdownloader') }
        }
    }
    plugins.withId('java') {
        tasks.register('verifySdk') {
            dependsOn tasks.named('build')
            doLast {
                assert configurations.runtimeClasspath.files.empty : 'SDK runtime dependencies leaked'
                layout.buildDirectory.file('sdk-classpath.txt').get().asFile.setText(configurations.compileClasspath.asPath, 'UTF-8')
                layout.buildDirectory.file('sdk-artifact.txt').get().asFile.setText(tasks.jar.archiveFile.get().asFile.absolutePath, 'UTF-8')
            }
        }
    }
}
`, 'utf8');
            extra.push('--init-script', init);
        }
        const args = ['--no-daemon', '--project-cache-dir', path.join(work, 'gradle-project-cache'),
            '--gradle-user-home', path.join(work, 'gradle-home'), '-p', project, ...extra, 'clean', 'verifySdk',
            ...(options['--sdk-zip'] ? ['stopPlugin'] : [])];
        wrapper(command, args, project);
        wrapper(command, ['--offline', ...args], project);
        verifyClasspath(path.join(project, 'build/sdk-classpath.txt'));
    } else {
        if (options['--sdk-zip']) {
            fs.appendFileSync(path.join(project, 'build.sbt'), `
val verifySdk = taskKey[Unit]("验证 SDK 编译作用域及生成产物")
verifySdk := {
  val artifact = (Compile / packageBin).value
  require((Runtime / externalDependencyClasspath).value.isEmpty, "SDK runtime dependencies leaked")
  IO.write(target.value / "sdk-classpath.txt", (Compile / dependencyClasspath).value.map(_.data.getAbsolutePath).mkString(java.io.File.pathSeparator))
  IO.write(target.value / "sdk-artifact.txt", artifact.getAbsolutePath)
}
`, 'utf8');
        }
        const repositories = path.join(work, 'sbt-repositories');
        fs.writeFileSync(repositories, `[repositories]\nsdk: ${pathToFileURL(repository).href}\ncentral: https://repo.maven.apache.org/maven2\n`, 'utf8');
        run('java', ['-Dfile.encoding=UTF-8', '-Duser.language=en', `-Dsbt.repository.config=${repositories}`, '-Dsbt.override.build.repos=true',
            `-Dsbt.boot.directory=${path.join(work, 'sbt-boot')}`, `-Dsbt.global.base=${path.join(work, 'sbt-global')}`,
            `-Dsbt.ivy.home=${path.join(work, 'ivy')}`, '-Dsbt.supershell=false', '-Dsbt.log.noformat=true',
            '-jar', path.resolve(options['--sbt-launcher']), 'clean',
            ...(options['--sdk-zip'] ? ['checkJavaScript'] : []), 'verifySdk'], project);
        if (options['--sdk-zip']) run('java', ['-jar', path.join(sdkProject, 'tools/sdk-tools.jar'), 'stop', project], project);
        verifyClasspath(path.join(project, 'target/sdk-classpath.txt'));
    }
    if (options['--sdk-zip']) {
        const artifact = fs.readFileSync(path.join(project, tool === 'gradle' ? 'build' : 'target', 'sdk-artifact.txt'), 'utf8').trim();
        const listing = spawnSync('jar', ['--list', '--file', artifact], { encoding: 'utf8' });
        assert.equal(listing.status, 0);
        const entries = listing.stdout.split(/\r?\n/u).filter(Boolean);
        assertThinJarEntries(entries);
        assert.ok(entries.includes('com/example/pixivdownload/minimal/ExampleMinimalPlugin.class'));
        assert.ok(entries.includes('static/example-minimal.html'));
        assert.ok(!fs.existsSync(path.join(project, '.dev', 'current-run.json')), 'Build / stop unexpectedly started a host');
    }
    process.stdout.write(`${tool}: compiled the single SDK dependency; SDK bytes and runtime scope verified\n`);
}
process.stdout.write(`Evidence: ${work}\n`);
