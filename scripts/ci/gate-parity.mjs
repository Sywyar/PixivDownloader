#!/usr/bin/env node
'use strict';
/**
 * Gate Parity / Monotonicity 审计（Gate Epoch 2 标准的一部分）。
 *
 * 回答：候选提交是否删除、缩小、弱化了任何既有质量门禁？
 * 它不兼容旧 gate，也不是旧 contract 的替代品；它冻结「门禁不可减少」不变量：
 * - policy 集合：gateEpoch 不变 / contractVersion 不降 / requiredPaths、
 *   protectedBranches、requiredWorkflowJobs、requiredPackageScripts、
 *   requiredExternalChecks 不减少（允许新增）；
 * - quality-gate.yml：trigger / 必需 job / Java compile fixture / 完整 Maven test /
 *   JS tests / web standards / signature guard（必须来自 trusted base）/
 *   i18n CI=true tests / ref snapshot / worktree / static generate / static diff /
 *   report upload / result propagation / root tag 与 ROOT_ADMISSION 机制 /
 *   input 优先级 / trusted helper 交叉验证 不减少（shell 规范化后检查实际命令）；
 * - package.json：必需 scripts 存在且指向真实入口（不得 = true / echo ok）；
 * - gate-invariants.json：候选 policy / workflow / package 必须满足 Epoch 2 最低合同
 *   （root admission 模式下没有 trusted predecessor，用 invariants 作为最低线）。
 *
 * 用法：
 *   node gate-parity.mjs --repo-root <repo> --trusted-dir <dir> --candidate-ref <sha>
 *   node gate-parity.mjs --repo-root <repo> --trusted-dir <dir> --candidate-snapshot index
 *   node gate-parity.mjs --repo-root <repo> --candidate-ref <sha> --invariants
 *   node gate-parity.mjs --version
 *   --report-root <dir>（默认 repo root；报告写 build/reports/i18n/parity.json）
 *
 * 退出码：0 = 无减少；1 = 发现减少（fail closed）；2 = 用法 / 解析错误。
 * 本地 Git hooks / 仓库内 workflow 不能宣称绝对不可绕过；本审计只是 Epoch 2 门禁的
 * 一部分，最终 required check / branch protection 由 GitHub Ruleset 提供。
 */
import { execFileSync, spawnSync } from 'child_process';
import { createRequire } from 'module';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath } from 'url';

import snapshot from '../i18n/lib/repository-snapshot.mjs';

const VERSION = '1';
const WORKFLOW_REL = path.posix.join('.github', 'workflows', 'quality-gate.yml');
const PACKAGE_JSON_REL = path.posix.join('package.json');
const POLICY_REL = path.posix.join('scripts', 'i18n', 'gate-policy.json');
const INVARIANTS_REL = path.posix.join('scripts', 'ci', 'gate-invariants.json');
const CANDIDATE_PATHS = [
    'scripts/i18n',
    'scripts/hooks',
    'scripts/ci',
    '.github/workflows/quality-gate.yml',
    'package.json',
    'package-lock.json',
];

const REQUIRED_TRIGGERS = ['push', 'pull_request', 'merge_group', 'workflow_dispatch', 'workflow_call'];
const REQUIRED_WORKFLOW_JOBS = ['java-tests', 'javascript-tests', 'signature-guard', 'trusted-gate-contract', 'i18n-check'];
/** Epoch 2 门禁的最低 package scripts 集合（policy.requiredPackageScripts 在此基础上只增不减）。 */
const REQUIRED_SCRIPTS = ['test:i18n', 'i18n:check', 'i18n:generate-static', 'i18n:trust-gate',
    'i18n:gate-contract', 'i18n:gate-parity', 'test:js', 'test:web-standards'];
const TRUSTED_LOC = /\$GATE_DIR|\$RUNNER_TEMP|\bguard\/out\b|materialize-trusted-gate/;

const SHA_RE = /^[0-9a-f]{40}$/;

function fail(message) {
    console.error('gate-parity ERROR: ' + message);
    process.exit(2);
}

