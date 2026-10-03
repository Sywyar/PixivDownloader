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
        hostElevated: false,
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
            return client.t(key.indexOf(':') === -1 ? 'plugin-market:' + key : key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    };

    PMK.TRUST_CONFIRMATION_REQUIRED = 'TRUST_CONFIRMATION_REQUIRED';
    PMK.trustConfirmationOptions = function (requirement) {
        var r = requirement || {};
        var executionMode = String(r.executionMode || 'HOST_PROCESS_FULL_TRUST');
        var executionLabel = PMK.t('install.trust.execution.' + executionMode.toLowerCase(), executionMode);
        var source = r.repositoryId || r.source || PMK.t('install.trust.source.local', '本地上传');
        var publisher = r.publisher || PMK.t('install.trust.publisher.unknown', '无法确认');
        var signature = r.signed === true
            ? PMK.t('install.trust.signature.signed', '已签名')
            : PMK.t('install.trust.signature.unsigned', '未签名');
        var fingerprint = r.publisherKeyFingerprint
            || PMK.t('install.trust.fingerprint.unavailable', '不适用');
        var fullTrust = executionMode === 'HOST_PROCESS_FULL_TRUST';
        var message = fullTrust
            ? PMK.t('install.trust.risk',
                '此插件将在 PixivDownloader 进程中运行，拥有与 PixivDownloader 相同的本机权限。它可以访问当前用户可访问的文件和网络、运行后台任务、注册本地接口，并可能在 PixivDownloader 页面中执行脚本。安装插件相当于运行一个本地应用。请只安装你信任的来源。')
            : PMK.t('install.trust.declarative-risk',
                '此插件将在独立声明式 worker 中运行。worker 有协议与资源限制，但仍使用与 PixivDownloader 相同的系统账号，不是完整的操作系统沙箱。请只安装你信任的来源。');
        if (fullTrust && PMK.state.hostElevated) {
            message += '\n\n' + PMK.t('install.trust.elevated-risk',
                'PixivDownloader 当前正以高权限运行；此 full-trust 插件将继承同等权限。');
        }
        if (r.signed !== true) {
            message += '\n\n' + PMK.t('install.trust.unsigned-risk',
                '此插件没有发布者签名。PixivDownloader 无法证明它来自谁，也无法确认后续更新是否仍由同一作者发布。');
        }
        message += '\n\n' + PMK.t('install.trust.details',
            '插件 ID：{pluginId}\n版本：{version}\n来源：{source}\n发布者：{publisher}\n签名状态：{signature}\n发布者指纹：{fingerprint}\n制品 SHA-256：{sha256}\n执行模式：{executionMode}', {
                pluginId: r.pluginId || '',
                version: r.version || '',
                source: source,
                publisher: publisher,
                signature: signature,
                fingerprint: fingerprint,
                sha256: r.artifactSha256 || '',
                executionMode: executionLabel
            });
        return {
            title: PMK.t('install.trust.title', '确认插件执行信任'),
            message: message + '\n\n' + global.PixivPluginPresentationTokens.trustLines(r, PMK.state.i18n.client).join('\n'),
            confirmLabel: PMK.t('install.trust.confirm', '我信任此插件并允许运行'),
            cancelLabel: PMK.t('install.trust.cancel', '取消安装')
        };
    };

    PMK.installPluginWithConfirmation = function (repositoryId, pluginId, version) {
        function canConfirm() {
            return global.PixivFeedback && typeof global.PixivFeedback.confirm === 'function';
        }
        var confirmedArtifacts = Object.create(null);
        var completedDependencies = Object.create(null);
        function rememberDependencies(response) {
            var body = response.body || {};
            (body.dependencyInstallResults || []).forEach(function (item) {
                completedDependencies[item.pluginId] = item;
            });
            body.dependencyInstallResults = Object.keys(completedDependencies).map(function (id) {
                return completedDependencies[id];
            });
            response.body = body;
            return response;
        }
        function previewAndAttempt(confirmations) {
            return PMK.api.previewInstall(repositoryId, pluginId, version).then(function (preview) {
                if (!canConfirm() || !preview || !/^[0-9a-f]{64}$/.test(preview.fingerprint)) {
                    throw new Error(PMK.t('install.preview.unavailable'));
                }
                var lines = (preview.packages || []).map(function (item) {
                    return PMK.t('install.preview.package', '', {
                        plugin: item.pluginId, version: item.version,
                        source: item.repositoryId || PMK.t('install.preview.local'),
                        previous: item.installedVersion || '—',
                        previousSource: item.installedRepositoryId || PMK.t('install.preview.local'),
                        publisher: item.publisher || '—', sha256: item.sha256 || '—',
                        action: PMK.t('install.preview.action.' + String(item.action).toLowerCase()),
                        consumers: (item.consumers || []).join(', ') || '—',
                        active: (item.activeConsumers || []).join(', ') || '—'
                    });
                });
                (preview.conflicts || []).forEach(function (conflict) {
                    lines.push(PMK.t('install.preview.conflict.' + conflict.code.toLowerCase(), '', {
                        plugin: conflict.pluginId, details: (conflict.arguments || []).join(', ')
                    }));
                });
                lines.push(PMK.t('install.preview.boundary'));
                if ((preview.conflicts || []).length) {
                    return rememberDependencies({kind: 'error', body: {
                        code: 'INSTALL_PREVIEW_BLOCKED', message: lines.join('\n\n')
                    }});
                }
                return global.PixivFeedback.confirm({
                    title: PMK.t('install.preview.title'), message: lines.join('\n\n'),
                    confirmLabel: PMK.t('install.preview.confirm'), cancelLabel: PMK.t('install.trust.cancel')
                }).then(function (confirmed) {
                    if (!confirmed) return rememberDependencies({ kind: 'error', body: {
                        code: 'CANCELLED', message: PMK.t('install.preview.cancelled')
                    } });
                    confirmations.fingerprint = preview.fingerprint;
                    return attempt(confirmations);
                });
            });
        }
        function attempt(confirmations) {
            return PMK.api.installPlugin(repositoryId, pluginId, version, confirmations).then(function (response) {
                rememberDependencies(response);
                var body = response.body || {};
                var trustRequired = body.outcome === PMK.TRUST_CONFIRMATION_REQUIRED
                    || body.code === PMK.TRUST_CONFIRMATION_REQUIRED;
                if (trustRequired && body.trustRequirement && canConfirm()) {
                    var sha256 = String(body.trustRequirement.artifactSha256 || '').toLowerCase();
                    if (!/^[0-9a-f]{64}$/.test(sha256) || confirmedArtifacts[sha256]) return response;
                    return global.PixivFeedback.confirm(PMK.trustConfirmationOptions(body.trustRequirement))
                        .then(function (confirmed) {
                        if (!confirmed) return response;
                        confirmedArtifacts[sha256] = true;
                        return previewAndAttempt({ trustSha256: sha256 });
                    });
                }
                return response;
            });
        }
        return previewAndAttempt({ trustSha256: null }).catch(function (failure) {
            return rememberDependencies({kind: 'error', body: {
                code: failure.body && failure.body.code || 'REQUEST_FAILED',
                message: failure.message || PMK.t('install.preview.unavailable')
            }});
        });
    };

    PMK.currentLang = function () {
        var client = PMK.state.i18n.client;
        // 语言一律来自 meta：优先当前语言，缺省用 meta 的 defaultLang，不写死语言
        return client ? (client.lang || client.defaultLang || '') : '';
    };

    // 恢复横幅原因：直接投影 /api/plugins/status 的结构化恢复原因，不另造页面私有状态协议。
    PMK.recoveryReasons = function (report) {
        if (!report || !report.recoveryMode) return [];
        var reasons = [];
        var transaction = report.transactionRecovery;
        if (transaction && transaction.safeToScan === false) {
            var failures = Array.isArray(transaction.failures) ? transaction.failures : [];
            if (failures.length) {
                failures.forEach(function (failure) {
                    var detail = [failure.kind, failure.transactionId, failure.detail].filter(Boolean).join(' · ');
                    reasons.push(PMK.t('recovery.reason.transaction', '插件安装事务恢复失败：{detail}', { detail: detail }));
                });
            } else {
                reasons.push(PMK.t('recovery.reason.transaction-unknown', '插件安装事务尚未完成恢复，请查看日志并重启程序。'));
            }
        }
        function appendPluginReason(reason) {
            if (!reason) return;
            var pluginId = reason.pluginId || reason.id;
            var status = String(reason.status || '');
            if (status === 'MISSING_REQUIRED') {
                reasons.push(PMK.t('recovery.reason.missing', '必装插件 {pluginId} 尚未安装，请先安装或修复。', {
                    pluginId: pluginId
                }));
                return;
            }
            if (status === 'FAILED') {
                if (reason.messageKey === 'plugin.recovery.transaction' && transaction) return;
                var detail = Array.isArray(reason.messages) ? reason.messages.filter(Boolean).join(' · ') : '';
                reasons.push(detail
                    ? PMK.t('recovery.reason.failed', '插件 {pluginId} 启动失败：{detail}', {
                        pluginId: pluginId, detail: detail
                    })
                    : PMK.t('recovery.reason.failed-no-detail', '插件 {pluginId} 启动失败，请查看日志并修复或重新安装。', {
                        pluginId: pluginId
                    }));
                return;
            }
            if (status !== 'STARTED') {
                reasons.push(PMK.t('recovery.reason.unavailable', '必装插件 {pluginId} 当前不可用（{status}），请安装或修复。', {
                    pluginId: pluginId, status: status
                }));
            }
        }
        var structuredReasons = Array.isArray(report.recoveryReasons) ? report.recoveryReasons : null;
        if (structuredReasons) {
            structuredReasons.forEach(appendPluginReason);
        } else {
            (Array.isArray(report.plugins) ? report.plugins : [])
                .filter(function (plugin) {
                    return plugin && (plugin.requiredByPolicy || plugin.status === 'FAILED');
                })
                .forEach(appendPluginReason);
        }
        if (!reasons.length) {
            reasons.push(PMK.t('recovery.reason.unknown', '插件状态异常，请查看日志并修复或重新安装相关插件。'));
        }
        return reasons;
    };

    PMK.escapeHtml = function (str) {
        return String(str == null ? '' : str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    };

    // 文本、链接标签与文档共用语言选择；有作者默认语言时不把简繁体当作同一主语言。
    PMK.localeKey = function (map, defaultLocale) {
        if (!map || typeof map !== 'object') return null;
        var keys = Object.keys(map).filter(function (key) { return !!map[key]; });
        function normalized(value) { return String(value || '').replace(/_/g, '-').toLowerCase(); }
        function exact(value) { return keys.find(function (key) { return normalized(key) === normalized(value); }); }
        var lang = normalized(PMK.currentLang());
        var match = exact(lang);
        if (match) return match;
        var base = lang.split('-')[0];
        if (!defaultLocale) return exact(base) || exact('zh') || exact('en') || keys[0] || null;
        if (base === 'zh') {
            function script(value) {
                try { return new Intl.Locale(value).maximize().script; } catch (error) { return null; }
            }
            match = keys.slice().sort().find(function (key) {
                return normalized(key).split('-')[0] === 'zh' && script(key) === script(lang);
            });
        } else match = exact(base) || keys.slice().sort().find(function (key) { return normalized(key).split('-')[0] === base; });
        return match || exact(defaultLocale) || exact('en') || keys.sort()[0] || null;
    };
    PMK.localeText = function (map, fallback, defaultLocale) {
        var key = PMK.localeKey(map, defaultLocale);
        return key ? map[key] : (fallback || '');
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
        YANKED:         { labelKey: 'common:plugin-trust.revocation.YANKED', icon: 'ban', variant: 'gray', disabled: true },
        REVOKED:        { labelKey: 'common:plugin-trust.revocation.REVOKED', icon: 'ban', variant: 'gray', disabled: true },
        // 无任何可安装版本制品的条目（后端 UNAVAILABLE）：稳定降级为不可点击的不可安装态，绝不渲染可点击但无响应的安装按钮。
        UNAVAILABLE:     { labelKey: 'install.state.unavailable', icon: 'ban',    variant: 'gray', disabled: true },
        // 前端本地请求态（安装 POST 在途）：不来自后端，安装结果仍以后端响应为准。
        INSTALLING:      { labelKey: 'install.state.installing', icon: 'spinner',         variant: 'primary', disabled: true },
        RECOVERY_BLOCKED: { labelKey: 'install.state.recovery-blocked', icon: 'triangle-exclamation', variant: 'gray', disabled: true },
        PENDING_RESTART: { labelKey: 'install.state.pending-restart', icon: 'circle-check', variant: 'success-outline', disabled: true },
        ACTIVATED:       { labelKey: 'install.state.activated', icon: 'circle-check', variant: 'success-outline', disabled: true }
    };
    PMK.installMeta = function (status) {
        var normalized = PMK.INSTALL_META[status] ? status : 'UNAVAILABLE';
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
