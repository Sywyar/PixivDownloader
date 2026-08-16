#!/usr/bin/env node
'use strict';
/**
 * GitHub Ruleset 只读 doctor（管理员本地审计用；不修改任何 GitHub 设置）。
 *
 * 检查（全部只读）：
 * - master 分支 ruleset：required checks 完整集合 / strict_required_status_checks_policy /
 *   bypass actors（allowBypass=false 时必须为空）/ deletion / non-fast-forward；
 * - invariants 中声明的每个 verifier root tag ruleset：no deletion / no non-fast-forward / no bypass。
 *
 * REST API 使用（正确流程）：
 *   GET /repos/{owner}/{repo}/rulesets            → 摘要列表（只含 id / name / target /
 *                                                   conditions / enforcement）
 *   GET /repos/{owner}/{repo}/rulesets/{id}       → detail（rules[].parameters.required_status_checks、
 *                                                   bypass_actors 等完整字段）
 * 摘要列表对象不含 conditions 之外的 rules / bypass_actors 完整语义，
 * 因此必须逐个 follow detail endpoint 后再检查（doctor 的退出码以此为准）。
 *
 * 期望不变量声明：scripts/ci/github-ruleset-invariants.json（仓库内愿望清单，不是远端事实）。
 *
 * 凭据：GITHUB_TOKEN 或 GH_TOKEN（需要 repo metadata + rulesets read 权限）。
 * 无 token / API 不可用时明确输出 CANNOT VERIFY 并以退出码 2 结束——绝不静默 pass。
 * 退出码：0 = 已验证且符合不变量；1 = 已验证但存在违规；2 = 无法验证 / 用法错误。
 *
 * 用法：
 *   node scripts/ci/doctor-github-ruleset.mjs [--repo owner/name]
 *   npm run doctor:github-gate
 *
 * 测试：runDoctor 接受注入的 fetchJson，单元测试不依赖真实 GitHub（见
 * scripts/i18n/test/doctor-github-ruleset.test.mjs）。
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const OWN_DIR = path.dirname(fileURLToPath(import.meta.url));
const INVARIANTS_REL = path.posix.join('github-ruleset-invariants.json');

export function loadInvariants() {
    const file = path.join(OWN_DIR, INVARIANTS_REL);
    if (!fs.existsSync(file)) {
        throw new Error('missing scripts/ci/github-ruleset-invariants.json');
    }
    return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function parseArgs(argv) {
    const args = { repo: null };
    for (let i = 0; i < argv.length; i += 1) {
        if (argv[i] === '--repo') {
            args.repo = argv[++i];
        } else {
            throw new Error('unknown argument: ' + argv[i]);
        }
    }
    return args;
}

/** 默认 fetchJson：HTTP fetch + GitHub 认证头。返回 {status, body}；网络错误抛错。 */
async function defaultFetchJson(url, token) {
    const response = await fetch(url, {
        headers: {
            Authorization: 'Bearer ' + token,
            Accept: 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
            'User-Agent': 'pixivdownloader-gate-doctor',
        },
    });
    let body = null;
    if (response.status !== 204) {
        try {
            body = await response.json();
        } catch (e) {
            body = null;
        }
    }
    return { status: response.status, body };
}

function isBranchRulesetFor(rs, ref) {
    return rs && rs.target === 'branch' && (rs.conditions || {}).ref_name
        && Array.isArray((rs.conditions.ref_name || {}).include)
        && rs.conditions.ref_name.include.includes(ref);
}

function isTagRulesetFor(rs, ref) {
    return rs && rs.target === 'tag' && (rs.conditions || {}).ref_name
        && Array.isArray((rs.conditions.ref_name || {}).include)
        && rs.conditions.ref_name.include.includes(ref);
}

/**
 * 审核单个 master ruleset detail（detail 才含 rules / bypass_actors 完整语义）。
 */
