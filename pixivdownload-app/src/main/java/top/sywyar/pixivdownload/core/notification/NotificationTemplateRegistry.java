package top.sywyar.pixivdownload.core.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.notification.ImmutableNotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 宿主拥有的通知模板 publication 注册表；只保存 plugin-api 纯值快照。 */
@Component
public class NotificationTemplateRegistry implements NotificationTemplateCatalog {

    private record Owner(String pluginId, long publicationId) {
    }

    private final Object lock = new Object();
    private Map<Owner, List<NotificationTemplateContribution>> publications = Map.of();
    private volatile ImmutableNotificationTemplateCatalog snapshot =
            ImmutableNotificationTemplateCatalog.empty();

    public NotificationTemplateRegistry() {
        this(List.of());
    }

    /** 根上下文场景 owner 在宿主启动时贡献模板；外置插件仍经 publication adapter 动态注册。 */
    @Autowired
    public NotificationTemplateRegistry(List<NotificationTemplateContributor> initialContributors) {
        List<NotificationTemplateContribution> templates = new ArrayList<>();
        if (initialContributors != null) {
            for (NotificationTemplateContributor contributor : initialContributors) {
                if (contributor != null) {
                    templates.addAll(contributor.notificationTemplates());
                }
            }
        }
        if (!templates.isEmpty()) {
            registerPrepared("core", 1L, templates);
        }
    }

    public void registerPrepared(
            String pluginId,
            long publicationId,
            List<NotificationTemplateContribution> templates) {
        Owner owner = owner(pluginId, publicationId);
        List<NotificationTemplateContribution> copied = List.copyOf(templates);
        synchronized (lock) {
            if (publications.containsKey(owner)) {
                throw new IllegalStateException("notification templates already published: " + owner);
            }
            Map<Owner, List<NotificationTemplateContribution>> next = new LinkedHashMap<>(publications);
            next.put(owner, copied);
            ImmutableNotificationTemplateCatalog nextSnapshot = catalog(next);
            publications = Map.copyOf(next);
            snapshot = nextSnapshot;
        }
    }

    public void unregisterPrepared(String pluginId, long publicationId) {
        Owner owner = owner(pluginId, publicationId);
        synchronized (lock) {
            if (!publications.containsKey(owner)) {
                return;
            }
            Map<Owner, List<NotificationTemplateContribution>> next = new LinkedHashMap<>(publications);
            next.remove(owner);
            publications = Map.copyOf(next);
            snapshot = catalog(next);
        }
    }

    @Override
    public Optional<NotificationTemplateContribution> find(
            String scenarioId,
            String medium,
            Locale locale) {
        return snapshot.find(scenarioId, medium, locale);
    }

    @Override
    public Set<String> scenarioIds(String medium) {
        return snapshot.scenarioIds(medium);
    }

    private static Owner owner(String pluginId, long publicationId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("notification template plugin id must not be blank");
        }
        if (publicationId <= 0L) {
            throw new IllegalArgumentException("notification template publication id must be positive");
        }
        return new Owner(pluginId, publicationId);
    }

    private static ImmutableNotificationTemplateCatalog catalog(
            Map<Owner, List<NotificationTemplateContribution>> publications) {
        List<NotificationTemplateContribution> templates = new ArrayList<>();
        publications.values().forEach(templates::addAll);
        return new ImmutableNotificationTemplateCatalog(templates);
    }
}
