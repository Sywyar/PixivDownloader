'use strict';
/*
 * 插件市场页数据变换模块（框架无关纯函数）：把后端 catalog 条目派生为卡片 / 详情视图模型，并实现分类 / 筛选 / 搜索 /
 * 排序与安装结果映射。Vue 渲染器与命令式回退渲染器共用本模块，确保两条渲染路径的派生逻辑一致。无 DOM、无副作用。
 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var D = PMK.data = {};

    // —— 条目字段读取（市场元数据缺失时稳定降级，不破坏渲染）——
    function market(entry) { return entry && entry.market; }

    D.suspectedProblem = function (entry) {
        return !!entry && !!entry.installation
            && ['FAILED', 'CRASHED'].indexOf(entry.installation.runtimeStatus) !== -1;
    };

    D.focusReason = function (entry) {
        if (D.suspectedProblem(entry)) return 'failed';
        var focus = PMK.recovery && PMK.recovery.focus && PMK.recovery.focus();
        if (!focus || !entry) return '';
        if (focus.plugins && Object.prototype.hasOwnProperty.call(focus.plugins, entry.pluginId))
            return focus.plugins[entry.pluginId];
        return (focus.categories || []).indexOf(D.entryCategory(entry)) !== -1 ? 'gui' : '';
    };

    D.artifactMismatch = function (entry, pkg) {
        var local = entry && entry.installation;
        return !!local && local.state === 'PRESENT' && !!pkg && local.version === pkg.version
            && /^[a-f0-9]{64}$/i.test(local.sha256 || '') && /^[a-f0-9]{64}$/i.test(pkg.sha256 || '')
            && local.sha256.toLowerCase() !== pkg.sha256.toLowerCase();
    };

    D.developmentLoaded = function (entry) {
        var local = entry && entry.installation;
        return !!local && local.installedArtifactsEnabled === false && local.runtimeStatus === 'STARTED';
    };

    D.installationState = function (entry, pkg) {
        var local = entry && entry.installation;
        if (!local) return '';
        if (D.artifactMismatch(entry, pkg)) return 'mismatch';
        if (D.developmentLoaded(entry)) return 'development';
        if (local.state === 'UNKNOWN') return 'unknown';
        if (local.installedArtifactsEnabled === false && local.state === 'PRESENT') return 'stored-development';
        if (local.runtimeVersion && local.version && local.runtimeVersion !== local.version) return 'runtime-different';
        if (local.state === 'ABSENT' && local.runtimeStatus === 'STARTED') return 'runtime-only';
        if (local.runtimeStatus === 'DISABLED') return 'disabled';
        if (local.runtimeStatus === 'STOPPED') return 'stopped';
        if (['INCOMPATIBLE', 'INCOMPATIBLE_REQUIRED'].indexOf(local.runtimeStatus) !== -1) return 'incompatible';
        if (local.runtimeStatus === 'MISSING_REQUIRED') return 'dependency';
        if (['INSTALLED', 'RESOLVED', 'LOADED'].indexOf(local.runtimeStatus) !== -1) return 'not-started';
        if (local.state === 'PRESENT' && !local.runtimeStatus) return 'not-loaded';
        return '';
    };

    D.mergeEntries = function (entries, additions) {
        var byId = new Map();
        (entries || []).concat(additions || []).forEach(function (entry) { byId.set(entry.pluginId, entry); });
        return Array.from(byId.values());
    };

    D.entryName = function (entry) {
        var m = market(entry);
        return PMK.localeText(m && m.displayName, entry.pluginId, m && m.defaultLocale) || entry.pluginId;
    };
    D.pluginLabel = function (id, entries) {
        var entry = (entries || []).find(function (item) { return item.pluginId === id; });
        return global.PixivPluginPresentationTokens.pluginLabel(id, entry && D.entryName(entry));
    };
    D.dependencyLabel = function (dependency, entries) {
        var parts = /^([^?@]+)(\?)?(?:@(.*))?$/.exec(dependency);
        if (!parts) return dependency;
        return D.pluginLabel(parts[1], entries) + (parts[3] ? ' · ' + parts[3] : '')
            + (parts[2] ? ' · ' + PMK.t('common:plugin-info.optional-dependency') : '');
    };
    D.entryAuthor = function (entry) {
        var m = market(entry);
        return m && m.author ? m.author : '';
    };
    D.entrySummary = function (entry) {
        var m = market(entry);
        return m ? PMK.localeText(m.summary, '', m.defaultLocale) : '';
    };
    D.entryDescription = function (entry) {
        var m = market(entry);
        if (!m) return '';
        return PMK.localeText(m.description, '', m.defaultLocale) || PMK.localeText(m.summary, '', m.defaultLocale);
    };
    D.entryCategory = function (entry) {
        var m = market(entry);
        return (m && m.category) || 'utility';
    };
    D.entryOfficial = function (entry) {
        var verification = entry && packageVerification(entry);
        return !!entry && (verification ? verification.assuranceLevel : entry.assuranceLevel) === 'OFFICIAL';
    };
    D.entryRecommended = function (entry) {
        var m = market(entry);
        return !!(m && m.recommended);
    };
    // “默认安装”是 catalog 明确投影的中性展示事实；旧 / 社区清单缺字段时按 false，绝不据插件 id 或本机安装态猜测。
    D.entryDefaultInstalled = function (entry) {
        var m = market(entry);
        return !!(m && m.defaultInstalled);
    };
    D.entryDependency = function (entry) {
        return D.entryCategory(entry) === 'dependency';
    };
    D.entryTags = function (entry) {
        var m = market(entry);
        return (m && Array.isArray(m.tags)) ? m.tags : [];
    };
    function downloads(entry) {
        var m = market(entry);
        return m && m.totalDownloadCount != null ? Number(m.totalDownloadCount) : 0;
    }
    function rating(entry) {
        var m = market(entry);
        return m && m.rating != null ? Number(m.rating) : 0;
    }
    function updatedAt(entry) {
        var m = market(entry);
        return m && m.updatedTime ? (Date.parse(m.updatedTime) || 0) : 0;
    }

    // 按版本号取某个版本制品（用于详情弹窗的版本选择）。
    D.packageOf = function (entry, version) {
        var packages = (entry && entry.packages) || [];
        if (version) {
            for (var i = 0; i < packages.length; i++) {
                if (packages[i].version === version) return packages[i];
            }
        }
        return packages.length ? packages[0] : null;
    };

    D.defaultVersion = function (entry) {
        return entry && (entry.recommendedVersion || entry.latestVersion);
    };

    D.resolveVersion = function (entry, selectedVersion) {
        var packages = (entry && entry.packages) || [];
        var selected = packages.find(function (pkg) { return pkg.version === selectedVersion; })
            || D.packageOf(entry, D.defaultVersion(entry));
        return selected ? selected.version : null;
    };

    D.compatibilityNotice = function (entry) {
        if (entry.compatibilitySearchIncomplete) return PMK.t('compat.search-incomplete');
        if (entry.installStatus === 'NO_RECOMMENDATION') return PMK.t('compat.no-recommendation');
        if (entry.recommendedVersion && entry.recommendedVersion !== entry.latestVersion) {
            return PMK.t('compat.fallback', '', {latest: entry.latestVersion, selected: entry.recommendedVersion});
        }
        return entry.compatible === false ? PMK.t('compat.none') : '';
    };

    var VERIFICATION_BADGE_META = {
        VERIFIED_OFFICIAL: { labelKey: 'verification.verified-official', tone: 'ok', icon: 'fa-circle-check' },
        VERIFIED_CUSTOM: { labelKey: 'verification.verified-custom', tone: 'ok', icon: 'fa-circle-check' },
        VERIFIED_COMMUNITY: { labelKey: 'verification.verified-community', tone: 'ok', icon: 'fa-circle-check' },
        UNVERIFIED_LOCAL: { labelKey: 'verification.unverified-local', tone: 'warn', icon: 'fa-triangle-exclamation' },
        UNSIGNED_ALLOWED: { labelKey: 'verification.unsigned-allowed', tone: 'warn', icon: 'fa-triangle-exclamation' },
        SIGNATURE_REQUIRED: { labelKey: 'verification.signature-required', tone: 'danger', icon: 'fa-circle-exclamation' },
        UNKNOWN_KEY: { labelKey: 'verification.unknown-key', tone: 'danger', icon: 'fa-circle-exclamation' },
        REVOKED_KEY: { labelKey: 'verification.revoked-key', tone: 'danger', icon: 'fa-circle-exclamation' },
        INVALID_SIGNATURE: { labelKey: 'verification.invalid-signature', tone: 'danger', icon: 'fa-circle-exclamation' },
        HASH_MISMATCH: { labelKey: 'verification.hash-mismatch', tone: 'danger', icon: 'fa-circle-exclamation' },
        NOT_INSTALLED: { labelKey: 'verification.not-installed', tone: 'neutral', icon: 'fa-circle-minus' }
    };

    function verificationKey(status) {
        return 'verification.' + String(status).toLowerCase().replace(/_/g, '-');
    }

    D.verificationBadge = function (verification) {
        var status = verification && verification.status ? verification.status : 'UNVERIFIED_LOCAL';
        var meta = VERIFICATION_BADGE_META[status] || {
            labelKey: verificationKey(status),
            tone: 'warn',
            icon: 'fa-circle-question'
        };
        return {
            status: status,
            labelKey: meta.labelKey,
            tone: meta.tone,
            icon: meta.icon,
            title: verification ? (verification.trustLabel || verification.publisher || verification.diagnosticCode || null) : null
        };
    };

    // —— 卡片视图模型 ——
    D.cardModel = function (entry) {
        var m = market(entry) || {};
        var author = D.entryAuthor(entry);
        var verification = packageVerification(entry);
        var ratingVal = m.rating != null ? Number(m.rating) : null;
        var dl = PMK.formatDownloads(m.totalDownloadCount);
        var category = D.entryCategory(entry);
        return {
            pluginId: entry.pluginId,
            suspectedProblem: D.suspectedProblem(entry),
            focusReason: D.focusReason(entry),
            artifactMismatch: D.artifactMismatch(entry, D.packageOf(entry, D.defaultVersion(entry))),
            developmentLoaded: D.developmentLoaded(entry),
            installationState: D.installationState(entry, D.packageOf(entry, D.defaultVersion(entry))),
            name: D.entryName(entry),
            publisher: verification && verification.publisher ? verification.publisher : author,
            sub: [entry.pluginId, author].filter(Boolean).join(' · '),
            iconClass: PMK.iconClass(m.iconToken),
            icon: m.icon || null,
            colorClass: PMK.colorClass(m.colorToken),
            category: category,
            categoryLabel: PMK.categoryLabel(category),
            categoryIcon: PMK.iconClass(PMK.CATEGORY_ICON[category] || 'screwdriver-wrench'),
            official: D.entryOfficial(entry),
            assuranceLevel: verification && verification.assuranceLevel || 'UNVERIFIED',
            assuranceLabel: D.assuranceLabel(verification && verification.assuranceLevel),
            recommended: D.entryRecommended(entry),
            ratingStars: ratingVal != null ? PMK.stars(ratingVal) : null,
            ratingNum: ratingVal != null ? ratingVal.toFixed(1) : null,
            downloadsLabel: dl,
            desc: D.entrySummary(entry),
            tags: D.entryTags(entry),
            latestVersion: entry.latestVersion,
            targetVersion: D.defaultVersion(entry),
            versionLabel: D.defaultVersion(entry) ? ('v' + D.defaultVersion(entry)) : null,
            compatibilityNotice: D.compatibilityNotice(entry),
            installationNotice: D.installationNotice(entry, D.packageOf(entry, D.defaultVersion(entry))),
            sizeLabel: PMK.formatSize(latestSize(entry)),
            dateLabel: m.updatedTime ? PMK.formatDate(m.updatedTime) : '',
            installStatus: installStatusWithVerification(entry),
            installedVersion: entry.installedVersion,
            updateAvailable: entry.updateAvailable,
            compatible: entry.compatible,
            compatibilityReason: entry.compatibilityReason,
            verification: verification,
            verificationBadge: D.verificationBadge(verification)
        };
    };

    D.assuranceLabel = function (level) {
        var client = PMK.state.i18n.client;
        return client ? client.t('common:plugin-trust.assurance.' + (level || 'UNVERIFIED'), level || 'UNVERIFIED')
            : level || 'UNVERIFIED';
    };

    function latestSize(entry) {
        var pkg = D.packageOf(entry, D.defaultVersion(entry));
        return pkg ? pkg.expectedSizeBytes : 0;
    }

    function packageVerification(entry) {
        var pkg = D.packageOf(entry, D.defaultVersion(entry));
        return pkg && pkg.verification ? pkg.verification : null;
    }

    function installStatusWithVerification(entry) {
        if (entry.compatibilitySearchIncomplete) return 'UNAVAILABLE';
        var pkg = D.packageOf(entry, D.defaultVersion(entry));
        return D.packageInstallBlock(pkg) || (entry.installStatus === 'NO_RECOMMENDATION'
            ? 'NO_RECOMMENDATION' : D.selectedInstallStatus(entry, pkg, null, false));
    }

    D.selectedInstallStatus = function (entry, pkg, facts, manual) {
        if (PMK.recovery && PMK.recovery.installationBlocked()) return 'RECOVERY_BLOCKED';
        var blocked = D.packageInstallBlock(pkg, facts);
        if (blocked) return blocked;
        if (!pkg) return entry.installStatus;
        if (pkg.compatible === false) return 'INCOMPATIBLE';
        if (pkg.installationMatch === 'SAME_ARTIFACT') return D.suspectedProblem(entry) ? 'REINSTALL' : 'INSTALLED_SAME';
        if (pkg.installationMatch === 'DIFFERENT_ARTIFACT') return 'INSTALL_DIFFERENT';
        if (pkg.installationMatch === 'UNKNOWN') return 'INSTALL_UNVERIFIED';
        if (D.developmentLoaded(entry) && (manual || entry.installStatus === 'NOT_INSTALLED')) return 'INSTALL_DISTRIBUTION';
        return manual ? 'NOT_INSTALLED' : entry.installStatus;
    };

    D.installationNotice = function (entry, pkg) {
        var match = pkg && pkg.installationMatch;
        var notice = ['SAME_ARTIFACT', 'DIFFERENT_ARTIFACT', 'UNKNOWN'].indexOf(match) !== -1
            ? PMK.t('installation.match.' + match) : '';
        if (D.artifactMismatch(entry, pkg)) notice = PMK.t('installation.same-version-mismatch');
        var state = D.installationState(entry, pkg);
        if (state && ['mismatch', 'development', 'stored-development'].indexOf(state) === -1)
            notice += (notice ? ' ' : '') + PMK.t('installation.advice.' + state);
        if (entry.installation && entry.installation.installedArtifactsEnabled === false)
            notice += (notice ? ' ' : '') + PMK.t('installation.development');
        return notice;
    };

    D.localArtifactFields = function (entry) {
        var local = entry.installation || {};
        var unknown = PMK.t('common:plugin-info.unknown');
        var source = ['LOCAL_UPLOAD', 'MARKET_CATALOG'].indexOf(local.source) !== -1
            ? PMK.t('installation.source.' + local.source) : unknown;
        return [
            {label: PMK.t('detail.installed-version'), value: local.version || entry.installedVersion
                || (local.state === 'ABSENT' ? PMK.t('common:plugin-info.not-installed') : unknown)},
            {label: 'SHA-256', value: local.sha256 || unknown},
            {label: PMK.t('installation.source'), value: source + (local.repositoryId ? ' · ' + local.repositoryId : '')},
            {label: PMK.t('installation.runtime-version'), value: local.runtimeVersion || unknown}
        ];
    };

    // 卡片、所选历史版本和新取回的事实共用后端禁用原因，不从签名有效推断未被撤销。
    D.packageInstallBlock = function (pkg, facts) {
        var verification = facts || (pkg && pkg.verification);
        var revocation = verification && verification.revocationStatus;
        if (revocation === 'REVOKED' || revocation === 'YANKED') return revocation;
        if (verification && verification.status && ['VERIFIED_OFFICIAL', 'VERIFIED_CUSTOM', 'VERIFIED_COMMUNITY',
                'UNVERIFIED_LOCAL', 'UNSIGNED_ALLOWED'].indexOf(verification.status) === -1)
            return PMK.INSTALL_META[verification.status] ? verification.status : 'UNAVAILABLE';
        if (pkg && pkg.installable === false || revocation === 'NOT_CHECKED') return 'UNAVAILABLE';
        return null;
    };

    // —— 筛选 + 搜索 + 排序 ——
    function matches(entry, opts) {
        if (opts.category && opts.category !== 'all' && D.entryCategory(entry) !== opts.category) return false;
        var attention = D.focusReason(entry) || D.artifactMismatch(entry, D.packageOf(entry, D.defaultVersion(entry)));
        if (opts.hideDefaultInstalled && D.entryDefaultInstalled(entry) && !attention) return false;
        if (opts.hideDependencies && D.entryDependency(entry) && !attention) return false;
        if (opts.onlyOfficial && !D.entryOfficial(entry)) return false;
        // 后端按推荐版本投影兼容性；查询未完成的条目保留明确提示，不能当作没有兼容版本。
        if (opts.onlyCompatible && entry.compatible === false && !entry.compatibilitySearchIncomplete) return false;
        var q = (opts.search || '').trim().toLowerCase();
        if (q) {
            var hay = [D.entryName(entry), entry.pluginId, D.entrySummary(entry), D.entryDescription(entry),
                D.entryAuthor(entry), D.entryTags(entry).join(' ')].join(' ').toLowerCase();
            if (hay.indexOf(q) === -1) return false;
        }
        return true;
    }

    function comparator(sort) {
        switch (sort) {
            case 'updated': return function (a, b) { return updatedAt(b) - updatedAt(a); };
            case 'downloads': return function (a, b) { return downloads(b) - downloads(a); };
            case 'rating': return function (a, b) { return (rating(b) - rating(a)) || (downloads(b) - downloads(a)); };
            case 'name': return function (a, b) { return D.entryName(a).localeCompare(D.entryName(b)); };
            case 'recommended':
            default:
                return function (a, b) {
                    return (D.entryRecommended(b) ? 1 : 0) - (D.entryRecommended(a) ? 1 : 0)
                        || (downloads(b) - downloads(a));
                };
        }
    }

    // 过滤 + 排序（不改入参；JS sort 稳定）。
    D.filterAndSort = function (entries, opts) {
        var list = (entries || []).filter(function (e) { return matches(e, opts); });
        var compare = comparator(opts.sort || 'recommended');
        list.sort(function (a, b) {
            return Number(!!D.focusReason(b)) - Number(!!D.focusReason(a))
                || Number(D.artifactMismatch(b, D.packageOf(b, D.defaultVersion(b))))
                    - Number(D.artifactMismatch(a, D.packageOf(a, D.defaultVersion(a))))
                || compare(a, b);
        });
        return list;
    };

    // 侧栏分类列表（按 CATEGORY_ORDER + 后端派生计数；后端 categories 已含全部已知分类含 0）。
    D.categoryList = function (catalog) {
        var counts = {};
        ((catalog && catalog.categories) || []).forEach(function (c) { counts[c.category] = c.count; });
        return PMK.CATEGORY_ORDER.map(function (id) {
            return {
                id: id,
                label: PMK.categoryLabel(id),
                icon: PMK.iconClass(PMK.CATEGORY_ICON[id] || 'grip'),
                count: counts[id] != null ? counts[id] : 0
            };
        });
    };

    // —— 安装结果映射（消费 POST install 的 PluginInstallResponse；纯映射、无副作用，任何字符串都不在此拼 HTML）——
    function installTone(outcome, accepted, recoveryBlocked) {
        if (recoveryBlocked) return 'bad';
        if (!accepted) return 'bad';
        return outcome === 'DUPLICATE' ? 'info' : 'ok';
    }

    function dependencyProblemLabel(problem) {
        var reason = problem && problem.reason ? String(problem.reason).toLowerCase().replace(/_/g, '-') : 'unknown';
        return PMK.t('install.dependency.' + reason, '{id}', {
            id: problem && problem.pluginId ? problem.pluginId : '',
            required: problem && problem.versionSupport ? problem.versionSupport : '*',
            installed: problem && problem.installedVersion ? problem.installedVersion : '-',
            status: problem && problem.status ? problem.status : '-'
        });
    }

    function dependencyWarnings(response) {
        if (Array.isArray(response.dependencyProblems) && response.dependencyProblems.length) {
            return response.dependencyProblems.map(dependencyProblemLabel);
        }
        return Array.isArray(response.unsatisfiedDependencies) ? response.unsatisfiedDependencies : [];
    }

    function dependencyInstallResults(response) {
        if (!Array.isArray(response.dependencyInstallResults)) {
            return [];
        }
        return response.dependencyInstallResults.map(function (dependency) {
            return D.installResult(dependency);
        }).filter(function (result) {
            return !!(result && result.pluginId);
        });
    }

    D.dependencyInstallResults = dependencyInstallResults;

    D.installResult = function (response) {
        var r = response || {};
        var outcome = r.outcome || null;
        var accepted = r.accepted === true;
        var recoveryBlocked = r.recoveryBlocked === true;
        return {
            outcome: outcome,
            accepted: accepted,
            recoveryBlocked: recoveryBlocked,
            effectiveAfterRestart: r.effectiveAfterRestart === true,
            activationBlockedByDevelopmentMode: r.activationBlockedByDevelopmentMode === true,
            activated: r.activated === true,
            rolledBack: r.rolledBack === true,
            rollbackVersion: r.rollbackVersion || null,
            transactionId: r.transactionId || null,
            tone: installTone(outcome, accepted, recoveryBlocked),
            message: r.message || outcome || null,
            pluginId: r.pluginId || null,
            version: r.version || null,
            previousVersion: r.previousVersion || null,
            packageId: r.packageId || r.pluginId || null,
            targetVersion: r.targetVersion || r.version || null,
            operation: r.operation || null,
            runtimePhase: r.runtimePhase || null,
            updated: r.updated === true,
            errors: Array.isArray(r.diagnostics) ? r.diagnostics : [],
            warnings: dependencyWarnings(r),
            dependencyInstallResults: dependencyInstallResults(r)
        };
    };

    // Vue 主路径与命令式回退共享同一安装终态优先级，避免任一路径把待恢复事务投影成绿色成功。
    D.installResultStatus = function (result, fallbackStatus) {
        var r = result || {};
        if (r.recoveryBlocked) return 'RECOVERY_BLOCKED';
        // 当前限制优先于此前安装回执；回执仍保留在结果区供查看。
        if (fallbackStatus && ['NOT_INSTALLED', 'INSTALL_DISTRIBUTION', 'UPDATE_AVAILABLE', 'INSTALLED', 'INSTALLED_SAME', 'REINSTALL', 'NO_RECOMMENDATION'].indexOf(fallbackStatus) === -1)
            return fallbackStatus;
        if (r.accepted && r.activationBlockedByDevelopmentMode) return 'STORED_DEVELOPMENT';
        if (r.activated) return 'ACTIVATED';
        if (r.accepted && r.effectiveAfterRestart) return 'PENDING_RESTART';
        return fallbackStatus;
    };

    // toast 同样由两条渲染路径共用；恢复阻断直接保留后端本地化 message，并优先于 activated / accepted。
    D.installFeedback = function (result) {
        var r = result || {};
        if (r.outcome === 'CANCELLED') return {message: PMK.t('install.preview.cancelled'), tone: 'info'};
        if (r.recoveryBlocked) {
            return {
                message: r.message || PMK.t('install.toast.recovery-blocked', '安装事务需要在重启后恢复。'),
                tone: 'error'
            };
        }
        if (r.activated) {
            return { message: PMK.t('install.toast.activated', '已安装并激活。'), tone: 'ok' };
        }
        if (r.accepted && r.activationBlockedByDevelopmentMode) {
            return {message: PMK.t('installation.development'), tone: 'info'};
        }
        if (r.rolledBack) {
            return { message: PMK.t('install.toast.rolled-back', '激活失败，已恢复原版本。'), tone: 'error' };
        }
        if (r.accepted) {
            return { message: PMK.t('install.toast.accepted', '已安装。'), tone: 'ok' };
        }
        return {
            message: PMK.t('install.toast.rejected', '未安装：{message}', { message: r.message || r.outcome || '' }),
            tone: 'error'
        };
    };

    // 后端 catalog 错误响应（{code, message, ...}）→ 结果区可渲染的本地化提示（按稳定 code 选 i18n 文案，回退后端 message）。
    D.catalogError = function (body, httpStatus) {
        var code = body && body.code ? body.code : null;
        var message = code ? PMK.t('error.code.' + code, (body && (body.message || body.error)) || code)
            : ((body && (body.message || body.error)) || PMK.t('error.install.generic', '安装请求失败，请重试。'));
        return {
            outcome: code, accepted: false, recoveryBlocked: false, effectiveAfterRestart: false,
            activated: false, rolledBack: false, tone: code === 'CANCELLED' ? 'info' : 'bad',
            message: message, pluginId: body && body.pluginId, version: body && body.version,
            previousVersion: null, packageId: null, targetVersion: null, operation: null,
            runtimePhase: null, updated: false, errors: [], warnings: [],
            dependencyInstallResults: dependencyInstallResults(body || {}),
            httpStatus: httpStatus || null
        };
    };
})(window);
