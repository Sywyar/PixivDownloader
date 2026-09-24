'use strict';
/* ============================================================
   alt-chrome — 顶栏（品牌 / 版本菜单 / 语言主题 / 登录）、Cookie 弹窗、
   油猴脚本弹窗、新手引导卡、后端不可用横幅、坞开关。
   应用信息 / 登录态 / 脚本清单均来自真实 API；
   脚本安装追踪逐字移植 batch-userscripts.js。
   ============================================================ */

/* ============================================================
   应用信息与版本菜单（/api/app/info）
   ============================================================ */
async function loadAppInfo() {
    const versionText = document.getElementById('abVersionText');
    try {
        const res = await fetch('/api/app/info', {credentials: 'same-origin'});
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        chromeState.appInfo = data;
        const version = data.version || bt('app.unknown', 'unknown');
        if (versionText) versionText.textContent = 'v' + version;
        const nameEl = document.getElementById('abAppName');
        if (nameEl) nameEl.textContent = data.name || 'PixivDownloader';
        const verEl = document.getElementById('abAppVersion');
        if (verEl) verEl.textContent = 'v' + version;
        setLinkHref('abLinkGithub', data.githubUrl);
        setLinkHref('abLinkReleases', data.releasesUrl);
        setLinkHref('abLinkDocs', data.docsUrl);
        setLinkHref('abLinkLicense', data.licenseUrl);
    } catch {
        if (versionText) versionText.textContent = bt('app.unknown', 'unknown');
    }
}

function setLinkHref(id, href) {
    const node = document.getElementById(id);
    if (!node) return;
    if (href) {
        node.href = href;
        node.classList.remove('is-disabled');
    } else {
        node.removeAttribute('href');
        node.classList.add('is-disabled');
    }
}

function bindVersionMenu() {
    const btn = document.getElementById('abVersionBtn');
    const menu = document.getElementById('abVersionMenu');
    if (!btn || !menu) return;
    btn.setAttribute('aria-controls', menu.id);
    const show = open => {
        menu.hidden = !open;
        btn.setAttribute('aria-expanded', String(open));
    };
    show(false);
    btn.addEventListener('click', event => {
        event.stopPropagation();
        show(menu.hidden);
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && !menu.hidden) {
            show(false);
            btn.focus();
            event.preventDefault();
        }
    });
    document.addEventListener('click', event => {
        if (!menu.hidden && !menu.contains(event.target) && event.target !== btn) {
            show(false);
        }
    });
}

/* ============================================================
   登录态（/api/auth/check 已在 detectAuthState 完成，这里只渲染）
   ============================================================ */
function renderAuthButton() {
    const btn = document.getElementById('abAuthBtn');
    if (!btn) return;
    btn.setAttribute('data-i18n', isAdmin ? 'auth.logout' : 'auth.login');
    btn.textContent = isAdmin ? bt('auth.logout', '退出') : bt('auth.login', '登录');
    btn.classList.toggle('ab-btn--primary', !isAdmin);
    btn.classList.toggle('ab-btn--ghost', isAdmin);
    btn.classList.toggle('button-fill', !isAdmin);
    btn.classList.toggle('button-tonal', isAdmin);
}

function bindAuthButton() {
    const btn = document.getElementById('abAuthBtn');
    if (!btn) return;
    btn.addEventListener('click', async () => {
        if (!isAdmin) {
            window.location.href = '/login.html?redirect=/pixiv-batch-alt.html';
            return;
        }
        if (!await abConfirm('dialog.confirm-logout', '确认退出登录？')) return;
        doLogout();
    });
}

/* ============================================================
   Cookie 芯片 + 弹窗
   ============================================================ */
function refreshCookieUi() {
    const chip = document.getElementById('abCookieChip');
    if (!chip) return;
    const ok = cookieHasPhpsessid();
    const any = hasPixivCookie();
    chromeState.cookieSaved = any;
    chip.classList.toggle('ab-chip--ok', ok);
    chip.classList.toggle('ab-chip--warn', !ok);
    const label = chip.querySelector('.ab-chip-label');
    if (label) {
        label.textContent = ok
            ? bt('cookie.status.saved', 'Cookie 已保存')
            : any
                ? bt('cookie.status.no-phpsessid', 'Cookie 缺少 PHPSESSID')
                : bt('cookie.status.missing', '未保存 Cookie');
    }
}

