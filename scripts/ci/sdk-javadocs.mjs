'use strict';

import fs from 'node:fs';
import path from 'node:path';

const MODULES = [
    ['sdk-info', 'SDK 信息', 'pixivdownload-sdk-info'],
    ['plugin-api', '插件 API', 'pixivdownload-plugin-api'],
    ['core-api', '核心 API', 'pixivdownload-core-api'],
];

function fail(message) {
    throw new Error(message);
}

function repoRoot(values) {
    if (values.length === 0) return '.';
    if (values.length === 2 && values[0] === '--repo-root') return values[1];
    fail('usage: node sdk-javadocs.mjs [--repo-root <path>]');
}

const root = path.resolve(repoRoot(process.argv.slice(2)));
const output = path.join(root, 'target', 'sdk-javadocs');
fs.rmSync(output, { recursive: true, force: true });
fs.mkdirSync(output, { recursive: true });

for (const [directory, , module] of MODULES) {
    const source = path.join(root, module, 'target', 'reports', 'apidocs');
    if (!fs.existsSync(path.join(source, 'index.html'))) {
        fail(`missing generated Javadocs for ${module}`);
    }
    const javaRoot = path.join(root, module, 'src', 'main', 'java');
    const pending = [javaRoot];
    while (pending.length > 0) {
        const current = pending.pop();
        for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
            const sourcePath = path.join(current, entry.name);
            if (entry.isDirectory()) {
                pending.push(sourcePath);
                continue;
            }
            if (!entry.name.endsWith('.java')) continue;
            const sourceText = fs.readFileSync(sourcePath, 'utf8');
            const comments = sourceText.match(/\/\*\*[\s\S]*?\*\//g) ?? [];
            if (comments.length === 0) fail(`missing Javadocs in ${path.relative(root, sourcePath)}`);
            const englishOnly = comments.find(comment => !/\p{Script=Han}/u.test(comment));
            if (englishOnly) fail(`non-Chinese Javadocs in ${path.relative(root, sourcePath)}`);
        }
    }
    fs.cpSync(source, path.join(output, directory), { recursive: true });
}

const links = MODULES.map(([directory, title]) =>
    `        <li><a href="./${directory}/index.html">${title}</a></li>`).join('\n');
fs.writeFileSync(path.join(output, 'index.html'), `<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>PixivDownloader 插件 SDK API</title>
</head>
<body>
    <main>
        <h1>PixivDownloader 插件 SDK API</h1>
        <ul>
${links}
        </ul>
    </main>
</body>
</html>
`, 'utf8');

console.log(`SDK Javadoc 已汇总至 ${path.relative(root, output)}`);
