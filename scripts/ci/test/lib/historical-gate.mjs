import { execFileSync } from 'node:child_process';

const ROOT = 'refs/tags/release-gate-epoch-5-root';

export function historicalGateFile(repo, rel) {
    return execFileSync('git', ['-C', repo, 'show', `${ROOT}:${rel}`],
        { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
}

export function historicalWorkflows(repo) {
    return execFileSync('git', ['-C', repo, 'ls-tree', '-r', '--name-only', ROOT, '--', '.github/workflows'],
        { encoding: 'utf8', windowsHide: true }).trim().split(/\r?\n/u).filter((rel) => /\.ya?ml$/u.test(rel));
}
