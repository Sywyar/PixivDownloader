import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { queryModel, runBuild, buildEnvironment } from './build-model.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
function fileDigest(file, maximum = 192 * 1024 * 1024) {
    if (!fs.lstatSync(file).isFile()) throw new Error('CANDIDATE_FILE_INVALID');
    const fd = fs.openSync(file, 'r');
    try {
        const digest = crypto.createHash('sha256');
        const buffer = Buffer.alloc(8192); let total = 0;
        for (let count; (count = fs.readSync(fd, buffer));) {
            total += count; if (total > maximum) throw new Error('CANDIDATE_SIZE_EXCEEDED');
            digest.update(buffer.subarray(0, count));
        }
        return digest.digest('hex');
    } finally { fs.closeSync(fd); }
}
const git = (...args) => execFileSync('git', ['--no-optional-locks', '-C', root, ...args],
    { encoding: 'utf8', timeout: 60_000, maxBuffer: 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] }).trim();

export function buildCommand(project, profile, offline = false, sbtLauncher) {
    const win = process.platform === 'win32';
    if (profile === 'maven-java17-v1') {
        const wrapper = win ? 'mvnw.cmd' : 'mvnw';
        return [path.join(fs.existsSync(path.join(project, wrapper)) ? project : root, wrapper), ['-B', '-ntp', ...(offline ? ['-o'] : []), 'clean', 'verify']];
    }
    if (profile === 'gradle-java17-v1') return [path.join(project, win ? 'gradlew.bat' : 'gradlew'), ['--no-daemon', '--console=plain', ...(offline ? ['--offline'] : []), 'clean', 'build']];
    if (profile === 'sbt-java17-v1') return ['java', ['-jar', sbtLauncher, ...(offline ? ['set offline := true'] : []), 'clean', 'test', 'package']];
    throw new Error('BUILD_PROFILE_UNSUPPORTED');
}

