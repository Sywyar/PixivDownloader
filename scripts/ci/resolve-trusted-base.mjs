#!/usr/bin/env node
'use strict';
/**
 * 可信 base 解析（CI 共享实现）。
 *
 * 本脚本由 GitHub Actions quality-gate 的 bootstrap shell 从 **trusted base** 物化后执行，
 * 与 workflow 内联解析逻辑互为镜像：inline 结果与本脚本输出不一致时 job 失败（fail closed）。
 * 候选提交中的同名脚本永远不会被直接运行（候选不能自我批准）。
 *
 * 规则（与 .github/workflows/quality-gate.yml 的 bootstrap 保持一致）：
 * - pull_request   → github.event.pull_request.base.sha（明确语义，无需祖先校验）
 * - merge_group    → github.event.merge_group.base_sha
 * - push           → github.event.before；若为全零或本地不可解析（新分支），
 *                    使用受保护默认分支的远端 ref（fetch 后 refs/remotes/origin/<default>）
 * - workflow_dispatch → 显式 input trusted_base_sha；未提供时回退默认分支远端 ref
 * - workflow_call  → 必填 input trusted_base_sha，缺失 fail closed
 * - 禁止回退到 candidate 的父提交（github.sha^ 不可信，新分支父提交可能属于恶意 gate）
 *
 * 结果验证（写 GITHUB_ENV 前）：
 * - 40 位小写 hex commit SHA；在本地 object database 中存在；不是 candidate SHA。
 */
import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ZERO = '0000000000000000000000000000000000000000';
const SHA_RE = /^[0-9a-f]{40}$/;

function fail(message) {
    console.error('resolve-trusted-base ERROR: ' + message);
    process.exit(2);
}

function git(args, repoRoot) {
    return execFileSync('git', args, {
        cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
}

function resolveCommit(repoRoot, ref) {
    try {
        const sha = git(['rev-parse', '--verify', '--quiet', ref + '^{commit}'], repoRoot);
        return SHA_RE.test(sha) ? sha : null;
    } catch (e) {
        return null;
    }
}

function fetchDefaultBranch(repoRoot, defaultBranch) {
    try {
        execFileSync('git', ['fetch', '-q', 'origin', 'refs/heads/' + defaultBranch + ':refs/remotes/origin/' + defaultBranch],
            { cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
        return true;
    } catch (e) {
        return false;
    }
}

function resolveDefaultBranch(repoRoot, defaultBranch) {
    if (!defaultBranch) {
        return null;
    }
    const existing = resolveCommit(repoRoot, 'refs/remotes/origin/' + defaultBranch);
    if (existing) {
        return existing;
    }
    if (fetchDefaultBranch(repoRoot, defaultBranch)) {
        return resolveCommit(repoRoot, 'refs/remotes/origin/' + defaultBranch);
    }
    return null;
}

function parseArgs(argv) {
    const args = {
        repoRoot: null, eventName: null, candidate: null, before: null,
        prBase: null, mergeGroupBase: null, inputBase: null, defaultBranch: null,
    };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        const value = () => argv[++i];
        if (arg === '--repo-root') {
            args.repoRoot = value();
        } else if (arg === '--event-name') {
            args.eventName = value();
        } else if (arg === '--candidate') {
            args.candidate = value();
        } else if (arg === '--before') {
            args.before = value();
        } else if (arg === '--pr-base') {
            args.prBase = value();
        } else if (arg === '--merge-group-base') {
            args.mergeGroupBase = value();
        } else if (arg === '--input-base') {
            args.inputBase = value();
        } else if (arg === '--default-branch') {
            args.defaultBranch = value();
        } else if (arg === '--version') {
            args.version = true;
        } else {
            throw new Error('unknown argument: ' + arg);
        }
    }
    return args;
}

function main() {
    let args;
    try {
        args = parseArgs(process.argv.slice(2));
    } catch (e) {
        fail(e.message);
        return;
    }
    if (args.version) {
        console.log('resolve-trusted-base 1');
        return;
    }
    if (!args.repoRoot || !args.eventName || !args.candidate) {
        fail('--repo-root, --event-name and --candidate are required');
        return;
    }
    const repoRoot = path.resolve(args.repoRoot);
    if (!fs.existsSync(path.join(repoRoot, '.git'))) {
        fail('not a git repository: ' + repoRoot);
        return;
    }
    const candidate = resolveCommit(repoRoot, args.candidate);
    if (!candidate) {
        fail('candidate ' + args.candidate + ' does not resolve to a commit');
        return;
    }

    let base = null;
    const event = args.eventName;
    if (event === 'pull_request') {
        base = SHA_RE.test(args.prBase || '') ? args.prBase : null;
    } else if (event === 'merge_group') {
        base = SHA_RE.test(args.mergeGroupBase || '') ? args.mergeGroupBase : null;
    } else if (event === 'push') {
        if (SHA_RE.test(args.before || '') && args.before !== ZERO) {
            base = resolveCommit(repoRoot, args.before);
            if (!base) {
                fail('event.before ' + args.before + ' is not present in the local object database');
                return;
            }
        } else {
            base = resolveDefaultBranch(repoRoot, args.defaultBranch);
            if (!base) {
                fail('new-branch push: cannot resolve the protected default branch remote ref'
                    + ' (refs/remotes/origin/' + (args.defaultBranch || '?') + ')');
                return;
            }
        }
    } else if (event === 'workflow_dispatch') {
        base = SHA_RE.test(args.inputBase || '') ? args.inputBase : null;
        if (!base) {
            base = resolveDefaultBranch(repoRoot, args.defaultBranch);
            if (!base) {
                fail('workflow_dispatch: neither trusted_base_sha input nor the default branch remote ref is available');
                return;
            }
        }
    } else if (event === 'workflow_call') {
        base = SHA_RE.test(args.inputBase || '') ? args.inputBase : null;
        if (!base) {
            fail('workflow_call requires the trusted_base_sha input; fail closed');
            return;
        }
    } else {
        fail('unsupported event: ' + event + '; refusing to guess a trusted base');
        return;
    }

    if (!base) {
        fail('cannot determine a trusted base for ' + candidate + ' (event ' + event + '); fail closed');
        return;
    }
    if (base === candidate) {
        fail('trusted base ' + base + ' equals the candidate; fail closed');
        return;
    }
    const resolved = resolveCommit(repoRoot, base);
    if (!resolved) {
        fail('trusted base ' + base + ' is not present in the local object database; fail closed');
        return;
    }
    if (event === 'push' && args.before && SHA_RE.test(args.before) && args.before !== ZERO
        && resolved !== args.before) {
        // before 已解析为另一个 SHA（缩写/ref 归一化）：以解析结果为准
        fail('trusted base ' + base + ' does not resolve to the push before commit ' + args.before);
        return;
    }
    console.log(resolved);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}
