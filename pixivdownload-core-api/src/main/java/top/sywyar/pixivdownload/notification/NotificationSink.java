package top.sywyar.pixivdownload.notification;

import java.util.Locale;
import java.util.Map;

/**
 * 通知<b>介质</b>的 SPI。宿主注册并遍历活动实现；新增介质不改变通知编排契约。
 *
 * <p>实现需满足两条不变量：
 * <ul>
 *   <li>{@link #deliver} 必须 <b>best-effort、绝不抛</b>：内部 {@code try/catch} 兜住一切异常并记日志，
 *       单介质失败绝不拖累其它介质，也绝不向业务调用方抛出。</li>
 *   <li>{@link #verifyRenderable} 在本介质缺少某场景的渲染资源时<b>抛异常</b>，供「成对 / 齐全维护」守护测试
 *       做<b>静态</b>校验——只检查资源是否齐备，<b>不</b>发送任何网络请求。</li>
 * </ul>
 * 渲染产物与日志<b>绝不含</b> cookie / PHPSESSID / 任何凭证。
 */
public interface NotificationSink {

    /** 介质标识（日志用，如 {@code "mail"} / {@code "push"}）。 */
    String medium();

    /**
     * 渲染并下发指定场景的通知。best-effort：一切失败仅记日志，<b>绝不抛</b>。
     *
     * @param scenario     业务场景（提供 canonical id 与级别）
     * @param locale       目标语言（调度器自发通知无 HTTP 上下文，必须显式传入）
     * @param placeholders 运行期占位符（各介质共用同一套键，含义一致）
     */
    void deliver(NotificationScenario scenario, Locale locale, Map<String, String> placeholders);

    /**
     * 静态校验本介质是否具备渲染该场景的全部资源（模板 / i18n key），缺失时抛异常。
     * 供守护测试遍历「场景 × 介质」逐一调用；<b>不</b>发送网络请求。
     *
     * @throws RuntimeException 渲染资源缺失
     */
    void verifyRenderable(NotificationScenario scenario);
}
