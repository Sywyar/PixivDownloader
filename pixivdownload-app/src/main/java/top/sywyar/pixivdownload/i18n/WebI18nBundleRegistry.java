package top.sywyar.pixivdownload.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
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
 * <strong>exact 与 effective 严格分离：</strong>
 * <ul>
 *   <li>{@link RegisteredBundle#loadExact} 只加载某个语言自己的物理资源文件
 *       （{@code baseName + resourceSuffix + .properties}），绝不隐式合并 root 或其它语言；
 *       覆盖率检查基于 exact，不允许调用 effective。</li>
 *   <li>{@link RegisteredBundle#loadEffective} 仅用于运行期展示，按 catalog 回退链
 *       源语言 → fallback → 目标语言 顺序合并（后写入的目标语言覆盖前面的回退值），
 *       实现「目标语言 → en-US → zh-CN」的展示契约。</li>
 * </ul>
 * <p>
 * 活动插件注册时只在当前栈帧内使用声明方 ClassLoader，一次性物化 catalog 可见语言的 exact
 * UTF-8 properties；发布后的 {@link RegisteredBundle} 只保存纯 JDK 不可变 map 与
 * {@link LocaleCatalog} 快照，不再持有或调用插件 ClassLoader。ClassLoader 由
 * {@link PluginRegistry.RegisteredPlugin#classLoader()} 权威提供，不能从插件实例的类自行推导。
 * <p>
 * 安装态但未进入活动快照的外置插件只暴露展示身份所需的只读 i18n fallback：按 descriptor 声明的
 * {@code displayNamespace} 从已安装 artifact 内读取 {@code i18n/web/<namespace>.properties}，不注册路由、
 * 静态资源、导航、userscript 或其它运行期 contribution。这样插件管理页 / GUI 状态可以解析未启动插件的名称与简介，
 * 同时禁用 / 停止插件的服务入口仍按「未声明即 404」清退。
 * <p>
 * namespace 全局唯一：跨插件用不同 baseName 指向同一 namespace 会让
 * {@code /api/i18n/messages/{namespace}} 解析不确定，故 namespace 冲突
 * （跨插件与同一批次内）一律在注册期拒绝，使应用启动失败而不是带病运行。
 * <p>
 * 外部第三方插件缺失某个正式语言不会阻断启动：effective 按插件实际存在的语言文件回退
 * （只提供源语言时其它语言显示中文）；第一方核心与官方插件的完整性由仓库 CI 检查。
 */
@Component
public class WebI18nBundleRegistry implements NamespaceMessageResolver {

    private static final Logger log = LoggerFactory.getLogger(WebI18nBundleRegistry.class);

    /** 一条已注册 namespace 的纯宿主快照；活动来源保存物化 exact map，安装态 fallback 只保存 artifact 路径。 */
    public record RegisteredBundle(
            String pluginId,
            I18nContribution contribution,
            Map<String, Map<String, String>> messagesByLocale,
            Path installedArtifact,
            LocaleCatalog catalog) {

        public RegisteredBundle {
            Map<String, Map<String, String>> immutable = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : messagesByLocale.entrySet()) {
                immutable.put(entry.getKey(), immutableMessages(entry.getValue()));
            }
            messagesByLocale = Collections.unmodifiableMap(immutable);
            catalog = catalog == null ? LocaleCatalog.defaultCatalog() : catalog;
        }

        /**
         * exact bundle：只加载目标语言自己的物理资源文件，不合并 root / 其它语言。
         * 物理文件不存在时抛 {@link MissingResourceException}（运行时展示请用 {@link #loadEffective}）。
         */
        public Map<String, String> loadExact(Locale locale) {
            LocaleDescriptor target = catalog.resolve(locale);
            if (installedArtifact != null) {
                return loadInstalledExact(installedArtifact, contribution.baseName(), target);
            }
            Map<String, String> messages = messagesByLocale.get(target.tag());
            if (messages == null) {
                throw new MissingResourceException(
                        "Missing exact i18n bundle " + contribution.baseName()
                                + " for locale " + target.tag(),
                        contribution.baseName(), "");
            }
            return messages;
        }

        /**
         * effective bundle：仅用于运行期展示。按 catalog 回退链「源语言 → fallback → 目标语言」
         * 合并 exact 文件，后写入者覆盖前值，实现 目标语言 → en-US → zh-CN。
         * 整个回退链都没有任何物理文件时才抛 {@link MissingResourceException}。
         */
        public Map<String, String> loadEffective(Locale locale) {
            LocaleDescriptor target = catalog.resolve(locale);
            Map<String, String> merged = new LinkedHashMap<>();
            boolean loaded = false;
            List<LocaleDescriptor> chain = catalog.fallbackChain(target);
            for (int i = chain.size() - 1; i >= 0; i--) {
                Map<String, String> exact = safeExact(chain.get(i));
                if (!exact.isEmpty()) {
                    loaded = true;
                }
                merged.putAll(exact);
            }
            if (!loaded) {
                throw new MissingResourceException(
                        "Missing i18n bundle " + contribution.baseName()
                                + " for locale " + target.tag(),
                        contribution.baseName(), "");
            }
            return sortedMessages(merged);
        }

        /** 运行期展示语义 = effective。 */
        public Map<String, String> load(Locale locale) {
            return loadEffective(locale);
        }

        private Map<String, String> safeExact(LocaleDescriptor descriptor) {
            try {
                return loadExact(descriptor.toLocale());
            } catch (MissingResourceException ignored) {
                return Map.of();
            }
        }
    }

    private final Object lock = new Object();
    private final Supplier<List<InstalledPlugin>> installedPlugins;
    private final LocaleCatalog catalog;

    private volatile List<RegisteredBundle> activeSnapshot = List.of();
    private volatile List<RegisteredBundle> installedSnapshot = List.of();

    @Autowired
    public WebI18nBundleRegistry(PluginRegistry pluginRegistry,
                                 ObjectProvider<ExternalPluginInstaller> installer,
                                 LocaleCatalog catalog) {
        this(pluginRegistry, installedPluginSupplier(installer), catalog);
    }

    public WebI18nBundleRegistry(PluginRegistry pluginRegistry) {
        this(pluginRegistry, List::of, LocaleCatalog.defaultCatalog());
    }

    public WebI18nBundleRegistry(PluginRegistry pluginRegistry,
                                 ObjectProvider<ExternalPluginInstaller> installer) {
        this(pluginRegistry, installedPluginSupplier(installer), LocaleCatalog.defaultCatalog());
    }

    public WebI18nBundleRegistry(PluginRegistry pluginRegistry, Supplier<List<InstalledPlugin>> installedPlugins) {
        this(pluginRegistry, installedPlugins, LocaleCatalog.defaultCatalog());
    }

    WebI18nBundleRegistry(PluginRegistry pluginRegistry,
                          Supplier<List<InstalledPlugin>> installedPlugins,
                          LocaleCatalog catalog) {
        this.installedPlugins = installedPlugins != null ? installedPlugins : List::of;
        this.catalog = catalog == null ? LocaleCatalog.defaultCatalog() : catalog;
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            PixivFeaturePlugin plugin = registered.plugin();
            List<I18nContribution> contributions = plugin.i18n();
            if (!contributions.isEmpty()) {
                register(registered.id(), registered.classLoader(), contributions);
            }
        }
        refreshInstalledSnapshot();
    }

    private static Supplier<List<InstalledPlugin>> installedPluginSupplier(
            ObjectProvider<ExternalPluginInstaller> installer) {
        ExternalPluginInstaller value = installer == null ? null : installer.getIfAvailable();
        return value != null ? value::listInstalled : List::of;
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
            for (I18nContribution contribution : contributions) {
                validate(contribution, pluginId);
                if (!namespaces.add(contribution.namespace())) {
                    throw new IllegalStateException("duplicate i18n namespace: "
                            + contribution.namespace() + " (plugin: " + pluginId + ")");
                }
            }
            List<RegisteredBundle> materialized = contributions.stream()
                    .map(contribution -> materializeActiveBundle(pluginId, contribution, classLoader))
                    .toList();
            List<RegisteredBundle> next = new ArrayList<>(activeSnapshot);
            next.addAll(materialized);
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

    /** namespace → 已注册的纯宿主 bundle 快照，未注册返回 {@code null}。 */
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

    @Override
    public Optional<String> resolve(String namespace, Locale locale, String key) {
        if (namespace == null || namespace.isBlank() || key == null || key.isBlank()) {
            return Optional.empty();
        }
        RegisteredBundle registered = resolve(namespace);
        if (registered == null) {
            return Optional.empty();
        }
        String message = registered.load(locale).get(key);
        return message == null || message.isBlank() ? Optional.empty() : Optional.of(message);
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

    /** catalog 可见语言列表（source + supported），与运行期语言菜单一致。 */
    public List<LocaleDescriptor> supportedLocales() {
        return catalog.visibleLocales();
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
        try {
            installedSnapshot = installedBundles(installedPlugins.get());
        } catch (RuntimeException failure) {
            log.warn("Installed plugin i18n snapshot refresh failed; keeping the previous snapshot "
                    + "(failureType={}).", failure.getClass().getName());
        }
    }

    private List<RegisteredBundle> installedBundles(List<InstalledPlugin> installedPlugins) {
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
                    Map.of(),
                    artifact,
                    catalog));
        }
        return List.copyOf(bundles);
    }

    private RegisteredBundle materializeActiveBundle(
            String pluginId, I18nContribution contribution, ClassLoader classLoader) {
        Map<String, Map<String, String>> messagesByLocale = new LinkedHashMap<>();
        for (LocaleDescriptor descriptor : catalog.allLocales()) {
            try {
                messagesByLocale.put(
                        descriptor.tag(),
                        loadExactResource(contribution, classLoader, descriptor));
            } catch (MissingResourceException ignored) {
                // 保留既有语义：缺少某个语言物理文件时 exact 缺席，effective 沿回退链补齐；
                // 真正读取缺失 locale 时再抛 MissingResourceException。
            }
        }
        return new RegisteredBundle(pluginId, contribution, messagesByLocale, null, catalog);
    }

    private static Map<String, String> loadExactResource(
            I18nContribution contribution, ClassLoader classLoader, LocaleDescriptor descriptor) {
        String resourceName = resourceName(contribution.baseName(), descriptor.resourceSuffix());
        Map<String, String> values = readClassLoaderProperties(classLoader, resourceName);
        if (values == null) {
            throw new MissingResourceException(
                    "Missing exact plugin i18n bundle " + contribution.baseName() + " (" + resourceName + ")",
                    contribution.baseName(), "");
        }
        return sortedMessages(values);
    }

    private static Map<String, String> loadInstalledExact(Path artifact, String baseName, LocaleDescriptor descriptor) {
        String resourceName = resourceName(baseName, descriptor.resourceSuffix());
        Map<String, String> values = readArtifactProperties(artifact, resourceName);
        if (values == null) {
            throw new MissingResourceException("Missing installed plugin i18n bundle " + resourceName,
                    baseName, "");
        }
        Map<String, String> sorted = new LinkedHashMap<>();
        for (String key : new TreeSet<>(values.keySet())) {
            sorted.put(key, values.get(key));
        }
        return immutableMessages(sorted);
    }

    private static String resourceName(String baseName, String resourceSuffix) {
        String basePath = baseName.replace('.', '/');
        String suffix = resourceSuffix == null ? "" : resourceSuffix;
        return suffix.isEmpty() ? basePath + ".properties" : basePath + "_" + suffix + ".properties";
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

    private static Map<String, String> readClassLoaderProperties(ClassLoader classLoader, String resourceName) {
        try (InputStream in = classLoader.getResourceAsStream(resourceName)) {
            if (in == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(reader);
                Map<String, String> values = new LinkedHashMap<>();
                for (String key : properties.stringPropertyNames()) {
                    values.put(WebI18nService.normalizeKey(key), properties.getProperty(key));
                }
                return values;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot materialize plugin i18n bundle " + resourceName, e);
        }
    }

    private static Map<String, String> sortedMessages(Map<String, String> messages) {
        Map<String, String> sorted = new LinkedHashMap<>();
        for (String key : new TreeSet<>(messages.keySet())) {
            sorted.put(key, messages.get(key));
        }
        return immutableMessages(sorted);
    }

    private static Map<String, String> immutableMessages(Map<String, String> messages) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(messages));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
