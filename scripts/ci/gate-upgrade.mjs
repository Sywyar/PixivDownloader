#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const POLICY_PATH = 'scripts/ci/release-gate-policy.json';
export const APPROVAL_PATH = 'scripts/ci/gate-upgrade/approval.json';
export const UPGRADE_DIRECTORY = 'scripts/ci/gate-upgrade/core/';
const DEPENDENCIES = ['package.json', 'package-lock.json'];
const MAX_GIT_BYTES = 32 * 1024 * 1024;
const MAX_APPROVAL_BYTES = 64 * 1024;
const SHA = /^[0-9a-f]{40}$/u;
const FILE = /^(?:scripts\/ci\/[a-z0-9-]+\.mjs|scripts\/ci\/release-gate-policy\.json|package(?:-lock)?\.json)$/u;

function fail(message) { throw new Error(`gate upgrade: ${message}`); }
export function readGit(repo, args, options = {}) {
    return execFileSync('git', ['--no-replace-objects', '-C', repo, ...args], {
        encoding: 'utf8', windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
        maxBuffer: MAX_GIT_BYTES, ...options,
    });
}
function commit(repo, ref) {
    const value = readGit(repo, ['rev-parse', '--verify', '--end-of-options', `${ref}^{commit}`]).trim();
    if (!SHA.test(value)) fail('invalid commit identity');
    return value;
}
function ancestor(repo, older, newer) {
    try { readGit(repo, ['merge-base', '--is-ancestor', older, newer]); return true; }
    catch { return false; }
}
function read(repo, ref, file) { return readGit(repo, ['show', `${ref}:${file}`], { encoding: null }); }
export function readPolicy(repo, ref) { return JSON.parse(read(repo, ref, POLICY_PATH).toString('utf8')); }
function equal(left, right) { return JSON.stringify(left) === JSON.stringify(right); }
function exactKeys(value, keys, label) {
    if (!value || typeof value !== 'object' || Array.isArray(value)
        || !equal(Object.keys(value).sort(), [...keys].sort())) fail(`invalid ${label} fields`);
}
function epoch(value) { return Number.isSafeInteger(value) && value > 0; }
function rootName(value) { return `refs/tags/release-gate-epoch-${value}-root`; }
function entry(repo, ref, file) {
    const rows = readGit(repo, ['ls-tree', '-z', ref, '--', `:(literal)${file}`]).split('\0').filter(Boolean);
    const match = rows.length === 1 && /^(100644|100755) blob ([0-9a-f]{40})\t(.+)$/u.exec(rows[0]);
    if (!match || match[3] !== file) fail(`missing regular file: ${file}`);
    return { mode: match[1], blob: match[2] };
}
function containsAll(before, after, label) {
    if (!Array.isArray(before) || !Array.isArray(after)
        || before.some((value) => !after.includes(value))) fail(`upgrade removed ${label}`);
}

// 升级授权不豁免现有质量职责、发布环境、检查签发者及历史根保护。
export function verifyUpgradePolicy(before, after) {
    for (const key of ['schemaVersion', 'protectedBranch', 'releaseEnvironment']) {
        if (!equal(before[key], after[key])) fail(`upgrade changed ${key}`);
    }
    for (const key of ['workflow', 'workflowName']) {
        if (!equal(before.qualityGate?.[key], after.qualityGate?.[key])) fail(`upgrade changed quality ${key}`);
    }
    containsAll(before.qualityGate?.requiredJobs, after.qualityGate?.requiredJobs, 'quality roles');
    containsAll(before.qualityGate?.requiredTriggers, after.qualityGate?.requiredTriggers, 'quality triggers');
    for (const [file, spec] of Object.entries(before.workflows || {})) {
        const next = after.workflows?.[file];
        if (next?.workflowName !== spec.workflowName) fail(`upgrade changed workflow ${file}`);
        containsAll(spec.requiredJobs, next.requiredJobs, `${file} roles`);
        containsAll(spec.requiredTriggers, next.requiredTriggers, `${file} triggers`);
    }
    const oldRules = before.ruleset, nextRules = after.ruleset;
    containsAll(oldRules?.requiredChecks, nextRules?.requiredChecks, 'required checks');
    for (const name of oldRules.requiredChecks) {
        if (oldRules.requiredCheckSources?.[name] !== nextRules.requiredCheckSources?.[name]) {
            fail(`upgrade changed check authority: ${name}`);
        }
    }
    for (const key of ['requireStrict', 'requirePullRequest', 'allowBypass', 'allowDeletion', 'allowNonFastForward']) {
        if (oldRules[key] !== nextRules[key]) fail(`upgrade changed ruleset ${key}`);
    }
    if (!Number.isSafeInteger(nextRules.minimumApprovals) || nextRules.minimumApprovals < oldRules.minimumApprovals) {
        fail('upgrade lowered required reviews');
    }
    for (const [root, rules] of Object.entries(oldRules.roots)) {
        if (!equal(rules, nextRules.roots?.[root])) fail(`upgrade changed historical root ${root}`);
    }
    if (!equal(nextRules.roots?.[after.rootTag],
        { allowDeletion: false, allowNonFastForward: false, allowBypass: false })) fail('target root is unprotected');
}

