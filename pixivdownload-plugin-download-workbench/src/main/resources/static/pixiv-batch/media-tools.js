'use strict';

(async () => {
    const i18n = await window.PixivI18n.create({namespaces: ['batch']});
    i18n.apply(document);
    const t = (key, values) => i18n.t(key, key, values);
    const element = id => document.getElementById('media-' + id);
    const settings = {imageFormats: 'original', ugoiraFormats: 'webp'};
    let token = null;
    let timer = null;
    let running = false;
    let busy = false;
    let disposed = false;
    const invalidate = () => { token = null; element('start').disabled = true; };
    window.PixivMediaSettings.mount(element('formats'), settings, invalidate, t, true, true);
    element('ids').addEventListener('input', invalidate);
    element('thumbnails').addEventListener('change', invalidate);
    element('animations').addEventListener('change', invalidate);
    async function api(path, data) {
        const response = await fetch('/api/download/media/' + path, {
            method: data === undefined ? 'GET' : 'POST', credentials: 'same-origin',
            headers: {'Content-Type': 'application/json'},
            body: data === undefined ? undefined : JSON.stringify(data)
        });
        if (!response.ok) {
            const error = await response.json().catch(() => ({}));
            throw new Error(error.error || t('media.tools.failed'));
        }
        const text = await response.text();
        return text ? JSON.parse(text) : null;
    }
    function buttons() {
        element('preview').disabled = running || busy;
        element('start').disabled = running || busy || !token;
        element('cancel').disabled = !running;
        element('ids').disabled = running || busy;
        element('thumbnails').disabled = running || busy;
        element('animations').disabled = running || busy;
        element('formats').querySelectorAll('input').forEach(input => { input.disabled = running || busy; });
    }
    async function action(callback) {
        if (busy) return;
        busy = true;
        buttons();
        try { await callback(); }
        catch (error) { element('status').textContent = error.message; }
        finally { busy = false; buttons(); }
    }
    element('preview').addEventListener('click', () => action(async () => {
        invalidate();
        const ids = element('ids').value.split(/[\s,，]+/).filter(Boolean);
        if (!ids.length || ids.some(id => !/^\d+$/.test(id) || !Number.isSafeInteger(Number(id)))) {
            throw new Error(t('media.tools.scope-help'));
        }
        const preview = await api('preview', {
            artworkIds: ids.map(Number), imageFormats: settings.imageFormats,
            ugoiraFormats: element('animations').checked ? settings.ugoiraFormats : null,
            repairThumbnails: element('thumbnails').checked
        });
        token = preview.token;
        element('preview-files').textContent = preview.files.map(file => file.artworkId + ' / ' + file.fileName).join('\n');
        element('status').textContent = t('media.tools.ready', {count: preview.files.length});
    }));
    async function refresh() {
        clearTimeout(timer);
        try {
            const status = await api('status');
            running = status.state === 'running';
            element('status').textContent = t('media.tools.state.' + status.state) + ' — '
                + t('media.tools.progress', {completed: status.completed, total: status.total, failed: status.failed});
            if (status.failures.length) {
                element('preview-files').textContent = t('media.tools.failed') + '\n'
                    + status.failures.map(file => file.artworkId + ' / ' + (file.page + 1)).join('\n');
            }
            buttons();
            if (running && !disposed) timer = setTimeout(refresh, 1000);
        } catch (error) { element('status').textContent = error.message; }
    }
    element('start').addEventListener('click', () => action(async () => {
        await api('start', {token});
        invalidate();
        await refresh();
    }));
    element('cancel').addEventListener('click', () => action(async () => {
        await api('cancel', {});
        await refresh();
    }));
    element('check').addEventListener('click', async () => {
        element('check').disabled = true;
        element('capabilities').replaceChildren();
        try {
            const report = await api('capabilities');
            element('command').textContent = report.command;
            for (const capability of report.capabilities) {
                const line = document.createElement('li');
                line.textContent = t('media.capability.' + capability.name) + ': '
                    + t(capability.available ? 'media.tools.available' : 'media.tools.unavailable');
                element('capabilities').append(line);
            }
        } catch (error) { element('command').textContent = error.message; }
        finally { element('check').disabled = false; }
    });
    window.addEventListener('pagehide', () => { disposed = true; clearTimeout(timer); }, {once: true});
    await refresh();
})();
