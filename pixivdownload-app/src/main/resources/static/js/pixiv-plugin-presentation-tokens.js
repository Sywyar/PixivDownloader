'use strict';
/*
 * Shared plugin presentation token mapping. Backends and manifests expose controlled tokens only; pages call this
 * module to map them to fixed FontAwesome classes and fixed CSS suffixes.
 */
(function (global) {
    var DEFAULT_ICON_KEY = 'puzzle';
    var DEFAULT_COLOR_TOKEN = 'neutral';

    var ICON_CLASSES = {
        puzzle: 'fa-solid fa-puzzle-piece',
        'puzzle-piece': 'fa-solid fa-puzzle-piece',
        store: 'fa-solid fa-store',
        language: 'fa-solid fa-language',
        bolt: 'fa-solid fa-bolt',
        rotate: 'fa-solid fa-rotate',
        bell: 'fa-solid fa-bell',
        'bell-ring': 'fa-solid fa-bell',
        cloud: 'fa-solid fa-cloud',
        shield: 'fa-solid fa-shield-halved',
        'shield-halved': 'fa-solid fa-shield-halved',
        palette: 'fa-solid fa-palette',
        'screwdriver-wrench': 'fa-solid fa-screwdriver-wrench',
        grip: 'fa-solid fa-grip',
        hashtag: 'fa-solid fa-hashtag',
        'paper-plane': 'fa-solid fa-paper-plane',
        film: 'fa-solid fa-film',
        book: 'fa-solid fa-book',
        duplicate: 'fa-solid fa-clone',
        clone: 'fa-solid fa-clone',
        'file-signature': 'fa-solid fa-file-signature',
        heart: 'fa-solid fa-heart',
        download: 'fa-solid fa-download',
        upload: 'fa-solid fa-upload',
        users: 'fa-solid fa-users',
        globe: 'fa-solid fa-globe',
        gear: 'fa-solid fa-gear',
        image: 'fa-solid fa-image',
        images: 'fa-solid fa-images',
        gallery: 'fa-solid fa-images',
        chart: 'fa-solid fa-chart-line',
        'chart-line': 'fa-solid fa-chart-line',
        'layer-group': 'fa-solid fa-layer-group',
        'cloud-arrow-down': 'fa-solid fa-cloud-arrow-down',
        'cloud-arrow-up': 'fa-solid fa-cloud-arrow-up',
        cube: 'fa-solid fa-cube',
        'wand-magic-sparkles': 'fa-solid fa-wand-magic-sparkles',
        sparkles: 'fa-solid fa-wand-magic-sparkles',
        robot: 'fa-solid fa-robot',
        music: 'fa-solid fa-music',
        microphone: 'fa-solid fa-microphone',
        'audio-lines': 'fa-solid fa-wave-square',
        mail: 'fa-solid fa-envelope',
        envelope: 'fa-solid fa-envelope',
        lock: 'fa-solid fa-lock',
        key: 'fa-solid fa-key',
        tag: 'fa-solid fa-tag',
        tags: 'fa-solid fa-tags',
        folder: 'fa-solid fa-folder',
        box: 'fa-solid fa-box',
        plug: 'fa-solid fa-plug',
        code: 'fa-solid fa-code',
        scroll: 'fa-solid fa-scroll',
        filter: 'fa-solid fa-filter',
        wrench: 'fa-solid fa-wrench',
        gauge: 'fa-solid fa-gauge',
        'magnifying-glass': 'fa-solid fa-magnifying-glass',
        star: 'fa-solid fa-star',
        fire: 'fa-solid fa-fire',
        bookmark: 'fa-solid fa-bookmark',
        comments: 'fa-solid fa-comments',
        database: 'fa-solid fa-database',
        wifi: 'fa-solid fa-wifi',
        compass: 'fa-solid fa-compass',
        feather: 'fa-solid fa-feather',
        pen: 'fa-solid fa-pen',
        brush: 'fa-solid fa-brush',
        eye: 'fa-solid fa-eye',
        clock: 'fa-solid fa-clock'
    };

    var COLOR_TOKENS = {
        neutral: 1, gray: 1, pixiv: 1, blue: 1, teal: 1,
        amber: 1, purple: 1, orange: 1, red: 1, green: 1
    };

    function normalized(token) {
        return token == null ? '' : String(token).trim().toLowerCase();
    }

    function hasOwn(obj, key) {
        return Object.prototype.hasOwnProperty.call(obj, key);
    }

    function iconToken(token) {
        var key = normalized(token);
        return hasOwn(ICON_CLASSES, key) ? key : DEFAULT_ICON_KEY;
    }

    function iconClass(token) {
        return ICON_CLASSES[iconToken(token)];
    }

    function colorToken(token) {
        var key = normalized(token);
        return hasOwn(COLOR_TOKENS, key) ? key : DEFAULT_COLOR_TOKEN;
    }

    function colorClass(prefix, token) {
        return String(prefix || '') + colorToken(token);
    }

    global.PixivPluginPresentationTokens = {
        DEFAULT_ICON_KEY: DEFAULT_ICON_KEY,
        DEFAULT_COLOR_TOKEN: DEFAULT_COLOR_TOKEN,
        iconToken: iconToken,
        iconClass: iconClass,
        colorToken: colorToken,
        colorClass: colorClass
    };
})(window);
