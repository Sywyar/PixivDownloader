'use strict';

let _saveTimer = null;
let _saveRequest = Promise.resolve();

function persistStoreEntries(entries) {
    const apply = (target) => Object.entries(entries).forEach(([key, value]) => {
        if (value == null) delete target[key];
        else target[key] = value;
    });
    if (appMode !== 'solo') {
        const previous = Object.fromEntries(Object.keys(entries).map(key => [key, localStorage.getItem(key)]));
        const write = values => Object.entries(values).forEach(([key, value]) => {
            if (value == null) localStorage.removeItem(key);
            else localStorage.setItem(key, value);
        });
        try { write(entries); } catch (error) {
            try { write(previous); } catch {}
            return Promise.reject(error);
        }
        return Promise.resolve();
    }
    // 服务端整份替换状态；串行写入，凭据成功后才发布，避免旧自动保存覆盖新凭据。
    _saveRequest = _saveRequest.catch(() => {}).then(async () => {
        const snapshot = {...serverState};
        apply(snapshot);
        const response = await fetch(BASE + '/api/batch/state', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({state: snapshot}), credentials: 'same-origin'
        });
        if (!response.ok) throw new Error('HTTP ' + response.status);
        apply(serverState);
    });
    return _saveRequest;
}

function scheduleServerSave() {
    if (_saveTimer) clearTimeout(_saveTimer);
    _saveTimer = setTimeout(() => {
        _saveTimer = null;
        persistStoreEntries({}).catch(() => {});
    }, 400);
}

function flushServerState() {
    if (_saveTimer) clearTimeout(_saveTimer);
    _saveTimer = null;
    return appMode === 'solo' ? persistStoreEntries({}) : Promise.resolve();
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
        _saveTimer = null;
        try {
            await persistStoreEntries({pixiv_cookie: null});
        } catch {
        }
    }
    try {
        await fetch('/api/auth/logout', {method: 'POST', credentials: 'same-origin'});
    } catch {
    }
    window.location.href = '/pixiv-batch.html';
}
