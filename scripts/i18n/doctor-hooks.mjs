#!/usr/bin/env node
'use strict';
/**
 * 检查本地 Git hooks 配置是否正确（doctor）。
 * 只读检查，不修改任何配置；发现问题时给出修复命令并返回非零退出码。
 *
 * 检查项：
 * - core.hooksPath 是 local 配置且值为预期相对路径；
 * - pre-commit / pre-push / pre-push-guard.sh 存在；
 * - Git index 文件模式是 100755（executable bit 已提交）；
 * - POSIX 平台下文件实际可执行（Windows 不依赖 fs.constants.X_OK）；
 * - shebang 合法（#!/usr/bin/env bash）；
 * - 文件使用 LF 且不含 BOM；
 * - 每个 bash hook 能通过语法检查（bash -n）：全平台（含 Windows）执行——
 *   Windows 下 hooks 经 Git for Windows / Git Bash 的 bash 运行，跳过检查会掩盖语法错误；
 * - bash 可从 PATH 解析且 `bash --version` 可执行（Windows 缺失时给出 Git for Windows 提示）；
 * - hooks 中声明的本地文档路径必须存在于 Git 仓库（git ls-files 判定），禁止引用不存在的本地文件。
 *
 * 平台判定封装为 platform()（默认 process.platform），测试可注入 PIXIV_DOCTOR_MOCK_PLATFORM。
 * 仓库根目录由 git rev-parse --show-toplevel 解析。
 */

import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';

const REQUIRED_HOOKS = ['pre-commit', 'pre-push', 'pre-push-guard.sh'];
const BASH_HOOKS = ['pre-commit', 'pre-push', 'pre-push-guard.sh'];
const HOOKS_DIR = path.join('scripts', 'hooks').split(path.sep).join('/');

function platform() {
    return process.env.PIXIV_DOCTOR_MOCK_PLATFORM || process.platform;
}

function run(args, opts = {}) {
    return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts }).trim();
}

/** bash 可用性探测：不抛异常，返回 true/false。 */
function bashAvailable() {
    try {
        execFileSync('bash', ['--version'], { stdio: ['ignore', 'pipe', 'pipe'] });
        return true;
    } catch (e) {
        return false;
    }
}

