'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const QUESTION_ID = '11111111-2222-4333-8444-555555555555';

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

test('使用当前问卷的题目标识，同时校验问卷结构与开放选项', () => {
    const api = internals();
    const survey = {
        id: api.POSTHOG.surveyId,
        type: 'api',
        questions: [{
            id: QUESTION_ID,
            type: 'single_choice',
            choices: ['Yes', 'No', 'Other'],
            hasOpenChoice: true
        }]
    };

    assert.equal(api.resolveQuestion(survey).id, QUESTION_ID);
    const replacementId = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';
    survey.questions[0].id = replacementId;
    assert.equal(api.resolveQuestion(survey).id, replacementId);
    for (const invalid of [null, '', ' ', 123, 'not-a-question-id']) {
        survey.questions[0].id = invalid;
        assert.equal(api.resolveQuestion(survey), null);
    }
    survey.questions[0].id = QUESTION_ID;
    assert.equal(api.resolveQuestion({...survey, id: 'another-survey'}), null);
    assert.equal(api.resolveQuestion({...survey, type: 'popover'}), null);
    assert.equal(api.resolveQuestion({...survey, questions: [...survey.questions, survey.questions[0]]}), null);
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
            ['$survey_response_' + QUESTION_ID]: 'Yes',
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
        '$survey_completed', '$survey_id', '$survey_response_' + QUESTION_ID,
        'distinct_id', 'token'
    ].sort());
    assert.equal(result.uuid, undefined);
    assert.equal(result.timestamp, '2026-08-14T00:00:00.000Z');
    assert.deepEqual(Object.keys(result).sort(), ['event', 'properties', 'timestamp']);
    assert.equal(api.beforeSend({event: '$pageview', properties: {}}), null);
});

async function surveyForm(identityChanges = {}) {
    const elements = [];
    function element(tag) {
        const listeners = new Map();
        const node = {
            tag, children: [], disabled: false, checked: false, value: '', scrollHeight: 120,
            append(...children) { this.children.push(...children); },
            appendChild(child) { this.children.push(child); },
            setAttribute(name, value) { this[name] = value; },
            removeAttribute(name) { delete this[name]; },
            addEventListener(type, listener) { listeners.set(type, listener); },
            fire(type) { listeners.get(type)?.(); },
            focus() {},
            getBoundingClientRect() { return {height: 120}; },
            querySelector(selector) {
                assert.equal(selector, 'input[type="radio"]:checked');
                return elements.find(item => item.type === 'radio' && item.checked) || null;
            }
        };
        elements.push(node);
        return node;
    }
    const root = element('main');
    const saved = new Map();
    const submissions = [];
    const loadingTargets = [];
    const identity = {
        distinctId: 'pmds_' + 'a'.repeat(64),
        submissionId: '11111111-2222-8333-8444-555555555555',
        ...identityChanges
    };
    let start;
    const window = {
        document: {
            addEventListener(type, listener) { if (type === 'DOMContentLoaded') start = listener; },
            getElementById: () => root,
            createElement: element
        },
        location: {search: ''}, setTimeout, clearTimeout,
        PixivMultiModeDecisionSurveyOfficialRelease: true,
        PixivSurveyFrameBridge: {
            ready: async () => ({storage: {getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value)}}),
            post() {}
        },
        PixivI18n: {create: async () => ({t: key => key})},
        fetch: async () => ({ok: true, json: async () => identity}),
        PixivPostHog: {
            showSurveyLoading: async target => { loadingTargets.push(target); },
            createSurveyClient: async options => ({
                get_distinct_id: () => options.distinctId,
                getSurveys: callback => callback([{
                    id: options.posthog.surveyId, type: 'api', start_date: '2026-01-01',
                    questions: [{id: QUESTION_ID, type: 'single_choice',
                        choices: ['Yes', 'No', 'Other'], hasOpenChoice: true}]
                }]),
                capture() {}
            }),
            captureSurveyWithAck: (...args) => new Promise((resolve, reject) => {
                submissions.push({args, resolve, reject});
            })
        }
    };
    const context = vm.createContext({window, URLSearchParams});
    for (const name of ['posthog-config.js', 'survey.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static',
            'pixiv-multi-mode-decision-survey', name), 'utf8'), context);
    }
    await start();
    const fieldset = elements.find(item => item.tag === 'fieldset');
    const submit = elements.find(item => item.tag === 'button');
    const other = elements.find(item => item.type === 'text');
    return {
        fieldset, submit, root, saved, submissions, identity, loadingTargets,
        choose(choice, text = '') {
            elements.filter(item => item.type === 'radio').forEach(item => { item.checked = item.value === choice; });
            other.value = text;
            fieldset.fire('change');
        }
    };
}

test('等待提交确认时保持表单禁用，并拒绝重复提交', async () => {
    const form = await surveyForm();
    assert.deepEqual(form.loadingTargets, [form.root]);
    assert.equal(form.submit.disabled, true);
    form.choose('Yes');
    form.submit.fire('click');
    assert.equal(form.saved.size, 0);
    assert.equal(form.fieldset.disabled, true);
    assert.equal(form.submit.disabled, true);
    form.choose('No');
    assert.equal(form.submit.disabled, true);
    form.submit.fire('click');
    assert.equal(form.submissions.length, 1);
    assert.equal(form.submissions[0].args[2]['$survey_response_' + QUESTION_ID], 'Yes');
    assert.equal(form.submissions[0].args[3], form.identity.submissionId);
    form.submissions[0].resolve();
    await new Promise(setImmediate);
    const state = JSON.parse([...form.saved.values()][0]);
    assert.equal(state.status, 'submitted');
    assert.equal(state.surveyId, form.submissions[0].args[2].$survey_id);
    assert.equal(form.root.textContent, 'multi-mode-decision-survey:completed');
});

test('提交失败后保留回答并按当前输入恢复，重试成功才记录完成', async () => {
    const form = await surveyForm();
    form.choose('Other', 'a valid answer');
    form.submit.fire('click');
    form.submissions[0].reject(new Error('controlled acknowledgement failure'));
    await new Promise(setImmediate);
    assert.equal(form.saved.size, 0);
    assert.equal(form.fieldset.disabled, false);
    assert.equal(form.submit.disabled, false);
    form.choose('Other', '');
    assert.equal(form.submit.disabled, true);
    form.choose('Other', 'a valid answer');
    form.submit.fire('click');
    assert.equal(form.submissions.length, 2);
    assert.equal(form.submissions[1].args[2]['$survey_response_' + QUESTION_ID], 'a valid answer');
    assert.equal(form.submissions[1].args[3], form.submissions[0].args[3]);
    form.submissions[1].resolve();
    await new Promise(setImmediate);
    assert.equal(JSON.parse([...form.saved.values()][0]).status, 'submitted');
});

test('拒绝无效的调查身份和提交标识，不展示提交表单', async () => {
    for (const identity of [{distinctId: 'unscoped'}, {submissionId: 'invalid'},
        {submissionId: '11111111-2222-4333-8444-555555555555'}]) {
        const form = await surveyForm(identity);
        assert.equal(form.submit, undefined);
        assert.equal(form.submissions.length, 0);
        assert.equal(form.saved.size, 0);
        assert.equal(form.root.textContent, 'multi-mode-decision-survey:unavailable');
    }
});
