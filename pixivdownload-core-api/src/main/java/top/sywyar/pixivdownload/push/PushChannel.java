package top.sywyar.pixivdownload.push;

import java.util.List;

/**
 * 推送通道的中性发送契约。每个实现只拥有自己的设置与协议投影，并声明可接收的消息格式；
 * 格式协商和跨格式转换由调用方在进入通道前完成。
 */
public interface PushChannel {

    /**
     * 本通道的稳定标识。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    PushChannelId type();

    /**
     * 本通道是否具备发送所需设置。实现只检查自己的设置。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isConfigured();

    /**
     * 本通道支持的发送格式，按优先级从高到低排列。列表末位必须包含
     * {@link PushFormat#PLAIN_TEXT}，作为格式协商的稳定兜底。
     *
     * @return 方法返回的列表
     */
    List<PushFormat> supportedFormats();

    /**
     * 用本通道当前设置发送一条已渲染消息。发送失败收敛为 {@link PushResult#failed}，不向调用方抛出。
     *
     * @param message 消息
     * @return 方法返回的 {@code PushResult} 实例
     */
    PushResult send(RenderedMessage message);

    /**
     * 用调用方提供的设置快照发送已渲染消息。设置类型不匹配或发送失败时返回
     * {@link PushResult#failed}，不向调用方抛出。
     *
     * @param settings 设置
     * @param message 消息
     * @return 方法返回的 {@code PushResult} 实例
     */
    PushResult sendTest(PushChannelSettings settings, RenderedMessage message);
}
