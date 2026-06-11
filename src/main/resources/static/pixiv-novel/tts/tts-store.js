/* eslint-disable */
/**
 * 听书「本地存储」子模块：localStorage 小工具 + IndexedDB 合成音频缓存。
 * 纯工具，无业务状态，输入输出皆为参数与 Promise，故独立成文件，对外 window.PixivTtsStore。
 *
 *   - lsGet/lsSet：对 localStorage 读写做异常兜底。
 *   - IndexedDB：持久化在线合成音频（Blob 太大无法放 localStorage）。按小说做 LRU 淘汰，
 *     最多保留 NOVEL_LIMIT 本小说的音频；每本含其全部段落/语音/语速变体。
 */
(function (global) {
    'use strict';

    // 合成音频缓存的小说数上限。与主控制器 pixiv-novel-tts.js 的「进度记忆上限」保持同一数值。
    const NOVEL_LIMIT = 100;

    function lsGet(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : v; } catch { return d; } }
    function lsSet(k, v) { try { localStorage.setItem(k, v); } catch {} }

    const DB_NAME = 'pixiv-tts';
    const AUDIO = 'audio';   // { key, novelId, blob }
    const META = 'meta';     // { novelId, lastUsed }
    let dbPromise = null;
    const available = typeof indexedDB !== 'undefined';

    function open() {
        if (dbPromise) return dbPromise;
        dbPromise = new Promise((resolve, reject) => {
            const req = indexedDB.open(DB_NAME, 1);
            req.onupgradeneeded = () => {
                const db = req.result;
                if (!db.objectStoreNames.contains(AUDIO)) {
                    db.createObjectStore(AUDIO, { keyPath: 'key' })
                        .createIndex('novelId', 'novelId', { unique: false });
                }
                if (!db.objectStoreNames.contains(META)) {
                    db.createObjectStore(META, { keyPath: 'novelId' });
                }
            };
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
        });
        return dbPromise;
    }

    async function getAudio(key) {
        if (!available) return null;
        try {
            const db = await open();
            return await new Promise((resolve) => {
                const r = db.transaction(AUDIO, 'readonly').objectStore(AUDIO).get(key);
                r.onsuccess = () => resolve(r.result ? r.result.blob : null);
                r.onerror = () => resolve(null);
            });
        } catch { return null; }
    }

    async function putAudio(key, novelId, blob) {
        if (!available || !novelId) return;
        try {
            const db = await open();
            await new Promise((resolve) => {
                const tx = db.transaction([AUDIO, META], 'readwrite');
                tx.objectStore(AUDIO).put({ key, novelId, blob });
                tx.objectStore(META).put({ novelId, lastUsed: Date.now() });
                tx.oncomplete = resolve;
                tx.onerror = resolve;
                tx.onabort = resolve;
            });
            await evict();
        } catch {}
    }

    // 标记某本小说为最近使用（打开页面时调用），避免被淘汰
    async function touch(novelId) {
        if (!available || !novelId) return;
        try {
            const db = await open();
            await new Promise((resolve) => {
                const tx = db.transaction(META, 'readwrite');
                tx.objectStore(META).put({ novelId, lastUsed: Date.now() });
                tx.oncomplete = resolve;
                tx.onerror = resolve;
                tx.onabort = resolve;
            });
        } catch {}
    }

    async function evict() {
        try {
            const db = await open();
            const metas = await new Promise((resolve) => {
                const r = db.transaction(META, 'readonly').objectStore(META).getAll();
                r.onsuccess = () => resolve(r.result || []);
                r.onerror = () => resolve([]);
            });
            if (metas.length <= NOVEL_LIMIT) return;
            metas.sort((a, b) => (a.lastUsed || 0) - (b.lastUsed || 0));
            const victims = metas.slice(0, metas.length - NOVEL_LIMIT);
            for (const m of victims) await deleteNovel(db, m.novelId);
        } catch {}
    }

    function deleteNovel(db, novelId) {
        return new Promise((resolve) => {
            const tx = db.transaction([AUDIO, META], 'readwrite');
            const store = tx.objectStore(AUDIO);
            const idxReq = store.index('novelId').getAllKeys(novelId);
            idxReq.onsuccess = () => (idxReq.result || []).forEach((k) => store.delete(k));
            tx.objectStore(META).delete(novelId);
            tx.oncomplete = resolve;
            tx.onerror = resolve;
            tx.onabort = resolve;
        });
    }

    global.PixivTtsStore = { lsGet: lsGet, lsSet: lsSet, getAudio: getAudio, putAudio: putAudio, touch: touch };
})(window);
