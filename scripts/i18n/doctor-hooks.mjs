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
 * - 每个 bash hook 能通过语法检查（bash -n，最小 dry-run）。
 *
 * 仓库根目录由 git rev-parse --show-toplevel 解析。
 */

import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';

const REQUIRED_HOOKS = ['pre-commit', 'pre-push', 'pre-push-guard.sh'];
const BASH_HOOKS = ['pre-commit', 'pre-push', 'pre-push-guard.sh'];
const HOOKS_DIR = path.join('scripts', 'hooks').split(path.sep).join('/');

function run(args, opts = {}) {
    return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts }).trim();
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

        // ---- POSIX 平台下实际可执行（Windows 不依赖 X_OK） ----
        if (process.platform !== 'win32') {
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

        // ---- bash 语法检查（最小 dry-run；不执行任何 hook 逻辑） ----
        if (process.platform !== 'win32') {
            for (const hook of BASH_HOOKS) {
                try {
                    execFileSync('bash', ['-n', path.join(hooksAbs, hook)], { stdio: ['ignore', 'pipe', 'pipe'] });
                } catch (e) {
                    problems.push(hook + ' failed bash syntax check (bash -n)');
                    fixes.push('fix syntax errors in ' + HOOKS_DIR + '/' + hook);
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
        + REQUIRED_HOOKS.length + ' hooks present with mode 100755, LF, valid shebang and syntax.');
}

main();
