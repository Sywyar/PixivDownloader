import test from 'node:test';
import assert from 'node:assert/strict';
import {resolveBinding} from '../../../pixivdownload-plugin-page-editor/src/main/resources/web/editor-bindings.mjs';
import {publicationLabels, publicationSections} from '../../../pixivdownload-plugin-page-editor/src/main/resources/web/editor-publication.mjs';
import {errorMessage} from '../../../pixivdownload-plugin-page-editor/src/main/resources/web/editor-feedback.mjs';

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
    const sections = publicationSections({pages: ['owner:page', 'owner:other'],
        drafts: [{page: 'owner:page', revision: 2}],
        impacts: [{operation: 'owner:sign-in', before: [], after: ['proof'], pages: ['owner:page', 'owner:other']}],
        permissions: [{owner: 'provider', policy: 'proof', profile: {id: 'widget', sources: [{kind: 'FRAME', origin: 'https://verify.example'}]}}]}, key => key);
    assert.deepEqual(sections[0].fields.map(field => field.label), ['page (owner:page)', 'page (owner:other)']);
    assert.deepEqual(sections[0].fields.map(field => field.value), ['publication-draft', 'publication-refresh']);
    assert.match(sections[1].fields[0].value, /publication-before\npublication-none\npublication-after/);
    assert.equal(sections[2].fields[0].label, 'hook.definition (provider / proof)');
    assert.equal(sections[2].fields[0].value, 'publication-resource-FRAME: https://verify.example');
    assert.equal(sections[3].collapsed, true);
    assert.equal(sections[3].fields.at(-1).label, 'provider / proof / widget');
});


test('恢复确认区分当前页和所选页的历史，以及自动刷新的正式内容', () => {
    const sections = publicationSections({pages: ['owner:current', 'owner:selected', 'owner:refreshed'],
        drafts: [{page: 'owner:current', revision: 2}, {page: 'owner:selected', revision: 4}],
        impacts: [], permissions: []}, key => key, true);
    assert.deepEqual(sections[0].fields, [
        {label: 'page (owner:current)', value: 'publication-previous'},
        {label: 'page (owner:selected)', value: 'publication-previous'},
        {label: 'page (owner:refreshed)', value: 'publication-refresh'}
    ]);
});

test('确认使用目录名称，区分前置检查与后置处理，保留精确授权且不重复空权限', () => {
    const catalog = {pages: [{definition: {...page, id: 'owner:page', namespace: 'form', labelKey: 'Login page',
        targets: [{...target, labelKey: 'Sign in'}]}}],
        components: [{definition: {type: 'provider:proof', namespace: 'proof', labelKey: 'Verification', participation: {policy: 'proof'}}}]};
    const names = publicationLabels(catalog, definition => definition.labelKey, key => key);
    const plan = {pages: ['owner:page'], drafts: [{page: 'owner:page', revision: 2}],
        impacts: [{operation: target.operation, before: ['proof@BEFORE_INVOKE:' + target.operation],
            after: ['proof@AFTER_COMPLETION:' + target.operation], pages: ['owner:page']}],
        permissions: ['binding1', 'binding2'].map(revision => ({owner: 'provider', policy: 'proof',
            profile: {id: 'hook-binding', revision, sources: []}}))};
    const original = structuredClone(plan), sections = publicationSections(plan, key => key, false, names);
    assert.equal(sections[0].fields[0].label, 'Login page');
    assert.equal(sections[1].fields[0].label, 'Sign in');
    assert.match(sections[1].fields[0].value, /Verification · hook.BEFORE_INVOKE/);
    assert.match(sections[1].fields[0].value, /Verification · hook.AFTER_COMPLETION/);
    assert.deepEqual(sections[2].fields, [{label: 'Verification', value: 'publication-resources-none'}]);
    assert.equal(sections[3].fields.length, 3);
    assert.deepEqual(plan, original, 'display grouping must not change approvals or plan identity');
    plan.impacts[0].after = [];
    assert.match(publicationSections(plan, key => key, false, names)[1].fields[0].value, /publication-after\npublication-none/);
    assert.notEqual(names.policy('proof', 'other'), 'Verification', 'do not borrow a different owner’s name');
});

