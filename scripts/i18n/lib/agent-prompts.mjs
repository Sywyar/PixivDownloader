'use strict';
/**
 * 模块化 Agent 翻译提示词生成（build/reports/i18n/prompts/）。
 * 每个模块提示词是可独立交给另一个 Agent 执行的完整任务；
 * master.md 按「语言 → 模块 → bundle」组织，允许多个 Agent 在互不重叠的文件范围并行翻译。
 */

import fs from 'fs';
import path from 'path';

const PROMPTS_DIR = path.join('build', 'reports', 'i18n', 'prompts');

const FORBIDDEN_INSTRUCTIONS = [
    '- 禁止修改中文源文件（zh-CN）',
    '- 禁止修改任何 key',
    '- 禁止修改 i18n/locales.json 目录清单',
    '- 禁止修改 Java / JavaScript 业务代码',
    '- 禁止删除未理解的文案',
    '- 禁止用机器翻译占位或复制中文冒充完成',
    '- 禁止执行 `--allow-unchanged`',
    '- 禁止推送远端',
];

function write(repoRoot, report, catalog) {
    const dir = path.join(repoRoot, PROMPTS_DIR);
    fs.rmSync(dir, { recursive: true, force: true });
    fs.mkdirSync(dir, { recursive: true });

    const perLocale = new Map(); // locale -> Map(bundleId -> {bundle, issues})
    for (const issue of report.issues) {
        if (!['missing', 'stale', 'invalid', 'empty', 'extra'].includes(issue.type)) {
            continue;
        }
        if (!perLocale.has(issue.locale)) {
            perLocale.set(issue.locale, new Map());
        }
        const bundleMap = perLocale.get(issue.locale);
        if (!bundleMap.has(issue.bundle)) {
            bundleMap.set(issue.bundle, { issues: [] });
        }
        bundleMap.get(issue.bundle).issues.push(issue);
    }

    for (const [locale, bundleMap] of [...perLocale.entries()].sort()) {
        const localeDir = path.join(dir, locale);
        fs.mkdirSync(localeDir, { recursive: true });
        for (const [bundleId, data] of [...bundleMap.entries()].sort()) {
            fs.writeFileSync(
                path.join(localeDir, promptFileName(bundleId) + '.md'),
                bundlePrompt(repoRoot, catalog, locale, bundleId, data.issues) + '\n',
                'utf8');
        }
    }

    fs.writeFileSync(path.join(dir, 'master.md'), masterPrompt(catalog, perLocale) + '\n', 'utf8');
}

function masterPrompt(catalog, perLocale) {
    const lines = [];
    lines.push('# i18n 翻译任务总览（master）');
    lines.push('');
    lines.push(`- 仓库：Sywyar/PixivDownloader`);
    lines.push(`- 源语言：${catalog.sourceLocale}（开发源语言，禁止修改）`);
    lines.push(`- 全局回退语言：${catalog.fallbackLocale}`);
    lines.push(`- 默认语言：${catalog.defaultLocale}`);
    lines.push('');
    lines.push('按「语言 → 模块 → bundle」拆分，每个 bundle 文件独立、可并行执行；');
    lines.push('不同 bundle 的文件范围互不重叠，可放心交给多个 Agent 同时翻译。');
    lines.push('');
    if (perLocale.size === 0) {
        lines.push('（当前没有需要翻译的 bundle）');
        return lines.join('\n');
    }
    for (const [locale, bundleMap] of [...perLocale.entries()].sort()) {
        lines.push(`## ${locale}`);
        lines.push('');
        for (const bundleId of [...bundleMap.keys()].sort()) {
            lines.push(`- [${bundleId}](${locale}/${bundleId}.md)`);
        }
        lines.push('');
    }
    return lines.join('\n');
}

function bundlePrompt(repoRoot, catalog, locale, bundleId, issues) {
    const first = issues[0];
    const lines = [];
    lines.push('# i18n 翻译任务：' + bundleId);
    lines.push('');
    lines.push('## 任务信息');
    lines.push('');
    lines.push(`- 仓库：Sywyar/PixivDownloader`);
    lines.push(`- 目标语言：${locale}`);
    lines.push(`- 源语言：${catalog.sourceLocale}`);
    lines.push(`- 全局回退语言：${catalog.fallbackLocale}`);
    lines.push(`- 模块：${first.module}`);
    lines.push(`- namespace / baseName：${first.baseName}`);
    lines.push('');
    lines.push('## 允许修改的文件');
    lines.push('');
    lines.push(`- ${first.file || first.module + ' 下的 ' + first.baseName + ' 翻译资源文件'}`);
    lines.push('');
    lines.push('## 禁止修改的文件');
    lines.push('');
    lines.push('- 所有中文源文件（' + catalog.sourceLocale + '）');
    lines.push('- i18n/locales.json');
    lines.push('- 所有 Java / JavaScript 业务代码');
    lines.push('- i18n/catalog-lock.json（由 `i18n:accept` 生成，不要手工编辑）');
    lines.push('');
    lines.push('## 翻译规则');
    lines.push('');
    for (const rule of FORBIDDEN_INSTRUCTIONS) {
        lines.push(rule);
    }
    lines.push('');
    lines.push('必须保留以下占位符与不可翻译 token：');
    lines.push('- 命名占位符 `{name}` / `{count}` / `{time}` 与位置占位符 `{0}` / `{1}` 等：名称与数量必须与中文一致，顺序可调整');
    lines.push('- HTML 标签、URL、文件扩展名、命令、产品名、代码片段：按中文原文保留');
    lines.push('- 只有明显属于专有名词（Pixiv / API / AI / URL / 数字 / 文件名）的条目允许与中文相同；整句复制中文属于违规');
    lines.push('');
    lines.push('## 需要翻译的 key');
    lines.push('');
    lines.push('| key | 类型 | 中文源文本 | 当前目标文本 | 必须保留的占位符 |');
    lines.push('| --- | --- | --- | --- | --- |');
    for (const issue of [...issues].sort((a, b) => String(a.key).localeCompare(String(b.key)))) {
        const placeholders = (issue.placeholders || []).join(' ');
        const source = oneLine(issue.sourceValue != null ? issue.sourceValue : '');
        const current = oneLine(issue.translationValue != null ? issue.translationValue : '');
        lines.push(`| ${issue.key || ''} | ${issue.type} | ${source} | ${current} | ${placeholders} |`);
    }
    lines.push('');
    lines.push('## 执行命令');
    lines.push('');
    lines.push('```bash');
    lines.push('npm run i18n:check');
    lines.push('npm run i18n:accept -- --locale ' + locale + ' --module ' + first.module);
    lines.push('```');
    lines.push('');
    lines.push('## 验收条件');
    lines.push('');
    lines.push(`- [ ] ${locale} 与中文的 key 完全一致（无 missing / extra）`);
    lines.push(`- [ ] 无空翻译、无占位符不一致、无解析错误`);
    lines.push(`- [ ] 占位符数量与名称与中文一致`);
    lines.push(`- [ ] \`npm run i18n:check\` 通过`);
    lines.push(`- [ ] 对修改过的 key 执行 \`npm run i18n:accept -- --locale ${locale}\` 更新已审核基线`);
    lines.push('');
    return lines.join('\n');
}

/** bundleId 含 /（如 ai/messages）时规范为文件名安全形式。 */
function promptFileName(bundleId) {
    return bundleId.replace(/\//g, '_');
}

function oneLine(value) {
    return String(value).replace(/\r?\n/g, '\\n').replace(/\|/g, '\\|');
}

export {  write, PROMPTS_DIR  };

export default { write, PROMPTS_DIR };
