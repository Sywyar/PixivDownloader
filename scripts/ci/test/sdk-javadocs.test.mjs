'use strict';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'sdk-javadocs.mjs');
const MODULES = ['pixivdownload-sdk-info', 'pixivdownload-plugin-api', 'pixivdownload-core-api'];

function fixture() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-sdk-javadocs-'));
    for (const module of MODULES) {
        const directory = path.join(root, module, 'target', 'reports', 'apidocs');
        fs.mkdirSync(directory, { recursive: true });
        fs.writeFileSync(path.join(directory, 'index.html'), module, 'utf8');
        const sourceDirectory = path.join(root, module, 'src', 'main', 'java');
        fs.mkdirSync(sourceDirectory, { recursive: true });
        fs.writeFileSync(path.join(sourceDirectory, 'Contract.java'), '/** 中文契约。 */\npublic interface Contract {}\n', 'utf8');
    }
    return root;
}

function run(root) {
    return spawnSync(process.execPath, [SCRIPT, '--repo-root', root], { encoding: 'utf8' });
}

test('SDK Javadoc assembler creates one portal for all public modules', () => {
    const root = fixture();
    try {
        const result = run(root);
        assert.equal(result.status, 0, result.stderr);
        const portal = fs.readFileSync(path.join(root, 'target', 'sdk-javadocs', 'index.html'), 'utf8');
        assert.match(portal, /\.\/sdk-info\/index\.html/);
        assert.match(portal, /\.\/plugin-api\/index\.html/);
        assert.match(portal, /\.\/core-api\/index\.html/);
        assert.match(portal, /<html lang=\x22zh-CN\x22>/);
        assert.match(portal, /PixivDownloader 插件 SDK API/);
        assert.equal(fs.readFileSync(path.join(root, 'target', 'sdk-javadocs', 'plugin-api', 'index.html'), 'utf8'),
            'pixivdownload-plugin-api');
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('SDK Javadoc assembler rejects English-only API comments', () => {
    const root = fixture();
    try {
        fs.writeFileSync(
            path.join(root, 'pixivdownload-plugin-api', 'src', 'main', 'java', 'Contract.java'),
            '/** English-only contract. */\npublic interface Contract {}\n',
            'utf8');
        const result = run(root);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /non-Chinese Javadocs in .*Contract\.java/);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('SDK Javadoc assembler fails when a module site is absent', () => {
    const root = fixture();
    try {
        fs.rmSync(path.join(root, 'pixivdownload-core-api'), { recursive: true, force: true });
        const result = run(root);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /missing generated Javadocs for pixivdownload-core-api/);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
