/* AI 翻译请求、批量执行与进度状态。 */
(function (global) {
    'use strict';

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

    // 文本模型（LLM）是否已配置：admin-only 的 /api/admin/ai/status（纯配置检查、不触网）。结果按进程缓存，
    // 供小说 / 系列详情页决定是否展示「AI 翻译」入口——未配置时不展示。非管理员 / 探测失败一律按未配置处理。
    var aiConfiguredPromise = null;
    function isAiConfigured() {
        if (!aiConfiguredPromise) {
            aiConfiguredPromise = fetch('/api/admin/ai/status', { credentials: 'same-origin' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (d) { return !!(d && d.configured); })
                .catch(function () { return false; });
        }
        return aiConfiguredPromise;
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
                targetLanguage: opts.targetLanguage,
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
        var res = await fetch('/api/novel/' + encodeURIComponent(novelId) + '/translate', fetchInit);
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
        if (langCode) params.set('lang', langCode);
        var res = await fetch('/api/novel/series/' + encodeURIComponent(seriesId)
            + '/merge?' + params.toString(), { method: 'POST', credentials: 'same-origin' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    }

    // 翻译某系列的系列名 / 系列简介为目标语言（admin only）。best-effort：失败仅返回 null，不抛错。
    // 传 glossaryId 时与正文翻译共用同一张映射表，保证系列名与已译章节标题的术语一致。
    // translateTitle / translateDescription 默认 true；两者全 false 时跳过本次调用直接返回 null。
    async function translateSeriesTitle(seriesId, targetLanguage, langHint, glossaryId,
                                        translateTitle, translateDescription) {
        var doTitle = translateTitle == null ? true : !!translateTitle;
        var doDescription = translateDescription == null ? true : !!translateDescription;
        if (!doTitle && !doDescription) return null;
        try {
            var res = await fetch('/api/novel/series/' + encodeURIComponent(seriesId)
                + '/translate-title', {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    targetLanguage: targetLanguage,
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
            body: JSON.stringify({ targetLanguage: targetLanguage || '' })
        };
        if (opts.signal) fetchInit.signal = opts.signal;
        var res = await fetch('/api/novel/translate-lang-probe', fetchInit);
        if (!res.ok) return '';
        try {
            var data = await res.json();
            return (data && data.valid && data.code) ? String(data.code).trim() : '';
        } catch (_) {
            return '';
        }
    }

    // ── 翻译进度弹窗（单作品 / 系列共用） ────────────────────────────────────────────
    // 设计：同一时刻只允许一个 activeJob；翻译按钮在 job 进行中再次点击 = 重新弹出当前进度。
    // 「隐藏」只关闭弹窗、保留任务；「取消」abort 当前 fetch（后端可能仍写入 DB，无法拦截）。
    // 系列模式下取消后已完成章节仍触发该语言合订。

    var progressEl = null;
    var progressRefs = null;
    var progressTimerId = null;
    var activeJob = null;

    function buildProgress() {
        var backdrop = document.createElement('div');
        backdrop.className = 'pt-backdrop pt-progress-backdrop';
        backdrop.innerHTML =
            '<div class="pt-modal" role="dialog" aria-modal="true">' +
            '  <div class="pt-head">' +
            '    <span class="pt-title pt-progress-title"></span>' +
            '  </div>' +
            '  <div class="pt-body pt-progress-body">' +
            '    <div class="pt-progress-status">' +
            '      <span class="pt-progress-spinner"></span>' +
            '      <span class="pt-progress-status-text"></span>' +
            '    </div>' +
            '    <div class="pt-progress-sub pt-progress-sub-text"></div>' +
            '    <div class="pt-progress-stats pt-progress-stats-text"></div>' +
            '    <div class="pt-progress-warnings"></div>' +
            '  </div>' +
            '  <div class="pt-foot">' +
            '    <button class="pt-btn pt-progress-hide" type="button"></button>' +
            '    <button class="pt-btn pt-btn-danger pt-progress-cancel" type="button"></button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(backdrop);
        progressRefs = {
            backdrop: backdrop,
            title: backdrop.querySelector('.pt-progress-title'),
            statusText: backdrop.querySelector('.pt-progress-status-text'),
            subText: backdrop.querySelector('.pt-progress-sub-text'),
            statsText: backdrop.querySelector('.pt-progress-stats-text'),
            warnings: backdrop.querySelector('.pt-progress-warnings'),
            hideBtn: backdrop.querySelector('.pt-progress-hide'),
            cancelBtn: backdrop.querySelector('.pt-progress-cancel')
        };
        return backdrop;
    }

    function elapsedSec(startedAt) {
        return Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
    }

    function renderProgressStatus() {
        if (!progressRefs || !activeJob) return;
        var st = activeJob.state;
        var i18n = st.i18n;
        var elapsed = elapsedSec(st.phaseStartedAt);
        if (st.phase === 'merging') {
            progressRefs.statusText.textContent = tt(i18n, 'progress.status-merging',
                'Generating the merged volume… ({elapsed}s)', { elapsed: elapsed });
        } else if (st.phase === 'resolving') {
            progressRefs.statusText.textContent = tt(i18n, 'progress.status-resolving',
                'Resolving language code… ({elapsed}s)', { elapsed: elapsed });
        } else {
            progressRefs.statusText.textContent = tt(i18n, 'progress.status-running',
                'AI model translating… ({elapsed}s)', { elapsed: elapsed });
        }
    }

    function renderProgressAll() {
        if (!progressRefs || !activeJob) return;
        var st = activeJob.state;
        var i18n = st.i18n;
        progressRefs.title.textContent = st.title;
        progressRefs.hideBtn.textContent = tt(i18n, 'progress.btn-hide', 'Hide');
        progressRefs.cancelBtn.textContent = tt(i18n, 'progress.btn-cancel', 'Cancel');
        renderProgressStatus();
        if (st.type === 'series' && st.phase === 'translating') {
            progressRefs.subText.textContent = tt(i18n, 'progress.series-progress',
                'Translating chapter {done} / {total}',
                { done: st.currentIndex + 1, total: st.total });
            progressRefs.subText.style.display = '';
        } else {
            progressRefs.subText.style.display = 'none';
        }
        if (st.type === 'series') {
            progressRefs.statsText.textContent = tt(i18n, 'progress.series-stats',
                '{ok} done {skipped} skipped {failed} failed',
                { ok: st.ok, skipped: st.skipped + (st.sameLang || 0), failed: st.failed });
            progressRefs.statsText.style.display = '';
        } else {
            progressRefs.statsText.style.display = 'none';
        }
        progressRefs.warnings.innerHTML = '';
        if (st.warnings && st.warnings.length) {
            st.warnings.forEach(function (text) {
                var div = document.createElement('div');
                div.className = 'pt-progress-warn';
                div.textContent = text;
                progressRefs.warnings.appendChild(div);
            });
            progressRefs.warnings.style.display = '';
        } else {
            progressRefs.warnings.style.display = 'none';
        }
        progressRefs.cancelBtn.disabled = !!st.cancelDisabled;
    }

    function showProgressDialog() {
        if (!activeJob) return;
        if (!progressEl) progressEl = buildProgress();
        progressEl.classList.add('open');
        renderProgressAll();
        if (progressTimerId == null) {
            progressTimerId = setInterval(function () {
                if (!activeJob) return;
                if (progressEl && progressEl.classList.contains('open')) {
                    renderProgressStatus();
                }
            }, 500);
        }
    }

    function hideProgressDialog() {
        if (progressEl) progressEl.classList.remove('open');
    }

    function endProgressJob() {
        hideProgressDialog();
        if (progressTimerId != null) {
            clearInterval(progressTimerId);
            progressTimerId = null;
        }
        activeJob = null;
    }

    function bindProgressButtons(onHide, onCancel) {
        if (!progressRefs) return;
        progressRefs.hideBtn.onclick = onHide || null;
        progressRefs.cancelBtn.onclick = onCancel || null;
    }

    async function runSingleNovel(opts) {
        opts = opts || {};
        if (activeJob) { showProgressDialog(); return null; }
        var i18n = opts.i18n;
        var controller = new AbortController();
        var state = {
            type: 'single',
            i18n: i18n,
            title: tt(i18n, 'progress.title-single', 'AI translation in progress'),
            phase: 'translating',
            phaseStartedAt: Date.now(),
            warnings: []
        };
        var cancelled = false;
        activeJob = { type: 'single', state: state, show: showProgressDialog };
        // 必须先 showProgressDialog 触发 buildProgress（progressRefs 才存在），再绑定按钮
        showProgressDialog();
        bindProgressButtons(hideProgressDialog, function () {
            cancelled = true;
            state.cancelDisabled = true;
            renderProgressAll();
            controller.abort();
        });
        var result = null, error = null;
        try {
            result = await translateNovel(opts.novelId, Object.assign({}, opts.choice,
                { signal: controller.signal }));
        } catch (e) {
            error = e;
        }

        // 翻译成功且该小说属于某个系列：重生该语言变体合订本（best-effort）。
        // 仅在新译成功（OK）时触发，SKIPPED 表示该语言译文早已落库、合订本应已是最新。
        var mergeFailed = null;
        if (!cancelled && !error && result && opts.seriesId
                && result.status === 'OK' && result.langCode) {
            // 合订前顺手补齐该系列在此语言下的系列名 / 系列简介翻译（best-effort），共用本次选定的映射表与勾选范围
            try { await translateSeriesTitle(opts.seriesId, opts.choice.targetLanguage,
                    result.langCode, opts.choice.glossaryId,
                    opts.choice.translateTitle, opts.choice.translateDescription); }
            catch (_) {}
            state.phase = 'merging';
            state.phaseStartedAt = Date.now();
            state.cancelDisabled = true;
            renderProgressAll();
            try {
                await mergeSeriesLang(opts.seriesId, result.langCode, 'epub');
            } catch (e) {
                mergeFailed = e;
            }
        }

        endProgressJob();
        if (cancelled) return { cancelled: true };
        if (error) return { error: error };
        return { result: result, mergeFailed: mergeFailed };
    }

    async function runSeries(opts) {
        opts = opts || {};
        if (activeJob) { showProgressDialog(); return null; }
        var i18n = opts.i18n;
        var controller = new AbortController();
        var state = {
            type: 'series',
            i18n: i18n,
            title: tt(i18n, 'progress.title-series', 'Series translation in progress'),
            phase: 'translating',
            phaseStartedAt: Date.now(),
            currentIndex: 0,
            total: 0,
            ok: 0, skipped: 0, sameLang: 0, failed: 0,
            warnings: [
                tt(i18n, 'progress.warn-do-not-close',
                    'Do not close or refresh this tab; the progress will be interrupted.'),
                tt(i18n, 'progress.warn-long',
                    'Serial translation keeps glossary consistency; the whole series may take minutes.'),
                tt(i18n, 'progress.warn-cancel',
                    'Cancel keeps already-translated chapters and still triggers the merged volume.')
            ]
        };
        var cancelled = false;
        activeJob = { type: 'series', state: state, show: showProgressDialog };
        // 必须先 showProgressDialog 触发 buildProgress（progressRefs 才存在），再绑定按钮
        showProgressDialog();
        bindProgressButtons(hideProgressDialog, function () {
            cancelled = true;
            state.cancelDisabled = true;
            renderProgressAll();
            controller.abort();
        });

        var ids;
        try {
            ids = await fetchSeriesNovelIds(opts.seriesId);
        } catch (e) {
            endProgressJob();
            return { error: e };
        }
        if (!ids.length) {
            endProgressJob();
            return { empty: true };
        }
        state.total = ids.length;
        renderProgressAll();

        // 跳过模式下：先用一次小型 AI 探测把用户自由文本目标语言转为规范 BCP-47 代码，
        // 使首章也能凭 langHint 走 DB 跳过、不必为识别语言再发一次完整翻译请求。
        // 覆盖模式下每章都必发 AI 调用，无需预探测。
        var langCode = null;
        var invalid = false;
        if (!opts.choice.overwrite && !cancelled) {
            state.phase = 'resolving';
            state.phaseStartedAt = Date.now();
            renderProgressAll();
            try {
                var probed = await probeLangCode(opts.choice.targetLanguage,
                    { signal: controller.signal });
                if (probed) langCode = probed;
            } catch (e) {
                // 探测失败 / 取消：忽略，回退到无 langHint 的行为
                if (cancelled || (e && e.name === 'AbortError')) {
                    // fall through to loop; cancelled flag handles break
                }
            }
            state.phase = 'translating';
            renderProgressAll();
        }

        for (var i = 0; i < ids.length; i++) {
            if (cancelled) break;
            state.currentIndex = i;
            state.phaseStartedAt = Date.now();
            renderProgressAll();
            try {
                var resp = await translateNovel(ids[i], Object.assign({}, opts.choice,
                    { langHint: langCode, signal: controller.signal }));
                if (resp.status === 'INVALID_LANGUAGE') { invalid = true; break; }
                if (resp.status === 'OK') {
                    if (resp.langCode && !langCode) langCode = resp.langCode;
                    state.ok++;
                } else if (resp.status === 'SKIPPED') {
                    if (resp.langCode && !langCode) langCode = resp.langCode;
                    state.skipped++;
                } else if (resp.status === 'SAME_LANGUAGE') {
                    // 原文已是目标语言：计入「跳过」，但不贡献 langCode —— 该语言没有任何译文变体，
                    // 不能触发合订（否则会对无译文的语言生成空/原文合订本）。
                    state.sameLang++;
                } else {
                    state.failed++;
                }
            } catch (e) {
                if (cancelled || (e && e.name === 'AbortError')) break;
                state.failed++;
            }
            renderProgressAll();
        }

        var mergeFailed = null;
        if (!invalid && langCode && (state.ok > 0 || state.skipped > 0)) {
            // 合订前先把系列名 / 系列简介翻译好（best-effort），共用本次选定的映射表与勾选范围
            try { await translateSeriesTitle(opts.seriesId, opts.choice.targetLanguage,
                    langCode, opts.choice.glossaryId,
                    opts.choice.translateTitle, opts.choice.translateDescription); }
            catch (_) {}
            state.phase = 'merging';
            state.phaseStartedAt = Date.now();
            state.cancelDisabled = true;
            renderProgressAll();
            try {
                await mergeSeriesLang(opts.seriesId, langCode, 'epub');
            } catch (e) {
                mergeFailed = e;
            }
        }
        endProgressJob();
        return {
            cancelled: cancelled,
            invalid: invalid,
            // 跳过数对外合并展示「已有译文跳过」与「源语言一致跳过」两类
            ok: state.ok, skipped: state.skipped + state.sameLang, failed: state.failed,
            langCode: langCode,
            mergeFailed: mergeFailed
        };
    }

    function hasActiveJob() { return !!activeJob; }
    function showActiveJob() { if (activeJob) showProgressDialog(); }

    global.PixivTranslate = {
        openDialog: function (opts) { return global.PixivTranslateDialog.openDialog(opts); },
        isAiConfigured: isAiConfigured,
        translateNovel: translateNovel,
        translateSeriesTitle: translateSeriesTitle,
        mergeSeriesLang: mergeSeriesLang,
        fetchSeriesNovelIds: fetchSeriesNovelIds,
        runSingleNovel: runSingleNovel,
        runSeries: runSeries,
        hasActiveJob: hasActiveJob,
        showActiveJob: showActiveJob,
        STATUS_OK: 'OK',
        STATUS_SKIPPED: 'SKIPPED',
        STATUS_SAME_LANGUAGE: 'SAME_LANGUAGE',
        STATUS_INVALID_LANGUAGE: 'INVALID_LANGUAGE'
    };

})(window);
