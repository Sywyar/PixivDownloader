package top.sywyar.pixivdownload.douyin.client;

import org.springframework.http.HttpMethod;

enum DouyinEndpointRequestPolicy {

    SIGNED_GET(HttpMethod.GET, true),
    UNSIGNED_GET(HttpMethod.GET, false),
    SIGNED_POST(HttpMethod.POST, true);

    private static final String GENERAL_SEARCH_PATH = "/aweme/v1/web/general/search/single/";
    private static final String FAVORITE_WORKS_PATH = "/aweme/v1/web/aweme/listcollection/";

    private final HttpMethod method;
    private final boolean requiresSignature;

    DouyinEndpointRequestPolicy(HttpMethod method, boolean requiresSignature) {
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

    HttpMethod method() {
        return method;
    }

    boolean requiresSignature() {
        return requiresSignature;
    }
}
