'use strict';
async function loadAll() {
    pageI18n = await PixivI18n.create({ namespaces: ['novel', 'common', 'translate', 'narration'] });
    pageI18n.apply();
    await PixivLangSwitcher.mount({
        mountPoint: document.getElementById('langSwitcherAnchor'),
        i18n: pageI18n,
        onChange: (next) => {
            pageI18n = next;
            pageI18n.apply();
            rerenderDynamic();
            if (contentLangCtl) contentLangCtl.relabel(pageI18n);
            if (window.PixivNovelTts) PixivNovelTts.setI18n(next);
            if (window.PixivNovelNarration) PixivNovelNarration.setI18n(next);
        }
    });
    PixivTheme.mount({ mountPoint: document.getElementById('langSwitcherAnchor') });
    if (!novelId) {
        document.getElementById('loading').textContent = pageI18n.t('status.missing-id');
        return;
    }
    document.getElementById('btn-pixiv').href = `https://www.pixiv.net/novel/show.php?id=${encodeURIComponent(novelId)}`;
    try {
        const localOk = await loadNovel();
        if (!localOk) {
            // 本地无此小说：与图片详情页一致，仅提示不存在，绝不访问 Pixiv
            showNotDownloaded();
            return;
        }
        await loadSeriesNav();
        revealNovel();
    } catch (e) {
        document.getElementById('loading').textContent = pageI18n.t('status.novel-load-failed', null, { message: String(e && e.message ? e.message : e) });
    }
}

function revealNovel() {
    document.getElementById('loading').style.display = 'none';
    document.getElementById('root').style.display = 'block';
    setupTts();
    loadCollectionState();
}

async function loadNovel() {
    const r = await fetch(`/api/gallery/novel/${encodeURIComponent(novelId)}`);
    if (!r.ok) return false;
    const view = await r.json();
    await renderLocal(view);
    return true;
}

// 本地没有该小说：与图片详情页保持一致，仅提示「不存在」，不访问 Pixiv、不提供联网加载入口。
function showNotDownloaded() {
    const el = document.getElementById('loading');
    el.classList.remove('empty');
    el.style.display = 'block';
    el.innerHTML = '';
    const box = document.createElement('div');
    box.className = 'not-downloaded';
    const title = document.createElement('div');
    title.className = 'nd-title';
    title.textContent = pageI18n.t('status.not-downloaded', '该小说尚未下载到本地');
    box.appendChild(title);
    el.appendChild(box);
}

function showCover() {
    const wrap = document.getElementById('novel-cover');
    const img = document.getElementById('novel-cover-img');
    img.src = `/api/gallery/novel/${encodeURIComponent(novelId)}/cover`;
    wrap.style.display = 'block';
}

function rerenderDynamic() {
    if (!rerenderPayload) return;
    const d = rerenderPayload.data;
    renderBadges({ xRestrict: d.xRestrict, isAi: d.isAi, isOriginal: d.isOriginal });
    renderMetaRow({ wordCount: d.wordCount, textLength: d.textLength, readingTimeSeconds: d.readingTimeSeconds, language: d.xLanguage, uploadTimestamp: d.time });
    renderDescription(d.description);
    renderTags(d.tags || []);
    if (cachedSeriesNav) {
        renderSeriesNavSet(cachedSeriesNav, { wrap: 'series-nav', prev: 'series-prev', index: 'series-index', next: 'series-next' });
        renderSeriesNavSet(cachedSeriesNav, { wrap: 'series-nav-bottom', prev: 'series-prev-bottom', index: 'series-index-bottom', next: 'series-next-bottom' });
    }
    updateHeart();
}

async function renderLocal(view) {
    rerenderPayload = { kind: 'local', data: view };
    novelView = view;
    availableContentLangs = Array.isArray(view.translatedLanguages) ? view.translatedLanguages : [];
    activeContentLang = resolveInitialContentLang(availableContentLangs);
    if (view.coverExt) showCover();
    document.getElementById('novel-title').textContent = view.title || '';
    document.getElementById('novel-author').innerHTML = view.authorId
        ? `<a href="https://www.pixiv.net/users/${view.authorId}" target="_blank">${escapeHtml(view.authorName || '')}</a>`
        : escapeHtml(view.authorName || pageI18n.t('status.unknown-author'));
    renderBadges({ xRestrict: view.xRestrict, isAi: view.isAi, isOriginal: view.isOriginal });
    renderMetaRow({
        wordCount: view.wordCount,
        textLength: view.textLength,
        readingTimeSeconds: view.readingTimeSeconds,
        language: view.xLanguage,
        uploadTimestamp: view.time
    });
    renderDescription(view.description);
    renderTags(view.tags || []);
    mountContentLangSwitcher();
    await loadContent(activeContentLang);
}

