'use strict';
/**
 * GitHub Ruleset doctor 单元测试（不依赖真实 GitHub）：
 * - list endpoint 只给摘要 → doctor 必须逐个 follow detail endpoint 再检查；
 * - detail 正确解析 rules[].parameters.required_status_checks（context）与
 *   strict_required_status_checks_policy；
 * - strict=false / 任意 bypass / required check 缺失 / tag 未保护 → exit 1；
 * - 无 token / 403 / 404 / detail 不可读 → CANNOT VERIFY / exit 2。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runDoctor, loadInvariants } from '../../ci/doctor-github-ruleset.mjs';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CLI = path.join(SCRIPTS_DIR, '..', 'ci', 'doctor-github-ruleset.mjs');
const REPO = 'test/repo';
const invariants = loadInvariants();
const REQUIRED = invariants.master.requiredChecks;

test('doctor：required contexts 与当前 trusted predecessor 声明一致', () => {
    assert.deepEqual(REQUIRED, [
        'Quality Gate / java-tests', 'Quality Gate / javascript-tests',
        'Quality Gate / signature-guard', 'Quality Gate / trusted-gate-contract',
        'Quality Gate / i18n-check', 'Shared Snippet Drift Check / check-shared-snippets',
    ]);
    const policy = JSON.parse(fs.readFileSync(path.join(SCRIPTS_DIR, 'gate-policy.json'), 'utf8'));
    assert.equal(policy.requiredExternalCheckDefinitions[0].requiredContext,
        'Shared Snippet Drift Check / check-shared-snippets');
});

function validMasterDetail() {
    return {
        id: 101,
        name: 'master-protection',
        enforcement: 'active',
        target: 'branch',
        conditions: { ref_name: { include: ['refs/heads/master'] } },
        rules: [
            {
                type: 'required_status_checks',
                parameters: {
                    strict_required_status_checks_policy: true,
                    required_status_checks: REQUIRED.map((context) => ({ context })),
                },
            },
            { type: 'deletion', parameters: {} },
            { type: 'non_fast_forward', parameters: {} },
        ],
        bypass_actors: [],
    };
}

function validTagDetail() {
    return {
        id: 202,
        name: 'epoch2-root-protection',
        enforcement: 'active',
        target: 'tag',
        conditions: { ref_name: { include: ['refs/tags/i18n-gate-epoch-2-root'] } },
        rules: [
            { type: 'deletion', parameters: {} },
            { type: 'non_fast_forward', parameters: {} },
        ],
        bypass_actors: [],
    };
}

/** 摘要只含 id / name / target / enforcement；真实 API 的摘要 conditions 为 null（必须用 detail 分类）。 */
function summaryOf(detail) {
    return {
        id: detail.id,
        name: detail.name,
        target: detail.target,
        enforcement: detail.enforcement,
        conditions: null,
    };
}

function makeFetch(listSummaries, detailsById) {
    const calls = [];
    const fetchJson = async (url) => {
        calls.push(url);
        if (/\/rulesets\?per_page=100$/.test(url)) {
            return { status: 200, body: listSummaries };
        }
        const m = /\/rulesets\/(\d+)$/.exec(url);
        if (m) {
            const detail = detailsById[Number(m[1])];
            if (detail) {
                return { status: 200, body: detail };
            }
            return { status: 404, body: null };
        }
        return { status: 404, body: null };
    };
    return { fetchJson, calls };
}

async function doctorWith(masterDetail, tagDetail, overrides = {}) {
    const master = masterDetail === null ? null : (masterDetail || validMasterDetail());
    const tag = tagDetail === null ? null : (tagDetail || validTagDetail());
    const summaries = [];
    const details = {};
    if (master) {
        summaries.push(summaryOf(master));
        details[master.id] = master;
    }
    if (tag) {
        summaries.push(summaryOf(tag));
        details[tag.id] = tag;
    }
    const { fetchJson, calls } = makeFetch(summaries, details);
    const result = await runDoctor({
        fetchJson,
        token: 'test-token',
        repo: REPO,
        invariants,
        ...overrides,
    });
    return { result, calls };
}

