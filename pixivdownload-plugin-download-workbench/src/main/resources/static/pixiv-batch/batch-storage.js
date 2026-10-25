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
                try {
                    window.dispatchEvent(new CustomEvent('pixivbatch:storageloaded', {detail: {mode: appMode}}));
                } catch {
                }
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
