(function (global) {
    'use strict';
    // ============================================================
    //  PixivVue —— 插件 UI 槽位的 Vue 挂载约定（共享 helper，加性、加载即无副作用）。
    //
    //  Vue 运行时为**单一来源**：核心静态目录提供的全局构建版（/vendor/vue/vue.global.prod.js）。
    //  各页面 / 插件**按需**经 ensure() 懒加载它，不全站加载；外置插件**禁止**自带 Vue（否则在
    //  classloader-aware serving 下会出现 N 份重复与版本漂移）。
    //
    //  与 Web UiSlot 扩展点（/api/download/extensions 的 uiSlots 清单 + WebUiSlotRegistry）配套：
    //  宿主页声明稳定的槽位锚点（下载页以 <template data-qt-slot="<target>"> 实现），插件经
    //  uiSlots() 清单声明 { slotId, target, moduleUrl, order, metadata }；本 helper 提供
    //  「按 target 定位锚点 → （宿主已据 moduleUrl 加载行为模块）→ 用 Vue.createApp().mount() 挂载」
    //  的前向渲染路径，取代命令式 insertAdjacentHTML + 手动 renderQueue 扇出。
    //
    //  下载页 8 个 data-qt-slot 槽位的 descriptor.slots 片段**主路径已走 Vue**（batch-queue-types.js 的
    //  renderSlots 经本 helper 把片段渲染进各槽位宿主）；命令式 insertAdjacentHTML 注入只在 Vue 不可用 /
    //  运行时加载失败 / 挂载失败时作为**回退**。mountInto 在挂载抛错时还原宿主既有内容，保证失败回退不丢内容。
    // ============================================================

    // 单一来源：核心静态目录提供的 Vue 全局构建版。
    var VUE_RUNTIME_URL = '/vendor/vue/vue.global.prod.js';

    var runtimePromise = null;     // Vue 运行时加载的单例 Promise（去重；失败后置空允许重试）
    var moduleLoads = {};          // moduleUrl -> Promise（槽位行为模块脚本去重）

    // Vue 全局是否已就位。
    function available() {
        return typeof global.Vue !== 'undefined' && !!global.Vue;
    }

    // 通用同源脚本加载（顺序求值、失败 reject）。供 ensure() 与 loadModule() 复用。
    function loadScript(url) {
        return new Promise(function (resolve, reject) {
            var s = document.createElement('script');
            s.src = url;
            s.async = false;
            s.onload = function () { resolve(); };
            s.onerror = function () { reject(new Error('script load failed: ' + url)); };
            (document.head || document.documentElement).appendChild(s);
        });
    }

    // 确保核心 Vue 运行时已加载，返回 resolve 为 window.Vue 的 Promise。页面 / 插件按需调用：
    // 仅真正要挂载 Vue 组件的页面才加载这份运行时，不全站加载。重复调用复用同一 Promise。
    function ensure() {
        if (available()) return Promise.resolve(global.Vue);
        if (!runtimePromise) {
            runtimePromise = loadScript(VUE_RUNTIME_URL).then(function () {
                if (!available()) throw new Error('Vue runtime loaded but window.Vue missing');
                return global.Vue;
            }).catch(function (e) {
                runtimePromise = null; // 允许后续重试
                throw e;
            });
        }
        return runtimePromise;
    }

    // 加载某插件槽位行为模块（moduleUrl，来自 uiSlots() 清单），同源、去重。下载页 bootstrap
    // 已统一加载各类型 moduleUrl；此处供脱离下载页宿主的页面 / 测试夹具按需自行加载同一模块。
    function loadModule(url) {
        if (!url) return Promise.resolve();
        if (!moduleLoads[url]) {
            moduleLoads[url] = loadScript(url).catch(function (e) {
                moduleLoads[url] = null;
                throw e;
            });
        }
        return moduleLoads[url];
    }

    // 在 root 范围内按「属性精确值」定位元素：querySelector 只用**固定字面**的属性存在式
    //（如 [data-vue-slot]），target 原始值仅经 getAttribute 精确比较——target **绝不**拼进
    // 选择器字符串，故任意字符（含 "、]、\ 等 CSS 元字符）都不会触发选择器 SyntaxError，
    // 找不到即返回 null。
    function queryByAttrValue(root, presenceSelector, attr, value) {
        var nodes = root.querySelectorAll(presenceSelector);
        for (var i = 0; i < nodes.length; i++) {
            if (nodes[i].getAttribute(attr) === value) return nodes[i];
        }
        return null;
    }

    // 在 <template data-qt-slot> 锚点的**原开槽位置**幂等地确保该 target 的真实挂载宿主 <div data-vue-slot>
    // 已存在并返回它。放置约定：把宿主插在模板**原位**（insertBefore 到模板之前；renderSlots 随后移除模板，
    // 宿主即落在模板原先所在的槽位）——使非空 Vue 组件挂载后出现在该 slot 的真实位置（如 settings-card 落在
    // 「下载设置」卡与「存为计划任务」卡之间，而非被挪到父容器末尾）。空宿主由页面 CSS
    // `[data-vue-slot]:empty { display:none }` 兜底为不参与布局（消 flex gap / grid 单元 / 空盒），挂载后
    // 非空即恢复显示。同父已存在同名宿主则复用（幂等，不产生重复）。
    //
    // 相邻选择器解耦（不靠「把宿主挪到末尾」规避）：下载页 .kind-switcher 的分隔线改用
    // `label:not(:first-child)`（与既有 .quick-kind-switcher 同款），不依赖 `label + label` 的严格相邻——
    // 故宿主即便夹在两个 label 之间（如 kind-option-user 落在「插画」「约稿」之间）也不会让后一个 label
    // 丢失左分隔线。
    function ensureHostForTemplate(tpl, target) {
        var parent = tpl && tpl.parentNode;
        if (!parent) return null;
        var existing = queryByAttrValue(parent, '[data-vue-slot]', 'data-vue-slot', target);
        if (existing) return existing;            // 幂等：复用同父已存在的宿主，不重复创建
        var host = document.createElement('div');
        host.setAttribute('data-vue-slot', target);
        parent.insertBefore(host, tpl);           // 模板原位：宿主取代模板所在槽位（模板随后移除）
        return host;
    }

    // 据槽位 target 定位宿主挂载元素。约定：
    //  · 宿主以 <template data-qt-slot="<target>"> 声明锚点：在锚点的**原开槽位置**插入（或复用）真实挂载
    //    宿主 <div data-vue-slot="<target>"> 并返回它（模板自身不渲染；模板节点保留，若宿主后续 renderSlots
    //    统一清理模板亦不影响已就位的宿主节点）。
    //  · 宿主直接放一个 [data-vue-slot="<target>"] 或 id==="<target>" 的真实元素：直接返回它。
    // 找不到锚点返回 null —— 插件未提供锚点 / 宿主页不含该槽位时优雅缺席（不抛错；任意非空 target
    // 经 getAttribute 精确比较、不进选择器，绝不因 CSS 解析抛 SyntaxError）。
    function anchorFor(target) {
        if (!target) return null;
        var tpl = queryByAttrValue(document, 'template[data-qt-slot]', 'data-qt-slot', target);
        if (tpl) {
            var host = ensureHostForTemplate(tpl, target);
            if (host) return host;
        }
        return queryByAttrValue(document, '[data-vue-slot]', 'data-vue-slot', target) || document.getElementById(target);
    }

    // 幂等地为 root（默认整个 document）下每个 <template data-qt-slot> 槽位准备稳定的 Vue 挂载宿主。
    // 下载页 renderSlots 在**移除模板之前**调用本函数：使页面初始化后每个已开出的 UiSlot target 都拥有
    // 可被 anchorFor / mount / mountUiSlot 命中的 [data-vue-slot] 宿主——即便该槽位当前没有任何命令式
    // descriptor.slots 片段（未来可能仅由 Vue 组件消费）。加性、幂等、无副作用扩散：只创建 / 复用宿主，
    // 不改任何全局状态、不影响命令式片段注入。选择器为固定字面（不拼 target），返回本次涉及的宿主节点数组。
    function prepareSlotHosts(root) {
        root = root || document;
        var tpls = root.querySelectorAll('template[data-qt-slot]');
        var hosts = [];
        for (var i = 0; i < tpls.length; i++) {
            var target = tpls[i].getAttribute('data-qt-slot');
            if (!target) continue;
            var host = ensureHostForTemplate(tpls[i], target);
            if (host) hosts.push(host);
        }
        return hosts;
    }

    // 快照 el 当前的全部子节点（即调用方已渲染好的**命令式 fallback**），供挂载失败时还原。
    // 真实 DOM 用 childNodes（含文本节点），最小测试 DOM 退化用 children；live 集合先复制引用再操作。
    function snapshotChildNodes(el) {
        var saved = [];
        var list = el.childNodes || el.children;
        if (list) { for (var i = 0; i < list.length; i++) saved.push(list[i]); }
        return saved;
    }
    // 清掉 el 现存（可能被 Vue 半渲染留下的）子节点，再把快照的 fallback 子节点逐一接回。
    function restoreChildNodes(el, saved) {
        var list = el.childNodes || el.children;
        if (list) {
            var leftover = [];
            for (var i = 0; i < list.length; i++) leftover.push(list[i]);
            for (var j = 0; j < leftover.length; j++) {
                if (leftover[j] && typeof leftover[j].remove === 'function') leftover[j].remove();
            }
        }
        for (var k = 0; k < saved.length; k++) el.appendChild(saved[k]);
    }

    // 把 Vue 组件挂载到给定元素。appOptions 既可是组件定义（{ template, setup, ... }），
    // 也可是已建好的 app（含 mount 方法）。挂载后对挂载子树重跑页面级 i18n（data-i18n 绑定），
    // 与 renderSlots 行为一致（注入片段后重跑 pageI18n.apply）。
    //
    // **失败前不得吞掉已有命令式 fallback**：Vue 的 app.mount（runtime-dom）在首次渲染前会**先清空容器**
    //（container.innerHTML=''），若随后 setup / 模板编译抛错，容器已被清空、调用方此前渲染的 fallback 就丢了。
    // 故先快照容器子节点、挂载抛错时清掉残留再还原快照，并把异常上抛由 mount / mountOn 收敛为 null——
    // 这样「升级 Vue 失败」绝不会让一个本已有命令式内容的槽位 / slot 变空白（优雅降级、回退命令式）。
    function mountInto(Vue, el, appOptions) {
        var app = (appOptions && typeof appOptions.mount === 'function') ? appOptions : Vue.createApp(appOptions);
        var fallback = snapshotChildNodes(el);   // 命令式 fallback 快照（挂载抛错时还原）
        var vm;
        try {
            vm = app.mount(el);
        } catch (e) {
            try { if (typeof app.unmount === 'function') app.unmount(); } catch (_) { /* 卸载失败忽略 */ }
            restoreChildNodes(el, fallback);     // Vue 清空容器后才抛错 → 还原命令式 fallback
            throw e;                              // 交由 mount / mountOn catch → 返回 null（调用方保留 fallback）
        }
        if (typeof global.pageI18n !== 'undefined' && global.pageI18n) {
            try { global.pageI18n.apply(el); } catch (e) { /* i18n 可选，缺失不阻断挂载 */ }
        }
        return { app: app, vm: vm, el: el };
    }

    // 据 target 锚点挂载一个 Vue 组件。返回 resolve 为 { app, vm, el } 或 null 的 Promise
    //（Vue 不可用 / 锚点缺失 / 挂载抛错都收敛为 null —— **绝不向宿主 init 抛异常**，
    // 与「插件未加载即槽位入口自然缺席」的优雅降级一致）。
    function mount(target, appOptions) {
        return ensure().then(function (Vue) {
            var el = anchorFor(target);
            if (!el) {
                console.warn('[PixivVue] 未找到槽位锚点：', target);
                return null;
            }
            return mountInto(Vue, el, appOptions);
        }).catch(function (e) {
            console.warn('[PixivVue] 挂载失败：', target, e);
            return null;
        });
    }

    // 把 Vue 组件挂到一个**已由调用方解析好的真实元素**（如 [data-nav-slot] / [data-section-slot] 容器——
    // 这类「非 <template data-qt-slot>」的槽位由其渲染模块用**固定字面**存在式选择器 + getAttribute 自行定位，
    // 不经 anchorFor）。与 mount(target) 共享同一 ensure + mountInto + 失败收敛语义：Vue 不可用 / 元素缺失 /
    // 挂载抛错一律收敛为返回 null（解析为 { app, vm, el } 或 null 的 Promise），**绝不向宿主抛异常**。
    // 元素由调用方负责解析（target 绝不在本 helper 内拼进选择器字符串）。
    function mountOn(el, appOptions) {
        if (!el) return Promise.resolve(null);
        return ensure().then(function (Vue) {
            return mountInto(Vue, el, appOptions);
        }).catch(function (e) {
            console.warn('[PixivVue] 挂载失败（元素锚点）：', e);
            return null;
        });
    }

    // 据 uiSlots() 清单的一条 { slotId, target, moduleUrl, order, metadata } 挂载组件。
    // appOptions 为该槽位要渲染的 Vue 组件定义。moduleUrl 脚本的加载由宿主（下载页
    // batch-queue-types.js）在 bootstrap 时统一完成；此处只据 slot.target 挂载组件。
    function mountUiSlot(slot, appOptions) {
        if (!slot || !slot.target) return Promise.resolve(null);
        return mount(slot.target, appOptions);
    }

    global.PixivVue = {
        runtimeUrl: VUE_RUNTIME_URL,
        available: available,
        ensure: ensure,
        loadModule: loadModule,
        anchorFor: anchorFor,
        prepareSlotHosts: prepareSlotHosts,
        mount: mount,
        mountOn: mountOn,
        mountUiSlot: mountUiSlot
    };
})(window);
