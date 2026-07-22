'use strict';
(function () {
    const runtime = window.PixivBatch && window.PixivBatch.scheduleSources;
    if (!runtime) return;

    const MODULE_URL = '/pixiv-douyin-download/douyin-schedule-sources.js';
    const WORK_TYPE = 'douyin';
    const DEFAULT_FETCH_LIMIT = 100;
    const MAX_FETCH_LIMIT = 5000;
    const FAVORITE_FOLDER_PREFIX = 'favorite-folder:';
    const SOURCE = Object.freeze({
        USER: 'douyin.user',
        SEARCH: 'douyin.search',
        COLLECTION: 'douyin.collection',
        MUSIC: 'douyin.music',
        ACCOUNT_OWN: 'douyin.account.own-works',
        ACCOUNT_LIKED: 'douyin.account.liked-works',
        ACCOUNT_FAVORITE: 'douyin.account.favorite-works',
        ACCOUNT_FAVORITE_FOLDER: 'douyin.account.favorite-folder',
        ACCOUNT_FAVORITE_COLLECTION: 'douyin.account.favorite-collection'
    });
    const ACCOUNT_SOURCES = new Set([
        SOURCE.ACCOUNT_OWN,
        SOURCE.ACCOUNT_LIKED,
        SOURCE.ACCOUNT_FAVORITE
    ]);
    const FETCH_LIMIT_PRESENTATION = Object.freeze({
        namespace: 'douyin',
        watermarkHintKey: 'schedule.fetch-limit.hint.per-run',
        perRunHintKey: 'schedule.fetch-limit.hint.per-run',
        fullFetchConfirmKey: 'schedule.fetch-limit.confirm.full-fetch'
    });

    function t(key, fallback, args) {
        return bt('douyin:' + key, fallback, args || {});
    }

    function parseParams(task) {
        try {
            const raw = (task || {}).paramsJson || (task || {}).definitionJson || '{}';
            const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
            return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
        } catch (e) {
            return null;
        }
    }

    function exactKeys(value, expected) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
        const actual = Object.keys(value).sort();
        const wanted = expected.slice().sort();
        return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
    }

    function boundedText(value, maxLength) {
        const normalized = value == null ? '' : String(value).trim();
        return normalized && normalized.length <= maxLength ? normalized : '';
    }

    function normalizedSource(sourceType, value) {
        const source = value && typeof value === 'object' && !Array.isArray(value) ? value : null;
        if (sourceType === SOURCE.USER && exactKeys(source, ['userId'])) {
            const userId = boundedText(source.userId, 256);
            return userId ? {userId} : null;
        }
        if (sourceType === SOURCE.SEARCH && exactKeys(source, ['keyword'])) {
            const keyword = boundedText(source.keyword, 200);
            return keyword ? {keyword} : null;
        }
        if ((sourceType === SOURCE.COLLECTION || sourceType === SOURCE.ACCOUNT_FAVORITE_COLLECTION)
            && exactKeys(source, ['collectionId'])) {
            const collectionId = boundedText(source.collectionId, 256);
            return collectionId ? {collectionId} : null;
        }
        if (sourceType === SOURCE.MUSIC && exactKeys(source, ['musicId'])) {
            const musicId = boundedText(source.musicId, 256);
            return musicId ? {musicId} : null;
        }
        if (sourceType === SOURCE.ACCOUNT_FAVORITE_FOLDER && exactKeys(source, ['folderId'])) {
            const folderId = boundedText(source.folderId, 256);
            return folderId ? {folderId} : null;
        }
        return ACCOUNT_SOURCES.has(sourceType) && exactKeys(source, []) ? {} : null;
    }

    function normalizedDefinition(sourceType, value) {
        if (!exactKeys(value, ['source', 'fetchLimit'])) return null;
        const source = normalizedSource(sourceType, value.source);
        const fetchLimit = Number(value.fetchLimit);
        if (!source || !Number.isSafeInteger(fetchLimit)
            || fetchLimit < 0 || fetchLimit > MAX_FETCH_LIMIT) return null;
        return {source, fetchLimit};
    }

    function normalizedQuickSource(context) {
        const value = context && context.quickSource;
        if (!value || typeof value !== 'object') return null;
        const sourceType = String(value.sourceType || value.type || '').trim();
        if (!Object.values(SOURCE).includes(sourceType)) return null;
        const source = normalizedSource(sourceType,
            value.source && typeof value.source === 'object' ? value.source : {});
        if (!source) return null;
        return {
            sourceType,
            source,
            label: value.label || ''
        };
    }

    function parseUserId(raw) {
        const value = String(raw || '').trim();
        if (/^[A-Za-z0-9._-]{6,256}$/.test(value)) return value;
        const match = value.match(/(?:https?:\/\/)?(?:www\.)?douyin\.com\/user\/([^/?#\s]+)/i);
        if (!match) return '';
        try {
            return boundedText(decodeURIComponent(match[1]), 256);
        } catch (e) {
            return '';
        }
    }

    function parseSeriesValue(raw) {
        const value = String(raw || '').trim();
        if (!value) return null;
        if (value.startsWith(FAVORITE_FOLDER_PREFIX)) {
            const folderId = boundedText(value.substring(FAVORITE_FOLDER_PREFIX.length), 256);
            return folderId
                ? {sourceType: SOURCE.ACCOUNT_FAVORITE_FOLDER, source: {folderId}}
                : null;
        }
        if (value.startsWith('music:')) {
            const musicId = boundedText(value.substring('music:'.length), 256);
            return musicId ? {sourceType: SOURCE.MUSIC, source: {musicId}} : null;
        }
        const match = value.match(/(?:https?:\/\/)?(?:www\.)?douyin\.com\/(collection|mix|music)\/([^/?#\s]+)/i);
        if (match) {
            let id;
            try {
                id = boundedText(decodeURIComponent(match[2]), 256);
            } catch (e) {
                return null;
            }
            if (!id) return null;
            return match[1].toLowerCase() === 'music'
                ? {sourceType: SOURCE.MUSIC, source: {musicId: id}}
                : {sourceType: SOURCE.COLLECTION, source: {collectionId: id}};
        }
        const collectionId = /^[A-Za-z0-9._-]{1,256}$/.test(value) ? value : '';
        return collectionId ? {sourceType: SOURCE.COLLECTION, source: {collectionId}} : null;
    }

    function currentSeriesValue() {
        if (typeof seriesState !== 'undefined' && seriesState && seriesState.kind === WORK_TYPE
            && seriesState.seriesId != null && String(seriesState.seriesId).trim()) {
            return parseSeriesValue(seriesState.seriesId);
        }
        const input = document.getElementById('series-input-url');
        return parseSeriesValue(input && input.value);
    }

    function modeFor(sourceType) {
        if (sourceType === SOURCE.USER) return 'user';
        if (sourceType === SOURCE.SEARCH) return 'search';
        if (sourceType === SOURCE.COLLECTION || sourceType === SOURCE.MUSIC
            || sourceType === SOURCE.ACCOUNT_FAVORITE_FOLDER) return 'series';
        return QUICK_FETCH_MODE;
    }

    function matches(sourceType, context) {
        if (!context) return false;
        if (context.mode === QUICK_FETCH_MODE) {
            const quick = normalizedQuickSource(context);
            return !!quick && quick.sourceType === sourceType;
        }
        if (sourceType === SOURCE.USER) {
            const input = document.getElementById('user-id-input');
            return context.mode === 'user' && state.settings.userKind === WORK_TYPE
                && parseUserId(input && input.value) !== 'self';
        }
        if (sourceType === SOURCE.SEARCH) return context.mode === 'search';
        if (context.mode !== 'series') return false;
        const current = currentSeriesValue();
        return !!current && current.sourceType === sourceType;
    }

    function sourceFromUi(sourceType, context) {
        if (context && context.mode === QUICK_FETCH_MODE) {
            const quick = normalizedQuickSource(context);
            if (!quick || quick.sourceType !== sourceType) {
                throw new Error(t('schedule.error.quick-source',
                    'Select a concrete Douyin list before saving a scheduled task'));
            }
            return {source: quick.source, label: quick.label};
        }
        if (sourceType === SOURCE.USER) {
            const input = document.getElementById('user-id-input');
            const userId = parseUserId(input && input.value);
            if (!userId) {
                throw new Error(t('schedule.error.user-id',
                    'Enter a valid Douyin user ID or profile URL'));
            }
            if (userId === 'self') {
                throw new Error(t('schedule.error.user-id',
                    'Enter a stable Douyin user ID or profile URL'));
            }
            return {source: {userId}, label: ''};
        }
        if (sourceType === SOURCE.SEARCH) {
            const input = document.getElementById('search-word');
            const keyword = boundedText(input && input.value, 200);
            if (!keyword) {
                throw new Error(t('schedule.error.keyword', 'Enter a Douyin search keyword'));
            }
            return {source: {keyword}, label: ''};
        }
        const series = currentSeriesValue();
        if (!series || series.sourceType !== sourceType) {
            throw new Error(t('schedule.error.series-id',
                'Preview a valid Douyin collection, music, or favorite folder source first'));
        }
        return {source: series.source, label: ''};
    }

    function readFetchLimit() {
        const field = document.getElementById('sch-fetch-limit');
        const raw = String(field && field.value != null ? field.value : DEFAULT_FETCH_LIMIT).trim();
        const value = Number(raw);
        if (!Number.isSafeInteger(value) || value < 0 || value > MAX_FETCH_LIMIT) {
            throw new Error(t('schedule.error.fetch-limit',
                'The per-run limit must be an integer from 0 to {max}', {max: MAX_FETCH_LIMIT}));
        }
        return value;
    }

    function capture(sourceType, context) {
        const selected = sourceFromUi(sourceType, context);
        return {
            params: {
                source: selected.source,
                fetchLimit: readFetchLimit()
            },
            fetchLimitMode: 'per-run',
            fetchLimitPresentation: FETCH_LIMIT_PRESENTATION,
            quickLabel: selected.label,
            workType: WORK_TYPE
        };
    }

    function preview(sourceType, context) {
        const quick = normalizedQuickSource(context);
        return {
            label: quick && quick.sourceType === sourceType ? quick.label : '',
            fetchLimitMode: 'per-run',
            fetchLimitPresentation: FETCH_LIMIT_PRESENTATION
        };
    }

    function setKind(mode, kind) {
        const settingKey = mode === 'user' ? 'userKind' : 'searchKind';
        if (state && state.settings) state.settings[settingKey] = kind;
        const controls = window.PixivBatch && window.PixivBatch.modeControls;
        if (controls) {
            controls.selectSource(mode, 'douyin', false);
            controls.selectType(mode, kind, false);
            const modeApi = window.PixivBatch.modes && window.PixivBatch.modes[mode];
            const applyAvailability = mode === 'user'
                ? modeApi && modeApi.applyUserSourceKindAvailability
                : modeApi && modeApi.applySearchSourceKindAvailability;
            if (typeof applyAvailability === 'function') applyAvailability();
        }
        const radio = document.querySelector(`input[name="${mode}-kind"][value="${kind}"]`);
        if (radio) radio.checked = true;
        if (typeof applyKindSwitcherUI === 'function') {
            applyKindSwitcherUI(`${mode}-kind-switcher`, kind);
        }
    }

    function selectSeriesDataSource() {
        const seriesMode = window.PixivBatch && window.PixivBatch.modes
            && window.PixivBatch.modes.series;
        if (seriesMode && typeof seriesMode.selectSeriesDataSource === 'function') {
            seriesMode.selectSeriesDataSource('douyin');
        }
    }

    function quickLabel(sourceType, source) {
        if (sourceType === SOURCE.ACCOUNT_OWN) return t('quick.own-works', 'My Douyin works');
        if (sourceType === SOURCE.ACCOUNT_LIKED) return t('quick.liked', 'Liked works');
        if (sourceType === SOURCE.ACCOUNT_FAVORITE) return t('quick.favorites', 'Favorite works');
        if (sourceType === SOURCE.ACCOUNT_FAVORITE_COLLECTION) {
            return t('schedule.quick.favorite-collection', 'Favorite collection {name} (ID {id})', {
                name: source.collectionId,
                id: source.collectionId
            });
        }
        return '';
    }

    function restore(sourceType, task) {
        const params = parseParams(task);
        const normalized = normalizedDefinition(sourceType, params);
        if (!normalized) {
            throw new Error(t('schedule.error.snapshot', 'The Douyin task definition is invalid'));
        }
        const targetMode = modeFor(sourceType);
        if (typeof switchMode === 'function') switchMode(targetMode);
        let quickSource = null;
        if (sourceType === SOURCE.USER) {
            setKind('user', WORK_TYPE);
            const input = document.getElementById('user-id-input');
            if (input) input.value = normalized.source.userId;
        } else if (sourceType === SOURCE.SEARCH) {
            setKind('search', WORK_TYPE);
            const input = document.getElementById('search-word');
            if (input) input.value = normalized.source.keyword;
        } else if (sourceType === SOURCE.COLLECTION || sourceType === SOURCE.MUSIC
            || sourceType === SOURCE.ACCOUNT_FAVORITE_FOLDER) {
            selectSeriesDataSource();
            const id = sourceType === SOURCE.MUSIC
                ? normalized.source.musicId
                : sourceType === SOURCE.ACCOUNT_FAVORITE_FOLDER
                    ? normalized.source.folderId : normalized.source.collectionId;
            const seriesId = sourceType === SOURCE.MUSIC
                ? 'music:' + id
                : sourceType === SOURCE.ACCOUNT_FAVORITE_FOLDER
                    ? FAVORITE_FOLDER_PREFIX + id : id;
            if (typeof seriesState !== 'undefined' && seriesState) {
                seriesState.kind = WORK_TYPE;
                seriesState.seriesId = seriesId;
                seriesState.seriesTitle = id;
            }
            const input = document.getElementById('series-input-url');
            if (input) {
                input.value = sourceType === SOURCE.MUSIC
                    ? `https://www.douyin.com/music/${id}`
                    : sourceType === SOURCE.ACCOUNT_FAVORITE_FOLDER
                        ? seriesId : `https://www.douyin.com/mix/${id}`;
            }
        } else {
            quickSource = {
                sourceType,
                type: sourceType,
                source: normalized.source,
                kind: WORK_TYPE,
                workTypes: [WORK_TYPE],
                label: quickLabel(sourceType, normalized.source)
            };
        }
        return {mode: targetMode, quickSource, params: normalized, kind: WORK_TYPE};
    }

    function valueOrUnset(value) {
        return value == null || value === ''
            ? t('schedule.value.unset', 'Not set') : String(value);
    }

    function sourceRows(sourceType, source) {
        if (sourceType === SOURCE.USER) {
            return [[t('schedule.field.user-id', 'User ID'), valueOrUnset(source.userId)]];
        }
        if (sourceType === SOURCE.SEARCH) {
            return [[t('schedule.field.keyword', 'Keyword'), valueOrUnset(source.keyword)]];
        }
        if (sourceType === SOURCE.COLLECTION || sourceType === SOURCE.ACCOUNT_FAVORITE_COLLECTION) {
            return [[t('schedule.field.collection-id', 'Collection ID'), valueOrUnset(source.collectionId)]];
        }
        if (sourceType === SOURCE.MUSIC) {
            return [[t('schedule.field.music-id', 'Music ID'), valueOrUnset(source.musicId)]];
        }
        if (sourceType === SOURCE.ACCOUNT_FAVORITE_FOLDER) {
            return [[t('schedule.field.folder-id', 'Favorite folder ID'), valueOrUnset(source.folderId)]];
        }
        const accountLabel = sourceType === SOURCE.ACCOUNT_OWN
            ? t('schedule.value.account-own', 'Own works')
            : sourceType === SOURCE.ACCOUNT_LIKED
                ? t('schedule.value.account-liked', 'Liked works')
                : t('schedule.value.account-favorite', 'Favorite works');
        return [[t('schedule.field.account-source', 'Account source'), accountLabel]];
    }

    function summary(sourceType, task) {
        const params = normalizedDefinition(sourceType, parseParams(task));
        if (!params) {
            throw new Error(t('schedule.error.snapshot', 'The Douyin task definition is invalid'));
        }
        const rows = sourceRows(sourceType, params.source);
        rows.push([t('schedule.field.fetch-limit', 'Per-run discovery limit'),
            params.fetchLimit > 0
                ? t('schedule.value.fetch-count', '{count} works', {count: params.fetchLimit})
                : t('schedule.value.fetch-all', 'Unlimited')]);
        return {
            kind: WORK_TYPE,
            sections: [{
                title: t('schedule.section.source', 'Source snapshot'),
                rows
            }]
        };
    }

    function cookieFacade() {
        return window.PixivBatch && window.PixivBatch.cookie;
    }

    function savedCookie() {
        const facade = cookieFacade();
        return facade && typeof facade.getCookieHeaderStringFor === 'function'
            ? String(facade.getCookieHeaderStringFor(WORK_TYPE) || '').trim() : '';
    }

    function cookieValidation(cookie) {
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        const descriptor = queueTypes && typeof queueTypes.descriptor === 'function'
            ? queueTypes.descriptor(WORK_TYPE) : null;
        const validate = descriptor && descriptor.cookie && descriptor.cookie.validate;
        return typeof validate === 'function' ? validate(String(cookie || '')) : {ok: false, empty: true, missing: []};
    }

    function credentialActions(api) {
        return Object.freeze({
            supportsCookie: true,
            supportsProxy: true,
            presentation: Object.freeze({
                boundLabel: t('schedule.credential.bound', 'Douyin Cookie bound'),
                unboundLabel: t('schedule.credential.unbound', 'Douyin Cookie required'),
                overrideLabel: t('schedule.credential.override', 'Set a dedicated proxy / Douyin Cookie'),
                modalTitle: t('schedule.credential.modal-title', 'Dedicated proxy / Douyin Cookie'),
                modalIntro: t('schedule.credential.modal-intro',
                    'Set a dedicated network route and Douyin Cookie for this task.'),
                proxyToggleLabel: t('schedule.credential.proxy-toggle', 'Set a dedicated proxy'),
                credentialToggleLabel: t('schedule.credential.cookie-toggle', 'Set a dedicated Douyin Cookie'),
                savedCredentialLabel: t('schedule.credential.saved-cookie', 'Use the saved Douyin Cookie'),
                boundPlaceholder: t('schedule.credential.bound-placeholder',
                    'A Cookie is bound and hidden; leave empty to keep it'),
                placeholder: t('schedule.credential.placeholder', 'Paste a complete logged-in Douyin Cookie'),
                proxyHint: t('schedule.credential.proxy-hint',
                    'Discovery, work resolution, media downloads, credential probes, and guards use this route.'),
                credentialHint: t('schedule.credential.cookie-hint',
                    'This task uses the Cookie for all Douyin discovery and work requests.'),
                emptyCredentialMessage: t('schedule.credential.empty',
                    'Enter a Douyin Cookie, use the saved Cookie, or disable the override'),
                namespace: 'douyin',
                clearProxyConfirmKey: 'schedule.confirm.clear-proxy',
                clearCredentialConfirmKey: 'schedule.confirm.clear-cookie'
            }),
            savedCookie() {
                api.assertActive();
                return savedCookie();
            },
            validateCookie(cookie) {
                api.assertActive();
                const validation = cookieValidation(cookie);
                if (validation && validation.ok) return null;
                if (!cookie || (validation && validation.empty)) {
                    return t('settings.cookie.empty', 'Douyin Cookie is empty');
                }
                return t('settings.cookie.missing',
                    'Douyin Cookie is missing required fields: {fields}', {
                        fields: Array.isArray(validation && validation.missing)
                            ? validation.missing.join(', ') : ''
                    });
            },
            async autoAuthorize(taskId, lease) {
                api.assertActive();
                const cookie = savedCookie();
                if (!cookie || !cookieValidation(cookie).ok) return 'no-cookie';
                try {
                    const response = await fetch(`${BASE}/api/schedule/tasks/${taskId}/authorize-cookie`, {
                        method: 'POST',
                        credentials: 'same-origin',
                        headers: {
                            'Content-Type': 'application/json',
                            'X-Acquisition-Credential': cookie
                        },
                        signal: lease && lease.signal ? lease.signal : api.signal,
                        body: JSON.stringify({
                            activationToken: lease && lease.activationToken
                        })
                    });
                    api.assertActive();
                    if (lease && typeof lease.assertCurrent === 'function') lease.assertCurrent();
                    return response.ok ? 'authorized' : 'failed';
                } catch (e) {
                    api.assertActive();
                    return 'failed';
                }
            }
        });
    }

    runtime.registerModule(MODULE_URL, function (api) {
        const declared = new Set(api.descriptors.map(item => item.sourceType));
        Object.values(SOURCE).forEach(sourceType => {
            if (!declared.has(sourceType)) return;
            api.registerSource(sourceType, {
                matches: context => matches(sourceType, context),
                preview: context => preview(sourceType, context),
                capture: context => capture(sourceType, context),
                restore: task => restore(sourceType, task),
                summary: task => summary(sourceType, task),
                fetchLimitMode: () => 'per-run',
                quickSourceNote: context => {
                    const quick = normalizedQuickSource(context);
                    return quick && quick.sourceType === sourceType ? quick.label : null;
                },
                credentialActions: () => credentialActions(api)
            });
        });
    });
})();
