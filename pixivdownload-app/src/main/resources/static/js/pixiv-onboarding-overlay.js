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

    function Overlay() {
        this.revision = 0;
        this.root = null;
        this.spot = null;
        this.pop = null;
        this.interactiveEl = null;
        this.targetSelector = null;
        this._onReposition = this._reposition.bind(this);
        this._onKeyDown = this._keyDown.bind(this);
        this._repositionTimer = null;
        this.cleanups = [];
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
        var cue = document.createElement('div');
        cue.className = 'po-target-cue';
        cue.setAttribute('aria-hidden', 'true');
        spot.appendChild(cue);
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
        pop.setAttribute('tabindex', '-1');
        root.appendChild(backdrop);
        root.appendChild(spot);
        root.appendChild(pop);
        document.body.appendChild(root);
        this.root = root;
        this.spot = spot;
        this.pop = pop;
        this.cue = cue;
        global.addEventListener('resize', this._onReposition, true);
        global.addEventListener('scroll', this._onReposition, true);
        global.addEventListener('keydown', this._onKeyDown, true);
        // 目标可能因异步渲染稍后出现 / 布局变化，持续轻量重定位
        this._repositionTimer = global.setInterval(this._onReposition, 400);
    };

    /**
     * 渲染一帧。
     * @param opts {targetSelector, interactiveSelector, html, centered, scrollTarget}
     */
    Overlay.prototype.render = function (opts) {
        this.revision++;
        this.clearResources();
        this.ensure();
        this._clearInteractive();
        this.spot.style.pointerEvents = 'none';
        this.targetSelector = opts.targetSelector || null;
        this.interactiveSelector = opts.interactiveSelector || null;
        this.centered = !!opts.centered;
        this.root.classList.toggle('po-centered', this.centered);
        this.root.classList.toggle('po-action', !!this.interactiveSelector);
        this.pop.classList.toggle('po-modal', this.centered);
        this.pop.style.width = '';
        this.pop.style.maxHeight = '';
        this.pop.setAttribute('aria-modal', this.centered ? 'true' : 'false');
        if (this.centered) {
            this.pop.style.top = '';
            this.pop.style.left = '';
        }
        var kind = this.interactiveSelector
            ? ctx.message('onboarding.overlay.action', '请操作高亮区域')
            : ctx.message('onboarding.overlay.info', '浏览说明');
        this.pop.innerHTML = '<div class="po-content">' + (this.targetSelector && !this.centered
            ? '<div class="po-step-kind">' + ctx.messageHtml(kind) + '</div>' : '') + (opts.html || '') + '</div>';
        var foot = this.pop.querySelector('.po-foot');
        if (foot) this.pop.appendChild(foot);
        var title = this.pop.querySelector('.po-pop-title');
        if (title) {
            title.id = 'po-dialog-title';
            this.pop.setAttribute('aria-labelledby', title.id);
        } else {
            this.pop.removeAttribute('aria-labelledby');
        }
        this.pop.classList.remove('po-in');
        var el = this.targetSelector ? document.querySelector(this.targetSelector) : null;
        if (el && el.scrollIntoView && opts.scrollTarget !== false) {
            try {
                el.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'instant'});
            } catch (e) {
                el.scrollIntoView();
            }
        }
        var self = this;
        var renderedPop = this.pop;
        var revision = this.revision;
        global.requestAnimationFrame(function () {
            if (self.pop !== renderedPop || self.revision !== revision) return;
            ctx.delay(function () {
                self._reposition();
                renderedPop.classList.add('po-in');
                if (!renderedPop.contains(document.activeElement) && document.activeElement !== self.interactiveEl) {
                    renderedPop.focus({preventScroll: true});
                }
            }, 60);
        });
    };

    Overlay.prototype.refreshTranslations = function () {
        if (!this.pop) return;
        this.pop.querySelectorAll('[data-po-message]').forEach(function (element) {
            element.textContent = ctx.resolveMessage(JSON.parse(element.getAttribute('data-po-message')));
        });
        if (ctx.i18n && typeof ctx.i18n.apply === 'function') ctx.i18n.apply(this.pop);
        if (this.cue && this.cue.getAttribute('data-po-message')) {
            this.cue.textContent = ctx.resolveMessage(JSON.parse(this.cue.getAttribute('data-po-message')));
        }
        this._reposition();
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
            var editing = inter && /^(INPUT|TEXTAREA)$/.test(inter.tagName);
            ctx.setMessage(this.cue, editing
                ? ctx.message('onboarding.overlay.input', '在此输入')
                : ctx.message('onboarding.overlay.click', '点击这里'));
        }
        this.cue.hidden = !inter;
        this.spot.style.pointerEvents = inter ? 'auto' : 'none';
        if (this.centered) {
            return; // 居中模态：CSS 已固定，无需定位聚光
        }
        var el = inter || (this.targetSelector ? document.querySelector(this.targetSelector) : null);
        var vw = document.documentElement.clientWidth;
        var vh = document.documentElement.clientHeight;
        var pop = this.pop;
        var content = pop.querySelector('.po-content');
        var contentScroll = content ? content.scrollTop : 0;
        pop.style.width = '';
        pop.style.maxHeight = '';
        var pw = pop.offsetWidth;
        var ph = pop.offsetHeight;
        var minSideWidth = 12 * (parseFloat(global.getComputedStyle(document.documentElement).fontSize) || 16);
        var rect;
        if (el) {
            var r = el.getBoundingClientRect();
            // 矮窗口先腾出说明空间，避免输入框居中后只剩一条可滚动的正文。
            if (inter && r.top > VIEWPORT_MARGIN && el.scrollIntoView
                    && Math.max(r.top, vh - r.top - r.height) < Math.min(ph + POP_GAP, vh / 2)
                    && Math.max(r.left, vw - r.left - r.width) < minSideWidth + POP_GAP + VIEWPORT_MARGIN) {
                el.scrollIntoView({block: 'start', inline: 'nearest', behavior: 'instant'});
                r = el.getBoundingClientRect();
            }
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
        var cueBelow = rect.top < this.cue.offsetHeight + VIEWPORT_MARGIN;
        this.spot.classList.toggle('po-cue-below', cueBelow);

        var gapBelow = POP_GAP + (inter && cueBelow ? this.cue.offsetHeight + 6 : 0);
        var gapAbove = POP_GAP + (inter && !cueBelow ? this.cue.offsetHeight + 6 : 0);
        var spaceBelow = Math.max(0, vh - rect.top - rect.height - gapBelow - VIEWPORT_MARGIN);
        var spaceAbove = Math.max(0, rect.top - gapAbove - VIEWPORT_MARGIN);
        var top;
        var left = rect.left + rect.width / 2 - pw / 2;
        var spaceLeft = rect.left - POP_GAP - VIEWPORT_MARGIN;
        var spaceRight = vw - rect.left - rect.width - POP_GAP - VIEWPORT_MARGIN;
        if (Math.max(spaceBelow, spaceAbove) < ph && Math.max(spaceLeft, spaceRight) >= minSideWidth) {
            pop.style.width = Math.min(pw, Math.max(spaceLeft, spaceRight)) + 'px';
            pw = pop.offsetWidth;
            ph = pop.offsetHeight;
            left = spaceLeft >= spaceRight ? rect.left - POP_GAP - pw : rect.left + rect.width + POP_GAP;
            top = rect.top + rect.height / 2 - ph / 2;
        } else if (spaceBelow >= ph || spaceBelow >= spaceAbove) {
            if (inter && spaceBelow > 0) pop.style.maxHeight = spaceBelow + 'px';
            ph = pop.offsetHeight;
            top = rect.top + rect.height + gapBelow;
        } else {
            if (inter && spaceAbove > 0) pop.style.maxHeight = spaceAbove + 'px';
            ph = pop.offsetHeight;
            top = rect.top - ph - gapAbove;
        }
        top = Math.max(VIEWPORT_MARGIN, Math.min(top, vh - ph - VIEWPORT_MARGIN));
        left = Math.max(VIEWPORT_MARGIN, Math.min(left, vw - pw - VIEWPORT_MARGIN));
        pop.style.top = top + 'px';
        pop.style.left = left + 'px';
        if (content) content.scrollTop = contentScroll;
    };

    Overlay.prototype._keyDown = function (event) {
        if (!this.pop || event.defaultPrevented || document.querySelector('dialog[open]')) return;
        if (event.key === 'Escape') {
            var skip = this.qs('[data-act="skip"]');
            if (skip) { event.preventDefault(); event.stopPropagation(); skip.click(); }
            return;
        }
        if (event.key !== 'Tab') return;
        var focusable = Array.from(this.pop.querySelectorAll('button:not(:disabled), input:not(:disabled), a[href]'));
        if (this.interactiveEl && !this.interactiveEl.disabled) focusable.unshift(this.interactiveEl);
        focusable = focusable.filter(function (el) { return el.getClientRects().length > 0; });
        var index = focusable.indexOf(document.activeElement);
        var next = event.shiftKey ? (index < 0 ? focusable.length - 1 : index - 1) : index + 1;
        event.preventDefault();
        (focusable[(next + focusable.length) % focusable.length] || this.pop).focus();
    };

    Overlay.prototype.qs = function (sel) {
        return this.pop ? this.pop.querySelector(sel) : null;
    };

    Overlay.prototype.onCleanup = function (cleanup) {
        this.cleanups.push(cleanup);
    };

    Overlay.prototype.clearResources = function () {
        var cleanups = this.cleanups;
        this.cleanups = [];
        cleanups.forEach(function (cleanup) {
            try { cleanup(); } catch (error) { global.console.warn('Onboarding cleanup failed', error); }
        });
    };

    Overlay.prototype.destroy = function () {
        this.revision++;
        this.clearResources();
        if (this._repositionTimer) {
            global.clearInterval(this._repositionTimer);
            this._repositionTimer = null;
        }
        global.removeEventListener('resize', this._onReposition, true);
        global.removeEventListener('scroll', this._onReposition, true);
        global.removeEventListener('keydown', this._onKeyDown, true);
        this._clearInteractive();
        if (this.root && this.root.parentNode) {
            this.root.parentNode.removeChild(this.root);
        }
        this.root = this.spot = this.pop = this.cue = null;
        this.targetSelector = this.interactiveSelector = null;
    };

    // 单例覆盖层
    var overlay = new Overlay();

    function footHtml(buttons, progress) {
        var parts = ['<div class="po-foot">'];
        if (progress) {
            parts.push('<span class="po-progress">' + ctx.messageHtml(progress) + '</span>');
        }
        parts.push('<div class="po-actions">');
        buttons.forEach(function (b) {
            var cls = 'po-btn' + (b.variant ? ' po-btn-' + b.variant : '');
            var dis = b.disabled ? ' disabled' : '';
            parts.push('<button type="button" class="' + cls + '" data-act="' + b.act + '"' + dis + '>'
                + ctx.messageHtml(b.label) + '</button>');
        });
        parts.push('</div></div>');
        return parts.join('');
    }

    function bindFoot(handlers) {
        if (!overlay.pop) {
            return;
        }
        var revision = overlay.revision;
        overlay.pop.querySelectorAll('[data-act]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                if (overlay.revision !== revision) return;
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
