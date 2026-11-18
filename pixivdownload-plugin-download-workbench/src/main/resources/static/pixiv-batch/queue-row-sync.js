'use strict';

// 每个页面独立持有索引与脏行；业务队列仍由调用方拥有。
// store.rows 按复合键保存行快照，store.rowKeys 只表达顺序：行组件按键读取自己的快照，
// 重排、插入和删除只替换结构键，未变化的行不因位置移动而重新渲染。
// store.statusRevision 在结构或任一行状态变化时递增，按状态派生的视图（当前卡）据此重算，
// 纯数值进度不再逐帧遍历整个队列。
function createQueueRowSync(rowKey, snapshot) {
    const dirtyRows = new Map();
    const rowIndexes = new Map();
    // 复合键 → 当前快照对应的源队列项、快照与源字段数；只持有引用，不复制业务数据。
    const rowSources = new Map();
    let lastQueue = null;
    let fullRefresh = false;
    let structureChanged = false;

    function writeRow(store, key, item) {
        const previous = rowSources.get(key);
        const next = snapshot(item);
        rowSources.set(key, {item, snapshot: next, fieldCount: Object.keys(item).length});
        store.rows.set(key, next);
        return !previous || previous.snapshot.status !== next.status;
    }

    // 结构同步时识别未标记但已原地修改的行：比较顶层字段的值与字段数，覆盖新增、删除和替换。
    // 嵌套对象的原地修改仍须调用方标记该行。
    function sourceChanged(source, item) {
        if (!source || source.item !== item) return true;
        const fields = Object.keys(item);
        if (fields.length !== source.fieldCount) return true;
        return fields.some(field => item[field] !== source.snapshot[field]);
    }

    function sameKeys(current, next) {
        if (current.length !== next.length) return false;
        for (let index = 0; index < next.length; index++) {
            if (current[index] !== next[index]) return false;
        }
        return true;
    }

    return {
        mark(item) {
            if (item) dirtyRows.set(rowKey(item), item);
            else fullRefresh = true;
        },
        // 队列只改变顺序或成员时调用：重算结构键，内容未变化的行沿用原快照。
        markStructure() {
            structureChanged = true;
        },
        sync(queue, store) {
            if (store && queue) {
                let structural = fullRefresh || structureChanged || lastQueue !== queue
                    || store.rowKeys.length !== queue.length;
                dirtyRows.forEach((item, key) => {
                    const index = rowIndexes.get(key);
                    if (index === undefined || queue[index] !== item) structural = true;
                });
                let statusChanged = false;
                if (structural) {
                    rowIndexes.clear();
                    lastQueue = queue;
                    const keys = queue.map((item, index) => {
                        const key = rowKey(item);
                        rowIndexes.set(key, index);
                        // 完整同步（语言、重挂、批量状态变化）重建全部快照；其余结构变化只重建变动行。
                        if (fullRefresh || dirtyRows.has(key) || sourceChanged(rowSources.get(key), item)) {
                            writeRow(store, key, item);
                        }
                        return key;
                    });
                    Array.from(store.rows.keys()).forEach(key => {
                        if (rowIndexes.has(key)) return;
                        rowSources.delete(key);
                        store.rows.delete(key);
                    });
                    // 完整同步即使顺序未变也替换结构键，让依赖结构的视图承接语言刷新。
                    if (fullRefresh || !sameKeys(store.rowKeys, keys)) store.rowKeys = keys;
                    statusChanged = true;
                } else {
                    // 原始队列在 Vue 外更新，替换快照以通知嵌套字段的变化。
                    dirtyRows.forEach((item, key) => {
                        if (writeRow(store, key, item)) statusChanged = true;
                    });
                }
                if (statusChanged) store.statusRevision = (store.statusRevision || 0) + 1;
            }
            dirtyRows.clear();
            fullRefresh = false;
            structureChanged = false;
        },
        clear() {
            dirtyRows.clear();
            rowIndexes.clear();
            rowSources.clear();
            lastQueue = null;
            fullRefresh = false;
            structureChanged = false;
        }
    };
}
