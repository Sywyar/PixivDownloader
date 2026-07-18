package top.sywyar.pixivdownload.core.push;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushDispatcher;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushFormatConverter;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 推送派发器——框架的<b>唯一入口</b>。业务侧只需构造一个 {@link PushMessage} 调 {@link #push}，
 * 由它广播给所有"已启用且已配置"的通道。
 * <p>
 * 通过 {@link PushChannelRegistry} 读取活动插件贡献的全部通道，本类<b>不感知</b>任何具体通道：
 * 新增通道不改动此类。整体 best-effort：
 * 单个通道的失败 / 异常被隔离，绝不影响其它通道，也<b>绝不向调用方抛出</b>。
 * <p>
 * 派发前先由 {@link PushFormatConverter} 按每个通道 {@link PushChannel#supportedFormats() 支持的格式}与
 * 消息源格式协商目标格式、把正文转换好（不可转换时尽力降级为纯文本），通道只渲染已定型的
 * {@link RenderedMessage}。
 */
@Service
public class PushService implements PushDispatcher {

    private final PushChannelRegistry channelRegistry;
    private final PushFormatConverter formatConverter;

    public PushService(PushChannelRegistry channelRegistry,
                       PushFormatConverter formatConverter) {
        this.channelRegistry = channelRegistry;
        this.formatConverter = formatConverter;
    }

    /**
     * 向所有已启用且已配置的通道广播一条消息。
     *
     * @return 每个参与通道一条 {@link PushResult}；无可用通道时返回空列表。绝不抛异常。
     */
    public List<PushResult> push(PushMessage message) {
        if (message == null) {
            return List.of();
        }
        List<PushResult> results = new ArrayList<>();
        for (PushChannelRegistry.PreparedChannel channel : channelRegistry.preparedChannels()) {
            PushResult result = dispatchConfigured(channel, message);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    /**
     * 向指定类型的单个通道发送（定向通知 / 后续测试入口）。无此通道或该通道未配置时返回
     * {@link PushResult.Status#SKIPPED}。绝不抛异常。
     */
    public PushResult push(PushChannelType type, PushMessage message) {
        if (type == null) {
            return PushResult.skipped(null, PushResult.DETAIL_CHANNEL_UNAVAILABLE);
        }
        PushChannelRegistry.PreparedChannel channel;
        try {
            channel = channelRegistry.preparedByType(type).orElse(null);
        } catch (RuntimeException ignored) {
            return PushResult.skipped(type, PushResult.DETAIL_CHANNEL_UNAVAILABLE);
        }
        if (channel == null) {
            return PushResult.skipped(type, PushResult.DETAIL_CHANNEL_UNAVAILABLE);
        }
        return dispatchTargeted(channel, message == null ? PushMessage.of("", "") : message);
    }

    /**
     * 测试路径：用调用方传入的<b>临时设置</b>（GUI 当前表单值，尚未保存）逐个发送一条测试消息。
     * <p>
     * 与 {@link #push} 不同，本方法不读取任何已保存的
     * 通道配置——便于用户在启用 / 保存前先验证连通性，与其它介质的保存前测试语义一致。
     * 设置不完整或找不到对应通道时该项记 {@link PushResult.Status#SKIPPED}。
     * 绝不抛异常。
     *
     * @param settingsList 每个元素是某通道的临时设置快照（仅含调用方希望测试的通道）
     * @return 与 {@code settingsList} 一一对应的发送结果
     */
    public List<PushResult> test(List<PushChannelSettings> settingsList, PushMessage message) {
        if (settingsList == null || settingsList.isEmpty()) {
            return List.of();
        }
        PushMessage payload = message == null ? PushMessage.of("", "") : message;
        List<PushResult> results = new ArrayList<>();
        for (PushChannelSettings settings : settingsList) {
            if (settings == null) {
                results.add(PushResult.failed(null, PushResult.DETAIL_UNEXPECTED_ERROR));
                continue;
            }
            PushChannelType type = null;
            try {
                type = settings.type();
                PushChannelRegistry.PreparedChannel channel =
                        channelRegistry.preparedByType(type).orElse(null);
                if (channel == null) {
                    results.add(PushResult.skipped(type, PushResult.DETAIL_CHANNEL_UNAVAILABLE));
                    continue;
                }
                if (!settings.isComplete()) {
                    results.add(PushResult.skipped(type, PushResult.DETAIL_SETTINGS_INCOMPLETE));
                    continue;
                }
                results.add(dispatchTest(channel, settings, payload));
            } catch (RuntimeException ignored) {
                results.add(PushResult.failed(type, PushResult.DETAIL_UNEXPECTED_ERROR));
            }
        }
        return results;
    }

    /**
     * 双保险：通道实现已承诺 best-effort 不抛，这里仍兜一层 {@link RuntimeException}，确保广播不会因单个
     * 通道的意外异常中断。
     */
    private PushResult dispatchConfigured(
            PushChannelRegistry.PreparedChannel prepared,
            PushMessage message) {
        PushChannel channel = prepared.channel();
        try {
            if (!channel.isConfigured()) {
                return null;
            }
            return normalizeResult(prepared, channel.send(renderFor(channel, message)));
        } catch (RuntimeException ignored) {
            return PushResult.failed(prepared.type(), PushResult.DETAIL_UNEXPECTED_ERROR);
        }
    }

    private PushResult dispatchTargeted(
            PushChannelRegistry.PreparedChannel prepared,
            PushMessage message) {
        PushChannel channel = prepared.channel();
        try {
            if (!channel.isConfigured()) {
                return PushResult.skipped(prepared.type(), PushResult.DETAIL_CHANNEL_NOT_CONFIGURED);
            }
            return normalizeResult(prepared, channel.send(renderFor(channel, message)));
        } catch (RuntimeException ignored) {
            return PushResult.failed(prepared.type(), PushResult.DETAIL_UNEXPECTED_ERROR);
        }
    }

    private PushResult dispatchTest(
            PushChannelRegistry.PreparedChannel prepared,
            PushChannelSettings settings,
            PushMessage message) {
        PushChannel channel = prepared.channel();
        try {
            return normalizeResult(prepared, channel.sendTest(settings, renderFor(channel, message)));
        } catch (RuntimeException ignored) {
            return PushResult.failed(prepared.type(), PushResult.DETAIL_UNEXPECTED_ERROR);
        }
    }

    /** 为指定通道协商目标格式并把正文转换好。 */
    private RenderedMessage renderFor(PushChannel channel, PushMessage message) {
        PushFormat target = formatConverter.negotiate(channel.supportedFormats(), message.sourceFormat());
        return formatConverter.render(message, target);
    }

    private static PushResult normalizeResult(
            PushChannelRegistry.PreparedChannel prepared,
            PushResult result) {
        if (result == null || result.status() == null) {
            return PushResult.failed(prepared.type(), PushResult.DETAIL_UNEXPECTED_ERROR);
        }
        if (result.channel() == prepared.type()) {
            return result;
        }
        return new PushResult(prepared.type(), result.status(), result.detail());
    }
}
