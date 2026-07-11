package top.sywyar.pixivdownload.plugin.api.schedule.source;

import java.net.URI;

/**
 * 来源定义编辑、回灌、摘要与校验提示的同源前端模块。模块只通过宿主提供的受控上下文注册行为，
 * 不携带内联脚本、原始 HTML 或外部 URL。
 */
public record ScheduledSourceFrontendContribution(
        int contractVersion,
        String moduleUrl
) {

    public static final int CURRENT_CONTRACT_VERSION = 1;

    public ScheduledSourceFrontendContribution {
        if (contractVersion <= 0) {
            throw new IllegalArgumentException("frontend contract version must be positive");
        }
        if (moduleUrl == null || moduleUrl.isBlank()) {
            throw new IllegalArgumentException("frontend module URL must be a same-origin absolute path");
        }
        moduleUrl = moduleUrl.trim();
        if (moduleUrl.indexOf('\\') >= 0 || moduleUrl.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("frontend module URL must not contain backslashes or control characters");
        }
        URI uri = URI.create(moduleUrl);
        String rawPath = uri.getRawPath();
        if (uri.isAbsolute()
                || uri.getRawAuthority() != null
                || rawPath == null
                || !rawPath.startsWith("/")
                || rawPath.startsWith("//")
                || !uri.normalize().getRawPath().equals(rawPath)) {
            throw new IllegalArgumentException("frontend module URL must be a normalized same-origin absolute path");
        }
    }
}