function approval(repo, base) {
    const bytes = read(repo, base, APPROVAL_PATH);
    if (bytes.length > MAX_APPROVAL_BYTES) fail('approval exceeds 64 KiB');
    const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    exactKeys(value, ['schemaVersion', 'from', 'to', 'files', 'changes'], 'approval');
    exactKeys(value.from, ['epoch', 'root', 'rootCommit'], 'source identity');
    exactKeys(value.to, ['epoch', 'root'], 'target identity');
    if (value.schemaVersion !== 1 || !epoch(value.from.epoch) || !epoch(value.to.epoch)
        || value.to.epoch <= value.from.epoch || !SHA.test(value.from.rootCommit)
        || value.from.root !== rootName(value.from.epoch) || value.to.root !== rootName(value.to.epoch)
        || !Array.isArray(value.files) || !value.files.length || !Array.isArray(value.changes)
        || !value.changes.length || value.changes.some((change) => typeof change !== 'string' || !change.trim())) {
        fail('invalid approval identity or declared changes');
    }
    const names = new Set();
    for (const file of value.files) {
        exactKeys(file, ['path', 'source', 'mode', 'blob'], 'approved file');
        if (!FILE.test(file.path) || names.has(file.path)
            || ![file.path, UPGRADE_DIRECTORY + path.posix.basename(file.path)].includes(file.source)
            || !['100644', '100755'].includes(file.mode) || !SHA.test(file.blob)) fail('invalid approved file');
        names.add(file.path);
        if (!equal(entry(repo, base, file.source), { mode: file.mode, blob: file.blob })) {
            fail(`approved source changed: ${file.source}`);
        }
    }
    return value;
}

export function verifyUpgrade({ repo, trusted, candidate, localFeedback = false }) {
    if (localFeedback && process.env.CI === 'true') fail('local feedback is forbidden in CI');
    const base = commit(repo, trusted), next = commit(repo, candidate);
    const before = readPolicy(repo, base), after = readPolicy(repo, next);
    if (base === next || !ancestor(repo, base, 'refs/remotes/origin/master') || !ancestor(repo, base, next)) {
        fail('approval must come from a strict protected predecessor');
    }
    const permit = approval(repo, base);
    if (permit.from.epoch !== before.gateEpoch || permit.from.root !== before.rootTag
        || permit.to.epoch !== after.gateEpoch || permit.to.root !== after.rootTag
        || after.upgradeProtocol !== 1 || commit(repo, permit.from.root) !== permit.from.rootCommit
        || !ancestor(repo, permit.from.rootCommit, base)) fail('approval does not match the active trust chain');
    const expected = [...after.protectedCore, POLICY_PATH, ...DEPENDENCIES].sort();
    if (new Set(expected).size !== expected.length
        || !equal(permit.files.map((file) => file.path).sort(), expected)) fail('approval does not cover the execution closure');
    for (const file of permit.files) {
        if (!equal(entry(repo, next, file.path), { mode: file.mode, blob: file.blob })) {
            fail(`candidate differs from approved bytes: ${file.path}`);
        }
    }
    verifyUpgradePolicy(before, after);
    if (localFeedback) return permit;
    const root = commit(repo, permit.to.root);
    if (readGit(repo, ['cat-file', '-t', permit.to.root]).trim() !== 'tag') fail('root must be an annotated tag');
    const parents = (ref) => readGit(repo, ['rev-list', '--parents', '-n', '1', ref]).trim().split(/\s+/u).slice(1);
    const rootParents = parents(root);
    if (rootParents.length !== 1 || !ancestor(repo, rootParents[0], base)
        || !equal(entry(repo, rootParents[0], APPROVAL_PATH), entry(repo, base, APPROVAL_PATH))) {
        fail('root must descend from the same protected approval');
    }
    for (const file of permit.files) {
        if (!equal(entry(repo, root, file.path), { mode: file.mode, blob: file.blob })) fail(`root bytes differ: ${file.path}`);
    }
    const nextParents = parents(next);
    if (next !== root && (nextParents.length !== 2 || nextParents[0] !== base
        || !ancestor(repo, root, nextParents[1]))) fail('activation must preserve the approved root and exact merge parents');
    return permit;
}

