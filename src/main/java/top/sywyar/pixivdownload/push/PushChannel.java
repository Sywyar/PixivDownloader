package top.sywyar.pixivdownload.push;

/**
 * 推送通道 SPI——整个框架的解耦缝。每个通道（Bark / 钉钉 / Telegram …）实现为一个 Spring {@code @Component}，
 * 框架通过 {@code List<PushChannel>} 注入自动发现它们，{@link PushService} 不感知任何具体通道。
 * <p>
 * <b>新增一个通道 = 新增一个实现类（+ 它自己的配置类）</b>，无需改动核心模型或派发器。各实现只读取<b>自身</b>
 * 的配置切片，不依赖其它通道，互不耦合（pushbot 中"一个 diy_send + 每通道一份 JSON 模板"的 Java 等价物）。
 */
public interface PushChannel {

    /** 本通道类型。 */
    PushChannelType type();

    /**
     * 本通道是否已配置完整、可发送。只读取自身配置（如自身的 {@code enabled} 与必填字段），
     * 不读其它通道配置；{@link PushService} 据此决定是否把消息派发给本通道。
     */
    boolean isConfigured();

    /**
     * 用<b>本通道当前已保存的配置</b>发送一条消息。<b>best-effort 契约</b>：实现<b>绝不</b>抛异常——一切失败
     * （网络、序列化、非 2xx）都收敛为 {@link PushResult#failed}，保证广播不会被单个通道拖垮。
     */
    PushResult send(PushMessage message);

    /**
     * 用一次性的<b>临时设置</b>发送（GUI 尚未保存配置时的测试路径，见 {@link PushService#test}）。同样遵守
     * best-effort 契约不抛异常；{@code settings} 形态须与本通道匹配（类型不符时返回 {@link PushResult#failed}）。
     */
    PushResult sendTest(PushChannelSettings settings, PushMessage message);
}
