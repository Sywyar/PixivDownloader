'use strict';
/**
 * 占位符与保护内容检查：
 * - 命名占位符 {name}、位置占位符 {0}、${name}、printf %s/%d 的 token 多重集合比较
 *   （顺序可不同、名称与数量必须一致；不允许删除 / 新增；重复次数一致）；
 * - HTML 标签名称集合比较（不一致为可抑制 warning；明显闭合损坏为 fail）；
 * - URL 集合比较（不一致为可抑制 warning）；
 * - 翻译值与中文源相同：warning（Pixiv / URL / API / 数字等专有名词可能无需翻译）。
 */

const HTML_VOID_TAGS = new Set([
    'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr',
]);

// 已知 HTML 标签白名单：`<path>` / `<port>` / `<n>` 等角括号占位 token 不是 HTML，不参与检查。
const HTML_TAGS = new Set([
    'a', 'abbr', 'article', 'aside', 'b', 'bdi', 'bdo', 'blockquote', 'button', 'caption', 'code',
    'colgroup', 'dd', 'del', 'details', 'dfn', 'div', 'dl', 'dt', 'em', 'fieldset', 'figcaption',
    'figure', 'footer', 'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'header', 'i', 'iframe', 'ins',
    'kbd', 'label', 'legend', 'li', 'main', 'mark', 'nav', 'ol', 'optgroup', 'option', 'p', 'pre',
    'q', 's', 'samp', 'section', 'select', 'small', 'span', 'strong', 'sub', 'summary', 'sup',
    'table', 'tbody', 'td', 'textarea', 'tfoot', 'th', 'thead', 'time', 'tr', 'u', 'ul', 'var',
    ...HTML_VOID_TAGS,
]);

function tokens(value) {
    const list = [];
    let text = String(value);
    for (const match of text.matchAll(/\$\{([a-zA-Z0-9_.-]+)\}/g)) {
        list.push('${' + match[1] + '}');
    }
    text = text.replace(/\$\{[a-zA-Z0-9_.-]+\}/g, '');
    for (const match of text.matchAll(/\{([a-zA-Z0-9_.-]+)\}/g)) {
        list.push('{' + match[1] + '}');
    }
    for (const match of String(value).matchAll(/%[sd]/g)) {
        list.push(match[0]);
    }
    return list.sort();
}

function htmlTags(value) {
    const names = [];
    for (const match of String(value).matchAll(/<\/?([a-zA-Z][a-zA-Z0-9]*)/g)) {
        const name = match[1].toLowerCase();
        if (HTML_TAGS.has(name)) {
            names.push(name);
        }
    }
    return names.sort();
}

/** 明显损坏：已知 HTML 标签中非 void 标签开 / 闭数量不配对。 */
function brokenHtml(value) {
    const counts = {};
    for (const match of String(value).matchAll(/<\/?([a-zA-Z][a-zA-Z0-9]*)/g)) {
        const name = match[1].toLowerCase();
        if (!HTML_TAGS.has(name) || HTML_VOID_TAGS.has(name)) {
            continue;
        }
        const isClosing = match[0].startsWith('</');
        counts[name] = counts[name] || { open: 0, close: 0 };
        if (isClosing) {
            counts[name].close += 1;
        } else {
            counts[name].open += 1;
        }
    }
    const broken = [];
    for (const [name, pair] of Object.entries(counts)) {
        if (pair.open !== pair.close) {
            broken.push(name);
        }
    }
    return broken;
}

function urls(value) {
    const list = [];
    for (const match of String(value).matchAll(/https?:\/\/[^\s"'<>）)\]】]+/g)) {
        list.push(match[0]);
    }
    return list.sort();
}

function sameMultiset(a, b) {
    if (a.length !== b.length) {
        return false;
    }
    for (let i = 0; i < a.length; i += 1) {
        if (a[i] !== b[i]) {
            return false;
        }
    }
    return true;
}

/**
 * 源 vs 翻译的质量检查。
 * @returns {{errors: Array<string>, warnings: Array<string>}}
 *   errors：占位符不一致、明显 HTML 损坏（对应「已存在目标值的占位符不一致」「HTML/保护标记明显损坏」）。
 *   warnings：HTML 标签集合差异、URL 差异、翻译值与源相同。
 */
function checkTranslation(sourceValue, translationValue) {
    const errors = [];
    const warnings = [];

    const zhTokens = tokens(sourceValue);
    const enTokens = tokens(translationValue);
    if (!sameMultiset(zhTokens, enTokens)) {
        errors.push('placeholder mismatch: zh=' + JSON.stringify(zhTokens)
            + ' en=' + JSON.stringify(enTokens));
    }

    const zhTags = htmlTags(sourceValue);
    const enTags = htmlTags(translationValue);
    if (!sameMultiset(zhTags, enTags)) {
        warnings.push('HTML tag set differs: zh=[' + zhTags.join(',') + '] en=[' + enTags.join(',') + ']');
    }
    for (const broken of brokenHtml(translationValue)) {
        errors.push('broken HTML in translation: unclosed <' + broken + '>');
    }
    for (const broken of brokenHtml(sourceValue)) {
        errors.push('broken HTML in source: unclosed <' + broken + '>');
    }

    const zhUrls = urls(sourceValue);
    const enUrls = urls(translationValue);
    if (!sameMultiset(zhUrls, enUrls)) {
        warnings.push('URL set differs: zh=[' + zhUrls.join(',') + '] en=[' + enUrls.join(',') + ']');
    }

    if (String(sourceValue) === String(translationValue)) {
        warnings.push('translation identical to source (possibly untranslatable proper noun)');
    }

    return { errors, warnings };
}

export {  checkTranslation, tokens, htmlTags, brokenHtml, urls  };

export default { checkTranslation, tokens, htmlTags, brokenHtml, urls };
