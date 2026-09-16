package top.sywyar.pixivdownload.core.work.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 目标文件系统可确认的上限；0 表示未知或没有固定上限，不据此拒绝路径。
 * @param componentLength 单个路径组成部分的最大长度
 * @param pathLength 包含结尾空字符预算的完整路径长度上限
 * @param utf8Bytes 是否按 UTF-8 字节计数；否则按 UTF-16 代码单元计数
 */
public record DownloadPathLimits(int componentLength, int pathLength, boolean utf8Bytes) {
    /** 没有可确认的固定长度限制。 */
    public static final DownloadPathLimits UNKNOWN = new DownloadPathLimits(0, 0, false);

    /**
     * 同时检查完整路径和每个组成部分；调用方应包含实际使用的临时后缀。
     * @param candidate 待检查的产物或临时文件路径
     * @return 未超出任何已知限制时为 {@code true}
     */
    public boolean accepts(Path candidate) {
        Path absolute = candidate.toAbsolutePath().normalize();
        if (pathLength > 0 && length(absolute.toString()) >= pathLength) return false;
        for (Path part : absolute) {
            if (componentLength > 0 && length(part.toString()) > componentLength) return false;
        }
        return true;
    }

    private int length(String value) {
        return utf8Bytes ? value.getBytes(StandardCharsets.UTF_8).length : value.length();
    }
}
