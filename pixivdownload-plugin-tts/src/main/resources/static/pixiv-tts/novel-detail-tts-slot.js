PixivUiSlots.registerModule('/pixiv-tts/novel-detail-tts-slot.js', async function (root, context) {
    'use strict';
    var global = window;
    var pageI18n = context.i18n;
    var toast = (message, kind) => context.emit('message', {message, kind: kind || 'info'});
    var initialized = false;
    var BASE = '/pixiv-tts/';

    function loadScript(url) { return context.loadScript(url); }
    function loadStyle(url) { context.loadStyle(url); }

    function hasReadableContent() {
        var contentEl = context.region('content');
        return !!(contentEl && contentEl.querySelector('p, h2.novel-chapter'));
    }

    function renderButton(slotHost) {
        slotHost.innerHTML = '<button class="btn" id="ttsToggle" type="button" style="display:none" '
                + 'data-i18n="tts:listen">Listen</button>';
    }

    function ensureControls() {
        if (document.getElementById('ttsBar') || document.getElementById('modalNarrationCast')) throw new Error('TTS_CONTROLS_IN_USE');
        var template = document.createElement('template');
        template.innerHTML =
            '<div class="tts-bar" id="ttsBar" style="display:none;">' +
            '  <div class="tts-progressbar" id="ttsProgressBar" data-i18n-title="tts:seek" title="Seek">' +
            '    <div class="tts-progressbar-fill" id="ttsProgressFill"></div>' +
            '  </div>' +
            '  <div class="narration-subtitle" id="ttsSubtitle" style="display:none;">-</div>' +
            '  <div class="tts-bar-main">' +
            '    <button class="tts-icon-btn tts-play" id="ttsPlayPause" type="button" data-i18n-title="tts:play" title="Play">▶</button>' +
            '    <button class="tts-icon-btn" id="ttsPrev" type="button" data-i18n-title="tts:prev" title="Previous">⏮</button>' +
            '    <button class="tts-icon-btn" id="ttsNext" type="button" data-i18n-title="tts:next" title="Next">⏭</button>' +
            '    <button class="tts-icon-btn" id="ttsStop" type="button" data-i18n-title="tts:stop" title="Stop">⏹</button>' +
            '    <span class="tts-progress" id="ttsProgress">0 / 0</span>' +
            '    <button class="tts-icon-btn tts-gear" id="ttsSettingsToggle" type="button" data-i18n-title="tts:settings" title="Settings">⚙</button>' +
            '    <button class="tts-icon-btn tts-close" id="ttsClose" type="button" data-i18n-title="tts:close" title="Close">×</button>' +
            '  </div>' +
            '  <div class="tts-settings" id="ttsSettings" style="display:flex;">' +
            '    <div class="tts-field">' +
            '      <label data-i18n="tts:engine">Engine</label>' +
            '      <select id="ttsEngine">' +
            '        <option value="browser" data-i18n="tts:engine.browser">Browser</option>' +
            '        <option value="edge" data-i18n="tts:engine.edge">Edge neural voice</option>' +
            '        <option value="narration" id="ttsEngineNarrationOpt" style="display:none;">Narration</option>' +
            '      </select>' +
            '    </div>' +
            '    <div class="tts-field" id="ttsVoiceField">' +
            '      <label data-i18n="tts:voice">Voice</label>' +
            '      <select id="ttsVoice"></select>' +
            '    </div>' +
            '    <div class="tts-field tts-field-rate" id="ttsRateField">' +
            '      <label data-i18n="tts:rate">Rate</label>' +
            '      <input type="range" id="ttsRate" min="-50" max="100" step="10" value="0">' +
            '      <span class="tts-rate-value" id="ttsRateValue">0%</span>' +
            '    </div>' +
            '    <button class="btn btn-sm" id="ttsRegenerate" type="button" style="display:none;" data-i18n="narration:settings.analysis-settings">Analysis settings</button>' +
            '    <label class="tts-field tts-checkbox-field" id="ttsShowSpeakersField" style="display:none;">' +
            '      <input type="checkbox" id="ttsShowSpeakers">' +
            '      <span data-i18n="narration:settings.show-speakers">Show speakers</span>' +
            '    </label>' +
            '    <div class="tts-hint" id="ttsEngineHint"></div>' +
            '  </div>' +
            '</div>' +
            '<div class="modal-backdrop" id="modalNarrationCast">' +
            '  <div class="modal narration-cast-modal">' +
            '    <div class="modal-head">' +
            '      <span class="modal-title" data-i18n="narration:cast.title">Cast and voices</span>' +
            '      <button class="modal-close" id="narrationCastClose" type="button">×</button>' +
            '    </div>' +
            '    <div class="modal-body">' +
            '      <div class="narration-conflicts" id="narrationConflicts" style="display:none;"></div>' +
            '      <div class="narration-cast-list" id="narrationCastList"></div>' +
            '    </div>' +
            '    <div class="modal-foot">' +
            '      <button class="btn" id="narrationCastDone" data-i18n="common:button.done" type="button">Done</button>' +
            '    </div>' +
            '  </div>' +
            '</div>';
        var controls = Array.from(template.content.children);
        context.onCleanup(() => controls.forEach(node => node.remove()));
        document.body.append(template.content);
    }

    async function loadControllers() {
        var urls = [
            BASE + 'tts/tts-store.js',
            BASE + 'tts/tts-voices.js',
            BASE + 'tts/tts-ui.js',
            BASE + 'tts/tts-engine-browser.js',
            BASE + 'tts/tts-engine-edge.js',
            BASE + 'pixiv-novel-narration-core.js',
            BASE + 'pixiv-novel-narration-marks.js',
            BASE + 'pixiv-novel-narration-playback.js',
            BASE + 'pixiv-novel-narration-cast.js',
            BASE + 'pixiv-novel-narration-dialog.js',
            BASE + 'pixiv-novel-narration.js',
            BASE + 'pixiv-novel-tts.js'
        ];
        for (var i = 0; i < urls.length; i++) {
            if (!await loadScript(urls[i])) return false;
        }
        return true;
    }

    function attach() {
        if (!global.PixivNovelTts) return;
        var contentEl = context.region('content');
        var language = context.properties.language;
        var id = context.properties.novelId;
        var toastFn = toast;
        global.PixivNovelTts.attach({
            i18n: pageI18n, signal: context.signal, fetch: context.fetch,
            contentEl: contentEl,
            toast: toastFn,
            language: language,
            novelId: id,
            narrationLang: context.properties.contentLanguage || ''
        });
        var toggle = document.getElementById('ttsToggle');
        if (toggle) toggle.style.display = '';
    }

    async function init() {
        if (initialized) return;
        initialized = true;
        if (!hasReadableContent()) return;
        loadStyle(BASE + 'pixiv-tts.css');
        renderButton(root);
        ensureControls();
        if (typeof pageI18n !== 'undefined' && pageI18n) {
            try { pageI18n.apply(document.body); } catch (_) {}
        }
        if (!await loadControllers()) return;
        if (context.signal.aborted) return;
        const controller = global.PixivNovelTts;
        context.onCleanup(() => controller?.destroy());
        attach();
    }

    await init();
    return {update() { pageI18n = context.i18n; global.PixivNovelTts?.setI18n(pageI18n); global.PixivNovelNarration?.setI18n(pageI18n); }, destroy() { root.replaceChildren(); }};
});
