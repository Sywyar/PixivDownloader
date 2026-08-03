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

/* ============================================================
   PropertiesFile 模式（本地打包专用 posthog.properties）
   ============================================================ */

const PROP_KEYS = [
    'pixiv.layout-survey.project-token',
    'pixiv.layout-survey.survey-id',
    'pixiv.layout-survey.api-host',
    'pixiv.layout-survey.ui-host'
];
const PLACEHOLDER_BASE64 = 'cG9zdGhvZy5wcm9wZXJ0aWVzIOS7jeWMheWQq+WNoOS9jeWAvO+8jOivt+Whq+WGmeWunumZheWFrOW8gOWuouaIt+err+mFjee9ruOAgg==';
const PLACEHOLDER_MESSAGE = Buffer.from(PLACEHOLDER_BASE64, 'base64').toString('utf8');

function writeProperties(dir, lines, name) {
    const file = path.join(dir, name || 'posthog.properties');
    fs.writeFileSync(file, lines.join('\n') + '\n', 'utf8');
    return file;
}

function completeProps() {
    return [
        'pixiv.layout-survey.project-token=phc_props_test_token_12345',
        'pixiv.layout-survey.survey-id=' + SURVEY_ID,
        'pixiv.layout-survey.api-host=https://feedback.example.invalid',
        'pixiv.layout-survey.ui-host=https://us.posthog.com'
    ];
}

function readGeneratedConfig(output) {
    const sandbox = {window: {}};
    require('vm').runInNewContext(fs.readFileSync(output, 'utf8'), sandbox);
    return sandbox.window.PixivLayoutFeedbackPublicConfig;
}

function expectPropertiesFailure(powerShell, label, lines, extraArgs, env) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const file = writeProperties(dir, lines);
    const output = path.join(dir, 'public-config.js');
    const args = ['-PropertiesFile', file, '-OutputPath', output].concat(extraArgs || []);
    const result = runGenerator(powerShell, args, env || {});
    if (result.status === 0) {
        throw new Error('FAIL: ' + label + ' expected non-zero exit but got 0');
    }
    passed++;
    fs.rmSync(dir, {recursive: true, force: true});
    return (result.stderr || '') + (result.stdout || '');
}

