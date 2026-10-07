import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { compareVersions, consumerPomSurface } from './sdk-contract.mjs';
import { parseSdkVersion } from './sdk-version.mjs';

const compare = (left, right) => compareVersions(parseSdkVersion(left), parseSdkVersion(right));

export function sdkBaselineVersion(requirement, currentVersion) {
    const required = requirement?.trim() ?? '';
    let version;
    if (required.startsWith('=') || required.includes('-')) {
        version = required.startsWith('=') ? required.slice(1) : required;
        parseSdkVersion(version);
    } else {
        const match = /^(?:>=\s*)?[vV]?(\d+)\.(\d+)(?:\.\d+)?$/u.exec(required);
        if (!match) throw new Error(`Declare a valid minimum SDK major.minor or an exact SDK identity: ${required}`);
        version = `${match[1]}.${match[2]}.0`;
        parseSdkVersion(version);
    }
    return version === currentVersion ? 'candidate' : version;
}

export function verifyPluginVersion({ id, version, previousVersion, publishedVersion, changed }) {
    parseSdkVersion(version);
    if (previousVersion && compare(version, previousVersion) < 0) {
        throw new Error(`${id}: plugin.version cannot decrease (${previousVersion} -> ${version})`);
    }
    if (publishedVersion && (compare(version, publishedVersion) < 0
            || (changed.length > 0 && compare(version, publishedVersion) === 0))) {
        throw new Error(`${id}: delivered content changed; increase plugin.version above published ${publishedVersion}: ${changed.join(', ')}`);
    }
}

export function publishedVersions(releases, plugins) {
    const result = new Map();
    for (const { Id: id } of plugins) {
        for (const release of releases) {
            const prefix = `${id}-v`;
            if (release.draft || !release.tag_name?.startsWith(prefix)) continue;
            const version = release.tag_name.slice(prefix.length);
            if (/^\d+\.\d+\.\d+-nightly\.\d{8}\.[1-9]\d*\.[1-9]\d*$/u.test(version)) continue;
            parseSdkVersion(version);
            if (!result.has(id) || compare(version, result.get(id)) > 0) result.set(id, version);
        }
    }
    return result;
}

