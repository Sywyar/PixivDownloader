/*
 * 跨页新用户引导的共享状态、生命周期与等待工具。
 * 各职责模块通过 PixivOnboardingRuntime 显式协作，公共 API 由最后加载的 facade 暴露。
 */
(function (global) {
    'use strict';

    var STORAGE_KEY = 'pixiv_onboarding_v1';
    var EXAMPLE_ID = '145378118';
    var EXAMPLE_URL = 'https://www.pixiv.net/artworks/' + EXAMPLE_ID;
    var SPOT_PADDING = 8;
    var POP_GAP = 14;
    var VIEWPORT_MARGIN = 12;

    var ctx = {
        config: null,
        i18n: null,
        completionStepNotified: false,
        download: {},
        gallery: {}
    };

    function t(key, fallback, vars) {
        var full = key.indexOf(':') >= 0 ? key : 'tour:' + key;
        if (ctx.i18n && typeof ctx.i18n.t === 'function') {
            return ctx.i18n.t(full, fallback, vars);
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

    function message(key, fallback, args) {
        return {key: key, fallback: fallback, args: args};
    }

    function resolveMessage(value) {
        if (!value || typeof value !== 'object') return String(value == null ? '' : value);
        var args = {};
        Object.keys(value.args || {}).forEach(function (key) {
            args[key] = resolveMessage(value.args[key]);
        });
        return t(value.key, value.fallback, args);
    }

    function messageHtml(value) {
        if (!value || typeof value !== 'object') return escapeHtml(value);
        return '<span data-po-message="' + escapeHtml(JSON.stringify(value)) + '">'
            + escapeHtml(resolveMessage(value)) + '</span>';
    }

    function setMessage(element, value) {
        element.setAttribute('data-po-message', JSON.stringify(value));
        element.textContent = resolveMessage(value);
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

    function finish() {
        markCompleted();
        ctx.overlay.destroy();
        setRestartEnabled(true);
        focusRestartEntry();
    }

    function skip() {
        markCompleted();
        ctx.overlay.destroy();
        setRestartEnabled(true);
        focusRestartEntry();
    }

    var SKIP_BTN = function () {
        return {act: 'skip', label: message('onboarding.common.skip', '跳过指引'), variant: 'ghost'};
    };

    // 页面提供入口位置与外观，引导只绑定重看行为。
    var restartEl = null;

    function bindRestartButton() {
        if (restartEl || !ctx.config || ctx.config.page !== 'batch') {
            return;
        }
        var selector = ctx.config.sel.restartButton;
        restartEl = selector ? document.querySelector(selector) : null;
        if (!restartEl) return;
        restartEl.addEventListener('click', restart);
        restartEl.hidden = false;
        refreshRestartLabel();
    }

    function refreshRestartLabel() {
        if (!restartEl) {
            return;
        }
        var label = t('common.help', '操作指引');
        var labelEl = restartEl.querySelector('[data-onboarding-label]');
        if (labelEl) {
            labelEl.textContent = label;
        }
        restartEl.setAttribute('aria-label', label);
        restartEl.title = label;
    }

    function setRestartEnabled(enabled) {
        if (restartEl) restartEl.disabled = !enabled;
    }

    function focusRestartEntry() {
        var selector = ctx.config && ctx.config.sel.restartFocusTarget;
        var target = selector ? document.querySelector(selector) : restartEl;
        if (target) target.focus({preventScroll: true});
    }

    // 重看时保留称呼，重新进入欢迎与下载流程。
    function restart() {
        if (!ctx.config || ctx.config.page !== 'batch') {
            return;
        }
        ctx.overlay.destroy();
        setRestartEnabled(false);
        ctx.completionStepNotified = false;
        patchState({status: 'active', phase: 'welcome'});
        if (ctx.download && typeof ctx.download.phaseWelcome === 'function') {
            ctx.download.phaseWelcome();
        }
    }

    // 通知后端当前网页操作指引已完成（GUI 引导据此推进），best-effort、每次向导仅发一次。
    function notifyCompletionStepDone() {
        var stepId = ctx.config && ctx.config.completionStepId;
        if (!stepId || ctx.completionStepNotified) {
            return;
        }
        ctx.completionStepNotified = true;
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
    function hook(name) {
        return ctx.config && ctx.config.hooks && typeof ctx.config.hooks[name] === 'function'
            ? ctx.config.hooks[name] : null;
    }

    function callHook(name, arg) {
        var fn = hook(name);
        return fn ? fn(arg) : undefined;
    }


    // ── 通用等待工具 ────────────────────────────────────────────────────────────
    // 轮询条件 cond()→true 时执行 done；overlay 关闭则自动停止
    function waitFor(cond, done) {
        var revision = ctx.overlay.revision;
        var timer = global.setInterval(function () {
            if (!ctx.overlay.pop || ctx.overlay.revision !== revision) {
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


    ctx.STORAGE_KEY = STORAGE_KEY;
    ctx.EXAMPLE_ID = EXAMPLE_ID;
    ctx.EXAMPLE_URL = EXAMPLE_URL;
    ctx.SPOT_PADDING = SPOT_PADDING;
    ctx.POP_GAP = POP_GAP;
    ctx.VIEWPORT_MARGIN = VIEWPORT_MARGIN;
    ctx.t = t;
    ctx.message = message;
    ctx.resolveMessage = resolveMessage;
    ctx.messageHtml = messageHtml;
    ctx.setMessage = setMessage;
    ctx.escapeHtml = escapeHtml;
    ctx.loadState = loadState;
    ctx.saveState = saveState;
    ctx.patchState = patchState;
    ctx.markCompleted = markCompleted;
    ctx.SKIP_BTN = SKIP_BTN;
    ctx.finish = finish;
    ctx.skip = skip;
    ctx.restart = restart;
    ctx.bindRestartButton = bindRestartButton;
    ctx.refreshRestartLabel = refreshRestartLabel;
    ctx.setRestartEnabled = setRestartEnabled;
    ctx.notifyCompletionStepDone = notifyCompletionStepDone;
    ctx.hook = hook;
    ctx.callHook = callHook;
    ctx.waitFor = waitFor;
    ctx.waitForElement = waitForElement;
    global.PixivOnboardingRuntime = ctx;
})(window);
