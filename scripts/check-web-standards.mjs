import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const SCRIPT_FILE = fileURLToPath(import.meta.url);
const REPOSITORY_ROOT = path.resolve(path.dirname(SCRIPT_FILE), '..');
const BASELINE_FILE = path.join(path.dirname(SCRIPT_FILE), 'web-standards-baseline.json');
const RULE_NAMES = Object.freeze([
    'hardcodedColors',
    'inlineStyles',
    'inlineHandlers',
    'nativeDialogs',
    'universalSiblingSelectors'
]);

function countMatches(source, pattern) {
    return Array.from(source.matchAll(pattern)).length;
}

// 右侧为通配的兄弟组合器（`* + *`、`.a ~ *`）没有可用于缩小样式失效范围的特征，
// Chromium 在任意元素插入或移动子节点时可能重算其整棵子树；下载队列等长列表每移动一行都要付出全量重算。
function countUniversalSiblingSelectors(source) {
    const css = source
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, '""');
    let total = 0;
    let prelude = '';
    for (const character of css) {
        if (character === '{') {
            // @media、@supports 等规则的前导部分是条件而不是选择器。
            if (!prelude.trim().startsWith('@')) {
                // 属性选择器中的 `~=` / `*=` 与 `:nth-*()` 的 `an+b` 参数不是组合器。
                const selector = prelude
                    .replace(/\[[^\]]*\]/g, '[]')
                    .replace(/:nth-[a-z-]+\([^)]*\)/gi, ':nth');
                total += countMatches(selector, /[+~]\s*\*/g);
            }
            prelude = '';
        } else if (character === '}' || character === ';') {
            prelude = '';
        } else {
            prelude += character;
        }
    }
    return total;
}

function countHardcodedColors(source) {
    return source.split(/\r?\n/).reduce(function (total, line) {
        // Literal colors are allowed at their CSS custom-property definition site.
        // Consumers still have to reference that semantic token instead of repeating it.
        if (/--[a-z0-9_-]+\s*:/i.test(line)) return total;
        return total + countMatches(line, /#[0-9a-f]{3,8}\b|\b(?:rgb|rgba|hsl|hsla)\s*\(/gi);
    }, 0);
}

export function analyzeSource(relativePath, source) {
    const extension = path.extname(relativePath).toLowerCase();
    return {
        hardcodedColors: countHardcodedColors(source),
        inlineStyles: extension === '.html' ? countMatches(source, /\sstyle\s*=/gi) : 0,
        inlineHandlers: extension === '.html' || extension === '.js' || extension === '.mjs'
            ? countMatches(source, /\son[a-z]+\s*=\s*["']/gi)
            : 0,
        nativeDialogs: extension === '.js' || extension === '.mjs'
            ? countMatches(source, /(^|[^\w$.])(?:(?:window|globalThis)\s*\.\s*)?(?:alert|confirm|prompt)\s*\(/gm)
            : 0,
        universalSiblingSelectors: extension === '.css' ? countUniversalSiblingSelectors(source) : 0
    };
}

function isScannableFile(filePath) {
    const normalized = filePath.replaceAll('\\', '/').toLowerCase();
    if (!/\.(?:css|html|m?js)$/.test(normalized)) return false;
    if (/(^|\/)(?:vendor|node_modules|target)(\/|$)/.test(normalized)) return false;
    return !/(?:\.min\.(?:css|js)|\.bundle\.js)$/.test(normalized);
}

function walk(directory, files) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const absolutePath = path.join(directory, entry.name);
        if (entry.isDirectory()) walk(absolutePath, files);
        else if (entry.isFile() && isScannableFile(absolutePath)) files.push(absolutePath);
    }
}

export function collectSnapshot(repositoryRoot = REPOSITORY_ROOT) {
    const files = [];
    for (const entry of fs.readdirSync(repositoryRoot, { withFileTypes: true })) {
        if (!entry.isDirectory() || !entry.name.startsWith('pixivdownload-')) continue;
        for (const resourceRoot of ['static', 'web']) {
            const staticRoot = path.join(repositoryRoot, entry.name, 'src', 'main', 'resources', resourceRoot);
            if (fs.existsSync(staticRoot)) walk(staticRoot, files);
        }
    }
    const templatesRoot = path.join(repositoryRoot, 'plugin-templates');
    if (fs.existsSync(templatesRoot)) {
        for (const entry of fs.readdirSync(templatesRoot, { withFileTypes: true })) {
            if (!entry.isDirectory()) continue;
            const staticRoot = path.join(
                templatesRoot, entry.name, 'src', 'main', 'resources', 'static');
            if (fs.existsSync(staticRoot)) walk(staticRoot, files);
        }
    }

    const snapshot = {};
    for (const absolutePath of files.sort()) {
        const relativePath = path.relative(repositoryRoot, absolutePath).replaceAll('\\', '/');
        const counts = analyzeSource(relativePath, fs.readFileSync(absolutePath, 'utf8'));
        if (RULE_NAMES.some(function (rule) { return counts[rule] > 0; })) snapshot[relativePath] = counts;
    }
    return snapshot;
}

export function compareSnapshot(current, baseline) {
    const violations = [];
    for (const [relativePath, counts] of Object.entries(current)) {
        const allowed = baseline[relativePath] || {};
        for (const rule of RULE_NAMES) {
            const actualCount = counts[rule] || 0;
            const allowedCount = allowed[rule] || 0;
            if (actualCount > allowedCount) {
                violations.push(`${relativePath}: ${rule} ${actualCount} > baseline ${allowedCount}`);
            }
        }
    }
    return violations;
}

function baselineDocument(snapshot) {
    return {
        version: 1,
        policy: 'Existing debt may decrease but must never increase; new files start at zero.',
        files: snapshot
    };
}

function run() {
    const snapshot = collectSnapshot();
    if (process.argv.includes('--update-baseline')) {
        fs.writeFileSync(BASELINE_FILE, `${JSON.stringify(baselineDocument(snapshot), null, 2)}\n`, 'utf8');
        console.log(`Updated ${path.relative(REPOSITORY_ROOT, BASELINE_FILE)} (${Object.keys(snapshot).length} debt files).`);
        return;
    }

    if (!fs.existsSync(BASELINE_FILE)) {
        throw new Error(`Missing baseline: ${path.relative(REPOSITORY_ROOT, BASELINE_FILE)}`);
    }
    const baseline = JSON.parse(fs.readFileSync(BASELINE_FILE, 'utf8'));
    if (baseline.version !== 1 || !baseline.files) throw new Error('Unsupported web standards baseline format.');
    const violations = compareSnapshot(snapshot, baseline.files);
    if (violations.length) {
        console.error('Web standards debt increased:');
        for (const violation of violations) console.error(`- ${violation}`);
        process.exitCode = 1;
        return;
    }
    console.log(`Web standards gate passed (${Object.keys(snapshot).length} tracked debt files).`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === SCRIPT_FILE) run();
