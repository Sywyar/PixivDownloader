(function (global) {
    'use strict';

    var queueTypes = global.PixivBatch && global.PixivBatch.queueTypes;
    if (!queueTypes || typeof queueTypes.registerUiModule !== 'function') return;
    var TYPE = 'example-download';

    queueTypes.registerUiModule(function (context) {
        function supportsQuick() {
            return typeof context.supports === 'function' && context.supports(TYPE, 'quick');
        }

        if (!supportsQuick()) return;
        var slot = (Array.isArray(context.slots) ? context.slots : []).find(function (candidate) {
            return candidate.target === 'quick-actions-mine';
        });
        var mounted = null;
        var mounting = false;

        function render() {
            if (!context.isActive() || !supportsQuick() || !slot || mounting || mounted
                    || !global.PixivVue || typeof global.PixivVue.mountUiSlot !== 'function') return;
            mounting = true;
            global.PixivVue.mountUiSlot(slot, {
                template: '<div class="example-download-vue-slot">'
                    + '<button type="button" class="btn btn-green quick-action" data-quick="example-featured" '
                    + '@click="activate" data-i18n="example-download:slot.quick.action"></button>'
                    + '<span v-if="showReady()" data-i18n="example-download:slot.quick.ready"></span>'
                    + '</div>',
                setup: function () {
                    var Vue = global.Vue;
                    var ready = Vue && typeof Vue.ref === 'function' ? Vue.ref(false) : {value: false};
                    return {
                        activate: function () {
                            if (!supportsQuick()) return;
                            ready.value = true;
                            context.dispatchQuickAction('example-featured');
                        },
                        showReady: function () { return ready.value === true; }
                    };
                }
            }).then(function (handle) {
                mounting = false;
                if (!context.isActive() || !supportsQuick()) {
                    if (handle && handle.app) handle.app.unmount();
                    return;
                }
                mounted = handle;
            }).catch(function () {
                mounting = false;
            });
        }

        function onSlotsRendered() {
            render();
        }

        global.addEventListener('pixivbatch:slotsrendered', onSlotsRendered);
        context.onCleanup(function () {
            global.removeEventListener('pixivbatch:slotsrendered', onSlotsRendered);
            if (mounted && mounted.app) mounted.app.unmount();
            mounted = null;
        });
        render();
    });
})(window);
