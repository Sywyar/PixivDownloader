#!/usr/bin/env node
'use strict';

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

const REPO = 'Sywyar/PixivDownloader';
const REPO_ID = 1089943605;
const APP_ID = 4837005;
const QUALITY = '.github/workflows/quality-gate.yml';
const CALLER = '.github/workflows/pr-quality-gate.yml';
const PREFIX = `repos/${REPO}`;
const MAX_RESPONSE = 32 * 1024 * 1024;

function fail(message) { throw new Error(message); }
function integer(value) {
    if (!/^[1-9][0-9]*$/u.test(String(value)) || !Number.isSafeInteger(Number(value))) fail('invalid GitHub object ID');
    return Number(value);
}
function sha(value) {
    if (!/^[0-9a-f]{40}$/u.test(value)) fail('invalid GitHub commit SHA');
    return value;
}
function git(repo, args) {
    return execFileSync('git', ['-C', repo, ...args], {
        encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: MAX_RESPONSE,
    }).trim();
}

export function github(endpoint, { pages = false, raw = false, method = 'GET', body, token } = {}) {
    const args = ['api', '--method', method, endpoint];
    if (pages) args.push('--paginate', '--slurp');
    if (body) args.push('--input', '-');
    const result = execFileSync('gh', args, {
        encoding: 'utf8', windowsHide: true, timeout: 60_000, maxBuffer: MAX_RESPONSE,
        stdio: ['pipe', 'pipe', 'pipe'], input: body ? JSON.stringify(body) : undefined,
        env: { ...process.env, ...(token ? { GH_TOKEN: token } : {}) },
    });
    return raw ? result : JSON.parse(result);
}

export function completePages(pages, key) {
    if (!Array.isArray(pages) || !pages.length) fail('missing API pages');
    const expected = pages[0].total_count;
    if (!Number.isSafeInteger(expected) || expected < 0 || pages.some((page) =>
        page.total_count !== expected || !Array.isArray(page[key]))) fail('inconsistent API pagination');
    const rows = pages.flatMap((page) => page[key]);
    if (rows.length !== expected) fail('incomplete API pagination');
    return rows;
}

function assertRun(run) {
    integer(run.id); integer(run.run_attempt); sha(run.head_sha);
    if (run.repository?.id !== REPO_ID || run.repository?.full_name !== REPO
        || run.event !== 'pull_request' || run.path?.split('@')[0] !== CALLER
        || run.name !== 'Pull Request Quality Gate') fail('foreign or unexpected workflow run');
}

function expectedJobs(repo, ref) {
    const source = YAML.parse(git(repo, ['show', `${sha(ref)}:${QUALITY}`]));
    return Object.entries(source.jobs).map(([id, job]) => {
        if (job.uses || job.strategy || job.if !== undefined) fail('protected jobs must execute directly and unconditionally');
        return job.name || id;
    });
}

export async function inspectRun({ repo, runId, api = github, core }) {
    const run = api(`${PREFIX}/actions/runs/${integer(runId)}`);
    assertRun(run);
    const jobs = completePages(api(`${PREFIX}/actions/runs/${run.id}/jobs?filter=latest&per_page=100`,
        { pages: true }), 'jobs');
    const contract = jobs.filter((job) => job.name === 'quality-gate / trusted-gate-contract');
    if (contract.length !== 1) fail('missing or ambiguous protected contract job');
    const proof = core.parseExecutionProof(api(`${PREFIX}/actions/jobs/${integer(contract[0].id)}/logs`, { raw: true }));
    git(repo, ['fetch', '--no-tags', 'origin', sha(proof.merge)]);
    const read = (ref, rel) => git(repo, ['show', `${ref}:${rel}`]);
    const merge = api(`${PREFIX}/git/commits/${sha(proof.merge)}`);
    const caller = YAML.parse(read(proof.merge, CALLER));
    core.verifyRunResults({ run, jobs, expectedJobs: expectedJobs(repo, proof.base) });
    const evidence = core.verifyRunIdentity({ run, merge, caller, proof, protectedBase: proof.base });
    const policy = core.verifyCandidate({ repo, trusted: proof.base, candidate: proof.merge });
    verifyAttempts(run, jobs, api, contract[0].id, Number(proof.attempt));
    return { ...evidence, pullRequest: integer(proof.pullRequest), checks: policy.ruleset.requiredChecks,
        status: 'completed', conclusion: 'success' };
}

