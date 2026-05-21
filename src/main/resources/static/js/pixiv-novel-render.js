/* eslint-disable */
/**
 * Pixiv 小说标记客户端渲染器。与后端 NovelMarkupParser 保持一致的语义：
 *   [newpage]                — 分段
 *   [chapter:Title]          — 章节标题
 *   [[rb:base > ruby]]       — 注音
 *   [[jumpuri:text > url]]   — 外链
 *   [jump:N]                 — 翻页提示（仅作弱视觉提示，不会真的跳转）
 *   [uploadedimage:id]       — 上传图片占位
 *   [pixivimage:id[-page]]   — Pixiv 插图占位
 */
(function (global) {
    'use strict';

    const INLINE = /\[\[rb:([^>\]]+?)\s*>\s*([^\]]+?)]]|\[\[jumpuri:([^>\]]+?)\s*>\s*([^\]]+?)]]|\[jump:(\d+)]|\[uploadedimage:(\d+)]|\[pixivimage:(\d+(?:-\d+)?)]/g;

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, (c) => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[c]));
    }

    function imageFigure(dataAttr, id, label, url, hideMissing) {
        if (!url) {
            // 缺失资源：hideMissing 时完全不渲染（本地离线模式），否则保留占位提示。
            if (hideMissing) return '';
            return `<figure class="novel-image" ${dataAttr}="${escapeHtml(id)}"><span class="novel-image-placeholder">${escapeHtml(label)}</span></figure>`;
        }
        return `<figure class="novel-image" ${dataAttr}="${escapeHtml(id)}"><img src="${escapeHtml(url)}" alt="${escapeHtml(label)}" loading="lazy"></figure>`;
    }

    function imageLabel(resolver, kind, id) {
        const labels = resolver && resolver.labels ? resolver.labels : null;
        if (labels && typeof labels[kind] === 'function') {
            return labels[kind](id);
        }
        return kind === 'uploadedImage' ? `[uploadedimage:${id}]` : `[pixivimage:${id}]`;
    }

    function renderInline(text, resolver) {
        let out = '';
        let last = 0;
        let m;
        INLINE.lastIndex = 0;
        while ((m = INLINE.exec(text)) !== null) {
            out += escapeHtml(text.slice(last, m.index)).replace(/\n/g, '<br>');
            if (m[1] !== undefined) {
                out += `<ruby>${escapeHtml(m[1].trim())}<rt>${escapeHtml(m[2].trim())}</rt></ruby>`;
            } else if (m[3] !== undefined) {
                out += `<a href="${escapeHtml(m[4].trim())}" rel="noopener noreferrer" target="_blank">${escapeHtml(m[3].trim())}</a>`;
            } else if (m[5] !== undefined) {
                out += `<span class="novel-jump">↗ p.${escapeHtml(m[5])}</span>`;
            } else if (m[6] !== undefined) {
                const url = resolver && typeof resolver.uploadedImage === 'function'
                    ? resolver.uploadedImage(m[6]) : null;
                out += imageFigure('data-uploaded-image', m[6], imageLabel(resolver, 'uploadedImage', m[6]), url,
                    resolver && resolver.hideMissingImages);
            } else if (m[7] !== undefined) {
                const url = resolver && typeof resolver.pixivImage === 'function'
                    ? resolver.pixivImage(m[7]) : null;
                out += imageFigure('data-pixiv-image', m[7], imageLabel(resolver, 'pixivImage', m[7]), url,
                    resolver && resolver.hideMissingImages);
            }
            last = m.index + m[0].length;
        }
        out += escapeHtml(text.slice(last)).replace(/\n/g, '<br>');
        return out;
    }

    function render(raw, resolver) {
        if (!raw) return '<section class="novel-page"></section>';
        const normalized = raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
        const lines = normalized.split('\n');
        const blocks = [];
        let buf = [];
        const flush = () => {
            if (buf.length === 0) return;
            blocks.push({ kind: 'p', text: buf.join('\n') });
            buf = [];
        };
        for (const line of lines) {
            const chapter = /^\s*\[chapter:(.+?)]\s*$/.exec(line);
            if (chapter) {
                flush();
                blocks.push({ kind: 'chapter', text: chapter[1].trim() });
                continue;
            }
            if (/^\s*\[newpage]\s*$/.test(line)) {
                flush();
                blocks.push({ kind: 'newpage' });
                continue;
            }
            buf.push(line);
        }
        flush();

        let html = '<section class="novel-page">';
        let pageOpen = true;
        for (const b of blocks) {
            if (b.kind === 'chapter') {
                html += `<h2 class="novel-chapter">${escapeHtml(b.text)}</h2>`;
            } else if (b.kind === 'newpage') {
                if (pageOpen) html += '</section>';
                html += '<section class="novel-page">';
                pageOpen = true;
            } else if (b.kind === 'p') {
                const paragraphs = b.text.split(/\n{2,}/);
                for (const p of paragraphs) {
                    if (!p) continue;
                    html += `<p>${renderInline(p, resolver)}</p>`;
                }
            }
        }
        if (pageOpen) html += '</section>';
        return html;
    }

    global.PixivNovelRender = { render: render, escapeHtml: escapeHtml };
})(window);
