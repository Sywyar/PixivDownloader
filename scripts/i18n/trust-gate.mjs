#!/usr/bin/env node
'use strict';

import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const EPOCH = 4;
const VERSION = '4';
const SHA_RE = /^[0-9a-f]{40}$/;
const ROOT_TAG = `refs/tags/i18n-gate-epoch-${EPOCH}-root`;
const POLICY_REL = 'scripts/i18n/gate-policy.json';
const TRUSTED_EPOCH_KEY = 'pixiv.i18n.trustedGateEpoch';
const TRUSTED_REF_KEY = 'pixiv.i18n.trustedGateRef';
const TICKET_KEYS = {
    sourceEpoch: 'pixiv.i18n.firstAdmissionSourceEpoch',
    targetEpoch: 'pixiv.i18n.firstAdmissionTargetEpoch',
    trustedSource: 'pixiv.i18n.firstAdmissionTrustedSource',
    parent: 'pixiv.i18n.firstAdmissionParent',
    tree: 'pixiv.i18n.firstAdmissionTree',
    candidate: 'pixiv.i18n.firstAdmissionCandidate',
};

function fail(message) {
    throw new Error(message);
}

function git(root, args, options = {}) {
    return execFileSync('git', args, {
        cwd: root,
        encoding: options.encoding === null ? null : 'utf8',
        stdio: ['pipe', 'pipe', 'pipe'],
        maxBuffer: 128 * 1024 * 1024,
        ...options,
    }).trim();
}

function config(root, key) {
    try {
        return git(root, ['config', '--local', '--get', key]) || null;
    } catch {
        return null;
    }
}

function resolveCommit(root, ref) {
    try {
        const sha = git(root, ['rev-parse', '--verify', `${ref}^{commit}`]);
        return SHA_RE.test(sha) ? sha : null;
    } catch {
        return null;
    }
}

function isAncestor(root, ancestor, descendant) {
    try {
        git(root, ['merge-base', '--is-ancestor', ancestor, descendant]);
        return true;
    } catch {
        return false;
    }
}

function readAt(root, ref, rel) {
    try {
        return git(root, ['show', `${ref}:${rel}`]);
    } catch {
        return null;
    }
}

function readJsonAt(root, ref, rel) {
    const text = readAt(root, ref, rel);
    if (text === null) fail(`${ref} does not contain ${rel}`);
    try {
        return JSON.parse(text);
    } catch (error) {
        fail(`${rel} at ${ref} is invalid: ${error.message}`);
    }
}

function assertClean(root) {
    const status = git(root, ['status', '--porcelain']);
    if (status) fail('trust commands require a clean index and worktree');
}

function ticket(root) {
    return Object.fromEntries(Object.entries(TICKET_KEYS).map(([field, key]) => [field, config(root, key)]));
}

function setAnchor(root, epoch, sha) {
    git(root, ['config', '--local', TRUSTED_EPOCH_KEY, String(epoch)]);
    git(root, ['config', '--local', TRUSTED_REF_KEY, sha]);
    if (config(root, TRUSTED_EPOCH_KEY) !== String(epoch) || config(root, TRUSTED_REF_KEY) !== sha) {
        fail('trusted anchor verification failed');
    }
}

function clearTicket(root) {
    for (const key of Object.values(TICKET_KEYS)) {
        try {
            git(root, ['config', '--local', '--unset-all', key]);
        } catch {
            // An absent ticket field is already cleared.
        }
    }
    if (Object.values(ticket(root)).some(Boolean)) fail('first-admission ticket cleanup failed');
}

function restoreTicket(root, value) {
    clearTicket(root);
    for (const [field, key] of Object.entries(TICKET_KEYS)) {
        if (value[field]) git(root, ['config', '--local', key, value[field]]);
    }
    if (JSON.stringify(ticket(root)) !== JSON.stringify(value)) fail('first-admission ticket rollback failed');
}

