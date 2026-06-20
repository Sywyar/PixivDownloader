(function (global) {
    'use strict';
    // ============================================================
    //  通用语义下钻渲染模块（PixivDrilldowns）
    //
    //  目标：让宿主页面只认得「语义 placement」（如 stats.top-authors / stats.top-tags），在渲染某条记录时以一组
    //  运行期变量（如作者 id / 标签名）请求一个可点击的下钻 href，由后端活动插件经 /api/drilldowns 决定目标页面与
    //  查询参数。宿主页面不需要知道是哪个插件、目标页面路径或查询参数名——这些只存在于贡献方插件与运行期响应里。
    //  禁用贡献方插件后该 placement 没有贡献，href() 返回 null，宿主自然回到纯展示。
    //
    //  数据来源：GET /api/drilldowns —— 后端按当前请求身份过滤 visibleTo、按 priority 排序，只返回当前用户可见的
    //  下钻贡献（每条含 placements + hrefTemplate）。前端隐藏不是安全边界：下钻链接指向的目标 URL 仍由后端 AuthFilter
    //  鉴权；某下钻对当前身份隐藏，不代表其目标 URL 在后端开放，反之亦然。
    //
    //  用法：
    //    await PixivDrilldowns.ready();                 // 等待首次拉取完成（成功或失败都 resolve）
    //    var href = PixivDrilldowns.href(placement, vars); // 同步解析；无贡献 / 拉取失败 / 模板不可用时返回 null
    //
    //  模板替换：只把 hrefTemplate 里的 {变量名} 占位替换为 encodeURIComponent 后的变量值（变量缺失视为空串前先
    //  保留占位、最终若仍有未填充占位则判模板不可用返回 null）。不提供任何特定页面的 fallback。
    // ============================================================

    var ENDPOINT = '/api/drilldowns';
    var PLACEHOLDER = /\{([A-Za-z0-9_]+)\}/g;

    var byPlacement = {};   // placement -> [drilldown, ...]（后端已按来源层级 → priority → id 排序）
    var loaded = false;

    var resolveReady;
    var readyResolved = false;
    var readyPromise = new global.Promise(function (resolve) { resolveReady = resolve; });

    function markReady() {
        if (readyResolved) return;
        readyResolved = true;
        resolveReady();
    }

    function indexByPlacement(list) {
        var map = {};
        (Array.isArray(list) ? list : []).forEach(function (d) {
            if (!d || !d.hrefTemplate || !Array.isArray(d.placements)) return;
            d.placements.forEach(function (placement) {
                if (!placement) return;
                (map[placement] = map[placement] || []).push(d);
            });
        });
        return map;
    }

    async function load() {
        try {
            var res = await global.fetch(ENDPOINT, {
                credentials: 'same-origin',
                headers: { 'Accept': 'application/json' }
            });
            if (!res.ok) throw new Error('drilldowns http ' + res.status);
            byPlacement = indexByPlacement(await res.json());
            loaded = true;
        } catch (e) {
            // 失败降级：清空贡献、href() 一律返回 null（宿主回退纯展示），不把错误抛给调用方。
            byPlacement = {};
            loaded = false;
            console.warn('[PixivDrilldowns] 拉取下钻贡献失败，下钻链接降级为纯展示：', e);
        } finally {
            markReady();
        }
    }

    // 把模板里的 {变量名} 替换为 encodeURIComponent 后的变量值；缺失的变量保留原占位，
    // 替换后若仍残留任何占位则视模板不可用、返回 null（绝不生成半截 URL）。
    function fillTemplate(template, variables) {
        var vars = variables || {};
        var out = String(template).replace(PLACEHOLDER, function (match, key) {
            if (!Object.prototype.hasOwnProperty.call(vars, key)) return match;
            var value = vars[key];
            return encodeURIComponent(value == null ? '' : String(value));
        });
        PLACEHOLDER.lastIndex = 0;
        if (PLACEHOLDER.test(out)) {
            PLACEHOLDER.lastIndex = 0;
            return null;
        }
        return out;
    }

    // 解析某语义 placement 下、以给定变量替换后的下钻 href；无贡献 / 未加载 / 模板不可用时返回 null。
    function href(placement, variables) {
        if (!loaded) return null;
        var list = byPlacement[placement];
        if (!list || !list.length) return null;
        var template = list[0].hrefTemplate;   // 取后端排序后的胜者（来源层级 → priority → id）
        if (!template) return null;
        return fillTemplate(template, variables);
    }

    global.PixivDrilldowns = {
        ready: function () { return readyPromise; },
        href: href
    };

    load();
})(window);
