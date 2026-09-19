package top.sywyar.pixivdownload.download;

import java.nio.file.Path;
import java.util.List;

/**
 * 动图（ugoira）转换的临时产物路径。
 *
 * <p>临时 zip 与解帧目录仍放在作品自己的下载目录下（与原实现一致），但**必须带上作品 ID**。
 * 原因：配置了 {@code download.artwork-folder-template} 时，同一目录会承载多个作品；
 * 若沿用固定名（{@code _ugoira_frames.zip} / {@code _frames_tmp}），两个作品并发转换
 * 会在同一个共享目录里互相覆盖，并且各自开头的 {@code cleanup} 会把对方正在使用的
 * 解帧目录删掉 —— 表现为转换失败或产物损坏。
 *
 * <p>命名同时收敛了「路径长度占用哨兵」：{@link ArtworkDownloadExecutor} 计算可用文件名长度时
 * 需要预留本作品会占用的名字，若两处各写一份字面量，改名字时极易漏改。
 */
final class UgoiraTempPaths {

    private UgoiraTempPaths() {}

    private static final String TOKEN_PREFIX = "_ugoira_";

    /** 作品级临时产物前缀；artworkId 缺失时退化为固定 token（真实下载不会缺失）。 */
    static String token(Long artworkId) {
        return TOKEN_PREFIX + (artworkId == null ? "unknown" : artworkId);
    }

    /** 下载中的 ugoira zip（写盘时先以 {@code .part} 存在）。 */
    static Path zip(Path downloadPath, Long artworkId) {
        return downloadPath.resolve(token(artworkId) + "_frames.zip");
    }

    /** 解帧与 ffmpeg 中间产物的目录。 */
    static Path framesDir(Path downloadPath, Long artworkId) {
        return downloadPath.resolve(token(artworkId) + "_frames_tmp");
    }

    /**
     * 计算文件名长度上限时需要为「本作品」预留的相对路径 / 文件名。
     *
     * @return 相对作品目录的占用项；调用方负责保证这些名字不会挤掉用户期望的文件名
     */
    static List<String> pathSentinels(Long artworkId) {
        String token = token(artworkId);
        return List.of(token + "_frames.zip.part", token + "_frames_tmp/ffmpeg-progress.log");
    }
}