function verifyAttempts(run, jobs, api, proofJob, proofAttempt) {
    // 失败 job 重跑会保留已成功的 job，须逐项证明其实际执行来源。
    const remaining = new Set(jobs.map((job) => job.id));
    for (let attempt = run.run_attempt; attempt > 0 && remaining.size; attempt -= 1) {
        const current = api(`${PREFIX}/actions/runs/${run.id}/attempts/${attempt}`);
        const entries = completePages(api(`${PREFIX}/actions/runs/${run.id}/attempts/${attempt}/jobs?per_page=100`,
            { pages: true }), 'jobs');
        const retained = entries.filter((job) => remaining.has(job.id));
        if (!retained.length) continue;
        const sources = (value) => (value.referenced_workflows || []).map(({ path, sha, ref }) => ({ path, sha, ref }));
        if (current.head_sha !== run.head_sha || current.id !== run.id || current.run_attempt !== attempt
            || current.event !== run.event || current.path !== run.path
            || JSON.stringify(sources(current)) !== JSON.stringify(sources(run))) {
            fail('retained jobs used a different workflow source');
        }
        for (const job of retained) {
            const effective = jobs.find((item) => item.id === job.id);
            if (job.name !== effective.name || job.conclusion !== 'success'
                || job.status !== 'completed' || job.head_sha !== run.head_sha || job.run_id !== run.id) {
                fail('retained job results do not agree with the effective run');
            }
            if (job.id === proofJob && proofAttempt !== attempt) {
                fail('execution record belongs to a different job attempt');
            }
            remaining.delete(job.id);
        }
    }
    if (remaining.size) fail('cannot identify the execution attempt of every effective job');
    const latest = api(`${PREFIX}/actions/runs/${run.id}`);
    if (latest.run_attempt !== run.run_attempt || latest.status !== 'completed' || latest.conclusion !== 'success') {
        fail('workflow changed while its evidence was being verified');
    }
}

export function inspectFullRun({ repo, runId, candidate, api = github, core }) {
    const run = api(`${PREFIX}/actions/runs/${integer(runId)}`);
    integer(run.run_attempt);
    if (run.id !== Number(runId) || run.repository?.id !== REPO_ID || run.repository?.full_name !== REPO
        || run.event !== 'workflow_dispatch' || run.head_branch !== 'master' || run.head_sha !== sha(candidate)
        || run.path?.split('@')[0] !== QUALITY || run.name !== 'Quality Gate'
        || run.referenced_workflows?.length) fail('full verification must execute Quality Gate on the same protected master commit');
    const runs = completePages(api(`${PREFIX}/actions/workflows/quality-gate.yml/runs?event=workflow_dispatch&head_sha=${candidate}&per_page=100`,
        { pages: true }), 'workflow_runs').filter((entry) => entry.head_branch === 'master');
    if (!runs.length || Math.max(...runs.map((entry) => integer(entry.id))) !== run.id) fail('newer full verification superseded this run');
    git(repo, ['fetch', '--no-tags', 'origin', candidate]);
    git(repo, ['merge-base', '--is-ancestor', candidate, 'refs/remotes/origin/master']);
    const parent = git(repo, ['rev-parse', `${candidate}^1`]);
    core.verifyCandidate({ repo, trusted: parent, candidate });
    const jobs = completePages(api(`${PREFIX}/actions/runs/${run.id}/jobs?filter=latest&per_page=100`,
        { pages: true }), 'jobs');
    core.verifyRunResults({ run, jobs, expectedJobs: expectedJobs(repo, candidate), jobPrefix: '' });
    verifyAttempts(run, jobs, api);
    return { integrated: candidate, runId: run.id, attempt: run.run_attempt, verification: 'same-commit-full-quality-gate' };
}

function openPullRequest(run, api) {
    const numbers = run.pull_requests?.map((pr) => pr.number) || [];
    const candidates = numbers.length
        ? numbers.map((number) => api(`${PREFIX}/pulls/${integer(number)}`))
        : api(`${PREFIX}/commits/${sha(run.head_sha)}/pulls?per_page=100`, { pages: true }).flat();
    const matches = candidates.filter((pr) => pr.state === 'open' && pr.base?.ref === 'master'
        && pr.base?.repo?.id === REPO_ID && pr.head?.sha === run.head_sha);
    if (matches.length !== 1) fail('run does not identify one current master PR');
    return matches[0];
}

