package top.sywyar.pixivdownload.plugin.api.web;

import java.util.List;

/** 宿主向插件消费者暴露的活动 Web UI 槽位只读快照。 */
@FunctionalInterface
public interface WebUiSlotCatalog {

    List<WebUiSlotContribution> uiSlots();
}
