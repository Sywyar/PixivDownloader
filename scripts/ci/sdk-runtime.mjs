#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { createArchive, sha256 } from './sdk-release.mjs';
import { inspectSdkVersion } from './sdk-version.mjs';

// 只负责把共享发行组装器的实际输出固化为 SDK 配套关系，不维护第二份官方插件清单。
export function assembleDevelopmentRuntime(options) {
    const root = path.resolve(options.repoRoot);
    const identity = inspectSdkVersion(root);
    if (!/^[0-9a-f]{40}$/u.test(options.sourceSha)
            || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u.test(options.hostVersion)) {
        throw new Error('SDK runtime requires an exact source SHA and host version');
    }
    const output = path.join(root, 'build', 'sdk-development-runtime');
    execFileSync('pwsh', ['-NoProfile', '-File', path.join(root, 'scripts', 'package-java-distributions.ps1'),
        '-Version', options.hostVersion, '-PrebuiltJar', path.resolve(options.hostJar),
        '-PrebuiltPluginsDir', path.resolve(options.pluginsDirectory),
        '-SignatureToolJar', path.resolve(options.signatureTool), '-OutputDir', output,
    ], { cwd: root, stdio: 'inherit' });
    const layout = path.join(output, 'full-offline');
    const archive = path.join(output, `PixivDownload-${options.hostVersion}-full-offline.zip`);
    // 复用 SDK 的可重现 ZIP producer，恢复发布时不把文件复制时间写进附件身份。
    createArchive(layout, archive);
    const describe = file => ({ file: path.basename(file), size: fs.statSync(file).size, sha256: sha256(file) });
    const developmentRuntime = {
        hostVersion: options.hostVersion,
        hostSourceCommitSha: options.sourceSha,
        platforms: ['windows-x64', 'windows-arm64', 'linux-x64', 'linux-arm64', 'macos-arm64'],
        downloadUrl: `https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/${identity.releaseId}/${path.basename(archive)}`,
        archive: describe(archive),
        host: describe(path.join(layout, `PixivDownload-${options.hostVersion}.jar`)),
        pluginsManifest: describe(path.join(layout, 'plugins-manifest.json')),
    };
    const manifest = path.join(output, 'sdk-runtime.json');
    fs.writeFileSync(manifest, `${JSON.stringify({
        schemaVersion: 1, sdkVersion: identity.version, sourceCommitSha: options.sourceSha, developmentRuntime,
    }, null, 2)}\n`, 'utf8');
    return { manifest, archive };
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    try {
        const names = new Map([
            ['--repo-root', 'repoRoot'], ['--source-sha', 'sourceSha'], ['--host-version', 'hostVersion'],
            ['--host-jar', 'hostJar'], ['--plugins-directory', 'pluginsDirectory'], ['--signature-tool', 'signatureTool'],
        ]);
        const options = { repoRoot: '.' };
        const args = process.argv.slice(2);
        for (let index = 0; index < args.length; index += 2) {
            if (!names.has(args[index]) || !args[index + 1]) throw new Error(`invalid argument: ${args[index]}`);
            options[names.get(args[index])] = args[index + 1];
        }
        process.stdout.write(`${JSON.stringify(assembleDevelopmentRuntime(options))}\n`);
    } catch (failure) {
        process.stderr.write(`${failure.message}\n`);
        process.exitCode = 1;
    }
}
