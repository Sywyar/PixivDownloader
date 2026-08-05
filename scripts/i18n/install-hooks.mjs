#!/usr/bin/env node
'use strict';
/**
 * 安装本地 Git hooks：git config --local core.hooksPath scripts/hooks。
 * 只修改当前仓库的 local 配置，绝不触碰 global 配置；重复执行幂等。
 * Windows / Git Bash / Unix 路径兼容（一律写入正斜杠相对路径）。
 * 安装后自动运行 doctor 验证；不复制 hook 到 .git/hooks、不覆盖用户全局模板。
 * 仓库根目录由 git rev-parse --show-toplevel 解析，任意子目录执行均可。
 */

import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';

const REQUIRED_HOOKS = ['pre-commit', 'pre-push', 'pre-push-guard.sh'];
const HOOKS_DIR = path.join('scripts', 'hooks').split(path.sep).join('/');

function run(args, opts = {}) {
    return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts }).trim();
}

function main() {
    let repoRoot;
    try {
        repoRoot = run(['rev-parse', '--show-toplevel']);
    } catch (e) {
        console.error('install-hooks: not inside a git repository (git rev-parse --show-toplevel failed).');
        process.exit(1);
        return;
    }

    const hooksAbs = path.join(repoRoot, ...HOOKS_DIR.split(path.sep));
    if (!fs.existsSync(hooksAbs) || !fs.statSync(hooksAbs).isDirectory()) {
        console.error('install-hooks: hooks directory missing: ' + HOOKS_DIR + ' (run from the repository root)');
        process.exit(1);
    }
    for (const hook of REQUIRED_HOOKS) {
        if (!fs.existsSync(path.join(hooksAbs, hook))) {
            console.error('install-hooks: required hook missing: ' + HOOKS_DIR + '/' + hook);
            process.exit(1);
        }
    }

    execFileSync('git', ['config', '--local', 'core.hooksPath', HOOKS_DIR], {
        cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });

    const actual = run(['config', '--local', '--get', 'core.hooksPath'], { cwd: repoRoot });
    if (actual !== HOOKS_DIR) {
        console.error('install-hooks: verification failed — core.hooksPath is "' + actual + '"');
        process.exit(1);
    }

    console.log('install-hooks: core.hooksPath = ' + actual + ' (local only)');
    console.log('install-hooks: pre-commit / pre-push / pre-push-guard.sh are active.');
    console.log('install-hooks: running doctor to verify the installation...');

    try {
        execFileSync('node', [path.join(hooksAbs, '..', 'i18n', 'doctor-hooks.mjs')],
            { cwd: repoRoot, encoding: 'utf8', stdio: ['inherit', 'inherit', 'inherit'] });
    } catch (e) {
        console.error('install-hooks: doctor reported problems after installation; fix them and re-run.');
        process.exit(1);
    }
}

main();
