package top.sywyar.pixivdownload.core.artwork.download;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * 插画下载判重的核心查询端口。
 */
public interface ArtworkDownloadLookup {

    /**
     * 判断插画是否已经下载。
     *
     * @param artworkId   插画 id
     * @param verifyFiles 是否校验磁盘文件并修复陈旧历史
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isDownloaded(long artworkId, boolean verifyFiles);

    /**
     * 在作品文件锁内读取完整登记的受管产物。没有有效登记时返回空 Optional，
     * 已登记但没有可替换原件（例如只读导入）时返回空集合。
     * @param artworkId 作品身份
     * @return 旧版本可以随发布移除的文件
     * @throws IOException 受管文件无法安全定位
     */
    Optional<Set<Path>> registeredOutputs(long artworkId) throws IOException;
}