function main() {
    let repoRoot;
    try {
        repoRoot = run(['rev-parse', '--show-toplevel']);
    } catch (e) {
        console.error('doctor:hooks: not inside a git repository.');
        process.exit(1);
        return;
    }

    const hooksAbs = path.join(repoRoot, ...HOOKS_DIR.split(path.sep));
    const problems = [];
    const fixes = [];
    let hooksConfigured = false;

    // ---- core.hooksPath：local 配置 + 预期值 ----
    let configured = null;
    try {
        configured = run(['config', '--local', '--get', 'core.hooksPath'], { cwd: repoRoot });
    } catch (e) {
        problems.push('core.hooksPath is not configured (local)');
    }
    if (configured === null || configured === '') {
        problems.push('core.hooksPath is not configured (local)');
        fixes.push('npm run setup:hooks');
    } else {
        hooksConfigured = true;
        let globalValue = null;
        try {
            globalValue = run(['config', '--get', 'core.hooksPath'], { cwd: repoRoot });
        } catch (e) {
            globalValue = null;
        }
        if (globalValue !== configured) {
            problems.push('core.hooksPath is set by a non-local config (expected local-only)');
            fixes.push('npm run setup:hooks');
        }
        if (configured !== HOOKS_DIR) {
            problems.push('core.hooksPath is "' + configured + '" (expected "' + HOOKS_DIR + '")');
            fixes.push('npm run setup:hooks');
        }
    }

    if (!fs.existsSync(hooksAbs) || !fs.statSync(hooksAbs).isDirectory()) {
        problems.push('hooks directory missing: ' + HOOKS_DIR);
        fixes.push('npm run setup:hooks');
    } else {
        for (const hook of REQUIRED_HOOKS) {
            const file = path.join(hooksAbs, hook);
            if (!fs.existsSync(file)) {
                problems.push('required hook missing: ' + HOOKS_DIR + '/' + hook);
                fixes.push('npm run setup:hooks');
                continue;
            }

            // ---- LF / BOM / shebang ----
            const raw = fs.readFileSync(file, 'utf8');
            if (raw.charCodeAt(0) === 0xFEFF) {
                problems.push(hook + ' has a BOM; convert to UTF-8 without BOM');
                fixes.push('re-save ' + HOOKS_DIR + '/' + hook + ' as UTF-8 without BOM');
            }
            if (raw.includes('\r\n')) {
                problems.push(hook + ' uses CRLF line endings; hooks must use LF');
                fixes.push('re-save ' + HOOKS_DIR + '/' + hook + ' with LF line endings (gitattributes enforces eol=lf)');
            }
            const firstLine = raw.split(/\r?\n/, 1)[0];
            if (!/^#!\/usr\/bin\/env\s+bash$/.test(firstLine)) {
                problems.push(hook + ' has an invalid shebang (expected "#!/usr/bin/env bash")');
            }

            // ---- 本地文档引用必须存在于仓库（git ls-files） ----
            for (const match of raw.matchAll(/docs\/[A-Za-z0-9_./-]+\.md/g)) {
                const docPath = match[0];
                try {
                    const tracked = run(['ls-files', '--', docPath], { cwd: repoRoot });
                    if (!tracked) {
                        problems.push(hook + ' references a local doc path that does not exist in the repository: '
                            + docPath);
                        fixes.push('point the hook message at the online docs (README 在线文档章节)');
                    }
                } catch (e) {
                    problems.push(hook + ': cannot verify doc reference ' + docPath);
                }
            }
        }

        // ---- Git index 文件模式（executable bit） ----
        try {
            const lsFiles = execFileSync('git',
                ['ls-files', '--stage', '--', HOOKS_DIR],
                { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
            for (const line of lsFiles.split('\n')) {
                const match = line.match(/^(\d{6}) [0-9a-f]{40}\s+\d+\s+(.*)$/);
                if (!match) {
                    continue;
                }
                const mode = match[1];
                const rel = match[2];
                if (mode !== '100644' && mode !== '100755') {
                    problems.push('hook has unexpected index mode ' + mode + ': ' + rel);
                    continue;
                }
                if (mode !== '100755') {
                    problems.push('hook is not executable in the Git index (mode 100644): ' + rel);
                    fixes.push('git update-index --chmod=+x ' + rel + ' && git add ' + rel);
                }
            }
        } catch (e) {
            problems.push('cannot read Git index modes for ' + HOOKS_DIR);
        }

        // ---- POSIX 平台下实际可执行（Windows 不依赖 X_OK，Git for Windows 忽略文件位） ----
        if (platform() !== 'win32') {
            for (const hook of BASH_HOOKS) {
                const file = path.join(hooksAbs, hook);
                try {
                    fs.accessSync(file, fs.constants.X_OK);
                } catch (e) {
                    problems.push(hook + ' is not executable on this platform');
                    fixes.push('chmod +x scripts/hooks/' + hook);
                }
            }
        }

        // ---- bash 语法检查（全平台）：bash --version + bash -n 每个 hook ----
        if (bashAvailable()) {
            for (const hook of BASH_HOOKS) {
                try {
                    // bash -n 无参数时从 stdin 读取：规避 MSYS / WSL 对 Windows 路径的参数改写
                    const content = fs.readFileSync(path.join(hooksAbs, hook), 'utf8');
                    execFileSync('bash', ['-n'], { input: content, stdio: ['pipe', 'ignore', 'pipe'] });
                } catch (e) {
                    problems.push(hook + ' failed bash syntax check (bash -n)');
                    fixes.push('fix syntax errors in ' + HOOKS_DIR + '/' + hook);
                }
            }
        } else {
            const message = 'bash is not available on PATH; the hooks run under bash (#!/usr/bin/env bash) '
                + 'so syntax cannot be verified';
            if (hooksConfigured) {
                problems.push(message + ' and hooks are configured');
                if (platform() === 'win32') {
                    fixes.push('install Git for Windows (Git Bash) or add its bin to PATH, then re-run doctor');
                } else {
                    fixes.push('install bash and ensure it is on PATH, then re-run doctor');
                }
            } else {
                problems.push(message + ' (hooks not configured yet)');
                if (platform() === 'win32') {
                    fixes.push('install Git for Windows (Git Bash) or add its bin to PATH');
                }
            }
        }
    }

    if (problems.length > 0) {
        console.error('doctor:hooks: PROBLEMS FOUND');
        for (const problem of problems) {
            console.error('  - ' + problem);
        }
        console.error('');
        console.error('Fix with:');
        for (const fix of [...new Set(fixes)]) {
            console.error('  ' + fix);
        }
        process.exit(1);
    }
    console.log('doctor:hooks: OK — core.hooksPath = ' + HOOKS_DIR + ' (local), '
        + REQUIRED_HOOKS.length + ' hooks present with mode 100755, LF, valid shebang, '
        + 'bash syntax (bash -n) and local doc references verified.');
}

main();
