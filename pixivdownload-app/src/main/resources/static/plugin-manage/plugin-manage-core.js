'use strict';
/*
 * 插件管理页核心：常量、共享状态、i18n 助手、以及把后端 /api/plugins/status 条目映射为卡片视图模型。
 * 本模块只定义全局命名空间 PixivPluginManage，无任何顶层副作用（启动逻辑收拢在 plugin-manage-init.js）。
 *
 * 数据来源：后端管理 API（admin-only，已接线）。后端响应见 PluginManagementService.PluginManagementReport：
 *   { recoveryMode, plugins: [ { id, displayNamespace, displayNameKey, version, kind, apiRequirement,
 *     dependencies, source, status, runtimePhase, managed, requiredByPolicy, allowDisable,
 *     availableActions, messages } ] }
 * 设计稿里后端暂未提供的字段（更新机制 / 体积 / 下载量 / 每插件图标色 / 作者 / 描述）在此处优雅留空
 * （见各 vm.hasUpdate 等占位字段与 describe()），待后端补齐对应数据后再点亮。
 */
(function (global) {
    var STATUS_URL = '/api/plugins/status';
    var ACTION_URL_PREFIX = '/api/plugins/';

    // 共享视图状态：渲染层只读，init 层写。
    var state = {
        report: null,      // 最近一次 /api/plugins/status 响应
        loading: false,
        error: null,
        activeTab: 'all',  // all | enabled | disabled | external
        search: '',
        busyId: null       // 正在执行运行期动词的插件 id（期间禁用其卡片按钮，动作串行化）
    };

    // i18n 客户端容器（init 创建 / 切语言时替换；渲染层经 t / tns 读取当前客户端）。
    var i18n = { client: null };

    function interpolate(template, vars) {
        if (!vars) return String(template);
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, function (match, name) {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match;
        });
    }

    // 页面自有文案：在 plugins namespace 内解析。
    function t(key, fallback, vars) {
        if (i18n.client) {
            return i18n.client.t('plugins:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    // 纯 key 在指定 namespace 内解析：插件展示名 displayNameKey 须在其 displayNamespace 内解析。
    // namespace 规范化（与 collectNamespaces 同一规则）：null / "" / 纯空白都视为缺省 → 直接回退 fallback，
    // 不把空白 namespace 透传给客户端 tns（避免裸 key 误解析到页面首个 namespace）；非空先 trim 再解析。
    function tns(namespace, key, fallback) {
        var ns = namespace == null ? '' : String(namespace).trim();
        if (i18n.client && ns && key) {
            return i18n.client.tns(ns, key, fallback != null ? fallback : key);
        }
        return fallback != null ? fallback : (key || '');
    }

    function escapeHtml(str) {
        return String(str == null ? '' : str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // 收集后端响应里全部需要的展示 namespace（displayNameKey 在其 displayNamespace 内解析）。
    // 前端据此动态扩展 i18n 客户端的 namespace 集，不硬编码哪个插件用哪个 namespace。
    function collectNamespaces(report) {
        var set = {};
        var list = (report && report.plugins) || [];
        for (var i = 0; i < list.length; i++) {
            var raw = list[i] && list[i].displayNamespace;
            // displayNamespace 规范化（与 tns 同一规则）：null / "" / 纯空白都跳过——不请求空白 namespace bundle；非空先 trim。
            var ns = raw == null ? '' : String(raw).trim();
            if (ns) {
                set[ns] = true;
            }
        }
        return Object.keys(set);
    }

    // 状态 → { i18n key, 色调 }。色调用于状态点 / API 兼容标记的着色。
    var STATUS_META = {
        STARTED:               { key: 'status.started', tone: 'ok' },
        STOPPED:               { key: 'status.stopped', tone: 'idle' },
        DISABLED:              { key: 'status.disabled', tone: 'idle' },
        LOADED:                { key: 'status.loaded', tone: 'info' },
        INSTALLED:             { key: 'status.installed', tone: 'idle' },
        RESOLVED:              { key: 'status.resolved', tone: 'info' },
        FAILED:                { key: 'status.failed', tone: 'bad' },
        INCOMPATIBLE:          { key: 'status.incompatible', tone: 'bad' },
        MISSING_REQUIRED:      { key: 'status.missing-required', tone: 'bad' },
        INCOMPATIBLE_REQUIRED: { key: 'status.incompatible-required', tone: 'bad' }
    };

    function statusMeta(status) {
        return STATUS_META[status] || { key: 'status.unknown', tone: 'idle' };
    }

    // 运行期动词 → { 图标, 按钮变体 }。动词清单与可用集均由后端 availableActions 给出，前端只负责渲染。
    var VERB_META = {
        load:    { icon: 'fa-plug', variant: 'teal' },
        start:   { icon: 'fa-play', variant: 'primary' },
        quiesce: { icon: 'fa-pause', variant: 'gray' },
        stop:    { icon: 'fa-stop', variant: 'danger' },
        unload:  { icon: 'fa-eject', variant: 'danger' },
        reload:  { icon: 'fa-rotate', variant: 'teal' }
    };

    function verbMeta(verb) {
        return VERB_META[verb] || { icon: 'fa-gear', variant: 'gray' };
    }

    // 运行期阶段 → 色调（受管外置插件的精确生命周期状态）。
    var PHASE_TONE = {
        STARTED: 'ok', LOADED: 'info', QUIESCED: 'warn', STOPPED: 'idle', UNLOADED: 'idle'
    };

    // 图标贴片的图标 / 强调色：后端暂无每插件展示元数据，按来源派生（待后端补齐每插件 presentation 字段后再读取）。
    var PRESENTATION = {
        'built-in':      { icon: 'fa-solid fa-puzzle-piece', color: 'var(--accent-pixiv)' },
        'external':      { icon: 'fa-solid fa-cube', color: 'var(--accent-purple)' },
        'not-installed': { icon: 'fa-solid fa-box-open', color: 'var(--accent-gray)' }
    };

    function presentationOf(source) {
        return PRESENTATION[source] || PRESENTATION['built-in'];
    }

    // 卡片描述：后端条目暂不携带每插件简介（仅核心 / 计划任务宿主有 summary key，且未投影到条目）。
    // 退化为按来源给出的通用描述（待后端补齐每插件 descriptionKey 后再读取）。
    function describe(entry) {
        if (entry.source === 'not-installed') {
            return t('desc.not-installed', '该插件尚未安装。');
        }
        if (entry.source === 'external') {
            return t('desc.external', '外置插件，可在运行期启停。');
        }
        return t('desc.built-in', '内置插件，随主程序编译。');
    }

    // 后端条目 → 卡片视图模型。
    function buildViewModel(entry) {
        var source = entry.source || 'built-in';
        var status = entry.status || 'STARTED';
        var meta = statusMeta(status);
        var running = status === 'STARTED';
        var phase = entry.runtimePhase || null;
        var name = tns(entry.displayNamespace, entry.displayNameKey, entry.id);
        var presentation = presentationOf(source);
        var version = entry.version ? ('v' + entry.version) : null;
        var sub = [entry.id, version, t('source.' + source, source)].filter(Boolean).join(' · ');

        // 标签：用真实数据派生（类别 / 必须 / 是否可热管理）。
        var tags = [];
        if (entry.kind) {
            tags.push(t('kind.' + String(entry.kind).toLowerCase(), entry.kind));
        }
        if (entry.requiredByPolicy) {
            tags.push(t('tag.required', '必须'));
        }
        if (entry.managed) {
            tags.push(t('tag.managed', '可热管理'));
        }

        // 开关仅在「受管 + 允许停用」时可交互；内置 / 必须项锁定。
        var toggleable = !!entry.managed && entry.allowDisable !== false;

        return {
            id: entry.id,
            name: name,
            sub: sub,
            source: source,
            status: status,
            statusLabel: t(meta.key, status),
            statusTone: meta.tone,
            running: running,
            runtimePhase: phase,
            phaseLabel: phase ? t('phase.' + String(phase).toLowerCase(), phase) : null,
            phaseTone: phase ? (PHASE_TONE[phase] || 'idle') : null,
            icon: presentation.icon,
            iconColor: presentation.color,
            badgeKey: 'source.' + source,
            badgeTone: source === 'built-in' ? 'success' : (source === 'external' ? 'idle' : 'warn'),
            desc: describe(entry),
            tags: tags,
            api: entry.apiRequirement || null,
            deps: entry.dependencies || [],
            messages: entry.messages || [],
            availableActions: entry.availableActions || [],
            managed: !!entry.managed,
            toggleable: toggleable,
            requiredByPolicy: !!entry.requiredByPolicy,
            allowDisable: entry.allowDisable !== false,
            // —— 设计稿的更新机制（后端暂未实现）：占位，恒为关闭态，故横幅 / 进度 / 「更新」按钮均不渲染。——
            hasUpdate: false,
            latest: null,
            updating: false,
            progress: 0
        };
    }

    function allViewModels() {
        var list = (state.report && state.report.plugins) || [];
        return list.map(buildViewModel);
    }

    function tabCounts(models) {
        return {
            all: models.length,
            enabled: models.filter(function (p) { return p.running; }).length,
            disabled: models.filter(function (p) { return !p.running; }).length,
            external: models.filter(function (p) { return p.source === 'external'; }).length
        };
    }

    function tabsModel(models) {
        var counts = tabCounts(models);
        return [
            { id: 'all',      labelKey: 'tab.all',      icon: 'fa-puzzle-piece', count: counts.all },
            { id: 'enabled',  labelKey: 'tab.enabled',  icon: 'fa-circle-check', count: counts.enabled },
            { id: 'disabled', labelKey: 'tab.disabled', icon: 'fa-circle-pause', count: counts.disabled },
            { id: 'external', labelKey: 'tab.external', icon: 'fa-cube',         count: counts.external }
        ];
    }

    function filterModels(models) {
        var list = models.slice();
        var tab = state.activeTab;
        if (tab === 'enabled') {
            list = list.filter(function (p) { return p.running; });
        } else if (tab === 'disabled') {
            list = list.filter(function (p) { return !p.running; });
        } else if (tab === 'external') {
            list = list.filter(function (p) { return p.source === 'external'; });
        }
        var query = state.search.trim().toLowerCase();
        if (query) {
            list = list.filter(function (p) {
                return (p.id + ' ' + p.name + ' ' + p.tags.join(' ')).toLowerCase().indexOf(query) !== -1;
            });
        }
        return list;
    }

    // 概览统计：已安装 / 已启用为真实计数；外置 / 必须替代设计稿的「可更新 / 占用空间」（后端暂无对应数据来源）。
    function stats(models) {
        return {
            total: models.length,
            enabled: models.filter(function (p) { return p.running; }).length,
            external: models.filter(function (p) { return p.source === 'external'; }).length,
            required: models.filter(function (p) { return p.requiredByPolicy; }).length
        };
    }

    global.PixivPluginManage = {
        STATUS_URL: STATUS_URL,
        ACTION_URL_PREFIX: ACTION_URL_PREFIX,
        state: state,
        i18n: i18n,
        t: t,
        tns: tns,
        escapeHtml: escapeHtml,
        interpolate: interpolate,
        collectNamespaces: collectNamespaces,
        statusMeta: statusMeta,
        verbMeta: verbMeta,
        allViewModels: allViewModels,
        tabsModel: tabsModel,
        filterModels: filterModels,
        stats: stats
    };
})(window);
