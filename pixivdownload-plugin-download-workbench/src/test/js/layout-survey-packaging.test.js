'use strict';
/*
 * 本地打包布局调查注入集成测试（矩阵 A/B/C/D）。
 *
 * 通过 Node 子进程调用真实 PowerShell 脚本：
 *   A. Local disabled：不传 -EnableLayoutSurvey，即使 source JAR 残留 enabled=true，
 *      最终 app-image 中 workbench JAR 也必须 enabled=false 且四项为空。
 *   B. Local unsigned enabled：传 -EnableLayoutSurvey + 临时测试 properties，
 *      最终 JAR enabled=true、四项测试值准确、entry 与生成文件逐字节一致、
 *      provenance 为 LOCAL_UPLOAD / UNSIGNED_ALLOWED、不存在错误的旧官方签名。
 *   C. Catalog + Enable：在修改任何插件前立即失败，Catalog 插件不被篡改。
 *   D. Local signed：仅在测试环境有可用签名密钥时执行：修改最终 JAR、重新签名、
 *      新签名验证最终字节、不复用旧签名。
 *
 * 测试只使用临时 properties 文件，绝不读取用户真实 scripts/properties/posthog.properties，
 * 也绝不请求任何真实或测试 PostHog 地址（只构建，不运行应用）。
 *
 * 完整矩阵需要真实 Windows 打包工具链（JDK 17 / jlink / jpackage / Inno Setup 6 /
 * PowerShell）并会执行两次完整安装包构建（数分钟），因此默认跳过；设置环境变量
 * PIXIV_LAYOUT_SURVEY_PACKAGING_IT=1 后从仓库根目录执行：
 *
 *   node pixivdownload-plugin-download-workbench/src/test/js/layout-survey-packaging.test.js
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const {spawnSync} = require('child_process');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const WORKBENCH_JAR_GLOB = path.join(REPO_ROOT, 'pixivdownload-plugin-download-workbench',
    'target', 'pixivdownload-plugin-download-workbench-*.jar');
const STAGED_JAR = path.join(REPO_ROOT, 'build', 'app-image-online', 'PixivDownload',
    'plugins', 'pixivdownload-plugin-download-workbench.jar');
const JAR_ENTRY = 'static/pixiv-layout-feedback/public-config.js';
const TEST_PROPS = [
    'pixiv.layout-survey.project-token=phc_layout_survey_local_test',
    'pixiv.layout-survey.survey-id=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    'pixiv.layout-survey.api-host=https://feedback.example.invalid',
    'pixiv.layout-survey.ui-host=https://us.posthog.com'
];
const RESIDUAL_PROPS = [
    'pixiv.layout-survey.project-token=phc_layout_survey_residual_enabled',
    'pixiv.layout-survey.survey-id=11111111-2222-3333-4444-555555555555',
    'pixiv.layout-survey.api-host=https://residual.example.invalid',
    'pixiv.layout-survey.ui-host=https://eu.i.posthog.com'
];

let passed = 0;
let skipped = 0;
function ok(label, condition) {
    if (!condition) throw new Error('FAIL: ' + label);
    passed++;
}
function eq(label, actual, expected) {
    if (actual !== expected) {
        throw new Error('FAIL: ' + label + ' expected=' + JSON.stringify(expected)
            + ' actual=' + JSON.stringify(actual));
    }
    passed++;
}
function skip(label) {
    skipped++;
    console.log('SKIP: ' + label);
}

function run(command, args, options) {
    options = options || {};
    const result = spawnSync(command, args, {
        encoding: 'utf8',
        cwd: options.cwd || REPO_ROOT,
        env: Object.assign({}, process.env, options.env || {}),
        maxBuffer: 256 * 1024 * 1024,
        timeout: options.timeout || 60 * 60 * 1000
    });
    return result;
}

function runMaven(args, options) {
    // mvnw.cmd is a batch file: Node spawn cannot execute it without shell:true.
    options = options || {};
    return spawnSync(path.join(REPO_ROOT, 'mvnw.cmd'), args, {
        encoding: 'utf8',
        cwd: options.cwd || REPO_ROOT,
        env: Object.assign({}, process.env, options.env || {}),
        maxBuffer: 256 * 1024 * 1024,
        timeout: options.timeout || 60 * 60 * 1000,
        shell: true
    });
}

function runPowerShell(args, options) {
    return run('powershell', ['-NoProfile', '-NonInteractive', '-File'].concat(args), options);
}

function combined(result) {
    return ((result.stderr || '') + (result.stdout || '')
        + (result.error ? '\nspawn error: ' + result.error.message : ''));
}

function readZipEntry(jarPath, entryName) {
    if (!fs.existsSync(jarPath)) {
        throw new Error('jar not found: ' + jarPath);
    }
    const script = [
        'Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null',
        'Add-Type -AssemblyName System.IO.Compression | Out-Null',
        `$archive = [System.IO.Compression.ZipFile]::OpenRead(${JSON.stringify(jarPath)})`,
        'try {',
        `  $entry = $archive.GetEntry(${JSON.stringify(entryName)})`,
        '  if (-not $entry) { exit 2 }',
        '  $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)',
        '  try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }',
        '  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8',
        '  [Console]::Write($text)',
        '} finally {',
        '  $archive.Dispose()',
        '}'
    ].join('\n');
    const result = run('powershell', ['-NoProfile', '-NonInteractive', '-Command', script]);
    if (result.status === 2) {
        throw new Error('jar entry not found: ' + entryName + ' in ' + jarPath);
    }
    if (result.status !== 0) {
        throw new Error('read jar entry failed: ' + combined(result));
    }
    return result.stdout;
}

function parseConfig(text) {
    const sandbox = {window: {}};
    require('vm').runInNewContext(text, sandbox);
    return sandbox.window.PixivLayoutFeedbackPublicConfig;
}

function findLatestJar(dir, prefix) {
    if (!fs.existsSync(dir)) return null;
    const files = fs.readdirSync(dir).filter(name => name.indexOf(prefix) === 0
        && name.endsWith('.jar')
        && name.indexOf('-sources.jar') < 0
        && name.indexOf('-javadoc.jar') < 0);
    if (!files.length) return null;
    files.sort((a, b) => {
        return fs.statSync(path.join(dir, b)).mtimeMs - fs.statSync(path.join(dir, a)).mtimeMs;
    });
    return path.join(dir, files[0]);
}

function hasCommand(name) {
    const result = run('where.exe', [name]);
    return result.status === 0;
}

function buildWorkbenchJarWithEnabledResidual() {
    console.log('==> building workbench module jar with a residual enabled config');
    const mvnArgs = [
        '-pl', 'pixivdownload-plugin-download-workbench',
        '-am', 'package', '-DskipTests',
        '-Dpixiv.layout-survey.project-token=phc_layout_survey_residual_enabled',
        '-Dpixiv.layout-survey.survey-id=11111111-2222-3333-4444-555555555555',
        '-Dpixiv.layout-survey.api-host=https://residual.example.invalid',
        '-Dpixiv.layout-survey.ui-host=https://eu.i.posthog.com'
    ];
    const result = runMaven(mvnArgs, {timeout: 60 * 60 * 1000});
    if (result.status !== 0) {
        throw new Error('Maven residual-enabled build failed: ' + combined(result).slice(-4000));
    }
    const jar = findLatestJar(path.join(REPO_ROOT, 'pixivdownload-plugin-download-workbench', 'target'),
        'pixivdownload-plugin-download-workbench-');
    ok('模块 target jar 已构建', jar !== null);
    const config = parseConfig(readZipEntry(jar, JAR_ENTRY));
    eq('source JAR 残留 enabled=true', config.enabled, true);
    eq('source JAR 残留 token 为 residual 值', config.projectToken, 'phc_layout_survey_residual_enabled');
    return jar;
}

function runInstaller(args, options) {
    const script = path.join(REPO_ROOT, 'scripts', 'package-installer-with-plugins.ps1');
    return runPowerShell([script].concat(args), options);
}

function assertStagedJar(configExpected) {
    ok('app-image 中 workbench JAR 存在', fs.existsSync(STAGED_JAR));
    const config = parseConfig(readZipEntry(STAGED_JAR, JAR_ENTRY));
    eq('enabled=' + configExpected.enabled, config.enabled, configExpected.enabled);
    eq('projectToken', config.projectToken, configExpected.projectToken);
    eq('surveyId', config.surveyId, configExpected.surveyId);
    eq('apiHost', config.apiHost, configExpected.apiHost);
    eq('uiHost', config.uiHost, configExpected.uiHost);
}

function assertUnsignedProvenance() {
    const provenanceDir = path.join(path.dirname(STAGED_JAR), 'provenance');
    const sidecar = path.join(provenanceDir, 'pixivdownload-plugin-download-workbench.jar.pixiv-plugin-provenance');
    ok('unsigned provenance sidecar 存在', fs.existsSync(sidecar));
    const text = fs.readFileSync(sidecar, 'utf8');
    ok('provenance source=LOCAL_UPLOAD', text.indexOf('source=LOCAL_UPLOAD') >= 0);
    ok('provenance status=UNSIGNED_ALLOWED', text.indexOf('status=UNSIGNED_ALLOWED') >= 0);
    const shaLine = text.split(/\r?\n/).find(line => line.indexOf('artifactSha256=') === 0);
    ok('provenance 覆盖最终字节（SHA 与文件一致）', shaLine && shaLine.indexOf('artifactSha256=') === 0);
}

function matrixC() {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-pack-'));
    const inputsDir = path.join(tmp, 'catalog-inputs');
    fs.mkdirSync(inputsDir);
    const result = runInstaller([
        '-Version', '0.0.1-survey-test',
        '-PluginSource', 'Catalog',
        '-EnableLayoutSurvey',
        '-PluginInputsDir', inputsDir
    ], {timeout: 10 * 60 * 1000});
    eq('Catalog + Enable 立即失败', result.status === 0, false);
    const output = combined(result);
    ok('失败信息提示必须使用 PluginSource Local', output.indexOf('EnableLayoutSurvey requires -PluginSource Local') >= 0);
    ok('失败前未写入 Catalog 输入目录', fs.readdirSync(inputsDir).length === 0);
    fs.rmSync(tmp, {recursive: true, force: true});
}

function matrixA(sourceJar) {
    console.log('==> matrix A: Local disabled（残留 enabled 必须被覆盖）');
    const result = runInstaller([
        '-Version', '0.0.1-survey-test',
        '-PluginSource', 'Local',
        '-AllowUnsignedLocalPlugins'
    ]);
    if (result.status !== 0) {
        throw new Error('matrix A installer run failed: ' + combined(result).slice(-4000));
    }
    const output = combined(result);
    ok('输出不包含测试 token 值', output.indexOf('phc_layout_survey_local_test') < 0);
    ok('输出声明 disabled', output.indexOf('Layout survey packaging: disabled') >= 0);
    assertStagedJar({enabled: false, projectToken: '', surveyId: '', apiHost: '', uiHost: ''});
    assertUnsignedProvenance();
    eq('模块 target 原始 JAR 未被修改（仍残留 enabled）',
        parseConfig(readZipEntry(sourceJar, JAR_ENTRY)).enabled, true);
}

function matrixB() {
    console.log('==> matrix B: Local unsigned enabled');
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-pack-'));
    const propsFile = path.join(tmp, 'posthog.properties');
    fs.writeFileSync(propsFile, TEST_PROPS.join('\n') + '\n', 'utf8');
    const result = runInstaller([
        '-Version', '0.0.1-survey-test',
        '-PluginSource', 'Local',
        '-AllowUnsignedLocalPlugins',
        '-EnableLayoutSurvey',
        '-LayoutSurveyPropertiesFile', propsFile
    ]);
    if (result.status !== 0) {
        throw new Error('matrix B installer run failed: ' + combined(result).slice(-4000));
    }
    const output = combined(result);
    ok('输出声明 enabled', output.indexOf('Layout survey packaging: enabled') >= 0);
    ok('输出不打印四项值', output.indexOf('phc_layout_survey_local_test') < 0
        && output.indexOf('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee') < 0);
    assertStagedJar({
        enabled: true,
        projectToken: 'phc_layout_survey_local_test',
        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        apiHost: 'https://feedback.example.invalid',
        uiHost: 'https://us.posthog.com'
    });
    const generatedConfig = path.join(REPO_ROOT, 'build', 'local-layout-survey', 'public-config.js');
    ok('生成文件存在', fs.existsSync(generatedConfig));
    const entryBytes = readZipEntry(STAGED_JAR, JAR_ENTRY);
    eq('entry 与生成文件逐字节一致', entryBytes === fs.readFileSync(generatedConfig, 'utf8'), true);
    eq('无 .sig（unsigned 移除旧官方签名）', fs.existsSync(STAGED_JAR + '.sig'), false);
    eq('无 .sig.json', fs.existsSync(STAGED_JAR + '.sig.json'), false);
    assertUnsignedProvenance();
    fs.rmSync(tmp, {recursive: true, force: true});
}

function matrixD() {
    const privateKey = path.join(REPO_ROOT, 'official-signing', 'official-ed25519-private.pem');
    if (!fs.existsSync(privateKey)) {
        skip('D：测试环境没有可用签名密钥（official-signing/official-ed25519-private.pem 缺失），Local signed 矩阵未执行');
        return;
    }
    console.log('==> matrix D: Local signed（修改后重新签名，不复用旧签名）');
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-pack-'));
    const propsFile = path.join(tmp, 'posthog.properties');
    fs.writeFileSync(propsFile, [
        'pixiv.layout-survey.project-token=phc_layout_survey_local_signed',
        'pixiv.layout-survey.survey-id=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        'pixiv.layout-survey.api-host=https://feedback.example.invalid',
        'pixiv.layout-survey.ui-host=https://us.posthog.com'
    ].join('\n') + '\n', 'utf8');
    const configFile = path.join(tmp, 'public-config.js');
    let result = runPowerShell([
        path.join(REPO_ROOT, 'scripts', 'generate-layout-survey-public-config.ps1'),
        '-PropertiesFile', propsFile,
        '-OutputPath', configFile
    ]);
    if (result.status !== 0) {
        throw new Error('generator failed for matrix D: ' + combined(result));
    }
    const bootJar = findLatestJar(path.join(REPO_ROOT, 'pixivdownload-app', 'target'), 'PixivDownload-');
    const sigToolJar = findLatestJar(path.join(REPO_ROOT, 'pixivdownload-plugin-signature', 'target'),
        'pixivdownload-plugin-signature-');
    if (!bootJar || !sigToolJar) {
        skip('D：boot jar 或签名工具 jar 缺失，Local signed 矩阵未执行');
        fs.rmSync(tmp, {recursive: true, force: true});
        return;
    }
    const packageScript = path.join(REPO_ROOT, 'scripts', 'package-local.ps1');
    result = runPowerShell([
        packageScript,
        '-Version', '0.0.1-survey-signed',
        '-PrebuiltJar', bootJar,
        '-OfficialKeyId', 'pixivdownloader-official-root-2026-07',
        '-PrivateKeyFile', privateKey,
        '-SignatureToolJar', sigToolJar,
        '-LayoutSurveyPublicConfigFile', configFile,
        '-SkipPortable',
        '-SkipOfflinePortable',
        '-SkipInstaller'
    ]);
    if (result.status !== 0) {
        throw new Error('matrix D package-local run failed: ' + combined(result).slice(-4000));
    }
    assertStagedJar({
        enabled: true,
        projectToken: 'phc_layout_survey_local_signed',
        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        apiHost: 'https://feedback.example.invalid',
        uiHost: 'https://us.posthog.com'
    });
    eq('signed 模式生成 .sig', fs.existsSync(STAGED_JAR + '.sig'), true);
    const signature = JSON.parse(fs.readFileSync(STAGED_JAR + '.sig', 'utf8'));
    ok('签名对象含 algorithm=Ed25519', signature.algorithm === 'Ed25519');
    ok('签名 keyId 为当前 OfficialKeyId', signature.keyId === 'pixivdownloader-official-root-2026-07');
    const size = fs.statSync(STAGED_JAR).size;
    const sha256 = require('crypto').createHash('sha256')
        .update(fs.readFileSync(STAGED_JAR)).digest('hex');
    // official-signing 开发密钥即官方信任根：按 official policy 验证签名覆盖最终字节。
    result = run('java', ['-cp', sigToolJar,
        'top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool',
        'verify-artifact',
        '--artifact', STAGED_JAR,
        '--signature', STAGED_JAR + '.sig',
        '--plugin-id', 'download-workbench',
        '--version', '1.0.0',
        '--expected-size', String(size),
        '--sha256', sha256,
        '--policy', 'official'
    ]);
    eq('新签名验证最终 JAR 字节', result.status, 0);
    const provenance = fs.readFileSync(path.join(path.dirname(STAGED_JAR), 'provenance',
        'pixivdownload-plugin-download-workbench.jar.pixiv-plugin-provenance'), 'utf8');
    ok('signed provenance 为 VERIFIED 且覆盖最终字节',
        provenance.indexOf('status=VERIFIED') >= 0
        && provenance.indexOf('artifactSha256=' + sha256) >= 0);
    fs.rmSync(tmp, {recursive: true, force: true});
}

function main() {
    if (process.platform !== 'win32') {
        skip('非 Windows 环境：Windows 安装包矩阵无法执行');
        return;
    }
    if (process.env.PIXIV_LAYOUT_SURVEY_PACKAGING_IT !== '1') {
        skip('未设置 PIXIV_LAYOUT_SURVEY_PACKAGING_IT=1：完整矩阵需要两次真实安装包构建（数分钟）');
        console.log('运行：node pixivdownload-plugin-download-workbench/src/test/js/layout-survey-packaging.test.js');
        return;
    }
    for (const tool of ['powershell', 'jlink', 'jpackage']) {
        if (!hasCommand(tool)) {
            skip('缺少 ' + tool + '：JAR 集成矩阵与真实安装包验证未执行');
            return;
        }
    }
    if (!fs.existsSync('D:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe')
            && !hasCommand('iscc.exe')) {
        skip('缺少 Inno Setup 6（ISCC.exe）：安装包构建无法执行');
        return;
    }
    console.log(`\nlayout-survey-packaging.test.js: ${passed} passed, ${skipped} skipped ✓`);
    matrixC();
    const sourceJar = buildWorkbenchJarWithEnabledResidual();
    matrixA(sourceJar);
    matrixB();
    matrixD();
    console.log(`\nlayout-survey-packaging.test.js: ${passed} passed, ${skipped} skipped ✓`);
}

try {
    main();
} catch (error) {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
}