function skippedCaller(run, api) {
    if (run.status !== 'completed') return false;
    const jobs = completePages(api(`${PREFIX}/actions/runs/${integer(run.id)}/jobs?filter=latest&per_page=100`,
        { pages: true }), 'jobs');
    return jobs.length === 1 && jobs[0].name === 'quality-gate' && jobs[0].status === 'completed'
        && jobs[0].conclusion === 'skipped' && jobs[0].head_sha === run.head_sha && jobs[0].run_id === run.id;
}

function latestExecution(runs, api) {
    // 纯文本编辑只产生跳过的调用；原有运行仍须完整证明同一合并候选。
    return runs.sort((a, b) => integer(b.id) - integer(a.id)).find((run) => !skippedCaller(run, api));
}

function assertLatestPullRequestRun(run, number, api) {
    const runs = completePages(api(`${PREFIX}/actions/workflows/pr-quality-gate.yml/runs?event=pull_request&head_sha=${sha(run.head_sha)}&per_page=100`,
        { pages: true }), 'workflow_runs');
    const matching = runs.filter((entry) => entry.pull_requests?.some((pr) => pr.number === number)
        || (!entry.pull_requests?.length && entry.head_branch === run.head_branch
            && entry.head_repository?.id === run.head_repository?.id));
    if (latestExecution(matching, api)?.id !== run.id) {
        fail('a newer PR execution superseded this run');
    }
}

export async function checkEvent({ repo, event, eventName, core, api = github }) {
    const ownPolicy = JSON.parse(fs.readFileSync(path.join(repo, 'scripts/ci/release-gate-policy.json'), 'utf8'));
    if (eventName === 'workflow_run') {
        const run = api(`${PREFIX}/actions/runs/${integer(event.workflow_run?.id)}`);
        if (run.event === 'workflow_dispatch') {
            return inspectFullRun({ repo, runId: run.id, candidate: run.head_sha, api, core });
        }
        assertRun(run);
        if (skippedCaller(run, api)) return { ignored: true, reason: 'PR caller did not start a quality execution', runId: run.id };
        const pr = openPullRequest(run, api);
        assertLatestPullRequestRun(run, pr.number, api);
        if (run.status !== 'completed' || run.conclusion !== 'success') {
            return { merge: sha(pr.merge_commit_sha), head: sha(pr.head.sha), base: sha(pr.base.sha),
                pullRequest: integer(pr.number), runId: run.id, attempt: run.run_attempt,
                checks: ownPolicy.ruleset.requiredChecks,
                status: run.status === 'completed' ? 'completed' : 'in_progress',
                conclusion: run.status === 'completed' ? 'failure' : null };
        }
        const evidence = await inspectRun({ repo, runId: run.id, api, core });
        const current = api(`${PREFIX}/pulls/${evidence.pullRequest}`);
        if (current.number !== pr.number || current.state !== 'open' || current.base?.ref !== 'master'
            || current.base?.sha !== evidence.base || current.head?.sha !== evidence.head
            || current.merge_commit_sha !== evidence.merge) fail('PR changed since the tested execution');
        assertLatestPullRequestRun(run, pr.number, api);
        return evidence;
    }
    if (eventName !== 'push' || event.ref !== 'refs/heads/master') fail('unsupported check event');
    const integrated = api(`${PREFIX}/git/commits/${sha(event.after)}`);
    if (integrated.parents?.length !== 2 || integrated.parents[0]?.sha !== event.before) {
        fail('master update is not the expected two-parent merge');
    }
    try {
    const head = sha(integrated.parents[1].sha);
    const runs = completePages(api(`${PREFIX}/actions/workflows/pr-quality-gate.yml/runs?event=pull_request&head_sha=${head}&per_page=100`,
        { pages: true }), 'workflow_runs').sort((a, b) => b.id - a.id);
    const selected = latestExecution(runs, api);
    if (!selected) fail('no PR execution for the integrated head');
    const evidence = await inspectRun({ repo, runId: selected.id, api, core });
    core.verifyIntegratedTree(evidence, integrated);
    const checks = completePages(api(`${PREFIX}/commits/${evidence.merge}/check-runs?filter=latest&per_page=100`,
        { pages: true }), 'check_runs');
    core.verifyAppChecks({ checks, evidence });
    return { ...evidence, integrated: integrated.sha };
    } catch (prError) {
        const full = completePages(api(`${PREFIX}/actions/workflows/quality-gate.yml/runs?event=workflow_dispatch&head_sha=${sha(integrated.sha)}&per_page=100`,
            { pages: true }), 'workflow_runs').filter((run) => run.head_branch === 'master').sort((a, b) => b.id - a.id);
        if (!full.length) fail(`PR evidence unavailable: ${prError.message}; run the existing manual Quality Gate on this master commit`);
        return inspectFullRun({ repo, runId: full[0].id, candidate: integrated.sha, api, core });
    }
}

