(function (global) {
    'use strict';

    var SDK_LOAD_TIMEOUT_MS = 10000;
    var SDK_VERSION = '1.409.5';
    var SDK_URL = '/vendor/posthog-js/' + SDK_VERSION + '/array.full.js';
    var clients = Object.create(null);
    var sdkPromise = null;

    function warn(message) {
        if (global.console && typeof global.console.warn === 'function') {
            try { global.console.warn(message); } catch (_) { /* best effort */ }
        }
    }

    function loadSdk() {
        if (global.posthog && typeof global.posthog.init === 'function') {
            return Promise.resolve(global.posthog);
        }
        if (sdkPromise) return sdkPromise;
        sdkPromise = new Promise(function (resolve) {
            var script;
            var settled = false;
            var timeoutId = null;
            function finish(sdk) {
                if (settled) return;
                settled = true;
                if (timeoutId != null) global.clearTimeout(timeoutId);
                if (script) {
                    script.removeEventListener('load', onLoad);
                    script.removeEventListener('error', onError);
                }
                resolve(sdk);
            }
            function onLoad() {
                finish(global.posthog && typeof global.posthog.init === 'function' ? global.posthog : null);
            }
            function onError() {
                finish(null);
            }
            try {
                script = global.document.createElement('script');
                script.src = SDK_URL;
                script.async = true;
                script.addEventListener('load', onLoad);
                script.addEventListener('error', onError);
                timeoutId = global.setTimeout(function () {
                    finish(null);
                    if (script.parentNode) script.parentNode.removeChild(script);
                }, SDK_LOAD_TIMEOUT_MS);
                (global.document.head || global.document.documentElement).appendChild(script);
            } catch (_) {
                finish(null);
            }
        });
        return sdkPromise;
    }

    function instanceName(ownerKey) {
        var name = 'pixiv';
        for (var i = 0; i < ownerKey.length; i++) {
            name += '_' + ownerKey.charCodeAt(i).toString(16);
        }
        return name;
    }

    function nonBlank(value) {
        return typeof value === 'string' && value.trim() !== '';
    }

    function httpsUrl(value) {
        if (!nonBlank(value) || typeof global.URL !== 'function') return false;
        try {
            var parsed = new global.URL(value.trim());
            return parsed.protocol === 'https:' && parsed.hostname !== ''
                && parsed.username === '' && parsed.password === '';
        } catch (_) {
            return false;
        }
    }

    function normalizePostHog(value) {
        if (!value || typeof value !== 'object'
                || !nonBlank(value.projectToken)
                || !nonBlank(value.surveyId)
                || !httpsUrl(value.apiHost)
                || !httpsUrl(value.uiHost)) {
            return null;
        }
        return Object.freeze({
            projectToken: value.projectToken.trim(),
            surveyId: value.surveyId.trim(),
            apiHost: value.apiHost.trim(),
            uiHost: value.uiHost.trim()
        });
    }

    function samePostHog(left, right) {
        return left.projectToken === right.projectToken
            && left.surveyId === right.surveyId
            && left.apiHost === right.apiHost
            && left.uiHost === right.uiHost;
    }

    function sdkConfig(options, posthog) {
        var result = {
            api_host: posthog.apiHost,
            ui_host: posthog.uiHost,
            autocapture: false,
            capture_pageview: false,
            capture_pageleave: false,
            capture_performance: false,
            capture_dead_clicks: false,
            capture_exceptions: false,
            capture_heatmaps: false,
            disable_session_recording: true,
            disable_surveys: false,
            person_profiles: 'identified_only',
            persistence: 'localStorage',
            cross_subdomain_cookie: false,
            respect_dnt: true,
            save_campaign_params: false,
            save_referrer: false,
            rageclick: false,
            disable_surveys_automatic_display: true,
            advanced_only_evaluate_survey_feature_flags: true,
            disable_external_dependency_loading: true,
            feature_flag_request_timeout_ms: 5000,
            surveys_request_timeout_ms: 15000,
            mask_all_text: true,
            mask_all_element_attributes: true,
            before_send: options.beforeSend
        };
        if (options.distinctId) {
            result.bootstrap = {distinctID: options.distinctId, isIdentifiedID: false};
        }
        return result;
    }

    function createSurveyClient(options) {
        options = options || {};
        var ownerKey = typeof options.ownerKey === 'string' ? options.ownerKey.trim() : '';
        var posthog = normalizePostHog(options.posthog);
        if (!ownerKey || !posthog || typeof options.beforeSend !== 'function') {
            return Promise.resolve(null);
        }
        var distinctId = typeof options.distinctId === 'string' ? options.distinctId : '';
        var existing = clients[ownerKey];
        if (existing) {
            if (existing.distinctId !== distinctId || existing.beforeSend !== options.beforeSend
                    || !samePostHog(existing.posthog, posthog)) {
                warn('posthog: survey client already exists with a different configuration; owner disabled for this page');
                return Promise.resolve(null);
            }
            return existing.promise;
        }
        var record = {
            distinctId: distinctId,
            beforeSend: options.beforeSend,
            posthog: posthog,
            promise: null
        };
        clients[ownerKey] = record;
        record.promise = loadSdk().then(function (sdk) {
            if (!sdk || typeof sdk.init !== 'function') return null;
            return sdk.init(posthog.projectToken, sdkConfig({
                distinctId: distinctId,
                beforeSend: options.beforeSend
            }, posthog), instanceName(ownerKey)) || null;
        }).catch(function () {
            return null;
        });
        return record.promise;
    }

    global.PixivPostHog = Object.freeze({
        createSurveyClient: createSurveyClient
    });
})(window);
