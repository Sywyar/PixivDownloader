'use strict';
/*
 * 插件市场页核心模块（最先加载）：命名空间 PixivPluginMarket、共享状态、i18n 解析助手、HTML 转义、受控展示 token
 * 映射委托、分类 / 排序 / 安装状态元数据、以及展示格式化（下载量 / 体积 / 时间 / 评分星级）。
 * 纯定义、无任何顶层副作用（启动逻辑收拢在 plugin-market-init.js）。
 *
 * 安全约束：后端给出的图标 / 颜色都是受控 token（已在 DTO 边界净化为 [a-z][a-z0-9-]{0,39}，绝非 URL / SVG / HTML /
 * CSS）；本模块再经共享 PixivPluginPresentationTokens 映射为固定的 FontAwesome class / CSS class 后缀，白名单外回退默认——
 * 原始 token 绝不被当作任意类名 / 样式直接渲染，杜绝注入面。所有文本一律经 escapeHtml 后才进 innerHTML。
 */
(function (global) {
    var PMK = global.PixivPluginMarket = global.PixivPluginMarket || {};

    // 共享状态：i18n 客户端容器（init 创建 / 切语言时替换）、当前渲染器句柄（Vue 或命令式回退，供刷新 / 重渲染统一调度）。
    PMK.state = {
        i18n: { client: null },
        activeView: null   // { reload: fn, rerender: fn } —— 由实际挂载的渲染器登记
    };

    function interpolate(template, vars) {
        if (!vars) return String(template);
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)}/g, function (match, name) {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match;
        });
    }

    // 页面自有文案：在 plugin-market namespace 内解析（缺失回退到提供的默认文案）。
    PMK.t = function (key, fallback, vars) {
        var client = PMK.state.i18n.client;
        if (client) {
            return client.t('plugin-market:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    };

    PMK.currentLang = function () {
        var client = PMK.state.i18n.client;
        return client && client.lang ? String(client.lang) : 'zh-CN';
    };

    PMK.escapeHtml = function (str) {
        return String(str == null ? '' : str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    };

    // 市场元数据的本地化文本（{locale: text} 映射）解析：当前语言 → 语言主段 → zh → en → 任一 → 兜底。
    // 用于未安装插件浏览时的名称 / 简介（其 i18n key 的 bundle 未加载，故须用清单字面文本兜底）。
    PMK.localeText = function (map, fallback) {
        if (!map || typeof map !== 'object') return fallback || '';
        var lang = PMK.currentLang();
        if (map[lang]) return map[lang];
        var base = lang.split('-')[0];
        if (map[base]) return map[base];
        if (map.zh) return map.zh;
        if (map.en) return map.en;
        var keys = Object.keys(map);
        return keys.length ? map[keys[0]] : (fallback || '');
    };

    PMK.iconClass = function (token) {
        return global.PixivPluginPresentationTokens.iconClass(token);
    };

    PMK.colorClass = function (token) {
        return global.PixivPluginPresentationTokens.colorClass('pmk-accent--', token);
    };

    // —— 分类词表（与后端 PluginCatalogCategory 对齐；图标为本地白名单内的受控 token）——
    PMK.CATEGORY_ORDER = ['all', 'translate', 'download-type', 'download', 'convert', 'notify', 'backup', 'security', 'ui', 'utility', 'dependency'];
    PMK.CATEGORY_ICON = {
        all: 'grip', translate: 'language', 'download-type': 'plug', download: 'bolt', convert: 'rotate',
        notify: 'bell', backup: 'cloud', security: 'shield-halved', ui: 'palette',
        utility: 'screwdriver-wrench', dependency: 'layer-group'
    };
    PMK.categoryLabel = function (id) {
        return PMK.t('category.' + id, id);
    };
    PMK.categoryDescription = function (id) {
        return PMK.t('category.' + id + '.description', '');
    };

    // —— 排序选项（设计要求的推荐 / 最近更新 / 下载量 / 评分，并补名称；缺字段时稳定降级）——
    PMK.SORT_OPTIONS = ['recommended', 'updated', 'downloads', 'rating', 'name'];

    // —— 安装状态机机器码 → 控件渲染元数据（与后端 MarketInstallStatus 对齐；installing 是前端本地态）——
    PMK.INSTALL_META = {
        NOT_INSTALLED:   { labelKey: 'install.action.install', icon: 'cloud-arrow-down', variant: 'primary' },
        INSTALLED:       { labelKey: 'install.state.installed', icon: 'circle-check',      variant: 'success-outline', disabled: true },
        UPDATE_AVAILABLE:{ labelKey: 'install.action.update',  icon: 'arrow-up',          variant: 'amber' },
        INCOMPATIBLE:    { labelKey: 'install.state.incompatible', icon: 'ban',           variant: 'gray', disabled: true },
        SIGNATURE_REQUIRED: { labelKey: 'install.state.signature-required', icon: 'shield-halved', variant: 'gray', disabled: true },
        UNKNOWN_KEY:     { labelKey: 'install.state.unknown-key', icon: 'shield-halved', variant: 'gray', disabled: true },
        REVOKED_KEY:     { labelKey: 'install.state.revoked-key', icon: 'shield-halved', variant: 'gray', disabled: true },
        INVALID_SIGNATURE:{ labelKey: 'install.state.invalid-signature', icon: 'shield-halved', variant: 'gray', disabled: true },
        HASH_MISMATCH:   { labelKey: 'install.state.hash-mismatch', icon: 'shield-halved', variant: 'gray', disabled: true },
        // 无任何可安装版本制品的条目（后端 UNAVAILABLE）：稳定降级为不可点击的不可安装态，绝不渲染可点击但无响应的安装按钮。
        UNAVAILABLE:     { labelKey: 'install.state.unavailable', icon: 'ban',    variant: 'gray', disabled: true },
        // 前端本地请求态（安装 POST 在途）：不来自后端，安装结果仍以后端响应为准。
        INSTALLING:      { labelKey: 'install.state.installing', icon: 'spinner',         variant: 'primary', disabled: true },
        PENDING_RESTART: { labelKey: 'install.state.pending-restart', icon: 'circle-check', variant: 'success-outline', disabled: true },
        ACTIVATED:       { labelKey: 'install.state.activated', icon: 'circle-check', variant: 'success-outline', disabled: true }
    };
    PMK.installMeta = function (status) {
        var normalized = PMK.INSTALL_META[status] ? status : 'NOT_INSTALLED';
        var meta = PMK.INSTALL_META[normalized];
        return {
            status: normalized,
            labelKey: meta.labelKey,
            icon: meta.icon,
            variant: meta.variant,
            disabled: meta.disabled
        };
    };

    // —— 展示格式化 ——
    PMK.formatDownloads = function (n) {
        if (n == null || n === '') return null;
        var v = Number(n);
        if (isNaN(v)) return null;
        if (v >= 1e6) return (v / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
        if (v >= 1e3) return (v / 1e3).toFixed(1).replace(/\.0$/, '') + 'k';
        return String(Math.round(v));
    };

    PMK.formatSize = function (bytes) {
        var v = Number(bytes);
        if (!v || v <= 0 || isNaN(v)) return null;
        if (v >= 1048576) return (v / 1048576).toFixed(1).replace(/\.0$/, '') + ' MB';
        if (v >= 1024) return Math.round(v / 1024) + ' KB';
        return Math.round(v) + ' B';
    };

    // 相对时间（来自受信 catalog 的 ISO-8601 串；无法解析时原样回显，已是受控文本）。
    PMK.formatDate = function (iso) {
        if (!iso) return '';
        var d = new Date(iso);
        if (isNaN(d.getTime())) return String(iso);
        var now = new Date();
        var utcNow = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
        var utcDate = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
        var days = Math.floor((utcNow - utcDate) / 86400000);
        if (days <= 0) return PMK.t('date.today', '今天');
        if (days === 1) return PMK.t('date.yesterday', '昨天');
        if (days < 30) return PMK.t('date.days-ago', '{n} 天前', { n: days });
        if (days < 365) return PMK.t('date.months-ago', '{n} 个月前', { n: Math.floor(days / 30) });
        return PMK.t('date.years-ago', '{n} 年前', { n: Math.floor(days / 365) });
    };

    // 评分 → 5 星拆分（满 / 半 / 空）。
    PMK.stars = function (rating) {
        var r = Math.max(0, Math.min(5, Number(rating) || 0));
        var full = Math.floor(r);
        var half = (r - full) >= 0.5 ? 1 : 0;
        var empty = 5 - full - half;
        return { full: full, half: half, empty: empty };
    };
})(window);