function auditMasterDetail(detail, invariants, problems, report) {
    const name = detail.name;
    report.push('master ruleset: ' + name + ' (enforcement: ' + detail.enforcement + ')');
    if (detail.enforcement !== 'active') {
        problems.push('master ruleset ' + name + ' is not active (enforcement: ' + detail.enforcement + ')');
    }
    const rules = Array.isArray(detail.rules) ? detail.rules : [];
    const statusRule = rules.find((r) => r && r.type === 'required_status_checks');
    if (statusRule) {
        const parameters = statusRule.parameters && typeof statusRule.parameters === 'object'
            ? statusRule.parameters : {};
        // detail 字段：rules[].parameters.required_status_checks[]（每项有 context）；
        // 不存在 required_checks 之类旧字段，不要读错键
        const checks = Array.isArray(parameters.required_status_checks)
            ? parameters.required_status_checks : [];
        const checkNames = checks.map((c) => (c && typeof c.context === 'string' ? c.context : ''));
        for (const expected of invariants.requiredChecks) {
            if (!checkNames.includes(expected)) {
                problems.push('master ruleset ' + name + ' misses required check "' + expected
                    + '" (found: ' + (checkNames.join(', ') || 'none') + ')');
            }
        }
        if (parameters.strict_required_status_checks_policy !== true) {
            problems.push('master ruleset ' + name + ' has strict_required_status_checks_policy disabled'
                + ' (Require branches to be up to date before merging must be on)');
        }
    } else {
        problems.push('master ruleset ' + name + ' has no required_status_checks rule');
    }
    const pullRequestRule = rules.find((r) => r && r.type === 'pull_request');
    if (invariants.requirePullRequest) {
        if (!pullRequestRule) {
            problems.push('master ruleset ' + name + ' has no pull_request rule');
        } else {
            const approvals = pullRequestRule.parameters?.required_approving_review_count;
            if (approvals !== invariants.requiredApprovals) {
                problems.push('master ruleset ' + name + ' requires ' + approvals
                    + ' approvals instead of ' + invariants.requiredApprovals);
            }
        }
    }
    for (const expectedRule of ['deletion', 'non_fast_forward']) {
        const present = rules.some((r) => r && r.type === expectedRule);
        const shouldDisable = expectedRule === 'deletion'
            ? !invariants.allowDeletion : !invariants.allowNonFastForward;
        if (shouldDisable && !present) {
            problems.push('master ruleset ' + name + ' does not block ' + expectedRule);
        }
    }
    const bypassActors = Array.isArray(detail.bypass_actors) ? detail.bypass_actors : [];
    if (bypassActors.length > 0 && !invariants.allowBypass) {
        problems.push('master ruleset ' + name + ' has bypass actors while allowBypass=false: '
            + bypassActors.map((a) => (a.actor_type + ':' + (a.actor_id || '?')
                + ':' + (a.bypass_mode || '?'))).join(', '));
    }
}

/**
 * 审核单个 root tag ruleset detail。
 */
function auditTagDetail(detail, invariants, problems, report) {
    const name = detail.name;
    report.push('root tag ruleset: ' + name + ' (enforcement: ' + detail.enforcement + ')');
    if (detail.enforcement !== 'active') {
        problems.push('root tag ruleset ' + name + ' is not active');
    }
    const rules = Array.isArray(detail.rules) ? detail.rules : [];
    if (!rules.some((r) => r && r.type === 'deletion') && !invariants.allowDeletion) {
        problems.push('root tag ruleset ' + name + ' does not block deletion');
    }
    if (!rules.some((r) => r && r.type === 'non_fast_forward') && !invariants.allowNonFastForward) {
        problems.push('root tag ruleset ' + name + ' does not block non-fast-forward updates');
    }
    const bypassActors = Array.isArray(detail.bypass_actors) ? detail.bypass_actors : [];
    if (bypassActors.length > 0 && !invariants.allowBypass) {
        problems.push('root tag ruleset ' + name + ' has bypass actors while allowBypass=false');
    }
}

/**
 * 运行 Ruleset doctor（fetch 注入，可单测；不依赖真实 GitHub）。
 * @param {Object} opts
 * @param {(url: string, token: string) => Promise<{status: number, body: any}>} opts.fetchJson
 * @param {string} opts.token
 * @param {string} opts.repo owner/name
 * @param {string} [opts.baseUrl]
 * @param {Object} opts.invariants github-ruleset-invariants.json 解析结果
 * @returns {Promise<{exitCode: 0|1|2, problems: string[], report: string[], cannotVerify: string|null}>}
 */
