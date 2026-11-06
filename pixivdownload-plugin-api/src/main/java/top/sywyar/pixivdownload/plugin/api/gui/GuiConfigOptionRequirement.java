package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 枚举选项依赖的配置能力。能力缺席只改变标签，不构成鉴权、远程可用性或执行许可。
 * 提供者与消费者通过稳定能力标识协作，不引用彼此的插件 ID 或私有配置。
 *
 * @param fieldKey 同一贡献中的枚举字段
 * @param value 字段已有的枚举值
 * @param capability 配置能力标识
 * @param unavailableLabelKey 能力缺席时使用的完整选项标签，归字段的 i18n namespace
 */
public record GuiConfigOptionRequirement(
        String fieldKey,
        String value,
        String capability,
        String unavailableLabelKey
) {
    /** 校验完整的纯值声明，不接受空标识。 */
    public GuiConfigOptionRequirement {
        if (fieldKey == null || fieldKey.isBlank() || value == null || value.isBlank()
                || capability == null || capability.isBlank()
                || unavailableLabelKey == null || unavailableLabelKey.isBlank()) {
            throw new IllegalArgumentException("Configuration option requirement must not contain blank values");
        }
    }
}
