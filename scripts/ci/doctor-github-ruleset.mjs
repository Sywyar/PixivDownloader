#!/usr/bin/env node
'use strict';
/**
 * GitHub Ruleset 只读 doctor（管理员本地审计用；不修改任何 GitHub 设置）。
 *
 * 检查（全部只读）：
 * - master 分支 ruleset：required checks 完整集合 / strict_required_status_checks_policy /
 *   bypass actors（permanent always bypass 禁止）/ deletion / non-fast-forward；
 * - refs/tags/i18n-gate-epoch-2-root ruleset：no deletion / no non-fast-forward / no bypass。
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
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const OWN_DIR = path.dirname(fileURLToPath(import.meta.url));
const INVARIANTS_REL = path.posix.join('github-ruleset-invariants.json');

function loadInvariants() {
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

async function api(baseUrl, repo, pathname, token) {
    const url = `${baseUrl}/repos/${repo}/${pathname}?per_page=100`;
    const response = await fetch(url, {
        headers: {
            Authorization: 'Bearer ' + token,
            Accept: 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
            'User-Agent': 'pixivdownloader-gate-doctor',
        },
    });
    if (response.status === 404) {
        return { missing: true };
    }
    if (response.status === 403 || response.status === 401) {
        throw new Error('GitHub API access denied (HTTP ' + response.status
            + '): the token needs repo metadata + rulesets read permission');
    }
    if (!response.ok) {
        throw new Error('GitHub API error HTTP ' + response.status + ' for ' + pathname);
    }
    return response.json();
}

async function main() {
    let args;
    try {
        args = parseArgs(process.argv.slice(2));
    } catch (e) {
        console.error('doctor-github-ruleset ERROR: ' + e.message);
        process.exit(2);
        return;
    }
    const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN || '';
    if (!token) {
        console.error('doctor-github-ruleset: CANNOT VERIFY — no GITHUB_TOKEN / GH_TOKEN in the'
            + ' environment; the GitHub repository settings cannot be read without token'
            + ' (metadata + rulesets read). This is a report, not a pass.');
        process.exit(2);
        return;
    }
    let invariants;
    try {
        invariants = loadInvariants();
    } catch (e) {
        console.error('doctor-github-ruleset ERROR: ' + e.message);
        process.exit(2);
        return;
    }
    const repo = args.repo || process.env.GITHUB_REPOSITORY;
    if (!repo || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) {
        console.error('doctor-github-ruleset ERROR: repository must be owner/name'
            + ' (--repo or GITHUB_REPOSITORY)');
        process.exit(2);
        return;
    }
    const baseUrl = process.env.GITHUB_API_URL || 'https://api.github.com';

    let rulesets;
    try {
        rulesets = await api(baseUrl, repo, 'rulesets', token);
    } catch (e) {
        console.error('doctor-github-ruleset: CANNOT VERIFY — ' + e.message);
        process.exit(2);
        return;
    }
    if (rulesets && rulesets.missing) {
        console.error('doctor-github-ruleset: CANNOT VERIFY — no rulesets endpoint for ' + repo
            + ' (missing or read-restricted); this is a report, not a pass.');
        process.exit(2);
        return;
    }
    if (!Array.isArray(rulesets)) {
        console.error('doctor-github-ruleset: CANNOT VERIFY — unexpected rulesets response shape;'
            + ' this is a report, not a pass.');
        process.exit(2);
        return;
    }

    const problems = [];
    const report = [];
    const masterInvariants = invariants.master;
    const tagInvariants = invariants['i18n-gate-epoch-2-root'];

    const masterRulesets = rulesets.filter((r) => r.target === 'branch'
        && (r.conditions || {}).ref_name && (r.conditions.ref_name.include || []).includes('refs/heads/master'));
    const tagRulesets = rulesets.filter((r) => r.target === 'tag'
        && (r.conditions || {}).ref_name
        && (r.conditions.ref_name.include || []).includes('refs/tags/i18n-gate-epoch-2-root'));

    if (masterRulesets.length === 0) {
        problems.push('no branch ruleset covers refs/heads/master');
    } else {
        for (const rs of masterRulesets) {
            const name = rs.name;
            report.push('master ruleset: ' + name + ' (enforcement: ' + rs.enforcement + ')');
            if (rs.enforcement !== 'active') {
                problems.push('master ruleset ' + name + ' is not active (enforcement: ' + rs.enforcement + ')');
            }
            const rules = rs.rules || [];
            const statusRule = rules.find((r) => r.type === 'required_status_checks');
            if (statusRule) {
                const checks = (statusRule.parameters && statusRule.parameters.required_checks) || [];
                const checkNames = checks.map((c) => c.context);
                for (const expected of masterInvariants.requiredChecks) {
                    if (!checkNames.includes(expected)) {
                        problems.push('master ruleset ' + name + ' misses required check "' + expected
                            + '" (found: ' + (checkNames.join(', ') || 'none') + ')');
                    }
                }
                const strict = statusRule.parameters && statusRule.parameters.strict_required_status_checks_policy;
                if (!strict) {
                    problems.push('master ruleset ' + name + ' has strict_required_status_checks_policy=false'
                        + ' (Require branches to be up to date before merging must be on)');
                }
            } else {
                problems.push('master ruleset ' + name + ' has no required_status_checks rule');
            }
            for (const expectedRule of ['deletion', 'non_fast_forward']) {
                const present = rules.some((r) => r.type === expectedRule);
                const shouldDisable = expectedRule === 'deletion'
                    ? !masterInvariants.allowDeletion : !masterInvariants.allowNonFastForward;
                if (shouldDisable && !present) {
                    problems.push('master ruleset ' + name + ' does not block ' + expectedRule);
                }
            }
            const bypassActors = rs.bypass_actors || [];
            const alwaysBypass = bypassActors.filter((a) => a.bypass_mode === 'always');
            if (alwaysBypass.length > 0 && !masterInvariants.allowBypass) {
                problems.push('master ruleset ' + name + ' has permanent always-bypass actors: '
                    + alwaysBypass.map((a) => (a.actor_type + ':' + (a.actor_id || '?'))).join(', '));
            }
        }
    }

    if (tagRulesets.length === 0) {
        problems.push('no tag ruleset covers refs/tags/i18n-gate-epoch-2-root');
    } else {
        for (const rs of tagRulesets) {
            const name = rs.name;
            report.push('root tag ruleset: ' + name + ' (enforcement: ' + rs.enforcement + ')');
            if (rs.enforcement !== 'active') {
                problems.push('root tag ruleset ' + name + ' is not active');
            }
            const rules = rs.rules || [];
            if (!rules.some((r) => r.type === 'deletion') && !tagInvariants.allowDeletion) {
                problems.push('root tag ruleset ' + name + ' does not block deletion');
            }
            if (!rules.some((r) => r.type === 'non_fast_forward') && !tagInvariants.allowNonFastForward) {
                problems.push('root tag ruleset ' + name + ' does not block non-fast-forward updates');
            }
            const alwaysBypass = (rs.bypass_actors || []).filter((a) => a.bypass_mode === 'always');
            if (alwaysBypass.length > 0 && !tagInvariants.allowBypass) {
                problems.push('root tag ruleset ' + name + ' has permanent always-bypass actors');
            }
        }
    }

    console.log('doctor-github-ruleset: repository ' + repo);
    for (const line of report) {
        console.log('  ' + line);
    }
    if (problems.length > 0) {
        console.error('doctor-github-ruleset: VIOLATIONS FOUND');
        for (const p of problems) {
            console.error('  - ' + p);
        }
        console.error('doctor-github-ruleset: admin must fix the GitHub Ruleset settings;'
            + ' repository code cannot change them.');
        process.exit(1);
        return;
    }
    console.log('doctor-github-ruleset: VERIFIED — master and root-tag rulesets match'
        + ' scripts/ci/github-ruleset-invariants.json.');
}

main().catch((e) => {
    console.error('doctor-github-ruleset: CANNOT VERIFY — ' + (e && e.message ? e.message : e));
    process.exit(2);
});
