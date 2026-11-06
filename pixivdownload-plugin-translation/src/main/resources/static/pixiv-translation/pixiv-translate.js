/* 翻译请求、批量执行与进度状态。 */
(function (global) {
    'use strict';
    function create(scope = {}) {
    const fetch = scope.fetch || global.fetch.bind(global);
    const signal = scope.signal;
    let disposed = false;
    let dialog;
    function dispose() {
        if (disposed) return;
        disposed = true;
        hideProgressDialog();
        progressEl?.remove();
        dialog?.destroy();
        signal?.removeEventListener('abort', dispose);
        global.removeEventListener?.('pagehide', hideProgressDialog);
        global.removeEventListener?.('pixiv:i18n-changed', renderProgressAll);
    }
    signal?.addEventListener('abort', dispose, {once: true});

    function tt(i18n, key, fallback, vars) {
        if (i18n && typeof i18n.t === 'function') {
            return i18n.t('translate:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function interpolate(template, vars) {
        if (!vars) return String(template);
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, function (m, name) {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : m;
        });
    }

    // ── 后端接口 ─────────────────────────────────────────────────────────────────

    var configuredPromise = null;
    function engineStatus(refresh) {
        if (refresh || !configuredPromise) {
            configuredPromise = fetch('/api/translation/status', { credentials: 'same-origin' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .catch(function () { return null; });
        }
        return configuredPromise;
    }
    function isConfigured() {
        return engineStatus().then(function (status) { return !!(status && status.configured); });
    }

    async function translateNovel(novelId, opts) {
        opts = opts || {};
        // 翻译范围：未显式给出时默认全部 true（兼容旧调用方与外部脚本）
        var translateBody = opts.translateBody == null ? true : !!opts.translateBody;
        var translateTitle = opts.translateTitle == null ? true : !!opts.translateTitle;
        var translateDescription = opts.translateDescription == null ? true : !!opts.translateDescription;
        var fetchInit = {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                novelId: novelId,
                targetLanguage: opts.targetLanguage,
                engine: opts.engine || null,
                segmentSize: opts.segmentSize == null ? 0 : opts.segmentSize,
                overwrite: !!opts.overwrite,
                langHint: opts.langHint || null,
                glossaryId: opts.glossaryId == null ? null : opts.glossaryId,
                translateBody: translateBody,
                translateTitle: translateTitle,
                translateDescription: translateDescription
            })
        };
        if (opts.signal) fetchInit.signal = opts.signal;
        var res = await fetch('/api/translation/novel', fetchInit);
        if (!res.ok) {
            var msg = 'HTTP ' + res.status;
            try {
                var j = await res.json();
                if (j && (j.error || j.message)) msg = j.error || j.message;
            } catch (_) {}
            throw new Error(msg);
        }
        return res.json();
    }

    async function mergeSeriesLang(seriesId, langCode, format) {
        var params = new URLSearchParams();
        params.set('format', format || 'epub');
        if (langCode) params.set('variant', langCode);
        var res = await fetch('/api/novel/series/' + encodeURIComponent(seriesId)
            + '/merge?' + params.toString(), { method: 'POST', credentials: 'same-origin' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    }

    // 翻译某系列的系列名 / 系列简介为目标语言（admin only）。best-effort：失败仅返回 null，不抛错。
    // 传 glossaryId 时与正文翻译共用同一张映射表，保证系列名与已译章节标题的术语一致。
    // translateTitle / translateDescription 默认 true；两者全 false 时跳过本次调用直接返回 null。
    async function translateSeriesTitle(seriesId, targetLanguage, langHint, glossaryId,
                                        translateTitle, translateDescription, engine) {
        var doTitle = translateTitle == null ? true : !!translateTitle;
        var doDescription = translateDescription == null ? true : !!translateDescription;
        if (!doTitle && !doDescription) return null;
        try {
            var res = await fetch('/api/translation/series', {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    seriesId: seriesId,
                    targetLanguage: targetLanguage,
                    engine: engine || null,
                    langHint: langHint || null,
                    glossaryId: (glossaryId == null ? null : glossaryId),
                    translateTitle: doTitle,
                    translateDescription: doDescription
                })
            });
            if (!res.ok) return null;
            var data = await res.json();
            return (data && data.langCode) ? data : null;
        } catch (_) {
            return null;
        }
    }

    async function fetchSeriesNovelIds(seriesId) {
        var res = await fetch('/api/novel/series/' + encodeURIComponent(seriesId) + '/novel-ids',
            { credentials: 'same-origin' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        var data = await res.json();
        return (data && data.novelIds) || [];
    }

    // 把用户自由文本（「简体中文」/「english」）解析为规范 BCP-47 代码（zh-CN / en-US）。
    // 用于系列批量翻译前预解析，使首章也能凭 langHint 走 DB 跳过。失败返回空字符串。
    async function probeLangCode(targetLanguage, opts) {
        opts = opts || {};
        var fetchInit = {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ targetLanguage: targetLanguage || '', engine: opts.engine || null })
        };
        if (opts.signal) fetchInit.signal = opts.signal;
        var res = await fetch('/api/translation/language', fetchInit);
        if (!res.ok) return '';
        try {
            var data = await res.json();
            return (data && data.valid && data.code) ? String(data.code).trim() : '';
        } catch (_) {
            return '';
        }
    }

    // 进度读取随弹窗关闭而释放；服务端任务继续保留自己的执行状态。
    var progressEl = null;
    var progressRefs = null;
    var activeJob = null;
    var returnFocus = null;

    async function jobRequest(path, method, body, signal) {
        var init = { method: method || 'GET', credentials: 'same-origin' };
        if (body) { init.headers = { 'Content-Type': 'application/json' }; init.body = JSON.stringify(body); }
        if (signal) init.signal = signal;
        var response = await fetch('/api/translation/jobs' + path, init);
        if (!response.ok) {
            var error = new Error('HTTP ' + response.status);
            try { var value = await response.json(); error.message = value.error || error.message; } catch (_) {}
            throw error;
        }
        return response.status === 204 ? null : response.json();
    }

    async function findJob(novelId, seriesId) {
        var query = seriesId ? '?seriesId=' + encodeURIComponent(seriesId) : '?novelId=' + encodeURIComponent(novelId);
        var jobs = await jobRequest(query);
        return jobs.find(function (job) {
            return job.state !== 'COMPLETED' && (!seriesId || job.allChapters === true);
        }) || null;
    }

    function buildProgress() {
        var backdrop = document.createElement('div');
        backdrop.className = 'pt-backdrop pt-progress-backdrop';
        backdrop.innerHTML =
            '<div class="pt-modal" role="dialog" aria-modal="true" aria-labelledby="pt-job-title">' +
            '<div class="pt-head"><span class="pt-title" id="pt-job-title"></span></div>' +
            '<div class="pt-body pt-progress-body">' +
            '<div class="pt-progress-status"><span class="pt-progress-spinner" aria-hidden="true"></span>' +
            '<span class="pt-progress-status-text" role="status"></span></div>' +
            '<div class="pt-progress-sub"></div><div class="pt-progress-stats"></div>' +
            '<div class="pt-progress-warnings" role="status"></div></div>' +
            '<div class="pt-foot"><button class="pt-btn pt-progress-hide" type="button"></button>' +
            '<button class="pt-btn pt-progress-action" type="button"></button>' +
            '<button class="pt-btn pt-btn-danger pt-progress-cancel" type="button"></button></div></div>';
        document.body.appendChild(backdrop);
        progressRefs = {
            title: backdrop.querySelector('.pt-title'), status: backdrop.querySelector('.pt-progress-status-text'),
            spinner: backdrop.querySelector('.pt-progress-spinner'), sub: backdrop.querySelector('.pt-progress-sub'),
            stats: backdrop.querySelector('.pt-progress-stats'), warning: backdrop.querySelector('.pt-progress-warnings'),
            hide: backdrop.querySelector('.pt-progress-hide'), action: backdrop.querySelector('.pt-progress-action'),
            cancel: backdrop.querySelector('.pt-progress-cancel')
        };
        backdrop.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') { event.preventDefault(); hideProgressDialog(); }
            if (event.key !== 'Tab') return;
            var buttons = [progressRefs.hide, progressRefs.action, progressRefs.cancel].filter(function (b) { return !b.disabled && !b.hidden; });
            var first = buttons[0], last = buttons[buttons.length - 1];
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        });
        progressRefs.hide.onclick = hideProgressDialog;
        progressRefs.action.onclick = function () { commandJob(false); };
        progressRefs.cancel.onclick = function () { commandJob(true); };
        return backdrop;
    }

    function stopped(job) { return job && (job.state === 'PAUSED' || job.state === 'CANCELLED'); }

    function renderProgressAll() {
        if (!activeJob || !progressRefs) return;
        var state = activeJob, job = state.job, i18n = state.i18n;
        progressRefs.title.textContent = tt(i18n, 'dialog.title', 'Translation');
        progressRefs.status.textContent = tt(i18n, 'job.state.' + (job ? job.state.toLowerCase() : 'submitting'), 'Translation');
        progressRefs.spinner.hidden = !!(job && (stopped(job) || job.state === 'COMPLETED')) || !!state.error;
        progressRefs.sub.textContent = tt(i18n, 'job.saved', '{count} responses saved', { count: job ? job.savedSteps : 0 });
        progressRefs.stats.textContent = state.series && job
            ? tt(i18n, 'job.chapters', '{done} of {total} chapters complete', { done: job.completed, total: job.total }) : '';
        progressRefs.warning.textContent = state.error || (job && job.reason ? tt(i18n, job.reason, 'Translation paused. Check the service and resume.')
            : tt(i18n, 'job.background', 'You can close this page. Translation continues in the background.'));
        progressRefs.hide.textContent = tt(i18n, 'progress.btn-hide', 'Hide');
        progressRefs.action.textContent = tt(i18n, state.error ? 'job.reconnect' : stopped(job) ? 'job.resume' : 'job.pause',
            state.error ? 'Reconnect' : stopped(job) ? 'Resume' : 'Pause');
        progressRefs.cancel.textContent = tt(i18n, state.confirmDiscard ? 'job.confirm-discard' : stopped(job) ? 'job.discard' : 'progress.btn-cancel',
            state.confirmDiscard ? 'Discard saved progress' : stopped(job) ? 'Discard task' : 'Cancel');
        var pending = !job || job.state === 'PAUSING' || job.state === 'CANCELLING';
        progressRefs.action.disabled = state.busy || pending;
        progressRefs.cancel.disabled = state.busy || pending;
    }

    function showProgressDialog() {
        if (!activeJob) return;
        if (!progressEl) progressEl = buildProgress();
        if (!progressEl.classList.contains('open')) returnFocus = document.activeElement;
        progressEl.classList.add('open');
        renderProgressAll();
        progressRefs.hide.focus();
    }

    function hideProgressDialog() {
        if (activeJob) finishJob(activeJob, { cancelled: true });
    }

    function finishJob(state, result) {
        if (activeJob !== state) return;
        activeJob = null;
        if (state.read) state.read.abort();
        if (state.timer) clearTimeout(state.timer);
        if (state.readTimeout) clearTimeout(state.readTimeout);
        if (progressEl) progressEl.classList.remove('open');
        if (returnFocus && returnFocus.isConnected && typeof returnFocus.focus === 'function') returnFocus.focus();
        returnFocus = null;
        state.resolve(result);
    }

    function completedResult(state) {
        var job = state.job;
        return state.series
            ? { empty: job.total === 0, ok: job.ok, skipped: job.skipped, failed: 0, langCode: job.language, variantKey: job.variantKey }
            : { result: { status: job.outcome, langCode: job.language, variantKey: job.variantKey, truncated: false } };
    }

    function pollJob(state) {
        if (activeJob !== state || state.busy || state.error || !state.job) return;
        state.read = new AbortController();
        var read = state.read;
        state.readTimeout = setTimeout(function () {
            if (activeJob !== state || state.read !== read) return;
            state.error = tt(state.i18n, 'job.connection-lost', 'Progress is unavailable. The task may still be running.');
            state.read = null;
            read.abort();
            renderProgressAll();
        }, 10000);
        jobRequest('/' + encodeURIComponent(state.job.id), 'GET', null, read.signal).then(function (job) {
            if (activeJob !== state || state.busy || state.read !== read) return;
            clearTimeout(state.readTimeout);
            state.job = job;
            renderProgressAll();
            if (job.state === 'COMPLETED') { finishJob(state, completedResult(state)); return; }
            state.timer = setTimeout(function () { pollJob(state); }, 1000);
        }).catch(function (error) {
            if (activeJob !== state || state.read !== read || error.name === 'AbortError') return;
            clearTimeout(state.readTimeout);
            state.error = tt(state.i18n, 'job.connection-lost', 'Progress is unavailable. The task may still be running.');
            renderProgressAll();
        });
    }

    async function commandJob(discardOrCancel) {
        var state = activeJob;
        if (!state || state.busy || !state.job) return;
        if (discardOrCancel && stopped(state.job) && !state.confirmDiscard) {
            state.confirmDiscard = true;
            renderProgressAll();
            return;
        }
        state.busy = true;
        progressRefs.hide.focus();
        if (state.read) state.read.abort();
        state.read = null;
        if (state.readTimeout) clearTimeout(state.readTimeout);
        if (state.timer) clearTimeout(state.timer);
        renderProgressAll();
        try {
            var discard = discardOrCancel && stopped(state.job);
            var action = discardOrCancel ? discard ? '' : '/cancel' : state.error ? '' : stopped(state.job) ? '/resume' : '/pause';
            var job = await jobRequest('/' + encodeURIComponent(state.job.id) + action,
                discard ? 'DELETE' : !discardOrCancel && state.error ? 'GET' : 'POST');
            if (activeJob !== state) return;
            if (discard) { finishJob(state, { cancelled: true }); return; }
            state.job = job; state.error = null; state.confirmDiscard = false;
        } catch (error) {
            if (activeJob === state) state.error = error.message;
        } finally {
            state.busy = false;
            if (activeJob === state) { renderProgressAll(); pollJob(state); }
        }
    }

    function runJob(opts, series) {
        if (disposed || signal?.aborted) return Promise.resolve({cancelled: true});
        if (activeJob) { showProgressDialog(); return Promise.resolve(null); }
        return new Promise(function (resolve) {
            var state = { i18n: opts.i18n, series: series, job: opts.job || null, resolve: resolve, busy: false };
            activeJob = state;
            showProgressDialog();
            if (state.job) { pollJob(state); return; }
            var choice = opts.choice;
            var bytes = global.crypto.getRandomValues(new Uint8Array(16));
            bytes[6] = (bytes[6] & 15) | 64;
            bytes[8] = (bytes[8] & 63) | 128;
            var id = Array.from(bytes, function (b) { return b.toString(16).padStart(2, '0'); }).join('');
            var submission = {
                requestId: [id.slice(0, 8), id.slice(8, 12), id.slice(12, 16), id.slice(16, 20), id.slice(20)].join('-'),
                seriesId: opts.seriesId || null, allChapters: series,
                mergeAfter: !!opts.seriesId, mergeFormat: 'epub',
                request: { novelId: opts.novelId || 0, targetLanguage: choice.targetLanguage, engine: choice.engine || null,
                    segmentSize: choice.segmentSize || 0, overwrite: !!choice.overwrite, langHint: null,
                    glossaryId: choice.glossaryId == null ? null : choice.glossaryId,
                    translateBody: choice.translateBody !== false, translateTitle: choice.translateTitle !== false,
                    translateDescription: choice.translateDescription !== false }
            };
            jobRequest('', 'POST', submission).then(function (job) {
                if (activeJob !== state) return;
                state.job = job; renderProgressAll(); pollJob(state);
            }).catch(function (error) {
                if (activeJob === state) finishJob(state, { error: error });
            });
        });
    }

    function runSingleNovel(opts) { return runJob(opts || {}, false); }
    function runSeries(opts) { return runJob(opts || {}, true); }
    if (typeof global.addEventListener === 'function') {
        global.addEventListener('pagehide', hideProgressDialog);
        global.addEventListener('pixiv:i18n-changed', renderProgressAll);
    }

    function hasActiveJob() { return !!activeJob; }
    function showActiveJob() { if (activeJob) showProgressDialog(); }

    return {
        openDialog: function (opts) {
            if (disposed || signal?.aborted) return Promise.resolve(null);
            if (!dialog) dialog = global.PixivTranslateDialog.create(scope);
            return dialog.openDialog(opts);
        },
        isConfigured: isConfigured,
        engineStatus: engineStatus,
        translateNovel: translateNovel,
        translateSeriesTitle: translateSeriesTitle,
        mergeSeriesLang: mergeSeriesLang,
        fetchSeriesNovelIds: fetchSeriesNovelIds,
        runSingleNovel: runSingleNovel,
        runSeries: runSeries,
        hasActiveJob: hasActiveJob,
        findJob: findJob,
        showActiveJob: showActiveJob,
        STATUS_OK: 'OK',
        STATUS_SKIPPED: 'SKIPPED',
        STATUS_SAME_LANGUAGE: 'SAME_LANGUAGE',
        STATUS_INVALID_LANGUAGE: 'INVALID_LANGUAGE',
        destroy: dispose
    };

    }
    global.PixivTranslate = {...create(), create};
})(window);