test('doctor：list endpoint 摘要 → 必须 follow detail endpoint（摘要数据不可信）', async () => {
    // 摘要故意携带错误 enforcement 且 conditions=null（列表对象不可作为检查依据）：
    // detail 完全正确 → 必须 exit 0，证明分类与检查全部来自 detail
    const master = validMasterDetail();
    const tag = validTagDetail();
    const summaries = [
        { ...summaryOf(master), enforcement: 'disabled' },
        { ...summaryOf(tag), enforcement: 'disabled' },
    ];
    const { fetchJson, calls } = makeFetch(summaries, { [master.id]: master, [tag.id]: tag });
    const result = await runDoctor({ fetchJson, token: 't', repo: REPO, invariants });
    assert.equal(result.exitCode, 0, JSON.stringify(result.problems));
    assert.ok(calls.some((u) => /\/rulesets\/101$/.test(u)), '必须请求 master detail endpoint');
    assert.ok(calls.some((u) => /\/rulesets\/202$/.test(u)), '必须请求 tag detail endpoint');
    assert.ok(calls.filter((u) => /\/rulesets\/\d+$/.test(u)).length >= 2,
        'list 之后必须逐个 follow detail（用 detail 的 conditions 分类）');
});

test('doctor：master + root tag detail 完全正确 → success (exit 0)', async () => {
    const { result } = await doctorWith(validMasterDetail(), validTagDetail());
    assert.equal(result.exitCode, 0, JSON.stringify(result.problems));
});

test('doctor：声明多个 Epoch root 时逐个要求受保护 ruleset', async () => {
    const nextInvariants = structuredClone(invariants);
    nextInvariants['i18n-gate-epoch-3-root'] = structuredClone(
        nextInvariants['i18n-gate-epoch-2-root']);
    const master = validMasterDetail();
    const epoch2 = validTagDetail();
    const epoch3 = {
        ...validTagDetail(),
        id: 203,
        name: 'epoch3-root-protection',
        conditions: { ref_name: { include: ['refs/tags/i18n-gate-epoch-3-root'] } },
    };
    const completeFetch = makeFetch(
        [summaryOf(master), summaryOf(epoch2), summaryOf(epoch3)],
        { [master.id]: master, [epoch2.id]: epoch2, [epoch3.id]: epoch3 });
    const complete = await runDoctor({
        fetchJson: completeFetch.fetchJson, token: 't', repo: REPO, invariants: nextInvariants,
    });
    assert.equal(complete.exitCode, 0, JSON.stringify(complete.problems));

    const missingFetch = makeFetch(
        [summaryOf(master), summaryOf(epoch2)], { [master.id]: master, [epoch2.id]: epoch2 });
    const missing = await runDoctor({
        fetchJson: missingFetch.fetchJson, token: 't', repo: REPO, invariants: nextInvariants,
    });
    assert.equal(missing.exitCode, 1);
    assert.ok(missing.problems.some((p) => /refs\/tags\/i18n-gate-epoch-3-root/.test(p)));
});

test('doctor：strict_required_status_checks_policy=false → violation (exit 1)', async () => {
    const master = validMasterDetail();
    master.rules[0].parameters.strict_required_status_checks_policy = false;
    const { result } = await doctorWith(master, validTagDetail());
    assert.equal(result.exitCode, 1);
    assert.ok(result.problems.some((p) => /strict_required_status_checks_policy disabled/.test(p)));
});

test('doctor：allowBypass=false 时任意 bypass actor → violation (exit 1)', async () => {
    const master = validMasterDetail();
    master.bypass_actors = [
        { actor_type: 'RepositoryRole', actor_id: 4, bypass_mode: 'always' },
        { actor_type: 'OrganizationAdmin', actor_id: 1, bypass_mode: 'pull_request' },
    ];
    const { result } = await doctorWith(master, validTagDetail());
    assert.equal(result.exitCode, 1);
    assert.ok(result.problems.some((p) => /allowBypass=false/.test(p)));
});

