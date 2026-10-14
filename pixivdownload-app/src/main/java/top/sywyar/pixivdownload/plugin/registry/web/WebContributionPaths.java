package top.sywyar.pixivdownload.plugin.registry.web;

final class WebContributionPaths {

    private WebContributionPaths() {
    }

    // 浏览器会将双斜杠及 /\host 视为跨源地址；路径其余部分与模板占位保持原样。
    static boolean isSameOriginAbsolutePath(String value) {
        if (value.isEmpty() || value.charAt(0) != '/') {
            return false;
        }
        if (value.length() >= 2) {
            char second = value.charAt(1);
            return second != '/' && second != '\\';
        }
        return true;
    }
}
