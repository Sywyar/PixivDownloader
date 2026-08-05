'use strict';
/**
 * Java properties 文件解析器（与 java.util.Properties 语义对齐，但严格得多）：
 * - `=` / `:` / 空白分隔；
 * - 注释（# / !）与空行；
 * - 行续接（物理行末反斜杠，含 \uXXXX 跨续接行）；
 * - 转义字符与 \uXXXX（非法 Unicode escape 报错）；
 * - escaped separator（\= \: \ 空格）在 key 中不结束 key；
 * - value 内可含 `=`；
 * - 重复 key 检测（不静默覆盖，报告全部定义位置）；
 * - UTF-8 与 BOM（文件级 BOM 剥离）；
 * - 逻辑行与物理行号定位。
 */

function stripBom(text) {
    return text.charCodeAt(0) === 0xFEFF ? text.slice(1) : text;
}

function isContinuation(line) {
    let backslashes = 0;
    for (let i = line.length - 1; i >= 0 && line[i] === '\\'; i -= 1) {
        backslashes += 1;
    }
    return backslashes % 2 === 1;
}

/**
 * 解析转义并处理 \uXXXX（允许 4 位十六进制跨续接行延续）。
 * 返回 { value, errors }；errors 为 [{ line, message }]。
 */
function unescape(raw, physicalLines) {
    const out = [];
    const errors = [];
    let i = 0;
    const lineAt = (idx) => physicalLines[Math.min(idx, physicalLines.length - 1)];

    while (i < raw.length) {
        const ch = raw[i];
        if (ch !== '\\') {
            out.push(ch);
            i += 1;
            continue;
        }
        if (i + 1 >= raw.length) {
            errors.push({ line: lineAt(i), message: 'dangling backslash at end of logical line' });
            break;
        }
        const next = raw[i + 1];
        if (next === '\n') {
            // 续接：反斜杠 + 换行被整体丢弃（Java continuation 语义）
            i += 2;
            continue;
        }
        if (next === 'u') {
            let j = i + 2;
            let hex = '';
            while (hex.length < 4 && j < raw.length) {
                const c = raw[j];
                if (/[0-9a-fA-F]/.test(c)) {
                    hex += c;
                    j += 1;
                } else {
                    break;
                }
            }
            if (hex.length < 4) {
                errors.push({ line: lineAt(i), message: 'invalid \\u escape: expected 4 hex digits' });
                out.push('\\u' + hex);
                i = j;
                continue;
            }
            out.push(String.fromCharCode(parseInt(hex, 16)));
            i = j;
            continue;
        }
        const escapes = { t: '\t', n: '\n', f: '\f', r: '\r', '\\': '\\', '=': '=', ':': ':', ' ': ' ', '#': '#', '!': '!' };
        if (Object.prototype.hasOwnProperty.call(escapes, next)) {
            out.push(escapes[next]);
            i += 2;
            continue;
        }
        // 未知转义：按 Java 行为保留反斜杠与字符本身
        out.push(next);
        i += 2;
    }
    return { value: out.join(''), errors };
}

/**
 * 解析 properties 文本。
 * @returns {{ entries: Array<{key, value, keyLine, physicalLines}>,
 *            duplicateKeys: Array<{key, lines}>,
 *            errors: Array<{line, message}> }}
 */