function validatePolicy(policy) {
    const minimum = policy?.minimumTrustedVerifier;
    if (policy?.gateEpoch !== EPOCH || policy?.schemaVersion !== 4 || policy?.contractVersion !== 5
        || minimum?.contractVersion !== 5 || minimum?.schemaVersion !== 4
        || !Array.isArray(minimum?.requiredFiles) || minimum.requiredFiles.length !== 5) {
        fail('candidate does not contain the Gate Epoch 4 protected verifier baseline');
    }
    return policy;
}

function materializeCore(root, ref) {
    const policy = validatePolicy(readJsonAt(root, ref, POLICY_REL));
    const out = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-trusted-core-'));
    try {
        for (const rel of policy.minimumTrustedVerifier.requiredFiles) {
            const bytes = execFileSync('git', ['show', `${ref}:${rel}`], {
                cwd: root, encoding: null, stdio: ['pipe', 'pipe', 'pipe'],
            });
            const file = path.join(out, ...rel.split('/'));
            fs.mkdirSync(path.dirname(file), { recursive: true });
            fs.writeFileSync(file, bytes);
        }
        return out;
    } catch (error) {
        fs.rmSync(out, { recursive: true, force: true });
        fail('cannot materialize the protected verifier core: ' + error.message);
    }
}

function runCore(root, core, args) {
    const result = spawnSync(process.execPath, [path.join(core, 'scripts', 'ci', 'gate-parity.mjs'),
        '--repo-root', root, ...args], {
        cwd: root,
        encoding: 'utf8',
        stdio: ['ignore', 'inherit', 'inherit'],
        maxBuffer: 128 * 1024 * 1024,
    });
    if (result.status !== 0) fail('protected verifier rejected the candidate');
}

function assertProtectedSource(root, source) {
    const sourcePolicy = readJsonAt(root, source, POLICY_REL);
    if (sourcePolicy.gateEpoch !== EPOCH - 1) fail('first-admission source is not the previous Gate Epoch');
    const live = resolveCommit(root, 'refs/remotes/origin/master');
    if (live !== source || config(root, TRUSTED_REF_KEY) !== source
        || config(root, TRUSTED_EPOCH_KEY) !== String(EPOCH - 1)) {
        fail('first-admission source is no longer the protected master tip and local anchor');
    }
    const oldRoot = resolveCommit(root, `refs/tags/i18n-gate-epoch-${EPOCH - 1}-root`);
    if (!oldRoot || !isAncestor(root, oldRoot, source)) fail('previous Gate Epoch root ancestry is missing');
}

function adoptRoot(root, ref, epoch) {
    if (process.env.CI === 'true') fail('root adoption is forbidden in CI');
    if (String(epoch) !== String(EPOCH)) fail(`--epoch must be exactly ${EPOCH}`);
    assertClean(root);
    const candidate = resolveCommit(root, ref);
    const source = config(root, TRUSTED_REF_KEY);
    if (!candidate || !source) fail('candidate and previous trusted source must resolve to commits');
    assertProtectedSource(root, source);

    const prepared = ticket(root);
    const parents = git(root, ['rev-list', '--parents', '-n', '1', candidate]).split(/\s+/).slice(1);
    const tree = git(root, ['rev-parse', `${candidate}^{tree}`]);
    if (prepared.sourceEpoch !== String(EPOCH - 1) || prepared.targetEpoch !== String(EPOCH)
        || prepared.trustedSource !== source || prepared.parent !== source
        || prepared.tree !== tree || prepared.candidate !== candidate
        || parents.length !== 1 || parents[0] !== source || resolveCommit(root, 'HEAD') !== candidate) {
        fail('root adoption requires the exact sealed first-admission source, parent, tree and candidate');
    }

    const core = materializeCore(root, candidate);
    try {
        runCore(root, core, ['--candidate-ref', candidate, '--invariants']);
        runCore(root, core, ['--candidate-ref', candidate, '--invariants', '--signature']);
    } finally {
        fs.rmSync(core, { recursive: true, force: true });
    }

    try {
        assertProtectedSource(root, source);
        if (JSON.stringify(ticket(root)) !== JSON.stringify(prepared) || resolveCommit(root, 'HEAD') !== candidate
            || git(root, ['rev-parse', `${candidate}^{tree}`]) !== prepared.tree) {
            fail('repository state changed before root adoption');
        }
        setAnchor(root, EPOCH, candidate);
        clearTicket(root);
    } catch (error) {
        try {
            setAnchor(root, EPOCH - 1, source);
            restoreTicket(root, prepared);
        } catch (rollbackError) {
            fail(`${error.message}; rollback failed: ${rollbackError.message}`);
        }
        throw error;
    }
    console.log(`trust-gate: Gate Epoch ${EPOCH} root adopted at ${candidate}`);
}

