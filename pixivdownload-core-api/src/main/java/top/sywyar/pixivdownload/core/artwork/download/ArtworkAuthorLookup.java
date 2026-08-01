package top.sywyar.pixivdownload.core.artwork.download;

/**
 * 补齐插画作者关联的核心端口。
 */
public interface ArtworkAuthorLookup {

    /**
     * 解析尚未携带作者事实的插画；实现可以按自身策略异步执行。
     *
     * @param artworkId 插画 id
     * @param credential 查询所需的不透明凭证，可为 {@code null}
     */
    void resolveMissing(long artworkId, String credential);
}
