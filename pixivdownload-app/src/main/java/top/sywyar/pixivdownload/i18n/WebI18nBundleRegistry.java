package top.sywyar.pixivdownload.i18n;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.PluginRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Web i18n bundle 注册中心。收集各插件声明的 {@link I18nContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁
 * （{@code /api/i18n/**} 在每次请求上读取）。
 * <p>
 * bundle 解析经声明方插件的 ClassLoader（{@link RegisteredBundle#classLoader()}）：
 * 现阶段全部内置插件共用应用 ClassLoader，解析结果与退役前的静态 map 完全一致；
 * 物理拆分为插件 jar 后，各插件 properties 经各自 ClassLoader 解析、卸载时随之清缓存。
 * <p>
 * namespace 全局唯一：跨插件用不同 baseName 指向同一 namespace 会让
 * {@code /api/i18n/messages/{namespace}} 解析不确定，故 namespace 冲突
 * （跨插件与同一批次内）一律在注册期拒绝，使应用启动失败而不是带病运行。
 */
@Component
public class WebI18nBundleRegistry {

    /** 一条已注册 namespace、声明方插件与解析用 ClassLoader。 */
    public record RegisteredBundle(String pluginId, I18nContribution contribution, ClassLoader classLoader) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredBundle> snapshot = List.of();

    public WebI18nBundleRegistry(PluginRegistry pluginRegistry) {
        for (PixivFeaturePlugin plugin : pluginRegistry.plugins()) {
            List<I18nContribution> contributions = plugin.i18n();
            if (!contributions.isEmpty()) {
                register(plugin.id(), plugin.getClass().getClassLoader(), contributions);
            }
        }
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
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("i18n already registered for plugin: " + pluginId);
            }
            Set<String> namespaces = snapshot.stream()
                    .map(registered -> registered.contribution().namespace())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredBundle> next = new ArrayList<>(snapshot);
            for (I18nContribution contribution : contributions) {
                validate(contribution, pluginId);
                if (!namespaces.add(contribution.namespace())) {
                    throw new IllegalStateException("duplicate i18n namespace: "
                            + contribution.namespace() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredBundle(pluginId, contribution, classLoader));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部 i18n namespace。插件可以不声明任何 namespace，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部已注册 bundle 的不可变快照。 */
    public List<RegisteredBundle> bundles() {
        return snapshot;
    }

    /** namespace → 已注册 bundle（含 baseName 与解析用 ClassLoader），未注册返回 {@code null}。 */
    public RegisteredBundle resolve(String namespace) {
        for (RegisteredBundle registered : snapshot) {
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
        return snapshot.stream()
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
}
