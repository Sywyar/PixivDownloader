import { execFileSync } from 'node:child_process';
import { generateKeyPairSync } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { freezeDevelopmentRuntime } from './sdk-runtime.mjs';

// CI 夹具使用临时自定义仓库密钥，所有制品字节仍来自当前 canonical 构建集合。
const [sourceSha, hostJar, signatureTool] = process.argv.slice(2);
if (!/^[0-9a-f]{40}$/u.test(sourceSha) || !hostJar || !signatureTool) {
    throw new Error('Expected source SHA, host JAR and signature tool');
}
const root = process.cwd();
const inputs = path.join(root, 'target/sdk-runtime-inputs');
const output = path.join(root, 'build/sdk-development-runtime');
const layout = path.join(output, 'full-offline');
if (fs.existsSync(output)) throw new Error('Candidate runtime output must be new');
const run = (command, args, cwd = root) => execFileSync(command, args, { cwd, stdio: 'inherit', windowsHide: true });
run('pwsh', ['-NoProfile', '-File', 'scripts/ci/sdk-runtime-inputs.ps1',
    '-OutputDirectory', inputs, '-HostJar', path.resolve(hostJar)]);
fs.mkdirSync(output, { recursive: true });
fs.cpSync(inputs, layout, { recursive: true });
const hostVersion = path.basename(hostJar).replace(/^PixivDownload-/u, '').replace(/-boot.jar$/u, '');
if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u.test(hostVersion)) throw new Error('Invalid host version');
fs.renameSync(path.join(layout, 'host.jar'), path.join(layout, `PixivDownload-${hostVersion}.jar`));
const manifestPath = path.join(layout, 'plugins-manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const keyId = 'sdk-ci-ephemeral';
const { privateKey, publicKey } = generateKeyPairSync('ed25519');
const publicSpki = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const privateFile = path.join(root, 'target/sdk-ci-key.pem');
fs.writeFileSync(privateFile, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
try {
    for (const entry of manifest) {
        const artifact = path.join(layout, entry.file);
        run('java', ['-cp', path.resolve(signatureTool),
            'top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool', 'artifact',
            '--artifact', artifact, '--plugin-id', entry.id, '--version', entry.version,
            '--key-id', keyId, '--private-key', privateFile, '--out', `${artifact}.sig`]);
        entry.signature = JSON.parse(fs.readFileSync(`${artifact}.sig`, 'utf8'));
        fs.writeFileSync(`${artifact}.sha256`, `${entry.sha256}  ${path.basename(artifact)}\n`);
    }
} finally { fs.unlinkSync(privateFile); }
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
const classes = path.join(root, 'target/sdk-ci-host-classpath');
fs.mkdirSync(classes);
run('jar', ['--extract', '--file', path.resolve(hostJar), 'BOOT-INF/lib', 'BOOT-INF/classes'], classes);
run('java', ['-Dfile.encoding=UTF-8', '-cp', [path.join(classes, 'BOOT-INF/classes'),
    path.join(classes, 'BOOT-INF/lib/*'), path.resolve(hostJar)].join(path.delimiter),
    'scripts/ci/test/SdkCandidateProvenance.java', layout, keyId, publicSpki]);
// 只注入验收工程的现有仓库配置；夹具公钥不进入 SDK ZIP 或生产信任根。
fs.writeFileSync(path.join(output, 'ci-repository.yaml'), [
    'plugin-catalog.enabled: false',
    `plugin-catalog.repositories: ${JSON.stringify([{
        id: 'sdk-ci', 'manifest-url': 'https://sdk-ci.invalid/manifest.json',
        'trusted-keys': [{ 'key-id': keyId, 'public-key': publicSpki,
            publisher: 'SDK CI', 'trust-label': 'Ephemeral SDK candidate' }],
    }])}`,
    '',
].join('\n'));
freezeDevelopmentRuntime({ repoRoot: root, sourceSha, hostVersion }, layout, output);
