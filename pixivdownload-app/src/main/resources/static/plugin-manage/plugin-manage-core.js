'use strict';
/*
 * 插件管理页核心：常量、共享状态、i18n 助手、以及把后端 /api/plugins/status 条目映射为卡片视图模型。
 * 本模块只定义全局命名空间 PixivPluginManage，无任何顶层副作用（启动逻辑收拢在 plugin-manage-init.js）。
 *
 * 数据来源：后端管理 API（admin-only，已接线）。后端响应见 PluginManagementService.PluginManagementReport：
 *   { recoveryMode, plugins: [ { id, displayNamespace, displayNameKey, descriptionKey, iconKey, colorToken,
 *     version, kind, apiRequirement, dependencies, source, status, runtimePhase, managed, requiredByPolicy,
 *     allowDisable, lifecyclePolicy, configuredEnabled, toggleable, availableActions, messages } ] }
 * 其中 descriptionKey 是纯 i18n key（在 displayNamespace 内解析）；iconKey / colorToken 是<b>受控展示 token</b>
 * （非 URL / CSS / 远程资源），经共享 PixivPluginPresentationTokens 映射为图标 class / 颜色 class，未知值回退默认。设计稿里后端仍未
 * 提供的字段（更新机制 / 体积 / 下载量 / 作者）在此处优雅留空（见各 vm.hasUpdate 等占位字段），待后端补齐后再点亮。
 */
