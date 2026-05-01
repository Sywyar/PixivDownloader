(function (global) {
    'use strict';

    var STORAGE_KEY = 'pixiv_theme';
    var DARK = 'dark';
    var LIGHT = 'light';
    var buttons = [];

    var MOON_ICON = '' +
        '<svg viewBox="0 0 24 24" aria-hidden="true">' +
        '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>' +
        '</svg>';

    var SUN_ICON = '' +
        '<svg viewBox="0 0 24 24" aria-hidden="true">' +
        '<circle cx="12" cy="12" r="4"/>' +
        '<path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>' +
        '</svg>';

    function normalize(theme) {
        return theme === DARK ? DARK : LIGHT;
    }

    function storedTheme() {
        try {
            return normalize(global.localStorage.getItem(STORAGE_KEY));
        } catch (e) {
            return LIGHT;
        }
    }

    function setDocumentTheme(theme) {
        theme = normalize(theme);
        if (global.document && global.document.documentElement) {
            var root = global.document.documentElement;
            root.setAttribute('data-theme', theme);
            root.style.colorScheme = theme;
        }
        return theme;
    }

    function currentTheme() {
        if (!global.document || !global.document.documentElement) {
            return storedTheme();
        }
        return normalize(global.document.documentElement.getAttribute('data-theme') || storedTheme());
    }

    function nextTitle(theme) {
        return theme === DARK ? 'Switch to light mode' : 'Switch to dark mode';
    }

    function nextIcon(theme) {
        return theme === DARK ? SUN_ICON : MOON_ICON;
    }

    function syncButton(button) {
        var theme = currentTheme();
        button.innerHTML = nextIcon(theme);
        button.title = nextTitle(theme);
        button.setAttribute('aria-label', nextTitle(theme));
        button.dataset.theme = theme;
    }

    function apply(theme, persist) {
        theme = setDocumentTheme(theme);
        if (persist !== false) {
            try {
                global.localStorage.setItem(STORAGE_KEY, theme);
            } catch (e) {
                // Ignore storage failures.
            }
        }
        buttons.forEach(syncButton);
        try {
            global.dispatchEvent(new CustomEvent('pixiv-theme-change', {detail: {theme: theme}}));
        } catch (e) {
            // CustomEvent can be unavailable in older embedded browsers.
        }
        return theme;
    }

    function toggle() {
        return apply(currentTheme() === DARK ? LIGHT : DARK, true);
    }

    function mount(options) {
        var mountPoint = options && options.mountPoint;
        if (!mountPoint) {
            throw new Error('mountPoint is required');
        }
        var variant = (options && options.variant) || 'default';
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'pixiv-theme-toggle pixiv-theme-toggle--' + variant;
        button.addEventListener('click', toggle);
        buttons.push(button);
        mountPoint.appendChild(button);
        syncButton(button);
        return {
            element: button,
            refresh: function () {
                syncButton(button);
            }
        };
    }

    apply(storedTheme(), false);

    global.PixivTheme = {
        apply: apply,
        current: currentTheme,
        mount: mount,
        toggle: toggle
    };
})(window);
