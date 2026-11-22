package top.sywyar.pixivdownload.plugin.api.download.submission;

import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;
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
        if (requestId == null) throw new InputFieldException("/requestId", "required");
        new DownloadAttempt(requestId, workType, workId);
        options = copyOptions(options);
    }

    /** 将作品 ID 解析为不带前导零的十进制正整数，仅由需要整数 ID 的提供方调用。
     * @return 数值形式的作品 ID
     */
    public long positiveDecimalWorkId() {
        if (!workId.matches("[1-9][0-9]{0,18}")) throw new InputFieldException("/workId", "invalid_format");
        try {
            return Long.parseLong(workId);
        } catch (NumberFormatException outsideLong) {
            throw new InputFieldException("/workId", "out_of_range");
        }
    }

    /** 对已提供的选项执行提供方的解析，并将解析失败归到对应字段。
     * @param name 提供方声明的选项名
     * @param setter 提供方的解析和赋值操作
     */
    public void readOption(String name, java.util.function.Consumer<String> setter) {
        String value = options.get(name);
        if (value == null) return;
        try {
            setter.accept(value);
        } catch (InputFieldException invalid) {
            throw invalid;
        } catch (IllegalArgumentException invalid) {
            throw new InputFieldException("/options/" + name, "invalid_value");
        }
    }

    /**
     * 校验原始或 hook 返回的选项；不截断输入。
     * @param options 原始选项
     * @return 不可变选项
     */
    public static Map<String, String> copyOptions(Map<String, String> options) {
        if (options == null) throw new InputFieldException("/options", "required");
        if (options.size() > MAX_OPTIONS) throw new InputFieldException("/options", "too_many_properties");
        int bytes = 0;
        for (var entry : options.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9.-]{0,63}"))
                throw new InputFieldException("/options", "invalid_property");
            if (value == null) throw new InputFieldException("/options/" + key, "type");
            // 先按字符拒绝巨大输入，再计算有界编码。
            if (value.length() > MAX_OPTIONS_BYTES)
                throw new InputFieldException("/options/" + key, "too_large");
            bytes += key.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_OPTIONS_BYTES) throw new InputFieldException("/options", "too_large");
        }
        return Map.copyOf(options);
    }
}
