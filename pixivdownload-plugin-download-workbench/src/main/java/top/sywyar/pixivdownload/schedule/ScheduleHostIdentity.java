package top.sywyar.pixivdownload.schedule;

/** 由插件组合根注入的计划宿主 publication 身份；通用调度代码不认识具体插件 id。 */
public record ScheduleHostIdentity(String featurePluginId) {

    public ScheduleHostIdentity {
        if (featurePluginId == null || featurePluginId.isBlank()) {
            throw new IllegalArgumentException("schedule host feature plugin id must not be blank");
        }
        featurePluginId = featurePluginId.trim();
    }
}