// 内容显示语言（独立于界面语言）：?lang 参数优先，其次全局记忆的内容语言，且都必须存在于本作品已有译文中。
function resolveInitialContentLang(langs) {
    const urlLang = params.get('lang');
    if (urlLang && langs.indexOf(urlLang) !== -1) return urlLang;
    if (window.PixivContentLang) {
        const stored = PixivContentLang.getStored();
        if (stored && langs.indexOf(stored) !== -1) return stored;
    }
    return '';
}

function mountContentLangSwitcher() {
    const anchor = document.getElementById('contentLangAnchor');
    if (!anchor || !window.PixivContentLang) return;
    contentLangCtl = PixivContentLang.mount({
        mountPoint: anchor,
        i18n: pageI18n,
        languages: availableContentLangs,
        current: activeContentLang,
        onChange: (lang) => switchContentLang(lang)
    });
}

// 切换内容语言：带 ?lang 参数整页重载，使正文与听书都在该语言下重新初始化（界面语言不受影响）。
function switchContentLang(lang) {
    const p = new URLSearchParams(location.search);
    if (lang) p.set('lang', lang); else p.delete('lang');
    location.search = p.toString();
}

// 正文取自本地权威源 novels.raw_content（或所选语言的 AI 译文）；内嵌图只用本地落盘的，缺失静默不显示，全程不访问 Pixiv。
async function loadContent(lang) {
    const localIds = new Set((novelView && novelView.embeddedImageIds) || []);
    const url = `/api/gallery/novel/${encodeURIComponent(novelId)}/content`
        + (lang ? `?lang=${encodeURIComponent(lang)}` : '');
    try {
        const r = await fetch(url);
        if (r.ok) {
            const data = await r.json();
            renderContent(data.content || '', buildImageResolver({ local: localIds, hideMissing: true }));
            applyTitleForLang(data);
            applyDescriptionForLang(data);
            return;
        }
    } catch {}
    document.getElementById('content-card').innerHTML = `<div class="empty">${pageI18n.t('status.load-failed')}</div>`;
}

// 切换内容语言时同步替换显示标题：返回 translated=true 且带 translatedTitle 时用译后标题，否则回退原文标题。
function applyTitleForLang(data) {
    const titleEl = document.getElementById('novel-title');
    if (!titleEl || !novelView) return;
    const fallback = novelView.title || '';
    if (data && data.translated && data.translatedTitle && data.translatedTitle.trim()) {
        titleEl.textContent = data.translatedTitle;
    } else {
        titleEl.textContent = fallback;
    }
}

// 切换内容语言时同步替换显示简介：返回 translated=true 且带 translatedDescription 时用译后简介，否则回退原文简介。
function applyDescriptionForLang(data) {
    if (!novelView) return;
    const fallback = novelView.description;
    if (data && data.translated && data.translatedDescription && data.translatedDescription.trim()) {
        renderDescription(data.translatedDescription);
    } else {
        renderDescription(fallback);
    }
}

function renderBadges(o) {
    const parts = [];
    if (o.xRestrict === 1) parts.push('<span class="badge r18">R-18</span>');
    if (o.xRestrict === 2) parts.push('<span class="badge r18g">R-18G</span>');
    if (o.isAi) parts.push('<span class="badge ai">AI</span>');
    if (o.isOriginal) parts.push(`<span class="badge original">${escapeHtml(pageI18n.t('meta.original'))}</span>`);
    document.getElementById('novel-badges').innerHTML = parts.join('');
}

function formatReadingDuration(seconds) {
    const total = Math.floor(Number(seconds || 0));
    if (!Number.isFinite(total) || total <= 0) return '';
    const hour = Math.floor(total / 3600);
    const minute = Math.floor((total % 3600) / 60);
    const second = total % 60;
    const parts = [];
    if (hour > 0) parts.push(pageI18n.t('duration.hour', '{count} 小时', { count: hour }));
    if (minute > 0) parts.push(pageI18n.t('duration.minute', '{count} 分钟', { count: minute }));
    if (second > 0 && hour === 0) parts.push(pageI18n.t('duration.second', '{count} 秒', { count: second }));
    if (!parts.length) parts.push(pageI18n.t('duration.second', '{count} 秒', { count: total }));
    return parts.join(' ');
}

function formatReadingTime(seconds) {
    const text = formatReadingDuration(seconds);
    return text ? pageI18n.t('meta.reading-time', '预计阅读：{time}', { time: text }) : '';
}

