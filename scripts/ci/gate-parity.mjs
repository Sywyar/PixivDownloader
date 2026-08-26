#!/usr/bin/env node
'use strict';

import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const VERSION = '5';
const SHA_RE = /^[0-9a-f]{40}$/;
const POLICY_REL = 'scripts/i18n/gate-policy.json';
const INVARIANTS_REL = 'scripts/ci/gate-invariants.json';
const RULESET_REL = 'scripts/ci/github-ruleset-invariants.json';
const CONTRACT_REL = 'scripts/i18n/gate-contract.mjs';
const ROOT_TAG = 'refs/tags/i18n-gate-epoch-4-root';

function die(message) {
    throw new Error(message);
}

function git(root, args, options = {}) {
    return execFileSync('git', args, {
        cwd: root,
        encoding: options.encoding === null ? null : 'utf8',
        stdio: ['pipe', 'pipe', 'pipe'],
        maxBuffer: 128 * 1024 * 1024,
        ...options,
    });
}

function parseArgs(argv) {
    const args = {
        repoRoot: null, trustedDir: null, trustedRef: null, candidateRef: null,
        candidateSnapshot: null, reportRoot: null, invariants: false, signature: false, version: false,
    };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        const value = () => argv[++i];
        if (arg === '--repo-root') args.repoRoot = value();
        else if (arg === '--trusted-dir') args.trustedDir = value();
        else if (arg === '--trusted-ref') args.trustedRef = value();
        else if (arg === '--candidate-ref') args.candidateRef = value();
        else if (arg === '--candidate-snapshot') args.candidateSnapshot = value();
        else if (arg === '--report-root') args.reportRoot = value();
        else if (arg === '--invariants') args.invariants = true;
        else if (arg === '--signature') args.signature = true;
        else if (arg === '--version') args.version = true;
        else die('unknown argument: ' + arg);
    }
    if (args.version) return args;
    if (!args.repoRoot) die('--repo-root <path> is required');
    if (!args.candidateRef && args.candidateSnapshot !== 'index') {
        die('--candidate-ref <sha> or --candidate-snapshot index is required');
    }
    if (args.candidateRef && !SHA_RE.test(args.candidateRef)) {
        die('--candidate-ref must be a full commit SHA');
    }
    if (!args.invariants && !args.trustedDir) die('--trusted-dir <path> is required');
    return args;
}

function showPath(root, ref, rel) {
    try {
        return git(root, ['show', `${ref}:${rel}`], { encoding: null });
    } catch {
        return null;
    }
}

function materialize(root, ref, paths) {
    const out = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-gate-candidate-'));
    for (const rel of paths) {
        const bytes = showPath(root, ref, rel);
        if (bytes === null) continue;
        const file = path.join(out, ...rel.split('/'));
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, bytes);
    }
    return out;
}

function materializeIndex(root, paths) {
    const out = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-gate-index-'));
    for (const rel of paths) {
        let bytes;
        try {
            bytes = git(root, ['show', `:${rel}`], { encoding: null });
        } catch {
            continue;
        }
        const file = path.join(out, ...rel.split('/'));
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, bytes);
    }
    return out;
}