function updateCookieImportStatus(msg, tone) {
    const area = document.getElementById('abCookieParseArea');
    if (!area) {
        if (msg) abToast(tone === 'error' ? 'error' : 'info', msg);
        return;
    }
    area.textContent = msg || '';
    area.dataset.tone = tone || 'info';
    area.hidden = !msg;
}

function cookieFormatSeg(current) {
    const seg = el('div', 'ab-seg ab-seg--sm');
    [
        ['header', 'Header String'],
        ['json', 'JSON'],
        ['netscape', 'Netscape']
    ].forEach(([value, label]) => {
        const btn = el('button', 'ab-seg-item' + (current === value ? ' is-active' : ''), label);
        btn.type = 'button';
        btn.dataset.value = value;
        btn.setAttribute('aria-pressed', String(current === value));
        btn.addEventListener('click', () => {
            seg.querySelectorAll('.ab-seg-item').forEach(b => {
                b.classList.remove('is-active');
                b.setAttribute('aria-pressed', String(b === btn));
            });
            btn.classList.add('is-active');
            storeSet('pixiv_cookie_fmt', value);
        });
        seg.appendChild(btn);
    });
    return seg;
}

function openCookieModal() {
    const body = el('div', 'ab-cookie');

    const head = el('div', 'ab-cookie-head');
    const saved = hasPixivCookie();
    const ok = cookieHasPhpsessid();
    const statusPill = el('span', 'ab-pill ' + (ok ? 'ab-pill--ok' : 'ab-pill--warn'),
        ok ? bt('cookie.status.saved', 'Cookie 已保存')
            : saved ? bt('cookie.status.no-phpsessid', 'Cookie 缺少 PHPSESSID')
                : bt('cookie.status.missing', '未保存 Cookie'));
    head.appendChild(statusPill);
    const fmtSeg = cookieFormatSeg(getCookieFmt());
    head.appendChild(fmtSeg);
    body.appendChild(head);

    const inputWrap = el('div', 'ab-cookie-input-wrap');
    const input = el('textarea', 'ab-input ab-cookie-input');
    input.id = 'abCookieInput';
    input.setAttribute('aria-label', bt('cookie.title', 'Pixiv Cookie'));
    input.setAttribute('aria-describedby', 'abCookieParseArea');
    input.rows = 5;
    input.spellcheck = false;
    input.placeholder = bt('cookie.placeholder', '粘贴 Cookie（支持 Header String / JSON / Netscape 三种格式）');
    input.value = getStoredCookie('pixiv');
    inputWrap.appendChild(input);

    const toolRow = el('div', 'ab-cookie-tools');
    const pasteBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
    pasteBtn.type = 'button';
    pasteBtn.appendChild(abIconEl('paste'));
    pasteBtn.appendChild(el('span', '', bt('cookie.paste', '粘贴')));
    pasteBtn.addEventListener('click', async () => {
        try {
            input.value = await navigator.clipboard.readText();
            input.focus();
        } catch {
            abToast('warning', bt('cookie.paste-failed', '无法读取剪贴板，请手动粘贴'));
        }
    });
    const toggleBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
    toggleBtn.type = 'button';
    toggleBtn.appendChild(abIconEl('eye'));
    toggleBtn.appendChild(el('span', '', bt('cookie.toggle.show', '显示')));
    toggleBtn.addEventListener('click', () => {
        const masked = input.classList.toggle('is-masked');
        toggleBtn.querySelector('span:last-child').textContent =
            masked ? bt('cookie.toggle.show', '显示') : bt('cookie.toggle.hide', '隐藏');
    });
    toolRow.appendChild(pasteBtn);
    toolRow.appendChild(toggleBtn);
    inputWrap.appendChild(toolRow);
    body.appendChild(inputWrap);
    input.classList.add('is-masked');

    const parseArea = el('div', 'ab-cookie-parse');
    parseArea.id = 'abCookieParseArea';
    parseArea.setAttribute('role', 'status');
    parseArea.hidden = true;
    body.appendChild(parseArea);

    const formatsNote = el('details', 'ab-cookie-formats');
    const summary = el('summary', '', bt('cookie.formats.title', '支持的格式说明'));
    formatsNote.appendChild(summary);
    const fmtList = el('ul', 'ab-note-list');
    [
        bt('cookie.formats.header', 'Header String：直接复制浏览器请求头中的 Cookie 值'),
        bt('cookie.formats.json', 'JSON：{"key":"value", ...} 对象格式'),
        bt('cookie.formats.netscape', 'Netscape：EditThisCookie 等工具导出的 7 列 tab 分隔格式')
    ].forEach(text => fmtList.appendChild(el('li', '', text)));
    formatsNote.appendChild(fmtList);
    const guideLink = el('a', 'ab-cookie-guide', bt('cookie.guide-link', '获取 Cookie 指南'));
    guideLink.href = (chromeState.appInfo && chromeState.appInfo.docsUrl) || '#';
    guideLink.target = '_blank';
    guideLink.rel = 'noopener';
    if (!guideLink.getAttribute('href') || guideLink.getAttribute('href') === '#') {
        guideLink.classList.add('is-disabled');
    }
    formatsNote.appendChild(guideLink);
    body.appendChild(formatsNote);

    const actions = el('div', 'ab-cookie-actions');
    const importBtn = el('button', 'ab-btn ab-btn--ghost');
    importBtn.type = 'button';
    importBtn.appendChild(abIconEl('zap'));
    importBtn.appendChild(el('span', '', bt('cookie.import', '一键导入')));
    importBtn.disabled = appMode !== 'solo';
    if (appMode !== 'solo') {
        importBtn.title = bt('status.cookie-import-solo-only', '一键导入仅在 solo 模式可用');
    }
    importBtn.addEventListener('click', () => importCookieViaScript());
    const clearBtn = el('button', 'ab-btn ab-btn--danger-ghost');
    clearBtn.type = 'button';
    clearBtn.appendChild(abIconEl('trash'));
    clearBtn.appendChild(el('span', '', bt('cookie.clear', '清除')));
    clearBtn.addEventListener('click', async () => {
        if (!await abConfirm('dialog.confirm-clear-cookie', '确认清除已保存的 Cookie？')) return;
        removeStoredCookie('pixiv');
        input.value = '';
        updateCookieImportStatus(bt('status.cookie-cleared', 'Cookie 已清除'), 'info');
        refreshCookieUi();
        refreshQuickCredentialGate();
    });
    const saveBtn = el('button', 'ab-btn ab-btn--primary');
    saveBtn.type = 'button';
    saveBtn.appendChild(abIconEl('check'));
    saveBtn.appendChild(el('span', '', bt('cookie.save', '保存')));
    saveBtn.addEventListener('click', () => {
        const raw = input.value.trim();
        const activeFmt = fmtSeg.querySelector('.ab-seg-item.is-active');
        const result = validateAndParseCookie(raw, activeFmt ? activeFmt.dataset.value : getCookieFmt());
        if (!result.ok) {
            updateCookieImportStatus(bt('status.cookie-save-failed', 'Cookie 保存失败：{message}', {message: result.error}), 'error');
            return;
        }
        setStoredCookie('pixiv', raw);
        if (result.warnings.length) {
            updateCookieImportStatus(
                bt('status.cookie-saved-warning', 'Cookie 已保存（{count} 个字段）⚠ {warnings}', {
                    count: result.count,
                    warnings: result.warnings.join(punct('semicolon'))
                }), 'error');
        } else {
            updateCookieImportStatus(
                bt('status.cookie-saved', 'Cookie 已保存，共 {count} 个字段', {count: result.count}), 'info');
        }
        refreshCookieUi();
        refreshQuickCredentialGate();
    });
    actions.appendChild(importBtn);
    actions.appendChild(clearBtn);
    actions.appendChild(saveBtn);
    body.appendChild(actions);
    // 插件自定义 Cookie 卡槽位（与旧布局 cookie-tools 槽位同契约）：提供自定义卡的类型
    // 在此渲染其卡片（事件由插件经 pixivbatch:slotsrendered 自行绑定），其余类型走下方通用编辑器。
    const cookieToolsSlot = document.createElement('template');
    cookieToolsSlot.setAttribute('data-qt-slot', 'cookie-tools');
    body.appendChild(cookieToolsSlot);
    appendExtensionCookieEditors(body);

    openModal({
        id: 'cookie',
        icon: 'key',
        title: bt('cookie.title', 'Pixiv Cookie'),
        body,
        widthClass: 'ab-modal--wide'
    });
    // 弹窗 body 重建后重挂 cookie-tools 槽位内容。
    refreshAltSlots();
}

