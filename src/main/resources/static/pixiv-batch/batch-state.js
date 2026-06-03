'use strict';
    /* ============================================================
       状态
    ============================================================ */
    const DEFAULT_FILE_NAME_TEMPLATE = '{artwork_id}_p{page}';
    const QUICK_FETCH_MODE = 'quick-fetch';
    const SINGLE_IMPORT_MODE = 'single-import';
    const SINGLE_IMPORT_NOVEL_SOURCE = 'single-import-novel';
    let state = {
        mode: QUICK_FETCH_MODE,
        queue: [],
        isRunning: false,
        isPaused: false,
        stopRequested: false,
        activeWorkers: 0,
        currentItemId: null,
        userId: '',
        username: '',
        sharedSse: null,        // 共享 EventSource 单例
        sharedSseConnectionId: null,
        sseRefs: {},            // artworkId -> 引用计数；共享连接由批量任务生命周期统一关闭
        sseListeners: {},
        stats: {success: 0, failed: 0, active: 0, skipped: 0},
        settings: {
            interval: 2,
            intervalUnit: 's',
            imageDelay: 0,
            imageDelayUnit: 'ms',
            concurrent: 1,
            skipHistory: false,
            verifyHistoryFiles: false,
            bookmark: false,
            collectionId: null,
            fileNameTemplate: DEFAULT_FILE_NAME_TEMPLATE,
            novelFormat: 'txt',
            mergeNovelSeries: false,
            mergeNovelFormat: 'epub',
            userKind: 'illust',     // 'illust' | 'novel' — User 模式作品类型
            searchKind: 'illust'    // 'illust' | 'novel' — Search 模式作品类型
        }
    };

    /* ============================================================
       模式检测 & 存储抽象（solo=服务器，multi=localStorage）
    ============================================================ */
    let appMode = 'multi';   // 'solo' | 'multi'，init() 中确定
    let isAdmin = false;
    let serverState = {};    // solo 模式下的状态内存镜像
    let multiModeLimitPage = 0;  // multi 模式下补页上限（0=不限制），来自 /api/setup/status

// ---- PixivBatch facade ----
window.PixivBatch.state = window.PixivBatch.state || {};
window.PixivBatch.state = Object.assign(window.PixivBatch.state, { state, QUICK_FETCH_MODE, SINGLE_IMPORT_MODE, SINGLE_IMPORT_NOVEL_SOURCE, DEFAULT_FILE_NAME_TEMPLATE });
