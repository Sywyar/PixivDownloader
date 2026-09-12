package top.sywyar.pixivdownload.plugin.signature.community;

/** 三种互不替代的操作证明；每种用途有独立的冻结签名域。 */
public enum CommunityOperation {
    PUBLISHER_KEY_ROTATION("pixivdownloader-community-publisher-key-rotation-v1"),
    VERSION_STATUS_REQUEST("pixivdownloader-community-version-status-request-v1"),
    OWNERSHIP_TRANSFER("pixivdownloader-community-ownership-transfer-v1");

    private final String domain;

    CommunityOperation(String domain) { this.domain = domain; }

    public String domain() { return domain; }
}
