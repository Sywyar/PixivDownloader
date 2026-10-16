'use strict';

// 每个页面独立持有索引与脏行；业务队列仍由调用方拥有。
function createQueueRowSync(rowKey, snapshot) {
    const dirtyRows = new Map();
    const rowIndexes = new Map();
    let lastQueue = null;
    let fullRefresh = false;

    return {
        mark(item) {
            if (item) dirtyRows.set(rowKey(item), item);
            else fullRefresh = true;
        },
        sync(queue, store) {
            if (store && queue) {
                let rebuild = fullRefresh || lastQueue !== queue || store.items.length !== queue.length;
                dirtyRows.forEach((item, key) => {
                    const index = rowIndexes.get(key);
                    if (index === undefined || queue[index] !== item) rebuild = true;
                });
                if (rebuild) {
                    rowIndexes.clear();
                    lastQueue = queue;
                    const keys = [];
                    store.items = queue.map((item, index) => {
                        const key = rowKey(item);
                        keys.push(key);
                        rowIndexes.set(key, index);
                        return snapshot(item);
                    });
                    store.rowKeys = keys;
                } else {
                    // 原始队列在 Vue 外更新，替换快照以通知嵌套字段的变化。
                    dirtyRows.forEach((item, key) => {
                        store.items[rowIndexes.get(key)] = snapshot(item);
                    });
                }
            }
            dirtyRows.clear();
            fullRefresh = false;
        },
        clear() {
            dirtyRows.clear();
            rowIndexes.clear();
            lastQueue = null;
            fullRefresh = false;
        }
    };
}
