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
 * 期望不变量声明：scripts/ci/release-gate-policy.json（仓库内愿望清单，不是远端事实）。
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
 * scripts/ci/test/doctor-github-ruleset.test.mjs）。
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const OWN_DIR = path.dirname(fileURLToPath(import.meta.url));
const POLICY_REL = path.posix.join('release-gate-policy.json');

export function loadInvariants() {
    const file = path.join(OWN_DIR, POLICY_REL);
    if (!fs.existsSync(file)) {
        throw new Error('missing scripts/ci/release-gate-policy.json');
    }
    const policy = JSON.parse(fs.readFileSync(file, 'utf8'));
    const rules = policy.ruleset;
    return {
        branch: policy.protectedBranch,
        master: {
            requiredChecks: rules.requiredChecks,
            requireStrict: rules.requireStrict,
            requirePullRequest: rules.requirePullRequest,
            requiredApprovals: rules.minimumApprovals,
            allowBypass: rules.allowBypass,
            allowDeletion: rules.allowDeletion,
            allowNonFastForward: rules.allowNonFastForward,
        },
        roots: rules.roots,
    };
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

function active(details, label, problems, report) {
    for (const detail of details) {
        report.push(label + ': ' + detail.name + ' (enforcement: ' + detail.enforcement + ')');
    }
    const enabled = details.filter((detail) => detail.enforcement === 'active');
    if (enabled.length === 0) problems.push(`no active ${label}`);
    return enabled;
}

/** GitHub layers all active matching Rulesets, so audit their combined protection. */
function auditMaster(details, invariants, problems, report) {
    const enabled = active(details, 'master ruleset', problems, report);
    const rules = enabled.flatMap((detail) => Array.isArray(detail.rules) ? detail.rules : []);
    const statusRules = rules.filter((rule) => rule?.type === 'required_status_checks');
    const checks = statusRules.flatMap((rule) => Array.isArray(rule.parameters?.required_status_checks)
        ? rule.parameters.required_status_checks : []);
    for (const expected of invariants.requiredChecks) {
        const found = checks.filter((check) => check?.context === expected);
        if (found.length === 0) {
            problems.push(`master Rulesets miss required check "${expected}"`);
        }
    }
    if (invariants.requireStrict && !statusRules.some((rule) =>
        rule.parameters?.strict_required_status_checks_policy === true)) {
        problems.push('master Rulesets have strict_required_status_checks_policy disabled');
    }
    const pullRules = rules.filter((rule) => rule?.type === 'pull_request');
    if (invariants.requirePullRequest && pullRules.length === 0) {
        problems.push('master Rulesets have no pull_request rule');
    } else if (invariants.requirePullRequest) {
        const approvals = Math.max(...pullRules.map((rule) =>
            Number(rule.parameters?.required_approving_review_count) || 0));
        if (approvals < invariants.requiredApprovals) {
            problems.push(`master Rulesets require ${approvals} approvals instead of at least ${invariants.requiredApprovals}`);
        }
    }
    for (const [type, allowed] of [['deletion', invariants.allowDeletion],
        ['non_fast_forward', invariants.allowNonFastForward]]) {
        if (!allowed && !rules.some((rule) => rule?.type === type)) {
            problems.push(`master Rulesets do not block ${type}`);
        }
    }
    const bypass = enabled.flatMap((detail) => Array.isArray(detail.bypass_actors)
        ? detail.bypass_actors.map((actor) => ({ detail, actor })) : []);
    if (!invariants.allowBypass && bypass.length > 0) {
        problems.push('master Rulesets have bypass actors while allowBypass=false: '
            + bypass.map(({ detail, actor }) => `${detail.name}:${actor.actor_type}:${actor.actor_id || '?'}:${actor.bypass_mode || '?'}`).join(', '));
    }
}

function auditTag(details, invariants, problems, report) {
    const enabled = active(details, 'root tag ruleset', problems, report);
    const rules = enabled.flatMap((detail) => Array.isArray(detail.rules) ? detail.rules : []);
    if (!invariants.allowDeletion && !rules.some((rule) => rule?.type === 'deletion')) {
        problems.push('root tag Rulesets do not block deletion');
    }
    if (!invariants.allowNonFastForward && !rules.some((rule) => rule?.type === 'non_fast_forward')) {
        problems.push('root tag Rulesets do not block non-fast-forward updates');
    }
    if (!invariants.allowBypass && enabled.some((detail) => detail.bypass_actors?.length > 0)) {
        problems.push('root tag Rulesets have bypass actors while allowBypass=false');
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
    const branchRef = invariants.branch || 'refs/heads/master';
    const tagInvariants = Object.entries(invariants.roots || {});

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
        if (isBranchRulesetFor(fetched.detail, branchRef)) {
            masterMatches.push(fetched.detail);
        }
        for (const [ref] of tagInvariants) {
            if (isTagRulesetFor(fetched.detail, ref)) {
                tagMatches.get(ref).push(fetched.detail);
            }
        }
    }

    if (masterMatches.length === 0) {
        problems.push('no branch ruleset covers ' + branchRef);
    } else {
        auditMaster(masterMatches, masterInvariants, problems, report);
    }

    for (const [ref, expected] of tagInvariants) {
        const matches = tagMatches.get(ref);
        if (matches.length === 0) {
            problems.push('no tag ruleset covers ' + ref);
        } else {
            auditTag(matches, expected, problems, report);
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
    console.log('doctor-github-ruleset: VERIFIED — master and all declared root-tag Rulesets match'
        + ' scripts/ci/release-gate-policy.json.');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main().catch((e) => {
        console.error('doctor-github-ruleset: CANNOT VERIFY — ' + (e && e.message ? e.message : e));
        process.exitCode = 2;
    });
}
