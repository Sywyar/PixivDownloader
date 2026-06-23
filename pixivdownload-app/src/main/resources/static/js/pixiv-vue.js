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
    //  这是**加性**约定：现有 descriptor.slots 片段注入路径（batch-queue-types.js 的 renderSlots）
    //  保持可用、行为不变；某槽位是否改用 Vue 挂载由该槽位的行为模块自行决定（渐进迁移）。
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

    // 据槽位 target 定位宿主挂载元素。约定（与下载页 renderSlots 的相邻位置一致）：
    //  · 宿主以 <template data-qt-slot="<target>"> 声明锚点：在锚点**前**插入一个真实挂载宿主 <div>
    //    兄弟节点并返回它（模板自身不渲染，与 descriptor.slots 片段注入同一相邻位置；模板节点保留，
    //    若宿主后续 renderSlots 统一清理模板亦不影响已挂载节点）。
    //  · 宿主直接放一个 [data-vue-slot="<target>"] 或 id==="<target>" 的真实元素：直接返回它。
    // 找不到锚点返回 null —— 插件未提供锚点 / 宿主页不含该槽位时优雅缺席（不抛错；任意非空 target
    // 经 getAttribute 精确比较、不进选择器，绝不因 CSS 解析抛 SyntaxError）。
    function anchorFor(target) {
        if (!target) return null;
        var tpl = queryByAttrValue(document, 'template[data-qt-slot]', 'data-qt-slot', target);
        if (tpl && tpl.parentNode) {
            var existing = queryByAttrValue(tpl.parentNode, '[data-vue-slot]', 'data-vue-slot', target);
            if (existing) return existing; // 幂等：重复挂载复用同一宿主节点
            var host = document.createElement('div');
            host.setAttribute('data-vue-slot', target);
            tpl.parentNode.insertBefore(host, tpl);
            return host;
        }
        return queryByAttrValue(document, '[data-vue-slot]', 'data-vue-slot', target) || document.getElementById(target);
    }

    // 把 Vue 组件挂载到给定元素。appOptions 既可是组件定义（{ template, setup, ... }），
    // 也可是已建好的 app（含 mount 方法）。挂载后对挂载子树重跑页面级 i18n（data-i18n 绑定），
    // 与 renderSlots 行为一致（注入片段后重跑 pageI18n.apply）。
    function mountInto(Vue, el, appOptions) {
        var app = (appOptions && typeof appOptions.mount === 'function') ? appOptions : Vue.createApp(appOptions);
        var vm = app.mount(el);
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
        mount: mount,
        mountUiSlot: mountUiSlot
    };
})(window);