function readJson(root, rel) {
    const file = path.join(root, ...rel.split('/'));
    if (!fs.existsSync(file)) die(`required file is missing: ${rel}`);
    try {
        return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (error) {
        die(`${rel} is not valid JSON: ${error.message}`);
    }
}

function same(a, b) {
    return JSON.stringify(a) === JSON.stringify(b);
}

function list(value) {
    if (value === undefined || value === null) return [];
    return Array.isArray(value) ? value.map(String) : [String(value)];
}

function triggers(doc) {
    const value = doc.on ?? doc.true;
    if (typeof value === 'string') return [value];
    if (!value || typeof value !== 'object') return [];
    return Object.keys(value);
}

function needs(job) {
    return list(job?.needs);
}

function workflow(root, rel, YAML) {
    const file = path.join(root, ...rel.split('/'));
    if (!fs.existsSync(file)) die(`required workflow is missing: ${rel}`);
    try {
        return YAML.parse(fs.readFileSync(file, 'utf8'));
    } catch (error) {
        die(`${rel} is not valid YAML: ${error.message}`);
    }
}

function containsForbidden(value, forbidden) {
    if (typeof value === 'string') return forbidden.some((entry) => value.includes(entry));
    if (Array.isArray(value)) return value.some((entry) => containsForbidden(entry, forbidden));
    if (value && typeof value === 'object') {
        return Object.entries(value).some(([key, entry]) => key === 'continue-on-error'
            || key === 'continueOnError' || containsForbidden(entry, forbidden));
    }
    return false;
}

function hasDependency(jobs, start, targets, seen = new Set()) {
    if (targets.has(start)) return true;
    if (seen.has(start)) return false;
    seen.add(start);
    return needs(jobs[start]).some((dependency) => hasDependency(jobs, dependency, targets, seen));
}

function permissionWrites(permissions) {
    if (permissions === 'write-all') return true;
    return permissions && typeof permissions === 'object'
        && Object.values(permissions).some((value) => value === 'write');
}

function environmentName(job) {
    if (typeof job?.environment === 'string') return job.environment;
    return job?.environment?.name || null;
}

function validatePolicy(candidateRoot, policy, invariants) {
    if (policy.schemaVersion !== 4 || policy.gateEpoch !== 4 || policy.contractVersion !== 5) {
        die('gate policy must be schema 4, Epoch 4 and contract 5');
    }
    if (invariants.schemaVersion !== 1 || invariants.gateEpoch !== 4 || invariants.contractVersion !== 5) {
        die('gate invariants must be schema 1, Epoch 4 and contract 5');
    }
    if (!same(policy.minimumTrustedVerifier, {
        contractVersion: 5,
        schemaVersion: 4,
        requiredFiles: invariants.protectedPaths,
    })) {
        die('minimumTrustedVerifier must exactly name the protected verifier core');
    }
    for (const rel of invariants.protectedPaths) {
        if (!fs.existsSync(path.join(candidateRoot, ...rel.split('/')))) {
            die(`protected verifier file is missing: ${rel}`);
        }
    }
    if (!same(policy.requiredWorkflowJobs, invariants.qualityGate.requiredJobs)) {
        die('policy and Quality Gate required jobs differ');
    }
    const requiredFiles = Object.keys(invariants.workflows);
    if (!same(policy.requiredWorkflowFiles, [invariants.qualityGate.workflow, ...requiredFiles])) {
        die('policy and invariant workflow files differ');
    }
}

function validateRuleset(candidateRoot) {
    const rules = readJson(candidateRoot, RULESET_REL);
    const expectedMaster = {
        requiredChecks: ['java-tests', 'javascript-tests', 'signature-guard',
            'trusted-gate-contract', 'i18n-check', 'check-shared-snippets'],
        requireStrict: true,
        requirePullRequest: true,
        requiredApprovals: 0,
        allowBypass: false,
        allowDeletion: false,
        allowNonFastForward: false,
    };
    if (!same(rules.master, expectedMaster)) die('master Ruleset invariants were weakened or renamed');
    const rootRule = { allowDeletion: false, allowNonFastForward: false, allowBypass: false };
    if (!same(rules['i18n-gate-epoch-4-root'], rootRule)) {
        die('Epoch 4 root tag protection is incomplete');
    }
}

function validateBootstrap(doc, invariants) {
    const jobs = doc.jobs || {};
    for (const id of [invariants.qualityGate.trustedJob, invariants.qualityGate.signatureJob]) {
        const scripts = (jobs[id]?.steps || []).map((step) => String(step.run || '')).join('\n');
        if (!scripts.includes('resolve-trusted-base.mjs') || !scripts.includes('git show "$BASE_SHA:$rel"')
            || !scripts.includes('gate-parity.mjs') || !scripts.includes('--candidate-ref')) {
            die(`Quality Gate job ${id} no longer executes the protected predecessor verifier`);
        }
    }
    const signature = (jobs[invariants.qualityGate.signatureJob]?.steps || [])
        .map((step) => String(step.run || '')).join('\n');
    if (!signature.includes('--signature')) die('signature-guard no longer uses the protected signature mode');
}

function validatePublication(rel, doc, invariants) {
    const jobs = doc.jobs || {};
    const gateRoots = rel.endsWith('/publish-plugins.yml')
        ? new Set(['quality-gate'])
        : rel.endsWith('/release.yml')
            ? new Set(['draft-quality-gate', 'publish-plugins'])
            : new Set(['publish-plugins']);
    for (const [id, job] of Object.entries(jobs)) {
        const serialized = JSON.stringify(job);
        const sensitive = serialized.includes('secrets.') || permissionWrites(job.permissions);
        if (!sensitive) continue;
        if (environmentName(job) !== invariants.releaseEnvironment) {
            die(`${rel} job ${id} uses credentials or write permission outside the release Environment`);
        }
        if (!hasDependency(jobs, id, gateRoots)) {
            die(`${rel} job ${id} can use credentials before Quality Gate success`);
        }
    }
    const serialized = JSON.stringify(doc);
    if (/PLUGIN_SIGNING_PRIVATE_KEY_PEM(?!_BASE64)/.test(serialized)) {
        die(`${rel} still accepts the plaintext plugin signing key`);
    }
    if (rel.endsWith('/release.yml') || rel.endsWith('/nightly.yml')) {
        if (!serialized.includes('UPDATE_SIGNING_PRIVATE_KEY_PEM_BASE64')) {
            die(`${rel} does not use the dedicated update signing key`);
        }
    }
    if (rel.endsWith('/build-stable-ffmpeg.yml')) {
        if (!serialized.includes('FFMPEG_SIGNING_PRIVATE_KEY_PEM_BASE64')
            || serialized.includes('PLUGIN_SIGNING_PRIVATE_KEY_PEM_BASE64')) {
            die(`${rel} does not exclusively use the dedicated FFmpeg signing key`);
        }
    }
}

function validateWorkflows(candidateRoot, invariants, repoRoot) {
    const require = createRequire(path.join(repoRoot, 'package.json'));
    const YAML = require('yaml');
    const all = [[invariants.qualityGate.workflow, invariants.qualityGate],
        ...Object.entries(invariants.workflows)];
    for (const [rel, spec] of all) {
        const doc = workflow(candidateRoot, rel, YAML);
        if (doc.name !== spec.workflowName) die(`${rel} workflow name changed`);
        const actualTriggers = triggers(doc);
        for (const trigger of spec.requiredTriggers) {
            if (!actualTriggers.includes(trigger)) die(`${rel} is missing trigger ${trigger}`);
        }
        for (const id of spec.requiredJobs) {
            if (!doc.jobs?.[id]) die(`${rel} is missing required job ${id}`);
        }
        if (containsForbidden(doc, invariants.forbiddenExpressions)) {
            die(`${rel} contains a failure-bypass expression or continue-on-error`);
        }
        if (rel === invariants.qualityGate.workflow) {
            const excluded = list((doc.on ?? doc.true)?.push?.['branches-ignore']);
            if (!same(excluded, invariants.qualityGate.allowedPushExclusions)) {
                die('Quality Gate push exclusions changed');
            }
            validateBootstrap(doc, invariants);
        }
        if (invariants.publicationWorkflows.includes(rel)) validatePublication(rel, doc, invariants);
    }
    const publish = workflow(candidateRoot, '.github/workflows/publish-plugins.yml', YAML);
    if (publish.jobs?.['quality-gate']?.uses !== './.github/workflows/quality-gate.yml'
        || !needs(publish.jobs.publish).includes('quality-gate')) {
        die('plugin publication no longer depends on the reusable Quality Gate');
    }
    const release = workflow(candidateRoot, '.github/workflows/release.yml', YAML);
    if (release.jobs?.['draft-quality-gate']?.uses !== './.github/workflows/quality-gate.yml'
        || release.jobs?.['publish-plugins']?.uses !== './.github/workflows/publish-plugins.yml') {
        die('release no longer routes through Quality Gate');
    }
    const nightly = workflow(candidateRoot, '.github/workflows/nightly.yml', YAML);
    if (nightly.jobs?.['publish-plugins']?.uses !== './.github/workflows/publish-plugins.yml') {
        die('nightly publication no longer routes through Quality Gate');
    }
}

function validateWrapper(candidateRoot) {
    const file = path.join(candidateRoot, ...CONTRACT_REL.split('/'));
    if (!fs.existsSync(file)) return;
    const body = fs.readFileSync(file, 'utf8');
    if (!body.includes('gate-parity.mjs') || !body.includes('spawnSync')) {
        die('gate-contract compatibility entry no longer delegates to the shared checker');
    }
}

function validateProtectedCore(repoRoot, candidateRoot, trustedDir, trustedRef, candidateRef, paths) {
    for (const rel of paths) {
        const trusted = fs.readFileSync(path.join(trustedDir, ...rel.split('/')));
        const candidate = fs.readFileSync(path.join(candidateRoot, ...rel.split('/')));
        if (!trusted.equals(candidate)) die(`protected verifier core changed without a new Gate Epoch: ${rel}`);
    }
    if (!trustedRef || !SHA_RE.test(trustedRef)) die('--trusted-ref must be a full commit SHA');
    if (!candidateRef) return;
    if (trustedRef === candidateRef) return;
    const root = git(repoRoot, ['rev-parse', '--verify', `${ROOT_TAG}^{commit}`]).trim();
    for (const [ancestor, descendant, label] of [
        [root, trustedRef, 'root to trusted base'],
        [trustedRef, candidateRef, 'trusted base to candidate'],
        [trustedRef, 'refs/remotes/origin/master', 'trusted base to protected branch'],
    ]) {
        try {
            git(repoRoot, ['merge-base', '--is-ancestor', ancestor, descendant]);
        } catch {
            die(`invalid protected predecessor ancestry: ${label}`);
        }
    }
}

function runSignature(repoRoot, candidateRef) {
    const markers = [
        'DouyinX' + 'BogusSigner',
        'DouyinA' + 'BogusSigner',
        'Douyin' + 'Sm3',
        'generateChrome' + 'Fingerprint',
        'Dkdpgh4' + 'ZKs',
        'Dkdpgh2' + 'Zms',
        'ckdp1h4' + 'ZKs',
    ].join('|');
    const args = ['grep', '-nE', markers];
    if (candidateRef) args.push(candidateRef);
    else args.push('--cached');
    args.push('--', '.', ':(exclude)scripts/ci/gate-parity.mjs',
        ':(exclude)scripts/hooks/pre-push-guard.sh', ':(exclude)scripts/i18n/test/hooks.test.mjs');
    try {
        const matches = git(repoRoot, args).trim();
        if (matches) die('detected reverse-engineered Douyin signature code:\n' + matches);
    } catch (error) {
        if (error instanceof Error && error.message.startsWith('detected ')) throw error;
        if (error.status !== 1) throw error;
    }
}

function candidatePaths(policy, invariants) {
    return [...new Set([
        ...invariants.protectedPaths,
        ...policy.requiredWorkflowFiles,
        RULESET_REL,
        CONTRACT_REL,
    ])];
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (args.version) {
        console.log(`trusted-release-core ${VERSION}`);
        return;
    }
    const repoRoot = path.resolve(args.repoRoot);
    const reportRoot = path.resolve(args.reportRoot || repoRoot);
    const ownRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
    const ownPolicy = readJson(ownRoot, POLICY_REL);
    const ownInvariants = readJson(ownRoot, INVARIANTS_REL);
    const paths = candidatePaths(ownPolicy, ownInvariants);
    const candidateRoot = args.candidateRef
        ? materialize(repoRoot, args.candidateRef, paths)
        : materializeIndex(repoRoot, paths);
    const report = { version: VERSION, mode: args.invariants ? 'root' : 'normal', verdict: 'fail' };
    try {
        const policy = readJson(candidateRoot, POLICY_REL);
        const invariants = readJson(candidateRoot, INVARIANTS_REL);
        validatePolicy(candidateRoot, policy, invariants);
        if (args.signature) {
            if (!args.invariants) {
                validateProtectedCore(repoRoot, candidateRoot, path.resolve(args.trustedDir),
                    args.trustedRef, args.candidateRef, invariants.protectedPaths);
            }
            runSignature(repoRoot, args.candidateRef);
        } else {
            validateRuleset(candidateRoot);
            validateWorkflows(candidateRoot, invariants, repoRoot);
            validateWrapper(candidateRoot);
            if (!args.invariants) {
                validateProtectedCore(repoRoot, candidateRoot, path.resolve(args.trustedDir),
                    args.trustedRef, args.candidateRef, invariants.protectedPaths);
            }
        }
        report.verdict = 'pass';
        console.log(`TRUSTED RELEASE CORE OK (${args.candidateRef || 'index'})`);
    } catch (error) {
        report.error = error.message;
        console.error(`TRUSTED RELEASE CORE FAILED: ${error.message}`);
        process.exitCode = 1;
    } finally {
        const dir = path.join(reportRoot, 'build', 'reports', 'i18n');
        fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(path.join(dir, 'parity.json'), JSON.stringify(report, null, 2) + '\n', 'utf8');
        fs.rmSync(candidateRoot, { recursive: true, force: true });
    }
}

try {
    main();
} catch (error) {
    console.error('gate-parity ERROR: ' + error.message);
    process.exit(2);
}
