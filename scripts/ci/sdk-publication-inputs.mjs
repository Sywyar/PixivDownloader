import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { inspectSdkVersion, SDK_ARTIFACTS, SDK_GROUP_ID } from './sdk-version.mjs';
import { sha256 } from './sdk-release.mjs';

const MAX_FILES = 48000, MAX_BYTES = 2 * 1024 ** 3, MAX_FILE_BYTES = 256 * 1024 ** 2;
const artifactNames = SDK_ARTIFACTS.map(([name]) => name).join('|');
const allowed = new RegExp(`^(?:(?:${artifactNames})/target/[^/]+(?:\\.jar|\\.xml)|`
    + 'target/(?:sdk-javadocs/[^\\\\]+|sdk-runtime-inputs/(?:host\\.jar|plugins-manifest\\.json|plugins/[^/]+\\.jar)|sdk-tools\\.jar))$');

function plainFile(root, relative) {
    if (typeof relative !== 'string' || !allowed.test(relative) || relative.length > 1024 || relative.includes('\\')
            || relative.split('/').some(part => !part || part === '.' || part === '..' || part.includes(':'))) {
        throw new Error('Invalid SDK publication input path');
    }
    let current = path.resolve(root);
    for (const part of relative.split('/')) {
        current = path.join(current, part);
        if (fs.existsSync(current) && fs.lstatSync(current).isSymbolicLink()) {
            throw new Error('Linked SDK publication input');
        }
    }
    return current;
}

export function verifyInputs(directory, sourceSha) {
    const metadata = path.join(directory, 'inputs.json');
    if (fs.lstatSync(metadata).isSymbolicLink() || fs.statSync(metadata).size > 8 * 1024 ** 2) {
        throw new Error('Invalid SDK publication input manifest');
    }
    const manifest = JSON.parse(fs.readFileSync(metadata, 'utf8'));
    if (!/^[0-9a-f]{40}$/u.test(sourceSha) || manifest.schemaVersion !== 1
            || manifest.sourceSha !== sourceSha || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u.test(manifest.hostVersion ?? '')
            || !Array.isArray(manifest.files)
            || !manifest.files.length || manifest.files.length > MAX_FILES) {
        throw new Error('SDK publication input identity mismatch');
    }
    const seen = new Set();
    let bytes = 0;
    for (const entry of manifest.files) {
        const file = plainFile(directory, entry.file);
        const stat = fs.lstatSync(file);
        if (seen.has(entry.file.toLowerCase()) || !stat.isFile() || !Number.isSafeInteger(entry.size)
                || entry.size <= 0 || entry.size > MAX_FILE_BYTES || (bytes += entry.size) > MAX_BYTES
                || stat.size !== entry.size || !/^[0-9a-f]{64}$/u.test(entry.sha256)
                || sha256(file) !== entry.sha256) throw new Error('SDK publication input bytes mismatch');
        seen.add(entry.file.toLowerCase());
    }
    return manifest;
}

export function transferInputs(root, directory, sourceSha, restore = false) {
    if (restore) {
        const manifest = verifyInputs(directory, sourceSha);
        for (const entry of manifest.files) plainFile(root, entry.file);
        for (const entry of manifest.files) {
            const target = plainFile(root, entry.file);
            fs.mkdirSync(path.dirname(target), { recursive: true });
            fs.copyFileSync(path.join(directory, entry.file), target);
        }
        return manifest;
    }
    if (fs.existsSync(directory)) throw new Error('SDK publication input output must be new');
    const identity = inspectSdkVersion(root);
    const files = SDK_ARTIFACTS.flatMap(([name, packaging]) => [
        `${name}/target/flattened-pom.xml`,
        ...(packaging === 'jar' ? ['.jar', '-sources.jar', '-javadoc.jar']
            .map(suffix => `${name}/target/${name}-${identity.version}${suffix}`) : []),
    ]);
    const walk = relative => {
        for (const entry of fs.readdirSync(path.join(root, relative), { withFileTypes: true })) {
            const file = `${relative}/${entry.name}`;
            if (entry.isDirectory()) walk(file);
            else if (entry.isFile()) files.push(file);
            else throw new Error('Non-regular SDK publication input');
        }
    };
    walk('target/sdk-javadocs');
    walk('target/sdk-runtime-inputs');
    files.push('target/sdk-tools.jar');
    const runtime = JSON.parse(fs.readFileSync(path.join(root, 'build/sdk-development-runtime/sdk-runtime.json'), 'utf8'));
    if (runtime.sourceCommitSha !== sourceSha) throw new Error('Candidate runtime source mismatch');
    const manifest = { schemaVersion: 1, sourceSha, hostVersion: runtime.developmentRuntime.hostVersion,
        files: files.sort().map(file => {
        const source = plainFile(root, file);
        return { file, size: fs.statSync(source).size, sha256: sha256(source) };
    }) };
    for (const entry of manifest.files) {
        const target = plainFile(directory, entry.file);
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.copyFileSync(path.join(root, entry.file), target);
    }
    fs.writeFileSync(path.join(directory, 'inputs.json'), JSON.stringify(manifest) + '\n');
    return verifyInputs(directory, sourceSha);
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    const [mode, sourceSha] = process.argv.slice(2);
    if (!['stage', 'restore'].includes(mode)) throw new Error('Expected stage or restore');
    transferInputs(process.cwd(), path.resolve('target/sdk-publication-inputs'), sourceSha, mode === 'restore');
    if (mode === 'restore') {
        const identity = inspectSdkVersion(process.cwd());
        for (const [name, packaging] of SDK_ARTIFACTS) {
            const destination = path.resolve('target/sdk-staging', SDK_GROUP_ID.replaceAll('.', '/'), name, identity.version);
            fs.mkdirSync(destination, { recursive: true });
            for (const extension of packaging === 'jar' ? ['pom', 'jar'] : ['pom']) {
                const artifact = `${name}-${identity.version}.${extension}`;
                const source = `${name}/target/${extension === 'pom' ? 'flattened-pom.xml' : artifact}`;
                const target = path.join(destination, artifact);
                fs.copyFileSync(source, target);
                fs.writeFileSync(`${target}.sha1`, `${createHash('sha1').update(fs.readFileSync(target)).digest('hex')}\n`, 'utf8');
            }
        }
    }
}
