(function (global) {
    'use strict';

    var version = document.currentScript.getAttribute('data-pixiv-assets');

    global.PixivAssets = {
        url: function (value) {
            var url = new URL(value, document.baseURI);
            if (!version || url.origin !== global.location.origin
                    || (url.protocol !== 'http:' && url.protocol !== 'https:')) return value;
            // 新启动隔离旧浏览器缓存；同一次运行复用 URL，并由服务端内容 ETag 校验。
            url.searchParams.set('_runtime', version);
            return url.href;
        }
    };
})(window);
