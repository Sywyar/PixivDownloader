'use strict';
/*
 * 插件管理页核心：常量、共享状态、i18n 助手、以及把后端 /api/plugins/status 条目映射为卡片视图模型。
 * 本模块只定义全局命名空间 PixivPluginManage，无任何顶层副作用（启动逻辑收拢在 plugin-manage-init.js）。
 *
 * 数据来源：后端管理 API（admin-only，已接线）。后端响应见 PluginManagementService.PluginManagementReport：
 *   { recoveryMode, plugins: [ { id, displayNamespace, displayNameKey, descriptionKey, iconKey, colorToken,
 *     version, kind, sdkRequirement, dependencies, source, status, runtimePhase, managed, requiredByPolicy,
 *     allowDisable, executionMode, lifecyclePolicy, configuredEnabled, toggleable, availableActions, messages } ] }
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
        pluginOrder: [],   // 当前页面会话的插件顺序；显式刷新时重建
        loading: false,
        error: null,
        activeTab: 'all',  // all | enabled | disabled | external
        search: '',
        busyId: null,      // 正在执行运行期动词的插件 id（期间禁用其卡片按钮，动作串行化）
        installBusy: false // 本地包安装请求在途（期间禁用安装提交按钮，避免重复提交）
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

    // 判断导航响应中是否存在某个中性 placement 的有效入口；只用于控制宿主容器显隐，实际 href、图标与文案
    // 仍由通用 PixivNav renderer 消费完整 contribution。本页不识别贡献方插件 id，也不复制其展示语义。
    function hasNavigationForPlacement(items, placement) {
        var list = Array.isArray(items) ? items : [];
        for (var i = 0; i < list.length; i++) {
            var item = list[i];
            if (item && item.href && Array.isArray(item.placements)
                    && item.placements.indexOf(placement) !== -1) {
                return true;
            }
        }
        return false;
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
        CRASHED:               { key: 'status.crashed', tone: 'bad' },
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
        IO_ERROR: { key: 'verification.io-error', tone: 'bad' },
        PROVENANCE_INVALID: { key: 'verification.provenance-invalid', tone: 'bad' },
        NOT_INSTALLED: { key: 'verification.not-installed', tone: 'idle' }
    };

    function verificationMeta(status) {
        return VERIFICATION_META[status] || { key: 'verification.unverified-local', tone: 'idle' };
    }

    var TRUST_META = {
        NOT_INSTALLED: { key: 'trust.state.not-installed', tone: 'idle', fallback: '尚未安装' },
        BUILT_IN: { key: 'trust.state.built-in', tone: 'ok', fallback: '内置信任' },
        DEVELOPMENT: { key: 'trust.state.development', tone: 'warn', fallback: '仅开发模式信任' },
        OFFICIAL: { key: 'trust.state.official', tone: 'ok', fallback: '官方来源信任' },
        APPROVED: { key: 'trust.state.approved', tone: 'ok', fallback: '已批准执行信任' },
        CONFIRMATION_REQUIRED: { key: 'trust.state.confirmation-required', tone: 'warn', fallback: '需要执行信任确认' },
        REVOKED: { key: 'trust.state.revoked', tone: 'bad', fallback: '执行信任已撤销' },
        INVALID: { key: 'trust.state.invalid', tone: 'bad', fallback: '执行信任无效' }
    };

    function trustMeta(state) {
        return TRUST_META[state] || TRUST_META.INVALID;
    }

    // 插件代码执行位置。未知 token 按宿主进程完全信任收敛，避免向用户误报隔离保护。
    var EXECUTION_MODE_META = {
        DECLARATIVE_PROCESS:     { key: 'execution.declarative-process', tone: 'info', fallback: '声明式独立 JVM（有限隔离）' },
        HOST_PROCESS_FULL_TRUST: { key: 'execution.host-process-full-trust', tone: 'warn', fallback: '宿主进程完全信任' }
    };

    function executionModeOf(value) {
        var token = value == null ? '' : String(value).trim().toUpperCase();
        return token === 'DECLARATIVE_PROCESS' ? token : 'HOST_PROCESS_FULL_TRUST';
    }

    function executionModeMeta(mode) {
        return EXECUTION_MODE_META[executionModeOf(mode)];
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
        // 副标题只保留稳定 id：版本上移到名称行，来源由名称行徽标表达。
        var sub = String(entry.id);
        var verification = entry.verification || {};
        var verificationStatus = verification.status || null;
        var verificationInfo = verificationMeta(verificationStatus);
        var trust = entry.trust || {};
        var trustState = String(trust.state || 'INVALID');
        var trustInfo = trustMeta(trustState);
        var executionMode = executionModeOf(entry.executionMode);
        var executionInfo = executionModeMeta(executionMode);
        var lifecyclePolicy = lifecyclePolicyOf(entry.lifecyclePolicy);
        var lifecycleInfo = lifecyclePolicyMeta(lifecyclePolicy);
        var configuredEnabled = entry.configuredEnabled !== false;
        // 热重载插件的开关反映当前运行态；需重启插件反映已经持久化、将在重启后生效的配置态。
        var enabled = lifecyclePolicy === 'HOT_RELOAD' ? running : configuredEnabled;

        // 标签：类别 / 必须标签只由后端投影派生；卡片不再渲染标签区，这里仅作搜索索引保留。
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
            version: version,
            sub: sub,
            source: source,
            status: status,
            statusLabel: t(meta.key, status),
            statusTone: meta.tone,
            running: running,
            enabled: enabled,
            configuredEnabled: configuredEnabled,
            executionMode: executionMode,
            executionLabel: t(executionInfo.key, executionInfo.fallback),
            executionTone: executionInfo.tone,
            showExecutionTag: source === 'external',
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
            trustState: trustState,
            trustLabel: t(trustInfo.key, trustInfo.fallback),
            trustTone: trustInfo.tone,
            trustArtifactSha256: trust.artifactSha256 || null,
            trustPublisherKeyFingerprint: trust.publisherKeyFingerprint || null,
            trustApprovalType: trust.approvalType || null,
            trustApprovedAt: trust.approvedAt || null,
            trustRevokedAt: trust.revokedAt || null,
            trustApprovable: trust.approvable === true,
            trustRevocable: trust.revocable === true,
            trustSource: verification.source || source,
            trustPublisher: verification.publisher || verification.trustLabel || null,
            icon: iconClass(entry.iconKey),
            colorToken: colorTokenOf(entry.colorToken),
            badgeKey: 'source.' + source,
            badgeTone: source === 'built-in' ? 'success' : (source === 'external' ? 'idle' : 'warn'),
            desc: describe(entry),
            tags: tags,
            sdk: entry.sdkRequirement || null,
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

    function applyReport(report, resetOrder) {
        var plugins = report && Array.isArray(report.plugins) ? report.plugins : [];
        if (resetOrder) state.pluginOrder = [];

        var positions = Object.create(null);
        state.pluginOrder.forEach(function (id, index) { positions[id] = index; });
        plugins.forEach(function (plugin) {
            var id = String(plugin.id);
            if (!Object.prototype.hasOwnProperty.call(positions, id)) {
                positions[id] = state.pluginOrder.length;
                state.pluginOrder.push(id);
            }
        });

        state.report = Object.assign({}, report, {
            plugins: plugins.slice().sort(function (left, right) {
                return positions[String(left.id)] - positions[String(right.id)];
            })
        });
        return state.report;
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

    function hasAcceptedSignatureExtension(filename) {
        var name = filename == null ? '' : String(filename).toLowerCase();
        return name.endsWith('.sig');
    }

    // 安装结果色调：恢复阻断优先于 accepted（落盘存在）与 activated（运行时已加载），必须作为失败态展示；其余 accepted
    // 结果里 DUPLICATE（已存在、无改动）记为中性 info，新装 / 升级 / 降级记为成功 ok，未 accepted 记为 bad。
    function installTone(outcome, accepted, recoveryBlocked) {
        if (recoveryBlocked) return 'bad';
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
        var recoveryBlocked = r.recoveryBlocked === true;
        return {
            outcome: outcome,
            accepted: accepted,
            recoveryBlocked: recoveryBlocked,
            effectiveAfterRestart: r.effectiveAfterRestart === true,
            status: typeof r.status === 'number' ? r.status : null,
            tone: installTone(outcome, accepted, recoveryBlocked),
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

    // 安装完成后的 toast 语义。恢复阻断必须先于 activated / accepted 判定，并直接保留后端按请求语言解析的 message，
    // 避免已经进入待恢复状态的事务被绿色成功提示掩盖。
    function installFeedback(model) {
        var m = model || {};
        if (m.recoveryBlocked) {
            return {
                message: m.message || t('install.toast.recovery-blocked', '安装事务需要在重启后恢复。'),
                tone: 'error'
            };
        }
        if (m.activated) {
            return { message: t('install.toast.accepted', '插件已安装并激活。'), tone: 'ok' };
        }
        if (m.rolledBack) {
            return { message: t('install.rollback-note', '新版本激活失败，已恢复原版本。'), tone: 'error' };
        }
        if (m.accepted) {
            return { message: t('install.toast.accepted', '插件已安装。'), tone: 'ok' };
        }
        return {
            message: t('install.toast.rejected', '未安装：{message}', { message: m.message || m.outcome || '' }),
            tone: 'error'
        };
    }

    // 纯前端的本地校验提示（未选文件 / 非法选择 / 网络异常等）：与 buildInstallResult 同形态，供结果区统一渲染。
    function localInstallNotice(message, tone) {
        return {
            outcome: null, accepted: false, recoveryBlocked: false, effectiveAfterRestart: false, status: null,
            tone: tone || 'warn', message: message || null,
            pluginId: null, version: null, previousVersion: null, packageId: null, targetVersion: null,
            operation: null, runtimePhase: null, updated: false, transactionId: null,
            activated: false, rolledBack: false, rollbackVersion: null,
            errors: [], warnings: [], localValidation: true
        };
    }

    function trustConfirmationOptions(details) {
        var r = details || {};
        var fingerprint = r.publisherKeyFingerprint || r.fingerprint || null;
        var signed = r.signed === true || (r.signed == null && !!fingerprint);
        var executionMode = executionModeOf(r.executionMode);
        var execution = r.executionLabel
            || t(executionModeMeta(executionMode).key, executionModeMeta(executionMode).fallback);
        var source = r.repositoryId || r.source || t('trust.confirm.source.local', '本地上传');
        var publisher = r.publisher || t('trust.confirm.publisher.unknown', '无法确认');
        var signature = signed
            ? t('trust.confirm.signature.signed', '已签名')
            : t('trust.confirm.signature.unsigned', '未签名');
        var message = t('trust.confirm.risk',
            '此插件将在 PixivDownloader 进程中运行，拥有与 PixivDownloader 相同的本机权限。它可以访问当前用户可访问的文件和网络、运行后台任务、注册本地接口，并可能在 PixivDownloader 页面中执行脚本。安装插件相当于运行一个本地应用。请只安装你信任的来源。');
        if (!signed) {
            message += '\n\n' + t('trust.confirm.unsigned-risk',
                '此插件没有发布者签名。PixivDownloader 无法证明它来自谁，也无法确认后续更新是否仍由同一作者发布。');
        }
        message += '\n\n' + t('trust.confirm.details',
            '插件 ID：{pluginId}\n版本：{version}\n来源：{source}\n发布者：{publisher}\n签名状态：{signature}\n发布者指纹：{fingerprint}\n制品 SHA-256：{sha256}\n执行模式：{executionMode}', {
                pluginId: r.pluginId || r.id || '',
                version: r.version || '',
                source: source,
                publisher: publisher,
                signature: signature,
                fingerprint: fingerprint || t('trust.confirm.fingerprint.unavailable', '不适用'),
                sha256: r.artifactSha256 || r.sha256 || '',
                executionMode: execution
            });
        return {
            title: t('trust.confirm.title', '确认插件执行信任'),
            message: message,
            confirmLabel: t('trust.confirm.allow', '我信任此插件并允许运行'),
            cancelLabel: t('trust.confirm.cancel', '取消')
        };
    }

    async function installPackageWithConfirmation(file, signature, allowDowngrade) {
        var confirmedArtifacts = Object.create(null);
        var confirmTrust = null;
        while (true) {
            var response = await global.PixivPluginManage.installPackage(
                file, signature, allowDowngrade, confirmTrust);
            if (!response || response.outcome !== 'TRUST_CONFIRMATION_REQUIRED'
                    || !response.trustRequirement
                    || !global.PixivFeedback
                    || typeof global.PixivFeedback.confirm !== 'function') return response;
            var sha256 = String(response.trustRequirement.artifactSha256 || '').toLowerCase();
            if (!/^[0-9a-f]{64}$/.test(sha256) || confirmedArtifacts[sha256]) return response;
            var confirmed = await global.PixivFeedback.confirm(
                trustConfirmationOptions(response.trustRequirement));
            if (!confirmed) return response;
            confirmedArtifacts[sha256] = true;
            confirmTrust = sha256;
        }
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
        hasNavigationForPlacement: hasNavigationForPlacement,
        statusMeta: statusMeta,
        verbMeta: verbMeta,
        verificationMeta: verificationMeta,
        trustMeta: trustMeta,
        executionModeOf: executionModeOf,
        executionModeMeta: executionModeMeta,
        lifecyclePolicyOf: lifecyclePolicyOf,
        lifecyclePolicyMeta: lifecyclePolicyMeta,
        applyReport: applyReport,
        allViewModels: allViewModels,
        tabsModel: tabsModel,
        filterModels: filterModels,
        stats: stats,
        hasAcceptedExtension: hasAcceptedExtension,
        hasAcceptedSignatureExtension: hasAcceptedSignatureExtension,
        buildInstallResult: buildInstallResult,
        installFeedback: installFeedback,
        localInstallNotice: localInstallNotice,
        trustConfirmationOptions: trustConfirmationOptions,
        installPackageWithConfirmation: installPackageWithConfirmation
    };
})(window);
