'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');

function internals() {
    const configSource = fs.readFileSync(path.join(__dirname, '../../main/resources/static',
        'pixiv-multi-mode-decision-survey', 'posthog-config.js'), 'utf8');
    const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static',
        'pixiv-multi-mode-decision-survey', 'survey.js'), 'utf8');
    const window = {document: {addEventListener() {}}};
    const context = vm.createContext({window});
    vm.runInContext(configSource, context);
    vm.runInContext(source, context);
    return window.PixivMultiModeDecisionSurvey._internals;
}

test('explains security risks without presenting the survey as deletion consent', () => {
    const source = fs.readFileSync(path.join(__dirname, '../../main/resources/i18n/web',
        'multi-mode-decision-survey.properties'), 'utf8');

    assert.match(source, /身份伪造、越权访问和资源滥用风险/);
    assert.match(source, /如果后续决定不删除，我们将加固多人模式的安全边界/);
    assert.doesNotMatch(source, /填写前请阅读风险说明/);
});

test('validates the fixed PostHog schema and open-choice response', () => {
    const api = internals();
    const survey = {
        id: api.POSTHOG.surveyId,
        type: 'api',
        questions: [{
            id: api.QUESTION_ID,
            type: 'single_choice',
            choices: ['Yes', 'No', 'Other'],
            hasOpenChoice: true
        }]
    };

    assert.equal(api.resolveQuestion(survey).id, api.QUESTION_ID);
    assert.equal(api.responseValue('Yes', ''), 'Yes');
    assert.equal(api.responseValue('Other', '  current multi-user deployment  '),
        'current multi-user deployment');
    assert.equal(api.responseValue('Other', '   '), null);
    survey.questions[0].choices = ['No', 'Yes', 'Other'];
    assert.equal(api.resolveQuestion(survey), null);
});

test('beforeSend keeps only survey protocol and response properties', () => {
    const api = internals();
    const result = api.beforeSend({
        uuid: 'event-1',
        event: 'survey sent',
        timestamp: '2026-08-14T00:00:00.000Z',
        properties: {
            distinct_id: 'pmds_test',
            token: 'phc_test',
            '$survey_id': api.POSTHOG.surveyId,
            '$survey_completed': true,
            ['$survey_response_' + api.QUESTION_ID]: 'Yes',
            '$device_id': 'device-1',
            '$session_id': 'session-1',
            '$window_id': 'window-1',
            '$pageview_id': 'pageview-1',
            '$lib': 'web',
            '$lib_version': '1.409.5',
            '$current_url': 'https://private.example/path',
            account: 'admin'
        }
    });

    assert.deepEqual(Object.keys(result.properties).sort(), [
        '$survey_completed', '$survey_id', '$survey_response_' + api.QUESTION_ID,
        'distinct_id', 'token'
    ].sort());
    assert.equal(result.uuid, undefined);
    assert.equal(result.timestamp, '2026-08-14T00:00:00.000Z');
    assert.deepEqual(Object.keys(result).sort(), ['event', 'properties', 'timestamp']);
    assert.equal(api.beforeSend({event: '$pageview', properties: {}}), null);
});

test('waits for remote acknowledgement before recording completion', () => {
    const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static',
        'pixiv-multi-mode-decision-survey', 'survey.js'), 'utf8');

    assert.match(source, /captureSurveyWithAck\(OWNER_KEY, 'survey sent', properties\)\.then\(function \(\) \{\s*rememberSubmitted\(\)/);
    assert.doesNotMatch(source, /client\.capture\('survey sent'/);
});
