import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const source = fileURLToPath(new URL('../../aot-packaged.ps1', import.meta.url));
const shells = process.platform === 'win32' ? ['powershell.exe', 'pwsh'] : ['pwsh'];
for (const shell of shells) test(`完整 AOT 准备与运行编排保留旧实例并复用产物 (${shell})`, async t => {
    const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'aot packaged [test] ')));
    const scripts = path.join(root, 'scripts');
    const runtime = path.join(root, 'target/runtime');
    const jar = path.join(root, 'app/target/host.jar');
    const stateFile = path.join(runtime, 'current.json');
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    fs.mkdirSync(scripts);
    fs.copyFileSync(source, path.join(scripts, 'aot-packaged.ps1'));
    const write = (name, text) => fs.writeFileSync(path.join(root, name), text);
    const sources = { host: 'host-one', plugin: 'plugin-one' };
    const update = () => write('sources.json', JSON.stringify(sources));
    update();
    write('user settings.xml', '<settings/>');
    write('global settings.xml', '<settings/>');
    write('build.mjs', `import fs from 'node:fs';
fs.appendFileSync('builds.log',JSON.stringify(process.argv.slice(2))+'\\n');
if(fs.existsSync('build-fail')) process.exit(7);
const s=JSON.parse(fs.readFileSync('sources.json','utf8'));
fs.mkdirSync('app/target',{recursive:true});
fs.writeFileSync('app/target/host.jar',s.host);fs.writeFileSync('plugin.jar',s.plugin);
`);
    write('mvnw.cmd', '@echo off\r\nnode "%~dp0build.mjs" %*\r\nexit /b %errorlevel%\r\n');
    write('mvnw', '#!/bin/sh\nexec node "./build.mjs" "$@"\n');
    fs.chmodSync(path.join(root, 'mvnw'), 0o755);
    write('scripts/package-local-plugin-staging.ps1', `
function Get-Sha256Hex($Path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() }
function Assert-BootJarBoundary($Path) { if (-not (Test-Path -LiteralPath $Path)) { throw 'Missing built host' } }
function Get-OfficialDistributionPlugins { param([switch]$IncludeOptional); if (-not $IncludeOptional) { throw 'Missing optional plugins' }; [pscustomobject]@{Module='fixture-plugin'} }
function Find-ModulePluginArtifact($Plugin, $ProjectRoot) { Join-Path $ProjectRoot 'plugin.jar' }
function Get-OfficialPluginArtifactExtension($Plugin) { 'jar' }
function Stage-OfficialPlugins {
 param($AppDir, $Plugins, $ProjectRoot, [switch]$AllowUnsignedLocalPlugins)
 if (-not $AllowUnsignedLocalPlugins) { throw 'Wrong local source policy' }
 if (Test-Path -LiteralPath (Join-Path $ProjectRoot 'stage-fail')) { throw 'Staging failed' }
 [void][IO.Directory]::CreateDirectory((Join-Path $AppDir 'plugins'))
 Copy-Item -LiteralPath (Join-Path $ProjectRoot 'plugin.jar') -Destination (Join-Path $AppDir 'plugins/fixture-plugin.jar')
 return 1
}
`);
    write('scripts/aot-cache.mjs', `import fs from 'node:fs';import path from 'node:path';import {createHash} from 'node:crypto';import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const args=process.argv.slice(2),command=args[0],value=n=>args[args.indexOf(n)+1];
const jar=value('--jar'),cache=value('--cache-dir');
if(command==='key')console.log(createHash('sha256').update(fs.readFileSync(jar)).digest('hex'));
if(command==='prepare'){
 if(fs.existsSync(path.join(root,'cache-fail')))process.exit(8);
 if(!fs.existsSync(cache)){fs.mkdirSync(cache,{recursive:true});fs.writeFileSync(path.join(cache,'trained'),'cache');fs.appendFileSync(path.join(root,'trains.log'),'train\\n');}
}
if(command==='run'){
 fs.writeFileSync(path.join(root,'run.json'),JSON.stringify({cwd:process.cwd(),jar,cache,plugins:value('--plugins-dir'),instance:value('--instance-dir')}));
 if(fs.existsSync(path.join(root,'hold-run')))await new Promise(resolve=>{const timer=setInterval(()=>{if(fs.existsSync(path.join(root,'release-run'))){clearInterval(timer);resolve();}},30);});
}
`);
    const args = command => ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(scripts, 'aot-packaged.ps1'),
        '-Command', command, '-Jar', jar, '-RuntimeDirectory', runtime, '-Offline:true', '-LocalRepository', path.join(root, 'local repo'),
        `-UserSettings:${path.join(root, 'user settings.xml')}`, `-GlobalSettings:${path.join(root, 'global settings.xml')}`];
    const invoke = (command, success = true) => {
        const result = spawnSync(shell, args(command), { encoding: 'utf8', windowsHide: true });
        assert.equal(result.status === 0, success, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
        return result;
    };
    invoke('run', false);
    write('build-fail', '');
    invoke('prepare', false);
    assert.equal(fs.existsSync(stateFile), false);
    fs.unlinkSync(path.join(root, 'build-fail'));
    invoke('prepare');
    const first = fs.readFileSync(stateFile, 'utf8');
    const state = JSON.parse(first);
    assert.equal(fs.readFileSync(path.join(runtime, 'artifacts', state.bundle, 'plugins/fixture-plugin.jar'), 'utf8'), sources.plugin);
    const training = path.join(runtime, 'cache', state.cache, 'trained');
    const modified = fs.statSync(training).mtimeMs;
    invoke('run');
    const run = JSON.parse(fs.readFileSync(path.join(root, 'run.json'), 'utf8'));
    assert.equal(run.cwd, path.join(runtime, 'instance'));
    assert.equal(run.plugins, path.join(runtime, 'artifacts', state.bundle, 'plugins'));
    assert.equal(run.instance, path.join(runtime, 'instance/ipc'));
    fs.writeFileSync(path.join(run.cwd, 'config.yaml'), 'keep: true');
    invoke('prepare');
    assert.equal(fs.readFileSync(stateFile, 'utf8'), first);
    assert.equal(fs.statSync(training).mtimeMs, modified);
    sources.plugin = 'plugin-two'; update();
    invoke('prepare');
    const second = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
    assert.notEqual(second.bundle, state.bundle);
    assert.equal(second.cache, state.cache);
    assert.equal(fs.readFileSync(path.join(root, 'trains.log'), 'utf8'), 'train\n');
    assert.equal(fs.readFileSync(path.join(run.cwd, 'config.yaml'), 'utf8'), 'keep: true');
    const previous = fs.readFileSync(stateFile, 'utf8');
    sources.host = 'host-two'; update();
    for (const failure of ['build-fail', 'stage-fail', 'cache-fail']) {
        write(failure, ''); invoke('prepare', false);
        assert.equal(fs.readFileSync(stateFile, 'utf8'), previous);
        fs.unlinkSync(path.join(root, failure));
    }
    invoke('prepare');
    const third = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
    assert.notEqual(third.cache, second.cache);
    assert.equal(fs.readFileSync(path.join(root, 'trains.log'), 'utf8'), 'train\ntrain\n');
    const artifact = path.join(runtime, 'artifacts', third.bundle, 'plugins/fixture-plugin.jar');
    fs.writeFileSync(artifact, 'corrupt');
    invoke('run', false); invoke('prepare', false);
    assert.equal(fs.readFileSync(artifact, 'utf8'), 'corrupt');
    fs.writeFileSync(artifact, sources.plugin);
    const savedState = fs.readFileSync(stateFile);
    for (const invalid of ['{', ' '.repeat(1024 * 1024 + 1), JSON.stringify({ ...third, bundle: '../outside' })]) {
        fs.writeFileSync(stateFile, invalid);
        invoke('run', false);
        assert.equal(fs.readFileSync(path.join(run.cwd, 'config.yaml'), 'utf8'), 'keep: true');
    }
    fs.writeFileSync(stateFile, savedState);
    write('hold-run', ''); fs.unlinkSync(path.join(root, 'run.json'));
    const child = spawn(shell, args('run'), { stdio: 'ignore', windowsHide: true });
    const closed = new Promise(resolve => child.once('close', resolve));
    try {
        for (let i = 0; i < 200 && !fs.existsSync(path.join(root, 'run.json')); i++) await new Promise(resolve => setTimeout(resolve, 50));
        assert.ok(fs.existsSync(path.join(root, 'run.json')), 'Run must start before checking its lock');
        assert.match(invoke('prepare', false).stderr, /busy/);
    } finally { write('release-run', ''); assert.equal(await closed, 0); }
    const buildArgs = JSON.parse(fs.readFileSync(path.join(root, 'builds.log'), 'utf8').trim().split('\n')[0]);
    assert.ok(buildArgs.includes('verify'));
    assert.ok(buildArgs.includes('-o'));
    assert.ok(buildArgs.includes('-P!aot'));
    assert.ok(buildArgs.includes(`-Dmaven.repo.local=${path.join(root, 'local repo')}`));
    assert.equal(buildArgs[buildArgs.indexOf('-s') + 1], path.join(root, 'user settings.xml'));
    assert.equal(buildArgs[buildArgs.indexOf('-gs') + 1], path.join(root, 'global settings.xml'));
    assert.equal(fs.readdirSync(runtime).some(name => name.startsWith('.staging-')), false);
});
