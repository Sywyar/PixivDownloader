'use strict';
/**
 * warning 抑制配置（i18n/ignore.json）。
 * 只允许抑制 warning（如「翻译与源相同」「HTML 标签集合差异」等合理 warning），
 * 不允许抑制 error（missing / empty / stale / placeholder 不一致等）。
 */

import fs from 'fs';
import path from 'path';

const IGNORE_PATH = path.join('i18n', 'ignore.json');

function load(repoRoot) {
    const file = path.join(repoRoot, IGNORE_PATH);
    let raw;
    try {
        raw = fs.readFileSync(file, 'utf8');
    } catch (e) {
        return { warnings: [] };
    }
    try {
        const parsed = JSON.parse(raw);
        return { warnings: Array.isArray(parsed.warnings) ? parsed.warnings : [] };
    } catch (e) {
        throw new Error('cannot parse i18n/ignore.json: ' + e.message);
    }
}

function isIgnored(config, bundleId, key, type) {
    if (!config || !Array.isArray(config.warnings)) {
        return false;
    }
    return config.warnings.some((item) => item && item.bundle === bundleId
        && item.key === key && item.reason);
}

export {  load, isIgnored, IGNORE_PATH  };

export default { load, isIgnored, IGNORE_PATH };
