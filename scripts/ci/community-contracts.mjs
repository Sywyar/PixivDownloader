import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { inspectSdkVersion } from './sdk-version.mjs';

const MANIFEST = 'bundle-manifest.json';
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

function files(directory, prefix = '') {
    if (fs.lstatSync(directory).isSymbolicLink()) throw new Error('COMMUNITY_RESOURCE_TYPE');
    return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
        const relative = `${prefix}${entry.name}`;
        if (entry.isSymbolicLink() || !entry.isDirectory() && !entry.isFile()) throw new Error('COMMUNITY_RESOURCE_TYPE');
        return entry.isDirectory() ? files(path.join(directory, entry.name), `${relative}/`) : [relative];
    }).sort();
}

function describe(directory, name) {
    const bytes = fs.readFileSync(path.join(directory, name));
    return { path: name, size: bytes.length, sha256: hash(bytes) };
}

/** 从实际固定配置提取工具身份；发行 ZIP 再绑定包含全部依赖的工具 JAR 摘要。 */
export function describeCommunityBundle(repoRoot) {
    const directory = path.join(repoRoot, 'contracts/community/v1');
    const read = name => fs.readFileSync(path.join(repoRoot, name), 'utf8');
    const property = (text, name) => text.match(new RegExp(`^${name.replaceAll('.', '\\.') }=(.+)$`, 'mu'))?.[1].trim();
    const maven = read('.mvn/wrapper/maven-wrapper.properties');
    const gradle = read('pixivdownload-plugin-gui-compose/gradle/wrapper/gradle-wrapper.properties');
    const sbt = read('plugin-templates/sdk-package/examples/sbt-plugin/project/build.properties');
    const toolsPom = read('pixivdownload-sdk-tools/pom.xml');
    const dependency = id => toolsPom.match(new RegExp(`<artifactId>${id}</artifactId>\\s*<version>([^<]+)</version>`, 'u'))?.[1];
    const toolchain = {
        sdk: inspectSdkVersion(repoRoot).version,
        java: read('pom.xml').match(/<java.version>([^<]+)<\/java.version>/u)?.[1],
        maven: { version: maven.match(/apache-maven\/([^/]+)\//u)?.[1], sha256: property(maven, 'distributionSha256Sum') },
        gradle: { version: gradle.match(/gradle-([0-9.]+)-bin/u)?.[1], sha256: property(gradle, 'distributionSha256Sum') },
        sbt: { version: property(sbt, 'sbt.version'), sha256: property(sbt, 'sbt.launcher.sha256') },
        jsonSchemaValidator: dependency('json-schema-validator'),
        jsonCanonicalization: dependency('java-json-canonicalization'),
        webpDecoder: dependency('imageio-webp'),
    };
    if (Object.values(toolchain).some(value => value === undefined)
            || ['maven', 'gradle', 'sbt'].some(tool => !toolchain[tool].version || !/^[a-f0-9]{64}$/u.test(toolchain[tool].sha256))) {
        throw new Error('COMMUNITY_TOOLCHAIN_METADATA');
    }
    return { schemaVersion: 1, contractVersion: 1, toolchain,
        files: files(directory).filter(name => name !== MANIFEST).map(name => describe(directory, name)) };
}

export function checkCommunitySource(repoRoot) {
    const directory = path.join(repoRoot, 'contracts/community/v1');
    const bytes = fs.readFileSync(path.join(directory, MANIFEST));
    if (!isDeepStrictEqual(JSON.parse(bytes.toString('utf8')), describeCommunityBundle(repoRoot))) {
        throw new Error('COMMUNITY_BUNDLE_STALE');
    }
    return hash(bytes);
}

/** manifest 摘要来自已固定的发行物；校验器不访问网络或寻找更新版本。 */
export function verifyCommunityBundle(directory, expectedManifestSha256) {
    const bytes = fs.readFileSync(path.join(directory, MANIFEST));
    if (hash(bytes) !== expectedManifestSha256) throw new Error('COMMUNITY_MANIFEST_HASH');
    const manifest = JSON.parse(bytes.toString('utf8'));
    const expectedFiles = [MANIFEST, ...manifest.files.map(item => item.path)].sort();
    if (!isDeepStrictEqual(files(directory), expectedFiles)) throw new Error('COMMUNITY_RESOURCE_SET');
    for (const expected of manifest.files) {
        if (!isDeepStrictEqual(describe(directory, expected.path), expected)) throw new Error('COMMUNITY_RESOURCE_HASH');
    }
    return manifest;
}

/** 必须在 SDK 工程初始 Git 提交之前调用，保证分发资源和工具字节均进入基线。 */
export function stageCommunityBundle(repoRoot, workspace, toolsJar, sourceCommit) {
    const manifestSha256 = checkCommunitySource(repoRoot);
    const destination = path.join(workspace, 'contracts/community/v1');
    fs.cpSync(path.join(repoRoot, 'contracts/community/v1'), destination, { recursive: true, errorOnExist: true });
    verifyCommunityBundle(destination, manifestSha256);
    const metadata = { schemaVersion: 1, sourceCommit, sdkVersion: inspectSdkVersion(repoRoot).version,
        contractVersion: 1, manifestSha256, tool: describe(path.dirname(toolsJar), path.basename(toolsJar)) };
    metadata.tool.path = 'tools/sdk-tools.jar';
    fs.writeFileSync(path.join(workspace, 'tools/community-contract.json'), `${JSON.stringify(metadata, null, 2)}\n`, 'utf8');
    return metadata;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const root = fileURLToPath(new URL('../../', import.meta.url));
    if (process.argv.length === 3 && process.argv[2] === '--write') {
        fs.writeFileSync(path.join(root, 'contracts/community/v1', MANIFEST),
                `${JSON.stringify(describeCommunityBundle(root), null, 2)}\n`, 'utf8');
    } else if (process.argv.length !== 2) throw new Error('COMMUNITY_ARGUMENTS');
    process.stdout.write(`${checkCommunitySource(root)}\n`);
}
