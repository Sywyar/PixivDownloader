(function (global) {
    'use strict';

    var loadedModules = {};

    function basePath() {
        return typeof global.BASE !== 'undefined' ? global.BASE : '';
    }

    function loadScript(url) {
        if (!url) return Promise.resolve();
        if (loadedModules[url]) return loadedModules[url];
        loadedModules[url] = new Promise(function (resolve) {
            var script = document.createElement('script');
            script.src = url;
            script.async = false;
            script.onload = function () { resolve(true); };
            script.onerror = function () {
                console.warn('[PixivUiSlots] module load failed:', url);
                resolve(false);
            };
            (document.head || document.documentElement).appendChild(script);
        });
        return loadedModules[url];
    }

    function manifestUrl(options) {
        var prefix = options && options.targetPrefix ? String(options.targetPrefix) : '';
        var url = basePath() + '/api/web/ui-slots';
        return prefix ? url + '?targetPrefix=' + encodeURIComponent(prefix) : url;
    }

    function prepareHosts() {
        if (global.PixivVue && typeof global.PixivVue.prepareSlotHosts === 'function') {
            global.PixivVue.prepareSlotHosts(document);
        }
    }

    function removeConsumedTemplates(slots) {
        var targets = new Set((slots || []).map(function (slot) { return slot && slot.target; }).filter(Boolean));
        if (!targets.size) return;
        document.querySelectorAll('template[data-qt-slot]').forEach(function (tpl) {
            if (targets.has(tpl.getAttribute('data-qt-slot'))) tpl.remove();
        });
    }

    async function bootstrap(options) {
        var slots = [];
        try {
            var res = await fetch(manifestUrl(options), { credentials: 'same-origin' });
            if (!res.ok) return [];
            slots = await res.json();
        } catch (e) {
            console.warn('[PixivUiSlots] manifest fetch failed:', e);
            return [];
        }
        if (!Array.isArray(slots) || !slots.length) return [];
        prepareHosts();
        for (var i = 0; i < slots.length; i++) {
            await loadScript(slots[i].moduleUrl);
        }
        if (typeof global.pageI18n !== 'undefined' && global.pageI18n) {
            try { global.pageI18n.apply(document.body); } catch (_) {}
        }
        removeConsumedTemplates(slots);
        return slots;
    }

    global.PixivUiSlots = {
        bootstrap: bootstrap
    };
})(window);
