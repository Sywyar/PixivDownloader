'use strict';
/**
 * 机器报告（report.json）与人类摘要（summary.md）生成。
 * 全部列表确定性排序，避免每次执行产生随机 diff。
 */

import fs from 'fs';
import path from 'path';

const REPORT_DIR = path.join('build', 'reports', 'i18n');

function sortIssues(a, b) {
    return String(a.locale).localeCompare(String(b.locale))
        || String(a.module).localeCompare(String(b.module))
        || String(a.baseName).localeCompare(String(b.baseName))
        || String(a.key).localeCompare(String(b.key))
        || String(a.type).localeCompare(String(b.type));
}

function sortCoverage(a, b) {
    return String(a.locale).localeCompare(String(b.locale))
        || String(a.module).localeCompare(String(b.module))
        || String(a.baseName).localeCompare(String(b.baseName));
}

function sortWarnings(a, b) {
    return String(a.locale).localeCompare(String(b.locale))
        || String(a.module).localeCompare(String(b.module))
        || String(a.baseName).localeCompare(String(b.baseName))
        || String(a.key).localeCompare(String(b.key))
        || String(a.type).localeCompare(String(b.type));
}

/**
 * @param {Object} report { catalog, issues, coverage, warnings, catalogError }
 * @param {Object} [options] { snapshotRef: 快照检查时记录被检查的 commit，供 pre-push 多 commit 报告定位 }
 */
function write(repoRoot, report, options = {}) {
    const dir = path.join(repoRoot, REPORT_DIR);
    fs.mkdirSync(dir, { recursive: true });

    const payload = {
        generatedAt: new Date().toISOString(),
        snapshotRef: options.snapshotRef || null,
        catalog: report.catalogError
            ? { error: report.catalogError }
            : {
                sourceLocale: report.catalog.sourceLocale,
                defaultLocale: report.catalog.defaultLocale,
                fallbackLocale: report.catalog.fallbackLocale,
                locales: report.catalog.locales.map((d) => ({
                    tag: d.tag, nativeName: d.nativeName, status: d.status,
                    resourceSuffix: d.resourceSuffix, direction: d.direction,
                })),
            },
        issues: [...report.issues].sort(sortIssues),
        coverage: [...report.coverage].sort(sortCoverage),
        warnings: [...report.warnings].sort(sortWarnings),
    };
    fs.writeFileSync(path.join(dir, 'report.json'), JSON.stringify(payload, null, 2) + '\n', 'utf8');

    fs.writeFileSync(path.join(dir, 'summary.md'), summarize(payload) + '\n', 'utf8');
}

function summarize(report) {
    const lines = [];
    lines.push('# i18n 检查摘要');
    lines.push('');
    if (report.catalog.error) {
        lines.push('## catalog 错误');
        lines.push('');
        lines.push('```');
        lines.push(report.catalog.error);
        lines.push('```');
        return lines.join('\n');
    }
    if (report.snapshotRef) {
        lines.push('- snapshotRef: ' + report.snapshotRef);
    }
    lines.push('- source: ' + report.catalog.sourceLocale);
    lines.push('- default: ' + report.catalog.defaultLocale);
    lines.push('- fallback: ' + report.catalog.fallbackLocale);
    lines.push('');
    lines.push('## 覆盖率');
    lines.push('');
    lines.push('| locale | module | baseName | source keys | translated | missing | empty | stale | invalid | extra | coverage |');
    lines.push('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |');
    for (const row of report.coverage) {
        lines.push(`| ${row.locale} | ${row.module} | ${row.baseName} | ${row.sourceKeys} | ${row.translated} | ${row.missing} | ${row.empty} | ${row.stale} | ${row.invalid} | ${row.extra} | ${row.coverage} |`);
    }
    lines.push('');
    lines.push('## 问题');
    lines.push('');
    if (report.issues.length === 0) {
        lines.push('（无）');
    } else {
        for (const issue of report.issues) {
            lines.push(`- [${issue.severity}] ${issue.locale} ${issue.module} ${issue.baseName} ${issue.key || ''} — ${issue.type}: ${issue.message}`);
        }
    }
    lines.push('');
    lines.push('## 警告');
    lines.push('');
    if (report.warnings.length === 0) {
        lines.push('（无）');
    } else {
        for (const warning of report.warnings) {
            lines.push(`- [warning] ${warning.locale} ${warning.module} ${warning.baseName} ${warning.key} — ${warning.type}: ${warning.message}`);
        }
    }
    return lines.join('\n');
}

export {  write, REPORT_DIR  };

export default { write, REPORT_DIR };