function advance(root, ref) {
    if (process.env.CI === 'true') fail('trusted anchor advancement is forbidden in CI');
    assertClean(root);
    const current = config(root, TRUSTED_REF_KEY);
    const candidate = resolveCommit(root, ref);
    const protectedTip = resolveCommit(root, 'refs/remotes/origin/master');
    const rootSha = resolveCommit(root, ROOT_TAG);
    if (config(root, TRUSTED_EPOCH_KEY) !== String(EPOCH) || !current || !candidate || !rootSha) {
        fail('current Epoch 4 anchor, candidate or protected root tag is missing');
    }
    if (candidate !== protectedTip || candidate === current || !isAncestor(root, rootSha, current)
        || !isAncestor(root, current, candidate)) {
        fail('advance requires root <= current anchor < current protected master tip');
    }
    const core = materializeCore(root, current);
    try {
        runCore(root, core, ['--trusted-dir', core, '--trusted-ref', current,
            '--candidate-ref', candidate]);
        runCore(root, core, ['--trusted-dir', core, '--trusted-ref', current,
            '--candidate-ref', candidate, '--signature']);
    } finally {
        fs.rmSync(core, { recursive: true, force: true });
    }
    setAnchor(root, EPOCH, candidate);
    console.log(`trust-gate: trusted anchor advanced to ${candidate}`);
}

function show(root) {
    const epoch = config(root, TRUSTED_EPOCH_KEY);
    const trusted = config(root, TRUSTED_REF_KEY);
    const rootSha = resolveCommit(root, ROOT_TAG);
    let baseline = 'MISSING';
    try {
        if (trusted) validatePolicy(readJsonAt(root, trusted, POLICY_REL));
        baseline = trusted ? 'OK' : 'MISSING';
    } catch {
        baseline = 'INVALID';
    }
    console.log(`trustedGateEpoch=${epoch || '<unset>'}`);
    console.log(`trustedGateRef=${trusted || '<unset>'}`);
    console.log(`root=${rootSha || '<missing>'}`);
    console.log(`verifierBaseline=${baseline}`);
}

function parseArgs(argv) {
    const args = { command: null, ref: null, epoch: null };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        if (['--show', '--adopt-root', '--advance'].includes(arg)) args.command = arg.slice(2);
        else if (arg === '--ref') args.ref = argv[++i];
        else if (arg === '--epoch') args.epoch = argv[++i];
        else if (arg === '--version') args.command = 'version';
        else if (arg === '--prepare-root' || arg === '--seal-root' || arg === '--trusted-source') {
            fail('new Gate roots require a separately reviewed predecessor admission bridge');
        } else fail('unknown argument: ' + arg);
    }
    return args;
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (args.command === 'version') {
        console.log(`i18n-trust-gate ${VERSION}`);
        return;
    }
    const root = git(process.cwd(), ['rev-parse', '--show-toplevel']);
    if (args.command === 'show') show(root);
    else if (args.command === 'adopt-root' && args.ref && args.epoch) adoptRoot(root, args.ref, args.epoch);
    else if (args.command === 'advance' && args.ref) advance(root, args.ref);
    else fail('usage: trust-gate.mjs --show | --adopt-root --ref <commit> --epoch 4 | --advance --ref <commit>');
}

try {
    main();
} catch (error) {
    console.error('trust-gate ERROR: ' + error.message);
    process.exit(1);
}
