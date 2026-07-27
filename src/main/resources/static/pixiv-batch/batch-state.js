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
            redownloadDeleted: false,   // 允许已删除（软删除标记）的作品被重新下载；默认不勾选 = 跳过
            bookmark: false,
            collectionId: null,
            fileNameTemplate: DEFAULT_FILE_NAME_TEMPLATE,
            novelFormat: 'txt',
            mergeNovelSeries: false,
            mergeNovelFormat: 'epub',
            novelAutoTranslate: false,   // 下载即自动翻译（仅管理员 + 已配置 AI 生效）
            novelTranslateLang: '',      // 空 = 跟随页面语言的默认目标语言；非空 = 用户自定义
            novelTranslateSeg: 0,
            userKind: 'illust',     // 'illust' | 'novel' — User 模式作品类型
            searchKind: 'illust'    // 'illust' | 'novel' — Search 模式作品类型
        }
    };
    // 高频刷新防卡死：把 state 整体纳管为 Vue 响应式，供下载队列等高频区按行细粒度更新
    //（每个进度事件只更新变动行，而非整块 innerHTML 重建）。window.Vue 由页面底部
    // /vendor/vue/vue.global.prod.js 同步提供；缺失时保持普通对象，队列渲染降级为命令式。
    // 全部 batch-*.js 均按名引用 state（无 const 提前捕获），故此处置换 binding 安全。
    if (typeof window.Vue !== 'undefined') {
        state = Vue.reactive(state);
    }

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
