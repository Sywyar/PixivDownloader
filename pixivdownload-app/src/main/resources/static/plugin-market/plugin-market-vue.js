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
    function renderMarket(Vue, vm, Content) {
        var h = Vue.h;
        var t = vm.t;
        function icon(cls) { return h('i', { class: cls, 'aria-hidden': 'true' }); }
        function marketIcon(card, className) {
            var url = PMK.api.contentImageUrl(card.repositoryId, card.pluginId, card.icon, 'icon', 0);
            return h('span', { class: className }, [icon(card.iconClass), url ? h('img', {
                key: url, class: 'pmk-market-image', src: url, alt: '', loading: 'lazy',
                onError: function (event) { event.target.hidden = true; }
            }) : null]);
        }
        function stars(rating) {
            if (!rating) return null;
            return h('span', { class: 'pmk-stars' }, [
                Array.from({ length: rating.full }, function () { return icon('fa-solid fa-star'); }),
                Array.from({ length: rating.half }, function () { return icon('fa-solid fa-star-half-stroke'); }),
                Array.from({ length: rating.empty }, function () { return icon('fa-regular fa-star'); })
            ]);
        }
        function progress(phase) {
            return h('div', { class: 'pmk-install-progress', role: 'status' }, [
                h('div', { class: 'pmk-install-progress-label' }, [icon(phase === 'confirm' || phase === 'trust' ? 'fa-solid fa-clock' : 'fa-solid fa-spinner fa-spin'), t(PMK.installPhaseKey(phase))])
            ]);
        }
        function factSection(section) {
            var content = [
                h('dl', {class: 'pmk-facts'}, (section.fields || []).map(function (field) {
                    return h('div', {key: field.label}, [h('dt', field.label), h('dd', field.value)]);
                })),
                (section.paragraphs || []).map(function (text) { return h('p', {key: text}, text); })
            ];
            return section.collapsed ? disclosure(section.title, content, 'pmk-fact-section')
                : h('section', {class: 'pmk-fact-section', key: section.title}, [h('h3', section.title), content]);
        }
        function disclosure(title, content, className) {
            return h('details', {class: [className, 'accordion accordion-item pixiv-disclosure'], key: title}, [
                h('summary', {class: 'accordion-button collapsed'}, title),
                h('div', {class: 'accordion-body'}, content)
            ]);
        }
        function loading() {
            return h('div', { class: 'pmk-state' }, [icon('fa-solid fa-spinner fa-spin'), h('span', t('loading', '正在加载…'))]);
        }
        function filter(field, cls, key, fallback) {
            var label = t(key, fallback);
            return h('label', { class: 'pmk-filter' }, [
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
            return h('article', { key: card.pluginId, class: ['pmk-card', card.colorClass,
                card.focusReason ? 'pmk-card--suspected' : '', card.artifactMismatch ? 'pmk-card--mismatch' : ''] }, [
                h('div', { class: 'pmk-card-banner', onClick: open }, [
                    icon(['pmk-card-banner-glyph', card.iconClass]), icon(['pmk-card-banner-bg', card.iconClass]),
                    h('span', { class: 'pmk-card-banner-cat' }, [icon(card.categoryIcon), card.categoryLabel])
                ]),
                h('div', { class: 'pmk-card-body' }, [
                    h('div', { class: 'pmk-card-head' }, [
                        marketIcon(card, 'pmk-card-icon'),
                        h('div', { class: 'pmk-card-titleblock' }, [
                            h('div', { class: 'pmk-card-name-row' }, [
                                h('button', { type: 'button', class: 'pmk-card-name', onClick: open }, card.name),
                                card.focusReason ? h('span', { class: 'pmk-badge pmk-badge--suspected' }, t('recovery.focus.' + card.focusReason)) : null,
                                card.installationState ? h('span', { class: ['pmk-badge', card.artifactMismatch ? 'pmk-badge--mismatch' : 'pmk-badge--state'] }, t('installation.state.' + card.installationState)) : null,
                                h('span', { class: 'pmk-badge pmk-badge--' + (card.official ? 'official' : 'community') },
                                    card.official ? t('badge.official', '官方') : card.assuranceLabel),
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
                    h('div', { class: 'pmk-card-footer' }, [
                        vm.showCardMeta(card) ? h('div', { class: 'pmk-card-meta' }, [card.versionLabel, card.sizeLabel, card.dateLabel].filter(Boolean).join(' · ')) : null,
                        vm.showCardCompat(card) ? h('div', { class: 'pmk-card-compat' }, [icon('fa-solid fa-triangle-exclamation'),
                            t('compat.needs', '需要SDK v{v}+（当前 v{cur}）', { v: card.compatibilityReason, cur: vm.sdkVersion })]) : null,
                        card.compatibilityNotice ? h('p', { class: 'pmk-card-compat pmk-card-compat--notice' }, card.compatibilityNotice) : null,
                        card.installationNotice ? h('p', {class: 'pmk-card-compat pmk-card-compat--notice'}, card.installationNotice) : null,
                        h('div', { class: 'pmk-card-actions' }, [
                            vm.cardStatus(card) === 'INSTALLING' ? progress(vm.installing[vm.installKey(card.repositoryId, card.pluginId)]) : h('button', {
                                class: ['pmk-btn pmk-install', 'pmk-btn--' + meta.variant], disabled: meta.disabled,
                                onClick: function () { vm.install(card); }
                            }, [icon('fa-solid fa-' + meta.icon), h('span', vm.cardLabel(card))]),
                            h('button', { class: 'pmk-btn pmk-btn--gray pmk-btn--sm', onClick: open },
                                [icon('fa-solid fa-circle-info'), h('span', t('card.detail', '详情'))])
                        ])
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
                    vm.catalog && vm.catalog.focusIncomplete ? h('p', {class: 'pmk-banner pmk-banner--warn'}, t('recovery.focus-incomplete')) : null,
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
            return h('dialog', {
                class: 'pmk-modal', 'aria-labelledby': 'pmk-detail-title',
                onClick: Vue.withModifiers(vm.closeDetail, ['self']),
                onCancel: function (event) { event.preventDefault(); event.stopPropagation(); vm.closeDetail(); },
                onVnodeMounted: function (vnode) { vnode.el.showModal(); }
            }, [
                h('div', { class: ['pmk-modal-panel', detail.colorClass] }, [
                    h('div', { class: 'pmk-hero' }, [
                        h('button', { class: 'pmk-hero-close', 'aria-label': t('modal.close', '关闭'), onClick: vm.closeDetail }, [icon('fa-solid fa-xmark')]),
                        marketIcon(detail, 'pmk-hero-icon'),
                        h('div', { class: 'pmk-hero-titleblock' }, [
                            h('h2', { id: 'pmk-detail-title', class: 'pmk-hero-name' }, [h('span', detail.name), h('span', { class: 'pmk-hero-pill' },
                                detail.assuranceLabel)]),
                            h('div', { class: 'pmk-hero-sub' }, [detail.author, detail.categoryLabel].filter(Boolean).join(' · ')),
                            h('p', {class: 'pmk-hero-summary'}, detail.summary)
                        ])
                    ]),
                    h('div', { class: 'pmk-modal-actionbar' }, [
                        h('div', {class: 'pmk-action-context'}, detail.infoRows.filter(function (row) {
                            return row.key === 'detail.installed-version' && detail.installedVersion
                                || row.key === 'detail.effect' && detail.restartRequired;
                        }).map(function (row) { return h('p', {key: row.key}, [h('span', t(row.key) + ' '), h('strong', row.val)]); })),
                        h('div', { class: 'pmk-modal-actionbar-right' }, [
                            vm.showVersionSelect ? Vue.withDirectives(h('select', { class: 'pmk-version-select', 'aria-label': t('detail.version'), disabled: vm.modalStatus === 'INSTALLING',
                                'onUpdate:modelValue': function (value) { vm.selectedVersion = value; vm.loadPackageFacts(); } }, detail.versions.map(function (v) {
                                return h('option', { key: v.version, value: v.version }, 'v' + v.version + (v.channel && v.channel !== 'stable' ? ' · ' + v.channel : ''));
                            })), [[Vue.vModelSelect, vm.selectedVersion]]) : null,
                            vm.modalStatus === 'INSTALLING' ? progress(vm.installing[vm.installKey(vm.activeCatalogRepositoryId, vm.selectedPluginId)]) : h('button', {
                                class: ['pmk-btn pmk-install', 'pmk-btn--' + vm.modalMeta.variant], disabled: vm.modalMeta.disabled, onClick: vm.installModal
                            }, [icon('fa-solid fa-' + vm.modalMeta.icon), h('span', vm.modalLabel)]),
                            ['INSTALLED', 'INSTALLED_SAME', 'INSTALL_DIFFERENT', 'INSTALL_UNVERIFIED', 'ACTIVATED', 'PENDING_RESTART', 'STORED_DEVELOPMENT'].includes(vm.modalState()) ? h('a', {class: 'pmk-btn pmk-btn--primary', href: '/plugin-manage.html'}, t('install.goto-manage')) : null
                        ])
                    ]),
                    h('div', { class: 'pmk-modal-body' }, [
                        detail.compatibilityNotice ? h('p', {class: 'pmk-card-compat pmk-card-compat--notice', role: 'status'}, detail.compatibilityNotice) : null,
                        detail.installationNotice ? h('p', {class: 'pmk-card-compat pmk-card-compat--notice', role: 'status'}, detail.installationNotice) : null,
                        factSection({title: t('installation.local-package'), fields: detail.localArtifactFields}),
                        h('p', {class: 'pmk-section-text'}, t('installation.market-facts')),
                        h('section', {class: 'pmk-about'}, [h('h3', {class: 'pmk-section-label'}, t('detail.about')),
                            h('div', {class: 'pmk-section-text'}, detail.description || t('detail.no-description'))]),
                        h('dl', {class: 'pmk-detail-overview'}, detail.infoRows.filter(function (row) {
                            return ['detail.version', 'detail.requires', 'detail.size'].includes(row.key);
                        }).map(function (row) {
                            return h('div', {key: row.key}, [h('dt', t(row.key)), h('dd', {class: {'pmk-info-val--danger': row.danger}}, row.val)]);
                        })),
                        detail.infoRows.filter(function (row) { return row.key === 'detail.compatible' && row.danger; })
                            .map(function (row) { return h('p', {class: 'pmk-card-compat pmk-card-compat--notice', role: 'status'}, row.val); }),
                        result ? h('section', {class: 'pmk-install-result'}, [
                            h('h3', {class: 'pmk-section-label'}, t('detail.install-result')),
                            h('div', {class: ['pmk-install-result-box', 'pmk-install-result-box--' + result.tone]}, [
                                h('div', {class: 'pmk-install-result-head'}, [
                                    icon(['fa-solid', vm.installResultIcon(result)]),
                                    h('span', {class: 'pmk-install-result-msg'}, PMK.data.installFeedback(result).message)
                                ]),
                                vm.showRestartHint ? h('p', {class: 'pmk-install-restart'}, [
                                    t('install.restart-hint'), ' ', h('a', {href: '/plugin-manage.html'}, t('install.goto-manage'))
                                ]) : null,
                                result.warnings.length ? h('div', {class: 'pmk-install-list'}, [
                                    t('install.unmet-deps'), h('ul', result.warnings.map(function (text) { return h('li', {key: text}, text); }))
                                ]) : null,
                                disclosure(t('common:plugin-info.diagnostics'), [
                                    h('p', result.message),
                                    result.outcome ? h('code', result.outcome) : null,
                                    (result.errors || []).map(function (text) { return h('p', {key: text}, text); })
                                ], 'pmk-detail-disclosure')
                            ])
                        ]) : null,
                        h(Content, { model: detail.content }),
                        h('section', {class: 'pmk-detail-security'}, [
                            vm.showDetailVerification ? h('div', {class: ['pmk-verification-summary', 'pmk-detail-verification--' + badge.tone]}, [
                                icon(['fa-solid', badge.icon]), h('span', t(badge.labelKey, badge.status))
                            ]) : null,
                            detail.trustSections.reduce(function (notes, section) { return notes.concat(section.paragraphs || []); }, [])
                                .map(function (text) { return h('p', {class: 'pmk-section-text', key: text}, text); }),
                            disclosure(t('trust.facts'), [
                                h('div', {class: 'pmk-trust-grid'}, detail.trustSections.map(function (section) {
                                    return factSection(Object.assign({}, section, {paragraphs: []}));
                                })),
                                factSection({title: t('common:plugin-info.artifact'), fields: detail.artifactFields})
                            ], 'pmk-detail-disclosure')
                        ]),
                        disclosure(t('detail.changelog'), [
                            detail.versions.length ? h('div', {class: 'pmk-versions'}, detail.versions.map(function (version) {
                                return h('div', {key: version.version, class: 'pmk-version-row'}, [
                                    h('div', {class: 'pmk-version-col'}, [
                                        h('span', {class: 'pmk-version-tag'}, 'v' + version.version),
                                        version.dateLabel ? h('div', {class: 'pmk-version-date'}, version.dateLabel) : null
                                    ]),
                                    version.hasReleaseNotes ? h('button', {class: 'pmk-btn pmk-btn--gray pmk-btn--sm',
                                        onClick: function () { vm.showReleaseNotes(version.version); }}, t('content.releaseNotes'))
                                        : version.notes.length ? h('ul', {class: 'pmk-version-notes'}, version.notes.map(function (text, i) { return h('li', {key: i}, text); }))
                                        : h('p', {class: 'pmk-version-empty'}, t('detail.no-notes'))
                                ]);
                            })) : h('p', t('detail.no-versions')),
                            detail.nextVersionCursor ? h('button', {class: 'pmk-btn pmk-btn--gray', onClick: vm.loadMoreVersions, disabled: vm.detailLoadingMore}, t('pagination.more-versions')) : null
                        ], 'pmk-detail-disclosure'),
                        detail.dependencies.length ? h('section', [
                            h('h3', {class: 'pmk-section-label'}, t('common:plugin-info.dependency-plugins')),
                            h('ul', detail.dependencies.map(function (dep) { return h('li', {key: dep}, dep); }))
                        ]) : null,
                        disclosure(t('detail.information'), [
                            h('dl', {class: 'pmk-info-panel'}, detail.infoRows.filter(function (row) {
                                return !['detail.version', 'detail.requires', 'detail.size'].includes(row.key);
                            }).map(function (row) {
                                return h('div', {key: row.key, class: 'pmk-info-row'}, [h('dt', t(row.key)),
                                    h('dd', {class: 'pmk-info-val'}, row.href
                                        ? h('a', {href: row.href, target: '_blank', rel: 'noopener noreferrer'}, t(row.key)) : row.val)]);
                            })),
                            h('div', {class: 'pmk-modal-actionbar-stats'}, [
                                stars(detail.ratingStars), detail.ratingNum ? h('span', detail.ratingNum) : null,
                                detail.downloadsLabel ? h('span', [icon('fa-solid fa-download'), ' ' + detail.downloadsLabel]) : null
                            ])
                        ], 'pmk-detail-disclosure'),
                        detail.tags.length ? h('div', {class: 'pmk-tags'}, detail.tags.map(function (tag) {
                            return h('span', {key: tag, class: 'pmk-tag'}, '#' + tag);
                        })) : null
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
                    PMK.operations ? h('span', { class: 'pmk-operations-trigger', ref: PMK.operations.mountButton }) : null,
                    h('button', { class: 'pmk-btn pmk-btn--teal', onClick: vm.reload, disabled: vm.loading }, [icon('fa-solid fa-rotate'), h('span', t('refresh', '刷新'))])
                ])
            ]),
            PMK.operations ? h('div', { ref: PMK.operations.mountPanel }) : null,
            vm.loading ? loading() : vm.error ? h('div', { class: 'pmk-banner pmk-banner--error' },
                [icon('fa-solid fa-triangle-exclamation'), h('div', { class: 'pmk-banner-body' }, vm.error)]) : [
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
        var Content = PMK.content.component(Vue);
        return {
            render: function () { return renderMarket(Vue, this, Content); },
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
                    selectedFacts: null,
                    detailToken: 0,
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
                this.catalogToken++;
                if (PMK.api.cancelCatalog) PMK.api.cancelCatalog();
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
                    if (PMK.api.cancelCatalog) PMK.api.cancelCatalog();
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
                    this.loadingMore = false;
                    this.catalogLoading = true; this.catalogError = null;
                    PMK.api.fetchCatalog(repoId).then(function (cat) {
                        if (token !== self.catalogToken) return;   // 仓库已切换，丢弃旧仓库的 catalog 响应
                        self.catalog = cat;
                        self.catalogLoading = false;
                    }).catch(function (failure) {
                        if (token !== self.catalogToken) return;
                        if (failure.name === 'AbortError') return;
                        self.catalog = null;
                        self.catalogError = self.t('error.catalog', '无法加载该仓库的插件清单，请检查仓库状态或稍后重试。');
                        self.catalogLoading = false;
                    });
                },
                loadMore: function () {
                    var self = this;
                    if (!this.catalog || !this.catalog.nextCursor || this.loadingMore) return;
                    var token = this.catalogToken;
                    var repository = this.activeRepositoryId;
                    this.loadingMore = true;
                    PMK.api.fetchCatalog(this.activeRepositoryId, { cursor: this.catalog.nextCursor }).then(function (page) {
                        if (token !== self.catalogToken || repository !== self.activeRepositoryId) return;
                        if (!self.catalog || page.generation !== self.catalog.generation) {
                            self.loadCatalog(self.activeRepositoryId); return;
                        }
                        self.catalog.entries = PMK.data.mergeEntries(self.catalog.entries, page.entries);
                        self.catalog.nextCursor = page.nextCursor;
                    }).catch(function (failure) {
                        if (token !== self.catalogToken || failure.name === 'AbortError') return;
                        PMK.toast(self.t('error.catalog', '无法加载该仓库的插件清单，请检查仓库状态或稍后重试。'), 'error');
                    }).finally(function () { if (token === self.catalogToken) self.loadingMore = false; });
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
                    this.detailReturnFocus = document.activeElement;
                    var token = ++this.detailToken;
                    this.detailLoadingMore = false;
                    var repository = this.activeCatalogRepositoryId;
                    this.selectedPluginId = pluginId;
                    var entry = this.selectedEntry;
                    this.selectedDetail = entry;
                    this.selectedVersion = PMK.data.defaultVersion(entry);
                    this.selectedFacts = null;
                    document.body.style.overflow = 'hidden';
                    PMK.api.fetchPluginDetail(repository, pluginId).then(function (detail) {
                        if (token !== self.detailToken || self.activeCatalogRepositoryId !== repository || self.selectedPluginId !== pluginId) return;
                        self.selectedDetail = detail;
                        self.selectedVersion = PMK.data.resolveVersion(detail, self.selectedVersion);
                        self.loadPackageFacts();
                    }).catch(function () {
                        if (token !== self.detailToken || self.activeCatalogRepositoryId !== repository) return;
                        PMK.toast(self.t('error.detail', '无法加载插件详情，请稍后重试。'), 'error');
                    });
                },
                loadPackageFacts: function () {
                    var self = this;
                    var token = this.detailToken;
                    var repository = this.activeCatalogRepositoryId;
                    var plugin = this.selectedPluginId;
                    var version = this.selectedVersion;
                    this.selectedFacts = null;
                    if (!plugin || !version) return;
                    PMK.api.fetchPackageFacts(repository, plugin, version).then(function (facts) {
                        if (token === self.detailToken && self.activeCatalogRepositoryId === repository && self.selectedPluginId === plugin
                            && self.selectedVersion === version) self.selectedFacts = facts;
                    }).catch(function () {
                        if (token === self.detailToken && self.activeCatalogRepositoryId === repository && self.selectedPluginId === plugin && self.selectedVersion === version)
                            PMK.toast(self.t('error.detail', '无法加载插件详情，请稍后重试。'), 'error');
                    });
                },
                showReleaseNotes: function (version) {
                    this.selectedVersion = version;
                    this.loadPackageFacts();
                    var self = this;
                    this.$nextTick(function () {
                        var section = self.$el.querySelector('[data-content-kind="releaseNotes"]');
                        if (section) { section.open = true; section.querySelector('summary').focus(); section.scrollIntoView({ block: 'nearest' }); }
                    });
                },
                loadMoreVersions: function () {
                    var self = this;
                    var detail = this.selectedDetail;
                    if (!detail || !detail.nextVersionCursor || this.detailLoadingMore) return;
                    var token = this.detailToken;
                    var repository = this.activeCatalogRepositoryId;
                    this.detailLoadingMore = true;
                    PMK.api.fetchPluginDetail(repository, detail.pluginId,
                        { cursor: detail.nextVersionCursor }).then(function (page) {
                        if (token !== self.detailToken || self.activeCatalogRepositoryId !== repository) return;
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
                        if (token !== self.detailToken || self.activeCatalogRepositoryId !== repository) return;
                        PMK.toast(self.t('error.detail', '无法加载插件详情，请稍后重试。'), 'error');
                    }).finally(function () { if (token === self.detailToken) self.detailLoadingMore = false; });
                },
                closeDetail: function () {
                    this.detailToken++;
                    this.detailLoadingMore = false;
                    this.selectedPluginId = null;
                    this.selectedDetail = null;
                    this.selectedVersion = null;
                    document.body.style.overflow = '';
                    var target = this.detailReturnFocus;
                    this.$nextTick(function () { if (target && target.isConnected) target.focus(); });
                },
                onKeydown: function (e) {
                    if (e.key === 'Escape' && this.selectedPluginId && !document.querySelector('.pixiv-feedback-backdrop')) {
                        e.preventDefault(); this.closeDetail();
                    }
                },
                // 安装态键控：(repositoryId, pluginId) 复合键，使同名插件在不同仓库间互不污染。
                installKey: function (repositoryId, pluginId) {
                    return String(repositoryId) + '\u0000' + String(pluginId);
                },
                // 卡片安装控件的有效状态：本地安装中 / 后端安装终态 / 否则 catalog 安装状态（按卡片同源仓库键控）。
                cardStatus: function (card) {
                    var key = this.installKey(card.repositoryId, card.pluginId);
                    if (this.installing[key]) return 'INSTALLING';
                    var result = this.installResults[key];
                    return PMK.data.installResultStatus(result && result.version === card.targetVersion ? result : null, card.installStatus);
                },
                cardMeta: function (card) { return PMK.installMeta(this.cardStatus(card)); },
                showCardRating: function (card) { return !!(card.ratingStars || card.downloadsLabel); },
                showCardMeta: function (card) { return !!(card.versionLabel || card.sizeLabel || card.dateLabel); },
                showCardCompat: function (card) { return !card.compatible && !!card.compatibilityReason; },
                showCardVerification: function (card) { return !!(card && card.verificationBadge); },
                cardLabel: function (card) {
                    var status = this.cardStatus(card);
                    if (status === 'UPDATE_AVAILABLE') return this.t('install.action.update-to', '更新到 v{v}', { v: card.targetVersion });
                    return this.installLabelText(status);
                },
                install: function (card) {
                    this.doInstall(card.repositoryId, card.pluginId, card.targetVersion);
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
                    var trigger = document.activeElement;
                    var focusScope = trigger && trigger.closest && trigger.closest('.pmk-modal, .pmk-card');
                    this.installing[key] = 'preview';
                    delete this.installResults[key];
                    PMK.installPluginWithConfirmation(repositoryId, pluginId, version, function (phase) { self.installing[key] = phase; }, self.catalog && self.catalog.entries).then(function (res) {
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
                        self.$nextTick(function () {
                            if (!focusScope || !focusScope.isConnected || document.activeElement !== document.body) return;
                            var target = focusScope.querySelector('.pmk-install:not(:disabled)')
                                || focusScope.querySelector('.pmk-hero-close, .pmk-card-name');
                            if (target) target.focus({preventScroll: true});
                        });
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
                    var pkg = PMK.data.packageOf(entry, this.selectedVersion);
                    if (!pkg) return entry.installStatus;   // 无可安装版本制品 → 沿用后端状态（UNAVAILABLE / 已安装）
                    var status = PMK.data.selectedInstallStatus(entry, pkg, this.selectedFacts, true);
                    return PMK.data.installResultStatus(result && result.version === pkg.version ? result : null, status);
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
                    rows.push({key: 'detail.installed-version', val: entry.installedVersion || this.t('common:plugin-info.not-installed')});
                    if (m.updatedTime) rows.push({ key: 'detail.updated', val: PMK.formatDate(m.updatedTime) });
                    var size = pkg ? PMK.formatSize(pkg.expectedSizeBytes) : null;
                    if (size) rows.push({ key: 'detail.size', val: size, mono: true });
                    if (pkg && pkg.requiredSdk) {
                        rows.push({ key: 'detail.requires', val: pkg.requiredSdk, mono: true, danger: !pkg.compatible });
                    }
                    rows.push({
                        key: 'detail.compatible',
                        val: !pkg || typeof pkg.compatible !== 'boolean' ? this.t('common:plugin-info.unknown')
                            : !pkg.compatible ? this.t('detail.incompatible', '不兼容') : this.t('detail.compatible-yes', '兼容'),
                        danger: !!(pkg && !pkg.compatible)
                    });
                    if (m.license) rows.push({ key: 'detail.license', val: m.license, mono: true });
                    rows.push({
                        key: 'detail.effect',
                        val: pkg && pkg.effectiveAfterRestart === true
                            ? this.t('detail.restart-required', '重启后生效')
                            : this.t('detail.effect-at-install')
                    });

                    var entries = this.catalog && this.catalog.entries || [];
                    var deps = (pkg && pkg.dependencies || []).map(function (dependency) {
                        return PMK.data.dependencyLabel(dependency, entries);
                    });
                    var versions = (entry.packages || []).map(function (p) {
                        return {
                            version: p.version,
                            dateLabel: p.releasedTime ? PMK.formatDate(p.releasedTime) : '',
                            notes: p.changeNotes || [],
                            hasReleaseNotes: !!PMK.localeKey(p.content && p.content.releaseNotes, m.defaultLocale),
                            channel: p.channel,
                            deprecated: p.deprecated
                        };
                    });
                    return {
                        pluginId: entry.pluginId,
                        repositoryId: this.activeCatalogRepositoryId, icon: card.icon,
                        content: PMK.content.model(this.activeCatalogRepositoryId, entry, pkg),
                        name: card.name, sub: card.sub, iconClass: card.iconClass, colorClass: card.colorClass,
                        categoryLabel: card.categoryLabel, categoryIcon: card.categoryIcon, official: card.official,
                        assuranceLabel: PMK.data.assuranceLabel(pkg && pkg.verification && pkg.verification.assuranceLevel),
                        ratingStars: card.ratingStars, ratingNum: card.ratingNum, downloadsLabel: card.downloadsLabel,
                        description: PMK.data.entryDescription(entry), summary: card.desc, author: m.author, tags: card.tags,
                        installedVersion: entry.installedVersion, restartRequired: !!(pkg && pkg.effectiveAfterRestart === true),
                        compatibilityNotice: card.compatibilityNotice,
                        installationNotice: PMK.data.installationNotice(entry, pkg),
                        localArtifactFields: PMK.data.localArtifactFields(entry),
                        trustSections: global.PixivPluginPresentationTokens.trustSections(
                            this.selectedFacts || (pkg && pkg.verification), PMK.state.i18n.client),
                        artifactFields: [
                            {label: 'SHA-256', value: pkg && pkg.sha256 || this.t('common:plugin-info.unknown')},
                            {label: this.t('common:plugin-info.id'), value: entry.pluginId}
                        ],
                        nextVersionCursor: entry.nextVersionCursor, versions: versions, dependencies: deps, infoRows: rows, verificationBadge: verificationBadge
                    };
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
