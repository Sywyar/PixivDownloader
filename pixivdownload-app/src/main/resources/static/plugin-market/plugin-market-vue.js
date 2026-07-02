'use strict';
/*
 * 插件市场页 Vue reactive 视图（主渲染路径）：用 window.PixivVue 懒加载核心 Vue 运行时，把整个市场页内容
 * （标题区 + 分段控件 + 受信仓库行 + 分类侧栏 + 筛选 / 搜索 / 排序 + 卡片网格 + 详情弹窗 + 安装状态）渲染为
 * 数据驱动的 reactive 组件。Vue 缺失 / 加载失败 / 挂载抛错时 tryMount 收敛为返回 false，由 init 回退命令式渲染。
 *
 * 安全：模板只用 {{ }} 文本插值（Vue 自动转义）与 :class（取自 core 的受控 token 白名单），<b>不内联任意 HTML</b>
 * （无 HTML 注入指令）；主页外链已由后端净化为 http/https，再以 :href 绑定 + rel="noopener"。安装只按受控 repositoryId+pluginId+version
 * 发起，绝不传任意 URL；安装结果一律以后端响应为准（前端只有「安装中 / 待重启」本地态）。
 */
(function (global) {
    var PMK = global.PixivPluginMarket;
    var VUE = PMK.vue = {};

    var TEMPLATE = [
'<div class="pmk-page">',
'  <div class="pmk-titlebar">',
'    <div>',
'      <h1 class="pmk-title"><i class="fa-solid fa-store"></i><span>{{ t(\'page.heading\', \'插件市场\') }}</span></h1>',
'      <p class="pmk-subtitle">{{ t(\'page.subtitle\', \'从受信仓库浏览并安装插件\') }}</p>',
'    </div>',
'    <div class="pmk-titlebar-actions">',
'      <div class="pmk-seg" role="tablist">',
'        <span class="pmk-seg-item active"><i class="fa-solid fa-store"></i><span>{{ t(\'seg.market\', \'市场\') }}</span></span>',
'        <a class="pmk-seg-item" href="/plugin-manage.html">',
'          <i class="fa-solid fa-puzzle-piece"></i><span>{{ t(\'seg.installed\', \'已安装\') }}</span>',
'          <span class="pmk-seg-count">{{ installedCount }}</span>',
'        </a>',
'      </div>',
'      <button class="pmk-btn pmk-btn--teal" @click="reload" :disabled="loading">',
'        <i class="fa-solid fa-rotate"></i><span>{{ t(\'refresh\', \'刷新\') }}</span>',
'      </button>',
'    </div>',
'  </div>',
'',
'  <div v-if="loading" class="pmk-state"><i class="fa-solid fa-spinner fa-spin"></i><span>{{ t(\'loading\', \'正在加载…\') }}</span></div>',
'  <div v-else-if="error" class="pmk-banner pmk-banner--error"><i class="fa-solid fa-triangle-exclamation"></i><div class="pmk-banner-body">{{ error }}</div></div>',
'',
'  <template v-else>',
'    <div v-if="!masterEnabled" class="pmk-banner pmk-banner--warn">',
'      <i class="fa-solid fa-circle-exclamation"></i>',
'      <div class="pmk-banner-body">',
'        <div class="pmk-banner-title">{{ t(\'master.disabled.title\', \'插件市场未开启\') }}</div>',
'        <div>{{ t(\'master.disabled.desc\', \'请在配置中开启受信 catalog 后再浏览仓库与安装插件。\') }}</div>',
'      </div>',
'    </div>',
'',
'    <div v-if="repositories.length" class="pmk-repos">',
'      <span class="pmk-repos-label">{{ t(\'section.repositories\', \'受信仓库\') }}</span>',
'      <button v-for="repo in repositories" :key="repo.repositoryId" class="pmk-repo-chip"',
'              :class="{active: repo.repositoryId === activeRepositoryId}" :disabled="!repo.enabled"',
'              :title="repoTitle(repo)" @click="switchRepository(repo)">',
'        <i class="fa-solid" :class="repo.official ? \'fa-circle-check\' : \'fa-folder\'"></i>',
'        <span class="pmk-repo-chip-name">{{ repo.repositoryId }}</span>',
'        <span v-if="repo.repositoryId === activeRepositoryId" class="pmk-repo-chip-meta">{{ t(\'repo.active\', \'当前\') }}</span>',
'        <span v-else-if="!repo.enabled" class="pmk-repo-chip-meta">{{ t(\'repo.disabled\', \'已禁用\') }}</span>',
'        <span v-else-if="!repo.proxyPolicySupported" class="pmk-repo-chip-meta">{{ t(\'repo.proxy.unsupported\', \'代理不支持\') }}</span>',
'      </button>',
'    </div>',
'',
'    <div class="pmk-banner pmk-banner--warn pmk-security-notice">',
'      <i class="fa-solid fa-shield-halved"></i>',
'      <div class="pmk-banner-body">{{ t(\'security.notice\', \'安全提示：无法验证、未签名或由用户放行的插件会在本机进程内运行代码。安装前请自行确认来源与安全性；我们无法保证未验证插件的安全。\') }}</div>',
'    </div>',
'',
'    <div v-if="showCatalogLoading" class="pmk-state"><i class="fa-solid fa-spinner fa-spin"></i><span>{{ t(\'loading\', \'正在加载…\') }}</span></div>',
'    <div v-else-if="showCatalogError" class="pmk-banner pmk-banner--error">',
'      <i class="fa-solid fa-triangle-exclamation"></i>',
'      <div class="pmk-banner-body"><div class="pmk-banner-title">{{ t(\'error.catalog.title\', \'无法加载插件清单\') }}</div><div>{{ catalogError }}</div></div>',
'    </div>',
'',
'    <div v-else-if="showBody" class="pmk-body">',
'      <aside class="pmk-sidebar">',
'        <div class="pmk-side-card">',
'          <div class="pmk-side-label">{{ t(\'sidebar.browse\', \'浏览分类\') }}</div>',
'          <div class="pmk-cat-list">',
'            <button v-for="cat in categoryList" :key="cat.id" class="pmk-cat" :class="{active: cat.id === category}" @click="setCategory(cat.id)">',
'              <i :class="cat.icon"></i><span class="pmk-cat-name">{{ cat.label }}</span><span class="pmk-cat-count">{{ cat.count }}</span>',
'            </button>',
'          </div>',
'          <div class="pmk-side-divider"></div>',
'          <div class="pmk-side-label">{{ t(\'sidebar.filter\', \'筛选\') }}</div>',
'          <div class="pmk-filter">',
'            <span class="pmk-filter-label"><i class="fa-solid fa-circle-check pmk-fi-official"></i>{{ t(\'filter.official\', \'仅官方插件\') }}</span>',
'            <button class="pmk-switch" :class="{on: onlyOfficial}" :aria-pressed="onlyOfficial" @click="onlyOfficial = !onlyOfficial"></button>',
'          </div>',
'          <div class="pmk-filter">',
'            <span class="pmk-filter-label"><i class="fa-solid fa-plug-circle-check pmk-fi-compat"></i>{{ t(\'filter.compatible\', \'仅兼容当前版本\') }}</span>',
'            <button class="pmk-switch" :class="{on: onlyCompatible}" :aria-pressed="onlyCompatible" @click="onlyCompatible = !onlyCompatible"></button>',
'          </div>',
'        </div>',
'        <div class="pmk-version-card">',
'          <div class="pmk-version-label">{{ t(\'sidebar.core-api\', \'核心 API 版本\') }}</div>',
'          <div class="pmk-version-num">v{{ coreApiVersion }}</div>',
'          <div class="pmk-version-hint">{{ t(\'sidebar.core-api.hint\', \'标记为「不兼容」的插件需要更新应用后才能安装。\') }}</div>',
'        </div>',
'      </aside>',
'',
'      <div class="pmk-main">',
'        <div class="pmk-toolbar">',
'          <div class="pmk-toolbar-head">',
'            <span class="pmk-toolbar-title">{{ categoryLabel }}</span>',
'            <span class="pmk-toolbar-count">{{ t(\'toolbar.count\', \'{n} 款插件\', {n: cards.length}) }}</span>',
'          </div>',
'          <div class="pmk-search">',
'            <i class="fa-solid fa-magnifying-glass"></i>',
'            <input type="text" v-model="search" :placeholder="t(\'search.placeholder\', \'搜索插件、作者或标签…\')" autocomplete="off">',
'          </div>',
'          <span class="pmk-sort-label">{{ t(\'sort.label\', \'排序\') }}</span>',
'          <select class="pmk-sort" v-model="sort">',
'            <option v-for="opt in sortOptions" :key="opt" :value="opt">{{ t(\'sort.\' + opt, opt) }}</option>',
'          </select>',
'        </div>',
'',
'        <div v-if="cards.length" class="pmk-grid">',
'          <article v-for="card in cards" :key="card.pluginId" class="pmk-card" :class="card.colorClass">',
'            <div class="pmk-card-banner" @click="openDetail(card.pluginId)">',
'              <i class="pmk-card-banner-glyph" :class="card.iconClass"></i>',
'              <i class="pmk-card-banner-bg" :class="card.iconClass"></i>',
'              <span class="pmk-card-banner-cat"><i :class="card.categoryIcon"></i>{{ card.categoryLabel }}</span>',
'            </div>',
'            <div class="pmk-card-body">',
'              <div class="pmk-card-head">',
'                <span class="pmk-card-icon"><i :class="card.iconClass"></i></span>',
'                <div class="pmk-card-titleblock">',
'                  <div class="pmk-card-name-row">',
'                    <span class="pmk-card-name" @click="openDetail(card.pluginId)">{{ card.name }}</span>',
'                    <span v-if="card.official" class="pmk-badge pmk-badge--official">{{ t(\'badge.official\', \'官方\') }}</span>',
'                    <span v-else class="pmk-badge pmk-badge--community">{{ t(\'badge.community\', \'社区\') }}</span>',
'                    <span v-if="card.recommended" class="pmk-badge pmk-badge--recommended">{{ t(\'badge.recommended\', \'推荐\') }}</span>',
'                    <span v-if="showCardVerification(card)" class="pmk-verification-badge" :class="\'pmk-verification-badge--\' + card.verificationBadge.tone" :title="card.verificationBadge.title || null"><i class="fa-solid" :class="card.verificationBadge.icon"></i><span>{{ t(card.verificationBadge.labelKey, card.verificationBadge.status) }}</span></span>',
'                  </div>',
'                  <div class="pmk-card-sub">{{ card.sub }}</div>',
'                </div>',
'              </div>',
'              <div v-if="showCardRating(card)" class="pmk-rating">',
'                <span v-if="card.ratingStars" class="pmk-stars">',
'                  <i v-for="n in card.ratingStars.full" :key="\'f\'+n" class="fa-solid fa-star"></i>',
'                  <i v-for="n in card.ratingStars.half" :key="\'h\'+n" class="fa-solid fa-star-half-stroke"></i>',
'                  <i v-for="n in card.ratingStars.empty" :key="\'e\'+n" class="fa-regular fa-star"></i>',
'                </span>',
'                <span v-if="card.ratingNum" class="pmk-rating-num">{{ card.ratingNum }}</span>',
'                <span v-if="card.downloadsLabel" class="pmk-rating-dl"><i class="fa-solid fa-download"></i>{{ card.downloadsLabel }}</span>',
'              </div>',
'              <p v-if="card.desc" class="pmk-card-desc">{{ card.desc }}</p>',
'              <div v-if="card.tags.length" class="pmk-tags"><span v-for="tag in card.tags.slice(0,4)" :key="tag" class="pmk-tag">#{{ tag }}</span></div>',
'              <div v-if="showCardMeta(card)" class="pmk-card-meta">',
'                {{ [card.versionLabel, card.sizeLabel, card.dateLabel].filter(Boolean).join(\' · \') }}',
'              </div>',
'              <div v-if="showCardCompat(card)" class="pmk-card-compat">',
'                <i class="fa-solid fa-triangle-exclamation"></i>{{ t(\'compat.needs\', \'需要核心 API v{v}+（当前 v{cur}）\', {v: card.compatibilityReason, cur: coreApiVersion}) }}',
'              </div>',
'              <div class="pmk-card-actions">',
'                <div v-if="cardStatus(card) === \'INSTALLING\'" class="pmk-install-progress">',
'                  <div class="pmk-install-progress-label"><i class="fa-solid fa-spinner fa-spin"></i>{{ t(\'install.state.installing\', \'安装中…\') }}</div>',
'                  <div class="pmk-progressbar"><span></span></div>',
'                </div>',
'                <button v-else class="pmk-btn pmk-install" :class="\'pmk-btn--\' + cardMeta(card).variant"',
'                        :disabled="cardMeta(card).disabled" @click="install(card)">',
'                  <i class="fa-solid" :class="\'fa-\' + cardMeta(card).icon"></i><span>{{ cardLabel(card) }}</span>',
'                </button>',
'                <button class="pmk-btn pmk-btn--gray pmk-btn--sm" @click="openDetail(card.pluginId)">',
'                  <i class="fa-solid fa-circle-info"></i><span>{{ t(\'card.detail\', \'详情\') }}</span>',
'                </button>',
'              </div>',
'            </div>',
'          </article>',
'        </div>',
'        <div v-else class="pmk-empty">',
'          <i class="fa-solid fa-store-slash"></i>',
'          <div class="pmk-empty-title">{{ t(\'empty.title\', \'没有匹配的插件\') }}</div>',
'          <div class="pmk-empty-hint">{{ t(\'empty.hint\', \'试试切换分类、关闭筛选，或更换搜索关键词。\') }}</div>',
'        </div>',
'      </div>',
'    </div>',
'  </template>',
'',
'  <div class="pmk-disclaimer">{{ t(\'disclaimer\', \'插件运行于本地，仅供个人学习与研究使用；无法验证、未签名或用户放行的插件请自行确认来源与安全性，我们无法保证未验证插件的安全；请尊重创作者版权 · 本工具与 Pixiv 无任何关联\') }}</div>',
'',
'  <div v-if="detail" class="pmk-modal" @click.self="closeDetail">',
'    <div class="pmk-modal-panel" :class="detail.colorClass">',
'      <div class="pmk-hero">',
'        <i class="pmk-hero-bg" :class="detail.iconClass"></i>',
'        <span class="pmk-hero-cat"><i :class="detail.categoryIcon"></i>{{ detail.categoryLabel }}</span>',
'        <button class="pmk-hero-close" :aria-label="t(\'modal.close\', \'关闭\')" @click="closeDetail"><i class="fa-solid fa-xmark"></i></button>',
'        <span class="pmk-hero-icon"><i :class="detail.iconClass"></i></span>',
'        <div class="pmk-hero-titleblock">',
'          <div class="pmk-hero-name"><span>{{ detail.name }}</span><span class="pmk-hero-pill">{{ detail.official ? t(\'badge.official\', \'官方\') : t(\'badge.community\', \'社区\') }}</span></div>',
'          <div class="pmk-hero-sub">{{ detail.sub }}</div>',
'        </div>',
'      </div>',
'      <div class="pmk-modal-actionbar">',
'        <div class="pmk-modal-actionbar-stats">',
'          <span v-if="detail.ratingStars" class="pmk-stars">',
'            <i v-for="n in detail.ratingStars.full" :key="\'F\'+n" class="fa-solid fa-star"></i>',
'            <i v-for="n in detail.ratingStars.half" :key="\'H\'+n" class="fa-solid fa-star-half-stroke"></i>',
'            <i v-for="n in detail.ratingStars.empty" :key="\'E\'+n" class="fa-regular fa-star"></i>',
'          </span>',
'          <span v-if="detail.ratingNum" class="pmk-rating-num">{{ detail.ratingNum }}</span>',
'          <span v-if="detail.downloadsLabel"><i class="fa-solid fa-download"></i> {{ detail.downloadsLabel }}</span>',
'        </div>',
'        <div class="pmk-modal-actionbar-right">',
'          <select v-if="showVersionSelect" class="pmk-version-select" v-model="selectedVersion">',
'            <option v-for="v in detail.versions" :key="v.version" :value="v.version">v{{ v.version }}{{ v.channel && v.channel !== \'stable\' ? \' · \' + v.channel : \'\' }}</option>',
'          </select>',
'          <div v-if="modalStatus === \'INSTALLING\'" class="pmk-install-progress" style="min-width:200px">',
'            <div class="pmk-install-progress-label"><i class="fa-solid fa-spinner fa-spin"></i>{{ t(\'install.state.installing\', \'安装中…\') }}</div>',
'            <div class="pmk-progressbar"><span></span></div>',
'          </div>',
'          <button v-else class="pmk-btn" :class="\'pmk-btn--\' + modalMeta.variant" :disabled="modalMeta.disabled" @click="installModal">',
'            <i class="fa-solid" :class="\'fa-\' + modalMeta.icon"></i><span>{{ modalLabel }}</span>',
'          </button>',
'        </div>',
'      </div>',
'      <div class="pmk-modal-body">',
'        <div class="pmk-modal-col">',
'          <div>',
'            <div class="pmk-section-label">{{ t(\'detail.about\', \'简介\') }}</div>',
'            <div class="pmk-section-text">{{ detail.description || t(\'detail.no-description\', \'该插件暂无简介。\') }}</div>',
'          </div>',
'          <div v-if="installResultFor">',
'            <div class="pmk-section-label">{{ t(\'detail.install-result\', \'安装结果\') }}</div>',
'            <div class="pmk-install-result"><div class="pmk-install-result-box" :class="\'pmk-install-result-box--\' + installResultFor.tone">',
'              <div class="pmk-install-result-head">',
'                <i class="fa-solid" :class="installResultFor.accepted ? \'fa-circle-check\' : \'fa-circle-exclamation\'"></i>',
'                <span class="pmk-install-result-msg">{{ installResultFor.message }}</span>',
'                <span v-if="installResultFor.outcome" class="pmk-install-code">{{ installResultFor.outcome }}</span>',
'              </div>',
'              <div v-if="showRestartHint" class="pmk-install-restart">',
'                <i class="fa-solid fa-power-off"></i><span>{{ t(\'install.restart-hint\', \'需重启应用后生效。\') }}</span>',
'                <a href="/plugin-manage.html">{{ t(\'install.goto-manage\', \'前往插件管理\') }}</a>',
'              </div>',
'              <div v-if="installResultFor.warnings.length" class="pmk-install-list">',
'                <span>{{ t(\'install.unmet-deps\', \'尚未满足的依赖：\') }}</span>',
'                <ul><li v-for="w in installResultFor.warnings" :key="w">{{ w }}</li></ul>',
'              </div>',
'            </div></div>',
'          </div>',
'          <div>',
'            <div class="pmk-section-label">{{ t(\'detail.changelog\', \'更新日志\') }}</div>',
'            <div v-if="detail.versions.length" class="pmk-versions">',
'              <div v-for="v in detail.versions" :key="v.version" class="pmk-version-row">',
'                <div class="pmk-version-col"><span class="pmk-version-tag">v{{ v.version }}</span><div v-if="v.dateLabel" class="pmk-version-date">{{ v.dateLabel }}</div></div>',
'                <ul v-if="v.notes.length" class="pmk-version-notes"><li v-for="(note, i) in v.notes" :key="i">{{ note }}</li></ul>',
'                <div v-else class="pmk-version-notes pmk-version-empty">{{ t(\'detail.no-notes\', \'无更新说明。\') }}</div>',
'              </div>',
'            </div>',
'            <div v-else class="pmk-version-empty">{{ t(\'detail.no-versions\', \'暂无版本信息。\') }}</div>',
'          </div>',
'          <div v-if="detail.dependencies.length">',
'            <div class="pmk-section-label">{{ t(\'detail.dependencies\', \'依赖\') }}</div>',
'            <div class="pmk-deps"><span v-for="dep in detail.dependencies" :key="dep" class="pmk-dep">{{ dep }}</span></div>',
'          </div>',
'        </div>',
'        <div class="pmk-modal-col">',
'          <div v-if="showDetailVerification" class="pmk-detail-verification" :class="\'pmk-detail-verification--\' + detail.verificationBadge.tone" :title="detail.verificationBadge.title || null">',
'            <i class="fa-solid" :class="detail.verificationBadge.icon"></i>',
'            <div>',
'              <div class="pmk-detail-verification-title">{{ t(\'detail.verification\', \'来源验证\') }}</div>',
'              <div class="pmk-detail-verification-text">{{ t(detail.verificationBadge.labelKey, detail.verificationBadge.status) }}</div>',
'            </div>',
'          </div>',
'          <div class="pmk-info-panel">',
'            <div v-for="row in detail.infoRows" :key="row.key" class="pmk-info-row">',
'              <span class="pmk-info-key">{{ t(row.key, row.key) }}</span>',
'              <span class="pmk-info-val" :class="{\'pmk-info-val--mono\': row.mono, \'pmk-info-val--danger\': row.danger}" :title="row.title || null">',
'                <a v-if="row.href" :href="row.href" target="_blank" rel="noopener noreferrer">{{ row.val }}</a>',
'                <template v-else>{{ row.val }}</template>',
'              </span>',
'            </div>',
'          </div>',
'          <div v-if="detail.tags.length">',
'            <div class="pmk-section-label">{{ t(\'detail.tags\', \'标签\') }}</div>',
'            <div class="pmk-tags"><span v-for="tag in detail.tags" :key="tag" class="pmk-tag">#{{ tag }}</span></div>',
'          </div>',
'        </div>',
'      </div>',
'    </div>',
'  </div>',
'</div>'
    ].join('\n');

    function component() {
        return {
            template: TEMPLATE,
            data: function () {
                return {
                    i18nRev: 0,
                    loading: true,
                    catalogLoading: false,
                    error: null,
                    catalogError: null,
                    masterEnabled: false,
                    coreApiVersion: '',
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
                    onlyOfficial: false,
                    onlyCompatible: false,
                    selectedPluginId: null,
                    selectedVersion: null,
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
                        category: this.category, search: this.search, onlyOfficial: this.onlyOfficial,
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
                installedCount: function () { return this.catalog ? this.catalog.installedCount : 0; },
                // 当前展示 catalog 所属仓库 id（安装状态键控与安装请求的同一来源）。
                activeCatalogRepositoryId: function () { return this.catalog ? this.catalog.repositoryId : null; },
                selectedEntry: function () {
                    var id = this.selectedPluginId;
                    if (!id) return null;
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
                // 模板 v-if / v-else-if 条件一律走方法 / 计算属性（规避 vue.global.prod 编译器对成员链 && / || 条件的
                // 静态折叠崩溃，详见 docs/claude/gui.md「Vue 全局 prod 构建模板编译器」一节）。
                showCatalogLoading: function () { return this.masterEnabled && this.catalogLoading; },
                showCatalogError: function () { return this.masterEnabled && !!this.catalogError; },
                showBody: function () { return this.masterEnabled && !!this.catalog; },
                showVersionSelect: function () { return !!this.detail && this.detail.versions.length > 1; },
                showDetailVerification: function () { return !!this.detail && !!this.detail.verificationBadge; },
                showRestartHint: function () { var r = this.installResultFor; return !!r && r.accepted && r.effectiveAfterRestart; }
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
                    PMK.api.fetchRepositories().then(function (repos) {
                        if (token !== self.reloadToken) return;   // 已有更新的 reload，丢弃旧响应
                        self.masterEnabled = !!repos.enabled;
                        self.coreApiVersion = repos.coreApiVersion || '';
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
                switchRepository: function (repo) {
                    if (!repo.enabled || repo.repositoryId === this.activeRepositoryId) return;
                    this.activeRepositoryId = repo.repositoryId;
                    this.category = 'all'; this.search = '';
                    this.loadCatalog(repo.repositoryId);
                },
                setCategory: function (id) { this.category = id; },
                openDetail: function (pluginId) {
                    this.selectedPluginId = pluginId;
                    var entry = this.selectedEntry;
                    this.selectedVersion = entry ? entry.latestVersion : null;
                    document.body.style.overflow = 'hidden';
                },
                closeDetail: function () {
                    this.selectedPluginId = null;
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
                // 卡片安装控件的有效状态：本地安装中 / 安装成功待重启 / 否则后端安装状态（按卡片同源仓库键控）。
                cardStatus: function (card) {
                    var key = this.installKey(card.repositoryId, card.pluginId);
                    if (this.installing[key]) return 'INSTALLING';
                    var r = this.installResults[key];
                    if (r && r.activated) return 'ACTIVATED';
                    if (r && r.accepted && r.effectiveAfterRestart) return 'PENDING_RESTART';
                    return card.installStatus;
                },
                cardMeta: function (card) { return PMK.installMeta(this.cardStatus(card)); },
                // 卡片 v-if 条件走方法（同上，规避 prod 编译器成员链 && / || 静态折叠崩溃）。
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
                        this.doInstall(this.activeCatalogRepositoryId, this.selectedPluginId, this.selectedVersion);
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
                    PMK.api.installPlugin(repositoryId, pluginId, version).then(function (res) {
                        var model = res.kind === 'install'
                            ? PMK.data.installResult(res.body)
                            : PMK.data.catalogError(res.body, res.httpStatus);
                        self.installResults[key] = model;
                        self.recordDependencyInstallResults(repositoryId, model);
                        if (model.activated) {
                            PMK.toast(self.t('install.toast.activated', '已安装并激活。'), 'ok');
                        } else if (model.rolledBack) {
                            PMK.toast(self.t('install.toast.rolled-back', '激活失败，已恢复原版本。'), 'error');
                        } else if (model.accepted) {
                            PMK.toast(self.t('install.toast.accepted', '已安装。'), 'ok');
                        } else {
                            PMK.toast(self.t('install.toast.rejected', '未安装：{message}', { message: model.message || model.outcome || '' }), 'error');
                        }
                    }).catch(function () {
                        self.installResults[key] = {
                            tone: 'bad', accepted: false, effectiveAfterRestart: false, outcome: null,
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
                    if (result && result.activated) return 'ACTIVATED';
                    if (result && result.accepted && result.effectiveAfterRestart) return 'PENDING_RESTART';
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
                    if (pkg && pkg.requiredCoreApi) {
                        rows.push({ key: 'detail.requires', val: pkg.requiredCoreApi, mono: true, danger: !pkg.compatible });
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
            var app = Vue.createApp(component());
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
