(function (global) {
    'use strict';

    var initialized = false;
    var loaded = {};
    var BASE = '/pixiv-tts/';

    function loadScript(url) {
        if (loaded[url]) return loaded[url];
        loaded[url] = new Promise(function (resolve) {
            var script = document.createElement('script');
            script.src = url;
            script.async = false;
            script.onload = function () { resolve(true); };
            script.onerror = function () {
                console.warn('[TTS] script load failed:', url);
                resolve(false);
            };
            (document.head || document.documentElement).appendChild(script);
        });
        return loaded[url];
    }

    function loadStyle(url) {
        if (document.querySelector('link[href="' + url + '"]')) return;
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = url;
        document.head.appendChild(link);
    }

    function host() {
        if (global.PixivVue && typeof global.PixivVue.anchorFor === 'function') {
            return global.PixivVue.anchorFor('novel-detail-tts');
        }
        return document.querySelector('[data-vue-slot="novel-detail-tts"]')
                || document.getElementById('novel-detail-tts');
    }

    function hasReadableContent() {
        var contentEl = document.getElementById('content-card');
        return !!(contentEl && contentEl.querySelector('p, h2.novel-chapter'));
    }

    function renderButton(slotHost) {
        slotHost.innerHTML = '<button class="btn" id="ttsToggle" type="button" style="display:none" '
                + 'data-i18n="tts:listen">Listen</button>';
    }

    function ensureControls() {
        if (document.getElementById('ttsBar')) return;
        document.body.insertAdjacentHTML('beforeend',
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
            '</div>');
    }

    async function loadControllers() {
        var urls = [
            BASE + 'tts/tts-store.js',
            BASE + 'tts/tts-voices.js',
            BASE + 'tts/tts-ui.js',
            BASE + 'tts/tts-engine-browser.js',
            BASE + 'tts/tts-engine-edge.js',
            BASE + 'pixiv-novel-narration.js',
            BASE + 'pixiv-novel-tts.js'
        ];
        for (var i = 0; i < urls.length; i++) {
            await loadScript(urls[i]);
        }
    }

    function attach() {
        if (!global.PixivNovelTts) return;
        var contentEl = document.getElementById('content-card');
        var data = (typeof rerenderPayload !== 'undefined' && rerenderPayload) ? rerenderPayload.data : null;
        var language = (typeof activeContentLang !== 'undefined' && activeContentLang)
                || (data ? (data.language || data.xLanguage || '') : '');
        var id = typeof novelId !== 'undefined' ? novelId : '';
        var toastFn = typeof toast === 'function' ? toast : function () {};
        global.PixivNovelTts.attach({
            i18n: typeof pageI18n !== 'undefined' ? pageI18n : null,
            contentEl: contentEl,
            toast: toastFn,
            language: language,
            novelId: id,
            narrationLang: typeof activeContentLang !== 'undefined' ? activeContentLang : ''
        });
        var toggle = document.getElementById('ttsToggle');
        if (toggle) toggle.style.display = '';
    }

    async function init() {
        if (initialized) return;
        initialized = true;
        var slotHost = host();
        if (!slotHost || !hasReadableContent()) return;
        loadStyle(BASE + 'pixiv-tts.css');
        renderButton(slotHost);
        ensureControls();
        if (typeof pageI18n !== 'undefined' && pageI18n) {
            try { pageI18n.apply(document.body); } catch (_) {}
        }
        await loadControllers();
        attach();
    }

    init();
})(window);
