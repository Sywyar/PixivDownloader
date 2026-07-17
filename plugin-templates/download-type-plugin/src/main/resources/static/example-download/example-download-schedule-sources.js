(function (global) {
    'use strict';

    var MODULE_URL = '/example-download/example-download-schedule-sources.js';
    var SOURCE_TYPE = 'example-download.ids';
    var TYPE = 'example-download';
    var runtime = global.PixivBatch && global.PixivBatch.scheduleSources;
    if (!runtime || typeof runtime.registerModule !== 'function') return;

    function text(key, fallback, values) {
        if (typeof global.bt === 'function') {
            return global.bt(TYPE + ':' + key, fallback, values || {});
        }
        if (global.pageI18n && typeof global.pageI18n.t === 'function') {
            return global.pageI18n.t(TYPE + ':' + key, fallback, values || {});
        }
        return fallback || key;
    }

    function parseId(value) {
        var raw = String(value == null ? '' : value).trim();
        var match = raw.match(/^(?:https:\/\/example\.invalid\/work\/)?([0-9]{1,18})\/?$/i);
        return match ? match[1] : null;
    }

    function paramsOf(task) {
        var raw = task && (task.params || task.definition || task.definitionJson || task.paramsJson);
        if (typeof raw === 'string') {
            try { raw = JSON.parse(raw); } catch (_) { return null; }
        }
        return raw && typeof raw === 'object' ? raw : null;
    }

    function inputFromContext(context) {
        if (!context || typeof context.acquisitionInput !== 'function') return null;
        var first = String(context.acquisitionInput('single-import') || '').split(/\r?\n/)[0];
        return parseId(first && first.split('|')[0]);
    }

    runtime.registerModule(MODULE_URL, function (api) {
        var declared = api.descriptors.some(function (descriptor) {
            return descriptor.sourceType === SOURCE_TYPE;
        });
        if (!declared) return;
        api.registerSource(SOURCE_TYPE, {
            matches: function (context) {
                return context && context.mode === 'single-import'
                    && context.workType === TYPE && !!inputFromContext(context);
            },
            capture: function (context) {
                var id = inputFromContext(context);
                if (!id) {
                    throw new Error(text('schedule.error.input',
                        'The current input cannot be saved as an example schedule'));
                }
                return {
                    params: {id: id},
                    workType: TYPE
                };
            },
            restore: function (task, context) {
                var params = paramsOf(task);
                var id = params && parseId(params.id);
                if (!id) {
                    throw new Error(text('schedule.error.input',
                        'The example schedule definition is invalid'));
                }
                if (!context || typeof context.restoreAcquisition !== 'function'
                        || !context.restoreAcquisition('single-import',
                            'https://example.invalid/work/' + id)) {
                    throw new Error(text('schedule.error.input',
                        'The example schedule input cannot be restored'));
                }
                return {mode: 'single-import', params: {id: id}, kind: TYPE};
            },
            summary: function (task) {
                var params = paramsOf(task);
                var id = params && parseId(params.id);
                if (!id) {
                    throw new Error(text('schedule.error.input',
                        'The example schedule definition is invalid'));
                }
                return {
                    kind: TYPE,
                    sections: [{
                        title: text('schedule.section.source', 'Source snapshot'),
                        rows: [[text('schedule.field.id', 'Example id'), id]]
                    }]
                };
            }
        });
    });
})(window);
