(function (global) {
    'use strict';

    var queueTypes = global.PixivBatch && global.PixivBatch.queueTypes;
    if (!queueTypes || typeof queueTypes.registerModule !== 'function') return;

    var TYPE = 'example-download';
    var API = '/api/example-download';
    var DATA_SOURCE = Object.freeze({
        id: TYPE,
        displayNamespace: TYPE,
        displayI18nKey: 'source.example',
        order: 900
    });

    function text(key, fallback, values) {
        if (typeof global.bt === 'function') return global.bt(TYPE + ':' + key, fallback, values || {});
        if (global.pageI18n && typeof global.pageI18n.t === 'function') {
            return global.pageI18n.t(TYPE + ':' + key, fallback, values || {});
        }
        return fallback || key;
    }

    function parseInput(value) {
        var raw = String(value == null ? '' : value).trim();
        var match = raw.match(/^(?:https:\/\/example\.invalid\/work\/)?([0-9]{1,18})\/?$/i);
        return match ? {id: match[1], url: 'https://example.invalid/work/' + match[1]} : null;
    }

    function workId(item) {
        var raw = item && (item.exampleId || item.id || (item.typeData && item.typeData.exampleId));
        var parsed = parseInput(raw);
        return parsed ? parsed.id : null;
    }

    function queueId(item) {
        var id = workId(item);
        return id ? TYPE + ':' + id : null;
    }

    function queueMeta(item, context) {
        var id = workId(item);
        return {
            kind: TYPE,
            exampleId: id,
            cancelWorkKey: id,
            canonicalUrl: id ? 'https://example.invalid/work/' + id : '',
            typeData: {
                exampleId: id,
                sourceType: context && context.sourceType ? context.sourceType : 'example.single',
                sourceId: context && context.sourceId ? String(context.sourceId) : id
            }
        };
    }

    async function readJson(response) {
        var body = await response.json().catch(function () { return {}; });
        if (!response.ok) {
            var failure = new Error(body.code || ('HTTP_' + response.status));
            failure.code = body.code || 'example.http-error';
            failure.messageKey = body.messageKey || TYPE + ':error.queue';
            throw failure;
        }
        return body;
    }

    async function processItem(item, context) {
        context.assertActive();
        var id = workId(item);
        if (!id) throw new Error('example.invalid-input');
        context.updateItem({
            status: 'downloading',
            rawStatus: 'processing',
            failureCode: null,
            statusMessageKey: null,
            endTime: null
        });
        try {
            var response = await fetch(API + '/queue', {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
                signal: context.signal,
                body: JSON.stringify({
                    id: id,
                    title: item.rawTitle || item.title || null
                })
            });
            var result = await readJson(response);
            context.assertActive();
            context.updateItem({
                status: 'completed',
                rawStatus: result.code || 'example.completed',
                downloadedCount: 1,
                failureCode: null,
                statusMessageKey: null,
                endTime: new Date().toISOString()
            });
            return result;
        } catch (failure) {
            context.assertActive();
            var messageKey = String(failure && failure.messageKey || '');
            context.updateItem({
                status: 'failed',
                rawStatus: 'failed',
                failureCode: failure.code || 'example.queue-failed',
                statusMessageKey: messageKey.indexOf(TYPE + ':') === 0
                    ? messageKey : TYPE + ':error.queue',
                endTime: new Date().toISOString()
            });
            throw failure;
        }
    }

    function fetchPage(url, signal) {
        return fetch(url, {credentials: 'same-origin', signal: signal})
            .then(readJson);
    }

    function userCardId(index) {
        return 'example-user-' + index;
    }

    function searchCardId(index) {
        return 'example-search-' + index;
    }

    function seriesCardId(index) {
        return 'example-series-' + index;
    }

    function quickGridCardId(prefix, index) {
        return prefix + '-example-' + index;
    }

    function isInQueue(inQueue, item) {
        return !!inQueue && typeof inQueue.has === 'function' && inQueue.has(queueId(item));
    }

    function renderItems(items, target, options) {
        var host = target && target.nodeType === 1 ? target : null;
        var values = Array.isArray(items) ? items : [];
        if (!host) return values;
        var opts = options || {};
        while (host.firstChild) host.removeChild(host.firstChild);
        values.forEach(function (item, index) {
            var row = document.createElement('article');
            var queued = isInQueue(opts.inQueue, item);
            row.className = 'example-download-result' + (queued ? ' in-queue' : '');
            if (typeof opts.cardId === 'function') row.id = opts.cardId(index);
            row.dataset.exampleId = String(item.id || '');
            var title = document.createElement('strong');
            title.textContent = String(item.title || item.id || '');
            row.appendChild(title);
            host.appendChild(row);
        });
        return values;
    }

    function syncQueueState(items, inQueue, cardId) {
        (Array.isArray(items) ? items : []).forEach(function (item, index) {
            var row = document.getElementById(cardId(index));
            if (row && row.classList) row.classList.toggle('in-queue', isInQueue(inQueue, item));
        });
    }

    function renderUser(area, view) {
        return renderItems(view && view.items, area, {
            cardId: userCardId,
            inQueue: view && view.inQueue
        });
    }

    function renderSearch(area, view) {
        var base = Number(view && view.base);
        if (!Number.isFinite(base) || base < 0) base = 0;
        return renderItems(view && view.items, area, {
            cardId: function (index) { return searchCardId(base + index); }
        });
    }

    function syncSearchQueueState(items, inQueue) {
        syncQueueState(items, inQueue, searchCardId);
    }

    function renderSeries(area, view) {
        return renderItems(view && view.items, area, {
            cardId: seriesCardId,
            inQueue: view && view.inQueue
        });
    }

    function renderQuick(items, idPrefix, _summaryHtml, inQueue) {
        var prefix = String(idPrefix || 'quick');
        return renderItems(items, document.getElementById('quick-preview-area'), {
            cardId: function (index) { return quickGridCardId(prefix, index); },
            inQueue: inQueue
        });
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    var descriptor = {
        contractVersion: 1,
        process: processItem,
        import: {
            dataSource: DATA_SOURCE,
            sectionType: TYPE,
            matchUrl: parseInput,
            buildItem: function (match, title, line) {
                var parsed = match && match.id ? match : parseInput(line || match);
                if (!parsed) return null;
                return Object.assign({
                    id: queueId(parsed),
                    exampleId: parsed.id,
                    rawTitle: title || null,
                    title: title || 'Example ' + parsed.id,
                    kind: TYPE,
                    source: 'single-import-' + TYPE
                }, queueMeta(parsed, {sourceType: 'example.single', sourceId: parsed.id}));
            },
            source: 'single-import-' + TYPE
        },
        acquisition: {
            user: {
                dataSource: DATA_SOURCE,
                pageSize: 12,
                initialCursor: '1',
                parseInput: function (value) {
                    var normalized = String(value == null ? '' : value).trim();
                    return normalized || null;
                },
                fetchMeta: function (userId) {
                    return Promise.resolve(String(userId));
                },
                fetchPage: function (userId, context) {
                    var page = Number(context.page) || 1;
                    return fetchPage(API + '/user/' + encodeURIComponent(userId)
                        + '/works?page=' + page + '&pageSize=12', context.signal);
                },
                queueId: queueId,
                cardId: userCardId,
                render: renderUser,
                buildQueueMeta: function (item, context) {
                    return queueMeta(item, {
                        sourceType: 'example.user',
                        sourceId: context.userId
                    });
                }
            },
            search: {
                dataSource: DATA_SOURCE,
                pageSize: 12,
                buildRequest: function (context) {
                    return {
                        endpoint: API + '/search',
                        params: {word: context.word, page: context.page, pageSize: 12},
                        premiumOrder: false,
                        clientFilter: 0
                    };
                },
                buildRangeRequest: function (context) {
                    return {
                        endpoint: API + '/search/range',
                        params: {
                            word: context.word,
                            startPage: context.startPage,
                            endPage: context.endPage,
                            pageSize: 12
                        }
                    };
                },
                queueId: queueId,
                render: renderSearch,
                syncQueueState: syncSearchQueueState,
                buildQueueMeta: function (item, context) {
                    return queueMeta(item, {
                        sourceType: 'example.search',
                        sourceId: context && context.word
                    });
                }
            },
            series: {
                dataSource: DATA_SOURCE,
                pageSize: 12,
                apiPath: function (seriesId, page) {
                    return API + '/series/' + encodeURIComponent(seriesId)
                        + '?page=' + (Number(page) || 1) + '&pageSize=12';
                },
                parseUrl: function (value) {
                    var match = String(value == null ? '' : value).trim()
                        .match(/^(?:https:\/\/example\.invalid\/series\/)?([A-Za-z0-9._-]{1,64})\/?$/i);
                    return match ? {seriesId: match[1]} : null;
                },
                typeLabel: function () {
                    return text('schedule.section.source', 'Example series');
                },
                queueId: queueId,
                cardId: seriesCardId,
                render: renderSeries,
                buildQueueMeta: function (item, order, context) {
                    var meta = queueMeta(item, {
                        sourceType: 'example.series',
                        sourceId: context.seriesId
                    });
                    meta.typeData.seriesId = String(context.seriesId);
                    meta.seriesOrder = order;
                    meta.seriesTitle = context.seriesTitle || '';
                    return meta;
                }
            },
            quick: {
                dataSource: DATA_SOURCE,
                pageSize: 12,
                queueId: queueId,
                gridCardId: quickGridCardId,
                innerCardHtml: function (item) {
                    return '<strong>' + escapeHtml(item.title || item.id || '') + '</strong>';
                },
                render: renderQuick,
                buildQueueMeta: function (item) {
                    return queueMeta(item, {sourceType: 'example.quick', sourceId: 'featured'});
                },
                actions: {
                    'example-featured': {
                        viewType: 'works-list',
                        kind: TYPE,
                        sourceType: 'example.quick',
                        load: async function (_action, context) {
                            var data = await fetchPage(
                                API + '/quick?pageSize=12', context && context.signal);
                            context.assertCurrent();
                            var items = Array.isArray(data.items) ? data.items : [];
                            return context.publishWorks({
                                items: items,
                                total: Number(data.total) || items.length,
                                title: text('source.example', 'Example source'),
                                toolbar: {
                                    showBack: false,
                                    showAdd: items.length > 0,
                                    showSearch: false,
                                    showKindSwitcher: false
                                }
                            });
                        }
                    }
                }
            }
        },
        filters: {
            'example-ready-filter': {
                displayNamespace: TYPE,
                displayI18nKey: 'filter.ready',
                matchExtra: function (item) {
                    return !item || (item.status !== 'failed' && item.rawStatus !== 'failed');
                },
                evaluateSkip: function (item) {
                    return item && (item.status === 'failed' || item.rawStatus === 'failed')
                        ? text('error.queue', 'The example task failed') : null;
                }
            }
        },
        settings: {
            'example-output-setting': {
                cardId: 'example-download-settings-card'
            }
        },
        slots: {
            'settings-card': '<section id="example-download-settings-card" class="setting-item example-download-setting">'
                + '<label for="example-download-output-setting" data-i18n="example-download:setting.output"></label>'
                + '<select id="example-download-output-setting">'
                + '<option value="mock" data-i18n="example-download:slot.settings.mock"></option>'
                + '</select>'
                + '<small data-i18n="example-download:setting.output.help"></small>'
                + '</section>'
        }
    };

    queueTypes.registerModule(function () {
        return {descriptor: descriptor};
    });
})(window);
