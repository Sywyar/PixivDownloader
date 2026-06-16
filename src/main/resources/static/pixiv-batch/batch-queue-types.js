'use strict';
// ============================================================
//  下载页扩展点装配：作品类型（work-type 轴）注册中心 + 获取方式标签页（acquisition 轴）元数据。
//  数据来自 /api/download/extensions（合并各已启用插件贡献的 queueTypes / tabs），行为由各类型的
//  行为模块在运行期 register。统一队列引擎（batch-download.js 的 processSingle）据 item.kind 多态派发；
//  某类型插件被禁用 → 其不再出现在响应中 → 隐藏对应入口（kind 单选 / 设置卡 / 专属筛选）+ 残留队列项暂停。
//  插画为内置类型（无行为模块、由宿主内联注册）；小说等由各自插件经 moduleUrl 提供行为模块。
// ============================================================
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.queueTypes = (function () {
    const behaviors = new Map();    // type -> { process(item) }
    const loadedModules = new Set(); // 已加载的行为模块 URL（去重）
    let enabledTypes = new Set();   // 后端报告为已启用的作品类型
    let tabMeta = [];               // [{tabId, order, supportedQueueTypes}]
    let bootstrapped = false;       // 是否已拿到 /api/download/extensions 权威数据

    // 标签页 id -> 取其某作品类型 kind 单选选项 DOM 的函数。仅 user / search / quick-fetch 有 kind 单选；
    // single-import（经文本区段头）/ series（自 URL 判定）无独立 kind 单选，其小说能力随全局小说入口显隐。
    const TAB_KIND_OPTION = {
        'user': type => document.querySelectorAll('#user-kind-switcher [data-kind="' + type + '"]'),
        'search': type => document.querySelectorAll('#search-kind-switcher [data-kind="' + type + '"]'),
        'quick-fetch': type => document.querySelectorAll('#quick-inner-kind-switcher [data-quick-kind="' + type + '"]')
    };

    function register(type, descriptor) {
        if (!type || !descriptor || typeof descriptor.process !== 'function') {
            console.warn('[queueTypes] 忽略无效的作品类型注册：', type);
            return;
        }
        behaviors.set(type, descriptor);
    }

    function get(type) {
        return behaviors.get(type);
    }

    function has(type) {
        return behaviors.has(type);
    }

    // 某作品类型是否启用：拿到权威数据前一律视为启用（维持页面默认、不误隐藏）。
    function isEnabled(type) {
        return !bootstrapped || enabledTypes.has(type);
    }

    function loadModule(url) {
        if (!url || loadedModules.has(url)) return Promise.resolve();
        loadedModules.add(url);
        return new Promise(resolve => {
            const s = document.createElement('script');
            s.src = url;
            s.async = false;
            s.onload = () => resolve();
            s.onerror = () => {
                console.warn('[queueTypes] 作品类型行为模块加载失败：', url);
                resolve();
            };
            (document.head || document.documentElement).appendChild(s);
        });
    }

    // 拉取并装配下载页扩展点：登记已启用类型 + 标签页元数据，加载各类型行为模块，再据启用情况显隐入口。
    // 拉取失败：保持 bootstrapped=false（isEnabled 恒真）→ 维持页面 HTML 默认，插画内置行为照常可用。
    async function bootstrap() {
        let data = null;
        try {
            const res = await fetch(BASE + '/api/download/extensions', {credentials: 'same-origin'});
            if (res.ok) data = await res.json();
        } catch (e) {
            console.warn('[queueTypes] 拉取下载页扩展点失败：', e);
        }
        if (!data) return;
        enabledTypes = new Set((data.queueTypes || []).map(t => t.type));
        tabMeta = data.tabs || [];
        bootstrapped = true;
        await Promise.all((data.queueTypes || [])
            .filter(t => t.moduleUrl)
            .map(t => loadModule(t.moduleUrl)));
        applyVisibility();
    }

    // 据各类型启用情况显隐其入口。重跑页面既有的小说显隐（此时 isEnabled 已反映真实启用情况，二者经
    // isEnabled 单一来源对齐），再隐藏「始终可见、既有逻辑不管理」的入口（kind 单选选项 / 快捷小说按钮）。
    function applyVisibility() {
        if (typeof applyNovelSettingsVisibility === 'function') applyNovelSettingsVisibility();
        if (typeof applySearchKindUI === 'function') applySearchKindUI();
        // 子模式 = 标签页支持的类型 ∩ 已启用类型：不在交集中的类型，隐藏其 kind 单选选项。
        tabMeta.forEach(tab => {
            const optionsOf = TAB_KIND_OPTION[tab.tabId];
            if (!optionsOf) return;
            (tab.supportedQueueTypes || []).forEach(type => {
                if (type === 'illust' || isEnabled(type)) return; // 插画为默认项、无独立可隐藏选项
                optionsOf(type).forEach(el => { el.style.display = 'none'; });
            });
        });
        // 快捷获取的小说专属按钮不属于 kind 单选、既有逻辑不管理，随小说启停。
        if (!isEnabled('novel')) {
            document.querySelectorAll(
                '[data-quick="my-novel-bookmarks-show"],[data-quick="my-novel-bookmarks-hide"],[data-quick="my-novels"]')
                .forEach(el => { el.style.display = 'none'; });
        }
    }

    return {register, get, has, isEnabled, bootstrap};
})();
