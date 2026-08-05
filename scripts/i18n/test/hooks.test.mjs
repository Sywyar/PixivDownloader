'use strict';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import staleLock from '../lib/stale-lock.mjs';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function git(args, cwd) {
    return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

function makeGitRepo() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-hooks-'));
    git(['init', '-q'], root);
    git(['config', 'user.email', 't@example.com'], root);
    git(['config', 'user.name', 'test'], root);
    // 模拟仓库 hooks 目录
    fs.mkdirSync(path.join(root, 'scripts', 'hooks'), { recursive: true });
    for (const hook of ['pre-commit', 'pre-push', 'pre-push-guard.sh']) {
        fs.writeFileSync(path.join(root, 'scripts', 'hooks', hook), '#!/usr/bin/env bash\nexit 0\n', 'utf8');
    }
    return root;
}

test('install-hooks 幂等且只写 local 配置', () => {
    const root = makeGitRepo();
    try {
        const run = () => execFileSync('node',
            [path.join(SCRIPTS_DIR, 'install-hooks.mjs')], { cwd: root, encoding: 'utf8' });
        run();
        assert.equal(git(['config', '--local', '--get', 'core.hooksPath'], root), 'scripts/hooks');
        // 幂等：重复执行成功且值不变
        run();
        assert.equal(git(['config', '--local', '--get', 'core.hooksPath'], root), 'scripts/hooks');
        // 不修改 global 配置
        let globalValue = '(unset)';
        try {
            globalValue = execFileSync('git', ['config', '--global', '--get', 'core.hooksPath'],
                { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
        } catch (e) {
            globalValue = '(unset)';
        }
        assert.notEqual(globalValue, 'scripts/hooks');
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('install-hooks 缺失 hooks 文件时失败', () => {
    const root = makeGitRepo();
    try {
        fs.rmSync(path.join(root, 'scripts', 'hooks', 'pre-commit'));
        assert.throws(() => execFileSync('node',
            [path.join(SCRIPTS_DIR, 'install-hooks.mjs')], { cwd: root, encoding: 'utf8' }));
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('doctor-hooks：正确配置通过，错误配置失败', () => {
    const root = makeGitRepo();
    try {
        const doctor = () => execFileSync('node',
            [path.join(SCRIPTS_DIR, 'doctor-hooks.mjs')], { cwd: root, encoding: 'utf8' });
        // 未配置 → 失败
        assert.throws(() => doctor());
        // 配置后 → 通过
        execFileSync('node', [path.join(SCRIPTS_DIR, 'install-hooks.mjs')], { cwd: root, encoding: 'utf8' });
        doctor();
        // 错误配置 → 失败
        git(['config', '--local', 'core.hooksPath', 'scripts/other'], root);
        assert.throws(() => doctor());
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('stale-lock：保存/加载往返、确定性排序、hash 不受注释/排序影响', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-lock-'));
    try {
        const entries = [
            { locale: 'en-US', module: 'm', baseName: 'b', key: 'z', acceptedSourceHash: 'h1', acceptedTranslationHash: 't1' },
            { locale: 'en-US', module: 'm', baseName: 'b', key: 'a', acceptedSourceHash: 'h2', acceptedTranslationHash: 't2' },
        ];
        staleLock.save(root, { entries });
        const loaded = staleLock.load(root);
        assert.equal(loaded.entries.length, 2);
        // 确定性排序
        assert.equal(loaded.entries[0].key, 'a');
        assert.equal(loaded.entries[1].key, 'z');

        // hash 只依赖规范值：换行与首尾空白不影响
        assert.equal(staleLock.hashValue(' x\r\ny '), staleLock.hashValue('x\ny'));
        assert.notEqual(staleLock.hashValue('x'), staleLock.hashValue('y'));
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('Agent 提示词：文件范围正确且禁止项齐全', async () => {
    const prompts = await import('../lib/agent-prompts.mjs');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-prompts-'));
    try {
        const catalog = { sourceLocale: 'zh-CN', fallbackLocale: 'en-US', defaultLocale: 'zh-CN', locales: [] };
        prompts.default.write(root, {
            catalog,
            issues: [
                { locale: 'en-US', module: 'pixivdownload-app', baseName: 'web/common', bundle: 'pixivdownload-app__common', file: 'x.properties', key: 'greeting', type: 'missing', severity: 'error', message: 'missing', sourceValue: '你好', translationValue: null, placeholders: ['{name}'] },
                { locale: 'en-US', module: 'pixivdownload-app', baseName: 'web/common', bundle: 'pixivdownload-app__common', file: 'x.properties', key: 'title', type: 'stale', severity: 'error', message: 'stale', sourceValue: '标题', translationValue: 'Title', placeholders: [] },
            ],
            coverage: [], warnings: [],
        }, catalog);

        const promptPath = path.join(root, 'build', 'reports', 'i18n', 'prompts');
        const master = fs.readFileSync(path.join(promptPath, 'master.md'), 'utf8');
        assert.match(master, /pixivdownload-app__common/);
        assert.match(master, /Sywyar\/PixivDownloader/);

        const bundlePrompt = fs.readFileSync(
            path.join(promptPath, 'en-US', 'pixivdownload-app__common.md'), 'utf8');
        for (const forbidden of [
            '禁止修改中文源文件', '禁止修改任何 key', '禁止修改 i18n/locales.json',
            '禁止修改 Java / JavaScript 业务代码', '禁止删除未理解的文案',
            '禁止用机器翻译占位或复制中文冒充完成', '禁止执行 `--allow-unchanged`', '禁止推送远端',
        ]) {
            assert.ok(bundlePrompt.includes(forbidden), 'missing forbidden rule: ' + forbidden);
        }
        assert.match(bundlePrompt, /{name}/); // 必须保留的占位符
        assert.match(bundlePrompt, /i18n:accept/); // 执行命令
        assert.match(bundlePrompt, /验收条件/);
        // 只允许修改该 bundle 的翻译文件
        assert.match(bundlePrompt, /允许修改的文件/);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
