import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const API_BYTES = 32 * 1024 * 1024;
export const buildEnvironment = () => Object.fromEntries(Object.entries(process.env).filter(([key]) => !/TOKEN|SECRET|PRIVATE_KEY|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS/iu.test(key)));
export function runBuild(executable, args, cwd) {
    const env = buildEnvironment();
    const options = { cwd, env, encoding: 'utf8', windowsHide: true, timeout: 15 * 60_000, maxBuffer: API_BYTES,
        stdio: ['ignore', 'pipe', 'pipe'] };
    if (process.platform !== 'win32' || !/\.(cmd|bat)$/iu.test(executable)) return execFileSync(executable, args, options);
    // PowerShell 读取数据参数；路径和模型命令均不拼进 shell 源码。
    env.PIXIV_BUILD_ARGUMENTS = Buffer.from(JSON.stringify({ executable, args }), 'utf8').toString('base64');
    const script = "$ErrorActionPreference='Stop'; $OutputEncoding=[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); "
        + '$v=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:PIXIV_BUILD_ARGUMENTS))|ConvertFrom-Json; '
        + '$buildArgs=@($v.args); & $v.executable @buildArgs; exit $LASTEXITCODE';
    return execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand',
        Buffer.from(script, 'utf16le').toString('base64')], options);
}

const scalaString = text => `new String(Array[Byte](${[...Buffer.from(text, 'utf8')].join(',')}),java.nio.charset.StandardCharsets.UTF_8)`;

// SDK 候选构建和社区容器重建共用模型命令及解析。
export function modelArguments(profileId, file, gradleScript) {
    if (profileId === 'maven-java17-v1') {
        return ['-N', 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom', `-Doutput=${file}`];
    } else if (profileId === 'gradle-java17-v1') {
        return ['--init-script', gradleScript, 'pixivCommunityModel'];
    } else if (profileId === 'sbt-java17-v1') {
        const separator = scalaString('\n');
        const command = 'set commands += Command.command(' + scalaString('pixivCommunityModel') + ') { s => '
            + 'val e=Project.extract(s); val lines=Seq(e.get(version),e.get(Compile / packageBin / artifactPath).getAbsolutePath,e.get(Compile / classDirectory).getAbsolutePath) ++ '
            + 'e.get(libraryDependencies).map(d => Seq(d.organization,d.name,d.revision,d.configurations.getOrElse(' + scalaString('compile')
            + ')).mkString(' + scalaString('\t') + ')); println(' + scalaString('PIXIV_COMMUNITY_MODEL=')
            + '+java.util.Base64.getEncoder.encodeToString(lines.mkString(' + separator + ').getBytes(java.nio.charset.StandardCharsets.UTF_8))); s }';
        return [command, 'pixivCommunityModel'];
    } else throw new Error('BUILD_PROFILE_UNSUPPORTED');
}

export function readModel(sdk, profileId, output, file) {
    if (profileId === 'maven-java17-v1') return sdk.invoke({ command: 'maven-model', file });
    const lines = output.split(/\r?\n/u).filter(line => line.startsWith('PIXIV_COMMUNITY_MODEL='));
    if (lines.length !== 1) throw new Error('BUILD_MODEL_AMBIGUOUS');
    const value = lines[0].slice('PIXIV_COMMUNITY_MODEL='.length);
    if (profileId === 'gradle-java17-v1') return JSON.parse(value);
    if (profileId !== 'sbt-java17-v1') throw new Error('BUILD_PROFILE_UNSUPPORTED');
    const [version, artifact, classes, ...dependencies] = Buffer.from(value, 'base64').toString('utf8').split('\n');
    return { version, artifacts: [artifact], classDirectories: [classes], dependencies: dependencies.map(line => {
        const [group, name, version, scope] = line.split('\t'); return { group, name, version, scope };
    }) };
}

export function queryModel(sdk, selection, profileId, run = runBuild) {
    const project = selection.project;
    const commands = {
        'maven-java17-v1': ['mvnw', 'mvnw.cmd', 'mvn', 'mvn.cmd'],
        'gradle-java17-v1': ['gradlew', 'gradlew.bat'],
        'sbt-java17-v1': ['sbtw', 'sbtw.cmd', 'sbt', 'sbt.bat'],
    }[profileId];
    if (!commands) throw new Error('BUILD_PROFILE_UNSUPPORTED');
    const platform = process.platform === 'win32' ? 1 : 0;
    let executable = path.join(project, commands[platform]);
    if (!fs.existsSync(executable) && profileId === 'maven-java17-v1' && selection.wrapperRoot) executable = path.join(selection.wrapperRoot, commands[platform]);
    if (!fs.existsSync(executable) && commands[platform + 2]) executable = commands[platform + 2];
    const file = path.join(sdk.workspace, 'effective-pom.xml');
    const options = profileId === 'maven-java17-v1' ? ['-B', '-ntp']
        : profileId === 'gradle-java17-v1' ? ['--no-daemon', '--console=plain', '--no-configuration-cache'] : [];
    const args = [...options, ...modelArguments(profileId, file, path.join(root, 'tools/community-model.gradle'))];
    const model = readModel(sdk, profileId, run(executable, args, project), file);
    if (typeof model.version !== 'string' || !model.version || !Array.isArray(model.artifacts) || !model.artifacts.length) {
        throw new Error('BUILD_MODEL_INVALID');
    }
    const artifacts = [...new Set(model.artifacts.map(file => path.relative(project, file).split(path.sep).join('/')))];
    for (const artifact of artifacts) sdk.invoke({ command: 'path', root: project, path: artifact, mustExist: true });
    return { ...model, artifacts, command: { executable, args } };
}
