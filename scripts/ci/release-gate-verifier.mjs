#!/usr/bin/env node
'use strict';

import { execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

import { verifyUpgrade } from './gate-upgrade.mjs';
import { credentialSources, privilegedCredentials, workflowCredentialBindings } from './gate-credentials.mjs';

const POLICY = 'scripts/ci/release-gate-policy.json';
const REPOSITORY = 'Sywyar/PixivDownloader';
const REPOSITORY_ID = 1089943605;
const APP_ID = 4837005;
const PR_WORKFLOW = '.github/workflows/pr-quality-gate.yml';
const CHECK_WORKFLOW = '.github/workflows/gate-checks.yml';
const QUALITY_USE = `${REPOSITORY}/.github/workflows/quality-gate.yml@master`;
const BRANCH = 'refs/heads/master';
const CORE = [
    'scripts/ci/release-gate-trust.mjs',
    'scripts/ci/release-gate-verifier.mjs',
    'scripts/ci/resolve-trusted-base.mjs',
    'scripts/ci/gate-upgrade.mjs',
    'scripts/ci/gate-credentials.mjs',
];
const FLOOR = {
    checks: ['java-tests', 'javascript-tests', 'signature-guard', 'trusted-gate-contract',
        'i18n-check', 'check-shared-snippets'],
    roots: ['refs/tags/i18n-gate-epoch-2-root', 'refs/tags/i18n-gate-epoch-3-root',
        'refs/tags/i18n-gate-epoch-4-root', 'refs/tags/release-gate-epoch-5-root',
        'refs/tags/release-gate-epoch-6-root', 'refs/tags/release-gate-epoch-7-root', 'refs/tags/release-gate-epoch-8-root'],
    workflows: {
        '.github/workflows/release.yml': ['validate-release-tag', 'draft-quality-gate',
            'publish-plugins', 'publish-plugin-artifacts', 'build-jar',
            'build-windows-installer', 'release', 'create-draft-release'],
        '.github/workflows/nightly.yml': ['resolve-version', 'publish-plugins',
            'publish-plugin-artifacts', 'build-jar', 'build-windows-installer', 'release-nightly'],
        '.github/workflows/publish-plugins.yml': ['quality-gate', 'publish'],
    },
};
const SHA = /^[0-9a-f]{40}$/;

function fail(message) {
    throw new Error(message);
}

function git(repo, args, encoding = 'utf8') {
    return execFileSync('git', ['-C', repo, ...args], {
        encoding, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    });
}

function resolveCommit(repo, ref, label) {
    const sha = git(repo, ['rev-parse', '--verify', `${ref}^{commit}`]).trim();
    if (!SHA.test(sha)) fail(`${label} is not a commit: ${ref}`);
    return sha;
}

function show(repo, ref, rel, encoding = 'utf8') {
    try {
        return git(repo, ['show', `${ref}:${rel}`], encoding);
    } catch {
        fail(`missing ${rel} at ${ref}`);
    }
}

function json(repo, ref, rel) {
    try {
        return JSON.parse(show(repo, ref, rel));
    } catch (error) {
        if (error instanceof SyntaxError) fail(`invalid JSON in ${rel} at ${ref}: ${error.message}`);
        throw error;
    }
}

function same(a, b) {
    return JSON.stringify(a) === JSON.stringify(b);
}

function list(value) {
    if (value === undefined || value === null) return [];
    return Array.isArray(value) ? value.map(String) : [String(value)];
}

function requireSubset(less, more, label) {
    const actual = new Set(more);
    for (const item of less) if (!actual.has(item)) fail(`${label} removed ${item}`);
}

function exactIdentity(trusted, candidate, label) {
    if (!same(trusted, candidate)) fail(`${label} changed and requires a new Gate Epoch`);
}

function validateRootPolicy(policy) {
    if (policy.schemaVersion !== 1 || !Number.isSafeInteger(policy.gateEpoch) || policy.gateEpoch <= 8
        || !Number.isSafeInteger(policy.contractVersion) || policy.contractVersion < 10 || policy.upgradeProtocol !== 1) {
        fail('upgradable Gate policy identity is invalid');
    }
    if (policy.rootTag !== `refs/tags/release-gate-epoch-${policy.gateEpoch}-root` || policy.protectedBranch !== BRANCH) {
        fail('protected root or branch identity changed');
    }
    if (!same(policy.protectedCore, CORE)) fail('immutable core declaration differs from verifier');
    if (policy.qualityGate?.workflow !== '.github/workflows/quality-gate.yml'
        || policy.qualityGate?.workflowName !== 'Quality Gate') {
        fail('Quality Gate identity is invalid');
    }
    requireSubset(FLOOR.checks,
        policy.qualityGate.requiredJobs, 'Quality Gate jobs');
    requireSubset(['workflow_dispatch', 'workflow_call'],
        policy.qualityGate.requiredTriggers, 'Quality Gate triggers');
    for (const [rel, jobs] of Object.entries(FLOOR.workflows)) {
        if (!policy.workflows?.[rel]) fail(`policy removed required workflow ${rel}`);
        requireSubset(jobs, policy.workflows[rel].requiredJobs, `${rel} jobs`);
    }
    const rules = policy.ruleset || {};
    const contexts = rules.requiredChecks || [];
    if (!Array.isArray(contexts) || contexts.some((context) => typeof context !== 'string' || !context)
        || new Set(contexts).size !== contexts.length) {
        fail('Ruleset required check declarations are invalid or duplicated');
    }
    requireSubset(FLOOR.checks, contexts, 'Ruleset checks');
    requireSubset(contexts, policy.qualityGate.requiredJobs, 'required check execution roles');
    for (const context of contexts) {
        if (rules.requiredCheckSources?.[context] !== APP_ID) {
            fail(`required check ${context} must be bound to the Gate App`);
        }
    }
    for (const root of [...FLOOR.roots, policy.rootTag]) {
        if (!rules.roots?.[root]) fail(`Ruleset policy removed historical root ${root}`);
    }
    if (rules.requireStrict !== true || rules.requirePullRequest !== true
        || !Number.isInteger(rules.minimumApprovals) || rules.minimumApprovals < 0
        || rules.allowBypass !== false || rules.allowDeletion !== false
        || rules.allowNonFastForward !== false) {
        fail('master Ruleset policy is weakened or invalid');
    }
    for (const [root, expected] of Object.entries(rules.roots || {})) {
        if (!root.startsWith('refs/tags/') || expected.allowDeletion !== false
            || expected.allowNonFastForward !== false || expected.allowBypass !== false) {
            fail(`root Ruleset policy is weakened or invalid: ${root}`);
        }
    }
    if (policy.releaseEnvironment !== 'release') fail('release Environment identity changed');
}

function validateMonotonic(trusted, candidate) {
    validateRootPolicy(trusted);
    validateRootPolicy(candidate);
    for (const key of ['schemaVersion', 'gateEpoch', 'contractVersion', 'rootTag',
        'protectedBranch', 'protectedCore', 'releaseEnvironment', 'upgradeProtocol']) {
        exactIdentity(trusted[key], candidate[key], `policy.${key}`);
    }
    for (const key of ['workflow', 'workflowName']) {
        exactIdentity(trusted.qualityGate[key], candidate.qualityGate[key], `qualityGate.${key}`);
    }
    requireSubset(trusted.qualityGate.requiredJobs, candidate.qualityGate.requiredJobs,
        'Quality Gate jobs');
    requireSubset(trusted.qualityGate.requiredTriggers, candidate.qualityGate.requiredTriggers,
        'Quality Gate triggers');
    for (const [rel, spec] of Object.entries(trusted.workflows)) {
        const next = candidate.workflows?.[rel];
        if (!next) fail(`policy removed workflow ${rel}`);
        exactIdentity(spec.workflowName, next.workflowName, `${rel} workflow name`);
        requireSubset(spec.requiredJobs, next.requiredJobs, `${rel} jobs`);
        requireSubset(spec.requiredTriggers, next.requiredTriggers, `${rel} triggers`);
    }
    const oldRules = trusted.ruleset;
    const newRules = candidate.ruleset;
    requireSubset(oldRules.requiredChecks, newRules.requiredChecks, 'Ruleset checks');
    if (newRules.minimumApprovals < oldRules.minimumApprovals) {
        fail('Ruleset minimum approvals decreased');
    }
    for (const key of ['requireStrict', 'requirePullRequest']) {
        if (oldRules[key] === true && newRules[key] !== true) fail(`Ruleset ${key} was disabled`);
    }
    for (const key of ['allowBypass', 'allowDeletion', 'allowNonFastForward']) {
        if (oldRules[key] === false && newRules[key] !== false) fail(`Ruleset ${key} was enabled`);
    }
    for (const [root, expected] of Object.entries(oldRules.roots)) {
        if (!same(expected, newRules.roots?.[root])) fail(`historical root policy changed: ${root}`);
    }
}

function parseWorkflow(YAML, repo, ref, rel) {
    try {
        const doc = YAML.parse(show(repo, ref, rel));
        if (!doc || typeof doc !== 'object' || !doc.jobs || typeof doc.jobs !== 'object') {
            fail(`${rel} is not a workflow document`);
        }
        return doc;
    } catch (error) {
        if (error.message?.startsWith(rel)) throw error;
        fail(`invalid workflow ${rel}: ${error.message}`);
    }
}

function triggers(doc) {
    const value = doc.on ?? doc.true;
    if (typeof value === 'string') return [value];
    if (Array.isArray(value)) return value.map(String);
    return value && typeof value === 'object' ? Object.keys(value) : [];
}

function permissionsWrite(permissions) {
    if (permissions === 'write-all') return true;
    return permissions && typeof permissions === 'object'
        && Object.values(permissions).some((value) => value === 'write');
}

function environmentName(job) {
    return typeof job.environment === 'string' ? job.environment : job.environment?.name;
}

function needs(job) {
    return list(job.needs);
}

function dependsOn(jobs, id, roots, seen = new Set()) {
    if (roots.has(id)) return true;
    if (seen.has(id)) return false;
    seen.add(id);
    const job = jobs[id] || {};
    if (/\b(?:always|failure|cancelled)\s*\(/iu.test(String(job.if || ''))
        || [job, ...(job.steps || [])].some((step) => step['continue-on-error'] !== undefined
            && step['continue-on-error'] !== false)) return false;
    return needs(jobs[id] || {}).some((dep) => dependsOn(jobs, dep, roots, seen));
}

function containsSecret(job) {
    return credentialSources(job).has('secret');
}

function localWorkflow(uses) {
    return typeof uses === 'string' && uses.startsWith('./.github/workflows/') ? uses.slice(2) : null;
}

function validateActionPins(rel, doc) {
    const uses = [];
    for (const job of Object.values(doc.jobs)) {
        if (job.uses) uses.push(job.uses);
        for (const step of job.steps || []) if (step.uses) uses.push(step.uses);
    }
    for (const value of uses) {
        if (rel === PR_WORKFLOW && value === QUALITY_USE) continue;
        if (typeof value !== 'string') fail(`${rel} has a nonliteral action reference`);
        if (value.startsWith('./')) continue;
        if (/^docker:\/\/.+@sha256:[0-9a-f]{64}$/u.test(value)) continue;
        if (!/@[0-9a-f]{40}$/u.test(value)) fail(`${rel} uses an external action without a full SHA: ${value}`);
    }
}

function validateQualityJobs(policy, doc) {
    for (const id of policy.qualityGate.requiredJobs) {
        const job = doc.jobs[id];
        if (!job || job.uses || job.if !== undefined || job.strategy !== undefined
            || (job.name !== undefined && job.name !== id)) {
            fail(`Quality Gate role ${id} must be unconditional and have a stable check identity`);
        }
    }
}

export function validatePullRequestCaller(doc) {
    if (!doc || doc.name !== 'Pull Request Quality Gate'
        || !same(triggers(doc), ['pull_request'])) fail('PR caller must use pull_request');
    const event = doc.on.pull_request || {};
    requireSubset(['opened', 'reopened', 'synchronize', 'edited'], event.types || [], 'PR events');
    if (!same(Object.keys(doc.jobs), ['quality-gate'])) fail('PR caller must only invoke Quality Gate');
    const call = doc.jobs['quality-gate'];
    if (call.uses !== QUALITY_USE || Object.keys(call).some((key) => !['uses', 'if'].includes(key))) {
        fail('PR caller must invoke the protected Quality Gate without candidate inputs or steps');
    }
    if (!same(doc.permissions, { contents: 'read' }) || containsSecret(doc)) {
        fail('PR caller may only read contents');
    }
}

function validateCheckPublisher(doc) {
    if (!doc || doc.name !== 'Gate Checks') fail('missing protected check publisher');
    if (triggers(doc).some((event) => !['workflow_run', 'push'].includes(event))
        || !same([...(doc.on.workflow_run?.workflows || [])].sort(), ['Pull Request Quality Gate', 'Quality Gate'])
        || !same([...(doc.on.workflow_run?.types || [])].sort(), ['completed', 'in_progress'])
        || !same(doc.on.push?.branches, ['master'])) {
        fail('check credentials are restricted to protected workflow completion and master push');
    }
    // 此例外仅允许 App 写检查；产品凭据和 GITHUB_TOKEN 写权限仍须经过完整发布门禁。
    const publicDocument = structuredClone(doc);
    for (const job of Object.values(publicDocument.jobs)) {
        if (permissionsWrite(job.permissions === undefined ? doc.permissions : job.permissions)) {
            fail('check publisher cannot grant GITHUB_TOKEN write permissions');
        }
        for (const step of job.steps || []) {
            if (!step.uses?.startsWith('actions/create-github-app-token@')) continue;
            const inputs = step.with || {};
            if (inputs['client-id'] !== 'Iv23lixorUs94dx1xEvB' || inputs['permission-checks'] !== 'write'
                || Object.keys(inputs).some((key) => !['client-id', 'private-key', 'permission-checks'].includes(key))) {
                fail('check publisher may only mint the configured checks-only App token');
            }
            delete inputs['private-key'];
        }
    }
    if (containsSecret(publicDocument)) {
        fail('check publisher cannot consume other release credentials');
    }
}

function workflowSecurityProblems(rel, doc, policy, providerPaths) {
    const problems = [];
    if (!Object.prototype.hasOwnProperty.call(doc, 'permissions') || doc.permissions === null) {
        problems.push(`${rel} must declare top-level permissions`);
    }
    if (triggers(doc).includes('pull_request_target')) {
        problems.push(`${rel} cannot give PR execution the protected branch cache scope`);
    }
    for (const permissions of [doc.permissions, ...Object.values(doc.jobs).map((job) => job.permissions)]) {
        if (permissions === undefined) continue;
        if (permissions === 'read-all' || permissions === 'write-all') continue;
        if (!permissions || typeof permissions !== 'object' || Array.isArray(permissions)
            || Object.values(permissions).some((value) => !['read', 'write', 'none'].includes(value))) {
            problems.push(`${rel} permissions must be explicit literal access levels`);
        }
    }
    const jobs = doc.jobs;
    const credentialBindings = workflowCredentialBindings(doc);
    const roots = new Set(Object.entries(jobs)
        .filter(([, job]) => providerPaths.has(localWorkflow(job.uses)))
        .map(([id]) => id));
    for (const [id, job] of Object.entries(jobs)) {
        if (job.secrets === 'inherit') problems.push(`${rel} job ${id} uses secrets: inherit`);
        const condition = String(job.if || '');
        const provider = roots.has(id);
        const sensitive = privilegedCredentials([doc.env, job], doc.permissions, job.permissions, credentialBindings.get(id))
            || environmentName(job) !== undefined;
        const required = provider || (rel === policy.qualityGate.workflow
            && policy.qualityGate.requiredJobs.some((role) => dependsOn(jobs, role, new Set([id]))));
        if ((required || sensitive) && (/\b(?:always|failure|cancelled)\s*\(/iu.test(condition)
            || [job, ...(job.steps || [])].some((step) => step['continue-on-error'] !== undefined
                && step['continue-on-error'] !== false))) {
            problems.push(`${rel} job ${id} can bypass a failed dependency or suppress a failure`);
        }
        if (provider && privilegedCredentials([doc.env, job], doc.permissions, job.permissions, credentialBindings.get(id))) {
            problems.push(`${rel} job ${id} cannot forward credentials into the quality provider`);
        }
        if (!sensitive || provider) continue;
        if (environmentName(job) !== policy.releaseEnvironment) {
            problems.push(`${rel} job ${id} uses privileged capabilities outside the release Environment`);
        }
        if (rel !== CHECK_WORKFLOW && !dependsOn(jobs, id, roots)) {
            problems.push(`${rel} job ${id} can use privileged capabilities before Quality Gate success`);
        }
    }
    return problems;
}

function validateWorkflows(repo, ref, policy) {
    const require = createRequire(import.meta.url);
    let YAML;
    try {
        YAML = require('yaml');
    } catch {
        fail('the installed yaml dependency is required to inspect workflows');
    }
    const names = git(repo, ['ls-tree', '-r', '--name-only', ref, '--', '.github/workflows'])
        .split(/\r?\n/u).filter((name) => /\.ya?ml$/u.test(name));
    const expand = (steps, seen = new Set()) => {
        for (const step of steps || []) {
            if (!step.uses?.startsWith('./')) continue;
            const action = step.uses.slice(2);
            if (!action || path.posix.isAbsolute(action) || path.posix.normalize(action) !== action
                || action.startsWith('../') || action.includes('\\') || seen.has(action)) {
                fail('invalid or recursive local action: ' + action);
            }
            const files = git(repo, ['ls-tree', '--name-only', ref, '--', action + '/action.yml', action + '/action.yaml'])
                .trim().split(/\r?\n/u).filter(Boolean);
            if (files.length !== 1) fail('missing or ambiguous local action: ' + action);
            if (!/^100(?:644|755) blob /u.test(git(repo, ['ls-tree', ref, '--', files[0]]))) {
                fail('local action descriptor must be a regular file: ' + action);
            }
            const definition = YAML.parse(show(repo, ref, files[0]));
            if (definition?.runs?.using !== 'composite') continue;
            const steps = definition.runs.steps;
            validateActionPins(files[0], { jobs: { action: { steps } } });
            expand(steps, new Set([...seen, action]));
            step.gateLocalAction = definition;
        }
    };
    const docs = new Map(names.map((rel) => {
        const doc = parseWorkflow(YAML, repo, ref, rel);
        for (const job of Object.values(doc.jobs)) expand(job.steps);
        return [rel, doc];
    }));
    const required = new Map([[policy.qualityGate.workflow, policy.qualityGate],
        ...Object.entries(policy.workflows)]);
    for (const [rel, spec] of required) {
        const doc = docs.get(rel);
        if (!doc) fail(`missing required workflow ${rel}`);
        if (doc.name !== spec.workflowName) fail(`${rel} workflow name changed`);
        requireSubset(spec.requiredTriggers, triggers(doc), `${rel} triggers`);
        requireSubset(spec.requiredJobs, Object.keys(doc.jobs), `${rel} jobs`);
    }
    const quality = docs.get(policy.qualityGate.workflow);
    if (triggers(quality).some((event) => !['workflow_call', 'workflow_dispatch'].includes(event))) {
        fail('full Quality Gate must run through the PR caller or an explicit reusable/manual call');
    }
    validateQualityJobs(policy, quality);
    validatePullRequestCaller(docs.get(PR_WORKFLOW));
    validateCheckPublisher(docs.get(CHECK_WORKFLOW));
    const providerPaths = new Set([policy.qualityGate.workflow]);
    let changed = true;
    while (changed) {
        changed = false;
        for (const [rel, doc] of docs) {
            if (providerPaths.has(rel) || !triggers(doc).includes('workflow_call')) continue;
            // 可复用 workflow 整体成功必须意味着 QG 已执行成功；仅包含一个可跳过的调用不够。
            const unconditional = (id, seen = new Set()) => {
                const job = doc.jobs[id];
                if (!job || seen.has(id) || job.if !== undefined
                    || (job.uses && !providerPaths.has(localWorkflow(job.uses)))) return false;
                return needs(job).every((dep) => unconditional(dep, new Set([...seen, id])));
            };
            const hasProvider = Object.entries(doc.jobs)
                .some(([id, job]) => providerPaths.has(localWorkflow(job.uses)) && unconditional(id));
            if (hasProvider && workflowSecurityProblems(rel, doc, policy, providerPaths).length === 0) {
                providerPaths.add(rel);
                changed = true;
            }
        }
    }
    for (const [rel, doc] of docs) {
        validateActionPins(rel, doc);
        const problems = workflowSecurityProblems(rel, doc, policy, providerPaths);
        if (problems.length > 0) fail(problems.join('\n'));
    }
}

function validateCore(repo, trusted, candidate) {
    for (const rel of CORE) {
        const before = git(repo, ['ls-tree', trusted, '--', rel]).trim();
        const after = git(repo, ['ls-tree', candidate, '--', rel]).trim();
        if (!before || before !== after) fail(`immutable release gate core changed: ${rel}`);
    }
}

function validateAncestry(repo, trusted, candidate) {
    const root = resolveCommit(repo, json(repo, trusted, POLICY).rootTag, 'Gate root');
    if (trusted === candidate) fail('trusted base must be a strict predecessor of the candidate');
    for (const [ancestor, descendant, label] of [
        [root, trusted, 'root to trusted base'],
        [trusted, candidate, 'trusted base to candidate'],
        [trusted, 'refs/remotes/origin/master', 'trusted base to protected branch'],
    ]) {
        const result = spawnSync('git', ['-C', repo, 'merge-base', '--is-ancestor', ancestor, descendant],
            { windowsHide: true, stdio: 'ignore' });
        if (result.status !== 0) fail(`invalid protected predecessor ancestry: ${label}`);
    }
}

function validateSignatureBoundary(repo, candidate) {
    const markers = ['DouyinX' + 'BogusSigner', 'DouyinA' + 'BogusSigner', 'Douyin' + 'Sm3',
        'generateChrome' + 'Fingerprint', 'Dkdpgh4' + 'ZKs', 'Dkdpgh2' + 'Zms', 'ckdp1h4' + 'ZKs'];
    const result = spawnSync('git', ['-C', repo, 'grep', '-nE', markers.join('|'), candidate, '--', '.',
        ':(exclude)scripts/hooks/pre-push-guard.sh', ':(exclude)scripts/i18n/test/hooks.test.mjs'],
    { encoding: 'utf8', windowsHide: true });
    if (result.status === 0 && result.stdout.trim()) {
        fail(`detected reverse-engineered Douyin signature code:\n${result.stdout.trim()}`);
    }
    if (result.status !== 0 && result.status !== 1) fail('cannot inspect signature boundary');
}

function parseArgs(argv) {
    const out = { repo: '.', candidate: null, trusted: null, invariants: false,
        signature: false, version: false, localFeedback: false };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        if (arg === '--repo-root') out.repo = argv[++i];
        else if (arg === '--candidate-ref') out.candidate = argv[++i];
        else if (arg === '--trusted-ref') out.trusted = argv[++i];
        else if (arg === '--invariants') out.invariants = true;
        else if (arg === '--signature') out.signature = true;
        else if (arg === '--version') out.version = true;
        else if (arg === '--local-feedback') out.localFeedback = true;
        else fail(`unknown argument: ${arg}`);
    }
    return out;
}

export function verifyCandidate({ repo, trusted, candidate, invariants = false,
    signature = false, localFeedback = false }) {
    const candidatePolicy = json(repo, candidate, POLICY);
    validateRootPolicy(candidatePolicy);
    if (!invariants) {
        if (json(repo, trusted, POLICY).gateEpoch !== candidatePolicy.gateEpoch) {
            verifyUpgrade({ repo, trusted, candidate, localFeedback });
        } else {
            validateMonotonic(json(repo, trusted, POLICY), candidatePolicy);
            validateCore(repo, trusted, candidate);
            validateAncestry(repo, trusted, candidate);
        }
    }
    if (!signature) validateWorkflows(repo, candidate, candidatePolicy);
    validateSignatureBoundary(repo, candidate);
    return candidatePolicy;
}

export function parseExecutionProof(log) {
    const records = String(log).split(/\r?\n/u).map((line) => line.replace(
        /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z\s+/u, ''))
        .filter((line) => line.startsWith('GATE_EXECUTION '));
    if (records.length !== 1) fail('missing or ambiguous protected execution record');
    const proof = JSON.parse(records[0].slice('GATE_EXECUTION '.length));
    if (proof.schemaVersion !== 1 || proof.repository !== REPOSITORY
        || proof.repositoryId !== String(REPOSITORY_ID) || proof.event !== 'pull_request'
        || proof.baseRef !== 'master' || !SHA.test(proof.merge) || !SHA.test(proof.base)
        || !SHA.test(proof.head) || !/^[1-9][0-9]*$/u.test(proof.pullRequest)
        || !/^[1-9][0-9]*$/u.test(proof.runId) || !/^[1-9][0-9]*$/u.test(proof.attempt)) {
        fail('execution record does not identify a master PR merge');
    }
    return proof;
}

export function verifyRunIdentity({ run, merge, caller, protectedBase, proof }) {
    validatePullRequestCaller(caller);
    if (run.repository?.id !== REPOSITORY_ID || run.repository?.full_name !== REPOSITORY
        || run.event !== 'pull_request' || run.path?.split('@')[0] !== PR_WORKFLOW
        || run.name !== caller.name || !Number.isSafeInteger(run.id) || run.id <= 0
        || !Number.isSafeInteger(run.run_attempt) || run.run_attempt <= 0) {
        fail('run is not a pull request execution from the expected repository and workflow');
    }
    if (!SHA.test(run.head_sha) || proof.merge !== merge.sha || proof.head !== run.head_sha
        || proof.base !== protectedBase
        || !SHA.test(merge.tree?.sha) || merge.parents?.length !== 2
        || merge.parents[1]?.sha !== run.head_sha || merge.parents[0]?.sha !== protectedBase) {
        fail('run head, tested merge and protected base do not agree');
    }
    const use = caller.jobs['quality-gate'].uses;
    const sources = run.referenced_workflows;
    const qualitySources = (sources || []).filter((source) => source.path === use);
    if (!Array.isArray(sources) || qualitySources.length !== 1
        || qualitySources[0].sha !== protectedBase || qualitySources[0].ref !== 'refs/heads/master'
        || proof.runId !== String(run.id) || Number(proof.attempt) > run.run_attempt) {
        fail('Quality Gate execution did not use the tested protected base');
    }
    return { head: run.head_sha, base: protectedBase, merge: merge.sha, tree: merge.tree.sha,
        runId: run.id, attempt: run.run_attempt };
}

export function verifyRunResults({ run, jobs, requiredJobs = FLOOR.checks, expectedJobs = requiredJobs,
    jobPrefix = 'quality-gate / ' }) {
    if (run.status !== 'completed' || run.conclusion !== 'success') {
        fail('Quality Gate run has not completed successfully');
    }
    if (!Array.isArray(jobs)) {
        fail('missing effective jobs');
    }
    const ids = new Set();
    for (const job of jobs) {
        if (!Number.isSafeInteger(job.id) || job.id <= 0 || ids.has(job.id)
            || job.run_id !== run.id || job.head_sha !== run.head_sha
            || job.status !== 'completed' || !job.name?.startsWith(jobPrefix)) {
            fail('job results are duplicated, foreign or incomplete');
        }
        ids.add(job.id);
    }
    requireSubset(requiredJobs, expectedJobs, 'protected Quality Gate roles');
    for (const role of requiredJobs) {
        const matches = jobs.filter((job) => job.name === `${jobPrefix}${role}`);
        if (matches.length !== 1) fail(`missing or ambiguous effective result for ${role}`);
        if (matches[0].conclusion !== 'success') fail(`required role ${role} is skipped or unsuccessful`);
    }
}

export function verifyIntegratedTree(evidence, integrated) {
    if (!SHA.test(integrated.sha) || integrated.parents?.length !== 2
        || integrated.parents[0]?.sha !== evidence.base || integrated.parents[1]?.sha !== evidence.head
        || integrated.tree?.sha !== evidence.tree) {
        fail('protected merge commit does not preserve the tested parents and tree');
    }
    return integrated.sha;
}

export function verifyAppChecks({ checks, evidence, requiredChecks = FLOOR.checks }) {
    const details = `https://github.com/${REPOSITORY}/actions/runs/${evidence.runId}/attempts/${evidence.attempt}`;
    for (const name of requiredChecks) {
        const matching = checks.filter((check) => check.name === name && check.app?.id === APP_ID);
        if (matching.length !== 1 || matching[0].head_sha !== evidence.merge
            || matching[0].status !== 'completed' || matching[0].conclusion !== 'success'
            || matching[0].details_url !== details) fail(`missing current App evidence for ${name}`);
    }
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (args.version) {
        console.log('release-gate-verifier upgrade-protocol=1 schema=1');
        return;
    }
    const repo = path.resolve(args.repo);
    const candidate = resolveCommit(repo, args.candidate || 'HEAD', 'candidate');
    const trusted = args.invariants ? null : resolveCommit(repo, args.trusted, 'trusted base');
    verifyCandidate({ ...args, repo, trusted, candidate });
    console.log(`TRUSTED RELEASE GATE ${json(repo, candidate, POLICY).gateEpoch} OK (${candidate})`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        main();
    } catch (error) {
        console.error(`release-gate-verifier: ${error.message}`);
        process.exitCode = 1;
    }
}
