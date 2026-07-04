'use strict';

    async function setupAdminMode() {
        try {
            const res = await fetch('/api/admin/invites/access-check', {credentials: 'same-origin'});
            if (res.ok) {
                document.body.classList.add('admin-mode');
                isAdmin = true;
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

    // 章节卡片链接：选定内容语言时附带 ?lang，使点开的小说详情页默认显示该语言译文。
    function novelChapterHref(novelId) {
        let href = `/pixiv-novel.html?id=${novelId}`;
        if (activeContentLang) href += `&lang=${encodeURIComponent(activeContentLang)}`;
        return href;
    }

    // 内容显示语言（独立于界面语言）：?lang 优先，其次全局记忆，且都需存在于本系列已有译文中。
    function resolveInitialContentLang(langs) {
        const params = new URLSearchParams(location.search);
        const urlLang = params.get('lang');
        if (urlLang && langs.indexOf(urlLang) !== -1) return urlLang;
        if (window.PixivContentLang) {
            const stored = PixivContentLang.getStored();
            if (stored && langs.indexOf(stored) !== -1) return stored;
        }
        return '';
    }

    // 装配小说系列专属控件：内容语言切换器 + 「翻译整个系列」按钮可见性。
    function setupNovelContentControls(langs) {
        if (Array.isArray(langs)) {
            seriesTranslatedLangs = langs;
        }
        activeContentLang = resolveInitialContentLang(seriesTranslatedLangs);
        refreshDownloadMergedBtn();
        const anchor = document.getElementById('contentLangAnchor');
        if (!anchor || !window.PixivContentLang) return;
        if (contentLangCtl) {
            contentLangCtl.setLanguages(seriesTranslatedLangs, activeContentLang);
            return;
        }
        contentLangCtl = PixivContentLang.mount({
            mountPoint: anchor,
            i18n: pageI18n,
            languages: seriesTranslatedLangs,
            current: activeContentLang,
            onChange: async (lang) => {
                activeContentLang = lang || '';
                try {
                    await Promise.all([
                        fetchSeriesTitleForLang(activeContentLang),
                        fetchChapterTitlesForLang(activeContentLang, state.items),
                    ]);
                } catch (_) {
                    // 切语言时拉译文失败：先按原文渲染，由用户再次切换重试
                }
                renderSeriesHeader();
                renderGrid(state.items);
            }
        });
    }

    // 系列名 / 系列简介：一次请求取该语言的译后系列名与系列简介（内存缓存）。
    // lang 为空时清除并由 renderSeriesHeader / renderDescription 自动回退到原文。
    // 网络失败时<b>不写</b>空缓存（否则后续 hasOwnProperty 命中会拦下重试），让调用方决定回退或提示。
    async function fetchSeriesTitleForLang(lang) {
        if (!lang || !state.seriesId) return;
        if (Object.prototype.hasOwnProperty.call(seriesTitleByLang, lang)) return;
        const data = await api(`/api/gallery/novel/series/${state.seriesId}?lang=${encodeURIComponent(lang)}`);
        seriesTitleByLang[lang] = (data && data.translatedTitle) ? data.translatedTitle : '';
        seriesDescriptionByLang[lang] = (data && data.translatedDescription) ? data.translatedDescription : '';
    }

    // 章节标题：批量取当前页章节在该语言的译后标题（一次性请求 + 按 lang 缓存覆盖）。lang 空时跳过。
    // 同样改为<b>失败抛出</b>，让调用方决定是否提示用户重试。
    async function fetchChapterTitlesForLang(lang, items) {
        if (!lang || !Array.isArray(items) || !items.length) return;
        const ids = items.map(it => it && it.novelId).filter(id => id != null);
        if (!ids.length) return;
        const url = `/api/gallery/novel/translated-titles?lang=${encodeURIComponent(lang)}`
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

    async function downloadMergedVolume(lang) {
        if (!state.seriesId) return;
        setSeriesMessage(t('merge.generating', '正在生成合订本，请稍候...'));
        try {
            const params = new URLSearchParams();
            params.set('format', 'epub');
            if (lang) params.set('lang', lang);
            const url = `/api/novel/series/${state.seriesId}/merged?${params.toString()}`;
            const res = await fetch(url, {credentials: 'same-origin'});
            if (res.status === 401) {
                window.location.href = '/login.html?redirect='
                    + encodeURIComponent(location.pathname + location.search);
                return;
            }
            if (!res.ok) {
                let msg = `HTTP ${res.status}`;
                try {
                    const j = await res.json();
                    if (j && (j.error || j.message)) msg = j.error || j.message;
                } catch (_) {
                }
                throw new Error(msg);
            }
            const blob = await res.blob();
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
        } catch (e) {
            setSeriesMessage(t('merge.failed', '合订本下载失败：{message}',
                {message: String(e && e.message ? e.message : e)}));
        }
    }

    // 「下载合订本」弹窗：选择语言后触发下载；非原文语言额外展示「未译章节回退原文」的提示。
    let mergeDownloadBackdrop = null;

    function openDownloadMergedDialog() {
        if (!isNovelMode() || !state.seriesId) return;
        if (!mergeDownloadBackdrop) {
            mergeDownloadBackdrop = document.createElement('div');
            mergeDownloadBackdrop.className = 'pt-backdrop';
            mergeDownloadBackdrop.innerHTML =
                '<div class="pt-modal" role="dialog" aria-modal="true">' +
                '  <div class="pt-head">' +
                '    <span class="pt-title" data-role="title"></span>' +
                '    <button class="pt-close" type="button" data-role="close" aria-label="close">×</button>' +
                '  </div>' +
                '  <div class="pt-body">' +
                '    <div class="pt-field">' +
                '      <span class="pt-label" data-role="lang-label"></span>' +
                '      <div class="pt-radio-row" data-role="lang-list"></div>' +
                '    </div>' +
                '    <div class="pt-hint" data-role="warning"></div>' +
                '  </div>' +
                '  <div class="pt-foot">' +
                '    <button class="pt-btn" type="button" data-role="cancel"></button>' +
                '    <button class="pt-btn pt-btn-primary" type="button" data-role="confirm"></button>' +
                '  </div>' +
                '</div>';
            document.body.appendChild(mergeDownloadBackdrop);
        }
        const root = mergeDownloadBackdrop;
        const titleEl = root.querySelector('[data-role="title"]');
        const closeBtn = root.querySelector('[data-role="close"]');
        const langLabel = root.querySelector('[data-role="lang-label"]');
        const langList = root.querySelector('[data-role="lang-list"]');
        const warning = root.querySelector('[data-role="warning"]');
        const cancelBtn = root.querySelector('[data-role="cancel"]');
        const confirmBtn = root.querySelector('[data-role="confirm"]');

        titleEl.textContent = t('merge.dialog-title', '下载合订本');
        langLabel.textContent = t('merge.dialog-language', '语言');
        cancelBtn.textContent = t('merge.dialog-cancel', '取消');
        confirmBtn.textContent = t('merge.dialog-confirm', '下载');

        const options = [{value: '', label: t('merge.lang-original', '原文')}];
        (seriesTranslatedLangs || []).forEach(code => {
            if (code) options.push({value: code, label: code});
        });
        langList.innerHTML = options.map((o, i) => (
            '<label class="pt-radio">'
            + '<input type="radio" name="mergeDownloadLang" value="' + escapeHtml(o.value) + '"'
            + (i === 0 ? ' checked' : '') + '>'
            + '<span>' + escapeHtml(o.label) + '</span>'
            + '</label>'
        )).join('');

        const warningText = t('merge.dialog-warning',
            '非原文语言的合订本只会替换已翻译的章节，未翻译章节仍保留原文。' +
            '若希望合订本完全使用所选语言，请先点击「翻译整个系列」翻译全部章节后重新下载。');
        function updateWarning() {
            const checked = langList.querySelector('input[name="mergeDownloadLang"]:checked');
            const isOriginal = !checked || !checked.value;
            warning.textContent = isOriginal ? '' : warningText;
            warning.style.display = isOriginal ? 'none' : '';
        }
        updateWarning();
        langList.querySelectorAll('input[name="mergeDownloadLang"]').forEach(r =>
            r.addEventListener('change', updateWarning));

        function cleanup() {
            root.classList.remove('open');
            closeBtn.onclick = null;
            cancelBtn.onclick = null;
            confirmBtn.onclick = null;
            root.onclick = null;
            document.removeEventListener('keydown', onKey);
        }
        function onKey(e) {
            if (e.key === 'Escape') cleanup();
            else if (e.key === 'Enter') confirmBtn.click();
        }
        closeBtn.onclick = cleanup;
        cancelBtn.onclick = cleanup;
        root.onclick = (e) => { if (e.target === root) cleanup(); };
        confirmBtn.onclick = async () => {
            const checked = langList.querySelector('input[name="mergeDownloadLang"]:checked');
            const lang = checked ? checked.value : '';
            cleanup();
            await downloadMergedVolume(lang);
        };
        document.addEventListener('keydown', onKey);
        root.classList.add('open');
    }