(function (global) {
    var STATUS_URL = '/api/plugins/status';
    var ACTION_URL_PREFIX = '/api/plugins/';
    var BACKEND_RESTART_URL = '/api/plugins/backend-restart';
    var INSTALL_URL = '/api/plugins/install';
    // 受支持的本地插件包扩展名（与后端安装器一致：仅 .jar / .zip）；用于 <input accept> 与本地预校验。
    var INSTALL_ACCEPT = '.jar,.zip';

    // 共享视图状态：渲染层只读，init 层写。
    var state = {
        report: null,      // 最近一次 /api/plugins/status 响应
        loading: false,
        error: null,
        activeTab: 'all',  // all | enabled | disabled | external
        search: '',
        busyId: null,      // 正在执行运行期动词的插件 id（期间禁用其卡片按钮，动作串行化）
        installBusy: false, // 本地包安装请求在途（期间禁用安装提交按钮，避免重复提交）
        marketNav: null    // 插件市场导航入口（{href} 取自 /api/navigation；plugin-market 禁用时为 null → 分段控件隐藏）
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
        remove:  { icon: 'fa-trash', variant: 'danger' },
        restart: { icon: 'fa-arrows-rotate', variant: 'teal' },
        reload:  { icon: 'fa-rotate', variant: 'teal' }
    };

    function verbMeta(verb) {
        return VERB_META[verb] || { icon: 'fa-gear', variant: 'gray' };
    }

    // 运行期阶段 → 色调（受管外置插件的精确生命周期状态）。
    var PHASE_TONE = {
        STARTED: 'ok', LOADED: 'info', QUIESCED: 'warn', STOPPED: 'idle', UNLOADED: 'idle'
    };

    var VERIFICATION_META = {
        VERIFIED_OFFICIAL: { key: 'verification.verified-official', tone: 'ok' },
        VERIFIED_CUSTOM: { key: 'verification.verified-custom', tone: 'ok' },
        UNVERIFIED_LOCAL: { key: 'verification.unverified-local', tone: 'warn' },
        UNSIGNED_ALLOWED: { key: 'verification.unsigned-allowed', tone: 'warn' },
        SIGNATURE_REQUIRED: { key: 'verification.signature-required', tone: 'bad' },
        UNKNOWN_KEY: { key: 'verification.unknown-key', tone: 'bad' },
        REVOKED_KEY: { key: 'verification.revoked-key', tone: 'bad' },
        INVALID_SIGNATURE: { key: 'verification.invalid-signature', tone: 'bad' },
        HASH_MISMATCH: { key: 'verification.hash-mismatch', tone: 'bad' },
        NOT_INSTALLED: { key: 'verification.not-installed', tone: 'idle' }
    };

    function verificationMeta(status) {
        return VERIFICATION_META[status] || { key: 'verification.unverified-local', tone: 'idle' };
    }

    // 插件声明的启停生效策略。未知 token 按完整进程重启收敛，避免误走热启停。
    var LIFECYCLE_POLICY_META = {
        HOT_RELOAD:      { key: 'lifecycle.hot-reload', tone: 'hot', fallback: '热重载' },
        BACKEND_RESTART: { key: 'lifecycle.backend-restart', tone: 'backend', fallback: '重启后端' },
        PROCESS_RESTART: { key: 'lifecycle.process-restart', tone: 'process', fallback: '重启软件' }
    };

    function lifecyclePolicyOf(value) {
        var token = value == null ? '' : String(value).trim().toUpperCase();
        return LIFECYCLE_POLICY_META[token] ? token : 'PROCESS_RESTART';
    }

    function lifecyclePolicyMeta(policy) {
        return LIFECYCLE_POLICY_META[lifecyclePolicyOf(policy)];
    }

    function iconClass(iconKey) {
        return global.PixivPluginPresentationTokens.iconClass(iconKey);
    }

    function colorTokenOf(token) {
        return global.PixivPluginPresentationTokens.colorToken(token);
    }

    // 按来源的通用简介（descriptionKey 缺失时的回退文案）。
    function sourceDesc(source) {
        if (source === 'not-installed') {
            return t('desc.not-installed', '该插件尚未安装。');
        }
        if (source === 'external') {
            return t('desc.external', '外置插件；启停后的生效方式以生命周期标签为准。');
        }
        return t('desc.built-in', '内置插件，随主程序编译。');
    }

    // 卡片描述：优先用后端投影的每插件简介纯 key descriptionKey（在 displayNamespace 内经 tns 解析）；缺失
    // （未安装无描述符 / 无 namespace / 缺 bundle key）时优雅回退到按来源的通用文案，不影响渲染。
    function describe(entry) {
        var fallback = sourceDesc(entry.source);
        return entry.descriptionKey ? tns(entry.displayNamespace, entry.descriptionKey, fallback) : fallback;
    }

    // 后端条目 → 卡片视图模型。
    function buildViewModel(entry) {
        var source = entry.source || 'built-in';
        var status = entry.status || 'STARTED';
        var meta = statusMeta(status);
        var phase = entry.runtimePhase || null;
        // 受管外置插件以 runtimePhase 为权威运行态；不受管 / 无运行阶段的条目回退到 status。
        var running;
        if (entry.managed && entry.runtimePhase) {
            running = entry.runtimePhase === 'STARTED';
        } else {
            running = entry.status === 'STARTED';
        }
        var name = tns(entry.displayNamespace, entry.displayNameKey, entry.id);
        var version = entry.version ? ('v' + entry.version) : null;
        var sub = [entry.id, version, t('source.' + source, source)].filter(Boolean).join(' · ');
        var verification = entry.verification || {};
        var verificationStatus = verification.status || null;
        var verificationInfo = verificationMeta(verificationStatus);
        var lifecyclePolicy = lifecyclePolicyOf(entry.lifecyclePolicy);
        var lifecycleInfo = lifecyclePolicyMeta(lifecyclePolicy);
        var configuredEnabled = entry.configuredEnabled !== false;
        // 热重载插件的开关反映当前运行态；需重启插件反映已经持久化、将在重启后生效的配置态。
        var enabled = lifecyclePolicy === 'HOT_RELOAD' ? running : configuredEnabled;

        // 标签：类别 / 必须标签与独立的生命周期策略标签均只由后端投影派生。
        var tags = [];
        if (entry.kind) {
            tags.push(t('kind.' + String(entry.kind).toLowerCase(), entry.kind));
        }
        if (entry.requiredByPolicy) {
            tags.push(t('tag.required', '必须'));
        }
        // 开关可用性以后端稳定字段为权威；热重载还要求当前确由运行期管理，避免损坏 / 不兼容插件得到必然失败的开关。
        // PROCESS_RESTART 插件可以不受热生命周期管理但仍允许持久化启停。
        var toggleable = entry.toggleable === true
            && (lifecyclePolicy !== 'HOT_RELOAD' || entry.managed === true);

        return {
            id: entry.id,
            name: name,
            sub: sub,
            source: source,
            status: status,
            statusLabel: t(meta.key, status),
            statusTone: meta.tone,
            running: running,
            enabled: enabled,
            configuredEnabled: configuredEnabled,
            lifecyclePolicy: lifecyclePolicy,
            lifecycleLabel: t(lifecycleInfo.key, lifecycleInfo.fallback),
            lifecycleTone: lifecycleInfo.tone,
            showLifecycleTag: source === 'external',
            runtimePhase: phase,
            phaseLabel: phase ? t('phase.' + String(phase).toLowerCase(), phase) : null,
            phaseTone: phase ? (PHASE_TONE[phase] || 'idle') : null,
            verificationStatus: verificationStatus,
            verificationLabel: verificationStatus ? t(verificationInfo.key, verificationStatus) : null,
            verificationTone: verificationInfo.tone,
            verificationTrustLabel: verification.trustLabel || verification.publisher || null,
            icon: iconClass(entry.iconKey),
            colorToken: colorTokenOf(entry.colorToken),
            badgeKey: 'source.' + source,
            badgeTone: source === 'built-in' ? 'success' : (source === 'external' ? 'idle' : 'warn'),
            desc: describe(entry),
            tags: tags,
            api: entry.apiRequirement || null,
            deps: entry.dependencies || [],
            messages: (entry.messages || []).concat(entry.operationDiagnostic ? [entry.operationDiagnostic] : []),
            // 只有热重载策略继续暴露既有运行期动词；其余策略统一走持久化启停开关。
            availableActions: lifecyclePolicy === 'HOT_RELOAD' ? (entry.availableActions || []) : [],
            managed: !!entry.managed,
            toggleable: toggleable,
            requiredByPolicy: !!entry.requiredByPolicy,
            allowDisable: entry.allowDisable !== false,
            generation: entry.generation == null ? null : entry.generation,
            operation: entry.operation || 'IDLE',
            hasUpdate: false,
            latest: null,
            updating: !!entry.operation && entry.operation !== 'IDLE' && entry.operation !== 'FAILED',
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
            enabled: models.filter(function (p) { return p.enabled; }).length,
            disabled: models.filter(function (p) { return !p.enabled; }).length,
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
            list = list.filter(function (p) { return p.enabled; });
        } else if (tab === 'disabled') {
            list = list.filter(function (p) { return !p.enabled; });
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
            enabled: models.filter(function (p) { return p.enabled; }).length,
            external: models.filter(function (p) { return p.source === 'external'; }).length,
            required: models.filter(function (p) { return p.requiredByPolicy; }).length
        };
    }

    // —— 本地插件包安装（消费 POST /api/plugins/install 的 PluginInstallResponse） ——

    // 本地预校验：文件名是否为受支持的扩展名（.jar / .zip，大小写不敏感）。仅作即时反馈；包是否合法仍以后端为准。
    function hasAcceptedExtension(filename) {
        var name = filename == null ? '' : String(filename).toLowerCase();
        return name.endsWith('.jar') || name.endsWith('.zip');
    }

    // 安装结果色调：accepted（落盘存在）里 DUPLICATE（已存在、无改动）记为中性 info，其余（新装 / 升级 / 降级）记为
    // 成功 ok；未 accepted（各类拒绝 / 失败）一律记为 bad。仅用于结果区的着色，不参与任何机器判别。
    function installTone(outcome, accepted) {
        if (!accepted) return 'bad';
        return outcome === 'DUPLICATE' ? 'info' : 'ok';
    }

    // 后端 PluginInstallResponse → 安装结果区视图模型（纯映射，无副作用）。message 由后端按请求语言解析、直接展示；
    // outcome 是稳定机器码（结果区以代码片展示，便于排错）；errors=安装器诊断说明、warnings=尚未满足的依赖（建议性）。
    // 任何字符串都不在此拼接 HTML——渲染层统一转义。
    function buildInstallResult(response) {
        var r = response || {};
        var outcome = r.outcome || null;
        var accepted = r.accepted === true;
        return {
            outcome: outcome,
            accepted: accepted,
            effectiveAfterRestart: r.effectiveAfterRestart === true,
            status: typeof r.status === 'number' ? r.status : null,
            tone: installTone(outcome, accepted),
            message: r.message || null,
            pluginId: r.pluginId || null,
            version: r.version || null,
            previousVersion: r.previousVersion || null,
            packageId: r.packageId || r.pluginId || null,
            targetVersion: r.targetVersion || r.version || null,
            operation: r.operation || null,
            runtimePhase: r.runtimePhase || null,
            updated: r.updated === true,
            transactionId: r.transactionId || null,
            activated: r.activated === true,
            rolledBack: r.rolledBack === true,
            rollbackVersion: r.rollbackVersion || null,
            errors: Array.isArray(r.diagnostics) ? r.diagnostics : [],
            warnings: Array.isArray(r.unsatisfiedDependencies) ? r.unsatisfiedDependencies : [],
            localValidation: false
        };
    }

    // 纯前端的本地校验提示（未选文件 / 非法选择 / 网络异常等）：与 buildInstallResult 同形态，供结果区统一渲染。
    function localInstallNotice(message, tone) {
        return {
            outcome: null, accepted: false, effectiveAfterRestart: false, status: null,
            tone: tone || 'warn', message: message || null,
            pluginId: null, version: null, previousVersion: null, packageId: null, targetVersion: null,
            operation: null, runtimePhase: null, updated: false, transactionId: null,
            activated: false, rolledBack: false, rollbackVersion: null,
            errors: [], warnings: [], localValidation: true
        };
    }

    global.PixivPluginManage = {
        STATUS_URL: STATUS_URL,
        ACTION_URL_PREFIX: ACTION_URL_PREFIX,
        BACKEND_RESTART_URL: BACKEND_RESTART_URL,
        INSTALL_URL: INSTALL_URL,
        INSTALL_ACCEPT: INSTALL_ACCEPT,
        state: state,
        i18n: i18n,
        t: t,
        tns: tns,
        escapeHtml: escapeHtml,
        interpolate: interpolate,
        collectNamespaces: collectNamespaces,
        statusMeta: statusMeta,
        verbMeta: verbMeta,
        verificationMeta: verificationMeta,
        lifecyclePolicyOf: lifecyclePolicyOf,
        lifecyclePolicyMeta: lifecyclePolicyMeta,
        allViewModels: allViewModels,
        tabsModel: tabsModel,
        filterModels: filterModels,
        stats: stats,
        hasAcceptedExtension: hasAcceptedExtension,
        buildInstallResult: buildInstallResult,
        localInstallNotice: localInstallNotice
    };
})(window);
