'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {harness, manifest, typeDescriptor, uiSlotDescriptor, BASIC_INITIALIZER, UI_INITIALIZER}
    = require('./batch-queue-types-fixture');

test('仅提供 UI 的活动插件可预加载语言资源，撤回和替换后不沿用旧命名空间', async () => {
    const slot = uiSlotDescriptor({i18nNamespace: 'ui-copy'});
    const replacement = uiSlotDescriptor({i18nNamespace: 'replacement-copy',
        owner: {pluginId: 'ui-owner', packageId: 'ui-package', generation: 2, publicationId: 11}});
    const h = harness([
        manifest(1, [], 'epoch-a', [slot]),
        manifest(2, []),
        manifest(3, [], 'epoch-a', [replacement])
    ], {'/modules/ui-slot.js': {ui: true, initializer: UI_INITIALIZER}});
    assert.deepEqual(Array.from(await h.qt.i18nNamespaces()), ['ui-copy']);
    await h.qt.bootstrap();
    assert.deepEqual(Array.from(await h.qt.i18nNamespaces()), ['ui-copy']);
    await h.qt.refresh();
    assert.deepEqual(Array.from(await h.qt.i18nNamespaces()), []);
    await h.qt.refresh();
    assert.deepEqual(Array.from(await h.qt.i18nNamespaces()), ['replacement-copy']);
    h.qt.dispose();
});

test('槽位与作品类型命名空间去重，缺省字段和任意 metadata 不增加资源请求', async () => {
    const h = harness([manifest(1, [typeDescriptor()], 'epoch-a', [
        uiSlotDescriptor({i18nNamespace: 'demo-i18n'}),
        uiSlotDescriptor({slotId: 'ui.empty', metadata: {i18nNamespace: 'legacy-copy'}})
    ])], {
        '/modules/demo.js': {initializer: BASIC_INITIALIZER},
        '/modules/ui-slot.js': {ui: true, initializer: UI_INITIALIZER}
    });
    assert.deepEqual(Array.from(await h.qt.i18nNamespaces()), ['demo', 'demo-i18n']);
    await h.qt.bootstrap();
    assert.deepEqual(Array.from(await h.qt.i18nNamespaces()), ['demo', 'demo-i18n']);
    h.qt.dispose();
});
