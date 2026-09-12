import { execFileSync } from 'node:child_process';

function root(epoch) {
    if (!Number.isSafeInteger(epoch) || epoch < 5) throw new Error('invalid historical epoch');
    return `refs/tags/release-gate-epoch-${epoch}-root`;
}

export function historicalGateFile(repo, rel, epoch = 5) {
    return execFileSync('git', ['-C', repo, 'show', `${root(epoch)}:${rel}`],
        { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
}

export function historicalWorkflows(repo, epoch = 5) {
    return execFileSync('git', ['-C', repo, 'ls-tree', '-r', '--name-only', root(epoch), '--', '.github/workflows'],
        { encoding: 'utf8', windowsHide: true }).trim().split(/\r?\n/u).filter((rel) => /\.ya?ml$/u.test(rel));
}
