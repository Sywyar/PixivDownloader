// ==UserScript==
// @name         PixivBatchDownloader 导入支持
// @name:en      PixivBatchDownloader import support
// @name:zh-CN   PixivBatchDownloader 导入支持
// @name:zh-TW   PixivBatchDownloader 匯入支援
// @name:ja      PixivBatchDownloader インポートサポート
// @name:ko      PixivBatchDownloader 가져오기 지원
// @namespace    https://github.com/Sywyar/PixivDownloader
// @version      1.2.0
// @updateURL    https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/pixivdownload-plugin-pixiv-batch-downloader-import/src/main/resources/userscripts/pixiv-batch-downloader-import.user.js
// @downloadURL  https://raw.githubusercontent.com/Sywyar/PixivDownloader/master/pixivdownload-plugin-pixiv-batch-downloader-import/src/main/resources/userscripts/pixiv-batch-downloader-import.user.js
// @description Configure the original files folder, enable userscripts and refresh Pixiv. Automatically import new downloads or read this browser’s download history directly from the userscript menu, without exporting a file. Original files are retained.
// @description:zh-CN 配置原文件目录、启用用户脚本权限并刷新 Pixiv 后，可自动导入新下载，或从油猴菜单直接读取此浏览器的下载历史，无需导出文件。原文件保持不变。
// @description:zh-TW 設定原始檔案目錄、啟用使用者腳本權限並重新整理 Pixiv 後，可自動匯入新下載，或從腳本選單直接讀取此瀏覽器的下載歷史，無需匯出檔案。原始檔案保持不變。
// @description:ja 元ファイルのフォルダーを設定し、ユーザースクリプトの実行を許可して Pixiv を再読み込みしてください。新規ダウンロードの自動登録や、メニューからのブラウザー履歴の直接読み込みが可能です。履歴ファイルの出力は不要で、元ファイルは保持されます。
// @description:ko 원본 파일 폴더를 설정하고 유저스크립트 실행을 허용한 뒤 Pixiv를 새로 고치세요. 새 다운로드를 자동 등록하거나 메뉴에서 브라우저 기록을 직접 읽을 수 있습니다. 기록 파일을 내보낼 필요가 없으며 원본 파일은 유지됩니다.
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
    // @pixiv-presence-bootstrap
    const MAX_WORKS = 1000, MAX_FILES = 5000, MAX_BYTES = 8 * 1024 * 1024;
    const PREFIX = 'pixiv-batch-downloader-import.work.';
    const metadata = new Map();
    const works = new Map();
    const session = crypto.randomUUID();
    const DEFAULT_SERVER = installedServer();
    const locale = (navigator.language || 'en').toLowerCase();
    const language = locale.startsWith('zh') ? (/tw|hk|hant/.test(locale) ? 'zh-Hant' : 'zh')
        : locale.startsWith('ja') ? 'ja' : locale.startsWith('ko') ? 'ko' : 'en';
    const messages = {
        zh: ['设置本机服务地址', '自动导入状态', '本机服务地址（默认 {server}）', '只支持本机 localhost、127.0.0.1 或 [::1]。', '等待导入 / 未完成 / 需检查', '采集容量已满，请启动本机服务并检查自动导入状态。', '尚未捕获作品元数据，请刷新页面后重新抓取。'],
        'zh-Hant': ['設定本機服務位址', '自動匯入狀態', '本機服務位址（預設 {server}）', '僅支援本機 localhost、127.0.0.1 或 [::1]。', '等待匯入 / 未完成 / 需檢查', '採集容量已滿，請啟動本機服務並檢查自動匯入狀態。', '尚未取得作品中繼資料，請重新整理頁面後重新擷取。'],
        en: ['Set local server address', 'Automatic import status', 'Local server address (default {server})', 'Only localhost, 127.0.0.1 or [::1] is supported.', 'Pending / Incomplete / Needs attention', 'Collector storage is full. Start the local server and check import status.', 'Work metadata is missing. Refresh the page and crawl the work again.'],
        ja: ['ローカルサーバーの設定', '自動登録の状態', 'ローカルサーバー（既定 {server}）', 'localhost、127.0.0.1、[::1] のみ使用できます。', '登録待ち / 未完了 / 要確認', '収集容量が上限に達しました。ローカルサーバーを起動して状態を確認してください。', '作品情報がありません。ページを再読み込みして再取得してください。'],
        ko: ['로컬 서버 주소 설정', '자동 가져오기 상태', '로컬 서버 주소 (기본 {server})', 'localhost, 127.0.0.1 또는 [::1]만 지원합니다.', '대기 / 미완료 / 확인 필요', '수집 공간이 가득 찼습니다. 로컬 서버를 시작하고 상태를 확인하세요.', '작품 정보가 없습니다. 페이지를 새로 고친 후 다시 수집하세요.']
    }[language];
    let metadataBytes = 0;
    let taskBatch = 0, lastBatch = 0, paused = false;
    let active = null, timer = null, stopped = false, lastError = '', warned = false;
    let backoff = 1000, nextAttempt = 0;
    function server() {
        const url = new URL(GM_getValue('pixiv-batch-downloader-import.server', DEFAULT_SERVER));
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
            || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
            || url.pathname !== '/' || url.search || url.hash) throw new Error('INVALID_SERVER');
        return url.origin;
    }
    function installedServer() {
        const info = typeof GM_info === 'undefined' ? {} : GM_info;
        for (const address of [info.script?.downloadURL, info.script?.updateURL, info.scriptUpdateURL]) {
            try {
                const url = new URL(address);
                if (['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
                    && !url.search && !url.hash
                    && /^\/api\/scripts\/pixiv-batch-downloader-import(?:\.user\.js|\/install)$/.test(url.pathname)) {
                    return url.origin;
                }
            } catch (_) { /* 手动安装的脚本可能没有服务端安装地址。 */ }
        }
        return 'http://localhost:6999';
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
        const encoded = JSON.stringify(value);
        if (encoded.length > MAX_BYTES / 2) { warn(messages[5]); return; }
        const bytes = new TextEncoder().encode(encoded).length;
        const nextBytes = metadataBytes - (metadata.get(key)?.bytes || 0) + bytes;
        if (nextBytes > MAX_BYTES) { warn(messages[5]); return; }
        metadataBytes = nextBytes;
        metadata.set(key, {value, observedAt: Date.now(), bytes});
    }
    function captureSuccess(event) {
        const data = eventData(event);
        if (!data || data.noReply || !taskBatch
            || !Number.isSafeInteger(data.tabId) || typeof data.browserSetFilename !== 'string'
            || !data.browserSetFilename || data.browserSetFilename.length > 4096) return;
        const match = /^([1-9][0-9]*)(?:_p([0-9]+))?$/.exec(String(data.id));
        if (!match) return;
        const candidates = [...metadata.values()].filter(item => item.value.id === match[1]
            && item.observedAt <= taskBatch
            && (match[2] === undefined ? item.value.type >= 2 : item.value.type < 2));
        if (candidates.length !== 1) { warn(messages[6]); return; }
        const meta = candidates[0].value;
        const type = meta.type === 3 ? 'novel' : 'artwork';
        const key = PREFIX + `${session}:${data.tabId}:${taskBatch}:${type}:${meta.id}`;
        let work = works.get(key);
        if (!work) {
            if (works.size >= MAX_WORKS) { warn(messages[5]); return; }
            work = {schemaVersion: 1, source: 'pixiv-batch-downloader', taskBatch,
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
    function request(operation, base, path, options = {}) {
        return new Promise((resolve, reject) => {
            let handle, deadline;
            const pending = {cancel: () => {
                finish(new Error('REQUEST_ABORTED'));
                if (handle) { try { handle.abort(); } catch (_) { /* 已撤回归属 */ } }
            }};
            operation.request = pending;
            function finish(error, response) {
                if (operation.request !== pending) return;
                clearTimeout(deadline);
                operation.request = null;
                if (error) reject(error); else resolve(response);
            }
            try {
                // 匿名请求可能走 fetch；自行计时，避免依赖扩展的 XHR timeout 实现。
                deadline = setTimeout(() => {
                    finish(new Error('SERVER_TIMEOUT'));
                    try { handle?.abort(); } catch (_) { /* 已结算超时 */ }
                }, 10000);
                handle = GM_xmlhttpRequest({
                    method: options.method || (options.data ? 'POST' : 'GET'), url: base + path, anonymous: true, timeout: 10000, redirect: 'error',
                    // 匿名 GM 请求可能省略来源头，显式携带当前 Pixiv 来源供后端校验。
                    headers: Object.assign({'X-Pixiv-Collector': '1', Origin: window.location.origin}, options.headers || {}), data: options.data,
                    onload: response => {
                        try {
                            if (response.finalUrl && new URL(response.finalUrl).origin !== base) throw new Error('REDIRECT_REJECTED');
                            let body; try { body = JSON.parse(response.responseText); } catch (_) { body = {}; }
                            finish(null, {status: response.status, body});
                        } catch (error) { finish(error); }
                    }, onerror: () => finish(new Error('SERVER_UNAVAILABLE')),
                    ontimeout: () => finish(new Error('SERVER_TIMEOUT')), onabort: () => finish(new Error('REQUEST_ABORTED'))
                });
            } catch (error) { finish(error); }
        });
    }
    function schedule() {
        clearTimeout(timer);
        timer = stopped ? null : setTimeout(() => {
            timer = null;
            drain();
        }, Math.max(backoff, nextAttempt - Date.now()));
    }
    async function drain() {
        if (active || stopped) return;
        if (Date.now() < nextAttempt) { schedule(); return; }
        clearTimeout(timer); timer = null;
        const operation = {request: null};
        active = operation;
        try {
            const base = server();
            for (const key of keys()) {
                const encoded = GM_getValue(key, '');
                let work;
                try { work = JSON.parse(encoded); } catch (_) { lastError = 'INVALID_SAVED_WORK'; continue; }
                if (work.error || work.files.length !== work.metadata.pageCount) continue;
                const token = await request(operation, base, '/api/pixiv-batch-downloader-import/token');
                if (active !== operation) return;
                if (GM_getValue(key, '') !== encoded) continue;
                if (token.status !== 200 || typeof token.body.token !== 'string') throw new Error(token.body.code || `HTTP_${token.status}`);
                const result = await request(operation, base, '/api/pixiv-batch-downloader-import', {
                    headers: {'Content-Type':'application/json', 'X-Import-Token':token.body.token}, data: encoded
                });
                if (active !== operation) return;
                if (GM_getValue(key, '') !== encoded) continue;
                if (result.status === 200 && ['IMPORTED', 'ALREADY_RECORDED'].includes(result.body.code)) {
                    GM_deleteValue(key); works.delete(key); lastError = ''; warned = false; backoff = 1000;
                } else if (result.status === 400 || result.status === 413) {
                    work.error = result.body.code || `HTTP_${result.status}`; save(key, work); lastError = work.error;
                } else throw new Error(result.body.code || `HTTP_${result.status}`);
            }
        } catch (error) {
            if (active === operation) {
                lastError = error.message; backoff = Math.min(backoff * 2, 60000); nextAttempt = Date.now() + backoff;
            }
        } finally {
            if (active === operation) { active = null; schedule(); }
        }
    }
    const historyText = {
    "zh": [
        "读取浏览器下载历史",
        "读取此浏览器中的 PixivBatchDownloader 下载历史，检查后导入本地画廊，无需导出文件。",
        "读取并检查",
        "开始导入",
        "取消导入",
        "关闭",
        "历史记录超过单次 1 GiB 上限。请使用桌面历史导入工具分批导入。",
        "正在读取浏览器历史",
        "正在检查本地文件",
        "记录 / 作品 / 待导入 / 已存在 / 缺失或冲突",
        "已处理 / 总计 / 已导入 / 已跳过 / 失败",
        "等待开始",
        "正在导入",
        "正在取消",
        "处理结束",
        "已取消，已登记的作品保留。",
        "导入未完成",
        "通信中断，结果尚未确认。已登记的作品保留；请检查本机服务后重新读取历史。",
        "请登录 Pixiv 后重试；元数据请求失败。",
        "此浏览器没有可读取的下载历史库。请在使用 PixivBatchDownloader 的浏览器和用户配置中打开原来的 Pixiv 网站。",
        "无法读取下载历史。请关闭此窗口，确认外部下载已暂停、数据库未被其他页面升级后重试。",
        "下载历史格式无法识别，已停止读取；原记录未修改。",
        "检查完成，没有可导入的作品。",
        "请查看下方明细，核对插件设置中的原文件目录与历史记录的相对路径、文件名及页记录，然后重新读取并检查。",
        "可导入",
        "已登记",
        "原文件不可用或页记录不完整",
        "路径或记录冲突",
        "作品明细（显示 {shown} / {total} 部）",
        "作品处理进度",
        "数据与隐私",
        "已导入",
        "已跳过",
        "失败",
        "等待导入",
        "未处理",
        "结果待确认",
        "已处理 {done} / {total} 部作品",
        "历史记录",
        "作品",
        "缺失或冲突",
        "导入完成",
        "其他问题",
        "部分作品未完成导入或需要处理，请展开明细查看原因。",
        "作品",
        "小说",
        "读取期间请暂停下载，并保持此页打开。",
        "请保持此页打开，勿移动原文件或更改下载根目录。",
        "导入期间请保持此页打开，勿移动原文件。",
        "仅使用当前浏览器的 Pixiv 登录状态，Cookie 不会发送到本机软件。原文件保持不变。",
        "正在结束当前操作，请保持此页打开。"
    ],
    "zh-Hant": [
        "讀取瀏覽器下載歷史",
        "讀取此瀏覽器中的 PixivBatchDownloader 下載歷史，檢查後匯入本機圖庫，無需匯出檔案。",
        "讀取並檢查",
        "開始匯入",
        "取消匯入",
        "關閉",
        "歷史記錄超過單次 1 GiB 上限。請使用桌面歷史匯入工具分批匯入。",
        "正在讀取瀏覽器歷史",
        "正在檢查本機檔案",
        "記錄 / 作品 / 待匯入 / 已存在 / 缺失或衝突",
        "已處理 / 總計 / 已匯入 / 已略過 / 失敗",
        "等待開始",
        "正在匯入",
        "正在取消",
        "處理結束",
        "已取消，已登記的作品會保留。",
        "匯入未完成",
        "通訊中斷，結果尚未確認。已登記的作品會保留；請檢查本機服務後重新讀取歷史。",
        "請登入 Pixiv 後重試；中繼資料請求失敗。",
        "此瀏覽器沒有可讀取的下載歷史資料庫。請在使用 PixivBatchDownloader 的瀏覽器和使用者設定檔中開啟原來的 Pixiv 網站。",
        "無法讀取下載歷史。請關閉此視窗，確認外部下載已暫停、資料庫未被其他頁面升級後重試。",
        "無法辨識下載歷史格式，已停止讀取；原始記錄未修改。",
        "檢查完成，沒有可匯入的作品。",
        "請查看下方明細，核對外掛設定中的原始檔案目錄與歷史記錄的相對路徑、檔名及頁面記錄，然後重新讀取並檢查。",
        "可匯入",
        "已登記",
        "原始檔案無法使用或頁面記錄不完整",
        "路徑或記錄衝突",
        "作品明細（顯示 {shown} / {total} 部）",
        "作品處理進度",
        "資料與隱私",
        "已匯入",
        "已略過",
        "失敗",
        "等待匯入",
        "未處理",
        "結果待確認",
        "已處理 {done} / {total} 部作品",
        "歷史記錄",
        "作品",
        "缺失或衝突",
        "匯入完成",
        "其他問題",
        "部分作品未完成匯入或需要處理，請展開明細查看原因。",
        "作品",
        "小說",
        "讀取期間請暫停下載，並保持此頁開啟。",
        "請保持此頁開啟，勿移動原始檔案或變更下載根目錄。",
        "匯入期間請保持此頁開啟，勿移動原始檔案。",
        "僅使用目前瀏覽器的 Pixiv 登入狀態，Cookie 不會傳送到本機軟體。原始檔案保持不變。",
        "正在結束目前的操作，請保持此頁開啟。"
    ],
    "en": [
        "Read browser download history",
        "Read PixivBatchDownloader history from this browser, check it and import works into the local gallery. No file export is needed.",
        "Read and check",
        "Start import",
        "Cancel import",
        "Close",
        "History exceeds the 1 GiB limit per session. Use the desktop history import tool to import it in batches.",
        "Reading browser history",
        "Checking local files",
        "Records / Works / Candidates / Existing / Missing or conflicting",
        "Processed / Total / Imported / Skipped / Failed",
        "Ready to start",
        "Importing",
        "Cancelling",
        "Processing finished",
        "Cancelled. Registered works are retained.",
        "Import incomplete",
        "Connection interrupted; the result is unconfirmed. Registered works are retained. Check the local server, then read the history again.",
        "Metadata request failed. Sign in to Pixiv and try again.",
        "No download history database was found. Open the original Pixiv site in the browser and profile you use for PixivBatchDownloader.",
        "Cannot read download history. Close this dialog, pause external downloads and wait for any database upgrade in other tabs, then try again.",
        "Unrecognized download history format. Reading stopped; the original records were not changed.",
        "Check complete. No works are available to import.",
        "Review the details below. Check the original files folder in the plugin settings against the relative paths, filenames and page records in the history, then read and check again.",
        "Ready to import",
        "Already registered",
        "Original files unavailable or pages incomplete",
        "Path or record conflict",
        "Work details (showing {shown} / {total})",
        "Work processing progress",
        "Data and privacy",
        "Imported",
        "Skipped",
        "Failed",
        "Waiting to import",
        "Not processed",
        "Result unconfirmed",
        "Processed {done} / {total} works",
        "History records",
        "Works",
        "Missing or conflicting",
        "Import complete",
        "Other issues",
        "Some works could not be imported or need attention. Expand the details to see why.",
        "Artwork",
        "Novel",
        "Pause downloads and keep this page open while reading.",
        "Keep this page open. Do not move the original files or change the download root folder.",
        "Keep this page open during import. Do not move the original files.",
        "Your current Pixiv sign-in is used only in this browser. Cookies are not sent to the local app. Original files remain unchanged.",
        "Finishing the current operation. Keep this page open."
    ],
    "ja": [
        "ブラウザーのダウンロード履歴を読み込む",
        "このブラウザーの PixivBatchDownloader の履歴を読み込み、確認後にローカルギャラリーへインポートします。ファイルの書き出しは不要です。",
        "読み込んで確認",
        "インポート開始",
        "インポートをキャンセル",
        "閉じる",
        "履歴が1回あたりの上限 1 GiB を超えています。デスクトップの履歴インポートツールで分けて取り込んでください。",
        "ブラウザーの履歴を読み込み中",
        "ローカルファイルを確認中",
        "記録 / 作品 / 対象 / 登録済み / 不足または競合",
        "処理済み / 合計 / 登録 / スキップ / 失敗",
        "開始待ち",
        "インポート中",
        "キャンセル中",
        "処理終了",
        "キャンセルしました。登録済みの作品は保持されます。",
        "インポート未完了",
        "通信が途切れ、結果を確認できません。登録済みの作品は保持されます。ローカルサーバーを確認し、履歴を読み直してください。",
        "作品情報の取得に失敗しました。Pixiv にログインして再試行してください。",
        "履歴データベースが見つかりません。PixivBatchDownloader を使用しているブラウザーとプロファイルで、元の Pixiv サイトを開いてください。",
        "履歴を読み込めません。この画面を閉じ、外部ダウンロードを一時停止して、他のタブでのデータベース更新が終わってから再試行してください。",
        "履歴の形式を認識できないため読み込みを停止しました。元の記録は変更されていません。",
        "確認が完了しました。インポートできる作品はありません。",
        "下の詳細を確認してください。プラグイン設定の元ファイルのフォルダーと履歴の相対パス、ファイル名、ページ記録を照合し、もう一度読み込んで確認してください。",
        "インポート可能",
        "登録済み",
        "元ファイルを利用できないか、ページ記録が不完全",
        "パスまたは記録の競合",
        "作品の詳細（{total} 作品中 {shown} 作品を表示）",
        "作品の処理状況",
        "データとプライバシー",
        "インポート済み",
        "スキップ済み",
        "失敗",
        "インポート待ち",
        "未処理",
        "結果未確認",
        "{total} 作品中 {done} 作品を処理済み",
        "履歴の記録",
        "作品",
        "不足または競合",
        "インポート完了",
        "その他の問題",
        "インポートできなかった作品や確認が必要な作品があります。詳細を開いて原因を確認してください。",
        "作品",
        "小説",
        "読み込み中はダウンロードを一時停止し、このページを開いたままにしてください。",
        "このページを開いたまま、元ファイルの移動やダウンロードのルートフォルダーの変更はしないでください。",
        "インポート中はこのページを開いたままにし、元ファイルを移動しないでください。",
        "現在の Pixiv ログイン状態はブラウザー内でのみ使用します。Cookie はローカルアプリに送信しません。元ファイルは変更されません。",
        "現在の操作を終了しています。このページを開いたままお待ちください。"
    ],
    "ko": [
        "브라우저 다운로드 기록 읽기",
        "이 브라우저의 PixivBatchDownloader 기록을 읽고 확인한 뒤 로컬 갤러리로 가져옵니다. 파일을 내보낼 필요가 없습니다.",
        "읽고 확인",
        "가져오기 시작",
        "가져오기 취소",
        "닫기",
        "기록이 세션당 1 GiB 한도를 초과했습니다. 데스크톱 기록 가져오기 도구에서 나누어 가져오세요.",
        "브라우저 기록 읽는 중",
        "로컬 파일 확인 중",
        "기록 / 작품 / 대상 / 이미 존재 / 누락 또는 충돌",
        "처리 / 전체 / 가져옴 / 건너뜀 / 실패",
        "시작 대기",
        "가져오는 중",
        "취소 중",
        "처리 종료",
        "취소되었습니다. 등록된 작품은 유지됩니다.",
        "가져오기 미완료",
        "연결이 끊겨 결과를 확인할 수 없습니다. 등록된 작품은 유지됩니다. 로컬 서버를 확인한 뒤 기록을 다시 읽으세요.",
        "작품 정보 요청에 실패했습니다. Pixiv에 로그인한 뒤 다시 시도하세요.",
        "다운로드 기록 데이터베이스를 찾지 못했습니다. PixivBatchDownloader를 사용하던 브라우저와 프로필에서 원래 Pixiv 사이트를 여세요.",
        "기록을 읽을 수 없습니다. 이 창을 닫고 외부 다운로드를 일시 중지한 다음 다른 탭의 데이터베이스 업데이트가 끝나면 다시 시도하세요.",
        "기록 형식을 인식하지 못해 읽기를 중지했습니다. 원래 기록은 변경되지 않았습니다.",
        "확인이 완료되었습니다. 가져올 수 있는 작품이 없습니다.",
        "아래 세부 내용을 확인하세요. 플러그인 설정의 원본 파일 폴더와 기록의 상대 경로, 파일 이름, 페이지 기록을 대조한 뒤 다시 읽고 확인하세요.",
        "가져오기 가능",
        "이미 등록됨",
        "원본 파일을 사용할 수 없거나 페이지 기록이 불완전함",
        "경로 또는 기록 충돌",
        "작품 세부 내용 (작품 {total}개 중 {shown}개 표시)",
        "작품 처리 진행률",
        "데이터 및 개인정보",
        "가져옴",
        "건너뜀",
        "실패",
        "가져오기 대기",
        "미처리",
        "결과 미확인",
        "작품 {total}개 중 {done}개 처리",
        "기록",
        "작품",
        "누락 또는 충돌",
        "가져오기 완료",
        "기타 문제",
        "일부 작품을 가져오지 못했거나 확인이 필요합니다. 세부 내용을 펼쳐 원인을 확인하세요.",
        "작품",
        "소설",
        "기록을 읽는 동안 다운로드를 일시 중지하고 이 페이지를 열어 두세요.",
        "이 페이지를 열어 두세요. 원본 파일을 이동하거나 다운로드 루트 폴더를 변경하지 마세요.",
        "가져오는 동안 이 페이지를 열어 두고 원본 파일을 이동하지 마세요.",
        "현재 Pixiv 로그인 상태는 브라우저 안에서만 사용합니다. 쿠키는 로컬 앱으로 보내지 않으며 원본 파일도 변경하지 않습니다.",
        "현재 작업을 마무리하고 있습니다. 이 페이지를 열어 두세요."
    ]
}[language];
    let historyWindow = null;
    const historyTerminal = phase => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(phase);
    function openHistoryDatabase(operation) {
        return new Promise((resolve, reject) => {
            let settled = false;
            const request = indexedDB.open('DLRecord');
            const finish = (error, db) => {
                if (settled) { db?.close(); return; }
                settled = true; clearTimeout(timer); operation.abortRead = null;
                if (error) { db?.close(); reject(Object.assign(error, {rejected: true})); }
                else resolve(db);
            };
            const timer = setTimeout(() => finish(new Error(historyText[20])), 10000);
            operation.abortRead = () => finish(new Error('REQUEST_ABORTED'));
            // 不传版本号；不存在时中止创建，绝不升级或初始化上游数据库。
            request.onupgradeneeded = () => { request.transaction.abort(); finish(new Error(historyText[19])); };
            request.onerror = () => finish(new Error(historyText[20]));
            request.onblocked = () => finish(new Error(historyText[20]));
            request.onsuccess = () => {
                const db = request.result;
                if (settled) { db.close(); return; }
                if (!Array.from({length: 9}, (_, index) => 'record' + (index + 1))
                    .every(name => db.objectStoreNames.contains(name))) { finish(new Error(historyText[21]), db); return; }
                db.onversionchange = () => {
                    operation.readError = Object.assign(new Error(historyText[20]), {rejected: true});
                    operation.abortRead?.(); db.close();
                };
                finish(null, db);
            };
        });
    }
    function readHistoryBatch(operation, db, store, after) {
        return new Promise((resolve, reject) => {
            const transaction = db.transaction(store, 'readonly'), rows = [];
            let bytes = 0, last = after, ended = false, failure;
            const fail = error => { failure = error; transaction.abort(); };
            const timer = setTimeout(() => fail(new Error(historyText[20])), 10000);
            operation.abortRead = () => fail(new Error('REQUEST_ABORTED'));
            const finish = () => { clearTimeout(timer); operation.abortRead = null; };
            transaction.onabort = () => { finish(); reject(Object.assign(failure || new Error(historyText[20]), {rejected: true})); };
            transaction.oncomplete = () => { finish(); resolve({rows, bytes, last, ended}); };
            const records = transaction.objectStore(store);
            if (records.keyPath !== 'id') { fail(new Error(historyText[21])); return; }
            const request = records.openCursor(after === undefined ? null : IDBKeyRange.lowerBound(after, true));
            const encoder = new TextEncoder();
            request.onsuccess = () => {
                const cursor = request.result;
                if (!cursor) { ended = true; return; }
                const record = cursor.value;
                if (!record || typeof record !== 'object' || Array.isArray(record)
                    || typeof record.id !== 'string' || cursor.key !== record.id || typeof record.n !== 'string'
                    || Object.keys(record).some(key => !['id', 'n', 'd'].includes(key))
                    || ['id', 'n', 'd'].some(key => record[key] !== undefined
                        && (typeof record[key] !== 'string' || record[key].length > 4096))) {
                    fail(new Error(historyText[21])); return;
                }
                const row = JSON.stringify(record), size = encoder.encode(row).length + 1;
                if (rows.length && bytes + size > 512 * 1024) return;
                rows.push(row); bytes += size; last = cursor.key;
                if (rows.length < 1000) cursor.continue();
            };
        });
    }
    async function uploadBrowserHistory(operation, progress) {
        const db = await openHistoryDatabase(operation);
        let file = 0, offset = 0, sent = 0, count = 0;
        const upload = async text => {
            if (operation.readError) throw operation.readError;
            const chunk = new Blob([text]);
            if (sent + chunk.size > 1024 * 1024 * 1024) throw Object.assign(new Error(historyText[6]), {rejected: true});
            await historyCall(operation, 'upload', chunk, {file: String(file), offset: String(offset)});
            offset += chunk.size; sent += chunk.size;
        };
        try {
            if (operation.cancelled) throw new Error('REQUEST_ABORTED');
            operation.creating = true;
            operation.session = (await historyCall(operation, 'create')).session;
            operation.creating = false;
            for (let store = 1; store <= 9; store++) {
                let after;
                while (true) {
                    if (operation.cancelled) throw new Error('REQUEST_ABORTED');
                    if (operation.readError) throw operation.readError;
                    const batch = await readHistoryBatch(operation, db, 'record' + store, after);
                    if (batch.rows.length) {
                        if (offset + batch.bytes + 2 > 512 * 1024 * 1024) {
                            await upload(']'); file++; offset = 0;
                        }
                        await upload((offset ? ',' : '[') + batch.rows.join(','));
                        count += batch.rows.length;
                        progress.textContent = `${historyText[7]}: ${count} (${Math.floor(sent / 1024)} KiB)`;
                    }
                    after = batch.last;
                    if (batch.ended) break;
                }
            }
            await upload(offset ? ']' : '[]');
        } finally { db.close(); }
    }
    async function historyCall(operation, action, data = '{}', parameters = {}) {
        if (operation.cancelled) throw new Error('REQUEST_ABORTED');
        const token = await request(operation, operation.base, '/api/pixiv-batch-downloader-import/token');
        if (operation.cancelled) throw new Error('REQUEST_ABORTED');
        if (token.status !== 200 || typeof token.body.token !== 'string')
            throw Object.assign(new Error(token.body.error || `HTTP_${token.status}`), {rejected: true});
        const query = new URLSearchParams({action, ...parameters});
        const result = await request(operation, operation.base, `/api/pixiv-batch-downloader-import/history?${query}`, {
            method: 'POST', data, headers: {'Content-Type': typeof data === 'string' ? 'application/json' : 'application/octet-stream',
                'Accept-Language': navigator.language || 'en', 'X-Import-Token': token.body.token,
                'X-History-Session': operation.session || ''}
        });
        if (operation.cancelled) throw new Error('REQUEST_ABORTED');
        if (result.status !== 200) throw Object.assign(new Error(result.body.error || `HTTP_${result.status}`), {rejected: true});
        return result.body;
    }
    async function historyMetadata(operation, metadata) {
        if (!['illust', 'novel'].includes(metadata.kind) || !/^[1-9][0-9]{0,15}$/.test(metadata.workId))
            throw new Error(historyText[18]);
        const abort = new AbortController();
        operation.fetch = abort;
        const timeout = setTimeout(() => abort.abort(), 60000);
        let status = 0, data = new Blob([]);
        try {
            const response = await fetch(`/ajax/${metadata.kind}/${metadata.workId}`, {
                credentials: 'same-origin', redirect: 'error', signal: abort.signal, headers: {Accept: 'application/json'}
            });
            status = response.status;
            if (status === 200) {
                const limit = 4 * 1024 * 1024;
                if (Number(response.headers.get('Content-Length')) > limit) throw new Error(historyText[18]);
                const reader = response.body.getReader(), chunks = [];
                let size = 0;
                try {
                    while (true) {
                        const {value, done} = await reader.read();
                        if (done) break;
                        size += value.byteLength;
                        if (size > limit) throw new Error(historyText[18]);
                        chunks.push(value);
                    }
                    data = new Blob(chunks);
                } finally { await reader.cancel(); reader.releaseLock(); }
            } else if (response.body) await response.body.cancel();
        } catch (_) { status = 0; }
        finally { clearTimeout(timeout); operation.fetch = null; }
        if (operation.cancelled) throw new Error('REQUEST_ABORTED');
        return historyCall(operation, 'reply', data, {request: metadata.id, status: String(status)});
    }
    function historyWait(operation) {
        return new Promise(resolve => {
            const timer = setTimeout(() => { operation.wake = null; resolve(); }, 1000);
            operation.wake = () => { clearTimeout(timer); operation.wake = null; resolve(); };
        });
    }
    async function cancelHistory() {
        const current = historyWindow, operation = current?.operation;
        if (!operation || operation.cancelled || operation.done) return;
        operation.cancelled = true;
        operation.request?.cancel(); operation.fetch?.abort(); operation.wake?.(); operation.abortRead?.();
        current.start.disabled = true; current.cancel.disabled = true;
        current.progress.textContent = historyText[13];
        current.guidance('CANCELLING');
        try {
            if (!operation.session) {
                if (operation.creating) throw new Error(historyText[17]);
                current.progress.textContent = historyText[15];
                current.guidance('CANCELLED');
                return true;
            }
            const cancellation = {base: operation.base, session: operation.session};
            let view = await historyCall(cancellation, 'cancel');
            while (!historyTerminal(view.phase) && historyWindow === current && !stopped) {
                await historyWait(cancellation);
                view = await historyCall(cancellation, 'status');
            }
            if (historyWindow === current) current.render(view);
            return historyTerminal(view.phase);
        } catch (_) {
            if (historyWindow === current) { current.progress.textContent = historyText[17]; current.guidance('FAILED'); }
            return false;
        }
        finally {
            operation.done = true;
            if (historyWindow === current) { current.cancel.disabled = false; current.cancel.textContent = historyText[5]; }
        }
    }
    function openHistory() {
        if (historyWindow) { historyWindow.dialog.focus(); return; }
        const host = document.createElement('div'), shadow = host.attachShadow({mode: 'closed'});
        const style = document.createElement('style');
        style.textContent = `
            dialog{--line:color-mix(in srgb,CanvasText 18%,Canvas);--muted:color-mix(in srgb,CanvasText 72%,Canvas);
                --success:light-dark(#216e39,#85dd9f);--warning:light-dark(#9c4321,#ffbd95);--caution:light-dark(#755800,#ffdf80);
                font:1rem/1.5 system-ui,sans-serif;color:CanvasText;background:Canvas;border:1px solid var(--line);
                border-radius:1rem;padding:1.5rem;width:40rem;max-width:calc(100vw - 2rem);max-height:calc(100dvh - 2rem);box-sizing:border-box}
            dialog[open]{display:flex;flex-direction:column;gap:1rem}dialog::backdrop{background:#0007}
            [hidden]{display:none!important}h2,footer,.status{flex-shrink:0}
            h2{font-size:1.25rem;line-height:1.3;letter-spacing:-.02em;margin:0}
            p{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}
            .status{display:flex;align-items:center;gap:1rem}.status p{min-width:0;font-weight:600}
            .ring,.badge{flex:0 0 4rem;width:4rem;height:4rem;display:grid;place-items:center;border-radius:50%}
            .ring span{width:3.4rem;height:3.4rem;display:grid;place-items:center;border-radius:50%;background:Canvas;font-weight:600;font-variant-numeric:tabular-nums}
            .badge{font-size:2rem;border:2px solid currentColor;box-sizing:border-box}
            .success{color:var(--success)}.warning{color:var(--warning)}
            .body{min-height:0;overflow:auto;overscroll-behavior:contain;scrollbar-gutter:stable}
            .metrics{display:flex;flex-wrap:wrap;gap:.75rem 1.5rem;margin:0 0 1rem}
            .metrics div{min-width:5rem}.metrics dt{color:var(--muted);font-size:.85rem}.metrics dd{margin:0;font-size:1.25rem;font-weight:600;font-variant-numeric:tabular-nums}
            details{border-top:1px solid var(--line)}summary{cursor:pointer;padding:.75rem .2rem;font-weight:600;overflow-wrap:anywhere}
            details p{color:var(--muted);padding:0 .2rem 1rem}.notice,.intro,.caution{margin-bottom:1rem}
            .notice strong{display:block;color:var(--caution);margin-bottom:.5rem}
            .intro{color:var(--muted)}.caution{color:var(--caution);border-left:3px solid currentColor;padding-left:.75rem;font-weight:600}
            ul{list-style:none;padding:0;margin:0}li{display:flex;justify-content:space-between;align-items:baseline;gap:.4rem 1rem;flex-wrap:wrap;padding:.65rem .2rem;border-top:1px solid var(--line);font-size:.9rem}
            .work{font-variant-numeric:tabular-nums}.result{color:var(--muted)}.result[data-state="IMPORTED"]{color:var(--success)}
            .result[data-state="FAILED"]{color:var(--warning)}.reason{flex-basis:100%;color:var(--warning)}
            footer{display:flex;justify-content:flex-end;gap:.65rem;flex-wrap:wrap}
            button{font:inherit;font-weight:600;min-height:max(44px,2.75rem);padding:.5rem 1rem;border:1px solid var(--line);border-radius:.6rem;background:Canvas;color:CanvasText;cursor:pointer}
            button.primary{background:Highlight;color:HighlightText;border-color:Highlight}
            button:disabled{opacity:.5;cursor:default}button:not(:disabled):hover{filter:brightness(.95)}
            button:not(:disabled):active{opacity:.75}button:focus-visible,summary:focus-visible{outline:2px solid Highlight;outline-offset:-2px}
            @media(max-height:32rem){dialog{padding:1rem;gap:.65rem}.ring,.badge{flex-basis:3rem;width:3rem;height:3rem}.ring span{width:2.5rem;height:2.5rem;font-size:.8rem}}
            @media(max-width:30rem){dialog{padding:1rem}footer button{flex:1;padding-inline:.6rem}}
            @media(forced-colors:active){.ring,.badge{outline:2px solid CanvasText}.success,.warning,.reason,.result,.caution,.notice strong{color:CanvasText}}
        `;
        shadow.append(style);
        const dialog = document.createElement('dialog'); shadow.append(dialog); document.body.append(host);
        const add = (parent, tag, text) => { const node = document.createElement(tag); if (text) node.textContent = text; parent.append(node); return node; };
        const heading = add(dialog, 'h2', historyText[0]); heading.id = 'history-title'; dialog.setAttribute('aria-labelledby', heading.id);
        const status = add(dialog, 'div'); status.className = 'status';
        const ring = add(status, 'div'); ring.className = 'ring'; ring.hidden = true;
        ring.setAttribute('role', 'progressbar'); ring.setAttribute('aria-label', historyText[29]);
        ring.setAttribute('aria-valuemin', '0'); ring.setAttribute('aria-valuemax', '100');
        const percentage = add(ring, 'span'); percentage.setAttribute('aria-hidden', 'true');
        const badge = add(status, 'div'); badge.className = 'badge'; badge.hidden = true; badge.setAttribute('aria-hidden', 'true');
        const progress = add(status, 'p', historyText[11]); progress.setAttribute('role', 'status'); progress.setAttribute('aria-live', 'polite');
        const body = add(dialog, 'div'); body.className = 'body';
        const metrics = add(body, 'dl'); metrics.className = 'metrics'; metrics.hidden = true;
        const counts = Array.from({length: 5}, () => { const group = add(metrics, 'div'); return {group, label: add(group, 'dt'), value: add(group, 'dd')}; });
        const notice = add(body, 'div'); notice.className = 'notice'; notice.hidden = true; notice.setAttribute('aria-live', 'polite');
        const noticeReason = add(notice, 'strong'), noticeAction = add(notice, 'p');
        const showNotice = message => {
            const [reason, ...actions] = (message || '').split('\n');
            noticeReason.textContent = reason; noticeAction.textContent = actions.join('\n');
            noticeAction.hidden = !noticeAction.textContent; notice.hidden = !message;
        };
        const intro = add(body, 'p', historyText[1]); intro.className = 'intro';
        const caution = add(body, 'p'); caution.className = 'caution'; caution.setAttribute('aria-live', 'polite');
        const help = add(body, 'details'); add(help, 'summary', historyText[30]); add(help, 'p', historyText[49]);
        const guidance = phase => {
            intro.hidden = help.hidden = phase !== 'IDLE';
            const key = {IDLE: 46, UPLOADING: 46, INSPECTING: 47, READY: 47, RUNNING: 48, CANCELLING: 50}[phase];
            caution.hidden = key === undefined; caution.textContent = key === undefined ? '' : historyText[key];
        };
        guidance('IDLE');
        const details = add(body, 'details'); details.hidden = true;
        const summary = add(details, 'summary');
        const list = add(details, 'ul'), rows = new Map();
        const problems = add(body, 'details'); problems.hidden = true; add(problems, 'summary', historyText[42]);
        const problemText = add(problems, 'p');
        const footer = add(dialog, 'footer');
        const check = add(footer, 'button', historyText[2]), start = add(footer, 'button', historyText[3]), cancel = add(footer, 'button', historyText[5]);
        start.disabled = true;
        check.className = 'primary';
        const current = {dialog, start, cancel, progress, guidance, operation: null, render(view) {
            const phases = {UPLOADING: 7, INSPECTING: 8, READY: 11, RUNNING: 12, CANCELLING: 13, COMPLETED: 14, CANCELLED: 15, FAILED: 16};
            const p = view.preview;
            const empty = view.phase === 'READY' && p?.candidates === 0;
            guidance(empty ? 'DONE' : view.phase);
            const terminal = historyTerminal(view.phase), executing = view.phase !== 'READY' && (view.total > 0 && view.phase !== 'INSPECTING');
            ring.hidden = !['RUNNING', 'CANCELLING'].includes(view.phase)
                || !Number.isFinite(view.total) || view.total <= 0 || !Number.isFinite(view.processed);
            if (!ring.hidden) {
                const percent = Math.floor(100 * Math.min(Math.max(view.processed, 0), view.total) / view.total);
                percentage.textContent = `${percent}%`;
                ring.style.background = `conic-gradient(Highlight ${percent}%, GrayText 0)`;
                ring.setAttribute('aria-valuenow', String(percent));
                ring.setAttribute('aria-valuetext', `${view.processed} / ${view.total}`);
            }
            const successful = view.phase === 'COMPLETED' && !view.failed && !view.error && !view.problems?.length;
            badge.hidden = !terminal;
            badge.className = 'badge ' + (successful ? 'success' : 'warning');
            badge.textContent = successful ? '✓' : view.phase === 'CANCELLED' ? '−' : '!';
            progress.textContent = empty ? historyText[22]
                : successful ? historyText[41] : historyText[phases[view.phase] ?? 16];
            if (executing) progress.textContent += '\n' + historyText[37].replace('{done}', view.processed).replace('{total}', view.total);
            const metricLabels = executing ? [31, 32, 33] : [38, 39, 24, 25, 40];
            const values = executing ? [view.imported, view.skipped, view.failed] : p ? [p.records, p.works, p.candidates, p.skipped, p.unavailable] : [];
            metrics.hidden = !values.length;
            counts.forEach((count, index) => {
                count.group.hidden = index >= values.length;
                if (!count.group.hidden) { count.label.textContent = historyText[metricLabels[index]]; count.value.textContent = String(values[index]); }
            });
            showNotice(view.error || (empty && p.unavailable ? historyText[23] : view.failed || view.problems?.length ? historyText[43] : ''));
            const dispositions = {CANDIDATE: 24, ALREADY_PRESENT: 25, MISSING_FILES: 26, CONFLICT: 27};
            const states = {WAITING: terminal ? 35 : 34, RUNNING: 12, IMPORTED: 31, SKIPPED: 32, FAILED: 33};
            const results = new Map((view.results || []).map(item => [item.workKey, item]));
            details.hidden = !p?.samples?.length;
            summary.textContent = historyText[28].replace('{shown}', p?.samples?.length || 0).replace('{total}', p?.works || 0);
            for (const item of p?.samples || []) {
                let row = rows.get(item.workKey);
                if (!row) {
                    const node = add(list, 'li'); node.setAttribute('data-work', item.workKey);
                    const name = add(node, 'span', historyText[item.workKey.startsWith('novel:') ? 45 : 44] + ' ' + item.workKey.split(':')[1]); name.className = 'work';
                    const result = add(node, 'span'); result.className = 'result';
                    const reason = add(node, 'p'); reason.className = 'reason';
                    row = {node, result, reason}; rows.set(item.workKey, row);
                }
                const result = results.get(item.workKey);
                row.result.textContent = result ? historyText[states[result.state] ?? 36]
                    : historyText[executing && item.disposition === 'CANDIDATE' ? 36 : dispositions[item.disposition] ?? 36];
                row.result.setAttribute('data-state', result?.state || item.disposition);
                row.reason.textContent = result?.error || ''; row.reason.hidden = !row.reason.textContent;
            }
            const otherProblems = (view.problems || []).filter(item => !results.get(item.work)?.error);
            problems.hidden = !otherProblems.length;
            problemText.textContent = otherProblems.map(item => [item.work, item.error].filter(Boolean).join(': ')).join('\n');
            start.disabled = view.phase !== 'READY' || !p?.candidates || current.operation.startRequested;
            check.hidden = executing; start.hidden = executing; cancel.className = terminal ? 'primary' : '';
            start.className = !start.disabled ? 'primary' : ''; check.className = !check.disabled ? 'primary' : '';
        }};
        historyWindow = current;
        const close = () => {
            if (current.operation && !current.operation.done) { void cancelHistory(); return; }
            dialog.close(); host.remove(); if (historyWindow === current) historyWindow = null;
        };
        cancel.addEventListener('click', close);
        dialog.addEventListener('cancel', event => { event.preventDefault(); close(); });
        start.addEventListener('click', () => {
            start.disabled = true; current.operation.startRequested = true; current.operation.wake?.();
        });
        check.addEventListener('click', async () => {
            const operation = {base: '', session: '', request: null, done: false, cancelled: false, startRequested: false};
            current.operation = operation;
            ring.hidden = true; badge.hidden = true; metrics.hidden = true; details.hidden = true; problems.hidden = true; notice.hidden = true;
            list.replaceChildren(); rows.clear();
            details.open = false; body.scrollTop = 0; guidance('UPLOADING');
            check.disabled = true; cancel.textContent = historyText[4];
            try {
                operation.base = server();
                progress.textContent = historyText[7];
                await uploadBrowserHistory(operation, progress);
                if (operation.readError) throw operation.readError;
                let view = await historyCall(operation, 'inspect');
                while (!operation.cancelled && historyWindow === current) {
                    current.render(view);
                    if (historyTerminal(view.phase)) break;
                    if (view.phase === 'READY' && view.preview?.candidates === 0) {
                        if (await cancelHistory() && historyWindow === current) {
                            current.render(view);
                            check.disabled = false;
                        }
                        break;
                    }
                    if (view.phase === 'READY' && operation.startRequested) view = await historyCall(operation, 'start');
                    else if (view.metadata) view = await historyMetadata(operation, view.metadata);
                    else { await historyWait(operation); view = await historyCall(operation, 'status'); }
                }
                if (!operation.cancelled) operation.done = true;
            } catch (error) {
                if (!operation.cancelled) {
                    if (operation.session || !error.rejected && operation.base) await cancelHistory();
                    else operation.done = true;
                    if (historyWindow === current) {
                        progress.textContent = historyText[16];
                        guidance('FAILED');
                        showNotice((error.rejected || !operation.base ? '' : historyText[17] + '\n') + error.message);
                        ring.hidden = true;
                        badge.hidden = false; badge.className = 'badge warning'; badge.textContent = '!';
                    }
                }
            } finally {
                if (operation.done && historyWindow === current) {
                    start.disabled = true; start.hidden = true; check.hidden = check.disabled;
                    check.className = check.disabled ? '' : 'primary';
                    cancel.textContent = historyText[5]; cancel.className = check.disabled ? 'primary' : '';
                }
            }
        });
        dialog.showModal();
    }
    GM_registerMenuCommand(historyText[0], openHistory);
    window.addEventListener('addResult', captureMetadata);
    window.addEventListener('downloadSuccess', captureSuccess);
    window.addEventListener('crawlStart', () => {
        metadata.clear(); metadataBytes = 0; warned = false; taskBatch = 0; paused = false;
    });
    // 上游成功事件不含请求批号；按观察到的下载开始划分，暂停续传保留已完成页。
    window.addEventListener('downloadStart', () => {
        if (!paused || !taskBatch) taskBatch = lastBatch = Math.max(Date.now(), lastBatch + 1);
        paused = false;
    });
    window.addEventListener('downloadPause', () => { paused = true; });
    window.addEventListener('downloadStop', () => { paused = false; });
    window.addEventListener('pagehide', () => {
        void cancelHistory();
        stopped = true; clearTimeout(timer); timer = null;
        const operation = active;
        active = null;
        if (operation && operation.request) operation.request.cancel();
    });
    window.addEventListener('pageshow', () => {
        if (!stopped) return;
        stopped = false;
        drain();
    });
    GM_registerMenuCommand(messages[0], () => {
        const value = prompt(messages[2].replace('{server}', DEFAULT_SERVER), GM_getValue('pixiv-batch-downloader-import.server', DEFAULT_SERVER));
        if (value === null) return;
        const previous = GM_getValue('pixiv-batch-downloader-import.server', DEFAULT_SERVER);
        GM_setValue('pixiv-batch-downloader-import.server',value.trim());
        try { server(); if (value.trim() !== previous) void cancelHistory(); backoff = 1000; nextAttempt = 0; drain(); }
        catch (_) { GM_setValue('pixiv-batch-downloader-import.server',previous); alert(messages[3]); }
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
    drain();
})();
