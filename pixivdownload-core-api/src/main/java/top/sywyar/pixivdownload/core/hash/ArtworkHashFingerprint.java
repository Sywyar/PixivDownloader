package top.sywyar.pixivdownload.core.hash;

/**
 * 核心哈希索引的轻量变更指纹。
 *
 * @param rowCount 哈希索引中的记录数量
 * @param latestCreatedTime 索引中最新记录的创建时间
 */
public record ArtworkHashFingerprint(long rowCount, Long latestCreatedTime) {
}
