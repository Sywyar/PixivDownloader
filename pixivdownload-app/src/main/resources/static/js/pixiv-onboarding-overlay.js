/* 跨页新用户引导的聚光覆盖层与按钮绑定。 */
(function (global) {
    'use strict';

    var ctx = global.PixivOnboardingRuntime;
    if (!ctx) {
        return;
    }

    var SPOT_PADDING = ctx.SPOT_PADDING;
    var POP_GAP = ctx.POP_GAP;
    var VIEWPORT_MARGIN = ctx.VIEWPORT_MARGIN;
    var escapeHtml = ctx.escapeHtml;

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
        var self = this;
        spot.addEventListener('click', function () {
            if (!self.interactiveEl) {
                return;
            }
            if (typeof self.interactiveEl.focus === 'function') {
                self.interactiveEl.focus();
            }
            if (typeof self.interactiveEl.click === 'function') {
                self.interactiveEl.click();
            }
        });
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
        this._clearInteractive();
        this.spot.style.pointerEvents = 'none';
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
        this.spot.style.pointerEvents = inter ? 'auto' : 'none';
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

    ctx.overlay = overlay;
    ctx.footHtml = footHtml;
    ctx.bindFoot = bindFoot;
})(window);
