import test from 'node:test';
import assert from 'node:assert/strict';
import {resolveBinding, publicationSections} from '../../../pixivdownload-plugin-page-editor/src/main/resources/web/editor-bindings.mjs';

const protocol = {id: 'proof', version: 1, stateSchema: '{"type":"string","maxLength":32}', responseSchema: '{"type":"string"}'};
const component = {participation: {policy: 'proof', protocol}};
const target = {id: 'sign-in', region: 'form', operation: 'owner:sign-in', protocols: [protocol], maximumParticipants: 1};
const page = {regions: [{id: 'form', parent: ''}, {id: 'inner', parent: 'form'}], targets: [target]};

test('唯一协议绑定、移动和撤销只按当前 owner 作用域解析', () => {
    assert.equal(resolveBinding(component, 'inner', page).target, 'sign-in');
    assert.equal(resolveBinding(component, undefined, page, 'sign-in').status, 'unmatched');
    assert.equal(resolveBinding(component, 'inner', page, 'deleted-target').target, 'sign-in');
    const reordered = {participation: {protocol: {...protocol, stateSchema: '{"maxLength":32,"type":"string"}'}}};
    assert.equal(resolveBinding(reordered, 'form', page).target, 'sign-in');
    assert.equal(resolveBinding({participation: {protocol: {...protocol, version: 2}}}, 'form', page).status, 'unmatched');
});

test('歧义必须选择目标，不越过不兼容的内层表单', () => {
    const ambiguous = {...page, targets: [target, {...target, id: 'other'}]};
    assert.equal(resolveBinding(component, 'inner', ambiguous).status, 'ambiguous');
    assert.equal(resolveBinding(component, 'inner', ambiguous, 'other').target, 'other');
    const nested = {...page, targets: [target, {...target, id: 'inner-form', region: 'inner', protocols: []}]};
    assert.equal(resolveBinding(component, 'inner', nested).status, 'unmatched');
    assert.equal(resolveBinding({}, 'form', page).status, 'none');
});

test('发布确认展示服务端操作差异和每项资源授权', () => {
    const sections = publicationSections({impacts: [{operation: 'owner:sign-in', before: [], after: ['proof'], pages: ['owner:page']}],
        permissions: [{owner: 'provider', policy: 'proof', profile: {id: 'widget', sources: [{kind: 'FRAME', origin: 'https://verify.example'}]}}]}, key => key);
    assert.match(sections[0].fields[0].value, /publication-none → proof/);
    assert.equal(sections[1].fields[0].label, 'provider / proof / widget');
    assert.equal(sections[1].fields[0].value, 'FRAME: https://verify.example');
});