/* ============================================================
   油猴脚本弹窗（/api/scripts + 安装追踪逐字移植）
   ============================================================ */
const SCRIPT_ID_TOOLBOX = 'experience-toolbox';
const SCRIPT_ID_ALL_IN_ONE = 'all-in-one';
const ALL_IN_ONE_SCRIPT_IDS = [
    'experience-toolbox', 'artwork-java', 'user-batch', 'page-batch', 'import-batch'
];
const INSTALLED_SCRIPTS_KEY = 'pixiv_userscript_installed';

function getInstalledScripts() {
    try {
        return JSON.parse(localStorage.getItem(INSTALLED_SCRIPTS_KEY) || '{}') || {};
    } catch (e) {
        return {};
    }
}

function markScriptInstalled(id) {
    const map = getInstalledScripts();
    map[id] = true;
    if (id === SCRIPT_ID_ALL_IN_ONE) {
        ALL_IN_ONE_SCRIPT_IDS.forEach(sid => {
            map[sid] = true;
        });
    }
    try {
        localStorage.setItem(INSTALLED_SCRIPTS_KEY, JSON.stringify(map));
    } catch (e) {
        /* 隐私模式等场景静默降级 */
    }
}

function isToolboxInstalled() {
    const map = getInstalledScripts();
    return map[SCRIPT_ID_TOOLBOX] === true || map[SCRIPT_ID_ALL_IN_ONE] === true;
}

