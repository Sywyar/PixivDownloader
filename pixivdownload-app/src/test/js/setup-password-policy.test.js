'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
    '../../main/resources/static/setup/setup.js'), 'utf8');
const submitSource = source.slice(0, source.indexOf("document.addEventListener('keydown'"));

function createHarness(password) {
    const elements = {
        username: {value: 'admin'},
        password: {value: password},
        'proxy-enabled': {checked: false},
        'proxy-host': {value: ''},
        'proxy-port': {value: '7890'},
        'submit-btn': {disabled: false},
        'status-msg': {textContent: '', dataset: {}}
    };
    const requests = [];
    const sandbox = {
        document: {getElementById: id => elements[id]},
        fetch: async (url, options) => {
            requests.push({url, options});
            return {ok: true, json: async () => ({})};
        },
        location: {href: 'http://localhost/setup.html'},
        setTimeout: () => {}
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(submitSource, sandbox, {filename: 'setup.js'});
    return {
        elements,
        requests,
        submit: () => vm.runInContext('submitSetup()', sandbox),
        changePassword: value => {
            elements.password.value = value;
            vm.runInContext('resetWeakPasswordConfirmation()', sandbox);
        }
    };
}

test('首次配置密码应强制 8 位且对 8–11 位要求再次提交确认', async () => {
    const tooShort = createHarness('1234567');
    await tooShort.submit();
    assert.equal(tooShort.requests.length, 0);
    assert.match(tooShort.elements['status-msg'].textContent, /8/);

    const weak = createHarness('12345678');
    await weak.submit();
    assert.equal(weak.requests.length, 0);
    assert.match(weak.elements['status-msg'].textContent, /Cookie/);
    assert.match(weak.elements['status-msg'].textContent, /12/);
    await weak.submit();
    assert.equal(weak.requests.length, 1);

    const changed = createHarness('12345678');
    await changed.submit();
    changed.changePassword('abcdefgh');
    await changed.submit();
    assert.equal(changed.requests.length, 0);
    await changed.submit();
    assert.equal(changed.requests.length, 1);

    const recommended = createHarness('123456789012');
    await recommended.submit();
    assert.equal(recommended.requests.length, 1);
});
