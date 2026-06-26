'use strict';
    function syncCookieToggleLabel() {
        const input = document.getElementById('cookie-input');
        const button = document.getElementById('cookie-toggle');
        if (!input || !button) return;
        button.textContent = input.type === 'password'
            ? bt('cookie.toggle.show', '显示')
            : bt('cookie.toggle.hide', '隐藏');
    }

    function applyCookieHint() {
        const el = document.getElementById('cookie-hint');
        if (!el) return;
        if (appMode === 'solo') {
            el.textContent = bt(
                'cookie.hint.server',
                'Cookie 保存在服务器，所有设备共享同一配置。支持三种格式：Header String（直接复制浏览器请求头）、JSON（对象格式）、Netscape（EditThisCookie 等工具导出）。'
            );
            return;
        }
        if (pageI18n) {
            pageI18n.apply(el);
        }
    }

    /* ============================================================
       API
    ============================================================ */
    function getCookieFmt() {
        return storeGet('pixiv_cookie_fmt') || 'header';
    }

    function setCookieFmt(fmt) {
        storeSet('pixiv_cookie_fmt', fmt);
        ['header', 'json', 'netscape'].forEach(f => {
            document.getElementById('fmt-' + f).classList.toggle('active', f === fmt);
        });
        applyCookieDependentUi();
    }

    function parseCookieToHeaderString(raw, fmt) {
        if (!raw) return '';
        try {
            if (fmt === 'json') {
                const obj = JSON.parse(raw);
                return Object.entries(obj).map(([k, v]) => `${k}=${v}`).join('; ');
            }
            if (fmt === 'netscape') {
                return raw.split('\n')
                    .filter(l => l.trim() && !l.trim().startsWith('#'))
                    .map(l => {
                        const p = l.split('\t');
                        return p.length >= 7 ? `${p[5]}=${p[6].trim()}` : null;
                    })
                    .filter(Boolean)
                    .join('; ');
            }
        } catch (e) {
            console.warn(bt('cookie.warn.parse-fallback', 'Cookie 解析失败，原样使用: {message}', {message: e.message}));
        }
        // header string 或解析失败时原样返回
        return raw;
    }

    function getCookie() {
        const raw = storeGet('pixiv_cookie') || '';
        return parseCookieToHeaderString(raw, getCookieFmt());
    }

    function getCookieInputHeaderString() {
        const input = document.getElementById('cookie-input');
        const raw = input ? input.value.trim() : (storeGet('pixiv_cookie') || '');
        return parseCookieToHeaderString(raw, getCookieFmt());
    }

    /** 当前输入框 Cookie 是否含 PHPSESSID（登录态）。先归一化为 header 串以兼容 JSON / Netscape 格式。 */
    function cookieHasPhpsessid() {
        return /(?:^|;\s*)PHPSESSID=/.test(getCookieInputHeaderString());
    }

    /**
     * 根据是否有含 PHPSESSID 的有效登录 Cookie，启用/禁用依赖登录态才有意义的组件：
     * 下载后自动收藏、内容分级里的 R-18+/R-18/R-18G 选项。禁用时统一加悬停提示。
     * Cookie 变化（保存/清除/导入）后都应调用本函数。
     * 计划任务的「指定单独的 代理/cookie」弹窗不依赖已保存的 Cookie（可直接粘贴任意 Cookie），不在此门控。
     */
    function applyCookieDependentUi() {
        const ok = cookieHasPhpsessid();
        const title = ok ? '' : bt('cookie.requires-phpsessid', '无有效cookie(PHPSESSID)此功能不可用');
        // 1) 下载后自动收藏。注意：「收藏到」是本地画廊收藏夹，不依赖 Pixiv Cookie。
        ['s-bookmark'].forEach(id => {
            const el = document.getElementById(id);
            if (!el) return;
            el.disabled = !ok;
            el.title = title;
            const container = el.closest('.setting-item');
            if (container) container.title = title;
            const label = container?.querySelector('label');
            if (label) label.title = title;
        });
        // 2) 内容分级下拉里的 R18 档位（全部 / 全年龄保持可用）
        const contentSel = document.getElementById('search-content-filter');
        if (contentSel) {
            const r18Values = ['r18plus', 'r18', 'r18g'];
            r18Values.forEach(val => {
                const opt = contentSel.querySelector(`option[value="${val}"]`);
                if (!opt) return;
                opt.disabled = !ok;
                opt.title = title;
            });
            contentSel.title = title;
            const container = contentSel.closest('.search-extra-item');
            if (container) container.title = title;
            const label = container?.querySelector('label');
            if (label) label.title = title;
            if (!ok && r18Values.includes(contentSel.value)) {
                contentSel.value = 'all';
                handleSearchFilterChange();
            }
        }
        // 3) 快捷获取 Tab 的所有动作按钮（基于「我的」数据，必须有 PHPSESSID）
        document.querySelectorAll('.quick-action').forEach(btn => {
            btn.disabled = !ok;
            btn.title = title;
        });
        // 4) 帐号栏的提示文本（无 Cookie 时显示「未检测到登录 Cookie...」）
        updateQuickAccountBar();
        // 5) 「工具」抽屉标题处的 Cookie 状态标记：有效（含 PHPSESSID）→ 绿色对号；否则 → 红色叉号。
        //    data-i18n-title 同步为当前态的 key，切换界面语言时由 pageI18n.apply 重译提示文案。
        const badge = document.getElementById('cookie-status-badge');
        if (badge) {
            badge.textContent = ok ? '✓' : '✕';
            badge.classList.toggle('cookie-ok', ok);
            badge.classList.toggle('cookie-bad', !ok);
            const badgeKey = ok ? 'cookie.status.ok' : 'cookie.status.missing';
            badge.setAttribute('data-i18n-title', badgeKey);
            badge.title = bt(badgeKey, ok ? 'Cookie 有效（含 PHPSESSID）' : '未检测到有效 Cookie（缺少 PHPSESSID）');
        }
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
                headerString = Object.entries(obj).map(([k, v]) => `${k}=${v}`).join('; ');
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
                    {pairs: invalid.slice(0, 3).map(s => `"${s}"`).join(uiLang() === 'en-US' ? ', ' : '、')}
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

    // Cookie 相关提示显示在 Cookie 区域，而非下载队列状态栏
    function setCookieStatus(msg, type = 'info') {
        const el = document.getElementById('cookie-status');
        if (!el) {
            setStatus(msg, type);
            return;
        }
        el.textContent = msg;
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    function hasPixivCookie() {
        return !!getCookie().trim();
    }

    /* ------------------------------------------------------------------
       一键导入 Cookie：让 pixiv.net 上的体验增强工具箱自动取 Cookie 回传
    ------------------------------------------------------------------ */
    const COOKIE_SYNC_SIGNAL = '__pixiv_cookie_sync__';

    async function fetchServerPixivCookie() {
        try {
            const res = await fetch(BASE + '/api/batch/state', {credentials: 'same-origin'});
            if (!res.ok) return null;
            const data = await res.json();
            const st = data.state || {};
            return {
                cookie: st.pixiv_cookie != null ? String(st.pixiv_cookie) : '',
                fmt: st.pixiv_cookie_fmt || 'header',
                syncAt: st.pixiv_cookie_sync_at != null ? String(st.pixiv_cookie_sync_at) : '',
                syncStatus: st.pixiv_cookie_sync_status != null ? String(st.pixiv_cookie_sync_status) : ''
            };
        } catch (e) {
            return null;
        }
    }

    function applyImportedCookie(snapshot) {
        serverState['pixiv_cookie'] = snapshot.cookie;
        serverState['pixiv_cookie_fmt'] = snapshot.fmt;
        if (snapshot.syncAt) serverState['pixiv_cookie_sync_at'] = snapshot.syncAt;
        setCookieFmt(snapshot.fmt);
        const input = document.getElementById('cookie-input');
        if (input) input.value = snapshot.cookie;
        applyCookieDependentUi();
        const hasPhp = /(?:^|;\s*)PHPSESSID=/.test(snapshot.cookie);
        if (hasPhp) {
            setCookieStatus(bt('status.cookie-imported', '已从 Pixiv 自动导入并保存 Cookie'), 'success');
        } else {
            setCookieStatus(bt('status.cookie-imported-no-phpsessid',
                '已导入 Cookie，但未检测到 PHPSESSID，可能未登录 Pixiv'), 'warning');
        }
    }

    function runScriptCookieImport() {
        if (appMode !== 'solo') {
            setCookieStatus(bt('status.cookie-import-solo-only', '一键导入仅在 solo 模式可用'), 'error');
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
            setCookieStatus(bt('status.cookie-import-popup-blocked',
                '弹窗被拦截，请允许本站弹窗后重试'), 'error');
            return;
        }
        setCookieStatus(bt('status.cookie-import-opening', '正在打开 Pixiv 自动获取 Cookie...'), 'info');
        const deadline = Date.now() + 25000;
        const poll = () => {
            setTimeout(async () => {
                const cur = await fetchServerPixivCookie();
                // 本次同步已结束（时间戳变化）。无论成功与否工具箱都会更新时间戳，
                // 故缺 PHPSESSID 时也能立即停下并给出明确提示，不再空等到超时。
                if (cur && cur.syncAt && cur.syncAt !== baselineSyncAt) {
                    try { win.close(); } catch (e) {}
                    // 同步内存基线，避免下次重试用旧时间戳瞬间误判
                    serverState['pixiv_cookie_sync_at'] = cur.syncAt;
                    if (cur.syncStatus === 'ok' || /(?:^|;\s*)PHPSESSID=/.test(cur.cookie || '')) {
                        applyImportedCookie(cur);
                    } else {
                        setCookieStatus(bt('status.cookie-imported-no-phpsessid',
                            '已导入 Cookie，但未检测到 PHPSESSID，可能未登录 Pixiv'), 'error');
                    }
                    return;
                }
                if (Date.now() > deadline) {
                    setCookieStatus(bt('status.cookie-import-timeout',
                        '未能自动获取 Cookie，请确认已安装并启用「体验增强工具箱」且已登录 Pixiv，或手动粘贴'),
                        'error');
                    return;
                }
                poll();
            }, 1500);
        };
        poll();
    }

    function importCookieViaScript() {
        if (appMode !== 'solo') {
            setCookieStatus(bt('status.cookie-import-solo-only', '一键导入仅在 solo 模式可用'), 'error');
            return;
        }
        if (isToolboxInstalled()) {
            runScriptCookieImport();
            return;
        }
        // 未安装工具箱：复用引导遮罩，门槛步骤须先点安装按钮才能进入下一步
        if (typeof PixivTour === 'undefined') {
            setCookieStatus(bt('status.cookie-import-need-toolbox',
                '请先在「油猴脚本」面板安装「体验增强工具箱」'), 'error');
            ensureUserscriptsExpanded();
            return;
        }
        const ctrl = PixivTour.init({
            pageKey: 'cookie-import',
            i18n: pageI18n,
            noHelpFab: true,
            onFinish: runScriptCookieImport,
            steps: [
                {
                    target: '#userscripts-card',
                    interactiveSelector: '#userscripts-list [data-install-id="' + SCRIPT_ID_TOOLBOX + '"]',
                    titleKey: 'tour:batch.cookie-import.install.title',
                    bodyKey: 'tour:batch.cookie-import.install.body',
                    fallbackTitle: '① 安装体验增强工具箱',
                    fallbackBody: '一键导入需要 pixiv.net 上的「体验增强工具箱」配合。请点击下方高亮的「体验增强工具箱」安装按钮（引导期间页面其它内容不可点击，安装后才能进入下一步）。',
                    onShow: ctrl => {
                        ensureUserscriptsExpanded();
                        setTimeout(() => ctrl.refresh(), 400);
                    },
                    gate: () => isToolboxInstalled(),
                    actionKey: 'tour:batch.cookie-import.install.have-aio',
                    actionFallback: '我已安装 All-in-One',
                    onAction: ctrl => {
                        // 用户声明已装 All-in-One（含工具箱）：记录并直接结束引导、开始获取
                        markScriptInstalled(SCRIPT_ID_ALL_IN_ONE);
                        ctrl.end(true, 'finish');
                    }
                },
                {
                    target: '#cookie-import',
                    titleKey: 'tour:batch.cookie-import.ready.title',
                    bodyKey: 'tour:batch.cookie-import.ready.body',
                    fallbackTitle: '② 开始导入',
                    fallbackBody: '请确保已在本浏览器登录 Pixiv。点击「完成」后会打开 Pixiv 页面自动获取 Cookie 并返回，整个过程几秒内完成。'
                }
            ]
        });
        if (ctrl) ctrl.start(true);
    }


// ---- PixivBatch facade ----
window.PixivBatch.cookie = window.PixivBatch.cookie || {};
window.PixivBatch.cookie = Object.assign(window.PixivBatch.cookie, { getCookie, getCookieInputHeaderString, pixivHeader, cookieHasPhpsessid, applyCookieDependentUi, setCookieFmt, getCookieFmt, validateAndParseCookie, parseCookieToHeaderString });