function testPropertiesFile(powerShell) {
    // 静态契约：脚本内嵌精确的占位值错误消息（Base64，脚本本身保持 ASCII-only）。
    const scriptSource = fs.readFileSync(SCRIPT_PATH, 'utf8');
    ok('脚本内嵌占位值错误消息', scriptSource.indexOf(PLACEHOLDER_BASE64) >= 0);
    ok('脚本不使用旧 pixiv.feedback.layout-survey 前缀', scriptSource.indexOf('pixiv.feedback.layout-survey') < 0);

    // 1/2/4/5/6/7/8/24/25/26. 完整有效文件（注释 / 空行 / 空格 trim / 值中后续 =）
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const file = writeProperties(dir, [
        '# leading comment',
        '',
        '! bang comment',
        '  pixiv.layout-survey.project-token  =  phc_props_test_token_12345  ',
        'pixiv.layout-survey.survey-id=' + SURVEY_ID,
        'pixiv.layout-survey.api-host=https://feedback.example.invalid/collect?path=a=b',
        'pixiv.layout-survey.ui-host=https://us.posthog.com'
    ]);
    const output = path.join(dir, 'public-config.js');
    const result = runGenerator(powerShell, ['-PropertiesFile', file, '-OutputPath', output], {});
    eq('完整有效 properties 文件生成成功', result.status, 0);
    const config = readGeneratedConfig(output);
    eq('properties 模式 enabled=true', config.enabled, true);
    eq('四个新键正确（token）', config.projectToken, 'phc_props_test_token_12345');
    eq('四个新键正确（surveyId）', config.surveyId, SURVEY_ID);
    eq('值中后续 = 保留', config.apiHost, 'https://feedback.example.invalid/collect?path=a=b');
    eq('四个新键正确（uiHost）', config.uiHost, 'https://us.posthog.com');
    const bytes = fs.readFileSync(output);
    const hasBom = bytes.length >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF;
    eq('properties 模式输出 UTF-8 无 BOM', hasBom, false);
    ok('生成的 JS 可执行', typeof config === 'object');
    ok('输出不含管理密钥字段', fs.readFileSync(output, 'utf8').indexOf('personalApiKey') < 0
        && fs.readFileSync(output, 'utf8').indexOf('serviceAccountToken') < 0);
    fs.rmSync(dir, {recursive: true, force: true});

    // 9. 文件不存在失败
    const missingDir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const missingResult = runGenerator(powerShell,
        ['-PropertiesFile', path.join(missingDir, 'nope.properties'), '-OutputPath', path.join(missingDir, 'x.js')], {});
    eq('文件不存在失败', missingResult.status === 0, false);
    passed++;
    fs.rmSync(missingDir, {recursive: true, force: true});

    // 10. 缺任一键失败（四个键各缺一次）
    PROP_KEYS.forEach((missingKey, index) => {
        const lines = completeProps().filter(l => l.indexOf(missingKey) !== 0);
        const combined = expectPropertiesFailure(powerShell, '缺键 ' + missingKey, lines);
        ok('缺键失败消息含 key 名', combined.indexOf(missingKey) >= 0);
    });

    // 11/12. 空值与纯空白值失败
    ['', '   '].forEach((emptyValue, index) => {
        const lines = completeProps();
        lines[0] = 'pixiv.layout-survey.project-token=' + emptyValue;
        expectPropertiesFailure(powerShell, '空值失败 ' + index, lines);
    });

    // 13. 重复 key 失败
    expectPropertiesFailure(powerShell, '重复 key 失败',
        completeProps().concat(['pixiv.layout-survey.survey-id=' + SURVEY_ID]));

    // 14/15. 未知 key / 拼写错误 key 失败
    expectPropertiesFailure(powerShell, '未知 key 失败',
        completeProps().concat(['pixiv.feedback.layout-survey.project-token=x']));
    expectPropertiesFailure(powerShell, '拼写错误 key 失败',
        completeProps().concat(['pixiv.layout-survey.project-tokne=x']));

    // 16. 四个占位值分别失败
    ['project-token', 'survey-id', 'api-host', 'ui-host'].forEach(placeholder => {
        const lines = completeProps();
        lines[0] = 'pixiv.layout-survey.project-token=' + placeholder;
        const combined = expectPropertiesFailure(powerShell, '占位值 ' + placeholder, lines);
        ok('占位值错误消息含精确中文提示', combined.indexOf('placeholder') >= 0);
        ok('占位值失败包含 key 名', combined.indexOf('pixiv.layout-survey.project-token') >= 0);
        // 消息内容按 Base64 解码后与静态常量一致（控制台代码页可能乱码，静态校验内容本身）
        ok('脚本内嵌消息解码后含要求文案', Buffer.from(PLACEHOLDER_BASE64, 'base64')
            .toString('utf8') === PLACEHOLDER_MESSAGE);
        ok('占位值失败不输出值本身', combined.indexOf(placeholder + '=') < 0);
    });

    // 17/18/20/21. 非法 URL / 非 HTTPS 远程 / credentials / fragment 失败
    const badUrl = completeProps();
    badUrl[2] = 'pixiv.layout-survey.api-host=not a url';
    expectPropertiesFailure(powerShell, '非法 URL 失败', badUrl);
    const httpRemote = completeProps();
    httpRemote[2] = 'pixiv.layout-survey.api-host=http://example.com';
    expectPropertiesFailure(powerShell, '非 HTTPS 远程地址失败', httpRemote);
    const credentials = completeProps();
    credentials[2] = 'pixiv.layout-survey.api-host=https://user:pass@example.com';
    expectPropertiesFailure(powerShell, 'URL credentials 失败', credentials);
    const fragment = completeProps();
    fragment[2] = 'pixiv.layout-survey.api-host=https://a.example.com#frag';
    expectPropertiesFailure(powerShell, 'fragment 失败', fragment);

    // 19. localhost HTTP 按原规则允许
    const localhostDir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const localhostFile = writeProperties(localhostDir, [
        'pixiv.layout-survey.project-token=phc_local_token',
        'pixiv.layout-survey.survey-id=' + SURVEY_ID,
        'pixiv.layout-survey.api-host=http://localhost:6999/proxy',
        'pixiv.layout-survey.ui-host=http://127.0.0.1:8080'
    ]);
    const localhostResult = runGenerator(powerShell,
        ['-PropertiesFile', localhostFile, '-OutputPath', path.join(localhostDir, 'out.js')], {});
    eq('localhost http 允许', localhostResult.status, 0);
    fs.rmSync(localhostDir, {recursive: true, force: true});

    // 22. PropertiesFile 与显式参数混用失败
    const mixedDir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const mixedFile = writeProperties(mixedDir, completeProps());
    const mixedResult = runGenerator(powerShell, [
        '-PropertiesFile', mixedFile,
        '-ProjectToken', 'phc_mixed',
        '-OutputPath', path.join(mixedDir, 'out.js')
    ], {});
    eq('PropertiesFile 与显式参数混用失败', mixedResult.status === 0, false);
    passed++;
    fs.rmSync(mixedDir, {recursive: true, force: true});

    // 23. PropertiesFile 不受残留环境变量影响
    const envDir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const envFile = writeProperties(envDir, completeProps());
    const envOutput = path.join(envDir, 'out.js');
    const envResult = runGenerator(powerShell, ['-PropertiesFile', envFile, '-OutputPath', envOutput], {
        PIXIV_LAYOUT_SURVEY_PROJECT_TOKEN: 'phc_env_leak_token',
        PIXIV_LAYOUT_SURVEY_ID: '11111111-2222-3333-4444-555555555555',
        PIXIV_LAYOUT_SURVEY_API_HOST: 'https://env.example.com',
        PIXIV_LAYOUT_SURVEY_UI_HOST: 'https://eu.i.posthog.com'
    });
    eq('残留环境变量不影响 PropertiesFile 模式', envResult.status, 0);
    const envConfig = readGeneratedConfig(envOutput);
    eq('文件值优先于残留环境变量', envConfig.projectToken, 'phc_props_test_token_12345');
    fs.rmSync(envDir, {recursive: true, force: true});

    // 27. 错误日志不含 Project token 实际值
    const secretDir = fs.mkdtempSync(path.join(os.tmpdir(), 'plf-cfg-'));
    const secretLines = completeProps();
    secretLines[0] = 'pixiv.layout-survey.project-token=phc_props_secret_token_value_xyz';
    secretLines[2] = 'pixiv.layout-survey.api-host=not a url';
    const secretFile = writeProperties(secretDir, secretLines);
    const secretResult = runGenerator(powerShell,
        ['-PropertiesFile', secretFile, '-OutputPath', path.join(secretDir, 'out.js')], {});
    eq('token 泄露场景仍失败', secretResult.status === 0, false);
    passed++;
    const combined = (secretResult.stderr || '') + (secretResult.stdout || '');
    ok('错误日志不含 Project token 实际值', combined.indexOf('phc_props_secret_token_value_xyz') < 0);
    // 非法 URL 错误在 PropertiesFile 模式下不输出配置值本身
    ok('PropertiesFile 模式错误不显示配置值', combined.indexOf('not a url') < 0);
    fs.rmSync(secretDir, {recursive: true, force: true});
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
    testPropertiesFile(powerShell);
    console.log(`\nlayout-survey-config-generator.test.js: ${passed} assertions passed ✓`);
}

try {
    main();
} catch (error) {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
}
