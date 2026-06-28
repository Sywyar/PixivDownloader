'use strict';
/*
 * 插件市场页核心模块（最先加载）：命名空间 PixivPluginMarket、共享状态、i18n 解析助手、HTML 转义、受控展示 token
 * 白名单（图标 / 颜色）、分类 / 排序 / 安装状态元数据、以及展示格式化（下载量 / 体积 / 时间 / 评分星级）。
 * 纯定义、无任何顶层副作用（启动逻辑收拢在 plugin-market-init.js）。
 *
 * 安全约束：后端给出的图标 / 颜色都是受控 token（已在 DTO 边界净化为 [a-z][a-z0-9-]{0,39}，绝非 URL / SVG / HTML /
 * CSS）；本模块再经<b>本地白名单</b>映射为固定的 FontAwesome class / CSS class 后缀，白名单外回退默认——原始 token
 * 绝不被当作任意类名 / 样式直接渲染，杜绝注入面。所有文本一律经 escapeHtml 后才进 innerHTML。
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
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, function (match, name) {
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

    // —— 受控图标 token → FontAwesome class 的本地白名单 ——
    // 后端只给受控 token（[a-z][a-z0-9-]{0,39}）；这里固定映射为 fa-solid fa-<token>，白名单外的未知 token 回退到默认
    // puzzle-piece（稳定回退），原始 token 绝不被当作任意类名直接拼接。
    var ICON_WHITELIST = {
        'store': 1, 'puzzle-piece': 1, 'language': 1, 'bolt': 1, 'rotate': 1, 'bell': 1, 'cloud': 1,
        'shield-halved': 1, 'palette': 1, 'screwdriver-wrench': 1, 'grip': 1, 'hashtag': 1, 'paper-plane': 1,
        'film': 1, 'book': 1, 'clone': 1, 'file-signature': 1, 'heart': 1, 'download': 1, 'upload': 1, 'users': 1,
        'globe': 1, 'gear': 1, 'image': 1, 'images': 1, 'chart-line': 1, 'layer-group': 1, 'cloud-arrow-down': 1,
        'cloud-arrow-up': 1, 'cube': 1, 'wand-magic-sparkles': 1, 'robot': 1, 'music': 1, 'microphone': 1, 'lock': 1,
        'key': 1, 'tag': 1, 'tags': 1, 'folder': 1, 'box': 1, 'plug': 1, 'code': 1, 'scroll': 1, 'filter': 1,
        'wrench': 1, 'gauge': 1, 'magnifying-glass': 1, 'star': 1, 'fire': 1, 'bookmark': 1, 'comments': 1,
        'envelope': 1, 'database': 1, 'wifi': 1, 'compass': 1, 'feather': 1, 'pen': 1, 'brush': 1, 'eye': 1
    };
    var DEFAULT_ICON = 'puzzle-piece';

    PMK.iconClass = function (token) {
        var name = Object.prototype.hasOwnProperty.call(ICON_WHITELIST, token) ? token : DEFAULT_ICON;
        return 'fa-solid fa-' + name;
    };

    // —— 受控颜色 token → 稳定 CSS class 后缀的本地白名单（颜色只用于卡片可扫描性、非任意样式）——
    var COLOR_TOKENS = {
        neutral: 1, gray: 1, pixiv: 1, blue: 1, teal: 1, amber: 1, purple: 1, orange: 1, red: 1, green: 1
    };
    var DEFAULT_COLOR = 'neutral';

    PMK.colorClass = function (token) {
        return 'pmk-accent--' + (Object.prototype.hasOwnProperty.call(COLOR_TOKENS, token) ? token : DEFAULT_COLOR);
    };

    // —— 分类词表（与后端 PluginCatalogCategory 对齐；图标为本地白名单内的受控 token）——
    PMK.CATEGORY_ORDER = ['all', 'translate', 'download', 'convert', 'notify', 'backup', 'security', 'ui', 'utility'];
    PMK.CATEGORY_ICON = {
        all: 'grip', translate: 'language', download: 'bolt', convert: 'rotate', notify: 'bell',
        backup: 'cloud', security: 'shield-halved', ui: 'palette', utility: 'screwdriver-wrench'
    };
    PMK.categoryLabel = function (id) {
        return PMK.t('category.' + id, id);
    };

    // —— 排序选项（设计要求的推荐 / 最近更新 / 下载量 / 评分，并补名称；缺字段时稳定降级）——
    PMK.SORT_OPTIONS = ['recommended', 'updated', 'downloads', 'rating', 'name'];

    // —— 安装状态机机器码 → 控件渲染元数据（与后端 MarketInstallStatus 对齐；installing 是前端本地态）——
    PMK.INSTALL_META = {
        NOT_INSTALLED:   { labelKey: 'install.action.install', fallback: '安装',   icon: 'cloud-arrow-down', variant: 'primary' },
        INSTALLED:       { labelKey: 'install.state.installed', fallback: '已安装', icon: 'circle-check',      variant: 'success-outline', disabled: true },
        UPDATE_AVAILABLE:{ labelKey: 'install.action.update',  fallback: '更新',   icon: 'arrow-up',          variant: 'amber' },
        INCOMPATIBLE:    { labelKey: 'install.state.incompatible', fallback: '不兼容', icon: 'ban',           variant: 'gray', disabled: true },
        // 无任何可安装版本制品的条目（后端 UNAVAILABLE）：稳定降级为不可点击的不可安装态，绝不渲染可点击但无响应的安装按钮。
        UNAVAILABLE:     { labelKey: 'install.state.unavailable', fallback: '暂无可安装版本', icon: 'ban',    variant: 'gray', disabled: true },
        // 前端本地请求态（安装 POST 在途）：不来自后端，安装结果仍以后端响应为准。
        INSTALLING:      { labelKey: 'install.state.installing', fallback: '安装中…', icon: 'spinner',         variant: 'primary', disabled: true },
        PENDING_RESTART: { labelKey: 'install.state.pending-restart', fallback: '待重启', icon: 'circle-check', variant: 'success-outline', disabled: true },
        ACTIVATED:       { labelKey: 'install.state.activated', fallback: '已激活', icon: 'circle-check', variant: 'success-outline', disabled: true }
    };
    PMK.installMeta = function (status) {
        return PMK.INSTALL_META[status] || PMK.INSTALL_META.NOT_INSTALLED;
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
        var t = Date.parse(iso);
        if (isNaN(t)) return String(iso);
        var days = Math.floor((Date.now() - t) / 86400000);
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
