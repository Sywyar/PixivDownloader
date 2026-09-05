#!/usr/bin/env node
'use strict';

import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';

const EPOCH = 8;
const ROOT_TAG = 'refs/tags/release-gate-epoch-8-root';
const POLICY = 'scripts/ci/release-gate-policy.json';
const REF_KEY = 'pixiv.release.trustedGateRef';
const EPOCH_KEY = 'pixiv.release.trustedGateEpoch';
const SHA = /^[0-9a-f]{40}$/;

function fail(message) {
    throw new Error(message);
}

function git(root, args, options = {}) {
    const result = execFileSync('git', args, {
        cwd: root, encoding: options.encoding === null ? null : 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 128 * 1024 * 1024,
    });
    return Buffer.isBuffer(result) ? result : result.trim();
}

function config(root, key) {
    try { return git(root, ['config', '--local', '--get', key]) || null; } catch { return null; }
}

function commit(root, ref) {
    try {
        const value = git(root, ['rev-parse', '--verify', `${ref}^{commit}`]);
        return SHA.test(value) ? value : null;
    } catch { return null; }
}

function ancestor(root, older, newer) {
    try { git(root, ['merge-base', '--is-ancestor', older, newer]); return true; } catch { return false; }
}

function clean(root) {
    if (git(root, ['status', '--porcelain'])) fail('trust commands require a clean worktree');
}

function materialize(root, ref, files, admission = false) {
    const out = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-release-gate-core-'));
    try {
        for (const rel of [...files, 'package.json', 'package-lock.json']) {
            const target = path.join(out, ...rel.split('/'));
            fs.mkdirSync(path.dirname(target), { recursive: true });
            const source = admission && files.includes(rel)
                ? `scripts/ci/gate-admission/${path.posix.basename(rel)}` : rel;
            fs.writeFileSync(target, git(root, ['show', `${ref}:${source}`], { encoding: null }));
        }
        const install = spawnSync(process.platform === 'win32'
            ? 'npm.cmd ci --offline --ignore-scripts --no-audit --no-fund' : 'npm',
            process.platform === 'win32' ? [] : ['ci', '--offline', '--ignore-scripts', '--no-audit', '--no-fund'], {
                cwd: out, shell: process.platform === 'win32', windowsHide: true,
                stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8',
            });
        if (install.status !== 0) fail('protected parser is not available in the local npm cache; install the protected base dependencies first');
        return out;
    } catch (error) {
        fs.rmSync(out, { recursive: true, force: true });
        throw error;
    }
}

function run(root, script, args) {
    const result = spawnSync(process.execPath, [script, '--repo-root', root, ...args], {
        cwd: root, encoding: 'utf8', stdio: ['ignore', 'inherit', 'inherit'],
        maxBuffer: 128 * 1024 * 1024, windowsHide: true,
    });
    if (result.status !== 0) fail('protected verifier rejected the candidate');
}

function setAnchor(root, sha) {
    const common = git(root, ['rev-parse', '--path-format=absolute', '--git-common-dir']);
    const configFile = path.join(common, 'config');
    const lock = `${configFile}.lock`;
    const fd = fs.openSync(lock, 'wx');
    try {
        try { fs.writeFileSync(fd, fs.readFileSync(configFile)); } finally { fs.closeSync(fd); }
        git(root, ['config', '--file', lock, EPOCH_KEY, String(EPOCH)]);
        git(root, ['config', '--file', lock, REF_KEY, sha]);
        if (git(root, ['config', '--file', lock, '--get', EPOCH_KEY]) !== String(EPOCH)
            || git(root, ['config', '--file', lock, '--get', REF_KEY]) !== sha) fail('invalid proposed anchor');
        fs.renameSync(lock, configFile);
    } finally {
        if (fs.existsSync(lock)) fs.unlinkSync(lock);
    }
    if (config(root, EPOCH_KEY) !== String(EPOCH) || config(root, REF_KEY) !== sha) {
        fail('trusted anchor verification failed');
    }
}

