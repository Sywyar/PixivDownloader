import assert from 'node:assert/strict';

export async function verifySlotOrder(page, contribution) {
    const endpoint = '**/api/web/ui-slots?order-fixture=1';
    const slot = (id, order) => ({...contribution, slotId: id, target: 'order.fixture', order});
    const a = slot('order.a', 10), b = slot('order.b', 20), c = slot('order.c', 15);
    let manifest = [a, b];
    await page.route(endpoint, route => route.fulfill({json: manifest}));
    try {
        for (const fallback of [false, true]) {
            manifest = [a, b];
            await page.evaluate(async fallback => {
                const root = document.createElement('section');
                root.id = 'order-fixture';
                const target = document.createElement('div'); target.dataset.uiSlot = 'order.fixture';
                if (fallback) target.moveBefore = undefined;
                root.append(target); document.body.append(root);
                window.orderPage = PixivUiSlots.connect({root, manifestUrl: '/api/web/ui-slots?order-fixture=1'});
                await orderPage.refresh();
                window.orderInput = root.querySelector('[data-ui-instance="order.b"] input');
                window.orderMounts = slotFixture.mounts;
                window.orderBlurs = 0;
                orderInput.addEventListener('blur', () => { window.orderBlurs++; });
                orderInput.focus(); orderInput.setSelectionRange(2, 12, 'backward');
            }, fallback);
            const check = async (ids, added, checkBlur = true) => {
                const actual = await page.evaluate(() => ({
                    order: [...document.querySelectorAll('#order-fixture [data-ui-instance]')].map(node => node.dataset.uiInstance),
                    focused: document.activeElement === orderInput,
                    same: document.querySelector('#order-fixture [data-ui-instance="order.b"] input') === orderInput,
                    selection: [orderInput.selectionStart, orderInput.selectionEnd, orderInput.selectionDirection],
                    mounts: slotFixture.mounts - orderMounts, blurs: orderBlurs
                }));
                assert.deepEqual(actual.order, ids); assert.equal(actual.focused, true);
                assert.equal(actual.same, true); assert.deepEqual(actual.selection, [2, 12, 'backward']);
                assert.equal(actual.mounts, added);
                if (checkBlur) assert.equal(actual.blurs, 0, 'unchanged focused host must not be detached');
            };
            manifest = [b]; await page.evaluate(() => orderPage.refresh());
            await check(['order.b'], 0);
            manifest = [a, b]; await page.evaluate(() => orderPage.refresh());
            await check(['order.a', 'order.b'], 1);
            manifest = [a, c, b]; await page.evaluate(() => orderPage.refresh());
            await check(['order.a', 'order.c', 'order.b'], 2);
            // 改变已有贡献的顺序，覆盖原生移动和兼容路径的焦点、选区恢复。
            manifest = [a, b, c]; await page.evaluate(() => orderPage.refresh());
            await check(['order.a', 'order.b', 'order.c'], 2, false);
            manifest = [b, c, a]; await page.evaluate(() => orderPage.refresh());
            await check(['order.b', 'order.c', 'order.a'], 2, false);
            manifest = [a, c, b]; await page.evaluate(() => orderPage.refresh());
            await check(['order.a', 'order.c', 'order.b'], 2, false);
            await page.evaluate(() => {
                const editor = document.createElement('div'); editor.contentEditable = 'true';
                editor.textContent = 'Editable selection'; orderInput.parentNode.append(editor);
                editor.focus(); getSelection().setBaseAndExtent(editor.firstChild, 12, editor.firstChild, 2);
                window.orderEditor = editor;
            });
            manifest = [b, c, a]; await page.evaluate(() => orderPage.refresh());
            manifest = [a, c, b]; await page.evaluate(() => orderPage.refresh());
            assert.deepEqual(await page.evaluate(() => ({
                focused: document.activeElement === orderEditor,
                anchor: getSelection().anchorNode === orderEditor.firstChild,
                focus: getSelection().focusNode === orderEditor.firstChild,
                offsets: [getSelection().anchorOffset, getSelection().focusOffset]
            })), {focused: true, anchor: true, focus: true, offsets: [12, 2]});
            await page.evaluate(async () => { await orderPage.destroy(); document.getElementById('order-fixture').remove(); });
        }
        console.log('UI_SLOT_ORDER_FOCUS_SELECTION_PASSED');
    } finally {
        await page.evaluate(async () => { await window.orderPage?.destroy(); document.getElementById('order-fixture')?.remove(); });
        await page.unroute(endpoint);
    }
}
