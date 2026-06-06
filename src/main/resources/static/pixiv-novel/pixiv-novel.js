const params = new URLSearchParams(location.search);
const novelId = params.get('id');
let pageI18n;
let rerenderPayload = null;
let cachedSeriesNav = null;
let novelView = null;
let availableContentLangs = [];
let activeContentLang = '';
let contentLangCtl = null;

const HEART_DEFAULT_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>';

const collectionState = {
    list: [],
    membership: new Set()
};

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

async function loadSeriesNav() {
    try {
        // 透传当前内容语言：后端按 lang 替换上一章 / 下一章标题与系列名为译后版本，缺失时回退原文。
        const url = `/api/gallery/novel/${encodeURIComponent(novelId)}/series`
            + (activeContentLang ? `?lang=${encodeURIComponent(activeContentLang)}` : '');
        const r = await fetch(url);
        if (!r.ok) return;
        const nav = await r.json();
        if (!nav.seriesId) return;
        cachedSeriesNav = nav;
        renderSeriesNavSet(nav, {
            wrap: 'series-nav',
            prev: 'series-prev',
            index: 'series-index',
            next: 'series-next'
        });
        renderSeriesNavSet(nav, {
            wrap: 'series-nav-bottom',
            prev: 'series-prev-bottom',
            index: 'series-index-bottom',
            next: 'series-next-bottom'
        });
    } catch {}
}

function renderSeriesNavSet(nav, ids) {
    const wrap = document.getElementById(ids.wrap);
    const prev = document.getElementById(ids.prev);
    const next = document.getElementById(ids.next);
    const index = document.getElementById(ids.index);
    if (!wrap || !prev || !next || !index) return;

    wrap.style.display = 'grid';
    if (nav.prev) {
        prev.href = `/pixiv-novel.html?id=${nav.prev.novelId}`;
        prev.classList.remove('disabled-look');
        prev.textContent = pageI18n.t('series.prev', null, { order: nav.prev.seriesOrder, title: nav.prev.title });
    } else {
        prev.classList.add('disabled-look');
        prev.textContent = pageI18n.t('series.prev', null, { order: '-', title: '' });
    }
    if (nav.next) {
        next.href = `/pixiv-novel.html?id=${nav.next.novelId}`;
        next.classList.remove('disabled-look');
        next.textContent = pageI18n.t('series.next', null, { order: nav.next.seriesOrder, title: nav.next.title });
    } else {
        next.classList.add('disabled-look');
        next.textContent = pageI18n.t('series.next', null, { order: '-', title: '' });
    }
    const indexParams = new URLSearchParams({
        type: 'novel',
        seriesId: String(nav.seriesId),
        currentId: String(novelId)
    });
    if (nav.seriesTitle) indexParams.set('seriesTitle', nav.seriesTitle);
    if (nav.currentOrder != null) indexParams.set('order', String(nav.currentOrder));
    index.href = `/pixiv-series.html?${indexParams.toString()}`;
    index.removeAttribute('target');
    index.removeAttribute('rel');
    index.textContent = pageI18n.t('series.index') + (nav.seriesTitle ? ` · ${nav.seriesTitle}` : '');
}

function escapeHtml(s) {
    return PixivNovelRender.escapeHtml(s);
}

// ---------- 听书（TTS） ----------
function setupTts() {
    if (!window.PixivNovelTts) return;
    const contentEl = document.getElementById('content-card');
    const hasReadable = contentEl && contentEl.querySelector('p, h2.novel-chapter');
    if (!hasReadable) return; // 正文加载失败 / 无可朗读内容时不显示听书入口
    document.getElementById('ttsToggle').style.display = '';
    const data = rerenderPayload ? rerenderPayload.data : null;
    // 听书语言：显示译文时用所选内容语言，否则用作品原始语言
    const language = activeContentLang || (data ? (data.language || data.xLanguage || '') : '');
    // 「富感情朗读（多角色）」作为听书引擎之一并入同一控制条；narrationLang 用所选内容语言（空=原文），
    // 与脚本 / 渲染同源。引擎可用性 + 管理员可见由 TTS 控制器自行探测，未配置时该引擎选项禁用。
    PixivNovelTts.attach({ i18n: pageI18n, contentEl, toast, language, novelId, narrationLang: activeContentLang });
}

// ---------- Collections ----------
async function loadCollectionState() {
    try {
        const [listRes, ofRes] = await Promise.all([
            fetch('/api/collections', { credentials: 'same-origin' }),
            fetch(`/api/collections/novels/of/${encodeURIComponent(novelId)}`, { credentials: 'same-origin' })
        ]);
        if (listRes.ok) {
            const data = await listRes.json();
            collectionState.list = data.collections || [];
        }
        if (ofRes.ok) {
            const data = await ofRes.json();
            collectionState.membership = new Set(data.collectionIds || []);
        }
        document.getElementById('heartBtn').style.display = '';
        updateHeart();
    } catch (e) {
        // silent — heart stays hidden
    }
}

