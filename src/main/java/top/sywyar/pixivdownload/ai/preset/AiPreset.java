package top.sywyar.pixivdownload.ai.preset;

/**
 * 单个 AI 服务商预设（OpenAI 兼容端点）。
 *
 * @param id                预设标识（小写连字符），如 {@code openai}、{@code deepseek}、{@code custom}
 * @param displayNameKey    显示名 i18n key（如 {@code ai.preset.name.openai}）
 * @param baseUrl           OpenAI 兼容端点基础地址；{@code custom} / 本地预设下可为空或本机地址
 * @param defaultModel      预设的默认模型名（仅作建议，用户可改）
 * @param defaultUseProxy   选中该预设时"使用代理"开关的建议默认值（海外为 true、国内 / 本地为 false）
 * @param credentialHelpKey 凭证 / 获取方式提示 i18n key
 */
public record AiPreset(
        String id,
        String displayNameKey,
        String baseUrl,
        String defaultModel,
        boolean defaultUseProxy,
        String credentialHelpKey
) {

    /** {@code custom} 哨兵 id。GUI 选中它时解锁 base-url。 */
    public static final String CUSTOM_ID = "custom";

    /** 是否是"自定义"哨兵（base-url 不锁定）。 */
    public boolean isCustom() {
        return CUSTOM_ID.equals(id);
    }
}
