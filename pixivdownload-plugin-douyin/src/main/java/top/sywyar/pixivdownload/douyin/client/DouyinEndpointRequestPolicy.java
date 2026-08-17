package top.sywyar.pixivdownload.douyin.client;

enum DouyinEndpointRequestPolicy {

    SIGNED_GET("GET", true),
    UNSIGNED_GET("GET", false),
    SIGNED_POST("POST", true);

    private static final String GENERAL_SEARCH_PATH = "/aweme/v1/web/general/search/single/";
    private static final String FAVORITE_WORKS_PATH = "/aweme/v1/web/aweme/listcollection/";

    private final String method;
    private final boolean requiresSignature;

    DouyinEndpointRequestPolicy(String method, boolean requiresSignature) {
        this.method = method;
        this.requiresSignature = requiresSignature;
    }

    static DouyinEndpointRequestPolicy forPath(String path) {
        String normalized = path == null ? "" : path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (GENERAL_SEARCH_PATH.equals(normalized)) {
            return UNSIGNED_GET;
        }
        if (FAVORITE_WORKS_PATH.equals(normalized)) {
            return SIGNED_POST;
        }
        return SIGNED_GET;
    }

    String method() {
        return method;
    }

    boolean requiresSignature() {
        return requiresSignature;
    }
}