function installScript(id) {
    // 记录该脚本安装按钮已被点击（All-in-One 连带标记其覆盖的脚本）
    markScriptInstalled(id);
    // URL 必须以 .user.js 结尾，Tampermonkey 才会拦截并弹出安装确认页
    window.location.href = '/api/scripts/' + encodeURIComponent(id) + '.user.js';
}

async function fetchScriptsList() {
    try {
        const resp = await fetch('/api/scripts?lang=' + encodeURIComponent(uiLang()));
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const data = await resp.json();
        const items = data.scripts || data.items || [];
        if (!items.length) return {items: [], empty: true};
        return {
            items: items.map(s => ({
                id: s.id,
                name: s.displayName || s.name || s.id,
                version: s.version || '',
                description: s.description || ''
            }))
        };
    } catch (e) {
        return {items: [], error: String(e && e.message || bt('common.request-failed', '请求失败'))};
    }
}

async function openScriptsModal() {
    const body = el('div', 'ab-scripts');
    body.appendChild(el('p', 'ab-loading-line', bt('userscripts.loading', '加载中…')));
    openModal({
        id: 'scripts',
        icon: 'puzzle',
        title: bt('scripts.title', '油猴脚本'),
        body,
        widthClass: 'ab-modal--wide'
    });
    const result = await fetchScriptsList();
    body.innerHTML = '';
    if (result.error) {
        body.appendChild(errorBox(result.error, openScriptsModal));
        return;
    }
    if (result.empty) {
        body.appendChild(el('p', 'ab-empty-line', bt('userscripts.empty', '暂无可安装的脚本。')));
        return;
    }
    const installed = getInstalledScripts();
    result.items.forEach((s, idx) => {
        const card = el('div', 'ab-script-card card');
        card.style.setProperty('--stagger', String(idx));
        const head = el('div', 'ab-script-head');
        head.appendChild(el('strong', 'ab-script-name', s.name));
        if (s.version) head.appendChild(el('span', 'ab-script-version', 'v' + s.version));
        if (installed[s.id]) {
            const done = el('span', 'ab-pill ab-pill--ok', bt('scripts.installed', '已安装'));
            head.appendChild(done);
        }
        card.appendChild(head);
        if (s.description) card.appendChild(el('p', 'ab-script-desc', s.description));
        const actions = el('div', 'ab-script-actions');
        const installBtn = el('button', 'ab-btn ab-btn--primary ab-btn--sm');
        installBtn.type = 'button';
        installBtn.appendChild(abIconEl('download'));
        installBtn.appendChild(el('span', '', bt('userscripts.install', '安装')));
        installBtn.addEventListener('click', () => installScript(s.id));
        const sourceLink = el('a', 'ab-btn ab-btn--ghost ab-btn--sm');
        sourceLink.href = '/api/scripts/' + encodeURIComponent(s.id) + '?raw=true';
        sourceLink.target = '_blank';
        sourceLink.rel = 'noopener';
        sourceLink.appendChild(abIconEl('file-text'));
        sourceLink.appendChild(el('span', '', bt('userscripts.view-source', '查看源码')));
        actions.appendChild(installBtn);
        actions.appendChild(sourceLink);
        card.appendChild(actions);
        body.appendChild(card);
    });
}