// 进程退出（含 fail() / 拒绝 verdict 的 process.exit 路径）也必须清理会话级临时快照目录
process.on('exit', () => {
    try {
        snapshot.cleanupAll();
    } catch (ignored) {
        // 退出清理失败不掩盖 verdict
    }
});

function git(args, repoRoot, opts = {}) {
    return (execFileSync('git', args, {
        cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], ...opts,
    }) || '').trim();
}

/** 与 gate-contract 同构的 shell 规范化：只保留实际可执行命令。 */
function extractCommands(script) {
    const lines = String(script || '').split(/\r?\n/);
    const statements = [];
    for (const raw of lines) {
        const line = raw.replace(/#.*$/, '').trim();
        if (!line) {
            continue;
        }
        const protectedLine = line.replace(/(\d*)([<>])&(\d*)/g, '$1$2REDIRECT$3');
        const parts = protectedLine.split(/\s*[;|&]{1,2}\s*/).map((p) => p.trim()).filter(Boolean);
        for (const part of parts) {
            const command = part.trim();
            if (!command) {
                continue;
            }
            if (/^(true|:|exit(\s+0)?|echo(\s.*)?)$/.test(command)) {
                continue;
            }
            statements.push(command);
        }
    }
    return statements;
}

function commandsInclude(script, re) {
    return extractCommands(script).some((c) => re.test(c));
}

function hasNoopSwallow(script) {
    const s = String(script || '');
    return /(\|\||;)\s*(true|:)(\s|;|$|\|\||&&)/.test(s)
        || /(\|\||;)\s*exit(?:\s*0)?(?=\s*(?:;|$|\|\||&&))/.test(s)
        || /;\s*exit(?!\s*[1-9])/.test(s);
}

function hasConditionalSkip(script) {
    return /if\s+false\s*;?\s*then/.test(String(script || ''));
}

function isNoopStep(script) {
    const stripped = String(script || '').replace(/#.*$/g, '').replace(/\s+/g, ' ').trim();
    if (!stripped) {
        return true;
    }
    return /^(true|:|exit(\s+0)?|echo(\s.*)?)$/.test(stripped);
}

function stepRun(step) {
    return typeof step.run === 'string' ? step.run : '';
}

function stepUses(step) {
    return typeof step.uses === 'string' ? step.uses : '';
}

function stepIf(step) {
    return typeof step.if === 'string' ? step.if : '';
}

function jobSteps(job) {
    return job && Array.isArray(job.steps) ? job.steps : [];
}

function jobHasRun(job, re) {
    return jobSteps(job).some((s) => commandsInclude(stepRun(s), re));
}

function jobHasUses(job, re) {
    return jobSteps(job).some((s) => re.test(stepUses(s)));
}

function jobHasUploadAlways(job) {
    const uploads = jobSteps(job).filter((s) => /actions\/upload-artifact@/.test(stepUses(s)));
    return uploads.length > 0 && uploads.every((s) => /always\(\)/.test(stepIf(s)));
}

/** step 是否运行了关键门禁命令（只有关键命令被吞掉才构成降级）。 */
const CRITICAL_RUN_RE = [
    /\bmvn\b/,
    /npm run test:js/,
    /npm run test:web-standards/,
    /npm run test:i18n/,
    /npm run i18n:check/,
    /i18n:generate-static/,
    /git diff --exit-code/,
    /gate-contract\.mjs/,
    /gate-parity\.mjs/,
    /pre-push-guard\.sh/,
    /check\.mjs/,
];

function isCriticalStep(script) {
    return extractCommands(script).some((c) => CRITICAL_RUN_RE.some((re) => re.test(c)));
}

function isBannedScript(value) {
    if (typeof value !== 'string') {
        return true;
    }
    const v = value.trim();
    if (/^(true|:|exit(\s+0)?|echo|printf|:\s*true)/i.test(v)) {
        return true;
    }
    return !(/\b(node|mvn)\b/.test(v) || v.includes('/'));
}

function loadYamlModule(repoRoot) {
    const anchors = [path.join(repoRoot, 'package.json')];
    if (process.env.NODE_PATH) {
        let nodePath = process.env.NODE_PATH;
        if (process.platform !== 'win32' && /^[A-Za-z]:[\\/]/.test(nodePath)) {
            nodePath = '/mnt/' + nodePath[0].toLowerCase() + '/' + nodePath.slice(2).replace(/\\/g, '/');
        }
        anchors.push(path.join(nodePath, 'package.json'));
    }
    let lastError = null;
    for (const anchor of anchors) {
        try {
            const yaml = createRequire(anchor)('yaml');
            if (yaml && typeof yaml.parse === 'function' && typeof yaml.stringify === 'function') {
                return yaml;
            }
        } catch (e) {
            lastError = e;
        }
    }
    fail('the yaml parser dependency is required by gate-parity (npm ci / npm install first): '
        + (lastError ? lastError.message : 'yaml not found'));
    return null;
}

function loadJson(dir, rel) {
    const file = path.join(dir, ...rel.split('/'));
    if (!fs.existsSync(file)) {
        return null;
    }
    return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function setReduced(trustedList, candidateList) {
    const trusted = new Set(trustedList || []);
    const candidate = new Set(candidateList || []);
    return [...trusted].filter((entry) => !candidate.has(entry));
}

function parseArgs(argv) {
    const args = { repoRoot: null, trustedDir: null, candidateRef: null, mode: null,
        reportRoot: null, invariants: false, version: false };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        const value = () => argv[++i];
        if (arg === '--repo-root') {
            args.repoRoot = value();
        } else if (arg === '--trusted-dir') {
            args.trustedDir = value();
        } else if (arg === '--candidate-ref') {
            args.candidateRef = value();
            args.mode = 'ref';
        } else if (arg === '--candidate-snapshot') {
            args.mode = value();
        } else if (arg === '--report-root') {
            args.reportRoot = value();
        } else if (arg === '--invariants') {
            args.invariants = true;
        } else if (arg === '--version') {
            args.version = true;
        } else {
            throw new Error('unknown argument: ' + arg);
        }
    }
    if (!args.version) {
        if (!args.repoRoot) {
            throw new Error('--repo-root <path> is required');
        }
        if (args.invariants) {
            if (args.mode !== 'ref' || !args.candidateRef) {
                throw new Error('--invariants requires --candidate-ref <sha>');
            }
        } else if (!args.trustedDir || !fs.existsSync(path.join(args.trustedDir, 'scripts', 'i18n'))) {
            throw new Error('--trusted-dir <dir> (materialized trusted gate) is required');
        }
        if (args.mode === 'index') {
            args.candidateRef = null;
        } else if (args.mode === 'ref') {
            if (!args.candidateRef) {
                throw new Error('--candidate-ref <sha> is required');
            }
        } else {
            throw new Error('--candidate-snapshot index or --candidate-ref <sha> is required');
        }
    }
    return args;
}

/**
 * workflow 审计（候选 vs 可信/invariants）。
 * @param {Object} trustedDoc 可信 workflow（YAML 解析）或 null（invariants 模式）
 * @param {Object} candidateDoc 候选 workflow
 */
function auditWorkflow(checks, trustedDoc, candidateDoc, candidateRoot) {
    const pushCheck = (name, ok, diagnostic) => {
        checks.push({ name, kind: 'workflow', expected: ok ? 'present' : 'absent', status: null, ok,
            diagnostic: ok ? '' : diagnostic });
    };
    const trustedJobs = trustedDoc && trustedDoc.jobs && typeof trustedDoc.jobs === 'object'
        ? trustedDoc.jobs : null;
    const candidateJobs = candidateDoc && candidateDoc.jobs && typeof candidateDoc.jobs === 'object'
        ? candidateDoc.jobs : {};

    // triggers：trusted 的每个 trigger 都必须保留
    if (trustedDoc && trustedDoc.on && typeof trustedDoc.on === 'object') {
        for (const trigger of Object.keys(trustedDoc.on)) {
            pushCheck('trigger ' + trigger + ' is preserved',
                candidateDoc && candidateDoc.on && candidateDoc.on[trigger] !== undefined,
                'candidate workflow dropped the ' + trigger + ' trigger');
        }
    }
    if (trustedDoc) {
        for (const trigger of REQUIRED_TRIGGERS) {
            pushCheck('trigger ' + trigger + ' is preserved',
                candidateDoc && candidateDoc.on && candidateDoc.on[trigger] !== undefined,
                'candidate workflow dropped the required ' + trigger + ' trigger');
        }
    }

    // jobs：trusted / required job 集合必须全部存在
    const jobNames = new Set(Object.keys(candidateJobs));
    const requiredJobs = trustedJobs ? new Set(Object.keys(trustedJobs)) : new Set();
    for (const jobId of REQUIRED_WORKFLOW_JOBS) {
        requiredJobs.add(jobId);
    }
    const missingJobs = [...requiredJobs].filter((j) => !jobNames.has(j));
    pushCheck('required workflow jobs preserved', missingJobs.length === 0,
        'candidate workflow dropped required jobs: ' + missingJobs.join(', '));

    // 关键行为（规范化命令）
    const jJava = candidateJobs['java-tests'];
    pushCheck('java-tests: compile external plugin fixtures',
        jobHasRun(jJava, /mvn/) && jobHasRun(jJava, /pixivdownload-official-plugins/) && jobHasRun(jJava, /compile/),
        'java-tests must compile the external plugin fixtures');
    pushCheck('java-tests: full maven tests',
        jobHasRun(jJava, /mvn/) && jobHasRun(jJava, /test/) && jobHasRun(jJava, /exec\.skip/),
        'java-tests must run the full maven tests');
    pushCheck('java-tests: no -DskipTests',
        !jobSteps(jJava).some((s) => commandsInclude(stepRun(s), /mvn/) && /-DskipTests/.test(stepRun(s))),
        'java-tests must never skip tests');
    const jJs = candidateJobs['javascript-tests'];
    pushCheck('javascript-tests: npm ci', jobHasRun(jJs, /npm\s+ci/), 'javascript-tests must run npm ci');
    pushCheck('javascript-tests: npm run test:js', jobHasRun(jJs, /npm run test:js/),
        'javascript-tests must run npm run test:js');
    pushCheck('javascript-tests: npm run test:web-standards', jobHasRun(jJs, /npm run test:web-standards/),
        'javascript-tests must run npm run test:web-standards');
    const jGuard = candidateJobs['signature-guard'];
    const guardSteps = jobSteps(jGuard).filter((s) => /(^|\n)\s*bash\s+[^\n]*pre-push-guard\.sh/.test(stepRun(s)));
    pushCheck('signature-guard: guard from the trusted base',
        guardSteps.length > 0 && guardSteps.every((s) => TRUSTED_LOC.test(stepRun(s)) && /github\.sha/.test(stepRun(s))),
        'signature-guard must run the guard from the materialized trusted bundle against github.sha');
    const jContract = candidateJobs['trusted-gate-contract'];
    // 只匹配实际执行 contract 的 step（同时含 gate-contract.mjs 与 --candidate-ref；
    // bootstrap step 里的 test -f gate-contract.mjs 引用不算）
    const contractSteps = jobSteps(jContract).filter((s) => /gate-contract\.mjs/.test(stepRun(s))
        && /--candidate-ref/.test(stepRun(s)));
    pushCheck('trusted-gate-contract: trusted contract checks github.sha',
        contractSteps.length > 0 && contractSteps.every((s) => TRUSTED_LOC.test(stepRun(s))
            && /--candidate-ref/.test(stepRun(s)) && /github\.sha/.test(stepRun(s))),
        'trusted-gate-contract must run the materialized trusted gate-contract.mjs against github.sha');
    pushCheck('trusted-gate-contract: gate parity step', jobHasRun(jContract, /gate-parity\.mjs/),
        'trusted-gate-contract must run gate-parity.mjs');
    pushCheck('trusted-gate-contract: report upload', jobHasUploadAlways(jContract),
        'trusted-gate-contract must upload the contract report with if: always()');
    const jI18n = candidateJobs['i18n-check'];
    pushCheck('i18n-check: CI=true npm run test:i18n',
        jobSteps(jI18n).some((s) => commandsInclude(stepRun(s), /npm run test:i18n/)
            && s.env && typeof s.env.CI === 'string' && /true/i.test(s.env.CI)),
        'i18n-check must run npm run test:i18n with CI=true');
    const i18nContractSteps = jobSteps(jI18n).filter((s) => /gate-contract\.mjs/.test(stepRun(s))
        && /--candidate-ref/.test(stepRun(s)));
    pushCheck('i18n-check: trusted contract checks github.sha',
        i18nContractSteps.length > 0 && i18nContractSteps.every((s) => TRUSTED_LOC.test(stepRun(s))
            && /--candidate-ref/.test(stepRun(s)) && /github\.sha/.test(stepRun(s))),
        'i18n-check must run the materialized trusted gate-contract.mjs against github.sha');
    pushCheck('i18n-check: ref snapshot check',
        jobHasRun(jI18n, /check\.mjs/) && jobHasRun(jI18n, /--snapshot ref/) && jobHasRun(jI18n, /--ref/),
        'i18n-check must run the ref snapshot check');
    pushCheck('i18n-check: worktree check', jobHasRun(jI18n, /npm run i18n:check/),
        'i18n-check must run the worktree i18n check');
    pushCheck('i18n-check: static generation', jobHasRun(jI18n, /i18n:generate-static/),
        'i18n-check must generate the static i18n resources');
    pushCheck('i18n-check: static diff',
        jobHasRun(jI18n, /git diff --exit-code/) && jobHasRun(jI18n, /i18n-static/),
        'i18n-check must verify the generated resources');
    pushCheck('i18n-check: gate parity step', jobHasRun(jI18n, /gate-parity\.mjs/),
        'i18n-check must run gate-parity.mjs');
    pushCheck('i18n-check: report upload', jobHasUploadAlways(jI18n),
        'i18n-check must upload the i18n report with if: always()');
    pushCheck('i18n-check: final propagation',
        jobHasRun(jI18n, /outcome/) && jobHasRun(jI18n, /GITHUB_STEP_SUMMARY|check_outcome/),
        'i18n-check must propagate all collected outcomes at the end');

    // trusted helper 内容检查：候选弱化 scripts/ci 共享实现必须被拒绝
    const matFile = path.join(candidateRoot, 'scripts', 'ci', 'materialize-trusted-gate.sh');
    if (fs.existsSync(matFile)) {
        const matText = fs.readFileSync(matFile, 'utf8');
        const matOk = /ls-tree/.test(matText) && /read-tree/.test(matText)
            && /checkout-index/.test(matText) && /test -s/.test(matText)
            && /pre-push-guard\.sh/.test(matText) && !isNoopStep(matText);
        pushCheck('materialize-trusted-gate.sh keeps its materialization behavior', matOk,
            'candidate weakened scripts/ci/materialize-trusted-gate.sh');
    }
    const resFile = path.join(candidateRoot, 'scripts', 'ci', 'resolve-trusted-base.mjs');
    if (fs.existsSync(resFile)) {
        const resText = fs.readFileSync(resFile, 'utf8');
        const resOk = /i18n-gate-epoch-2-root/.test(resText)
            && /ROOT_ADMISSION/.test(resText)
            && /trusted_base_sha/.test(resText)
            && !isNoopStep(resText);
        pushCheck('resolve-trusted-base.mjs keeps root/input-precedence semantics', resOk,
            'candidate weakened scripts/ci/resolve-trusted-base.mjs');
    }

    // Epoch 2 机制
    for (const jobId of ['signature-guard', 'trusted-gate-contract', 'i18n-check']) {
        const job = candidateJobs[jobId];
        pushCheck(jobId + ': Epoch 2 root tag + ROOT_ADMISSION machinery',
            jobSteps(job).some((s) => /i18n-gate-epoch-2-root/.test(stepRun(s)) && /ROOT_ADMISSION/.test(stepRun(s))),
            jobId + ' must resolve the Epoch 2 root tag and branch on ROOT_ADMISSION/NORMAL');
        pushCheck(jobId + ': trusted_base_sha input takes priority',
            jobSteps(job).some((s) => /inputs\.trusted_base_sha/.test(stepRun(s))),
            jobId + ' must prefer inputs.trusted_base_sha before event-based fallback');
        pushCheck(jobId + ': trusted helper cross-validation',
            jobSteps(job).some((s) => /resolve-trusted-base\.mjs/.test(stepRun(s))),
            jobId + ' must cross-validate against the trusted resolve-trusted-base.mjs');
        pushCheck(jobId + ': materialization cross-check with the trusted helper',
            jobSteps(job).some((s) => /materialize-trusted-gate\.sh/.test(stepRun(s))),
            jobId + ' must cross-check materialization against materialize-trusted-gate.sh');
    }

    // 失败吞没 / 条件跳过禁令：只针对运行关键门禁命令的 step；纯 no-op step 一律拒绝
    for (const jobId of REQUIRED_WORKFLOW_JOBS) {
        const job = candidateJobs[jobId];
        for (const step of jobSteps(job)) {
            if (!stepRun(step)) {
                continue;
            }
            if (isCriticalStep(stepRun(step))) {
                pushCheck(jobId + ': no critical step swallows failures',
                    !hasNoopSwallow(stepRun(step)),
                    jobId + ' step swallows the gate command with || true / ; true / ; exit 0');
                pushCheck(jobId + ': no critical step hides behind if false; then',
                    !hasConditionalSkip(stepRun(step)),
                    jobId + ' step hides the gate command behind if false; then');
            }
            pushCheck(jobId + ': no step reduced to comments + no-op',
                !isNoopStep(stepRun(step)),
                jobId + ' step reduces to comments + no-op commands');
        }
    }
    pushCheck('ROOT ADMISSION MODE banner is explicit',
        Object.values(candidateJobs).some((job) => jobSteps(job).some((s) => /ROOT ADMISSION MODE/.test(stepRun(s)))),
        'the workflow must explicitly print ROOT ADMISSION MODE');

    // github.sha^ 回退禁令
    const shaCaret = /github\.sha\s*(\^|\}\}\s*\^)/;
    const badShaCaret = [];
    for (const job of Object.values(candidateJobs)) {
        for (const step of jobSteps(job)) {
            if (shaCaret.test(stepRun(step))) {
                badShaCaret.push(stepName(step) || '(unnamed step)');
            }
        }
    }
    pushCheck('trusted base never falls back to github.sha^', badShaCaret.length === 0,
        'candidate workflow uses github.sha^ fallback: ' + badShaCaret.join(', '));
}

