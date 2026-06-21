package top.sywyar.pixivdownload.scripts;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.PluginRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 油猴脚本来源注册中心。收集各插件声明的 {@link UserscriptContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用。
 * <p>
 * 脚本扫描经声明方插件的 ClassLoader（{@link RegisteredUserscript#classLoader()}）：
 * 现阶段唯下载工作台插件声明、共用应用 ClassLoader，扫描结果与退役前的全局
 * {@code classpath:/static/userscripts/*.user.js} 扫描完全一致；物理拆分为插件 jar 后，
 * 脚本随插件 ClassLoader 解析（见 {@link ScriptRegistry}）。
 * <p>
 * 扫描模式（{@code classpathPattern}）全局唯一：两个声明指向同一模式会重复扫描，
 * 故模式冲突（跨插件与同一批次内）一律在注册期拒绝。
 */
@Component
public class UserscriptRegistry {

    /** 一条已注册油猴脚本来源、声明方插件与扫描用 ClassLoader。 */
    public record RegisteredUserscript(String pluginId, UserscriptContribution contribution,
                                       ClassLoader classLoader) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredUserscript> snapshot = List.of();

    public UserscriptRegistry(PluginRegistry pluginRegistry) {
        for (PixivFeaturePlugin plugin : pluginRegistry.plugins()) {
            List<UserscriptContribution> contributions = plugin.userscripts();
            if (!contributions.isEmpty()) {
                register(plugin.id(), plugin.getClass().getClassLoader(), contributions);
            }
        }
    }

    /**
     * 注册一个插件声明的全部油猴脚本来源。同一 pluginId 重复注册、声明非法，
     * 或扫描模式与已注册项冲突都立即抛出，使应用启动失败而不是带病运行。
     */
    public void register(String pluginId, ClassLoader classLoader, List<UserscriptContribution> contributions) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("userscript contribution without pluginId");
        }
        if (classLoader == null) {
            throw new IllegalStateException("userscript contribution without classLoader (plugin: "
                    + pluginId + ")");
        }
        if (contributions == null || contributions.isEmpty()) {
            throw new IllegalStateException("empty userscript contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("userscripts already registered for plugin: " + pluginId);
            }
            Set<String> patterns = snapshot.stream()
                    .map(registered -> registered.contribution().classpathPattern())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredUserscript> next = new ArrayList<>(snapshot);
            for (UserscriptContribution contribution : contributions) {
                validate(contribution, pluginId);
                if (!patterns.add(contribution.classpathPattern())) {
                    throw new IllegalStateException("duplicate userscript pattern: "
                            + contribution.classpathPattern() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredUserscript(pluginId, contribution, classLoader));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部油猴脚本来源。插件可以不声明任何来源，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部已注册油猴脚本来源的不可变快照。 */
    public List<RegisteredUserscript> userscripts() {
        return snapshot;
    }

    private static void validate(UserscriptContribution contribution, String pluginId) {
        if (contribution == null) {
            throw new IllegalStateException("null userscript contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(contribution.pluginId())) {
            throw new IllegalStateException("userscript pluginId mismatch: declared "
                    + contribution.pluginId() + " under plugin " + pluginId);
        }
        if (contribution.classpathPattern() == null || contribution.classpathPattern().isBlank()) {
            throw new IllegalStateException("userscript contribution without classpath pattern (plugin: "
                    + pluginId + ")");
        }
    }
}
