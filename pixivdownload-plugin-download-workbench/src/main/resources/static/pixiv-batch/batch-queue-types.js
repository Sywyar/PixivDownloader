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
//  通用槽位机制：宿主页在各开放位置放置 <template data-qt-slot="<名字>"> 锚点；本模块据已启用类型把各类型
//  descriptor.slots[名字] 的片段渲染进该锚点在模板原位备好的 [data-vue-slot] 宿主——**主路径经共享 helper 用
//  Vue 渲染**（renderSlots → PixivVue.mount），命令式注入为锚点真实兄弟节点只是 Vue 不可用 / 运行时加载失败 /
//  挂载失败时的回退；随后移除模板锚点。锚点名字是宿主与贡献方之间的通用契约，不含任何具体类型字样——插画为
//  内置类型（无 slots、由宿主内联注册行为），小说等由各自插件经 moduleUrl 指向的行为模块贡献 slots。
//
//  Web UI 槽位清单（uiSlots）：/api/download/extensions 现额外返回各活动插件向后端声明的 UI 槽位
//  （{slotId, target, moduleUrl, order, metadata}）——把「页面槽位」从纯前端约定提升为后端可追踪、随插件
//  生命周期动态注册/注销的契约。本模块拉取后存为清单并经 uiSlots() 暴露；片段的实际渲染由上面的
//  descriptor.slots（行为模块提供 HTML）经 Vue 主路径承载、命令式回退兜底，清单为声明式来源（插件停用即其槽位从清单消失）。
// ============================================================
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.queueTypes = (function () {
    const CONTRACT_VERSION = 1;
    const behaviors = new Map();    // type -> descriptor { pluginId, type, display, slots, process }
    const backendDescriptors = new Map(); // type -> /api/download/extensions downloadTypes descriptor
    const loadedModules = new Set(); // 已加载的行为模块 URL（去重）
    let extensionData = null;        // /api/download/extensions 成功响应缓存；i18n 预取与 bootstrap 共用
    let extensionDataPromise = null;
    let enabledTypes = new Set();   // 后端报告为已启用的作品类型
    let orderedTypes = [];          // 已启用作品类型按贡献 order 排序（slots 渲染与子模式顺序据此）
    let tabMeta = [];               // [{tabId, order, supportedQueueTypes}]
    let uiSlotsManifest = [];       // 后端声明的 UI 槽位清单（/api/download/extensions 的 uiSlots，按 order 已排序）
    let bootstrapped = false;       // 是否已拿到 /api/download/extensions 权威数据

    function warnInvalid(type, reason) {
        console.warn('[queueTypes] 忽略无效的作品类型注册：', type, reason || '');
    }

    function isPlainObject(value) {
        return !!value && typeof value === 'object' && !Array.isArray(value);
    }

    // 前端行为模块 descriptor 契约：
    // {
    //   contractVersion?: 1,
    //   pluginId, type, display,
    //   process(item),
    //   import?: { sectionType, matchUrl(line), buildItem(matchOrId, title, line), source },
    //   acquisition?: { user?, series?, search?, quick? },
    //   filters?: object,
    //   settings?: object,
    //   slots|uiSlots?: object
    // }
    // 无效模块不抛出到页面主流程，只 warning 并跳过，使插件损坏 / 前端不可用时下载页不白屏。
    function validateDescriptor(type, descriptor) {
        if (!type || !descriptor || typeof descriptor.process !== 'function') {
            return 'missing process(item)';
        }
        const version = descriptor.contractVersion || CONTRACT_VERSION;
        if (version !== CONTRACT_VERSION) {
            return 'unsupported contractVersion=' + version;
        }
        if (descriptor.type && descriptor.type !== type) {
            return 'descriptor.type mismatch: ' + descriptor.type;
        }
        if (descriptor.import) {
            if (!isPlainObject(descriptor.import)) return 'import must be an object';
            if (descriptor.import.matchUrl && typeof descriptor.import.matchUrl !== 'function') {
                return 'import.matchUrl must be a function';
            }
            if (descriptor.import.buildItem && typeof descriptor.import.buildItem !== 'function') {
                return 'import.buildItem must be a function';
            }
        }
        if (descriptor.acquisition) {
            if (!isPlainObject(descriptor.acquisition)) return 'acquisition must be an object';
            for (const mode of ['user', 'series', 'search', 'quick']) {
                if (descriptor.acquisition[mode] && !isPlainObject(descriptor.acquisition[mode])) {
                    return 'acquisition.' + mode + ' must be an object';
                }
            }
        }
        if (descriptor.filters && !isPlainObject(descriptor.filters)) return 'filters must be an object';
        if (descriptor.settings && !isPlainObject(descriptor.settings)) return 'settings must be an object';
        if (descriptor.slots && !isPlainObject(descriptor.slots)) return 'slots must be an object';
        if (descriptor.uiSlots && !isPlainObject(descriptor.uiSlots)) return 'uiSlots must be an object';
        return null;
    }

    // 注册一个作品类型的行为 + UI 贡献。descriptor 至少含 process(item)（下载行为）。
    function register(type, descriptor) {
        const reason = validateDescriptor(type, descriptor);
        if (reason) {
            warnInvalid(type, reason);
            return false;
        }
        const normalized = Object.assign({}, descriptor);
        normalized.contractVersion = normalized.contractVersion || CONTRACT_VERSION;
        normalized.type = normalized.type || type;
        normalized.slots = normalized.slots || normalized.uiSlots || {};
        behaviors.set(type, normalized);
        return true;
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

    // 后端声明的 UI 槽位清单（来自 /api/download/extensions 的 uiSlots，已按 order 排序）。
    // 返回副本防外部改写内部状态。拿到权威数据前为空（与「未 bootstrap 即不渲染插件槽位」一致）。
    function uiSlots() {
        return uiSlotsManifest.slice();
    }

    // 后端声明的下载类型 descriptor 清单（来自 /api/download/extensions 的 downloadTypes，已按 order 排序）。
    function downloadTypes() {
        return Array.from(backendDescriptors.values()).map(item => Object.assign({}, item));
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

    async function fetchExtensionData() {
        if (extensionData) return extensionData;
        if (extensionDataPromise) return extensionDataPromise;
        extensionDataPromise = (async function () {
            try {
                const res = await fetch(BASE + '/api/download/extensions', {credentials: 'same-origin'});
                if (res.ok) {
                    extensionData = await res.json();
                    return extensionData;
                }
            } catch (e) {
                console.warn('[queueTypes] 拉取下载页扩展点失败：', e);
            } finally {
                if (!extensionData) extensionDataPromise = null;
            }
            return null;
        })();
        return extensionDataPromise;
    }

    function addNamespace(out, seen, value) {
        const namespace = value == null ? '' : String(value).trim();
        if (!namespace || seen.has(namespace)) return;
        seen.add(namespace);
        out.push(namespace);
    }

    async function i18nNamespaces() {
        const data = await fetchExtensionData();
        if (!data) return [];
        const out = [];
        const seen = new Set();
        (data.queueTypes || []).forEach(item => addNamespace(out, seen, item && item.labelNamespace));
        (data.downloadTypes || []).forEach(item => {
            addNamespace(out, seen, item && item.displayNamespace);
            addNamespace(out, seen, item && item.i18nNamespace);
            const gallery = item && item.gallery;
            addNamespace(out, seen, gallery && gallery.reasonNamespace);
        });
        return out;
    }

    // 拉取并装配下载页扩展点：登记已启用类型 + 标签页元数据，加载各类型行为模块，再据其 slots 贡献
    // 把取得侧控件注入宿主锚点。拉取失败：保持 bootstrapped=false（isEnabled 恒真）→ 维持页面 HTML 默认、
    // 不注入任何插件 slot（插画内置行为照常可用，仅外部类型的入口缺席——优雅降级）。
    async function bootstrap() {
        const data = await fetchExtensionData();
        if (!data) return;
        const queueTypes = (data.queueTypes || []).slice()
            .sort((a, b) => (a.order - b.order) || String(a.type).localeCompare(String(b.type)));
        const downloadTypeList = (data.downloadTypes || []).slice()
            .sort((a, b) => (a.order - b.order) || String(a.type).localeCompare(String(b.type)));
        backendDescriptors.clear();
        downloadTypeList.forEach(item => {
            if (item && item.type) backendDescriptors.set(item.type, item);
        });
        enabledTypes = new Set(queueTypes.map(t => t.type));
        orderedTypes = queueTypes.map(t => t.type);
        tabMeta = data.tabs || [];
        uiSlotsManifest = Array.isArray(data.uiSlots) ? data.uiSlots : [];
        bootstrapped = true;
        await Promise.all(queueTypes
            .filter(t => t.moduleUrl)
            .map(t => loadModule(t.moduleUrl)));
        await Promise.all(uiSlotsManifest
            .filter(slot => slot && slot.moduleUrl)
            .map(slot => loadModule(slot.moduleUrl)));
        queueTypes
            .filter(t => t && t.type && t.moduleUrl && !behaviors.has(t.type))
            .forEach(t => console.warn('[queueTypes] 作品类型行为模块未注册：', t.type, t.moduleUrl));
        // await：renderSlots 主路径走 Vue（异步挂载），需在此 await 完成后下载页 init 才读取 kind 单选 /
        // 设置卡 / 专属筛选等控件（init `await bootstrap()`）——保证控件就位、无「尚未注入」竞态。
        await renderSlots();
    }

    // 把各已启用作品类型 descriptor.slots 贡献的片段按 target 聚合（贡献 order：orderedTypes × slot 键序）。
    // 返回 Map<target, html[]>（同一 target 可有多个类型贡献，按序叠放）。
    function collectSlotFragments() {
        const byTarget = new Map();
        orderedTypes.forEach(type => {
            if (!enabledTypes.has(type)) return;
            const descriptor = behaviors.get(type);
            const slots = (descriptor && descriptor.slots) || {};
            Object.keys(slots).forEach(name => {
                const raw = slots[name];
                const html = typeof raw === 'function' ? raw() : raw;
                if (html == null) return;
                if (!byTarget.has(name)) byTarget.set(name, []);
                byTarget.get(name).push(html);
            });
        });
        return byTarget;
    }

    // 据 target 定位其全部 <template data-qt-slot> 锚点：**固定字面**选择器 + getAttribute 精确比较——
    // target **绝不**拼进选择器字符串（任意 CSS 元字符都不会触发 SyntaxError；找不到即空集）。
    function templatesForTarget(target) {
        const out = [];
        document.querySelectorAll('template[data-qt-slot]').forEach(t => {
            if (t.getAttribute('data-qt-slot') === target) out.push(t);
        });
        return out;
    }

    // Vue 主路径：把某 target 的片段交由共享 helper 用 Vue 渲染进其稳定宿主（[data-vue-slot]，在模板原位备好）。
    // 返回是否成功（Vue 不可用 / 锚点缺失 / 挂载失败 → false，调用方据此命令式回退）。PixivVue.mount 内部已把
    // 挂载抛错收敛为 null、且失败前还原宿主既有内容，绝不向此处抛异常。
    function mountSlotViaVue(target, html) {
        if (!window.PixivVue || typeof window.PixivVue.mount !== 'function') {
            return Promise.resolve(false);
        }
        // PixivVue.mount 内部已收敛挂载抛错为 null；额外 .catch 兜底任何意外 rejection，绝不让 renderSlots
        // 的 await 在下载页 init 关键路径上抛出（失败一律视为「未走 Vue」→ 命令式回退）。
        return window.PixivVue.mount(target, { template: html })
            .then(handle => !!(handle && handle.app))
            .catch(() => false);
    }

    // 命令式回退：把片段以 beforebegin 插到 target 的每个模板锚点前（真实兄弟节点、按 order 叠放）。
    // **仅在 Vue 不可用 / 挂载失败时调用**（见 renderSlots）——正常路径走 Vue、不命令式注入。
    function injectSlotImperative(target, fragments) {
        const html = fragments.join('');
        templatesForTarget(target).forEach(marker => marker.insertAdjacentHTML('beforebegin', html));
    }

    // 据已启用作品类型，把各类型 descriptor.slots 贡献的 DOM 片段渲染进宿主锚点。
    //
    // **主路径 = Vue 渲染**：每个 data-qt-slot 槽位的片段交由共享 helper 经 Vue（createApp().mount 到该槽位在
    // 模板原位备好的 [data-vue-slot] 宿主）渲染；宿主 display:contents（空时 display:none，见 pixiv-batch.css）
    // 使 Vue 内容作为父容器的真实子节点参与布局（保持 .quick-actions / .search-extra-grid 的 grid、.kind-switcher
    // 的 inline-flex 与相邻 / 分隔线 CSS）。**命令式 descriptor.slots 注入只是 Vue 不可用 / 运行时加载失败 / 挂载
    // 失败时的回退**（insertAdjacentHTML 为锚点兄弟节点），不再是常态主路径。
    //
    // **init 时序安全**：本函数为 async，且由 bootstrap `await` 之、bootstrap 又被下载页 init `await`（见
    // batch-init.js）——故无论走 Vue 还是回退，kind 单选 / 设置卡 / 专属筛选等控件都在 init 读取它们**之前**已就位
    //（Vue 主路径下宿主 init 等 Vue 槽位渲染完成，无「控件尚未注入」竞态）。
    //
    // 本流程一次性（锚点被消费 / 移除）。某类型禁用 → 其无片段 → 既不 Vue 挂载也不命令式注入 → 对应入口缺席。
    async function renderSlots() {
        const byTarget = collectSlotFragments();
        // 移除模板**之前**，为每个 data-qt-slot 槽位在模板原位备好稳定 Vue 挂载宿主（幂等；helper 缺失即跳过）。
        if (window.PixivVue && typeof window.PixivVue.prepareSlotHosts === 'function') {
            window.PixivVue.prepareSlotHosts(document);
        }
        // 逐槽位走 Vue 主路径；仅在 Vue 不可用 / 挂载失败时命令式回退（保证「正常路径走 Vue」）。
        for (const [target, fragments] of byTarget) {
            const mounted = await mountSlotViaVue(target, fragments.join(''));
            if (!mounted) injectSlotImperative(target, fragments);
        }
        // 片段带 data-i18n：重跑页面级 i18n 绑定（与语言切换同一幂等流程）后再清理锚点。
        if (typeof pageI18n !== 'undefined' && pageI18n) pageI18n.apply(document.body);
        // 锚点一律移除（一次性消费）。固定字面选择器，不拼 target。
        document.querySelectorAll('template[data-qt-slot]').forEach(t => t.remove());
        try {
            window.dispatchEvent(new CustomEvent('pixivbatch:slotsrendered', { detail: { targets: Array.from(byTarget.keys()) } }));
        } catch (e) {
            // 旧环境没有 CustomEvent 构造器时忽略；槽位本身已渲染完成。
        }
    }

    return {
        register, get, has, isEnabled, bootstrap, uiSlots, downloadTypes,
        isTypeAvailable, resolveType, normalizeSelectedType, descriptor,
        acquisition, acquisitionList, filtersFor, settingsFor, quickActionsFor, contributionsOf,
        i18nNamespaces
    };
})();
