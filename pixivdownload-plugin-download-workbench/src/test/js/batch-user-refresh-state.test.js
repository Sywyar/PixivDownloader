'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
const QUEUE_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-queue.js'), 'utf8');
const INIT_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-init.js'), 'utf8');
const USER_SOURCE = fs.readFileSync(path.join(STATIC, 'modes', 'user.js'), 'utf8');
const HTML_SOURCE = fs.readFileSync(path.join(STATIC, '..', 'pixiv-batch.html'), 'utf8');
const DOUYIN_SEC_UID = 'MS4wLjABAAAA-regression-fixture';
const SOURCE_A = 'source-a';
const SOURCE_B = 'plugin/source:b';
const SOURCE_A_KEY = 'pixiv_batch_user_input:source-a';
const SOURCE_B_KEY = 'pixiv_batch_user_input:plugin/source:b';
const SOURCE_SELECTION_KEY = 'pixiv_batch_user_data_source';

function captureQueueSaveKeys() {
    const writes = [];
    const sandbox = {
        window: {PixivBatch: {}},
        state: {
            queue: [{id: 'work-1', status: 'idle'}],
            isRunning: false,
            isPaused: false,
            userId: DOUYIN_SEC_UID,
            username: DOUYIN_SEC_UID,
            stats: {success: 0, failed: 0, active: 0, skipped: 0}
        },
        storeSet(key, value) { writes.push([key, value]); },
        normalizeAuthorId: value => value,
        normalizeImportMode: value => value,
        SINGLE_IMPORT_MODE: 'single-import',
        SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
        syncSearchResultsQueueState() {},
        syncSeriesResultsQueueState() {},
        syncUserResultsQueueState() {},
        syncQuickQueueState() {},
        updateAdminPackButton() {},
        document: {getElementById: () => null},
        console: {warn() {}, log() {}, error() {}}
    };
    vm.createContext(sandbox);
    vm.runInContext(QUEUE_SOURCE, sandbox);
    vm.runInContext('updateStats = function () {}; renderQueue = function () {};', sandbox);
    sandbox.window.PixivBatch.queue.removeFromQueue('work-1');
    return writes.map(([key]) => key);
}

function createUserDraftHarness(storage, initialSource, availableSources = [SOURCE_A, SOURCE_B]) {
    let selection = {sourceId: initialSource, type: 'owner-type'};
    let scheduleUpdates = 0;
    const inputListeners = new Set();
    const input = {
        value: '',
        addEventListener(type, listener) {
            if (type === 'input') inputListeners.add(listener);
        }
    };
    const sandbox = {
        window: {
            PixivBatch: {
                modes: {},
                modeControls: {
                    selection() { return {...selection}; },
                    selectSource(_mode, sourceId) {
                        if (!availableSources.includes(sourceId)) return false;
                        const changed = selection.sourceId !== sourceId;
                        selection = {sourceId, type: 'owner-type'};
                        return changed;
                    }
                }
            }
        },
        document: {
            getElementById(id) { return id === 'user-id-input' ? input : null; }
        },
        state: {settings: {}, queue: []},
        searchState: {},
        storeGet(key) { return storage.has(key) ? storage.get(key) : null; },
        storeSet(key, value) { storage.set(key, String(value)); },
        storeRemove(key) { storage.delete(key); },
        saveSettings() {},
        applyNovelSettingsVisibility() {},
        applySearchKindUI() {},
        updateExtraFiltersCardVisibility() {},
        updateSaveScheduleCardVisibility() { scheduleUpdates++; },
        console: {warn() {}, log() {}, error() {}},
        Map, Set, Promise, URL, URLSearchParams, AbortController
    };
    vm.createContext(sandbox);
    vm.runInContext(USER_SOURCE, sandbox);
    vm.runInContext(`
        applyUserSourceKindAvailability = function () { return false; };
        clearUserPreview = function () {};
    `, sandbox);
    const api = sandbox.window.PixivBatch.modes.user;
    return {
        api,
        input,
        inputListenerCount() { return inputListeners.size; },
        scheduleUpdateCount() { return scheduleUpdates; },
        currentSource() { return selection.sourceId; },
        dispatchInput() {
            Array.from(inputListeners).forEach(listener => listener({target: input}));
        },
        switchSource(sourceId) {
            const previous = {...selection};
            selection = {sourceId, type: 'owner-type'};
            api.handleUserModeControlChange({
                mode: 'user', reason: 'source', previous, selection: {...selection}
            });
        },
        changeType(type) {
            const previous = {...selection};
            selection = {sourceId: selection.sourceId, type};
            api.handleUserModeControlChange({
                mode: 'user', reason: 'type', previous, selection: {...selection}
            });
        },
        withdrawSources() {
            const previous = {...selection};
            selection = {sourceId: null, type: null};
            api.handleUserModeControlChange({
                mode: 'user', reason: 'reconcile', previous, selection: {...selection}
            });
        }
    };
}

{
    const keys = captureQueueSaveKeys();
    assert.deepEqual(keys, ['pixiv_batch_queue'],
        '队列保存不得再持久化来源用户标识');
}

