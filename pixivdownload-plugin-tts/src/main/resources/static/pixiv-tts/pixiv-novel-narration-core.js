/* eslint-disable */
/**
 * 小说「AI 多角色朗读」控制器（admin-only）。区别于单声道 pixiv-novel-tts.js：
 *
 *   - 朗读以「句」为粒度：后端 LLM 把整章逐句归属到说话人（旁白 / 角色），每个说话人有固定音色画像；
 *   - 每句按其说话人的音色用 VoxCPM 等富情感引擎逐句合成、顺序播放；
 *   - 高亮以「段落（渲染块）」为粒度：每句带 paragraphIndex（与正文 DOM 块顺序对齐），逐句高亮+滚动其所在段落，
 *     条上字幕显示「说话人：当前句」；
 *   - 分析结果持久化，重播不重算；可查看 / 编辑花名册音色（锁定），处理未解决冲突；编辑音色立即对后续合成生效。
 *
 * 模型只存与语言无关的原始数据（speaker / delivery / paragraphIndex / 原始码），渲染时再用 i18n 派生显示文案，
 * 切换界面语言可重新派生。
 */
(function (global) {
    'use strict';

    const modules = global.PixivNovelNarrationModules || (global.PixivNovelNarrationModules = {});
    modules.core = {
        install(ctx) {


    const LS = { segment: 'pixiv_narration_segment', showSpeakers: 'pixiv_narration_show_speakers' };
    const MARK_GAP = 14;              // 说话人列与正文之间的间隔（px）
    const MARK_NAME_CAP_RATIO = 0.10; // 说话人列宽上限：正文宽度的 10%，超出此宽度的名字换行
    const NOVEL_LIMIT = 50; // 音频缓存保留的小说数上限
    const PREFETCH_AHEAD = 20;       // 维持的预生成缓冲句数：播放时后台至少向前合成这么多句，消除逐句合成的停顿
    const PREFETCH_CONCURRENCY = 3;  // 后台并发合成上限：避免把整个缓冲窗口一次性压向合成后端

    function lsGet(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : v; } catch { return d; } }
    function lsSet(k, v) { try { localStorage.setItem(k, v); } catch {} }

    /** IndexedDB 持久化逐句合成音频，按小说 LRU 淘汰；键含 castUpdatedTime，音色编辑 / 重分析后自动失效。 */
    const NarrationStore = (function () {
        const DB_NAME = 'pixiv-narration';
        const AUDIO = 'audio';
        const META = 'meta';
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

        async function deleteAudioForNovel(novelId) {
            if (!available || !novelId) return;
            try {
                const db = await open();
                await deleteNovel(db, novelId);
            } catch {}
        }

        return { getAudio, putAudio, touch, deleteAudioForNovel };
    })();

    const state = {
        i18n: null,
        toast: null,
        contentEl: null,
        novelId: '',
        lang: '',
        blocks: [],            // [Element] 可朗读渲染块（与 paragraphIndex 对齐）
        lines: [],             // [{index, speakerId, speakerName, delivery, paragraphIndex, text}]
        cast: [],              // [{id, name, gender, age}]
        conflicts: [],         // [{characterId, name, type, reason, currentInstruction, suggestion}]
        scriptCastId: 0,       // 当前脚本所用花名册 id（0=纯旁白/无花名册）；冲突解决 / 选角编辑以它为对象
        editCastId: 0,         // 选角弹窗当前编辑的花名册 id（脚本册或弹窗内所选册）
        castUpdatedTime: 0,
        analyzedTime: 0,
        segmentSize: 0,
        scriptLoaded: false,
        loading: false,
        showSpeakers: false,   // 「显示分析出的说话人」：在正文左侧逐句标注说话人并整体缩进
        active: false,         // 是否为当前选中的听书引擎（仅 active 时才写共享控制条 DOM）
        index: -1,
        playing: false,
        paused: false,
        token: 0,
        audio: null,
        audioIndex: -1,
        pending: null,         // 合成完成但仍暂停时暂存 {url,index,token}
        fetching: null,        // {index, token}
        cache: new Map(),      // lineIndex -> blob URL（内存，当前 castUpdatedTime 下复用）
        inflight: new Map(),   // lineIndex -> Promise<url>：合成 / 读盘在途请求，去重「当前句直接取」与「后台预取」
        cacheGen: 0            // 缓存代际：clearCache 自增；在途请求据此判断结果是否仍可写入当前内存缓存
    };

    const els = {};

    function t(key, fallback, params) {
        if (state.i18n) return state.i18n.t(key, fallback, params);
        return fallback != null ? fallback : key;
    }

    function feedbackUnavailable() {
        if (state.toast) {
            state.toast(t('common:error.feature-unavailable',
                'This feature is temporarily unavailable. Refresh the page and try again.'), 'error');
        }
    }

    function requireFeedbackPrompt() {
        if (global.PixivFeedback && typeof global.PixivFeedback.prompt === 'function') return true;
        feedbackUnavailable();
        return false;
    }

    function feedbackPrompt(message, value) {
        if (!requireFeedbackPrompt()) return Promise.resolve(null);
        return global.PixivFeedback.prompt({
            message,
            value,
            confirmLabel: t('common:button.confirm', '确定'),
            cancelLabel: t('common:button.cancel', '取消')
        });
    }

    function feedbackConfirm(message) {
        if (!global.PixivFeedback || typeof global.PixivFeedback.confirm !== 'function') {
            feedbackUnavailable();
            return Promise.resolve(false);
        }
        return global.PixivFeedback.confirm({
            message,
            confirmLabel: t('common:button.confirm', '确定'),
            cancelLabel: t('common:button.cancel', '取消'),
            danger: true
        });
    }


            ctx.core = {
                LS, MARK_GAP, MARK_NAME_CAP_RATIO, PREFETCH_AHEAD, PREFETCH_CONCURRENCY,
                lsGet, lsSet, NarrationStore, state, els, t,
                requireFeedbackPrompt, feedbackPrompt, feedbackConfirm
            };
        }
    };
})(window);