function parse(text) {
    const source = stripBom(String(text));
    const rawLines = source.split('\n').map((line) => line.endsWith('\r') ? line.slice(0, -1) : line);
    const logicalLines = [];
    const lineNumbers = [];

    let current = null;
    let currentNumbers = [];
    for (let i = 0; i < rawLines.length; i += 1) {
        if (current === null) {
            current = rawLines[i];
            currentNumbers = [i + 1];
        } else {
            current += '\n' + rawLines[i];
            currentNumbers.push(i + 1);
        }
        if (isContinuation(current)) {
            continue;
        }
        logicalLines.push(current);
        lineNumbers.push(currentNumbers);
        current = null;
    }
    if (current !== null) {
        logicalLines.push(current);
        lineNumbers.push(currentNumbers);
    }

    const entries = [];
    const seen = new Map();
    const errors = [];

    for (let idx = 0; idx < logicalLines.length; idx += 1) {
        const logical = logicalLines[idx];
        const physicalLines = lineNumbers[idx];
        const keyLine = physicalLines[0];

        if (logical.trim() === '' || /^[ \t\f]*[#!]/.test(logical)) {
            continue;
        }

        let i = 0;
        // 跳过 key 前导空白
        while (i < logical.length && (logical[i] === ' ' || logical[i] === '\t' || logical[i] === '\f')) {
            i += 1;
        }
        const keyStart = i;
        let keyEnd = -1;
        let separator = null;
        while (i < logical.length) {
            const ch = logical[i];
            if (ch === '\\') {
                i += 2;
                continue;
            }
            if (ch === '=' || ch === ':') {
                keyEnd = i;
                separator = ch;
                break;
            }
            if (ch === ' ' || ch === '\t' || ch === '\f') {
                // Java：key 在首个未转义空白处终止；后面即使跟着 = 或 : 也是值的一部分
                keyEnd = i;
                separator = ' ';
                break;
            }
            i += 1;
        }
        if (keyEnd < 0) {
            // Java 语义：无分隔符的行 = 整行为 key、值为空
            const rawKey = logical.slice(keyStart);
            const keyDecoded = unescape(rawKey, physicalLines);
            for (const err of keyDecoded.errors) {
                errors.push({ line: err.line, message: 'key: ' + err.message });
            }
            if (keyDecoded.value === '') {
                errors.push({ line: keyLine, message: 'empty key' });
                continue;
            }
            entries.push({
                key: keyDecoded.value,
                value: '',
                keyLine,
                physicalLines,
            });
            if (!seen.has(keyDecoded.value)) {
                seen.set(keyDecoded.value, []);
            }
            seen.get(keyDecoded.value).push(keyLine);
            continue;
        }

        const rawKey = logical.slice(keyStart, keyEnd);
        let valueStart;
        if (separator === ' ') {
            // Java 规范：只有空白作为分隔符时，值开头的 = / : 才被跳过
            valueStart = keyEnd;
            while (valueStart < logical.length
                && (logical[valueStart] === ' ' || logical[valueStart] === '\t' || logical[valueStart] === '\f')) {
                valueStart += 1;
            }
            if (valueStart < logical.length && (logical[valueStart] === '=' || logical[valueStart] === ':')) {
                valueStart += 1;
                while (valueStart < logical.length
                    && (logical[valueStart] === ' ' || logical[valueStart] === '\t' || logical[valueStart] === '\f')) {
                    valueStart += 1;
                }
            }
        } else {
            valueStart = keyEnd + 1;
        }

        const keyDecoded = unescape(rawKey, physicalLines);
        const valueDecoded = unescape(logical.slice(valueStart), physicalLines);
        for (const err of keyDecoded.errors) {
            errors.push({ line: err.line, message: 'key: ' + err.message });
        }
        for (const err of valueDecoded.errors) {
            errors.push({ line: err.line, message: 'value: ' + err.message });
        }

        const key = keyDecoded.value;
        if (key === '') {
            errors.push({ line: keyLine, message: 'empty key' });
            continue;
        }
        entries.push({
            key,
            value: valueDecoded.value,
            keyLine,
            physicalLines,
        });
        if (!seen.has(key)) {
            seen.set(key, []);
        }
        seen.get(key).push(keyLine);
    }

    const duplicateKeys = [];
    for (const [key, lines] of seen) {
        if (lines.length > 1) {
            duplicateKeys.push({ key, lines });
        }
    }

    return { entries, duplicateKeys, errors };
}

/** 解析后的规范值：统一换行、去掉首尾空白（用于哈希，不受注释 / 排序变化影响）。 */
function canonicalValue(value) {
    return String(value).replace(/\r\n/g, '\n').trim();
}

export {  parse, canonicalValue  };

export default { parse, canonicalValue };
