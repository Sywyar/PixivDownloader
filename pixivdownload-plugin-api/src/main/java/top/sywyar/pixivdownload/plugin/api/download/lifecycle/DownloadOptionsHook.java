package top.sywyar.pixivdownload.plugin.api.download.lifecycle;

import java.util.Map;

/**
 * 可选的执行前选项扩展。宿主按 order、可信插件 ID、Bean 名稳定排序，固定本次调用的代理快照。
 * 只开放类型明确提供的选项；返回值须再次通过类型校验。不得改身份、凭据、根目录或鉴权结论。
 * 任一 hook 失败或在调用时已撤回，则拒绝本次执行，不能跳过规则继续下载。
 */
public interface DownloadOptionsHook {
    /**
     * {@return 越小越先执行，同序按可信 owner 和 Bean 名排序}
     */
    default int order() { return 0; }
    /**
     * 按当前执行身份调整下载选项，并返回供后续流程使用的完整选项。
     *
     * @param attempt 执行身份
     * @param options 当前不可变选项
     * @return 完整的替换选项，不能返回 null
     */
    Map<String, String> customize(DownloadAttempt attempt, Map<String, String> options);
}
