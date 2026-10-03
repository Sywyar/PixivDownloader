import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

function order(version) {
    const match = /-nightly\.[0-9]{8}\.([1-9][0-9]{0,8})\.([1-9][0-9]{0,8})$/u.exec(version);
    if (!match) throw new Error(`Invalid Nightly publication version: ${version}`);
    return [Number(match[1]), Number(match[2])];
}

export function assertPublicationOrder(candidate, publishedVersions) {
    const [run, attempt] = order(candidate);
    for (const version of publishedVersions) {
        const [previousRun, previousAttempt] = order(version);
        // 重跑日期会变化；顺序只由同一 Nightly workflow 的 run 和 attempt 决定。
        if (previousRun > run || (previousRun === run && previousAttempt > attempt)) {
            throw new Error(`Refusing stale Nightly ${candidate}; ${version} is already published`);
        }
    }
}

export function manifestVersions(manifest) {
    if (!Array.isArray(manifest.entries) || manifest.entries.length === 0) {
        throw new Error('Nightly plugin manifest has no entries');
    }
    return manifest.entries.map(entry => entry.version);
}

export function releaseVersions(release) {
    if (release.tag_name !== 'nightly' || !release.name?.startsWith('Nightly Build ')) {
        throw new Error('Unrecognized Nightly Release identity');
    }
    return [release.name.slice('Nightly Build '.length)];
}

function main([mode, candidate, target]) {
    order(candidate);
    if (mode === 'plugins') {
        if (!fs.existsSync(target)) return;
        const manifest = JSON.parse(fs.readFileSync(target, 'utf8'));
        assertPublicationOrder(candidate, manifestVersions(manifest));
    } else if (mode === 'release') {
        if (!/^[\w.-]+\/[\w.-]+$/u.test(target)) throw new Error('Invalid publication repository');
        let text;
        try {
            text = execFileSync('gh', ['api', `repos/${target}/releases/tags/nightly`], {
                encoding: 'utf8', timeout: 60_000, maxBuffer: 32 * 1024 * 1024,
                stdio: ['ignore', 'pipe', 'pipe'],
            });
        } catch (error) {
            if (error.status === 1 && /\(HTTP 404\)/u.test(String(error.stderr))) return;
            throw new Error('Cannot verify current Nightly Release', { cause: error });
        }
        assertPublicationOrder(candidate, releaseVersions(JSON.parse(text)));
    } else {
        throw new Error('Expected plugins or release publication mode');
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main(process.argv.slice(2));
}
