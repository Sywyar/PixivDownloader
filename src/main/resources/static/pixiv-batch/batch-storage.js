'use strict';
    async function detectMode() {
        try {
            const res = await fetch('/api/setup/status');
            if (res.ok) {
                const data = await res.json();
                appMode = data.mode === 'solo' ? 'solo' : 'multi';
                multiModeLimitPage = Math.max(0, data.multiModeLimitPage ?? 0);
            }
        } catch {
            appMode = 'multi';
        }
    }

    async function loadServerState() {
        try {
            const res = await fetch(BASE + '/api/batch/state');
            if (res.ok) {
                const data = await res.json();
                serverState = data.state ?? {};
            }
        } catch {
        }
    }

    async function detectAuthState() {
        try {
            const res = await fetch('/api/auth/check', {credentials: 'same-origin'});
            if (!res.ok) {
                isAdmin = false;
                return;
            }
            const data = await res.json();
            isAdmin = !!data.valid;
        } catch {
            isAdmin = false;
        }
    }

    let _saveTimer = null;

    function scheduleServerSave() {
        if (_saveTimer) clearTimeout(_saveTimer);
        _saveTimer = setTimeout(() => {
            fetch(BASE + '/api/batch/state', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({state: serverState}),
                credentials: 'same-origin'
            }).catch(() => {
            });
        }, 400);
    }

    /** 统一存储读取：solo 模式读服务器内存，multi 模式读 localStorage */
    function storeGet(key) {
        if (appMode === 'solo') {
            const v = serverState[key];
            return v != null ? String(v) : null;
        }
        return localStorage.getItem(key);
    }

    function storeSet(key, value) {
        if (appMode === 'solo') {
            serverState[key] = value;
            scheduleServerSave();
        } else localStorage.setItem(key, value);
    }

    function storeRemove(key) {
        if (appMode === 'solo') {
            delete serverState[key];
            scheduleServerSave();
        } else localStorage.removeItem(key);
    }

    async function doLogout() {
        // solo 模式下退出登录同时清除服务器保存的 Cookie；必须在 logout 使 session 失效前持久化
        if (appMode === 'solo') {
            if (_saveTimer) clearTimeout(_saveTimer);
            delete serverState['pixiv_cookie'];
            try {
                await fetch(BASE + '/api/batch/state', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({state: serverState}),
                    credentials: 'same-origin'
                });
            } catch {
            }
        }
        try {
            await fetch('/api/auth/logout', {method: 'POST', credentials: 'same-origin'});
        } catch {
        }
        window.location.href = '/pixiv-batch.html';
    }

    function updateAuthButtons() {
        document.getElementById('login-btn').style.display = isAdmin ? 'none' : 'block';
        document.getElementById('logout-btn').style.display = isAdmin ? 'block' : 'none';
        // 计划任务为管理员专用：非管理员隐藏该 tab
        const schedTab = document.getElementById('tab-schedule');
        if (schedTab) {
            schedTab.style.display = isAdmin ? '' : 'none';
            // 非管理员若停留在 schedule 模式则退回默认模式
            if (!isAdmin && state.mode === 'schedule') switchMode(SINGLE_IMPORT_MODE);
        }
        updateSaveScheduleCardVisibility();
        updateExtraFiltersCardVisibility();
        updateBatchLimitNote();
        updateBatchEndPageAdminGate();
        // 自动翻译为管理员专用：管理员状态变化后刷新该行显隐
        updateNovelTranslateVisibility();
    }


// ---- PixivBatch facade ----
window.PixivBatch.storage = window.PixivBatch.storage || {};
window.PixivBatch.storage = Object.assign(window.PixivBatch.storage, { detectMode, storeGet, storeSet, storeRemove, scheduleServerSave, doLogout, loadServerState, detectAuthState });
