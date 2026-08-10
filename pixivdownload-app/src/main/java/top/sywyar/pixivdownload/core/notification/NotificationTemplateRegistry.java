package top.sywyar.pixivdownload.core.notification;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.notification.ImmutableNotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

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