function auditPolicy(checks, trustedPolicy, candidatePolicy) {
    const pushCheck = (name, ok, diagnostic) => {
        checks.push({ name, kind: 'policy', expected: ok ? 'kept' : 'reduced', status: null, ok,
            diagnostic: ok ? '' : diagnostic });
    };
    if (!candidatePolicy) {
        pushCheck('candidate gate-policy.json present', false, 'candidate has no gate-policy.json');
        return;
    }
    pushCheck('gateEpoch unchanged', candidatePolicy.gateEpoch === trustedPolicy.gateEpoch,
        'candidate gateEpoch ' + candidatePolicy.gateEpoch
            + ' != trusted ' + trustedPolicy.gateEpoch);
    pushCheck('contractVersion not lowered',
        candidatePolicy.contractVersion >= trustedPolicy.contractVersion,
        'candidate contractVersion ' + candidatePolicy.contractVersion
            + ' < trusted ' + trustedPolicy.contractVersion);
    for (const [name, key] of [
        ['required paths', 'requiredPaths'],
        ['protected branches', 'protectedBranches'],
        ['required workflow jobs', 'requiredWorkflowJobs'],
        ['required package scripts', 'requiredPackageScripts'],
        ['required external checks', 'requiredExternalChecks'],
    ]) {
        const removed = setReduced(trustedPolicy[key], candidatePolicy[key]);
        pushCheck(name + ' not reduced', removed.length === 0,
            'candidate dropped ' + name + ': ' + removed.join(', '));
    }
}

