'use strict';
/*
 * 插件市场页 Vue reactive 视图（主渲染路径）：用 window.PixivVue 懒加载核心 Vue 运行时，把整个市场页内容
 * （标题区 + 分段控件 + 受信仓库行 + 分类侧栏 + 筛选 / 搜索 / 排序 + 卡片网格 + 详情弹窗 + 安装状态）渲染为
 * 数据驱动的 reactive 组件。Vue 缺失 / 加载失败 / 挂载抛错时 tryMount 收敛为返回 false，由 init 回退命令式渲染。
 *
 * 安全：使用原生 VNode 文本子节点与 class（取自 core 的受控 token 白名单），不内联任意 HTML；
 * 主页外链已由后端净化为 http/https，再以 href 绑定 + rel="noopener"。安装只按受控 repositoryId+pluginId+version
 * 发起，绝不传任意 URL；安装结果一律以后端响应为准（前端只投影「安装中 / 已激活 / 待重启 / 恢复阻断」状态）。
 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var VUE = PMK.vue = {};

    // 直接构造 VNode，避免模板运行时编译触发 CSP 禁止的动态代码执行。
    function renderMarket(Vue, vm) {
        var h = Vue.h;
        var t = vm.t;
        function icon(cls) { return h('i', { class: cls }); }
        function stars(rating) {
            if (!rating) return null;
            return h('span', { class: 'pmk-stars' }, [
                Array.from({ length: rating.full }, function () { return icon('fa-solid fa-star'); }),
                Array.from({ length: rating.half }, function () { return icon('fa-solid fa-star-half-stroke'); }),
                Array.from({ length: rating.empty }, function () { return icon('fa-regular fa-star'); })
            ]);
        }
        function progress(modal) {
            return h('div', { class: 'pmk-install-progress', style: modal ? { minWidth: '200px' } : null }, [
                h('div', { class: 'pmk-install-progress-label' }, [icon('fa-solid fa-spinner fa-spin'), t('install.state.installing', '安装中…')]),
                h('div', { class: 'pmk-progressbar' }, [h('span')])
            ]);
        }
        function loading() {
            return h('div', { class: 'pmk-state' }, [icon('fa-solid fa-spinner fa-spin'), h('span', t('loading', '正在加载…'))]);
        }
        function filter(field, cls, key, fallback) {
            var label = t(key, fallback);
            return h('div', { class: 'pmk-filter' }, [
                h('span', { class: 'pmk-filter-label' }, [icon(cls), label]),
                h('button', { type: 'button', class: ['pmk-switch', { on: vm[field] }],
                    'aria-pressed': vm[field], 'aria-label': label,
                    onClick: function () { vm[field] = !vm[field]; } })
            ]);
        }
        function cardView(card) {
            var meta = vm.cardMeta(card);
            var badge = card.verificationBadge;
            function open() { vm.openDetail(card.pluginId); }
            return h('article', { key: card.pluginId, class: ['pmk-card', card.colorClass] }, [
                h('div', { class: 'pmk-card-banner', onClick: open }, [
                    icon(['pmk-card-banner-glyph', card.iconClass]), icon(['pmk-card-banner-bg', card.iconClass]),
                    h('span', { class: 'pmk-card-banner-cat' }, [icon(card.categoryIcon), card.categoryLabel])
                ]),
                h('div', { class: 'pmk-card-body' }, [
                    h('div', { class: 'pmk-card-head' }, [
                        h('span', { class: 'pmk-card-icon' }, [icon(card.iconClass)]),
                        h('div', { class: 'pmk-card-titleblock' }, [
                            h('div', { class: 'pmk-card-name-row' }, [
                                h('span', { class: 'pmk-card-name', onClick: open }, card.name),
                                h('span', { class: 'pmk-badge pmk-badge--' + (card.official ? 'official' : 'community') },
                                    card.official ? t('badge.official', '官方') : t('badge.publisher-signed', '发布者签名')),
                                card.recommended ? h('span', { class: 'pmk-badge pmk-badge--recommended' }, t('badge.recommended', '推荐')) : null,
                                vm.showCardVerification(card) ? h('span', { class: ['pmk-verification-badge', 'pmk-verification-badge--' + badge.tone], title: badge.title || null },
                                    [icon(['fa-solid', badge.icon]), h('span', t(badge.labelKey, badge.status))]) : null
                            ]),
                            h('div', { class: 'pmk-card-sub' }, card.sub)
                        ])
                    ]),
                    vm.showCardRating(card) ? h('div', { class: 'pmk-rating' }, [
                        stars(card.ratingStars),
                        card.ratingNum ? h('span', { class: 'pmk-rating-num' }, card.ratingNum) : null,
                        card.downloadsLabel ? h('span', { class: 'pmk-rating-dl' }, [icon('fa-solid fa-download'), card.downloadsLabel]) : null
                    ]) : null,
                    card.desc ? h('p', { class: 'pmk-card-desc' }, card.desc) : null,
                    card.tags.length ? h('div', { class: 'pmk-tags' }, card.tags.slice(0, 4).map(function (tag) {
                        return h('span', { key: tag, class: 'pmk-tag' }, '#' + tag);
                    })) : null,
                    vm.showCardMeta(card) ? h('div', { class: 'pmk-card-meta' }, [card.versionLabel, card.sizeLabel, card.dateLabel].filter(Boolean).join(' · ')) : null,
                    vm.showCardCompat(card) ? h('div', { class: 'pmk-card-compat' }, [icon('fa-solid fa-triangle-exclamation'),
                        t('compat.needs', '需要SDK v{v}+（当前 v{cur}）', { v: card.compatibilityReason, cur: vm.sdkVersion })]) : null,
                    h('div', { class: 'pmk-card-actions' }, [
                        vm.cardStatus(card) === 'INSTALLING' ? progress(false) : h('button', {
                            class: ['pmk-btn pmk-install', 'pmk-btn--' + meta.variant], disabled: meta.disabled,
                            onClick: function () { vm.install(card); }
                        }, [icon('fa-solid fa-' + meta.icon), h('span', vm.cardLabel(card))]),
                        h('button', { class: 'pmk-btn pmk-btn--gray pmk-btn--sm', onClick: open },
                            [icon('fa-solid fa-circle-info'), h('span', t('card.detail', '详情'))])
                    ])
                ])
            ]);
        }
        function body() {
            return h('div', { class: 'pmk-body' }, [
                h('aside', { class: 'pmk-sidebar' }, [
                    h('div', { class: 'pmk-side-card' }, [
                        h('div', { class: 'pmk-side-label' }, t('sidebar.browse', '浏览分类')),
                        h('div', { class: 'pmk-cat-list' }, vm.categoryList.map(function (cat) {
                            return h('button', { key: cat.id, class: ['pmk-cat', { active: cat.id === vm.category }], onClick: function () { vm.setCategory(cat.id); } },
                                [icon(cat.icon), h('span', { class: 'pmk-cat-name' }, cat.label), h('span', { class: 'pmk-cat-count' }, String(cat.count))]);
                        })),
                        h('div', { class: 'pmk-side-divider' }),
                        h('div', { class: 'pmk-side-label' }, t('sidebar.filter', '筛选')),
                        filter('hideDefaultInstalled', 'fa-solid fa-box-archive pmk-fi-default', 'filter.hide-default-installed', '隐藏默认安装插件'),
                        filter('hideDependencies', 'fa-solid fa-layer-group pmk-fi-dependency', 'filter.hide-dependencies', '隐藏依赖插件'),
                        filter('onlyOfficial', 'fa-solid fa-circle-check pmk-fi-official', 'filter.official', '仅官方插件'),
                        filter('onlyCompatible', 'fa-solid fa-plug-circle-check pmk-fi-compat', 'filter.compatible', '仅兼容当前版本')
                    ]),
                    h('div', { class: 'pmk-version-card' }, [
                        h('div', { class: 'pmk-version-label' }, t('sidebar.sdk', 'SDK 版本')),
                        h('div', { class: 'pmk-version-num' }, 'v' + vm.sdkVersion),
                        h('div', { class: 'pmk-version-hint' }, t('sidebar.sdk.hint', '标记为「不兼容」的插件需要更新应用后才能安装。'))
                    ])
                ]),
                h('div', { class: 'pmk-main' }, [
                    h('div', { class: 'pmk-toolbar' }, [
                        h('div', { class: 'pmk-toolbar-head' }, [
                            h('div', { class: 'pmk-toolbar-title-row' }, [
                                h('span', { class: 'pmk-toolbar-title' }, vm.categoryLabel),
                                h('span', { class: 'pmk-toolbar-count' }, t('toolbar.count', '{n} 款插件', { n: vm.cards.length }))
                            ]),
                            h('p', { class: 'pmk-toolbar-description' }, vm.categoryDescription)
                        ]),
                        h('div', { class: 'pmk-search' }, [icon('fa-solid fa-magnifying-glass'),
                            Vue.withDirectives(h('input', { type: 'text', placeholder: t('search.placeholder', '搜索插件、作者或标签…'), autocomplete: 'off',
                                'onUpdate:modelValue': function (value) { vm.search = value; } }), [[Vue.vModelText, vm.search]])
                        ]),
                        h('span', { class: 'pmk-sort-label' }, t('sort.label', '排序')),
                        Vue.withDirectives(h('select', { class: 'pmk-sort', 'onUpdate:modelValue': function (value) { vm.sort = value; } },
                            vm.sortOptions.map(function (opt) { return h('option', { key: opt, value: opt }, t('sort.' + opt, opt)); })), [[Vue.vModelSelect, vm.sort]])
                    ]),
                    vm.cards.length ? h('div', { class: 'pmk-grid' }, vm.cards.map(cardView)) : null,
                    vm.catalog && vm.catalog.nextCursor ? h('div', { class: 'pmk-load-more' }, [
                        h('button', { class: 'pmk-btn pmk-btn--gray', disabled: vm.loadingMore, onClick: vm.loadMore },
                            [icon('fa-solid ' + (vm.loadingMore ? 'fa-spinner fa-spin' : 'fa-chevron-down')), h('span', t('pagination.more', '加载更多'))])
                    ]) : null,
                    !vm.cards.length ? h('div', { class: 'pmk-empty' }, [
                        icon('fa-solid fa-store-slash'),
                        h('div', { class: 'pmk-empty-title' }, t('empty.title', '没有匹配的插件')),
                        h('div', { class: 'pmk-empty-hint' }, t('empty.hint', '试试切换分类、关闭筛选，或更换搜索关键词。'))
                    ]) : null
                ])
            ]);
        }
        function modal() {
            var detail = vm.detail;
            if (!detail) return null;
            var result = vm.installResultFor;
            var badge = detail.verificationBadge;
            return h('div', { class: 'pmk-modal', onClick: Vue.withModifiers(vm.closeDetail, ['self']) }, [
                h('div', { class: ['pmk-modal-panel', detail.colorClass] }, [
                    h('div', { class: 'pmk-hero' }, [
                        icon(['pmk-hero-bg', detail.iconClass]),
                        h('span', { class: 'pmk-hero-cat' }, [icon(detail.categoryIcon), detail.categoryLabel]),
                        h('button', { class: 'pmk-hero-close', 'aria-label': t('modal.close', '关闭'), onClick: vm.closeDetail }, [icon('fa-solid fa-xmark')]),
                        h('span', { class: 'pmk-hero-icon' }, [icon(detail.iconClass)]),
                        h('div', { class: 'pmk-hero-titleblock' }, [
                            h('div', { class: 'pmk-hero-name' }, [h('span', detail.name), h('span', { class: 'pmk-hero-pill' },
                                detail.official ? t('badge.official', '官方') : t('badge.publisher-signed', '发布者签名'))]),
                            h('div', { class: 'pmk-hero-sub' }, detail.sub)
                        ])
                    ]),
                    h('div', { class: 'pmk-modal-actionbar' }, [
                        h('div', { class: 'pmk-modal-actionbar-stats' }, [
                            stars(detail.ratingStars), detail.ratingNum ? h('span', { class: 'pmk-rating-num' }, detail.ratingNum) : null,
                            detail.downloadsLabel ? h('span', [icon('fa-solid fa-download'), ' ' + detail.downloadsLabel]) : null
                        ]),
                        h('div', { class: 'pmk-modal-actionbar-right' }, [
                            vm.showVersionSelect ? Vue.withDirectives(h('select', { class: 'pmk-version-select',
                                'onUpdate:modelValue': function (value) { vm.selectedVersion = value; } }, detail.versions.map(function (v) {
                                return h('option', { key: v.version, value: v.version }, 'v' + v.version + (v.channel && v.channel !== 'stable' ? ' · ' + v.channel : ''));
                            })), [[Vue.vModelSelect, vm.selectedVersion]]) : null,
                            vm.modalStatus === 'INSTALLING' ? progress(true) : h('button', {
                                class: ['pmk-btn', 'pmk-btn--' + vm.modalMeta.variant], disabled: vm.modalMeta.disabled, onClick: vm.installModal
                            }, [icon('fa-solid fa-' + vm.modalMeta.icon), h('span', vm.modalLabel)])
                        ])
                    ]),
                    h('div', { class: 'pmk-modal-body' }, [
                        h('div', { class: 'pmk-modal-col' }, [
                            h('div', [h('div', { class: 'pmk-section-label' }, t('detail.about', '简介')),
                                h('div', { class: 'pmk-section-text' }, detail.description || t('detail.no-description', '该插件暂无简介。'))]),
                            result ? h('div', [h('div', { class: 'pmk-section-label' }, t('detail.install-result', '安装结果')),
                                h('div', { class: 'pmk-install-result' }, [h('div', { class: ['pmk-install-result-box', 'pmk-install-result-box--' + result.tone] }, [
                                    h('div', { class: 'pmk-install-result-head' }, [icon(['fa-solid', vm.installResultIcon(result)]),
                                        h('span', { class: 'pmk-install-result-msg' }, result.message),
                                        result.outcome ? h('span', { class: 'pmk-install-code' }, result.outcome) : null]),
                                    vm.showRestartHint ? h('div', { class: 'pmk-install-restart' }, [icon('fa-solid fa-power-off'),
                                        h('span', t('install.restart-hint', '需重启应用后生效。')),
                                        h('a', { href: '/plugin-manage.html' }, t('install.goto-manage', '前往插件管理'))]) : null,
                                    result.warnings.length ? h('div', { class: 'pmk-install-list' }, [h('span', t('install.unmet-deps', '尚未满足的依赖：')),
                                        h('ul', result.warnings.map(function (w) { return h('li', { key: w }, w); }))]) : null
                                ])])]) : null,
                            h('div', [
                                h('div', { class: 'pmk-section-label' }, t('detail.changelog', '更新日志')),
                                detail.versions.length ? h('div', { class: 'pmk-versions' }, detail.versions.map(function (v) {
                                    return h('div', { key: v.version, class: 'pmk-version-row' }, [
                                        h('div', { class: 'pmk-version-col' }, [h('span', { class: 'pmk-version-tag' }, 'v' + v.version),
                                            v.dateLabel ? h('div', { class: 'pmk-version-date' }, v.dateLabel) : null]),
                                        v.notes.length ? h('ul', { class: 'pmk-version-notes' }, v.notes.map(function (note, i) { return h('li', { key: i }, note); }))
                                            : h('div', { class: 'pmk-version-notes pmk-version-empty' }, t('detail.no-notes', '无更新说明。'))
                                    ]);
                                })) : h('div', { class: 'pmk-version-empty' }, t('detail.no-versions', '暂无版本信息。')),
                                detail.nextVersionCursor ? h('button', { class: 'pmk-btn pmk-btn--gray pmk-btn--sm', onClick: vm.loadMoreVersions, disabled: vm.detailLoadingMore }, t('pagination.more-versions', '加载更多版本')) : null
                            ]),
                            detail.dependencies.length ? h('div', [h('div', { class: 'pmk-section-label' }, t('detail.dependencies', '依赖')),
                                h('div', { class: 'pmk-deps' }, detail.dependencies.map(function (dep) { return h('span', { key: dep, class: 'pmk-dep' }, dep); }))]) : null
                        ]),
                        h('div', { class: 'pmk-modal-col' }, [
                            vm.showDetailVerification ? h('div', { class: ['pmk-detail-verification', 'pmk-detail-verification--' + badge.tone], title: badge.title || null }, [
                                icon(['fa-solid', badge.icon]), h('div', [h('div', { class: 'pmk-detail-verification-title' }, t('detail.verification', '来源验证')),
                                    h('div', { class: 'pmk-detail-verification-text' }, t(badge.labelKey, badge.status))])
                            ]) : null,
                            h('div', { class: 'pmk-info-panel' }, detail.infoRows.map(function (row) {
                                return h('div', { key: row.key, class: 'pmk-info-row' }, [h('span', { class: 'pmk-info-key' }, t(row.key, row.key)),
                                    h('span', { class: ['pmk-info-val', { 'pmk-info-val--mono': row.mono, 'pmk-info-val--danger': row.danger }], title: row.title || null },
                                        row.href ? [h('a', { href: row.href, target: '_blank', rel: 'noopener noreferrer' }, row.val)] : row.val)]);
                            })),
                            detail.tags.length ? h('div', [h('div', { class: 'pmk-section-label' }, t('detail.tags', '标签')),
                                h('div', { class: 'pmk-tags' }, detail.tags.map(function (tag) { return h('span', { key: tag, class: 'pmk-tag' }, '#' + tag); }))]) : null
                        ])
                    ])
                ])
            ]);
        }
        return h('div', { class: 'pmk-page' }, [
            h('div', { class: 'pmk-titlebar' }, [
                h('div', [h('h1', { class: 'pmk-title' }, [icon('fa-solid fa-store'), h('span', t('page.heading', '插件市场'))]),
                    h('p', { class: 'pmk-subtitle' }, t('page.subtitle', '从受信仓库浏览并安装插件'))]),
                h('div', { class: 'pmk-titlebar-actions' }, [
                    h('div', { class: 'pmk-seg', role: 'tablist' }, [
                        h('span', { class: 'pmk-seg-item active' }, [icon('fa-solid fa-store'), h('span', t('seg.market', '市场'))]),
                        h('a', { class: 'pmk-seg-item', href: '/plugin-manage.html' }, [icon('fa-solid fa-puzzle-piece'),
                            h('span', t('seg.installed', '已安装')), h('span', { class: 'pmk-seg-count' }, String(vm.installedCount))])
                    ]),
                    h('button', { class: 'pmk-btn pmk-btn--teal', onClick: vm.reload, disabled: vm.loading }, [icon('fa-solid fa-rotate'), h('span', t('refresh', '刷新'))])
                ])
            ]),
            vm.loading ? loading() : vm.error ? h('div', { class: 'pmk-banner pmk-banner--error' },
                [icon('fa-solid fa-triangle-exclamation'), h('div', { class: 'pmk-banner-body' }, vm.error)]) : [
                vm.recoveryMode ? h('div', { class: 'pmk-banner pmk-banner--error' }, [icon('fa-solid fa-triangle-exclamation'), h('div', { class: 'pmk-banner-body' }, [
                    h('div', { class: 'pmk-banner-title' }, t('recovery.banner.title', '当前正处于恢复模式')),
                    h('div', t('recovery.banner.desc', '正常功能已暂停。请根据下列原因安装、修复或重新安装插件，完成后重启程序。')),
                    vm.hasRecoveryReasons ? h('ul', vm.recoveryReasons.map(function (reason) { return h('li', { key: reason }, reason); })) : null
                ])]) : null,
                !vm.masterEnabled ? h('div', { class: 'pmk-banner pmk-banner--warn' }, [icon('fa-solid fa-circle-exclamation'), h('div', { class: 'pmk-banner-body' }, [
                    h('div', { class: 'pmk-banner-title' }, t('master.disabled.title', '插件市场未开启')),
                    h('div', t('master.disabled.desc', '请在配置中开启受信 catalog 后再浏览仓库与安装插件。'))
                ])]) : null,
                vm.repositories.length ? h('div', { class: 'pmk-repos' }, [h('span', { class: 'pmk-repos-label' }, t('section.repositories', '受信仓库')),
                    vm.repositories.map(function (repo) {
                        return h('button', { key: repo.repositoryId, class: ['pmk-repo-chip', { active: repo.repositoryId === vm.activeRepositoryId }],
                            disabled: !repo.enabled, title: vm.repoTitle(repo), onClick: function () { vm.switchRepository(repo); } }, [
                            icon('fa-solid ' + (repo.official ? 'fa-circle-check' : 'fa-folder')), h('span', { class: 'pmk-repo-chip-name' }, repo.displayName || repo.repositoryId),
                            repo.repositoryId === vm.activeRepositoryId ? h('span', { class: 'pmk-repo-chip-meta' }, t('repo.active', '当前'))
                                : !repo.enabled ? h('span', { class: 'pmk-repo-chip-meta' }, t('repo.disabled', '已禁用'))
                                    : !repo.proxyPolicySupported ? h('span', { class: 'pmk-repo-chip-meta' }, t('repo.proxy.unsupported', '代理不支持')) : null
                        ]);
                    })]) : null,
                vm.hostElevated ? h('div', { class: 'pmk-banner pmk-banner--warn' }, [icon('fa-solid fa-triangle-exclamation'),
                    h('div', { class: 'pmk-banner-body' }, t('host.elevated.notice', '宿主正在以高权限运行；所有宿主进程完全信任插件都会继承当前高权限。'))]) : null,
                h('div', { class: 'pmk-banner pmk-banner--warn pmk-security-notice' }, [icon('fa-solid fa-shield-halved'),
                    h('div', { class: 'pmk-banner-body' }, t('security.notice', '插件执行与签名说明：宿主进程完全信任插件与主程序运行在同一 JVM；声明式插件进入使用同一系统账号的有限隔离 worker。签名只证明来源与内容完整性，不代表安全审查；请仅安装你信任的插件。'))]),
                vm.showCatalogLoading ? loading() : vm.showCatalogError ? h('div', { class: 'pmk-banner pmk-banner--error' }, [icon('fa-solid fa-triangle-exclamation'),
                    h('div', { class: 'pmk-banner-body' }, [h('div', { class: 'pmk-banner-title' }, t('error.catalog.title', '无法加载插件清单')), h('div', vm.catalogError)])])
                    : vm.showBody ? body() : null
            ],
            h('div', { class: 'pmk-disclaimer' }, t('disclaimer', '插件运行于本地，仅供个人学习与研究使用；无法验证、未签名或用户放行的插件请自行确认来源与安全性，我们无法保证未验证插件的安全；请尊重创作者版权 · 本工具与 Pixiv 无任何关联')),
            modal()
        ]);
    }


    function component(Vue) {
        return {
            render: function () { return renderMarket(Vue, this); },
            data: function () {
                return {
                    i18nRev: 0,
                    loading: true,
                    catalogLoading: false,
                    loadingMore: false,
                    error: null,
                    catalogError: null,
                    masterEnabled: false,
                    recoveryMode: false,
                    recoveryReasons: [],
                    hostElevated: false,
                    filtersInitialized: false,
                    sdkVersion: '',
                    repositories: [],
                    defaultRepositoryId: null,
                    activeRepositoryId: null,
                    catalog: null,
                    // 异步竞态护栏：每次仓库列表 / catalog 拉取自增对应 token，回调只在 token 仍为最新时落地，
                    // 仓库快速切换时旧仓库的响应被丢弃、绝不覆盖当前仓库状态。
                    reloadToken: 0,
                    catalogToken: 0,
                    category: 'all',
                    search: '',
                    sort: 'recommended',
                    hideDefaultInstalled: true,
                    hideDependencies: true,
                    onlyOfficial: false,
                    onlyCompatible: false,
                    selectedPluginId: null,
                    selectedDetail: null,
                    selectedVersion: null,
                    detailLoadingMore: false,
                    installing: {},
                    installResults: {},
                    sortOptions: PMK.SORT_OPTIONS
                };
            },
            computed: {
                entries: function () { return this.catalog ? this.catalog.entries : []; },
                filteredEntries: function () {
                    this.i18nRev; // 语言变化时重排（名称排序依赖本地化名）
                    return PMK.data.filterAndSort(this.entries, {
                        category: this.category, search: this.search,
                        hideDefaultInstalled: this.hideDefaultInstalled, hideDependencies: this.hideDependencies,
                        onlyOfficial: this.onlyOfficial,
                        onlyCompatible: this.onlyCompatible, sort: this.sort
                    });
                },
                // 卡片绑定其所属仓库 id（= 当前 catalog 的 repositoryId，后端权威），安装时用它而非易变的全局
                // activeRepositoryId —— 展示条目与安装请求的 repositoryId 同源，仓库切换后也不会错配。
                cards: function () {
                    this.i18nRev;
                    var repoId = this.activeCatalogRepositoryId;
                    return this.filteredEntries.map(function (entry) {
                        var card = PMK.data.cardModel(entry);
                        card.repositoryId = repoId;
                        return card;
                    });
                },
                categoryList: function () { this.i18nRev; return PMK.data.categoryList(this.catalog); },
                categoryLabel: function () { this.i18nRev; return PMK.categoryLabel(this.category); },
                categoryDescription: function () { this.i18nRev; return PMK.categoryDescription(this.category); },
                installedCount: function () { return this.catalog ? this.catalog.installedCount : 0; },
                // 当前展示 catalog 所属仓库 id（安装状态键控与安装请求的同一来源）。
                activeCatalogRepositoryId: function () { return this.catalog ? this.catalog.repositoryId : null; },
                selectedEntry: function () {
                    var id = this.selectedPluginId;
                    if (!id) return null;
                    if (this.selectedDetail && this.selectedDetail.pluginId === id) return this.selectedDetail;
                    for (var i = 0; i < this.entries.length; i++) { if (this.entries[i].pluginId === id) return this.entries[i]; }
                    return null;
                },
                detail: function () { this.i18nRev; return this.selectedEntry ? this.buildDetail(this.selectedEntry) : null; },
                modalStatus: function () {
                    if (this.selectedPluginId
                            && this.installing[this.installKey(this.activeCatalogRepositoryId, this.selectedPluginId)]) {
                        return 'INSTALLING';
                    }
                    return null;
                },
                modalMeta: function () { return PMK.installMeta(this.modalState()); },
                modalLabel: function () { return this.installLabel(this.modalState(), this.selectedVersion); },
                installResultFor: function () {
                    if (!this.selectedPluginId) return null;
                    return this.installResults[this.installKey(this.activeCatalogRepositoryId, this.selectedPluginId)] || null;
                },
                showCatalogLoading: function () { return this.masterEnabled && this.catalogLoading; },
                showCatalogError: function () { return this.masterEnabled && !!this.catalogError; },
                showBody: function () { return this.masterEnabled && !!this.catalog; },
                hasRecoveryReasons: function () { return this.recoveryReasons.length > 0; },
                showVersionSelect: function () { return !!this.detail && this.detail.versions.length > 1; },
                showDetailVerification: function () { return !!this.detail && !!this.detail.verificationBadge; },
                showRestartHint: function () {
                    var r = this.installResultFor;
                    return !!r && !r.recoveryBlocked && r.accepted && r.effectiveAfterRestart;
                }
            },
            mounted: function () {
                document.addEventListener('keydown', this.onKeydown);
                this.reload();
            },
            beforeUnmount: function () {
                document.removeEventListener('keydown', this.onKeydown);
                document.body.style.overflow = '';
            },
            methods: {
                t: function (key, fallback, vars) { this.i18nRev; return PMK.t(key, fallback, vars); },
                bumpI18n: function () { this.i18nRev++; },
                repoTitle: function (repo) {
                    var parts = [repo.manifestUrl || ''];
                    parts.push(this.t('repo.proxy', '代理策略') + ': ' + (repo.proxyPolicy || ''));
                    if (!repo.proxyPolicySupported) parts.push(this.t('repo.proxy.unsupported', '代理不支持'));
                    if (repo.official) parts.push(this.t('repo.official', '官方'));
                    if (repo.builtIn) parts.push(this.t('repo.builtin', '内嵌'));
                    return parts.filter(Boolean).join(' · ');
                },
                reload: function () {
                    var self = this;
                    // 让在途的旧仓库列表 / catalog 拉取全部失效（其回调将被 token 守卫丢弃）。
                    var token = ++this.reloadToken;
                    this.catalogToken++;
                    this.loading = true; this.error = null; this.catalogError = null;
                    Promise.all([PMK.api.fetchRepositories(), PMK.api.fetchPluginStatus()]).then(function (responses) {
                        if (token !== self.reloadToken) return;   // 已有更新的 reload，丢弃旧响应
                        var repos = responses[0];
                        var status = responses[1];
                        self.masterEnabled = !!repos.enabled;
                        self.recoveryMode = !!status.recoveryMode;
                        self.recoveryReasons = PMK.recoveryReasons(status);
                        self.hostElevated = !!status.hostElevated;
                        PMK.state.hostElevated = self.hostElevated;
                        if (!self.filtersInitialized) {
                            self.hideDefaultInstalled = !self.recoveryMode;
                            self.filtersInitialized = true;
                        }
                        self.sdkVersion = repos.sdkVersion || '';
                        self.repositories = repos.repositories || [];
                        self.defaultRepositoryId = repos.defaultRepositoryId || null;
                        var stillValid = self.repositories.some(function (r) {
                            return r.repositoryId === self.activeRepositoryId && r.enabled;
                        });
                        if (!stillValid) self.activeRepositoryId = repos.defaultRepositoryId || null;
                        self.loading = false;
                        if (self.masterEnabled && self.activeRepositoryId) {
                            self.loadCatalog(self.activeRepositoryId);
                        } else {
                            self.catalog = null;
                        }
                    }).catch(function () {
                        if (token !== self.reloadToken) return;
                        self.error = self.t('error.load', '加载插件市场失败，请稍后重试。');
                        self.loading = false;
                    });
                },
                loadCatalog: function (repoId) {
                    var self = this;
                    var token = ++this.catalogToken;
                    this.catalogLoading = true; this.catalogError = null;
                    PMK.api.fetchCatalog(repoId).then(function (cat) {
                        if (token !== self.catalogToken) return;   // 仓库已切换，丢弃旧仓库的 catalog 响应
                        self.catalog = cat;
                        self.catalogLoading = false;
                    }).catch(function () {
                        if (token !== self.catalogToken) return;
                        self.catalog = null;
                        self.catalogError = self.t('error.catalog', '无法加载该仓库的插件清单，请检查仓库状态或稍后重试。');
                        self.catalogLoading = false;
                    });
                },
                loadMore: function () {
                    var self = this;
                    if (!this.catalog || !this.catalog.nextCursor || this.loadingMore) return;
                    this.loadingMore = true;
                    PMK.api.fetchCatalog(this.activeRepositoryId, { cursor: this.catalog.nextCursor }).then(function (page) {
                        if (!self.catalog || page.generation !== self.catalog.generation) {
                            self.loadCatalog(self.activeRepositoryId); return;
                        }
                        self.catalog.entries = self.catalog.entries.concat(page.entries || []);
                        self.catalog.nextCursor = page.nextCursor;
                    }).catch(function () {
                        PMK.toast(self.t('error.catalog', '无法加载该仓库的插件清单，请检查仓库状态或稍后重试。'), 'error');
                    }).finally(function () { self.loadingMore = false; });
                },
                switchRepository: function (repo) {
                    if (!repo.enabled || repo.repositoryId === this.activeRepositoryId) return;
                    this.activeRepositoryId = repo.repositoryId;
                    this.category = 'all'; this.search = '';
                    this.loadCatalog(repo.repositoryId);
                },
                setCategory: function (id) { this.category = id; },
                openDetail: function (pluginId) {
                    var self = this;
                    this.selectedPluginId = pluginId;
                    var entry = this.selectedEntry;
                    this.selectedDetail = entry;
                    this.selectedVersion = entry ? entry.latestVersion : null;
                    document.body.style.overflow = 'hidden';
                    PMK.api.fetchPluginDetail(this.activeCatalogRepositoryId, pluginId).then(function (detail) {
                        if (self.selectedPluginId !== pluginId) return;
                        self.selectedDetail = detail;
                        self.selectedVersion = detail.latestVersion || self.selectedVersion;
                    }).catch(function () {
                        PMK.toast(self.t('error.detail', '无法加载插件详情，请稍后重试。'), 'error');
                    });
                },
                loadMoreVersions: function () {
                    var self = this;
                    var detail = this.selectedDetail;
                    if (!detail || !detail.nextVersionCursor || this.detailLoadingMore) return;
                    this.detailLoadingMore = true;
                    PMK.api.fetchPluginDetail(this.activeCatalogRepositoryId, detail.pluginId,
                        { cursor: detail.nextVersionCursor }).then(function (page) {
                        if (!self.selectedDetail || page.versionsGeneration !== detail.versionsGeneration) {
                            self.openDetail(detail.pluginId); return;
                        }
                        var seen = {};
                        (detail.packages || []).forEach(function (pkg) { seen[pkg.version] = true; });
                        detail.packages = (detail.packages || []).concat((page.packages || []).filter(function (pkg) {
                            if (seen[pkg.version]) return false;
                            seen[pkg.version] = true; return true;
                        }));
                        detail.nextVersionCursor = page.nextVersionCursor;
                    }).catch(function () {
                        PMK.toast(self.t('error.detail', '无法加载插件详情，请稍后重试。'), 'error');
                    }).finally(function () { self.detailLoadingMore = false; });
                },
                closeDetail: function () {
                    this.selectedPluginId = null;
                    this.selectedDetail = null;
                    this.selectedVersion = null;
                    document.body.style.overflow = '';
                },
                onKeydown: function (e) {
                    if (e.key === 'Escape' && this.selectedPluginId) this.closeDetail();
                },
                // 安装态键控：(repositoryId, pluginId) 复合键，使同名插件在不同仓库间互不污染。
                installKey: function (repositoryId, pluginId) {
                    return String(repositoryId) + '\u0000' + String(pluginId);
                },
                // 卡片安装控件的有效状态：本地安装中 / 后端安装终态 / 否则 catalog 安装状态（按卡片同源仓库键控）。
                cardStatus: function (card) {
                    var key = this.installKey(card.repositoryId, card.pluginId);
                    if (this.installing[key]) return 'INSTALLING';
                    return PMK.data.installResultStatus(this.installResults[key], card.installStatus);
                },
                cardMeta: function (card) { return PMK.installMeta(this.cardStatus(card)); },
                showCardRating: function (card) { return !!(card.ratingStars || card.downloadsLabel); },
                showCardMeta: function (card) { return !!(card.versionLabel || card.sizeLabel || card.dateLabel); },
                showCardCompat: function (card) { return !card.compatible && !!card.compatibilityReason; },
                showCardVerification: function (card) { return !!(card && card.verificationBadge); },
                cardLabel: function (card) {
                    var status = this.cardStatus(card);
                    if (status === 'UPDATE_AVAILABLE') return this.t('install.action.update-to', '更新到 v{v}', { v: card.latestVersion });
                    return this.installLabelText(status);
                },
                install: function (card) {
                    this.doInstall(card.repositoryId, card.pluginId, card.latestVersion);
                },
                installModal: function () {
                    if (this.selectedPluginId) {
                        this.doInstall(this.activeCatalogRepositoryId, this.selectedPluginId,
                            this.selectedVersion);
                    }
                },
                // 安装请求只用展示条目同源的 repositoryId（来自卡片 / 当前 catalog），不读易变的全局 activeRepositoryId；
                // 在途与结果按 (repositoryId, pluginId) 复合键存储——切到其它仓库时本仓库的安装态不会污染同名插件。
                doInstall: function (repositoryId, pluginId, version) {
                    var self = this;
                    if (!repositoryId || !pluginId || !version) return;
                    var key = this.installKey(repositoryId, pluginId);
                    if (this.installing[key]) return;
                    this.installing[key] = true;
                    delete this.installResults[key];
                    PMK.installPluginWithConfirmation(repositoryId, pluginId, version).then(function (res) {
                        var model = res.kind === 'install'
                            ? PMK.data.installResult(res.body)
                            : PMK.data.catalogError(res.body, res.httpStatus);
                        self.installResults[key] = model;
                        self.recordDependencyInstallResults(repositoryId, model);
                        var feedback = PMK.data.installFeedback(model);
                        PMK.toast(feedback.message, feedback.tone);
                    }).catch(function () {
                        self.installResults[key] = {
                            tone: 'bad', accepted: false, recoveryBlocked: false,
                            effectiveAfterRestart: false, outcome: null,
                            message: self.t('error.install.generic', '安装请求失败，请重试。'), warnings: [], errors: []
                        };
                        PMK.toast(self.t('error.install.generic', '安装请求失败，请重试。'), 'error');
                    }).then(function () {
                        delete self.installing[key];
                        self.refreshCatalogAfterInstall(repositoryId);
                    });
                },
                recordDependencyInstallResults: function (repositoryId, model) {
                    var self = this;
                    (model.dependencyInstallResults || []).forEach(function (dependencyResult) {
                        if (!dependencyResult.pluginId) return;
                        self.installResults[self.installKey(repositoryId, dependencyResult.pluginId)] = dependencyResult;
                    });
                },
                refreshCatalogAfterInstall: function (repositoryId) {
                    if (repositoryId && repositoryId === this.activeCatalogRepositoryId) {
                        this.loadCatalog(repositoryId);
                    }
                },
                // 详情弹窗当前选中版本的安装状态（按所选版本制品兼容性 / 是否已是已安装版本派生）。
                modalState: function () {
                    var entry = this.selectedEntry;
                    if (!entry) return 'NOT_INSTALLED';
                    var result = this.installResults[this.installKey(this.activeCatalogRepositoryId, entry.pluginId)];
                    var resultStatus = PMK.data.installResultStatus(result, null);
                    if (resultStatus) return resultStatus;
                    var pkg = PMK.data.packageOf(entry, this.selectedVersion);
                    if (!pkg) return entry.installStatus;   // 无可安装版本制品 → 沿用后端状态（UNAVAILABLE / 已安装）
                    var verificationStatus = this.packageVerificationInstallStatus(pkg);
                    if (verificationStatus) return verificationStatus;
                    if (!pkg.compatible) return 'INCOMPATIBLE';
                    if (entry.installedVersion && entry.installedVersion === this.selectedVersion) return 'INSTALLED';
                    return entry.installStatus === 'UPDATE_AVAILABLE' ? 'UPDATE_AVAILABLE' : 'NOT_INSTALLED';
                },
                installLabel: function (status, version) {
                    if (status === 'UPDATE_AVAILABLE') return this.t('install.action.update-to', '更新到 v{v}', { v: version });
                    if (status === 'NOT_INSTALLED' && version) return this.t('install.action.install-version', '安装 v{v}', { v: version });
                    return this.installLabelText(status);
                },
                installLabelText: function (status) {
                    var meta = PMK.installMeta(status);
                    return this.t(meta.labelKey, meta.status);
                },
                installResultIcon: function (result) {
                    return result && result.accepted && !result.recoveryBlocked
                        ? 'fa-circle-check' : 'fa-circle-exclamation';
                },
                buildDetail: function (entry) {
                    var m = entry.market || {};
                    var card = PMK.data.cardModel(entry);
                    var pkg = PMK.data.packageOf(entry, this.selectedVersion);
                    var verificationBadge = PMK.data.verificationBadge(pkg && pkg.verification);
                    var rows = [];
                    if (m.author) rows.push({ key: 'detail.author', val: m.author });
                    rows.push({ key: 'detail.category', val: card.categoryLabel });
                    if (pkg) rows.push({ key: 'detail.version', val: 'v' + pkg.version, mono: true });
                    if (entry.installedVersion) rows.push({ key: 'detail.installed-version', val: 'v' + entry.installedVersion, mono: true });
                    if (m.updatedTime) rows.push({ key: 'detail.updated', val: PMK.formatDate(m.updatedTime) });
                    var size = pkg ? PMK.formatSize(pkg.expectedSizeBytes) : null;
                    if (size) rows.push({ key: 'detail.size', val: size, mono: true });
                    if (pkg && pkg.requiredSdk) {
                        rows.push({ key: 'detail.requires', val: pkg.requiredSdk, mono: true, danger: !pkg.compatible });
                    }
                    rows.push({
                        key: 'detail.compatible',
                        val: (pkg && !pkg.compatible) ? this.t('detail.incompatible', '不兼容') : this.t('detail.compatible-yes', '兼容'),
                        danger: !!(pkg && !pkg.compatible)
                    });
                    if (m.license) rows.push({ key: 'detail.license', val: m.license, mono: true });
                    if (pkg && pkg.sha256) rows.push({ key: 'detail.sha256', val: shorten(pkg.sha256), mono: true, title: pkg.sha256 });
                    if (pkg && pkg.verification) rows.push({
                        key: 'detail.verification',
                        val: this.verificationLabel(pkg.verification),
                        danger: this.verificationDanger(pkg.verification),
                        title: pkg.verification.trustLabel || pkg.verification.publisher || pkg.verification.diagnosticCode || null
                    });
                    if (m.homepageUrl) rows.push({ key: 'detail.homepage', val: m.homepageUrl, href: m.homepageUrl });
                    rows.push({
                        key: 'detail.effect',
                        val: (pkg && pkg.effectiveAfterRestart)
                            ? this.t('detail.restart-required', '重启后生效')
                            : this.t('detail.hot-activation', '安装后即时激活')
                    });

                    var deps = pkg && pkg.dependencies ? pkg.dependencies.slice() : [];
                    var versions = (entry.packages || []).map(function (p) {
                        return {
                            version: p.version,
                            dateLabel: p.releasedTime ? PMK.formatDate(p.releasedTime) : '',
                            notes: p.changeNotes || [],
                            channel: p.channel,
                            deprecated: p.deprecated
                        };
                    });
                    return {
                        pluginId: entry.pluginId,
                        name: card.name, sub: card.sub, iconClass: card.iconClass, colorClass: card.colorClass,
                        categoryLabel: card.categoryLabel, categoryIcon: card.categoryIcon, official: card.official,
                        ratingStars: card.ratingStars, ratingNum: card.ratingNum, downloadsLabel: card.downloadsLabel,
                        description: PMK.data.entryDescription(entry), tags: card.tags,
                        versions: versions, dependencies: deps, infoRows: rows, verificationBadge: verificationBadge
                    };
                },
                packageVerificationInstallStatus: function (pkg) {
                    var v = pkg && pkg.verification;
                    if (!v || !v.status) return null;
                    if (v.status === 'VERIFIED_OFFICIAL' || v.status === 'VERIFIED_CUSTOM') return null;
                    if (['SIGNATURE_REQUIRED', 'UNKNOWN_KEY', 'REVOKED_KEY', 'INVALID_SIGNATURE', 'HASH_MISMATCH']
                            .indexOf(v.status) !== -1) {
                        return v.status;
                    }
                    return null;
                },
                verificationLabel: function (verification) {
                    if (!verification || !verification.status) return this.t('verification.unverified-local', '本地未验证');
                    return this.t('verification.' + String(verification.status).toLowerCase().replace(/_/g, '-'), verification.status);
                },
                verificationDanger: function (verification) {
                    if (!verification || !verification.status) return true;
                    return ['SIGNATURE_REQUIRED', 'UNKNOWN_KEY', 'REVOKED_KEY', 'INVALID_SIGNATURE', 'HASH_MISMATCH']
                        .indexOf(verification.status) !== -1;
                }
            }
        };
    }

    function shorten(hash) {
        if (!hash) return '';
        return hash.length > 16 ? (hash.slice(0, 12) + '…') : hash;
    }

    // 尝试用 Vue reactive 渲染市场页。Vue 缺失 / 运行时加载失败 / 挂载抛错 → 收敛为 false（init 回退命令式渲染）。
    VUE.tryMount = function (rootEl) {
        if (!rootEl || !global.PixivVue) return Promise.resolve(false);
        return global.PixivVue.ensure().then(function (Vue) {
            var app = Vue.createApp(component(Vue));
            var vm = app.mount(rootEl);
            PMK.state.activeView = {
                reload: function () { vm.reload(); },
                rerender: function () { vm.bumpI18n(); }
            };
            return true;
        }).catch(function (e) {
            console.warn('[PluginMarket] Vue 挂载失败，回退命令式渲染：', e);
            return false;
        });
    };
})(window);