function properties(text) {
    return Object.fromEntries(text.split(/\r?\n/u).filter(line => /^\s*[^#!\s][^=]*=/u.test(line))
        .map(line => { const i = line.indexOf('='); return [line.slice(0, i).trim(), line.slice(i + 1).trim()]; }));
}

function buildPlugins(pom) {
    const build = pom.replace(/<!--[\s\S]*?-->/gu, '').match(/<build>([\s\S]*?)<\/build>/u)?.[1] ?? '';
    return [...build.matchAll(/<plugin>([\s\S]*?)<\/plugin>/gu)].map(match => match[0])
        .filter(plugin => !/<artifactId>(?:maven-(?:clean|surefire|failsafe|source|javadoc|install|deploy|site)-plugin|flatten-maven-plugin)<\/artifactId>/u.test(plugin))
        .join('\n');
}

function referencedInputs(pom, module) {
    return [...pom.matchAll(/\$\{(maven.multiModuleProjectDirectory|project.basedir|project.parent.basedir)\}\/([^<"'\r\n]+)/gu)]
        .flatMap(([, base, suffix]) => {
            if (suffix.includes('${')) return [];
            const prefix = base === 'maven.multiModuleProjectDirectory' ? ''
                : base === 'project.parent.basedir' ? path.posix.dirname(module) : module;
            return [path.posix.normalize(path.posix.join(prefix, suffix.trim().split(/[?*]/u)[0]))];
        });
}

export function checkPluginVersions(root, baseRef, plugins, releases) {
    const git = (...args) => execFileSync('git', ['-C', root, ...args],
        { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 16 * 1024 * 1024 });
    if (!/^[a-f0-9]{40}$/u.test(baseRef)) throw new Error('The plugin version baseline must be a full commit SHA');
    git('merge-base', '--is-ancestor', baseRef, 'HEAD');
    const files = git('diff', '--name-only', '-z', baseRef, '--').split('\0').filter(Boolean);
    const tracked = new Set(git('ls-tree', '-r', '--name-only', baseRef).trim().split('\n'));
    const old = file => tracked.has(file) ? git('show', `${baseRef}:${file}`) : '';
    const current = file => fs.existsSync(path.join(root, file)) ? fs.readFileSync(path.join(root, file), 'utf8') : '';
    const published = publishedVersions(releases, plugins);
    const curation = text => text ? JSON.parse(text) : {};
    const currentCuration = curation(current('scripts/market-curation.json'));
    const oldCuration = curation(old('scripts/market-curation.json'));
    const rootPom = current('pom.xml');
    const oldRootPom = old('pom.xml');
    const rootProperties = text => Object.fromEntries([...(text.match(/<properties>([\s\S]*?)<\/properties>/u)?.[1] ?? '')
        .matchAll(/<([\w.-]+)>\s*([^<]*?)\s*<\/\1>/gu)].map(match => [match[1], match[2]]));
    const currentProperties = rootProperties(rootPom);
    const previousProperties = rootProperties(oldRootPom);
    const rootBuild = buildPlugins(rootPom);
    const oldRootBuild = buildPlugins(oldRootPom);
    const report = [];
    for (const plugin of plugins) {
        const descriptorPath = `${plugin.Module}/src/main/resources/plugin.properties`;
        const descriptor = properties(current(descriptorPath));
        if (descriptor['plugin.id'] !== plugin.Id) throw new Error(`Invalid plugin identity: ${descriptorPath}`);
        const modules = new Set();
        const pomInputs = [];
        const deliveryInputs = new Set([...referencedInputs(rootBuild, ''), ...referencedInputs(oldRootBuild, '')]);
        const privateCoordinates = new Set();
        const visit = module => {
            if (modules.has(module)) return;
            modules.add(module);
            for (const pom of [current(`${module}/pom.xml`), old(`${module}/pom.xml`)]) {
                if (!pom) continue;
                pomInputs.push(pom);
                referencedInputs(pom, module).forEach(file => deliveryInputs.add(file));
                for (const line of consumerPomSurface(pom, '').split('\n')) {
                    const [kind, group, artifact, , , , scope] = line.split('\t');
                    if (kind === 'DEPENDENCY' && ['compile', 'runtime'].includes(scope)) privateCoordinates.add(`${group}:${artifact}`);
                    if (kind === 'DEPENDENCY' && group === 'top.sywyar.lovepopup'
                            && ['compile', 'runtime'].includes(scope) && current(`${artifact}/pom.xml`)) visit(artifact);
                }
            }
        };
        visit(plugin.Module);
        const changed = files.filter(file => [...modules].some(module => file.startsWith(`${module}/src/main/`)
                || file === `${module}/pom.xml` || (file.startsWith(`${module}/`) && /\.(?:gradle(?:\.kts)?|pro|rules)$/u.test(file)))
            || (plugin.DeliveryPaths ?? []).some(prefix => file.startsWith(prefix))
            || [...deliveryInputs].some(input => file === input || file.startsWith(input.replace(/\/$/u, '') + '/')));
        const publication = entry => ({ documentationSources: entry?.documentationSources ?? null,
            icon: entry?.icon ?? null, screenshots: entry?.screenshots ?? null, defaultLocale: entry?.defaultLocale ?? null });
        const documents = [publication(currentCuration[plugin.Id]), publication(oldCuration[plugin.Id])];
        if (JSON.stringify(documents[0]) !== JSON.stringify(documents[1])) changed.push('scripts/market-curation.json');
        const documentPaths = JSON.stringify(documents);
        changed.push(...files.filter(file => documentPaths.includes(JSON.stringify(file)) && !changed.includes(file)));
        const managed = pom => pom ? consumerPomSurface(pom, '').split('\n').filter(line => {
            const [kind, group, artifact] = line.split('\t');
            return kind === 'MANAGED_DEPENDENCY' && privateCoordinates.has(`${group}:${artifact}`);
        }).join('\n') : '';
        const usedProperties = new Set([...([...pomInputs, rootBuild, oldRootBuild, managed(rootPom), managed(oldRootPom)].join('\n'))
            .matchAll(/\$\{([^}]+)\}/gu)].map(match => match[1]));
        for (const key of usedProperties) {
            for (const value of [currentProperties[key], previousProperties[key]]) {
                for (const match of (value ?? '').matchAll(/\$\{([^}]+)\}/gu)) usedProperties.add(match[1]);
            }
        }
        if (rootBuild !== oldRootBuild || managed(rootPom) !== managed(oldRootPom) || [...usedProperties].some(key =>
                key !== 'pixivdownload.sdk.version' && currentProperties[key] !== previousProperties[key])) changed.push('pom.xml');
        const previousVersion = properties(old(descriptorPath))['plugin.version'];
        verifyPluginVersion({ id: plugin.Id, version: descriptor['plugin.version'], previousVersion,
            publishedVersion: published.get(plugin.Id), changed });
        report.push({ pluginId: plugin.Id, version: descriptor['plugin.version'],
            publishedVersion: published.get(plugin.Id) ?? null, changed });
    }
    return report;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        if (process.argv[2] === '--sdk-baseline') {
            console.log(sdkBaselineVersion(process.argv[3], process.argv[4]));
        } else {
            const [root, baseRef, pluginsFile, releasesFile] = process.argv.slice(2);
            const releases = releasesFile ? JSON.parse(fs.readFileSync(releasesFile, 'utf8'))
                : JSON.parse(execFileSync('gh', ['api', '--paginate', '--slurp',
                    'repos/Sywyar/PixivDownloader-plugins/releases?per_page=100'],
                    { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 })).flat();
            console.log(JSON.stringify(checkPluginVersions(root, baseRef,
                JSON.parse(fs.readFileSync(pluginsFile, 'utf8')), releases), null, 2));
        }
    } catch (error) { console.error(error.message); process.exitCode = 1; }
}
