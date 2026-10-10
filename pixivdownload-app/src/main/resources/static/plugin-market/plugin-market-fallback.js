'use strict';
/*
 * 插件市场页命令式回退渲染器：仅在 Vue 运行时缺失 / 加载失败 / 挂载抛错时由 init 启用（主路径是 plugin-market-vue.js
 * 的 reactive 渲染）。本回退提供「可诊断降级」——顶部明确提示已降级，但仍能浏览受信仓库、按分类 / 关键字筛选并安装插件，
 * 不让页面崩溃或不可用。全部文本经 escapeHtml 后才进 innerHTML，图标 / 颜色取自 core 的受控 token 白名单。
 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var FB = PMK.fallback = {};

    var rootEl = null;
    var detailView = null;
    var state = {
        loading: true, error: null, masterEnabled: false, recoveryMode: false, recoveryReasons: [], hostElevated: false,
        filtersInitialized: false, sdkVersion: '',
        repositories: [], activeRepositoryId: null, defaultRepositoryId: null,
        catalog: null, catalogError: null, loadingMore: false, category: 'all', search: '',
        hideDefaultInstalled: true, hideDependencies: true,
        // 异步竞态护栏：每次 catalog 拉取自增 token，回调只在仍最新时落地（仓库快速切换丢弃旧响应）。
        catalogToken: 0,
        installing: {}, installResults: {}
    };

    var esc = PMK.escapeHtml;
    function t(k, f, v) { return PMK.t(k, f, v); }

    // 安装态键控：(repositoryId, pluginId) 复合键，使同名插件在不同仓库间互不污染。
    function installKey(repositoryId, pluginId) {
        return String(repositoryId) + ' ' + String(pluginId);
    }

    function cardStatus(card) {
        var key = installKey(card.repositoryId, card.pluginId);
        if (state.installing[key]) return 'INSTALLING';
        var result = state.installResults[key];
        return PMK.data.installResultStatus(result && result.version === card.targetVersion ? result : null, card.installStatus);
    }

    function installControl(card) {
        var status = cardStatus(card);
        if (status === 'INSTALLING') {
            var phase = state.installing[installKey(card.repositoryId, card.pluginId)];
            return '<div class="pmk-install-progress" role="status"><div class="pmk-install-progress-label">' +
                '<i class="fa-solid ' + (phase === 'confirm' || phase === 'trust' ? 'fa-clock' : 'fa-spinner fa-spin') + '"></i>' + esc(t(PMK.installPhaseKey(phase))) +
                '</div></div>';
        }
        var meta = PMK.installMeta(status);
        var label = status === 'UPDATE_AVAILABLE'
            ? t('install.action.update-to', '更新到 v{v}', { v: card.targetVersion })
            : t(meta.labelKey, meta.status);
        var attrs = meta.disabled ? ' disabled'
            : ' data-pmk-repo="' + esc(card.repositoryId || '') + '" data-pmk-install="' + esc(card.pluginId) +
              '" data-pmk-version="' + esc(card.targetVersion || '') + '"';
        return '<button class="pmk-btn pmk-install pmk-btn--' + meta.variant + '"' + attrs + '>' +
            '<i class="fa-solid fa-' + esc(meta.icon) + '"></i><span>' + esc(label) + '</span></button>';
    }

    function verificationBadgeHtml(badge) {
        if (!badge) return '';
        var title = badge.title ? ' title="' + esc(badge.title) + '"' : '';
        return '<span class="pmk-verification-badge pmk-verification-badge--' + esc(badge.tone) + '"' + title + '>' +
            '<i class="fa-solid ' + esc(badge.icon) + '"></i><span>' +
            esc(t(badge.labelKey, badge.status || badge.labelKey)) + '</span></span>';
    }

    function cardHtml(card) {
        var badges = card.official
            ? '<span class="pmk-badge pmk-badge--official">' + esc(t('badge.official', '官方')) + '</span>'
            : '<span class="pmk-badge pmk-badge--community">' + esc(card.assuranceLabel) + '</span>';
        if (card.recommended) badges += '<span class="pmk-badge pmk-badge--recommended">' + esc(t('badge.recommended', '推荐')) + '</span>';
        badges += verificationBadgeHtml(card.verificationBadge);
        if (card.focusReason) badges += '<span class="pmk-badge pmk-badge--suspected">' + esc(t('recovery.focus.' + card.focusReason)) + '</span>';
        if (card.installationState) badges += '<span class="pmk-badge ' + (card.artifactMismatch ? 'pmk-badge--mismatch' : 'pmk-badge--state') + '">' + esc(t('installation.state.' + card.installationState)) + '</span>';
        var rating = '';
        if (card.ratingNum || card.downloadsLabel) {
            rating = '<div class="pmk-rating">' +
                (card.ratingNum ? '<span class="pmk-rating-num">★ ' + esc(card.ratingNum) + '</span>' : '') +
                (card.downloadsLabel ? '<span class="pmk-rating-dl"><i class="fa-solid fa-download"></i>' + esc(card.downloadsLabel) + '</span>' : '') +
                '</div>';
        }
        var tags = card.tags.length
            ? '<div class="pmk-tags">' + card.tags.slice(0, 4).map(function (tag) { return '<span class="pmk-tag">#' + esc(tag) + '</span>'; }).join('') + '</div>'
            : '';
        var meta = [card.versionLabel, card.sizeLabel, card.dateLabel].filter(Boolean).join(' · ');
        var compat = (!card.compatible && card.compatibilityReason)
            ? '<div class="pmk-card-compat"><i class="fa-solid fa-triangle-exclamation"></i>' +
              esc(t('compat.needs', '需要SDK v{v}+（当前 v{cur}）', { v: card.compatibilityReason, cur: state.sdkVersion })) + '</div>'
            : '';
        return '<article class="pmk-card ' + esc(card.colorClass) + (card.focusReason ? ' pmk-card--suspected' : '') + (card.artifactMismatch ? ' pmk-card--mismatch' : '') + '"><div class="pmk-card-body">' +
            '<div class="pmk-card-head"><span class="pmk-card-icon"><i class="' + esc(card.iconClass) + '"></i>' +
            (PMK.api.contentImageUrl(card.repositoryId, card.pluginId, card.icon, 'icon', 0)
                ? '<img class="pmk-market-image" alt="" loading="lazy" src="' + esc(PMK.api.contentImageUrl(card.repositoryId, card.pluginId, card.icon, 'icon', 0)) + '">' : '') + '</span>' +
            '<div class="pmk-card-titleblock"><div class="pmk-card-name-row">' +
            '<span class="pmk-card-name">' + esc(card.name) + '</span>' + badges + '</div>' +
            '<div class="pmk-card-sub">' + esc(card.sub) + '</div></div></div>' +
            rating +
            (card.desc ? '<p class="pmk-card-desc">' + esc(card.desc) + '</p>' : '') +
            tags +
            '<div class="pmk-card-footer">' +
            (meta ? '<div class="pmk-card-meta">' + esc(meta) + '</div>' : '') +
            compat +
            (card.compatibilityNotice ? '<p class="pmk-card-compat pmk-card-compat--notice">' + esc(card.compatibilityNotice) + '</p>' : '') +
            (card.installationNotice ? '<p class="pmk-card-compat pmk-card-compat--notice">' + esc(card.installationNotice) + '</p>' : '') +
            '<div class="pmk-card-actions"><div class="pmk-install-slot">' + installControl(card) + '</div>' +
            '<button type="button" class="pmk-btn pmk-btn--gray pmk-btn--sm" data-pmk-detail="' + esc(card.pluginId)
                + '" data-pmk-version="' + esc(card.targetVersion) + '" aria-haspopup="dialog">'
                + esc(t('card.detail', '详情')) + '</button></div>' +
            '</div></div></article>';
    }

    function gridHtml() {
        var repoId = state.catalog.repositoryId;
        var cards = PMK.data.filterAndSort(state.catalog.entries, {
            category: state.category, search: state.search,
            hideDefaultInstalled: state.hideDefaultInstalled, hideDependencies: state.hideDependencies,
            sort: 'recommended'
        }).map(function (entry) {
            var card = PMK.data.cardModel(entry);
            card.repositoryId = repoId;   // 卡片绑定其同源仓库（安装请求与安装态键控同一来源）
            return card;
        });
        if (!cards.length) {
            return '<div class="pmk-empty"><i class="fa-solid fa-store-slash"></i>' +
                '<div class="pmk-empty-title">' + esc(t('empty.title', '没有匹配的插件')) + '</div>' +
                '<div class="pmk-empty-hint">' + esc(t('empty.hint', '试试切换分类、关闭筛选，或更换搜索关键词。')) + '</div></div>';
        }
        return '<div class="pmk-grid">' + cards.map(cardHtml).join('') + '</div>';
    }

    function repoChip(repo) {
        var active = repo.repositoryId === state.activeRepositoryId;
        var metaText = active ? t('repo.active', '当前') : (!repo.enabled ? t('repo.disabled', '已禁用') : '');
        var attrs = (!repo.enabled || active) ? ' disabled' : ' data-pmk-repo="' + esc(repo.repositoryId) + '"';
        return '<button class="pmk-repo-chip' + (active ? ' active' : '') + '"' + attrs + '>' +
            '<i class="fa-solid ' + (repo.official ? 'fa-circle-check' : 'fa-folder') + '"></i>' +
            '<span class="pmk-repo-chip-name">' + esc(repo.displayName || repo.repositoryId) + '</span>' +
            (metaText ? '<span class="pmk-repo-chip-meta">' + esc(metaText) + '</span>' : '') + '</button>';
    }

    function categoryChips() {
        return PMK.data.categoryList(state.catalog).map(function (cat) {
            return '<button class="pmk-repo-chip' + (cat.id === state.category ? ' active' : '') + '" data-pmk-cat="' + esc(cat.id) + '">' +
                '<i class="' + esc(cat.icon) + '"></i><span class="pmk-repo-chip-name">' + esc(cat.label) + '</span>' +
                '<span class="pmk-repo-chip-meta">' + cat.count + '</span></button>';
        }).join('');
    }

    function filterChip(id, enabled, label, icon) {
        return '<button class="pmk-repo-chip' + (enabled ? ' active' : '') + '" data-pmk-filter="' + esc(id) +
            '" aria-pressed="' + (enabled ? 'true' : 'false') + '">' +
            '<i class="fa-solid ' + esc(icon) + '"></i><span class="pmk-repo-chip-name">' + esc(label) + '</span></button>';
    }

    function filterChips() {
        return '<div class="pmk-repos"><span class="pmk-repos-label">' + esc(t('sidebar.filter', '筛选')) + '</span>' +
            filterChip('hideDefaultInstalled', state.hideDefaultInstalled,
                t('filter.hide-default-installed', '隐藏默认安装插件'), 'fa-box-archive') +
            filterChip('hideDependencies', state.hideDependencies,
                t('filter.hide-dependencies', '隐藏依赖插件'), 'fa-layer-group') + '</div>';
    }

    function shellHtml() {
        var head =
            '<div class="pmk-titlebar"><div>' +
            '<h1 class="pmk-title"><i class="fa-solid fa-store"></i><span>' + esc(t('page.heading', '插件市场')) + '</span></h1>' +
            '<p class="pmk-subtitle">' + esc(t('page.subtitle', '从受信仓库浏览并安装插件')) + '</p></div>' +
            '<div class="pmk-titlebar-actions"><div class="pmk-seg">' +
            '<span class="pmk-seg-item active"><i class="fa-solid fa-store"></i><span>' + esc(t('seg.market', '市场')) + '</span></span>' +
            '<a class="pmk-seg-item" href="/plugin-manage.html"><i class="fa-solid fa-puzzle-piece"></i><span>' + esc(t('seg.installed', '已安装')) + '</span>' +
            '<span class="pmk-seg-count">' + (state.catalog ? state.catalog.installedCount : 0) + '</span></a></div>' +
            '<span class="pmk-operations-trigger" data-pmk-operations-trigger></span>' +
            '<button class="pmk-btn pmk-btn--teal" data-pmk-refresh><i class="fa-solid fa-rotate"></i><span>' + esc(t('refresh', '刷新')) + '</span></button>' +
            '</div></div><div data-pmk-operations-panel hidden></div>';

        // 降级诊断条（明确告知已回退为基础视图）。
        var degraded = '<div class="pmk-banner pmk-banner--info"><i class="fa-solid fa-circle-info"></i>' +
            '<div class="pmk-banner-body">' + esc(t('fallback.notice', '增强界面未能加载，已切换为基础视图；浏览与安装仍可正常使用。')) + '</div></div>';

        if (state.loading) {
            return head + degraded + '<div class="pmk-state"><i class="fa-solid fa-spinner fa-spin"></i><span>' + esc(t('loading', '正在加载…')) + '</span></div>';
        }
        if (state.error) {
            return head + degraded + '<div class="pmk-banner pmk-banner--error"><i class="fa-solid fa-triangle-exclamation"></i><div class="pmk-banner-body">' + esc(state.error) + '</div></div>';
        }

        var body = '';
        if (!state.masterEnabled) {
            body += '<div class="pmk-banner pmk-banner--warn"><i class="fa-solid fa-circle-exclamation"></i><div class="pmk-banner-body">' +
                '<div class="pmk-banner-title">' + esc(t('master.disabled.title', '插件市场未开启')) + '</div>' +
                '<div>' + esc(t('master.disabled.desc', '请在配置中开启受信 catalog 后再浏览仓库与安装插件。')) + '</div></div></div>';
        }
        if (state.repositories.length) {
            body += '<div class="pmk-repos"><span class="pmk-repos-label">' + esc(t('section.repositories', '受信仓库')) + '</span>' +
                state.repositories.map(repoChip).join('') + '</div>';
        }
        if (state.hostElevated) {
            body += '<div class="pmk-banner pmk-banner--warn"><i class="fa-solid fa-triangle-exclamation"></i>' +
                '<div class="pmk-banner-body">' + esc(t('host.elevated.notice',
                    '宿主正在以高权限运行；所有宿主进程完全信任插件都会继承当前高权限。')) + '</div></div>';
        }
        body += '<div class="pmk-banner pmk-banner--warn pmk-security-notice">' +
            '<i class="fa-solid fa-shield-halved"></i><div class="pmk-banner-body">' +
            esc(t('security.notice', '插件执行与签名说明：宿主进程完全信任插件与主程序运行在同一 JVM；声明式插件进入使用同一系统账号的有限隔离 worker。签名只证明来源与内容完整性，不代表安全审查；请仅安装你信任的插件。')) +
            '</div></div>';
        if (state.masterEnabled && state.catalogError) {
            body += '<div class="pmk-banner pmk-banner--error"><i class="fa-solid fa-triangle-exclamation"></i><div class="pmk-banner-body">' +
                '<div class="pmk-banner-title">' + esc(t('error.catalog.title', '无法加载插件清单')) + '</div><div>' + esc(state.catalogError) + '</div></div></div>';
        }
        if (state.masterEnabled && state.catalog) {
            if (state.catalog.focusIncomplete) body += '<p class="pmk-banner pmk-banner--warn">' + esc(t('recovery.focus-incomplete')) + '</p>';
            body += '<div class="pmk-repos">' + categoryChips() + '</div>' +
                filterChips() +
                '<div class="pmk-toolbar"><div class="pmk-toolbar-head"><div class="pmk-toolbar-title-row"><span class="pmk-toolbar-title">' +
                esc(PMK.categoryLabel(state.category)) + '</span></div><p class="pmk-toolbar-description">' +
                esc(PMK.categoryDescription(state.category)) + '</p></div>' +
                '<div class="pmk-search"><i class="fa-solid fa-magnifying-glass"></i>' +
                '<input type="text" id="pmk-fb-search" value="' + esc(state.search) + '" placeholder="' + esc(t('search.placeholder', '搜索插件、作者或标签…')) + '" autocomplete="off"></div></div>' +
                '<div id="pmk-fb-grid">' + gridHtml() + '</div>';
            if (state.catalog.nextCursor) body += '<div class="pmk-load-more"><button class="pmk-btn pmk-btn--gray" data-pmk-more' +
                (state.loadingMore ? ' disabled' : '') + '>' +
                esc(t('pagination.more', '加载更多')) + '</button></div>';
        }
        return head + degraded + body + '<div class="pmk-disclaimer">' + esc(t('disclaimer', '插件运行于本地，仅供个人学习与研究使用；无法验证、未签名或用户放行的插件请自行确认来源与安全性，我们无法保证未验证插件的安全；请尊重创作者版权 · 本工具与 Pixiv 无任何关联')) + '</div>';
    }

    function paint() {
        if (!rootEl) return;
        var focused = document.activeElement;
        var restoreFocus = focused && focused.closest && focused.closest('.pmk-operations, .pmk-operations-trigger');
        rootEl.innerHTML = '<div class="pmk-page">' + shellHtml() + '</div>';
        if (PMK.operations) {
            PMK.operations.mountButton(rootEl.querySelector('[data-pmk-operations-trigger]'));
            PMK.operations.mountPanel(rootEl.querySelector('[data-pmk-operations-panel]'));
            PMK.operations.render();
            if (restoreFocus && focused.isConnected) focused.focus({preventScroll: true});
        }
    }

    function updateGrid() {
        var grid = document.getElementById('pmk-fb-grid');
        if (grid && state.catalog) grid.innerHTML = gridHtml();
        var title = rootEl && rootEl.querySelector('.pmk-toolbar-title');
        if (title) title.textContent = PMK.categoryLabel(state.category);
        var description = rootEl && rootEl.querySelector('.pmk-toolbar-description');
        if (description) description.textContent = PMK.categoryDescription(state.category);
        // 分类 chip 高亮同步。
        var chips = rootEl ? rootEl.querySelectorAll('[data-pmk-cat]') : [];
        for (var i = 0; i < chips.length; i++) {
            chips[i].classList.toggle('active', chips[i].getAttribute('data-pmk-cat') === state.category);
        }
    }

    function loadCatalog(repoId, preserveDetail) {
        if (detailView && !preserveDetail) detailView.close();
        var token = ++state.catalogToken;
        state.loadingMore = false;
        state.catalogError = null;
        return PMK.api.fetchCatalog(repoId).then(function (cat) {
            if (token !== state.catalogToken) return;   // 仓库已切换，丢弃旧仓库的 catalog 响应
            state.catalog = cat;
        }).catch(function (failure) {
            if (token !== state.catalogToken) return;
            if (failure.name === 'AbortError') return;
            state.catalog = null;
            state.catalogError = t('error.catalog', '无法加载该仓库的插件清单，请检查仓库状态或稍后重试。');
        });
    }

    function load() {
        state.loading = true; state.error = null; state.catalogError = null;
        state.catalogToken++;   // 让在途的旧 catalog 拉取失效（其回调将被 token 守卫丢弃）
        if (PMK.api.cancelCatalog) PMK.api.cancelCatalog();
        paint();
        Promise.all([PMK.api.fetchRepositories(), PMK.api.fetchPluginStatus()]).then(function (responses) {
            var repos = responses[0];
            var status = responses[1];
            state.masterEnabled = !!repos.enabled;
            state.recoveryMode = !!status.recoveryMode;
            state.recoveryReasons = PMK.recoveryReasons(status);
            state.hostElevated = !!status.hostElevated;
            PMK.state.hostElevated = state.hostElevated;
            if (!state.filtersInitialized) {
                state.hideDefaultInstalled = !state.recoveryMode;
                state.filtersInitialized = true;
            }
            state.sdkVersion = repos.sdkVersion || '';
            state.repositories = repos.repositories || [];
            state.defaultRepositoryId = repos.defaultRepositoryId || null;
            var valid = state.repositories.some(function (r) { return r.repositoryId === state.activeRepositoryId && r.enabled; });
            if (!valid) state.activeRepositoryId = repos.defaultRepositoryId || null;
            state.loading = false;
            if (state.masterEnabled && state.activeRepositoryId) {
                return loadCatalog(state.activeRepositoryId).then(paint);
            }
            state.catalog = null;
            paint();
        }).catch(function () {
            state.error = t('error.load', '加载插件市场失败，请稍后重试。');
            state.loading = false;
            paint();
        });
    }

    function recordDependencyInstallResults(repositoryId, model) {
        (model.dependencyInstallResults || []).forEach(function (dependencyResult) {
            if (!dependencyResult.pluginId) return;
            state.installResults[installKey(repositoryId, dependencyResult.pluginId)] = dependencyResult;
        });
    }

    function refreshCatalogAfterInstall(repositoryId) {
        updateGrid();
        if (state.masterEnabled && repositoryId && repositoryId === state.activeRepositoryId) {
            return loadCatalog(repositoryId, true).then(paint);
        }
        return Promise.resolve();
    }

    // 安装请求只用展示条目同源的 repositoryId（来自卡片 data-pmk-repo），不读易变的全局 activeRepositoryId；
    // 在途与结果按 (repositoryId, pluginId) 复合键存储——切到其它仓库时本仓库的安装态不会污染同名插件。
    function doInstall(repositoryId, pluginId, version) {
        if (!repositoryId || !pluginId || !version) return;
        var key = installKey(repositoryId, pluginId);
        if (state.installing[key]) return;
        var trigger = document.activeElement;
        var restoreFocus = trigger && trigger.hasAttribute && trigger.hasAttribute('data-pmk-install');
        state.installing[key] = 'preview';
        delete state.installResults[key];
        updateGrid();
        return PMK.installPluginWithConfirmation(repositoryId, pluginId, version, function (phase) { state.installing[key] = phase; updateGrid(); }, state.catalog && state.catalog.entries).then(function (res) {
            var model = res.kind === 'install'
                ? PMK.data.installResult(res.body)
                : PMK.data.catalogError(res.body, res.httpStatus);
            state.installResults[key] = model;
            recordDependencyInstallResults(repositoryId, model);
            var feedback = PMK.data.installFeedback(model);
            PMK.toast(feedback.message, feedback.tone);
        }).catch(function () {
            state.installResults[key] = {
                tone: 'bad', accepted: false, recoveryBlocked: false,
                effectiveAfterRestart: false, outcome: null,
                message: t('error.install.generic', '安装请求失败，请重试。'), warnings: [], errors: [],
                dependencyInstallResults: []
            };
            PMK.toast(t('error.install.generic', '安装请求失败，请重试。'), 'error');
        }).then(function () {
            delete state.installing[key];
            return refreshCatalogAfterInstall(repositoryId).then(function () {
                if (!restoreFocus || document.activeElement !== document.body || state.activeRepositoryId !== repositoryId) return;
                var button = Array.from(rootEl.querySelectorAll('[data-pmk-install]')).find(function (item) {
                    return item.getAttribute('data-pmk-install') === pluginId;
                });
                var target = button && (button.disabled ? button.closest('.pmk-card').querySelector('[data-pmk-detail]') : button);
                if (target) target.focus({preventScroll: true});
            });
        });
    }

    function openContentDetail(pluginId, opener) {
        if (detailView) detailView.close();
        var catalog = state.catalog;
        if (!catalog) return;
        var entry = catalog.entries.find(function (item) { return item.pluginId === pluginId; });
        if (!entry) return;
        var dialog = document.createElement('dialog');
        dialog.className = 'pmk-content-dialog';
        dialog.innerHTML = '<div class="pmk-content-dialog-head"><h2></h2><button type="button" class="pmk-btn pmk-btn--gray">'
            + esc(t('modal.close', '关闭')) + '</button></div><div class="pmk-content-dialog-body"><p class="pmk-section-text"></p>'
            + '<label><span>' + esc(t('detail.version', '版本')) + '</span> <select class="pmk-version-select"></select></label>'
            + '<p class="pmk-installation-notice"></p><button type="button" class="pmk-btn pmk-btn--primary pmk-detail-install"></button>'
            + '<dl class="pmk-local-artifact"></dl><p class="pmk-market-facts"></p>'
            + '<button type="button" class="pmk-btn pmk-btn--gray pmk-more-versions"></button>'
            + '<div class="pmk-content"></div><div class="pmk-version-notes"></div>'
            + '<button type="button" class="pmk-btn pmk-btn--gray" data-pmk-facts="' + esc(pluginId) + '" aria-haspopup="dialog"></button></div>';
        var content = PMK.content.mount(dialog.querySelector('.pmk-content'));
        var select = dialog.querySelector('select');
        var version = PMK.data.defaultVersion(entry);
        var alive = true;
        var detailToken = 0;
        var loadingMore = false;
        var more = dialog.querySelector('.pmk-more-versions');
        var install = dialog.querySelector('.pmk-detail-install');
        var installing = false;
        function refreshDetail(preserveVersions) {
            var token = ++detailToken;
            loadingMore = false;
            function current() {
                return alive && token === detailToken && state.activeRepositoryId === catalog.repositoryId;
            }
            return PMK.api.refreshPluginDetail(catalog.repositoryId, pluginId, preserveVersions ? entry : null, current)
                .then(function (fresh) {
                    if (!current() || !fresh) return;
                    Object.assign(entry, fresh); update();
                }).catch(function () { if (current()) PMK.toast(t('error.detail'), 'error'); });
        }
        function update() {
            version = PMK.data.resolveVersion(entry, version);
            dialog.querySelector('h2').textContent = PMK.data.entryName(entry);
            dialog.querySelector('p').textContent = PMK.data.entryDescription(entry);
            dialog.querySelector('button').textContent = t('modal.close', '关闭');
            dialog.querySelector('label span').textContent = t('detail.version', '版本');
            more.textContent = t('pagination.more-versions', '加载更多版本');
            more.hidden = !entry.nextVersionCursor; more.disabled = loadingMore;
            select.replaceChildren();
            (entry.packages || []).forEach(function (pkg) {
                var option = document.createElement('option'); option.value = pkg.version; option.textContent = pkg.version;
                select.appendChild(option);
            });
            select.value = version;
            var facts = dialog.querySelector('[data-pmk-facts]');
            facts.textContent = t('trust.facts');
            facts.setAttribute('data-pmk-version', version);
            var pkg = PMK.data.packageOf(entry, version);
            var result = state.installResults[installKey(catalog.repositoryId, pluginId)];
            var status = PMK.data.installResultStatus(result && result.version === version ? result : null,
                PMK.data.selectedInstallStatus(entry, pkg, null, true));
            var meta = PMK.installMeta(status);
            install.disabled = installing || !!state.installing[installKey(catalog.repositoryId, pluginId)] || meta.disabled;
            install.textContent = t(installing ? 'install.state.installing' : meta.labelKey);
            select.disabled = installing;
            dialog.querySelector('.pmk-installation-notice').textContent = PMK.data.installationNotice(entry, pkg);
            dialog.querySelector('.pmk-market-facts').textContent = t('installation.market-facts');
            dialog.querySelector('.pmk-local-artifact').innerHTML = '<dt>' + esc(t('installation.local-package')) + '</dt>'
                + PMK.data.localArtifactFields(entry).map(function (field) {
                    return '<dt>' + esc(field.label) + '</dt><dd>' + esc(field.value) + '</dd>';
                }).join('');
            var model = PMK.content.model(catalog.repositoryId, entry, pkg);
            content.update(model);
            dialog.querySelector('.pmk-version-notes').textContent = model.documents.some(function (doc) { return doc.kind === 'releaseNotes'; })
                ? '' : ((pkg && pkg.changeNotes) || []).join('\n');
        }
        install.addEventListener('click', function () {
            if (installing || install.disabled) return;
            installing = true; update();
            doInstall(catalog.repositoryId, pluginId, version).then(function () {
                if (alive) return refreshDetail(true);
            }).catch(function () {
                if (alive) PMK.toast(t('error.detail'), 'error');
            }).finally(function () { installing = false; if (alive) update(); });
        });
        more.addEventListener('click', function () {
            if (loadingMore || !entry.nextVersionCursor) return;
            var token = detailToken;
            var cursor = entry.nextVersionCursor;
            var generation = entry.versionsGeneration;
            loadingMore = true; update();
            PMK.api.fetchPluginDetail(catalog.repositoryId, pluginId, { cursor: cursor }).then(function (page) {
                if (!alive || token !== detailToken || state.activeRepositoryId !== catalog.repositoryId
                        || entry.versionsGeneration !== generation || entry.nextVersionCursor !== cursor) return;
                if (page.versionsGeneration !== generation) {
                    return refreshDetail();
                }
                else {
                    entry.packages = (entry.packages || []).concat((page.packages || []).filter(function (pkg) {
                        return !(entry.packages || []).some(function (old) { return old.version === pkg.version; });
                    }));
                    entry.nextVersionCursor = page.nextVersionCursor;
                }
            }).catch(function () { if (alive && token === detailToken) PMK.toast(t('error.detail'), 'error'); })
                .finally(function () { if (alive && token === detailToken) { loadingMore = false; update(); } });
        });
        select.addEventListener('change', function () { version = select.value; update(); });
        dialog.addEventListener('click', handleClick);
        dialog.querySelector('button').addEventListener('click', function () { dialog.close(); });
        dialog.addEventListener('close', function () {
            alive = false; content.dispose(); dialog.remove();
            if (detailView === owner) {
                detailView = null;
                var target = opener.isConnected ? opener : rootEl.querySelector('[data-pmk-refresh]');
                if (target) target.focus();
            }
        }, { once: true });
        var owner = detailView = { close: function () { dialog.close(); }, update: update,
            updateFacts: function (repositoryId, id, selectedVersion, data) {
                if (repositoryId !== catalog.repositoryId || id !== pluginId) return;
                var pkg = PMK.data.packageOf(entry, selectedVersion);
                if (pkg) pkg.verification = data;
                update();
            }
        };
        document.body.appendChild(dialog); update(); dialog.showModal();
        refreshDetail();
    }

    function handleClick(e) {
            var detail = e.target.closest('[data-pmk-detail]');
            if (detail) { openContentDetail(detail.getAttribute('data-pmk-detail'), detail); return; }
            var facts = e.target.closest('[data-pmk-facts]');
            if (facts) {
                if (facts.disabled) return;
                facts.disabled = true;
                var catalog = state.catalog;
                var pluginId = facts.getAttribute('data-pmk-facts');
                var version = facts.getAttribute('data-pmk-version');
                PMK.api.fetchPackageFacts(catalog.repositoryId, pluginId, version).then(function (data) {
                    if (state.catalog !== catalog || !facts.isConnected || facts.getAttribute('data-pmk-version') !== version) return;
                    var entry = catalog.entries.find(function (item) { return item.pluginId === pluginId; });
                    var pkg = entry && PMK.data.packageOf(entry, version);
                    if (pkg) {
                        pkg.verification = data;
                        var card = PMK.data.cardModel(entry);
                        card.repositoryId = catalog.repositoryId;
                        var cardElement = facts.closest('.pmk-card');
                        if (cardElement) cardElement.querySelector('.pmk-install-slot').innerHTML = installControl(card);
                        else updateGrid();
                        if (detailView) detailView.updateFacts(catalog.repositoryId, pluginId, version, data);
                    }
                    if (!entry) return;
                    facts.disabled = false;
                    facts.focus({preventScroll: true});
                    return global.PixivFeedback.alert({
                        title: PMK.data.entryName(entry), message: PMK.data.entryDescription(entry),
                        confirmLabel: t('common:button.close'),
                        sections: [{title: t('installation.local-package'), fields: PMK.data.localArtifactFields(entry)},
                            {title: t('installation.market-facts'), fields: [
                                {label: t('detail.version'), value: version},
                                {label: 'SHA-256', value: pkg && pkg.sha256 || t('common:plugin-info.unknown')},
                                {label: t('installation.source'), value: catalog.repositoryId}
                            ]}, {title: t('common:plugin-info.dependencies'), fields: [
                            {label: t('common:plugin-info.sdk'), value: pkg && pkg.requiredSdk || t('common:plugin-info.unknown')},
                            {label: t('common:plugin-info.dependency-plugins'), value: (pkg && pkg.dependencies || []).map(function (dep) {
                                return PMK.data.dependencyLabel(dep, catalog.entries);
                            }).join('\n') || t('common:plugin-info.no-dependencies')}
                        ]}].concat(global.PixivPluginPresentationTokens.trustSections(data, PMK.state.i18n.client))
                    });
                }).catch(function () {
                    if (state.catalog === catalog && facts.isConnected && facts.getAttribute('data-pmk-version') === version)
                        PMK.toast(t('error.detail', '无法加载插件详情，请稍后重试。'), 'error');
                }).finally(function () { facts.disabled = false; });
                return;
            }
            var repo = e.target.closest('[data-pmk-repo]');
            if (repo && !repo.hasAttribute('data-pmk-install')) {
                var id = repo.getAttribute('data-pmk-repo');
                if (id !== state.activeRepositoryId) {
                    state.activeRepositoryId = id; state.category = 'all'; state.search = '';
                    loadCatalog(id).then(paint);
                }
                return;
            }
            var cat = e.target.closest('[data-pmk-cat]');
            if (cat) { state.category = cat.getAttribute('data-pmk-cat'); updateGrid(); return; }
            var filter = e.target.closest('[data-pmk-filter]');
            if (filter) {
                var filterId = filter.getAttribute('data-pmk-filter');
                if (filterId === 'hideDefaultInstalled' || filterId === 'hideDependencies') {
                    state[filterId] = !state[filterId];
                    paint();
                }
                return;
            }
            var install = e.target.closest('[data-pmk-install]');
            if (install && !install.disabled) {
                doInstall(install.getAttribute('data-pmk-repo'), install.getAttribute('data-pmk-install'),
                    install.getAttribute('data-pmk-version'));
                return;
            }
            if (e.target.closest('[data-pmk-refresh]')) { load(); }
            if (e.target.closest('[data-pmk-more]') && state.catalog && state.catalog.nextCursor && !state.loadingMore) {
                var generation = state.catalog.generation;
                var token = state.catalogToken;
                state.loadingMore = true; paint();
                PMK.api.fetchCatalog(state.activeRepositoryId, { cursor: state.catalog.nextCursor }).then(function (page) {
                    if (token !== state.catalogToken) return;
                    if (!state.catalog || page.generation !== generation) return loadCatalog(state.activeRepositoryId).then(paint);
                    state.catalog.entries = PMK.data.mergeEntries(state.catalog.entries, page.entries);
                    state.catalog.nextCursor = page.nextCursor; paint();
                }).catch(function (failure) {
                    if (token !== state.catalogToken || failure.name === 'AbortError') return;
                    PMK.toast(t('error.catalog', '无法加载该仓库的插件清单，请检查仓库状态或稍后重试。'), 'error');
                }).then(function () {
                    if (token !== state.catalogToken) return;
                    state.loadingMore = false; paint();
                });
            }
    }

    function wire() {
        rootEl.addEventListener('error', function (event) {
            if (event.target.classList && event.target.classList.contains('pmk-market-image')) event.target.hidden = true;
        }, true);
        rootEl.addEventListener('click', handleClick);
        rootEl.addEventListener('input', function (e) {
            if (e.target && e.target.id === 'pmk-fb-search') {
                state.search = e.target.value || '';
                updateGrid();
            }
        });
    }

    // 启用命令式回退渲染（init 在 Vue 不可用时调用）。
    FB.render = function (el) {
        rootEl = el;
        PMK.state.activeView = { reload: load, rerender: function () { paint(); if (detailView) detailView.update(); } };
        wire();
        load();
    };
})(window);
