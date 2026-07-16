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

    D.entryName = function (entry) {
        var m = market(entry);
        return PMK.localeText(m && m.displayName, entry.pluginId) || entry.pluginId;
    };
    D.entryAuthor = function (entry) {
        var m = market(entry);
        return m && m.author ? m.author : '';
    };
    D.entrySummary = function (entry) {
        var m = market(entry);
        return m ? PMK.localeText(m.summary, '') : '';
    };
    D.entryDescription = function (entry) {
        var m = market(entry);
        if (!m) return '';
        return PMK.localeText(m.description, '') || PMK.localeText(m.summary, '');
    };
    D.entryCategory = function (entry) {
        var m = market(entry);
        return (m && m.category) || 'utility';
    };
    D.entryOfficial = function (entry) {
        var m = market(entry);
        return !!(m && m.sourceType === 'official');
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

    var VERIFICATION_BADGE_META = {
        VERIFIED_OFFICIAL: { labelKey: 'verification.verified-official', tone: 'ok', icon: 'fa-circle-check' },
        VERIFIED_CUSTOM: { labelKey: 'verification.verified-custom', tone: 'ok', icon: 'fa-circle-check' },
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
        var ratingVal = m.rating != null ? Number(m.rating) : null;
        var dl = PMK.formatDownloads(m.totalDownloadCount);
        var category = D.entryCategory(entry);
        return {
            pluginId: entry.pluginId,
            name: D.entryName(entry),
            sub: [entry.pluginId, author].filter(Boolean).join(' · '),
            iconClass: PMK.iconClass(m.iconToken),
            colorClass: PMK.colorClass(m.colorToken),
            category: category,
            categoryLabel: PMK.categoryLabel(category),
            categoryIcon: PMK.iconClass(PMK.CATEGORY_ICON[category] || 'screwdriver-wrench'),
            official: D.entryOfficial(entry),
            recommended: D.entryRecommended(entry),
            ratingStars: ratingVal != null ? PMK.stars(ratingVal) : null,
            ratingNum: ratingVal != null ? ratingVal.toFixed(1) : null,
            downloadsLabel: dl,
            desc: D.entrySummary(entry),
            tags: D.entryTags(entry),
            latestVersion: entry.latestVersion,
            versionLabel: entry.latestVersion ? ('v' + entry.latestVersion) : null,
            sizeLabel: PMK.formatSize(latestSize(entry)),
            dateLabel: m.updatedTime ? PMK.formatDate(m.updatedTime) : '',
            installStatus: installStatusWithVerification(entry),
            installedVersion: entry.installedVersion,
            updateAvailable: entry.updateAvailable,
            compatible: entry.compatible,
            compatibilityReason: entry.compatibilityReason,
            verification: packageVerification(entry),
            verificationBadge: D.verificationBadge(packageVerification(entry))
        };
    };

    function latestSize(entry) {
        var pkg = D.packageOf(entry, entry.latestVersion);
        return pkg ? pkg.expectedSizeBytes : 0;
    }

    function packageVerification(entry) {
        var pkg = D.packageOf(entry, entry.latestVersion);
        return pkg && pkg.verification ? pkg.verification : null;
    }

    function installStatusWithVerification(entry) {
        var verification = packageVerification(entry);
        if (!verification || !verification.status) return entry.installStatus;
        if (verification.status === 'VERIFIED_OFFICIAL' || verification.status === 'VERIFIED_CUSTOM') {
            return entry.installStatus;
        }
        if (verification.status === 'SIGNATURE_REQUIRED'
                || verification.status === 'UNKNOWN_KEY'
                || verification.status === 'REVOKED_KEY'
                || verification.status === 'INVALID_SIGNATURE'
                || verification.status === 'HASH_MISMATCH') {
            return verification.status;
        }
        return entry.installStatus;
    }

    // —— 筛选 + 搜索 + 排序 ——
    function matches(entry, opts) {
        if (opts.category && opts.category !== 'all' && D.entryCategory(entry) !== opts.category) return false;
        if (opts.hideDefaultInstalled && D.entryDefaultInstalled(entry)) return false;
        if (opts.hideDependencies && D.entryDependency(entry)) return false;
        if (opts.onlyOfficial && !D.entryOfficial(entry)) return false;
        // 「仅兼容当前版本」按条目自身的兼容标记（= 最新可安装版本是否被当前核心 API 满足）判定，而非派生的
        // installStatus —— 已安装但最新版本不兼容的条目（installStatus=INSTALLED）也应被该筛选排除。
        if (opts.onlyCompatible && entry.compatible === false) return false;
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
        list.sort(comparator(opts.sort || 'recommended'));
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
    function installTone(outcome, accepted) {
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
        return {
            outcome: outcome,
            accepted: accepted,
            effectiveAfterRestart: r.effectiveAfterRestart === true,
            activated: r.activated === true,
            rolledBack: r.rolledBack === true,
            rollbackVersion: r.rollbackVersion || null,
            transactionId: r.transactionId || null,
            tone: installTone(outcome, accepted),
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

    // 后端 catalog 错误响应（{code, message, ...}）→ 结果区可渲染的本地化提示（按稳定 code 选 i18n 文案，回退后端 message）。
    D.catalogError = function (body, httpStatus) {
        var code = body && body.code ? body.code : null;
        var message = code ? PMK.t('error.code.' + code, (body && body.message) || code)
            : ((body && body.message) || PMK.t('error.install.generic', '安装请求失败，请重试。'));
        return {
            outcome: code, accepted: false, effectiveAfterRestart: false, activated: false, rolledBack: false, tone: 'bad',
            message: message, pluginId: body && body.pluginId, version: body && body.version,
            previousVersion: null, packageId: null, targetVersion: null, operation: null,
            runtimePhase: null, updated: false, errors: [], warnings: [],
            dependencyInstallResults: dependencyInstallResults(body || {}),
            httpStatus: httpStatus || null
        };
    };
})(window);
