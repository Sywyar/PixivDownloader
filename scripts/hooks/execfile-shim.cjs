'use strict';
/**
 * execFileSync 空返回归一化 shim（Node 兼容层）。
 *
 * Node 的 execFileSync 在 stdio: 'ignore' 时返回 null（而不是 ''），
 * 早期（v1 契约）的 trusted gate-contract 对返回值直接 .trim()，
 * 导致候选 hook 与 trusted bundle 不一致时契约进程崩溃（null.trim）。
 * 本 shim 通过 --require 注入：execFileSync 返回 null 时归一化为 ''，
 * 其余行为完全不变（非零退出仍抛错、pipe 输出原样返回）。
 *
 * 用途：hooks 与 trust-gate CLI 以 --require 运行 trusted anchor 的契约，
 * 使旧 anchor 契约也能完成候选校验；只修正 Node 平台怪癖，不改变任何判定逻辑。
 */

const childProcess = require('child_process');

const originalExecFileSync = childProcess.execFileSync;

childProcess.execFileSync = function patchedExecFileSync(...args) {
    const result = originalExecFileSync.apply(this, args);
    return result === null ? '' : result;
};
