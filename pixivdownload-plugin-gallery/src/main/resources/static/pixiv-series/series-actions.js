'use strict';

    async function setupAdminMode() {
        try {
            const res = await fetch('/api/auth/check', {credentials: 'same-origin'});
            if (res.ok && (await res.json()).valid === true) {
                document.body.classList.add('admin-mode');
            }
        } catch (_) {
        }
    }

    // 「下载合订本」仅小说系列可见（任何能访问该系列的用户都可下载）
    function refreshDownloadMergedBtn() {
        const btn = document.getElementById('downloadMergedBtn');
        if (!btn) return;
        btn.style.display = isNovelMode() ? '' : 'none';
    }

    // 章节链接保留选中的译文身份，确保详情页沿用同一语言与引擎。
    function novelChapterHref(novelId) {
        let href = `/pixiv-novel.html?id=${novelId}`;
        if (activeContentLang) href += `&variant=${encodeURIComponent(activeContentLang)}`;
        return href;
    }

    // 内容选择优先使用 variant，兼容旧 lang 链接；其次使用已有译文中的全局记忆。
    function resolveInitialContentLang(langs) {
        const params = new URLSearchParams(location.search);
        const urlLang = params.get('variant') || params.get('lang');
        if (urlLang && langs.indexOf(urlLang) !== -1) return urlLang;
        try {
            const stored = localStorage.getItem('pixiv.contentLang');
            if (stored && langs.indexOf(stored) !== -1) return stored;
        } catch (_) {}
        return '';
    }

    // 装配小说系列专属控件：内容语言切换器 + 「翻译整个系列」按钮可见性。
    function setupNovelContentControls(langs) {
        if (Array.isArray(langs)) {
            seriesTranslatedLangs = langs;
        }
        activeContentLang = resolveInitialContentLang(seriesTranslatedLangs);
        refreshDownloadMergedBtn();
        window.PixivUiSlots?.update('series-detail-', seriesSlotProperties());
    }

    async function selectSeriesContentLanguage(lang) {
        activeContentLang = lang || '';
        try {
            await Promise.all([fetchSeriesTitleForLang(activeContentLang), fetchChapterTitlesForLang(activeContentLang, state.items)]);
        } catch (_) {
            // 读取失败时保留原文，允许用户再次选择。
        }
        renderSeriesHeader(); renderGrid(state.items);
        window.PixivUiSlots?.update('series-detail-', seriesSlotProperties());
    }

    // 系列名 / 系列简介：一次请求取该语言的译后系列名与系列简介（内存缓存）。
    // lang 为空时清除并由 renderSeriesHeader / renderDescription 自动回退到原文。
    // 网络失败时<b>不写</b>空缓存（否则后续 hasOwnProperty 命中会拦下重试），让调用方决定回退或提示。
    async function fetchSeriesTitleForLang(lang) {
        if (!lang || !state.seriesId) return;
        if (Object.prototype.hasOwnProperty.call(seriesTitleByLang, lang)) return;
        const data = await api(`/api/gallery/novel/series/${state.seriesId}?variant=${encodeURIComponent(lang)}`);
        seriesTitleByLang[lang] = (data && data.translatedTitle) ? data.translatedTitle : '';
        seriesDescriptionByLang[lang] = (data && data.translatedDescription) ? data.translatedDescription : '';
    }

    // 章节标题：批量取当前页章节在该语言的译后标题（一次性请求 + 按 lang 缓存覆盖）。lang 空时跳过。
    // 同样改为<b>失败抛出</b>，让调用方决定是否提示用户重试。
    async function fetchChapterTitlesForLang(lang, items) {
        if (!lang || !Array.isArray(items) || !items.length) return;
        const ids = items.map(it => it && it.novelId).filter(id => id != null);
        if (!ids.length) return;
        const url = `/api/gallery/novel/translated-titles?variant=${encodeURIComponent(lang)}`
            + `&ids=${encodeURIComponent(ids.join(','))}`;
        const data = await api(url);
        const titles = (data && data.titles) || {};
        const bucket = chapterTitlesByLang[lang] || {};
        Object.keys(titles).forEach(k => { bucket[k] = titles[k]; });
        chapterTitlesByLang[lang] = bucket;
    }

    // 当前章节卡片应显示的标题：所选语言译文优先，回退原标题。
    function chapterDisplayTitle(item) {
        if (!item) return '';
        const original = item.title || t('status.untitled', 'Untitled');
        if (!activeContentLang || !item.novelId) return original;
        const bucket = chapterTitlesByLang[activeContentLang];
        const translated = bucket ? bucket[item.novelId] : null;
        return (translated && String(translated).trim()) ? translated : original;
    }

    // 当前应显示的系列名：所选语言译后系列名优先，回退原系列名。
    function seriesDisplayTitle() {
        const detail = state.detail;
        const original = (detail && detail.title)
            || modeText('series.default', 'Series #{id}', 'Series #{id}', {id: state.seriesId});
        if (!activeContentLang) return original;
        const translated = seriesTitleByLang[activeContentLang];
        return (translated && String(translated).trim()) ? translated : original;
    }

    // 当前应显示的系列简介：所选语言译后系列简介优先，回退原系列简介。
    function seriesDisplayDescription() {
        const detail = state.detail;
        const original = detail ? detail.description : null;
        if (!activeContentLang) return original;
        const translated = seriesDescriptionByLang[activeContentLang];
        return (translated && String(translated).trim()) ? translated : original;
    }

    function setSeriesMessage(text) {
        const el = document.getElementById('seriesStatus');
        if (el) el.textContent = text;
    }

    // 解析 Content-Disposition 中的 filename / filename*（后者优先，因为它带 UTF-8 编码）
    function parseFilenameFromDisposition(disp) {
        if (!disp) return '';
        let m = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/i.exec(disp);
        if (m) {
            try { return decodeURIComponent(m[1].trim()); } catch (_) { return m[1].trim(); }
        }
        m = /filename\s*=\s*"([^"]+)"/i.exec(disp);
        if (m) return m[1];
        m = /filename\s*=\s*([^;]+)/i.exec(disp);
        return m ? m[1].trim() : '';
    }

    async function downloadMergedVolume(lang, signal) {
        if (!state.seriesId) return;
        setSeriesMessage(t('merge.generating', '正在生成合订本，请稍候...'));
        try {
            const params = new URLSearchParams();
            params.set('format', 'epub');
            if (lang) params.set('variant', lang);
            const url = `/api/novel/series/${state.seriesId}/merged?${params.toString()}`;
            const res = await fetch(url, {credentials: 'same-origin', signal});
            if (signal?.aborted) return;
            if (res.status === 401) {
                window.location.href = '/login.html?redirect='
                    + encodeURIComponent(location.pathname + location.search);
                return;
            }
            if (!res.ok) throw new Error('merge_download_failed');
            const blob = await res.blob();
            if (signal?.aborted) return;
            const filename = parseFilenameFromDisposition(res.headers.get('content-disposition'))
                || `${(state.detail && state.detail.title) || state.seriesId}.epub`;
            const blobUrl = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = blobUrl;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(() => URL.revokeObjectURL(blobUrl), 1000);
            setSeriesMessage(t('merge.downloaded', '合订本已开始下载'));
        } catch (_) {
            if (!signal?.aborted) setSeriesMessage(t('merge.failed', 'Unable to download the merged volume. Please try again.'));
        }
    }

    let mergeDownloadRequest = null;

    function cancelMergedDownload() { mergeDownloadRequest?.abort(); }

    async function openDownloadMergedDialog() {
        if (!isNovelMode() || !state.seriesId || mergeDownloadRequest) return;
        const request = new AbortController();
        mergeDownloadRequest = request;
        const button = document.getElementById('downloadMergedBtn');
        button.disabled = true;
        try {
            const variants = [...new Set((seriesTranslatedLangs || []).filter(Boolean))];
            const content = document.createElement('div');
            content.className = 'series-merge-options';
            function select(key, name) {
                const label = document.createElement('label');
                label.textContent = t(key);
                const control = document.createElement('select');
                control.name = name;
                control.className = 'pixiv-feedback-input';
                label.appendChild(control);
                content.appendChild(label);
                return control;
            }
            function option(control, value, label) {
                const node = document.createElement('option');
                node.value = value;
                node.textContent = label;
                control.appendChild(node);
            }
            const language = select('merge.dialog-language', 'language');
            const engine = select('merge.dialog-engine', 'engine');
            option(language, '', t('merge.lang-original'));
            [...new Set(variants.map(value => value.split('@')[0]))].forEach(value => {
                let label = value;
                try { label = new Intl.DisplayNames([document.documentElement.lang || 'en'], {type: 'language'}).of(value); }
                catch (_) { /* 未识别的语言保留原始代码。 */ }
                option(language, value, label);
            });
            const warning = document.createElement('p');
            warning.className = 'series-merge-hint';
            warning.textContent = t('merge.dialog-warning');
            content.appendChild(warning);
            function updateEngines(preferred) {
                engine.replaceChildren();
                const matches = variants.filter(value => value.split('@')[0] === language.value);
                matches.forEach(value => {
                    const source = value.split('@')[1] || 'unknown';
                    option(engine, value, ['ai', 'youdao', 'libretranslate', 'unknown'].includes(source)
                        ? t('merge.engine.' + source) : source);
                });
                if (!matches.length) option(engine, '', t('merge.engine.none'));
                engine.disabled = !matches.length;
                if (matches.includes(preferred)) engine.value = preferred;
                warning.hidden = !language.value;
            }
            language.value = variants.includes(activeContentLang) ? activeContentLang.split('@')[0] : '';
            updateEngines(activeContentLang);
            language.addEventListener('change', () => {
                const source = engine.value.split('@')[1];
                updateEngines(language.value + (source ? '@' + source : ''));
            });
            const variant = await window.PixivFeedback.form({
                title: t('merge.dialog-title'), message: t('merge.dialog-message'), content,
                readValue: () => engine.value,
                confirmLabel: t('merge.dialog-confirm'), cancelLabel: t('merge.dialog-cancel'), signal: request.signal
            });
            if (variant === null || request.signal.aborted) return;
            await downloadMergedVolume(variant, request.signal);
        } finally {
            if (mergeDownloadRequest === request) mergeDownloadRequest = null;
            button.disabled = false;
            if (!request.signal.aborted) button.focus();
        }
    }
