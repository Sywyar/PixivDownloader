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
    var state = {
        loading: true, error: null, masterEnabled: false, coreApiVersion: '',
        repositories: [], activeRepositoryId: null, defaultRepositoryId: null,
        catalog: null, catalogError: null, category: 'all', search: '',
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
        var r = state.installResults[key];
        if (r && r.activated) return 'ACTIVATED';
        if (r && r.accepted && r.effectiveAfterRestart) return 'PENDING_RESTART';
        return card.installStatus;
    }

    function installControl(card) {
        var status = cardStatus(card);
        if (status === 'INSTALLING') {
            return '<div class="pmk-install-progress"><div class="pmk-install-progress-label">' +
                '<i class="fa-solid fa-spinner fa-spin"></i>' + esc(t('install.state.installing', '安装中…')) +
                '</div><div class="pmk-progressbar"><span></span></div></div>';
        }
        var meta = PMK.installMeta(status);
        var label = status === 'UPDATE_AVAILABLE'
            ? t('install.action.update-to', '更新到 v{v}', { v: card.latestVersion })
            : t(meta.labelKey, meta.status);
        var attrs = meta.disabled ? ' disabled'
            : ' data-pmk-repo="' + esc(card.repositoryId || '') + '" data-pmk-install="' + esc(card.pluginId) +
              '" data-pmk-version="' + esc(card.latestVersion || '') + '"';
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
            : '<span class="pmk-badge pmk-badge--community">' + esc(t('badge.community', '社区')) + '</span>';
        if (card.recommended) badges += '<span class="pmk-badge pmk-badge--recommended">' + esc(t('badge.recommended', '推荐')) + '</span>';
        badges += verificationBadgeHtml(card.verificationBadge);
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
              esc(t('compat.needs', '需要核心 API v{v}+（当前 v{cur}）', { v: card.compatibilityReason, cur: state.coreApiVersion })) + '</div>'
            : '';
        return '<article class="pmk-card ' + esc(card.colorClass) + '"><div class="pmk-card-body">' +
            '<div class="pmk-card-head"><span class="pmk-card-icon"><i class="' + esc(card.iconClass) + '"></i></span>' +
            '<div class="pmk-card-titleblock"><div class="pmk-card-name-row">' +
            '<span class="pmk-card-name">' + esc(card.name) + '</span>' + badges + '</div>' +
            '<div class="pmk-card-sub">' + esc(card.sub) + '</div></div></div>' +
            rating +
            (card.desc ? '<p class="pmk-card-desc">' + esc(card.desc) + '</p>' : '') +
            tags +
            (meta ? '<div class="pmk-card-meta">' + esc(meta) + '</div>' : '') +
            compat +
            '<div class="pmk-card-actions">' + installControl(card) + '</div>' +
            '</div></article>';
    }

    function gridHtml() {
        var repoId = state.catalog.repositoryId;
        var cards = PMK.data.filterAndSort(state.catalog.entries, {
            category: state.category, search: state.search, sort: 'recommended'
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
            '<span class="pmk-repo-chip-name">' + esc(repo.repositoryId) + '</span>' +
            (metaText ? '<span class="pmk-repo-chip-meta">' + esc(metaText) + '</span>' : '') + '</button>';
    }

    function categoryChips() {
        return PMK.data.categoryList(state.catalog).map(function (cat) {
            return '<button class="pmk-repo-chip' + (cat.id === state.category ? ' active' : '') + '" data-pmk-cat="' + esc(cat.id) + '">' +
                '<i class="' + esc(cat.icon) + '"></i><span class="pmk-repo-chip-name">' + esc(cat.label) + '</span>' +
                '<span class="pmk-repo-chip-meta">' + cat.count + '</span></button>';
        }).join('');
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
            '<button class="pmk-btn pmk-btn--teal" data-pmk-refresh><i class="fa-solid fa-rotate"></i><span>' + esc(t('refresh', '刷新')) + '</span></button>' +
            '</div></div>';

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
        body += '<div class="pmk-banner pmk-banner--warn pmk-security-notice">' +
            '<i class="fa-solid fa-shield-halved"></i><div class="pmk-banner-body">' +
            esc(t('security.notice', '安全提示：无法验证、未签名或由用户放行的插件会在本机进程内运行代码。安装前请自行确认来源与安全性；我们无法保证未验证插件的安全。')) +
            '</div></div>';
        if (state.masterEnabled && state.catalogError) {
            body += '<div class="pmk-banner pmk-banner--error"><i class="fa-solid fa-triangle-exclamation"></i><div class="pmk-banner-body">' +
                '<div class="pmk-banner-title">' + esc(t('error.catalog.title', '无法加载插件清单')) + '</div><div>' + esc(state.catalogError) + '</div></div></div>';
        }
        if (state.masterEnabled && state.catalog) {
            body += '<div class="pmk-repos">' + categoryChips() + '</div>' +
                '<div class="pmk-toolbar"><div class="pmk-toolbar-head"><span class="pmk-toolbar-title">' +
                esc(PMK.categoryLabel(state.category)) + '</span></div>' +
                '<div class="pmk-search"><i class="fa-solid fa-magnifying-glass"></i>' +
                '<input type="text" id="pmk-fb-search" value="' + esc(state.search) + '" placeholder="' + esc(t('search.placeholder', '搜索插件、作者或标签…')) + '" autocomplete="off"></div></div>' +
                '<div id="pmk-fb-grid">' + gridHtml() + '</div>';
        }
        return head + degraded + body + '<div class="pmk-disclaimer">' + esc(t('disclaimer', '插件运行于本地，仅供个人学习与研究使用；无法验证、未签名或用户放行的插件请自行确认来源与安全性，我们无法保证未验证插件的安全；请尊重创作者版权 · 本工具与 Pixiv 无任何关联')) + '</div>';
    }

    function paint() {
        if (rootEl) rootEl.innerHTML = shellHtml();
    }

    function updateGrid() {
        var grid = document.getElementById('pmk-fb-grid');
        if (grid && state.catalog) grid.innerHTML = gridHtml();
        var title = rootEl && rootEl.querySelector('.pmk-toolbar-title');
        if (title) title.textContent = PMK.categoryLabel(state.category);
        // 分类 chip 高亮同步。
        var chips = rootEl ? rootEl.querySelectorAll('[data-pmk-cat]') : [];
        for (var i = 0; i < chips.length; i++) {
            chips[i].classList.toggle('active', chips[i].getAttribute('data-pmk-cat') === state.category);
        }
    }

    function loadCatalog(repoId) {
        var token = ++state.catalogToken;
        state.catalogError = null;
        return PMK.api.fetchCatalog(repoId).then(function (cat) {
            if (token !== state.catalogToken) return;   // 仓库已切换，丢弃旧仓库的 catalog 响应
            state.catalog = cat;
        }).catch(function () {
            if (token !== state.catalogToken) return;
            state.catalog = null;
            state.catalogError = t('error.catalog', '无法加载该仓库的插件清单，请检查仓库状态或稍后重试。');
        });
    }

    function load() {
        state.loading = true; state.error = null; state.catalogError = null;
        state.catalogToken++;   // 让在途的旧 catalog 拉取失效（其回调将被 token 守卫丢弃）
        paint();
        PMK.api.fetchRepositories().then(function (repos) {
            state.masterEnabled = !!repos.enabled;
            state.coreApiVersion = repos.coreApiVersion || '';
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

    // 安装请求只用展示条目同源的 repositoryId（来自卡片 data-pmk-repo），不读易变的全局 activeRepositoryId；
    // 在途与结果按 (repositoryId, pluginId) 复合键存储——切到其它仓库时本仓库的安装态不会污染同名插件。
    function doInstall(repositoryId, pluginId, version) {
        if (!repositoryId || !pluginId || !version) return;
        var key = installKey(repositoryId, pluginId);
        if (state.installing[key]) return;
        state.installing[key] = true;
        delete state.installResults[key];
        updateGrid();
        PMK.api.installPlugin(repositoryId, pluginId, version).then(function (res) {
            var model = res.kind === 'install'
                ? PMK.data.installResult(res.body)
                : PMK.data.catalogError(res.body, res.httpStatus);
            state.installResults[key] = model;
            PMK.toast(model.activated
                ? t('install.toast.activated', '已安装并激活。')
                : (model.rolledBack
                    ? t('install.toast.rolled-back', '激活失败，已恢复原版本。')
                    : t('install.toast.rejected', '未安装：{message}', { message: model.message || model.outcome || '' })),
                model.activated ? 'ok' : 'error');
        }).catch(function () {
            state.installResults[key] = { accepted: false };
            PMK.toast(t('error.install.generic', '安装请求失败，请重试。'), 'error');
        }).then(function () {
            delete state.installing[key];
            updateGrid();
        });
    }

    function wire() {
        rootEl.addEventListener('click', function (e) {
            var repo = e.target.closest('[data-pmk-repo]');
            if (repo) {
                var id = repo.getAttribute('data-pmk-repo');
                if (id !== state.activeRepositoryId) {
                    state.activeRepositoryId = id; state.category = 'all'; state.search = '';
                    loadCatalog(id).then(paint);
                }
                return;
            }
            var cat = e.target.closest('[data-pmk-cat]');
            if (cat) { state.category = cat.getAttribute('data-pmk-cat'); updateGrid(); return; }
            var install = e.target.closest('[data-pmk-install]');
            if (install && !install.disabled) {
                doInstall(install.getAttribute('data-pmk-repo'), install.getAttribute('data-pmk-install'),
                    install.getAttribute('data-pmk-version'));
                return;
            }
            if (e.target.closest('[data-pmk-refresh]')) { load(); }
        });
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
        PMK.state.activeView = { reload: load, rerender: paint };
        wire();
        load();
    };
})(window);
