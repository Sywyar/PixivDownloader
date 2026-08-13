'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');

function internals() {
    const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static',
        'pixiv-multi-mode-decision-survey', 'survey.js'), 'utf8');
    const window = {document: {addEventListener() {}}};
    const context = vm.createContext({window});
    vm.runInContext(source, context);
    return window.PixivMultiModeDecisionSurvey._internals;
}

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
        event: 'survey sent',
        properties: {
            distinct_id: 'pmds_test',
            '$survey_id': api.POSTHOG.surveyId,
            ['$survey_response_' + api.QUESTION_ID]: 'Yes',
            '$current_url': 'https://private.example/path',
            account: 'admin'
        }
    });

    assert.deepEqual(Object.keys(result.properties).sort(), [
        '$survey_id', '$survey_response_' + api.QUESTION_ID, 'distinct_id'
    ].sort());
    assert.equal(api.beforeSend({event: '$pageview', properties: {}}), null);
});
