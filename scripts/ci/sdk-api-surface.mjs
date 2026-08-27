#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

function command(tool, args) {
    return execFileSync(tool, args, {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
        maxBuffer: 64 * 1024 * 1024
    });
}

export function normalizeJavap(moduleName, output) {
    const entries = [];
    let type = '';
    let included = false;
    let declaration = '';
    for (const rawLine of output.split(/\r?\n/u)) {
        const line = rawLine.trim();
        if (!line || line.startsWith('Compiled from ')) {
            continue;
        }
        if (line === '}') {
            type = '';
            included = false;
            declaration = '';
            continue;
        }
        if (line.endsWith('{')) {
            type = line.slice(0, -1).trim();
            included = /^(public|protected)\s/u.test(type);
            if (included) {
                entries.push(`${moduleName}\tTYPE\t${type}`);
            }
            continue;
        }
        if (line.startsWith('descriptor:')) {
            if (included && declaration && declaration !== 'static {};') {
                entries.push(`${moduleName}\tMEMBER\t${type}\t${declaration}\t${line}`);
            }
            declaration = '';
            continue;
        }
        if (included && line.endsWith(';')) {
            declaration = line;
        }
    }
    return entries.sort((left, right) => left.localeCompare(right, 'en'));
}

function classNames(jarPath) {
    return command('jar', ['--list', '--file', jarPath])
            .split(/\r?\n/u)
            .filter((entry) => entry.endsWith('.class')
                    && !entry.startsWith('META-INF/versions/')
                    && !entry.endsWith('/module-info.class')
                    && !entry.endsWith('/package-info.class')
                    && entry !== 'module-info.class')
            .map((entry) => entry.slice(0, -6).replaceAll('/', '.'))
            .sort((left, right) => left.localeCompare(right, 'en'));
}

function moduleArtifact(name, directory) {
    const candidates = fs.readdirSync(directory, { withFileTypes: true })
            .filter((entry) => entry.isFile()
                    && entry.name.endsWith('.jar')
                    && !/-(sources|javadoc|tests)\.jar$/u.test(entry.name))
            .map((entry) => path.join(directory, entry.name));
    if (candidates.length !== 1) {
        throw new Error(`Expected exactly one main JAR for ${name} in ${directory}, found ${candidates.length}`);
    }
    return { name, path: candidates[0] };
}

export function collectApiSurface(artifacts) {
    const surface = [];
    for (const artifact of artifacts) {
        const classes = classNames(artifact.path);
        if (classes.length === 0) {
            throw new Error(`SDK artifact contains no classes: ${artifact.path}`);
        }
        const output = command('javap', ['-classpath', artifact.path, '-protected', '-s', '-constants', ...classes]);
        surface.push(...normalizeJavap(artifact.name, output));
    }
    return `${surface.sort((left, right) => left.localeCompare(right, 'en')).join('\n')}\n`;
}

function parseArguments(argv) {
    const artifacts = [];
    let output = '';
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        if (argument === '--artifact') {
            const value = argv[++index] ?? '';
            const separator = value.indexOf('=');
            if (separator < 1 || separator === value.length - 1) {
                throw new Error('--artifact requires name=path');
            }
            artifacts.push({ name: value.slice(0, separator), path: path.resolve(value.slice(separator + 1)) });
        } else if (argument === '--module') {
            const value = argv[++index] ?? '';
            const separator = value.indexOf('=');
            if (separator < 1 || separator === value.length - 1) {
                throw new Error('--module requires name=target-directory');
            }
            const name = value.slice(0, separator);
            artifacts.push(moduleArtifact(name, path.resolve(value.slice(separator + 1))));
        } else if (argument === '--output') {
            output = path.resolve(argv[++index] ?? '');
        } else {
            throw new Error(`Unknown argument: ${argument}`);
        }
    }
    if (artifacts.length === 0 || !output) {
        throw new Error('At least one --artifact/--module and --output are required');
    }
    for (const artifact of artifacts) {
        if (!fs.statSync(artifact.path).isFile()) {
            throw new Error(`SDK artifact is not a file: ${artifact.path}`);
        }
    }
    return { artifacts, output };
}

function main() {
    const options = parseArguments(process.argv.slice(2));
    fs.mkdirSync(path.dirname(options.output), { recursive: true });
    fs.writeFileSync(options.output, collectApiSurface(options.artifacts), 'utf8');
    process.stdout.write(`Wrote SDK API surface to ${options.output}\n`);
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    try {
        main();
    } catch (error) {
        process.stderr.write(`${error.message}\n`);
        process.exitCode = 1;
    }
}
