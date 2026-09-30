function applyUserscriptPanelStyle(container) {
    container.classList.add('pixiv-download-panel');
    if (document.getElementById('pixiv-download-panel-style')) return;
    const style = document.createElement('style');
    style.id = 'pixiv-download-panel-style';
    const dark = `color-scheme:dark;
        --pixiv-panel-bg:#202124;--pixiv-panel-surface:#303134;
        --pixiv-panel-text:#f1f3f4;--pixiv-panel-muted:#bdc1c6;--pixiv-panel-line:#686b70;
        --pixiv-panel-info:#8ab4f8;--pixiv-panel-success:#81c995;--pixiv-panel-danger:#f28b82;
        --pixiv-panel-warning:#fdd663;--pixiv-panel-warning-bg:#40351c;--pixiv-panel-teal:#78d9cc;`;
    style.textContent = `
        .pixiv-download-panel {
            color-scheme:light;
            --pixiv-panel-bg:#fff;--pixiv-panel-surface:#f8f9fa;
            --pixiv-panel-text:#333;--pixiv-panel-muted:#62666b;--pixiv-panel-line:#ccc;
            --pixiv-panel-info:#0066cc;--pixiv-panel-success:#187a35;--pixiv-panel-danger:#c62828;
            --pixiv-panel-warning:#805400;--pixiv-panel-warning-bg:#fff4db;--pixiv-panel-teal:#08796e;
            width:420px;color:var(--pixiv-panel-text);overflow-wrap:anywhere;
        }
        @media (prefers-color-scheme:dark) {
            :root:not([data-theme="light"]) .pixiv-download-panel { ${dark} }
        }
        :root[data-theme="dark"] .pixiv-download-panel { ${dark} }
        .pixiv-download-panel, .pixiv-download-panel * { box-sizing:border-box; }
        .pixiv-download-panel :is(input,select,textarea) {
            min-width:0;max-width:100%;color:var(--pixiv-panel-text);
            background:var(--pixiv-panel-bg);border:1px solid var(--pixiv-panel-line);
        }
        .pixiv-download-panel :is(input,textarea)::placeholder { color:var(--pixiv-panel-muted);opacity:1; }
        .pixiv-download-settings div { flex-wrap:wrap; }
        .pixiv-download-settings :is(input[type="text"],select) { min-width:min(140px,100%); }
        .pixiv-download-panel button {
            color:var(--pixiv-panel-text);background:var(--pixiv-panel-surface);
            border:1px solid var(--pixiv-panel-line);padding:4px 8px;font-family:inherit;
        }
        .pixiv-download-panel :is(button,input,select,textarea,summary):focus-visible {
            outline:2px solid var(--pixiv-panel-info);outline-offset:2px;
        }
        .pixiv-download-options p { margin:10px 0;line-height:1.5; }
        .pixiv-download-options summary { cursor:pointer; }
    `;
    document.head.appendChild(style);
}