function renderMetaRow(o) {
    const parts = [];
    if (o.wordCount != null && o.wordCount > 0) {
        parts.push(`<span>${escapeHtml(pageI18n.t('meta.word-count', null, { count: o.wordCount }))}</span>`);
    }
    if (o.textLength != null && o.textLength > 0 && o.textLength !== o.wordCount) {
        parts.push(`<span>${escapeHtml(pageI18n.t('meta.text-length', null, { count: o.textLength }))}</span>`);
    }
    const readingText = formatReadingTime(o.readingTimeSeconds);
    if (readingText) parts.push(`<span>${escapeHtml(readingText)}</span>`);
    if (o.language) {
        parts.push(`<span>${escapeHtml(pageI18n.t('meta.language', null, { lang: o.language }))}</span>`);
    }
    if (o.uploadTimestamp) {
        const d = new Date(o.uploadTimestamp);
        if (!isNaN(d.getTime())) {
            parts.push(`<span>${escapeHtml(pageI18n.t('meta.upload', null, { time: d.toISOString().slice(0, 16).replace('T', ' ') }))}</span>`);
        }
    }
    document.getElementById('novel-meta-row').innerHTML = parts.join('');
}

function renderDescription(text) {
    const el = document.getElementById('novel-description');
    if (text && text.trim()) {
        // Pixiv 简介本身是受限 HTML（<br>, <a>, <strong> 等），与 pixiv-artwork.html 保持一致
        // 直接用 innerHTML 让 <br>/<br/> 等渲染为真实换行；并把链接强制在新标签页打开。
        el.innerHTML = text;
        el.querySelectorAll('a').forEach(a => {
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
        });
    } else {
        el.textContent = pageI18n.t('status.no-description');
    }
}

function renderTags(tags) {
    if (!tags || tags.length === 0) {
        document.getElementById('novel-tags').innerHTML = `<span class="tag">${escapeHtml(pageI18n.t('status.no-tags'))}</span>`;
        return;
    }
    document.getElementById('novel-tags').innerHTML = tags.map(t => {
        const name = t.name || '';
        const translated = t.translatedName
            ? `<span class="translated">${escapeHtml(t.translatedName)}</span>`
            : '';
        const tagId = Number(t.tagId);
        const canFilter = (Number.isFinite(tagId) && tagId > 0) || name;
        if (!canFilter) {
            return `<span class="tag">${escapeHtml(name)}${translated}</span>`;
        }
        return `<a class="tag tag-link" href="${escapeHtml(buildNovelGalleryTagFilterHref(t))}">${escapeHtml(name || ('#' + tagId))}${translated}</a>`;
    }).join('');
}

function buildNovelGalleryTagFilterHref(tag) {
    const params = new URLSearchParams();
    params.set('view', 'all');
    const tagId = Number(tag.tagId);
    if (Number.isFinite(tagId) && tagId > 0) params.set('tagIds', String(tagId));
    if (tag.name) params.set('tagName', tag.name);
    if (tag.translatedName) params.set('tagTranslatedName', tag.translatedName);
    return `/pixiv-novel-gallery.html?${params.toString()}`;
}

function renderContent(raw, resolver) {
    document.getElementById('content-card').innerHTML = PixivNovelRender.render(raw, resolver);
}

/**
 * 构造内嵌图片解析器（纯本地）：
 * - opts.local: Set<string>，已落盘到本地的 [uploadedimage:id] ID 集合，命中时走本地接口；
 * - opts.hideMissing: true 时，本地没有的内嵌图静默不渲染（不访问 Pixiv），否则保留占位文字。
 */
function buildImageResolver(opts) {
    const local = opts && opts.local ? opts.local : null;
    return {
        hideMissingImages: !!(opts && opts.hideMissing),
        labels: {
            uploadedImage(id) {
                return pageI18n.t('render.uploaded-image', null, { id });
            },
            pixivImage(id) {
                return pageI18n.t('render.pixiv-image', null, { id });
            }
        },
        uploadedImage(id) {
            if (local && local.has(String(id))) {
                return `/api/gallery/novel/${encodeURIComponent(novelId)}/embed/${encodeURIComponent(id)}`;
            }
            return null;
        },
        pixivImage() { return null; }
    };
}


// ---- PixivNovel facade ----
window.PixivNovel.content = window.PixivNovel.content || {};
window.PixivNovel.content = Object.assign(window.PixivNovel.content, { loadAll, revealNovel, loadNovel, showNotDownloaded, showCover, rerenderDynamic, renderLocal, resolveInitialContentLang, mountContentLangSwitcher, switchContentLang, loadContent, applyTitleForLang, applyDescriptionForLang, renderBadges, formatReadingDuration, formatReadingTime, renderMetaRow, renderDescription, renderTags, buildNovelGalleryTagFilterHref, renderContent, buildImageResolver });