export function assertCurrentEvidence(evidence, api = github) {
    const run = api(`${PREFIX}/actions/runs/${integer(evidence.runId)}`);
    assertRun(run);
    const pr = api(`${PREFIX}/pulls/${integer(evidence.pullRequest)}`);
    if (pr.state !== 'open' || pr.base?.repo?.id !== REPO_ID || pr.base?.ref !== 'master'
        || pr.base?.sha !== evidence.base || pr.head?.sha !== evidence.head || pr.merge_commit_sha !== evidence.merge
        || run.run_attempt !== evidence.attempt || run.head_sha !== evidence.head
        || (evidence.conclusion === 'success' && (run.status !== 'completed' || run.conclusion !== 'success'))) {
        fail('PR or execution changed before check publication completed');
    }
    assertLatestPullRequestRun(run, evidence.pullRequest, api);
}

export function publishChecks(evidence, token, api = github) {
    if (!token) fail('missing App check token');
    const existing = completePages(api(`${PREFIX}/commits/${sha(evidence.merge)}/check-runs?filter=latest&per_page=100`,
        { pages: true }), 'check_runs');
    for (const name of evidence.checks) {
        const matches = existing.filter((check) => check.name === name && check.app?.id === APP_ID);
        if (matches.length > 1) fail('ambiguous App check identity');
        const body = { name, status: evidence.status,
            details_url: `https://github.com/${REPO}/actions/runs/${integer(evidence.runId)}/attempts/${integer(evidence.attempt)}`,
            output: { title: evidence.conclusion === 'success' ? 'Protected execution verified' : 'Quality Gate has not succeeded',
                summary: evidence.conclusion === 'success'
                    ? `Verified PR head ${evidence.head}, base ${evidence.base}, merge ${evidence.merge} and tree ${evidence.tree}.`
                    : 'The current PR execution must finish successfully before these checks can pass.' } };
        if (evidence.conclusion) body.conclusion = evidence.conclusion;
        const result = matches.length
            ? api(`${PREFIX}/check-runs/${integer(matches[0].id)}`, { method: 'PATCH', body, token })
            : api(`${PREFIX}/check-runs`, { method: 'POST', body: { ...body, head_sha: evidence.merge }, token });
        if (result.app?.id !== APP_ID || result.head_sha !== evidence.merge || result.name !== name) {
            fail('check response does not match the configured authority and merge');
        }
    }
}

async function main() {
    if (process.argv.slice(2).some((arg) => arg !== '--publish')) fail('usage: gate-checks.mjs [--publish]');
    const repo = process.cwd();
    const policy = JSON.parse(fs.readFileSync(path.join(repo, 'scripts/ci/release-gate-policy.json'), 'utf8'));
    const core = await import(policy.gateEpoch === 5
        ? './gate-admission/release-gate-verifier.mjs' : './release-gate-verifier.mjs');
    const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
    const evidence = await checkEvent({ repo, event, eventName: process.env.GITHUB_EVENT_NAME, core });
    if (process.env.GITHUB_OUTPUT && !process.argv.includes('--publish')) {
        fs.appendFileSync(process.env.GITHUB_OUTPUT, `publish=${Boolean(evidence.merge && process.env.GITHUB_EVENT_NAME === 'workflow_run')}\n`, 'utf8');
    }
    if (process.argv.includes('--publish')) {
        if (process.env.GITHUB_EVENT_NAME !== 'workflow_run' || !evidence.merge) fail('master verification does not publish checks');
        assertCurrentEvidence(evidence);
        try {
            publishChecks(evidence, process.env.GATE_APP_TOKEN);
            assertCurrentEvidence(evidence);
        } catch (error) {
            // GitHub 检查写入并非原子操作；发现并发重跑或 PR 更新时撤销本次成功。
            publishChecks({ ...evidence, status: 'completed', conclusion: 'failure' }, process.env.GATE_APP_TOKEN);
            throw error;
        }
    }
    console.log(JSON.stringify(evidence));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main().catch((error) => { console.error(`gate-checks: ${error.message}`); process.exitCode = 1; });
}
