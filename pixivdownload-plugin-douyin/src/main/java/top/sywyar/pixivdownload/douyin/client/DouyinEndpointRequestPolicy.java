package top.sywyar.pixivdownload.douyin.client;

enum DouyinEndpointRequestPolicy {

    SIGNED,
    UNSIGNED;

    private static final String GENERAL_SEARCH_PATH = "/aweme/v1/web/general/search/single/";

    static DouyinEndpointRequestPolicy forPath(String path) {
        String normalized = path == null ? "" : path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return GENERAL_SEARCH_PATH.equals(normalized) ? UNSIGNED : SIGNED;
    }

    boolean requiresSignature() {
        return this == SIGNED;
    }
}