export function selectGateFiles({ repo, trusted, candidate, localFeedback = false }) {
    const base = commit(repo, trusted), next = commit(repo, candidate);
    const before = readPolicy(repo, base), after = readPolicy(repo, next);
    if (!epoch(before.gateEpoch) || before.rootTag !== rootName(before.gateEpoch)
        || !ancestor(repo, before.rootTag, base) || !ancestor(repo, base, 'refs/remotes/origin/master')
        || !ancestor(repo, base, next)) fail('loader requires a protected predecessor');
    if (before.gateEpoch !== after.gateEpoch) {
        return verifyUpgrade({ repo, trusted: base, candidate: next, localFeedback }).files;
    }
    if (!epoch(before.gateEpoch) || !Array.isArray(before.protectedCore)) fail('invalid protected policy');
    return [...before.protectedCore, POLICY_PATH, ...DEPENDENCIES].map((file) => {
        if (!FILE.test(file)) fail('invalid protected core path');
        return { path: file, source: file, ...entry(repo, base, file) };
    });
}

export function materializeGate({ repo, trusted, candidate, directory, localFeedback = false }) {
    const base = commit(repo, trusted);
    const files = selectGateFiles({ repo, trusted: base, candidate, localFeedback });
    if (!path.isAbsolute(directory) || fs.existsSync(directory)) fail('materialization requires a new absolute directory');
    fs.mkdirSync(directory);
    for (const file of files) {
        const target = path.join(directory, file.path);
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.writeFileSync(target, read(repo, base, file.source), { flag: 'wx', mode: file.mode === '100755' ? 0o755 : 0o644 });
    }
    return files;
}

// 只生成待审数据；文件存在不代表已经获得受保护主线授权。
export function createApproval({ repo, trusted, changes }) {
    const before = readPolicy(repo, trusted);
    const after = JSON.parse(fs.readFileSync(path.join(repo, UPGRADE_DIRECTORY, 'release-gate-policy.json'), 'utf8'));
    verifyUpgradePolicy(before, after);
    const files = [...after.protectedCore, POLICY_PATH, ...DEPENDENCIES].map((file) => {
        if (!FILE.test(file)) fail('invalid proposed core path');
        const staged = UPGRADE_DIRECTORY + path.posix.basename(file);
        const source = fs.existsSync(path.join(repo, staged)) ? staged : file;
        const actual = path.join(repo, source);
        if (!fs.lstatSync(actual).isFile()) fail('proposal source must be a regular file');
        const blob = readGit(repo, ['hash-object', '--path', source, '--stdin'], { input: fs.readFileSync(actual) }).trim();
        const tracked = readGit(repo, ['ls-files', '--stage', '--', source]).trim();
        const mode = /^(100644|100755) /u.exec(tracked)?.[1] || '100644';
        return { path: file, source, mode, blob };
    });
    return { schemaVersion: 1, from: { epoch: before.gateEpoch, root: before.rootTag,
        rootCommit: commit(repo, before.rootTag) }, to: { epoch: after.gateEpoch, root: after.rootTag }, files, changes };
}

function main() {
    const args = process.argv.slice(2), values = {};
    const operation = ['--prepare', '--activate'].includes(args[0]) ? args.shift() : null;
    for (let i = 0; i < args.length; i += 2) {
        if (!['--repo-root', '--trusted-ref', '--candidate-ref', '--directory', '--changes'].includes(args[i])
            || !args[i + 1] || Object.hasOwn(values, args[i])) {
            fail('invalid or duplicated upgrade argument');
        }
        values[args[i]] = args[i + 1];
    }
    const repo = path.resolve(values['--repo-root'] || '.');
    if (operation === '--prepare') {
        const changes = JSON.parse(values['--changes'] || 'null');
        if (!Array.isArray(changes) || !changes.length || changes.some((item) => typeof item !== 'string' || !item.trim())) {
            fail('--changes must be a nonempty JSON string array');
        }
        const record = createApproval({ repo, trusted: values['--trusted-ref'], changes });
        fs.writeFileSync(path.join(repo, APPROVAL_PATH), JSON.stringify(record, null, 2) + '\n', 'utf8');
        return;
    }
    if (operation === '--activate') {
        if (readGit(repo, ['status', '--porcelain']).trim()) fail('activation requires a clean worktree');
        const base = commit(repo, values['--trusted-ref']);
        if (commit(repo, 'HEAD') !== base || !ancestor(repo, base, 'refs/remotes/origin/master')) {
            fail('activation must start at the protected approval commit');
        }
        const record = approval(repo, base), current = readPolicy(repo, base);
        if (current.gateEpoch !== record.from.epoch || current.rootTag !== record.from.root
            || commit(repo, current.rootTag) !== record.from.rootCommit) fail('stale upgrade approval');
        for (const file of record.files) {
            const target = path.join(repo, file.path);
            fs.mkdirSync(path.dirname(target), { recursive: true });
            if (fs.existsSync(target) && !fs.lstatSync(target).isFile()) fail('activation cannot replace a nonregular file');
            fs.writeFileSync(target, read(repo, base, file.source));
            fs.chmodSync(target, file.mode === '100755' ? 0o755 : 0o644);
        }
        console.log('Approved files copied; commit, protected root creation and integration remain separate operations.');
        return;
    }
    materializeGate({ repo: values['--repo-root'] || '.', trusted: values['--trusted-ref'],
        candidate: values['--candidate-ref'], directory: values['--directory'] });
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
