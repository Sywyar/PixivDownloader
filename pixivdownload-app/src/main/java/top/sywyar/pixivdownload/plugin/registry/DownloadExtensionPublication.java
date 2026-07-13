package top.sywyar.pixivdownload.plugin.registry;

/**
 * 精确标识一次下载扩展 owner 全量发布。registry 按对象身份验证，观察字段不构成撤回授权。
 */
public final class DownloadExtensionPublication {

    private final DownloadExtensionOwner owner;
    private final long publicationId;

    DownloadExtensionPublication(DownloadExtensionOwner owner, long publicationId) {
        if (owner == null) {
            throw new IllegalArgumentException("download extension publication owner must not be null");
        }
        if (publicationId <= 0L) {
            throw new IllegalArgumentException("download extension publication id must be positive");
        }
        this.owner = owner;
        this.publicationId = publicationId;
    }

    public DownloadExtensionOwner owner() {
        return owner;
    }

    public long publicationId() {
        return publicationId;
    }

    @Override
    public String toString() {
        return "DownloadExtensionPublication[owner=" + owner
                + ", publicationId=" + publicationId + "]";
    }
}