export async function candidate(command, selection, env = process.env) {
    const workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-candidate-'));
    const invoke = input => {
        const file = path.join(workspace, 'input.json');
        fs.writeFileSync(file, JSON.stringify(input), 'utf8');
        return JSON.parse(execFileSync('java', ['-Dfile.encoding=UTF-8', '-Xlog:all=off:stdout', '-Xlog:all=warning:stderr', '-jar', path.join(root, 'tools/sdk-tools.jar'), 'candidate', file],
            { env: buildEnvironment(), encoding: 'utf8', timeout: 120_000, maxBuffer: 32 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] }));
    };
    try {
        const projects = invoke({ command: 'projects', gitRoot: root });
        if (command === 'matrix') {
            const config = path.join(root, 'tools/candidate-projects.json');
            let configured;
            if (fs.existsSync(config)) {
                if (!fs.lstatSync(config).isFile() || fs.statSync(config).size > 64 * 1024) throw new Error('CANDIDATE_CONFIG_INVALID');
                const bytes = fs.readFileSync(config);
                if (bytes.length > 64 * 1024) throw new Error('CANDIDATE_CONFIG_INVALID');
                configured = JSON.parse(bytes.toString('utf8'));
                if (!Array.isArray(configured) || configured.some(item => !item || typeof item !== 'object' || Array.isArray(item)
                    || Object.keys(item).some(key => !['projectDir', 'profileId', 'artifactPath'].includes(key))
                    || item.artifactPath !== undefined && (typeof item.artifactPath !== 'string' || !item.artifactPath)
                    || !projects.some(project => project.projectDir === item.projectDir && project.profiles.includes(item.profileId)))) throw new Error('CANDIDATE_CONFIG_INVALID');
            } else {
                const selected = projects.some(project => project.projectDir === '.') ? projects.filter(project => project.projectDir === '.') : projects;
                if (selected.length !== 1) throw new Error('PROJECT_SELECTION_REQUIRED');
                if (selected.some(project => project.profiles.length !== 1)) throw new Error('BUILD_PROFILE_SELECTION_REQUIRED');
                configured = selected.map(project => ({ projectDir: project.projectDir, profileId: project.profiles[0] }));
            }
            const include = configured.map(item => ({ ...item, key: hash(Buffer.from(item.projectDir)).slice(0, 16) }));
            if (!include.length || include.length > 32) throw new Error('PROJECT_COUNT_INVALID');
            if (new Set(include.map(item => item.projectDir)).size !== include.length) throw new Error('PROJECT_SELECTION_DUPLICATED');
            return { include };
        }
        if (command !== 'build' || !projects.some(project => project.projectDir === selection.projectDir && project.profiles.includes(selection.profileId))) {
            throw new Error('BUILD_PROFILE_UNSUPPORTED');
        }
        const commit = git('rev-parse', 'HEAD');
        if (commit !== env.GITHUB_SHA || git('status', '--porcelain=v1')) throw new Error('SOURCE_COMMIT_REQUIRED');
        const project = invoke({ command: 'path', root, path: selection.projectDir, allowRoot: true, mustExist: true }).path;
        let sbtLauncher;
        if (selection.profileId === 'sbt-java17-v1') {
            const properties = fs.readFileSync(path.join(project, 'project/build.properties'), 'utf8');
            const version = /^sbt\.version=([0-9]+\.[0-9]+\.[0-9]+)$/mu.exec(properties)?.[1];
            const expected = /^sbt\.launcher\.sha256=([a-f0-9]{64})$/mu.exec(properties)?.[1];
            if (!version || !expected) throw new Error('SBT_LAUNCHER_LOCK_REQUIRED');
            sbtLauncher = path.join(workspace, 'sbt-launch.jar');
            execFileSync('curl', ['--fail', '--silent', '--show-error', '--proto', '=https', '--max-time', '120', '--max-filesize', String(8 * 1024 * 1024),
                '--output', sbtLauncher, `https://repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/${version}/sbt-launch-${version}.jar`],
                { timeout: 125_000, maxBuffer: 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
            if (fileDigest(sbtLauncher, 8 * 1024 * 1024) !== expected) throw new Error('SBT_LAUNCHER_CHANGED');
        }
        process.umask(0o022);
        const [executable, args] = buildCommand(project, selection.profileId, false, sbtLauncher);
        runBuild(executable, args, project);
        const sdk = { workspace, invoke };
        const model = queryModel(sdk, { project, wrapperRoot: root }, selection.profileId, (executable, args, cwd) => sbtLauncher
            ? runBuild('java', ['-jar', sbtLauncher, ...args], cwd) : runBuild(executable, args, cwd));
        if (!selection.artifactPath && model.artifacts.length !== 1) throw new Error('ARTIFACT_SELECTION_REQUIRED');
        const artifactPath = selection.artifactPath ?? model.artifacts[0];
        if (!model.artifacts.includes(artifactPath)) throw new Error('BUILD_OUTPUT_MISMATCH');
        const file = path.join(project, artifactPath);
        const before = fileDigest(file);
        const [offlineExecutable, offlineArgs] = buildCommand(project, selection.profileId, true, sbtLauncher);
        runBuild(offlineExecutable, offlineArgs, project);
        if (before !== fileDigest(file)) throw new Error('BUILD_NOT_REPRODUCIBLE');
        if (git('rev-parse', 'HEAD') !== commit || git('status', '--porcelain=v1')) throw new Error('SOURCE_CHANGED');
        const destination = path.join(root, 'target/community-candidate');
        fs.mkdirSync(destination, { recursive: true });
        return invoke({ command: 'create', gitRoot: root, buildProfile: { id: selection.profileId, projectDir: selection.projectDir, artifactPath },
            outputs: model.artifacts, modelVersion: model.version, repositoryId: env.GITHUB_REPOSITORY_ID,
            repository: env.GITHUB_REPOSITORY, sourceCommit: commit, runId: env.GITHUB_RUN_ID,
            runAttempt: Number(env.GITHUB_RUN_ATTEMPT), destination });
    } finally { fs.rmSync(workspace, { recursive: true }); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const result = await candidate(process.argv[2], process.env.CANDIDATE_SELECTION ? JSON.parse(process.env.CANDIDATE_SELECTION) : undefined);
    if (process.argv[2] === 'matrix' && process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, `matrix=${JSON.stringify(result)}\n`, 'utf8');
    process.stdout.write(JSON.stringify(result) + '\n');
}
