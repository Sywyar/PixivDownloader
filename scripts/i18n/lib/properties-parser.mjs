'use strict';
/**
 * Java properties 解析器（与 java.util.Properties.load(Reader) 逐位对齐）。
 *
 * 实现参考 JDK 17 java.util.Properties 的 LineReader / load0 / loadConvert 算法：
 * - `=` / `:` / 未转义空白均可分隔；分隔符之后的未转义空白被跳过；
 * - `key=   ` 与 `key:\t` 最终 value 为空串；value 的尾随空白原样保留（与 JVM 一致）；
 * - 行续接：行尾奇数个反斜杠触发续接，反斜杠与换行消失（每处续接只去掉一个反斜杠），
 *   下一物理行开头的空格 / tab / form-feed 被跳过；偶数反斜杠不续接；
 * - 行尾悬空反斜杠（奇数个且无续接内容）被 JVM 直接丢弃；
 * - 未知转义按 Java 语义：反斜杠丢弃、字符保留（\t \r \n \f 转义为控制字符）；
 * - `\uXXXX` 必须恰好四位十六进制，否则报错（与 JVM "Malformed \uxxxx encoding." 一致）；
 * - 文件级 BOM 不剥离：BOM 会成为第一个 key 的前缀（JVM load(Reader) 真实语义，
 *   与运行时 normalizeKey 的补偿行为解耦）；
 * - CRLF / LF 等价；注释（# / !）只在逻辑行开头；重复 key 不静默覆盖，报告全部位置。
 *
 * 返回的 value 不 trim、不剥离任何空白，保持与 JVM 逐字符一致。
 */

function isWhiteSpace(c) {
    return c === ' ' || c === '\t' || c === '\f';
}

/**
 * loadConvert：处理 `\` 转义与 \uXXXX。
 * 输入必须是已完成续接处理后的逻辑行切片（JVM 保证行尾没有未转义的孤立反斜杠）。
 * @returns {{value: string, errors: Array<{line, message}>}}
 */
