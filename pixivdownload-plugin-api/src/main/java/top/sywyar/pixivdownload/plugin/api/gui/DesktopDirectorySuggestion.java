package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.Objects;

/**
 * 插件自有、初始为空的 PATH_DIR 配置字段的不可信候选目录。
 * 宿主校验目录，并仅在用户通过桌面界面明确确认后保存配置。
 * 候选标识必须在轮询和重试期间保持稳定。
 * @param suggestionId 插件内部稳定标识，最多 128 个 UTF-16 单元
 * @param configurationKey 插件声明的配置键，最多 256 个 UTF-16 单元
 * @param directory 候选绝对目录，最多 4096 个 UTF-16 单元
 */
public record DesktopDirectorySuggestion(String suggestionId, String configurationKey, String directory) {
    /**
     * 拒绝空白、超长或包含控制字符的字段。
     * @param suggestionId 插件内部稳定标识
     * @param configurationKey 插件声明的配置键
     * @param directory 候选绝对目录
     */
    public DesktopDirectorySuggestion {
        requireText(suggestionId, 128);
        requireText(configurationKey, 256);
        requireText(directory, 4096);
    }

    private static void requireText(String value, int maximum) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid directory suggestion");
        }
    }
}