test('doctor：只有 bypass_mode=pull_request 也必须拒绝', async () => {
    const master = validMasterDetail();
    master.bypass_actors = [
        { actor_type: 'OrganizationAdmin', actor_id: 1, bypass_mode: 'pull_request' },
    ];
    const { result } = await doctorWith(master, validTagDetail());
    assert.equal(result.exitCode, 1);
    assert.ok(result.problems.some((p) => /OrganizationAdmin:1:pull_request/.test(p)));
});

test('doctor：root tag 的 pull_request bypass 同样拒绝', async () => {
    const tag = validTagDetail();
    tag.bypass_actors = [
        { actor_type: 'OrganizationAdmin', actor_id: 1, bypass_mode: 'pull_request' },
    ];
    const { result } = await doctorWith(validMasterDetail(), tag);
    assert.equal(result.exitCode, 1);
    assert.ok(result.problems.some((p) => /root tag.*allowBypass=false/.test(p)));
});

test('doctor：required check 缺失 → violation (exit 1)', async () => {
    const master = validMasterDetail();
    master.rules[0].parameters.required_status_checks = master.rules[0].parameters.required_status_checks
        .filter((c) => c.context !== REQUIRED[0]);
    const { result } = await doctorWith(master, validTagDetail());
    assert.equal(result.exitCode, 1);
    assert.ok(result.problems.some((p) => /misses required check/.test(p)));
});

test('doctor：tag deletion 未保护 → violation (exit 1)', async () => {
    const tag = validTagDetail();
    tag.rules = tag.rules.filter((r) => r.type !== 'deletion');
    const { result } = await doctorWith(validMasterDetail(), tag);
    assert.equal(result.exitCode, 1);
    assert.ok(result.problems.some((p) => /does not block deletion/.test(p)));
});

test('doctor：tag non-fast-forward 未保护 → violation (exit 1)', async () => {
    const tag = validTagDetail();
    tag.rules = tag.rules.filter((r) => r.type !== 'non_fast_forward');
    const { result } = await doctorWith(validMasterDetail(), tag);
    assert.equal(result.exitCode, 1);
    assert.ok(result.problems.some((p) => /does not block non-fast-forward/.test(p)));
});

test('doctor：无 token → CANNOT VERIFY (exit 2)，不是 pass', () => {
    const env = { ...process.env };
    delete env.GITHUB_TOKEN;
    delete env.GH_TOKEN;
    const run = spawnSync('node', [CLI, '--repo', REPO], { encoding: 'utf8', env });
    assert.equal(run.status, 2);
    assert.match(run.stdout + run.stderr, /CANNOT VERIFY/);
});

test('doctor：list 403 → CANNOT VERIFY (exit 2)', async () => {
    const fetchJson = async () => ({ status: 403, body: null });
    const result = await runDoctor({ fetchJson, token: 't', repo: REPO, invariants });
    assert.equal(result.exitCode, 2);
    assert.match(result.cannotVerify, /access denied/);
});

test('doctor：list 404 → CANNOT VERIFY (exit 2)', async () => {
    const fetchJson = async () => ({ status: 404, body: null });
    const result = await runDoctor({ fetchJson, token: 't', repo: REPO, invariants });
    assert.equal(result.exitCode, 2);
    assert.match(result.cannotVerify, /no rulesets endpoint/);
});

test('doctor：detail 404 → CANNOT VERIFY (exit 2)', async () => {
    const master = validMasterDetail();
    const { fetchJson, calls } = makeFetch([summaryOf(master)], {});
    const result = await runDoctor({ fetchJson, token: 't', repo: REPO, invariants });
    assert.equal(result.exitCode, 2);
    assert.match(result.cannotVerify, /cannot read ruleset detail/);
    assert.ok(calls.some((u) => /\/rulesets\/101$/.test(u)), 'detail 不可读也必须先请求 detail');
});
