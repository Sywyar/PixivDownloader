#!/usr/bin/env node
'use strict';

import { execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import { createRequire } from 'node:module';

const POLICY = 'scripts/ci/release-gate-policy.json';
const ROOT_TAG = 'refs/tags/release-gate-epoch-5-root';
const BRANCH = 'refs/heads/master';
const CORE = [
    'scripts/ci/release-gate-trust.mjs',
    'scripts/ci/release-gate-verifier.mjs',
    'scripts/ci/resolve-trusted-base.mjs',
];
const FLOOR = {
    checks: ['java-tests', 'javascript-tests', 'signature-guard', 'trusted-gate-contract',
        'i18n-check', 'check-shared-snippets'],
    roots: ['refs/tags/i18n-gate-epoch-2-root', 'refs/tags/i18n-gate-epoch-3-root',
        'refs/tags/i18n-gate-epoch-4-root', ROOT_TAG],
    workflows: {
        '.github/workflows/shared-snippets-check.yml': ['check-shared-snippets'],
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
    if (policy.schemaVersion !== 1 || policy.gateEpoch !== 5 || policy.contractVersion !== 6) {
        fail('Epoch 5 policy identity is invalid');
    }
    if (policy.rootTag !== ROOT_TAG || policy.protectedBranch !== BRANCH) {
        fail('protected root or branch identity changed');
    }
    if (!same(policy.protectedCore, CORE)) fail('immutable core declaration differs from verifier');
    if (policy.qualityGate?.workflow !== '.github/workflows/quality-gate.yml'
        || policy.qualityGate?.workflowName !== 'Quality Gate') {
        fail('Quality Gate identity is invalid');
    }
    requireSubset(['java-tests', 'javascript-tests', 'signature-guard',
        'trusted-gate-contract', 'i18n-check'],
        policy.qualityGate.requiredJobs, 'Quality Gate jobs');
    requireSubset(['push', 'pull_request', 'merge_group', 'workflow_dispatch', 'workflow_call'],
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
    for (const root of FLOOR.roots) {
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
        'protectedBranch', 'protectedCore', 'releaseEnvironment']) {
        exactIdentity(trusted[key], candidate[key], `policy.${key}`);
    }
    for (const key of ['workflow', 'workflowName']) {
        exactIdentity(trusted.qualityGate[key], candidate.qualityGate[key], `qualityGate.${key}`);
    }
    requireSubset(trusted.qualityGate.requiredJobs, candidate.qualityGate.requiredJobs,
        'Quality Gate jobs');
    requireSubset(trusted.qualityGate.requiredTriggers, candidate.qualityGate.requiredTriggers,
        'Quality Gate triggers');
    requireSubset(candidate.qualityGate.allowedPushExclusions,
        trusted.qualityGate.allowedPushExclusions, 'Quality Gate push exclusions');
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
    return needs(jobs[id] || {}).some((dep) => dependsOn(jobs, dep, roots, seen));
}

function containsSecret(job) {
    const expressions = JSON.stringify(job).match(/\$\{\{.*?\}\}/gu) || [];
    return expressions.some((expression) => /\bsecrets\b/iu.test(expression)
        || /\bgithub\s*(?:\.\s*token|\[\s*\\?['"]token\\?['"]\s*\])/iu.test(expression));
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
        if (typeof value !== 'string' || value.startsWith('./') || value.startsWith('docker://')) continue;
        if (!/@[0-9a-f]{40}$/u.test(value)) fail(`${rel} uses an external action without a full SHA: ${value}`);
    }
}

function validateQualityBootstrap(policy, doc) {
    for (const id of ['signature-guard', 'trusted-gate-contract']) {
        const body = (doc.jobs[id]?.steps || []).map((step) => String(step.run || '')).join('\n');
        if (!body.includes('resolve-trusted-base.mjs') || !body.includes('git show "$BASE_SHA:$rel"')
            || !body.includes('release-gate-verifier.mjs') || !body.includes('--candidate-ref')) {
            fail(`Quality Gate job ${id} no longer executes the protected predecessor verifier`);
        }
    }
    const signature = (doc.jobs['signature-guard']?.steps || [])
        .map((step) => String(step.run || '')).join('\n');
    if (!signature.includes('--signature')) fail('signature-guard no longer uses signature mode');
}

function workflowSecurityProblems(rel, doc, policy, providerPaths) {
    const problems = [];
    if (!Object.prototype.hasOwnProperty.call(doc, 'permissions') || doc.permissions === null) {
        problems.push(`${rel} must declare top-level permissions`);
    }
    if (/"continue-on-error":true/u.test(JSON.stringify(doc))) {
        problems.push(`${rel} contains continue-on-error: true`);
    }
    const jobs = doc.jobs;
    const roots = new Set(Object.entries(jobs)
        .filter(([, job]) => providerPaths.has(localWorkflow(job.uses)))
        .map(([id]) => id));
    for (const [id, job] of Object.entries(jobs)) {
        if (job.secrets === 'inherit') problems.push(`${rel} job ${id} uses secrets: inherit`);
        const condition = String(job.if || '');
        if (/\b(?:always|failure|cancelled)\s*\(/iu.test(condition)) {
            problems.push(`${rel} job ${id} can bypass a failed dependency`);
        }
        const provider = roots.has(id);
        const sensitive = permissionsWrite(job.permissions === undefined ? doc.permissions : job.permissions)
            || containsSecret(job) || environmentName(job) !== undefined;
        if (!sensitive || provider) continue;
        if (environmentName(job) !== policy.releaseEnvironment) {
            problems.push(`${rel} job ${id} uses privileged capabilities outside the release Environment`);
        }
        if (!dependsOn(jobs, id, roots)) {
            problems.push(`${rel} job ${id} can use privileged capabilities before Quality Gate success`);
        }
    }
    return problems;
}

function validateWorkflows(repo, ref, policy) {
    const require = createRequire(process.env.TRUSTED_GATE_PACKAGE_JSON || path.join(repo, 'package.json'));
    let YAML;
    try {
        YAML = require('yaml');
    } catch {
        fail('the installed yaml dependency is required to inspect workflows');
    }
    const names = git(repo, ['ls-tree', '-r', '--name-only', ref, '--', '.github/workflows'])
        .split(/\r?\n/u).filter((name) => /\.ya?ml$/u.test(name));
    const docs = new Map(names.map((rel) => [rel, parseWorkflow(YAML, repo, ref, rel)]));
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
    const push = (quality.on ?? quality.true)?.push;
    if (!same(list(push?.['branches-ignore']), policy.qualityGate.allowedPushExclusions)) {
        fail('Quality Gate push exclusions differ from policy');
    }
    validateQualityBootstrap(policy, quality);
    const providerPaths = new Set([policy.qualityGate.workflow]);
    let changed = true;
    while (changed) {
        changed = false;
        for (const [rel, doc] of docs) {
            if (providerPaths.has(rel) || !triggers(doc).includes('workflow_call')) continue;
            const hasProvider = Object.values(doc.jobs)
                .some((job) => providerPaths.has(localWorkflow(job.uses)));
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
    const root = resolveCommit(repo, ROOT_TAG, 'Epoch 5 root');
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
        signature: false, version: false };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        if (arg === '--repo-root') out.repo = argv[++i];
        else if (arg === '--candidate-ref') out.candidate = argv[++i];
        else if (arg === '--trusted-ref') out.trusted = argv[++i];
        else if (arg === '--invariants') out.invariants = true;
        else if (arg === '--signature') out.signature = true;
        else if (arg === '--version') out.version = true;
        else fail(`unknown argument: ${arg}`);
    }
    return out;
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (args.version) {
        console.log('release-gate-verifier epoch=5 contract=6 schema=1');
        return;
    }
    const repo = path.resolve(args.repo);
    const candidate = resolveCommit(repo, args.candidate || 'HEAD', 'candidate');
    const candidatePolicy = json(repo, candidate, POLICY);
    validateRootPolicy(candidatePolicy);
    if (!args.invariants) {
        const trusted = resolveCommit(repo, args.trusted, 'trusted base');
        validateMonotonic(json(repo, trusted, POLICY), candidatePolicy);
        validateCore(repo, trusted, candidate);
        validateAncestry(repo, trusted, candidate);
    }
    if (!args.signature) validateWorkflows(repo, candidate, candidatePolicy);
    validateSignatureBoundary(repo, candidate);
    console.log(`TRUSTED RELEASE GATE 5 OK (${candidate})`);
}

try {
    main();
} catch (error) {
    console.error(`release-gate-verifier: ${error.message}`);
    process.exitCode = 1;
}
