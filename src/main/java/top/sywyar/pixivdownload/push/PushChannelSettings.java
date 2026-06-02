package top.sywyar.pixivdownload.push;

/**
 * 单个推送通道的<b>不可变设置快照</b>（各通道形态不同，由各自的实现类承载）。两条用途：
 * <ul>
 *   <li>各通道的 {@code Config.toSettings()} 产出"当前已保存配置"的快照，供 {@link PushChannel#send} 使用；</li>
 *   <li>GUI 测试入口把"当前表单值"（尚未保存）包装成快照，经 {@link PushService#test} →
 *       {@link PushChannel#sendTest} 发送，<b>无需先落盘</b>。</li>
 * </ul>
 * 与 AI 的 {@link top.sywyar.pixivdownload.ai.AiClientSettings} 思路一致：把"配置来源"与"渲染发送"解耦，
 * 从而既支持热重载，又支持 GUI 保存前测试。
 */
public interface PushChannelSettings {

    /** 这份设置属于哪个通道；{@link PushService#test} 据此路由到对应实现。 */
    PushChannelType type();

    /** 必填字段是否齐全（可发送）。 */
    boolean isComplete();
}
