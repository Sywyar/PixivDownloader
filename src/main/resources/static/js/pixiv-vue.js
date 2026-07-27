(function (global) {
    'use strict';
    // ============================================================
    //  PixivVue —— 高频刷新区域的 Vue 挂载 helper（共享、加性、加载即无副作用）。
    //
    //  用途：把下载队列 / 监控活跃下载 / 任务侧栏等「每个 SSE / 轮询事件就整块 innerHTML
    //  重建」的高频刷新区改为 Vue 响应式——状态对象变 reactive 后，逐条进度事件只触发
    //  「变动那一行」的细粒度更新，不再全量重建 DOM，从而消除高并发下载时的整页卡死。
    //
    //  Vue 运行时为单一来源：核心静态目录提供的全局构建版（/vendor/vue/vue.global.prod.js）。
    //  本 helper 经 ensure() 懒加载它；页面也可在 HTML 里同步引入该运行时使 window.Vue 提早就绪。
    //
    //  失败回退：Vue 不可用 / 运行时加载失败 / 挂载抛错时一律收敛为返回 null——调用方保留
    //  既有的命令式 innerHTML 渲染（graceful degradation），绝不让「升级 Vue 失败」把一个本
    //  已有内容的区域清成空白（mountInto 在挂载抛错时会还原挂载前的命令式子节点）。
    // ============================================================

    // 单一来源：核心静态目录提供的 Vue 全局构建版。
    var VUE_RUNTIME_URL = '/vendor/vue/vue.global.prod.js';

    var runtimePromise = null;     // Vue 运行时加载的单例 Promise（去重；失败后置空允许重试）

    // Vue 全局是否已就位。
    function available() {
        return typeof global.Vue !== 'undefined' && !!global.Vue;
    }

    // 通用同源脚本加载（顺序求值、失败 reject）。供 ensure() 复用。
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

    // 确保核心 Vue 运行时已加载，返回 resolve 为 window.Vue 的 Promise。重复调用复用同一 Promise。
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

    // 快照 el 当前的全部子节点（即调用方已渲染好的命令式 fallback），供挂载失败时还原。
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
    // 也可是已建好的 app（含 mount 方法）。挂载后对挂载子树重跑页面级 i18n（data-i18n 绑定）。
    //
    // **失败前不得吞掉已有命令式 fallback**：Vue 的 app.mount（runtime-dom）在首次渲染前会**先清空容器**
    //（container.innerHTML=''），若随后 setup / 模板编译抛错，容器已被清空、调用方此前渲染的 fallback 就丢了。
    // 故先快照容器子节点、挂载抛错时清掉残留再还原快照，并把异常上抛由 mountOn 收敛为 null——
    // 这样「升级 Vue 失败」绝不会让一个本已有命令式内容的区域变空白（优雅降级、回退命令式）。
    function mountInto(Vue, el, appOptions) {
        var app = (appOptions && typeof appOptions.mount === 'function') ? appOptions : Vue.createApp(appOptions);
        var fallback = snapshotChildNodes(el);   // 命令式 fallback 快照（挂载抛错时还原）
        var vm;
        try {
            vm = app.mount(el);
        } catch (e) {
            try { if (typeof app.unmount === 'function') app.unmount(); } catch (_) { /* 卸载失败忽略 */ }
            restoreChildNodes(el, fallback);     // Vue 清空容器后才抛错 → 还原命令式 fallback
            throw e;                              // 交由 mountOn catch → 返回 null（调用方保留 fallback）
        }
        if (typeof global.pageI18n !== 'undefined' && global.pageI18n) {
            try { global.pageI18n.apply(el); } catch (e) { /* i18n 可选，缺失不阻断挂载 */ }
        }
        return { app: app, vm: vm, el: el };
    }

    // 把 Vue 组件挂到一个**已由调用方解析好的真实元素**（如 #queue-list / #activeDownloadsList 容器）。
    // 返回 resolve 为 { app, vm, el } 或 null 的 Promise：Vue 不可用 / 元素缺失 / 挂载抛错都收敛为 null，
    // **绝不向调用方抛异常**（调用方保留命令式 fallback 继续工作）。
    function mountOn(el, appOptions) {
        if (!el) return Promise.resolve(null);
        return ensure().then(function (Vue) {
            return mountInto(Vue, el, appOptions);
        }).catch(function (e) {
            console.warn('[PixivVue] 挂载失败（元素锚点）：', e);
            return null;
        });
    }

    global.PixivVue = {
        runtimeUrl: VUE_RUNTIME_URL,
        available: available,
        ensure: ensure,
        mountOn: mountOn
    };
})(window);