function auditPackage(checks, requiredScripts, candidatePkg) {
    const pushCheck = (name, ok, diagnostic) => {
        checks.push({ name, kind: 'package', expected: ok ? 'valid' : 'invalid', status: null, ok,
            diagnostic: ok ? '' : diagnostic });
    };
    const scripts = candidatePkg && typeof candidatePkg.scripts === 'object' ? candidatePkg.scripts : {};
    for (const script of requiredScripts) {
        const value = scripts[script];
        pushCheck('package script ' + script + ' points at a real entry',
            typeof value === 'string' && !isBannedScript(value),
            'package script ' + script + ' must be a real test entry (got ' + JSON.stringify(value) + ')');
    }
}

function auditInvariants(checks, invariants, candidatePolicy, candidateDoc, candidatePkg, candidateRoot) {
    const pushCheck = (name, ok, diagnostic) => {
        checks.push({ name, kind: 'invariant', expected: ok ? 'satisfied' : 'violated', status: null, ok,
            diagnostic: ok ? '' : diagnostic });
    };
    if (!invariants) {
        pushCheck('gate-invariants.json present', false, 'gate-invariants.json is missing');
        return;
    }
    if (candidatePolicy) {
        pushCheck('invariant: gateEpoch == ' + invariants.gateEpoch,
            candidatePolicy.gateEpoch === invariants.gateEpoch,
            'candidate gateEpoch ' + candidatePolicy.gateEpoch + ' != invariant ' + invariants.gateEpoch);
        pushCheck('invariant: contractVersion >= ' + invariants.contractVersion,
            candidatePolicy.contractVersion >= invariants.contractVersion,
            'candidate contractVersion ' + candidatePolicy.contractVersion
                + ' < invariant ' + invariants.contractVersion);
    }
    const candidateJobs = candidateDoc && candidateDoc.jobs && typeof candidateDoc.jobs === 'object'
        ? candidateDoc.jobs : {};
    for (const jobId of invariants.requiredJobs || []) {
        pushCheck('invariant: job ' + jobId + ' present',
            candidateJobs[jobId] && typeof candidateJobs[jobId] === 'object',
            'candidate workflow dropped the invariant job ' + jobId);
    }
    if (candidateDoc && candidateDoc.on && typeof candidateDoc.on === 'object') {
        for (const trigger of invariants.requiredTriggers || []) {
            pushCheck('invariant: trigger ' + trigger + ' present',
                candidateDoc.on[trigger] !== undefined,
                'candidate workflow dropped the invariant trigger ' + trigger);
        }
    }
    for (const command of invariants.requiredCommands || []) {
        // token 匹配：命令中每个 token 都必须出现在候选的某个实际执行命令里；
        // token 去引号后按相等或后缀匹配（"gate-parity.mjs" ⊂ "$GATE_DIR/scripts/ci/gate-parity.mjs"）
        const tokens = command.trim().split(/\s+/).filter(Boolean);
        const found = Object.values(candidateJobs).some((job) => jobSteps(job).some((s) =>
            extractCommands(stepRun(s)).some((c) => {
                const cTokens = c.split(/\s+/)
                    .map((t) => t.replace(/^["']|["']$/g, ''))
                    .filter(Boolean);
                return tokens.every((t) => cTokens.some((ct) => ct === t || ct.endsWith('/' + t) || ct.endsWith(t)));
            })));
        pushCheck('invariant: command "' + command + '" present', found,
            'candidate workflow no longer runs "' + command + '"');
    }
    for (const rel of invariants.requiredFiles || []) {
        pushCheck('invariant: file ' + rel + ' present', fs.existsSync(path.join(candidateRoot, ...rel.split('/'))),
            'candidate is missing the invariant file ' + rel);
    }
}

function main() {
    let args;
    try {
        args = parseArgs(process.argv.slice(2));
    } catch (e) {
        fail(e.message);
        return;
    }
    if (args.version) {
        console.log('i18n-gate-parity ' + VERSION);
        return;
    }
    const repoRoot = path.resolve(args.repoRoot);
    const reportRoot = path.resolve(args.reportRoot || repoRoot);

    let candidateRoot = null;
    let historyRef = null;
    try {
        if (args.mode === 'ref') {
            if (!SHA_RE.test(args.candidateRef)) {
                fail('--candidate-ref must be a full 40-char commit sha: ' + args.candidateRef);
                return;
            }
            candidateRoot = snapshot.materializePaths(repoRoot, args.candidateRef, CANDIDATE_PATHS).root;
            historyRef = args.candidateRef;
        } else {
            candidateRoot = snapshot.materializeIndexPathsTo(repoRoot,
                CANDIDATE_PATHS, fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-parity-index-')));
            historyRef = 'HEAD';
        }
    } catch (e) {
        fail('cannot materialize the candidate snapshot: ' + e.message);
        return;
    }

    const checks = [];
    const diagnostics = [];
    try {
        const candidatePolicy = loadJson(candidateRoot, POLICY_REL);
        const candidateDocFile = path.join(candidateRoot, ...WORKFLOW_REL.split('/'));
        const candidatePkg = loadJson(candidateRoot, PACKAGE_JSON_REL);
        const invariants = loadJson(candidateRoot, INVARIANTS_REL);

        let candidateDoc = null;
        if (fs.existsSync(candidateDocFile)) {
            const YAML = loadYamlModule(repoRoot);
            candidateDoc = YAML.parse(fs.readFileSync(candidateDocFile, 'utf8'));
        }

        if (args.invariants) {
            // root admission / root adoption：没有 trusted predecessor，用 invariants 作为最低线
            auditInvariants(checks, invariants, candidatePolicy, candidateDoc, candidatePkg, candidateRoot);
            if (candidatePolicy) {
                auditPackage(checks, REQUIRED_SCRIPTS, candidatePkg);
            }
        } else {
            const trustedPolicy = loadJson(args.trustedDir, POLICY_REL);
            const trustedDocFile = path.join(args.trustedDir, ...WORKFLOW_REL.split('/'));
            let trustedDoc = null;
            if (fs.existsSync(trustedDocFile)) {
                const YAML = loadYamlModule(repoRoot);
                trustedDoc = YAML.parse(fs.readFileSync(trustedDocFile, 'utf8'));
            }
            if (!trustedPolicy) {
                fail('the trusted gate bundle has no gate-policy.json; fail closed');
                return;
            }
            auditPolicy(checks, trustedPolicy, candidatePolicy);
            auditWorkflow(checks, trustedDoc, candidateDoc, candidateRoot);
            auditPackage(checks, [...REQUIRED_SCRIPTS, ...(trustedPolicy.requiredPackageScripts || [])], candidatePkg);
            if (invariants) {
                auditInvariants(checks, invariants, candidatePolicy, candidateDoc, candidatePkg, candidateRoot);
            }
        }

        for (const check of checks) {
            if (!check.ok) {
                diagnostics.push('REDUCED: ' + check.name + (check.diagnostic ? ' — ' + check.diagnostic : ''));
            }
        }
        const verdict = checks.every((c) => c.ok) ? 'pass' : 'fail';
        const payload = {
            version: VERSION,
            mode: args.invariants ? 'invariants' : 'trusted-vs-candidate',
            trustedDir: args.invariants ? null : args.trustedDir,
            candidate: { mode: args.mode, ref: args.candidateRef || null },
            checks, verdict, diagnostics,
        };
        const dir = path.join(reportRoot, 'build', 'reports', 'i18n');
        fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(path.join(dir, 'parity.json'), JSON.stringify(payload, null, 2) + '\n', 'utf8');

        if (verdict === 'fail') {
            console.error('GATE PARITY FAILED (candidate ' + (args.candidateRef || 'index') + ')');
            for (const check of checks) {
                if (!check.ok) {
                    console.error('  - ' + check.name + (check.diagnostic ? ': ' + check.diagnostic : ''));
                }
            }
            console.error('parity report: ' + path.join(reportRoot, 'build', 'reports', 'i18n', 'parity.json'));
            process.exit(1);
            return;
        }
        console.log('GATE PARITY OK (candidate ' + (args.candidateRef || 'index')
            + '; ' + checks.length + ' checks)');
    } finally {
        try {
            fs.rmSync(candidateRoot, { recursive: true, force: true });
        } catch (ignored) {
            // 清理失败不能掩盖 verdict
        }
        snapshot.cleanupAll();
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}