function updateHeart() {
    const btn = document.getElementById('heartBtn');
    const liked = collectionState.membership.size > 0;
    btn.classList.toggle('liked', liked);
    document.getElementById('heartLabel').textContent = liked
        ? pageI18n.t('button.favorite-active', '已收藏 ({count})', { count: collectionState.membership.size })
        : pageI18n.t('button.favorite', '收藏');
}

function iconHtml(c) {
    return c.iconExt
        ? `<img src="/api/collections/${c.id}/icon?v=${c.createdTime}" alt="">`
        : HEART_DEFAULT_SVG;
}

async function refreshMembership() {
    try {
        const r = await fetch(`/api/collections/novels/of/${encodeURIComponent(novelId)}`, { credentials: 'same-origin' });
        if (!r.ok) return;
        const data = await r.json();
        collectionState.membership = new Set(data.collectionIds || []);
        updateHeart();
    } catch (e) {}
}

async function refreshCollections() {
    try {
        const r = await fetch('/api/collections', { credentials: 'same-origin' });
        if (!r.ok) return;
        const data = await r.json();
        collectionState.list = data.collections || [];
    } catch (e) {}
}

async function openAddToCollectionModal() {
    await refreshCollections();
    await refreshMembership();
    renderAddToCollectionList();
    document.getElementById('modalAddToCollection').classList.add('open');
}

function closeAddToCollectionModal() {
    document.getElementById('modalAddToCollection').classList.remove('open');
    updateHeart();
}

function renderAddToCollectionList() {
    const list = document.getElementById('addToCollectionList');
    if (!collectionState.list.length) {
        list.innerHTML = `<div style="padding:24px; text-align:center; color:var(--muted); font-size:13px">${escapeHtml(pageI18n.t('status.no-collections', '暂无收藏夹，请先新建'))}</div>`;
        return;
    }
    list.innerHTML = collectionState.list.map(c => {
        const checked = collectionState.membership.has(c.id) ? 'checked' : '';
        return `<label class="collection-row" data-id="${c.id}">
            <input type="checkbox" ${checked}>
            <div class="collection-row-icon">${iconHtml(c)}</div>
            <div class="collection-row-name">${escapeHtml(c.name)}</div>
            <span class="collection-count">${c.novelCount ?? 0}</span>
        </label>`;
    }).join('');
    list.querySelectorAll('.collection-row').forEach(row => {
        const id = Number(row.dataset.id);
        const cb = row.querySelector('input[type="checkbox"]');
        cb.addEventListener('change', async () => {
            try {
                if (cb.checked) {
                    const r = await fetch(`/api/collections/${id}/novels/${encodeURIComponent(novelId)}`, { method: 'POST', credentials: 'same-origin' });
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    collectionState.membership.add(id);
                } else {
                    const r = await fetch(`/api/collections/${id}/novels/${encodeURIComponent(novelId)}`, { method: 'DELETE', credentials: 'same-origin' });
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    collectionState.membership.delete(id);
                }
                updateHeart();
            } catch (e) {
                cb.checked = !cb.checked;
                toast(pageI18n.t('toast.action-failed', '操作失败'), 'error');
            }
        });
    });
}

document.getElementById('heartBtn').addEventListener('click', openAddToCollectionModal);
document.getElementById('addToCollectionClose').addEventListener('click', closeAddToCollectionModal);
document.getElementById('addToCollectionDone').addEventListener('click', closeAddToCollectionModal);
document.getElementById('modalAddToCollection').addEventListener('click', e => {
    if (e.target.id === 'modalAddToCollection') closeAddToCollectionModal();
});

document.getElementById('quickCreateBtn').addEventListener('click', async () => {
    const input = document.getElementById('quickCreateName');
    const name = input.value.trim();
    if (!name) return;
    const btn = document.getElementById('quickCreateBtn');
    btn.disabled = true;
    try {
        const r = await fetch('/api/collections', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name })
        });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const c = await r.json();
        input.value = '';
        collectionState.list.push(c);
        collectionState.list.sort((a, b) => (a.sortOrder - b.sortOrder) || (a.id - b.id));
        const link = await fetch(`/api/collections/${c.id}/novels/${encodeURIComponent(novelId)}`, { method: 'POST', credentials: 'same-origin' });
        if (link.ok) collectionState.membership.add(c.id);
        renderAddToCollectionList();
        toast(pageI18n.t('toast.created', '已创建'), 'success');
    } catch (e) {
        toast(pageI18n.t('toast.create-failed', '创建失败'), 'error');
    } finally {
        btn.disabled = false;
    }
});

function toast(msg, type) {
    const c = document.getElementById('toastContainer');
    if (!c) return;
    const el = document.createElement('div');
    el.className = 'toast' + (type ? ' ' + type : '');
    el.textContent = msg;
    c.appendChild(el);
    setTimeout(() => el.remove(), 2800);
}