function adopt(root, ref) {
    if (process.env.CI === 'true') fail('root adoption is forbidden in CI');
    clean(root);
    const candidate = commit(root, ref);
    const source = commit(root, config(root, REF_KEY));
    const master = commit(root, 'refs/remotes/origin/master');
    const rootTag = commit(root, ROOT_TAG);
    if (config(root, EPOCH_KEY) !== '5' || !candidate || !source || !rootTag
        || git(root, ['symbolic-ref', '--quiet', 'HEAD']) !== 'refs/heads/master'
        || candidate !== master || candidate !== commit(root, 'HEAD')) {
        fail('adoption requires the Epoch 5 anchor, protected master tip, HEAD and Epoch 8 root');
    }
    const parents = git(root, ['rev-list', '--parents', '-n', '1', candidate]).split(/\s+/u).slice(1);
    if (parents.length !== 2 || parents[0] !== source || parents[1] !== rootTag) {
        fail('Epoch 8 adoption requires the exact root merge from the Epoch 5 anchor');
    }
    const files = JSON.parse(git(root, ['show', `${source}:${POLICY}`])).protectedCore;
    const oldCore = materialize(root, source, files, true);
    try {
        run(root, path.join(oldCore, 'scripts', 'ci', 'release-gate-verifier.mjs'),
            ['--trusted-ref', source, '--candidate-ref', candidate]);
    } finally {
        fs.rmSync(oldCore, { recursive: true, force: true });
    }
    setAnchor(root, candidate);
    console.log(`release-gate-trust: Epoch 8 root adopted at ${candidate}`);
}

function advance(root, ref) {
    if (process.env.CI === 'true') fail('trusted anchor advancement is forbidden in CI');
    clean(root);
    const current = commit(root, config(root, REF_KEY));
    const candidate = commit(root, ref);
    const master = commit(root, 'refs/remotes/origin/master');
    const rootSha = commit(root, ROOT_TAG);
    if (config(root, EPOCH_KEY) !== String(EPOCH) || !current || !candidate || !rootSha
        || candidate !== master || candidate !== commit(root, 'HEAD')
        || git(root, ['symbolic-ref', '--quiet', 'HEAD']) !== 'refs/heads/master'
        || candidate === current || !ancestor(root, rootSha, current)
        || !ancestor(root, current, candidate)) {
        fail('advance requires root <= current anchor < protected master tip');
    }
    const verifier = materialize(root, current, ['scripts/ci/release-gate-verifier.mjs']);
    try {
        run(root, path.join(verifier, 'scripts', 'ci', 'release-gate-verifier.mjs'),
            ['--trusted-ref', current, '--candidate-ref', candidate]);
    } finally {
        fs.rmSync(verifier, { recursive: true, force: true });
    }
    setAnchor(root, candidate);
    console.log(`release-gate-trust: trusted anchor advanced to ${candidate}`);
}

function show(root) {
    console.log(`trustedGateEpoch=${config(root, EPOCH_KEY) || '<unset>'}`);
    console.log(`trustedGateRef=${config(root, REF_KEY) || '<unset>'}`);
    console.log(`root=${commit(root, ROOT_TAG) || '<missing>'}`);
}

function main() {
    const args = process.argv.slice(2);
    const root = git(process.cwd(), ['rev-parse', '--show-toplevel']);
    if (args[0] === '--show') show(root);
    else if (args[0] === '--adopt-root' && args[1] === '--ref' && args[2]) adopt(root, args[2]);
    else if (args[0] === '--advance' && args[1] === '--ref' && args[2]) advance(root, args[2]);
    else if (args[0] === '--version') console.log('release-gate-trust 8');
    else fail('usage: release-gate-trust.mjs --show | --adopt-root --ref <commit> | --advance --ref <commit>');
}

try { main(); } catch (error) {
    console.error(`release-gate-trust: ${error.message}`);
    process.exitCode = 1;
}
