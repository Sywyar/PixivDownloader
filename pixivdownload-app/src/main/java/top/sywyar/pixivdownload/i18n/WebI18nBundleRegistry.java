package top.sywyar.pixivdownload.i18n;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Web i18n bundle 注册中心。收集各插件声明的 {@link I18nContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁
 * （{@code /api/i18n/**} 在每次请求上读取）。
 * <p>
 * bundle 解析经声明方插件的 ClassLoader（{@link RegisteredBundle#classLoader()}），该 ClassLoader 由
 * {@link PluginRegistry} 的每条注册（{@link PluginRegistry.RegisteredPlugin#classLoader()}）权威提供：内置插件
 * 是应用 ClassLoader（解析结果与退役前的静态 map 一致），外置插件是发现桥接捕获的该插件自身 ClassLoader、
 * 卸载时随之清缓存。故本注册中心消费 {@link PluginRegistry#registeredPlugins()}（带来源 + ClassLoader），
 * <b>不</b>从 {@code plugin.getClass().getClassLoader()} 自行推导——后者对「插件实例由共享 / 父 ClassLoader 创建」
 * 的外置插件会误解析到错误的 ClassLoader。
 * <p>
 * 安装态但未进入活动快照的外置插件只暴露展示身份所需的只读 i18n fallback：按 descriptor 声明的
 * {@code displayNamespace} 从已安装 artifact 内读取 {@code i18n/web/<namespace>.properties}，不注册路由、
 * 静态资源、导航、userscript 或其它运行期 contribution。这样插件管理页 / GUI 状态可以解析未启动插件的名称与简介，
 * 同时禁用 / 停止插件的服务入口仍按「未声明即 404」清退。
 * <p>
 * namespace 全局唯一：跨插件用不同 baseName 指向同一 namespace 会让
 * {@code /api/i18n/messages/{namespace}} 解析不确定，故 namespace 冲突
 * （跨插件与同一批次内）一律在注册期拒绝，使应用启动失败而不是带病运行。
 */
@Component
public class WebI18nBundleRegistry {

    private static final BundleLoader RESOURCE_BUNDLE_LOADER = WebI18nBundleRegistry::loadResourceBundle;

    /** 一条已注册 namespace、声明方插件与解析用 ClassLoader。 */
    public record RegisteredBundle(String pluginId, I18nContribution contribution, ClassLoader classLoader,
                                   BundleLoader loader) {

        public RegisteredBundle(String pluginId, I18nContribution contribution, ClassLoader classLoader) {
            this(pluginId, contribution, classLoader, RESOURCE_BUNDLE_LOADER);
        }

        public Map<String, String> load(Locale locale) {
            return loader.load(contribution, classLoader, locale);
        }
    }

    @FunctionalInterface
    public interface BundleLoader {
        Map<String, String> load(I18nContribution contribution, ClassLoader classLoader, Locale locale);
    }

    private final Object lock = new Object();
    private final Supplier<List<InstalledPlugin>> installedPlugins;

    private volatile List<RegisteredBundle> activeSnapshot = List.of();
    private volatile List<RegisteredBundle> installedSnapshot = List.of();

    @Autowired
    public WebI18nBundleRegistry(PluginRegistry pluginRegistry, ObjectProvider<ExternalPluginInstaller> installer) {
        this(pluginRegistry, () -> {
            ExternalPluginInstaller value = installer.getIfAvailable();
            return value != null ? value.listInstalled() : List.of();
        });
    }

    public WebI18nBundleRegistry(PluginRegistry pluginRegistry) {
        this(pluginRegistry, List::of);
    }

    WebI18nBundleRegistry(PluginRegistry pluginRegistry, Supplier<List<InstalledPlugin>> installedPlugins) {
        this.installedPlugins = installedPlugins != null ? installedPlugins : List::of;
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            PixivFeaturePlugin plugin = registered.plugin();
            List<I18nContribution> contributions = plugin.i18n();
            if (!contributions.isEmpty()) {
                register(plugin.id(), registered.classLoader(), contributions);
            }
        }
        refreshInstalledSnapshot();
    }

    /**
     * 注册一个插件声明的全部 i18n namespace。同一 pluginId 重复注册、namespace 非法，
     * 或 namespace 与已注册项冲突都立即抛出，使应用启动失败而不是带病运行。
     */
    public void register(String pluginId, ClassLoader classLoader, List<I18nContribution> contributions) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("i18n contribution without pluginId");
        }
        if (classLoader == null) {
            throw new IllegalStateException("i18n contribution without classLoader (plugin: " + pluginId + ")");
        }
        if (contributions == null || contributions.isEmpty()) {
            throw new IllegalStateException("empty i18n contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (activeSnapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("i18n already registered for plugin: " + pluginId);
            }
            Set<String> namespaces = activeSnapshot.stream()
                    .map(registered -> registered.contribution().namespace())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredBundle> next = new ArrayList<>(activeSnapshot);
            for (I18nContribution contribution : contributions) {
                validate(contribution, pluginId);
                if (!namespaces.add(contribution.namespace())) {
                    throw new IllegalStateException("duplicate i18n namespace: "
                            + contribution.namespace() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredBundle(pluginId, contribution, classLoader));
            }
            activeSnapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部 i18n namespace。插件可以不声明任何 namespace，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            activeSnapshot = activeSnapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
            refreshInstalledSnapshot();
        }
    }

    /** 按注册顺序返回全部已注册 bundle 的不可变快照。 */
    public List<RegisteredBundle> bundles() {
        refreshInstalledSnapshot();
        return mergedSnapshot();
    }

    /** namespace → 已注册 bundle（含 baseName 与解析用 ClassLoader），未注册返回 {@code null}。 */
    public RegisteredBundle resolve(String namespace) {
        for (RegisteredBundle registered : activeSnapshot) {
            if (registered.contribution().namespace().equals(namespace)) {
                return registered;
            }
        }
        for (RegisteredBundle registered : installedSnapshot) {
            if (registered.contribution().namespace().equals(namespace)) {
                return registered;
            }
        }
        synchronized (lock) {
            refreshInstalledSnapshot();
        }
        for (RegisteredBundle registered : installedSnapshot) {
            if (registered.contribution().namespace().equals(namespace)) {
                return registered;
            }
        }
        return null;
    }

    /**
     * 按声明的 {@link I18nContribution#order()} 升序返回全部受支持的 namespace，
     * 同 order 保持注册顺序（稳定排序）。{@code /api/i18n/meta} 据此对外暴露 namespace 列表，
     * 顺序由各插件 contribution 自带、不依赖插件注册先后，故跨插件合并不漂移。
     */
    public List<String> supportedNamespaces() {
        refreshInstalledSnapshot();
        return mergedSnapshot().stream()
                .sorted(Comparator.comparingInt(registered -> registered.contribution().order()))
                .map(registered -> registered.contribution().namespace())
                .toList();
    }

    private static void validate(I18nContribution contribution, String pluginId) {
        if (contribution == null) {
            throw new IllegalStateException("null i18n contribution (plugin: " + pluginId + ")");
        }
        if (contribution.namespace() == null || contribution.namespace().isBlank()) {
            throw new IllegalStateException("i18n contribution without namespace (plugin: " + pluginId + ")");
        }
        if (contribution.baseName() == null || contribution.baseName().isBlank()) {
            throw new IllegalStateException("i18n contribution without baseName: "
                    + contribution.namespace() + " (plugin: " + pluginId + ")");
        }
    }

    private List<RegisteredBundle> mergedSnapshot() {
        List<RegisteredBundle> merged = new ArrayList<>(activeSnapshot);
        Set<String> activeNamespaces = activeSnapshot.stream()
                .map(registered -> registered.contribution().namespace())
                .collect(Collectors.toCollection(HashSet::new));
        for (RegisteredBundle installed : installedSnapshot) {
            if (!activeNamespaces.contains(installed.contribution().namespace())) {
                merged.add(installed);
            }
        }
        return List.copyOf(merged);
    }

    private void refreshInstalledSnapshot() {
        installedSnapshot = installedBundles(installedPlugins.get());
    }

    private static List<RegisteredBundle> installedBundles(List<InstalledPlugin> installedPlugins) {
        List<RegisteredBundle> bundles = new ArrayList<>();
        Set<String> namespaces = new HashSet<>();
        for (InstalledPlugin installed : installedPlugins == null ? List.<InstalledPlugin>of() : installedPlugins) {
            String namespace = trimToNull(installed.descriptor().displayNamespace());
            if (namespace == null || !namespaces.add(namespace)) {
                continue;
            }
            I18nContribution contribution = new I18nContribution(namespace, "i18n.web." + namespace);
            Path artifact = installed.path();
            bundles.add(new RegisteredBundle(
                    installed.id(),
                    contribution,
                    WebI18nBundleRegistry.class.getClassLoader(),
                    (ns, ignored, locale) -> loadInstalledBundle(artifact, ns.baseName(), locale)));
        }
        return List.copyOf(bundles);
    }

    private static Map<String, String> loadResourceBundle(
            I18nContribution contribution, ClassLoader classLoader, Locale locale) {
        java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle(
                contribution.baseName(), locale, classLoader, WebI18nService.NO_FALLBACK_CONTROL);
        Map<String, String> messages = new LinkedHashMap<>();
        for (String key : new TreeSet<>(bundle.keySet())) {
            messages.putIfAbsent(WebI18nService.normalizeKey(key), bundle.getString(key));
        }
        return messages;
    }

    private static Map<String, String> loadInstalledBundle(Path artifact, String baseName, Locale locale) {
        Map<String, String> merged = new LinkedHashMap<>();
        boolean loaded = false;
        for (String resource : resourceNames(baseName, locale)) {
            Map<String, String> values = readArtifactProperties(artifact, resource);
            if (values != null) {
                merged.putAll(values);
                loaded = true;
            }
        }
        if (!loaded) {
            throw new MissingResourceException("Missing installed plugin i18n bundle " + baseName,
                    baseName, "");
        }
        Map<String, String> sorted = new LinkedHashMap<>();
        for (String key : new TreeSet<>(merged.keySet())) {
            sorted.put(key, merged.get(key));
        }
        return sorted;
    }

    private static List<String> resourceNames(String baseName, Locale locale) {
        String basePath = baseName.replace('.', '/');
        List<String> names = new ArrayList<>();
        names.add(basePath + ".properties");
        String language = locale == null ? "" : locale.getLanguage();
        if (language != null && !language.isBlank()) {
            names.add(basePath + "_" + language + ".properties");
            String country = locale.getCountry();
            if (country != null && !country.isBlank()) {
                names.add(basePath + "_" + language + "_" + country + ".properties");
            }
        }
        return names;
    }

    private static Map<String, String> readArtifactProperties(Path artifact, String resourceName) {
        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            ZipEntry entry = zip.getEntry(resourceName);
            if (entry == null) {
                entry = zip.getEntry("classes/" + resourceName);
            }
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(reader);
                Map<String, String> values = new LinkedHashMap<>();
                for (String key : properties.stringPropertyNames()) {
                    values.put(WebI18nService.normalizeKey(key), properties.getProperty(key));
                }
                return values;
            }
        } catch (IOException e) {
            throw new MissingResourceException("Cannot read installed plugin i18n bundle "
                    + resourceName + " from " + artifact + ": " + e.getMessage(), resourceName, "");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
