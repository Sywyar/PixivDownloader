'use strict';
/** 新版工作台的 Cookie 校验、登录态与导入交互。 */
/** 当前已保存 Cookie 是否含 PHPSESSID（登录态）。先归一化为 header 串以兼容 JSON / Netscape 格式。 */
function cookieHasPhpsessid() {
    return /(?:^|;\s*)PHPSESSID=/.test(getCookie());
}

function validateAndParseCookie(raw, fmt) {
    if (!raw.trim()) {
        return {ok: false, error: bt('cookie.error.empty', 'Cookie 不能为空')};
    }

    let headerString;
    try {
        if (fmt === 'json') {
            const obj = JSON.parse(raw);
            if (typeof obj !== 'object' || Array.isArray(obj) || obj === null)
                throw new Error(bt('cookie.error.invalid-json', '需要 JSON 对象格式 {"key":"value",...}'));
            headerString = Object.entries(obj).map(([k, v]) => `${k}=${String(v)}`).join('; ');
        } else if (fmt === 'netscape') {
            const lines = raw.split('\n')
                .filter(l => l.trim() && !l.trim().startsWith('#'))
                .map(l => {
                    const p = l.split('\t');
                    return p.length >= 7 ? `${p[5]}=${p[6].trim()}` : null;
                })
                .filter(Boolean);
            if (!lines.length) {
                throw new Error(bt('cookie.error.invalid-netscape', '未解析到有效的 Cookie 行（需要 7 列 tab 分隔格式）'));
            }
            headerString = lines.join('; ');
        } else {
            headerString = raw.trim();
        }
    } catch (e) {
        return {
            ok: false,
            error: bt('cookie.error.parse-failed', '格式解析失败：{message}', {message: e.message})
        };
    }

    // 校验所有键值对格式是否合法
    const pairs = headerString.split(';').map(s => s.trim()).filter(Boolean);
    const invalid = pairs.filter(p => !/^[^=]+=/.test(p));
    if (invalid.length) {
        return {
            ok: false,
            error: bt(
                'cookie.error.invalid-pairs',
                '包含无效键值对：{pairs}',
                {pairs: invalid.slice(0, 3).map(s => `"${s}"`).join(punct('enum'))}
            )
        };
    }

    // 警告：缺少关键字段
    const warnings = [];
    if (!pairs.some(p => p.startsWith('PHPSESSID='))) {
        warnings.push(bt('cookie.warning.no-phpsessid', '未检测到 PHPSESSID，可能无法访问需要登录的内容'));
    }

    return {ok: true, count: pairs.length, warnings};
}

function pixivHeader() {
    const c = getCookie();
    return c ? {'X-Pixiv-Cookie': c} : {};
}

function hasPixivCookie() {
    return !!getCookie().trim();
}

/* ------------------------------------------------------------------
   一键导入 Cookie：让 pixiv.net 上的体验增强工具箱自动取 Cookie 回传
------------------------------------------------------------------ */
const COOKIE_SYNC_SIGNAL = '__pixiv_cookie_sync__';

function applyImportedCookie(snapshot, editor) {
    serverState['pixiv_cookie'] = snapshot.cookie;
    serverState['pixiv_cookie_fmt'] = snapshot.fmt;
    if (snapshot.syncAt) serverState['pixiv_cookie_sync_at'] = snapshot.syncAt;
    refreshCookieViews();
    const hasPhp = cookieHasPhpsessid();
    if (hasPhp) {
        finishCookieSave(editor, bt('status.cookie-imported', '已从 Pixiv 自动导入并保存 Cookie'));
    } else {
        finishCookieSave(editor, bt('status.cookie-imported-no-phpsessid',
            '已导入 Cookie，但未检测到 PHPSESSID，可能未登录 Pixiv'), 'warning');
    }
}

function runScriptCookieImport() {
    const editor = document.getElementById('abCookieInput')?.closest('.ab-cookie');
    if (appMode !== 'solo') {
        abToast('error', bt('status.cookie-import-solo-only', '一键导入仅在 solo 模式可用'));
        return;
    }
    // window.open 必须在用户手势内同步调用（await 会让弹窗被拦截），故同步取
    // 内存里的同步时间戳作基线。基线必须在每次同步结束后（成功或缺 PHPSESSID）
    // 都同步更新到 serverState，否则上次遗留的时间戳会让下次重试被瞬间误判。
    const baselineSyncAt = serverState['pixiv_cookie_sync_at'] != null
        ? String(serverState['pixiv_cookie_sync_at']) : '';
    const win = window.open(
        'https://www.pixiv.net/#' + COOKIE_SYNC_SIGNAL,
        'pixivCookieSync',
        'width=560,height=420'
    );
    if (!win) {
        abToast('error', bt('status.cookie-import-popup-blocked',
            '弹窗被拦截，请允许本站弹窗后重试'));
        return;
    }
    updateCookieImportStatus(bt('status.cookie-import-opening', '正在打开 Pixiv 自动获取 Cookie...'), 'info');
    startCookieSync(win, baselineSyncAt, cur => {
        serverState['pixiv_cookie_sync_at'] = cur.syncAt;
        if (cur.syncStatus === 'ok' || /(?:^|;\s*)PHPSESSID=/.test(cur.cookie || '')) {
            applyImportedCookie(cur, editor);
        } else {
            updateCookieImportStatus(bt('status.cookie-imported-no-phpsessid',
                '已导入 Cookie，但未检测到 PHPSESSID，可能未登录 Pixiv'), 'error');
        }
    }, () => {
        updateCookieImportStatus(bt('status.cookie-import-timeout',
            '未能自动获取 Cookie，请确认已安装并启用「体验增强工具箱」且已登录 Pixiv，或手动粘贴'), 'error');
    });
}

function importCookieViaScript() {
    if (appMode !== 'solo') {
        updateCookieImportStatus(bt('status.cookie-import-solo-only', '一键导入仅在 solo 模式可用'), 'error');
        return;
    }
    if (isToolboxInstalled()) {
        runScriptCookieImport();
        return;
    }
    // 未安装工具箱：提示先安装脚本（alt-chrome 提供脚本弹窗入口）
    updateCookieImportStatus(
        bt('status.cookie-import-need-toolbox', '请先在「油猴脚本」面板安装「体验增强工具箱」'), 'error');
    openScriptsModal();
}

window.PixivBatchAlt.cookie = Object.assign(window.PixivBatchAlt.cookie, {
    getCookie,
    pixivHeader,
    hasPixivCookie,
    cookieHasPhpsessid,
    getCookieFmt,
    normalizeCookieFormat,
    parseCookieToHeaderString,
    cookieStorageKey,
    getStoredCookie,
    setStoredCookie,
    removeStoredCookie,
    getCookieHeaderStringFor,
    validateAndParseCookie,
    importCookieViaScript
});
