/*
 * 引导遮罩（操作指引）通用运行时。
 *
 * 用法：
 *   PixivTour.init({
 *     pageKey: 'batch',
 *     i18n: pageI18nClient,            // 提供 .t(key, fallback) 的 PixivI18n client
 *     steps: [{ target: '#sel', titleKey, bodyKey, fallbackTitle, fallbackBody }],
 *     auto: true                       // 首次访问自动开始
 *   });
 *
 * 文案全部经 i18n（tour 命名空间）；首次访问后写 localStorage，左下角常驻
 * “操作指引”按钮可随时重新查看。无任何第三方依赖。
 */
(function (global) {
    'use strict';

    var SEEN_PREFIX = 'pixiv_tour_seen_';
    var SPOT_PADDING = 8;
    var POP_GAP = 14;
    var VIEWPORT_MARGIN = 12;

    function seenKey(pageKey) {
        return SEEN_PREFIX + pageKey;
    }

    function isSeen(pageKey) {
        try {
            return global.localStorage.getItem(seenKey(pageKey)) === '1';
        } catch (e) {
            return false;
        }
    }

    function markSeen(pageKey) {
        try {
            global.localStorage.setItem(seenKey(pageKey), '1');
        } catch (e) {
            /* 隐私模式等场景下静默降级 */
        }
    }

    function PixivTourController(options) {
        this.pageKey = options.pageKey;
        this.i18n = options.i18n || null;
        this.steps = Array.isArray(options.steps) ? options.steps : [];
        this.onComplete = typeof options.onComplete === 'function' ? options.onComplete : null;
        this.index = 0;
        this.active = false;
        this.root = null;
        this.spot = null;
        this.pop = null;
        this.helpFab = null;
        this._onResize = this._reposition.bind(this);
        this._onKey = this._handleKey.bind(this);
    }

    PixivTourController.prototype.t = function (key, fallback) {
        if (this.i18n && typeof this.i18n.t === 'function') {
            return this.i18n.t(key, fallback);
        }
        return fallback;
    };

    PixivTourController.prototype._resolveStep = function (step) {
        return {
            el: step.target ? document.querySelector(step.target) : null,
            title: this.t(step.titleKey, step.fallbackTitle || ''),
            body: this.t(step.bodyKey, step.fallbackBody || '')
        };
    };

    // 从 startIndex 起按 dir(+1/-1) 找到第一个目标存在的步骤；找不到返回 -1
    PixivTourController.prototype._nextResolvable = function (startIndex, dir) {
        for (var i = startIndex; i >= 0 && i < this.steps.length; i += dir) {
            var s = this.steps[i];
            if (!s.target || document.querySelector(s.target)) {
                return i;
            }
        }
        return -1;
    };

    PixivTourController.prototype.ensureHelpFab = function () {
        if (this.helpFab) {
            return;
        }
        var fab = document.createElement('button');
        fab.type = 'button';
        fab.className = 'pt-help-fab';
        fab.innerHTML = '<span aria-hidden="true">💡</span><span class="pt-help-fab-label"></span>';
        var self = this;
        fab.addEventListener('click', function () {
            self.start(true);
        });
        document.body.appendChild(fab);
        this.helpFab = fab;
        this.refreshHelpFabLabel();
    };

    PixivTourController.prototype.refreshHelpFabLabel = function () {
        if (!this.helpFab) {
            return;
        }
        var label = this.t('tour:common.help', '操作指引');
        this.helpFab.querySelector('.pt-help-fab-label').textContent = label;
        this.helpFab.setAttribute('aria-label', label);
        this.helpFab.title = label;
    };

    PixivTourController.prototype._buildOverlay = function () {
        var root = document.createElement('div');
        root.className = 'pt-root';

        var backdrop = document.createElement('div');
        backdrop.className = 'pt-backdrop';

        var spot = document.createElement('div');
        spot.className = 'pt-spot';

        var pop = document.createElement('div');
        pop.className = 'pt-pop';
        pop.setAttribute('role', 'dialog');
        pop.setAttribute('aria-modal', 'true');
        pop.innerHTML =
            '<h3 class="pt-pop-title"></h3>' +
            '<div class="pt-pop-body"></div>' +
            '<div class="pt-pop-foot">' +
            '<span class="pt-progress"></span>' +
            '<button type="button" class="pt-btn pt-btn-skip"></button>' +
            '<button type="button" class="pt-btn pt-btn-prev"></button>' +
            '<button type="button" class="pt-btn pt-btn-primary pt-btn-next"></button>' +
            '</div>';

        root.appendChild(backdrop);
        root.appendChild(spot);
        root.appendChild(pop);
        document.body.appendChild(root);

        var self = this;
        pop.querySelector('.pt-btn-skip').addEventListener('click', function () {
            self.end(true);
        });
        pop.querySelector('.pt-btn-prev').addEventListener('click', function () {
            self.prev();
        });
        pop.querySelector('.pt-btn-next').addEventListener('click', function () {
            self.next();
        });

        this.root = root;
        this.spot = spot;
        this.pop = pop;
    };

    PixivTourController.prototype.start = function (force) {
        if (this.active) {
            return;
        }
        if (!force && isSeen(this.pageKey)) {
            return;
        }
        var first = this._nextResolvable(0, 1);
        if (first < 0) {
            return; // 当前页没有任何可定位的步骤目标，不打扰用户
        }
        this.active = true;
        this.index = first;
        if (this.helpFab) {
            this.helpFab.hidden = true;
        }
        this._buildOverlay();
        global.addEventListener('resize', this._onResize, true);
        global.addEventListener('scroll', this._onResize, true);
        document.addEventListener('keydown', this._onKey, true);
        this._render();
    };

    PixivTourController.prototype.next = function () {
        var n = this._nextResolvable(this.index + 1, 1);
        if (n < 0) {
            this.end(true);
            return;
        }
        this.index = n;
        this._render();
    };

    PixivTourController.prototype.prev = function () {
        var p = this._nextResolvable(this.index - 1, -1);
        if (p < 0) {
            return;
        }
        this.index = p;
        this._render();
    };

    PixivTourController.prototype.end = function (persist) {
        if (!this.active) {
            return;
        }
        this.active = false;
        global.removeEventListener('resize', this._onResize, true);
        global.removeEventListener('scroll', this._onResize, true);
        document.removeEventListener('keydown', this._onKey, true);
        if (this.root && this.root.parentNode) {
            this.root.parentNode.removeChild(this.root);
        }
        this.root = this.spot = this.pop = null;
        if (persist) {
            markSeen(this.pageKey);
            if (this.onComplete) {
                try {
                    this.onComplete();
                } catch (e) {
                    /* 完成回调失败不应影响指引关闭 */
                }
            }
        }
        if (this.helpFab) {
            this.helpFab.hidden = false;
        }
    };

    PixivTourController.prototype._handleKey = function (e) {
        if (e.key === 'Escape') {
            this.end(true);
        } else if (e.key === 'ArrowRight' || e.key === 'Enter') {
            this.next();
        } else if (e.key === 'ArrowLeft') {
            this.prev();
        }
    };

    PixivTourController.prototype._render = function () {
        if (!this.active) {
            return;
        }
        var step = this.steps[this.index];
        var resolved = this._resolveStep(step);
        if (resolved.el && resolved.el.scrollIntoView) {
            resolved.el.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'smooth'});
        }

        this.pop.querySelector('.pt-pop-title').textContent = resolved.title;
        this.pop.querySelector('.pt-pop-body').textContent = resolved.body;

        var isFirst = this._nextResolvable(this.index - 1, -1) < 0;
        var isLast = this._nextResolvable(this.index + 1, 1) < 0;
        var pos = this.index + 1;
        var total = this.steps.length;

        this.pop.querySelector('.pt-progress').textContent =
            this.t('tour:common.progress', pos + ' / ' + total)
                .replace('{0}', pos).replace('{1}', total);
        this.pop.querySelector('.pt-btn-skip').textContent = this.t('tour:common.skip', '跳过');
        var prevBtn = this.pop.querySelector('.pt-btn-prev');
        prevBtn.textContent = this.t('tour:common.prev', '上一步');
        prevBtn.style.display = isFirst ? 'none' : '';
        this.pop.querySelector('.pt-btn-next').textContent = isLast
            ? this.t('tour:common.done', '完成')
            : this.t('tour:common.next', '下一步');

        var self = this;
        // 等待 scrollIntoView 平滑滚动后再定位高亮框与气泡
        global.requestAnimationFrame(function () {
            global.setTimeout(function () {
                self._reposition();
                self.pop.classList.add('pt-pop-in');
            }, 60);
        });
    };

    PixivTourController.prototype._reposition = function () {
        if (!this.active || !this.spot) {
            return;
        }
        var step = this.steps[this.index];
        var el = step && step.target ? document.querySelector(step.target) : null;
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
            // 无目标：居中聚光于空，气泡居中
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

    var controllers = {};

    global.PixivTour = {
        init: function (options) {
            if (!options || !options.pageKey) {
                return null;
            }
            var ctrl = controllers[options.pageKey];
            if (!ctrl) {
                ctrl = new PixivTourController(options);
                controllers[options.pageKey] = ctrl;
            } else {
                ctrl.i18n = options.i18n || ctrl.i18n;
                if (Array.isArray(options.steps)) {
                    ctrl.steps = options.steps;
                }
                if (typeof options.onComplete === 'function') {
                    ctrl.onComplete = options.onComplete;
                }
            }
            ctrl.ensureHelpFab();
            ctrl.refreshHelpFabLabel();
            if (options.auto) {
                ctrl.start(false);
            }
            return ctrl;
        },
        get: function (pageKey) {
            return controllers[pageKey] || null;
        }
    };
})(window);
