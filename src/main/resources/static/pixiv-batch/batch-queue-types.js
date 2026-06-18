'use strict';
// ============================================================
//  下载页扩展点装配：作品类型（work-type 轴）注册中心 + 获取方式标签页（acquisition 轴）元数据
//  + 通用 UI 槽位机制（取得侧控件据扩展点动态生成 DOM）。
//
//  - 数据来自 /api/download/extensions（合并各已启用插件贡献的 queueTypes / tabs）。
//  - 行为与 UI 由各类型的行为模块在运行期向本注册中心 register：descriptor 携带
//    { pluginId, type, display, slots, process, acquisition, import, filters, settings }。
//      · process    —— 下载行为（队列引擎据 item.kind 派发）。
//      · slots      —— 该类型向宿主页贡献的 DOM 片段（kind 单选选项 / 设置卡 / 专属筛选 / 导入提示 / 入口按钮）。
//      · acquisition —— 取得侧行为钩子（按取得模式 user/search/series/quick 分组：抓取端点 / 渲染器 /
//                       队列 id / 队列 meta / 队列 source / 分页大小 / 快捷动作等）。宿主取得侧只面向这些
//                       钩子调用，自身不再按类型字面量分流；插画为内置默认路径、无需经钩子。
//      · import     —— 批量导入单作品时该类型的链接 / 区段头 / 裸 id 解析与入队项构造。
//      · filters    —— 附加筛选里该类型的专属字段（显隐 / 逐作品匹配 / 下载跳过判定）。
//      · settings   —— 该类型的设置卡标识，供宿主按模式 / kind 显隐。
//  - 统一队列引擎（batch-download.js 的 processSingle）据 item.kind 多态派发 process；
//    某类型插件被禁用 → 其不在 /api/download/extensions 响应中 → 其行为模块不加载 → 其 slots
//    不注入（对应入口在宿主页自然消失）+ 取得侧钩子缺席（宿主回退插画、不再发起其专属抓取）
//    + 残留队列项标记暂停（见 processSingle）。
//
//  通用槽位机制：宿主页在各开放位置放置 <template data-qt-slot="<名字>"> 锚点；本模块据已启用类型
//  把各类型 descriptor.slots[名字] 注入为锚点处的**真实兄弟节点**（保留相邻选择器 / flex 布局），再移除
//  模板锚点。锚点名字是宿主与贡献方之间的通用契约，不含任何具体类型字样——插画为内置类型（无 slots、
//  由宿主内联注册行为），小说等由各自插件经 moduleUrl 指向的行为模块贡献 slots。
// ============================================================
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.queueTypes = (function () {
    const behaviors = new Map();    // type -> descriptor { pluginId, type, display, slots, process }
    const loadedModules = new Set(); // 已加载的行为模块 URL（去重）
    let enabledTypes = new Set();   // 后端报告为已启用的作品类型
    let orderedTypes = [];          // 已启用作品类型按贡献 order 排序（slots 渲染与子模式顺序据此）
    let tabMeta = [];               // [{tabId, order, supportedQueueTypes}]
    let bootstrapped = false;       // 是否已拿到 /api/download/extensions 权威数据

    // 注册一个作品类型的行为 + UI 贡献。descriptor 至少含 process(item)（下载行为）；
    // 可选 pluginId / type / display（子模式标签 i18n key）/ slots（DOM 片段贡献）。
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

    // 某作品类型是否「可用」：后端报告启用 **且** 其行为模块已注册。某类型的行为模块只有在后端
    // 报告它启用时才会被加载（见 bootstrap），故「已注册」即蕴含「模块已加载」；拉取扩展点失败时
    // bootstrapped=false（isEnabled 恒真）但外部类型模块从未加载（has 为假）→ 该类型不可用，宿主
    // 取得侧调用自然回退到内置（插画）路径。插画为内置类型、载入即注册，恒可用（除非后端显式禁用）。
    function isTypeAvailable(type) {
        return has(type) && isEnabled(type);
    }

    // 把请求的作品类型解析为一个**可用**类型：可用则原样返回，否则回退（默认插画）。
    // 取得侧任何「按 kind 发起抓取 / 渲染」前都应先经此解析，确保不可用类型不会触发其专属请求。
    function resolveType(type, fallback) {
        const fb = fallback || 'illust';
        if (isTypeAvailable(type)) return type;
        return isTypeAvailable(fb) ? fb : 'illust';
    }

    // 规范化一个「持久化 / 选中」的作品类型：在允许集合内且可用则保留，否则取允许集合里第一个可用类型，
    // 再否则回退插画。用于页面初始化时把存储里残留的、当前不可用的 kind（如禁用小说后仍存 'novel'）
    // 收敛为可用默认值，调用方据返回值同步 UI 单选。allowed 缺省为全部已注册类型。
    function normalizeSelectedType(type, allowed) {
        const allow = (Array.isArray(allowed) && allowed.length) ? allowed : Array.from(behaviors.keys());
        if (allow.indexOf(type) !== -1 && isTypeAvailable(type)) return type;
        const firstAvail = allow.find(t => isTypeAvailable(t));
        return firstAvail || 'illust';
    }

    // descriptor 访问器（get 的语义别名，读起来更贴近调用意图）。
    function descriptor(type) {
        return behaviors.get(type);
    }

    // 取某可用类型的「取得侧行为钩子」（acquisition）。mode 给定时返回该取得模式（user/search/series/quick）
    // 的子钩子对象，否则返回整个 acquisition。**类型不可用时返回 null** —— 宿主据此回退到内置（插画）路径，
    // 故禁用 / 缺席类型不会暴露任何抓取 / 渲染钩子（取得侧不会再产生其专属请求）。
    function acquisition(type, mode) {
        if (!isTypeAvailable(type)) return null;
        const d = behaviors.get(type);
        const acq = d && d.acquisition;
        if (!acq) return null;
        return mode ? (acq[mode] || null) : acq;
    }

    // 汇总所有**可用**类型在某取得模式（user/search/series/quick）下的钩子，按贡献 order，每项 {type, ...该模式钩子}。
    // 供宿主在「需要遍历各类型」的取得点使用（如系列 URL 解析按各类型 parseUrl 逐一尝试）。
    function acquisitionList(mode) {
        return orderedTypes
            .filter(t => isTypeAvailable(t))
            .map(t => { const a = acquisition(t, mode); return a ? Object.assign({type: t}, a) : null; })
            .filter(Boolean);
    }

    // 取某可用类型贡献的「专属筛选」钩子（附加筛选里的字段显隐 / 逐作品匹配 / 下载跳过判定）；不可用→null。
    function filtersFor(type) {
        if (!isTypeAvailable(type)) return null;
        const d = behaviors.get(type);
        return (d && d.filters) || null;
    }

    // 取某可用类型贡献的「设置钩子」（设置卡 id 等，供宿主按模式 / kind 显隐其设置卡）；不可用→null。
    function settingsFor(type) {
        if (!isTypeAvailable(type)) return null;
        const d = behaviors.get(type);
        return (d && d.settings) || null;
    }

    // 取某可用类型贡献的「快捷获取」入口动作（action → 处理元数据），供宿主 quickLoad 的动作映射合并；不可用→{}。
    function quickActionsFor(type) {
        const acq = acquisition(type, 'quick');
        return (acq && acq.actions) || {};
    }

    // 汇总所有**可用**类型贡献的某一类钩子（按 slots 渲染同序：贡献 order）。key='import' / 'filters'。
    // 各元素形如 {type, ...该类型的对应钩子}。宿主用它驱动「批量导入解析」「附加筛选字段集合」等多类型聚合点。
    function contributionsOf(key) {
        return orderedTypes
            .filter(t => isTypeAvailable(t))
            .map(t => behaviors.get(t))
            .filter(d => d && d[key])
            .map(d => Object.assign({type: d.type}, d[key]));
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

    // 拉取并装配下载页扩展点：登记已启用类型 + 标签页元数据，加载各类型行为模块，再据其 slots 贡献
    // 把取得侧控件注入宿主锚点。拉取失败：保持 bootstrapped=false（isEnabled 恒真）→ 维持页面 HTML 默认、
    // 不注入任何插件 slot（插画内置行为照常可用，仅外部类型的入口缺席——优雅降级）。
    async function bootstrap() {
        let data = null;
        try {
            const res = await fetch(BASE + '/api/download/extensions', {credentials: 'same-origin'});
            if (res.ok) data = await res.json();
        } catch (e) {
            console.warn('[queueTypes] 拉取下载页扩展点失败：', e);
        }
        if (!data) return;
        const queueTypes = (data.queueTypes || []).slice()
            .sort((a, b) => (a.order - b.order) || String(a.type).localeCompare(String(b.type)));
        enabledTypes = new Set(queueTypes.map(t => t.type));
        orderedTypes = queueTypes.map(t => t.type);
        tabMeta = data.tabs || [];
        bootstrapped = true;
        await Promise.all(queueTypes
            .filter(t => t.moduleUrl)
            .map(t => loadModule(t.moduleUrl)));
        renderSlots();
    }

    // 据已启用作品类型，把各类型 descriptor.slots 贡献的 DOM 片段注入宿主锚点。
    // 锚点 <template data-qt-slot="名字"> 渲染为空（JS 未跑 / 拉取失败时入口自然缺席）；本流程把每个已启用
    // 类型的 slots[名字] 以 beforebegin 插到同名锚点前（真实兄弟节点、按类型 order 叠放），随后移除全部锚点模板。
    // 本流程一次性（锚点被消费 / 移除）。某类型禁用 → 其 slots 不渲染 → 对应取得侧入口在宿主页消失。
    function renderSlots() {
        orderedTypes.forEach(type => {
            if (!enabledTypes.has(type)) return;
            const descriptor = behaviors.get(type);
            const slots = (descriptor && descriptor.slots) || {};
            Object.keys(slots).forEach(name => {
                const raw = slots[name];
                const html = typeof raw === 'function' ? raw() : raw;
                if (html == null) return;
                document.querySelectorAll('template[data-qt-slot="' + name + '"]')
                    .forEach(marker => marker.insertAdjacentHTML('beforebegin', html));
            });
        });
        // 注入的片段带 data-i18n，重跑页面级 i18n 绑定（与语言切换同一幂等流程）后再清理锚点。
        if (typeof pageI18n !== 'undefined' && pageI18n) pageI18n.apply(document.body);
        document.querySelectorAll('template[data-qt-slot]').forEach(t => t.remove());
    }

    return {
        register, get, has, isEnabled, bootstrap,
        isTypeAvailable, resolveType, normalizeSelectedType, descriptor,
        acquisition, acquisitionList, filtersFor, settingsFor, quickActionsFor, contributionsOf
    };
})();
