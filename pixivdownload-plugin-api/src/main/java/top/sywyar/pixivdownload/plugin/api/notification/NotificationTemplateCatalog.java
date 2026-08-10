package top.sywyar.pixivdownload.plugin.api.notification;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 宿主向通知介质暴露的只读模板快照。 */
public interface NotificationTemplateCatalog {

    Optional<NotificationTemplateContribution> find(String scenarioId, String medium, Locale locale);

    Set<String> scenarioIds(String medium);
}
