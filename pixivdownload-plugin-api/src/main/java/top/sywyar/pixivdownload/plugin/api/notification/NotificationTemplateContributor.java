package top.sywyar.pixivdownload.plugin.api.notification;

import java.util.List;

/** 插件 child context 通过本稳定契约一次性贡献其拥有的通知模板纯值。 */
@FunctionalInterface
public interface NotificationTemplateContributor {

    /**
     * 返回对应值。
     *
     * @return 方法返回的列表
     */
    List<NotificationTemplateContribution> notificationTemplates();
}