function mountUserscriptDownloadOptions(container, legacyR18 = false) {
    applyUserscriptPanelStyle(container);
    const options = UserscriptDownloadOptions;
    const text = options.text;
    const settings = options.read(legacyR18);
    const details = document.createElement('details');
    details.className = 'pixiv-download-options';
    details.style.cssText = 'margin:8px 0;padding:8px;border:1px solid var(--pixiv-panel-line);border-radius:6px;background:var(--pixiv-panel-surface);font-size:12px;min-width:0;';
    const summary = document.createElement('summary');
    summary.textContent = text('title');
    details.appendChild(summary);
    const note = document.createElement('p');
    note.textContent = text('live');
    details.appendChild(note);
    function field(key, type, values, label = key) {
        const row = document.createElement('label');
        row.style.cssText = 'display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin:8px 0;';
        const caption = document.createElement('span');
        caption.textContent = text(label);
        caption.style.cssText = 'flex:1 1 130px;';
        const input = document.createElement(values ? 'select' : 'input');
        input.dataset.downloadOption = key;
        input.style.cssText = 'box-sizing:border-box;min-width:0;max-width:100%;font:inherit;';
        if (values) for (const value of values) {
            const option = document.createElement('option');
            option.value = value;
            option.textContent = ['txt', 'html', 'epub'].includes(value) ? value.toUpperCase() : text(value);
            input.appendChild(option);
        } else {
            input.type = type;
            if (type === 'number') { input.min = '0'; input.step = '1'; input.max = String(Number.MAX_SAFE_INTEGER); }
        }
        if (type === 'checkbox') input.checked = settings[key];
        else { input.value = settings[key] ?? ''; input.style.width = '160px'; }
        if (key === 'fileNameTemplate') { input.maxLength = 512; input.title = text('template-hint'); }
        if (key === 'autoTranslateLanguage') input.maxLength = 100;
        if (key === 'autoTranslateSegmentSize') input.max = '1000000';
        input.addEventListener('change', () => {
            if (key === 'collection') return;
            if (!input.reportValidity()) return;
            const current = options.read(legacyR18);
            current[key] = type === 'checkbox' ? input.checked : input.value;
            GM_setValue(options.STORAGE_KEY, current);
        });
        row.append(caption, input);
        details.appendChild(row);
        return input;
    }
    field('content', 'select', ['all', 'safe', 'r18plus', 'r18', 'r18g']);
    field('ai', 'select', ['all', 'exclude', 'only']);
    field('type', 'select', ['all', 'illust', 'manga', 'ugoira']);
    field('tagsExact', 'text');
    field('tagsFuzzy', 'text');
    for (const prefix of ['bookmark', 'page', 'words']) {
        for (const bound of ['Min', 'Max']) {
            const input = field(prefix + bound, 'number', null, prefix);
            input.setAttribute('aria-label', text(prefix) + ' — ' + text(bound.toLowerCase()));
            input.placeholder = text(bound.toLowerCase());
        }
    }
    field('fileNameTemplate', 'text');
    const collection = field('collection', 'select', ['none']);
    collection.options[0].value = '';
    collection.value = '';
    const selected = options.selectedCollectionId();
    if (selected) {
        const option = document.createElement('option');
        option.value = String(selected);
        option.textContent = String(selected);
        collection.appendChild(option);
        collection.value = String(selected);
    }
    collection.disabled = !selected;
    collection.addEventListener('change', () => options.selectCollection(collection.value));
    const refresh = document.createElement('button');
    refresh.type = 'button';
    refresh.textContent = text('refresh');
    details.appendChild(refresh);
    const message = document.createElement('p');
    message.setAttribute('role', 'status');
    details.appendChild(message);
    refresh.addEventListener('click', async () => {
        refresh.disabled = true;
        collection.disabled = true;
        message.textContent = '';
        try {
            const rows = await options.collections();
            if (!details.isConnected) return;
            collection.replaceChildren();
            for (const row of [{id: '', name: text('none')}, ...rows]) {
                const option = document.createElement('option');
                option.value = String(row.id);
                option.textContent = String(row.name || row.id);
                collection.appendChild(option);
            }
            collection.value = String(options.selectedCollectionId() || '');
            collection.disabled = false;
        } catch (_) {
            collection.value = '';
            options.selectCollection(null);
            message.textContent = text('unavailable');
        } finally { refresh.disabled = false; }
    });
    const admin = document.createElement('p');
    admin.textContent = text('admin');
    details.appendChild(admin);
    field('autoTranslate', 'checkbox');
    field('autoTranslateLanguage', 'text');
    field('autoTranslateSegmentSize', 'number');
    field('autoTranslateMerge', 'checkbox');
    field('autoTranslateMergeFormat', 'select', ['epub', 'txt', 'html']);
    container.appendChild(details);
    // 原分级开关的保存值仍作为首次使用的默认值，界面只保留一份分级控件。
    const oldRating = container.querySelector('#r18-only, #pbd-r18-only');
    if (oldRating) oldRating.parentElement.style.display = 'none';
    container.style.boxSizing = 'border-box';
    container.style.maxHeight = `calc(100vh - ${(parseFloat(container.style.top) || 20) + 20}px)`;
    container.style.overflowY = 'auto';
    container.style.minWidth = '0';
    container.style.maxWidth = 'calc(100vw - 96px)';
    const notice = document.createElement('div');
    notice.className = 'pixiv-download-options-notice';
    notice.setAttribute('role', 'status');
    container.appendChild(notice);
    return details;
}

function showUserscriptDownloadNotice(message) {
    const notice = document.querySelector('#pixiv-java-downloader-ui .pixiv-download-options-notice');
    if (notice) notice.textContent = message;
}
