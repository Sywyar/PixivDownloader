package top.sywyar.pixivdownload.plugin.web;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 校验插件前端模块确实由声明它的插件包提供。路径校验只接受规范化的同源 JavaScript 资源，
 * 随后按 serving 使用的同一 owner-only 资源根解析，确认资源确实存在于该 generation 的插件包中。
 */
@Component
public final class PluginOwnedWebAssetValidator {

    private final StaticResourceRegistry staticResourceRegistry;

    public PluginOwnedWebAssetValidator(StaticResourceRegistry staticResourceRegistry) {
        this.staticResourceRegistry = Objects.requireNonNull(staticResourceRegistry, "static resource registry");
    }

    /**
     * 从插件自己的静态资源声明中校验 JavaScript 模块。
     *
     * @param owner             插件注册权威身份
     * @param moduleUrl         待校验的同源模块 URL
     * @param contributionLabel 诊断中的贡献类型
     */
    public void validateOwnedJavaScript(PluginRegistry.RegisteredPlugin owner,
                                        String moduleUrl,
                                        String contributionLabel) {
        Objects.requireNonNull(owner, "plugin owner");
        validateOwnedJavaScript(
                owner, staticResourceRegistry.resourcesFor(owner), moduleUrl, contributionLabel);
    }

    /**
     * 使用调用方已经一次性读取的 owner 静态资源快照校验 JavaScript 模块。
     */
    public void validateOwnedJavaScript(PluginRegistry.RegisteredPlugin owner,
                                        List<StaticResourceRegistry.RegisteredStaticResource> resources,
                                        String moduleUrl,
                                        String contributionLabel) {
        Objects.requireNonNull(owner, "plugin owner");
        Objects.requireNonNull(resources, "owner static resources");
        String path = requireSafeModulePath(moduleUrl, contributionLabel);

        for (StaticResourceRegistry.RegisteredStaticResource registered : resources) {
            if (registered == null) {
                throw invalid(contributionLabel, moduleUrl,
                        "owner static resource snapshot contains null", null);
            }
            if (registered.owner() != owner) {
                throw invalid(contributionLabel, moduleUrl,
                        "static resource snapshot belongs to another registered owner", null);
            }
            var resource = registered.contribution();
            if (resource == null) {
                throw invalid(contributionLabel, moduleUrl,
                        "owner declared a null static resource contribution", null);
            }
            if (!owner.id().equals(resource.pluginId())) {
                throw invalid(contributionLabel, moduleUrl,
                        "static resource owner does not match registered plugin", null);
            }
            String suffix = resolveRelativeAsset(resource, path);
            if (suffix == null) {
                continue;
            }
            Resource resolved;
            try {
                resolved = registered.location().createRelative(suffix);
                if (!isUnderRegisteredRoot(registered.location(), resolved)) {
                    throw invalid(contributionLabel, moduleUrl,
                            "resolved module escapes the owner resource root", null);
                }
            } catch (IOException failure) {
                throw invalid(contributionLabel, moduleUrl,
                        "failed to resolve module under the owner resource root", failure);
            }
            if (!isDirectlyReadable(resolved)) {
                throw invalid(contributionLabel, moduleUrl,
                        "module is declared by the owner but missing from its package", null);
            }
            return;
        }
        throw invalid(contributionLabel, moduleUrl,
                "module is not covered by an owner static resource contribution", null);
    }

    private static boolean isDirectlyReadable(Resource resource) {
        try {
            URLConnection connection = resource.getURL().openConnection();
            // JarURLConnection 默认全局缓存会在 Windows 锁住可热卸载插件 JAR；校验只需一次短读。
            connection.setUseCaches(false);
            try (InputStream ignored = connection.getInputStream()) {
                return true;
            }
        } catch (IOException failure) {
            return false;
        }
    }

    private static String requireSafeModulePath(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw invalid(label, value, "module URL must be a normalized same-origin absolute path", null);
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!isSafePathCharacter(current)) {
                throw invalid(label, value, "module URL contains a forbidden character", null);
            }
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getRawPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || path == null || !path.equals(value)
                    || path.length() <= 1 || !path.startsWith("/") || path.startsWith("//")
                    || path.contains("//") || !uri.normalize().getRawPath().equals(path)
                    || !path.toLowerCase(Locale.ROOT).endsWith(".js")) {
                throw invalid(label, value, "module URL must identify one normalized same-origin .js asset", null);
            }
            for (String segment : path.split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) {
                    throw invalid(label, value, "module URL contains a traversal segment", null);
                }
            }
            return path;
        } catch (IllegalArgumentException failure) {
            throw invalid(label, value, "module URL is invalid", failure);
        }
    }

    private static boolean isSafePathCharacter(char value) {
        return value == '/' || value == '-' || value == '_' || value == '.' || value == '~'
                || value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9';
    }

    private static boolean isUnderRegisteredRoot(Resource root, Resource resolved) throws IOException {
        String rootValue = root.getURL().toExternalForm();
        return rootValue.endsWith("/") && resolved.getURL().toExternalForm().startsWith(rootValue);
    }

    private static String resolveRelativeAsset(StaticResourceContribution resource, String modulePath) {
        String publicPath = resource.publicPathPrefix();
        String location = resource.classpathLocation();
        if (publicPath == null || location == null
                || !location.startsWith("classpath:/") || !location.endsWith("/")) {
            return null;
        }
        String suffix;
        if (resource.exactFile()) {
            if (!modulePath.equals(publicPath)) {
                return null;
            }
            int slash = publicPath.lastIndexOf('/');
            suffix = slash < 0 ? publicPath : publicPath.substring(slash + 1);
        } else {
            if (!publicPath.endsWith("/") || !modulePath.startsWith(publicPath)) {
                return null;
            }
            suffix = modulePath.substring(publicPath.length());
            if (suffix.isEmpty()) {
                return null;
            }
        }
        return suffix;
    }

    private static IllegalStateException invalid(String label, String moduleUrl,
                                                 String reason, Throwable cause) {
        String prefix = label == null || label.isBlank() ? "plugin frontend module" : label.trim();
        String message = prefix + " is invalid: " + reason + " (moduleUrl=" + moduleUrl + ")";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
