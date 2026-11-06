(function () {
    'use strict';
    let activeRequest = null;
    function cancel() { activeRequest?.abort(); }

    function buildForm(variants, initialVariant, t, single) {
        const content = document.createElement('div');
        content.className = 'novel-export-form';
        function option(control, value, text) {
            const node = document.createElement('option');
            node.value = value;
            node.textContent = text;
            control.appendChild(node);
        }
        function select(key, name, options) {
            const label = document.createElement('label');
            label.textContent = t(key);
            const control = document.createElement('select');
            control.className = 'pixiv-feedback-input';
            control.name = name;
            options.forEach(([value, text]) => option(control, value, text));
            label.appendChild(control);
            content.appendChild(label);
            return control;
        }
        function languageLabel(value) {
            try { return new Intl.DisplayNames([document.documentElement.lang || 'en'], {type: 'language'}).of(value); }
            catch (_) { return value; }
        }
        const language = select('export.language', 'language', [
            ['', t('export.original')],
            ...[...new Set(variants.map(value => value.split('@')[0]))].map(value => [value, languageLabel(value)])
        ]);
        const engine = select('export.engine', 'engine', []);
        const hint = document.createElement('p');
        hint.className = 'novel-export-hint';
        hint.textContent = t('export.variants-hint');
        if (!single) content.appendChild(hint);
        const groupBy = single ? null : select('manage.export-group-by', 'groupBy', [
            ['author', t('manage.export-group-author')], ['id', t('manage.export-group-id')]
        ]);
        const format = single ? null : select('manage.export-format', 'format', [['zip', 'ZIP']]);
        const deletion = document.createElement('label');
        deletion.className = 'pixiv-feedback-choice';
        const deleteAfter = document.createElement('input');
        deleteAfter.type = 'checkbox';
        deleteAfter.name = 'deleteAfter';
        const caption = document.createElement('span');
        caption.textContent = t('manage.export-delete-source');
        deletion.append(deleteAfter, caption);
        if (!single) content.appendChild(deletion);
        const deletionHint = document.createElement('p');
        deletionHint.className = 'novel-export-hint';
        deletionHint.setAttribute('aria-live', 'polite');
        if (!single) content.appendChild(deletionHint);
        function updateDeletion() {
            deleteAfter.disabled = !!language.value;
            if (deleteAfter.disabled) deleteAfter.checked = false;
            deletionHint.hidden = !language.value && !deleteAfter.checked;
            deletionHint.textContent = t(language.value ? 'export.keep-source' : 'manage.export-delete-hint');
        }
        function updateEngines(preferred) {
            engine.replaceChildren();
            const matches = variants.filter(value => value.split('@')[0] === language.value);
            matches.forEach(value => {
                const source = value.split('@')[1] || 'unknown';
                option(engine, value, ['ai', 'youdao', 'libretranslate', 'unknown'].includes(source)
                    ? t('export.engine.' + source) : source);
            });
            if (!matches.length) option(engine, '', t('export.not-applicable'));
            engine.disabled = !matches.length;
            if (matches.includes(preferred)) engine.value = preferred;
            updateDeletion();
        }
        language.value = variants.includes(initialVariant) ? initialVariant.split('@')[0] : '';
        updateEngines(initialVariant);
        language.addEventListener('change', () => {
            const source = engine.value.split('@')[1] || '';
            updateEngines(variants.find(value => value === language.value + (source ? '@' + source : '')));
        });
        deleteAfter.addEventListener('change', updateDeletion);
        return {content, readValue: () => single ? {variant: engine.value || null} : ({
            variant: engine.value || null, groupBy: groupBy.value,
            format: format.value, deleteAfter: deleteAfter.checked
        })};
    }

    async function open(options) {
        if (activeRequest) return;
        const request = new AbortController();
        activeRequest = request;
        const single = options.novelId != null;
        const t = key => options.i18n.t('novel-gallery:' + key);
        async function post(path, payload) {
            const response = await fetch(path, {
                method: 'POST', credentials: 'same-origin', signal: request.signal,
                headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)
            });
            if (!response.ok) {
                const failure = new Error('export_failed');
                failure.status = response.status;
                throw failure;
            }
            return response.status === 204 ? null : response.json();
        }
        try {
            const available = single ? {variants: options.variants || []}
                : await post('/api/gallery/novels/export/options', options.selection);
            if (request.signal.aborted) return;
            const form = buildForm(available.variants, options.initialVariant, t, single);
            const choice = await window.PixivFeedback.form({
                title: t(single ? 'export.single-title' : 'manage.export-title'),
                message: t(single ? 'export.single-message' : 'export.message'),
                ...form, confirmLabel: t(single ? 'export.download' : 'manage.export-start'),
                cancelLabel: options.i18n.t('common:button.cancel'), signal: request.signal
            });
            if (!choice || request.signal.aborted) return;
            options.toast(t('export.generating'));
            if (single) {
                const params = new URLSearchParams();
                if (choice.variant) params.set('variant', choice.variant);
                const response = await fetch(`/api/novel/${encodeURIComponent(options.novelId)}/export?${params}`, {
                    credentials: 'same-origin', signal: request.signal
                });
                if (!response.ok) {
                    const failure = new Error('export_failed');
                    failure.status = response.status;
                    throw failure;
                }
                const blob = await response.blob();
                if (request.signal.aborted) return;
                const disposition = response.headers.get('content-disposition') || '';
                const filename = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition);
                const url = URL.createObjectURL(blob);
                try {
                    const link = document.createElement('a');
                    link.href = url;
                    link.download = filename ? decodeURIComponent(filename[1]) : `${options.novelId}.epub`;
                    document.body.appendChild(link);
                    link.click();
                    link.remove();
                } finally { setTimeout(() => URL.revokeObjectURL(url), 1000); }
                options.toast(t('export.downloaded'), 'success');
                return;
            }
            const data = await post('/api/gallery/novels/export', {...options.selection, ...choice});
            if (request.signal.aborted) return;
            if (!data?.archiveToken) { options.toast(t('manage.export-empty'), 'error'); return; }
            activeRequest = null;
            options.toast(t('manage.export-started'), 'success');
            window.PixivSideModules.trackArchive(data.archiveToken, {
                onReady: () => {
                    options.toast(t('manage.export-ready'), 'success');
                    if (choice.deleteAfter) options.onDeleted?.();
                },
                onFailed: status => options.toast(t(status === 'empty' ? 'manage.export-empty' : 'manage.export-failed'), 'error')
            });
            options.onSubmitted?.();
            window.PixivSideModules.openTasks();
        } catch (failure) {
            if (!request.signal.aborted) options.toast(t([404, 409].includes(failure.status) ? 'export.unavailable'
                : failure.status === 413 ? (single ? 'export.single-too-large' : 'export.too-large') : 'manage.export-failed'), 'error');
        } finally {
            if (activeRequest === request) activeRequest = null;
        }
    }

    window.PixivNovelExport = {open, cancel};
    window.addEventListener('pagehide', cancel);
})();
