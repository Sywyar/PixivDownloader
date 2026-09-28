package top.sywyar.pixivdownload.plugin.api.download.lifecycle;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次执行的公开身份；不携带用户、凭证、路径或内部请求对象。
 * @param attemptId 本次执行的随机标识
 * @param workType 稳定类型，小写字母起始，后续可包含数字和连字符，最多 64 字符
 * @param workId 类型内的不透明作品键，非空、无控制字符，最多 512 个 UTF-16 单元
 */
public record DownloadAttempt(UUID attemptId, String workType, String workId) {
    /**
     * 校验公开身份的格式和长度。
     * @param attemptId 本次执行的随机标识
     * @param workType 稳定类型
     * @param workId 类型内的不透明作品键
     */
    public DownloadAttempt {
        Objects.requireNonNull(attemptId, "attemptId");
        if (workType == null || !workType.matches("[a-z][a-z0-9-]{0,63}")
                || workId == null || workId.isBlank() || workId.length() > 512
                || workId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid download identity");
        }
    }
}
