(function (global) {
    'use strict';

    var OWNER_KEY = 'multi-mode-decision-survey.removal-decision';
    var TRUSTED_POSTHOG_API_ORIGINS = Object.freeze(['https://layout-survey.sywyar.top']);
    var POSTHOG = global.PixivMultiModeDecisionSurveyPostHog || Object.freeze({});
    var QUESTION_ID = '0ac24f7c-abeb-4405-8c9c-916e4ca904ac';
    var STATE_KEY = 'pixiv:multi-mode-decision-survey:state:v1';
    var IDENTITY_URL = '/api/multi-mode-decision-survey/identity';
    var SCOPED_ID = /^pmds_[0-9a-f]{64}$/;
    var CHOICES = ['Yes', 'No', 'Other'];
    var PROTOCOL_PROPERTIES = [
        'distinct_id', 'token', 'time', '$lib', '$lib_version', '$lib_variant',
        '$device_id', '$session_id', '$window_id', '$pageview_id', '$survey_id',
        '$survey_completed'
    ];

    function beforeSend(event) {
        if (!event || (event.event !== 'survey shown' && event.event !== 'survey sent')) return null;
        var source = event.properties && typeof event.properties === 'object' ? event.properties : {};
        var properties = {};
        Object.keys(source).forEach(function (key) {
            if (PROTOCOL_PROPERTIES.indexOf(key) >= 0 || key.indexOf('$survey_response_') === 0) {
                properties[key] = source[key];
            }
        });
        var result = {event: event.event, properties: properties};
        if (typeof event.uuid === 'string') result.uuid = event.uuid;
        if (typeof event.timestamp === 'string'
                || Object.prototype.toString.call(event.timestamp) === '[object Date]') {
            result.timestamp = event.timestamp;
        }
        return result;
    }

    function resolveQuestion(survey) {
        if (!survey || survey.id !== POSTHOG.surveyId || survey.type !== 'api'
                || !Array.isArray(survey.questions) || survey.questions.length !== 1) return null;
        var question = survey.questions[0];
        if (!question || question.id !== QUESTION_ID || question.type !== 'single_choice'
                || question.hasOpenChoice !== true || !Array.isArray(question.choices)
                || question.choices.length !== CHOICES.length) return null;
        return CHOICES.every(function (choice, index) {
            return question.choices[index] === choice;
        }) ? question : null;
    }

    function responseValue(choice, other) {
        if (choice === 'Yes' || choice === 'No') return choice;
        if (choice !== 'Other') return null;
        var text = String(other || '').trim();
        return text && Array.from(text).length <= 1000 ? text : null;
    }

    function acceptedCapture(result, eventName) {
        return !!result && typeof result === 'object' && result.event === eventName;
    }

    global.PixivMultiModeDecisionSurvey = Object.freeze({
        _internals: Object.freeze({
            POSTHOG: POSTHOG,
            QUESTION_ID: QUESTION_ID,
            beforeSend: beforeSend,
            resolveQuestion: resolveQuestion,
            responseValue: responseValue,
            acceptedCapture: acceptedCapture
        })
    });

    if (!global.document || typeof global.document.addEventListener !== 'function') return;

    global.document.addEventListener('DOMContentLoaded', async function () {
        var root = global.document.getElementById('multiModeDecisionSurvey');
        if (!root) return;
        var params = new URLSearchParams(global.location.search);
        var notificationId = params.get('notificationId') || '';
        var i18n = null;
        var lastHeight = 0;

        function t(key, fallback) {
            return i18n ? i18n.t('multi-mode-decision-survey:' + key, fallback) : fallback;
        }

        function reportHeight() {
            var height = Math.ceil(Math.max(root.scrollHeight, root.getBoundingClientRect().height));
            if (height <= 0 || height === lastHeight) return;
            lastHeight = height;
            global.parent.postMessage({type: 'pixiv-content-height', height: height}, global.location.origin);
        }

        function status(key, fallback) {
            root.className = 'survey-status';
            root.setAttribute('role', 'status');
            root.textContent = t(key, fallback);
            reportHeight();
        }

        function submitted() {
            try {
                var state = JSON.parse(global.localStorage.getItem(STATE_KEY) || 'null');
                return !!state && state.surveyId === POSTHOG.surveyId && state.status === 'submitted';
            } catch (_) {
                return false;
            }
        }

        function rememberSubmitted() {
            try {
                global.localStorage.setItem(STATE_KEY, JSON.stringify({
                    surveyId: POSTHOG.surveyId,
                    status: 'submitted'
                }));
            } catch (_) { /* PostHog 已接受提交时，本地存储失败不撤销结果。 */ }
        }

        function unavailablePermanently() {
            status('ended', '该调查已结束。');
            global.parent.postMessage({
                type: 'pixiv-survey-unavailable',
                notificationId: notificationId
            }, global.location.origin);
        }

        function fetchIdentity() {
            return global.fetch(IDENTITY_URL + '?surveyId=' + encodeURIComponent(POSTHOG.surveyId), {
                credentials: 'same-origin',
                cache: 'no-store',
                headers: {'Accept': 'application/json'}
            }).then(function (response) {
                if (!response || !response.ok) throw new Error('identity unavailable');
                return response.json();
            }).then(function (body) {
                if (!body || !SCOPED_ID.test(body.distinctId)) throw new Error('invalid identity');
                return body.distinctId;
            });
        }

        function fetchSurvey(client) {
            return new Promise(function (resolve) {
                var settled = false;
                var timer = global.setTimeout(function () { finish('unavailable'); }, 30000);
                function finish(statusValue, survey) {
                    if (settled) return;
                    settled = true;
                    global.clearTimeout(timer);
                    resolve({status: statusValue, survey: survey || null});
                }
                try {
                    client.getSurveys(function (surveys, context) {
                        if (context && context.isLoaded === false) {
                            finish('unavailable');
                            return;
                        }
                        var survey = Array.isArray(surveys) ? surveys.find(function (item) {
                            return item && item.id === POSTHOG.surveyId;
                        }) : null;
                        finish(survey && survey.start_date && !survey.end_date ? 'available' : 'removed', survey);
                    }, true);
                } catch (_) {
                    finish('unavailable');
                }
            });
        }

        function renderForm(client, question) {
            root.className = 'survey-card';
            root.removeAttribute('role');
            root.textContent = '';

            var title = global.document.createElement('h2');
            title.textContent = t('inbox-title', '多人模式去留调查');
            var description = global.document.createElement('p');
            description.className = 'survey-description';
            description.textContent = t('description', '本问卷用于调查多人模式的实际使用情况。当前实现存在身份伪造、越权访问和资源滥用风险，因此维护计划拟删除多人模式。如果您正在使用，诚邀填写；如果后续决定不删除，我们将加固相关安全边界。感谢您的填写。');
            var fieldset = global.document.createElement('fieldset');
            fieldset.className = 'survey-options';
            var legend = global.document.createElement('legend');
            legend.textContent = t('question', '您使用或者未来会使用多人模式吗？');
            fieldset.appendChild(legend);

            var otherInput = global.document.createElement('input');
            otherInput.className = 'survey-other';
            otherInput.type = 'text';
            otherInput.maxLength = 1000;
            otherInput.placeholder = t('other-placeholder', '请说明您的情况');
            otherInput.setAttribute('aria-label', otherInput.placeholder);
            otherInput.disabled = true;

            CHOICES.forEach(function (choice) {
                var label = global.document.createElement('label');
                label.className = 'survey-option';
                var radio = global.document.createElement('input');
                radio.type = 'radio';
                radio.name = 'multi-mode-decision';
                radio.value = choice;
                var text = global.document.createElement('span');
                text.textContent = choice === 'Yes' ? t('choice-yes', '会使用')
                    : choice === 'No' ? t('choice-no', '不会使用')
                        : t('choice-other', '其他');
                label.append(radio, text);
                fieldset.appendChild(label);
            });
            fieldset.appendChild(otherInput);

            var privacy = global.document.createElement('p');
            privacy.className = 'survey-privacy';
            privacy.textContent = t('privacy', '本问卷使用 PostHog SDK 提交，只收集您的回答和匿名标识。');
            var error = global.document.createElement('p');
            error.className = 'survey-error';
            error.hidden = true;
            error.setAttribute('role', 'alert');
            var submit = global.document.createElement('button');
            submit.className = 'survey-submit';
            submit.type = 'button';
            submit.textContent = t('submit', '提交');
            submit.disabled = true;
            root.append(title, description, fieldset, privacy, error, submit);

            function selectedChoice() {
                var selected = fieldset.querySelector('input[type="radio"]:checked');
                return selected ? selected.value : null;
            }

            function update() {
                var choice = selectedChoice();
                otherInput.disabled = choice !== 'Other';
                submit.disabled = !responseValue(choice, otherInput.value);
                error.hidden = true;
                reportHeight();
            }

            fieldset.addEventListener('change', function () {
                update();
                if (selectedChoice() === 'Other') otherInput.focus();
            });
            otherInput.addEventListener('input', update);
            submit.addEventListener('click', function () {
                if (submitted()) {
                    status('completed', '感谢反馈，您的回答已提交。');
                    return;
                }
                var choice = selectedChoice();
                var response = responseValue(choice, otherInput.value);
                if (!choice) {
                    error.textContent = t('required', '请选择一个选项。');
                    error.hidden = false;
                    return;
                }
                if (!response) {
                    error.textContent = t('other-required', '选择“其他”时请填写说明。');
                    error.hidden = false;
                    return;
                }
                submit.disabled = true;
                submit.textContent = t('submitting', '提交中…');
                var properties = {'$survey_id': POSTHOG.surveyId, '$survey_completed': true};
                properties['$survey_response_' + question.id] = response;
                var capture = null;
                try { capture = client.capture('survey sent', properties); } catch (_) { capture = null; }
                if (acceptedCapture(capture, 'survey sent')) {
                    rememberSubmitted();
                    status('completed', '感谢反馈，您的回答已提交。');
                } else {
                    submit.textContent = t('submit', '提交');
                    submit.disabled = false;
                    error.textContent = t('submit-failed', '提交失败，请稍后重试。');
                    error.hidden = false;
                    reportHeight();
                }
            });
            try { client.capture('survey shown', {'$survey_id': POSTHOG.surveyId}); } catch (_) { /* best effort */ }
            reportHeight();
        }

        try {
            i18n = await global.PixivI18n.create({
                namespaces: ['multi-mode-decision-survey'],
                lang: params.get('lang') || undefined
            });
            status('loading', '正在加载调查…');
            if (global.PixivMultiModeDecisionSurveyOfficialRelease !== true
                    || !global.PixivPostHog || typeof global.PixivPostHog.createSurveyClient !== 'function') {
                status('unavailable', '调查暂时无法加载，请稍后重试。');
                return;
            }
            var distinctId = await fetchIdentity();
            var client = await global.PixivPostHog.createSurveyClient({
                ownerKey: OWNER_KEY,
                posthog: POSTHOG,
                trustedApiOrigins: TRUSTED_POSTHOG_API_ORIGINS,
                distinctId: distinctId,
                beforeSend: beforeSend
            });
            if (!client || typeof client.get_distinct_id !== 'function'
                    || client.get_distinct_id() !== distinctId) throw new Error('posthog identity mismatch');
            var published = await fetchSurvey(client);
            if (published.status === 'removed') {
                unavailablePermanently();
                return;
            }
            var question = published.status === 'available' ? resolveQuestion(published.survey) : null;
            if (!question) throw new Error('survey unavailable');
            if (submitted()) {
                status('completed', '感谢反馈，您的回答已提交。');
            } else {
                renderForm(client, question);
            }
        } catch (_) {
            status('unavailable', '调查暂时无法加载，请稍后重试。');
        }

        if (typeof global.ResizeObserver === 'function') {
            new global.ResizeObserver(reportHeight).observe(root);
        }
    });
})(window);
