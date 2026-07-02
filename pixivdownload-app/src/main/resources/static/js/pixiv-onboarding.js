/*
 * 新用户引导（跨页持续指引）运行时。
 *
 * 一个共享的状态机，被下载页（batch）、图片画廊（gallery）、作品详情页（artwork）共同引入；
 * 跨页进度存于 localStorage（同源多标签共享），各页加载后调用 PixivOnboarding.boot(config)
 * 续跑对应阶段：
 *   welcome  —— 下载页：欢迎 + 询问称呼 + 网络可达检测
 *   download —— 下载页：先逐区域认识页面（Cookie / 油猴脚本 / Tab 下载方式 → 选用「批量导入单作品」），
 *               再引导下载第一份示例作品（粘贴示例链接 → 入队 → 介绍附加筛选/下载设置 → 开始 → 监听结果）
 *   await-gallery / gallery —— 高亮首个下载结果入口 → 结果页逐区域讲解 → 高亮刚下载的作品 → 引导点进详情
 *   detail   —— 详情页：逐区域讲解（作品图 / 功能区 / 简介区 / 作者区 / 系列相关）→ 完成
 *
 * 文案走 i18n（tour 命名空间，onboarding.* 前缀），同时内置中文兜底；深色模式由 pixiv-onboarding.css
 * 的 CSS 变量自动跟随。仅供有「全局可见」权限（solo / 已登录管理员）的用户首次自动触发。
 */
