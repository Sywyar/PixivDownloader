#!/usr/bin/env node
'use strict';

import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

function git(repo, args) {
    return execFileSync('git', ['-C', repo, ...args], {
        encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();
}

export function resolveTrustedBase({ repo = '.', candidate, event, before, ref,
    prBase, prHead, mergeGroupBase, inputBase }) {
    const commit = (value) => {
        if (!value) throw new Error('missing commit');
        const sha = git(repo, ['rev-parse', '--verify', value + '^{commit}']);
        if (!/^[0-9a-f]{40}$/u.test(sha)) throw new Error('invalid commit');
        return sha;
    };
    const tested = commit(candidate);
    const master = commit('refs/remotes/origin/master');
    const protectedPredecessor = tested === master
        ? commit(tested + '^1') : git(repo, ['merge-base', tested, master]);
    let proposed = inputBase;
    if (event === 'pull_request') {
        proposed = prBase;
        const parents = git(repo, ['rev-list', '--parents', '-n', '1', tested]).split(/\s+/u).slice(1);
        if (parents.length !== 2 || parents[0] !== commit(prBase) || parents[1] !== commit(prHead)) {
            throw new Error('PR candidate must have the event base and head as its two parents');
        }
    } else if (!proposed && event === 'push' && ref === 'refs/heads/master') {
        proposed = before;
    } else if (!proposed && event === 'merge_group') {
        proposed = mergeGroupBase;
    } else if (!proposed) {
        proposed = protectedPredecessor;
    }
    const base = commit(proposed);
    if (base !== protectedPredecessor || (inputBase && commit(inputBase) !== base) || base === tested) {
        throw new Error('trusted base must be the exact protected predecessor');
    }
    git(repo, ['merge-base', '--is-ancestor', base, tested]);
    git(repo, ['merge-base', '--is-ancestor', base, master]);
    const policy = JSON.parse(git(repo, ['show', base + ':scripts/ci/release-gate-policy.json']));
    if (![5, 6].includes(policy.gateEpoch)) throw new Error('unsupported predecessor epoch');
    const root = commit('refs/tags/release-gate-epoch-' + policy.gateEpoch + '-root');
    git(repo, ['merge-base', '--is-ancestor', root, base]);
    return { mode: 'NORMAL', base, root };
}

function main() {
    const values = {};
    let mode = false;
    const names = new Set(['repo-root', 'candidate', 'event-name', 'before', 'ref',
        'pr-base', 'pr-head', 'merge-group-base', 'input-base', 'default-branch',
        'root-admission', 'root-candidate-sha']);
    for (let i = 2; i < process.argv.length; i += 1) {
        const key = process.argv[i].replace(/^--/u, '');
        if (key === 'mode') mode = true;
        else if (names.has(key) && process.argv[i + 1] !== undefined) values[key] = process.argv[++i];
        else throw new Error('unknown or incomplete argument: ' + process.argv[i]);
    }
    if (values['default-branch'] && values['default-branch'] !== 'master') {
        throw new Error('protected branch must be master');
    }
    if ((values['root-admission'] && values['root-admission'] !== 'false') || values['root-candidate-sha']) {
        throw new Error('candidate self-admission is not supported');
    }
    const result = resolveTrustedBase({ repo: values['repo-root'], candidate: values.candidate,
        event: values['event-name'], before: values.before, ref: values.ref,
        prBase: values['pr-base'], prHead: values['pr-head'], mergeGroupBase: values['merge-group-base'],
        inputBase: values['input-base'] });
    console.log(mode ? JSON.stringify(result) : result.base);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try { main(); } catch (error) {
        console.error('resolve-trusted-base: ' + error.message);
        process.exitCode = 2;
    }
}
