// ==UserScript==
// @name         Pixiv external download gallery sync
// @name:en      PixivBatchDownloader gallery auto import
// @name:zh-CN   PixivBatchDownloader 自动导入画廊
// @name:zh-TW   PixivBatchDownloader 自動匯入畫廊
// @name:ja      PixivBatchDownloader ギャラリー自動登録
// @name:ko      PixivBatchDownloader 갤러리 자동 가져오기
// @namespace    https://github.com/Sywyar/PixivDownloader
// @version      1.1.0
// @description  After one-time download directory setup, automatically add new PixivBatchDownloader illustrations, manga, animations and novels to the local gallery. Keep original files; do not scan past downloads. Allow userscripts and refresh Pixiv before use.
// @description:zh-CN 配置一次下载目录、启用用户脚本权限并刷新 Pixiv 页面后，自动将 PixivBatchDownloader 此后下载完成的插画、漫画、动图和小说加入本机画廊。保留原文件，不扫描历史下载。
// @description:zh-TW 設定一次下載目錄、啟用使用者腳本權限並重新整理 Pixiv 頁面後，自動將 PixivBatchDownloader 此後下載完成的插畫、漫畫、動圖和小說加入本機畫廊。保留原始檔案，不掃描歷史下載。
// @description:ja 初回に保存先を設定し、ユーザースクリプトの実行を許可して Pixiv を再読み込みすると、以後 PixivBatchDownloader でダウンロードが完了したイラスト・漫画・うごイラ・小説をローカルのギャラリーに自動登録します。元ファイルは保持され、過去のダウンロードは走査しません。
// @description:ko 다운로드 폴더를 한 번 설정하고 유저스크립트 실행을 허용한 뒤 Pixiv 페이지를 새로 고치면, 이후 PixivBatchDownloader로 다운로드를 완료한 일러스트, 만화, 애니메이션과 소설을 로컬 갤러리에 자동으로 추가합니다. 원본은 보존되며 이전 다운로드는 검색하지 않습니다.
// @match        https://www.pixiv.net/*
// @match        https://pixiv.net/*
// @run-at       document-start
// @grant        GM_xmlhttpRequest
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_deleteValue
// @grant        GM_listValues
// @grant        GM_registerMenuCommand
// @connect      localhost
// @connect      127.0.0.1
// @connect      ::1
// ==/UserScript==