(function (global) {
    'use strict';

    var STORAGE_KEY = 'pixiv_onboarding_v1';
    var EXAMPLE_ID = '145378118';
    var EXAMPLE_URL = 'https://www.pixiv.net/artworks/' + EXAMPLE_ID;
    var SPOT_PADDING = 8;
    var POP_GAP = 14;
    var VIEWPORT_MARGIN = 12;

    var i18n = null;

    function t(key, fallback, vars) {
        var full = key.indexOf(':') >= 0 ? key : 'tour:' + key;
        if (i18n && typeof i18n.t === 'function') {
            return i18n.t(full, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function interpolate(template, vars) {
        if (!vars) {
            return String(template);
        }
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, function (m, name) {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : m;
        });
    }

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // ── 跨页状态 ────────────────────────────────────────────────────────────────
    function loadState() {
        try {
            var raw = global.localStorage.getItem(STORAGE_KEY);
            if (!raw) {
                return {status: 'new', phase: 'welcome', name: ''};
            }
            var s = JSON.parse(raw);
            if (!s || typeof s !== 'object') {
                return {status: 'new', phase: 'welcome', name: ''};
            }
            return s;
        } catch (e) {
            return {status: 'new', phase: 'welcome', name: ''};
        }
    }

    function saveState(s) {
        try {
            global.localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
        } catch (e) {
            /* 隐私模式等场景静默降级 */
        }
    }

    function patchState(patch) {
        var s = loadState();
        Object.keys(patch).forEach(function (k) {
            s[k] = patch[k];
        });
        saveState(s);
        return s;
    }

    function markCompleted() {
        patchState({status: 'completed'});
    }

    // ── 覆盖层（聚光 + 富 HTML 气泡） ──────────────────────────────────────────────
    function Overlay() {
        this.root = null;
        this.spot = null;
        this.pop = null;
        this.interactiveEl = null;
        this.targetSelector = null;
        this._onReposition = this._reposition.bind(this);
        this._repositionTimer = null;
    }

    Overlay.prototype.ensure = function () {
        if (this.root) {
            return;
        }
        var root = document.createElement('div');
        root.className = 'po-root';
        var backdrop = document.createElement('div');
        backdrop.className = 'po-backdrop';
        var spot = document.createElement('div');
        spot.className = 'po-spot';
        var pop = document.createElement('div');
        pop.className = 'po-pop';
        pop.setAttribute('role', 'dialog');
        pop.setAttribute('aria-modal', 'true');
        root.appendChild(backdrop);
        root.appendChild(spot);
        root.appendChild(pop);
        document.body.appendChild(root);
        this.root = root;
        this.spot = spot;
        this.pop = pop;
        global.addEventListener('resize', this._onReposition, true);
        global.addEventListener('scroll', this._onReposition, true);
        // 目标可能因异步渲染稍后出现 / 布局变化，持续轻量重定位
        this._repositionTimer = global.setInterval(this._onReposition, 400);
    };

    /**
     * 渲染一帧。
     * @param opts {targetSelector, interactiveSelector, html, centered, scrollTarget}
     */
    Overlay.prototype.render = function (opts) {
        this.ensure();
        this.targetSelector = opts.targetSelector || null;
        this.interactiveSelector = opts.interactiveSelector || null;
        this.centered = !!opts.centered;
        this.root.classList.toggle('po-centered', this.centered);
        this.pop.classList.toggle('po-modal', this.centered);
        this.pop.innerHTML = opts.html || '';
        this.pop.classList.remove('po-in');
        var el = this.targetSelector ? document.querySelector(this.targetSelector) : null;
        if (el && el.scrollIntoView && opts.scrollTarget !== false) {
            try {
                el.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'smooth'});
            } catch (e) {
                el.scrollIntoView();
            }
        }
        var self = this;
        global.requestAnimationFrame(function () {
            global.setTimeout(function () {
                self._reposition();
                self.pop.classList.add('po-in');
            }, 60);
        });
    };

    Overlay.prototype._clearInteractive = function () {
        if (this.interactiveEl) {
            this.interactiveEl.classList.remove('po-interactive');
            this.interactiveEl = null;
        }
    };

    Overlay.prototype._reposition = function () {
        if (!this.root || !this.spot) {
            return;
        }
        var inter = this.interactiveSelector ? document.querySelector(this.interactiveSelector) : null;
        if (inter !== this.interactiveEl) {
            this._clearInteractive();
            if (inter) {
                inter.classList.add('po-interactive');
                this.interactiveEl = inter;
            }
        }
        if (this.centered) {
            return; // 居中模态：CSS 已固定，无需定位聚光
        }
        var el = inter || (this.targetSelector ? document.querySelector(this.targetSelector) : null);
        var vw = document.documentElement.clientWidth;
        var vh = document.documentElement.clientHeight;
        var rect;
        if (el) {
            var r = el.getBoundingClientRect();
            rect = {
                top: r.top - SPOT_PADDING,
                left: r.left - SPOT_PADDING,
                width: r.width + SPOT_PADDING * 2,
                height: r.height + SPOT_PADDING * 2
            };
        } else {
            rect = {top: vh / 2, left: vw / 2, width: 0, height: 0};
        }
        this.spot.style.top = rect.top + 'px';
        this.spot.style.left = rect.left + 'px';
        this.spot.style.width = rect.width + 'px';
        this.spot.style.height = rect.height + 'px';

        var pop = this.pop;
        var pw = pop.offsetWidth;
        var ph = pop.offsetHeight;
        var spaceBelow = vh - (rect.top + rect.height);
        var top;
        if (spaceBelow >= ph + POP_GAP || spaceBelow >= rect.top) {
            top = rect.top + rect.height + POP_GAP;
        } else {
            top = rect.top - ph - POP_GAP;
        }
        top = Math.max(VIEWPORT_MARGIN, Math.min(top, vh - ph - VIEWPORT_MARGIN));
        var left = rect.left + rect.width / 2 - pw / 2;
        left = Math.max(VIEWPORT_MARGIN, Math.min(left, vw - pw - VIEWPORT_MARGIN));
        pop.style.top = top + 'px';
        pop.style.left = left + 'px';
    };

    Overlay.prototype.qs = function (sel) {
        return this.pop ? this.pop.querySelector(sel) : null;
    };

    Overlay.prototype.destroy = function () {
        if (this._repositionTimer) {
            global.clearInterval(this._repositionTimer);
            this._repositionTimer = null;
        }
        global.removeEventListener('resize', this._onReposition, true);
        global.removeEventListener('scroll', this._onReposition, true);
        this._clearInteractive();
        if (this.root && this.root.parentNode) {
            this.root.parentNode.removeChild(this.root);
        }
        this.root = this.spot = this.pop = null;
    };

    // 单例覆盖层
    var overlay = new Overlay();

    function footHtml(buttons, progress) {
        var parts = ['<div class="po-foot">'];
        if (progress) {
            parts.push('<span class="po-progress">' + escapeHtml(progress) + '</span>');
        }
        buttons.forEach(function (b) {
            var cls = 'po-btn' + (b.variant ? ' po-btn-' + b.variant : '');
            var dis = b.disabled ? ' disabled' : '';
            parts.push('<button type="button" class="' + cls + '" data-act="' + b.act + '"' + dis + '>'
                + escapeHtml(b.label) + '</button>');
        });
        parts.push('</div>');
        return parts.join('');
    }

    function bindFoot(handlers) {
        if (!overlay.pop) {
            return;
        }
        overlay.pop.querySelectorAll('[data-act]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var act = btn.getAttribute('data-act');
                if (handlers[act]) {
                    handlers[act]();
                }
            });
        });
    }

    function finish() {
        markCompleted();
        overlay.destroy();
        showFab();
    }

    function skip() {
        markCompleted();
        overlay.destroy();
        showFab();
    }

    var SKIP_BTN = function () {
        return {act: 'skip', label: t('onboarding.common.skip', '跳过指引'), variant: 'ghost'};
    };

    // ── 右下角「操作指引」FAB（仅下载页注册；复用 pixiv-tour.css 的 .pt-help-fab 样式） ────────
    // 取代旧版 PixivTour 的 FAB：点击重跑跨页新手向导（已保存称呼则直接跳到连通性检测）。
    var fabEl = null;

    function ensureFab() {
        if (fabEl || !config || config.page !== 'batch') {
            return;
        }
        var fab = document.createElement('button');
        fab.type = 'button';
        fab.className = 'pt-help-fab po-help-fab';
        fab.innerHTML = '<span aria-hidden="true">💡</span><span class="po-help-fab-label"></span>';
        fab.addEventListener('click', restart);
        document.body.appendChild(fab);
        fabEl = fab;
        refreshFabLabel();
    }

    function refreshFabLabel() {
        if (!fabEl) {
            return;
        }
        var label = t('common.help', '操作指引');
        var labelEl = fabEl.querySelector('.po-help-fab-label');
        if (labelEl) {
            labelEl.textContent = label;
        }
        fabEl.setAttribute('aria-label', label);
        fabEl.title = label;
    }

    function showFab() {
        if (fabEl) {
            fabEl.hidden = false;
        }
    }

    function hideFab() {
        if (fabEl) {
            fabEl.hidden = true;
        }
    }

    // 从 FAB 重跑向导：重置进度回欢迎阶段，再走一遍下载页流程（已保存称呼则跳过称呼步）。
    function restart() {
        if (!config || config.page !== 'batch') {
            return;
        }
        overlay.destroy();
        hideFab();
        _completionStepNotified = false;
        patchState({status: 'active', phase: 'welcome'});
        phaseWelcome();
    }

    // 通知后端当前网页操作指引已完成（GUI 引导据此推进），best-effort、每次向导仅发一次。
    var _completionStepNotified = false;

    function notifyCompletionStepDone() {
        var stepId = config && config.completionStepId;
        if (!stepId || _completionStepNotified) {
            return;
        }
        _completionStepNotified = true;
        try {
            fetch('/api/onboarding/steps/' + encodeURIComponent(stepId) + '/complete', {
                method: 'POST',
                credentials: 'same-origin'
            }).catch(function () { /* best-effort */ });
        } catch (e) {
            /* ignore */
        }
    }

    // ── 当前页配置 ──────────────────────────────────────────────────────────────
    var config = null;

    function hook(name) {
        return config && config.hooks && typeof config.hooks[name] === 'function'
            ? config.hooks[name] : null;
    }

    function callHook(name, arg) {
        var fn = hook(name);
        return fn ? fn(arg) : undefined;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  下载页阶段
    // ════════════════════════════════════════════════════════════════════════════

    // 阶段：欢迎 + 称呼 + 网络检测（居中模态，内部多屏）
    function phaseWelcome() {
        patchState({status: 'active', phase: 'welcome'});
        // 已保存过称呼（本地或服务端）则跳过称呼步，直接进入连通性检测
        if (hasSavedName()) {
            screenNetwork();
        } else {
            screenName();
        }
    }

    function hasSavedName() {
        var local = loadState().name;
        if (local && local.trim()) {
            return true;
        }
        return !!(config && config.savedName && config.savedName.trim());
    }

    function screenName() {
        var existing = loadState().name || '';
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.welcome.title', '👋 欢迎使用 PixivDownloader')) + '</h3>'
                + '<div class="po-pop-body">'
                + '<p>' + escapeHtml(t('onboarding.welcome.intro', '初次见面！我会带你下载第一份示例作品，熟悉整个流程。先告诉我，怎么称呼你？')) + '</p>'
                + '<input type="text" class="po-input" id="po-name-input" maxlength="40" placeholder="'
                + escapeHtml(t('onboarding.welcome.name-placeholder', '输入你的称呼（可留空）')) + '" value="' + escapeHtml(existing) + '">'
                + '<div class="po-hint" id="po-name-hint"></div>'
                + '</div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ])
        });
        var input = overlay.qs('#po-name-input');
        if (input) {
            input.focus();
            input.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') {
                    submitName();
                }
            });
        }
        bindFoot({skip: skip, next: submitName});
    }

    function submitName() {
        var input = overlay.qs('#po-name-input');
        var name = input ? input.value.trim() : '';
        patchState({name: name});
        // 持久化到服务端（best-effort）并即时刷新当前页占位
        saveProfileName(name);
        callHook('applyName', name);
        screenNetwork();
    }

    function saveProfileName(name) {
        try {
            fetch('/api/onboarding/profile', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({displayName: name})
            }).catch(function () { /* best-effort */ });
        } catch (e) {
            /* ignore */
        }
    }

    function greetName() {
        var name = loadState().name;
        if (name && name.trim()) {
            return name.trim();
        }
        if (config && config.savedName && config.savedName.trim()) {
            return config.savedName.trim();
        }
        return t('onboarding.welcome.default-name', '朋友');
    }

    function screenNetwork() {
        renderNetwork('checking', null);
        runNetworkCheck();
    }

    function renderNetwork(stateName, data) {
        var bodyTop = '<p>' + escapeHtml(t('onboarding.network.intro',
            '你好，{name}！开始之前，先检查后端能否连上 Pixiv（全程经你配置的代理）。', {name: greetName()})) + '</p>';
        var netRow;
        var buttons;
        if (stateName === 'checking') {
            netRow = '<div class="po-net"><span class="po-spinner"></span><span>'
                + escapeHtml(t('onboarding.network.checking', '正在检测网络…')) + '</span></div>';
            buttons = [SKIP_BTN(), {act: 'wait', label: t('onboarding.network.checking-btn', '检测中…'), variant: 'primary', disabled: true}];
        } else if (stateName === 'ok') {
            netRow = '<div class="po-net"><span class="po-net-ok">✓ '
                + escapeHtml(t('onboarding.network.ok', 'Pixiv 可达：{ping}ms', {ping: data.latencyMs})) + '</span></div>';
            buttons = [SKIP_BTN(), {act: 'next', label: t('onboarding.network.start-download', '开始下载第一份作品'), variant: 'primary'}];
        } else {
            netRow = '<div class="po-net"><span class="po-net-fail">✕ '
                + escapeHtml(t('onboarding.network.fail', '无法连接 Pixiv，请检查代理 / 网络设置')) + '</span></div>'
                + '<div class="po-hint">' + escapeHtml(t('onboarding.network.fail-hint',
                    '常见原因：代理未开启或端口不对。请在程序的「设置 / config.yaml」中配置 proxy.host / proxy.port 后重试。')) + '</div>';
            buttons = [SKIP_BTN(), {act: 'retry', label: t('onboarding.network.retry', '重试'), variant: 'primary'}];
        }
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.network.title', '① 检查网络是否可达')) + '</h3>'
                + '<div class="po-pop-body">' + bodyTop + netRow + '</div>'
                + footHtml(buttons)
        });
        bindFoot({
            skip: skip,
            retry: screenNetwork,
            next: function () {
                patchState({phase: 'download'});
                phaseDownload();
            },
            wait: function () { /* no-op */ }
        });
    }

    function runNetworkCheck() {
        fetch('/api/onboarding/connectivity', {credentials: 'same-origin'})
            .then(function (r) {
                if (!r.ok) {
                    throw new Error('HTTP ' + r.status);
                }
                return r.json();
            })
            .then(function (data) {
                if (data && data.reachable) {
                    renderNetwork('ok', data);
                } else {
                    renderNetwork('fail', data);
                }
            })
            .catch(function () {
                renderNetwork('fail', null);
            });
    }

    // 阶段：先逐区域认识下载页（Cookie / 油猴脚本 / Tab 模式），再引导下载第一份示例作品（聚光步骤）
    function phaseDownload() {
        stepCookie();
    }

    function orientProgress(step) {
        return t('onboarding.orient.progress', '认识下载页 · {step}/4', {step: step});
    }

    // 认识下载页 ①：Cookie 区域
    function stepCookie() {
        overlay.render({
            targetSelector: config.sel.cookieCard,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.orient.cookie.title', '① Cookie 区域')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.orient.cookie.body',
                    '这里填写你的 Pixiv Cookie。想下载需要登录才能看的作品（R-18、关注限定等）时，必须先在这里保存 Cookie；本次示例是全年龄作品，可以先不配置。')) + '</p></div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ], orientProgress(1))
        });
        bindFoot({skip: skip, next: stepScripts});
    }

    // 认识下载页 ②：油猴脚本区域
    function stepScripts() {
        overlay.render({
            targetSelector: config.sel.scriptsCard,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.orient.scripts.title', '② 油猴脚本区域')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.orient.scripts.body',
                    '这里可以安装配套的油猴脚本：在 Pixiv 站内一键导入登录 Cookie、抓取作品链接、批量下载等。本次示例用不到，先了解即可。')) + '</p></div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ], orientProgress(2))
        });
        bindFoot({skip: skip, next: stepTabs});
    }

    // 认识下载页 ③：下载方式 Tab
    function stepTabs() {
        overlay.render({
            targetSelector: config.sel.tabs,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.orient.tabs.title', '③ 下载方式')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.orient.tabs.body',
                    '这里切换不同的下载方式：快捷获取我的收藏 / 关注 / 作品、批量导入单作品链接、按画师 ID 下载、关键词搜索、整系列下载。')) + '</p></div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ], orientProgress(3))
        });
        bindFoot({skip: skip, next: stepChooseExample});
    }

    // 认识下载页 ④：选择本次示例所用的下载方式（切到「批量导入单作品」）
    function stepChooseExample() {
        callHook('switchToSingleImport');
        // 切换 Tab 后单作品面板稍后才可见，延迟渲染让聚光定位到位
        global.setTimeout(function () {
            overlay.render({
                targetSelector: config.sel.singleImportTab,
                html:
                    '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.orient.choose.title', '④ 选择下载方式')) + '</h3>'
                    + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.orient.choose.body',
                        '第一次，我们来使用「批量导入单作品」作为示例。已为你切换到该模式，点「下一步」开始下载示例作品。')) + '</p></div>'
                    + footHtml([
                        SKIP_BTN(),
                        {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                    ], orientProgress(4))
            });
            bindFoot({skip: skip, next: stepPasteUrl});
        }, 120);
    }

    function stepPasteUrl() {
        // 模式已在「选择下载方式」步切到单作品；再幂等确认一次后直接渲染
        callHook('switchToSingleImport');
        renderPasteUrl();
    }

    function renderPasteUrl() {
        overlay.render({
            targetSelector: config.sel.importTextarea,
            interactiveSelector: config.sel.importTextarea,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.download.paste.title', '① 粘贴示例作品链接')) + '</h3>'
                + '<div class="po-pop-body">'
                + '<p>' + escapeHtml(t('onboarding.download.paste.body', '复制下面这个示例作品链接，粘贴到高亮的输入框里：')) + '</p>'
                + '<div class="po-codeblock">'
                + '<code class="po-code" id="po-example-url">' + escapeHtml(EXAMPLE_URL) + '</code>'
                + '<button type="button" class="po-btn po-copy-btn" id="po-copy-btn">'
                + escapeHtml(t('onboarding.common.copy', '复制')) + '</button>'
                + '</div>'
                + '<div class="po-hint" id="po-paste-hint"></div>'
                + '</div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary', disabled: true}
                ], t('onboarding.download.progress', '下载示例 · {step}/5', {step: 1}))
        });
        var copyBtn = overlay.qs('#po-copy-btn');
        if (copyBtn) {
            copyBtn.addEventListener('click', function () {
                copyExampleUrl(copyBtn);
            });
        }
        bindFoot({skip: skip, next: stepAddQueue});
        watchPasteInput();
    }

    function copyExampleUrl(btn) {
        var done = function () {
            btn.classList.add('po-copied');
            btn.textContent = t('onboarding.common.copied', '已复制');
            global.setTimeout(function () {
                btn.classList.remove('po-copied');
                btn.textContent = t('onboarding.common.copy', '复制');
            }, 1500);
        };
        if (global.navigator && global.navigator.clipboard && global.navigator.clipboard.writeText) {
            global.navigator.clipboard.writeText(EXAMPLE_URL).then(done).catch(function () {
                legacyCopy(EXAMPLE_URL);
                done();
            });
        } else {
            legacyCopy(EXAMPLE_URL);
            done();
        }
    }

    function legacyCopy(text) {
        try {
            var ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
        } catch (e) {
            /* ignore */
        }
    }

    var _pasteWatchTimer = null;

    function watchPasteInput() {
        if (_pasteWatchTimer) {
            global.clearInterval(_pasteWatchTimer);
        }
        var check = function () {
            if (!overlay.pop) {
                global.clearInterval(_pasteWatchTimer);
                _pasteWatchTimer = null;
                return;
            }
            var ta = document.querySelector(config.sel.importTextarea);
            var nextBtn = overlay.qs('[data-act="next"]');
            var hint = overlay.qs('#po-paste-hint');
            if (!ta || !nextBtn) {
                return;
            }
            var verdict = classifyPasteValue(ta.value);
            nextBtn.disabled = !verdict.ok;
            if (hint) {
                if (verdict.ok) {
                    hint.className = 'po-hint po-hint-ok';
                    hint.textContent = t('onboarding.download.paste.ok', '✓ 已识别示例作品，点「下一步」继续');
                } else if (verdict.foreign) {
                    hint.className = 'po-hint po-hint-error';
                    hint.textContent = t('onboarding.download.paste.foreign', '检测到其它内容，请先完成指引哦～本步只粘贴上面的示例链接');
                } else {
                    hint.className = 'po-hint';
                    hint.textContent = '';
                }
            }
        };
        _pasteWatchTimer = global.setInterval(check, 300);
        check();
    }

    // 判定输入框内容：ok=含示例且无杂项；foreign=含与示例无关的内容
    function classifyPasteValue(value) {
        var lines = String(value || '').split('\n')
            .map(function (l) { return l.trim(); })
            .filter(function (l) { return l.length > 0; });
        if (!lines.length) {
            return {ok: false, foreign: false};
        }
        var hasExample = false;
        var hasForeign = false;
        lines.forEach(function (line) {
            if (lineRefersExample(line)) {
                hasExample = true;
            } else {
                hasForeign = true;
            }
        });
        return {ok: hasExample && !hasForeign, foreign: hasForeign};
    }

    function lineRefersExample(line) {
        // 接受：完整链接 / 形如 "url | title" / 纯 ID / "id | title"
        var head = line.split('|')[0].trim();
        if (head === EXAMPLE_ID) {
            return true;
        }
        var m = head.match(/artworks\/(\d+)/);
        if (m && m[1] === EXAMPLE_ID) {
            return true;
        }
        return false;
    }

    function stepAddQueue() {
        if (_pasteWatchTimer) {
            global.clearInterval(_pasteWatchTimer);
            _pasteWatchTimer = null;
        }
        overlay.render({
            targetSelector: config.sel.importButton,
            interactiveSelector: config.sel.importButton,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.download.queue.title', '② 加入下载队列')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.download.queue.body',
                    '点击高亮的「导入并加入队列」按钮，把示例作品加入下载队列。')) + '</p></div>'
                + footHtml([SKIP_BTN()], t('onboarding.download.progress', '下载示例 · {step}/5', {step: 2}))
        });
        bindFoot({skip: skip});
        waitFor(function () {
            return !!callHook('isExampleQueued', EXAMPLE_ID);
        }, stepFilters);
    }

    // 入队后逐一介绍「附加筛选」「下载设置」两块卡片（仅讲解，遮罩拦截交互、本步不允许修改）
    function stepFilters() {
        overlay.render({
            targetSelector: config.sel.filtersCard,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.download.filters.title', '③ 附加筛选')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.download.filters.body',
                    '可按内容分级、AI、标签、类型、页数、收藏数等条件过滤要下载的作品，不符合条件的会在下载时自动跳过。先了解一下，这一步暂不修改。')) + '</p></div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ], t('onboarding.download.progress', '下载示例 · {step}/5', {step: 3}))
        });
        bindFoot({skip: skip, next: stepSettings});
    }

    function stepSettings() {
        overlay.render({
            targetSelector: config.sel.settingsCard,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.download.settings.title', '④ 下载设置')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.download.settings.body',
                    '这里设置作品间隔、并发数、是否跳过已下载、下载后自动收藏、文件名格式等。先了解一下，这一步暂不修改。')) + '</p></div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ], t('onboarding.download.progress', '下载示例 · {step}/5', {step: 4}))
        });
        bindFoot({skip: skip, next: stepStart});
    }

    function stepStart() {
        overlay.render({
            targetSelector: config.sel.startButton,
            interactiveSelector: config.sel.startButton,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.download.start.title', '⑤ 开始下载')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.download.start.body',
                    '点击高亮的「开始下载」按钮。下载期间请保持本页打开。')) + '</p></div>'
                + footHtml([SKIP_BTN()], t('onboarding.download.progress', '下载示例 · {step}/5', {step: 5}))
        });
        bindFoot({skip: skip});
        waitFor(function () {
            return !!callHook('isRunning');
        }, phaseMonitor);
    }

    // 阶段：监听示例作品下载结果（轮询后端状态，解耦于页面 SSE）
    function phaseMonitor() {
        renderMonitor();
        pollDownloadStatus();
    }

    function renderMonitor() {
        var monitorBody = findFirstDownloadResultEntry()
            ? t('onboarding.monitor.body',
                '正在下载，请稍候…下方的状态栏与下载队列会实时显示进度，完成后会自动带你去画廊查看。')
            : t('onboarding.monitor.body.no-result',
                '正在下载，请稍候…下方的状态栏与下载队列会实时显示进度，完成后会提示你查看下载结果。');
        // 高亮下载状态 + 队列区域，让用户实时看到下载进度（仅高亮、不可交互）
        overlay.render({
            targetSelector: config.sel.progressArea,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.monitor.title', '⏳ 正在下载示例作品')) + '</h3>'
                + '<div class="po-pop-body">'
                + '<div class="po-net"><span class="po-spinner"></span><span>'
                + escapeHtml(monitorBody) + '</span></div>'
                + '</div>'
                + footHtml([SKIP_BTN()])
        });
        bindFoot({skip: skip});
    }

    var _monitorTimer = null;

    function pollDownloadStatus() {
        if (_monitorTimer) {
            global.clearInterval(_monitorTimer);
        }
        var attempts = 0;
        _monitorTimer = global.setInterval(function () {
            attempts++;
            if (!overlay.pop) {
                global.clearInterval(_monitorTimer);
                _monitorTimer = null;
                return;
            }
            fetch('/api/download/status/' + EXAMPLE_ID, {credentials: 'same-origin'})
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (data) {
                    if (!data) {
                        return;
                    }
                    if (data.completed) {
                        stopMonitor();
                        monitorSucceeded();
                    } else if (data.failed) {
                        stopMonitor();
                        monitorFailed(data.message || '');
                    }
                })
                .catch(function () { /* 网络抖动：下一轮再试 */ });
            if (attempts > 150) { // ~5 分钟保护，避免无限轮询
                stopMonitor();
            }
        }, 2000);
    }

    function stopMonitor() {
        if (_monitorTimer) {
            global.clearInterval(_monitorTimer);
            _monitorTimer = null;
        }
    }

    function firstDownloadResultEntrySelector() {
        if (!config || !config.sel) {
            return null;
        }
        return config.sel.firstDownloadResultEntry || config.sel.resultEntry || null;
    }

    function findFirstDownloadResultEntry() {
        var sel = firstDownloadResultEntrySelector();
        if (!sel) {
            return null;
        }
        try {
            return document.querySelector(sel);
        } catch (e) {
            return null;
        }
    }

    function waitForFirstDownloadResultEntry(done) {
        var immediate = findFirstDownloadResultEntry();
        if (immediate || !firstDownloadResultEntrySelector()) {
            done(immediate);
            return;
        }
        var settled = false;
        var settle = function (el) {
            if (settled) {
                return;
            }
            settled = true;
            done(el || findFirstDownloadResultEntry());
        };
        if (global.PixivNav && typeof global.PixivNav.ready === 'function') {
            try {
                global.PixivNav.ready().then(function () {
                    settle(findFirstDownloadResultEntry());
                }, function () {
                    settle(findFirstDownloadResultEntry());
                });
                global.setTimeout(function () {
                    settle(findFirstDownloadResultEntry());
                }, 3000);
                return;
            } catch (e) {
                // 回退到下方短轮询
            }
        }
        waitForElement(findFirstDownloadResultEntry, settle, function () {
            settle(null);
        }, 3000);
    }

    function monitorSucceeded() {
        waitForFirstDownloadResultEntry(function (entry) {
            if (entry) {
                renderFirstDownloadResultEntryPrompt();
            } else {
                renderDownloadCompletedWithoutResultEntry();
            }
        });
    }

    function renderFirstDownloadResultEntryPrompt() {
        var resultEntry = firstDownloadResultEntrySelector();
        patchState({phase: 'await-gallery', downloaded: true});
        overlay.render({
            targetSelector: resultEntry,
            interactiveSelector: resultEntry,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.monitor.success.title', '🎉 下载成功！')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.monitor.success.body',
                    '第一份作品已下载到本地。点击高亮的「画廊」入口，去看看你的成果吧。')) + '</p></div>'
                + footHtml([SKIP_BTN()])
        });
        bindFoot({skip: skip});
        // 画廊在新标签打开，由那边的 boot 续跑；这里点击后保持提示即可
    }

    function renderDownloadCompletedWithoutResultEntry() {
        patchState({downloaded: true});
        markCompleted();
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.monitor.success.title', '🎉 下载成功！')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.monitor.success.no-result.body',
                    '第一份作品已下载到本地。当前没有可打开的下载结果入口，指引已完成。')) + '</p></div>'
                + footHtml([{act: 'done', label: t('onboarding.done.close', '开始使用'), variant: 'primary'}])
        });
        bindFoot({done: finish});
    }

    function monitorFailed(message) {
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.monitor.fail.title', '下载未成功')) + '</h3>'
                + '<div class="po-pop-body">'
                + '<p>' + escapeHtml(t('onboarding.monitor.fail.body',
                    '示例作品下载失败了。请检查 Cookie 与网络 / 代理后重试。')) + '</p>'
                + (message ? '<div class="po-hint po-hint-error">' + escapeHtml(message) + '</div>' : '')
                + '</div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'retry', label: t('onboarding.monitor.fail.retry', '重新下载'), variant: 'primary'}
                ])
        });
        // 重试只回到「粘贴示例链接」，无需重走认识下载页的几步
        bindFoot({skip: skip, retry: stepPasteUrl});
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  画廊阶段
    // ════════════════════════════════════════════════════════════════════════════

    function phaseGallery() {
        patchState({phase: 'gallery'});
        // 进入画廊讲解即视为「画廊操作指引」已抵达，通知后端供 GUI 引导推进（取代旧版画廊指引的信号）
        notifyCompletionStepDone();
        // 先逐区域认识画廊（视图 / 搜索 / 筛选 / 作品网格），再高亮刚下载的作品卡片
        runGalleryRegions(highlightExampleCard);
    }

    // 画廊各区域讲解（按当前可见者裁剪）
    function galleryRegions() {
        return [
            {
                sels: [config.sel.viewNav],
                title: t('onboarding.gallery.views.title', '① 浏览视图'),
                body: t('onboarding.gallery.views.body', '左侧可在「全部图片 / 按作者 / 按系列漫画」之间切换，用不同维度浏览你下载的作品；下方还有收藏夹与各页面导航。')
            },
            {
                sels: [config.sel.searchBox],
                title: t('onboarding.gallery.search.title', '② 搜索'),
                body: t('onboarding.gallery.search.body', '输入作品标题、画师名、标签等快速查找已下载的内容；左侧下拉可切换搜索范围。')
            },
            {
                sels: [config.sel.filterToggle],
                title: t('onboarding.gallery.filter.title', '③ 筛选'),
                body: t('onboarding.gallery.filter.body', '点开筛选可按排序、分级、AI、格式、收藏夹、系列等条件组合过滤，精准定位想看的作品。')
            },
            {
                sels: [config.sel.grid],
                title: t('onboarding.gallery.grid.title', '④ 作品网格'),
                body: t('onboarding.gallery.grid.body', '下载完成的作品都展示在这里，点任意一张卡片即可进入作品详情页。')
            }
        ];
    }

    function runGalleryRegions(onComplete) {
        var steps = galleryRegions()
            .map(function (r) {
                var sel = firstVisibleSelector(r.sels);
                return sel ? {sel: sel, title: r.title, body: r.body} : null;
            })
            .filter(Boolean);
        runRegionSteps(steps, onComplete, t('onboarding.common.next', '下一步'), function (i, n) {
            return t('onboarding.gallery.progress', '认识画廊 · {step}/{total}', {step: i + 1, total: n});
        });
    }

    function highlightExampleCard() {
        // 卡片可能随网格异步渲染，等它出现再聚光
        waitForElement(function () {
            var fn = hook('getExampleCard');
            return fn ? fn(EXAMPLE_ID) : null;
        }, function (el) {
            renderGalleryCard(!!el);
        }, function () {
            renderGalleryCard(false);
        }, 8000);
    }

    function renderGalleryCard(found) {
        var sel = found ? callHook('getExampleCardSelector', EXAMPLE_ID) : (config.sel.grid || null);
        overlay.render({
            targetSelector: sel,
            interactiveSelector: found ? sel : null,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.gallery.card.title', '🖼 这是你刚下载的作品')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(found
                    ? t('onboarding.gallery.card.body', '点击这张高亮的作品卡片，进入作品详情页。')
                    : t('onboarding.gallery.card.body-fallback', '你下载的作品会出现在这里，点任意一张卡片可进入作品详情页。')) + '</p></div>'
                + footHtml([SKIP_BTN()])
        });
        bindFoot({skip: skip});
        if (found) {
            // 点击卡片会跳转到详情页；先把阶段推进到 detail，详情页 boot 续跑
            var card = callHook('getExampleCard', EXAMPLE_ID);
            if (card) {
                card.addEventListener('click', function () {
                    patchState({phase: 'detail'});
                }, {capture: true, once: true});
            }
        }
    }

    // 用户先打开画廊（未走下载页）：引导回下载页
    function phaseGalleryRedirect() {
        patchState({status: 'active', phase: 'welcome'});
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.redirect.title', '👋 欢迎使用 PixivDownloader')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.redirect.body',
                    '新手指引从「下载页」开始，会带你下载第一份示例作品。要现在前往吗？')) + '</p></div>'
                + footHtml([
                    {act: 'later', label: t('onboarding.redirect.later', '以后再说'), variant: 'ghost'},
                    {act: 'go', label: t('onboarding.redirect.go', '前往下载页'), variant: 'primary'}
                ])
        });
        bindFoot({
            later: skip,
            go: function () {
                global.location.href = '/pixiv-batch.html';
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  详情页阶段（逐区域讲解）
    // ════════════════════════════════════════════════════════════════════════════

    function isVisible(el) {
        return !!(el && el.offsetParent !== null);
    }

    // 解析一组候选选择器中第一个「存在且可见」的，返回其选择器（用于聚光），无则 null
    function firstVisibleSelector(sels) {
        for (var i = 0; i < sels.length; i++) {
            var s = sels[i];
            if (s && isVisible(document.querySelector(s))) {
                return s;
            }
        }
        return null;
    }

    function detailRegions() {
        return [
            {
                sels: [config.sel.viewer],
                title: t('onboarding.detail.viewer.title', '① 作品图区'),
                body: t('onboarding.detail.viewer.body', '这里展示作品的图片，点击任意图片可放大查看；多图作品会逐页平铺浏览。')
            },
            {
                sels: [config.sel.actions],
                title: t('onboarding.detail.actions.title', '② 功能区'),
                body: t('onboarding.detail.actions.body', '作品的操作区：展开 / 收起多图、跳转 Pixiv 原作品、作品展示，以及删除本地作品等，旁边还显示页数与格式信息。')
            },
            {
                sels: [config.sel.detail],
                title: t('onboarding.detail.meta.title', '③ 简介区'),
                body: t('onboarding.detail.meta.body', '作品标题、简介与标签都在这里。点标签可在画廊里筛选同标签的作品。')
            },
            {
                sels: [config.sel.author],
                title: t('onboarding.detail.author.title', '④ 作者区'),
                body: t('onboarding.detail.author.body', '作品的作者（画师）信息。点击可查看该作者在本地的其他作品，或跳转其 Pixiv 主页。')
            },
            {
                // 系列面板可能因作品不属于系列而隐藏，此时退而高亮「相关作品」面板
                sels: [config.sel.series, config.sel.related],
                title: t('onboarding.detail.series.title', '⑤ 系列 / 相关区'),
                body: t('onboarding.detail.series.body', '若作品属于某个系列，这里可翻阅同系列其他话；下方还会推荐相关作品。')
            }
        ];
    }

    // 解析当前页可见的详情区域步骤（带聚光选择器）
    function resolveDetailSteps() {
        return detailRegions()
            .map(function (r) {
                var sel = firstVisibleSelector(r.sels);
                return sel ? {sel: sel, title: r.title, body: r.body} : null;
            })
            .filter(Boolean);
    }

    function phaseDetail() {
        var doneLabel = t('onboarding.common.done', '完成');
        var runResolved = function () {
            var steps = resolveDetailSteps();
            if (steps.length) {
                runRegionSteps(steps, renderDetailDone, doneLabel);
            } else {
                renderDetailDone();
            }
        };
        // 作品图区一开始就显示「加载中」占位、始终可见，但功能区 / 简介区 / 作者区要等作品数据加载后才渲染。
        // 因此等「简介区」面板出现（此时作品图 / 功能区 / 作者区已一并渲染）再解析步骤，并留一点时间让
        // 系列 / 相关区异步加载完成，避免过早只讲到作品图区。
        waitForElement(function () {
            var el = config.sel.detail ? document.querySelector(config.sel.detail) : null;
            return isVisible(el) ? el : null;
        }, function () {
            global.setTimeout(runResolved, 500);
        }, function () {
            // 超时（异常情况）：按当前可见区域尽力讲解，没有可讲再直接完成
            runResolved();
        }, 10000);
    }

    // 通用：逐区域聚光讲解，跑完调用 onComplete。lastLabel 为最后一步主按钮文案；
    // progressFn(idx,total)→进度文案（缺省 "x / total"）。画廊与详情区域讲解共用此渲染器。
    function runRegionSteps(steps, onComplete, lastLabel, progressFn) {
        if (!steps || !steps.length) {
            onComplete();
            return;
        }
        var idx = 0;
        var total = steps.length;
        var prog = progressFn || function (i, n) { return (i + 1) + ' / ' + n; };
        var show = function () {
            var s = steps[idx];
            var isLast = idx === total - 1;
            overlay.render({
                targetSelector: s.sel,
                html:
                    '<h3 class="po-pop-title">' + escapeHtml(s.title) + '</h3>'
                    + '<div class="po-pop-body"><p>' + escapeHtml(s.body) + '</p></div>'
                    + footHtml([
                        SKIP_BTN(),
                        {act: 'next', label: isLast ? lastLabel : t('onboarding.common.next', '下一步'), variant: 'primary'}
                    ], prog(idx, total))
            });
            bindFoot({
                skip: skip,
                next: function () {
                    if (isLast) {
                        onComplete();
                    } else {
                        idx++;
                        show();
                    }
                }
            });
        };
        show();
    }

    function renderDetailDone() {
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.done.title', '🎉 新手指引完成！')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.done.body',
                    '你已经走完了从下载到浏览的完整流程。接下来可以用「快捷获取 / 搜索 / 系列」等方式批量下载更多作品，尽情探索吧！')) + '</p></div>'
                + footHtml([{act: 'done', label: t('onboarding.done.close', '开始使用'), variant: 'primary'}])
        });
        bindFoot({done: finish});
    }

    // ── 通用等待工具 ────────────────────────────────────────────────────────────
    // 轮询条件 cond()→true 时执行 done；overlay 关闭则自动停止
    function waitFor(cond, done) {
        var timer = global.setInterval(function () {
            if (!overlay.pop) {
                global.clearInterval(timer);
                return;
            }
            var ok = false;
            try {
                ok = !!cond();
            } catch (e) {
                ok = false;
            }
            if (ok) {
                global.clearInterval(timer);
                done();
            }
        }, 400);
    }

    // 轮询取元素，命中→found(el)；超时→timeout()。getter 可能在 overlay 建立前就被调用
    // （首屏异步渲染时），这是预期的：命中后由 found 负责建立 overlay。
    function waitForElement(getter, found, timeout, timeoutMs) {
        var start = Date.now();
        var timer = global.setInterval(function () {
            var el = null;
            try {
                el = getter();
            } catch (e) {
                el = null;
            }
            if (el) {
                global.clearInterval(timer);
                found(el);
            } else if (Date.now() - start > (timeoutMs || 6000)) {
                global.clearInterval(timer);
                if (timeout) {
                    timeout();
                }
            }
        }, 300);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  入口
    // ════════════════════════════════════════════════════════════════════════════

    function boot(cfg) {
        if (!cfg || !cfg.page) {
            return;
        }
        config = cfg;
        i18n = cfg.i18n || i18n;
        config.sel = config.sel || {};

        if (!cfg.eligible) {
            return; // 仅 solo / 已登录管理员
        }

        // 下载页常驻「操作指引」FAB（无论是否已完成都可随时重看）
        if (cfg.page === 'batch') {
            ensureFab();
        }

        var s = loadState();
        if (s.status === 'completed') {
            showFab(); // 已完成：仅保留 FAB 供重看，不自动弹
            return;
        }

        if (cfg.page === 'batch') {
            hideFab(); // 自动运行期间隐藏 FAB
            bootBatch(s);
        } else if (cfg.page === 'gallery') {
            bootGallery(s);
        } else if (cfg.page === 'artwork') {
            bootArtwork(s);
        }
    }

    function bootBatch(s) {
        if (s.status === 'new') {
            phaseWelcome();
            return;
        }
        // 续跑（同标签刷新 / 多标签）
        if (s.phase === 'welcome') {
            phaseWelcome();
        } else if (s.phase === 'download') {
            phaseDownload();
        } else if (s.phase === 'await-gallery') {
            // 已下载完成、等待去画廊：重新提示画廊入口
            monitorSucceeded();
        }
    }

    function bootGallery(s) {
        if (s.status === 'new') {
            phaseGalleryRedirect();
            return;
        }
        if (s.phase === 'await-gallery' || s.phase === 'gallery') {
            phaseGallery();
        }
        // 其它阶段（welcome/download 正在下载页进行）：画廊不打扰
    }

    function bootArtwork(s) {
        if (s.status !== 'active') {
            return;
        }
        if (s.phase === 'detail' || s.phase === 'gallery' || s.phase === 'await-gallery') {
            patchState({phase: 'detail'});
            phaseDetail();
        }
    }

    global.PixivOnboarding = {
        boot: boot,
        // 从右下角 FAB 重跑向导（取代旧版 PixivTour 操作指引）
        restart: restart,
        // 语言切换后刷新 FAB 文案（可携新的 i18n client）
        refreshFab: function (client) {
            if (client) {
                i18n = client;
            }
            refreshFabLabel();
        },
        EXAMPLE_ID: EXAMPLE_ID,
        EXAMPLE_URL: EXAMPLE_URL,
        // 供页面读取当前称呼（如需即时渲染占位）
        getName: function () {
            return loadState().name || '';
        }
    };
})(window);
