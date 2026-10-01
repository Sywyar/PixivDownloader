package top.sywyar.pixivdownload.plugin.api.download.submission;

import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次管理员下载命令。类型解释选项；身份、凭据和来源不能通过选项改写。
 * 同一进程保留期内，相同 requestId 与相同内容返回原任务；不同内容冲突。
 * @param requestId 调用者生成的幂等键，重试时保持不变
 * @param workType 类型内执行器的稳定键
 * @param workId 类型内的不透明作品键
 * @param options 无凭据的类型自有选项
 */
public record DownloadSubmission(UUID requestId, String workType, String workId, Map<String, String> options) {
    /** 选项累计 UTF-8 字节上限。 */
    public static final int MAX_OPTIONS_BYTES = 16 * 1024;
    /** 单次命令的选项数量上限。 */
    public static final int MAX_OPTIONS = 32;

    /**
     * 校验身份并防御性复制选项。
     * @param requestId 幂等请求标识
     * @param workType 作品类型
     * @param workId 作品键
     * @param options 类型自有选项
     */
    public DownloadSubmission {
        new DownloadAttempt(Objects.requireNonNull(requestId, "requestId"), workType, workId);
        options = copyOptions(options);
    }

    /**
     * 校验原始或 hook 返回的选项；不截断输入。
     * @param options 原始选项
     * @return 不可变选项
     */
    public static Map<String, String> copyOptions(Map<String, String> options) {
        Objects.requireNonNull(options, "options");
        if (options.size() > MAX_OPTIONS) throw new IllegalArgumentException("too many download options");
        int bytes = 0;
        for (var entry : options.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "option key");
            String value = Objects.requireNonNull(entry.getValue(), "option value");
            if (!key.matches("[a-zA-Z][a-zA-Z0-9.-]{0,63}"))
                throw new IllegalArgumentException("invalid download option key");
            // 先按字符拒绝巨大输入，再计算有界编码。
            if (value.length() > MAX_OPTIONS_BYTES)
                throw new IllegalArgumentException("download option too large");
            bytes += key.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_OPTIONS_BYTES) throw new IllegalArgumentException("download options too large");
        }
        return Map.copyOf(options);
    }
}