function convert(raw, line) {
    const out = [];
    const errors = [];
    let i = 0;
    while (i < raw.length) {
        const ch = raw[i];
        if (ch !== '\\') {
            out.push(ch);
            i += 1;
            continue;
        }
        // 行尾孤立反斜杠不会到达这里（LineReader 已处理），但防御性兜底
        if (i + 1 >= raw.length) {
            out.push('\\');
            break;
        }
        const next = raw[i + 1];
        i += 2;
        if (next === 'u') {
            if (i + 4 > raw.length) {
                errors.push({ line, message: 'Malformed \\uxxxx encoding.' });
                return { value: out.join('') + '\\u' + raw.slice(i), errors };
            }
            let value = 0;
            for (let k = 0; k < 4; k += 1) {
                const hex = raw[i + k];
                const digit = hex >= '0' && hex <= '9' ? hex.charCodeAt(0) - 48
                    : hex >= 'a' && hex <= 'f' ? hex.charCodeAt(0) - 87
                        : hex >= 'A' && hex <= 'F' ? hex.charCodeAt(0) - 55
                            : -1;
                if (digit < 0) {
                    errors.push({ line, message: 'Malformed \\uxxxx encoding.' });
                    return { value: out.join(''), errors };
                }
                value = (value << 4) + digit;
            }
            out.push(String.fromCharCode(value));
            i += 4;
            continue;
        }
        if (next === 't') {
            out.push('\t');
        } else if (next === 'r') {
            out.push('\r');
        } else if (next === 'n') {
            out.push('\n');
        } else if (next === 'f') {
            out.push('\f');
        } else {
            out.push(next);
        }
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
    const src = String(text);
    const entries = [];
    const seen = new Map();
    const errors = [];

    let i = 0;
    let lineNumber = 1;
    let len = 0;
    let buf = [];
    let startLine = 1;
    const physicalLines = [];
    let skipWhiteSpace = true;
    let appendedLineBegin = false;
    let precedingBackslash = false;

    function consume() {
        const c = src[i];
        i += 1;
        if (c === '\n') {
            lineNumber += 1;
        }
        return c;
    }

    function finishLine(raw) {
        // ---- load0 的 key/value 分割（与 JDK 逐行一致）----
        let keyLen = 0;
        let valueStart = raw.length;
        let hasSep = false;
        let keyBackslash = false;
        while (keyLen < raw.length) {
            const c = raw[keyLen];
            if ((c === '=' || c === ':') && !keyBackslash) {
                valueStart = keyLen + 1;
                hasSep = true;
                break;
            }
            if (isWhiteSpace(c) && !keyBackslash) {
                valueStart = keyLen + 1;
                break;
            }
            keyBackslash = c === '\\' ? !keyBackslash : false;
            keyLen += 1;
        }
        while (valueStart < raw.length) {
            const c = raw[valueStart];
            if (!isWhiteSpace(c)) {
                if (!hasSep && (c === '=' || c === ':')) {
                    hasSep = true;
                } else {
                    break;
                }
            }
            valueStart += 1;
        }

        const keyDecoded = convert(raw.slice(0, keyLen), startLine);
        const valueDecoded = convert(raw.slice(valueStart), startLine);
        errors.push(...keyDecoded.errors);
        errors.push(...valueDecoded.errors);

        const key = keyDecoded.value;
        entries.push({
            key,
            value: valueDecoded.value,
            keyLine: startLine,
            physicalLines: [...physicalLines],
        });
        if (!seen.has(key)) {
            seen.set(key, []);
        }
        seen.get(key).push(startLine);
    }

    while (true) {
        if (i >= src.length) {
            if (len === 0) {
                break;
            }
            const end = precedingBackslash ? len - 1 : len;
            physicalLines.push(startLine, lineNumber);
            finishLine(buf.slice(0, end).join(''));
            break;
        }        const c = consume();
        if (skipWhiteSpace) {
            if (isWhiteSpace(c)) {
                continue;
            }
            if (!appendedLineBegin && (c === '\r' || c === '\n')) {
                continue;
            }
            skipWhiteSpace = false;
            appendedLineBegin = false;
        }
        if (len === 0 && (c === '#' || c === '!')) {
            while (i < src.length) {
                const cc = consume();
                if (cc === '\r' || cc === '\n') {
                    break;
                }
            }
            skipWhiteSpace = true;
            continue;
        }
        if (c !== '\n' && c !== '\r') {
            if (len === 0) {
                startLine = lineNumber;
                physicalLines.length = 0;
            }
            buf[len] = c;
            len += 1;
            if (c === '\\') {
                precedingBackslash = !precedingBackslash;
            } else {
                precedingBackslash = false;
            }
        } else {
            if (len === 0) {
                skipWhiteSpace = true;
                continue;
            }
            if (precedingBackslash) {
                len -= 1;
                skipWhiteSpace = true;
                appendedLineBegin = true;
                precedingBackslash = false;
                if (c === '\r' && i < src.length && src[i] === '\n') {
                    consume();
                }
                continue;
            }
            physicalLines.push(startLine, lineNumber - (c === '\n' ? 1 : 0));
            finishLine(buf.slice(0, len).join(''));
            len = 0;
            buf = [];
            precedingBackslash = false;
            skipWhiteSpace = true;
            appendedLineBegin = false;
        }
    }

    const duplicateKeys = [];
    for (const [key, lines] of seen) {
        if (lines.length > 1) {
            duplicateKeys.push({ key, lines });
        }
    }

    return { entries, duplicateKeys, errors };
}

/**
 * 解析后的规范值：只做必要的换行规范化（CRLF → LF）。
 * 保留前导 / 尾随空格、转义空格与换行 —— 空白差异不是「相同翻译」，
 * 因此 hash 与比较一律不调用 trim。
 */
function canonicalValue(value) {
    return String(value).replace(/\r\n/g, '\n');
}

export { parse, canonicalValue };

export default { parse, canonicalValue };
