'use strict';
    /* ============================================================
       油猴脚本安装面板
    ============================================================ */
    let _userscriptsLoaded = false;
    let _userscriptsGeneration = 0;

    function toggleUserscripts() {
        const panel = document.getElementById('userscripts-panel');
        if (panel.hidden) {
            panel.hidden = false;
            _userscriptsLoaded = true;
            loadUserscripts();
        } else {
            panel.hidden = true;
        }
    }

    async function loadUserscripts() {
        const generation = ++_userscriptsGeneration;
        const list = document.getElementById('userscripts-list');
        list.textContent = bt('userscripts.loading', '加载中…');
        try {
            const resp = await fetch('/api/scripts?lang=' + encodeURIComponent(uiLang()));
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            const data = await resp.json();
            if (generation !== _userscriptsGeneration) return;

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
            const hint = document.createElement('p');
            hint.className = 'userscripts-hint';
            hint.textContent = bt('userscripts.detection-hint');
            const refresh = document.createElement('button');
            refresh.type = 'button';
            refresh.className = 'btn btn-blue userscript-card-btn';
            refresh.textContent = bt('userscripts.detect-again');
            refresh.addEventListener('click', loadUserscripts);
            list.append(hint, refresh);
            const statuses = [];
            data.scripts.forEach(s => {
                const item = document.createElement('div');
                item.className = 'userscript-card';
                item.innerHTML =
                    '<div class="userscript-card-head">' +
                        '<strong class="userscript-card-title">' + escHtml(s.displayName) + '</strong>' +
                        '<span class="userscript-card-version">' + escHtml(bt('userscripts.available-version', undefined, {version: s.version})) + '</span>' +
                    '</div>' +
                    '<div class="userscript-card-desc">' + escHtml(s.description) + '</div>' +
                    '<div class="userscript-card-actions">' +
                        '<button class="btn btn-green userscript-card-btn" data-install-id="' + escHtml(s.id) + '" data-pixiv-click="installScriptFromElement(this)">' +
                            escHtml(bt('userscripts.install', '⬇ 安装')) +
                        '</button>' +
                        '<a class="btn btn-blue userscript-card-btn userscript-card-source" ' +
                            'href="/api/scripts/' + encodeURIComponent(s.id) + '?raw=true" target="_blank">' +
                            escHtml(bt('userscripts.view-source', '📄 查看源码')) +
                        '</a>' +
                    '</div>';
                const status = document.createElement('p');
                status.className = 'userscript-card-desc';
                status.setAttribute('role', 'status');
                status.textContent = bt('userscripts.detecting');
                item.appendChild(status);
                statuses.push([s.id, status]);
                list.appendChild(item);
            });
            refresh.disabled = true;
            const detected = await PixivUserscriptDetection.probe(data.scripts.map(s => s.id));
            if (generation !== _userscriptsGeneration) return;
            statuses.forEach(([id, node]) => { node.textContent = PixivUserscriptDetection.describe(id, detected, bt); });
            refresh.disabled = false;
        } catch (e) {
            if (generation !== _userscriptsGeneration) return;
            list.textContent = bt('userscripts.load-failed', '加载失败：{message}', {message: e.message});
        }
    }

    const SCRIPT_ID_TOOLBOX = 'experience-toolbox';
    const SCRIPT_ID_ALL_IN_ONE = 'all-in-one';

    function isToolboxInstalled() {
        return PixivUserscriptDetection.has(SCRIPT_ID_TOOLBOX);
    }

    function installScript(id) {
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
window.PixivBatch.userscripts = Object.assign(window.PixivBatch.userscripts, { toggleUserscripts, loadUserscripts, ensureUserscriptsExpanded, isToolboxInstalled });
