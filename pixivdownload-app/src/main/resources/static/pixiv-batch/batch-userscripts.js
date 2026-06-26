'use strict';
    /* ============================================================
       油猴脚本安装面板
    ============================================================ */
    let _userscriptsLoaded = false;

    function toggleUserscripts() {
        const panel = document.getElementById('userscripts-panel');
        if (panel.hidden) {
            panel.hidden = false;
            if (!_userscriptsLoaded) {
                _userscriptsLoaded = true;
                loadUserscripts();
            }
        } else {
            panel.hidden = true;
        }
    }

    async function loadUserscripts() {
        const list = document.getElementById('userscripts-list');
        list.textContent = bt('userscripts.loading', '加载中…');
        try {
            const resp = await fetch('/api/scripts?lang=' + encodeURIComponent(uiLang()));
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            const data = await resp.json();

            // 显示 host 提示
            const hostHint = document.getElementById('userscripts-host-hint');
            if (data.detectedHost && data.detectedHost !== 'localhost' && data.detectedHost !== '127.0.0.1') {
                hostHint.textContent = bt(
                    'userscripts.host-hint',
                    '此安装链接将自动把脚本 @connect 指向：{host}',
                    {host: data.detectedHost}
                );
                hostHint.style.display = 'block';
            } else {
                hostHint.textContent = '';
                hostHint.style.display = 'none';
            }

            list.innerHTML = '';
            if (!data.scripts || data.scripts.length === 0) {
                list.textContent = bt('userscripts.empty', '暂无可安装的脚本。');
                return;
            }
            data.scripts.forEach(s => {
                const item = document.createElement('div');
                item.className = 'userscript-card';
                item.innerHTML =
                    '<div class="userscript-card-head">' +
                        '<strong class="userscript-card-title">' + escHtml(s.displayName) + '</strong>' +
                        '<span class="userscript-card-version">v' + escHtml(s.version) + '</span>' +
                    '</div>' +
                    '<div class="userscript-card-desc">' + escHtml(s.description) + '</div>' +
                    '<div class="userscript-card-actions">' +
                        '<button class="btn btn-green userscript-card-btn" data-install-id="' + escHtml(s.id) + '" onclick="installScript(\'' + escHtml(s.id) + '\')">' +
                            escHtml(bt('userscripts.install', '⬇ 安装')) +
                        '</button>' +
                        '<a class="btn btn-blue userscript-card-btn userscript-card-source" ' +
                            'href="/api/scripts/' + encodeURIComponent(s.id) + '?raw=true" target="_blank">' +
                            escHtml(bt('userscripts.view-source', '📄 查看源码')) +
                        '</a>' +
                    '</div>';
                list.appendChild(item);
            });
        } catch (e) {
            list.textContent = bt('userscripts.load-failed', '加载失败：{message}', {message: e.message});
        }
    }

    /* ------------------------------------------------------------------
       脚本安装追踪（按浏览器记录，localStorage）
       仅记录「用户点过哪个脚本的安装按钮」，无法真正探测 Tampermonkey 是否
       装好；用于「一键导入 Cookie」判断是否需要先引导安装体验增强工具箱。
       All-in-One 合并包内含除「Local Download」外的全部脚本，安装它视为
       这些脚本（含体验增强工具箱）均已安装。
    ------------------------------------------------------------------ */
    const SCRIPT_ID_TOOLBOX = 'experience-toolbox';
    const SCRIPT_ID_ALL_IN_ONE = 'all-in-one';
    const SCRIPT_ID_LOCAL_DOWNLOAD = 'artwork-local';
    // All-in-One 覆盖的脚本 id（除 Local Download 外的全部）
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

    function ensureUserscriptsExpanded() {
        // 油猴脚本卡现位于「工具」抽屉内：先确保抽屉展开，卡片才可见 / 可滚动定位。
        const drawer = document.getElementById('tools-drawer');
        if (drawer && !drawer.open) drawer.open = true;
        const panel = document.getElementById('userscripts-panel');
        if (panel && panel.hidden) {
            toggleUserscripts();
        } else if (!_userscriptsLoaded) {
            _userscriptsLoaded = true;
            loadUserscripts();
        }
        const card = document.getElementById('userscripts-card');
        if (card && card.scrollIntoView) {
            card.scrollIntoView({block: 'center', behavior: 'smooth'});
        }
    }


// ---- PixivBatch facade ----
window.PixivBatch.userscripts = window.PixivBatch.userscripts || {};
window.PixivBatch.userscripts = Object.assign(window.PixivBatch.userscripts, { toggleUserscripts, loadUserscripts, ensureUserscriptsExpanded, isToolboxInstalled, markScriptInstalled, getInstalledScripts });