// ---------- Admin only (delete + AI translate) ----------
async function setupAdminMode() {
    try {
        const res = await fetch('/api/admin/invites/access-check', { credentials: 'same-origin' });
        if (!res.ok) return;
        document.body.classList.add('admin-mode');
        const btn = document.getElementById('deleteNovelBtn');
        if (btn) btn.style.display = '';
        // 「AI 翻译」入口仅在后端已配置文本模型时展示：未配置时翻译无法工作，隐藏入口。
        const translateBtn = document.getElementById('aiTranslateBtn');
        if (translateBtn && window.PixivTranslate && PixivTranslate.isAiConfigured) {
            const aiConfigured = await PixivTranslate.isAiConfigured();
            if (aiConfigured) {
                translateBtn.style.display = '';
                translateBtn.addEventListener('click', openTranslateDialog);
            }
        }
    } catch (_) { /* not admin */ }
}

// ---------- AI translate (admin only) ----------
async function openTranslateDialog() {
    if (!window.PixivTranslate || !novelId) return;
    // 已有进行中的翻译：直接重新弹出当前进度，不再发新请求
    if (PixivTranslate.hasActiveJob()) {
        PixivTranslate.showActiveJob();
        return;
    }
    const choice = await PixivTranslate.openDialog({
        i18n: pageI18n, series: false, novelId: novelId, onToast: toast
    });
    if (!choice) return;
    const seriesIdForMerge = cachedSeriesNav && cachedSeriesNav.seriesId
        ? cachedSeriesNav.seriesId : null;
    const outcome = await PixivTranslate.runSingleNovel({
        i18n: pageI18n, novelId: novelId, choice: choice,
        seriesId: seriesIdForMerge
    });
    if (!outcome || outcome.cancelled) return;
    if (outcome.error) {
        toast(pageI18n.t('translate:toast.failed', '翻译失败：{message}',
            { message: String(outcome.error.message || outcome.error) }), 'error');
        return;
    }
    if (outcome.mergeFailed) {
        // 翻译已成功落库，仅合订本生成失败：提示后仍走后续刷新逻辑
        toast(pageI18n.t('translate:toast.merge-failed', '合订本生成失败：{message}',
            { message: String(outcome.mergeFailed.message || outcome.mergeFailed) }), 'error');
    }
    const resp = outcome.result;
    if (resp.status === PixivTranslate.STATUS_INVALID_LANGUAGE) {
        toast(resp.message || pageI18n.t('translate:toast.invalid-language', '该语言不存在或无法识别'), 'error');
    } else if (resp.status === PixivTranslate.STATUS_SAME_LANGUAGE) {
        // 原文已是目标语言：无译文变体，提示后不切换内容语言、不跳转。
        toast(resp.message || pageI18n.t('translate:toast.same-language',
            '原文已是目标语言，已跳过翻译'), 'success');
    } else if (resp.status === PixivTranslate.STATUS_OK || resp.status === PixivTranslate.STATUS_SKIPPED) {
        toast(resp.message || pageI18n.t('translate:toast.success', '翻译完成'), 'success');
        if (resp.langCode) {
            if (window.PixivContentLang) PixivContentLang.setStored(resp.langCode);
            const p = new URLSearchParams(location.search);
            p.set('lang', resp.langCode);
            location.search = p.toString();
        }
    } else {
        toast(resp.message || pageI18n.t('translate:toast.failed', '翻译失败：{message}', { message: '' }), 'error');
    }
}

function openDeleteNovelModal() {
    document.getElementById('modalDeleteNovel').classList.add('open');
}

function closeDeleteNovelModal() {
    document.getElementById('modalDeleteNovel').classList.remove('open');
}

async function confirmDeleteNovel() {
    if (!novelId) return;
    const btn = document.getElementById('deleteNovelConfirm');
    btn.disabled = true;
    try {
        const r = await fetch(`/api/gallery/novel/${encodeURIComponent(novelId)}`, { method: 'DELETE', credentials: 'same-origin' });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        toast(pageI18n.t('delete.success', '已删除'), 'success');
        setTimeout(() => { window.location.href = '/pixiv-novel-gallery.html?view=all'; }, 600);
    } catch (e) {
        btn.disabled = false;
        closeDeleteNovelModal();
        toast(pageI18n.t('delete.failed', '删除失败'), 'error');
    }
}

document.getElementById('deleteNovelBtn').addEventListener('click', openDeleteNovelModal);
document.getElementById('deleteNovelClose').addEventListener('click', closeDeleteNovelModal);
document.getElementById('deleteNovelCancel').addEventListener('click', closeDeleteNovelModal);
document.getElementById('deleteNovelConfirm').addEventListener('click', confirmDeleteNovel);
document.getElementById('modalDeleteNovel').addEventListener('click', e => {
    if (e.target.id === 'modalDeleteNovel') closeDeleteNovelModal();
});

setupAdminMode();
loadAll();
