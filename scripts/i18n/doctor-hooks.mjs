#!/usr/bin/env node
'use strict';
/**
 * 检查本地 Git hooks 配置是否正确（doctor）。
 * 只读检查，不修改任何配置；发现问题时给出修复命令并返回非零退出码。
 * 仓库根目录由 git rev-parse --show-toplevel 解析。
 */

import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';

const REQUIRED_HOOKS = ['pre-commit', 'pre-push', 'pre-push-guard.sh'];
const HOOKS_DIR = path.join('scripts', 'hooks').split(path.sep).join('/');

function main() {
    let repoRoot;
    try {
        repoRoot = execFileSync('git', ['rev-parse', '--show-toplevel'],
            { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    } catch (e) {
        console.error('doctor:hooks: not inside a git repository.');
        process.exit(1);
        return;
    }

    const hooksAbs = path.join(repoRoot, ...HOOKS_DIR.split(path.sep));
    const problems = [];

    let configured = null;
    try {
        configured = execFileSync('git', ['config', '--local', '--get', 'core.hooksPath'],
            { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    } catch (e) {
        problems.push('core.hooksPath is not configured (local)');
    }
    if (configured === null || configured === '') {
        problems.push('run: npm run setup:hooks');
    } else if (configured !== HOOKS_DIR) {
        problems.push('core.hooksPath is "' + configured + '" (expected "' + HOOKS_DIR + '"); run: npm run setup:hooks');
    }

    if (!fs.existsSync(hooksAbs)) {
        problems.push('hooks directory missing: ' + HOOKS_DIR);
    } else {
        for (const hook of REQUIRED_HOOKS) {
            if (!fs.existsSync(path.join(hooksAbs, hook))) {
                problems.push('required hook missing: ' + HOOKS_DIR + '/' + hook);
            }
        }
    }

    if (problems.length > 0) {
        console.error('doctor:hooks: PROBLEMS FOUND');
        for (const problem of problems) {
            console.error('  - ' + problem);
        }
        process.exit(1);
    }
    console.log('doctor:hooks: OK — core.hooksPath = ' + configured + ' (local), hooks present.');
}

main();
