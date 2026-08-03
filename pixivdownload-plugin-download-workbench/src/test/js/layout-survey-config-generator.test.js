'use strict';
/*
 * 公开配置生成器（scripts/generate-layout-survey-public-config.ps1）行为测试。
 *
 * 通过 Node 子进程调用本机 PowerShell（Windows PowerShell 5.1 或 pwsh）真实执行
 * 生成器脚本，覆盖三态构建规则（全空 / 完整 / 部分）、URL 校验、转义、UTF-8 无 BOM
 * 与「公开配置不是 Secret」的输出契约。找不到可用的 PowerShell 时整组跳过
 * （GitHub Actions ubuntu / windows runner 均预装 pwsh / powershell）。
 *
 * 运行：node pixivdownload-plugin-download-workbench/src/test/js/layout-survey-config-generator.test.js
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const {execFile, spawnSync} = require('child_process');

const SCRIPT_PATH = path.join(__dirname, '..', '..', '..', '..', 'scripts',
    'generate-layout-survey-public-config.ps1');

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

function resolvePowerShell() {
    if (process.platform === 'win32') {
        const ps5 = spawnSync('powershell', ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.Major'], {encoding: 'utf8'});
        if (ps5.status === 0 && /^\s*\d/.test(ps5.stdout || '')) return 'powershell';
    }
    const pwsh = spawnSync('pwsh', ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.Major'], {encoding: 'utf8'});
    if (pwsh.status === 0 && /^\s*\d/.test(pwsh.stdout || '')) return 'pwsh';
    return null;
}

function runGenerator(powerShell, args, env) {
    const result = spawnSync(powerShell, ['-NoProfile', '-NonInteractive', '-File', SCRIPT_PATH].concat(args), {
        encoding: 'utf8',
        env: Object.assign({}, process.env, env || {})
    });
    return result;
}

function generateDisabled(powerShell, outputPath, extraEnv) {
    return runGenerator(powerShell, ['-OutputPath', outputPath], extraEnv || {});
}

const SURVEY_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

function testDisabled(powerShell) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const output = path.join(dir, 'public-config.js');
    const result = generateDisabled(powerShell, output);
    eq('四项全空时退出码为 0', result.status, 0);
    ok('四项全空时输出文件存在', fs.existsSync(output));

    const text = fs.readFileSync(output, 'utf8');
    ok('输出含 enabled=false', text.indexOf('enabled: false') >= 0);
    ok('输出含空 projectToken', text.indexOf('projectToken: ""') >= 0);
    ok('输出含空 surveyId', text.indexOf('surveyId: ""') >= 0);
    ok('输出含空 apiHost', text.indexOf('apiHost: ""') >= 0);
    ok('输出含空 uiHost', text.indexOf('uiHost: ""') >= 0);
    ok('输出使用 Object.freeze', text.indexOf('Object.freeze({') >= 0);
    ok('输出不含 Personal API Key 字段', text.indexOf('personalApiKey') < 0);
    ok('输出不含 Service Account Token 字段', text.indexOf('serviceAccountToken') < 0);
    ok('输出注明公开配置不是 Secret', text.indexOf('not a secret') >= 0 || text.indexOf('PUBLIC client configuration') >= 0);

    const bytes = fs.readFileSync(output);
    const hasBom = bytes.length >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF;
    eq('输出为 UTF-8 无 BOM', hasBom, false);
    // 合法 JavaScript：Node 可直接执行并得到预期对象
    const sandbox = {window: {}};
    require('vm').runInNewContext(text, sandbox);
    eq('生成的 JS 可执行且 enabled=false', sandbox.window.PixivLayoutFeedbackPublicConfig.enabled, false);
    eq('生成的 JS 四项为空', JSON.stringify([
        sandbox.window.PixivLayoutFeedbackPublicConfig.projectToken,
        sandbox.window.PixivLayoutFeedbackPublicConfig.surveyId,
        sandbox.window.PixivLayoutFeedbackPublicConfig.apiHost,
        sandbox.window.PixivLayoutFeedbackPublicConfig.uiHost
    ]), JSON.stringify(['', '', '', '']));
    fs.rmSync(dir, {recursive: true, force: true});
}

function testEnabled(powerShell) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const output = path.join(dir, 'public-config.js');
    const result = runGenerator(powerShell, [
        '-ProjectToken', 'phc_test_token_12345',
        '-SurveyId', SURVEY_ID,
        '-ApiHost', 'https://proxy.example.com/collect',
        '-UiHost', 'https://us.i.posthog.com',
        '-OutputPath', output
    ], {});
    eq('四项完整时退出码为 0', result.status, 0);
    const text = fs.readFileSync(output, 'utf8');
    ok('完整配置生成 enabled=true', text.indexOf('enabled: true') >= 0);
    ok('完整配置含 projectToken', text.indexOf('phc_test_token_12345') >= 0);
    ok('完整配置含 surveyId', text.indexOf(SURVEY_ID) >= 0);
    const sandbox = {window: {}};
    require('vm').runInNewContext(text, sandbox);
    eq('生成的 JS enabled=true', sandbox.window.PixivLayoutFeedbackPublicConfig.enabled, true);
    eq('apiHost 保持代理路径', sandbox.window.PixivLayoutFeedbackPublicConfig.apiHost, 'https://proxy.example.com/collect');
    eq('uiHost 不被替换', sandbox.window.PixivLayoutFeedbackPublicConfig.uiHost, 'https://us.i.posthog.com');
    fs.rmSync(dir, {recursive: true, force: true});
}

function expectFailure(powerShell, label, args, env) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const output = path.join(dir, 'public-config.js');
    const result = runGenerator(powerShell, args.concat(['-OutputPath', output]), env || {});
    if (result.status === 0) {
        throw new Error('FAIL: ' + label + ' expected non-zero exit but got 0');
    }
    passed++;
    const combined = (result.stderr || '') + (result.stdout || '');
    ok(label + ' 输出缺失字段提示', /missing|required|invalid|must|unsupported|not an absolute|does not match|shape|incomplete|control character/i.test(combined));
    fs.rmSync(dir, {recursive: true, force: true});
    return combined;
}

function testPartialAndInvalid(powerShell) {
    const full = ['-SurveyId', SURVEY_ID, '-ApiHost', 'https://a.example.com', '-UiHost', 'https://b.example.com'];
    expectFailure(powerShell, '单独缺 token 失败', full);
    expectFailure(powerShell, '单独缺 SurveyId 失败',
        ['-ProjectToken', 't1', '-ApiHost', 'https://a.example.com', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, '单独缺 apiHost 失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, '单独缺 uiHost 失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-ApiHost', 'https://a.example.com']);
    expectFailure(powerShell, '只提供一项失败',
        ['-ProjectToken', 't1']);
    expectFailure(powerShell, '只提供两项失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID]);
    expectFailure(powerShell, '只提供三项失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-ApiHost', 'https://a.example.com']);

    expectFailure(powerShell, '非法 URL 失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-ApiHost', 'not a url', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, 'URL credentials 失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-ApiHost', 'https://user:pass@example.com', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, '正式构建 http 失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-ApiHost', 'http://example.com', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, 'fragment 失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-ApiHost', 'https://a.example.com#frag', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, 'file: scheme 失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-ApiHost', 'file:///etc/passwd', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, 'ftp: scheme 失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-ApiHost', 'ftp://example.com', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, 'javascript: scheme 失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-ApiHost', 'javascript:alert(1)', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, 'data: scheme 失败',
        ['-ProjectToken', 't1', '-SurveyId', SURVEY_ID, '-ApiHost', 'data:text/plain,x', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, 'SurveyId 形状非法失败',
        ['-ProjectToken', 't1', '-SurveyId', 'bad id!!', '-ApiHost', 'https://a.example.com', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, 'ProjectToken 含换行失败',
        ['-ProjectToken', 'line1\nline2', '-SurveyId', SURVEY_ID, '-ApiHost', 'https://a.example.com', '-UiHost', 'https://b.example.com']);
    expectFailure(powerShell, '空白值按缺失处理（部分配置失败）',
        ['-ProjectToken', '   ', '-SurveyId', SURVEY_ID, '-ApiHost', 'https://a.example.com', '-UiHost', 'https://b.example.com']);
}

function testLocalHttpAllowed(powerShell) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const output = path.join(dir, 'public-config.js');
    const result = runGenerator(powerShell, [
        '-ProjectToken', 't1',
        '-SurveyId', SURVEY_ID,
        '-ApiHost', 'http://localhost:6999/proxy',
        '-UiHost', 'http://127.0.0.1:8080',
        '-OutputPath', output
    ], {});
    eq('localhost http 允许', result.status, 0);
    const sandbox = {window: {}};
    require('vm').runInNewContext(fs.readFileSync(output, 'utf8'), sandbox);
    eq('localhost apiHost 生效', sandbox.window.PixivLayoutFeedbackPublicConfig.apiHost, 'http://localhost:6999/proxy');
    eq('127.0.0.1 uiHost 生效', sandbox.window.PixivLayoutFeedbackPublicConfig.uiHost, 'http://127.0.0.1:8080');
    fs.rmSync(dir, {recursive: true, force: true});
}

function testRequireConfig(powerShell) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const output = path.join(dir, 'public-config.js');
    const result = runGenerator(powerShell, ['-RequireConfig', '-OutputPath', output], {});
    if (result.status === 0) {
        throw new Error('FAIL: require-config 且全空应失败');
    }
    passed++;
    ok('require-config 全空报错列出缺失项',
        /required for this build|all four PIXIV_LAYOUT_SURVEY/i.test((result.stderr || '') + (result.stdout || '')));
    fs.rmSync(dir, {recursive: true, force: true});
}

function testEnvVarInput(powerShell) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const output = path.join(dir, 'public-config.js');
    const result = runGenerator(powerShell, ['-OutputPath', output], {
        PIXIV_LAYOUT_SURVEY_PROJECT_TOKEN: 'env-token',
        PIXIV_LAYOUT_SURVEY_ID: SURVEY_ID,
        PIXIV_LAYOUT_SURVEY_API_HOST: 'https://env.example.com',
        PIXIV_LAYOUT_SURVEY_UI_HOST: 'https://eu.i.posthog.com'
    });
    eq('环境变量注入生成 enabled 配置', result.status, 0);
    const sandbox = {window: {}};
    require('vm').runInNewContext(fs.readFileSync(output, 'utf8'), sandbox);
    eq('env 注入的 token', sandbox.window.PixivLayoutFeedbackPublicConfig.projectToken, 'env-token');
    eq('env 注入的 apiHost', sandbox.window.PixivLayoutFeedbackPublicConfig.apiHost, 'https://env.example.com');
    fs.rmSync(dir, {recursive: true, force: true});
}

function testStringEscaping(powerShell) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const output = path.join(dir, 'public-config.js');
    const token = 'phc_"quoted"_\\back\\slash';
    const result = runGenerator(powerShell, [
        '-ProjectToken', token,
        '-SurveyId', SURVEY_ID,
        '-ApiHost', 'https://a.example.com',
        '-UiHost', 'https://b.example.com',
        '-OutputPath', output
    ], {});
    eq('含引号反斜杠 token 生成成功', result.status, 0);
    const sandbox = {window: {}};
    require('vm').runInNewContext(fs.readFileSync(output, 'utf8'), sandbox);
    eq('token 转义往返正确', sandbox.window.PixivLayoutFeedbackPublicConfig.projectToken, token);
    fs.rmSync(dir, {recursive: true, force: true});
}

function main() {
    if (!fs.existsSync(SCRIPT_PATH)) {
        throw new Error('generator script not found: ' + SCRIPT_PATH);
    }
    const powerShell = resolvePowerShell();
    if (!powerShell) {
        skip('未找到 powershell / pwsh，跳过配置生成器行为测试（静态契约仍由 Java 测试覆盖）');
        console.log(`\nlayout-survey-config-generator.test.js: ${passed} passed, ${skipped} skipped ✓`);
        return;
    }
    testDisabled(powerShell);
    testEnabled(powerShell);
    testPartialAndInvalid(powerShell);
    testLocalHttpAllowed(powerShell);
    testRequireConfig(powerShell);
    testEnvVarInput(powerShell);
    testStringEscaping(powerShell);
    console.log(`\nlayout-survey-config-generator.test.js: ${passed} assertions passed ✓`);
}

try {
    main();
} catch (error) {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
}
