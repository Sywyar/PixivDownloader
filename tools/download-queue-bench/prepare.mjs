// 从仓库导出要测试的版本的页面相关文件到数据目录的 trees/，供模拟后端原样提供。
// 对比两个版本：node prepare.mjs --base <对照版本> --cand <被测版本>
// 只测一个版本：node prepare.mjs --only <版本>
// 版本可以是分支、标签、提交号或 origin/master~1 等写法。工具位于仓库目录内时自动使用该仓库，
// 否则用 --repo <仓库路径> 指定。使用临时 index 导出指定提交的文件，不修改仓库的 index、
// 工作区或任何 worktree；同一提交只导出一次。
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {DATA_DIR, TOOL_REPO_ROOT, parseArgs} from './lib/servers.mjs';

const USAGE = [
    '用法：',
    '  对比两个版本：node prepare.mjs --base <对照版本> --cand <被测版本>',
    '  只测一个版本：node prepare.mjs --only <版本>',
    '  工具不在仓库目录内时，再加 --repo <仓库路径>'
].join('\n');
const stop = message => { console.error(message + '\n\n' + USAGE); process.exit(1); };

const args = parseArgs(process.argv.slice(2), {});
for (const key of ['only', 'base', 'cand', 'repo']) {
    if (args[key] === true || Array.isArray(args[key])) stop(`--${key} 后面需要写一个值`);
}
if (args.only === undefined && (args.base === undefined || args.cand === undefined)) {
    stop(args.base === undefined && args.cand === undefined ? '没有指定要测试的版本' : '--base 和 --cand 需要同时指定');
}
if (args.repo === undefined) {
    if (!TOOL_REPO_ROOT) stop('工具不在 Git 仓库目录内，请用 --repo 指定仓库路径');
    args.repo = TOOL_REPO_ROOT;
}
const targets = args.only !== undefined
    ? {single: args.only}
    : {base: args.base, cand: args.cand};
const PATHS = [
    'pixivdownload-app/src/main/resources/static',
    'pixivdownload-app/src/main/resources/i18n',
    'pixivdownload-plugin-download-workbench/src/main/resources'
];
const git = (gitArgs, options = {}) => execFileSync('git', ['-C', args.repo, ...gitArgs],
    {encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, ...options});

const treesDir = path.join(DATA_DIR, 'trees');
fs.mkdirSync(treesDir, {recursive: true});
const manifest = {repo: args.repo, createdAt: new Date().toISOString(), versions: {}};

for (const [name, rev] of Object.entries(targets)) {
    const sha = git(['rev-parse', '--verify', `${rev}^{commit}`]).trim();
    const subject = git(['log', '-1', '--format=%s', sha]).trim();
    const dir = path.join(treesDir, sha.slice(0, 10));
    const marker = path.join(dir, '.dqb-complete');
    if (!fs.existsSync(marker)) {
        fs.rmSync(dir, {recursive: true, force: true});
        fs.mkdirSync(dir, {recursive: true});
        const index = path.join(os.tmpdir(), `dqb-index-${process.pid}-${name}`);
        const env = {...process.env, GIT_INDEX_FILE: index};
        try {
            git(['read-tree', sha], {env});
            const files = git(['ls-files', '-z', '--', ...PATHS], {env});
            const prefix = dir.replaceAll('\\', '/') + '/';
            git(['checkout-index', `--prefix=${prefix}`, '-z', '--stdin'], {env, input: files});
            const count = files.split('\0').filter(Boolean).length;
            fs.writeFileSync(marker, `${sha}\n${count}\n`, 'utf8');
        } finally {
            fs.rmSync(index, {force: true});
        }
    }
    manifest.versions[name] = {rev, sha, subject, dir};
    console.log(`${name}: ${rev} -> ${sha.slice(0, 10)} ${subject}`);
}
fs.writeFileSync(path.join(treesDir, 'manifest.json'), JSON.stringify(manifest, null, 1), 'utf8');
console.log(Object.keys(manifest.versions).length === 1
    ? '已写入 trees/manifest.json（单版本测试）'
    : '已写入 trees/manifest.json（两版本对比）');