(() => {
    'use strict';
    const MAX_WORKS = 1000, MAX_FILES = 5000, MAX_BYTES = 8 * 1024 * 1024;
    const PREFIX = 'gallery-sync.work.';
    const metadata = new Map();
    const works = new Map();
    const session = crypto.randomUUID();
    const locale = (navigator.language || 'en').toLowerCase();
    const language = locale.startsWith('zh') ? (/tw|hk|hant/.test(locale) ? 'zh-Hant' : 'zh')
        : locale.startsWith('ja') ? 'ja' : locale.startsWith('ko') ? 'ko' : 'en';
    const messages = {
        zh: ['设置本机服务地址', '自动导入状态', '本机服务地址（默认 http://localhost:6999）', '只支持本机 localhost、127.0.0.1 或 [::1]。', '等待导入 / 未完成 / 需检查', '采集容量已满，请启动本机服务并检查自动导入状态。', '尚未捕获作品元数据，请刷新页面后重新抓取。'],
        'zh-Hant': ['設定本機服務位址', '自動匯入狀態', '本機服務位址（預設 http://localhost:6999）', '僅支援本機 localhost、127.0.0.1 或 [::1]。', '等待匯入 / 未完成 / 需檢查', '採集容量已滿，請啟動本機服務並檢查自動匯入狀態。', '尚未取得作品中繼資料，請重新整理頁面後重新擷取。'],
        en: ['Set local server address', 'Automatic import status', 'Local server address (default http://localhost:6999)', 'Only localhost, 127.0.0.1 or [::1] is supported.', 'Pending / Incomplete / Needs attention', 'Collector storage is full. Start the local server and check import status.', 'Work metadata is missing. Refresh the page and crawl the work again.'],
        ja: ['ローカルサーバーの設定', '自動登録の状態', 'ローカルサーバー（既定 http://localhost:6999）', 'localhost、127.0.0.1、[::1] のみ使用できます。', '登録待ち / 未完了 / 要確認', '収集容量が上限に達しました。ローカルサーバーを起動して状態を確認してください。', '作品情報がありません。ページを再読み込みして再取得してください。'],
        ko: ['로컬 서버 주소 설정', '자동 가져오기 상태', '로컬 서버 주소 (기본 http://localhost:6999)', 'localhost, 127.0.0.1 또는 [::1]만 지원합니다.', '대기 / 미완료 / 확인 필요', '수집 공간이 가득 찼습니다. 로컬 서버를 시작하고 상태를 확인하세요.', '작품 정보가 없습니다. 페이지를 새로 고친 후 다시 수집하세요.']
    }[language];
    let metadataBytes = 0;
    let running = false, stopped = false, lastError = '', warned = false;
    let backoff = 1000, nextAttempt = 0;
    function server() {
        const url = new URL(GM_getValue('gallery-sync.server', 'http://localhost:6999'));
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
            || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
            || url.pathname !== '/' || url.search || url.hash) throw new Error('INVALID_SERVER');
        return url.origin;
    }
    function keys() { return GM_listValues().filter(key => key.startsWith(PREFIX)).sort(); }
    function warn(message) { lastError = message; if (!warned) { warned = true; alert(message); } }
    function save(key, work) {
        const encoded = JSON.stringify(work);
        // ponytail: 扫描最多 1000 个、总计 8 MiB 的持久条目；提高容量前改用独立索引。
        const others = keys().filter(value => value !== key);
        let bytes = new TextEncoder().encode(encoded).length, files = work.files.length;
        for (const other of others) {
            const value = GM_getValue(other, '');
            bytes += new TextEncoder().encode(value).length;
            try { files += JSON.parse(value).files.length; } catch (_) { /* 损坏条目由发送循环报告 */ }
        }
        if (others.length >= MAX_WORKS || bytes > MAX_BYTES || files > MAX_FILES) {
            warn(messages[5]); return false;
        }
        GM_setValue(key, encoded); return true;
    }
    function eventData(event) { return event && event.detail && event.detail.data; }
    function captureMetadata(event) {
        const data = eventData(event);
        if (!data || !Number.isSafeInteger(data.idNum) || data.idNum <= 0 || ![0,1,2,3].includes(data.type)) return;
        const key = `${data.type === 3 ? 'novel' : 'artwork'}:${data.idNum}`;
        if (!metadata.has(key) && metadata.size >= MAX_WORKS) { warn(messages[5]); return; }
        const text = (value, limit) => typeof value === 'string' && value.length <= limit ? value : null;
        const value = {
            id: String(data.idNum), type: data.type, title: text(data.title, 1000),
            pageCount: data.type >= 2 ? 1 : data.pageCount,
            restriction: data.xRestrict, aiType: data.aiType, authorId: String(data.userId),
            authorName: text(data.user,1000), description: text(data.description,32768),
            seriesId: data.seriesId || null, seriesOrder: data.seriesId ? data.seriesOrder : null,
            tags: Array.isArray(data.tags) && data.tags.length <= 256
                && data.tags.every(tag => typeof tag === 'string' && tag.length <= 512) ? data.tags.slice() : null,
            novelContent: data.type === 3 ? text(data.novelMeta && data.novelMeta.content,3000000) : null
        };
        if (JSON.stringify(value).length > MAX_BYTES / 2) { warn(messages[5]); return; }
        const bytes = new TextEncoder().encode(JSON.stringify(value)).length;
        const nextBytes = metadataBytes - (metadata.get(key)?.bytes || 0) + bytes;
        if (nextBytes > MAX_BYTES) { warn(messages[5]); return; }
        metadataBytes = nextBytes;
        metadata.set(key, {value, observedAt: Date.now(), bytes});
    }
    function captureSuccess(event) {
        const data = eventData(event);
        if (!data || data.noReply || !Number.isSafeInteger(data.taskBatch) || data.taskBatch <= 0
            || !Number.isSafeInteger(data.tabId) || typeof data.browserSetFilename !== 'string'
            || !data.browserSetFilename || data.browserSetFilename.length > 4096) return;
        const match = /^([1-9][0-9]*)(?:_p([0-9]+))?$/.exec(String(data.id));
        if (!match) return;
        const candidates = [...metadata.values()].filter(item => item.value.id === match[1]
            && item.observedAt <= data.taskBatch
            && (match[2] === undefined ? item.value.type >= 2 : item.value.type < 2));
        if (candidates.length !== 1) { warn(messages[6]); return; }
        const meta = candidates[0].value;
        const type = meta.type === 3 ? 'novel' : 'artwork';
        const key = PREFIX + `${session}:${data.tabId}:${data.taskBatch}:${type}:${meta.id}`;
        let work = works.get(key);
        if (!work) {
            if (works.size >= MAX_WORKS) { warn(messages[5]); return; }
            work = {schemaVersion: 1, source: 'pixiv-batch-downloader', taskBatch: data.taskBatch,
                tabId: data.tabId, metadata: meta, files: []};
        }
        const page = match[2] === undefined ? 0 : Number(match[2]);
        if (!Number.isInteger(meta.pageCount) || meta.pageCount < 1 || meta.pageCount > 1000
            || page < 0 || page >= meta.pageCount) return;
        const existing = work.files.find(file => file.page === page);
        if (existing && existing.path !== data.browserSetFilename) { work.error = 'AMBIGUOUS_PAGE'; save(key, work); return; }
        if (!existing) work.files.push({fileId: String(data.id), page, path: data.browserSetFilename, outcome: 'success'});
        if (save(key, work)) { works.set(key, work); drain(); }
    }
    function request(base, path, options = {}) {
        return new Promise((resolve, reject) => GM_xmlhttpRequest({
            method: options.data ? 'POST' : 'GET', url: base + path, anonymous: true, timeout: 10000,
            // 匿名 GM 请求可能省略来源头，显式携带当前 Pixiv 来源供后端校验。
            headers: Object.assign({'X-Pixiv-Collector': '1', Origin: window.location.origin}, options.headers || {}), data: options.data,
            onload: response => {
                if (response.finalUrl && new URL(response.finalUrl).origin !== base) { reject(new Error('REDIRECT_REJECTED')); return; }
                let body; try { body = JSON.parse(response.responseText); } catch (_) { body = {}; }
                resolve({status: response.status, body});
            }, onerror: () => reject(new Error('SERVER_UNAVAILABLE')),
            ontimeout: () => reject(new Error('SERVER_TIMEOUT')), onabort: () => reject(new Error('REQUEST_ABORTED'))
        }));
    }
    async function drain() {
        if (running || stopped || Date.now() < nextAttempt) return;
        running = true;
        try {
            const base = server();
            for (const key of keys()) {
                let work;
                try { work = JSON.parse(GM_getValue(key, '')); } catch (_) { lastError = 'INVALID_SAVED_WORK'; continue; }
                if (work.error || work.files.length !== work.metadata.pageCount) continue;
                const token = await request(base, '/api/external-download-import/token');
                if (token.status !== 200 || typeof token.body.token !== 'string') throw new Error(token.body.code || `HTTP_${token.status}`);
                const result = await request(base, '/api/external-download-import', {
                    headers: {'Content-Type':'application/json', 'X-Import-Token':token.body.token}, data: JSON.stringify(work)
                });
                if (result.status === 200 && ['IMPORTED', 'ALREADY_RECORDED'].includes(result.body.code)) {
                    GM_deleteValue(key); works.delete(key); lastError = ''; warned = false; backoff = 1000;
                } else if (result.status === 400 || result.status === 413) {
                    work.error = result.body.code || `HTTP_${result.status}`; save(key, work); lastError = work.error;
                } else throw new Error(result.body.code || `HTTP_${result.status}`);
            }
        } catch (error) { lastError = error.message; backoff = Math.min(backoff * 2, 60000); nextAttempt = Date.now() + backoff; }
        finally { running = false; }
    }
    async function tick() { await drain(); if (!stopped) setTimeout(tick, backoff); }
    window.addEventListener('addResult', captureMetadata);
    window.addEventListener('downloadSuccess', captureSuccess);
    window.addEventListener('crawlStart', () => { metadata.clear(); metadataBytes = 0; warned = false; });
    window.addEventListener('pagehide', () => { stopped = true; });
    GM_registerMenuCommand(messages[0], () => {
        const value = prompt(messages[2], GM_getValue('gallery-sync.server','http://localhost:6999'));
        if (value === null) return;
        const previous = GM_getValue('gallery-sync.server','http://localhost:6999');
        GM_setValue('gallery-sync.server',value.trim());
        try { server(); backoff = 1000; nextAttempt = 0; drain(); }
        catch (_) { GM_setValue('gallery-sync.server',previous); alert(messages[3]); }
    });
    GM_registerMenuCommand(messages[1], () => {
        let pending = 0, incomplete = 0, errors = 0;
        for (const key of keys()) {
            try { const work = JSON.parse(GM_getValue(key,''));
                if (work.error) errors++; else if (work.files.length === work.metadata.pageCount) pending++; else incomplete++;
            } catch (_) { errors++; }
        }
        alert(`${messages[4]}: ${pending} / ${incomplete} / ${errors}${lastError ? '\n' + lastError : ''}`);
        backoff = 1000; nextAttempt = 0; drain();
    });
    tick();
})();