/* ============================================================
   后端不可用横幅 / 坞开关
   ============================================================ */
function renderBackendBanner() {
    const banner = document.getElementById('abBackendBanner');
    if (!banner) return;
    banner.hidden = chromeState.backendAvailable !== false;
    hydrateIcons(banner);
}

function bindDockToggle() {
    const toggle = document.getElementById('abDockToggle');
    const close = document.getElementById('abDockClose');
    if (toggle) toggle.addEventListener('click', openDock);
    if (close) close.addEventListener('click', () => switchMode(lastAcquisitionMode));
    document.getElementById('abPrepareTab')?.addEventListener('click', () => switchMode(lastAcquisitionMode));
    document.getElementById('abScheduleTab')?.addEventListener('click', () => switchMode('schedule'));
}

function syncWorkspaceNavigation() {
    const downloads = dockState.open;
    const scheduled = !downloads && state.mode === 'schedule';
    const stage = document.getElementById('abStage');
    const dock = document.getElementById('abDock');
    const rail = document.getElementById('abRail');
    if (stage) stage.hidden = downloads;
    if (dock) dock.hidden = !downloads;
    if (rail) rail.hidden = scheduled;
    [['abPrepareTab', !downloads && !scheduled], ['abDockToggle', downloads], ['abScheduleTab', scheduled]]
        .forEach(([id, active]) => {
            const tab = document.getElementById(id);
            if (!tab) return;
            tab.classList.toggle('is-active', active);
            if (active) tab.setAttribute('aria-current', 'page');
            else tab.removeAttribute('aria-current');
        });
}

let acquisitionScroll = 0;
let downloadsScroll = 0;

function toggleDock(force) {
    const open = force !== undefined ? force : !dockState.open;
    const changed = open !== dockState.open;
    if (changed) {
        if (open) acquisitionScroll = window.scrollY;
        else downloadsScroll = window.scrollY;
    }
    dockState.open = open;
    syncWorkspaceNavigation();
    if (changed) window.scrollTo({top: open ? downloadsScroll : acquisitionScroll, behavior: 'instant'});
}

function openDock() {
    toggleDock(true);
}

function bindChrome() {
    bindVersionMenu();
    bindAuthButton();
    bindDockToggle();
    const cookieChip = document.getElementById('abCookieChip');
    if (cookieChip) cookieChip.addEventListener('click', openCookieModal);
    const scriptsBtn = document.getElementById('abScriptsBtn');
    if (scriptsBtn) scriptsBtn.addEventListener('click', openScriptsModal);
}

window.PixivBatchAlt.chrome = Object.assign(window.PixivBatchAlt.chrome, {
    loadAppInfo, renderAuthButton, bindChrome,
    refreshCookieUi, openCookieModal, updateCookieImportStatus,
    openScriptsModal, isToolboxInstalled, markScriptInstalled,
    renderBackendBanner, toggleDock, openDock
});
