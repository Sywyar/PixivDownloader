'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function fixture(options = {}) {
    const saved = [];
    const toasts = [];
    let form, picker;
    const detail = {
        id: 1, name: 'guest', expireTime: null, allowSfw: true,
        tagUnrestricted: true, authorUnrestricted: true,
        novelTagUnrestricted: true, novelAuthorUnrestricted: true,
        collectionUnrestricted: false, collectionIds: [7, 9], collectionRestrictsWorks: true
    };
    const sandbox = {
        detail, inviteId: 1, cachedTags: [], cachedAuthors: [], cachedNovelTags: [], cachedNovelAuthors: [],
        tr: (key, fallback) => fallback, render() {},
        api: async (url, options) => {
            if (url === '/api/collections') {
                if (sandbox.failCollections) throw new Error('collection load failed');
                return {collections: [{id: 7, name: 'visible'}, {id: 9, name: 'hidden'}]};
            }
            if (options?.method === 'PUT' && sandbox.failSave) throw new Error('save failed');
            if (options?.method === 'PUT') saved.push(JSON.parse(options.body));
            return detail;
        },
        ...options,
        window: {
            PixivInviteDetail: {},
            InviteModals: {
                openInviteFormModal(options) { form = options; },
                openVisibilityPicker(options) { picker = options; },
                showToast(message, kind) { toasts.push({message, kind}); }
            }
        }
    };
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/pixiv-invite-detail/invite-detail-actions.js'), 'utf8'), sandbox);
    return {sandbox, saved, toasts, form: () => form, picker: () => picker};
}

test('编辑邀请回填收藏夹选择和作品限制开关', async () => {
    const f = fixture();
    f.sandbox.openEditModal();
    const prefill = f.form().prefill;
    assert.equal(prefill.collectionUnrestricted, false);
    assert.deepEqual(Array.from(prefill.collectionIds), [7, 9]);
    assert.equal(prefill.collectionRestrictsWorks, true);
    await f.form().onSubmit({...prefill, collectionRestrictsWorks: false});
    assert.equal(f.saved[0].collectionRestrictsWorks, false);
    assert.deepEqual(f.saved[0].collectionIds, [7, 9]);
});

for (const kind of ['tag', 'author', 'novel-tag', 'novel-author']) {
    test(`单独修改 ${kind} 保留收藏夹权限`, async () => {
        const f = fixture();
        f.sandbox.openViewDetailPicker(kind);
        await new Promise(resolve => setImmediate(resolve));
        await f.picker().onSubmit({unrestricted: false, ids: [10]});
        assert.equal(f.saved[0].collectionUnrestricted, false);
        assert.deepEqual(f.saved[0].collectionIds, [7, 9]);
        assert.equal(f.saved[0].collectionRestrictsWorks, true);
    });
}

test('详情行可编辑收藏夹与作品开关，并保留其它可见范围', async () => {
    const f = fixture();
    const detail = f.sandbox.detail;
    Object.assign(detail, {tags: [{tagId: 11}], authors: [{authorId: 12}],
        novelTags: [{tagId: 13}], novelAuthors: [{authorId: 14}]});
    await f.sandbox.openViewDetailPicker('collection');
    assert.equal(f.picker().unrestricted, false);
    assert.deepEqual(Array.from(f.picker().selectedIds), [7, 9]);
    assert.equal(f.picker().collectionRestrictsWorks, true);
    assert.deepEqual(Array.from(f.picker().items, item => item.id), [7, 9]);
    await f.picker().onSubmit({unrestricted: false, ids: [7], collectionRestrictsWorks: false});
    assert.deepEqual(f.saved[0], {
        name: 'guest', expireDays: null, allowSfw: true,
        tagUnrestricted: true, tagIds: [11], authorUnrestricted: true, authorIds: [12],
        novelTagUnrestricted: true, novelTagIds: [13], novelAuthorUnrestricted: true, novelAuthorIds: [14],
        collectionUnrestricted: false, collectionIds: [7], collectionRestrictsWorks: false
    });
    await f.picker().onSubmit({unrestricted: true, ids: [7], collectionRestrictsWorks: true});
    assert.equal(f.saved[1].collectionUnrestricted, true);
    assert.deepEqual(f.saved[1].collectionIds, []);
    assert.equal(f.saved[1].collectionRestrictsWorks, true);
});

test('收藏夹读取失败显示错误且不打开空选择器，保存失败交由弹窗保留草稿', async () => {
    const unavailable = fixture({failCollections: true});
    await unavailable.sandbox.openViewDetailPicker('collection');
    assert.equal(unavailable.picker(), undefined);
    assert.equal(unavailable.toasts[0].kind, 'error');
    assert.equal(unavailable.saved.length, 0);
    const f = fixture({failSave: true});
    await f.sandbox.openViewDetailPicker('collection');
    await assert.rejects(f.picker().onSubmit({unrestricted: false, ids: [], collectionRestrictsWorks: true}), /save failed/);
    assert.equal(f.saved.length, 0);
    assert.equal(f.toasts.length, 0);
});
