'use strict';
/* ============================================================
   alt-state — 页面状态、运行模式与登录态
   ============================================================ */
const DEFAULT_FILE_NAME_TEMPLATE = '{artwork_id}_p{page}';
const QUICK_FETCH_MODE = 'quick-fetch';
const SINGLE_IMPORT_MODE = 'single-import';

let appMode = 'solo';
let multiModeLimitPage = 0;
let isAdmin = false;
let serverState = {};

let state = {
    mode: SINGLE_IMPORT_MODE,
    queue: [],
    isRunning: false,
    isPaused: false,
    stopRequested: false,
    activeWorkers: 0,
    currentItemId: null,
    userId: '',
    username: '',
    sharedSse: null,
    sharedSseConnectionId: null,
    sseRefs: {},
    sseListeners: {},
    stats: {success: 0, failed: 0, active: 0, skipped: 0},
    speedSamples: {},
    speedAccumBytes: 0,
    speedLastAccum: 0,
    speedLastTime: 0,
    speedTimer: null,
    settings: {
        ...window.PixivBatch.downloadDefaults,
        imageDelay: 0,
        imageDelayUnit: 'ms',
        autoStartOnEnqueue: false,
        verifyHistoryFiles: false,
        redownloadDeleted: false,
        bookmark: false,
        collectionId: null,
        fileNameTemplate: DEFAULT_FILE_NAME_TEMPLATE,
        pathOverflowAction: 'ASK',
        userKind: 'illust',
        searchKind: 'illust',
        novelFormat: 'txt',
        mergeNovelSeries: false,
        mergeNovelFormat: 'epub',
        novelAutoTranslate: false,
        novelTranslateLang: '',
        novelTranslateSeg: 0
    }
};

// 各取得模式的运行态（模型只存原始数据，展示文案渲染时经 bt() 派生）。
let quickState = {
    source: 'pixiv',
    kind: 'illust',
    action: null,
    uid: null,
    allIds: [],
    items: [],
    rawItems: [],
    page: 1,
    totalPages: 1,
    total: 0,
    hasNext: false,
    title: '',
    loading: false,
    loadSeq: 0,
    error: '',
    drill: null,          // {type: 'user'|'collection', id, name}
    drillItems: [],
    users: [],
    usersTotal: 0,
    usersOffset: 0,
    usersFilter: '',
    collections: [],
    filterSummary: null
};

let importState = {
    parsed: [],           // 解析预览（未入队）
    lastSummary: null
};

let userState = {
    source: 'pixiv',
    input: '',
    kind: 'illust',       // illust | request
    userId: '',
    username: '',
    total: 0,
    ids: [],
    items: [],
    rawItems: [],
    page: 1,
    pageSize: 30,
    loading: false,
    error: '',
    filterSummary: null
};

let searchState = {
    source: 'pixiv',
    kind: 'illust',
    word: '',
    order: 'date_d',
    sMode: 's_tag',
    mode: 'all',
    submode: 'search',    // search | batch
    startPage: 1,
    endPage: 1,
    blurR18: false,
    rawResults: [],
    results: [],
    total: 0,
    page: 1,
    batchInfo: null,
    localPage: 1,
    loading: false,
    error: '',
    filterSummary: null,
    metaCache: new WeakMap(),
    filterSeq: 0
};

let seriesState = {
    source: 'pixiv',
    url: '',
    kind: 'illust',       // illust | novel
    info: null,           // {seriesId,title,authorId,authorName,total,coverUrl}
    rawItems: [],
    items: [],
    page: 1,
    isLastPage: true,
    loading: false,
    error: '',
    filterSummary: null
};

let scheduleState = {
    tasks: [],
    sources: [],
    loaded: false,
    error: '',
    editing: null,        // {taskId} | null
    expandedQueues: new Set(),
    pendingTaskId: null
};

let dockState = {
    quota: {enabled: false, adminMode: false, artworksUsed: 0, maxArtworks: 50, resetSeconds: 0},
    archive: null,
    statusMessage: null,
    statusTone: 'info',
    // 最近一次格式化的下载速度（下载坞 Vue 岛重挂后播种用；命令式路径实时写 DOM 不读这里）
    speed: {value: '0', unit: 'B/s'},
    open: false
};

let chromeState = {
    backendAvailable: null,   // null=未知 true/false
    appInfo: null,
    cookieSaved: false
};

/* ============================================================
   运行模式、服务端初始快照与登录态
   ============================================================ */
async function detectMode() {
    try {
        const res = await fetch('/api/setup/status');
        if (res.ok) {
            const data = await res.json();
            appMode = data.mode === 'solo' ? 'solo' : 'multi';
            multiModeLimitPage = Math.max(0, data.multiModeLimitPage ?? 0);
        }
    } catch {
        appMode = 'multi';
    }
}

async function loadServerState() {
    try {
        const res = await fetch(BASE + '/api/batch/state');
        if (res.ok) {
            const data = await res.json();
            serverState = data.state ?? {};
        }
    } catch {
    }
}

async function detectAuthState() {
    try {
        const res = await fetch('/api/auth/check', {credentials: 'same-origin'});
        if (!res.ok) {
            isAdmin = false;
            return;
        }
        const data = await res.json();
        isAdmin = !!data.valid;
    } catch {
        isAdmin = false;
    }
}

window.PixivBatchAlt.state = Object.assign(window.PixivBatchAlt.state, {
    detectMode, detectAuthState, loadServerState,
    storeGet, storeSet, storeRemove, scheduleServerSave, doLogout
});
