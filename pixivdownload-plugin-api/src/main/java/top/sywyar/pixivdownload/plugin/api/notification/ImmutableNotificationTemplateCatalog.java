package top.sywyar.pixivdownload.plugin.api.notification;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** 一次 publication 物化后的不可变通知模板目录。 */
public final class ImmutableNotificationTemplateCatalog implements NotificationTemplateCatalog {

    private record Key(String scenarioId, String medium, Locale locale) {
    }

    private static final Comparator<NotificationTemplateContribution> ORDER = Comparator
            .comparing(NotificationTemplateContribution::scenarioId)
            .thenComparing(NotificationTemplateContribution::medium)
            .thenComparing(template -> template.locale().toLanguageTag());

    private final Map<Key, NotificationTemplateContribution> templates;
    private final List<NotificationTemplateContribution> ordered;
    private final Map<String, Set<String>> scenarioIdsByMedium;

    /**
     * 从通知模板集合创建不可变目录。
     *
     * @param contributions 通知模板集合
     */
    public ImmutableNotificationTemplateCatalog(
            Collection<NotificationTemplateContribution> contributions) {
        if (contributions == null) {
            throw new IllegalArgumentException("notification templates must not be null");
        }
        Map<Key, NotificationTemplateContribution> byKey = new LinkedHashMap<>();
        for (NotificationTemplateContribution contribution : List.copyOf(contributions)) {
            NotificationTemplateContribution copy = copy(contribution);
            Key key = new Key(copy.scenarioId(), copy.medium(), copy.locale());
            NotificationTemplateContribution previous = byKey.putIfAbsent(key, copy);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate notification template: "
                        + copy.scenarioId() + "/" + copy.medium() + "/"
                        + copy.locale().toLanguageTag());
            }
        }
        templates = Map.copyOf(byKey);
        ordered = byKey.values().stream().sorted(ORDER).toList();
        scenarioIdsByMedium = indexScenarioIds(ordered);
    }

    /**
     * 返回空的 {@code ImmutableNotificationTemplateCatalog} 实例。
     *
     * @return 方法返回的 {@code ImmutableNotificationTemplateCatalog} 实例
     */
    public static ImmutableNotificationTemplateCatalog empty() {
        return new ImmutableNotificationTemplateCatalog(List.of());
    }

    @Override
    public Optional<NotificationTemplateContribution> find(
            String scenarioId,
            String medium,
            Locale locale) {
        if (scenarioId == null || scenarioId.isBlank() || medium == null || medium.isBlank()) {
            return Optional.empty();
        }
        String normalizedScenario = scenarioId.trim();
        String normalizedMedium = medium.trim();
        Locale requested = locale == null ? Locale.getDefault() : Locale.forLanguageTag(locale.toLanguageTag());
        NotificationTemplateContribution exact = templates.get(
                new Key(normalizedScenario, normalizedMedium, requested));
        if (exact != null) {
            return Optional.of(exact);
        }
        return ordered.stream()
                .filter(template -> template.scenarioId().equals(normalizedScenario))
                .filter(template -> template.medium().equals(normalizedMedium))
                .filter(template -> template.locale().getLanguage().equalsIgnoreCase(requested.getLanguage()))
                .findFirst();
    }

    @Override
    public Set<String> scenarioIds(String medium) {
        if (medium == null || medium.isBlank()) {
            return Set.of();
        }
        return scenarioIdsByMedium.getOrDefault(medium.trim(), Set.of());
    }

    private static NotificationTemplateContribution copy(NotificationTemplateContribution value) {
        if (value == null) {
            throw new IllegalArgumentException("notification template must not be null");
        }
        return new NotificationTemplateContribution(
                value.scenarioId(), value.medium(), value.locale(),
                value.titleTemplate(), value.bodyTemplate());
    }

    private static Map<String, Set<String>> indexScenarioIds(
            List<NotificationTemplateContribution> contributions) {
        Map<String, Set<String>> mutable = new LinkedHashMap<>();
        for (NotificationTemplateContribution contribution : contributions) {
            mutable.computeIfAbsent(contribution.medium(), ignored -> new TreeSet<>())
                    .add(contribution.scenarioId());
        }
        Map<String, Set<String>> immutable = new LinkedHashMap<>();
        mutable.forEach((medium, ids) -> immutable.put(
                medium, Collections.unmodifiableSet(new LinkedHashSet<>(ids))));
        return Collections.unmodifiableMap(immutable);
    }
}
