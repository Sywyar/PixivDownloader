// 以子进程启动 / 停止模拟后端；端口被占用或启动失败时直接报错。
import {execFileSync, spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import fs from 'node:fs';
import path from 'node:path';

export const HARNESS_DIR = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const SERVER = path.join(HARNESS_DIR, 'server.mjs');

// 工具所在 Git 仓库的根目录；工具不在仓库内时为 null。
export const TOOL_REPO_ROOT = (() => {
    try {
        return execFileSync('git', ['-C', HARNESS_DIR, 'rev-parse', '--show-toplevel'],
            {encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']}).trim();
    } catch {
        return null;
    }
})();

// 导出的版本和测试结果写入仓库已忽略的 target/download-queue-bench/，不进入 Git；
// 可用环境变量 DQB_DATA_DIR 改到其它目录。
export const DATA_DIR = process.env.DQB_DATA_DIR
    || (TOOL_REPO_ROOT ? path.join(TOOL_REPO_ROOT, 'target', 'download-queue-bench') : path.join(HARNESS_DIR, 'data'));

export function readManifest() {
    const file = path.join(DATA_DIR, 'trees', 'manifest.json');
    if (!fs.existsSync(file)) throw new Error('未找到 trees/manifest.json，请先运行 node prepare.mjs');
    return JSON.parse(fs.readFileSync(file, 'utf8'));
}

export function startServer(treeDir, port) {
    return new Promise((resolve, reject) => {
        const child = spawn(process.execPath, [SERVER, treeDir, String(port)], {stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true});
        let output = '';
        const timer = setTimeout(() => { child.kill(); reject(new Error(`模拟后端 ${port} 启动超时：${output}`)); }, 15000);
        const onData = chunk => {
            output += chunk;
            if (output.includes(` on ${port}`)) { clearTimeout(timer); resolve(child); }
        };
        child.stdout.on('data', onData);
        child.stderr.on('data', onData);
        child.on('exit', code => { clearTimeout(timer); reject(new Error(`模拟后端 ${port} 退出（${code}）：${output.slice(-500)}`)); });
    });
}

export function stopServers(children) {
    for (const child of children) {
        if (child && child.exitCode === null) child.kill();
    }
}

// 解析 --name value；值可以逗号分隔或连续多个（兼容 PowerShell 把 a,b 拆成两个参数）。
export function parseArgs(argv, defaults) {
    const options = {...defaults};
    for (let i = 0; i < argv.length; i++) {
        if (!argv[i].startsWith('--')) continue;
        const key = argv[i].slice(2);
        const values = [];
        while (i + 1 < argv.length && !argv[i + 1].startsWith('--')) values.push(...argv[++i].split(',').filter(Boolean));
        options[key] = values.length === 0 ? true : values.length === 1 ? values[0] : values;
    }
    return options;
}

export const list = value => (Array.isArray(value) ? value : String(value).split(',')).map(v => v.trim()).filter(Boolean);