export async function runDoctor({ fetchJson, token, repo, baseUrl, invariants }) {
    const problems = [];
    const report = [];
    const base = baseUrl || process.env.GITHUB_API_URL || 'https://api.github.com';
    const masterInvariants = invariants.master;
    const tagInvariants = Object.entries(invariants)
        .filter(([name, value]) => /^i18n-gate-epoch-[2-9][0-9]*-root$/.test(name)
            && value && typeof value === 'object');

    // 1. list endpoint：只提供摘要（id / name / target / conditions / enforcement）
    let list;
    try {
        list = await fetchJson(base + '/repos/' + repo + '/rulesets?per_page=100', token);
    } catch (e) {
        return { exitCode: 2, problems, report,
            cannotVerify: 'GitHub API request failed for the rulesets list: '
                + (e && e.message ? e.message : e) };
    }
    if (list.status === 404) {
        return { exitCode: 2, problems, report,
            cannotVerify: 'no rulesets endpoint for ' + repo
                + ' (missing or read-restricted); this is a report, not a pass' };
    }
    if (list.status === 403 || list.status === 401) {
        return { exitCode: 2, problems, report,
            cannotVerify: 'GitHub API access denied (HTTP ' + list.status
                + '): the token needs repo metadata + rulesets read permission' };
    }
    if (list.status !== 200) {
        return { exitCode: 2, problems, report,
            cannotVerify: 'GitHub API error HTTP ' + list.status + ' for the rulesets list' };
    }
    if (!Array.isArray(list.body)) {
        return { exitCode: 2, problems, report,
            cannotVerify: 'unexpected rulesets response shape; this is a report, not a pass' };
    }

    // 2. 逐个 follow detail endpoint：list 摘要不含 conditions / rules / bypass_actors 完整语义
    //    （真实 API 的摘要对象 conditions 为 null），必须用 detail 的 target + conditions 分类，
    //    再用 detail 的 rules / bypass_actors 检查
    const fetchDetail = async (id) => {
        const detail = await fetchJson(base + '/repos/' + repo + '/rulesets/' + id, token);
        if (detail.status === 404 || detail.status === 403 || detail.status === 401) {
            return { error: 'cannot read ruleset detail ' + id + ' (HTTP ' + detail.status
                + '); this is a report, not a pass' };
        }
        if (detail.status !== 200 || !detail.body || typeof detail.body !== 'object') {
            return { error: 'unexpected ruleset detail response HTTP ' + detail.status
                + ' for ruleset ' + id + '; this is a report, not a pass' };
        }
        return { detail: detail.body };
    };

    const masterMatches = [];
    const tagMatches = new Map(tagInvariants.map(([name]) => [name, []]));
    for (const rs of list.body) {
        const fetched = await fetchDetail(rs.id);
        if (fetched.error) {
            return { exitCode: 2, problems, report, cannotVerify: fetched.error };
        }
        if (isBranchRulesetFor(fetched.detail, 'refs/heads/master')) {
            masterMatches.push(fetched.detail);
        }
        for (const [name] of tagInvariants) {
            if (isTagRulesetFor(fetched.detail, 'refs/tags/' + name)) {
                tagMatches.get(name).push(fetched.detail);
            }
        }
    }

    if (masterMatches.length === 0) {
        problems.push('no branch ruleset covers refs/heads/master');
    } else {
        for (const detail of masterMatches) {
            auditMasterDetail(detail, masterInvariants, problems, report);
        }
    }

    for (const [name, expected] of tagInvariants) {
        const matches = tagMatches.get(name);
        if (matches.length === 0) {
            problems.push('no tag ruleset covers refs/tags/' + name);
        } else {
            for (const detail of matches) {
                auditTagDetail(detail, expected, problems, report);
            }
        }
    }

    const exitCode = problems.length > 0 ? 1 : 0;
    return { exitCode, problems, report, cannotVerify: null };
}

async function main() {
    let args;
    try {
        args = parseArgs(process.argv.slice(2));
    } catch (e) {
        console.error('doctor-github-ruleset ERROR: ' + e.message);
        process.exitCode = 2;
        return;
    }
    const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN || '';
    if (!token) {
        console.error('doctor-github-ruleset: CANNOT VERIFY — no GITHUB_TOKEN / GH_TOKEN in the'
            + ' environment; the GitHub repository settings cannot be read without token'
            + ' (metadata + rulesets read). This is a report, not a pass.');
        process.exitCode = 2;
        return;
    }
    let invariants;
    try {
        invariants = loadInvariants();
    } catch (e) {
        console.error('doctor-github-ruleset ERROR: ' + e.message);
        process.exitCode = 2;
        return;
    }
    const repo = args.repo || process.env.GITHUB_REPOSITORY;
    if (!repo || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) {
        console.error('doctor-github-ruleset ERROR: repository must be owner/name'
            + ' (--repo or GITHUB_REPOSITORY)');
        process.exitCode = 2;
        return;
    }

    let result;
    try {
        result = await runDoctor({ fetchJson: defaultFetchJson, token, repo, invariants });
    } catch (e) {
        console.error('doctor-github-ruleset: CANNOT VERIFY — '
            + (e && e.message ? e.message : e));
        process.exitCode = 2;
        return;
    }
    if (result.cannotVerify) {
        console.error('doctor-github-ruleset: CANNOT VERIFY — ' + result.cannotVerify);
        process.exitCode = 2;
        return;
    }

    console.log('doctor-github-ruleset: repository ' + repo);
    for (const line of result.report) {
        console.log('  ' + line);
    }
    if (result.problems.length > 0) {
        console.error('doctor-github-ruleset: VIOLATIONS FOUND');
        for (const p of result.problems) {
            console.error('  - ' + p);
        }
        console.error('doctor-github-ruleset: admin must fix the GitHub Ruleset settings;'
            + ' repository code cannot change them.');
        process.exitCode = 1;
        return;
    }
    console.log('doctor-github-ruleset: VERIFIED — master and all declared root-tag rulesets match'
        + ' scripts/ci/github-ruleset-invariants.json.');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main().catch((e) => {
        console.error('doctor-github-ruleset: CANNOT VERIFY — ' + (e && e.message ? e.message : e));
        process.exitCode = 2;
    });
}
