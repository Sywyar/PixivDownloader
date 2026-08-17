package top.sywyar.pixivdownload.plugin.api.notification;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 宿主向通知介质暴露的只读模板快照。 */
public interface NotificationTemplateCatalog {

    /**
     * 查询并返回对应结果。
     *
     * @param scenarioId 场景标识
     * @param medium 介质
     * @param locale 语言区域
     * @return 匹配的可选值
     */
    Optional<NotificationTemplateContribution> find(String scenarioId, String medium, Locale locale);

    /**
     * 执行场景标识集合并返回结果。
     *
     * @param medium 介质
     * @return 方法返回的集合
     */
    Set<String> scenarioIds(String medium);
}
