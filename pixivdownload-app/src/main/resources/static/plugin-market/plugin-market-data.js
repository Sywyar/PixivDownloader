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
            installStatus: entry.installStatus,
            installedVersion: entry.installedVersion,
            updateAvailable: entry.updateAvailable,
            compatible: entry.compatible,
            compatibilityReason: entry.compatibilityReason
        };
    };

    function latestSize(entry) {
        var pkg = D.packageOf(entry, entry.latestVersion);
        return pkg ? pkg.expectedSizeBytes : 0;
    }

    // —— 筛选 + 搜索 + 排序 ——
    function matches(entry, opts) {
        if (opts.category && opts.category !== 'all' && D.entryCategory(entry) !== opts.category) return false;
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
            message: r.message || null,
            pluginId: r.pluginId || null,
            version: r.version || null,
            previousVersion: r.previousVersion || null,
            packageId: r.packageId || r.pluginId || null,
            targetVersion: r.targetVersion || r.version || null,
            operation: r.operation || null,
            runtimePhase: r.runtimePhase || null,
            updated: r.updated === true,
            errors: Array.isArray(r.diagnostics) ? r.diagnostics : [],
            warnings: Array.isArray(r.unsatisfiedDependencies) ? r.unsatisfiedDependencies : []
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
            runtimePhase: null, updated: false, errors: [], warnings: [], httpStatus: httpStatus || null
        };
    };
})(window);
