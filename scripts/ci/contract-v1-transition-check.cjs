'use strict';
/**
 * v1 锚点契约过渡豁免判定（一次性迁移辅助，非门禁逻辑）。
 *
 * ce283af6 时代的 gate-contract v1 的 pre-push 场景用「无 stdin 直接调用」执行候选
 * pre-push hook（空 stdin → trusted hook 必然 exit 0），其自身设计下永远失败——
 * 这是 v1 契约的死代码场景（该路径从未被真正执行过，见契约重构记录）。
 * 本检查用于：锚点契约失败时，若失败项全部是 v1 的死代码 pre-push 场景，
 * 判定为「仅死于自身死代码」，允许人工审核过渡；其它任何失败一律不豁免。
 *
 * 用法：
 *   node contract-v1-transition-check.mjs <contract-report.json>
 * 退出码：
 *   0 = 允许过渡（v1 契约仅死于死代码 pre-push 场景）
 *   1 = 不豁免（v2+ 契约 / 存在其它失败项）
 *   2 = 报告缺失或无法解析（fail closed）
 */

const fs = require('fs');

function main() {
    const reportPath = process.argv[2];
    if (!reportPath) {
        process.exit(2);
    }
    let report;
    try {
        report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
    } catch (e) {
        process.exit(2);
    }
    if (String(report.contractVersion) !== '1') {
        process.exit(1);
    }
    const failed = (report.checks || []).filter((check) => !check.ok && check.kind !== 'report');
    const broken = failed.filter((check) =>
        check.kind === 'hooks' && /^candidate pre-push:/.test(String(check.name || '')));
    if (failed.length > 0 && failed.length === broken.length) {
        process.exit(0);
    }
    process.exit(1);
}

main();