test('未知名称仍可区分，资源来源与用途完整展示', () => {
    const plan = {pages: ['missing:page'], impacts: [], permissions: [{owner: 'other', policy: 'external',
        profile: {id: 'assets', revision: 'r1', sources: [
            {kind: 'SCRIPT', origin: 'https://verify.example'}, {kind: 'CONNECT', origin: 'https://verify.example'},
            {kind: 'FRAME', origin: 'self'}, {kind: 'WORKER', origin: 'blob:'}]}}]};
    const sections = publicationSections(plan, key => key);
    assert.equal(sections[0].fields[0].label, 'page (missing:page)');
    for (const kind of ['SCRIPT', 'CONNECT', 'FRAME', 'WORKER']) assert.ok(sections[2].fields[0].value.includes('publication-resource-' + kind));
    assert.ok(sections[2].fields[0].value.includes('https://verify.example'));
    assert.ok(sections[2].fields[0].value.includes('publication-self'));
    assert.ok(sections[2].fields[0].value.includes('publication-local'));
});

test('错误码映射为可操作提示，未知错误不把内部异常暴露给用户', () => {
    const translate = key => 'translated:' + key;
    assert.equal(errorMessage(new Error('PAGE_BINDING_DUPLICATE'), translate), 'translated:error.duplicate');
    assert.equal(errorMessage('PAGE_OPERATION_CONFLICT', translate), 'translated:error.shared');
    assert.equal(errorMessage('PAGE_PUBLICATION_STALE', translate), 'translated:error.stale');
    assert.equal(errorMessage('PAGE_COMPONENT_PROPERTIES_INVALID', translate), 'translated:error.configuration');
    assert.equal(errorMessage('PAGE_COMPONENT_ORIGIN', translate), 'translated:error.origin');
    assert.equal(errorMessage(new Error('private server detail'), translate), 'translated:failed');
    assert.equal(errorMessage(new TypeError('Failed to fetch'), translate), 'translated:failed');
});

test('操作和策略按当前译文消歧，同属主重名与缺失名称仍可识别', () => {
    const catalog = {pages: [], components: []};
    for (const [owner, id] of [['a', 'first'], ['a', 'second'], ['b', 'first']]) {
        catalog.pages.push({definition: {id: owner + ':' + id, labelKey: 'Page', namespace: owner,
            regions: [], targets: [{id, operation: owner + ':' + id, labelKey: id + ' label'}]}});
        catalog.components.push({definition: {type: owner + ':' + id, namespace: owner, labelKey: id + ' label',
            participation: {policy: owner + ':' + id}}});
    }
    let translated = false;
    const names = publicationLabels(catalog, definition => translated ? 'Same name' : definition.labelKey, key => key);
    assert.equal(names.operation('a:second'), 'second label');
    translated = true;
    for (const getName of [id => names.operation(id), id => names.policy(id, id.split(':')[0]), id => names.policy(id)]) {
        const labels = ['a:first', 'a:second', 'b:first'].map(getName);
        assert.equal(new Set(labels).size, 3);
        labels.forEach((label, index) => assert.ok(label.includes(['a:first', 'a:second', 'b:first'][index])));
    }
    assert.notEqual(names.policy('a:first', 'b'), names.policy('a:first', 'a'));
    assert.ok(names.operation('missing:one').includes('missing:one'));
    assert.ok(names.policy('missing:proof', 'missing').includes('missing:proof'));
});

test('发布与恢复默认确认显示每页字段授权前后变化，不读取或改写配置', () => {
    const access = fields => ({owner: 'provider', hook: 'observe', operation: 'owner:save', endpoint: '',
        phase: 'AFTER_COMPLETION', completion: 'SUCCEEDED', order: 3, fields});
    const fields = [{source: 'QUERY', path: 'tag', name: 'label'},
        {source: 'JSON', path: '/email', name: 'email'}, {source: 'RESULT', path: '/displayName', name: 'display'}];
    const plan = {pages: ['owner:a', 'owner:b'], drafts: [{page: 'owner:a'}, {page: 'owner:b'}], impacts: [], permissions: [],
        hooks: [{page: 'owner:a', before: [access([])], after: [access(fields)]},
            {page: 'owner:b', before: [access(fields)], after: [access([{source: 'JSON', path: '/alias', name: 'alias'}])]}]};
    const original = structuredClone(plan);
    for (const restoring of [false, true]) {
        const sections = publicationSections(plan, key => key, restoring);
        const section = sections.find(section => section.title === 'publication-data');
        assert.ok(section && !section.collapsed);
        const visible = JSON.stringify(section);
        for (const field of fields) assert.ok(visible.includes(field.path) && visible.includes('publication-field-' + field.source));
        assert.ok(visible.includes('/alias') && visible.includes('publication-fields-none'));
        assert.ok(visible.includes('hook.SUCCEEDED') && visible.includes('hook.order: 3'));
        assert.equal(section.fields.length, 2);
        assert.match(section.fields[0].value, /publication-before[\s\S]*publication-after/);
        assert.notEqual(section.fields[0].label, section.fields[1].label);
    }
    assert.deepEqual(plan, original);
});