{
    const userInput = (HTML_SOURCE.match(/<input\b[^>]*\bid="user-id-input"[^>]*>/) || [''])[0];
    assert.ok(userInput, '页面应包含 User 输入框');
    assert.doesNotMatch(userInput, /\bvalue\s*=/,
        'User 输入框在 HTML 中应保持空白默认值');
    assert.doesNotMatch(INIT_SOURCE, /pixiv_batch_last_(?:user_id|username)/,
        '页面刷新不得从旧状态恢复 User 输入框');
    assert.doesNotMatch(QUEUE_SOURCE, /pixiv_batch_last_(?:user_id|username)/,
        '队列保存不得重新写入旧 User 草稿键');
    assert.doesNotMatch(USER_SOURCE, /storeSet\(\s*['"]pixiv_batch_last_(?:user_id|username)/,
        'User 模式不得继续写入不带来源维度的旧键');
    const loadSettingsIndex = INIT_SOURCE.indexOf('loadSettings();');
    const initDraftsIndex = INIT_SOURCE.indexOf('initUserInputDraftPersistence();');
    assert.ok(loadSettingsIndex >= 0 && initDraftsIndex >= 0
        && loadSettingsIndex < initDraftsIndex,
        'User 草稿必须在设置收敛来源后恢复');
    const userKindStart = INIT_SOURCE.indexOf("bindKindSwitcher('user-kind-switcher'");
    const searchKindStart = INIT_SOURCE.indexOf("bindKindSwitcher('search-kind-switcher'", userKindStart);
    const userKindCallback = INIT_SOURCE.slice(userKindStart, searchKindStart);
    assert.match(userKindCallback, /updateSaveScheduleCardVisibility\(\)/,
        'User 二级类型切换后必须立即收敛计划任务卡片显隐');
}

{
    const storage = new Map();
    const h = createUserDraftHarness(storage, SOURCE_A);
    h.api.initUserInputDraftPersistence();
    h.api.initUserInputDraftPersistence();
    assert.equal(h.input.value, '', '没有当前来源草稿时首次进入 User 应保持空白');
    assert.equal(h.inputListenerCount(), 1, '重复初始化不得重复绑定输入监听器');
    assert.equal(storage.get(SOURCE_SELECTION_KEY), SOURCE_A,
        '当前 User 数据来源应独立持久化');
}

{
    const storage = new Map([
        [SOURCE_A_KEY, 'https://source-a.example/users/42'],
        [SOURCE_B_KEY, `https://source-b.example/user/${DOUYIN_SEC_UID}`],
        ['pixiv_batch_last_user_id', DOUYIN_SEC_UID],
        ['pixiv_batch_last_username', 'legacy user']
    ]);
    const h = createUserDraftHarness(storage, SOURCE_A);
    h.api.initUserInputDraftPersistence();

    assert.equal(h.input.value, 'https://source-a.example/users/42',
        '初始化只恢复当前来源的原始输入');
    assert.equal(storage.has('pixiv_batch_last_user_id'), false,
        '无法判断来源的旧用户标识应被清除而不是迁移');
    assert.equal(storage.has('pixiv_batch_last_username'), false,
        '旧用户名状态也应被清除');

    h.input.value = 'https://source-a.example/users/84/request';
    h.dispatchInput();
    h.changeType('another-owner-type');
    assert.equal(h.input.value, 'https://source-a.example/users/84/request',
        '同一数据来源内切换作品类型不得替换输入草稿');
    assert.equal(h.scheduleUpdateCount(), 1,
        'User 来源或类型委托变化后必须刷新计划任务卡片显隐');
    h.switchSource(SOURCE_B);
    assert.equal(h.input.value, `https://source-b.example/user/${DOUYIN_SEC_UID}`,
        '切换来源应恢复目标来源自己的草稿');

    h.input.value = 'https://source-b.example/user/raw-input';
    h.dispatchInput();
    h.switchSource(SOURCE_A);
    assert.equal(h.input.value, 'https://source-a.example/users/84/request',
        '切回来源应恢复之前保存的原始输入');
    assert.equal(storage.get(SOURCE_A_KEY), 'https://source-a.example/users/84/request');
    assert.equal(storage.get(SOURCE_B_KEY), 'https://source-b.example/user/raw-input');

    h.switchSource(SOURCE_B);
    assert.equal(storage.get(SOURCE_SELECTION_KEY), SOURCE_B);
    const refreshed = createUserDraftHarness(storage, SOURCE_A);
    refreshed.api.initUserInputDraftPersistence();
    assert.equal(refreshed.currentSource(), SOURCE_B,
        '整页刷新应从持久化状态恢复真实的当前来源');
    assert.equal(refreshed.input.value, 'https://source-b.example/user/raw-input',
        '刷新后应按当前来源恢复原始输入，而不是其他来源或解析标识');

    refreshed.input.value = '';
    refreshed.dispatchInput();
    const clearedRefresh = createUserDraftHarness(storage, SOURCE_A);
    clearedRefresh.api.initUserInputDraftPersistence();
    assert.equal(clearedRefresh.currentSource(), SOURCE_B);
    assert.equal(clearedRefresh.input.value, '',
        '显式清空当前来源后刷新不得复活旧的 canonical 用户标识');

    clearedRefresh.input.value = 'draft kept while unavailable';
    clearedRefresh.dispatchInput();
    clearedRefresh.withdrawSources();
    assert.equal(clearedRefresh.input.value, 'draft kept while unavailable',
        '当前来源消失时 User 应保留正在编辑的输入');
    assert.equal(storage.get(SOURCE_SELECTION_KEY), SOURCE_B,
        '来源暂时不可用时应保留最后一次有效来源选择');

    const unavailableRefresh = createUserDraftHarness(storage, null, []);
    unavailableRefresh.api.initUserInputDraftPersistence();
    assert.equal(unavailableRefresh.currentSource(), null);
    assert.equal(unavailableRefresh.input.value, 'draft kept while unavailable',
        '刷新时来源不可用也应恢复最后一次有效来源的草稿');
    unavailableRefresh.input.value = 'edited while unavailable';
    unavailableRefresh.dispatchInput();
    assert.equal(storage.get(SOURCE_B_KEY), 'edited while unavailable',
        '来源不可用期间的编辑仍应写回最后一次有效来源草稿');
}

console.log('batch-user-refresh-state.test.js: 4 scenarios passed');
